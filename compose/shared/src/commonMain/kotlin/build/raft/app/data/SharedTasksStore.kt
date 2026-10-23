package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Page size for the paginated server Tasks read (task #345), matching Web. */
internal const val TASKS_SERVER_PAGE_LIMIT = 200

/**
 * Whether a paginated page fetched for [requestServerId] may still be applied
 * to the store: only when the request server is known and is STILL the current
 * server. A server switch while a page is in flight must discard the page
 * instead of folding it into the new server's projection (task #345). Pure so
 * the fence is unit-testable and mutation-demonstrable.
 */
internal fun tasksPageServerStillCurrent(requestServerId: String?, currentServerId: String?): Boolean =
    requestServerId != null && requestServerId == currentServerId

/**
 * Whether a paginated page still belongs to the store's current owner. The
 * owner identity is server + API base + principal, so this catches a
 * same-server logout/login that a server-ID-only fence would miss (task #345).
 * When the store has no owner provider (both null) it is treated as unchanged.
 */
internal fun tasksPageOwnerStillCurrent(requestOwnerIdentity: String?, currentOwnerIdentity: String?): Boolean =
    requestOwnerIdentity == currentOwnerIdentity

/**
 * Owner fence for an in-flight page request (task #345, round 5): the owner
 * identity AND the owner generation must both be unchanged.
 *
 * The generation is load-bearing: the same principal logging out and back in
 * restores an EQUAL identity string, so an identity-only fence would let a page
 * fetched before logout be folded into the new session. [SharedTasksDataStore.clearOwnerData]
 * bumps the generation, so the stale page is rejected.
 */
internal fun tasksPageOwnerSnapshotStillCurrent(
    request: SharedTasksOwnerSnapshot?,
    current: SharedTasksOwnerSnapshot?
): Boolean =
    request?.identity == current?.identity && request?.generation == current?.generation

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
    val createTerminalAttempt: Long? = null,
    // Pagination (task #345): the opaque server cursor for the next page and
    // whether one exists. `nextCursor == null` means the walk is exhausted.
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreErrorCode: String? = null,
    // Per-status pagination (task #363): independent cursor/hasMore per status
    // column. Consulted ONLY in per-status mode; the global triple above stays
    // the authoritative walk for the default (non-per-status) path, so #345's
    // behaviour is unchanged when this map is unused.
    val statusPages: Map<TaskStatus, TasksStatusPageState> = emptyMap()
)

/** One status column's independent pagination position (task #363). */
data class TasksStatusPageState(
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreErrorCode: String? = null
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

internal const val TasksUnassignedAssigneeFilterId = "unassigned"

internal fun tasksAvailableAssigneeFilterIds(assignees: List<MemberProfile>): Set<String> =
    assignees.mapTo(mutableSetOf(TasksUnassignedAssigneeFilterId)) { it.tasksIdentityFilterId() }

data class TasksBoardColumn(
    val status: TaskStatus,
    val totalCount: Int,
    val tasks: List<TaskSummary>
)

sealed interface SharedTasksIntent {
    data object StartLoadingCurrentFilter : SharedTasksIntent
    /**
     * A snapshot to show. [endsRefresh] marks the answer of the network refresh; a projection or
     * cache replay leaves `refreshing` as it is.
     */
    data class SetResult(
        val result: DataResult<TaskSnapshot>,
        val selectedFilter: TaskStatus? = null,
        val endsRefresh: Boolean = false
    ) : SharedTasksIntent
    data class MergeRefreshResult(val result: DataResult<TaskSnapshot>, val selectedFilter: TaskStatus? = null) : SharedTasksIntent
    data class SetContent(val snapshot: TaskSnapshot, val selectedFilter: TaskStatus? = null) : SharedTasksIntent
    data class SeedContent(val snapshot: TaskSnapshot, val selectedFilter: TaskStatus? = null) : SharedTasksIntent
    data class StartLoading(val filter: TaskStatus? = null) : SharedTasksIntent
    data class RefreshFailed(val errorCode: String) : SharedTasksIntent
    data class UpdateTaskStatus(val taskId: String, val nextStatus: TaskStatus) : SharedTasksIntent
    data class TaskStatusUpdateSucceeded(val task: TaskSummary) : SharedTasksIntent
    data class TaskStatusUpdateFailed(val previousTask: TaskSummary, val errorCode: String) : SharedTasksIntent
    data class TaskAssignStarted(val taskId: String, val assignee: MemberProfile?) : SharedTasksIntent
    data class TaskAssignSucceeded(val task: TaskSummary) : SharedTasksIntent
    data class TaskAssignFailed(val previousTask: TaskSummary, val errorCode: String) : SharedTasksIntent
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

    /** Pagination (task #345): request the next page of the current filter. */
    data object LoadMore : SharedTasksIntent

    /** Next page appended: [tasks] carry a server `nextCursor` (null = exhausted). */
    data class LoadMoreSucceeded(
        val tasks: List<TaskSummary>,
        val nextCursor: String?,
        val selectedFilter: TaskStatus? = null
    ) : SharedTasksIntent

    /** Next page failed: fail-open (keep existing items); [invalidCursor] asks the
     *  host to refresh from the head instead of retrying the same cursor. */
    data class LoadMoreFailed(
        val errorCode: String,
        val invalidCursor: Boolean = false
    ) : SharedTasksIntent

    /** Per-status pagination (task #363): request one more page for [status]. */
    data class LoadMoreStatus(val status: TaskStatus) : SharedTasksIntent

    /** Per-status seed (task #363): a column's FIRST page. Its rows and its
     *  cursor land together — publishing only the cursor would silently drop the
     *  rows and start the column at page 2 (@LoveArk's blocker). */
    data class SeedStatusPage(
        val status: TaskStatus,
        val tasks: List<TaskSummary>,
        val nextCursor: String?
    ) : SharedTasksIntent

    /** One status column's next page was appended (task #363). */
    data class LoadMoreStatusSucceeded(
        val status: TaskStatus,
        val tasks: List<TaskSummary>,
        val nextCursor: String?
    ) : SharedTasksIntent

    /** One status column's page failed (task #363): fail-open for THAT column only
     *  — other columns' cursors are untouched. */
    data class LoadMoreStatusFailed(
        val status: TaskStatus,
        val errorCode: String,
        val invalidCursor: Boolean = false
    ) : SharedTasksIntent
}

sealed interface SharedTasksRouteIntent {
    data object Start : SharedTasksRouteIntent
    data object StartBackground : SharedTasksRouteIntent
    data object Refresh : SharedTasksRouteIntent
    data class SelectFilter(val status: TaskStatus?) : SharedTasksRouteIntent
    data class UpdateStatus(val task: TaskSummary, val status: TaskStatus) : SharedTasksRouteIntent

    /** Assign (or, with `assignee == null`, unassign) a task from the task card. */
    data class AssignTask(val task: TaskSummary, val assignee: MemberProfile?) : SharedTasksRouteIntent
    data class ApplyTaskStatus(val taskId: String, val status: TaskStatus) : SharedTasksRouteIntent
    data class ApplyTaskUpdate(val task: TaskSummary) : SharedTasksRouteIntent
    data object ClearNavigationError : SharedTasksRouteIntent
    /** Pagination (task #345): follow the server cursor for the current filter. */
    data object LoadMore : SharedTasksRouteIntent
    /** Per-status pagination (task #363): follow ONE status column's cursor. */
    data class LoadMoreStatus(val status: TaskStatus) : SharedTasksRouteIntent
    /** The per-status Board became the active view: seed each column's first page
     *  on the next completed load. List mode never dispatches this, so the #345
     *  global path issues no extra requests. */
    data object EnterBoard : SharedTasksRouteIntent
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
    authStore: RaftAuthSessionStore,
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
    private var assignJob: Job? = null
    private var lastRefreshCompletedAt: TimeMark? = null
    private var canonicalRefetchRequested = false

    private var createJob: Job? = null

    private var loadMoreJob: Job? = null

    /** Per-status single-flight (task #363): one in-flight page per status column,
     *  mirroring the global [loadMoreJob] gate. Columns never block each other. */
    private val statusLoadJobs = mutableMapOf<TaskStatus, Job>()
    /** Single-flight for the per-status cursor seed (task #363). */
    private var statusSeedJob: Job? = null
    /** Set by the Board entering; consumed by the next completed load (task #363). */
    private var seedStatusPagesOnNext = false

    /**
     * Follow the server cursor for one more page (task #345). Single-flight and
     * fail-open: a transient failure keeps what is loaded; an invalid cursor
     * drops the cursor so the next entry refreshes from the head.
     */
    private fun loadMore() {
        val current = state.value
        val cursor = current.nextCursor
        // Server scope only: the paginated server read is the server Tasks panel.
        if (scope != SharedTasksScope.Server || !current.hasMore || cursor == null || loadMoreJob?.isActive == true) {
            return
        }
        loadMoreJob =
            runtimeScope.launch {
                diagnosticsLogger("tasks_load_more_start filter=${current.selectedFilter?.name.orEmpty()}")
                // Fence the request against a server switch: capture the request
                // server identity BEFORE the read, and re-verify it AFTER the
                // response. Without this, a page fetched from server A can be
                // folded into server B's store if the user switches mid-request,
                // polluting B's projection with A's rows.
                val requestServerId = useCase.currentServerId()?.value
                // Owner fence: the store is bound to an owner identity (server +
                // API base + principal). A same-server logout/login must also
                // discard an in-flight page, not just a server switch.
                val requestOwner = sharedTasksDataStore?.currentOwnerSnapshot()
                store.accept(SharedTasksIntent.LoadMore)
                val page = useCase.loadServerTasksPage(limit = TASKS_SERVER_PAGE_LIMIT, cursor = cursor)
                val currentServerId = useCase.currentServerId()?.value
                val currentOwner = sharedTasksDataStore?.currentOwnerSnapshot()
                val serverStillCurrent = tasksPageServerStillCurrent(requestServerId, currentServerId)
                // Fence on identity AND generation: an identity-only fence misses a
                // same-principal logout/login, whose identity string is restored.
                val ownerStillCurrent = tasksPageOwnerSnapshotStillCurrent(requestOwner, currentOwner)
                if (!serverStillCurrent || !ownerStillCurrent || requestServerId == null) {
                    // Server changed while the page was in flight: discard the
                    // page, clear the loading flag, and refresh from the new
                    // server's head.
                    diagnosticsLogger(
                        "tasks_load_more_discard reason=owner_or_server_changed requestServer=${requestServerId.orEmpty()} " +
                            "currentServer=${currentServerId.orEmpty()} ownerChanged=${requestOwner?.identity != currentOwner?.identity} " +
                            "generationChanged=${requestOwner?.generation != currentOwner?.generation}"
                    )
                    store.accept(SharedTasksIntent.LoadMoreFailed(errorCode = "server_changed", invalidCursor = true))
                    runtimeScope.launch { load(SharedTasksRouteIntent.Refresh) }
                    return@launch
                }
                when (page) {
                    is TasksPageResult.Page -> {
                        // In the shared-store server scope the authoritative source
                        // is the shared store's projection: fold each page row in
                        // first, so the long-lived projectionFlow includes it and
                        // other Tasks consumers see it. Appending only to the
                        // ViewModel's private state would be overwritten by the
                        // next projection emission and lost.
                        val sharedStore = sharedTasksDataStore
                        val sharedServerId = requestServerId
                        if (sharedStore != null) {
                            page.tasks.forEach { task ->
                                sharedStore.upsertTaskForCurrentServer(sharedServerId, task)
                            }
                            store.accept(
                                SharedTasksIntent.LoadMoreSucceeded(
                                    tasks = page.tasks,
                                    nextCursor = page.nextCursor,
                                    selectedFilter = state.value.selectedFilter
                                )
                            )
                        } else {
                            store.accept(
                                SharedTasksIntent.LoadMoreSucceeded(
                                    tasks = page.tasks,
                                    nextCursor = page.nextCursor,
                                    selectedFilter = state.value.selectedFilter
                                )
                            )
                        }
                    }
                    TasksPageResult.InvalidCursor ->
                        store.accept(SharedTasksIntent.LoadMoreFailed(errorCode = "invalid_cursor", invalidCursor = true))
                    is TasksPageResult.Error ->
                        store.accept(SharedTasksIntent.LoadMoreFailed(errorCode = page.code))
                }
            }
    }

    /**
     * Follow ONE status column's cursor for one more page (task #363). Mirrors
     * [loadMore] exactly — same owner/server fence, same fail-open semantics —
     * but is scoped to a single status: it reads/writes only `statusPages[status]`
     * and its own single-flight job, so concurrent columns cannot interfere and a
     * stale page from one column can never advance another's cursor.
     */
    private fun loadMoreStatus(status: TaskStatus) {
        val current = state.value
        val page = current.statusPages[status] ?: TasksStatusPageState()
        val cursor = page.nextCursor
        if (scope != SharedTasksScope.Server || !page.hasMore || cursor == null ||
            page.loadingMore || statusLoadJobs[status]?.isActive == true
        ) {
            return
        }
        statusLoadJobs[status] =
            runtimeScope.launch {
                diagnosticsLogger("tasks_load_more_status_start status=${status.name}")
                val requestServerId = useCase.currentServerId()?.value
                val requestOwner = sharedTasksDataStore?.currentOwnerSnapshot()
                store.accept(SharedTasksIntent.LoadMoreStatus(status))
                val result = useCase.loadServerTasksPage(
                    limit = TASKS_SERVER_PAGE_LIMIT,
                    cursor = cursor,
                    // The server filter uses the WIRE value (snake_case), not the
                    // enum name -- `InProgress` is not a valid server filter.
                    status = status.toWireValue()
                )
                val currentServerId = useCase.currentServerId()?.value
                val currentOwner = sharedTasksDataStore?.currentOwnerSnapshot()
                val serverStillCurrent = tasksPageServerStillCurrent(requestServerId, currentServerId)
                val ownerStillCurrent = tasksPageOwnerSnapshotStillCurrent(requestOwner, currentOwner)
                if (!serverStillCurrent || !ownerStillCurrent || requestServerId == null) {
                    diagnosticsLogger(
                        "tasks_load_more_status_discard status=${status.name} reason=owner_or_server_changed"
                    )
                    store.accept(
                        SharedTasksIntent.LoadMoreStatusFailed(
                            status = status,
                            errorCode = "server_changed",
                            invalidCursor = true
                        )
                    )
                    return@launch
                }
                when (result) {
                    is TasksPageResult.Page -> {
                        sharedTasksDataStore?.let { sharedStore ->
                            result.tasks.forEach { task ->
                                sharedStore.upsertTaskForCurrentServer(requestServerId, task)
                            }
                        }
                        store.accept(
                            SharedTasksIntent.LoadMoreStatusSucceeded(
                                status = status,
                                tasks = result.tasks,
                                nextCursor = result.nextCursor
                            )
                        )
                    }
                    TasksPageResult.InvalidCursor ->
                        store.accept(
                            SharedTasksIntent.LoadMoreStatusFailed(
                                status = status,
                                errorCode = "invalid_cursor",
                                invalidCursor = true
                            )
                        )
                    is TasksPageResult.Error ->
                        store.accept(
                            SharedTasksIntent.LoadMoreStatusFailed(status = status, errorCode = result.code)
                        )
                }
            }
    }

    /**
     * The statuses a Board seeds: EVERY column it renders, including one whose
     * first page is empty (@LoveArk). Seeding only the statuses present in the
     * loaded rows would leave an empty column with no page state, and its
     * sentinel would then fall back to the GLOBAL cursor — neither per-status nor
     * safe.
     */
    private fun boardSeedStatuses(): List<TaskStatus> =
        state.value.availableFilters.ifEmpty { TaskStatus.entries.toList() }.toList()

    /**
     * Seed every board column's own cursor (task #363). Called after a load that
     * used the per-status walk: the initial page for a column is what tells the
     * store whether that column has more pages at all — without it, a column's
     * first `onReachedEnd` would have no cursor to advance (and a column whose
     * first page is short must never fire).
     *
     * One page per status, sequential (cheap: the seed runs once per load, and
     * the columns are few). Any failure simply leaves that column unpublished
     * (hasMore=false) — fail-closed, matching the global path's behaviour.
     */
    private fun seedStatusPages(statuses: List<TaskStatus>) {
        if (scope != SharedTasksScope.Server || statuses.isEmpty()) return
        statusSeedJob?.cancel()
        statusSeedJob =
            runtimeScope.launch {
                val requestServerId = useCase.currentServerId()?.value ?: return@launch
                val requestOwner = sharedTasksDataStore?.currentOwnerSnapshot()
                for (status in statuses) {
                    val result = useCase.loadServerTasksPage(
                        limit = TASKS_SERVER_PAGE_LIMIT,
                        cursor = null,
                        status = status.toWireValue()
                    )
                    // Fence each seed like any other page: a server/owner change
                    // while seeding must not publish a stale column cursor.
                    val currentServerId = useCase.currentServerId()?.value
                    val currentOwner = sharedTasksDataStore?.currentOwnerSnapshot()
                    if (!tasksPageServerStillCurrent(requestServerId, currentServerId) ||
                        !tasksPageOwnerSnapshotStillCurrent(requestOwner, currentOwner)
                    ) {
                        return@launch
                    }
                    if (result is TasksPageResult.Page) {
                        // Fold the first page's rows into the shared projection
                        // (same as a page append) so they are visible and other
                        // Tasks consumers see them, then land rows+cursor.
                        sharedTasksDataStore?.let { sharedStore ->
                            result.tasks.forEach { task ->
                                sharedStore.upsertTaskForCurrentServer(requestServerId, task)
                            }
                        }
                        store.accept(
                            SharedTasksIntent.SeedStatusPage(
                                status = status,
                                tasks = result.tasks,
                                nextCursor = result.nextCursor
                            )
                        )
                    }
                }
            }
    }

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
            is SharedTasksRouteIntent.AssignTask -> {
                val skipReason = state.value.taskAssignUpdateSkipReason(action.task, action.assignee)
                if (skipReason != null) {
                    diagnosticsLogger(
                        "tasks_assign_update_skip reason=${skipReason.logValue} " +
                            "taskId=${action.task.id.value} requestedAssignee=${action.assignee?.id?.value ?: "null"}"
                    )
                    return
                }
                assignJob?.cancel()
                assignJob =
                    runtimeScope.launch {
                        assignTask(action.task, action.assignee)
                    }
            }
            is SharedTasksRouteIntent.ApplyTaskStatus -> {
                store.accept(SharedTasksIntent.UpdateTaskStatus(action.taskId, action.status))
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
            SharedTasksRouteIntent.LoadMore ->
                loadMore()
            is SharedTasksRouteIntent.LoadMoreStatus ->
                loadMoreStatus(action.status)
            SharedTasksRouteIntent.EnterBoard -> {
                seedStatusPagesOnNext = true
                // If content is already loaded (Board toggled after a List load),
                // seed now instead of waiting for an unrelated refresh.
                if (state.value.hasCompletedTasksSnapshot()) {
                    seedStatusPagesOnNext = false
                    seedStatusPages(boardSeedStatuses())
                }
            }
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

    fun applyRealtimeTaskUpdate(serverId: String, task: TaskSummary) {
        runtimeScope.launch {
            val currentServerId = useCase.currentServerId()?.value
            if (currentServerId != serverId) {
                diagnosticsLogger(
                    "tasks_realtime_reject reason=server_changed taskId=${task.id.value} " +
                        "eventServerId=$serverId currentServerId=${currentServerId.orEmpty()}"
                )
                return@launch
            }
            val sharedStore = sharedTasksDataStore
            if (sharedStore == null) {
                store.accept(SharedTasksIntent.TaskStatusUpdateSucceeded(task))
                return@launch
            }
            when (sharedStore.upsertTaskForCurrentServer(serverId, task)) {
                SharedTaskFactFoldOutcome.Accepted ->
                    store.accept(SharedTasksIntent.TaskStatusUpdateSucceeded(task))
                SharedTaskFactFoldOutcome.StaleIgnored ->
                    diagnosticsLogger("tasks_realtime_ignore reason=stale taskId=${task.id.value} serverId=$serverId")
                SharedTaskFactFoldOutcome.Conflict ->
                    diagnosticsLogger("tasks_realtime_ignore reason=conflict taskId=${task.id.value} serverId=$serverId")
                null ->
                    diagnosticsLogger("tasks_realtime_reject reason=owner_changed taskId=${task.id.value} serverId=$serverId")
            }
        }
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
            // Server Tasks panel reads the paginated summary page (task #345):
            // the legacy unpaginated full read is what produced the ~22.8 MiB
            // response. Use it for the unfiltered server scope only; filtered
            // reads keep the existing (bounded) path.
            // The server Tasks panel reads the paginated summary page (#345):
            // the unpaginated full read produced a ~22.8 MiB response. This
            // applies to BOTH the shared-snapshot path (the real production
            // wiring, always used when sharedTasksDataStore is present) and the
            // direct path — the fetch itself must be paginated, or the panel
            // silently falls back to the full read. Filtered reads keep the
            // existing (bounded) path.
            val usesPaginatedServerRead =
                scope == SharedTasksScope.Server && request.filter.statuses.isEmpty()
            // The fetch may run in a different coroutine (the shared store's
            // deferred snapshot load), so the page cursor is captured through a
            // CompletableDeferred rather than a plain local var.
            val firstPageCursorRef = kotlinx.coroutines.CompletableDeferred<String?>(null)
            val paginatedFetch: suspend () -> DataResult<TaskSnapshot> = {
                when (val page = useCase.loadServerTasksPage(limit = TASKS_SERVER_PAGE_LIMIT, cursor = null)) {
                    is TasksPageResult.Page -> {
                        firstPageCursorRef.complete(page.nextCursor)
                        DataResult.Content(TaskSnapshot(tasks = page.tasks, summaryCounts = page.tasks.summaryCounts()))
                    }
                    TasksPageResult.InvalidCursor -> DataResult.Error(ScreenError(code = "tasks_page_invalid_cursor", retryable = false))
                    is TasksPageResult.Error -> DataResult.Error(ScreenError(code = page.code, retryable = true))
                }
            }
            val result =
                if (sharedStore != null && sharedServerId != null) {
                    sharedStore.loadSnapshot(
                        serverId = sharedServerId.value,
                        scope = scope,
                        forceRefresh = forceSharedRefresh
                    ) {
                        if (usesPaginatedServerRead) paginatedFetch() else useCase.refreshTasks(request)
                    }
                } else if (usesPaginatedServerRead) {
                    paginatedFetch()
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
                    if (store.accept(SharedTasksIntent.SetResult(result, selectedFilter, endsRefresh = true))) {
                        diagnosticsLogger(
                            "tasks_snapshot_commit source=shared_load_result ${result.tasksDiagnosticsSummary()}"
                        )
                    }
                } else {
                    if (store.accept(SharedTasksIntent.SetResult(result, selectedFilter, endsRefresh = true))) {
                        diagnosticsLogger(
                            "tasks_snapshot_commit source=refresh_result ${result.tasksDiagnosticsSummary()}"
                        )
                    }
                }
                if (usesPaginatedServerRead) {
                    val firstPageCursor = firstPageCursorRef.await()
                    store.accept(
                        SharedTasksIntent.LoadMoreSucceeded(
                            tasks = emptyList(),
                            nextCursor = firstPageCursor,
                            selectedFilter = selectedFilter
                        )
                    )
                    // task #363: per-status seeding is driven by the ACTIVE view
                    // (see SharedTasksRouteIntent.EnterBoard), never by a
                    // construction flag — so List mode cannot seed.
                    if (seedStatusPagesOnNext) {
                        seedStatusPagesOnNext = false
                        seedStatusPages(boardSeedStatuses())
                    }
                }
            } else {
                result.failureCodeOrNull()?.let { events.emit(SharedTasksEvent.OperationFailed(it)) }
                store.accept(
                    if (mergeResult || state.value.loadState is ScreenLoadState.Content) {
                        SharedTasksIntent.MergeRefreshResult(result, selectedFilter)
                    } else {
                        SharedTasksIntent.SetResult(result, selectedFilter, endsRefresh = true)
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

    private suspend fun publishSharedTaskUpdate(
        task: TaskSummary,
        source: String,
        serverId: ServerId? = null
    ) {
        val sharedStore = sharedTasksDataStore ?: return
        val resolvedServerId = serverId ?: useCase.currentServerId() ?: return
        val outcome = sharedStore.upsertTaskForCurrentServer(resolvedServerId.value, task)
        if (outcome == null) {
            diagnosticsLogger(
                "tasks_projection_reject source=$source taskId=${task.id.value} serverId=${resolvedServerId.value}"
            )
            return
        }
        diagnosticsLogger(
            "tasks_projection_upsert source=$source taskId=${task.id.value} status=${task.status.name} " +
                "serverId=${resolvedServerId.value} outcome=${outcome.name}"
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
                        if (slice.needsCanonicalRefetch) {
                            if (!canonicalRefetchRequested) {
                                canonicalRefetchRequested = true
                                diagnosticsLogger("tasks_canonical_conflict_refetch serverId=${serverId.value}")
                                runtimeScope.launch { load(SharedTasksRouteIntent.Refresh) }
                            }
                            return@collect
                        }
                        canonicalRefetchRequested = false
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
        val mutationServerId = useCase.currentServerId()
        val sharedStore = sharedTasksDataStore
        val mutationPermit =
            if (sharedStore != null && mutationServerId != null) {
                sharedStore.beginStatusMutation(
                    serverId = mutationServerId.value,
                    scope = scope,
                    taskId = task.id,
                    expectedRevision = task.revision,
                    status = status
                )
            } else {
                null
            }
        val mutationTask =
            if (sharedStore == null) {
                task
            } else {
                mutationPermit?.previousTask
            }
        if (mutationServerId == null || mutationTask == null) {
            diagnosticsLogger(
                "tasks_status_update_reject reason=missing_local_projection taskId=${task.id.value} " +
                    "revision=${task.revision ?: -1L}"
            )
            events.emit(SharedTasksEvent.OperationFailed("tasks_local_projection_missing"))
            return
        }
        store.accept(SharedTasksIntent.UpdateTaskStatus(task.id.value, status))
        try {
            when (
                val result =
                    useCase.updateTaskStatus(
                        mutationServerId,
                        TasksStatusUpdateRequest(mutationTask.id, status)
                    )
            ) {
                is DataResult.Content -> {
                    val acceptedTask =
                        if (sharedStore == null) {
                            result.data.task
                        } else {
                            mutationPermit?.let { permit ->
                                sharedStore.completeStatusMutation(permit, result.data.task)
                            }
                        }
                    if (acceptedTask != null) {
                        store.accept(SharedTasksIntent.TaskStatusUpdateSucceeded(acceptedTask))
                    } else {
                        diagnosticsLogger(
                            "tasks_status_result_ignore reason=permit_superseded taskId=${task.id.value} " +
                                "serverId=${mutationServerId.value}"
                        )
                    }
                }
                else -> {
                    val errorCode = result.failureCodeOrNull() ?: "tasks_status_unknown"
                    if (
                        errorCode == status.taskStatusAlreadyAppliedErrorCode() ||
                        errorCode == TASKS_STATUS_APPLIED_WITHOUT_PROJECTION
                    ) {
                        val acceptedTask =
                            if (sharedStore == null) {
                                state.value.currentTaskSummary(task.id.value)?.copy(status = status)
                                    ?: mutationTask.copy(status = status)
                            } else {
                                mutationPermit?.let { permit ->
                                    sharedStore.completeStatusMutationWithoutProjection(permit)
                                }
                            }
                        if (acceptedTask != null) {
                            store.accept(SharedTasksIntent.TaskStatusUpdateSucceeded(acceptedTask))
                            if (sharedStore != null) {
                                runtimeScope.launch { load(SharedTasksRouteIntent.Refresh) }
                            }
                            diagnosticsLogger(
                                "tasks_status_update_accepted taskId=${task.id.value} status=${status.name} " +
                                    "serverCode=$errorCode"
                            )
                        } else {
                            diagnosticsLogger(
                                "tasks_status_result_ignore reason=permit_superseded taskId=${task.id.value} " +
                                    "serverId=${mutationServerId.value} serverCode=$errorCode"
                            )
                        }
                    } else {
                        val restoredTask =
                            if (sharedStore == null) {
                                mutationTask
                            } else {
                                mutationPermit?.let { permit -> sharedStore.rollbackStatusMutation(permit) }
                            }
                        if (restoredTask != null) {
                            events.emit(SharedTasksEvent.OperationFailed(errorCode))
                            store.accept(
                                SharedTasksIntent.TaskStatusUpdateFailed(
                                    previousTask = restoredTask,
                                    errorCode = errorCode
                                )
                            )
                        } else {
                            diagnosticsLogger(
                                "tasks_status_failure_ignore reason=permit_superseded taskId=${task.id.value} " +
                                    "serverId=${mutationServerId.value} errorCode=$errorCode"
                            )
                        }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            if (sharedStore != null && mutationPermit != null) {
                withContext(NonCancellable) {
                    sharedStore.rollbackStatusMutation(mutationPermit)
                }
            }
            throw cancellation
        } catch (_: Throwable) {
            val restoredTask =
                if (sharedStore == null) {
                    mutationTask
                } else {
                    mutationPermit?.let { permit -> sharedStore.rollbackStatusMutation(permit) }
                }
            if (restoredTask != null) {
                events.emit(SharedTasksEvent.OperationFailed("tasks_status_network"))
                store.accept(SharedTasksIntent.TaskStatusUpdateFailed(restoredTask, "tasks_status_network"))
            } else {
                diagnosticsLogger(
                    "tasks_status_failure_ignore reason=permit_superseded taskId=${task.id.value} " +
                        "serverId=${mutationServerId.value} errorCode=tasks_status_network"
                )
            }
        }
    }

    private suspend fun assignTask(task: TaskSummary, assignee: MemberProfile?) {
        val serverId = useCase.currentServerId()
        val sharedStore = sharedTasksDataStore
        val mutationPermit =
            if (sharedStore != null && serverId != null) {
                sharedStore.beginAssignMutation(
                    serverId = serverId.value,
                    scope = scope,
                    taskId = task.id,
                    expectedRevision = task.revision,
                    assignee = assignee
                )
            } else {
                null
            }
        val mutationTask =
            if (sharedStore == null) {
                task
            } else {
                mutationPermit?.previousTask
            }
        if (serverId == null || mutationTask == null) {
            diagnosticsLogger(
                "tasks_assign_reject reason=missing_local_projection taskId=${task.id.value} " +
                    "revision=${task.revision ?: -1L}"
            )
            events.emit(SharedTasksEvent.OperationFailed("tasks_local_projection_missing"))
            return
        }
        store.accept(SharedTasksIntent.TaskAssignStarted(mutationTask.id.value, assignee))
        try {
            when (
                val result =
                    useCase.assignTask(
                        serverId,
                        mutationTask.id,
                        assignee,
                        expectedRevision = mutationTask.revision
                    )
            ) {
                is DataResult.Content -> {
                    val acceptedTask =
                        if (sharedStore == null) {
                            result.data.task
                        } else {
                            mutationPermit?.let { permit ->
                                sharedStore.completeStatusMutation(permit, result.data.task)
                            }
                        }
                    if (acceptedTask != null) {
                        store.accept(SharedTasksIntent.TaskAssignSucceeded(acceptedTask))
                    } else {
                        diagnosticsLogger(
                            "tasks_assign_result_ignore reason=permit_superseded taskId=${task.id.value} " +
                                "serverId=${serverId.value}"
                        )
                    }
                }
                else -> {
                    val errorCode = result.failureCodeOrNull() ?: "tasks_assign_unknown"
                    val restoredTask =
                        if (sharedStore == null) {
                            mutationTask
                        } else {
                            mutationPermit?.let { permit -> sharedStore.rollbackStatusMutation(permit) }
                        }
                    if (restoredTask != null) {
                        events.emit(SharedTasksEvent.OperationFailed(errorCode))
                        store.accept(SharedTasksIntent.TaskAssignFailed(restoredTask, errorCode))
                    } else {
                        diagnosticsLogger(
                            "tasks_assign_failure_ignore reason=permit_superseded taskId=${task.id.value} " +
                                "serverId=${serverId.value} errorCode=$errorCode"
                        )
                    }
                    // Server contract: a lost OCC race means our projected revision is
                    // stale; re-read once so the next attempt carries a fresh token.
                    if (errorCode == TASKS_ASSIGN_CONFLICT) {
                        runtimeScope.launch { load(SharedTasksRouteIntent.Refresh) }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            if (sharedStore != null && mutationPermit != null) {
                withContext(NonCancellable) {
                    sharedStore.rollbackStatusMutation(mutationPermit)
                }
            }
            throw cancellation
        } catch (_: Throwable) {
            val restoredTask =
                if (sharedStore == null) {
                    mutationTask
                } else {
                    mutationPermit?.let { permit -> sharedStore.rollbackStatusMutation(permit) }
                }
            if (restoredTask != null) {
                events.emit(SharedTasksEvent.OperationFailed("tasks_assign_network"))
                store.accept(SharedTasksIntent.TaskAssignFailed(restoredTask, "tasks_assign_network"))
            } else {
                diagnosticsLogger(
                    "tasks_assign_failure_ignore reason=permit_superseded taskId=${task.id.value} " +
                        "serverId=${serverId.value} errorCode=tasks_assign_network"
                )
            }
        }
    }

    private suspend fun createTasks(operation: TasksCreateOperationId, titles: List<String>) {
        val serverId = useCase.currentServerId()
        val isLocalChannelScope = scope is SharedTasksScope.Channel && scope.channelId == operation.channelId
        if (serverId == null || !isLocalChannelScope) {
            val errorCode = "tasks_local_projection_missing"
            diagnosticsLogger(
                "tasks_create_reject reason=missing_local_projection channelId=${operation.channelId.value}"
            )
            if (store.accept(SharedTasksIntent.CreateTasksFailed(operation, errorCode))) {
                events.emit(SharedTasksEvent.OperationFailed(errorCode))
            }
            return
        }
        try {
            when (
                val result =
                    useCase.createTasks(
                        serverId,
                        TasksCreateRequest(operation.channelId, titles)
                    )
            ) {
                is DataResult.Content -> {
                    if (store.accept(SharedTasksIntent.CreateTasksSucceeded(operation, result.data))) {
                        result.data.tasks.forEach { task ->
                            publishSharedTaskUpdate(task, "create_result", serverId)
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
        loadMoreJob?.cancel()
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
        is SharedTasksRouteIntent.AssignTask -> "assign"
        is SharedTasksRouteIntent.ApplyTaskStatus -> "apply_task_status"
        is SharedTasksRouteIntent.ApplyTaskUpdate -> "apply_task_update"
        SharedTasksRouteIntent.LoadMore -> "load_more"
        is SharedTasksRouteIntent.LoadMoreStatus -> "load_more_status_${status.name}"
        SharedTasksRouteIntent.EnterBoard -> "enter_board"
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

                // Pagination position is owned by the walk (LoadMore), not by a
                // snapshot/projection emission: preserve it so a shared
                // projectionFlow emission does not silently reset the cursor.
                // Likewise `refreshing` belongs to the network refresh: every load re-subscribes
                // the projection, which replays the cached snapshot at once, and that replay must
                // not end the refresh it started (header spinner, pull-to-refresh indicator).
                // MergeRefreshResult / RefreshFailed end it.
                .copy(nextCursor = nextCursor, hasMore = hasMore, loadingMore = loadingMore, refreshing = refreshing && !intent.endsRefresh)
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
        is SharedTasksIntent.TaskAssignStarted ->
            withTaskAssignUpdating(taskId = intent.taskId, assignee = intent.assignee)
        is SharedTasksIntent.TaskAssignSucceeded -> withTaskStatusUpdateSuccess(intent.task)
        is SharedTasksIntent.TaskAssignFailed ->
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
        SharedTasksIntent.LoadMore ->
            if (!hasMore || loadingMore || nextCursor == null) {
                this
            } else {
                copy(loadingMore = true, loadMoreErrorCode = null)
            }
        is SharedTasksIntent.LoadMoreSucceeded ->
            withAppendedTasks(intent.tasks, intent.nextCursor)
                .copy(loadingMore = false, loadMoreErrorCode = null)
        is SharedTasksIntent.LoadMoreFailed ->
            // Fail-open: keep whatever is already loaded; only record the error
            // (and, for a stale cursor, drop it so the host refreshes the head).
            copy(
                loadingMore = false,
                loadMoreErrorCode = intent.errorCode,
                nextCursor = if (intent.invalidCursor) null else nextCursor,
                hasMore = if (intent.invalidCursor) false else hasMore
            )
        // ---- Per-status pagination (task #363) --------------------------------
        // Only this status' entry changes; the global triple and every OTHER
        // status' cursor are untouched, so columns cannot corrupt each other.
        is SharedTasksIntent.SeedStatusPage ->
            // Rows and cursor are ONE update: the column's first page becomes
            // visible and its cursor is where the NEXT page continues from.
            withAppendedStatusPage(intent.status, intent.tasks, intent.nextCursor)
        is SharedTasksIntent.LoadMoreStatus -> {
            val page = statusPages[intent.status] ?: TasksStatusPageState()
            if (!page.hasMore || page.loadingMore || page.nextCursor == null) {
                this
            } else {
                copy(statusPages = statusPages + (intent.status to page.copy(loadingMore = true, loadMoreErrorCode = null)))
            }
        }
        is SharedTasksIntent.LoadMoreStatusSucceeded ->
            withAppendedStatusPage(intent.status, intent.tasks, intent.nextCursor)
                .copy(
                    statusPages = (statusPages + (intent.status to
                        (statusPages[intent.status] ?: TasksStatusPageState()).copy(
                            loadingMore = false,
                            loadMoreErrorCode = null
                        )))
                )
        is SharedTasksIntent.LoadMoreStatusFailed -> {
            val page = statusPages[intent.status] ?: TasksStatusPageState()
            copy(
                statusPages = statusPages + (intent.status to page.copy(
                    loadingMore = false,
                    loadMoreErrorCode = intent.errorCode,
                    // A stale cursor drops ONLY this column's cursor.
                    nextCursor = if (intent.invalidCursor) null else page.nextCursor,
                    hasMore = if (intent.invalidCursor) false else page.hasMore
                ))
            )
        }
    }

/** Append one status column's page (task #363): the SAME merge-by-id semantics as
 *  [withAppendedTasks] (never drops loaded rows, no duplicate ids), but only this
 *  status' cursor is advanced — every other column is left alone. */
internal fun TasksUiState.withAppendedStatusPage(
    status: TaskStatus,
    incoming: List<TaskSummary>,
    nextCursor: String?
): TasksUiState {
    val page = statusPages[status] ?: TasksStatusPageState()
    val merged = if (incoming.isEmpty()) {
        this
    } else {
        val content = (loadState as? ScreenLoadState.Content)
        if (content == null) {
            this
        } else {
            val tasks = mergeTasksById(current = content.data.tasks, incoming = incoming)
            copy(
                loadState = ScreenLoadState.Content(
                    content.data.copy(tasks = tasks, summaryCounts = tasks.summaryCounts())
                )
            )
        }
    }
    return merged.copy(
        statusPages = merged.statusPages + (status to page.copy(
            nextCursor = nextCursor,
            hasMore = nextCursor != null
        ))
    )
}

/** Append a page of tasks, merging by id: existing rows are replaced in place
 *  (so an updated row does not duplicate), new rows are appended, and the
 *  currently loaded items are never dropped (no reset). Summary counts are
 *  recomputed over the merged set. Cursor/`hasMore` come from the server. */
internal fun TasksUiState.withAppendedTasks(
    incoming: List<TaskSummary>,
    nextCursor: String?
): TasksUiState {
    if (incoming.isEmpty()) {
        return copy(nextCursor = nextCursor, hasMore = nextCursor != null)
    }
    val content = (loadState as? ScreenLoadState.Content) ?: return this
    val merged = mergeTasksById(current = content.data.tasks, incoming = incoming)
    return copy(
        loadState = ScreenLoadState.Content(
            content.data.copy(tasks = merged, summaryCounts = merged.summaryCounts())
        ),
        nextCursor = nextCursor,
        hasMore = nextCursor != null
    )
}

/** Merge [incoming] into [current] keeping first-seen order; an id present in
 *  both is replaced by the incoming row (same position, no duplicate). */
internal fun mergeTasksById(
    current: List<TaskSummary>,
    incoming: List<TaskSummary>
): List<TaskSummary> {
    if (incoming.isEmpty()) return current
    val incomingById = incoming.associateBy { it.id.value }
    val merged = ArrayList<TaskSummary>(current.size + incoming.size)
    val seen = HashSet<String>(current.size + incoming.size)
    for (task in current) {
        val replacement = incomingById[task.id.value]
        merged.add(replacement ?: task)
        seen.add(task.id.value)
    }
    for (task in incoming) {
        if (seen.add(task.id.value)) {
            merged.add(task)
        }
    }
    return merged
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
    showChannelFilter: Boolean,
    identityOptions: List<MemberProfile> = emptyList()
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
    // Web feeds Creator and Assignee from the same server-scoped humans + agents
    // directory. Never rebuild this pool from task projections: an empty list while
    // the directory loads is still authoritative and both chips remain interactive.
    val sharedIdentityOptions =
        identityOptions
            .distinctBy { it.tasksIdentityFilterId() }
            .sortedWith(compareBy({ it.displayName.lowercase() }, { it.tasksIdentityFilterId() }))
    val creatorOptions = sharedIdentityOptions
    val assigneeOptions = sharedIdentityOptions
    val selectedChannels =
        if (showChannelFilter) {
            presentationState.selectedChannelNames.filter { it in channelOptions }.toSet()
        } else {
            emptySet()
        }
    val availableCreatorIds = creatorOptions.mapTo(mutableSetOf()) { it.tasksIdentityFilterId() }
    val selectedCreatorIds =
        presentationState.selectedCreatorIds.filter { it in availableCreatorIds }.toSet()
    val availableAssigneeIds = tasksAvailableAssigneeFilterIds(assigneeOptions)
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
            creatorScopedTasks.filter { task ->
                val assigneeId = task.assignee?.tasksIdentityFilterId()
                if (assigneeId == null) {
                    TasksUnassignedAssigneeFilterId in selectedAssigneeIds
                } else {
                    assigneeId in selectedAssigneeIds
                }
            }
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
        MemberKind.External -> "external"
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

internal enum class TaskAssignSkipReason(val logValue: String) {
    AlreadyCurrent("already_current"),
    StaleTaskAssignee("stale_task_assignee"),
}

/**
 * Assignee twin of [taskStatusUpdateSkipReason]. A task missing from the current
 * projection is not skipped here (same as status): the caller treats it as
 * `tasks_local_projection_missing`.
 */
internal fun TasksUiState.taskAssignUpdateSkipReason(
    task: TaskSummary,
    requestedAssignee: MemberProfile?
): TaskAssignSkipReason? {
    val currentTask = currentTaskSummary(task.id.value) ?: return null
    val sameAssignee =
        currentTask.assignee?.id == requestedAssignee?.id &&
            (currentTask.assignee != null) == (requestedAssignee != null)
    return when {
        sameAssignee -> TaskAssignSkipReason.AlreadyCurrent
        currentTask.assignee?.id != task.assignee?.id -> TaskAssignSkipReason.StaleTaskAssignee
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

/** Optimistic in-place assignee copy mirroring [withTaskStatus]. */
private fun TasksContent.withTaskAssignee(
    taskId: String,
    assignee: MemberProfile?
): TasksContent {
    val nextTasks =
        tasks.map { task ->
            if (task.matchesTaskKey(taskId)) task.copy(assignee = assignee) else task
        }
    return copy(tasks = nextTasks, summaryCounts = nextTasks.summaryCounts())
}

/**
 * Assignee twin of [withTaskStatusUpdating]: optimistic projection plus the same
 * per-card busy/error slots (a task has at most one in-flight mutation).
 */
fun TasksUiState.withTaskAssignUpdating(
    taskId: String,
    assignee: MemberProfile?
): TasksUiState {
    val contentState = loadState as? ScreenLoadState.Content
    return if (contentState != null) {
        copy(
            loadState =
                ScreenLoadState.Content(
                    contentState.data.withTaskAssignee(taskId = taskId, assignee = assignee)
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
