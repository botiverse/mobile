package build.raft.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

internal const val RaftZoomableImageMinScale = 1f
internal const val RaftZoomableImageMaxScale = 4f

/** Zoom a double tap on an unzoomed image goes to (a double tap on a zoomed image goes back to 1x). */
internal const val RaftZoomableImageDoubleTapScale = 2.5f

internal const val RaftZoomableImageAnimationMillis = 250

/**
 * Zoom and pan of the image viewer. The image fills the viewport at [scale] 1 and is scaled around
 * the viewport centre; [pan] (px) moves the scaled image's centre away from the viewport centre.
 */
@Immutable
internal data class RaftZoomableImageTransform(val scale: Float = 1f, val pan: Offset = Offset.Zero) {
    /** At 1x and centred: the state a double tap zooms in from. */
    val isIdentity: Boolean get() = abs(scale - 1f) < 0.01f && abs(pan.x) < 0.5f && abs(pan.y) < 0.5f
}

/** [pan] limited so the image scaled by [scale] never leaves a gap inside [viewport]. */
internal fun raftZoomableImageClampPan(pan: Offset, scale: Float, viewport: Size): Offset {
    if (scale <= 1f || viewport.width <= 0f || viewport.height <= 0f) return Offset.Zero
    val maxX = viewport.width * (scale - 1f) / 2f
    val maxY = viewport.height * (scale - 1f) / 2f
    val x = if (pan.x.isFinite()) pan.x.coerceIn(-maxX, maxX) else 0f
    val y = if (pan.y.isFinite()) pan.y.coerceIn(-maxY, maxY) else 0f
    return Offset(x, y)
}

/**
 * [current] zoomed by [zoomChange] around [focus] (px in the viewport; the image point under it stays
 * under it), then moved by [panChange]. Scale stays within
 * [RaftZoomableImageMinScale]..[RaftZoomableImageMaxScale] and pan within the image bounds.
 */
internal fun raftZoomableImageTransformBy(
    current: RaftZoomableImageTransform,
    viewport: Size,
    focus: Offset,
    zoomChange: Float,
    panChange: Offset,
): RaftZoomableImageTransform {
    val factor = if (zoomChange.isFinite() && zoomChange > 0f) zoomChange else 1f
    val startScale = current.scale.takeIf { it.isFinite() && it > 0f } ?: 1f
    val scale = (startScale * factor).coerceIn(RaftZoomableImageMinScale, RaftZoomableImageMaxScale)
    val startPan = raftZoomableImageClampPan(current.pan, startScale, viewport)
    val anchor = if (focus.isSpecifiedAndFinite()) focus - viewport.center else Offset.Zero
    // The image point under the anchor sits at (anchor - pan) / scale from the image centre.
    val focalPan = anchor - (anchor - startPan) * (scale / startScale)
    val move = if (panChange.isSpecifiedAndFinite()) panChange else Offset.Zero
    return RaftZoomableImageTransform(scale, raftZoomableImageClampPan(focalPan + move, scale, viewport))
}

/**
 * Double tap target: an unzoomed image zooms to [RaftZoomableImageDoubleTapScale] around [tap]; a
 * zoomed or panned one goes back to 1x.
 */
internal fun raftZoomableImageDoubleTapTarget(
    current: RaftZoomableImageTransform,
    viewport: Size,
    tap: Offset,
): RaftZoomableImageTransform =
    if (current.isIdentity) {
        raftZoomableImageTransformBy(
            RaftZoomableImageTransform(),
            viewport,
            tap,
            RaftZoomableImageDoubleTapScale,
            Offset.Zero
        )
    } else {
        RaftZoomableImageTransform()
    }

/** Frame [fraction] (0..1) of the animation from [from] to [to]. */
internal fun raftZoomableImageLerp(
    from: RaftZoomableImageTransform,
    to: RaftZoomableImageTransform,
    fraction: Float,
): RaftZoomableImageTransform {
    val t = fraction.coerceIn(0f, 1f)
    return RaftZoomableImageTransform(
        scale = from.scale + (to.scale - from.scale) * t,
        pan = from.pan + (to.pan - from.pan) * t
    )
}

private fun Offset.isSpecifiedAndFinite(): Boolean = this != Offset.Unspecified && x.isFinite() && y.isFinite()

/**
 * Zoom state of one image in the viewer. Hoisted by the page and keyed by the image's identity, so
 * the full-size image replacing the thumbnail of the same image keeps the zoom, while moving to
 * another image starts at 1x.
 */
@Stable
internal class RaftZoomableImageState {
    var transform: RaftZoomableImageTransform by mutableStateOf(RaftZoomableImageTransform())
        private set

    /** Viewport size in px, written by the viewer as it lays out. */
    internal var viewport: Size = Size.Zero

    private var animationJob: Job? = null
    private var animationTarget: RaftZoomableImageTransform? = null

    fun transformBy(focus: Offset, zoomChange: Float, panChange: Offset) {
        transform = raftZoomableImageTransformBy(transform, viewport, focus, zoomChange, panChange)
    }

    fun doubleTap(at: Offset, scope: CoroutineScope) {
        animateTo(raftZoomableImageDoubleTapTarget(transform, viewport, at), scope)
    }

    /** A new touch takes over from a running double-tap animation where it is. */
    fun stopAnimation() {
        animationJob?.cancel()
        animationJob = null
        animationTarget = null
    }

    /** The viewer is leaving composition (e.g. the full-size image replaces the thumbnail): land the animation. */
    fun finishAnimation() {
        val target = animationTarget
        stopAnimation()
        if (target != null) transform = target
    }

    private fun animateTo(target: RaftZoomableImageTransform, scope: CoroutineScope) {
        stopAnimation()
        val from = transform
        animationTarget = target
        animationJob =
            scope.launch {
                val progress = Animatable(0f)
                progress.animateTo(1f, tween(RaftZoomableImageAnimationMillis)) {
                    transform = raftZoomableImageLerp(from, target, value)
                }
                transform = target
                animationTarget = null
            }
    }
}
