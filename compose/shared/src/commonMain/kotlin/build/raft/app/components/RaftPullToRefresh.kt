package build.raft.app.components

import build.raft.designsystem.haptics.rememberRaftHaptics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
 *   with UIScrollView's rubber band and the design system's spin loader sits in the gap
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
 * Whether a page header's refresh spinner shows (artin task #22). While the user's pull owns the
 * refresh, the pull indicator is the only refresh chrome: the header spinner would duplicate it.
 * Refreshes the user did not pull keep the header spinner.
 */
internal fun raftHeaderRefreshIndicatorVisible(refreshing: Boolean, pullRefreshing: Boolean): Boolean =
    refreshing && !pullRefreshing

/**
 * Hoistable pull state of [RaftStorePullToRefreshBox], so a page header can tell a pulled refresh
 * from any other refresh of the same store flag ([raftHeaderRefreshIndicatorVisible]).
 */
internal class RaftStorePullRefreshState {
    var pulled by mutableStateOf(false)
        internal set

    /** The store is refreshing because of a pull on this box. */
    fun pullRefreshing(refreshing: Boolean): Boolean = refreshing && pulled

    /** The header spinner for the same [refreshing] flag: hidden while the pull indicator shows. */
    fun headerRefreshing(refreshing: Boolean): Boolean =
        raftHeaderRefreshIndicatorVisible(refreshing, pullRefreshing(refreshing))
}

@Composable
internal fun rememberRaftStorePullRefreshState(): RaftStorePullRefreshState = remember { RaftStorePullRefreshState() }

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
    pullState: RaftStorePullRefreshState = rememberRaftStorePullRefreshState(),
    content: @Composable BoxScope.() -> Unit,
) {
    LaunchedEffect(refreshing) {
        if (!refreshing) pullState.pulled = false
    }
    // A hoisted state outlives this box: if the box leaves mid-refresh, nothing would clear the
    // pull, and a later refresh the user did not pull would read as pulled.
    DisposableEffect(pullState) {
        onDispose { pullState.pulled = false }
    }
    val latestRefreshing by rememberUpdatedState(refreshing)
    RaftPullToRefreshBox(
        isRefreshing = pullState.pullRefreshing(refreshing),
        onRefresh = {
            pullState.pulled = true
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
 * clockwise with the pull. It spins from the moment the pull commits at the threshold (with
 * UIRefreshControl's commit haptic) through the refresh, and keeps spinning while the gap springs
 * closed after it, as UIRefreshControl's spinner does after `endRefreshing()`.
 */
@Composable
private fun RaftCupertinoRefreshIndicator(controller: RaftPullToRefreshController, modifier: Modifier) {
    val color = RaftTheme.colors.foregroundMuted
    // The iOS-style pull plays a threshold buzz where UIRefreshControl does (RaftHaptics: light impact).
    val haptic = rememberRaftHaptics()
    val committed by remember(controller) {
        derivedStateOf { controller.armed || controller.showsRefreshing }
    }
    var spinning by remember(controller) { mutableStateOf(false) }
    val phase = controller.phase
    LaunchedEffect(committed, phase) {
        when {
            committed -> spinning = true
            // Released short of the threshold or back at rest: the light follows the pull again.
            phase == RaftPullToRefreshPhase.Idle || phase == RaftPullToRefreshPhase.Pulling -> spinning = false
        }
    }
    LaunchedEffect(controller) {
        snapshotFlow { controller.phase == RaftPullToRefreshPhase.Pulling }.collect { if (it) haptic.prepare() }
    }
    LaunchedEffect(controller) {
        snapshotFlow { controller.armed }.collect { if (it) haptic.threshold() }
    }
    val layer =
        modifier.graphicsLayer {
            val band = controller.contentOffset.coerceAtMost(controller.geometry.indicatorHoldPx)
            translationY = (band - size.height) / 2f
            alpha = if (spinning) 1f else controller.pullProgress
        }
    if (spinning) {
        RaftSpinner(modifier = layer, size = RaftCupertinoSpinnerSize, color = color)
    } else {
        // Before the threshold the light walks with the pull: step n lights the n-th pixel.
        val step by remember(controller) {
            derivedStateOf { (controller.pullProgress * 3.999f).toInt() }
        }
        RaftSpinner(modifier = layer, size = RaftCupertinoSpinnerSize, color = color, stillStep = step)
    }
}

private const val RaftMaterialRubberBandCoefficient = 0.5f

/** UIScrollView's rubber-band constant (`c = 0.55`, the widely reproduced UIKit value). */
private const val RaftCupertinoRubberBandCoefficient = 0.55f

/**
 * Content offset at which an iOS pull commits. Apple does not document UIRefreshControl's
 * threshold; Flutter's iOS-fidelity port (`CupertinoSliverRefreshControl`,
 * `_defaultRefreshTriggerPullDistance = 100.0`) commits at 100pt of visible overscroll, and so
 * does this (the content offset, after the rubber band). It exceeds [RaftCupertinoHold], so the
 * indicator finishes filling in after the gap passes its resting height, as on the system control.
 */
private val RaftCupertinoTrigger = 100.dp

/**
 * Gap held open while refreshing: UIRefreshControl's own height, 60pt (observed default frame
 * height; the scroll view's top inset grows by it while refreshing, Apple does not document it).
 * Flutter's iOS port uses the same (`_defaultRefreshIndicatorExtent = 60.0`).
 */
private val RaftCupertinoHold = 60.dp
private val RaftCupertinoSpinnerSize = RaftSpinnerSize.Md
