package build.raft.app.data

import build.raft.app.withKnownLatestDelegatedLabelsFrom
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.app.resolveInboxAnchorMessageId
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.TimeSource

private const val HomeSavedPageSize = 100
private const val HomeSearchPageSize = 30
private const val SearchDebounceMillis = 250L
private const val SearchPersonalizationPrewarmTimeoutMillis = 250L

internal class SharedHomeSearchSavedStore(
    private val authStore: RaftAuthSessionStore,
    private val savedRepository: SavedRepository,
    private val searchRepository: SearchRepository,
    private val memberRepository: MemberRepository? = null,
    private val serverMembersStore: SharedServerMembersStore? = null,
    private val savedStateCache: SharedHomeSavedStateCache? = null,
    private val scope: CoroutineScope,
    private val timeRangeBoundsProvider: (SearchTimeRange) -> SearchIsoBounds = { SearchIsoBounds() },
    private val searchDebounceMillis: Long = SearchDebounceMillis,
    private val readReceiptCoordinator: ReadReceiptCoordinator? = null,
    private val readReceiptOwnerFactory: ((String, String) -> ReadReceiptOwner)? = null,
    private val diagnosticsLogger: (String) -> Unit = {},
    private val personalizationRepository: SearchPersonalizationRepository? = null,
    private val nowMillis: () -> Long = ::defaultNowMillis,
) {
    private val _state = MutableStateFlow(SharedHomeSearchSavedState())
    val state: StateFlow<SharedHomeSearchSavedState> = _state.asStateFlow()

    private var savedJob: Job? = null
    private var searchJob: Job? = null
    private var searchDebounceJob: Job? = null
    private var senderFiltersJob: Job? = null
    private var personalizationJob: Job? = null
    private var personalizationWriteJob: Job? = null
    private var savedRequestToken: Long = 0L
    private var searchRequestToken: Long = 0L
    private var senderFiltersRequestToken: Long = 0L
    private var personalizationRequestToken: Long = 0L
    private var senderFiltersOwner: SenderFiltersOwner? = null
    private var personalizationOwner: SearchPersonalizationOwner? = null
    private var personalizationPrewarmedOwner: SearchPersonalizationOwner? = null
    private var readPredictionRevisions: Map<ChannelId, Long> = emptyMap()
    private var savedOwner: SavedStateOwner? = null
    // Server-scoped Search state (message rows, sender filters, channel/sender selections) belongs
    // to this owner. The Search tab outlives a server switch, so a change of owner drops all of it.
    private var searchServerOwner: SearchServerOwner? = null
    private var searchOpened = false
    private var authEventsJob: Job? = null
    private val pendingSavedUnsaves = mutableMapOf<MessageId, PendingSavedUnsave>()
    private val savedOrderRanks = mutableMapOf<MessageId, Long>()
    private var nextSavedOrderRank = 0L

    init {
        readReceiptCoordinator?.let { coordinator ->
            scope.launch {
                coordinator.predictions.collect { prediction ->
                    applyReadReceiptPrediction(prediction)
                }
            }
            scope.launch {
                coordinator.failures.collect { failure ->
                    handleReadReceiptFailure(failure.request)
                }
            }
        }
        runCatching {
            runBlocking {
                withTimeout(SearchPersonalizationPrewarmTimeoutMillis) {
                    adoptSearchServerOwner(SearchServerOwner.from(authStore.readSession()), reload = false)
                }
            }
        }
        (authStore as? RaftAuthSessionEventSource)?.let { authEvents ->
            authEventsJob =
                scope.launch {
                    authEvents.authSessionEvents.collect { event ->
                        if (event !is AuthSessionEvent.TokensRotated) {
                            reconcileSearchServerOwner()
                            refreshSearchPersonalization()
                        }
                    }
                }
        }
        if (personalizationRepository != null) {
            prewarmSearchPersonalization()
        }
    }

    /** Re-reads the session's server and drops server-scoped Search state when it changed. */
    private suspend fun reconcileSearchServerOwner(): SearchServerOwner? {
        val owner = SearchServerOwner.from(runCatching { authStore.readSession() }.getOrNull())
        adoptSearchServerOwner(owner, reload = true)
        return owner
    }

    /**
     * Adopts [owner] as the server Search results belong to. When it replaces a different owner
     * (a server or account switch), every server-scoped part of the state is dropped and any
     * in-flight search / sender-filter request is invalidated, so nothing from the previous server
     * can reach the new one. With [reload], the open Search re-runs against the new server.
     */
    private fun adoptSearchServerOwner(owner: SearchServerOwner?, reload: Boolean) {
        val previous = searchServerOwner
        if (owner == previous) return
        searchServerOwner = owner
        if (previous == null) {
            _state.value = _state.value.copy(search = _state.value.search.copy(serverId = owner?.serverId?.value))
            return
        }
        diagnosticsLogger(
            "search_server_owner_changed from=${previous.serverId.value} to=${owner?.serverId?.value ?: "_"}"
        )
        searchJob?.cancel()
        searchJob = null
        searchDebounceJob?.cancel()
        searchDebounceJob = null
        senderFiltersJob?.cancel()
        senderFiltersJob = null
        searchRequestToken++
        senderFiltersRequestToken++
        senderFiltersOwner = null
        readPredictionRevisions = emptyMap()
        _state.value =
            _state.value.copy(
                search =
                    _state.value.search.copy(
                        serverId = owner?.serverId?.value,
                        selectedSenderId = null,
                        selectedChannelId = null,
                        senderFilters = emptyList(),
                        senderFiltersLoading = false,
                        senderFiltersErrorCode = null,
                        items = emptyList(),
                        totalCount = 0,
                        searched = false,
                        loading = false,
                        refreshing = false,
                        loadingMore = false,
                        querySearchPending = false,
                        empty = false,
                        hasMore = false,
                        nextOffset = null,
                        nextCursor = null,
                        errorCode = null,
                        noPermissionCode = null,
                        resultQuery = null,
                    )
            )
        if (!reload || owner == null) return
        if (searchOpened) loadSenderFilters()
        if (_state.value.search.hasMessageSearchIntent()) runSearch(refresh = true)
    }

    fun dispatch(intent: SharedHomeSearchSavedIntent) {
        when (intent) {
            SharedHomeSearchSavedIntent.ClearSavedSummary -> clearSavedSummary()
            SharedHomeSearchSavedIntent.OpenSearch -> {
                searchOpened = true
                loadSenderFilters()
                refreshSearchPersonalization()
            }
            SharedHomeSearchSavedIntent.OpenSaved -> {
                val saved = _state.value.saved
                if (!saved.loading && !saved.refreshing) loadSaved(refresh = true, pullRefresh = false)
            }
            SharedHomeSearchSavedIntent.RefreshSaved -> loadSaved(refresh = true, pullRefresh = false)
            SharedHomeSearchSavedIntent.PullRefreshSaved -> loadSaved(refresh = true, pullRefresh = true)
            SharedHomeSearchSavedIntent.LoadMoreSaved -> loadSaved(refresh = false)
            is SharedHomeSearchSavedIntent.Unsave -> unsave(intent.item)
            is SharedHomeSearchSavedIntent.SearchQueryChanged -> {
                _state.value =
                    _state.value.copy(
                        search =
                            _state.value.search.copy(
                                query = intent.query,
                                querySearchPending = intent.query.trim().isNotBlank()
                            )
                    )
                scheduleSearchRefresh()
            }
            SharedHomeSearchSavedIntent.SubmitSearch -> runSearch(refresh = true)
            SharedHomeSearchSavedIntent.RefreshSearch -> runSearch(refresh = true, keepLoadedDepth = true)
            SharedHomeSearchSavedIntent.LoadMoreSearch -> runSearch(refresh = false)
            is SharedHomeSearchSavedIntent.PreselectSearchChannel -> {
                _state.value =
                    _state.value.copy(search = _state.value.search.copy(selectedChannelId = intent.channelId))
            }
            is SharedHomeSearchSavedIntent.SetSearchChannel -> {
                _state.value =
                    _state.value.copy(search = _state.value.search.copy(selectedChannelId = intent.channelId))
                scheduleSearchRefresh()
            }
            is SharedHomeSearchSavedIntent.SetSearchSender -> {
                // One sender at most, like the web: picking another replaces the current one.
                val currentSearch = _state.value.search
                val senderId = intent.senderId?.trim()?.takeIf { it.isNotBlank() }
                val senderKind = senderId?.let { id -> currentSearch.senderFilters.firstOrNull { it.id == id }?.kind }
                _state.value =
                    _state.value.copy(
                        search =
                            currentSearch.copy(
                                selectedSenderId = senderId,
                                selectedScopes =
                                    if (senderKind != null) {
                                        currentSearch.selectedScopes.withSenderKindConflictResolved(senderKind)
                                    } else {
                                        currentSearch.selectedScopes
                                    },
                                myMessagesOnly = false
                            )
                    )
                scheduleSearchRefresh()
            }
            is SharedHomeSearchSavedIntent.SetSearchSort -> {
                _state.value = _state.value.copy(search = _state.value.search.copy(sort = intent.sort))
                scheduleSearchRefresh()
            }
            is SharedHomeSearchSavedIntent.SetSearchTimeRange -> {
                _state.value = _state.value.copy(search = _state.value.search.copy(timeRange = intent.range))
                scheduleSearchRefresh()
            }
            is SharedHomeSearchSavedIntent.ToggleSearchScope -> {
                val currentSearch = _state.value.search
                val selectedScopes = currentSearch.selectedScopes.toggleSearchScope(intent.scope)
                val exclusiveKind = selectedScopes.exclusiveSenderKind()
                _state.value =
                    _state.value.copy(
                        search =
                            currentSearch.copy(
                                selectedScopes = selectedScopes,
                                selectedSenderId =
                                    currentSearch.selectedSenderId?.takeIf { senderId ->
                                        exclusiveKind == null ||
                                            currentSearch.senderFilters.firstOrNull { it.id == senderId }?.kind
                                                ?.let { it == exclusiveKind } != false
                                    },
                                myMessagesOnly = currentSearch.myMessagesOnly && exclusiveKind != MemberKind.Agent
                            )
                    )
                scheduleSearchRefresh()
            }
            SharedHomeSearchSavedIntent.ClearSearchScopes -> {
                _state.value = _state.value.copy(search = _state.value.search.copy(selectedScopes = emptyList()))
                scheduleSearchRefresh()
            }
            is SharedHomeSearchSavedIntent.SetMyMessagesOnly -> {
                _state.value =
                    _state.value.copy(
                        search =
                            _state.value.search.copy(
                                myMessagesOnly = intent.enabled,
                                selectedSenderId = if (intent.enabled) null else _state.value.search.selectedSenderId,
                                selectedScopes =
                                    if (intent.enabled) {
                                        _state.value.search.selectedScopes.withSenderKindConflictResolved(MemberKind.Human)
                                    } else {
                                        _state.value.search.selectedScopes
                                    }
                            )
                    )
                scheduleSearchRefresh()
            }
            SharedHomeSearchSavedIntent.ClearSearchFilters -> {
                _state.value =
                    _state.value.copy(
                        search =
                            _state.value.search.copy(
                                myMessagesOnly = false,
                                selectedSenderId = null,
                                selectedScopes = emptyList(),
                                selectedChannelId = null,
                                timeRange = SearchTimeRange.Any
                            )
                    )
                scheduleSearchRefresh()
            }
            is SharedHomeSearchSavedIntent.RecordSuccessfulSearchOpen -> {
                recordSuccessfulSearchOpen(intent)
            }
            is SharedHomeSearchSavedIntent.RemoveSearchHistoryEntry -> {
                mutateSearchPersonalization { repository, owner, timestamp ->
                    repository.removeHistoryEntry(owner, intent.query, timestamp)
                }
            }
            SharedHomeSearchSavedIntent.ClearSearchHistory -> {
                mutateSearchPersonalization { repository, owner, timestamp ->
                    repository.clearHistory(owner, timestamp)
                }
            }
        }
    }

    private fun clearSavedSummary() {
        savedJob?.cancel()
        savedJob = null
        savedRequestToken++
        pendingSavedUnsaves.clear()
        clearSavedOrderRanks()
        savedOwner = null
        _state.value = _state.value.copy(saved = SharedHomeSavedPanelState())
    }

    fun clear() {
        savedJob?.cancel()
        searchJob?.cancel()
        searchDebounceJob?.cancel()
        senderFiltersJob?.cancel()
        personalizationJob?.cancel()
        personalizationWriteJob?.cancel()
        authEventsJob?.cancel()
        authEventsJob = null
        savedJob = null
        searchJob = null
        searchDebounceJob = null
        senderFiltersJob = null
        personalizationJob = null
        personalizationWriteJob = null
        savedRequestToken++
        searchRequestToken++
        senderFiltersRequestToken++
        personalizationRequestToken++
        senderFiltersOwner = null
        personalizationOwner = null
        personalizationPrewarmedOwner = null
        pendingSavedUnsaves.clear()
        clearSavedOrderRanks()
        savedOwner = null
    }

    private suspend fun applyReadReceiptPrediction(prediction: ReadReceiptPrediction) {
        val session = authStore.readSession() ?: return
        val serverId = session.currentServerId ?: return
        val owner = readReceiptOwnerFactory?.invoke(serverId, session.user.id) ?: return
        if (owner != prediction.owner) return
        val previousRevision = readPredictionRevisions[prediction.channelId]
        if (previousRevision != null && previousRevision >= prediction.revision) return
        readPredictionRevisions = readPredictionRevisions + (prediction.channelId to prediction.revision)
        fun List<InboxItem>.clearPredictedUnread(): List<InboxItem> =
            map { item ->
                if (item.readReceiptChannelId() == prediction.channelId && item.unreadCount > 0) {
                    item.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false)
                } else {
                    item
                }
            }
        _state.value =
            _state.value.copy(
                saved = _state.value.saved.copy(items = _state.value.saved.items.clearPredictedUnread()),
                search = _state.value.search.copy(items = _state.value.search.items.clearPredictedUnread())
            )
    }

    private suspend fun handleReadReceiptFailure(request: ReadReceiptRequest) {
        val session = authStore.readSession() ?: return
        val serverId = session.currentServerId ?: return
        val owner = readReceiptOwnerFactory?.invoke(serverId, session.user.id) ?: return
        if (owner != request.owner || readPredictionRevisions[request.channelId] != request.revision) return
        readPredictionRevisions = readPredictionRevisions - request.channelId
        if (_state.value.saved.loaded) loadSaved(refresh = true, pullRefresh = false)
        if (_state.value.search.searched) runSearch(refresh = true, keepLoadedDepth = true)
    }

    private fun loadSenderFilters() {
        if (memberRepository == null && serverMembersStore == null) return
        senderFiltersJob?.cancel()
        val requestToken = ++senderFiltersRequestToken
        senderFiltersJob =
            scope.launch {
                val startedAt = TimeSource.Monotonic.markNow()
                var requestOwner: SenderFiltersOwner? = null
                try {
                    val session = authStore.readSession()
                        ?: return@launch setSenderFiltersErrorIfActive(requestToken, "unauthenticated")
                    val serverId = session.currentServerId?.let(::ServerId)
                        ?: return@launch setSenderFiltersErrorIfActive(requestToken, "server_missing")
                    val owner = SenderFiltersOwner(serverId = serverId, principalId = session.user.id)
                    requestOwner = owner
                    if (requestToken != senderFiltersRequestToken) return@launch
                    val ownerChanged = senderFiltersOwner != null && senderFiltersOwner != owner
                    senderFiltersOwner = owner
                    _state.value =
                        _state.value.copy(
                            search =
                                _state.value.search.copy(
                                    senderFilters =
                                        if (ownerChanged) emptyList() else _state.value.search.senderFilters,
                                    senderFiltersLoading = true,
                                    senderFiltersErrorCode = null,
                                )
                        )
                    val result = loadSearchSenderFilters(serverId, DataRequestPriority.Visible, session.user.id)
                    diagnosticsLogger(
                        "store_load domain=home_search_sender_filters result=${result.senderFiltersDiagnosticsResult()} " +
                            "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                    )
                    if (!senderFiltersRequestStillActive(requestToken, owner)) return@launch
                    when (result) {
                        is DataResult.Content ->
                            _state.value =
                                _state.value.copy(
                                    search =
                                        _state.value.search.copy(
                                            senderFilters = result.data,
                                            senderFiltersLoading = false,
                                            senderFiltersErrorCode = null
                                        )
                                )
                        is DataResult.Empty ->
                            _state.value =
                                _state.value.copy(
                                    search =
                                        _state.value.search.copy(
                                            senderFilters = emptyList(),
                                            senderFiltersLoading = false,
                                            senderFiltersErrorCode = null
                                        )
                                )
                        is DataResult.Error -> setSenderFiltersErrorIfActive(requestToken, result.error.code)
                        is DataResult.NoPermission -> setSenderFiltersErrorIfActive(requestToken, result.blocker.code)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    val owner = requestOwner
                    if (owner == null || senderFiltersRequestStillActive(requestToken, owner)) {
                        setSenderFiltersErrorIfActive(requestToken, "sender_filters_network")
                    }
                    diagnosticsLogger(
                        "store_load domain=home_search_sender_filters result=exception code=sender_filters_network " +
                            "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                    )
                }
            }
    }

    private fun loadSaved(
        refresh: Boolean,
        pullRefresh: Boolean = false
    ) {
        val current = _state.value.saved
        if (refresh && (current.loading || current.refreshing)) return
        if (!refresh && (current.loadingMore || !current.hasMore)) return
        savedJob?.cancel()
        val requestToken = ++savedRequestToken
        val excludedMessageIds = pendingSavedUnsaves.keys.toSet()
        savedJob =
            scope.launch {
                val startedAt = TimeSource.Monotonic.markNow()
                val previous = _state.value.saved
                val refreshInPlace = refresh && (previous.loaded || previous.items.isNotEmpty())
                _state.value =
                    _state.value.copy(
                        saved =
                            if (refresh) {
                                previous.copy(
                                    loading = !refreshInPlace,
                                    refreshing = refreshInPlace,
                                    pullRefreshing = refreshInPlace && pullRefresh,
                                    errorCode = null,
                                    noPermissionCode = null
                                )
                            } else {
                                previous.copy(pullRefreshing = false, loadingMore = true, errorCode = null, noPermissionCode = null)
                            }
                    )
                try {
                    val session = authStore.readSession() ?: return@launch setSavedErrorIfActive(requestToken, "unauthenticated")
                    val serverId = session.currentServerId?.let(::ServerId)
                        ?: return@launch setSavedErrorIfActive(requestToken, "server_missing")
                    val owner = SavedStateOwner(serverId, session.user.id)
                    if (!isActiveSavedRequest(requestToken)) return@launch
                    adoptSavedOwner(owner)
                    if (refresh && !previous.loaded && previous.items.isEmpty()) {
                        savedStateCache?.read(serverId.value, session.user.id)?.let { cached ->
                            if (!isActiveSavedRequest(requestToken)) return@let
                            synchronizeSavedOrderRanks(cached.items, replace = true)
                            _state.value =
                                _state.value.copy(
                                    saved =
                                        cached.copy(
                                            items = cached.items.withoutSavedMessages(excludedMessageIds),
                                            totalCount = cached.totalCount.withExcludedSavedCount(cached.items, excludedMessageIds),
                                            loading = false,
                                            refreshing = true,
                                            pullRefreshing = false,
                                            loadingMore = false,
                                            errorCode = null,
                                            noPermissionCode = null
                                        )
                                )
                        }
                    }
                    val result =
                        if (refresh) {
                            savedRepository.loadInitial(serverId, HomeSavedPageSize)
                        } else {
                            savedRepository.loadMore(serverId, previous.nextOffset ?: previous.items.size, HomeSavedPageSize)
                        }
                    if (isActiveSavedRequest(requestToken)) {
                        applySavedResult(
                            result = result,
                            append = !refresh,
                            owner = owner,
                            excludedMessageIds = excludedMessageIds
                        )
                        diagnosticsLogger(
                            "store_load domain=saved refresh=$refresh append=${!refresh} " +
                                "result=${result.savedDiagnosticsResult()} durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                        )
                    } else {
                        diagnosticsLogger(
                            "store_load domain=saved refresh=$refresh append=${!refresh} result=stale_request " +
                                "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                        )
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    setSavedErrorIfActive(requestToken, "saved_network")
                    diagnosticsLogger(
                        "store_load domain=saved refresh=$refresh append=${!refresh} result=exception code=saved_network " +
                            "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                    )
                }
            }
    }

    private fun scheduleSearchRefresh() {
        searchDebounceJob?.cancel()
        val current = _state.value.search
        if (!current.hasMessageSearchIntent()) {
            searchDebounceJob = null
            clearMessageSearchResults()
            return
        }
        searchDebounceJob =
            scope.launch {
                if (searchDebounceMillis > 0) delay(searchDebounceMillis)
                runSearch(refresh = true, cancelDebounce = false)
            }
    }

    private fun runSearch(
        refresh: Boolean,
        cancelDebounce: Boolean = true,
        // A refresh of the same query and filters (page reappears, foreground, retry) reloads as
        // many rows as are shown, so a reader scrolled past the first page keeps their place
        // (nav spec §9.7 / §10.2: the Search tab keeps its results and scroll). A new query,
        // filter or sort starts from the first page.
        keepLoadedDepth: Boolean = false
    ) {
        if (cancelDebounce) {
            searchDebounceJob?.cancel()
            searchDebounceJob = null
        }
        val current = _state.value.search
        if (!current.hasMessageSearchIntent()) {
            clearMessageSearchResults()
            return
        }
        if (!refresh && (current.loadingMore || current.refreshing || !current.hasMore)) return
        searchJob?.cancel()
        val requestToken = ++searchRequestToken
        searchJob =
            scope.launch {
                val startedAt = TimeSource.Monotonic.markNow()
                val previous = _state.value.search
                // `searched` becomes true as soon as the first request starts. A filter/sort
                // change can replace that request before it has produced any rows, so using
                // `searched` as proof of visible content turns the replacement into
                // refreshing=true + loading=false + items=[], which the UI presents as an
                // empty result while the new request is still running. Only a real retained
                // snapshot qualifies for stale-while-refresh presentation.
                val refreshInPlace = refresh && previous.items.isNotEmpty()
                _state.value =
                    _state.value.copy(
                        search =
                            if (refresh) {
                                previous.copy(
                                    loading = !refreshInPlace,
                                    refreshing = refreshInPlace,
                                    querySearchPending = false,
                                    searched = true,
                                    errorCode = null,
                                    noPermissionCode = null
                                )
                            } else {
                                previous.copy(loadingMore = true, searched = true, errorCode = null, noPermissionCode = null)
                            }
                    )
                try {
                    val session = authStore.readSession()
                        ?: return@launch setSearchErrorIfActive(requestToken, "unauthenticated")
                    val owner = SearchServerOwner.from(session)
                        ?: return@launch setSearchErrorIfActive(requestToken, "server_missing")
                    if (owner != searchServerOwner) {
                        // The server changed before its auth event reached this store: drop the
                        // previous server's state; that re-runs this search for the new server.
                        val switched = searchServerOwner != null
                        adoptSearchServerOwner(owner, reload = true)
                        if (switched) return@launch
                    }
                    val serverId = owner.serverId
                    val bounds = timeRangeBoundsProvider(previous.timeRange)
                    val senderId =
                        when {
                            !previous.selectedSenderId.isNullOrBlank() -> previous.selectedSenderId
                            previous.myMessagesOnly -> session.user.id
                            else -> null
                        }
                    val options =
                        SearchQueryOptions(
                            sort = previous.sort,
                            senderId = senderId,
                            scopes = previous.selectedScopes,
                            channelId = previous.selectedChannelId?.let(::ChannelId),
                            afterIso = bounds.afterIso,
                            beforeIso = bounds.beforeIso
                        )
                    val fetchPage: SearchPageFetcher = { page, limit ->
                        searchRepository.search(
                            serverId = serverId,
                            query = previous.query,
                            offset = page.offset,
                            limit = limit,
                            options = options,
                            cursor = page.cursor
                        )
                    }
                    val result =
                        if (refresh) {
                            loadSearchSnapshotToDepth(
                                depth =
                                    if (keepLoadedDepth && previous.resultQuery == previous.query.trim()) {
                                        previous.items.size
                                    } else {
                                        0
                                    },
                                pageSize = HomeSearchPageSize,
                                fetchPage = fetchPage
                            )
                        } else {
                            fetchPage(previous.nextSearchPage(), HomeSearchPageSize)
                        }
                    val latestOwner = SearchServerOwner.from(authStore.readSession())
                    if (latestOwner != owner) {
                        // A response for a server the user has left: never show it.
                        diagnosticsLogger(
                            "store_load domain=search result=stale_server serverId=${owner.serverId.value} " +
                                "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                        )
                        adoptSearchServerOwner(latestOwner, reload = true)
                    } else if (isActiveSearchRequest(requestToken)) {
                        applySearchResult(
                            result = result,
                            append = !refresh,
                            resultQuery = previous.query.trim(),
                        )
                        diagnosticsLogger(
                            "store_load domain=search ${previous.query.searchQueryDiagnostics()} refresh=$refresh append=${!refresh} " +
                                "result=${result.searchDiagnosticsResult()} durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                        )
                    } else {
                        diagnosticsLogger(
                            "store_load domain=search ${previous.query.searchQueryDiagnostics()} refresh=$refresh append=${!refresh} result=stale_request " +
                                "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                        )
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    setSearchErrorIfActive(requestToken, "search_network")
                    diagnosticsLogger(
                        "store_load domain=search refresh=$refresh append=${!refresh} result=exception code=search_network " +
                            "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                    )
                }
            }
    }

    private fun clearMessageSearchResults() {
        searchJob?.cancel()
        searchJob = null
        searchRequestToken++
        val previous = _state.value.search
        _state.value =
            _state.value.copy(
                search =
                    previous.copy(
                        items = emptyList(),
                        totalCount = 0,
                        searched = false,
                        loading = false,
                        refreshing = false,
                        loadingMore = false,
                        querySearchPending = false,
                        empty = false,
                        hasMore = false,
                        nextOffset = null,
                        nextCursor = null,
                        errorCode = null,
                        noPermissionCode = null,
                        resultQuery = null,
                    )
            )
    }

    /**
     * Loads persisted search personalization synchronously so the first visible
     * Search frame already projects history and frequent rows instead of
     * flashing an empty state until the async refresh lands. Both reads are
     * local (MMKV-backed on Android) and fast; the async refresh triggered by
     * [SharedHomeSearchSavedIntent.OpenSearch] still reconciles afterwards and
     * must not clear the prewarmed rows.
     */
    private fun prewarmSearchPersonalization() {
        val repository = personalizationRepository ?: return
        runCatching {
            runBlocking {
                val owner = currentSearchPersonalizationOwner() ?: return@runBlocking
                val snapshot =
                    withTimeout(SearchPersonalizationPrewarmTimeoutMillis) {
                        repository.read(owner, nowMillis())
                    }
                personalizationOwner = owner
                personalizationPrewarmedOwner = owner
                applySearchPersonalizationSnapshot(snapshot)
            }
        }
    }

    private fun refreshSearchPersonalization() {
        val repository = personalizationRepository ?: return
        personalizationJob?.cancel()
        val requestToken = ++personalizationRequestToken
        if (personalizationPrewarmedOwner == null) {
            // No synchronously restored rows yet: keep the historical behavior of
            // showing a clean loading state until the async read completes.
            personalizationOwner = null
            _state.value =
                _state.value.copy(
                    search =
                        _state.value.search.copy(
                            personalizationLoaded = false,
                            history = emptyList(),
                            frequentEntityRanks = emptyMap(),
                        )
                )
        }
        personalizationJob =
            scope.launch {
                val owner = currentSearchPersonalizationOwner()
                if (owner == null) {
                    if (requestToken == personalizationRequestToken) {
                        // Session is gone: drop any prewarmed rows so a signed-out
                        // surface never keeps the previous account's history.
                        personalizationOwner = null
                        personalizationPrewarmedOwner = null
                        _state.value =
                            _state.value.copy(
                                search =
                                    _state.value.search.copy(
                                        personalizationLoaded = true,
                                        history = emptyList(),
                                        frequentEntityRanks = emptyMap(),
                                    )
                            )
                    }
                    return@launch
                }
                val snapshot = repository.read(owner, nowMillis())
                if (!personalizationRequestStillActive(requestToken, owner)) return@launch
                personalizationOwner = owner
                applySearchPersonalizationSnapshot(snapshot)
            }
    }

    private fun recordSuccessfulSearchOpen(intent: SharedHomeSearchSavedIntent.RecordSuccessfulSearchOpen) {
        val entityKey =
            intent.entityKind?.let { kind ->
                SearchPersonalizationEntityKey.create(kind, intent.entityId)
            }
        mutateSearchPersonalization(expectedServerId = intent.serverId) { repository, owner, timestamp ->
            repository.recordSuccessfulOpen(
                owner = owner,
                rawQuery = intent.query,
                entityKey = entityKey,
                openedAtMillis = timestamp,
            )
        }
    }

    private fun mutateSearchPersonalization(
        expectedServerId: String? = null,
        mutation: suspend (
            repository: SearchPersonalizationRepository,
            owner: SearchPersonalizationOwner,
            timestamp: Long,
        ) -> SearchPersonalizationSnapshot,
    ) {
        val repository = personalizationRepository ?: return
        val predecessor = personalizationWriteJob
        val requestToken = personalizationRequestToken
        personalizationWriteJob =
            scope.launch {
                predecessor?.join()
                val owner = currentSearchPersonalizationOwner() ?: return@launch
                if (requestToken != personalizationRequestToken) return@launch
                if (expectedServerId != null && expectedServerId.trim() != owner.serverId) {
                    diagnosticsLogger("search_personalization_write_dropped reason=server_mismatch")
                    return@launch
                }
                val snapshot = mutation(repository, owner, nowMillis())
                if (!personalizationRequestStillActive(requestToken, owner)) return@launch
                personalizationOwner = owner
                applySearchPersonalizationSnapshot(snapshot)
            }
    }

    private suspend fun currentSearchPersonalizationOwner(): SearchPersonalizationOwner? {
        val session = authStore.readSession() ?: return null
        return SearchPersonalizationOwner.create(session.currentServerId, session.user.id)
    }

    private suspend fun personalizationRequestStillActive(
        requestToken: Long,
        owner: SearchPersonalizationOwner,
    ): Boolean {
        if (requestToken != personalizationRequestToken) return false
        val currentOwner = currentSearchPersonalizationOwner()
        if (currentOwner == owner) return true
        personalizationRequestToken++
        personalizationOwner = null
        personalizationPrewarmedOwner = null
        _state.value =
            _state.value.copy(
                search =
                    _state.value.search.copy(
                        personalizationLoaded = currentOwner == null,
                        history = emptyList(),
                        frequentEntityRanks = emptyMap(),
                    )
            )
        return false
    }

    private fun applySearchPersonalizationSnapshot(snapshot: SearchPersonalizationSnapshot) {
        if (snapshot.owner != personalizationOwner) return
        val timestamp = nowMillis()
        val ranks =
            snapshot.usage.mapNotNull { (rawKey, events) ->
                val key = SearchPersonalizationEntityKey.parse(rawKey) ?: return@mapNotNull null
                val rank = searchFrequentEntityRank(events, timestamp)
                if (rank.score <= 0.0) null else key to rank
            }.toMap()
        _state.value =
            _state.value.copy(
                search =
                    _state.value.search.copy(
                        personalizationLoaded = true,
                        history = snapshot.history,
                        frequentEntityRanks = ranks,
                    )
            )
    }

    private fun isActiveSearchRequest(requestToken: Long): Boolean =
        requestToken == searchRequestToken

    private fun isActiveSavedRequest(requestToken: Long): Boolean =
        requestToken == savedRequestToken

    private fun unsave(item: InboxItem) {
        val messageId = item.savedMessageIdOrNull() ?: return
        if (messageId in pendingSavedUnsaves) return
        val owner = savedOwner ?: return
        savedJob?.cancel()
        savedJob = null
        savedRequestToken++
        val previous = _state.value.saved
        val removedIndex = previous.items.indexOfFirst { it.savedMessageIdOrNull() == messageId }
        if (removedIndex < 0) return
        val canonicalItem = previous.items[removedIndex]
        synchronizeSavedOrderRanks(previous.items, replace = false)
        pendingSavedUnsaves[messageId] = PendingSavedUnsave(canonicalItem, owner)
        _state.value =
            _state.value.copy(
                saved =
                    previous.copy(
                        items = previous.items.filterNot { it.savedMessageIdOrNull() == messageId },
                        totalCount = (previous.totalCount - 1).coerceAtLeast(0),
                        empty = previous.items.size == 1,
                        loading = false,
                        refreshing = false,
                        pullRefreshing = false,
                        loadingMore = false,
                        errorCode = null,
                        noPermissionCode = null
                    )
            )
        writeSavedStateCache()
        scope.launch {
            try {
                when (savedRepository.unsaveMessage(owner.serverId, messageId)) {
                    is DataResult.Content -> completeSavedUnsave(messageId)
                    is DataResult.Empty -> rollbackSavedUnsave(messageId, "saved_unsave_empty")
                    is DataResult.Error -> rollbackSavedUnsave(messageId, "saved_unsave_failed")
                    is DataResult.NoPermission -> rollbackSavedUnsave(messageId, "saved_unsave_no_permission")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                rollbackSavedUnsave(messageId, "saved_unsave_network")
            }
        }
    }

    private suspend fun loadSearchSenderFilters(
        serverId: ServerId,
        priority: DataRequestPriority,
        currentUserId: String
    ): DataResult<List<SearchSenderFilterItem>> {
        val store = serverMembersStore
        if (store != null) {
            return when (val result = store.refreshMembers(serverId, priority)) {
                is DataResult.Content ->
                    DataResult.Content(
                        store.currentServerMembers(serverId)
                            .toSearchSenderFilters(currentUserId, serverId)
                    )
                is DataResult.Empty -> DataResult.Empty(result.reason)
                is DataResult.Error -> DataResult.Error(result.error)
                is DataResult.NoPermission -> DataResult.NoPermission(result.blocker)
            }
        }
        return when (val result = memberRepository?.getMembers(priority, serverId)) {
            is DataResult.Content ->
                DataResult.Content(result.data.members.toSearchSenderFilters(currentUserId, serverId))
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> DataResult.Error(result.error)
            is DataResult.NoPermission -> DataResult.NoPermission(result.blocker)
            null -> DataResult.Error(ScreenError(code = "members_unavailable", retryable = false))
        }
    }

    private fun applySavedResult(
        result: DataResult<SavedSnapshot>,
        append: Boolean,
        owner: SavedStateOwner,
        excludedMessageIds: Set<MessageId>
    ) {
        adoptSavedOwner(owner)
        val current = _state.value.saved
        _state.value =
            _state.value.copy(
                saved =
                    when (result) {
                        is DataResult.Content -> {
                            val pageItems: List<InboxItem> = result.data.items
                            synchronizeSavedOrderRanks(pageItems, replace = !append)
                            val visiblePageItems = pageItems.withoutSavedMessages(excludedMessageIds)
                            val items =
                                if (append) {
                                    (current.items + visiblePageItems).distinctBy { item: InboxItem -> item.key }
                                } else {
                                    visiblePageItems
                                }
                            val nextState =
                                current.copy(
                                    items = items,
                                    totalCount = result.data.totalCount.withExcludedSavedCount(pageItems, excludedMessageIds),
                                    hasMore = result.data.hasMore,
                                    nextOffset = result.data.nextOffset,
                                    loaded = true,
                                    empty = items.isEmpty(),
                                    loading = false,
                                    refreshing = false,
                                    pullRefreshing = false,
                                    loadingMore = false,
                                    errorCode = null,
                                    noPermissionCode = null
                                )
                            savedStateCache?.write(owner.serverId.value, owner.userId, nextState)
                            nextState
                        }
                        is DataResult.Empty ->
                            current.copy(
                                items = if (append) current.items else emptyList(),
                                loaded = true,
                                empty = !append,
                                hasMore = false,
                                nextOffset = null,
                                loading = false,
                                refreshing = false,
                                pullRefreshing = false,
                                loadingMore = false,
                                errorCode = null,
                                noPermissionCode = null
                            ).also { nextState ->
                                if (!append && pendingSavedUnsaves.isEmpty()) clearSavedOrderRanks()
                                savedStateCache?.write(owner.serverId.value, owner.userId, nextState)
                            }
                        is DataResult.Error ->
                            current.copy(
                                loading = false,
                                refreshing = false,
                                pullRefreshing = false,
                                loadingMore = false,
                                errorCode = result.error.code,
                                noPermissionCode = null
                            )
                        is DataResult.NoPermission ->
                            current.copy(
                                loading = false,
                                refreshing = false,
                                pullRefreshing = false,
                                loadingMore = false,
                                errorCode = null,
                                noPermissionCode = result.blocker.code
                            ).also {
                                savedStateCache?.clear(owner.serverId.value, owner.userId)
                            }
                    }
            )
    }

    private fun applySearchResult(
        result: DataResult<SearchSnapshot>,
        append: Boolean,
        resultQuery: String,
    ) {
        val current = _state.value.search
        _state.value =
            _state.value.copy(
                search =
                    when (result) {
                        is DataResult.Content -> {
                            val pageItems: List<InboxItem> = result.data.items
                            val items =
                                if (append) {
                                    (current.items + pageItems).distinctBy { item: InboxItem -> item.key }
                                } else {
                                    // RFC 077: a re-run hit without `delegatedBy` keeps the known label.
                                    pageItems.withKnownLatestDelegatedLabelsFrom(current.items)
                                }
                            current.copy(
                                items = items,
                                totalCount = result.data.totalCount,
                                hasMore = result.data.hasMore,
                                nextOffset = result.data.nextOffset,
                                nextCursor = result.data.nextCursor,
                                searched = true,
                                empty = items.isEmpty(),
                                loading = false,
                                refreshing = false,
                                loadingMore = false,
                                querySearchPending = false,
                                resultQuery = resultQuery,
                                errorCode = null,
                                noPermissionCode = null
                            )
                        }
                        is DataResult.Empty ->
                            current.copy(
                                items = if (append) current.items else emptyList(),
                                searched = true,
                                empty = !append,
                                hasMore = false,
                                nextOffset = null,
                                nextCursor = null,
                                loading = false,
                                refreshing = false,
                                loadingMore = false,
                                querySearchPending = false,
                                resultQuery = resultQuery,
                                errorCode = null,
                                noPermissionCode = null
                            )
                        is DataResult.Error ->
                            current.copy(
                                searched = true,
                                loading = false,
                                refreshing = false,
                                loadingMore = false,
                                querySearchPending = false,
                                errorCode = result.error.code,
                                noPermissionCode = null
                            )
                        is DataResult.NoPermission ->
                            current.copy(
                                searched = true,
                                loading = false,
                                refreshing = false,
                                loadingMore = false,
                                querySearchPending = false,
                                errorCode = null,
                                noPermissionCode = result.blocker.code
                            )
                    }
            )
    }

    private fun setSavedError(code: String) {
        _state.value =
            _state.value.copy(
                saved = _state.value.saved.copy(loading = false, refreshing = false, pullRefreshing = false, loadingMore = false, errorCode = code)
            )
    }

    private fun setSavedErrorIfActive(
        requestToken: Long,
        code: String
    ) {
        if (isActiveSavedRequest(requestToken)) {
            setSavedError(code)
        }
    }

    private fun setSearchError(code: String) {
        _state.value =
            _state.value.copy(
                search =
                    _state.value.search.copy(
                        loading = false,
                        refreshing = false,
                        loadingMore = false,
                        querySearchPending = false,
                        searched = true,
                        errorCode = code
                    )
            )
    }

    private fun setSearchErrorIfActive(
        requestToken: Long,
        code: String
    ) {
        if (isActiveSearchRequest(requestToken)) {
            setSearchError(code)
        }
    }

    private fun setSenderFiltersError(code: String) {
        _state.value =
            _state.value.copy(
                search =
                    _state.value.search.copy(
                        senderFiltersLoading = false,
                        senderFiltersErrorCode = code
                    )
            )
    }

    private fun setSenderFiltersErrorIfActive(
        requestToken: Long,
        code: String,
    ) {
        if (requestToken == senderFiltersRequestToken) {
            setSenderFiltersError(code)
        }
    }

    private suspend fun senderFiltersRequestStillActive(
        requestToken: Long,
        owner: SenderFiltersOwner,
    ): Boolean {
        if (requestToken != senderFiltersRequestToken) {
            diagnosticsLogger(
                "sender_filters_stale_discard reason=request_token " +
                    "serverId=${owner.serverId.value}"
            )
            return false
        }
        val currentSession = authStore.readSession()
        val currentOwner =
            currentSession?.currentServerId?.let(::ServerId)?.let { serverId ->
                SenderFiltersOwner(serverId = serverId, principalId = currentSession.user.id)
            }
        if (currentOwner == owner) return true
        senderFiltersOwner = null
        _state.value =
            _state.value.copy(
                search =
                    _state.value.search.copy(
                        senderFilters = emptyList(),
                        senderFiltersLoading = false,
                        senderFiltersErrorCode = null,
                    )
            )
        diagnosticsLogger(
            "sender_filters_stale_discard reason=owner_changed " +
                "serverId=${owner.serverId.value}"
        )
        return false
    }

    private fun adoptSavedOwner(owner: SavedStateOwner) {
        if (savedOwner != owner) {
            pendingSavedUnsaves.clear()
            clearSavedOrderRanks()
            savedOwner = owner
        }
    }

    private fun completeSavedUnsave(messageId: MessageId) {
        pendingSavedUnsaves.remove(messageId) ?: return
        savedOrderRanks.remove(messageId)
        normalizeSavedOrderRanksIfSettled()
        writeSavedStateCache()
    }

    private fun rollbackSavedUnsave(messageId: MessageId, code: String) {
        val mutation = pendingSavedUnsaves.remove(messageId) ?: return
        if (savedOwner != mutation.owner) return
        savedRequestToken++
        savedJob?.cancel()
        savedJob = null
        val current = _state.value.saved
        val restored = current.items.none { it.savedMessageIdOrNull() == messageId }
        val items =
            if (restored) {
                (current.items + mutation.item).sortedBySavedOrderRank()
            } else {
                current.items
            }
        _state.value =
            _state.value.copy(
                saved =
                    current.copy(
                        items = items,
                        totalCount = if (restored) current.totalCount + 1 else current.totalCount,
                        empty = items.isEmpty(),
                        loading = false,
                        refreshing = false,
                        pullRefreshing = false,
                        loadingMore = false,
                        errorCode = code,
                        noPermissionCode = null
                    )
            )
        writeSavedStateCache()
        normalizeSavedOrderRanksIfSettled()
    }

    private fun synchronizeSavedOrderRanks(
        items: List<InboxItem>,
        replace: Boolean
    ) {
        if (replace && pendingSavedUnsaves.isEmpty()) clearSavedOrderRanks()
        items.forEach { item ->
            val messageId = item.savedMessageIdOrNull() ?: return@forEach
            if (messageId !in savedOrderRanks) {
                savedOrderRanks[messageId] = nextSavedOrderRank++
            }
        }
    }

    private fun List<InboxItem>.sortedBySavedOrderRank(): List<InboxItem> =
        withIndex()
            .sortedWith(
                compareBy<IndexedValue<InboxItem>> {
                    it.value.savedMessageIdOrNull()?.let(savedOrderRanks::get) ?: Long.MAX_VALUE
                }.thenBy { it.index }
            ).map { it.value }

    private fun normalizeSavedOrderRanksIfSettled() {
        if (pendingSavedUnsaves.isEmpty()) {
            val items = _state.value.saved.items
            clearSavedOrderRanks()
            synchronizeSavedOrderRanks(items, replace = false)
        }
    }

    private fun clearSavedOrderRanks() {
        savedOrderRanks.clear()
        nextSavedOrderRank = 0L
    }

    private fun writeSavedStateCache() {
        val owner = savedOwner ?: return
        savedStateCache?.write(owner.serverId.value, owner.userId, _state.value.saved)
    }
}

private data class SavedStateOwner(
    val serverId: ServerId,
    val userId: String
)

private data class SenderFiltersOwner(
    val serverId: ServerId,
    val principalId: String,
)

/** The server + principal every Search result (messages, sender filters, filters) belongs to. */
private data class SearchServerOwner(
    val serverId: ServerId,
    val principalId: String,
) {
    companion object {
        fun from(session: StoredAuthSession?): SearchServerOwner? {
            val serverId = session?.currentServerId?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val principalId = session.user.id.trim().takeIf { it.isNotBlank() } ?: return null
            return SearchServerOwner(ServerId(serverId), principalId)
        }
    }
}

private data class PendingSavedUnsave(
    val item: InboxItem,
    val owner: SavedStateOwner
)

private fun List<InboxItem>.withoutSavedMessages(messageIds: Set<MessageId>): List<InboxItem> =
    if (messageIds.isEmpty()) this else filterNot { it.savedMessageIdOrNull() in messageIds }

private fun Int.withExcludedSavedCount(
    items: List<InboxItem>,
    messageIds: Set<MessageId>
): Int {
    if (messageIds.isEmpty()) return this
    val excludedCount = items.mapNotNull { it.savedMessageIdOrNull() }.toSet().count { it in messageIds }
    return (this - excludedCount).coerceAtLeast(0)
}

class SharedHomeSavedStateCache {
    private var ownerKey: String? = null
    private var state: SharedHomeSavedPanelState? = null

    fun read(serverId: String, userId: String): SharedHomeSavedPanelState? =
        state.takeIf { ownerKey == savedStateCacheKey(serverId, userId) }

    fun write(serverId: String, userId: String?, state: SharedHomeSavedPanelState) {
        if (!state.loaded) return
        val resolvedUserId = userId?.takeIf { it.isNotBlank() } ?: return
        ownerKey = savedStateCacheKey(serverId, resolvedUserId)
        this.state =
            state.copy(
                loading = false,
                refreshing = false,
                pullRefreshing = false,
                loadingMore = false,
                errorCode = null,
                noPermissionCode = null
            )
    }

    fun clear() {
        ownerKey = null
        state = null
    }

    fun clear(serverId: String, userId: String) {
        if (ownerKey == savedStateCacheKey(serverId, userId)) {
            clear()
        }
    }
}

private fun savedStateCacheKey(serverId: String, userId: String): String =
    "${serverId.trim()}:${userId.trim()}"

private fun DataResult<List<SearchSenderFilterItem>>.senderFiltersDiagnosticsResult(): String =
    when (this) {
        is DataResult.Content -> "content filters=${data.size}"
        is DataResult.Empty -> "empty code=${reason.name}"
        is DataResult.Error -> "error code=${error.code}"
        is DataResult.NoPermission -> "no_permission code=${blocker.code}"
    }

private fun DataResult<MemberSnapshot>.memberDiagnosticsResult(): String =
    when (this) {
        is DataResult.Content -> "content members=${data.members.size}"
        is DataResult.Empty -> "empty code=${reason.name}"
        is DataResult.Error -> "error code=${error.code}"
        is DataResult.NoPermission -> "no_permission code=${blocker.code}"
    }

private fun DataResult<SavedSnapshot>.savedDiagnosticsResult(): String =
    when (this) {
        is DataResult.Content -> "content items=${data.items.size} total=${data.totalCount}"
        is DataResult.Empty -> "empty code=${reason.name}"
        is DataResult.Error -> "error code=${error.code}"
        is DataResult.NoPermission -> "no_permission code=${blocker.code}"
    }

private fun DataResult<SearchSnapshot>.searchDiagnosticsResult(): String =
    when (this) {
        is DataResult.Content -> "content items=${data.items.size} total=${data.totalCount}"
        is DataResult.Empty -> "empty code=${reason.name}"
        is DataResult.Error -> "error code=${error.code}"
        is DataResult.NoPermission -> "no_permission code=${blocker.code}"
    }

private fun String.searchQueryDiagnostics(): String {
    val trimmed = trim()
    val wordCount =
        trimmed
            .split(Regex("\\s+"))
            .count { it.isNotBlank() }
    return "queryLen=${trimmed.length} queryWords=$wordCount"
}

class SharedHomeSearchSavedViewModel(
    authStore: RaftAuthSessionStore,
    savedRepository: SavedRepository,
    searchRepository: SearchRepository,
    memberRepository: MemberRepository? = null,
    serverMembersStore: SharedServerMembersStore? = null,
    savedStateCache: SharedHomeSavedStateCache? = null,
    timeRangeBoundsProvider: (SearchTimeRange) -> SearchIsoBounds = { SearchIsoBounds() },
    readReceiptCoordinator: ReadReceiptCoordinator? = null,
    readReceiptOwnerFactory: ((String, String) -> ReadReceiptOwner)? = null,
    diagnosticsLogger: (String) -> Unit = {},
) : ViewModel() {
    private val storeDelegate =
        lazy {
            SharedHomeSearchSavedStore(
                authStore = authStore,
                savedRepository = savedRepository,
                searchRepository = searchRepository,
                memberRepository = memberRepository,
                serverMembersStore = serverMembersStore,
                savedStateCache = savedStateCache,
                scope = viewModelScope,
                timeRangeBoundsProvider = timeRangeBoundsProvider,
                readReceiptCoordinator = readReceiptCoordinator,
                readReceiptOwnerFactory = readReceiptOwnerFactory,
                diagnosticsLogger = diagnosticsLogger,
                personalizationRepository =
                    PersistentSearchPersonalizationRepository(raftSharedPreferenceStore()),
            )
        }
    private val store: SharedHomeSearchSavedStore by storeDelegate
    val state: StateFlow<SharedHomeSearchSavedState>
        get() = store.state

    fun dispatch(intent: SharedHomeSearchSavedIntent) {
        store.dispatch(intent)
    }

    override fun onCleared() {
        if (storeDelegate.isInitialized()) {
            store.clear()
        }
        super.onCleared()
    }
}

data class SharedHomeSearchSavedState(
    val saved: SharedHomeSavedPanelState = SharedHomeSavedPanelState(),
    val search: SharedHomeSearchPanelState = SharedHomeSearchPanelState()
)

data class SharedHomeSavedPanelState(
    val items: List<InboxItem> = emptyList(),
    val totalCount: Int = 0,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val pullRefreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val empty: Boolean = false,
    val hasMore: Boolean = false,
    val nextOffset: Int? = null,
    val errorCode: String? = null,
    val noPermissionCode: String? = null
)

data class SharedHomeSearchPanelState(
    // The server the server-scoped parts of this state (message rows, sender filters, channel and
    // sender selections) belong to. Hosts scope their local candidates (Home channels/DMs) to it.
    val serverId: String? = null,
    val query: String = "",
    val sort: SearchSort = SearchSort.Relevance,
    val timeRange: SearchTimeRange = SearchTimeRange.Any,
    val myMessagesOnly: Boolean = false,
    /** The one sender filter (web `senderId`); choosing another sender replaces it. */
    val selectedSenderId: String? = null,
    val selectedScopes: List<SearchScope> = emptyList(),
    val senderFilters: List<SearchSenderFilterItem> = emptyList(),
    val senderFiltersLoading: Boolean = false,
    val senderFiltersErrorCode: String? = null,
    val selectedChannelId: String? = null,
    val items: List<InboxItem> = emptyList(),
    val totalCount: Int = 0,
    val searched: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val querySearchPending: Boolean = false,
    val loadingMore: Boolean = false,
    val empty: Boolean = false,
    val hasMore: Boolean = false,
    val nextOffset: Int? = null,
    /** Server walk cursor for the next page (recent-sort text search); preferred over [nextOffset]. */
    val nextCursor: String? = null,
    val errorCode: String? = null,
    val noPermissionCode: String? = null,
    val resultQuery: String? = null,
    val personalizationLoaded: Boolean = false,
    val history: List<String> = emptyList(),
    val frequentEntityRanks: Map<SearchPersonalizationEntityKey, SearchFrequentEntityRank> = emptyMap(),
)

internal fun SharedHomeSearchPanelState.hasMessageSearchIntent(): Boolean =
    query.trim().isNotBlank() ||
        myMessagesOnly ||
        !selectedSenderId.isNullOrBlank() ||
        selectedScopes.isNotEmpty() ||
        !selectedChannelId.isNullOrBlank() ||
        timeRange != SearchTimeRange.Any

/**
 * Where a search page starts: the server's walk [cursor] when the previous page gave one, else
 * the row [offset] (web `loadMoreResults`: "a cursor continues exactly below what was shown").
 * The repository sends the cursor only when it is valid for the search (query + sort=recent).
 */
internal data class SearchPageStart(val offset: Int, val cursor: String? = null)

internal typealias SearchPageFetcher = suspend (page: SearchPageStart, limit: Int) -> DataResult<SearchSnapshot>

private fun searchPageAfter(loadedRows: Int, nextOffset: Int?, nextCursor: String?): SearchPageStart =
    SearchPageStart(offset = nextOffset ?: loadedRows, cursor = nextCursor)

internal fun SharedHomeSearchPanelState.nextSearchPage(): SearchPageStart =
    searchPageAfter(items.size, nextOffset, nextCursor)

private fun List<SearchScope>.toggleSearchScope(scope: SearchScope): List<SearchScope> =
    if (scope in this) filterNot { it == scope } else SearchScope.entries.filter { it == scope || it in this }

private fun List<SearchScope>.exclusiveSenderKind(): MemberKind? =
    when (exclusiveSenderTypeWireValue()) {
        "user" -> MemberKind.Human
        "agent" -> MemberKind.Agent
        else -> null
    }

internal fun List<SearchScope>.withSenderKindConflictResolved(kind: MemberKind): List<SearchScope> {
    val exclusiveKind = exclusiveSenderKind() ?: return this
    if (exclusiveKind == kind) return this
    return filterNot {
        (it == SearchScope.Humans && kind == MemberKind.Agent) ||
            (it == SearchScope.Agents && kind == MemberKind.Human)
    }
}

sealed interface SharedHomeSearchSavedIntent {
    data object ClearSavedSummary : SharedHomeSearchSavedIntent
    data object OpenSearch : SharedHomeSearchSavedIntent
    data object OpenSaved : SharedHomeSearchSavedIntent
    data object RefreshSaved : SharedHomeSearchSavedIntent
    data object PullRefreshSaved : SharedHomeSearchSavedIntent
    data object LoadMoreSaved : SharedHomeSearchSavedIntent
    data class Unsave(val item: InboxItem) : SharedHomeSearchSavedIntent
    data class SearchQueryChanged(val query: String) : SharedHomeSearchSavedIntent
    data object SubmitSearch : SharedHomeSearchSavedIntent
    data object RefreshSearch : SharedHomeSearchSavedIntent
    data object LoadMoreSearch : SharedHomeSearchSavedIntent
    data class PreselectSearchChannel(val channelId: String?) : SharedHomeSearchSavedIntent
    /** Filters by one sender, replacing any other; null clears it. */
    data class SetSearchSender(val senderId: String?) : SharedHomeSearchSavedIntent
    data class SetSearchChannel(val channelId: String?) : SharedHomeSearchSavedIntent
    data class SetSearchSort(val sort: SearchSort) : SharedHomeSearchSavedIntent
    data class SetSearchTimeRange(val range: SearchTimeRange) : SharedHomeSearchSavedIntent
    data class ToggleSearchScope(val scope: SearchScope) : SharedHomeSearchSavedIntent
    data object ClearSearchScopes : SharedHomeSearchSavedIntent
    data class SetMyMessagesOnly(val enabled: Boolean) : SharedHomeSearchSavedIntent
    data object ClearSearchFilters : SharedHomeSearchSavedIntent
    data class RecordSuccessfulSearchOpen(
        val query: String,
        val entityKind: SearchPersonalizationEntityKind? = null,
        val entityId: String? = null,
        // The server the opened row belongs to. A row from another server (a candidate snapshot
        // left over from before a switch) must not enter this server's history/frequent usage.
        val serverId: String? = null,
    ) : SharedHomeSearchSavedIntent
    data class RemoveSearchHistoryEntry(val query: String) : SharedHomeSearchSavedIntent
    data object ClearSearchHistory : SharedHomeSearchSavedIntent
}

private const val SavedInboxItemKeyPrefix = "saved:"

fun InboxItem.savedMessageIdOrNull(): MessageId? =
    key
        .takeIf { it.startsWith(SavedInboxItemKeyPrefix) }
        ?.removePrefix(SavedInboxItemKeyPrefix)
        ?.takeIf { it.isNotBlank() }
        ?.let(::MessageId)
        ?: resolveInboxAnchorMessageId()

enum class SearchTimeRange(val key: String, val label: String) {
    Any("any", "ANY TIME"),
    Today("today", "TODAY"),
    Last7Days("7d", "LAST 7 DAYS"),
    Last30Days("30d", "LAST 30 DAYS")
}

data class SearchIsoBounds(
    val afterIso: String? = null,
    val beforeIso: String? = null
)

data class SearchSenderFilterItem(
    val id: String,
    val label: String,
    val handle: String,
    val kind: MemberKind,
    val sourceServerId: ServerId,
    val isSelf: Boolean = false,
    val avatarWireValue: String? = null
)

internal fun List<MemberProfile>.toSearchSenderFilters(
    currentUserId: String,
    sourceServerId: ServerId,
): List<SearchSenderFilterItem> =
    mapNotNull { member ->
        val memberId = member.id.value.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (member.serverId != null && member.serverId != sourceServerId) return@mapNotNull null
        SearchSenderFilterItem(
            id = memberId,
            label = member.displayName.ifBlank { member.handle.ifBlank { memberId } },
            handle = member.handle,
            kind = member.kind,
            sourceServerId = sourceServerId,
            isSelf = memberId == currentUserId,
            avatarWireValue = member.avatarWireValueForAvatarSlot()
        )
    }
        .distinctBy { "${it.kind}:${it.id}" }
        .sortedWith(
            compareByDescending<SearchSenderFilterItem> { it.isSelf }
                .thenBy { if (it.kind == MemberKind.Human) 0 else 1 }
                .thenBy { it.label.lowercase() }
        )

/**
 * Loads search results from offset 0 until at least [depth] rows are loaded (at least one page),
 * the server has no more, or a page fails. Pages are [pageSize] rows (the server caps a page at
 * 50), so a depth beyond one page takes several requests. The combined snapshot continues paging
 * where the last loaded page ended (its walk cursor, else its offset). A failure on the first page is returned as is; a failure on a
 * later page keeps the rows loaded so far and leaves more to load from there.
 */
internal suspend fun loadSearchSnapshotToDepth(
    depth: Int,
    pageSize: Int,
    fetchPage: SearchPageFetcher,
): DataResult<SearchSnapshot> {
    val first = fetchPage(SearchPageStart(offset = 0), pageSize)
    var combined = (first as? DataResult.Content)?.data ?: return first
    while (combined.hasMore && combined.items.size < depth) {
        val start = searchPageAfter(combined.items.size, combined.nextOffset, combined.nextCursor)
        when (val page = fetchPage(start, pageSize)) {
            is DataResult.Content -> {
                val items = (combined.items + page.data.items).distinctBy { it.key }
                val grew = items.size > combined.items.size
                combined =
                    combined.copy(
                        items = items,
                        totalCount = items.size,
                        hasMore = page.data.hasMore,
                        nextOffset = page.data.nextOffset,
                        nextCursor = page.data.nextCursor
                    )
                // A page of rows already shown cannot make progress; stop instead of looping.
                if (!grew) break
            }
            is DataResult.Empty -> {
                combined = combined.copy(hasMore = false, nextOffset = null, nextCursor = null)
            }
            else -> break
        }
    }
    return DataResult.Content(combined)
}
