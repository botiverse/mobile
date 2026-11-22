package build.raft.designsystem

import build.raft.designsystem.components.RaftPageLoadState
import build.raft.designsystem.components.RaftPageLoadingBarLine
import build.raft.designsystem.components.RaftPageLoadingBarPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The page load line's life as plain data ([RaftPageLoadingBarLine]): no clock, no composition. */
class RaftPageLoadingBarLineTest {
    private fun loading(progress: Float? = null) = RaftPageLoadState.Loading(progress)

    /** A line that is past its appear delay, after these reports. */
    private fun shown(vararg progress: Float?): RaftPageLoadingBarLine {
        var line = RaftPageLoadingBarLine().onState(loading(progress.first())).onAppearDelayPassed()
        for (value in progress.drop(1)) line = line.onState(loading(value))
        return line
    }

    @Test
    fun aLoadThatEndsInsideTheAppearDelayDrawsNoLine() {
        for (end in listOf(RaftPageLoadState.Finished, RaftPageLoadState.Failed, RaftPageLoadState.Idle)) {
            val ended = RaftPageLoadingBarLine().onState(loading(0.6f)).onState(end)
            assertEquals(RaftPageLoadingBarPhase.Hidden, ended.phase, "for $end")
            // The delay's timer firing late must not bring it back.
            assertEquals(RaftPageLoadingBarPhase.Hidden, ended.onAppearDelayPassed().phase, "for $end")
        }
        // A page that was never loading has no line to finish either.
        assertEquals(RaftPageLoadingBarPhase.Hidden, RaftPageLoadingBarLine().onState(RaftPageLoadState.Finished).phase)
    }

    @Test
    fun progressOnlyMovesForwardWithinOneLoad() {
        val line = shown(0.5f, 0.3f)
        assertEquals(0.5f, line.fraction)
        assertEquals(0.7f, line.onState(loading(0.7f)).fraction)
        assertEquals(1, line.load, "still the first load")
        assertTrue(line.reported)
    }

    @Test
    fun aFailedOrStoppedLoadHidesTheLineAtOnce() {
        for (end in listOf(RaftPageLoadState.Failed, RaftPageLoadState.Idle)) {
            assertEquals(RaftPageLoadingBarPhase.Hidden, shown(0.4f).onState(end).phase, "shown, $end")
            val leaving = shown(0.4f).onState(RaftPageLoadState.Finished)
            assertEquals(RaftPageLoadingBarPhase.Hidden, leaving.onState(end).phase, "leaving, $end")
        }
    }
}
