package build.raft.app

import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftPublicPageGlobe
import build.raft.designsystem.components.RaftAvatarAttentionLight
import build.raft.designsystem.components.RaftAvatarBadgeBox
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftSearchTrigger
import build.raft.designsystem.components.RaftTopBar
import build.raft.designsystem.components.RaftTopBarDefaults
import build.raft.designsystem.components.RaftWorkspaceButton
import build.raft.designsystem.components.RaftWorkspaceButtonDefaults
import build.raft.designsystem.components.RaftWorkspaceStrip
import build.raft.designsystem.components.RaftWorkspaceStripEntry
import build.raft.designsystem.theme.RaftTheme

/**
 * The wide layout's top bar (artin task #8; web AppTopBar, raft-design page-structure.md), above
 * the rail and the content:
 * - leading: the current server's [RaftWorkspaceButton], which opens the server panel
 *   ([onWorkspaceClick]; [serverMenuOpen] keeps its press fill), then the workspace strip of
 *   every server's avatar, where a tap on another server switches to it ([onServerPick]);
 * - centre: Back and Forward through what the layout showed ([history], Slack desktop's history
 *   arrows; disabled at either end) just before the search field ([RaftSearchTrigger]), which
 *   opens search ([onSearchClick]). The search stays centred on the bar; the arrows sit 8dp
 *   before it, outside its width ([RaftTopBar]'s `centerLeading`);
 * - trailing: the notification bell ([bell], web TopBarGlobalActions' NotificationTrigger
 *   `top-bar`). The web shows Working now before it; the app has no Working now summary yet.
 *
 * [currentServer] null (the server list hasn't loaded) leaves the workspace out.
 */
@Composable
internal fun RaftAppShellTopBar(
    currentServer: RaftServerSwitcherItem?,
    servers: List<RaftServerSwitcherItem>,
    serverMenuOpen: Boolean,
    onWorkspaceClick: () -> Unit,
    onServerPick: (String) -> Unit,
    searchLabel: String,
    onSearchClick: () -> Unit,
    windowWidth: Dp,
    modifier: Modifier = Modifier,
    topSafeArea: Dp = 0.dp,
    /** The bell at the trailing end; null draws none. */
    bell: RaftAppShellTopBarState? = null,
    /** A tap on the bell, before it toggles (the shell closes the server panel). */
    onBellToggle: () -> Unit = {},
    /** The Back / Forward arrows just before the search field; null draws none. */
    history: RaftAppShellTopBarHistory? = null,
) {
    val unreadDescription = stringResource(Res.string.tab_activity_unread_dot)
    val otherUnreadDescription = stringResource(Res.string.home_server_switcher_other_unread)
    RaftDesignSystemTheme {
        RaftTopBar(
            modifier = modifier,
            topInset = topSafeArea,
            leading = {
                if (currentServer != null) {
                    var stripShown by remember { mutableStateOf(true) }
                    val otherUnread = homeServerSwitcherHasOtherUnread(currentServer.id, servers)
                    RaftWorkspaceButton(
                        name = currentServer.name,
                        onClick = onWorkspaceClick,
                        open = serverMenuOpen,
                        nameMaxWidth =
                            if (stripShown) RaftWorkspaceButtonDefaults.NameMaxWidthBesideStrip else RaftWorkspaceButtonDefaults.NameMaxWidth,
                        avatar = {
                            // With the strip showing, the current server's avatar there carries another
                            // server's unread; without it, the button's avatar does (web AppTopBar).
                            RaftAvatarBadgeBox(
                                size = RaftAvatarSize.Sm,
                                badge = {
                                    if (otherUnread && !stripShown) {
                                        RaftAvatarAttentionLight(
                                            RaftAvatarSize.Sm,
                                            cutout = RaftTheme.colors.layerFrame,
                                            contentDescription = otherUnreadDescription,
                                        )
                                    }
                                },
                            ) {
                                ServerSwitcherAvatarTile(currentServer, size = RaftWorkspaceButtonDefaults.AvatarSize)
                            }
                        },
                        mark =
                            if (currentServer.publicPageLive) {
                                {
                                    RaftPublicPageGlobe(
                                        live = true,
                                        size = HomeServerPublicPageGlobeSize,
                                        color = raftArgbHex(RaftTheme.colors.foregroundOnFrame),
                                    )
                                }
                            } else {
                                null
                            },
                    )
                    val entries =
                        remember(servers) {
                            servers.map { server ->
                                RaftWorkspaceStripEntry(
                                    key = server.id,
                                    name = server.name,
                                    unread = homeServerSwitcherBadgePresentation(server).kind != HomeServerSwitcherBadgeKind.None,
                                )
                            }
                        }
                    val serversById = remember(servers) { servers.associateBy { it.id } }
                    RaftWorkspaceStrip(
                        entries = entries,
                        currentKey = currentServer.id,
                        onPick = { entry -> if (entry.key != currentServer.id) onServerPick(entry.key) },
                        avatar = { entry ->
                            serversById[entry.key]?.let { server -> ServerSwitcherAvatarTile(server, size = RaftAvatarSize.Xs.edge) }
                        },
                        modifier = Modifier.weight(1f),
                        unreadDescription = unreadDescription,
                        otherUnreadDescription = otherUnreadDescription,
                        onShownChange = { stripShown = it },
                    )
                }
            },
            center = { RaftSearchTrigger(label = searchLabel, onClick = onSearchClick, windowWidth = windowWidth) },
            // artin 2026-10-27: the arrows sit just before the search field, outside its width, so
            // the search stays centred; on a short bar they move with it, never over the workspace.
            centerLeading = history?.let { { RaftAppShellTopBarHistoryButtons(it) } },
            leadingMinWidth = if (currentServer != null) RaftWorkspaceButtonDefaults.MinWidth else 0.dp,
            // The theme's pixel art on the bar's end shows from a 1024dp window, as on the web.
            windowWidth = windowWidth,
            trailing = {
                if (bell != null) {
                    RaftAppTopBarNotificationBell(
                        notifications = bell.notifications,
                        open = bell.bellOpen.value,
                        onToggle = {
                            onBellToggle()
                            bell.bellOpen.value = !bell.bellOpen.value
                        },
                    )
                }
            },
        )
    }
}

/**
 * The top bar's history arrows (see [RaftWideNavHistory]): what they can do now and what a click
 * does. Both are on-frame icon buttons (the bar's 36dp items), greyed when there is nothing that way.
 */
internal data class RaftAppShellTopBarHistory(
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val onBack: () -> Unit,
    val onForward: () -> Unit,
)

@Composable
private fun RaftAppShellTopBarHistoryButtons(history: RaftAppShellTopBarHistory) {
    RaftIconButton(
        onClick = history.onBack,
        tooltip = stringResource(Res.string.app_top_bar_history_back),
        variant = RaftButtonVariant.OnFrame,
        enabled = history.canGoBack,
    ) {
        RaftContentLucideIcon(RaftLucideIconName.ChevronLeft, size = RaftAppShellTopBarHistoryGlyph)
    }
    RaftIconButton(
        onClick = history.onForward,
        tooltip = stringResource(Res.string.app_top_bar_history_forward),
        variant = RaftButtonVariant.OnFrame,
        enabled = history.canGoForward,
    ) {
        RaftContentLucideIcon(RaftLucideIconName.ChevronRight, size = RaftAppShellTopBarHistoryGlyph)
    }
}

/** An on-frame icon button's glyph (raft-ui: 20dp in the 36dp frame button). */
private val RaftAppShellTopBarHistoryGlyph = 20.dp

/**
 * Where the server panel under the top bar starts: 8dp (the popover's gap) under the bottom of the
 * workspace button, which ends [RaftTopBarDefaults.ItemInset] above the bar's seam.
 */
internal fun raftAppShellTopBarMenuTopInset(topSafeArea: Dp): Dp =
    topSafeArea + RaftTopBarDefaults.Height - RaftTopBarDefaults.ItemInset

/**
 * The wide layout's top bar, as the pages under it see it (artin task #8, follow-up to #3550). Its
 * presence ([LocalRaftAppShellTopBar] non-null) tells a tab root's list pane that the top bar is
 * above it, so the list has no head of its own (web MainLayout `hideTitle={showTopBar}`).
 *
 * The bell moved from the Home list's head to the top bar: Home publishes its notifications and
 * actions here ([notifications], [onView], [onDismiss]) and keeps [bellOpen] as its notification
 * center's open state, so Home's focus-loss and tab-switch dismissals still close it. The shell
 * draws the bell and its popover.
 */
@Stable
internal class RaftAppShellTopBarState {
    val bellOpen: MutableState<Boolean> = mutableStateOf(false)
    var notifications: List<RaftHomeNotificationItem> by mutableStateOf(emptyList())
    var onView: (RaftHomeNotificationItem) -> Unit by mutableStateOf({})
    var onDismiss: (RaftHomeNotificationItem) -> Unit by mutableStateOf({})

    /**
     * False when the wide layout has no top bar (iPhone Duo's inner screen, raft-design #859): the
     * bell is the rail's, and the list panes keep their own 56dp head with the destination's
     * name, its seam on the rail header's. The server switcher stays the rail's.
     */
    var drawn: Boolean by mutableStateOf(true)
}

/** The wide top bar over the shell's pages; null on the phone (and outside the shell). */
internal val LocalRaftAppShellTopBar = staticCompositionLocalOf<RaftAppShellTopBarState?> { null }

/** True under a drawn wide top bar: a top-level list pane then has no head of its own. */
@Composable
internal fun raftUnderAppShellTopBar(): Boolean = LocalRaftAppShellTopBar.current?.drawn == true
