package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftPublicPageGlobe
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftAvatarAttentionLight
import build.raft.designsystem.components.RaftAvatarBadgeBox
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftSearchTrigger
import build.raft.designsystem.components.RaftTopBar
import build.raft.designsystem.components.RaftTopBarDefaults
import build.raft.designsystem.components.RaftWorkspaceButton
import build.raft.designsystem.components.RaftWorkspaceButtonDefaults
import build.raft.designsystem.components.RaftWorkspaceStrip
import build.raft.designsystem.components.RaftWorkspaceStripEntry
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource

/**
 * The wide layout's top bar (artin task #8; web AppTopBar, raft-design page-structure.md), above
 * the rail and the content:
 * - leading: the current server's [RaftWorkspaceButton], which opens the server panel
 *   ([onWorkspaceClick]; [serverMenuOpen] keeps its press fill), then the workspace strip of
 *   every server's avatar, where a tap on another server switches to it ([onServerPick]);
 * - centre: the search field ([RaftSearchTrigger]), which opens search ([onSearchClick]);
 * - trailing: empty. The web shows Working now and the bell there; the app has no Working now
 *   summary yet, and Activity is a rail entry.
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
) {
    val unreadDescription = stringResource(MR.strings.tab_activity_unread_dot)
    val otherUnreadDescription = stringResource(MR.strings.home_server_switcher_other_unread)
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
        )
    }
}

/**
 * Where the server panel under the top bar starts: 8dp (the popover's gap) under the bottom of the
 * workspace button, which ends [RaftTopBarDefaults.ItemInset] above the bar's seam.
 */
internal fun raftAppShellTopBarMenuTopInset(topSafeArea: Dp): Dp =
    topSafeArea + RaftTopBarDefaults.Height - RaftTopBarDefaults.ItemInset
