package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
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
                fromTouch = true,
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
                fromTouch = true,
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
                fromTouch = true,
                selectionShowingAtDown = false,
                hasSingleTapAction = true,
                doubleTapAvailable = false,
                completesDoubleTap = false
            ),
            "a message that cannot be reacted to opens its thread immediately"
        )
    }

    @Test
    fun withBothGesturesTheSingleTapWaitsAndASecondTapShowsReactions() {
        assertEquals(
            ThreadMessageRowTapOutcome.DeferSingleTap,
            decideThreadMessageRowTap(
                tapAccepted = true,
                fromTouch = true,
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
                fromTouch = true,
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
                fromTouch = true,
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
                fromTouch = true,
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
                fromTouch = true,
                selectionShowingAtDown = false,
                hasSingleTapAction = false,
                doubleTapAvailable = false,
                completesDoubleTap = false
            ),
            "no tap action and no reactions: nothing"
        )
    }

    @Test
    fun onlyAFingerGetsTheRowTapGestures() {
        assertTrue(threadMessageRowTapGesturesFor(PointerType.Touch))
        assertFalse(threadMessageRowTapGesturesFor(PointerType.Mouse), "desktop, an iPad with a mouse or trackpad")
        assertFalse(threadMessageRowTapGesturesFor(PointerType.Stylus))
        assertFalse(threadMessageRowTapGesturesFor(PointerType.Eraser))
        assertFalse(threadMessageRowTapGesturesFor(PointerType.Unknown))
    }

    @Test
    fun aRightClickOpensTheMessageMenuAndAFingerNeverDoes() {
        // artin, 2026-10-29 (Mac): a right-click on a message opens its menu at the pointer (web
        // `contextmenu`), not the text's own Copy menu.
        assertTrue(threadMessageIsContextClick(PointerType.Mouse, secondaryButtonPressed = true, pressStarted = true))
        assertTrue(
            threadMessageIsContextClick(PointerType.Stylus, secondaryButtonPressed = true, pressStarted = true),
            "a stylus's barrel button"
        )
        assertFalse(
            threadMessageIsContextClick(PointerType.Touch, secondaryButtonPressed = true, pressStarted = true),
            "a finger keeps the long press"
        )
        assertFalse(
            threadMessageIsContextClick(PointerType.Mouse, secondaryButtonPressed = false, pressStarted = true),
            "a left click selects text (#3941)"
        )
        assertFalse(
            threadMessageIsContextClick(PointerType.Mouse, secondaryButtonPressed = true, pressStarted = false),
            "a move or release while the button is held is not a new right-click"
        )
    }

    @Test
    fun aMouseClickNeitherOpensTheThreadNorShowsReactions() {
        // stdrc + raft-design #878: a click selects text; it never opens the thread.
        for (doubleTapAvailable in listOf(false, true)) {
            for (completesDoubleTap in listOf(false, true)) {
                assertEquals(
                    ThreadMessageRowTapOutcome.None,
                    decideThreadMessageRowTap(
                        tapAccepted = true,
                        fromTouch = false,
                        selectionShowingAtDown = false,
                        hasSingleTapAction = true,
                        doubleTapAvailable = doubleTapAvailable,
                        completesDoubleTap = completesDoubleTap
                    ),
                    "doubleTapAvailable=$doubleTapAvailable completesDoubleTap=$completesDoubleTap"
                )
            }
        }
        assertEquals(
            ThreadMessageRowTapOutcome.DismissSelectionOnly,
            decideThreadMessageRowTap(
                tapAccepted = true,
                fromTouch = false,
                selectionShowingAtDown = true,
                hasSingleTapAction = true,
                doubleTapAvailable = false,
                completesDoubleTap = false
            ),
            "a click while a selection shows still only dismisses it"
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
