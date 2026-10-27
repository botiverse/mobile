package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke

// The web's top bar at desktop width (packages/web/src/components/topbar/AppTopBar.tsx): one row
// on the frame with a seam under it, above the rail and the content. Three regions: the
// workspace (RaftWorkspaceButton, RaftWorkspaceStrip) on the leading side, search
// (RaftSearchTrigger) in the centre, and a trailing region. The two side regions share the room
// left equally, so the centre stays centred on the bar whatever they hold.

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
}

/**
 * The web top bar: a [RaftTopBarDefaults.Height] row on `layer-frame` (the brand yellow in light
 * mode, the dark frame in dark) with a 1dp `line-seam` underneath, content in
 * `foreground-on-frame`. [topInset] (the status bar) is added above the row, on the frame.
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
) {
    val colors = RaftTheme.colors
    Column(modifier.fillMaxWidth().background(colors.layerFrame)) {
        CompositionLocalProvider(LocalRaftContentColor provides colors.foregroundOnFrame) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = topInset)
                    // The frame runs edge to edge; its items stay out of the horizontal safe area.
                    .raftSafeAreaHorizontalPadding()
                    .height(RaftTopBarDefaults.Height - RaftStroke.Hairline),
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
                Row(
                    Modifier
                        .weight(1f)
                        .padding(start = RaftTopBarDefaults.TrailingStartPadding, end = RaftTopBarDefaults.TrailingEndPadding),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam))
    }
}
