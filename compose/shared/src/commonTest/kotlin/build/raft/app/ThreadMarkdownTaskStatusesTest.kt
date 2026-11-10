package build.raft.app

import build.raft.app.data.SharedTasksDataStoreStatus
import build.raft.app.data.SharedTasksProjectionSlice
import build.raft.app.markdown.threadInlineTagMatches
import build.raft.app.markdown.withRaftMarkdownOwnTaskStatus
import build.raft.app.model.ChannelId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    private fun conversationPayload(channelKind: String, channelId: String) =
        RaftThreadScreenPayload(
            status = "content",
            surfaceKind = RaftThreadSurfaceKind.Channel,
            channelKind = channelKind,
            sourceThreadId = channelId,
            streamChannelId = channelId,
        )

    /**
     * Web TaskConversationDialog passes `parentChannelId: task.channelId`, so a task conversation's
     * `#7` is task 7 of the task's host channel, not of the conversation's own channel (which has
     * no tasks: every `#N` was plain and every tap missed).
     */
    @Test
    fun taskConversationBareRefsNameTheHostChannelsTasks() {
        val conversation = conversationPayload(RaftTaskConversationChannelKind, "task-conv-1")
        // Until the header resolves the host channel there is no context, never the conversation.
        assertNull(conversation.markdownTaskContextChannelId(taskConversationHostChannelId = null))
        val context = assertNotNull(conversation.markdownTaskContextChannelId(taskConversationHostChannelId = "ch-host"))
        assertEquals("ch-host", context)

        val statuses =
            assertNotNull(
                threadMarkdownTaskStatusesByNumber(
                    channelTasks = listOf(task(7, TaskStatus.InReview, channelId = "ch-host")),
                    serverTasks = listOf(task(9, TaskStatus.Todo, channelId = "task-conv-1")),
                    loaded = true,
                    currentChannelId = context,
                )
            )
        assertEquals(mapOf(7 to TaskStatus.InReview), statuses)
        val bare = "#7".threadInlineTagMatches().single().tag
        assertTrue(bare.isKnownThreadMarkdownTag(emptySet(), emptySet(), emptySet(), knownTaskNumbers = statuses.keys))
        // The host channel's tasks load through the task conversation's own tasks store.
        assertFalse(shouldLoadMarkdownTaskContextTasks(conversation, ChannelId(context), taskChannelId = null))

        // A channel's own `#7` keeps reading the channel itself.
        assertEquals("ch-here", conversationPayload("channel", "ch-here").markdownTaskContextChannelId("ignored"))
    }

    /** Web has no write gate on `taskContextChannelId`: a read-only reader still sees task chips. */
    @Test
    fun readOnlyReaderKeepsTheTaskContext() {
        val payload = conversationPayload("channel", "ch-here")
        // The write-gated lookup (Tasks tab, task writes) is gone for a read-only reader ...
        assertNull(payload.resolvedTaskLookupChannelId(writeActionsEnabled = false))
        // ... but the task refs' context is not.
        assertEquals("ch-here", payload.markdownTaskContextChannelId(taskConversationHostChannelId = null))
        // Nothing else loads its tasks then, so the refs load them.
        assertTrue(shouldLoadMarkdownTaskContextTasks(payload, ChannelId("ch-here"), taskChannelId = null))
        // A writer's channel is already loaded by the Tasks projection.
        assertFalse(shouldLoadMarkdownTaskContextTasks(payload, ChannelId("ch-here"), taskChannelId = ChannelId("ch-here")))
        assertFalse(
            shouldLoadMarkdownTaskContextTasks(payload.copy(status = "loading"), ChannelId("ch-here"), taskChannelId = null)
        )
    }
}
