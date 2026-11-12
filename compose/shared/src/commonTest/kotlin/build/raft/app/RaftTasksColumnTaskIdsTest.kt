package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Back / Forward through the wide history marks the card of the task the column reopens ([RaftTasksColumnTaskIds]). */
class RaftTasksColumnTaskIdsTest {
    @Test
    fun aRestoredDetailMarksTheTaskItWasOpenedFor() {
        val ids = RaftTasksColumnTaskIds().opened("detail-a", "task-a").opened("detail-b", "task-b")
        // Showing B, Back reopens A's detail: A's card, not B's.
        assertEquals("task-a", ids.restored("detail-a", current = "task-b"))
        assertEquals("task-b", ids.restored("detail-b", current = "task-a"))
    }

    @Test
    fun aDetailReachedInsideTheColumnKeepsTheTaskMarked() {
        val ids = RaftTasksColumnTaskIds().opened("detail-a", "task-a")
        assertEquals("task-a", ids.restored("channel-from-a", current = "task-a"))
        assertNull(RaftTasksColumnTaskIds().restored("channel", current = null))
    }

    @Test
    fun reopeningADetailForAnotherTaskTakesItsPlaceAndOnlyTheMostRecentAreKept() {
        val reopened = RaftTasksColumnTaskIds().opened("detail-a", "task-a").opened("detail-a", "task-c")
        assertEquals("task-c", reopened.restored("detail-a", current = null))

        val capped = RaftTasksColumnTaskIds(cap = 2).opened("d1", "t1").opened("d2", "t2").opened("d3", "t3")
        assertNull(capped.restored("d1", current = null), "the oldest drops off past the cap")
        assertEquals("t2", capped.restored("d2", current = null))
        assertEquals("t3", capped.restored("d3", current = null))
    }
}
