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
 */
internal fun activityShouldAnchorTopOnCanonicalSnapshot(
    entryOwnerArmed: Boolean,
    pullRefreshOwnsTop: Boolean,
    parkedAtTop: Boolean,
    scrollInProgress: Boolean,
    genuineTopUpdate: Boolean,
): Boolean =
    genuineTopUpdate && parkedAtTop && !scrollInProgress && (entryOwnerArmed || pullRefreshOwnsTop)

/** The list is at its very top edge (first row fully at the top). */
internal fun activityListParkedAtTop(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int): Boolean =
    firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0

/**
 * A new or bumped row now sits above the previous head. Appends, in-place field updates and
 * removing the head (Done/read) are not top updates.
 */
internal fun activityHasNewTopRow(previousKeys: List<String>, nextKeys: List<String>): Boolean {
    val previousHead = previousKeys.firstOrNull() ?: return false
    val nextHead = nextKeys.firstOrNull() ?: return false
    val previousHeadIndex = nextKeys.indexOf(previousHead)
    return when {
        previousHeadIndex > 0 -> true
        previousHeadIndex < 0 -> nextHead !in previousKeys
        else -> false
    }
}
