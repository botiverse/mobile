package build.raft.app

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Decision of [resolveThreadInitialFocusedPlacement] for a reversed thread list whose first
 * composition already carries its focused target message in cache (#proj-mobile:#258).
 */
internal sealed interface ThreadInitialFocusedPlacementDecision {
    /** The target row or viewport is not measured yet; wait for the next layout pass. */
    data object Pending : ThreadInitialFocusedPlacementDecision

    /** The target row is already at its focused offset; retire without scrolling. */
    data object AlreadyAligned : ThreadInitialFocusedPlacementDecision

    /** Align the target row synchronously before the first frame draws. */
    data class Scroll(val request: ThreadRowTopKeepRequest) : ThreadInitialFocusedPlacementDecision
}

/**
 * Computes the raw `scrollToItem` position that places [focusedForwardIndex] at its focused target
 * offset on a `LazyColumn(reverseLayout = true)` whose [layoutInfo] has just measured it at the
 * bottom edge (from `initialFirstVisibleItemIndex = reversedThreadListIndex(focusedForwardIndex, total)`).
 *
 * Uses the exact inverse of [reversedThreadListItemOffset] so top content padding (offline stand-in
 * tip, inline search bar) and [LazyViewportAnchorPlacement.FocusedThreadReplies] agree pixel-for-pixel
 * with [ThreadListViewport.scrollToItem] and [scrollThreadItemToFocusedViewport].
 */
internal fun resolveThreadInitialFocusedPlacement(
    layoutInfo: LazyListLayoutInfo,
    focusedForwardIndex: Int,
    placement: LazyViewportAnchorPlacement,
    minBottomReservePx: Int,
    canScrollForward: Boolean = true,
    canScrollBackward: Boolean = true,
): ThreadInitialFocusedPlacementDecision {
    val total = layoutInfo.totalItemsCount
    if (focusedForwardIndex !in 0 until total) return ThreadInitialFocusedPlacementDecision.Pending
    val layoutSize = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
    if (layoutSize <= 0) return ThreadInitialFocusedPlacementDecision.Pending
    val forwardViewportEnd = layoutSize - layoutInfo.afterContentPadding
    val forwardViewportHeight = layoutSize - layoutInfo.beforeContentPadding - layoutInfo.afterContentPadding
    if (forwardViewportHeight <= 0) return ThreadInitialFocusedPlacementDecision.Pending
    val rawIndex = reversedThreadListIndex(focusedForwardIndex, total)
    val rawItem =
        layoutInfo.visibleItemsInfo.firstOrNull { it.index == rawIndex }
            ?: return ThreadInitialFocusedPlacementDecision.Pending
    val desiredForwardScrollOffset =
        when (placement) {
            LazyViewportAnchorPlacement.FocusedThreadReplies ->
                threadRepliesFocusedItemScrollOffset(
                    itemSize = rawItem.size,
                    viewportEnd = forwardViewportEnd,
                    minBottomReservePx = minBottomReservePx,
                )
            else ->
                threadFocusedItemScrollOffset(
                    itemSize = rawItem.size,
                    viewportHeight = forwardViewportHeight,
                )
        }
    val desiredForwardTop = -desiredForwardScrollOffset
    val desiredRawOffset =
        layoutSize -
            layoutInfo.beforeContentPadding -
            layoutInfo.afterContentPadding -
            rawItem.size -
            desiredForwardTop
    val forwardDelta = desiredRawOffset - rawItem.offset
    if (
        forwardDelta == 0 ||
        (forwardDelta > 0 && !canScrollForward) ||
        (forwardDelta < 0 && !canScrollBackward)
    ) {
        return ThreadInitialFocusedPlacementDecision.AlreadyAligned
    }
    return ThreadInitialFocusedPlacementDecision.Scroll(
        ThreadRowTopKeepRequest(rawIndex = rawIndex, scrollOffset = -desiredRawOffset)
    )
}

/**
 * Single-shot pre-first-draw placement owner for a cached focused open on a reversed thread list
 * (#proj-mobile:#258).
 *
 * `LazyColumn(reverseLayout = true)` starts its first layout pass with the cached focused row at
 * physical bottom (`rawOffset = 0`) because `LazyListState`'s initial scroll offset cannot be
 * negative. Right after that measure/layout pass — before the first frame draws — this owner issues
 * one undispatched [ThreadListViewport.scrollRawToItem] (forcing a synchronous remeasure, the same
 * mechanism [ThreadReadingAnchorOwner] uses) so Frame 1 already draws the focused message in place
 * instead of flashing the bottom of the conversation and jumping up on a later frame.
 */
internal class ThreadInitialFocusedPlacementOwner(
    private val listState: ThreadListViewport,
    private val scope: CoroutineScope,
    armed: Boolean,
) {
    var applied: Boolean = false
        private set

    var retired: Boolean = !armed
        private set

    fun afterLayout(
        owns: () -> Boolean,
        retiredByHost: () -> Boolean,
        focusedForwardIndex: () -> Int,
        placement: () -> LazyViewportAnchorPlacement,
        minBottomReservePx: Int,
    ) {
        if (applied || retired) return
        if (retiredByHost()) {
            retired = true
            return
        }
        if (!listState.reverse || listState.isVirtual || !owns()) return
        val resolvedPlacement = placement()
        when (
            val decision =
                resolveThreadInitialFocusedPlacement(
                    layoutInfo = listState.raw.layoutInfo,
                    focusedForwardIndex = focusedForwardIndex(),
                    placement = resolvedPlacement,
                    minBottomReservePx = minBottomReservePx,
                    canScrollForward = listState.canScrollForward,
                    canScrollBackward = listState.canScrollBackward,
                )
        ) {
            ThreadInitialFocusedPlacementDecision.Pending -> Unit
            ThreadInitialFocusedPlacementDecision.AlreadyAligned -> {
                applied = true
                retired = true
            }
            is ThreadInitialFocusedPlacementDecision.Scroll -> {
                applied = true
                retired = true
                val ownerTag =
                    if (resolvedPlacement == LazyViewportAnchorPlacement.FocusedThreadReplies) {
                        ThreadViewportOwnerTag.JumpPill
                    } else {
                        ThreadViewportOwnerTag.FocusedReanchor
                    }
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    listState.noteViewportOwner(ownerTag)
                    listState.scrollRawToItem(decision.request.rawIndex, decision.request.scrollOffset)
                }
            }
        }
    }
}
