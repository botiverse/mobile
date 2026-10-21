package build.raft.app.data

import build.raft.app.RaftSidebarSortMode
import build.raft.app.base.putIfAbsent
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.data.realtime.PerReceiverStateScope
import build.raft.app.data.realtime.PerReceiverStateVersionStore
import build.raft.app.data.realtime.PerReceiverReadStateCheckpoint
import build.raft.app.data.realtime.PerReceiverReadStateFactResult
import build.raft.app.network.sync.ReadStateSyncApplyResult
import build.raft.app.network.sync.ReadStateSyncEvent
import build.raft.app.network.sync.ReadStateSyncState
import build.raft.app.network.sync.ReadStateV1Fact
import build.raft.app.network.sync.ReadStateV1PreparedPublication
import build.raft.app.network.sync.ReadStateV1ProductionCanary
import build.raft.app.network.sync.ReadStateV1ProductionDisposition
import build.raft.app.network.sync.ReadStateV1ProductionOwner
import build.raft.app.network.sync.reduceReadStateSyncState
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.MemberId
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.PerReceiverNotificationPrefsChanged
import build.raft.app.model.PerReceiverPinnedChanged
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.ThreadUpdated
import build.raft.app.model.mentionsUser
import build.raft.app.model.messageStreamChannelId
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class SharedConversationIdentitySupplementSource(val precedence: Int) {
    Activity(10),
    Search(20),
    Saved(30),
    Tasks(40),
    ForegroundBanner(50),
    DirectMessageOpen(60)
}

internal data class OpenedDirectMessageAuthority(
    val serverId: ServerId,
    val accountId: MemberId
)

private data class HomeNotificationAuthority(
    val accountId: String?,
    val serverId: String?,
    val canManageMachines: Boolean
)

internal class SharedHomeStore(
    private val authStore: RaftAuthSessionStore,
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
    private var loadSidebarOrder: suspend (ServerId) -> DataResult<SettingsServerSidebarOrderSnapshot> = { _ ->
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
    private var loadServerOrder: suspend () -> DataResult<SettingsServerOrderSnapshot> = {
        DataResult.Empty(EmptyReason.NoSettingsAvailable)
    },
    private var updateServerOrder: suspend (SettingsServerOrderUpdateRequest) -> DataResult<SettingsServerOrderSnapshot> = {
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
    private var setChannelActivityMuted: suspend (ChannelId, Boolean) -> DataResult<ChannelActivityMuteSettings> = { _, _ ->
        DataResult.Empty(EmptyReason.NoChannels)
    },
    private val perReceiverStateVersionStore: PerReceiverStateVersionStore =
        PerReceiverStateVersionStore(InMemoryRaftKeyValueStore()),
    private var readReceiptCoordinator: ReadReceiptCoordinator? = null,
    private var readReceiptOwnerFactory: ((String, String) -> ReadReceiptOwner)? = null,
    private var readStateV1Canary: ReadStateV1ProductionCanary? = null,
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
     * per the datetime klib stability rule adopted in
     * `d56ed07d`; UI layer formats via `RaftTime` if needed.
     */
    private val nowMillis: () -> Long = { defaultNowMillis() },
    private val beforeRealtimeHomeStateCommit: () -> Unit = {},
    private val onReadReceiptPredictionOwnerEntered: () -> Unit = {},
    private val beforeNotificationPublicationCommit: suspend () -> Unit = {},
    private val conversationIdentityPublicationSink:
        (SharedConversationIdentityCandidatePublication) -> Unit = {},
) : SharedConversationIdentityProvider {
    private val _state = raftStoreState(SharedHomeState(), "SharedHomeStore")
    val state: StateFlow<SharedHomeState> = _state.asStateFlow()
    private val homeConversationIdentityStates = linkedMapOf<String, SharedConversationDirectoryState>()
    private val supplementalConversationIdentityStates =
        linkedMapOf<String, MutableMap<SharedConversationIdentitySupplementSource, SharedConversationDirectoryState>>()
    private val conversationIdentityStates =
        MutableStateFlow<Map<String, SharedConversationDirectoryState>>(emptyMap())
    private val conversationIdentityAccountByServer = linkedMapOf<String, String>()
    /** Published copy of [conversationIdentityAccountByServer] for synchronous off-store reads. */
    private val conversationIdentityOwners = MutableStateFlow<Map<String, String>>(emptyMap())
    private val conversationIdentityRevisionByServer = linkedMapOf<String, Long>()

    private var loadJob: Job? = null
    /** Intent and wall-clock time that launched [loadJob] through [dispatch] (task #60 coalescing). */
    private var loadJobLaunchIntent: SharedHomeIntent? = null
    private var loadJobLaunchedAtMillis: Long = 0L
    /**
     * Home loads whose response is still in flight, keyed by token, valued by whether a realtime message
     * raised a local unread count meanwhile. The unread endpoints carry no message seq, so a response read
     * before that message cannot be told apart from one that counted it: such a load keeps the local unread
     * and one more load (sent after the message, which the server stored before pushing it) replaces it.
     */
    private val realtimeUnreadDirtyByLoad = mutableMapOf<Long, Boolean>()
    private val realtimeUnreadDirtyLock = SynchronizedObject()
    private var nextHomeLoadToken = 0L
    /** Monotonic fence for non-cooperative loads that outlive an auth/session reset. */
    private val homeSessionGeneration = atomic(0L)
    private var agentDirectMessageStatusJob: Job? = null
    private var serverOrderLoadJob: Job? = null
    private var notificationRequestGeneration: Long = 0L
    private val notificationOwnerLock = SynchronizedObject()
    private val contentByServer = linkedMapOf<ServerId, SharedHomeContentState.Content>()
    private val serverDrawerLoadJobs = linkedMapOf<ServerId, Job>()
    private val serverDrawerRequestGenerations = linkedMapOf<ServerId, Long>()
    // #211: serialize sidebar-order loads and mutations so a Pin/Unpin
    // arriving before the initial load resolves cannot PATCH from an empty
    // base snapshot and clobber any server-side pinned list.
    private val sidebarOrderMutex = Mutex()
    private val sidebarOrderByServer = linkedMapOf<ServerId, SettingsServerSidebarOrderSnapshot>()
    // task #322: version counter for local pin mutations (TogglePinned / SetPinnedSortMode /
    // PerReceiverPinnedChanged). The concurrent sidebar-order fetch snapshots this at
    // launch and, before applying a late-arriving result, re-checks it inside
    // `sidebarOrderMutex`: if a pin landed meanwhile, the deferred stale result is
    // dropped instead of clobbering the authoritative pin.
    private var sidebarPinChangeVersion: Long = 0L
    private var channelActivityMuteRequestSeq: Long = 0L
    private val channelActivityMuteRequests = linkedMapOf<ChannelId, ChannelActivityMuteRequest>()
    private val backgroundUnreadByScope =
        linkedMapOf<BackgroundUnreadScopeKey, BackgroundUnreadScopeProjection>()
    private var homeSurfaceVisible: Boolean = true
    private var hiddenHomeSnapshotDirty: Boolean = false
    private val dismissedLocalNotificationIds = mutableSetOf<String>()
    private var readPredictionRevisions: Map<ChannelId, Long> = emptyMap()
    // Process read predictions and local realtime message patches are concurrent producers.
    // Own both read/transform/publish sequences here so neither can publish from a stale state.
    private val readProjectionOwnerLock = SynchronizedObject()
    // A reply into a thread Home does not know reloads the summary once per window (web followedThreadsRefresh).
    private val followedThreadUnreadRefresh = HomeCoalescedRefresh(scope = scope) { refreshFollowedThreadUnread() }

    fun snapshotFor(serverId: ServerId): HomeSnapshot? =
        (_state.value.content as? SharedHomeContentState.Content)
            ?.snapshot
            ?.takeIf { snapshot -> snapshot.server.id == serverId }

    /**
     * Bounded wait for an in-flight home load, returning the resulting snapshot when it belongs to
     * [serverId]. Lets cold-start readers (e.g. thread initial access, task #387) reuse the single
     * home fetch instead of issuing a duplicate network request; returns null when no load is
     * in flight or the deadline elapses first.
     */
    suspend fun awaitSnapshotFor(serverId: ServerId, timeoutMillis: Long): HomeSnapshot? {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow()
        while (true) {
            snapshotFor(serverId)?.let { return it }
            val remaining = timeoutMillis - deadline.elapsedNow().inWholeMilliseconds
            if (remaining <= 0) return null
            val job = loadJob?.takeIf { it.isActive } ?: return null
            withTimeoutOrNull(remaining) { job.join() } ?: return null
        }
    }

    private var stateDiagnosticsReason: String = "unknown"
    private var stateDiagnosticsIntent: SharedHomeIntent? = null

    fun updateRuntimeIntegrations(
        dataSource: SharedHomeDataSource,
        diagnosticsLogger: (String) -> Unit = {},
        loadSidebarOrder: suspend (ServerId) -> DataResult<SettingsServerSidebarOrderSnapshot> = { _ ->
            DataResult.Empty(EmptyReason.NoSettingsAvailable)
        },
        updateSidebarOrder: suspend (SettingsServerSidebarOrderUpdateRequest) -> DataResult<SettingsServerSidebarOrderSnapshot> = {
            DataResult.Empty(EmptyReason.NoSettingsAvailable)
        },
        loadServerOrder: suspend () -> DataResult<SettingsServerOrderSnapshot> = {
            DataResult.Empty(EmptyReason.NoSettingsAvailable)
        },
        updateServerOrder: suspend (SettingsServerOrderUpdateRequest) -> DataResult<SettingsServerOrderSnapshot> = {
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
        setChannelActivityMuted: suspend (ChannelId, Boolean) -> DataResult<ChannelActivityMuteSettings> = { _, _ ->
            DataResult.Empty(EmptyReason.NoChannels)
        },
        readReceiptCoordinator: ReadReceiptCoordinator? = this.readReceiptCoordinator,
        readReceiptOwnerFactory: ((String, String) -> ReadReceiptOwner)? = this.readReceiptOwnerFactory,
        readStateV1Canary: ReadStateV1ProductionCanary? = this.readStateV1Canary,
        pinUpdateFailureCallback: (String) -> Unit = {}
    ) {
        loadJob?.cancel()
        agentDirectMessageStatusJob?.cancel()
        supersedeNotificationRequests()
        serverDrawerLoadJobs.values.forEach { it.cancel() }
        serverDrawerLoadJobs.clear()
        serverDrawerRequestGenerations.clear()
        loadJob = null
        agentDirectMessageStatusJob = null
        this.dataSource = dataSource
        this.diagnosticsLogger = diagnosticsLogger
        this.loadSidebarOrder = loadSidebarOrder
        this.updateSidebarOrder = updateSidebarOrder
        this.loadServerOrder = loadServerOrder
        this.updateServerOrder = updateServerOrder
        this.markChannelReadAll = markChannelReadAll
        this.markChannelUnread = markChannelUnread
        this.archiveChannel = archiveChannel
        this.setChannelActivityMuted = setChannelActivityMuted
        this.readReceiptCoordinator = readReceiptCoordinator
        this.readReceiptOwnerFactory = readReceiptOwnerFactory
        this.readStateV1Canary = readStateV1Canary
        this.pinUpdateFailureCallback = pinUpdateFailureCallback
        diagnosticsLogger("home_runtime_integrations_updated")
    }

    fun dispatch(intent: SharedHomeIntent) {
        when (intent) {
            is SharedHomeIntent.ApplyReadReceiptPrediction -> {
                applyReadReceiptPrediction(intent.prediction)
                return
            }
            is SharedHomeIntent.ApplyReadReceiptFailure -> {
                handleReadReceiptFailure(intent.request)
                return
            }
            else -> Unit
        }
        if (intent.loadsHome) {
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
            loadJobLaunchIntent = intent
            loadJobLaunchedAtMillis = nowMillis()
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

    private fun debounceInFlightHomeLoad(intent: SharedHomeIntent): Boolean {
        if (!intent.debouncesInFlightHomeLoad) return false
        return coalesceIntoInFlightHomeLoad(intent.toSharedHomeIntentDiagnosticsLabel())
    }

    /**
     * Foreground refresh requested by app/page visibility (resume, pageDidAppear) rather than by a
     * data event.
     *
     * Task #60: one return from background dispatched `Start` (shell home start check) and
     * `ForegroundRefresh` (shell pageDidAppear) in the same frame. ForegroundRefresh cancelled the
     * Start load and launched an identical one, so every resume paid two Home bootstraps (channels,
     * DMs, unread, sidebar order) and two rounds of Home state publication. The visibility refresh
     * now joins a Start/ForegroundRefresh load launched moments ago: that load was launched by the
     * same foreground transition, so its requests already carry foreground-fresh data. Older loads
     * are still replaced. Event-driven reconciles keep using [dispatch], which always refetches.
     */
    fun dispatchVisibleForegroundRefresh() {
        if (coalesceForegroundRefreshIntoFreshLoad()) return
        dispatch(SharedHomeIntent.ForegroundRefresh)
    }

    private fun coalesceForegroundRefreshIntoFreshLoad(): Boolean {
        if (loadJob?.isActive != true) return false
        val launchIntent = loadJobLaunchIntent
        if (launchIntent != SharedHomeIntent.Start && launchIntent != SharedHomeIntent.ForegroundRefresh) return false
        val ageMillis = nowMillis() - loadJobLaunchedAtMillis
        if (ageMillis !in 0L..FOREGROUND_REFRESH_COALESCE_WINDOW_MILLIS) return false
        diagnosticsLogger(
            "home_load_debounce intent=${SharedHomeIntent.ForegroundRefresh.toSharedHomeIntentDiagnosticsLabel()} " +
                "reason=fresh_in_flight_load launchedBy=${launchIntent.toSharedHomeIntentDiagnosticsLabel()} " +
                "ageMs=$ageMillis"
        )
        return true
    }

    private fun coalesceIntoInFlightHomeLoad(intentLabel: String): Boolean {
        if (loadJob?.isActive != true) return false
        diagnosticsLogger(
            "home_load_debounce intent=$intentLabel " +
                "reason=in_flight_load"
        )
        return true
    }

    fun clear() {
        loadJob?.cancel()
        loadJob = null
        supersedeNotificationRequests()
        agentDirectMessageStatusJob?.cancel()
        agentDirectMessageStatusJob = null
        serverDrawerLoadJobs.values.forEach { it.cancel() }
        serverDrawerLoadJobs.clear()
        serverDrawerRequestGenerations.clear()
        sidebarOrderByServer.clear()
        contentByServer.clear()
        backgroundUnreadByScope.clear()
        homeConversationIdentityStates.clear()
        supplementalConversationIdentityStates.clear()
        conversationIdentityAccountByServer.clear()
        conversationIdentityRevisionByServer.clear()
        conversationIdentityStates.value = emptyMap()
        conversationIdentityOwners.value = emptyMap()
        hiddenHomeSnapshotDirty = false
        followedThreadUnreadRefresh.cancel()
        synchronized(readProjectionOwnerLock) {
            readPredictionRevisions = emptyMap()
        }
    }

    /**
     * Retires the current in-memory Home projection after an auth/session boundary.
     *
     * [clear] intentionally only retires jobs and auxiliary indexes for ordinary teardown. A
     * session reset must also replace the published state, otherwise the old account's rows stay
     * visible (and can suppress the successor account's first Home load). Durable identity
     * snapshots are owned by the persistence slice and are deliberately left untouched.
     */
    fun reset() {
        homeSessionGeneration.incrementAndGet()
        clearRealtimeUnreadWatches()
        clear()
        dismissedLocalNotificationIds.clear()
        homeSurfaceVisible = true
        _state.value = SharedHomeState()
    }

    suspend fun awaitRealtimeSettled() {
        while (true) {
            val observedLoad = loadJob
            val observedAgentStatus = agentDirectMessageStatusJob
            observedLoad?.join()
            observedAgentStatus?.join()
            if (loadJob === observedLoad && agentDirectMessageStatusJob === observedAgentStatus) return
        }
    }

    fun supplementConversationIdentity(
        snapshot: InboxSnapshot,
        dmPeerNames: Map<String, String> = emptyMap()
    ) {
        supplementConversationIdentity(
            serverId = snapshot.server.id,
            source = SharedConversationIdentitySupplementSource.Activity,
            items = snapshot.items,
            dmPeerNames = dmPeerNames
        )
    }

    fun supplementConversationIdentity(
        serverId: ServerId,
        source: SharedConversationIdentitySupplementSource,
        items: List<InboxItem>,
        dmPeerNames: Map<String, String> = emptyMap()
    ) {
        val serverKey = serverId.value
        supplementalConversationIdentityStates
            .getOrPut(serverKey) { linkedMapOf() }[source] =
            items.toSharedConversationDirectoryState(serverId, dmPeerNames, logger = diagnosticsLogger)
        publishConversationIdentity(serverKey)
    }

    /**
     * Publish the canonical row returned by POST /channels/dm before navigating to Thread.
     *
     * A newly created empty DM is not necessarily present in the current Home snapshot yet. The
     * route deliberately carries identity only, so Thread needs this repository-owned directory
     * candidate to render the peer header until Home refreshes. Home remains last-writer authority
     * in [publishConversationIdentity], and an auth/server change rejects the captured response.
     */
    suspend fun supplementOpenedDirectMessage(
        authority: OpenedDirectMessageAuthority,
        conversation: HomeDirectMessageRow
    ): Boolean {
        val currentSession = authStore.readSession()
        if (
            currentSession == null ||
            currentSession.user.id != authority.accountId.value ||
            currentSession.currentServerId != authority.serverId.value
        ) {
            diagnosticsLogger(
                "conversation_directory_dm_open_drop reason=authority_changed " +
                    "serverId=${authority.serverId.value} accountMatched=${currentSession?.user?.id == authority.accountId.value}"
            )
            return false
        }
        val projection = conversation.toSharedConversationDirectoryProjection(authority.serverId)
        if (
            projection == null ||
            projection.conversationKind != ConversationKind.DirectMessage ||
            projection.peerIdentity == null
        ) {
            diagnosticsLogger(
                "conversation_directory_dm_open_drop reason=invalid_projection " +
                    "serverId=${authority.serverId.value} channelId=${conversation.channelId.value}"
            )
            return false
        }

        val serverKey = authority.serverId.value
        val ownerChanged = bindConversationIdentityOwner(serverKey, authority.accountId.value)
        val sources = supplementalConversationIdentityStates.getOrPut(serverKey) { linkedMapOf() }
        val existing = sources[SharedConversationIdentitySupplementSource.DirectMessageOpen]
        sources[SharedConversationIdentitySupplementSource.DirectMessageOpen] =
            listOfNotNull(
                existing,
                listOf(projection).toSharedConversationDirectoryState(logger = diagnosticsLogger)
            ).mergedSharedConversationDirectoryState(logger = diagnosticsLogger)
        publishConversationIdentity(serverKey, forcePublication = ownerChanged)
        diagnosticsLogger(
            "conversation_directory_dm_open_accept " +
                "serverId=$serverKey channelId=${projection.canonicalChannelId}"
        )
        return true
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

    /**
     * Owner-checked candidate lookup for [RaftKnownEntityResolver]: the in-memory Home/Activity
     * row for [id], only when this store collected [serverId]'s directory for [accountId].
     * Every server visited in this process keeps its rows, so switching back paints at once.
     */
    fun knownConversationCandidate(
        accountId: String,
        serverId: ServerId,
        id: String
    ): SharedConversationDirectoryProjection? {
        val account = accountId.trim().takeIf { it.isNotBlank() } ?: return null
        if (conversationIdentityOwners.value[serverId.value] != account) return null
        return resolveProjection(serverId, id)
    }

    suspend fun accept(intent: SharedHomeIntent) {
        withStateDiagnosticsContext(intent) {
            when (intent) {
            SharedHomeIntent.Start ->
                load(
                    _state.value.serverSwitchState,
                    showRefreshAffordance =
                        (_state.value.content as? SharedHomeContentState.Content)
                            ?.needsNetworkRefresh
                            ?: true,
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
            SharedHomeIntent.ToggleServerSwitcher -> {
                val expanding = !_state.value.serverSwitcherExpanded
                val drawer =
                    if (expanding) {
                        openedServerDrawerState(authStore.readSession())
                    } else {
                        _state.value.serverDrawer.copy(expanded = false, reorderMode = false)
                    }
                val nextIntent = _state.value.serverDrawerCommandIntent.transitionTo(expanding)
                nextIntent.supersededGeneration?.let { generation ->
                    diagnosticsLogger("server_drawer_command_superseded generation=$generation")
                }
                reduceState(
                    _state.value.copy(
                        serverSwitcherExpanded = expanding,
                        serverDrawer = drawer,
                        serverDrawerCommandIntent = nextIntent.intent
                    )
                )
                if (expanding) {
                    launchServerOrderRefreshForOpenSwitcher()
                    val currentServerId =
                        (_state.value.content as? SharedHomeContentState.Content)?.snapshot?.server?.id
                    drawer.expandedServerIds.forEach { serverId ->
                        if (drawer.sections[serverId]?.content == null || serverId != currentServerId) {
                            launchServerDrawerSectionLoad(serverId, force = false)
                        }
                    }
                }
            }
            SharedHomeIntent.DismissServerSwitcher -> {
                val nextIntent = _state.value.serverDrawerCommandIntent.transitionTo(false)
                nextIntent.supersededGeneration?.let { generation ->
                    diagnosticsLogger("server_drawer_command_superseded generation=$generation")
                }
                reduceState(
                    _state.value.copy(
                        serverSwitcherExpanded = false,
                        serverDrawer = _state.value.serverDrawer.copy(expanded = false, reorderMode = false),
                        serverDrawerCommandIntent = nextIntent.intent
                    )
                )
            }
            is SharedHomeIntent.ToggleServerDrawerSection ->
                toggleServerDrawerSection(intent.serverId)
            is SharedHomeIntent.RetryServerDrawerSection ->
                launchServerDrawerSectionLoad(intent.serverId, force = true)
            is SharedHomeIntent.SelectConversation ->
                reduceState(
                    _state.value.copy(
                        selectedConversationId = intent.threadId,
                        selectedTopAction = null
                    )
                )
            SharedHomeIntent.ClearSelectedConversation ->
                reduceState(_state.value.copy(selectedConversationId = null))
            is SharedHomeIntent.SelectTopAction ->
                reduceState(
                    _state.value.copy(
                        selectedConversationId = null,
                        selectedTopAction = intent.action
                    )
                )
            is SharedHomeIntent.ApplyRealtimeEvent ->
                applyRealtimeEvent(ServerId(intent.serverId), intent.event)
            is SharedHomeIntent.ApplyBackgroundServerUnreadEvent ->
                applyBackgroundServerUnreadEvent(ServerId(intent.serverId), intent.event)
            SharedHomeIntent.RefreshNotifications ->
                loadNotifications()
            is SharedHomeIntent.UpsertNotification ->
                upsertNotification(intent.notification)
            is SharedHomeIntent.DismissNotification ->
                dismissNotification(intent.id)
            is SharedHomeIntent.ChannelCreated ->
                reduceState(_state.value.withCreatedChannel(intent.channel))
            is SharedHomeIntent.ChannelProfileUpdated ->
                reduceState(
                    _state.value.withChannelProfile(
                        channelId = intent.channelId,
                        name = intent.name,
                        description = intent.description
                    )
                )
            is SharedHomeIntent.SelectServer -> {
                val currentServerId = (_state.value.content as? SharedHomeContentState.Content)?.snapshot?.server?.id?.value
                reconcileNotificationAuthority(authStore.readSession(), intent.serverId)
                supersedeServerDrawerRequest(ServerId(intent.serverId))
                // A pending followed-thread reload belongs to the server being left; its result
                // would be dropped anyway, so don't spend the request.
                if (intent.serverId != currentServerId) followedThreadUnreadRefresh.cancel()
                val nextSwitch =
                    if (intent.serverId == currentServerId) {
                        _state.value.serverSwitchState.refresh()
                    } else {
                        _state.value.serverSwitchState.select(intent.serverId)
                    }
                val nextDrawerIntent = _state.value.serverDrawerCommandIntent.transitionTo(false)
                nextDrawerIntent.supersededGeneration?.let { generation ->
                    diagnosticsLogger("server_drawer_command_superseded generation=$generation")
                }
                reduceState(
                    _state.value.copy(
                        serverSwitcherExpanded = false,
                        serverDrawer = _state.value.serverDrawer.copy(expanded = false, reorderMode = false),
                        serverDrawerCommandIntent = nextDrawerIntent.intent,
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
                togglePinned(intent)
            is SharedHomeIntent.SetPinnedSortMode ->
                setPinnedSortMode(intent.mode)
            is SharedHomeIntent.SetChannelSortMode ->
                setChannelSortMode(intent.mode)
            is SharedHomeIntent.SetJointChannelSortMode ->
                setJointChannelSortMode(intent.mode)
            is SharedHomeIntent.CreateSidebarSection ->
                createSidebarSection(intent.name, intent.emoji, intent.moveConversationId, intent.insertBeforeSectionId)
            is SharedHomeIntent.RenameSidebarSection ->
                renameSidebarSection(intent.sectionId, intent.name, intent.emoji)
            is SharedHomeIntent.DeleteSidebarSection ->
                deleteSidebarSection(intent.sectionId)
            is SharedHomeIntent.MoveConversationToSection ->
                moveConversationToSection(intent.conversationId, intent.sectionId)
            is SharedHomeIntent.SetSidebarSectionSortMode ->
                setSidebarSectionSortMode(intent.sectionId, intent.mode)
            is SharedHomeIntent.ReorderSidebar ->
                reorderSidebar(intent)
            is SharedHomeIntent.ReorderServers ->
                reorderServers(intent.serverOrder)
            is SharedHomeIntent.MarkRead ->
                markConversationRead(intent.conversationId)
            is SharedHomeIntent.MarkUnread ->
                markConversationUnread(intent.conversationId)
            is SharedHomeIntent.CloseDirectMessage ->
                closeDirectMessage(intent.conversationId)
            is SharedHomeIntent.ArchiveChannel ->
                archiveConversationChannel(intent.conversationId)
            is SharedHomeIntent.SetChannelActivityMuted ->
                setConversationActivityMuted(intent.conversationId, intent.muted)
            is SharedHomeIntent.ApplyPerReceiverReadState ->
                if (applyPerReceiverReadState(intent.update).requiresReconcile()) {
                    scheduleReadStateReconcile()
                }
            is SharedHomeIntent.ApplyPerReceiverNotificationPrefs ->
                applyPerReceiverNotificationPrefs(intent.update)
            is SharedHomeIntent.ApplyPerReceiverServerOrder ->
                applyPerReceiverServerOrder(intent.update)
            is SharedHomeIntent.ApplyPerReceiverPinned ->
                applyPerReceiverPinned(intent.update)
            is SharedHomeIntent.ApplyReadReceiptPrediction ->
                applyReadReceiptPrediction(intent.prediction)
            is SharedHomeIntent.ApplyReadReceiptFailure ->
                handleReadReceiptFailure(intent.request)
            is SharedHomeIntent.HydrateDirectMessagePreview ->
                hydrateDirectMessagePreview(intent)
        }
        }
    }

    /**
     * Hydrates the identity-bound Home snapshot without starting a network request.
     *
     * Cold-start callers use this while the auth gate is still reconciling a cached session.
     * The follow-up [SharedHomeIntent.Start] is deliberately separate so an expired token cannot
     * make the first paint wait behind the auth refresh. The durable slice remains the sole cache
     * reader; no product content is transported through route/pageData.
    */
    internal suspend fun hydrateCachedHome(serverId: ServerId): Boolean =
        // Called from the app shell's LaunchedEffect (main thread): hop onto the store dispatcher
        // like dispatch() does, so the Loading write and the diagnostics context stay confined.
        scope.raftWithStoreScope {
            withStateDiagnosticsContext(SharedHomeIntent.Start) {
                hydrateCachedHomeForServer(serverId)
            }
            val content = _state.value.content as? SharedHomeContentState.Content ?: return@raftWithStoreScope false
            content.isDurableIdentityCache &&
                content.needsNetworkRefresh &&
                content.snapshot.server.id == serverId &&
                content.snapshot.currentMember.id.value == content.session.user.id
        }

    private suspend fun hydrateCachedHomeForServer(serverId: ServerId) {
        val session = authStore.readSession() ?: return
        val server = session.servers.firstOrNull { it.id == serverId.value } ?: return
        val sessionForServer =
            session.copy(
                selectedServerId = server.id,
                selectedServerSlug = server.slug
            )
        val current = _state.value.content as? SharedHomeContentState.Content
        if (
            current != null &&
                current.snapshot.server.id == serverId &&
                current.snapshot.currentMember.id.value == sessionForServer.user.id
        ) {
            return
        }
        reduceState(
            _state.value.copy(
                content = SharedHomeContentState.Loading(sessionForServer)
            )
        )
        diagnosticsLogger(
            "home_cached_hydrate_result serverId=${serverId.value} " +
                "content=${_state.value.content is SharedHomeContentState.Content}"
        )
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

    private fun SharedHomeState.withChannelProfile(
        channelId: ChannelId,
        name: String,
        description: String?
    ): SharedHomeState {
        val current = content as? SharedHomeContentState.Content ?: return this
        val existing = current.baseSnapshot.channels.firstOrNull { it.channelId == channelId } ?: return this
        val updated = existing.copy(name = name, subtitle = description)
        return copy(
            content =
                contentFromDurableSnapshot(
                    snapshot =
                        current.baseSnapshot.copy(
                            channels = current.baseSnapshot.channels.map { row ->
                                if (row.channelId == channelId) updated else row
                            }
                        ),
                    session = current.session,
                    contentRefreshing = false,
                    previousContent = current
                )
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
        if (event is PerReceiverReadStateChanged) {
            applyPerReceiverReadStateChanged(serverId, event)
            return
        }
        if (event is PerReceiverNotificationPrefsChanged) {
            applyPerReceiverNotificationPrefsChanged(serverId, event)
            return
        }
        if (event is PerReceiverPinnedChanged) {
            applyPerReceiverPinnedChanged(serverId, event)
            return
        }
        synchronized(readProjectionOwnerLock) {
            applyRealtimeHomeEventLocally(serverId, event)
        }
    }

    private suspend fun applyBackgroundServerUnreadEvent(serverId: ServerId, event: RealtimeEvent): Boolean {
        val current = _state.value
        val content = current.content as? SharedHomeContentState.Content ?: return false
        if (content.snapshot.server.id == serverId) return false
        if (content.session.servers.none { it.id == serverId.value }) return false
        val memberId = MemberId(content.session.user.id)
        return when (event) {
            is MessageNew -> applyBackgroundUnreadMessages(serverId, memberId, listOf(event.message))
            is SyncResumeResponse -> applyBackgroundUnreadMessages(serverId, memberId, event.messages)
            is PerReceiverReadStateChanged -> {
                if (event.serverId != null && event.serverId != serverId) return false
                var accepted = false
                event.scopes.forEach { scope ->
                    val productionOwner =
                        readReceiptOwnerFactory
                            ?.invoke(serverId.value, memberId.value)
                            ?.toReadStateV1ProductionOwner()
                    if (productionOwner != null) {
                        val disposition =
                            readStateV1Canary?.applyOwnerRealtime(
                                owner = productionOwner,
                                channelId = scope.scopeId.value,
                                readStateVersion = scope.readStateVersion,
                                maxReadSeq = scope.maxReadSeq,
                            )
                        if (disposition != null && disposition !is ReadStateV1ProductionDisposition.Legacy) {
                            return@forEach
                        }
                    }
                    accepted =
                        applyBackgroundReadState(
                        serverId = serverId,
                        memberId = memberId,
                        scope = scope
                    ) || accepted
                }
                if (accepted) scheduleReadStateReconcile()
                accepted
            }
            else -> false
        }
    }

    private fun applyBackgroundUnreadMessages(
        serverId: ServerId,
        memberId: MemberId,
        messages: List<Message>
    ): Boolean {
        var increment = 0
        messages
            .distinctBy { it.id }
            .filter { it.author.id != memberId }
            .forEach { message ->
            val scopeId = message.channelId ?: ChannelId(message.threadId.value)
            val checkpoint = perReceiverStateVersionStore.readReadStateCheckpoint(serverId, memberId, scopeId)
            if (message.seq != null && message.seq <= checkpoint.maxReadSeq) return@forEach
            val key = BackgroundUnreadScopeKey(serverId, scopeId)
            val projection = backgroundUnreadByScope.getOrPut(key) { BackgroundUnreadScopeProjection() }
            val seq = message.seq
            if (seq == null) {
                projection.unknownSeqCount += 1
            } else {
                projection.countBySeq[seq] = (projection.countBySeq[seq] ?: 0) + 1
                projection.trimOldestBuckets()
            }
            increment += 1
        }
        if (increment == 0) return false
        val current = _state.value
        val previous = current.serverUnreadCounts[serverId.value] ?: 0
        return reduceState(
            current.copy(
                serverUnreadCounts =
                    current.serverUnreadCounts + (serverId.value to (previous + increment).coerceAtLeast(0))
            )
        )
    }

    private fun applyBackgroundReadState(
        serverId: ServerId,
        memberId: MemberId,
        scope: build.raft.app.model.PerReceiverReadStateScope
    ): Boolean {
        val incomingMaxReadSeq = scope.maxReadSeq ?: return false
        val factResult =
            perReceiverStateVersionStore.applyOrObserveReadStateFact(
                serverId = serverId,
                memberId = memberId,
                scopeId = scope.scopeId,
                incomingVersion = scope.readStateVersion,
                incomingMaxReadSeq = incomingMaxReadSeq
            )
        if (factResult != PerReceiverReadStateFactResult.Accepted) return false
        val key = BackgroundUnreadScopeKey(serverId, scope.scopeId)
        val projection = backgroundUnreadByScope[key] ?: return true
        val clearedUnread = projection.removeThrough(incomingMaxReadSeq)
        if (projection.isEmpty()) backgroundUnreadByScope.remove(key)
        if (clearedUnread == 0) return true
        val current = _state.value
        val previous = current.serverUnreadCounts[serverId.value] ?: 0
        return reduceState(
            current.copy(
                serverUnreadCounts =
                    current.serverUnreadCounts +
                        (serverId.value to (previous - clearedUnread).coerceAtLeast(0))
            )
        )
    }

    // RFC 041 wire events carry the scope facts but not the receiving member —
    // the server emits them to the caller's `user:<id>` room, so the member is
    // whoever this session is. Enrich with the current snapshot's identity and
    // hand each scope to the version-gated applier; a mismatched or stale
    // scope is a per-scope no-op there, never a batch failure.
    private suspend fun applyPerReceiverReadStateChanged(
        serverId: ServerId,
        event: PerReceiverReadStateChanged
    ) {
        val content = _state.value.content as? SharedHomeContentState.Content ?: return
        val memberId = content.snapshot.currentMember.id
        val eventServerId = event.serverId ?: serverId
        var needsReconcile = false
        event.scopes.forEach { scope ->
            val productionOwner =
                readReceiptOwnerFactory
                    ?.invoke(eventServerId.value, memberId.value)
                    ?.toReadStateV1ProductionOwner()
            if (productionOwner != null) {
                val disposition =
                    readStateV1Canary?.applyOwnerRealtime(
                        owner = productionOwner,
                        channelId = scope.scopeId.value,
                        readStateVersion = scope.readStateVersion,
                        maxReadSeq = scope.maxReadSeq,
                    )
                if (disposition != null && disposition !is ReadStateV1ProductionDisposition.Legacy) {
                    return@forEach
                }
            }
            val outcome =
                applyPerReceiverReadState(
                    PerReceiverReadStateUpdate(
                        serverId = eventServerId,
                        memberId = memberId,
                        channelId = scope.scopeId,
                        version = scope.readStateVersion,
                        maxReadSeq = scope.maxReadSeq,
                        unreadCount = scope.unreadCount
                    )
                )
            diagnosticsLogger(
                "per_receiver_read_state_apply serverId=${eventServerId.value} scopeId=${scope.scopeId.value} " +
                    "version=${scope.readStateVersion} maxReadSeq=${scope.maxReadSeq ?: -1} " +
                    "result=${outcome.diagnosticsName}"
            )
            needsReconcile = needsReconcile || outcome == HomeReadStateApplyOutcome.AcceptedNeedsReconcile
        }
        if (needsReconcile) scheduleReadStateReconcile()
    }

    private data class PreparedReadStateV1Hydrates(
        val snapshot: HomeSnapshot,
        val selectedAdjudication: Boolean = false,
        val publication: ReadStateV1PreparedPublication? = null,
        val projectionFact: ReadStateV1Fact? = null,
    )

    private suspend fun prepareReadStateV1Hydrates(snapshot: HomeSnapshot): PreparedReadStateV1Hydrates {
        val canary = readStateV1Canary ?: return PreparedReadStateV1Hydrates(snapshot)
        val owner =
            readReceiptOwnerFactory
                ?.invoke(snapshot.server.id.value, snapshot.currentMember.id.value)
                ?.toReadStateV1ProductionOwner()
                ?: return PreparedReadStateV1Hydrates(snapshot)
        val projectedFacts = mutableMapOf<ChannelId, ReadStateV1Fact?>()
        var selectedAdjudication = false
        var publication: ReadStateV1PreparedPublication? = null
        var projectionFact: ReadStateV1Fact? = null
        (snapshot.channels + snapshot.directMessages).forEach { row ->
            val hydrate = row.readStateV1Hydrate ?: return@forEach
            val channelId = row.channelId?.value ?: return@forEach
            when (val disposition = canary.prepareHydrate(owner, channelId, hydrate.readStateVersion, hydrate.maxReadSeq)) {
                is ReadStateV1ProductionDisposition.Selected -> {
                    selectedAdjudication = true
                    publication = disposition.preparedPublication
                    projectedFacts[ChannelId(channelId)] = disposition.projectedFact
                    if (disposition.requiresProjectionConfirmation && disposition.projectedFact != null) {
                        projectionFact = disposition.projectedFact
                    }
                }
                is ReadStateV1ProductionDisposition.SelectedFailure -> {
                    selectedAdjudication = true
                    publication = disposition.preparedPublication
                    projectedFacts[ChannelId(channelId)] = null
                }
                ReadStateV1ProductionDisposition.Legacy -> Unit
            }
        }
        if (projectedFacts.isEmpty() && !selectedAdjudication) return PreparedReadStateV1Hydrates(snapshot)
        return PreparedReadStateV1Hydrates(
            snapshot =
                snapshot.copy(
                    channels =
                        snapshot.channels.map { row ->
                            if (row.channelId !in projectedFacts) row
                            else row.copy(readStateMaxReadSeq = projectedFacts[row.channelId]?.maxReadSeq)
                        },
                    directMessages =
                        snapshot.directMessages.map { row ->
                            if (row.channelId !in projectedFacts) row
                            else row.copy(readStateMaxReadSeq = projectedFacts[row.channelId]?.maxReadSeq)
                        },
                ),
            selectedAdjudication = selectedAdjudication,
            publication = publication,
            projectionFact = projectionFact,
        )
    }

    internal fun applyReadStateV1Projection(owner: ReadStateV1ProductionOwner, fact: ReadStateV1Fact) {
        val content = _state.value.content as? SharedHomeContentState.Content ?: return
        val currentOwner =
            readReceiptOwnerFactory
                ?.invoke(content.snapshot.server.id.value, content.snapshot.currentMember.id.value)
                ?.toReadStateV1ProductionOwner()
                ?: return
        if (currentOwner != owner) return
        val changed = synchronized(readProjectionOwnerLock) {
            applyHomeSnapshotLocally { snapshot ->
                snapshot.withPerReceiverReadState(
                    PerReceiverReadStateUpdate(
                        serverId = snapshot.server.id,
                        memberId = snapshot.currentMember.id,
                        channelId = ChannelId(fact.channelId),
                        version = fact.readStateVersion,
                        maxReadSeq = fact.maxReadSeq,
                    )
                )
            }
        }
        if (changed) scheduleReadStateReconcile()
    }

    private fun scheduleReadStateReconcile() {
        diagnosticsLogger("per_receiver_read_state_reconcile reason=incomplete_home_projection")
        dispatch(SharedHomeIntent.ForegroundRefresh)
    }

    private fun applyPerReceiverNotificationPrefsChanged(
        serverId: ServerId,
        event: PerReceiverNotificationPrefsChanged
    ) {
        val content = _state.value.content as? SharedHomeContentState.Content ?: return
        val memberId = content.snapshot.currentMember.id
        val eventServerId = event.serverId ?: serverId
        val applied =
            applyPerReceiverNotificationPrefs(
                PerReceiverNotificationPrefsUpdate(
                    serverId = eventServerId,
                    memberId = memberId,
                    channelId = event.scopeId,
                    version = event.prefsVersion,
                    preference = event.preference
                )
            )
        diagnosticsLogger(
            "per_receiver_notification_prefs_apply serverId=${eventServerId.value} scopeId=${event.scopeId.value} " +
                "version=${event.prefsVersion} muted=${event.preference.activityMuted} " +
                "result=${if (applied) "applied" else "skipped"}"
        )
    }

    private fun applyPerReceiverPinnedChanged(
        serverId: ServerId,
        event: PerReceiverPinnedChanged
    ) {
        val content = _state.value.content as? SharedHomeContentState.Content ?: return
        val eventServerId = event.serverId ?: serverId
        val update =
            PerReceiverPinnedUpdate(
                serverId = eventServerId,
                memberId = content.snapshot.currentMember.id,
                version = event.pinnedVersion,
                sidebarOrder =
                    content.snapshot.sidebarOrder
                        ?.withCanonicalTypedPinned(event.pinned)
                        ?: SettingsServerSidebarOrderSnapshot().withCanonicalTypedPinned(event.pinned)
            )
        val applied = applyPerReceiverPinned(update)
        diagnosticsLogger(
            "per_receiver_pinned_apply serverId=${eventServerId.value} " +
                "version=${event.pinnedVersion} pinned=${event.pinned.size} " +
                "result=${if (applied) "applied" else "skipped"}"
        )
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
                if (coalesceIntoInFlightHomeLoad("visible_dirty_reconcile")) {
                    reduceState(
                        current.copy(
                            homeRealtimeDirty = false
                        )
                    )
                    return
                }
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
        if (event is ThreadUpdated) return applyThreadUpdatedLocally(event)
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
        beforeRealtimeHomeStateCommit()
        val changed =
            reduceState(
                current.copy(
                    content = nextContent,
                    serverUnreadCounts = nextBaseSnapshot.serverUnreadCounts,
                    appBadgeCount = nextBadge
                )
            )
        if (changed && messagePatch.incrementUnread && nextBaseSnapshot.unreadTotal != content.baseSnapshot.unreadTotal) {
            markRealtimeUnreadDirtyForInflightLoads()
        }
        diagnosticsLogger(
            "store_merge domain=home source=store_realtime serverId=${serverId.value} " +
                "event=${event.toSharedHomeRealtimeEventDiagnosticsLabel()} messages=${messagePatch.messages.size} " +
                "incrementUnread=${messagePatch.incrementUnread} changed=$changed " +
                "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
        )
        return changed
    }

    /** Web `socketBridge.ts` `threadUpdated` for the sidebar mark; caller holds [readProjectionOwnerLock]. */
    private fun applyThreadUpdatedLocally(event: ThreadUpdated): Boolean {
        val current = _state.value
        val content = current.content as? SharedHomeContentState.Content ?: return false
        return when (content.baseSnapshot.decideThreadReply(event, current.selectedConversationId)) {
            HomeThreadReplyDecision.Ignore -> false
            HomeThreadReplyDecision.Refresh -> {
                diagnosticsLogger("home_followed_thread_refresh_request threadId=${event.threadChannelId.value}")
                followedThreadUnreadRefresh.request()
                false
            }
            HomeThreadReplyDecision.Bump -> applyHomeSnapshotLocally { it.withFollowedThreadReply(event) }
        }
    }

    /** One `channels/unread?summary=1` read that replaces only the followed-thread mark. */
    private suspend fun refreshFollowedThreadUnread() {
        val content = _state.value.content as? SharedHomeContentState.Content ?: return
        val serverId = content.baseSnapshot.server.id
        val accountId = content.baseSnapshot.currentMember.id
        val result =
            try {
                dataSource.channelUnreadSummary(serverId, DataRequestPriority.Background)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                diagnosticsLogger(
                    "home_followed_thread_refresh result=exception errorType=${throwable.safeHomeStoreDiagnosticsType()}"
                )
                return
            }
        val summary = (result as? DataResult.Content)?.data
        if (summary == null) {
            diagnosticsLogger("home_followed_thread_refresh result=unavailable")
            return
        }
        val changed =
            synchronized(readProjectionOwnerLock) {
                val now = _state.value.content as? SharedHomeContentState.Content ?: return@synchronized false
                if (now.baseSnapshot.server.id != serverId || now.baseSnapshot.currentMember.id != accountId) {
                    return@synchronized false
                }
                applyHomeSnapshotLocally { it.withFollowedThreadUnread(summary.followedThreadUnread) }
            }
        diagnosticsLogger(
            "home_followed_thread_refresh result=content threads=${summary.followedThreadUnread.size} changed=$changed"
        )
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
        loadJobLaunchIntent = null
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

    private fun beginRealtimeUnreadWatch(): Long =
        synchronized(realtimeUnreadDirtyLock) {
            val token = ++nextHomeLoadToken
            realtimeUnreadDirtyByLoad[token] = false
            token
        }

    private fun markRealtimeUnreadDirtyForInflightLoads() {
        synchronized(realtimeUnreadDirtyLock) {
            realtimeUnreadDirtyByLoad.keys.toList().forEach { token -> realtimeUnreadDirtyByLoad[token] = true }
        }
    }

    private fun isRealtimeUnreadDirty(token: Long): Boolean =
        synchronized(realtimeUnreadDirtyLock) { realtimeUnreadDirtyByLoad[token] == true }

    private fun endRealtimeUnreadWatch(token: Long): Boolean =
        synchronized(realtimeUnreadDirtyLock) { realtimeUnreadDirtyByLoad.remove(token) == true }

    /** Drop dirty marks of loads a session reset or server switch has orphaned. */
    private fun clearRealtimeUnreadWatches() {
        synchronized(realtimeUnreadDirtyLock) { realtimeUnreadDirtyByLoad.clear() }
    }

    private fun scheduleRealtimeUnreadReconcileLoad(
        round: Int,
        sessionGeneration: Long,
        accountId: String,
        serverId: String?,
    ) {
        if (round > MAX_REALTIME_UNREAD_RECONCILE_ROUNDS) {
            // The local counts stay as they are; the next ordinary load or foreground refresh reconciles.
            diagnosticsLogger("home_unread_reconcile_cap rounds=$MAX_REALTIME_UNREAD_RECONCILE_ROUNDS")
            return
        }
        val finishing = loadJob
        scope.launch {
            finishing?.join()
            // Re-check after the wait: a reset, account change or server switch meanwhile owns Home now,
            // and the message that marked the load belonged to the old session/server.
            val session = authStore.readSession()
            val content = _state.value.content as? SharedHomeContentState.Content
            val skipReason =
                when {
                    sessionGeneration != homeSessionGeneration.value -> "session_generation_changed"
                    session == null || session.user.id != accountId -> "account_changed"
                    serverId == null || session.currentServerId != serverId -> "server_changed"
                    content == null || content.snapshot.server.id.value != serverId -> "content_changed"
                    // A load already in flight was sent after the message too; it will replace the counts.
                    loadJob?.isActive == true -> "load_in_flight"
                    else -> null
                }
            if (skipReason != null) {
                diagnosticsLogger("home_unread_reconcile_skip round=$round reason=$skipReason")
                return@launch
            }
            diagnosticsLogger("home_unread_reconcile_load round=$round")
            val nextSwitch = _state.value.serverSwitchState.refresh()
            reduceState(_state.value.copy(serverSwitchState = nextSwitch))
            loadJobLaunchIntent = null
            loadJob =
                scope.launch {
                    val previousReason = stateDiagnosticsReason
                    stateDiagnosticsReason = "RealtimeUnreadReconcile"
                    try {
                        load(nextSwitch, showRefreshAffordance = false, realtimeUnreadRound = round)
                    } finally {
                        stateDiagnosticsReason = previousReason
                    }
                }
        }
    }

    private fun HomeServerDrawerState.withServerContent(
        content: SharedHomeContentState.Content,
        status: HomeServerDrawerLoadStatus
    ): HomeServerDrawerState {
        val serverId = content.snapshot.server.id
        val section = sections[serverId] ?: HomeServerDrawerSectionState(server = content.snapshot.server)
        return copy(
            sections =
                sections +
                    (serverId to
                        section.copy(
                            content = content,
                            normalizedContent =
                                homeServerDrawerNormalizedContentForSnapshot(
                                    previousSnapshot =
                                        section.content?.snapshot
                                            ?.takeIf { section.normalizedContent !== HomeServerDrawerNormalizedContent.Empty },
                                    nextSnapshot = content.snapshot,
                                    previousContent = section.normalizedContent
                                ),
                            status = status,
                            errorCode = null
                        ))
        )
    }

    private fun openedServerDrawerState(session: StoredAuthSession?): HomeServerDrawerState {
        val previous = _state.value.serverDrawer
        if (session == null) {
            pruneServerDrawerRuntimeState(emptySet())
            return previous.copy(expanded = true, sections = emptyMap(), expandedServerIds = emptySet())
        }
        val currentContent = _state.value.content as? SharedHomeContentState.Content
        val serverSummaries = session.servers.map { it.toServerSummary() }
        val availableServerIds = serverSummaries.mapTo(linkedSetOf()) { it.id }
        pruneServerDrawerRuntimeState(availableServerIds)
        val selectedServerId =
            currentContent?.snapshot?.server?.id
                ?: session.currentServerId?.let(::ServerId)
        val expandedServerIds =
            selectedServerId
                ?.takeIf { it in availableServerIds }
                ?.let(::setOf)
                ?: emptySet()
        val sections =
            serverSummaries.associate { server ->
                val cached =
                    currentContent?.takeIf { it.snapshot.server.id == server.id }
                        ?: contentByServer[server.id]
                val status =
                    when {
                        cached == null -> HomeServerDrawerLoadStatus.Unloaded
                        cached.snapshot.server.id == currentContent?.snapshot?.server?.id && cached.contentRefreshing ->
                            HomeServerDrawerLoadStatus.Refreshing
                        cached.snapshot.server.id == currentContent?.snapshot?.server?.id ->
                            HomeServerDrawerLoadStatus.Loaded
                        else -> HomeServerDrawerLoadStatus.Cached
                    }
                server.id to
                    HomeServerDrawerSectionState(
                        server = server,
                        content = cached,
                        normalizedContent =
                            previous.sections[server.id]?.normalizedContent
                                ?: cached?.takeIf { server.id == selectedServerId }
                                    ?.snapshot?.toHomeServerDrawerNormalizedContent()
                                ?: HomeServerDrawerNormalizedContent.Empty,
                        status = status,
                        requestGeneration = previous.sections[server.id]?.requestGeneration ?: 0L
                    )
            }
        return previous.copy(
            expanded = true,
            expandedServerIds = expandedServerIds,
            sections = sections
        )
    }

    private fun pruneServerDrawerRuntimeState(availableServerIds: Set<ServerId>) {
        serverDrawerLoadJobs.keys
            .filterNot { it in availableServerIds }
            .forEach { serverId -> serverDrawerLoadJobs.remove(serverId)?.cancel() }
        serverDrawerRequestGenerations.keys.removeAll { it !in availableServerIds }
        contentByServer.keys.removeAll { it !in availableServerIds }
        sidebarOrderByServer.keys.removeAll { it !in availableServerIds }
    }

    private fun HomeServerDrawerState.withSessionServers(session: StoredAuthSession): HomeServerDrawerState {
        val serverSummaries = session.servers.map { it.toServerSummary() }
        val availableServerIds = serverSummaries.mapTo(linkedSetOf()) { it.id }
        pruneServerDrawerRuntimeState(availableServerIds)
        return copy(
            expandedServerIds =
                (
                    expandedServerIds.firstOrNull { it in availableServerIds }
                        ?: session.currentServerId?.let(::ServerId)?.takeIf { it in availableServerIds }
                )
                    ?.let(::setOf)
                    ?: emptySet(),
            sections =
                serverSummaries.associate { server ->
                    val existing = sections[server.id]
                    val cached = existing?.content ?: contentByServer[server.id]
                    server.id to
                        if (existing != null) {
                            existing.copy(
                                server = server,
                                content = cached,
                                normalizedContent =
                                    cached?.snapshot?.let { snapshot ->
                                        homeServerDrawerNormalizedContentForSnapshot(
                                            previousSnapshot =
                                                existing.content?.snapshot
                                                    ?.takeIf {
                                                        existing.normalizedContent !== HomeServerDrawerNormalizedContent.Empty
                                                    },
                                            nextSnapshot = snapshot,
                                            previousContent = existing.normalizedContent
                                        )
                                    } ?: HomeServerDrawerNormalizedContent.Empty
                            )
                        } else {
                            HomeServerDrawerSectionState(
                                server = server,
                                content = cached,
                                normalizedContent =
                                    cached?.snapshot?.toHomeServerDrawerNormalizedContent()
                                        ?: HomeServerDrawerNormalizedContent.Empty,
                                status =
                                    if (cached == null) {
                                        HomeServerDrawerLoadStatus.Unloaded
                                    } else {
                                        HomeServerDrawerLoadStatus.Cached
                                    }
                            )
                        }
                }
        )
    }

    private fun toggleServerDrawerSection(serverId: ServerId) {
        val drawer = _state.value.serverDrawer
        if (!drawer.expanded || serverId !in drawer.sections) return
        if (serverId in drawer.expandedServerIds) {
            reduceState(
                _state.value.copy(
                    serverDrawer = drawer.copy(expandedServerIds = drawer.expandedServerIds - serverId)
                )
            )
            return
        }
        reduceState(
            _state.value.copy(
                serverDrawer = drawer.copy(expandedServerIds = setOf(serverId))
            )
        )
        val section = _state.value.serverDrawer.sections[serverId] ?: return
        val currentServerId =
            (_state.value.content as? SharedHomeContentState.Content)?.snapshot?.server?.id
        if (section.content == null || serverId != currentServerId) {
            launchServerDrawerSectionLoad(serverId, force = false)
        }
    }

    /**
     * A foreground server selection owns fresher publication authority than any drawer preload
     * that started for the same server. Rotate the drawer generation before foreground loading so
     * even a non-cooperative older request can finish without overwriting [contentByServer] or the
     * conversation projection. The detached request is intentionally allowed to complete; every
     * terminal drawer publication path is fenced by [isCurrentServerDrawerRequest].
     */
    private fun supersedeServerDrawerRequest(serverId: ServerId) {
        val sectionGeneration = _state.value.serverDrawer.sections[serverId]?.requestGeneration
        val currentGeneration = serverDrawerRequestGenerations[serverId] ?: sectionGeneration ?: return
        serverDrawerRequestGenerations[serverId] = currentGeneration + 1L
        serverDrawerLoadJobs.remove(serverId)
    }

    private fun launchServerDrawerSectionLoad(serverId: ServerId, force: Boolean) {
        val drawer = _state.value.serverDrawer
        val section = drawer.sections[serverId] ?: return
        if (!drawer.expanded || serverId !in drawer.expandedServerIds) return
        if (section.status == HomeServerDrawerLoadStatus.Unavailable) return
        if (!force && serverDrawerLoadJobs[serverId]?.isActive == true) return

        val generation = (serverDrawerRequestGenerations[serverId] ?: section.requestGeneration) + 1L
        serverDrawerRequestGenerations[serverId] = generation
        serverDrawerLoadJobs.remove(serverId)?.cancel()
        val cached = section.content ?: contentByServer[serverId]
        val loadingSection =
            section.copy(
                content = cached,
                normalizedContent =
                    if (cached == null) {
                        HomeServerDrawerNormalizedContent.Empty
                    } else {
                        homeServerDrawerNormalizedContentForSnapshot(
                            previousSnapshot =
                                section.content?.snapshot
                                    ?.takeIf { section.normalizedContent !== HomeServerDrawerNormalizedContent.Empty },
                            nextSnapshot = cached.snapshot,
                            previousContent = section.normalizedContent
                        )
                    },
                status =
                    if (cached == null) {
                        HomeServerDrawerLoadStatus.Loading
                    } else {
                        HomeServerDrawerLoadStatus.Refreshing
                    },
                requestGeneration = generation,
                errorCode = null
            )
        reduceState(
            _state.value.copy(
                serverDrawer =
                    drawer.copy(
                        sections = drawer.sections + (serverId to loadingSection)
                    )
            )
        )
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                loadServerDrawerSection(serverId, generation)
            }
        serverDrawerLoadJobs[serverId] = job
        job.start()
    }

    private suspend fun loadServerDrawerSection(serverId: ServerId, generation: Long) {
        val session = authStore.readSession()
        if (session == null || session.servers.none { it.id == serverId.value }) {
            completeServerDrawerSectionError(serverId, generation, "server_unavailable")
            return
        }
        val homeResult =
            try {
                dataSource.loadHome(
                    serverId = serverId,
                    priority = DataRequestPriority.Background,
                    forceNetwork = false
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                diagnosticsLogger(
                    "home_server_drawer_load_failed serverId=${serverId.value} " +
                        "generation=$generation errorType=${throwable.safeHomeStoreDiagnosticsType()}"
                )
                completeServerDrawerSectionError(serverId, generation, "home_network")
                return
            }
        when (homeResult) {
            is DataResult.Content -> {
                if (homeResult.data.server.id != serverId) {
                    completeServerDrawerSectionError(serverId, generation, "server_mismatch")
                    return
                }
                val sidebarOrder = loadServerDrawerSidebarOrder(serverId)
                if (!isCurrentServerDrawerRequest(serverId, generation)) return
                val content =
                    createHomeContent(
                        snapshot =
                            if (sidebarOrder != null && homeResult.data.sidebarOrder != sidebarOrder) {
                                homeResult.data.copy(sidebarOrder = sidebarOrder)
                            } else {
                                homeResult.data
                            },
                        session = session,
                        contentRefreshing = false,
                        previousContent = contentByServer[serverId]
                    )
                publishServerDrawerContent(serverId, generation, content)
            }
            is DataResult.Empty -> completeServerDrawerSectionEmpty(serverId, generation)
            is DataResult.Error -> completeServerDrawerSectionError(serverId, generation, homeResult.error.code)
            is DataResult.NoPermission ->
                completeServerDrawerSectionUnavailable(serverId, generation, homeResult.blocker.code)
        }
    }

    private suspend fun loadServerDrawerSidebarOrder(
        serverId: ServerId
    ): SettingsServerSidebarOrderSnapshot? =
        try {
            when (val result = loadSidebarOrder(serverId)) {
                is DataResult.Content -> result.data
                is DataResult.Empty,
                is DataResult.Error,
                is DataResult.NoPermission -> sidebarOrderByServer[serverId]
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            diagnosticsLogger(
                "home_server_drawer_sidebar_order_failed serverId=${serverId.value} " +
                    "errorType=${throwable.safeHomeStoreDiagnosticsType()}"
            )
            sidebarOrderByServer[serverId]
        }

    private fun isCurrentServerDrawerRequest(serverId: ServerId, generation: Long): Boolean =
        serverDrawerRequestGenerations[serverId] == generation &&
            _state.value.serverDrawer.sections[serverId]?.requestGeneration == generation

    private suspend fun publishServerDrawerContent(
        serverId: ServerId,
        generation: Long,
        content: SharedHomeContentState.Content
    ) {
        if (!isCurrentServerDrawerRequest(serverId, generation)) return
        val latestSession = authStore.readSession()
        if (latestSession == null || latestSession.servers.none { it.id == serverId.value }) {
            removeUnavailableServerDrawerSection(serverId, generation)
            return
        }
        content.snapshot.sidebarOrder?.let { sidebarOrderByServer[serverId] = it }
        contentByServer[serverId] = content
        updateConversationIdentity(content.baseSnapshot)
        val drawer = _state.value.serverDrawer
        val section = drawer.sections[serverId] ?: return
        reduceState(
            _state.value.copy(
                serverDrawer =
                    drawer.copy(
                        sections =
                            drawer.sections +
                                (serverId to
                                    section.copy(
                                        content = content,
                                        normalizedContent = content.snapshot.toHomeServerDrawerNormalizedContent(),
                                        status = HomeServerDrawerLoadStatus.Loaded,
                                        errorCode = null
                                    ))
                    )
            )
        )
        serverDrawerLoadJobs.remove(serverId)
    }

    private fun removeUnavailableServerDrawerSection(serverId: ServerId, generation: Long) {
        if (!isCurrentServerDrawerRequest(serverId, generation)) return
        serverDrawerLoadJobs.remove(serverId)
        serverDrawerRequestGenerations.remove(serverId)
        contentByServer.remove(serverId)
        sidebarOrderByServer.remove(serverId)
        val drawer = _state.value.serverDrawer
        val remainingSections = drawer.sections - serverId
        val fallbackExpandedServerId =
            (_state.value.content as? SharedHomeContentState.Content)
                ?.snapshot
                ?.server
                ?.id
                ?.takeIf { it in remainingSections }
        reduceState(
            _state.value.copy(
                serverDrawer =
                    drawer.copy(
                        expandedServerIds =
                            if (serverId in drawer.expandedServerIds) {
                                fallbackExpandedServerId?.let(::setOf) ?: emptySet()
                            } else {
                                drawer.expandedServerIds - serverId
                            },
                        sections = remainingSections
                    )
            )
        )
    }

    private fun completeServerDrawerSectionEmpty(serverId: ServerId, generation: Long) {
        if (!isCurrentServerDrawerRequest(serverId, generation)) return
        val drawer = _state.value.serverDrawer
        val section = drawer.sections[serverId] ?: return
        reduceState(
            _state.value.copy(
                serverDrawer =
                    drawer.copy(
                        sections =
                            drawer.sections +
                                (serverId to
                                    section.copy(
                                        status =
                                            if (section.content == null) {
                                                HomeServerDrawerLoadStatus.Empty
                                            } else {
                                                HomeServerDrawerLoadStatus.Cached
                                            },
                                        errorCode = null
                                    ))
                    )
            )
        )
        serverDrawerLoadJobs.remove(serverId)
    }

    private fun completeServerDrawerSectionError(serverId: ServerId, generation: Long, code: String) {
        if (!isCurrentServerDrawerRequest(serverId, generation)) return
        val drawer = _state.value.serverDrawer
        val section = drawer.sections[serverId] ?: return
        reduceState(
            _state.value.copy(
                serverDrawer =
                    drawer.copy(
                        sections =
                            drawer.sections +
                                (serverId to
                                    section.copy(
                                        status =
                                            if (section.content == null) {
                                                HomeServerDrawerLoadStatus.ErrorEmpty
                                            } else {
                                                HomeServerDrawerLoadStatus.ErrorWithCache
                                            },
                                        errorCode = code
                                    ))
                    )
            )
        )
        serverDrawerLoadJobs.remove(serverId)
    }

    private fun completeServerDrawerSectionUnavailable(serverId: ServerId, generation: Long, code: String) {
        if (!isCurrentServerDrawerRequest(serverId, generation)) return
        val drawer = _state.value.serverDrawer
        val section = drawer.sections[serverId] ?: return
        reduceState(
            _state.value.copy(
                serverDrawer =
                    drawer.copy(
                        sections =
                            drawer.sections +
                                (serverId to
                                    section.copy(
                                        status = HomeServerDrawerLoadStatus.Unavailable,
                                        errorCode = code
                                    ))
                    )
            )
        )
        serverDrawerLoadJobs.remove(serverId)
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
        showRefreshAffordance: Boolean,
        realtimeUnreadRound: Int = 0,
    ) {
        val sessionGeneration = homeSessionGeneration.value
        diagnosticsLogger("home_ui_load_start refreshAffordance=$showRefreshAffordance")
        val previous = _state.value.content
        reduceState(
            _state.value.copy(
                content = previous.asRefreshing(showRefreshAffordance = showRefreshAffordance)
            )
        )
        try {
            val session = authStore.readSession()
            if (sessionGeneration != homeSessionGeneration.value) return
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
            if (sessionGeneration != homeSessionGeneration.value) return
            val activeServerId = requestedServerId ?: activeSession.currentServerId
            reconcileNotificationAuthority(activeSession, activeServerId)
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
            markCachedHomeNetworkRefreshStarted()
            if (sessionGeneration != homeSessionGeneration.value) return
            val activeServer = activeServerId?.let(::ServerId)
            loadHomeFromNetwork(
                request = request,
                activeSession = activeSession,
                activeServer = activeServer,
                priority = DataRequestPriority.Visible,
                forceNetwork = false,
                refreshNotificationsAfterLoad = true,
                sessionGeneration = sessionGeneration,
                realtimeUnreadRound = realtimeUnreadRound,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            if (sessionGeneration != homeSessionGeneration.value) return
            diagnosticsLogger(
                "home_ui_exception source=shared_home_load errorType=${throwable.safeHomeStoreDiagnosticsType()} mapped=${_state.value.content.refreshFailureMapping()} code=home_network"
            )
            reduceState(_state.value.copy(content = _state.value.content.asRefreshFailed("home_network")))
        }
    }

    private fun markCachedHomeNetworkRefreshStarted() {
        val current = _state.value.content as? SharedHomeContentState.Content ?: return
        if (!current.needsNetworkRefresh) return
        reduceState(
            _state.value.copy(
                content = current.copy(needsNetworkRefresh = false)
            )
        )
    }

    private suspend fun loadHomeFromNetwork(
        request: HomeServerSwitchState,
        activeSession: StoredAuthSession,
        activeServer: ServerId?,
        priority: DataRequestPriority,
        forceNetwork: Boolean,
        refreshNotificationsAfterLoad: Boolean,
        sessionGeneration: Long,
        realtimeUnreadRound: Int = 0,
    ) {
        val realtimeUnreadToken = beginRealtimeUnreadWatch()
        try {
            loadHomeFromNetworkWatched(
                request = request,
                activeSession = activeSession,
                activeServer = activeServer,
                priority = priority,
                forceNetwork = forceNetwork,
                refreshNotificationsAfterLoad = refreshNotificationsAfterLoad,
                sessionGeneration = sessionGeneration,
                realtimeUnreadToken = realtimeUnreadToken,
            )
        } finally {
            // A message counted at any point while this load was in flight, including after its response
            // was frozen against the live counts, needs a load sent after it.
            if (endRealtimeUnreadWatch(realtimeUnreadToken) && sessionGeneration == homeSessionGeneration.value) {
                scheduleRealtimeUnreadReconcileLoad(
                    round = realtimeUnreadRound + 1,
                    sessionGeneration = sessionGeneration,
                    accountId = activeSession.user.id,
                    serverId = activeServer?.value ?: activeSession.currentServerId,
                )
            }
        }
    }

    private suspend fun loadHomeFromNetworkWatched(
        request: HomeServerSwitchState,
        activeSession: StoredAuthSession,
        activeServer: ServerId?,
        priority: DataRequestPriority,
        forceNetwork: Boolean,
        refreshNotificationsAfterLoad: Boolean,
        sessionGeneration: Long,
        realtimeUnreadToken: Long,
    ) {
        val activityUnreadRequestOwner =
            activeServer?.let { server ->
                readReceiptOwnerFactory?.invoke(server.value, activeSession.user.id)
            }
        // task #322: fire the sidebar-order fetch concurrently with the home request so
        // the pinned section can land in the same frame as the channel list instead of
        // being appended after it. `applySidebarOrderLocally` merges it on arrival; the
        // bounded await below only waits a short cap before publishing channels alone.
        val sidebarFetchPinChangeVersion = sidebarPinChangeVersion
        val sidebarOrderDeferred =
            activeServer?.let { serverId ->
                scope.async {
                    runCatching { loadSidebarOrder(serverId) }.getOrNull()
                }
            }
        val dataResult = dataSource.loadHome(serverId = activeServer, priority = priority, forceNetwork = forceNetwork)
        if (sessionGeneration != homeSessionGeneration.value) {
            diagnosticsLogger("home_network_result_drop reason=session_generation_changed")
            return
        }
        val latestSession = authStore.readSession()
        val expectedServerId = activeServer?.value ?: activeSession.currentServerId
        if (
            latestSession == null ||
                latestSession.user.id != activeSession.user.id ||
                latestSession.currentServerId != expectedServerId
        ) {
            diagnosticsLogger(
                "home_network_result_drop reason=auth_authority_changed " +
                    "expectedAccount=${activeSession.user.id} actualAccount=${latestSession?.user?.id ?: "_"} " +
                    "expectedServer=${expectedServerId ?: "_"} actualServer=${latestSession?.currentServerId ?: "_"}"
            )
            return
        }
        val activeServerId = expectedServerId?.let(::ServerId)
        val candidatePrevious = _state.value.content as? SharedHomeContentState.Content
        val previousContent =
            candidatePrevious?.takeIf {
                it.snapshot.server.id == activeServerId &&
                    it.session.user.id == activeSession.user.id &&
                    it.snapshot.currentMember.id.value == activeSession.user.id
            } ?: activeServerId?.let { contentByServer[it] }?.takeIf {
                it.session.user.id == activeSession.user.id &&
                    it.snapshot.currentMember.id.value == activeSession.user.id
            }
        val preparedHydrates =
            (dataResult as? DataResult.Content)?.data?.let { snapshot ->
                prepareReadStateV1Hydrates(snapshot)
            }
        val rawSnapshot = preparedHydrates?.snapshot
        // task #831: when the unread enrichment was unavailable, atomically
        // freeze ALL unread-bearing fields (per-row channel/DM unread, aggregate
        // total/source, server counts) to the prior committed baseline. Fresh
        // channel/DM identity + content still apply, but the committed snapshot
        // (and therefore the realtime unread delta baseline) is never reset to a
        // fake zero — otherwise the next realtime message would rebuild from 0
        // and clobber the real count. Only a truly available response
        // (including a real 200-empty) advances unread.
        // A realtime message raised a local unread count after this request was sent: the response may
        // predate it, so keep every unread-bearing field at the live counts and let the reconcile load
        // scheduled when this load ends replace them.
        val realtimeUnreadDirty = isRealtimeUnreadDirty(realtimeUnreadToken)
        val freshSnapshot =
            if (rawSnapshot != null && !rawSnapshot.unreadEnrichmentAvailable && previousContent != null) {
                rawSnapshot.frozenUnreadFrom(previousContent.baseSnapshot)
            } else if (rawSnapshot != null && realtimeUnreadDirty && previousContent != null) {
                diagnosticsLogger("home_unread_keep_local reason=realtime_during_load")
                rawSnapshot.frozenUnreadFrom(previousContent.baseSnapshot)
            } else {
                rawSnapshot
            }
        // task #322: the sidebar-order fetch runs concurrently with the home request.
        // Wait a short cap for it before building the published content so the pinned
        // section lands in the same frame as the channel list; on timeout the home
        // content publishes channels alone and the deferred result merges under
        // `sidebarOrderMutex` when it resolves (same result, no second request).
        val earlySidebarOrder =
            runCatching {
                sidebarOrderDeferred?.let { deferred ->
                    withTimeoutOrNull(SIDEBAR_ORDER_FIRST_PAINT_WAIT_MS) { deferred.await() }
                }
            }.getOrNull()
        val freshSnapshotWithSidebar =
            if (
                earlySidebarOrder is DataResult.Content &&
                    freshSnapshot != null &&
                    activeServer == freshSnapshot.server.id
            ) {
                sidebarOrderByServer[freshSnapshot.server.id] = earlySidebarOrder.data
                freshSnapshot.copy(sidebarOrder = earlySidebarOrder.data)
            } else {
                freshSnapshot
            }
        // On timeout or a non-Content early result, still reuse the in-flight deferred so
        // the late-arriving order merges under sidebarOrderMutex — the same request's
        // result, no second fetch, and Pin/Unpin dispatched meanwhile stays serialized.
        if (
            sidebarOrderDeferred != null &&
                freshSnapshotWithSidebar === freshSnapshot
        ) {
            sidebarOrderDeferred?.let { deferred ->
                scope.launch {
                    val lateResult = deferred.await()
                    if (lateResult is DataResult.Content && activeServer != null) {
                        sidebarOrderMutex.withLock {
                            val current =
                                _state.value.content as? SharedHomeContentState.Content ?: return@withLock
                            // A pin dispatched between this fetch's launch and its late
                            // apply already wrote the authoritative snapshot — drop the
                            // stale deferred result instead of clobbering the pin.
                            if (
                                current.snapshot.server.id == activeServer &&
                                    sidebarPinChangeVersion == sidebarFetchPinChangeVersion
                            ) {
                                applySidebarOrderLocally(
                                    serverId = current.snapshot.server.id,
                                    next = lateResult.data
                                )
                            }
                        }
                    }
                }
            }
        }
        val result =
            when (dataResult) {
                is DataResult.Content ->
                    createHomeContent(
                        snapshot = requireNotNull(freshSnapshotWithSidebar),
                        session = activeSession,
                        contentRefreshing = false,
                        previousContent = previousContent
                    )
                is DataResult.Empty -> SharedHomeContentState.Empty(activeSession)
                is DataResult.Error -> {
                    diagnosticsLogger(
                        "home_ui_error source=shared_home_load code=${dataResult.error.code} mapped=${_state.value.content.refreshFailureMapping()}"
                    )
                    _state.value.content.asRefreshFailed(
                        dataResult.error.code,
                        serverMessage = dataResult.error.serverMessage
                    )
                }
                is DataResult.NoPermission -> {
                    diagnosticsLogger(
                        "home_ui_error source=shared_home_load code=${dataResult.blocker.code} mapped=${_state.value.content.refreshFailureMapping()}"
                    )
                    _state.value.content.asRefreshFailed(
                        dataResult.blocker.code,
                        serverMessage = dataResult.blocker.serverMessage
                    )
                }
            }
        val resultServerId = (result as? SharedHomeContentState.Content)?.snapshot?.server?.id
            ?: activeServer
        val latestSwitch = _state.value.serverSwitchState
        if (sessionGeneration != homeSessionGeneration.value) {
            diagnosticsLogger("home_network_result_drop reason=session_generation_changed")
            return
        }
        if (resultServerId == null || latestSwitch.isCurrent(request.requestGeneration, resultServerId)) {
            val liveActivityUnreadOwner =
                activeServer?.let { server ->
                    authStore.readSession()?.let { liveSession ->
                        readReceiptOwnerFactory?.invoke(server.value, liveSession.user.id)
                    }
                }
            val nextActivityUnread =
                if (
                    activityUnreadRequestOwner != null &&
                    activityUnreadRequestOwner == liveActivityUnreadOwner
                ) {
                    HomeServerActivityUnreadProjection(
                        requestOwner = activityUnreadRequestOwner,
                        countsByServer = freshSnapshot?.serverActivityUnreadCounts.orEmpty(),
                        serverPushMutedByServer = freshSnapshot?.serverPushMutedByServer.orEmpty(),
                    )
                } else {
                    _state.value.serverActivityUnread
                }
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
            // task #831: only a snapshot whose unread enrichment was actually
            // available may drive the badge to a fresh (possibly zero) value.
            // When channels/DMs are fresh but unread was unavailable
            // (error/timeout/hung/contract-mismatch), keep the prior badge and
            // only flip isStale — never zero it, or a slow/hung unread endpoint
            // would fake "all read".
            val unreadEnrichmentAvailable = freshSnapshot?.unreadEnrichmentAvailable == true
            val nextBadge =
                if (freshSnapshot != null && unreadEnrichmentAvailable) {
                    val next =
                        snapshotToBadgeCount(
                            snapshot = freshSnapshot,
                            selectedServerId = activeServer,
                            isStale = false,
                            nowMillis = nowMillis,
                        )
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
            val nextState =
                _state.value.copy(
                    content = nextContent,
                    serverDrawer =
                        if (nextContent is SharedHomeContentState.Content) {
                            _state.value.serverDrawer.withServerContent(
                                nextContent,
                                HomeServerDrawerLoadStatus.Loaded
                            )
                        } else {
                            _state.value.serverDrawer
                        },
                    serverSwitchState =
                        if (resultServerId != null) {
                            latestSwitch.complete(request.requestGeneration, resultServerId)
                        } else {
                            latestSwitch
                        },
                    // task #831: keep the prior drawer unread when unread was
                    // unavailable, so a slow/hung unread endpoint can't blank the
                    // multi-server switcher badges. Only a fresh, available
                    // projection replaces them.
                    serverUnreadCounts =
                        if (freshSnapshot != null && unreadEnrichmentAvailable) {
                            freshSnapshot.serverUnreadCounts
                        } else {
                            _state.value.serverUnreadCounts
                        },
                    serverActivityUnread = nextActivityUnread,
                    appBadgeCount = nextBadge
                )
            val published =
                if (preparedHydrates?.selectedAdjudication == true) {
                    val publication = preparedHydrates.publication
                    publication != null && readStateV1Canary?.completePreparedPublication(
                        publication = publication,
                        projectionFact = preparedHydrates.projectionFact,
                        publishContent = { publishLoadedHomeState(nextState, freshSnapshot) },
                    ) == true
                } else {
                    publishLoadedHomeState(nextState, freshSnapshot)
                    true
                }
            if (!published) {
                diagnosticsLogger("read_state_v1_home_publish rejected=stale_witness")
                return
            }
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
        val content =
            createHomeContent(
                snapshot = snapshot,
                session = session,
                contentRefreshing = contentRefreshing,
                previousContent = previousContent
            )
        commitDurableContent(content)
        return content
    }

    private fun publishLoadedHomeState(nextState: SharedHomeState, freshSnapshot: HomeSnapshot?) {
        if (freshSnapshot != null) {
            // The server unread-summary is the canonical aggregate after
            // cold start/reconnect/refresh. Drop only the local count-only
            // deltas after the exact-witness destination has admitted the
            // final publication.
            //
            // task #831: but only when the unread enrichment was actually
            // available. If it was unavailable the snapshot froze unread to the
            // prior baseline, so the accumulated background deltas must be kept
            // — clearing them here would drop the very count we just preserved
            // and let the next realtime message rebuild from zero.
            if (freshSnapshot.unreadEnrichmentAvailable) {
                backgroundUnreadByScope.clear()
            }
            (nextState.content as? SharedHomeContentState.Content)?.let(::commitDurableContent)
        }
        reduceState(nextState)
    }

    private fun commitDurableContent(content: SharedHomeContentState.Content) {
        content.snapshot.sidebarOrder?.let { sidebarOrderByServer[content.snapshot.server.id] = it }
        contentByServer[content.baseSnapshot.server.id] = content
        updateConversationIdentity(content.baseSnapshot)
    }

    /**
     * task #831: returns this fresh snapshot with every unread-bearing field
     * (per-row channel/DM unread + mention counts, aggregate total/source, and
     * cross-server counts) replaced by [prior]'s values, matched by channel id.
     * Fresh channel/DM identity and content are kept. Used when the unread
     * enrichment was unavailable, so the committed snapshot — and the realtime
     * unread delta baseline built from it — freezes at the prior count instead
     * of a fake zero.
     */
    private fun HomeSnapshot.frozenUnreadFrom(prior: HomeSnapshot): HomeSnapshot {
        if (prior.server.id != server.id || prior.currentMember.id != currentMember.id) return this
        val priorChannels = prior.channels.associateBy { it.channelId }
        val priorDirectMessages = prior.directMessages.associateBy { it.channelId }
        return copy(
            channels =
                channels.map { row ->
                    val priorRow = priorChannels[row.channelId] ?: return@map row
                    row.copy(
                        unreadCount = priorRow.unreadCount,
                        unreadCountKnown = priorRow.unreadCountKnown,
                        mentionCount = priorRow.mentionCount,
                        mentionCountKnown = priorRow.mentionCountKnown,
                    )
                },
            directMessages =
                directMessages.map { row ->
                    val priorRow = priorDirectMessages[row.channelId] ?: return@map row
                    row.copy(
                        unreadCount = priorRow.unreadCount,
                        unreadCountKnown = priorRow.unreadCountKnown,
                        mentionCount = priorRow.mentionCount,
                        mentionCountKnown = priorRow.mentionCountKnown,
                    )
                },
            // server.unreadCount is itself an unread-bearing surface:
            // withRealtimeMessagesForStream derives nextServerUnreadCount from it,
            // so it must freeze to prior too or the next realtime message would
            // increment from a stale zero.
            server = server.copy(unreadCount = prior.server.unreadCount),
            unreadTotal = prior.unreadTotal,
            unreadSource = prior.unreadSource,
            serverUnreadCounts = prior.serverUnreadCounts,
            followedThreadUnread = prior.followedThreadUnread,
        )
    }

    private fun createHomeContent(
        snapshot: HomeSnapshot,
        session: StoredAuthSession,
        contentRefreshing: Boolean,
        previousContent: SharedHomeContentState.Content?
    ): SharedHomeContentState.Content {
        val compatiblePreviousContent =
            previousContent?.takeIf {
                it.snapshot.server.id == snapshot.server.id &&
                    it.session.user.id == session.user.id &&
                    it.snapshot.currentMember.id == snapshot.currentMember.id
            }
        val strippedSnapshot = snapshot.withoutReadStateV1Hydrates()
        val sidebarOrder = strippedSnapshot.sidebarOrder ?: sidebarOrderByServer[strippedSnapshot.server.id]
            ?: compatiblePreviousContent?.baseSnapshot?.sidebarOrder
        val snapshotWithSidebarOrder =
            if (sidebarOrder != null && strippedSnapshot.sidebarOrder != sidebarOrder) {
                strippedSnapshot.copy(sidebarOrder = sidebarOrder)
            } else {
                strippedSnapshot
            }
        val baseSnapshot =
            snapshotWithSidebarOrder
                .withAcceptedReadStateCursors()
                .preserveStableRowsFrom(compatiblePreviousContent?.baseSnapshot)
        return SharedHomeContentState.Content(
            snapshot = baseSnapshot.preserveStableRowsFrom(compatiblePreviousContent?.snapshot),
            session = session,
            contentRefreshing = contentRefreshing,
            baseSnapshot = baseSnapshot
        )
    }

    private fun HomeSnapshot.withoutReadStateV1Hydrates(): HomeSnapshot =
        copy(
            channels = channels.map { row ->
                if (row.readStateV1Hydrate == null) row else row.copy(readStateV1Hydrate = null)
            },
            directMessages = directMessages.map { row ->
                if (row.readStateV1Hydrate == null) row else row.copy(readStateV1Hydrate = null)
            },
        )

    private fun HomeSnapshot.withAcceptedReadStateCursors(): HomeSnapshot {
        val productionOwner =
            readReceiptOwnerFactory
                ?.invoke(server.id.value, currentMember.id.value)
                ?.toReadStateV1ProductionOwner()

        fun selected(scopeId: ChannelId): Boolean =
            productionOwner != null && readStateV1Canary?.isSelected(productionOwner, scopeId.value) == true

        fun checkpoint(scopeId: ChannelId): PerReceiverReadStateCheckpoint =
            perReceiverStateVersionStore.readReadStateCheckpoint(
                serverId = server.id,
                memberId = currentMember.id,
                scopeId = scopeId
            )

        fun HomeChannelRow.withCheckpoint(): HomeChannelRow {
            if (selected(channelId)) return this
            val accepted = checkpoint(channelId)
            return if (accepted.version > 0L && readStateMaxReadSeq == null) {
                copy(readStateMaxReadSeq = accepted.maxReadSeq)
            } else {
                this
            }
        }

        fun HomeDirectMessageRow.withCheckpoint(): HomeDirectMessageRow {
            if (selected(channelId)) return this
            val accepted = checkpoint(channelId)
            return if (accepted.version > 0L && readStateMaxReadSeq == null) {
                copy(readStateMaxReadSeq = accepted.maxReadSeq)
            } else {
                this
            }
        }

        return copy(
            channels = channels.map { it.withCheckpoint() },
            directMessages = directMessages.map { it.withCheckpoint() }
        )
    }

    private suspend fun loadNotifications() {
        val requestGeneration = beginNotificationRequest()
        val requestAuthority = authStore.readSession().notificationAuthority()
        val result = dataSource.loadNotifications()
        val latestAuthority = authStore.readSession().notificationAuthority()
        beforeNotificationPublicationCommit()
        val commitResult =
            synchronized(notificationOwnerLock) {
                val latestGeneration = notificationRequestGeneration
                if (
                    requestGeneration != latestGeneration ||
                    requestAuthority != latestAuthority
                ) {
                    false to latestGeneration
                } else {
                    when (result) {
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
                    true to latestGeneration
                }
            }
        if (!commitResult.first) {
            diagnosticsLogger(
                "home_notifications_publish_skip reason=stale_authority " +
                    "requestGeneration=$requestGeneration latestGeneration=${commitResult.second} " +
                    "requestServerId=${requestAuthority.serverId.orEmpty()} " +
                    "latestServerId=${latestAuthority.serverId.orEmpty()}"
            )
        }
    }

    private fun beginNotificationRequest(): Long =
        synchronized(notificationOwnerLock) {
            notificationRequestGeneration += 1L
            notificationRequestGeneration
        }

    private fun supersedeNotificationRequests() {
        synchronized(notificationOwnerLock) {
            notificationRequestGeneration += 1L
        }
    }

    private fun reconcileNotificationAuthority(
        session: StoredAuthSession?,
        targetServerId: String?
    ) {
        synchronized(notificationOwnerLock) {
            notificationRequestGeneration += 1L
            val currentServerId =
                (_state.value.content as? SharedHomeContentState.Content)?.snapshot?.server?.id?.value
            val authority = session.notificationAuthority(targetServerId)
            if (currentServerId == authority.serverId && authority.canManageMachines) return@synchronized
            val retained = _state.value.notifications.filterNot { it.id.isMachineHomeNotificationId() }
            if (retained != _state.value.notifications) {
                reduceState(_state.value.copy(notifications = retained))
            }
        }
    }

    private fun StoredAuthSession?.notificationAuthority(
        selectedServerId: String? = this?.currentServerId
    ): HomeNotificationAuthority {
        val normalizedServerId = selectedServerId?.trim()?.takeIf { it.isNotBlank() }
        val server = this?.servers?.firstOrNull { it.id == normalizedServerId }
        return HomeNotificationAuthority(
            accountId = this?.user?.id?.trim()?.takeIf { it.isNotBlank() },
            serverId = normalizedServerId,
            canManageMachines = server?.toServerSummary()?.permissions?.canManageMachines == true
        )
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
            val fetched = loadSidebarOrder(latestServerId)
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
                content = nextContent,
                serverDrawer =
                    _state.value.serverDrawer.withServerContent(
                        nextContent,
                        HomeServerDrawerLoadStatus.Loaded
                    )
            ),
            intentOverride = SharedHomeIntent.Refresh
        )
        return true
    }

    private suspend fun applyHomeSessionLocally(session: StoredAuthSession) {
        // #466: these are server-ordering (metadata) changes only — persist onto the store's LATEST
        // session (keeping latest tokens) instead of whole-saving this state-captured snapshot, which
        // could clobber a token the coordinator rotated in between.
        var persisted: StoredAuthSession? = null
        authStore.updateMetadata(expectedAccountId = session.user.id) { latest ->
            latest.copy(
                servers = session.servers,
                selectedServerId = session.selectedServerId,
                selectedServerSlug = session.selectedServerSlug
            ).also { persisted = it }
        }
        // If the store no-op'd (session was cleared / switched to another account under the lock),
        // do NOT re-project the captured (now-stale) session into home state — that would revive a
        // session the persistence layer just correctly rejected. Only project the APPLIED session.
        val applied = persisted ?: return
        contentByServer.entries.forEach { entry ->
            entry.setValue(entry.value.copy(session = applied))
        }
        val current = _state.value.content
        val nextContent =
            when (current) {
                is SharedHomeContentState.Content -> current.copy(session = applied)
                is SharedHomeContentState.Empty -> current.copy(session = applied)
                is SharedHomeContentState.Error -> current.copy(session = applied)
                is SharedHomeContentState.Loading -> current.copy(session = applied)
            }
        reduceState(
            _state.value.copy(
                content = nextContent,
                serverDrawer = _state.value.serverDrawer.withSessionServers(applied)
            )
        )
    }

    private fun StoredAuthSession.withServerOrder(serverOrder: List<String>): StoredAuthSession {
        if (servers.isEmpty()) return this
        val byId = servers.associateBy { it.id }
        val seen = linkedSetOf<String>()
        val cleaned =
            serverOrder
                .mapNotNull { id ->
                    val server = byId[id] ?: return@mapNotNull null
                    if (!seen.add(server.id)) return@mapNotNull null
                    server
                }
        val nextServers = cleaned + servers.filterNot { it.id in seen }
        return if (nextServers == servers) this else copy(servers = nextServers)
    }

    private suspend fun refreshServerOrderBeforeSwitcherOpen() {
        val previousSession = _state.value.content.sessionOrNull() ?: authStore.readSession() ?: return
        when (val result = loadServerOrder()) {
            is DataResult.Content -> {
                val orderedSession = previousSession.withServerOrder(result.data.serverOrder)
                if (orderedSession.servers != previousSession.servers) {
                    applyHomeSessionLocally(orderedSession)
                }
                diagnosticsLogger(
                    "home_server_order_load_result result=content count=${result.data.serverOrder.size} " +
                        "order=${result.data.serverOrder.serverOrderDiagnosticsValue()}"
                )
            }
            is DataResult.Empty ->
                diagnosticsLogger("home_server_order_load_result result=empty")
            is DataResult.Error ->
                diagnosticsLogger("home_server_order_load_result result=error code=${result.error.code}")
            is DataResult.NoPermission ->
                diagnosticsLogger("home_server_order_load_result result=no_permission code=${result.blocker.code}")
        }
    }

    private fun launchServerOrderRefreshForOpenSwitcher() {
        if (serverOrderLoadJob?.isActive == true) return
        serverOrderLoadJob =
            scope.launch {
                val previousReason = stateDiagnosticsReason
                stateDiagnosticsReason = "ServerOrderRefresh"
                try {
                    refreshServerOrderBeforeSwitcherOpen()
                } finally {
                    stateDiagnosticsReason = previousReason
                }
            }
    }

    private suspend fun togglePinned(intent: SharedHomeIntent.TogglePinned) {
        val current = _state.value.content as? SharedHomeContentState.Content
        val serverId = current?.snapshot?.server?.id
        if (serverId == null) {
            pinUpdateFailureCallback("Unable to update pinned channels: home not loaded")
            return
        }
        val previous = ensureSidebarOrderLoaded()
        if (previous == null) {
            pinUpdateFailureCallback("Unable to update pinned channels: sidebar order not loaded")
            return
        }
        val target = current.snapshot.pinnedToggleTarget(intent)
        var next = previous.withToggledPinnedTarget(target)
        // task #512 (web parity): pinning a conversation removes its custom
        // section placement — web moveItemToPinned does the same so pinned and
        // sectioned states stay mutually exclusive. Only then does the PATCH
        // carry section fields (which require the OCC sectionsVersion).
        val wasPinned =
            previous.typedPinnedOrLegacy().any { it.matchesPinnedTarget(target) } ||
                previous.legacyPinnedContains(target)
        val nextPlacements =
            if (wasPinned) {
                previous.sectionPlacements
            } else {
                removeSidebarItemPlacement(
                    removeSidebarItemPlacement(
                        previous.sectionPlacements,
                        SIDEBAR_SECTION_ITEM_KIND_CHANNEL,
                        target.conversationId
                    ),
                    target.item.kind,
                    target.item.id
                )
            }
        if (nextPlacements != previous.sectionPlacements) {
            next = next.copy(sectionPlacements = nextPlacements)
        }
        sidebarPinChangeVersion += 1
        applySidebarOrderLocally(serverId = serverId, next = next)
        val request = SettingsServerSidebarOrderUpdateRequest(
            pinned = next.pinned,
            sectionPlacements = nextPlacements.takeIf { it != previous.sectionPlacements },
            sectionsVersion = previous.sectionsVersion.takeIf { nextPlacements != previous.sectionPlacements }
        )
        when (val result = updateSidebarOrder(request)) {
            is DataResult.Content -> applySidebarOrderLocally(serverId = serverId, next = result.data)
            is DataResult.Empty -> Unit
            is DataResult.Error -> {
                if (result.error.code == "http_409") {
                    reloadSidebarOrderAfterConflict(serverId)
                } else {
                    applySidebarOrderLocally(serverId = serverId, next = previous)
                    pinUpdateFailureCallback("Unable to update pinned channels")
                }
            }
            is DataResult.NoPermission -> {
                applySidebarOrderLocally(serverId = serverId, next = previous)
                pinUpdateFailureCallback("Unable to update pinned channels")
            }
        }
    }

    private suspend fun setPinnedSortMode(mode: RaftSidebarSortMode) {
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
        sidebarPinChangeVersion += 1
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
                if (nextChannelOrder != previous.channelOrder || RaftSidebarSortMode.fromWireValue(previous.channelSortMode) != RaftSidebarSortMode.Manual) {
                    next = next.copy(
                        channelOrder = nextChannelOrder,
                        channelSortMode = RaftSidebarSortMode.Manual.wireValue
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
                if (nextChannelOrder != next.channelOrder || RaftSidebarSortMode.fromWireValue(previous.jointChannelSortMode) != RaftSidebarSortMode.Manual) {
                    // Web keeps a separate Joint Channels sort mode; a manual
                    // joint reorder flips only that one.
                    next = next.copy(
                        channelOrder = nextChannelOrder,
                        jointChannelSortMode = RaftSidebarSortMode.Manual.wireValue
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
                if (nextDmOrder != previous.dmOrder || RaftSidebarSortMode.fromWireValue(previous.dmSortMode) != RaftSidebarSortMode.Manual) {
                    next = next.copy(
                        dmOrder = nextDmOrder,
                        dmSortMode = RaftSidebarSortMode.Manual.wireValue
                    )
                }
            }
        }
        if (intent.pinnedOrder != null) {
            // The sort sheet orders the rows the Pinned section renders, keyed by row id: a
            // channel's id, but a pinned agent/human is a typed `{kind, id: memberId}` ref
            // whose row is its DM conversation (row id = the DM channel id) — or the member
            // itself while no DM exists. Resolve every row id back to its pinned ref
            // (web Sidebar.tsx resolvePinnedRefFromDragId) so DM rows keep their place
            // instead of being dropped and re-appended in their old order.
            val currentPinned = previous.typedPinnedOrLegacy()
            val requestedPinned =
                intent.pinnedOrder
                    .mapNotNull { rowId -> current.snapshot.resolvePinnedRefForRow(rowId, currentPinned) }
                    .distinct()
            if (requestedPinned.isNotEmpty()) {
                val nextPinned = requestedPinned + currentPinned.filterNot { it in requestedPinned }
                if (nextPinned != currentPinned || RaftSidebarSortMode.fromWireValue(previous.pinnedSortMode) != RaftSidebarSortMode.Manual) {
                    next = next.withCanonicalTypedPinned(nextPinned)
                        .copy(pinnedSortMode = RaftSidebarSortMode.Manual.wireValue)
                }
            }
        }
        if (next == previous) return
        applySidebarOrderLocally(serverId = serverId, next = next)
        val request = SettingsServerSidebarOrderUpdateRequest(
            channelOrder = next.channelOrder.takeIf { next.channelOrder != previous.channelOrder },
            channelSortMode = next.channelSortMode.takeIf { next.channelOrder != previous.channelOrder || next.channelSortMode != previous.channelSortMode },
            jointChannelSortMode = next.jointChannelSortMode.takeIf { next.jointChannelSortMode != previous.jointChannelSortMode },
            dmOrder = next.dmOrder.takeIf { next.dmOrder != previous.dmOrder },
            dmSortMode = next.dmSortMode.takeIf { next.dmOrder != previous.dmOrder || next.dmSortMode != previous.dmSortMode },
            pinned = next.pinned.takeIf { next.pinned != previous.pinned },
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

    private suspend fun reorderServers(serverOrder: List<String>) {
        val previousSession = _state.value.content.sessionOrNull()
        if (previousSession == null) {
            pinUpdateFailureCallback("Unable to reorder servers: home not loaded")
            return
        }
        val optimisticSession = previousSession.withServerOrder(serverOrder)
        if (optimisticSession.servers == previousSession.servers) return
        applyHomeSessionLocally(optimisticSession)
        val optimisticOrder = optimisticSession.servers.map { it.id }
        diagnosticsLogger(
            "home_server_order_patch_start count=${optimisticOrder.size} " +
                "order=${optimisticOrder.serverOrderDiagnosticsValue()}"
        )
        when (val result = updateServerOrder(SettingsServerOrderUpdateRequest(optimisticOrder))) {
            is DataResult.Content -> {
                val savedSession = optimisticSession.withServerOrder(result.data.serverOrder)
                applyHomeSessionLocally(savedSession)
                diagnosticsLogger(
                    "home_server_order_patch_result result=content count=${result.data.serverOrder.size} " +
                        "order=${result.data.serverOrder.serverOrderDiagnosticsValue()}"
                )
            }
            is DataResult.Empty ->
                diagnosticsLogger("home_server_order_patch_result result=empty")
            is DataResult.Error,
            is DataResult.NoPermission -> {
                applyHomeSessionLocally(previousSession)
                diagnosticsLogger("home_server_order_patch_result result=error")
                pinUpdateFailureCallback("Unable to reorder servers")
            }
        }
    }

    private suspend fun setChannelSortMode(mode: RaftSidebarSortMode) {
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

    private suspend fun setJointChannelSortMode(mode: RaftSidebarSortMode) {
        val serverId = (_state.value.content as? SharedHomeContentState.Content)?.snapshot?.server?.id
        if (serverId == null) {
            pinUpdateFailureCallback("Unable to update joint channel sort mode: home not loaded")
            return
        }
        val previous = ensureSidebarOrderLoaded()
        if (previous == null) {
            pinUpdateFailureCallback("Unable to update joint channel sort mode: sidebar order not loaded")
            return
        }
        if (previous.jointChannelSortMode == mode.wireValue) return
        val next = previous.copy(jointChannelSortMode = mode.wireValue)
        applySidebarOrderLocally(serverId = serverId, next = next)
        when (
            val result =
                updateSidebarOrder(SettingsServerSidebarOrderUpdateRequest(jointChannelSortMode = next.jointChannelSortMode))
        ) {
            is DataResult.Content -> applySidebarOrderLocally(serverId = serverId, next = result.data)
            is DataResult.Empty -> Unit
            is DataResult.Error,
            is DataResult.NoPermission -> {
                applySidebarOrderLocally(serverId = serverId, next = previous)
                pinUpdateFailureCallback("Unable to update joint channel sort mode")
            }
        }
    }

    // task #512: custom-section mutations (web channel-sections port). All of
    // them follow the setChannelSortMode pattern — optimistic local apply,
    // PATCH, rollback + toast on failure — with two section-specific rules
    // from web serverStore.updateSidebarOrder: the PATCH carries the OCC
    // sectionsVersion, and a 409 reloads the sidebar order instead of
    // rolling back to the (by definition stale) previous snapshot. Section
    // mutations serialize on sidebarSectionsMutex so back-to-back edits PATCH
    // with the version confirmed by the previous response (web's per-server
    // update queue).
    private val sidebarSectionsMutex = Mutex()

    private suspend fun reloadSidebarOrderAfterConflict(serverId: ServerId) {
        sidebarOrderMutex.withLock {
            when (val result = loadSidebarOrder(serverId)) {
                is DataResult.Content -> applySidebarOrderLocally(serverId = serverId, next = result.data)
                is DataResult.Empty,
                is DataResult.Error,
                is DataResult.NoPermission -> Unit
            }
        }
    }

    private suspend fun mutateSidebarSections(
        failureMessage: String,
        mutate: (SettingsServerSidebarOrderSnapshot) -> SettingsServerSidebarOrderSnapshot?
    ) {
        val content = _state.value.content as? SharedHomeContentState.Content
        val serverId = content?.snapshot?.server?.id
        if (serverId == null) {
            pinUpdateFailureCallback("$failureMessage: home not loaded")
            return
        }
        sidebarSectionsMutex.withLock {
            val previous = ensureSidebarOrderLoaded()
            if (previous == null) {
                pinUpdateFailureCallback("$failureMessage: sidebar order not loaded")
                return@withLock
            }
            val next = mutate(previous) ?: return@withLock
            applySidebarOrderLocally(serverId = serverId, next = next)
            val request =
                SettingsServerSidebarOrderUpdateRequest(
                    customSections = next.customSections,
                    sectionOrder = next.sectionOrder,
                    sectionPlacements = next.sectionPlacements,
                    sectionsVersion = previous.sectionsVersion,
                    // MoveConversationToSection also unpins (web parity); when the
                    // mutation changed the pinned list the PATCH must carry it or
                    // the server keeps the conversation pinned.
                    pinned = next.pinned.takeIf { it != previous.pinned }
                )
            when (val result = updateSidebarOrder(request)) {
                is DataResult.Content -> applySidebarOrderLocally(serverId = serverId, next = result.data)
                is DataResult.Empty -> Unit
                is DataResult.Error -> {
                    if (result.error.code == "http_409") {
                        reloadSidebarOrderAfterConflict(serverId)
                    } else {
                        applySidebarOrderLocally(serverId = serverId, next = previous)
                        pinUpdateFailureCallback(failureMessage)
                    }
                }
                is DataResult.NoPermission -> {
                    applySidebarOrderLocally(serverId = serverId, next = previous)
                    pinUpdateFailureCallback(failureMessage)
                }
            }
        }
    }

    /**
     * Web parity (Sidebar.tsx createCustomSection): the section is inserted
     * before [insertBeforeSectionId] (the section whose menu opened the
     * dialog) or appended, and the row whose menu opened it is moved straight
     * into the new section in the same PATCH.
     */
    private suspend fun createSidebarSection(
        name: String,
        emoji: String?,
        moveConversationId: String?,
        insertBeforeSectionId: String?
    ) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return
        val trimmedEmoji = emoji?.trim()?.takeIf { it.isNotEmpty() }
        val snapshot = (_state.value.content as? SharedHomeContentState.Content)?.snapshot
        mutateSidebarSections("Unable to create section") { previous ->
            val section =
                SettingsServerSidebarCustomSection(
                    id = newSidebarSectionId(),
                    name = trimmedName,
                    emoji = trimmedEmoji,
                    sortMode = RaftSidebarSortMode.Manual.wireValue
                )
            val created =
                previous.copy(
                    customSections = previous.customSections + section,
                    sectionOrder =
                        insertSidebarSectionIntoOrder(
                            order = previous.sectionOrder,
                            customSections = previous.customSections,
                            sectionId = section.id,
                            insertBeforeSectionId = insertBeforeSectionId
                        )
                )
            if (moveConversationId != null && snapshot != null) {
                created.withConversationInSection(snapshot, moveConversationId, section.id)
            } else {
                created
            }
        }
    }

    private suspend fun renameSidebarSection(sectionId: String, name: String, emoji: String?) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return
        val trimmedEmoji = emoji?.trim()?.takeIf { it.isNotEmpty() }
        mutateSidebarSections("Unable to rename section") { previous ->
            val section = previous.customSections.firstOrNull { it.id == sectionId } ?: return@mutateSidebarSections null
            if (section.name == trimmedName && section.emoji == trimmedEmoji) return@mutateSidebarSections null
            previous.copy(
                customSections =
                    previous.customSections.map { candidate ->
                        if (candidate.id == sectionId) candidate.copy(name = trimmedName, emoji = trimmedEmoji) else candidate
                    }
            )
        }
    }

    private suspend fun deleteSidebarSection(sectionId: String) {
        mutateSidebarSections("Unable to delete section") { previous ->
            if (previous.customSections.none { it.id == sectionId }) return@mutateSidebarSections null
            previous.copy(
                customSections = previous.customSections.filterNot { it.id == sectionId },
                sectionOrder = previous.sectionOrder.filter { it != sectionId },
                sectionPlacements = previous.sectionPlacements.filterNot { it.sectionId == sectionId }
            )
        }
    }

    private suspend fun setSidebarSectionSortMode(sectionId: String, mode: RaftSidebarSortMode) {
        mutateSidebarSections("Unable to update section sort mode") { previous ->
            val section = previous.customSections.firstOrNull { it.id == sectionId } ?: return@mutateSidebarSections null
            if (section.sortMode == mode.wireValue) return@mutateSidebarSections null
            previous.copy(
                customSections =
                    previous.customSections.map { candidate ->
                        if (candidate.id == sectionId) candidate.copy(sortMode = mode.wireValue) else candidate
                    }
            )
        }
    }

    private suspend fun moveConversationToSection(conversationId: String, sectionId: String?) {
        val snapshot = (_state.value.content as? SharedHomeContentState.Content)?.snapshot
        if (snapshot == null) {
            pinUpdateFailureCallback("Unable to move conversation to section: home not loaded")
            return
        }
        mutateSidebarSections("Unable to move conversation to section") { previous ->
            if (sectionId != null && previous.customSections.none { it.id == sectionId }) return@mutateSidebarSections null
            val moved = previous.withConversationInSection(snapshot, conversationId, sectionId)
            if (moved == previous) null else moved
        }
    }

    private suspend fun markConversationRead(conversationId: String) {
        val current = _state.value.content as? SharedHomeContentState.Content
        val target = current?.snapshot?.findHomeConversation(conversationId)
        if (current == null || target == null) {
            pinUpdateFailureCallback("Unable to mark conversation read: home not loaded")
            return
        }
        // A mention-only row (count 0, web `mentionFlags`) is still worth a read: it clears the @.
        if (target.unreadCount <= 0 && target.mentionCount <= 0) return
        val coordinator = readReceiptCoordinator
        val owner =
            readReceiptOwnerFactory?.invoke(
                current.snapshot.server.id.value,
                current.snapshot.currentMember.id.value
            )
        if (coordinator != null && owner != null) {
            applyReadReceiptPrediction(coordinator.markReadAll(owner, target.messageStreamChannelId()))
            return
        }
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

    internal fun applyReadReceiptPrediction(prediction: ReadReceiptPrediction) {
        synchronized(readProjectionOwnerLock) {
            onReadReceiptPredictionOwnerEntered()
            val current = _state.value.content as? SharedHomeContentState.Content ?: return@synchronized
            if (!current.snapshot.matchesReadReceiptOwner(prediction.owner)) return@synchronized
            val previousRevision = readPredictionRevisions[prediction.channelId]
            if (previousRevision != null && previousRevision >= prediction.revision) return@synchronized
            readPredictionRevisions = readPredictionRevisions + (prediction.channelId to prediction.revision)
            applyHomeSnapshotLocally(
                transform = { snapshot ->
                    snapshot.withConversationUnread(prediction.channelId.value, 0)
                },
                intentOverride = SharedHomeIntent.ApplyReadReceiptPrediction(prediction)
            )
        }
    }

    internal fun handleReadReceiptFailure(request: ReadReceiptRequest) {
        val shouldRefresh =
            synchronized(readProjectionOwnerLock) {
                val current = _state.value.content as? SharedHomeContentState.Content ?: return@synchronized false
                if (!current.snapshot.matchesReadReceiptOwner(request.owner)) return@synchronized false
                if (readPredictionRevisions[request.channelId] != request.revision) return@synchronized false
                readPredictionRevisions = readPredictionRevisions - request.channelId
                true
            }
        if (shouldRefresh) dispatch(SharedHomeIntent.Refresh)
    }

    internal fun hydrateDirectMessagePreview(intent: SharedHomeIntent.HydrateDirectMessagePreview) {
        val current = _state.value.content as? SharedHomeContentState.Content ?: return
        if (current.snapshot.server.id != intent.serverId) return
        if (intent.accountId.isNotBlank() && current.snapshot.currentMember.id.value != intent.accountId) return

        applyHomeSnapshotLocally(intentOverride = intent) { snapshot ->
            if (snapshot.server.id != intent.serverId) return@applyHomeSnapshotLocally snapshot
            if (intent.accountId.isNotBlank() && snapshot.currentMember.id.value != intent.accountId) return@applyHomeSnapshotLocally snapshot
            val targetDm = snapshot.directMessages.firstOrNull { it.matchesHomeConversation(intent.channelId.value) }
                ?: return@applyHomeSnapshotLocally snapshot

            if (!targetDm.lastMessagePreview.isNullOrBlank()) {
                return@applyHomeSnapshotLocally snapshot
            }

            val updatedDm = targetDm.copy(
                lastMessagePreview = intent.preview?.takeIf { it.isNotBlank() },
                lastMessageSenderName = intent.senderName?.takeIf { it.isNotBlank() },
                lastMessageAt = targetDm.lastMessageAt ?: intent.lastMessageAt,
                lastMessageId = targetDm.lastMessageId ?: intent.lastMessageId,
            )

            snapshot.copy(
                directMessages = snapshot.directMessages.map { row ->
                    if (row.matchesHomeConversation(intent.channelId.value)) updatedDm else row
                }
            )
        }
    }

    private fun HomeSnapshot.matchesReadReceiptOwner(owner: ReadReceiptOwner): Boolean {
        val serverId = server.id.value
        val accountId = currentMember.id.value
        if (serverId != owner.serverId || accountId != owner.accountId) return false
        val ownerFactory = readReceiptOwnerFactory ?: return true
        return ownerFactory(serverId, accountId) == owner
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
        when (val result = markChannelUnread(channelId)) {
            is DataResult.Content -> {
                val owner =
                    readReceiptOwnerFactory
                        ?.invoke(current.snapshot.server.id.value, current.snapshot.currentMember.id.value)
                        ?.toReadStateV1ProductionOwner()
                if (owner != null) {
                    readStateV1Canary?.applyMutationAck(
                        owner = owner,
                        channelId = channelId.value,
                        readStateVersion = result.data.readStateVersion,
                        maxReadSeq = result.data.maxReadSeq,
                    )
                }
            }
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

    private suspend fun setConversationActivityMuted(conversationId: String, muted: Boolean) {
        val current = _state.value.content as? SharedHomeContentState.Content
        val target = current?.snapshot?.findHomeConversation(conversationId)
        if (current == null || target == null) {
            pinUpdateFailureCallback("Unable to update channel notifications: home not loaded")
            return
        }
        if (!target.activityMuteSupported) {
            pinUpdateFailureCallback("Unable to update channel notifications")
            return
        }
        if (target.activityMuted == muted) return
        val channelId = target.messageStreamChannelId()
        val previousSettings = target.toChannelActivityMuteSettings(channelId)
        val request =
            ChannelActivityMuteRequest(
                id = ++channelActivityMuteRequestSeq,
                muted = muted
            )
        channelActivityMuteRequests[channelId] = request
        applyHomeSnapshotLocally {
            it.withChannelActivityMute(
                ChannelActivityMuteSettings(
                    channelId = channelId,
                    activityNotificationPreference =
                        target.activityNotificationPreference.copy(
                            activityMuted = muted,
                            muteFromSeq = if (muted) target.muteFromSeq else null
                        )
                )
            )
        }
        when (val result = setChannelActivityMuted(channelId, muted)) {
            is DataResult.Content -> {
                val version = result.data.prefsVersion
                if (version == null || version <= 0L) {
                    if (channelActivityMuteRequests[channelId] == request) {
                        channelActivityMuteRequests.remove(channelId)
                        // A successful HTTP status without the per-receiver
                        // version is not durable authority. Drop the transient
                        // prediction instead of bypassing the shared gate.
                        applyHomeSnapshotLocally { it.withChannelActivityMute(previousSettings) }
                        pinUpdateFailureCallback("Unable to update channel notifications")
                    }
                } else {
                    val activeRequest = channelActivityMuteRequests[channelId]
                    if (activeRequest == request) {
                        val content = _state.value.content as? SharedHomeContentState.Content
                        val applied =
                            content?.let {
                                applyPerReceiverNotificationPrefs(
                                    PerReceiverNotificationPrefsUpdate(
                                        serverId = it.snapshot.server.id,
                                        memberId = it.snapshot.currentMember.id,
                                        channelId = channelId,
                                        version = version,
                                        preference = result.data.activityNotificationPreference
                                    )
                                )
                            } ?: false
                        diagnosticsLogger(
                            "per_receiver_notification_prefs_response_apply channelId=${channelId.value} " +
                                "version=$version muted=${result.data.activityMuted} " +
                                "result=${if (applied) "applied" else "skipped"}"
                        )
                    } else {
                        // The echo already resolved this request, or a newer
                        // prediction replaced it. Do not let the stale response
                        // briefly overwrite current UI; echoes remain
                        // independent version-gated inputs.
                        diagnosticsLogger(
                            "per_receiver_notification_prefs_response_superseded channelId=${channelId.value} " +
                                "version=$version muted=${result.data.activityMuted}"
                        )
                    }
                }
            }
            is DataResult.Empty -> {
                if (channelActivityMuteRequests[channelId] == request) {
                    channelActivityMuteRequests.remove(channelId)
                    applyHomeSnapshotLocally { it.withChannelActivityMute(previousSettings) }
                    pinUpdateFailureCallback("Unable to update channel notifications")
                }
            }
            is DataResult.Error,
            is DataResult.NoPermission -> {
                if (channelActivityMuteRequests[channelId] == request) {
                    channelActivityMuteRequests.remove(channelId)
                    applyHomeSnapshotLocally { it.withChannelActivityMute(previousSettings) }
                    pinUpdateFailureCallback("Unable to update channel notifications")
                }
            }
        }
    }

    private fun applyPerReceiverReadState(update: PerReceiverReadStateUpdate): HomeReadStateApplyOutcome {
        // RFC 041 §2.2: the version is monotonic per (kind, scope) — for
        // read_state the scope is the channel (server PR #4175 keeps the
        // counter on the per-(user, channel) cursor row), so the gate MUST
        // key on channelId or one channel's high version swallows another's.
        val current = _state.value.content as? SharedHomeContentState.Content
            ?: return HomeReadStateApplyOutcome.Skipped
        if (current.snapshot.server.id != update.serverId) return HomeReadStateApplyOutcome.Skipped
        if (current.snapshot.currentMember.id != update.memberId) return HomeReadStateApplyOutcome.Skipped
        val incomingMaxReadSeq = update.maxReadSeq ?: return HomeReadStateApplyOutcome.Skipped
        val factResult =
            perReceiverStateVersionStore.applyOrObserveReadStateFact(
                serverId = update.serverId,
                memberId = update.memberId,
                scopeId = update.channelId,
                incomingVersion = update.version,
                incomingMaxReadSeq = incomingMaxReadSeq
            )
        if (factResult != PerReceiverReadStateFactResult.Accepted) {
            return HomeReadStateApplyOutcome.Skipped
        }
        applyHomeSnapshotLocally { snapshot ->
            snapshot.withPerReceiverReadState(
                update = update.copy(maxReadSeq = incomingMaxReadSeq)
            )
        }
        return HomeReadStateApplyOutcome.AcceptedNeedsReconcile
    }

    private fun applyPerReceiverNotificationPrefs(update: PerReceiverNotificationPrefsUpdate): Boolean {
        if (!canApplyPerReceiverState(
                serverId = update.serverId,
                memberId = update.memberId,
                scope = PerReceiverStateScope.NotificationPrefs,
                version = update.version,
                scopeId = update.channelId
            )
        ) {
            return false
        }
        perReceiverStateVersionStore.recordApplied(
            serverId = update.serverId,
            memberId = update.memberId,
            scope = PerReceiverStateScope.NotificationPrefs,
            incomingVersion = update.version,
            scopeId = update.channelId
        )
        // A versioned authority fold resolves the local prediction. This also
        // covers an echo arriving before its PATCH response.
        channelActivityMuteRequests.remove(update.channelId)
        return applyHomeSnapshotLocally { snapshot ->
            snapshot.withChannelActivityMute(
                ChannelActivityMuteSettings(
                    channelId = update.channelId,
                    activityNotificationPreference = update.preference
                )
            )
        }
    }

    private fun applyPerReceiverServerOrder(update: PerReceiverServerOrderUpdate): Boolean {
        if (!canApplyPerReceiverState(
                serverId = update.serverId,
                memberId = update.memberId,
                scope = PerReceiverStateScope.ServerOrder,
                version = update.version
            )
        ) {
            return false
        }
        perReceiverStateVersionStore.recordApplied(
            serverId = update.serverId,
            memberId = update.memberId,
            scope = PerReceiverStateScope.ServerOrder,
            incomingVersion = update.version
        )
        return applySidebarOrderLocally(
            serverId = update.serverId,
            next = update.sidebarOrder
        )
    }

    private fun applyPerReceiverPinned(update: PerReceiverPinnedUpdate): Boolean {
        if (!canApplyPerReceiverState(
                serverId = update.serverId,
                memberId = update.memberId,
                scope = PerReceiverStateScope.Pinned,
                version = update.version
            )
        ) {
            return false
        }
        perReceiverStateVersionStore.recordApplied(
            serverId = update.serverId,
            memberId = update.memberId,
            scope = PerReceiverStateScope.Pinned,
            incomingVersion = update.version
        )
        sidebarPinChangeVersion += 1
        return applySidebarOrderLocally(
            serverId = update.serverId,
            next = update.sidebarOrder
        )
    }

    private fun canApplyPerReceiverState(
        serverId: ServerId,
        memberId: MemberId,
        scope: PerReceiverStateScope,
        version: Long,
        scopeId: ChannelId? = null
    ): Boolean {
        if (version <= 0L) return false
        val content = _state.value.content as? SharedHomeContentState.Content ?: return false
        if (content.snapshot.server.id != serverId) return false
        if (content.snapshot.currentMember.id != memberId) return false
        return perReceiverStateVersionStore
            .decide(
                serverId = serverId,
                memberId = memberId,
                scope = scope,
                incomingVersion = version,
                scopeId = scopeId
            )
            .shouldApply
    }

    private fun applyHomeSnapshotLocally(
        intentOverride: SharedHomeIntent? = null,
        transform: (HomeSnapshot) -> HomeSnapshot
    ): Boolean {
        val current = _state.value.content as? SharedHomeContentState.Content ?: return false
        val nextSnapshot = transform(current.snapshot)
        val nextBaseSnapshot = transform(current.baseSnapshot)
        if (nextSnapshot == current.snapshot && nextBaseSnapshot == current.baseSnapshot) {
            return false
        }
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
                serverUnreadCounts = nextSnapshot.serverUnreadCounts,
                appBadgeCount =
                    snapshotToBadgeCount(
                        snapshot = nextBaseSnapshot,
                        selectedServerId = nextBaseSnapshot.server.id,
                        isStale = false,
                        nowMillis = nowMillis
                    )
            ),
            intentOverride = intentOverride
        )
        return true
    }

    private fun rollbackHomeContent(previous: SharedHomeContentState.Content) {
        updateConversationIdentity(previous.baseSnapshot)
        contentByServer[previous.baseSnapshot.server.id] = previous
        reduceState(_state.value.copy(content = previous))
    }

    private fun updateConversationIdentity(snapshot: HomeSnapshot) {
        val serverKey = snapshot.server.id.value
        val accountId = snapshot.currentMember.id.value
        val ownerChanged = bindConversationIdentityOwner(serverKey, accountId)
        homeConversationIdentityStates[serverKey] =
            snapshot.toSharedConversationDirectoryState(logger = diagnosticsLogger)
        publishConversationIdentity(serverKey, forcePublication = ownerChanged)
    }

    /**
     * A retained Home store can outlive an auth owner. Never merge rows collected for the old
     * account into the first candidate published for its successor, even when both accounts use
     * the same server id.
     */
    private fun bindConversationIdentityOwner(serverKey: String, accountId: String): Boolean {
        val normalizedAccountId = accountId.trim()
        if (serverKey.isBlank() || normalizedAccountId.isBlank()) return false
        val previous = conversationIdentityAccountByServer[serverKey]
        if (previous == normalizedAccountId) return false
        homeConversationIdentityStates.remove(serverKey)
        supplementalConversationIdentityStates.remove(serverKey)
        conversationIdentityRevisionByServer.remove(serverKey)
        conversationIdentityStates.value = conversationIdentityStates.value - serverKey
        conversationIdentityAccountByServer[serverKey] = normalizedAccountId
        conversationIdentityOwners.value = conversationIdentityOwners.value + (serverKey to normalizedAccountId)
        return true
    }

    private fun publishConversationIdentity(serverKey: String, forcePublication: Boolean = false) {
        val supplements =
            supplementalConversationIdentityStates[serverKey]
                .orEmpty()
                .entries
                .sortedBy { it.key.precedence }
                .map { it.value }
        val merged =
            (supplements + listOfNotNull(homeConversationIdentityStates[serverKey]))
                .mergedSharedConversationDirectoryState(logger = diagnosticsLogger)
        val next = conversationIdentityStates.value + (serverKey to merged)
        val changed = next != conversationIdentityStates.value
        if (changed) {
            conversationIdentityStates.value = next
        }
        if (changed || forcePublication) {
            val accountId = conversationIdentityAccountByServer[serverKey]?.takeIf { it.isNotBlank() }
                ?: return
            val revision = (conversationIdentityRevisionByServer[serverKey] ?: 0L) + 1L
            conversationIdentityRevisionByServer[serverKey] = revision
            conversationIdentityPublicationSink(
                SharedConversationIdentityCandidatePublication(
                    serverId = ServerId(serverKey),
                    accountId = accountId,
                    revision = revision,
                    state = merged,
                )
            )
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

    private fun reduceState(
        nextState: SharedHomeState,
        intentOverride: SharedHomeIntent? = null
    ): Boolean {
        val previousState = _state.value
        val intent = intentOverride ?: stateDiagnosticsIntent
        val diagnosticsReason = intentOverride?.toSharedHomeIntentDiagnosticsLabel() ?: stateDiagnosticsReason
        val reducedState =
            if (intent == null || persistSlices.isEmpty()) {
                nextState
            } else {
                persistSlices.fold(nextState) { state, slice ->
                    slice.reduce(previous = previousState, intent = intent, reduced = state)
                }
            }
        val finalState =
            (reducedState.content as? SharedHomeContentState.Content)?.let { content ->
                reducedState.copy(
                    serverDrawer =
                        reducedState.serverDrawer.withServerContent(
                            content = content,
                            status =
                                if (content.contentRefreshing) {
                                    HomeServerDrawerLoadStatus.Refreshing
                                } else {
                                    HomeServerDrawerLoadStatus.Loaded
                                }
                        )
                )
            } ?: reducedState
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
            "shared_home_state_change reason=$diagnosticsReason " +
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
    authStore: RaftAuthSessionStore,
    dataSource: SharedHomeDataSource,
    diagnosticsLogger: (String) -> Unit = {},
    loadSidebarOrder: suspend (ServerId) -> DataResult<SettingsServerSidebarOrderSnapshot> = { _ ->
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
        count =
            when (snapshot.unreadSource) {
                AppBadgeSource.ACTIVITY_UNREAD_SUMMARY,
                AppBadgeSource.SERVER_UNREAD_SUMMARY -> snapshot.unreadTotal
                AppBadgeSource.DISABLED -> 0
                AppBadgeSource.CHANNEL_DM_FALLBACK,
                AppBadgeSource.UNKNOWN -> snapshot.unmutedUnreadTotal()
            },
        source = snapshot.unreadSource,
        selectedServerId = selectedServerId,
        lastUpdated = nowMillis(),
        isStale = isStale
    )

private fun HomeSnapshot.unmutedUnreadTotal(): Int =
    (channels.asSequence() + directMessages.asSequence())
        .filterNot { it.activityMuted }
        .sumOf { it.unreadCount.coerceAtLeast(0) }

private fun HomeSnapshot.findHomeConversation(conversationId: String): HomeConversationRow? =
    (channels + directMessages).firstOrNull { it.matchesHomeConversation(conversationId) }

private data class PinnedToggleTarget(
    val conversationId: String,
    val item: SettingsServerSidebarPinnedItem
)

private fun HomeSnapshot.pinnedToggleTarget(intent: SharedHomeIntent.TogglePinned): PinnedToggleTarget {
    val conversationId = intent.conversationId
    val explicitKind = intent.pinnedKind?.trim()?.lowercase()
    val explicitId = intent.pinnedId?.trim()?.takeIf { it.isNotBlank() }
    if (explicitKind in setOf("channel", "agent", "human") && explicitId != null) {
        return PinnedToggleTarget(
            conversationId = conversationId,
            item = SettingsServerSidebarPinnedItem(kind = explicitKind.orEmpty(), id = explicitId)
        )
    }
    val directMessage =
        directMessages.firstOrNull { row ->
            row.threadId.value == conversationId || row.channelId.value == conversationId
        }
    val peerType = directMessage?.directMessagePeerType?.lowercase()
    val memberId = directMessage?.presenceMemberId?.value?.takeIf { it.isNotBlank() }
    val item =
        when (peerType) {
            "agent" -> memberId?.let { SettingsServerSidebarPinnedItem(kind = "agent", id = it) }
            "human", "user" -> memberId?.let { SettingsServerSidebarPinnedItem(kind = "human", id = it) }
            else -> null
        } ?: SettingsServerSidebarPinnedItem(kind = "channel", id = conversationId)
    return PinnedToggleTarget(conversationId = conversationId, item = item)
}

private fun SettingsServerSidebarOrderSnapshot.withToggledPinnedTarget(
    target: PinnedToggleTarget
): SettingsServerSidebarOrderSnapshot {
    val current = typedPinnedOrLegacy()
    val isPinned = current.any { it.matchesPinnedTarget(target) } || legacyPinnedContains(target)
    val withoutTarget = current.filterNot { it.matchesPinnedTarget(target) }
    val nextPinned = if (isPinned) withoutTarget else withoutTarget + target.item
    return withCanonicalTypedPinned(nextPinned)
}

/** task #512: unpin without toggling — used when a conversation moves into a custom section. */
/**
 * Place [conversationId] in custom section [sectionId] (null: back to its
 * default section). Web parity (Sidebar.tsx moveItemToCustomSection): moving
 * into a custom section unpins the conversation, so the pinned and sectioned
 * states stay mutually exclusive. The placement key follows the conversation
 * type — agent-peer DMs key by their agent member id (kind "agent"), everything
 * else by channel id (kind "channel") — matching web's movable-item derivation
 * and the UI's placement resolution.
 */
private fun SettingsServerSidebarOrderSnapshot.withConversationInSection(
    home: HomeSnapshot,
    conversationId: String,
    sectionId: String?
): SettingsServerSidebarOrderSnapshot {
    val pinnedTarget = home.pinnedToggleTarget(SharedHomeIntent.TogglePinned(conversationId))
    val wasPinned = typedPinnedOrLegacy().any { it.matchesPinnedTarget(pinnedTarget) } || legacyPinnedContains(pinnedTarget)
    val row = home.findHomeConversation(conversationId)
    val (placementKind, placementId) =
        sidebarSectionPlacementKindId(
            peerType = row?.directMessagePeerType,
            presenceMemberId = row?.presenceMemberId?.value,
            conversationId = conversationId
        )
    val nextPlacements =
        if (sectionId == null) {
            removeSidebarItemPlacement(sectionPlacements, placementKind, placementId)
        } else {
            moveSidebarItemToCustomSection(sectionPlacements, placementKind, placementId, sectionId)
        }
    val next = if (sectionId != null && wasPinned) withoutPinnedTarget(pinnedTarget) else this
    return next.copy(sectionPlacements = nextPlacements)
}

private fun SettingsServerSidebarOrderSnapshot.withoutPinnedTarget(
    target: PinnedToggleTarget
): SettingsServerSidebarOrderSnapshot =
    withCanonicalTypedPinned(typedPinnedOrLegacy().filterNot { it.matchesPinnedTarget(target) })

internal fun SettingsServerSidebarOrderSnapshot.typedPinnedOrLegacy(): List<SettingsServerSidebarPinnedItem> {
    if (pinned.isNotEmpty()) return pinned
    val orderedIds =
        (pinnedOrder + pinnedChannelIds + pinnedAgentIds)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    return orderedIds.mapNotNull { id ->
        when {
            id in pinnedAgentIds -> SettingsServerSidebarPinnedItem(kind = "agent", id = id)
            id in pinnedChannelIds -> SettingsServerSidebarPinnedItem(kind = "channel", id = id)
            else -> null
        }
    }
}

private fun SettingsServerSidebarOrderSnapshot.legacyPinnedContains(target: PinnedToggleTarget): Boolean =
    target.conversationId in pinnedChannelIds ||
        (target.item.kind == "agent" && target.item.id in pinnedAgentIds) ||
        target.item.id in pinnedOrder ||
        target.conversationId in pinnedOrder

private fun SettingsServerSidebarPinnedItem.matchesPinnedTarget(target: PinnedToggleTarget): Boolean =
    kind == target.item.kind && id == target.item.id ||
        kind == "channel" && id == target.conversationId

/**
 * The pinned ref behind a Pinned-section row id: a ref whose own id is [rowId] (a channel,
 * or a member row shown before its DM exists), else the agent/human ref of the DM
 * conversation [rowId] names. Null for a row that is not pinned.
 */
internal fun HomeSnapshot.resolvePinnedRefForRow(
    rowId: String,
    pinned: List<SettingsServerSidebarPinnedItem>
): SettingsServerSidebarPinnedItem? {
    val id = rowId.trim().takeIf { it.isNotBlank() } ?: return null
    pinned.firstOrNull { it.id == id }?.let { return it }
    val directMessage =
        directMessages.firstOrNull { row -> row.threadId.value == id || row.channelId.value == id } ?: return null
    val memberId = directMessage.presenceMemberId?.value?.takeIf { it.isNotBlank() } ?: return null
    val kind =
        when (directMessage.directMessagePeerType?.trim()?.lowercase()) {
            "agent" -> "agent"
            "human", "user" -> "human"
            else -> null
        }
    return pinned.firstOrNull { ref -> ref.id == memberId && (kind == null || ref.kind == kind) }
}

private fun SettingsServerSidebarOrderSnapshot.withCanonicalTypedPinned(
    nextPinned: List<SettingsServerSidebarPinnedItem>
): SettingsServerSidebarOrderSnapshot =
    copy(
        pinned = nextPinned,
        pinnedChannelIds = nextPinned.filter { it.kind == "channel" }.map { it.id },
        pinnedAgentIds = nextPinned.filter { it.kind == "agent" }.map { it.id },
        pinnedOrder = nextPinned.map { it.id }
    )

private data class ChannelActivityMuteRequest(
    val id: Long,
    val muted: Boolean
)

private enum class SharedHomeChannelFoldEvent(val wireName: String) {
    Hydrate("hydrate"),
    HydrateDirectMessage("hydrate:dm"),
    Ensure("ensure"),
    Patch("patch"),
    Remove("remove"),
    TouchActivity("touch-activity"),
    Mute("mute")
}

private fun SharedHomeIntent.toSharedHomeIntentDiagnosticsLabel(): String =
    when (this) {
        SharedHomeIntent.Start -> "${SharedHomeChannelFoldEvent.Hydrate.wireName}:start"
        SharedHomeIntent.Refresh -> "${SharedHomeChannelFoldEvent.Hydrate.wireName}:refresh"
        SharedHomeIntent.ForegroundRefresh -> "${SharedHomeChannelFoldEvent.Hydrate.wireName}:foreground"
        SharedHomeIntent.ToggleServerSwitcher -> "${SharedHomeChannelFoldEvent.Patch.wireName}:server-switcher"
        SharedHomeIntent.DismissServerSwitcher -> "${SharedHomeChannelFoldEvent.Patch.wireName}:server-switcher-dismiss"
        is SharedHomeIntent.ToggleServerDrawerSection ->
            "${SharedHomeChannelFoldEvent.Patch.wireName}:server-drawer-toggle:${serverId.value}"
        is SharedHomeIntent.RetryServerDrawerSection ->
            "${SharedHomeChannelFoldEvent.Hydrate.wireName}:server-drawer-retry:${serverId.value}"
        is SharedHomeIntent.SelectConversation -> "${SharedHomeChannelFoldEvent.Patch.wireName}:select:$threadId"
        SharedHomeIntent.ClearSelectedConversation -> "${SharedHomeChannelFoldEvent.Patch.wireName}:select:_"
        is SharedHomeIntent.SelectTopAction -> "${SharedHomeChannelFoldEvent.Patch.wireName}:top-action:$action"
        is SharedHomeIntent.SelectServer -> "${SharedHomeChannelFoldEvent.Hydrate.wireName}:server:$serverId"
        is SharedHomeIntent.ApplyRealtimeEvent -> "${event.toSharedHomeChannelFoldEvent().wireName}:$serverId:${event.toSharedHomeRealtimeEventDiagnosticsLabel()}"
        is SharedHomeIntent.ApplyBackgroundServerUnreadEvent ->
            "${SharedHomeChannelFoldEvent.Patch.wireName}:background-unread:$serverId:${event.toSharedHomeRealtimeEventDiagnosticsLabel()}"
        SharedHomeIntent.RefreshNotifications -> "${SharedHomeChannelFoldEvent.Patch.wireName}:notifications-refresh"
        is SharedHomeIntent.UpsertNotification -> "${SharedHomeChannelFoldEvent.Patch.wireName}:notification:${notification.id}"
        is SharedHomeIntent.DismissNotification -> "${SharedHomeChannelFoldEvent.Patch.wireName}:notification-dismiss:$id"
        is SharedHomeIntent.ChannelCreated -> "${SharedHomeChannelFoldEvent.Ensure.wireName}:${channel.threadId.value}"
        is SharedHomeIntent.ChannelProfileUpdated ->
            "${SharedHomeChannelFoldEvent.Patch.wireName}:channel-profile:${channelId.value}"
        is SharedHomeIntent.SetSurfaceVisible -> "${SharedHomeChannelFoldEvent.Patch.wireName}:surface-visible:$visible"
        is SharedHomeIntent.TogglePinned -> "${SharedHomeChannelFoldEvent.Patch.wireName}:pin:$conversationId"
        is SharedHomeIntent.SetPinnedSortMode -> "${SharedHomeChannelFoldEvent.Patch.wireName}:pinned-sort:$mode"
        is SharedHomeIntent.SetChannelSortMode -> "${SharedHomeChannelFoldEvent.Patch.wireName}:channel-sort:$mode"
        is SharedHomeIntent.SetJointChannelSortMode -> "${SharedHomeChannelFoldEvent.Patch.wireName}:joint-channel-sort:$mode"
        is SharedHomeIntent.CreateSidebarSection -> "${SharedHomeChannelFoldEvent.Patch.wireName}:section-create:$name"
        is SharedHomeIntent.RenameSidebarSection -> "${SharedHomeChannelFoldEvent.Patch.wireName}:section-rename:$sectionId"
        is SharedHomeIntent.DeleteSidebarSection -> "${SharedHomeChannelFoldEvent.Patch.wireName}:section-delete:$sectionId"
        is SharedHomeIntent.MoveConversationToSection -> "${SharedHomeChannelFoldEvent.Patch.wireName}:section-move:$conversationId:${sectionId ?: "default"}"
        is SharedHomeIntent.SetSidebarSectionSortMode -> "${SharedHomeChannelFoldEvent.Patch.wireName}:section-sort:$sectionId:$mode"
        is SharedHomeIntent.ReorderSidebar -> "${SharedHomeChannelFoldEvent.Patch.wireName}:sidebar-order:${channelOrder?.size ?: 0}/${jointChannelOrder?.size ?: 0}/${dmOrder?.size ?: 0}/${pinnedOrder?.size ?: 0}"
        is SharedHomeIntent.ReorderServers -> "${SharedHomeChannelFoldEvent.Patch.wireName}:server-order:${serverOrder.size}"
        is SharedHomeIntent.MarkRead -> "${SharedHomeChannelFoldEvent.Patch.wireName}:read:$conversationId"
        is SharedHomeIntent.MarkUnread -> "${SharedHomeChannelFoldEvent.Patch.wireName}:unread:$conversationId"
        is SharedHomeIntent.CloseDirectMessage -> "${SharedHomeChannelFoldEvent.Remove.wireName}:dm:$conversationId"
        is SharedHomeIntent.ArchiveChannel -> "${SharedHomeChannelFoldEvent.Remove.wireName}:archive:$conversationId"
        is SharedHomeIntent.SetChannelActivityMuted -> "${SharedHomeChannelFoldEvent.Mute.wireName}:$conversationId:$muted"
        is SharedHomeIntent.ApplyPerReceiverReadState -> "read_state:${update.serverId.value}:${update.channelId.value}:${update.version}"
        is SharedHomeIntent.ApplyPerReceiverNotificationPrefs -> "notification_prefs:${update.serverId.value}:${update.channelId.value}:${update.version}"
        is SharedHomeIntent.ApplyPerReceiverServerOrder -> "server_order:${update.serverId.value}:${update.version}"
        is SharedHomeIntent.ApplyPerReceiverPinned -> "pinned:${update.serverId.value}:${update.version}"
        is SharedHomeIntent.ApplyReadReceiptPrediction -> "read_prediction:${prediction.channelId.value}:${prediction.revision}"
        is SharedHomeIntent.ApplyReadReceiptFailure -> "read_failure:${request.channelId.value}:${request.revision}"
        is SharedHomeIntent.HydrateDirectMessagePreview -> "hydrate_dm_preview:${channelId.value}"
    }

private fun RealtimeEvent.toSharedHomeChannelFoldEvent(): SharedHomeChannelFoldEvent =
    when (this) {
        is DirectMessageCreated -> SharedHomeChannelFoldEvent.HydrateDirectMessage
        is MessageNew,
        is MessageUpdated,
        is SyncResumeResponse -> SharedHomeChannelFoldEvent.TouchActivity
        is RealtimeRefreshRequired -> SharedHomeChannelFoldEvent.Hydrate
        else -> SharedHomeChannelFoldEvent.Patch
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

internal data class HomeRealtimeMessagePatch(
    val messages: List<Message>,
    val incrementUnread: Boolean
)

private data class BackgroundUnreadScopeKey(
    val serverId: ServerId,
    val scopeId: ChannelId
)

private data class BackgroundUnreadScopeProjection(
    val countBySeq: LinkedHashMap<Long, Int> = linkedMapOf(),
    var unknownSeqCount: Int = 0
) {
    fun removeThrough(maxReadSeq: Long): Int {
        var removed = 0
        val iterator = countBySeq.iterator()
        while (iterator.hasNext()) {
            val (seq, count) = iterator.next()
            if (seq <= maxReadSeq) {
                removed += count
                iterator.remove()
            }
        }
        // A missing seq cannot be proven read by a watermark. Keep those
        // counts until the canonical unread-summary replaces the projection.
        return removed
    }

    fun trimOldestBuckets() {
        while (countBySeq.size > BackgroundUnreadSeqBucketLimit) {
            countBySeq.entries.iterator().also { iterator ->
                if (iterator.hasNext()) {
                    val oldest = iterator.next()
                    unknownSeqCount += oldest.value
                    iterator.remove()
                }
            }
        }
    }

    fun isEmpty(): Boolean = countBySeq.isEmpty() && unknownSeqCount == 0
}

private const val BackgroundUnreadSeqBucketLimit = 256

// task #322: cap on how long the first home paint waits for the concurrent sidebar-order
// fetch before publishing channels alone. Kept short so a slow pinned-order endpoint
// can't delay the channel list; the sidebar still merges on arrival when it's late.
private const val SIDEBAR_ORDER_FIRST_PAINT_WAIT_MS = 300L

// Reconcile loads after realtime unread raced a Home load; past this the local counts stand until the next load.
private const val MAX_REALTIME_UNREAD_RECONCILE_ROUNDS = 3

/** Task #60: a ForegroundRefresh joins a Start/ForegroundRefresh load launched at most this long ago. */
internal const val FOREGROUND_REFRESH_COALESCE_WINDOW_MILLIS = 1_000L

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

internal fun HomeSnapshot.withRealtimeMessages(
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
    // Web projects `hasMention` from the unread messages it holds: an unread one that mentions the user.
    val mentioned = unreadCandidates.any { message -> message.mentions.mentionsUser(currentMemberId) }
    val next =
        copy(
            mentionCount = if (mentioned) maxOf(mentionCount, 1) else mentionCount,
            mentionCountKnown = mentionCountKnown || mentioned,
            lastMessageId = latest.id,
            lastMessageSeq = latest.seq ?: lastMessageSeq,
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

internal fun HomeSnapshot.withConversationUnread(
    conversationId: String,
    unreadCount: Int
): HomeSnapshot {
    var delta = 0
    // Web `clearUnread`: reading a conversation drops its count and its mention flag together.
    fun HomeChannelRow.updated(): HomeChannelRow {
        if (!matchesHomeConversation(conversationId)) return this
        val nextUnread = unreadCount.coerceAtLeast(0)
        delta += nextUnread - this.unreadCount
        return copy(
            unreadCount = nextUnread,
            unreadCountKnown = true,
            firstUnreadMessageId = if (nextUnread > 0) firstUnreadMessageId else null,
            mentionCount = if (nextUnread > 0) mentionCount else 0
        )
    }
    fun HomeDirectMessageRow.updated(): HomeDirectMessageRow {
        if (!matchesHomeConversation(conversationId)) return this
        val nextUnread = unreadCount.coerceAtLeast(0)
        delta += nextUnread - this.unreadCount
        return copy(
            unreadCount = nextUnread,
            unreadCountKnown = true,
            firstUnreadMessageId = if (nextUnread > 0) firstUnreadMessageId else null,
            mentionCount = if (nextUnread > 0) mentionCount else 0
        )
    }
    val nextChannels = channels.map { it.updated() }
    val nextDirectMessages = directMessages.map { it.updated() }
    // A read of a followed thread (its id is the conversation id) clears its part of the parent mark.
    val nextThreads =
        if (unreadCount <= 0) {
            followedThreadUnread - conversationId
        } else {
            followedThreadUnread
        }
    if (delta == 0) {
        return copy(channels = nextChannels, directMessages = nextDirectMessages, followedThreadUnread = nextThreads)
    }
    val nextUnreadTotal = (unreadTotal + delta).coerceAtLeast(0)
    val nextServerUnreadCount = (server.unreadCount + delta).coerceAtLeast(0)
    return copy(
        channels = nextChannels,
        directMessages = nextDirectMessages,
        followedThreadUnread = nextThreads,
        server = server.copy(unreadCount = nextServerUnreadCount),
        unreadTotal = nextUnreadTotal,
        serverUnreadCounts = serverUnreadCounts + (server.id.value to nextServerUnreadCount)
    )
}

private enum class HomeReadStateApplyOutcome(val diagnosticsName: String) {
    Skipped("skipped"),
    AcceptedNeedsReconcile("accepted_needs_reconcile");

    fun requiresReconcile(): Boolean = this == AcceptedNeedsReconcile
}

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> {
    val updated = toMutableList()
    updated[index] = value
    return updated
}

internal fun HomeSnapshot.withPerReceiverReadState(update: PerReceiverReadStateUpdate): HomeSnapshot {
    fun HomeChannelRow.updated(): HomeChannelRow {
        if (!matchesHomeConversation(update.channelId.value)) return this
        val nextReadSeq = update.maxReadSeq ?: return this
        return copy(readStateMaxReadSeq = nextReadSeq)
    }
    fun HomeDirectMessageRow.updated(): HomeDirectMessageRow {
        if (!matchesHomeConversation(update.channelId.value)) return this
        val nextReadSeq = update.maxReadSeq ?: return this
        return copy(readStateMaxReadSeq = nextReadSeq)
    }
    val nextChannels = channels.map { it.updated() }
    val nextDirectMessages = directMessages.map { it.updated() }
    val withRows =
        copy(
            channels = nextChannels,
            directMessages = nextDirectMessages
        )
    // Web `applyReadStateUpdate` -> `applyServerThreadUnread`: a thread scope's server count lowers the mark.
    val serverUnread = update.unreadCount ?: return withRows
    return withRows.withFollowedThreadServerUnread(update.channelId, serverUnread)
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

private fun HomeConversationRow.toChannelActivityMuteSettings(channelId: ChannelId): ChannelActivityMuteSettings =
    ChannelActivityMuteSettings(
        channelId = channelId,
        activityNotificationPreference = activityNotificationPreference
    )

private fun HomeSnapshot.withChannelActivityMute(settings: ChannelActivityMuteSettings): HomeSnapshot =
    copy(
        channels = channels.map { channel ->
            if (channel.messageStreamChannelId() == settings.channelId) {
                channel.copy(
                    activityNotificationPreference = settings.activityNotificationPreference
                )
            } else {
                channel
            }
        },
        directMessages = directMessages.map { directMessage ->
            if (directMessage.messageStreamChannelId() == settings.channelId) {
                directMessage.copy(
                    activityNotificationPreference = settings.activityNotificationPreference
                )
            } else {
                directMessage
            }
        }
    )

enum class HomeServerDrawerLoadStatus {
    Unloaded,
    Loading,
    Cached,
    Refreshing,
    Loaded,
    Empty,
    Unavailable,
    ErrorWithCache,
    ErrorEmpty
}

data class HomeServerDrawerSectionState(
    val server: ServerSummary,
    val content: SharedHomeContentState.Content? = null,
    val normalizedContent: HomeServerDrawerNormalizedContent = HomeServerDrawerNormalizedContent.Empty,
    val status: HomeServerDrawerLoadStatus = HomeServerDrawerLoadStatus.Unloaded,
    val requestGeneration: Long = 0L,
    val errorCode: String? = null
)

data class HomeServerDrawerCommandIntent(
    val generation: Long = 0L,
    val desiredOpen: Boolean = false
)

internal data class HomeServerDrawerCommandTransition(
    val intent: HomeServerDrawerCommandIntent,
    val supersededGeneration: Long?
)

internal fun HomeServerDrawerCommandIntent.transitionTo(desiredOpen: Boolean): HomeServerDrawerCommandTransition =
    if (this.desiredOpen == desiredOpen) {
        HomeServerDrawerCommandTransition(this, null)
    } else {
        HomeServerDrawerCommandTransition(
            intent = HomeServerDrawerCommandIntent(generation = generation + 1L, desiredOpen = desiredOpen),
            supersededGeneration = generation.takeIf { it > 0L }
        )
    }

enum class HomeServerDrawerConversationSection {
    Pinned,
    Channel,
    JointChannel,
    DirectMessage
}

sealed interface HomeServerDrawerNormalizedRow {
    val key: String

    data class SectionHeader(
        val section: HomeServerDrawerConversationSection,
        val count: Int
    ) : HomeServerDrawerNormalizedRow {
        override val key: String = "section:${section.name.lowercase()}"
    }

    data class Conversation(
        val section: HomeServerDrawerConversationSection,
        val conversation: HomeConversationRow
    ) : HomeServerDrawerNormalizedRow {
        override val key: String = "conversation:${section.name.lowercase()}:${conversation.threadId.value}"
    }
}

data class HomeServerDrawerNormalizedContent(
    val rows: List<HomeServerDrawerNormalizedRow>,
    val firstConversationIndex: Int
) {
    companion object {
        val Empty = HomeServerDrawerNormalizedContent(emptyList(), -1)
    }
}

internal fun homeServerDrawerNormalizedContentForSnapshot(
    previousSnapshot: HomeSnapshot?,
    nextSnapshot: HomeSnapshot,
    previousContent: HomeServerDrawerNormalizedContent,
    normalize: (HomeSnapshot) -> HomeServerDrawerNormalizedContent = HomeSnapshot::toHomeServerDrawerNormalizedContent
): HomeServerDrawerNormalizedContent =
    if (previousSnapshot === nextSnapshot) previousContent else normalize(nextSnapshot)

internal fun HomeSnapshot.toHomeServerDrawerNormalizedContent(): HomeServerDrawerNormalizedContent {
    val order = sidebarOrder ?: SettingsServerSidebarOrderSnapshot()
    val visibleDirectMessages =
        directMessages
            .orderedForHomeServerDrawer(order.dmOrder)
            .filter { row -> row.threadId.value !in order.hiddenDmIds || row.unreadCount > 0 }
            .sortedForHomeServerDrawer(RaftSidebarSortMode.fromWireValue(order.dmSortMode))
    val regularChannels =
        channels
            .filter { row -> row.kind != ConversationKind.Joint }
            .orderedForHomeServerDrawer(order.channelOrder)
            .sortedForHomeServerDrawer(RaftSidebarSortMode.fromWireValue(order.channelSortMode))
    val jointChannels =
        channels
            .filter { row -> row.kind == ConversationKind.Joint }
            .orderedForHomeServerDrawer(order.channelOrder)
            .sortedForHomeServerDrawer(RaftSidebarSortMode.fromWireValue(order.channelSortMode))
    val pinned = homeServerDrawerPinnedConversations(channels, directMessages, order)
    val rows =
        buildList {
            fun append(section: HomeServerDrawerConversationSection, conversations: List<HomeConversationRow>) {
                if (conversations.isEmpty()) return
                add(HomeServerDrawerNormalizedRow.SectionHeader(section, conversations.size))
                conversations.forEach { add(HomeServerDrawerNormalizedRow.Conversation(section, it)) }
            }
            append(HomeServerDrawerConversationSection.Pinned, pinned)
            append(HomeServerDrawerConversationSection.Channel, regularChannels)
            append(HomeServerDrawerConversationSection.JointChannel, jointChannels)
            append(HomeServerDrawerConversationSection.DirectMessage, visibleDirectMessages)
        }
    return HomeServerDrawerNormalizedContent(
        rows = rows,
        firstConversationIndex = rows.indexOfFirst { it is HomeServerDrawerNormalizedRow.Conversation }
    )
}

private fun List<HomeConversationRow>.orderedForHomeServerDrawer(order: List<String>): List<HomeConversationRow> {
    if (isEmpty() || order.isEmpty()) return this
    val byId = associateBy { it.threadId.value }
    val ordered = LinkedHashMap<String, HomeConversationRow>()
    order.forEach { id -> byId[id]?.let { row -> ordered[row.threadId.value] = row } }
    forEach { row -> ordered.putIfAbsent(row.threadId.value, row) }
    return ordered.values.toList()
}

private fun List<HomeConversationRow>.sortedForHomeServerDrawer(mode: RaftSidebarSortMode): List<HomeConversationRow> =
    when (mode) {
        RaftSidebarSortMode.Manual -> this
        RaftSidebarSortMode.Recent ->
            sortedWith(
                compareByDescending<HomeConversationRow> {
                    parseRaftTimestampEpochMillisOrNull(it.lastMessageAt) ?: Long.MIN_VALUE
                }.thenBy { it.name.trim().lowercase() }.thenBy { it.threadId.value }
            )
        RaftSidebarSortMode.Az ->
            sortedWith(compareBy<HomeConversationRow> { it.name.trim().lowercase() }.thenBy { it.threadId.value })
    }

private fun homeServerDrawerPinnedConversations(
    channels: List<HomeConversationRow>,
    directMessages: List<HomeConversationRow>,
    order: SettingsServerSidebarOrderSnapshot
): List<HomeConversationRow> {
    val byConversationId = (channels + directMessages).associateBy { it.threadId.value }
    val byMemberId =
        directMessages.mapNotNull { row -> row.presenceMemberId?.value?.let { memberId -> memberId to row } }.toMap()
    return order.typedPinnedOrLegacy().mapNotNull { item ->
        when (item.kind.trim().lowercase()) {
            "channel" -> byConversationId[item.id]
            "agent", "human" -> byMemberId[item.id] ?: byConversationId[item.id]
            else -> null
        }
    }.distinctBy { it.threadId.value }
        .sortedForHomeServerDrawer(RaftSidebarSortMode.fromWireValue(order.pinnedSortMode))
}

data class HomeServerDrawerState(
    val expanded: Boolean = false,
    val expandedServerIds: Set<ServerId> = emptySet(),
    val sections: Map<ServerId, HomeServerDrawerSectionState> = emptyMap(),
    val reorderMode: Boolean = false
)

data class SharedHomeState(
    val content: SharedHomeContentState = SharedHomeContentState.Loading(),
    val serverSwitchState: HomeServerSwitchState = HomeServerSwitchState(),
    val serverSwitcherExpanded: Boolean = false,
    val serverDrawer: HomeServerDrawerState = HomeServerDrawerState(),
    val serverDrawerCommandIntent: HomeServerDrawerCommandIntent = HomeServerDrawerCommandIntent(),
    val selectedConversationId: String? = null,
    val selectedTopAction: SharedHomeTopAction? = null,
    val notifications: List<SharedHomeNotification> = emptyList(),
    val homeRealtimeDirty: Boolean = false,
    val serverUnreadCounts: Map<String, Int> = emptyMap(),
    val serverActivityUnread: HomeServerActivityUnreadProjection = HomeServerActivityUnreadProjection(),
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

/**
 * One admitted `/servers/unread-summary` batch, fenced to the environment and
 * account that requested it. [requestOwner]'s server id is the request witness;
 * each returned value is additionally fenced by its map key when projected.
 */
data class HomeServerActivityUnreadProjection(
    val requestOwner: ReadReceiptOwner? = null,
    val countsByServer: Map<String, Long> = emptyMap(),
    val serverPushMutedByServer: Map<String, Boolean> = emptyMap(),
) {
    fun countFor(expectedOwner: ReadReceiptOwner?): Long? =
        expectedOwner?.takeIf(::hasSameEnvironmentAndAccount)
            ?.serverId
            ?.let(countsByServer::get)

    fun serverPushMutedFor(expectedOwner: ReadReceiptOwner?): Boolean =
        expectedOwner?.takeIf(::hasSameEnvironmentAndAccount)
            ?.serverId
            ?.let(serverPushMutedByServer::get)
            ?: false

    private fun hasSameEnvironmentAndAccount(expectedOwner: ReadReceiptOwner): Boolean =
        requestOwner?.let { admitted ->
            admitted.environmentId == expectedOwner.environmentId &&
                admitted.accountId == expectedOwner.accountId
        } == true
}

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
    data class ToggleServerDrawerSection(val serverId: ServerId) : SharedHomeIntent
    data class RetryServerDrawerSection(val serverId: ServerId) : SharedHomeIntent
    data class SelectConversation(val threadId: String) : SharedHomeIntent
    /**
     * task #299: clear the home-side active-conversation marker when the UI
     * leaves a conversation. Without this the realtime merge keeps treating
     * the last opened conversation as active and permanently suppresses its
     * unread increment (ConversationSummary.withRealtimePreview).
     */
    data object ClearSelectedConversation : SharedHomeIntent
    data class SelectTopAction(val action: SharedHomeTopAction) : SharedHomeIntent
    data class SelectServer(val serverId: String) : SharedHomeIntent
    data class ApplyRealtimeEvent(val serverId: String, val event: RealtimeEvent) : SharedHomeIntent
    data class ApplyBackgroundServerUnreadEvent(val serverId: String, val event: RealtimeEvent) : SharedHomeIntent
    data object RefreshNotifications : SharedHomeIntent
    data class UpsertNotification(val notification: SharedHomeNotification) : SharedHomeIntent
    data class DismissNotification(val id: String) : SharedHomeIntent
    data class ChannelCreated(val channel: HomeChannelRow) : SharedHomeIntent
    data class ChannelProfileUpdated(
        val channelId: ChannelId,
        val name: String,
        val description: String?
    ) : SharedHomeIntent
    data class SetSurfaceVisible(val visible: Boolean) : SharedHomeIntent
    /**
     * #211: pin or unpin a conversation. The store applies an optimistic
     * local update to the in-memory sidebar-order snapshot, then issues a
     * PATCH `/servers/:id/sidebar-order`. On failure the previous snapshot
     * is restored and a transient error is recorded.
     */
    data class TogglePinned(
        val conversationId: String,
        val pinnedKind: String? = null,
        val pinnedId: String? = null
    ) : SharedHomeIntent
    /**
     * #211: change the Pinned section sort mode (Manual / Recent / A-Z).
     * Persists through the same sidebar-order PATCH as pin/unpin.
     */
    data class SetPinnedSortMode(val mode: RaftSidebarSortMode) : SharedHomeIntent
    data class SetChannelSortMode(val mode: RaftSidebarSortMode) : SharedHomeIntent
    /** Web parity: the Joint Channels section has its own sort mode (`jointChannelSortMode`). */
    data class SetJointChannelSortMode(val mode: RaftSidebarSortMode) : SharedHomeIntent
    /**
     * task #512: custom-section CRUD + membership (web channel-sections port).
     * All persist through the sidebar-order PATCH with OCC sectionsVersion;
     * a 409 reloads the sidebar order instead of rolling back.
     */
    data class CreateSidebarSection(
        val name: String,
        val emoji: String?,
        /** Row whose "Move to section > New section…" opened the dialog; moved into the new section. */
        val moveConversationId: String? = null,
        /** sectionOrder id of the section whose header menu opened the dialog; the new section goes before it. */
        val insertBeforeSectionId: String? = null
    ) : SharedHomeIntent
    data class RenameSidebarSection(val sectionId: String, val name: String, val emoji: String?) : SharedHomeIntent
    data class DeleteSidebarSection(val sectionId: String) : SharedHomeIntent
    /** [sectionId] null removes the conversation from its custom section (back to its default one). */
    data class MoveConversationToSection(val conversationId: String, val sectionId: String?) : SharedHomeIntent
    data class SetSidebarSectionSortMode(val sectionId: String, val mode: RaftSidebarSortMode) : SharedHomeIntent
    data class ReorderSidebar(
        val channelOrder: List<String>? = null,
        val jointChannelOrder: List<String>? = null,
        val dmOrder: List<String>? = null,
        val pinnedOrder: List<String>? = null
    ) : SharedHomeIntent
    data class ReorderServers(val serverOrder: List<String>) : SharedHomeIntent
    data class MarkRead(val conversationId: String) : SharedHomeIntent
    data class MarkUnread(val conversationId: String) : SharedHomeIntent
    data class CloseDirectMessage(val conversationId: String) : SharedHomeIntent
    data class ArchiveChannel(val conversationId: String) : SharedHomeIntent
    data class SetChannelActivityMuted(val conversationId: String, val muted: Boolean) : SharedHomeIntent
    data class ApplyPerReceiverReadState(val update: PerReceiverReadStateUpdate) : SharedHomeIntent
    data class ApplyPerReceiverNotificationPrefs(val update: PerReceiverNotificationPrefsUpdate) : SharedHomeIntent
    data class ApplyPerReceiverServerOrder(val update: PerReceiverServerOrderUpdate) : SharedHomeIntent
    data class ApplyPerReceiverPinned(val update: PerReceiverPinnedUpdate) : SharedHomeIntent
    data class ApplyReadReceiptPrediction(val prediction: ReadReceiptPrediction) : SharedHomeIntent
    data class ApplyReadReceiptFailure(val request: ReadReceiptRequest) : SharedHomeIntent
    data class HydrateDirectMessagePreview(
        val serverId: ServerId,
        val channelId: ChannelId,
        val preview: String?,
        val senderName: String?,
        val lastMessageAt: String? = null,
        val lastMessageId: MessageId? = null,
        val accountId: String = "",
    ) : SharedHomeIntent
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
            is SharedHomeIntent.ToggleServerDrawerSection,
            is SharedHomeIntent.RetryServerDrawerSection,
            is SharedHomeIntent.SelectConversation,
            SharedHomeIntent.ClearSelectedConversation,
            is SharedHomeIntent.SelectTopAction,
            is SharedHomeIntent.ApplyRealtimeEvent,
            is SharedHomeIntent.ApplyBackgroundServerUnreadEvent,
            SharedHomeIntent.RefreshNotifications,
            is SharedHomeIntent.UpsertNotification,
            is SharedHomeIntent.DismissNotification,
            is SharedHomeIntent.ChannelCreated,
            is SharedHomeIntent.ChannelProfileUpdated,
            is SharedHomeIntent.SetSurfaceVisible,
            is SharedHomeIntent.TogglePinned,
            is SharedHomeIntent.SetPinnedSortMode,
            is SharedHomeIntent.SetChannelSortMode,
            is SharedHomeIntent.SetJointChannelSortMode,
            is SharedHomeIntent.CreateSidebarSection,
            is SharedHomeIntent.RenameSidebarSection,
            is SharedHomeIntent.DeleteSidebarSection,
            is SharedHomeIntent.MoveConversationToSection,
            is SharedHomeIntent.SetSidebarSectionSortMode,
            is SharedHomeIntent.ReorderSidebar,
            is SharedHomeIntent.ReorderServers,
            is SharedHomeIntent.MarkRead,
            is SharedHomeIntent.MarkUnread,
            is SharedHomeIntent.CloseDirectMessage,
            is SharedHomeIntent.ArchiveChannel,
            is SharedHomeIntent.SetChannelActivityMuted,
            is SharedHomeIntent.ApplyPerReceiverReadState,
            is SharedHomeIntent.ApplyPerReceiverNotificationPrefs,
            is SharedHomeIntent.ApplyPerReceiverServerOrder,
            is SharedHomeIntent.ApplyPerReceiverPinned,
            is SharedHomeIntent.ApplyReadReceiptPrediction,
            is SharedHomeIntent.ApplyReadReceiptFailure,
            is SharedHomeIntent.HydrateDirectMessagePreview -> false
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
            is SharedHomeIntent.ToggleServerDrawerSection,
            is SharedHomeIntent.RetryServerDrawerSection,
            is SharedHomeIntent.SelectConversation,
            SharedHomeIntent.ClearSelectedConversation,
            is SharedHomeIntent.SelectTopAction,
            is SharedHomeIntent.ApplyRealtimeEvent,
            is SharedHomeIntent.ApplyBackgroundServerUnreadEvent,
            SharedHomeIntent.RefreshNotifications,
            is SharedHomeIntent.UpsertNotification,
            is SharedHomeIntent.DismissNotification,
            is SharedHomeIntent.ChannelCreated,
            is SharedHomeIntent.ChannelProfileUpdated,
            is SharedHomeIntent.SetSurfaceVisible,
            is SharedHomeIntent.TogglePinned,
            is SharedHomeIntent.SetPinnedSortMode,
            is SharedHomeIntent.SetChannelSortMode,
            is SharedHomeIntent.SetJointChannelSortMode,
            is SharedHomeIntent.CreateSidebarSection,
            is SharedHomeIntent.RenameSidebarSection,
            is SharedHomeIntent.DeleteSidebarSection,
            is SharedHomeIntent.MoveConversationToSection,
            is SharedHomeIntent.SetSidebarSectionSortMode,
            is SharedHomeIntent.ReorderSidebar,
            is SharedHomeIntent.ReorderServers,
            is SharedHomeIntent.MarkRead,
            is SharedHomeIntent.MarkUnread,
            is SharedHomeIntent.CloseDirectMessage,
            is SharedHomeIntent.ArchiveChannel,
            is SharedHomeIntent.SetChannelActivityMuted,
            is SharedHomeIntent.ApplyPerReceiverReadState,
            is SharedHomeIntent.ApplyPerReceiverNotificationPrefs,
            is SharedHomeIntent.ApplyPerReceiverServerOrder,
            is SharedHomeIntent.ApplyPerReceiverPinned,
            is SharedHomeIntent.ApplyReadReceiptPrediction,
            is SharedHomeIntent.ApplyReadReceiptFailure,
            is SharedHomeIntent.HydrateDirectMessagePreview -> false
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
            is SharedHomeIntent.ToggleServerDrawerSection,
            is SharedHomeIntent.RetryServerDrawerSection,
            is SharedHomeIntent.SelectConversation,
            SharedHomeIntent.ClearSelectedConversation,
            is SharedHomeIntent.SelectTopAction,
            is SharedHomeIntent.ApplyRealtimeEvent,
            is SharedHomeIntent.ApplyBackgroundServerUnreadEvent,
            SharedHomeIntent.RefreshNotifications,
            is SharedHomeIntent.UpsertNotification,
            is SharedHomeIntent.DismissNotification,
            is SharedHomeIntent.ChannelCreated,
            is SharedHomeIntent.ChannelProfileUpdated,
            is SharedHomeIntent.SetSurfaceVisible,
            is SharedHomeIntent.TogglePinned,
            is SharedHomeIntent.SetPinnedSortMode,
            is SharedHomeIntent.SetChannelSortMode,
            is SharedHomeIntent.SetJointChannelSortMode,
            is SharedHomeIntent.CreateSidebarSection,
            is SharedHomeIntent.RenameSidebarSection,
            is SharedHomeIntent.DeleteSidebarSection,
            is SharedHomeIntent.MoveConversationToSection,
            is SharedHomeIntent.SetSidebarSectionSortMode,
            is SharedHomeIntent.ReorderSidebar,
            is SharedHomeIntent.ReorderServers,
            is SharedHomeIntent.MarkRead,
            is SharedHomeIntent.MarkUnread,
            is SharedHomeIntent.CloseDirectMessage,
            is SharedHomeIntent.ArchiveChannel,
            is SharedHomeIntent.SetChannelActivityMuted,
            is SharedHomeIntent.ApplyPerReceiverReadState,
            is SharedHomeIntent.ApplyPerReceiverNotificationPrefs,
            is SharedHomeIntent.ApplyPerReceiverServerOrder,
            is SharedHomeIntent.ApplyPerReceiverPinned,
            is SharedHomeIntent.ApplyReadReceiptPrediction,
            is SharedHomeIntent.ApplyReadReceiptFailure,
            is SharedHomeIntent.HydrateDirectMessagePreview -> false
        }

sealed interface SharedHomeContentState {
    data class Loading(val session: StoredAuthSession? = null) : SharedHomeContentState
    data class Empty(val session: StoredAuthSession) : SharedHomeContentState
    data class Error(
        val code: String,
        val session: StoredAuthSession? = null,
        val serverMessage: String? = null
    ) : SharedHomeContentState
    data class Content(
        val snapshot: HomeSnapshot,
        val session: StoredAuthSession,
        val contentRefreshing: Boolean = false,
        val baseSnapshot: HomeSnapshot = snapshot,
        /** Durable identity cache restored before the first authoritative Home request. */
        val isDurableIdentityCache: Boolean = false,
        /** True until the first network-backed Home refresh is admitted. */
        val needsNetworkRefresh: Boolean = isDurableIdentityCache,
        /**
         * Display-only last-known unread counts and previews keyed by thread id, restored with the
         * durable identity cache (task #166). Never fed into rows, badges or read state; see
         * [displayRowHints].
         */
        val restoredRowHints: Map<String, HomeRowRestoredHint> = emptyMap()
    ) : SharedHomeContentState
}

private fun SharedHomeContentState.asRefreshing(
    session: StoredAuthSession? = (this as? SharedHomeContentState.Content)?.session,
    showRefreshAffordance: Boolean = true
): SharedHomeContentState =
    when (this) {
        is SharedHomeContentState.Content ->
            copy(
                contentRefreshing = showRefreshAffordance,
                needsNetworkRefresh = false
            )
        is SharedHomeContentState.Empty -> SharedHomeContentState.Loading(session ?: this.session)
        is SharedHomeContentState.Loading -> SharedHomeContentState.Loading(session ?: this.session)
        is SharedHomeContentState.Error -> SharedHomeContentState.Loading(session ?: this.session)
    }

private fun SharedHomeContentState.asRefreshFailed(
    code: String,
    serverMessage: String? = null
): SharedHomeContentState =
    when (this) {
        is SharedHomeContentState.Content -> copy(contentRefreshing = false)
        is SharedHomeContentState.Empty ->
            SharedHomeContentState.Error(code, session, serverMessage = serverMessage)
        is SharedHomeContentState.Loading ->
            SharedHomeContentState.Error(code, session, serverMessage = serverMessage)
        is SharedHomeContentState.Error -> copy(code = code, serverMessage = serverMessage)
    }

private fun SharedHomeContentState.sessionOrNull(): StoredAuthSession? =
    when (this) {
        is SharedHomeContentState.Content -> session
        is SharedHomeContentState.Empty -> session
        is SharedHomeContentState.Error -> session
        is SharedHomeContentState.Loading -> session
    }

private fun List<String>.serverOrderDiagnosticsValue(): String =
    joinToString(separator = ",", prefix = "[", postfix = "]")

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
        is PerReceiverReadStateChanged -> "read_state_updated"
        is PerReceiverNotificationPrefsChanged -> "notification_prefs_updated"
        is PerReceiverPinnedChanged -> "pinned_updated"
        is build.raft.app.model.ComputerRestartDone -> "computer_restart_done"
        else -> "realtime"
    }

private fun Throwable.safeHomeStoreDiagnosticsType(): String =
    this::class.simpleName?.takeIf { it.isNotBlank() } ?: "Throwable"

/**
 * Home state for a pushed page, seeded with the store's CURRENT value (data-layer plan: pages never
 * start from an empty default). Collect it with `collectAsState()` so the first frame already has
 * the pin state and the conversation rows the store holds; a page without a Home store gets a fixed
 * empty state.
 */
internal fun SharedHomeStore?.homeStateForPage(): StateFlow<SharedHomeState> =
    this?.state ?: MutableStateFlow(SharedHomeState())
