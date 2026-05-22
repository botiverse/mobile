package ai.slock.compose.data

import ai.slock.compose.model.ServerId
import ai.slock.compose.model.DirectMessageCreated
import ai.slock.compose.model.RealtimeEvent
import com.tencent.kuikly.lifecycle.ViewModel
import com.tencent.kuikly.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SharedHomeStore(
    private val authStore: SlockAuthSessionStore,
    private val dataSource: SharedHomeDataSource,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(SharedHomeState())
    val state: StateFlow<SharedHomeState> = _state.asStateFlow()

    private var loadJob: Job? = null

    fun dispatch(intent: SharedHomeIntent) {
        if (intent.loadsHome) {
            loadJob?.cancel()
            loadJob =
                scope.launch {
                    accept(intent)
                }
        } else {
            scope.launch {
                accept(intent)
            }
        }
    }

    fun clear() {
        loadJob?.cancel()
        loadJob = null
    }

    suspend fun accept(intent: SharedHomeIntent) {
        when (intent) {
            SharedHomeIntent.Start -> load(_state.value.serverSwitchState)
            SharedHomeIntent.Refresh -> {
                val nextSwitch = _state.value.serverSwitchState.refresh()
                _state.value = _state.value.copy(serverSwitchState = nextSwitch)
                load(nextSwitch)
            }
            SharedHomeIntent.ToggleServerSwitcher ->
                _state.value = _state.value.copy(serverSwitcherExpanded = !_state.value.serverSwitcherExpanded)
            SharedHomeIntent.DismissServerSwitcher ->
                _state.value = _state.value.copy(serverSwitcherExpanded = false)
            is SharedHomeIntent.ApplyRealtimeEvent ->
                applyRealtimeEvent(ServerId(intent.serverId), intent.event)
            is SharedHomeIntent.SelectServer -> {
                val currentServerId = (_state.value.content as? SharedHomeContentState.Content)?.snapshot?.server?.id?.value
                val nextSwitch =
                    if (intent.serverId == currentServerId) {
                        _state.value.serverSwitchState.refresh()
                    } else {
                        _state.value.serverSwitchState.select(intent.serverId)
                    }
                _state.value =
                    _state.value.copy(
                        serverSwitcherExpanded = false,
                        serverSwitchState = nextSwitch
                    )
                load(nextSwitch)
            }
        }
    }

    private suspend fun applyRealtimeEvent(serverId: ServerId, event: RealtimeEvent) {
        val current = _state.value.content
        val currentContent = current as? SharedHomeContentState.Content
        val visibleServerId = currentContent?.snapshot?.server?.id
        val result = dataSource.applyRealtimeEvent(serverId, event)
        if (visibleServerId != null && visibleServerId != serverId) {
            return
        }
        if (event is DirectMessageCreated) {
            val nextSwitch = _state.value.serverSwitchState.refresh()
            _state.value = _state.value.copy(serverSwitchState = nextSwitch)
            load(nextSwitch)
            return
        }
        if (result is DataResult.Content) {
            val session = currentContent?.session
                ?: authStore.readSession()
                ?: return
            _state.value =
                _state.value.copy(
                    content =
                        SharedHomeContentState.Content(
                            snapshot = result.data,
                            session = session,
                            contentRefreshing = currentContent?.contentRefreshing == true
                        )
                )
        }
    }

    private suspend fun load(request: HomeServerSwitchState) {
        val previous = _state.value.content
        _state.value = _state.value.copy(content = previous.asRefreshing())
        try {
            val session = authStore.readSession()
            if (session == null) {
                _state.value = _state.value.copy(content = SharedHomeContentState.Error("unauthenticated"))
                return
            }
            _state.value = _state.value.copy(content = previous.asRefreshing(session))

            val requestedServerId = request.selectedServerId
            requestedServerId?.let { serverId ->
                val server = session.servers.firstOrNull { it.id == serverId }
                authStore.updateSelectedServer(serverId = serverId, serverSlug = server?.slug)
            }

            val activeSession = authStore.readSession() ?: session
            _state.value = _state.value.copy(content = _state.value.content.asRefreshing(activeSession))
            val activeServerId = requestedServerId ?: activeSession.currentServerId
            val activeServer = activeServerId?.let(::ServerId)
            when (val cached = dataSource.readCachedHome(serverId = activeServer)) {
                is DataResult.Content -> {
                    val latestSwitch = _state.value.serverSwitchState
                    val cachedServerId = cached.data.server.id
                    if (latestSwitch.isCurrent(request.requestGeneration, cachedServerId)) {
                        _state.value =
                            _state.value.copy(
                                content = SharedHomeContentState.Content(cached.data, activeSession, contentRefreshing = true),
                                serverSwitchState = latestSwitch.complete(request.requestGeneration, cachedServerId)
                            )
                    }
                }
                is DataResult.Empty,
                is DataResult.Error,
                is DataResult.NoPermission -> Unit
            }
            val result =
                when (val result = dataSource.loadHome(serverId = activeServer, priority = DataRequestPriority.Visible)) {
                    is DataResult.Content -> SharedHomeContentState.Content(result.data, activeSession)
                    is DataResult.Empty -> SharedHomeContentState.Empty(activeSession)
                    is DataResult.Error -> _state.value.content.asRefreshFailed(result.error.code)
                    is DataResult.NoPermission -> _state.value.content.asRefreshFailed(result.blocker.code)
                }
            val resultServerId = (result as? SharedHomeContentState.Content)?.snapshot?.server?.id
                ?: activeServerId?.let(::ServerId)
            val latestSwitch = _state.value.serverSwitchState
            if (resultServerId == null || latestSwitch.isCurrent(request.requestGeneration, resultServerId)) {
                _state.value =
                    _state.value.copy(
                        content = result,
                        serverSwitchState =
                            if (resultServerId != null) {
                                latestSwitch.complete(request.requestGeneration, resultServerId)
                            } else {
                                latestSwitch
                            }
                    )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            _state.value = _state.value.copy(content = _state.value.content.asRefreshFailed("home_network"))
        }
    }
}

class SharedHomeViewModel(
    authStore: SlockAuthSessionStore,
    dataSource: SharedHomeDataSource
) : ViewModel() {
    private val storeDelegate =
        lazy {
            SharedHomeStore(authStore, dataSource, viewModelScope)
        }
    private val store: SharedHomeStore by storeDelegate
    val state: StateFlow<SharedHomeState>
        get() = store.state

    fun dispatch(intent: SharedHomeIntent) {
        store.dispatch(intent)
    }

    override fun onCleared() {
        if (storeDelegate.isInitialized()) {
            store.clear()
        }
        super.onCleared()
    }
}

data class SharedHomeState(
    val content: SharedHomeContentState = SharedHomeContentState.Loading(),
    val serverSwitchState: HomeServerSwitchState = HomeServerSwitchState(),
    val serverSwitcherExpanded: Boolean = false
)

sealed interface SharedHomeIntent {
    data object Start : SharedHomeIntent
    data object Refresh : SharedHomeIntent
    data object ToggleServerSwitcher : SharedHomeIntent
    data object DismissServerSwitcher : SharedHomeIntent
    data class SelectServer(val serverId: String) : SharedHomeIntent
    data class ApplyRealtimeEvent(val serverId: String, val event: RealtimeEvent) : SharedHomeIntent
}

private val SharedHomeIntent.loadsHome: Boolean
    get() =
        when (this) {
            SharedHomeIntent.Start,
            SharedHomeIntent.Refresh,
            is SharedHomeIntent.SelectServer -> true
            SharedHomeIntent.ToggleServerSwitcher,
            SharedHomeIntent.DismissServerSwitcher,
            is SharedHomeIntent.ApplyRealtimeEvent -> false
        }

sealed interface SharedHomeContentState {
    data class Loading(val session: StoredAuthSession? = null) : SharedHomeContentState
    data class Empty(val session: StoredAuthSession) : SharedHomeContentState
    data class Error(val code: String) : SharedHomeContentState
    data class Content(
        val snapshot: HomeSnapshot,
        val session: StoredAuthSession,
        val contentRefreshing: Boolean = false
    ) : SharedHomeContentState
}

private fun SharedHomeContentState.asRefreshing(
    session: StoredAuthSession? = (this as? SharedHomeContentState.Content)?.session
): SharedHomeContentState =
    when (this) {
        is SharedHomeContentState.Content -> copy(contentRefreshing = true)
        is SharedHomeContentState.Empty -> SharedHomeContentState.Loading(session ?: this.session)
        is SharedHomeContentState.Loading -> SharedHomeContentState.Loading(session ?: this.session)
        is SharedHomeContentState.Error -> SharedHomeContentState.Loading(session)
    }

private fun SharedHomeContentState.asRefreshFailed(code: String): SharedHomeContentState =
    when (this) {
        is SharedHomeContentState.Content -> copy(contentRefreshing = false)
        else -> SharedHomeContentState.Error(code)
    }
