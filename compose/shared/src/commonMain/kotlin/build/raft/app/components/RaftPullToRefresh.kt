package build.raft.app.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.pullToRefreshIndicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.theme.RaftTheme

/**
 * Material 3 pull-to-refresh with the Raft indicator: the M3 `PullToRefreshBox` shape
 * (`Modifier.pullToRefresh` on the container, the indicator overlaid at the top centre), so the
 * content never moves or stretches while pulling; only the indicator slides down from the top
 * edge. Threshold, the release/fling handling and the indicator travel are the M3 defaults.
 *
 * [enabled] gates the gesture (M3 1.3's `PullToRefreshBox` has no such parameter); the
 * indicator is not shown while disabled. [isRefreshing] is the caller's refresh signal, which
 * keeps the indicator parked at the threshold until it turns false.
 *
 * The gesture consumes the pull in nested post-scroll, so the platform overscroll (Android
 * stretch, the CMP iOS rubber band) only receives what the refresh did not take: a downward drag
 * at the top of the list drives the indicator, flings and bottom-edge overscroll stay native.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RaftPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    Box(
        modifier.pullToRefresh(
            isRefreshing = isRefreshing,
            state = state,
            enabled = enabled,
            onRefresh = onRefresh,
        ),
        contentAlignment = contentAlignment,
    ) {
        content()
        if (enabled) {
            RaftPullToRefreshIndicator(
                state = state,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/**
 * The M3 pull-to-refresh container (`pullToRefreshIndicator`: 40dp circle, translation, clip,
 * elevation) in Raft tokens: `--layer-popover` fill with a `--line-hairline` edge, and the raft-ui
 * pixel [RaftSpinner] instead of the M3 arc. While pulling, the spinner fades in and its lit pixel
 * walks clockwise with the pull ([raftPullToRefreshIndicatorStep]); while refreshing it animates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RaftPullToRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    Box(
        modifier =
            modifier
                .pullToRefreshIndicator(
                    state = state,
                    isRefreshing = isRefreshing,
                    shape = CircleShape,
                    containerColor = colors.layerPopover,
                    elevation = PullToRefreshDefaults.Elevation,
                )
                .border(1.dp, colors.lineHairline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (isRefreshing) {
            RaftSpinner(size = RaftSpinnerSize.Md, color = colors.foreground)
        } else {
            // The fade follows the drag in the layer block (no recomposition); the step only
            // recomposes when the lit pixel actually changes.
            val step by remember(state) {
                derivedStateOf { raftPullToRefreshIndicatorStep(state.distanceFraction) }
            }
            RaftSpinner(
                modifier = Modifier.graphicsLayer { alpha = state.distanceFraction.coerceIn(0f, 1f) },
                size = RaftSpinnerSize.Md,
                color = colors.foreground,
                stillStep = step,
            )
        }
    }
}

/**
 * Spinner step shown while pulling (not refreshing): the lit pixel walks one quarter per quarter
 * of the threshold, top-left at rest and bottom-left (the last step) from the threshold on.
 */
internal fun raftPullToRefreshIndicatorStep(distanceFraction: Float): Int =
    (distanceFraction.coerceIn(0f, 1f) * RaftPullToRefreshSpinnerSteps)
        .toInt()
        .coerceAtMost(RaftPullToRefreshSpinnerSteps - 1)

private const val RaftPullToRefreshSpinnerSteps = 4
