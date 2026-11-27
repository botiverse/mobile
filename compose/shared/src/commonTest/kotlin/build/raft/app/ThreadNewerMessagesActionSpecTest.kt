package build.raft.app

import build.raft.app.resources.*
import build.raft.app.thread.threadNewerMessagesActionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadNewerMessagesActionSpecTest {

    @Test
    fun newerErrorState_hasNoLeadingIcon() {
        val spec = threadNewerMessagesActionSpec("error")

        assertEquals(Res.string.thread_retry_new.desc(), spec.label)
        assertEquals("retryLoadNewer", spec.event)
        assertNull(spec.leadingIcon)
    }
}
