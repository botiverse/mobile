package build.raft.designsystem.gesture

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Decision of [rememberRaftBackSwipeDismiss] for one drag before it claims it. */
internal enum class RaftBackSwipeDismissDecision {
    /** Not enough movement yet. */
    Undecided,

    /** A back-direction horizontal drag: the overlay follows the finger. */
    Claim,

    /** Vertical-dominant or forward: left to the overlay's content. */
    Release,
}

/**
 * Pure classification of a drag on a back-swipe-dismissable overlay: back direction is physically
 * rightward in LTR and leftward in RTL.
 */
internal fun raftBackSwipeDismissDecision(dxPx: Float, dyPx: Float, touchSlopPx: Float, isLtr: Boolean): RaftBackSwipeDismissDecision {
    val adx = abs(dxPx)
    val ady = abs(dyPx)
    if (adx < touchSlopPx && ady < touchSlopPx) return RaftBackSwipeDismissDecision.Undecided
    if (ady >= adx) return RaftBackSwipeDismissDecision.Release
    val back = if (isLtr) dxPx > 0f else dxPx < 0f
    return if (back) RaftBackSwipeDismissDecision.Claim else RaftBackSwipeDismissDecision.Release
}

/** Physical horizontal finger movement as back-direction progress (positive = toward dismissal). */
internal fun raftBackSwipeDismissProgress(dxPx: Float, isLtr: Boolean): Float = if (isLtr) dxPx else -dxPx

/** Fling speed (px/s, back direction) that dismisses whatever the distance, like the native pop. */
internal const val RAFT_BACK_SWIPE_DISMISS_FLING_PX_PER_S = 800f

/**
 * Whether a released drag dismisses: past a third of the width, or flung in the back direction
 * (a fling the other way always cancels).
 */
internal fun raftBackSwipeDismissCommits(offsetPx: Float, widthPx: Float, backVelocityPxPerS: Float): Boolean {
    if (offsetPx <= 0f) return false
    if (backVelocityPxPerS >= RAFT_BACK_SWIPE_DISMISS_FLING_PX_PER_S) return true
    if (backVelocityPxPerS <= -RAFT_BACK_SWIPE_DISMISS_FLING_PX_PER_S) return false
    return widthPx > 0f && offsetPx >= widthPx / 3f
}

/**
 * For an overlay that Back dismisses (a card over the page's content), on a host with a full-page
 * back swipe (iOS): the swipe that would pop the page dismisses the overlay instead. While composed
 * it blocks the page's back swipe ([RaftBlockBackSwipe]) so the drag reaches Compose; a
 * back-direction horizontal drag no content below consumed then slides the overlay with the
 * finger and, released past a third of its width or flung, slides it out and calls [onDismiss];
 * otherwise it slides back. Put the returned modifier on the overlay's root. The overlay is
 * expected to leave composition on [onDismiss] (it stays slid out meanwhile).
 *
 * Inert (an empty modifier, nothing blocked) on hosts without a content back swipe (Android: the
 * system back gesture already reaches the overlay's back handler) or when [enabled] is false.
 */
@Composable
fun rememberRaftBackSwipeDismiss(enabled: Boolean = true, onDismiss: () -> Unit): Modifier {
    val regions = LocalRaftBackSwipeRegions.current
    val active = enabled && regions != null && LocalRaftBackSwipeActive.current
    RaftBlockBackSwipe(enabled = active)
    if (!active) return Modifier
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    val offset = remember { mutableFloatStateOf(0f) }
    val width = remember { mutableIntStateOf(0) }
    return remember(isLtr, scope) {
        Modifier
            .onSizeChanged { width.intValue = it.width }
            .pointerInput(isLtr) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    val tracker = VelocityTracker()
                    tracker.addPointerInputChange(down)
                    val slop = viewConfiguration.touchSlop
                    var claimed = false
                    while (!claimed) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        // Content below (a scroller away from its start, a seek bar) took the drag.
                        if (!change.pressed || change.isConsumed) return@awaitEachGesture
                        tracker.addPointerInputChange(change)
                        val total = change.position - down.position
                        when (raftBackSwipeDismissDecision(total.x, total.y, slop, isLtr)) {
                            RaftBackSwipeDismissDecision.Undecided -> Unit
                            RaftBackSwipeDismissDecision.Release -> return@awaitEachGesture
                            RaftBackSwipeDismissDecision.Claim -> {
                                claimed = true
                                change.consume()
                            }
                        }
                    }
                    var released = false
                    while (!released) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPointerInputChange(change)
                        if (!change.pressed) released = true
                        val dx = change.position.x - change.previousPosition.x
                        offset.floatValue = (offset.floatValue + raftBackSwipeDismissProgress(dx, isLtr)).coerceAtLeast(0f)
                        change.consume()
                    }
                    val backVelocity = raftBackSwipeDismissProgress(tracker.calculateVelocity().x, isLtr)
                    val widthPx = width.intValue.toFloat()
                    val commits = released && raftBackSwipeDismissCommits(offset.floatValue, widthPx, backVelocity)
                    scope.launch {
                        animate(offset.floatValue, if (commits) widthPx else 0f, animationSpec = tween(220)) { value, _ ->
                            offset.floatValue = value
                        }
                        if (commits) {
                            currentOnDismiss()
                            // Normally gone by now; an overlay that stayed comes back into view.
                            delay(600)
                            offset.floatValue = 0f
                        }
                    }
                }
            }
            // After the drag handler: it reads touches in the untranslated frame, so the finger's
            // movement is not offset by the slide it drives.
            .graphicsLayer { translationX = if (isLtr) offset.floatValue else -offset.floatValue }
    }
}
