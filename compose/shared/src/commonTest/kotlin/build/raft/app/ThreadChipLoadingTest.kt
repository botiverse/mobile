package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadChipLoadingTest {
    @Test
    fun pendingReactionKeyBindsMessageAndEmojiIdentity() {
        assertEquals("message-1:👍", threadReactionPendingKey("message-1", "👍"))
        assertEquals("message-2:👍", threadReactionPendingKey("message-2", "👍"))
        assertEquals("message-1:❤️", threadReactionPendingKey("message-1", "❤️"))
    }

    @Test
    fun taskChipLoadsOnlyForTheExactPendingTask() {
        assertTrue(threadTaskChipLoading(taskId = "task-1", pendingTaskId = "task-1"))
        assertFalse(threadTaskChipLoading(taskId = "task-2", pendingTaskId = "task-1"))
        assertFalse(threadTaskChipLoading(taskId = null, pendingTaskId = null))
        assertFalse(threadTaskChipLoading(taskId = "task-1", pendingTaskId = null))
    }
}
