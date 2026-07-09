package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadHeaderScrollToTopTest {

    @Test
    fun headerScrollToTopOnlyEnabledForChatTab() {
        assertTrue(threadHeaderScrollToTopEnabled("chat"))
        assertTrue(threadHeaderScrollToTopEnabled(" Chat "))
        assertFalse(threadHeaderScrollToTopEnabled("tasks"))
        assertFalse(threadHeaderScrollToTopEnabled("files"))
        assertFalse(threadHeaderScrollToTopEnabled(null))
    }

    @Test
    fun headerScrollToTopTargetsFirstListItemWhenThreadListExists() {
        assertNull(threadHeaderScrollToTopTargetIndex(0))
        assertNull(threadHeaderScrollToTopTargetIndex(-1))
        assertEquals(0, threadHeaderScrollToTopTargetIndex(1))
        assertEquals(0, threadHeaderScrollToTopTargetIndex(42))
    }
}
