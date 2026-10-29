package build.raft.app.data

import build.raft.app.model.Message
import build.raft.app.model.MessageNew
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.RealtimeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    private val scope: CoroutineScope
) {
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
            SharedHomeShellIntent.CloseSecondaryDestination ->
                _state.value = _state.value.copy(destination = null)
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
        _state.value =
            _state.value.copy(
                destination = null,
                selectedTab = null,
                selectedConversationId = null,
                primarySurfaceVisible = false
            )
    }

    suspend fun acceptRealtimeEvent(serverId: String, event: RealtimeEvent) {
        acceptHome(SharedHomeIntent.ApplyRealtimeEvent(serverId, event))
        val current = _state.value
        if (current.isActivityVisible) {
            acceptActivityRealtime(
                SharedActivityIntent.ApplyRealtimeEvent(
                    event = event,
                    allowLocalPatch = current.home.allowsLocalActivityPatch(event)
                )
            )
        } else if (event is PerReceiverReadStateChanged || current.primarySurfaceVisible) {
            acceptActivityRealtime(
                SharedActivityIntent.ApplyRealtimeSummaryEvent(
                    event = event,
                    activeConversationId = current.selectedConversationId
                )
            )
        }
    }

    suspend fun acceptBackgroundServerUnreadEvent(serverId: String, event: RealtimeEvent) {
        acceptHome(SharedHomeIntent.ApplyBackgroundServerUnreadEvent(serverId, event))
    }

    fun clear() {
        jobs.forEach(Job::cancel)
    }

    private fun setSelectedTab(tab: String) {
        val previousTab = _state.value.selectedTab
        _state.value = _state.value.copy(selectedTab = tab)
        if (tab == "activity" && previousTab != "activity") {
            dispatchActivity(SharedActivityIntent.Refresh)
        }
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
    }

    private fun refreshVisibleHomeDestination() {
        when (val destination = _state.value.destination) {
            SharedHomeShellDestinationActivity -> dispatchActivity(SharedActivityIntent.Refresh)
            SharedHomeShellDestinationSaved -> dispatchSearchSaved(SharedHomeSearchSavedIntent.RefreshSaved)
            SharedHomeShellDestinationSearch -> dispatchSearchSaved(SharedHomeSearchSavedIntent.RefreshSearch)
            null ->
                if (_state.value.selectedTab == "activity") {
                    dispatchActivity(SharedActivityIntent.Refresh)
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
                    allowLocalPatch = current.home.allowsLocalActivityPatch(event)
                )
            )
        } else if (event is PerReceiverReadStateChanged || current.primarySurfaceVisible) {
            dispatchActivity(
                SharedActivityIntent.ApplyRealtimeSummaryEvent(
                    event = event,
                    activeConversationId = current.selectedConversationId
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
    val snapshot = (content as? SharedHomeContentState.Content)?.snapshot ?: return false
    val sourceChannelId =
        message.conversationContext?.parentChannelId?.value
            ?: message.channelId?.value
            ?: return false
    val conversation =
        (snapshot.channels.asSequence() + snapshot.directMessages.asSequence())
            .firstOrNull { row ->
                row.channelId?.value == sourceChannelId || row.threadId.value == sourceChannelId
            }
            ?: return false
    if (!conversation.activityMuted) return true
    val muteFromSeq = conversation.muteFromSeq ?: return false
    val messageSeq = message.seq ?: return false
    return messageSeq < muteFromSeq
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
        is SharedHomeIntent.MarkRead -> "patch:read:$conversationId"
        is SharedHomeIntent.MarkUnread -> "patch:unread:$conversationId"
        is SharedHomeIntent.MarkConversationReadAll -> "patch:read-all:$conversationId"
        is SharedHomeIntent.ProjectThreadUnfollow -> "patch:thread-unfollow:$threadChannelId:$phase"
        is SharedHomeIntent.CloseDirectMessage -> "remove:dm:$conversationId"
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
