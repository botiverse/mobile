package build.raft.app.markdown

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.Modal
import build.raft.app.RaftAppRadius
import build.raft.app.RaftColor
import build.raft.app.RaftContentLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.base.Utils
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Pager
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.raftBottomSafeAreaPadding
import build.raft.app.raftTopSafeAreaPadding
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import build.raft.designsystem.gesture.raftBackSwipeRegion
import build.raft.designsystem.gesture.raftKeepsBackSwipe
import build.raft.designsystem.tokens.RaftSpacing
import build.raft.designsystem.tokens.RaftStroke
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.coroutines.delay

internal enum class RaftMermaidView { Diagram, Code }

/**
 * Draws the diagram into a viewport (see [RaftMermaidViewport]): at `fitScale` dp per scene unit at
 * zoom 1, zoomed and panned by `state`, with `panAlways` as in [RaftMermaidViewport], filling
 * `modifier`.
 */
internal typealias RaftMermaidDiagramContent =
    @Composable (fitScale: Float, state: RaftMermaidViewportState, panAlways: Boolean, modifier: Modifier) -> Unit

private val RaftMermaidInlineMinHeight = 160.dp
private val RaftMermaidInlineVerticalPadding = 16.dp
private val RaftMermaidInlineHorizontalPadding = 8.dp
private val RaftMermaidFullscreenPadding = 16.dp

/**
 * Card for a Mermaid block, matching the web: the code-block container (container radius, hairline
 * edge) with a header holding the Diagram / Code segmented control on the left and zoom, copy
 * source and full screen on the right. [diagramContent] draws the `diagramWidth × diagramHeight`
 * (scene units) diagram; the toolbar zoom buttons and the touch gestures share one zoom state.
 * [codeContent] is the source code block.
 */
@Composable
internal fun RaftMermaidCard(
    code: String,
    diagramWidth: Float,
    diagramHeight: Float,
    codeContent: @Composable () -> Unit,
    diagramContent: RaftMermaidDiagramContent
) {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    var view by remember(code) { mutableStateOf(RaftMermaidView.Diagram) }
    val zoom = remember(code) { RaftMermaidViewportState() }
    var fullscreen by remember(code) { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    val shape = RoundedCornerShape(RaftAppRadius.Container)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(shape)
                .background(RaftColor.SurfaceWhite)
                .border(RaftStroke.Hairline, RaftColor.BorderHairline, shape)
    ) {
        RaftDesignSystemTheme {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RaftSegmentedControl {
                    RaftSegment(
                        text = stringResource(MR.strings.markdown_mermaid_diagram),
                        selected = view == RaftMermaidView.Diagram,
                        onClick = { view = RaftMermaidView.Diagram }
                    )
                    RaftSegment(
                        text = stringResource(MR.strings.markdown_mermaid_code),
                        selected = view == RaftMermaidView.Code,
                        onClick = { view = RaftMermaidView.Code }
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(RaftSpacing.Xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (view == RaftMermaidView.Diagram) {
                        RaftMermaidToolbarButton(
                            icon = RaftLucideIconName.ZoomOut,
                            tooltip = stringResource(MR.strings.markdown_mermaid_zoom_out),
                            enabled = zoom.canZoomOut,
                            onClick = { zoom.zoomOut() }
                        )
                        RaftMermaidToolbarButton(
                            icon = RaftLucideIconName.ZoomIn,
                            tooltip = stringResource(MR.strings.markdown_mermaid_zoom_in),
                            enabled = zoom.canZoomIn,
                            onClick = { zoom.zoomIn() }
                        )
                    }
                    RaftMermaidToolbarButton(
                        icon = if (copied) RaftLucideIconName.Check else RaftLucideIconName.Copy,
                        tooltip = stringResource(MR.strings.markdown_mermaid_copy_source),
                        onClick = {
                            bridge.copyToPasteboard(code)
                            copied = true
                        }
                    )
                    if (view == RaftMermaidView.Diagram) {
                        RaftMermaidToolbarButton(
                            icon = RaftLucideIconName.Maximize2,
                            tooltip = stringResource(MR.strings.markdown_mermaid_fullscreen),
                            onClick = { fullscreen = true }
                        )
                    }
                }
            }
        }
        RaftMermaidHairline()
        if (view == RaftMermaidView.Code) {
            codeContent()
        } else {
            // Mermaid scene colours are light-theme ink, so the diagram keeps a paper panel (dark mode too).
            BoxWithConstraints(Modifier.fillMaxWidth().background(RaftColor.StickerPaper)) {
                val density = LocalDensity.current.density
                val fitScale =
                    raftMermaidFitScale(
                        sceneWidth = diagramWidth,
                        sceneHeight = diagramHeight,
                        availableWidth = (maxWidth - RaftMermaidInlineHorizontalPadding * 2).value,
                        density = density
                    )
                val height =
                    raftMermaidInlineViewportHeight(
                        sceneHeight = diagramHeight,
                        fitScale = fitScale,
                        density = density,
                        minHeight = RaftMermaidInlineMinHeight.value,
                        verticalPadding = RaftMermaidInlineVerticalPadding.value
                    )
                diagramContent(
                    fitScale,
                    zoom,
                    false,
                    Modifier
                        .fillMaxWidth()
                        .height(height.dp)
                        // While zoomed in a horizontal drag pans the diagram, not the page.
                        .raftBackSwipeRegion { zoom.canPan }
                )
            }
        }
    }

    if (fullscreen) {
        RaftMermaidFullscreen(
            diagramWidth = diagramWidth,
            diagramHeight = diagramHeight,
            onDismiss = { fullscreen = false },
            diagramContent = diagramContent
        )
    }
}

@Composable
private fun RaftMermaidFullscreen(
    diagramWidth: Float,
    diagramHeight: Float,
    onDismiss: () -> Unit,
    diagramContent: RaftMermaidDiagramContent
) {
    val topSafeArea = raftTopSafeAreaPadding()
    val bottomSafeArea = raftBottomSafeAreaPadding()
    val zoom = remember { RaftMermaidViewportState() }
    val dragX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    Modal(onWillDismiss = { onDismiss() }) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = dragX.value }
                    .raftMermaidSwipeToDismiss(
                        canStartAnywhere = { !zoom.canPan },
                        onDrag = { dx -> scope.launch { dragX.snapTo((dragX.value + dx).coerceAtLeast(0f)) } },
                        onRelease = { width, velocityX ->
                            scope.launch {
                                if (raftMermaidSwipeDismisses(dragX.value, width, velocityX)) {
                                    dragX.animateTo(width)
                                    currentOnDismiss()
                                } else {
                                    dragX.animateTo(0f)
                                }
                            }
                        },
                    )
                    .background(RaftColor.SurfaceWhite)
        ) {
            RaftDesignSystemTheme {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = topSafeArea + 6.dp, end = 8.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RaftSpacing.Xs)
                ) {
                    Spacer(Modifier.weight(1f))
                    RaftMermaidToolbarButton(
                        icon = RaftLucideIconName.ZoomOut,
                        tooltip = stringResource(MR.strings.markdown_mermaid_zoom_out),
                        enabled = zoom.canZoomOut,
                        onClick = { zoom.zoomOut() },
                        size = RaftIconButtonSize.Lg
                    )
                    RaftMermaidToolbarButton(
                        icon = RaftLucideIconName.ZoomIn,
                        tooltip = stringResource(MR.strings.markdown_mermaid_zoom_in),
                        enabled = zoom.canZoomIn,
                        onClick = { zoom.zoomIn() },
                        size = RaftIconButtonSize.Lg
                    )
                    RaftMermaidToolbarButton(
                        icon = RaftLucideIconName.X,
                        tooltip = stringResource(MR.strings.markdown_mermaid_close_fullscreen),
                        onClick = onDismiss,
                        size = RaftIconButtonSize.Lg
                    )
                }
            }
            RaftMermaidHairline()
            BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(RaftColor.StickerPaper)
                        .padding(bottom = bottomSafeArea)
            ) {
                val fitScale =
                    raftMermaidFitScale(
                        sceneWidth = diagramWidth,
                        sceneHeight = diagramHeight,
                        availableWidth = (maxWidth - RaftMermaidFullscreenPadding * 2).value,
                        availableHeight = (maxHeight - RaftMermaidFullscreenPadding * 2).value,
                        density = LocalDensity.current.density
                    )
                // Panning the diagram keeps every drag from the host back swipe.
                diagramContent(fitScale, zoom, true, Modifier.fillMaxSize().raftKeepsBackSwipe())
            }
        }
    }
}

@Composable
private fun RaftMermaidHairline() {
    Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(RaftColor.BorderHairline))
}

/** A ghost design-system icon button (32dp in the card header, 44dp in the full-screen top bar). */
@Composable
private fun RaftMermaidToolbarButton(
    icon: RaftLucideIconName,
    tooltip: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    size: RaftIconButtonSize = RaftIconButtonSize.Md
) {
    RaftIconButton(
        onClick = onClick,
        tooltip = tooltip,
        variant = RaftButtonVariant.Ghost,
        size = size,
        enabled = enabled
    ) {
        RaftContentLucideIcon(icon, size = raftMermaidToolbarGlyph(size))
    }
}

/** The design-system icon-button glyph size (Md 18dp, Lg 22dp). */
private fun raftMermaidToolbarGlyph(size: RaftIconButtonSize): Dp =
    if (size == RaftIconButtonSize.Lg) 22.dp else 18.dp


/**
 * A right swipe closes the full-screen diagram, like the back swipe of a pushed page (the modal
 * blocks the host's page back swipe): from the leading edge always, and anywhere while the diagram
 * is not zoomed in for panning ([canStartAnywhere]). Only a mostly horizontal rightward drag is
 * claimed; vertical drags, leftward drags and pinches stay with the diagram.
 */
private fun Modifier.raftMermaidSwipeToDismiss(
    canStartAnywhere: () -> Boolean,
    onDrag: (dx: Float) -> Unit,
    onRelease: (widthPx: Float, velocityX: Float) -> Unit,
): Modifier =
    pointerInput(Unit) {
        val edgePx = RaftMermaidSwipeEdge.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.x > edgePx && !canStartAnywhere()) return@awaitEachGesture
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            var claimed = false
            var total = Offset.Zero
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (!claimed && event.changes.size > 1) return@awaitEachGesture
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val delta = change.positionChange()
                total += delta
                tracker.addPointerInputChange(change)
                if (!claimed) {
                    when (raftMermaidSwipeClaim(total, viewConfiguration.touchSlop)) {
                        RaftMermaidSwipeClaim.Claim -> claimed = true
                        RaftMermaidSwipeClaim.Reject -> return@awaitEachGesture
                        RaftMermaidSwipeClaim.Undecided -> Unit
                    }
                }
                if (claimed) {
                    change.consume()
                    onDrag(delta.x)
                }
            }
            if (claimed) onRelease(size.width.toFloat(), tracker.calculateVelocity().x)
        }
    }

internal enum class RaftMermaidSwipeClaim { Undecided, Claim, Reject }

/** Claim a drag once it passes the slop going right and mostly sideways; reject vertical or leftward. */
internal fun raftMermaidSwipeClaim(total: Offset, touchSlop: Float): RaftMermaidSwipeClaim =
    when {
        total.x > touchSlop && total.x > abs(total.y) * 1.5f -> RaftMermaidSwipeClaim.Claim
        abs(total.y) > touchSlop || total.x < -touchSlop -> RaftMermaidSwipeClaim.Reject
        else -> RaftMermaidSwipeClaim.Undecided
    }

/** Dismiss past a third of the width, or on a quick flick to the right. */
internal fun raftMermaidSwipeDismisses(offsetPx: Float, widthPx: Float, velocityX: Float): Boolean =
    offsetPx > widthPx / 3f || (velocityX > RaftMermaidSwipeFlingVelocity && offsetPx > 0f)

private val RaftMermaidSwipeEdge = 24.dp
private const val RaftMermaidSwipeFlingVelocity = 1000f
