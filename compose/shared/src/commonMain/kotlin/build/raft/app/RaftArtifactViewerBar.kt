package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

/**
 * The viewer's own ⋮: only what the web page's ⋯ cannot offer (owner decision 2026-11-10, "two
 * bars"): Desktop Mode (where offered; null: not offered) and Open in Browser. Everything else
 * (where it lives, presence, Comments, Find, the version history, Copy link, Follow, Send to
 * conversation, Who can see, Rename, Files, Download, Delete, …) is the page's own toolbar, under
 * this bar, so nothing shows twice.
 */
internal class RaftArtifactViewerBarActions(
    val onToggleDesktopMode: (() -> Unit)? = null,
    val onOpenInBrowser: (() -> Unit)? = null,
)

/**
 * The viewer's page: the App's title bar (Back, the title, ⋮ with Desktop Mode and Open in Browser)
 * over [content], the web page with its own toolbar and ⋯ menu. [desktopMode]: on or off, null
 * where it is not offered (no ⋮ item). The menu is a window on a device; screenshots draw
 * [RaftArtifactViewerMenuItems] themselves.
 */
@Composable
internal fun RaftArtifactViewerFrame(
    title: String,
    onBack: () -> Unit,
    actions: RaftArtifactViewerBarActions = RaftArtifactViewerBarActions(),
    desktopMode: Boolean? = null,
    /** Screenshot fixtures only (no host window there). */
    topSafeAreaOverride: Dp? = null,
    content: @Composable () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = actions.onOpenInBrowser != null || (desktopMode != null && actions.onToggleDesktopMode != null)
    Column(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        RaftTopPageHeader(
            title = title,
            backgroundColor = RaftColor.SurfaceWhite,
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
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/**
 * The ⋮ menu's items: Desktop Mode (where offered: a check mark while on, the label constant as in
 * the HTML attachment preview), then Open in Browser.
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
    actions.onOpenInBrowser?.let { open ->
        RaftMenuItem(
            text = stringResource(Res.string.artifacts_viewer_open_in_browser),
            leadingIcon = { RaftSlotIcon(RaftLucideIconName.ArrowUpRight) },
            onClick = {
                onDone()
                open()
            },
        )
    }
}
