package build.raft.app.compat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * TODO(cmp-switch): Kuikly's list-header pull-to-refresh (material3.pullToRefreshItem) relied on
 * Kuikly's native list overscrolling past the top, which upstream lazy lists do not do. This
 * stand-in keeps the API and shows the indicator only while [PullToRefreshState.isRefreshing];
 * there is no pull gesture. Replace call sites with material3 `PullToRefreshBox` around the list.
 */

class PullToRefreshState(isRefreshing: Boolean) {
    var isRefreshing by mutableStateOf(isRefreshing)

    /** Pull progress 0..1; always 0 here (no gesture). */
    var pullProgress by mutableStateOf(0f)
        internal set
}

@Composable
fun rememberPullToRefreshState(isRefreshing: Boolean): PullToRefreshState =
    remember { PullToRefreshState(isRefreshing) }.apply { this.isRefreshing = isRefreshing }

fun LazyListScope.pullToRefreshItem(
    state: PullToRefreshState,
    onRefresh: () -> Unit,
    scrollState: LazyListState,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    refreshThreshold: Dp = 80.dp,
    holdRefreshInset: Boolean = true,
    content: @Composable (pullProgress: Float, isRefreshing: Boolean, refreshThreshold: Dp) -> Unit = { _, _, _ -> },
) {
    item(key = "pull_to_refresh") {
        if (state.isRefreshing) {
            Box(
                modifier.fillMaxWidth().height(if (holdRefreshInset) refreshThreshold else 0.dp),
                contentAlignment = Alignment.Center,
            ) {
                content(1f, true, refreshThreshold)
            }
        }
    }
}
