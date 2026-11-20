package build.raft.designsystem

import build.raft.designsystem.components.RaftPageLoadState
import build.raft.designsystem.components.RaftPageLoadingBarAppearDelayMillis
import build.raft.designsystem.components.RaftPageLoadingBarFadeOutMillis
import build.raft.designsystem.components.RaftPageLoadingBarLine
import build.raft.designsystem.components.RaftPageLoadingBarLoadingMaxFraction
import build.raft.designsystem.components.RaftPageLoadingBarPhase
import build.raft.designsystem.components.RaftPageLoadingBarProgressMillis
import build.raft.designsystem.components.RaftPageLoadingBarRestFraction
import build.raft.designsystem.components.raftPageLoadingBarLoadingFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun theSpecNumbers() {
        assertEquals(200, RaftPageLoadingBarAppearDelayMillis)
        assertEquals(200, RaftPageLoadingBarProgressMillis)
        assertEquals(200, RaftPageLoadingBarFadeOutMillis)
        assertEquals(0.08f, RaftPageLoadingBarRestFraction)
        assertEquals(0.95f, RaftPageLoadingBarLoadingMaxFraction)
    }

    @Test
    fun widthRestsAtEightPercentFollowsProgressAndStopsShortOfFullWhileLoading() {
        val cases =
            listOf<Pair<Float?, Float>>(
                null to 0.08f,
                0f to 0.08f,
                0.03f to 0.08f,
                0.42f to 0.42f,
                0.95f to 0.95f,
                1f to 0.95f,
                7f to 0.95f,
                -1f to 0.08f,
                Float.NaN to 0.08f,
            )
        for ((progress, expected) in cases) {
            assertEquals(expected, shown(progress).fraction, "for $progress")
        }
        assertEquals(0.08f, raftPageLoadingBarLoadingFraction(null))
        assertEquals(0.95f, raftPageLoadingBarLoadingFraction(1f))
    }

    @Test
    fun aLoadWaitsOutTheAppearDelayBeforeItsLineShows() {
        val waiting = RaftPageLoadingBarLine().onState(loading())
        assertEquals(RaftPageLoadingBarPhase.Waiting, waiting.phase)
        assertFalse(waiting.visible)
        // Progress inside the delay is kept, and does not show the line early.
        val progressed = waiting.onState(loading(0.3f))
        assertEquals(RaftPageLoadingBarPhase.Waiting, progressed.phase)
        val shown = progressed.onAppearDelayPassed()
        assertEquals(RaftPageLoadingBarPhase.Shown, shown.phase)
        assertTrue(shown.visible)
        assertEquals(0.3f, shown.fraction)
        // With no delay (screenshots) it shows at once.
        assertEquals(RaftPageLoadingBarPhase.Shown, RaftPageLoadingBarLine().onState(loading(), appearDelay = false).phase)
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
    fun aNewLoadNamedByTheHostStartsFromTheBeginning() {
        val first = shown(0.6f)
        // Whatever the new load reports first: nothing yet, less than the last one, or more.
        val cases = listOf<Pair<Float?, Float>>(null to 0.08f, 0.2f to 0.2f, 0.9f to 0.9f)
        for ((progress, expected) in cases) {
            val second = first.onState(RaftPageLoadState.Loading(progress, load = 1))
            assertEquals(expected, second.fraction, "for $progress")
            assertEquals(first.load + 1, second.load, "for $progress")
            assertEquals(progress != null, second.reported, "for $progress")
            // The line was already up: the new load does not hide it for another delay.
            assertEquals(RaftPageLoadingBarPhase.Shown, second.phase, "for $progress")
            // The same report again is the same load.
            assertEquals(second, second.onState(RaftPageLoadState.Loading(progress, load = 1)), "for $progress")
        }
        // The new load moves forward only, too.
        val second = first.onState(RaftPageLoadState.Loading(0.2f, load = 1))
        assertEquals(0.2f, second.onState(RaftPageLoadState.Loading(0.1f, load = 1)).fraction)
        // Inside the appear delay a new load restarts the wait (its count changes), still unseen.
        val waiting = RaftPageLoadingBarLine().onState(loading(0.5f))
        val restarted = waiting.onState(RaftPageLoadState.Loading(null, load = 1))
        assertEquals(RaftPageLoadingBarPhase.Waiting, restarted.phase)
        assertEquals(waiting.load + 1, restarted.load)
        assertEquals(0.08f, restarted.fraction)
    }

    @Test
    fun progressGoingAwayIsNotReadAsANewLoad() {
        // Only the host's load name starts the line again; "no progress" in the same load holds it.
        val line = shown(0.6f)
        val held = line.onState(loading(null))
        assertEquals(0.6f, held.fraction)
        assertEquals(line.load, held.load)
        assertTrue(held.reported)
    }

    @Test
    fun aFinishedLoadRunsToTheEndThenLeaves() {
        val leaving = shown(0.4f).onState(RaftPageLoadState.Finished)
        assertEquals(RaftPageLoadingBarPhase.Leaving, leaving.phase)
        assertEquals(1f, leaving.fraction)
        assertTrue(leaving.visible)
        // Finished reported again keeps the exit going; it ends only when the fade has.
        assertEquals(leaving, leaving.onState(RaftPageLoadState.Finished))
        val left = leaving.onLeft()
        assertEquals(RaftPageLoadingBarPhase.Hidden, left.phase)
        assertEquals(left, left.onState(RaftPageLoadState.Finished))
        // onLeft is only the end of an exit.
        assertEquals(RaftPageLoadingBarPhase.Shown, shown(0.4f).onLeft().phase)
    }

    @Test
    fun aFailedOrStoppedLoadHidesTheLineAtOnce() {
        for (end in listOf(RaftPageLoadState.Failed, RaftPageLoadState.Idle)) {
            assertEquals(RaftPageLoadingBarPhase.Hidden, shown(0.4f).onState(end).phase, "shown, $end")
            val leaving = shown(0.4f).onState(RaftPageLoadState.Finished)
            assertEquals(RaftPageLoadingBarPhase.Hidden, leaving.onState(end).phase, "leaving, $end")
        }
    }

    @Test
    fun reducedMotionDropsAFinishedLineAtOnce() {
        val ended = shown(0.4f).onState(RaftPageLoadState.Finished, exitMotion = false)
        assertEquals(RaftPageLoadingBarPhase.Hidden, ended.phase)
    }

    @Test
    fun aLoadAfterAnEndedOneStartsAgainWithItsOwnDelay() {
        val ends =
            listOf(
                shown(0.9f).onState(RaftPageLoadState.Finished),
                shown(0.9f).onState(RaftPageLoadState.Finished).onLeft(),
                shown(0.9f).onState(RaftPageLoadState.Failed),
            )
        for (ended in ends) {
            val next = ended.onState(loading(0.1f))
            assertEquals(RaftPageLoadingBarPhase.Waiting, next.phase, "after $ended")
            assertEquals(0.1f, next.fraction, "after $ended")
            assertEquals(ended.load + 1, next.load, "after $ended")
        }
    }
}
