package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
 * The wide layout's chrome, light and dark (artin task #8, the web desktop): the top bar
 * ([RaftAppShellTopBar]) across the window, with the current server's workspace button, the
 * workspace strip of five servers (Botiverse current, Acme Labs with unread) and the search field
 * centred; under it the navigation rail ([RaftNavigationRailTabBar]) with no header and no Search:
 * Home selected, Activity with unread, Tasks, Members, then Settings in the footer; beside it the
 * Home list pane, whose head shows "Home" rather than a second server switcher. Fixed fixture
 * data; verify / record as [RaftRobolectricScreenshot].
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

    private val servers =
        listOf(
            RaftServerSwitcherItem(id = "s1", name = "Botiverse", slug = "botiverse", activityUnreadCount = 0L),
            RaftServerSwitcherItem(id = "s2", name = "Acme Labs", slug = "acme", activityUnreadCount = 3L),
            RaftServerSwitcherItem(id = "s3", name = "Quiet", slug = "quiet", activityUnreadCount = 0L),
            RaftServerSwitcherItem(id = "s4", name = "Raft Design", slug = "raft-design", activityUnreadCount = 0L),
            RaftServerSwitcherItem(id = "s5", name = "Stamp", slug = "stamp", activityUnreadCount = 0L),
        )

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                Column(Modifier.width(1280.dp).height(600.dp).background(RaftColor.SurfaceWhite)) {
                    RaftAppShellTopBar(
                        currentServer = servers.first(),
                        servers = servers,
                        serverMenuOpen = false,
                        onWorkspaceClick = {},
                        onServerPick = {},
                        searchLabel = "Search",
                        onSearchClick = {},
                        windowWidth = 1280.dp,
                    )
                    Row(Modifier.weight(1f)) {
                        RaftNavigationRailTabBar(
                            items =
                                listOf(
                                    RaftBottomTabItemPayload(key = "home", label = "Home", iconName = RaftLucideIconName.House, selected = true),
                                    RaftBottomTabItemPayload(key = "activity", label = "Activity", iconName = RaftLucideIconName.Activity, badgeCount = 3),
                                    RaftBottomTabItemPayload(key = "tasks", label = "Tasks", iconName = RaftLucideIconName.SquareCheckBig),
                                    RaftBottomTabItemPayload(key = "members", label = "Members", iconName = RaftLucideIconName.Users),
                                ),
                            onSelect = {},
                            modifier = Modifier.fillMaxHeight(),
                            bottomItems =
                                listOf(RaftBottomTabItemPayload(key = "settings", label = "Settings", iconName = RaftLucideIconName.Settings)),
                        )
                        // The Home list pane: its head shows the page name, not a second server
                        // switcher (the top bar holds the server).
                        Box(Modifier.width(320.dp).fillMaxHeight()) {
                            RaftStructuralHomeShell(
                                currentServerName = servers.first().name,
                                currentServerId = servers.first().id,
                                servers = servers,
                                channels =
                                    listOf(
                                        RaftHomeConversationItem(id = "c-general", name = "general", kind = "channel"),
                                        RaftHomeConversationItem(id = "c-design", name = "design", kind = "channel", unreadCount = 3),
                                    ),
                                directMessages =
                                    listOf(RaftHomeConversationItem(id = "dm-ada", name = "Ada", kind = "dm", avatarLabel = "AD")),
                                serverSwitcherInTopBar = true,
                                applyTopSafeArea = false,
                                topSafeAreaOverride = 0.dp,
                                showBottomBar = false,
                                searchAndActivityOnShellNav = true,
                                bottomContentPadding = 0.dp,
                                outerPadding = 0.dp,
                                contentRefreshEnabled = false,
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
}
