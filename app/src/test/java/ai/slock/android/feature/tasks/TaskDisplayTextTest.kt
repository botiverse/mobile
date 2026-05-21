package ai.slock.android.feature.tasks

import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.compose.data.tasksAssigneeDisplayName
import ai.slock.compose.data.tasksDisplayTitle
import ai.slock.compose.data.tasksSourceLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskDisplayTextTest {
    @Test
    fun displayTitleDoesNotDuplicateNumberPlaceholderTitle() {
        val task =
            TaskSummary(
                id = TaskId("task-20"),
                title = "#20",
                status = TaskStatus.InReview,
                taskNumber = 20,
                updatedAtLabel = ""
            )

        assertEquals("Task #20", task.tasksDisplayTitle())
    }

    @Test
    fun displayTitlePrefixesMeaningfulTitleWithTaskNumber() {
        val task =
            TaskSummary(
                id = TaskId("task-21"),
                title = "Fix Tasks list display",
                status = TaskStatus.InReview,
                taskNumber = 21,
                updatedAtLabel = ""
            )

        assertEquals("#21 Fix Tasks list display", task.tasksDisplayTitle())
    }

    @Test
    fun displayTitleRemovesDuplicateNumberPrefixFromMeaningfulTitle() {
        val task =
            TaskSummary(
                id = TaskId("task-21"),
                title = "#21 Fix Tasks list display",
                status = TaskStatus.InReview,
                taskNumber = 21,
                updatedAtLabel = ""
            )

        assertEquals("#21 Fix Tasks list display", task.tasksDisplayTitle())
    }

    @Test
    fun assigneeDisplayHidesRawUuidFallbacks() {
        val task =
            TaskSummary(
                id = TaskId("task-22"),
                title = "Fix Tasks list display",
                status = TaskStatus.InReview,
                assignee =
                    MemberProfile(
                        id = MemberId("027ca19e-d020-4054-bed5-0f9173333333"),
                        displayName = "027ca19e-d020-4054-bed5-0f9173333333",
                        handle = "027ca19e-d020-4054-bed5-0f9173333333",
                        kind = MemberKind.Agent
                    ),
                updatedAtLabel = ""
            )

        assertNull(task.tasksAssigneeDisplayName())
    }

    @Test
    fun sourceLabelDoesNotFallbackToSampleState() {
        val taskWithoutChannel =
            TaskSummary(
                id = TaskId("task-23"),
                title = "Real task with missing source",
                status = TaskStatus.Todo,
                updatedAtLabel = ""
            )
        val taskWithChannel =
            taskWithoutChannel.copy(channelName = "product")

        assertNull(taskWithoutChannel.tasksSourceLabel())
        assertEquals("#product", taskWithChannel.tasksSourceLabel())
    }
}
