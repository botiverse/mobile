package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shared outside-dismiss barrier of every menu and popover ([RaftFloatingOverlayHost], and
 * through it [RaftModalAnchoredPopover] / RaftBaseMenuOverlay) on a real composition: a finger tap
 * outside closes it even though a real finger wobbles a few pixels between down and up; a drag
 * outside (a scroll attempt) does not; a tap on the popover itself does not.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftFloatingOverlayOutsideTapUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun ComposeUiTest.showHost(onDismiss: () -> Unit) {
        setContent {
            Box(Modifier.fillMaxSize()) {
                RaftFloatingOverlayHost(onDismiss = onDismiss, modifier = Modifier.testTag(HostTag)) {
                    Box(Modifier.size(80.dp).testTag(ContentTag).raftFloatingContentClickTarget())
                }
            }
        }
        // Past the long-press activation delay.
        mainClock.advanceTimeBy(1_000)
    }

    private fun ComposeUiTest.showModalPopover(onDismiss: () -> Unit) {
        setContent {
            Box(Modifier.fillMaxSize()) {
                RaftModalAnchoredPopover(
                    anchor = RaftPopoverAnchor.forRootOffset(Offset(40f, 40f)),
                    contentSize = Size(200f, 200f),
                    onDismiss = onDismiss,
                    modifier = Modifier.testTag(HostTag),
                ) {
                    Box(Modifier.size(80.dp).raftFloatingContentClickTarget())
                }
            }
        }
        mainClock.advanceTimeBy(1_000)
    }

    private fun ComposeUiTest.touchOutside(gesture: TouchInjectionScope.(Offset) -> Unit) {
        onNodeWithTag(HostTag).performTouchInput { gesture(Offset(width - 40f, height - 40f)) }
        mainClock.advanceTimeBy(1_000)
    }

    /** A real finger tap: down, a few pixels of wobble (well inside the touch slop), up. */
    private fun TouchInjectionScope.fingerTap(at: Offset) {
        down(at)
        moveBy(Offset(2f, 1f))
        moveBy(Offset(1f, -2f))
        up()
    }

    @Test
    fun aFingerTapOutsideThatWobblesWithinTheTouchSlopDismisses() =
        runRobolectricComposeUiTest {
            var dismissed = 0
            showHost { dismissed++ }
            touchOutside { fingerTap(it) }
            assertEquals(1, dismissed)
        }

    @Test
    fun aDragOutsideDoesNotDismiss() =
        runRobolectricComposeUiTest {
            var dismissed = 0
            showHost { dismissed++ }
            touchOutside {
                down(it)
                moveBy(Offset(0f, -200f))
                up()
            }
            assertEquals(0, dismissed, "a scroll attempt outside must not close the menu")
        }

    @Test
    fun aTapOnThePopoverDoesNotDismiss() =
        runRobolectricComposeUiTest {
            var dismissed = 0
            showHost { dismissed++ }
            onNodeWithTag(ContentTag).performTouchInput { fingerTap(center) }
            mainClock.advanceTimeBy(1_000)
            assertEquals(0, dismissed)
        }

    @Test
    fun aFingerTapOutsideAModalMenuDismisses() =
        runRobolectricComposeUiTest {
            // The Home long-press menu path: Modal window + anchored popover.
            var dismissed = 0
            showModalPopover { dismissed++ }
            touchOutside { fingerTap(it) }
            assertEquals(1, dismissed)
        }

    private companion object {
        const val HostTag = "overlay-host"
        const val ContentTag = "overlay-content"
    }
}
