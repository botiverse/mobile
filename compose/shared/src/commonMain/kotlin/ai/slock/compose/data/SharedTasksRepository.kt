package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskStatus

/**
 * Domain-facing Tasks repository.
 *
 * This is above concrete API/local sources: callers ask for typed task domain
 * results, while the implementation decides which lower data source is used.
 */
interface SharedTasksRepository {
    suspend fun loadTasks(serverId: ServerId, filter: TaskFilter): DataResult<TaskSnapshot>

    suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): DataResult<TaskActionResult>

    suspend fun createTasks(channelId: ChannelId, titles: List<String>): DataResult<TaskSnapshot>
}

class SharedTasksDataRepository(
    private val remoteDataSource: SharedTasksDataSource
) : SharedTasksRepository {
    override suspend fun loadTasks(serverId: ServerId, filter: TaskFilter): DataResult<TaskSnapshot> =
        remoteDataSource.loadTasks(serverId, filter)

    override suspend fun updateTaskStatus(
        taskId: TaskId,
        status: TaskStatus
    ): DataResult<TaskActionResult> =
        remoteDataSource.updateTaskStatus(taskId, status)

    override suspend fun createTasks(channelId: ChannelId, titles: List<String>): DataResult<TaskSnapshot> =
        remoteDataSource.createTasks(channelId, titles)
}
