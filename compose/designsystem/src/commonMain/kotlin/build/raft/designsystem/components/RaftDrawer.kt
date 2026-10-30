package build.raft.designsystem.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius

/**
 * raft-ui Drawer (bottom sheet) holding a panel of the caller's own: web `<DrawerContent>` with
 * a whole panel as its child (the assistant opened from its otter on a phone), where
 * [RaftSheet] is the Drawer with its standard title, body and actions.
 *
 * The window is [RaftSheet]'s: [RaftDrawerSurface] at the bottom of the window over the scrim,
 * sliding up as it opens (240ms, `ease-slide`); Back and a tap on the scrim call
 * [onDismissRequest]; it never goes under the status bar. Swipe to dismiss is not ported (as
 * [RaftSheet]): the panel keeps a close control of its own.
 *
 * The surface is always 82% of the window tall (web `--drawer-content-height: 82vh`), at most
 * [RaftDrawerDefaults.MaxWidth] wide, and [content] fills it: it draws its own head.
 * [clearNavigationBar] false leaves the home indicator and the keyboard to [content] (a
 * conversation whose composer already pads for both); true pads the surface for them.
 */
@Composable
fun RaftDrawer(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    clearNavigationBar: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    RaftSheetWindow(onDismissRequest, modifier) { surfaceModifier ->
        RaftDrawerSurfaceImpl(surfaceModifier, clearNavigationBar, content)
    }
}

/**
 * [RaftDrawer]'s surface without its window, for fixtures: `layer-popover`, the dialog radius on
 * its top corners, the `line-seam` ring, clipped to its shape. It fills the height it is given
 * (the window gives it 82%); a fixture gives it one.
 */
@Composable
fun RaftDrawerSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = RaftDrawerSurfaceImpl(modifier, clearNavigationBar = false, content)

@Composable
private fun RaftDrawerSurfaceImpl(
    modifier: Modifier,
    clearNavigationBar: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    CompositionLocalProvider(LocalRaftContentColor provides colors.foreground, LocalRaftSurfaceColor provides colors.layerPopover) {
        Column(
            modifier
                .widthIn(max = RaftDrawerDefaults.MaxWidth)
                .fillMaxWidth()
                // After the window's 82% cap: the surface takes all of it, whatever its content.
                .fillMaxHeight()
                .raftOverlaySurface(colors.layerPopover, colors.lineSeam, RaftTheme.isDark, RaftRadius.Dialog, 0.dp)
                .then(if (clearNavigationBar) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) else Modifier),
            content = content,
        )
    }
}

/** Metrics of [RaftDrawer]. */
object RaftDrawerDefaults {
    /** The widest the drawer gets: [RaftSheet]'s 448dp, so a wide window keeps a phone-wide sheet. */
    val MaxWidth = 448.dp
}
