package build.raft.designsystem.vcase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarGroup
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftSearchTrigger
import build.raft.designsystem.components.RaftTopBar
import build.raft.designsystem.components.RaftWorkspaceButton
import build.raft.designsystem.screenshot.AppIcon
import build.raft.designsystem.screenshot.AppIconImage
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftThemeId
import build.raft.designsystem.tokens.RaftUiPalette
import dev.vcase.VCase

// Theme decor (raft-ui `frame-decor-top` / `frame-decor-rail`): the top bar at a 1024dp window
// with its 190x48 strip 72dp from the end, and the rail with its 48x112 strip above the footer,
// in Bots, Weather and Spooky (the last two repaint for dark mode) with decor on, then Bots with
// decor off (no art). Each theme nests its own RaftTheme with the case's appearance.
// These replace ThemeDecorScreenshotTest (theme_decor_top, theme_decor_top_actions and
// theme_decor_rail, each _light/_dark).

private val decorThemes =
    listOf(
        RaftThemeId.Bots to true,
        RaftThemeId.Weather to true,
        RaftThemeId.Spooky to true,
        RaftThemeId.Bots to false,
    )

/** The top bar's strip in each theme. */
@VCase("ds.theme-decor.top")
@Composable
fun ThemeDecorTop() {
    val appearance = caseAppearance
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        decorThemes.forEach { (theme, decor) ->
            RaftTheme(appearance = appearance, systemDark = false, raftTheme = theme, decor = decor) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DecorLabel(theme, decor)
                    DecorTopBar()
                }
            }
        }
    }
}

/** The rail's strip in each theme, then a short rail: the strip is cut from the top, never drawn under an item. */
@VCase("ds.theme-decor.rail")
@Composable
fun ThemeDecorRail() {
    val appearance = caseAppearance
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        decorThemes.forEach { (theme, decor) ->
            RaftTheme(appearance = appearance, systemDark = false, raftTheme = theme, decor = decor) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DecorLabel(theme, decor)
                    DecorRail(height = 360.dp)
                }
            }
        }
        RaftTheme(appearance = appearance, systemDark = false, raftTheme = RaftThemeId.Bots, decor = true) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DecorLabel(RaftThemeId.Bots, decor = true)
                DecorRail(height = 300.dp)
            }
        }
    }
}

@Composable
private fun RowScope.DecorActions() {
    RaftAvatarGroup {
        RaftAvatar(RaftAvatarType.Human, RaftAvatarSize.Sm, initials = "A")
        RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Sm)
    }
    RaftIconButton(onClick = {}, tooltip = "New Message", variant = RaftButtonVariant.OnFrame) {
        RaftGlyphIcon(RaftGlyph.Plus, size = 20.dp)
    }
    RaftIconButton(onClick = {}, tooltip = "Search", variant = RaftButtonVariant.OnFrame) {
        RaftGlyphIcon(RaftGlyph.Search, size = 20.dp)
    }
}

@Composable
private fun DecorLabel(theme: RaftThemeId, decor: Boolean) {
    RaftText(
        "${theme.wire} decor ${if (decor) "on" else "off"}",
        style = RaftTheme.typography.monoMeta,
        color = RaftTheme.colors.foregroundHint,
    )
}

@Composable
private fun DecorTopBar(
    width: Dp = 1024.dp,
    endInset: Dp = 0.dp,
    trailing: @Composable RowScope.() -> Unit = {
        Box(
            Modifier.size(36.dp).background(RaftTheme.colors.fillFrameMedium, RoundedCornerShape(RaftRadius.Control)),
        )
    },
) {
    RaftTopBar(
        modifier = Modifier.width(width),
        windowWidth = width,
        endInset = endInset,
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
        center = { RaftSearchTrigger(label = "Search", onClick = {}, windowWidth = width) },
        trailing = trailing,
    )
}

@Composable
private fun DecorRail(height: Dp) {
    Box(Modifier.height(height)) {
        // Regular whatever the capture's height: the decor strip, not the compact rail, is under test.
        RaftAppRail(compact = false, footer = { DecorRailItem("Settings", AppIcon.Settings) }) {
            DecorRailItem("Home", AppIcon.House, selected = true)
            DecorRailItem("Activity", AppIcon.Activity)
            DecorRailItem("Tasks", AppIcon.SquareCheckBig)
            DecorRailItem("Members", AppIcon.Users)
        }
    }
}

@Composable
private fun DecorRailItem(label: String, icon: AppIcon, selected: Boolean = false) {
    RaftAppRailItem(
        label = label,
        selected = selected,
        onClick = {},
        icon = { ink -> AppIconImage(icon, RaftAppRailDefaults.IconSize, ink) },
    )
}
