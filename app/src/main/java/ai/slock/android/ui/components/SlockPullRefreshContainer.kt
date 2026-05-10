package ai.slock.android.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.slock.android.ui.theme.SlockSpacing

object SlockPullRefreshDefaults {
    val Threshold = 180.dp
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlockPullRefreshContainer(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    threshold: Dp = SlockPullRefreshDefaults.Threshold,
    content: @Composable BoxScope.(Dp) -> Unit
) {
    val refreshState = rememberPullToRefreshState()
    Box(
        modifier = modifier.pullToRefresh(
            isRefreshing = refreshing,
            state = refreshState,
            enabled = enabled,
            threshold = threshold,
            onRefresh = {
                if (enabled && !refreshing) {
                    onRefresh()
                }
            }
        )
    ) {
        val progress = refreshState.distanceFraction.coerceIn(0f, 1f)
        val easedProgress = ((progress - 0.16f) / 0.84f).coerceIn(0f, 1f)
        val contentOffset = if (refreshing) {
            SlockSpacing.Xl
        } else {
            SlockSpacing.Xl * easedProgress
        }
        content(contentOffset)
        SlockPullRefreshIndicator(
            refreshing = refreshing,
            progress = progress,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

@Composable
private fun SlockPullRefreshIndicator(
    refreshing: Boolean,
    progress: Float,
    modifier: Modifier = Modifier
) {
    if (!refreshing && progress <= 0.12f) return
    val transition = rememberInfiniteTransition(label = "slock-refresh-logo")
    val pulse = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520),
            repeatMode = RepeatMode.Reverse
        ),
        label = "slock-refresh-logo-pulse"
    )
    val bounce = if (refreshing) pulse.value else progress.coerceIn(0f, 1f)
    val scale = if (refreshing) {
        1f + (0.06f * pulse.value)
    } else {
        0.92f + (0.08f * progress.coerceIn(0f, 1f))
    }
    Box(
        modifier = modifier
            .offset(y = SlockSpacing.Xs * bounce)
            .padding(top = SlockSpacing.Lg),
        contentAlignment = Alignment.Center
    ) {
        SlockLogoBadge(
            text = "SLOCK",
            variant = SlockLogoBadgeVariant.Compact,
            modifier = Modifier
                .offset(y = -(SlockSpacing.Xs * bounce))
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
        )
    }
}
