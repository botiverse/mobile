package build.raft.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCardVariant
import build.raft.designsystem.components.RaftPresence
import build.raft.designsystem.components.RaftPressSurface
import build.raft.designsystem.components.RaftRowTone
import build.raft.designsystem.components.raftActiveStickerLook
import build.raft.designsystem.components.raftAvatarInitials
import build.raft.designsystem.components.raftDescriptionTermStyle
import build.raft.designsystem.components.raftFirstGrapheme
import build.raft.designsystem.components.raftUnreadCountDescription
import build.raft.designsystem.components.raftBannerFill
import build.raft.designsystem.components.raftButtonLook
import build.raft.designsystem.components.raftButtonMetrics
import build.raft.designsystem.components.raftCardLook
import build.raft.designsystem.components.raftInsetBand
import build.raft.designsystem.components.raftListRowInks
import build.raft.designsystem.components.raftOuterBand
import build.raft.designsystem.components.raftPresenceChipVariant
import build.raft.designsystem.components.raftPressOverlayColor
import build.raft.designsystem.components.raftSkeletonAlphaAt
import build.raft.designsystem.components.raftUnreadCountText
import build.raft.designsystem.theme.RaftFonts
import build.raft.designsystem.theme.RaftTypography
import build.raft.designsystem.tokens.RaftUiPalette
import build.raft.designsystem.tokens.RaftUiThemeColors
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val light = RaftUiThemeColors.RaftLight
private val dark = RaftUiThemeColors.RaftDark

class RaftBandTest {
    @Test
    fun outerBandsGrowTheirCornersLikeASpreadShadow() {
        // Focus ring on a control (radius 8): the 2px gap, then 2px of ink beyond it.
        val gap = raftOuterBand(offset = 0f, width = 2f, radius = 8f)
        assertEquals(1f, gap.outset)
        assertEquals(9f, gap.cornerRadius)
        val ink = raftOuterBand(offset = 2f, width = 2f, radius = 8f)
        assertEquals(3f, ink.outset)
        assertEquals(11f, ink.cornerRadius)
        assertEquals(2f, ink.strokeWidth)
    }

    @Test
    fun squareBoxesKeepSquareRings() {
        assertEquals(0f, raftOuterBand(0f, 1.5f, 0f).cornerRadius)
        assertEquals(0f, raftInsetBand(2f, 0f).cornerRadius)
    }

    @Test
    fun insetBandsSitInsideTheEdge() {
        val band = raftInsetBand(width = 2f, radius = 8f)
        assertEquals(-1f, band.outset)
        assertEquals(7f, band.cornerRadius)
    }
}

class RaftActiveStickerTest {
    @Test
    fun activeIsABrandStickerWithAnInkEdgeAndInkType() {
        val look = raftActiveStickerLook(active = true, colors = light)
        assertEquals(RaftUiPalette.selected, look.fill)
        assertEquals(RaftUiPalette.stickerInk, look.edge)
        assertEquals(RaftUiPalette.stickerInk, look.ink)
        assertEquals(RaftUiPalette.stickerPressed, look.pressOverlay)
        // Same in both modes, plus the die-cut edge in dark only.
        val darkLook = raftActiveStickerLook(active = true, colors = dark)
        assertEquals(look.fill, darkLook.fill)
        assertEquals(0f, look.dieCut.alpha)
        assertTrue(darkLook.dieCut.alpha > 0f)
    }

    @Test
    fun restingKeepsATransparentEdgeAndNoDieCut() {
        val look = raftActiveStickerLook(active = false, colors = dark, restingInk = dark.foregroundMuted)
        assertEquals(Color.Transparent, look.edge)
        assertEquals(Color.Transparent, look.dieCut)
        assertEquals(dark.foregroundMuted, look.ink)
        assertEquals(dark.fillPressed, look.pressOverlay)
    }

    @Test
    fun pressOverlayFollowsTheSurface() {
        assertEquals(light.fillPressed, raftPressOverlayColor(RaftPressSurface.Paper, light))
        assertEquals(RaftUiPalette.stickerPressed, raftPressOverlayColor(RaftPressSurface.Sticker, light))
        assertEquals(light.fillFramePressed, raftPressOverlayColor(RaftPressSurface.Frame, light))
    }
}

class RaftButtonLookTest {
    @Test
    fun colouredButtonsAreStickersWithADieCutEdgeInDark() {
        listOf(
            RaftButtonVariant.Primary to RaftUiPalette.attention400,
            RaftButtonVariant.Warning to RaftUiPalette.warning,
            RaftButtonVariant.Danger to RaftUiPalette.danger,
        ).forEach { (variant, fill) ->
            val look = raftButtonLook(variant, enabled = true, frameless = false, colors = dark)
            assertEquals(fill, look.fill, "$variant")
            assertEquals(RaftUiPalette.stickerInk, look.ink, "$variant")
            assertEquals(dark.stickerEdge, look.outerRing, "$variant")
            assertEquals(RaftUiPalette.stickerPressed, look.pressOverlay, "$variant")
        }
    }

    @Test
    fun disabledIsNoLongerASticker() {
        val look = raftButtonLook(RaftButtonVariant.Primary, enabled = false, frameless = false, colors = dark)
        assertEquals(dark.fillMuted, look.fill)
        assertEquals(dark.lineHairline, look.edge)
        assertEquals(dark.foregroundDisabled, look.ink)
        assertEquals(Color.Transparent, look.outerRing)
        assertEquals(Color.Transparent, look.pressOverlay)
    }

    @Test
    fun framelessDisabledOnlyGreysItsInk() {
        val iconGhost = raftButtonLook(RaftButtonVariant.Ghost, enabled = false, frameless = true, colors = light)
        assertEquals(Color.Transparent, iconGhost.fill)
        assertEquals(Color.Transparent, iconGhost.edge)
        // A ghost text button keeps the shared disabled box.
        val textGhost = raftButtonLook(RaftButtonVariant.Ghost, enabled = false, frameless = false, colors = light)
        assertEquals(light.fillMuted, textGhost.fill)
        val onFrame = raftButtonLook(RaftButtonVariant.OnFrame, enabled = false, frameless = false, colors = light)
        assertEquals(Color.Transparent, onFrame.fill)
        assertEquals(light.foregroundDisabled, onFrame.ink)
    }

    @Test
    fun sizesFollowPurpose() {
        assertEquals(32.dp, raftButtonMetrics(RaftButtonSize.Md, RaftButtonVariant.Default).height)
        assertEquals(36.dp, raftButtonMetrics(RaftButtonSize.Md, RaftButtonVariant.OnFrame).height)
        assertEquals(44.dp, raftButtonMetrics(RaftButtonSize.Lg, RaftButtonVariant.Default).height)
        assertEquals(52.dp, raftButtonMetrics(RaftButtonSize.Xl, RaftButtonVariant.Primary).height)
    }
}

class RaftListRowInksTest {
    @Test
    fun tonesSetTheThreeInks() {
        val unread = raftListRowInks(RaftRowTone.Unread, enabled = true, colors = light)
        assertEquals(FontWeight.Bold, unread.titleWeight)
        assertEquals(light.foreground, unread.ink2)
        assertEquals(light.foregroundMuted, raftListRowInks(RaftRowTone.Muted, true, light).ink)
        val away = raftListRowInks(RaftRowTone.Away, true, light)
        assertEquals(light.foregroundHint, away.ink)
        assertEquals(light.foregroundHint, away.ink2)
    }

    @Test
    fun disabledIgnoresTheTone() {
        val inks = raftListRowInks(RaftRowTone.Unread, enabled = false, colors = light)
        assertEquals(light.foregroundDisabled, inks.ink)
        assertEquals(light.foregroundDisabled, inks.ink2)
        assertEquals(light.foregroundDisabled, inks.ink3)
        assertEquals(FontWeight.SemiBold, inks.titleWeight)
    }
}

class RaftDisplayLooksTest {
    @Test
    fun optionCardTakesTheFieldBorder() {
        val option = raftCardLook(RaftCardVariant.Option, light)
        // raft-ui 0.18 card.recipe `option`: border-[1.5px] border-line-field.
        assertEquals(1.5.dp, option.border)
        assertEquals(light.lineField, option.borderColor)
        assertEquals(8.dp, option.radius)
        val card = raftCardLook(RaftCardVariant.Default, light)
        assertEquals(1.dp, card.border)
        assertEquals(light.lineHairline, card.borderColor)
        assertEquals(10.dp, card.radius)
    }

    @Test
    fun infoBannerIsCyanNeverYellow() {
        assertEquals(light.infoSoft, raftBannerFill(RaftBannerStatus.Info, light))
        assertEquals(dark.dangerSoft, raftBannerFill(RaftBannerStatus.Destructive, dark))
    }

    @Test
    fun countsCapAt99() {
        assertEquals("7", raftUnreadCountText(7))
        assertEquals("99", raftUnreadCountText(99))
        assertEquals("99+", raftUnreadCountText(100))
    }

    @Test
    fun agentStateChipsTakeTheLightsTint() {
        assertEquals(RaftBadgeVariant.Primary, raftPresenceChipVariant(RaftPresence.Busy))
        assertEquals(RaftBadgeVariant.Warning, raftPresenceChipVariant(RaftPresence.Error))
    }

    @Test
    fun initialsFitTheSize() {
        assertEquals("AL", raftAvatarInitials("Ada  Lovelace", RaftAvatarSize.Md))
        assertEquals("A", raftAvatarInitials("ada lovelace", RaftAvatarSize.Sm))
    }

    @Test
    fun initialsNeverSplitACharacter() {
        // A surrogate pair, an emoji with a skin tone, a ZWJ family, a flag, a combining accent.
        assertEquals("\uD83D\uDE00L", raftAvatarInitials("\uD83D\uDE00 Lovelace", RaftAvatarSize.Md))
        assertEquals("\uD83D\uDC4B\uD83C\uDFFD", raftAvatarInitials("\uD83D\uDC4B\uD83C\uDFFDhi", RaftAvatarSize.Sm))
        val family = "\uD83D\uDC69\u200D\uD83D\uDC67"
        assertEquals(family, raftFirstGrapheme("${family}x"))
        assertEquals("\uD83C\uDDEB\uD83C\uDDF7", raftFirstGrapheme("\uD83C\uDDEB\uD83C\uDDF7\uD83C\uDDE9\uD83C\uDDEA"))
        assertEquals("E\u0301", raftAvatarInitials("e\u0301mile", RaftAvatarSize.Sm))
        assertEquals("", raftFirstGrapheme(""))
    }

    @Test
    fun unreadCountReadsTheFullNumber() {
        assertEquals("4 unread", raftUnreadCountDescription(4))
        assertEquals("120 unread", raftUnreadCountDescription(120))
    }

    @Test
    fun descriptionTermIsMediumFootnote() {
        val typography = RaftTypography.from(RaftFonts(FontFamily.SansSerif, FontFamily.Monospace))
        val term = raftDescriptionTermStyle(typography)
        assertEquals(FontWeight.Medium, term.fontWeight)
        assertEquals(typography.footnote.fontSize, term.fontSize)
        assertEquals(typography.footnote.lineHeight, term.lineHeight)
    }

    @Test
    fun skeletonPulsesBetweenFullAndHalf() {
        assertEquals(1f, raftSkeletonAlphaAt(0))
        assertTrue(abs(raftSkeletonAlphaAt(1000) - 0.5f) < 0.001f)
        assertTrue(abs(raftSkeletonAlphaAt(2000) - 1f) < 0.001f)
        assertTrue(raftSkeletonAlphaAt(500) in 0.5f..1f)
    }
}
