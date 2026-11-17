package build.raft.designsystem.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs

/** What a sideways drag over segmented content does: still undecided, turn a segment, or let it go. */
enum class RaftSegmentSwipeDecision { Undecided, Forward, Backward, Release }

/**
 * Pure decision for [raftSwipesBetweenSegments]: after [decidePx] of travel, a mostly horizontal drag
 * (|dx| > 2|dy|) toward a segment that exists turns to it; toward the edge, a vertical or diagonal drag,
 * it is released (to the list, or to the enclosing tab pager at the edge). Forward is toward the end
 * (a drag toward the start in LTR).
 */
fun raftSegmentSwipeDecision(
    dx: Float,
    dy: Float,
    decidePx: Float,
    isLtr: Boolean,
    canGoBackward: Boolean,
    canGoForward: Boolean,
): RaftSegmentSwipeDecision {
    val ax = abs(dx)
    val ay = abs(dy)
    if (ax < decidePx) {
        return if (ay >= decidePx) RaftSegmentSwipeDecision.Release else RaftSegmentSwipeDecision.Undecided
    }
    if (ax <= 2 * ay) return RaftSegmentSwipeDecision.Release
    val forward = (dx < 0) == isLtr
    return when {
        forward && canGoForward -> RaftSegmentSwipeDecision.Forward
        !forward && canGoBackward -> RaftSegmentSwipeDecision.Backward
        else -> RaftSegmentSwipeDecision.Release
    }
}

/**
 * One gesture's hand-over of horizontal scroll a child scroller left over (the segment track itself,
 * a code block) at its end: the sub-tabs get it before the enclosing tab pager, as a nested pager
 * would. The first leftover decides the gesture: toward a sub-tab that exists, the sub-tabs own it
 * (all of its leftover and fling, so the outer pager stays put) and turn once after [decidePx] of it;
 * toward the edge it passes through to the outer pager for the whole gesture.
 */
internal class RaftSegmentNestedSwipe(private val decidePx: Float) {
    private enum class Owner { Undecided, Segments, PassThrough }

    private var owner = Owner.Undecided
    private var leftover = 0f

    /** A new gesture went down. */
    fun reset() {
        owner = Owner.Undecided
        leftover = 0f
    }

    /** The pointer path turned the sub-tab: the rest of the gesture belongs to the sub-tabs. */
    fun claimedByDrag() {
        owner = Owner.Segments
        leftover = Float.NaN
    }

    /** Whether the sub-tabs own this gesture (its leftover fling is theirs too). */
    val owned: Boolean get() = owner == Owner.Segments

    /**
     * Horizontal leftover [dx] (pointer direction) from a child: returns how much of it the sub-tabs
     * consume, calling [turn] (forward = toward the end) when the gesture turns a sub-tab.
     */
    fun onLeftover(
        dx: Float,
        isLtr: Boolean,
        canGoBackward: Boolean,
        canGoForward: Boolean,
        turn: (forward: Boolean) -> Unit,
    ): Float {
        if (dx == 0f || owner == Owner.PassThrough) return 0f
        if (owner == Owner.Undecided) {
            val forward = (dx < 0) == isLtr
            owner = if (if (forward) canGoForward else canGoBackward) Owner.Segments else Owner.PassThrough
            if (owner == Owner.PassThrough) return 0f
        }
        if (!leftover.isNaN()) {
            leftover += dx
            if (abs(leftover) >= decidePx) {
                val forward = (leftover < 0) == isLtr
                leftover = Float.NaN
                if (if (forward) canGoForward else canGoBackward) turn(forward)
            }
        }
        return dx
    }
}

/**
 * Content with sub-tabs (a segmented switch over views) inside a tab pager page: a sideways swipe
 * turns the sub-tab first, and reaches the enclosing pager only at the first or last sub-tab, as
 * nested pagers do. Over this content the pager waits ([raftClaimsHorizontalDragFirst]) until the
 * swipe is decided. A drag a child took is left to it: a list's vertical scroll, or a horizontal
 * scroller (the segment track, a code block) while it scrolls; what such a scroller leaves over at its
 * end reaches the sub-tabs before the pager ([RaftSegmentNestedSwipe]), so a swipe on the sub-tab
 * switch itself turns the sub-tab too.
 */
@Composable
fun Modifier.raftSwipesBetweenSegments(
    canGoBackward: Boolean,
    canGoForward: Boolean,
    onSwipe: (forward: Boolean) -> Unit,
): Modifier {
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val decidePx = LocalViewConfiguration.current.touchSlop * 2
    val backward by rememberUpdatedState(canGoBackward)
    val forward by rememberUpdatedState(canGoForward)
    val swipe by rememberUpdatedState(onSwipe)
    val enabled = canGoBackward || canGoForward
    val nested = remember(decidePx) { RaftSegmentNestedSwipe(decidePx) }
    val connection =
        remember(nested, isLtr) {
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput) return Offset.Zero
                    val taken = nested.onLeftover(available.x, isLtr, backward, forward) { toEnd -> swipe(toEnd) }
                    return Offset(taken, 0f)
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                    if (nested.owned) Velocity(available.x, 0f) else Velocity.Zero
            }
        }
    if (!enabled) return this
    return this
        .raftClaimsHorizontalDragFirst { _, _ -> decidePx }
        .nestedScroll(connection)
        .pointerInput(isLtr, decidePx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                nested.reset()
                var claimed = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if (claimed) {
                        change.consume()
                        continue
                    }
                    // A second finger, or a child (a list's scroll) took the drag: not a sub-tab swipe.
                    if (event.changes.size > 1 || change.isConsumed) break
                    val delta = change.position - down.position
                    when (raftSegmentSwipeDecision(delta.x, delta.y, decidePx, isLtr, backward, forward)) {
                        RaftSegmentSwipeDecision.Undecided -> Unit
                        RaftSegmentSwipeDecision.Release -> break
                        RaftSegmentSwipeDecision.Forward, RaftSegmentSwipeDecision.Backward -> {
                            val toEnd = (delta.x < 0) == isLtr
                            claimed = true
                            nested.claimedByDrag()
                            change.consume()
                            swipe(toEnd)
                        }
                    }
                }
            }
        }
}
