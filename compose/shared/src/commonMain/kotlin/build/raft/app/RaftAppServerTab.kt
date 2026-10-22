package build.raft.app

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftMobileNavDefaults
import build.raft.designsystem.components.RaftMobileNavServerPopover
import build.raft.designsystem.components.RaftMobileNavServerPopoverContent
import build.raft.designsystem.components.RaftMobileNavServerPopoverDestination
import build.raft.designsystem.components.RaftMobileNavServerPopoverDestinations
import build.raft.designsystem.components.RaftMobileNavServerPopoverGroup
import build.raft.designsystem.components.RaftMobileNavServerPopoverItem
import build.raft.designsystem.components.RaftMobileNavServerPopoverList
import build.raft.designsystem.components.RaftMobileNavServerPopoverRow
import build.raft.designsystem.components.RaftMobileNavServerPopoverSeparator
import build.raft.designsystem.components.RaftMobileNavServerPopoverState
import build.raft.designsystem.components.RaftMutedCount
import build.raft.designsystem.components.RaftUnreadCount
import com.tencent.tmm.kmmresource.compose.stringResource

// Design D1a (#proj-raft-ui task #152; raft-design #712): the app's current-server tab and its
// server popover (the "server panel"), built from the design system's RaftMobileNavServerPopover*
// parts with the app's server avatars, icons and words. The entry and the popover are separate
// composables so a host can place the entry elsewhere (the wide rail's foot today; maybe a
// floating button on iOS later) and open the same popover.

/** Keys of the popover's rows in [RaftMobileNavServerPopoverState] (a held long press lets go over one). */
internal fun raftServerMenuServerKey(serverId: String): String = "server:$serverId"

internal fun raftServerMenuEntryKey(entry: RaftServerMenuEntry): String = "page:${entry.key}"

/** What a held long press was let go over: a server id or a page, or nothing. */
internal sealed interface RaftServerMenuPick {
    data class Server(val serverId: String) : RaftServerMenuPick

    data class Page(val entry: RaftServerMenuEntry) : RaftServerMenuPick
}

internal fun raftServerMenuPick(key: String): RaftServerMenuPick? =
    when {
        key.startsWith("server:") -> key.removePrefix("server:").takeIf { it.isNotBlank() }?.let(RaftServerMenuPick::Server)
        key.startsWith("page:") -> RaftServerMenuEntry.fromKey(key.removePrefix("page:"))?.let(RaftServerMenuPick::Page)
        else -> null
    }

/**
 * The current-server entry: the phone bar's last tab, or the wide rail's foot ([compact]).
 * A press or a 500ms hold opens [state]'s popover; letting a held press go over a row picks it
 * ([onPick]).
 */
@Composable
internal fun RaftAppServerTab(
    model: RaftServerMenuModel,
    state: RaftMobileNavServerPopoverState,
    onPick: (RaftServerMenuPick) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    requestFocus: Boolean = false,
    onFocusRequested: () -> Unit = {},
) {
    val server = model.currentServer
    // Back from a page the server panel opened puts focus back on the tab (Kirby 10-22).
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(requestFocus) {
        if (!requestFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
        onFocusRequested()
    }
    val attention = appShellServerTabShowsAttention(model)
    val attentionDescription =
        if (attention) stringResource(MR.strings.home_server_switcher_other_unread) else null
    val label = stringResource(MR.strings.server_tab_a11y_label, server.name)
    val longPressLabel = stringResource(MR.strings.server_tab_switch_action)
    RaftDesignSystemTheme {
        RaftMobileNavServerPopoverItem(
            label = server.name,
            state = state,
            avatar = { size -> ServerSwitcherAvatarTile(server, size = size) },
            modifier = modifier,
            attention = attention,
            attentionDescription = attentionDescription,
            contentDescription = label,
            longPressLabel = longPressLabel,
            onHoldRelease = { key -> raftServerMenuPick(key)?.let(onPick) },
            compact = compact,
            focusRequester = focusRequester,
        )
    }
}

/** Where the server popover rises from. */
internal enum class RaftServerMenuAnchor {
    /** Above the phone bar, 8dp from each side. */
    BottomBar,

    /** Beside the wide rail's foot, against its start edge. */
    RailFoot,
}

/**
 * The server popover — the server panel (服务器面板) — over the shell content (raft-design #712,
 * compact): [model]'s servers (name, `/slug` under it, unread or the current check), scrolling
 * inside; then Join Community, Switch or Create Server and, for owners and admins, Invite
 * Human; and at the very bottom, nearest the thumb (stdrc 10-22), the server's pages (Members,
 * Computers, Settings) as one row of destinations, each with its own dot. At most 60% of [screenHeight], below [topInset]. It closes on a tap outside,
 * on Back ([RaftBackHandler]) and after a pick. [bottomInset] is space under the content area
 * the popover must clear (the iOS system bar floats over it).
 */
@Composable
internal fun RaftAppServerMenuOverlay(
    model: RaftServerMenuModel,
    state: RaftMobileNavServerPopoverState,
    anchor: RaftServerMenuAnchor,
    onPick: (RaftServerMenuPick) -> Unit,
    prefersChineseCommunity: Boolean,
    onCreateServerClick: () -> Unit,
    onInviteHumanClick: (() -> Unit)?,
    bottomInset: Dp = 0.dp,
    topInset: Dp = 0.dp,
    screenHeight: Dp? = null,
) {
    if (!state.open) return
    // The overlay host registers Back as well; the popover names its own handler so Back closes
    // it before it moves the page, whatever the host does.
    RaftBackHandler(enabled = true, label = "server_menu", onBack = state::dismiss)
    val labels = RaftHomeShellLabels.localized()
    val panelTitle = stringResource(MR.strings.server_panel_title)
    // raft-design #712: Join Community (while not joined), Switch or Create Server, Invite Human.
    val joinCommunityActions =
        homeServerSwitcherJoinCommunityActions(
            servers = model.servers,
            labels = labels,
            includeChineseCommunity = prefersChineseCommunity,
        )
    val attentionDescription = stringResource(MR.strings.more_row_attention_dot)
    val pageLabels =
        mapOf(
            RaftServerMenuEntry.Members to stringResource(MR.strings.tab_members),
            RaftServerMenuEntry.Computers to stringResource(MR.strings.settings_computers_title),
            RaftServerMenuEntry.Settings to stringResource(MR.strings.tab_settings),
        )
    RaftFloatingOverlayHost(
        onDismiss = state::dismiss,
        dismissActivationDelayMillis = RaftFloatingOverlayClickTriggeredDismissDelayMillis,
    ) { _ ->
        RaftDesignSystemTheme {
            RaftMobileNavServerPopover(
                state = state,
                bottomInset = bottomInset,
                topInset = topInset,
                screenHeight = screenHeight,
                alignment = if (anchor == RaftServerMenuAnchor.RailFoot) Alignment.BottomStart else Alignment.BottomCenter,
            ) {
                RaftMobileNavServerPopoverContent(
                    modifier = Modifier.raftFloatingContentClickTarget(),
                    paneTitle = panelTitle,
                ) {
                    RaftMobileNavServerPopoverList {
                        model.servers.forEach { server ->
                            val current = server.id == model.currentServer.id
                            RaftMobileNavServerPopoverRow(
                                label = server.name,
                                description = server.slug.takeIf { it.isNotBlank() }?.let { "/$it" },
                                onClick = { onPick(RaftServerMenuPick.Server(server.id)) },
                                current = current,
                                state = state,
                                key = raftServerMenuServerKey(server.id),
                                leading = {
                                    ServerSwitcherAvatarTile(server, size = RaftMobileNavDefaults.ServerPopoverAvatarSize)
                                },
                                trailing =
                                    if (current) {
                                        { RaftGlyphIcon(RaftGlyph.Check, color = RaftColor.TextBlack) }
                                    } else {
                                        raftServerMenuUnreadCount(server)?.let { (count, muted) ->
                                            { if (muted) RaftMutedCount(count) else RaftUnreadCount(count) }
                                        }
                                    },
                            )
                        }
                    }
                    RaftMobileNavServerPopoverSeparator()
                    RaftMobileNavServerPopoverGroup {
                        // raft-design #712: Join Community (while not joined), Switch or Create
                        // Server, Invite Human (owners and admins) — the Home switcher's footer.
                        joinCommunityActions.forEach { action ->
                            RaftMobileNavServerPopoverRow(
                                label = action.label,
                                onClick = {
                                    state.dismiss()
                                    onCreateServerClick()
                                },
                                leading = { RaftLucideIcon(RaftLucideIconName.Globe, Modifier.size(16.dp)) },
                            )
                        }
                        RaftMobileNavServerPopoverRow(
                            label = labels.switchOrCreateServer,
                            onClick = {
                                state.dismiss()
                                onCreateServerClick()
                            },
                            leading = { RaftLucideIcon(RaftLucideIconName.Plus, Modifier.size(16.dp)) },
                        )
                        onInviteHumanClick?.let { invite ->
                            RaftMobileNavServerPopoverRow(
                                label = labels.inviteHuman,
                                onClick = {
                                    state.dismiss()
                                    invite()
                                },
                                leading = { RaftLucideIcon(RaftLucideIconName.UserPlus, Modifier.size(16.dp)) },
                            )
                        }
                    }
                    RaftMobileNavServerPopoverSeparator()
                    RaftMobileNavServerPopoverDestinations {
                        model.entries.forEach { entry ->
                            RaftMobileNavServerPopoverDestination(
                                label = pageLabels.getValue(entry),
                                onClick = { onPick(RaftServerMenuPick.Page(entry)) },
                                icon = {
                                    RaftLucideIcon(
                                        name = raftServerMenuEntryIcon(entry),
                                        color = raftArgbHex(RaftColor.ForegroundMuted),
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                attention = entry in model.attentionEntries,
                                attentionDescription = attentionDescription,
                                state = state,
                                key = raftServerMenuEntryKey(entry),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A server's unread count in the popover and whether it is muted: the number the Home switcher
 * shows (exact Activity unread, else the broader fallback), or null for none.
 */
internal fun raftServerMenuUnreadCount(server: RaftServerSwitcherItem): Pair<Int, Boolean>? {
    val presentation = homeServerSwitcherBadgePresentation(server)
    if (presentation.kind == HomeServerSwitcherBadgeKind.None) return null
    val count =
        when (presentation.kind) {
            HomeServerSwitcherBadgeKind.ActivityNumber -> server.activityUnreadCount?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
            else -> server.broaderUnreadCount
        }
    return if (count > 0) count to presentation.muted else null
}

/** The popover's page icons: the ones their old More rows wore. */
internal fun raftServerMenuEntryIcon(entry: RaftServerMenuEntry): RaftLucideIconName =
    when (entry) {
        RaftServerMenuEntry.Members -> RaftLucideIconName.Users
        RaftServerMenuEntry.Computers -> RaftLucideIconName.Monitor
        RaftServerMenuEntry.Settings -> RaftLucideIconName.Settings
    }
