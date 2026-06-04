package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Domain-facing Tasks repository.
 *
 * This is above concrete API/local sources: callers ask for typed task domain
 * results, while the implementation decides which lower data source is used.
 */
interface SharedTasksRepository {
    fun observeTasks(serverId: ServerId, filter: TaskFilter): Flow<DataResult<TaskSnapshot>>

    suspend fun refreshTasks(serverId: ServerId, filter: TaskFilter): DataResult<TaskSnapshot>

    suspend fun updateTaskStatus(serverId: ServerId, taskId: TaskId, status: TaskStatus): DataResult<TaskActionResult>

    suspend fun createTasks(serverId: ServerId, channelId: ChannelId, titles: List<String>): DataResult<TaskSnapshot>
}

class SharedTasksDataRepository(
    private val localDataSource: SharedTasksLocalDataSource = InMemorySharedTasksLocalDataSource(),
    private val remoteDataSource: SharedTasksRemoteDataSource
) : SharedTasksRepository {
    override fun observeTasks(serverId: ServerId, filter: TaskFilter): Flow<DataResult<TaskSnapshot>> =
        localDataSource.observeTasks(serverId, filter).mapNotNull { snapshot ->
            snapshot?.toTasksDataResult()
        }

    override suspend fun refreshTasks(serverId: ServerId, filter: TaskFilter): DataResult<TaskSnapshot> {
        val remoteResult = remoteDataSource.loadTasks(serverId, TaskFilter())
        if (remoteResult is DataResult.Content) {
            localDataSource.replaceTasks(serverId, remoteResult.data)
            return localDataSource.cachedTasks(serverId, filter)?.toTasksDataResult()
                ?: DataResult.Empty(EmptyReason.NoTasks)
        }
        return remoteResult
    }

    override suspend fun updateTaskStatus(
        serverId: ServerId,
        taskId: TaskId,
        status: TaskStatus
    ): DataResult<TaskActionResult> {
        val result = remoteDataSource.updateTaskStatus(taskId, status)
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
        val result = remoteDataSource.createTasks(channelId, titles)
        if (result is DataResult.Content) {
            localDataSource.mergeTasks(serverId, result.data)
        }
        return result
    }
}

interface SharedTasksLocalDataSource {
    fun observeTasks(serverId: ServerId, filter: TaskFilter): Flow<TaskSnapshot?>

    suspend fun cachedTasks(serverId: ServerId, filter: TaskFilter): TaskSnapshot?

    suspend fun replaceTasks(serverId: ServerId, snapshot: TaskSnapshot)

    suspend fun mergeTasks(serverId: ServerId, snapshot: TaskSnapshot)

    suspend fun upsertTask(serverId: ServerId, task: TaskSummary)
}

interface SharedTasksRemoteDataSource {
    suspend fun loadTasks(serverId: ServerId, filter: TaskFilter = TaskFilter()): DataResult<TaskSnapshot>

    suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): DataResult<TaskActionResult>

    suspend fun createTasks(channelId: ChannelId, titles: List<String>): DataResult<TaskSnapshot>
}

class InMemorySharedTasksLocalDataSource(
    initialSnapshots: Map<ServerId, TaskSnapshot> = emptyMap()
) : SharedTasksLocalDataSource {
    private val snapshots = MutableStateFlow(initialSnapshots)

    override fun observeTasks(serverId: ServerId, filter: TaskFilter): Flow<TaskSnapshot?> =
        snapshots.mapNotNull { current ->
            current[serverId]?.filteredBy(filter)
        }

    override suspend fun cachedTasks(serverId: ServerId, filter: TaskFilter): TaskSnapshot? =
        snapshots.value[serverId]?.filteredBy(filter)

    override suspend fun replaceTasks(serverId: ServerId, snapshot: TaskSnapshot) {
        snapshots.value = snapshots.value + (serverId to snapshot.normalized())
    }

    override suspend fun mergeTasks(serverId: ServerId, snapshot: TaskSnapshot) {
        val current = snapshots.value[serverId]
        val merged =
            if (current == null) {
                snapshot.normalized()
            } else {
                current.merge(snapshot.tasks)
            }
        snapshots.value = snapshots.value + (serverId to merged)
    }

    override suspend fun upsertTask(serverId: ServerId, task: TaskSummary) {
        val current = snapshots.value[serverId]
        val next =
            if (current == null) {
                TaskSnapshot(tasks = listOf(task), summaryCounts = listOf(task).summaryCounts())
            } else {
                current.merge(listOf(task))
            }
        snapshots.value = snapshots.value + (serverId to next)
    }
}

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
