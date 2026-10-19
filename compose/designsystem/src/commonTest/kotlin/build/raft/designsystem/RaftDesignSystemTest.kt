package build.raft.designsystem

import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.raftBadgeLook
import build.raft.designsystem.components.raftSpinnerLitCell
import build.raft.designsystem.components.raftSpinnerStepAt
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftThemeState
import build.raft.designsystem.tokens.RaftUiPalette
import build.raft.designsystem.tokens.RaftUiThemeColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class RaftThemeStateTest {
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
            assertEquals(expected, RaftThemeState(appearance).isDark(systemDark), "$appearance systemDark=$systemDark")
        }
    }

    @Test
    fun applyReportsOnlyRealChanges() {
        val state = RaftThemeState(RaftAppearance.Light)
        assertFalse(state.apply(RaftAppearance.Light))
        assertTrue(state.apply(RaftAppearance.Dark))
        assertEquals(RaftAppearance.Dark, state.appearance)
    }
}

class RaftSpinnerStepTest {
    @Test
    fun walksClockwiseInQuarterSecondSteps() {
        // elapsed ms -> lit cell (0 TL, 1 TR, 2 BL, 3 BR): TL, TR, BR, BL, then TL again.
        val expected = mapOf(0L to 0, 249L to 0, 250L to 1, 500L to 3, 750L to 2, 999L to 2, 1000L to 0, 1250L to 1)
        expected.forEach { (elapsed, cell) ->
            assertEquals(cell, raftSpinnerLitCell(raftSpinnerStepAt(elapsed)), "elapsed=$elapsed")
        }
    }
}

class RaftBadgeLookTest {
    private val light = RaftUiThemeColors.RaftLight
    private val dark = RaftUiThemeColors.RaftDark

    @Test
    fun solidIsAnInkEdgedStickerWithAnOuterRingOnlyInDark() {
        val primary = raftBadgeLook(RaftBadgeAppearance.Solid, RaftBadgeVariant.Primary, enabled = true, colors = light)
        assertEquals(RaftUiPalette.brand, primary.fill)
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

    @Test
    fun disabledDropsTheInsetEdgeOnFillMuted() {
        val look = raftBadgeLook(RaftBadgeAppearance.Solid, RaftBadgeVariant.Danger, enabled = false, colors = light)
        assertEquals(light.fillMuted, look.fill)
        assertEquals(light.foregroundDisabled, look.ink)
        assertEquals(0.dp, look.edgeWidth)
    }
}
