package build.raft.app

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZoomPanTouchSlopTrackerTest {
    @Test fun slowDragCrossesTheSlopOnceSmallMovesAddUp() {
        val tracker = ZoomPanTouchSlopTracker(touchSlop = 18f)
        repeat(9) { assertFalse(tracker.accept(1f, Offset(2f, 0f), viewportExtent = 1000f), "move $it") }
        assertTrue(tracker.accept(1f, Offset(2f, 0f), viewportExtent = 1000f), "20px total is past an 18px slop")
        assertTrue(tracker.accept(1f, Offset(0.5f, 0f), viewportExtent = 1000f), "stays past the slop")
    }

    @Test fun jitterBackAndForthDoesNotCross() {
        val tracker = ZoomPanTouchSlopTracker(18f)
        repeat(20) { assertFalse(tracker.accept(1f, Offset(if (it % 2 == 0) 5f else -5f, 0f), 1000f)) }
    }

    @Test fun onlyAZoomedImageKeepsBackSwipes() {
        // At 1x a horizontal drag belongs to the gallery / the host's back swipe; zoomed in it pans.
        assertFalse(raftZoomedImageKeepsBackSwipe(1f))
        assertTrue(raftZoomedImageKeepsBackSwipe(1.01f))
        assertTrue(raftZoomedImageKeepsBackSwipe(4f))
    }
}
