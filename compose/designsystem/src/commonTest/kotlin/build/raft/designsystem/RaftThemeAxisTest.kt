package build.raft.designsystem

import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftThemeState
import build.raft.designsystem.tokens.RaftThemeId
import build.raft.designsystem.tokens.RaftUiThemeColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The theme axis: raft-ui theme (the Web's `data-theme`) times mode. */
class RaftThemeAxisTest {
    private val expected: Map<RaftThemeId, Pair<RaftUiThemeColors, RaftUiThemeColors>> =
        mapOf(
            RaftThemeId.Raft to (RaftUiThemeColors.RaftLight to RaftUiThemeColors.RaftDark),
            RaftThemeId.Boring to (RaftUiThemeColors.BoringLight to RaftUiThemeColors.BoringDark),
            RaftThemeId.Bots to (RaftUiThemeColors.BotsLight to RaftUiThemeColors.BotsDark),
            RaftThemeId.Critters to (RaftUiThemeColors.CrittersLight to RaftUiThemeColors.CrittersDark),
            RaftThemeId.Space to (RaftUiThemeColors.SpaceLight to RaftUiThemeColors.SpaceDark),
            RaftThemeId.Desk to (RaftUiThemeColors.DeskLight to RaftUiThemeColors.DeskDark),
            RaftThemeId.Food to (RaftUiThemeColors.FoodLight to RaftUiThemeColors.FoodDark),
            RaftThemeId.Quest to (RaftUiThemeColors.QuestLight to RaftUiThemeColors.QuestDark),
            RaftThemeId.Weather to (RaftUiThemeColors.WeatherLight to RaftUiThemeColors.WeatherDark),
            RaftThemeId.Plants to (RaftUiThemeColors.PlantsLight to RaftUiThemeColors.PlantsDark),
            RaftThemeId.Spooky to (RaftUiThemeColors.SpookyLight to RaftUiThemeColors.SpookyDark),
        )

    @Test
    fun ofPicksTheVariantOfEveryThemeAndMode() {
        assertEquals(RaftThemeId.entries.toSet(), expected.keys)
        expected.forEach { (theme, variants) ->
            val (light, dark) = variants
            assertSame(light, RaftUiThemeColors.of(theme, dark = false), "$theme light")
            assertSame(dark, RaftUiThemeColors.of(theme, dark = true), "$theme dark")
            assertTrue(light is RaftUiThemeColors.Light, "$theme light is a light variant")
            assertTrue(dark is RaftUiThemeColors.Dark, "$theme dark is a dark variant")
        }
    }

    @Test
    fun stateDefaultsToRaftWithoutDecor() {
        val state = RaftThemeState()
        assertEquals(RaftThemeId.Raft, state.raftTheme)
        assertFalse(state.decor)
        assertSame(RaftUiThemeColors.RaftLight, state.colors)
        state.apply(RaftAppearance.Dark)
        assertSame(RaftUiThemeColors.RaftDark, state.colors)
        assertSame(RaftThemeId.Raft, RaftThemeState.App.raftTheme)
    }

    @Test
    fun stateColorsFollowThemeAndMode() {
        val state = RaftThemeState(RaftAppearance.System, raftTheme = RaftThemeId.Boring)
        assertSame(RaftUiThemeColors.BoringLight, state.colors)
        state.onSystemDarkChanged(true)
        assertSame(RaftUiThemeColors.BoringDark, state.colors)
        state.applyRaftTheme(RaftThemeId.Spooky)
        assertSame(RaftUiThemeColors.SpookyDark, state.colors)
    }

    @Test
    fun applyRaftThemeReportsChanges() {
        val state = RaftThemeState()
        assertFalse(state.applyRaftTheme(RaftThemeId.Raft))
        assertTrue(state.applyRaftTheme(RaftThemeId.Bots))
        assertEquals(RaftThemeId.Bots, state.raftTheme)
        assertTrue(state.applyRaftTheme(RaftThemeId.Bots, decor = true))
        assertTrue(state.decor)
        // Decor is kept when only the theme changes.
        assertTrue(state.applyRaftTheme(RaftThemeId.Food))
        assertTrue(state.decor)
        assertFalse(state.applyRaftTheme(RaftThemeId.Food, decor = true))
    }

    @Test
    fun fromWireFallsBackToRaft() {
        RaftThemeId.entries.forEach { assertEquals(it, RaftThemeId.fromWire(it.wire)) }
        assertEquals(RaftThemeId.Raft, RaftThemeId.fromWire(null))
        assertEquals(RaftThemeId.Raft, RaftThemeId.fromWire("not-a-theme"))
    }
}
