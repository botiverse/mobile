package build.raft.app

import build.raft.app.markdown.RaftMarkdownPreview
import build.raft.app.markdown.markdownToPreview
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedActivitySenderProfile
import build.raft.app.data.typedPinnedOrLegacy
import build.raft.app.model.MessageDelegatedBy
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.TaskStatus
import build.raft.app.model.agentAvatarFallbackWireValue
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftConversationKindGlyph
import build.raft.designsystem.components.RaftConversationKindIcon
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftInboxItemLeading
import build.raft.designsystem.components.RaftInboxItemLeadingBadge
import build.raft.designsystem.components.RaftInboxItemLeadingDefaults
import build.raft.designsystem.components.RaftMentionMarker
import build.raft.designsystem.components.RaftTouchTargetSize
import build.raft.designsystem.components.RaftUnreadCount
import build.raft.designsystem.components.raftUnreadCountText
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource

/*
 * Activity conversation list, the only Activity layout: the KMP port of the web
 * `ThreadsInbox` `layout="list"` branch (slock packages/web/src/components/thread/ThreadsInbox.tsx,
 * raft-ui InboxItem density="compact"). One row per conversation: a leading avatar or channel tile, the
 * name, a one-line preview, the time and the unread count, in the order the server lists them.
 *
 * This file is presentation only. Opening, Done, read/unread and the press-and-hold menu stay the
 * Activity list's own intents (SharedActivityList); this layout never changes how Done is applied. The
 * touch rules (Rule L: persistent Done, swipe-to-Done with undo) are in ActivityConversationSwipe.kt.
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
    /** The sidebar pins; the Pinned filter reloads when they change (the server resolves them). */
    val pinnedRefs: List<SettingsServerSidebarPinnedItem> = emptyList(),
    /** Channel ids (not DMs) whose activity the person muted; web `channels.find(...).activityMuted`. */
    val mutedChannelIds: Set<String> = emptySet(),
    /** Home DM rows keyed by their DM channel id; web `dmChannels`. */
    val dmPeersByChannelId: Map<String, ActivityConversationDmPeer> = emptyMap(),
    /** The signed-in member; their own last message reads "You: ..." instead of their name. */
    val currentMemberId: String? = null,
    /**
     * Home channel rows keyed by channel id: the glyph the Home sidebar draws for that channel
     * ([homeChannelSymbol]: `#`, lock, joint branch, public-page hash-globe).
     */
    val channelSymbolsByChannelId: Map<String, String> = emptyMap(),
) {
    companion object {
        val Empty = ActivityConversationListContext()
    }
}

/**
 * What the conversation-list rows read beyond the item: the Home list context and the agent status.
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
        currentMemberId = snapshot.currentMember.id.value.takeIf { it.isNotBlank() },
        channelSymbolsByChannelId = snapshot.activityChannelSymbolsByChannelId(),
    )
}

/** The Home sidebar's glyph per channel id ([homeChannelSymbol]), for Activity's channel icons. */
internal fun HomeSnapshot.activityChannelSymbolsByChannelId(): Map<String, String> =
    channels.associate { channel ->
        channel.channelId.value to channel.homeChannelSymbol(serverPublicPageLive = server.publicPageLive)
    }

/**
 * The list context the layout may paint with, from the Home content of the shell, or null while it is
 * still pending (the Activity panel keeps its loading state rather than painting rows without their DM
 * faces and mutes and filling them in a moment later):
 *
 * - Home Content for [activityServerId]: its pins, mutes and DM peers.
 * - Home Loading, or Content of another server (a server switch in flight): pending, unless a context was
 *   already resolved ([previous]), which stays — rows never fall back to a spinner once painted.
 * - Home Error / Empty, or no Home store at all ([homeContent] null): no Home facts will come. A context
 *   already resolved stays; otherwise the list without Home facts, which a later Home Content may still
 *   enrich.
 */
internal fun nextActivityConversationListContext(
    previous: ActivityConversationListContext?,
    homeContent: SharedHomeContentState?,
    activityServerId: String,
): ActivityConversationListContext? =
    when (homeContent) {
        is SharedHomeContentState.Content ->
            if (activityServerId.isNotBlank() && homeContent.snapshot.server.id.value == activityServerId) {
                homeContent.snapshot.toActivityConversationListContext(activityServerId)
            } else {
                previous
            }
        is SharedHomeContentState.Loading -> previous
        is SharedHomeContentState.Error,
        is SharedHomeContentState.Empty,
        null -> previous ?: ActivityConversationListContext.Empty
    }

/** web `item.kind === "dm" || (item.kind === "mention_action" && item.channelType === "dm")`. */
internal fun InboxItem.isActivityDirectMessageConversation(): Boolean =
    kind == InboxItemKind.DirectMessage ||
        (kind == InboxItemKind.MentionAction && channelKind == ConversationKind.DirectMessage)

/** One LazyColumn slot of the list layout. */
internal sealed interface ActivityConversationListEntry {
    val key: String

    /** [divider]: the hairline above the row (raft-ui draws it only between two adjacent items). */
    data class Row(val item: InboxItem, val divider: Boolean) : ActivityConversationListEntry {
        override val key: String = item.key
    }
}

/**
 * web `visibleItems` (slock #10785): one row per item in the order the server lists them, a hairline
 * between two rows. There are no Pinned / All conversations sections: Pinned is a filter of its own
 * (`InboxFilter.Pinned`, `scope=pinned`).
 */
internal fun activityConversationListEntries(items: List<InboxItem>): List<ActivityConversationListEntry> =
    items.mapIndexed { index, item -> ActivityConversationListEntry.Row(item, divider = index > 0) }

/**
 * The rows in the order the person sees them. "Prewarm the next thread after the clicked one" follows
 * this order.
 */
internal fun activityRowsInDisplayOrder(conversationListEntries: List<ActivityConversationListEntry>): List<InboxItem> =
    conversationListEntries.mapNotNull { entry -> (entry as? ActivityConversationListEntry.Row)?.item }

/** What leads the row: the DM peer's face, else a neutral tile for the kind of place. */
internal enum class ActivityConversationLeadingKind { Thread, DirectMessage, Channel }

/** Who spoke last, before the preview. */
internal sealed interface ActivityConversationSender {
    /** A system row: the localized "System" label, never the raw wire name. */
    data object System : ActivityConversationSender

    /** The signed-in member: the localized "You" instead of their own name, with their face as [Named]. */
    data class Self(val avatar: ActivitySenderAvatarIdentity?) : ActivityConversationSender

    data class Named(
        val name: String,
        /** The 19dp face before the name; null for DM rows (they already lead with the peer). */
        val avatar: ActivitySenderAvatarIdentity?,
    ) : ActivityConversationSender
}

@Immutable
internal data class ActivityConversationRowModel(
    val leadingKind: ActivityConversationLeadingKind,
    /**
     * The DM peer whose face leads the row: a DM row's peer, or the peer of the DM a thread lives in
     * (web `threadDmChannel`). Null: not a DM, or the DM is not loaded yet (the kind tile instead).
     */
    val dmPeer: ActivityConversationDmPeer?,
    /**
     * Thread: the root message's Markdown read as one line, inline styles kept (web `InlineMarkdownPreview`);
     * else the place name without its `#`/`@` prefix. Null: fallback.
     */
    val title: RaftMarkdownPreview?,
    /**
     * Thread rows only: "#channel" / "@peer", written before the preview as "#channel ·". Always in the
     * row's accessibility text; shown only when [threadChannelLabelVisible].
     */
    val threadChannelLabel: String?,
    /**
     * False on a thread in a DM whose peer leads the row: the face already says the peer, so the line
     * drops "@peer ·" (web `sr-only`). Channel threads, and DM threads whose DM is not loaded, show it.
     */
    val threadChannelLabelVisible: Boolean = threadChannelLabel != null,
    /** The thread's parent is a DM: an unresolved DM thread leads with the DM tile, not `#`. */
    val threadInDirectMessage: Boolean = false,
    /**
     * The channel tile's glyph ([HomeSidebarChannelGlyph] symbol, [activityChannelSymbol]): the
     * channel's own icon (a thread: its parent channel's), as the Home sidebar draws it.
     */
    val channelSymbol: String = "#",
    val sender: ActivityConversationSender?,
    /** The latest message's Markdown read as one line ([markdownToPreview]). Null: the localized kind fallback. */
    val preview: RaftMarkdownPreview?,
    val muted: Boolean,
    /** Bold title and a full-ink preview: unread and not muted. */
    val strong: Boolean,
    val showMentionMarker: Boolean,
    val unreadCount: Int,
    val showUnreadCount: Boolean,
    val hasDraft: Boolean,
    val taskNumber: Int?,
    val taskStatus: TaskStatus?,
    /**
     * RFC 077: the assistant that sent the latest message for [sender] — "Alice (sent by
     * Friday)", text only (web ThreadsInbox `formatDelegatedSender`). Never on a system row.
     */
    val delegatedBy: MessageDelegatedBy? = null,
) {
    /** The thread badge sits in the leading corner (on the channel tile or the DM peer's face). */
    val leadingThreadBadge: Boolean
        get() = leadingKind == ActivityConversationLeadingKind.Thread

    /**
     * An agent peer's presence light on the leading face: DM rows only. A thread row's corner holds the
     * thread badge, one mark per corner (raft-ui InboxItemLeadingBadge, Jasper).
     */
    val leadingPresence: Boolean
        get() = leadingKind == ActivityConversationLeadingKind.DirectMessage && dmPeer?.peerIsAgent == true
}

private fun String.stripActivityTitlePrefix(): String = trimStart('@', '#')

/** web `isSystemSender`: mention actions, system-kind senders and the `system` sender id. */
internal fun InboxItem.hasActivitySystemSender(): Boolean {
    if (kind == InboxItemKind.MentionAction) return true
    val (senderKind, senderId) =
        if (kind == InboxItemKind.Thread) {
            (latestActivitySenderKind ?: lastMessageSenderKind) to (latestActivitySenderId ?: lastMessageSenderId)
        } else {
            (lastMessageSenderKind ?: latestActivitySenderKind) to (lastMessageSenderId ?: latestActivitySenderId)
        }
    return senderKind == MemberKind.System || senderId == "system"
}

/** web `threadDmChannel`: the Home DM row of the DM a thread lives in; null for other rows or while unloaded. */
internal fun InboxItem.activityThreadDmPeer(context: ActivityConversationListContext): ActivityConversationDmPeer? =
    if (kind == InboxItemKind.Thread && parentChannelKind == ConversationKind.DirectMessage) {
        parentChannelId?.let { context.dmPeersByChannelId[it.value] }
    } else {
        null
    }

/**
 * The conversation (thread: its parent channel or DM) or DM peer is muted; muted rows show no unread
 * count. A thread in a muted DM is as quiet as the DM's own row (web `?? threadDmChannel`).
 */
internal fun InboxItem.activityConversationMuted(
    context: ActivityConversationListContext,
    dmPeer: ActivityConversationDmPeer? =
        if (isActivityDirectMessageConversation()) {
            context.dmPeersByChannelId[channelId.value]
        } else {
            activityThreadDmPeer(context)
        },
): Boolean {
    val mutedChannelId = if (kind == InboxItemKind.Thread) parentChannelId?.value else channelId.value
    return (mutedChannelId != null && mutedChannelId in context.mutedChannelIds) || dmPeer?.activityMuted == true
}

/**
 * The channel's own glyph for an Activity row (web ThreadsInbox `ChannelKindIcon`, slock #10806), for
 * the row's channel (a thread: its parent channel). The type is the item's own wire `channelType` /
 * `parentChannelType` (private → lock, joint → branch, channel → `#`); the public-page globe needs the
 * Home channel row (guestVisible, archived) and the server's live page, so it comes from the Home
 * sidebar's symbol for that channel. An item without a wire type (built from a realtime event) uses
 * the Home row's symbol, else its folded kind (joint), else `#`.
 */
internal fun InboxItem.activityChannelSymbol(homeChannelSymbols: Map<String, String>): String {
    val thread = kind == InboxItemKind.Thread
    val placeId = if (thread) parentChannelId else channelId
    val wireType = if (thread) parentChannelType else channelType
    val homeSymbol = placeId?.let { homeChannelSymbols[it.value] }
    if (wireType == null) {
        return homeSymbol ?: (if (thread) parentChannelKind else channelKind)?.toWireChannelKindOrNull().toRaftChannelSymbol()
    }
    val symbol = wireType.toRaftChannelSymbol()
    return if (symbol == "#" && homeSymbol == HomeChannelSymbolHashGlobe) homeSymbol else symbol
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
    val threadInDirectMessage = kind == InboxItemKind.Thread && parentChannelKind == ConversationKind.DirectMessage
    val threadDmPeer = activityThreadDmPeer(context)
    val dmPeer = if (directMessage) context.dmPeersByChannelId[channelId.value] else threadDmPeer
    val title =
        when {
            kind == InboxItemKind.Thread -> parentMessagePreview?.let(::markdownToPreview)?.takeIf { it.text.isNotBlank() }
            kind == InboxItemKind.DirectMessage ->
                RaftMarkdownPreview(
                    (dmPeer?.displayName ?: dmPeerNames[channelId.value] ?: channelName.ifBlank { channelId.value })
                        .stripActivityTitlePrefix()
                )
            else -> RaftMarkdownPreview(channelName.ifBlank { channelId.value }.stripActivityTitlePrefix())
        }
    val systemSender = hasActivitySystemSender()
    val senderName = senderDisplayName(senderProfiles)?.takeIf { it.isNotBlank() }
    // The same precedence as senderDisplayName, so the id belongs to the name shown.
    val senderId =
        if (kind == InboxItemKind.Thread) {
            latestActivitySenderId ?: lastMessageSenderId
        } else {
            lastMessageSenderId ?: latestActivitySenderId
        }
    val self = context.currentMemberId != null && senderId == context.currentMemberId
    val sender =
        when {
            systemSender -> ActivityConversationSender.System
            // web: a DM whose last message came from the peer shows the preview alone. A DM thread keeps
            // "Peer: …": either side may have replied last (Jasper).
            directMessage && dmPeer != null && dmPeer.peerId != null && senderId == dmPeer.peerId -> null
            !self && senderName == null -> null
            else -> {
                val identity = activitySenderAvatarIdentity(senderProfiles)
                val faced = identity.senderKind == MemberKind.Agent || identity.senderKind == MemberKind.Human
                // DM rows and DM threads already lead with the peer's face: no second, inline face.
                val avatar = identity.takeIf { faced && leadingKind != ActivityConversationLeadingKind.DirectMessage && threadDmPeer == null }
                if (self) {
                    ActivityConversationSender.Self(avatar)
                } else {
                    ActivityConversationSender.Named(name = senderName.orEmpty(), avatar = avatar)
                }
            }
        }
    val preview =
        if (kind == InboxItemKind.Thread) {
            latestActivityPreview ?: lastMessagePreview
        } else {
            lastMessagePreview ?: latestActivityPreview
        }
    val muted = activityConversationMuted(context, dmPeer)
    val unread = unreadCount.coerceAtLeast(0)
    return ActivityConversationRowModel(
        leadingKind = leadingKind,
        dmPeer = dmPeer,
        title = title,
        threadChannelLabel =
            when {
                kind != InboxItemKind.Thread -> null
                // The Home DM row names the peer the face shows.
                threadDmPeer?.displayName != null -> "@${threadDmPeer.displayName.stripActivityTitlePrefix()}"
                else -> channelLabel(dmPeerNames)
            },
        threadChannelLabelVisible = kind == InboxItemKind.Thread && threadDmPeer == null,
        threadInDirectMessage = threadInDirectMessage,
        channelSymbol =
            if (leadingKind == ActivityConversationLeadingKind.DirectMessage || threadInDirectMessage) "#" else activityChannelSymbol(context.channelSymbolsByChannelId),
        sender = sender,
        preview = preview?.let(::markdownToPreview)?.takeIf { it.text.isNotBlank() },
        muted = muted,
        strong = unread > 0 && !muted,
        showMentionMarker = selectedFilter != InboxFilter.Mentions && hasMention && unread > 0,
        unreadCount = unread,
        showUnreadCount = !muted && unread > 0,
        hasDraft = hasDraft,
        taskNumber = taskNumber.takeIf { activityTaskStatusBadgesEnabled && kind == InboxItemKind.Thread && taskStatus != null },
        taskStatus = taskStatus.takeIf { activityTaskStatusBadgesEnabled && kind == InboxItemKind.Thread && taskNumber != null },
        delegatedBy = previewDelegatedBy(),
    )
}

// ---------------------------------------------------------------------------------------------
// Avatars
// ---------------------------------------------------------------------------------------------

/**
 * Where Activity faces load from: the server's member directory (the same [RaftMemberAvatarResolver]
 * and Coil cache identity as the Home DM rows), so a sender or DM peer shows their real avatar even
 * though inbox items carry no avatar of their own (web resolves them from its agent / member stores).
 */
@Immutable
internal class ActivityAvatarSource(
    val memberAvatarResolver: RaftMemberAvatarResolver,
    val cacheServerId: String?,
)

internal val LocalActivityAvatarSource =
    staticCompositionLocalOf { ActivityAvatarSource(EmptyRaftMemberAvatarResolver, cacheServerId = null) }

/**
 * The avatar an Activity face renders, web `AvatarSlot`: the member directory's avatar first, then the
 * one the item carries, then the kind's own default (an agent's pixel face). A person with neither
 * stays null and shows the placeholder person (web `humanPlaceholder`), never their initials.
 */
internal fun activityFaceAvatarWireValue(
    memberAvatarWireValue: String?,
    identity: ActivitySenderAvatarIdentity,
): String? {
    memberAvatarWireValue?.takeIf { it.isNotBlank() }?.let { return it }
    val itemAvatar = identity.avatarWireValue?.takeIf { it.isNotBlank() }
    val kind = identity.senderKind
    val id = identity.senderId?.trim()?.takeIf { it.isNotEmpty() }
    return if ((kind == MemberKind.Agent || kind == MemberKind.Human) && id != null) {
        avatarWireValueForAvatarSlot(kind = kind, id = id, avatarWireValue = itemAvatar)
    } else if (kind == MemberKind.Agent) {
        itemAvatar ?: agentAvatarFallbackWireValue("")
    } else {
        itemAvatar
    }
}

/**
 * One person's or agent's face in Activity: raft-ui Avatar (sticker radius, 1.5dp ink edge) through the
 * shared avatar pipeline, keyed by member for the image cache. Agents sit on `inactive`, people on
 * their palette fill under the placeholder person.
 */
@Composable
internal fun ActivityMemberFace(
    identity: ActivitySenderAvatarIdentity,
    avatarSize: Dp,
    modifier: Modifier = Modifier,
) {
    val source = LocalActivityAvatarSource.current
    val memberAvatar = rememberRaftMemberAvatarWireValue(source.memberAvatarResolver, identity.senderId)
    RaftAppAvatar(
        avatarWireValue = activityFaceAvatarWireValue(memberAvatar, identity),
        // Empty: a face that does not load shows the placeholder person, as on web, not a letter.
        label = "",
        modifier = modifier,
        avatarSize = avatarSize,
        cacheSubject = raftMemberAvatarCacheSubject(identity.senderKind, identity.senderId),
        cacheServerId = source.cacheServerId,
        fallbackFill = raftAvatarFallbackFill(identity.senderKind),
    )
}

// ---------------------------------------------------------------------------------------------
// Composables
// ---------------------------------------------------------------------------------------------

/** The avatar / tile inside raft-ui's 36dp `InboxItemLeading` slot is the 35dp md step. */
private val ConversationLeadingAvatar = 35.dp

/** The glyph inside the 35dp channel / DM tile. */
private val ConversationTileGlyph = 18.dp

/** `before:left-16`: the hairline starts where the text column starts. */
private val ConversationDividerStart = 64.dp
private val ConversationSenderAvatar = 19.dp

@Composable
internal fun ActivityConversationListRow(
    item: InboxItem,
    model: ActivityConversationRowModel,
    divider: Boolean,
    selected: Boolean,
    /** Its long-press menu is open: `fill-row-open`, under selection. */
    menuOpen: Boolean = false,
    timeContext: RaftTimeContext?,
    agentStatusResolver: RaftAgentStatusResolver,
    showDoneAction: Boolean,
    openGestureKey: String,
    captureMenuAnchor: Boolean,
    onOpen: () -> Unit,
    onDone: () -> Unit,
    onOpenMenu: (RaftPopoverAnchor) -> Unit,
    modifier: Modifier = Modifier,
    /** Rule L swipe shortcut (ActivitySwipeToDoneBox); null: no swipe. */
    onSwipeDone: (() -> Unit)? = null,
    /** On the row surface above the swipe box's opaque layer (e.g. the unread-walk mark). */
    contentModifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val hairline = colors.lineHairline
    // Every compact row is the same height (raft-ui InboxItem density="compact": one line each for the
    // title and the latest message): each line is a slot of its style's line height, so a task badge,
    // an inline face or a fallback CJK glyph cannot make one row taller than its neighbours.
    val density = LocalDensity.current
    val titleLine = with(density) { typography.subhead.lineHeight.toDp() }
    val bodyLine = with(density) { typography.callout.lineHeight.toDp() }
    val titleColor = if (model.strong) colors.foreground else colors.foregroundMuted
    val bodyColor = if (model.strong) colors.foreground else colors.foregroundMuted
    val threadFallback = stringResource(MR.strings.activity_fallback_thread_update)
    val actionFallback = stringResource(MR.strings.activity_fallback_action_required)
    val conversationFallback = stringResource(MR.strings.activity_fallback_conversation_update)
    val systemLabel = stringResource(MR.strings.activity_list_system_sender)
    val selfLabel = stringResource(MR.strings.activity_list_self_sender)
    val openLabel = stringResource(MR.strings.common_open)
    val threadChannelText = model.threadChannelLabel?.let { stringResource(MR.strings.activity_list_thread_channel, it) }
    // A DM thread's face says the peer, so its line drops "@peer ·"; screen readers still hear it.
    val visibleThreadChannelText = threadChannelText?.takeIf { model.threadChannelLabelVisible }
    val fallback =
        when (item.kind) {
            InboxItemKind.Thread -> threadFallback
            InboxItemKind.MentionAction -> actionFallback
            else -> conversationFallback
        }
    var pressed by remember(item.key) { mutableStateOf(false) }
    ActivitySwipeToDoneBox(
        gestureKey = openGestureKey,
        onSwipeDone = onSwipeDone.takeIf { showDoneAction },
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(contentModifier)
                    .then(
                        when {
                            selected -> Modifier.background(colors.infoSoft)
                            menuOpen -> Modifier.background(colors.fillRowOpen)
                            // raft-ui 0.20: an inbox row presses with the medium tier.
                            pressed -> Modifier.background(colors.fillPressed)
                            else -> Modifier
                        }
                    )
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
                        .activityRowGestures(openGestureKey, captureMenuAnchor, onOpen, onOpenMenu, onPressedChange = { pressed = it })
                        // One focusable button per row for screen readers: title, preview and marks read
                        // together, and the accessibility click opens the row like a tap.
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            onClick(label = openLabel) {
                                onOpen()
                                true
                            }
                        }
                        // With Done, its 44dp touch slot supplies 6dp of the 8dp gap that used to be padding here.
                        .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = if (showDoneAction) 2.dp else 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActivityConversationLeading(model, item, agentStatusResolver)
                Column(modifier = Modifier.weight(1f)) {
                    // Line 1: title (+ task badge) and, at its end, the time (raft-ui InboxItem: the
                    // timestamp sits beside the title only, so it never narrows the excerpt below).
                    Row(
                        modifier = Modifier.height(titleLine),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RaftText(
                                text = model.title?.toAnnotatedString() ?: AnnotatedString(fallback),
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
                        ActivityConversationTime(item, timeContext)
                    }
                    // One AnnotatedString for "#channel · [face] Sender: preview" so the line truncates as a
                    // whole like web's InboxItemBody: a long channel name ellipsizes the line instead of
                    // starving the preview to zero width. The sender face rides inline as a placeholder.
                    val senderAvatar =
                        when (val sender = model.sender) {
                            is ActivityConversationSender.Named -> sender.avatar
                            is ActivityConversationSender.Self -> sender.avatar
                            else -> null
                        }
                    val density = LocalDensity.current
                    val inlineContent =
                        remember(senderAvatar, density) {
                            senderAvatar?.let { identity ->
                                val width = with(density) { (ConversationSenderAvatar + 4.dp).toSp() }
                                val height = with(density) { ConversationSenderAvatar.toSp() }
                                mapOf(
                                    ActivityConversationSenderAvatarInlineId to
                                        InlineTextContent(
                                            Placeholder(width, height, PlaceholderVerticalAlign.TextCenter)
                                        ) {
                                            ActivityMemberFace(
                                                identity = identity,
                                                avatarSize = ConversationSenderAvatar,
                                                modifier = Modifier.padding(end = 4.dp),
                                            )
                                        }
                                )
                            }.orEmpty()
                        }
                    // Line 2+: the excerpt (up to two lines). The marks sit beside its first line only;
                    // the second line runs the full width, so the marks never narrow the whole excerpt.
                    val senderText =
                        when (val sender = model.sender) {
                            ActivityConversationSender.System -> systemLabel
                            is ActivityConversationSender.Self -> delegatedPreviewSender(selfLabel, model.delegatedBy)
                            is ActivityConversationSender.Named -> delegatedPreviewSender(sender.name, model.delegatedBy)
                            null -> null
                        }
                    val bodyText =
                        activityConversationBodyText(
                            channelLabel = visibleThreadChannelText,
                            sender = senderText,
                            preview = model.preview?.toAnnotatedString() ?: AnnotatedString(fallback),
                            senderStyle = SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.foreground),
                            senderAvatar = senderAvatar != null,
                        )
                    ActivityExcerptBesideMarks(
                        text = bodyText,
                        accessibilityText =
                            if (threadChannelText != null && visibleThreadChannelText == null) {
                                activityConversationAccessibilityBody(threadChannelText, senderText, model.preview?.text ?: fallback)
                            } else {
                                null
                            },
                        style = typography.callout,
                        color = bodyColor,
                        inlineContent = inlineContent,
                        lineHeight = bodyLine,
                        marks = { ActivityConversationMarks(model, marksHeight = bodyLine) },
                    )
                }
            }
            if (showDoneAction) {
                // Rule L (touch): the row's primary action is always on screen, never hover-only, with a
                // 44dp end touch target (raft-ui's `::after`). The 32dp button keeps its look inside that
                // slot; its 6dp on each side plus 2dp / 10dp of padding keep the button where it was.
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides RaftTouchTargetSize) {
                    RaftIconButton(
                        onClick = onDone,
                        tooltip = stringResource(MR.strings.thread_action_mark_done),
                        modifier = Modifier.padding(end = 10.dp).minimumInteractiveComponentSize(),
                    ) {
                        RaftGlyphIcon(RaftGlyph.Check, size = 18.dp)
                    }
                }
            }
        }
    }
}

/** The body wraps to a second line before it ellipsizes; a one-line body keeps the row height. */
internal const val ActivityConversationPreviewMaxLines = 2

internal const val ActivityConversationSenderAvatarInlineId = "activity-conversation-sender-avatar"

/**
 * The body, up to [ActivityConversationPreviewMaxLines] lines: "#channel · " (thread rows), the sender's
 * face (inline placeholder [ActivityConversationSenderAvatarInlineId]), "Sender: " in semibold
 * `foreground` (raft-ui `InboxItemSender`), then the preview. One string, so it wraps and ellipsizes together.
 */
internal fun activityConversationBodyText(
    sender: String?,
    preview: AnnotatedString,
    senderStyle: SpanStyle = SpanStyle(fontWeight = FontWeight.SemiBold),
    channelLabel: String? = null,
    senderAvatar: Boolean = false,
): AnnotatedString =
    buildAnnotatedString {
        if (!channelLabel.isNullOrBlank()) append("$channelLabel ")
        if (senderAvatar && !sender.isNullOrBlank()) {
            appendInlineContent(ActivityConversationSenderAvatarInlineId, alternateText = " ")
        }
        if (!sender.isNullOrBlank()) {
            withStyle(senderStyle) { append("$sender: ") }
        }
        append(preview)
    }

/**
 * What a screen reader hears for the body: the same line with the thread's "@peer ·" label even where
 * the row hides it (web `sr-only`), so the row still names the DM it belongs to.
 */
internal fun activityConversationAccessibilityBody(channelLabel: String?, sender: String?, preview: String): String =
    activityConversationBodyText(sender = sender, preview = AnnotatedString(preview), channelLabel = channelLabel).text

@Composable
private fun ActivityConversationLeading(
    model: ActivityConversationRowModel,
    item: InboxItem,
    agentStatusResolver: RaftAgentStatusResolver,
) {
    val colors = RaftTheme.colors
    // raft-ui InboxItemLeading: the conversation the row lives in. A thread leads with its channel's
    // tile or its DM peer's face, with the thread badge in the corner (raft-ui InboxItemLeadingBadge).
    val threadBadge: (@Composable () -> Unit)? =
        if (model.leadingThreadBadge) {
            {
                RaftInboxItemLeadingBadge {
                    // raft-ui `ThreadIcon`, in the badge's `foreground` ink.
                    RaftConversationKindIcon(RaftConversationKindGlyph.Thread, size = RaftInboxItemLeadingDefaults.BadgeIcon)
                }
            }
        } else {
            null
        }
    RaftInboxItemLeading(badge = threadBadge) {
        val peer = model.dmPeer
        if (peer != null) {
            val label = peer.displayName ?: item.channelName
            val avatarKind = if (peer.peerIsAgent) MemberKind.Agent else MemberKind.Human
            // An agent's DM row carries its status light, as on its sidebar row; people do not, and a
            // DM thread's corner is the thread badge's.
            val status =
                if (model.leadingPresence) {
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
                ActivityMemberFace(
                    identity =
                        ActivitySenderAvatarIdentity(
                            senderKind = avatarKind,
                            avatarWireValue = peer.avatarWireValue,
                            label = label,
                            senderId = peer.peerId,
                        ),
                    avatarSize = ConversationLeadingAvatar,
                )
            }
        } else {
            // A channel is a place, not a face: the neutral tile at the avatar's 35dp step, with the
            // channel's own glyph as the Home sidebar draws it. A DM (or a thread in one) whose peer is
            // not loaded yet shows the DM icon, never `#`.
            RaftIconTile(size = RaftIconTileSize.Md) {
                if (model.leadingKind == ActivityConversationLeadingKind.DirectMessage || model.threadInDirectMessage) {
                    RaftLucideIcon(RaftLucideIconName.MessageCircle, Modifier.size(ConversationTileGlyph), color = raftArgbHex(colors.foregroundMuted))
                } else {
                    HomeSidebarChannelGlyph(model.channelSymbol, size = ConversationTileGlyph)
                }
            }
        }
    }
}

@Composable
private fun ActivityConversationTime(item: InboxItem, timeContext: RaftTimeContext?) {
    RaftRelativeTimestampText(
        value = item.latestActivityAt ?: item.lastMessageAt,
        timeContext = timeContext,
        style = RaftTheme.typography.monoMeta.copy(fontFeatureSettings = "tnum"),
        color = RaftTheme.colors.foregroundHint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        clampFuture = true,
        // Web InboxItemTimestamp is plain text: a tap near the time opens the row.
        toggleEnabled = false,
        modifier = Modifier.widthIn(max = 96.dp),
    )
}

/** raft-ui `InboxItemMarks` (draft, mention, muted / unread count), beside the excerpt's first line. */
@Composable
private fun ActivityConversationMarks(model: ActivityConversationRowModel, marksHeight: Dp) {
    val colors = RaftTheme.colors
    if (!model.hasDraft && !model.showMentionMarker && !model.muted && !model.showUnreadCount) return
    Row(
        modifier = Modifier.height(marksHeight),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (model.hasDraft) {
            val draftDescription = stringResource(MR.strings.activity_list_draft_description)
            Box(
                modifier =
                    Modifier.semantics {
                        contentDescription = draftDescription
                        role = Role.Image
                    }
            ) {
                RaftLucideIcon(RaftLucideIconName.Pencil, Modifier.size(12.dp), color = raftArgbHex(colors.foregroundHint))
            }
        }
        if (model.showMentionMarker) {
            RaftMentionMarker(contentDescription = stringResource(MR.strings.activity_list_mentioned_you))
        }
        when {
            model.muted ->
                RaftLucideIcon(RaftLucideIconName.BellOff, Modifier.size(14.dp), color = raftArgbHex(colors.foregroundHint))
            model.showUnreadCount ->
                RaftUnreadCount(
                    model.unreadCount,
                    contentDescription =
                        stringResource(MR.strings.activity_list_unread_count_description, model.unreadCount),
                )
        }
    }
}

/** The count as raft-ui writes it (`99+` above 99); null when the row shows no count. */
internal fun ActivityConversationRowModel.unreadCountLabel(): String? =
    if (showUnreadCount) raftUnreadCountText(unreadCount) else null


/**
 * The excerpt with [marks] beside its first line only (web `InboxItemMarks` rides the first line):
 * line 1 wraps at the width left of the marks, line 2 takes the rest of the text at the full width,
 * then ellipsizes. Without marks it is one two-line text at the full width.
 */
@Composable
private fun ActivityExcerptBesideMarks(
    text: AnnotatedString,
    /** Non-null: what the excerpt reads as instead of its visible text (one node, all of it). */
    accessibilityText: String? = null,
    style: TextStyle,
    color: Color,
    inlineContent: Map<String, InlineTextContent>,
    lineHeight: Dp,
    marks: @Composable () -> Unit,
) {
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val width = constraints.maxWidth
        val gap = 8.dp.roundToPx()
        val minLine = lineHeight.roundToPx()
        val marksPlaceables = subcompose("marks", marks).map { it.measure(Constraints()) }
        val marksWidth = marksPlaceables.maxOfOrNull { it.width } ?: 0
        val loose = Constraints(maxWidth = width)
        val readAs =
            if (accessibilityText != null) {
                Modifier.clearAndSetSemantics { this.text = AnnotatedString(accessibilityText) }
            } else {
                Modifier
            }
        val silent = if (accessibilityText != null) Modifier.clearAndSetSemantics { } else Modifier
        if (marksWidth == 0) {
            val body =
                subcompose("body") {
                    Box(readAs) {
                        RaftText(
                            text = text,
                            style = style,
                            color = color,
                            maxLines = ActivityConversationPreviewMaxLines,
                            overflow = TextOverflow.Ellipsis,
                            inlineContent = inlineContent,
                        )
                    }
                }.first().measure(loose)
            val height = maxOf(body.height, minLine)
            return@SubcomposeLayout layout(width, height) {
                body.place(0, (height - body.height).coerceAtLeast(0) / 2)
            }
        }
        var firstLineEnd = text.length
        val firstWidth = (width - marksWidth - gap).coerceAtLeast(0)
        val first =
            subcompose("first") {
                Box(readAs) {
                    RaftText(
                        text = text,
                        style = style,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        inlineContent = inlineContent,
                        onTextLayout = { layout -> firstLineEnd = layout.getLineEnd(0, visibleEnd = false) },
                    )
                }
            }.first().measure(Constraints(maxWidth = firstWidth))
        val firstRow = maxOf(first.height, minLine)
        val rest = text.subSequence(firstLineEnd.coerceIn(0, text.length), text.length)
        val second =
            if (rest.text.isBlank()) {
                null
            } else {
                subcompose("second") {
                    Box(silent) {
                        RaftText(
                            text = rest,
                            style = style,
                            color = color,
                            maxLines = ActivityConversationPreviewMaxLines - 1,
                            overflow = TextOverflow.Ellipsis,
                            inlineContent = inlineContent,
                        )
                    }
                }.first().measure(loose)
            }
        layout(width, firstRow + (second?.height ?: 0)) {
            first.place(0, (firstRow - first.height) / 2)
            var x = width
            marksPlaceables.forEach { marksPlaceable ->
                x -= marksPlaceable.width
                marksPlaceable.place(x, (firstRow - marksPlaceable.height) / 2)
            }
            second?.place(0, firstRow)
        }
    }
}
