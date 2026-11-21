package build.raft.app.core.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun scrollStartFirstFrameStallCountsAsJankWhileSmoothStartPreservesMinCadence() {
        // Smooth start (6 ms partial first vsync) does not distort minFrameMs or jankFrames.
        val smooth = RaftScrollFrameStats(startNanos = 0L, frameIntervalNanos = vsync90)
        var t = 6_000_000L
        smooth.onScrollVsync(frameTimeNanos = t, nowNanos = t)
        repeat(8) {
            t += vsync90
            smooth.onScrollVsync(frameTimeNanos = t, nowNanos = t)
        }
        val smoothSummary = smooth.summary(nowNanos = t)
        assertEquals(6L, smoothSummary.firstFrameMs)
        assertEquals(8, smoothSummary.frames)
        assertEquals(0, smoothSummary.jankFrames)
        assertEquals(11L, smoothSummary.longestFrameMs)
        assertEquals(11.1, smoothSummary.shortestFrameMs, 0.02)

        // Opening frame stalls for 78 ms on main thread -> counted as jank and longestFrameMs.
        val stalled = RaftScrollFrameStats(startNanos = 0L, frameIntervalNanos = vsync90)
        var ts = 78_000_000L
        stalled.onScrollVsync(frameTimeNanos = ts, nowNanos = ts)
        repeat(5) {
            ts += vsync90
            stalled.onScrollVsync(frameTimeNanos = ts, nowNanos = ts)
        }
        val stalledSummary = stalled.summary(nowNanos = ts)
        assertEquals(78L, stalledSummary.firstFrameMs)
        assertEquals(6, stalledSummary.frames)
        assertEquals(1, stalledSummary.jankFrames)
        assertEquals(78L, stalledSummary.longestFrameMs)
        assertEquals(11.1, stalledSummary.shortestFrameMs, 0.02)

        // Single-frame opening stall (short scroll that stopped right after the hitch): still has valid shortestFrameMs.
        val singleStall = RaftScrollFrameStats(startNanos = 0L, frameIntervalNanos = vsync90)
        singleStall.onScrollVsync(frameTimeNanos = 65_000_000L, nowNanos = 65_000_000L)
        val singleSummary = singleStall.summary(nowNanos = 65_000_000L)
        assertEquals(65L, singleSummary.firstFrameMs)
        assertEquals(1, singleSummary.frames)
        assertEquals(1, singleSummary.jankFrames)
        assertEquals(65L, singleSummary.longestFrameMs)
        assertEquals(65.0, singleSummary.shortestFrameMs, 0.02)
    }

    @Test
    fun throttleCarriesSkippedJankAndLongestFrameOntoNextLineAndFlushesOnPause() {
        val throttle = RaftScrollFrameThrottle(minIntervalMillis = 1_000L)
        val smooth =
            RaftScrollFrameStats.Summary(
                durationMs = 300L,
                frames = 27,
                jankFrames = 0,
                longestFrameMs = 11L,
                shortestFrameMs = 11.1,
                p95FrameMs = 12L,
                frameIntervalNanos = vsync90,
                firstFrameMs = 8L,
            )
        val janky1 = smooth.copy(jankFrames = 2, longestFrameMs = 44L)
        val janky2 = smooth.copy(jankFrames = 1, longestFrameMs = 88L)

        // First scroll emits with zero skipped.
        val first = throttle.record(nowMillis = 1_000L, list = "thread", summary = smooth)
        assertEquals(RaftScrollSkippedCarryover(sessions = 0, jankFrames = 0, longestFrameMs = 0L), first)
        assertEquals(
            "scroll_frames page=compose_slock_app_shell list=thread reason=stopped firstFrameMs=8 " +
                "durationMs=300 frames=27 jankFrames=0 longestFrameMs=11 p95FrameMs=12 minFrameMs=11.1 " +
                "vsyncMs=11.1 vsyncEndMs=11.1 skippedSessions=0",
            raftScrollFramesLine("compose_slock_app_shell", "thread", "stopped", smooth, vsync90, first!!),
        )

        // Two rapid scrolls within 1s are throttled, accumulating their jank and worst frame gap.
        assertNull(throttle.record(nowMillis = 1_400L, list = "thread", summary = janky1))
        assertNull(throttle.record(nowMillis = 1_850L, list = "thread", summary = janky2))

        // Next scroll after 1s emits and carries both skipped sessions' worst frame and total jank.
        val next = throttle.record(nowMillis = 2_100L, list = "inbox", summary = smooth)!!
        assertEquals(2, next.sessions)
        assertEquals(3, next.jankFrames)
        assertEquals(88L, next.longestFrameMs)
        assertEquals(
            "scroll_frames page=compose_slock_app_shell list=inbox reason=stopped firstFrameMs=8 " +
                "durationMs=300 frames=27 jankFrames=0 longestFrameMs=11 p95FrameMs=12 minFrameMs=11.1 " +
                "vsyncMs=11.1 vsyncEndMs=11.1 skippedSessions=2 skippedJankFrames=3 skippedLongestFrameMs=88",
            raftScrollFramesLine("compose_slock_app_shell", "inbox", "stopped", smooth, vsync90, next),
        )

        // After emitting, carryover resets; a skipped smooth scroll clears cleanly on pause without flushing.
        assertNull(throttle.record(nowMillis = 2_500L, list = "thread", summary = smooth))
        assertNull(throttle.flushAndResetOnPause())

        // A skipped scroll with jank flushes on pause so it is never lost when leaving the screen.
        assertNull(throttle.record(nowMillis = 2_700L, list = "thread", summary = janky2))
        val flushed = throttle.flushAndResetOnPause()!!
        assertEquals(1, flushed.sessions)
        assertEquals(1, flushed.jankFrames)
        assertEquals(88L, flushed.longestFrameMs)
        assertEquals(
            "scroll_frames page=compose_slock_app_shell list=thread reason=paused_skipped " +
                "skippedSessions=1 skippedJankFrames=1 skippedLongestFrameMs=88",
            raftScrollFramesSkippedFlushLine("compose_slock_app_shell", flushed),
        )
        assertNull(throttle.flushAndResetOnPause())
    }

    @Test
    fun openingStallWithUnclampedFrameTimestampDoesNotDoubleCountNextVsync() {
        val stats = RaftScrollFrameStats(startNanos = 0L, frameIntervalNanos = vsync90)
        // Main thread blocked 78 ms; Choreographer passed the earlier scheduled vsync (11.1 ms).
        stats.onScrollVsync(frameTimeNanos = vsync90, nowNanos = 78_000_000L)
        // Next frame arrives on cadence at 8 * vsync90 (~88.9 ms).
        stats.onScrollVsync(frameTimeNanos = 8 * vsync90, nowNanos = 8 * vsync90)
        val summary = stats.summary(nowNanos = 8 * vsync90)

        assertEquals(78L, summary.firstFrameMs)
        assertEquals(2, summary.frames)
        assertEquals(1, summary.jankFrames)
        assertEquals(78L, summary.longestFrameMs)
        assertEquals(11.1, summary.shortestFrameMs, 0.02)
    }
}
