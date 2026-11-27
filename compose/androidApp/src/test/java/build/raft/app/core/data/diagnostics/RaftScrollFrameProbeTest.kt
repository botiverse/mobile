package build.raft.app.core.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftScrollFrameProbeTest {
    private val ms = 1_000_000L
    private val interval120Hz = 8_333_333L
    private val interval90Hz = 11_111_111L
    private val interval60Hz = 16_666_666L

    @Test
    fun ltpo120HzTo60HzDownclockCountsZeroFalseJank() {
        val start = 10_000L * ms
        val stats = RaftScrollFrameStats(startNanos = start, frameIntervalNanos = interval120Hz)
        var t = start + 4L * ms
        stats.onScrollVsync(frameTimeNanos = t, nowNanos = t, liveFrameIntervalNanos = interval120Hz)
        repeat(20) {
            t += interval120Hz
            stats.onScrollVsync(frameTimeNanos = t, nowNanos = t, liveFrameIntervalNanos = interval120Hz)
        }
        repeat(30) {
            t += interval60Hz
            stats.onScrollVsync(frameTimeNanos = t, nowNanos = t, liveFrameIntervalNanos = interval60Hz)
        }
        val summary = stats.summary(t)
        assertEquals(50, summary.frames)
        assertEquals(0, summary.jankFrames)
        assertEquals(interval120Hz, summary.frameIntervalNanos)
        assertEquals(interval60Hz, summary.endFrameIntervalNanos)
        assertTrue(summary.wireFields().contains("vsyncMs=8.3 vsyncEndMs=16.6"))
        assertTrue(
            raftScrollFramesLine(pageName = "thread", list = "messages", reason = "idle", summary = summary)
                .contains("vsyncMs=8.3 vsyncEndMs=16.6"),
        )
        assertTrue(
            raftNavTransitionFramesLine(
                kind = build.raft.app.nav.RaftStackTransitionKind.Push,
                outcome = build.raft.app.nav.RaftStackTransitionOutcome.Completed,
                fromPage = "home",
                toPage = "thread",
                firstFrameMs = 4L,
                summary = summary,
            ).contains("vsyncMs=8.3 vsyncEndMs=16.6"),
        )
    }

    @Test
    fun midFrame60HzTo120HzDisplayChangeDoesNotFlagTransitionFrameAsJank() {
        val start = 15_000L * ms
        val stats = RaftScrollFrameStats(startNanos = start, frameIntervalNanos = interval60Hz)
        var t = start + 5L * ms
        stats.onVsync(frameTimeNanos = t, liveFrameIntervalNanos = interval60Hz)
        // Display listener updates to 120 Hz mid-frame before the 16.6 ms transition vsync arrives.
        stats.onFrameIntervalChanged(interval120Hz)
        t += interval60Hz
        stats.onVsync(frameTimeNanos = t, liveFrameIntervalNanos = interval120Hz)
        val summary = stats.summary(t)
        assertEquals(1, summary.frames)
        assertEquals(0, summary.jankFrames)
        assertEquals(interval120Hz, summary.endFrameIntervalNanos)
    }

    @Test
    fun rampUpFrom60HzTo120HzTracksEndIntervalAndCatches18MsHitch() {
        val start = 20_000L * ms
        val stats = RaftScrollFrameStats(startNanos = start, frameIntervalNanos = interval60Hz)
        var t = start + 5L * ms
        stats.onVsync(frameTimeNanos = t, liveFrameIntervalNanos = interval60Hz)
        repeat(5) {
            t += interval60Hz
            stats.onVsync(frameTimeNanos = t, liveFrameIntervalNanos = interval60Hz)
        }
        // Display ramps up to 120 Hz: the first frame across the transition uses max(16.6, 8.3).
        t += interval120Hz
        stats.onVsync(frameTimeNanos = t, liveFrameIntervalNanos = interval120Hz)
        // Next steady 120 Hz frame stalls for 18 ms (>= 1.75 * 8.33 ms = 14.58 ms) -> counts as jank.
        t += 18L * ms
        stats.onVsync(frameTimeNanos = t, liveFrameIntervalNanos = interval120Hz)

        val summary = stats.summary(t)
        assertEquals(7, summary.frames)
        assertEquals(1, summary.jankFrames)
        assertEquals(interval60Hz, summary.frameIntervalNanos)
        assertEquals(interval120Hz, summary.endFrameIntervalNanos)
        assertTrue(summary.wireFields().contains("vsyncMs=16.6 vsyncEndMs=8.3"))
    }

    @Test
    fun invalidLiveIntervalsAreIgnored() {
        val start = 30_000L * ms
        val stats = RaftScrollFrameStats(startNanos = start, frameIntervalNanos = interval90Hz)
        stats.onFrameIntervalChanged(0L)
        stats.onFrameIntervalChanged(-10L)
        stats.onFrameIntervalChanged(1_000_000L)
        stats.onFrameIntervalChanged(500_000_000L)
        val summary = stats.summary(start + 100L * ms)
        assertEquals(interval90Hz, summary.endFrameIntervalNanos)
    }
}
