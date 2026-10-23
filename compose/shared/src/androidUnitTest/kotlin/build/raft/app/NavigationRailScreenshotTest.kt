package build.raft.app

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The wide layout's navigation rail ([RaftNavigationRailTabBar]), light and dark: the server
 * tile in the header, Home selected, Activity with unread, Tasks, Search, Members, then Settings
 * in the footer. Fixed fixture data; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class NavigationRailScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun navigationRailLight() = capture("shell.navigation-rail", RaftAppAppearance.Light)

    @Test
    fun navigationRailDark() = capture("shell.navigation-rail.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftNavigationRailTabBar(
                    items =
                        listOf(
                            RaftBottomTabItemPayload(key = "home", label = "Home", iconName = RaftLucideIconName.House, selected = true),
                            RaftBottomTabItemPayload(key = "activity", label = "Activity", iconName = RaftLucideIconName.Activity, badgeCount = 3),
                            RaftBottomTabItemPayload(key = "tasks", label = "Tasks", iconName = RaftLucideIconName.SquareCheckBig),
                            RaftBottomTabItemPayload(key = "search", label = "Search", iconName = RaftLucideIconName.Search),
                            RaftBottomTabItemPayload(key = "members", label = "Members", iconName = RaftLucideIconName.Users),
                        ),
                    onSelect = {},
                    modifier = Modifier.height(600.dp),
                    header = RaftNavigationRailHeader(key = "server:1", label = "Botiverse", unread = true),
                    bottomItems =
                        listOf(RaftBottomTabItemPayload(key = "settings", label = "Settings", iconName = RaftLucideIconName.Settings)),
                )
            }
        }
}
