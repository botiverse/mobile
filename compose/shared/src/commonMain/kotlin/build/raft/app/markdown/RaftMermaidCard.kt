package build.raft.app.markdown

import build.raft.designsystem.gesture.rememberRaftBackSwipeScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.Modal
import build.raft.app.RaftColor
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.base.Utils
import build.raft.app.components.RaftBrutalIconButton
import build.raft.app.resources.MR
import build.raft.app.raftBottomSafeAreaPadding
import build.raft.app.raftTopSafeAreaPadding
import build.raft.app.compat.ui.NestedScrollMode
import build.raft.app.compat.ui.nestedScroll
import build.raft.app.compat.ui.setProp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.compat.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.coroutines.delay

internal enum class RaftMermaidView { Diagram, Code }

// Same step as the web toolbar (MermaidToolbar ZOOM_FACTOR).
internal const val RaftMermaidZoomFactor = 1.2f
internal const val RaftMermaidMinScale = 0.25f
internal const val RaftMermaidMaxScale = 4f

internal fun raftMermaidZoomIn(scale: Float): Float =
    (scale * RaftMermaidZoomFactor).coerceAtMost(RaftMermaidMaxScale)

internal fun raftMermaidZoomOut(scale: Float): Float =
    (scale / RaftMermaidZoomFactor).coerceAtLeast(RaftMermaidMinScale)

// Toolbar zoom is relative to the fitted preview, so narrow screens and rotations
// recompute the base scale without resetting the user's zoom choice.
internal fun raftMermaidCardInlineScale(diagramWidth: Float, availableWidth: Float, zoom: Float): Float {
    if (!diagramWidth.isFinite() || diagramWidth <= 0f || !availableWidth.isFinite() || availableWidth <= 0f) {
        return zoom
    }
    return (availableWidth / diagramWidth).coerceAtMost(1f) * zoom
}

private val RaftMermaidInlineMinHeight = 160.dp
private val RaftMermaidToolbarButtonSize = 28.dp

/**
 * Card chrome shared by every platform's Mermaid block, matching the web card: a toolbar with the
 * Diagram / Code switch on the left and zoom, copy source and full screen on the right. The
 * platform `actual` only supplies the Canvas that draws the diagram at a given scale.
 */
@Composable
internal fun RaftMermaidCard(
    code: String,
    diagramWidth: Float,
    codeContent: @Composable () -> Unit,
    diagramContent: @Composable (scale: Float) -> Unit
) {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    var view by remember(code) { mutableStateOf(RaftMermaidView.Diagram) }
    var scale by remember(code) { mutableStateOf(1f) }
    var fullscreen by remember(code) { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(RaftColor.SurfaceWhite)
                .border(1.dp, RaftColor.BorderBlack)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RaftMermaidViewTab(
                icon = RaftLucideIconName.Image,
                label = stringResource(MR.strings.markdown_mermaid_diagram),
                selected = view == RaftMermaidView.Diagram,
                onClick = { view = RaftMermaidView.Diagram }
            )
            Spacer(Modifier.width(6.dp))
            RaftMermaidViewTab(
                icon = RaftLucideIconName.Code,
                label = stringResource(MR.strings.markdown_mermaid_code),
                selected = view == RaftMermaidView.Code,
                onClick = { view = RaftMermaidView.Code }
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (view == RaftMermaidView.Diagram) {
                    RaftMermaidToolbarButton(
                        icon = RaftLucideIconName.ZoomOut,
                        contentDescription = stringResource(MR.strings.markdown_mermaid_zoom_out),
                        enabled = scale > RaftMermaidMinScale,
                        onClick = { scale = raftMermaidZoomOut(scale) }
                    )
                    RaftMermaidToolbarButton(
                        icon = RaftLucideIconName.ZoomIn,
                        contentDescription = stringResource(MR.strings.markdown_mermaid_zoom_in),
                        enabled = scale < RaftMermaidMaxScale,
                        onClick = { scale = raftMermaidZoomIn(scale) }
                    )
                }
                RaftMermaidToolbarButton(
                    icon = if (copied) RaftLucideIconName.Check else RaftLucideIconName.Copy,
                    contentDescription = stringResource(MR.strings.markdown_mermaid_copy_source),
                    onClick = {
                        bridge.copyToPasteboard(code)
                        copied = true
                    }
                )
                if (view == RaftMermaidView.Diagram) {
                    RaftMermaidToolbarButton(
                        icon = RaftLucideIconName.Maximize2,
                        contentDescription = stringResource(MR.strings.markdown_mermaid_fullscreen),
                        onClick = { fullscreen = true }
                    )
                }
            }
        }
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(RaftColor.BorderBlack))
        if (view == RaftMermaidView.Code) {
            codeContent()
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                val fittedScale = raftMermaidCardInlineScale(diagramWidth, maxWidth.value, scale)
                val backSwipeRowState = rememberLazyListState()
                val backSwipeRow = rememberRaftBackSwipeScroll(backSwipeRowState)
                LazyRow(
                    state = backSwipeRowState,
                    userScrollEnabled = backSwipeRow.userScrollEnabled,
                    modifier =
                        Modifier.then(backSwipeRow.modifier)
                            .fillMaxWidth()
                            .heightIn(min = RaftMermaidInlineMinHeight)
                            .clipToBounds()
                            .padding(vertical = 16.dp)
                            // Android's same-axis parent interception uses scrollWithParent,
                            // separately from nested-scroll delta handoff. Keep both edges in
                            // this diagram, while the vertical message list retains its axis.
                            .setProp("scrollWithParent", 0)
                            .nestedScroll(
                                scrollUp = NestedScrollMode.SELF_ONLY,
                                scrollDown = NestedScrollMode.SELF_ONLY
                            ),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item { diagramContent(fittedScale) }
                }
            }
        }
    }

    if (fullscreen) {
        RaftMermaidFullscreen(
            onDismiss = { fullscreen = false },
            diagramContent = diagramContent
        )
    }
}

@Composable
private fun RaftMermaidFullscreen(
    onDismiss: () -> Unit,
    diagramContent: @Composable (scale: Float) -> Unit
) {
    val topSafeArea = raftTopSafeAreaPadding()
    val bottomSafeArea = raftBottomSafeAreaPadding()
    var scale by remember { mutableStateOf(1f) }
    var panX by remember { mutableStateOf(0f) }
    var panY by remember { mutableStateOf(0f) }
    Modal(onWillDismiss = { onDismiss() }) {
        Column(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = topSafeArea + 10.dp, end = 16.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Spacer(Modifier.weight(1f))
                RaftMermaidToolbarButton(
                    icon = RaftLucideIconName.ZoomOut,
                    contentDescription = stringResource(MR.strings.markdown_mermaid_zoom_out),
                    enabled = scale > RaftMermaidMinScale,
                    onClick = { scale = raftMermaidZoomOut(scale) },
                    size = 36.dp
                )
                RaftMermaidToolbarButton(
                    icon = RaftLucideIconName.ZoomIn,
                    contentDescription = stringResource(MR.strings.markdown_mermaid_zoom_in),
                    enabled = scale < RaftMermaidMaxScale,
                    onClick = { scale = raftMermaidZoomIn(scale) },
                    size = 36.dp
                )
                RaftMermaidToolbarButton(
                    icon = RaftLucideIconName.X,
                    contentDescription = stringResource(MR.strings.markdown_mermaid_close_fullscreen),
                    onClick = onDismiss,
                    size = 36.dp
                )
            }
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(RaftColor.BorderBlack))
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = bottomSafeArea)
                        .clipToBounds()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                panX += dragAmount.x
                                panY += dragAmount.y
                            }
                        },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier =
                        Modifier.graphicsLayer {
                            translationX = panX
                            translationY = panY
                        }
                ) {
                    diagramContent(scale)
                }
            }
        }
    }
}

@Composable
private fun RaftMermaidViewTab(
    icon: RaftLucideIconName,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .background(if (selected) RaftColor.BrandYellow else RaftColor.SurfaceWhite)
                .border(1.dp, RaftColor.BorderBlack)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RaftLucideIcon(name = icon, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = RaftColor.TextBlack)
        )
    }
}

@Composable
private fun RaftMermaidToolbarButton(
    icon: RaftLucideIconName,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    size: androidx.compose.ui.unit.Dp = RaftMermaidToolbarButtonSize
) {
    RaftBrutalIconButton(
        iconName = icon,
        onClick = onClick,
        contentDescription = contentDescription,
        enabled = enabled,
        disabledAlpha = 0.4f,
        size = size,
        shadowOffset = 2.dp
    )
}
