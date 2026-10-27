package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * The theme axis: the top bar on the frame and a row of the tokens themes override (frame,
 * frame active, sidebar, canvas, card, brand) next to `selected`, which no theme changes, in
 * Raft (the default), Boring Gray and Bots, one block per theme on that theme's canvas.
 */
class ThemeScreenshotTest {
    private val themes = listOf(RaftThemeId.Raft, RaftThemeId.Boring, RaftThemeId.Bots)

    @Test
    fun themeFrames() =
        listOf(RaftAppearance.Light, RaftAppearance.Dark).forEach { appearance ->
            RaftScreenshot.capture("theme_frames_${appearance.name.lowercase()}", appearance) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    themes.forEach { theme ->
                        RaftTheme(appearance = appearance, systemDark = false, raftTheme = theme) { ThemeBlock(theme) }
                    }
                }
            }
        }

    @Composable
    private fun ThemeBlock(theme: RaftThemeId) {
        val colors = RaftTheme.colors
        Column(
            Modifier.background(colors.layerCanvas).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RaftText(theme.wire, style = RaftTheme.typography.monoMeta, color = colors.foregroundHint)
            RaftTopBar(
                modifier = Modifier.width(560.dp),
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
                center = { RaftSearchTrigger(label = "Search", onClick = {}, windowWidth = 560.dp) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Swatch("frame", colors.layerFrame)
                Swatch("frame-active", colors.layerFrameActive)
                Swatch("sidebar", colors.layerSidebar)
                Swatch("canvas", colors.layerCanvas)
                Swatch("card", colors.layerCard)
                Swatch("brand", colors.brand)
                Swatch("selected", RaftUiPalette.selected)
            }
        }
    }

    @Composable
    private fun Swatch(label: String, color: Color) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(width = 64.dp, height = 28.dp)
                    .background(color, RoundedCornerShape(RaftRadius.Control))
                    .border(1.dp, RaftTheme.colors.lineHairline, RoundedCornerShape(RaftRadius.Control)),
            )
            RaftText(label, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundMuted)
        }
    }
}
