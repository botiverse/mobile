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
