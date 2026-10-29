package build.raft.app

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Single tap opens a message's thread, double tap shows its quick reaction bar (stdrc, 2026-10-29,
 * #proj-raft-ui:911ccc08).
 */
class ThreadMessageDoubleTapTrackerTest {
    private val slop = 30f // 10dp at 3x

    @Test
    fun theDoubleTapWindowIs250Millis() {
        assertEquals(250L, ThreadMessageDoubleTapTimeoutMillis)
    }

    @Test
    fun twoTapsCloseInTimeAndPlaceAreADoubleTap() {
        val tracker = ThreadMessageDoubleTapTracker()
        assertFalse(tracker.onTap(1_000, Offset(100f, 100f), slop), "the first tap is a first tap")
        assertTrue(tracker.onTap(1_250, Offset(110f, 105f), slop), "the second within 250ms and the slop pairs")
    }

    @Test
    fun tooSlowOrTooFarIsTwoSingleTaps() {
        val tracker = ThreadMessageDoubleTapTracker()
        tracker.onTap(1_000, Offset(100f, 100f), slop)
        assertFalse(tracker.onTap(1_251, Offset(100f, 100f), slop), "past 250ms")
        val wasTheOld300msWindow = ThreadMessageDoubleTapTracker()
        wasTheOld300msWindow.onTap(1_000, Offset(100f, 100f), slop)
        assertFalse(wasTheOld300msWindow.onTap(1_300, Offset(100f, 100f), slop), "300ms no longer pairs")
        val far = ThreadMessageDoubleTapTracker()
        far.onTap(1_000, Offset(100f, 100f), slop)
        assertFalse(far.onTap(1_100, Offset(100f, 140f), slop), "past the slop")
    }

    @Test
    fun aSlowSecondTapStartsANewPair() {
        val tracker = ThreadMessageDoubleTapTracker()
        tracker.onTap(1_000, Offset.Zero, slop)
        assertFalse(tracker.onTap(1_400, Offset.Zero, slop))
        assertTrue(tracker.onTap(1_600, Offset.Zero, slop), "the slow tap became the new first tap")
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

    @Test
    fun aRefusedTapDoesNothing() {
        assertEquals(
            ThreadMessageRowTapOutcome.None,
            decideThreadMessageRowTap(
                tapAccepted = false,
                selectionShowingAtDown = true,
                hasSingleTapAction = true,
                doubleTapAvailable = true,
                completesDoubleTap = true
            ),
            "a scroll, a child's own tap (link, image, code block) or a stale gesture"
        )
    }

    @Test
    fun aTapWhileASelectionShowsOnlyDismissesIt() {
        assertEquals(
            ThreadMessageRowTapOutcome.DismissSelectionOnly,
            decideThreadMessageRowTap(
                tapAccepted = true,
                selectionShowingAtDown = true,
                hasSingleTapAction = true,
                doubleTapAvailable = true,
                completesDoubleTap = false
            )
        )
    }

    @Test
    fun withoutADoubleTapTheSingleTapRunsAtOnce() {
        assertEquals(
            ThreadMessageRowTapOutcome.RunSingleTap,
            decideThreadMessageRowTap(
                tapAccepted = true,
                selectionShowingAtDown = false,
                hasSingleTapAction = true,
                doubleTapAvailable = false,
                completesDoubleTap = false
            ),
            "a message that cannot be reacted to (or a mouse click) opens its thread immediately"
        )
    }

    @Test
    fun withBothGesturesTheSingleTapWaitsAndASecondTapShowsReactions() {
        assertEquals(
            ThreadMessageRowTapOutcome.DeferSingleTap,
            decideThreadMessageRowTap(
                tapAccepted = true,
                selectionShowingAtDown = false,
                hasSingleTapAction = true,
                doubleTapAvailable = true,
                completesDoubleTap = false
            )
        )
        assertEquals(
            ThreadMessageRowTapOutcome.ShowReactionBar,
            decideThreadMessageRowTap(
                tapAccepted = true,
                selectionShowingAtDown = false,
                hasSingleTapAction = true,
                doubleTapAvailable = true,
                completesDoubleTap = true
            )
        )
    }

    @Test
    fun insideAReplyThreadOnlyTheDoubleTapActs() {
        assertEquals(
            ThreadMessageRowTapOutcome.None,
            decideThreadMessageRowTap(
                tapAccepted = true,
                selectionShowingAtDown = false,
                hasSingleTapAction = false,
                doubleTapAvailable = true,
                completesDoubleTap = false
            ),
            "the first tap only waits for a second"
        )
        assertEquals(
            ThreadMessageRowTapOutcome.ShowReactionBar,
            decideThreadMessageRowTap(
                tapAccepted = true,
                selectionShowingAtDown = false,
                hasSingleTapAction = false,
                doubleTapAvailable = true,
                completesDoubleTap = true
            )
        )
        assertEquals(
            ThreadMessageRowTapOutcome.None,
            decideThreadMessageRowTap(
                tapAccepted = true,
                selectionShowingAtDown = false,
                hasSingleTapAction = false,
                doubleTapAvailable = false,
                completesDoubleTap = false
            ),
            "no tap action and no reactions: nothing"
        )
    }

    @Test
    fun aDownAnywhereCancelsTheHeldSingleTap() {
        val slot = ThreadMessagePendingTapSlot()
        assertFalse(slot.cancel(), "nothing held")
        val held = Job()
        slot.hold(held)
        assertTrue(slot.cancel(), "a down on any row cancels the held tap")
        assertTrue(held.isCancelled)
        assertFalse(slot.cancel(), "only once")
    }
}
