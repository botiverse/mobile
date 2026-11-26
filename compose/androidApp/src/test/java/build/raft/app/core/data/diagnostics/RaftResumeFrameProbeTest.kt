package build.raft.app.core.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftResumeFrameProbeTest {
    private val ms = 1_000_000L
    private val interval120Hz = 8_333_333L
    private val interval90Hz = 11_111_111L
    private val interval60Hz = 16_666_666L

    @Test
    fun steady120HzProducesZeroJankAndMatchingEndInterval() {
        val start = 1_000L * ms
        val stats = RaftResumeFrameStats(startNanos = start, frameIntervalNanos = interval120Hz)
        var t = start
        stats.onFirstDraw(start + 10L * ms)
        repeat(60) {
            t += interval120Hz
            stats.onFrameCallback(nowNanos = t, liveFrameIntervalNanos = interval120Hz)
        }
        val summary = stats.summary(t)
        assertEquals(60, summary.frames)
        assertEquals(0, summary.jankFrames)
        assertEquals(0L, summary.blockedMs)
        assertEquals(10L, summary.firstDrawMs)
        assertEquals(interval120Hz, summary.endFrameIntervalNanos)
        assertEquals(0, summary.rateSwitches)
        assertTrue(summary.wireFields().contains("vsyncMs=8.3 vsyncEndMs=8.3"))
        assertFalse(summary.wireFields().contains("rateSwitches="))
    }

    @Test
    fun ltpo120HzTo60HzIdleDownclockProducesZeroFalseJank() {
        // Mirrors vivo V2419A / PNM-AN10 / SM-S9110 (#proj-mobile:#269): starts at 120 Hz on resume,
        // then steps down to 60 Hz while idle. Every steady 60 Hz frame (~16.67 ms) must count as
        // on-time against the live 60 Hz interval instead of adding +8.3 ms fake blockedMs per frame.
        val start = 10_000L * ms
        val stats = RaftResumeFrameStats(startNanos = start, frameIntervalNanos = interval120Hz)
        var t = start
        repeat(60) {
            t += interval120Hz
            stats.onFrameCallback(nowNanos = t, liveFrameIntervalNanos = interval120Hz)
        }
        // LTPO downclock to 60 Hz for the remaining ~2 seconds (120 steady 60 Hz frames).
        repeat(120) {
            t += interval60Hz
            stats.onFrameCallback(nowNanos = t, liveFrameIntervalNanos = interval60Hz)
        }
        val summary = stats.summary(t)
        assertEquals(180, summary.frames)
        assertEquals(0, summary.jankFrames)
        assertEquals(0L, summary.blockedMs)
        assertEquals(interval60Hz, summary.endFrameIntervalNanos)
        assertEquals(1, summary.rateSwitches)
        assertTrue(summary.wireFields().contains("vsyncMs=8.3 vsyncEndMs=16.6 rateSwitches=1"))
    }

    @Test
    fun realMainThreadStallsAreStillCountedAtBoth90HzAnd60Hz() {
        // Mirrors Huawei GRL-AL10 / ALT-AL10 / HOP-AL10 (#proj-mobile:#269): real 48 ms and 120 ms
        // main-thread stalls must still count as jank and accumulate accurate blockedMs.
        val start = 20_000L * ms
        val stats = RaftResumeFrameStats(startNanos = start, frameIntervalNanos = interval90Hz)
        var t = start
        t += interval90Hz
        stats.onFrameCallback(nowNanos = t, liveFrameIntervalNanos = interval90Hz)
        // 48 ms stall at 90 Hz -> blocked = 48 - 11.11 = 36.88 ms.
        t += 48L * ms
        stats.onFrameCallback(nowNanos = t, liveFrameIntervalNanos = interval90Hz)
        // Downclock to 60 Hz, then a real 120 ms stall -> blocked = 120 - 16.67 = 103.33 ms.
        t += interval60Hz
        stats.onFrameCallback(nowNanos = t, liveFrameIntervalNanos = interval60Hz)
        t += 120L * ms
        stats.onFrameCallback(nowNanos = t, liveFrameIntervalNanos = interval60Hz)

        val summary = stats.summary(t)
        assertEquals(4, summary.frames)
        assertEquals(2, summary.jankFrames)
        assertEquals(120L, summary.longestGapMs)
        // 36.88 ms + 103.33 ms = 140.22 ms -> 140 ms.
        assertEquals(140L, summary.blockedMs)
        assertEquals(interval60Hz, summary.endFrameIntervalNanos)
        assertEquals(1, summary.rateSwitches)
    }

    @Test
    fun rampUpFrom60HzTo90HzTracksEndRateAndCatches25MsHitch() {
        val start = 30_000L * ms
        val stats = RaftResumeFrameStats(startNanos = start, frameIntervalNanos = interval60Hz)
        var t = start
        repeat(5) {
            t += interval60Hz
            stats.onFrameCallback(nowNanos = t, liveFrameIntervalNanos = interval60Hz)
        }
        // Display ramps up to 90 Hz via onFrameIntervalChanged; 25 ms >= 2 * 11.11 ms counts as jank.
        stats.onFrameIntervalChanged(interval90Hz)
        t += 25L * ms
        stats.onFrameCallback(nowNanos = t)

        val summary = stats.summary(t)
        assertEquals(1, summary.jankFrames)
        assertEquals(13L, summary.blockedMs)
        assertEquals(interval90Hz, summary.endFrameIntervalNanos)
        assertEquals(1, summary.rateSwitches)
        assertTrue(summary.wireFields().contains("vsyncMs=16.6 vsyncEndMs=11.1 rateSwitches=1"))
    }

    @Test
    fun invalidIntervalUpdatesAreIgnored() {
        val start = 40_000L * ms
        val stats = RaftResumeFrameStats(startNanos = start, frameIntervalNanos = interval90Hz)
        stats.onFrameIntervalChanged(0L)
        stats.onFrameIntervalChanged(-1L)
        stats.onFrameIntervalChanged(1_000_000L) // 1000 Hz out of bounds
        stats.onFrameIntervalChanged(500_000_000L) // 2 Hz out of bounds
        val summary = stats.summary(start + 100L * ms)
        assertEquals(interval90Hz, summary.endFrameIntervalNanos)
        assertEquals(0, summary.rateSwitches)
    }

    @Test
    fun raftFrameTimelineIntervalNanosExtractsMinimumValidStep() {
        assertNull(raftFrameTimelineIntervalNanos(longArrayOf()))
        assertNull(raftFrameTimelineIntervalNanos(longArrayOf(100L * ms)))
        assertNull(raftFrameTimelineIntervalNanos(longArrayOf(100L * ms, 100L * ms)))
        assertNull(raftFrameTimelineIntervalNanos(longArrayOf(100L * ms, 101L * ms))) // < 4 ms
        assertEquals(
            interval60Hz,
            raftFrameTimelineIntervalNanos(
                longArrayOf(100L * ms, 100L * ms + interval60Hz, 100L * ms + 2 * interval60Hz),
            ),
        )
        assertEquals(
            interval120Hz,
            raftFrameTimelineIntervalNanos(
                longArrayOf(100L * ms, 100L * ms + interval60Hz, 100L * ms + interval60Hz + interval120Hz),
            ),
        )
    }
}
