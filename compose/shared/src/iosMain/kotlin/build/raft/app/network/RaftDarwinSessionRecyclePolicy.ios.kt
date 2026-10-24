package build.raft.app.network

/**
 * When a lane's NSURLSession should be replaced (Hands 43837e84). Android's OkHttp pings HTTP/2
 * connections and health-checks them before reuse; NSURLSession does neither, so a reused connection
 * that stops answering keeps failing every request of its lane after the 20 s idle timeout.
 *
 * - [timeoutsToRecycle] timeouts in a row on the current session (no answer before the idle timeout)
 *   replace it; one timeout alone can be a slow endpoint.
 * - Any answer on the current session resets the count.
 * - Outcomes of calls that ran on an earlier session ([generation]) are ignored: the burst of timeouts
 *   still draining from a replaced session says nothing about the new one.
 * - At most one replacement per [minIntervalMillis], so a network that is down for everyone does not
 *   churn sessions.
 *
 * Not thread-safe: the lane calls it under its lock.
 */
internal class RaftDarwinSessionRecyclePolicy(
    private val timeoutsToRecycle: Int = 2,
    private val minIntervalMillis: Long = 10_000L,
) {
    class Recycle(val reason: String, val consecutiveTimeouts: Int, val generation: Long)

    var generation: Long = 0L
        private set

    private var consecutiveTimeouts = 0
    private var lastRecycleAtMillis: Long? = null

    /** Feeds one finished call; returns a [Recycle] (generation already advanced) when the session must go. */
    fun record(callGeneration: Long, timedOut: Boolean, nowMillis: Long): Recycle? {
        if (callGeneration != generation) return null
        if (!timedOut) {
            consecutiveTimeouts = 0
            return null
        }
        consecutiveTimeouts += 1
        if (consecutiveTimeouts < timeoutsToRecycle) return null
        val last = lastRecycleAtMillis
        if (last != null && nowMillis - last < minIntervalMillis) return null
        val timeouts = consecutiveTimeouts
        consecutiveTimeouts = 0
        lastRecycleAtMillis = nowMillis
        generation += 1
        return Recycle(reason = "consecutive_timeouts", consecutiveTimeouts = timeouts, generation = generation)
    }
}
