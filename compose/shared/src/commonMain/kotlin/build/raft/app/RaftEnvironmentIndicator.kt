package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.base.Utils
import build.raft.app.components.RaftBrutalBadge
import build.raft.app.components.RaftBrutalTone
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.SharedSettingsApiEnvironmentOption
import build.raft.app.data.RaftSharedStoreRegistry
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import kotlin.math.roundToInt

internal const val RAFT_EMBEDDED_CHILD_PAGE_PARAM = "slockEmbeddedChildPage"

private val RaftEnvironmentIndicatorMaxWidth = 144.dp
private val RaftEnvironmentIndicatorEdgeMargin = 6.dp

internal data class RaftEnvironmentIndicatorDragBounds(
    val hostWidthPx: Float,
    val hostHeightPx: Float,
    val badgeWidthPx: Float,
    val badgeHeightPx: Float,
    val topSafeAreaPx: Float,
    val bottomSafeAreaPx: Float,
    val edgeMarginPx: Float,
)

internal data class RaftEnvironmentIndicatorPosition(
    val xPx: Float,
    val yPx: Float,
)

@Composable
internal fun RaftEnvironmentIndicatorHost(
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }

    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val environmentStore =
        remember(bridge) {
            RaftSharedStoreRegistry.get(bridge).apiEnvironmentStore
        }
    val environment by environmentStore.state.collectAsState()
    val label = raftEnvironmentIndicatorLabel(environment)
    if (label == null) {
        // Production keeps its original layout tree: no indicator and no extra root Box.
        content()
        return
    }

    val density = LocalDensity.current
    val topSafeArea = raftTopSafeAreaPadding()
    val bottomSafeArea = raftBottomSafeAreaPadding()
    var badgeSize by remember { mutableStateOf(IntSize.Zero) }
    // Deliberately window-scoped rather than durable configuration: tabs and in-shell routes keep
    // the user's placement, while a fresh top-level window/process starts from the predictable
    // top-right default again.
    var draggedPosition by remember { mutableStateOf<RaftEnvironmentIndicatorPosition?>(null) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        content()
        val bounds =
            RaftEnvironmentIndicatorDragBounds(
                hostWidthPx = with(density) { maxWidth.toPx() },
                hostHeightPx = with(density) { maxHeight.toPx() },
                badgeWidthPx = badgeSize.width.toFloat(),
                badgeHeightPx = badgeSize.height.toFloat(),
                topSafeAreaPx = with(density) { topSafeArea.toPx() },
                bottomSafeAreaPx = with(density) { bottomSafeArea.toPx() },
                edgeMarginPx = with(density) { RaftEnvironmentIndicatorEdgeMargin.toPx() },
            )
        val currentPosition = {
            raftClampEnvironmentIndicatorPosition(
                draggedPosition ?: raftDefaultEnvironmentIndicatorPosition(bounds),
                bounds,
            )
        }
        val placementModifier =
            if (badgeSize == IntSize.Zero) {
                // Preserve the stable first frame while the configured label reports its width.
                Modifier
                    .align(AbsoluteAlignment.TopRight)
                    .absolutePadding(
                        top = topSafeArea + RaftEnvironmentIndicatorEdgeMargin,
                        right = RaftEnvironmentIndicatorEdgeMargin,
                    )
            } else {
                // Drag bounds use physical screen coordinates, so placement must not mirror in RTL.
                Modifier
                    .align(AbsoluteAlignment.TopLeft)
                    .absoluteOffset {
                        val effectivePosition = currentPosition()
                        IntOffset(
                            x = effectivePosition.xPx.roundToInt(),
                            y = effectivePosition.yPx.roundToInt(),
                        )
                    }
            }
        RaftBrutalBadge(
            label = label,
            modifier =
                placementModifier
                    .widthIn(max = RaftEnvironmentIndicatorMaxWidth)
                    .onSizeChanged { badgeSize = it }
                    .pointerInput(bounds) {
                        detectDragGestures(
                            onDragStart = { draggedPosition = currentPosition() },
                        ) { change, dragAmount ->
                            change.consume()
                            val current = currentPosition()
                            draggedPosition =
                                raftClampEnvironmentIndicatorPosition(
                                    RaftEnvironmentIndicatorPosition(
                                        xPx = current.xPx + dragAmount.x,
                                        yPx = current.yPx + dragAmount.y,
                                    ),
                                    bounds,
                                )
                        }
                    }
                    .zIndex(RaftEnvironmentIndicatorZIndex),
            tone = RaftBrutalTone.Yellow,
        )
    }
}

internal fun raftEnvironmentIndicatorLabel(
    environment: SharedSettingsApiEnvironmentOption,
): String? {
    val productionApiBaseUrl = SharedApiEnvironmentStore.Production.apiBaseUrl.normalizedEnvironmentBaseUrl()
    if (environment.apiBaseUrl.normalizedEnvironmentBaseUrl() == productionApiBaseUrl) return null
    return environment.label.trim().takeIf(String::isNotEmpty)
}

private fun String.normalizedEnvironmentBaseUrl(): String = trim().trimEnd('/')

internal fun raftDefaultEnvironmentIndicatorPosition(
    bounds: RaftEnvironmentIndicatorDragBounds,
): RaftEnvironmentIndicatorPosition =
    raftClampEnvironmentIndicatorPosition(
        position =
            RaftEnvironmentIndicatorPosition(
                xPx = bounds.hostWidthPx - bounds.badgeWidthPx - bounds.edgeMarginPx,
                yPx = bounds.topSafeAreaPx + bounds.edgeMarginPx,
            ),
        bounds = bounds,
    )

internal fun raftClampEnvironmentIndicatorPosition(
    position: RaftEnvironmentIndicatorPosition,
    bounds: RaftEnvironmentIndicatorDragBounds,
): RaftEnvironmentIndicatorPosition =
    RaftEnvironmentIndicatorPosition(
        xPx =
            raftClampEnvironmentIndicatorAxis(
                value = position.xPx,
                hostSize = bounds.hostWidthPx,
                badgeSize = bounds.badgeWidthPx,
                startInset = 0f,
                endInset = 0f,
                edgeMargin = bounds.edgeMarginPx,
            ),
        yPx =
            raftClampEnvironmentIndicatorAxis(
                value = position.yPx,
                hostSize = bounds.hostHeightPx,
                badgeSize = bounds.badgeHeightPx,
                startInset = bounds.topSafeAreaPx,
                endInset = bounds.bottomSafeAreaPx,
                edgeMargin = bounds.edgeMarginPx,
            ),
    )

private fun raftClampEnvironmentIndicatorAxis(
    value: Float,
    hostSize: Float,
    badgeSize: Float,
    startInset: Float,
    endInset: Float,
    edgeMargin: Float,
): Float {
    val normalizedHost = hostSize.coerceAtLeast(0f)
    val normalizedBadge = badgeSize.coerceAtLeast(0f)
    val min = startInset.coerceAtLeast(0f) + edgeMargin.coerceAtLeast(0f)
    val max = normalizedHost - normalizedBadge - endInset.coerceAtLeast(0f) - edgeMargin.coerceAtLeast(0f)
    if (max < min) {
        // No safe interval exists (for example, an oversized badge in a tiny split window).
        // Center it in the raw host so the maximum possible portion remains reachable.
        return ((normalizedHost - normalizedBadge) / 2f).coerceAtLeast(0f)
    }
    return value.coerceIn(min, max)
}

internal fun raftEnvironmentIndicatorEnabledForPage(params: JSONObject): Boolean =
    !params.optBoolean(RAFT_EMBEDDED_CHILD_PAGE_PARAM, false)
