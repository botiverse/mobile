package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** artin 11-06: a tap on a message row with the keyboard up closes it and does not open the thread. */
class ThreadKeyboardTapDismissalTest {
    @Test
    fun keyboardUpClosesItAndTakesTheTap() {
        var hides = 0
        val dismissal = ThreadKeyboardTapDismissal(keyboardInsetDp = { 300f }, hideKeyboard = { hides++ })
        assertTrue(dismissal.dismissIfUp())
        assertEquals(1, hides)
    }

    @Test
    fun noKeyboardLeavesTheTapToTheRow() {
        var hides = 0
        val dismissal = ThreadKeyboardTapDismissal(keyboardInsetDp = { 0f }, hideKeyboard = { hides++ })
        assertFalse(dismissal.dismissIfUp())
        assertEquals(0, hides)
    }

    @Test
    fun aTapTakenByTheKeyboardOpensNothing() {
        val outcome =
            decideThreadMessageRowTap(
                tapAccepted = true,
                fromTouch = true,
                selectionShowingAtDown = true,
                hasSingleTapAction = true,
                doubleTapAvailable = true,
                completesDoubleTap = false,
            )
        assertEquals(ThreadMessageRowTapOutcome.DismissSelectionOnly, outcome)
    }
}
