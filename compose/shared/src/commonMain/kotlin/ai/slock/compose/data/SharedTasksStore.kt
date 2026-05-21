package ai.slock.compose.data

import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TasksUiState(
    val loadState: ScreenLoadState<TasksContent>,
    val selectedFilter: TaskStatus? = null,
    val availableFilters: List<TaskStatus> = TaskStatus.entries,
    val navigationErrorCode: String? = null,
    val refreshing: Boolean = false,
    val statusUpdatingTaskId: String? = null,
    val statusUpdateErrorCode: String? = null
)

data class TasksContent(
    val summaryCounts: Map<TaskStatus, Int>,
    val tasks: List<TaskSummary>
)

sealed interface SharedTasksIntent {
    data class SetContent(val snapshot: TaskSnapshot, val selectedFilter: TaskStatus? = null) : SharedTasksIntent
    data class StartLoading(val filter: TaskStatus? = null) : SharedTasksIntent
    data class RefreshFailed(val errorCode: String) : SharedTasksIntent
    data class UpdateTaskStatus(val taskId: String, val nextStatus: TaskStatus) : SharedTasksIntent
    data class TaskStatusUpdateSucceeded(val task: TaskSummary) : SharedTasksIntent
    data class TaskStatusUpdateFailed(val previousTask: TaskSummary, val errorCode: String) : SharedTasksIntent
    data class NavigationFailed(val errorCode: String?) : SharedTasksIntent
}

class SharedTasksStore(
    initialState: TasksUiState = TasksUiState(ScreenLoadState.Loading),
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<TasksUiState> = _state.asStateFlow()

    fun dispatch(intent: SharedTasksIntent) {
        scope.launch {
            accept(intent)
        }
    }

    fun accept(intent: SharedTasksIntent) {
        _state.value = _state.value.reduce(intent)
    }
}

fun TasksUiState.reduce(intent: SharedTasksIntent): TasksUiState =
    when (intent) {
        is SharedTasksIntent.SetContent ->
            TasksUiState(
                loadState = ScreenLoadState.Content(intent.snapshot.toTasksContent()),
                selectedFilter = intent.selectedFilter,
                refreshing = false
            )
        is SharedTasksIntent.StartLoading -> withLoading(intent.filter ?: selectedFilter)
        is SharedTasksIntent.RefreshFailed -> withRefreshFailure(intent.errorCode)
        is SharedTasksIntent.UpdateTaskStatus -> withTaskStatusUpdating(intent.taskId, intent.nextStatus)
        is SharedTasksIntent.TaskStatusUpdateSucceeded -> withTaskStatusUpdateSuccess(intent.task)
        is SharedTasksIntent.TaskStatusUpdateFailed ->
            withTaskStatusUpdateFailure(
                previousTask = intent.previousTask,
                errorCode = intent.errorCode
            )
        is SharedTasksIntent.NavigationFailed -> copy(navigationErrorCode = intent.errorCode)
    }

fun TasksUiState.withLoading(filter: TaskStatus? = selectedFilter): TasksUiState =
    if (loadState is ScreenLoadState.Content) {
        copy(
            selectedFilter = filter,
            navigationErrorCode = null,
            refreshing = true
        )
    } else {
        copy(
            loadState = ScreenLoadState.Loading,
            selectedFilter = filter,
            navigationErrorCode = null,
            refreshing = false
        )
    }

fun TasksUiState.withRefreshFailure(errorCode: String): TasksUiState =
    copy(
        refreshing = false,
        statusUpdateErrorCode = errorCode
    )

fun TasksUiState.withTaskStatusUpdating(
    taskId: String,
    nextStatus: TaskStatus
): TasksUiState {
    val contentState = loadState as? ScreenLoadState.Content
    return if (contentState != null) {
        copy(
            loadState =
                ScreenLoadState.Content(
                    contentState.data.withTaskStatus(taskId = taskId, status = nextStatus)
                ),
            navigationErrorCode = null,
            statusUpdatingTaskId = taskId,
            statusUpdateErrorCode = null
        )
    } else {
        copy(
            navigationErrorCode = null,
            statusUpdatingTaskId = taskId,
            statusUpdateErrorCode = null
        )
    }
}

fun TasksUiState.withTaskStatusUpdateSuccess(task: TaskSummary): TasksUiState {
    val contentState = loadState as? ScreenLoadState.Content
    return copy(
        loadState =
            if (contentState != null) {
                ScreenLoadState.Content(contentState.data.withTask(task))
            } else {
                loadState
            },
        statusUpdatingTaskId = null,
        statusUpdateErrorCode = null
    )
}

fun TasksUiState.withTaskStatusUpdateFailure(
    previousTask: TaskSummary,
    errorCode: String
): TasksUiState {
    val contentState = loadState as? ScreenLoadState.Content
    return copy(
        loadState =
            if (contentState != null) {
                ScreenLoadState.Content(contentState.data.withTask(previousTask))
            } else {
                loadState
            },
        statusUpdatingTaskId = null,
        statusUpdateErrorCode = errorCode
    )
}

fun TaskSnapshot.toTasksContent(): TasksContent =
    TasksContent(
        summaryCounts = summaryCounts.takeIf { it.isNotEmpty() } ?: tasks.summaryCounts(),
        tasks = tasks
    )

private fun TasksContent.withTask(task: TaskSummary): TasksContent {
    val nextTasks =
        if (tasks.any { it.id == task.id }) {
            tasks.map { current -> if (current.id == task.id) task else current }
        } else {
            tasks + task
        }
    return copy(tasks = nextTasks, summaryCounts = nextTasks.summaryCounts())
}

private fun TasksContent.withTaskStatus(
    taskId: String,
    status: TaskStatus
): TasksContent {
    val nextTasks =
        tasks.map { task ->
            if (task.id.value == taskId) task.copy(status = status) else task
        }
    return copy(tasks = nextTasks, summaryCounts = nextTasks.summaryCounts())
}

private fun List<TaskSummary>.summaryCounts(): Map<TaskStatus, Int> =
    TaskStatus.entries.associateWith { status -> count { it.status == status } }
