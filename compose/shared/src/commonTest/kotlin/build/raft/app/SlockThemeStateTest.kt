package build.raft.app

import build.raft.app.designtokens.RaftUiPalette
import build.raft.app.designtokens.RaftUiThemeColors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockThemeStateTest {
    // The theme state is process-wide; restore it so this suite cannot leak dark into others.
    @AfterTest
    fun restoreLight() {
        SlockThemeState.apply(SlockAppearance.Light)
        SlockThemeState.onSystemDarkChanged(false)
    }

    @Test
    fun defaultsToRaftLight() {
        assertEquals(SlockAppearance.Light, SlockThemeState.appearance)
        assertEquals(raftLightTheme, SlockThemeState.theme)
        assertEquals(RaftUiThemeColors.RaftLight.foreground, SlockColor.TextBlack)
        assertEquals("#FF141111", SlockColor.TextBlackArgb)
    }

    @Test
    fun darkAppearanceSwitchesThemedTokens() {
        assertTrue(SlockThemeState.apply(SlockAppearance.Dark))
        assertEquals(raftDarkTheme, SlockThemeState.theme)
        assertEquals(RaftUiThemeColors.RaftDark.foreground, SlockColor.TextBlack)
        assertEquals(RaftUiThemeColors.RaftDark.lineStrong, SlockColor.BorderBlack)
        assertEquals(RaftUiThemeColors.RaftDark.layerCanvas, SlockColor.Canvas)
        assertEquals(slockArgbHex(RaftUiThemeColors.RaftDark.foreground), SlockColor.TextBlackArgb)
        // Fixed brand colours do not follow the theme.
        assertEquals(RaftUiPalette.brutalYellow, SlockColor.BrandYellow)
    }

    @Test
    fun systemAppearanceFollowsTheOsSetting() {
        assertFalse(SlockThemeState.apply(SlockAppearance.System), "system light resolves to the same theme")
        assertTrue(SlockThemeState.onSystemDarkChanged(true))
        assertTrue(SlockThemeState.isDark)
        assertTrue(SlockThemeState.apply(SlockAppearance.Light), "an explicit choice overrides the OS")
        assertFalse(SlockThemeState.onSystemDarkChanged(false), "the OS no longer matters under Light")
    }

    @Test
    fun argbHexMatchesTheLiteralFormat() {
        assertEquals("#FFFFFFFF", slockArgbHex(RaftUiPalette.white))
        // Source Yellow is raft-ui's `--primary-400` (#FFD440). 0.6.0 re-derives the legacy
        // `--color-brutal-yellow-400` from a rounded oklch that lands one step off (#FFD441),
        // so the brand literal is checked against the primary token.
        assertEquals(SlockColor.BrandYellowArgb, slockArgbHex(SlockColor.Primary))
    }

}
