package ai.slock.android

import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskReferenceCacheTest {
    @Test
    fun incomingTaskStatusReplacesCachedStatusButKeepsResolvedReferenceFields() {
        val cached =
                task(status = TaskStatus.InProgress).copy(
                        taskNumber = 382,
                        messageId = MessageId("message-382"),
                        assignee =
                                MemberProfile(
                                        id = MemberId("agent-cindy"),
                                        displayName = "Cindy",
                                        handle = "cindy",
                                        kind = MemberKind.Agent
                                ),
                        channelName = "对话流专修"
                )
        val incoming =
                task(status = TaskStatus.Done).copy(
                        title = "",
                        taskNumber = null,
                        messageId = null,
                        assignee = null,
                        channelName = null,
                        updatedAtLabel = "12:24"
                )

        val merged = cached.mergeTaskReferenceCacheUpdate(incoming)

        assertEquals(TaskStatus.Done, merged.status)
        assertEquals(382, merged.taskNumber)
        assertEquals(MessageId("message-382"), merged.messageId)
        assertEquals("Cindy", merged.assignee?.displayName)
        assertEquals("对话流专修", merged.channelName)
        assertEquals("12:24", merged.updatedAtLabel)
    }

    @Test
    fun applyingCacheToVisibleTaskDoesNotOverrideVisibleStatus() {
        val visible = task(status = TaskStatus.Done).copy(updatedAtLabel = "Now")
        val cached =
                task(status = TaskStatus.InProgress).copy(
                        taskNumber = 382,
                        messageId = MessageId("message-382"),
                        channelId = ChannelId("channel-382")
                )

        val enriched = visible.withTaskReferenceFieldsFrom(cached)

        assertEquals(TaskStatus.Done, enriched.status)
        assertEquals(382, enriched.taskNumber)
        assertEquals(MessageId("message-382"), enriched.messageId)
        assertEquals(ChannelId("channel-382"), enriched.channelId)
        assertEquals("Now", enriched.updatedAtLabel)
    }

    @Test
    fun threadTaskStatusActionPrefersParentMessageId() {
        val task =
                task(status = TaskStatus.InProgress).copy(
                        id = TaskId("thread-reference-id"),
                        parentMessageId = MessageId("parent-message-382"),
                        messageId = MessageId("reply-message-382"),
                        isThreadTask = true
                )

        assertEquals(TaskId("parent-message-382"), task.statusActionTaskId(preferThreadMessageIdentity = true))
        assertEquals(TaskId("thread-reference-id"), task.statusActionTaskId(preferThreadMessageIdentity = false))
    }

    @Test
    fun threadTaskStatusActionFallsBackToMessageId() {
        val task =
                task(status = TaskStatus.InProgress).copy(
                        id = TaskId("thread-reference-id"),
                        messageId = MessageId("message-382"),
                        isThreadTask = true
                )

        assertEquals(TaskId("message-382"), task.statusActionTaskId(preferThreadMessageIdentity = true))
    }

    private fun task(status: TaskStatus) =
            TaskSummary(
                    id = TaskId("task-382"),
                    title = "Task 382",
                    status = status,
                    updatedAtLabel = "Earlier"
            )
}
