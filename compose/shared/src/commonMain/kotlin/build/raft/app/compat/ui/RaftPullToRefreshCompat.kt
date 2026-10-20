package build.raft.app.compat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.pulltorefresh.PullToRefreshState as M3PullToRefreshState

/*
 * Kuikly's list-header pull-to-refresh (material3.pullToRefreshItem) relied on Kuikly's native
 * list overscrolling past the top. Upstream lazy lists do not overscroll that way, so the gesture
 * now comes from material3 `Modifier.pullToRefresh` (the engine of M3 `PullToRefreshBox`), applied
 * to the list with [raftPullToRefresh]. The `pullToRefreshItem` header keeps the Kuikly API: it
 * registers its onRefresh/threshold on the state and grows with the pull distance, so the list
 * content is pushed down like the Kuikly overscroll and the call site's custom indicator keeps
 * rendering from `pullProgress`.
 *
 * Wiring: `LazyColumn(modifier = Modifier.raftPullToRefresh(state))` + `pullToRefreshItem(state)`.
 * A list with only the item (no modifier) shows the indicator while refreshing but has no gesture.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Stable
class PullToRefreshState(isRefreshing: Boolean) {
    var isRefreshing by mutableStateOf(isRefreshing)

    internal val gestureState: M3PullToRefreshState = M3PullToRefreshState()

    internal var onRefresh: (() -> Unit)? by mutableStateOf(null)

    internal var refreshThreshold: Dp by mutableStateOf(RaftPullToRefreshDefaultThreshold)

    /** Pull distance relative to the refresh threshold: 0 at rest, 1 at the threshold (may exceed 1). */
    val pullProgress: Float
        get() = gestureState.distanceFraction
}

private val RaftPullToRefreshDefaultThreshold = 80.dp

@Composable
fun rememberPullToRefreshState(isRefreshing: Boolean): PullToRefreshState =
    remember { PullToRefreshState(isRefreshing) }.apply { this.isRefreshing = isRefreshing }

/**
 * Attaches the pull gesture (material3 pull-to-refresh nested scroll) for [state] to a scrollable
 * list. Refresh fires the onRefresh registered by [pullToRefreshItem].
 */
@OptIn(ExperimentalMaterial3Api::class)
fun Modifier.raftPullToRefresh(
    state: PullToRefreshState,
    enabled: Boolean = true,
): Modifier =
    pullToRefresh(
        isRefreshing = state.isRefreshing,
        state = state.gestureState,
        enabled = enabled && state.onRefresh != null,
        threshold = state.refreshThreshold,
        onRefresh = { state.onRefresh?.invoke() },
    )

@Suppress("UNUSED_PARAMETER")
fun LazyListScope.pullToRefreshItem(
    state: PullToRefreshState,
    onRefresh: () -> Unit,
    scrollState: LazyListState,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    refreshThreshold: Dp = RaftPullToRefreshDefaultThreshold,
    holdRefreshInset: Boolean = true,
    content: @Composable (pullProgress: Float, isRefreshing: Boolean, refreshThreshold: Dp) -> Unit = { _, _, _ -> },
) {
    item(key = "pull_to_refresh") {
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
                refreshThreshold * pullProgress.coerceIn(0f, RaftPullToRefreshMaxStretch)
            }
        if (height > 0.dp) {
            Box(
                modifier.fillMaxWidth().height(height).clipToBounds(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                content(if (isRefreshing) 1f else pullProgress, isRefreshing, refreshThreshold)
            }
        }
    }
}

private const val RaftPullToRefreshMaxStretch = 1.5f
