package ai.slock.compose.data

import ai.slock.compose.model.ServerId
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
        return repository.refreshTasks(serverId, scope, request.filter)
    }

    suspend fun seedTasks(snapshot: TaskSnapshot) {
        val serverId = selectedServerIdOrError() ?: return
        repository.seedTasks(serverId, scope, snapshot)
    }

    suspend fun updateTaskStatus(request: TasksStatusUpdateRequest): DataResult<TaskActionResult> {
        val serverId = selectedServerIdOrError() ?: return selectedServerMissing()
        return repository.updateTaskStatus(serverId, request.taskId, request.status)
    }

    suspend fun createTasks(request: TasksCreateRequest): DataResult<TaskSnapshot> {
        val serverId = selectedServerIdOrError() ?: return selectedServerMissing()
        return repository.createTasks(serverId, request.channelId, request.titles)
    }

    private suspend fun selectedServerIdOrError(): ServerId? =
        authStore.readSession()?.currentServerId?.let(::ServerId)

    private fun <T> selectedServerMissing(): DataResult<T> =
        DataResult.Error(ScreenError(code = "selected_server_missing", retryable = false))
}
