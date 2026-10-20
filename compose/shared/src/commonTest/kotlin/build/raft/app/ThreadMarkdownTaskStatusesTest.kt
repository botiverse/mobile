package build.raft.app

import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadMarkdownTaskStatusesTest {
    private fun task(number: Int, status: TaskStatus) =
        TaskSummary(id = TaskId("task-$number-$status"), title = "t", status = status, taskNumber = number, updatedAtLabel = "now")

    private fun message(number: Int?, status: String?) =
        RaftThreadMessageItem(id = "m-$number", authorId = "a", authorName = "A", taskNumber = number, taskStatus = status)

    @Test
    fun channelBeatsServerBeatsMessageFacts() {
        val statuses =
            threadMarkdownTaskStatusesByNumber(
                channelTasks = listOf(task(1, TaskStatus.Done)),
                serverTasks = listOf(task(1, TaskStatus.Todo), task(2, TaskStatus.InReview)),
                messages = listOf(message(2, "todo"), message(3, "in_progress"), message(4, "bogus"), message(null, "done")),
            )
        assertEquals(
            mapOf(1 to TaskStatus.Done, 2 to TaskStatus.InReview, 3 to TaskStatus.InProgress),
            statuses
        )
    }
}
