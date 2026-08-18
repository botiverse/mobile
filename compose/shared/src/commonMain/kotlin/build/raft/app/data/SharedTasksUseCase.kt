package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Tasks application rules that should not live in UI state code.
 *
 * The use case owns session/server preconditions and delegates typed task data
 * work to the repository. Store reducers only receive typed results.
 */
class SharedTasksUseCase(
    private val authStore: SlockAuthSessionStore,
    private val repository: SharedTasksRepository,
    private val scope: SharedTasksScope = SharedTasksScope.Server
) {
    suspend fun observeTasks(request: TasksLoadRequest): Flow<DataResult<TaskSnapshot>> {
        val serverId =
            authStore.readSession()?.currentServerId?.let(::ServerId)
                ?: return flowOf(DataResult.Error(ScreenError(code = "selected_server_missing", retryable = false)))
        return repository.observeTasks(serverId, scope, request.filter)
    }

    suspend fun refreshTasks(request: TasksLoadRequest): DataResult<TaskSnapshot> {
        val serverId = selectedServerIdOrError() ?: return selectedServerMissing()
        return repository.refreshTasks(serverId, scope, request.filter, request.priority)
    }

    suspend fun currentServerId(): ServerId? =
        selectedServerIdOrError()

    suspend fun updateTaskStatus(request: TasksStatusUpdateRequest): DataResult<TaskActionResult> {
        val serverId = selectedServerIdOrError() ?: return selectedServerMissing()
        return updateTaskStatus(serverId, request)
    }

    suspend fun updateTaskStatus(
        serverId: ServerId,
        request: TasksStatusUpdateRequest
    ): DataResult<TaskActionResult> =
        repository.updateTaskStatus(serverId, request.taskId, request.status)

    suspend fun createTasks(request: TasksCreateRequest): DataResult<TaskSnapshot> {
        val serverId = selectedServerIdOrError() ?: return selectedServerMissing()
        return createTasks(serverId, request)
    }

    suspend fun createTasks(serverId: ServerId, request: TasksCreateRequest): DataResult<TaskSnapshot> =
        repository.createTasks(serverId, request.channelId, request.titles)

    private suspend fun selectedServerIdOrError(): ServerId? =
        authStore.readSession()?.currentServerId?.let(::ServerId)

    private fun <T> selectedServerMissing(): DataResult<T> =
        DataResult.Error(ScreenError(code = "selected_server_missing", retryable = false))
}
