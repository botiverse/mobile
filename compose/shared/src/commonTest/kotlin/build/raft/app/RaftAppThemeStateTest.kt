package build.raft.app

import build.raft.app.designtokens.RaftUiPalette
import build.raft.app.designtokens.RaftUiThemeColors
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
    fun systemAppearanceFollowsTheOsSetting() {
        assertFalse(RaftAppThemeState.apply(RaftAppAppearance.System), "system light resolves to the same theme")
        assertTrue(RaftAppThemeState.onSystemDarkChanged(true))
        assertTrue(RaftAppThemeState.isDark)
        assertTrue(RaftAppThemeState.apply(RaftAppAppearance.Light), "an explicit choice overrides the OS")
        assertFalse(RaftAppThemeState.onSystemDarkChanged(false), "the OS no longer matters under Light")
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
}
