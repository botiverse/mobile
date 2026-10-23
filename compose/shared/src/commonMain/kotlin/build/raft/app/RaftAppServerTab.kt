package build.raft.app

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
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
import build.raft.designsystem.components.raftReorderableItem
import build.raft.designsystem.components.rememberRaftReorderState
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

/** The two server menus: the server panel (the server tab's popover) and the Home switcher dropdown. */
internal enum class RaftServerMenuSurface { Panel, HomeSwitcher }

/** Which server menus are open. */
internal data class RaftServerMenusOpen(var panel: Boolean = false, var homeSwitcher: Boolean = false)

/**
 * The server panel and the Home switcher dropdown never show together (artin 10-23: both open
 * at once on a phone): when both are open [now], the one that was already open [before] closes
 * and the one just opened stays. Null when there is nothing to close.
 */
internal fun raftServerMenuToClose(before: RaftServerMenusOpen, now: RaftServerMenusOpen): RaftServerMenuSurface? =
    when {
        !now.panel || !now.homeSwitcher -> null
        // The panel just opened (or both did at once: the panel is the newer surface).
        !before.panel -> RaftServerMenuSurface.HomeSwitcher
        !before.homeSwitcher -> RaftServerMenuSurface.Panel
        // Both were already open (a state from before this rule): keep the panel the person
        // reached for at the bottom.
        else -> RaftServerMenuSurface.HomeSwitcher
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

/** The server panel's width under the wide top bar (web AppTopBar's server menu, `w-64`). */
internal val RaftServerMenuTopBarWidth = 256.dp

/** Where the server popover rises from. */
internal enum class RaftServerMenuAnchor {
    /** Above the phone bar, 8dp from each side. */
    BottomBar,

    /**
     * Under the wide top bar's workspace button, at the top start, 256dp wide (web AppTopBar:
     * the server menu opens 8dp under the button, `w-64`). On a wide screen the button is the
     * one server entry.
     */
    TopBar,
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
    /** The pages in the bottom row ([raftServerMenuEntriesFor]); none draws no row. */
    entries: List<RaftServerMenuEntry> = model.entries,
    /**
     * A held server row dropped in a new place (artin 10-23): the whole order, as the Home
     * switcher's grip sends it ([SharedHomeIntent.ReorderServers]).
     */
    onServerOrderChange: (List<String>) -> Unit = {},
    /**
     * A server switch is pending (web ServerSwitcherMenu `hostedSwitchPending`): the server list
     * sits at 60% and its rows take no tap, hold or drag until the switch lands or fails.
     */
    switchPending: Boolean = false,
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
    // artin 10-23: hold a server row to lift it, drag, let go to save the order (the Home
    // switcher's order and intent); a tap still switches.
    val serversById = model.servers.associateBy { it.id }
    val reorderState = rememberRaftReorderState(model.servers.map { it.id })
    val reorderable = model.servers.size > 1
    val moveUpLabel = stringResource(MR.strings.server_panel_move_up)
    val moveDownLabel = stringResource(MR.strings.server_panel_move_down)
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
                alignment =
                    when (anchor) {
                        RaftServerMenuAnchor.TopBar -> Alignment.TopStart
                        RaftServerMenuAnchor.BottomBar -> Alignment.BottomCenter
                    },
            ) {
                RaftMobileNavServerPopoverContent(
                    modifier =
                        Modifier
                            .then(if (anchor == RaftServerMenuAnchor.TopBar) Modifier.width(RaftServerMenuTopBarWidth) else Modifier)
                            .raftFloatingContentClickTarget(),
                    paneTitle = panelTitle,
                ) {
                    RaftMobileNavServerPopoverList(reorderState = reorderState.takeIf { reorderable }, enabled = !switchPending) {
                        // The rows follow the reorder state's order (the lifted row's slot while
                        // dragging); keyed, so a row keeps its gesture as it moves.
                        reorderState.order.forEach { serverId ->
                            val server = serversById[serverId] ?: return@forEach
                            key(serverId) {
                                val current = server.id == model.currentServer.id
                                RaftMobileNavServerPopoverRow(
                                    modifier =
                                        Modifier.raftReorderableItem(
                                            state = reorderState,
                                            key = server.id,
                                            onOrderChange = onServerOrderChange,
                                            moveUpLabel = moveUpLabel,
                                            moveDownLabel = moveDownLabel,
                                            enabled = reorderable && !switchPending,
                                        ),
                                    enabled = !switchPending,
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
                    if (entries.isNotEmpty()) RaftMobileNavServerPopoverSeparator()
                    if (entries.isNotEmpty()) RaftMobileNavServerPopoverDestinations {
                        entries.forEach { entry ->
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
