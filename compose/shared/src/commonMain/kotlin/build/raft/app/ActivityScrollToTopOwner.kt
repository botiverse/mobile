package build.raft.app

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Hands b205bd89 (artin, Android 1.16.0): "Activity: tap to go back to the top, then it suddenly
 * jumps back to the second screen."
 *
 * LazyColumn keeps the first visible row by KEY across a data change. A list parked at the top
 * whose head row is moved down (a refresh, a realtime bump, a presentation rebuilt for the same
 * rows) therefore follows that row down, unless something re-asserts the top in the same
 * snapshot. The canonical-snapshot anchor ([activityShouldAnchorTopOnCanonicalSnapshot]) only
 * decides while the list is parked and not scrolling; a change applied while the scroll-to-top
 * animation is still running, or a presentation rebuilt without passing through that decision,
 * lands after the animation already settled and nothing owned the top any more.
 *
 * Scroll ownership (docs/programmatic-scroll-ownership-contract.md):
 * - Intent owner: the explicit scroll-to-top (Activity title tap, Activity tab re-tap, the
 *   "new activity" pill), one generation per request.
 * - The owner keeps the top edge after the animation lands, until the reader moves the list.
 * - User-override fence: a scroll in progress that is not this owner's own animation retires the
 *   owner; so does any other scroll cancelling the animation. A newer request supersedes an
 *   older one by generation, so an older request's late completion never acts.
 * - After the suspension (the animation) the request revalidates its generation and the live
 *   scroll state before it pins; the pin is a same-snapshot `requestScrollToItem(0)` to the
 *   owned edge, never a numeric index taken from an earlier layout.
 */
internal class ActivityScrollToTopOwner {
    private var generation: Long = 0L

    /** The generation that owns the top edge (animating or landed); null when nobody does. */
    private var ownedGeneration by mutableStateOf<Long?>(null)

    /** The generation whose own animation is running (its scroll is not the reader's). */
    private var animatingGeneration by mutableStateOf<Long?>(null)

    /** The row the held list shows at the top (index 0, offset 0); null until observed. */
    private var heldHeadKey: Any? = null

    /** The top edge is owned and the owner's animation has landed: hold the list at the top. */
    val holdsTop: Boolean
        get() = ownedGeneration != null && animatingGeneration == null

    /** A new explicit scroll-to-top request; replaces any earlier one. */
    fun begin(): Long {
        generation += 1
        heldHeadKey = null
        ownedGeneration = generation
        animatingGeneration = generation
        return generation
    }

    fun isCurrent(requestGeneration: Long): Boolean = ownedGeneration == requestGeneration

    /** The request's animation reached the top. */
    fun landed(requestGeneration: Long) {
        if (animatingGeneration == requestGeneration) animatingGeneration = null
    }

    /**
     * The request's animation was cancelled. A newer request of this owner already took over
     * (its generation is current); otherwise another scroll did (a drag, a fling, another
     * programmatic owner), and the top edge is no longer this owner's.
     */
    fun interrupted(requestGeneration: Long) {
        if (ownedGeneration == requestGeneration) retire()
    }

    internal fun heldHeadKeyForPolicy(): Any? = heldHeadKey

    internal fun recordHeldHead(key: Any?) {
        if (holdsTop) heldHeadKey = key
    }

    /** Observed scroll progress: a scroll that is not this owner's own animation is the reader's. */
    fun onScrollInProgress(scrolling: Boolean) {
        if (scrolling && animatingGeneration == null && ownedGeneration != null) retire()
    }

    /** The reader or another viewport replacement owns the viewport now. */
    fun retire() {
        generation += 1
        heldHeadKey = null
        ownedGeneration = null
        animatingGeneration = null
    }
}

/**
 * What a held top does with an observed layout: a list at the top records its head row; the same
 * head row pushed below index 0 by a data change (LazyList kept it by key) is pinned back; any
 * other move (a different first row, a partial offset) is the reader's or another owner's, and
 * retires the hold. Never pins while a scroll is in progress.
 */
internal enum class ActivityHeldTopAction { None, Pin, Retire }

internal fun ActivityScrollToTopOwner.observeHeldLayout(
    scrollInProgress: Boolean,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    firstVisibleItemKey: Any?,
): ActivityHeldTopAction {
    if (!holdsTop || scrollInProgress) return ActivityHeldTopAction.None
    return activityHeldTopAction(
        heldHeadKey = heldHeadKeyForPolicy(),
        firstVisibleItemIndex = firstVisibleItemIndex,
        firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
        firstVisibleItemKey = firstVisibleItemKey,
    ).also { action ->
        when (action) {
            ActivityHeldTopAction.None -> recordHeldHead(firstVisibleItemKey)
            ActivityHeldTopAction.Retire -> retire()
            ActivityHeldTopAction.Pin -> Unit
        }
    }
}

internal fun activityHeldTopAction(
    heldHeadKey: Any?,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    firstVisibleItemKey: Any?,
): ActivityHeldTopAction =
    when {
        activityListParkedAtTop(firstVisibleItemIndex, firstVisibleItemScrollOffset) -> ActivityHeldTopAction.None
        heldHeadKey != null && firstVisibleItemScrollOffset == 0 && firstVisibleItemKey == heldHeadKey ->
            ActivityHeldTopAction.Pin
        else -> ActivityHeldTopAction.Retire
    }

/**
 * Runs one explicit scroll-to-top under [owner]. [scrollToTop] is the animation;
 * [pinTopIfOwned] is the post-suspension check: it runs only if this request is still the
 * owner's current generation once the animation returns.
 */
internal suspend fun ActivityScrollToTopOwner.runScrollToTop(
    scrollToTop: suspend () -> Unit,
    pinTopIfOwned: () -> Unit,
) {
    val requestGeneration = begin()
    try {
        scrollToTop()
    } catch (cancelled: CancellationException) {
        interrupted(requestGeneration)
        throw cancelled
    }
    if (!isCurrent(requestGeneration)) return
    landed(requestGeneration)
    pinTopIfOwned()
}

/**
 * True when the owner holds the top edge and the list is off it while nobody scrolls: a data or
 * presentation change moved the viewport by key after the owner landed.
 */
internal fun activityScrollToTopShouldPin(
    holdsTop: Boolean,
    scrollInProgress: Boolean,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
): Boolean =
    holdsTop && !scrollInProgress && !activityListParkedAtTop(firstVisibleItemIndex, firstVisibleItemScrollOffset)

/** The explicit Activity scroll-to-top: the expressive animation, owned by [owner]. */
internal suspend fun LazyListState.animateActivityScrollToTop(owner: ActivityScrollToTopOwner) {
    owner.runScrollToTop(
        scrollToTop = { animateScrollToTopExpressive() },
        pinTopIfOwned = { pinActivityTopIfOwned(owner) },
    )
}

/** Same-snapshot pin to the owned top edge; a no-op unless [owner] holds it and nobody scrolls. */
internal fun LazyListState.pinActivityTopIfOwned(owner: ActivityScrollToTopOwner) {
    if (
        activityScrollToTopShouldPin(
            holdsTop = owner.holdsTop,
            scrollInProgress = isScrollInProgress,
            firstVisibleItemIndex = firstVisibleItemIndex,
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
        )
    ) {
        requestScrollToItem(0)
    }
}

/**
 * Called with a data change about to be applied (same snapshot): true when the owner holds the
 * top and the list is still parked there, i.e. the reader has not moved it since it landed. The
 * caller then requests index 0 together with the change, so LazyList never follows the old head.
 */
internal fun LazyListState.activityHeldTopOwnsChange(owner: ActivityScrollToTopOwner): Boolean =
    owner.holdsTop && !isScrollInProgress && activityListParkedAtTop(firstVisibleItemIndex, firstVisibleItemScrollOffset)

/**
 * Wires [owner] to [listState]: the reader's scroll retires it, and while it holds the top any
 * key-preserving move off the top (a change that did not pass through the canonical anchor) is
 * pinned back to the owned edge.
 */
@Composable
internal fun ActivityScrollToTopOwnerEffect(listState: LazyListState, owner: ActivityScrollToTopOwner) {
    LaunchedEffect(listState, owner) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling -> owner.onScrollInProgress(scrolling) }
    }
    LaunchedEffect(listState, owner) {
        snapshotFlow {
            HeldTopLayout(
                holdsTop = owner.holdsTop,
                scrollInProgress = listState.isScrollInProgress,
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                firstVisibleItemKey = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key,
            )
        }
            .distinctUntilChanged()
            .collect { layout ->
                val action =
                    owner.observeHeldLayout(
                        scrollInProgress = layout.scrollInProgress,
                        firstVisibleItemIndex = layout.firstVisibleItemIndex,
                        firstVisibleItemScrollOffset = layout.firstVisibleItemScrollOffset,
                        firstVisibleItemKey = layout.firstVisibleItemKey,
                    )
                if (action == ActivityHeldTopAction.Pin) listState.pinActivityTopIfOwned(owner)
            }
    }
}

private data class HeldTopLayout(
    val holdsTop: Boolean,
    val scrollInProgress: Boolean,
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    val firstVisibleItemKey: Any?,
)
