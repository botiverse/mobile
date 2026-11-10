package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftFilterChip
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tap areas that reach past a control on some sides only (raft-ui `::after` insets): a segment's
 * 6dp up and down (raft-ui 0.70 SegmentedControlItem), the filter chip ×'s 10dp up and down,
 * 6dp toward its label and 14dp toward the chip's end (raft-ui 0.73 FilterChipRemove). By touch
 * only: a mouse takes no taps past the box.
 */
@OptIn(ExperimentalTestApi::class)
class RaftTouchReachHitTest {
    /** Taps at [at] (from the control's box, in dp) and counts the presses on the control. */
    private fun tap(pointer: RaftPointerInput, mouse: Boolean, content: @Composable (onClick: () -> Unit) -> Unit, at: Density.(Rect) -> Offset): Int {
        var clicks = 0
        runComposeUiTest {
            var box = Rect.Zero
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false, pointerInput = pointer) {
                    Box(Modifier.padding(40.dp)) {
                        Box(Modifier.onGloballyPositioned { box = it.boundsInRoot() }) { content { clicks++ } }
                    }
                }
            }
            waitForIdle()
            val point = density.at(box)
            if (mouse) onRoot().performMouseInput { click(point) } else onRoot().performTouchInput { click(point) }
            waitForIdle()
        }
        return clicks
    }

    private val segments: @Composable (() -> Unit) -> Unit = { onClick ->
        RaftSegmentedControl {
            RaftSegment("All", selected = false, onClick = onClick)
            RaftSegment("Unread", selected = true, onClick = {})
        }
    }

    /** [above] dp above the first segment (3dp inside the track's top), 10dp into it. */
    private fun aboveSegment(above: Float): Density.(Rect) -> Offset = { track ->
        Offset(track.left + (3 + 10).dp.toPx(), track.top + (3 - above).dp.toPx())
    }

    @Test
    fun aFingerPicksASegmentUpTo6dpAboveIt() {
        assertTrue(tap(RaftPointerInput.Touch, mouse = false, segments, aboveSegment(5f)) > 0, "5dp above")
        assertEquals(0, tap(RaftPointerInput.Touch, mouse = false, segments, aboveSegment(8f)), "8dp above")
    }

    @Test
    fun aMouseTakesNoTapsAboveASegment() {
        assertEquals(0, tap(RaftPointerInput.Fine, mouse = true, segments, aboveSegment(2f)))
    }

    private val chip: @Composable (() -> Unit) -> Unit = { onClick -> RaftFilterChip("Mentions", onClick, removeLabel = "Clear filter") }

    /** [dx] dp from the ×'s start edge (24dp, 4dp in from the chip's end), [dy] dp from its centre line. */
    private fun fromRemoveStart(dx: Float, dy: Float = 0f): Density.(Rect) -> Offset = { chip ->
        Offset(chip.right - (4 + 24 - dx).dp.toPx(), chip.center.y + dy.dp.toPx())
    }

    @Test
    fun theChipRemoveReachesTheGapButNotTheLabel() {
        assertEquals(1, tap(RaftPointerInput.Touch, mouse = false, chip, fromRemoveStart(-5f)), "in the 6dp gap")
        assertEquals(0, tap(RaftPointerInput.Touch, mouse = false, chip, fromRemoveStart(-8f)), "on the label's end")
    }

    @Test
    fun theChipRemoveReachesPastTheChipsEndAndUpAndDown() {
        // The × ends at 24; 14dp past it is 10dp past the chip.
        assertEquals(1, tap(RaftPointerInput.Touch, mouse = false, chip, fromRemoveStart(24f + 12f)), "12dp past the ×")
        assertEquals(0, tap(RaftPointerInput.Touch, mouse = false, chip, fromRemoveStart(24f + 16f)), "16dp past the ×")
        assertEquals(1, tap(RaftPointerInput.Touch, mouse = false, chip, fromRemoveStart(12f, dy = 21f)), "21dp below its centre")
        assertEquals(0, tap(RaftPointerInput.Touch, mouse = false, chip, fromRemoveStart(12f, dy = 24f)), "24dp below its centre")
    }

    @Test
    fun aMouseTakesNoTapsBesideTheChipRemove() {
        assertEquals(0, tap(RaftPointerInput.Fine, mouse = true, chip, fromRemoveStart(-3f)))
    }
}
