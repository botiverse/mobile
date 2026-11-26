package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.activityRowTimeValue
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageType
import build.raft.app.model.TaskStatus
import build.raft.app.model.ThreadUpdated
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.shownAuthorName

/*
 * #proj-mobile:#163 (Web parity with inboxInsertedThreadRow.ts, inboxThreadInsert.ts,
 * inboxUnfollowedThreadReply.ts):
 * - When a `thread:updated` arrives for a followed thread not yet listed in Activity, build and
 *   insert the row directly in place from the frame + known client data instead of returning
 *   Absent and reloading the list.
 * - When a `thread:updated` arrives for an unfollowed thread that cannot list for this viewer,
 *   skip the re-read cleanly instead of firing a full `/api/channels/inbox` reload or sync pull.
 */

private val PlainActivityParentChannelTypes: Set<String> = setOf("channel", "private", "dm")
private val ValidInsertedParentSenderKinds: Set<MemberKind> =
    setOf(MemberKind.Human, MemberKind.Agent, MemberKind.External)

/** Known followed-thread metadata before the incoming `thread:updated` frame (web `FollowedThread`). */
@RaftCoreInternalApi data class ActivityKnownFollowedThread(
    val threadChannelId: String,
    val parentMessageId: String,
    val parentChannelId: String,
    val parentChannelName: String,
    val parentChannelType: String,
    val parentMessagePreview: String?,
    val parentMessageSenderId: String?,
    val parentMessageSenderKind: MemberKind?,
    val parentInHistory: Boolean?,
    val latestActivitySeq: String?,
    val replyCount: Int,
    val unreadCount: Int,
    val hasMention: Boolean = false,
    val firstUnreadMessageId: String? = null,
    val isTaskConversation: Boolean = false,
    val taskNumber: Int? = null,
    val taskStatus: TaskStatus? = null,
    val taskClaimedByName: String? = null,
)

/** Known parent channel metadata (web `KnownParentChannel`). */
@RaftCoreInternalApi data class ActivityKnownParentChannel(
    val id: String,
    val name: String,
    val type: String,
    val archived: Boolean = false,
)

/** Known parent message author info (web `UnfollowedReplyFacts.parent`). */
@RaftCoreInternalApi data class ActivityKnownParentMessage(
    val senderId: String,
    val senderKind: MemberKind,
    val messageType: MessageType = MessageType.Chat,
)

/** Web `KnownThreadFollow`. */
@RaftCoreInternalApi enum class ActivityKnownThreadFollow(val wireValue: String) {
    Followed("followed"),
    UnfollowedHere("unfollowed_here"),
    NotFollowed("not_followed"),
    Unknown("unknown"),
}

/** Local client context for an unlisted `thread:updated` frame. */
@RaftCoreInternalApi data class ActivityUnlistedThreadContext(
    val follow: ActivityKnownThreadFollow = ActivityKnownThreadFollow.Unknown,
    val followed: ActivityKnownFollowedThread? = null,
    val parentChannel: ActivityKnownParentChannel? = null,
    val parentMessage: ActivityKnownParentMessage? = null,
)

/** Outcome of [buildInsertedThreadRow]. */
@RaftCoreInternalApi sealed interface ActivityThreadInsertResult {
    data class Inserted(val row: InboxItem, val state: ActivityRowState) : ActivityThreadInsertResult
    data class Refused(val reason: String) : ActivityThreadInsertResult
}

/** Why an unfollowed thread reply may still list (web `UnfollowedListingCause`); null = cannot list. */
@RaftCoreInternalApi enum class ActivityUnfollowedListingCause(val wireValue: String) {
    FollowUnknown("follow_unknown"),
    Followed("followed"),
    Joint("joint"),
    System("system"),
    OwnReply("own_reply"),
    Mentioned("mentioned"),
    DmParticipant("dm_participant"),
    ParentUnknown("parent_unknown"),
    Authored("authored"),
    Overlay("overlay"),
}

/**
 * Pure port of web `buildInsertedThreadRow` (`inboxInsertedThreadRow.ts`): constructs the inserted
 * thread [InboxItem] and its initial [ActivityRowState] from the incoming [event], [reply], and
 * locally known [followed] + [parentChannel] metadata, failing closed with a refusal reason
 * whenever any required fact is missing or ambiguous.
 */
@RaftCoreInternalApi fun buildInsertedThreadRow(
    event: ThreadUpdated,
    reply: Message?,
    viewerId: String?,
    readOnArrival: Boolean,
    followed: ActivityKnownFollowedThread?,
    parentChannel: ActivityKnownParentChannel?,
    peopleActivityRows: Boolean = false,
): ActivityThreadInsertResult {
    if (reply == null || reply.channelId != event.threadChannelId) {
        return ActivityThreadInsertResult.Refused("no_reply")
    }
    val seq = reply.seq?.takeIf { it >= 0L }?.toString()
    if (seq == null || !isCanonicalActivitySeq(seq)) {
        return ActivityThreadInsertResult.Refused("no_seq")
    }
    val parentChannelId = event.parentChannelId?.value?.takeIf { it.isNotBlank() }
    val parentChannelType = event.parentChannelType?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    val replyChannelType = event.latestReplyChannelType?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    if (replyChannelType != "thread" || parentChannelId == null) {
        return ActivityThreadInsertResult.Refused("not_thread")
    }
    if (parentChannelType == null || parentChannelType !in PlainActivityParentChannelTypes) {
        return ActivityThreadInsertResult.Refused("joint")
    }
    if (followed == null) {
        return ActivityThreadInsertResult.Refused("not_followed")
    }
    if (
        followed.threadChannelId != event.threadChannelId.value ||
        followed.parentMessageId != event.parentMessageId.value ||
        followed.parentChannelId != parentChannelId ||
        followed.parentChannelType != parentChannelType ||
        followed.isTaskConversation ||
        followed.parentMessageSenderKind !in ValidInsertedParentSenderKinds
    ) {
        return ActivityThreadInsertResult.Refused("followed_mismatch")
    }
    if (followed.parentInHistory != true) {
        return ActivityThreadInsertResult.Refused("parent_history")
    }
    if (
        parentChannel == null ||
        parentChannel.id != parentChannelId ||
        parentChannel.type != parentChannelType ||
        parentChannel.archived
    ) {
        return ActivityThreadInsertResult.Refused("parent_unknown")
    }
    val cleanViewerId = viewerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return ActivityThreadInsertResult.Refused("unjudged")
    val own = reply.author.kind == MemberKind.Human && reply.author.id.value == cleanViewerId
    if (own && reply.mentions.isNotEmpty()) {
        return ActivityThreadInsertResult.Refused("own_mentions")
    }
    if (reply.messageType == MessageType.System || event.latestReplyMessageType == MessageType.System) {
        return ActivityThreadInsertResult.Refused("system")
    }
    val verdict =
        activityLiveUnread(
            message = reply.toActivityUnreadMessage(provenance = null),
            viewer = ActivityUnreadViewer(type = "user", id = cleanViewerId),
            scope =
                ActivityUnreadScope(
                    rowKind = ActivityUnreadRowKind.Thread,
                    threadFollowed = true,
                    activityMuted = false,
                    readOnArrival = readOnArrival,
                ),
            rules = ActivityUnreadRules(peopleActivityRows = peopleActivityRows),
        )
    if (verdict.counts == null || !verdict.moves) {
        return ActivityThreadInsertResult.Refused("unjudged")
    }
    val base = followed.latestActivitySeq
    if (base == null || !isCanonicalActivitySeq(base)) {
        return ActivityThreadInsertResult.Refused("no_position")
    }
    if (compareActivitySeq(seq, base) <= 0) {
        return ActivityThreadInsertResult.Refused("not_newer")
    }
    if (event.replyCount != followed.replyCount + 1) {
        return ActivityThreadInsertResult.Refused("reply_gap")
    }
    val beforeUnread = followed.unreadCount.coerceAtLeast(0)
    val beforeMention = beforeUnread > 0 && followed.hasMention
    val unreadCount: Int
    val hasMention: Boolean
    val firstUnreadMessageId: MessageId?
    val firstMentionMessageId: MessageId?
    when {
        own -> {
            unreadCount = 0
            hasMention = false
            firstUnreadMessageId = null
            firstMentionMessageId = null
        }
        verdict.counts == true -> {
            val first =
                if (beforeUnread == 0) {
                    reply.id
                } else {
                    followed.firstUnreadMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId)
                } ?: return ActivityThreadInsertResult.Refused("unread_unknown")
            if (beforeMention) return ActivityThreadInsertResult.Refused("mention_unknown")
            unreadCount = beforeUnread + 1
            hasMention = verdict.mention
            firstUnreadMessageId = first
            firstMentionMessageId = if (verdict.mention) reply.id else null
        }
        else -> {
            if (beforeUnread != 0 || beforeMention) return ActivityThreadInsertResult.Refused("unread_unknown")
            unreadCount = 0
            hasMention = false
            firstUnreadMessageId = null
            firstMentionMessageId = null
        }
    }
    val parentKind =
        when (parentChannelType) {
            "dm" -> ConversationKind.DirectMessage
            else -> ConversationKind.Channel
        }
    val activityAt = reply.timestampLabel.takeIf { it.isNotBlank() } ?: event.lastReplyAt
    val row =
        InboxItem(
            key = "thread:${event.threadChannelId.value}",
            kind = InboxItemKind.Thread,
            channelId = event.threadChannelId,
            channelName = followed.parentChannelName.ifBlank { parentChannel.name },
            channelKind = parentKind,
            channelType = parentChannelType,
            unreadCount = unreadCount,
            hasMention = hasMention,
            firstUnreadMessageId = firstUnreadMessageId,
            firstMentionMessageId = firstMentionMessageId,
            threadChannelId = event.threadChannelId,
            parentChannelId = ChannelId(parentChannelId),
            parentChannelName = followed.parentChannelName.ifBlank { parentChannel.name },
            parentChannelKind = parentKind,
            parentChannelType = parentChannelType,
            parentMessageId = event.parentMessageId,
            parentMessagePreview = followed.parentMessagePreview,
            parentMessageSenderId = followed.parentMessageSenderId,
            parentMessageSenderKind = followed.parentMessageSenderKind,
            parentInHistory = true,
            latestActivityMessageId = reply.id,
            latestActivityPreview = reply.body,
            latestActivityAt = activityAt,
            latestActivitySenderName = reply.shownAuthorName(),
            latestActivitySenderId = reply.author.id.value,
            latestActivitySenderKind = reply.author.kind,
            latestActivitySenderAvatarWireValue = reply.author.avatar?.wireValue ?: event.latestReply?.senderAvatarUrl,
            latestDelegatedBy = reply.delegatedBy ?: event.latestReplyDelegatedBy,
            latestRetracted = event.latestReplyRetracted || !reply.deletedAt.isNullOrBlank(),
            replyCount = event.replyCount,
            participantIds = event.participantIds,
            taskNumber = followed.taskNumber,
            taskStatus = followed.taskStatus,
            taskClaimedByName = followed.taskClaimedByName,
            isFollowing = true,
            unfollowedAt = null,
            latestActivitySeq = seq,
            doneFrontierSeq = seq,
            doneFrontierSeqPresent = true,
            readStateLatestActivitySeq = seq,
        )
    val counts = !own && verdict.counts == true
    val state =
        ActivityRowState(
            unreadCount = row.unreadCount,
            hasMention = row.hasMention,
            mentionOnly = false,
            position = base,
            applied = listOf(ActivityRowAppliedSeq(seq = seq, counts = counts, mention = !own && verdict.mention)),
            inversionObserved = false,
        )
    return ActivityThreadInsertResult.Inserted(row = row, state = state)
}

/**
 * Pure port of web `insertedRowIndex` (`inboxInsertedThreadRow.ts`): newest-first placement by row
 * time (object & mention-action rows sort last). Returns null when the row sorts below every loaded
 * row while [hasMore] is true (belongs past the loaded window).
 */
@RaftCoreInternalApi fun insertedActivityRowIndex(items: List<InboxItem>, row: InboxItem, hasMore: Boolean): Int? {
    val rowAt = activityRowPlaceAtMillis(row) ?: return null
    val index = items.indexOfFirst { other -> (activityRowPlaceAtMillis(other) ?: Long.MIN_VALUE) < rowAt }
    if (index >= 0) return index
    return if (hasMore) null else items.size
}

private fun activityRowPlaceAtMillis(item: InboxItem): Long? {
    if (item.kind == InboxItemKind.MentionAction || item.kind == InboxItemKind.Object) return null
    return parseRaftTimestampEpochMillisOrNull(item.activityRowTimeValue())
}

/**
 * Pure port of web `threadReplyMayListUnfollowed` (`inboxUnfollowedThreadReply.ts`): returns the
 * reason the server's Activity query could still list/move the thread for [viewerId] despite not
 * being followed, or null when the thread cannot list and its re-read should be skipped.
 */
@RaftCoreInternalApi fun threadReplyMayListUnfollowed(
    viewerId: String?,
    reply: Message,
    follow: ActivityKnownThreadFollow,
    parentChannelType: String?,
    replyChannelType: String?,
    parentMessage: ActivityKnownParentMessage?,
    filter: InboxFilter,
    listComplete: Boolean,
): ActivityUnfollowedListingCause? {
    val cleanViewerId = viewerId?.trim()?.takeIf { it.isNotEmpty() }
    if (cleanViewerId == null || follow == ActivityKnownThreadFollow.Unknown) {
        return ActivityUnfollowedListingCause.FollowUnknown
    }
    if (follow == ActivityKnownThreadFollow.Followed) {
        return ActivityUnfollowedListingCause.Followed
    }
    val normalizedReplyType = replyChannelType?.trim()?.lowercase()
    val normalizedParentType = parentChannelType?.trim()?.lowercase()
    if (normalizedReplyType != "thread" || normalizedParentType == null || normalizedParentType !in PlainActivityParentChannelTypes) {
        return ActivityUnfollowedListingCause.Joint
    }
    if (
        reply.messageType == MessageType.System ||
        reply.author.kind == MemberKind.System ||
        reply.author.id.value == "system"
    ) {
        return ActivityUnfollowedListingCause.System
    }
    if (reply.author.kind == MemberKind.Human && reply.author.id.value == cleanViewerId) {
        return ActivityUnfollowedListingCause.OwnReply
    }
    if (reply.mentions.any { mention -> mention.type == "user" && mention.id == cleanViewerId }) {
        return ActivityUnfollowedListingCause.Mentioned
    }
    if (normalizedParentType == "dm" && follow == ActivityKnownThreadFollow.NotFollowed) {
        return ActivityUnfollowedListingCause.DmParticipant
    }
    if (parentMessage == null) {
        return ActivityUnfollowedListingCause.ParentUnknown
    }
    if (
        parentMessage.messageType != MessageType.System &&
        parentMessage.senderKind == MemberKind.Human &&
        parentMessage.senderId == cleanViewerId
    ) {
        return ActivityUnfollowedListingCause.Authored
    }
    if (filter == InboxFilter.All && (follow == ActivityKnownThreadFollow.UnfollowedHere || !listComplete)) {
        return ActivityUnfollowedListingCause.Overlay
    }
    return null
}

/** Captures a listed/Done followed thread [InboxItem] into local metadata for future live insertion. */
@RaftCoreInternalApi fun InboxItem.toActivityKnownFollowedThread(unreadCleared: Boolean = false): ActivityKnownFollowedThread? {
    if (kind != InboxItemKind.Thread || isTaskConversation || isFollowing == false) return null
    val threadId = (threadChannelId ?: channelId).value.takeIf { it.isNotBlank() } ?: return null
    val parentMsgId = parentMessageId?.value?.takeIf { it.isNotBlank() } ?: return null
    val parentChanId = parentChannelId?.value?.takeIf { it.isNotBlank() } ?: return null
    val parentType =
        parentChannelType?.trim()?.lowercase()?.takeIf { it in PlainActivityParentChannelTypes }
            ?: when (parentChannelKind) {
                ConversationKind.DirectMessage -> "dm"
                else -> null
            }
            ?: return null
    return ActivityKnownFollowedThread(
        threadChannelId = threadId,
        parentMessageId = parentMsgId,
        parentChannelId = parentChanId,
        parentChannelName = parentChannelName?.takeIf { it.isNotBlank() } ?: channelName,
        parentChannelType = parentType,
        parentMessagePreview = parentMessagePreview,
        parentMessageSenderId = parentMessageSenderId,
        parentMessageSenderKind = parentMessageSenderKind,
        parentInHistory = parentInHistory,
        latestActivitySeq = latestActivitySeq,
        replyCount = replyCount,
        unreadCount = if (unreadCleared) 0 else unreadCount,
        hasMention = if (unreadCleared) false else hasMention,
        firstUnreadMessageId = if (unreadCleared) null else firstUnreadMessageId?.value,
        isTaskConversation = isTaskConversation,
        taskNumber = taskNumber,
        taskStatus = taskStatus,
        taskClaimedByName = taskClaimedByName,
    )
}
