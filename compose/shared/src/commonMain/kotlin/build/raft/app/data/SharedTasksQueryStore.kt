package build.raft.app.data

import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The Tasks page (server scope) read from the server's task board query, as the web's Tasks page
 * (botiverse/slock staging c6580f2f41a617f1c406f13ffdcac2150ea3b044,
 * packages/web/src/components/task/tasksPage/: tasksPageModel.ts, useTasksPageCounts.ts,
 * TasksPageToolbar.tsx). The server filters, searches and sorts; the app shows what it sent.
 *
 * - **Lanes.** A lane is one filtered, sorted, paged read of `GET /api/tasks/server`
 *   ([tasksQueryLanes]). All is one lane; Mine is two sections: Assigned to you (one lane,
 *   relation `assigned_to_me`) and Started by you (`created_by_me` and `from_my_message`, merged
 *   by the sort and cut at the earliest page end of the two). Without a status filter Mine shows
 *   unfinished work, and Started by you also what was done in the last day (`completed_since`).
 * - **Counts.** `GET /api/tasks/server/counts` per filter set: the scope's numbers under no filter
 *   (All: every task; Mine: the three Mine lanes), and the view's under its filters (none while a
 *   search is on: the web does not count a search either).
 * - **Search.** Typed text shows at once ([TasksQueryState.searchDraft]); the server reads it once
 *   typing pauses for [SearchDebounceMs] (the web's 250 ms), at once when emptied.
 * - **Stale responses.** Every read carries its lane's or the query's generation; a response to an
 *   older query, an older lane read or another server is dropped. One read per lane at a time
 *   (single-flight), a repeated query is a no-op.
 * - **Failure** keeps the rows already shown and sets [TasksQueryState.error] with the failed
 *   response's trace id.
 * - **Live changes** ([onProjection]): the app's canonical task projection (realtime `task:*`
 *   frames and the app's own writes fold into it) patches the rows the lanes list, and a row whose
 *   sort key moved is placed again by the sort; a row that no longer matches its lane (as far as
 *   the row tells) leaves it; a changed task that matches joins where the sort places it, within
 *   the rows read (cut at the page end as read, web `LaneReadState.frontier`); one whose match the
 *   row can't tell, or a row that left the projection (not a deletion signal), makes the lane read
 *   its loaded rows again (throttled, Background). The counts are read again after live changes
 *   (throttled).
 * - **Old servers.** A counts read answering 404 means the server has no board query (slock
 *   before v1.21.63): [TasksQueryState.unsupported], and the page shows the app's own list.
 */
class SharedTasksQueryStore(
    private val scope: CoroutineScope,
    private val repository: SharedTasksRepository,
    private val serverId: suspend () -> ServerId?,
    private val viewerId: suspend () -> String?,
    private val preferences: TasksPageQueryPreferenceRepository? = null,
    /** Rows a page brought, for the shared projection (so the app's writes find them). */
    private val onPageRows: suspend (ServerId, List<TaskSummary>) -> Unit = { _, _ -> },
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val diagnosticsLogger: (String) -> Unit = {},
) {
    private val mutableState =
        MutableStateFlow(
            TasksQueryState(
                query =
                    TasksPageQuery(
                        scope = preferences?.scope() ?: TasksPageScope.Default,
                        sort = preferences?.sort() ?: ServerTaskSort.Default,
                    ),
            ),
        )
    val state: StateFlow<TasksQueryState> = mutableState.asStateFlow()

    private var startRequested = false
    private var queryGeneration = 0L
    private var laneSequence = 0L
    private val laneJobs = mutableMapOf<String, Job>()
    private val laneTokens = mutableMapOf<String, Long>()
    private val countJobs = mutableMapOf<String, Job>()
    private var searchJob: Job? = null
    private var liveRefreshJob: Job? = null
    private var countsRefreshJob: Job? = null
    private val pendingLiveLanes = mutableSetOf<String>()
    private var lastProjection: Map<String, TaskSummary>? = null

    /**
     * Reads the current query (no-op once asked). The server and the reader are resolved again
     * for every read, so a session that was not ready yet (a server switch, a cached restore) is
     * picked up by the next read or Refresh instead of sticking as missing.
     */
    fun start() {
        if (startRequested) return
        startRequested = true
        scope.launch {
            val viewer = viewerId()
            mutableState.value = state.value.copy(started = true, viewerId = viewer, doneSince = doneSinceNow())
            applyQuery(state.value.query, force = true)
        }
    }

    fun dispatch(intent: TasksQueryIntent) {
        val current = state.value
        when (intent) {
            is TasksQueryIntent.SetScope -> {
                if (intent.scope == current.query.scope) return
                preferences?.saveScope(intent.scope)
                applyQuery(current.query.copy(scope = intent.scope))
            }
            is TasksQueryIntent.SetSort -> {
                if (intent.sort == current.query.sort) return
                preferences?.saveSort(intent.sort)
                applyQuery(current.query.copy(sort = intent.sort))
            }
            is TasksQueryIntent.SetSearch -> setSearch(intent.text)
            is TasksQueryIntent.ToggleStatus ->
                applyQuery(current.query.copy(statuses = current.query.statuses.toggle(intent.status)))
            is TasksQueryIntent.ToggleChannel ->
                applyQuery(current.query.copy(channelIds = current.query.channelIds.toggle(intent.channelId)))
            is TasksQueryIntent.ToggleCreator ->
                applyQuery(current.query.copy(creators = current.query.creators.toggle(intent.identity)))
            is TasksQueryIntent.ToggleAssignee ->
                applyQuery(current.query.copy(assignees = current.query.assignees.toggle(intent.identity)))
            TasksQueryIntent.ClearChannels -> applyQuery(current.query.copy(channelIds = emptySet()))
            TasksQueryIntent.ClearCreators -> applyQuery(current.query.copy(creators = emptySet()))
            TasksQueryIntent.ClearAssignees -> applyQuery(current.query.copy(assignees = emptySet()))
            TasksQueryIntent.ClearFilters ->
                applyQuery(current.query.copy(statuses = emptySet(), channelIds = emptySet(), creators = emptySet(), assignees = emptySet()))
            TasksQueryIntent.LoadMore -> loadMore()
            TasksQueryIntent.Refresh -> refresh()
        }
    }

    private fun setSearch(text: String) {
        val draft = text.take(ServerTaskQueryMaxLength)
        if (draft != state.value.searchDraft) mutableState.value = state.value.copy(searchDraft = draft)
        searchJob?.cancel()
        val next = draft.trim()
        searchJob =
            scope.launch {
                if (next.isNotEmpty()) delay(SearchDebounceMs)
                applyQuery(state.value.query.copy(search = next))
            }
    }

    /**
     * Makes [next] the query. Equal to the current one: nothing (unless [force]). Otherwise every
     * lane read in flight is dropped, the new lanes keep the rows their predecessors showed until
     * their first page lands, the rows and counts no lane needs any more are let go, and the
     * counts the new query needs are read.
     */
    private fun applyQuery(next: TasksPageQuery, force: Boolean = false) {
        val current = state.value
        if (!force && next == current.query) return
        if (!current.started) {
            mutableState.value = current.copy(query = next)
            return
        }
        queryGeneration++
        laneJobs.values.forEach { it.cancel() }
        laneJobs.clear()
        laneTokens.clear()
        pendingLiveLanes.clear()
        val lanes = tasksQueryLanes(next, current.doneSince)
        val previousBySection = current.lanes.groupBy { it.section }
        val nextLanes =
            lanes.map { lane ->
                // Its rows stay on screen until the new first page lands; their cursor and page end
                // (maybe another sort's) do not.
                val previous = current.laneStates[lane.key] ?: previousBySection[lane.section]?.firstOrNull()?.let { current.laneStates[it.key] }
                lane to (previous?.copy(nextCursor = null, frontier = null, loaded = false, error = null) ?: TasksLaneState())
            }
        val laneStates = nextLanes.associate { (lane, laneState) -> lane.key to laneState.copy(loading = false) }
        val listed = laneStates.values.flatMapTo(mutableSetOf()) { it.ids }
        val neededCounts = tasksQueryCountFilters(next, current.doneSince).mapTo(mutableSetOf()) { tasksCountsKey(it) }
        mutableState.value =
            current.copy(
                query = next,
                lanes = lanes,
                laneStates = laneStates,
                tasks = current.tasks.filterKeys { it in listed },
                // A count the view no longer shows is read again when it is shown again.
                counts = current.counts.filterKeys { it in neededCounts },
                error = null,
            )
        diagnosticsLogger(
            "tasks_query_apply scope=${next.scope.storageKey} sort=${next.sort.wire} searching=${next.search.isNotEmpty()} " +
                "statuses=${next.statuses.size} channels=${next.channelIds.size} creators=${next.creators.size} " +
                "assignees=${next.assignees.size} lanes=${lanes.size}"
        )
        lanes.forEach { lane -> readLane(lane, cursor = null, limit = TasksQueryPageSize, priority = DataRequestPriority.Visible) }
        loadCounts(DataRequestPriority.Visible)
    }

    private fun refresh() {
        val current = state.value
        if (!current.started) return
        // Started by you's day of Done moves with the clock: a new bound is a new query.
        val doneSince = doneSinceNow()
        if (doneSince != current.doneSince) {
            mutableState.value = current.copy(doneSince = doneSince, error = null, countsError = null)
            applyQuery(current.query, force = true)
            return
        }
        // A pulled refresh re-reads every lane's loaded rows and the counts; the rows stay meanwhile.
        mutableState.value = current.copy(error = null, countsError = null)
        current.lanes.forEach { lane -> rereadLoaded(lane, DataRequestPriority.Visible) }
        loadCounts(DataRequestPriority.Visible, force = true)
    }

    /** Reads a lane's loaded rows again from its head, in one read (at most [TasksQueryMaxPageSize]). */
    private fun rereadLoaded(lane: TasksQueryLane, priority: DataRequestPriority) {
        val loaded = state.value.laneStates[lane.key]?.ids?.size ?: 0
        readLane(lane, cursor = null, limit = loaded.coerceIn(TasksQueryPageSize, TasksQueryMaxPageSize), priority = priority, force = true)
    }

    private fun loadMore() {
        val current = state.value
        current.lanes.forEach { lane ->
            val laneState = current.laneStates[lane.key] ?: return@forEach
            val cursor = laneState.nextCursor ?: return@forEach
            if (!laneState.loaded || laneState.loading) return@forEach
            readLane(lane, cursor = cursor, limit = TasksQueryPageSize, priority = DataRequestPriority.Visible)
        }
    }

    /**
     * One read of [lane]: the first page ([cursor] null, replacing the lane's rows) or the next.
     * Single-flight per lane unless [force] (a newer read of the head supersedes an older one).
     */
    private fun readLane(lane: TasksQueryLane, cursor: String?, limit: Int, priority: DataRequestPriority, force: Boolean = false) {
        if (laneJobs[lane.key]?.isActive == true) {
            if (!force) return
            laneJobs[lane.key]?.cancel()
        }
        val generation = queryGeneration
        val token = ++laneSequence
        laneTokens[lane.key] = token
        val sort = state.value.query.sort
        updateLane(lane.key) { it.copy(loading = true) }
        laneJobs[lane.key] =
            scope.launch {
                val server = serverId()
                if (state.value.viewerId == null) {
                    viewerId()?.let { viewer -> mutableState.value = state.value.copy(viewerId = viewer) }
                }
                val result =
                    if (server == null) {
                        TasksPageResult.Error(code = "selected_server_missing")
                    } else {
                        repository.loadServerTaskQueryPage(server, lane.filters, sort, limit, cursor, priority)
                    }
                // A newer query, or a newer read of this lane, owns the lane now.
                if (generation != queryGeneration || laneTokens[lane.key] != token) {
                    diagnosticsLogger("tasks_query_page_drop reason=stale lane=${lane.section.name}")
                    return@launch
                }
                when (result) {
                    is TasksPageResult.Page -> {
                        val current = state.value
                        val rows = result.tasks.associateBy { it.id.value }
                        val laneState = current.laneStates[lane.key] ?: TasksLaneState()
                        val pageIds = result.tasks.map { it.id.value }
                        val pageKeys = result.tasks.associate { it.id.value to tasksSortKey(sort, it) }
                        // A later page names where the server places a row now (a row placed live moves there).
                        val ids = if (cursor == null) pageIds else laneState.ids.filterNot { it in rows } + pageIds
                        val keys = if (cursor == null) pageKeys else laneState.keys + pageKeys
                        // Where the rows read end, as read (web LaneReadState.frontier): live changes never move it.
                        val frontier = result.tasks.lastOrNull() ?: if (cursor == null) null else laneState.frontier
                        mutableState.value =
                            current.copy(
                                tasks = current.tasks + rows,
                                laneStates =
                                    current.laneStates +
                                        (
                                            lane.key to
                                                laneState.copy(
                                                    ids = ids,
                                                    keys = keys,
                                                    frontier = frontier,
                                                    nextCursor = result.nextCursor,
                                                    loaded = true,
                                                    loading = false,
                                                    error = null,
                                                )
                                        ),
                                error = if (current.laneStates.values.any { it !== laneState && it.error != null }) current.error else null,
                            )
                        // After the rows are the lane's, so the projection echoing them is no live change.
                        if (server != null) onPageRows(server, result.tasks)
                    }
                    TasksPageResult.InvalidCursor -> {
                        // The cursor no longer walks (the sort changed under it): read the head again.
                        updateLane(lane.key) { it.copy(loading = false, nextCursor = null) }
                        readLane(lane, cursor = null, limit = TasksQueryPageSize, priority = priority, force = true)
                    }
                    is TasksPageResult.Error -> {
                        val error = ScreenError(code = result.code, retryable = true, traceId = result.traceId)
                        diagnosticsLogger("tasks_query_page_failed lane=${lane.section.name} code=${result.code}")
                        updateLane(lane.key) { it.copy(loading = false, error = error) }
                        mutableState.value = state.value.copy(error = error)
                    }
                }
            }
    }

    private fun updateLane(key: String, transform: (TasksLaneState) -> TasksLaneState) {
        val current = state.value
        val laneState = current.laneStates[key] ?: return
        val next = transform(laneState)
        if (next != laneState) mutableState.value = current.copy(laneStates = current.laneStates + (key to next))
    }

    /** Reads every filter set the counts need that is not read (or every one, [force]). */
    private fun loadCounts(priority: DataRequestPriority, force: Boolean = false) {
        val needed = tasksQueryCountFilters(state.value.query, state.value.doneSince)
        needed.forEach { filters ->
            val key = tasksCountsKey(filters)
            if (!force && state.value.counts.containsKey(key)) return@forEach
            if (countJobs[key]?.isActive == true) {
                if (!force) return@forEach
                countJobs[key]?.cancel()
            }
            countJobs[key] =
                scope.launch {
                    val server = serverId()
                    val result =
                        if (server == null) {
                            ServerTaskCountsResult.Error(code = "selected_server_missing")
                        } else {
                            repository.loadServerTaskCounts(server, filters, priority)
                        }
                    when (result) {
                        is ServerTaskCountsResult.Counts -> {
                            val current = state.value
                            if (current.counts[key] != result.counts) {
                                mutableState.value = current.copy(counts = current.counts + (key to result.counts), countsError = null)
                            } else if (current.countsError != null) {
                                mutableState.value = current.copy(countsError = null)
                            }
                        }
                        is ServerTaskCountsResult.Error -> {
                            // A server from before the board query (slock v1.21.63) has no counts route:
                            // the page goes back to the list the app filters itself.
                            val unsupported = result.code in TasksQueryUnsupportedCodes
                            if (unsupported) diagnosticsLogger("tasks_query_unsupported code=${result.code}")
                            // The numbers already shown stay; the error carries the trace id.
                            mutableState.value =
                                state.value.copy(
                                    countsError = ScreenError(code = result.code, retryable = true, traceId = result.traceId),
                                    unsupported = state.value.unsupported || unsupported,
                                )
                        }
                    }
                }
        }
    }

    /**
     * The app's canonical task projection changed ([projection]: every task it holds for this
     * server). [bulk]: a list read landed (not a live change): only patch the rows shown.
     *
     * A row that leaves the projection is not taken as deleted: the projection also lets rows go
     * when a list read replaces it, a channel read narrows it, or a fact conflicts. The lanes
     * listing it read their rows again instead, and the server says whether it is still there.
     */
    fun onProjection(projection: List<TaskSummary>, bulk: Boolean) {
        val byId = projection.associateBy { it.id.value }
        val previous = lastProjection
        lastProjection = byId
        val current = state.value
        if (!current.started) return
        // Patch the rows the lanes list (never with an older revision, never losing a known source sender).
        var tasks = current.tasks
        current.tasks.forEach { (id, row) ->
            val fresh = byId[id]?.withKnownSourceSender(row) ?: return@forEach
            val older = fresh.revision != null && row.revision != null && fresh.revision < row.revision
            if (fresh != row && !older) tasks = tasks + (id to fresh)
        }
        if (previous == null) {
            if (tasks != current.tasks) mutableState.value = current.copy(tasks = tasks)
            return
        }
        val left = previous.keys - byId.keys
        val reread = mutableSetOf<String>()
        current.lanes.forEach { lane ->
            if (current.laneStates[lane.key]?.ids?.any { it in left } == true) reread += lane.key
        }
        val changed =
            byId.values.filter { task ->
                val id = task.id.value
                previous[id] != task && current.tasks[id] != task
            }
        var laneStates = current.laneStates
        // Many at once is a list read landing, not live edits: patch only.
        if (!bulk && changed.size <= LiveBatchLimit) {
            val compare = tasksQueryComparator(current.query.sort)
            current.lanes.forEach { lane ->
                val laneState = laneStates[lane.key] ?: return@forEach
                var ids = laneState.ids
                changed.forEach { change ->
                    val task = tasks[change.id.value] ?: change
                    val id = task.id.value
                    val listed = id in ids
                    // A task sorting after the rows read, while more pages remain, arrives with them.
                    val frontier = laneState.frontier
                    val withinRead = laneState.nextCursor == null || frontier == null || compare.compare(task, frontier) <= 0
                    val matches = task.matchesTasksQueryLane(lane.filters, current.viewerId)
                    if (matches == false) {
                        if (listed) ids = ids - id
                    } else if (!listed && laneState.loaded && withinRead) {
                        if (matches == true) {
                            // It joins where the sort places it (no key as read: placed by the sort).
                            ids = ids + id
                            tasks = tasks + (id to task)
                        } else {
                            // The row lacks a fact the lane hinges on: the server decides.
                            reread += lane.key
                        }
                    }
                }
                if (ids != laneState.ids) laneStates = laneStates + (lane.key to laneState.copy(ids = ids))
            }
        }
        val next = current.copy(tasks = tasks, laneStates = laneStates)
        if (next != current) mutableState.value = next
        if (changed.isNotEmpty() || left.isNotEmpty()) scheduleCountsRefresh()
        if (reread.isNotEmpty()) scheduleLiveReread(reread)
    }

    /**
     * Lanes to read again after live changes: the first change starts the wait, later ones join it,
     * so a busy server cannot put the read off for ever (a throttle, not a trailing debounce).
     */
    private fun scheduleLiveReread(keys: Set<String>) {
        pendingLiveLanes += keys
        if (liveRefreshJob?.isActive == true) return
        liveRefreshJob =
            scope.launch {
                delay(LiveRereadDelayMs)
                val pending = pendingLiveLanes.toSet()
                pendingLiveLanes.clear()
                state.value.lanes.filter { it.key in pending }.forEach { lane ->
                    diagnosticsLogger("tasks_query_live_reread lane=${lane.section.name}")
                    // A page being loaded is superseded: this read covers it.
                    rereadLoaded(lane, DataRequestPriority.Background)
                }
            }
    }

    /** The counts after live changes, at most one read per [CountsRefreshDelayMs] (throttled). */
    private fun scheduleCountsRefresh() {
        if (countsRefreshJob?.isActive == true) return
        countsRefreshJob =
            scope.launch {
                delay(CountsRefreshDelayMs)
                loadCounts(DataRequestPriority.Background, force = true)
            }
    }

    private fun doneSinceNow(): String =
        Instant.fromEpochMilliseconds((nowEpochMillis() - RecentDoneMs) / 60_000L * 60_000L).toString()

    companion object {
        /** The web's SEARCH_DEBOUNCE_MS. */
        const val SearchDebounceMs: Long = 250L
        /** After a live change, how long the lanes wait before reading again (changes in between join). */
        const val LiveRereadDelayMs: Long = 1_000L
        /** After a live change, how long before the counts are read again. */
        const val CountsRefreshDelayMs: Long = 2_000L
        /** More changes than this at once is a list read landing, not a live change. */
        const val LiveBatchLimit: Int = 20
        /** Started by you keeps a task done this recently (web RECENT_DONE_MS). */
        const val RecentDoneMs: Long = 24L * 60L * 60L * 1000L
    }
}

/** First page of a lane and each page after it (web LANE_PAGE_SIZE is 20; a phone list reads a little more at once). */
const val TasksQueryPageSize: Int = 50

/** The most a lane reads again at once (the server's limit is 500). */
const val TasksQueryMaxPageSize: Int = 500

/**
 * A counts read failing with one of these means the server has no board query (slock before
 * v1.21.63: no `/api/tasks/server/counts`, and `GET /api/tasks/server` ignores relation, q and
 * sort): [TasksQueryState.unsupported].
 */
internal val TasksQueryUnsupportedCodes: Set<String> = setOf("tasks_counts_http_404", "tasks_counts_http_405")

enum class TasksPageScope(val storageKey: String) {
    Mine("mine"),
    All("all");

    companion object {
        /** The web's: Mine. */
        val Default: TasksPageScope = Mine

        fun fromStorageKey(value: String?): TasksPageScope? = entries.firstOrNull { it.storageKey == value }
    }
}

/** Where a lane's rows show: Mine's two sections, or All's one list. */
enum class TasksQuerySection { Assigned, Started, All }

/** What the page asks the server for (the search as the server reads it: trimmed, debounced). */
data class TasksPageQuery(
    val scope: TasksPageScope = TasksPageScope.Default,
    val search: String = "",
    val statuses: Set<TaskStatus> = emptySet(),
    val channelIds: Set<String> = emptySet(),
    /** `user:<id>` / `agent:<id>`. */
    val creators: Set<String> = emptySet(),
    /** `user:<id>` / `agent:<id>` / `unassigned`. */
    val assignees: Set<String> = emptySet(),
    val sort: ServerTaskSort = ServerTaskSort.Default,
) {
    /** A toolbar filter is set (Clear all shows). The search is not one: it has its own clear. */
    val hasToolbarFilter: Boolean
        get() = statuses.isNotEmpty() || channelIds.isNotEmpty() || creators.isNotEmpty() || assignees.isNotEmpty()

    /** The page shows less than the scope holds. */
    val narrowed: Boolean
        get() = hasToolbarFilter || search.isNotEmpty()

    /** The filters every lane shares (statuses in the board's order). */
    fun baseFilters(): ServerTaskBoardFilters =
        ServerTaskBoardFilters(
            statuses = ServerTaskBoardStatuses.filter { it in statuses },
            channelIds = channelIds.sorted(),
            assignees = assignees.sorted(),
            creators = creators.sorted(),
            query = search,
        )
}

/** One filtered, sorted read of the board ([key]: its filters, stable). */
data class TasksQueryLane(
    val key: String,
    val section: TasksQuerySection,
    val filters: ServerTaskBoardFilters,
)

data class TasksLaneState(
    /** Task ids in the server's order (a row that joined live is appended; the sort places it). */
    val ids: List<String> = emptyList(),
    /** Each row's sort key as the server read it ([tasksSortKey]): a row whose key moved since is placed again by the sort. */
    val keys: Map<String, String> = emptyMap(),
    /** The last row of the last page as it was read: where the rows read end while more pages remain. */
    val frontier: TaskSummary? = null,
    val nextCursor: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: ScreenError? = null,
)

sealed interface TasksQueryIntent {
    data class SetScope(val scope: TasksPageScope) : TasksQueryIntent
    data class SetSort(val sort: ServerTaskSort) : TasksQueryIntent
    data class SetSearch(val text: String) : TasksQueryIntent
    data class ToggleStatus(val status: TaskStatus) : TasksQueryIntent
    data class ToggleChannel(val channelId: String) : TasksQueryIntent
    data class ToggleCreator(val identity: String) : TasksQueryIntent
    data class ToggleAssignee(val identity: String) : TasksQueryIntent
    data object ClearChannels : TasksQueryIntent
    data object ClearCreators : TasksQueryIntent
    data object ClearAssignees : TasksQueryIntent
    /** Clear all: every toolbar filter (not the search). */
    data object ClearFilters : TasksQueryIntent
    data object LoadMore : TasksQueryIntent
    data object Refresh : TasksQueryIntent
}

data class TasksQueryState(
    val query: TasksPageQuery = TasksPageQuery(),
    /** The search field as typed (the server reads [TasksPageQuery.search]). */
    val searchDraft: String = "",
    val started: Boolean = false,
    val viewerId: String? = null,
    /** Started by you's done bound (ISO), fixed per page session. */
    val doneSince: String = "",
    val lanes: List<TasksQueryLane> = emptyList(),
    val laneStates: Map<String, TasksLaneState> = emptyMap(),
    /** Every row the lanes have read, by task id, patched by live changes. */
    val tasks: Map<String, TaskSummary> = emptyMap(),
    /** Counts by filter set ([tasksCountsKey]). */
    val counts: Map<String, ServerTaskCounts> = emptyMap(),
    /** The last failed page read; the rows shown stay. */
    val error: ScreenError? = null,
    val countsError: ScreenError? = null,
    /** The server has no board query ([TasksQueryUnsupportedCodes]): the page shows the app's own list instead. */
    val unsupported: Boolean = false,
)

/** One section of the list as shown. [total]: the server's count, null while unread or searching. */
data class TasksQuerySectionRows(
    val section: TasksQuerySection,
    val rows: List<TaskSummary>,
    val total: Int?,
    val hasMore: Boolean,
    val loading: Boolean,
    val loaded: Boolean,
)

/** Mine without a status filter: what is not finished. */
internal val TasksQueryUnfinishedStatuses: List<TaskStatus> = listOf(TaskStatus.Backlog, TaskStatus.Todo, TaskStatus.InProgress)

/** The lanes [query] reads (web tasksPageModel.ts `viewLanes` for the list). */
fun tasksQueryLanes(query: TasksPageQuery, doneSince: String): List<TasksQueryLane> {
    val base = query.baseFilters()
    return when (query.scope) {
        TasksPageScope.All -> listOf(lane(TasksQuerySection.All, base))
        TasksPageScope.Mine -> {
            val chosen = base.statuses.isNotEmpty()
            val assigned = base.copy(relation = ServerTaskRelation.AssignedToMe, statuses = if (chosen) base.statuses else TasksQueryUnfinishedStatuses)
            val started =
                listOf(ServerTaskRelation.CreatedByMe, ServerTaskRelation.FromMyMessage).map { relation ->
                    base.copy(
                        relation = relation,
                        statuses = if (chosen) base.statuses else TasksQueryUnfinishedStatuses + TaskStatus.Done,
                        completedSince = if (chosen) null else doneSince.takeIf { it.isNotEmpty() },
                    )
                }
            listOf(lane(TasksQuerySection.Assigned, assigned)) + started.map { lane(TasksQuerySection.Started, it) }
        }
    }
}

private fun lane(section: TasksQuerySection, filters: ServerTaskBoardFilters): TasksQueryLane =
    TasksQueryLane(key = tasksCountsKey(filters), section = section, filters = filters)

/** A filter set's stable key (its query string), shared by lanes and counts. */
fun tasksCountsKey(filters: ServerTaskBoardFilters): String =
    serverTaskFilterQuery(filters).joinToString("&") { (key, value) -> "$key=$value" }

/**
 * The filter sets the counts read: the scopes under no filter (All: everything; Mine: its three
 * lanes), then the view's lanes under the current filters unless a search is on.
 */
fun tasksQueryCountFilters(query: TasksPageQuery, doneSince: String): List<ServerTaskBoardFilters> {
    val all = ServerTaskBoardFilters()
    val mine = tasksQueryLanes(TasksPageQuery(scope = TasksPageScope.Mine), doneSince).map { it.filters }
    val view = if (query.search.isNotEmpty()) emptyList() else tasksQueryLanes(query, doneSince).map { it.filters }
    return (listOf(all) + mine + view).distinctBy { tasksCountsKey(it) }
}

/** The scopes' numbers under no filter (null while unread). */
fun TasksQueryState.scopeCounts(): Map<TasksPageScope, Int?> {
    val mine = tasksQueryLanes(TasksPageQuery(scope = TasksPageScope.Mine), doneSince).map { counts[it.key]?.total }
    return mapOf(
        TasksPageScope.All to counts[tasksCountsKey(ServerTaskBoardFilters())]?.total,
        TasksPageScope.Mine to if (mine.any { it == null }) null else mine.sumOf { it ?: 0 },
    )
}

/** Per board status, the counts of the current view (null while unread or searching). */
fun TasksQueryState.viewStatusCounts(): Map<TaskStatus, Int>? {
    if (query.search.isNotEmpty()) return null
    val read = lanes.map { counts[it.key] ?: return null }
    return ServerTaskBoardStatuses.associateWith { status -> read.sumOf { it.statuses[status] ?: 0 } }
}

/** The list as shown: All one section, Mine two; each lane's rows that still match, merged by the sort. */
fun TasksQueryState.sections(): List<TasksQuerySectionRows> {
    val order = if (query.scope == TasksPageScope.All) listOf(TasksQuerySection.All) else listOf(TasksQuerySection.Assigned, TasksQuerySection.Started)
    return order.map { section ->
        val sectionLanes = lanes.filter { it.section == section }
        val states = sectionLanes.map { laneStates[it.key] ?: TasksLaneState() }
        val counted = if (query.search.isNotEmpty()) null else sectionLanes.map { counts[it.key]?.total }
        TasksQuerySectionRows(
            section = section,
            rows = mergedLaneRows(sectionLanes, states),
            total = counted?.takeIf { totals -> totals.none { it == null } }?.sumOf { it ?: 0 },
            hasMore = states.any { it.nextCursor != null },
            loading = states.any { it.loading },
            loaded = states.all { it.loaded },
        )
    }
}

/** Every row the view shows, once (the board groups them by status). */
fun TasksQueryState.shownRows(): List<TaskSummary> = sections().flatMap { it.rows }.distinctBy { it.id.value }

/**
 * The head's number (web TasksPage `number`): the view's count, or "N / scope total" when narrowed;
 * searching (not counted), the hits read so far once every lane is read to its end, else the scope's total.
 */
fun TasksQueryState.headNumber(): String? {
    val scopeTotal = scopeCounts()[query.scope]
    if (query.search.isNotEmpty()) {
        val sections = sections()
        val complete = sections.all { it.loaded && !it.hasMore }
        val hits = sections.sumOf { it.rows.size }
        return when {
            scopeTotal == null -> null
            complete -> "$hits / $scopeTotal"
            else -> scopeTotal.toString()
        }
    }
    val shown = sections().map { it.total }.takeIf { totals -> totals.none { it == null } }?.sumOf { it ?: 0 } ?: return null
    return if (query.narrowed && scopeTotal != null) "$shown / $scopeTotal" else shown.toString()
}

/**
 * A group's rows (web tasksPageModel.ts `mergedLaneRows`): each lane's rows that still match, the
 * server's own in its order, merged across lanes by the sort; rows whose sort key moved since the
 * read (or that joined live) are placed by the sort. Nothing sorting after the earliest page end
 * as read among the lanes with more to read shows, except that lane's own server-placed rows.
 */
private fun TasksQueryState.mergedLaneRows(sectionLanes: List<TasksQueryLane>, states: List<TasksLaneState>): List<TaskSummary> {
    val sort = query.sort
    val compare = tasksQueryComparator(sort)
    var bound: TaskSummary? = null
    var cutLane = -1
    for (index in states.indices) {
        val frontier = states[index].frontier ?: continue
        if (states[index].nextCursor == null) continue
        val current = bound
        if (current == null || compare.compare(frontier, current) < 0) {
            bound = frontier
            cutLane = index
        }
    }
    val cut = bound
    val placed = mutableSetOf<String>()
    var ordered = emptyList<TaskSummary>()
    val moved = mutableListOf<TaskSummary>()
    sectionLanes.forEachIndexed { index, lane ->
        val laneState = states[index]
        val serverRows = mutableListOf<TaskSummary>()
        for (id in laneState.ids) {
            val task = tasks[id] ?: continue
            if (id in placed || task.matchesTasksQueryLane(lane.filters, viewerId) == false) continue
            val asRead = laneState.keys[id] == tasksSortKey(sort, task)
            if (asRead) {
                if (index != cutLane && cut != null && compare.compare(task, cut) > 0) continue
                serverRows += task
            } else {
                if (cut != null && compare.compare(task, cut) > 0) continue
                moved += task
            }
            placed += id
        }
        ordered = if (index == 0) serverRows else mergeOrderedRows(ordered, serverRows, compare)
    }
    return mergeOrderedRows(ordered, moved.sortedWith(compare), compare)
}

/** Two lists each in order, merged; ties keep the first list's row first. */
private fun mergeOrderedRows(a: List<TaskSummary>, b: List<TaskSummary>, compare: Comparator<TaskSummary>): List<TaskSummary> {
    val out = ArrayList<TaskSummary>(a.size + b.size)
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        if (compare.compare(b[j], a[i]) < 0) out += b[j++] else out += a[i++]
    }
    while (i < a.size) out += a[i++]
    while (j < b.size) out += b[j++]
    return out
}

/** The value [sort] orders by (web `sortKeyOf`). */
internal fun tasksSortKey(sort: ServerTaskSort, task: TaskSummary): String =
    if (sort == ServerTaskSort.Created) task.createdAt.orEmpty() else task.tasksActivityKey()

/** A live copy without the source message's sender (a socket frame) keeps the one a page read, for the same source. */
internal fun TaskSummary.withKnownSourceSender(read: TaskSummary): TaskSummary =
    if (!sourceSenderReported && read.sourceSenderReported && sourceMessageId == read.sourceMessageId) {
        copy(sourceSenderType = read.sourceSenderType, sourceSenderId = read.sourceSenderId, sourceSenderReported = true)
    } else {
        this
    }

/** The server's order for [sort] (newest first, ties by id descending; activity_asc the exact reverse). */
fun tasksQueryComparator(sort: ServerTaskSort): Comparator<TaskSummary> {
    val newestFirst =
        compareByDescending<TaskSummary> { tasksSortKey(sort, it) }
            .thenByDescending { it.id.value }
    return if (sort == ServerTaskSort.ActivityAsc) newestFirst.reversed() else newestFirst
}

/** When a task last moved, as the page orders it (web `taskActivityAt`). */
internal fun TaskSummary.tasksActivityKey(): String = activityAt ?: updatedAtLabel

/**
 * Whether a row belongs to a lane as far as the row tells (web tasksPageModel.ts `matchLane`):
 * true, false, or null when it hinges on a fact the row lacks (who sent a task's source message,
 * or who reads).
 * `completed_since` is the server's to judge (rows carry no completion time).
 */
fun TaskSummary.matchesTasksQueryLane(filters: ServerTaskBoardFilters, viewerId: String?): Boolean? {
    val boardStatus = if (status == TaskStatus.InReview) TaskStatus.InProgress else status
    if (filters.statuses.isNotEmpty() && boardStatus !in filters.statuses) return false
    if (filters.channelIds.isNotEmpty() && channelId?.value !in filters.channelIds) return false
    if (filters.creators.isNotEmpty() && creator?.tasksQueryIdentity() !in filters.creators) return false
    if (filters.assignees.isNotEmpty()) {
        val identity = assignee?.tasksQueryIdentity()
        if (identity == null) {
            if (TasksUnassignedAssigneeFilterId !in filters.assignees) return false
        } else if (identity !in filters.assignees) {
            return false
        }
    }
    if (filters.query.isNotEmpty() && !matchesTasksQuerySearch(filters.query)) return false
    val relation = filters.relation ?: return true
    // Not known yet who reads: the server's word stands.
    val viewer = viewerId ?: return null
    val claimedByMe = assignee?.kind == MemberKind.Human && assignee.id.value == viewer
    val createdByMe = creator?.kind == MemberKind.Human && creator.id.value == viewer
    return when (relation) {
        ServerTaskRelation.AssignedToMe -> claimedByMe
        ServerTaskRelation.CreatedByMe -> createdByMe && !claimedByMe
        ServerTaskRelation.FromMyMessage ->
            when {
                claimedByMe || createdByMe -> false
                sourceMessageReported && sourceMessageId == null -> false
                // Summary rows say who sent the source message (null: none, or deleted); socket frames don't.
                sourceSenderReported -> sourceSenderType == "user" && sourceSenderId == viewer
                else -> null
            }
    }
}

/** The search as the server reads it: every word in the title (any case), or one word that is the number (`12`, `#12`). */
fun TaskSummary.matchesTasksQuerySearch(query: String): Boolean {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.distinct().take(8)
    if (words.isEmpty()) return true
    val lowered = title.lowercase()
    if (words.all { lowered.contains(it) }) return true
    val number = if (words.size == 1) Regex("^#?(\\d{1,9})$").find(words.first())?.groupValues?.get(1)?.toIntOrNull() else null
    return number != null && taskNumber == number
}

/** The server's `user:<id>` / `agent:<id>` of a task's person. */
internal fun build.raft.app.model.MemberProfile.tasksQueryIdentity(): String? =
    when (kind) {
        MemberKind.Human -> "user:${id.value}"
        MemberKind.Agent -> "agent:${id.value}"
        else -> null
    }

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
