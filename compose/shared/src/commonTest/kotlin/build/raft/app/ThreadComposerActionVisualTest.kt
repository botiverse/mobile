package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadComposerActionVisualTest {
    @Test
    fun sendButtonDisabledBackgroundIsNeutralWhenDraftCannotSend() {
        assertEquals(
            SlockColor.EmptyStateGray,
            threadComposerSendDisabledBackgroundColor(sending = false)
        )
    }

    @Test
    fun sendButtonDisabledBackgroundCanPreserveActiveColorWhileSending() {
        assertEquals(
            SlockColor.AccentPink,
            threadComposerSendDisabledBackgroundColor(sending = true)
        )
    }
}
