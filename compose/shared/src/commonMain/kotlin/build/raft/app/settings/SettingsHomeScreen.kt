package build.raft.app

import build.raft.app.resources.*
import build.raft.app.thread.dispatch
import build.raft.app.thread.toThreadLaunchConversationIdentity
import build.raft.app.toThreadLaunchConversationIdentity
import build.raft.app.data.isAppUpdateHomeNotificationId
import build.raft.app.data.homeComputerVersionGateTargetOrNull
import build.raft.app.data.isMachineHomeNotificationId
import build.raft.app.data.raftMachineNoticeComputerIds
import build.raft.app.data.ChannelRepository
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.jointInviteIdFromHomeNotificationIdOrNull
import build.raft.app.data.seatJoinBlockIdFromHomeNotificationIdOrNull
import build.raft.app.data.usageLimitIdFromHomeNotificationIdOrNull
import build.raft.app.data.displayRowHints
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.agentLoungeEnabled
import build.raft.app.data.RaftAgentLounges
import build.raft.app.data.foldThreadUnreadByParent
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedHomeState
import build.raft.app.data.HomeServerActivityUnreadProjection
import build.raft.app.data.SharedHomeTopAction
import build.raft.app.data.isAuthSessionError
import build.raft.app.data.isEdgeBlockedMarker
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.ReadReceiptOwner
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationIdentityProvider
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.model.ConversationKind
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import build.raft.app.data.map
import kotlinx.coroutines.launch

private val EmptyComposerDraftRecordsFlow = MutableStateFlow(emptySet<SharedThreadComposerDraftRecord>())
private val EmptyConversationDirectoryProjectionFlow =
    MutableStateFlow(emptyMap<String, SharedConversationDirectoryProjection>())

internal fun sharedHomeContentRenderSummary(content: SharedHomeContentState): String =
    when (content) {
        is SharedHomeContentState.Loading ->
            "Loading(session=${content.session?.currentServerId ?: "_"})"
        is SharedHomeContentState.Empty ->
            "Empty(session=${content.session.currentServerId ?: "_"})"
        is SharedHomeContentState.Error ->
            "Error(${content.code},session=${content.session?.currentServerId ?: "_"})"
        is SharedHomeContentState.Content -> {
            val snapshot = content.snapshot
            "Content(server=${snapshot.server.id.value},channels=${snapshot.channels.size}," +
                "dms=${snapshot.directMessages.size},unread=${snapshot.unreadTotal}:${snapshot.unreadSource}," +
                "refreshing=${content.contentRefreshing})"
        }
    }

private fun sharedHomeRenderBranch(content: SharedHomeContentState): String =
    when (content) {
        is SharedHomeContentState.Loading ->
            if (content.session != null) "loading_session_shell" else "loading_centered"
        is SharedHomeContentState.Empty -> "empty_session_shell"
        is SharedHomeContentState.Error ->
            if (content.session != null) "error_session_shell_${content.code}" else "error_${content.code}"
        is SharedHomeContentState.Content -> "content"
    }

private fun sharedHomeErrorDisplay(code: String): RaftHomeInlineError =
    RaftHomeInlineError(
        title =
            when {
                ScreenError(code, retryable = false).isAuthSessionError() -> "Session expired"
                code.contains("permission", ignoreCase = true) -> "Home is unavailable"
                code.contains("timeout", ignoreCase = true) -> "Connection timed out"
                code.contains("offline", ignoreCase = true) -> "You are offline"
                code.contains("network", ignoreCase = true) -> "Could not refresh Home"
                else -> "Could not load Home"
            },
        detail =
            when {
                ScreenError(code, retryable = false).isAuthSessionError() ->
                    "Sign out and sign in again, or switch to another available server."
                code.contains("permission", ignoreCase = true) ->
                    "You can still switch servers or open account actions while this server recovers."
                code.contains("timeout", ignoreCase = true) ->
                    "The server did not respond in time. Retry, or switch servers if another workspace is available."
                code.contains("offline", ignoreCase = true) ->
                    "Check your connection, then retry. Server switch and account actions remain available."
                code.contains("network", ignoreCase = true) ->
                    "The current server could not be reached. Retry, or switch servers if another workspace is available."
                else ->
                    "Retry Home refresh, or use server switch and account actions to recover."
            },
        code = code,
        actionLabel = "Retry"
    )

internal fun sharedHomeSearchErrorDisplay(
    code: String,
    errorStrings: ErrorPresentationStrings
): RaftHomeInlineError =
    RaftHomeInlineError(
        title =
            when {
                code.contains("permission", ignoreCase = true) -> "Search is unavailable"
                code.contains("timeout", ignoreCase = true) -> "Search timed out"
                code.contains("offline", ignoreCase = true) -> "You are offline"
                code.contains("network", ignoreCase = true) -> "Could not refresh Search"
                else -> "Could not load Search"
            },
        detail =
            when {
                code.contains("permission", ignoreCase = true) ->
                    "You do not have access to search this workspace right now."
                code.contains("timeout", ignoreCase = true) ->
                    "The server did not respond in time. Retry search after a moment."
                code.contains("offline", ignoreCase = true) ->
                    "Check your connection, then retry search."
                code.contains("network", ignoreCase = true) ->
                    "The current server could not be reached. Retry search after it reconnects."
                else ->
                    code.userFacingErrorCodeMessage(errorStrings)
            },
        code = code.ifBlank { "unknown_error" },
        actionLabel = "Retry"
    )

@Composable
private fun localizedSharedHomeErrorDisplay(
    code: String,
    serverMessage: String? = null,
    traceId: String? = null
): RaftHomeInlineError =
    RaftHomeInlineError(
        title =
            when {
                serverMessage.isEdgeBlockedMarker() -> stringResource(Res.string.home_error_unavailable)
                ScreenError(code, retryable = false).isAuthSessionError() -> stringResource(Res.string.home_error_session_expired)
                code.contains("permission", ignoreCase = true) -> stringResource(Res.string.home_error_unavailable)
                code.contains("timeout", ignoreCase = true) -> stringResource(Res.string.home_error_timeout)
                code.contains("offline", ignoreCase = true) -> stringResource(Res.string.home_error_offline)
                code.contains("network", ignoreCase = true) -> stringResource(Res.string.home_error_refresh)
                else -> stringResource(Res.string.home_error_load)
            },
        detail =
            when {
                serverMessage.isEdgeBlockedMarker() -> stringResource(Res.string.error_edge_blocked)
                ScreenError(code, retryable = false).isAuthSessionError() -> stringResource(Res.string.home_error_session_detail)
                code.contains("permission", ignoreCase = true) -> stringResource(Res.string.home_error_unavailable_detail)
                code.contains("timeout", ignoreCase = true) -> stringResource(Res.string.home_error_timeout_detail)
                code.contains("offline", ignoreCase = true) -> stringResource(Res.string.home_error_offline_detail)
                code.contains("network", ignoreCase = true) -> stringResource(Res.string.home_error_network_detail)
                else -> stringResource(Res.string.home_error_load_detail)
            },
        code = code,
        actionLabel = stringResource(Res.string.common_retry),
        traceId = traceId
    )

/**
 * task #337: projects the shared home snapshot's direct messages into [RaftHomeConversationItem]
 * for the Slack-nav DMs tab, reusing the exact projection chain SharedHomeScreen uses
 * (directory identity + draft projections). Null-safe: empty until home content is ready.
 */
@Composable
private fun rememberProjectedDirectMessageItems(
    state: SharedHomeState,
    conversationIdentityProvider: SharedConversationIdentityProvider?,
    draftPersistSlice: SharedThreadComposerDraftPersistSlice?,
): List<RaftHomeConversationItem> {
    val snapshot = (state.content as? SharedHomeContentState.Content)?.snapshot
    val restoredRowHints = (state.content as? SharedHomeContentState.Content)?.displayRowHints().orEmpty()
    val activeDraftRecords by (draftPersistSlice?.activeDraftRecordsState ?: EmptyComposerDraftRecordsFlow)
        .collectAsState()
    val draftProjection =
        remember(activeDraftRecords, draftPersistSlice) {
            if (draftPersistSlice != null) {
                activeDraftRecords.toSharedThreadComposerDraftProjection()
            } else {
                SharedThreadComposerDraftProjection.Empty
            }
        }
    val conversationDirectoryIds =
        remember(snapshot?.channels, snapshot?.directMessages) {
            val channels = snapshot?.channels.orEmpty()
            val directMessages = snapshot?.directMessages.orEmpty()
            (channels + directMessages).map { it.threadId.value }
        }
    val conversationDirectoryFlow =
        remember(conversationIdentityProvider, snapshot?.server?.id, conversationDirectoryIds) {
            val serverId = snapshot?.server?.id
            if (conversationIdentityProvider == null || serverId == null) {
                EmptyConversationDirectoryProjectionFlow
            } else {
                conversationIdentityProvider.projectionFlow(serverId, conversationDirectoryIds)
            }
        }
    val conversationDirectoryProjections by conversationDirectoryFlow.collectAsState(emptyMap())
    val markedUnreadIds = snapshot?.markedUnreadIds.orEmpty()
    val conversationRowProjector =
        remember(conversationDirectoryProjections, draftProjection, restoredRowHints, markedUnreadIds) {
            SharedHomeConversationRowProjector(
                directoryProjections = conversationDirectoryProjections,
                draftProjection = draftProjection,
                restoredRowHints = restoredRowHints,
                markedUnreadIds = markedUnreadIds
            )
        }
    return remember(snapshot?.directMessages, conversationRowProjector) {
        snapshot
            ?.directMessages
            ?.map { conversation -> conversationRowProjector.project(conversation) }
            .orEmpty()
    }
}

/**
 * The Home row menu's Leave confirmation (web Sidebar -> `LeaveChannelConfirmDialog`): a private
 * channel reads its roster first, since its last member's leave deletes it; until the roster
 * arrives (or if it fails) the dialog warns that it deletes, as web does. Confirm hands back
 * [SharedHomeIntent.LeaveChannel].
 */
@Composable
private fun HomeLeaveChannelConfirm(
    target: HomeChannelRow,
    serverId: ServerId,
    serverMembersStore: SharedServerMembersStore?,
    onDismiss: () -> Unit,
    onConfirm: (SharedHomeIntent) -> Unit,
) {
    val isJoint = channelSettingsIsJoint(target)
    val isPrivate = channelSettingsIsPrivate(target)
    val memberCount by produceState<Int?>(null, target.channelId, serverId, serverMembersStore) {
        value = null
        if (isPrivate && !isJoint && serverMembersStore != null) {
            val roster = serverMembersStore.loadChannelMembers(serverId, target.channelId, force = true)
            value = (roster as? DataResult.Content)?.data?.members?.size
        }
    }
    LeaveChannelConfirmDialog(
        variant = leaveChannelConfirmVariant(isJoint = isJoint, isPrivate = isPrivate, memberCount = memberCount),
        channelName = target.name.removePrefix("#"),
        busy = false,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(homeLeaveChannelIntent(target)) },
    )
}

/** The intent a confirmed Home row Leave dispatches: the row's conversation id, as the menu passes it. */
internal fun homeLeaveChannelIntent(target: HomeChannelRow): SharedHomeIntent =
    SharedHomeIntent.LeaveChannel(target.threadId.value)

@Composable
internal fun SharedHomeScreen(
    bridge: build.raft.app.base.BridgeModule,
    state: SharedHomeState,
    active: Boolean,
    scrollToTopRequest: Int = 0,
    sessionReady: Boolean = true,
    conversationKindFilter: ConversationKind? = null,
    draftPersistSlice: SharedThreadComposerDraftPersistSlice? = null,
    conversationIdentityProvider: SharedConversationIdentityProvider? = null,
    activityUnreadCount: Int = 0,
    activityTotalCount: Int = 0,
    serverActivityExpectedOwner: ReadReceiptOwner? = null,
    savedCount: Int = 0,
    prefersChineseCommunity: Boolean = false,
    serverDrawerEnabled: Boolean = false,
    homeServerAvatarEnabled: Boolean = false,
    /** Wide layout: the top bar holds the server switcher and the bell, so the list has no head. */
    serverSwitcherInTopBar: Boolean = false,
    /** The phone bottom bar is on screen: the list drops its Search / Activity rows. */
    searchAndActivityOnShellNav: Boolean = false,
    /** Server flag `agent_lounge_v0` and not a Guest: agent sidebar rows merge with Lounges. */
    agentLoungeEnabled: Boolean = false,
    /** Loads a listed Lounge's own row (mute, recency) and creates Lounges on first click; null: off. */
    agentLoungeNetworkClient: build.raft.app.data.RaftNetworkClient? = null,
    /** Personal assistant agent IDs for this server (never merged with a Lounge). */
    personalAssistantAgentIds: Set<String> = emptySet(),
    onIntent: (SharedHomeIntent) -> Unit,
    onNotificationDismiss: (String) -> Unit = { id -> onIntent(SharedHomeIntent.DismissNotification(id)) },
    onAppUpdateOpen: (() -> Unit)? = null,
    externalFocusLossDismissEpoch: Int = 0,
    selectedConversationId: String? = null,
    onSelectedConversationCleared: () -> Unit = {},
    /** A picked server opens in its own window (multi-window setting); true = handled, no in-place switch. */
    onOpenServerInSeparateWindow: (serverId: String) -> Boolean = { false },
    onOpenSearch: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenSaved: () -> Unit,
    /** Server flag `drafts_section_v0` on: what the Drafts row's count reads; null hides the row. */
    draftsSource: RaftDraftsSource? = null,
    onOpenDrafts: () -> Unit = {},
    /** The phone layout's Assistant row (web `mobileInline`); null: no row (the wide layout). */
    assistantEntry: build.raft.app.assistant.HomeAssistantEntrySource? = null,
    onOpenComputers: () -> Unit = {},
    /** One Computer's page (a version-gate notice's View); falls back to the Computers list. */
    onOpenComputer: (machineId: String) -> Unit = { onOpenComputers() },
    /** The Computers list at Status: Needs attention (web `/computers?filter=attention`). */
    onOpenComputersAttention: () -> Unit = onOpenComputers,
    onOpenUrl: (String) -> Unit = bridge::openUrl,
    onOpenServerSelection: () -> Unit,
    /** Server switcher "Invite Human" for the given server; null hides the entry. */
    onInviteHuman: ((serverId: String) -> Unit)? = null,
    onCreateChannel: () -> Unit,
    /**
     * The External section's "+": Create Channel with External chosen, shown only to those Create
     * Channel offers External to ([externalChannelCreateAccess]: owners and admins).
     */
    onCreateJointChannel: (() -> Unit)? = null,
    onOpenConversation: (ThreadLaunchConversationIdentity) -> Unit,
    serverMembersStore: SharedServerMembersStore? = null,
    channelRepository: ChannelRepository? = null,
    jointInviteStore: build.raft.app.data.SharedJointInviteStore? = null,
    secondFactorRecoveryStore: build.raft.app.data.SharedSecondFactorRecoveryStore? = null,
    usageLimitAttentionStore: build.raft.app.data.SharedUsageLimitAttentionStore? = null,
    seatJoinBlocksStore: build.raft.app.data.SharedSeatJoinBlocksStore? = null,
    serverFeatureFlags: build.raft.app.data.SharedServerFeatureFlags? = null,
    onOpenBilling: () -> Unit = {},
    onAskSeatJoin: (
        server: build.raft.app.model.ServerSummary,
        block: build.raft.app.data.SeatJoinBlock,
        usage: build.raft.app.data.SeatJoinSeatUsage?,
        onSent: () -> Unit,
    ) -> Unit = { _, _, _, _ -> },
    topSafeAreaOverride: Dp? = null,
    // web Sidebar scroll content `pb-2`: 8dp under the last section's list.
    bottomContentPadding: Dp = RaftAppSpacing.Sm,
    listPositionServerId: String? = null,
    initialListPosition: AppShellLazyListPosition = AppShellLazyListPosition(),
    onListPositionSnapshot: (String?, AppShellLazyListPosition) -> Unit = { _, _ -> },
    onContentComposeCommitted: (String) -> Unit = {}
) {
    // keepItemAlive keeps hidden pages composed: a DisposableEffect
    // (onDispose) downstream can still fire a stale snapshot while the page is
    // off-screen. Gate through rememberUpdatedState so the check reads the
    // current `active` flag, not the one captured at callback creation.
    val latestActive = rememberUpdatedState(active)
    // The Home switcher pick: a server that opens in its own window leaves this one (and its open
    // conversation) as it is; otherwise switch in place.
    val selectServerFromSwitcher: (String) -> Unit = { serverId ->
        if (onOpenServerInSeparateWindow(serverId)) {
            onIntent(SharedHomeIntent.DismissServerSwitcher)
        } else {
            onSelectedConversationCleared()
            onIntent(SharedHomeIntent.SelectServer(serverId))
        }
    }
    val conversationUnavailableMessage = stringResource(Res.string.home_error_conversation_unavailable)
    val directMessageUnavailableMessage = stringResource(Res.string.home_error_direct_message_unavailable)
    val openDirectMessageFailedMessage = stringResource(Res.string.home_error_open_direct_message)
    val openDirectMessagePermissionMessage = stringResource(Res.string.home_error_open_direct_message_permission)
    val errorStrings = localizedErrorPresentationStrings()
    val pinnedMemberOpenScope = rememberCoroutineScope()
    val homeContentSummary = sharedHomeContentRenderSummary(state.content)
    val shouldStartHome = sharedHomeShouldStartOnActive(active, sessionReady, state)
    val jointInvites =
        rememberHomeJointInvites(
            bridge = bridge,
            store = jointInviteStore,
            content = state.content,
            active = active,
            onIntent = onIntent,
            onOpenConversation = onOpenConversation,
        )
    val secondFactorRecovery = rememberHomeSecondFactorRecovery(bridge, secondFactorRecoveryStore, active)
    val usageLimitAttention =
        rememberHomeUsageLimitAttention(
            store = usageLimitAttentionStore,
            content = state.content,
            active = active,
        )
    val seatJoinBlocks =
        rememberHomeSeatJoinBlocks(
            store = seatJoinBlocksStore,
            serverFeatureFlags = serverFeatureFlags,
            content = state.content,
            active = active,
        )
    LaunchedEffect(active, sessionReady, homeContentSummary, state.homeRealtimeDirty, shouldStartHome) {
        bridge.log(
            "shared_home_screen_enter active=$active sessionReady=$sessionReady " +
                "shouldStart=$shouldStartHome state=$homeContentSummary homeRealtimeDirty=${state.homeRealtimeDirty}"
        )
    }
    LaunchedEffect(active, sessionReady, state.content, state.homeRealtimeDirty) {
        val effectShouldStart = sharedHomeShouldStartOnActive(active, sessionReady, state)
        bridge.log(
            "shared_home_start_effect active=$active sessionReady=$sessionReady " +
                "shouldStart=$effectShouldStart state=${sharedHomeContentRenderSummary(state.content)} " +
                "homeRealtimeDirty=${state.homeRealtimeDirty}"
        )
        if (effectShouldStart) {
            bridge.log(
                "shared_home_start_effect_dispatch active=$active sessionReady=$sessionReady " +
                    "state=${sharedHomeContentRenderSummary(state.content)}"
            )
            onIntent(SharedHomeIntent.Start)
        }
    }

    val serverSwitchState = state.serverSwitchState
    val serverSwitcherExpanded = state.serverSwitcherExpanded
    val serverDrawerSections =
        remember(
            state.serverDrawer,
            state.serverUnreadCounts,
            state.serverActivityUnread,
            serverActivityExpectedOwner,
        ) {
            state.serverDrawer.toRaftHomeServerDrawerSections(
                serverUnreadCounts = state.serverUnreadCounts,
                activityExpectedOwner = serverActivityExpectedOwner,
                activityProjection = state.serverActivityUnread,
            )
        }
    val renderBranch = sharedHomeRenderBranch(state.content)
    LaunchedEffect(active, renderBranch, homeContentSummary, selectedConversationId, activityUnreadCount, savedCount) {
        bridge.log(
            "shared_home_render_branch active=$active branch=$renderBranch state=$homeContentSummary " +
                "activityUnread=$activityUnreadCount saved=$savedCount selectedConversation=${selectedConversationId.orEmpty()}"
        )
    }
    when (val current = state.content) {
        is SharedHomeContentState.Loading -> {
            val session = current.session
            if (session != null) {
                SharedHomeSessionShell(
                    session = session,
                    searchAndActivityOnShellNav = searchAndActivityOnShellNav,
                    scrollToTopRequest = scrollToTopRequest,
                    serverSwitcherExpanded = serverSwitcherExpanded,
                    serverDrawerEnabled = serverDrawerEnabled,
                    homeServerAvatarEnabled = homeServerAvatarEnabled,
                    serverSwitcherInTopBar = serverSwitcherInTopBar,
                    serverDrawerSections = serverDrawerSections,
                    serverUnreadCounts = state.serverUnreadCounts,
                    serverActivityExpectedOwner = serverActivityExpectedOwner,
                    serverActivityProjection = state.serverActivityUnread,
                    prefersChineseCommunity = prefersChineseCommunity,
                    topSafeAreaOverride = topSafeAreaOverride,
                    bottomContentPadding = bottomContentPadding,
                    loadingSkeletons = true,
                    onServerBadgeClick = { onIntent(SharedHomeIntent.ToggleServerSwitcher) },
                    onServerSwitcherDismiss = { onIntent(SharedHomeIntent.DismissServerSwitcher) },
                    onServerDrawerSectionToggle = { serverId ->
                        onIntent(SharedHomeIntent.ToggleServerDrawerSection(ServerId(serverId)))
                    },
                    onServerDrawerRetry = { serverId ->
                        onIntent(SharedHomeIntent.RetryServerDrawerSection(ServerId(serverId)))
                    },
                    onServerSelected = selectServerFromSwitcher,
                    onServerOrderChange = { order ->
                        onIntent(SharedHomeIntent.ReorderServers(order))
                    },
                    onCreateServerClick = onOpenServerSelection,
                    onInviteHuman = onInviteHuman,
                    onCreateChannelClick = onCreateChannel
                )
            } else {
                RaftCenteredLoading()
            }
        }
        is SharedHomeContentState.Empty ->
            SharedHomeSessionShell(
                session = current.session,
                searchAndActivityOnShellNav = searchAndActivityOnShellNav,
                scrollToTopRequest = scrollToTopRequest,
                serverSwitcherExpanded = serverSwitcherExpanded,
                serverDrawerEnabled = serverDrawerEnabled,
                homeServerAvatarEnabled = homeServerAvatarEnabled,
                serverSwitcherInTopBar = serverSwitcherInTopBar,
                serverDrawerSections = serverDrawerSections,
                serverUnreadCounts = state.serverUnreadCounts,
                serverActivityExpectedOwner = serverActivityExpectedOwner,
                serverActivityProjection = state.serverActivityUnread,
                prefersChineseCommunity = prefersChineseCommunity,
                topSafeAreaOverride = topSafeAreaOverride,
                bottomContentPadding = bottomContentPadding,
                onServerBadgeClick = { onIntent(SharedHomeIntent.ToggleServerSwitcher) },
                onServerSwitcherDismiss = { onIntent(SharedHomeIntent.DismissServerSwitcher) },
                onServerDrawerSectionToggle = { serverId ->
                    onIntent(SharedHomeIntent.ToggleServerDrawerSection(ServerId(serverId)))
                },
                onServerDrawerRetry = { serverId ->
                    onIntent(SharedHomeIntent.RetryServerDrawerSection(ServerId(serverId)))
                },
                onServerSelected = selectServerFromSwitcher,
                onServerOrderChange = { order ->
                    onIntent(SharedHomeIntent.ReorderServers(order))
                },
                onCreateServerClick = onOpenServerSelection,
                onInviteHuman = onInviteHuman,
                onCreateChannelClick = onCreateChannel
            )
        is SharedHomeContentState.Error -> {
            val errorSession = current.session
            if (errorSession != null) {
                SharedHomeSessionShell(
                    session = errorSession,
                    searchAndActivityOnShellNav = searchAndActivityOnShellNav,
                    scrollToTopRequest = scrollToTopRequest,
                    serverSwitcherExpanded = serverSwitcherExpanded,
                    serverDrawerEnabled = serverDrawerEnabled,
                    homeServerAvatarEnabled = homeServerAvatarEnabled,
                    serverSwitcherInTopBar = serverSwitcherInTopBar,
                    serverDrawerSections = serverDrawerSections,
                    serverUnreadCounts = state.serverUnreadCounts,
                    serverActivityExpectedOwner = serverActivityExpectedOwner,
                    serverActivityProjection = state.serverActivityUnread,
                    prefersChineseCommunity = prefersChineseCommunity,
                    topSafeAreaOverride = topSafeAreaOverride,
                    bottomContentPadding = bottomContentPadding,
                    inlineError =
                        localizedSharedHomeErrorDisplay(
                            current.code,
                            serverMessage = current.serverMessage,
                            traceId = current.traceId
                        ),
                    onInlineErrorAction = { onIntent(SharedHomeIntent.Refresh) },
                    onServerBadgeClick = { onIntent(SharedHomeIntent.ToggleServerSwitcher) },
                    onServerSwitcherDismiss = { onIntent(SharedHomeIntent.DismissServerSwitcher) },
                    onServerDrawerSectionToggle = { serverId ->
                        onIntent(SharedHomeIntent.ToggleServerDrawerSection(ServerId(serverId)))
                    },
                    onServerDrawerRetry = { serverId ->
                        onIntent(SharedHomeIntent.RetryServerDrawerSection(ServerId(serverId)))
                    },
                    onServerSelected = selectServerFromSwitcher,
                    onServerOrderChange = { order ->
                        onIntent(SharedHomeIntent.ReorderServers(order))
                    },
                    onCreateServerClick = onOpenServerSelection,
                    onInviteHuman = onInviteHuman,
                    onCreateChannelClick = onCreateChannel
                )
            } else {
                val error =
                    localizedSharedHomeErrorDisplay(
                        current.code,
                        serverMessage = current.serverMessage,
                        traceId = current.traceId
                    )
                PageErrorContent(
                    title = stringResource(Res.string.tab_home),
                    subtitle = stringResource(Res.string.settings_navigation_section_workspace),
                    iconName = RaftLucideIconName.House,
                    message = error.title,
                    code = current.code,
                    traceId = current.traceId,
                    onRetry = { onIntent(SharedHomeIntent.Refresh) }
                )
            }
        }
        is SharedHomeContentState.Content -> {
            val snapshot = current.snapshot
            SideEffect {
                if (active) {
                    onContentComposeCommitted(snapshot.server.id.value)
                }
            }
            val agentStatusResolver =
                remember(serverMembersStore, snapshot.server.id.value) {
                    raftScopedAgentStatusResolver(serverMembersStore, snapshot.server.id.value)
                }
            val memberAvatarResolver =
                remember(serverMembersStore, snapshot.server.id.value) {
                    raftMemberAvatarResolver(serverMembersStore, snapshot.server.id.value)
                }
            val memberAppBotResolver =
                remember(serverMembersStore, snapshot.server.id.value) {
                    raftMemberAppBotResolver(serverMembersStore, snapshot.server.id.value)
                }
            val pinnedMemberProfileResolver =
                rememberPinnedMemberProfileResolver(
                    serverMembersStore = serverMembersStore,
                    serverId = snapshot.server.id,
                    pinnedItems = snapshot.sidebarOrder?.pinned.orEmpty(),
                )
            LaunchedEffect(serverMembersStore, snapshot.server.id) {
                bridge.log(
                    "shared_home_avatar_chain serverId=${snapshot.server.id.value} " +
                        "memberStore=${serverMembersStore != null} resolver=member_ssot"
                )
            }
            LaunchedEffect(serverMembersStore, snapshot.server.id, current.contentRefreshing, active) {
                if (active && !current.contentRefreshing) {
                    serverMembersStore?.refreshMembers(snapshot.server.id, DataRequestPriority.Background)
                }
            }
            val activeDraftRecords by (draftPersistSlice?.activeDraftRecordsState ?: EmptyComposerDraftRecordsFlow)
                .collectAsState()
            val draftProjection =
                remember(activeDraftRecords, draftPersistSlice) {
                    if (draftPersistSlice != null) {
                        activeDraftRecords.toSharedThreadComposerDraftProjection()
                    } else {
                        SharedThreadComposerDraftProjection.Empty
                    }
                }
            // Drafts (drafts_section_v0): the row's count is the Drafts list's length, placed the same way.
            val draftDirectory = rememberRaftDraftDirectory(draftsSource, snapshot)
            val draftListing =
                rememberRaftDraftListing(
                    directory = draftDirectory,
                    draftPersistSlice = draftPersistSlice,
                    networkClient = draftsSource?.networkClient,
                    refreshTick = if (active) 1 else 0,
                )
            val conversationDirectoryIds =
                remember(snapshot.channels, snapshot.directMessages) {
                    (snapshot.channels + snapshot.directMessages).map { it.threadId.value }
                }
            val conversationDirectoryFlow =
                remember(conversationIdentityProvider, snapshot.server.id, conversationDirectoryIds) {
                    conversationIdentityProvider
                        ?.projectionFlow(snapshot.server.id, conversationDirectoryIds)
                        ?: EmptyConversationDirectoryProjectionFlow
                }
            val conversationDirectoryProjections by conversationDirectoryFlow.collectAsState(emptyMap())
            val restoredRowHints = current.displayRowHints()
            // Store-owned followed-thread unread, folded per parent channel.
            val threadUnreadByParent =
                remember(snapshot.followedThreadUnread) {
                    snapshot.followedThreadUnread.foldThreadUnreadByParent()
                }
            // Agent Lounge v0: a Lounge joined here (your first post) shows its row without a Home reload.
            val knownAgentLounges by RaftAgentLounges.lounges.collectAsState()
            val conversationRowProjector =
                remember(
                    conversationDirectoryProjections,
                    draftProjection,
                    restoredRowHints,
                    threadUnreadByParent,
                    snapshot.markedUnreadIds,
                ) {
                    SharedHomeConversationRowProjector(
                        directoryProjections = conversationDirectoryProjections,
                        draftProjection = draftProjection,
                        restoredRowHints = restoredRowHints,
                        threadUnreadByParent = threadUnreadByParent,
                        markedUnreadIds = snapshot.markedUnreadIds,
                    )
                }
            // Agent Lounge v0: each listed Lounge's own row (Direct Messages, or Pinned once pinned).
            val serverKnownAgentLounges = knownAgentLounges[snapshot.server.id.value].orEmpty()
            val agentLoungeItems =
                remember(
                    agentLoungeEnabled,
                    snapshot.directMessages,
                    snapshot.standaloneLounges,
                    serverKnownAgentLounges,
                    snapshot.markedUnreadIds,
                    snapshot.followedThreadUnread,
                    snapshot.unreadEnrichmentAvailable,
                ) {
                    homeSidebarAgentLoungeItems(
                        enabled = agentLoungeEnabled,
                        directMessages = snapshot.directMessages,
                        known = serverKnownAgentLounges,
                        markedUnreadIds = snapshot.markedUnreadIds,
                        title = ::agentLoungeTitleNow,
                        standaloneLounges = snapshot.standaloneLounges,
                        followedThreadUnread = snapshot.followedThreadUnread,
                        useLiveFollowedThreadUnread = snapshot.unreadEnrichmentAvailable,
                    )
                }
            // web slock #13876 (useSidebarItems `ensureChannel`): a listed Lounge this client never
            // loaded has only its DM row's summary; its own row brings your mute (the menu's Mute)
            // and its latest activity. Each is loaded once (single flight across Home instances).
            val unloadedAgentLoungeIds =
                remember(agentLoungeItems, serverKnownAgentLounges) {
                    agentLoungeItems.map { it.id }.filter { id -> serverKnownAgentLounges[id]?.loaded != true }
                }
            // Each load runs in the page's scope, not the effect's: one Lounge finishing changes
            // [unloadedAgentLoungeIds] and restarts the effect, which must not cancel another
            // Lounge's request still in flight (it would stay unloaded with no Mute).
            val agentLoungeLoadScope = rememberCoroutineScope()
            LaunchedEffect(snapshot.server.id, unloadedAgentLoungeIds, agentLoungeNetworkClient) {
                val client = agentLoungeNetworkClient ?: return@LaunchedEffect
                val serverId = snapshot.server.id
                val api = build.raft.app.data.AgentLoungeApi(client)
                for (loungeId in unloadedAgentLoungeIds) {
                    if (!RaftAgentLounges.beginLoad(serverId.value, loungeId)) continue
                    agentLoungeLoadScope.launch {
                        try {
                            val result = api.loadLounge(serverId, ChannelId(loungeId))
                            if (result !is DataResult.Content) {
                                bridge.log("agent_lounge_row_load_failed serverId=${serverId.value} lounge=${loungeId.take(8)}")
                            }
                        } finally {
                            RaftAgentLounges.endLoad(serverId.value, loungeId)
                        }
                    }
                }
            }
            val servers =
                remember(
                    current.session.servers,
                    snapshot.server,
                    state.serverUnreadCounts,
                    state.serverActivityUnread,
                    serverActivityExpectedOwner,
                ) {
                    sharedHomeSwitcherItems(
                        session = current.session,
                        selectedServer = snapshot.server,
                        serverUnreadCounts = state.serverUnreadCounts,
                        activityExpectedOwner = serverActivityExpectedOwner,
                        activityProjection = state.serverActivityUnread,
                    )
                }
            val channelItems =
                remember(snapshot.channels, conversationRowProjector) {
                    snapshot.channels.map { conversation ->
                        conversationRowProjector.project(conversation)
                    }
                }
            val directMessageItems =
                remember(snapshot.directMessages, conversationRowProjector) {
                    snapshot.directMessages.map { conversation ->
                        conversationRowProjector.project(conversation)
                    }
                }
            val serverMemberProfiles by produceState(
                initialValue = serverMembersStore?.currentServerMembers(snapshot.server.id).orEmpty(),
                serverMembersStore,
                snapshot.server.id,
            ) {
                val store = serverMembersStore ?: return@produceState
                store.state.collect {
                    value = store.currentServerMembers(snapshot.server.id)
                }
            }
            val appBotAgentIds =
                remember(serverMemberProfiles) {
                    serverMemberProfiles
                        .filter { it.kind == build.raft.app.model.MemberKind.Agent && it.appBotIdentityOrNull() != null }
                        .mapTo(HashSet()) { it.id.value }
                }
            val deletedAgentIds =
                remember(serverMemberProfiles) {
                    serverMemberProfiles
                        .filter {
                            it.kind == build.raft.app.model.MemberKind.Agent &&
                                (it.agentDeletedAt?.isNotBlank() == true || it.assistantPausedAt != null)
                        }
                        .mapTo(HashSet()) { it.id.value }
                }
            val mergedPinnedChannelIds =
                remember(snapshot.sidebarOrder?.pinnedChannelIds, snapshot.sidebarOrder?.pinned) {
                    buildSet {
                        addAll(snapshot.sidebarOrder?.pinnedChannelIds.orEmpty())
                        snapshot.sidebarOrder?.pinned.orEmpty().forEach { item ->
                            if (item.kind.equals("channel", ignoreCase = true) && item.id.isNotBlank()) {
                                add(item.id.trim())
                            }
                        }
                    }
                }
            val mergedPinnedAgentIds =
                remember(snapshot.sidebarOrder?.pinnedAgentIds, snapshot.sidebarOrder?.pinned) {
                    buildSet {
                        addAll(snapshot.sidebarOrder?.pinnedAgentIds.orEmpty())
                        snapshot.sidebarOrder?.pinned.orEmpty().forEach { item ->
                            if (item.kind.equals("agent", ignoreCase = true) && item.id.isNotBlank()) {
                                add(item.id.trim())
                            }
                        }
                    }
                }
            val mergedAgentConversations =
                remember(
                    agentLoungeEnabled,
                    directMessageItems,
                    agentLoungeItems,
                    snapshot.directMessages,
                    serverKnownAgentLounges,
                    snapshot.markedUnreadIds,
                    mergedPinnedChannelIds,
                    mergedPinnedAgentIds,
                    personalAssistantAgentIds,
                    appBotAgentIds,
                    deletedAgentIds,
                ) {
                    homeSidebarMergeAgentLounges(
                        enabled = agentLoungeEnabled,
                        directMessages = directMessageItems,
                        agentLounges = agentLoungeItems,
                        rawDirectMessages = snapshot.directMessages,
                        knownLounges = serverKnownAgentLounges,
                        markedUnreadIds = snapshot.markedUnreadIds,
                        pinnedChannelIds = mergedPinnedChannelIds,
                        pinnedAgentIds = mergedPinnedAgentIds,
                        personalAssistantAgentIds = personalAssistantAgentIds,
                        appBotAgentIds = appBotAgentIds,
                        deletedAgentIds = deletedAgentIds,
                    )
                }
            val mergedDirectMessageItems = mergedAgentConversations.directMessages
            val remainingAgentLoungeItems = mergedAgentConversations.agentLounges
            val mergedDirectMessagesById =
                remember(mergedDirectMessageItems) {
                    mergedDirectMessageItems.associateBy { it.id }
                }
            val loungeOpenFailedMessage = stringResource(Res.string.agent_lounge_open_failed)
            val latestLoungeOpenFailedMessage by rememberUpdatedState(loungeOpenFailedMessage)
            val homeLoungeOpener =
                remember(agentLoungeNetworkClient, agentLoungeLoadScope, bridge) {
                    agentLoungeNetworkClient?.let { client ->
                        AgentLoungeOpener(
                            scope = agentLoungeLoadScope,
                            networkClient = client,
                            openFailedMessage = { latestLoungeOpenFailedMessage },
                            onError = { message, traceId -> bridge.errorToast(message, traceId) },
                        )
                    }
                }
            val channelDraftIds =
                remember(channelItems) {
                    channelItems.filter { it.hasDraft }.map { it.id }
                }
            val directMessageDraftIds =
                remember(directMessageItems) {
                    directMessageItems.filter { it.hasDraft }.map { it.id }
                }
            LaunchedEffect(snapshot.server.id, activeDraftRecords, channelDraftIds, directMessageDraftIds) {
                bridge.log(
                    "shared_home_draft_projection serverId=${snapshot.server.id.value} " +
                        "activeCount=${activeDraftRecords.size} " +
                        "activeRecords=${activeDraftRecords.toThreadComposerDraftDiagnosticsSummary()} " +
                        "channelDraftIds=${channelDraftIds.joinToString(separator = ",")} " +
                        "directMessageDraftIds=${directMessageDraftIds.joinToString(separator = ",")}"
                )
            }
            // Account security first (web useSystemNotifications), then joint invites (web "0a"),
            // then server-backed usage-limit attention cards (web "0"), then seat-join blocks ("0b").
            val notificationItems =
                listOfNotNull(secondFactorRecovery.item) +
                    jointInvites.items +
                    usageLimitAttention.items +
                    seatJoinBlocks.items +
                    state.notifications.map { it.toHomeNotificationItem() }
            val conversationsByThreadId =
                remember(snapshot.channels, snapshot.directMessages) {
                    (snapshot.channels + snapshot.directMessages).associateBy { it.threadId.value }
                }
            val homeActivityUnreadCount = homeActivitySidebarUnreadCount(
                activityUnreadCount = activityUnreadCount
            )
            val homeShellLabels = RaftHomeShellLabels.localized()
            // Row menu "Leave Channel" awaiting its confirmation (web Sidebar `leaveConfirm`).
            var leaveConfirmChannel by remember(snapshot.server.id) { mutableStateOf<HomeChannelRow?>(null) }
            // Web "Hide Empty Sections": a device-local display preference, shared with
            // Settings > Appearance "Hide empty sidebar sections".
            val hideEmptySections = build.raft.app.data.RaftDisplayPreferenceRuntime.observeHideEmptySidebarSections()
            RaftStructuralHomeShell(
                currentServerName = snapshot.server.name,
                currentServerId = snapshot.server.id.value,
                servers = servers,
                // task #94: the Slack-aligned Home tab is channels-only and the DMs tab is
                // direct-message-only; null keeps the legacy merged list.
                channels =
                    if (conversationKindFilter == ConversationKind.DirectMessage) {
                        emptyList()
                    } else {
                        channelItems
                    },
                directMessages =
                    if (conversationKindFilter == ConversationKind.Channel) {
                        emptyList()
                    } else {
                        mergedDirectMessageItems
                    },
                selectedChannelId = selectedConversationId,
                inboxUnreadCount = homeActivityUnreadCount,
                inboxTotalCount = homeActivitySidebarTotalCount(
                    activityUnreadCount = homeActivityUnreadCount,
                    activityTotalCount = activityTotalCount
                ),
                savedCount = savedCount,
                draftsCount = draftListing.count.takeIf { draftDirectory != null && draftListing.recordsKnown },
                onAssistantClick =
                    build.raft.app.assistant.rememberHomeAssistantRow(assistantEntry, snapshot.server.id.value, current.session.user.id, snapshot.server.role),
                serverSwitcherExpanded = serverSwitcherExpanded,
                serverDrawerEnabled = serverDrawerEnabled,
                homeServerAvatarEnabled = homeServerAvatarEnabled,
                serverSwitcherInTopBar = serverSwitcherInTopBar,
                serverDrawerSections = serverDrawerSections,
                pendingServerId = serverSwitchState.pendingServerId,
                prefersChineseCommunity = prefersChineseCommunity,
                topSafeAreaOverride = topSafeAreaOverride,
                activitySelected = state.selectedTopAction == SharedHomeTopAction.Activity,
                searchSelected = state.selectedTopAction == SharedHomeTopAction.Search,
                savedSelected = state.selectedTopAction == SharedHomeTopAction.Saved,
                draftsSelected = state.selectedTopAction == SharedHomeTopAction.Drafts,
                notifications = notificationItems,
                contentRefreshing = sharedHomeHeaderRefreshing(state),
                unreadStale = state.unreadStale,
                unreadUnavailable = state.unreadUnavailable,
                channelOrder = snapshot.sidebarOrder?.channelOrder.orEmpty(),
                channelSortMode = snapshot.sidebarOrder?.channelSortMode ?: "manual",
                jointChannelSortMode = snapshot.sidebarOrder?.jointChannelSortMode ?: "manual",
                dmOrder = snapshot.sidebarOrder?.dmOrder.orEmpty(),
                dmSortMode = snapshot.sidebarOrder?.dmSortMode ?: "manual",
                pinnedChannelIds = snapshot.sidebarOrder?.pinnedChannelIds.orEmpty(),
                pinnedAgentIds = snapshot.sidebarOrder?.pinnedAgentIds.orEmpty(),
                pinnedOrder = snapshot.sidebarOrder?.pinnedOrder.orEmpty(),
                pinnedItems = snapshot.sidebarOrder?.pinned.orEmpty(),
                pinnedSortMode = snapshot.sidebarOrder?.pinnedSortMode ?: "manual",
                hiddenDmIds = snapshot.sidebarOrder?.hiddenDmIds.orEmpty(),
                agentLounges =
                    if (conversationKindFilter == ConversationKind.Channel) {
                        emptyList()
                    } else {
                        remainingAgentLoungeItems
                    },
                customSections = snapshot.sidebarOrder?.customSections.orEmpty(),
                sectionOrder = snapshot.sidebarOrder?.sectionOrder.orEmpty(),
                sectionPlacements = snapshot.sidebarOrder?.sectionPlacements.orEmpty(),
                hideEmptySections = hideEmptySections,
                canArchiveChannels = snapshot.server.permissions.canManageSettings,
                showBottomBar = false,
                searchAndActivityOnShellNav = searchAndActivityOnShellNav,
                externalFocusLossDismissEpoch = externalFocusLossDismissEpoch,
                bottomContentPadding = bottomContentPadding,
                outerPadding = 0.dp,
                labels = homeShellLabels,
                onServerBadgeClick = { onIntent(SharedHomeIntent.ToggleServerSwitcher) },
                onServerSwitcherDismiss = { onIntent(SharedHomeIntent.DismissServerSwitcher) },
                onServerDrawerSectionToggle = { serverId ->
                    onIntent(SharedHomeIntent.ToggleServerDrawerSection(ServerId(serverId)))
                },
                onServerDrawerRetry = { serverId ->
                    onIntent(SharedHomeIntent.RetryServerDrawerSection(ServerId(serverId)))
                },
                onServerSelected = selectServerFromSwitcher,
                onServerOrderChange = { order ->
                    onIntent(SharedHomeIntent.ReorderServers(order))
                },
                onCreateServerClick = onOpenServerSelection,
                onInviteHumanClick =
                    homeServerSwitcherInviteClick(snapshot.server, onInviteHuman) {
                        onIntent(SharedHomeIntent.DismissServerSwitcher)
                    },
                onCreateChannelClick = onCreateChannel,
                // Web useSidebarViewState `canCreateJointChannel`.
                onCreateJointChannelClick = onCreateJointChannel?.takeIf { externalChannelCreateAccess(snapshot.server.role) },
                onSearchClick = {
                    onIntent(SharedHomeIntent.SelectTopAction(SharedHomeTopAction.Search))
                    onOpenSearch()
                },
                onInboxClick = {
                    onIntent(SharedHomeIntent.SelectTopAction(SharedHomeTopAction.Activity))
                    onOpenActivity()
                },
                onSavedClick = {
                    onIntent(SharedHomeIntent.SelectTopAction(SharedHomeTopAction.Saved))
                    onOpenSaved()
                },
                onDraftsClick = {
                    onIntent(SharedHomeIntent.SelectTopAction(SharedHomeTopAction.Drafts))
                    onOpenDrafts()
                },
                onNotificationView = { notification ->
                    val jointInviteId = notification.id.jointInviteIdFromHomeNotificationIdOrNull()
                    val usageLimitIncidentId = notification.id.usageLimitIdFromHomeNotificationIdOrNull()
                    val seatJoinBlockId = notification.id.seatJoinBlockIdFromHomeNotificationIdOrNull()
                    val versionGateComputerId = homeComputerVersionGateTargetOrNull(notification.id, notification.targetId)
                    when {
                        notification.id == SECOND_FACTOR_RECOVERY_HOME_NOTIFICATION_ID ->
                            secondFactorRecovery.cancel()
                        jointInviteId != null ->
                            jointInviteStore?.dispatch(build.raft.app.data.SharedJointInviteIntent.Accept(jointInviteId))
                        usageLimitIncidentId != null -> {
                            usageLimitAttention.markSeen(usageLimitIncidentId)
                            val machineId = notification.targetId?.trim()?.takeIf { it.isNotEmpty() }
                            if (machineId != null) onOpenComputer(machineId) else onOpenComputers()
                        }
                        seatJoinBlockId != null -> {
                            if (settingsBillingOffersUpgrade()) {
                                if (snapshot.server.role?.trim()?.lowercase() == "owner") {
                                    onOpenBilling()
                                } else {
                                    seatJoinBlocks.blockById[seatJoinBlockId]?.let { block ->
                                        onAskSeatJoin(snapshot.server, block, seatJoinBlocks.usage, seatJoinBlocks.refresh)
                                    }
                                }
                            }
                        }
                        notification.id.isAppUpdateHomeNotificationId() && onAppUpdateOpen != null ->
                            onAppUpdateOpen()
                        versionGateComputerId != null ->
                            onOpenComputer(versionGateComputerId)
                        // Web computer-attention View: one computer opens it, several open the list
                        // filtered to those needing attention.
                        notification.id.isMachineHomeNotificationId() ->
                            raftMachineNoticeComputerIds(notification.id).singleOrNull()?.let(onOpenComputer)
                                ?: onOpenComputersAttention()
                        notification.targetId?.startsWith("http://") == true ||
                            notification.targetId?.startsWith("https://") == true -> {
                            notification.targetId?.let(onOpenUrl)
                        }
                        else -> Unit
                    }
                },
                onNotificationDismiss = { notification ->
                    val jointInviteId = notification.id.jointInviteIdFromHomeNotificationIdOrNull()
                    val usageLimitIncidentId = notification.id.usageLimitIdFromHomeNotificationIdOrNull()
                    val seatJoinBlockId = notification.id.seatJoinBlockIdFromHomeNotificationIdOrNull()
                    if (notification.security) {
                        // Not dismissible (no Dismiss is drawn): it goes only when the reset does.
                    } else if (jointInviteId != null) {
                        jointInviteStore?.dispatch(build.raft.app.data.SharedJointInviteIntent.Dismiss(jointInviteId))
                    } else if (usageLimitIncidentId != null) {
                        usageLimitAttention.dismiss(usageLimitIncidentId)
                    } else if (seatJoinBlockId != null) {
                        seatJoinBlocks.dismiss(seatJoinBlockId)
                    } else {
                        onNotificationDismiss(notification.id)
                    }
                },
                onNotificationCenterOpened = {
                    secondFactorRecovery.refresh()
                    usageLimitAttention.markAllUnseenSeen()
                },
                onRefresh = {
                    onIntent(SharedHomeIntent.Refresh)
                    jointInvites.refresh()
                    secondFactorRecovery.refresh()
                    usageLimitAttention.refresh()
                    seatJoinBlocks.refresh()
                },
                onChannelClick = { threadId ->
                    val mergedDm = mergedDirectMessagesById[threadId]
                    val hiddenIds = snapshot.sidebarOrder?.hiddenDmIds.orEmpty()
                    val mergedAgentId = mergedDm?.presenceMemberId?.trim()?.takeIf { it.isNotEmpty() }
                    val openMergedAsLounge =
                        mergedDm?.opensAgentLoungeOnClick == true &&
                            resolveMergedAgentRowOpenSide(
                                store = defaultAgentConversationSideStore(),
                                userId = current.session.user.id,
                                serverId = snapshot.server.id.value,
                                agentId = mergedAgentId,
                                hasDirectMessage = conversationsByThreadId[threadId] != null,
                            ) == AgentLoungeConversationSide.Lounge
                    if (openMergedAsLounge) {
                        val mergedLoungeId = mergedDm.mergedAgentLounge?.channelId
                        val immediateLoungeIdentity =
                            mergedLoungeId?.let { loungeId ->
                                agentLoungeConversationIdentityOrNull(
                                    snapshot.server.id.value,
                                    loungeId,
                                    snapshot.directMessages,
                                )
                            }
                        if (immediateLoungeIdentity != null && mergedLoungeId != null) {
                            if (mergedDm.id in hiddenIds) {
                                onIntent(SharedHomeIntent.ReopenClosedConversation(mergedDm.id))
                            }
                            onOpenConversation(immediateLoungeIdentity)
                            onIntent(SharedHomeIntent.SelectConversation(mergedLoungeId))
                        } else {
                            val agentId = mergedAgentId
                            if (agentId != null && homeLoungeOpener != null) {
                                homeLoungeOpener.open(snapshot.server.id.value, agentId, mergedDm.name) { opened ->
                                    if (mergedDm.id in hiddenIds) {
                                        onIntent(SharedHomeIntent.ReopenClosedConversation(mergedDm.id))
                                    }
                                    onOpenConversation(opened.conversationIdentity())
                                    onIntent(SharedHomeIntent.SelectConversation(opened.channel.threadId.value))
                                }
                            } else {
                                val fallbackConversation = conversationsByThreadId[threadId]
                                if (fallbackConversation != null) {
                                    onOpenConversation(fallbackConversation.toThreadLaunchConversationIdentity())
                                    onIntent(SharedHomeIntent.SelectConversation(threadId))
                                } else {
                                    bridge.toast(conversationUnavailableMessage)
                                }
                            }
                        }
                    } else {
                        val conversation = conversationsByThreadId[threadId]
                        // Agent Lounge v0: a standalone or pinned-only Lounge opens a conversation no list holds.
                        val loungeIdentity =
                            if (conversation == null) {
                                agentLoungeConversationIdentityOrNull(snapshot.server.id.value, threadId, snapshot.directMessages)
                            } else {
                                null
                            }
                        if (loungeIdentity != null) {
                            onOpenConversation(loungeIdentity)
                            onIntent(SharedHomeIntent.SelectConversation(threadId))
                        } else if (conversation == null) {
                            bridge.toast(conversationUnavailableMessage)
                        } else {
                            if (mergedDm != null && mergedDm.id in hiddenIds) {
                                onIntent(SharedHomeIntent.ReopenClosedConversation(mergedDm.id))
                            }
                            onOpenConversation(conversation.toThreadLaunchConversationIdentity())
                            onIntent(SharedHomeIntent.SelectConversation(threadId))
                        }
                    }
                },
                onPinnedMemberClick = { identity ->
                    val repository = channelRepository
                    if (repository == null) {
                        bridge.toast(directMessageUnavailableMessage)
                    } else {
                        pinnedMemberOpenScope.launch {
                            val result =
                                repository.openPinnedMemberDirectMessage(identity, snapshot) ?: return@launch
                            when (result) {
                                is DataResult.Content -> {
                                    val conversation = result.data.conversation
                                    onOpenConversation(conversation.toThreadLaunchConversationIdentity())
                                    onIntent(SharedHomeIntent.SelectConversation(conversation.threadId.value))
                                    onIntent(SharedHomeIntent.Refresh)
                                }
                                is DataResult.Empty -> bridge.toast(openDirectMessageFailedMessage)
                                is DataResult.Error ->
                                    bridge.errorToast(
                                        homeDirectMessageOpenFailureMessage(
                                            openDirectMessageFailedMessage,
                                            result.error,
                                            errorStrings,
                                        ),
                                        result.error.traceId,
                                    )
                                is DataResult.NoPermission -> bridge.errorToast(openDirectMessagePermissionMessage, result.blocker)
                            }
                        }
                    }
                },
                onChannelLongPress = { _, _ -> },
                onPinnedSortModeChange = { mode ->
                    onIntent(SharedHomeIntent.SetPinnedSortMode(mode))
                },
                onChannelSortModeChange = { mode ->
                    onIntent(SharedHomeIntent.SetChannelSortMode(mode))
                },
                onJointChannelSortModeChange = { mode ->
                    onIntent(SharedHomeIntent.SetJointChannelSortMode(mode))
                },
                onHideEmptySectionsChange = { hide ->
                    build.raft.app.data.RaftDisplayPreferenceRuntime.setHideEmptySidebarSections(hide)
                },
                onSidebarOrderChange = { target, order ->
                    onIntent(target.toReorderSidebarIntent(order))
                },
                onSidebarSectionSortModeChange = { sectionId, mode ->
                    onIntent(SharedHomeIntent.SetSidebarSectionSortMode(sectionId, mode))
                },
                onSidebarSectionCreate = { request ->
                    onIntent(
                        SharedHomeIntent.CreateSidebarSection(
                            name = request.name,
                            emoji = request.emoji,
                            moveConversationId = request.moveConversationId,
                            insertBeforeSectionId = request.insertBeforeSectionId
                        )
                    )
                },
                onSidebarSectionRename = { sectionId, name, emoji ->
                    onIntent(SharedHomeIntent.RenameSidebarSection(sectionId, name, emoji))
                },
                onSidebarSectionDelete = { sectionId ->
                    onIntent(SharedHomeIntent.DeleteSidebarSection(sectionId))
                },
                onMoveConversationToSection = { conversationId, sectionId ->
                    onIntent(SharedHomeIntent.MoveConversationToSection(conversationId, sectionId))
                },
                onPinToggle = { conversationId ->
                    onIntent(SharedHomeIntent.TogglePinned(conversationId))
                },
                onReadToggle = { conversationId, markUnread ->
                    if (markUnread) {
                        onIntent(SharedHomeIntent.MarkUnread(conversationId))
                    } else {
                        onIntent(SharedHomeIntent.MarkRead(conversationId))
                        mergedDirectMessagesById[conversationId]?.mergedAgentLounge?.channelId?.let { loungeId ->
                            onIntent(SharedHomeIntent.MarkConversationReadAll(loungeId))
                        }
                    }
                },
                onMarkConversationReadAll = { conversationId ->
                    onIntent(SharedHomeIntent.MarkConversationReadAll(conversationId))
                    mergedDirectMessagesById[conversationId]?.mergedAgentLounge?.channelId?.let { loungeId ->
                        onIntent(SharedHomeIntent.MarkConversationReadAll(loungeId))
                    }
                },
                onChannelActivityMuteToggle = { conversationId, muted ->
                    onIntent(SharedHomeIntent.SetChannelActivityMuted(conversationId, muted))
                },
                onCloseDirectMessage = { conversationId ->
                    onIntent(SharedHomeIntent.CloseDirectMessage(conversationId))
                    mergedDirectMessagesById[conversationId]?.mergedAgentLounge?.channelId?.let { loungeId ->
                        onIntent(SharedHomeIntent.CloseDirectMessage(loungeId))
                    }
                },
                onArchiveChannel = { conversationId ->
                    onIntent(SharedHomeIntent.ArchiveChannel(conversationId))
                },
                onLeaveChannel = { conversationId ->
                    leaveConfirmChannel = conversationsByThreadId[conversationId] as? HomeChannelRow
                },
                agentStatusResolver = agentStatusResolver,
                memberAvatarResolver = memberAvatarResolver,
                memberAppBotResolver = memberAppBotResolver,
                pinnedMemberProfileResolver = pinnedMemberProfileResolver,
                diagnosticsLogger = bridge::log,
                scrollToTopRequest = scrollToTopRequest,
                active = active,
                listPositionServerId = listPositionServerId ?: snapshot.server.id.value,
                initialListPosition = initialListPosition,
                onListPositionSnapshot = { serverId, position ->
                    if (latestActive.value) {
                        onListPositionSnapshot(serverId, position)
                    }
                }
            )
            leaveConfirmChannel?.let { target ->
                HomeLeaveChannelConfirm(
                    target = target,
                    serverId = snapshot.server.id,
                    serverMembersStore = serverMembersStore,
                    onDismiss = { leaveConfirmChannel = null },
                    onConfirm = { intent ->
                        leaveConfirmChannel = null
                        onIntent(intent)
                    },
                )
            }
        }
    }
}

@Composable
private fun SharedHomeSessionShell(
    session: StoredAuthSession,
    serverSwitcherExpanded: Boolean,
    serverDrawerEnabled: Boolean = false,
    homeServerAvatarEnabled: Boolean = false,
    serverSwitcherInTopBar: Boolean = false,
    serverDrawerSections: List<RaftHomeServerDrawerSection> = emptyList(),
    scrollToTopRequest: Int = 0,
    serverUnreadCounts: Map<String, Int> = emptyMap(),
    serverActivityExpectedOwner: ReadReceiptOwner? = null,
    serverActivityProjection: HomeServerActivityUnreadProjection = HomeServerActivityUnreadProjection(),
    prefersChineseCommunity: Boolean = false,
    topSafeAreaOverride: Dp? = null,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    // #372: true only for the Loading-with-session branch — renders
    // skeleton rows under the CHANNELS / DIRECT MESSAGES headers so the
    // post-sign-in transition matches the React app instead of a bare shell.
    loadingSkeletons: Boolean = false,
    searchAndActivityOnShellNav: Boolean = false,
    inlineError: RaftHomeInlineError? = null,
    onInlineErrorAction: () -> Unit = {},
    onServerBadgeClick: () -> Unit,
    onServerSwitcherDismiss: () -> Unit,
    onServerDrawerSectionToggle: (String) -> Unit,
    onServerDrawerRetry: (String) -> Unit,
    onServerSelected: (String) -> Unit,
    onServerOrderChange: (List<String>) -> Unit = {},
    onCreateServerClick: () -> Unit,
    onInviteHuman: ((serverId: String) -> Unit)? = null,
    onCreateChannelClick: () -> Unit
) {
    val currentServer = session.currentServerSummary()
    val currentServerId = currentServer?.id?.value ?: session.currentServerId.orEmpty()
    RaftStructuralHomeShell(
        currentServerName = currentServer?.name ?: "No server",
        currentServerId = currentServerId,
        servers =
            currentServer?.let {
                sharedHomeSwitcherItems(
                    session = session,
                    selectedServer = it,
                    serverUnreadCounts = serverUnreadCounts,
                    activityExpectedOwner = serverActivityExpectedOwner,
                    activityProjection = serverActivityProjection,
                )
            }.orEmpty(),
        channels = emptyList(),
        directMessages = emptyList(),
        showLoadingSkeletons = loadingSkeletons,
        inlineError = inlineError,
        serverSwitcherExpanded = serverSwitcherExpanded,
        serverDrawerEnabled = serverDrawerEnabled,
        homeServerAvatarEnabled = homeServerAvatarEnabled,
        serverSwitcherInTopBar = serverSwitcherInTopBar,
        serverDrawerSections = serverDrawerSections,
        prefersChineseCommunity = prefersChineseCommunity,
        topSafeAreaOverride = topSafeAreaOverride,
        showBottomBar = false,
        searchAndActivityOnShellNav = searchAndActivityOnShellNav,
        bottomContentPadding = bottomContentPadding,
        outerPadding = 0.dp,
        labels = RaftHomeShellLabels.localized(),
        onInlineErrorAction = onInlineErrorAction,
        onServerBadgeClick = onServerBadgeClick,
        onServerSwitcherDismiss = onServerSwitcherDismiss,
        onServerDrawerSectionToggle = onServerDrawerSectionToggle,
        onServerDrawerRetry = onServerDrawerRetry,
        onServerSelected = onServerSelected,
        onServerOrderChange = onServerOrderChange,
        onCreateServerClick = onCreateServerClick,
        onInviteHumanClick = homeServerSwitcherInviteClick(currentServer, onInviteHuman, onServerSwitcherDismiss),
        onCreateChannelClick = onCreateChannelClick,
        scrollToTopRequest = scrollToTopRequest,
    )
}
