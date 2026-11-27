package build.raft.app.thread

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.ChannelSettingsAction
import build.raft.app.ChannelSettingsCloseLanding
import build.raft.app.ChannelSettingsRoute
import build.raft.app.DirectMessageDetailsAnnouncementSection
import build.raft.app.MemberDetailIdentity
import build.raft.app.RaftAgentStatusResolver
import build.raft.app.RaftMemberAvatarResolver
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.SharedThreadComposerDraftIntent
import build.raft.app.SharedThreadComposerDraftViewModel
import build.raft.app.ThreadChannelSettingsPanel
import build.raft.app.ThreadComposerQuoteInsert
import build.raft.app.ThreadLaunchConversationIdentity
import build.raft.app.ThreadPageLounge
import build.raft.app.base.BridgeModule
import build.raft.app.buildAppShellThreadPayload
import build.raft.app.channelSettingsCanStopAllAgents
import build.raft.app.channelSettingsDisconnectedToast
import build.raft.app.channelSettingsHidesMembersStrip
import build.raft.app.compat.pager.Pager
import build.raft.app.createChannelSettingsStore
import build.raft.app.data.RaftAgentControlsApi
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SharedHomeStore
import build.raft.app.data.SharedRealtimeRoomLeaseRegistry
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.memberDetailIdentity
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.openRaftPage
import build.raft.app.rememberThreadChannelSettingsPanel
import build.raft.app.rememberThreadPageLounge
import build.raft.app.thread.memberDetailIdentity
import build.raft.app.thread.toRaftTimeCapabilities
import build.raft.app.toRaftTimeCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The conversation's settings links: what the settings drawer and the phone's settings page do
 * beyond the members strip and the preferences, the drawer's panel, the settings page's link and its
 * sources, the leave after the settings page, and how the settings page opens. Called every
 * composition of the page's feature slots from where this code stood, so its remembers and effects
 * keep their keys and their place; only the holder is new each time.
 */
internal class ThreadSettingsLinks(
    val channelSettingsPanel: ThreadChannelSettingsPanel?,
    val openSettingsPage: () -> Boolean,
)

/** The conversation's settings links: see [ThreadSettingsLinks]. */
@Composable
internal fun rememberThreadSettingsLinks(
    bridge: BridgeModule,
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    active: Boolean,
    pager: Pager,
    currentSession: StoredAuthSession?,
    threadViewModel: SharedThreadViewModel,
    renderSlice: SharedThreadRenderSlice,
    payload: RaftThreadScreenPayload,
    renderedThread: RaftThreadScreenPayload,
    sharedHomeStore: SharedHomeStore?,
    sharedHomeStateFlow: StateFlow<SharedHomeState>,
    sharedHomeState: SharedHomeState,
    serverMembersStore: SharedServerMembersStore?,
    threadMembersServerId: ServerId?,
    threadMembersChannelId: ChannelId?,
    threadChannelMembersSlice: SharedServerChannelMembersSlice,
    agentStatusResolver: RaftAgentStatusResolver,
    headerDisplayModel: ThreadHeaderDisplayModel,
    headerProjectionInput: ThreadHeaderProjectionInput,
    conversationDirectoryLookupIds: List<String>,
    directoryConversationProjectionFlow: Flow<Map<String, SharedConversationDirectoryProjection>>,
    directoryConversationProjections: Map<String, SharedConversationDirectoryProjection>,
    conversationPinState: ThreadConversationPinUiState?,
    activityMuteState: ThreadActivityMuteUiState?,
    longMessageCollapseConversationSetting: RaftLongMessageCollapseConversationSetting,
    taskConversationParentChannelId: ChannelId?,
    realtimeRoomLeaseRegistry: SharedRealtimeRoomLeaseRegistry,
    settingsPageLinkKey: String,
    onBack: () -> Unit,
    onOpenThreadPayload: (RaftThreadScreenPayload) -> Unit,
    onOpenMembersPager: (RaftThreadScreenPayload, ServerId, ChannelId, RaftShellStoreScope, Boolean) -> Unit,
    onOpenChannelSettings: (ServerId, ChannelId, RaftShellStoreScope, String) -> Unit,
): ThreadSettingsLinks {
    // What the settings drawer (and the phone's settings page) do beyond the members strip and
    // the preferences: Info with the Slack pick, Guest access and every action through the
    // settings page's store, then what follows them. [onLeave] leaves the conversation after
    // Leave, Delete, Disconnect or Archive.
    fun completeChannelSettingsPanel(
        panel: ThreadChannelSettingsPanel,
        homeContent: SharedHomeContentState,
        onLeave: () -> Unit,
    ): ThreadChannelSettingsPanel {
        val serverId = threadMembersServerId
        val channelId = threadMembersChannelId
        val homeServer = (homeContent as? SharedHomeContentState.Content)?.snapshot?.server
        val routeOwner = sharedStores.currentAuthenticatedRouteOwner(storeScope)
        return panel.copy(
            // The settings page's store: Info with the Slack pick, Guest access and every action.
            storeFactory =
                if (serverId != null && channelId != null && routeOwner != null && routeOwner.serverId == serverId.value) {
                    { scope ->
                        createChannelSettingsStore(ChannelSettingsRoute(serverId, channelId), routeOwner, sharedStores, storeScope, scope) {
                            bridge.log("channel_settings_store drawer $it")
                        }
                    }
                } else {
                    null
                },
            canStopAllAgents = panel.channel?.let { channelSettingsCanStopAllAgents(it, homeServer?.role) } == true,
            agentControlsApi = RaftAgentControlsApi(sharedStores.networkClient(storeScope)),
            hideMembersStrip =
                channelSettingsHidesMembersStrip(panel.channel, homeServer?.role, homeServer?.hideHumansFromMembers == true),
            onProfileSaved = { name, description ->
                channelId?.let { id ->
                    threadViewModel.dispatch(SharedThreadIntent.ApplyChannelProfile(id.value, name, description))
                    sharedHomeStore?.dispatch(SharedHomeIntent.ChannelProfileUpdated(channelId = id, name = name, description = description))
                }
            },
            onChannelChanged = { sharedHomeStore?.dispatch(SharedHomeIntent.Refresh) },
            onChannelClosed = { action, accessLost ->
                if (action == ChannelSettingsAction.Disconnect) {
                    // Web: say which server let go of which channel before moving the reader on.
                    channelSettingsDisconnectedToast(homeServer?.name, panel.channel?.name)?.let(bridge::toast)
                }
                if (accessLost) channelId?.let { sharedStores.publishChannelAccessLost(storeScope, it) }
                sharedHomeStore?.dispatch(SharedHomeIntent.Refresh)
                // Leaving, deleting or archiving takes the person out of the channel (the old drawer did too).
                if (accessLost || action == ChannelSettingsAction.Archive) onLeave()
            },
            onActionFailed = { message -> bridge.toast(message) },
            onOpenChannel = { conversation, unarchived ->
                if (unarchived) sharedHomeStore?.dispatch(SharedHomeIntent.Refresh)
                val next =
                    buildAppShellThreadPayload(
                        conversation = conversation,
                        timeContext = renderedThread.toRaftTimeCapabilities().staticContext(),
                        alignOwnMessagesRight = renderedThread.alignOwnMessagesRight,
                        compactConsecutiveMessages = renderedThread.compactConsecutiveMessages,
                        onWarn = bridge::logWarn,
                    )
                if (next != null) {
                    onOpenThreadPayload(next)
                } else {
                    bridge.logWarn("channel_settings_archived_open_rejected reason=identity_unresolved")
                }
            },
            dmAnnouncement =
                if (renderedThread.surfaceKind == RaftThreadSurfaceKind.DirectMessage && serverId != null && channelId != null) {
                    val dmArchived =
                        ((homeContent as? SharedHomeContentState.Content)?.snapshot)
                            ?.takeIf { it.server.id == serverId }
                            ?.directMessages
                            ?.firstOrNull { it.channelId == channelId }
                            ?.archived
                            ?: renderedThread.channelArchived
                    @Composable {
                        DirectMessageDetailsAnnouncementSection(
                            sharedStores = sharedStores,
                            storeScope = storeScope,
                            serverId = serverId.value,
                            channelId = channelId.value,
                            peerName = headerDisplayModel.title,
                            archived = dmArchived,
                            active = true,
                        )
                    }
                } else {
                    null
                },
        )
    }
    // The settings drawer beyond the name form and actions (web ChannelOverflowMenu): the
    // members strip, Move to section, Notifications and Collapse long messages.
    val channelSettingsPanel =
        rememberThreadChannelSettingsPanel(
            homeContent = sharedHomeState.content,
            serverId = threadMembersServerId,
            channelId = threadMembersChannelId,
            memberSlice = threadChannelMembersSlice,
            pinState = conversationPinState,
            activityMuteState = activityMuteState,
            longMessageCollapse = longMessageCollapseConversationSetting,
            agentStatusResolver = agentStatusResolver,
            writeActionsEnabled = renderSlice.writeActionsEnabled,
            onHomeIntent = { intent -> sharedHomeStore?.dispatch(intent) },
            directMessage = renderedThread.surfaceKind == RaftThreadSurfaceKind.DirectMessage,
        )?.let { panel -> completeChannelSettingsPanel(panel, sharedHomeState.content, onLeave = onBack) }
    // A phone's settings page (ThreadSettingsPage.kt) reads this conversation through its link:
    // the same stores and callbacks as the drawer above, as live sources.
    val settingsPageLink = remember(settingsPageLinkKey) { ThreadSettingsPageLink() }
    DisposableEffect(settingsPageLinkKey, settingsPageLink) {
        ThreadSettingsPageLinks.register(settingsPageLinkKey, settingsPageLink)
        onDispose { ThreadSettingsPageLinks.unregister(settingsPageLinkKey, settingsPageLink) }
    }
    // Leave, Delete, Disconnect or Archive on the page: the conversation goes once it is back on screen.
    var leaveAfterSettingsPage by remember(settingsPageLinkKey) { mutableStateOf(false) }
    LaunchedEffect(leaveAfterSettingsPage, active) {
        if (leaveAfterSettingsPage && active) {
            leaveAfterSettingsPage = false
            onBack()
        }
    }
    val settingsPageMembers =
        remember(serverMembersStore, threadMembersServerId, threadMembersChannelId) {
            if (serverMembersStore != null && threadMembersServerId != null && threadMembersChannelId != null) {
                serverMembersStore.channelMemberSliceFlow(serverId = threadMembersServerId, channelId = threadMembersChannelId)
            } else {
                null
            }
        }
    val settingsPageRenderSlices = remember(threadViewModel) { threadViewModel.renderSliceFlow() }
    val settingsPageSources =
        ThreadSettingsPageSources(
            directMessage = renderedThread.surfaceKind == RaftThreadSurfaceKind.DirectMessage,
            title = headerDisplayModel.title,
            channelKind = headerDisplayModel.channelKind,
            subtitle = headerDisplayModel.subtitle,
            renderSlices = settingsPageRenderSlices,
            initialRenderSlice = renderSlice,
            homeState = sharedHomeStateFlow,
            hasHomeStore = sharedHomeStore != null,
            onHomeIntent = { intent -> sharedHomeStore?.dispatch(intent) },
            projections = directoryConversationProjectionFlow,
            initialProjections = directoryConversationProjections,
            selectProjection = { projections ->
                conversationDirectoryLookupIds.firstNotNullOfOrNull { id ->
                    projections[id]?.takeIf { projection -> headerProjectionInput.acceptsForDisplay(projection) }
                }
            },
            serverId = threadMembersServerId,
            channelId = threadMembersChannelId,
            members = settingsPageMembers ?: MutableStateFlow(threadChannelMembersSlice),
            initialMembers = threadChannelMembersSlice,
            agentStatusResolver = agentStatusResolver,
            collapseServerId = currentSession?.currentServerId,
            collapseConversationId =
                payload.longMessageCollapseConversationId(
                    homeContent = sharedHomeState.content,
                    taskParentChannelId = taskConversationParentChannelId,
                ),
            completePanel = { panel, homeContent, onLeave -> completeChannelSettingsPanel(panel, homeContent, onLeave) },
            onOpenMembers = { add ->
                val serverId = threadMembersServerId
                val channelId = threadMembersChannelId
                if (serverId != null && channelId != null) onOpenMembersPager(renderedThread, serverId, channelId, storeScope, add)
            },
            onLeaveConversation = { leaveAfterSettingsPage = true },
            // The standalone settings page: delete, disconnect or a private leave goes back as the
            // drawer's Leave does; a public leave stays here with the composer's Join.
            onChannelSettingsClosed = { landing, channelId ->
                if (landing != ChannelSettingsCloseLanding.StayInChannel) {
                    currentSession?.currentServerId?.takeIf(String::isNotBlank)?.let { serverId ->
                        realtimeRoomLeaseRegistry.forgetJoinedByUser(serverId, channelId.value)
                    }
                }
                when (landing) {
                    ChannelSettingsCloseLanding.StayInChannel -> Unit
                    ChannelSettingsCloseLanding.StayNotJoined ->
                        threadViewModel.dispatch(SharedThreadIntent.ApplyChannelLeft(channelId.value))
                    ChannelSettingsCloseLanding.LeaveConversation -> leaveAfterSettingsPage = true
                }
            },
        )
    SideEffect { settingsPageLink.sources = settingsPageSources }
    val openSettingsPage: () -> Boolean = {
        val serverId = threadMembersServerId
        val channelId = threadMembersChannelId
        if (serverId != null && channelId != null && renderedThread.surfaceKind == RaftThreadSurfaceKind.Channel) {
            // A channel's settings are its details now (the old settings page only closes for a
            // channel): the announcement bar's settings open them the way the header does.
            onOpenChannelSettings(serverId, channelId, storeScope, settingsPageLinkKey)
            true
        } else serverId != null && channelId != null &&
            pager.openRaftPage(
                pageName = THREAD_SETTINGS_PAGE,
                pageData = threadSettingsPageData(settingsPageLinkKey, serverId, channelId),
                storeScopeOverride = storeScope,
                authenticatedRouteAuthorityRequired = true,
                onMissingScope = { bridge.logWarn("thread_settings_page_open_skip reason=missing_scope") },
            )
    }
    return ThreadSettingsLinks(
        channelSettingsPanel = channelSettingsPanel,
        openSettingsPage = openSettingsPage,
    )
}

/**
 * Agent Lounge v0 for this page, and where a quote of selected text lands with the in-place
 * selection toolbar's Quote. Called every composition of the page's feature slots from where this
 * code stood, so its remembers and effects keep their keys and their place; only the holder is new
 * each time.
 */
internal class ThreadLoungeSelectionQuote(
    val threadLounge: ThreadPageLounge,
    val selectionQuoteTarget: ThreadSelectTextQuoteTarget,
    /** Raft Agent Voice: starts a voice call for an eligible 1:1 Agent DM; null elsewhere. */
    val onStartCall: (() -> Unit)? = null,
)

/** Agent Lounge and the selection quote: see [ThreadLoungeSelectionQuote]. */
@Composable
internal fun rememberThreadLoungeSelectionQuote(
    bridge: BridgeModule,
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    authStore: RaftAuthSessionStore,
    currentSession: StoredAuthSession?,
    currentServerId: String?,
    renderSlice: SharedThreadRenderSlice,
    payload: RaftThreadScreenPayload,
    renderedPayload: RaftThreadScreenPayload,
    renderedThread: RaftThreadScreenPayload,
    renderedTimelineSlice: ThreadTimelineSlice,
    sharedHomeStore: SharedHomeStore?,
    sharedHomeState: SharedHomeState,
    serverMembersStore: SharedServerMembersStore?,
    memberAvatarResolver: RaftMemberAvatarResolver,
    threadLimitLocked: Boolean,
    selectedThreadTab: RaftThreadTab,
    composerDraftViewModel: SharedThreadComposerDraftViewModel,
    quoteReplyHost: ThreadQuoteReplyHost,
    inPlaceTextSelectionHost: ThreadInPlaceTextSelectionHost,
    onOpenThreadPayload: (RaftThreadScreenPayload) -> Unit,
    onReplaceThreadPayload: (RaftThreadScreenPayload) -> Unit,
    onOpenMemberProfile: (RaftThreadScreenPayload, ThreadTimelineSlice, MemberDetailIdentity) -> Unit,
): ThreadLoungeSelectionQuote {
    // Agent Lounge v0: this page as (or inside) an agent's Lounge, and the under-header Lounge / Private switch.
    val loungeAssistantAnswers by
        remember(sharedStores, storeScope) { sharedStores.myAssistantStore(storeScope) }.answers.collectAsState()
    val buildLoungeConversationPayload: (ThreadLaunchConversationIdentity) -> RaftThreadScreenPayload? = { identity ->
        buildAppShellThreadPayload(
            conversation = identity,
            timeContext = renderedPayload.toRaftTimeCapabilities().staticContext(),
            alignOwnMessagesRight = renderedPayload.alignOwnMessagesRight,
            compactConsecutiveMessages = renderedPayload.compactConsecutiveMessages,
            onWarn = bridge::logWarn
        )
    }
    val threadLounge =
        rememberThreadPageLounge(
            payload = renderedThread,
            bridge = bridge,
            storeScope = storeScope,
            flags = sharedStores.serverFeatureFlags,
            serverId = currentServerId,
            serverRole = currentSession?.servers?.firstOrNull { it.id == currentServerId }?.role,
            memberAvatarResolver = memberAvatarResolver,
            personalAssistantAgentIds =
                remember(loungeAssistantAnswers) {
                    loungeAssistantAnswers.values.mapNotNull { it.assistant?.agentId }.toSet()
                },
            onOpenConversation = { identity ->
                buildLoungeConversationPayload(identity)?.let(onOpenThreadPayload)
                    ?: bridge.logWarn("agent_lounge_open_rejected reason=identity_unresolved")
            },
            onReplaceConversation = { identity ->
                buildLoungeConversationPayload(identity)?.let(onReplaceThreadPayload)
                    ?: bridge.logWarn("agent_lounge_switch_rejected reason=identity_unresolved")
            },
            onOpenAgentProfile = { agentId ->
                memberDetailIdentity(agentId, MemberKind.Agent, currentServerId)?.let { identity ->
                    onOpenMemberProfile(renderedThread, renderedTimelineSlice, identity)
                }
            },
            homeSnapshot = (sharedHomeState.content as? SharedHomeContentState.Content)?.snapshot,
            sharedHomeStore = sharedHomeStore,
            authStore = authStore,
            serverMembersStore = serverMembersStore,
            viewerUserId = currentSession?.user?.id,
        )
    // Agent Lounge v0 (web slock #13880 `reopenRouteDm`): opening a closed Lounge, from any
    // entry point, takes it off `hiddenDmIds`, as opening a closed DM on the web does.
    val openedLoungeId =
        threadLounge.lounge?.channelId?.value?.takeIf { renderedThread.surfaceKind == RaftThreadSurfaceKind.Channel }
    LaunchedEffect(openedLoungeId, sharedHomeStore) {
        val loungeId = openedLoungeId ?: return@LaunchedEffect
        sharedHomeStore?.dispatch(SharedHomeIntent.ReopenClosedConversation(loungeId))
    }
    // Where a quote of selected text lands (Select Text > Quote, the mouse selection's Quote).
    val selectionQuoteTarget =
        threadSelectTextQuoteTarget(
            surfaceKind = renderedThread.surfaceKind,
            composerAvailable =
                !threadLimitLocked &&
                    shouldShowThreadComposerForReadyContent(
                        joined = payload.joined && renderSlice.writeActionsEnabled,
                        showComposer = payload.showComposer && renderSlice.writeActionsEnabled,
                        selectedTab = selectedThreadTab.wireValue
                    )
        )
    // The in-place selection toolbar's Quote: the same composer append as Select Text > Quote.
    val selectionQuoteAvailable = selectionQuoteTarget != ThreadSelectTextQuoteTarget.None
    val inPlaceSelectionQuote: ((String) -> Unit)? =
        remember(selectionQuoteAvailable, composerDraftViewModel) {
            if (selectionQuoteAvailable) {
                { quote: String -> composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.AppendQuote(quote)) }
            } else {
                null
            }
        }
    val inPlaceSelectionQuoteInsert: ((ThreadComposerQuoteInsert) -> Unit)? =
        remember(selectionQuoteAvailable, quoteReplyHost) {
            if (selectionQuoteAvailable) quoteReplyHost::requestInsertQuote else null
        }
    SideEffect {
        inPlaceTextSelectionHost.quoteIntoComposer = inPlaceSelectionQuote
        inPlaceTextSelectionHost.insertQuoteIntoComposer = inPlaceSelectionQuoteInsert
    }
    val onStartCall =
        build.raft.app.agents.rememberThreadDmAgentVoiceCallAction(
            payload = renderedThread,
            sharedStores = sharedStores,
            storeScope = storeScope,
            serverId = currentServerId,
            serverRole = currentSession?.servers?.firstOrNull { it.id == currentServerId }?.role,
            serverMembersStore = serverMembersStore,
            memberAvatarResolver = memberAvatarResolver,
        )
    return ThreadLoungeSelectionQuote(
        threadLounge = threadLounge,
        selectionQuoteTarget = selectionQuoteTarget,
        onStartCall = onStartCall,
    )
}
