package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftTooltipPreview
import build.raft.designsystem.components.RaftAppRail
import build.raft.designsystem.components.RaftAppRailDefaults
import build.raft.designsystem.components.RaftAppRailFooterItem
import build.raft.designsystem.components.RaftAppRailItem
import build.raft.designsystem.components.RaftAppRailToggleItem
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftUiPalette
import kotlin.test.Test

/**
 * raft-ui AppRail (icons only, the product's): the rail with its 56dp header holding the server
 * tile, Home selected (the sticker on the frame), Activity with the attention pixel, and the
 * footer pinned to the bottom; next to it a rail with no header (a bar above holds the server).
 * Then the web LeftRail behaviours: the items' tooltips beside the rail, the footer's tools with
 * the sidebar toggle on and off, and the compact rail of a short window.
 *
 * Every rail passes `compact` explicitly: the capture's window is the content's size, which
 * would otherwise make every rail under 600dp compact.
 */
class AppRailScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    private fun capture(name: String, settleFrames: Int = 0, content: @Composable () -> Unit) =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("${name}_${appearance.name.lowercase()}", appearance, settleFrames = settleFrames) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) { content() }
            }
        }

    @Test
    fun appRail() =
        capture("app_rail") {
            Rail(withHeader = true)
            Rail(withHeader = false)
        }

    /**
     * Tooltips (web LeftRail `RailTabButton`, `side="right"`): a destination's name beside the
     * rail, centred on it, its caret toward the item; the sidebar toggle's with its shortcut
     * (`Kbd`). Opened through [LocalRaftTooltipPreview] (no pointer offscreen); the room right of
     * the rail holds them.
     */
    @Test
    fun appRailTooltip() =
        capture("app_rail_tooltip", settleFrames = 2) {
            Row {
                Box(Modifier.height(400.dp)) {
                    RaftAppRail(
                        compact = false,
                        footer = {
                            CompositionLocalProvider(LocalRaftTooltipPreview provides true) {
                                Toggle(checked = true)
                            }
                            Item("Settings", AppIcon.Settings)
                        },
                    ) {
                        Item("Home", AppIcon.House, selected = true)
                        CompositionLocalProvider(LocalRaftTooltipPreview provides true) {
                            Item("Activity", AppIcon.Activity)
                        }
                        Item("Tasks", AppIcon.SquareCheckBig)
                    }
                }
                Box(Modifier.width(200.dp))
            }
        }

    /**
     * The footer's tools as a list (web: Collapse sidebar, Help, Settings): the toggle's icon
     * follows its state (PanelLeftClose while the sidebar shows, PanelLeftOpen while hidden) and
     * never takes the sticker; Settings selected, Help with the attention pixel.
     */
    @Test
    fun appRailFooterToggle() =
        capture("app_rail_footer_toggle") {
            listOf(true, false).forEach { sidebarShown ->
                Box(Modifier.height(360.dp)) {
                    RaftAppRail(
                        compact = false,
                        footerItems =
                            listOf(
                                RaftAppRailFooterItem.Toggle(
                                    key = "sidebar",
                                    label = if (sidebarShown) "Collapse sidebar" else "Expand sidebar",
                                    checked = sidebarShown,
                                    onCheckedChange = {},
                                    icon = { ink, shown -> AppIconImage(if (shown) AppIcon.PanelLeftClose else AppIcon.PanelLeftOpen, RaftAppRailDefaults.IconSize, ink) },
                                    shortcutLabel = "⌘\\",
                                ),
                                RaftAppRailFooterItem.Action(
                                    key = "help",
                                    label = "Help",
                                    onClick = {},
                                    icon = { ink -> AppIconImage(AppIcon.CircleHelp, RaftAppRailDefaults.IconSize, ink) },
                                    attention = true,
                                    attentionDescription = "new",
                                    showTooltip = false,
                                ),
                                RaftAppRailFooterItem.Action(
                                    key = "settings",
                                    label = "Settings",
                                    onClick = {},
                                    icon = { ink -> AppIconImage(AppIcon.Settings, RaftAppRailDefaults.IconSize, ink) },
                                    selected = !sidebarShown,
                                ),
                            ),
                    ) {
                        Item("Home", AppIcon.House, selected = sidebarShown)
                        Item("Activity", AppIcon.Activity)
                        Item("Tasks", AppIcon.SquareCheckBig)
                    }
                }
            }
        }

    /**
     * A window at most 600dp tall (raft-ui `[@media(max-height:600px)]`): the rail narrows to
     * 48dp, its header to 48dp, its items to 36dp (icons stay 20dp, 6dp apart), 6dp from the
     * ends; next to it the regular rail at the same height.
     */
    @Test
    fun appRailCompact() =
        capture("app_rail_compact") {
            Rail(withHeader = true, compact = true, height = 440.dp)
            Rail(withHeader = false, compact = true, height = 440.dp)
            Rail(withHeader = true, compact = false, height = 440.dp)
        }

    @Composable
    private fun Rail(withHeader: Boolean, compact: Boolean = false, height: Dp = 520.dp) {
        Box(Modifier.height(height)) {
            RaftAppRail(
                compact = compact,
                header =
                    if (withHeader) {
                        {
                            Box(
                                Modifier
                                    .size(RaftAppRailDefaults.metrics.itemSize)
                                    .background(RaftUiPalette.stickerInk, RoundedCornerShape(RaftRadius.Control)),
                                contentAlignment = Alignment.Center,
                            ) { RaftText("B", style = RaftTheme.typography.callout, color = RaftUiPalette.selected) }
                        }
                    } else {
                        null
                    },
                footer = {
                    Item("Settings", AppIcon.Settings)
                },
            ) {
                Item("Home", AppIcon.House, selected = true)
                Item("Activity", AppIcon.Activity, attention = true)
                Item("Tasks", AppIcon.SquareCheckBig)
                Item("Members", AppIcon.Users)
                Item("Computers", AppIcon.Monitor, enabled = false)
            }
        }
    }

    @Composable
    private fun Toggle(checked: Boolean) {
        RaftAppRailToggleItem(
            label = if (checked) "Collapse sidebar" else "Expand sidebar",
            checked = checked,
            onCheckedChange = {},
            icon = { ink, shown -> AppIconImage(if (shown) AppIcon.PanelLeftClose else AppIcon.PanelLeftOpen, RaftAppRailDefaults.IconSize, ink) },
            shortcutLabel = "⌘\\",
        )
    }

    @Composable
    private fun Item(label: String, icon: AppIcon, selected: Boolean = false, attention: Boolean = false, enabled: Boolean = true) {
        RaftAppRailItem(
            label = label,
            selected = selected,
            onClick = {},
            icon = { ink -> AppIconImage(icon, RaftAppRailDefaults.IconSize, ink) },
            enabled = enabled,
            attention = attention,
            attentionDescription = "new",
        )
    }
}
