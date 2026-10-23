package build.raft.designsystem

import build.raft.designsystem.components.RaftSystemBarsAppearance
import build.raft.designsystem.components.RaftWindowDialogProperties
import build.raft.designsystem.components.raftSystemBarsAppearance
import build.raft.designsystem.components.raftWindowDialogPlatformProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftWindowDialogTest {
    @Test
    fun barsFollowTheThemeBothWays() {
        // Light theme: dark icons on both bars; dark theme: light icons. No fixed fallback.
        assertEquals(RaftSystemBarsAppearance(lightStatusBars = true, lightNavigationBars = true), raftSystemBarsAppearance(isDark = false))
        assertEquals(RaftSystemBarsAppearance(lightStatusBars = false, lightNavigationBars = false), raftSystemBarsAppearance(isDark = true))
    }

    @Test
    fun everyWindowDialogFillsTheWindowWithoutTheFrameworkOutsideClickWrapper() {
        for (properties in listOf(
            RaftWindowDialogProperties(),
            RaftWindowDialogProperties(dismissOnBackPress = false),
            RaftWindowDialogProperties(drawScrim = false, contentHandlesKeyboard = true),
        )) {
            val platform = raftWindowDialogPlatformProperties(properties)
            // The content handles a tap on the scrim; on iOS the wrapper made the window one
            // accessibility leaf.
            assertFalse(platform.dismissOnClickOutside)
            assertFalse(platform.usePlatformDefaultWidth)
            assertEquals(properties.dismissOnBackPress, platform.dismissOnBackPress)
        }
    }

    @Test
    fun windowDialogsDrawTheThemeScrimByDefault() {
        assertTrue(RaftWindowDialogProperties().drawScrim)
        assertTrue(RaftWindowDialogProperties().dismissOnBackPress)
        assertFalse(RaftWindowDialogProperties().contentHandlesKeyboard)
    }
}
