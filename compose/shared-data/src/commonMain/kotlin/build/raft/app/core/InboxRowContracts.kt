package build.raft.app

import build.raft.app.data.InboxItem
import build.raft.app.data.isTaskConversation
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId

/** The time an Activity row shows, which is the time it is grouped by (web `inboxItemTime`). */
@RaftCoreInternalApi fun InboxItem.activityRowTimeValue(): String? = latestActivityAt ?: lastMessageAt

/** Web ThreadsInbox `jointRow`: a thread of a joint channel, or a joint channel row. */
@RaftCoreInternalApi fun InboxItem.isJointActivityRow(): Boolean =
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
@RaftCoreInternalApi fun InboxItem.resolveInboxStreamChannelId(): ChannelId? =
    when (kind) {
        InboxItemKind.Thread -> threadChannelId
        InboxItemKind.Channel, InboxItemKind.DirectMessage, InboxItemKind.MentionAction, InboxItemKind.System, InboxItemKind.Object -> null
    }

/**
 * The reply an Activity thread or task-conversation row opens on for its unread
 * (botiverse/slock #16908 `activityThreadUnreadFocusId`): [InboxItem.firstUnreadMessageId] while
 * unread, else `null` so the thread opens at its latest replies. Never [InboxItem.latestActivityMessageId]:
 * it is the thread's newest row of any kind (the server's max seq, no visibility filter), such as
 * an agent's "stopped following this thread" line that people cannot read.
 */
@RaftCoreInternalApi fun InboxItem.activityThreadUnreadFocusId(): MessageId? =
    if (unreadCount > 0) firstUnreadMessageId else null

/**
 * Open anchor used by Activity, Search, and Saved rows. Mirrors React's row-open contract
 * (botiverse/slock #16908 `useThreadsInboxOpen.ts` / `inboxItem.ts`):
 * - Single-message rows (Saved / Search) carry [InboxItem.explicitFocusMessageId] and always focus
 *   that exact saved/matched message.
 * - Activity thread and task-conversation rows focus only a readable unread target
 *   ([activityThreadUnreadFocusId], plus `firstMentionMessageId` on task conversations when an
 *   unread mention is active). Read rows (`unreadCount == 0`) and unread rows without a known
 *   first unread reply return `null` so the thread opens cleanly at its latest replies without
 *   focusing `latestActivityMessageId`, `lastMessageId`, or `parentMessageId`.
 */
@RaftCoreInternalApi fun InboxItem.resolveInboxAnchorMessageId(): MessageId? =
    explicitFocusMessageId
        ?: when (kind) {
            InboxItemKind.Object -> null
            InboxItemKind.Thread ->
                if (isTaskConversation) {
                    firstMentionMessageId?.takeIf { unreadCount > 0 || hasMention } ?: activityThreadUnreadFocusId()
                } else {
                    activityThreadUnreadFocusId()
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
