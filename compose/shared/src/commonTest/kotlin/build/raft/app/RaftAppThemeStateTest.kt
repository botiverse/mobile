package build.raft.app

import androidx.compose.ui.graphics.toArgb
import build.raft.app.data.RaftAppAppearanceRuntime
import build.raft.app.designtokens.RaftUiPalette
import build.raft.app.designtokens.RaftUiThemeColors
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftThemeState
import build.raft.designsystem.tokens.RaftThemeId
import build.raft.designsystem.tokens.RaftUiThemeColors as DesignSystemThemeColors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftAppThemeStateTest {
    // The theme state is process-wide; restore it so this suite cannot leak dark into others.
    @AfterTest
    fun restoreLight() {
        RaftAppThemeState.apply(RaftAppAppearance.Light)
        RaftAppThemeState.onSystemDarkChanged(false)
        RaftAppThemeState.setRaftTheme(RaftThemeId.Raft, decor = false)
    }

    @Test
    fun lightAppearanceResolvesRaftLight() {
        RaftAppThemeState.apply(RaftAppAppearance.Light)
        assertEquals(RaftAppAppearance.Light, RaftAppThemeState.appearance)
        assertEquals(raftLightTheme, RaftAppThemeState.theme)
        assertEquals(RaftUiThemeColors.RaftLight.foreground, RaftColor.TextBlack)
        assertEquals("#FF141111", RaftColor.TextBlackArgb)
    }

    @Test
    fun darkAppearanceSwitchesThemedTokens() {
        assertTrue(RaftAppThemeState.apply(RaftAppAppearance.Dark))
        assertEquals(raftDarkTheme, RaftAppThemeState.theme)
        assertEquals(RaftUiThemeColors.RaftDark.foreground, RaftColor.TextBlack)
        assertEquals(RaftUiThemeColors.RaftDark.lineStrong, RaftColor.BorderBlack)
        assertEquals(RaftUiThemeColors.RaftDark.layerCanvas, RaftColor.Canvas)
        assertEquals(raftArgbHex(RaftUiThemeColors.RaftDark.foreground), RaftColor.TextBlackArgb)
        // Fixed brand colours do not follow the theme.
        assertEquals(RaftUiPalette.paletteYellow, RaftColor.BrandYellow)
        assertEquals(RaftUiPalette.stickerInk, RaftColor.StickerInk)
        // The legacy light-only names follow it too, so screens built on them go dark.
        assertEquals(RaftUiThemeColors.RaftDark.layerCanvas, RaftColor.SurfaceWhite)
        assertEquals(RaftUiThemeColors.RaftDark.layerSidebar, RaftColor.SurfaceCream)
        assertEquals(RaftUiThemeColors.RaftDark.foregroundMuted, RaftColor.TextMutedGray)
        assertEquals(RaftUiThemeColors.RaftDark.fillStrong, RaftColor.EmptyStateGray)
        assertEquals(RaftUiThemeColors.RaftDark.infoSoft, RaftColor.AppBgLightBlue)
    }

    @Test
    fun legacyLightTokensKeepTheirLightValues() {
        RaftAppThemeState.apply(RaftAppAppearance.Light)
        assertEquals(RaftUiPalette.white, RaftColor.SurfaceWhite)
        assertEquals(RaftUiPalette.paletteCream, RaftColor.SurfaceCream)
        assertEquals(RaftUiPalette.neutral500, RaftColor.TextMutedGray)
        assertEquals("#FFFFFFFF", RaftColor.SurfaceWhiteArgb)
        assertEquals("#FF737373", RaftColor.TextMutedGrayArgb)
    }

    @Test
    fun appStateIsTheDesignSystemAppState() {
        RaftAppThemeState.apply(RaftAppAppearance.Dark)
        assertEquals(RaftAppearance.Dark, RaftThemeState.App.appearance)
        assertTrue(RaftThemeState.App.isDark)
    }

    @Test
    fun systemAppearanceFollowsTheOsSetting() {
        assertFalse(RaftAppThemeState.apply(RaftAppAppearance.System), "system light resolves to the same theme")
        assertTrue(RaftAppThemeState.onSystemDarkChanged(true))
        assertTrue(RaftAppThemeState.isDark)
        assertTrue(RaftAppThemeState.apply(RaftAppAppearance.Light), "an explicit choice overrides the OS")
        assertFalse(RaftAppThemeState.onSystemDarkChanged(false), "the OS no longer matters under Light")
    }

    @Test
    fun argbHexMatchesTheLiteralFormat() {
        assertEquals("#FFFFFFFF", raftArgbHex(RaftUiPalette.white))
        // Source Yellow is raft-ui's `--brand-400` (#FFD440). raft-ui re-derives the
        // `--palette-yellow-400` ramp from a rounded oklch that lands one step off (#FFD441),
        // so the brand literal is checked against the brand token.
        assertEquals(RaftColor.BrandYellowArgb, raftArgbHex(RaftColor.Brand))
    }

    @Test
    fun defaultThemeIsRaftWithoutDecor() {
        assertEquals(RaftThemeId.Raft, RaftAppThemeState.raftTheme)
        assertFalse(RaftAppThemeState.decor)
        assertEquals(RaftUiThemeColors.RaftLight, RaftAppThemeState.colors)
        assertFalse(RaftAppThemeState.setRaftTheme(RaftThemeId.Raft), "setting the current theme changes nothing")
    }

    @Test
    fun serverThemeRepaintsAppTokensAndTheDesignSystemState() {
        assertTrue(RaftAppThemeState.setRaftTheme(RaftThemeId.Boring))
        assertEquals(RaftThemeId.Boring, RaftThemeState.App.raftTheme)
        assertEquals(RaftUiThemeColors.BoringLight, RaftAppThemeState.colors)
        assertEquals(RaftUiThemeColors.BoringLight.layerFrame, RaftColor.Frame)
        assertEquals(RaftUiThemeColors.BoringLight.brand, RaftColor.Brand)
        assertEquals(RaftUiThemeColors.BoringLight.layerFrame, RaftAppThemeState.theme.accent)
        // Selected and You keep their yellow in every theme (raft-ui #791).
        assertEquals(RaftUiPalette.selected, RaftColor.Selected)
        assertEquals(RaftUiPalette.you, RaftColor.You)

        RaftAppThemeState.apply(RaftAppAppearance.Dark)
        assertEquals(RaftUiThemeColors.BoringDark, RaftAppThemeState.colors)
        assertEquals(RaftUiThemeColors.BoringDark.layerCanvas, RaftAppThemeState.theme.surface)
        assertEquals(RaftUiThemeColors.BoringDark.layerCanvas, RaftColor.Canvas)
    }

    @Test
    fun setRaftThemeReportsDecorChanges() {
        assertTrue(RaftAppThemeState.setRaftTheme(RaftThemeId.Bots, decor = true))
        assertTrue(RaftAppThemeState.decor)
        assertTrue(RaftThemeState.App.decor)
        // Decor stays on when only the theme changes.
        assertTrue(RaftAppThemeState.setRaftTheme(RaftThemeId.Space))
        assertTrue(RaftAppThemeState.decor)
        assertTrue(RaftAppThemeState.setRaftTheme(RaftThemeId.Space, decor = false))
        assertFalse(RaftAppThemeState.setRaftTheme(RaftThemeId.Space, decor = false))
    }

    @Test
    fun frameArgbFollowsTheTheme() {
        assertEquals(RaftUiThemeColors.RaftLight.layerFrame.toArgb(), RaftAppAppearanceRuntime.frameArgb)
        RaftAppThemeState.setRaftTheme(RaftThemeId.Bots)
        assertEquals(RaftUiThemeColors.BotsLight.layerFrame.toArgb(), RaftAppAppearanceRuntime.frameArgb)
        RaftAppThemeState.apply(RaftAppAppearance.Dark)
        assertEquals(RaftUiThemeColors.BotsDark.layerFrame.toArgb(), RaftAppAppearanceRuntime.frameArgb)
        // The canvas the hosts paint today follows the theme too (Bots dark has its own canvas).
        assertEquals(RaftUiThemeColors.BotsDark.layerCanvas.toArgb(), RaftAppAppearanceRuntime.canvasArgb)
    }

    @Test
    fun appAndDesignSystemTokensAgreeForEveryTheme() {
        RaftThemeId.entries.forEach { theme ->
            assertEquals(theme.wire, theme.toAppThemeId().wire)
            listOf(false, true).forEach { dark ->
                val app = RaftUiThemeColors.of(theme.toAppThemeId(), dark)
                val designSystem = DesignSystemThemeColors.of(theme, dark)
                assertEquals(designSystem.layerFrame, app.layerFrame, "$theme dark=$dark layerFrame")
                assertEquals(designSystem.layerCanvas, app.layerCanvas, "$theme dark=$dark layerCanvas")
                assertEquals(designSystem.brand, app.brand, "$theme dark=$dark brand")
            }
        }
    }

    @Test
    fun raftAppThemeOfTheDefaultColoursIsTheDefaultTheme() {
        assertEquals(raftLightTheme, raftAppThemeOf(RaftUiThemeColors.RaftLight))
        assertEquals(raftDarkTheme, raftAppThemeOf(RaftUiThemeColors.RaftDark))
    }
}
