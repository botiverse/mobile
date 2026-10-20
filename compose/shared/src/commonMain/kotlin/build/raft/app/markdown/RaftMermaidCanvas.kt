package build.raft.app.markdown

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.toSize
import build.raft.mermaid.compose.MermaidMaxSidePx
import build.raft.mermaid.compose.drawMermaidScene
import build.raft.mermaid.compose.measureMermaidScene
import build.raft.mermaid.compose.mermaidSafeScale
import build.raft.mermaid.layout.LayoutScene

/**
 * A Mermaid [scene] drawn by the `mermaid-compose` library into a clipped viewport, with zoom and
 * pan from [state] (toolbar buttons and touch gestures share it).
 *
 * At zoom 1 the diagram is drawn at [fitScale] dp per scene unit, centred. Scene text uses
 * [textStyle] as its base style (the message font) and is measured once per scene, measurer and
 * style; zooming and panning only redraw. The scene's accessibility title or description is the
 * viewport's content description.
 *
 * Gestures ([raftMermaidGestures]): two-finger pinch zooms around the fingers, a drag pans while
 * the diagram overflows the viewport, a double tap toggles between fit and 2x. With [panAlways]
 * false (inline in a message) a one-finger drag on a diagram that fits is left to the message list.
 */
@Composable
internal fun RaftMermaidViewport(
    scene: LayoutScene,
    textStyle: TextStyle,
    fitScale: Float,
    state: RaftMermaidViewportState,
    panAlways: Boolean,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val measured = remember(scene, textMeasurer, textStyle) { measureMermaidScene(scene, textMeasurer, textStyle) }
    // px per scene unit at zoom 1.
    val unit = fitScale * LocalDensity.current.density
    val sceneSize = Size(scene.width.toFloat(), scene.height.toFloat())
    SideEffect { state.fitted = sceneSize * unit }
    val description = listOf(scene.accessibilityTitle, scene.accessibilityDescription).firstOrNull { !it.isNullOrBlank() }
    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { state.viewport = it.toSize() }
            .raftMermaidGestures(state, panAlways)
            .then(if (description == null) Modifier else Modifier.semantics { contentDescription = description })
    ) {
        // Read while drawing: a zoom or pan frame redraws without recomposing.
        val transform = state.transform
        val scale = unit * transform.zoom
        if (scale <= 0f || !scale.isFinite()) return@Canvas
        val content = sceneSize * scale
        val origin = raftMermaidContentOrigin(raftMermaidClampPan(transform.pan, content, size), content, size)
        translate(left = origin.x, top = origin.y) {
            drawMermaidScene(measured, scale)
        }
    }
}

/**
 * Pinch-to-zoom, drag-to-pan and double tap for a diagram viewport.
 *
 * Scroll ownership: the message list keeps every one-finger drag that starts while the diagram fits
 * (unless [panAlways]); this handler never consumes those, and once another handler consumes the
 * gesture (the list started scrolling) it leaves the rest of it alone. It consumes movement only
 * while it owns the gesture: two or more fingers down, or the diagram overflows the viewport. Down
 * events are never consumed, so a long press still reaches the message row.
 */
private fun Modifier.raftMermaidGestures(state: RaftMermaidViewportState, panAlways: Boolean): Modifier =
    pointerInput(state, panAlways) {
        var lastTapUpMillis = 0L
        var lastTapPosition: Offset? = null
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val previousTap = lastTapPosition
            lastTapPosition = null
            val doubleTapSlop = viewConfiguration.touchSlop * 4f
            val secondTap =
                previousTap != null &&
                    down.uptimeMillis - lastTapUpMillis <= viewConfiguration.doubleTapTimeoutMillis &&
                    (down.position - previousTap).getDistance() <= doubleTapSlop
            var isTap = true
            var released = false
            var travelled = Offset.Zero
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                if (pressed == 0) {
                    val up = event.changes.firstOrNull()
                    if (isTap && !released && up != null && !up.isConsumed) {
                        if (secondTap) {
                            state.doubleTap(up.position)
                            up.consume()
                        } else {
                            lastTapPosition = up.position
                            lastTapUpMillis = up.uptimeMillis
                        }
                    }
                    break
                }
                if (pressed > 1) isTap = false
                if (released) continue
                if (event.changes.any { it.isConsumed }) {
                    released = true
                    isTap = false
                    continue
                }
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                travelled += panChange
                if (travelled.getDistance() > viewConfiguration.touchSlop) isTap = false
                if (pressed > 1 || panAlways || state.canPan) {
                    if (zoomChange != 1f || panChange != Offset.Zero) {
                        state.transformBy(event.calculateCentroid(useCurrent = true), zoomChange, panChange)
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                } else {
                    // The same event after every handler has seen it: if the message list consumed
                    // it, the list owns this drag now.
                    val finalPass = awaitPointerEvent(PointerEventPass.Final)
                    if (finalPass.changes.any { it.isConsumed }) {
                        released = true
                        isTap = false
                    }
                }
            }
        }
    }

/**
 * dp per scene unit that fits a `sceneWidth × sceneHeight` diagram into `availableWidth ×
 * availableHeight` dp without enlarging it past its natural size (an unbounded or unmeasured side
 * does not constrain). The result is kept within the library's [MermaidMaxSidePx] limit at
 * [density] and is 0 for an empty or invalid scene. Zoom is applied on top of this fitted scale,
 * so narrow screens and rotations refit without resetting the user's zoom.
 */
internal fun raftMermaidFitScale(
    sceneWidth: Float,
    sceneHeight: Float,
    availableWidth: Float,
    availableHeight: Float = Float.POSITIVE_INFINITY,
    density: Float,
): Float {
    var fit = 1f
    if (sceneWidth.isFinite() && sceneWidth > 0f && availableWidth.isFinite() && availableWidth > 0f) {
        fit = minOf(fit, availableWidth / sceneWidth)
    }
    if (sceneHeight.isFinite() && sceneHeight > 0f && availableHeight.isFinite() && availableHeight > 0f) {
        fit = minOf(fit, availableHeight / sceneHeight)
    }
    return mermaidSafeScale(sceneWidth, sceneHeight, fit, density)
}

/**
 * Height (dp) of the inline diagram viewport: the fitted diagram with [verticalPadding] above and
 * below, at least [minHeight], and never taller than Compose can lay out ([MermaidMaxSidePx]).
 */
internal fun raftMermaidInlineViewportHeight(
    sceneHeight: Float,
    fitScale: Float,
    density: Float,
    minHeight: Float,
    verticalPadding: Float,
): Float {
    val fitted = if (sceneHeight.isFinite() && fitScale.isFinite()) sceneHeight * fitScale else 0f
    val height = maxOf(minHeight, fitted + verticalPadding * 2f)
    return if (density.isFinite() && density > 0f) minOf(height, MermaidMaxSidePx / density) else height
}
