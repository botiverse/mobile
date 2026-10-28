package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.theme.RaftThemeDecorDefaults
import build.raft.designsystem.theme.RaftThemeDecorSlot
import build.raft.designsystem.theme.raftThemeDecor
import build.raft.designsystem.theme.raftThemeDecorTopPlacement
import build.raft.designsystem.tokens.RaftStroke

// The web's top bar at desktop width (packages/web/src/components/topbar/AppTopBar.tsx): one row
// on the frame with a seam under it, above the rail and the content. Three regions: the
// workspace (RaftWorkspaceButton, RaftWorkspaceStrip) on the leading side, search
// (RaftSearchTrigger) in the centre, and a trailing region. The two side regions share the room
// left equally, so the centre stays centred on the bar whatever they hold. An optional
// centre-leading slot (the history arrows) sits just before the centre, outside its width.

/** Metrics of [RaftTopBar] (web AppTopBar `BAR_FRAME`). */
object RaftTopBarDefaults {
    /** The bar, its `line-seam` included (`h-12 border-b`). */
    val Height: Dp = 48.dp

    /** From the bar's top and bottom edges to its 36dp frame items. */
    val ItemInset: Dp = 6.dp

    /** The leading region's padding: 9dp on the outside (so the workspace avatar sits 20dp
     *  from the edge, inside the button's border and padding), 8dp before the centre. */
    val LeadingStartPadding: Dp = 9.dp
    val LeadingEndPadding: Dp = 8.dp

    /** The trailing region's padding: 8dp after the centre, 12dp on the outside. */
    val TrailingStartPadding: Dp = 8.dp
    val TrailingEndPadding: Dp = 12.dp

    /** From the centre-leading items (the history arrows) to the centre (the search field). */
    val CenterLeadingGap: Dp = 8.dp

    /**
     * The end-side room system window controls take over the bar (raft-ui
     * `--raft-frame-inset-end`): the end side of the caption bar's insets, for a window whose
     * caption controls overlay the app's content. Zero where the system keeps its controls in a
     * title bar of its own or on the start side (macOS traffic lights, iPadOS windowed mode).
     * The horizontal safe area ([LocalRaftSafeAreaInsets]) is separate: the bar pads it anyway.
     */
    @Composable
    fun windowControlsEndInset(): Dp {
        val insets = WindowInsets.captionBar
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val px = if (direction == LayoutDirection.Ltr) insets.getRight(density, direction) else insets.getLeft(density, direction)
        return with(density) { px.toDp() }
    }
}

/**
 * What the theme decor needs to know of the bar's end, measured in the layout phase and read
 * only by the decor's draw (no recomposition): the trailing region's width (from the centre's
 * end to the bar's content end) and the width of the trailing content inside it.
 */
@Stable
private class RaftTopBarDecorRoom {
    var trailingRegionPx by mutableIntStateOf(-1)
    var trailingContentPx by mutableIntStateOf(0)

    /** The strip's end offset in [density] for an [artWidth] strip ([raftThemeDecorTopPlacement]). */
    fun placement(density: Density, endInset: Dp, artWidth: Dp): Dp? =
        with(density) {
            val region = trailingRegionPx
            raftThemeDecorTopPlacement(
                trailingOccupied = trailingContentPx.toDp() + RaftTopBarDefaults.TrailingEndPadding,
                endInset = endInset,
                centerEndOffset = if (region < 0) Dp.Infinity else region.toDp() + endInset.coerceAtLeast(0.dp),
                artWidth = artWidth,
            )
        }
}

/**
 * The trailing region: [content] end-aligned in a wrap-content row, so [room] learns how far the
 * actions actually reach rather than the region's half share.
 */
@Composable
private fun RaftTopBarTrailing(
    modifier: Modifier,
    room: RaftTopBarDecorRoom,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .onSizeChanged { room.trailingRegionPx = it.width }
            .padding(start = RaftTopBarDefaults.TrailingStartPadding, end = RaftTopBarDefaults.TrailingEndPadding),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.onSizeChanged { room.trailingContentPx = it.width },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * The web top bar: a [RaftTopBarDefaults.Height] row on `layer-frame` (the brand yellow in light
 * mode, the dark frame in dark) with a 1dp `line-seam` underneath, content in
 * `foreground-on-frame`. [topInset] (the status bar) is added above the row, on the frame.
 * A decorated theme paints its pixel art on the row's end from a 1024dp window
 * ([RaftThemeDecorSlot.Top], web AppTopBar `frame-decor-top`): before the trailing actions and
 * the end inset, only in the empty room after the centre, whole or not at all
 * ([raftThemeDecorTopPlacement]). Tooltips of the bar's items open below them
 * ([RaftTooltipPlacement.Below]), away from the window's edge and its controls.
 *
 * [leading] and [trailing] each take half of the room the [center] leaves (`flex-1`), so the
 * centre stays centred on the bar; [leading] lays out from the start, [trailing] from the end.
 */
@Composable
fun RaftTopBar(
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    leading: @Composable RowScope.() -> Unit = {},
    center: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    /**
     * Items just before [center] (the history arrows), [RaftTopBarDefaults.CenterLeadingGap]
     * before it and outside its width, so [center] stays centred. Null draws none.
     */
    centerLeading: (@Composable RowScope.() -> Unit)? = null,
    /**
     * The least room [leading] keeps beside [centerLeading] (the workspace button at its
     * narrowest). When the centred layout would leave it less, [centerLeading] and [center]
     * move toward the end together and [center] narrows from its start, its end staying put.
     */
    leadingMinWidth: Dp = 0.dp,
    /**
     * The window's width, for the theme decor's gate (raft-ui `frame-decor-top` shows from a
     * 1024px window, [RaftThemeDecorDefaults.TopMinWindowWidth]). Unspecified takes the bar's
     * own width.
     */
    windowWidth: Dp = Dp.Unspecified,
    /**
     * The end-side room the system's window controls take over the bar (raft-ui
     * `--raft-frame-inset-end`): the frame runs under it, the items stay out of it, and the decor
     * moves in by it.
     */
    endInset: Dp = RaftTopBarDefaults.windowControlsEndInset(),
) {
    val colors = RaftTheme.colors
    val density = LocalDensity.current
    val room = remember { RaftTopBarDecorRoom() }
    val inset = endInset.coerceAtLeast(0.dp)
    val decorEnd: (Dp) -> Dp? = { artWidth -> room.placement(density, inset, artWidth) }
    // The frame runs up under the status bar when the bar pads for it: the host's icons follow it.
    RaftStatusBarBackdropClaim(colors.layerFrame, enabled = topInset > 0.dp)
    Column(modifier.fillMaxWidth().background(colors.layerFrame)) {
        CompositionLocalProvider(
            LocalRaftContentColor provides colors.foregroundOnFrame,
            LocalRaftTooltipPlacement provides RaftTooltipPlacement.Below,
        ) {
            if (centerLeading != null && center != null) {
                RaftTopBarWithCenterLeading(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = topInset)
                        // The frame runs edge to edge; its items stay out of the horizontal safe area.
                        .raftSafeAreaHorizontalPadding()
                        .height(RaftTopBarDefaults.Height - RaftStroke.Hairline)
                        // The theme's pixel art on the bar's end, over the frame, under the items.
                        .raftThemeDecor(RaftThemeDecorSlot.Top, windowWidth, decorEnd)
                        // The items stay out of the window controls; the frame and the decor's offset don't.
                        .padding(end = inset),
                    room = room,
                    leading = leading,
                    centerLeading = centerLeading,
                    center = center,
                    trailing = trailing,
                    leadingMinWidth = leadingMinWidth,
                )
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = topInset)
                        // The frame runs edge to edge; its items stay out of the horizontal safe area.
                        .raftSafeAreaHorizontalPadding()
                        .height(RaftTopBarDefaults.Height - RaftStroke.Hairline)
                        .raftThemeDecor(RaftThemeDecorSlot.Top, windowWidth, decorEnd)
                        .padding(end = inset),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier
                            .weight(1f)
                            .padding(start = RaftTopBarDefaults.LeadingStartPadding, end = RaftTopBarDefaults.LeadingEndPadding),
                        verticalAlignment = Alignment.CenterVertically,
                        content = leading,
                    )
                    if (center != null) Box(contentAlignment = Alignment.Center) { center() }
                    RaftTopBarTrailing(Modifier.weight(1f), room, trailing)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam))
    }
}

/**
 * [RaftTopBar] with a centre-leading slot: the centre at the bar's middle (as the plain bar has
 * it), [centerLeading] [RaftTopBarDefaults.CenterLeadingGap] before it, [leading] in what is left
 * before that, [trailing] in the same half-share after the centre as the plain bar gives it.
 * [leading] keeps at least [leadingMinWidth]: when it would get less, the centre-leading items and
 * the centre move toward the end by the shortfall and the centre narrows by it, so nothing
 * overlaps the leading items and the centre's end (and the trailing region) stays put.
 */
@Composable
private fun RaftTopBarWithCenterLeading(
    modifier: Modifier,
    room: RaftTopBarDecorRoom,
    leading: @Composable RowScope.() -> Unit,
    centerLeading: @Composable RowScope.() -> Unit,
    center: @Composable () -> Unit,
    trailing: @Composable RowScope.() -> Unit,
    leadingMinWidth: Dp,
) {
    Layout(
        modifier = modifier,
        content = {
            Row(
                Modifier.padding(start = RaftTopBarDefaults.LeadingStartPadding, end = RaftTopBarDefaults.LeadingEndPadding),
                verticalAlignment = Alignment.CenterVertically,
                content = leading,
            )
            Row(
                Modifier.padding(end = RaftTopBarDefaults.CenterLeadingGap),
                verticalAlignment = Alignment.CenterVertically,
                content = centerLeading,
            )
            Box(contentAlignment = Alignment.Center) { center() }
            RaftTopBarTrailing(Modifier, room, trailing)
        },
    ) { measurables, constraints ->
        val (leadingM, centerLeadingM, centerM, trailingM) = measurables
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        fun loose(max: Int) = constraints.copy(minWidth = 0, maxWidth = max.coerceAtLeast(0), minHeight = 0)
        // The centre at the bar's middle, as the plain bar lays it out.
        val centerIdeal = centerM.maxIntrinsicWidth(height).coerceAtMost(width)
        val side = (width - centerIdeal) / 2
        val arrows = centerLeadingM.measure(loose(width))
        val leadingFloor =
            leadingMinWidth.roundToPx() + RaftTopBarDefaults.LeadingStartPadding.roundToPx() + RaftTopBarDefaults.LeadingEndPadding.roundToPx()
        val centeredLeading = side - arrows.width
        val shift = (leadingFloor - centeredLeading).coerceIn(0, centerIdeal)
        val centerX = side + shift
        val center = centerM.measure(loose(centerIdeal - shift))
        val leadingRow = leadingM.measure(loose(centeredLeading + shift).copy(minWidth = (centeredLeading + shift).coerceAtLeast(0)))
        val trailingWidth = width - side - centerIdeal
        val trailingRow = trailingM.measure(loose(trailingWidth).copy(minWidth = trailingWidth.coerceAtLeast(0)))
        layout(width, height) {
            fun y(h: Int) = (height - h) / 2
            leadingRow.placeRelative(0, y(leadingRow.height))
            arrows.placeRelative(centerX - arrows.width, y(arrows.height))
            center.placeRelative(centerX, y(center.height))
            trailingRow.placeRelative(side + centerIdeal, y(trailingRow.height))
        }
    }
}
