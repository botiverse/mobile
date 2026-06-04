package ai.slock.compose.data

import ai.slock.compose.model.ServerId

/**
 * Tasks application rules that should not live in UI state code.
 *
 * The use case owns session/server preconditions and delegates typed task data
 * work to the repository. Store reducers only receive typed results.
 */
class SharedTasksUseCase(
    private val authStore: SlockAuthSessionStore,
    private val repository: SharedTasksRepository
) {
    suspend fun loadTasks(request: TasksLoadRequest): DataResult<TaskSnapshot> {
        val serverId = authStore.readSession()?.currentServerId?.let(::ServerId)
            ?: return DataResult.Error(ScreenError(code = "selected_server_missing", retryable = false))
        return repository.loadTasks(serverId, request.filter)
    }

    suspend fun updateTaskStatus(request: TasksStatusUpdateRequest): DataResult<TaskActionResult> =
        repository.updateTaskStatus(request.taskId, request.status)

    suspend fun createTasks(request: TasksCreateRequest): DataResult<TaskSnapshot> =
        repository.createTasks(request.channelId, request.titles)
}
