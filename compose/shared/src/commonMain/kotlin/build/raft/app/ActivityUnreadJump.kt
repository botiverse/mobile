package build.raft.app

import build.raft.app.data.InboxItem

/**
 * artin 2026-10-21: while Activity is the selected tab, re-tapping its bottom-bar/rail tab or
 * tapping its head walks the unread rows of the visible list downward instead of only scrolling
 * to the top. After the last unread row the next tap returns to the very top; from the top the
 * next tap starts again at the first unread row. Nothing is marked read.
 *
 * Scroll ownership (docs/programmatic-scroll-ownership-contract.md): the intent owner is the
 * direct user tap (an explicit viewport replacement, like the title scroll-to-top); the
 * user-override fence is the scroll mutex (a drag cancels the animation) plus the per-tap
 * LaunchedEffect key (a newer tap cancels the older one). The decision is taken once per tap
 * from the live layout; nothing is re-issued from a changing index.
 */
internal sealed interface ActivityUnreadJumpTarget {
    /** No unread row at or below the current position: scroll to the very top. */
    data object Top : ActivityUnreadJumpTarget

    /** Scroll LazyColumn item [index] (row [key]) to the top of the list and mark it briefly. */
    data class Row(val index: Int, val key: String) : ActivityUnreadJumpTarget
}

/** One LazyColumn item in laid-out order; section labels and the footer are never unread. */
internal data class ActivityUnreadJumpEntry(val key: String, val unread: Boolean)

/**
 * Pure decision for one tap.
 *
 * [cursorKey] is the row the previous tap jumped to. While that row is still laid out, the walk
 * continues after it — the list may not have been able to bring it to the top (rows near the
 * end), so the first visible index alone would land on it again. Once it is off screen (the
 * reader scrolled away) the walk restarts from the viewport: the first unread row whose top is
 * at or below the viewport top. A first visible row that is partly scrolled out counts as above.
 */
internal fun activityNextUnreadJumpTarget(
    entries: List<ActivityUnreadJumpEntry>,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    visibleItemKeys: Set<String>,
    cursorKey: String?,
): ActivityUnreadJumpTarget {
    val cursorIndex =
        cursorKey
            ?.takeIf { it in visibleItemKeys }
            ?.let { key -> entries.indexOfFirst { it.key == key } }
            ?.takeIf { it >= 0 }
    val firstCandidate =
        when {
            cursorIndex != null -> cursorIndex + 1
            firstVisibleItemScrollOffset <= 0 -> firstVisibleItemIndex.coerceAtLeast(0)
            else -> firstVisibleItemIndex + 1
        }
    for (index in firstCandidate until entries.size) {
        val entry = entries[index]
        if (entry.unread) return ActivityUnreadJumpTarget.Row(index = index, key = entry.key)
    }
    return ActivityUnreadJumpTarget.Top
}

/**
 * The unread state each Activity row already displays: the card rows bold any unread row; the
 * conversation-list rows show the count only when the conversation is not muted.
 */
internal fun InboxItem.activityRowDisplaysUnread(conversationListContext: ActivityConversationListContext?): Boolean =
    unreadCount > 0 && (conversationListContext == null || !activityConversationMuted(conversationListContext))
