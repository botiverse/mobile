package build.raft.app.core.data.diagnostics

import build.raft.app.nav.RaftStackTransitionKind
import build.raft.app.nav.RaftStackTransitionOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class RaftNavTransitionFrameProbeTest {
    private val vsync90 = 11_111_111L

    @Test
    fun pushLineCarriesKindOutcomePagesAndTheScrollFrameFields() {
        // A 300 ms push at 90 Hz with one missed vsync: the same stats and jank rule as scroll_frames.
        val stats = RaftScrollFrameStats(startNanos = 0L, frameIntervalNanos = vsync90)
        var t = 0L
        stats.onVsync(t)
        repeat(24) { t += vsync90; stats.onVsync(t) }
        t += 2 * vsync90; stats.onVsync(t)
        val line =
            raftNavTransitionFramesLine(
                kind = RaftStackTransitionKind.Push,
                outcome = RaftStackTransitionOutcome.Completed,
                fromPage = "compose_slock_app_shell",
                toPage = "compose_app_shell_server_page",
                firstFrameMs = raftNavTransitionFirstFrameMs(startNanos = 0L, firstFrameNanos = 0L),
                summary = stats.summary(nowNanos = t),
            )

        assertEquals(
            "nav_transition_frames kind=push outcome=completed from=compose_slock_app_shell " +
                "to=compose_app_shell_server_page firstFrameMs=0 durationMs=288 frames=25 jankFrames=1 longestFrameMs=22 " +
                "p95FrameMs=12 minFrameMs=11.1 vsyncMs=11.1 vsyncEndMs=11.1",
            line
        )
    }

    @Test
    fun cancelledPredictiveBackWithoutFramesReportsZeros() {
        val stats = RaftScrollFrameStats(startNanos = 0L, frameIntervalNanos = vsync90)
        stats.onVsync(1_000L)
        val line =
            raftNavTransitionFramesLine(
                kind = RaftStackTransitionKind.PredictiveBack,
                outcome = RaftStackTransitionOutcome.Cancelled,
                fromPage = "compose_thread",
                toPage = "compose_slock_app_shell",
                firstFrameMs = raftNavTransitionFirstFrameMs(startNanos = 0L, firstFrameNanos = -1L),
                summary = stats.summary(nowNanos = 2_000L),
                endFrameIntervalNanos = 16_666_666L,
            )

        assertEquals(
            "nav_transition_frames kind=predictive_back outcome=cancelled from=compose_thread to=compose_slock_app_shell " +
                "firstFrameMs=-1 durationMs=0 frames=0 jankFrames=0 longestFrameMs=0 p95FrameMs=0 minFrameMs=0.0 vsyncMs=11.1 vsyncEndMs=16.6",
            line
        )
    }

    @Test
    fun firstFrameLatencyRunsFromTheNavigateCallToTheFirstFrame() {
        // A chat push whose first frame took 140 ms to build the page.
        assertEquals(140L, raftNavTransitionFirstFrameMs(startNanos = 1_000_000_000L, firstFrameNanos = 1_140_400_000L))
        // A vsync stamped just before the start (the callback ran in the same frame): 0, never negative.
        assertEquals(0L, raftNavTransitionFirstFrameMs(startNanos = 1_000_000_000L, firstFrameNanos = 999_000_000L))
    }
}
