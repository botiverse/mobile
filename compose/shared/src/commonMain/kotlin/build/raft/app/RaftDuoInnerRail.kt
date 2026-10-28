package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftAppRailDefaults
import build.raft.designsystem.components.RaftAppRailItem
import build.raft.designsystem.components.RaftAvatarAttentionLight
import build.raft.designsystem.components.RaftAvatarBadgeBox
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

// iPhone Duo's inner screen is the apps' desktop mode without the top bar (raft-design #859,
// page-structure "Foldable phones" > "The inner screen"): the rail takes what the top bar held.
// The rail header's workspace tile is the server switcher, Search is a rail destination and the
// bell sits in the rail's footer (raft-ui AppRail without the top bar).

/**
 * The wide layout drops its top bar for the rail's header and footer: on iPhone Duo's inner screen
 * (the side-column posture) while the rail shows. Off Duo (iPad, Android tablets, the Compose
 * desktop app) the top bar stays.
 */
@Composable
internal fun raftAppRailWithoutTopBar(navRailActive: Boolean): Boolean =
    navRailActive && LocalRaftSideColumnPosture.current.sideColumn

/**
 * The rail header's workspace tile (raft-ui AppRail `AppRailHeader` with the docs' app-owned
 * server button): the current server's 40dp avatar tile, ink with its initial in yellow when it
 * has no image. It is the one server entry and opens the server panel ([onClick]). The attention
 * light shows when another server has unread items, the server tab's rule
 * ([appShellServerTabShowsAttention]). The tile is an ink sticker: while the panel is [open], and
 * under a press, it lays `sticker-ink-medium` (6% paper) over its whole face, image avatars too
 * (raft-design #875, States "Medium on an ink sticker"; Kirby 10-28).
 */
@Composable
internal fun RaftAppRailServerTile(
    model: RaftServerMenuModel,
    open: Boolean,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val server = model.currentServer
    val attention = appShellServerTabShowsAttention(model)
    val label = stringResource(Res.string.server_tab_a11y_label, server.name)
    val actionLabel = stringResource(Res.string.server_tab_switch_action)
    val otherUnreadDescription = stringResource(Res.string.home_server_switcher_other_unread)
    RaftAvatarBadgeBox(
        size = RaftAvatarSize.Md,
        modifier =
            Modifier
                .size(RaftAppRailDefaults.ItemSize)
                .clickable(
                    interactionSource = source,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = actionLabel,
                    onClick = onClick,
                )
                .semantics {
                    contentDescription = label
                    if (attention) stateDescription = otherUnreadDescription
                },
        badge = {
            if (attention) {
                RaftAvatarAttentionLight(RaftAvatarSize.Md, cutout = RaftTheme.colors.layerFrame)
            }
        },
    ) {
        ServerSwitcherAvatarTile(server, size = RaftAppRailDefaults.ItemSize)
        if (open || pressed) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(RaftAppRailServerTileOpenFill, RoundedCornerShape(RaftRadius.Sticker))
            )
        }
    }
}

/**
 * The bell in the rail's footer (raft-ui AppRail: notifications before help and settings when
 * there is no top bar): a rail item with the attention pixel while there are notifications. It
 * toggles the same notification popover as the top bar's bell ([RaftAppShellTopBarState]).
 */
@Composable
internal fun RaftAppRailNotificationBell(
    state: RaftAppShellTopBarState,
    onToggle: () -> Unit,
) {
    val visible = remember(state.notifications) { state.notifications.visibleHomeNotifications() }
    val attentionDescription =
        when (visible.size) {
            0 -> null
            1 -> Res.string.home_notification_center_item_count_one.format(listOf(1)).localized()
            else -> Res.string.home_notification_center_item_count_many.format(listOf(visible.size)).localized()
        }
    RaftAppRailItem(
        label = stringResource(Res.string.home_notification_center_title),
        selected = false,
        onClick = {
            onToggle()
            state.bellOpen.value = !state.bellOpen.value
        },
        icon = { ink ->
            RaftLucideIcon(
                name = RaftLucideIconName.Bell,
                color = raftArgbHex(ink),
                modifier = Modifier.size(RaftAppRailDefaults.IconSize),
            )
        },
        attention = visible.isNotEmpty(),
        attentionDescription = attentionDescription,
    )
}

/** The rail's width with its seam: the rail's popovers open past it, 8dp (the popover gap) beyond. */
internal val RaftAppRailOuterWidth = RaftAppRailDefaults.Width + RaftStroke.Hairline

/** A popover's gap from the rail's seam and from the window's edge (raft-ui `sideOffset` 8). */
internal val RaftAppRailPopoverGap = 8.dp

/**
 * The rail's destinations without the top bar: Search first (raft-ui AppRail without the top bar),
 * then [items]. While Search is open ([searchOpen], the shell's inline Search over the current tab)
 * it is the selected one and nothing else is.
 */
internal fun appShellRailItemsWithSearch(
    items: List<RaftBottomTabItemPayload>,
    searchLabel: String,
    searchOpen: Boolean,
): List<RaftBottomTabItemPayload> =
    listOf(
        RaftBottomTabItemPayload(
            key = AppShellRailSearchKey,
            label = searchLabel,
            iconName = RaftLucideIconName.Search,
            selected = searchOpen,
        )
    ) + items.map { if (searchOpen) it.copy(selected = false) else it }

// raft-ui --sticker-ink-medium (raft-design #875); generated token after the 0.48 bump
private val RaftAppRailServerTileOpenFill = Color.White.copy(alpha = 0.06f) // raft-color: sticker-ink-medium is paper 6% in both themes (raft-design #875)
