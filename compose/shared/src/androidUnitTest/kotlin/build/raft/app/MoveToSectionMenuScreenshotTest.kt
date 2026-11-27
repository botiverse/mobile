package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.SettingsServerSidebarCustomSection
import build.raft.app.localized
import build.raft.app.members.localized
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sidebar row's Move to section submenu (web SidebarRowContextMenu, botiverse/slock #15514),
 * light and dark: `home.move-to-section.menu` is a channel in the custom section "Launch" — its
 * home section (Channels) first, Pinned, the custom sections with Launch checked and bold, then
 * New section; no "Remove from section". The real panel drawn in the page (in the app it is its
 * own popup window, which the capture can't see). Reports land in
 * `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h360dp-xxhdpi")
class MoveToSectionMenuScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun menuLight() = menu("home.move-to-section.menu", RaftAppAppearance.Light)

    private fun menu(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            Page {
                val items =
                    raftHomeMoveToSectionMenuItems(
                        ChannelSectionPlacement(
                            customSections =
                                listOf(
                                    SettingsServerSidebarCustomSection(id = "s-launch", name = "Launch", emoji = null),
                                    SettingsServerSidebarCustomSection(id = "s-ops", name = "Ops", emoji = null),
                                ),
                            pinned = false,
                            placementSectionId = "s-launch",
                        ),
                        RaftHomeShellLabels.localized(),
                    )
                Box(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite).padding(16.dp)) {
                    RaftLookBaseMenuPanel(
                        items = items,
                        selectedId = "",
                        width = raftLookBaseMenuWidth(items, fixedWidth = null, minWidth = 136.dp),
                        onSelect = {},
                    )
                }
            }
        }

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { RaftDesignSystemTheme { content() } }
    }
}
