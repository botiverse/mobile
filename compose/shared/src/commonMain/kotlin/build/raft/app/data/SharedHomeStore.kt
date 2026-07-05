package build.raft.app.data

import build.raft.app.SlockSidebarSortMode
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Message
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.messageStreamChannelId
import com.tencent.kuikly.lifecycle.ViewModel
import com.tencent.kuikly.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SharedHomeStore(
    private val authStore: SlockAuthSessionStore,
    private var dataSource: SharedHomeDataSource,
    private val scope: CoroutineScope,
    private var diagnosticsLogger: (String) -> Unit = {},
    private val presenceFlushDelayMillis: Long = 250L,
    /**
     * #211: load the current server's sidebar-order snapshot. The store
     * calls this in parallel with the home payload load to populate the
     * `sidebarOrder` field on `HomeSnapshot`. Default is a no-op that
     * returns `DataResult.Empty(EmptyReason.NoSettingsAvailable)` so existing
     * test fixtures and callers that don't pass a real provider keep
     * compiling.
     */
    private var loadSidebarOrder: suspend () -> DataResult<SettingsServerSidebarOrderSnapshot> = {
        DataResult.Empty(EmptyReason.NoSettingsAvailable)
    },
    /**
     * #211: PATCH the sidebar-order and return the persisted snapshot. Used
     * for both pin/unpin and pinned-sort-mode persistence with optimistic
     * update + rollback. Default is a no-op (returns `Empty`) so existing
     * tests keep compiling.
     */
    private var updateSidebarOrder: suspend (SettingsServerSidebarOrderUpdateRequest) -> DataResult<SettingsServerSidebarOrderSnapshot> = {
        DataResult.Empty(EmptyReason.NoSettingsAvailable)
    },
    private var markChannelReadAll: suspend (ChannelId) -> DataResult<ReadReceiptResult> = {
        DataResult.Empty(EmptyReason.NoMessages)
    },
    private var markChannelUnread: suspend (ChannelId) -> DataResult<ReadReceiptResult> = {
        DataResult.Empty(EmptyReason.NoMessages)
    },
    private var archiveChannel: suspend (ChannelId) -> DataResult<ChannelArchiveResult> = {
        DataResult.Empty(EmptyReason.NoChannels)
    },
    /**
     * #211: callback invoked when a pin/unpin or pinned-sort-mode PATCH
     * fails. The host (SettingsHomePage) typically wires this to
     * `bridge.toast(...)` so the user sees a short error after the
     * optimistic update is rolled back. Default is a no-op so existing
     * tests keep compiling.
     */
    private var pinUpdateFailureCallback: (String) -> Unit = {},
    private val persistSlices: List<MviPersistSlice<SharedHomeState, SharedHomeIntent>> = emptyList(),
    /**
     * #81: wall-clock source for [AppBadgeCount.lastUpdated]. Injected so
     * tests can pin a deterministic clock; production passes a
     * `System.currentTimeMillis` adapter. Use [Long] (not [kotlin.time.Instant])
     * to match the OHOS datetime klib stability rule we adopted in
     * `d56ed07d`; UI layer formats via `SlockTime` if needed.
     */
    private val nowMillis: () -> Long = { defaultNowMillis() }
) : SharedConversationIdentityProvider {
    private val _state = MutableStateFlow(SharedHomeState())
    val state: StateFlow<SharedHomeState> = _state.asStateFlow()
    private val conversationIdentityStates =
        MutableStateFlow<Map<String, SharedConversationDirectoryState>>(emptyMap())

    private var loadJob: Job? = null
    private var agentDirectMessageStatusJob: Job? = null
    private val contentByServer = linkedMapOf<ServerId, SharedHomeContentState.Content>()
    // #211: serialize sidebar-order loads and mutations so a Pin/Unpin
    // arriving before the initial load resolves cannot PATCH from an empty
    // base snapshot and clobber any server-side pinned list.
    private val sidebarOrderMutex = Mutex()
    private val sidebarOrderByServer = linkedMapOf<ServerId, SettingsServerSidebarOrderSnapshot>()
    private var homeSurfaceVisible: Boolean = true
    private var hiddenHomeSnapshotDirty: Boolean = false
    private val dismissedLocalNotificationIds = mutableSetOf<String>()
    private var stateDiagnosticsReason: String = "unknown"
    private var stateDiagnosticsIntent: SharedHomeIntent? = null

    fun updateRuntimeIntegrations(
        dataSource: SharedHomeDataSource,
        diagnosticsLogger: (String) -> Unit = {},
        loadSidebarOrder: suspend () -> DataResult<SettingsServerSidebarOrderSnapshot> = {
            DataResult.Empty(EmptyReason.NoSettingsAvailable)
        },
        updateSidebarOrder: suspend (SettingsServerSidebarOrderUpdateRequest) -> DataResult<SettingsServerSidebarOrderSnapshot> = {
            DataResult.Empty(EmptyReason.NoSettingsAvailable)
        },
        markChannelReadAll: suspend (ChannelId) -> DataResult<ReadReceiptResult> = {
            DataResult.Empty(EmptyReason.NoMessages)
        },
        markChannelUnread: suspend (ChannelId) -> DataResult<ReadReceiptResult> = {
            DataResult.Empty(EmptyReason.NoMessages)
        },
        archiveChannel: suspend (ChannelId) -> DataResult<ChannelArchiveResult> = {
            DataResult.Empty(EmptyReason.NoChannels)
        },
        pinUpdateFailureCallback: (String) -> Unit = {}
    ) {
        loadJob?.cancel()
        agentDirectMessageStatusJob?.cancel()
        loadJob = null
        agentDirectMessageStatusJob = null
        this.dataSource = dataSource
        this.diagnosticsLogger = diagnosticsLogger
        this.loadSidebarOrder = loadSidebarOrder
        this.updateSidebarOrder = updateSidebarOrder
        this.markChannelReadAll = markChannelReadAll
        this.markChannelUnread = markChannelUnread
        this.archiveChannel = archiveChannel
        this.pinUpdateFailureCallback = pinUpdateFailureCallback
        diagnosticsLogger("home_runtime_integrations_updated")
    }

    fun dispatch(intent: SharedHomeIntent) {
        if (intent.loadsHome) {
            diagnosticsLogger(
                "home_dispatch_receive intent=${intent.toSharedHomeIntentDiagnosticsLabel()} " +
                    "loadsHome=true debounceable=${intent.debouncesInFlightHomeLoad} " +
                    "activeLoad=${loadJob?.isActive == true} state=${_state.value.toSharedHomeStateDiagnosticsSummary()}"
            )
            if (debounceInFlightHomeLoad(intent)) {
                return
            }
            diagnosticsLogger(
                "home_dispatch_launch_load intent=${intent.toSharedHomeIntentDiagnosticsLabel()} " +
                    "cancelPrevious=${loadJob != null} state=${_state.value.toSharedHomeStateDiagnosticsSummary()}"
            )
            loadJob?.cancel()
            agentDirectMessageStatusJob?.cancel()
            agentDirectMessageStatusJob = null
            loadJob =
                scope.launch {
                    diagnosticsLogger(
                        "home_dispatch_coroutine_enter intent=${intent.toSharedHomeIntentDiagnosticsLabel()} " +
                            "state=${_state.value.toSharedHomeStateDiagnosticsSummary()}"
                    )
                    accept(intent)
                }
        } else {
            if (intent is SharedHomeIntent.SetSurfaceVisible) {
                diagnosticsLogger(
                    "home_dispatch_receive intent=${intent.toSharedHomeIntentDiagnosticsLabel()} " +
                        "loadsHome=false state=${_state.value.toSharedHomeStateDiagnosticsSummary()}"
                )
            }
            scope.launch {
                accept(intent)
            }
        }
    }

    private fun debounceInFlightHomeLoad(intent: SharedHomeIntent): Boolean {
        if (!intent.debouncesInFlightHomeLoad) return false
        val activeLoad = loadJob?.isActive == true
        if (!activeLoad) return false
        diagnosticsLogger(
            "home_load_debounce intent=${intent.toSharedHomeIntentDiagnosticsLabel()} " +
                "reason=in_flight_load"
        )
        return true
    }

    fun clear() {
        loadJob?.cancel()
        loadJob = null
        agentDirectMessageStatusJob?.cancel()
        agentDirectMessageStatusJob = null
        sidebarOrderByServer.clear()
        contentByServer.clear()
        conversationIdentityStates.value = emptyMap()
        hiddenHomeSnapshotDirty = false
    }

    override fun observeServer(serverId: ServerId): Flow<SharedConversationDirectoryState> =
        conversationIdentityStates
            .map { byServer -> byServer[serverId.value] ?: SharedConversationDirectoryState() }
            .distinctUntilChanged()

    override fun projectionFlow(
        serverId: ServerId,
        ids: Collection<String>
    ): Flow<Map<String, SharedConversationDirectoryProjection>> =
        observeServer(serverId)
            .map { state ->
                ids
                    .filter { it.isNotBlank() }
                    .associateWith { id -> state.resolve(id) }
                    .filterValues { it != null }
                    .mapValues { (_, value) -> value!! }
            }
            .distinctUntilChanged()

    override fun resolveProjection(
        serverId: ServerId,
        id: String
    ): SharedConversationDirectoryProjection? =
        conversationIdentityStates.value[serverId.value]?.resolve(id)

    suspend fun accept(intent: SharedHomeIntent) {
        withStateDiagnosticsContext(intent) {
            diagnosticsLogger(
                "home_accept_enter intent=${intent.toSharedHomeIntentDiagnosticsLabel()} " +
                    "state=${_state.value.toSharedHomeStateDiagnosticsSummary()}"
            )
            when (intent) {
            SharedHomeIntent.Start ->
                load(
                    _state.value.serverSwitchState,
                    showRefreshAffordance = _state.value.content !is SharedHomeContentState.Content,
                )
            SharedHomeIntent.Refresh -> {
                val nextSwitch = _state.value.serverSwitchState.refresh()
                reduceState(_state.value.copy(serverSwitchState = nextSwitch))
                load(
                    nextSwitch,
                    showRefreshAffordance = true,
                )
            }
            SharedHomeIntent.ForegroundRefresh ->
                load(
                    _state.value.serverSwitchState,
                    showRefreshAffordance = false,
                )
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
            SharedHomeIntent.RefreshNotifications ->
                loadNotifications()
            is SharedHomeIntent.UpsertNotification ->
                upsertNotification(intent.notification)
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
                        selectedTopAction = null,
                        // #81: clear badge on server switch so the host
                        // stops showing a stale count from the previous
                        // server before the new server's first
                        // /servers/unread-summary arrives. See
                        // AppBadgeCount.DISABLED semantics.
                        appBadgeCount = AppBadgeCount.DISABLED
                    )
                )
                load(
                    nextSwitch,
                    showRefreshAffordance = true,
                )
            }
            is SharedHomeIntent.SetSurfaceVisible ->
                setHomeSurfaceVisible(intent.visible)
            is SharedHomeIntent.TogglePinned ->
                togglePinned(intent.conversationId)
            is SharedHomeIntent.SetPinnedSortMode ->
                setPinnedSortMode(intent.mode)
            is SharedHomeIntent.SetChannelSortMode ->
                setChannelSortMode(intent.mode)
            is SharedHomeIntent.ReorderSidebar ->
                reorderSidebar(intent)
            is SharedHomeIntent.MarkRead ->
                markConversationRead(intent.conversationId)
            is SharedHomeIntent.MarkUnread ->
                markConversationUnread(intent.conversationId)
            is SharedHomeIntent.CloseDirectMessage ->
                closeDirectMessage(intent.conversationId)
            is SharedHomeIntent.ArchiveChannel ->
                archiveConversationChannel(intent.conversationId)
            }
        }
    }

    private suspend fun withStateDiagnosticsContext(intent: SharedHomeIntent, block: suspend () -> Unit) {
        val previousReason = stateDiagnosticsReason
        val previousIntent = stateDiagnosticsIntent
        stateDiagnosticsReason = intent.toSharedHomeIntentDiagnosticsLabel()
        stateDiagnosticsIntent = intent
        try {
            block()
        } finally {
            stateDiagnosticsReason = previousReason
            stateDiagnosticsIntent = previousIntent
        }
    }

    private fun SharedHomeState.withCreatedChannel(channel: HomeChannelRow): SharedHomeState {
        val nextContent =
            when (val current = content) {
                is SharedHomeContentState.Content ->
                    contentFromDurableSnapshot(
                        snapshot =
                            current.baseSnapshot.copy(
                                channels =
                                    (current.baseSnapshot.channels.filterNot {
                                        it.threadId == channel.threadId ||
                                            it.channelId == channel.channelId
                                    } + channel)
                            ),
                        session = current.session,
                        contentRefreshing = false,
                        previousContent = current
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
        if (!homeSurfaceVisible) {
            applyRealtimeEventWhileHomeHidden(serverId, event)
            return
        }
        if (event is RealtimeRefreshRequired) {
            applyRealtimeRefresh(event)
            return
        }
        if (event is MemberPresenceUpdated) return
        if (event is DirectMessageCreated) {
            val nextSwitch = _state.value.serverSwitchState.refresh()
            reduceState(_state.value.copy(serverSwitchState = nextSwitch))
            load(
                nextSwitch,
                showRefreshAffordance = false
            )
            return
        }
        applyRealtimeHomeEventLocally(serverId, event)
    }

    private suspend fun applyRealtimeEventWhileHomeHidden(serverId: ServerId, event: RealtimeEvent) {
        when (event) {
            is RealtimeRefreshRequired -> {
                if (event.scope == RealtimeRefreshScope.Announcements) {
                    Unit
                } else if (event.updatesHomeSnapshot()) {
                    hiddenHomeSnapshotDirty = true
                    markHomeRealtimeDirty(serverId, "refresh:${event.scope}")
                }
            }
            is MemberPresenceUpdated -> Unit
            else -> {
                if (event.updatesHomeSnapshot()) {
                    hiddenHomeSnapshotDirty = true
                    markHomeRealtimeDirty(serverId, event.homeRealtimeDirtyReason())
                }
            }
        }
    }

    private suspend fun setHomeSurfaceVisible(visible: Boolean) {
        val current = _state.value
        if (homeSurfaceVisible == visible) return
        homeSurfaceVisible = visible
        if (!visible) return
        if (visible && current.homeRealtimeDirty) {
            val currentContent = current.content as? SharedHomeContentState.Content
            if (hiddenHomeSnapshotDirty) {
                hiddenHomeSnapshotDirty = false
                val nextSwitch = current.serverSwitchState.refresh()
                reduceState(
                    current.copy(
                        homeRealtimeDirty = false,
                        serverSwitchState = nextSwitch
                    )
                )
                load(
                    nextSwitch,
                    showRefreshAffordance = false,
                )
                return
            }
            val nextContent =
                currentContent?.let { content ->
                    content.copy(snapshot = content.baseSnapshot.preserveStableRowsFrom(content.snapshot))
                } ?: current.content
            reduceState(
                current.copy(
                    homeRealtimeDirty = false,
                    content = nextContent
                )
            )
            return
        }
    }

    private fun markHomeRealtimeDirty(serverId: ServerId, reason: String) {
        val current = _state.value
        if (current.homeRealtimeDirty) return
        diagnosticsLogger("home_realtime_dirty serverId=${serverId.value} reason=$reason")
        reduceState(current.copy(homeRealtimeDirty = true))
    }

    private fun applyRealtimeHomeEventLocally(serverId: ServerId, event: RealtimeEvent): Boolean {
        val current = _state.value
        val content = current.content as? SharedHomeContentState.Content ?: return false
        if (content.baseSnapshot.server.id != serverId) return false
        val startedAt = kotlin.time.TimeSource.Monotonic.markNow()
        val messagePatch =
            when (event) {
                is MessageNew -> HomeRealtimeMessagePatch(messages = listOf(event.message), incrementUnread = true)
                is MessageUpdated -> HomeRealtimeMessagePatch(messages = listOf(event.message), incrementUnread = false)
                is SyncResumeResponse -> HomeRealtimeMessagePatch(messages = event.messages, incrementUnread = true)
                else -> null
            } ?: return false
        val activeConversationId = current.selectedConversationId
        val nextSnapshot =
            content.snapshot.withRealtimeMessages(
                patch = messagePatch,
                activeConversationId = activeConversationId
            )
        if (nextSnapshot === content.snapshot || nextSnapshot == content.snapshot) {
            diagnosticsLogger(
                "store_merge_skip domain=home source=store_realtime reason=unchanged " +
                    "event=${event.toSharedHomeRealtimeEventDiagnosticsLabel()} durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
            )
            return false
        }
        val nextBaseSnapshot =
            content.baseSnapshot.withRealtimeMessages(
                patch = messagePatch,
                activeConversationId = activeConversationId
            )
        val nextContent =
            content.copy(
                snapshot = nextSnapshot,
                baseSnapshot = nextBaseSnapshot
            )
        val nextBadge = snapshotToBadgeCount(
            snapshot = nextBaseSnapshot,
            selectedServerId = nextBaseSnapshot.server.id,
            isStale = false
        )
        val changed =
            reduceState(
                current.copy(
                    content = nextContent,
                    serverUnreadCounts = nextBaseSnapshot.serverUnreadCounts,
                    appBadgeCount = nextBadge
                )
            )
        diagnosticsLogger(
            "store_merge domain=home source=store_realtime serverId=${serverId.value} " +
                "event=${event.toSharedHomeRealtimeEventDiagnosticsLabel()} messages=${messagePatch.messages.size} " +
                "incrementUnread=${messagePatch.incrementUnread} changed=$changed " +
                "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
        )
        return changed
    }

    private suspend fun applyRealtimeRefresh(event: RealtimeRefreshRequired) {
        when (event.scope) {
            RealtimeRefreshScope.Announcements -> {
                diagnosticsLogger("home_realtime_refresh_ignore scope=${event.scope} reason=${event.reason}")
            }
            RealtimeRefreshScope.Agents -> {
                diagnosticsLogger("home_realtime_refresh_lightweight scope=${event.scope} reason=${event.reason}")
                launchAgentDirectMessageStatusRefreshForCurrentSnapshot()
            }
            RealtimeRefreshScope.Machines -> {
                diagnosticsLogger("home_realtime_refresh_lightweight scope=${event.scope} reason=${event.reason}")
                loadNotifications()
            }
            RealtimeRefreshScope.Home,
            RealtimeRefreshScope.ChannelMembers,
            RealtimeRefreshScope.ServerMembers,
            RealtimeRefreshScope.Settings,
            RealtimeRefreshScope.Reminders -> {
                launchCoalescedRealtimeHomeReload(event)
            }
        }
    }

    private fun launchCoalescedRealtimeHomeReload(event: RealtimeRefreshRequired) {
        val activeLoad = loadJob?.takeIf { it.isActive }
        if (activeLoad != null) {
            diagnosticsLogger(
                "home_realtime_refresh_coalesce scope=${event.scope} reason=${event.reason} " +
                    "active=load"
            )
            return
        }
        diagnosticsLogger("home_realtime_refresh scope=${event.scope} reason=${event.reason}")
        val nextSwitch = _state.value.serverSwitchState.refresh()
        reduceState(_state.value.copy(serverSwitchState = nextSwitch))
        loadJob =
            scope.launch {
                val previousReason = stateDiagnosticsReason
                stateDiagnosticsReason = "RealtimeRefresh"
                try {
                    load(
                        nextSwitch,
                        showRefreshAffordance = false
                    )
                } finally {
                    stateDiagnosticsReason = previousReason
                }
            }
    }

    private fun launchAgentDirectMessageStatusRefreshForCurrentSnapshot() {
        val currentContent = _state.value.content as? SharedHomeContentState.Content ?: return
        launchAgentDirectMessageStatusRefresh(
            request = _state.value.serverSwitchState,
            snapshot = currentContent.baseSnapshot
        )
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
                reduceState(
                    _state.value.copy(
                        content = SharedHomeContentState.Error("unauthenticated"),
                        // #81: clear badge on logout. The underlying
                        // SharedHomeSnapshot.unreadTotal is preserved for
                        // other surfaces (Home / Inbox), but the selected-
                        // server badge projection must not show a count after
                        // the user logs out.
                        appBadgeCount = AppBadgeCount.DISABLED
                    )
                )
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
            val activeServerId = requestedServerId ?: activeSession.currentServerId
            reduceState(
                _state.value.copy(
                    content =
                        if (switchingServers) {
                            activeServerId
                                ?.let(::ServerId)
                                ?.let { serverId ->
                                    contentByServer[serverId]?.copy(
                                        session = activeSession,
                                        contentRefreshing = showRefreshAffordance
                                    )
                                }
                                ?: SharedHomeContentState.Loading(activeSession)
                        } else {
                            _state.value.content.asRefreshing(activeSession, showRefreshAffordance)
                        }
                )
            )
            val activeServer = activeServerId?.let(::ServerId)
            loadHomeFromNetwork(
                request = request,
                activeSession = activeSession,
                activeServer = activeServer,
                priority = DataRequestPriority.Visible,
                forceNetwork = false,
                refreshNotificationsAfterLoad = true
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

    private suspend fun loadHomeFromNetwork(
        request: HomeServerSwitchState,
        activeSession: StoredAuthSession,
        activeServer: ServerId?,
        priority: DataRequestPriority,
        forceNetwork: Boolean,
        refreshNotificationsAfterLoad: Boolean
    ) {
        val dataResult = dataSource.loadHome(serverId = activeServer, priority = priority, forceNetwork = forceNetwork)
        val previousContent = _state.value.content as? SharedHomeContentState.Content
        val result =
            when (dataResult) {
                is DataResult.Content ->
                    contentFromDurableSnapshot(
                        snapshot = dataResult.data,
                        session = activeSession,
                        contentRefreshing = false,
                        previousContent = previousContent
                    )
                is DataResult.Empty -> SharedHomeContentState.Empty(activeSession)
                is DataResult.Error -> {
                    diagnosticsLogger(
                        "home_ui_error source=shared_home_load code=${dataResult.error.code} mapped=${_state.value.content.refreshFailureMapping()}"
                    )
                    _state.value.content.asRefreshFailed(dataResult.error.code)
                }
                is DataResult.NoPermission -> {
                    diagnosticsLogger(
                        "home_ui_error source=shared_home_load code=${dataResult.blocker.code} mapped=${_state.value.content.refreshFailureMapping()}"
                    )
                    _state.value.content.asRefreshFailed(dataResult.blocker.code)
                }
            }
        val resultServerId = (result as? SharedHomeContentState.Content)?.snapshot?.server?.id
            ?: activeServer
        val latestSwitch = _state.value.serverSwitchState
        if (resultServerId == null || latestSwitch.isCurrent(request.requestGeneration, resultServerId)) {
            val nextContent = result
            // #81: derive the badge projection from the fresh snapshot.
            // - On success: source comes from the parser (SERVER_UNREAD_SUMMARY
            //   or CHANNEL_DM_FALLBACK), isStale is false, lastUpdated advances.
            // - On Error / NoPermission: keep last value but flip isStale = true
            //   so a future UI layer can hint staleness. lastUpdated MUST NOT
            //   advance.
            //
            // #82: derive `freshSnapshot` from the **original** data source
            // result (before the `asRefreshFailed` content transform). When
            // the previous state is `Content`, `asRefreshFailed` returns
            // `Content.copy(contentRefreshing = false)` — the failure case
            // therefore produces a transformed `result` whose snapshot is
            // non-null, and the old `(result as? Content)?.snapshot` check
            // would mis-classify the failure as a fresh success and skip
            // `isStale = true`. The original `dataResult` is the source of
            // truth for whether the network actually succeeded.
            val freshSnapshot = (dataResult as? DataResult.Content)?.data
            val nextBadge =
                if (freshSnapshot != null) {
                    val next = snapshotToBadgeCount(freshSnapshot, selectedServerId = activeServer, isStale = false)
                    // #81: don't bump lastUpdated (and therefore the state
                    // itself) when the projection is structurally the same
                    // as the previous one. Avoids spurious state emissions
                    // for refresh-on-no-change paths.
                    if (next.copy(lastUpdated = 0L) == _state.value.appBadgeCount.copy(lastUpdated = 0L)) {
                        _state.value.appBadgeCount
                    } else {
                        next
                    }
                } else {
                    val previous = _state.value.appBadgeCount
                    previous.copy(isStale = true)
                }
            reduceState(
                _state.value.copy(
                    content = nextContent,
                    serverSwitchState =
                        if (resultServerId != null) {
                            latestSwitch.complete(request.requestGeneration, resultServerId)
                        } else {
                            latestSwitch
                        },
                    serverUnreadCounts = freshSnapshot?.serverUnreadCounts ?: _state.value.serverUnreadCounts,
                    appBadgeCount = nextBadge
                )
            )
            if (refreshNotificationsAfterLoad) {
                loadNotifications()
            } else {
                diagnosticsLogger("home_notifications_refresh_skip reason=cached_refresh_already_loaded")
            }
            if (freshSnapshot != null) {
                launchAgentDirectMessageStatusRefresh(
                    request = request,
                    snapshot = freshSnapshot
                )
                loadAndMergeSidebarOrder()
            }
        }
    }

    private fun launchAgentDirectMessageStatusRefresh(
        request: HomeServerSwitchState,
        snapshot: HomeSnapshot
    ) {
        if (snapshot.directMessages.none { it.isAgentDirectMessage } && !dataSource.projectsAgentStatusFromMemberStore) return
        if (dataSource.projectsAgentStatusFromMemberStore) {
            diagnosticsLogger("home_agent_status_refresh_start source=member_store_projection")
        } else {
            diagnosticsLogger("home_agent_status_refresh_start source=legacy_direct_message_patch")
        }
        agentDirectMessageStatusJob?.cancel()
        agentDirectMessageStatusJob =
            scope.launch {
                val patch =
                    try {
                        dataSource.refreshAgentDirectMessageStatuses(snapshot, DataRequestPriority.Background)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (throwable: Throwable) {
                        diagnosticsLogger(
                            "home_agent_status_refresh_failed result=exception errorType=${throwable.safeHomeStoreDiagnosticsType()}"
                        )
                        return@launch
                    }
                if (!patch.changed) return@launch
                val latestSwitch = _state.value.serverSwitchState
                if (!latestSwitch.isCurrent(request.requestGeneration, patch.snapshot.server.id)) {
                    diagnosticsLogger(
                        "home_agent_status_refresh_result serverId=${patch.snapshot.server.id.value} ignored=true reason=stale_server"
                    )
                    return@launch
                }
                val currentContent = _state.value.content as? SharedHomeContentState.Content
                if (currentContent?.baseSnapshot?.server?.id != patch.snapshot.server.id) {
                    diagnosticsLogger(
                        "home_agent_status_refresh_result serverId=${patch.snapshot.server.id.value} ignored=true reason=inactive_content"
                    )
                    return@launch
                }
                diagnosticsLogger(
                    "home_agent_status_refresh_result serverId=${patch.snapshot.server.id.value} ignored=true reason=member_store_ssot"
                )
            }
    }

    private fun contentFromDurableSnapshot(
        snapshot: HomeSnapshot,
        session: StoredAuthSession,
        contentRefreshing: Boolean,
        previousContent: SharedHomeContentState.Content?
    ): SharedHomeContentState.Content {
        snapshot.sidebarOrder?.let { sidebarOrderByServer[snapshot.server.id] = it }
        val sidebarOrder = snapshot.sidebarOrder ?: sidebarOrderByServer[snapshot.server.id]
        val snapshotWithSidebarOrder =
            if (sidebarOrder != null && snapshot.sidebarOrder != sidebarOrder) {
                snapshot.copy(sidebarOrder = sidebarOrder)
            } else {
                snapshot
            }
        val baseSnapshot = snapshotWithSidebarOrder.preserveStableRowsFrom(previousContent?.baseSnapshot)
        updateConversationIdentity(baseSnapshot)
        val content = SharedHomeContentState.Content(
            snapshot = baseSnapshot.preserveStableRowsFrom(previousContent?.snapshot),
            session = session,
            contentRefreshing = contentRefreshing,
            baseSnapshot = baseSnapshot
        )
        contentByServer[baseSnapshot.server.id] = content
        return content
    }

    private suspend fun loadNotifications() {
        when (val result = dataSource.loadNotifications()) {
            is DataResult.Content ->
                reduceState(
                    _state.value.copy(
                        notifications = result.data.filterNot { it.id in dismissedLocalNotificationIds }
                    )
                )
            is DataResult.Empty ->
                reduceState(_state.value.copy(notifications = emptyList()))
            is DataResult.Error,
            is DataResult.NoPermission -> Unit
        }
    }

    /**
     * #211: load the current server's sidebar-order snapshot and merge it
     * into the active `HomeSnapshot`. Safe to call from any load path; if
     * the active content is not `Content` (e.g. Loading / Empty / Error),
     * the result is dropped because there is no snapshot to attach it to.
     * Held under [sidebarOrderMutex] so it never races with a Pin/Unpin
     * dispatched before the initial load resolves.
     */
    private suspend fun loadAndMergeSidebarOrder() {
        sidebarOrderMutex.withLock {
            val current = _state.value.content as? SharedHomeContentState.Content ?: return@withLock
            when (val result = loadSidebarOrder()) {
                is DataResult.Content ->
                    applySidebarOrderLocally(serverId = current.snapshot.server.id, next = result.data)
                is DataResult.Empty,
                is DataResult.Error,
                is DataResult.NoPermission -> Unit
            }
        }
    }

    /**
     * #211: return the current sidebar-order snapshot, fetching it from the
     * server first if it has not been loaded yet. Holds [sidebarOrderMutex]
     * so a Pin/Unpin issued before the initial load completes cannot PATCH
     * from an empty base and clobber any existing server-side pinned list.
     * Returns `null` only if the load fails or there is no active content;
     * callers must treat `null` as "do not PATCH" and surface a user-visible
     * error.
     */
    private suspend fun ensureSidebarOrderLoaded(): SettingsServerSidebarOrderSnapshot? {
        val current = _state.value.content as? SharedHomeContentState.Content ?: return null
        val serverId = current.snapshot.server.id
        val existing = current.snapshot.sidebarOrder ?: sidebarOrderByServer[serverId]
        if (existing != null) {
            if (current.snapshot.sidebarOrder == null) {
                applySidebarOrderLocally(serverId = serverId, next = existing)
            }
            return existing
        }
        return sidebarOrderMutex.withLock {
            val latest = _state.value.content as? SharedHomeContentState.Content ?: return@withLock null
            val latestServerId = latest.snapshot.server.id
            val again = latest.snapshot.sidebarOrder ?: sidebarOrderByServer[latestServerId]
            if (again != null) {
                if (latest.snapshot.sidebarOrder == null) {
                    applySidebarOrderLocally(serverId = latestServerId, next = again)
                }
                return@withLock again
            }
            val fetched = loadSidebarOrder()
            if (fetched is DataResult.Content) {
                applySidebarOrderLocally(serverId = latestServerId, next = fetched.data)
                fetched.data
            } else {
                null
            }
        }
    }

    private fun applySidebarOrderLocally(
        serverId: ServerId,
        next: SettingsServerSidebarOrderSnapshot
    ): Boolean {
        sidebarOrderByServer[serverId] = next
        val current = _state.value.content as? SharedHomeContentState.Content ?: return false
        if (current.snapshot.server.id != serverId) return false
        if (current.snapshot.sidebarOrder == next) return false
        val nextContent =
            current.copy(
                snapshot = current.snapshot.copy(sidebarOrder = next),
                baseSnapshot = current.baseSnapshot.copy(sidebarOrder = next)
            )
        contentByServer[serverId] = nextContent
        reduceState(
            _state.value.copy(
                content = nextContent
            )
        )
        return true
    }

    private suspend fun togglePinned(conversationId: String) {
        val serverId = (_state.value.content as? SharedHomeContentState.Content)?.snapshot?.server?.id
        if (serverId == null) {
            pinUpdateFailureCallback("Unable to update pinned channels: home not loaded")
            return
        }
        val previous = ensureSidebarOrderLoaded()
        if (previous == null) {
            pinUpdateFailureCallback("Unable to update pinned channels: sidebar order not loaded")
            return
        }
        val isCurrentlyPinned = previous.pinnedChannelIds.contains(conversationId)
        val next = if (isCurrentlyPinned) {
            previous.copy(
                pinnedChannelIds = previous.pinnedChannelIds - conversationId,
                pinnedOrder = previous.pinnedOrder.filterNot { it == conversationId }
            )
        } else {
            previous.copy(
                pinnedChannelIds = previous.pinnedChannelIds + conversationId,
                pinnedOrder = previous.pinnedOrder + conversationId
            )
        }
        applySidebarOrderLocally(serverId = serverId, next = next)
        val request = SettingsServerSidebarOrderUpdateRequest(
            pinnedChannelIds = next.pinnedChannelIds,
            pinnedOrder = next.pinnedOrder
        )
        when (val result = updateSidebarOrder(request)) {
            is DataResult.Content -> applySidebarOrderLocally(serverId = serverId, next = result.data)
            is DataResult.Empty -> Unit
            is DataResult.Error,
            is DataResult.NoPermission -> {
                applySidebarOrderLocally(serverId = serverId, next = previous)
                pinUpdateFailureCallback("Unable to update pinned channels")
            }
        }
    }

    private suspend fun setPinnedSortMode(mode: SlockSidebarSortMode) {
        val serverId = (_state.value.content as? SharedHomeContentState.Content)?.snapshot?.server?.id
        if (serverId == null) {
            pinUpdateFailureCallback("Unable to update pinned sort mode: home not loaded")
            return
        }
        val previous = ensureSidebarOrderLoaded()
        if (previous == null) {
            pinUpdateFailureCallback("Unable to update pinned sort mode: sidebar order not loaded")
            return
        }
        if (previous.pinnedSortMode == mode.wireValue) return
        val next = previous.copy(pinnedSortMode = mode.wireValue)
        applySidebarOrderLocally(serverId = serverId, next = next)
        val request = SettingsServerSidebarOrderUpdateRequest(
            pinnedSortMode = mode.wireValue
        )
        when (val result = updateSidebarOrder(request)) {
            is DataResult.Content -> applySidebarOrderLocally(serverId = serverId, next = result.data)
            is DataResult.Empty -> Unit
            is DataResult.Error,
            is DataResult.NoPermission -> {
                applySidebarOrderLocally(serverId = serverId, next = previous)
                pinUpdateFailureCallback("Unable to update pinned sort mode")
            }
        }
    }

    private suspend fun reorderSidebar(intent: SharedHomeIntent.ReorderSidebar) {
        val current = _state.value.content as? SharedHomeContentState.Content
        if (current == null) {
            pinUpdateFailureCallback("Unable to reorder sidebar: home not loaded")
            return
        }
        val serverId = current.snapshot.server.id
        val previous = ensureSidebarOrderLoaded()
        if (previous == null) {
            pinUpdateFailureCallback("Unable to reorder sidebar: sidebar order not loaded")
            return
        }
        val jointChannelIds =
            current.snapshot.channels
                .filter { it.kind == ConversationKind.Joint }
                .map { it.threadId.value }
                .toSet()
        val knownChannelIds = current.snapshot.channels.map { it.threadId.value }
        var next = previous
        if (intent.channelOrder != null) {
            val regularChannelIds = knownChannelIds.toSet() - jointChannelIds
            val cleanedOrder = intent.channelOrder.distinct().filter { it.isNotBlank() && it in regularChannelIds }
            if (cleanedOrder.isNotEmpty()) {
                val nextChannelOrder =
                    (cleanedOrder +
                        previous.channelOrder.filterNot { it in regularChannelIds } +
                        knownChannelIds.filterNot { it in cleanedOrder || it in previous.channelOrder })
                        .distinct()
                if (nextChannelOrder != previous.channelOrder) {
                    next = next.copy(
                        channelOrder = nextChannelOrder,
                        channelSortMode = SlockSidebarSortMode.Manual.wireValue
                    )
                }
            }
        }
        if (intent.jointChannelOrder != null) {
            val cleanedJoint = intent.jointChannelOrder.distinct().filter { it.isNotBlank() && it in jointChannelIds }
            if (cleanedJoint.isNotEmpty()) {
                // Joint channels share channelOrder with regular channels; the
                // sections render separately, so only the relative joint order
                // inside channelOrder matters.
                val nextChannelOrder =
                    (next.channelOrder.filterNot { it in jointChannelIds } + cleanedJoint +
                        jointChannelIds.filterNot { it in cleanedJoint })
                        .distinct()
                if (nextChannelOrder != next.channelOrder) {
                    next = next.copy(
                        channelOrder = nextChannelOrder,
                        channelSortMode = SlockSidebarSortMode.Manual.wireValue
                    )
                }
            }
        }
        if (intent.dmOrder != null) {
            val knownDmIds = current.snapshot.directMessages.map { it.threadId.value }.toSet()
            val cleanedDms = intent.dmOrder.distinct().filter { it.isNotBlank() && it in knownDmIds }
            if (cleanedDms.isNotEmpty()) {
                val nextDmOrder =
                    (cleanedDms + previous.dmOrder.filterNot { it in cleanedDms }).distinct()
                if (nextDmOrder != previous.dmOrder) {
                    next = next.copy(
                        dmOrder = nextDmOrder,
                        dmSortMode = SlockSidebarSortMode.Manual.wireValue
                    )
                }
            }
        }
        if (intent.pinnedOrder != null) {
            val pinnedIdSet = previous.pinnedChannelIds.toSet() + previous.pinnedAgentIds.toSet()
            val cleanedPinned = intent.pinnedOrder.distinct().filter { it.isNotBlank() && it in pinnedIdSet }
            if (cleanedPinned.isNotEmpty()) {
                val nextPinnedOrder =
                    (cleanedPinned + previous.pinnedOrder.filterNot { it in cleanedPinned }).distinct()
                if (nextPinnedOrder != previous.pinnedOrder) {
                    next = next.copy(
                        pinnedOrder = nextPinnedOrder,
                        pinnedSortMode = SlockSidebarSortMode.Manual.wireValue
                    )
                }
            }
        }
        if (next == previous) return
        applySidebarOrderLocally(serverId = serverId, next = next)
        val request = SettingsServerSidebarOrderUpdateRequest(
            channelOrder = next.channelOrder.takeIf { next.channelOrder != previous.channelOrder },
            channelSortMode = next.channelSortMode.takeIf { next.channelOrder != previous.channelOrder || next.channelSortMode != previous.channelSortMode },
            dmOrder = next.dmOrder.takeIf { next.dmOrder != previous.dmOrder },
            dmSortMode = next.dmSortMode.takeIf { next.dmOrder != previous.dmOrder || next.dmSortMode != previous.dmSortMode },
            pinnedOrder = next.pinnedOrder.takeIf { next.pinnedOrder != previous.pinnedOrder },
            pinnedSortMode = next.pinnedSortMode.takeIf { next.pinnedOrder != previous.pinnedOrder || next.pinnedSortMode != previous.pinnedSortMode }
        )
        when (val result = updateSidebarOrder(request)) {
            is DataResult.Content -> applySidebarOrderLocally(serverId = serverId, next = result.data)
            is DataResult.Empty -> Unit
            is DataResult.Error,
            is DataResult.NoPermission -> {
                applySidebarOrderLocally(serverId = serverId, next = previous)
                pinUpdateFailureCallback("Unable to reorder sidebar")
            }
        }
    }

    private suspend fun setChannelSortMode(mode: SlockSidebarSortMode) {
        val serverId = (_state.value.content as? SharedHomeContentState.Content)?.snapshot?.server?.id
        if (serverId == null) {
            pinUpdateFailureCallback("Unable to update channel sort mode: home not loaded")
            return
        }
        val previous = ensureSidebarOrderLoaded()
        if (previous == null) {
            pinUpdateFailureCallback("Unable to update channel sort mode: sidebar order not loaded")
            return
        }
        if (previous.channelSortMode == mode.wireValue) return
        val next = previous.copy(channelSortMode = mode.wireValue)
        applySidebarOrderLocally(serverId = serverId, next = next)
        when (val result = updateSidebarOrder(SettingsServerSidebarOrderUpdateRequest(channelSortMode = next.channelSortMode))) {
            is DataResult.Content -> applySidebarOrderLocally(serverId = serverId, next = result.data)
            is DataResult.Empty -> Unit
            is DataResult.Error,
            is DataResult.NoPermission -> {
                applySidebarOrderLocally(serverId = serverId, next = previous)
                pinUpdateFailureCallback("Unable to update channel sort mode")
            }
        }
    }

    private suspend fun markConversationRead(conversationId: String) {
        val current = _state.value.content as? SharedHomeContentState.Content
        val target = current?.snapshot?.findHomeConversation(conversationId)
        if (current == null || target == null) {
            pinUpdateFailureCallback("Unable to mark conversation read: home not loaded")
            return
        }
        if (target.unreadCount <= 0) return
        val previous = current
        val channelId = target.messageStreamChannelId()
        applyHomeSnapshotLocally { it.withConversationUnread(conversationId, 0) }
        when (markChannelReadAll(channelId)) {
            is DataResult.Content,
            is DataResult.Empty -> Unit
            is DataResult.Error,
            is DataResult.NoPermission -> {
                rollbackHomeContent(previous)
                pinUpdateFailureCallback("Unable to mark conversation read")
            }
        }
    }

    private suspend fun markConversationUnread(conversationId: String) {
        val current = _state.value.content as? SharedHomeContentState.Content
        val target = current?.snapshot?.findHomeConversation(conversationId)
        if (current == null || target == null) {
            pinUpdateFailureCallback("Unable to mark conversation unread: home not loaded")
            return
        }
        val previous = current
        val channelId = target.messageStreamChannelId()
        val nextUnread = target.unreadCount.takeIf { it > 0 } ?: 1
        applyHomeSnapshotLocally { it.withConversationUnread(conversationId, nextUnread) }
        when (markChannelUnread(channelId)) {
            is DataResult.Content,
            is DataResult.Empty -> Unit
            is DataResult.Error,
            is DataResult.NoPermission -> {
                rollbackHomeContent(previous)
                pinUpdateFailureCallback("Unable to mark conversation unread")
            }
        }
    }

    private suspend fun closeDirectMessage(conversationId: String) {
        val serverId = (_state.value.content as? SharedHomeContentState.Content)?.snapshot?.server?.id
        if (serverId == null) {
            pinUpdateFailureCallback("Unable to close chat: home not loaded")
            return
        }
        val previous = ensureSidebarOrderLoaded()
        if (previous == null) {
            pinUpdateFailureCallback("Unable to close chat: sidebar order not loaded")
            return
        }
        if (conversationId in previous.hiddenDmIds) return
        val next = previous.copy(hiddenDmIds = previous.hiddenDmIds + conversationId)
        val previousState = _state.value
        applySidebarOrderLocally(serverId = serverId, next = next)
        if (_state.value.selectedConversationId == conversationId) {
            reduceState(_state.value.copy(selectedConversationId = null))
        }
        when (val result = updateSidebarOrder(SettingsServerSidebarOrderUpdateRequest(hiddenDmIds = next.hiddenDmIds))) {
            is DataResult.Content -> applySidebarOrderLocally(serverId = serverId, next = result.data)
            is DataResult.Empty -> Unit
            is DataResult.Error,
            is DataResult.NoPermission -> {
                reduceState(previousState)
                sidebarOrderByServer[serverId] = previous
                pinUpdateFailureCallback("Unable to close chat")
            }
        }
    }

    private suspend fun archiveConversationChannel(conversationId: String) {
        val current = _state.value.content as? SharedHomeContentState.Content
        val target = current?.snapshot?.findHomeConversation(conversationId)
        if (current == null || target == null) {
            pinUpdateFailureCallback("Unable to archive channel: home not loaded")
            return
        }
        val channelId = target.messageStreamChannelId()
        val previousState = _state.value
        applyHomeSnapshotLocally { it.withoutHomeConversation(conversationId) }
        if (_state.value.selectedConversationId == conversationId) {
            reduceState(_state.value.copy(selectedConversationId = null))
        }
        when (archiveChannel(channelId)) {
            is DataResult.Content,
            is DataResult.Empty -> Unit
            is DataResult.Error,
            is DataResult.NoPermission -> {
                reduceState(previousState)
                pinUpdateFailureCallback("Unable to archive channel")
            }
        }
    }

    private fun applyHomeSnapshotLocally(transform: (HomeSnapshot) -> HomeSnapshot) {
        val current = _state.value.content as? SharedHomeContentState.Content ?: return
        val nextSnapshot = transform(current.snapshot)
        val nextBaseSnapshot = transform(current.baseSnapshot)
        updateConversationIdentity(nextBaseSnapshot)
        val nextContent =
            current.copy(
                snapshot = nextSnapshot,
                baseSnapshot = nextBaseSnapshot
            )
        contentByServer[nextBaseSnapshot.server.id] = nextContent
        reduceState(
            _state.value.copy(
                content = nextContent,
                serverUnreadCounts = nextSnapshot.serverUnreadCounts
            )
        )
    }

    private fun rollbackHomeContent(previous: SharedHomeContentState.Content) {
        updateConversationIdentity(previous.baseSnapshot)
        contentByServer[previous.baseSnapshot.server.id] = previous
        reduceState(_state.value.copy(content = previous))
    }

    private fun updateConversationIdentity(snapshot: HomeSnapshot) {
        val serverKey = snapshot.server.id.value
        val next =
            conversationIdentityStates.value +
                (serverKey to snapshot.toSharedConversationDirectoryState(logger = diagnosticsLogger))
        if (next != conversationIdentityStates.value) {
            conversationIdentityStates.value = next
        }
    }

    private suspend fun dismissNotification(id: String) {
        val previous = _state.value.notifications
        val next = previous.filterNot { it.id == id }
        if (next == previous) return
        reduceState(_state.value.copy(notifications = next))
        val isLocalNotification = id.isLocalHomeNotificationId()
        if (isLocalNotification) {
            dismissedLocalNotificationIds += id
            return
        }
        when (dataSource.dismissNotification(id)) {
            is DataResult.Content,
            is DataResult.Empty -> Unit
            is DataResult.Error,
            is DataResult.NoPermission ->
                reduceState(_state.value.copy(notifications = previous))
        }
    }

    private fun upsertNotification(notification: SharedHomeNotification) {
        dismissedLocalNotificationIds.remove(notification.id)
        val previous = _state.value.notifications
        val next =
            if (previous.any { it.id == notification.id }) {
                previous.map { existing ->
                    if (existing.id == notification.id) notification else existing
                }
            } else {
                listOf(notification) + previous
            }
        reduceState(_state.value.copy(notifications = next))
    }

    private fun reduceState(nextState: SharedHomeState): Boolean {
        val previousState = _state.value
        val intent = stateDiagnosticsIntent
        val finalState =
            if (intent == null || persistSlices.isEmpty()) {
                nextState
            } else {
                persistSlices.fold(nextState) { state, slice ->
                    slice.reduce(previous = previousState, intent = intent, reduced = state)
                }
            }
        if (finalState == previousState) return false
        (finalState.content as? SharedHomeContentState.Content)?.let { content ->
            updateConversationIdentity(content.baseSnapshot)
            contentByServer[content.baseSnapshot.server.id] = content
            content.baseSnapshot.sidebarOrder?.let { sidebarOrder ->
                sidebarOrderByServer[content.baseSnapshot.server.id] = sidebarOrder
            }
        }
        _state.value = finalState
        if (intent != null) {
            persistSlices.forEach { slice ->
                slice.onTransition(previous = previousState, intent = intent, next = finalState)
            }
        }
        diagnosticsLogger(
            "shared_home_state_change reason=$stateDiagnosticsReason " +
                "previous=${previousState.toSharedHomeStateDiagnosticsSummary()} " +
                "next=${finalState.toSharedHomeStateDiagnosticsSummary()}"
        )
        return true
    }

}

private val HomeDirectMessageRow.isAgentDirectMessage: Boolean
    get() =
        kind == ConversationKind.DirectMessage &&
            directMessagePeerType.equals("agent", ignoreCase = true)

class SharedHomeViewModel(
    authStore: SlockAuthSessionStore,
    dataSource: SharedHomeDataSource,
    diagnosticsLogger: (String) -> Unit = {},
    loadSidebarOrder: suspend () -> DataResult<SettingsServerSidebarOrderSnapshot> = {
        DataResult.Empty(EmptyReason.NoSettingsAvailable)
    },
    updateSidebarOrder: suspend (SettingsServerSidebarOrderUpdateRequest) -> DataResult<SettingsServerSidebarOrderSnapshot> = {
        DataResult.Empty(EmptyReason.NoSettingsAvailable)
    },
    markChannelReadAll: suspend (ChannelId) -> DataResult<ReadReceiptResult> = {
        DataResult.Empty(EmptyReason.NoMessages)
    },
    markChannelUnread: suspend (ChannelId) -> DataResult<ReadReceiptResult> = {
        DataResult.Empty(EmptyReason.NoMessages)
    },
    archiveChannel: suspend (ChannelId) -> DataResult<ChannelArchiveResult> = {
        DataResult.Empty(EmptyReason.NoChannels)
    },
    pinUpdateFailureCallback: (String) -> Unit = {},
    persistSlices: List<MviPersistSlice<SharedHomeState, SharedHomeIntent>> = emptyList()
) : ViewModel() {
    private val storeDelegate =
        lazy {
            SharedHomeStore(
                authStore = authStore,
                dataSource = dataSource,
                scope = viewModelScope,
                diagnosticsLogger = diagnosticsLogger,
                loadSidebarOrder = loadSidebarOrder,
                updateSidebarOrder = updateSidebarOrder,
                markChannelReadAll = markChannelReadAll,
                markChannelUnread = markChannelUnread,
                archiveChannel = archiveChannel,
                pinUpdateFailureCallback = pinUpdateFailureCallback,
                persistSlices = persistSlices
            )
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

/**
 * #81: derive an [AppBadgeCount] from a freshly loaded snapshot. Centralized
 * so every code path (cached load, network load, realtime increment) goes
 * through the same provenance + freshness rules. KDoc mirrors [AppBadgeCount].
 */
internal fun snapshotToBadgeCount(
    snapshot: HomeSnapshot,
    selectedServerId: build.raft.app.model.ServerId?,
    isStale: Boolean = false,
    nowMillis: () -> Long = { defaultNowMillis() }
): AppBadgeCount =
    AppBadgeCount(
        count = snapshot.unreadTotal,
        source = snapshot.unreadSource,
        selectedServerId = selectedServerId,
        lastUpdated = nowMillis(),
        isStale = isStale
    )

/**
 * #83: KMP-friendly wall-clock source.
 *
 * `kotlin.time.Clock.System` (used in the first cut of #81) is not
 * available on the OHOS K/N target we ship (`2.0.21-KBA-010`).
 * Instead of pulling `kotlinx-datetime` as a new dep, we use a tiny
 * `expect/actual` so each platform can use its own JVM/host
 * `currentTimeMillis`.
 *
 * The `expect` lives in commonMain; the `actual`s are in
 * `androidMain` (uses `System.currentTimeMillis` from the JVM
 * runtime) and `ohosArm64Main` (uses platform-specific
 * `kotlin.system.getTimeMillis`).
 *
 * `kotlin.system.getTimeMillis()` was the simpler alternative
 * explored first; it does NOT work because
 * `kotlin-stdlib-2.0.21-KBA-010` doesn't ship a commonMain
 * implementation (only platform-specific ones), so commonMain
 * can't resolve it. Hence the explicit `expect/actual`.
 *
 * For unit tests, callers inject a fixed clock via
 * [SharedHomeStore]'s `nowMillis` constructor param; the `expect` /
 * `actual` pair is only consulted when no test clock is provided.
 */
internal expect fun defaultNowMillis(): Long

private fun HomeSnapshot.findHomeConversation(conversationId: String): HomeConversationRow? =
    (channels + directMessages).firstOrNull { it.matchesHomeConversation(conversationId) }

private fun SharedHomeIntent.toSharedHomeIntentDiagnosticsLabel(): String =
    when (this) {
        SharedHomeIntent.Start -> "Start"
        SharedHomeIntent.Refresh -> "Refresh"
        SharedHomeIntent.ForegroundRefresh -> "ForegroundRefresh"
        SharedHomeIntent.ToggleServerSwitcher -> "ToggleServerSwitcher"
        SharedHomeIntent.DismissServerSwitcher -> "DismissServerSwitcher"
        is SharedHomeIntent.SelectConversation -> "SelectConversation:$threadId"
        is SharedHomeIntent.SelectTopAction -> "SelectTopAction:$action"
        is SharedHomeIntent.SelectServer -> "SelectServer:$serverId"
        is SharedHomeIntent.ApplyRealtimeEvent -> "ApplyRealtimeEvent:$serverId:${event.toSharedHomeRealtimeEventDiagnosticsLabel()}"
        SharedHomeIntent.RefreshNotifications -> "RefreshNotifications"
        is SharedHomeIntent.UpsertNotification -> "UpsertNotification:${notification.id}"
        is SharedHomeIntent.DismissNotification -> "DismissNotification:$id"
        is SharedHomeIntent.ChannelCreated -> "ChannelCreated:${channel.threadId.value}"
        is SharedHomeIntent.SetSurfaceVisible -> "SetSurfaceVisible:$visible"
        is SharedHomeIntent.TogglePinned -> "TogglePinned:$conversationId"
        is SharedHomeIntent.SetPinnedSortMode -> "SetPinnedSortMode:$mode"
        is SharedHomeIntent.SetChannelSortMode -> "SetChannelSortMode:$mode"
        is SharedHomeIntent.ReorderSidebar -> "ReorderSidebar:${channelOrder?.size ?: 0}/${jointChannelOrder?.size ?: 0}/${dmOrder?.size ?: 0}/${pinnedOrder?.size ?: 0}"
        is SharedHomeIntent.MarkRead -> "MarkRead:$conversationId"
        is SharedHomeIntent.MarkUnread -> "MarkUnread:$conversationId"
        is SharedHomeIntent.CloseDirectMessage -> "CloseDirectMessage:$conversationId"
        is SharedHomeIntent.ArchiveChannel -> "ArchiveChannel:$conversationId"
    }

private fun RealtimeEvent.toSharedHomeRealtimeEventDiagnosticsLabel(): String =
    toString().substringBefore("(").takeIf { it.isNotBlank() } ?: "RealtimeEvent"

private fun SharedHomeState.toSharedHomeStateDiagnosticsSummary(): String =
    buildString {
        append("content=")
        append(content.toSharedHomeContentDiagnosticsSummary())
        append(" selectedConversation=")
        append(selectedConversationId ?: "_")
        append(" selectedTopAction=")
        append(selectedTopAction ?: "_")
        append(" serverSwitcherExpanded=")
        append(serverSwitcherExpanded)
        append(" switchSelected=")
        append(serverSwitchState.selectedServerId ?: "_")
        append(" homeRealtimeDirty=")
        append(homeRealtimeDirty)
        append(" notifications=")
        append(notifications.size)
        append(" badge=")
        append(appBadgeCount.count)
        append(":")
        append(appBadgeCount.source)
        append(":stale=")
        append(appBadgeCount.isStale)
    }

private data class HomeRealtimeMessagePatch(
    val messages: List<Message>,
    val incrementUnread: Boolean
)

private data class HomeRealtimeConversationPatch(
    val conversation: ConversationSummary,
    val unreadIncrement: Int
)

private data class HomeRealtimeChannelRowPatch(
    val conversation: HomeChannelRow,
    val unreadIncrement: Int
)

private data class HomeRealtimeDirectMessageRowPatch(
    val conversation: HomeDirectMessageRow,
    val unreadIncrement: Int
)

private fun HomeSnapshot.withRealtimeMessages(
    patch: HomeRealtimeMessagePatch,
    activeConversationId: String?
): HomeSnapshot {
    if (patch.messages.isEmpty()) return this
    var next = this
    patch.messages
        .groupBy { message -> message.channelId ?: ChannelId(message.threadId.value) }
        .forEach { (streamChannelId, streamMessages) ->
            next =
                next.withRealtimeMessagesForStream(
                    streamChannelId = streamChannelId,
                    messages = streamMessages,
                    incrementUnread = patch.incrementUnread,
                    activeConversationId = activeConversationId
                )
        }
    return next
}

private fun HomeSnapshot.withRealtimeMessagesForStream(
    streamChannelId: ChannelId,
    messages: List<Message>,
    incrementUnread: Boolean,
    activeConversationId: String?
): HomeSnapshot {
    val latest = messages.maxByOrNull { it.seq ?: Long.MIN_VALUE } ?: return this
    val channelIndex = channels.indexOfFirst { it.messageStreamChannelId() == streamChannelId }
    val directMessageIndex =
        if (channelIndex < 0) {
            directMessages.indexOfFirst { it.messageStreamChannelId() == streamChannelId }
        } else {
            -1
        }
    if (channelIndex < 0 && directMessageIndex < 0) return this
    return if (channelIndex >= 0) {
        val currentConversation = channels[channelIndex]
        val conversationPatch =
            currentConversation.withRealtimePreview(
                latest = latest,
                messages = messages,
                incrementUnread = incrementUnread,
                activeConversationId = activeConversationId,
                currentMemberId = currentMember.id.value
            )
        if (conversationPatch.conversation == currentConversation) return this
        val nextUnreadTotal = (unreadTotal + conversationPatch.unreadIncrement).coerceAtLeast(0)
        val nextServerUnreadCount = (server.unreadCount + conversationPatch.unreadIncrement).coerceAtLeast(0)
        copy(
            channels = channels.replaceAt(channelIndex, conversationPatch.conversation),
            server = server.copy(unreadCount = nextServerUnreadCount),
            unreadTotal = nextUnreadTotal,
            serverUnreadCounts = serverUnreadCounts + (server.id.value to nextServerUnreadCount)
        )
    } else {
        val currentConversation = directMessages[directMessageIndex]
        val conversationPatch =
            currentConversation.withRealtimePreview(
                latest = latest,
                messages = messages,
                incrementUnread = incrementUnread,
                activeConversationId = activeConversationId,
                currentMemberId = currentMember.id.value
            )
        if (conversationPatch.conversation == currentConversation) return this
        val nextUnreadTotal = (unreadTotal + conversationPatch.unreadIncrement).coerceAtLeast(0)
        val nextServerUnreadCount = (server.unreadCount + conversationPatch.unreadIncrement).coerceAtLeast(0)
        copy(
            directMessages = directMessages.replaceAt(directMessageIndex, conversationPatch.conversation),
            server = server.copy(unreadCount = nextServerUnreadCount),
            unreadTotal = nextUnreadTotal,
            serverUnreadCounts = serverUnreadCounts + (server.id.value to nextServerUnreadCount)
        )
    }
}

private fun HomeChannelRow.withRealtimePreview(
    latest: Message,
    messages: List<Message>,
    incrementUnread: Boolean,
    activeConversationId: String?,
    currentMemberId: String
): HomeRealtimeChannelRowPatch {
    val patch =
        toConversationSummary().withRealtimePreview(
            latest = latest,
            messages = messages,
            incrementUnread = incrementUnread,
            activeConversationId = activeConversationId,
            currentMemberId = currentMemberId
        )
    return HomeRealtimeChannelRowPatch(patch.conversation.toHomeChannelRow(), patch.unreadIncrement)
}

private fun HomeDirectMessageRow.withRealtimePreview(
    latest: Message,
    messages: List<Message>,
    incrementUnread: Boolean,
    activeConversationId: String?,
    currentMemberId: String
): HomeRealtimeDirectMessageRowPatch {
    val patch =
        toConversationSummary().withRealtimePreview(
            latest = latest,
            messages = messages,
            incrementUnread = incrementUnread,
            activeConversationId = activeConversationId,
            currentMemberId = currentMemberId
        )
    return HomeRealtimeDirectMessageRowPatch(patch.conversation.toHomeDirectMessageRow(), patch.unreadIncrement)
}

private fun ConversationSummary.withRealtimePreview(
    latest: Message,
    messages: List<Message>,
    incrementUnread: Boolean,
    activeConversationId: String?,
    currentMemberId: String
): HomeRealtimeConversationPatch {
    if (!incrementUnread && latest.id != lastMessageId) {
        return HomeRealtimeConversationPatch(this, unreadIncrement = 0)
    }
    val isActiveConversation =
        activeConversationId != null &&
            (
                threadId.value == activeConversationId ||
                    channelId?.value == activeConversationId ||
                    messageStreamChannelId().value == activeConversationId
                )
    val unreadCandidates =
        if (!incrementUnread || isActiveConversation) {
            emptyList()
        } else {
            messages
                .distinctBy { it.id }
                .filter { message ->
                    message.id != lastMessageId &&
                        message.author.id.value != currentMemberId
                }
        }
    val unreadIncrement = unreadCandidates.size
    val next =
        copy(
            lastMessageId = latest.id,
            lastMessagePreview = latest.body,
            lastMessageAt = latest.timestampLabel,
            lastMessageSenderName = latest.author.displayName,
            unreadCount = unreadCount + unreadIncrement,
            firstUnreadMessageId = firstUnreadMessageId ?: unreadCandidates.firstOrNull()?.id,
            unreadCountKnown = unreadCountKnown || unreadIncrement > 0
        )
    return HomeRealtimeConversationPatch(next, unreadIncrement)
}

private fun SharedHomeContentState.toSharedHomeContentDiagnosticsSummary(): String =
    when (this) {
        is SharedHomeContentState.Loading ->
            "Loading(session=${session?.currentServerId ?: "_"})"
        is SharedHomeContentState.Empty ->
            "Empty(session=${session.currentServerId ?: "_"})"
        is SharedHomeContentState.Error ->
            "Error($code,session=${session?.currentServerId ?: "_"})"
        is SharedHomeContentState.Content ->
            "Content(server=${snapshot.server.id.value},channels=${snapshot.channels.size},dms=${snapshot.directMessages.size}," +
                "channelDrafts=${snapshot.channels.count { it.hasDraft }},dmDrafts=${snapshot.directMessages.count { it.hasDraft }}," +
                "refreshing=$contentRefreshing,unread=${snapshot.unreadTotal}:${snapshot.unreadSource})"
    }

private fun HomeSnapshot.withConversationUnread(
    conversationId: String,
    unreadCount: Int
): HomeSnapshot {
    var delta = 0
    fun HomeChannelRow.updated(): HomeChannelRow {
        if (!matchesHomeConversation(conversationId)) return this
        val nextUnread = unreadCount.coerceAtLeast(0)
        delta += nextUnread - this.unreadCount
        return copy(
            unreadCount = nextUnread,
            unreadCountKnown = true,
            firstUnreadMessageId = if (nextUnread > 0) firstUnreadMessageId else null
        )
    }
    fun HomeDirectMessageRow.updated(): HomeDirectMessageRow {
        if (!matchesHomeConversation(conversationId)) return this
        val nextUnread = unreadCount.coerceAtLeast(0)
        delta += nextUnread - this.unreadCount
        return copy(
            unreadCount = nextUnread,
            unreadCountKnown = true,
            firstUnreadMessageId = if (nextUnread > 0) firstUnreadMessageId else null
        )
    }
    val nextChannels = channels.map { it.updated() }
    val nextDirectMessages = directMessages.map { it.updated() }
    if (delta == 0) {
        return copy(channels = nextChannels, directMessages = nextDirectMessages)
    }
    val nextUnreadTotal = (unreadTotal + delta).coerceAtLeast(0)
    val nextServerUnreadCount = (server.unreadCount + delta).coerceAtLeast(0)
    return copy(
        channels = nextChannels,
        directMessages = nextDirectMessages,
        server = server.copy(unreadCount = nextServerUnreadCount),
        unreadTotal = nextUnreadTotal,
        serverUnreadCounts = serverUnreadCounts + (server.id.value to nextServerUnreadCount)
    )
}

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> {
    val updated = toMutableList()
    updated[index] = value
    return updated
}

private fun HomeSnapshot.withoutHomeConversation(conversationId: String): HomeSnapshot {
    var removedUnread = 0
    fun shouldRemove(conversation: HomeConversationRow): Boolean {
        val remove = conversation.matchesHomeConversation(conversationId)
        if (remove) removedUnread += conversation.unreadCount
        return remove
    }
    val nextChannels = channels.filterNot(::shouldRemove)
    val nextDirectMessages = directMessages.filterNot(::shouldRemove)
    val nextUnreadTotal = (unreadTotal - removedUnread).coerceAtLeast(0)
    val nextServerUnreadCount = (server.unreadCount - removedUnread).coerceAtLeast(0)
    return copy(
        channels = nextChannels,
        directMessages = nextDirectMessages,
        server = server.copy(unreadCount = nextServerUnreadCount),
        unreadTotal = nextUnreadTotal,
        serverUnreadCounts = serverUnreadCounts + (server.id.value to nextServerUnreadCount)
    )
}

private fun HomeConversationRow.matchesHomeConversation(conversationId: String): Boolean =
    threadId.value == conversationId ||
        channelId?.value == conversationId ||
        messageStreamChannelId().value == conversationId

private fun HomeConversationRow.messageStreamChannelId(): ChannelId =
    thread?.streamChannelId ?: channelId ?: ChannelId(threadId.value)

data class SharedHomeState(
    val content: SharedHomeContentState = SharedHomeContentState.Loading(),
    val serverSwitchState: HomeServerSwitchState = HomeServerSwitchState(),
    val serverSwitcherExpanded: Boolean = false,
    val selectedConversationId: String? = null,
    val selectedTopAction: SharedHomeTopAction? = null,
    val notifications: List<SharedHomeNotification> = emptyList(),
    val homeRealtimeDirty: Boolean = false,
    val serverUnreadCounts: Map<String, Int> = emptyMap(),
    /**
     * #81: typed projection of the selected-server app badge. Derived from
     * the snapshot's [SharedHomeSnapshot.unreadTotal] (which is
     * `serverUnreadTotal ?: channelUnreadTotal` per `HomeParser.kt:102-103`),
     * with explicit source provenance and refresh-failure semantics. Host
     * receives this through `bridge.appBadge(count, source)` and MUST NOT
     * branch on `source` to decide which UI to show.
     */
    val appBadgeCount: AppBadgeCount = AppBadgeCount.UNKNOWN
)

enum class SharedHomeTopAction {
    Search,
    Activity,
    Saved
}

sealed interface SharedHomeIntent {
    data object Start : SharedHomeIntent
    data object Refresh : SharedHomeIntent
    data object ForegroundRefresh : SharedHomeIntent
    data object ToggleServerSwitcher : SharedHomeIntent
    data object DismissServerSwitcher : SharedHomeIntent
    data class SelectConversation(val threadId: String) : SharedHomeIntent
    data class SelectTopAction(val action: SharedHomeTopAction) : SharedHomeIntent
    data class SelectServer(val serverId: String) : SharedHomeIntent
    data class ApplyRealtimeEvent(val serverId: String, val event: RealtimeEvent) : SharedHomeIntent
    data object RefreshNotifications : SharedHomeIntent
    data class UpsertNotification(val notification: SharedHomeNotification) : SharedHomeIntent
    data class DismissNotification(val id: String) : SharedHomeIntent
    data class ChannelCreated(val channel: HomeChannelRow) : SharedHomeIntent
    data class SetSurfaceVisible(val visible: Boolean) : SharedHomeIntent
    /**
     * #211: pin or unpin a conversation. The store applies an optimistic
     * local update to the in-memory sidebar-order snapshot, then issues a
     * PATCH `/servers/:id/sidebar-order`. On failure the previous snapshot
     * is restored and a transient error is recorded.
     */
    data class TogglePinned(val conversationId: String) : SharedHomeIntent
    /**
     * #211: change the Pinned section sort mode (Manual / Recent / A-Z).
     * Persists through the same sidebar-order PATCH as pin/unpin.
     */
    data class SetPinnedSortMode(val mode: SlockSidebarSortMode) : SharedHomeIntent
    data class SetChannelSortMode(val mode: SlockSidebarSortMode) : SharedHomeIntent
    data class ReorderSidebar(
        val channelOrder: List<String>? = null,
        val jointChannelOrder: List<String>? = null,
        val dmOrder: List<String>? = null,
        val pinnedOrder: List<String>? = null
    ) : SharedHomeIntent
    data class MarkRead(val conversationId: String) : SharedHomeIntent
    data class MarkUnread(val conversationId: String) : SharedHomeIntent
    data class CloseDirectMessage(val conversationId: String) : SharedHomeIntent
    data class ArchiveChannel(val conversationId: String) : SharedHomeIntent
}

private val SharedHomeIntent.loadsHome: Boolean
    get() =
        when (this) {
            SharedHomeIntent.Start,
            SharedHomeIntent.Refresh,
            SharedHomeIntent.ForegroundRefresh,
            is SharedHomeIntent.SelectServer -> true
            SharedHomeIntent.ToggleServerSwitcher,
            SharedHomeIntent.DismissServerSwitcher,
            is SharedHomeIntent.SelectConversation,
            is SharedHomeIntent.SelectTopAction,
            is SharedHomeIntent.ApplyRealtimeEvent,
            SharedHomeIntent.RefreshNotifications,
            is SharedHomeIntent.UpsertNotification,
            is SharedHomeIntent.DismissNotification,
            is SharedHomeIntent.ChannelCreated,
            is SharedHomeIntent.SetSurfaceVisible,
            is SharedHomeIntent.TogglePinned,
            is SharedHomeIntent.SetPinnedSortMode,
            is SharedHomeIntent.SetChannelSortMode,
            is SharedHomeIntent.ReorderSidebar,
            is SharedHomeIntent.MarkRead,
            is SharedHomeIntent.MarkUnread,
            is SharedHomeIntent.CloseDirectMessage,
            is SharedHomeIntent.ArchiveChannel -> false
        }

private val SharedHomeIntent.cancelsBackgroundRefresh: Boolean
    get() =
        when (this) {
            SharedHomeIntent.Start -> false
            SharedHomeIntent.Refresh,
            SharedHomeIntent.ForegroundRefresh,
            is SharedHomeIntent.SelectServer -> true
            SharedHomeIntent.ToggleServerSwitcher,
            SharedHomeIntent.DismissServerSwitcher,
            is SharedHomeIntent.SelectConversation,
            is SharedHomeIntent.SelectTopAction,
            is SharedHomeIntent.ApplyRealtimeEvent,
            SharedHomeIntent.RefreshNotifications,
            is SharedHomeIntent.UpsertNotification,
            is SharedHomeIntent.DismissNotification,
            is SharedHomeIntent.ChannelCreated,
            is SharedHomeIntent.SetSurfaceVisible,
            is SharedHomeIntent.TogglePinned,
            is SharedHomeIntent.SetPinnedSortMode,
            is SharedHomeIntent.SetChannelSortMode,
            is SharedHomeIntent.ReorderSidebar,
            is SharedHomeIntent.MarkRead,
            is SharedHomeIntent.MarkUnread,
            is SharedHomeIntent.CloseDirectMessage,
            is SharedHomeIntent.ArchiveChannel -> false
        }

private val SharedHomeIntent.debouncesInFlightHomeLoad: Boolean
    get() =
        when (this) {
            SharedHomeIntent.Start -> true
            SharedHomeIntent.ForegroundRefresh -> false
            SharedHomeIntent.Refresh,
            is SharedHomeIntent.SelectServer,
            SharedHomeIntent.ToggleServerSwitcher,
            SharedHomeIntent.DismissServerSwitcher,
            is SharedHomeIntent.SelectConversation,
            is SharedHomeIntent.SelectTopAction,
            is SharedHomeIntent.ApplyRealtimeEvent,
            SharedHomeIntent.RefreshNotifications,
            is SharedHomeIntent.UpsertNotification,
            is SharedHomeIntent.DismissNotification,
            is SharedHomeIntent.ChannelCreated,
            is SharedHomeIntent.SetSurfaceVisible,
            is SharedHomeIntent.TogglePinned,
            is SharedHomeIntent.SetPinnedSortMode,
            is SharedHomeIntent.SetChannelSortMode,
            is SharedHomeIntent.ReorderSidebar,
            is SharedHomeIntent.MarkRead,
            is SharedHomeIntent.MarkUnread,
            is SharedHomeIntent.CloseDirectMessage,
            is SharedHomeIntent.ArchiveChannel -> false
        }

sealed interface SharedHomeContentState {
    data class Loading(val session: StoredAuthSession? = null) : SharedHomeContentState
    data class Empty(val session: StoredAuthSession) : SharedHomeContentState
    data class Error(val code: String, val session: StoredAuthSession? = null) : SharedHomeContentState
    data class Content(
        val snapshot: HomeSnapshot,
        val session: StoredAuthSession,
        val contentRefreshing: Boolean = false,
        val baseSnapshot: HomeSnapshot = snapshot
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
        is SharedHomeContentState.Error -> SharedHomeContentState.Loading(session ?: this.session)
    }

private fun SharedHomeContentState.asRefreshFailed(code: String): SharedHomeContentState =
    when (this) {
        is SharedHomeContentState.Content -> copy(contentRefreshing = false)
        is SharedHomeContentState.Empty -> SharedHomeContentState.Error(code, session)
        is SharedHomeContentState.Loading -> SharedHomeContentState.Error(code, session)
        is SharedHomeContentState.Error -> copy(code = code)
    }

private fun SharedHomeContentState.refreshFailureMapping(): String =
    when (this) {
        is SharedHomeContentState.Content -> "keep_cached_content"
        else -> "home_error_panel"
    }

private fun RealtimeEvent.homeRealtimeDirtyReason(): String =
    when (this) {
        is DirectMessageCreated -> "direct_message_created"
        is build.raft.app.model.MessageNew -> "message_new"
        is build.raft.app.model.MessageUpdated -> "message_updated"
        is build.raft.app.model.SyncResumeResponse -> "sync_resume"
        is build.raft.app.model.ThreadUpdated -> "thread_updated"
        is build.raft.app.model.TaskCreated -> "task_created"
        is build.raft.app.model.TaskUpdated -> "task_updated"
        else -> "realtime"
    }

private fun Throwable.safeHomeStoreDiagnosticsType(): String =
    this::class.simpleName?.takeIf { it.isNotBlank() } ?: "Throwable"
