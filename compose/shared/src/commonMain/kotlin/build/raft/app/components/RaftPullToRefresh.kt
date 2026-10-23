package build.raft.app.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import build.raft.app.raftPlatformPullToRefreshStyle
import build.raft.designsystem.theme.RaftTheme
import kotlin.math.roundToInt

/**
 * Pull-to-refresh for every refreshable list, in the platform's own feel ([RaftPullToRefreshStyle]).
 *
 * - Android: Compose Material 3's own pull-to-refresh, unchanged (default threshold, drag behaviour
 *   and indicator; the list does not move, the indicator slides down from the top edge). Only the
 *   indicator colours come from the design system.
 * - iOS: UIRefreshControl feel driven by [RaftPullToRefreshController]: the list follows the finger
 *   with UIScrollView's rubber band and the system `UIActivityIndicatorView` sits in the gap
 *   ([RaftCupertinoRefreshIndicator]; metrics on [RaftCupertinoTrigger] / [RaftCupertinoHold]).
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

/**
 * [RaftPullToRefreshBox] over a store whose [refreshing] flag also covers refreshes the user did
 * not pull (open, resume, realtime, retry). The indicator follows only a refresh this box asked
 * for; a pull while the store is already refreshing joins that refresh instead of starting
 * another, so [onRefresh] (the page's store Refresh intent) runs once per idle pull.
 */
@Composable
internal fun RaftStorePullToRefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (!refreshing) pulled = false
    }
    val latestRefreshing by rememberUpdatedState(refreshing)
    RaftPullToRefreshBox(
        isRefreshing = refreshing && pulled,
        onRefresh = {
            pulled = true
            if (!latestRefreshing) onRefresh()
        },
        modifier = modifier,
        enabled = enabled,
        contentAlignment = contentAlignment,
        content = content,
    )
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
            // In the gap the content reveals; the indicator sizes itself to that gap.
            Box(Modifier.matchParentSize()) {
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
 * The iOS pull indicator: the system spinner ([RaftCupertinoActivityIndicator], a
 * `UIActivityIndicatorView`) in a box that covers the revealed gap up to the hold band, so the
 * spinner stays centred in the visible gap and is clipped by it. While pulling, its spokes appear
 * one by one with the pull ([raftRefreshRevealedTicks]); it spins from the moment the pull arms
 * (with the commit haptic) through the refresh, and keeps spinning while the content springs back
 * from a refresh, as UIRefreshControl does after `endRefreshing()`.
 */
@Composable
private fun RaftCupertinoRefreshIndicator(controller: RaftPullToRefreshController, modifier: Modifier) {
    val haptic = rememberRaftCupertinoRefreshHaptic()
    // Recomposes on spoke changes only; the box height follows the pull in the layout phase.
    val revealedTicks by remember(controller) {
        derivedStateOf { raftRefreshRevealedTicks(controller.pullProgress) }
    }
    val committed by remember(controller) {
        derivedStateOf { controller.armed || controller.showsRefreshing }
    }
    var spinning by remember(controller) { mutableStateOf(false) }
    val phase = controller.phase
    LaunchedEffect(committed, phase) {
        when {
            committed -> spinning = true
            // Released short of the threshold or back at rest: the spokes follow the pull again.
            phase == RaftPullToRefreshPhase.Idle || phase == RaftPullToRefreshPhase.Pulling -> spinning = false
        }
    }
    LaunchedEffect(controller) {
        snapshotFlow { controller.phase == RaftPullToRefreshPhase.Pulling }.collect { if (it) haptic.prepare() }
    }
    LaunchedEffect(controller) {
        snapshotFlow { controller.armed }.collect { if (it) haptic.trigger() }
    }
    RaftCupertinoActivityIndicator(
        revealedTicks = revealedTicks,
        animating = spinning,
        modifier =
            modifier.fillMaxWidth().layout { measurable, constraints ->
                val band =
                    controller.contentOffset.coerceIn(0f, controller.geometry.indicatorHoldPx).roundToInt()
                val placeable = measurable.measure(constraints.copy(minHeight = band, maxHeight = band))
                layout(placeable.width, band) { placeable.place(0, 0) }
            },
    )
}

private const val RaftMaterialRubberBandCoefficient = 0.5f

/** UIScrollView's rubber-band constant (`c = 0.55`, the widely reproduced UIKit value). */
private const val RaftCupertinoRubberBandCoefficient = 0.55f

/**
 * Content offset at which an iOS pull commits. Apple does not document UIRefreshControl's
 * threshold; Flutter's iOS-fidelity port (`CupertinoSliverRefreshControl`,
 * `_defaultRefreshTriggerPullDistance = 100.0`) commits at 100pt of visible overscroll, and so
 * does this (the content offset, after the rubber band). It must exceed [RaftCupertinoHold] so the
 * spokes finish revealing after the gap reaches its resting height, as on the system control.
 */
private val RaftCupertinoTrigger = 100.dp

/**
 * Gap held open while refreshing: UIRefreshControl's own height, 60pt (observed default frame
 * height; the scroll view's top inset grows by it while refreshing, Apple does not document it).
 * Flutter's iOS port uses the same (`_defaultRefreshIndicatorExtent = 60.0`).
 */
private val RaftCupertinoHold = 60.dp
