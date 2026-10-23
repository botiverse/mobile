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

/**
 * Size the image is drawn at inside [viewport] at 1x ([ContentScale.Fit][androidx.compose.ui.layout.ContentScale.Fit]):
 * [content] (its intrinsic size) scaled to fit. While [content] is unknown (not loaded yet) the
 * image is taken to fill the viewport.
 */
internal fun raftZoomableImageFittedSize(content: Size, viewport: Size): Size {
    if (content == Size.Unspecified || content.width <= 0f || content.height <= 0f ||
        !content.width.isFinite() || !content.height.isFinite()
    ) {
        return viewport
    }
    if (viewport.width <= 0f || viewport.height <= 0f) return viewport
    val fit = minOf(viewport.width / content.width, viewport.height / content.height)
    return Size(content.width * fit, content.height * fit)
}

/** Largest pan (px, each axis) that keeps the fitted image's edges at or beyond the viewport's. */
internal fun raftZoomableImageMaxPan(scale: Float, viewport: Size, content: Size): Offset {
    if (scale <= 1f || viewport.width <= 0f || viewport.height <= 0f) return Offset.Zero
    val fitted = raftZoomableImageFittedSize(content, viewport)
    return Offset(
        x = ((fitted.width * scale - viewport.width) / 2f).coerceAtLeast(0f),
        y = ((fitted.height * scale - viewport.height) / 2f).coerceAtLeast(0f)
    )
}

/**
 * [pan] limited so the image scaled by [scale] never leaves blank space inside [viewport]: along an
 * axis where the zoomed image (fitted from [content]) is larger than the viewport its edge stops at
 * the viewport's edge; along an axis where it is still smaller it stays centred.
 */
internal fun raftZoomableImageClampPan(
    pan: Offset,
    scale: Float,
    viewport: Size,
    content: Size = Size.Unspecified,
): Offset {
    val max = raftZoomableImageMaxPan(scale, viewport, content)
    val x = if (pan.x.isFinite()) pan.x.coerceIn(-max.x, max.x) else 0f
    val y = if (pan.y.isFinite()) pan.y.coerceIn(-max.y, max.y) else 0f
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
    content: Size = Size.Unspecified,
): RaftZoomableImageTransform {
    val factor = if (zoomChange.isFinite() && zoomChange > 0f) zoomChange else 1f
    val startScale = current.scale.takeIf { it.isFinite() && it > 0f } ?: 1f
    val scale = (startScale * factor).coerceIn(RaftZoomableImageMinScale, RaftZoomableImageMaxScale)
    val startPan = raftZoomableImageClampPan(current.pan, startScale, viewport, content)
    val anchor = if (focus.isSpecifiedAndFinite()) focus - viewport.center else Offset.Zero
    // The image point under the anchor sits at (anchor - pan) / scale from the image centre.
    val focalPan = anchor - (anchor - startPan) * (scale / startScale)
    val move = if (panChange.isSpecifiedAndFinite()) panChange else Offset.Zero
    return RaftZoomableImageTransform(scale, raftZoomableImageClampPan(focalPan + move, scale, viewport, content))
}

/**
 * Double tap target: an unzoomed image zooms to [RaftZoomableImageDoubleTapScale] around [tap]; a
 * zoomed or panned one goes back to 1x. The target pan is clamped to the fitted [content], so a tap
 * near an edge (or on the letterbox) never zooms into blank space.
 */
internal fun raftZoomableImageDoubleTapTarget(
    current: RaftZoomableImageTransform,
    viewport: Size,
    tap: Offset,
    content: Size = Size.Unspecified,
): RaftZoomableImageTransform =
    if (current.isIdentity) {
        raftZoomableImageTransformBy(
            RaftZoomableImageTransform(),
            viewport,
            tap,
            RaftZoomableImageDoubleTapScale,
            Offset.Zero,
            content
        )
    } else {
        RaftZoomableImageTransform()
    }

/**
 * Hand-over to a surrounding pager (iOS Photos): the horizontal push a zoomed one-finger pan keeps
 * making against the image's side edge, accumulated from [previous]. Movement that is not outward
 * against an edge the image already touches (or not horizontal-dominant) resets it to zero.
 */
internal fun raftZoomableImageEdgePush(
    previous: Offset,
    current: RaftZoomableImageTransform,
    viewport: Size,
    content: Size,
    panChange: Offset,
): Offset {
    if (current.scale <= 1f || !panChange.isSpecifiedAndFinite() || panChange.x == 0f) return Offset.Zero
    if (abs(panChange.x) < abs(panChange.y)) return Offset.Zero
    val maxX = raftZoomableImageMaxPan(current.scale, viewport, content).x
    // Finger right (pan grows) at pan == maxX: the image's left edge already sits on the viewport's.
    val atEdge =
        if (panChange.x > 0f) current.pan.x >= maxX - RaftZoomableImageEdgeTolerancePx
        else current.pan.x <= -maxX + RaftZoomableImageEdgeTolerancePx
    if (!atEdge) return Offset.Zero
    val sameDirection = previous == Offset.Zero || (previous.x > 0f) == (panChange.x > 0f)
    return if (sameDirection) previous + panChange else panChange
}

/** Whether an accumulated [edgePush] is a deliberate horizontal push to hand to the pager. */
internal fun raftZoomableImageHandsOffToPager(edgePush: Offset, touchSlop: Float): Boolean =
    abs(edgePush.x) > touchSlop && abs(edgePush.x) > abs(edgePush.y)

private const val RaftZoomableImageEdgeTolerancePx = 0.5f

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
internal class RaftZoomableImageState(
    /**
     * Inside a pager: a zoomed one-finger pan pushed on against the image's side edge stops panning
     * and leaves the rest of the drag to the pager (iOS Photos). Off for a lone image, where nothing
     * would take the drag over.
     */
    val handsOffAtHorizontalEdge: Boolean = false,
) {
    var transform: RaftZoomableImageTransform by mutableStateOf(RaftZoomableImageTransform())
        private set

    /** Viewport size in px, written by the viewer as it lays out. */
    internal var viewport: Size = Size.Zero

    /**
     * The image's intrinsic size, once loaded; pan is bounded by the image fitted into the viewport,
     * not by the viewport. [Size.Unspecified] until known.
     */
    internal var contentSize: Size = Size.Unspecified
        set(value) {
            if (field == value) return
            field = value
            // A size arriving under an existing zoom (the full-size image replacing the thumbnail)
            // re-applies the bounds; a running animation lands on its own clamped target.
            if (animationJob == null) {
                transform = transform.copy(pan = raftZoomableImageClampPan(transform.pan, transform.scale, viewport, value))
            }
        }

    private var animationJob: Job? = null
    private var animationTarget: RaftZoomableImageTransform? = null

    fun transformBy(focus: Offset, zoomChange: Float, panChange: Offset) {
        transform = raftZoomableImageTransformBy(transform, viewport, focus, zoomChange, panChange, contentSize)
    }

    fun edgePush(previous: Offset, panChange: Offset): Offset =
        raftZoomableImageEdgePush(previous, transform, viewport, contentSize, panChange)

    fun doubleTap(at: Offset, scope: CoroutineScope) {
        animateTo(raftZoomableImageDoubleTapTarget(transform, viewport, at, contentSize), scope)
    }

    /** Back to 1x at once (the image left the screen: another page of the gallery is shown). */
    fun reset() {
        stopAnimation()
        transform = RaftZoomableImageTransform()
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
