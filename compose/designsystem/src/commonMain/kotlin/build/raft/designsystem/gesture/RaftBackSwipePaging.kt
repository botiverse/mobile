package build.raft.designsystem.gesture

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

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
 * Drives a horizontal pager (a `HorizontalPager` with `userScrollEnabled = false`, state [state])
 * from horizontal drags on its bounds, so the neighbouring page slides in under the finger and the
 * release settles with [flingBehavior] (the pager's snap fling), while cooperating with the host's
 * full-page back swipe (iOS: the page is a native push swiped away by `FDFullscreenPopGesture`):
 *
 * - On the first page a back-direction drag is left unconsumed, so the host pops the page.
 * - Past the first page a back drag pages back, except from the leading [RaftBackSwipeEdgeWidth]
 *   strip, which still pops the page; the surface registers itself as back-swipe content over the
 *   rest of its bounds so the native recogniser does not begin there.
 * - Vertical-dominant drags are left to the content (a swipe-down dismiss, a list).
 * - Content inside that consumes the movement (a zoomed image panning, a pinch, a dismiss drag)
 *   keeps it: the pager does not move while it does. Should the content stop consuming in the
 *   middle of the gesture (a zoomed image panned against its side edge hands the drag over), the
 *   pager measures from that point and takes the drag if it continues horizontally.
 *
 * The pager's own drag handling must stay disabled: it would consume the back drags above (which
 * makes the Compose touch recogniser win against the native pop) and could not be handed a drag in
 * the middle of a gesture. Scroll semantics are provided here instead. Without a host back swipe
 * (Android: Back is an OS gesture) every horizontal drag pages.
 */
@Composable
fun Modifier.raftBackSwipePaging(
    state: ScrollableState,
    flingBehavior: FlingBehavior,
): Modifier {
    val hostHasBackSwipe = LocalRaftBackSwipeRegions.current != null
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val edgePx = with(LocalDensity.current) { RaftBackSwipeEdgeWidth.toPx() }
    val scope = rememberCoroutineScope()
    val currentFling = rememberUpdatedState(flingBehavior)
    return this
        .raftBackSwipeRegion(RaftBackSwipeEdgeWidth) { state.canScrollBackward }
        .raftYieldingScrollSemantics(state, isLtr, scope, currentFling)
        .pointerInput(state, hostHasBackSwipe, isLtr, edgePx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val slop = viewConfiguration.touchSlop
                // The host's pop decides from where the gesture began, so the edge rule does too.
                val startsInLeadingEdge =
                    raftStartsInLeadingEdge(down.position.x, size.width.toFloat(), edgePx, isLtr)
                val tracker = VelocityTracker()
                tracker.addPointerInputChange(down)
                var origin = down.position
                var overSlop = 0f
                var claimed = false
                while (!claimed) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (!change.pressed) return@awaitEachGesture
                    if (change.isConsumed || event.changes.count { it.pressed } > 1) {
                        // Content owns this movement (or a second finger is down): measure any
                        // hand-over from here.
                        origin = change.position
                        tracker.resetTracking()
                        continue
                    }
                    tracker.addPointerInputChange(change)
                    val total = change.position - origin
                    when (
                        raftPagingDragDecision(
                            dxPx = total.x,
                            dyPx = total.y,
                            touchSlopPx = slop,
                            isLtr = isLtr,
                            hostHasBackSwipe = hostHasBackSwipe,
                            startsInLeadingEdge = startsInLeadingEdge,
                            canPageBackward = state.canScrollBackward,
                        )
                    ) {
                        RaftPagingDragDecision.Undecided -> Unit
                        RaftPagingDragDecision.Release -> return@awaitEachGesture
                        RaftPagingDragDecision.Claim -> {
                            claimed = true
                            overSlop = raftOverSlopPx(total.x, slop)
                            change.consume()
                        }
                    }
                }
                val deltas = Channel<Float>(Channel.UNLIMITED)
                val velocities = Channel<Float>(1)
                if (overSlop != 0f) deltas.trySend(raftForwardScrollDelta(overSlop, isLtr))
                scope.launch {
                    state.scroll(MutatePriority.UserInput) {
                        for (delta in deltas) scrollBy(delta)
                        val velocity = velocities.tryReceive().getOrNull() ?: 0f
                        with(currentFling.value) { performFling(velocity) }
                    }
                }
                var finalVelocity = 0f
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPointerInputChange(change)
                        if (!change.pressed) {
                            finalVelocity = raftForwardFlingVelocity(
                                tracker.calculateVelocity().x,
                                isLtr,
                                viewConfiguration.maximumFlingVelocity,
                            )
                            change.consume()
                            break
                        }
                        val dx = change.position.x - change.previousPosition.x
                        if (dx != 0f) deltas.trySend(raftForwardScrollDelta(dx, isLtr))
                        change.consume()
                    }
                } finally {
                    // Also on cancellation (pointer input reset): the scroll block must end and
                    // settle on a page, or it would hold the scroll mutex at UserInput priority.
                    velocities.trySend(finalVelocity)
                    deltas.close()
                }
            }
        }
}
