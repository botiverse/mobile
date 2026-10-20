package build.raft.app

import build.raft.app.designtokens.RaftUiPalette
import build.raft.app.designtokens.RaftUiThemeColors
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftThemeState
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

}
