package build.raft.app

import build.raft.app.data.InboxItem
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * artin 2026-10-21: while Activity is the selected tab, re-tapping its bottom-bar/rail tab or
 * tapping its head walks the unread rows of the visible list downward instead of only scrolling
 * to the top. After the last unread row the next tap returns to the very top; from the top the
 * next tap starts again at the first unread row. Nothing is marked read. Web parity: slock
 * #10720 / raft-design #671.
 *
 * Scroll ownership (docs/programmatic-scroll-ownership-contract.md): the intent owner is the
 * direct user tap (an explicit viewport replacement, like the title scroll-to-top); the
 * user-override fence is the scroll mutex (a drag cancels the animation) plus the per-tap
 * LaunchedEffect key (a newer tap cancels the older one). The target is decided per tap from the
 * live layout and the walk's keyed cursor; nothing is re-issued from a changing index.
 *
 * artin 2026-10-22: a DOUBLE tap (tab re-tap or head tap) scrolls back to the top instead
 * ([ActivityRetapGesture]). The first tap walks at once (no wait to rule out a second one); a
 * second tap within the platform double-tap timeout overrides it: as a newer request it cancels
 * the walk still animating and animates the list to the top.
 */
internal sealed interface ActivityUnreadJumpTarget {
    /** No unread row at or below the walk position and nothing left to load: scroll to the top. */
    data object Top : ActivityUnreadJumpTarget

    /** Scroll LazyColumn item [index] (row [key]) to the top of the list and mark it briefly. */
    data class Row(val index: Int, val key: String) : ActivityUnreadJumpTarget

    /** No unread row in the loaded rows below, but more pages exist: load the next one, then decide again. */
    data object LoadMore : ActivityUnreadJumpTarget
}

/**
 * Where the walk stands between taps. Kept per filter page (a filter change starts a new walk).
 */
internal sealed interface ActivityUnreadWalk {
    /** No walk, or the reader took the viewport over: start from the viewport. */
    data object Idle : ActivityUnreadWalk

    /** The scroll back to the top is running: the next tap starts again at the first unread row. */
    data object ReturningToTop : ActivityUnreadWalk

    /**
     * The walk jumped to row [key]. [settled] once that scroll finished, with the viewport it left
     * ([anchorKey] first visible at [anchorOffset]); a different viewport means the reader scrolled.
     * Not [settled]: the scroll was interrupted (a newer tap or a drag).
     */
    data class AtRow(
        val key: String,
        val settled: Boolean,
        val anchorKey: String? = null,
        val anchorOffset: Int = 0,
    ) : ActivityUnreadWalk
}

/** One LazyColumn item in laid-out order; section labels are never unread. */
internal data class ActivityUnreadJumpEntry(val key: String, val unread: Boolean)

/** The live LazyColumn viewport at tap time. */
internal data class ActivityUnreadJumpViewport(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    val visibleItemKeys: List<String>,
) {
    val firstVisibleKey: String? get() = visibleItemKeys.firstOrNull()
}

/**
 * Pure decision for one tap.
 *
 * - [ActivityUnreadWalk.ReturningToTop]: from the first row (a tap during the scroll to the top
 *   restarts at the first unread row, wherever the animation is).
 * - [ActivityUnreadWalk.AtRow] settled and the viewport is the one the jump left: after that
 *   row's CURRENT index (found by key, so a realtime reorder does not lose the place). This also
 *   keeps walking rows near the end that the list cannot bring to the top.
 * - [ActivityUnreadWalk.AtRow] interrupted: after the row while it is on screen.
 * - Otherwise (no walk, the reader scrolled, or the row is gone): from the viewport — the first
 *   row whose top is at or below the viewport top; a partly scrolled-out first row counts as above.
 *
 * No unread row from there on: [ActivityUnreadJumpTarget.LoadMore] when [canLoadMore], else Top.
 */
internal fun activityNextUnreadJumpTarget(
    entries: List<ActivityUnreadJumpEntry>,
    viewport: ActivityUnreadJumpViewport,
    walk: ActivityUnreadWalk,
    canLoadMore: Boolean = false,
): ActivityUnreadJumpTarget {
    fun indexOf(key: String): Int? = entries.indexOfFirst { it.key == key }.takeIf { it >= 0 }
    val fromViewport =
        if (viewport.firstVisibleItemScrollOffset <= 0) {
            viewport.firstVisibleItemIndex.coerceAtLeast(0)
        } else {
            viewport.firstVisibleItemIndex + 1
        }
    val firstCandidate =
        when (walk) {
            ActivityUnreadWalk.Idle -> fromViewport
            ActivityUnreadWalk.ReturningToTop -> 0
            is ActivityUnreadWalk.AtRow -> {
                val viewportUnchanged =
                    walk.settled &&
                        walk.anchorKey == viewport.firstVisibleKey &&
                        walk.anchorOffset == viewport.firstVisibleItemScrollOffset
                val stillOnScreen = !walk.settled && walk.key in viewport.visibleItemKeys
                val rowIndex = if (viewportUnchanged || stillOnScreen) indexOf(walk.key) else null
                rowIndex?.plus(1) ?: fromViewport
            }
        }
    for (index in firstCandidate until entries.size) {
        val entry = entries[index]
        if (entry.unread) return ActivityUnreadJumpTarget.Row(index = index, key = entry.key)
    }
    return if (canLoadMore) ActivityUnreadJumpTarget.LoadMore else ActivityUnreadJumpTarget.Top
}

/** Pages the walk may load in one tap before it gives up and returns to the top. */
internal const val ActivityUnreadJumpMaxLoadMorePages = 10

/** How long one tap waits for a requested page before it gives up and returns to the top. */
internal const val ActivityUnreadJumpLoadMoreTimeoutMillis = 10_000L

/**
 * The list's paging state as the walk sees it. [rowCount] is the number of loaded rows; a page
 * has landed when it grows, or when paging ends or fails.
 */
internal data class ActivityUnreadJumpPaging(
    val rowCount: Int,
    val hasMore: Boolean,
    val loadingMore: Boolean,
    val loadMoreErrorCode: String?,
) {
    /** Another page can be requested now (none in flight, no failed one waiting for Retry). */
    val canLoadMore: Boolean get() = hasMore && loadMoreErrorCode == null

    /** The request made at [before] finished: rows arrived, paging ended or it failed. */
    fun settledAfter(before: ActivityUnreadJumpPaging): Boolean =
        !loadingMore && (rowCount != before.rowCount || !hasMore || loadMoreErrorCode != null)
}

/**
 * The unread state each Activity row already displays: the count shows only when the conversation is not
 * muted.
 */
internal fun InboxItem.activityRowDisplaysUnread(conversationListContext: ActivityConversationListContext): Boolean =
    unreadCount > 0 && !activityConversationMuted(conversationListContext)

/** What one Activity tab re-tap / head tap does. */
internal enum class ActivityRetapAction {
    /** A single tap: walk to the next unread row (falls back to the top). */
    WalkNextUnread,

    /** The second tap of a double tap: scroll the list back to the top. */
    ScrollToTop,
}

/**
 * Tells a single tap from a double tap on one tap target (the Activity tab, or the Activity head)
 * without delaying the single tap: every tap answers at once. A tap within [onTap]'s timeout of
 * the previous single tap is the second tap of a double tap ([ActivityRetapAction.ScrollToTop]);
 * it closes the pair, so a third quick tap walks again. Both the Compose bar/rail and the iOS
 * UITabBar deliver re-taps through the shell's tab callback, so the timing is measured here, in
 * shared code, for every platform.
 */
internal class ActivityRetapGesture(private val timeSource: TimeSource = TimeSource.Monotonic) {
    private var lastSingleTap: TimeMark? = null

    fun onTap(doubleTapTimeout: Duration): ActivityRetapAction {
        val previous = lastSingleTap
        return if (previous != null && previous.elapsedNow() <= doubleTapTimeout) {
            lastSingleTap = null
            ActivityRetapAction.ScrollToTop
        } else {
            lastSingleTap = timeSource.markNow()
            ActivityRetapAction.WalkNextUnread
        }
    }
}

/**
 * One request to the Activity list per tap: [generation] grows by one per tap (consumed through
 * [AppShellScrollToTopRequestFence]); [toTop] is set for the second tap of a double tap.
 */
internal data class ActivityUnreadJumpRequest(val generation: Int = 0, val toTop: Boolean = false) {
    fun next(action: ActivityRetapAction): ActivityUnreadJumpRequest =
        ActivityUnreadJumpRequest(generation = generation + 1, toTop = action == ActivityRetapAction.ScrollToTop)
}
