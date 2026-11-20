package build.raft.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import build.raft.designsystem.components.RaftPageLoadState
import build.raft.designsystem.components.RaftPageLoadingBar
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * The page load line's timing on a real composition: the 200ms before it first shows, the run
 * to the end and the fade when the load finishes, at once when it fails. On the test's main
 * clock (virtual time), never the wall clock. The line is in the tree only while it is drawn.
 */
@OptIn(ExperimentalTestApi::class)
class RaftPageLoadingBarUiTest {
    private var state by mutableStateOf<RaftPageLoadState>(RaftPageLoadState.Loading())

    private fun ComposeUiTest.setLine(reducedMotion: Boolean = false) {
        mainClock.autoAdvance = false
        setContent {
            RaftTheme {
                RaftPageLoadingBar(
                    state = state,
                    modifier = Modifier.testTag("line"),
                    contentDescription = "Loading",
                    reducedMotion = reducedMotion,
                )
            }
        }
        mainClock.advanceTimeByFrame()
    }

    private fun ComposeUiTest.report(next: RaftPageLoadState) {
        state = next
        mainClock.advanceTimeByFrame()
    }

    @Test
    fun theLineShowsOnlyAfterTheAppearDelay() =
        runComposeUiTest {
            setLine()
            onNodeWithTag("line").assertDoesNotExist()
            mainClock.advanceTimeBy(100)
            onNodeWithTag("line").assertDoesNotExist()
            mainClock.advanceTimeBy(200)
            onNodeWithTag("line")
                .assertExists()
                .assertContentDescriptionEquals("Loading")
                .assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
            report(RaftPageLoadState.Loading(0.4f))
            onNodeWithTag("line").assertRangeInfoEquals(ProgressBarRangeInfo(0.4f, 0f..1f))
        }

    @Test
    fun aLoadThatFinishesInsideTheDelayNeverShowsALine() =
        runComposeUiTest {
            setLine()
            mainClock.advanceTimeBy(100)
            report(RaftPageLoadState.Finished)
            onNodeWithTag("line").assertDoesNotExist()
            // Nothing shows later either: neither the delayed line nor an exit.
            mainClock.advanceTimeBy(300)
            onNodeWithTag("line").assertDoesNotExist()
        }

    @Test
    fun aFinishedLoadRunsToTheEndAndFadesBeforeTheLineGoes() =
        runComposeUiTest {
            state = RaftPageLoadState.Loading(0.5f)
            setLine()
            mainClock.advanceTimeBy(300)
            onNodeWithTag("line").assertExists()
            report(RaftPageLoadState.Finished)
            onNodeWithTag("line").assertExists().assertRangeInfoEquals(ProgressBarRangeInfo(1f, 0f..1f))
            // 200ms to the end, then 200ms of fade: still there part way.
            mainClock.advanceTimeBy(250)
            onNodeWithTag("line").assertExists()
            mainClock.advanceTimeBy(400)
            onNodeWithTag("line").assertDoesNotExist()
        }

    @Test
    fun aFailedLoadDropsTheLineAtOnce() =
        runComposeUiTest {
            state = RaftPageLoadState.Loading(0.5f)
            setLine()
            mainClock.advanceTimeBy(300)
            onNodeWithTag("line").assertExists()
            report(RaftPageLoadState.Failed)
            onNodeWithTag("line").assertDoesNotExist()
        }

    @Test
    fun reducedMotionKeepsTheDelayAndDropsAFinishedLineAtOnce() =
        runComposeUiTest {
            state = RaftPageLoadState.Loading(0.5f)
            setLine(reducedMotion = true)
            onNodeWithTag("line").assertDoesNotExist()
            mainClock.advanceTimeBy(300)
            onNodeWithTag("line").assertExists()
            report(RaftPageLoadState.Finished)
            onNodeWithTag("line").assertDoesNotExist()
        }

    @Test
    fun aNewLoadAfterAFinishedOneWaitsAgain() =
        runComposeUiTest {
            setLine()
            mainClock.advanceTimeBy(300)
            report(RaftPageLoadState.Finished)
            mainClock.advanceTimeBy(700)
            onNodeWithTag("line").assertDoesNotExist()
            report(RaftPageLoadState.Loading(0.2f))
            onNodeWithTag("line").assertDoesNotExist()
            mainClock.advanceTimeBy(300)
            onNodeWithTag("line").assertExists()
        }
}
