package build.raft.app

import build.raft.app.markdown.threadInlineTagMatches
import build.raft.app.markdown.withRaftMarkdownOwnTaskStatus
import build.raft.app.model.ChannelId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.isKnownThreadMarkdownTag
import build.raft.app.thread.markdownTaskContextChannelId
import build.raft.app.thread.shouldLoadMarkdownTaskContextTasks
import build.raft.app.thread.threadMarkdownTaskStatusesByNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
}
