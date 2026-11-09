package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Moving a task to Backlog with a reason, and Create Task's Status (slock #12962 server, #12963
 * web). The wire: `PATCH /tasks/:taskId/status` `{ status: "backlog", reason? }` (web taskStore
 * `updateTaskStatus`: the reason trimmed, blank sends none; the server refuses a reason with any
 * other status) and `POST /tasks/channel/:channelId` `{ tasks, status? }` (web `createTasks`; the
 * statuses are `TASK_CREATE_STATUSES`, Todo by default). History shows a move to Backlog's reason
 * (web `TimelineDetail`).
 */
class TaskBacklogMoveTest {
    @Test
    fun aMoveToBacklogSendsItsReasonTrimmedOnOneLine() {
        assertEquals(
            """{"status":"backlog","reason":"waits for the server-side record"}""",
            buildSharedTaskStatusBody(TaskStatus.Backlog, "  waits for the\nserver-side   record "),
        )
        // Blank is no reason; no reason at all is the body from before.
        assertEquals("""{"status":"backlog"}""", buildSharedTaskStatusBody(TaskStatus.Backlog, "   "))
        assertEquals("""{"status":"backlog"}""", buildSharedTaskStatusBody(TaskStatus.Backlog))
        // Only backlog takes one (the server answers 400 task_status_reason_invalid otherwise).
        assertEquals("""{"status":"todo"}""", buildSharedTaskStatusBody(TaskStatus.Todo, "not now"))
        // slock TASK_BACKLOG_REASON_MAX_LENGTH.
        assertEquals(TaskBacklogReasonMaxLength, taskBacklogReasonOrNull(TaskStatus.Backlog, "x".repeat(600))?.length)
    }

    @Test
    fun aCreateSendsTheChosenStatusOnlyWhenOneWasChosen() {
        val drafts = listOf(TaskCreateDraft("Offline drafts"), TaskCreateDraft("Sync rewrite"))
        assertEquals(
            """{"tasks":[{"title":"Offline drafts"},{"title":"Sync rewrite"}],"status":"backlog"}""",
            buildSharedTasksCreateBody(drafts, TaskStatus.Backlog),
        )
        assertEquals("""{"tasks":[{"title":"Offline drafts"},{"title":"Sync rewrite"}]}""", buildSharedTasksCreateBody(drafts))
        // A status a task can't start in is never sent (task_create_status_not_allowed).
        assertEquals(
            """{"tasks":[{"title":"Offline drafts"},{"title":"Sync rewrite"}]}""",
            buildSharedTasksCreateBody(drafts, TaskStatus.Done),
        )
        assertEquals(listOf(TaskStatus.Backlog, TaskStatus.Todo, TaskStatus.InProgress), TaskCreateStatuses)
        assertEquals(TaskStatus.Todo, DefaultTaskCreateStatus)
    }

    @Test
    fun onlyAMoveIntoBacklogFromAnotherStatusAsksForAReason() {
        TaskStatus.known.filter { it != TaskStatus.Backlog }.forEach { current ->
            assertTrue(taskStatusPickAsksBacklogReason(current, TaskStatus.Backlog), "current=$current")
            assertFalse(taskStatusPickAsksBacklogReason(current, TaskStatus.Done), "current=$current")
        }
        assertFalse(taskStatusPickAsksBacklogReason(TaskStatus.Backlog, TaskStatus.Backlog))
        assertFalse(taskStatusPickAsksBacklogReason(TaskStatus.Backlog, TaskStatus.Todo))
    }

    @Test
    fun historyShowsTheReasonOfAMoveToBacklogOnly() {
        val rows =
            parseTaskHistory(
                """
                {"events":[
                  {"id":"e1","eventType":"status_changed","actorType":"user","actorName":"Martin","createdAt":"2026-11-08T01:00:00.000Z",
                   "payload":{"from":"todo","to":"backlog","reason":"waits for the server-side record"}},
                  {"id":"e2","eventType":"status_changed","actorType":"user","actorName":"Martin","createdAt":"2026-11-08T02:00:00.000Z",
                   "payload":{"from":"todo","to":"backlog"}},
                  {"id":"e3","eventType":"status_changed","actorType":"user","actorName":"Martin","createdAt":"2026-11-08T03:00:00.000Z",
                   "payload":{"from":"backlog","to":"in_progress","reason":"claimed"}}
                ]}
                """.trimIndent()
            )!!.toTaskHistoryRows()
        assertEquals(
            TaskHistoryDetail.Transition(TaskStatus.Todo, TaskStatus.Backlog, "waits for the server-side record"),
            rows[0].detail,
        )
        // The hollow Backlog marker (web: a move to Backlog is painted with its own marker).
        assertEquals(TaskStatus.Backlog, rows[0].markerStatus)
        assertEquals(TaskHistoryDetail.Transition(TaskStatus.Todo, TaskStatus.Backlog), rows[1].detail)
        assertNull((rows[2].detail as TaskHistoryDetail.Transition).reason)
    }

    @Test
    fun theTasksStoreSendsTheReasonAndTheCreateStatusToTheServer() = runBlocking {
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val network = RecordingNetworkClient()
        val channelId = ChannelId("channel-1")
        val viewModel =
            SharedTasksViewModel(
                authStore =
                    InMemoryRaftAuthSessionStore(
                        StoredAuthSession(
                            user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
                            accessToken = "token",
                            refreshToken = "refresh",
                            selectedServerId = "server-1",
                        )
                    ),
                dataSource = SharedTasksDataSource(NetworkRaftTasksApi(network)),
                scope = SharedTasksScope.Channel(channelId),
                runtimeScopeOverride = runtimeScope,
            )
        val task =
            TaskSummary(id = TaskId("task-1"), title = "Offline drafts", status = TaskStatus.Todo, taskNumber = 1, channelId = channelId, updatedAtLabel = "now")

        viewModel.dispatch(SharedTasksRouteIntent.UpdateStatus(task, TaskStatus.Backlog, reason = "after the sync rewrite"))
        network.awaitCall { it.startsWith("PATCH tasks/task-1/status") }
        viewModel.dispatch(SharedTasksRouteIntent.CreateTasks(channelId, listOf("Sync rewrite"), TaskStatus.InProgress))
        network.awaitCall { it.startsWith("POST tasks/channel/channel-1") }

        assertEquals(
            listOf(
                """PATCH tasks/task-1/status server=server-1 body={"status":"backlog","reason":"after the sync rewrite"}""",
                """POST tasks/channel/channel-1 server=server-1 body={"tasks":[{"title":"Sync rewrite"}],"status":"in_progress"}""",
            ),
            network.calls.filter { it.startsWith("PATCH") || it.startsWith("POST") },
        )
        runtimeScope.cancel()
    }

    @Test
    fun theUseCaseCarriesTheReasonThroughTheRepository() = runBlocking {
        val network = RecordingNetworkClient()
        val repository = SharedTasksDataRepository(remoteDataSource = SharedTasksDataSource(NetworkRaftTasksApi(network)))
        val result =
            SharedTasksUseCase(InMemoryRaftAuthSessionStore(), repository)
                .updateTaskStatus(ServerId("server-1"), TasksStatusUpdateRequest(TaskId("task-1"), TaskStatus.Backlog, "not now"))
        assertIs<DataResult.Content<TaskActionResult>>(result)
        assertEquals(listOf("""PATCH tasks/task-1/status server=server-1 body={"status":"backlog","reason":"not now"}"""), network.calls)
    }

    private class RecordingNetworkClient : RaftNetworkClient {
        val calls = mutableListOf<String>()

        suspend fun awaitCall(match: (String) -> Boolean) {
            withTimeout(2_000L) { while (calls.none(match)) delay(10) }
        }

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            val route = path.trim('/')
            calls += "$method $route server=${serverIdOverride?.value.orEmpty()} body=${bodyText.orEmpty()}"
            return when {
                route.endsWith("/status") ->
                    ok("""{"task":{"id":"task-1","channelId":"channel-1","taskNumber":1,"title":"Offline drafts","status":"backlog","createdByType":"user","createdById":"user-1"}}""")
                method == "POST" ->
                    ok("""{"tasks":[{"id":"task-2","channelId":"channel-1","taskNumber":2,"title":"Sync rewrite","status":"in_progress","createdByType":"user","createdById":"user-1"}]}""")
                else ->
                    ok("""{"tasks":[{"id":"task-1","channelId":"channel-1","taskNumber":1,"title":"Offline drafts","status":"todo","createdByType":"user","createdById":"user-1"}]}""")
            }
        }

        private fun ok(body: String) = HomeNetworkResponse(code = 200, bodyText = body)
    }
}
