package build.raft.app

import build.raft.app.data.TaskConversationHeaderState
import build.raft.app.data.TaskStatusOption
import build.raft.app.data.TasksContent
import build.raft.app.data.boardColumns
import build.raft.app.model.ChannelId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * In Review is retired for task v2 (#wg-task-v2:f7043517, raft-design decision 0043). Mirrors web
 * botiverse/slock #12884: `getTaskStatusOptions(task, ..., taskV2)` drops in_review on a task that
 * `opensAsTaskConversation(task, taskV2)` unless it is the current status
 * (`tests/taskStatusRetiredInReview.test.ts`), and TasksPanel drops the In Review lane only while
 * task v2 is on and nothing is in it, so a pre-v2 task still in it stays reachable.
 */
class TaskInReviewRetiredTest {
    private val withoutInReview = listOf(TaskStatus.Todo, TaskStatus.InProgress, TaskStatus.Done, TaskStatus.Closed)

    @Test
    fun aV2TaskIsNotOfferedInReviewWithTaskV2On() {
        assertEquals(withoutInReview, task().statusMenuChoices(taskV2Enabled = true))
        // A task made the v2 way opens as its conversation even where the flag is off (web `isTaskV2Task`).
        assertEquals(withoutInReview, task(conversationAnchored = true).statusMenuChoices(taskV2Enabled = false))
    }

    @Test
    fun aTaskStillInReviewKeepsItAsItsCurrentOption() {
        assertEquals(TaskStatus.entries.toList(), task(status = TaskStatus.InReview).statusMenuChoices(taskV2Enabled = true))
    }

    @Test
    fun aLegacyTaskAndTaskV2OffKeepEveryStatus() {
        assertEquals(TaskStatus.entries.toList(), task(isLegacy = true).statusMenuChoices(taskV2Enabled = true))
        assertEquals(TaskStatus.entries.toList(), task().statusMenuChoices(taskV2Enabled = false))
    }

    @Test
    fun theTaskConversationBarDropsInReviewUnlessTheTaskIsInIt() {
        fun barStatuses(task: TaskSummary) =
            task.taskConversationHeaderBarModel(TaskConversationHeaderState(ChannelId("conv-1")), statusUpdating = false)
                .statusOptions.map(TaskStatusOption::status)
        assertEquals(withoutInReview, barStatuses(task(conversationAnchored = true)))
        assertEquals(TaskStatus.entries.toList(), barStatuses(task(status = TaskStatus.InReview, conversationAnchored = true)))
        // Closed -> Todo still reads "Reopen to Todo".
        val closed =
            task(status = TaskStatus.Closed, conversationAnchored = true)
                .taskConversationHeaderBarModel(TaskConversationHeaderState(ChannelId("conv-1")), statusUpdating = false)
        assertEquals(listOf(TaskStatus.Todo), closed.statusOptions.filter { it.reopensToTodo }.map { it.status })
    }

    @Test
    fun theBoardHasNoEmptyInReviewColumnWithTaskV2On() {
        val content = content(task(id = "t1"), task(id = "t2", status = TaskStatus.Todo))
        assertEquals(withoutInReview, content.boardColumns(renderLimit = 0, taskV2Enabled = true).map { it.status })
    }

    @Test
    fun aLegacyTaskStillInReviewKeepsItsColumn() {
        val legacy = task(id = "legacy", status = TaskStatus.InReview, isLegacy = true)
        val columns = content(task(id = "t1"), legacy).boardColumns(renderLimit = 0, taskV2Enabled = true)
        assertEquals(TaskStatus.entries.toList(), columns.map { it.status })
        assertEquals(listOf("legacy"), columns.single { it.status == TaskStatus.InReview }.tasks.map { it.id.value })
    }

    @Test
    fun taskV2OffKeepsTheEmptyInReviewColumn() {
        assertEquals(
            TaskStatus.entries.toList(),
            content(task(id = "t1")).boardColumns(renderLimit = 0, taskV2Enabled = false).map { it.status },
        )
    }

    private fun content(vararg tasks: TaskSummary) = TasksContent(summaryCounts = emptyMap(), tasks = tasks.toList())

    private fun task(
        id: String = "task-1",
        status: TaskStatus = TaskStatus.InProgress,
        isLegacy: Boolean = false,
        conversationAnchored: Boolean = false,
    ): TaskSummary =
        TaskSummary(
            id = TaskId(id),
            title = "Ship the bar",
            status = status,
            updatedAtLabel = "",
            isLegacy = isLegacy,
            conversationAnchored = conversationAnchored,
        )
}
