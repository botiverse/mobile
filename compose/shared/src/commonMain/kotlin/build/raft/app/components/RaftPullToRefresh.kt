package build.raft.app.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.designsystem.theme.RaftTheme

/**
 * Pull-to-refresh for every refreshable list: Compose Material 3's own pull-to-refresh, unchanged
 * (default threshold, drag behaviour and indicator; the list does not move, the indicator slides
 * down from the top edge like Flutter's `RefreshIndicator`). Only the indicator colours come from
 * the design system.
 *
 * This is `PullToRefreshBox` plus [enabled], which gates the gesture and the indicator. [isRefreshing]
 * is the caller's store signal. Material's gesture listens to user-input nested scroll only, so
 * programmatic scrolls and flings pass untouched.
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
    val colors = RaftTheme.colors
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
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = colors.layerPopover,
                color = colors.foreground,
            )
        }
    }
}
