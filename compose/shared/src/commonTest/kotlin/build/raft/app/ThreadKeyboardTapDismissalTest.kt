package build.raft.app

import build.raft.app.thread.ThreadKeyboardTapDismissal
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
