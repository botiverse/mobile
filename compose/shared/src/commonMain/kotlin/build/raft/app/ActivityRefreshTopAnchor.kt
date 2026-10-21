package build.raft.app

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
): Boolean {
    if (newRowKeys.isEmpty() || firstVisibleItemIndex <= 0) return false
    val end = minOf(firstVisibleItemIndex, lazyItemKeys.size)
    for (index in 0 until end) {
        if (lazyItemKeys[index] in newRowKeys) return true
    }
    return false
}
