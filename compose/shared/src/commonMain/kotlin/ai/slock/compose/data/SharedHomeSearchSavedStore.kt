package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.Presence
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.avatarWireValueForAvatarSlot
import ai.slock.compose.resolveInboxAnchorMessageId
import com.tencent.kuikly.lifecycle.ViewModel
import com.tencent.kuikly.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val HomeSavedPageSize = 100
private const val HomeSearchPageSize = 30
private const val SearchDebounceMillis = 250L

class SharedHomeSearchSavedStore(
    private val authStore: SlockAuthSessionStore,
    private val savedRepository: SavedRepository,
    private val searchRepository: SearchRepository,
    private val memberRepository: MemberRepository? = null,
    private val serverMembersStore: SharedServerMembersStore? = null,
    private val savedStateCache: SharedHomeSavedStateCache? = null,
    private val scope: CoroutineScope,
    private val timeRangeBoundsProvider: (SearchTimeRange) -> SearchIsoBounds = { SearchIsoBounds() },
    private val searchDebounceMillis: Long = SearchDebounceMillis
) {
    private val _state = MutableStateFlow(SharedHomeSearchSavedState())
    val state: StateFlow<SharedHomeSearchSavedState> = _state.asStateFlow()

    private var savedJob: Job? = null
    private var searchJob: Job? = null
    private var searchDebounceJob: Job? = null
    private var senderFiltersJob: Job? = null
    private var searchRequestToken: Long = 0L

    fun dispatch(intent: SharedHomeSearchSavedIntent) {
        when (intent) {
            SharedHomeSearchSavedIntent.OpenSearch -> {
                val search = _state.value.search
                if (search.senderFilters.isEmpty() && !search.senderFiltersLoading) {
                    loadSenderFilters()
                }
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
            SharedHomeSearchSavedIntent.RefreshSearch -> runSearch(refresh = true)
            SharedHomeSearchSavedIntent.LoadMoreSearch -> runSearch(refresh = false)
            is SharedHomeSearchSavedIntent.SetSearchChannel -> {
                _state.value =
                    _state.value.copy(search = _state.value.search.copy(selectedChannelId = intent.channelId))
                scheduleSearchRefresh()
            }
            is SharedHomeSearchSavedIntent.ToggleSearchSender -> {
                _state.value =
                    _state.value.copy(
                        search =
                            _state.value.search.copy(
                                selectedSenderIds = _state.value.search.selectedSenderIds.toggleSenderId(intent.senderId),
                                myMessagesOnly = false
                            )
                    )
                scheduleSearchRefresh()
            }
            SharedHomeSearchSavedIntent.ClearSearchSenders -> {
                _state.value =
                    _state.value.copy(
                        search =
                            _state.value.search.copy(
                                selectedSenderIds = emptyList(),
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
            is SharedHomeSearchSavedIntent.SetMyMessagesOnly -> {
                _state.value =
                    _state.value.copy(
                        search =
                            _state.value.search.copy(
                                myMessagesOnly = intent.enabled,
                                selectedSenderIds = if (intent.enabled) emptyList() else _state.value.search.selectedSenderIds
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
                                selectedSenderIds = emptyList(),
                                selectedChannelId = null,
                                timeRange = SearchTimeRange.Any
                            )
                    )
                scheduleSearchRefresh()
            }
        }
    }

    fun clear() {
        savedJob?.cancel()
        searchJob?.cancel()
        searchDebounceJob?.cancel()
        senderFiltersJob?.cancel()
        savedJob = null
        searchJob = null
        searchDebounceJob = null
        senderFiltersJob = null
        searchRequestToken++
    }

    private fun loadSenderFilters() {
        if (memberRepository == null && serverMembersStore == null) return
        senderFiltersJob?.cancel()
        senderFiltersJob =
            scope.launch {
                _state.value =
                    _state.value.copy(
                        search = _state.value.search.copy(senderFiltersLoading = true, senderFiltersErrorCode = null)
                    )
                try {
                    val session = authStore.readSession() ?: return@launch setSenderFiltersError("unauthenticated")
                    val serverId = session.currentServerId?.let(::ServerId) ?: return@launch setSenderFiltersError("server_missing")
                    when (val result = loadServerMembers(serverId, DataRequestPriority.Visible)) {
                        is DataResult.Content ->
                            _state.value =
                                _state.value.copy(
                                    search =
                                        _state.value.search.copy(
                                            senderFilters = result.data.members.toSearchSenderFilters(session.user.id),
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
                        is DataResult.Error -> setSenderFiltersError(result.error.code)
                        is DataResult.NoPermission -> setSenderFiltersError(result.blocker.code)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    setSenderFiltersError("sender_filters_network")
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
        savedJob =
            scope.launch {
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
                    val session = authStore.readSession() ?: return@launch setSavedError("unauthenticated")
                    val serverId = session.currentServerId?.let(::ServerId) ?: return@launch setSavedError("server_missing")
                    if (refresh && !previous.loaded && previous.items.isEmpty()) {
                        savedStateCache?.read(serverId.value, session.user.id)?.let { cached ->
                            _state.value =
                                _state.value.copy(
                                    saved =
                                        cached.copy(
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
                    val senderProfiles = loadSenderAvatarProfiles(session, serverId)
                    val result =
                        if (refresh) {
                            savedRepository.loadInitial(serverId, HomeSavedPageSize)
                        } else {
                            savedRepository.loadMore(serverId, previous.nextOffset ?: previous.items.size, HomeSavedPageSize)
                        }
                    applySavedResult(
                        result = result,
                        append = !refresh,
                        senderProfiles = senderProfiles,
                        serverId = serverId,
                        userId = session.user.id
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    setSavedError("saved_network")
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
        cancelDebounce: Boolean = true
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
                val previous = _state.value.search
                val refreshInPlace = refresh && (previous.searched || previous.items.isNotEmpty())
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
                    val serverId = session.currentServerId?.let(::ServerId)
                        ?: return@launch setSearchErrorIfActive(requestToken, "server_missing")
                    val senderProfiles = loadSenderAvatarProfiles(session, serverId)
                    val bounds = timeRangeBoundsProvider(previous.timeRange)
                    val senderIds =
                        when {
                            previous.selectedSenderIds.isNotEmpty() -> previous.selectedSenderIds
                            previous.myMessagesOnly -> listOf(session.user.id)
                            else -> emptyList()
                        }
                    val result =
                        searchRepository.search(
                            serverId = serverId,
                            query = previous.query,
                            offset = if (refresh) 0 else previous.nextOffset ?: previous.items.size,
                            limit = HomeSearchPageSize,
                            options =
                                SearchQueryOptions(
                                    sort = previous.sort,
                                    senderIds = senderIds,
                                    channelId = previous.selectedChannelId?.let(::ChannelId),
                                    afterIso = bounds.afterIso,
                                    beforeIso = bounds.beforeIso
                                )
                        )
                    if (isActiveSearchRequest(requestToken)) {
                        applySearchResult(result, append = !refresh, senderProfiles = senderProfiles)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    setSearchErrorIfActive(requestToken, "search_network")
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
                        errorCode = null,
                        noPermissionCode = null
                    )
            )
    }

    private fun isActiveSearchRequest(requestToken: Long): Boolean =
        requestToken == searchRequestToken

    private fun unsave(item: InboxItem) {
        val messageId = item.savedMessageIdOrNull() ?: return
        val previous = _state.value.saved
        _state.value =
            _state.value.copy(
                saved =
                    previous.copy(
                        items = previous.items.filterNot { it.savedMessageIdOrNull() == messageId },
                        totalCount = (previous.totalCount - 1).coerceAtLeast(0),
                        errorCode = null,
                        noPermissionCode = null
                    )
            )
        scope.launch {
            try {
                val serverId = currentServerId() ?: return@launch restoreSaved(previous, "server_missing")
                when (savedRepository.unsaveMessage(serverId, messageId)) {
                    is DataResult.Content -> Unit
                    is DataResult.Empty -> restoreSaved(previous, "saved_unsave_empty")
                    is DataResult.Error -> restoreSaved(previous, "saved_unsave_failed")
                    is DataResult.NoPermission -> restoreSaved(previous, "saved_unsave_no_permission")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                restoreSaved(previous, "saved_unsave_network")
            }
        }
    }

    private suspend fun currentServerId(): ServerId? = authStore.readSession()?.currentServerId?.let(::ServerId)

    private suspend fun loadSenderAvatarProfiles(
        session: StoredAuthSession,
        serverId: ServerId
    ): Map<String, SenderAvatarProfile> {
        val cached =
            _state.value.search.senderFilters.associateBy(
                keySelector = { it.id },
                valueTransform = {
                    SenderAvatarProfile(
                        kind = it.kind,
                        avatarWireValue = it.avatarWireValue,
                        presence = it.presence,
                        agentStatus = it.agentStatus
                    )
                }
        )
        if (cached.isNotEmpty()) return cached
        return try {
            when (val result = loadServerMembers(serverId, DataRequestPriority.Visible)) {
                is DataResult.Content -> {
                    val filters = result.data.members.toSearchSenderFilters(session.user.id)
                    _state.value =
                        _state.value.copy(
                            search =
                                _state.value.search.copy(
                                    senderFilters = filters,
                                    senderFiltersLoading = false,
                                    senderFiltersErrorCode = null
                                )
                        )
                    filters.associateBy(
                        keySelector = { it.id },
                        valueTransform = {
                            SenderAvatarProfile(
                                kind = it.kind,
                                avatarWireValue = it.avatarWireValue,
                                presence = it.presence,
                                agentStatus = it.agentStatus
                            )
                        }
                    )
                }
                else -> emptyMap()
            }
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private suspend fun loadServerMembers(
        serverId: ServerId,
        priority: DataRequestPriority
    ): DataResult<MemberSnapshot> =
        serverMembersStore?.loadMembers(serverId, priority)
            ?: memberRepository?.getMembers(priority, serverId)
            ?: DataResult.Error(ScreenError(code = "members_unavailable", retryable = false))

    private fun applySavedResult(
        result: DataResult<SavedSnapshot>,
        append: Boolean,
        senderProfiles: Map<String, SenderAvatarProfile>,
        serverId: ServerId,
        userId: String
    ) {
        val current = _state.value.saved
        _state.value =
            _state.value.copy(
                saved =
                    when (result) {
                        is DataResult.Content -> {
                            val pageItems: List<InboxItem> = result.data.items.withSenderAvatarProfiles(senderProfiles)
                            val items =
                                if (append) {
                                    (current.items + pageItems).distinctBy { item: InboxItem -> item.key }
                                } else {
                                    pageItems
                                }
                            val nextState =
                                current.copy(
                                    items = items,
                                    totalCount = result.data.totalCount,
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
                            savedStateCache?.write(result.data.server.id.value, userId, nextState)
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
                                savedStateCache?.write(serverId.value, userId, nextState)
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
                                savedStateCache?.clear(serverId.value, userId)
                            }
                    }
            )
    }

    private fun applySearchResult(
        result: DataResult<SearchSnapshot>,
        append: Boolean,
        senderProfiles: Map<String, SenderAvatarProfile>
    ) {
        val current = _state.value.search
        _state.value =
            _state.value.copy(
                search =
                    when (result) {
                        is DataResult.Content -> {
                            val pageItems: List<InboxItem> = result.data.items.withSenderAvatarProfiles(senderProfiles)
                            val items =
                                if (append) {
                                    (current.items + pageItems).distinctBy { item: InboxItem -> item.key }
                                } else {
                                    pageItems
                                }
                            current.copy(
                                items = items,
                                totalCount = result.data.totalCount,
                                hasMore = result.data.hasMore,
                                nextOffset = result.data.nextOffset,
                                searched = true,
                                empty = items.isEmpty(),
                                loading = false,
                                refreshing = false,
                                loadingMore = false,
                                querySearchPending = false,
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
                                loading = false,
                                refreshing = false,
                                loadingMore = false,
                                querySearchPending = false,
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

    private fun restoreSaved(previous: SharedHomeSavedPanelState, code: String) {
        _state.value = _state.value.copy(saved = previous.copy(errorCode = code, loading = false, refreshing = false, pullRefreshing = false, loadingMore = false))
    }
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

class SharedHomeSearchSavedViewModel(
    authStore: SlockAuthSessionStore,
    savedRepository: SavedRepository,
    searchRepository: SearchRepository,
    memberRepository: MemberRepository? = null,
    serverMembersStore: SharedServerMembersStore? = null,
    savedStateCache: SharedHomeSavedStateCache? = null,
    timeRangeBoundsProvider: (SearchTimeRange) -> SearchIsoBounds = { SearchIsoBounds() }
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
                timeRangeBoundsProvider = timeRangeBoundsProvider
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
    val query: String = "",
    val sort: SearchSort = SearchSort.Relevance,
    val timeRange: SearchTimeRange = SearchTimeRange.Any,
    val myMessagesOnly: Boolean = false,
    val selectedSenderIds: List<String> = emptyList(),
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
    val errorCode: String? = null,
    val noPermissionCode: String? = null
)

private fun SharedHomeSearchPanelState.hasMessageSearchIntent(): Boolean =
    query.trim().isNotBlank() ||
        myMessagesOnly ||
        selectedSenderIds.any { it.isNotBlank() } ||
        !selectedChannelId.isNullOrBlank() ||
        timeRange != SearchTimeRange.Any

private fun List<String>.toggleSenderId(senderId: String): List<String> {
    val normalized = senderId.trim()
    if (normalized.isBlank()) return this
    return if (normalized in this) filterNot { it == normalized } else this + normalized
}

sealed interface SharedHomeSearchSavedIntent {
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
    data class ToggleSearchSender(val senderId: String) : SharedHomeSearchSavedIntent
    data object ClearSearchSenders : SharedHomeSearchSavedIntent
    data class SetSearchChannel(val channelId: String?) : SharedHomeSearchSavedIntent
    data class SetSearchSort(val sort: SearchSort) : SharedHomeSearchSavedIntent
    data class SetSearchTimeRange(val range: SearchTimeRange) : SharedHomeSearchSavedIntent
    data class SetMyMessagesOnly(val enabled: Boolean) : SharedHomeSearchSavedIntent
    data object ClearSearchFilters : SharedHomeSearchSavedIntent
}

fun InboxItem.savedMessageIdOrNull(): MessageId? = resolveInboxAnchorMessageId()

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
    val isSelf: Boolean = false,
    val avatarWireValue: String? = null,
    val presence: Presence? = null,
    val agentStatus: ai.slock.compose.model.AgentStatus? = null
)

private data class SenderAvatarProfile(
    val kind: MemberKind,
    val avatarWireValue: String? = null,
    val presence: Presence? = null,
    val agentStatus: ai.slock.compose.model.AgentStatus? = null
)

private fun List<InboxItem>.withSenderAvatarProfiles(
    senderProfiles: Map<String, SenderAvatarProfile>
): List<InboxItem> =
    map { item ->
        val lastSender = item.lastMessageSenderId?.let(senderProfiles::get)
        val latestSender = item.latestActivitySenderId?.let(senderProfiles::get)
        item.copy(
            lastMessageSenderKind = lastSender?.kind ?: item.lastMessageSenderKind,
            lastMessageSenderAvatarWireValue = lastSender?.avatarWireValue ?: item.lastMessageSenderAvatarWireValue,
            latestActivitySenderKind = latestSender?.kind ?: item.latestActivitySenderKind,
            latestActivitySenderAvatarWireValue = latestSender?.avatarWireValue ?: item.latestActivitySenderAvatarWireValue
        )
    }

private fun List<MemberProfile>.toSearchSenderFilters(currentUserId: String): List<SearchSenderFilterItem> =
    map { member ->
        SearchSenderFilterItem(
            id = member.id.value,
            label = member.displayName.ifBlank { member.handle.ifBlank { member.id.value } },
            handle = member.handle,
            kind = member.kind,
            isSelf = member.id.value == currentUserId,
            avatarWireValue = member.avatarWireValueForAvatarSlot(),
            presence = member.presence,
            agentStatus = member.agentStatus
        )
    }
        .distinctBy { "${it.kind}:${it.id}" }
        .sortedWith(
            compareByDescending<SearchSenderFilterItem> { it.isSelf }
                .thenBy { if (it.kind == MemberKind.Human) 0 else 1 }
                .thenBy { it.label.lowercase() }
        )
