package build.raft.app.data

import build.raft.app.RaftThreadMessageItem
import build.raft.app.RaftThreadPendingSendContext
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.ReplyCacheProvenance
import build.raft.app.ThreadMessageDeliveryFailed
import build.raft.app.ThreadMessageDeliverySending
import build.raft.app.ThreadRouteIdentity
import build.raft.app.network.RaftRequestTimeoutException
import build.raft.app.network.retry.RetryPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Every test here is runBlocking + CompletableDeferred barriers + injected wakeup capture, with
// zero reliance on virtual time.
class ThreadPendingSendDrainerTest {
    /** Every drainer scope this class made: cancelled after each test so none outlives it. */
    private val drainerScopes = mutableListOf<CoroutineScope>()

    // The in-flight registry is process-global and every case sends "random-1": a drain still
    // running from an earlier case (or class) would make this case skip its row as "in flight"
    // (seen once in CI as emptySuccessResponseIsTreatedAsTerminalFailure). Start and end clean.
    @BeforeTest
    fun startWithEmptyInFlightRegistry() {
        ThreadPendingSendInFlightRegistry.clearForTests()
    }

    @AfterTest
    fun clearInFlightRegistry() {
        drainerScopes.forEach { it.cancel() }
        drainerScopes.clear()
        ThreadPendingSendInFlightRegistry.clearForTests()
    }

    @Test
    fun drainsFailedRowWithSameRandomIdAndConsumesOnSuccess() = runBlocking {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = true) }
        val send = RecordingSend()
        val sent = mutableListOf<String>()
        val drainer = drainer(store = store, send = send.handler, onSent = { localId, _, _, _ -> sent += localId })

        drainer.drainOnceForTests("send_failed")

        assertEquals(listOf("random-1"), send.randomIds)
        assertEquals(listOf("random-1"), store.consumedRandomIds)
        assertEquals(listOf("local-1"), sent)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun storeStartAdoptsSendingOrphansButNeverAutoSendsFailedRows() = runBlocking {
        val store =
            FakeDrainStore().apply {
                rows += pendingMessage("local-orphan", "random-orphan", failed = false)
                rows +=
                    pendingMessage("local-failed", "random-failed", failed = true)
                        .copy(pendingSendErrorCode = "thread_send_network")
            }
        val send = RecordingSend()
        val drainer = drainer(store = store, send = send.handler)

        drainer.drainOnceForTests("start")

        // The orphaned "sending" row is adopted and resent; the failed row stays untouched —
        // process recreation alone must never auto-send (the live-trigger drain below can).
        assertEquals(listOf("random-orphan"), send.randomIds)
        assertEquals(listOf("local-orphan" to "thread_send_adopted_stale"), store.markFailedCalls)
        assertEquals(listOf("random-orphan"), store.consumedRandomIds)
        assertEquals(listOf("local-failed"), store.rows.map { it.id })

        drainer.drainOnceForTests("authoritative_online")

        assertEquals(listOf("random-orphan", "random-failed"), send.randomIds)
        assertEquals(listOf("random-orphan", "random-failed"), store.consumedRandomIds)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun adoptsStaleSendingRowWhoseOwningAttemptDied() = runBlocking {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = false) }
        val send = RecordingSend()
        val drainer = drainer(store = store, send = send.handler)

        drainer.drainOnceForTests("start")

        // The orphaned "sending" row is flipped to failed BEFORE the resend so the UI keeps an
        // explicit retry affordance even if every automatic attempt is later exhausted.
        assertEquals(listOf("local-1" to "thread_send_adopted_stale"), store.markFailedCalls)
        assertEquals(listOf("random-1"), send.randomIds)
        assertEquals(listOf("random-1"), store.consumedRandomIds)
    }

    @Test
    fun notifiesRowFailedWhenAdoptingStaleSendingRow() = runBlocking {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = false) }
        val send = RecordingSend()
        val failedCallbacks = mutableListOf<Pair<String, String>>()
        val drainer =
            drainer(
                store = store,
                send = send.handler,
                onRowFailed = { localId, _, _, errorCode, _ ->
                    failedCallbacks += localId to errorCode.orEmpty()
                },
            )

        drainer.drainOnceForTests("start")

        // The open page hot-updates off this callback, so the adopted row must surface as failed
        // without requiring a page re-entry (#1134 cell 3 follow-up).
        assertEquals(listOf("local-1" to "thread_send_adopted_stale"), failedCallbacks)
    }

    @Test
    fun notifiesRowFailedOnlyWhenDrainFailureIsTerminal() = runBlocking {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = true) }
        val send = RecordingSend()
        send.results += ThreadApiResult.Failure(code = 400)
        val failedCallbacks = mutableListOf<Pair<String, String>>()
        val drainer =
            drainer(
                store = store,
                send = send.handler,
                onRowFailed = { localId, _, _, errorCode, _ ->
                    failedCallbacks += localId to errorCode.orEmpty()
                },
            )

        // Failed rows are excluded on cold-start triggers; a realtime trigger drives the retry.
        drainer.drainOnceForTests("realtime")

        // http_400 is non-retryable, so the first failure is terminal and notifies once.
        assertEquals(listOf("local-1" to "http_400"), failedCallbacks)
    }

    @Test
    fun skipsRowWhoseRandomIdHasALiveInProcessAttempt() = runBlocking {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = true) }
        val send = RecordingSend()
        val drainer = drainer(store = store, send = send.handler)

        ThreadPendingSendInFlightRegistry.begin("random-1")
        drainer.drainOnceForTests("realtime")

        assertEquals(emptyList(), send.randomIds)
        assertEquals(emptyList(), store.consumedRandomIds)
        assertEquals(emptyList(), store.markFailedCalls)

        // Once the live attempt ends, the same row becomes drain-eligible again.
        ThreadPendingSendInFlightRegistry.end("random-1")
        drainer.drainOnceForTests("realtime")
        assertEquals(listOf("random-1"), send.randomIds)
    }

    @Test
    fun retryableFailureBacksOffAndRetriesWithSameRandomIdUntilSuccess() = runBlocking {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = true) }
        val send = RecordingSend()
        send.results += ThreadApiResult.Failure(code = 429)
        send.results += ThreadApiResult.Success(successPage())
        val wakeups = mutableListOf<Long>()
        val drainer = drainer(store = store, send = send.handler, wakeups = wakeups)

        drainer.drainOnceForTests("send_failed")

        assertEquals(listOf("random-1"), send.randomIds)
        assertEquals(listOf("local-1" to "http_429"), store.markFailedCalls)
        assertEquals(listOf(0L), wakeups)
        assertEquals(1, drainer.recordedAttemptsForTests("local-1"))
        assertEquals(emptyList(), store.consumedRandomIds)

        // The backoff wakeup fires (driven manually here; production arms a delayed re-trigger).
        drainer.drainOnceForTests("backoff")

        assertEquals(listOf("random-1", "random-1"), send.randomIds)
        assertEquals(listOf("random-1"), store.consumedRandomIds)
        assertEquals(0, drainer.recordedAttemptsForTests("local-1"))
    }

    @Test
    fun nonRetryableFailureStopsAfterSingleAttemptAndLeavesRowFailed() = runBlocking {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = true) }
        val send = RecordingSend()
        send.results += ThreadApiResult.Failure(code = 400)
        val wakeups = mutableListOf<Long>()
        val drainer = drainer(store = store, send = send.handler, wakeups = wakeups)

        drainer.drainOnceForTests("send_failed")

        assertEquals(listOf("random-1"), send.randomIds)
        assertEquals(listOf("local-1" to "http_400"), store.markFailedCalls)
        assertEquals(emptyList(), store.consumedRandomIds)
        assertEquals(emptyList(), wakeups)
        assertEquals(4, drainer.recordedAttemptsForTests("local-1"))
        // A later trigger must not send again: the manual retry button owns it from here.
        drainer.drainOnceForTests("realtime")
        assertEquals(listOf("random-1"), send.randomIds)
    }

    @Test
    fun attemptBudgetExhaustionStopsAutomaticRetries() = runBlocking {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = true) }
        val send = RecordingSend()
        repeat(10) { send.results += ThreadApiResult.Failure(code = 500) }
        val drainer =
            drainer(
                store = store,
                send = send.handler,
                policy = RetryPolicy(maxAttempts = 2, baseDelayMillis = 0L, maxDelayMillis = 0L),
            )

        drainer.drainOnceForTests("start")
        drainer.drainOnceForTests("backoff")
        drainer.drainOnceForTests("backoff")

        assertEquals(2, send.randomIds.size)
        assertEquals(2, drainer.recordedAttemptsForTests("local-1"))
        assertEquals(emptyList(), store.consumedRandomIds)
    }

    @Test
    fun emptySuccessResponseIsTreatedAsTerminalFailure() = runBlocking {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = true) }
        val send = RecordingSend()
        send.results +=
            ThreadApiResult.Success(
                ThreadMessagesPage(messages = emptyList(), canLoadMoreBefore = false, canLoadMoreAfter = false),
            )
        val drainer = drainer(store = store, send = send.handler)

        drainer.drainOnceForTests("send_failed")

        assertEquals(listOf("local-1" to "empty_response"), store.markFailedCalls)
        assertEquals(emptyList(), store.consumedRandomIds)
        assertEquals(4, drainer.recordedAttemptsForTests("local-1"))
    }

    @Test
    fun timeoutExceptionIsClassifiedAndBackedOff() = runBlocking {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = true) }
        val send = RecordingSend()
        send.throwables += RaftRequestTimeoutException("send request exceeded 30000ms")
        send.results += ThreadApiResult.Success(successPage())
        val wakeups = mutableListOf<Long>()
        val drainer = drainer(store = store, send = send.handler, wakeups = wakeups)

        drainer.drainOnceForTests("send_failed")
        assertEquals(listOf(0L), wakeups)
        drainer.drainOnceForTests("backoff")

        assertEquals(listOf("local-1" to "thread_send_timeout"), store.markFailedCalls)
        assertEquals(listOf("random-1", "random-1"), send.randomIds)
        assertEquals(listOf("random-1"), store.consumedRandomIds)
    }

    @Test
    fun realtimeTriggersAreThrottledToOneDrainPerInterval() {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = true) }
        val drainer =
            drainer(
                store = store,
                send = RecordingSend().handler,
                realtimeTriggerMinIntervalMs = 3_600_000L,
            )

        drainer.onAuthoritativeOnline()
        drainer.onAuthoritativeOnline()
        drainer.onAuthoritativeOnline()

        assertEquals(1, drainer.realtimeTriggersAcceptedForTests)
    }

    @Test
    fun triggersArrivingDuringADrainCoalesceIntoOneFollowUpPass() = runBlocking {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = true) }
        val sendStarted = CompletableDeferred<Unit>()
        val releaseSend = CompletableDeferred<Unit>()
        val send = RecordingSend(beforeResult = {
            sendStarted.complete(Unit)
            releaseSend.await()
        })
        send.results += ThreadApiResult.Failure(code = 400)
        val consumerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val drainer = drainer(store = store, send = send.handler, scope = consumerScope)

            drainer.trigger("send_failed")
            withTimeout(5_000) { sendStarted.await() }
            // These pile up while the first pass is blocked inside the send.
            drainer.trigger("realtime")
            drainer.trigger("foreground")
            drainer.trigger("send_failed")
            releaseSend.complete(Unit)
            withTimeout(5_000) { store.secondPass.await() }

            // One in-flight pass + one coalesced follow-up pass; the follow-up finds the row
            // terminally failed (attempt budget spent), so the wire saw exactly one request.
            assertEquals(2, store.rowsForDrainCalls)
            assertEquals(listOf("random-1"), send.randomIds)
        } finally {
            consumerScope.cancel()
        }
    }

    @Test
    fun missingProvenanceOrInvalidRouteIsANoOp() = runBlocking {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = true) }
        val send = RecordingSend()
        val drainer =
            drainer(
                store = store,
                send = send.handler,
                provenance = null,
            )

        drainer.drainOnceForTests("start")
        drainer.drainOnceForTests("foreground")

        assertEquals(0, store.rowsForDrainCalls)
        assertEquals(emptyList(), send.randomIds)
    }

    private fun drainer(
        store: FakeDrainStore,
        send: suspend (RaftThreadPendingSendContext) -> ThreadApiResult<ThreadMessagesPage>,
        onSent: suspend (String, ThreadMessagesPage, ReplyCacheProvenance, ThreadRouteIdentity) -> Unit =
            { _, _, _, _ -> },
        onRowFailed: (String, ReplyCacheProvenance, ThreadRouteIdentity, String?, String?) -> Unit =
            { _, _, _, _, _ -> },
        provenance: ReplyCacheProvenance? = provenance(),
        route: ThreadRouteIdentity? = route(),
        policy: RetryPolicy = RetryPolicy(maxAttempts = 4, baseDelayMillis = 0L, maxDelayMillis = 0L),
        realtimeTriggerMinIntervalMs: Long = 0L,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(drainerScopes::add),
        // Always capture backoff wakeups. A real wakeup would launch a second drain pass on
        // Dispatchers.Default that races the pass the test drives with drainOnceForTests and
        // can send the same row an extra time (the flaky timeoutExceptionIsClassifiedAndBackedOff).
        wakeups: MutableList<Long> = mutableListOf(),
    ): ThreadPendingSendDrainer =
        ThreadPendingSendDrainer(
            drainStore = store,
            scope = scope,
            provenanceResolver = { provenance },
            routeResolver = { route },
            send = send,
            onSent = onSent,
            onRowFailed = onRowFailed,
            policy = policy,
            realtimeTriggerMinIntervalMs = realtimeTriggerMinIntervalMs,
            wakeupSchedulerOverride = { ms -> wakeups += ms },
        )

    private fun provenance() =
        ReplyCacheProvenance(
            environmentId = "env-1",
            principalId = "user-1",
            serverId = "server-1",
            generation = 1L,
            authGeneration = 1L,
            selectionGeneration = 1L,
            bindingGeneration = 1L,
        )

    private fun route() =
        ThreadRouteIdentity(
            surfaceKind = RaftThreadSurfaceKind.Channel,
            channelKind = "group",
            instanceKey = "instance-1",
            timelineKey = "channel-1",
            canRenderTimelineCandidates = true,
        )

    private fun successPage() =
        ThreadMessagesPage(
            messages = listOf(WireMessage(id = "wire-1", content = "hello")),
            canLoadMoreBefore = false,
            canLoadMoreAfter = false,
        )

    private fun pendingMessage(localId: String, randomId: String, failed: Boolean) =
        RaftThreadMessageItem(
            id = localId,
            authorId = "user-1",
            authorName = "User",
            deliveryState = if (failed) ThreadMessageDeliveryFailed else ThreadMessageDeliverySending,
            pendingSendContext =
                RaftThreadPendingSendContext(
                    channelId = "channel-1",
                    body = "hello",
                    randomId = randomId,
                ),
        )

    private class RecordingSend(
        private val beforeResult: suspend () -> Unit = {}
    ) {
        val randomIds = mutableListOf<String>()
        val results = ArrayDeque<ThreadApiResult<ThreadMessagesPage>>()
        val throwables = ArrayDeque<Throwable>()
        val handler: suspend (RaftThreadPendingSendContext) -> ThreadApiResult<ThreadMessagesPage> =
            { context ->
                beforeResult()
                randomIds += context.randomId.orEmpty()
                throwables.removeFirstOrNull()?.let { throw it }
                results.removeFirstOrNull()
                    ?: ThreadApiResult.Success(
                        ThreadMessagesPage(
                            messages = listOf(WireMessage(id = "wire-${randomIds.size}", content = "hello")),
                            canLoadMoreBefore = false,
                            canLoadMoreAfter = false,
                        ),
                    )
            }
    }

    private class FakeDrainStore : ThreadPendingSendDrainStore {
        val rows = mutableListOf<RaftThreadMessageItem>()
        val markFailedCalls = mutableListOf<Pair<String, String>>()
        val consumedRandomIds = mutableListOf<String>()
        val secondPass = CompletableDeferred<Unit>()
        var rowsForDrainCalls = 0

        override fun rowsForDrain(
            provenance: ReplyCacheProvenance,
            route: ThreadRouteIdentity
        ): List<RaftThreadMessageItem> {
            rowsForDrainCalls += 1
            if (rowsForDrainCalls == 2) secondPass.complete(Unit)
            return rows.toList()
        }

        override fun markFailed(
            provenance: ReplyCacheProvenance,
            route: ThreadRouteIdentity,
            localId: String,
            errorCode: String?,
            errorMessage: String?
        ): Boolean {
            markFailedCalls += localId to errorCode.orEmpty()
            // Index loop instead of MutableList.replaceAll, which is JVM-only and fails the
            // Kotlin/Native commonTest compile.
            for (index in rows.indices) {
                val row = rows[index]
                if (row.id == localId) rows[index] = row.copy(deliveryState = ThreadMessageDeliveryFailed)
            }
            return true
        }

        override fun consumeByRandomId(
            provenance: ReplyCacheProvenance,
            route: ThreadRouteIdentity,
            randomId: String
        ): Boolean {
            consumedRandomIds += randomId
            rows.removeAll { row -> row.pendingSendContext?.randomId == randomId }
            return true
        }
    }
}
