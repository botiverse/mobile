package build.raft.app

import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadNewerMessagesActionSpecTest {

    @Test
    fun newerDefaultState_isPassiveAndHasNoLeadingIcon() {
        val spec = threadNewerMessagesActionSpec("idle")

        assertEquals(Res.string.thread_load_new.desc(), spec.label)
        assertNull(spec.event)
        assertNull(spec.leadingIcon)
    }

    @Test
    fun newerLoadingState_isPassiveAndHasNoLeadingIcon() {
        val spec = threadNewerMessagesActionSpec("loading")

        assertEquals(Res.string.thread_state_loading_new.desc(), spec.label)
        assertNull(spec.event)
        assertNull(spec.leadingIcon)
    }

    @Test
    fun newerErrorState_hasNoLeadingIcon() {
        val spec = threadNewerMessagesActionSpec("error")

        assertEquals(Res.string.thread_retry_new.desc(), spec.label)
        assertEquals("retryLoadNewer", spec.event)
        assertNull(spec.leadingIcon)
    }

    @Test
    fun olderLoadingState_hasNoLeadingIcon() {
        val spec = threadOlderMessagesStatusSpec(canLoadMoreBefore = true)

        assertEquals(Res.string.thread_state_loading_older.desc(), spec.label)
        assertNull(spec.leadingIcon)
        assertNull(spec.secondaryLabel)
    }

    @Test
    fun olderBeginningState_hasNoLeadingIcon() {
        val spec = threadOlderMessagesStatusSpec(
            canLoadMoreBefore = false,
            secondaryLabel = "1 reply"
        )

        assertEquals(Res.string.thread_state_beginning_conversation.desc(), spec.label)
        assertNull(spec.leadingIcon)
        assertEquals("1 reply", spec.secondaryLabel)
    }
}
