package build.raft.designsystem.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
 * Content with sub-tabs (a segmented switch over views) inside a tab pager page: a sideways swipe
 * turns the sub-tab first, and reaches the enclosing pager only at the first or last sub-tab, as
 * nested pagers do. Over this content the pager waits ([raftClaimsHorizontalDragFirst]) until the
 * swipe is decided; a drag a child took (a list's scroll, a horizontal scroller) is left to it.
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
    if (!enabled) return this
    return this
        .raftClaimsHorizontalDragFirst { _, _ -> decidePx }
        .pointerInput(isLtr, decidePx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
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
                            change.consume()
                            swipe(toEnd)
                        }
                    }
                }
            }
        }
}
