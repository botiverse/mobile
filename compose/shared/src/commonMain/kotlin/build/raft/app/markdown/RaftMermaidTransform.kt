package build.raft.app.markdown

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import kotlin.math.abs

// Same step as the web toolbar (MermaidToolbar ZOOM_FACTOR).
internal const val RaftMermaidZoomFactor = 1.2f
internal const val RaftMermaidMinScale = 0.25f
internal const val RaftMermaidMaxScale = 4f

/** Zoom a double tap on a fitted diagram goes to (a second double tap goes back to fit). */
internal const val RaftMermaidDoubleTapZoom = 2f

internal fun raftMermaidZoomIn(scale: Float): Float =
    (scale * RaftMermaidZoomFactor).coerceAtMost(RaftMermaidMaxScale)

internal fun raftMermaidZoomOut(scale: Float): Float =
    (scale / RaftMermaidZoomFactor).coerceAtLeast(RaftMermaidMinScale)

/** Zoom (relative to the fitted size) and pan (px, from the centred position) of one diagram viewport. */
@Immutable
internal data class RaftMermaidTransform(val zoom: Float = 1f, val pan: Offset = Offset.Zero) {
    /** At the fitted size (pan is always 0 there, the fitted diagram is centred). */
    val isFit: Boolean get() = abs(zoom - 1f) < 0.01f
}

/** Top-left (px in the viewport) of [content] drawn centred in [viewport] and moved by [pan]. */
internal fun raftMermaidContentOrigin(pan: Offset, content: Size, viewport: Size): Offset =
    Offset((viewport.width - content.width) / 2f + pan.x, (viewport.height - content.height) / 2f + pan.y)

/**
 * [pan] limited so [content] never leaves a gap inside [viewport]: on an axis where the content is
 * larger than the viewport its edges stay outside the viewport's; where it fits it stays centred.
 */
internal fun raftMermaidClampPan(pan: Offset, content: Size, viewport: Size): Offset {
    fun axis(value: Float, contentSide: Float, viewportSide: Float): Float {
        val slack = (contentSide - viewportSide) / 2f
        if (!slack.isFinite() || slack <= 0f || !value.isFinite()) return 0f
        return value.coerceIn(-slack, slack)
    }
    return Offset(axis(pan.x, content.width, viewport.width), axis(pan.y, content.height, viewport.height))
}

/** True when the diagram at [zoom] is larger than [viewport] on either axis, so a drag can pan it. */
internal fun raftMermaidCanPan(zoom: Float, fitted: Size, viewport: Size): Boolean {
    val content = fitted * zoom
    return content.width > viewport.width + 0.5f || content.height > viewport.height + 0.5f
}

/**
 * [current] zoomed by [zoomChange] around [centroid] (px in the viewport; the diagram point under it
 * stays under it), then moved by [panChange]. Zoom stays within
 * [RaftMermaidMinScale]..[RaftMermaidMaxScale] and pan within the content bounds
 * ([raftMermaidClampPan]). [fitted] is the diagram's size in px at zoom 1.
 */
internal fun raftMermaidTransformBy(
    current: RaftMermaidTransform,
    fitted: Size,
    viewport: Size,
    centroid: Offset,
    zoomChange: Float,
    panChange: Offset,
): RaftMermaidTransform {
    val factor = if (zoomChange.isFinite() && zoomChange > 0f) zoomChange else 1f
    val startZoom = current.zoom.takeIf { it.isFinite() && it > 0f } ?: 1f
    val zoom = (startZoom * factor).coerceIn(RaftMermaidMinScale, RaftMermaidMaxScale)
    val before = fitted * startZoom
    val after = fitted * zoom
    val originBefore = raftMermaidContentOrigin(raftMermaidClampPan(current.pan, before, viewport), before, viewport)
    val anchor = if (centroid.isSpecifiedAndFinite()) centroid else viewport.center
    val originAfter = anchor - (anchor - originBefore) * (zoom / startZoom)
    val centred = raftMermaidContentOrigin(Offset.Zero, after, viewport)
    val move = if (panChange.isSpecifiedAndFinite()) panChange else Offset.Zero
    return RaftMermaidTransform(zoom, raftMermaidClampPan(originAfter - centred + move, after, viewport))
}

/**
 * Double tap: a fitted diagram zooms to [RaftMermaidDoubleTapZoom] around [tap]; any other zoom
 * goes back to fit.
 */
internal fun raftMermaidDoubleTap(
    current: RaftMermaidTransform,
    fitted: Size,
    viewport: Size,
    tap: Offset,
): RaftMermaidTransform =
    if (current.isFit) {
        raftMermaidTransformBy(RaftMermaidTransform(), fitted, viewport, tap, RaftMermaidDoubleTapZoom, Offset.Zero)
    } else {
        RaftMermaidTransform()
    }

private fun Offset.isSpecifiedAndFinite(): Boolean = this != Offset.Unspecified && x.isFinite() && y.isFinite()

/**
 * Zoom and pan of one diagram viewport, shared by its toolbar buttons and its touch gestures.
 * Only [transform] is observable (the canvas reads it while drawing, so a gesture frame redraws
 * without recomposing); the geometry is written by the viewport as it lays out.
 */
@Stable
internal class RaftMermaidViewportState {
    var transform: RaftMermaidTransform by mutableStateOf(RaftMermaidTransform())
        private set

    /** Diagram size in px at zoom 1. */
    internal var fitted: Size = Size.Zero

    /** Viewport size in px. */
    internal var viewport: Size = Size.Zero

    val zoom: Float get() = transform.zoom

    val canZoomIn: Boolean get() = zoom < RaftMermaidMaxScale
    val canZoomOut: Boolean get() = zoom > RaftMermaidMinScale

    /** The diagram overflows the viewport, so a one-finger drag pans it instead of scrolling the page. */
    val canPan: Boolean get() = raftMermaidCanPan(zoom, fitted, viewport)

    fun transformBy(centroid: Offset, zoomChange: Float, panChange: Offset) {
        transform = raftMermaidTransformBy(transform, fitted, viewport, centroid, zoomChange, panChange)
    }

    fun zoomIn() = zoomTo(raftMermaidZoomIn(zoom))

    fun zoomOut() = zoomTo(raftMermaidZoomOut(zoom))

    fun doubleTap(at: Offset) {
        transform = raftMermaidDoubleTap(transform, fitted, viewport, at)
    }

    /** Toolbar zoom keeps the viewport centre in place. */
    private fun zoomTo(target: Float) = transformBy(viewport.center, target / zoom, Offset.Zero)
}
