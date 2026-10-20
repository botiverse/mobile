package build.raft.app.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import build.raft.app.raftPlatformPullToRefreshStyle
import build.raft.designsystem.theme.RaftTheme

/**
 * Pull-to-refresh for every refreshable list: the platform's own feel ([RaftPullToRefreshStyle],
 * Android = Material, iOS = Cupertino) driven by [RaftPullToRefreshController].
 *
 * The content (the list) is translated with the pull and springs back; the indicator is Compose's
 * own (Material 3 `PullToRefreshDefaults.Indicator` on Android, a Material 3
 * `CircularProgressIndicator` in the revealed gap on iOS). [isRefreshing] is the caller's store
 * signal; [enabled] gates the gesture and the indicator. The gesture listens to user-input nested
 * scroll only, so programmatic scrolls, flings and horizontal pager/back swipes pass untouched.
 */
@Composable
internal fun RaftPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentAlignment: Alignment = Alignment.TopStart,
    style: RaftPullToRefreshStyle = raftPlatformPullToRefreshStyle(),
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val geometry = remember(style, density) { raftPullToRefreshGeometry(style, density) }
    val controller = remember(style, scope) { RaftPullToRefreshController(style, scope, geometry) }
    val latestOnRefresh by rememberUpdatedState(onRefresh)
    SideEffect {
        controller.geometry = geometry
        controller.onRefresh = { latestOnRefresh() }
    }
    LaunchedEffect(controller, isRefreshing) { controller.onStoreRefreshingChanged(isRefreshing) }
    LaunchedEffect(controller, enabled) { controller.onEnabledChanged(enabled) }

    Box(
        modifier
            .onSizeChanged { controller.viewportPx = it.height.toFloat() }
            .nestedScroll(controller.nestedScrollConnection)
            .clipToBounds(),
        contentAlignment = contentAlignment,
        propagateMinConstraints = true,
    ) {
        if (enabled && style == RaftPullToRefreshStyle.Cupertino) {
            // Behind the content, clipped to the gap it reveals.
            Box(
                Modifier.matchParentSize().drawWithContent {
                    clipRect(bottom = controller.contentOffset) { this@drawWithContent.drawContent() }
                },
            ) {
                RaftCupertinoRefreshIndicator(controller, Modifier.align(Alignment.TopCenter))
            }
        }
        Box(
            Modifier.graphicsLayer { translationY = controller.contentOffset },
            contentAlignment = contentAlignment,
            content = content,
        )
        if (enabled && style == RaftPullToRefreshStyle.Material) {
            Box(Modifier.matchParentSize()) {
                RaftMaterialRefreshIndicator(controller, Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

internal fun raftPullToRefreshGeometry(style: RaftPullToRefreshStyle, density: Density): RaftPullToRefreshGeometry =
    with(density) {
        when (style) {
            RaftPullToRefreshStyle.Material ->
                RaftPullToRefreshGeometry(
                    triggerPx = PullToRefreshDefaults.PositionalThreshold.toPx(),
                    indicatorHoldPx = PullToRefreshDefaults.PositionalThreshold.toPx(),
                    coefficient = RaftMaterialRubberBandCoefficient,
                )
            RaftPullToRefreshStyle.Cupertino ->
                RaftPullToRefreshGeometry(
                    triggerPx = RaftCupertinoTrigger.toPx(),
                    indicatorHoldPx = RaftCupertinoHold.toPx(),
                    coefficient = RaftCupertinoRubberBandCoefficient,
                )
        }
    }

/** Compose Material 3's indicator (arrow arc while pulling, spinner while refreshing), Raft colours. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RaftMaterialRefreshIndicator(controller: RaftPullToRefreshController, modifier: Modifier) {
    val state = remember(controller) { RaftPullToRefreshIndicatorState(controller) }
    val colors = RaftTheme.colors
    PullToRefreshDefaults.Indicator(
        state = state,
        isRefreshing = controller.showsRefreshing,
        modifier = modifier,
        containerColor = colors.layerPopover,
        color = colors.foreground,
        maxDistance = PullToRefreshDefaults.PositionalThreshold,
    )
}

/**
 * Read-only bridge so the Material indicator follows the controller. The controller owns all
 * motion, so the indicator's own animate calls are no-ops.
 */
@OptIn(ExperimentalMaterial3Api::class)
private class RaftPullToRefreshIndicatorState(
    private val controller: RaftPullToRefreshController,
) : PullToRefreshState {
    override val distanceFraction: Float
        get() = controller.indicatorFraction

    override val isAnimating: Boolean
        get() = controller.isAnimating

    override suspend fun animateToThreshold() = Unit

    override suspend fun animateToHidden() = Unit

    override suspend fun snapTo(targetValue: Float) = Unit
}

/**
 * UIRefreshControl placement with Compose's Material 3 progress indicator: centred in the revealed
 * gap (up to the hold band), the arc filling with the pull, spinning once refreshing.
 */
@Composable
private fun RaftCupertinoRefreshIndicator(controller: RaftPullToRefreshController, modifier: Modifier) {
    val color = RaftTheme.colors.foregroundMuted
    val layer =
        modifier
            .size(RaftCupertinoSpinnerSize)
            .graphicsLayer {
                val band = controller.contentOffset.coerceAtMost(controller.geometry.indicatorHoldPx)
                translationY = (band - size.height) / 2f
                alpha = (controller.contentOffset / controller.geometry.triggerPx).coerceIn(0f, 1f)
            }
    if (controller.showsRefreshing) {
        CircularProgressIndicator(
            modifier = layer,
            color = color,
            strokeWidth = RaftCupertinoSpinnerStroke,
            trackColor = Color.Transparent,
            strokeCap = StrokeCap.Round,
        )
    } else {
        CircularProgressIndicator(
            progress = { (controller.contentOffset / controller.geometry.triggerPx).coerceIn(0f, 1f) },
            modifier = layer,
            color = color,
            strokeWidth = RaftCupertinoSpinnerStroke,
            trackColor = Color.Transparent,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
        )
    }
}

private const val RaftMaterialRubberBandCoefficient = 0.5f
private const val RaftCupertinoRubberBandCoefficient = 0.55f
private val RaftCupertinoTrigger = 72.dp
private val RaftCupertinoHold = 56.dp
private val RaftCupertinoSpinnerSize = 22.dp
private val RaftCupertinoSpinnerStroke = 2.dp
