package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftToastHost
import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.components.raftToastAvoid
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * artin 2026-11-07 (recording): a toast moved with each page under it, above the composer, above the
 * tab bar, down to the window's edge on a page still loading. It keeps the height it appeared at.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftToastHoldsPositionUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun ComposeUiTest.bottomOf(text: String): Dp = onNodeWithText(text).getBoundsInRoot().bottom

    @Test
    fun aToastStaysWhereItAppearedWhenThePageChangesUnderIt() = runRobolectricComposeUiTest {
        val state = RaftToastHostState()
        var barShown by mutableStateOf(true)
        mainClock.autoAdvance = false
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                CompositionLocalProvider(LocalRaftToastHostState provides state) {
                    Box(Modifier.fillMaxSize()) {
                        if (barShown) {
                            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(120.dp).raftToastAvoid())
                        }
                        RaftToastHost(state)
                    }
                }
            }
        }
        mainClock.advanceTimeBy(500)
        state.show("First")
        mainClock.advanceTimeBy(500)
        val appeared = bottomOf("First")

        barShown = false
        mainClock.advanceTimeBy(500)
        assertEquals(appeared, bottomOf("First"), "the page changed; the toast stays")

        state.dismissAll()
        mainClock.advanceTimeBy(500)
        state.show("Second")
        mainClock.advanceTimeBy(500)
        assertTrue(bottomOf("Second") > appeared, "a new toast takes the page it appears on")
    }
}
