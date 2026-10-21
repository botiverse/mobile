package build.raft.designsystem.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Leading-edge strip where a back drag always belongs to the host's back swipe, even over content
 * that could page back — a native `UINavigationController` edge pop wins over its content the same
 * way. Matches the iOS host's `RAFT_INTERACTIVE_POP_DEFAULT_EDGE_WIDTH` (40 pt; 1 dp == 1 pt).
 */
val RaftBackSwipeEdgeWidth: Dp = 40.dp

/** Decision of a swipe-paged surface's drag handler for one gesture. */
internal enum class RaftPagingDragDecision {
    /** Not enough movement yet. */
    Undecided,

    /** Horizontal drag the surface follows and pages with. */
    Claim,

    /** Left unconsumed: a back drag for the host's back swipe, or a vertical drag for the content. */
    Release,
}

/**
 * Pure classification for a drag on swipe-paged content (an image gallery) on a page:
 *
 * - vertical-dominant drags are released;
 * - on a host with a full-page back swipe a back-direction drag (right in LTR, left in RTL) is
 *   released when there is no previous item to page to, or when it starts in the leading edge
 *   strip — consuming it (even as a rubber-band) would make the Compose touch recogniser win
 *   against the native interactive pop, so the page could not be swiped away;
 * - every other horizontal drag is claimed (paging, or a rubber-band at either end).
 *
 * Without a host back swipe (Android: Back is an OS gesture) every horizontal drag is claimed, as
 * before.
 */
internal fun raftPagingDragDecision(
    dxPx: Float,
    dyPx: Float,
    touchSlopPx: Float,
    isLtr: Boolean,
    hostHasBackSwipe: Boolean,
    startsInLeadingEdge: Boolean,
    canPageBackward: Boolean,
): RaftPagingDragDecision {
    val adx = abs(dxPx)
    val ady = abs(dyPx)
    if (adx < touchSlopPx && ady < touchSlopPx) return RaftPagingDragDecision.Undecided
    if (ady > adx) return RaftPagingDragDecision.Release
    val back = if (isLtr) dxPx > 0f else dxPx < 0f
    if (back && hostHasBackSwipe && (!canPageBackward || startsInLeadingEdge)) return RaftPagingDragDecision.Release
    return RaftPagingDragDecision.Claim
}

/** Whether a touch at [xPx] in a [widthPx]-wide element lies in its leading [edgePx] strip. */
internal fun raftStartsInLeadingEdge(xPx: Float, widthPx: Float, edgePx: Float, isLtr: Boolean): Boolean {
    val distance = if (isLtr) xPx else widthPx - xPx
    return distance >= 0f && distance <= edgePx
}

/**
 * Horizontal swipe paging (an image gallery: one item at a time, swipe to the next / previous) that
 * cooperates with the host's full-page back swipe (iOS: the page is a native push and is swiped
 * away by `FDFullscreenPopGesture`):
 *
 * - On the first item a back-direction drag is left to the host, so the page pops interactively.
 * - Past the first item a back drag pages back, except from the leading [RaftBackSwipeEdgeWidth]
 *   strip, which still pops the page; the surface registers itself as back-swipe content over the
 *   rest of its bounds so the native recogniser does not begin there.
 * - Content inside that consumes the movement first (a zoomed image panning, a pinch) cancels the
 *   drag; such content registers its own [raftBackSwipeRegion] while it owns horizontal drags.
 *
 * [onDrag] and [onDragEnd] receive the horizontal distance from the touch-down point in pixels.
 * Without a host back swipe this claims every horizontal drag.
 */
@Composable
fun Modifier.raftBackSwipePagingDrag(
    canPageBackward: Boolean,
    onDragStart: () -> Unit = {},
    onDrag: (totalDxPx: Float) -> Unit,
    onDragEnd: (totalDxPx: Float) -> Unit,
    onDragCancel: () -> Unit = {},
): Modifier {
    val hostHasBackSwipe = LocalRaftBackSwipeRegions.current != null
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val edgePx = with(LocalDensity.current) { RaftBackSwipeEdgeWidth.toPx() }
    val currentCanPageBackward by rememberUpdatedState(canPageBackward)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    return this
        .raftBackSwipeRegion(RaftBackSwipeEdgeWidth) { currentCanPageBackward }
        .pointerInput(hostHasBackSwipe, isLtr, edgePx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val slop = viewConfiguration.touchSlop
                val startsInLeadingEdge =
                    raftStartsInLeadingEdge(down.position.x, size.width.toFloat(), edgePx, isLtr)
                var claimed = false
                while (!claimed) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (!change.pressed || change.isConsumed) return@awaitEachGesture
                    val total = change.position - down.position
                    when (
                        raftPagingDragDecision(
                            dxPx = total.x,
                            dyPx = total.y,
                            touchSlopPx = slop,
                            isLtr = isLtr,
                            hostHasBackSwipe = hostHasBackSwipe,
                            startsInLeadingEdge = startsInLeadingEdge,
                            canPageBackward = currentCanPageBackward,
                        )
                    ) {
                        RaftPagingDragDecision.Undecided -> Unit
                        RaftPagingDragDecision.Release -> return@awaitEachGesture
                        RaftPagingDragDecision.Claim -> {
                            claimed = true
                            change.consume()
                            currentOnDragStart()
                            currentOnDrag(total.x)
                        }
                    }
                }
                var finished = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        val totalDx = change.position.x - down.position.x
                        if (!change.pressed) {
                            change.consume()
                            finished = true
                            currentOnDragEnd(totalDx)
                            break
                        }
                        currentOnDrag(totalDx)
                        change.consume()
                    }
                } finally {
                    // Also on cancellation (pointer input reset): the surface must settle back.
                    if (!finished) currentOnDragCancel()
                }
            }
        }
}
