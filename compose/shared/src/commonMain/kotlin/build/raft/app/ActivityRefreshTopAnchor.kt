package build.raft.app

import androidx.compose.foundation.lazy.LazyListState
import build.raft.app.data.InboxItem
import build.raft.app.data.activityUpdateRevisionMessageId
import kotlinx.coroutines.flow.Flow

/**
 * Task #47 (Hands e7b1f0ce): on cold start Activity paints the cached snapshot, then the
 * fresh first page lands. LazyList keeps the previously first-visible key in place, so the
 * newest rows were inserted ABOVE the viewport (or, with stable order on, withheld behind
 * the "new updates" button) and the reader stayed on the old data.
 *
 * Scroll ownership (docs/programmatic-scroll-ownership-contract.md):
 * - Intent owner: the Activity entry session — the panel becoming active (route entry, cold
 *   start, return from a thread, app foreground). While armed, the reader has not moved the
 *   list since the page opened, so a genuine top update owns the top edge.
 * - User-override fence: the first observed `isScrollInProgress == true` (drag, fling, pull,
 *   title/tab scroll-to-top) retires the session until the next activation; the decision also
 *   requires the list to be parked at the very top and not scrolling at decision time.
 * - The anchor is applied with `requestScrollToItem(0)` in the same snapshot as the list
 *   change, so no delayed job exists that could outlive the fence.
 * A reader who already scrolled is never moved; they get the "new activity" pill instead.
 */
internal class ActivityEntryTopAnchorOwner {
    var armed: Boolean = false
        private set

    /** The panel became active: a new entry session owns the top edge. */
    fun arm() {
        armed = true
    }

    /** Any direct viewport movement replaces the entry intent. */
    fun retire() {
        armed = false
    }

    /** User-override fence: retires the session on the first scroll in progress. */
    suspend fun fenceUserScroll(scrollInProgress: Flow<Boolean>) {
        scrollInProgress.collect { scrolling -> if (scrolling) retire() }
    }
}

/**
 * True when the canonical snapshot should be adopted with the list kept at the top edge.
 * [genuineTopUpdate] excludes pagination appends and read/busy-state mutations (they do not
 * change the head of the list). [pullRefreshOwnsTop] is the user's own pull-to-refresh (in
 * flight or just completed), which also owns the top edge.
 *
 * [followParkedTop]: a list resting at index 0 / offset 0 follows the head like a live feed
 * even after the entry session retired (the reader scrolled away and came back). Without it,
 * LazyList keeps the old first key in place: new rows were inserted above a reader who was at
 * the top, or (pinned section label at index 0) the list stayed put while the "New activity"
 * pill claimed something was hidden above (iOS 1.15.0 report). The stable-order presentation
 * keeps its own "N updates" button and does not follow.
 */
internal fun activityShouldAnchorTopOnCanonicalSnapshot(
    entryOwnerArmed: Boolean,
    pullRefreshOwnsTop: Boolean,
    parkedAtTop: Boolean,
    scrollInProgress: Boolean,
    genuineTopUpdate: Boolean,
    followParkedTop: Boolean = false,
): Boolean =
    genuineTopUpdate && parkedAtTop && !scrollInProgress && (entryOwnerArmed || pullRefreshOwnsTop || followParkedTop)

/**
 * The root rule for the Activity list's top edge (artin, Hands b205bd89: "tap back to the top,
 * then it jumps to the second screen"): a list parked at its very top stays there across a
 * change that moves its head row. LazyList keeps the first visible row by key, so without this a
 * new or bumped row above a reader at the top carries the list down with the old head. The web
 * gets this for free (browsers don't scroll-anchor at scrollTop 0).
 *
 * Call it in the same snapshot as the list change, before the next measure (the state still holds
 * the previous layout): a same-snapshot `requestScrollToItem(0)`, no animation, no delayed job.
 * Never while a scroll is in progress (a drag, a fling, an animation owns the viewport); a reader
 * scrolled away keeps their row. Returns whether it pinned.
 */
internal fun LazyListState.keepTopIfParked(previousHeadKey: Any?, nextHeadKey: Any?): Boolean {
    if (previousHeadKey == nextHeadKey || isScrollInProgress) return false
    if (!activityListParkedAtTop(firstVisibleItemIndex, firstVisibleItemScrollOffset)) return false
    requestScrollToItem(0)
    return true
}

/** The list is at its very top edge (first row fully at the top). */
internal fun activityListParkedAtTop(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int): Boolean =
    firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0

/**
 * The rows that now sit above the previous head (new or bumped). Appends, in-place field
 * updates and removing the head (Done/read) yield none.
 */
internal fun activityNewTopRowKeys(previousKeys: List<String>, nextKeys: List<String>): Set<String> {
    val previousHead = previousKeys.firstOrNull() ?: return emptySet()
    if (nextKeys.isEmpty()) return emptySet()
    val previousHeadIndex = nextKeys.indexOf(previousHead)
    if (previousHeadIndex >= 0) return nextKeys.subList(0, previousHeadIndex).toSet()
    val previous = previousKeys.toSet()
    return nextKeys.takeWhile { it !in previous }.toSet()
}

/**
 * The rows that ARRIVED above the previous head: rows that were not listed before, or rows whose
 * activity advanced (a new message / event, [activityUpdateRevisionMessageId]). This is what the
 * "new activity" pill counts (artin 2026-10-28: "顶部明明就两个未读 … 往下拉的时候，已读也会被计算上").
 *
 * [activityNewTopRowKeys] is positional: every row above the old head's new index. When a change
 * only REORDERS rows (e.g. the realtime order and a served page disagree about an unread head, so
 * the head drops back below rows the reader already passed), that set is every row above the
 * demoted head, read or unread, and the pill then counted each of them once scrolled past.
 * A row with no new activity never arrived, wherever it moved to.
 */
internal fun activityArrivedTopRowKeys(previousItems: List<InboxItem>, nextItems: List<InboxItem>): Set<String> {
    val moved = activityNewTopRowKeys(previousItems.map(InboxItem::key), nextItems.map(InboxItem::key))
    if (moved.isEmpty()) return moved
    val previousByKey = previousItems.associateBy(InboxItem::key)
    return nextItems
        .filter { item ->
            if (item.key !in moved) return@filter false
            val previous = previousByKey[item.key] ?: return@filter true
            val revision = item.activityUpdateRevisionMessageId()
            revision != null && revision != previous.activityUpdateRevisionMessageId()
        }
        .mapTo(linkedSetOf(), InboxItem::key)
}

/**
 * The pill's key set after a list change, as settleTopEdge applies it in the same snapshot:
 * cleared when the change keeps or anchors the list at the top ([listAtTop]), otherwise grown by
 * the rows that arrived ([activityArrivedTopRowKeys]). Rows the reader merely scrolled past never
 * enter it.
 */
internal fun activityPillKeysAfterChange(
    current: Set<String>,
    previousItems: List<InboxItem>,
    nextItems: List<InboxItem>,
    listAtTop: Boolean,
): Set<String> {
    if (listAtTop) return if (current.isEmpty()) current else emptySet()
    val arrived = activityArrivedTopRowKeys(previousItems, nextItems)
    return if (arrived.isEmpty() || current.containsAll(arrived)) current else current + arrived
}

/**
 * A new or bumped row now sits above the previous head. Appends, in-place field updates and
 * removing the head (Done/read) are not top updates.
 */
internal fun activityHasNewTopRow(previousKeys: List<String>, nextKeys: List<String>): Boolean =
    activityNewTopRowKeys(previousKeys, nextKeys).isNotEmpty()

/**
 * The "New activity" pill shows only while one of [newRowKeys] is laid out ABOVE the first
 * visible LazyColumn item. [lazyItemKeys] is the LazyColumn key order the person sees (section
 * labels and pinned rows included), so a row bumped into "All conversations" below the pinned
 * block, or a row already in the viewport, never raises the pill, and a list at the top never
 * shows it.
 */
internal fun activityNewRowsAboveViewport(
    newRowKeys: Set<String>,
    lazyItemKeys: List<String>,
    firstVisibleItemIndex: Int,
): Boolean = activityNewRowsAboveViewportCount(newRowKeys, lazyItemKeys, firstVisibleItemIndex) > 0

/**
 * The pill's count (web `thread.newUpdates`, artin 2026-10-21): how many of [newRowKeys] are laid
 * out above the first visible LazyColumn item. [newRowKeys] holds only rows not seen yet
 * ([activityUnseenNewRowKeys]).
 */
internal fun activityNewRowsAboveViewportCount(
    newRowKeys: Set<String>,
    lazyItemKeys: List<String>,
    firstVisibleItemIndex: Int,
): Int {
    if (newRowKeys.isEmpty() || firstVisibleItemIndex <= 0) return 0
    val end = minOf(firstVisibleItemIndex, lazyItemKeys.size)
    var count = 0
    for (index in 0 until end) {
        if (lazyItemKeys[index] in newRowKeys) count += 1
    }
    return count
}

/** A landed row the reader has scrolled into view is seen: it no longer counts for the pill. */
internal fun activityUnseenNewRowKeys(newRowKeys: Set<String>, visibleItemKeys: Set<String>): Set<String> =
    if (newRowKeys.isEmpty() || visibleItemKeys.none { it in newRowKeys }) newRowKeys else newRowKeys - visibleItemKeys
