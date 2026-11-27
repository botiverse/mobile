package build.raft.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import build.raft.app.base.BridgeModule
import build.raft.app.cmp.RaftCmpRouter
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.raftPlatformName
import build.raft.app.compat.ui.LocalRaftHostImeInsets
import build.raft.app.compat.ui.raftImeBottomPx
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.ARTIFACT_CONTENT_DOMAIN
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.markdown.lucideIcon
import build.raft.app.resources.*
import build.raft.app.settings.RaftWebPageLoadingBar
import build.raft.app.settings.SettingsBillingOpenTarget
import build.raft.app.settings.SettingsWebViewAccessTokenRefresher
import build.raft.app.settings.SettingsWebViewAccessTokenSyncEffect
import build.raft.app.settings.SettingsWebViewSurface
import build.raft.app.settings.openRaftOwnWebPage
import build.raft.app.settings.openSettingsBillingPage
import build.raft.app.settings.raftUrlIsAppStoreRestrictedPurchaseUrl
import build.raft.app.settings.raftUrlIsOwnBillingPath
import build.raft.app.settings.raftWebPageLoadState
import build.raft.app.settings.rememberSettingsWebViewAuthSnapshot
import build.raft.app.settings.rememberSettingsWebViewHostAuthBinding
import build.raft.app.settings.settingsBillingOpenTarget
import build.raft.app.settings.settingsWebViewAuthEventSource
import build.raft.app.settings.settingsWebViewErrorBody
import build.raft.app.settings.standaloneSettingsWebBaseUrl
import build.raft.app.shell.RaftHorizontalSafeArea
import build.raft.app.shell.RaftInAppBrowserRouteScope
import build.raft.app.shell.RaftLinkOpenDecision
import build.raft.app.shell.RaftLinkOpenIntent
import build.raft.app.shell.RaftLinkOpenTarget
import build.raft.app.shell.appShellRailPlacesEnabled
import build.raft.app.shell.openRaftInAppBrowserOutboundChild
import build.raft.app.shell.raftBottomSafeAreaPadding
import build.raft.app.shell.raftOwnLinkIsWebPage
import build.raft.app.shell.raftOwnLinkShellOpener
import build.raft.app.shell.raftWindowTopSafeAreaPadding
import build.raft.app.shell.rememberRaftLinkNavigator
import build.raft.app.thread.RaftArtifactLinkInbox
import build.raft.app.thread.RaftConnectedAppLogo
import build.raft.app.thread.RaftMessageLinkIngress
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCloseButton
import build.raft.designsystem.components.RaftCloseButtonSize
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.gesture.raftKeepsEdgeTouches
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.webcontainer.WebContainerAndroidInsetMode
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/*
 * Unified Resizable Floating Mini-Window Overlay for Installed Raft Apps and Artifact Viewer
 * (dm:@artin:#175 Part 2):
 * - Mounted over Home / Chat / Threads without ever intercepting touches outside its bounding rect,
 *   so users can read, scroll, and reply in chat underneath while an App or Artifact stays live.
 * - Compact ~40dp drag bar with surface icon, single-line title, Collapse to Edge Pill, Expand to
 *   Full-Screen, and Close controls.
 * - Interactive bottom-left and bottom-right corner resize handles with strict safe-area / IME /
 *   min-max clamping and `.raftKeepsEdgeTouches()` so edge back-swipe gestures never hijack drags.
 * - Reuses the exact warm `keepAliveKey` for both Apps and Artifacts (zero reload on transitions)
 *   and preserves every security invariant (App Store 3.1.1, isolated storage partition, Tenny's 3
 *   Artifact WebView rules).
 */

/** Above content, bottom tab bar, and voice call card, below global modals/sheets/toasts. */
internal const val RaftFloatingMiniWindowZIndex: Float = RaftFloatingBrowserTabPillZIndex + 0.02f

internal const val RAFT_MINI_WINDOW_CONTAINER_TAG = "raft_mini_window_container"
internal const val RAFT_MINI_WINDOW_HEADER_TAG = "raft_mini_window_header"
internal const val RAFT_MINI_WINDOW_TITLE_TAG = "raft_mini_window_title"
internal const val RAFT_MINI_WINDOW_COLLAPSE_BUTTON_TAG = "raft_mini_window_collapse_button"
internal const val RAFT_MINI_WINDOW_EXPAND_BUTTON_TAG = "raft_mini_window_expand_button"
internal const val RAFT_MINI_WINDOW_CLOSE_BUTTON_TAG = "raft_mini_window_close_button"
internal const val RAFT_MINI_WINDOW_RESIZE_SE_TAG = "raft_mini_window_resize_se"
internal const val RAFT_MINI_WINDOW_RESIZE_SW_TAG = "raft_mini_window_resize_sw"

// app-only: compact floating mini-window drag bar chrome height
private val MiniWindowChromeBarHeight: Dp = 40.dp
private val MiniWindowResizeHandleSize: Dp = 30.dp
private val MiniWindowHorizontalMargin: Dp = 8.dp
private val MiniWindowHeaderGap: Dp = 8.dp
private val MiniWindowImeGap: Dp = 8.dp
private val MiniWindowBottomExtra: Dp = 12.dp
private val MiniWindowCornerRadius: Dp = RaftRadius.Container

/** Surface presentation metadata for the Mini-Window header chrome. */
internal sealed interface RaftMiniWindowHeaderIdentity {
    data class App(
        val appName: String,
        val appLogoUrl: String?,
        val title: String,
        val isDomainOnly: Boolean = false,
    ) : RaftMiniWindowHeaderIdentity

    data class Artifact(
        val title: String,
        val kind: ArtifactKind,
    ) : RaftMiniWindowHeaderIdentity
}

private class RaftMiniWindowMeasuredBoundsBox(
    var bounds: RaftFloatingMiniWindowBoundsPx,
)

private data class RaftMiniWindowHostMetrics(
    val viewportWidthPx: Float,
    val viewportHeightPx: Float,
    val leftSafeAreaPx: Float,
    val rightSafeAreaPx: Float,
    val topSafeAreaPx: Float,
    val bottomSafeAreaPx: Float,
    val headerGapPx: Float,
    val bottomExtraPx: Float,
    val horizontalMarginPx: Float,
    val imeGapPx: Float,
    val density: Density,
) {
    fun toViewportPx(imeBottomPx: Float): RaftFloatingMiniWindowViewportPx =
        RaftFloatingMiniWindowViewportPx(
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            leftSafeAreaPx = leftSafeAreaPx,
            rightSafeAreaPx = rightSafeAreaPx,
            topSafeAreaPx = topSafeAreaPx,
            bottomSafeAreaPx = bottomSafeAreaPx,
            imeBottomPx = imeBottomPx,
            headerGapPx = headerGapPx,
            bottomExtraPx = bottomExtraPx,
            horizontalMarginPx = horizontalMarginPx,
            imeGapPx = imeGapPx,
            density = density.density,
        )
}

/**
 * Pure, testable draggable + bottom-left/bottom-right resizable Mini-Window overlay surface.
 *
 * The outer `BoxWithConstraints` installs NO pointer listeners so touches outside the mini-window
 * bounding box pass straight through to the underlying conversation or home screen. Move-drag
 * updates read live coordinates strictly inside `.layout` placement so dragging skips composition
 * and measurement of [content].
 */
@Composable
internal fun RaftFloatingMiniWindowOverlay(
    identity: RaftMiniWindowHeaderIdentity,
    placement: RaftFloatingMiniWindowPlacement,
    onPlacementChange: (RaftFloatingMiniWindowPlacement) -> Unit,
    onCollapseToPill: () -> Unit,
    onExpandToFullScreen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    topSafeArea: Dp = raftWindowTopSafeAreaPadding(),
    bottomSafeArea: Dp = raftBottomSafeAreaPadding(),
    horizontalSafeArea: RaftHorizontalSafeArea = RaftHorizontalSafeArea.Zero,
    voiceCallExtraDp: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val hostImeInsets = LocalRaftHostImeInsets.current

    val topSafeAreaPx = with(density) { topSafeArea.coerceAtLeast(0.dp).toPx() }
    val bottomSafeAreaPx = with(density) { bottomSafeArea.coerceAtLeast(0.dp).toPx() }
    val leftSafeAreaPx = with(density) { horizontalSafeArea.left.coerceAtLeast(0.dp).toPx() }
    val rightSafeAreaPx = with(density) { horizontalSafeArea.right.coerceAtLeast(0.dp).toPx() }
    val headerGapPx = with(density) { MiniWindowHeaderGap.toPx() }
    val bottomExtraPx = with(density) { (MiniWindowBottomExtra + voiceCallExtraDp.coerceAtLeast(0.dp)).toPx() }
    val horizontalMarginPx = with(density) { MiniWindowHorizontalMargin.toPx() }
    val imeGapPx = with(density) { MiniWindowImeGap.toPx() }

    var localPlacement by remember { mutableStateOf(placement) }
    var draggingMove by remember { mutableStateOf(false) }
    var resizingCorner by remember { mutableStateOf<RaftFloatingMiniWindowResizeCorner?>(null) }
    var liveLeftPx by remember { mutableFloatStateOf(0f) }
    var liveTopPx by remember { mutableFloatStateOf(0f) }
    var liveWidthPx by remember { mutableFloatStateOf(with(density) { 300.dp.toPx() }) }
    var liveHeightPx by remember { mutableFloatStateOf(with(density) { 340.dp.toPx() }) }
    val measuredBoundsBox =
        remember {
            RaftMiniWindowMeasuredBoundsBox(
                RaftFloatingMiniWindowBoundsPx(0f, 0f, liveWidthPx, liveHeightPx),
            )
        }

    LaunchedEffect(placement) {
        if (!draggingMove && resizingCorner == null) {
            localPlacement = placement
        }
    }

    // Outer container NEVER installs pointerInput or clickable: touches outside the card pass through.
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().zIndex(RaftFloatingMiniWindowZIndex),
    ) {
        val viewportWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val viewportHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val hostMetrics =
            RaftMiniWindowHostMetrics(
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                leftSafeAreaPx = leftSafeAreaPx,
                rightSafeAreaPx = rightSafeAreaPx,
                topSafeAreaPx = topSafeAreaPx,
                bottomSafeAreaPx = bottomSafeAreaPx,
                headerGapPx = headerGapPx,
                bottomExtraPx = bottomExtraPx,
                horizontalMarginPx = horizontalMarginPx,
                imeGapPx = imeGapPx,
                density = density,
            )
        val latestMetrics by rememberUpdatedState(hostMetrics)
        val latestOnPlacementChange by rememberUpdatedState(onPlacementChange)

        fun resolveLiveViewport(): RaftFloatingMiniWindowViewportPx {
            val metrics = latestMetrics
            val imePx = raftImeBottomPx(imeInsets, hostImeInsets, metrics.density).toFloat()
            return metrics.toViewportPx(imePx)
        }

        val colors = RaftTheme.colors
        val dark = RaftTheme.isDark
        val cardShape = RoundedCornerShape(MiniWindowCornerRadius)

        Column(
            modifier =
                Modifier
                    .align(AbsoluteAlignment.TopLeft)
                    .layout { measurable, _ ->
                        val liveImePx = raftImeBottomPx(imeInsets, hostImeInsets, hostMetrics.density).toFloat()
                        val viewport = hostMetrics.toViewportPx(liveImePx)
                        val resolvedForMeasurement =
                            if (resizingCorner != null) {
                                RaftFloatingMiniWindowGeometry.clampBounds(
                                    RaftFloatingMiniWindowBoundsPx(
                                        leftPx = measuredBoundsBox.bounds.leftPx,
                                        topPx = measuredBoundsBox.bounds.topPx,
                                        widthPx = liveWidthPx,
                                        heightPx = liveHeightPx,
                                    ),
                                    viewport,
                                )
                            } else {
                                RaftFloatingMiniWindowGeometry.resolveBounds(localPlacement, viewport)
                            }
                        val widthInt = resolvedForMeasurement.widthPx.roundToInt().coerceAtLeast(1)
                        val heightInt = resolvedForMeasurement.heightPx.roundToInt().coerceAtLeast(1)
                        val placeable = measurable.measure(Constraints.fixed(widthInt, heightInt))
                        layout(placeable.width, placeable.height) {
                            // Read move-drag coordinates strictly in placement so dragging skips
                            // composition and measurement of the embedded WebView.
                            val finalBounds =
                                when {
                                    draggingMove ->
                                        RaftFloatingMiniWindowGeometry.clampBounds(
                                            RaftFloatingMiniWindowBoundsPx(
                                                leftPx = liveLeftPx,
                                                topPx = liveTopPx,
                                                widthPx = placeable.width.toFloat(),
                                                heightPx = placeable.height.toFloat(),
                                            ),
                                            viewport,
                                        )
                                    resizingCorner != null ->
                                        RaftFloatingMiniWindowGeometry.clampBounds(
                                            RaftFloatingMiniWindowBoundsPx(
                                                leftPx = liveLeftPx,
                                                topPx = liveTopPx,
                                                widthPx = placeable.width.toFloat(),
                                                heightPx = placeable.height.toFloat(),
                                            ),
                                            viewport,
                                        )
                                    else -> resolvedForMeasurement
                                }
                            measuredBoundsBox.bounds = finalBounds
                            placeable.place(
                                x = finalBounds.leftPx.roundToInt(),
                                y = finalBounds.topPx.roundToInt(),
                            )
                        }
                    }
                    .testTag(RAFT_MINI_WINDOW_CONTAINER_TAG)
                    .shadow(elevation = if (dark) 16.dp else 10.dp, shape = cardShape)
                    .clip(cardShape)
                    .background(colors.layerPopover, cardShape)
                    .border(
                        width = RaftStroke.Hairline,
                        color = if (resizingCorner != null || draggingMove) colors.lineStrong else colors.lineSeam,
                        shape = cardShape,
                    ),
        ) {
            RaftMiniWindowHeaderBar(
                identity = identity,
                onCollapseToPill = onCollapseToPill,
                onExpandToFullScreen = onExpandToFullScreen,
                onClose = onClose,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .raftKeepsEdgeTouches()
                        .pointerInput(Unit) {
                            fun finishMoveDrag() {
                                val viewport = resolveLiveViewport()
                                val finalBounds =
                                    RaftFloatingMiniWindowGeometry.clampBounds(
                                        RaftFloatingMiniWindowBoundsPx(
                                            leftPx = liveLeftPx,
                                            topPx = liveTopPx,
                                            widthPx = measuredBoundsBox.bounds.widthPx,
                                            heightPx = measuredBoundsBox.bounds.heightPx,
                                        ),
                                        viewport,
                                    )
                                val committed =
                                    RaftFloatingMiniWindowGeometry.boundsToPlacement(
                                        bounds = finalBounds,
                                        viewport = viewport,
                                        preservePreferredSizeFrom = localPlacement,
                                    )
                                localPlacement = committed
                                draggingMove = false
                                latestOnPlacementChange(committed)
                            }

                            detectDragGestures(
                                onDragStart = {
                                    val start = measuredBoundsBox.bounds
                                    liveLeftPx = start.leftPx
                                    liveTopPx = start.topPx
                                    liveWidthPx = start.widthPx
                                    liveHeightPx = start.heightPx
                                    resizingCorner = null
                                    draggingMove = true
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val viewport = resolveLiveViewport()
                                    val next =
                                        RaftFloatingMiniWindowGeometry.dragBy(
                                            bounds =
                                                RaftFloatingMiniWindowBoundsPx(
                                                    leftPx = liveLeftPx,
                                                    topPx = liveTopPx,
                                                    widthPx = measuredBoundsBox.bounds.widthPx,
                                                    heightPx = measuredBoundsBox.bounds.heightPx,
                                                ),
                                            deltaXPx = dragAmount.x,
                                            deltaYPx = dragAmount.y,
                                            viewport = viewport,
                                        )
                                    liveLeftPx = next.leftPx
                                    liveTopPx = next.topPx
                                },
                                onDragEnd = { finishMoveDrag() },
                                onDragCancel = { finishMoveDrag() },
                            )
                        },
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(RaftStroke.Hairline)
                        .background(colors.lineHairline),
            )
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(colors.layerCanvas),
            ) {
                content()
                RaftMiniWindowResizeHandle(
                    corner = RaftFloatingMiniWindowResizeCorner.BottomLeft,
                    active = resizingCorner == RaftFloatingMiniWindowResizeCorner.BottomLeft,
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .testTag(RAFT_MINI_WINDOW_RESIZE_SW_TAG)
                            .raftKeepsEdgeTouches()
                            .pointerInput(Unit) {
                                fun finishResize() {
                                    val viewport = resolveLiveViewport()
                                    val finalBounds =
                                        RaftFloatingMiniWindowGeometry.clampBounds(
                                            RaftFloatingMiniWindowBoundsPx(
                                                leftPx = liveLeftPx,
                                                topPx = liveTopPx,
                                                widthPx = liveWidthPx,
                                                heightPx = liveHeightPx,
                                            ),
                                            viewport,
                                        )
                                    val committed =
                                        RaftFloatingMiniWindowGeometry.boundsToPlacement(
                                            bounds = finalBounds,
                                            viewport = viewport,
                                            preservePreferredSizeFrom = null,
                                        )
                                    localPlacement = committed
                                    resizingCorner = null
                                    latestOnPlacementChange(committed)
                                }

                                detectDragGestures(
                                    onDragStart = {
                                        val start = measuredBoundsBox.bounds
                                        liveLeftPx = start.leftPx
                                        liveTopPx = start.topPx
                                        liveWidthPx = start.widthPx
                                        liveHeightPx = start.heightPx
                                        draggingMove = false
                                        resizingCorner = RaftFloatingMiniWindowResizeCorner.BottomLeft
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val viewport = resolveLiveViewport()
                                        val next =
                                            RaftFloatingMiniWindowGeometry.resizeBy(
                                                bounds =
                                                    RaftFloatingMiniWindowBoundsPx(
                                                        leftPx = liveLeftPx,
                                                        topPx = liveTopPx,
                                                        widthPx = liveWidthPx,
                                                        heightPx = liveHeightPx,
                                                    ),
                                                corner = RaftFloatingMiniWindowResizeCorner.BottomLeft,
                                                deltaXPx = dragAmount.x,
                                                deltaYPx = dragAmount.y,
                                                viewport = viewport,
                                            )
                                        liveLeftPx = next.leftPx
                                        liveTopPx = next.topPx
                                        liveWidthPx = next.widthPx
                                        liveHeightPx = next.heightPx
                                    },
                                    onDragEnd = { finishResize() },
                                    onDragCancel = { finishResize() },
                                )
                            },
                )
                RaftMiniWindowResizeHandle(
                    corner = RaftFloatingMiniWindowResizeCorner.BottomRight,
                    active = resizingCorner == RaftFloatingMiniWindowResizeCorner.BottomRight,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .testTag(RAFT_MINI_WINDOW_RESIZE_SE_TAG)
                            .raftKeepsEdgeTouches()
                            .pointerInput(Unit) {
                                fun finishResize() {
                                    val viewport = resolveLiveViewport()
                                    val finalBounds =
                                        RaftFloatingMiniWindowGeometry.clampBounds(
                                            RaftFloatingMiniWindowBoundsPx(
                                                leftPx = liveLeftPx,
                                                topPx = liveTopPx,
                                                widthPx = liveWidthPx,
                                                heightPx = liveHeightPx,
                                            ),
                                            viewport,
                                        )
                                    val committed =
                                        RaftFloatingMiniWindowGeometry.boundsToPlacement(
                                            bounds = finalBounds,
                                            viewport = viewport,
                                            preservePreferredSizeFrom = null,
                                        )
                                    localPlacement = committed
                                    resizingCorner = null
                                    latestOnPlacementChange(committed)
                                }

                                detectDragGestures(
                                    onDragStart = {
                                        val start = measuredBoundsBox.bounds
                                        liveLeftPx = start.leftPx
                                        liveTopPx = start.topPx
                                        liveWidthPx = start.widthPx
                                        liveHeightPx = start.heightPx
                                        draggingMove = false
                                        resizingCorner = RaftFloatingMiniWindowResizeCorner.BottomRight
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val viewport = resolveLiveViewport()
                                        val next =
                                            RaftFloatingMiniWindowGeometry.resizeBy(
                                                bounds =
                                                    RaftFloatingMiniWindowBoundsPx(
                                                        leftPx = liveLeftPx,
                                                        topPx = liveTopPx,
                                                        widthPx = liveWidthPx,
                                                        heightPx = liveHeightPx,
                                                    ),
                                                corner = RaftFloatingMiniWindowResizeCorner.BottomRight,
                                                deltaXPx = dragAmount.x,
                                                deltaYPx = dragAmount.y,
                                                viewport = viewport,
                                            )
                                        liveLeftPx = next.leftPx
                                        liveTopPx = next.topPx
                                        liveWidthPx = next.widthPx
                                        liveHeightPx = next.heightPx
                                    },
                                    onDragEnd = { finishResize() },
                                    onDragCancel = { finishResize() },
                                )
                            },
                )
            }
        }
    }
}

@Composable
private fun RaftMiniWindowHeaderBar(
    identity: RaftMiniWindowHeaderIdentity,
    onCollapseToPill: () -> Unit,
    onExpandToFullScreen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val dragA11y = stringResource(Res.string.floating_mini_window_drag_a11y)
    val collapseLabel = stringResource(Res.string.floating_mini_window_collapse_to_pill)
    val expandLabel = stringResource(Res.string.floating_mini_window_expand_full_screen)
    val closeLabel = stringResource(Res.string.common_close)

    Box(
        modifier =
            modifier
                .height(MiniWindowChromeBarHeight)
                .background(colors.layerPopover)
                .testTag(RAFT_MINI_WINDOW_HEADER_TAG)
                .semantics { contentDescription = dragA11y },
    ) {
        // Subtle top-center drag pill affordance.
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
                    .size(width = 28.dp, height = 3.dp)
                    .clip(CircleShape)
                    .background(colors.lineStrong.copy(alpha = 0.55f)),
        )
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(start = 10.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (identity) {
                is RaftMiniWindowHeaderIdentity.App -> {
                    if (!identity.isDomainOnly) {
                        RaftConnectedAppLogo(
                            name = identity.appName,
                            logoUrl = identity.appLogoUrl,
                            size = RaftAvatarSize.Xs,
                        )
                    }
                    if (identity.isDomainOnly) {
                        RaftSiteDomainText(
                            host = identity.title,
                            modifier = Modifier.weight(1f).testTag(RAFT_MINI_WINDOW_TITLE_TAG),
                            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.foreground,
                        )
                    } else {
                        RaftText(
                            text = identity.title,
                            modifier = Modifier.weight(1f).testTag(RAFT_MINI_WINDOW_TITLE_TAG),
                            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.foreground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                is RaftMiniWindowHeaderIdentity.Artifact -> {
                    Box(
                        modifier =
                            Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(RaftRadius.Chip))
                                .background(colors.fillSubtle)
                                .border(RaftStroke.Hairline, colors.lineHairline, RoundedCornerShape(RaftRadius.Chip)),
                        contentAlignment = Alignment.Center,
                    ) {
                        RaftSlotIcon(
                            name = identity.kind.lucideIcon(),
                            size = 14.dp,
                            color = colors.foregroundMuted,
                        )
                    }
                    RaftText(
                        text = identity.title,
                        modifier = Modifier.weight(1f).testTag(RAFT_MINI_WINDOW_TITLE_TAG),
                        style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftIconButton(
                    onClick = onCollapseToPill,
                    tooltip = collapseLabel,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftIconButtonSize.Sm,
                    hitSize = 30.dp,
                    modifier = Modifier.testTag(RAFT_MINI_WINDOW_COLLAPSE_BUTTON_TAG),
                ) {
                    RaftGlyphIcon(glyph = RaftGlyph.Minus, size = 15.dp)
                }
                RaftIconButton(
                    onClick = onExpandToFullScreen,
                    tooltip = expandLabel,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftIconButtonSize.Sm,
                    hitSize = 30.dp,
                    modifier = Modifier.testTag(RAFT_MINI_WINDOW_EXPAND_BUTTON_TAG),
                ) {
                    RaftSlotIcon(RaftLucideIconName.Maximize2, size = 15.dp)
                }
                RaftCloseButton(
                    onClick = onClose,
                    label = closeLabel,
                    size = RaftCloseButtonSize.Dialog,
                    modifier = Modifier.testTag(RAFT_MINI_WINDOW_CLOSE_BUTTON_TAG),
                )
            }
        }
    }
}

@Composable
private fun RaftMiniWindowResizeHandle(
    corner: RaftFloatingMiniWindowResizeCorner,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val a11y = stringResource(Res.string.floating_mini_window_resize_a11y)
    val gripColor = if (active) colors.brand else colors.foregroundHint.copy(alpha = 0.7f)

    Box(
        modifier =
            modifier
                .size(MiniWindowResizeHandleSize)
                .semantics { contentDescription = a11y },
        contentAlignment =
            if (corner == RaftFloatingMiniWindowResizeCorner.BottomLeft) {
                Alignment.BottomStart
            } else {
                Alignment.BottomEnd
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val inset = 6.dp.toPx()
            val arm = 10.dp.toPx()
            val strokePx = (if (active) 2.25.dp else 1.75.dp).toPx()
            val path =
                Path().apply {
                    if (corner == RaftFloatingMiniWindowResizeCorner.BottomRight) {
                        val right = size.width - inset
                        val bottom = size.height - inset
                        moveTo(right, bottom - arm)
                        lineTo(right, bottom - 3.dp.toPx())
                        quadraticTo(right, bottom, right - 3.dp.toPx(), bottom)
                        lineTo(right - arm, bottom)
                    } else {
                        val left = inset
                        val bottom = size.height - inset
                        moveTo(left, bottom - arm)
                        lineTo(left, bottom - 3.dp.toPx())
                        quadraticTo(left, bottom, left + 3.dp.toPx(), bottom)
                        lineTo(left + arm, bottom)
                    }
                }
            drawPath(
                path = path,
                color = gripColor,
                style =
                    Stroke(
                        width = strokePx,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
            )
            // Subtle inner diagonal tick to reinforce diagonal resize affordance.
            val tickOffset = 10.dp.toPx()
            val tickLen = 3.5.dp.toPx()
            if (corner == RaftFloatingMiniWindowResizeCorner.BottomRight) {
                val x1 = size.width - tickOffset - tickLen
                val y1 = size.height - tickOffset
                val x2 = size.width - tickOffset
                val y2 = size.height - tickOffset - tickLen
                drawLine(
                    color = gripColor.copy(alpha = 0.55f),
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = 1.25.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            } else {
                val x1 = tickOffset
                val y1 = size.height - tickOffset - tickLen
                val x2 = tickOffset + tickLen
                val y2 = size.height - tickOffset
                drawLine(
                    color = gripColor.copy(alpha = 0.55f),
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = 1.25.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/**
 * Live runtime host mounting [RaftFloatingMiniWindowOverlay] for either an Installed Raft App tab
 * or a Floating Artifact window, reusing the exact warm `keepAliveKey` without reloading.
 */
@Composable
internal fun RaftFloatingMiniWindowLiveHost(
    entry: RaftFloatingWindowEntry,
    pager: Pager,
    bridge: BridgeModule,
    storeScope: RaftShellStoreScope?,
    floatingCoordinator: RaftFloatingWindowCoordinator,
    placement: RaftFloatingMiniWindowPlacement,
    onPlacementChange: (RaftFloatingMiniWindowPlacement) -> Unit,
    onCollapseToPill: () -> Unit,
    onExpandToFullScreen: () -> Unit,
    onClose: () -> Unit,
    horizontalSafeArea: RaftHorizontalSafeArea,
    voiceCallExtraDp: Dp,
) {
    val untitled = stringResource(Res.string.artifacts_activity_untitled)
    var appLiveUrl by remember(entry.id) {
        mutableStateOf(
            when (entry) {
                is RaftFloatingWindowEntry.AppTab -> entry.tab.currentUrl
                is RaftFloatingWindowEntry.Artifact -> ""
            },
        )
    }

    val headerIdentity =
        when (entry) {
            is RaftFloatingWindowEntry.AppTab -> {
                val onScope = raftAppIsOnScopeOrigin(appLiveUrl, entry.tab.scopeOrigin)
                if (onScope) {
                    RaftMiniWindowHeaderIdentity.App(
                        appName = entry.tab.displayName,
                        appLogoUrl = entry.tab.appLogoUrl,
                        title = entry.tab.switcherPrimaryLabel,
                        isDomainOnly = false,
                    )
                } else {
                    RaftMiniWindowHeaderIdentity.App(
                        appName = entry.tab.displayName,
                        appLogoUrl = null,
                        title = raftMobileWebPanelDisplayHost(appLiveUrl).ifBlank { entry.tab.displayHost },
                        isDomainOnly = true,
                    )
                }
            }
            is RaftFloatingWindowEntry.Artifact ->
                RaftMiniWindowHeaderIdentity.Artifact(
                    title = entry.artifact.displayTitle.ifBlank { untitled },
                    kind = entry.artifact.kind,
                )
        }

    RaftFloatingMiniWindowOverlay(
        identity = headerIdentity,
        placement = placement,
        onPlacementChange = onPlacementChange,
        onCollapseToPill = onCollapseToPill,
        onExpandToFullScreen = onExpandToFullScreen,
        onClose = onClose,
        horizontalSafeArea = horizontalSafeArea,
        voiceCallExtraDp = voiceCallExtraDp,
    ) {
        key(entry.id) {
            when (entry) {
                is RaftFloatingWindowEntry.AppTab ->
                    RaftMiniWindowAppWebBody(
                        tab = entry.tab,
                        pager = pager,
                        bridge = bridge,
                        storeScope = storeScope,
                        floatingCoordinator = floatingCoordinator,
                        onCurrentUrlChanged = { appLiveUrl = it },
                        onClose = onClose,
                    )
                is RaftFloatingWindowEntry.Artifact ->
                    RaftMiniWindowArtifactWebBody(
                        entry = entry.artifact,
                        pager = pager,
                        bridge = bridge,
                        storeScope = entry.artifact.storeScope ?: storeScope,
                        floatingCoordinator = floatingCoordinator,
                        onClose = onClose,
                    )
            }
        }
    }
}

/**
 * Live App WebView body hosted inside the resizable Mini-Window: reuses [RaftAppBrowserTab.keepAliveKey]
 * and [RaftAppBrowserTab.loadedBaseUrl] for zero-reload attachment while preserving App Store 3.1.1
 * guards, storage partition isolation, OAuth #356 native sign-in sheet, and cross-origin outbound routing.
 */
@Composable
private fun RaftMiniWindowAppWebBody(
    tab: RaftAppBrowserTab,
    pager: Pager,
    bridge: BridgeModule,
    storeScope: RaftShellStoreScope?,
    floatingCoordinator: RaftFloatingWindowCoordinator,
    onCurrentUrlChanged: (String) -> Unit,
    onClose: () -> Unit,
) {
    val webPanelCoordinator = floatingCoordinator.webPanelCoordinator
    val appStoreBillingRules = raftPlatformFollowsAppStoreBillingRules()
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val activeEnvironment by sharedStores.apiEnvironmentStore.state.collectAsState()
    val activeWebBaseUrl = activeEnvironment.webUrl
    val activeApiBaseUrl = activeEnvironment.apiBaseUrl
    val sessionKey = "mini-app-tab:${tab.tabId}"

    val launchBlocked =
        remember(tab.loadedBaseUrl, activeWebBaseUrl, appStoreBillingRules) {
            raftInAppBrowserShouldBlockUrl(tab.loadedBaseUrl, activeWebBaseUrl, appStoreBillingRules)
        }
    val effectiveLaunchUrl = remember(sessionKey, tab.loadedBaseUrl, launchBlocked) { tab.loadedBaseUrl.takeUnless { launchBlocked } }
    val entryRequest =
        remember(tab.appEntryToken, tab.launchUrl, tab.entryOwnerId) {
            RaftAppEntryRegistry.process.claim(tab.appEntryToken, tab.launchUrl, tab.entryOwnerId)
        }
    val entryContext =
        remember(entryRequest, effectiveLaunchUrl, activeWebBaseUrl, activeApiBaseUrl) {
            if (entryRequest != null && effectiveLaunchUrl != null) {
                raftAppEntryContext(
                    request = entryRequest,
                    url = effectiveLaunchUrl,
                    raftHosts = raftBrowserRaftHosts(activeWebBaseUrl, activeApiBaseUrl),
                    debug = RaftCmpRouter.isDebugBuild,
                    platformName = raftPlatformName(),
                )
            } else {
                null
            }
        }
    val useEphemeralStore =
        remember(effectiveLaunchUrl, activeWebBaseUrl, appStoreBillingRules) {
            raftInAppBrowserUsesEphemeralStore(effectiveLaunchUrl, activeWebBaseUrl, appStoreBillingRules)
        }
    val storageResolution =
        remember(entryContext, useEphemeralStore) {
            resolveRaftMobileWebPanelStorage(
                isRaftAppEntry = entryContext != null,
                useEphemeralStore = useEphemeralStore,
                supportsIsolatedThirdPartyProfile = raftPlatformSupportsIsolatedWebViewProfile(),
            )
        }

    // Declared before RaftWebView so onDispose runs after child onRelease and immediately destroys
    // any keep-alive slot belonging to an explicitly closed tab.
    DisposableEffect(tab.tabId, tab.keepAliveKey) {
        onDispose {
            if (!webPanelCoordinator.isKeepAliveEligible(tab.keepAliveKey)) {
                raftWebViewKeepAliveEvict(tab.keepAliveKey)
            }
        }
    }

    val failClosedAction =
        resolveRaftMobileWebPanelFailClosedAction(
            storageResolution = storageResolution,
            effectiveLaunchUrl = effectiveLaunchUrl,
            closing = false,
            wasAuthorizedAppEntry = true,
        )
    LaunchedEffect(failClosedAction) {
        if (failClosedAction != RaftMobileWebPanelFailClosedAction.None) {
            onClose()
        }
    }
    if (storageResolution !is RaftMobileWebPanelLaunchResolution.OpenInPanel || effectiveLaunchUrl == null) {
        return
    }

    val controller = remember(sessionKey) { RaftWebViewController() }
    val coroutineScope = rememberCoroutineScope()
    val externalNavigator =
        rememberRaftLinkNavigator(
            pager = pager,
            bridge = bridge,
            routeScope = RaftInAppBrowserRouteScope.Unscoped,
        )
    val raftHostNavigator =
        rememberRaftLinkNavigator(
            pager = pager,
            bridge = bridge,
            routeScope = if (storeScope != null) RaftInAppBrowserRouteScope.InheritShell else RaftInAppBrowserRouteScope.Unscoped,
        )
    val signInHost =
        rememberInAppBrowserSignInHost(
            pager = pager,
            bridge = bridge,
            sharedStores = sharedStores,
            storeScope = storeScope,
            entryContext = entryContext,
            activeWebBaseUrl = activeWebBaseUrl,
            activeApiBaseUrl = activeApiBaseUrl,
            controller = controller,
            sessionKey = sessionKey,
            panelVisible = true,
            onOpenInBrowser = { authorizeUrl ->
                externalNavigator.open(authorizeUrl, RaftLinkOpenIntent.RaftWebPageInBrowser)
            },
        )
    val signInUiState by signInHost.session.uiState.collectAsState()
    val stepUpState by signInHost.stepUp.state.collectAsState()

    var pendingExternalAppLaunch by remember(sessionKey) { mutableStateOf<RaftExternalAppLaunchDecision.ConfirmExternalApp?>(null) }
    val jsDialogsEnabled = signInUiState.sheet == null && pendingExternalAppLaunch == null
    val jsDialogState = remember(sessionKey) { RaftWebJsDialogState(initialVisible = jsDialogsEnabled) }
    LaunchedEffect(jsDialogState, jsDialogsEnabled) {
        jsDialogState.onVisibilityChanged(jsDialogsEnabled)
    }

    val initiallyWarm = remember(tab.keepAliveKey) { raftWebViewKeptDocument(tab.keepAliveKey) != null }
    var navigationState by
        remember(sessionKey, effectiveLaunchUrl) {
            mutableStateOf(
                RaftWebViewNavigationState(
                    url = tab.currentUrl.ifBlank { effectiveLaunchUrl },
                    isLoading = !initiallyWarm,
                ),
            )
        }
    var loadError by remember(sessionKey, effectiveLaunchUrl) { mutableStateOf<String?>(null) }
    var webViewAttachReady by remember(sessionKey, effectiveLaunchUrl) { mutableStateOf(false) }
    var hasVisualCommit by remember(sessionKey, effectiveLaunchUrl) { mutableStateOf(initiallyWarm) }
    var hasCommittedDocument by remember(sessionKey, effectiveLaunchUrl) { mutableStateOf(initiallyWarm) }
    var committedDocumentUrl by remember(sessionKey, effectiveLaunchUrl) {
        mutableStateOf(if (initiallyWarm) tab.currentUrl.ifBlank { effectiveLaunchUrl } else "")
    }
    var lastObservedUrl by remember(sessionKey, effectiveLaunchUrl) { mutableStateOf(tab.currentUrl.ifBlank { effectiveLaunchUrl }) }
    val latestOnCurrentUrlChanged by rememberUpdatedState(onCurrentUrlChanged)
    val latestOnClose by rememberUpdatedState(onClose)

    // Defer attach by 1 frame so a popping full-screen InAppBrowserPage finishes parking its warm
    // WebView before the Mini-Window takes the same keepAliveKey.
    LaunchedEffect(sessionKey, effectiveLaunchUrl) {
        withFrameNanos { }
        if (raftWebViewKeptDocument(tab.keepAliveKey) != null) {
            hasVisualCommit = true
            hasCommittedDocument = true
            if (committedDocumentUrl.isBlank()) {
                committedDocumentUrl = tab.currentUrl.ifBlank { effectiveLaunchUrl }
            }
            if (navigationState.isLoading) {
                navigationState = navigationState.copy(isLoading = false)
            }
        }
        webViewAttachReady = true
    }

    fun handleAppStoreRestrictedPurchase(targetUrl: String, closeOnBlock: Boolean): Boolean {
        if (!raftInAppBrowserShouldBlockUrl(targetUrl, activeWebBaseUrl, appStoreBillingRules)) return false
        bridge.logWarn("mini_window_appstore_purchase_blocked url=${raftOwnWebUrlForLog(targetUrl)}")
        if (
            raftUrlIsOwnBillingPath(targetUrl, activeWebBaseUrl) &&
            storeScope != null &&
            settingsBillingOpenTarget() != SettingsBillingOpenTarget.ExternalBrowser
        ) {
            pager.openSettingsBillingPage(storeScope)
        } else if (closeOnBlock) {
            latestOnClose()
        }
        return true
    }

    fun routeRaftHostOut(targetUrl: String) {
        if (entryContext != null || !appStoreBillingRules) {
            val ownTarget =
                if (storeScope != null && raftOwnLinkIsWebPage(targetUrl, activeWebBaseUrl)) {
                    pager.openRaftOwnWebPage(targetUrl, storeScopeOverride = storeScope, activeWebBaseUrl = activeWebBaseUrl)
                } else {
                    false
                }
            if (!ownTarget) {
                val decision =
                    if (storeScope != null && raftOwnLinkShellOpener(webUrl = { activeWebBaseUrl })(targetUrl)) {
                        RaftLinkOpenDecision(RaftLinkOpenTarget.InAppDestination, targetUrl)
                    } else {
                        raftHostNavigator.open(targetUrl, RaftLinkOpenIntent.RaftWebPageInBrowser)
                    }
                if (decision.target == RaftLinkOpenTarget.Rejected) {
                    externalNavigator.open(targetUrl, RaftLinkOpenIntent.RaftWebPageInBrowser)
                }
            }
            return
        }
        externalNavigator.open(targetUrl, RaftLinkOpenIntent.RaftWebPageInBrowser)
    }

    fun lookupOtherInstalledAppSync(candidateOrigin: String): Pair<RaftInstalledAppOriginMatch, String>? {
        val serverId =
            entryContext?.preferredServerId?.trim()?.takeIf { it.isNotEmpty() }
                ?: tab.serverId?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
        val scope = sharedStores.currentInstalledAppScopeSync(serverId)
        val cached = scope?.let { sharedStores.installedAppOriginsCache.lookupSync(it, candidateOrigin) }
        if (cached is RaftInstalledAppSyncLookup.Matched && cached.match.origin == candidateOrigin) {
            return cached.match to cached.serverId
        }
        if (appShellRailPlacesEnabled(sharedStores.serverFeatureFlags, serverId)) {
            val railApp = sharedStores.railPlacesStore.usableAppOn(serverId, candidateOrigin)
            if (railApp != null) {
                val logoUrl =
                    railApp.iconUrl?.let { icon ->
                        build.raft.app.data.resolveAppLinkIconUrl(icon, activeApiBaseUrl)
                    }
                return RaftInstalledAppOriginMatch(
                    origin = candidateOrigin,
                    appName = railApp.name,
                    logoUrl = logoUrl,
                ) to serverId
            }
        }
        return null
    }

    fun routeCrossOriginOutbound(targetUrl: String): Boolean {
        val scopeOrigin = entryContext?.scopeOrigin ?: return false
        if (useEphemeralStore) return false
        if (
            !raftAppShouldRouteCrossOriginOutbound(
                candidateUrl = targetUrl,
                scopeOrigin = scopeOrigin,
                activeWebBaseUrl = activeWebBaseUrl,
                activeApiBaseUrl = activeApiBaseUrl,
                appStoreBillingRules = appStoreBillingRules,
                isRedirect = false,
                committedDocumentUrl = committedDocumentUrl,
            )
        ) {
            return false
        }
        val candidateOrigin = raftAppEntryOriginOf(targetUrl) ?: return false
        val installedOther = lookupOtherInstalledAppSync(candidateOrigin)
        if (installedOther != null) {
            val (match, serverId) = installedOther
            val env = sharedStores.apiEnvironmentStore.current()
            val envKey = "${env.webUrl}|${env.apiBaseUrl}"
            val accountId = runCatching { sharedStores.authStore.readCommittedSessionSnapshot()?.user?.id }.getOrNull()
            val request =
                RaftAppEntryRequest(
                    scopeOrigin = match.origin,
                    appName = match.appName,
                    preferredServerId = serverId,
                    appLogoUrl = match.logoUrl,
                )
            val token = RaftAppEntryRegistry.process.register(targetUrl, request)
            val result =
                webPanelCoordinator.openOrActivateApp(
                    launchUrl = targetUrl,
                    appEntryToken = token,
                    request = request,
                    accountId = accountId,
                    serverId = serverId,
                    environmentKey = envKey,
                )
            floatingCoordinator.floatAppTabInMiniWindow(result.tab.tabId)
        } else if (!raftPlatformSupportsIsolatedWebViewProfile()) {
            bridge.openUrl(targetUrl, systemInAppBrowser = true)
        } else {
            pager.openRaftInAppBrowserOutboundChild(
                rawUrl = targetUrl,
                storeScopeOverride = storeScope,
            )
        }
        return true
    }

    Box(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        if (webViewAttachReady) {
            RaftWebView(
                url = effectiveLaunchUrl,
                modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite),
                controller = controller,
                allowScripts = true,
                allowNavigation = true,
                allowedNavigationSchemes = setOf("http", "https"),
                domStorageEnabled = true,
                cacheEnabled = true,
                ephemeral = useEphemeralStore,
                storagePartition = storageResolution.storagePartition,
                allowsBackForwardNavigationGestures = false,
                raftHost = if (signInHost.flagEnabled) entryContext?.raftHost else null,
                injectedJavaScript = "",
                diagnosticsSurface = "in_app_browser_mini_window",
                showsFormAccessoryBar = true,
                allowJsDialogs = jsDialogsEnabled,
                allowNewWindows = false,
                keepAliveKey = if (storageResolution.isRaftApp && tab.isLive) tab.keepAliveKey else null,
                onJsDialog = { request -> jsDialogState.show(request) },
                catchesMainFrameNavigation = { ask -> signInHost.session.onMainFrameNavigation(ask) },
                routesMainFrameNavigationWithContext = { ask ->
                    raftInAppBrowserShouldRouteNavigation(
                        candidateUrl = ask.url,
                        activeWebBaseUrl = activeWebBaseUrl,
                        appStoreBillingRules = appStoreBillingRules,
                        useEphemeralStore = useEphemeralStore,
                        activeApiBaseUrl = activeApiBaseUrl,
                    ) ||
                        (
                            !useEphemeralStore &&
                                entryContext != null &&
                                raftAppShouldRouteCrossOriginOutbound(
                                    candidateUrl = ask.url,
                                    scopeOrigin = entryContext.scopeOrigin,
                                    activeWebBaseUrl = activeWebBaseUrl,
                                    activeApiBaseUrl = activeApiBaseUrl,
                                    appStoreBillingRules = appStoreBillingRules,
                                    isRedirect = ask.isRedirect,
                                    committedDocumentUrl = ask.committedUrl,
                                )
                            )
                },
                onUrlChange = { observedUrl ->
                    val clean = observedUrl.trim()
                    if (clean.isNotEmpty()) {
                        lastObservedUrl = clean
                        latestOnCurrentUrlChanged(clean)
                    }
                },
                onContentProcessGone = {
                    webPanelCoordinator.onTabContentProcessGone(tab.tabId)
                },
                onLoadCommit = {
                    hasVisualCommit = true
                    hasCommittedDocument = true
                    val observed = lastObservedUrl.takeIf { it.isNotBlank() && !it.equals("about:blank", ignoreCase = true) }
                    if (observed != null) committedDocumentUrl = observed
                    pendingExternalAppLaunch = null
                    signInHost.session.onPageCommitted()
                },
                onLoadSuccess = {
                    hasVisualCommit = true
                    hasCommittedDocument = true
                    val observed = lastObservedUrl.takeIf { it.isNotBlank() && !it.equals("about:blank", ignoreCase = true) }
                    if (observed != null) committedDocumentUrl = observed
                    loadError = null
                    signInHost.session.onPageCommitted()
                },
                onLoadFailure = { reason ->
                    if (reason == RAFT_WEBVIEW_ISOLATED_PROFILE_UNAVAILABLE_REASON) {
                        latestOnClose()
                        return@RaftWebView
                    }
                    loadError = reason.ifBlank { "webview_load_failed" }
                },
                onNavigationBlockedWithGesture = { blockedUrl, isUserGesture ->
                    if (handleAppStoreRestrictedPurchase(blockedUrl, closeOnBlock = false)) {
                        return@RaftWebView
                    }
                    if (!useEphemeralStore && raftInAppBrowserIsRaftHost(blockedUrl, activeWebBaseUrl, activeApiBaseUrl)) {
                        routeRaftHostOut(blockedUrl)
                        return@RaftWebView
                    }
                    if (routeCrossOriginOutbound(blockedUrl)) {
                        return@RaftWebView
                    }
                    when (
                        val decision =
                            classifyRaftExternalAppLaunch(
                                rawUrl = blockedUrl,
                                isUserGesture = isUserGesture,
                                panelVisible = true,
                            )
                    ) {
                        is RaftExternalAppLaunchDecision.OpenExternalWeb -> {
                            if (!routeCrossOriginOutbound(decision.url)) {
                                externalNavigator.open(decision.url, RaftLinkOpenIntent.ForceExternal)
                            }
                        }
                        is RaftExternalAppLaunchDecision.ConfirmExternalApp -> {
                            pendingExternalAppLaunch = decision
                        }
                        is RaftExternalAppLaunchDecision.Drop -> Unit
                    }
                },
                onNavigationStateChange = { nextState ->
                    val nextUrl = nextState.url.trim()
                    if (
                        nextUrl.isNotEmpty() &&
                        raftInAppBrowserShouldRouteNavigation(
                            candidateUrl = nextUrl,
                            activeWebBaseUrl = activeWebBaseUrl,
                            appStoreBillingRules = appStoreBillingRules,
                            useEphemeralStore = useEphemeralStore,
                            activeApiBaseUrl = activeApiBaseUrl,
                        )
                    ) {
                        if (nextState.isLoading) controller.stop()
                        if (!handleAppStoreRestrictedPurchase(nextUrl, closeOnBlock = true)) {
                            routeRaftHostOut(nextUrl)
                        }
                        return@RaftWebView
                    }
                    navigationState = nextState
                    if (nextUrl.isNotEmpty()) {
                        lastObservedUrl = nextUrl
                        latestOnCurrentUrlChanged(nextUrl)
                        webPanelCoordinator.updateTabNavigation(
                            tabId = tab.tabId,
                            currentUrl = nextUrl,
                            pageTitle = nextState.title.takeIf { it.isNotBlank() },
                        )
                    }
                    if (nextState.progress >= 25) {
                        hasVisualCommit = true
                    }
                    if (nextState.isLoading) loadError = null
                },
            )
            val declinedAuthorizeUrl = signInUiState.declinedAuthorizeUrl
            if (declinedAuthorizeUrl != null) {
                RaftInAppBrowserError(
                    message = raftBrowserSignInDeclinedMessage(signInUiState.declinedReason),
                    onRetry = null,
                    onOpenSystem = {
                        signInHost.session.dismissDeclinedNotice()
                        externalNavigator.open(declinedAuthorizeUrl, RaftLinkOpenIntent.RaftWebPageInBrowser)
                    },
                    onClose = {
                        when (resolveRaftMobileWebPanelDeclinedCloseAction(hasCommittedDocument = hasCommittedDocument)) {
                            RaftMobileWebPanelDeclinedCloseAction.DismissNotice -> signInHost.session.dismissDeclinedNotice()
                            RaftMobileWebPanelDeclinedCloseAction.ClosePage -> latestOnClose()
                        }
                    },
                )
            } else if (loadError != null) {
                RaftInAppBrowserError(
                    message = stringResource(Res.string.in_app_browser_load_error),
                    onRetry = {
                        loadError = null
                        val fresh =
                            resolveRaftMobileWebPanelReloadTargetUrl(
                                currentUrl = navigationState.url,
                                effectiveLaunchUrl = effectiveLaunchUrl,
                                hasCommittedDocument = hasCommittedDocument,
                            )
                        if (fresh == null || !controller.loadUrl(fresh)) {
                            controller.reload()
                        }
                    },
                    onOpenSystem = {
                        val target = navigationState.url.ifBlank { effectiveLaunchUrl }
                        if (!handleAppStoreRestrictedPurchase(target, closeOnBlock = false)) {
                            externalNavigator.open(target, RaftLinkOpenIntent.ForceExternal)
                        }
                    },
                    onClose = latestOnClose,
                )
            } else if (signInUiState.resolving) {
                RaftBrowserSignInResolvingOverlay()
            }
        }
        if (
            shouldShowRaftMobileWebPanelLoadingPlaceholder(
                hasLaunchUrl = true,
                hasVisualCommit = hasVisualCommit,
                hasLoadError = loadError != null,
                isSignInResolving = signInUiState.resolving,
                hasDeclinedSignIn = signInUiState.declinedAuthorizeUrl != null,
                isLoading = navigationState.isLoading,
                progress = navigationState.progress,
            )
        ) {
            RaftMobileWebPanelLoadingPlaceholder()
        }
        RaftWebPageLoadingBar(
            raftWebPageLoadState(
                isLoading = navigationState.isLoading,
                progress = navigationState.progress,
                failed = loadError != null,
                loadGeneration = navigationState.loadGeneration,
            ),
            appearDelayMillis = 0,
        )
    }

    RaftWebJsDialogHost(state = jsDialogState)

    RaftExternalAppLaunchDialogHost(
        request = pendingExternalAppLaunch,
        onConfirm = { confirmed ->
            pendingExternalAppLaunch = null
            bridge.openUrl(confirmed.url, inBrowser = false)
        },
        onDismiss = { pendingExternalAppLaunch = null },
    )

    RaftBrowserSignInSheetHost(
        sheet = signInUiState.sheet,
        stepUpState = stepUpState,
        onStepUpIntent = { intent -> coroutineScope.launch { signInHost.stepUp.accept(intent) } },
        appForeground = signInHost.appForeground,
        onSelectServer = signInHost.session::selectServer,
        onApprove = signInHost.session::approveCurrent,
        onCancel = signInHost.session::cancelSheet,
        onOpenInBrowser = signInHost.session::openOriginalAuthorizeInBrowser,
    )
}

/**
 * Live Artifact WebView body hosted inside the resizable Mini-Window: reuses [RaftFloatingArtifactWindow.keepAliveKey]
 * and preserves EVERY Tenny security invariant (`hostEventsEnabled = false`, `mainFrameOrigin = scope.webOrigin`,
 * `frameHosts = setOf(ARTIFACT_CONTENT_DOMAIN)`, origin-guarded top-level session injection,
 * `audioCaptureAllowedOrigin = scope.webOrigin`, and `SettingsWebViewAccessTokenSyncEffect`).
 */
@Composable
private fun RaftMiniWindowArtifactWebBody(
    entry: RaftFloatingArtifactWindow,
    pager: Pager,
    bridge: BridgeModule,
    storeScope: RaftShellStoreScope?,
    floatingCoordinator: RaftFloatingWindowCoordinator,
    onClose: () -> Unit,
) {
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val authStore =
        remember(sharedStores, storeScope) {
            if (storeScope != null) sharedStores.authStore(storeScope) else sharedStores.authStore
        }
    val authEvents =
        remember(authStore, sharedStores.authStore) {
            settingsWebViewAuthEventSource(authStore, sharedStores.authStore)
        }
    val initialSession =
        remember(sharedStores.authStore, entry.scope) {
            val raw = runCatching { sharedStores.authStore.readInMemorySessionSnapshot()?.session }.getOrNull()
                ?: runCatching { sharedStores.authStore.readCommittedSessionSnapshot() }.getOrNull()
            if (raw != null && raw.user.id.trim() == entry.scope.accountId) {
                if (raw.currentServerId?.trim() == entry.scope.serverId) {
                    raw
                } else {
                    val server = raw.servers.firstOrNull { it.id.trim() == entry.scope.serverId }
                    server?.let { raw.copy(selectedServerId = it.id, selectedServerSlug = it.slug) }
                }
            } else {
                null
            }
        }
    val snapshot = rememberSettingsWebViewAuthSnapshot(authStore, authEvents, initialSession = initialSession)
    val controller = remember(entry.windowKey, entry.keepAliveKey) { RaftWebViewController() }
    val binding = rememberSettingsWebViewHostAuthBinding(snapshot, controller)
    val session = snapshot.session?.takeIf { it.user.id.trim() == entry.scope.accountId }

    val accessTokenRefresher =
        remember(sharedStores, authStore, storeScope) {
            SettingsWebViewAccessTokenRefresher(
                authStore,
                sharedStores.createAuthDataSourceForCurrentEnvironment(storeScope ?: RaftShellStoreScope.Legacy),
            )
        }
    val coroutineScope = rememberCoroutineScope()
    val copiedMessage = stringResource(Res.string.common_copied)
    val latestOnClose by rememberUpdatedState(onClose)
    val currentToken by rememberUpdatedState(session?.accessToken)

    var webViewAttachReady by remember(entry.windowKey, entry.keepAliveKey) { mutableStateOf(false) }
    var loadError by remember(entry.windowKey, entry.keepAliveKey) { mutableStateOf<String?>(null) }
    var documentReady by remember(entry.windowKey, entry.keepAliveKey) {
        mutableStateOf(raftWebViewKeptDocument(entry.keepAliveKey)?.loaded == true)
    }
    var pendingHostAction by remember(entry.windowKey, entry.keepAliveKey) {
        mutableStateOf<Pair<RaftArtifactViewerHostAction, String>?>(null)
    }
    var pendingOpenMessage by remember(entry.windowKey, entry.keepAliveKey) {
        mutableStateOf<Pair<String?, String>?>(null)
    }
    var hashBeforeHostRequest by remember(entry.windowKey, entry.keepAliveKey) { mutableStateOf("") }

    // Declared before RaftSandboxWebView so onDispose runs AFTER child onRelease:
    // - If explicitly closed (removed from coordinator), evict its keep-alive slot cleanly.
    // - Otherwise (collapsing to Edge Pill or expanding to Full-Screen), record warm metadata so
    //   full-screen re-attaches with Frame-1 warm bar readiness and zero reload.
    DisposableEffect(entry.windowKey, entry.keepAliveKey) {
        onDispose {
            val stillFloating = floatingCoordinator.artifactByKey(entry.windowKey) != null
            if (!stillFloating) {
                RaftArtifactViewerMemory.cache.forgetKeptDocument(entry.scope, entry.keepAliveKey)
                raftWebViewKeepAliveDrop(entry.keepAliveKey)
            } else {
                val token = currentToken
                val parked = raftWebViewKeptDocument(entry.keepAliveKey)
                if (!token.isNullOrBlank() && parked != null) {
                    val keepableUrl =
                        parked.currentUrl
                            .takeIf { raftArtifactViewerDocumentUrlIsKeepable(entry.scope.webOrigin, it) }
                            ?: entry.embeddedPageUrl
                    RaftArtifactViewerMemory.cache.rememberKeptDocument(
                        scope = entry.scope,
                        target = entry.target,
                        documentUrl = keepableUrl,
                        accessToken = token,
                        slotKey = entry.keepAliveKey,
                        renderedArtifact = true,
                    )
                    RaftArtifactViewerMemory.cache.noteKeptBoot(
                        scope = entry.scope,
                        conversationId = entry.conversationId,
                        hostHead = entry.hostHead,
                        langTag = entry.langTag,
                        slotKey = entry.keepAliveKey,
                    )
                }
            }
        }
    }

    // Defer attach by 1 frame so a popping full-screen Artifact viewer parks its warm WebView first.
    LaunchedEffect(entry.windowKey, entry.keepAliveKey) {
        withFrameNanos { }
        if (raftWebViewKeptDocument(entry.keepAliveKey)?.loaded == true) {
            documentReady = true
        }
        webViewAttachReady = true
    }

    LaunchedEffect(pendingHostAction) {
        val (action, url) = pendingHostAction ?: return@LaunchedEffect
        val configuredWebBaseUrl = standaloneSettingsWebBaseUrl(sharedStores.apiEnvironmentStore)
        val shown = raftArtifactViewerNavigation(url, entry.scope.webOrigin, entry.artifactId) as? RaftArtifactViewerNavigation.Stay
        val preservedHostDesktop =
            when {
                raftArtifactViewerAddressSaysDesktop(entry.embeddedPageUrl, true) -> true
                raftArtifactViewerAddressSaysDesktop(entry.embeddedPageUrl, false) -> false
                else -> null
            }
        controller.evaluateJavaScript(
            raftArtifactViewerHostDesktopScript(entry.scope.webOrigin, preservedHostDesktop, clearHostAction = true),
        )
        when (action) {
            RaftArtifactViewerHostAction.CopyLink -> {
                val link = raftArtifactViewerPageUrl(configuredWebBaseUrl, ArtifactLinkTarget(entry.artifactId, shown?.version))
                bridge.copyToPasteboard(link)
                bridge.toast(copiedMessage)
            }
            RaftArtifactViewerHostAction.ShareLink -> {
                val link = raftArtifactViewerPageUrl(configuredWebBaseUrl, ArtifactLinkTarget(entry.artifactId, shown?.version))
                coroutineScope.launch { raftArtifactViewerShareLink(bridge, link, copiedMessage) }
            }
            RaftArtifactViewerHostAction.DesktopMode,
            RaftArtifactViewerHostAction.FloatWindow -> Unit
        }
        pendingHostAction = null
    }

    LaunchedEffect(pendingOpenMessage) {
        val (link, hashBefore) = pendingOpenMessage ?: return@LaunchedEffect
        controller.evaluateJavaScript(raftArtifactViewerHostOpenTakenScript(entry.scope.webOrigin, hashBefore))
        if (link != null) {
            RaftMessageLinkIngress.accept(link, "artifact_mini_window", entry.scope.webOrigin)
        }
        pendingOpenMessage = null
    }

    if (session == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(RaftColor.Canvas),
            contentAlignment = Alignment.Center,
        ) {
            RaftSpinner(size = RaftSpinnerSize.Md, color = RaftTheme.colors.foregroundMuted)
        }
        return
    }

    val kept = remember(entry.keepAliveKey, webViewAttachReady) { raftWebViewKeptDocument(entry.keepAliveKey) }
    val src = kept?.src ?: entry.embeddedPageUrl
    val injected =
        remember(entry.scope, session.accessToken, binding?.webViewGeneration, entry.desktopMode) {
            raftArtifactViewerInjectedScript(
                entry.scope.webOrigin,
                raftArtifactViewerSessionScript(entry.scope, session, binding),
                entry.desktopMode,
            )
        }
    val loadWatchdog = remember(entry.windowKey, src) { RaftWebViewLoadWatchdog(page = "artifact_mini_window", url = src, retry = 0) }
    val hasError = raftArtifactViewerHasLoadError(platformLoadError = loadError, watchdogShowsError = loadWatchdog.showsError)

    fun handleAddress(url: String) {
        val clean = url.trim()
        if (clean.isEmpty()) return
        if (raftArtifactViewerAsksHostClose(clean)) {
            if (raftArtifactViewerIsHostCloseOnArtifact(clean, entry.scope.webOrigin, entry.artifactId)) {
                controller.evaluateJavaScript(raftArtifactViewerHostCloseTakenScript(entry.scope.webOrigin, hashBeforeHostRequest))
            }
            latestOnClose()
            return
        }
        val hostAction = raftArtifactViewerHostAction(clean)
        val asksOpen = raftArtifactViewerAsksHostOpen(clean)
        if (hostAction == null && !asksOpen) {
            hashBeforeHostRequest = clean.substringAfter('#', "")
        } else if (pendingHostAction == null && pendingOpenMessage == null) {
            if (hostAction != null) {
                pendingHostAction = hostAction to clean
            } else {
                pendingOpenMessage = raftArtifactViewerHostOpenMessageLink(clean, entry.scope.webOrigin) to hashBeforeHostRequest
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        if (webViewAttachReady && !hasError) {
            RaftSandboxWebView(
                url = src,
                modifier = Modifier.fillMaxSize(),
                allowScripts = true,
                allowNavigation = true,
                domStorageEnabled = true,
                useWideViewPort = entry.desktopMode,
                cacheEnabled = true,
                ephemeral = raftPlatformUsesEphemeralAuthWebView(),
                injectedJavaScript = injected,
                // Tenny Rule 3: no native bridge into the app.
                hostEventsEnabled = false,
                controller = controller,
                allowedNavigationSchemes = setOf("http", "https"),
                // Tenny Rule 2: top-level documents stay strictly on webOrigin.
                mainFrameOrigin = entry.scope.webOrigin,
                frameHosts = setOf(ARTIFACT_CONTENT_DOMAIN),
                keepAliveKey = entry.keepAliveKey,
                diagnosticsSurface = RAFT_ARTIFACT_VIEWER_WEBVIEW_SURFACE,
                allowPinchZoom = true,
                backgroundArgb = RaftColor.Canvas.toArgb(),
                persistBackgroundAfterPaint = true,
                androidInsetMode = WebContainerAndroidInsetMode.HostHandled,
                audioCaptureAllowedOrigin = entry.scope.webOrigin,
                loadWatchdog = loadWatchdog,
                onLoadCommit = { loadError = null },
                onLoadSuccess = {
                    loadError = null
                    documentReady = true
                },
                onLoadFailure = { reason ->
                    loadError = reason.trim().ifBlank { "webview_load_failed" }
                },
                onUrlChange = ::handleAddress,
                onNavigationStateChange = { state ->
                    if (state.url.isNotBlank()) handleAddress(state.url)
                },
                onNavigationBlocked = { blocked ->
                    val configuredWebBaseUrl = standaloneSettingsWebBaseUrl(sharedStores.apiEnvironmentStore)
                    fun openExternallyRespectingAppStore(targetUrl: String) {
                        if (
                            raftPlatformFollowsAppStoreBillingRules() &&
                            raftUrlIsAppStoreRestrictedPurchaseUrl(targetUrl, configuredWebBaseUrl)
                        ) {
                            bridge.logWarn("artifact_mini_window_appstore_purchase_blocked url=${raftOwnWebUrlForLog(targetUrl)}")
                            if (raftUrlIsOwnBillingPath(targetUrl, configuredWebBaseUrl) && storeScope != null) {
                                pager.openSettingsBillingPage(storeScope)
                            }
                            return
                        }
                        bridge.openUrl(targetUrl, inBrowser = true)
                    }
                    when (val routed = raftArtifactViewerBlockedNavigationTarget(blocked, entry.scope.webOrigin, entry.artifactId)) {
                        is RaftArtifactViewerBlockedTarget.RaftWebPage ->
                            if (!pager.openRaftOwnWebPage(routed.url, storeScope, activeWebBaseUrl = configuredWebBaseUrl)) {
                                openExternallyRespectingAppStore(routed.url)
                            }
                        is RaftArtifactViewerBlockedTarget.SystemBrowser -> openExternallyRespectingAppStore(routed.url)
                        is RaftArtifactViewerBlockedTarget.OpenMessage ->
                            RaftMessageLinkIngress.accept(routed.url, "artifact_mini_window", entry.scope.webOrigin)
                        is RaftArtifactViewerBlockedTarget.OpenArtifact ->
                            RaftArtifactLinkInbox.Process.submit(routed.target, "artifact_mini_window")
                        RaftArtifactViewerBlockedTarget.Download,
                        RaftArtifactViewerBlockedTarget.Ignore -> Unit
                    }
                },
                routesMainFrameNavigation = { url ->
                    raftArtifactViewerRoutesDocumentAsLink(url, entry.scope.webOrigin, entry.artifactId)
                },
            )
            SettingsWebViewAccessTokenSyncEffect(
                snapshot = snapshot,
                documentReady = documentReady,
                controller = controller,
                binding = binding,
                surface = SettingsWebViewSurface.ArtifactViewer,
                logger = bridge::log,
                refresher = accessTokenRefresher,
                visible = true,
                webOrigin = entry.scope.webOrigin,
            )
        }
        if (hasError) {
            build.raft.app.settings.SettingsWebViewErrorContent(
                actionLabel = stringResource(Res.string.common_retry),
                actionIcon = RaftLucideIconName.RefreshCw,
                body = settingsWebViewErrorBody(loadWatchdog.state.phase),
                onAction = {
                    loadError = null
                    controller.loadUrl(entry.embeddedPageUrl)
                },
            )
        }
    }
}
