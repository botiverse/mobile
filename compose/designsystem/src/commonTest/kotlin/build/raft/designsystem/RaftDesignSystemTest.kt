package build.raft.designsystem

import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftThemeState
import kotlin.test.Test
import kotlin.test.assertEquals

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
            val state = RaftThemeState(appearance).also { it.onSystemDarkChanged(systemDark) }
            assertEquals(expected, state.isDark, "$appearance systemDark=$systemDark")
        }
    }
}
