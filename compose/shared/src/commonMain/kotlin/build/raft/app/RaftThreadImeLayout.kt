package build.raft.app

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.offset
import build.raft.app.compat.ui.raftKeyboardHeightExcludesNavigationBars

/*
 * IME geometry for the Thread page, applied in the layout phase.
 *
 * Root cause of the "page jitters while the keyboard slides up and a reply arrives" report
 * (Android, Huawei, 1.15.0):
 *
 * 1. The composer's bottom padding followed the keyboard through the compat
 *    `keyboardHeightChange` callback: composition reads `WindowInsets.ime`, a `LaunchedEffect`
 *    reports it, the page writes `composerKeyboardInsetDp`, and only the recomposition after that
 *    applies the padding. Every IME animation frame therefore landed two frames late (measured on
 *    the emulator: the composer used the IME height of frame n-2), so the composer and the newest
 *    message were dragged behind the keyboard on the way up and left a gap on the way down. A
 *    reply arriving mid-animation recomposes the whole page and stretches that lag further.
 * 2. With the forward (non-reverse) list layout, every shrink of the list viewport kept the first
 *    visible row in place and pushed the newest message below the composer for one frame; the
 *    bottom-correction collector then scrolled it back one frame later, and the next IME frame
 *    pushed it down again. The newest message bounced on alternate frames for the whole animation
 *    (measured: latest-row bottom alternating between the viewport end and +30..+100 px below it),
 *    and a realtime append added another frame where the new row sat below the fold until the
 *    follow-latest command caught up.
 *
 * The fixes below make `WindowInsets.ime` the single source of truth for the visible geometry and
 * apply it, and the forward list's tail anchoring, inside the same measure pass, so no frame shows
 * an intermediate position. The `keyboardHeightChange` callback keeps feeding the page's decision
 * state (kill-switch, focused-anchor handoff, layout probes); it no longer moves pixels.
 */

/**
 * Keyboard height in px with the per-platform meaning of the compat `keyboardHeightChange`
 * callback: Android reports `ime - navigationBars` (the page adds its bottom safe area back), iOS
 * reports the full keyboard frame.
 */
internal fun raftThreadKeyboardInsetPx(
    imeBottomPx: Int,
    navigationBottomPx: Int,
    excludesNavigationBars: Boolean,
): Int = (imeBottomPx - if (excludesNavigationBars) navigationBottomPx else 0).coerceAtLeast(0)

/**
 * The composer's bottom padding, following the live IME inset in the layout phase. Same formula as
 * [threadComposerBottomPadding] (which stays the single definition of the geometry), fed with the
 * inset of the frame being laid out instead of a state value that is two frames old.
 */
@Composable
internal fun Modifier.raftThreadComposerLiveKeyboardPadding(
    bottomSafeArea: Dp,
    keyboardInsetExcludesBottomSafeArea: Boolean,
): Modifier {
    val ime = WindowInsets.ime
    val navigationBars = WindowInsets.navigationBars
    return layout { measurable, constraints ->
        val keyboardPx =
            raftThreadKeyboardInsetPx(
                imeBottomPx = ime.getBottom(this),
                navigationBottomPx = navigationBars.getBottom(this),
                excludesNavigationBars = raftKeyboardHeightExcludesNavigationBars,
            )
        val bottomPx =
            threadComposerBottomPadding(
                keyboardInsetDp = keyboardPx.toDp().value,
                bottomSafeArea = bottomSafeArea,
                keyboardInsetExcludesBottomSafeArea = keyboardInsetExcludesBottomSafeArea,
            ).roundToPx().coerceAtLeast(0)
        val placeable = measurable.measure(constraints.offset(vertical = -bottomPx))
        layout(placeable.width, constraints.constrainHeight(placeable.height + bottomPx)) {
            placeable.place(0, 0)
        }
    }
}

/** What the forward thread list does in the measure pass that sees a new viewport or tail. */
internal enum class ThreadTailLayoutAnchor {
    /** Leave the list position alone. */
    None,

    /** Lay the list out with its last row (the trailing spacer) ending at the viewport end. */
    PinTail,
}

internal data class ThreadTailLayoutAnchorState(
    val reverseLayout: Boolean,
    /** The viewport controller is parked on the newest message (`FollowingLatest`). */
    val followingLatest: Boolean,
    /** A drag, fling or programmatic scroll owns the viewport right now. */
    val scrollInProgress: Boolean,
    /** In the previous layout the trailing row ended at the viewport end. */
    val previousTailAtRenderedBottom: Boolean,
    val previousViewportPx: Int,
    val nextViewportPx: Int,
    val previousItemCount: Int,
    val nextItemCount: Int,
    val previousFirstMessageId: String?,
    val nextFirstMessageId: String?,
    val previousLastMessageId: String?,
    val nextLastMessageId: String?,
    /** A newer page is loading / settling, or newer history exists: appends are pagination. */
    val newerPagingActive: Boolean,
)

/**
 * Same-pass tail anchoring for the forward (non-reverse) list. A reversed list keeps the newest
 * row at the bottom by construction and never needs this.
 *
 * Applies only while the newest message owns the viewport (parked at the rendered bottom,
 * `FollowingLatest`, nothing scrolling), and only for the two structural changes that would
 * otherwise push the tail below the fold for a frame: the viewport shrinking (keyboard rising,
 * composer growing) and a genuine tail append. Pagination, prepends, list replacement and any user
 * movement leave the position untouched, so the programmatic-scroll ownership fences of the page
 * (focused-message sessions, older/newer page restoration, drag) are unaffected.
 */
internal fun resolveThreadTailLayoutAnchor(state: ThreadTailLayoutAnchorState): ThreadTailLayoutAnchor {
    if (state.reverseLayout || !state.followingLatest || state.scrollInProgress) return ThreadTailLayoutAnchor.None
    if (!state.previousTailAtRenderedBottom) return ThreadTailLayoutAnchor.None
    if (state.previousViewportPx <= 0 || state.nextViewportPx <= 0 || state.nextItemCount <= 0) {
        return ThreadTailLayoutAnchor.None
    }
    val viewportShrank = state.nextViewportPx < state.previousViewportPx
    val tailAppended =
        !state.newerPagingActive &&
            state.nextItemCount > state.previousItemCount &&
            state.previousItemCount > 0 &&
            state.previousFirstMessageId == state.nextFirstMessageId &&
            state.nextLastMessageId != null &&
            state.previousLastMessageId != state.nextLastMessageId
    val sameItems =
        state.nextItemCount == state.previousItemCount &&
            state.previousFirstMessageId == state.nextFirstMessageId &&
            state.previousLastMessageId == state.nextLastMessageId
    return when {
        tailAppended -> ThreadTailLayoutAnchor.PinTail
        viewportShrank && sameItems -> ThreadTailLayoutAnchor.PinTail
        else -> ThreadTailLayoutAnchor.None
    }
}

/** The page inputs [raftThreadTailLayoutAnchor] reads at measure time. */
internal data class ThreadTailLayoutAnchorInputs(
    val reverseLayout: Boolean,
    val followingLatest: Boolean,
    val itemCount: Int,
    val firstMessageId: String?,
    val lastMessageId: String?,
    val newerPagingActive: Boolean,
)

private class ThreadTailLayoutAnchorMemory {
    var viewportPx: Int = -1
    var itemCount: Int = -1
    var firstMessageId: String? = null
    var lastMessageId: String? = null
}

/**
 * Applies [resolveThreadTailLayoutAnchor] to the thread list inside the measure pass that receives
 * the new viewport height (or the new tail), through `LazyListState.requestScrollToItem`, which
 * takes effect in the list's own measure right after this one. No coroutine, no extra frame, so
 * there is nothing for the follow-latest command or the bottom-correction collector to fight: when
 * they run, the tail is already at the bottom and their scroll is a no-op.
 */
@Composable
internal fun Modifier.raftThreadTailLayoutAnchor(
    listState: ThreadListViewport,
    inputs: ThreadTailLayoutAnchorInputs,
    onAnchored: (ThreadTailLayoutAnchorState) -> Unit = {},
): Modifier {
    val memory = remember(listState) { ThreadTailLayoutAnchorMemory() }
    val currentInputs: State<ThreadTailLayoutAnchorInputs> = rememberUpdatedState(inputs)
    val currentOnAnchored = rememberUpdatedState(onAnchored)
    return layout { measurable, constraints ->
        // Observed on purpose: a new tail must re-run this pass before the list measures it.
        val next = currentInputs.value
        val nextViewportPx = if (constraints.hasBoundedHeight) constraints.maxHeight else -1
        if (!next.reverseLayout) {
            val state =
                Snapshot.withoutReadObservation {
                    ThreadTailLayoutAnchorState(
                        reverseLayout = next.reverseLayout,
                        followingLatest = next.followingLatest,
                        scrollInProgress = listState.isScrollInProgress,
                        previousTailAtRenderedBottom = listState.raw.layoutInfo.threadTailEndsAtViewportEnd(),
                        previousViewportPx = memory.viewportPx,
                        nextViewportPx = nextViewportPx,
                        previousItemCount = memory.itemCount,
                        nextItemCount = next.itemCount,
                        previousFirstMessageId = memory.firstMessageId,
                        nextFirstMessageId = next.firstMessageId,
                        previousLastMessageId = memory.lastMessageId,
                        nextLastMessageId = next.lastMessageId,
                        newerPagingActive = next.newerPagingActive,
                    )
                }
            if (resolveThreadTailLayoutAnchor(state) == ThreadTailLayoutAnchor.PinTail) {
                listState.raw.requestScrollToItem(next.itemCount - 1)
                currentOnAnchored.value(state)
            }
        }
        memory.viewportPx = nextViewportPx
        memory.itemCount = next.itemCount
        memory.firstMessageId = next.firstMessageId
        memory.lastMessageId = next.lastMessageId
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/**
 * Whether the last laid-out pass ended with the list's final row flush with the viewport end.
 * Reads the previous layout only (index and count from the same snapshot), so it stays correct in
 * the pass where composition has already appended a row the list has not measured yet.
 */
internal fun androidx.compose.foundation.lazy.LazyListLayoutInfo.threadTailEndsAtViewportEnd(): Boolean {
    val last = visibleItemsInfo.lastOrNull() ?: return false
    if (last.index != totalItemsCount - 1) return false
    return threadTailEndsAtViewportEnd(
        tailOffsetPx = last.offset,
        tailSizePx = last.size,
        viewportEndPx = viewportEndOffset,
    )
}

internal fun threadTailEndsAtViewportEnd(tailOffsetPx: Int, tailSizePx: Int, viewportEndPx: Int): Boolean =
    kotlin.math.abs(tailOffsetPx + tailSizePx - viewportEndPx) <= ThreadTailLayoutAnchorTolerancePx

private const val ThreadTailLayoutAnchorTolerancePx = 24
