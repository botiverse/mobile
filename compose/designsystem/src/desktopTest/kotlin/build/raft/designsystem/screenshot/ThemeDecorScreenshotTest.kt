package build.raft.designsystem.screenshot

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
 * decor off (no art). [themeDecorTopBesideActions] follows the raft-ui end-inset rule (#174): the
 * strip moves before wide trailing actions and in by the window controls' end inset, and is
 * skipped when it no longer fits whole after the search field.
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

    /**
     * Bots with decor on, the trailing region holding an avatar stack and two icon buttons
     * (about 141dp in from the end with the bar's 12dp end padding):
     * - 1280dp, no end inset: the strip ends 16dp before the actions (about 157dp from the end);
     * - 1280dp, a 138dp end inset (Windows caption controls): the frame runs under the inset, the
     *   actions move in by it and the strip with them (about 295dp);
     * - 1024dp, no end inset: 157 + 190dp is more than the 332dp after the search field, so no
     *   strip (never a cut one);
     * - 1024dp, a 138dp end inset: no strip either.
     */
    @Test
    fun themeDecorTopBesideActions() =
        listOf(RaftAppearance.Light, RaftAppearance.Dark).forEach { appearance ->
            RaftScreenshot.capture("theme_decor_top_actions_${appearance.name.lowercase()}", appearance, maxWidthDp = 1340, settleFrames = 2) {
                RaftTheme(appearance = appearance, systemDark = false, raftTheme = RaftThemeId.Bots, decor = true) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1280.dp to 0.dp, 1280.dp to 138.dp, 1024.dp to 0.dp, 1024.dp to 138.dp).forEach { (width, inset) ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                RaftText(
                                    "${width.value.toInt()}dp, end inset ${inset.value.toInt()}dp",
                                    style = RaftTheme.typography.monoMeta,
                                    color = RaftTheme.colors.foregroundHint,
                                )
                                TopBar(width = width, endInset = inset) { Actions() }
                            }
                        }
                    }
                }
            }
        }

    @Composable
    private fun RowScope.Actions() {
        RaftAvatarGroup(ringColor = RaftTheme.colors.layerFrame) {
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
    private fun TopBar(
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
    private fun Rail(height: Dp) {
        Box(Modifier.height(height)) {
            // Regular whatever the capture's height: the decor strip, not the compact rail, is under test.
            RaftAppRail(compact = false, footer = { Item("Settings", AppIcon.Settings) }) {
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
