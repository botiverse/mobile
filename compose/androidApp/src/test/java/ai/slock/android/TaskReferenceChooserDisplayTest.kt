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

class TaskReferenceChooserDisplayTest {
    @Test
    fun chooserDisplayIncludesTaskNumberStatusTimeAndContext() {
        val task =
                TaskSummary(
                        id = TaskId("task-42"),
                        title = "Fix task context send route",
                        status = TaskStatus.InReview,
                        taskNumber = 42,
                        messageId = MessageId("abcdef123456"),
                        channelId = ChannelId("channel-android"),
                        assignee =
                                MemberProfile(
                                        id = MemberId("human-artin"),
                                        displayName = "Artin",
                                        handle = "@artin",
                                        kind = MemberKind.Human
                                ),
                        channelName = "对话流专修",
                        updatedAtLabel = "12:30"
                )

        assertEquals("#42 Fix task context send route", task.taskReferenceChooserTitle())
        assertEquals("In review · 12:30", task.taskReferenceChooserMetaLine())
        assertEquals("#对话流专修 · @artin · msg abcdef12", task.taskReferenceChooserContextLine())
    }

    @Test
    fun chooserDisplayFallsBackWithoutOptionalContext() {
        val task =
                TaskSummary(
                        id = TaskId("task-without-context"),
                        title = "Untitled fallback",
                        status = TaskStatus.Todo,
                        updatedAtLabel = ""
                )

        assertEquals("Untitled fallback", task.taskReferenceChooserTitle())
        assertEquals("Todo", task.taskReferenceChooserMetaLine())
        assertEquals("", task.taskReferenceChooserContextLine())
    }
}
