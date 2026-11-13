package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftToastHost
import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.components.raftToastAvoid
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftScale
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
 * tab bar, down to the window's edge on a page still loading. While it shows it only moves up (Kirby):
 * never down, and never left covering something taller that appears.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftToastHoldsPositionUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun ComposeUiTest.bottomOf(text: String): Dp = onNodeWithText(text).getBoundsInRoot().bottom

    /**
     * With the clock held, a state write from outside composition (or from layout: an avoidance
     * measured in that frame) needs its apply notification and a further frame to recompose.
     */
    private fun ComposeUiTest.settle() {
        repeat(3) {
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeBy(250)
            waitForIdle()
        }
    }

    private fun ComposeUiTest.host(
        state: RaftToastHostState,
        keyboard: () -> Dp = { 0.dp },
        scale: RaftScale = RaftScale.Compact,
        barHeight: () -> Dp,
    ) {
        setContent {
            RaftTheme(appearance = RaftAppearance.Light, scale = scale) {
                CompositionLocalProvider(LocalRaftToastHostState provides state) {
                    Box(Modifier.fillMaxSize()) {
                        val height = barHeight()
                        if (height > 0.dp) {
                            val density = LocalDensity.current
                            Box(
                                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height)
                                    .raftToastAvoid(keyboardPx = { with(density) { keyboard().toPx() } })
                            )
                        }
                        RaftToastHost(state)
                    }
                }
            }
        }
        settle()
    }

    @Test
    fun aToastDoesNotDropWhenThePageChangesUnderIt() = runRobolectricComposeUiTest {
        val state = RaftToastHostState()
        var bar by mutableStateOf(120.dp)
        mainClock.autoAdvance = false
        host(state) { bar }
        state.show("First")
        settle()
        val appeared = bottomOf("First")

        bar = 0.dp
        settle()
        assertEquals(appeared, bottomOf("First"), "the page changed; the toast stays")

        state.dismissAll()
        settle()
        state.show("Second")
        settle()
        assertTrue(bottomOf("Second") > appeared, "a new toast takes the page it appears on")
    }

    @Test
    fun aToastMovesUpForSomethingTallerAndStaysThere() = runRobolectricComposeUiTest {
        val state = RaftToastHostState()
        var bar by mutableStateOf(0.dp)
        mainClock.autoAdvance = false
        host(state) { bar }
        state.show("First")
        settle()
        val appeared = bottomOf("First")

        bar = 120.dp
        settle()
        val above = bottomOf("First")
        assertTrue(above < appeared, "the composer that appeared is not covered")

        bar = 0.dp
        settle()
        assertEquals(above, bottomOf("First"), "and it does not drop again")
    }

    /**
     * raft-design #1217: a wide window (a tablet) keeps the toast above the composer as a phone does,
     * not at the bottom of the window where it covered the composer while typing.
     */
    @Test
    fun aWideWindowsToastSitsAboveTheComposerToo() = runRobolectricComposeUiTest {
        val state = RaftToastHostState()
        mainClock.autoAdvance = false
        host(state, scale = RaftScale.Wide) { 120.dp }
        state.show("Wide")
        settle()
        val composerTop = onRoot().getBoundsInRoot().bottom - 120.dp
        // The text sits inside the toast's padding: its bottom is at least 16dp above the composer.
        assertTrue(bottomOf("Wide") <= composerTop - 16.dp, "above the composer, not over it at the window's bottom")
    }

    /** A toast shown while typing: above the composer on the keyboard, then back down with it. */
    @Test
    fun aToastComesBackDownWhenTheKeyboardCloses() = runRobolectricComposeUiTest {
        val state = RaftToastHostState()
        var keyboard by mutableStateOf(200.dp)
        mainClock.autoAdvance = false
        host(state, keyboard = { keyboard }) { 120.dp + keyboard }
        state.show("Typing")
        settle()
        val onKeyboard = bottomOf("Typing")

        keyboard = 0.dp
        settle()
        val closed = bottomOf("Typing")
        assertEquals(onKeyboard + 200.dp, closed, "down by the keyboard's height, still above the composer")
    }
}
