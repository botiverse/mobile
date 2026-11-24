package build.raft.app

import build.raft.app.data.isMarkedUnreadIn
import build.raft.app.data.HomeRowRestoredHint
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.HomeConversationRow
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeParentThreadUnread
import build.raft.app.data.HomeServerDrawerState
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.HomeServerActivityUnreadProjection
import build.raft.app.data.SharedHomeShellIntent
import build.raft.app.data.SharedHomeShellState
import build.raft.app.data.SharedHomeShellStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.ReadReceiptOwner
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.model.ConversationKind
import build.raft.app.model.ChannelId
import build.raft.app.model.Presence
import build.raft.app.model.RaftSlackSyncProblem
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import build.raft.app.data.map

internal fun SharedHomeShellState.withoutServerDrawerRuntimeForAppShellRender(): SharedHomeShellState =
    copy(
        home =
            home.copy(
                serverSwitcherExpanded = false,
                serverDrawer = HomeServerDrawerState(),
                serverDrawerCommandIntent = build.raft.app.data.HomeServerDrawerCommandIntent()
            )
    )

private data class AppShellServerDrawerProjection(
    val commandIntent: build.raft.app.data.HomeServerDrawerCommandIntent,
    val drawer: HomeServerDrawerState,
    val unreadCounts: Map<String, Int>,
    val activityUnread: HomeServerActivityUnreadProjection,
)

private data class DrawerSettledObservation(
    val isOpen: Boolean,
    val progress: Float
)

private fun DrawerSettledObservation.isSettled(desiredOpen: Boolean): Boolean =
    if (desiredOpen) isOpen && progress >= 0.99f else !isOpen && progress <= 0.01f

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun AppShellServerDrawerHost(
    homeShellStore: SharedHomeShellStore,
    bridge: build.raft.app.base.BridgeModule,
    labels: RaftHomeShellLabels,
    userHeader: RaftServerDrawerUserHeader,
    topSafeArea: Dp,
    bottomSafeArea: Dp,
    serverMembersStore: SharedServerMembersStore?,
    serverActivityExpectedOwner: ReadReceiptOwner?,
    selectedTab: String,
    modalGestureBlocked: Boolean,
    dismissLabel: String,
    onServerClick: (String) -> Unit,
    onConversationClick: (String, String) -> Unit,
    onProgrammaticCloseFenceChanged: (((String) -> Unit)?) -> Unit,
    content: @Composable () -> Unit
) {
    val projectionFlow =
        remember(homeShellStore) {
            homeShellStore.state
                .map { shellState ->
                    AppShellServerDrawerProjection(
                        commandIntent = shellState.home.serverDrawerCommandIntent,
                        drawer = shellState.home.serverDrawer,
                        unreadCounts = shellState.home.serverUnreadCounts,
                        activityUnread = shellState.home.serverActivityUnread,
                    )
                }
                .distinctUntilChanged()
        }
    val initialProjection =
        remember(homeShellStore) {
            val home = homeShellStore.state.value.home
            AppShellServerDrawerProjection(
                home.serverDrawerCommandIntent,
                home.serverDrawer,
                home.serverUnreadCounts,
                home.serverActivityUnread,
            )
        }
    val projection by projectionFlow.collectAsState(initialProjection)
    val currentIntent by rememberUpdatedState(projection.commandIntent)
    val drawerSections =
        remember(
            projection.drawer,
            projection.unreadCounts,
            projection.activityUnread,
            serverActivityExpectedOwner,
        ) {
            projection.drawer.toRaftHomeServerDrawerHostedSections(
                serverUnreadCounts = projection.unreadCounts,
                activityExpectedOwner = serverActivityExpectedOwner,
                activityProjection = projection.activityUnread,
            )
        }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val drawerWidth = homeServerDrawerWidth(maxWidth = maxWidth, maxHeight = maxHeight)
        val drawerState =
            rememberRaftMoveableDrawerState(
                initiallyOpen = false,
                drawerWidth = drawerWidth
            )
        val scrimWidth = (maxWidth - drawerWidth * drawerState.progress).coerceAtLeast(0.dp)
        // The drawer slides the shell (and its Compose bar) aside; the iOS system tab bar stays
        // put above the page, so it hides while any of the drawer shows.
        RaftNativeTabBarCover(active = drawerState.progress > 0.01f)
        var commandState by remember(drawerState) { mutableStateOf(HomeServerDrawerCommandState()) }
        val pointerPolicy = commandState.pointerPolicy(projection.commandIntent)
        val openGestureAdmission =
            HomeServerDrawerOpenGestureAdmission(
                homeSurfaceActive = selectedTab == "home",
                pointerOwnerActive = pointerPolicy.appEdgeEnabled,
                modalPresented = modalGestureBlocked
            )

        fun requestProgrammaticClose(source: String) {
            if (!currentIntent.desiredOpen) return
            commandState = commandState.beginProgrammaticClose()
            bridge.log(
                "server_drawer_programmatic_close_fenced generation=${currentIntent.generation} source=$source"
            )
            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.DismissServerSwitcher))
        }
        val currentCloseRequest by rememberUpdatedState<(String) -> Unit> { source ->
            requestProgrammaticClose(source)
        }
        val programmaticCloseFence = remember {
            { source: String -> currentCloseRequest(source) }
        }
        DisposableEffect(programmaticCloseFence, onProgrammaticCloseFenceChanged) {
            onProgrammaticCloseFenceChanged(programmaticCloseFence)
            onDispose { onProgrammaticCloseFenceChanged(null) }
        }

        LaunchedEffect(drawerState, projection.commandIntent.generation) {
            val intent = projection.commandIntent
            if (intent.generation == 0L && !intent.desiredOpen) return@LaunchedEffect
            val generation = intent.generation
            commandState = commandState.pendingAdmission(intent)
            bridge.log(
                "server_drawer_command_pending_admission generation=$generation desiredOpen=${intent.desiredOpen} " +
                    "selectedTab=$selectedTab progress=${drawerState.progress}"
            )
            if (intent.desiredOpen) drawerState.open() else drawerState.close()
            if (currentIntent.generation != generation || currentIntent.desiredOpen != intent.desiredOpen) {
                return@LaunchedEffect
            }
            commandState = commandState.markSubmitted(generation, intent.desiredOpen)
            bridge.log(
                "server_drawer_command_submitted generation=$generation desiredOpen=${intent.desiredOpen} " +
                    "selectedTab=$selectedTab progress=${drawerState.progress}"
            )
            snapshotFlow { DrawerSettledObservation(drawerState.isOpen, drawerState.progress) }
                .first { observation -> observation.isSettled(intent.desiredOpen) }
            if (currentIntent.generation != generation || currentIntent.desiredOpen != intent.desiredOpen) {
                return@LaunchedEffect
            }
            val previousInteractive = commandState.interactiveGeneration
            commandState = commandState.settle(generation, intent.desiredOpen)
            if (commandState.interactiveGeneration == generation && previousInteractive != generation) {
                bridge.log(
                    "server_drawer_interactive generation=$generation " +
                        "rowKey=${commandState.interactiveRowKey.orEmpty()} selectedTab=$selectedTab"
                )
            }
            bridge.log(
                "server_drawer_command_settled generation=$generation terminal=" +
                    "${if (intent.desiredOpen) "settled_open" else "settled_closed"} " +
                    "isOpen=${drawerState.isOpen} progress=${drawerState.progress}"
            )
        }

        LaunchedEffect(drawerState) {
            snapshotFlow { DrawerSettledObservation(drawerState.isOpen, drawerState.progress) }
                .collect { observation ->
                    if (observation.isSettled(desiredOpen = false)) {
                        val previousDismissCount = commandState.gestureDismissCount
                        val generation = commandState.currentIntent.generation
                        commandState =
                            commandState
                                .establishClosedOwner(currentIntent)
                                .nativeSettledClosedWithoutCommand()
                        if (commandState.gestureDismissCount > previousDismissCount) {
                            bridge.log("server_drawer_gesture_closed generation=$generation")
                            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.DismissServerSwitcher))
                        }
                    }
                }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            RaftMoveableDrawer(
                state = drawerState,
                scrimColor = Color.Transparent,
                userScrollEnabled = pointerPolicy.nativePagerEnabled,
                drawerContent = {
                    HomeServerDrawerHostedPanel(
                        sections = drawerSections,
                        labels = labels,
                        userHeader = userHeader,
                        topSafeArea = topSafeArea,
                        bottomSafeArea = bottomSafeArea,
                        serverMembersStore = serverMembersStore,
                        rowObservationGeneration =
                            commandState.currentIntent.generation.takeIf {
                                commandState.currentIntent.desiredOpen &&
                                    commandState.phase in
                                        setOf(
                                            HomeServerDrawerCommandPhase.SubmittedOpen,
                                            HomeServerDrawerCommandPhase.SettledOpen
                                        )
                            },
                        interactiveGeneration = commandState.interactiveGeneration,
                        authoritativeOpenGeneration =
                            projection.commandIntent.generation.takeIf { projection.commandIntent.desiredOpen },
                        onServerClick = onServerClick,
                        onSectionToggle = { serverId ->
                            homeShellStore.dispatch(
                                SharedHomeShellIntent.Home(
                                    SharedHomeIntent.ToggleServerDrawerSection(ServerId(serverId))
                                )
                            )
                        },
                        onRetry = { serverId ->
                            homeShellStore.dispatch(
                                SharedHomeShellIntent.Home(SharedHomeIntent.RetryServerDrawerSection(ServerId(serverId)))
                            )
                        },
                        onConversationClick = { generation, serverId, conversationId ->
                            if (commandState.acceptsConversationClick(generation, currentIntent)) {
                                commandState = commandState.beginProgrammaticClose()
                                onConversationClick(serverId, conversationId)
                            }
                        },
                        onFirstInteractiveRow = { generation, rowKey ->
                            if (!commandState.acceptsRowObservation(generation, currentIntent)) {
                                return@HomeServerDrawerHostedPanel
                            }
                            val previousInteractive = commandState.interactiveGeneration
                            commandState = commandState.rowLaidOut(generation, rowKey)
                            if (commandState.interactiveGeneration == generation && previousInteractive != generation) {
                                bridge.log(
                                    "server_drawer_interactive generation=$generation rowKey=$rowKey selectedTab=$selectedTab"
                                )
                            }
                        }
                    )
                },
                content = {
                    val contentAccessibilityModifier =
                        if (drawerState.progress > 0.01f) {
                            Modifier.clearAndSetSemantics { invisibleToUser() }
                        } else {
                            Modifier
                        }
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .then(contentAccessibilityModifier)
                    ) {
                        CompositionLocalProvider(
                            LocalRaftHomeDrawerOpenGesture provides
                                RaftHomeDrawerOpenGesture(
                                    admission = openGestureAdmission,
                                    onOpen = {
                                        if (
                                            selectedTab == "home" &&
                                            pointerPolicy.appEdgeEnabled &&
                                            !modalGestureBlocked &&
                                            !currentIntent.desiredOpen
                                        ) {
                                            commandState = commandState.beginProgrammaticOpen()
                                            bridge.log(
                                                "server_drawer_input source=app_shell_content selectedTab=$selectedTab"
                                            )
                                            homeShellStore.dispatch(
                                                SharedHomeShellIntent.Home(
                                                    SharedHomeIntent.ToggleServerSwitcher
                                                )
                                            )
                                        }
                                    }
                                )
                        ) {
                            content()
                        }
                    }
                }
            )
            if (drawerState.progress > 0.01f) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(scrimWidth)
                            .background(Color.Black.copy(alpha = 0.28f * drawerState.progress)) // raft-color: drawer dimming scrim, black in both modes
                            .semantics {
                                contentDescription = dismissLabel
                                traversalIndex = 1f
                            }
                            // Raw clickable on purpose: the drawer's scrim, a tap outside that
                            // closes it, with no press of its own.
                            // raw-click: scrim, a tap outside only dismisses; not a control
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button
                            ) {
                                requestProgrammaticClose("scrim_compose")
                            }
                )
            }
        }
    }
}

/**
 * The server switcher's "Invite Human" action for [server], or null when the
 * viewer lacks `inviteMembers` (web `ServerSwitcherMenu`). Like web, it closes
 * the switcher before opening the dialog.
 */
internal fun homeServerSwitcherInviteClick(
    server: ServerSummary?,
    onInviteHuman: ((serverId: String) -> Unit)?,
    onDismissSwitcher: () -> Unit
): (() -> Unit)? {
    if (onInviteHuman == null || server == null || !serverSwitcherShowsInviteHuman(server)) return null
    val serverId = server.id.value
    return {
        onDismissSwitcher()
        onInviteHuman(serverId)
    }
}

internal fun StoredAuthSession.currentServerSummary(): ServerSummary? {
    val currentId = currentServerId
    return servers.firstOrNull { it.id == currentId }?.toServerSummary()
        ?: servers.firstOrNull()?.toServerSummary()
}

private fun ServerSummary.toSharedSwitcherItem(
    serverUnreadCounts: Map<String, Int> = emptyMap(),
    activityExpectedOwner: ReadReceiptOwner? = null,
    activityProjection: HomeServerActivityUnreadProjection = HomeServerActivityUnreadProjection(),
): RaftServerSwitcherItem {
    val serverOwner = activityExpectedOwner?.copy(serverId = id.value)
    return RaftServerSwitcherItem(
        id = id.value,
        name = name,
        slug = slug ?: description?.removePrefix("/") ?: id.value,
        avatarUrl = avatarUrl,
        broaderUnreadCount = serverUnreadCounts[id.value] ?: unreadCount,
        activityUnreadCount = activityProjection.countFor(serverOwner),
        serverPushMuted = activityProjection.serverPushMutedFor(serverOwner),
        publicPageLive = publicPageLive,
    )
}

internal fun sharedHomeSwitcherItems(
    session: StoredAuthSession,
    selectedServer: ServerSummary,
    serverUnreadCounts: Map<String, Int>,
    activityExpectedOwner: ReadReceiptOwner?,
    activityProjection: HomeServerActivityUnreadProjection,
): List<RaftServerSwitcherItem> =
    session.servers.map { server ->
        server.toServerSummary().let { summary ->
            if (summary.id == selectedServer.id) {
                selectedServer.copy(
                    avatarUrl = selectedServer.avatarUrl ?: summary.avatarUrl,
                    // publicPageLive only comes from the server list (session), never
                    // from a Home snapshot, so the session row stays authoritative.
                    publicPageLive = summary.publicPageLive
                )
            } else {
                summary
            }
        }.toSharedSwitcherItem(
            serverUnreadCounts = serverUnreadCounts,
            activityExpectedOwner = activityExpectedOwner,
            activityProjection = activityProjection,
        )
    }.ifEmpty {
        listOf(
            selectedServer.toSharedSwitcherItem(
                serverUnreadCounts = serverUnreadCounts,
                activityExpectedOwner = activityExpectedOwner,
                activityProjection = activityProjection,
            )
        )
    }

internal data class SharedHomeConversationRowSeed(
    val id: String,
    val kind: ConversationKind,
    val unreadCount: Int = 0,
    val lastMessageAt: String? = null,
    val lastMessagePreview: String? = null,
    val lastMessageSenderName: String? = null,
    val fallbackAvatarWireValue: String? = null,
    val peerType: String? = null,
    val fallbackPresenceMemberId: String? = null,
    val fallbackName: String = "",
    val fallbackChannelType: String? = null,
    val fallbackDescription: String? = null,
    val fallbackJoined: Boolean = true,
    val fallbackArchived: Boolean = false,
    val fallbackActivityMuted: Boolean = false,
    val fallbackActivityMuteSupported: Boolean = false,
    val fallbackBridgeProvider: String? = null,
    val fallbackSlackSyncProblem: RaftSlackSyncProblem? = null,
    val fallbackGuestVisible: Boolean = false,
    val fallbackHasExternalRelation: Boolean = false,
    /** Unread summary `hasMention` (or a followed thread's), carried by the store. */
    val mentioned: Boolean = false,
    /** Followed threads under this channel with new replies, carried by the store. */
    val followedThreadUnreadCount: Int = 0,
    /** Marked unread with no count ([HomeSnapshot.markedUnreadIds]), carried by the store. */
    val markedUnread: Boolean = false
)

internal data class SharedHomeConversationRowProjector(
    val directoryProjections: Map<String, SharedConversationDirectoryProjection> = emptyMap(),
    val draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    /** task #166: cold-start hints from [SharedHomeContentState.Content.displayRowHints]. */
    val restoredRowHints: Map<String, HomeRowRestoredHint> = emptyMap(),
    /**
     * [HomeSnapshot.followedThreadUnread] folded per parent channel (web `threadUnread`): the row's
     * thread count and the thread's @.
     */
    val threadUnreadByParent: Map<ChannelId, HomeParentThreadUnread> = emptyMap(),
    /** [HomeSnapshot.markedUnreadIds]: rows marked unread with no count (botiverse/slock #11795). */
    val markedUnreadIds: Set<String> = emptySet(),
) {
    fun project(conversation: HomeConversationRow): RaftHomeConversationItem {
        // Only channel rows carry the followed-thread segment (web `SidebarChannelItem`).
        val threadUnread =
            if (conversation is HomeChannelRow) threadUnreadByParent[conversation.channelId] else null
        return project(
            conversation.toSharedHomeConversationRowSeed()
                .copy(
                    mentioned = conversation.mentionCount > 0 || threadUnread?.hasMention == true,
                    followedThreadUnreadCount = threadUnread?.count ?: 0,
                    markedUnread = conversation.isMarkedUnreadIn(markedUnreadIds)
                )
                .withRestoredHint(
                    // A known count (read prediction, realtime) is newer than anything restored.
                    restoredRowHints[conversation.threadId.value]?.takeIf { !conversation.unreadCountKnown }
                )
        )
    }

    fun project(seed: SharedHomeConversationRowSeed): RaftHomeConversationItem =
        seed.toSharedHomeConversationItem(
            directoryProjection = directoryProjections[seed.id],
            draftProjection = draftProjection
        )
}

internal fun HomeServerDrawerState.toRaftHomeServerDrawerSections(
    serverUnreadCounts: Map<String, Int> = emptyMap(),
    activityExpectedOwner: ReadReceiptOwner? = null,
    activityProjection: HomeServerActivityUnreadProjection = HomeServerActivityUnreadProjection(),
): List<RaftHomeServerDrawerSection> {
    val projector = SharedHomeConversationRowProjector()
    return sections.values.map { section ->
        val snapshot = section.content?.snapshot
        val sidebarOrder = snapshot?.sidebarOrder
        RaftHomeServerDrawerSection(
            server =
                section.server.toSharedSwitcherItem(
                    serverUnreadCounts = serverUnreadCounts,
                    activityExpectedOwner = activityExpectedOwner,
                    activityProjection = activityProjection,
                ),
            expanded = section.server.id in expandedServerIds,
            status = section.status,
            requestGeneration = section.requestGeneration,
            errorCode = section.errorCode,
            channels = snapshot?.channels.orEmpty().map(projector::project),
            directMessages = snapshot?.directMessages.orEmpty().map(projector::project),
            channelOrder = sidebarOrder?.channelOrder.orEmpty(),
            channelSortMode = sidebarOrder?.channelSortMode ?: "manual",
            dmOrder = sidebarOrder?.dmOrder.orEmpty(),
            dmSortMode = sidebarOrder?.dmSortMode ?: "manual",
            pinnedChannelIds = sidebarOrder?.pinnedChannelIds.orEmpty(),
            pinnedAgentIds = sidebarOrder?.pinnedAgentIds.orEmpty(),
            pinnedOrder = sidebarOrder?.pinnedOrder.orEmpty(),
            pinnedItems = sidebarOrder?.pinned.orEmpty(),
            pinnedSortMode = sidebarOrder?.pinnedSortMode ?: "manual",
            hiddenDmIds = sidebarOrder?.hiddenDmIds.orEmpty()
        )
    }
}

internal fun HomeServerDrawerState.toRaftHomeServerDrawerHostedSections(
    serverUnreadCounts: Map<String, Int> = emptyMap(),
    activityExpectedOwner: ReadReceiptOwner? = null,
    activityProjection: HomeServerActivityUnreadProjection = HomeServerActivityUnreadProjection(),
): List<RaftHomeServerDrawerHostedSection> =
    sections.values.map { section ->
        RaftHomeServerDrawerHostedSection(
            header =
                RaftHomeServerDrawerSection(
                    server =
                        section.server.toSharedSwitcherItem(
                            serverUnreadCounts = serverUnreadCounts,
                            activityExpectedOwner = activityExpectedOwner,
                            activityProjection = activityProjection,
                        ),
                    expanded = section.server.id in expandedServerIds,
                    status = section.status,
                    requestGeneration = section.requestGeneration,
                    errorCode = section.errorCode
                ),
            normalizedContent = section.normalizedContent,
            hasCachedConversations =
                section.content?.snapshot?.let { it.channels.isNotEmpty() || it.directMessages.isNotEmpty() } == true
        )
    }

internal fun SharedHomeConversationRowSeed.withRestoredHint(hint: HomeRowRestoredHint?): SharedHomeConversationRowSeed {
    if (hint == null) return this
    val usePreview = lastMessagePreview.isNullOrBlank() && !hint.lastMessagePreview.isNullOrBlank()
    val restoredUnread = if (unreadCount > 0) unreadCount else hint.unreadCount.coerceAtLeast(0)
    return copy(
        unreadCount = restoredUnread,
        // The last-known @ mark rides with the last-known count it belongs to.
        mentioned = mentioned || (restoredUnread > 0 && hint.mentionCount > 0),
        lastMessagePreview = if (usePreview) hint.lastMessagePreview else lastMessagePreview,
        lastMessageAt = if (usePreview) hint.lastMessageAt ?: lastMessageAt else lastMessageAt,
        lastMessageSenderName = if (usePreview) hint.lastMessageSenderName else lastMessageSenderName
    )
}

private fun HomeConversationRow.toSharedHomeConversationRowSeed(): SharedHomeConversationRowSeed =
    SharedHomeConversationRowSeed(
        id = threadId.value,
        kind = kind,
        unreadCount = unreadCount,
        lastMessageAt = lastMessageAt,
        lastMessagePreview = lastMessagePreview,
        lastMessageSenderName = lastMessageSenderName,
        fallbackAvatarWireValue = directMessagePeerAvatarWireValue?.takeIf { it.isNotBlank() },
        peerType = directMessagePeerType?.takeIf { it.isNotBlank() },
        fallbackPresenceMemberId = presenceMemberId?.value,
        fallbackName = name,
        fallbackChannelType = channelType?.takeIf { it.isNotBlank() },
        fallbackDescription = subtitle?.takeIf { it.isNotBlank() },
        fallbackJoined = joined && permissions.canRead,
        fallbackArchived = archived,
        fallbackActivityMuted = activityMuted,
        fallbackActivityMuteSupported = activityMuteSupported,
        fallbackBridgeProvider = bridgeProvider,
        fallbackSlackSyncProblem = slackSyncProblem,
        fallbackGuestVisible = guestVisible,
        fallbackHasExternalRelation = hasExternalRelation
    )

private fun SharedHomeConversationRowSeed.toSharedHomeConversationItem(
    draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    directoryProjection: SharedConversationDirectoryProjection? = null
): RaftHomeConversationItem {
    val title = resolvedDirectoryTitle(directoryProjection)
    val projectedChannelType =
        directoryProjection
            ?.takeIf { projection -> projection.conversationKind == kind }
            ?.channelKind
            ?.takeIf { it.isNotBlank() }
    return RaftHomeConversationItem(
        id = id,
        name = title,
        // The row seed is the Home identity source. Directory projection enriches display/state
        // fields, but cannot rewrite the row's conversation kind (§5).
        // task #444: serialize through the LOSSLESS wire kind, not the surface projection —
        // toRaftSurfaceKind() folds Joint into the channel surface (contract rule: the
        // surface projection is lossy), which made the Joint Channels section filter
        // (kind == "joint") never match and the section vanish. The surface fallback only
        // covers kinds without a wire channel kind (Inbox/System), which render as channel
        // surfaces anyway.
        kind = kind.toWireChannelKindOrNull() ?: kind.toRaftSurfaceKind(),
        channelType = projectedChannelType ?: fallbackChannelType,
        unreadCount = unreadCount,
        lastMessageAt = lastMessageAt,
        lastMessagePreview = lastMessagePreview,
        lastMessageSenderName = lastMessageSenderName,
        description = directoryProjection?.subtitle ?: fallbackDescription,
        avatarWireValue = directoryProjection?.avatarWireValue ?: fallbackAvatarWireValue,
        peerType = peerType,
        presenceMemberId = directoryProjection?.peerIdentity?.memberId?.value ?: fallbackPresenceMemberId,
        hasDraft = draftProjection.hasRootDraft(id),
        joined = directoryProjection?.joined ?: fallbackJoined,
        archived = directoryProjection?.archived ?: fallbackArchived,
        activityMuted = directoryProjection?.activityMuted ?: fallbackActivityMuted,
        activityMuteSupported = directoryProjection?.activityMuteSupported ?: fallbackActivityMuteSupported,
        bridgeProvider = directoryProjection?.bridgeProvider ?: fallbackBridgeProvider,
        // The sync state comes with the bridge it was read with.
        slackSyncProblem =
            if (directoryProjection?.bridgeProvider != null) directoryProjection.slackSyncProblem else fallbackSlackSyncProblem,
        // The Home row seed is the canonical channel row; a directory projection from a
        // sparse source (inbox) never carries guestVisible, so it may only add, not clear.
        guestVisible = fallbackGuestVisible || directoryProjection?.guestVisible == true,
        // Same rule: only the canonical channel row carries it (inbox projections never do).
        hasExternalRelation = fallbackHasExternalRelation || directoryProjection?.hasExternalRelation == true,
        mentioned = mentioned,
        followedThreadUnreadCount = followedThreadUnreadCount,
        markedUnread = markedUnread
    )
}

private fun SharedHomeConversationRowSeed.resolvedDirectoryTitle(
    directoryProjection: SharedConversationDirectoryProjection?
): String =
    directoryProjection
        ?.title
        ?.takeUnless { title -> title.isGenericHomeDirectoryTitle(directoryProjection) }
        ?: fallbackName

private fun String.isGenericHomeDirectoryTitle(
    directoryProjection: SharedConversationDirectoryProjection
): Boolean {
    val candidate = threadHeaderCandidateTitle(this) ?: return true
    val identityIds =
        directoryProjection.aliases +
            directoryProjection.canonicalChannelId +
            setOfNotNull(directoryProjection.parentChannelId, directoryProjection.parentMessageId)
    return candidate.isGenericThreadHeaderFallbackLabel() ||
        threadHeaderIdentityLikeTitle(candidate, identityIds)
}

private fun Presence?.toSharedPresence(): RaftAppPresence? =
    when (this) {
        Presence.Active -> RaftAppPresence.Online
        Presence.Away -> RaftAppPresence.Unknown
        Presence.Offline -> RaftAppPresence.Offline
        null -> null
    }
