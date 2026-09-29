package build.raft.app.data

import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
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

    /** One page of server Tasks (task #345); null server → typed error. */
    suspend fun loadServerTasksPage(
        limit: Int,
        cursor: String?,
        priority: DataRequestPriority = DataRequestPriority.Visible,
        status: String? = null
    ): TasksPageResult {
        val serverId = selectedServerIdOrError()
            ?: return TasksPageResult.Error(code = "selected_server_missing")
        return repository.loadServerTasksPage(serverId, limit, cursor, priority, status)
    }

    suspend fun updateTaskStatus(request: TasksStatusUpdateRequest): DataResult<TaskActionResult> {
        val serverId = selectedServerIdOrError() ?: return selectedServerMissing()
        return updateTaskStatus(serverId, request)
    }

    suspend fun updateTaskStatus(
        serverId: ServerId,
        request: TasksStatusUpdateRequest
    ): DataResult<TaskActionResult> =
        repository.updateTaskStatus(serverId, request.taskId, request.status)

    /**
     * Assign/unassign a task. `assignee == null` unassigns. `expectedRevision`
     * is the projected task's revision when known (OCC token; omitted when the
     * projection has none, mirroring the web client).
     */
    suspend fun assignTask(
        serverId: ServerId,
        taskId: TaskId,
        assignee: MemberProfile?,
        expectedRevision: Long?
    ): DataResult<TaskActionResult> =
        repository.assignTask(serverId, taskId, assignee, expectedRevision)

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
