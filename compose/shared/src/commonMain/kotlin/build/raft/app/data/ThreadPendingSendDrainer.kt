package build.raft.app.data

import build.raft.app.KuiklyThreadMessageItem
import build.raft.app.KuiklyThreadPendingSendContext
import build.raft.app.ReplyCacheProvenance
import build.raft.app.ThreadMessageDeliveryFailed
import build.raft.app.ThreadMessageDeliverySending
import build.raft.app.ThreadRouteIdentity
import build.raft.app.isValidPendingSendRoute
import build.raft.app.network.retry.BackoffCalculator
import build.raft.app.network.retry.RetryPolicy
import build.raft.app.threadSendExceptionErrorCode
import build.raft.app.threadSendFailureErrorCode
import build.raft.app.threadSendShouldSilentlyRetry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Process-wide registry of randomIds that currently have a live send attempt. Sends run on the
 * app-level `threadSendScope`, so a row is eligible for draining exactly when no attempt in this
 * process owns its randomId — no time-based staleness threshold is needed. A journaled row in
 * "sending" state whose randomId is absent here belongs to an attempt that died (process kill or
 * a scope that was torn down) and is adopted by the drainer.
 */
internal object ThreadPendingSendInFlightRegistry {
    private val lock = SynchronizedObject()
    private val inFlightRandomIds = mutableSetOf<String>()

    fun begin(randomId: String?) {
        val key = randomId?.trim()?.takeIf(String::isNotBlank) ?: return
        synchronized(lock) { inFlightRandomIds += key }
    }

    fun end(randomId: String?) {
        val key = randomId?.trim()?.takeIf(String::isNotBlank) ?: return
        synchronized(lock) { inFlightRandomIds -= key }
    }

    fun isInFlight(randomId: String?): Boolean {
        val key = randomId?.trim()?.takeIf(String::isNotBlank) ?: return false
        return synchronized(lock) { key in inFlightRandomIds }
    }

    internal fun clearForTests() {
        synchronized(lock) { inFlightRandomIds.clear() }
    }
}

/**
 * Persistence seam used by [ThreadPendingSendDrainer] so drain semantics can be unit-tested
 * without a SQL driver. Production wiring adapts [ThreadPendingSendJournal].
 */
internal interface ThreadPendingSendDrainStore {
    /** Rows visible to [provenance]+[route], in journal order (oldest first). */
    fun rowsForDrain(
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity
    ): List<KuiklyThreadMessageItem>

    /** Returns true when the row was durably transitioned to "failed". */
    fun markFailed(
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        localId: String,
        errorCode: String?,
        errorMessage: String?
    ): Boolean

    /**
     * Returns true when the row no longer needs draining after a confirmed send (deleted or
     * already consumed by a canonical echo; false only when the delete itself failed).
     */
    fun consumeByRandomId(
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        randomId: String
    ): Boolean
}

internal class ThreadPendingSendJournalDrainStore(
    private val journal: ThreadPendingSendJournal
) : ThreadPendingSendDrainStore {
    override fun rowsForDrain(
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity
    ): List<KuiklyThreadMessageItem> = journal.restore(provenance, route)

    override fun markFailed(
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        localId: String,
        errorCode: String?,
        errorMessage: String?
    ): Boolean = journal.markFailedForDrain(provenance, route, localId, errorCode, errorMessage)

    override fun consumeByRandomId(
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        randomId: String
    ): Boolean =
        journal.consumeCanonicalRandomId(provenance, route, randomId) !=
            ThreadPendingSendJournal.ConsumeResult.Failed
}

/**
 * Drains durably journaled pending sends. Triggers are event-driven: store start (page entry and
 * process restart), accepted realtime frames (authoritative online witness, throttled), app
 * foreground, a failed in-page send, and its own exponential backoff wakeups. A send retries with
 * the same randomId, so a request that committed server-side before its response was lost is
 * deduped by the server instead of producing a duplicate.
 *
 * Rows are fenced by the exact reply-cache provenance and route identity that admitted them; when
 * ownership advances (token rotation, owner switch), this drainer simply sees no rows, matching
 * the journal's existing lifecycle. Attempt budgets are process-local: a process restart resets
 * the counter, which is acceptable because every trigger is also re-armed on restart.
 */
internal class ThreadPendingSendDrainer(
    private val drainStore: ThreadPendingSendDrainStore,
    private val scope: CoroutineScope,
    private val provenanceResolver: () -> ReplyCacheProvenance?,
    private val routeResolver: () -> ThreadRouteIdentity?,
    private val send: suspend (KuiklyThreadPendingSendContext) -> ThreadApiResult<ThreadMessagesPage>,
    private val onSent: suspend (
        localId: String,
        page: ThreadMessagesPage,
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity
    ) -> Unit,
    /**
     * Notified after a row durably turns failed under drain ownership (adoption of a stale
     * in-flight row, or terminal failure after the last attempt). The journal write already
     * happened; this hook lets the open page hot-update its projection so the failure marker +
     * retry affordance render without a page re-entry (#1134 cell 3 follow-up).
     */
    private val onRowFailed: (
        localId: String,
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        errorCode: String?,
        errorMessage: String?,
    ) -> Unit = { _, _, _, _, _ -> },
    private val policy: RetryPolicy =
        RetryPolicy(
            maxAttempts = 4,
            baseDelayMillis = 2_000L,
            maxDelayMillis = 30_000L,
            jitterRatio = 0.2
        ),
    private val backoff: BackoffCalculator = BackoffCalculator(),
    private val inFlight: ThreadPendingSendInFlightRegistry = ThreadPendingSendInFlightRegistry,
    private val realtimeTriggerMinIntervalMs: Long = 10_000L,
    private val logger: (String) -> Unit = {},
    /**
     * Test seam for backoff wakeups: production leaves this null and [scheduleWakeup] arms a
     * delayed re-trigger on [scope]; tests capture the delay and drive the follow-up drain
     * manually, keeping every assertion free of wall-clock sleeps.
     */
    private val wakeupSchedulerOverride: ((Long) -> Unit)? = null
) {
    private val triggers = Channel<String>(capacity = Channel.UNLIMITED)
    private val consumerLock = SynchronizedObject()
    private var consumerJob: Job? = null
    private var wakeupJob: Job? = null
    private val attemptsByRow = mutableMapOf<String, Int>()
    private val notBeforeByRow = mutableMapOf<String, TimeMark>()
    private var lastRealtimeTrigger: TimeMark? = null

    /** Counts online triggers that passed the throttle — lets tests assert throttling
     * synchronously without running the consumer loop. */
    internal var realtimeTriggersAcceptedForTests = 0
        private set

    fun onStoreStarted() = trigger("start")

    fun onForeground() = trigger("foreground")

    fun onSendFailed() = trigger("send_failed")

    /** Accepted realtime frames prove connectivity; throttled to avoid draining per frame. */
    fun onAuthoritativeOnline() {
        val now = TimeSource.Monotonic.markNow()
        val last = lastRealtimeTrigger
        if (last != null && last.elapsedNow().inWholeMilliseconds < realtimeTriggerMinIntervalMs) {
            return
        }
        lastRealtimeTrigger = now
        realtimeTriggersAcceptedForTests += 1
        trigger("realtime")
    }

    fun trigger(reason: String) {
        triggers.trySend(reason)
        synchronized(consumerLock) {
            if (consumerJob?.isActive == true) return
            consumerJob =
                scope.launch {
                    consumeLoop()
                }
        }
    }

    private suspend fun consumeLoop() {
        while (true) {
            var reason = triggers.receive()
            // Coalesce triggers that piled up while a drain was running.
            while (true) {
                reason = triggers.tryReceive().getOrNull() ?: break
            }
            val result = runCatchingPreservingCancellation { drainOnce(reason) }
            result.exceptionOrNull()?.let { failure ->
                logger("thread_pending_send_drain_error reason=$reason message=${failure.message}")
            }
        }
    }

    private suspend fun drainOnce(reason: String) {
        val provenance =
            provenanceResolver()
                ?: run {
                    logger("thread_pending_send_drain_skip reason=$reason cause=no_provenance")
                    return
                }
        val route =
            routeResolver()?.takeIf { it.hasCompleteReplyIdentity && it.isValidPendingSendRoute() }
                ?: run {
                    logger("thread_pending_send_drain_skip reason=$reason cause=route_not_ready")
                    return
                }
        val rows = drainStore.rowsForDrain(provenance, route)
        if (rows.isEmpty()) {
            logger("thread_pending_send_drain_skip reason=$reason cause=no_rows")
            return
        }
        // A cold store start only adopts orphaned "sending" rows (the attempt that owned them
        // died with the process). Rows already marked failed wait for a live trigger — send
        // failure, authoritative online, foreground, backoff wake — or for an explicit user
        // retry: process recreation alone must never auto-send (old contract pinned by
        // ThreadPendingSendStoreIntegrationTest).
        val includeFailedRetries = reason != "start"
        var drained = 0
        for (row in rows) {
            val context = row.pendingSendContext ?: continue
            val randomId = context.randomId?.trim()?.takeIf(String::isNotBlank) ?: continue
            if (inFlight.isInFlight(randomId)) continue
            val failed = row.deliveryState.equals(ThreadMessageDeliveryFailed, ignoreCase = true)
            val sending = row.deliveryState.equals(ThreadMessageDeliverySending, ignoreCase = true)
            if (!failed && !sending) continue
            if (failed) {
                if (!includeFailedRetries) continue
                // Only transient failures are eligible for automatic resend; a permanent
                // failure (e.g. http_400) keeps the row failed for the manual retry button.
                // A missing code means the failure predates classification — retrying with the
                // same randomId is idempotent, so allow it.
                val rowErrorCode = row.pendingSendErrorCode
                if (rowErrorCode != null &&
                    !threadSendShouldSilentlyRetry(
                        rowErrorCode,
                        rowErrorCode.removePrefix("http_").toIntOrNull(),
                    )
                ) {
                    continue
                }
            }
            if (sending) {
                // No live attempt in this process owns this randomId, so the attempt that wrote
                // this row died (process kill or scope loss). Flip the row to failed first so the
                // UI offers an explicit retry even if every automatic attempt below is exhausted.
                val adopted =
                    drainStore.markFailed(
                        provenance = provenance,
                        route = route,
                        localId = row.id,
                        errorCode = "thread_send_adopted_stale",
                        errorMessage = null
                    )
                if (adopted) {
                    onRowFailed(row.id, provenance, route, "thread_send_adopted_stale", null)
                }
            }
            val attempt = attemptsByRow[row.id] ?: 0
            if (attempt >= policy.maxAttempts) continue
            val waitUntil = notBeforeByRow[row.id]
            if (waitUntil != null && waitUntil.hasNotPassedNow()) continue
            inFlight.begin(randomId)
            try {
                val sendResult =
                    runCatchingPreservingCancellation {
                        send(context.copy(randomId = randomId))
                    }
                val exception = sendResult.exceptionOrNull()
                if (exception != null) {
                    handleDrainFailure(
                        row = row,
                        provenance = provenance,
                        route = route,
                        attempt = attempt,
                        errorCode = threadSendExceptionErrorCode(exception),
                        httpCode = null
                    )
                } else {
                    when (val apiResult = sendResult.getOrThrow()) {
                        is ThreadApiResult.Success -> {
                            if (apiResult.data.messages.isEmpty()) {
                                handleDrainFailure(
                                    row = row,
                                    provenance = provenance,
                                    route = route,
                                    attempt = attempt,
                                    errorCode = "empty_response",
                                    httpCode = null
                                )
                            } else {
                                val consumed =
                                    drainStore.consumeByRandomId(provenance, route, randomId)
                                if (!consumed) {
                                    logger(
                                        "thread_pending_send_drain_consume_failed localId=${row.id} " +
                                            "randomId=$randomId"
                                    )
                                }
                                attemptsByRow.remove(row.id)
                                notBeforeByRow.remove(row.id)
                                drained += 1
                                logger(
                                    "thread_pending_send_drained localId=${row.id} randomId=$randomId " +
                                        "trigger=$reason attempt=$attempt"
                                )
                                runCatchingPreservingCancellation {
                                    onSent(row.id, apiResult.data, provenance, route)
                                }.exceptionOrNull()?.let { failure ->
                                    logger(
                                        "thread_pending_send_drain_merge_error localId=${row.id} " +
                                            "message=${failure.message}"
                                    )
                                }
                            }
                        }
                        is ThreadApiResult.Failure -> {
                            handleDrainFailure(
                                row = row,
                                provenance = provenance,
                                route = route,
                                attempt = attempt,
                                errorCode =
                                    threadSendFailureErrorCode(
                                        apiResult.contractCode,
                                        apiResult.errorBodyText,
                                        apiResult.code
                                    ),
                                httpCode = apiResult.code
                            )
                        }
                    }
                }
            } finally {
                inFlight.end(randomId)
            }
        }
        if (drained > 0) {
            logger("thread_pending_send_drain_pass trigger=$reason drained=$drained")
        }
    }

    private fun handleDrainFailure(
        row: KuiklyThreadMessageItem,
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        attempt: Int,
        errorCode: String?,
        httpCode: Int?
    ) {
        val markedFailed =
            drainStore.markFailed(
                provenance = provenance,
                route = route,
                localId = row.id,
                errorCode = errorCode,
                errorMessage = null
            )
        val retryable = threadSendShouldSilentlyRetry(errorCode, httpCode)
        val nextDelay =
            if (retryable) backoff.delayMillis(attempt, policy) else null
        if (!retryable || nextDelay == null || attempt + 1 >= policy.maxAttempts) {
            attemptsByRow[row.id] = policy.maxAttempts
            notBeforeByRow.remove(row.id)
            logger(
                "thread_pending_send_drain_terminal localId=${row.id} code=${errorCode.orEmpty()} " +
                    "http=${httpCode?.toString().orEmpty()} attempt=$attempt retryable=$retryable"
            )
            if (markedFailed) {
                onRowFailed(row.id, provenance, route, errorCode, null)
            }
            return
        }
        attemptsByRow[row.id] = attempt + 1
        notBeforeByRow[row.id] = TimeSource.Monotonic.markNow() + nextDelay.milliseconds
        scheduleWakeup(nextDelay)
        logger(
            "thread_pending_send_drain_backoff localId=${row.id} code=${errorCode.orEmpty()} " +
                "attempt=$attempt nextDelayMs=$nextDelay"
        )
    }

    private fun scheduleWakeup(delayMs: Long) {
        wakeupSchedulerOverride?.let { override ->
            override(delayMs)
            return
        }
        // A single wakeup is enough: when it fires the drain pass re-evaluates every row's
        // deadline, and stricter triggers (realtime/foreground) can always arrive sooner.
        if (wakeupJob?.isActive == true) return
        wakeupJob =
            scope.launch {
                delay(delayMs)
                trigger("backoff")
            }
    }

    internal fun recordedAttemptsForTests(localId: String): Int = attemptsByRow[localId] ?: 0

    /** Test seam: run one drain pass synchronously (the production path reaches this only
     * through the coalescing consumer loop). */
    internal suspend fun drainOnceForTests(reason: String) = drainOnce(reason)
}
