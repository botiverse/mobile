package build.raft.app.data

import build.raft.app.data.realtime.RaftRealtimeConnectionCauses
import build.raft.app.model.Message
import build.raft.app.model.MessageNew
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.RealtimeEvent
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val SharedHomeShellDestinationSearch = "search"
const val SharedHomeShellDestinationActivity = "activity"
const val SharedHomeShellDestinationSaved = "saved"

data class SharedHomeShellState(
    val home: SharedHomeState = SharedHomeState(),
    val activity: SharedActivityState = SharedActivityState(),
    val searchSaved: SharedHomeSearchSavedState = SharedHomeSearchSavedState(),
    val destination: String? = null,
    val selectedTab: String? = null,
    val selectedConversationId: String? = null,
    val primarySurfaceVisible: Boolean = false
) {
    val notifications: List<SharedHomeNotification>
        get() = home.notifications

    val isActivityVisible: Boolean
        get() = destination == SharedHomeShellDestinationActivity || selectedTab == "activity"
}

sealed interface SharedHomeShellIntent {
    data object Start : SharedHomeShellIntent
    data object ForegroundRefresh : SharedHomeShellIntent
    data class SetSurfaceVisible(val visible: Boolean) : SharedHomeShellIntent
    data object OpenActivity : SharedHomeShellIntent
    data object OpenSaved : SharedHomeShellIntent
    data object OpenSearch : SharedHomeShellIntent
    data object CloseSecondaryDestination : SharedHomeShellIntent
    data class SetSelectedTab(val tab: String) : SharedHomeShellIntent
    data class SetSelectedConversation(val conversationId: String?) : SharedHomeShellIntent
    data class SelectTopAction(val action: SharedHomeTopAction) : SharedHomeShellIntent
    data class DismissNotification(val id: String) : SharedHomeShellIntent
    data class ApplyRealtimeEvent(val serverId: String, val event: RealtimeEvent) : SharedHomeShellIntent
    data class ApplyBackgroundServerUnreadEvent(val serverId: String, val event: RealtimeEvent) : SharedHomeShellIntent
    data class Home(val intent: SharedHomeIntent) : SharedHomeShellIntent
    data class Activity(val intent: SharedActivityIntent) : SharedHomeShellIntent
    data class SearchSaved(val intent: SharedHomeSearchSavedIntent) : SharedHomeShellIntent
}

class SharedHomeShellStore(
    homeState: StateFlow<SharedHomeState>,
    activityState: StateFlow<SharedActivityState>,
    searchSavedState: StateFlow<SharedHomeSearchSavedState>,
    private val dispatchHome: (SharedHomeIntent) -> Unit,
    /** Visibility-driven Home refresh; may join a load the same foreground transition launched. */
    private val dispatchHomeVisibleRefresh: () -> Unit = { dispatchHome(SharedHomeIntent.ForegroundRefresh) },
    private val acceptHome: suspend (SharedHomeIntent) -> Unit = { intent -> dispatchHome(intent) },
    private val dispatchActivity: (SharedActivityIntent) -> Unit,
    private val acceptActivityRealtime: suspend (SharedActivityIntent) -> Unit = { intent -> dispatchActivity(intent) },
    private val dispatchSearchSaved: (SharedHomeSearchSavedIntent) -> Unit,
    private val refreshActivitySummary: (serverId: String?, reason: String) -> Unit = { _, _ ->
        dispatchActivity(SharedActivityIntent.Refresh)
    },
    private val resetActivity: () -> Unit = {},
    private val diagnosticsLogger: (String) -> Unit = {},
    private val scope: CoroutineScope,
    /** As [SharedHomeStore]'s: the selected conversation skips Activity unread only while it is seen. */
    private val selectedConversationSeen: () -> Boolean = { RaftHostInUse.isInUse },
    /** Process-wide freshness signal (#wg-mobile-feedback:#274 Phase 2). */
    private val freshness: Flow<RaftFreshnessEvent> = RaftFreshnessSignal.events,
    private val freshnessNowMillis: () -> Long = ::raftForegroundGateNowMillis,
    private val freshnessLiveConnectionToken: (serverId: String) -> Long? =
        RaftRealtimeConnectionCauses::liveConnectionToken,
    /**
     * Outcome-reporting refresh hooks used by [freshnessGate] (`trackOutcome = true`) so a failed
     * catch-up never consumes the 10 s coalesce window (#wg-mobile-feedback:#273/#274).
     */
    private val refreshHomeForFreshness: (suspend () -> Boolean)? = null,
    private val refreshActivityForFreshness: (suspend () -> Boolean)? = null,
    /**
     * Warm foreground return while Home is not the visible destination (Activity / Saved / Search):
     * trigger a throttled `/servers/unread-summary` refresh on multi-server accounts so other
     * servers' reads on Web/Desktop clear `serverActivityUnread[otherServer]`.
     */
    private val refreshHiddenServerActivityUnread: () -> Unit = {},
) {
    private val freshnessLock = SynchronizedObject()
    private val freshnessGate =
        RaftFreshnessGate(
            foreground = false,
            reconnect = true,
            nowMillis = freshnessNowMillis,
            trackOutcome = true,
            liveConnectionToken = freshnessLiveConnectionToken,
        )
    private var freshnessObserveJob: Job? = null
    private var freshnessCatchUpJob: Job? = null
    private var freshnessServerId: String? = null
    private var freshnessHostVisible: Boolean = false
    private var freshnessCatchUpInFlight: Boolean = false
    private var onFreshnessCatchUpTriggered: (
        trigger: RaftFreshnessTrigger,
        serverId: String,
        tab: String,
        destination: String?,
    ) -> Unit = { _, _, _, _ -> }

    private var observedHomeServerId: String? = homeState.value.activeHomeServerId()
    private var pendingSavedSummaryRefreshServerId: String? = null

    private val _state =
        MutableStateFlow(
            SharedHomeShellState(
                home = homeState.value,
                activity = activityState.value,
                searchSaved = searchSavedState.value
            )
        )
    val state: StateFlow<SharedHomeShellState> = _state.asStateFlow()

    private val jobs =
        listOf(
            scope.launch {
                homeState.collect { home ->
                    val previousServerId = observedHomeServerId
                    val nextServerId = home.activeHomeServerId()
                    val summariesCanRefresh = home.canRefreshHomeBadgeSummaries()
                    observedHomeServerId = nextServerId ?: previousServerId
                    _state.value = _state.value.copy(home = home)
                    if (nextServerId != null && previousServerId != nextServerId) {
                        if (previousServerId != null) {
                            // The Activity store shows the new server's cached feed and refetches
                            // it on every switch, including back to a server seen before.
                            dispatchActivity(SharedActivityIntent.ServerSelected(nextServerId))
                            dispatchSearchSaved(SharedHomeSearchSavedIntent.ClearSavedSummary)
                            refreshActivitySummary(nextServerId, "server_switch")
                        }
                        if (summariesCanRefresh) {
                            pendingSavedSummaryRefreshServerId = null
                            if (previousServerId == null) {
                                refreshBadgeSummaries(nextServerId, "server_ready")
                            } else {
                                refreshSavedSummary()
                            }
                        } else {
                            pendingSavedSummaryRefreshServerId = nextServerId
                        }
                    } else if (
                        nextServerId != null &&
                            pendingSavedSummaryRefreshServerId == nextServerId &&
                            summariesCanRefresh
                    ) {
                        pendingSavedSummaryRefreshServerId = null
                        refreshSavedSummary()
                    }
                }
            },
            scope.launch {
                activityState.collect { activity ->
                    _state.value = _state.value.copy(activity = activity)
                }
            },
            scope.launch {
                searchSavedState.collect { searchSaved ->
                    _state.value = _state.value.copy(searchSaved = searchSaved)
                }
            }
        )

    fun dispatch(intent: SharedHomeShellIntent) {
        if (intent.shouldLogHomeShellDispatch()) {
            diagnosticsLogger(
                "home_shell_dispatch intent=${intent.toSharedHomeShellIntentDiagnosticsLabel()} " +
                    "destination=${_state.value.destination ?: "_"} surfaceVisible=${_state.value.primarySurfaceVisible} " +
                    "selectedConversation=${_state.value.selectedConversationId ?: "_"} " +
                    "home=${_state.value.home.toSharedHomeShellHomeSummary()}"
            )
        }
        when (intent) {
            SharedHomeShellIntent.Start -> {
                diagnosticsLogger("home_shell_forward_home intent=hydrate:start source=shell_start")
                dispatchHome(SharedHomeIntent.Start)
            }
            SharedHomeShellIntent.ForegroundRefresh -> refreshVisibleHomeDestination()
            is SharedHomeShellIntent.SetSurfaceVisible -> setSurfaceVisible(intent.visible)
            SharedHomeShellIntent.OpenActivity -> openDestination(SharedHomeShellDestinationActivity)
            SharedHomeShellIntent.OpenSaved -> openDestination(SharedHomeShellDestinationSaved)
            SharedHomeShellIntent.OpenSearch -> openDestination(SharedHomeShellDestinationSearch)
            SharedHomeShellIntent.CloseSecondaryDestination -> {
                _state.value = _state.value.copy(destination = null)
                recheckFreshnessVisibility()
            }
            is SharedHomeShellIntent.SetSelectedTab -> setSelectedTab(intent.tab)
            is SharedHomeShellIntent.SetSelectedConversation -> {
                _state.value = _state.value.copy(selectedConversationId = intent.conversationId)
                // task #299: the home store keeps its own selectedConversationId
                // which the realtime merge uses to suppress unread increments for
                // the "active" conversation. Leaving a conversation only clears
                // the shell field, so forward the clear or the last opened
                // conversation would never accrue unread again.
                if (intent.conversationId == null) {
                    dispatchHome(SharedHomeIntent.ClearSelectedConversation)
                }
            }
            is SharedHomeShellIntent.SelectTopAction ->
                dispatchHome(SharedHomeIntent.SelectTopAction(intent.action))
            is SharedHomeShellIntent.DismissNotification ->
                dispatchHome(SharedHomeIntent.DismissNotification(intent.id))
            is SharedHomeShellIntent.ApplyRealtimeEvent -> applyRealtimeEvent(intent.serverId, intent.event)
            is SharedHomeShellIntent.ApplyBackgroundServerUnreadEvent ->
                dispatchHome(SharedHomeIntent.ApplyBackgroundServerUnreadEvent(intent.serverId, intent.event))
            is SharedHomeShellIntent.Home -> dispatchHomeIntent(intent.intent)
            is SharedHomeShellIntent.Activity -> dispatchActivity(intent.intent)
            is SharedHomeShellIntent.SearchSaved -> dispatchSearchSaved(intent.intent)
        }
    }

    fun reset() {
        resetActivity()
        synchronized(freshnessLock) {
            freshnessCatchUpJob?.cancel()
            freshnessCatchUpJob = null
            freshnessCatchUpInFlight = false
            freshnessServerId = null
            freshnessHostVisible = false
            freshnessGate.reset()
        }
        _state.value =
            _state.value.copy(
                destination = null,
                selectedTab = null,
                selectedConversationId = null,
                primarySurfaceVisible = false
            )
    }

    /**
     * Binds the shell's Home / DMs / Activity routes to the store-owned outcome-tracked
     * [RaftFreshnessGate] (#wg-mobile-feedback:#274 Phase 2). Lazily collects [freshness] while
     * active; triggers while hidden or on another tab (e.g. More, Tasks) are held and drained as
     * soon as an eligible route is visible again.
     */
    fun setFreshnessScope(
        serverId: String?,
        visible: Boolean,
        onTriggered: (
            trigger: RaftFreshnessTrigger,
            serverId: String,
            tab: String,
            destination: String?,
        ) -> Unit = { _, _, _, _ -> },
    ) {
        val normalizedServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
        val drained: Pair<RaftFreshnessTrigger, String>? =
            synchronized(freshnessLock) {
                val previousServerId = freshnessServerId
                val wasRouteVisible = isFreshnessRouteVisibleLocked()
                freshnessServerId = normalizedServerId
                freshnessHostVisible = visible
                onFreshnessCatchUpTriggered = onTriggered
                if (freshnessObserveJob?.isActive != true) {
                    freshnessObserveJob =
                        scope.launch {
                            freshness.collect { event ->
                                onFreshnessEvent(event)
                            }
                        }
                }
                if (previousServerId != null && normalizedServerId != previousServerId && !freshnessCatchUpInFlight) {
                    freshnessGate.reset()
                }
                val nowRouteVisible = isFreshnessRouteVisibleLocked()
                if (!wasRouteVisible && nowRouteVisible && normalizedServerId != null) {
                    freshnessGate.onVisibilityChanged(visible = true, serverId = normalizedServerId)?.let { trigger ->
                        freshnessCatchUpInFlight = true
                        trigger to normalizedServerId
                    }
                } else {
                    null
                }
            }
        if (drained != null) {
            startFreshnessCatchUp(drained.first, drained.second)
        }
    }

    fun stopFreshnessObserver() {
        synchronized(freshnessLock) {
            freshnessObserveJob?.cancel()
            freshnessObserveJob = null
            freshnessHostVisible = false
            freshnessServerId = null
            onFreshnessCatchUpTriggered = { _, _, _, _ -> }
            if (!freshnessCatchUpInFlight) {
                freshnessGate.reset()
            }
        }
    }

    private fun isFreshnessRouteVisibleLocked(): Boolean {
        if (!freshnessHostVisible || freshnessServerId == null) return false
        val current = _state.value
        return isHomeShellFreshnessRouteEligible(current.selectedTab, current.destination)
    }

    private fun recheckFreshnessVisibility() {
        val drained: Pair<RaftFreshnessTrigger, String>? =
            synchronized(freshnessLock) {
                val serverId = freshnessServerId ?: return
                if (!isFreshnessRouteVisibleLocked()) return
                freshnessGate.onVisibilityChanged(visible = true, serverId = serverId)?.let { trigger ->
                    freshnessCatchUpInFlight = true
                    trigger to serverId
                }
            }
        if (drained != null) {
            startFreshnessCatchUp(drained.first, drained.second)
        }
    }

    private fun onFreshnessEvent(event: RaftFreshnessEvent) {
        val fired: Pair<RaftFreshnessTrigger, String>? =
            synchronized(freshnessLock) {
                val serverId = freshnessServerId ?: return
                val trigger = freshnessGate.onEvent(event, serverId, visible = isFreshnessRouteVisibleLocked()) ?: return
                freshnessCatchUpInFlight = true
                trigger to serverId
            }
        if (fired != null) {
            startFreshnessCatchUp(fired.first, fired.second)
        }
    }

    private fun startFreshnessCatchUp(trigger: RaftFreshnessTrigger, serverId: String) {
        val current = _state.value
        val tab = normalizedHomeShellFreshnessTab(current.selectedTab)
        val destination = current.destination
        val callback = synchronized(freshnessLock) { onFreshnessCatchUpTriggered }
        callback(trigger, serverId, tab, destination)
        freshnessCatchUpJob =
            scope.launch {
                var succeeded = false
                try {
                    succeeded = runFreshnessCatchUp(tab, destination)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    succeeded = false
                } finally {
                    completeFreshnessCatchUp(succeeded)
                }
            }
    }

    private suspend fun runFreshnessCatchUp(tab: String, destination: String?): Boolean {
        val refreshActivity =
            destination == SharedHomeShellDestinationActivity ||
                (destination == null && tab == "activity")
        return if (refreshActivity) {
            refreshActivityForFreshness?.invoke() ?: run {
                dispatchActivity(SharedActivityIntent.Refresh)
                true
            }
        } else {
            val ok =
                refreshHomeForFreshness?.invoke() ?: run {
                    dispatchHomeVisibleRefresh()
                    true
                }
            refreshSavedSummary()
            ok
        }
    }

    private fun completeFreshnessCatchUp(succeeded: Boolean) {
        val followUp: Pair<RaftFreshnessTrigger, String>? =
            synchronized(freshnessLock) {
                if (!freshnessCatchUpInFlight) return
                freshnessCatchUpInFlight = false
                val nextTrigger = freshnessGate.onCatchUpCompleted(succeeded = succeeded, visible = isFreshnessRouteVisibleLocked())
                val serverId = freshnessServerId
                if (serverId == null && freshnessObserveJob == null) {
                    freshnessGate.reset()
                    null
                } else if (nextTrigger != null && serverId != null) {
                    freshnessCatchUpInFlight = true
                    nextTrigger to serverId
                } else {
                    null
                }
            }
        if (followUp != null) {
            startFreshnessCatchUp(followUp.first, followUp.second)
        }
    }

    suspend fun acceptRealtimeEvent(serverId: String, event: RealtimeEvent) {
        acceptHome(SharedHomeIntent.ApplyRealtimeEvent(serverId, event))
        val current = _state.value
        if (current.isActivityVisible) {
            acceptActivityRealtime(
                SharedActivityIntent.ApplyRealtimeEvent(
                    event = event,
                    allowLocalPatch = current.home.allowsLocalActivityPatch(event),
                    activeConversationId = current.selectedConversationId?.takeIf { selectedConversationSeen() },
                    activityMuted = current.home.isPastActivityMute(event)
                )
            )
        } else {
            // Off the primary surface too: a list that owns the badge (`inbox_row_position_v1`)
            // counts it; any other drops it there, as before (the store reads primarySurfaceVisible).
            acceptActivityRealtime(
                SharedActivityIntent.ApplyRealtimeSummaryEvent(
                    event = event,
                    activeConversationId = current.selectedConversationId?.takeIf { selectedConversationSeen() },
                    activityMuted = current.home.isPastActivityMute(event),
                    primarySurfaceVisible = event is PerReceiverReadStateChanged || current.primarySurfaceVisible
                )
            )
        }
    }

    suspend fun acceptBackgroundServerUnreadEvent(serverId: String, event: RealtimeEvent) {
        acceptHome(SharedHomeIntent.ApplyBackgroundServerUnreadEvent(serverId, event))
    }

    fun clear() {
        synchronized(freshnessLock) {
            freshnessObserveJob?.cancel()
            freshnessObserveJob = null
            freshnessCatchUpJob?.cancel()
            freshnessCatchUpJob = null
            freshnessCatchUpInFlight = false
            freshnessHostVisible = false
            freshnessServerId = null
            onFreshnessCatchUpTriggered = { _, _, _, _ -> }
            freshnessGate.reset()
        }
        jobs.forEach(Job::cancel)
    }

    private fun setSelectedTab(tab: String) {
        val previousTab = _state.value.selectedTab
        _state.value = _state.value.copy(selectedTab = tab)
        if (tab == "activity" && previousTab != "activity") {
            dispatchActivity(SharedActivityIntent.Refresh)
        }
        recheckFreshnessVisibility()
    }

    private fun setSurfaceVisible(visible: Boolean) {
        _state.value = _state.value.copy(primarySurfaceVisible = visible)
        diagnosticsLogger("home_shell_forward_home intent=SetSurfaceVisible:$visible source=surface_visible")
        dispatchHome(SharedHomeIntent.SetSurfaceVisible(visible))
    }

    private fun openDestination(destination: String) {
        val hadSelection = _state.value.selectedConversationId != null
        _state.value =
            _state.value.copy(
                destination = destination,
                selectedConversationId = null
            )
        if (hadSelection) {
            dispatchHome(SharedHomeIntent.ClearSelectedConversation)
        }
        when (destination) {
            SharedHomeShellDestinationActivity -> dispatchActivity(SharedActivityIntent.Refresh)
            SharedHomeShellDestinationSaved -> dispatchSearchSaved(SharedHomeSearchSavedIntent.OpenSaved)
            SharedHomeShellDestinationSearch -> dispatchSearchSaved(SharedHomeSearchSavedIntent.OpenSearch)
        }
        recheckFreshnessVisibility()
    }

    private fun refreshVisibleHomeDestination() {
        when (val destination = _state.value.destination) {
            SharedHomeShellDestinationActivity -> {
                dispatchActivity(SharedActivityIntent.Refresh)
                refreshHiddenServerActivityUnread()
            }
            SharedHomeShellDestinationSaved -> {
                dispatchSearchSaved(SharedHomeSearchSavedIntent.RefreshSaved)
                refreshHiddenServerActivityUnread()
            }
            SharedHomeShellDestinationSearch -> {
                dispatchSearchSaved(SharedHomeSearchSavedIntent.RefreshSearch)
                refreshHiddenServerActivityUnread()
            }
            null ->
                if (_state.value.selectedTab == "activity") {
                    dispatchActivity(SharedActivityIntent.Refresh)
                    refreshHiddenServerActivityUnread()
                } else {
                    dispatchHomeVisibleRefresh()
                    refreshSavedSummary()
                }
            else -> {
                dispatchHomeVisibleRefresh()
                refreshSavedSummary()
            }
        }
    }

    private fun applyRealtimeEvent(serverId: String, event: RealtimeEvent) {
        dispatchHome(SharedHomeIntent.ApplyRealtimeEvent(serverId, event))
        val current = _state.value
        if (current.isActivityVisible) {
            dispatchActivity(
                SharedActivityIntent.ApplyRealtimeEvent(
                    event = event,
                    allowLocalPatch = current.home.allowsLocalActivityPatch(event),
                    activeConversationId = current.selectedConversationId?.takeIf { selectedConversationSeen() },
                    activityMuted = current.home.isPastActivityMute(event)
                )
            )
        } else {
            dispatchActivity(
                SharedActivityIntent.ApplyRealtimeSummaryEvent(
                    event = event,
                    activeConversationId = current.selectedConversationId?.takeIf { selectedConversationSeen() },
                    activityMuted = current.home.isPastActivityMute(event),
                    primarySurfaceVisible = event is PerReceiverReadStateChanged || current.primarySurfaceVisible
                )
            )
        }
    }

    private fun refreshSavedSummary() {
        dispatchSearchSaved(SharedHomeSearchSavedIntent.RefreshSaved)
    }

    private fun refreshBadgeSummaries(serverId: String?, reason: String) {
        refreshActivitySummary(serverId, reason)
        refreshSavedSummary()
    }

    private fun dispatchHomeIntent(intent: SharedHomeIntent) {
        diagnosticsLogger(
            "home_shell_forward_home intent=${intent.toSharedHomeShellHomeIntentDiagnosticsLabel()} " +
                "source=home_wrapper"
        )
        dispatchHome(intent)
        if (intent == SharedHomeIntent.Refresh) {
            refreshBadgeSummaries(observedHomeServerId, "manual_home_refresh")
        }
    }
}

private fun normalizedHomeShellFreshnessTab(selectedTab: String?): String =
    selectedTab?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "home"

private fun isHomeShellFreshnessRouteEligible(selectedTab: String?, destination: String?): Boolean =
    when (normalizedHomeShellFreshnessTab(selectedTab)) {
        "home" -> destination == null || destination == SharedHomeShellDestinationActivity
        "dms", "activity" -> true
        else -> false
    }

private fun SharedHomeShellIntent.shouldLogHomeShellDispatch(): Boolean =
    when (this) {
        SharedHomeShellIntent.Start,
        SharedHomeShellIntent.ForegroundRefresh,
        is SharedHomeShellIntent.SetSurfaceVisible,
        SharedHomeShellIntent.OpenActivity,
        SharedHomeShellIntent.OpenSaved,
        SharedHomeShellIntent.OpenSearch,
        SharedHomeShellIntent.CloseSecondaryDestination,
        is SharedHomeShellIntent.SetSelectedTab,
        is SharedHomeShellIntent.SetSelectedConversation,
        is SharedHomeShellIntent.SelectTopAction,
        is SharedHomeShellIntent.DismissNotification -> true
        is SharedHomeShellIntent.ApplyRealtimeEvent,
        is SharedHomeShellIntent.ApplyBackgroundServerUnreadEvent,
        is SharedHomeShellIntent.Home,
        is SharedHomeShellIntent.Activity,
        is SharedHomeShellIntent.SearchSaved -> false
    }

private fun SharedHomeState.allowsLocalActivityPatch(event: RealtimeEvent): Boolean =
    when (event) {
        is MessageNew -> allowsLocalActivityPatch(event.message)
        else -> true
    }

private fun SharedHomeState.allowsLocalActivityPatch(message: Message): Boolean {
    val conversation = activityConversationOf(message) ?: return false
    if (!conversation.activityMuted) return true
    val muteFromSeq = conversation.muteFromSeq ?: return false
    val messageSeq = message.seq ?: return false
    return messageSeq < muteFromSeq
}

/**
 * The message is known to be past its conversation's Activity mute (web `isMessageInMutedActivityRange`):
 * muted with no start seq, or at or after it. Unknown (no Home list, conversation not listed, no seq
 * to place it) is not muted: [allowsLocalActivityPatch] is then false and the list is re-read.
 */
private fun SharedHomeState.isPastActivityMute(event: RealtimeEvent): Boolean {
    val message = (event as? MessageNew)?.message ?: return false
    val conversation = activityConversationOf(message) ?: return false
    if (!conversation.activityMuted) return false
    val muteFromSeq = conversation.muteFromSeq ?: return true
    val messageSeq = message.seq ?: return false
    return messageSeq >= muteFromSeq
}

private fun SharedHomeState.activityConversationOf(message: Message) =
    (content as? SharedHomeContentState.Content)?.snapshot?.let { snapshot ->
        val sourceChannelId =
            message.conversationContext?.parentChannelId?.value
                ?: message.channelId?.value
                ?: return@let null
        (snapshot.channels.asSequence() + snapshot.directMessages.asSequence())
            .firstOrNull { row ->
                row.channelId?.value == sourceChannelId || row.threadId.value == sourceChannelId
            }
    }

private fun SharedHomeShellIntent.toSharedHomeShellIntentDiagnosticsLabel(): String =
    when (this) {
        SharedHomeShellIntent.Start -> "Start"
        SharedHomeShellIntent.ForegroundRefresh -> "ForegroundRefresh"
        is SharedHomeShellIntent.SetSurfaceVisible -> "SetSurfaceVisible:$visible"
        SharedHomeShellIntent.OpenActivity -> "OpenActivity"
        SharedHomeShellIntent.OpenSaved -> "OpenSaved"
        SharedHomeShellIntent.OpenSearch -> "OpenSearch"
        SharedHomeShellIntent.CloseSecondaryDestination -> "CloseSecondaryDestination"
        is SharedHomeShellIntent.SetSelectedTab -> "SetSelectedTab:$tab"
        is SharedHomeShellIntent.SetSelectedConversation -> "SetSelectedConversation:${conversationId ?: "_"}"
        is SharedHomeShellIntent.SelectTopAction -> "SelectTopAction:$action"
        is SharedHomeShellIntent.DismissNotification -> "DismissNotification:$id"
        is SharedHomeShellIntent.ApplyRealtimeEvent -> "ApplyRealtimeEvent:$serverId"
        is SharedHomeShellIntent.ApplyBackgroundServerUnreadEvent -> "ApplyBackgroundServerUnreadEvent:$serverId"
        is SharedHomeShellIntent.Home -> "Home:${intent.toSharedHomeShellHomeIntentDiagnosticsLabel()}"
        is SharedHomeShellIntent.Activity -> "Activity"
        is SharedHomeShellIntent.SearchSaved -> "SearchSaved"
    }

private fun SharedHomeIntent.toSharedHomeShellHomeIntentDiagnosticsLabel(): String =
    when (this) {
        SharedHomeIntent.Start -> "hydrate:start"
        SharedHomeIntent.Refresh -> "hydrate:refresh"
        SharedHomeIntent.ForegroundRefresh -> "hydrate:foreground"
        SharedHomeIntent.ToggleServerSwitcher -> "patch:server-switcher"
        SharedHomeIntent.DismissServerSwitcher -> "patch:server-switcher-dismiss"
        is SharedHomeIntent.ToggleServerDrawerSection -> "patch:server-drawer-toggle:${serverId.value}"
        is SharedHomeIntent.RetryServerDrawerSection -> "hydrate:server-drawer-retry:${serverId.value}"
        is SharedHomeIntent.SelectConversation -> "patch:select:$threadId"
        SharedHomeIntent.ClearSelectedConversation -> "patch:select:_"
        is SharedHomeIntent.SelectTopAction -> "patch:top-action:$action"
        is SharedHomeIntent.SelectServer -> "hydrate:server:$serverId"
        is SharedHomeIntent.ApplyRealtimeEvent -> "patch:realtime:$serverId"
        is SharedHomeIntent.ApplyBackgroundServerUnreadEvent -> "patch:background-unread:$serverId"
        SharedHomeIntent.RefreshNotifications -> "patch:notifications-refresh"
        is SharedHomeIntent.UpsertNotification -> "patch:notification:${notification.id}"
        is SharedHomeIntent.DismissNotification -> "patch:notification-dismiss:$id"
        is SharedHomeIntent.ChannelCreated -> "ensure:${channel.threadId.value}"
        is SharedHomeIntent.ChannelJoined -> "patch:joined:${channelId.value}"
        is SharedHomeIntent.ChannelMembershipEnded -> "remove:ended:${channelId.value}"
        is SharedHomeIntent.ChannelProfileUpdated -> "patch:channel-profile:${channelId.value}"
        is SharedHomeIntent.SetSurfaceVisible -> "patch:surface-visible:$visible"
        is SharedHomeIntent.TogglePinned -> "patch:pin:$conversationId"
        is SharedHomeIntent.CreateSidebarSection -> "patch:section-create:$name"
        is SharedHomeIntent.RenameSidebarSection -> "patch:section-rename:$sectionId"
        is SharedHomeIntent.DeleteSidebarSection -> "patch:section-delete:$sectionId"
        is SharedHomeIntent.MoveConversationToSection -> "patch:section-move:$conversationId"
        is SharedHomeIntent.SetSidebarSectionSortMode -> "patch:section-sort:$sectionId"
        is SharedHomeIntent.SetPinnedSortMode -> "patch:pinned-sort:$mode"
        is SharedHomeIntent.SetChannelSortMode -> "patch:channel-sort:$mode"
        is SharedHomeIntent.SetJointChannelSortMode -> "patch:joint-channel-sort:$mode"
        is SharedHomeIntent.ReorderSidebar -> "patch:sidebar-order:${channelOrder?.size ?: 0}/${jointChannelOrder?.size ?: 0}/${dmOrder?.size ?: 0}/${pinnedOrder?.size ?: 0}"
        is SharedHomeIntent.ReorderServers -> "patch:server-order:${serverOrder.size}"
        SharedHomeIntent.SyncSessionServers -> "patch:session-servers"
        is SharedHomeIntent.MarkRead -> "patch:read:$conversationId"
        is SharedHomeIntent.MarkUnread -> "patch:unread:$conversationId"
        is SharedHomeIntent.MarkConversationReadAll -> "patch:read-all:$conversationId"
        is SharedHomeIntent.ProjectThreadUnfollow -> "patch:thread-unfollow:$threadChannelId:$phase"
        is SharedHomeIntent.NoteLoungeThreadFollowed -> "patch:lounge-thread-followed:${parentChannelId ?: threadChannelId ?: "_"}"
        is SharedHomeIntent.MarkFollowedThreadRead -> "patch:thread-read:$threadChannelId"
        is SharedHomeIntent.CloseDirectMessage -> "remove:dm:$conversationId"
        is SharedHomeIntent.ReopenClosedConversation -> "patch:reopen:$conversationId"
        is SharedHomeIntent.OwnMessagesSent -> "patch:own-sent:$serverId:${channelIds.size}"
        is SharedHomeIntent.ArchiveChannel -> "remove:archive:$conversationId"
        is SharedHomeIntent.LeaveChannel -> "remove:leave:$conversationId"
        is SharedHomeIntent.SetChannelActivityMuted -> "mute:$conversationId:$muted"
        is SharedHomeIntent.ApplyPerReceiverReadState -> "read_state:${update.serverId.value}:${update.channelId.value}:${update.version}"
        is SharedHomeIntent.ApplyPerReceiverNotificationPrefs -> "notification_prefs:${update.serverId.value}:${update.channelId.value}:${update.version}"
        is SharedHomeIntent.ApplyPerReceiverServerOrder -> "server_order:${update.serverId.value}:${update.version}"
        is SharedHomeIntent.ApplyPerReceiverPinned -> "pinned:${update.serverId.value}:${update.version}"
        is SharedHomeIntent.ApplyReadReceiptPrediction -> "read_prediction:${prediction.channelId.value}:${prediction.revision}"
        is SharedHomeIntent.ApplyReadReceiptFailure -> "read_failure:${request.channelId.value}:${request.revision}"
        is SharedHomeIntent.HydrateDirectMessagePreview -> "hydrate_dm_preview:${channelId.value}"
    }

private fun SharedHomeState.toSharedHomeShellHomeSummary(): String =
    when (val current = content) {
        is SharedHomeContentState.Loading -> "Loading(session=${current.session?.currentServerId ?: "_"})"
        is SharedHomeContentState.Empty -> "Empty(session=${current.session.currentServerId ?: "_"})"
        is SharedHomeContentState.Error -> "Error(${current.code},session=${current.session?.currentServerId ?: "_"})"
        is SharedHomeContentState.Content ->
            "Content(server=${current.snapshot.server.id.value},refreshing=${current.contentRefreshing}," +
                "channels=${current.snapshot.channels.size},dms=${current.snapshot.directMessages.size})"
    } + ":dirty=$homeRealtimeDirty"

private fun SharedHomeState.activeHomeServerId(): String? =
    when (val current = content) {
        is SharedHomeContentState.Content -> current.snapshot.server.id.value
        is SharedHomeContentState.Loading -> current.session?.currentServerId
        is SharedHomeContentState.Empty -> current.session.currentServerId
        is SharedHomeContentState.Error -> current.session?.currentServerId
    }

private fun SharedHomeState.canRefreshHomeBadgeSummaries(): Boolean =
    when (val current = content) {
        is SharedHomeContentState.Content -> !current.contentRefreshing
        is SharedHomeContentState.Empty -> true
        is SharedHomeContentState.Loading,
        is SharedHomeContentState.Error -> false
    }
