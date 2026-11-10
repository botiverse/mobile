package build.raft.app.network

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource
import kotlinx.coroutines.TimeoutCancellationException
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

/**
 * Runs every request at once under its wall-clock bound, whatever its priority; the priority is
 * only logged. Lanes are separated by the HTTP engine (one OkHttp Dispatcher / NSURLSession per
 * priority), not here.
 */
class RaftRequestScheduler(
    private val requestTimeoutMillis: Long = 30_000,
    private val logger: (String) -> Unit = {}
) {
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
         * itself fails.
         */
        timeoutMillis: Long? = null,
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

    private fun RaftRequestPriority.laneName(): String =
        when (this) {
            RaftRequestPriority.Foreground -> "foreground"
            RaftRequestPriority.Visible -> "visible"
            RaftRequestPriority.Background -> "background"
        }
}
