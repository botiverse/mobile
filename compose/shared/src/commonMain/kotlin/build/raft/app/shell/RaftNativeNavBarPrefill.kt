package build.raft.app.shell

import build.raft.app.CHANNEL_SETTINGS_PAGE
import build.raft.app.RaftAvatarKind
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.agentLoungeLookupChannelId
import build.raft.app.agentLoungeTitleNow
import build.raft.app.appBotIdentityOrNull
import build.raft.app.compat.json.JSONObject
import build.raft.app.data.AGENT_LOUNGE_FEATURE_FLAG_KEY
import build.raft.app.data.RaftAgentLounge
import build.raft.app.data.RaftAgentLounges
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.agentLoungeEnabled
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.optString
import build.raft.app.raftAvatarKind
import build.raft.app.resources.Res
import build.raft.app.resources.agent_lounge_audience_notice
import build.raft.app.resources.agent_lounge_label
import build.raft.app.resources.agent_lounge_private_audience_notice
import build.raft.app.resources.channel_details_open
import build.raft.app.resources.channel_details_title
import build.raft.app.resources.conversation_details_open
import build.raft.app.resources.desc
import build.raft.app.resources.format
import build.raft.app.resources.resolve
import build.raft.app.resources.settings_title
import build.raft.app.resources.task_conversation_search
import build.raft.app.resources.task_conversation_view_in_channel
import build.raft.app.resources.thread_action_search
import build.raft.app.resources.thread_action_search_channel
import build.raft.app.resources.thread_action_search_conversation
import build.raft.app.resources.thread_action_search_in_thread
import build.raft.app.resources.thread_channel_title
import build.raft.app.resources.thread_direct_message_title
import build.raft.app.resources.thread_members_heading
import build.raft.app.resources.thread_reply_title
import build.raft.app.resources.thread_side_card_files_open
import build.raft.app.resources.thread_side_card_tasks_open
import build.raft.app.shouldShowAgentLoungeConversationSwitch
import build.raft.app.thread.ConversationSideCard
import build.raft.app.thread.PhoneConversationHeaderPrimaryConfig
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.THREAD_MEMBERS_PAGE
import build.raft.app.thread.THREAD_PAGE
import build.raft.app.thread.ThreadHeaderSideColumnLabels
import build.raft.app.thread.conversationSideCardsEnabled
import build.raft.app.thread.displayModelWithKnownCandidate
import build.raft.app.thread.isGenericThreadHeaderFallbackLabel
import build.raft.app.thread.isTaskConversationSurface
import build.raft.app.thread.localizedThreadHeaderTitle
import build.raft.app.thread.parseThreadLaunchPayload
import build.raft.app.thread.phoneConversationHeaderPrimaryConfig
import build.raft.app.thread.supportsChannelTasks
import build.raft.app.thread.threadHeaderInitialProjectionMap
import build.raft.app.thread.threadHeaderKnownCandidate
import build.raft.app.thread.threadHeaderOpenInParentActionVisible
import build.raft.app.thread.threadHeaderProjectionInput
import build.raft.app.thread.threadHeaderProjectionRoutePayload
import build.raft.app.thread.threadHeaderProjectionServerId
import build.raft.app.thread.threadHeaderSideCardOwnActions
import build.raft.app.thread.threadHeaderSideColumnActionsForPayload

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
    /** "{Name}'s Lounge", the bare "Lounge" for a null name ([agentLoungeTitleNow]). */
    val loungeTitleForAgent: (String?) -> String,
    val loungeAudienceSubtitle: String = "",
    val privateAudienceSubtitleForAgent: (String) -> String = { "" },
    val channelDetailsTitle: String,
    val membersTitle: String,
    val searchChannelLabel: String = searchLabel,
    val searchConversationLabel: String = searchLabel,
    val channelDetailsOpenLabel: String = settingsLabel,
    val conversationDetailsOpenLabel: String = settingsLabel,
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
        loungeAudienceSubtitle = Res.string.agent_lounge_audience_notice.desc().resolve(),
        privateAudienceSubtitleForAgent = { name ->
            Res.string.agent_lounge_private_audience_notice.format(name).resolve()
        },
        channelDetailsTitle = Res.string.channel_details_title.desc().resolve(),
        membersTitle = Res.string.thread_members_heading.desc().resolve(),
        searchChannelLabel = Res.string.thread_action_search_channel.desc().resolve(),
        searchConversationLabel = Res.string.thread_action_search_conversation.desc().resolve(),
        channelDetailsOpenLabel = Res.string.channel_details_open.desc().resolve(),
        conversationDetailsOpenLabel = Res.string.conversation_details_open.desc().resolve(),
    )

internal fun buildNativeNavBarPrefillHead(
    pageName: String,
    pageDataJson: String,
    strings: RaftNativeNavBarPrefillStrings? = null,
): RaftNativeNavBarHead? =
    when (pageName) {
        THREAD_PAGE ->
            buildThreadNativeNavBarPrefillFromPageData(
                pageDataJson = pageDataJson,
                strings = strings ?: defaultNativeNavBarPrefillStrings(),
            )
        THREAD_MEMBERS_PAGE ->
            RaftNativeNavBarHead(
                title = (strings ?: defaultNativeNavBarPrefillStrings()).membersTitle,
                subtitle = null,
                actions = emptyList(),
                extras = RaftNativeNavBarTitleExtras(),
                onBack = {},
            )
        CHANNEL_SETTINGS_PAGE ->
            RaftNativeNavBarHead(
                title = (strings ?: defaultNativeNavBarPrefillStrings()).channelDetailsTitle,
                subtitle = null,
                actions = emptyList(),
                extras = RaftNativeNavBarTitleExtras(),
                onBack = {},
            )
        RAFT_APP_SHELL_SERVER_PAGE -> {
            val resolvedStrings = strings ?: defaultNativeNavBarPrefillStrings()
            val tab =
                runCatching { JSONObject(pageDataJson).optString(RAFT_APP_SHELL_SERVER_PAGE_TAB) }
                    .getOrNull()
                    ?.let(::appShellServerStackPageTab)
            val title =
                when (tab) {
                    AppShellServerTabKey -> resolvedStrings.settingsLabel
                    "members" -> resolvedStrings.membersTitle
                    else -> null
                }
            title?.let {
                RaftNativeNavBarHead(
                    title = it,
                    subtitle = null,
                    actions = emptyList(),
                    extras = RaftNativeNavBarTitleExtras(),
                    onBack = {},
                    // Settings wires the 7-tap developer unlock onto its native title (`SettingsHubPage`).
                    onTitleClick = ({}).takeIf { tab == AppShellServerTabKey },
                )
            }
        }
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
    val homeServer =
        (sharedStores?.sharedHomeStore(storeScope)?.state?.value?.content as? SharedHomeContentState.Content)
            ?.snapshot
            ?.server
            ?.takeIf { it.id == serverId }
    val serverPublicPageLive = homeServer?.publicPageLive == true
    val scopedMembersStore = sharedStores?.serverMembersStore(storeScope)
    val pageLounge =
        payload.agentLoungeLookupChannelId()?.let { channelId ->
            RaftAgentLounges.lounge(serverId?.value, channelId) ?: RaftAgentLounges.loungeAnyServer(channelId)
        }
    val loungeAgentAvatarWireValue =
        pageLounge?.let { lounge ->
            serverId?.let { sid ->
                scopedMembersStore?.memberProfile(sid, lounge.agentId)?.avatar?.wireValue
            }
        }
    val resolvedPeerIdentity = directoryProjection?.peerIdentity ?: knownCandidate?.peerIdentity ?: payload.dmPeerIdentity
    val dmAgentId =
        resolvedPeerIdentity
            ?.takeIf { payload.surfaceKind == RaftThreadSurfaceKind.DirectMessage && it.kind == MemberKind.Agent }
            ?.memberId
            ?.value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    val switchAgentId =
        if (payload.surfaceKind == RaftThreadSurfaceKind.Channel && pageLounge != null) {
            pageLounge.agentId
        } else {
            dmAgentId
        }
    val loungeSwitchVisible =
        if (switchAgentId != null && sharedStores != null && serverId != null) {
            val flagEnabled = sharedStores.serverFeatureFlags.isEnabled(serverId, AGENT_LOUNGE_FEATURE_FLAG_KEY)
            val personalAssistantAgentIds =
                sharedStores.myAssistantStore(storeScope).answers.value.values
                    .mapNotNull { it.assistant?.agentId }
                    .toSet()
            val memberProfile = scopedMembersStore?.memberProfile(serverId, switchAgentId)
            shouldShowAgentLoungeConversationSwitch(
                enabled = agentLoungeEnabled(flagEnabled, homeServer?.role),
                surfaceKind = payload.surfaceKind,
                isLoungeConversation = payload.surfaceKind == RaftThreadSurfaceKind.Channel && pageLounge != null,
                agentId = switchAgentId,
                personalAssistantAgentIds = personalAssistantAgentIds,
                isAppBot = memberProfile?.appBotIdentityOrNull() != null,
                isDeletedAgent = memberProfile?.agentDeletedAt?.isNotBlank() == true,
            ) && memberProfile?.assistantPausedAt == null
        } else {
            false
        }
    val dmPeerAvatarWireValue =
        serverId?.let { sid ->
            resolvedPeerIdentity
                ?.memberId
                ?.value
                ?.takeIf { it.isNotBlank() }
                ?.let { id -> scopedMembersStore?.memberProfile(sid, id)?.avatar?.wireValue }
        }
    val phoneHeaderPrimaryConfig =
        sharedStores?.serverFeatureFlags?.snapshotFor(serverId)?.phoneConversationHeaderPrimaryConfig()
            ?: PhoneConversationHeaderPrimaryConfig.Default
    return buildThreadNativeNavBarPrefillHead(
        payload = payload,
        directoryProjection = directoryProjection,
        knownCandidate = knownCandidate,
        serverPublicPageLive = serverPublicPageLive,
        pageLounge = pageLounge,
        loungeAgentAvatarWireValue = loungeAgentAvatarWireValue,
        loungeSwitchVisible = loungeSwitchVisible,
        resolvedDmPeerAvatarWireValue = dmPeerAvatarWireValue,
        phoneHeaderPrimaryConfig = phoneHeaderPrimaryConfig,
        strings = strings,
    )
}

internal fun buildThreadNativeNavBarPrefillHead(
    payload: RaftThreadScreenPayload,
    directoryProjection: SharedConversationDirectoryProjection? = null,
    knownCandidate: SharedConversationDirectoryProjection? = null,
    serverPublicPageLive: Boolean = false,
    pageLounge: RaftAgentLounge? = null,
    loungeAgentAvatarWireValue: String? = null,
    loungeSwitchVisible: Boolean = false,
    resolvedDmPeerAvatarWireValue: String? = null,
    phoneHeaderPrimaryConfig: PhoneConversationHeaderPrimaryConfig = PhoneConversationHeaderPrimaryConfig.Default,
    strings: RaftNativeNavBarPrefillStrings? = null,
): RaftNativeNavBarHead {
    val resolvedStrings = strings ?: defaultNativeNavBarPrefillStrings()
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
    val resolvedLoungeName = pageLounge?.displayAgentName
    val loungeTitle = pageLounge?.let { resolvedStrings.loungeTitleForAgent(resolvedLoungeName) }
    val title =
        if (loungeHeader != null && loungeTitle != null) {
            loungeTitle
        } else {
            localizedThreadHeaderTitle(
                title = headerDisplay.title,
                surfaceKind = headerDisplay.surfaceKind,
                localizedReplyThread = resolvedStrings.replyThreadTitle,
                localizedChannel = resolvedStrings.channelTitle,
                localizedDirectMessage = resolvedStrings.directMessageTitle,
            )
        }
    val loungeAudienceSubtitle =
        when {
            !loungeSwitchVisible -> null
            loungeHeader != null -> resolvedStrings.loungeAudienceSubtitle.takeIf { it.isNotBlank() }
            headerDisplay.surfaceKind == RaftThreadSurfaceKind.DirectMessage -> {
                val peerName =
                    build.raft.app.data.loungeAgentDisplayName(
                        headerDisplay.title.trim().removePrefix("@").takeUnless { it.isGenericThreadHeaderFallbackLabel() }
                    )
                peerName?.let { resolvedStrings.privateAudienceSubtitleForAgent(it).takeIf(String::isNotBlank) }
            }
            else -> null
        }
    val subtitle =
        when {
            loungeAudienceSubtitle != null -> loungeAudienceSubtitle
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
            loungeSwitchVisible && loungeHeader != null ->
                RaftNativeNavBarTitleLeading.AgentConversation(
                    label = resolvedLoungeName.orEmpty(),
                    avatarWireValue = loungeAgentAvatarWireValue,
                    isLounge = true,
                    agentId = loungeHeader.agentId,
                )
            loungeSwitchVisible && headerDisplay.surfaceKind == RaftThreadSurfaceKind.DirectMessage ->
                RaftNativeNavBarTitleLeading.AgentConversation(
                    label = headerDisplay.title.ifBlank { headerDisplay.dmPeerAvatarLabel },
                    avatarWireValue = resolvedDmPeerAvatarWireValue ?: headerDisplay.dmPeerAvatarWireValue,
                    isLounge = false,
                    agentId = headerDisplay.dmPeerIdentity?.memberId?.value,
                )
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
    val sideCardActions =
        if (conversationSideCardsEnabled(showModeTabs = payload.showModeTabs, assistantPanelEmbed = false)) {
            threadHeaderSideCardOwnActions(
                tasksAvailable = payload.supportsChannelTasks(),
                labelForCard = { card, _ ->
                    when (card) {
                        ConversationSideCard.Tasks -> resolvedStrings.openTasksLabel
                        ConversationSideCard.Files -> resolvedStrings.openFilesLabel
                    }
                },
            )
        } else {
            emptyList()
        }
    val taskViewInChannel: (() -> Unit)? =
        if (taskConversation && (payload.parentChannelId?.isNotBlank() == true || payload.threadHeaderOpenInParentActionVisible())) {
            {}
        } else {
            null
        }
    val isTopLevelConversation = !taskConversation && !replyThread
    val isNamedChannel = headerDisplay.surfaceKind == RaftThreadSurfaceKind.Channel && loungeHeader == null && !taskConversation
    val rawActions =
        threadHeaderSideColumnActionsForPayload(
            payload = payload,
            surfaceKind = headerDisplay.surfaceKind,
            taskConversation = taskConversation,
            labels =
                ThreadHeaderSideColumnLabels(
                    searchLabel = if (isNamedChannel) resolvedStrings.searchChannelLabel else resolvedStrings.searchConversationLabel,
                    searchInThreadLabel = resolvedStrings.searchInThreadLabel,
                    searchInTaskLabel = resolvedStrings.searchInTaskLabel,
                    settingsLabel = if (isNamedChannel) resolvedStrings.channelDetailsOpenLabel else resolvedStrings.conversationDetailsOpenLabel,
                    viewInChannelLabel = resolvedStrings.viewInChannelLabel,
                ),
            onViewTaskInChannel = taskViewInChannel,
            sideCardActions = sideCardActions,
        ).toRaftNativeNavBarActions()
    val actions =
        foldPhoneConversationNativeNavBarActions(
            actions = rawActions,
            foldForPhone = isTopLevelConversation,
            primaryConfig = phoneHeaderPrimaryConfig,
        )
    return RaftNativeNavBarHead(
        title = title,
        subtitle = subtitle,
        actions = actions,
        extras = RaftNativeNavBarTitleExtras(leading = leading),
        onSubtitleClick = ({}).takeIf { subtitleClickable },
    )
}
