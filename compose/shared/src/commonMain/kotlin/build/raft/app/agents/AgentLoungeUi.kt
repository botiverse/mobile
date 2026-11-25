package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import build.raft.app.data.AGENT_LOUNGE_FEATURE_FLAG_KEY
import build.raft.app.data.AgentLoungeApi
import build.raft.app.data.AgentLoungeOpenResult
import build.raft.app.data.DataResult
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeFollowedThreadUnread
import build.raft.app.data.HomeStandaloneLoungeRow
import build.raft.app.data.RaftAgentLounge
import build.raft.app.data.RaftAgentLounges
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftConversationHeaderAction
import build.raft.app.data.RaftConversationHeaderKind
import build.raft.app.data.RaftConversationHeaderSurface
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.RaftProductEvents
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SharedHomeStore
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.agentLoungeEnabled
import build.raft.app.data.agentLoungeOpenFailureTraceId
import build.raft.app.data.agentLoungeSidebarEntry
import build.raft.app.data.loungeAgentDisplayName
import build.raft.app.data.rememberServerFeatureLatched
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftReadOnlyComposer
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftStatusSize
import build.raft.designsystem.components.RaftStatusVariant
import build.raft.designsystem.components.RaftTab
import build.raft.designsystem.components.RaftTabStrip
import build.raft.designsystem.components.RaftUnreadCount
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Agent Lounge v0 UI pieces shared by Home, the conversation page and the agent profile (web
 * components/agent/LoungeAvatar.tsx, layout/AgentLoungeRow.tsx, features/channel/ChatPanel.tsx).
 * The rules live in data/AgentLounge.kt.
 */

/** Test tags (the web's `data-testid`s where there is one). */
/**
 * The Lounge's mark wherever the app draws one (web `AgentLoungeIcon` in agentLounge.ts): lucide Sofa,
 * so it no longer reads as Members (huxijin, #proj-chat msg=5dedad9c). [AgentLoungeSystemSymbol] is the
 * iOS native bar's counterpart.
 */
internal val AgentLoungeIcon = RaftLucideIconName.Sofa

/** SF Symbol for the Lounge in the iOS native navigation bar (pairs with [AgentLoungeIcon]). */
internal const val AgentLoungeSystemSymbol = "sofa"

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
    const val TabStrip = "lounge-conversation-tab-strip"
    const val TabLounge = "lounge-tab-lounge"
    const val TabPrivate = "lounge-tab-private"
}

/** Which side of an agent's paired conversation (Lounge vs Private DM) is currently shown. */
internal enum class AgentLoungeConversationSide {
    Lounge,
    Private,
}

/** Unread indicator shown on the inactive side of the under-header Lounge / Private tab strip. */
internal data class AgentLoungeSwitchUnread(
    val count: Int = 0,
    val dot: Boolean = false,
)

/**
 * State and actions for the under-header two-tab switch (`Lounge | Private`) on an eligible agent
 * Lounge or agent DM conversation page.
 */
internal class AgentLoungeConversationSwitch(
    val activeSide: AgentLoungeConversationSide,
    val loungeUnread: AgentLoungeSwitchUnread = AgentLoungeSwitchUnread(),
    val privateUnread: AgentLoungeSwitchUnread = AgentLoungeSwitchUnread(),
    val switching: Boolean = false,
    val onSelectLounge: () -> Unit,
    val onSelectPrivate: () -> Unit,
)

/**
 * Whether the under-header Lounge / Private switch shows for this conversation: behind
 * `agent_lounge_v0` (and not a Guest), only on top-level agent Lounge channels or eligible agent
 * DMs — never on reply threads, regular channels, human DMs, task conversations, personal
 * assistants, App Bots, or deleted agents.
 */
internal fun shouldShowAgentLoungeConversationSwitch(
    enabled: Boolean,
    surfaceKind: String,
    isLoungeConversation: Boolean,
    agentId: String?,
    personalAssistantAgentIds: Set<String>,
    isAppBot: Boolean = false,
    isDeletedAgent: Boolean = false,
): Boolean {
    if (!enabled) return false
    val cleanAgentId = agentId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    if (cleanAgentId in personalAssistantAgentIds || isAppBot || isDeletedAgent) return false
    return when (surfaceKind) {
        RaftThreadSurfaceKind.Channel -> isLoungeConversation
        RaftThreadSurfaceKind.DirectMessage -> true
        else -> false
    }
}

/**
 * Unread badge for the opposite side of [activeSide] on the under-header tab strip (web #15991):
 * muted sides show nothing, numeric unread counts win over the quiet dot, and live followed-thread
 * unread drives the Lounge side once unread enrichment is available.
 */
internal fun agentLoungeSwitchUnread(
    activeSide: AgentLoungeConversationSide,
    agentId: String,
    directMessages: List<HomeDirectMessageRow>,
    standaloneLounges: List<HomeStandaloneLoungeRow> = emptyList(),
    knownLounges: Map<String, RaftAgentLounge> = emptyMap(),
    markedUnreadIds: Set<String> = emptySet(),
    followedThreadUnread: Map<String, HomeFollowedThreadUnread> = emptyMap(),
    useLiveFollowedThreadUnread: Boolean = false,
): Pair<AgentLoungeSwitchUnread, AgentLoungeSwitchUnread> {
    val cleanAgentId = agentId.trim()
    if (cleanAgentId.isEmpty()) return AgentLoungeSwitchUnread() to AgentLoungeSwitchUnread()
    val agentDm =
        directMessages.firstOrNull {
            it.directMessagePeerType.equals("agent", ignoreCase = true) &&
                it.presenceMemberId?.value == cleanAgentId
        }
    val privateUnread =
        if (activeSide == AgentLoungeConversationSide.Private || agentDm == null || agentDm.activityMuted) {
            AgentLoungeSwitchUnread()
        } else if (agentDm.unreadCount > 0) {
            AgentLoungeSwitchUnread(count = agentDm.unreadCount)
        } else if (agentDm.threadId.value in markedUnreadIds) {
            AgentLoungeSwitchUnread(dot = true)
        } else {
            AgentLoungeSwitchUnread()
        }
    val loungeUnread =
        if (activeSide == AgentLoungeConversationSide.Lounge) {
            AgentLoungeSwitchUnread()
        } else {
            val standalone = standaloneLounges.firstOrNull { it.agentId == cleanAgentId }
            val summary = agentDm?.lounge ?: standalone?.lounge
            val known =
                knownLounges.values.firstOrNull { it.agentId == cleanAgentId }
                    ?: summary?.let { knownLounges[it.channelId.value] }
            if (known?.activityMuted == true) {
                AgentLoungeSwitchUnread()
            } else {
                val targetChannelId = (known?.channelId ?: summary?.channelId)?.value
                val livePair =
                    if (useLiveFollowedThreadUnread && targetChannelId != null) {
                        var count = 0
                        var mention = false
                        for (thread in followedThreadUnread.values) {
                            if (thread.unreadCount > 0 && thread.parentChannelId.value == targetChannelId) {
                                count += thread.unreadCount
                                if (thread.hasMention) mention = true
                            }
                        }
                        count to mention
                    } else {
                        null
                    }
                val entry =
                    agentLoungeSidebarEntry(
                        summary = summary,
                        known = known,
                        followedThreadUnreadCount = if (useLiveFollowedThreadUnread) (livePair?.first ?: 0) else null,
                        followedThreadHasMention = if (useLiveFollowedThreadUnread) (livePair?.second == true) else null,
                    )
                val channelId = entry?.channelId?.value ?: targetChannelId
                val markedUnread = channelId != null && channelId in markedUnreadIds
                when {
                    entry != null && entry.unreadCount > 0 -> AgentLoungeSwitchUnread(count = entry.unreadCount)
                    entry != null && entry.mentioned -> AgentLoungeSwitchUnread(dot = true)
                    markedUnread -> AgentLoungeSwitchUnread(dot = true)
                    else -> AgentLoungeSwitchUnread()
                }
            }
        }
    return loungeUnread to privateUnread
}

/** Formatted Space Mono badge for a [RaftSegment] (`"1"`..`"99+"`, `"•"` for dot-only, else null). */
internal fun formatAgentLoungeSwitchBadge(unread: AgentLoungeSwitchUnread): String? =
    when {
        unread.count > 0 -> formatHomeSidebarBadgeCount(unread.count)
        unread.dot -> "•"
        else -> null
    }

/**
 * Under-header 2-tab switch (`Lounge | Private`) sitting directly below the conversation header,
 * matching the compact left-aligned Task v2 [RaftTabStrip] pattern and using Raft's pink
 * [RaftUnreadCount] / [RaftStatusDot] unread language on the inactive tab.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun AgentLoungeConversationTabStrip(
    switch: AgentLoungeConversationSwitch,
    modifier: Modifier = Modifier,
    wideWindow: Boolean = raftWindowWide(),
) {
    val loungeLabel = stringResource(Res.string.agent_lounge_label)
    val privateLabel = stringResource(Res.string.agent_lounge_private_label)
    val loungeSelected = switch.activeSide == AgentLoungeConversationSide.Lounge
    val privateSelected = switch.activeSide == AgentLoungeConversationSide.Private
    RaftTabStrip(
        modifier = modifier.fillMaxWidth().testTag(AgentLoungeTestTags.TabStrip),
    ) {
        AgentLoungeConversationTab(
            label = loungeLabel,
            selected = loungeSelected,
            enabled = !switch.switching || loungeSelected,
            iconName = AgentLoungeIcon,
            unread = switch.loungeUnread,
            testTag = AgentLoungeTestTags.TabLounge,
            onClick = {
                if (!loungeSelected && !switch.switching) {
                    switch.onSelectLounge()
                }
            },
        )
        AgentLoungeConversationTab(
            label = privateLabel,
            selected = privateSelected,
            enabled = !switch.switching || privateSelected,
            iconName = RaftLucideIconName.Lock,
            unread = switch.privateUnread,
            testTag = AgentLoungeTestTags.TabPrivate,
            onClick = {
                if (!privateSelected && !switch.switching) {
                    switch.onSelectPrivate()
                }
            },
        )
    }
}

@Composable
private fun AgentLoungeConversationTab(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    iconName: RaftLucideIconName,
    unread: AgentLoungeSwitchUnread,
    testTag: String,
    onClick: () -> Unit,
) {
    val showUnread = !selected && (unread.count > 0 || unread.dot)
    Box(
        modifier = if (showUnread) Modifier.padding(end = 6.dp) else Modifier,
    ) {
        RaftTab(
            text = label,
            selected = selected,
            onClick = onClick,
            modifier = Modifier.testTag(testTag),
            enabled = enabled,
            icon = { RaftContentLucideIcon(iconName, size = 16.dp) },
        )
        if (showUnread) {
            if (unread.count > 0) {
                RaftUnreadCount(
                    count = unread.count,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-4).dp)
                            .zIndex(1f),
                )
            } else {
                RaftStatusDot(
                    variant = RaftStatusVariant.Accent,
                    size = RaftStatusSize.Sm,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-2).dp, y = 2.dp)
                            .zIndex(1f),
                )
            }
        }
    }
}

/**
 * "{Name}'s Lounge", or the bare "Lounge" while the agent's name is unknown (web `formatLoungeTitle`,
 * slock #16282): never anything read from the Lounge's channel name.
 */
@Composable
internal fun agentLoungeTitle(agentName: String?): String {
    val name = loungeAgentDisplayName(agentName)
    return if (name != null) stringResource(Res.string.agent_lounge_title, name) else stringResource(Res.string.agent_lounge_label)
}

/** [agentLoungeTitle] outside composition. */
internal fun agentLoungeTitleNow(agentName: String?): String {
    val name = loungeAgentDisplayName(agentName)
    return if (name != null) Res.string.agent_lounge_title.format(name).resolve() else Res.string.agent_lounge_label.desc().resolve()
}

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

/** The name of [lounge]'s agent: the member directory's display name when it knows the agent, else the Lounge's own (null: unknown). */
@Composable
internal fun RaftAgentLounge.rememberDisplayAgentName(memberDisplayName: String? = null): String? =
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
            kind = RaftAvatarKind.Agent,
            avatarWireValue = avatarWireValue,
            label = label,
            avatarSize = avatarSize,
            cacheSubject = raftMemberAvatarCacheSubject(MemberKind.Agent, agentId),
            cacheServerId = cacheServerId,
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
            RaftContentLucideIcon(AgentLoungeIcon, size = glyphSize)
        }
    }
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
    ThreadArchivedChannelNotice(
        canUnarchive = canUnarchive,
        unarchiving = unarchiving,
        onUnarchive = onUnarchive,
        modifier = modifier,
        noticeTag = AgentLoungeTestTags.ArchivedNotice,
        unarchiveTag = AgentLoungeTestTags.UnarchiveAction,
    )
}

/**
 * Open an agent's Lounge (POST /agents/:id/lounge makes it the first time) and remember it. Its
 * realtime room is joined by the conversation page it opens, which holds the room lease for as
 * long as it shows (web `joinRealtimeChannel` after `openLounge`); joining here as well would
 * leave the room joined when the navigation does not happen. The caller navigates on success and
 * shows the openFailed toast (with the trace id) on any failure.
 */
internal suspend fun openAgentLounge(
    networkClient: RaftNetworkClient,
    serverId: String,
    agentId: String,
    agentName: String?,
): DataResult<AgentLoungeOpenResult> {
    val result = AgentLoungeApi(networkClient).openLounge(ServerId(serverId), agentId)
    if (result is DataResult.Content) {
        RaftAgentLounges.rememberRow(
            serverId,
            result.data.channel,
            canPost = result.data.canPost,
            agentName = agentName,
            listed = result.data.listed,
        )
    }
    return result
}

/**
 * Find an agent's existing Lounge without creating one (GET /agents/:id/lounge) and remember it, for
 * `lounge:@agent` refs; [openAgentLounge] (POST) stays the profile's Lounge button.
 */
internal suspend fun lookupAgentLounge(
    networkClient: RaftNetworkClient,
    serverId: String,
    agentId: String,
    agentName: String?,
): DataResult<AgentLoungeOpenResult> {
    val result = AgentLoungeApi(networkClient).lookupLounge(ServerId(serverId), agentId)
    if (result is DataResult.Content) {
        RaftAgentLounges.rememberRow(
            serverId,
            result.data.channel,
            canPost = result.data.canPost,
            agentName = agentName,
            listed = result.data.listed,
        )
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
                    openAgentLounge(networkClient, cleanServerId, agentId, agentName)
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
            openFailedMessage = { latestOpenFailed },
            onError = { message, traceId -> bridge.errorToast(message, traceId) },
        )
    }
}

/**
 * What a conversation page needs to draw itself as (or inside) an agent's Lounge, and the
 * under-header Lounge / Private tab switch. Built by the route ([rememberThreadPageLounge]); null
 * pieces are off.
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
    /** Under-header `Lounge | Private` tab switch; null when not shown. */
    val conversationSwitch: AgentLoungeConversationSwitch? = null,
    /**
     * An archived (non-Lounge) channel's banner Unarchive, or its reply thread's. Built here, beside
     * the Lounge's, because the route's page lambda is at the JVM's capture limit.
     */
    val archivedChannel: ThreadPageArchivedChannel? = null,
    /** Lounges are on for this reader (the server's flag, not a Guest): `lounge:@agent…` refs may chip. */
    val loungesEnabled: Boolean = false,
    /** Resolved display name for the Lounge's agent when known from the member directory. */
    val agentDisplayNameOverride: String? = null,
) {
    val agentName: String
        get() = loungeAgentDisplayName(agentDisplayNameOverride) ?: lounge?.displayAgentName.orEmpty()
}

/** The channel id a conversation page shows: its own (channel) or its parent's (reply thread). */
internal fun RaftThreadScreenPayload.agentLoungeLookupChannelId(): String? =
    when (surfaceKind) {
        RaftThreadSurfaceKind.Channel ->
            (streamChannelId?.takeIf { it.isNotBlank() } ?: sourceThreadId?.takeIf { it.isNotBlank() })
        RaftThreadSurfaceKind.ReplyThread -> parentChannelId?.takeIf { it.isNotBlank() }
        else -> null
    }

/**
 * A Lounge page's Mute row (#proj-chat:#44): Mute is removed from Lounges, while an already-muted
 * Lounge keeps its Unmute row so users can clear stored mute state.
 */
internal fun RaftAgentLounge.agentLoungeActivityMuteState(joinedHint: Boolean = false): ThreadActivityMuteUiState? {
    val preference = activityNotificationPreference ?: return null
    if (!preference.activityMuted) return null
    return ThreadActivityMuteUiState(
        channelId = channelId.value,
        muted = true,
        supported = preference.activityMuteSupported,
        muteFromSeq = preference.muteFromSeq,
    )
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
    /** Replaces the active conversation in place (for the under-header Lounge / Private switch). */
    onReplaceConversation: (ThreadLaunchConversationIdentity) -> Unit = onOpenConversation,
    homeSnapshot: build.raft.app.data.HomeSnapshot? = null,
    sharedHomeStore: SharedHomeStore? = null,
    authStore: RaftAuthSessionStore? = null,
    serverMembersStore: SharedServerMembersStore? = null,
): ThreadPageLounge {
    val lounge = rememberAgentLounge(serverId, payload.agentLoungeLookupChannelId())
    val allKnownLounges by RaftAgentLounges.lounges.collectAsState()
    val serverKnownLounges = serverId?.let { allKnownLounges[it] }.orEmpty()
    val dmAgentId = payload.agentLoungeDmAgentId()
    val isLoungeConversation = payload.surfaceKind == RaftThreadSurfaceKind.Channel && lounge != null
    val targetAgentId = if (isLoungeConversation) lounge?.agentId else dmAgentId
    val avatar = rememberRaftMemberAvatarWireValue(memberAvatarResolver, lounge?.agentId ?: dmAgentId)
    val enabled = rememberAgentLoungeEnabled(flags, serverId, serverRole)
    val opener = rememberAgentLoungeOpener(bridge, storeScope)
    val opening by opener.opening.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var unarchiving by remember { mutableStateOf(false) }
    var openingPrivateDm by remember(serverId, targetAgentId) { mutableStateOf(false) }
    val errorStrings = localizedErrorPresentationStrings()
    val dmOpenFailedMessage = stringResource(Res.string.agent_lounge_dm_open_failed)
    val latestDmOpenFailedMessage by rememberUpdatedState(dmOpenFailedMessage)
    val wideWindow = raftWindowWide()
    val targetAppBot = rememberRaftMemberAppBot(targetAgentId)
    val cleanServerId = serverId?.takeIf { it.isNotBlank() }
    val targetMemberProfile =
        remember(serverMembersStore, cleanServerId, targetAgentId) {
            if (cleanServerId != null && targetAgentId != null) {
                serverMembersStore?.memberProfile(ServerId(cleanServerId), targetAgentId)
            } else {
                null
            }
        }
    val dmAgentName =
        loungeAgentDisplayName(targetMemberProfile?.displayName)
            ?: loungeAgentDisplayName(lounge?.displayAgentName)
            ?: loungeAgentDisplayName(payload.title.trim().removePrefix("@"))
    val showConversationSwitch =
        shouldShowAgentLoungeConversationSwitch(
            enabled = enabled,
            surfaceKind = payload.surfaceKind,
            isLoungeConversation = isLoungeConversation,
            agentId = targetAgentId,
            personalAssistantAgentIds = personalAssistantAgentIds,
            isAppBot = targetAppBot != null,
            isDeletedAgent = targetMemberProfile?.agentDeletedAt?.isNotBlank() == true,
        ) && targetMemberProfile?.assistantPausedAt == null
    val conversationSwitch =
        if (showConversationSwitch && targetAgentId != null) {
            val activeSide =
                if (isLoungeConversation) AgentLoungeConversationSide.Lounge else AgentLoungeConversationSide.Private
            val snapshotForServer =
                homeSnapshot?.takeIf { cleanServerId == null || it.server.id.value == cleanServerId }
            val (loungeUnread, privateUnread) =
                remember(
                    activeSide,
                    targetAgentId,
                    snapshotForServer,
                    serverKnownLounges,
                ) {
                    agentLoungeSwitchUnread(
                        activeSide = activeSide,
                        agentId = targetAgentId,
                        directMessages = snapshotForServer?.directMessages.orEmpty(),
                        standaloneLounges = snapshotForServer?.standaloneLounges.orEmpty(),
                        knownLounges = serverKnownLounges,
                        markedUnreadIds = snapshotForServer?.markedUnreadIds.orEmpty(),
                        followedThreadUnread = snapshotForServer?.followedThreadUnread.orEmpty(),
                        useLiveFollowedThreadUnread = snapshotForServer?.unreadEnrichmentAvailable == true,
                    )
                }
            AgentLoungeConversationSwitch(
                activeSide = activeSide,
                loungeUnread = loungeUnread,
                privateUnread = privateUnread,
                switching = openingPrivateDm || targetAgentId in opening,
                onSelectLounge = {
                    RaftProductEvents.conversationHeaderActionSelected(
                        action = RaftConversationHeaderAction.OpenLounge,
                        surface = RaftConversationHeaderSurface.Header,
                        conversation = RaftConversationHeaderKind.Dm,
                        compact = !wideWindow,
                    )
                    val directMessages = snapshotForServer?.directMessages.orEmpty()
                    val knownLoungeId =
                        serverKnownLounges.values.firstOrNull { it.agentId == targetAgentId }?.channelId?.value
                            ?: directMessages
                                .firstOrNull {
                                    it.directMessagePeerType.equals("agent", ignoreCase = true) &&
                                        it.presenceMemberId?.value == targetAgentId
                                }
                                ?.lounge
                                ?.channelId
                                ?.value
                    val immediateIdentity =
                        knownLoungeId?.let { id ->
                            agentLoungeConversationIdentityOrNull(cleanServerId, id, directMessages)
                        }
                    if (immediateIdentity != null) {
                        onReplaceConversation(immediateIdentity)
                    } else {
                        opener.open(cleanServerId, targetAgentId, dmAgentName) { opened ->
                            onReplaceConversation(opened.conversationIdentity())
                        }
                    }
                },
                onSelectPrivate = {
                    val existingDm =
                        snapshotForServer?.directMessages?.firstOrNull {
                            it.directMessagePeerType.equals("agent", ignoreCase = true) &&
                                it.presenceMemberId?.value == targetAgentId
                        }
                    if (existingDm != null) {
                        onReplaceConversation(existingDm.toThreadLaunchConversationIdentity())
                    } else if (!openingPrivateDm && authStore != null && cleanServerId != null) {
                        openingPrivateDm = true
                        scope.launch {
                            val result =
                                try {
                                    val stores = build.raft.app.data.RaftSharedStoreRegistry.get(bridge)
                                    val member =
                                        targetMemberProfile
                                            ?: MemberProfile(
                                                id = MemberId(targetAgentId),
                                                kind = MemberKind.Agent,
                                                displayName = dmAgentName.orEmpty(),
                                                handle = dmAgentName.orEmpty(),
                                                serverId = ServerId(cleanServerId),
                                            )
                                    openMemberDirectMessage(
                                        member = member,
                                        authStore = authStore,
                                        channelRepository =
                                            RealChannelRepository(
                                                sessionStore = authStore,
                                                networkClient = stores.networkClient(storeScope),
                                            ),
                                        sharedHomeStore = sharedHomeStore,
                                    )
                                } finally {
                                    openingPrivateDm = false
                                }
                            when (result) {
                                is DataResult.Content ->
                                    onReplaceConversation(result.data.conversation.toThreadLaunchConversationIdentity())
                                is DataResult.Error ->
                                    bridge.errorToast(latestDmOpenFailedMessage, result.error.traceId)
                                is DataResult.NoPermission ->
                                    bridge.errorToast(latestDmOpenFailedMessage, result.blocker.traceId)
                                is DataResult.Empty ->
                                    bridge.errorToast(latestDmOpenFailedMessage, null)
                                null -> Unit
                            }
                        }
                    } else if (!openingPrivateDm) {
                        bridge.errorToast(latestDmOpenFailedMessage, null)
                    }
                },
            )
        } else {
            null
        }
    val roleMayArchive = build.raft.app.model.serverPermissionsForRole(serverRole).canManageSettings
    var channelUnarchiving by remember { mutableStateOf(false) }
    val archivedChannel =
        if (lounge == null && threadFooterShowsArchived(payload)) {
            ThreadPageArchivedChannel(
                canUnarchive = threadArchivedChannelCanUnarchive(payload, roleMayArchive),
                unarchiving = channelUnarchiving,
                onUnarchive = { onUnarchived ->
                    // A reply thread's Unarchive restores its parent channel.
                    val target = payload.agentLoungeLookupChannelId()
                    if (target != null && cleanServerId != null && !channelUnarchiving) {
                        channelUnarchiving = true
                        scope.launch {
                            val stores = build.raft.app.data.RaftSharedStoreRegistry.get(bridge)
                            // The web's channelStore.unarchiveChannel: POST channels/:id/unarchive.
                            val result =
                                try {
                                    AgentLoungeApi(stores.networkClient(storeScope))
                                        .unarchive(ServerId(cleanServerId), build.raft.app.model.ChannelId(target))
                                } finally {
                                    channelUnarchiving = false
                                }
                            when (result) {
                                is DataResult.Content -> {
                                    bridge.logDebug("thread_channel_unarchived channelId=$target")
                                    // Back in the sidebar, and this page reloads with its composer.
                                    stores.sharedHomeStore(storeScope)?.dispatch(build.raft.app.data.SharedHomeIntent.Refresh)
                                    onUnarchived()
                                }
                                is DataResult.Error ->
                                    bridge.errorToast(result.error.userFacingMessage(errorStrings), result.error.traceId)
                                is DataResult.NoPermission ->
                                    bridge.errorToast(result.blocker.userFacingMessage(errorStrings), result.blocker.traceId)
                                else -> Unit
                            }
                        }
                    }
                },
            )
        } else {
            null
        }
    return ThreadPageLounge(
        archivedChannel = archivedChannel,
        loungesEnabled = enabled,
        lounge = lounge,
        agentAvatarWireValue = avatar,
        cacheServerId = serverId,
        canUnarchive = build.raft.app.model.serverPermissionsForRole(serverRole).canManageSettings,
        unarchiving = unarchiving,
        onUnarchive = {
            val target = lounge
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
                    // The server's own reason, as the web's LoungeUnarchiveAction shows it.
                    when (result) {
                        is DataResult.Error -> bridge.errorToast(result.error.userFacingMessage(errorStrings), result.error.traceId)
                        is DataResult.NoPermission ->
                            bridge.errorToast(result.blocker.userFacingMessage(errorStrings), result.blocker.traceId)
                        else -> Unit
                    }
                }
            }
        },
        onOpenAgentProfile = lounge?.let { { onOpenAgentProfile(it.agentId) } },
        conversationSwitch = conversationSwitch,
        agentDisplayNameOverride = targetMemberProfile?.displayName,
    )
}

/**
 * A Lounge's page: no Join step (the first post joins), and posting follows what the Lounge says
 * ([RaftAgentLounge.postable]: not archived, not refused by the open answer). Unchanged elsewhere.
 */
internal fun RaftThreadScreenPayload.withAgentLoungeAccess(
    lounge: RaftAgentLounge?,
    accessResolution: ThreadAccessResolution = ThreadAccessResolution.Resolved,
): RaftThreadScreenPayload {
    if (lounge == null || surfaceKind != RaftThreadSurfaceKind.Channel) return this
    // The store closed writes for a terminal access state (NoPermission / Error): a Lounge never
    // reopens them.
    if (
        accessResolution == ThreadAccessResolution.NoPermission ||
        accessResolution == ThreadAccessResolution.Error ||
        errorCode != null
    ) {
        return this
    }
    val postable = lounge.postable
    // Task writes need post authority too (web canWriteTasksInChannel reads the Lounge's `canPost`).
    val taskStatusActions = showTaskStatusActions && postable
    val asTaskToggle = composer.showAsTaskToggle && postable
    if (
        joined && canSend == postable && composer.enabled == postable &&
        showTaskStatusActions == taskStatusActions && composer.showAsTaskToggle == asTaskToggle
    ) {
        return this
    }
    return copy(
        joined = true,
        canSend = postable,
        showTaskStatusActions = taskStatusActions,
        composer = composer.copy(enabled = postable, showAsTaskToggle = asTaskToggle),
    )
}

/** "{Name}'s Lounge" while the composer belongs to a Lounge (its placeholder names the Lounge); null elsewhere. */
internal val LocalAgentLoungeComposerName = androidx.compose.runtime.staticCompositionLocalOf<String?> { null }

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
 * "{Name}'s Lounge" (the bare "Lounge" while its agent's name is unknown) for a place known only by
 * its channel id (an Activity / search / draft row, a permalink), or null when that channel is not
 * a Lounge this client knows. Use it where the place would otherwise read `#<its slug>`.
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
