package build.raft.app

import build.raft.app.compat.json.JSONObject
import build.raft.app.data.RaftAgentLounge
import build.raft.app.data.RaftAgentLounges
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedHomeContentState
import build.raft.app.model.ServerId
import build.raft.app.resources.Res
import build.raft.app.resources.agent_lounge_label
import build.raft.app.resources.channel_details_title
import build.raft.app.resources.desc
import build.raft.app.resources.resolve
import build.raft.app.resources.settings_title
import build.raft.app.resources.task_conversation_search
import build.raft.app.resources.task_conversation_view_in_channel
import build.raft.app.resources.thread_action_search
import build.raft.app.resources.thread_action_search_in_thread
import build.raft.app.resources.thread_channel_title
import build.raft.app.resources.thread_direct_message_title
import build.raft.app.resources.thread_members_heading
import build.raft.app.resources.thread_reply_title
import build.raft.app.resources.thread_side_card_files_open
import build.raft.app.resources.thread_side_card_tasks_open

/**
 * #proj-mobile:#282: populates a pushed iOS native navigation bar synchronously before
 * `UINavigationController.pushViewController`, so the transition animates with the destination's
 * title, channel glyph / DM avatar tile, and buttons instead of an empty `< Back` bar that jumps
 * ~0.25s later when Compose finishes its first frame.
 *
 * Exported to Swift as `RaftNativeNavBarPrefillKt.raftPrefillNativeNavBar(bridge:pageName:pageDataJson:)`.
 */
fun raftPrefillNativeNavBar(
    bridge: RaftNativeNavBarBridge,
    pageName: String,
    pageDataJson: String,
) {
    if (!bridge.hostDrawsBar) return
    val head =
        runCatching { buildNativeNavBarPrefillHead(pageName = pageName, pageDataJson = pageDataJson) }
            .getOrNull()
            ?: return
    bridge.prefill(head)
}

internal data class RaftNativeNavBarPrefillStrings(
    val replyThreadTitle: String,
    val channelTitle: String,
    val directMessageTitle: String,
    val searchLabel: String,
    val searchInThreadLabel: String,
    val searchInTaskLabel: String,
    val viewInChannelLabel: String,
    val settingsLabel: String,
    val openTasksLabel: String,
    val openFilesLabel: String,
    val loungeLabel: String,
    val loungeTitleForAgent: (String) -> String,
    val channelDetailsTitle: String,
    val membersTitle: String,
)

private fun defaultNativeNavBarPrefillStrings(): RaftNativeNavBarPrefillStrings =
    RaftNativeNavBarPrefillStrings(
        replyThreadTitle = Res.string.thread_reply_title.desc().resolve(),
        channelTitle = Res.string.thread_channel_title.desc().resolve(),
        directMessageTitle = Res.string.thread_direct_message_title.desc().resolve(),
        searchLabel = Res.string.thread_action_search.desc().resolve(),
        searchInThreadLabel = Res.string.thread_action_search_in_thread.desc().resolve(),
        searchInTaskLabel = Res.string.task_conversation_search.desc().resolve(),
        viewInChannelLabel = Res.string.task_conversation_view_in_channel.desc().resolve(),
        settingsLabel = Res.string.settings_title.desc().resolve(),
        openTasksLabel = Res.string.thread_side_card_tasks_open.desc().resolve(),
        openFilesLabel = Res.string.thread_side_card_files_open.desc().resolve(),
        loungeLabel = Res.string.agent_lounge_label.desc().resolve(),
        loungeTitleForAgent = ::agentLoungeTitleNow,
        channelDetailsTitle = Res.string.channel_details_title.desc().resolve(),
        membersTitle = Res.string.thread_members_heading.desc().resolve(),
    )

internal fun buildNativeNavBarPrefillHead(
    pageName: String,
    pageDataJson: String,
    strings: RaftNativeNavBarPrefillStrings = defaultNativeNavBarPrefillStrings(),
): RaftNativeNavBarHead? =
    when (pageName) {
        THREAD_PAGE -> buildThreadNativeNavBarPrefillFromPageData(pageDataJson = pageDataJson, strings = strings)
        THREAD_MEMBERS_PAGE ->
            RaftNativeNavBarHead(
                title = strings.membersTitle,
                subtitle = null,
                actions = emptyList(),
                extras = RaftNativeNavBarTitleExtras(),
            )
        CHANNEL_SETTINGS_PAGE ->
            RaftNativeNavBarHead(
                title = strings.channelDetailsTitle,
                subtitle = null,
                actions = emptyList(),
                extras = RaftNativeNavBarTitleExtras(),
            )
        else -> null
    }

private fun buildThreadNativeNavBarPrefillFromPageData(
    pageDataJson: String,
    strings: RaftNativeNavBarPrefillStrings,
): RaftNativeNavBarHead? {
    val params = runCatching { JSONObject(pageDataJson) }.getOrNull() ?: return null
    val payloadText = params.optString("payload").trim().takeIf { it.isNotEmpty() } ?: return null
    val instanceId = params.optString("instanceId")
    val payload = parseThreadLaunchPayload(payloadText, instanceId) ?: return null
    val storeScope = params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
    val sharedStores = RaftSharedStoreRegistry.currentOrNull()
    val routeOwner = params.authenticatedRouteOwnerOrNull()
    val liveOwner = sharedStores?.currentAuthenticatedRouteOwner(storeScope)
    val serverId =
        threadHeaderProjectionServerId(
            sessionServerId = liveOwner?.serverId ?: routeOwner?.serverId,
            scopeInitialServerId = storeScope.initialServerId,
        )?.let(::ServerId)
    val accountId = liveOwner?.accountId ?: routeOwner?.accountId
    val headerProjectionInput = payload.threadHeaderProjectionRoutePayload().threadHeaderProjectionInput()
    val initialProjections =
        threadHeaderInitialProjectionMap(
            provider = sharedStores?.conversationIdentityProvider(storeScope),
            serverId = serverId,
            input = headerProjectionInput,
        )
    val directoryProjection =
        headerProjectionInput.lookupIds.firstNotNullOfOrNull { id ->
            initialProjections[id]?.takeIf(headerProjectionInput::acceptsForDisplay)
        }
    val knownCandidate =
        sharedStores?.let { stores ->
            threadHeaderKnownCandidate(
                resolver = stores.knownEntityResolver(storeScope),
                accountId = accountId,
                serverId = serverId,
                input = headerProjectionInput,
            )
        }
    val serverPublicPageLive =
        (sharedStores?.sharedHomeStore(storeScope)?.state?.value?.content as? SharedHomeContentState.Content)
            ?.snapshot
            ?.server
            ?.takeIf { it.id == serverId }
            ?.publicPageLive == true
    val pageLounge =
        payload.agentLoungeLookupChannelId()?.let { channelId ->
            RaftAgentLounges.lounge(serverId?.value, channelId) ?: RaftAgentLounges.loungeAnyServer(channelId)
        }
    val loungeAgentProfile =
        pageLounge?.let { lounge ->
            serverId?.let { sid ->
                sharedStores?.serverMembersStore?.memberProfile(sid, lounge.agentId)
            }
        }
    val loungeAgentName =
        loungeAgentProfile?.displayName?.takeIf { it.isNotBlank() } ?: pageLounge?.displayAgentName
    val loungeAgentAvatarWireValue = loungeAgentProfile?.avatar?.wireValue
    val dmAgentHasKnownLounge =
        payload.agentLoungeDmAgentId()?.let { agentId ->
            RaftAgentLounges.loungeOfAgent(serverId?.value, agentId) != null
        } == true
    val dmPeerAvatarWireValue =
        serverId?.let { sid ->
            val peerId =
                (directoryProjection?.peerIdentity ?: knownCandidate?.peerIdentity ?: payload.dmPeerIdentity)
                    ?.memberId
                    ?.value
                    ?.takeIf { it.isNotBlank() }
            peerId?.let { id ->
                sharedStores?.serverMembersStore?.memberProfile(sid, id)?.avatar?.wireValue
            }
        }
    return buildThreadNativeNavBarPrefillHead(
        payload = payload,
        directoryProjection = directoryProjection,
        knownCandidate = knownCandidate,
        serverPublicPageLive = serverPublicPageLive,
        pageLounge = pageLounge,
        loungeAgentName = loungeAgentName,
        loungeAgentAvatarWireValue = loungeAgentAvatarWireValue,
        dmAgentHasKnownLounge = dmAgentHasKnownLounge,
        resolvedDmPeerAvatarWireValue = dmPeerAvatarWireValue,
        strings = strings,
    )
}

internal fun buildThreadNativeNavBarPrefillHead(
    payload: RaftThreadScreenPayload,
    directoryProjection: SharedConversationDirectoryProjection? = null,
    knownCandidate: SharedConversationDirectoryProjection? = null,
    serverPublicPageLive: Boolean = false,
    pageLounge: RaftAgentLounge? = null,
    loungeAgentName: String? = pageLounge?.displayAgentName,
    loungeAgentAvatarWireValue: String? = null,
    dmAgentHasKnownLounge: Boolean = false,
    resolvedDmPeerAvatarWireValue: String? = null,
    strings: RaftNativeNavBarPrefillStrings = defaultNativeNavBarPrefillStrings(),
): RaftNativeNavBarHead {
    val headerProjectionInput = payload.threadHeaderProjectionRoutePayload().threadHeaderProjectionInput()
    val headerDisplay =
        headerProjectionInput
            .displayModelWithKnownCandidate(
                projection = directoryProjection,
                contentPayload = payload,
                knownCandidate = knownCandidate,
            )
            .copy(serverPublicPageLive = serverPublicPageLive)
    val loungeHeader = pageLounge?.takeIf { headerDisplay.surfaceKind == RaftThreadSurfaceKind.Channel }
    val resolvedLoungeName = loungeAgentName?.takeIf { it.isNotBlank() } ?: pageLounge?.displayAgentName
    val loungeTitle = resolvedLoungeName?.let(strings.loungeTitleForAgent)
    val title =
        if (loungeHeader != null && loungeTitle != null) {
            loungeTitle
        } else {
            localizedThreadHeaderTitle(
                title = headerDisplay.title,
                surfaceKind = headerDisplay.surfaceKind,
                localizedReplyThread = strings.replyThreadTitle,
                localizedChannel = strings.channelTitle,
                localizedDirectMessage = strings.directMessageTitle,
            )
        }
    val subtitle =
        when {
            loungeHeader != null -> null
            pageLounge != null && headerDisplay.surfaceKind == RaftThreadSurfaceKind.ReplyThread -> loungeTitle
            else -> headerDisplay.subtitle?.takeIf { it.isNotBlank() }
        }
    val taskConversation = payload.isTaskConversationSurface()
    val replyThread = headerDisplay.surfaceKind == RaftThreadSurfaceKind.ReplyThread
    val subtitleClickable =
        replyThread && !subtitle.isNullOrBlank() && payload.threadHeaderOpenInParentActionVisible()
    val leading =
        when {
            loungeHeader != null ->
                RaftNativeNavBarTitleLeading.Avatar(
                    label = resolvedLoungeName.orEmpty(),
                    avatarWireValue = loungeAgentAvatarWireValue,
                    kind = RaftAvatarKind.Agent,
                )
            headerDisplay.surfaceKind == RaftThreadSurfaceKind.DirectMessage ->
                RaftNativeNavBarTitleLeading.Avatar(
                    label = headerDisplay.title.ifBlank { headerDisplay.dmPeerAvatarLabel },
                    avatarWireValue = resolvedDmPeerAvatarWireValue ?: headerDisplay.dmPeerAvatarWireValue,
                    kind = raftAvatarKind(headerDisplay.dmPeerIdentity?.kind),
                )
            payload.showModeTabs -> RaftNativeNavBarTitleLeading.Channel(headerDisplay.channelKindGlyph)
            else -> null
        }
    val actions =
        buildList {
            if (!taskConversation && !replyThread) {
                if (payload.threadHeaderSearchAction() != null) {
                    add(
                        RaftNativeNavBarAction(
                            id = "thread-search",
                            label = strings.searchLabel,
                            systemSymbol = "magnifyingglass",
                            onClick = {},
                            icon = RaftLucideIconName.Search,
                        )
                    )
                }
                if (dmAgentHasKnownLounge && headerDisplay.surfaceKind == RaftThreadSurfaceKind.DirectMessage) {
                    add(
                        RaftNativeNavBarAction(
                            id = "lounge",
                            label = strings.loungeLabel,
                            systemSymbol = "person.2",
                            onClick = {},
                            icon = RaftLucideIconName.Users,
                        )
                    )
                }
                if (payload.showModeTabs) {
                    if (payload.supportsChannelTasks()) {
                        add(
                            RaftNativeNavBarAction(
                                id = "thread-side-card-tasks",
                                label = strings.openTasksLabel,
                                systemSymbol = "checklist",
                                onClick = {},
                                icon = RaftLucideIconName.ListTodo,
                            )
                        )
                    }
                    add(
                        RaftNativeNavBarAction(
                            id = "thread-side-card-files",
                            label = strings.openFilesLabel,
                            systemSymbol = "paperclip",
                            onClick = {},
                            icon = RaftLucideIconName.Paperclip,
                        )
                    )
                }
                if (threadHeaderSettingsActionVisible(payload.showSettingsAction, headerDisplay.surfaceKind)) {
                    add(
                        RaftNativeNavBarAction(
                            id = "thread-settings",
                            label = strings.settingsLabel,
                            systemSymbol = "gearshape",
                            onClick = {},
                            icon = RaftLucideIconName.Settings,
                        )
                    )
                }
            } else {
                if (taskConversation || payload.threadHeaderSearchAction() != null) {
                    add(
                        RaftNativeNavBarAction(
                            id = SEARCH_IN_TASK_MENU_ITEM_ID,
                            label = if (taskConversation) strings.searchInTaskLabel else strings.searchInThreadLabel,
                            systemSymbol = "magnifyingglass",
                            onClick = {},
                            icon = RaftLucideIconName.Search,
                        )
                    )
                }
                if (payload.threadHeaderOpenInParentActionVisible()) {
                    add(
                        RaftNativeNavBarAction(
                            id = VIEW_TASK_IN_CHANNEL_MENU_ITEM_ID,
                            label = strings.viewInChannelLabel,
                            systemSymbol = "mappin.and.ellipse",
                            onClick = {},
                            icon = RaftLucideIconName.MapPin,
                        )
                    )
                }
            }
        }
    return RaftNativeNavBarHead(
        title = title,
        subtitle = subtitle,
        actions = actions,
        extras = RaftNativeNavBarTitleExtras(leading = leading),
        onSubtitleClick = ({}).takeIf { subtitleClickable },
    )
}
