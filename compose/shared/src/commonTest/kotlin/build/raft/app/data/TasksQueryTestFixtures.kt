package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Shared fixtures of the Tasks page's server query tests. */
internal object TasksQueryTestFixtures {
    const val ServerUuid = "8d7c9c1e-0a43-4b6e-9a55-0c1f3e2b7a10"
    const val ViewerId = "2f1e3d4c-5b6a-4798-8a9b-0c1d2e3f4a5b"

    fun person(id: String, kind: MemberKind = MemberKind.Human): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = id,
            handle = id,
            kind = kind,
            avatarLabel = id.take(1),
            presence = Presence.Offline,
        )

    fun task(
        id: String,
        status: TaskStatus = TaskStatus.Todo,
        title: String = "Task $id",
        activityAt: String = "2026-11-01T00:00:00.000Z",
        assignee: MemberProfile? = null,
        creator: MemberProfile? = null,
        revision: Long? = 1L,
        channelId: String = "c1",
    ): TaskSummary =
        TaskSummary(
            id = TaskId(id),
            title = title,
            status = status,
            channelId = ChannelId(channelId),
            channelName = channelId,
            assignee = assignee,
            creator = creator,
            updatedAtLabel = activityAt,
            revision = revision,
            activityAt = activityAt,
        )
}

/** One page read the query store asked for. */
internal data class TasksQueryPageCall(
    val filters: ServerTaskBoardFilters,
    val sort: ServerTaskSort,
    val limit: Int,
    val cursor: String?,
    val priority: DataRequestPriority,
)

/**
 * A [SharedTasksRepository] answering the query store's page and counts reads through [page] and
 * [counts], recording each call. Everything else is unused by the query store.
 */
internal class FakeTasksQueryRepository(
    var page: suspend (TasksQueryPageCall) -> TasksPageResult = { TasksPageResult.Page(emptyList(), null) },
    var counts: suspend (ServerTaskBoardFilters) -> ServerTaskCountsResult = { ServerTaskCountsResult.Counts(zeroCounts()) },
) : SharedTasksRepository {
    val pageCalls = mutableListOf<TasksQueryPageCall>()
    val countCalls = mutableListOf<ServerTaskBoardFilters>()

    override suspend fun loadServerTaskQueryPage(
        serverId: ServerId,
        filters: ServerTaskBoardFilters,
        sort: ServerTaskSort,
        limit: Int,
        cursor: String?,
        priority: DataRequestPriority
    ): TasksPageResult {
        val call = TasksQueryPageCall(filters, sort, limit, cursor, priority)
        pageCalls += call
        return page(call)
    }

    override suspend fun loadServerTaskCounts(
        serverId: ServerId,
        filters: ServerTaskBoardFilters,
        priority: DataRequestPriority
    ): ServerTaskCountsResult {
        countCalls += filters
        return counts(filters)
    }

    override fun observeTasks(serverId: ServerId, scope: SharedTasksScope, filter: TaskFilter): Flow<DataResult<TaskSnapshot>> = emptyFlow()

    override suspend fun refreshTasks(
        serverId: ServerId,
        scope: SharedTasksScope,
        filter: TaskFilter,
        priority: DataRequestPriority
    ): DataResult<TaskSnapshot> = DataResult.Error(ScreenError("unused", traceId = null))

    override suspend fun updateTaskStatus(serverId: ServerId, taskId: TaskId, status: TaskStatus): DataResult<TaskActionResult> =
        DataResult.Error(ScreenError("unused", traceId = null))

    override suspend fun assignTask(
        serverId: ServerId,
        taskId: TaskId,
        assignee: MemberProfile?,
        expectedRevision: Long?
    ): DataResult<TaskActionResult> = DataResult.Error(ScreenError("unused", traceId = null))

    override suspend fun createTasks(serverId: ServerId, channelId: ChannelId, titles: List<String>): DataResult<TaskSnapshot> =
        DataResult.Error(ScreenError("unused", traceId = null))

    companion object {
        fun zeroCounts(total: Map<TaskStatus, Int> = emptyMap()): ServerTaskCounts =
            ServerTaskCounts(
                statuses = ServerTaskBoardStatuses.associateWith { total[it] ?: 0 },
                relations = ServerTaskRelation.entries.associateWith { 0 },
            )
    }
}

/** Scope and sort kept in memory. */
internal class InMemoryTasksPageQueryPreferences(
    var savedScope: TasksPageScope? = null,
    var savedSort: ServerTaskSort? = null,
) : TasksPageQueryPreferenceRepository {
    override fun scope(): TasksPageScope? = savedScope

    override fun saveScope(scope: TasksPageScope) {
        savedScope = scope
    }

    override fun sort(): ServerTaskSort? = savedSort

    override fun saveSort(sort: ServerTaskSort) {
        savedSort = sort
    }
}
