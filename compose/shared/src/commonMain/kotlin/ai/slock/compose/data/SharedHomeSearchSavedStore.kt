package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MessageId
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

private const val HomePanelPageSize = 30

class SharedHomeSearchSavedStore(
    private val authStore: SlockAuthSessionStore,
    private val savedRepository: SavedRepository,
    private val searchRepository: SearchRepository,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(SharedHomeSearchSavedState())
    val state: StateFlow<SharedHomeSearchSavedState> = _state.asStateFlow()

    private var savedJob: Job? = null
    private var searchJob: Job? = null

    fun dispatch(intent: SharedHomeSearchSavedIntent) {
        when (intent) {
            SharedHomeSearchSavedIntent.OpenSaved -> {
                val saved = _state.value.saved
                if (!saved.loaded && !saved.loading) loadSaved(refresh = true)
            }
            SharedHomeSearchSavedIntent.RefreshSaved -> loadSaved(refresh = true)
            SharedHomeSearchSavedIntent.LoadMoreSaved -> loadSaved(refresh = false)
            is SharedHomeSearchSavedIntent.Unsave -> unsave(intent.item)
            is SharedHomeSearchSavedIntent.SearchQueryChanged -> {
                _state.value =
                    _state.value.copy(search = _state.value.search.copy(query = intent.query))
            }
            SharedHomeSearchSavedIntent.SubmitSearch -> runSearch(refresh = true)
            SharedHomeSearchSavedIntent.RefreshSearch -> runSearch(refresh = true)
            SharedHomeSearchSavedIntent.LoadMoreSearch -> runSearch(refresh = false)
            is SharedHomeSearchSavedIntent.SetSearchChannel -> {
                _state.value =
                    _state.value.copy(search = _state.value.search.copy(selectedChannelId = intent.channelId))
                if (_state.value.search.searched) runSearch(refresh = true)
            }
            is SharedHomeSearchSavedIntent.SetSearchSort -> {
                _state.value = _state.value.copy(search = _state.value.search.copy(sort = intent.sort))
                if (_state.value.search.searched) runSearch(refresh = true)
            }
            is SharedHomeSearchSavedIntent.SetMyMessagesOnly -> {
                _state.value =
                    _state.value.copy(search = _state.value.search.copy(myMessagesOnly = intent.enabled))
                if (_state.value.search.searched) runSearch(refresh = true)
            }
        }
    }

    fun clear() {
        savedJob?.cancel()
        searchJob?.cancel()
        savedJob = null
        searchJob = null
    }

    private fun loadSaved(refresh: Boolean) {
        val current = _state.value.saved
        if (!refresh && (current.loadingMore || !current.hasMore)) return
        savedJob?.cancel()
        savedJob =
            scope.launch {
                val previous = _state.value.saved
                _state.value =
                    _state.value.copy(
                        saved =
                            if (refresh) {
                                previous.copy(loading = true, errorCode = null, noPermissionCode = null)
                            } else {
                                previous.copy(loadingMore = true, errorCode = null, noPermissionCode = null)
                            }
                    )
                try {
                    val serverId = currentServerId() ?: return@launch setSavedError("server_missing")
                    val result =
                        if (refresh) {
                            savedRepository.loadInitial(serverId, HomePanelPageSize)
                        } else {
                            savedRepository.loadMore(serverId, previous.nextOffset ?: previous.items.size, HomePanelPageSize)
                        }
                    applySavedResult(result, append = !refresh)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    setSavedError("saved_network")
                }
            }
    }

    private fun runSearch(refresh: Boolean) {
        val current = _state.value.search
        if (!refresh && (current.loadingMore || !current.hasMore)) return
        searchJob?.cancel()
        searchJob =
            scope.launch {
                val previous = _state.value.search
                if (previous.query.isBlank()) {
                    _state.value =
                        _state.value.copy(
                            search =
                                previous.copy(
                                    items = emptyList(),
                                    loading = false,
                                    loadingMore = false,
                                    searched = false,
                                    empty = false,
                                    hasMore = false,
                                    nextOffset = null,
                                    errorCode = null,
                                    noPermissionCode = null
                                )
                        )
                    return@launch
                }
                _state.value =
                    _state.value.copy(
                        search =
                            if (refresh) {
                                previous.copy(loading = true, searched = true, errorCode = null, noPermissionCode = null)
                            } else {
                                previous.copy(loadingMore = true, searched = true, errorCode = null, noPermissionCode = null)
                            }
                    )
                try {
                    val session = authStore.readSession() ?: return@launch setSearchError("unauthenticated")
                    val serverId = session.currentServerId?.let(::ServerId) ?: return@launch setSearchError("server_missing")
                    val result =
                        searchRepository.search(
                            serverId = serverId,
                            query = previous.query,
                            offset = if (refresh) 0 else previous.nextOffset ?: previous.items.size,
                            limit = HomePanelPageSize,
                            options =
                                SearchQueryOptions(
                                    sort = previous.sort,
                                    senderId = session.user.id.takeIf { previous.myMessagesOnly },
                                    channelId = previous.selectedChannelId?.let(::ChannelId)
                                )
                        )
                    applySearchResult(result, append = !refresh)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    setSearchError("search_network")
                }
            }
    }

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

    private fun applySavedResult(result: DataResult<SavedSnapshot>, append: Boolean) {
        val current = _state.value.saved
        _state.value =
            _state.value.copy(
                saved =
                    when (result) {
                        is DataResult.Content -> {
                            val pageItems: List<InboxItem> = result.data.items
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
                                loaded = true,
                                empty = items.isEmpty(),
                                loading = false,
                                loadingMore = false,
                                errorCode = null,
                                noPermissionCode = null
                            )
                        }
                        is DataResult.Empty ->
                            current.copy(
                                items = if (append) current.items else emptyList(),
                                loaded = true,
                                empty = !append,
                                hasMore = false,
                                nextOffset = null,
                                loading = false,
                                loadingMore = false,
                                errorCode = null,
                                noPermissionCode = null
                            )
                        is DataResult.Error ->
                            current.copy(
                                loading = false,
                                loadingMore = false,
                                errorCode = result.error.code,
                                noPermissionCode = null
                            )
                        is DataResult.NoPermission ->
                            current.copy(
                                loading = false,
                                loadingMore = false,
                                errorCode = null,
                                noPermissionCode = result.blocker.code
                            )
                    }
            )
    }

    private fun applySearchResult(result: DataResult<SearchSnapshot>, append: Boolean) {
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
                                loadingMore = false,
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
                                loadingMore = false,
                                errorCode = null,
                                noPermissionCode = null
                            )
                        is DataResult.Error ->
                            current.copy(
                                searched = true,
                                loading = false,
                                loadingMore = false,
                                errorCode = result.error.code,
                                noPermissionCode = null
                            )
                        is DataResult.NoPermission ->
                            current.copy(
                                searched = true,
                                loading = false,
                                loadingMore = false,
                                errorCode = null,
                                noPermissionCode = result.blocker.code
                            )
                    }
            )
    }

    private fun setSavedError(code: String) {
        _state.value =
            _state.value.copy(
                saved = _state.value.saved.copy(loading = false, loadingMore = false, errorCode = code)
            )
    }

    private fun setSearchError(code: String) {
        _state.value =
            _state.value.copy(
                search = _state.value.search.copy(loading = false, loadingMore = false, searched = true, errorCode = code)
            )
    }

    private fun restoreSaved(previous: SharedHomeSavedPanelState, code: String) {
        _state.value = _state.value.copy(saved = previous.copy(errorCode = code, loading = false, loadingMore = false))
    }
}

class SharedHomeSearchSavedViewModel(
    authStore: SlockAuthSessionStore,
    savedRepository: SavedRepository,
    searchRepository: SearchRepository
) : ViewModel() {
    private val storeDelegate =
        lazy {
            SharedHomeSearchSavedStore(authStore, savedRepository, searchRepository, viewModelScope)
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
    val myMessagesOnly: Boolean = false,
    val selectedChannelId: String? = null,
    val items: List<InboxItem> = emptyList(),
    val totalCount: Int = 0,
    val searched: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val empty: Boolean = false,
    val hasMore: Boolean = false,
    val nextOffset: Int? = null,
    val errorCode: String? = null,
    val noPermissionCode: String? = null
)

sealed interface SharedHomeSearchSavedIntent {
    data object OpenSaved : SharedHomeSearchSavedIntent
    data object RefreshSaved : SharedHomeSearchSavedIntent
    data object LoadMoreSaved : SharedHomeSearchSavedIntent
    data class Unsave(val item: InboxItem) : SharedHomeSearchSavedIntent
    data class SearchQueryChanged(val query: String) : SharedHomeSearchSavedIntent
    data object SubmitSearch : SharedHomeSearchSavedIntent
    data object RefreshSearch : SharedHomeSearchSavedIntent
    data object LoadMoreSearch : SharedHomeSearchSavedIntent
    data class SetSearchChannel(val channelId: String?) : SharedHomeSearchSavedIntent
    data class SetSearchSort(val sort: SearchSort) : SharedHomeSearchSavedIntent
    data class SetMyMessagesOnly(val enabled: Boolean) : SharedHomeSearchSavedIntent
}

fun InboxItem.savedMessageIdOrNull(): MessageId? =
    latestActivityMessageId ?: lastMessageId ?: parentMessageId ?: firstUnreadMessageId
