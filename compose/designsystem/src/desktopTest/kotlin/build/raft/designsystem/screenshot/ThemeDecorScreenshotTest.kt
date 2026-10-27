package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.designsystem.components.RaftAppRail
import build.raft.designsystem.components.RaftAppRailDefaults
import build.raft.designsystem.components.RaftAppRailItem
import build.raft.designsystem.components.RaftSearchTrigger
import build.raft.designsystem.components.RaftTopBar
import build.raft.designsystem.components.RaftWorkspaceButton
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftThemeId
import build.raft.designsystem.tokens.RaftUiPalette
import kotlin.test.Test

/**
 * Theme decor (raft-ui `frame-decor-top` / `frame-decor-rail`): the top bar at a 1024dp window
 * with its 190x48 strip 72dp from the end, and the rail with its 48x112 strip above the footer,
 * in Bots, Weather and Spooky (the last two repaint for dark mode) with decor on, then Bots with
 * decor off (no art).
 */
class ThemeDecorScreenshotTest {
    private val cases =
        listOf(
            RaftThemeId.Bots to true,
            RaftThemeId.Weather to true,
            RaftThemeId.Spooky to true,
            RaftThemeId.Bots to false,
        )

    @Test
    fun themeDecorTop() =
        listOf(RaftAppearance.Light, RaftAppearance.Dark).forEach { appearance ->
            RaftScreenshot.capture("theme_decor_top_${appearance.name.lowercase()}", appearance) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cases.forEach { (theme, decor) ->
                        RaftTheme(appearance = appearance, systemDark = false, raftTheme = theme, decor = decor) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Label(theme, decor)
                                TopBar()
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun themeDecorRail() =
        listOf(RaftAppearance.Light, RaftAppearance.Dark).forEach { appearance ->
            RaftScreenshot.capture("theme_decor_rail_${appearance.name.lowercase()}", appearance) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    cases.forEach { (theme, decor) ->
                        RaftTheme(appearance = appearance, systemDark = false, raftTheme = theme, decor = decor) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Label(theme, decor)
                                Rail(height = 360.dp)
                            }
                        }
                    }
                    // A short rail: the strip is cut from the top, never drawn under an item.
                    RaftTheme(appearance = appearance, systemDark = false, raftTheme = RaftThemeId.Bots, decor = true) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Label(RaftThemeId.Bots, decor = true)
                            Rail(height = 300.dp)
                        }
                    }
                }
            }
        }

    @Composable
    private fun Label(theme: RaftThemeId, decor: Boolean) {
        RaftText(
            "${theme.wire} decor ${if (decor) "on" else "off"}",
            style = RaftTheme.typography.monoMeta,
            color = RaftTheme.colors.foregroundHint,
        )
    }

    @Composable
    private fun TopBar() {
        RaftTopBar(
            modifier = Modifier.width(1024.dp),
            windowWidth = 1024.dp,
            leading = {
                RaftWorkspaceButton(
                    name = "Botiverse",
                    onClick = {},
                    avatar = {
                        Box(
                            Modifier.size(24.dp).background(RaftUiPalette.stickerInk, RoundedCornerShape(RaftRadius.Sticker)),
                            contentAlignment = Alignment.Center,
                        ) {
                            RaftText(
                                "B",
                                style = RaftTheme.typography.caption.copy(fontSize = 13.sp, lineHeight = 13.sp),
                                color = RaftUiPalette.selected,
                            )
                        }
                    },
                )
            },
            center = { RaftSearchTrigger(label = "Search", onClick = {}, windowWidth = 1024.dp) },
            trailing = {
                Box(
                    Modifier.size(36.dp).background(RaftTheme.colors.fillFrameMedium, RoundedCornerShape(RaftRadius.Control)),
                )
            },
        )
    }

    @Composable
    private fun Rail(height: Dp) {
        Box(Modifier.height(height)) {
            RaftAppRail(footer = { Item("Settings", AppIcon.Settings) }) {
                Item("Home", AppIcon.House, selected = true)
                Item("Activity", AppIcon.Activity)
                Item("Tasks", AppIcon.SquareCheckBig)
                Item("Members", AppIcon.Users)
            }
        }
    }

    @Composable
    private fun Item(label: String, icon: AppIcon, selected: Boolean = false) {
        RaftAppRailItem(
            label = label,
            selected = selected,
            onClick = {},
            icon = { ink -> AppIconImage(icon, RaftAppRailDefaults.IconSize, ink) },
        )
    }
}
