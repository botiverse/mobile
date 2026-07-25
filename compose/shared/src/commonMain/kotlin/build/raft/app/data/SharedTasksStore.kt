package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import com.tencent.kuikly.lifecycle.ViewModel
import com.tencent.kuikly.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.time.TimeMark
import kotlin.time.TimeSource

data class TasksUiState(
    val loadState: ScreenLoadState<TasksContent>,
    val selectedFilter: TaskStatus? = null,
    val availableFilters: List<TaskStatus> = TaskStatus.entries,
    val navigationErrorCode: String? = null,
    val refreshing: Boolean = false,
    val statusUpdatingTaskId: String? = null,
    val statusUpdateErrorCode: String? = null,
    val createPendingChannelId: String? = null,
    val createErrorCode: String? = null,
    val createAttempt: Long = 0L,
    val createTerminalAttempt: Long? = null
)

fun TasksUiState.isCreatingTasksFor(channelId: ChannelId): Boolean =
    createPendingChannelId == channelId.value

data class TasksCreateOperationId(
    val attempt: Long,
    val channelId: ChannelId
)

fun TasksUiState.ownsCreateOperation(operation: TasksCreateOperationId): Boolean =
    createAttempt == operation.attempt && createPendingChannelId == operation.channelId.value

data class TasksContent(
    val summaryCounts: Map<TaskStatus, Int>,
    val tasks: List<TaskSummary>
)

enum class TasksViewMode {
    Board,
    List
}

/**
 * UI-only state for the Tasks screen. Lives next to [TasksUiState] but holds purely
 * presentational concerns that don't survive process death and don't round-trip to the
 * API: which chip popover is open, what's typed into a popover search box, what columns
 * the user collapsed, which assignee chips are checked.
 *
 * The collapsed-by-default for `done` / `closed` mirrors the React panel — visual focus
 * stays on active work; users can expand the terminal states explicitly. Initialized via
 * [TasksPresentationState.Default] (companion factory) so the defaults are tested once.
 */
data class TasksPresentationState(
    val selectedChannelNames: Set<String> = emptySet(),
    val channelSelectorExpanded: Boolean = false,
    val selectedMode: TasksViewMode = TasksViewMode.Board,
    val collapsedStatuses: Set<TaskStatus> = setOf(TaskStatus.Done, TaskStatus.Closed),
    val selectedCreatorIds: Set<String> = emptySet(),
    val selectedAssigneeIds: Set<String> = emptySet(),
    val openFilterId: String? = null,
    val channelSearch: String = "",
    val creatorSearch: String = "",
    val assigneeSearch: String = ""
) {
    companion object {
        val Default = TasksPresentationState()
    }
}

data class TasksVisibleContent(
    val channelOptions: List<String>,
    val selectedChannelNames: Set<String>,
    val creatorOptions: List<MemberProfile>,
    val selectedCreatorIds: Set<String>,
    val assigneeOptions: List<MemberProfile>,
    val selectedAssigneeIds: Set<String>,
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
    data class SeedContent(val snapshot: TaskSnapshot, val selectedFilter: TaskStatus? = null) : SharedTasksIntent
    data class StartLoading(val filter: TaskStatus? = null) : SharedTasksIntent
    data class RefreshFailed(val errorCode: String) : SharedTasksIntent
    data class UpdateTaskStatus(val taskId: String, val nextStatus: TaskStatus) : SharedTasksIntent
    data class TaskStatusUpdateSucceeded(val task: TaskSummary) : SharedTasksIntent
    data class TaskStatusUpdateFailed(val previousTask: TaskSummary, val errorCode: String) : SharedTasksIntent
    data class NavigationFailed(val errorCode: String?) : SharedTasksIntent
    data class CreateTasksStarted(val operation: TasksCreateOperationId) : SharedTasksIntent
    data class CreateTasksSucceeded(
        val operation: TasksCreateOperationId,
        val snapshot: TaskSnapshot
    ) : SharedTasksIntent
    data class CreateTasksFailed(
        val operation: TasksCreateOperationId,
        val errorCode: String
    ) : SharedTasksIntent
}

sealed interface SharedTasksRouteIntent {
    data object Start : SharedTasksRouteIntent
    data object StartBackground : SharedTasksRouteIntent
    data object Refresh : SharedTasksRouteIntent
    data class SelectFilter(val status: TaskStatus?) : SharedTasksRouteIntent
    data class UpdateStatus(val task: TaskSummary, val status: TaskStatus) : SharedTasksRouteIntent
    data class ApplyTaskStatus(val taskId: String, val status: TaskStatus) : SharedTasksRouteIntent
    data class ApplyTaskUpdate(val task: TaskSummary) : SharedTasksRouteIntent
    data object ClearNavigationError : SharedTasksRouteIntent
    data class SeedContent(val snapshot: TaskSnapshot) : SharedTasksRouteIntent

    /**
     * Channel-scoped task creation. The host is responsible for providing the
     * [ChannelId] context — the server-wide tasks screen has no implicit channel and
     * does not expose a create CTA, so this intent is only ever dispatched from a
     * channel-mode panel.
     */
    data class CreateTasks(val channelId: ChannelId, val titles: List<String>) : SharedTasksRouteIntent
}

sealed interface SharedTasksEvent {
    data class OperationFailed(val code: String) : SharedTasksEvent
}

sealed interface TasksPresentationIntent {
    data class SetChannelSelectorExpanded(val expanded: Boolean) : TasksPresentationIntent
    data class ToggleChannel(val channelName: String) : TasksPresentationIntent
    data object ClearChannels : TasksPresentationIntent
    data class SelectMode(val mode: TasksViewMode) : TasksPresentationIntent
    data class PruneUnavailableChannels(val availableChannels: Set<String>) : TasksPresentationIntent
    data class ToggleStatusCollapsed(val status: TaskStatus) : TasksPresentationIntent
    data class ToggleCreator(val memberId: String) : TasksPresentationIntent
    data object ClearCreators : TasksPresentationIntent
    data class PruneUnavailableCreators(val availableMemberIds: Set<String>) : TasksPresentationIntent
    data class ToggleAssignee(val memberId: String) : TasksPresentationIntent
    data object ClearAssignees : TasksPresentationIntent
    data class PruneUnavailableAssignees(val availableMemberIds: Set<String>) : TasksPresentationIntent
    data class SetOpenFilter(val filterId: String?) : TasksPresentationIntent
    data class SetChannelSearch(val text: String) : TasksPresentationIntent
    data class SetCreatorSearch(val text: String) : TasksPresentationIntent
    data class SetAssigneeSearch(val text: String) : TasksPresentationIntent
    data object ClearAllFilters : TasksPresentationIntent
}

internal class SharedTasksViewModel(
    authStore: SlockAuthSessionStore,
    dataSource: SharedTasksDataSource,
    private val scope: SharedTasksScope = SharedTasksScope.Server,
    private val sharedTasksDataStore: SharedTasksDataStore? = null,
    private val diagnosticsLogger: (String) -> Unit = {},
    private val staleWindowMs: Long = 15_000L,
    private val runtimeScopeOverride: CoroutineScope? = null,
    private val useCase: SharedTasksUseCase =
        SharedTasksUseCase(
            authStore = authStore,
            repository = SharedTasksDataRepository(remoteDataSource = dataSource),
            scope = scope
        )
) : ViewModel(),
    SharedStateHolder<TasksUiState, SharedTasksRouteIntent, SharedTasksEvent> {
    private val runtimeScope: CoroutineScope
        get() = runtimeScopeOverride ?: viewModelScope

    private val store by lazy {
        SharedTasksStore(scope = runtimeScope)
    }
    private val events = MviEffectEmitter<SharedTasksEvent>()

    override val uiState: StateFlow<TasksUiState>
        get() = store.state

    override val uiEvents: Flow<SharedTasksEvent>
        get() = events.effects

    val state: StateFlow<TasksUiState>
        get() = uiState

    private var loadJob: Job? = null
    private var loadJobPriority: DataRequestPriority? = null
    private var observeJob: Job? = null
    private var statusJob: Job? = null
    private var lastRefreshCompletedAt: TimeMark? = null

    private var createJob: Job? = null

    override fun dispatch(action: SharedTasksRouteIntent) {
        when (action) {
            SharedTasksRouteIntent.Start,
            SharedTasksRouteIntent.StartBackground,
            SharedTasksRouteIntent.Refresh,
            is SharedTasksRouteIntent.SelectFilter ->
                load(action)
            is SharedTasksRouteIntent.UpdateStatus -> {
                val skipReason = state.value.taskStatusUpdateSkipReason(action.task, action.status)
                if (skipReason != null) {
                    diagnosticsLogger(
                        "tasks_status_update_skip reason=${skipReason.logValue} " +
                            "taskId=${action.task.id.value} taskStatus=${action.task.status.name} " +
                            "requestedStatus=${action.status.name}"
                    )
                    return
                }
                statusJob?.cancel()
                statusJob =
                    runtimeScope.launch {
                        updateStatus(action.task, action.status)
                    }
            }
            is SharedTasksRouteIntent.ApplyTaskStatus -> {
                if (store.accept(SharedTasksIntent.UpdateTaskStatus(action.taskId, action.status))) {
                    state.value.currentTaskSummary(action.taskId)?.let { task ->
                        runtimeScope.launch {
                            publishSharedTaskUpdate(task, "external_task_status")
                        }
                    }
                }
            }
            is SharedTasksRouteIntent.ApplyTaskUpdate -> {
                if (store.accept(SharedTasksIntent.TaskStatusUpdateSucceeded(action.task))) {
                    runtimeScope.launch {
                        publishSharedTaskUpdate(action.task, "external_task_update")
                    }
                }
            }
            is SharedTasksRouteIntent.CreateTasks -> {
                val cleanedTitles = action.titles.map { it.trim() }.filter { it.isNotBlank() }
                if (cleanedTitles.isEmpty()) return
                val operation =
                    TasksCreateOperationId(
                        attempt = state.value.createAttempt + 1L,
                        channelId = action.channelId
                    )
                createJob?.cancel()
                store.accept(SharedTasksIntent.CreateTasksStarted(operation))
                createJob =
                    runtimeScope.launch {
                        createTasks(operation, cleanedTitles)
                    }
            }
            SharedTasksRouteIntent.ClearNavigationError ->
                store.dispatch(SharedTasksIntent.NavigationFailed(null))
            is SharedTasksRouteIntent.SeedContent -> {
                if (store.accept(SharedTasksIntent.SeedContent(action.snapshot, state.value.selectedFilter))) {
                    runtimeScope.launch {
                        action.snapshot.tasks.forEach { task ->
                            publishSharedTaskUpdate(task, "seed")
                        }
                    }
                }
            }
        }
    }

    fun applyRealtimeTaskUpdate(task: TaskSummary) {
        store.accept(SharedTasksIntent.TaskStatusUpdateSucceeded(task))
    }

    private fun load(intent: SharedTasksRouteIntent) {
        val selectedFilter =
            when (intent) {
                is SharedTasksRouteIntent.SelectFilter -> intent.status
                else -> state.value.selectedFilter
            }
        val request =
            TasksLoadRequest(
                filter = TaskFilter(statuses = selectedFilter?.let(::setOf) ?: emptySet()),
                priority =
                    if (intent == SharedTasksRouteIntent.StartBackground) {
                        DataRequestPriority.Background
                    } else {
                        DataRequestPriority.Visible
                    }
            )
        val currentState = state.value
        val hasSnapshot = currentState.hasCompletedTasksSnapshot()
        val reason = intent.tasksRefreshReason()
        val snapshotAgeMs = lastRefreshCompletedAt?.elapsedNow()?.inWholeMilliseconds
        diagnosticsLogger(
            "tasks_screen_enter reason=$reason hasSnapshot=$hasSnapshot " +
                "snapshotAgeMs=${snapshotAgeMs ?: -1L} filter=${selectedFilter?.name.orEmpty()}"
        )
        val forceRefresh = intent == SharedTasksRouteIntent.Refresh
        val inFlight = loadJob?.isActive == true
        val canUpgradeInFlight = inFlight && request.priority.canUpgradeTasksLoad(loadJobPriority)
        val skipReason =
            tasksRefreshSkipReason(
                inFlight = inFlight && !canUpgradeInFlight,
                hasSnapshot = hasSnapshot,
                snapshotAgeMs = snapshotAgeMs,
                staleWindowMs = staleWindowMs,
                forceRefresh = forceRefresh
            )
        if ((intent == SharedTasksRouteIntent.Start || intent == SharedTasksRouteIntent.StartBackground) && skipReason != null) {
            logTasksRefreshSkip(reason, skipReason, hasSnapshot, snapshotAgeMs, selectedFilter)
            return
        }
        observeTasks(request, selectedFilter)
        when (skipReason) {
            TasksRefreshSkipReason.InFlight -> {
                logTasksRefreshSkip(reason, skipReason, hasSnapshot, snapshotAgeMs, selectedFilter)
                return
            }
            TasksRefreshSkipReason.FreshSnapshot -> {
                logTasksRefreshSkip(reason, skipReason, hasSnapshot, snapshotAgeMs, selectedFilter)
                return
            }
            null -> Unit
        }
        if (canUpgradeInFlight) {
            diagnosticsLogger(
                "tasks_refresh_upgrade reason=$reason from=${loadJobPriority?.name.orEmpty()} " +
                    "to=${request.priority.name} hasSnapshot=$hasSnapshot filter=${selectedFilter?.name.orEmpty()}"
            )
            loadJob?.cancel()
        }
        store.accept(SharedTasksIntent.StartLoading(selectedFilter))
        loadJobPriority = request.priority
        loadJob =
            runtimeScope.launch {
                try {
                    refreshTasks(
                        request = request,
                        selectedFilter = selectedFilter,
                        reason = reason,
                        mergeResult = intent == SharedTasksRouteIntent.Refresh || hasSnapshot,
                        forceSharedRefresh = forceRefresh
                    )
                } finally {
                    if (loadJobPriority == request.priority) {
                        loadJobPriority = null
                    }
                }
            }
    }

    private suspend fun refreshTasks(
        request: TasksLoadRequest,
        selectedFilter: TaskStatus?,
        reason: String,
        mergeResult: Boolean,
        forceSharedRefresh: Boolean
    ) {
        val startedAt = TimeSource.Monotonic.markNow()
        diagnosticsLogger(
            "tasks_refresh_start reason=$reason filter=${selectedFilter?.name.orEmpty()} " +
                "hasSnapshot=${state.value.hasCompletedTasksSnapshot()} priority=${request.priority.name}"
        )
        try {
            val sharedStore = sharedTasksDataStore
            val sharedServerId =
                if (sharedStore != null && request.filter.statuses.isEmpty()) {
                    useCase.currentServerId()
                } else {
                    null
                }
            val usesSharedSnapshotLoad = sharedStore != null && sharedServerId != null
            val result =
                if (sharedStore != null && sharedServerId != null) {
                    sharedStore.loadSnapshot(
                        serverId = sharedServerId.value,
                        scope = scope,
                        forceRefresh = forceSharedRefresh
                    ) {
                        useCase.refreshTasks(request)
                    }
                } else {
                    useCase.refreshTasks(request)
                }
            val durationMs = startedAt.elapsedNow().inWholeMilliseconds
            diagnosticsLogger(
                "tasks_refresh_result reason=$reason ${result.tasksDiagnosticsSummary()} durationMs=$durationMs"
            )
            if (result.isCompletedTasksSnapshot()) {
                lastRefreshCompletedAt = TimeSource.Monotonic.markNow()
            }
            if (result is DataResult.Content) {
                if (usesSharedSnapshotLoad) {
                    if (store.accept(SharedTasksIntent.SetResult(result, selectedFilter))) {
                        diagnosticsLogger(
                            "tasks_snapshot_commit source=shared_load_result ${result.tasksDiagnosticsSummary()}"
                        )
                    }
                } else {
                    if (store.accept(SharedTasksIntent.SetResult(result, selectedFilter))) {
                        diagnosticsLogger(
                            "tasks_snapshot_commit source=refresh_result ${result.tasksDiagnosticsSummary()}"
                        )
                    }
                }
            } else {
                result.failureCodeOrNull()?.let { events.emit(SharedTasksEvent.OperationFailed(it)) }
                store.accept(
                    if (mergeResult || state.value.loadState is ScreenLoadState.Content) {
                        SharedTasksIntent.MergeRefreshResult(result, selectedFilter)
                    } else {
                        SharedTasksIntent.SetResult(result, selectedFilter)
                    }
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            diagnosticsLogger("tasks_refresh_result reason=$reason result=exception code=tasks_network")
            events.emit(SharedTasksEvent.OperationFailed("tasks_network"))
            store.accept(SharedTasksIntent.RefreshFailed("tasks_network"))
        }
    }

    private suspend fun publishSharedTaskUpdate(task: TaskSummary, source: String) {
        val sharedStore = sharedTasksDataStore ?: return
        val serverId = useCase.currentServerId() ?: return
        sharedStore.upsertTask(serverId.value, task)
        diagnosticsLogger(
            "tasks_projection_upsert source=$source taskId=${task.id.value} status=${task.status.name}"
        )
    }

    private fun logTasksRefreshSkip(
        reason: String,
        skipReason: TasksRefreshSkipReason,
        hasSnapshot: Boolean,
        snapshotAgeMs: Long?,
        selectedFilter: TaskStatus?
    ) {
        when (skipReason) {
            TasksRefreshSkipReason.InFlight ->
                diagnosticsLogger(
                    "tasks_refresh_skip reason=$reason skip=in_flight hasSnapshot=$hasSnapshot " +
                        "filter=${selectedFilter?.name.orEmpty()}"
                )
            TasksRefreshSkipReason.FreshSnapshot ->
                diagnosticsLogger(
                    "tasks_refresh_skip reason=$reason skip=fresh_snapshot hasSnapshot=true " +
                        "snapshotAgeMs=$snapshotAgeMs filter=${selectedFilter?.name.orEmpty()}"
                )
        }
    }

    private fun observeTasks(request: TasksLoadRequest, selectedFilter: TaskStatus?) {
        observeJob?.cancel()
        observeJob =
            runtimeScope.launch {
                val sharedStore = sharedTasksDataStore
                val serverId = useCase.currentServerId()
                if (sharedStore != null && serverId != null) {
                    sharedStore.projectionFlow(serverId.value, scope).collect { slice ->
                        val result = slice.toTasksDataResultOrNull(request.filter) ?: return@collect
                        if (store.accept(SharedTasksIntent.SetResult(result, selectedFilter))) {
                            diagnosticsLogger("tasks_snapshot_commit source=shared_projection ${result.tasksDiagnosticsSummary()}")
                        }
                    }
                } else {
                    useCase.observeTasks(request).collect { result ->
                        if (store.accept(SharedTasksIntent.SetResult(result, selectedFilter))) {
                            diagnosticsLogger("tasks_snapshot_commit source=local_observe ${result.tasksDiagnosticsSummary()}")
                        }
                    }
                }
            }
    }

    private suspend fun updateStatus(task: TaskSummary, status: TaskStatus) {
        val skipReason = state.value.taskStatusUpdateSkipReason(task, status)
        if (skipReason != null) {
            state.value.currentTaskSummary(task.id.value)?.let { currentTask ->
                store.accept(SharedTasksIntent.TaskStatusUpdateSucceeded(currentTask))
            }
            diagnosticsLogger(
                "tasks_status_update_skip reason=${skipReason.logValue} " +
                    "taskId=${task.id.value} taskStatus=${task.status.name} requestedStatus=${status.name}"
            )
            return
        }
        if (store.accept(SharedTasksIntent.UpdateTaskStatus(task.id.value, status))) {
            val optimisticTask =
                state.value.currentTaskSummary(task.id.value)?.copy(status = status)
                    ?: task.copy(status = status)
            publishSharedTaskUpdate(optimisticTask, "status_optimistic")
        }
        try {
            when (val result = useCase.updateTaskStatus(TasksStatusUpdateRequest(task.id, status))) {
                is DataResult.Content -> {
                    publishSharedTaskUpdate(result.data.task, "status_result")
                    store.accept(SharedTasksIntent.TaskStatusUpdateSucceeded(result.data.task))
                }
                else -> {
                    val errorCode = result.failureCodeOrNull() ?: "tasks_status_unknown"
                    if (
                        errorCode == status.taskStatusAlreadyAppliedErrorCode() ||
                        errorCode == TASKS_STATUS_APPLIED_WITHOUT_PROJECTION
                    ) {
                        val acceptedTask =
                            state.value.currentTaskSummary(task.id.value)?.copy(status = status)
                                ?: task.copy(status = status)
                        val source =
                            if (errorCode == TASKS_STATUS_APPLIED_WITHOUT_PROJECTION) {
                                "status_accepted_without_projection"
                            } else {
                                "status_idempotent"
                            }
                        publishSharedTaskUpdate(acceptedTask, source)
                        store.accept(SharedTasksIntent.TaskStatusUpdateSucceeded(acceptedTask))
                        diagnosticsLogger(
                            "tasks_status_update_accepted taskId=${task.id.value} status=${status.name} " +
                                "serverCode=$errorCode"
                        )
                    } else {
                        events.emit(SharedTasksEvent.OperationFailed(errorCode))
                        if (store.accept(
                            SharedTasksIntent.TaskStatusUpdateFailed(
                                previousTask = task,
                                errorCode = errorCode
                            )
                        )) {
                            publishSharedTaskUpdate(task, "status_rollback")
                        }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            events.emit(SharedTasksEvent.OperationFailed("tasks_status_network"))
            if (store.accept(SharedTasksIntent.TaskStatusUpdateFailed(task, "tasks_status_network"))) {
                publishSharedTaskUpdate(task, "status_rollback")
            }
        }
    }

    private suspend fun createTasks(operation: TasksCreateOperationId, titles: List<String>) {
        try {
            when (val result = useCase.createTasks(TasksCreateRequest(operation.channelId, titles))) {
                is DataResult.Content -> {
                    if (store.accept(SharedTasksIntent.CreateTasksSucceeded(operation, result.data))) {
                        result.data.tasks.forEach { task ->
                            publishSharedTaskUpdate(task, "create_result")
                        }
                        // Re-fetch the active scope so the board/list reflects server-side
                        // canonical ordering and any derived counts after create.
                        load(SharedTasksRouteIntent.Refresh)
                    }
                }
                else -> {
                    val errorCode = result.failureCodeOrNull() ?: "tasks_create_unknown"
                    if (store.accept(SharedTasksIntent.CreateTasksFailed(operation, errorCode))) {
                        events.emit(SharedTasksEvent.OperationFailed(errorCode))
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            val errorCode = "tasks_create_network"
            if (store.accept(SharedTasksIntent.CreateTasksFailed(operation, errorCode))) {
                events.emit(SharedTasksEvent.OperationFailed(errorCode))
            }
        }
    }

    override fun onCleared() {
        loadJob?.cancel()
        observeJob?.cancel()
        statusJob?.cancel()
        createJob?.cancel()
        super.onCleared()
    }
}

internal enum class TasksRefreshSkipReason {
    InFlight,
    FreshSnapshot
}

internal fun DataRequestPriority.canUpgradeTasksLoad(activePriority: DataRequestPriority?): Boolean =
    activePriority == DataRequestPriority.Background && this != DataRequestPriority.Background

private fun SharedTasksScope.tasksScopeDiagnostics(): String =
    when (this) {
        SharedTasksScope.Server -> "server"
        is SharedTasksScope.Channel -> "channel:${channelId.value}"
    }

internal fun tasksRefreshSkipReason(
    inFlight: Boolean,
    hasSnapshot: Boolean,
    snapshotAgeMs: Long?,
    staleWindowMs: Long,
    forceRefresh: Boolean
): TasksRefreshSkipReason? {
    if (inFlight) return TasksRefreshSkipReason.InFlight
    if (!forceRefresh && hasSnapshot && snapshotAgeMs != null && snapshotAgeMs in 0 until staleWindowMs) {
        return TasksRefreshSkipReason.FreshSnapshot
    }
    return null
}

internal fun TasksUiState.hasCompletedTasksSnapshot(): Boolean =
    loadState is ScreenLoadState.Content || loadState is ScreenLoadState.Empty

internal fun DataResult<TaskSnapshot>.isCompletedTasksSnapshot(): Boolean =
    this is DataResult.Content || this is DataResult.Empty

private fun SharedTasksRouteIntent.tasksRefreshReason(): String =
    when (this) {
        SharedTasksRouteIntent.Start -> "start"
        SharedTasksRouteIntent.StartBackground -> "background_start"
        SharedTasksRouteIntent.Refresh -> "manual"
        is SharedTasksRouteIntent.SelectFilter -> "filter"
        is SharedTasksRouteIntent.UpdateStatus -> "status"
        is SharedTasksRouteIntent.ApplyTaskStatus -> "apply_task_status"
        is SharedTasksRouteIntent.ApplyTaskUpdate -> "apply_task_update"
        SharedTasksRouteIntent.ClearNavigationError -> "navigation"
        is SharedTasksRouteIntent.SeedContent -> "seed"
        is SharedTasksRouteIntent.CreateTasks -> "create"
    }

private fun DataResult<TaskSnapshot>.tasksDiagnosticsSummary(): String =
    when (this) {
        is DataResult.Content ->
            "result=content tasks=${data.tasks.size} counts=${data.summaryCounts.values.sum()}"
        is DataResult.Empty ->
            "result=empty code=${reason.name} tasks=0"
        is DataResult.Error ->
            "result=error code=${error.code} tasks=0"
        is DataResult.NoPermission ->
            "result=no_permission code=${blocker.code} tasks=0"
    }

class SharedTasksStore(
    initialState: TasksUiState = TasksUiState(ScreenLoadState.Loading),
    scope: CoroutineScope,
    transitionLogger: (MviTransition<SharedTasksIntent>) -> Unit = {}
) {
    private val runtime =
        MviStoreRuntime(
            initialState = initialState,
            scope = scope,
            reduce = { state, intent -> state.reduce(intent) },
            transitionLogger = transitionLogger
        )
    val state: StateFlow<TasksUiState> = runtime.state

    fun dispatch(intent: SharedTasksIntent) {
        runtime.dispatch(intent)
    }

    fun accept(intent: SharedTasksIntent): Boolean = runtime.accept(intent)
}

fun TasksUiState.reduce(intent: SharedTasksIntent): TasksUiState =
    when (intent) {
        SharedTasksIntent.StartLoadingCurrentFilter -> withLoading(selectedFilter)
        is SharedTasksIntent.SetResult ->
            intent.result
                .toTasksUiState(intent.selectedFilter)
                .withCreateOperationFrom(this)
        is SharedTasksIntent.MergeRefreshResult -> mergeTasksRefreshResult(intent.result, intent.selectedFilter)
        is SharedTasksIntent.SetContent ->
            copy(
                loadState = ScreenLoadState.Content(intent.snapshot.toTasksContent()),
                selectedFilter = intent.selectedFilter,
                refreshing = false
            )
        is SharedTasksIntent.SeedContent ->
            if (hasCompletedTasksSnapshot()) {
                this
            } else {
                copy(
                    loadState = ScreenLoadState.Content(intent.snapshot.toTasksContent()),
                    selectedFilter = intent.selectedFilter,
                    refreshing = false
                )
            }
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
        is SharedTasksIntent.CreateTasksStarted ->
            if (intent.operation.attempt <= createAttempt) {
                this
            } else {
                copy(
                    navigationErrorCode = null,
                    refreshing = true,
                    createPendingChannelId = intent.operation.channelId.value,
                    createErrorCode = null,
                    createAttempt = intent.operation.attempt,
                    createTerminalAttempt = null
                )
            }
        is SharedTasksIntent.CreateTasksSucceeded ->
            if (!ownsCreateOperation(intent.operation)) {
                this
            } else {
                copy(
                    refreshing = false,
                    navigationErrorCode = null,
                    createPendingChannelId = null,
                    createErrorCode = null,
                    createTerminalAttempt = intent.operation.attempt
                )
            }
        is SharedTasksIntent.CreateTasksFailed ->
            if (!ownsCreateOperation(intent.operation)) {
                this
            } else {
                copy(
                    refreshing = false,
                    navigationErrorCode = intent.errorCode,
                    createPendingChannelId = null,
                    createErrorCode = intent.errorCode,
                    createTerminalAttempt = intent.operation.attempt
                )
            }
    }

private fun TasksUiState.withCreateOperationFrom(previous: TasksUiState): TasksUiState =
    copy(
        createPendingChannelId = previous.createPendingChannelId,
        createErrorCode = previous.createErrorCode,
        createAttempt = previous.createAttempt,
        createTerminalAttempt = previous.createTerminalAttempt
    )

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
        is TasksPresentationIntent.ToggleStatusCollapsed ->
            copy(
                collapsedStatuses =
                    if (intent.status in collapsedStatuses) {
                        collapsedStatuses - intent.status
                    } else {
                        collapsedStatuses + intent.status
                    }
            )
        is TasksPresentationIntent.ToggleCreator ->
            copy(
                selectedCreatorIds =
                    if (intent.memberId in selectedCreatorIds) {
                        selectedCreatorIds - intent.memberId
                    } else {
                        selectedCreatorIds + intent.memberId
                    }
            )
        TasksPresentationIntent.ClearCreators -> copy(selectedCreatorIds = emptySet())
        is TasksPresentationIntent.PruneUnavailableCreators ->
            copy(selectedCreatorIds = selectedCreatorIds.filter { it in intent.availableMemberIds }.toSet())
        is TasksPresentationIntent.ToggleAssignee ->
            copy(
                selectedAssigneeIds =
                    if (intent.memberId in selectedAssigneeIds) {
                        selectedAssigneeIds - intent.memberId
                    } else {
                        selectedAssigneeIds + intent.memberId
                    }
            )
        TasksPresentationIntent.ClearAssignees -> copy(selectedAssigneeIds = emptySet())
        is TasksPresentationIntent.PruneUnavailableAssignees ->
            copy(selectedAssigneeIds = selectedAssigneeIds.filter { it in intent.availableMemberIds }.toSet())
        is TasksPresentationIntent.SetOpenFilter ->
            copy(
                openFilterId = intent.filterId,
                // Reset the relevant search box when the popover is being closed or swapped
                // so the next open does not reopen with stale text.
                channelSearch = if (intent.filterId == "channel") channelSearch else "",
                creatorSearch = if (intent.filterId == "creator") creatorSearch else "",
                assigneeSearch = if (intent.filterId == "assignee") assigneeSearch else ""
            )
        is TasksPresentationIntent.SetChannelSearch -> copy(channelSearch = intent.text)
        is TasksPresentationIntent.SetCreatorSearch -> copy(creatorSearch = intent.text)
        is TasksPresentationIntent.SetAssigneeSearch -> copy(assigneeSearch = intent.text)
        TasksPresentationIntent.ClearAllFilters ->
            copy(selectedChannelNames = emptySet(), selectedCreatorIds = emptySet(), selectedAssigneeIds = emptySet())
    }

fun TasksContent.visibleContent(
    selectedFilter: TaskStatus?,
    presentationState: TasksPresentationState,
    showChannelFilter: Boolean
): TasksVisibleContent {
    val channelOptions =
        if (showChannelFilter) {
            tasks
                .mapNotNull { it.channelName }
                .distinct()
                .sorted()
        } else {
            emptyList()
        }
    // Derive identity option pools from the loaded tasks so we don't need a separate
    // members fetch. Each profile that ever appears as a creator/claimer becomes a chip
    // option; identical identities collapse so popovers don't show duplicates. Sorted
    // by display name for stable ordering across refreshes.
    val creatorOptions =
        tasks
            .mapNotNull { it.creator }
            .distinctBy { it.tasksIdentityFilterId() }
            .sortedBy { it.displayName.lowercase() }
    val assigneeOptions =
        tasks
            .mapNotNull { it.assignee }
            .distinctBy { it.id.value }
            .sortedBy { it.displayName.lowercase() }
    val selectedChannels =
        if (showChannelFilter) {
            presentationState.selectedChannelNames.filter { it in channelOptions }.toSet()
        } else {
            emptySet()
        }
    val availableCreatorIds = creatorOptions.mapTo(mutableSetOf()) { it.tasksIdentityFilterId() }
    val selectedCreatorIds =
        presentationState.selectedCreatorIds.filter { it in availableCreatorIds }.toSet()
    val availableAssigneeIds = assigneeOptions.mapTo(mutableSetOf()) { it.id.value }
    val selectedAssigneeIds =
        presentationState.selectedAssigneeIds.filter { it in availableAssigneeIds }.toSet()
    val channelScopedTasks =
        if (selectedChannels.isEmpty()) {
            tasks
        } else {
            tasks.filter { task -> task.channelName in selectedChannels }
        }
    val channelScopedContent =
        if (channelScopedTasks === tasks) {
            this
        } else {
            copy(
                summaryCounts = channelScopedTasks.summaryCounts(),
                tasks = channelScopedTasks
            )
        }
    val creatorScopedTasks =
        if (selectedCreatorIds.isEmpty()) {
            channelScopedTasks
        } else {
            channelScopedTasks.filter { task -> task.creator?.tasksIdentityFilterId() in selectedCreatorIds }
        }
    val assigneeScopedTasks =
        if (selectedAssigneeIds.isEmpty()) {
            creatorScopedTasks
        } else {
            creatorScopedTasks.filter { task -> task.assignee?.id?.value in selectedAssigneeIds }
        }
    val filteredTasks =
        if (selectedFilter == null) {
            assigneeScopedTasks
        } else {
            assigneeScopedTasks.filter { task -> task.status == selectedFilter }
        }
    val filteredContent = channelScopedContent.copy(tasks = filteredTasks)
    return TasksVisibleContent(
        channelOptions = channelOptions,
        selectedChannelNames = selectedChannels,
        creatorOptions = creatorOptions,
        selectedCreatorIds = selectedCreatorIds,
        assigneeOptions = assigneeOptions,
        selectedAssigneeIds = selectedAssigneeIds,
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
        is DataResult.Content ->
            result
                .toTasksUiState(selectedFilter)
                .copy(refreshing = false)
                .withCreateOperationFrom(this)
        is DataResult.Empty ->
            takeIf { loadState is ScreenLoadState.Content }
                ?.copy(
                    selectedFilter = selectedFilter,
                    refreshing = false,
                    navigationErrorCode = result.reason.name
                )
                ?: result.toTasksUiState(selectedFilter).copy(refreshing = false).withCreateOperationFrom(this)
        is DataResult.Error ->
            takeIf { loadState is ScreenLoadState.Content }
                ?.copy(
                    selectedFilter = selectedFilter,
                    refreshing = false,
                    navigationErrorCode = result.error.code
                )
                ?: result.toTasksUiState(selectedFilter).copy(refreshing = false).withCreateOperationFrom(this)
        is DataResult.NoPermission ->
            takeIf { loadState is ScreenLoadState.Content }
                ?.copy(
                    selectedFilter = selectedFilter,
                    refreshing = false,
                    navigationErrorCode = result.blocker.code
                )
                ?: result.toTasksUiState(selectedFilter).copy(refreshing = false).withCreateOperationFrom(this)
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

fun TaskSummary.tasksAssigneeDisplayName(): String? {
    val profile = assignee ?: return null
    return profile.displayName
        .trim()
        .takeIf { it.isNotBlank() }
        ?.takeUnless { it.isUuidLike() }
        ?.takeUnless { profile.id.value == it }
}

internal fun MemberProfile.tasksIdentityFilterId(): String =
    "${kind.tasksIdentityFilterPrefix()}:${id.value}"

private fun MemberKind.tasksIdentityFilterPrefix(): String =
    when (this) {
        MemberKind.Agent -> "agent"
        MemberKind.Human -> "user"
        MemberKind.System -> "system"
    }

internal enum class TaskStatusUpdateSkipReason(val logValue: String) {
    AlreadyCurrent("already_current"),
    StaleTaskStatus("stale_task_status")
}

internal fun TasksUiState.taskStatusUpdateSkipReason(
    task: TaskSummary,
    requestedStatus: TaskStatus
): TaskStatusUpdateSkipReason? {
    val currentTask = currentTaskSummary(task.id.value) ?: return null
    return when {
        currentTask.status == requestedStatus -> TaskStatusUpdateSkipReason.AlreadyCurrent
        currentTask.status != task.status -> TaskStatusUpdateSkipReason.StaleTaskStatus
        else -> null
    }
}

internal fun TasksUiState.currentTaskSummary(taskId: String): TaskSummary? =
    (loadState as? ScreenLoadState.Content)
        ?.data
        ?.tasks
        ?.firstOrNull { it.matchesTaskKey(taskId) }

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

private fun SharedTasksProjectionSlice.toTasksDataResultOrNull(filter: TaskFilter): DataResult<TaskSnapshot>? {
    if (status != SharedTasksDataStoreStatus.Loaded) return null
    val filteredTasks =
        tasks.filter { task ->
            filter.statuses.isEmpty() || task.status in filter.statuses
        }
    return if (filteredTasks.isEmpty()) {
        DataResult.Empty(EmptyReason.NoTasks)
    } else {
        DataResult.Content(
            TaskSnapshot(
                tasks = filteredTasks,
                summaryCounts = filteredTasks.summaryCounts()
            )
        )
    }
}

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
            if (task.matchesTaskKey(taskId)) task.copy(status = status) else task
        }
    return copy(tasks = nextTasks, summaryCounts = nextTasks.summaryCounts())
}

private fun TaskSummary.matchesTaskKey(taskId: String): Boolean {
    val key = taskId.trim().takeIf { it.isNotBlank() } ?: return false
    return id.value == key ||
        messageId?.value == key ||
        taskNumber?.toString() == key
}

fun List<TaskSummary>.summaryCounts(): Map<TaskStatus, Int> =
    mutableMapOf<TaskStatus, Int>()
        .also { counts ->
            TaskStatus.entries.forEach { status -> counts[status] = 0 }
            forEach { task ->
                counts[task.status] = (counts[task.status] ?: 0) + 1
            }
        }

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
