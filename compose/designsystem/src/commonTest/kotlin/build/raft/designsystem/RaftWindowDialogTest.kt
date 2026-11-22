package build.raft.designsystem

import build.raft.designsystem.components.RaftSystemBarsAppearance
import build.raft.designsystem.components.RaftWindowDialogProperties
import build.raft.designsystem.components.raftSystemBarsAppearance
import build.raft.designsystem.components.raftWantsDarkSystemBarIcons
import build.raft.designsystem.tokens.RaftThemeId
import build.raft.designsystem.tokens.RaftUiThemeColors
import build.raft.designsystem.components.raftWindowDialogPlatformProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftWindowDialogTest {
    @Test
    fun theStatusBarIconsFollowTheFrameLuminanceNotTheMode() {
        // Raft Yellow and Bots light blue are light frames: dark icons.
        assertTrue(raftWantsDarkSystemBarIcons(RaftUiThemeColors.RaftLight.layerFrame))
        assertTrue(raftWantsDarkSystemBarIcons(RaftUiThemeColors.BotsLight.layerFrame))
        // Space and Quest put a dark frame on light mode: light icons.
        assertFalse(raftWantsDarkSystemBarIcons(RaftUiThemeColors.SpaceLight.layerFrame))
        assertFalse(raftWantsDarkSystemBarIcons(RaftUiThemeColors.QuestLight.layerFrame))
        // Every dark frame: light icons.
        for (theme in RaftThemeId.entries) {
            assertFalse(raftWantsDarkSystemBarIcons(RaftUiThemeColors.of(theme, dark = true).layerFrame), "$theme dark frame")
        }
        assertEquals(
            RaftSystemBarsAppearance(lightStatusBars = false, lightNavigationBars = true),
            raftSystemBarsAppearance(
                statusBarBackdrop = RaftUiThemeColors.SpaceLight.layerFrame,
                navigationBarBackdrop = RaftUiThemeColors.SpaceLight.layerCanvas,
            ),
        )
        assertEquals(
            RaftSystemBarsAppearance(lightStatusBars = true, lightNavigationBars = true),
            raftSystemBarsAppearance(
                statusBarBackdrop = RaftUiThemeColors.BotsLight.layerFrame,
                navigationBarBackdrop = RaftUiThemeColors.BotsLight.layerCanvas,
            ),
        )
        assertEquals(
            RaftSystemBarsAppearance(lightStatusBars = false, lightNavigationBars = false),
            raftSystemBarsAppearance(
                statusBarBackdrop = RaftUiThemeColors.BotsDark.layerFrame,
                navigationBarBackdrop = RaftUiThemeColors.BotsDark.layerCanvas,
            ),
        )
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
}
