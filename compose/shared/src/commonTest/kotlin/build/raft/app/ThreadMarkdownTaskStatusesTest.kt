package build.raft.app

import build.raft.app.data.SharedTasksDataStoreStatus
import build.raft.app.data.SharedTasksProjectionSlice
import build.raft.app.markdown.withRaftMarkdownOwnTaskStatus
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ThreadMarkdownTaskStatusesTest {
    private fun task(number: Int, status: TaskStatus) =
        TaskSummary(id = TaskId("task-$number-$status"), title = "t", status = status, taskNumber = number, updatedAtLabel = "now")

    @Test
    fun channelBeatsServer() {
        val statuses =
            threadMarkdownTaskStatusesByNumber(
                channelTasks = listOf(task(1, TaskStatus.Done)),
                serverTasks = listOf(task(1, TaskStatus.Todo), task(2, TaskStatus.InReview)),
                loaded = true,
            )
        assertEquals(mapOf(1 to TaskStatus.Done, 2 to TaskStatus.InReview), statuses)
    }

    @Test
    fun ownLinkedTaskBeatsChannelForThatMessageOnly() {
        val thread =
            threadMarkdownTaskStatusesByNumber(
                channelTasks = listOf(task(1, TaskStatus.Done), task(2, TaskStatus.Todo)),
                serverTasks = emptyList(),
                loaded = true,
            )
        val own = thread.withRaftMarkdownOwnTaskStatus(taskNumber = 1, taskStatus = TaskStatus.InProgress)
        assertEquals(mapOf(1 to TaskStatus.InProgress, 2 to TaskStatus.Todo), own)
        // The thread-wide map (other messages' bodies) is untouched.
        assertEquals(mapOf(1 to TaskStatus.Done, 2 to TaskStatus.Todo), thread)
        // A message's own task that the slices don't know yet is still added.
        assertEquals(TaskStatus.InReview, thread.withRaftMarkdownOwnTaskStatus(7, TaskStatus.InReview)?.get(7))
    }

    @Test
    fun ownTaskWithoutFactsOrSameStatusKeepsTheThreadMap() {
        val thread = mapOf(1 to TaskStatus.Done)
        assertSame(thread, thread.withRaftMarkdownOwnTaskStatus(null, TaskStatus.Todo))
        assertSame(thread, thread.withRaftMarkdownOwnTaskStatus(1, null))
        assertSame(thread, thread.withRaftMarkdownOwnTaskStatus(0, TaskStatus.Todo))
        assertSame(thread, thread.withRaftMarkdownOwnTaskStatus(1, TaskStatus.Done))
    }

    @Test
    fun noMapUntilATaskSliceHasLoaded() {
        assertNull(
            threadMarkdownTaskStatusesByNumber(
                channelTasks = listOf(task(1, TaskStatus.Done)),
                serverTasks = emptyList(),
                loaded = false,
            )
        )
        // Own-task overlay keeps the legacy chip too (no grey -> colour flash).
        assertNull(null.withRaftMarkdownOwnTaskStatus(1, TaskStatus.Done))
    }

    @Test
    fun loadedWhenEitherSliceIsLoaded() {
        val idle = SharedTasksProjectionSlice()
        val partial = SharedTasksProjectionSlice(status = SharedTasksDataStoreStatus.Partial)
        val loaded = SharedTasksProjectionSlice(status = SharedTasksDataStoreStatus.Loaded)
        assertFalse(threadMarkdownTaskSourceLoaded(idle, idle))
        assertFalse(threadMarkdownTaskSourceLoaded(partial, partial))
        assertTrue(threadMarkdownTaskSourceLoaded(loaded, idle))
        assertTrue(threadMarkdownTaskSourceLoaded(idle, loaded))
    }
}
