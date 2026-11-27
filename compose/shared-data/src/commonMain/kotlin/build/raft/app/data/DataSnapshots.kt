package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.data.generated.MarkUnreadOutcomeDto
import build.raft.app.model.*
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.resources.StringDesc
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray

@Serializable
data class InboxItem(
    val key: String,
    val kind: InboxItemKind,
    val channelId: ChannelId,
    val channelName: String,
    val channelKind: ConversationKind? = null,
    /**
     * The wire `channelType` (`channel` | `private` | `joint` | `dm`, slock web inboxStore.ts).
     * [channelKind] folds `private` into Channel; this keeps it for the row's channel icon.
     */
    val channelType: String? = null,
    val unreadCount: Int = 0,
    val hasMention: Boolean = false,
    val hasDraft: Boolean = false,
    val firstUnreadMessageId: MessageId? = null,
    val lastMessageId: MessageId? = null,
    val lastMessagePreview: String? = null,
    val lastMessageAt: String? = null,
    val lastMessageSenderName: String? = null,
    val lastMessageSenderId: String? = null,
    val lastMessageSenderKind: MemberKind? = null,
    val lastMessageSenderAvatarWireValue: String? = null,
    val threadChannelId: ChannelId? = null,
    val parentChannelId: ChannelId? = null,
    val parentChannelName: String? = null,
    val parentChannelKind: ConversationKind? = null,
    /** The wire `parentChannelType`, unfolded like [channelType]. */
    val parentChannelType: String? = null,
    val parentMessageId: MessageId? = null,
    val parentMessagePreview: String? = null,
    val parentMessageSenderId: String? = null,
    val parentMessageSenderKind: MemberKind? = null,
    /** Followed-thread rows: when the parent message was posted (its place in the parent channel). */
    val parentMessageCreatedAt: String? = null,
    /**
     * Followed-thread rows (server #11517): the parent message / a reply is inside the plan's
     * history window. Null from an older server (or a row that isn't a followed thread).
     */
    val parentInHistory: Boolean? = null,
    val repliesInHistory: Boolean? = null,
    val latestActivityMessageId: MessageId? = null,
    val latestActivityPreview: String? = null,
    val latestActivityAt: String? = null,
    val latestActivitySenderName: String? = null,
    val latestActivitySenderId: String? = null,
    val latestActivitySenderKind: MemberKind? = null,
    val latestActivitySenderAvatarWireValue: String? = null,
    /**
     * RFC 077: the assistant that sent this row's latest message for its sender — wire
     * `latestDelegatedBy` on Activity rows, `delegatedBy` on search hits. Previews show it as
     * text next to the sender ("Alice (sent by Friday)"), never as a face.
     */
    val latestDelegatedBy: MessageDelegatedBy? = null,
    /**
     * RFC 077 PR 2b, wire `latestRetracted` / `parentRetracted`: the latest message (thread:
     * `latestActivityMessageId`, else `lastMessageId`) / the thread's parent was retracted.
     */
    val latestRetracted: Boolean = false,
    val parentRetracted: Boolean = false,
    val replyCount: Int = 0,
    /** Bounded server-derived latest replies; never a second message authority. */
    val latestReplies: List<ReplyPreview> = emptyList(),
    val participantIds: List<MemberId> = emptyList(),
    val taskNumber: Int? = null,
    val taskStatus: TaskStatus? = null,
    val taskClaimedByName: String? = null,
    /**
     * Server-owned follow state for thread Activity rows. Older payloads that
     * predate this projection remain unknown; only an explicit server `false`
     * is Unfollowed. We deliberately do not model a separate history source
     * on Mobile.
     */
    val isFollowing: Boolean? = null,
    val mentionActionId: String? = null,
    val pendingMentionActions: List<PendingMentionAction> = emptyList(),
    /**
     * Authority activity frontier for this row's own scope: wire
     * `readState.latestActivity.seq` (canonical decimal string) when
     * `readState.kind == "present"`, else null. Not the display
     * `latestActivitySeq`, which keeps a zero-reply parent fallback in another
     * seq domain. Used as the local Done/read overlay marker.
     */
    val readStateLatestActivitySeq: String? = null,
    /**
     * Wire `readState.readStateVersion` / `readState.maxReadSeq` when `readState.kind == "present"`,
     * else null: the read state this answer served for the row. Only diagnostics read them
     * (`activity:list_row_lowered` / `activity:list_row_corrected`, web `reportListRowsLowered`).
     */
    val readStateVersion: Long? = null,
    val readStateMaxReadSeq: String? = null,
    /**
     * Wire `readState.kind == "absent"`: the server holds no read state for the conversation (never read, or
     * its cursor row deleted when access was lost). Activity forgets the read state it held for it.
     */
    val readStateAbsent: Boolean = false,
    /**
     * Wire `readState.markedUnread` (botiverse/slock #11795): the person marked this conversation
     * unread and nothing countable is unread. With [unreadCount] 0 the row shows unread with a dot
     * and no number (and the Unread filter lists it); counts never include it.
     */
    val markedUnread: Boolean = false,
    /**
     * Wire `latestActivitySeq`: the display content frontier (decimal string).
     * Realtime reply patches advance a row only on a strictly newer seq, like
     * web `receiveThreadReply`. Null on servers that predate it.
     */
    val latestActivitySeq: String? = null,
    /**
     * Wire `doneFrontierSeq`, the storage-space frontier a Done must name
     * (`throughActivitySeq`). Only meaningful with [doneFrontierSeqPresent]:
     * a server that predates the field omits it entirely (legacy Done), while
     * a present-but-null/invalid value fails closed (refresh, no write).
     */
    val doneFrontierSeq: String? = null,
    /** Whether the wire row carried the `doneFrontierSeq` key at all. */
    val doneFrontierSeqPresent: Boolean = false,
    /** Wire `firstMentionMessageId` (unread-mention anchor), null when absent. */
    val firstMentionMessageId: MessageId? = null,
    /** Wire `unfollowedAt` for inline unfollowed thread rows (`isFollowing:false`). */
    val unfollowedAt: String? = null,
    /** [InboxItemKind.Object] rows only: the object's row (workspace Artifacts). */
    val activityObject: ActivityObjectRow? = null,
    /**
     * Task v2: this [InboxItemKind.Thread] row is a task's own conversation (wire `kind: "task"`,
     * slock `TaskConversationInboxItem`): [threadChannelId] is the reader's LOCAL conversation,
     * `parentChannel*` the task's channel, and there is no parent message.
     */
    val taskConversation: InboxTaskConversation? = null,
    /**
     * `task_waiting_on_you_v0` (botiverse/slock #16759): optional `waiting` metadata served on
     * `kind: "task"` rows when the task currently waits on the signed-in user. Absent/null when
     * the flag is off, the task does not wait on the viewer, or the payload is malformed.
     */
    val taskWaiting: TaskWaitingOnYou? = null,
    /**
     * Explicit message to focus when opening a single-message row (Saved entry or Search hit).
     * Null on Activity conversation summary rows, whose [latestActivityMessageId] is the thread's
     * newest row of any kind (including unreadable system/agent lines) and must never be focused
     * on open (botiverse/slock #16908).
     */
    val explicitFocusMessageId: MessageId? = null,
)

/** How a task ended up waiting on the viewer (wire `waiting.via`). */
@kotlinx.serialization.Serializable
enum class TaskWaitingVia {
    Claim,
    Assign,
    System,
}

/** Who assigned the task to the viewer (wire `waiting.assignedBy`, only on `via = "assign"`). */
@kotlinx.serialization.Serializable
data class TaskWaitingAssignedBy(
    val kind: MemberKind,
    val id: String,
    val name: String? = null,
)

/**
 * Waiting-on-you metadata carried by a `kind: "task"` inbox row (`task_waiting_on_you_v0`,
 * botiverse/slock `packages/shared/src/taskWaitingOnYou.ts`).
 */
@kotlinx.serialization.Serializable
data class TaskWaitingOnYou(
    val since: String,
    val via: TaskWaitingVia,
    val assignedBy: TaskWaitingAssignedBy? = null,
    val reason: String? = null,
    val reasonTruncated: Boolean = false,
)

/** The task a task-conversation Activity row belongs to (wire `taskId` / `taskTitle`). */
@kotlinx.serialization.Serializable
data class InboxTaskConversation(
    val taskId: TaskId?,
    val taskTitle: String?,
)

/** A task v2 conversation row (web `isTaskConversationItem`). */
val InboxItem.isTaskConversation: Boolean
    get() = taskConversation != null

/**
 * Whether [task] currently waits on [currentUserId] (web `liveTaskWaitsOn`, slock #16759):
 * active status (`todo` | `in_progress` | `in_review`) and assigned to this human user.
 */
@RaftCoreInternalApi fun liveTaskWaitsOn(task: TaskSummary, currentUserId: String?): Boolean {
    if (currentUserId.isNullOrBlank()) return false
    val active =
        task.status == TaskStatus.Todo ||
            task.status == TaskStatus.InProgress ||
            task.status == TaskStatus.InReview
    return active && task.assignee?.kind == MemberKind.Human && task.assignee.id.value == currentUserId
}

/**
 * Effective waiting metadata to display on an Activity task row (web `shownTaskWaiting`,
 * slock #16759): keeps [rowWaiting] unless [liveTask] is known and no longer waits on
 * [currentUserId], in which case it clears immediately in place without waiting for a refetch.
 */
@RaftCoreInternalApi fun shownTaskWaiting(
    rowWaiting: TaskWaitingOnYou?,
    liveTask: TaskSummary?,
    currentUserId: String?,
): TaskWaitingOnYou? {
    if (rowWaiting == null) return null
    if (liveTask == null) return rowWaiting
    if (currentUserId.isNullOrBlank()) {
        val active =
            liveTask.status == TaskStatus.Todo ||
                liveTask.status == TaskStatus.InProgress ||
                liveTask.status == TaskStatus.InReview
        return if (active && liveTask.assignee?.kind == MemberKind.Human) rowWaiting else null
    }
    if (!liveTaskWaitsOn(liveTask, currentUserId)) return null
    return rowWaiting
}

/**
 * Carries [served]'s `taskWaiting` onto a kept task-conversation row (web `withServedTaskWaiting`,
 * slock #16759 / #17085): `waiting` is task state, not row position, so a kept row still takes the
 * freshly served `waiting` (including clearing it to null).
 */
@RaftCoreInternalApi fun InboxItem.withServedTaskWaiting(served: InboxItem): InboxItem =
    if (isTaskConversation && served.isTaskConversation && taskWaiting != served.taskWaiting) {
        copy(taskWaiting = served.taskWaiting)
    } else {
        this
    }

/**
 * The frontier a Done request names, chosen exactly like web
 * `inboxStore.markDone` (botiverse/slock 1bb70cbeb, inboxStore.ts:1417-1470).
 */
@RaftCoreInternalApi sealed interface ActivityDoneRequestFrontier {
    /** The row predates `doneFrontierSeq`: omit the seq (server-side latest). */
    data object Legacy : ActivityDoneRequestFrontier

    /** `{throughActivitySeq, frontierSpace: "storage"}`. */
    data class Storage(val throughActivitySeq: String) : ActivityDoneRequestFrontier

    /** Present but unusable: an upgraded-server anomaly; refresh and do not write. */
    data object Invalid : ActivityDoneRequestFrontier
}

@RaftCoreInternalApi fun InboxItem.activityDoneRequestFrontier(): ActivityDoneRequestFrontier {
    if (!doneFrontierSeqPresent) return ActivityDoneRequestFrontier.Legacy
    val seq = doneFrontierSeq?.takeIf { isCanonicalActivitySeq(it) && it != "0" }
        ?: return ActivityDoneRequestFrontier.Invalid
    return ActivityDoneRequestFrontier.Storage(seq)
}

@RaftCoreInternalApi fun ActivityDoneRequestFrontier.toInboxDoneFrontier(): InboxDoneFrontier? =
    when (this) {
        ActivityDoneRequestFrontier.Legacy -> InboxDoneFrontier.Legacy
        is ActivityDoneRequestFrontier.Storage -> InboxDoneFrontier.Storage(throughActivitySeq)
        ActivityDoneRequestFrontier.Invalid -> null
    }

/** True only for an explicitly server-projected, unfollowed thread row. */
/**
 * The row reads as unread: a count, or marked unread with no count (botiverse/slock #11795). The
 * read toggle offers Mark as read, and opening it sends the read that clears the mark.
 */
@RaftCoreInternalApi val InboxItem.showsUnread: Boolean
    get() = unreadCount > 0 || markedUnread

/** Marked unread with nothing counted: the quiet dot instead of a number. */
@RaftCoreInternalApi val InboxItem.showsMarkedUnreadDot: Boolean
    get() = markedUnread && unreadCount <= 0

@RaftCoreInternalApi val InboxItem.isUnfollowedActivityThread: Boolean
    get() = kind == InboxItemKind.Thread && isFollowing == false

/** Legacy payloads omit the optional field; keep their existing followed-row controls. */
@RaftCoreInternalApi val InboxItem.isFollowedActivityThread: Boolean
    get() = kind == InboxItemKind.Thread && isFollowing != false

/**
 * Activity attention is derived from the follow-state projection.  A stale or
 * over-eager server/client payload must not make an Unfollowed row re-enter
 * ordinary unread/mention attention.
 */
@RaftCoreInternalApi fun InboxItem.withActivityFollowStateProjection(): InboxItem =
    if (isUnfollowedActivityThread) {
        copy(
            unreadCount = 0,
            firstUnreadMessageId = null,
            hasMention = false
        )
    } else {
        this
    }

/**
 * The Activity All projection may retain unfollowed rows, while attention
 * filters must never surface them. The Tasks scope (`scope=tasks`, slock #16990)
 * keeps only task conversation rows (both followed and unfollowed) so servers
 * that predate `scope=tasks` and return unscoped rows still display only tasks.
 * Keep this rule next to the model so every repository/fixture consumer applies
 * the same membership contract.
 */
@RaftCoreInternalApi fun InboxSnapshot.projectActivityFilter(filter: InboxFilter): InboxSnapshot {
    // The unread aggregate is the server's (it already zeroes unfollowed rows);
    // only row membership/attention is guarded here.
    val normalizedItems = items.map { item -> item.withActivityFollowStateProjection() }
    val visibleItems =
        when {
            filter == InboxFilter.Tasks -> normalizedItems.filter { item -> item.isTaskConversation }
            filter.listsUnfollowedActivityThreads -> normalizedItems
            else -> normalizedItems.filterNot { item -> item.isUnfollowedActivityThread }
        }
    val removedConversationCount =
        if (filter == InboxFilter.Tasks) {
            normalizedItems.count { item -> !item.isTaskConversation && item.kind != InboxItemKind.Object }
        } else {
            normalizedItems.size - visibleItems.size
        }
    val removedConversationUnread =
        if (filter == InboxFilter.Tasks) {
            normalizedItems
                .filter { item -> !item.isTaskConversation && item.kind != InboxItemKind.Object }
                .sumOf { item -> item.unreadCount.coerceAtLeast(0) }
        } else {
            0
        }
    val nextObjectCounts = if (filter == InboxFilter.Tasks) null else objectCounts
    if (
        removedConversationCount == 0 &&
            visibleItems.size == normalizedItems.size &&
            normalizedItems == items &&
            nextObjectCounts == objectCounts
    ) {
        return this
    }
    return copy(
        items = visibleItems,
        totalCount = (totalCount - removedConversationCount).coerceAtLeast(0),
        totalUnreadCount = (totalUnreadCount - removedConversationUnread).coerceAtLeast(0),
        objectCounts = nextObjectCounts,
    )
}

/**
 * Applies the local, reversible projection for a Follow/Unfollow mutation.
 * The network mutation remains authoritative; this helper only makes the
 * visible row respond immediately while preserving the server aggregate and
 * the All-vs-attention-filter membership contract.
 */
@RaftCoreInternalApi fun InboxSnapshot.projectActivityFollowMutation(
    itemKey: String,
    following: Boolean,
    filter: InboxFilter,
    retainedReadRowKeys: Set<String> = emptySet(),
): InboxSnapshot {
    val previousItem = items.firstOrNull { item -> item.key == itemKey }
    val mutated = items.map { item ->
        if (item.key != itemKey) {
            item
        } else {
            item.copy(
                isFollowing = following,
                unreadCount = 0,
                firstUnreadMessageId = null,
                hasMention = false
            )
        }
    }
    val projected =
        copy(
            items = mutated,
            totalUnreadCount =
                (totalUnreadCount - (previousItem?.unreadCount ?: 0)).coerceAtLeast(0)
        ).projectActivityFilter(filter)
    if (filter.listsUnfollowedActivityThreads) return projected
    val targetPresent = projected.items.any { it.key == itemKey }
    if (!targetPresent) return projected
    val alreadyLeftCount =
        !filter.keepsReadActivityRows() &&
            previousItem != null &&
            previousItem.key in retainedReadRowKeys &&
            !previousItem.showsUnread
    return projected.copy(
        items = projected.items.filterNot { it.key == itemKey },
        totalCount = if (alreadyLeftCount) projected.totalCount else (projected.totalCount - 1).coerceAtLeast(0)
    )
}

@RaftCoreInternalApi fun InboxItem.hasUnopenableInternalJointParentSource(): Boolean =
    kind == InboxItemKind.Thread &&
        parentChannelKind == ConversationKind.Joint &&
        isInternalJointStorageIdentity(parentChannelId?.value)

@Serializable
sealed interface HomeConversationRow {
    val threadId: ThreadId
    val channelId: ChannelId?
    val channelType: String?
    val thread: ThreadConversation?
    val kind: ConversationKind
    val name: String
    val subtitle: String?
    val directMessagePeerType: String?
    val directMessagePeerAvatarWireValue: String?
    val unreadCount: Int
    val unreadCountKnown: Boolean
    val mentionCount: Int
    val mentionCountKnown: Boolean
    val lastMessageId: MessageId?
    val lastMessageSeq: Long?
    val firstUnreadMessageId: MessageId?
    val readStateMaxReadSeq: Long?
    val readStateV1Hydrate: ReadStateV1HydrateCarrier?
    val lastMessagePreview: String?
    val lastMessageAt: String?
    val lastMessageSenderName: String?
    val presenceMemberId: MemberId?
    val hasDraft: Boolean
    val hasDraftKnown: Boolean
    val joined: Boolean
    val archived: Boolean
    val memberCount: Int?
    val jointChannelId: String?
    val jointRole: String?
    val jointPeerServerId: ServerId?
    val jointPeerServerName: String?
    val jointPeerServerSlug: String?
    val jointPeerStatus: String?
    /** Connected servers of a joint channel (`jointServers[]`, slock #15809) with each server's logo. */
    val jointServers: List<MemberServerRef>
        get() = emptyList()
    val jointBillingLocked: Boolean?
    val jointOverLimitGraceEndsAt: String?
    val externalFreezeAt: String?
        get() = null
    /** task #396: external bridge provider (e.g. "slack"); only channels carry one. */
    val bridgeProvider: String?
        get() = null
    /** A Slack channel's stopped sync ([RaftSlackSyncProblem], from `channel.bridge`); only channels carry one. */
    val slackSyncProblem: RaftSlackSyncProblem?
        get() = null
    /** Channel `guestVisible` (see [HomeChannelWireDto.guestVisible]); only channels carry one. */
    val guestVisible: Boolean
        get() = false
    /** Channel `hasExternalRelation` (see [HomeChannelWireDto.hasExternalRelation]); only channels carry one. */
    val hasExternalRelation: Boolean
        get() = false
    /** Whether this channel or DM has a pinned announcement (slock #17116). */
    val hasAnnouncement: Boolean
        get() = false
    /** Agent Lounge v0: the agent this channel is the Lounge of; only channels carry one. */
    val loungeAgentId: String?
        get() = null
    val activityNotificationPreference: ChannelActivityNotificationPreference
    val permissions: ConversationPermissions
    /**
     * Server-resolved channel-level capabilities (see [ConversationSummary.channelCapabilities]).
     * `null` on DMs and on payloads that pre-date the field.
     */
    val channelCapabilities: ChannelCapabilities?

    val activityMuted: Boolean
        get() = activityNotificationPreference.activityMuted
    val muteFromSeq: Long?
        get() = activityNotificationPreference.muteFromSeq
    val activityMuteSupported: Boolean
        get() = activityNotificationPreference.activityMuteSupported
}

@Serializable
data class HomeChannelRow(
    override val threadId: ThreadId,
    override val channelId: ChannelId,
    override val channelType: String? = null,
    override val thread: ThreadConversation? = null,
    override val kind: ConversationKind = ConversationKind.Channel,
    override val name: String,
    override val subtitle: String? = null,
    override val directMessagePeerType: String? = null,
    override val directMessagePeerAvatarWireValue: String? = null,
    override val unreadCount: Int = 0,
    override val unreadCountKnown: Boolean = false,
    override val mentionCount: Int = 0,
    override val mentionCountKnown: Boolean = false,
    override val lastMessageId: MessageId? = null,
    override val lastMessageSeq: Long? = null,
    override val firstUnreadMessageId: MessageId? = null,
    override val readStateMaxReadSeq: Long? = null,
    @Transient override val readStateV1Hydrate: ReadStateV1HydrateCarrier? = null,
    override val lastMessagePreview: String? = null,
    override val lastMessageAt: String? = null,
    override val lastMessageSenderName: String? = null,
    override val presenceMemberId: MemberId? = null,
    override val hasDraft: Boolean = false,
    override val hasDraftKnown: Boolean = false,
    override val joined: Boolean = true,
    override val archived: Boolean = false,
    override val memberCount: Int? = null,
    override val jointChannelId: String? = null,
    override val jointRole: String? = null,
    override val jointPeerServerId: ServerId? = null,
    override val jointPeerServerName: String? = null,
    override val jointPeerServerSlug: String? = null,
    override val jointPeerStatus: String? = null,
    override val jointServers: List<MemberServerRef> = emptyList(),
    override val jointBillingLocked: Boolean? = null,
    override val jointOverLimitGraceEndsAt: String? = null,
    override val externalFreezeAt: String? = null,
    override val bridgeProvider: String? = null,
    override val slackSyncProblem: RaftSlackSyncProblem? = null,
    override val guestVisible: Boolean = false,
    override val hasExternalRelation: Boolean = false,
    override val hasAnnouncement: Boolean = false,
    override val activityNotificationPreference: ChannelActivityNotificationPreference =
        ChannelActivityNotificationPreference.Unsupported,
    override val permissions: ConversationPermissions = ConversationPermissions(),
    override val channelCapabilities: ChannelCapabilities? = null,
    /** Agent Lounge v0 (see [HomeConversationRow.loungeAgentId]). */
    override val loungeAgentId: String? = null
) : HomeConversationRow

@Serializable
data class HomeDirectMessageRow(
    override val threadId: ThreadId,
    override val channelId: ChannelId,
    override val channelType: String? = null,
    override val thread: ThreadConversation? = null,
    override val kind: ConversationKind = ConversationKind.DirectMessage,
    override val name: String,
    override val subtitle: String? = null,
    override val directMessagePeerType: String? = null,
    override val directMessagePeerAvatarWireValue: String? = null,
    override val unreadCount: Int = 0,
    override val unreadCountKnown: Boolean = false,
    override val mentionCount: Int = 0,
    override val mentionCountKnown: Boolean = false,
    override val lastMessageId: MessageId? = null,
    override val lastMessageSeq: Long? = null,
    override val firstUnreadMessageId: MessageId? = null,
    override val readStateMaxReadSeq: Long? = null,
    @Transient override val readStateV1Hydrate: ReadStateV1HydrateCarrier? = null,
    override val lastMessagePreview: String? = null,
    override val lastMessageAt: String? = null,
    override val lastMessageSenderName: String? = null,
    override val presenceMemberId: MemberId? = null,
    override val hasDraft: Boolean = false,
    override val hasDraftKnown: Boolean = false,
    override val joined: Boolean = true,
    override val archived: Boolean = false,
    override val memberCount: Int? = null,
    override val jointChannelId: String? = null,
    override val jointRole: String? = null,
    override val jointPeerServerId: ServerId? = null,
    override val jointPeerServerName: String? = null,
    override val jointPeerServerSlug: String? = null,
    override val jointPeerStatus: String? = null,
    override val jointBillingLocked: Boolean? = null,
    override val jointOverLimitGraceEndsAt: String? = null,
    override val externalFreezeAt: String? = null,
    override val hasAnnouncement: Boolean = false,
    override val activityNotificationPreference: ChannelActivityNotificationPreference =
        ChannelActivityNotificationPreference.Unsupported,
    override val permissions: ConversationPermissions = ConversationPermissions(),
    override val channelCapabilities: ChannelCapabilities? = null,
    /**
     * Agent Lounge v0: on an agent DM row (`GET /channels/dm` `lounge`), that agent's Lounge, present
     * only once it exists. Its unread comes from the unread summary by the Lounge's channel id.
     */
    val lounge: AgentLoungeSummary? = null
) : HomeConversationRow

fun HomeConversationRow.toConversationSummary(): ConversationSummary =
    ConversationSummary(
        threadId = threadId,
        channelId = channelId,
        channelType = channelType,
        thread = thread,
        kind = kind,
        name = name,
        subtitle = subtitle,
        directMessagePeerType = directMessagePeerType,
        directMessagePeerAvatarWireValue = directMessagePeerAvatarWireValue,
        unreadCount = unreadCount,
        unreadCountKnown = unreadCountKnown,
        mentionCount = mentionCount,
        mentionCountKnown = mentionCountKnown,
        lastMessageId = lastMessageId,
        lastMessageSeq = lastMessageSeq,
        firstUnreadMessageId = firstUnreadMessageId,
        readStateMaxReadSeq = readStateMaxReadSeq,
        lastMessagePreview = lastMessagePreview,
        lastMessageAt = lastMessageAt,
        lastMessageSenderName = lastMessageSenderName,
        presenceMemberId = presenceMemberId,
        hasDraft = hasDraft,
        hasDraftKnown = hasDraftKnown,
        joined = joined,
        archived = archived,
        memberCount = memberCount,
        jointChannelId = jointChannelId,
        jointRole = jointRole,
        jointPeerServerId = jointPeerServerId,
        jointPeerServerName = jointPeerServerName,
        jointPeerServerSlug = jointPeerServerSlug,
        jointPeerStatus = jointPeerStatus,
        jointServers = jointServers,
        jointBillingLocked = jointBillingLocked,
        jointOverLimitGraceEndsAt = jointOverLimitGraceEndsAt,
        externalFreezeAt = externalFreezeAt,
        bridgeProvider = bridgeProvider,
        slackSyncProblem = slackSyncProblem,
        guestVisible = guestVisible,
        hasExternalRelation = hasExternalRelation,
        hasAnnouncement = hasAnnouncement,
        activityNotificationPreference = activityNotificationPreference,
        permissions = permissions,
        channelCapabilities = channelCapabilities,
        loungeAgentId = loungeAgentId
    )

fun ConversationSummary.toHomeChannelRow(): HomeChannelRow =
    HomeChannelRow(
        threadId = threadId,
        channelId = channelId ?: ChannelId(threadId.value),
        channelType = channelType,
        thread = thread,
        kind = if (kind == ConversationKind.Joint) ConversationKind.Joint else ConversationKind.Channel,
        name = name,
        subtitle = subtitle,
        directMessagePeerType = directMessagePeerType,
        directMessagePeerAvatarWireValue = directMessagePeerAvatarWireValue,
        unreadCount = unreadCount,
        unreadCountKnown = unreadCountKnown,
        mentionCount = mentionCount,
        mentionCountKnown = mentionCountKnown,
        lastMessageId = lastMessageId,
        lastMessageSeq = lastMessageSeq,
        firstUnreadMessageId = firstUnreadMessageId,
        readStateMaxReadSeq = readStateMaxReadSeq,
        lastMessagePreview = lastMessagePreview,
        lastMessageAt = lastMessageAt,
        lastMessageSenderName = lastMessageSenderName,
        presenceMemberId = presenceMemberId,
        hasDraft = hasDraft,
        hasDraftKnown = hasDraftKnown,
        joined = joined,
        archived = archived,
        memberCount = memberCount,
        jointChannelId = jointChannelId,
        jointRole = jointRole,
        jointPeerServerId = jointPeerServerId,
        jointPeerServerName = jointPeerServerName,
        jointPeerServerSlug = jointPeerServerSlug,
        jointPeerStatus = jointPeerStatus,
        jointServers = jointServers,
        jointBillingLocked = jointBillingLocked,
        jointOverLimitGraceEndsAt = jointOverLimitGraceEndsAt,
        externalFreezeAt = externalFreezeAt,
        bridgeProvider = bridgeProvider,
        slackSyncProblem = slackSyncProblem,
        guestVisible = guestVisible,
        hasExternalRelation = hasExternalRelation,
        hasAnnouncement = hasAnnouncement,
        activityNotificationPreference = activityNotificationPreference,
        permissions = permissions,
        channelCapabilities = channelCapabilities,
        loungeAgentId = loungeAgentId
    )

fun ConversationSummary.toHomeDirectMessageRow(): HomeDirectMessageRow =
    HomeDirectMessageRow(
        threadId = threadId,
        channelId = channelId ?: ChannelId(threadId.value),
        channelType = channelType,
        thread = thread,
        kind = ConversationKind.DirectMessage,
        name = name,
        subtitle = subtitle,
        directMessagePeerType = directMessagePeerType,
        directMessagePeerAvatarWireValue = directMessagePeerAvatarWireValue,
        unreadCount = unreadCount,
        unreadCountKnown = unreadCountKnown,
        mentionCount = mentionCount,
        mentionCountKnown = mentionCountKnown,
        lastMessageId = lastMessageId,
        lastMessageSeq = lastMessageSeq,
        firstUnreadMessageId = firstUnreadMessageId,
        readStateMaxReadSeq = readStateMaxReadSeq,
        lastMessagePreview = lastMessagePreview,
        lastMessageAt = lastMessageAt,
        lastMessageSenderName = lastMessageSenderName,
        presenceMemberId = presenceMemberId,
        hasDraft = hasDraft,
        hasDraftKnown = hasDraftKnown,
        joined = joined,
        archived = archived,
        memberCount = memberCount,
        hasAnnouncement = hasAnnouncement,
        activityNotificationPreference = activityNotificationPreference,
        permissions = permissions,
        channelCapabilities = channelCapabilities
    )

@Serializable
data class HomeSnapshot(
    val server: ServerSummary,
    val currentMember: MemberProfile,
    val channels: List<HomeChannelRow>,
    val directMessages: List<HomeDirectMessageRow>,
    val unreadTotal: Int,
    /**
     * #81: explicit source provenance for [unreadTotal]. Matches
     * `HomeParser.kt:97-103` derivation:
     * - `SERVER_UNREAD_SUMMARY` when `payload.serverUnreadSummary` had a
     *   non-null entry for the active server.
     * - `CHANNEL_DM_FALLBACK` when the server summary was missing for the
     *   active server and the count was derived from the channel + DM
     *   unread sum.
     * - `UNKNOWN` only for synthetic / preview snapshots that bypass the
     *   parser.
     *
     * Default is `UNKNOWN` so existing call sites that don't set it (test
     * fixtures, preview builders) keep compiling.
     */
    val unreadSource: AppBadgeSource = AppBadgeSource.UNKNOWN,
    /**
     * Cross-server unread counts from `/servers/unread-summary`, keyed by
     * server id. The selected server still exposes its projected count through
     * [server] / [unreadTotal]; this map is for surfaces such as the Home
     * server switcher that need other servers' counts without route payloads.
     */
    val serverUnreadCounts: Map<String, Int> = emptyMap(),
    /**
     * Known per-server Activity unread counts from the optional
     * `activityUnreadCount` summary field. Map membership is the knowledge
     * bit: a present zero is authoritative, while a missing key is unknown.
     * Legacy [serverUnreadCounts] must never be substituted into this map.
     */
    val serverActivityUnreadCounts: Map<String, Long> = emptyMap(),
    /** Notification delivery preference carried beside, never folded into, Activity count authority. */
    val serverPushMutedByServer: Map<String, Boolean> = emptyMap(),
    /** False when the server-summary request failed or its wire shape was rejected. */
    val serverUnreadSummaryAvailable: Boolean = true,
    /**
     * task #831: false when this snapshot's unread projection came from
     * unavailable auxiliary endpoints (error/timeout/hung/contract-mismatch),
     * so [unreadTotal] / [serverUnreadCounts] are an honest empty rather than an
     * authoritative zero. Consumers must keep the prior unread badges (mark them
     * stale) instead of clobbering them to read. Default true so existing
     * fixtures / preview builders keep compiling.
     */
    val unreadEnrichmentAvailable: Boolean = true,
    /**
     * #211: per-server sidebar order snapshot, including pinned conversation
     * ids, pinned order, and pinned sort mode. `null` means the sidebar
     * order has not been loaded for this snapshot yet; the visual layer
     * falls back to "no pinned items" until a non-null value arrives.
     */
    val sidebarOrder: SettingsServerSidebarOrderSnapshot? = null,
    /**
     * Followed threads with unread replies, keyed by thread channel id value (`channels/unread?summary=1`
     * entries that carry `parentChannelId`). Folded per parent channel into the Home row's
     * `[thread] N` segment and thread @ mark; kept current by `thread:updated` and read-state events.
     */
    // String keys: JSON map keys must be primitive, and HomeSnapshot is persisted.
    val followedThreadUnread: Map<String, HomeFollowedThreadUnread> = emptyMap(),
    /**
     * Listed Lounges for agents without a DM row (`GET /channels/dm?include=lounges` `lounges[]`,
     * `#proj-chat:#44`). Empty on older servers.
     */
    val standaloneLounges: List<HomeStandaloneLoungeRow> = emptyList(),
    /**
     * Channels and DMs marked unread with nothing countable (botiverse/slock #11795, the unread
     * summary's `readState.markedUnread`), by channel id value: the row shows unread with a dot and
     * no number. Counts never include them. A read of the row (local or a newer read-state version)
     * clears its entry.
     */
    val markedUnreadIds: Set<String> = emptySet(),
    /**
     * Task #164: this load's unread reads were the server's remembered bodies (`X-Raft-Stale`) or
     * `503 rw_overloaded`. Never persisted; the store turns it into [SharedHomeState.unreadStale].
     */
    @Transient
    val unreadStale: Boolean = false,
    /** botiverse/slock #12066: an unread read failed with `503 rw_unconfigured`. Never persisted. */
    @Transient
    val unreadUnavailable: Boolean = false,
    /** Task #164: the server's `Retry-After` on that stale or overloaded read. Never persisted. */
    @Transient
    val unreadRetryAfterMillis: Long? = null,
    /**
     * The unread summary's newest message per scope, by channel id value
     * ([ChannelUnreadSummary.latestActivitySeqs]): how far past a saved read a row's count can reach.
     * Never persisted, and kept apart from the rows' [HomeConversationRow.lastMessageSeq], which
     * decides whether a realtime message is new.
     */
    @Transient
    val unreadLatestActivitySeqs: Map<String, Long> = emptyMap(),
    /** The unread summary's read state per scope, by channel id value. Never persisted. */
    @Transient
    val unreadReadFrontiers: Map<String, ReadReceiptFrontier> = emptyMap(),
    /**
     * `slock #16474` / `#16479`: true when the last `GET /channels/dm?include=lounges` answer
     * carried an explicit `lounges` array (a server that speaks `listed`). Never persisted.
     */
    @Transient
    val dmListNamesLounges: Boolean = false,
)

@Serializable
data class ThreadSnapshot(
    val conversation: ConversationSummary,
    val messages: List<Message>,
    val parentMessage: Message? = null,
    val tasks: List<TaskSummary> = emptyList(),
    val canLoadMoreBefore: Boolean = false,
    val canLoadMoreAfter: Boolean = false,
    val hasGap: Boolean = false,
    val historyLimited: Boolean = false,
    val highlightedMessageId: MessageId? = null,
    val newUpdateCount: Int = 0,
    val lastSeq: Long = messages.maxOfOrNull { it.seq ?: 0L } ?: 0L,
    /** Page-coherent inline reply summaries returned with the same canonical message response. */
    @Transient
    val replySummaries: ThreadReplySummariesSnapshot = ThreadReplySummariesSnapshot(emptyList()),
    /**
     * Provenance for [replySummaries]. Missing is distinct from an authoritative empty result:
     * old Servers omit the embedded field and must use the targeted-summary compatibility
     * request before a replies-domain rebaseline can publish.
     */
    @Transient
    val replySummariesAuthority: ThreadReplySummariesAuthority = ThreadReplySummariesAuthority.Unavailable,
    /**
     * Destination-owned channel/DM detail loaded with this snapshot. Reply Threads keep their
     * stream identity in [conversation], so this separate witness retains the canonical parent
     * conversation without turning the reply stream into its parent.
     */
    @Transient
    val canonicalConversationDetail: ConversationSummary? = null,
    /**
     * Raw `messages` array of the REST page this snapshot was mapped from (task #168). Set ONLY by
     * the older / newer paging fetches, the reply page a prewarm asks for (task #14b) and the
     * `messages/context` page (task #14 part C, `requestedLimit = 0`: that endpoint takes no limit);
     * every other path leaves it [RawMessagePageWitness.None]. It
     * exists so a paging page can also be persisted into the durable V2 window; see
     * [RawMessagePageWitness] for why it never takes part in equality.
     */
    @Transient
    val rawMessagePage: RawMessagePageWitness = RawMessagePageWitness.None,
    /**
     * Raw server row count in `messages` before row-level decode drops (`AGENTS.md` No silent drops).
     * Paging compares this count with the requested limit, never `messages.size`.
     */
    @Transient
    val rawMessageCount: Int = messages.size,
    /**
     * A reply thread's total reply count as its own load reported it: `replyCount` of the
     * `POST /channels/:id/threads` answer (server `getThreadInfoForChannel`) that opens the thread.
     * `null` on every other load. The thread page counts from this, not from what its opener knew.
     */
    @Transient
    val replyThreadReplyCount: Int? = null,
)

/**
 * One fetched REST message page: the server's raw `messages` array and the `limit` the request sent, plus
 * (message storage Phase 1, no reader yet) the page's [messageWindow] (`messages/channel` only; `null` when
 * absent or malformed) and its `historyLimited` flag, which together say what range the page proves.
 */
data class RawMessagePage(
    val messages: JsonArray,
    val requestedLimit: Int,
    val messageWindow: ChannelMessageWindow? = null,
    val historyLimited: Boolean = false,
    /** Phase 2b: the page's embedded reply counts by parent message id; `null` = the page carried none. */
    val replyCountsByParentId: Map<String, Int>? = null,
    /** Phase 2c: what a `messages/context` response says about itself; `null` on any other page. */
    val context: RawMessageContextFields? = null,
)

/**
 * The top-level fields of a `messages/context` response the durable writer needs. [channelId] is the page's scope key;
 * [threadParentChannelId] / [threadRootMessageId] are its `canonicalTarget` when that is a reply in [channelId].
 */
data class RawMessageContextFields(
    val channelId: String,
    val targetMessageId: String?,
    val hasOlder: Boolean,
    val hasNewer: Boolean,
    val threadParentChannelId: String?,
    val threadRootMessageId: String?,
)

/**
 * Write-side witness carrying a [RawMessagePage] from the repository mapper to the thread store's
 * paging call site (task #168), which hands it to the durable window extender.
 *
 * It must never affect state equality or dedupe: two snapshots that map to the same messages are
 * the same snapshot whether or not one of them still carries its raw page. So [equals] is `true`
 * for every other witness (present or absent alike) and [hashCode] is constant. The field is
 * non-null with a [None] sentinel precisely so that "absent vs present" is not a difference either
 * (a nullable field would make `null != page` under the data-class equality).
 */
class RawMessagePageWitness(val page: RawMessagePage?) {
    override fun equals(other: Any?): Boolean = other is RawMessagePageWitness

    override fun hashCode(): Int = 0

    override fun toString(): String =
        page?.let { "RawMessagePageWitness(size=${it.messages.size}, requestedLimit=${it.requestedLimit})" }
            ?: "RawMessagePageWitness(None)"

    companion object {
        val None: RawMessagePageWitness = RawMessagePageWitness(null)
    }
}

data class ThreadListSnapshot(
    val server: ServerSummary,
    val threads: List<InboxItem>,
    val totalCount: Int = threads.size,
    val hasMore: Boolean = false,
    val nextOffset: Int? = null,
    /** Task #164: the server answered from its remembered followed-threads body (`X-Raft-Stale`). */
    val staleReason: RaftStaleReason? = null,
    /** Task #164: the server's `Retry-After` with that remembered body. */
    val staleRetryAfterMillis: Long? = null
)

data class ThreadReplySummariesSnapshot(
    val threads: List<InboxItem>,
    val followedThreadKeys: Set<String> = emptySet()
)

enum class ThreadReplySummariesAuthority {
    Unavailable,
    EmbeddedInvalid,
    Embedded,
    Targeted,
}

data class AttachmentUploadRequest(
    val channelId: ChannelId,
    val files: List<PlatformFile>
)

/**
 * Platform-owned local file handle selected by a picker or produced by a native editor.
 *
 * Product/upload code may carry this model through shared state, but only platform
 * file-reader capabilities may dereference [fileRef]. Multipart field names, auth,
 * endpoints, and HTTP headers belong to shared upload/network code, not this model.
 */
data class PlatformFile(
    val id: String,
    val fileName: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val previewUri: String? = null,
    val bytes: ByteArray = ByteArray(0),
    val fileRef: String? = null,
    val source: String = ""
)

data class AttachmentUploadResult(
    val attachments: List<Attachment>
) {
    val attachmentIds: List<AttachmentId> = attachments.map { it.id }
}

data class AttachmentUploadCapabilities(
    val maxBytes: Long,
    /** Server-authoritative direct-session availability.  Never infer this from the route. */
    val directUploadEnabled: Boolean = false,
    /** Optional threshold advertised by the server; null means no threshold contract. */
    val directUploadThresholdBytes: Long? = null,
    /** Optional session lifetime hint, in seconds. */
    val sessionExpiresInSeconds: Int? = null,
)

/** Input for the authenticated direct attachment-session create operation. */
data class AttachmentUploadSessionCreateRequest(
    val channelId: ChannelId,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val clientRequestId: String,
)

/** Presigned PUT instructions returned by the server.  [url] is never persisted or logged. */
data class AttachmentUploadSessionPut(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
)

data class AttachmentUploadSessionCreateResult(
    val uploadId: String,
    val attachmentId: AttachmentId,
    val state: String,
    val expiresAt: String,
    val upload: AttachmentUploadSessionPut,
)

data class AttachmentUploadSessionView(
    val uploadId: String,
    val state: String,
    val expiresAt: String,
    val attachment: Attachment? = null,
    val terminalReason: String? = null,
    val reservationState: String? = null,
)

data class AttachmentUploadSessionCompleteResult(
    val uploadId: String,
    val state: String,
    val attachment: Attachment,
)

/** One completed direct upload kept private until the enclosing Gallery batch commits. */
data class DirectAttachmentUpload(
    val localId: String,
    val uploadId: String,
    val attachment: Attachment,
)

@RaftCoreInternalApi data class DirectAttachmentUploadBatch(
    val uploads: List<DirectAttachmentUpload>,
    val sessionIds: List<String>,
    /** Process-local compensation handle; never persisted or sent over the wire. */
    @RaftCoreInternalApi val compensationHandle: DirectAttachmentUploadCompensationHandle? = null,
)

data class AttachmentOpenTarget(
    val attachmentId: AttachmentId,
    val url: String,
    val expiresAt: String? = null
)

data class ChannelFileItem(
    val attachment: Attachment,
    val createdAt: String? = null,
    val messageId: MessageId? = null,
    val channelId: ChannelId? = null,
    val uploaderType: String? = null,
    val uploaderId: String? = null,
    val uploaderName: String? = null,
    val uploaderDisplayName: String? = null,
    val sourceType: String? = null,
    val sourceChannelId: ChannelId? = null,
    val sourceParentMessageId: MessageId? = null,
    val sourceParentMessageShortId: String? = null
)

data class ChannelFilesSnapshot(
    val channelId: ChannelId,
    val items: List<ChannelFileItem>,
    val hasMore: Boolean = false,
    val nextCursor: String? = null
)

data class TaskSnapshot(
    val tasks: List<TaskSummary>,
    val summaryCounts: Map<TaskStatus, Int>
)

data class MemberSnapshot(
    val server: ServerSummary,
    val members: List<MemberProfile>
)

data class AgentDetailSnapshot(
    val agentId: MemberId,
    val summary: AgentDetailSummary? = null,
    val directMessages: List<AgentDmSummary>,
    val channels: List<AgentChannelSummary> = emptyList(),
    val reminders: List<AgentReminderSummary>,
    val activity: List<AgentActivityEntry>,
    val skills: AgentSkillsSnapshot = AgentSkillsSnapshot(agentId = agentId),
    val appLogins: AgentAppLoginSnapshot = AgentAppLoginSnapshot(agentId = agentId),
    /**
     * `GET /agents/{id}/external-status` for external agents (React
     * AgentDetailPanel parity). Null means *not provided*: the agent is not
     * external, the caller lacks `editAgents`/creator authority (403), or the
     * request failed. Consumers must not read null as "waiting for login".
     */
    val externalStatus: ExternalAgentStatusSnapshot? = null
)

/**
 * Wire `setupState` of `GET /agents/{id}/external-status` (server `externalAgentSetupState`,
 * botiverse/slock #14534): connected (any credential not revoked was used), credential_minted
 * (one exists, never used), disconnected (none left; a revoked one had been used),
 * login_token_expired (never connected; the newest CLI login token expired unused),
 * waiting_for_login (nothing yet).
 */
enum class ExternalAgentSetupState {
    WaitingForLogin,
    CredentialMinted,
    LoginTokenExpired,
    Connected,
    Disconnected,
    Unknown;

    /** Web `externalSetupNeedsSignIn`: no credential it can sign in with. */
    val needsSignIn: Boolean
        get() = this == WaitingForLogin || this == LoginTokenExpired || this == Disconnected

    companion object {
        fun fromWireValue(value: String?): ExternalAgentSetupState =
            when (value?.trim()?.lowercase()) {
                "waiting_for_login" -> WaitingForLogin
                "credential_minted" -> CredentialMinted
                "login_token_expired" -> LoginTokenExpired
                "connected" -> Connected
                "disconnected" -> Disconnected
                else -> Unknown
            }
    }
}

/** Web `AgentHostedRuntimeSummary`, what the profile shows. */
@Serializable
data class AgentHostedRuntimeSnapshot(
    val state: String,
    val syncPending: Boolean = false,
    /** Null until the provider has answered about message delivery. */
    val pushRegistered: Boolean? = null,
    val pushError: String? = null,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
    /** The provider refused the picked model: a retry must name a model (null is its default). */
    val retryNeedsModel: Boolean = false
)

data class ExternalAgentStatusSnapshot(
    val setupState: ExternalAgentSetupState,
    val credentialLastUsedAt: String? = null,
    val lastActivityAt: String? = null,
    val hostedRuntime: AgentHostedRuntimeSnapshot? = null,
    /**
     * How its person said it would connect: `existing_agent` or `sdk` (botiverse/slock #14534);
     * null for an agent made before that, or an older server (shown as an existing agent).
     */
    val externalSetupMethod: String? = null
)

data class AgentDetailSummary(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    val serverRole: String? = null,
    val status: String? = null,
    val runtime: String? = null,
    val isExternal: Boolean = false,
    val model: String? = null,
    val runtimeModelKind: String? = null,
    val runtimeProvider: build.raft.app.model.RuntimeProviderConfig? = null,
    val reasoning: String? = null,
    /**
     * The saved fast mode, `runtimeConfig.mode.kind` (`fast` / `default`); null without one (Default).
     * Not [executionMode], which says where the agent runs (`byoc` / `cloud`).
     */
    val runtimeMode: String? = null,
    val executionMode: String? = null,
    val machineId: String? = null,
    val machineName: String? = null,
    val daemonVersion: String? = null,
    /** The workspace path the agent's runtime reported; null when it reported none. */
    val workspacePath: String? = null,
    val avatarUrl: String? = null,
    val creatorId: String? = null,
    val creatorType: String? = null,
    val creator: AgentCreatorSummary? = null,
    val createdAt: String? = null,
    val envVarNames: List<String> = emptyList(),
    val envVars: Map<String, String> = emptyMap(),
    val createdAgents: List<AgentCreatedSummary> = emptyList(),
    val activity: String? = null,
    val activityDetail: String? = null,
    val deletedAt: String? = null,
    /** A personal assistant's owner; null for a shared agent (Agent Lounge v0 hides the Lounge for it). */
    val assistantOwnerUserId: String? = null,
    /** Raftbench service agent (`raftbenchService`); false for ordinary agents. */
    val raftbenchService: Boolean = false,
    /** App Bots only (`agents.managedByAppInstallId`); null for ordinary agents and guest projections. */
    val managedByAppInstallId: String? = null,
    /** App Bots only: owning App row id (`managedByApp.clientId` = `oauth_clients.id`, slock #14286). */
    val managedByAppClientId: String? = null,
    /** App Bots only: owning App display name (`managedByApp.name`). */
    val managedByAppName: String? = null,
    /** App Bots only: owning App logo URL (`managedByApp.logoUrl`). */
    val managedByAppLogoUrl: String? = null,
    /** App Bots only: app-published status line (`managedByApp.botStatusText`). */
    val managedByAppBotStatusText: String? = null,
    /** Bounded public profile projection marker (`"channel_summary"`, `routes/agents.ts`). */
    val profileProjection: String? = null,
    /** External agents only: `lastSeenAt` — credential last-use; realtime `agent:seen` advances it (task #410). */
    val lastSeenAt: String? = null,
    /** The typed kind behind [activityDetail] (`activityDetailKind`); null from older servers. */
    val activityDetailKind: String? = null,
    /** Why automatic wakes are paused, with [activityDetailKind] `wake_crash_loop_blocked`. */
    val wakeCrashLoop: build.raft.app.model.AgentWakeCrashLoop? = null,
    /** Why the last start failed, with [activityDetailKind] `runtime_unavailable` (web `activityState.spawnFailure`). */
    val spawnFailure: build.raft.app.model.AgentSpawnFailure? = null,
    /** `lastRuntimeError.message`: the runtime error the server kept (web `agent.lastRuntimeError`); null when none. */
    val lastRuntimeErrorMessage: String? = null,
    /** `lastRuntimeError.errorClass` / `errorReason`: its typed diagnostic (web `typedRuntimeError`), when it has one. */
    val lastRuntimeErrorClass: String? = null,
    val lastRuntimeErrorReason: String? = null,
    /**
     * `lastRuntimeError.requiredAction` (botiverse/slock #13276): what the owner must do about the kept
     * error, `user_reauth` or `user_model_access`; null when none or from older servers.
     */
    val lastRuntimeErrorRequiredAction: build.raft.app.model.AgentCredentialHoldReason? = null,
    /** `lastRuntimeError.code` (web #13985): the kept error's code, for every viewer; null when none or unknown. */
    val lastRuntimeErrorCode: AgentRuntimeErrorCode? = null,
    /** `lastRuntimeError.spawnFailureReason` (slock #15273): the failed start's reason kept with the error. */
    val lastRuntimeErrorSpawnFailureReason: String? = null,
    /** `activityErrorCode` (web #13985): the code of the error [activity] shows; only while it is `error`. */
    val activityErrorCode: AgentRuntimeErrorCode? = null,
    /** The agent is stuck on one tool call (`toolStuck`, served with any activity); null when not. */
    val toolStuck: build.raft.app.model.AgentToolStuck? = null,
    /**
     * Why the daemon holds the agent for an unusable sign-in another agent on the same credential saw
     * rejected, with [activityDetailKind] `idle` (web `activityState.credentialHold`, botiverse/slock #13276).
     */
    val credentialHold: build.raft.app.model.AgentCredentialHoldReason? = null,
    /**
     * The daemon holds this agent's deliveries on any activity (`deliveryHold`, botiverse/slock #16333);
     * null when not held or from older servers/daemons.
     */
    val deliveryHold: build.raft.app.model.AgentDeliveryHold? = null,
    /**
     * Running Workers count on this agent's current Computer (`runningWorkers`, botiverse/slock
     * #16819; joint-channel peer is 1 or absent). Null when none or from older servers.
     */
    val runningWorkers: Int? = null,
    /**
     * Live service tier reported by the agent's active session (`serviceTier`, botiverse/slock
     * #16868, e.g. `default`, `priority`, `fast`, `flex`). Null when unknown or from older servers.
     */
    val serviceTier: String? = null,
    /**
     * `modelLimits.defaultsReported` (botiverse/slock #13492): the agent's Computer reported that the
     * current session filled at least one limit of its Built-in custom gateway model with a Raft
     * default. Only true shows the warning; false, null and an old server's absent field show nothing.
     */
    val modelLimitsDefaultsReported: Boolean = false,
    /** A cloud agent's provisioning record (web `agent.hostedRuntime`); null for any other agent. */
    val hostedRuntime: AgentHostedRuntimeSnapshot? = null,
    /**
     * What the running process reports it runs (`runtimeProfile.current.runtime` / `.model`); null
     * when unknown. A saved change waiting for the agent's next idle point differs from the saved
     * [runtime] / [model] (web AgentProfileInfo `runningConfigHint`, botiverse/slock #13372).
     */
    val runningRuntime: String? = null,
    val runningModel: String? = null
)

data class AgentCreatorSummary(
    val type: String,
    val id: String,
    val name: String,
    val displayName: String? = null,
    val avatarUrl: String? = null
)

data class AgentCreatedSummary(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val runtime: String? = null,
    val status: String? = null,
    /** Required: producers must fold both sources via isExternalAgent(external, runtime). */
    val isExternal: Boolean
)

data class AgentDmSnapshot(
    val agentId: MemberId,
    val directMessages: List<AgentDmSummary>
)

data class AgentChannelSnapshot(
    val agentId: MemberId,
    val channels: List<AgentChannelSummary>
)

data class AgentReminderSnapshot(
    val agentId: MemberId,
    val reminders: List<AgentReminderSummary>
)

/**
 * Most raw activity rows one read asks for and the page keeps: web `MAX_TRAJECTORY_ENTRIES`, the server's
 * `/activity-log` cap, so the Raw log and Sub-agents list match the web's.
 */
const val AgentActivityLogMaxEntries = 500

data class AgentActivitySnapshot(
    val agentId: MemberId,
    val activity: List<AgentActivityEntry>,
    /** Log rows the response carried that this client could not read (left out, logged). */
    val droppedRowCount: Int = 0
)

// #89 React parity: agent permissions (scope grants) snapshot.
// Mirrors React `AgentScopeSet` returned by `GET /api/agents/:id/scopes`.
// `granted` is a list of `AgentGrantableScope` strings (e.g. "inbox:receive",
// "server:read", "channel:read", ...). `mode` is "default" vs "custom".
data class AgentScopeSet(
    val agentId: MemberId,
    val granted: List<String> = emptyList(),
    val mode: String = "default",
    val revision: Long = 0L,
    val updatedAt: String? = null
)

data class AgentWorkspaceSnapshot(
    val agentId: MemberId,
    val computerId: String? = null,
    val computerName: String? = null,
    val workspaces: List<SettingsComputerWorkspaceSnapshot> = emptyList()
)

data class AgentWorkspaceTreeSnapshot(
    val agentId: MemberId,
    val rootPath: String,
    val root: RaftWorkspaceNode
)

data class AgentWorkspaceChildrenSnapshot(
    val agentId: MemberId,
    val dirPath: String,
    val children: List<RaftWorkspaceNode>
)

data class AgentWorkspaceFilePreviewSnapshot(
    val agentId: MemberId,
    val path: String,
    val preview: RaftWorkspaceFilePreview
)

data class AgentSkillsSnapshot(
    val agentId: MemberId,
    val groups: List<AgentSkillGroup> = emptyList()
)

data class AgentAppLoginSnapshot(
    val agentId: MemberId,
    val items: List<AgentAppLoginItem> = emptyList(),
    val loadState: AgentAppLoginLoadState = AgentAppLoginLoadState.Content,
    val errorCode: String? = null,
    val screenError: ScreenError? = null,
    val permissionBlocker: PermissionBlocker? = null,
    /**
     * The Apps tab's re-read after a revoke failed (web AgentAppAccessTab's `error` banner): [items]
     * keep what was shown, and this says why they may be stale. The next read replaces the snapshot.
     */
    val refreshError: ScreenError? = null
)

/** An App's live grant (web `groupGrantsByApp` reads `type === "active"`; a revoked one isn't listed). */
@RaftCoreInternalApi val AgentAppLoginItem.isActiveGrant: Boolean
    get() = type == AgentAppLoginType.Active && revokedAt.isNullOrBlank()

enum class AgentAppLoginLoadState {
    Content,
    Empty,
    NoPermission,
    Error
}

data class AgentAppLoginItem(
    val id: String,
    val type: AgentAppLoginType,
    val clientId: String,
    val clientKey: String? = null,
    val clientName: String,
    val clientDescription: String? = null,
    val clientHomepageUrl: String? = null,
    val clientReturnUrl: String? = null,
    val clientAgentManifestUrl: String? = null,
    val scopes: List<String> = emptyList(),
    val remember: Boolean = false,
    val createdAt: String? = null,
    val resolvedAt: String? = null,
    val resolvedByUserId: String? = null,
    val revokedAt: String? = null,
    /** How the grant came about (web `grantSource`): person, agent_login, app_request, legacy_import; null when not reported. */
    val grantSource: String? = null
)

enum class AgentAppLoginType {
    Pending,
    Active
}

data class AgentSkillGroup(
    val title: String,
    val count: Int = 0,
    val paths: List<AgentSkillPathGroup> = emptyList()
)

data class AgentSkillPathGroup(
    val path: String,
    val count: Int = 0,
    val skills: List<AgentSkillSummary> = emptyList()
)

data class AgentSkillSummary(
    val name: String,
    val detail: String? = null,
    /** The `/command` a user can invoke it with (web `userInvocable` -> `/{name}`); null when not invocable. */
    val command: String? = null
)

data class AgentDmSummary(
    val id: String,
    val peerId: String?,
    val peerName: String,
    val peerAvatarUrl: String? = null,
    val lastMessageAt: String? = null,
    val lastMessagePreview: String? = null
)

/** An agent's channel membership as returned by GET /agents/:id/channels. */
data class AgentChannelSummary(
    val id: String,
    val name: String,
    val description: String? = null,
    val type: String = "channel",
    val createdAt: String? = null,
    val archivedAt: String? = null,
    val activityMuted: Boolean = false,
    val muteFromSeq: Long? = null,
    /** Set when this row is an agent's Lounge (slock #16325). */
    val loungeAgentId: String? = null
)

data class AgentReminderSummary(
    val id: String,
    val title: String,
    val fireAt: String? = null,
    val recurrenceDescription: String? = null,
    val messageRef: String? = null,
    val messagePermalink: String? = null,
    val ownerAgentId: String? = null,
    val status: String? = null
)

data class AgentActivityEntry(
    val id: String,
    val type: String,
    val title: String,
    val body: String? = null,
    val timestamp: String? = null,
    val details: List<Pair<String, String>> = emptyList(),
    val toolName: String? = null,
    val toolInput: String? = null,
    val statusActivity: String? = null,
    val statusDetail: String? = null,
    /** Server `detailKind` of a status entry ("starting", "compacting_context", ...). */
    val statusDetailKind: String? = null,
    val isSubagent: Boolean = false,
    val subagentType: String? = null,
    val expandable: Boolean = false,
    val refTextEnabled: Boolean = false,
    /** A `tool_start`'s call id (`callId`); a sub-agent's lineage names the call that started it. */
    val callId: String? = null,
    /** The row's sub-agent lineage marker (`subagent`); null for the agent's own rows. */
    val subagent: build.raft.app.model.AgentActivitySubagentLineage? = null
)

data class ChannelMemberSnapshot(
    val channelId: ChannelId,
    val members: List<MemberProfile>,
    val canAddMembers: Boolean = false,
    val canRemoveMembers: Boolean = false,
    /**
     * The channel's `channelCapabilities.changeChannelMemberRoles` on a channel whose roles can
     * change (not archived, not #all): gates the members page's role menu (web `MemberRoleActions`).
     */
    val canChangeMemberRoles: Boolean = false,
    /** Slack-bridge (or other provider) participants — display-only roster rows
     *  (web `channelExternalMembers`). Never selectable/removable. */
    val externalMembers: List<ChannelExternalMemberProfile> = emptyList(),
    /** Ids of agent rows that carried the server's live `activity` (the non-guest
     *  `GET /channels/:id/members` merges it in; the guest projection omits it). An
     *  explicit producer signal: only these rows may seed live presence facts. */
    val liveActivityAgentIds: Set<String> = emptySet(),
    /**
     * The channel's `channelCapabilities.federateChannels` resolved by `GET /channels/:id`
     * (false when the channel is archived or `#all`, null when the detail could not be read or the
     * server omitted `channelCapabilities`). Gates external invites on the members page (#wg-rbac:#133).
     */
    val federateChannels: Boolean? = null
)

@Serializable
data class InboxSnapshot(
    val server: ServerSummary,
    val items: List<InboxItem>,
    val totalCount: Int = items.size,
    val totalUnreadCount: Int = items.sumOf { it.unreadCount },
    val hasMore: Boolean = false,
    val nextOffset: Int? = null,
    /**
     * Server `activeUnreadCount` (unread excluding mention-only channels; web
     * drives its rail dot from it). Null when the server omits it.
     */
    val activeUnreadCount: Int? = null,
    /**
     * Task #164: set when the server answered from its remembered body (`X-Raft-Stale`), up to
     * 5 minutes old. Never persisted: a restored snapshot is the client's own last good page.
     */
    @Transient
    val staleReason: RaftStaleReason? = null,
    /** Task #164: the server's `Retry-After` with that remembered page. Never persisted. */
    @Transient
    val staleRetryAfterMillis: Long? = null,
    /**
     * The answer's `X-Raft-Inbox-Backend` (`risingwave` / `postgres`), unchanged; web copies it into
     * its drift check (`inbox.inplace.backend`). Null when the server sent none. Never persisted.
     */
    @Transient
    val inboxBackend: String? = null,
    /**
     * Workspace Artifacts (slock `activityObjectsContract.ts` `ObjectInboxFields`): the object rows'
     * own counts, apart from [totalCount] / [totalUnreadCount], which count no object rows. Null when
     * the server sent none (every object app off).
     */
    val objectCounts: ActivityObjectCounts? = null,
)

/**
 * A conversation row currently hidden from Activity while its Undo toast waits out the 8 s window
 * before [SharedActivityIntent.MarkDone] or [SharedActivityIntent.UnfollowThread] is sent.
 * [removesCountRow]: true for Done (and Unfollow in filters that drop unfollowed threads); false
 * for Unfollow in filters that keep unfollowed threads in totalCount while clearing unread.
 */
data class SharedActivityPendingDeferredHide(
    val itemKey: String,
    val removesCountRow: Boolean,
)

/**
 * Count deductions `(countDelta, unreadDelta)` for [pendingDeferredHide] while its row is still in
 * [items] (and neither an object row nor an already-suppressed internal joint row). Returns `0 to 0`
 * once the row leaves [items] (e.g. MarkDone committed or remote Done arrived), preventing double
 * subtraction.
 */
@RaftCoreInternalApi fun InboxSnapshot.activityPendingDeferredDeltas(
    pendingDeferredHide: SharedActivityPendingDeferredHide?,
    selectedFilter: InboxFilter = InboxFilter.All,
    retainedReadRowKeys: Set<String> = emptySet(),
): Pair<Int, Int> {
    val hide = pendingDeferredHide ?: return 0 to 0
    val row =
        items.firstOrNull { item ->
            item.key == hide.itemKey &&
                item.kind != InboxItemKind.Object &&
                !item.hasUnopenableInternalJointParentSource()
        } ?: return 0 to 0
    val alreadyLeftCount =
        !selectedFilter.keepsReadActivityRows() &&
            row.key in retainedReadRowKeys &&
            !row.showsUnread
    val countDelta = if (hide.removesCountRow && !alreadyLeftCount) 1 else 0
    val unreadDelta = row.unreadCount.coerceAtLeast(0)
    return countDelta to unreadDelta
}

/**
 * The counts Activity shows and badges for the All filter: the conversation rows' ([totalCount],
 * [totalUnreadCount], which the server keeps free of object rows) plus the object rows' own
 * ([objectCounts]); an override replaces the conversation part only. Any [pendingDeferredHide]
 * still present in [items] is projected out until committed or undone.
 */
@RaftCoreInternalApi fun InboxSnapshot.activityVisibleSummaryCounts(
    totalUnreadCountOverride: Int? = null,
    totalCountOverride: Int? = null,
    pendingDeferredHide: SharedActivityPendingDeferredHide? = null,
): Pair<Int, Int> {
    val conversations = items.filterNot { item -> item.kind == InboxItemKind.Object }
    val suppressed = conversations.filter { item -> item.hasUnopenableInternalJointParentSource() }
    val visibleItems = conversations.filterNot { item -> item.hasUnopenableInternalJointParentSource() }
    val pendingRow = pendingDeferredHide?.let { hide -> visibleItems.firstOrNull { it.key == hide.itemKey } }
    val pendingUnread = pendingRow?.unreadCount?.coerceAtLeast(0) ?: 0
    val pendingCount = if (pendingRow != null && pendingDeferredHide.removesCountRow) 1 else 0
    val shownUnreadFloor =
        visibleItems.sumOf { item ->
            if (pendingRow != null && item.key == pendingRow.key) 0 else item.unreadCount.coerceAtLeast(0)
        }
    val shownCountFloor = (visibleItems.size - pendingCount).coerceAtLeast(0)
    return maxOf(
        shownUnreadFloor,
        (totalUnreadCountOverride ?: totalUnreadCount) - suppressed.sumOf(InboxItem::unreadCount) - pendingUnread,
    ).coerceAtLeast(0) + (objectCounts?.objectUnreadCount ?: 0) to
        maxOf(
            shownCountFloor,
            (totalCountOverride ?: totalCount) - suppressed.size - pendingCount,
        ).coerceAtLeast(0) + (objectCounts?.objectCount ?: 0)
}

data class InboxDoneResult(
    val channelId: ChannelId
)

/**
 * Request frontier for `POST /channels/inbox/done` and `POST
 * /channels/threads/done` (botiverse/slock 1bb70cbeb, routes/channels.ts:1497,
 * 2014). The server's compatibility matrix: an omitted seq takes the legacy
 * "latest at request time" snapshot; a seq requires `frontierSpace:"storage"`.
 */
sealed interface InboxDoneFrontier {
    /** Omit `throughActivitySeq` and `frontierSpace` (server predates `doneFrontierSeq`). */
    data object Legacy : InboxDoneFrontier

    /** Send `{throughActivitySeq, frontierSpace:"storage"}`. */
    data class Storage(val throughActivitySeq: String) : InboxDoneFrontier
}

data class ChannelSnapshot(
    val server: ServerSummary,
    val channels: List<HomeChannelRow>
)

data class CreateChannelResult(
    val channel: HomeChannelRow,
    val addedAgentMemberIds: List<String> = emptyList(),
    val addedUserMemberIds: List<String> = emptyList()
)

data class ChannelJoinResult(
    val channelId: ChannelId,
    val joined: Boolean = true,
    val channel: HomeChannelRow? = null
)

data class ChannelLeaveResult(
    val channelId: ChannelId,
    val left: Boolean = true
)

data class ChannelArchiveResult(
    val channelId: ChannelId,
    val archived: Boolean = true,
    val channel: HomeChannelRow? = null
)

data class ChannelDeleteResult(
    val channelId: ChannelId,
    val deleted: Boolean = true
)

@Serializable
data class ChannelUnreadSnapshot(
    val server: ServerSummary,
    val unreadCounts: Map<ChannelId, Int>,
    val totalUnreadCount: Int = unreadCounts.values.sum()
)

data class ReadReceiptResult(
    val channelId: ChannelId? = null,
    val seq: Long? = null,
    val readStateVersion: Long? = null,
    val maxReadSeq: Long? = null,
    val scopes: List<ReadStateV1AckScope> = emptyList(),
    val unreadCount: Int = 0,
    val changed: Boolean = true,
    /**
     * `POST /channels/:id/unread` (botiverse/slock #11795): true = saved as "marked unread" with no
     * count; false = nothing to mark (refused); null = a server older than the mark (or another write).
     */
    val markedUnread: Boolean? = null,
    /**
     * `POST /channels/:id/unread` (botiverse/slock #11854): what the write left, decided by the server in
     * the read-state transaction. Null from a server older than the field (or another write);
     * [MarkUnreadOutcomeDto.Unknown] from a newer one. Read it through [markUnreadOutcome].
     */
    val serverMarkUnreadOutcome: MarkUnreadOutcomeDto? = null,
    /**
     * `POST /channels/:id/unread` answered 202 ([build.raft.app.data.generated.ReadMutationPendingResponseDto]):
     * the write was admitted, its result is not known yet; the `read_state:updated` that follows carries it.
     */
    val markUnreadPending: Boolean = false,
)

@Serializable
data class ReadStateV1AckScope(
    val channelId: ChannelId,
    val readStateVersion: Long? = null,
    val maxReadSeq: Long? = null,
)

@Serializable
data class ReadStateV1HydrateCarrier(
    val readStateVersion: Long? = null,
    val maxReadSeq: Long? = null,
)

data class DirectMessageSnapshot(
    val server: ServerSummary,
    val directMessages: List<HomeDirectMessageRow>
)

data class OpenDirectMessageResult(
    val conversation: HomeDirectMessageRow,
    val directMessages: List<HomeDirectMessageRow>
)

data class SavedSnapshot(
    val server: ServerSummary,
    val items: List<InboxItem>,
    val totalCount: Int = items.size,
    val hasMore: Boolean = false,
    val nextOffset: Int? = null
)

data class SavedMessageMutationResult(
    val messageId: MessageId
)

data class SavedMessageStatusSnapshot(
    val savedIds: Set<MessageId>
)

data class SearchSnapshot(
    val server: ServerSummary,
    val query: String,
    val items: List<InboxItem>,
    val totalCount: Int = items.size,
    val hasMore: Boolean = false,
    val nextOffset: Int? = null,
    /** Server walk cursor for the next page; when set it is used instead of [nextOffset]. */
    val nextCursor: String? = null,
    /** Server `relaxedSkipped: "too_broad"`: the page is empty because the search is too broad. */
    val tooBroad: Boolean = false,
    /** Server `relevanceFallback: "too_broad"`: too many matches to rank, sorted newest first. */
    val relevanceFallback: Boolean = false,
    /** Server `incomplete` with a cursor: the walk stopped at its scan budget (web `searchIncomplete`). */
    val incomplete: Boolean = false,
    /** With [incomplete]: the oldest message time the walk read (ISO). */
    val searchedThrough: String? = null,
)

data class SettingsConnectedAppsSnapshot(
    val marketplace: List<SettingsConnectedAppMarketplaceSnapshot> = emptyList(),
    val builtIn: List<SettingsConnectedAppBuiltInSnapshot> = emptyList(),
    val installed: List<SettingsConnectedAppInstalledSnapshot> = emptyList(),
    val registered: List<SettingsConnectedAppClientSnapshot> = emptyList(),
    val canManage: Boolean = true,
    val oneTimeClientSecret: SettingsConnectedAppSecretSnapshot? = null
)

data class SettingsConnectedAppMarketplaceSnapshot(
    val id: String,
    val clientId: String,
    val name: String,
    val category: String,
    val developer: String,
    val description: String? = null,
    val homepageUrl: String? = null,
    val homepageDomain: String? = null,
    val returnUrl: String? = null,
    val callbackDomain: String? = null,
    val logoUrl: String? = null,
    val installed: Boolean = false,
    val dataAccessSummary: String = "Profile and identity"
)

data class SettingsConnectedAppBuiltInSnapshot(
    val id: String,
    val clientId: String,
    val name: String,
    val description: String? = null,
    val homepageUrl: String? = null,
    val homepageDomain: String? = null,
    val agentManifestUrl: String? = null
)

data class SettingsConnectedAppInstalledSnapshot(
    val id: String,
    val clientId: String,
    val name: String,
    val developer: String,
    val description: String? = null,
    val homepageDomain: String? = null,
    val callbackDomain: String? = null,
    val logoUrl: String? = null,
    val origin: String,
    val editable: Boolean = false
)

data class SettingsConnectedAppClientSnapshot(
    val id: String,
    val serverId: String,
    val clientId: String,
    val appType: String,
    val publishStatus: String,
    val category: String,
    val dataAccessSummary: String? = null,
    val publishRejectionReason: String? = null,
    val name: String,
    val description: String? = null,
    val homepageUrl: String? = null,
    val homepageDomain: String? = null,
    val returnUrl: String? = null,
    val callbackDomain: String? = null,
    val agentManifestUrl: String? = null,
    val logoUrl: String? = null
)

data class SettingsConnectedAppSecretSnapshot(
    val clientId: String,
    val clientSecret: String
)

data class AccountSettingsSnapshot(
    val profile: AccountProfileSnapshot,
    val connectedAccounts: List<ConnectedAccountSnapshot>,
    val canEditProfile: Boolean = true,
    val canUploadAvatar: Boolean = true,
    val canChangePassword: Boolean = true,
    /** Settings › Account "Share usage data"; null hides the row (flag off, or not loaded from the server). */
    val usageData: AccountUsageDataSnapshot? = null,
    /** `GET /api/auth/identities` `passwordConfigured`: whether the account has a password; null until read. */
    val passwordConfigured: Boolean? = null
)

/**
 * slock `packages/shared/src/productEvents.ts` `SHARE_USAGE_DATA_DEFAULT`: what "Share usage data" is
 * for a user who has not chosen (`users.share_usage_data` null). The server gate
 * (`productAnalyticsGate`) applies the same default.
 */
const val SHARE_USAGE_DATA_DEFAULT: Boolean = true

/** The user's RFC-067 "Share usage data" choice as the server holds it. */
data class AccountUsageDataSnapshot(
    /** `users.shareUsageData`: true / false, or null when the user has never chosen. */
    val choice: Boolean?
) {
    /** What the switch shows: the choice, or [SHARE_USAGE_DATA_DEFAULT] until there is one (web parity). */
    val sharing: Boolean get() = choice ?: SHARE_USAGE_DATA_DEFAULT
}

/**
 * `PATCH /api/auth/me` `{ description }` result (botiverse/slock #15664): the normalized
 * description the server now holds (null when cleared) and any link-visibility warnings for
 * links in the newly saved description (botiverse/slock #15236).
 */
data class AccountDescriptionUpdateResult(
    val description: String?,
    val warnings: List<DescriptionLinkWarning> = emptyList(),
)

data class AccountProfileSnapshot(
    val userId: String,
    val displayName: String,
    val handle: String,
    val email: String,
    val avatarLabel: String,
    val avatarUrl: String? = null,
    val verified: Boolean = false,
    val roleLabel: String? = null
)

data class ConnectedAccountSnapshot(
    val providerId: String,
    val provider: String,
    val identifier: String,
    val status: ConnectedAccountStatus,
    val canConnect: Boolean = status == ConnectedAccountStatus.NotConnected
)

enum class ConnectedAccountStatus {
    Connected,
    NotConnected,
    Loading,
    Failed
}

data class SettingsBillingSnapshot(
    val currentPlan: SettingsBillingCurrentPlanSnapshot,
    val plans: List<SettingsBillingPlanSnapshot>,
    val notices: List<SettingsBillingNoticeSnapshot> = emptyList(),
    val actions: List<SettingsBillingActionSnapshot> = emptyList(),
    val comparePlansUrl: String = "https://raft.build/#pricing",
    /** `GET /billing/subscription` as loaded; null when it failed or was empty. */
    val summary: SettingsBillingSubscriptionSnapshot? = null,
    /** The server's own plan (the session's), for when [summary] is missing. */
    val serverPlan: String? = null,
    /** When the server was moved down to Free; its grace period runs from here. */
    val planDowngradedAt: String? = null
)

data class SettingsBillingSubscriptionSnapshot(
    val status: String? = null,
    val currentPeriodEnd: String? = null,
    val cancelAtPeriodEnd: Boolean = false,
    val stripeConfigured: Boolean = false,
    val billingInterval: String? = null,
    val canManageBilling: Boolean = false,
    val displayName: String? = null,
    val effectivePlan: String? = null,
    /** `server`, `subscription`, `appsumo` or `prepaid`: what the plan comes from. */
    val source: String? = null,
    /** Server-resolved Pro pricing (`seat_v1` | `person_v2`, slock #16345); null when an older server omits it (`seat_v1`). */
    val pricing: ProPricing? = null,
    val appsumoBillingReserved: Boolean = false,
    val appsumo: SettingsBillingAppSumoSnapshot? = null,
    /** Active or in-grace Prepaid Pro grant (`GET /billing/subscription` `prepaid`, botiverse/slock `#12867 #13745 #13932`). */
    val prepaid: SettingsBillingPrepaidSnapshot? = null,
    /** Seats the plan holds; -1 unlimited, null when billing does not say. */
    val maxUniversalSeats: Double? = null,
    val usage: SettingsBillingSeatUsageSnapshot? = null,
    val provisionedHumans: Int = 0,
    val provisionedAgents: Int = 0,
    val proPackQuantity: Int = 0,
    val fileUploadQuota: SettingsBillingFileUploadQuotaSnapshot? = null,
    val priceBillingInterval: String? = null,
    /**
     * On a plan with a history window: how often this server's agents reached behind it in the
     * past 7 days (`GET /billing/subscription` `agentHistoryBlocks7d`, botiverse/slock #14512).
     * Null when not counted (unlimited history) or unreadable.
     */
    val agentHistoryBlocks7d: Int? = null,
)

/** An AppSumo lifetime license: its allowances and what is used of them. */
data class SettingsBillingAppSumoSnapshot(
    val tier: Int,
    val maxHumans: Int,
    val maxAgents: Int,
    val maxHostedJointChannels: Int,
    val hostedJointChannels: Int
)

/**
 * Active or in-grace Prepaid Pro grant (`GET /billing/subscription` `prepaid`,
 * botiverse/slock `packages/shared/src/serverBillingEntitlement.ts` `PrepaidEntitlement`).
 */
data class SettingsBillingPrepaidSnapshot(
    val endsAt: String,
    val graceEndsAt: String,
    val inGrace: Boolean,
    val seatQuantity: Int,
)

/** Who is on the server for billing: people, agents and the seats they take (a person 1; an agent 0.1 under `seat_v1`, 0 under `person_v2`). */
data class SettingsBillingSeatUsageSnapshot(
    val humans: Int,
    val agents: Int,
    val universalSeats: Double
)

/** The monthly file upload allowance: [limitBytes] and [remainingBytes] are -1 when unlimited. */
data class SettingsBillingFileUploadQuotaSnapshot(
    val month: String?,
    val limited: Boolean,
    val limitBytes: Long,
    val remainingBytes: Long
)

data class SettingsServerUsageSnapshot(
    val agents: Int,
    val machines: Int,
    val channels: Int
)

data class SettingsBillingCurrentPlanSnapshot(
    val planName: String,
    val usageRows: List<SettingsPlanUsageSnapshot>,
    val trialActive: Boolean = false,
    val description: String = "",
    val includedFeatures: List<String> = emptyList(),
    val excludedFeatures: List<String> = emptyList()
)

data class SettingsPlanUsageSnapshot(
    val label: String,
    val value: String
)

data class SettingsBillingPlanSnapshot(
    val name: String,
    val price: String,
    val features: List<String>,
    val current: Boolean = false,
    val comingSoon: Boolean = false,
    val badgeLabel: String? = null
)

data class SettingsBillingNoticeSnapshot(
    val key: String,
    val title: String,
    val detail: String,
    val tone: String = "info",
    val rows: List<SettingsPlanUsageSnapshot> = emptyList()
)

data class SettingsBillingActionSnapshot(
    val key: String,
    val label: String,
    val detail: String,
    val enabled: Boolean = false,
    val style: String = "secondary"
)

data class SettingsComputersSnapshot(
    val computers: List<SettingsComputerSnapshot>,
    val canAddComputer: Boolean = false,
    val addComputerUnavailableReason: String? = null,
    val latestDaemonVersion: String? = null,
    val latestComputerVersion: String? = null,
    /** For the offline cards' restart command; null while the session has no server slug. */
    val commandContext: SettingsComputerCommandContext? = null,
    /** Server plan's cap on Computers (-1 = unlimited, web `getEffectiveLimits(plan).maxMachines`). */
    val maxComputers: Int = -1,
)

data class SettingsComputerSnapshot(
    val id: String,
    val name: String,
    val daemonVersion: String,
    val computerVersion: String? = null,
    val isComputer: Boolean = false,
    val online: Boolean,
    val hostname: String? = null,
    val platform: String? = null,
    val description: String? = null,
    val lastSeenLabel: String? = null,
    val runtimes: List<String> = emptyList(),
    val createdAt: String? = null,
    val lastHeartbeat: String? = null,
    val apiKeyPrefix: String? = null,
    val statusVersion: Long = 0L,
    /** `machines.userId` — the machine creator, who may edit/remove it without a capability. */
    val creatorUserId: String? = null,
    /** The current user attached the Computer backing this machine (web counts this as manage authority). */
    val attachedByCurrentUser: Boolean = false,
    /**
     * Server `controlledByCurrentUser` (slock #15002): the current user is the machine's controller as
     * the server's routes decide it, which still holds after its Computer's key is revoked. Null from
     * older servers, which [settingsComputerPermissions] then judges by [creatorUserId] and
     * [attachedByCurrentUser] as before.
     */
    val controlledByCurrentUser: Boolean? = null,
    /** Active agents assigned to this machine (server `agentCount`). */
    val agentCount: Int = 0,
    /**
     * The server's policy-aware `computerUpgradeAvailable` decision, only ever true for a managed
     * Computer (web `shouldShowComputerUpgradeIndicator`); raw daemons never claim an update.
     */
    val upgradeAvailable: Boolean = false,
    /** Server `computerBroadcastPolicy` (managed Computers only): the one authority for the upgrade target. */
    val broadcastPolicy: SettingsComputerBroadcastPolicySnapshot? = null,
    /** Server `upgradeRequest`: the latest web upgrade request for this machine (remote upgrade v2). */
    val upgradeRequest: SettingsComputerUpgradeRequestSnapshot? = null,
    /** Server `remoteUpgradeSupported`: only true may be upgraded from the web; null = version unknown. */
    val remoteUpgradeSupported: Boolean? = null,
    /** Server `versionGate`: the version floor verdict; null = at or above the floor (or not reported). */
    val versionGate: SettingsComputerVersionGateSnapshot? = null,
    /** The list's `latestComputerVersion` (artifact hint), carried on each row for the detail page. */
    val latestComputerVersion: String? = null,
    /** The list's `latestComputerReleaseNotes` (notes for exactly [latestComputerVersion]), carried on each row. */
    val latestComputerReleaseNotes: SettingsComputerReleaseNotesSnapshot? = null,
    /** Server `creator`: the human who attached a managed Computer; null when none (raw daemon, departed attacher). */
    val creator: SettingsComputerCreatorSnapshot? = null,
    /**
     * Computer owner v1 (slock #13344, server `ownerUserId`): who Raft records as this Computer's
     * owner; null = a Public Computer (also an older server). Not the connector ([creatorUserId],
     * [attachedByCurrentUser]); display and transfer only.
     */
    val ownerUserId: String? = null,
    /** The owner's public identity (server `owner`); shown only while [ownerUserId] is set. */
    val owner: SettingsComputerCreatorSnapshot? = null,
    /** Server `agentScopes`: the per-agent memory scope state the machine reported while online; null otherwise. */
    val agentScopes: SettingsComputerAgentScopesSnapshot? = null,
    /** Server `osRelease`: the OS name and version ("Ubuntu 24.04"), reported by Linux Computers only. */
    val osRelease: String? = null,
    /**
     * Server `hostProvider`: the cloud / VM platform the Computer recognized, a shared
     * `HOST_PROVIDER_LABELS` id ("hetzner", "aws"); null when none (a personal computer) or an older server.
     */
    val hostProvider: String? = null,
    /** Server `deviceClass` ("laptop", "server", "desktop", ...); null when unknown or an older server. */
    val deviceClass: String? = null,
    /**
     * Server `sameMachineGroup` (slock #10082, `computer_host_identity_v0`): opaque per-response
     * label shared by Computers running on the same physical machine ("machine-1"); null when alone
     * or while the flag is off / on older servers. Compare only, never display.
     */
    val sameMachineGroup: String? = null,
    /**
     * Server `installerRepairHint.reason` (`unrecovered_operation`): advisory, the installer state
     * needs a repair run on the machine; the install command is recommended beside Upgrade.
     */
    val installerRepairHint: String? = null,
    /**
     * Server `upgradeScope` is an explicit refusal (`ok: false`, botiverse/slock #15243): the
     * Computer runs as a systemd system service whose manager refuses the scope a web upgrade's
     * installer needs, so the web upgrade is refused (`scope_unavailable`) and no Upgrade is offered.
     * False when allowed, unknown, or an older server.
     */
    val upgradeScopeRefused: Boolean = false,
    /** Server `runtimeVersions`: runtime id -> detected CLI version, for the usage sheet's "Version …" line. */
    val runtimeVersions: Map<String, String> = emptyMap(),
    /**
     * Server `runtimeDetection` (task #1268 / slock #16477): explicit runtime detection status
     * reported by the Computer; null when complete or absent (older Computers).
     */
    val runtimeDetection: SettingsComputerRuntimeDetection? = null,
    /** Server `diskStatus`: free and total disk while online; null when offline or never reported. */
    val diskStatus: SettingsComputerSizeStatusSnapshot? = null,
    /** Server `memoryStatus`: free and total memory while online; null when offline, never reported, or an older Computer. */
    val memoryStatus: SettingsComputerSizeStatusSnapshot? = null,
    /**
     * The viewer may read this machine's runtime account usage: the detail page's rule
     * ([settingsComputerPermissions] `canEdit`: owner/admin, the creator, or the attaching person).
     * Set by the settings data source from the session; false until it has been.
     */
    val canViewRuntimeUsage: Boolean = false,
    /**
     * The viewer may restart / upgrade this machine: the detail page's `canManageMachines`
     * ([settingsComputerPermissions] edit or remove). Set with [canViewRuntimeUsage]; false until then.
     */
    val canManage: Boolean = false,
    /** Server `attention`'s machine logins that need a step, for the usage cells (web `rackLoginStep`); empty when none or not reported. */
    val loginSteps: List<ComputerLoginStep> = emptyList(),
    /** Server `attention.count > 0` verdict (web `serverNeedsAttention`, slock #15534); null when not reported by the server. */
    val serverNeedsAttention: Boolean? = null,
    /** Full server `attention` summary (slock #14587 / #14602); null when absent on older servers or cold durable cache. */
    val attention: ComputerReadinessAttention? = null,
    /** Full server `readiness` projection (slock #14554 / #14587 / #14602); null when absent on older servers or cold durable cache. */
    val readiness: ComputerReadinessSnapshot? = null,
    /**
     * Server `revocation` (slock #16468): set for a machine whose Computers are all revoked
     * (when, why, and the names this viewer may see). Null for a live Computer, a legacy row never
     * adopted, or an older server.
     */
    val revocation: SettingsComputerRevocationSnapshot? = null,
    /**
     * Server `versionRefusedAt` (slock #16514): when the server last refused this machine on
     * connect for being below the Computer version floor (or reporting no version); null otherwise.
     */
    val versionRefusedAt: String? = null,
    val refusedComputerVersion: String? = null,
    /**
     * Worker v1 step 2 (slock #17011, `MachineWorkerRuntimesFields`): runtimes turned on for Workers
     * on this Computer. Null when absent on older servers (section stays hidden); empty = none on.
     */
    val workerRuntimesTurnedOn: List<String>? = null,
    /** Whether this viewer may change [workerRuntimesTurnedOn] (the server's own decision). */
    val canChangeWorkerRuntimes: Boolean = false,
    /** Who can change [workerRuntimesTurnedOn]: `"owner_or_admin"` or `"any_member"`; null when unknown. */
    val workerRuntimesChangeBy: String? = null
)

/**
 * Runtime detection status of a Computer (`machines[].runtimeDetection`, slock #16477 / task #1268).
 */
enum class SettingsComputerRuntimeDetectionStatus(val wire: String) {
    Pending("pending"),
    Complete("complete"),
    Incomplete("incomplete");

    companion object {
        fun fromWire(value: String?): SettingsComputerRuntimeDetectionStatus? {
            val normalized = value?.trim() ?: return null
            return entries.firstOrNull { it.wire == normalized }
        }
    }
}

/**
 * Why a Computer's runtime detection finished [SettingsComputerRuntimeDetectionStatus.Incomplete].
 */
enum class SettingsComputerRuntimeDetectionReason(val wire: String) {
    Deadline("deadline"),
    ChildFailed("child_failed"),
    Cancelled("cancelled");

    companion object {
        fun fromWire(value: String?): SettingsComputerRuntimeDetectionReason? {
            val normalized = value?.trim() ?: return null
            return entries.firstOrNull { it.wire == normalized }
        }
    }
}

/**
 * `runtimeDetection` of a machines row (slock `MachineRuntimeDetection`, #16477).
 */
data class SettingsComputerRuntimeDetection(
    val status: SettingsComputerRuntimeDetectionStatus,
    val unfinishedRuntimes: List<String> = emptyList(),
    val reason: SettingsComputerRuntimeDetectionReason? = null,
    val updatedAtMs: Long? = null,
)

/**
 * Why a Computer's key was revoked (slock packages/shared/src/computerRevocation.ts
 * `ComputerRevokedVia`, #16468). Additive: unknown wire values decode as null so older builds
 * still show the revoked state without a specific cause sentence.
 */
enum class SettingsComputerRevokedVia(val wire: String) {
    MemberLeft("member_left"),
    MemberRemoved("member_removed"),
    AccountDeleted("account_deleted"),
    KeyRevokedManually("key_revoked_manually"),
    RevokeLink("revoke_link"),
    ComputerDetached("computer_detached"),
    SessionRevoked("session_revoked"),
    ApproverSessionRevoked("approver_session_revoked"),
    SetupReset("setup_reset");

    companion object {
        fun fromWire(value: String?): SettingsComputerRevokedVia? {
            val normalized = value?.trim() ?: return null
            return entries.firstOrNull { it.wire == normalized }
        }
    }
}

/**
 * The latest revoke of a machine whose Computers are all revoked (slock
 * `ComputerRevocation`, GET /api/servers/:id/machines `revocation`, #16468).
 */
data class SettingsComputerRevocationSnapshot(
    /** When the key was revoked (ISO). */
    val at: String,
    /** Why; null for a revoke before #16468 or an unrecognized future value. */
    val via: SettingsComputerRevokedVia? = null,
    /** Who did it (removed the attacher, or revoked the key); null if hidden or unused. */
    val byUserName: String? = null,
    /** Whose departure caused it; null if hidden or unused. */
    val subjectUserName: String? = null,
    /** `left`, `removed`, or `account_deleted` for membership vias; null otherwise. */
    val departureReason: String? = null,
)

/**
 * `diskStatus` / `memoryStatus` of a machines row (slock packages/shared/src/index.ts
 * `MachineDiskStatus` / `MachineMemoryStatus`), only a readable report (positive total, free within it).
 */
data class SettingsComputerSizeStatusSnapshot(
    val availableBytes: Double,
    val totalBytes: Double
)

/**
 * `agentScopes` of a machines row (slock packages/shared/src/agentMemoryLimit.ts
 * `MachineAgentScopesStatus`, served by packages/server/src/services/machineReadModel.ts only while the
 * machine is online). [limitsNotApplied]: a per-agent memory limit is configured but agents run without it.
 */
data class SettingsComputerAgentScopesSnapshot(
    /** `pending`, `available`, `unavailable`, `suspended` or `stopped`. */
    val state: String,
    val limitsConfigured: Boolean,
    val limitsNotApplied: Boolean,
    /** Closed enum from the daemon's preflight or suspension (e.g. `memory_controller_not_delegated`). */
    val reason: String? = null,
    val memoryMax: String? = null,
    val memoryHigh: String? = null
)

/**
 * A managed Computer's creator as `GET servers/:id/machines` sends it (slock
 * packages/server/src/services/computerCredentialService.ts `ComputerCreatorSummary`): a server
 * member's public identity, always a human.
 */
data class SettingsComputerCreatorSnapshot(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val gravatarHash: String? = null
)

/**
 * `computerBroadcastPolicy` of a `GET servers/:id/machines` row (slock
 * packages/server/src/services/computerBroadcastPolicyService.ts `projectComputerBroadcastPolicyDecision`).
 */
data class SettingsComputerBroadcastPolicySnapshot(
    /** `eligible` or `no_broadcast`. */
    val eligibility: String,
    val targetVersion: String? = null,
    val policyRevision: String? = null,
    val reasonCode: String? = null
) {
    val eligible: Boolean get() = eligibility == "eligible"
}

/**
 * `upgradeRequest` of a machines row and of `POST …/computer/upgrade` (slock
 * packages/server/src/services/computerUpgradeRequestService.ts `projectComputerUpgradeRequest`).
 */
/**
 * Shared `ComputerUpgradeFailureHint`: a failed web upgrade only a run of the install command on
 * the machine gets past. [reason] `unsafe_kill_mode`, `installation_held`, or
 * `earlier_recovery_pending`; [unit], [killMode] and [scope] (`user` / `system`) only when a
 * systemd-unit failure named them.
 */
data class SettingsComputerUpgradeFailureHintSnapshot(
    val reason: String,
    val unit: String? = null,
    val killMode: String? = null,
    val scope: String? = null
)

/**
 * Shared `ComputerUpgradeDiskFull` (slock #16536): the last web upgrade failed for lack of disk
 * space. [neededBytes] and [freeBytes] are present when the Computer's refusal named both sizes,
 * and null when the installer reported `disk_full` without sizes.
 */
data class SettingsComputerUpgradeDiskFullSnapshot(
    val neededBytes: Long? = null,
    val freeBytes: Long? = null
)

data class SettingsComputerUpgradeRequestSnapshot(
    val id: String,
    val targetVersion: String,
    val requestedAt: String,
    /** `pending`, `done`, `failed` or `no_response`. */
    val state: String,
    val observedVersion: String? = null,
    val reason: String? = null,
    val resolvedAt: String? = null,
    /** When the server settles the request as `no_response` (ISO), drain wait included; null on older servers. */
    val deadlineAt: String? = null,
    /**
     * Reconciler step 13, only on a pending request whose Computer waits for its agents first:
     * `draining` while they finish their work, `installing` once that wait is over; null otherwise.
     */
    val phase: String? = null,
    /** The agents of THIS server still finishing their work while [phase] is `draining` (other servers' are not listed). */
    val drainingAgentIds: List<String> = emptyList(),
    /** When the drain stops waiting (ISO). */
    val drainDeadlineAt: String? = null,
    /** Server `failureHint` (a failed request only): what to run on the machine. */
    val failureHint: SettingsComputerUpgradeFailureHintSnapshot? = null,
    /** Server `diskFull` (slock #16536, a failed request only): out of disk space, no install command. */
    val diskFull: SettingsComputerUpgradeDiskFullSnapshot? = null
)

/** `versionGate` of a machines row (slock packages/shared/src/index.ts `ComputerVersionGate`). */
data class SettingsComputerVersionGateSnapshot(
    /** `computer_version_unsupported` or `computer_version_unknown`. */
    val reason: String,
    val minVersion: String,
    val enforcedAt: String? = null,
    val enforced: Boolean = false
)

/**
 * `latestComputerReleaseNotes` of `GET servers/:id/machines` (slock packages/shared/src/computerReleaseNotes.ts):
 * markdown notes for [version] in the published locales (`en`, `zh-CN`).
 */
data class SettingsComputerReleaseNotesSnapshot(
    val version: String,
    val en: String? = null,
    val zhCn: String? = null
)

/**
 * What the computer detail page needs to write the `raft-computer` terminal commands (web
 * `getComputerCommands(serverSlug, deploymentEnv, serverUrl)`): the server slug, the deployment
 * (`production`, `staging`, or `slockdev` for a custom developer environment) and its server URL.
 */
data class SettingsComputerCommandContext(
    val serverSlug: String,
    val deploymentEnv: String,
    val serverUrl: String? = null
)

data class SettingsComputerDetailSnapshot(
    val computer: SettingsComputerSnapshot,
    val workspaces: List<SettingsComputerWorkspaceSnapshot>,
    val agents: List<SettingsComputerAgentSnapshot>,
    val sections: List<SettingsServerSectionSnapshot>,
    val workspacesScanned: Boolean = false,
    val workspacesError: SettingsSectionErrorSnapshot? = null,
    val agentsError: SettingsSectionErrorSnapshot? = null,
    /**
     * Either [canEditMachine] or [canRemoveMachine]: the page shows its Actions section. The
     * server still enforces 403 on the API endpoints, so a permissive client cannot bypass it.
     */
    val canManageMachines: Boolean = false,
    /** Rename / description: creator, attaching user, or the `editMachines` capability (owner/admin). */
    val canEditMachine: Boolean = false,
    /** Delete: creator, attaching user, or the `removeMachines` capability (owner/admin). */
    val canRemoveMachine: Boolean = false,
    /**
     * Web `canControlComputer` (`canManageMachine.ts`): may send this Computer commands (restart,
     * upgrade, stop `raft proc` jobs) — owner/admin, live attacher, creator, or controller while
     * `isComputer` is true.
     */
    val canControlComputer: Boolean = canEditMachine,
    /** For the terminal commands the page offers; null while the session has no server slug. */
    val commandContext: SettingsComputerCommandContext? = null,
    /**
     * Computer owner v1 (web `ComputerOwnerField`): Transfer owner, for the current owner and a
     * server owner/admin (`editMachines`). The server checks the same (403 otherwise).
     */
    val canTransferOwner: Boolean = false,
    /** Make it mine: a Public Computer, for whoever connected it (the attacher; a raw daemon's registrant). */
    val canClaimOwner: Boolean = false,
    /** The viewer's user id: Make it mine sends it, and the transfer list marks "(you)". */
    val currentUserId: String? = null,
    /**
     * Names of other Computers on this server sharing [computer]'s [SettingsComputerSnapshot.sameMachineGroup]
     * (botiverse/slock #10082 / #10304 `sameMachinePeers`), in list order. Empty when alone or when
     * `computer_host_identity_v0` omits `sameMachineGroup`.
     */
    val sameMachinePeerNames: List<String> = emptyList()
)

/**
 * Other Computers in [computers] on the same physical machine as [computer] (matching non-blank
 * [SettingsComputerSnapshot.sameMachineGroup]), in list order (botiverse/slock
 * `packages/web/src/utils/machinePlatformLine.ts` `sameMachinePeers`, #10082 / #10304). Empty when
 * [SettingsComputerSnapshot.sameMachineGroup] is null / blank (alone, or `computer_host_identity_v0` off).
 */
fun sameMachinePeers(
    computer: SettingsComputerSnapshot,
    computers: List<SettingsComputerSnapshot>,
): List<SettingsComputerSnapshot> {
    val group = computer.sameMachineGroup?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
    return computers.filter { other ->
        other.id != computer.id && other.sameMachineGroup?.trim() == group
    }
}

/** Display names of [sameMachinePeers] in list order (blank names fall back to id). */
fun sameMachinePeerNames(
    computer: SettingsComputerSnapshot,
    computers: List<SettingsComputerSnapshot>,
): List<String> =
    sameMachinePeers(computer, computers).map { peer -> peer.name.trim().ifBlank { peer.id } }

data class SettingsSectionErrorSnapshot(
    val code: String,
    val retryable: Boolean = false,
    val screenError: ScreenError? = null,
    val permissionBlocker: PermissionBlocker? = null
)

data class SettingsComputerWorkspaceSnapshot(
    val id: String,
    val name: String,
    val path: String,
    val statusLabel: String? = null,
    val lastActiveLabel: String? = null,
    val totalSizeBytes: Long? = null,
    val fileCount: Int? = null,
    /** Server `partial` (slock #16552): the bounded scan stopped early, so size and file count are lower bounds. */
    val partial: Boolean = false
)

data class SettingsComputerAgentSnapshot(
    val id: String,
    val name: String,
    val statusLabel: String? = null,
    val ownerLabel: String? = null,
    val runtime: String? = null,
    /** The agent's model id (wire `model`); null when the row carries none. */
    val model: String? = null,
    val avatarUrl: String? = null,
    val description: String? = null,
    val memberProfile: MemberProfile? = null,
    /** The saved reasoning effort (wire `reasoningEffort`); null is the runtime's default. */
    val reasoningEffort: String? = null,
    /** Wire `external`: an external agent's runtime fields cannot be changed. */
    val external: Boolean = false,
    /** Wire top-level `envVars`, the fallback when [runtimeConfig] carries none. */
    val envVars: Map<String, String>? = null,
    /** What the bulk Change model reads from wire `runtimeConfig`; null when the row carries none. */
    val runtimeConfig: SettingsComputerAgentRuntimeConfig? = null,
    /** User id who created this agent (`creatorId` when `creatorType == "user"`), for Delete Computer own-agent filtering. */
    val creatorUserId: String? = null,
    /** Personal assistant's owner user id (`assistantOwnerUserId`), for Delete Computer own-agent filtering. */
    val assistantOwnerUserId: String? = null,
)

/**
 * The parts of an agent's `runtimeConfig` the Computer page's bulk Change model needs (web
 * `agentHasOnlyRebuildableSettings` / `buildBulkAgentUpdate`).
 */
data class SettingsComputerAgentRuntimeConfig(
    val runtime: String? = null,
    /** `model.kind`: `preset` or `custom`. */
    val modelKind: String? = null,
    /** `provider.kind`; null when the runtime carries no provider. */
    val providerKind: String? = null,
    val command: String? = null,
    /** `mode.kind == "fast"`. */
    val fastMode: Boolean = false,
    val envVars: Map<String, String>? = null,
    /** `codexAccess`: `"auto"` or `"inherit"` (Codex only); null is All Access (slock #16692). */
    val codexAccess: String? = null,
)

data class SettingsServerSnapshot(
    val profile: SettingsServerProfileSnapshot,
    val sections: List<SettingsServerSectionSnapshot>,
    val admins: List<SettingsServerAdminSnapshot> = emptyList(),
    val usageRows: List<SettingsPlanUsageSnapshot> = emptyList(),
    val pendingInvites: List<SettingsServerInviteSnapshot> = emptyList(),
    val joinLinks: List<SettingsServerJoinLinkSnapshot> = emptyList(),
    val agreement: SettingsServerAgreementSnapshot? = null,
    val onboarding: SettingsServerOnboardingSnapshot? = null,
    val translation: SettingsServerTranslationSnapshot? = null,
    val sidebarOrder: SettingsServerSidebarOrderSnapshot? = null,
    val systemChannels: SettingsServerSystemChannelSnapshot? = null
)

data class SettingsServerSystemChannelSnapshot(
    val allChannelId: String = "",
    val allChannelHidden: Boolean = false,
    val canManage: Boolean = false
)

data class SettingsServerProfileSnapshot(
    val name: String,
    val slug: String,
    val hideHumansFromMembers: Boolean = false,
    val canSave: Boolean = false,
    /** The server's id and uploaded avatar (`servers[].avatarUrl`), for the profile card's tile. */
    val serverId: String? = null,
    val avatarUrl: String? = null
)

data class SettingsServerSectionSnapshot(
    val key: String,
    val title: StringDesc,
    val detail: String,
    val state: SettingsSectionAvailability = SettingsSectionAvailability.Unavailable,
    val errorPresentation: ErrorPresentation? = null
)

data class SettingsServerAdminSnapshot(
    val id: String,
    val name: String,
    val detail: String? = null,
    val role: String? = null
)

data class SettingsServerInviteSnapshot(
    val id: String,
    val email: String,
    val inviterName: String? = null,
    val createdAtLabel: String? = null
)

data class SettingsServerJoinLinkSnapshot(
    val id: String,
    val label: String,
    val url: String? = null,
    val maxUsesLabel: String? = null,
    val usageLabel: String? = null,
    val expiresAtLabel: String? = null,
    /** Wire `maxUses` (null = unlimited); the invite dialog's "keep current" limit. */
    val maxUses: Int? = null,
    /** Wire `expiresAt` ISO timestamp (null = never). */
    val expiresAt: String? = null,
    /** Wire `useCount`. */
    val useCount: Int? = null
)

data class SettingsServerAgreementSnapshot(
    val enabled: Boolean,
    val title: String,
    val bodyMarkdown: String,
    val statusLabel: String,
    val detail: String,
    val canSave: Boolean = false
)

data class SettingsServerOnboardingSnapshot(
    val setupModalReminderOptOut: Boolean,
    val onboardingAgentId: String? = null,
    val agentAllChannelGreetingEnabled: Boolean = true,
    val agents: List<SettingsServerOnboardingAgentSnapshot> = emptyList(),
    val statusLabel: String,
    val detail: String,
    val canSave: Boolean = false
)

data class SettingsServerOnboardingAgentSnapshot(
    val id: String,
    val name: String,
    val displayName: String? = null
)

data class SettingsServerTranslationSnapshot(
    val translationEnabled: Boolean,
    val translationAvailable: Boolean,
    val canManageTranslation: Boolean,
    val statusLabel: String,
    val detail: String
)

@Serializable
data class SettingsServerSidebarOrderSnapshot(
    val channelOrder: List<String> = emptyList(),
    val agentOrder: List<String> = emptyList(),
    val dmOrder: List<String> = emptyList(),
    val channelSortMode: String = "manual",
    val dmSortMode: String = "manual",
    val pinnedSortMode: String = "manual",
    val pinnedChannelIds: List<String> = emptyList(),
    val pinnedAgentIds: List<String> = emptyList(),
    val pinnedOrder: List<String> = emptyList(),
    val pinned: List<SettingsServerSidebarPinnedItem> = emptyList(),
    val hiddenDmIds: List<String> = emptyList(),
    val channelPanelTabOrder: List<String> = emptyList(),
    val agentPanelTabOrder: List<String> = emptyList(),
    // Web keeps the Joint Channels sort mode separate from Channels
    // (sidebar-order `jointChannelSortMode`).
    val jointChannelSortMode: String = "manual",
    // task #512: web "channel sections" port. Mirrors the wire fields of
    // GET/PATCH /servers/:id/sidebar-order (web packages/web/src/store/sidebarSections.ts).
    val customSections: List<SettingsServerSidebarCustomSection> = emptyList(),
    val sectionOrder: List<String> = emptyList(),
    val sectionPlacements: List<SettingsServerSidebarSectionPlacement> = emptyList(),
    val sectionsVersion: Long = 0L,
    // #wg-mobile-sync:#294 (slock #16682): per-server rail places (`places`, `placesVersion`,
    // `placeableApps`), null on older servers that do not send `places`.
    val railPlaces: RaftRailPlacesSnapshot? = null,
    // #proj-mobile:#329 (slock #16964): per-server mobile & tablet navigation places (`mobilePlaces`,
    // `mobilePlacesVersion`, `placeableApps`), null on older servers that do not send `mobilePlaces`.
    val mobilePlaces: RaftMobilePlacesSnapshot? = null,
)

@Serializable
data class SettingsServerSidebarPinnedItem(
    val kind: String,
    val id: String
)

@Serializable
data class SettingsServerSidebarCustomSection(
    val id: String,
    val name: String,
    val emoji: String? = null,
    val sortMode: String = "manual"
)

@Serializable
data class SettingsServerSidebarSectionPlacement(
    val kind: String,
    val id: String,
    val sectionId: String,
    val position: Int
)

data class SettingsMobileSnapshot(
    val pushNotifications: SettingsMobileToggleSnapshot,
    val muteServer: SettingsMobileToggleSnapshot,
    val serverNotificationMode: SettingsMobileSelectionSnapshot =
        unknownServerNotificationModeSelectionSnapshot(),
    /**
     * Message-preview choice for push notifications on this device. Kept as the three-state enum
     * rather than a toggle so the UI can render one choice per mode; availability is separate so an
     * unsupported build still shows the current selection as read-only.
     */
    val pushPreviewMode: SettingsMobileSelectionSnapshot =
        PushPreviewMode.Full.toSettingsMobileSelectionSnapshot(available = false),
    val sendTestEnabled: Boolean = false,
    val notificationPermission: SettingsMobileActionSnapshot =
        SettingsMobileActionSnapshot(
            available = false,
            statusLabel = "Unsupported",
            detail = "Notification permission is not available on this platform."
        ),
    val notificationSettings: SettingsMobileActionSnapshot =
        SettingsMobileActionSnapshot(
            available = false,
            statusLabel = "Unsupported",
            detail = "System notification settings are not available on this platform."
        ),
    val testNotification: SettingsMobileActionSnapshot =
        SettingsMobileActionSnapshot(
            available = false,
            statusLabel = "Unsupported",
            detail = "Test notifications are not available on this platform."
        ),
    val localePreference: SettingsMobileSelectionSnapshot =
        SettingsMobileSelectionSnapshot(
            selectedKey = AppLocale.Default.languageTag,
            selectedLabel = AppLocale.Default.englishLabel,
            statusLabel = AppLocale.Default.englishLabel,
            detail = "Stored locally on this device.",
            options =
                AppLocale.entries.map {
                    SettingsMobileSelectionOptionSnapshot(
                        key = it.languageTag,
                        label = it.englishLabel
                    )
                }
        ),
    val messageAlignmentPreference: SettingsMobileSelectionSnapshot =
        SettingsMobileSelectionSnapshot(
            selectedKey = MyMessageAlignmentPreference.Default.storageKey,
            selectedLabel = "Left",
            statusLabel = "Left",
            detail = "Stored locally on this device.",
            options =
                MyMessageAlignmentPreference.entries.map {
                    SettingsMobileSelectionOptionSnapshot(
                        key = it.storageKey,
                        label = it.name
                    )
                }
        ),
    val compactConsecutiveMessages: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = true,
            available = true,
            statusLabel = "Enabled",
            detail = "Hide repeated avatars for consecutive messages from the same sender. Message times appear while pressing a row."
        ),
    val longMessageCollapse: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = RAFT_LONG_MESSAGE_COLLAPSE_DEFAULT_ENABLED,
            available = true,
            statusLabel = if (RAFT_LONG_MESSAGE_COLLAPSE_DEFAULT_ENABLED) "Enabled" else "Off",
            detail = RAFT_LONG_MESSAGE_COLLAPSE_SETTINGS_DETAIL
        ),
    val newMessageMenu: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = false,
            available = true,
            statusLabel = "Off",
            detail = "Long-press a message to show quick reactions and common actions in a compact two-row menu. Turn it off to use the classic message menu."
        ),
    val foregroundMessageBanners: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = false,
            available = true,
            statusLabel = "Off",
            detail = "Show an in-app banner for new messages while Raft is open. When off, foreground messages stay quiet."
        ),
    val composerSendKey: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = false,
            available = true,
            statusLabel = "New line",
            detail = "Use the keyboard action key to insert new lines. Turn on Send key to send messages from the keyboard."
        ),
    val multiShellHost: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = false,
            available = true,
            statusLabel = "Off",
            detail = "Open different workspaces in separate Android app windows. Existing windows remain open until you close them."
        ),
    val serverDrawer: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = false,
            available = true,
            statusLabel = "Off",
            detail = "Use the new multi-server left drawer on Home. When off, keep the current server switcher popover."
        ),
    val desktopModeOnLargeScreen: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = true,
            available = true,
            statusLabel = "Enabled",
            detail = "On large screens, show the left rail with the list and detail side by side. Turn off to keep the single-pane mobile layout with the bottom tab bar."
        ),
    val linkOpenMode: SettingsMobileSelectionSnapshot =
        SettingsMobileSelectionSnapshot(
            selectedKey = LinkOpenMode.Default.storageKey,
            selectedLabel = "Default browser",
            statusLabel = "Default browser",
            detail = "Choose whether web links open in the default browser or inside Raft.",
            options =
                listOf(
                    SettingsMobileSelectionOptionSnapshot(
                        key = LinkOpenMode.SystemBrowser.storageKey,
                        label = "Default browser"
                    ),
                    SettingsMobileSelectionOptionSnapshot(
                        key = LinkOpenMode.InAppBrowser.storageKey,
                        label = "In-app browser"
                    )
                )
        )
)

enum class ServerPushMode(val wireName: String) {
    All("all"),
    Mentions("mentions"),
    None("none");

    companion object {
        fun fromWire(value: String?): ServerPushMode? = entries.firstOrNull { it.wireName == value }
    }
}

/**
 * [strictness] orders how much a mode hides (full < sender_only < hidden). Compare on it, never on
 * declaration order, so reordering the entries cannot change which redaction wins.
 */
enum class PushPreviewMode(val wireName: String, val strictness: Int) {
    Full("full", strictness = 0),
    SenderOnly("sender_only", strictness = 1),
    Hidden("hidden", strictness = 2);

    companion object {
        fun fromWire(value: String?): PushPreviewMode? = entries.firstOrNull { it.wireName == value }

        /** The mode that hides more; order-independent. */
        fun stricterOf(first: PushPreviewMode, second: PushPreviewMode): PushPreviewMode =
            if (second.strictness > first.strictness) second else first
    }
}

fun PushPreviewMode.toSettingsMobileSelectionSnapshot(
    available: Boolean = true
): SettingsMobileSelectionSnapshot =
    SettingsMobileSelectionSnapshot(
        selectedKey = wireName,
        selectedLabel = wireName,
        statusLabel = wireName,
        detail = "",
        options =
            PushPreviewMode.entries.map { mode ->
                SettingsMobileSelectionOptionSnapshot(key = mode.wireName, label = mode.wireName)
            },
        available = available
    )

fun ServerPushMode.toSettingsMobileSelectionSnapshot(
    available: Boolean = true,
    mentionsSupported: Boolean = true,
    detail: String = "Choose which messages from the selected workspace can send push notifications."
): SettingsMobileSelectionSnapshot =
    SettingsMobileSelectionSnapshot(
        selectedKey = wireName,
        selectedLabel = wireName,
        statusLabel = wireName,
        detail = detail,
        options =
            ServerPushMode.entries.filter { mode -> mentionsSupported || mode != ServerPushMode.Mentions }.map { mode ->
                SettingsMobileSelectionOptionSnapshot(key = mode.wireName, label = mode.wireName)
            },
        available = available
    )

/**
 * The workspace notification mode when neither the server nor a last-known copy answered: no option is
 * selected (the UI must not imply "All") and the control is unavailable, so the user cannot flip a value
 * they cannot see.
 */
fun unknownServerNotificationModeSelectionSnapshot(
    detail: String = "Workspace notification settings could not be loaded."
): SettingsMobileSelectionSnapshot =
    SettingsMobileSelectionSnapshot(
        selectedKey = "",
        selectedLabel = "",
        statusLabel = "Unavailable",
        detail = detail,
        options =
            ServerPushMode.entries.map { mode ->
                SettingsMobileSelectionOptionSnapshot(key = mode.wireName, label = mode.wireName)
            },
        available = false
    )

data class SettingsMobileToggleSnapshot(
    val enabled: Boolean,
    val available: Boolean,
    val statusLabel: String,
    val detail: String,
    val errorPresentation: ErrorPresentation? = null
)

data class SettingsMobileActionSnapshot(
    val available: Boolean,
    val statusLabel: String,
    val detail: String
)

data class SettingsMobileSelectionSnapshot(
    val selectedKey: String,
    val selectedLabel: String,
    val statusLabel: String,
    val detail: String,
    val options: List<SettingsMobileSelectionOptionSnapshot>,
    val available: Boolean = true
)

data class SettingsMobileMultiSelectionSnapshot(
    val selectedKeys: Set<String>,
    val statusLabel: String,
    val detail: String,
    val options: List<SettingsMobileSelectionOptionSnapshot>,
    val available: Boolean = true
)

data class SettingsMobileSelectionOptionSnapshot(
    val key: String,
    val label: String
)

data class SettingsLanguageRegionSnapshot(
    val serverTranslation: SettingsMobileToggleSnapshot,
    val autoTranslation: SettingsMobileToggleSnapshot,
    val browserLanguage: SettingsMobileSelectionSnapshot,
    val timezone: SettingsMobileSelectionSnapshot,
    val timeFormat: SettingsMobileSelectionSnapshot,
    val translationDisplay: SettingsMobileSelectionSnapshot =
        SettingsMobileSelectionSnapshot(
            selectedKey = "manual",
            selectedLabel = "Manual",
            statusLabel = "Manual",
            detail = "Stored locally on this device.",
            options =
                listOf(
                    SettingsMobileSelectionOptionSnapshot("automatic", "Auto"),
                    SettingsMobileSelectionOptionSnapshot("manual", "Manual"),
                    SettingsMobileSelectionOptionSnapshot("original", "Off")
                )
        )
)

data class SettingsLanguageRegionAccountPreferences(
    val preferredLanguage: String? = null,
    val preferredTimezone: String? = null,
    val autoTranslationEnabled: Boolean = true,
    val preferredTimeFormat: String? = null,
    val preferredTranslationDisplay: String? = null
)

data class SettingsLanguageRegionPreferenceUpdateRequest(
    val preferredLanguage: String? = null,
    val clearPreferredLanguage: Boolean = false,
    val preferredTimezone: String? = null,
    val clearPreferredTimezone: Boolean = false,
    val autoTranslationEnabled: Boolean? = null,
    val preferredTimeFormat: String? = null,
    val clearPreferredTimeFormat: Boolean = false,
    val preferredTranslationDisplay: String? = null
)

data class SettingsAppearanceSnapshot(
    val messageBodyFontSize: SettingsMobileSelectionSnapshot =
        SettingsMobileSelectionSnapshot(
            selectedKey = "md",
            selectedLabel = "Medium",
            statusLabel = "Medium",
            detail = "Adjusts message body text only.",
            options =
                listOf(
                    SettingsMobileSelectionOptionSnapshot("sm", "Small"),
                    SettingsMobileSelectionOptionSnapshot("md", "Medium"),
                    SettingsMobileSelectionOptionSnapshot("lg", "Large")
                )
        ),
    val composerSendKey: RaftComposerSendKey = RaftComposerSendKey.Default
)

data class SettingsAppearanceUpdateRequest(
    val preferredMessageBodyFontSize: String
)

enum class SettingsSectionAvailability {
    Available,
    Empty,
    Unavailable,
    NoPermission
}

@Serializable
data class PendingMentionAction(
    val resolutionId: String,
    val messageId: MessageId? = null,
    val channelId: ChannelId? = null,
    val channelName: String? = null,
    val targetType: String = "unknown",
    val targetHandle: String = "",
    val targetAvatarUrl: String? = null,
    val reason: String = "",
    val availableActions: List<String> = emptyList(),
    val expiresAt: String? = null
)

data class PendingMentionActionExecuteResult(
    val resolutionId: String,
    val status: String,
    val reason: String? = null
)

data class SendMessageResult(
    val message: Message,
    val pendingMentionActions: List<PendingMentionAction> = emptyList(),
    /** `POST v2/messages` `unresolvedMentionHandles`: `@handle` tokens that notified nobody. */
    val unresolvedMentionHandles: List<String> = emptyList()
)

data class ThreadActionResult(
    val threadChannelId: ChannelId,
    val changed: Boolean,
    val parentMessageId: MessageId? = null,
    val replyCount: Int = 0
)

data class TaskActionResult(
    val task: TaskSummary
)

data class TaskConvertResult(
    val task: TaskSummary?
)
