package build.raft.realtime.socket

import kotlin.math.min
import kotlin.random.Random

/**
 * Exponential backoff with jitter for reconnect attempts: `min(max, initial * 2^failures)`, then a
 * uniform ±[jitter] share of it, never below [initialDelayMillis] / 2 nor above [maxDelayMillis].
 * Unlimited attempts; the client resets the failure count after a successful Socket.IO connect.
 *
 * Defaults: 1 s to 30 s with ±50 % jitter (iOS's Socket.IO-Client-Swift used 1-30 s without jitter,
 * socket.io-client-java 1-5 s with 0.5).
 */
data class RaftReconnectPolicy(
    val initialDelayMillis: Long = 1_000L,
    val maxDelayMillis: Long = 30_000L,
    val jitter: Double = 0.5,
) {
    init {
        require(initialDelayMillis > 0) { "initialDelayMillis must be positive" }
        require(maxDelayMillis >= initialDelayMillis) { "maxDelayMillis must be >= initialDelayMillis" }
        require(jitter in 0.0..1.0) { "jitter must be within 0..1" }
    }

    /** The delay before the next attempt after [failures] consecutive failed attempts (0 = first retry). */
    fun delayMillis(failures: Int, random: Random): Long {
        val exponent = failures.coerceIn(0, 30)
        val base = min(maxDelayMillis.toDouble(), initialDelayMillis.toDouble() * (1L shl exponent).toDouble())
        val spread = base * jitter
        val jittered = if (spread > 0.0) base - spread + random.nextDouble() * 2.0 * spread else base
        val floor = (initialDelayMillis / 2).coerceAtLeast(1L)
        return jittered.toLong().coerceIn(floor, maxDelayMillis)
    }
}
