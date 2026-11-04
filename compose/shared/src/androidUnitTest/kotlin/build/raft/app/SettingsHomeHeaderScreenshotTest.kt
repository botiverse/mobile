package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.LocalRaftStatusBarBackdrop
import build.raft.designsystem.components.RaftStatusBarBackdrop
import build.raft.designsystem.components.raftWantsDarkSystemBarIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone Settings page head, light and dark: the web's PanelHeader on a pushed server page,
 * the canvas (white in light, the dark canvas in dark) with the seam under it, Back and the
 * 17/22 headline, the same head as every Settings sub-page. It pads for a 24dp status bar and
 * claims that strip in the canvas, so the bar's icons are dark on white and light in dark mode.
 * A 390dp-wide phone; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h640dp-xxhdpi")
class SettingsHomeHeaderScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun settingsHomeLight() = captureAndCheckStatusBar("settings.home", RaftAppAppearance.Light, darkIcons = true)

    @Test
    fun settingsHomeDark() = captureAndCheckStatusBar("settings.home.dark", RaftAppAppearance.Dark, darkIcons = false)

    private fun captureAndCheckStatusBar(name: String, appearance: RaftAppAppearance, darkIcons: Boolean) {
        var backdropRef: RaftStatusBarBackdrop? = null
        var canvas: Color? = null
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    // xxhdpi: 72px is a 24dp status bar.
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("statusBarHeight", 72) }) }
                }
            val backdrop = remember { RaftStatusBarBackdrop().also { backdropRef = it } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager), LocalRaftStatusBarBackdrop provides backdrop) {
                RaftDesignSystemTheme { SettingsHomeFixture() }
            }
            SideEffect { canvas = RaftColor.Canvas }
        }
        // Read after the capture has settled: the head's claim is live while its page is.
        val statusBar = assertNotNull(backdropRef?.color, "the Settings head claims the status bar")
        assertEquals(canvas, statusBar)
        assertEquals(darkIcons, raftWantsDarkSystemBarIcons(statusBar))
    }
}

@Composable
private fun SettingsHomeFixture() {
    SettingsHomeContent(
        payload =
            RaftSettingsHomePayload(
                title = "Settings",
                items =
                    listOf(
                        SettingsHomePageLinkPayload("account", "Account", iconName = RaftLucideIconName.User, value = "Ada", section = "Personal"),
                        SettingsHomePageLinkPayload("appearance", "Appearance", iconName = RaftLucideIconName.Type, section = "Personal"),
                        SettingsHomePageLinkPayload("notifications", "Notifications", iconName = RaftLucideIconName.Bell, section = "Personal"),
                        SettingsHomePageLinkPayload("server", "Server", iconName = RaftLucideIconName.Building2, value = "Owner", section = "Workspace"),
                        SettingsHomePageLinkPayload("about", "About", iconName = RaftLucideIconName.BadgeInfo, section = "About"),
                    ),
            ),
        onBack = {},
        onSelect = {},
    )
}
