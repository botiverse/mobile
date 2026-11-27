package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftStatusBarBackdropClaim
import build.raft.designsystem.components.raftPaneGridNoFade

/**
 * The viewer's own bar actions: only what the web page's ⋯ cannot offer (owner decision
 * 2026-11-10, "two bars"; #proj-mobile:#375 removed the temporary 1.30 Open in browser button):
 * Desktop Mode (where offered; null: not offered) in ⋮. Everything else (where it lives, presence,
 * Comments, Find, the version history, Copy link, Follow, Send to conversation, Who can see,
 * Rename, Files, Download, Delete, …) is the page's own toolbar, under this bar, so nothing shows
 * twice.
 */
internal class RaftArtifactViewerBarActions(
    val onToggleDesktopMode: (() -> Unit)? = null,
)

/**
 * The viewer's page: the App's title bar (Back, the title, and ⋮ with Desktop Mode when offered)
 * over [content], the web page with its own toolbar and ⋯ menu. [desktopMode]: on or off, null
 * where it is not offered (hiding ⋮). The menu is a window on a device; screenshots draw
 * [RaftArtifactViewerMenuItems] themselves.
 *
 * [pageDrawsBar] (artin, "双栏"): the page said its toolbar is the only bar (Back, the title, and
 * this bar's items in its own ⋯; [raftArtifactViewerPageDrawsHostBar]). Then this bar is not drawn:
 * [content] starts under the status bar, which keeps the page's colour. [content] keeps its place
 * either way, so the WebView is never recreated when the bar comes or goes.
 *
 * When [overlayHeaderOverContent] is true and [pageDrawsBar] is false, [content] is laid out at
 * its full final height directly below the status-bar safe area from frame 0 while
 * [RaftTopPageHeader] overlays its top edge. When [pageDrawsBar] later flips to true
 * (`hostToolbar=1`), [content] does not shift or resize, avoiding a cold-open WebView resize flash.
 */
@Composable
internal fun RaftArtifactViewerFrame(
    title: String,
    onBack: () -> Unit,
    actions: RaftArtifactViewerBarActions = RaftArtifactViewerBarActions(),
    desktopMode: Boolean? = null,
    pageDrawsBar: Boolean = false,
    /** Screenshot fixtures only (no host window there). */
    topSafeAreaOverride: Dp? = null,
    /**
     * In the wide layout's Artifact column (#189 step 4): not at the window's top, so where the page draws the only
     * bar nothing of the window's is touched (the host's native bar, the status bar, its safe area).
     */
    inColumn: Boolean = false,
    /** Overlays the native header over [content] while [pageDrawsBar] is false so [content] never resizes on handoff. */
    overlayHeaderOverContent: Boolean = false,
    content: @Composable () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = desktopMode != null && actions.onToggleDesktopMode != null
    val topSafeArea =
        if (inColumn) {
            0.dp
        } else {
            (topSafeAreaOverride ?: raftTopSafeAreaPadding()).coerceAtLeast(0.dp)
        }
    Layout(
        modifier = Modifier.fillMaxSize().background(RaftColor.Canvas),
        content = {
            Box(modifier = Modifier.fillMaxWidth()) {
                if (pageDrawsBar) {
                    // No head here: the host's own bar (iOS native navigation) is hidden too. In a column the page's toolbar
                    // is the column's head: nothing of the window's to hide or keep clear.
                    if (!inColumn) {
                        RaftNativeNavBarDecline()
                        RaftStatusBarBackdropClaim(RaftColor.Canvas, enabled = topSafeArea > 0.dp)
                        Spacer(Modifier.fillMaxWidth().height(topSafeArea))
                    }
                } else {
                    RaftTopPageHeader(
                        title = title,
                        backgroundColor = RaftColor.Canvas,
                        onBack = onBack,
                        topSafeAreaOverride = topSafeAreaOverride,
                        trailingContent =
                            if (!hasMenu) null else {
                                {
                                    RaftDesignSystemTheme {
                                        Box {
                                            RaftIconButton(
                                                onClick = { menuOpen = true },
                                                tooltip = stringResource(Res.string.artifacts_viewer_more),
                                                variant = RaftButtonVariant.Ghost,
                                                size = RaftIconButtonSize.Lg,
                                            ) {
                                                RaftSlotIcon(RaftLucideIconName.EllipsisVertical)
                                            }
                                            RaftDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, minWidth = 220.dp) {
                                                RaftArtifactViewerMenuItems(desktopMode = desktopMode, actions = actions, onDone = { menuOpen = false })
                                            }
                                        }
                                    }
                                }
                            },
                    )
                }
            }
            // The page is full-bleed: in a clipped column (#189 rule 2) it is cut hard at the column's edge, never faded
            // into the surface (raftPaneGridNoFade), whatever it draws, loading or not.
            Box(modifier = Modifier.fillMaxWidth().raftPaneGridNoFade()) { content() }
        },
    ) { measurables, constraints ->
        val headerPlaceable = measurables[0].measure(constraints.copy(minHeight = 0))
        val topSafeAreaPx =
            if (constraints.hasBoundedHeight) {
                topSafeArea.roundToPx().coerceIn(0, constraints.maxHeight)
            } else {
                topSafeArea.roundToPx().coerceAtLeast(0)
            }
        val contentTop =
            if (pageDrawsBar || overlayHeaderOverContent) {
                topSafeAreaPx
            } else if (constraints.hasBoundedHeight) {
                headerPlaceable.height.coerceIn(0, constraints.maxHeight)
            } else {
                headerPlaceable.height
            }
        val contentConstraints =
            if (constraints.hasBoundedHeight) {
                val contentHeight = (constraints.maxHeight - contentTop).coerceAtLeast(0)
                constraints.copy(minHeight = contentHeight, maxHeight = contentHeight)
            } else {
                constraints.copy(minHeight = 0)
            }
        val contentPlaceable = measurables[1].measure(contentConstraints)
        val layoutWidth =
            if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                maxOf(headerPlaceable.width, contentPlaceable.width)
            }
        val layoutHeight =
            if (constraints.hasBoundedHeight) {
                constraints.maxHeight
            } else {
                contentTop + contentPlaceable.height
            }
        layout(layoutWidth, layoutHeight) {
            contentPlaceable.placeRelative(0, contentTop)
            headerPlaceable.placeRelative(0, 0)
        }
    }
}

/**
 * The ⋮ menu's items: Desktop Mode (where offered: a check mark while on, the label constant as in
 * the HTML attachment preview).
 */
@Composable
internal fun ColumnScope.RaftArtifactViewerMenuItems(
    actions: RaftArtifactViewerBarActions,
    onDone: () -> Unit,
    desktopMode: Boolean? = null,
) {
    val toggleDesktopMode = actions.onToggleDesktopMode
    if (toggleDesktopMode != null && desktopMode != null) {
        RaftMenuItem(
            text = stringResource(Res.string.file_preview_desktop_mode_off),
            leadingIcon = { RaftSlotIcon(RaftLucideIconName.Monitor) },
            checked = desktopMode,
            onClick = {
                onDone()
                toggleDesktopMode()
            },
        )
    }
}
