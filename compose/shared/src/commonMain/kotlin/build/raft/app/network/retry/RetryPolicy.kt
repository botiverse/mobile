package build.raft.app.network.retry

import build.raft.app.network.model.NetworkIssue
import kotlin.math.min
import kotlin.random.Random

data class RetryPolicy(
    val maxAttempts: Int = 5,
    val baseDelayMillis: Long = 500,
    val maxDelayMillis: Long = 30_000,
    val jitterRatio: Double = 0.2
) {
    init {
        require(maxAttempts >= 0) { "maxAttempts must be >= 0" }
        require(baseDelayMillis >= 0) { "baseDelayMillis must be >= 0" }
        require(maxDelayMillis >= baseDelayMillis) {
            "maxDelayMillis must be >= baseDelayMillis"
        }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be in 0.0..1.0" }
    }
}

class BackoffCalculator(
    private val random: Random = Random.Default
) {
    fun delayMillis(
        attempt: Int,
        policy: RetryPolicy,
        issue: NetworkIssue? = null
    ): Long? {
        if (attempt >= policy.maxAttempts) return null
        issue?.retryAfterMillis?.let { retryAfter ->
            return retryAfter.coerceIn(0, policy.maxDelayMillis)
        }
        val safeAttempt = attempt.coerceAtLeast(0)
        val exponential = policy.baseDelayMillis.saturatingShiftLeft(safeAttempt)
        val capped = min(exponential, policy.maxDelayMillis)
        if (capped == 0L || policy.jitterRatio == 0.0) return capped

        val jitterWindow = (capped * policy.jitterRatio).toLong().coerceAtLeast(1)
        val minDelay = (capped - jitterWindow).coerceAtLeast(0)
        val maxDelay = (capped + jitterWindow).coerceAtMost(policy.maxDelayMillis)
        if (maxDelay <= minDelay) return minDelay
        return random.nextLong(minDelay, maxDelay + 1)
    }

    fun shouldRetry(attempt: Int, policy: RetryPolicy, issue: NetworkIssue?): Boolean =
        issue?.retryable == true && attempt < policy.maxAttempts

    private fun Long.saturatingShiftLeft(bits: Int): Long {
        var result = this
        repeat(bits) {
            if (result > Long.MAX_VALUE / 2) return Long.MAX_VALUE
            result *= 2
        }
        return result
    }
}
