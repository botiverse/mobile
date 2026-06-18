package ai.slock.compose.data

import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.DirectMessageCreated
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.RealtimeEvent
import ai.slock.compose.model.RealtimeRefreshRequired
import ai.slock.compose.model.RealtimeRefreshScope
import ai.slock.compose.model.ServerId
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
    private val presenceFlushDelayMillis: Long = 250L,
    /**
     * #81: wall-clock source for [AppBadgeCount.lastUpdated]. Injected so
     * tests can pin a deterministic clock; production passes a
     * `System.currentTimeMillis` adapter. Use [Long] (not [kotlin.time.Instant])
     * to match the OHOS datetime klib stability rule we adopted in
     * `d56ed07d`; UI layer formats via `SlockTime` if needed.
     */
    private val nowMillis: () -> Long = { defaultNowMillis() }
) {
    private val _state = MutableStateFlow(SharedHomeState())
    val state: StateFlow<SharedHomeState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var refreshJob: Job? = null
    private var presenceFlushJob: Job? = null
    private var pendingPresenceServerId: ServerId? = null
    private val pendingPresenceEvents = linkedMapOf<MemberId, MemberPresenceUpdated>()
    private val presenceFactsByServer = linkedMapOf<ServerId, LinkedHashMap<MemberId, MemberPresenceUpdated>>()
    private var hiddenHomeSnapshotDirty: Boolean = false

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
        presenceFactsByServer.clear()
        hiddenHomeSnapshotDirty = false
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
                        selectedTopAction = null,
                        // #81: clear badge on server switch so the host
                        // stops showing a stale count from the previous
                        // server before the new server's first
                        // /servers/unread-summary arrives. See
                        // AppBadgeCount.DISABLED semantics.
                        appBadgeCount = AppBadgeCount.DISABLED
                    )
                )
                load(nextSwitch, showRefreshAffordance = true)
            }
            is SharedHomeIntent.SetSurfaceVisible ->
                setHomeSurfaceVisible(intent.visible)
        }
    }

    private fun SharedHomeState.withCreatedChannel(channel: ConversationSummary): SharedHomeState {
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
        if (!_state.value.homeSurfaceVisible) {
            applyRealtimeEventWhileHomeHidden(serverId, event)
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
            // #81: realtime increment path — the `result.data.preserveStableRowsFrom`
            // call above preserves the existing unreadSource field on the
            // snapshot, so we can derive a fresh badge from it directly.
            // lastUpdated advances, isStale clears.
            val nextContent =
                contentFromDurableSnapshot(
                    snapshot = result.data,
                    session = session,
                    contentRefreshing = currentContent?.contentRefreshing == true,
                    previousContent = currentContent
                )
            val nextBadge = snapshotToBadgeCount(
                snapshot = nextContent.baseSnapshot,
                selectedServerId = nextContent.baseSnapshot.server.id,
                isStale = false
            )
            reduceState(
                _state.value.copy(
                    content = nextContent,
                    appBadgeCount = nextBadge
                )
            )
        }
    }

    private suspend fun applyRealtimeEventWhileHomeHidden(serverId: ServerId, event: RealtimeEvent) {
        when (event) {
            is RealtimeRefreshRequired -> {
                if (event.scope == RealtimeRefreshScope.Announcements) {
                    loadNotifications()
                } else if (event.updatesHomeSnapshot()) {
                    hiddenHomeSnapshotDirty = true
                    markHomeRealtimeDirty(serverId, "refresh:${event.scope}")
                }
            }
            is MemberPresenceUpdated -> {
                if (recordPresenceFacts(serverId, listOf(event)) > 0) {
                    markHomeRealtimeDirty(serverId, "presence")
                }
            }
            else -> {
                dataSource.applyRealtimeEvent(
                    serverId = serverId,
                    event = event,
                    activeConversationId = _state.value.selectedConversationId,
                    projectUpdatedSnapshot = false
                )
                if (event.updatesHomeSnapshot()) {
                    hiddenHomeSnapshotDirty = true
                    markHomeRealtimeDirty(serverId, event.homeRealtimeDirtyReason())
                }
            }
        }
    }

    private suspend fun setHomeSurfaceVisible(visible: Boolean) {
        val current = _state.value
        if (current.homeSurfaceVisible == visible) return
        if (visible && current.homeRealtimeDirty) {
            val currentContent = current.content as? SharedHomeContentState.Content
            if (hiddenHomeSnapshotDirty) {
                hiddenHomeSnapshotDirty = false
                val nextSwitch = current.serverSwitchState.refresh()
                reduceState(
                    current.copy(
                        homeSurfaceVisible = true,
                        homeRealtimeDirty = false,
                        serverSwitchState = nextSwitch
                    )
                )
                load(nextSwitch, showRefreshAffordance = false)
                return
            }
            val nextContent =
                currentContent?.let { content ->
                    val projection = projectPresence(content.baseSnapshot)
                    content.copy(snapshot = projection.snapshot.preserveStableRowsFrom(content.snapshot))
                } ?: current.content
            reduceState(
                current.copy(
                    homeSurfaceVisible = true,
                    homeRealtimeDirty = false,
                    content = nextContent
                )
            )
            return
        }
        reduceState(current.copy(homeSurfaceVisible = visible))
    }

    private fun markHomeRealtimeDirty(serverId: ServerId, reason: String) {
        val current = _state.value
        if (current.homeRealtimeDirty) return
        diagnosticsLogger("home_realtime_dirty serverId=${serverId.value} reason=$reason")
        reduceState(current.copy(homeRealtimeDirty = true))
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
                        val previousContent = _state.value.content as? SharedHomeContentState.Content
                        // #81: cached hit also seeds the badge projection.
                        // lastUpdated is 0L here because we don't know
                        // exactly when the cache was written; isStale
                        // stays false because the cache itself is a
                        // successful previous fetch.
                        val cachedBadge = snapshotToBadgeCount(
                            snapshot = cached.data,
                            selectedServerId = activeServer,
                            isStale = false
                        )
                        // #81: dedup — if the projection is structurally
                        // equal to the current one (modulo lastUpdated),
                        // keep the previous state so the host doesn't
                        // observe a no-op state emission.
                        val effectiveCachedBadge =
                            if (cachedBadge.copy(lastUpdated = 0L) == _state.value.appBadgeCount.copy(lastUpdated = 0L)) {
                                _state.value.appBadgeCount
                            } else {
                                cachedBadge
                            }
                        reduceState(
                            _state.value.copy(
                                content =
                                    contentFromDurableSnapshot(
                                        snapshot = cached.data,
                                        session = activeSession,
                                        contentRefreshing = showRefreshAffordance,
                                        previousContent = previousContent
                                    ),
                                serverSwitchState = latestSwitch.complete(request.requestGeneration, cachedServerId),
                                appBadgeCount = effectiveCachedBadge
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
                    appBadgeCount = nextBadge
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
        val previous = pendingPresenceEvents[event.memberId]
        if (event.shouldReplacePresenceFact(previous)) {
            pendingPresenceEvents[event.memberId] = event
        }
        if (presenceFlushJob?.isActive == true) return
        presenceFlushJob =
            scope.launch {
                if (presenceFlushDelayMillis > 0) {
                    delay(presenceFlushDelayMillis)
                }
                flushPresenceEvents()
            }
    }

    private fun flushPresenceEvents() {
        val serverId = pendingPresenceServerId ?: return
        val events = pendingPresenceEvents.values.toList()
        pendingPresenceEvents.clear()
        pendingPresenceServerId = null
        presenceFlushJob = null
        if (events.isEmpty()) return
        val changedFacts = recordPresenceFacts(serverId, events)
        if (changedFacts == 0) return
        if (!_state.value.homeSurfaceVisible) {
            markHomeRealtimeDirty(serverId, "presence")
            return
        }
        val currentContent = _state.value.content as? SharedHomeContentState.Content ?: return
        val visibleServerId = currentContent.baseSnapshot.server.id
        val selectedServerId = _state.value.serverSwitchState.selectedServerId
        if (visibleServerId != serverId) return
        if (selectedServerId != null && selectedServerId != serverId.value) return
        val projection = projectPresence(currentContent.baseSnapshot)
        if (projection.changedCount == 0) return
        val nextContent =
            currentContent.copy(
                snapshot = projection.snapshot.preserveStableRowsFrom(currentContent.snapshot)
            )
        if (nextContent == currentContent) return
        diagnosticsLogger(
            "home_presence_realtime_project serverId=${serverId.value} members=${events.distinctBy { it.memberId }.size} changed=${projection.changedCount}"
        )
        reduceState(
            _state.value.copy(
                content = nextContent
            )
        )
    }

    private fun recordPresenceFacts(
        serverId: ServerId,
        events: List<MemberPresenceUpdated>
    ): Int {
        if (events.isEmpty()) return 0
        val serverFacts = presenceFactsByServer.getOrPut(serverId) { linkedMapOf() }
        var changedCount = 0
        events.forEach { event ->
            val previous = serverFacts[event.memberId]
            if (event.shouldReplacePresenceFact(previous)) {
                serverFacts[event.memberId] = event
                changedCount += 1
            }
        }
        return changedCount
    }

    private fun MemberPresenceUpdated.shouldReplacePresenceFact(previous: MemberPresenceUpdated?): Boolean {
        previous ?: return true
        val nextSeq = serverSeq
        val previousSeq = previous.serverSeq
        if (nextSeq != null && previousSeq != null && nextSeq <= previousSeq) {
            return false
        }
        return this != previous
    }

    private fun contentFromDurableSnapshot(
        snapshot: HomeSnapshot,
        session: StoredAuthSession,
        contentRefreshing: Boolean,
        previousContent: SharedHomeContentState.Content?
    ): SharedHomeContentState.Content {
        val baseSnapshot = snapshot.preserveStableRowsFrom(previousContent?.baseSnapshot)
        val projection = projectPresence(baseSnapshot)
        return SharedHomeContentState.Content(
            snapshot = projection.snapshot.preserveStableRowsFrom(previousContent?.snapshot),
            session = session,
            contentRefreshing = contentRefreshing,
            baseSnapshot = baseSnapshot
        )
    }

    private fun projectPresence(snapshot: HomeSnapshot): PresenceProjection {
        val facts = presenceFactsByServer[snapshot.server.id]?.values.orEmpty()
        val patch = snapshot.directMessages.withPresenceEvents(facts)
        if (patch.changedCount == 0) {
            return PresenceProjection(snapshot, changedCount = 0)
        }
        return PresenceProjection(
            snapshot = snapshot.copy(directMessages = patch.directMessages),
            changedCount = patch.changedCount
        )
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

/**
 * #81: derive an [AppBadgeCount] from a freshly loaded snapshot. Centralized
 * so every code path (cached load, network load, realtime increment) goes
 * through the same provenance + freshness rules. KDoc mirrors [AppBadgeCount].
 */
internal fun snapshotToBadgeCount(
    snapshot: HomeSnapshot,
    selectedServerId: ai.slock.compose.model.ServerId?,
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

data class SharedHomeState(
    val content: SharedHomeContentState = SharedHomeContentState.Loading(),
    val serverSwitchState: HomeServerSwitchState = HomeServerSwitchState(),
    val serverSwitcherExpanded: Boolean = false,
    val selectedConversationId: String? = null,
    val selectedTopAction: SharedHomeTopAction? = null,
    val notifications: List<SharedHomeNotification> = emptyList(),
    val homeSurfaceVisible: Boolean = true,
    val homeRealtimeDirty: Boolean = false,
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
    data object ToggleServerSwitcher : SharedHomeIntent
    data object DismissServerSwitcher : SharedHomeIntent
    data class SelectConversation(val threadId: String) : SharedHomeIntent
    data class SelectTopAction(val action: SharedHomeTopAction) : SharedHomeIntent
    data class SelectServer(val serverId: String) : SharedHomeIntent
    data class ApplyRealtimeEvent(val serverId: String, val event: RealtimeEvent) : SharedHomeIntent
    data class DismissNotification(val id: String) : SharedHomeIntent
    data class ChannelCreated(val channel: ConversationSummary) : SharedHomeIntent
    data class SetSurfaceVisible(val visible: Boolean) : SharedHomeIntent
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
            is SharedHomeIntent.ChannelCreated,
            is SharedHomeIntent.SetSurfaceVisible -> false
        }

sealed interface SharedHomeContentState {
    data class Loading(val session: StoredAuthSession? = null) : SharedHomeContentState
    data class Empty(val session: StoredAuthSession) : SharedHomeContentState
    data class Error(val code: String) : SharedHomeContentState
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

private data class PresencePatch(
    val directMessages: List<ConversationSummary>,
    val changedCount: Int
)

private data class PresenceProjection(
    val snapshot: HomeSnapshot,
    val changedCount: Int
)

private fun List<ConversationSummary>.withPresenceEvents(
    events: Collection<MemberPresenceUpdated>
): PresencePatch {
    if (isEmpty() || events.isEmpty()) {
        return PresencePatch(this, changedCount = 0)
    }
    val latestEvents = events.associateBy { event -> event.memberId }.values
    var changedCount = 0
    val updated =
        map { conversation ->
            val event = latestEvents.firstOrNull { presenceEvent ->
                conversation.matchesPresenceMember(presenceEvent)
            } ?: return@map conversation
            val next =
                conversation.copy(
                    presence = event.presence,
                    agentStatus = event.agentStatus ?: conversation.agentStatus,
                    presenceMemberId = conversation.presenceMemberId ?: event.memberId
                )
            if (next == conversation) {
                conversation
            } else {
                changedCount += 1
                next
            }
        }
    return if (changedCount == 0) {
        PresencePatch(this, changedCount = 0)
    } else {
        PresencePatch(updated, changedCount)
    }
}

private fun ConversationSummary.matchesPresenceMember(
    event: MemberPresenceUpdated
): Boolean =
    kind == ConversationKind.DirectMessage &&
        (event.kind == null || event.kind == MemberKind.Agent || event.kind == MemberKind.Human) &&
        (presenceMemberId == event.memberId ||
            channelId?.value == event.memberId.value ||
            threadId.value == event.memberId.value)

private fun RealtimeEvent.homeRealtimeDirtyReason(): String =
    when (this) {
        is DirectMessageCreated -> "direct_message_created"
        is ai.slock.compose.model.MessageNew -> "message_new"
        is ai.slock.compose.model.MessageUpdated -> "message_updated"
        is ai.slock.compose.model.SyncResumeResponse -> "sync_resume"
        is ai.slock.compose.model.ThreadUpdated -> "thread_updated"
        is ai.slock.compose.model.TaskCreated -> "task_created"
        is ai.slock.compose.model.TaskUpdated -> "task_updated"
        else -> "realtime"
    }

private fun Throwable.safeHomeStoreDiagnosticsType(): String =
    this::class.simpleName?.takeIf { it.isNotBlank() } ?: "Throwable"
