package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Artifacts home page's list (web `ArtifactsDashboard.tsx` `useAllArtifacts`, `allReducer`,
 * `artifactDashboardFilters.ts`, and `useArtifactHomeGroups.ts`; botiverse/slock staging `87ab82603`
 * — `#15654`, `#16137`, `#16153`, `#16182`, `#16290`).
 *
 * - Reads when the page has a server with `workspace_artifacts_v0` on, and again for a new server,
 *   filter, settled search (typing waits [SEARCH_DEBOUNCE_MILLIS]; clearing search is immediate),
 *   the toggle or a sort. Same filter in another sort keeps rows on screen; new filter shows the
 *   skeleton. Older answers are dropped ([generation]).
 * - All 5 filters (conversation/place, author + cached actor, settled status, search, relevant)
 *   persist per `(readerId, serverId)` via [filterRepository], and server settled support persists
 *   via [settledSeenRepository] so 0-result reloads keep the Author label/avatar and Status chip.
 * - "Clear all" resets all 5 filters atomically and cancels any pending search debounce so no
 *   spurious `q` request fires.
 * - A sort that fails goes back to the last answered one, with [ArtifactsHomeEvent.SortFailed].
 * - Load More continues the query the rows on screen were read for and de-duplicates by id.
 * - Own Settle / Unsettle (`SetSettled`) is single-flight per Artifact, patches the row at once,
 *   and re-reads the first page silently unless Load More has extended the list.
 * - New activity on this server's Artifacts reads the first page again, rows kept, unless the
 *   viewer is open or Load More has extended the list. Closing the viewer always reads again.
 * - 403 ARTIFACTS_NOT_ENABLED: [ArtifactsHomeStatus.Off].
 */
@RaftCoreInternalApi class SharedArtifactsHomeStore(
    private val api: ArtifactsHomeSource,
    private val scope: CoroutineScope,
    initialSort: ArtifactsHomeSort = ArtifactsHomeSort.Default,
    /** The sort the reader chose, to keep on the device (web `artifactDashboardPrefs`). */
    private val onSortChosen: (ArtifactsHomeSort) -> Unit = {},
    private val logger: ((String) -> Unit)? = null,
    private val filterRepository: ArtifactsHomeFilterRepository? = null,
    private val settledSeenRepository: ArtifactsHomeSettledSeenRepository? = null,
    /** Own Follow/Unfollow events emitted from other surfaces (e.g. message cards, slock #16357). */
    private val ownFollowEvents: SharedFlow<ArtifactOwnFollowEvent>? = null,
    /** Reports a Follow/Unfollow committed here to the shared follow store so message cards sync. */
    private val onOwnFollowCommitted: (serverId: String, artifactId: String, following: Boolean) -> Unit = { _, _, _ -> },
) {
    private val mutableState = MutableStateFlow(ArtifactsHomeState(query = ArtifactsHomeQuery(sort = initialSort)))
    val state: StateFlow<ArtifactsHomeState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<ArtifactsHomeEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<ArtifactsHomeEvent> = mutableEvents.asSharedFlow()

    private var generation = 0
    private var reloadGeneration = 0
    private var readJob: Job? = null
    private var pageJob: Job? = null
    private var searchJob: Job? = null
    private var legacyAuthorJob: Job? = null
    private var ownFollowObserveJob: Job? = null

    /** What the rows on screen were read with: their cursor continues only that. */
    private var shownQuery: ArtifactsHomeQuery? = null
    private var shownGeneration = 0

    /** The sort of the last read that answered: where a re-sort that fails goes back to. */
    private var answeredSort: ArtifactsHomeSort = initialSort

    /** The filter (sort aside) and server the rows on screen were read for. */
    private var readFor: String? = null

    private var activityRead: ActivityRead = ActivityRead.Idle

    private val choiceJobs = mutableMapOf<ArtifactsHomeChoiceKind, Job>()
    private val settleJobs = mutableMapOf<String, Job>()
    private val followJobs = mutableMapOf<String, Job>()

    /**
     * Own Follow/Unfollow answers within the current [reloadGeneration] scope (web
     * `useArtifactHomeGroups.ts` `OwnAnswers`, slock #16357 + #16397): preserved across background
     * `keepRows = true` refreshes and cleared on `keepRows = false` filter/server reloads.
     */
    private val ownFollows = LinkedHashMap<String, Boolean>()
    private val retainedUnfollowed = LinkedHashMap<String, RetainedUnfollowedItem>()

    init {
        if (ownFollowEvents != null) {
            ownFollowObserveJob =
                scope.launch {
                    ownFollowEvents.collect { event ->
                        val current = mutableState.value
                        if (current.serverId == event.serverId && current.rows != null) {
                            applyOwnFollow(event.artifactId, event.following)
                        }
                    }
                }
        }
    }

    fun dispatch(intent: ArtifactsHomeIntent) {
        when (intent) {
            is ArtifactsHomeIntent.Start -> start(intent.serverId, intent.readerId)
            is ArtifactsHomeIntent.Search -> search(intent.text)
            is ArtifactsHomeIntent.SetPlace -> {
                applyQuery { it.copy(place = intent.place) }
                persistCurrentFilters()
            }
            is ArtifactsHomeIntent.SetAuthor -> {
                legacyAuthorJob?.cancel()
                val actor = intent.author
                val authorValue = normalizeArtifactsHomeAuthorFilter(actor?.filterValue)
                val updatedKnown =
                    if (actor != null && authorValue != null) {
                        mutableState.value.knownAuthors + (authorValue to actor)
                    } else {
                        mutableState.value.knownAuthors
                    }
                mutableState.value = mutableState.value.copy(authorActor = actor, knownAuthors = updatedKnown)
                applyQuery { it.copy(author = authorValue) }
                persistCurrentFilters()
            }
            is ArtifactsHomeIntent.SetStatus -> {
                if (intent.status == mutableState.value.statusFilter) return
                mutableState.value = mutableState.value.copy(statusFilter = intent.status)
                persistCurrentFilters()
            }
            is ArtifactsHomeIntent.SetRelevant -> {
                applyQuery { it.copy(relevant = intent.relevant) }
                persistCurrentFilters()
            }
            is ArtifactsHomeIntent.SetSort -> {
                if (intent.sort == mutableState.value.query.sort) return
                onSortChosen(intent.sort)
                applyQuery { it.copy(sort = intent.sort) }
            }
            ArtifactsHomeIntent.ClearSearchAndFilters,
            ArtifactsHomeIntent.ClearFilters,
            -> clearAllFilters()
            is ArtifactsHomeIntent.SetSettled -> setSettled(intent.artifactId, intent.settled)
            is ArtifactsHomeIntent.SetFollowing -> setFollowing(intent.artifactId, intent.following)
            ArtifactsHomeIntent.Retry -> read(keepRows = mutableState.value.rows != null)
            ArtifactsHomeIntent.LoadMore -> loadMore()
            is ArtifactsHomeIntent.ActivityChanged -> onActivity(intent.serverId)
            // #198: the app came back / the socket reconnected; `activity:changed` pushes sent
            // meanwhile were missed. Same silent, single-flight, rows-kept read.
            is ArtifactsHomeIntent.CatchUp -> onActivity(intent.serverId)
            ArtifactsHomeIntent.ViewerOpened -> mutableState.value = mutableState.value.copy(viewerOpen = true)
            ArtifactsHomeIntent.ViewerClosed -> {
                mutableState.value = mutableState.value.copy(viewerOpen = false)
                // Opening it read the current version: what was new may be gone, the order may have moved.
                if (mutableState.value.serverId != null && mutableState.value.status != ArtifactsHomeStatus.Off) read(keepRows = true)
            }
            is ArtifactsHomeIntent.OpenChoices -> openChoices(intent.kind)
            is ArtifactsHomeIntent.CloseChoices -> closeChoices(intent.kind)
            is ArtifactsHomeIntent.ChoicesQuery -> choicesQuery(intent.kind, intent.text)
            is ArtifactsHomeIntent.RetryChoices -> {
                updateChoices(intent.kind) { it.copy(phase = ArtifactsHomeChoicesPhase.Pending, answeredFor = null) }
                askChoices(intent.kind, delayMillis = 0)
            }
        }
    }

    fun clear() {
        readJob?.cancel()
        pageJob?.cancel()
        searchJob?.cancel()
        legacyAuthorJob?.cancel()
        ownFollowObserveJob?.cancel()
        choiceJobs.values.forEach { it.cancel() }
        choiceJobs.clear()
        val pendingSettle = settleJobs.values.toList()
        settleJobs.clear()
        pendingSettle.forEach { it.cancel() }
        val pendingFollow = followJobs.values.toList()
        followJobs.clear()
        pendingFollow.forEach { it.cancel() }
    }

    private fun start(serverId: String?, readerId: String?) {
        val current = mutableState.value
        val cleanReader = readerId?.trim()?.takeIf { it.isNotEmpty() }
        val scopeChanged = serverId != current.serverId || cleanReader != current.readerId
        if (!scopeChanged && current.status != ArtifactsHomeStatus.Idle) return
        if (scopeChanged) {
            // An answer still on its way is the old server's: it must never land under the new one
            // (review H2), even while the new one's flag is still unknown (Start(null)).
            generation++
            reloadGeneration = generation
            ownFollows.clear()
            retainedUnfollowed.clear()
            readJob?.cancel()
            pageJob?.cancel()
            activityRead = ActivityRead.Idle
            answeredSort = current.query.sort
            searchJob?.cancel()
            legacyAuthorJob?.cancel()
            choiceJobs.values.forEach { it.cancel() }
            choiceJobs.clear()
            val pendingSettle = settleJobs.values.toList()
            settleJobs.clear()
            pendingSettle.forEach { it.cancel() }
            val pendingFollow = followJobs.values.toList()
            followJobs.clear()
            pendingFollow.forEach { it.cancel() }
            val persisted =
                if (serverId != null) {
                    filterRepository?.read(cleanReader, serverId) ?: ArtifactsHomeFilters.Default
                } else {
                    ArtifactsHomeFilters.Default
                }
            val rememberedSettled =
                if (serverId != null) {
                    settledSeenRepository?.read(cleanReader, serverId) ?: false
                } else {
                    false
                }
            val restoredQuery =
                ArtifactsHomeQuery(
                    place = persisted.place,
                    author = persisted.author,
                    q = persisted.search.trim(),
                    sort = current.query.sort,
                    relevant = persisted.relevant,
                )
            mutableState.value =
                ArtifactsHomeState(
                    serverId = serverId,
                    readerId = cleanReader,
                    reloadGeneration = reloadGeneration,
                    query = restoredQuery,
                    search = persisted.search,
                    authorActor = persisted.authorActor,
                    statusFilter = persisted.status,
                    settledEnabled = rememberedSettled,
                )
            shownQuery = null
            readFor = null
            if (serverId != null) {
                maybeResolveLegacyAuthor(serverId, persisted.author, persisted.authorActor)
            }
        }
        if (serverId == null) return
        read(keepRows = false, keepCounts = false)
    }

    /**
     * Fallback for legacy persisted filter entries that stored `author` without `authorActor`
     * (web `ArtifactAuthorFilter.tsx:83-100`, slock #16182): resolves the trigger's label/avatar
     * once on empty reload.
     */
    private fun maybeResolveLegacyAuthor(serverId: String, author: String?, persistedActor: ArtifactsHomeActor?) {
        if (author == null || author == "me" || author == "my-agents" || persistedActor != null) return
        legacyAuthorJob?.cancel()
        legacyAuthorJob =
            scope.launch {
                val load = api.authors(serverId, "")
                if (load !is ArtifactsHomeLoad.Ok) return@launch
                val latest = mutableState.value
                if (latest.serverId != serverId) return@launch
                val match = load.value.firstOrNull { it.filterValue?.lowercase() == author } ?: return@launch
                val key = match.filterValue?.lowercase() ?: return@launch
                // Never downgrade fresher live metadata already in knownAuthors.
                if (key !in latest.knownAuthors) {
                    mutableState.value = latest.copy(knownAuthors = latest.knownAuthors + (key to match))
                }
            }
    }

    private fun clearAllFilters() {
        searchJob?.cancel()
        legacyAuthorJob?.cancel()
        val current = mutableState.value
        val nextQuery = current.query.copy(q = "", place = null, author = null, relevant = false)
        val stateChanged =
            current.search.isNotEmpty() ||
                current.authorActor != null ||
                current.statusFilter != ArtifactsHomeStatusFilter.Default ||
                nextQuery != current.query
        mutableState.value =
            current.copy(
                search = "",
                authorActor = null,
                statusFilter = ArtifactsHomeStatusFilter.Default,
                query = nextQuery,
            )
        persistCurrentFilters()
        if (!stateChanged || nextQuery == current.query) return
        if (current.serverId == null || current.status == ArtifactsHomeStatus.Off) return
        read(keepRows = readFor == filterKey(current.serverId, nextQuery), sortOnly = false)
    }

    private fun search(text: String) {
        val clipped = clampArtifactsHomeSearch(text)
        mutableState.value = mutableState.value.copy(search = clipped)
        persistCurrentFilters()
        searchJob?.cancel()
        val settled = clipped.trim()
        if (settled == mutableState.value.query.q) return
        if (settled.isEmpty()) {
            applyQuery { it.copy(q = "") }
            return
        }
        searchJob =
            scope.launch {
                delay(SEARCH_DEBOUNCE_MILLIS)
                applyQuery { it.copy(q = settled) }
            }
    }

    private fun persistCurrentFilters() {
        val repo = filterRepository ?: return
        val current = mutableState.value
        val serverId = current.serverId ?: return
        repo.write(
            readerId = current.readerId,
            serverId = serverId,
            filters =
                ArtifactsHomeFilters(
                    place = current.query.place,
                    author = current.query.author,
                    authorActor = current.resolvedAuthorActor ?: current.authorActor,
                    status = current.statusFilter,
                    search = current.search,
                    relevant = current.query.relevant,
                ),
        )
    }

    private fun applyQuery(change: (ArtifactsHomeQuery) -> ArtifactsHomeQuery) {
        val current = mutableState.value
        val next = change(current.query)
        if (next == current.query) return
        mutableState.value = current.copy(query = next)
        if (current.serverId == null || current.status == ArtifactsHomeStatus.Off) return
        // Only a change of the sort alone goes back when it fails (review M2): a filter's failure
        // is the ordinary failed state, its sort untouched.
        val sortOnly = next.copy(sort = current.query.sort) == current.query
        read(keepRows = readFor == filterKey(current.serverId, next), sortOnly = sortOnly)
    }

    private fun filterKey(serverId: String?, query: ArtifactsHomeQuery): String = "$serverId|${query.copy(sort = ArtifactsHomeSort.Default)}"

    private fun read(keepRows: Boolean, keepCounts: Boolean = true, sortOnly: Boolean = false): Job? {
        val current = mutableState.value
        val serverId = current.serverId ?: return null
        val query = current.query
        val readGeneration = ++generation
        if (!keepRows) {
            reloadGeneration = readGeneration
            ownFollows.clear()
            retainedUnfollowed.clear()
        }
        val expectedReloadGeneration = reloadGeneration
        readFor = filterKey(serverId, query)
        pageJob?.cancel()
        mutableState.value =
            current.copy(
                status = ArtifactsHomeStatus.Loading,
                reloadGeneration = reloadGeneration,
                rows = if (keepRows) current.rows else null,
                nextCursor = if (keepRows) current.nextCursor else null,
                counts = if (keepCounts) current.counts else null,
                unfollowedIds = if (keepRows) current.unfollowedIds else emptySet(),
                paging = false,
                error = null,
            )
        val priority = if (keepRows) DataRequestPriority.Visible else DataRequestPriority.Foreground
        logger?.invoke("artifacts_home_read generation=$readGeneration keepRows=$keepRows sort=${query.sort.wire} relevant=${query.relevant} narrowed=${query.narrowed}")
        readJob?.cancel()
        val job =
            scope.launch {
                val load = api.page(serverId, query, cursor = null, priority = priority)
                if (readGeneration != generation) return@launch
                val latest = mutableState.value
                when (load) {
                    is ArtifactsHomeLoad.Ok -> {
                        shownQuery = query
                        shownGeneration = readGeneration
                        answeredSort = query.sort
                        val page = load.value
                        val mergedRows =
                            if (latest.reloadGeneration == expectedReloadGeneration) {
                                val withRetained =
                                    if (query.relevant && retainedUnfollowed.isNotEmpty()) {
                                        mergeRetainedUnfollowed(page.rows, retainedUnfollowed, ownFollows, latest.unfollowedIds)
                                    } else {
                                        page.rows
                                    }
                                withOwnFollows(withRetained, ownFollows)
                            } else {
                                page.rows
                            }
                        val rowsLoaded = mergedRows.isNotEmpty()
                        val rowHasSettled = rowsLoaded && mergedRows.any { it.summary.settledShown }
                        val nextSettledEnabled =
                            settledSeenRepository?.observe(latest.readerId, serverId, rowHasSettled, rowsLoaded)
                                ?: if (rowsLoaded) rowHasSettled else latest.settledEnabled
                        val mergedKnown = mergeKnownAuthors(latest.knownAuthors, mergedRows.map { it.author })
                        mutableState.value =
                            latest.copy(
                                status = ArtifactsHomeStatus.Ok,
                                rows = mergedRows,
                                nextCursor = page.nextCursor,
                                counts = page.counts ?: latest.counts,
                                countsNarrowed = if (page.counts != null) query.narrowed else latest.countsNarrowed,
                                rowsByOpened = !query.relevant && query.sort == ArtifactsHomeSort.Opened,
                                settledEnabled = nextSettledEnabled,
                                knownAuthors = mergedKnown,
                                extended = false,
                                error = null,
                            )
                    }
                    ArtifactsHomeLoad.Off -> mutableState.value = latest.copy(status = ArtifactsHomeStatus.Off, error = null)
                    is ArtifactsHomeLoad.Missing, is ArtifactsHomeLoad.Failed -> {
                        val error = load.errorOrNull() ?: ScreenError("artifacts_home_failed")
                        mutableState.value = latest.copy(status = ArtifactsHomeStatus.Failed, error = error)
                        // The sort control goes back with the rows, which that sort never replaced.
                        if (sortOnly && answeredSort != query.sort) {
                            mutableEvents.tryEmit(ArtifactsHomeEvent.SortFailed(error))
                            val back = answeredSort
                            onSortChosen(back)
                            mutableState.value = mutableState.value.copy(query = mutableState.value.query.copy(sort = back))
                            readFor = filterKey(serverId, mutableState.value.query)
                        }
                    }
                }
                logger?.invoke("artifacts_home_result generation=$readGeneration status=${mutableState.value.status} rows=${mutableState.value.rows?.size ?: -1}")
            }
        readJob = job
        return job
    }

    private fun setSettled(artifactId: String, settled: Boolean) {
        val current = mutableState.value
        val serverId = current.serverId ?: return
        if (current.status == ArtifactsHomeStatus.Off || artifactId in current.settlingIds) return
        mutableState.value = current.copy(settlingIds = current.settlingIds + artifactId)
        val job =
            scope.launch {
                val load = api.setSettled(serverId, artifactId, settled)
                val latest = mutableState.value
                if (latest.serverId != serverId) return@launch
                val remaining = latest.settlingIds - artifactId
                when (load) {
                    is ArtifactsHomeLoad.Ok -> {
                        val updatedRows =
                            latest.rows?.map { row ->
                                if (row.summary.id == artifactId) {
                                    row.copy(summary = row.summary.copy(settled = load.value.settled, settledShown = true))
                                } else {
                                    row
                                }
                            }
                        mutableState.value = latest.copy(rows = updatedRows, settlingIds = remaining)
                        if (!latest.extended) read(keepRows = true)
                    }
                    else -> {
                        val error = load.errorOrNull()
                        mutableState.value = latest.copy(settlingIds = remaining)
                        mutableEvents.tryEmit(
                            ArtifactsHomeEvent.SettleFailed(
                                forbidden = error?.serverCode == "FORBIDDEN",
                                error = error,
                            ),
                        )
                    }
                }
            }
        settleJobs[artifactId] = job
        job.invokeOnCompletion {
            if (settleJobs[artifactId] === job) {
                settleJobs.remove(artifactId)
            }
        }
    }

    private fun setFollowing(artifactId: String, following: Boolean) {
        val current = mutableState.value
        val serverId = current.serverId ?: return
        if (current.status == ArtifactsHomeStatus.Off || artifactId in current.followingIds) return
        if (following) {
            mutableEvents.tryEmit(ArtifactsHomeEvent.Followed(artifactId))
        }
        val startedReloadGeneration = current.reloadGeneration
        mutableState.value = current.copy(followingIds = current.followingIds + artifactId)
        val job =
            scope.launch {
                val load = api.setFollowing(serverId, artifactId, following)
                val latest = mutableState.value
                if (latest.serverId != serverId) return@launch
                val remaining = latest.followingIds - artifactId
                when (load) {
                    is ArtifactsHomeLoad.Ok -> {
                        val answered = load.value
                        mutableState.value = latest.copy(followingIds = remaining)
                        if (latest.reloadGeneration == startedReloadGeneration && latest.rows != null) {
                            applyOwnFollow(artifactId, answered)
                        }
                        onOwnFollowCommitted(serverId, artifactId, answered)
                        if (!answered) {
                            mutableEvents.tryEmit(ArtifactsHomeEvent.Unfollowed(artifactId))
                        } else {
                            mutableEvents.tryEmit(ArtifactsHomeEvent.Followed(artifactId))
                        }
                    }
                    else -> {
                        mutableState.value = latest.copy(followingIds = remaining)
                        mutableEvents.tryEmit(ArtifactsHomeEvent.FollowFailed(load.errorOrNull()))
                    }
                }
            }
        followJobs[artifactId] = job
        job.invokeOnCompletion {
            if (followJobs[artifactId] === job) {
                followJobs.remove(artifactId)
            }
        }
    }

    private fun applyOwnFollow(artifactId: String, following: Boolean) {
        val current = mutableState.value
        val rows = current.rows ?: return
        val canonical = artifactCanonicalId(artifactId)
        val existingIdx = rows.indexOfFirst { artifactCanonicalId(it.summary.id) == canonical }
        val matchedId =
            when {
                existingIdx >= 0 -> rows[existingIdx].summary.id
                else -> retainedUnfollowed.keys.firstOrNull { artifactCanonicalId(it) == canonical } ?: artifactId
            }
        if (ownFollows[matchedId] == following && (following || matchedId in current.unfollowedIds)) {
            return
        }
        ownFollows[matchedId] = following
        val nextUnfollowed =
            if (following) {
                current.unfollowedIds - matchedId
            } else {
                if (existingIdx >= 0) {
                    val afterId = if (existingIdx > 0) rows[existingIdx - 1].summary.id else null
                    retainedUnfollowed[matchedId] = RetainedUnfollowedItem(row = rows[existingIdx], afterId = afterId)
                }
                current.unfollowedIds + matchedId
            }
        val updatedRows = withOwnFollows(rows, mapOf(matchedId to following))
        if (updatedRows != rows || nextUnfollowed != current.unfollowedIds) {
            mutableState.value = current.copy(rows = updatedRows, unfollowedIds = nextUnfollowed)
        }
    }

    private fun loadMore() {
        val current = mutableState.value
        val serverId = current.serverId ?: return
        val cursor = current.nextCursor ?: return
        val query = shownQuery ?: return
        // Off while the list reads itself again: this cursor continues rows that are being replaced.
        if (current.paging || current.status == ArtifactsHomeStatus.Loading) return
        val forGeneration = shownGeneration
        mutableState.value = current.copy(paging = true)
        pageJob =
            scope.launch {
                val load = api.page(serverId, query, cursor = cursor, priority = DataRequestPriority.Visible)
                val latest = mutableState.value
                val stillShown = forGeneration == shownGeneration && latest.status != ArtifactsHomeStatus.Loading
                if (load !is ArtifactsHomeLoad.Ok || !stillShown || latest.rows == null) {
                    mutableState.value = latest.copy(paging = false)
                    if (load !is ArtifactsHomeLoad.Ok && stillShown) {
                        mutableEvents.tryEmit(ArtifactsHomeEvent.MoreFailed(load.errorOrNull()))
                    }
                    return@launch
                }
                val seen = latest.rows.mapTo(HashSet()) { it.summary.id }
                val mergedRows = (latest.rows + load.value.rows.filter { it.summary.id !in seen }).distinctBy { it.summary.id }
                val mergedKnown = mergeKnownAuthors(latest.knownAuthors, load.value.rows.map { it.author })
                mutableState.value =
                    latest.copy(
                        // Keyset paging may list one twice, even within a page (review L2).
                        rows = mergedRows,
                        nextCursor = load.value.nextCursor,
                        knownAuthors = mergedKnown,
                        paging = false,
                        extended = true,
                    )
            }
    }

    private fun onActivity(serverId: String) {
        val current = mutableState.value
        if (current.serverId != serverId || current.viewerOpen || current.extended) return
        if (current.status == ArtifactsHomeStatus.Off || current.status == ArtifactsHomeStatus.Idle) return
        when (activityRead) {
            ActivityRead.Idle -> runActivityRead()
            ActivityRead.Reading -> activityRead = ActivityRead.Queued
            ActivityRead.Queued -> Unit
        }
    }

    private fun runActivityRead() {
        activityRead = ActivityRead.Reading
        val job = read(keepRows = true)
        if (job == null) {
            activityRead = ActivityRead.Idle
            return
        }
        job.invokeOnCompletion {
            val again = activityRead == ActivityRead.Queued
            activityRead = ActivityRead.Idle
            if (again) scope.launch { onActivity(mutableState.value.serverId ?: return@launch) }
        }
    }

    private fun openChoices(kind: ArtifactsHomeChoiceKind) {
        updateChoices(kind) { it.copy(open = true, text = "") }
        if (choicesOf(kind).phase != ArtifactsHomeChoicesPhase.Missing) askChoices(kind, delayMillis = 0)
    }

    private fun closeChoices(kind: ArtifactsHomeChoiceKind) {
        choiceJobs.remove(kind)?.cancel()
        updateChoices(kind) { it.copy(open = false, text = "") }
    }

    private fun choicesQuery(kind: ArtifactsHomeChoiceKind, text: String) {
        updateChoices(kind) { it.copy(text = text.take(ARTIFACTS_HOME_SEARCH_MAX_CHARS)) }
        if (choicesOf(kind).phase != ArtifactsHomeChoicesPhase.Missing) askChoices(kind, delayMillis = CHOICES_DEBOUNCE_MILLIS)
    }

    /** web `useFilterChoices`: the route asked with the settled words; an older answer never lands over a newer one. */
    private fun askChoices(kind: ArtifactsHomeChoiceKind, delayMillis: Long) {
        val serverId = mutableState.value.serverId ?: return
        choiceJobs.remove(kind)?.cancel()
        if (choicesOf(kind).answeredFor != choicesOf(kind).text.trim()) {
            updateChoices(kind) { it.copy(phase = ArtifactsHomeChoicesPhase.Pending) }
        }
        choiceJobs[kind] =
            scope.launch {
                if (delayMillis > 0) delay(delayMillis)
                val q = choicesOf(kind).text.trim()
                when (kind) {
                    ArtifactsHomeChoiceKind.Author -> {
                        val load = api.authors(serverId, q)
                        if (load is ArtifactsHomeLoad.Ok && mutableState.value.serverId == serverId) {
                            mutableState.value =
                                mutableState.value.copy(knownAuthors = mergeKnownAuthors(mutableState.value.knownAuthors, load.value))
                        }
                        landChoices(kind, q, load) { choices, rows -> choices.copy(authors = rows, baseAuthors = if (q.isEmpty()) rows else choices.baseAuthors) }
                    }
                    ArtifactsHomeChoiceKind.Conversation -> {
                        val load = api.conversations(serverId, q)
                        landChoices(kind, q, load) { choices, rows ->
                            choices.copy(conversations = rows, baseConversations = if (q.isEmpty()) rows else choices.baseConversations)
                        }
                    }
                }
            }
    }

    private fun <T> landChoices(
        kind: ArtifactsHomeChoiceKind,
        q: String,
        load: ArtifactsHomeLoad<List<T>>,
        withRows: (ArtifactsHomeChoices, List<T>) -> ArtifactsHomeChoices,
    ) {
        updateChoices(kind) { choices ->
            when (load) {
                is ArtifactsHomeLoad.Ok -> withRows(choices, load.value).copy(phase = ArtifactsHomeChoicesPhase.Ok, answeredFor = q)
                is ArtifactsHomeLoad.Missing -> choices.copy(phase = ArtifactsHomeChoicesPhase.Missing, answeredFor = q)
                else -> choices.copy(phase = ArtifactsHomeChoicesPhase.Failed, answeredFor = q)
            }
        }
    }

    private fun choicesOf(kind: ArtifactsHomeChoiceKind): ArtifactsHomeChoices =
        when (kind) {
            ArtifactsHomeChoiceKind.Author -> mutableState.value.authorChoices
            ArtifactsHomeChoiceKind.Conversation -> mutableState.value.conversationChoices
        }

    private fun updateChoices(kind: ArtifactsHomeChoiceKind, change: (ArtifactsHomeChoices) -> ArtifactsHomeChoices) {
        val current = mutableState.value
        mutableState.value =
            when (kind) {
                ArtifactsHomeChoiceKind.Author -> current.copy(authorChoices = change(current.authorChoices))
                ArtifactsHomeChoiceKind.Conversation -> current.copy(conversationChoices = change(current.conversationChoices))
            }
    }

    private enum class ActivityRead { Idle, Reading, Queued }

    private data class RetainedUnfollowedItem(
        val row: ArtifactsHomeRow,
        val afterId: String?,
    )

    companion object {
        /** web `ALL_SEARCH_DEBOUNCE_MS`. */
        const val SEARCH_DEBOUNCE_MILLIS: Long = 300

        /** web `useFilterChoices` `SEARCH_DEBOUNCE_MS`. */
        const val CHOICES_DEBOUNCE_MILLIS: Long = 250

        private fun mergeRetainedUnfollowed(
            rows: List<ArtifactsHomeRow>,
            retained: Map<String, RetainedUnfollowedItem>,
            follows: Map<String, Boolean>,
            unfollowedIds: Set<String>,
        ): List<ArtifactsHomeRow> {
            if (retained.isEmpty()) return rows
            val present = rows.mapTo(HashSet()) { it.summary.id }
            val missing = mutableListOf<Pair<String, RetainedUnfollowedItem>>()
            for ((id, entry) in retained) {
                if (id !in present && (id in unfollowedIds || follows[id] == true)) {
                    missing += id to entry
                }
            }
            if (missing.isEmpty()) return rows
            val merged = rows.toMutableList()
            for ((id, entry) in missing) {
                val clearedNews =
                    if (id in unfollowedIds && !entry.row.news.mentioned) {
                        ArtifactsHomeNews()
                    } else {
                        entry.row.news
                    }
                val restored = entry.row.copy(news = clearedNews)
                val anchorIdx = if (entry.afterId == null) -1 else merged.indexOfFirst { it.summary.id == entry.afterId }
                merged.add(anchorIdx + 1, restored)
                present += id
            }
            return merged
        }

        private fun withOwnFollows(
            rows: List<ArtifactsHomeRow>,
            follows: Map<String, Boolean>,
        ): List<ArtifactsHomeRow> {
            if (follows.isEmpty()) return rows
            return rows.map { row ->
                val following = follows[row.summary.id]
                if (following == null || row.summary.following == following) {
                    row
                } else {
                    row.copy(summary = row.summary.copy(following = following))
                }
            }
        }
    }
}

private fun mergeKnownAuthors(
    existing: Map<String, ArtifactsHomeActor>,
    incoming: List<ArtifactsHomeActor>,
): Map<String, ArtifactsHomeActor> {
    if (incoming.isEmpty()) return existing
    val next = LinkedHashMap(existing)
    for (actor in incoming) {
        val key = actor.filterValue?.lowercase() ?: continue
        next[key] = actor
    }
    return next
}

private fun ArtifactsHomeLoad<*>.errorOrNull(): ScreenError? =
    when (this) {
        is ArtifactsHomeLoad.Failed -> error
        is ArtifactsHomeLoad.Missing -> error
        else -> null
    }

@RaftCoreInternalApi enum class ArtifactsHomeStatus { Idle, Loading, Ok, Failed, Off }

@RaftCoreInternalApi enum class ArtifactsHomeChoiceKind { Author, Conversation }

/** web `useFilterChoices` phases: `Missing` is a 404 from an older server. */
@RaftCoreInternalApi enum class ArtifactsHomeChoicesPhase { Idle, Pending, Ok, Failed, Missing }

/** One filter's choices: the latest answer, the answer to no search, and what the field holds. */
@RaftCoreInternalApi data class ArtifactsHomeChoices(
    val open: Boolean = false,
    val text: String = "",
    val phase: ArtifactsHomeChoicesPhase = ArtifactsHomeChoicesPhase.Idle,
    val answeredFor: String? = null,
    val authors: List<ArtifactsHomeActor> = emptyList(),
    val baseAuthors: List<ArtifactsHomeActor> = emptyList(),
    val conversations: List<ArtifactsHomeConversation> = emptyList(),
    val baseConversations: List<ArtifactsHomeConversation> = emptyList(),
) {
    /** The answer is for the words in the field now; else what is loaded is filtered here at once. */
    val answered: Boolean get() = phase == ArtifactsHomeChoicesPhase.Ok && answeredFor == text.trim()
}

@RaftCoreInternalApi data class ArtifactsHomeState(
    val serverId: String? = null,
    val readerId: String? = null,
    /** Increments only on `keepRows = false` reloads (filter/server resets), not background refreshes (slock #16397). */
    val reloadGeneration: Int = 0,
    val status: ArtifactsHomeStatus = ArtifactsHomeStatus.Idle,
    /** The settled query the list is read for. */
    val query: ArtifactsHomeQuery = ArtifactsHomeQuery(),
    /** What the search field holds now (the query's `q` follows it after a pause). */
    val search: String = "",
    /** The chosen author, persisted so empty reloads keep rendering their name/avatar. */
    val authorActor: ArtifactsHomeActor? = null,
    /** Live author metadata from loaded rows, choices, or legacy fallback; wins over older persisted [authorActor]. */
    val knownAuthors: Map<String, ArtifactsHomeActor> = emptyMap(),
    /** Client-side settled status filter (default: Unsettled). */
    val statusFilter: ArtifactsHomeStatusFilter = ArtifactsHomeStatusFilter.Default,
    /** Whether settled support is active for this server (observed from rows or remembered across empty reloads). */
    val settledEnabled: Boolean = false,
    /** Artifact ids whose Settle / Unsettle write is currently in flight. */
    val settlingIds: Set<String> = emptySet(),
    /** Artifact ids whose Follow / Unfollow write is currently in flight (slock #16357). */
    val followingIds: Set<String> = emptySet(),
    /** Artifacts unfollowed in place on the current view scope until a filter/server reload (slock #16357/#16397). */
    val unfollowedIds: Set<String> = emptySet(),
    /** The rows on screen; null before the first page (the skeleton). */
    val rows: List<ArtifactsHomeRow>? = null,
    val nextCursor: String? = null,
    /** The latest first page's numbers (later pages send none), kept through a read in the same server. */
    val counts: ArtifactsHomeCounts? = null,
    /** Whether [counts] were read with any filter on ("5 of 24"). */
    val countsNarrowed: Boolean = false,
    /** The rows on screen are in the order they were opened: their time says when, not when updated. */
    val rowsByOpened: Boolean = false,
    val paging: Boolean = false,
    /** Load More added rows: a read of the first page would drop them. */
    val extended: Boolean = false,
    val viewerOpen: Boolean = false,
    val error: ScreenError? = null,
    val authorChoices: ArtifactsHomeChoices = ArtifactsHomeChoices(),
    val conversationChoices: ArtifactsHomeChoices = ArtifactsHomeChoices(),
) {
    /** The list is reading itself again over rows already shown. */
    val refreshing: Boolean get() = status == ArtifactsHomeStatus.Loading && rows != null

    /** Live author metadata for [query.author], falling back to persisted [authorActor]. */
    val resolvedAuthorActor: ArtifactsHomeActor?
        get() {
            val author = query.author?.lowercase() ?: return null
            return knownAuthors[author] ?: authorActor?.takeIf { it.filterValue?.lowercase() == author }
        }

    /** True when the Status picker is active and off its default (`Unsettled`). */
    val statusActive: Boolean get() = settledEnabled && statusFilter != ArtifactsHomeStatusFilter.Default

    /** Any non-search filter (Conversation, Author, Relevant, or non-default Status). */
    val hasNonSearchFilter: Boolean get() = query.place != null || query.author != null || query.relevant || statusActive

    /** Any of the 5 filters (including live search text) is non-default: reveals toolbar Clear all. */
    val hasToolbarFilter: Boolean get() = hasNonSearchFilter || search.trim().isNotEmpty()

    /** The author choices to show: the server's answer, or (an older server) the authors on screen. */
    val shownAuthorChoices: List<ArtifactsHomeActor>
        get() {
            val choices = authorChoices
            val words = choices.text.trim().lowercase()
            if (choices.phase == ArtifactsHomeChoicesPhase.Missing) {
                return artifactsHomeAuthorsOnScreen(rows.orEmpty().map { it.author }).filter { it.matches(words) }
            }
            if (choices.answered) return choices.authors
            return (choices.authors + choices.baseAuthors).distinctBy { it.filterValue }.filter { it.matches(words) }
        }

    val shownConversationChoices: List<ArtifactsHomeConversation>
        get() {
            val choices = conversationChoices
            if (choices.answered) return choices.conversations
            val words = choices.text.trim().lowercase().removePrefix("#")
            return (choices.conversations + choices.baseConversations).distinctBy { it.id }.filter {
                it.name.lowercase().contains(words) || (it.peerDisplayName ?: "").lowercase().contains(words)
            }
        }
}

private fun ArtifactsHomeActor.matches(words: String): Boolean =
    words.isEmpty() || name.lowercase().contains(words) || displayName.lowercase().contains(words)

/** web `authorsOnScreen`: the authors of the rows on screen, most frequent first, each once. */
@RaftCoreInternalApi fun artifactsHomeAuthorsOnScreen(actors: List<ArtifactsHomeActor>): List<ArtifactsHomeActor> =
    actors
        .filter { it.filterValue != null && it.gone == null }
        .groupBy { it.filterValue }
        .values
        .sortedByDescending { it.size }
        .take(ARTIFACTS_HOME_AUTHORS_LIMIT)
        .map { it.first() }

/** web `ARTIFACT_DASHBOARD_AUTHORS_LIMIT`. */
@RaftCoreInternalApi const val ARTIFACTS_HOME_AUTHORS_LIMIT: Int = 50

@RaftCoreInternalApi sealed interface ArtifactsHomeIntent {
    /** The page's server (with the flag on) and signed-in reader; null server leaves the list unread. */
    data class Start(val serverId: String?, val readerId: String? = null) : ArtifactsHomeIntent

    /** The one freshness signal (#198): re-read what the page shows, silently. */
    data class CatchUp(val serverId: String) : ArtifactsHomeIntent
    data class Search(val text: String) : ArtifactsHomeIntent
    data class SetPlace(val place: ArtifactsHomePlace?) : ArtifactsHomeIntent
    data class SetAuthor(val author: ArtifactsHomeActor?) : ArtifactsHomeIntent
    data class SetStatus(val status: ArtifactsHomeStatusFilter) : ArtifactsHomeIntent
    data class SetRelevant(val relevant: Boolean) : ArtifactsHomeIntent
    data class SetSort(val sort: ArtifactsHomeSort) : ArtifactsHomeIntent
    data class SetSettled(val artifactId: String, val settled: Boolean) : ArtifactsHomeIntent
    data class SetFollowing(val artifactId: String, val following: Boolean) : ArtifactsHomeIntent

    /** Empty state's clear: resets all 5 persisted filters atomically (web `clearFilters`). */
    data object ClearSearchAndFilters : ArtifactsHomeIntent

    /** Toolbar Clear all: resets all 5 persisted filters (search, place, author, status, relevant) atomically. */
    data object ClearFilters : ArtifactsHomeIntent
    data object Retry : ArtifactsHomeIntent
    data object LoadMore : ArtifactsHomeIntent

    /** `activity:changed` for [serverId]. */
    data class ActivityChanged(val serverId: String) : ArtifactsHomeIntent
    data object ViewerOpened : ArtifactsHomeIntent
    data object ViewerClosed : ArtifactsHomeIntent
    data class OpenChoices(val kind: ArtifactsHomeChoiceKind) : ArtifactsHomeIntent
    data class CloseChoices(val kind: ArtifactsHomeChoiceKind) : ArtifactsHomeIntent
    data class ChoicesQuery(val kind: ArtifactsHomeChoiceKind, val text: String) : ArtifactsHomeIntent
    data class RetryChoices(val kind: ArtifactsHomeChoiceKind) : ArtifactsHomeIntent
}

/** One-off notices (web toasts). */
@RaftCoreInternalApi sealed interface ArtifactsHomeEvent {
    data class SortFailed(val error: ScreenError) : ArtifactsHomeEvent
    data class MoreFailed(val error: ScreenError?) : ArtifactsHomeEvent
    data class SettleFailed(val forbidden: Boolean, val error: ScreenError?) : ArtifactsHomeEvent
    data class Unfollowed(val artifactId: String) : ArtifactsHomeEvent
    data class Followed(val artifactId: String) : ArtifactsHomeEvent
    data class FollowFailed(val error: ScreenError?) : ArtifactsHomeEvent
}
