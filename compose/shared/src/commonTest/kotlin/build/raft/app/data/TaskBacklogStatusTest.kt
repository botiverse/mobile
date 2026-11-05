package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.sharedTaskStatusChipMetaOrDefault
import build.raft.app.strictTaskStatusOrNull
import build.raft.app.model.ChannelId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.TaskUpdated
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task v2 `backlog` (slock #12962: `TASK_STATUSES = ["backlog", "todo", "in_progress", "in_review",
 * "done", "closed"]`; raft-design decision 0042). Before this, the app had no Backlog: the strict
 * parser dropped a backlog task from every list and dropped its `task:updated`, so the old status
 * stayed until a reload. A status a newer server adds after this build now reads as Unknown, so the
 * task keeps showing instead of disappearing.
 */
class TaskBacklogStatusTest {
    @Test
    fun backlogWireValueParsesBothWays() {
        assertEquals(TaskStatus.Backlog, TaskStatus.fromWireValue("backlog"))
        assertEquals("backlog", TaskStatus.Backlog.toWireValue())
        assertEquals(TaskStatus.Backlog, "backlog".strictTaskStatusOrNull("test", diagnostics = { error(it) }))
        // Every server status round-trips; the list is the server's TASK_STATUSES, in order.
        assertEquals(
            listOf("backlog", "todo", "in_progress", "in_review", "done", "closed"),
            TaskStatus.known.map { it.toWireValue() },
        )
        TaskStatus.known.forEach { assertEquals(it, TaskStatus.fromWireValue(it.toWireValue())) }
    }

    @Test
    fun anUnknownStatusIsKeptAsUnknownNotDroppedAndNotTodo() {
        val logs = mutableListOf<String>()
        assertEquals(TaskStatus.Unknown, "on_hold".strictTaskStatusOrNull("test", logs::add))
        assertEquals(listOf("projection_value_unknown source=test field=task_status value=on_hold"), logs)
        // The app's own token re-read from a projection it wrote: Unknown again, no new log.
        assertEquals(TaskStatus.Unknown, TaskStatus.UNKNOWN_WIRE_VALUE.strictTaskStatusOrNull("test", logs::add))
        assertEquals(1, logs.size)
        // A missing status is still not a task status.
        assertNull(null.strictTaskStatusOrNull("test", logs::add))
        assertNull("  ".strictTaskStatusOrNull("test", logs::add))
        // Unknown is not a server status: never a filter, never a choice, never parsed from the wire as one.
        assertNull(TaskStatus.fromWireValue(TaskStatus.UNKNOWN_WIRE_VALUE))
        assertTrue(TaskStatus.Unknown !in TaskStatus.known)
        TaskStatus.entries.forEach { current ->
            assertTrue(TaskStatus.Unknown !in taskStatusChoices(current, inReviewRetired = false), "current=$current")
        }
    }

    @Test
    fun aBacklogTaskFromTheListEndpointStaysInTheList() {
        val task =
            TaskWireDto(id = "task-7", channelId = "channel-1", taskNumber = 7, title = "Not now", status = "backlog")
                .toTaskSummary(diagnostics = { error(it) })
        assertEquals(TaskStatus.Backlog, task?.status)
    }

    @Test
    fun backlogIsNotOfferedInTheStatusMenuYetButABacklogTaskKeepsItAsCurrent() {
        TaskStatus.known.filter { it != TaskStatus.Backlog }.forEach { current ->
            assertTrue(TaskStatus.Backlog !in taskStatusChoices(current, inReviewRetired = true), "current=$current")
        }
        assertTrue(TaskStatus.Backlog in taskStatusChoices(TaskStatus.Backlog, inReviewRetired = true))
    }

    @Test
    fun theBoardAndListShowBacklogFirstAndCollapsed() {
        val content = content(task("b", TaskStatus.Backlog), task("t", TaskStatus.Todo))
        val v2 = content.boardColumns(renderLimit = 0, taskV2Enabled = true)
        assertEquals(
            listOf(TaskStatus.Backlog, TaskStatus.Todo, TaskStatus.InProgress, TaskStatus.Done, TaskStatus.Closed),
            v2.map { it.status },
        )
        assertEquals(listOf("b"), v2.first().tasks.map { it.id.value })
        assertTrue(TaskStatus.Backlog in TasksPresentationState.Default.collapsedStatuses)
        // Without task v2 there is no empty Backlog column, but a task in it still shows.
        assertTrue(content(task("t", TaskStatus.Todo)).boardColumns(0, taskV2Enabled = false).none { it.status == TaskStatus.Backlog })
        assertEquals(listOf("b"), content.boardColumns(0, taskV2Enabled = false).single { it.status == TaskStatus.Backlog }.tasks.map { it.id.value })
    }

    @Test
    fun theBoardAsksForTheBacklogColumnOnlyOnceABacklogTaskWasSeen() {
        // A server without backlog (pre-#12962) answers 400 to status=backlog: no request until one is seen.
        assertEquals(
            listOf(TaskStatus.Todo, TaskStatus.InProgress, TaskStatus.InReview, TaskStatus.Done, TaskStatus.Closed),
            tasksBoardSeedStatuses(TaskStatus.entries, backlogSeen = false),
        )
        assertEquals(TaskStatus.known, tasksBoardSeedStatuses(TaskStatus.entries, backlogSeen = true))
    }

    @Test
    fun anUnknownStatusTaskGetsItsOwnLastColumnOnlyWhileItHasOne() {
        assertTrue(content(task("t", TaskStatus.Todo)).boardColumns(0, taskV2Enabled = true).none { it.status == TaskStatus.Unknown })
        val columns = content(task("u", TaskStatus.Unknown), task("t", TaskStatus.Todo)).boardColumns(0, taskV2Enabled = true)
        assertEquals(TaskStatus.Unknown, columns.last().status)
        assertEquals(listOf("u"), columns.last().tasks.map { it.id.value })
    }

    @Test
    fun besideAConversationBacklogWaitsAfterTheActiveGroups() {
        val columns =
            content(
                task("b", TaskStatus.Backlog),
                task("t", TaskStatus.Todo),
                task("p", TaskStatus.InProgress),
                task("d", TaskStatus.Done),
            ).boardColumns(0, taskV2Enabled = true).filter { it.totalCount > 0 }
        assertEquals(
            listOf(TaskStatus.Todo, TaskStatus.InProgress, TaskStatus.Backlog, TaskStatus.Done),
            columns.inConversationSectionOrder().map { it.status },
        )
    }

    @Test
    fun theThreadTaskChipReadsBacklogNotTodo() {
        assertEquals(TaskStatus.Backlog, "backlog".sharedTaskStatusChipMetaOrDefault().status)
        assertEquals(TaskStatus.Unknown, "unknown".sharedTaskStatusChipMetaOrDefault().status)
        assertEquals(TaskStatus.Unknown, "on_hold".sharedTaskStatusChipMetaOrDefault().status)
        assertEquals(TaskStatus.Todo, null.sharedTaskStatusChipMetaOrDefault().status)
    }

    @Test
    fun realtimeTaskUpdatedToBacklogPatchesTheTaskProjection() = runBlocking {
        val tasksDataStore = SharedTasksDataStore()
        val owner = SharedRealtimeDomainEventOwner(tasksDataStore)
        tasksDataStore.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Server,
            snapshot = TaskSnapshot(tasks = listOf(task("task-1", TaskStatus.Todo)), summaryCounts = emptyMap()),
        )
        val event =
            RealtimeEventMapper(diagnosticsLogger = { if (it.startsWith("projection_value_")) error("unexpected diagnostic: $it") }).map(
                RealtimeSocketEvents.TaskUpdated,
                """
                {
                  "channelId": "channel-1",
                  "task": {
                    "id": "task-1",
                    "revision": 2,
                    "channelId": "channel-1",
                    "taskNumber": 1,
                    "title": "Task task-1",
                    "status": "backlog",
                    "updatedAt": "2026-11-05T01:00:00Z"
                  }
                }
                """.trimIndent(),
            )
        val updated = assertIs<TaskUpdated>(event)
        assertEquals(TaskStatus.Backlog, updated.task.status)

        owner.deliverGuardedAppShellDomainFinal(
            sinks = RealtimeGuardedDomainEventSinks(),
            serverId = "server-1",
            event = updated,
        )

        val projected = tasksDataStore.channelProjectionFlow("server-1", "channel-1", null).first()
        assertEquals(listOf(TaskStatus.Backlog), projected.tasks.map { it.status })
    }

    private fun content(vararg tasks: TaskSummary) = TasksContent(summaryCounts = emptyMap(), tasks = tasks.toList())

    private fun task(id: String, status: TaskStatus): TaskSummary =
        TaskSummary(
            id = TaskId(id),
            title = "Task $id",
            status = status,
            channelId = ChannelId("channel-1"),
            updatedAtLabel = "now",
        )
}
