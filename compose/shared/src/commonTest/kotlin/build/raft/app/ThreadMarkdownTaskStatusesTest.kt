package build.raft.app

import build.raft.app.data.SharedTasksDataStoreStatus
import build.raft.app.data.SharedTasksProjectionSlice
import build.raft.app.markdown.withRaftMarkdownOwnTaskStatus
import build.raft.app.model.ChannelId
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
    private fun task(number: Int, status: TaskStatus, channelId: String = "ch-here") =
        TaskSummary(
            id = TaskId("task-$number-$status-$channelId"),
            title = "t",
            status = status,
            taskNumber = number,
            updatedAtLabel = "now",
            channelId = ChannelId(channelId),
        )

    @Test
    fun channelBeatsServer() {
        val statuses =
            threadMarkdownTaskStatusesByNumber(
                channelTasks = listOf(task(1, TaskStatus.Done)),
                serverTasks = listOf(task(1, TaskStatus.Todo), task(2, TaskStatus.InReview)),
                loaded = true,
                currentChannelId = "ch-here",
            )
        assertEquals(mapOf(1 to TaskStatus.Done, 2 to TaskStatus.InReview), statuses)
    }

    @Test
    fun anotherChannelsTaskIsNotThisConversationsTask() {
        // Web `taskByChannelNumber`: a number is unique only inside its channel, so the server's
        // #4442 of another channel neither colours nor makes a chip of this conversation's `#4442`.
        val statuses =
            threadMarkdownTaskStatusesByNumber(
                channelTasks = emptyList(),
                serverTasks = listOf(task(4442, TaskStatus.Todo, channelId = "ch-other"), task(2, TaskStatus.Done)),
                loaded = true,
                currentChannelId = "ch-here",
            )
        assertEquals(mapOf(2 to TaskStatus.Done), statuses)
        assertEquals(
            emptyMap(),
            threadMarkdownTaskStatusesByNumber(
                channelTasks = emptyList(),
                serverTasks = listOf(task(2, TaskStatus.Done)),
                loaded = true,
                currentChannelId = null,
            )
        )
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
        // A slice demoted to Partial (canonical conflict) that still holds tasks stays usable,
        // so chips do not flip back to the legacy style while the refetch runs.
        val demoted = SharedTasksProjectionSlice(status = SharedTasksDataStoreStatus.Partial, tasks = listOf(task(1, TaskStatus.Done)))
        assertTrue(threadMarkdownTaskSourceLoaded(demoted, idle))
    }
}
