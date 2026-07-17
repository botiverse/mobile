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

    @Test
    fun headerScrollToTopRequestRunsOnceAcrossThreadPagination() {
        val fence = AppShellScrollToTopRequestFence(initialRequest = 1)

        assertFalse(consumeThreadHeaderScrollToTopRequest(request = 2, lazyItemCount = 0, fence = fence))
        assertTrue(consumeThreadHeaderScrollToTopRequest(request = 2, lazyItemCount = 1, fence = fence))
        assertFalse(consumeThreadHeaderScrollToTopRequest(request = 2, lazyItemCount = 51, fence = fence))
        assertFalse(consumeThreadHeaderScrollToTopRequest(request = 2, lazyItemCount = 101, fence = fence))
        assertTrue(consumeThreadHeaderScrollToTopRequest(request = 3, lazyItemCount = 101, fence = fence))
        assertFalse(consumeThreadHeaderScrollToTopRequest(request = 3, lazyItemCount = 151, fence = fence))
    }
}
