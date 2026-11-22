package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** Back / Forward through the wide history marks the card of the task the column reopens ([RaftTasksColumnTaskIds]). */
class RaftTasksColumnTaskIdsTest {
    @Test
    fun aRestoredDetailMarksTheTaskItWasOpenedFor() {
        val ids = RaftTasksColumnTaskIds().opened("detail-a", "task-a").opened("detail-b", "task-b")
        // Showing B, Back reopens A's detail: A's card, not B's.
        assertEquals("task-a", ids.restored("detail-a", current = "task-b"))
        assertEquals("task-b", ids.restored("detail-b", current = "task-a"))
    }
}
