package build.raft.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #164: the recovery schedule of a stale read, as the web's (botiverse/slock #11156 da23419e,
 * `store/rwStaleCounts.ts` `rwRecoveryDelayMs` / `withJitter` / `startRwRecovery`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RaftStaleReadRecoveryTest {
    @Test
    fun theFirstReadWaitsForRetryAfterThenFiveTenTwentyFortySixtySeconds() {
        val noJitter = FixedRandom(0.5)
        assertEquals(2_000L, raftStaleRecoveryDelayMillis(0, retryAfterMillis = 2_000L, random = noJitter))
        assertEquals(5_000L, raftStaleRecoveryDelayMillis(0, retryAfterMillis = null, random = noJitter))
        assertEquals(60_000L, raftStaleRecoveryDelayMillis(0, retryAfterMillis = 600_000L, random = noJitter), "capped")
        assertEquals(
            listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L),
            (1..6).map { raftStaleRecoveryDelayMillis(it, retryAfterMillis = 2_000L, random = noJitter) }
        )
        assertEquals(32_000L, raftStaleRecoveryDelayMillis(4, null, random = FixedRandom(0.0)), "-20 %")
        assertEquals(48_000L, raftStaleRecoveryDelayMillis(4, null, random = FixedRandom(1.0)), "+20 %")
        assertEquals(60_000L, raftStaleRecoveryDelayMillis(5, null, random = FixedRandom(1.0)), "never past 60 s")
    }

    @Test
    fun retryAfterReadsSecondsOrAnHttpDate() {
        fun response(value: String?) =
            HomeNetworkResponse(503, null, responseHeaders = value?.let { mapOf("retry-after" to listOf(it)) }.orEmpty())
        val now = parseRaftHttpDateMillis("Sat, 24 Oct 2026 09:53:00 GMT")!!
        assertEquals(2_000L, response("2").raftRetryAfterMillis { now })
        assertEquals(7_000L, response("Sat, 24 Oct 2026 09:53:07 GMT").raftRetryAfterMillis { now })
        assertEquals(0L, response("Sat, 24 Oct 2026 09:52:00 GMT").raftRetryAfterMillis { now }, "a past date waits nothing")
        assertNull(response("soon").raftRetryAfterMillis { now })
        assertNull(response(null).raftRetryAfterMillis { now })
        assertEquals(1_792_835_580_000L, now, "IMF-fixdate to epoch millis")
    }

    @Test
    fun aTimerThatEndsInTheBackgroundWaitsAndTheReturnToTheForegroundReadsAtOnce() = runTest {
        val foreground = MutableStateFlow(true)
        val nudges = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val reads = mutableListOf<Long>()
        val recovery = recovery(foreground, nudges)
        recovery.schedule(retryAfterMillis = null) { reads += testScheduler.currentTime; true }

        foreground.value = false
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(emptyList(), reads, "no read in the background")

        foreground.value = true
        nudges.emit("foreground")
        runCurrent()
        assertEquals(listOf(10_000L), reads, "the return reads at once")
    }

    @Test
    fun aSocketReconnectReadsAtOnceInsteadOfWaitingAndAFreshAnswerStopsEverything() = runTest {
        val nudges = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val reads = mutableListOf<Long>()
        val recovery = recovery(MutableStateFlow(true), nudges)
        recovery.schedule(retryAfterMillis = 2_000L) { reads += testScheduler.currentTime; true }
        advanceTimeBy(500)
        nudges.emit("socket_reconnect")
        runCurrent()
        assertEquals(listOf(500L), reads)
        nudges.emit("socket_reconnect")
        runCurrent()
        assertEquals(1, reads.size, "a read already running is not doubled")

        recovery.reset()
        nudges.emit("socket_reconnect")
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, reads.size, "disarmed: no timer, no nudge")
        assertTrue(!recovery.armed)
    }

    @Test
    fun eachStaleAnswerArmsTheNextBackoffStep() = runTest {
        val reads = mutableListOf<Long>()
        val recovery = recovery(MutableStateFlow(true), MutableSharedFlow())
        lateinit var read: suspend () -> Boolean
        read = {
            reads += testScheduler.currentTime
            recovery.schedule(retryAfterMillis = null, read) // the store found it still stale
            true
        }
        recovery.schedule(retryAfterMillis = 3_000L, read)
        advanceTimeBy(3_000 + 5_000 + 10_000 + 20_000 + 1)
        runCurrent()
        assertEquals(listOf(3_000L, 8_000L, 18_000L, 38_000L), reads)
        recovery.reset()
    }

    private fun TestScope.recovery(
        foreground: MutableStateFlow<Boolean>,
        nudges: MutableSharedFlow<String>,
    ) = RaftStaleReadRecovery(
        scope = backgroundScope,
        label = "test",
        delayMillis = { attempt, retryAfter -> raftStaleRecoveryDelayMillis(attempt, retryAfter, FixedRandom(0.5)) },
        foreground = foreground,
        nudges = nudges,
    )

    /** `nextDouble()` always [value]: 0.5 is no jitter, 0 is -20 %, 1 is +20 %. */
    private class FixedRandom(private val value: Double) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(): Double = value
    }
}
