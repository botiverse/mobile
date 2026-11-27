package build.raft.app

import build.raft.app.data.ActivityObjectRow
import build.raft.app.data.ActivityObjectEventVerb
import build.raft.app.data.ArtifactKind
import build.raft.app.data.eventVerb
import build.raft.app.markdown.lucideIcon
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.raftPointerHover
import build.raft.designsystem.components.raftPointerHoverPosition
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import build.raft.designsystem.components.raftStateOverlay
import build.raft.designsystem.components.rememberRaftStateOverlay
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.RectangleShape
import build.raft.app.resources.*
import build.raft.app.thread.delegatedPreviewSender
import build.raft.app.thread.key
import build.raft.app.thread.localizedLabel
import build.raft.app.markdown.RaftMarkdownPreview
import build.raft.app.markdown.markdownToPreview
import build.raft.app.markdown.toAnnotatedString
import build.raft.app.members.EmptyRaftMemberAvatarResolver
import build.raft.app.members.RaftAppBotBadge
import build.raft.app.members.RaftMemberAvatarResolver
import build.raft.app.members.explicitAvatarWireValue
import build.raft.app.members.localizedLabel
import build.raft.app.members.rememberRaftJointActor
import build.raft.app.members.rememberRaftMemberAppBot
import build.raft.app.members.rememberRaftMemberAvatarPending
import build.raft.app.members.rememberRaftMemberAvatarWireValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.LayoutDirection
import build.raft.designsystem.text.forCjkLineBox
import build.raft.designsystem.theme.LocalRaftContentColor
import kotlin.math.roundToInt
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
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
import build.raft.app.data.ResolvedActor
import build.raft.app.data.RaftAgentLounges
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.data.TaskWaitingOnYou
import build.raft.app.data.TaskWaitingVia
import build.raft.app.data.formatTaskConversationRef
import build.raft.app.data.isTaskConversation
import build.raft.app.data.shownTaskWaiting
import build.raft.app.data.showsMarkedUnreadDot
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedActivitySenderProfile
import build.raft.app.data.typedPinnedOrLegacy
import build.raft.app.localizedLabel
import build.raft.app.model.MessageDelegatedBy
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.agentAvatarFallbackWireValue
import build.raft.app.model.appAvatarWireValueOrNull
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.designsystem.components.RaftConversationKindGlyph
import build.raft.designsystem.components.RaftConversationKindIcon
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftInboxItemLeading
import build.raft.designsystem.components.RaftInboxItemLeadingBadge
import build.raft.designsystem.components.RaftInboxItemLeadingDefaults
import build.raft.designsystem.components.RaftTaskStatusBadge
import build.raft.designsystem.components.RaftTaskStatusBadgeSize
import build.raft.designsystem.components.RaftTaskStatusPixel
import build.raft.designsystem.components.RaftTooltip
import build.raft.designsystem.components.RaftMentionMarker
import build.raft.designsystem.components.RaftUnreadCount
import build.raft.designsystem.components.RaftQuietUnreadDot
import build.raft.designsystem.components.raftUnreadCountText
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.tokens.RaftRadius
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape

/*
 * Activity conversation list, the only Activity layout: the KMP port of the web
 * `ThreadsInbox` `layout="list"` branch (slock packages/web/src/components/thread/ThreadsInbox.tsx,
 * raft-ui InboxItem density="compact"). On phones / compact width (`ActivityRowLayout.phone`,
 * raft-design#1433 / #triage:#586 / #proj-mobile:#372 Option A, replacing 1.29's one-line trial #5434):
 * top-aligned leading avatar or channel tile, up to two preview lines whose height follows content
 * (56dp 1-line, 76dp 2-line), `#channel ·` in hint ink, and pixel-only task status. Wide lists keep
 * the single-line `InboxItemLine` layout (centered avatar, status badge, 1 preview line).
 *
 * This file is presentation only. Opening, Done, read/unread and the press-and-hold menu stay the
 * Activity list's own intents (SharedActivityList); this layout never changes how Done is applied. The
 * phone touch rules (raft-design #785: no Done button on the row; a left swipe uncovers Done / Unfollow / Mute buttons, a right swipe Read / Unread (#793), and the press-and-hold menu has the same actions, with undo)
 * are in ActivityConversationSwipe.kt, at every width (xxchan 2026-11-22: a tablet by touch drops the row's
 * Done button too, as Gmail and Feishu do). The row's own Mark as Done button (web InboxRowDone) shows only
 * with a mouse or trackpad, on hover ([activityRowDoneButton]).
 */

/** The agent peer of a DM row (not a thread in a DM): whose App Bot badge the title carries. */
internal fun ActivityConversationRowModel.activityDmAppBotPeerId(): String? =
    dmPeer
        ?.takeIf { leadingKind == ActivityConversationLeadingKind.DirectMessage && it.peerIsAgent }
        ?.peerId

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
     * ([homeChannelSymbol]: `#`, lock, joint branch, the public-page globe, two people, the Slack mark).
     */
    val channelSymbolsByChannelId: Map<String, String> = emptyMap(),
    /**
     * Agent Lounge v0: the Lounges known here, keyed by their channel id (an agent DM row's `lounge`,
     * or a Lounge this client opened or loaded). Their rows are named "{Name}'s Lounge" and lead
     * with the agent's face and the Lounge mark, never `#<agent>-lounge`.
     */
    val loungesByChannelId: Map<String, ActivityConversationLounge> = emptyMap(),
    /**
     * Home's joint channels by id. An object row carries only its home's channel id, so this is how
     * it knows its actor may be the other server's and must be resolved through `POST /api/actors/resolve`.
     */
    val jointChannelIds: Set<String> = emptySet(),
    /**
     * `task_waiting_on_you_v0` (botiverse/slock #16759): live canonical tasks known to the client
     * keyed by [TaskId], used to clear a row's waiting-on-you mark in place as soon as `task:updated`
     * resolves or reassigns it.
     */
    val liveTasksById: Map<TaskId, TaskSummary> = emptyMap(),
    /** Whether `task_waiting_on_you_v0` is enabled for this server (fail-closed default OFF). */
    val taskWaitingEnabled: Boolean = false,
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
        loungesByChannelId = snapshot.activityLoungesByChannelId(),
        jointChannelIds = snapshot.activityJointChannelIds(),
    )
}

/** The ids of this server's joint channels on Home ([channelSettingsIsJoint]). */
internal fun HomeSnapshot.activityJointChannelIds(): Set<String> =
    channels.filter(::channelSettingsIsJoint).mapTo(mutableSetOf()) { it.channelId.value }

/** An agent's Lounge as an Activity row draws it. */
internal data class ActivityConversationLounge(
    val agentId: String,
    /** The agent's name; blank while unknown (the row then says the bare "Lounge"). */
    val agentName: String,
    val avatarWireValue: String? = null,
)

/** The Lounges this server's DM rows carry (see [withKnownLounges] for those known by id). */
internal fun HomeSnapshot.activityLoungesByChannelId(): Map<String, ActivityConversationLounge> =
    directMessages.mapNotNull { dm ->
        val lounge = dm.lounge ?: return@mapNotNull null
        val agentId = dm.presenceMemberId?.value?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        lounge.channelId.value to
            ActivityConversationLounge(
                agentId = agentId,
                agentName = dm.name,
                avatarWireValue = dm.directMessagePeerAvatarWireValue,
            )
    }.toMap()

/**
 * This context with the Lounges the client knows by id ([RaftAgentLounges], e.g. one just opened
 * or loaded from Activity) added; what the DM rows carry wins. Follows the registry, so a Lounge
 * becoming known renames its rows without waiting for Home.
 */
internal fun ActivityConversationListContext.withKnownLounges(
    known: Map<String, build.raft.app.data.RaftAgentLounge>?,
): ActivityConversationListContext {
    if (known.isNullOrEmpty()) return this
    val fromRegistry =
        known.mapValues { (_, lounge) -> ActivityConversationLounge(agentId = lounge.agentId, agentName = lounge.displayAgentName.orEmpty()) }
    val merged = fromRegistry + loungesByChannelId
    return if (merged == loungesByChannelId) this else copy(loungesByChannelId = merged)
}

/** The Home sidebar's glyph per channel id ([homeChannelSymbol]), for Activity's channel icons. */
internal fun HomeSnapshot.activityChannelSymbolsByChannelId(): Map<String, String> =
    channels.associate { channel -> channel.channelId.value to channel.homeChannelSymbol(serverPublicPageLive = server.publicPageLive) }

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

    /** A day's heading over its rows (web slock #13030, [groupActivityRowsByDay]); sticky. */
    data class DayHeading(override val key: String, val label: ActivityDayLabel, val first: Boolean) : ActivityConversationListEntry
}

/**
 * web `visibleItems` (slock #10785): one row per item in the order the server lists them, under a
 * heading per day (slock #13030, [groupActivityRowsByDay]), a hairline between two rows of one day.
 * There are no Pinned / All conversations sections: Pinned is a filter of its own
 * (`InboxFilter.Pinned`, `scope=pinned`).
 */
internal fun activityConversationListEntries(
    items: List<InboxItem>,
    timeContext: RaftTimeContext? = null,
): List<ActivityConversationListEntry> =
    buildList {
        groupActivityRowsByDay(items, timeContext).forEach { group ->
            group.label?.let { label -> add(ActivityConversationListEntry.DayHeading(group.key, label, first = isEmpty())) }
            group.rows.forEachIndexed { index, item -> add(ActivityConversationListEntry.Row(item, divider = index > 0)) }
        }
    }

/**
 * The rows in the order the person sees them. "Prewarm the next thread after the clicked one" follows
 * this order.
 */
internal fun activityRowsInDisplayOrder(conversationListEntries: List<ActivityConversationListEntry>): List<InboxItem> =
    conversationListEntries.mapNotNull { entry -> (entry as? ActivityConversationListEntry.Row)?.item }

/** What leads the row: the DM peer's face, else a neutral tile for the kind of place. */
internal enum class ActivityConversationLeadingKind { Thread, DirectMessage, Channel, Object }

/**
 * An object row's line (web `ObjectInboxRow`): who did what, then where the object lives —
 * "[face] Alice commented · #proj-workspace". The sentence is built here from the event's kind and
 * why it reached the person ([ActivityObjectEventVerb]); [actorName] null reads "Someone".
 */
@Immutable
internal data class ActivityObjectLine(
    val actorName: String?,
    val verb: ActivityObjectEventVerb,
    val version: Int?,
    val home: String?,
    val avatar: ActivitySenderAvatarIdentity?,
    /** [actorName] is this server's own (a sender profile), never replaced by a joint actor's name. */
    val actorNameKnownLocally: Boolean = false,
)

/**
 * The name an object row's sentence shows (web `ObjectInboxRow`, slock #15104): this server's roster
 * first, then the joint actor `POST /api/actors/resolve` answered for an actor no roster has (another
 * server's agent in a joint channel), then the handle the row carries.
 */
internal fun ActivityObjectLine.displayActorName(jointActor: ResolvedActor?): String? {
    if (actorNameKnownLocally) return actorName
    return jointActor?.displayName?.takeIf { it.isNotBlank() }
        ?: jointActor?.name?.takeIf { it.isNotBlank() }
        ?: actorName
}

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

    /**
     * A joint channel's sender (a thread in one, or the channel's row) whom neither this server's
     * roster nor the row names: the other server's person or agent. The row names them, with
     * [avatar], once `POST /api/actors/resolve` answers ([activityJointSenderName]); until then, or if
     * it never does, the preview stands alone (web InboxRow `useActorFallback`, `jointRow`).
     */
    data class JointActor(val avatar: ActivitySenderAvatarIdentity) : ActivityConversationSender
}

/** The name a [ActivityConversationSender.JointActor] shows: the resolved actor's, else none. */
internal fun activityJointSenderName(jointActor: ResolvedActor?): String? =
    jointActor?.displayName?.takeIf { it.isNotBlank() } ?: jointActor?.name?.takeIf { it.isNotBlank() }

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
    /**
     * Marked unread with nothing counted (botiverse/slock #11795, web `conversation-card-marked-unread`):
     * the quiet unread dot where the count would be.
     */
    val showMarkedUnreadDot: Boolean = false,
    val hasDraft: Boolean,
    val taskNumber: Int?,
    val taskStatus: TaskStatus?,
    /**
     * RFC 077: the assistant that sent the latest message for [sender] — "Alice (sent by
     * Friday)", text only (web ThreadsInbox `formatDelegatedSender`). Never on a system row.
     */
    val delegatedBy: MessageDelegatedBy? = null,
    /** [InboxItemKind.Object] rows: their line instead of sender and preview. */
    val objectLine: ActivityObjectLine? = null,
    /** [InboxItemKind.Object] rows: the kind's icon in the leading tile (web `artifactKindIcon`). */
    val objectIcon: RaftLucideIconName? = null,
    /**
     * Task v2 rows (web ThreadsInbox task row): the corner badge is the task mark (`list-todo`)
     * instead of the thread mark, and the task's status sits in the marks beside the excerpt.
     */
    val taskConversation: Boolean = false,
    val taskStatusMark: TaskStatus? = null,
    /**
     * `task_waiting_on_you_v0` (botiverse/slock #16759): non-null when the task row waits on the
     * signed-in user (after applying live-task clearing via [shownTaskWaiting]).
     */
    val taskWaiting: TaskWaitingOnYou? = null,
    /** Agent Lounge v0: the row is a Lounge (or a thread in one); named and drawn as the Lounge. */
    val lounge: ActivityConversationLounge? = null,
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
 * sidebar's symbol for that channel, and so do the glyphs only the channel record can choose
 * ([RaftChannelKindGlyph.needsChannelRecord]: two people for `hasExternalRelation` or a joint
 * channel, the Slack mark for `bridge`; botiverse/slock #16060). A channel Home doesn't list draws
 * its type alone, like the web `ChannelIcon` fallback (joint: git-branch). An item without a wire
 * type (built from a realtime event) uses the Home row's symbol, else its folded kind (joint), else
 * `#`.
 */
internal fun InboxItem.activityChannelSymbol(homeChannelSymbols: Map<String, String>): String {
    val thread = kind == InboxItemKind.Thread
    val placeId = if (thread) parentChannelId else channelId
    val wireType = if (thread) parentChannelType else channelType
    val homeSymbol = placeId?.let { homeChannelSymbols[it.value] }
    if (wireType == null) {
        return homeSymbol
            ?: raftChannelKindGlyph((if (thread) parentChannelKind else channelKind)?.toWireChannelKindOrNull()).symbol
    }
    val symbol = raftChannelKindGlyph(wireType).symbol
    val homeGlyph = RaftChannelKindGlyph.fromSymbol(homeSymbol)
    return when {
        homeGlyph != null && homeGlyph.needsChannelRecord -> homeGlyph.symbol
        symbol == "#" && homeGlyph == RaftChannelKindGlyph.PublicPage -> RaftChannelKindGlyph.PublicPage.symbol
        else -> symbol
    }
}

/** The web `InboxRow` list-layout projection of one Activity item. Pure; unit-tested. */
internal fun InboxItem.activityConversationRowModel(
    context: ActivityConversationListContext,
    selectedFilter: InboxFilter,
    senderProfiles: Map<String, SharedActivitySenderProfile> = emptyMap(),
    dmPeerNames: Map<String, String> = emptyMap(),
): ActivityConversationRowModel {
    if (kind == InboxItemKind.Object) return activityObjectRowModel(selectedFilter, senderProfiles, context.jointChannelIds)
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
    // Task v2 (web ThreadsInbox): a task row is titled by the task's own title and its address
    // (`#channel:#7`) moves to the body line where a thread row names its channel.
    val taskConversationRow = kind == InboxItemKind.Thread && isTaskConversation
    val taskRef =
        taskNumber?.takeIf { taskConversationRow }?.let { number ->
            val placeName =
                if (parentChannelKind == ConversationKind.DirectMessage) {
                    threadDmPeer?.displayName ?: parentChannelId?.let { dmPeerNames[it.value] } ?: parentChannelName
                } else {
                    parentChannelName
                }
            formatTaskConversationRef(number, placeName, parentChannelKind)
        }
    val taskTitle = taskConversation?.taskTitle?.trim()?.takeIf { taskConversationRow && it.isNotBlank() }
    val title =
        when {
            taskTitle != null -> RaftMarkdownPreview(taskTitle)
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
            !self && senderName == null -> {
                // A joint channel's sender from the other server: in neither roster, and the server
                // names only external projections. Resolve them as web does (`useActorFallback`).
                val identity = activitySenderAvatarIdentity(senderProfiles)
                val faced = identity.senderKind == MemberKind.Agent || identity.senderKind == MemberKind.Human
                identity
                    .takeIf { it.jointSurface && faced && !it.senderId.isNullOrBlank() && threadDmPeer == null }
                    ?.let(ActivityConversationSender::JointActor)
            }
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
    val markedDot = showsMarkedUnreadDot
    val liveTask = taskConversation?.taskId?.takeIf { taskConversationRow }?.let { context.liveTasksById[it] }
    val effectiveTaskStatus = (liveTask?.status ?: taskStatus).takeIf { taskConversationRow }
    val effectiveTaskWaiting =
        if (taskConversationRow && context.taskWaitingEnabled && effectiveTaskStatus != null) {
            shownTaskWaiting(taskWaiting, liveTask, context.currentMemberId)
        } else {
            null
        }
    return ActivityConversationRowModel(
        leadingKind = leadingKind,
        dmPeer = dmPeer,
        title = title,
        threadChannelLabel =
            when {
                kind != InboxItemKind.Thread -> null
                taskTitle != null && taskRef != null -> taskRef
                // The Home DM row names the peer the face shows.
                threadDmPeer?.displayName != null -> "@${threadDmPeer.displayName.stripActivityTitlePrefix()}"
                else -> channelLabel(dmPeerNames)
            },
        threadChannelLabelVisible = kind == InboxItemKind.Thread && (threadDmPeer == null || (taskTitle != null && taskRef != null)),
        threadInDirectMessage = threadInDirectMessage,
        channelSymbol =
            if (leadingKind == ActivityConversationLeadingKind.DirectMessage || threadInDirectMessage) "#" else activityChannelSymbol(context.channelSymbolsByChannelId),
        sender = sender,
        preview = preview?.let(::markdownToPreview)?.takeIf { it.text.isNotBlank() },
        muted = muted,
        strong = (unread > 0 || markedDot) && !muted,
        showMentionMarker = selectedFilter != InboxFilter.Mentions && hasMention && unread > 0,
        unreadCount = unread,
        showUnreadCount = !muted && unread > 0,
        showMarkedUnreadDot = !muted && markedDot,
        hasDraft = hasDraft,
        taskNumber = taskNumber.takeIf { kind == InboxItemKind.Thread && !taskConversationRow && taskStatus != null },
        taskStatus = taskStatus.takeIf { kind == InboxItemKind.Thread && !taskConversationRow && taskNumber != null },
        delegatedBy = previewDelegatedBy(),
        taskConversation = taskConversationRow,
        taskStatusMark = effectiveTaskStatus,
        taskWaiting = effectiveTaskWaiting,
        lounge =
            if (directMessage || threadInDirectMessage) {
                null
            } else {
                context.loungesByChannelId[(if (kind == InboxItemKind.Thread) parentChannelId ?: channelId else channelId).value]
            },
    )
}

/**
 * An object row (web `ObjectInboxRow`, raft-ui InboxItem compact): the kind's tile, the object's
 * title, "[face] who did what · #home", then the time and the unread / @ marks. Never muted.
 */
internal fun InboxItem.activityObjectRowModel(
    selectedFilter: InboxFilter,
    senderProfiles: Map<String, SharedActivitySenderProfile> = emptyMap(),
    jointChannelIds: Set<String> = emptySet(),
): ActivityConversationRowModel {
    val row = activityObject ?: ActivityObjectRow()
    val unread = unreadCount.coerceAtLeast(0)
    val localActorName = senderProfiles[row.actorId]?.displayName?.takeIf { it.isNotBlank() }
    val actorName = localActorName ?: row.actorName?.takeIf { it.isNotBlank() }
    val actorKind = if (row.actorType == "agent") MemberKind.Agent else MemberKind.Human
    val (verb, version) = row.eventVerb()
    return ActivityConversationRowModel(
        leadingKind = ActivityConversationLeadingKind.Object,
        dmPeer = null,
        title = row.title?.trim()?.takeIf { it.isNotEmpty() }?.let(::RaftMarkdownPreview),
        threadChannelLabel = null,
        sender = null,
        preview = null,
        muted = false,
        strong = unread > 0,
        showMentionMarker = selectedFilter != InboxFilter.Mentions && hasMention && unread > 0,
        unreadCount = unread,
        showUnreadCount = unread > 0,
        hasDraft = false,
        taskNumber = null,
        taskStatus = null,
        objectLine =
            ActivityObjectLine(
                actorName = actorName,
                verb = verb,
                version = version,
                home = row.homeText,
                avatar =
                    ActivitySenderAvatarIdentity(
                        senderKind = actorKind,
                        avatarWireValue = null,
                        label = actorName.orEmpty(),
                        senderId = row.actorId.takeIf { it.isNotBlank() },
                        // An object in a joint channel may be the other server's agent's or person's,
                        // in neither of this server's rosters: resolve them as its conversation rows do.
                        jointSurface = row.homeChannelId?.let { it in jointChannelIds } == true,
                    ),
                actorNameKnownLocally = localActorName != null,
            ),
        objectIcon = ArtifactKind.fromWire(row.artifactKind).lucideIcon(),
    )
}

/** The object row's sentence with its actor in the sender's style (raft-ui `InboxItemSender`). */
@Composable
internal fun activityObjectBodyText(line: ActivityObjectLine, senderStyle: SpanStyle): AnnotatedString {
    // The same joint actor the face reads ([ActivityMemberFace]); null while a roster knows them.
    val jointActor =
        rememberRaftJointActor(
            memberAvatarResolver = LocalActivityAvatarSource.current.memberAvatarResolver,
            kind = line.avatar?.senderKind,
            memberId = line.avatar?.senderId,
            enabled = line.avatar?.jointSurface == true && !line.actorNameKnownLocally,
        )
    val name = line.displayActorName(jointActor) ?: stringResource(Res.string.artifacts_activity_someone)
    val sentence =
        when (line.verb) {
            ActivityObjectEventVerb.MentionedYou -> stringResource(Res.string.artifacts_activity_mentioned_you, ActivityObjectNameMark)
            ActivityObjectEventVerb.Commented -> stringResource(Res.string.artifacts_activity_commented, ActivityObjectNameMark)
            ActivityObjectEventVerb.EditedComment -> stringResource(Res.string.artifacts_activity_edited_comment, ActivityObjectNameMark)
            ActivityObjectEventVerb.Settled -> stringResource(Res.string.artifacts_activity_settled, ActivityObjectNameMark)
            ActivityObjectEventVerb.Unsettled -> stringResource(Res.string.artifacts_activity_unsettled, ActivityObjectNameMark)
            ActivityObjectEventVerb.PublishedVersionSinceSettled ->
                stringResource(Res.string.artifacts_activity_published_version_since_settled, ActivityObjectNameMark, line.version ?: 0)
            ActivityObjectEventVerb.PublishedVersion ->
                stringResource(Res.string.artifacts_activity_published_version, ActivityObjectNameMark, line.version ?: 0)
            ActivityObjectEventVerb.Published -> stringResource(Res.string.artifacts_activity_published, ActivityObjectNameMark)
            ActivityObjectEventVerb.Updated -> stringResource(Res.string.artifacts_activity_updated, ActivityObjectNameMark)
        }
    val home = line.home?.let { stringResource(Res.string.activity_list_object_home, it) }
    return activityObjectBodyText(sentence, name, home, senderStyle, avatar = line.avatar != null)
}

/** Pure part of [activityObjectBodyText]: [sentence] holds [ActivityObjectNameMark] where the name goes. */
internal fun activityObjectBodyText(
    sentence: String,
    name: String,
    home: String?,
    senderStyle: SpanStyle,
    avatar: Boolean,
): AnnotatedString =
    buildAnnotatedString {
        if (avatar) appendInlineContent(ActivityConversationSenderAvatarInlineId, alternateText = " ")
        val at = sentence.indexOf(ActivityObjectNameMark)
        if (at < 0) {
            append(sentence)
        } else {
            append(sentence.substring(0, at))
            withStyle(senderStyle) { append(name) }
            append(sentence.substring(at + ActivityObjectNameMark.length))
        }
        if (!home.isNullOrBlank()) append(" $home")
    }

/** Stands in for the actor's name while the localized sentence is resolved. */
internal const val ActivityObjectNameMark = "\u2063NAME\u2063"

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
    /**
     * A joint channel's sender from the other server, resolved through `POST /api/actors/resolve`:
     * fills only a row that carries no avatar of its own (a realtime update, or an older server).
     */
    jointActorAvatarWireValue: String? = null,
): String? {
    memberAvatarWireValue?.takeIf { it.isNotBlank() }?.let { return it }
    val itemAvatar =
        identity.avatarWireValue?.takeIf { it.isNotBlank() }
            ?: jointActorAvatarWireValue?.takeIf { it.isNotBlank() }
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
    val jointActor =
        rememberRaftJointActor(
            memberAvatarResolver = source.memberAvatarResolver,
            kind = identity.senderKind,
            memberId = identity.senderId,
            enabled = identity.jointSurface,
        )
    val explicitAvatar =
        memberAvatar?.appAvatarWireValueOrNull()
            ?: identity.avatarWireValue?.appAvatarWireValueOrNull()
            ?: jointActor?.explicitAvatarWireValue()?.appAvatarWireValueOrNull()
    val avatarPending =
        explicitAvatar == null &&
            rememberRaftMemberAvatarPending(
                memberAvatarResolver = source.memberAvatarResolver,
                kind = identity.senderKind,
                memberId = identity.senderId,
                jointSurface = identity.jointSurface,
            )
    RaftAppAvatar(
        // web Activity rows: an agent's face that does not load is the robot, a person's the
        // placeholder person; a sender of unknown kind has no label to take letters from either.
        kind = raftAvatarKind(identity.senderKind),
        avatarWireValue =
            if (avatarPending) {
                null
            } else {
                activityFaceAvatarWireValue(memberAvatar, identity, jointActor?.explicitAvatarWireValue())
            },
        label = "",
        modifier = modifier,
        avatarSize = avatarSize,
        cacheSubject = raftMemberAvatarCacheSubject(identity.senderKind, identity.senderId),
        cacheServerId = source.cacheServerId,
        avatarPending = avatarPending,
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

/**
 * How an Activity row is laid out: the compact list cell (raft-design patterns/lists.md and
 * components/inbox-item.md): the state drawn on one rounded shape inset [shapeInset] from both sides
 * of the column, the content [contentPadX] inside that shape, [padY] above and below it, rows
 * [rowGap] apart and no line between them. Phones / compact width use the two-line preview
 * (`twoLinePreview = true`, `previewLines = 2`, `reservePreviewLines = false`; raft-design#1433 /
 * #proj-mobile:#372 Option A); wide lists keep the single-line `InboxItemLine` (`previewLines = 1`).
 */
@Immutable
internal data class ActivityRowLayout(
    /** The state shape's inset from each side of the column (`--cell-inset-x`); 0: the fills run edge to edge. */
    val shapeInset: Dp,
    /** The content's inset inside the shape at the start and at the end (`--cell-pad-x`). */
    val contentPadX: Dp,
    /** Above and below the content. */
    val padY: Dp,
    /** The row's least height (`pointer-coarse:min-h-14`). */
    val minHeight: Dp,
    /** Maximum excerpt lines (2 on phone, 1 on wide). */
    val previewLines: Int,
    /** When true, always reserve [previewLines] vertical slots; when false, height follows laid-out lines. */
    val reservePreviewLines: Boolean = false,
    /**
     * Phone / compact two-line preview layout (raft-design#1433 `InboxItemPreview`): top-aligned
     * leading avatar/tile, `#channel ·` in `foregroundHint`, and pixel-only task status in marks.
     * False on wide lists, which keep the single-line `InboxItemLine` layout.
     */
    val twoLinePreview: Boolean = false,
    /**
     * Static top inset on the top-aligned leading avatar/tile so the 36dp leading slot centers on
     * the first two lines (20dp title + 20dp first excerpt line = 40dp -> static 2dp, matching web
     * `data-lead="two"` `margin-top: 2px` with zero runtime calculation). Zero on centered wide rows.
     */
    val leadingTopPadding: Dp = 0.dp,
    /** A hairline between two adjacent rows (none in the web's list cells: lists.md "lists have no dividers"). */
    val dividers: Boolean,
    /** Between two rows (the list's `gap-0.5`). */
    val rowGap: Dp,
    /** Above the first row (web inbox scroller `py-1` and the list's `py-1`). */
    val listTop: Dp,
) {
    /** Where a row's content starts and ends, from the column's edge: the column's margin. */
    val contentEdge: Dp
        get() = shapeInset + contentPadX

    val shape: Shape
        get() = if (shapeInset > 0.dp) RoundedCornerShape(RaftRadius.Control) else RectangleShape

    companion object {
        /**
         * The phone's row: compact list cell with the two-line preview (raft-design#1433): up to two
         * excerpt lines whose height follows content, top-aligned leading visual, `#channel ·` in
         * hint colour, and pixel-only task status. The shape is 8dp in, the content 12dp inside it
         * (20dp from the screen's edge); 8dp above and below, at least 56dp tall by touch (56dp for
         * 1-line body, 76dp for 2-line); rows 2dp apart with no line between them.
         */
        fun phone(pointerInput: RaftPointerInput): ActivityRowLayout =
            ActivityRowLayout(
                shapeInset = 8.dp,
                contentPadX = 12.dp,
                padY = 8.dp,
                minHeight = if (pointerInput == RaftPointerInput.Fine) 0.dp else 56.dp,
                previewLines = 2,
                reservePreviewLines = false,
                twoLinePreview = true,
                leadingTopPadding = 2.dp,
                dividers = false,
                rowGap = 2.dp,
                listTop = 8.dp,
            )

        /**
         * The wide list cell (`InboxItemLine`, raft-design#1433): shape 8dp in, content 12dp inside
         * it by touch (20dp from the column's edge) and 8dp inside it under a mouse or trackpad (16dp);
         * 8dp above and below, at least 56dp tall by touch; one excerpt line, centered avatar, and
         * the `sm` task status badge; rows 2dp apart with no line between them.
         */
        fun wide(pointerInput: RaftPointerInput): ActivityRowLayout =
            ActivityRowLayout(
                shapeInset = 8.dp,
                contentPadX = if (pointerInput == RaftPointerInput.Fine) 8.dp else 12.dp,
                padY = 8.dp,
                minHeight = if (pointerInput == RaftPointerInput.Fine) 0.dp else 56.dp,
                previewLines = 1,
                reservePreviewLines = true,
                twoLinePreview = false,
                dividers = false,
                rowGap = 2.dp,
                listTop = 8.dp,
            )

        fun of(scale: RaftScale, pointerInput: RaftPointerInput): ActivityRowLayout =
            if (scale == RaftScale.Wide) wide(pointerInput) else phone(pointerInput)
    }
}

/** The Activity row layout for the enclosing theme's scale and input. */
internal val activityRowLayout: ActivityRowLayout
    @Composable
    get() = ActivityRowLayout.of(RaftTheme.scale, RaftTheme.pointerInput)

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
    canMarkDone: Boolean,
    openGestureKey: String,
    captureMenuAnchor: Boolean,
    onOpen: () -> Unit,
    onOpenMenu: (RaftPopoverAnchor) -> Unit,
    modifier: Modifier = Modifier,
    /** The swipe's Done button (ActivitySwipeActionsBox); null: no Done button. */
    onSwipeDone: (() -> Unit)? = null,
    /** Called synchronously at swipe-Done commit time; returning false cancels the exit cleanly. */
    onSwipeDoneStart: (() -> Boolean)? = null,
    /**
     * The row's trailing Mark as Done button (web InboxRowDone, raft-ui `InboxItemActions
     * reveal="hover"`): the menu's Done. A mouse or trackpad shows it while the row is hovered, open
     * beside the list or has its menu open; the wide layout by touch shows it at rest; a phone by
     * touch has none (the swipe and the menu instead). Null, or [canMarkDone] false: no button
     * ([activityRowDoneButton]).
     */
    onRowDone: (() -> Unit)? = null,
    /** The swipe's Unfollow / Mute button ([activitySwipeEndAction]); null action or callback: none. */
    swipeEndAction: ActivitySwipeEndAction? = null,
    onSwipeEnd: (() -> Unit)? = null,
    /** Typed variant of [onSwipeEnd] carrying the tapped [ActivitySwipeEndAction]. */
    onSwipeEndAction: ((ActivitySwipeEndAction) -> Unit)? = null,
    /** Called synchronously at swipe-Unfollow commit time; returning false cancels the exit cleanly. */
    onSwipeEndStart: ((ActivitySwipeEndAction) -> Boolean)? = null,
    /**
     * The swipe toward the end's Read / Unread button (raft-design #793, [activitySwipeStartButton]): the
     * menu's Mark as read / unread. Null: no start button.
     */
    onSwipeToggleRead: (() -> Unit)? = null,
    /** The side the list has this row's swipe open on ([ActivitySwipeOpenRow]); null: closed. */
    swipeOpenSide: ActivitySwipeSide? = null,
    /** The row claimed a swipe or settled open on a side, or closed (null). */
    onSwipeOpenChange: (ActivitySwipeSide?) -> Unit = {},
    /** Where the open row is, so a tap outside it closes it ([ActivitySwipeOpenRowGeometry]). */
    onSwipeOpenRowPlaced: ((LayoutCoordinates) -> Unit)? = null,
    /**
     * Stable owner+row key for the swipe reveal / exit controller so unrelated list reorders
     * (which bump [openGestureKey]'s presentation generation) do not abort an in-progress Done
     * exit animation or snap an open row closed. Defaults to [openGestureKey].
     */
    swipeStateKey: String = openGestureKey,
    /** True while a non-swipe Done / Unfollow (row button, menu, a11y) collapses this row out. */
    exiting: Boolean = false,
    /** Called once [exiting]'s height collapse finishes at zero px. */
    onExitCollapsed: () -> Unit = {},
    /** Called when an in-flight swipe or collapse exit disposes before its completion callback ran. */
    onInFlightExitDisposed: () -> Unit = {},
    /** Screenshots only: the row slid by this share of its width on top of [swipeOpenSide] (negative: toward the end). */
    swipePreviewRevealFraction: Float = 0f,
    /** Screenshots only: draw the row as a mouse hovers it (the trailing Done button shown under a fine pointer). */
    hoverPreview: Boolean = false,
    /**
     * The row's screen-reader actions (raft-design #776 / #777): the row menu's items
     * ([activityContextMenuActionIds]), named as in the menu, run through [onAccessibilityAction] with
     * their id. A screen reader can't swipe or press and hold, so this is how it reaches Done, Unfollow,
     * Mute and Mark as read / unread.
     */
    accessibilityActionIds: List<String> = emptyList(),
    onAccessibilityAction: (actionId: String) -> Unit = {},
    layout: ActivityRowLayout = activityRowLayout,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val hairline = colors.lineHairline
    val rowShape = layout.shape
    // One title line and up to [layout.previewLines] excerpt lines, each a fixed slot of its style's
    // line height so an inline face, mark, or CJK fallback glyph never shifts a line box; height
    // follows the laid-out line count when [layout.reservePreviewLines] is false (56dp 1-line, 76dp 2-line).
    val density = LocalDensity.current
    val titleLine = with(density) { typography.subhead.lineHeight.toDp() }
    val bodyLine = with(density) { typography.callout.lineHeight.toDp() }
    val titleColor = if (model.strong) colors.foreground else colors.foregroundMuted
    val bodyColor = if (model.strong) colors.foreground else colors.foregroundMuted
    val threadFallback = stringResource(Res.string.activity_fallback_thread_update)
    val actionFallback = stringResource(Res.string.activity_fallback_action_required)
    val conversationFallback = stringResource(Res.string.activity_fallback_conversation_update)
    val systemLabel = stringResource(Res.string.activity_list_system_sender)
    val selfLabel = stringResource(Res.string.activity_list_self_sender)
    val openLabel = stringResource(Res.string.common_open)
    val latestOnAccessibilityAction by rememberUpdatedState(onAccessibilityAction)
    val accessibilityActions =
        accessibilityActionIds.map { actionId ->
            CustomAccessibilityAction(activityRowActionLabel(item, actionId)) {
                latestOnAccessibilityAction(actionId)
                true
            }
        }
    val loungeTitle = model.lounge?.let { agentLoungeTitle(it.agentName) }
    val threadChannelText =
        (loungeTitle?.takeIf { model.threadChannelLabel != null && model.taskConversation.not() } ?: model.threadChannelLabel)
            ?.let { stringResource(Res.string.activity_list_thread_channel, it) }
    // A DM thread's face says the peer, so its line drops "@peer ·"; screen readers still hear it.
    val visibleThreadChannelText = threadChannelText?.takeIf { model.threadChannelLabelVisible }
    val untitledObject = stringResource(Res.string.artifacts_activity_untitled)
    val fallback =
        when (item.kind) {
            InboxItemKind.Thread -> threadFallback
            InboxItemKind.MentionAction -> actionFallback
            InboxItemKind.Object -> untitledObject
            else -> conversationFallback
        }
    var pressed by remember(item.key) { mutableStateOf(false) }
    // raft-ui InboxItem is a list cell: a mouse or trackpad lays `fill-cell-hover` (150ms ease-out),
    // never a finger. A selected row, a row with its menu open and a pressed row keep their own fill.
    val hoverSource = remember { MutableInteractionSource() }
    val hover =
        rememberRaftStateOverlay(hoverSource, RaftHoverTier.Cell, selected = selected || menuOpen || pressed, highlighted = hoverPreview)
    // A mouse or trackpad over the row (never a finger: raftPointerHoverPosition drops touch).
    var pointerHovering by remember(item.key) { mutableStateOf(false) }
    val doneButton =
        activityRowDoneButton(
            pointerInput = RaftTheme.pointerInput,
            revealed = pointerHovering || hoverPreview || selected || menuOpen,
            canMarkDone = canMarkDone,
            hasAction = onRowDone != null,
        )
    // raft-ui `--inbox-item-actions-room`: while Done shows, both lines end short of it by its width
    // plus the gap, so the time and the marks move over and stay in view (never fade). The button is
    // [RaftIconButtonSize.Row]: the 28dp bar button under a fine pointer (metrics with a row height),
    // so the room is 28 + 12 = 40, as the web's.
    val doneButtonBox = RaftTheme.controls.let { if (it.rowHeight != null) it.barButtonSize else ActivityRowDoneButtonBox }
    val doneRoom = doneButton?.let { doneButtonBox + it.gap } ?: 0.dp
    val swipeButtons =
        activitySwipeButtons(
            doneEnabled = canMarkDone && onSwipeDone != null,
            endActions = listOfNotNull(swipeEndAction.takeIf { onSwipeEnd != null || onSwipeEndAction != null }),
        )
    ActivitySwipeActionsBox(
        gestureKey = openGestureKey,
        stateKey = swipeStateKey,
        buttons = swipeButtons,
        openSide = swipeOpenSide,
        onOpenChange = onSwipeOpenChange,
        onDone = { onSwipeDone?.invoke() },
        onAction = { action -> onSwipeEndAction?.invoke(action) ?: onSwipeEnd?.invoke() },
        startButton = item.activitySwipeStartButton().takeIf { onSwipeToggleRead != null },
        onToggleRead = { onSwipeToggleRead?.invoke() },
        modifier = modifier.fillMaxWidth(),
        onOpenRowPlaced = onSwipeOpenRowPlaced,
        exiting = exiting,
        onExitCollapsed = onExitCollapsed,
        onBeforeDone = { onSwipeDoneStart?.invoke() ?: true },
        onBeforeExitAction = { action -> onSwipeEndStart?.invoke(action) ?: true },
        onInFlightExitDisposed = onInFlightExitDisposed,
        previewRevealFraction = swipePreviewRevealFraction,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // The list cell: its state shape sits in from both sides of the column.
                    .padding(horizontal = layout.shapeInset)
                    .then(
                        when {
                            selected -> Modifier.background(colors.infoSoft, rowShape)
                            menuOpen -> Modifier.background(colors.fillSubtle, rowShape)
                            // raft-ui 0.20: an inbox row presses with the medium tier.
                            pressed -> Modifier.background(colors.fillMedium, rowShape)
                            else -> Modifier
                        }
                    )
                    .raftStateOverlay(hover, rowShape)
                    .raftPointerHover(hoverSource)
                    .raftPointerHoverPosition { position -> pointerHovering = position != null }
                    .then(
                        if (divider && layout.dividers) {
                            Modifier.drawBehind {
                                val start = ConversationDividerStart.toPx()
                                drawLine(hairline, Offset(start, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                            }
                        } else {
                            Modifier
                        }
                    )
                    // The washes above span the shape; the row's content (and its actions)
                    // stays out of the horizontal safe area.
                    .raftSafeAreaHorizontalPadding(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .activityRowGestures(openGestureKey, captureMenuAnchor, onOpen, onOpenMenu, onPressedChange = { pressed = it })
                        // One focusable button per row for screen readers: title, preview and marks read
                        // together, and the accessibility click opens the row like a tap.
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            onClick(label = openLabel) {
                                onOpen()
                                true
                            }
                            if (accessibilityActions.isNotEmpty()) customActions = accessibilityActions
                        }
                        .heightIn(min = layout.minHeight)
                        .padding(start = layout.contentPadX, end = layout.contentPadX + doneRoom, top = layout.padY, bottom = layout.padY),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = if (layout.twoLinePreview) Alignment.Top else Alignment.CenterVertically,
            ) {
                ActivityConversationLeading(
                    model = model,
                    fallbackLabel = item.channelName,
                    agentStatusResolver = agentStatusResolver,
                    modifier = if (layout.leadingTopPadding > 0.dp) Modifier.padding(top = layout.leadingTopPadding) else Modifier,
                )
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
                                text =
                                    loungeTitle?.takeIf { item.kind != InboxItemKind.Thread }?.let(::AnnotatedString)
                                        ?: model.title?.toAnnotatedString()
                                        ?: AnnotatedString(fallback),
                                modifier = Modifier.weight(1f, fill = false),
                                style = typography.subhead.copy(fontWeight = if (model.strong) FontWeight.Bold else FontWeight.SemiBold),
                                color = titleColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // botiverse/slock #11273: the DM with an App Bot names it a Bot.
                            val appBot = rememberRaftMemberAppBot(model.activityDmAppBotPeerId())
                            if (appBot != null) {
                                RaftAppBotBadge(identity = appBot, botName = model.title?.toAnnotatedString()?.text)
                            }
                            if (model.taskNumber != null && model.taskStatus != null) {
                                SharedActivityFooterBadge(
                                    label = "#${model.taskNumber}",
                                    slot = SharedActivityFooterSlot.Task,
                                    taskStatus = model.taskStatus,
                                )
                            }
                        }
                        ActivityConversationTime(item, timeContext, pointerHovering || hoverPreview)
                    }
                    // One AnnotatedString for "#channel · [face] Sender: preview" so the line truncates as a
                    // whole like web's InboxItemBody: a long channel name ellipsizes the line instead of
                    // starving the preview to zero width. The sender face rides inline as a placeholder.
                    // A joint sender from the other server: named (and faced) once the actor resolves.
                    val jointSender = model.sender as? ActivityConversationSender.JointActor
                    val jointSenderName =
                        activityJointSenderName(
                            rememberRaftJointActor(
                                memberAvatarResolver = LocalActivityAvatarSource.current.memberAvatarResolver,
                                kind = jointSender?.avatar?.senderKind,
                                memberId = jointSender?.avatar?.senderId,
                                enabled = jointSender != null,
                            )
                        )
                    val senderAvatar =
                        when (val sender = model.sender) {
                            is ActivityConversationSender.Named -> sender.avatar
                            is ActivityConversationSender.Self -> sender.avatar
                            is ActivityConversationSender.JointActor -> sender.avatar.takeIf { jointSenderName != null }
                            else -> model.objectLine?.avatar
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
                    // Line 2+: the excerpt, up to [ActivityRowLayout.previewLines] lines (height follows
                    // content when `!layout.reservePreviewLines`), the marks beside its first line only so
                    // line 2 uses full width.
                    val senderText =
                        when (val sender = model.sender) {
                            ActivityConversationSender.System -> systemLabel
                            is ActivityConversationSender.Self -> delegatedPreviewSender(selfLabel, model.delegatedBy)
                            is ActivityConversationSender.Named -> delegatedPreviewSender(sender.name, model.delegatedBy)
                            is ActivityConversationSender.JointActor -> jointSenderName?.let { delegatedPreviewSender(it, model.delegatedBy) }
                            null -> null
                        }
                    val senderStyle = SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.foreground)
                    val channelStyle = if (layout.twoLinePreview) SpanStyle(color = colors.foregroundHint) else null
                    val bodyText =
                        model.objectLine?.let { line -> activityObjectBodyText(line, senderStyle) }
                            ?: activityConversationBodyText(
                                channelLabel = visibleThreadChannelText,
                                channelStyle = channelStyle,
                                sender = senderText,
                                preview = model.preview?.toAnnotatedString() ?: AnnotatedString(fallback),
                                senderStyle = senderStyle,
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
                        maxLines = layout.previewLines,
                        reserveLines = layout.reservePreviewLines,
                        marks = {
                            ActivityConversationMarks(
                                model = model,
                                marksHeight = bodyLine,
                                taskStatusPixelOnly = layout.twoLinePreview,
                                timeContext = timeContext,
                            )
                        },
                    )
                }
            }
            if (doneButton != null && onRowDone != null) {
                // web InboxRowDone: Lucide Check in an icon-md Button, its tooltip "Mark as Done", named
                // after its row; centred on the row at the content's end inset. Outside the row's
                // gesture area, so a tap or click marks the row done without opening it.
                RaftIconButton(
                    onClick = onRowDone,
                    tooltip = stringResource(Res.string.thread_action_mark_done),
                    contentDescription = activityRowDoneLabel(item, model, loungeTitle, fallback),
                    variant = doneButton.variant,
                    size = RaftIconButtonSize.Row,
                    hitSize = doneButton.hitSize,
                    modifier =
                        Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = layout.contentPadX)
                            .testTag(ActivityRowDoneTag)
                            .semantics { activityRowDoneLook = doneButton },
                ) {
                    RaftContentLucideIcon(RaftLucideIconName.Check, size = 18.dp)
                }
            }
        }
    }
}

internal const val ActivityRowDoneTag = "activity-row-done"

/** The Done button's box with a finger (raft-ui `icon-md`, 32px); a fine pointer takes the 28dp bar button ([RaftIconButtonSize.Row]). */
private val ActivityRowDoneButtonBox = 32.dp

/**
 * How a row's Mark as Done shows (web InboxRowDone; raft-design components/inbox-item.md, Behavior
 * and Rules).
 */
internal enum class ActivityRowDoneButton(
    val variant: RaftButtonVariant,
    /** Between the button and the time and the marks (`--inbox-item-actions-room` less the button). */
    val gap: Dp,
    /** Its target, past the 32dp button; null: the button's own box. */
    val hitSize: Dp?,
) {
    /**
     * A mouse or trackpad, while the row is hovered, open beside the list or has its menu open:
     * a ghost 28dp row action (web InboxRowDone `md:pointer-fine:size-(--bar-button-size)`) on the
     * row's hover fill, 12dp from the time and the marks (`--inbox-item-actions-room` 40).
     */
    Revealed(RaftButtonVariant.Ghost, gap = 12.dp, hitSize = null),
}

/**
 * Which Done button a row shows, or null for none: none on a row without Done; none by touch at any
 * width (Done is the left swipe's first button and the press-and-hold menu's item, and the row's
 * screen-reader action; raft-design #769 for phones, xxchan 2026-11-22 for tablets: the wide layout's
 * at-rest button only repeated the swipe); with a mouse or trackpad, at any width, only while
 * [revealed] (hovered, open beside the list or its menu open).
 */
internal fun activityRowDoneButton(
    pointerInput: RaftPointerInput,
    revealed: Boolean,
    canMarkDone: Boolean,
    hasAction: Boolean,
): ActivityRowDoneButton? =
    when {
        !canMarkDone || !hasAction -> null
        pointerInput == RaftPointerInput.Fine -> ActivityRowDoneButton.Revealed.takeIf { revealed }
        else -> null
    }

/** Tests: which [ActivityRowDoneButton] the Done button node draws. */
internal val ActivityRowDoneLookKey = SemanticsPropertyKey<ActivityRowDoneButton>("ActivityRowDoneLook")
private var SemanticsPropertyReceiver.activityRowDoneLook by ActivityRowDoneLookKey

/**
 * The Done button's name, after its row (web `thread.row.markRowAsDone` / `markThreadAsDone`,
 * raft-design #777), so a list read aloud doesn't repeat one name: a task conversation by its task,
 * a thread by the place it lives in ("Mark the thread in #design as Done"), any other row by its
 * place ("Mark #release as Done", "Mark @Cindy as Done").
 */
@Composable
internal fun activityRowDoneLabel(item: InboxItem, model: ActivityConversationRowModel, loungeTitle: String?, fallback: String): String {
    val title = model.title?.text?.takeIf { it.isNotBlank() }
    return when {
        model.taskConversation -> stringResource(Res.string.activity_row_mark_row_done, title ?: fallback)
        item.kind == InboxItemKind.Thread ->
            stringResource(Res.string.activity_row_mark_thread_done, loungeTitle ?: model.threadChannelLabel ?: fallback)
        loungeTitle != null -> stringResource(Res.string.activity_row_mark_row_done, loungeTitle)
        title == null -> stringResource(Res.string.activity_row_mark_row_done, fallback)
        item.kind == InboxItemKind.Object -> stringResource(Res.string.activity_row_mark_row_done, title)
        model.leadingKind == ActivityConversationLeadingKind.DirectMessage ->
            stringResource(Res.string.activity_row_mark_row_done, "@$title")
        else -> stringResource(Res.string.activity_row_mark_row_done, "#$title")
    }
}

/**
 * Default maximum excerpt lines for [ActivityExcerptBesideMarks]: wraps to a second line before
 * ellipsizing. Drafts keeps `reserveLines = true`; Activity rows pass `reserveLines = false`
 * ([ActivityRowLayout.reservePreviewLines]) so 1-line previews stay compact.
 */
internal const val ActivityConversationPreviewMaxLines = 2

internal const val ActivityConversationSenderAvatarInlineId = "activity-conversation-sender-avatar"

/**
 * The body, up to [ActivityConversationPreviewMaxLines] lines: "#channel · " (thread/task rows,
 * optionally styled in [channelStyle] e.g. `foregroundHint`), the sender's face (inline placeholder
 * [ActivityConversationSenderAvatarInlineId]), "Sender: " in semibold `foreground` (raft-ui
 * `InboxItemSender`), then the preview. One string, so it wraps and ellipsizes together.
 */
internal fun activityConversationBodyText(
    sender: String?,
    preview: AnnotatedString,
    senderStyle: SpanStyle = SpanStyle(fontWeight = FontWeight.SemiBold),
    channelLabel: String? = null,
    channelStyle: SpanStyle? = null,
    senderAvatar: Boolean = false,
): AnnotatedString =
    buildAnnotatedString {
        if (!channelLabel.isNullOrBlank()) {
            if (channelStyle != null) {
                withStyle(channelStyle) { append("$channelLabel ") }
            } else {
                append("$channelLabel ")
            }
        }
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

/**
 * The row's leading tile (web `ConversationAvatar`), shared with Drafts (RaftDraftRow).
 * [fallbackLabel]: the DM peer's name when the peer has no display name.
 */
@Composable
internal fun ActivityConversationLeading(
    model: ActivityConversationRowModel,
    fallbackLabel: String,
    agentStatusResolver: RaftAgentStatusResolver,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    // Draft (cindyz, icon flip): a thread or task in a channel leads with the thread / task mark in the
    // tile and the channel's glyph in the corner badge. A thread in a DM keeps its peer's face with the
    // thread badge (DM rows unchanged).
    val flipped = model.leadingThreadBadge && model.dmPeer == null && !model.threadInDirectMessage
    val threadBadge: (@Composable () -> Unit)? =
        if (flipped) {
            {
                RaftInboxItemLeadingBadge {
                    if (model.lounge != null) {
                        // Agent Lounge v0: a thread in a Lounge wears the Lounge mark as its place badge.
                        RaftContentLucideIcon(AgentLoungeIcon, size = RaftInboxItemLeadingDefaults.BadgeIcon)
                    } else {
                        HomeSidebarChannelGlyph(model.channelSymbol, size = RaftInboxItemLeadingDefaults.BadgeIcon)
                    }
                }
            }
        } else if (model.leadingThreadBadge) {
            {
                RaftInboxItemLeadingBadge {
                    if (model.taskConversation) {
                        // web `ThreadCornerMark task`: lucide `list-todo`, in the badge's ink.
                        RaftLucideIcon(
                            RaftLucideIconName.ListTodo,
                            Modifier.size(RaftInboxItemLeadingDefaults.BadgeIcon),
                            color = raftArgbHex(colors.foreground),
                        )
                    } else {
                        // raft-ui `ThreadIcon`, in the badge's `foreground` ink.
                        RaftConversationKindIcon(RaftConversationKindGlyph.Thread, size = RaftInboxItemLeadingDefaults.BadgeIcon)
                    }
                }
            }
        } else {
            null
        }
    RaftInboxItemLeading(badge = threadBadge, modifier = modifier) {
        val peer = model.dmPeer
        val lounge = model.lounge
        if (lounge != null && !flipped && model.leadingKind == ActivityConversationLeadingKind.Channel) {
            // Agent Lounge v0: a Lounge is drawn as its agent's face with the Lounge mark, not a # tile.
            RaftLoungeAvatar(
                agentId = lounge.agentId,
                avatarWireValue = lounge.avatarWireValue,
                label = lounge.agentName,
                cacheServerId = null,
                avatarSize = ConversationLeadingAvatar,
            )
        } else if (peer != null) {
            val label = peer.displayName ?: fallbackLabel
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
            // not loaded yet shows the DM glyph in the tile's ink (web `DirectMessageIcon`), never `#`.
            RaftIconTile(size = RaftIconTileSize.Md) {
                val objectIcon = model.objectIcon
                if (flipped && model.taskConversation) {
                    RaftLucideIcon(RaftLucideIconName.ListTodo, Modifier.size(ConversationTileGlyph), color = raftArgbHex(colors.foregroundMuted))
                } else if (flipped) {
                    RaftConversationKindIcon(RaftConversationKindGlyph.Thread, size = ConversationTileGlyph)
                } else if (model.leadingKind == ActivityConversationLeadingKind.Object && objectIcon != null) {
                    RaftLucideIcon(objectIcon, Modifier.size(ConversationTileGlyph), color = raftArgbHex(colors.foregroundMuted))
                } else if (model.leadingKind == ActivityConversationLeadingKind.DirectMessage || model.threadInDirectMessage) {
                    RaftConversationKindIcon(RaftConversationKindGlyph.DirectMessage, size = ConversationTileGlyph)
                } else {
                    HomeSidebarChannelGlyph(model.channelSymbol, size = ConversationTileGlyph)
                }
            }
        }
    }
}

/**
 * The time at the end of the row's first line, in a list grouped by day (web ActivityRowTime, slock
 * #13030): today's rows and any row under an hour old show it short ("now", "3m", "2h"); an earlier
 * day's row, whose heading names the day, shows its clock alone ("23:15"): while a mouse or trackpad
 * hovers it when one is in use, and always with a finger, which cannot hover (Jasper,
 * #proj-raft-ui:#274). Screen readers hear it in words on every row ("yesterday at 17:20").
 */
@Composable
private fun ActivityConversationTime(
    item: InboxItem,
    timeContext: RaftTimeContext?,
    pointerHovering: Boolean,
    modifier: Modifier = Modifier,
) {
    val effectiveContext = currentRaftTimeContext(timeContext)
    val rowTime = activityRowTime(item.activityRowTimeValue(), effectiveContext)
    if (rowTime == null) {
        ActivityConversationTimeFallback(item, timeContext, modifier)
        return
    }
    val words = rowTime.words()
    val shown = rowTime.shown(RaftTheme.pointerInput, pointerHovering)
    RaftText(
        text = rowTime.shownLabel(),
        modifier =
            modifier
                .widthIn(max = 96.dp)
                .then(if (shown) Modifier else Modifier.alpha(0f))
                .clearAndSetSemantics { contentDescription = words },
        style = RaftTheme.typography.monoMeta.copy(fontFeatureSettings = "tnum"),
        color = RaftTheme.colors.foregroundHint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** No live clock or no readable time: the plain relative time, as before day groups. */
@Composable
private fun ActivityConversationTimeFallback(item: InboxItem, timeContext: RaftTimeContext?, modifier: Modifier = Modifier) {
    RaftRelativeTimestampText(
        value = item.activityRowTimeValue(),
        timeContext = timeContext,
        style = RaftTheme.typography.monoMeta.copy(fontFeatureSettings = "tnum"),
        color = RaftTheme.colors.foregroundHint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        clampFuture = true,
        // Web InboxItemTimestamp is plain text: a tap near the time opens the row.
        toggleEnabled = false,
        modifier = modifier.widthIn(max = 96.dp),
    )
}

internal const val ActivityTaskWaitingMarkTag = "activity-task-waiting-mark"

/**
 * Pure detail formatting for a waiting-on-you Activity task row (web `taskWaitingDetail.ts`,
 * slock #16759): builds the tooltip string (`"{status} · {who}"` or `"{status} · {who} · {reason}"`).
 */
internal inline fun formatTaskWaitingDetail(
    statusLabel: String,
    whoLabel: String,
    waiting: TaskWaitingOnYou,
    formatWithReason: (status: String, who: String, reason: String) -> String,
    formatWithoutReason: (status: String, who: String) -> String,
): String {
    val reason =
        waiting.reason
            ?.takeIf { waiting.via == TaskWaitingVia.Assign && it.isNotBlank() }
            ?.let { text -> if (waiting.reasonTruncated) "$text…" else text }
    return if (reason != null) {
        formatWithReason(statusLabel, whoLabel, reason)
    } else {
        formatWithoutReason(statusLabel, whoLabel)
    }
}

/**
 * Waiting-on-you indicator on an Activity task row (web `TaskWaitingStatus.tsx`, slock #16759):
 * keeps the task's status color/dot and replaces the badge text with "Waiting on you" (on wide) or
 * preserves the compact 10dp pixel (on phone), with a tooltip explaining status, who/when, and
 * optional assignment reason.
 */
@Composable
private fun ActivityTaskWaitingStatus(
    status: TaskStatus,
    waiting: TaskWaitingOnYou,
    pixelOnly: Boolean,
    timeContext: RaftTimeContext?,
) {
    val statusLabel = status.localizedLabel()
    val timeLabel = raftShortRelativeTimestampLabel(waiting.since, timeContext = timeContext)
    val assignerName = waiting.assignedBy?.name?.takeIf { it.isNotBlank() }
    val whoLabel =
        when {
            waiting.via == TaskWaitingVia.Assign && assignerName != null ->
                stringResource(Res.string.activity_list_waiting_assigned_by, assignerName, timeLabel)
            waiting.via == TaskWaitingVia.Claim ->
                stringResource(Res.string.activity_list_waiting_claimed, timeLabel)
            else ->
                stringResource(Res.string.activity_list_waiting_assigned, timeLabel)
        }
    val detail =
        formatTaskWaitingDetail(
            statusLabel = statusLabel,
            whoLabel = whoLabel,
            waiting = waiting,
            formatWithReason = { s, w, r -> stringResource(Res.string.activity_list_waiting_detail, s, w, r) },
            formatWithoutReason = { s, w -> stringResource(Res.string.activity_list_waiting_detail_no_reason, s, w) },
        )
    val accessibleName = stringResource(Res.string.activity_list_waiting_named, detail)
    RaftTooltip(text = detail, openOnLongPress = true) {
        if (pixelOnly) {
            Box(
                modifier =
                    Modifier
                        .testTag(ActivityTaskWaitingMarkTag)
                        .semantics {
                            contentDescription = accessibleName
                            role = Role.Image
                        },
                contentAlignment = Alignment.Center,
            ) {
                RaftTaskStatusPixel(status = status.raftTaskStatus())
            }
        } else {
            RaftTaskStatusBadge(
                status = status.raftTaskStatus(),
                text = stringResource(Res.string.activity_list_waiting_label),
                size = RaftTaskStatusBadgeSize.Sm,
                modifier =
                    Modifier
                        .testTag(ActivityTaskWaitingMarkTag)
                        .clearAndSetSemantics { contentDescription = accessibleName },
            )
        }
    }
}

/** raft-ui `InboxItemMarks` (draft, mention, muted / unread count), beside the excerpt's first line. */
@Composable
private fun ActivityConversationMarks(
    model: ActivityConversationRowModel,
    marksHeight: Dp,
    taskStatusPixelOnly: Boolean = false,
    timeContext: RaftTimeContext? = null,
) {
    val colors = RaftTheme.colors
    if (
        !model.hasDraft && !model.showMentionMarker && !model.muted && !model.showUnreadCount &&
        !model.showMarkedUnreadDot && model.taskStatusMark == null
    ) {
        return
    }
    Row(
        modifier = Modifier.height(marksHeight),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        model.taskStatusMark?.let { status ->
            val waiting = model.taskWaiting
            if (waiting != null) {
                ActivityTaskWaitingStatus(
                    status = status,
                    waiting = waiting,
                    pixelOnly = taskStatusPixelOnly,
                    timeContext = timeContext,
                )
            } else if (taskStatusPixelOnly) {
                // raft-design#1433 / #proj-mobile:#372 Option A (phone two-line preview): a task row's
                // status in the marks is only the 10dp colour pixel so line 1 keeps room for the
                // message; screen readers still read its localized label.
                val statusLabel = status.localizedLabel()
                Box(
                    modifier =
                        Modifier.semantics {
                            contentDescription = statusLabel
                            role = Role.Image
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    RaftTaskStatusPixel(status = status.raftTaskStatus())
                }
            } else {
                // Wide single-line `InboxItemLine`: keep the `sm` status badge before draft/unread.
                RaftTaskStatusBadge(
                    status = status.raftTaskStatus(),
                    text = status.localizedLabel(),
                    size = RaftTaskStatusBadgeSize.Sm,
                )
            }
        }
        if (model.hasDraft) {
            val draftDescription = stringResource(Res.string.activity_list_draft_description)
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
            RaftMentionMarker(contentDescription = stringResource(Res.string.activity_list_mentioned_you))
        }
        when {
            model.muted ->
                RaftLucideIcon(RaftLucideIconName.BellOff, Modifier.size(14.dp), color = raftArgbHex(colors.foregroundHint))
            model.showUnreadCount ->
                RaftUnreadCount(
                    model.unreadCount,
                    contentDescription =
                        stringResource(Res.string.activity_list_unread_count_description, model.unreadCount),
                )
            model.showMarkedUnreadDot ->
                RaftQuietUnreadDot(contentDescription = stringResource(Res.string.home_sidebar_marked_unread))
        }
    }
}

/** The count as raft-ui writes it (`99+` above 99); null when the row shows no count. */
internal fun ActivityConversationRowModel.unreadCountLabel(): String? =
    if (showUnreadCount) raftUnreadCountText(unreadCount) else null


/**
 * The excerpt with [marks] beside its first line only (web `InboxItemMarks` rides the first line):
 * a single-topology line-box exclusion layout primitive.
 *
 * Every line occupies an exact fixed vertical slot of height `slotHeight` (derived from
 * [lineHeight] and [style.lineHeight]), so an inline placeholder (sender face / draft icon), a mark,
 * or a CJK fallback glyph never inflates a line box. When [reserveLines] is `true` (default, e.g.
 * Drafts), total height is strictly `slotHeight * maxLines`; when `false` (Activity rows), total
 * height follows the laid-out line count (`slotHeight * actualLines`, in `1..maxLines`). Both line 1
 * and the remaining lines are measured once per pass with the same CJK-trimmed [TextStyle] and drawn
 * directly on one canvas with inline content placed at the measured placeholder rects, so line 2 uses
 * the full width while [marks] stay at the end of line 1.
 */
@Composable
internal fun ActivityExcerptBesideMarks(
    text: AnnotatedString,
    /** Non-null: what the excerpt reads as instead of its visible text (one node, all of it). */
    accessibilityText: String? = null,
    style: TextStyle,
    color: Color,
    inlineContent: Map<String, InlineTextContent>,
    lineHeight: Dp,
    /** Maximum excerpt lines; one: the excerpt ellipsizes on the marks' line. */
    maxLines: Int = ActivityConversationPreviewMaxLines,
    /** When true, always reserve [maxLines] slots; when false, height follows the laid-out line count. */
    reserveLines: Boolean = true,
    /** Optional test hook reporting the laid-out line split `(line1Text, restText)` after each measure. */
    onLineSplit: ((firstLine: String, rest: String?) -> Unit)? = null,
    marks: @Composable () -> Unit,
) {
    // Resolve ink and CJK line-box metrics ONCE on the full excerpt so line 1 and line 2+ never
    // disagree (e.g. CJK on line 1 with pure ASCII on line 2, or unread -> read transitions).
    val ink =
        when {
            color.isSpecified -> color
            style.color.isSpecified -> style.color
            else -> LocalRaftContentColor.current
        }
    val resolvedStyle = style.copy(color = ink).forCjkLineBox(text)
    val measurer = rememberTextMeasurer()
    val semanticText = remember(text, accessibilityText) { accessibilityText?.let(::AnnotatedString) ?: text }
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val clampedMaxLines = maxLines.coerceAtLeast(1)
        val gap = 8.dp.roundToPx()
        val slotHeight =
            maxOf(
                lineHeight.roundToPx(),
                if (resolvedStyle.lineHeight.isSp) resolvedStyle.lineHeight.roundToPx() else 0,
            )
        val marksPlaceables = subcompose("marks", marks).map { it.measure(Constraints()) }
        val marksWidth = marksPlaceables.sumOf { it.width }
        val plan =
            planActivityExcerptLayout(
                measurer = measurer,
                text = text,
                style = resolvedStyle,
                inlineContent = inlineContent,
                width = width,
                marksWidth = marksWidth,
                gap = gap,
                slotHeight = slotHeight,
                maxLines = clampedMaxLines,
                isRtl = layoutDirection == LayoutDirection.Rtl,
            )
        val actualLines =
            if (reserveLines) {
                clampedMaxLines
            } else if (plan.rest != null) {
                (1 + plan.rest.lineCount).coerceAtMost(clampedMaxLines)
            } else {
                1
            }
        val totalHeight = slotHeight * actualLines
        onLineSplit?.invoke(plan.firstLineText, plan.restText)
        val textCanvas =
            subcompose("text") {
                Box(
                    Modifier
                        .clearAndSetSemantics { this.text = semanticText }
                        .drawBehind { drawActivityExcerptPlan(plan, totalHeight, slotHeight) }
                )
            }.first().measure(Constraints.fixed(width, totalHeight))
        val inlinePlacements =
            plan.inlinePlacements.mapIndexed { index, placement ->
                val clipLeftInBox = (placement.clipLeft - placement.x).coerceAtLeast(0)
                val clipRightInBox = (placement.clipRight - placement.x).coerceAtMost(placement.width)
                val needsClip = clipLeftInBox > 0 || clipRightInBox < placement.width
                val placeable =
                    subcompose("inline-$index") {
                        Box(
                            Modifier
                                .clearAndSetSemantics {}
                                .then(
                                    if (needsClip) {
                                        Modifier.drawWithContent {
                                            clipRect(
                                                left = clipLeftInBox.toFloat(),
                                                top = -slotHeight.toFloat(),
                                                right = clipRightInBox.toFloat(),
                                                bottom = (placement.height + slotHeight).toFloat(),
                                            ) {
                                                this@drawWithContent.drawContent()
                                            }
                                        }
                                    } else {
                                        Modifier
                                    }
                                )
                        ) {
                            placement.span.content.children(placement.span.alternateText)
                        }
                    }.first().measure(Constraints(maxWidth = placement.width, maxHeight = placement.height))
                Triple(placeable, placement.x, placement.y)
            }
        layout(width, totalHeight) {
            textCanvas.place(0, 0)
            inlinePlacements.forEach { (placeable, x, y) ->
                placeable.place(x, y)
            }
            var xFromEnd = width
            marksPlaceables.forEach { marksPlaceable ->
                xFromEnd -= marksPlaceable.width
                marksPlaceable.placeRelative(xFromEnd, (slotHeight - marksPlaceable.height) / 2)
            }
        }
    }
}

private data class ActivityExcerptInlineSpan(
    val alternateText: String,
    val range: AnnotatedString.Range<Placeholder>,
    val content: InlineTextContent,
)

private data class ActivityExcerptInlinePlacement(
    val span: ActivityExcerptInlineSpan,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val clipLeft: Int,
    val clipRight: Int,
)

private data class ActivityExcerptLayoutPlan(
    val line0: TextLayoutResult?,
    val line0Left: Int,
    val line0Width: Int,
    val line0Top: Int,
    val clipLine0: Boolean,
    val rest: TextLayoutResult?,
    val restTop: Int,
    val inlinePlacements: List<ActivityExcerptInlinePlacement>,
    val firstLineText: String,
    val restText: String?,
)

/**
 * Normalizes line-0 end offset across Android (`StaticLayout.getLineEnd`, which includes the
 * terminating line break) and Skia on iOS/Desktop (`SkiaParagraph.getLineEnd`, which excludes it):
 * when [rawLineEnd] stops right before a line break that line 0 has not consumed yet, advances past
 * at most one line terminator (`\r\n`, `\n`, or `\r`) without eating a following blank line.
 */
internal fun advancePastSingleLineBreak(text: CharSequence, rawLineEnd: Int): Int {
    var split = rawLineEnd.coerceIn(0, text.length)
    if (split < text.length && (split == 0 || (text[split - 1] != '\n' && text[split - 1] != '\r'))) {
        if (text[split] == '\r') split++
        if (split < text.length && text[split] == '\n') split++
    }
    return split
}

private fun planActivityExcerptLayout(
    measurer: TextMeasurer,
    text: AnnotatedString,
    style: TextStyle,
    inlineContent: Map<String, InlineTextContent>,
    width: Int,
    marksWidth: Int,
    gap: Int,
    slotHeight: Int,
    maxLines: Int,
    isRtl: Boolean,
): ActivityExcerptLayoutPlan {
    val exclusion = if (marksWidth > 0) marksWidth + gap else 0
    val line0Width = (width - exclusion).coerceAtLeast(0)
    val line0Left = if (isRtl) (width - line0Width).coerceAtLeast(0) else 0
    val line0Constraints = Constraints(minWidth = if (isRtl) line0Width else 0, maxWidth = line0Width)
    val allSpans = text.inlinePlaceholderSpans(inlineContent)
    val placements = mutableListOf<ActivityExcerptInlinePlacement>()

    if (maxLines <= 1) {
        val line0 =
            if (line0Width > 0) {
                measurer.measure(
                    text = text,
                    style = style,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    placeholders = allSpans.map { it.range },
                    constraints = line0Constraints,
                )
            } else {
                null
            }
        val line0Top = if (line0 != null) (slotHeight - line0.size.height) / 2 else 0
        if (line0 != null) {
            collectInlinePlacements(line0, allSpans, line0Left, line0Top, line0Left, line0Left + line0Width, Int.MAX_VALUE, placements)
        }
        return ActivityExcerptLayoutPlan(
            line0 = line0,
            line0Left = line0Left,
            line0Width = line0Width,
            line0Top = line0Top,
            clipLine0 = marksWidth > 0,
            rest = null,
            restTop = slotHeight,
            inlinePlacements = placements,
            firstLineText = if (line0 != null) text.text else "",
            restText = null,
        )
    }

    val line0 =
        if (line0Width > 0) {
            measurer.measure(
                text = text,
                style = style,
                overflow = TextOverflow.Clip,
                maxLines = 1,
                placeholders = allSpans.map { it.range },
                constraints = line0Constraints,
            )
        } else {
            null
        }
    val line0Top = if (line0 != null) (slotHeight - line0.size.height) / 2 else 0
    val rawLine0End =
        if (line0 != null && line0.lineCount > 0) {
            line0.getLineEnd(0, visibleEnd = false).coerceIn(0, text.length)
        } else {
            0
        }
    val splitOffset = if (line0 != null) advancePastSingleLineBreak(text, rawLine0End) else 0
    if (line0 != null) {
        collectInlinePlacements(line0, allSpans, line0Left, line0Top, line0Left, line0Left + line0Width, splitOffset, placements)
    }
    val restAnnotated = if (splitOffset < text.length) text.subSequence(splitOffset, text.length) else null
    val restSpans = restAnnotated?.inlinePlaceholderSpans(inlineContent).orEmpty()
    val hasRestContent = restAnnotated != null && (restAnnotated.text.isNotBlank() || restSpans.isNotEmpty())
    val rest =
        if (hasRestContent && width > 0) {
            measurer.measure(
                text = restAnnotated,
                style = style,
                overflow = TextOverflow.Ellipsis,
                maxLines = maxLines - 1,
                placeholders = restSpans.map { it.range },
                constraints = Constraints(minWidth = if (isRtl) width else 0, maxWidth = width),
            )
        } else {
            null
        }
    val restFirstLineHeight =
        when {
            rest == null -> 0
            rest.lineCount <= 1 -> rest.size.height
            else -> (rest.getLineBottom(0) - rest.getLineTop(0)).roundToInt()
        }
    val restTop = slotHeight + (slotHeight - restFirstLineHeight) / 2
    if (rest != null) {
        collectInlinePlacements(rest, restSpans, 0, restTop, 0, width, Int.MAX_VALUE, placements)
    }
    return ActivityExcerptLayoutPlan(
        line0 = line0,
        line0Left = line0Left,
        line0Width = line0Width,
        line0Top = line0Top,
        clipLine0 = marksWidth > 0,
        rest = rest,
        restTop = restTop,
        inlinePlacements = placements,
        firstLineText = if (line0 != null) text.text.substring(0, splitOffset) else "",
        restText = if (rest != null) restAnnotated?.text else null,
    )
}

private fun collectInlinePlacements(
    result: TextLayoutResult,
    spans: List<ActivityExcerptInlineSpan>,
    originX: Int,
    originY: Int,
    clipLeft: Int,
    clipRight: Int,
    maxRangeStartExclusive: Int,
    out: MutableList<ActivityExcerptInlinePlacement>,
) {
    result.placeholderRects.forEachIndexed { index, rect ->
        if (rect == null || index >= spans.size) return@forEachIndexed
        val span = spans[index]
        if (span.range.start >= maxRangeStartExclusive) return@forEachIndexed
        val x = originX + rect.left.roundToInt()
        val width = rect.width.roundToInt().coerceAtLeast(0)
        val height = rect.height.roundToInt().coerceAtLeast(0)
        if (width <= 0 || height <= 0) return@forEachIndexed
        if (x + width <= clipLeft || x >= clipRight) return@forEachIndexed
        out +=
            ActivityExcerptInlinePlacement(
                span = span,
                x = x,
                y = originY + rect.top.roundToInt(),
                width = width,
                height = height,
                clipLeft = clipLeft,
                clipRight = clipRight,
            )
    }
}

private fun DrawScope.drawActivityExcerptPlan(plan: ActivityExcerptLayoutPlan, totalHeight: Int, slotHeight: Int) {
    val line0 = plan.line0
    if (line0 != null && plan.line0Width > 0) {
        val topLeft = Offset(plan.line0Left.toFloat(), plan.line0Top.toFloat())
        if (plan.clipLine0) {
            clipRect(
                left = plan.line0Left.toFloat(),
                top = -slotHeight.toFloat(),
                right = (plan.line0Left + plan.line0Width).toFloat(),
                bottom = (totalHeight + slotHeight).toFloat(),
            ) {
                drawText(textLayoutResult = line0, topLeft = topLeft)
            }
        } else {
            drawText(textLayoutResult = line0, topLeft = topLeft)
        }
    }
    val rest = plan.rest
    if (rest != null) {
        drawText(textLayoutResult = rest, topLeft = Offset(0f, plan.restTop.toFloat()))
    }
}

/** The [inlineContent] placeholders [BasicText] lays [this] out with (its `appendInlineContent` ranges). */
private fun AnnotatedString.inlinePlaceholderSpans(
    inlineContent: Map<String, InlineTextContent>,
): List<ActivityExcerptInlineSpan> =
    if (inlineContent.isEmpty()) {
        emptyList()
    } else {
        getStringAnnotations(InlineContentTag, 0, length).mapNotNull { annotation ->
            val start = annotation.start.coerceIn(0, length)
            val end = annotation.end.coerceIn(start, length)
            val content = inlineContent[annotation.item] ?: return@mapNotNull null
            ActivityExcerptInlineSpan(
                alternateText = text.substring(start, end),
                range = AnnotatedString.Range(content.placeholder, start, end),
                content = content,
            )
        }
    }

/** `androidx.compose.foundation.text.INLINE_CONTENT_TAG`, the annotation `appendInlineContent` writes. */
private const val InlineContentTag = "androidx.compose.foundation.text.inlineContent"
