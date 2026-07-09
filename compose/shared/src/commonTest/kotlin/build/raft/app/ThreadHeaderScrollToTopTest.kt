package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadHeaderScrollToTopTest {

    @Test
    fun headerScrollToTopOnlyEnabledForReplyThreadChatTab() {
        assertTrue(threadHeaderScrollToTopEnabled(KuiklyThreadSurfaceKind.ReplyThread, "chat"))
        assertTrue(threadHeaderScrollToTopEnabled(KuiklyThreadSurfaceKind.ReplyThread, " Chat "))
        assertFalse(threadHeaderScrollToTopEnabled(" Thread ", "chat"))
        assertFalse(threadHeaderScrollToTopEnabled(KuiklyThreadSurfaceKind.Channel, "chat"))
        assertFalse(threadHeaderScrollToTopEnabled(KuiklyThreadSurfaceKind.DirectMessage, "chat"))
        assertFalse(threadHeaderScrollToTopEnabled(KuiklyThreadSurfaceKind.ReplyThread, "tasks"))
        assertFalse(threadHeaderScrollToTopEnabled(KuiklyThreadSurfaceKind.ReplyThread, "files"))
        assertFalse(threadHeaderScrollToTopEnabled(KuiklyThreadSurfaceKind.ReplyThread, null))
        assertFalse(threadHeaderScrollToTopEnabled(null, "chat"))
    }

    @Test
    fun headerScrollToTopTargetsFirstListItemWhenThreadListExists() {
        assertNull(threadHeaderScrollToTopTargetIndex(0))
        assertNull(threadHeaderScrollToTopTargetIndex(-1))
        assertEquals(0, threadHeaderScrollToTopTargetIndex(1))
        assertEquals(0, threadHeaderScrollToTopTargetIndex(42))
    }
}
