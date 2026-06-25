package ai.slock.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadNewerMessagesActionSpecTest {

    @Test
    fun defaultState_hasNoLeadingIcon() {
        val spec = threadNewerMessagesActionSpec("idle")

        assertEquals("Load new messages", spec.label)
        assertEquals("loadNewer", spec.event)
        assertNull(spec.leadingIcon)
    }

    @Test
    fun loadingState_hasNoLeadingIcon() {
        val spec = threadNewerMessagesActionSpec("loading")

        assertEquals("Loading new messages…", spec.label)
        assertEquals("loadNewer", spec.event)
        assertNull(spec.leadingIcon)
    }

    @Test
    fun errorState_hasNoLeadingIcon() {
        val spec = threadNewerMessagesActionSpec("error")

        assertEquals("Retry new messages", spec.label)
        assertEquals("retryLoadNewer", spec.event)
        assertNull(spec.leadingIcon)
    }
}
