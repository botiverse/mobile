package build.raft.designsystem

import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftMessageActionCardStatusTone
import build.raft.designsystem.components.RaftStatusVariant
import build.raft.designsystem.components.RaftStepStatus
import build.raft.designsystem.components.raftActiveStickerLook
import build.raft.designsystem.components.raftBadgeLook
import build.raft.designsystem.components.raftStepLook
import build.raft.designsystem.components.raftSwitchLook
import build.raft.designsystem.components.raftSpinnerLitCell
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftThemeState
import build.raft.designsystem.tokens.RaftThemeId
import build.raft.designsystem.tokens.RaftUiPalette
import build.raft.designsystem.tokens.RaftUiThemeColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class RaftThemeStateTest {
    @Test
    fun defaultsToLight() {
        val state = RaftThemeState()
        state.onSystemDarkChanged(true)
        assertEquals(RaftAppearance.Light, state.appearance)
        assertFalse(state.isDark)
    }

    @Test
    fun resolvesDarkPerAppearanceAndSystemSetting() {
        val cases =
            listOf(
                Triple(RaftAppearance.Light, true, false),
                Triple(RaftAppearance.Dark, false, true),
                Triple(RaftAppearance.System, false, false),
                Triple(RaftAppearance.System, true, true),
            )
        cases.forEach { (appearance, systemDark, expected) ->
            val state = RaftThemeState(appearance).also { it.onSystemDarkChanged(systemDark) }
            assertEquals(expected, state.isDark, "$appearance systemDark=$systemDark")
        }
    }

    @Test
    fun applyReportsResolvedThemeChanges() {
        val state = RaftThemeState(RaftAppearance.Light)
        assertFalse(state.apply(RaftAppearance.Light))
        // The stored choice changes but the resolved theme stays light.
        assertFalse(state.apply(RaftAppearance.System))
        assertEquals(RaftAppearance.System, state.appearance)
        assertTrue(state.onSystemDarkChanged(true))
        assertFalse(state.apply(RaftAppearance.Dark))
        assertTrue(state.apply(RaftAppearance.Light))
    }
}

class RaftSpinnerStepTest {
    @Test
    fun walksClockwise() {
        // step -> lit cell (0 TL, 1 TR, 2 BL, 3 BR): TL, TR, BR, BL, then TL again.
        val expected = listOf(0, 1, 3, 2, 0)
        expected.forEachIndexed { step, cell -> assertEquals(cell, raftSpinnerLitCell(step), "step=$step") }
    }
}

class RaftBadgeLookTest {
    private val light = RaftUiThemeColors.RaftLight
    private val dark = RaftUiThemeColors.RaftDark

    @Test
    fun solidIsAnInkEdgedStickerWithAnOuterRingOnlyInDark() {
        val primary = raftBadgeLook(RaftBadgeAppearance.Solid, RaftBadgeVariant.Primary, enabled = true, colors = light)
        assertEquals(light.brand, primary.fill)
        assertEquals(RaftUiPalette.stickerInk, primary.ink)
        assertEquals(20.dp, primary.height)
        assertEquals(1.5.dp, primary.edgeWidth)
        assertEquals(0f, primary.outerRing.alpha)
        val darkPrimary = raftBadgeLook(RaftBadgeAppearance.Solid, RaftBadgeVariant.Primary, enabled = true, colors = dark)
        assertEquals(dark.stickerEdge, darkPrimary.outerRing)
        assertTrue(darkPrimary.outerRing.alpha > 0f)
    }

    @Test
    fun accentCountIsMonoAndOutlineTakesStrongInk() {
        assertTrue(raftBadgeLook(RaftBadgeAppearance.Solid, RaftBadgeVariant.Accent, true, light).mono)
        val outline = raftBadgeLook(RaftBadgeAppearance.Outline, RaftBadgeVariant.Danger, true, light)
        assertEquals(light.dangerStrong, outline.ink)
        assertEquals(Color.Transparent, outline.fill)
        assertEquals(light.lineHairline, outline.edge)
    }

    /**
     * The App Bot badge (web `AppBotBadge`: soft muted) reads in the sans `text-caption`, whatever its
     * label: only the unread count (solid accent) and outline labels are Space Mono.
     */
    @Test
    fun softBadgesAreNeverMono() {
        RaftBadgeVariant.entries.forEach { variant ->
            assertFalse(raftBadgeLook(RaftBadgeAppearance.Soft, variant, true, light).mono, "soft $variant")
        }
        assertTrue(raftBadgeLook(RaftBadgeAppearance.Outline, RaftBadgeVariant.Muted, true, light).mono)
    }

    @Test
    fun disabledDropsTheInsetEdgeOnFillMuted() {
        val look = raftBadgeLook(RaftBadgeAppearance.Solid, RaftBadgeVariant.Danger, enabled = false, colors = light)
        assertEquals(light.fillMuted, look.fill)
        assertEquals(light.foregroundDisabled, look.ink)
        assertEquals(0.dp, look.edgeWidth)
    }

    @Test
    fun selectedStatesPaintSelectedNotBrand() {
        // raft-ui 0.33 #791: `selected` has its own value (today the same yellow as `brand`).
        for (colors in listOf(light, RaftUiThemeColors.RaftDark)) {
            assertEquals(RaftUiPalette.selected, raftActiveStickerLook(active = true, colors = colors).fill)
            assertEquals(RaftUiPalette.selected, raftSwitchLook(checked = true, enabled = true, colors = colors).track)
            assertEquals(RaftUiPalette.selected, raftStepLook(RaftStepStatus.Process, colors).fill)
        }
        assertEquals(light.brand, RaftUiPalette.selected)
        assertEquals(light.brand, RaftUiPalette.you)
        // A theme that grays the brand (Boring Gray) keeps them yellow.
        assertNotEquals(RaftUiThemeColors.BoringLight.brand, RaftUiPalette.selected)
    }

    /**
     * raft-ui 0.39 (#832): a working agent is yellow in every theme. `presence-busy` is one
     * unthemed colour, and `presence-busy-soft` (the Working chip's tint) changes with the mode
     * only, never with the theme, unlike `brand` / `brand-soft`, which Boring Gray grays.
     */
    @Test
    fun presenceBusyIsTheSameInEveryThemeAndMode() {
        assertEquals(11, RaftThemeId.entries.size)
        for (dark in listOf(false, true)) {
            val raft = RaftUiThemeColors.of(RaftThemeId.Raft, dark)
            // Raft Yellow's own brand values.
            assertEquals(raft.brand, RaftUiPalette.presenceBusy, "dark=$dark")
            assertEquals(raft.brandSoft, raft.presenceBusySoft, "dark=$dark")
            for (theme in RaftThemeId.entries) {
                val colors = RaftUiThemeColors.of(theme, dark)
                assertEquals(raft.presenceBusySoft, colors.presenceBusySoft, "$theme dark=$dark")
            }
        }
        assertNotEquals(RaftUiThemeColors.BoringLight.brand, RaftUiPalette.presenceBusy)
        assertNotEquals(RaftUiThemeColors.BoringLight.brandSoft, RaftUiThemeColors.BoringLight.presenceBusySoft)
        assertNotEquals(RaftUiThemeColors.BoringLight.presenceBusySoft, RaftUiThemeColors.BoringDark.presenceBusySoft)
    }

    /** Badge `busy`: soft, the `presence-busy-soft` tint; solid, a `presence-busy` sticker; outline as `default`. */
    @Test
    fun busyBadgeResolvesToBusyTokensInEveryTheme() {
        for (dark in listOf(false, true)) {
            for (theme in RaftThemeId.entries) {
                val colors = RaftUiThemeColors.of(theme, dark)
                val soft = raftBadgeLook(RaftBadgeAppearance.Soft, RaftBadgeVariant.Busy, true, colors)
                assertEquals(colors.presenceBusySoft, soft.fill, "soft $theme dark=$dark")
                assertEquals(colors.foreground, soft.ink, "soft $theme dark=$dark")
                assertEquals(0.dp, soft.edgeWidth)
                val solid = raftBadgeLook(RaftBadgeAppearance.Solid, RaftBadgeVariant.Busy, true, colors)
                assertEquals(RaftUiPalette.presenceBusy, solid.fill, "solid $theme dark=$dark")
                assertEquals(RaftUiPalette.stickerInk, solid.ink)
                assertEquals(RaftUiPalette.stickerInk, solid.edge)
                assertEquals(
                    raftBadgeLook(RaftBadgeAppearance.Outline, RaftBadgeVariant.Default, true, colors),
                    raftBadgeLook(RaftBadgeAppearance.Outline, RaftBadgeVariant.Busy, true, colors),
                )
            }
        }
    }

    /** Status `primary` and the MessageActionCardStatus primary pixel are `presence-busy` (raft-ui 0.39). */
    @Test
    fun primaryStatusPixelsArePresenceBusy() {
        assertEquals(RaftUiPalette.presenceBusy, RaftStatusVariant.Primary.color)
        assertEquals(RaftUiPalette.presenceBusy, RaftMessageActionCardStatusTone.Primary.color)
    }
}
