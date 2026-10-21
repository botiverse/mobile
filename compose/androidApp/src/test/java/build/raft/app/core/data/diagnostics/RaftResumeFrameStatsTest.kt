package build.raft.app.core.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class RaftResumeFrameStatsTest {
    private val vsync = 16_000_000L

    @Test
    fun summarisesFirstDrawLateVsyncsAndLongestMainThreadGap() {
        val stats = RaftResumeFrameStats(startNanos = 0L, frameIntervalNanos = vsync)

        stats.onFrameCallback(10_000_000L) // resume -> first vsync, on time
        stats.onFirstDraw(25_000_000L)
        stats.onFrameCallback(26_000_000L) // on time
        stats.onFrameCallback(226_000_000L) // main thread blocked ~200 ms
        stats.onFrameCallback(242_000_000L) // on time
        stats.onFrameCallback(290_000_000L) // missed two vsyncs
        val summary = stats.summary(nowNanos = 3_000_000_000L)

        assertEquals(3_000L, summary.windowMs)
        assertEquals(25L, summary.firstDrawMs)
        assertEquals(5, summary.frames)
        assertEquals(2, summary.jankFrames)
        assertEquals((200L - 16L) + (48L - 16L), summary.blockedMs)
        assertEquals(200L, summary.longestGapMs)
    }

    @Test
    fun onTimeFramesWithoutADrawReportNoJankAndUnknownFirstFrame() {
        val stats = RaftResumeFrameStats(startNanos = 5_000L, frameIntervalNanos = vsync)

        stats.onFrameCallback(5_000L + vsync)
        stats.onFrameCallback(5_000L + 2 * vsync)
        val summary = stats.summary(nowNanos = 5_000L + 2 * vsync)

        assertEquals(-1L, summary.firstDrawMs)
        assertEquals(2, summary.frames)
        assertEquals(0, summary.jankFrames)
        assertEquals(0L, summary.blockedMs)
    }
}
