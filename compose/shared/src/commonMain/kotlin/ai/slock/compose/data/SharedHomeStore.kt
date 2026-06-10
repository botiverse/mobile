package ai.slock.compose.data

import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.DirectMessageCreated
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.RealtimeEvent
import ai.slock.compose.model.RealtimeRefreshRequired
import ai.slock.compose.model.RealtimeRefreshScope
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

class SharedHomeStore(
    private val authStore: SlockAuthSessionStore,
    private val dataSource: SharedHomeDataSource,
    private val scope: CoroutineScope,
    private val diagnosticsLogger: (String) -> Unit = {},
    private val presenceFlushDelayMillis: Long = 250L
) {
    private val _state = MutableStateFlow(SharedHomeState())
    val state: StateFlow<SharedHomeState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var refreshJob: Job? = null
    private var presenceFlushJob: Job? = null
    private var pendingPresenceServerId: ServerId? = null
    private val pendingPresenceEvents = linkedMapOf<MemberId, MemberPresenceUpdated>()

    fun dispatch(intent: SharedHomeIntent) {
        if (intent.loadsHome) {
            loadJob?.cancel()
            refreshJob?.cancel()
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
        refreshJob?.cancel()
        refreshJob = null
        presenceFlushJob?.cancel()
        presenceFlushJob = null
        pendingPresenceEvents.clear()
        pendingPresenceServerId = null
    }

    suspend fun accept(intent: SharedHomeIntent) {
        when (intent) {
            SharedHomeIntent.Start ->
                load(
                    _state.value.serverSwitchState,
                    showRefreshAffordance = _state.value.content !is SharedHomeContentState.Content
                )
            SharedHomeIntent.Refresh -> {
                val nextSwitch = _state.value.serverSwitchState.refresh()
                reduceState(_state.value.copy(serverSwitchState = nextSwitch))
                load(nextSwitch, showRefreshAffordance = true)
            }
            SharedHomeIntent.ToggleServerSwitcher ->
                reduceState(_state.value.copy(serverSwitcherExpanded = !_state.value.serverSwitcherExpanded))
            SharedHomeIntent.DismissServerSwitcher ->
                reduceState(_state.value.copy(serverSwitcherExpanded = false))
            is SharedHomeIntent.SelectConversation ->
                reduceState(
                    _state.value.copy(
                        selectedConversationId = intent.threadId,
                        selectedTopAction = null
                    )
                )
            is SharedHomeIntent.SelectTopAction ->
                reduceState(
                    _state.value.copy(
                        selectedConversationId = null,
                        selectedTopAction = intent.action
                    )
                )
            is SharedHomeIntent.ApplyRealtimeEvent ->
                applyRealtimeEvent(ServerId(intent.serverId), intent.event)
            is SharedHomeIntent.DismissNotification ->
                dismissNotification(intent.id)
            is SharedHomeIntent.ChannelCreated ->
                reduceState(_state.value.withCreatedChannel(intent.channel))
            is SharedHomeIntent.SelectServer -> {
                val currentServerId = (_state.value.content as? SharedHomeContentState.Content)?.snapshot?.server?.id?.value
                val nextSwitch =
                    if (intent.serverId == currentServerId) {
                        _state.value.serverSwitchState.refresh()
                    } else {
                        _state.value.serverSwitchState.select(intent.serverId)
                    }
                reduceState(
                    _state.value.copy(
                        serverSwitcherExpanded = false,
                        serverSwitchState = nextSwitch,
                        selectedConversationId = null,
                        selectedTopAction = null
                    )
                )
                load(nextSwitch, showRefreshAffordance = true)
            }
        }
    }

    private fun SharedHomeState.withCreatedChannel(channel: ConversationSummary): SharedHomeState {
        val nextContent =
            when (val current = content) {
                is SharedHomeContentState.Content ->
                    current.copy(
                        snapshot =
                            current.snapshot.copy(
                                channels =
                                    (current.snapshot.channels.filterNot {
                                        it.threadId == channel.threadId ||
                                            it.channelId == channel.channelId
                                    } + channel)
                            ),
                        contentRefreshing = false
                    )
                else -> current
            }
        return copy(
            content = nextContent,
            selectedConversationId = channel.threadId.value,
            selectedTopAction = null
        )
    }

    private suspend fun applyRealtimeEvent(serverId: ServerId, event: RealtimeEvent) {
        val current = _state.value.content
        val currentContent = current as? SharedHomeContentState.Content
        val visibleServerId = currentContent?.snapshot?.server?.id
        if (visibleServerId != null && visibleServerId != serverId) {
            return
        }
        if (event is RealtimeRefreshRequired) {
            applyRealtimeRefresh(event)
            return
        }
        if (event is MemberPresenceUpdated) {
            enqueuePresenceEvent(serverId, event)
            return
        }
        val result = dataSource.applyRealtimeEvent(
            serverId = serverId,
            event = event,
            activeConversationId = _state.value.selectedConversationId
        )
        if (event is DirectMessageCreated) {
            val nextSwitch = _state.value.serverSwitchState.refresh()
            reduceState(_state.value.copy(serverSwitchState = nextSwitch))
            load(nextSwitch, showRefreshAffordance = false)
            return
        }
        if (result is DataResult.Content) {
            val session = currentContent?.session
                ?: authStore.readSession()
                ?: return
            reduceState(
                _state.value.copy(
                    content =
                        SharedHomeContentState.Content(
                            snapshot = result.data.preserveStableRowsFrom(currentContent?.snapshot),
                            session = session,
                            contentRefreshing = currentContent?.contentRefreshing == true
                        )
                )
            )
        }
    }

    private suspend fun applyRealtimeRefresh(event: RealtimeRefreshRequired) {
        when (event.scope) {
            RealtimeRefreshScope.Announcements -> {
                loadNotifications()
            }
            RealtimeRefreshScope.Home,
            RealtimeRefreshScope.ChannelMembers,
            RealtimeRefreshScope.ServerMembers,
            RealtimeRefreshScope.Agents,
            RealtimeRefreshScope.Machines,
            RealtimeRefreshScope.Settings,
            RealtimeRefreshScope.Reminders -> {
                diagnosticsLogger("home_realtime_refresh scope=${event.scope} reason=${event.reason}")
                val nextSwitch = _state.value.serverSwitchState.refresh()
                reduceState(_state.value.copy(serverSwitchState = nextSwitch))
                load(nextSwitch, showRefreshAffordance = false)
            }
        }
    }

    private suspend fun load(
        request: HomeServerSwitchState,
        showRefreshAffordance: Boolean
    ) {
        diagnosticsLogger("home_ui_load_start refreshAffordance=$showRefreshAffordance")
        val previous = _state.value.content
        reduceState(
            _state.value.copy(
                content = previous.asRefreshing(showRefreshAffordance = showRefreshAffordance)
            )
        )
        try {
            val session = authStore.readSession()
            if (session == null) {
                diagnosticsLogger("home_ui_error source=shared_home_load code=unauthenticated mapped=home_error_panel")
                reduceState(_state.value.copy(content = SharedHomeContentState.Error("unauthenticated")))
                return
            }
            reduceState(
                _state.value.copy(
                    content = previous.asRefreshing(session, showRefreshAffordance)
                )
            )

            val requestedServerId = request.selectedServerId
            val switchingServers =
                requestedServerId != null &&
                    requestedServerId != (previous as? SharedHomeContentState.Content)?.snapshot?.server?.id?.value
            requestedServerId?.let { serverId ->
                val server = session.servers.firstOrNull { it.id == serverId }
                authStore.updateSelectedServer(serverId = serverId, serverSlug = server?.slug)
            }

            val activeSession = authStore.readSession() ?: session
            reduceState(
                _state.value.copy(
                    content =
                        if (switchingServers) {
                            SharedHomeContentState.Loading(activeSession)
                        } else {
                            _state.value.content.asRefreshing(activeSession, showRefreshAffordance)
                        }
                )
            )
            val activeServerId = requestedServerId ?: activeSession.currentServerId
            val activeServer = activeServerId?.let(::ServerId)
            when (val cached = dataSource.readCachedHome(serverId = activeServer)) {
                is DataResult.Content -> {
                    val latestSwitch = _state.value.serverSwitchState
                    val cachedServerId = cached.data.server.id
                    if (latestSwitch.isCurrent(request.requestGeneration, cachedServerId)) {
                        val previousSnapshot = (_state.value.content as? SharedHomeContentState.Content)?.snapshot
                        reduceState(
                            _state.value.copy(
                                content =
                                    SharedHomeContentState.Content(
                                        cached.data.preserveStableRowsFrom(previousSnapshot),
                                        activeSession,
                                        contentRefreshing = showRefreshAffordance
                                    ),
                                serverSwitchState = latestSwitch.complete(request.requestGeneration, cachedServerId)
                            )
                        )
                        launchNetworkRefresh(
                            request = request,
                            activeSession = activeSession,
                            activeServer = activeServer ?: cachedServerId
                        )
                        return
                    }
                }
                is DataResult.Empty,
                is DataResult.Error,
                is DataResult.NoPermission -> Unit
            }
            loadHomeFromNetwork(
                request = request,
                activeSession = activeSession,
                activeServer = activeServer,
                priority = DataRequestPriority.Visible,
                forceNetwork = false
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            diagnosticsLogger(
                "home_ui_exception source=shared_home_load errorType=${throwable.safeHomeStoreDiagnosticsType()} mapped=${_state.value.content.refreshFailureMapping()} code=home_network"
            )
            reduceState(_state.value.copy(content = _state.value.content.asRefreshFailed("home_network")))
        }
    }

    private fun launchNetworkRefresh(
        request: HomeServerSwitchState,
        activeSession: StoredAuthSession,
        activeServer: ServerId
    ) {
        refreshJob?.cancel()
        refreshJob =
            scope.launch {
                try {
                    loadHomeFromNetwork(
                        request = request,
                        activeSession = activeSession,
                        activeServer = activeServer,
                        priority = DataRequestPriority.Background,
                        forceNetwork = true
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    diagnosticsLogger(
                        "home_ui_exception source=shared_home_background_refresh errorType=${throwable.safeHomeStoreDiagnosticsType()} mapped=${_state.value.content.refreshFailureMapping()} code=home_network"
                    )
                    reduceState(_state.value.copy(content = _state.value.content.asRefreshFailed("home_network")))
                }
            }
    }

    private suspend fun loadHomeFromNetwork(
        request: HomeServerSwitchState,
        activeSession: StoredAuthSession,
        activeServer: ServerId?,
        priority: DataRequestPriority,
        forceNetwork: Boolean
    ) {
        val result =
            when (val result = dataSource.loadHome(serverId = activeServer, priority = priority, forceNetwork = forceNetwork)) {
                is DataResult.Content -> SharedHomeContentState.Content(result.data, activeSession)
                is DataResult.Empty -> SharedHomeContentState.Empty(activeSession)
                is DataResult.Error -> {
                    diagnosticsLogger(
                        "home_ui_error source=shared_home_load code=${result.error.code} mapped=${_state.value.content.refreshFailureMapping()}"
                    )
                    _state.value.content.asRefreshFailed(result.error.code)
                }
                is DataResult.NoPermission -> {
                    diagnosticsLogger(
                        "home_ui_error source=shared_home_load code=${result.blocker.code} mapped=${_state.value.content.refreshFailureMapping()}"
                    )
                    _state.value.content.asRefreshFailed(result.blocker.code)
                }
            }
        val resultServerId = (result as? SharedHomeContentState.Content)?.snapshot?.server?.id
            ?: activeServer
        val latestSwitch = _state.value.serverSwitchState
        if (resultServerId == null || latestSwitch.isCurrent(request.requestGeneration, resultServerId)) {
            val nextContent =
                (result as? SharedHomeContentState.Content)?.let { content ->
                    val previousSnapshot = (_state.value.content as? SharedHomeContentState.Content)?.snapshot
                    content.copy(snapshot = content.snapshot.preserveStableRowsFrom(previousSnapshot))
                } ?: result
            reduceState(
                _state.value.copy(
                    content = nextContent,
                    serverSwitchState =
                        if (resultServerId != null) {
                            latestSwitch.complete(request.requestGeneration, resultServerId)
                        } else {
                            latestSwitch
                        }
                )
            )
            loadNotifications()
        }
    }

    private fun enqueuePresenceEvent(serverId: ServerId, event: MemberPresenceUpdated) {
        val pendingServerId = pendingPresenceServerId
        if (pendingServerId != null && pendingServerId != serverId) {
            presenceFlushJob?.cancel()
            presenceFlushJob = null
            pendingPresenceEvents.clear()
        }
        pendingPresenceServerId = serverId
        pendingPresenceEvents[event.memberId] = event
        if (presenceFlushJob?.isActive == true) return
        presenceFlushJob =
            scope.launch {
                if (presenceFlushDelayMillis > 0) {
                    delay(presenceFlushDelayMillis)
                }
                flushPresenceEvents()
            }
    }

    private suspend fun flushPresenceEvents() {
        val serverId = pendingPresenceServerId ?: return
        val events = pendingPresenceEvents.values.toList()
        pendingPresenceEvents.clear()
        pendingPresenceServerId = null
        presenceFlushJob = null
        if (events.isEmpty()) return
        val visibleServerId = (_state.value.content as? SharedHomeContentState.Content)?.snapshot?.server?.id
        val selectedServerId = _state.value.serverSwitchState.selectedServerId
        if (visibleServerId != null && visibleServerId != serverId) return
        if (selectedServerId != null && selectedServerId != serverId.value) return
        val result = dataSource.applyRealtimeEvents(
            serverId = serverId,
            events = events,
            activeConversationId = _state.value.selectedConversationId
        )
        if (result is DataResult.Content) {
            val currentContent = _state.value.content as? SharedHomeContentState.Content
            val session = currentContent?.session
                ?: authStore.readSession()
                ?: return
            reduceState(
                _state.value.copy(
                    content =
                        SharedHomeContentState.Content(
                            snapshot = result.data.preserveStableRowsFrom(currentContent?.snapshot),
                            session = session,
                            contentRefreshing = currentContent?.contentRefreshing == true
                        )
                )
            )
        }
    }

    private suspend fun loadNotifications() {
        when (val result = dataSource.loadNotifications()) {
            is DataResult.Content ->
                reduceState(_state.value.copy(notifications = result.data))
            is DataResult.Empty ->
                reduceState(_state.value.copy(notifications = emptyList()))
            is DataResult.Error,
            is DataResult.NoPermission -> Unit
        }
    }

    private suspend fun dismissNotification(id: String) {
        val previous = _state.value.notifications
        val next = previous.filterNot { it.id == id }
        if (next == previous) return
        reduceState(_state.value.copy(notifications = next))
        when (dataSource.dismissNotification(id)) {
            is DataResult.Content,
            is DataResult.Empty -> Unit
            is DataResult.Error,
            is DataResult.NoPermission ->
                reduceState(_state.value.copy(notifications = previous))
        }
    }

    private fun reduceState(nextState: SharedHomeState): Boolean {
        if (nextState == _state.value) return false
        _state.value = nextState
        return true
    }
}

class SharedHomeViewModel(
    authStore: SlockAuthSessionStore,
    dataSource: SharedHomeDataSource,
    diagnosticsLogger: (String) -> Unit = {}
) : ViewModel() {
    private val storeDelegate =
        lazy {
            SharedHomeStore(authStore, dataSource, viewModelScope, diagnosticsLogger)
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
    val serverSwitcherExpanded: Boolean = false,
    val selectedConversationId: String? = null,
    val selectedTopAction: SharedHomeTopAction? = null,
    val notifications: List<SharedHomeNotification> = emptyList()
)

enum class SharedHomeTopAction {
    Search,
    Activity,
    Saved
}

sealed interface SharedHomeIntent {
    data object Start : SharedHomeIntent
    data object Refresh : SharedHomeIntent
    data object ToggleServerSwitcher : SharedHomeIntent
    data object DismissServerSwitcher : SharedHomeIntent
    data class SelectConversation(val threadId: String) : SharedHomeIntent
    data class SelectTopAction(val action: SharedHomeTopAction) : SharedHomeIntent
    data class SelectServer(val serverId: String) : SharedHomeIntent
    data class ApplyRealtimeEvent(val serverId: String, val event: RealtimeEvent) : SharedHomeIntent
    data class DismissNotification(val id: String) : SharedHomeIntent
    data class ChannelCreated(val channel: ConversationSummary) : SharedHomeIntent
}

private val SharedHomeIntent.loadsHome: Boolean
    get() =
        when (this) {
            SharedHomeIntent.Start,
            SharedHomeIntent.Refresh,
            is SharedHomeIntent.SelectServer -> true
            SharedHomeIntent.ToggleServerSwitcher,
            SharedHomeIntent.DismissServerSwitcher,
            is SharedHomeIntent.SelectConversation,
            is SharedHomeIntent.SelectTopAction,
            is SharedHomeIntent.ApplyRealtimeEvent,
            is SharedHomeIntent.DismissNotification,
            is SharedHomeIntent.ChannelCreated -> false
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
    session: StoredAuthSession? = (this as? SharedHomeContentState.Content)?.session,
    showRefreshAffordance: Boolean = true
): SharedHomeContentState =
    when (this) {
        is SharedHomeContentState.Content -> copy(contentRefreshing = showRefreshAffordance)
        is SharedHomeContentState.Empty -> SharedHomeContentState.Loading(session ?: this.session)
        is SharedHomeContentState.Loading -> SharedHomeContentState.Loading(session ?: this.session)
        is SharedHomeContentState.Error -> SharedHomeContentState.Loading(session)
    }

private fun SharedHomeContentState.asRefreshFailed(code: String): SharedHomeContentState =
    when (this) {
        is SharedHomeContentState.Content -> copy(contentRefreshing = false)
        else -> SharedHomeContentState.Error(code)
    }

private fun SharedHomeContentState.refreshFailureMapping(): String =
    when (this) {
        is SharedHomeContentState.Content -> "keep_cached_content"
        else -> "home_error_panel"
    }

private fun Throwable.safeHomeStoreDiagnosticsType(): String =
    this::class.simpleName?.takeIf { it.isNotBlank() } ?: "Throwable"
