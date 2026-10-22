package build.raft.app

import build.raft.app.data.HomeFollowedThreadUnread

/*
 * The conversation's jump pills, as on the web (botiverse/slock packages/web/src/components/
 * message/ChatPanel.tsx `bottomButton`, ThreadUnreadJumpPill.tsx, hooks/useMessagePlacement.ts,
 * utils/threadUnreadByParent.ts) drawn with raft-ui's MessageList jump controls
 * (RaftMessageListJumpPill / Group / Dismiss):
 *
 *  - bottom: "Back to Bottom" while the end of the conversation is out of view; "N New Messages"
 *    once messages arrived below since the reader left it (or, with newer messages not loaded,
 *    the conversation's unread count);
 *  - beside it: "Next Unread Thread (N)" when a followed thread with unread replies has its parent
 *    message below the view; else, alone at the top, "Earliest Unread Thread (N)" when one is
 *    above. N counts every unread followed thread of the channel. A tap focuses (scrolls to and
 *    highlights) that parent; ✕ hides the pill until N changes.
 *
 * The decisions are pure functions here; ThreadPage only feeds them and draws the result.
 */

/** Where a message row is relative to what the list shows (web `MessagePlacement`). */
internal enum class ThreadJumpPlacement { Above, Visible, Below }

/** Web `placeByIndex`: a message placed by its index against the indexes of the rows in view. */
internal fun threadJumpPlacement(messageIndex: Int, visibleMessageIndexes: IntRange): ThreadJumpPlacement =
    when {
        messageIndex < visibleMessageIndexes.first -> ThreadJumpPlacement.Above
        messageIndex > visibleMessageIndexes.last -> ThreadJumpPlacement.Below
        else -> ThreadJumpPlacement.Visible
    }

/** One laid-out list row: its forward list index and its extent along the list. */
internal data class ThreadJumpVisibleRow(val listIndex: Int, val offset: Int, val size: Int)

/**
 * The message indexes (into the payload's messages) of the rows that intersect the viewport
 * [viewportStart]..[viewportEnd], or null when no message row is in view. Rows laid out
 * beyond the viewport (Compose's beyond-bounds items) and non-message rows are ignored.
 */
internal fun threadVisibleMessageIndexRange(
    rows: List<ThreadJumpVisibleRow>,
    viewportStart: Int,
    viewportEnd: Int,
    firstMessageListItemIndex: Int,
    messageCount: Int,
): IntRange? {
    if (messageCount <= 0) return null
    val lastMessageListItemIndex = firstMessageListItemIndex + messageCount - 1
    var first = Int.MAX_VALUE
    var last = Int.MIN_VALUE
    for (row in rows) {
        if (row.listIndex !in firstMessageListItemIndex..lastMessageListItemIndex) continue
        if (row.offset + row.size <= viewportStart || row.offset >= viewportEnd) continue
        val messageIndex = row.listIndex - firstMessageListItemIndex
        if (messageIndex < first) first = messageIndex
        if (messageIndex > last) last = messageIndex
    }
    return if (first == Int.MAX_VALUE) null else first..last
}

/** A loaded message that may be the parent of a thread. */
internal data class ThreadJumpParentCandidate(
    val messageId: String,
    val threadChannelId: String?,
    val threadFollowed: Boolean,
    val unreadReplyCount: Int,
)

internal fun RaftThreadMessageItem.toThreadJumpParentCandidate(): ThreadJumpParentCandidate =
    ThreadJumpParentCandidate(
        messageId = id,
        threadChannelId = threadChannelId,
        threadFollowed = isThreadFollowed,
        unreadReplyCount = unreadReplyCount,
    )

/**
 * The thread channel ids of the channel's followed threads with unread replies.
 *
 * The source is Home's followed-thread unread ([homeFollowedThreadUnread], the same list the
 * channel row's thread mark is folded from, so the row and the pill never disagree, as the web
 * derives both from its followed-thread list). Until Home has loaded ([homeFollowedThreadUnread]
 * null) the loaded parents' own reply state stands in: followed, with unread replies.
 */
internal fun threadConversationUnreadThreadIds(
    channelId: String,
    homeFollowedThreadUnread: Map<String, HomeFollowedThreadUnread>?,
    loadedMessages: List<ThreadJumpParentCandidate>,
): Set<String> {
    if (homeFollowedThreadUnread != null) {
        return homeFollowedThreadUnread
            .filter { (_, thread) -> thread.parentChannelId.value == channelId && thread.unreadCount > 0 }
            .keys
    }
    return loadedMessages
        .filter { it.threadFollowed && it.unreadReplyCount > 0 }
        .mapNotNull { it.threadChannelId?.takeIf(String::isNotBlank) }
        .toSet()
}

internal enum class ThreadUnreadThreadJumpDirection {
    /** At the bottom, beside Back to Bottom: "Next Unread Thread (N)", the nearest one below. */
    Next,

    /** Alone at the top: "Earliest Unread Thread (N)", the topmost one above. */
    Earliest,
}

internal data class ThreadUnreadThreadJump(
    val direction: ThreadUnreadThreadJumpDirection,
    /** Every unread followed thread of the channel (threads, not replies). */
    val count: Int,
    /** The parent message to focus. */
    val targetMessageId: String,
)

/**
 * Web `ThreadUnreadJumpPill`: which unread-thread pill shows, if any.
 *
 * [messages] are the loaded messages top to bottom; [visibleMessageIndexes] the indexes of the
 * rows in view. A parent in view already shows its unread replies, so it is neither above nor
 * below. The nearest parent below wins ("Next"); only when none is below does the topmost one
 * above show ("Earliest"), so repeated taps walk the channel top to bottom. A thread whose parent
 * is not loaded still counts in N but cannot be a target here (Home's summary carries no parent
 * message id). [dismissedAtCount] hides the pill while N stays the count it was dismissed at.
 */
internal fun projectThreadUnreadThreadJump(
    unreadThreadIds: Set<String>,
    messages: List<ThreadJumpParentCandidate>,
    visibleMessageIndexes: IntRange?,
    dismissedAtCount: Int?,
): ThreadUnreadThreadJump? {
    val count = unreadThreadIds.size
    if (count == 0 || dismissedAtCount == count || visibleMessageIndexes == null) return null
    var nearestBelow: String? = null
    var earliestAbove: String? = null
    val seenThreads = HashSet<String>()
    for ((index, message) in messages.withIndex()) {
        val threadId = message.threadChannelId?.takeIf { it in unreadThreadIds } ?: continue
        if (!seenThreads.add(threadId)) continue
        when (threadJumpPlacement(index, visibleMessageIndexes)) {
            ThreadJumpPlacement.Above -> if (earliestAbove == null) earliestAbove = message.messageId
            ThreadJumpPlacement.Below -> if (nearestBelow == null) nearestBelow = message.messageId
            ThreadJumpPlacement.Visible -> Unit
        }
        if (nearestBelow != null) break
    }
    return when {
        nearestBelow != null -> ThreadUnreadThreadJump(ThreadUnreadThreadJumpDirection.Next, count, nearestBelow)
        earliestAbove != null -> ThreadUnreadThreadJump(ThreadUnreadThreadJumpDirection.Earliest, count, earliestAbove)
        else -> null
    }
}

/** A loaded message, for counting the ones that arrive below the reader. */
internal data class ThreadJumpArrival(
    val messageId: String,
    /** False for the reader's own local send (it takes them to the bottom anyway). */
    val countable: Boolean,
)

/**
 * Web ChatPanel `newMessageCount`: messages appended below while the reader is away from the
 * bottom. [lastMessageId] is the newest loaded message when this was last reduced.
 */
internal data class ThreadNewMessagesBelow(
    val count: Int = 0,
    val lastMessageId: String? = null,
)

/** Web: an append of more than this many rows at once is a page load, not new messages. */
internal const val ThreadNewMessagesBelowMaxBatch = 5

/**
 * Reduces [state] for the current [messages] (top to bottom).
 *
 *  - [awayFromBottom] false (the Back to Bottom pill is hidden: the reader follows the latest
 *    messages) clears the count;
 *  - rows appended after the previous newest message, at most [ThreadNewMessagesBelowMaxBatch]
 *    at once, add to it while the reader is away;
 *  - with newer messages not loaded ([canLoadMoreAfter]) appends are newer pages, not arrivals;
 *  - a list whose previous newest message is gone (replaced, reloaded) rebases without counting.
 */
internal fun reduceThreadNewMessagesBelow(
    state: ThreadNewMessagesBelow,
    messages: List<ThreadJumpArrival>,
    awayFromBottom: Boolean,
    canLoadMoreAfter: Boolean,
): ThreadNewMessagesBelow {
    val newest = messages.lastOrNull()?.messageId
    if (!awayFromBottom) return ThreadNewMessagesBelow(count = 0, lastMessageId = newest)
    val previous = state.lastMessageId
    if (canLoadMoreAfter || previous == null || previous == newest) return state.copy(lastMessageId = newest)
    val previousIndex = messages.indexOfLast { it.messageId == previous }
    if (previousIndex < 0) return state.copy(lastMessageId = newest)
    val appended = messages.subList(previousIndex + 1, messages.size)
    val arrived = appended.count { it.countable }
    return ThreadNewMessagesBelow(
        count = if (appended.size <= ThreadNewMessagesBelowMaxBatch) state.count + arrived else state.count,
        lastMessageId = newest,
    )
}

/**
 * Web `bottomButtonCount = hasNewer ? currentUnreadCount : newMessageCount`: with newer messages
 * not loaded, the conversation's unread count; otherwise the arrivals since leaving the bottom.
 * 0 reads "Back to Bottom".
 */
internal fun threadBottomJumpCount(
    canLoadMoreAfter: Boolean,
    conversationUnreadCount: Int,
    newMessagesBelow: Int,
): Int = (if (canLoadMoreAfter) conversationUnreadCount else newMessagesBelow).coerceAtLeast(0)

/**
 * What the conversation page hands its message list for the jump pills. [unreadThreadPillEnabled]
 * is a channel (the web shows the unread-thread pill in channels only, not DMs or threads) on a
 * server with `thread_unread_badge_v0` on; [homeFollowedThreadUnread] is Home's followed-thread
 * unread of this channel only (null until Home has this server loaded); [conversationUnreadCount]
 * is Home's unread count of this conversation.
 */
internal data class ThreadJumpInputs(
    val channelId: String? = null,
    val unreadThreadPillEnabled: Boolean = false,
    val homeFollowedThreadUnread: Map<String, HomeFollowedThreadUnread>? = null,
    val conversationUnreadCount: Int = 0,
)

/** The loaded messages as thread-parent candidates, with the list's live reply state where it has one. */
internal fun threadJumpParentCandidates(
    messages: List<RaftThreadMessageItem>,
    replyMetadataByParentId: Map<String, ThreadReplyMetadata>,
): List<ThreadJumpParentCandidate> =
    messages.map { message ->
        val metadata = replyMetadataByParentId[message.id]
        if (metadata == null) {
            message.toThreadJumpParentCandidate()
        } else {
            ThreadJumpParentCandidate(
                messageId = message.id,
                threadChannelId = metadata.threadChannelId ?: message.threadChannelId,
                threadFollowed = metadata.isThreadFollowed,
                unreadReplyCount = metadata.unreadReplyCount,
            )
        }
    }
