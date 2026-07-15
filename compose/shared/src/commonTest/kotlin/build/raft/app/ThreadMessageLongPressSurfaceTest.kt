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
}
