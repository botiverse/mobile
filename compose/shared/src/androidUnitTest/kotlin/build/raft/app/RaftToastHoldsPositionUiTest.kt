package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.compat.ui.RaftProvidePageActive
import build.raft.designsystem.components.LocalRaftToastAvoidanceActive
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftToastBottomInsetProvider
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

    @Test
    fun aToastOnFirstFrameAlreadyClearsTheComposerWithoutDroppingToWindowEdge() = runRobolectricComposeUiTest {
        val state = RaftToastHostState().apply { show("Immediate") }
        mainClock.autoAdvance = false
        setContent {
            RaftTheme(appearance = RaftAppearance.Light, scale = RaftScale.Compact) {
                CompositionLocalProvider(LocalRaftToastHostState provides state) {
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(120.dp)
                                .raftToastAvoid()
                        )
                        RaftToastHost(state)
                    }
                }
            }
        }
        // Single frame (no multi-frame settle): onPlaced + placement-phase layout already clears the bar.
        val composerTop = onRoot().getBoundsInRoot().bottom - 120.dp
        assertTrue(bottomOf("Immediate") <= composerTop - 16.dp, "frame 1 already sits above the composer")
    }

    @Test
    fun topStackPageInMotionStillReportsToastAvoidanceAndDialogSubtreeDoesNot() = runRobolectricComposeUiTest {
        val state = RaftToastHostState()
        mainClock.autoAdvance = false
        setContent {
            RaftTheme(appearance = RaftAppearance.Light, scale = RaftScale.Compact) {
                CompositionLocalProvider(LocalRaftToastHostState provides state) {
                    Box(Modifier.fillMaxSize()) {
                        // Simulates Nav3 top page mid-slide: active=false (back handlers wait), toastAvoidActive=true.
                        RaftProvidePageActive(active = false, toastAvoidActive = true) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(100.dp)
                                    .raftToastAvoid()
                            )
                        }
                        // Simulates separate dialog window whose local bounds must not override host avoidance.
                        CompositionLocalProvider(LocalRaftToastAvoidanceActive provides false) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(260.dp)
                                    .raftToastAvoid()
                            )
                        }
                        RaftToastHost(state)
                    }
                }
            }
        }
        state.show("Navigating")
        settle()
        val rootBottom = onRoot().getBoundsInRoot().bottom
        val toastBottom = bottomOf("Navigating")
        assertTrue(toastBottom <= rootBottom - 116.dp, "clears the 100dp bar of the sliding-in top page")
        assertTrue(toastBottom > rootBottom - 260.dp, "ignores the 260dp bar inside an isolated dialog window")
    }

    @Test
    fun bottomInsetProviderFollowsHostImeInsteadOfStaleWindowInsets() = runRobolectricComposeUiTest {
        val state = RaftToastHostState()
        var hostBottomInsetDp by mutableStateOf(0.dp)
        mainClock.autoAdvance = false
        setContent {
            RaftTheme(appearance = RaftAppearance.Light, scale = RaftScale.Compact) {
                CompositionLocalProvider(LocalRaftToastHostState provides state) {
                    Box(Modifier.fillMaxSize()) {
                        RaftToastBottomInsetProvider { hostBottomInsetDp.toPx() }
                        RaftToastHost(state)
                    }
                }
            }
        }
        state.show("Inset")
        settle()
        val resting = bottomOf("Inset")

        hostBottomInsetDp = 240.dp
        settle()
        assertEquals(resting - 240.dp, bottomOf("Inset"), "lifts by host-reported IME inset")

        hostBottomInsetDp = 0.dp
        settle()
        assertEquals(resting, bottomOf("Inset"), "returns to resting height once host IME closes")
    }

    @Test
    fun zeroSizedAndHorizontallyClippedAvoidanceNodesDoNotShootToastToTop() = runRobolectricComposeUiTest {
        val state = RaftToastHostState()
        mainClock.autoAdvance = false
        setContent {
            RaftTheme(appearance = RaftAppearance.Light, scale = RaftScale.Compact) {
                CompositionLocalProvider(LocalRaftToastHostState provides state) {
                    Box(Modifier.fillMaxSize().clipToBounds()) {
                        // Zero-sized avoided node (e.g., collapsed footer or hidden 0x0 subtree): ignored.
                        Box(Modifier.size(0.dp).raftToastAvoid())
                        // Horizontally translated completely offscreen inside a clipped parent (Frame 1 of push slide):
                        // positionInRoot().y stays at bottom - 100dp instead of boundsInRoot()'s clipped Rect.Zero (top = 0).
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { translationX = 2_000f }
                        ) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(100.dp)
                                    .raftToastAvoid()
                            )
                        }
                        RaftToastHost(state)
                    }
                }
            }
        }
        state.show("Sliding")
        settle()
        val rootBottom = onRoot().getBoundsInRoot().bottom
        val toastTop = onNodeWithText("Sliding").getBoundsInRoot().top
        val toastBottom = bottomOf("Sliding")
        assertTrue(toastTop > 100.dp, "toast must stay near bottom instead of shooting to y=0 ($toastTop)")
        assertTrue(toastBottom <= rootBottom - 116.dp, "toast still clears the 100dp bar on the offscreen sliding page")
    }
}
