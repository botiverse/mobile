package build.raft.app.core.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class RaftScrollFrameStatsTest {
    private val vsync90 = 11_111_111L

    @Test
    fun countsMissedVsyncsLongestAndP95FrameGap() {
        val stats = RaftScrollFrameStats(startNanos = 0L, frameIntervalNanos = vsync90)
        var t = 5_000_000L
        stats.onVsync(t) // anchor only
        repeat(18) { t += vsync90; stats.onVsync(t) } // on time at 90 Hz
        t += 2 * vsync90; stats.onVsync(t) // one missed vsync (22 ms)
        t += 100_000_000L; stats.onVsync(t) // 100 ms stall
        val summary = stats.summary(nowNanos = t)

        assertEquals(20, summary.frames)
        assertEquals(2, summary.jankFrames)
        assertEquals(100L, summary.longestFrameMs)
        // 19th of 20 sorted gaps is the 22 ms frame -> bucket [22, 23).
        assertEquals(23L, summary.p95FrameMs)
        assertEquals(11.1, summary.shortestFrameMs, 0.02)
    }

    @Test
    fun sixtyHertzCadenceOnANinetyHertzEstimateIsNotJank() {
        val stats = RaftScrollFrameStats(startNanos = 0L, frameIntervalNanos = vsync90)
        var t = 0L
        stats.onVsync(t)
        repeat(10) { t += 16_666_666L; stats.onVsync(t) }
        val summary = stats.summary(nowNanos = t)

        assertEquals(10, summary.frames)
        assertEquals(0, summary.jankFrames)
        assertEquals(17L, summary.p95FrameMs)
        assertEquals(
            "durationMs=166 frames=10 jankFrames=0 longestFrameMs=16 p95FrameMs=17 minFrameMs=16.6 " +
                "vsyncMs=11.1 vsyncEndMs=16.6",
            summary.wireFields(endFrameIntervalNanos = 16_666_666L)
        )
    }

    @Test
    fun noFramesReportsZeros() {
        val stats = RaftScrollFrameStats(startNanos = 0L, frameIntervalNanos = vsync90)
        stats.onVsync(1_000L)
        val summary = stats.summary(nowNanos = 2_000L)

        assertEquals(0, summary.frames)
        assertEquals(0L, summary.p95FrameMs)
        assertEquals(0.0, summary.shortestFrameMs, 0.0)
    }
}
