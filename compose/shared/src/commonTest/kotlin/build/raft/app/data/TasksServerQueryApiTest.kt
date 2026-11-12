package build.raft.app.data

import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.model.TaskStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The Tasks page's server query wire contract, against botiverse/slock staging
 * c6580f2f41a617f1c406f13ffdcac2150ea3b044:
 * packages/server/src/routes/tasks.ts (`parseServerTaskFilters`, `GET /server`, `GET /server/counts`),
 * packages/server/src/services/taskService.ts (`ServerTaskCounts`, `toServerTaskSummary`),
 * packages/web/src/components/task/tasksPage/tasksPageApi.ts (`serverTaskFilterParams`).
 */
class TasksServerQueryApiTest {
    // tasks.serverBoardFilters.api.test.ts "GET /api/tasks/server/counts counts every visible row ...": the unfiltered answer.
    private val canonicalCounts =
        """
        {"counts":{"backlog":1,"todo":4,"in_progress":3,"done":2,"closed":1},
         "relations":{"assigned_to_me":2,"created_by_me":2,"from_my_message":1}}
        """.trimIndent()

    // One `detail=summary` row with every field taskService.ts `toServerTaskSummary` writes.
    private val canonicalSummaryPage =
        """
        {"tasks":[{"id":"6f0c3a52-8d1e-4f7a-9b2c-3d4e5f607182","readOnlyReason":null,"messageId":null,
          "channelId":"1b2c3d4e-5f60-4718-8293-a4b5c6d7e8f9","channelName":"alpha","channelType":"channel",
          "taskNumber":12,"title":"Ship the board","revision":4,"status":"in_progress",
          "createdByType":"user","createdById":"2f1e3d4c-5b6a-4798-8a9b-0c1d2e3f4a5b","createdByName":"Ada",
          "claimedByType":"agent","claimedById":"9a8b7c6d-5e4f-4321-8765-0fedcba98765","claimedByName":"Bot",
          "claimedAt":"2026-11-01T09:00:00.000Z","completedAt":null,
          "createdAt":"2026-10-30T08:00:00.000Z","updatedAt":"2026-11-01T09:00:00.000Z",
          "lastActivityAt":"2026-11-02T10:30:00.000Z","activityAt":"2026-11-02T10:30:00.000Z",
          "isLegacy":false,"source":"tasks","hasDescription":true,"descriptionBytes":42,
          "sourceMessageId":null,"sourceRecorded":false,"sourceSenderType":null,"sourceSenderId":null,
          "conversationReplyCount":3}],
         "next_cursor":"opaque-cursor"}
        """.trimIndent()

    @Test
    fun countsParseTheServerShape() {
        val counts = parseSharedServerTaskCounts(canonicalCounts)!!
        assertEquals(
            mapOf(TaskStatus.Backlog to 1, TaskStatus.Todo to 4, TaskStatus.InProgress to 3, TaskStatus.Done to 2, TaskStatus.Closed to 1),
            counts.statuses,
        )
        assertEquals(11, counts.total)
        assertEquals(2, counts.relations[ServerTaskRelation.AssignedToMe])
        assertEquals(2, counts.relations[ServerTaskRelation.CreatedByMe])
        assertEquals(1, counts.relations[ServerTaskRelation.FromMyMessage])
    }

    @Test
    fun countsRejectGuessedWrappers() {
        assertNull(parseSharedServerTaskCounts("""{"data":$canonicalCounts}"""))
        assertNull(parseSharedServerTaskCounts("""{"counts":{"inProgress":3}}"""))
    }

    @Test
    fun summaryRowCarriesItsActivityTime() {
        val page = parseSharedServerTasksPagePayload(canonicalSummaryPage)!!
        val task = page.tasks.single()
        assertEquals("opaque-cursor", page.nextCursor)
        assertEquals("2026-11-02T10:30:00.000Z", task.activityAt)
        assertEquals(TaskStatus.InProgress, task.status)
        assertEquals(MemberKind.Agent, task.assignee?.kind)
        assertEquals(false, task.descriptionLoaded)
        // The summary says who sent the source message (here: none), so from_my_message is decided.
        assertEquals(true, task.sourceSenderReported)
        assertNull(task.sourceSenderType)
    }

    @Test
    fun summaryRowSourceSenderIsParsedAndAFullRowLeavesItUnknown() {
        val fromMe =
            canonicalSummaryPage
                .replace("\"sourceSenderType\":null", "\"sourceSenderType\":\"user\"")
                .replace("\"sourceSenderId\":null", "\"sourceSenderId\":\"2f1e3d4c-5b6a-4798-8a9b-0c1d2e3f4a5b\"")
        val task = parseSharedServerTasksPagePayload(fromMe)!!.tasks.single()
        assertEquals("user", task.sourceSenderType)
        assertEquals("2f1e3d4c-5b6a-4798-8a9b-0c1d2e3f4a5b", task.sourceSenderId)
        val fullRow =
            canonicalSummaryPage
                .replace("\"sourceSenderType\":null,", "")
                .replace("\"sourceSenderId\":null,", "")
        assertEquals(false, parseSharedServerTasksPagePayload(fullRow)!!.tasks.single().sourceSenderReported)
    }

    @Test
    fun filterParamsFollowTheWeb() {
        val params =
            serverTaskFilterQuery(
                ServerTaskBoardFilters(
                    statuses = listOf(TaskStatus.Todo, TaskStatus.InProgress),
                    channelIds = listOf("c2", "c1"),
                    assignees = listOf("unassigned", "agent:a1"),
                    creators = listOf("user:u1"),
                    relation = ServerTaskRelation.CreatedByMe,
                    completedSince = "2026-11-11T00:00:00Z",
                    query = "  ship board  ",
                ),
            ).toMap()
        // In progress is said as a list with in_review, so a lone status never reads as the legacy exact match.
        assertEquals("in_progress,in_review,todo", params["status"])
        assertEquals("c1,c2", params["channel"])
        assertEquals("agent:a1,unassigned", params["assignee"])
        assertEquals("user:u1", params["creator"])
        assertEquals("created_by_me", params["relation"])
        assertEquals("2026-11-11T00:00:00Z", params["completed_since"])
        assertEquals("ship board", params["q"])
    }

    @Test
    fun emptyFiltersSendNothingAndPagesCarryTheWalk() {
        assertEquals(emptyList(), serverTaskFilterQuery(ServerTaskBoardFilters(query = "   ")))
        val first = serverTaskPageQuery(ServerTaskBoardFilters(), ServerTaskSort.ActivityAsc, limit = 50, cursor = null).toMap()
        assertEquals(mapOf("sort" to "activity_asc", "limit" to "50", "detail" to "summary"), first)
        val next = serverTaskPageQuery(ServerTaskBoardFilters(), ServerTaskSort.Created, limit = 50, cursor = "abc").toMap()
        assertEquals("abc", next["cursor"])
        assertEquals("created", next["sort"])
    }

    @Test
    fun failedReadsCarryTheTraceId() = runTest {
        val traceId = "0af7651916cd43dd8448eb211c80319c"
        val api =
            object : RaftTasksApi {
                override suspend fun serverTasksQuery(serverId: ServerId, priority: DataRequestPriority, query: List<Pair<String, String>>) =
                    HomeNetworkResponse(code = 500, bodyText = null, responseHeaders = mapOf("X-Trace-Id" to listOf(traceId)))

                override suspend fun serverTaskCounts(serverId: ServerId, priority: DataRequestPriority, query: List<Pair<String, String>>) =
                    HomeNetworkResponse(code = 503, bodyText = null, responseHeaders = mapOf("X-Trace-Id" to listOf(traceId)))

                override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority) =
                    HomeNetworkResponse(code = 501, bodyText = null)

                override suspend fun updateTaskStatus(taskId: build.raft.app.model.TaskId, status: TaskStatus) =
                    HomeNetworkResponse(code = 501, bodyText = null)
            }
        val source = SharedTasksDataSource(api)
        val page = source.loadServerTaskQueryPage(ServerId("s1"), ServerTaskBoardFilters(), ServerTaskSort.Activity, 50, null, DataRequestPriority.Visible)
        val pageError = assertIs<TasksPageResult.Error>(page)
        assertEquals(traceId, pageError.traceId)
        val counts = assertIs<ServerTaskCountsResult.Error>(source.loadServerTaskCounts(ServerId("s1"), ServerTaskBoardFilters(), DataRequestPriority.Visible))
        assertEquals(traceId, counts.traceId)
        assertEquals("tasks_counts_http_503", counts.code)
    }

    @Test
    fun theNetworkTransportSendsTheQueryToTheBoardRoutes() = runTest {
        val api =
            object : RaftTasksApi {
                val paths = mutableListOf<List<Pair<String, String>>>()

                override suspend fun serverTasksQuery(serverId: ServerId, priority: DataRequestPriority, query: List<Pair<String, String>>): HomeNetworkResponse {
                    paths += query
                    return HomeNetworkResponse(code = 200, bodyText = canonicalSummaryPage)
                }

                override suspend fun serverTaskCounts(serverId: ServerId, priority: DataRequestPriority, query: List<Pair<String, String>>): HomeNetworkResponse {
                    paths += query
                    return HomeNetworkResponse(code = 200, bodyText = canonicalCounts)
                }

                override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority) =
                    HomeNetworkResponse(code = 501, bodyText = null)

                override suspend fun updateTaskStatus(taskId: build.raft.app.model.TaskId, status: TaskStatus) =
                    HomeNetworkResponse(code = 501, bodyText = null)
            }
        val source = SharedTasksDataSource(api)
        val filters = ServerTaskBoardFilters(relation = ServerTaskRelation.AssignedToMe, query = "#12")
        val page = assertIs<TasksPageResult.Page>(source.loadServerTaskQueryPage(ServerId("s1"), filters, ServerTaskSort.Activity, 50, null, DataRequestPriority.Visible))
        assertEquals(1, page.tasks.size)
        val counts = assertIs<ServerTaskCountsResult.Counts>(source.loadServerTaskCounts(ServerId("s1"), filters, DataRequestPriority.Background))
        assertEquals(11, counts.counts.total)
        assertEquals("assigned_to_me", api.paths[0].toMap()["relation"])
        assertEquals("#12", api.paths[0].toMap()["q"])
        // Counts take the filters only: limit, cursor, detail and sort are each a 400 there.
        assertEquals(setOf("relation", "q"), api.paths[1].toMap().keys)
    }
}
