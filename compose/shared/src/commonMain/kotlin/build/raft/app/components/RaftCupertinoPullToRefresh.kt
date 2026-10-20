package build.raft.app.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * iOS system-style pull-to-refresh (UIRefreshControl look), the "native" choice on iOS: the whole
 * content follows the finger down with rubber-band resistance and reveals a gray spoked activity
 * indicator (UIActivityIndicatorView look) above it. The spokes appear one by one while pulling;
 * releasing past the threshold refreshes, the content settles to hold the spinning indicator, and
 * springs back when [isRefreshing] turns false.
 *
 * The gesture engine is material3 `Modifier.pullToRefresh` (drag halved, tension past the
 * threshold, release/settle animations), consumed in nested post-scroll so the platform
 * overscroll does not fight it; the content offset is the gesture's distance in px.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RaftCupertinoPullToRefreshContainer(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    contentAlignment: Alignment,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    Box(
        modifier
            .pullToRefresh(
                isRefreshing = isRefreshing,
                state = state,
                enabled = enabled,
                threshold = RaftCupertinoRefreshThreshold,
                onRefresh = onRefresh,
            )
            .clipToBounds(),
    ) {
        if (enabled) {
            RaftCupertinoRefreshIndicator(state = state, isRefreshing = isRefreshing)
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY =
                        if (enabled) {
                            raftCupertinoRefreshContentOffsetFraction(state.distanceFraction) *
                                RaftCupertinoRefreshThreshold.toPx()
                        } else {
                            0f
                        }
                },
            contentAlignment = contentAlignment,
            content = content,
        )
    }
}

/**
 * The indicator sits in the gap revealed above the content: centred in it while the gap is shorter
 * than the threshold, then parked at the centre of a threshold-tall band at the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.RaftCupertinoRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
) {
    val visibleSpokes by remember(state) {
        derivedStateOf { raftCupertinoRefreshVisibleSpokes(state.distanceFraction) }
    }
    val modifier =
        Modifier
            .align(Alignment.TopCenter)
            .size(RaftCupertinoSpinnerSize)
            .graphicsLayer {
                val thresholdPx = RaftCupertinoRefreshThreshold.toPx()
                val gap = raftCupertinoRefreshContentOffsetFraction(state.distanceFraction) * thresholdPx
                translationY = gap.coerceAtMost(thresholdPx) / 2f - size.height / 2f
                alpha = if (isRefreshing) 1f else state.distanceFraction.coerceIn(0f, 1f).coerceAtLeast(0.35f)
            }
    if (isRefreshing) {
        val transition = rememberInfiniteTransition()
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = RaftCupertinoSpokeCount.toFloat(),
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = RaftCupertinoSpinPeriodMillis, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
        )
        RaftCupertinoActivityIndicator(
            visibleSpokes = RaftCupertinoSpokeCount,
            headSpoke = phase.toInt() % RaftCupertinoSpokeCount,
            modifier = modifier,
        )
    } else if (visibleSpokes > 0) {
        RaftCupertinoActivityIndicator(visibleSpokes = visibleSpokes, headSpoke = null, modifier = modifier)
    }
}

/**
 * UIActivityIndicatorView (medium) look: 8 gray rounded spokes. [visibleSpokes] spokes are drawn
 * clockwise from 12 o'clock; with [headSpoke] set (spinning), the head spoke is darkest and the
 * trailing ones fade, like the system animation.
 */
@Composable
internal fun RaftCupertinoActivityIndicator(
    visibleSpokes: Int,
    headSpoke: Int?,
    modifier: Modifier = Modifier,
    color: Color = RaftCupertinoSpinnerColor,
) {
    Canvas(modifier) {
        val radius = size.minDimension / 2f
        val inner = radius * 0.45f
        val stroke = radius * 0.24f
        val outer = radius - stroke / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        for (index in 0 until visibleSpokes.coerceIn(0, RaftCupertinoSpokeCount)) {
            val angle = (index.toDouble() / RaftCupertinoSpokeCount) * 2.0 * PI - PI / 2.0
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            val alpha =
                if (headSpoke == null) {
                    1f
                } else {
                    val behind = (headSpoke - index + RaftCupertinoSpokeCount) % RaftCupertinoSpokeCount
                    1f - behind * (0.75f / RaftCupertinoSpokeCount)
                }
            drawLine(
                color = color.copy(alpha = color.alpha * alpha),
                start = Offset(center.x + dx * inner, center.y + dy * inner),
                end = Offset(center.x + dx * outer, center.y + dy * outer),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Content offset in thresholds for the gesture's distance fraction (0 at rest, 1 while refreshing). */
internal fun raftCupertinoRefreshContentOffsetFraction(distanceFraction: Float): Float =
    distanceFraction.coerceAtLeast(0f)

/** Spokes shown while pulling: one more per eighth of the threshold, all 8 from the threshold on. */
internal fun raftCupertinoRefreshVisibleSpokes(distanceFraction: Float): Int =
    ceil(distanceFraction.coerceIn(0f, 1f) * RaftCupertinoSpokeCount).toInt()

internal const val RaftCupertinoSpokeCount = 8
private const val RaftCupertinoSpinPeriodMillis = 800
private val RaftCupertinoRefreshThreshold = 64.dp
private val RaftCupertinoSpinnerSize = 22.dp
private val RaftCupertinoSpinnerColor = Color(0xFF8E8E93)
