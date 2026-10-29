package build.raft.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerType
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.RaftPressSurface
import build.raft.designsystem.components.raftHoverFadeMillis
import build.raft.designsystem.components.raftMediumOverlayColor
import build.raft.designsystem.components.raftPointerTypeHovers
import build.raft.designsystem.components.raftPressOverlayColor
import build.raft.designsystem.components.raftStateOverlayColors
import build.raft.designsystem.components.raftStateOverlayTarget
import build.raft.designsystem.tokens.RaftUiPalette
import build.raft.designsystem.tokens.RaftUiThemeColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftStateOverlayTest {
    private val light = RaftUiThemeColors.RaftLight
    private val dark = RaftUiThemeColors.RaftDark

    @Test
    fun theMediumTierFollowsTheSurface() {
        assertEquals(light.fillMedium, raftMediumOverlayColor(RaftPressSurface.Paper, light))
        assertEquals(RaftUiPalette.stickerMedium, raftMediumOverlayColor(RaftPressSurface.Sticker, light))
        assertEquals(light.fillFrameMedium, raftMediumOverlayColor(RaftPressSurface.Frame, light))
        assertEquals(RaftUiPalette.stickerInkMedium, raftMediumOverlayColor(RaftPressSurface.InkSticker, dark))
        // The press overlays of old are the same colours.
        RaftPressSurface.entries.forEach { surface ->
            assertEquals(raftPressOverlayColor(surface, light), raftMediumOverlayColor(surface, light), surface.name)
            assertEquals(raftPressOverlayColor(surface, dark), raftMediumOverlayColor(surface, dark), surface.name)
        }
    }

    @Test
    fun eachTierHoversWithItsTokenAndPressesWithTheMediumTier() {
        val none = raftStateOverlayColors(RaftHoverTier.None, RaftPressSurface.Paper, light)
        assertEquals(Color.Transparent, none.hover)
        assertEquals(light.fillMedium, none.press)

        val subtle = raftStateOverlayColors(RaftHoverTier.Subtle, RaftPressSurface.Paper, dark)
        assertEquals(dark.fillSubtle, subtle.hover)
        assertEquals(dark.fillMedium, subtle.press)

        val medium = raftStateOverlayColors(RaftHoverTier.Medium, RaftPressSurface.Frame, light)
        assertEquals(light.fillFrameMedium, medium.hover)
        assertEquals(light.fillFrameMedium, medium.press)

        val sticker = raftStateOverlayColors(RaftHoverTier.Medium, RaftPressSurface.Sticker, dark)
        assertEquals(RaftUiPalette.stickerMedium, sticker.hover)

        val highlight = raftStateOverlayColors(RaftHoverTier.Highlight, RaftPressSurface.Paper, light)
        assertEquals(light.brandSoft, highlight.hover)
        assertEquals(light.brandSoft, highlight.press)

        val media = raftStateOverlayColors(RaftHoverTier.Media, RaftPressSurface.Paper, light)
        assertEquals(light.ink8, media.hover)
        assertEquals(light.ink8, media.press)
    }

    /** List cells: in light the subtle and medium tiers; in dark a step past them (rows only). */
    @Test
    fun cellsUseTheCellTokens() {
        val lightCell = raftStateOverlayColors(RaftHoverTier.Cell, RaftPressSurface.Paper, light)
        assertEquals(light.fillCellHover, lightCell.hover)
        assertEquals(light.fillCellPress, lightCell.press)
        assertEquals(light.fillSubtle, lightCell.hover)
        assertEquals(light.fillMedium, lightCell.press)

        val darkCell = raftStateOverlayColors(RaftHoverTier.Cell, RaftPressSurface.Paper, dark)
        assertEquals(dark.fillCellHover, darkCell.hover)
        assertEquals(dark.fillCellPress, darkCell.press)
        assertTrue(darkCell.hover.alpha > dark.fillSubtle.alpha, "dark cell hover steps past fill-subtle")
        assertTrue(darkCell.press.alpha > dark.fillMedium.alpha, "dark cell press steps past fill-medium")
    }

    @Test
    fun pressWinsThenHoverThenTheHighlight() {
        val overlay = raftStateOverlayColors(RaftHoverTier.Subtle, RaftPressSurface.Paper, light)
        fun target(pressed: Boolean = false, hovered: Boolean = false, highlighted: Boolean = false) =
            raftStateOverlayTarget(overlay, RaftHoverTier.Subtle, pressed = pressed, hovered = hovered, highlighted = highlighted)
        assertEquals(Color.Transparent, target())
        assertEquals(overlay.hover, target(hovered = true))
        assertEquals(overlay.hover, target(highlighted = true))
        assertEquals(overlay.press, target(pressed = true, hovered = true))
        assertEquals(overlay.press, target(pressed = true, highlighted = true))
    }

    @Test
    fun disabledAnswersNeitherHoverNorPress() {
        RaftHoverTier.entries.forEach { tier ->
            val overlay = raftStateOverlayColors(tier, RaftPressSurface.Paper, light)
            assertEquals(
                Color.Transparent,
                raftStateOverlayTarget(overlay, tier, pressed = true, hovered = true, enabled = false, highlighted = true),
                tier.name,
            )
        }
    }

    /** Selected items don't hover; a selected sticker still presses, a selected cell doesn't. */
    @Test
    fun selectedDropsHover() {
        val sticker = raftStateOverlayColors(RaftHoverTier.Medium, RaftPressSurface.Sticker, light)
        assertEquals(Color.Transparent, raftStateOverlayTarget(sticker, RaftHoverTier.Medium, pressed = false, hovered = true, selected = true))
        assertEquals(Color.Transparent, raftStateOverlayTarget(sticker, RaftHoverTier.Medium, pressed = false, hovered = false, selected = true, highlighted = true))
        assertEquals(sticker.press, raftStateOverlayTarget(sticker, RaftHoverTier.Medium, pressed = true, hovered = true, selected = true))

        val cell = raftStateOverlayColors(RaftHoverTier.Cell, RaftPressSurface.Paper, dark)
        assertEquals(Color.Transparent, raftStateOverlayTarget(cell, RaftHoverTier.Cell, pressed = true, hovered = true, selected = true, highlighted = true))
    }

    @Test
    fun fadesFollowRaftUiDurations() {
        assertEquals(100, raftHoverFadeMillis(RaftHoverTier.Medium))
        assertEquals(100, raftHoverFadeMillis(RaftHoverTier.Subtle))
        assertEquals(100, raftHoverFadeMillis(RaftHoverTier.Highlight))
        assertEquals(150, raftHoverFadeMillis(RaftHoverTier.Cell))
        assertEquals(75, raftHoverFadeMillis(RaftHoverTier.Media))
    }

    /** CSS `@media (hover: hover)`: a mouse, a trackpad or a pen hovers; a finger never does. */
    @Test
    fun onlyPointersThatHoverHover() {
        assertTrue(raftPointerTypeHovers(PointerType.Mouse))
        assertTrue(raftPointerTypeHovers(PointerType.Stylus))
        assertTrue(raftPointerTypeHovers(PointerType.Eraser))
        assertFalse(raftPointerTypeHovers(PointerType.Touch))
        assertFalse(raftPointerTypeHovers(PointerType.Unknown))
    }
}
