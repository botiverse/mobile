package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuSeparator
import build.raft.designsystem.components.RaftReadOnlyComposer
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftStatusSize
import build.raft.designsystem.components.RaftStatusVariant
import build.raft.designsystem.components.RaftUnreadCount
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftMenuMetrics
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftUiPalette
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
    const val ConversationMenuTrigger = "agent-conversation-menu-trigger"
    const val ConversationMenu = "agent-conversation-menu"
    const val ConversationMenuLounge = "agent-conversation-menu-lounge"
    const val ConversationMenuPrivate = "agent-conversation-menu-private"
    const val ConversationMenuProfile = "agent-conversation-menu-profile"
    const val ConversationMenuUnread = "agent-conversation-menu-unread"
    const val ConversationAvatarMark = "agent-conversation-avatar-mark"
    const val ConversationAvatarDot = "agent-conversation-avatar-dot"
}

/** Which side of an agent's paired conversation (Lounge vs Private DM) is currently shown. */
internal enum class AgentLoungeConversationSide {
    Lounge,
    Private,
}

/** Unread indicator shown on the inactive side of the avatar switcher menu. */
internal data class AgentLoungeSwitchUnread(
    val count: Int = 0,
    val dot: Boolean = false,
)

/**
 * State and actions for the B1 header avatar switcher menu (`Lounge / Private chat / View profile`,
 * web `AgentConversationMenu.tsx`, `botiverse/slock#16450`) on an eligible agent Lounge or agent DM
 * conversation page.
 */
internal class AgentLoungeConversationSwitch(
    val activeSide: AgentLoungeConversationSide,
    val loungeUnread: AgentLoungeSwitchUnread = AgentLoungeSwitchUnread(),
    val privateUnread: AgentLoungeSwitchUnread = AgentLoungeSwitchUnread(),
    val switching: Boolean = false,
    val onSelectLounge: () -> Unit,
    val onSelectPrivate: () -> Unit,
) {
    /** Unread state on the side that is NOT currently open. */
    val otherSideUnread: AgentLoungeSwitchUnread
        get() =
            when (activeSide) {
                AgentLoungeConversationSide.Lounge -> privateUnread
                AgentLoungeConversationSide.Private -> loungeUnread
            }

    /** True when the opposite side has any unread count or marked-unread dot. */
    val hasOtherSideAttention: Boolean
        get() = otherSideUnread.count > 0 || otherSideUnread.dot
}

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
 * Extra width the B1 avatar switcher pill takes beside a plain avatar (web
 * `CONVERSATION_MENU_EXTRA_WIDTH = 26`: gap before chevron + 14dp chevron + end inset).
 */
internal const val AgentConversationAvatarMenuExtraWidthDp = 26f

/**
 * An agent's face in the header of its Lounge and private DM (`botiverse/slock#16450`,
 * `AgentConversationAvatar` in `LoungeAvatar.tsx`): the agent avatar with a corner mark saying
 * which side is open (Sofa on `info-soft` for Lounge, Lock on `fill-muted` for Private chat),
 * ringed with `layer-canvas`, and an attention dot on the mark's outer top-right corner when the
 * other side has unread.
 */
@Composable
internal fun RaftAgentConversationAvatar(
    agentId: String?,
    avatarWireValue: String?,
    label: String,
    cacheServerId: String?,
    side: AgentLoungeConversationSide,
    attention: Boolean,
    avatarSize: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    // Web LOUNGE_BADGE_CLASS / PRIVATE_BADGE_CLASS: 14dp on 35dp phone head, 12dp on 27dp desktop head.
    val badgeSize = (avatarSize * 0.4f).coerceAtLeast(12.dp)
    val glyphSize = (badgeSize * 0.68f).coerceAtLeast(8.dp)
    val badgeFill =
        if (side == AgentLoungeConversationSide.Lounge) {
            colors.infoSoft.compositeOver(colors.layerCanvas)
        } else {
            colors.fillMuted.compositeOver(colors.layerCanvas)
        }
    val badgeShape = RoundedCornerShape(3.dp)
    val badgeRingShape = RoundedCornerShape(4.dp)
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
                    .zIndex(1f)
                    .testTag(AgentLoungeTestTags.ConversationAvatarMark),
            contentAlignment = Alignment.Center,
        ) {
            // 2dp layer-canvas outer outline (web `outline-2 outline-solid outline-layer-canvas`) + 1dp ink border.
            Box(
                modifier =
                    Modifier
                        .size(badgeSize + 3.dp)
                        .background(colors.layerCanvas, badgeRingShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(badgeSize)
                            .background(badgeFill, badgeShape)
                            .border(1.dp, RaftUiPalette.stickerInk, badgeShape),
                    contentAlignment = Alignment.Center,
                ) {
                    RaftLucideIcon(
                        name = if (side == AgentLoungeConversationSide.Lounge) AgentLoungeIcon else RaftLucideIconName.Lock,
                        modifier = Modifier.size(glyphSize),
                        color = raftArgbHex(colors.foreground),
                        strokeWidth = 2.6f,
                    )
                }
            }
            if (attention) {
                RaftStatusDot(
                    variant = RaftStatusVariant.Accent,
                    size = RaftStatusSize.Sm,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-4).dp)
                            .zIndex(2f)
                            .testTag(AgentLoungeTestTags.ConversationAvatarDot),
                )
            }
        }
    }
}

/**
 * Offscreen 28dp capture of [RaftAgentConversationAvatar] for the iOS native navigation bar
 * ([RaftNativeNavBarTitleImageCapture]): insets the avatar slightly so the corner mark and
 * attention dot stay completely inside the captured square without clipping.
 */
@Composable
internal fun RaftNativeNavBarAgentConversationAvatar(
    agentId: String?,
    avatarWireValue: String?,
    label: String,
    cacheServerId: String?,
    side: AgentLoungeConversationSide,
    attention: Boolean,
    captureSize: Dp = RaftNativeNavBarTitleImageSize,
) {
    val colors = RaftTheme.colors
    val faceSize = (captureSize - 4.dp).coerceAtLeast(20.dp)
    val badgeSize = 11.dp
    val glyphSize = 7.dp
    val badgeFill =
        if (side == AgentLoungeConversationSide.Lounge) {
            colors.infoSoft.compositeOver(colors.layerCanvas)
        } else {
            colors.fillMuted.compositeOver(colors.layerCanvas)
        }
    val badgeShape = RoundedCornerShape(3.dp)
    val badgeRingShape = RoundedCornerShape(4.dp)
    Box(modifier = Modifier.size(captureSize).clearAndSetSemantics {}) {
        RaftAppAvatar(
            kind = RaftAvatarKind.Agent,
            avatarWireValue = avatarWireValue,
            label = label,
            avatarSize = faceSize,
            cacheSubject = raftMemberAvatarCacheSubject(MemberKind.Agent, agentId),
            cacheServerId = cacheServerId,
            modifier = Modifier.align(Alignment.TopStart).padding(top = 1.dp, start = 1.dp),
        )
        Box(
            modifier = Modifier.align(Alignment.BottomEnd),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.size(badgeSize + 2.dp).background(colors.layerCanvas, badgeRingShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(badgeSize)
                            .background(badgeFill, badgeShape)
                            .border(1.dp, RaftUiPalette.stickerInk, badgeShape),
                    contentAlignment = Alignment.Center,
                ) {
                    RaftLucideIcon(
                        name = if (side == AgentLoungeConversationSide.Lounge) AgentLoungeIcon else RaftLucideIconName.Lock,
                        modifier = Modifier.size(glyphSize),
                        color = raftArgbHex(colors.foreground),
                        strokeWidth = 2.6f,
                    )
                }
            }
            if (attention) {
                RaftStatusDot(
                    variant = RaftStatusVariant.Accent,
                    size = RaftStatusSize.Xs,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 1.dp, y = (-2).dp).zIndex(2f),
                )
            }
        }
    }
}

/**
 * B1 conversation avatar switcher button + dropdown menu (`botiverse/slock#16450`, `#proj-chat:#39`):
 * the agent's avatar with its active-side corner mark (Sofa or Lock), opposite-side attention dot,
 * and up/down chevron form one button in the header. Tapping it opens a [RaftDropdownMenu] with
 * radio items for Lounge and Private chat (each with its audience line, checkmark on current, and
 * unread indicator on the other side), a separator, and View profile.
 */
@Composable
internal fun AgentConversationAvatarMenuButton(
    agentId: String?,
    agentName: String,
    avatarWireValue: String?,
    cacheServerId: String?,
    switch: AgentLoungeConversationSwitch,
    avatarSize: Dp,
    onViewProfile: (() -> Unit)?,
    modifier: Modifier = Modifier,
    initialExpanded: Boolean = false,
) {
    var menuExpanded by remember { mutableStateOf(initialExpanded) }
    val colors = RaftTheme.colors
    val wideWindow = raftWindowWide()
    val windowWidth = raftWindowWidth()
    val menuWidth =
        if (windowWidth > 0.dp) {
            minOf(320.dp, (windowWidth - 24.dp).coerceAtLeast(240.dp))
        } else {
            320.dp
        }
    val cleanName = loungeAgentDisplayName(agentName) ?: agentName.trim()
    val loungeLabel = stringResource(Res.string.agent_lounge_label)
    val privateChatLabel = stringResource(Res.string.agent_lounge_private_chat_label)
    val loungeAudience = stringResource(Res.string.agent_lounge_audience_notice)
    val privateAudience = stringResource(Res.string.agent_lounge_private_audience_notice, cleanName)
    val viewProfileLabel = stringResource(Res.string.agent_lounge_view_profile)
    val triggerLabelBase =
        if (switch.activeSide == AgentLoungeConversationSide.Lounge) {
            stringResource(Res.string.agent_lounge_switch_trigger_lounge, cleanName)
        } else {
            stringResource(Res.string.agent_lounge_switch_trigger_private, cleanName)
        }
    val otherUnread = switch.otherSideUnread
    val otherUnreadDescription =
        if (otherUnread.count > 0) {
            stringResource(Res.string.agent_lounge_switch_trigger_other_unread, otherUnread.count)
        } else {
            null
        }
    val triggerContentDescription =
        if (otherUnreadDescription != null) "$triggerLabelBase, $otherUnreadDescription" else triggerLabelBase
    val triggerShape = RoundedCornerShape(RaftRadius.Chip)
    val loungeSelected = switch.activeSide == AgentLoungeConversationSide.Lounge
    val privateSelected = switch.activeSide == AgentLoungeConversationSide.Private

    Box(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .heightIn(min = if (wideWindow) 34.dp else 44.dp)
                    .background(if (menuExpanded) colors.fillMedium else Color.Transparent, triggerShape)
                    .raftClickable(
                        onClick = { menuExpanded = !menuExpanded },
                        role = Role.Button,
                        tier = RaftHoverTier.Medium,
                        shape = triggerShape,
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = triggerContentDescription
                    }
                    .testTag(AgentLoungeTestTags.ConversationMenuTrigger)
                    .padding(start = 2.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            // Leave clear breathing room past the 3dp corner mark overhang (and 4dp attention dot).
            horizontalArrangement = Arrangement.spacedBy(if (switch.hasOtherSideAttention) 8.dp else 6.dp),
        ) {
            RaftAgentConversationAvatar(
                agentId = agentId,
                avatarWireValue = avatarWireValue,
                label = cleanName,
                cacheServerId = cacheServerId,
                side = switch.activeSide,
                attention = switch.hasOtherSideAttention,
                avatarSize = avatarSize,
            )
            RaftLucideIcon(
                name = RaftLucideIconName.ChevronsUpDown,
                modifier = Modifier.size(14.dp),
                color = raftArgbHex(colors.foregroundMuted),
            )
        }
        RaftDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            width = menuWidth,
            offset = 6.dp,
            modifier = Modifier.testTag(AgentLoungeTestTags.ConversationMenu),
        ) {
            AgentConversationAvatarMenuRows(
                agentName = cleanName,
                switch = switch,
                onViewProfile = onViewProfile,
                onDismiss = { menuExpanded = false },
            )
        }
    }
}

/**
 * The rows inside [AgentConversationAvatarMenuButton]'s dropdown menu: Lounge radio item,
 * Private chat radio item, separator, and View profile.
 */
@Composable
internal fun AgentConversationAvatarMenuRows(
    agentName: String,
    switch: AgentLoungeConversationSwitch,
    onViewProfile: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val cleanName = loungeAgentDisplayName(agentName) ?: agentName.trim()
    val loungeLabel = stringResource(Res.string.agent_lounge_label)
    val privateChatLabel = stringResource(Res.string.agent_lounge_private_chat_label)
    val loungeAudience = stringResource(Res.string.agent_lounge_audience_notice)
    val privateAudience = stringResource(Res.string.agent_lounge_private_audience_notice, cleanName)
    val viewProfileLabel = stringResource(Res.string.agent_lounge_view_profile)
    val loungeSelected = switch.activeSide == AgentLoungeConversationSide.Lounge
    val privateSelected = switch.activeSide == AgentLoungeConversationSide.Private

    AgentConversationMenuRadioItem(
        label = loungeLabel,
        description = loungeAudience,
        icon = AgentLoungeIcon,
        checked = loungeSelected,
        enabled = !switch.switching || loungeSelected,
        unread = switch.loungeUnread,
        testTag = AgentLoungeTestTags.ConversationMenuLounge,
        onClick = {
            onDismiss()
            if (!loungeSelected && !switch.switching) {
                switch.onSelectLounge()
            }
        },
    )
    AgentConversationMenuRadioItem(
        label = privateChatLabel,
        description = privateAudience,
        icon = RaftLucideIconName.Lock,
        checked = privateSelected,
        enabled = !switch.switching || privateSelected,
        unread = switch.privateUnread,
        testTag = AgentLoungeTestTags.ConversationMenuPrivate,
        onClick = {
            onDismiss()
            if (!privateSelected && !switch.switching) {
                switch.onSelectPrivate()
            }
        },
    )
    RaftMenuSeparator()
    RaftMenuItem(
        text = viewProfileLabel,
        onClick = {
            onDismiss()
            onViewProfile?.invoke()
        },
        enabled = onViewProfile != null,
        leadingIcon = { RaftContentLucideIcon(RaftLucideIconName.UserRound, size = 16.dp) },
        modifier = Modifier.testTag(AgentLoungeTestTags.ConversationMenuProfile),
    )
}

@Composable
private fun AgentConversationMenuRadioItem(
    label: String,
    description: String,
    icon: RaftLucideIconName,
    checked: Boolean,
    enabled: Boolean,
    unread: AgentLoungeSwitchUnread,
    testTag: String,
    onClick: () -> Unit,
) {
    val colors = RaftTheme.colors
    val ink = if (enabled) colors.foreground else colors.foregroundDisabled
    val iconInk = if (enabled) colors.foregroundMuted else colors.foregroundDisabled
    val shape = RoundedCornerShape(RaftMenuMetrics.ItemRadius)
    val showUnread = !checked && (unread.count > 0 || unread.dot)
    val unreadDescription =
        if (showUnread && unread.count > 0) {
            stringResource(Res.string.agent_lounge_switch_unread, unread.count)
        } else {
            null
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .raftClickable(
                    onClick = onClick,
                    enabled = enabled,
                    role = Role.RadioButton,
                    tier = RaftHoverTier.Highlight,
                    shape = shape,
                )
                .semantics { selected = checked }
                .testTag(testTag)
                .padding(horizontal = RaftMenuMetrics.ItemPaddingHorizontal, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(RaftMenuMetrics.ItemGap),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.padding(top = 2.dp).size(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            RaftLucideIcon(icon, modifier = Modifier.size(16.dp), color = raftArgbHex(iconInk))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            RaftText(
                text = label,
                style = RaftTheme.typography.callout.copy(fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium),
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RaftText(
                text = description,
                style = RaftTheme.typography.footnote,
                color = if (enabled) colors.foregroundMuted else colors.foregroundDisabled,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (checked) {
            Box(
                modifier = Modifier.padding(top = 2.dp).size(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                RaftLucideIcon(RaftLucideIconName.Check, modifier = Modifier.size(16.dp), color = raftArgbHex(ink))
            }
        } else if (showUnread) {
            Box(
                modifier = Modifier.padding(top = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (unread.count > 0) {
                    RaftUnreadCount(
                        count = unread.count,
                        contentDescription = unreadDescription,
                        modifier = Modifier.testTag(AgentLoungeTestTags.ConversationMenuUnread),
                    )
                } else {
                    RaftStatusDot(
                        variant = RaftStatusVariant.Accent,
                        size = RaftStatusSize.Sm,
                        modifier = Modifier.padding(top = 4.dp).testTag(AgentLoungeTestTags.ConversationMenuUnread),
                    )
                }
            }
        }
    }
}

/**
 * Pure builder for the iOS native navigation bar's B1 avatar switcher `UIMenu`
 * (`Lounge` radio, `Private chat` radio, inline separator, `View profile`), keeping iOS native
 * bar items 1:1 with [AgentConversationAvatarMenuButton].
 */
internal fun agentConversationNativeTitleMenu(
    switch: AgentLoungeConversationSwitch,
    loungeLabel: String,
    loungeAudience: String,
    privateChatLabel: String,
    privateAudience: String,
    viewProfileLabel: String,
    onViewProfile: (() -> Unit)?,
): List<RaftNativeNavBarAction> {
    val loungeSelected = switch.activeSide == AgentLoungeConversationSide.Lounge
    val privateSelected = switch.activeSide == AgentLoungeConversationSide.Private
    return listOf(
        RaftNativeNavBarAction(
            id = "agent-conversation-lounge",
            label = loungeLabel,
            subtitle = loungeAudience,
            systemSymbol = AgentLoungeSystemSymbol,
            icon = AgentLoungeIcon,
            checked = loungeSelected,
            badge = switch.loungeUnread.count.takeIf { !loungeSelected && it > 0 },
            attention = !loungeSelected && switch.loungeUnread.dot && switch.loungeUnread.count <= 0,
            enabled = !switch.switching || loungeSelected,
            onClick = {
                if (!loungeSelected && !switch.switching) switch.onSelectLounge()
            },
        ),
        RaftNativeNavBarAction(
            id = "agent-conversation-private",
            label = privateChatLabel,
            subtitle = privateAudience,
            systemSymbol = "lock",
            icon = RaftLucideIconName.Lock,
            checked = privateSelected,
            badge = switch.privateUnread.count.takeIf { !privateSelected && it > 0 },
            attention = !privateSelected && switch.privateUnread.dot && switch.privateUnread.count <= 0,
            enabled = !switch.switching || privateSelected,
            onClick = {
                if (!privateSelected && !switch.switching) switch.onSelectPrivate()
            },
        ),
        RaftNativeNavBarAction(
            id = "agent-conversation-profile",
            label = viewProfileLabel,
            systemSymbol = "person",
            icon = RaftLucideIconName.UserRound,
            enabled = onViewProfile != null,
            dividerBefore = true,
            onClick = { onViewProfile?.invoke() },
        ),
    )
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
                    RaftProductEvents.conversationHeaderActionSelected(
                        action = RaftConversationHeaderAction.OpenDm,
                        surface = RaftConversationHeaderSurface.Header,
                        conversation = RaftConversationHeaderKind.Channel,
                        compact = !wideWindow,
                    )
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
        onOpenAgentProfile = targetAgentId?.let { { onOpenAgentProfile(it) } },
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
