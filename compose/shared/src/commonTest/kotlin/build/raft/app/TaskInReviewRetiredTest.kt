package build.raft.app

import build.raft.app.data.TasksContent
import build.raft.app.data.boardColumns
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
 * nothing is in it, so a pre-v2 task still in it stays reachable.
 */
class TaskInReviewRetiredTest {
    private val withoutInReview = listOf(TaskStatus.Todo, TaskStatus.InProgress, TaskStatus.Done, TaskStatus.Closed)

    /** A task v2 task's menu: Backlog first (slock #12963), no In Review. */
    private val v2Choices = listOf(TaskStatus.Backlog) + withoutInReview

    /** A legacy orphan's menu: every status but Backlog (task v2 only); Unknown is never a choice. */
    private val everyChoice = listOf(TaskStatus.Todo, TaskStatus.InProgress, TaskStatus.InReview, TaskStatus.Done, TaskStatus.Closed)

    @Test
    fun aTaskIsNotOfferedInReview() {
        // A pre-v2 task opens as its conversation too, so it gets the same menu as a task made the v2 way.
        assertEquals(v2Choices, task().statusMenuChoices())
        assertEquals(v2Choices, task(conversationAnchored = true).statusMenuChoices())
    }

    @Test
    fun aTaskStillInReviewKeepsItAsItsCurrentOption() {
        assertEquals(TaskStatus.known, task(status = TaskStatus.InReview).statusMenuChoices())
    }

    @Test
    fun aLegacyTaskKeepsEveryStatus() {
        assertEquals(everyChoice, task(isLegacy = true).statusMenuChoices())
    }

    @Test
    fun theBoardHasNoEmptyInReviewColumn() {
        val content = content(task(id = "t1"), task(id = "t2", status = TaskStatus.Todo))
        // Task v2 adds the Backlog column first (raft-design tasks.md board order).
        assertEquals(listOf(TaskStatus.Backlog) + withoutInReview, content.boardColumns(renderLimit = 0).map { it.status })
    }

    @Test
    fun aLegacyTaskStillInReviewKeepsItsColumn() {
        val legacy = task(id = "legacy", status = TaskStatus.InReview, isLegacy = true)
        val columns = content(task(id = "t1"), legacy).boardColumns(renderLimit = 0)
        assertEquals(TaskStatus.known, columns.map { it.status })
        assertEquals(listOf("legacy"), columns.single { it.status == TaskStatus.InReview }.tasks.map { it.id.value })
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
