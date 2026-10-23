package build.raft.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Smallest the image shrinks to while it is dragged down (at a drag of one viewport height). */
internal const val RaftImageSwipeDismissMinScale = 0.8f

/** Horizontal movement allowed per unit of downward movement for a drag to count as "down". */
private const val RaftImageSwipeDismissMaxSlope = 0.7f

/** Decision of the swipe-down dismiss for one gesture. */
internal enum class RaftImageSwipeDismissDecision {
    /** Not enough movement yet. */
    Undecided,

    /** A mostly-vertical downward drag: the image follows the finger. */
    Claim,

    /** Sideways or upward: left to the pager, the back swipe or the content. */
    Release,
}

/**
 * Pure classification of a one-finger drag on an unzoomed image: claimed only once it is past the
 * touch slop, downward, and mostly vertical (at most [RaftImageSwipeDismissMaxSlope] sideways per
 * unit down), so it never fights the gallery's horizontal paging or the iOS back swipe.
 */
internal fun raftImageSwipeDismissDecision(dxPx: Float, dyPx: Float, touchSlopPx: Float): RaftImageSwipeDismissDecision {
    if (abs(dxPx) < touchSlopPx && abs(dyPx) < touchSlopPx) return RaftImageSwipeDismissDecision.Undecided
    return if (dyPx > 0f && abs(dxPx) <= dyPx * RaftImageSwipeDismissMaxSlope) {
        RaftImageSwipeDismissDecision.Claim
    } else {
        RaftImageSwipeDismissDecision.Release
    }
}

/** How far into a dismiss a downward drag of [dyPx] is: 0 at rest, 1 at half the viewport height. */
internal fun raftImageSwipeDismissProgress(dyPx: Float, viewportHeightPx: Float): Float {
    if (viewportHeightPx <= 0f || !dyPx.isFinite()) return 0f
    return (dyPx / (viewportHeightPx / 2f)).coerceIn(0f, 1f)
}

/** Image scale while dragged down by [dyPx]: shrinks slightly, to [RaftImageSwipeDismissMinScale]. */
internal fun raftImageSwipeDismissScale(dyPx: Float, viewportHeightPx: Float): Float {
    if (viewportHeightPx <= 0f || !dyPx.isFinite()) return 1f
    val fraction = (dyPx / viewportHeightPx).coerceIn(0f, 1f)
    return 1f - (1f - RaftImageSwipeDismissMinScale) * fraction
}

/** Opacity of the viewer's background and chrome while the image is dragged down by [dyPx]. */
internal fun raftImageSwipeDismissBackgroundAlpha(dyPx: Float, viewportHeightPx: Float): Float =
    1f - raftImageSwipeDismissProgress(dyPx, viewportHeightPx)

/**
 * Whether releasing a dismiss drag closes the viewer: dragged down past [distanceThresholdPx], or
 * flung down faster than [velocityThresholdPxPerSecond] (and still below where it started).
 * Otherwise the image springs back.
 */
internal fun raftImageSwipeDismissCloses(
    dyPx: Float,
    velocityYPxPerSecond: Float,
    distanceThresholdPx: Float,
    velocityThresholdPxPerSecond: Float,
): Boolean =
    dyPx >= distanceThresholdPx || (dyPx > 0f && velocityYPxPerSecond >= velocityThresholdPxPerSecond)

/** Offset of the image being dragged down to dismiss the viewer; shared by the image and the backdrop. */
@Stable
internal class RaftImageSwipeDismissState {
    /** Drag offset in px; [Offset.Zero] at rest. Read while drawing so a drag frame never recomposes. */
    var offset: Offset by mutableStateOf(Offset.Zero)
        private set

    /** Viewer height in px, written by the viewer as it lays out. */
    var viewportHeight: Float by mutableStateOf(0f)

    /** Set once a release closes the viewer: the image stays where it was let go while the page closes. */
    var dismissed: Boolean by mutableStateOf(false)
        private set

    private var settleJob: Job? = null

    val backgroundAlpha: Float get() = raftImageSwipeDismissBackgroundAlpha(offset.y, viewportHeight)
    val imageScale: Float get() = raftImageSwipeDismissScale(offset.y, viewportHeight)

    internal fun dragBy(delta: Offset) {
        settleJob?.cancel()
        settleJob = null
        offset += delta
    }

    internal fun dismiss() {
        dismissed = true
    }

    internal fun springBack(scope: CoroutineScope) {
        val from = offset
        settleJob?.cancel()
        settleJob =
            scope.launch {
                val animatable = Animatable(from, Offset.VectorConverter)
                animatable.animateTo(Offset.Zero, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)) {
                    offset = value
                }
                offset = Offset.Zero
            }
    }
}

/** Draws the page following [state]'s dismiss drag: moved with the finger and slightly shrunk. */
internal fun Modifier.raftImageSwipeDismissLayer(state: RaftImageSwipeDismissState): Modifier =
    graphicsLayer {
        val offset = state.offset
        translationX = offset.x
        translationY = offset.y
        val scale = state.imageScale
        scaleX = scale
        scaleY = scale
    }

/**
 * Swipe down to dismiss an image viewer page at 1x: a mostly-vertical downward one-finger drag
 * moves the image with the finger ([raftImageSwipeDismissLayer]); released past a distance or with
 * a fast downward fling it calls [onDismiss], otherwise it springs back. Never starts while
 * [zoomState] is zoomed in (the drag pans the image then), on a second finger (a pinch), or after
 * other content consumed the movement; sideways and upward drags are left alone for the pager and
 * the back swipe. [onDismiss] is read when the release happens, so it may read current state.
 */
internal fun Modifier.raftImageSwipeDismiss(
    state: RaftImageSwipeDismissState,
    zoomState: RaftZoomableImageState,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
): Modifier =
    pointerInput(state, zoomState) {
        val distanceThresholdPx = 120.dp.toPx()
        val velocityThresholdPx = 1000.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (state.dismissed || zoomState.transform.scale > 1f) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            var claimed = false
            while (!claimed) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed || change.isConsumed || event.changes.count { it.pressed } > 1) {
                    return@awaitEachGesture
                }
                tracker.addPointerInputChange(change)
                val total = change.position - down.position
                when (raftImageSwipeDismissDecision(total.x, total.y, slop)) {
                    RaftImageSwipeDismissDecision.Undecided -> Unit
                    RaftImageSwipeDismissDecision.Release -> return@awaitEachGesture
                    RaftImageSwipeDismissDecision.Claim -> {
                        claimed = true
                        // Start from the slop so the image does not jump to the finger.
                        state.dragBy(Offset(total.x, total.y - slop))
                        change.consume()
                    }
                }
            }
            var released = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    tracker.addPointerInputChange(change)
                    if (!change.pressed) {
                        change.consume()
                        released = true
                        break
                    }
                    // A second finger (pinch) or content taking over ends the dismiss drag.
                    if (change.isConsumed || event.changes.count { it.pressed } > 1) break
                    state.dragBy(change.positionChange())
                    change.consume()
                }
            } finally {
                val closes =
                    released &&
                        raftImageSwipeDismissCloses(
                            dyPx = state.offset.y,
                            velocityYPxPerSecond = tracker.calculateVelocity().y,
                            distanceThresholdPx = distanceThresholdPx,
                            velocityThresholdPxPerSecond = velocityThresholdPx,
                        )
                if (closes) {
                    state.dismiss()
                    onDismiss()
                } else {
                    state.springBack(scope)
                }
            }
        }
    }
