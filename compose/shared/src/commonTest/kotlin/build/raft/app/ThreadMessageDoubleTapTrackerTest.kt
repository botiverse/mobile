package build.raft.app

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Double tap on a message row opens its thread (artin; rules agreed with web, #proj-raft-ui:911ccc08). */
class ThreadMessageDoubleTapTrackerTest {
    private val slop = 30f // 10dp at 3x

    @Test
    fun twoTapsCloseInTimeAndPlaceAreADoubleTap() {
        val tracker = ThreadMessageDoubleTapTracker()
        assertFalse(tracker.onTap(1_000, Offset(100f, 100f), slop), "the first tap runs as a tap")
        assertTrue(tracker.onTap(1_250, Offset(110f, 105f), slop), "the second within 300ms and the slop opens the thread")
    }

    @Test
    fun tooSlowOrTooFarIsTwoSingleTaps() {
        val tracker = ThreadMessageDoubleTapTracker()
        tracker.onTap(1_000, Offset(100f, 100f), slop)
        assertFalse(tracker.onTap(1_301, Offset(100f, 100f), slop), "past 300ms")
        val far = ThreadMessageDoubleTapTracker()
        far.onTap(1_000, Offset(100f, 100f), slop)
        assertFalse(far.onTap(1_100, Offset(100f, 140f), slop), "past the slop")
    }

    @Test
    fun aThirdTapStartsOverAndAResetBreaksThePair() {
        val tracker = ThreadMessageDoubleTapTracker()
        tracker.onTap(1_000, Offset.Zero, slop)
        assertTrue(tracker.onTap(1_100, Offset.Zero, slop))
        assertFalse(tracker.onTap(1_200, Offset.Zero, slop), "a third tap is a new first tap")
        tracker.reset()
        assertFalse(tracker.onTap(1_300, Offset.Zero, slop), "a tap a child took breaks the pair")
    }
}
