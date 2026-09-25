package build.raft.app.data

import build.raft.app.KuiklyThreadMessageItem
import build.raft.app.KuiklyThreadPendingSendContext
import build.raft.app.KuiklyThreadSurfaceKind
import build.raft.app.ReplyCacheProvenance
import build.raft.app.ThreadMessageDeliveryFailed
import build.raft.app.ThreadMessageDeliverySending
import build.raft.app.ThreadRouteIdentity
import build.raft.app.network.SlockRequestTimeoutException
import build.raft.app.network.retry.RetryPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.backgroundScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadPendingSendDrainerTest {

    @AfterTest
    fun clearInFlightRegistry() {
        ThreadPendingSendInFlightRegistry.clearForTests()
    }

    @Test
    fun drainsFailedRowWithSameRandomIdAndConsumesOnSuccess() = runTest {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = true) }
        val send = RecordingSend()
        val sent = mutableListOf<String>()
        val drainer = drainer(store = store, send = send.handler, onSent = { localId, _, _, _ -> sent += localId })

        drainer.onStoreStarted()
        advanceUntilIdle()

        assertEquals(listOf("random-1"), send.randomIds)
        assertEquals(listOf("random-1"), store.consumedRandomIds)
        assertEquals(listOf("local-1"), sent)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun adoptsStaleSendingRowWhoseOwningAttemptDied() = runTest {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = false) }
        val send = RecordingSend()
        val drainer = drainer(store = store, send = send.handler)

        drainer.onStoreStarted()
        advanceUntilIdle()

        // The orphaned "sending" row is flipped to failed BEFORE the resend so the UI keeps an
        // explicit retry affordance even if every automatic attempt is later exhausted.
        assertEquals(listOf("local-1" to "thread_send_adopted_stale"), store.markFailedCalls)
        assertEquals(listOf("random-1"), send.randomIds)
        assertEquals(listOf("random-1"), store.consumedRandomIds)
    }

    @Test
    fun skipsRowWhoseRandomIdHasALiveInProcessAttempt() = runTest {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = true) }
        val send = RecordingSend()
        val drainer = drainer(store = store, send = send.handler)

        ThreadPendingSendInFlightRegistry.begin("random-1")
        drainer.onStoreStarted()
        advanceUntilIdle()

        assertEquals(emptyList(), send.randomIds)
        assertEquals(emptyList(), store.consumedRandomIds)
        assertEquals(emptyList(), store.markFailedCalls)

        // Once the live attempt ends, the same row becomes drain-eligible again.
        ThreadPendingSendInFlightRegistry.end("random-1")
        drainer.trigger("manual")
        advanceUntilIdle()
        assertEquals(listOf("random-1"), send.randomIds)
    }

    @Test
    fun retryableFailureBacksOffAndRetriesWithSameRandomIdUntilSuccess() = runTest {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = true) }
        val send = RecordingSend()
        send.results += ThreadApiResult.Failure(code = 429)
        send.results += successPage()
        val drainer = drainer(store = store, send = send.handler)

        drainer.onStoreStarted()
        advanceUntilIdle()

        assertEquals(listOf("random-1", "random-1"), send.randomIds)
        assertEquals(listOf("local-1" to "http_429"), store.markFailedCalls)
        assertEquals(listOf("random-1"), store.consumedRandomIds)
        assertEquals(0, drainer.recordedAttemptsForTests("local-1"))
    }

    @Test
    fun nonRetryableFailureStopsAfterSingleAttemptAndLeavesRowFailed() = runTest {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = true) }
        val send = RecordingSend()
        send.results += ThreadApiResult.Failure(code = 400)
        val drainer = drainer(store = store, send = send.handler)

        drainer.onStoreStarted()
        advanceUntilIdle()

        assertEquals(listOf("random-1"), send.randomIds)
        assertEquals(listOf("local-1" to "http_400"), store.markFailedCalls)
        assertEquals(emptyList(), store.consumedRandomIds)
        assertEquals(4, drainer.recordedAttemptsForTests("local-1"))
        // A later trigger must not send again: the manual retry button owns it from here.
        drainer.trigger("realtime")
        advanceUntilIdle()
        assertEquals(listOf("random-1"), send.randomIds)
    }

    @Test
    fun attemptBudgetExhaustionStopsAutomaticRetries() = runTest {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = true) }
        val send = RecordingSend()
        repeat(10) { send.results += ThreadApiResult.Failure(code = 500) }
        val drainer =
            drainer(
                store = store,
                send = send.handler,
                policy = RetryPolicy(maxAttempts = 2, baseDelayMillis = 100L, maxDelayMillis = 1_000L),
            )

        drainer.onStoreStarted()
        advanceUntilIdle()

        assertEquals(2, send.randomIds.size)
        assertEquals(2, drainer.recordedAttemptsForTests("local-1"))
        assertEquals(emptyList(), store.consumedRandomIds)
    }

    @Test
    fun emptySuccessResponseIsTreatedAsTerminalFailure() = runTest {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = true) }
        val send = RecordingSend()
        send.results += ThreadMessagesPage(messages = emptyList(), canLoadMoreBefore = false, canLoadMoreAfter = false)
        val drainer = drainer(store = store, send = send.handler)

        drainer.onStoreStarted()
        advanceUntilIdle()

        assertEquals(listOf("local-1" to "empty_response"), store.markFailedCalls)
        assertEquals(emptyList(), store.consumedRandomIds)
        assertEquals(4, drainer.recordedAttemptsForTests("local-1"))
    }

    @Test
    fun timeoutExceptionIsClassifiedAndBackedOff() = runTest {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = true) }
        val send = RecordingSend()
        send.throwables += SlockRequestTimeoutException("send request exceeded 30000ms")
        send.results += successPage()
        val drainer = drainer(store = store, send = send.handler)

        drainer.onStoreStarted()
        advanceUntilIdle()

        assertEquals(listOf("local-1" to "thread_send_timeout"), store.markFailedCalls)
        assertEquals(listOf("random-1", "random-1"), send.randomIds)
        assertEquals(listOf("random-1"), store.consumedRandomIds)
    }

    @Test
    fun realtimeTriggersAreThrottledToOneDrainPerInterval() = runTest {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = true) }
        val send = RecordingSend()
        repeat(5) { send.results += ThreadApiResult.Failure(code = 400) }
        val drainer =
            drainer(
                store = store,
                send = send.handler,
                realtimeTriggerMinIntervalMs = 3_600_000L,
            )

        drainer.onAuthoritativeOnline()
        drainer.onAuthoritativeOnline()
        drainer.onAuthoritativeOnline()
        advanceUntilIdle()

        assertEquals(1, store.rowsForDrainCalls)
    }

    @Test
    fun triggersArrivingDuringADrainCoalesceIntoOneFollowUpPass() = runTest {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = true) }
        val gate = CompletableDeferred<Unit>()
        val send = RecordingSend(beforeResult = { gate.await() })
        send.results += ThreadApiResult.Failure(code = 400)
        val drainer = drainer(store = store, send = send.handler)

        drainer.trigger("start")
        testScheduler.runCurrent()
        drainer.trigger("realtime")
        drainer.trigger("foreground")
        drainer.trigger("send_failed")
        gate.complete(Unit)
        advanceUntilIdle()

        // One in-flight pass + one coalesced follow-up pass; the follow-up finds the row
        // terminally failed (attempt budget spent), so the wire saw exactly one request.
        assertEquals(listOf("random-1"), send.randomIds)
        assertEquals(2, store.rowsForDrainCalls)
    }

    @Test
    fun missingProvenanceOrInvalidRouteIsANoOp() = runTest {
        val store = FakeDrainStore().apply { rows += pendingMessage("local-1", "random-1", failed = true) }
        val send = RecordingSend()
        val drainer =
            drainer(
                store = store,
                send = send.handler,
                provenance = null,
            )

        drainer.onStoreStarted()
        drainer.onForeground()
        advanceUntilIdle()

        assertEquals(0, store.rowsForDrainCalls)
        assertEquals(emptyList(), send.randomIds)
    }

    private fun drainer(
        store: FakeDrainStore,
        send: suspend (KuiklyThreadPendingSendContext) -> ThreadApiResult<ThreadMessagesPage>,
        onSent: suspend (String, ThreadMessagesPage, ReplyCacheProvenance, ThreadRouteIdentity) -> Unit =
            { _, _, _, _ -> },
        provenance: ReplyCacheProvenance? = provenance(),
        route: ThreadRouteIdentity? = route(),
        policy: RetryPolicy = RetryPolicy(maxAttempts = 4, baseDelayMillis = 100L, maxDelayMillis = 1_000L),
        realtimeTriggerMinIntervalMs: Long = 0L,
    ): ThreadPendingSendDrainer =
        ThreadPendingSendDrainer(
            drainStore = store,
            scope = backgroundScope,
            provenanceResolver = { provenance },
            routeResolver = { route },
            send = send,
            onSent = onSent,
            policy = policy,
            realtimeTriggerMinIntervalMs = realtimeTriggerMinIntervalMs,
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
            surfaceKind = KuiklyThreadSurfaceKind.Channel,
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
        KuiklyThreadMessageItem(
            id = localId,
            authorId = "user-1",
            authorName = "User",
            deliveryState = if (failed) ThreadMessageDeliveryFailed else ThreadMessageDeliverySending,
            pendingSendContext =
                KuiklyThreadPendingSendContext(
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
        val handler: suspend (KuiklyThreadPendingSendContext) -> ThreadApiResult<ThreadMessagesPage> =
            { context ->
                beforeResult()
                randomIds += context.randomId.orEmpty()
                throwables.removeFirstOrNull()?.let { throw it }
                results.removeFirstOrNull()
                    ?: ThreadMessagesPage(
                        messages = listOf(WireMessage(id = "wire-${randomIds.size}", content = "hello")),
                        canLoadMoreBefore = false,
                        canLoadMoreAfter = false,
                    )
            }
    }

    private class FakeDrainStore : ThreadPendingSendDrainStore {
        val rows = mutableListOf<KuiklyThreadMessageItem>()
        val markFailedCalls = mutableListOf<Pair<String, String?>>()
        val consumedRandomIds = mutableListOf<String>()
        var rowsForDrainCalls = 0

        override fun rowsForDrain(
            provenance: ReplyCacheProvenance,
            route: ThreadRouteIdentity
        ): List<KuiklyThreadMessageItem> {
            rowsForDrainCalls += 1
            return rows.toList()
        }

        override fun markFailed(
            provenance: ReplyCacheProvenance,
            route: ThreadRouteIdentity,
            localId: String,
            errorCode: String?,
            errorMessage: String?
        ): Boolean {
            markFailedCalls += localId to errorCode
            rows.replaceAll { row ->
                if (row.id == localId) row.copy(deliveryState = ThreadMessageDeliveryFailed) else row
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
