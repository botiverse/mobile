package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.data.AGENT_LOUNGE_FEATURE_FLAG_KEY
import build.raft.app.data.AgentLoungeApi
import build.raft.app.data.AgentLoungeOpenResult
import build.raft.app.data.DataResult
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.RaftAgentLounge
import build.raft.app.data.RaftAgentLounges
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.RaftRealtimeEvents
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.agentLoungeEnabled
import build.raft.app.data.rememberServerFeatureLatched
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftMentionMarker
import build.raft.designsystem.components.RaftReadOnlyComposer
import build.raft.designsystem.components.RaftRowTone
import build.raft.designsystem.components.RaftSidebarItem
import build.raft.designsystem.components.RaftSidebarItemMarks
import build.raft.designsystem.theme.RaftTheme
import build.raft.app.data.agentLoungeOpenFailureTraceId
import kotlinx.coroutines.launch

/**
 * Agent Lounge v0 UI pieces shared by Home, the conversation page and the agent profile (web
 * components/agent/LoungeAvatar.tsx, layout/AgentLoungeRow.tsx, features/channel/ChatPanel.tsx).
 * The rules live in data/AgentLounge.kt.
 */

/** Test tags (the web's `data-testid`s where there is one). */
internal object AgentLoungeTestTags {
    const val SidebarRow = "home-sidebar-lounge-row"
    const val SidebarUnread = "sidebar-lounge-unread"
    const val SidebarMention = "sidebar-lounge-mention-marker"
    const val AvatarBadge = "lounge-avatar-badge"
    const val Header = "lounge-header"
    const val AudienceNotice = "lounge-audience-notice"
    const val ReadOnlyComposer = "lounge-read-only-composer"
    const val ArchivedNotice = "lounge-archived-notice"
    const val UnarchiveAction = "lounge-unarchive-action"
    const val ProfileAction = "agent-profile-inline-lounge"
    const val DmHeaderToggle = "agent-dm-open-lounge"
}

/** "{Name}'s Lounge". */
@Composable
internal fun agentLoungeTitle(agentName: String): String = stringResource(Res.string.agent_lounge_title, agentName)

/** "{Name}'s Lounge" outside composition. */
internal fun agentLoungeTitleNow(agentName: String): String =
    Res.string.agent_lounge_title.format(agentName).resolve()

/** The server has Lounges on and this person is not a Guest; recomposes when the flag resolves. */
@Composable
internal fun rememberAgentLoungeEnabled(
    flags: SharedServerFeatureFlags?,
    serverId: String?,
    serverRole: String?,
): Boolean {
    val flag = rememberServerFeatureLatched(flags, serverId?.takeIf { it.isNotBlank() }?.let(::ServerId), AGENT_LOUNGE_FEATURE_FLAG_KEY)
    return agentLoungeEnabled(flag, serverRole)
}

/** The Lounge with [channelId] in [serverId], kept current as Lounges become known. */
@Composable
internal fun rememberAgentLounge(serverId: String?, channelId: String?): RaftAgentLounge? {
    val all by RaftAgentLounges.lounges.collectAsState()
    return remember(all, serverId, channelId) {
        RaftAgentLounges.lounge(serverId, channelId) ?: RaftAgentLounges.loungeAnyServer(channelId)
    }
}

/** The name of [lounge]'s agent: the member directory's display name when it knows the agent, else the Lounge's own. */
@Composable
internal fun RaftAgentLounge.rememberDisplayAgentName(memberDisplayName: String? = null): String =
    remember(this, memberDisplayName) { memberDisplayName?.takeIf { it.isNotBlank() } ?: displayAgentName }

/**
 * The Lounge's face: its agent's avatar with the Lounge mark (lucide `users` on `info-soft`, an
 * ink edge) in the bottom-right corner, wherever the Lounge itself is shown. Decorative: the row
 * or header around it carries the name ("{Name}'s Lounge").
 */
@Composable
internal fun RaftLoungeAvatar(
    agentId: String?,
    avatarWireValue: String?,
    label: String,
    cacheServerId: String?,
    avatarSize: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    // Web LOUNGE_BADGE_CLASS: 11px on the sidebar's 19px face, 14 on 35, 18 on 51; glyph about two thirds.
    val badgeSize = (avatarSize * 0.4f).coerceAtLeast(11.dp)
    val glyphSize = (badgeSize * 0.7f).coerceAtLeast(8.dp)
    Box(modifier = modifier.size(avatarSize).clearAndSetSemantics {}) {
        RaftAppAvatar(
            avatarWireValue = avatarWireValue,
            label = label,
            avatarSize = avatarSize,
            cacheSubject = raftMemberAvatarCacheSubject(MemberKind.Agent, agentId),
            cacheServerId = cacheServerId,
            fallbackFill = raftAvatarFallbackFill(MemberKind.Agent),
            modifier = Modifier.align(Alignment.Center),
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 3.dp, y = 3.dp)
                    .size(badgeSize)
                    .background(colors.layerCanvas, RoundedCornerShape(3.dp))
                    .border(1.dp, colors.foreground, RoundedCornerShape(3.dp))
                    .background(colors.infoSoft, RoundedCornerShape(3.dp))
                    .testTag(AgentLoungeTestTags.AvatarBadge),
            contentAlignment = Alignment.Center,
        ) {
            RaftContentLucideIcon(RaftLucideIconName.Users, size = glyphSize)
        }
    }
}

/**
 * Agent Lounge v0: the Lounge's own row right under its agent's DM row (web `AgentLoungeRow`):
 * indented one icon box, the Lounge's face, "Lounge", its @ marker and unread count (99+). Its
 * accessible name is "{Name}'s Lounge". Shown only when [build.raft.app.data.agentLoungeSidebarEntry]
 * says so; the caller decides.
 */
@Composable
internal fun AgentLoungeSidebarRow(
    agentName: String,
    agentId: String?,
    avatarWireValue: String?,
    cacheServerId: String?,
    unreadCount: Int,
    mentioned: Boolean,
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val title = agentLoungeTitle(agentName)
    val unread = unreadCount.coerceAtLeast(0)
    val mentionDescription = if (mentioned) stringResource(Res.string.activity_list_mentioned_you) else null
    RaftSidebarItem(
        title = stringResource(Res.string.agent_lounge_label),
        modifier =
            modifier
                .testTag(AgentLoungeTestTags.SidebarRow)
                .semantics { contentDescription = title }
                // An empty icon box: indents the face under the agent's name.
                .padding(start = 20.dp),
        onClick = onClick,
        selected = selected,
        tone = if (unread > 0) RaftRowTone.Unread else RaftRowTone.Default,
        leading = {
            RaftLoungeAvatar(
                agentId = agentId,
                avatarWireValue = avatarWireValue,
                label = agentName,
                cacheServerId = cacheServerId,
                avatarSize = 19.dp,
            )
        },
        trailing =
            if (mentioned || unread > 0) {
                {
                    if (mentioned) {
                        RaftMentionMarker(
                            modifier = Modifier.testTag(AgentLoungeTestTags.SidebarMention),
                            contentDescription = mentionDescription,
                        )
                    }
                    if (unread > 0) {
                        Box(Modifier.testTag(AgentLoungeTestTags.SidebarUnread)) {
                            RaftSidebarItemMarks(unreadCount = unread, countDescription = null)
                        }
                    }
                }
            } else {
                null
            },
    )
}

/** Agent Lounge v0: who is here, always on top of the conversation (web `lounge-audience-notice`). */
@Composable
internal fun AgentLoungeAudienceNotice(modifier: Modifier = Modifier) {
    RaftBanner(
        description = stringResource(Res.string.agent_lounge_audience_notice),
        status = RaftBannerStatus.Info,
        modifier = modifier.fillMaxWidth().testTag(AgentLoungeTestTags.AudienceNotice),
    )
}

/** A Lounge this person may only read (`canPost: false`): the read-only composer, no Join. */
@Composable
internal fun AgentLoungeReadOnlyComposer(modifier: Modifier = Modifier) {
    RaftReadOnlyComposer(
        text = stringResource(Res.string.thread_composer_chat_unavailable),
        modifier = modifier.testTag(AgentLoungeTestTags.ReadOnlyComposer),
    )
}

/**
 * An archived Lounge (web ChatPanel archived banner + `LoungeUnarchiveAction`): "This channel is
 * archived." and, only for a viewer whose server role may archive channels, Unarchive. A Lounge
 * has no settings sheet, so the action unarchives in place.
 */
@Composable
internal fun AgentLoungeArchivedNotice(
    canUnarchive: Boolean,
    unarchiving: Boolean,
    onUnarchive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RaftBanner(
        description = stringResource(Res.string.thread_archived_notice),
        status = RaftBannerStatus.Warning,
        modifier = modifier.fillMaxWidth().testTag(AgentLoungeTestTags.ArchivedNotice),
        action =
            if (canUnarchive) {
                {
                    RaftButton(
                        text = stringResource(Res.string.thread_archived_unarchive),
                        onClick = onUnarchive,
                        variant = RaftButtonVariant.Default,
                        loading = unarchiving,
                        modifier = Modifier.testTag(AgentLoungeTestTags.UnarchiveAction),
                    )
                }
            } else {
                null
            },
    )
}

/**
 * Open an agent's Lounge (POST /agents/:id/lounge makes it the first time): remember it, and
 * subscribe to its realtime room the way the app does for any conversation it opens (web
 * `joinRealtimeChannel` after `openLounge`). The caller navigates on success and shows the
 * openFailed toast (with the trace id) on any failure.
 */
internal suspend fun openAgentLounge(
    networkClient: RaftNetworkClient,
    serverId: String,
    agentId: String,
    agentName: String?,
    realtimeEvents: RaftRealtimeEvents?,
): DataResult<AgentLoungeOpenResult> {
    val result = AgentLoungeApi(networkClient).openLounge(ServerId(serverId), agentId)
    if (result is DataResult.Content) {
        RaftAgentLounges.rememberRow(serverId, result.data.channel, canPost = result.data.canPost, agentName = agentName)
        realtimeEvents?.joinChannel(result.data.channel.channelId.value, serverId)
    }
    return result
}

/** The conversation identity a Lounge opens as: a plain channel conversation. */
internal fun AgentLoungeOpenResult.conversationIdentity(): ThreadLaunchConversationIdentity =
    channel.toThreadLaunchConversationIdentity()

/** Opens agents' Lounges from a page: single flight per agent, the openFailed toast on any failure. */
internal class AgentLoungeOpener(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val networkClient: RaftNetworkClient,
    private val realtimeEvents: RaftRealtimeEvents?,
    private val openFailedMessage: () -> String,
    private val onError: (message: String, traceId: String?) -> Unit,
) {
    private val pending = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    /** Agent ids whose Lounge is opening (the button's loading state). */
    val opening: kotlinx.coroutines.flow.StateFlow<Set<String>> = pending

    fun open(serverId: String?, agentId: String, agentName: String?, onOpened: (AgentLoungeOpenResult) -> Unit) {
        val cleanServerId = serverId?.takeIf { it.isNotBlank() }
        if (cleanServerId == null) {
            onError(openFailedMessage(), null)
            return
        }
        if (agentId in pending.value) return
        pending.value = pending.value + agentId
        scope.launch {
            val result =
                try {
                    openAgentLounge(networkClient, cleanServerId, agentId, agentName, realtimeEvents)
                } finally {
                    pending.value = pending.value - agentId
                }
            when (result) {
                is DataResult.Content -> onOpened(result.data)
                else -> onError(openFailedMessage(), result.agentLoungeOpenFailureTraceId())
            }
        }
    }
}

@Composable
internal fun rememberAgentLoungeOpener(
    bridge: build.raft.app.base.BridgeModule,
    storeScope: build.raft.app.data.RaftShellStoreScope,
): AgentLoungeOpener {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val openFailed = stringResource(Res.string.agent_lounge_open_failed)
    val latestOpenFailed by androidx.compose.runtime.rememberUpdatedState(openFailed)
    return remember(bridge, storeScope, scope) {
        val stores = build.raft.app.data.RaftSharedStoreRegistry.get(bridge)
        AgentLoungeOpener(
            scope = scope,
            networkClient = stores.networkClient(storeScope),
            realtimeEvents = RaftRealtimeEvents(stores.eventBus),
            openFailedMessage = { latestOpenFailed },
            onError = { message, traceId -> bridge.errorToast(message, traceId) },
        )
    }
}

/**
 * What a conversation page needs to draw itself as (or inside) an agent's Lounge, and the agent
 * DM header's Lounge toggle. Built by the route ([rememberThreadPageLounge]); null pieces are off.
 */
internal class ThreadPageLounge(
    /** The Lounge this conversation is (a channel page) or is in (a reply thread); null elsewhere. */
    val lounge: RaftAgentLounge?,
    /** The Lounge's agent's avatar, for its face. */
    val agentAvatarWireValue: String?,
    val cacheServerId: String?,
    /** Unarchive is offered only to a viewer whose server role may archive channels. */
    val canUnarchive: Boolean,
    val unarchiving: Boolean,
    val onUnarchive: () -> Unit,
    /** The Lounge face's tap: the agent's profile. */
    val onOpenAgentProfile: (() -> Unit)?,
    /** An agent DM's header: open that agent's Lounge; null hides the toggle. */
    val onOpenDmAgentLounge: (() -> Unit)?,
    val dmLoungeOpening: Boolean,
) {
    val agentName: String
        get() = lounge?.displayAgentName.orEmpty()
}

/** The channel id a conversation page shows: its own (channel) or its parent's (reply thread). */
internal fun RaftThreadScreenPayload.agentLoungeLookupChannelId(): String? =
    when (surfaceKind) {
        RaftThreadSurfaceKind.Channel ->
            (streamChannelId?.takeIf { it.isNotBlank() } ?: sourceThreadId?.takeIf { it.isNotBlank() })
        RaftThreadSurfaceKind.ReplyThread -> parentChannelId?.takeIf { it.isNotBlank() }
        else -> null
    }

/** The agent of an agent DM page, or null. */
internal fun RaftThreadScreenPayload.agentLoungeDmAgentId(): String? =
    dmPeerIdentity
        ?.takeIf { surfaceKind == RaftThreadSurfaceKind.DirectMessage && it.kind == MemberKind.Agent }
        ?.memberId
        ?.value
        ?.takeIf { it.isNotBlank() }

@Composable
internal fun rememberThreadPageLounge(
    payload: RaftThreadScreenPayload,
    bridge: build.raft.app.base.BridgeModule,
    storeScope: build.raft.app.data.RaftShellStoreScope,
    flags: SharedServerFeatureFlags?,
    serverId: String?,
    serverRole: String?,
    memberAvatarResolver: RaftMemberAvatarResolver,
    /** Agents that are someone's personal assistant here (no Lounge for them). */
    personalAssistantAgentIds: Set<String>,
    onOpenConversation: (ThreadLaunchConversationIdentity) -> Unit,
    onOpenAgentProfile: (agentId: String) -> Unit,
): ThreadPageLounge {
    val lounge = rememberAgentLounge(serverId, payload.agentLoungeLookupChannelId())
    val avatar = rememberRaftMemberAvatarWireValue(memberAvatarResolver, lounge?.agentId ?: payload.agentLoungeDmAgentId())
    val enabled = rememberAgentLoungeEnabled(flags, serverId, serverRole)
    val opener = rememberAgentLoungeOpener(bridge, storeScope)
    val opening by opener.opening.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var unarchiving by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val unarchiveFailed = stringResource(Res.string.agent_lounge_open_failed)
    val dmAgentId = payload.agentLoungeDmAgentId()
    val dmAgentName = payload.title.trim().removePrefix("@")
    return ThreadPageLounge(
        lounge = lounge,
        agentAvatarWireValue = avatar,
        cacheServerId = serverId,
        canUnarchive = build.raft.app.model.serverPermissionsForRole(serverRole).canManageSettings,
        unarchiving = unarchiving,
        onUnarchive = {
            val target = lounge
            val cleanServerId = serverId?.takeIf { it.isNotBlank() }
            if (target != null && cleanServerId != null && !unarchiving) {
                unarchiving = true
                scope.launch {
                    val stores = build.raft.app.data.RaftSharedStoreRegistry.get(bridge)
                    val result =
                        try {
                            AgentLoungeApi(stores.networkClient(storeScope)).unarchive(ServerId(cleanServerId), target.channelId)
                        } finally {
                            unarchiving = false
                        }
                    if (result !is DataResult.Content) {
                        bridge.errorToast(unarchiveFailed, (result as? DataResult.Error)?.error?.traceId)
                    }
                }
            }
        },
        onOpenAgentProfile = lounge?.let { { onOpenAgentProfile(it.agentId) } },
        onOpenDmAgentLounge =
            dmAgentId
                ?.takeIf { enabled && it !in personalAssistantAgentIds }
                ?.let { agentId ->
                    {
                        opener.open(serverId, agentId, dmAgentName.ifBlank { null }) { opened ->
                            onOpenConversation(opened.conversationIdentity())
                        }
                    }
                },
        dmLoungeOpening = dmAgentId != null && dmAgentId in opening,
    )
}

/**
 * A Lounge's page: no Join step (the first post joins), and posting follows what the Lounge says
 * ([RaftAgentLounge.postable]: not archived, not refused by the open answer). Unchanged elsewhere.
 */
internal fun RaftThreadScreenPayload.withAgentLoungeAccess(lounge: RaftAgentLounge?): RaftThreadScreenPayload {
    if (lounge == null || surfaceKind != RaftThreadSurfaceKind.Channel) return this
    val postable = lounge.postable
    if (joined && canSend == postable && composer.enabled == postable) return this
    return copy(joined = true, canSend = postable, composer = composer.copy(enabled = postable))
}

/** "{Name}'s Lounge" while the composer belongs to a Lounge (its placeholder names the Lounge); null elsewhere. */
internal val LocalAgentLoungeComposerName = androidx.compose.runtime.staticCompositionLocalOf<String?> { null }

/** [AgentLoungeSidebarRow] for an agent DM item of the Home sidebar. */
@Composable
internal fun HomeSidebarAgentLoungeSubRow(
    dm: RaftHomeConversationItem,
    lounge: RaftHomeAgentLoungeRow,
    cacheServerId: String?,
    memberAvatarResolver: RaftMemberAvatarResolver,
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val avatar = rememberRaftMemberAvatarWireValue(memberAvatarResolver, dm.presenceMemberId)
    AgentLoungeSidebarRow(
        agentName = dm.name,
        agentId = dm.presenceMemberId,
        avatarWireValue = avatar ?: dm.avatarWireValue,
        cacheServerId = cacheServerId,
        unreadCount = lounge.unreadCount,
        mentioned = lounge.mentioned,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
    )
}

/** The sidebar sub-row of an agent DM row, when Lounges are on here (web `agentLoungeSidebarEntry`). */
internal fun HomeDirectMessageRow.agentLoungeSidebarRow(enabled: Boolean, serverId: String?): RaftHomeAgentLoungeRow? {
    if (!enabled || !directMessagePeerType.equals("agent", ignoreCase = true)) return null
    val agentId = presenceMemberId?.value
    val known =
        RaftAgentLounges.loungeOfAgent(serverId, agentId)
            ?: lounge?.channelId?.value?.let { RaftAgentLounges.lounge(serverId, it) }
    val entry = build.raft.app.data.agentLoungeSidebarEntry(lounge, known) ?: return null
    return RaftHomeAgentLoungeRow(entry.channelId.value, entry.unreadCount, entry.mentioned)
}

/**
 * The conversation identity of the Lounge with [channelId] when this client knows it (opened,
 * loaded, or carried on an agent DM row of [directMessages]); null for anything else.
 */
internal fun agentLoungeConversationIdentityOrNull(
    serverId: String?,
    channelId: String,
    directMessages: List<HomeDirectMessageRow>,
): ThreadLaunchConversationIdentity? {
    val known = RaftAgentLounges.lounge(serverId, channelId)
    val carriedBy = directMessages.firstOrNull { it.lounge?.channelId?.value == channelId }
    val agentId = known?.agentId ?: carriedBy?.presenceMemberId?.value ?: return null
    return build.raft.app.data.HomeChannelRow(
        threadId = build.raft.app.model.ThreadId(channelId),
        channelId = build.raft.app.model.ChannelId(channelId),
        channelType = "channel",
        name = known?.channelName.orEmpty(),
        loungeAgentId = agentId,
    ).toThreadLaunchConversationIdentity()
}

/**
 * "{Name}'s Lounge" for a place known only by its channel id (an Activity / search / draft row, a
 * permalink), or null when that channel is not a Lounge this client knows. Use it where the
 * place would otherwise read `#<agent>-lounge`.
 */
internal fun agentLoungeLabelOrNull(channelId: String?): String? =
    RaftAgentLounges.loungeAnyServer(channelId)?.let { agentLoungeTitleNow(it.displayAgentName) }

/** What a Lounge's page draws in place of its composer, if anything. */
internal enum class AgentLoungeFooterKind { Archived, ReadOnly }

/**
 * [conversationLounge]: the Lounge this page is; [pageLounge]: it, or the Lounge a reply thread is
 * in. An archived Lounge page shows the archived banner; a Lounge (or a thread in it) you may not
 * post in shows the read-only composer. Null: the ordinary composer.
 */
internal fun agentLoungeFooterKind(conversationLounge: RaftAgentLounge?, pageLounge: RaftAgentLounge?): AgentLoungeFooterKind? =
    when {
        conversationLounge?.archived == true -> AgentLoungeFooterKind.Archived
        pageLounge != null && !pageLounge.postable -> AgentLoungeFooterKind.ReadOnly
        else -> null
    }

/** The Join footer: an unjoined channel, never a Lounge (its first post joins). */
internal fun threadFooterShowsJoin(joined: Boolean, conversationLounge: RaftAgentLounge?): Boolean =
    !joined && conversationLounge == null

@Composable
internal fun AgentLoungeFooter(kind: AgentLoungeFooterKind?, inputs: ThreadPageLounge?, modifier: Modifier = Modifier) {
    when (kind) {
        AgentLoungeFooterKind.Archived ->
            AgentLoungeArchivedNotice(
                canUnarchive = inputs?.canUnarchive == true,
                unarchiving = inputs?.unarchiving == true,
                onUnarchive = { inputs?.onUnarchive?.invoke() },
                modifier = modifier,
            )
        AgentLoungeFooterKind.ReadOnly -> AgentLoungeReadOnlyComposer(modifier = modifier)
        null -> Unit
    }
}
