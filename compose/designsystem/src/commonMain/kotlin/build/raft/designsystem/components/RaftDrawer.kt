package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
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
 * The window is [RaftSheet]'s: [RaftDrawerSurface] at the bottom of the window over the scrim;
 * Back and a tap on the scrim call [onDismissRequest]; it never goes under the status bar. It
 * moves as raft-ui's drawer (drawer.recipe.ts; Motion "A scrim moves with its surface"): it
 * slides up (240ms, `ease-slide`) once the window has drawn its first frame, with the scrim fading
 * in alongside, and [visible] false slides it back down with the scrim fading out (180ms), then
 * calls [onHidden]: keep it composed from opening until [onHidden]. Swipe to dismiss is not
 * ported (as [RaftSheet]): the panel keeps a close control of its own.
 *
 * The surface is always 82% of the window tall (web `--drawer-content-height: 82vh`), at most
 * [RaftDrawerDefaults.MaxWidth] wide, and [content] fills it under the grab handle
 * ([RaftSheetGrabHandle]): it draws its own head.
 * [clearNavigationBar] false leaves the home indicator and the keyboard to [content] (a
 * conversation whose composer already pads for both); true pads the surface for them.
 * [overlay] is drawn over the whole window, above the scrim and the surface (a notification
 * banner that must stay on top while the drawer is open).
 */
@Composable
fun RaftDrawer(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    clearNavigationBar: Boolean = true,
    visible: Boolean = true,
    onHidden: () -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    RaftSheetWindow(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        slideMotion = true,
        visible = visible,
        onHidden = onHidden,
        overlay = overlay,
    ) { surfaceModifier ->
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
        ) {
            RaftSheetGrabHandle()
            content()
        }
    }
}

/**
 * raft-ui `DrawerSwipeHandle` (0.57.2, raft-design #944): every bottom sheet has a grab handle at
 * its top edge (Drawer Rules: "Give every bottom sheet a grab handle"; Shape: round ends, as the
 * iOS grabber and Material's drag handle). A 16dp strip (`h-4`) holding a 36 x 4dp bar in
 * `line-seam` with round ends, 6dp from the top (`before:top-1.5 before:h-1 before:w-9`),
 * centred. It is drawn only, with no semantics (`aria-hidden`). Swipe to dismiss is not ported:
 * the sheet keeps its ×, Back or a button, never only the gesture (Drawer Accessibility).
 */
@Composable
internal fun RaftSheetGrabHandle(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(RaftDrawerDefaults.GrabHandleHeight), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(width = 36.dp, height = 4.dp)
                .background(RaftTheme.colors.lineSeam, CircleShape),
        )
    }
}

/** Metrics of [RaftDrawer]. */
object RaftDrawerDefaults {
    /** The widest the drawer gets: [RaftSheet]'s 448dp, so a wide window keeps a phone-wide sheet. */
    val MaxWidth = 448.dp

    /** [RaftSheetGrabHandle]'s strip at the top of every bottom sheet (`h-4`). */
    val GrabHandleHeight = 16.dp
}
