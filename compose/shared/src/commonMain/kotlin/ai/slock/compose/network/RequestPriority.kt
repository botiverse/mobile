package ai.slock.compose.network

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SlockRequestPriority {
    Foreground,
    Visible,
    Background
}

class SlockRequestPriorityElement(
    val priority: SlockRequestPriority,
    val requestKey: String? = null,
    val reason: String? = null
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SlockRequestPriorityElement>
}

suspend fun <T> withSlockRequestPriority(
    priority: SlockRequestPriority,
    key: String? = null,
    reason: String? = null,
    block: suspend () -> T
): T = withContext(SlockRequestPriorityElement(priority, key, reason)) { block() }

class SlockRequestScheduler(
    private val maxForegroundRequests: Int = 6,
    private val maxVisibleRequests: Int = 3,
    private val maxBackgroundRequests: Int = 1,
    private val logger: (String) -> Unit = {}
) {
    private val mutex = Mutex()
    private val foregroundSemaphore = Semaphore(maxForegroundRequests)
    private val visibleSemaphore = Semaphore(maxVisibleRequests)
    private val backgroundSemaphore = Semaphore(maxBackgroundRequests)
    private var foregroundOrVisibleWaiting = 0
    private var foregroundOrVisibleActive = 0

    suspend fun <T> run(
        priority: SlockRequestPriority,
        key: String? = null,
        reason: String? = null,
        block: suspend () -> T
    ): T =
        when (priority) {
            SlockRequestPriority.Foreground ->
                runForegroundOrVisible(priority, key, reason, foregroundSemaphore, block)
            SlockRequestPriority.Visible ->
                runForegroundOrVisible(priority, key, reason, visibleSemaphore, block)
            SlockRequestPriority.Background -> runBackground(priority, key, reason, block)
        }

    private suspend fun <T> runForegroundOrVisible(
        priority: SlockRequestPriority,
        key: String?,
        reason: String?,
        semaphore: Semaphore,
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
            return block()
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
        priority: SlockRequestPriority,
        key: String?,
        reason: String?,
        block: suspend () -> T
    ): T {
        val queuedAt = TimeSource.Monotonic.markNow()
        backgroundSemaphore.acquire()
        var startedAtMs = queuedAt.elapsedNow().inWholeMilliseconds
        try {
            while (hasForegroundOrVisibleWork()) {
                delay(25)
            }
            startedAtMs = queuedAt.elapsedNow().inWholeMilliseconds
            logStart(priority, key, reason, startedAtMs)
            return block()
        } finally {
            val finishedAtMs = queuedAt.elapsedNow().inWholeMilliseconds
            logFinish(
                priority = priority,
                key = key,
                reason = reason,
                queuedMs = startedAtMs,
                runningMs = finishedAtMs - startedAtMs
            )
            backgroundSemaphore.release()
        }
    }

    private suspend fun hasForegroundOrVisibleWork(): Boolean =
        mutex.withLock { foregroundOrVisibleWaiting > 0 || foregroundOrVisibleActive > 0 }

    private fun logStart(
        priority: SlockRequestPriority,
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
        priority: SlockRequestPriority,
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

    private fun SlockRequestPriority.laneName(): String =
        when (this) {
            SlockRequestPriority.Foreground -> "foreground"
            SlockRequestPriority.Visible -> "visible"
            SlockRequestPriority.Background -> "background"
        }
}
