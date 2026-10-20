package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.data.SharedActivitySenderProfile
import build.raft.app.data.typedPinnedOrLegacy
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.TaskStatus
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftMentionMarker
import build.raft.designsystem.components.RaftUnreadCount
import build.raft.designsystem.components.raftUnreadCountText
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import com.tencent.tmm.kmmresource.compose.stringResource

/*
 * Activity conversation list (server flag `activity_conversation_list_v0`): the KMP port of the web
 * `ThreadsInbox` `layout="list"` branch (slock packages/web/src/components/thread/ThreadsInbox.tsx,
 * raft-ui InboxItem density="compact"). One row per conversation: a leading avatar or channel tile, the
 * name, a one-line preview, the time and the unread count, sidebar-pinned conversations first.
 *
 * This file is presentation only. Opening, Done, read/unread and the press-and-hold menu stay the
 * Activity list's own intents (SharedActivityList); this layout never changes how Done is applied.
 */

/** The Home sidebar facts a conversation row needs: who a DM is with, and whether the place is muted. */
@Immutable
internal data class ActivityConversationDmPeer(
    val peerId: String?,
    val peerIsAgent: Boolean,
    val avatarWireValue: String?,
    val displayName: String?,
    val activityMuted: Boolean,
)

/**
 * Selector output for the conversation-list layout, projected from the Home snapshot of the same server.
 * Empty until Home has loaded: no pins, nothing muted, DMs fall back to their kind tile.
 */
@Immutable
internal data class ActivityConversationListContext(
    val pinnedRefs: List<SettingsServerSidebarPinnedItem> = emptyList(),
    /** Channel ids (not DMs) whose activity the person muted; web `channels.find(...).activityMuted`. */
    val mutedChannelIds: Set<String> = emptySet(),
    /** Home DM rows keyed by their DM channel id; web `dmChannels`. */
    val dmPeersByChannelId: Map<String, ActivityConversationDmPeer> = emptyMap(),
) {
    companion object {
        val Empty = ActivityConversationListContext()
    }
}

/**
 * Non-null when Activity renders as a conversation list (flag on). Null keeps the card rows unchanged.
 */
@Immutable
internal data class ActivityConversationListPresentation(
    val context: ActivityConversationListContext = ActivityConversationListContext.Empty,
    val agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
)

/**
 * Projects the Home snapshot into the list context. A snapshot of another server is not a candidate for
 * this Activity (route-identity rule): it yields [ActivityConversationListContext.Empty].
 */
internal fun HomeSnapshot?.toActivityConversationListContext(activityServerId: String): ActivityConversationListContext {
    val snapshot = this ?: return ActivityConversationListContext.Empty
    if (activityServerId.isBlank() || snapshot.server.id.value != activityServerId) {
        return ActivityConversationListContext.Empty
    }
    return ActivityConversationListContext(
        pinnedRefs = snapshot.sidebarOrder?.typedPinnedOrLegacy().orEmpty(),
        mutedChannelIds =
            snapshot.channels
                .filter { it.activityMuted }
                .mapTo(mutableSetOf()) { it.channelId.value },
        dmPeersByChannelId =
            snapshot.directMessages.associate { dm ->
                dm.channelId.value to
                    ActivityConversationDmPeer(
                        peerId = dm.presenceMemberId?.value?.takeIf { it.isNotBlank() },
                        peerIsAgent = dm.directMessagePeerType.equals("agent", ignoreCase = true),
                        avatarWireValue = dm.directMessagePeerAvatarWireValue,
                        displayName = dm.name.takeIf { it.isNotBlank() },
                        activityMuted = dm.activityMuted,
                    )
            },
    )
}

/** web `item.kind === "dm" || (item.kind === "mention_action" && item.channelType === "dm")`. */
internal fun InboxItem.isActivityDirectMessageConversation(): Boolean =
    kind == InboxItemKind.DirectMessage ||
        (kind == InboxItemKind.MentionAction && channelKind == ConversationKind.DirectMessage)

/**
 * web `isSidebarPinnedInboxItem`: channels pin by channel id, DMs by their peer (agent or human), threads
 * are never pinned on their own.
 */
internal fun InboxItem.isSidebarPinnedActivityConversation(context: ActivityConversationListContext): Boolean {
    if (kind == InboxItemKind.Thread || context.pinnedRefs.isEmpty()) return false
    if (!isActivityDirectMessageConversation()) {
        return context.pinnedRefs.any { it.kind == "channel" && it.id == channelId.value }
    }
    val peer = context.dmPeersByChannelId[channelId.value] ?: return false
    val peerId = peer.peerId ?: return false
    val pinKind = if (peer.peerIsAgent) "agent" else "human"
    return context.pinnedRefs.any { it.kind == pinKind && it.id == peerId }
}

internal enum class ActivityConversationListSection { Pinned, AllConversations }

/** One LazyColumn slot of the list layout. */
internal sealed interface ActivityConversationListEntry {
    val key: String

    data class Section(val section: ActivityConversationListSection, val first: Boolean) : ActivityConversationListEntry {
        override val key: String = "activity-list-section:${section.name}"
    }

    /** [divider]: the hairline above the row (raft-ui draws it only between two adjacent items). */
    data class Row(val item: InboxItem, val pinned: Boolean, val divider: Boolean) : ActivityConversationListEntry {
        override val key: String = item.key
    }
}

/**
 * web `pinnedItemKeys` / `visibleItems` / section labels: pinned conversations float to the top in their
 * loaded order under "Pinned", the rest follow under "All conversations". With nothing pinned there are
 * no section labels at all.
 */
internal fun activityConversationListEntries(
    items: List<InboxItem>,
    context: ActivityConversationListContext,
): List<ActivityConversationListEntry> {
    val pinnedKeys = items.filter { it.isSidebarPinnedActivityConversation(context) }.mapTo(mutableSetOf()) { it.key }
    val ordered =
        if (pinnedKeys.isEmpty()) {
            items
        } else {
            items.filter { it.key in pinnedKeys } + items.filterNot { it.key in pinnedKeys }
        }
    val entries = ArrayList<ActivityConversationListEntry>(ordered.size + 2)
    ordered.forEachIndexed { index, item ->
        val pinned = item.key in pinnedKeys
        val section =
            when {
                pinnedKeys.isEmpty() -> null
                index == 0 -> if (pinned) ActivityConversationListSection.Pinned else ActivityConversationListSection.AllConversations
                !pinned && ordered[index - 1].key in pinnedKeys -> ActivityConversationListSection.AllConversations
                else -> null
            }
        if (section != null) entries += ActivityConversationListEntry.Section(section, first = index == 0)
        entries += ActivityConversationListEntry.Row(item, pinned = pinned, divider = index > 0 && section == null)
    }
    return entries
}

/** What leads the row: the DM peer's face, else a neutral tile for the kind of place. */
internal enum class ActivityConversationLeadingKind { Thread, DirectMessage, Channel }

/** Who spoke last, before the preview. */
internal sealed interface ActivityConversationSender {
    /** A system row: the localized "System" label, never the raw wire name. */
    data object System : ActivityConversationSender

    data class Named(
        val name: String,
        /** The 19dp face before the name; null for DM rows (they already lead with the peer). */
        val avatar: ActivitySenderAvatarIdentity?,
    ) : ActivityConversationSender
}

@Immutable
internal data class ActivityConversationRowModel(
    val leadingKind: ActivityConversationLeadingKind,
    val dmPeer: ActivityConversationDmPeer?,
    /** Thread: the root message preview; else the place name without its `#`/`@` prefix. Null: fallback. */
    val title: String?,
    /** Thread rows only: "#channel" / "@peer", written before the preview as "#channel ·". */
    val threadChannelLabel: String?,
    val sender: ActivityConversationSender?,
    /** Null: the localized kind fallback. */
    val preview: String?,
    val muted: Boolean,
    /** Bold title and a full-ink preview: unread and not muted. */
    val strong: Boolean,
    val showMentionMarker: Boolean,
    val unreadCount: Int,
    val showUnreadCount: Boolean,
    val hasDraft: Boolean,
    val taskNumber: Int?,
    val taskStatus: TaskStatus?,
)

private fun String.stripActivityTitlePrefix(): String = trimStart('@', '#')

/** web `isSystemSender`: mention actions, system-kind senders and the `system` sender id. */
private fun InboxItem.hasActivitySystemSender(): Boolean {
    if (kind == InboxItemKind.MentionAction) return true
    val (senderKind, senderId) =
        if (kind == InboxItemKind.Thread) {
            (latestActivitySenderKind ?: lastMessageSenderKind) to (latestActivitySenderId ?: lastMessageSenderId)
        } else {
            (lastMessageSenderKind ?: latestActivitySenderKind) to (lastMessageSenderId ?: latestActivitySenderId)
        }
    return senderKind == MemberKind.System || senderId == "system"
}

/** The web `InboxRow` list-layout projection of one Activity item. Pure; unit-tested. */
internal fun InboxItem.activityConversationRowModel(
    context: ActivityConversationListContext,
    selectedFilter: InboxFilter,
    senderProfiles: Map<String, SharedActivitySenderProfile> = emptyMap(),
    dmPeerNames: Map<String, String> = emptyMap(),
    activityTaskStatusBadgesEnabled: Boolean = true,
): ActivityConversationRowModel {
    val directMessage = isActivityDirectMessageConversation()
    val leadingKind =
        when {
            kind == InboxItemKind.Thread -> ActivityConversationLeadingKind.Thread
            directMessage -> ActivityConversationLeadingKind.DirectMessage
            else -> ActivityConversationLeadingKind.Channel
        }
    val dmPeer = if (directMessage) context.dmPeersByChannelId[channelId.value] else null
    val title =
        when {
            kind == InboxItemKind.Thread -> parentMessagePreview?.takeIf { it.isNotBlank() }
            kind == InboxItemKind.DirectMessage ->
                (dmPeer?.displayName ?: dmPeerNames[channelId.value] ?: channelName.ifBlank { channelId.value })
                    .stripActivityTitlePrefix()
            else -> channelName.ifBlank { channelId.value }.stripActivityTitlePrefix()
        }
    val systemSender = hasActivitySystemSender()
    val senderName = senderDisplayName(senderProfiles)?.takeIf { it.isNotBlank() }
    val senderId = if (kind == InboxItemKind.Thread) latestActivitySenderId else lastMessageSenderId
    val sender =
        when {
            systemSender -> ActivityConversationSender.System
            // web: a DM whose last message came from the peer shows the preview alone.
            dmPeer != null && dmPeer.peerId != null && senderId == dmPeer.peerId -> null
            senderName == null -> null
            else -> {
                val identity = activitySenderAvatarIdentity(senderProfiles)
                val faced = identity.senderKind == MemberKind.Agent || identity.senderKind == MemberKind.Human
                ActivityConversationSender.Named(
                    name = senderName,
                    avatar = identity.takeIf { faced && leadingKind != ActivityConversationLeadingKind.DirectMessage },
                )
            }
        }
    val preview =
        if (kind == InboxItemKind.Thread) {
            latestActivityPreview ?: lastMessagePreview
        } else {
            lastMessagePreview ?: latestActivityPreview
        }
    val mutedChannelId = if (kind == InboxItemKind.Thread) parentChannelId?.value else channelId.value
    val muted = (mutedChannelId != null && mutedChannelId in context.mutedChannelIds) || dmPeer?.activityMuted == true
    val unread = unreadCount.coerceAtLeast(0)
    return ActivityConversationRowModel(
        leadingKind = leadingKind,
        dmPeer = dmPeer,
        title = title,
        threadChannelLabel = if (kind == InboxItemKind.Thread) channelLabel(dmPeerNames) else null,
        sender = sender,
        preview = preview?.takeIf { it.isNotBlank() },
        muted = muted,
        strong = unread > 0 && !muted,
        showMentionMarker = selectedFilter != InboxFilter.Mentions && hasMention && unread > 0,
        unreadCount = unread,
        showUnreadCount = !muted && unread > 0,
        hasDraft = hasDraft,
        taskNumber = taskNumber.takeIf { activityTaskStatusBadgesEnabled && kind == InboxItemKind.Thread && taskStatus != null },
        taskStatus = taskStatus.takeIf { activityTaskStatusBadgesEnabled && kind == InboxItemKind.Thread && taskNumber != null },
    )
}

// ---------------------------------------------------------------------------------------------
// Composables
// ---------------------------------------------------------------------------------------------

/** raft-ui `InboxItemLeading` is a 36dp (size-9) slot; the avatar / tile inside is the 35dp md step. */
private val ConversationLeadingSlot = 36.dp
private val ConversationLeadingAvatar = 35.dp

/** `before:left-16`: the hairline starts where the text column starts. */
private val ConversationDividerStart = 64.dp
private val ConversationSenderAvatar = 19.dp

@Composable
internal fun ActivityConversationListSectionLabel(
    section: ActivityConversationListSection,
    first: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = if (first) 8.dp else 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (section == ActivityConversationListSection.Pinned) {
            RaftLucideIcon(RaftLucideIconName.Pin, Modifier.size(12.dp), color = raftArgbHex(colors.foregroundHint))
        }
        RaftText(
            text =
                stringResource(
                    when (section) {
                        ActivityConversationListSection.Pinned -> MR.strings.activity_list_pinned
                        ActivityConversationListSection.AllConversations -> MR.strings.activity_list_all_conversations
                    }
                ).uppercase(),
            style = RaftTheme.typography.label,
            color = colors.foregroundHint,
            maxLines = 1,
        )
    }
}

@Composable
internal fun ActivityConversationListRow(
    item: InboxItem,
    model: ActivityConversationRowModel,
    divider: Boolean,
    selected: Boolean,
    timeContext: RaftTimeContext?,
    agentStatusResolver: RaftAgentStatusResolver,
    showDoneAction: Boolean,
    openGestureKey: String,
    captureMenuAnchor: Boolean,
    onOpen: () -> Unit,
    onDone: () -> Unit,
    onOpenMenu: (RaftPopoverAnchor) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val hairline = colors.lineHairline
    val titleColor = if (model.strong) colors.foreground else colors.foregroundMuted
    val bodyColor = if (model.strong) colors.foreground else colors.foregroundMuted
    val threadFallback = stringResource(MR.strings.activity_fallback_thread_update)
    val actionFallback = stringResource(MR.strings.activity_fallback_action_required)
    val conversationFallback = stringResource(MR.strings.activity_fallback_conversation_update)
    val systemLabel = stringResource(MR.strings.activity_list_system_sender)
    val threadChannelText = model.threadChannelLabel?.let { stringResource(MR.strings.activity_list_thread_channel, it) }
    val fallback =
        when (item.kind) {
            InboxItemKind.Thread -> threadFallback
            InboxItemKind.MentionAction -> actionFallback
            else -> conversationFallback
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (selected) Modifier.background(colors.infoSoft) else Modifier)
                .then(
                    if (divider) {
                        Modifier.drawBehind {
                            val start = ConversationDividerStart.toPx()
                            drawLine(hairline, Offset(start, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                        }
                    } else {
                        Modifier
                    }
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .activityRowGestures(openGestureKey, captureMenuAnchor, onOpen, onOpenMenu)
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = if (showDoneAction) 8.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActivityConversationLeading(model, item, agentStatusResolver)
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftText(
                        text = model.title ?: fallback,
                        modifier = Modifier.weight(1f, fill = false),
                        style = typography.subhead.copy(fontWeight = if (model.strong) FontWeight.Bold else FontWeight.SemiBold),
                        color = titleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model.taskNumber != null && model.taskStatus != null) {
                        SharedActivityFooterBadge(
                            label = "#${model.taskNumber}",
                            slot = SharedActivityFooterSlot.Task,
                            taskStatus = model.taskStatus,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val namedSender = model.sender as? ActivityConversationSender.Named
                    if (threadChannelText != null) {
                        RaftText(
                            text = "$threadChannelText ",
                            style = typography.callout,
                            color = bodyColor,
                            maxLines = 1,
                        )
                    }
                    namedSender?.avatar?.let { identity ->
                        RaftAppAvatar(
                            avatarWireValue = identity.avatarWireValue,
                            label = identity.label,
                            modifier = Modifier.padding(end = 4.dp),
                            avatarSize = ConversationSenderAvatar,
                            borderWidth = 1.dp,
                            cacheSubject = raftMemberAvatarCacheSubject(identity.senderKind, identity.senderId),
                        )
                    }
                    RaftText(
                        text =
                            activityConversationBodyText(
                                sender =
                                    when (val sender = model.sender) {
                                        ActivityConversationSender.System -> systemLabel
                                        is ActivityConversationSender.Named -> sender.name
                                        null -> null
                                    },
                                preview = model.preview ?: fallback,
                                senderStyle = SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.foreground),
                            ),
                        modifier = Modifier.weight(1f),
                        style = typography.callout,
                        color = bodyColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ActivityConversationTrailing(item, model, timeContext)
        }
        if (showDoneAction) {
            RaftIconButton(
                onClick = onDone,
                contentDescription = stringResource(MR.strings.thread_action_mark_done),
                modifier = Modifier.padding(end = 16.dp),
            ) {
                RaftGlyphIcon(RaftGlyph.Check, size = 18.dp)
            }
        }
    }
}

/** "Sender: preview" with the sender in semibold `foreground` (raft-ui `InboxItemSender`). */
internal fun activityConversationBodyText(
    sender: String?,
    preview: String,
    senderStyle: SpanStyle = SpanStyle(fontWeight = FontWeight.SemiBold),
): AnnotatedString =
    buildAnnotatedString {
        if (!sender.isNullOrBlank()) {
            withStyle(senderStyle) { append("$sender: ") }
        }
        append(preview)
    }

@Composable
private fun ActivityConversationLeading(
    model: ActivityConversationRowModel,
    item: InboxItem,
    agentStatusResolver: RaftAgentStatusResolver,
) {
    val colors = RaftTheme.colors
    Box(modifier = Modifier.size(ConversationLeadingSlot), contentAlignment = Alignment.Center) {
        val peer = model.dmPeer
        if (model.leadingKind == ActivityConversationLeadingKind.DirectMessage && peer != null) {
            val label = peer.displayName ?: item.channelName
            val avatarKind = if (peer.peerIsAgent) MemberKind.Agent else MemberKind.Human
            // Agents carry their status light, as on their sidebar row; people do not.
            val status =
                if (peer.peerIsAgent) {
                    projectHomeAgentStatusIndicatorState(
                        showPresence = true,
                        memberId = peer.peerId,
                        canonicalState = rememberRaftAgentStatusIndicatorState(agentStatusResolver, peer.peerId),
                    )
                } else {
                    null
                }
            RaftAvatarWithStatus(
                statusColor = status?.color,
                avatarSize = ConversationLeadingAvatar,
                statusSize = 9.dp,
                statusPulsing = status?.pulsing == true,
            ) {
                RaftAppAvatar(
                    avatarWireValue = peer.avatarWireValue,
                    label = label,
                    avatarSize = ConversationLeadingAvatar,
                    borderWidth = 1.5.dp,
                    cacheSubject = raftMemberAvatarCacheSubject(avatarKind, peer.peerId),
                )
            }
        } else {
            // A channel is a place, not a face: the neutral tile at the avatar's 35dp step.
            RaftIconTile(size = RaftIconTileSize.Md) {
                RaftLucideIcon(
                    if (model.leadingKind == ActivityConversationLeadingKind.DirectMessage) {
                        RaftLucideIconName.MessageCircle
                    } else {
                        RaftLucideIconName.Hash
                    },
                    Modifier.size(18.dp),
                    color = raftArgbHex(colors.foregroundMuted),
                )
            }
            if (model.leadingKind == ActivityConversationLeadingKind.Thread) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 4.dp, y = 4.dp)
                            .size(18.dp)
                            .background(colors.layerCard, RoundedCornerShape(RaftRadius.Sticker))
                            .border(1.dp, colors.lineSeam, RoundedCornerShape(RaftRadius.Sticker)),
                    contentAlignment = Alignment.Center,
                ) {
                    RaftLucideIcon(
                        RaftLucideIconName.MessageSquare,
                        Modifier.size(11.dp),
                        color = raftArgbHex(colors.foregroundMuted),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityConversationTrailing(
    item: InboxItem,
    model: ActivityConversationRowModel,
    timeContext: RaftTimeContext?,
) {
    val colors = RaftTheme.colors
    Column(horizontalAlignment = Alignment.End) {
        RaftRelativeTimestampText(
            value = item.latestActivityAt ?: item.lastMessageAt,
            timeContext = timeContext,
            style = RaftTheme.typography.monoMeta.copy(fontFeatureSettings = "tnum"),
            color = colors.foregroundHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 96.dp),
        )
        Row(
            modifier = Modifier.height(20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (model.hasDraft) {
                RaftLucideIcon(RaftLucideIconName.Pencil, Modifier.size(12.dp), color = raftArgbHex(colors.foregroundHint))
            }
            if (model.showMentionMarker) {
                RaftMentionMarker(contentDescription = stringResource(MR.strings.activity_list_mentioned_you))
            }
            when {
                model.muted ->
                    RaftLucideIcon(RaftLucideIconName.BellOff, Modifier.size(14.dp), color = raftArgbHex(colors.foregroundHint))
                model.showUnreadCount -> RaftUnreadCount(model.unreadCount)
            }
        }
    }
}

/** The count as raft-ui writes it (`99+` above 99); null when the row shows no count. */
internal fun ActivityConversationRowModel.unreadCountLabel(): String? =
    if (showUnreadCount) raftUnreadCountText(unreadCount) else null
