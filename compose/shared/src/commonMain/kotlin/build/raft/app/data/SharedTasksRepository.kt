package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.mapNotNull

sealed interface SharedTasksScope {
    data object Server : SharedTasksScope
    data class Channel(val channelId: ChannelId) : SharedTasksScope
}

/**
 * Domain-facing Tasks repository.
 *
 * This is above concrete API/local sources: callers ask for typed task domain
 * results, while the implementation decides which lower data source is used.
 */
interface SharedTasksRepository {
    fun observeTasks(serverId: ServerId, scope: SharedTasksScope, filter: TaskFilter): Flow<DataResult<TaskSnapshot>>

    suspend fun refreshTasks(
        serverId: ServerId,
        scope: SharedTasksScope,
        filter: TaskFilter,
        priority: DataRequestPriority = DataRequestPriority.Visible
    ): DataResult<TaskSnapshot>

    /**
     * One page of server Tasks (task #345): summary detail, [limit] items after
     * [cursor] (null = first page). Returns the page + opaque next cursor.
     */
    suspend fun loadServerTasksPage(
        serverId: ServerId,
        limit: Int,
        cursor: String?,
        priority: DataRequestPriority = DataRequestPriority.Visible,
        status: String? = null
    ): TasksPageResult = TasksPageResult.Error(code = "tasks_page_unsupported")

    suspend fun updateTaskStatus(serverId: ServerId, taskId: TaskId, status: TaskStatus): DataResult<TaskActionResult>

    /**
     * Assign/unassign a task. `assignee == null` unassigns. `expectedRevision`
     * is the OCC token from the projected task (omitted when the projection has
     * no revision yet, mirroring the web client).
     */
    suspend fun assignTask(
        serverId: ServerId,
        taskId: TaskId,
        assignee: MemberProfile?,
        expectedRevision: Long?
    ): DataResult<TaskActionResult>

    suspend fun createTasks(serverId: ServerId, channelId: ChannelId, titles: List<String>): DataResult<TaskSnapshot>
}

class SharedTasksDataRepository(
    private val localDataSource: SharedTasksLocalDataSource = InMemorySharedTasksLocalDataSource(),
    private val remoteDataSource: SharedTasksRemoteDataSource
) : SharedTasksRepository {
    override fun observeTasks(
        serverId: ServerId,
        scope: SharedTasksScope,
        filter: TaskFilter
    ): Flow<DataResult<TaskSnapshot>> =
        localDataSource.observeTasks(serverId, scope, filter).mapNotNull { snapshot ->
            snapshot?.toTasksDataResult()
        }

    override suspend fun refreshTasks(
        serverId: ServerId,
        scope: SharedTasksScope,
        filter: TaskFilter,
        priority: DataRequestPriority
    ): DataResult<TaskSnapshot> {
        val remoteResult =
            when (scope) {
                SharedTasksScope.Server -> remoteDataSource.loadTasks(serverId, TaskFilter(), priority)
                is SharedTasksScope.Channel ->
                    remoteDataSource.loadChannelTasks(serverId, scope.channelId, TaskFilter(), priority)
            }
        if (remoteResult is DataResult.Content) {
            localDataSource.replaceTasks(serverId, scope, remoteResult.data)
            return localDataSource.cachedTasks(serverId, scope, filter)?.toTasksDataResult()
                ?: DataResult.Empty(EmptyReason.NoTasks)
        }
        return remoteResult
    }

    override suspend fun loadServerTasksPage(
        serverId: ServerId,
        limit: Int,
        cursor: String?,
        priority: DataRequestPriority,
        status: String?
    ): TasksPageResult = remoteDataSource.loadServerTasksPage(serverId, limit, cursor, priority, status)

    override suspend fun updateTaskStatus(
        serverId: ServerId,
        taskId: TaskId,
        status: TaskStatus
    ): DataResult<TaskActionResult> {
        val result = remoteDataSource.updateTaskStatus(serverId, taskId, status)
        if (result is DataResult.Content) {
            localDataSource.upsertTask(serverId, result.data.task)
        }
        return result
    }

    override suspend fun assignTask(
        serverId: ServerId,
        taskId: TaskId,
        assignee: MemberProfile?,
        expectedRevision: Long?
    ): DataResult<TaskActionResult> {
        val result = remoteDataSource.assignTask(serverId, taskId, assignee, expectedRevision)
        if (result is DataResult.Content) {
            localDataSource.upsertTask(serverId, result.data.task)
        }
        return result
    }

    override suspend fun createTasks(
        serverId: ServerId,
        channelId: ChannelId,
        titles: List<String>
    ): DataResult<TaskSnapshot> {
        val result = remoteDataSource.createTasks(serverId, channelId, titles)
        if (result is DataResult.Content) {
            val channelScope = SharedTasksScope.Channel(channelId)
            localDataSource.mergeTasks(serverId, channelScope, result.data)
            localDataSource.mergeTasksIfPresent(serverId, SharedTasksScope.Server, result.data)
        }
        return result
    }
}

interface SharedTasksLocalDataSource {
    fun observeTasks(serverId: ServerId, scope: SharedTasksScope, filter: TaskFilter): Flow<TaskSnapshot?>

    suspend fun cachedTasks(serverId: ServerId, scope: SharedTasksScope, filter: TaskFilter): TaskSnapshot?

    suspend fun replaceTasks(serverId: ServerId, scope: SharedTasksScope, snapshot: TaskSnapshot)

    suspend fun mergeTasks(serverId: ServerId, scope: SharedTasksScope, snapshot: TaskSnapshot)

    suspend fun mergeTasksIfPresent(serverId: ServerId, scope: SharedTasksScope, snapshot: TaskSnapshot)

    suspend fun upsertTask(serverId: ServerId, task: TaskSummary)
}

interface SharedTasksRemoteDataSource {
    suspend fun loadTasks(
        serverId: ServerId,
        filter: TaskFilter = TaskFilter(),
        priority: DataRequestPriority = DataRequestPriority.Visible
    ): DataResult<TaskSnapshot>

    /** See [SharedTasksRepository.loadServerTasksPage]. */
    suspend fun loadServerTasksPage(
        serverId: ServerId,
        limit: Int,
        cursor: String?,
        priority: DataRequestPriority = DataRequestPriority.Visible,
        status: String? = null
    ): TasksPageResult = TasksPageResult.Error(code = "tasks_page_unsupported")

    suspend fun loadChannelTasks(
        serverId: ServerId,
        channelId: ChannelId,
        filter: TaskFilter = TaskFilter(),
        priority: DataRequestPriority = DataRequestPriority.Visible
    ): DataResult<TaskSnapshot>

    suspend fun updateTaskStatus(serverId: ServerId, taskId: TaskId, status: TaskStatus): DataResult<TaskActionResult>

    /** See [SharedTasksRepository.assignTask]. */
    suspend fun assignTask(
        serverId: ServerId,
        taskId: TaskId,
        assignee: MemberProfile?,
        expectedRevision: Long?
    ): DataResult<TaskActionResult>

    suspend fun createTasks(serverId: ServerId, channelId: ChannelId, titles: List<String>): DataResult<TaskSnapshot>
}

private data class SharedTasksCacheKey(
    val serverId: ServerId,
    val scopeId: String
)

private fun SharedTasksCacheKey(serverId: ServerId, scope: SharedTasksScope): SharedTasksCacheKey =
    SharedTasksCacheKey(
        serverId = serverId,
        scopeId =
            when (scope) {
                SharedTasksScope.Server -> "server"
                is SharedTasksScope.Channel -> "channel:${scope.channelId.value}"
            }
    )

class InMemorySharedTasksLocalDataSource(
    initialSnapshots: Map<ServerId, TaskSnapshot> = emptyMap()
) : SharedTasksLocalDataSource {
    private val snapshots =
        MutableStateFlow(initialSnapshots.mapKeys { (serverId, _) -> SharedTasksCacheKey(serverId, SharedTasksScope.Server) })

    override fun observeTasks(serverId: ServerId, scope: SharedTasksScope, filter: TaskFilter): Flow<TaskSnapshot?> =
        snapshots.mapNotNull { current ->
            current[SharedTasksCacheKey(serverId, scope)]?.filteredBy(filter)
        }

    override suspend fun cachedTasks(serverId: ServerId, scope: SharedTasksScope, filter: TaskFilter): TaskSnapshot? =
        snapshots.value[SharedTasksCacheKey(serverId, scope)]?.filteredBy(filter)

    override suspend fun replaceTasks(serverId: ServerId, scope: SharedTasksScope, snapshot: TaskSnapshot) {
        snapshots.value = snapshots.value + (SharedTasksCacheKey(serverId, scope) to snapshot.normalized())
    }

    override suspend fun mergeTasks(serverId: ServerId, scope: SharedTasksScope, snapshot: TaskSnapshot) {
        val key = SharedTasksCacheKey(serverId, scope)
        val current = snapshots.value[key]
        val merged =
            if (current == null) {
                snapshot.normalized()
            } else {
                current.merge(snapshot.tasks)
            }
        snapshots.value = snapshots.value + (key to merged)
    }

    override suspend fun mergeTasksIfPresent(serverId: ServerId, scope: SharedTasksScope, snapshot: TaskSnapshot) {
        val key = SharedTasksCacheKey(serverId, scope)
        val current = snapshots.value[key] ?: return
        snapshots.value = snapshots.value + (key to current.merge(snapshot.tasks))
    }

    override suspend fun upsertTask(serverId: ServerId, task: TaskSummary) {
        val serverKey = SharedTasksCacheKey(serverId, SharedTasksScope.Server)
        val nextSnapshots = snapshots.value.toMutableMap()
        nextSnapshots[serverKey] = nextSnapshots[serverKey].upsert(task)
        task.channelId?.let { channelId ->
            val channelKey = SharedTasksCacheKey(serverId, SharedTasksScope.Channel(channelId))
            nextSnapshots[channelKey] = nextSnapshots[channelKey].upsert(task)
        }
        nextSnapshots.keys
            .filter { key -> key.serverId == serverId && nextSnapshots[key]?.tasks?.any { it.id == task.id } == true }
            .forEach { key ->
                nextSnapshots[key] = nextSnapshots[key].upsert(task)
        }
        snapshots.value = nextSnapshots
    }
}

private fun TaskSnapshot?.upsert(task: TaskSummary): TaskSnapshot =
    this?.merge(listOf(task))
        ?: TaskSnapshot(tasks = listOf(task), summaryCounts = listOf(task).summaryCounts())

private fun TaskSnapshot.merge(tasksToMerge: List<TaskSummary>): TaskSnapshot {
    val incomingById = tasksToMerge.associateBy { it.id }
    val replaced = tasks.map { current -> incomingById[current.id] ?: current }
    val appended = tasksToMerge.filterNot { incoming -> tasks.any { current -> current.id == incoming.id } }
    val nextTasks = replaced + appended
    return copy(tasks = nextTasks, summaryCounts = nextTasks.summaryCounts())
}

private fun TaskSnapshot.normalized(): TaskSnapshot =
    copy(summaryCounts = summaryCounts.takeIf { it.isNotEmpty() } ?: tasks.summaryCounts())

private fun TaskSnapshot.filteredBy(filter: TaskFilter): TaskSnapshot {
    val filtered =
        tasks.filter { task ->
            filter.statuses.isEmpty() || task.status in filter.statuses
        }
    return copy(tasks = filtered, summaryCounts = filtered.summaryCounts())
}

private fun TaskSnapshot.toTasksDataResult(): DataResult<TaskSnapshot> =
    if (tasks.isEmpty()) {
        DataResult.Empty(EmptyReason.NoTasks)
    } else {
        DataResult.Content(this)
    }
