package build.raft.app.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import build.raft.app.raftPlatformPullToRefreshStyle
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.theme.RaftTheme

/**
 * Pull-to-refresh for every refreshable list, in the platform's own feel ([RaftPullToRefreshStyle]).
 *
 * - Android: Compose Material 3's own pull-to-refresh, unchanged (default threshold, drag behaviour
 *   and indicator; the list does not move, the indicator slides down from the top edge). Only the
 *   indicator colours come from the design system.
 * - iOS: UIRefreshControl feel driven by [RaftPullToRefreshController]: the list follows the finger
 *   with UIScrollView's rubber band and the design system's spin loader sits in the gap.
 *
 * [isRefreshing] is the caller's store signal; [enabled] gates the gesture and the indicator. Both
 * gestures listen to user-input nested scroll only, so programmatic scrolls and flings pass untouched.
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
    when (style) {
        RaftPullToRefreshStyle.Material ->
            RaftMaterialPullToRefreshBox(isRefreshing, onRefresh, modifier, enabled, contentAlignment, content)
        RaftPullToRefreshStyle.Cupertino ->
            RaftCupertinoPullToRefreshBox(isRefreshing, onRefresh, modifier, enabled, contentAlignment, content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RaftMaterialPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    contentAlignment: Alignment,
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

@Composable
private fun RaftCupertinoPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    contentAlignment: Alignment,
    content: @Composable BoxScope.() -> Unit,
) {
    val style = RaftPullToRefreshStyle.Cupertino
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val geometry = remember(density) { raftPullToRefreshGeometry(style, density) }
    val controller = remember(scope) { RaftPullToRefreshController(style, scope, geometry) }
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
        if (enabled) {
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

/**
 * The iOS pull indicator is the design system's spin loader ([RaftSpinner], raft-ui Spinner),
 * centred in the revealed gap (up to the hold band). While pulling, its four pixels light up
 * clockwise with the pull; once refreshing it runs its own step animation.
 */
@Composable
private fun RaftCupertinoRefreshIndicator(controller: RaftPullToRefreshController, modifier: Modifier) {
    val color = RaftTheme.colors.foregroundMuted
    val layer =
        modifier.graphicsLayer {
            val band = controller.contentOffset.coerceAtMost(controller.geometry.indicatorHoldPx)
            translationY = (band - size.height) / 2f
            alpha = (controller.contentOffset / controller.geometry.triggerPx).coerceIn(0f, 1f)
        }
    if (controller.showsRefreshing) {
        RaftSpinner(modifier = layer, size = RaftCupertinoSpinnerSize, color = color)
    } else {
        // Before the threshold the light walks with the pull: step n lights the n-th pixel.
        val step by remember(controller) {
            derivedStateOf {
                val progress = (controller.contentOffset / controller.geometry.triggerPx).coerceIn(0f, 1f)
                (progress * 3.999f).toInt()
            }
        }
        RaftSpinner(modifier = layer, size = RaftCupertinoSpinnerSize, color = color, stillStep = step)
    }
}

private const val RaftMaterialRubberBandCoefficient = 0.5f
private const val RaftCupertinoRubberBandCoefficient = 0.55f
private val RaftCupertinoTrigger = 72.dp
private val RaftCupertinoHold = 56.dp
private val RaftCupertinoSpinnerSize = RaftSpinnerSize.Md
