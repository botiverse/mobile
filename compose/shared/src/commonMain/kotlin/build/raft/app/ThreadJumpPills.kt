package build.raft.app

import build.raft.app.data.HomeFollowedThreadUnread
import build.raft.app.data.InboxItem
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull

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
 *    above. N counts every unread followed thread of the channel. A parent that is not loaded is
 *    resolved from the followed-threads list and placed above (older) or below (newer) the loaded
 *    messages. A tap focuses (scrolls to and highlights) that parent, loading the messages around
 *    it first when it is not loaded; ✕ hides the pill until N changes.
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
    /**
     * The unread thread under [targetMessageId]: the jump prewarms its replies in the background
     * (artin: opening the thread right after the jump renders at once).
     */
    val targetThreadChannelId: String,
)

/**
 * An unread followed thread's parent message as the followed-threads list names it (web
 * `FollowedThread.parentMessageId` / `parentMessageCreatedAt`): how the pill reaches a parent the
 * page has not loaded. Home's unread summary carries no parent message, so the page resolves these
 * through its store (`SharedThreadIntent.ResolveUnreadThreadParents`).
 */
internal data class ThreadUnreadThreadParent(
    val threadChannelId: String,
    val parentChannelId: String,
    val parentMessageId: String,
    /** When the parent was posted; null from an older server. */
    val parentCreatedAtEpochMillis: Long? = null,
)

/**
 * The followed-threads rows of [parentChannelId] with unread replies, as pill parents by thread id
 * (web `orderParentUnreadThreadsForJump`'s filter). Rows without a parent message are skipped.
 */
internal fun List<InboxItem>.toThreadUnreadThreadParents(parentChannelId: String): Map<String, ThreadUnreadThreadParent> {
    val parents = LinkedHashMap<String, ThreadUnreadThreadParent>()
    for (item in this) {
        if (item.unreadCount <= 0 || item.parentChannelId?.value != parentChannelId) continue
        val threadId = (item.threadChannelId ?: item.channelId).value.takeIf { it.isNotBlank() } ?: continue
        val parentMessageId = item.parentMessageId?.value?.takeIf { it.isNotBlank() } ?: continue
        parents[threadId] =
            ThreadUnreadThreadParent(
                threadChannelId = threadId,
                parentChannelId = parentChannelId,
                parentMessageId = parentMessageId,
                parentCreatedAtEpochMillis = item.parentMessageCreatedAt?.let(::parseRaftTimestampEpochMillisOrNull),
            )
    }
    return parents
}

/** Web `LoadedMessageRange`: where the loaded messages end, to place a parent that is not loaded. */
internal data class ThreadJumpLoadedRange(
    /** createdAt of the newest loaded message, if known. */
    val newestCreatedAtEpochMillis: Long?,
    /** Newer messages exist past the loaded ones (reading history). */
    val hasNewer: Boolean,
)

/**
 * Web `placeUnrendered`: a parent that is not loaded is below only while newer messages are not
 * loaded and it is newer than the newest loaded one; otherwise it is in the older history above.
 */
internal fun threadUnloadedParentPlacement(
    parentCreatedAtEpochMillis: Long?,
    range: ThreadJumpLoadedRange,
): ThreadJumpPlacement {
    val newest = range.newestCreatedAtEpochMillis
    return if (range.hasNewer && parentCreatedAtEpochMillis != null && newest != null && parentCreatedAtEpochMillis > newest) {
        ThreadJumpPlacement.Below
    } else {
        ThreadJumpPlacement.Above
    }
}

/**
 * The unread threads whose parent is neither loaded (no loaded message carries the thread) nor
 * already resolved in [resolvedParents]: the ones the page must look up for the pill to reach them.
 */
internal fun threadUnreadThreadIdsNeedingParents(
    unreadThreadIds: Set<String>,
    messages: List<ThreadJumpParentCandidate>,
    resolvedParents: Map<String, ThreadUnreadThreadParent>,
): Set<String> {
    if (unreadThreadIds.isEmpty()) return emptySet()
    val loadedThreadIds = messages.mapNotNullTo(HashSet()) { it.threadChannelId }
    return unreadThreadIds.filterTo(LinkedHashSet()) { it !in loadedThreadIds && it !in resolvedParents }
}

/**
 * Web `ThreadUnreadJumpPill`: which unread-thread pill shows, if any.
 *
 * [messages] are the loaded messages top to bottom; [visibleMessageIndexes] the indexes of the
 * rows in view. A parent in view already shows its unread replies, so it is neither above nor
 * below. A parent that is not loaded is reached through [unloadedParents] (the followed-threads
 * list) and placed by [loadedRange] ([threadUnloadedParentPlacement]): one older than the loaded
 * messages is above all of them, one newer is below all of them. The nearest parent below wins
 * ("Next"); only when none is below does the topmost one above show ("Earliest"), so repeated taps
 * walk the channel top to bottom. A thread whose parent is neither loaded nor resolved still counts
 * in N but is no target until it resolves. [dismissedAtCount] hides the pill while N stays the
 * count it was dismissed at.
 */
internal fun projectThreadUnreadThreadJump(
    unreadThreadIds: Set<String>,
    messages: List<ThreadJumpParentCandidate>,
    visibleMessageIndexes: IntRange?,
    dismissedAtCount: Int?,
    unloadedParents: Map<String, ThreadUnreadThreadParent> = emptyMap(),
    loadedRange: ThreadJumpLoadedRange = ThreadJumpLoadedRange(newestCreatedAtEpochMillis = null, hasNewer = false),
): ThreadUnreadThreadJump? {
    val count = unreadThreadIds.size
    if (count == 0 || dismissedAtCount == count || visibleMessageIndexes == null) return null
    val messageIndexById = HashMap<String, Int>(messages.size)
    messages.forEachIndexed { index, message -> messageIndexById.getOrPut(message.messageId) { index } }
    // Each unread thread's loaded parent index: by the thread the row carries, else by the
    // resolved parent id (a row whose thread metadata has not arrived yet).
    val loadedIndexByThread = HashMap<String, Int>()
    for ((index, message) in messages.withIndex()) {
        val threadId = message.threadChannelId?.takeIf { it in unreadThreadIds } ?: continue
        loadedIndexByThread.getOrPut(threadId) { index }
    }
    var nearestBelowIndex: Int? = null
    var earliestAboveIndex: Int? = null
    // The unread thread each loaded candidate index stands for (the pill prewarms its replies).
    val threadByIndex = HashMap<Int, String>()
    // Unloaded parents, ordered by parent time (unknown last), as web orders the followed list.
    var unloadedBelow: ThreadUnreadThreadParent? = null
    var unloadedAbove: ThreadUnreadThreadParent? = null
    fun ThreadUnreadThreadParent.isEarlierThan(other: ThreadUnreadThreadParent?): Boolean =
        other == null ||
            (parentCreatedAtEpochMillis ?: Long.MAX_VALUE) < (other.parentCreatedAtEpochMillis ?: Long.MAX_VALUE)
    for (threadId in unreadThreadIds) {
        val parent = unloadedParents[threadId]
        val index = loadedIndexByThread[threadId] ?: parent?.let { messageIndexById[it.parentMessageId] }
        if (index != null) {
            threadByIndex.getOrPut(index) { threadId }
            when (threadJumpPlacement(index, visibleMessageIndexes)) {
                ThreadJumpPlacement.Above ->
                    if (earliestAboveIndex == null || index < earliestAboveIndex) earliestAboveIndex = index
                ThreadJumpPlacement.Below ->
                    if (nearestBelowIndex == null || index < nearestBelowIndex) nearestBelowIndex = index
                ThreadJumpPlacement.Visible -> Unit
            }
            continue
        }
        if (parent == null) continue
        when (threadUnloadedParentPlacement(parent.parentCreatedAtEpochMillis, loadedRange)) {
            ThreadJumpPlacement.Below -> if (parent.isEarlierThan(unloadedBelow)) unloadedBelow = parent
            else -> if (parent.isEarlierThan(unloadedAbove)) unloadedAbove = parent
        }
    }
    // A loaded parent below is nearer than any newer unloaded one; an unloaded parent above is
    // older (higher) than every loaded one.
    fun loadedTarget(index: Int) = messages[index].messageId to threadByIndex.getValue(index)
    fun unloadedTarget(parent: ThreadUnreadThreadParent) = parent.parentMessageId to parent.threadChannelId
    val nextTarget = nearestBelowIndex?.let(::loadedTarget) ?: unloadedBelow?.let(::unloadedTarget)
    val earliestTarget = unloadedAbove?.let(::unloadedTarget) ?: earliestAboveIndex?.let(::loadedTarget)
    return when {
        nextTarget != null ->
            ThreadUnreadThreadJump(ThreadUnreadThreadJumpDirection.Next, count, nextTarget.first, nextTarget.second)
        earliestTarget != null ->
            ThreadUnreadThreadJump(ThreadUnreadThreadJumpDirection.Earliest, count, earliestTarget.first, earliestTarget.second)
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
    // Task #78: the reader's own local sends (sending / failed) sit below every server message; they
    // are not arrivals and must not hide the server messages that land above them, so the frontier
    // is the newest countable message (the loaded tail only when every row is local).
    val newest = (messages.lastOrNull { it.countable } ?: messages.lastOrNull())?.messageId
    if (!awayFromBottom) return ThreadNewMessagesBelow(count = 0, lastMessageId = newest)
    val previous = state.lastMessageId
    if (canLoadMoreAfter || previous == null || previous == newest) return state.copy(lastMessageId = newest)
    val previousIndex = messages.indexOfLast { it.messageId == previous }
    if (previousIndex < 0) return state.copy(lastMessageId = newest)
    val arrived = messages.subList(previousIndex + 1, messages.size).count { it.countable }
    return ThreadNewMessagesBelow(
        count = if (arrived <= ThreadNewMessagesBelowMaxBatch) state.count + arrived else state.count,
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
    /** Parents of this channel's unread threads resolved from the followed-threads list, by thread id. */
    val unreadThreadParents: Map<String, ThreadUnreadThreadParent> = emptyMap(),
    /** The focus request nonce of the last pill jump ([SharedThreadState.unreadThreadJumpFocusNonce]). */
    val jumpFocusNonce: Int = 0,
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
