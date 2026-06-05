package ai.slock.android.feature.tasks

import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.TaskSnapshot
import ai.slock.compose.data.TasksContent as SharedTasksContent
import ai.slock.compose.data.TasksUiState as SharedTasksUiState
import ai.slock.compose.data.ScreenLoadState as SharedScreenLoadState
import ai.slock.compose.data.filteredForConversationIds as sharedFilteredForConversationIds
import ai.slock.compose.data.filteredForTasksFilter as sharedFilteredForTasksFilter
import ai.slock.compose.data.mergeTasksRefreshResult as sharedMergeTasksRefreshResult
import ai.slock.compose.data.mergeThreadTasksRefreshResult as sharedMergeThreadTasksRefreshResult
import ai.slock.compose.data.toTasksUiState as sharedToTasksUiState
import ai.slock.compose.data.toTasksUiStateForThreadTasks as sharedToTasksUiStateForThreadTasks
import ai.slock.compose.data.withLoading as sharedWithLoading
import ai.slock.compose.data.withRefreshFailure as sharedWithRefreshFailure
import ai.slock.compose.data.withTaskStatusUpdateFailure as sharedWithTaskStatusUpdateFailure
import ai.slock.compose.data.withTaskStatusUpdateSuccess as sharedWithTaskStatusUpdateSuccess
import ai.slock.compose.data.withTaskStatusUpdating as sharedWithTaskStatusUpdating

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

fun TasksUiState.withLoading(filter: TaskStatus? = selectedFilter): TasksUiState =
    toShared().sharedWithLoading(filter).toAndroid()

fun TasksUiState.withRefreshFailure(errorCode: String): TasksUiState =
    toShared().sharedWithRefreshFailure(errorCode).toAndroid()

fun TasksUiState.withTaskStatusUpdating(
    taskId: String,
    nextStatus: TaskStatus
): TasksUiState =
    toShared().sharedWithTaskStatusUpdating(taskId = taskId, nextStatus = nextStatus).toAndroid()

fun TasksUiState.withTaskStatusUpdateSuccess(task: TaskSummary): TasksUiState =
    toShared().sharedWithTaskStatusUpdateSuccess(task).toAndroid()

fun TasksUiState.withTaskStatusUpdateFailure(
    previousTask: TaskSummary,
    errorCode: String
): TasksUiState =
    toShared()
        .sharedWithTaskStatusUpdateFailure(previousTask = previousTask, errorCode = errorCode)
        .toAndroid()

fun DataResult<TaskSnapshot>.toTasksUiState(selectedFilter: TaskStatus?): TasksUiState =
    sharedToTasksUiState(selectedFilter).toAndroid()

fun TaskSnapshot.filteredForTasksFilter(filter: TaskStatus?): TaskSnapshot =
    sharedFilteredForTasksFilter(filter)

fun TaskSnapshot.filteredForConversationIds(knownConversationIds: Set<String>): TaskSnapshot =
    sharedFilteredForConversationIds(knownConversationIds)

fun TasksUiState.mergeTasksRefreshResult(
    result: DataResult<TaskSnapshot>,
    selectedFilter: TaskStatus?
): TasksUiState =
    toShared().sharedMergeTasksRefreshResult(result, selectedFilter).toAndroid()

fun TasksUiState?.mergeThreadTasksRefreshResult(result: DataResult<TaskSnapshot>): TasksUiState =
    this?.toShared().sharedMergeThreadTasksRefreshResult(result).toAndroid()

fun List<TaskSummary>.toTasksUiStateForThreadTasks(): TasksUiState =
    sharedToTasksUiStateForThreadTasks().toAndroid()

private fun TasksUiState.toShared(): SharedTasksUiState =
    SharedTasksUiState(
        loadState = loadState.toShared(),
        selectedFilter = selectedFilter,
        availableFilters = availableFilters,
        navigationErrorCode = navigationErrorCode,
        refreshing = refreshing,
        statusUpdatingTaskId = statusUpdatingTaskId,
        statusUpdateErrorCode = statusUpdateErrorCode
    )

private fun SharedTasksUiState.toAndroid(): TasksUiState =
    TasksUiState(
        loadState = loadState.toAndroid(),
        selectedFilter = selectedFilter,
        availableFilters = availableFilters,
        navigationErrorCode = navigationErrorCode,
        refreshing = refreshing,
        statusUpdatingTaskId = statusUpdatingTaskId,
        statusUpdateErrorCode = statusUpdateErrorCode
    )

private fun ScreenLoadState<TasksContent>.toShared(): SharedScreenLoadState<SharedTasksContent> =
    when (this) {
        is ScreenLoadState.Content ->
            SharedScreenLoadState.Content(
                SharedTasksContent(
                    summaryCounts = data.summaryCounts,
                    tasks = data.tasks
                )
            )
        is ScreenLoadState.Empty -> SharedScreenLoadState.Empty(reason)
        is ScreenLoadState.Error -> SharedScreenLoadState.Error(error)
        is ScreenLoadState.NoPermission -> SharedScreenLoadState.NoPermission(blocker)
        ScreenLoadState.Loading -> SharedScreenLoadState.Loading
    }

private fun SharedScreenLoadState<SharedTasksContent>.toAndroid(): ScreenLoadState<TasksContent> =
    when (this) {
        is SharedScreenLoadState.Content ->
            ScreenLoadState.Content(
                TasksContent(
                    summaryCounts = data.summaryCounts,
                    tasks = data.tasks
                )
            )
        is SharedScreenLoadState.Empty -> ScreenLoadState.Empty(reason)
        is SharedScreenLoadState.Error -> ScreenLoadState.Error(error)
        is SharedScreenLoadState.NoPermission -> ScreenLoadState.NoPermission(blocker)
        SharedScreenLoadState.Loading -> ScreenLoadState.Loading
    }
