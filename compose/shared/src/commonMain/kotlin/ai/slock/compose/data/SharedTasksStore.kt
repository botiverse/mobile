package ai.slock.compose.data

import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import ai.slock.compose.model.ServerId
import com.tencent.kuikly.lifecycle.ViewModel
import com.tencent.kuikly.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

enum class TasksViewMode {
    Board,
    List
}

data class TasksPresentationState(
    val selectedChannelNames: Set<String> = emptySet(),
    val channelSelectorExpanded: Boolean = false,
    val selectedMode: TasksViewMode = TasksViewMode.Board
)

data class TasksVisibleContent(
    val channelOptions: List<String>,
    val selectedChannelNames: Set<String>,
    val channelScopedContent: TasksContent,
    val filteredContent: TasksContent,
    val filteredTasks: List<TaskSummary>
)

data class TasksBoardColumn(
    val status: TaskStatus,
    val totalCount: Int,
    val tasks: List<TaskSummary>
)

sealed interface SharedTasksIntent {
    data object StartLoadingCurrentFilter : SharedTasksIntent
    data class SetResult(val result: DataResult<TaskSnapshot>, val selectedFilter: TaskStatus? = null) : SharedTasksIntent
    data class MergeRefreshResult(val result: DataResult<TaskSnapshot>, val selectedFilter: TaskStatus? = null) : SharedTasksIntent
    data class SetContent(val snapshot: TaskSnapshot, val selectedFilter: TaskStatus? = null) : SharedTasksIntent
    data class StartLoading(val filter: TaskStatus? = null) : SharedTasksIntent
    data class RefreshFailed(val errorCode: String) : SharedTasksIntent
    data class UpdateTaskStatus(val taskId: String, val nextStatus: TaskStatus) : SharedTasksIntent
    data class TaskStatusUpdateSucceeded(val task: TaskSummary) : SharedTasksIntent
    data class TaskStatusUpdateFailed(val previousTask: TaskSummary, val errorCode: String) : SharedTasksIntent
    data class NavigationFailed(val errorCode: String?) : SharedTasksIntent
}

sealed interface SharedTasksRouteIntent {
    data object Start : SharedTasksRouteIntent
    data object Refresh : SharedTasksRouteIntent
    data class SelectFilter(val status: TaskStatus?) : SharedTasksRouteIntent
    data class UpdateStatus(val task: TaskSummary, val status: TaskStatus) : SharedTasksRouteIntent
    data object ClearNavigationError : SharedTasksRouteIntent
}

sealed interface TasksPresentationIntent {
    data class SetChannelSelectorExpanded(val expanded: Boolean) : TasksPresentationIntent
    data class ToggleChannel(val channelName: String) : TasksPresentationIntent
    data object ClearChannels : TasksPresentationIntent
    data class SelectMode(val mode: TasksViewMode) : TasksPresentationIntent
    data class PruneUnavailableChannels(val availableChannels: Set<String>) : TasksPresentationIntent
}

class SharedTasksViewModel(
    private val authStore: SlockAuthSessionStore,
    private val dataSource: SharedTasksDataSource
) : ViewModel() {
    private val store = SharedTasksStore(scope = viewModelScope)
    val state: StateFlow<TasksUiState> = store.state

    private var loadJob: Job? = null
    private var statusJob: Job? = null

    fun dispatch(intent: SharedTasksRouteIntent) {
        when (intent) {
            SharedTasksRouteIntent.Start,
            SharedTasksRouteIntent.Refresh,
            is SharedTasksRouteIntent.SelectFilter -> {
                loadJob?.cancel()
                loadJob =
                    viewModelScope.launch {
                        load(intent)
                    }
            }
            is SharedTasksRouteIntent.UpdateStatus -> {
                statusJob?.cancel()
                statusJob =
                    viewModelScope.launch {
                        updateStatus(intent.task, intent.status)
                    }
            }
            SharedTasksRouteIntent.ClearNavigationError ->
                store.dispatch(SharedTasksIntent.NavigationFailed(null))
        }
    }

    private suspend fun load(intent: SharedTasksRouteIntent) {
        val selectedFilter =
            when (intent) {
                is SharedTasksRouteIntent.SelectFilter -> intent.status
                else -> state.value.selectedFilter
            }
        store.accept(SharedTasksIntent.StartLoading(selectedFilter))
        try {
            val serverId =
                authStore.readSession()?.currentServerId?.let(::ServerId)
                    ?: run {
                        store.accept(SharedTasksIntent.RefreshFailed("selected_server_missing"))
                        return
            }
            val result = dataSource.loadTasks(serverId, TaskFilter(statuses = selectedFilter?.let(::setOf) ?: emptySet()))
            store.accept(
                if (intent == SharedTasksRouteIntent.Refresh) {
                    SharedTasksIntent.MergeRefreshResult(result, selectedFilter)
                } else {
                    SharedTasksIntent.SetResult(result, selectedFilter)
                }
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            store.accept(SharedTasksIntent.RefreshFailed("tasks_network"))
        }
    }

    private suspend fun updateStatus(task: TaskSummary, status: TaskStatus) {
        store.accept(SharedTasksIntent.UpdateTaskStatus(task.id.value, status))
        try {
            when (val result = dataSource.updateTaskStatus(task.id, status)) {
                is DataResult.Content -> store.accept(SharedTasksIntent.TaskStatusUpdateSucceeded(result.data.task))
                is DataResult.Empty -> store.accept(SharedTasksIntent.TaskStatusUpdateFailed(task, result.reason.name))
                is DataResult.Error -> store.accept(SharedTasksIntent.TaskStatusUpdateFailed(task, result.error.code))
                is DataResult.NoPermission -> store.accept(SharedTasksIntent.TaskStatusUpdateFailed(task, result.blocker.code))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            store.accept(SharedTasksIntent.TaskStatusUpdateFailed(task, "tasks_status_network"))
        }
    }

    override fun onCleared() {
        loadJob?.cancel()
        statusJob?.cancel()
        super.onCleared()
    }
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
        SharedTasksIntent.StartLoadingCurrentFilter -> withLoading(selectedFilter)
        is SharedTasksIntent.SetResult -> intent.result.toTasksUiState(intent.selectedFilter)
        is SharedTasksIntent.MergeRefreshResult -> mergeTasksRefreshResult(intent.result, intent.selectedFilter)
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

fun TasksPresentationState.reduce(intent: TasksPresentationIntent): TasksPresentationState =
    when (intent) {
        is TasksPresentationIntent.SetChannelSelectorExpanded -> copy(channelSelectorExpanded = intent.expanded)
        is TasksPresentationIntent.ToggleChannel ->
            copy(
                selectedChannelNames =
                    if (intent.channelName in selectedChannelNames) {
                        selectedChannelNames - intent.channelName
                    } else {
                        selectedChannelNames + intent.channelName
                    }
            )
        TasksPresentationIntent.ClearChannels -> copy(selectedChannelNames = emptySet())
        is TasksPresentationIntent.SelectMode -> copy(selectedMode = intent.mode)
        is TasksPresentationIntent.PruneUnavailableChannels ->
            copy(selectedChannelNames = selectedChannelNames.filter { it in intent.availableChannels }.toSet())
    }

fun TasksContent.visibleContent(
    selectedFilter: TaskStatus?,
    presentationState: TasksPresentationState,
    showChannelFilter: Boolean
): TasksVisibleContent {
    val channelOptions =
        tasks
            .mapNotNull { it.channelName }
            .distinct()
            .sorted()
    val selectedChannels =
        if (showChannelFilter) {
            presentationState.selectedChannelNames.filter { it in channelOptions }.toSet()
        } else {
            emptySet()
        }
    val channelScopedTasks =
        tasks.filter { task ->
            selectedChannels.isEmpty() || task.channelName in selectedChannels
        }
    val channelScopedContent =
        copy(
            summaryCounts = channelScopedTasks.summaryCounts(),
            tasks = channelScopedTasks
        )
    val filteredTasks =
        channelScopedTasks.filter { task ->
            selectedFilter == null || task.status == selectedFilter
        }
    val filteredContent = channelScopedContent.copy(tasks = filteredTasks)
    return TasksVisibleContent(
        channelOptions = channelOptions,
        selectedChannelNames = selectedChannels,
        channelScopedContent = channelScopedContent,
        filteredContent = filteredContent,
        filteredTasks = filteredTasks
    )
}

fun DataResult<TaskSnapshot>.toTasksUiState(selectedFilter: TaskStatus?): TasksUiState =
    TasksUiState(
        loadState =
            when (this) {
                is DataResult.Content -> ScreenLoadState.Content(data.toTasksContent())
                is DataResult.Empty -> ScreenLoadState.Empty(reason)
                is DataResult.Error -> ScreenLoadState.Error(error)
                is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
            },
        selectedFilter = selectedFilter
    )

fun TaskSnapshot.filteredForTasksFilter(filter: TaskStatus?): TaskSnapshot {
    val filteredTasks = filter?.let { status -> tasks.filter { it.status == status } } ?: tasks
    return copy(
        tasks = filteredTasks,
        summaryCounts = tasks.summaryCounts()
    )
}

fun TaskSnapshot.filteredForConversationIds(knownConversationIds: Set<String>): TaskSnapshot {
    val filteredTasks =
        tasks.filter { task ->
            if (knownConversationIds.isNotEmpty()) {
                listOfNotNull(
                    task.channelId?.value,
                    task.threadId?.value
                ).any { it in knownConversationIds }
            } else {
                task.channelId != null || task.threadId != null
            }
        }
    return copy(
        tasks = filteredTasks,
        summaryCounts = filteredTasks.summaryCounts()
    )
}

fun TasksUiState.mergeTasksRefreshResult(
    result: DataResult<TaskSnapshot>,
    selectedFilter: TaskStatus?
): TasksUiState =
    when (result) {
        is DataResult.Content -> result.toTasksUiState(selectedFilter).copy(refreshing = false)
        is DataResult.Empty ->
            takeIf { loadState is ScreenLoadState.Content }
                ?.copy(
                    selectedFilter = selectedFilter,
                    refreshing = false,
                    navigationErrorCode = result.reason.name
                )
                ?: result.toTasksUiState(selectedFilter).copy(refreshing = false)
        is DataResult.Error ->
            takeIf { loadState is ScreenLoadState.Content }
                ?.copy(
                    selectedFilter = selectedFilter,
                    refreshing = false,
                    navigationErrorCode = result.error.code
                )
                ?: result.toTasksUiState(selectedFilter).copy(refreshing = false)
        is DataResult.NoPermission ->
            takeIf { loadState is ScreenLoadState.Content }
                ?.copy(
                    selectedFilter = selectedFilter,
                    refreshing = false,
                    navigationErrorCode = result.blocker.code
                )
                ?: result.toTasksUiState(selectedFilter).copy(refreshing = false)
    }

fun TasksUiState?.mergeThreadTasksRefreshResult(result: DataResult<TaskSnapshot>): TasksUiState {
    val existing = this
    return when (result) {
        is DataResult.Content -> result.toTasksUiState(null).copy(refreshing = false)
        is DataResult.Empty ->
            existing
                ?.takeIf { it.loadState is ScreenLoadState.Content }
                ?.copy(refreshing = false)
                ?: result.toTasksUiState(null).copy(refreshing = false)
        is DataResult.Error ->
            existing
                ?.takeIf { it.loadState is ScreenLoadState.Content }
                ?.withRefreshFailure(result.error.code)
                ?: result.toTasksUiState(null).copy(refreshing = false)
        is DataResult.NoPermission ->
            existing
                ?.takeIf { it.loadState is ScreenLoadState.Content }
                ?.withRefreshFailure(result.blocker.code)
                ?: result.toTasksUiState(null).copy(refreshing = false)
    }
}

fun List<TaskSummary>.toTasksUiStateForThreadTasks(): TasksUiState =
    TasksUiState(
        loadState =
            if (isEmpty()) {
                ScreenLoadState.Empty(EmptyReason.NoTasks)
            } else {
                ScreenLoadState.Content(
                    TasksContent(
                        summaryCounts = summaryCounts(),
                        tasks = this
                    )
                )
            }
    )

fun TasksContent.boardColumns(renderLimit: Int): List<TasksBoardColumn> {
    val tasksByStatus = tasks.groupBy { it.status }
    return TaskStatus.entries.map { status ->
        val statusTasks = tasksByStatus[status].orEmpty()
        TasksBoardColumn(
            status = status,
            totalCount = statusTasks.size,
            tasks = if (renderLimit > 0) statusTasks.take(renderLimit) else statusTasks
        )
    }
}

fun TaskSummary.tasksSourceLabel(): String? =
    channelName?.takeIf { it.isNotBlank() }?.let { "#$it" }

fun TaskSummary.tasksDisplayTitle(): String {
    val numberLabel = taskNumber?.let { "#$it" }
    val meaningfulTitle = title.tasksMeaningfulTitle(taskNumber)
    return when {
        numberLabel != null && meaningfulTitle != null -> "$numberLabel $meaningfulTitle"
        numberLabel != null -> "Task $numberLabel"
        meaningfulTitle != null -> meaningfulTitle
        else -> "Task"
    }
}

fun TaskSummary.tasksAssigneeDisplayName(): String? =
    assignee?.displayName
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.takeUnless { it.isUuidLike() }
        ?.takeUnless { assignee?.id?.value == it }

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

fun List<TaskSummary>.summaryCounts(): Map<TaskStatus, Int> =
    TaskStatus.entries.associateWith { status -> count { it.status == status } }

private fun String.tasksMeaningfulTitle(taskNumber: Int?): String? {
    val trimmed = trim()
    if (trimmed.isBlank()) return null
    val number = taskNumber ?: return trimmed
    val withoutNumberPrefix = trimmed.withoutTaskNumberPrefix(number)
    if (withoutNumberPrefix.isNotBlank() && withoutNumberPrefix != trimmed) return withoutNumberPrefix
    val placeholders =
        setOf(
            "#$number",
            number.toString(),
            "task #$number",
            "task $number"
        )
    return trimmed.takeUnless { it.lowercase() in placeholders }
}

private fun String.withoutTaskNumberPrefix(number: Int): String {
    var index = 0
    index = skipWhitespace(index)
    val lower = lowercase()
    if (lower.startsWith("task", index)) {
        index += "task".length
        index = skipWhitespace(index)
    }
    if (getOrNull(index) == '#') {
        index += 1
    }
    val numberStart = index
    while (index < length && this[index].isDigit()) {
        index += 1
    }
    if (numberStart == index) return trim()
    if (substring(numberStart, index).toIntOrNull() != number) return trim()
    index = skipWhitespace(index)
    if (getOrNull(index) in setOf('-', ':', '：', ',', '，')) {
        index += 1
    }
    index = skipWhitespace(index)
    return substring(index).trim()
}

private fun String.skipWhitespace(start: Int): Int {
    var index = start
    while (index < length && this[index].isWhitespace()) {
        index += 1
    }
    return index
}

private fun String.isUuidLike(): Boolean {
    val trimmed = trim()
    if (trimmed.length != 36) return false
    val dashPositions = setOf(8, 13, 18, 23)
    return trimmed.withIndex().all { (index, char) ->
        if (index in dashPositions) {
            char == '-'
        } else {
            char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
        }
    }
}
