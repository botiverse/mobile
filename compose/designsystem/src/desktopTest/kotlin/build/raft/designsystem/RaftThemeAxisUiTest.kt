package build.raft.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.theme.RaftThemeState
import build.raft.designsystem.tokens.RaftThemeId
import build.raft.designsystem.tokens.RaftUiThemeColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [RaftTheme] provides the colours of its raft-ui theme; the default path is unchanged. */
@OptIn(ExperimentalTestApi::class)
class RaftThemeAxisUiTest {
    private class Seen {
        var colors: RaftUiThemeColors? = null
        var themeId: RaftThemeId? = null
        var decor: Boolean? = null
        var surface: Color? = null
    }

    @Test
    fun defaultPathProvidesRaftColours() =
        runComposeUiTest {
            val light = Seen()
            val dark = Seen()
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) { Record(light) }
                RaftTheme(appearance = RaftAppearance.Dark, systemDark = false) { Record(dark) }
            }
            waitForIdle()
            assertSame(RaftUiThemeColors.RaftLight, light.colors)
            assertEquals(RaftThemeId.Raft, light.themeId)
            assertEquals(false, light.decor)
            assertEquals(RaftUiThemeColors.RaftLight.layerCanvas, light.surface)
            assertSame(RaftUiThemeColors.RaftDark, dark.colors)
        }

    @Test
    fun boringLightProvidesBoringLightColours() =
        runComposeUiTest {
            val seen = Seen()
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false, raftTheme = RaftThemeId.Boring) { Record(seen) }
            }
            waitForIdle()
            assertSame(RaftUiThemeColors.BoringLight, seen.colors)
            assertEquals(RaftThemeId.Boring, seen.themeId)
            assertEquals(RaftUiThemeColors.BoringLight.layerCanvas, seen.surface)
        }

    @Test
    fun stateThemeChangesRecompose() =
        runComposeUiTest {
            val state = RaftThemeState(RaftAppearance.Dark)
            val seen = Seen()
            setContent { RaftTheme(state = state, systemDark = false) { Record(seen) } }
            waitForIdle()
            assertSame(RaftUiThemeColors.RaftDark, seen.colors)
            assertFalse(seen.decor ?: true)

            runOnIdle { state.applyRaftTheme(RaftThemeId.Bots, decor = true) }
            waitForIdle()
            assertSame(RaftUiThemeColors.BotsDark, seen.colors)
            assertEquals(RaftThemeId.Bots, seen.themeId)
            assertTrue(seen.decor ?: false)
        }

    @Composable
    private fun Record(seen: Seen) {
        val colors = RaftTheme.colors
        val themeId = RaftTheme.themeId
        val decor = RaftTheme.decor
        val surface = RaftTheme.surfaceColor
        SideEffect {
            seen.colors = colors
            seen.themeId = themeId
            seen.decor = decor
            seen.surface = surface
        }
    }
}
