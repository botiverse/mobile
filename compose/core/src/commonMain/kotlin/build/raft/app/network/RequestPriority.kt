package build.raft.app.network

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class RaftRequestPriority {
    Foreground,
    Visible,
    Background
}

// Keep user-visible requests high while still bounding background/bulk work.
const val RAFT_DEFAULT_MAX_FOREGROUND_REQUESTS = 64
const val RAFT_DEFAULT_MAX_VISIBLE_REQUESTS = 32
const val RAFT_DEFAULT_MAX_BACKGROUND_REQUESTS = 16
const val RAFT_SHARED_REQUEST_PRIORITY_GATE_ENABLED = false

class RaftRequestTimeoutException(message: String) : Exception(message)

class RaftRequestPriorityElement(
    val priority: RaftRequestPriority,
    val requestKey: String? = null,
    val reason: String? = null
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RaftRequestPriorityElement>
}

suspend fun <T> withRaftRequestPriority(
    priority: RaftRequestPriority,
    key: String? = null,
    reason: String? = null,
    block: suspend () -> T
): T = withContext(RaftRequestPriorityElement(priority, key, reason)) { block() }

class RaftRequestScheduler(
    private val maxForegroundRequests: Int = RAFT_DEFAULT_MAX_FOREGROUND_REQUESTS,
    private val maxVisibleRequests: Int = RAFT_DEFAULT_MAX_VISIBLE_REQUESTS,
    private val maxBackgroundRequests: Int = RAFT_DEFAULT_MAX_BACKGROUND_REQUESTS,
    private val requestTimeoutMillis: Long = 30_000,
    private val priorityGateEnabled: Boolean = RAFT_SHARED_REQUEST_PRIORITY_GATE_ENABLED,
    private val logger: (String) -> Unit = {}
) {
    private val mutex = Mutex()
    private val foregroundSemaphore = Semaphore(maxForegroundRequests)
    private val visibleSemaphore = Semaphore(maxVisibleRequests)
    private val backgroundSemaphore = Semaphore(maxBackgroundRequests)
    private var foregroundOrVisibleWaiting = 0
    private var foregroundOrVisibleActive = 0

    suspend fun <T> run(
        priority: RaftRequestPriority,
        key: String? = null,
        reason: String? = null,
        /**
         * Per-request wall-clock override (task #788). Null keeps the ordinary
         * [requestTimeoutMillis] bound. Streaming uploads pass a backstop that
         * sits strictly above the transport's authoritative total budget so
         * the two deadlines never race: the transport terminal always wins,
         * and this bound only fires if the transport deadline machinery
         * itself fails, guaranteeing the priority semaphore slot is
         * reclaimed.
         */
        timeoutMillis: Long? = null,
        block: suspend () -> T
    ): T =
        if (!priorityGateEnabled) {
            runDirect(priority, key, reason, timeoutMillis, block)
        } else {
            when (priority) {
                RaftRequestPriority.Foreground ->
                    runForegroundOrVisible(priority, key, reason, foregroundSemaphore, timeoutMillis, block)
                RaftRequestPriority.Visible ->
                    runForegroundOrVisible(priority, key, reason, visibleSemaphore, timeoutMillis, block)
                RaftRequestPriority.Background -> runBackground(priority, key, reason, timeoutMillis, block)
            }
        }

    private suspend fun <T> runDirect(
        priority: RaftRequestPriority,
        key: String?,
        reason: String?,
        timeoutMillis: Long?,
        block: suspend () -> T
    ): T {
        val startedAt = TimeSource.Monotonic.markNow()
        logStart(priority, key, reason, queuedMs = 0)
        try {
            return runWithTimeout(priority, key, reason, timeoutMillis, block)
        } finally {
            logFinish(
                priority = priority,
                key = key,
                reason = reason,
                queuedMs = 0,
                runningMs = startedAt.elapsedNow().inWholeMilliseconds
            )
        }
    }

    private suspend fun <T> runForegroundOrVisible(
        priority: RaftRequestPriority,
        key: String?,
        reason: String?,
        semaphore: Semaphore,
        timeoutMillis: Long?,
        block: suspend () -> T
    ): T {
        val queuedAt = TimeSource.Monotonic.markNow()
        mutex.withLock { foregroundOrVisibleWaiting += 1 }
        var acquired = false
        var active = false
        var startedAtMs = queuedAt.elapsedNow().inWholeMilliseconds
        try {
            semaphore.acquire()
            acquired = true
            mutex.withLock {
                foregroundOrVisibleWaiting -= 1
                foregroundOrVisibleActive += 1
                active = true
            }
            startedAtMs = queuedAt.elapsedNow().inWholeMilliseconds
            logStart(priority, key, reason, startedAtMs)
            return runWithTimeout(priority, key, reason, timeoutMillis, block)
        } finally {
            val finishedAtMs = queuedAt.elapsedNow().inWholeMilliseconds
            mutex.withLock {
                if (active) {
                    foregroundOrVisibleActive -= 1
                } else {
                    foregroundOrVisibleWaiting -= 1
                }
            }
            if (acquired) {
                semaphore.release()
            }
            logFinish(
                priority = priority,
                key = key,
                reason = reason,
                queuedMs = startedAtMs,
                runningMs = finishedAtMs - startedAtMs
            )
        }
    }

    private suspend fun <T> runBackground(
        priority: RaftRequestPriority,
        key: String?,
        reason: String?,
        timeoutMillis: Long?,
        block: suspend () -> T
    ): T {
        val queuedAt = TimeSource.Monotonic.markNow()
        var acquired = false
        var started = false
        var startedAtMs = queuedAt.elapsedNow().inWholeMilliseconds
        try {
            waitForForegroundOrVisibleIdle(priority, key, reason)
            backgroundSemaphore.acquire()
            acquired = true
            waitForForegroundOrVisibleIdle(priority, key, reason)
            startedAtMs = queuedAt.elapsedNow().inWholeMilliseconds
            logStart(priority, key, reason, startedAtMs)
            started = true
            return runWithTimeout(priority, key, reason, timeoutMillis, block)
        } finally {
            val finishedAtMs = queuedAt.elapsedNow().inWholeMilliseconds
            if (started) {
                logFinish(
                    priority = priority,
                    key = key,
                    reason = reason,
                    queuedMs = startedAtMs,
                    runningMs = finishedAtMs - startedAtMs
                )
            }
            if (acquired) {
                backgroundSemaphore.release()
            }
        }
    }

    private suspend fun <T> runWithTimeout(
        priority: RaftRequestPriority,
        key: String?,
        reason: String?,
        timeoutMillis: Long?,
        block: suspend () -> T
    ): T {
        val effectiveTimeoutMillis = timeoutMillis ?: requestTimeoutMillis
        return try {
            withTimeout(effectiveTimeoutMillis) { block() }
        } catch (timeout: TimeoutCancellationException) {
            throw RaftRequestTimeoutException(
                "Raft request timed out after ${effectiveTimeoutMillis}ms " +
                    "priority=$priority key=${key.orEmpty()} reason=${reason.orEmpty()}"
            )
        }
    }

    private suspend fun waitForForegroundOrVisibleIdle(
        priority: RaftRequestPriority,
        key: String?,
        reason: String?
    ) {
        var loggedPause = false
        while (hasForegroundOrVisibleWork()) {
            if (!loggedPause) {
                logPause(priority, key, reason)
                loggedPause = true
            }
            delay(25)
        }
    }

    private suspend fun hasForegroundOrVisibleWork(): Boolean =
        mutex.withLock { foregroundOrVisibleWaiting > 0 || foregroundOrVisibleActive > 0 }

    private fun logStart(
        priority: RaftRequestPriority,
        key: String?,
        reason: String?,
        queuedMs: Long
    ) {
        logger(
            "request_start priority=$priority lane=${priority.laneName()} " +
                "key=${key.orEmpty()} reason=${reason.orEmpty()} queuedMs=$queuedMs"
        )
    }

    private fun logFinish(
        priority: RaftRequestPriority,
        key: String?,
        reason: String?,
        queuedMs: Long,
        runningMs: Long
    ) {
        logger(
            "request_finish priority=$priority lane=${priority.laneName()} " +
                "key=${key.orEmpty()} reason=${reason.orEmpty()} queuedMs=$queuedMs runningMs=$runningMs"
        )
    }

    private fun logPause(
        priority: RaftRequestPriority,
        key: String?,
        reason: String?
    ) {
        logger(
            "request_pause priority=$priority lane=${priority.laneName()} " +
                "key=${key.orEmpty()} reason=${reason.orEmpty()} waitingFor=foreground_or_visible"
        )
    }

    private fun RaftRequestPriority.laneName(): String =
        when (this) {
            RaftRequestPriority.Foreground -> "foreground"
            RaftRequestPriority.Visible -> "visible"
            RaftRequestPriority.Background -> "background"
        }
}
