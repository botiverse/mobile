package build.raft.app.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import build.raft.app.data.RaftNativePullToRefreshRuntime
import build.raft.app.raftPlatformNativePullToRefreshStyle
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.theme.RaftTheme

/**
 * Which pull-to-refresh implementation a [RaftPullToRefreshBox] runs.
 *
 * - [Custom]: Raft's custom pull-to-refresh (default; see RaftCustomPullToRefresh.kt): a header
 *   item in the list grows with the pull and pushes the content down.
 * - [Material]: Material 3 shape with the Raft indicator overlaid at the top (Android native).
 * - [Cupertino]: UIRefreshControl-style, content follows the finger (iOS native).
 */
internal enum class RaftPullToRefreshStyle {
    Custom,
    Material,
    Cupertino,
}

/** The custom style needs a header item inside the list; the native styles do not. */
internal val RaftPullToRefreshStyle.addsListItem: Boolean
    get() = this == RaftPullToRefreshStyle.Custom

/**
 * The Settings → Mobile "Native pull-to-refresh" switch: off = [RaftPullToRefreshStyle.Custom],
 * on = the platform's own style ([platformNativeStyle]).
 */
internal fun raftPullToRefreshStyle(
    nativeEnabled: Boolean,
    platformNativeStyle: RaftPullToRefreshStyle,
): RaftPullToRefreshStyle =
    if (nativeEnabled) platformNativeStyle else RaftPullToRefreshStyle.Custom

/** The live style: recomposes when the Settings switch changes. */
@Composable
internal fun rememberRaftPullToRefreshStyle(): RaftPullToRefreshStyle =
    raftPullToRefreshStyle(
        nativeEnabled = RaftNativePullToRefreshRuntime.enabled(),
        platformNativeStyle = raftPlatformNativePullToRefreshStyle(),
    )

/**
 * Content scope of [RaftPullToRefreshBox]. Lists inside call [raftPullToRefreshItem] first in their
 * LazyColumn content: it emits the custom style's header item, and nothing for the native styles.
 */
@Stable
internal interface RaftPullToRefreshScope : BoxScope {
    fun LazyListScope.raftPullToRefreshItem()
}

private class RaftPullToRefreshScopeImpl(
    boxScope: BoxScope,
    private val emitItem: (LazyListScope.() -> Unit)?,
) : RaftPullToRefreshScope, BoxScope by boxScope {
    override fun LazyListScope.raftPullToRefreshItem() {
        emitItem?.invoke(this)
    }
}

/**
 * The single switch point for pull-to-refresh: every refreshable list wraps itself in this box,
 * which runs the implementation picked by [style] (by default the live Settings switch, see
 * [rememberRaftPullToRefreshStyle]). [enabled] gates the gesture and the indicator;
 * [isRefreshing] is the caller's refresh signal.
 */
@Composable
internal fun RaftPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentAlignment: Alignment = Alignment.TopStart,
    style: RaftPullToRefreshStyle = rememberRaftPullToRefreshStyle(),
    content: @Composable RaftPullToRefreshScope.() -> Unit,
) {
    when (style) {
        RaftPullToRefreshStyle.Custom -> {
            val state = rememberRaftCustomPullToRefreshState(isRefreshing)
            val latestOnRefresh by rememberUpdatedState(onRefresh)
            Box(modifier.raftCustomPullToRefresh(state, enabled), contentAlignment = contentAlignment) {
                val boxScope = this
                val scope =
                    remember(boxScope, state, enabled) {
                        RaftPullToRefreshScopeImpl(
                            boxScope,
                            if (enabled) {
                                { raftCustomPullToRefreshItem(state, onRefresh = { latestOnRefresh() }) }
                            } else {
                                null
                            },
                        )
                    }
                scope.content()
            }
        }
        RaftPullToRefreshStyle.Material ->
            RaftMaterialPullToRefreshContainer(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = modifier,
                enabled = enabled,
                contentAlignment = contentAlignment,
            ) {
                val boxScope = this
                remember(boxScope) { RaftPullToRefreshScopeImpl(boxScope, null) }.content()
            }
        RaftPullToRefreshStyle.Cupertino ->
            RaftCupertinoPullToRefreshContainer(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = modifier,
                enabled = enabled,
                contentAlignment = contentAlignment,
            ) {
                val boxScope = this
                remember(boxScope) { RaftPullToRefreshScopeImpl(boxScope, null) }.content()
            }
    }
}

/**
 * Material 3 pull-to-refresh with the Raft indicator: the M3 `PullToRefreshBox` shape
 * (`Modifier.pullToRefresh` on the container, the indicator overlaid at the top centre), so the
 * content never moves or stretches while pulling; only the indicator slides down from the top
 * edge. Threshold, the release/fling handling and the indicator travel are the M3 defaults.
 *
 * [enabled] gates the gesture (M3 1.3's `PullToRefreshBox` has no such parameter); the
 * indicator is not shown while disabled. [isRefreshing] is the caller's refresh signal, which
 * keeps the indicator parked at the threshold until it turns false.
 *
 * The gesture consumes the pull in nested post-scroll, so the platform overscroll (Android
 * stretch, the CMP iOS rubber band) only receives what the refresh did not take: a downward drag
 * at the top of the list drives the indicator, flings and bottom-edge overscroll stay native.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RaftMaterialPullToRefreshContainer(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    contentAlignment: Alignment,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
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
            RaftPullToRefreshIndicator(
                state = state,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/**
 * The M3 pull-to-refresh container (`PullToRefreshDefaults.IndicatorBox`: 40dp circle, translation, clip,
 * elevation) in Raft tokens: `--layer-popover` fill with a `--line-hairline` edge, and the raft-ui
 * pixel [RaftSpinner] instead of the M3 arc. While pulling, the spinner fades in and its lit pixel
 * walks clockwise with the pull ([raftPullToRefreshIndicatorStep]); while refreshing it animates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RaftPullToRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    // Material3 1.4 (CMP 1.11+) replaced `Modifier.pullToRefreshIndicator` with
    // `PullToRefreshDefaults.IndicatorBox` (same 40dp box, clip, translation, elevation and
    // `maxDistance` = the old `threshold` default). The hairline edge used to be a `.border`
    // chained after the indicator modifier, i.e. inside the translated layer; it is now drawn
    // by a full-size box in the content, which sits in that same layer.
    PullToRefreshDefaults.IndicatorBox(
        state = state,
        isRefreshing = isRefreshing,
        modifier = modifier,
        shape = CircleShape,
        containerColor = colors.layerPopover,
        elevation = PullToRefreshDefaults.Elevation,
    ) {
        Box(Modifier.matchParentSize().border(1.dp, colors.lineHairline, CircleShape))
        if (isRefreshing) {
            RaftSpinner(size = RaftSpinnerSize.Md, color = colors.foreground)
        } else {
            // The fade follows the drag in the layer block (no recomposition); the step only
            // recomposes when the lit pixel actually changes.
            val step by remember(state) {
                derivedStateOf { raftPullToRefreshIndicatorStep(state.distanceFraction) }
            }
            RaftSpinner(
                modifier = Modifier.graphicsLayer { alpha = state.distanceFraction.coerceIn(0f, 1f) },
                size = RaftSpinnerSize.Md,
                color = colors.foreground,
                stillStep = step,
            )
        }
    }
}

/**
 * Spinner step shown while pulling (not refreshing): the lit pixel walks one quarter per quarter
 * of the threshold, top-left at rest and bottom-left (the last step) from the threshold on.
 */
internal fun raftPullToRefreshIndicatorStep(distanceFraction: Float): Int =
    (distanceFraction.coerceIn(0f, 1f) * RaftPullToRefreshSpinnerSteps)
        .toInt()
        .coerceAtMost(RaftPullToRefreshSpinnerSteps - 1)

private const val RaftPullToRefreshSpinnerSteps = 4
