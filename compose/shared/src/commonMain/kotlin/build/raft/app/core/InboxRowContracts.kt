package build.raft.app

import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId

/** The time an Activity row shows, which is the time it is grouped by (web `inboxItemTime`). */
internal fun InboxItem.activityRowTimeValue(): String? = latestActivityAt ?: lastMessageAt

/** Web ThreadsInbox `jointRow`: a thread of a joint channel, or a joint channel row. */
internal fun InboxItem.isJointActivityRow(): Boolean =
    when (kind) {
        InboxItemKind.Thread ->
            parentChannelKind == ConversationKind.Joint || parentChannelType.equals("joint", ignoreCase = true)
        InboxItemKind.MentionAction -> false
        else -> channelKind == ConversationKind.Joint || channelType.equals("joint", ignoreCase = true)
    }

/**
 * Per-kind stream channel id resolver. Replaces the prior silent fallback
 * `if (kind == Thread) threadChannelId else threadChannelId ?: channelId` (反例 1 in #280 audit).
 *
 * - Thread items resolve to their explicit `threadChannelId` (v0.2 contract requires the
 *   thread stream id to be distinct from `channelId`).
 * - Non-thread items resolve to `null`. The downstream `ThreadConversation` field is only
 *   populated for Thread items, so this is the safe default.
 *
 * Future sealed `InboxItem.Thread` (Phase 3 #3 (B)) will encode this in the type system:
 * `threadChannelId` becomes a required non-null constructor parameter on the Thread subclass.
 */
internal fun InboxItem.resolveInboxStreamChannelId(): ChannelId? =
    when (kind) {
        InboxItemKind.Thread -> threadChannelId
        InboxItemKind.Channel, InboxItemKind.DirectMessage, InboxItemKind.MentionAction, InboxItemKind.System, InboxItemKind.Object -> null
    }

/**
 * Open anchor used by Activity, Search, and Saved rows. This mirrors React's row-open
 * contract: `firstUnreadMessageId` is only an anchor while the row is actually unread.
 * Thread rows focus reply ids (`firstUnreadMessageId` / `latestActivityMessageId`) and
 * must not fall back to `lastMessageId` or `parentMessageId`, both of which can describe
 * the parent/origin rather than a thread reply.
 */
internal fun InboxItem.resolveInboxAnchorMessageId(): MessageId? =
    when (kind) {
        InboxItemKind.Object -> null
        InboxItemKind.Thread ->
            if (unreadCount > 0) {
                firstUnreadMessageId ?: latestActivityMessageId
            } else {
                latestActivityMessageId
            }
        InboxItemKind.MentionAction -> lastMessageId
        InboxItemKind.DirectMessage,
        InboxItemKind.Channel,
        InboxItemKind.System ->
            if (unreadCount > 0) {
                firstUnreadMessageId ?: lastMessageId
            } else {
                lastMessageId
            }
    }
