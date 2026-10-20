package build.raft.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.RaftColor
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName

/*
 * Raft's custom pull-to-refresh (the implementation before #3217, kept as the default of the
 * Settings → Mobile "Native pull-to-refresh" switch). The gesture is material3
 * `Modifier.pullToRefresh` on the container; the list carries a header item
 * ([raftCustomPullToRefreshItem]) that grows with the pull distance, so the list content is pushed
 * down like Kuikly's list overscroll, and holds the threshold height while refreshing. The item
 * shows the Raft loader icon turning with the pull.
 *
 * Wiring is done by [RaftPullToRefreshBox]; call sites only emit `pullToRefreshItem()` as the first
 * item of their LazyColumn.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Stable
internal class RaftCustomPullToRefreshState(isRefreshing: Boolean) {
    var isRefreshing by mutableStateOf(isRefreshing)

    val gestureState: PullToRefreshState = PullToRefreshState()

    var onRefresh: (() -> Unit)? by mutableStateOf(null)

    var refreshThreshold: Dp by mutableStateOf(RaftCustomPullToRefreshDefaultThreshold)

    /** Pull distance relative to the refresh threshold: 0 at rest, 1 at the threshold (may exceed 1). */
    val pullProgress: Float
        get() = gestureState.distanceFraction
}

internal val RaftCustomPullToRefreshDefaultThreshold = 80.dp

/** Key of the custom pull-to-refresh header item in a LazyColumn. */
internal const val RaftCustomPullToRefreshItemKey = "pull_to_refresh"

@Composable
internal fun rememberRaftCustomPullToRefreshState(isRefreshing: Boolean): RaftCustomPullToRefreshState =
    remember { RaftCustomPullToRefreshState(isRefreshing) }.apply { this.isRefreshing = isRefreshing }

/**
 * Attaches the pull gesture (material3 pull-to-refresh nested scroll) for [state] to a scrollable
 * (or its container). Refresh fires the onRefresh registered by [raftCustomPullToRefreshItem].
 */
@OptIn(ExperimentalMaterial3Api::class)
internal fun Modifier.raftCustomPullToRefresh(
    state: RaftCustomPullToRefreshState,
    enabled: Boolean = true,
): Modifier =
    pullToRefresh(
        isRefreshing = state.isRefreshing,
        state = state.gestureState,
        enabled = enabled && state.onRefresh != null,
        threshold = state.refreshThreshold,
        onRefresh = { state.onRefresh?.invoke() },
    )

internal fun LazyListScope.raftCustomPullToRefreshItem(
    state: RaftCustomPullToRefreshState,
    onRefresh: () -> Unit,
    refreshThreshold: Dp = RaftCustomPullToRefreshDefaultThreshold,
    holdRefreshInset: Boolean = true,
) {
    item(key = RaftCustomPullToRefreshItemKey) {
        SideEffect {
            state.onRefresh = onRefresh
            state.refreshThreshold = refreshThreshold
        }
        val pullProgress = state.pullProgress
        val isRefreshing = state.isRefreshing
        val height =
            if (isRefreshing && holdRefreshInset) {
                refreshThreshold
            } else {
                refreshThreshold * pullProgress.coerceIn(0f, RaftCustomPullToRefreshMaxStretch)
            }
        if (height > 0.dp) {
            Box(
                Modifier.fillMaxWidth().height(height).clipToBounds(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                RaftCustomPullRefreshIndicator(
                    pullProgress = if (isRefreshing) 1f else pullProgress,
                    isRefreshing = isRefreshing,
                    refreshThreshold = refreshThreshold,
                )
            }
        }
    }
}

private const val RaftCustomPullToRefreshMaxStretch = 1.5f

@Composable
internal fun RaftCustomPullRefreshIndicator(
    pullProgress: Float,
    isRefreshing: Boolean,
    refreshThreshold: Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(refreshThreshold)
                .background(RaftColor.Canvas),
        contentAlignment = Alignment.Center
    ) {
        if (raftPullRefreshIndicatorVisible(pullProgress, isRefreshing)) {
            RaftLucideIcon(
                RaftLucideIconName.LoaderCircle,
                modifier = Modifier.size(RaftCustomPullRefreshSpinnerSize),
                color = RaftColor.TextBlackArgb,
                spinning = isRefreshing,
                rotationDegrees = raftPullRefreshIndicatorRotationDegrees(pullProgress, isRefreshing)
            )
        }
    }
}

private val RaftCustomPullRefreshSpinnerSize = 14.dp

internal fun raftPullRefreshIndicatorVisible(pullProgress: Float, isRefreshing: Boolean): Boolean =
    isRefreshing || pullProgress > 0f

internal fun raftPullRefreshIndicatorRotationDegrees(pullProgress: Float, isRefreshing: Boolean): Float =
    if (isRefreshing) {
        0f
    } else {
        pullProgress.coerceIn(0f, 1f) * 360f
    }
