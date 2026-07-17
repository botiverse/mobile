package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadMessageLongPressSurfaceTest {
    @Test
    fun normalMessageUsesTheFullRowGestureSurface() {
        assertTrue(threadMessageUsesFullRowLongPressActions(shareSelectionActive = false))
    }

    @Test
    fun shareSelectionKeepsItsDedicatedSelectionGestures() {
        assertFalse(threadMessageUsesFullRowLongPressActions(shareSelectionActive = true))
    }

    @Test
    fun ordinaryMessageBodyTapOpensItsThread() {
        assertTrue(
            threadMessageTapOpensThread(
                showReplyAction = true,
                shareSelectionActive = false,
                hasLocalSendState = false
            )
        )
    }

    @Test
    fun bodyTapDoesNotReplaceReplyThreadSelectionOrPendingSendActions() {
        assertFalse(
            threadMessageTapOpensThread(
                showReplyAction = false,
                shareSelectionActive = false,
                hasLocalSendState = false
            )
        )
        assertFalse(
            threadMessageTapOpensThread(
                showReplyAction = true,
                shareSelectionActive = true,
                hasLocalSendState = false
            )
        )
        assertFalse(
            threadMessageTapOpensThread(
                showReplyAction = true,
                shareSelectionActive = false,
                hasLocalSendState = true
            )
        )
    }
}
