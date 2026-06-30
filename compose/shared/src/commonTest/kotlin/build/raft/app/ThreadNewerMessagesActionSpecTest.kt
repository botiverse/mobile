package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadNewerMessagesActionSpecTest {

    @Test
    fun newerDefaultState_hasNoLeadingIcon() {
        val spec = threadNewerMessagesActionSpec("idle")

        assertEquals("Load new messages", spec.label)
        assertEquals("loadNewer", spec.event)
        assertNull(spec.leadingIcon)
    }

    @Test
    fun newerLoadingState_hasNoLeadingIcon() {
        val spec = threadNewerMessagesActionSpec("loading")

        assertEquals("Loading new messages…", spec.label)
        assertEquals("loadNewer", spec.event)
        assertNull(spec.leadingIcon)
    }

    @Test
    fun newerErrorState_hasNoLeadingIcon() {
        val spec = threadNewerMessagesActionSpec("error")

        assertEquals("Retry new messages", spec.label)
        assertEquals("retryLoadNewer", spec.event)
        assertNull(spec.leadingIcon)
    }

    @Test
    fun olderLoadingState_hasNoLeadingIcon() {
        val spec = threadOlderMessagesStatusSpec(canLoadMoreBefore = true)

        assertEquals("Loading older messages…", spec.label)
        assertNull(spec.leadingIcon)
        assertNull(spec.secondaryLabel)
    }

    @Test
    fun olderBeginningState_hasNoLeadingIcon() {
        val spec = threadOlderMessagesStatusSpec(
            canLoadMoreBefore = false,
            secondaryLabel = "1 reply"
        )

        assertEquals("Beginning of conversation", spec.label)
        assertNull(spec.leadingIcon)
        assertEquals("1 reply", spec.secondaryLabel)
    }
}
