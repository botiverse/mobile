package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
 * centred, and the bell at the trailing end, dotted for the Home list's one notification; under it
 * the navigation rail ([RaftNavigationRailTabBar]) with the web LeftRail's entries (Direction A,
 * #proj-mobile 10-30) built by the shell's own [appShellRailTabs] / [appShellRailFooterTabs]: Chat
 * selected, Activity with unread, Tasks, Members, Computers dotted for an unseen offline computer,
 * Apps; Help and Settings (dotted for an app update) in the footer; beside it the Home list pane,
 * with no head of its own (web Sidebar `hideTitle`).
 *
 * Captured at the windows the rail now starts at (768dp, the web's `md`): iPad portrait 768 and
 * 834, iPad 11" landscape 1194, and a 1280 desktop window (the Compose 版's default). The Help menu
 * open beside the rail is the desktop window's `.help` case. Fixed fixture data; verify / record
 * as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class NavigationRailScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun navigationRailLight() = capture("shell.navigation-rail", RaftAppAppearance.Light, 1280)

    @Test
    fun navigationRailDark() = capture("shell.navigation-rail.dark", RaftAppAppearance.Dark, 1280)

    @Test
    fun navigationRailHelpLight() = capture("shell.navigation-rail.help", RaftAppAppearance.Light, 1280, helpOpen = true)

    @Test
    fun navigationRailHelpDark() = capture("shell.navigation-rail.help.dark", RaftAppAppearance.Dark, 1280, helpOpen = true)

    @Test
    @Config(qualifiers = "w768dp-h1024dp-mdpi")
    fun ipadPortrait768Light() = capture("shell.navigation-rail.ipad-768", RaftAppAppearance.Light, 768)

    @Test
    @Config(qualifiers = "w768dp-h1024dp-mdpi")
    fun ipadPortrait768Dark() = capture("shell.navigation-rail.ipad-768.dark", RaftAppAppearance.Dark, 768)

    @Test
    @Config(qualifiers = "w834dp-h1194dp-mdpi")
    fun ipadPortrait834Light() = capture("shell.navigation-rail.ipad-834", RaftAppAppearance.Light, 834)

    @Test
    @Config(qualifiers = "w834dp-h1194dp-mdpi")
    fun ipadPortrait834Dark() = capture("shell.navigation-rail.ipad-834.dark", RaftAppAppearance.Dark, 834)

    @Test
    @Config(qualifiers = "w1194dp-h834dp-mdpi")
    fun ipadLandscapeLight() = capture("shell.navigation-rail.ipad-landscape", RaftAppAppearance.Light, 1194)

    @Test
    @Config(qualifiers = "w1194dp-h834dp-mdpi")
    fun ipadLandscapeDark() = capture("shell.navigation-rail.ipad-landscape.dark", RaftAppAppearance.Dark, 1194)

    private val servers =
        listOf(
            RaftServerSwitcherItem(id = "s1", name = "Botiverse", slug = "botiverse", activityUnreadCount = 0L),
            RaftServerSwitcherItem(id = "s2", name = "Acme Labs", slug = "acme", activityUnreadCount = 3L),
            RaftServerSwitcherItem(id = "s3", name = "Quiet", slug = "quiet", activityUnreadCount = 0L),
            RaftServerSwitcherItem(id = "s4", name = "Raft Design", slug = "raft-design", activityUnreadCount = 0L),
            RaftServerSwitcherItem(id = "s5", name = "Stamp", slug = "stamp", activityUnreadCount = 0L),
        )

    private fun capture(name: String, appearance: RaftAppAppearance, windowWidth: Int, helpOpen: Boolean = false) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            val topBar = remember { RaftAppShellTopBarState() }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager), LocalRaftAppShellTopBar provides topBar) {
                Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
                    RaftAppShellTopBar(
                        currentServer = servers.first(),
                        servers = servers,
                        serverMenuOpen = false,
                        onWorkspaceClick = {},
                        onServerPick = {},
                        searchLabel = "Search",
                        onSearchClick = {},
                        windowWidth = windowWidth.dp,
                        bell = topBar,
                    )
                    Row(Modifier.weight(1f)) {
                        val attention = setOf(RaftMoreAttention.Computers, RaftMoreAttention.AppUpdate)
                        val labels =
                            mapOf(
                                "home" to "Chat",
                                "activity" to "Activity",
                                "tasks" to "Tasks",
                                "members" to "Members",
                                AppShellRailComputersKey to "Computers",
                                AppShellRailAppsKey to "Apps",
                            )
                        RaftNavigationRailTabBar(
                            items =
                                appShellRailTabs(
                                    selectedTab = "home",
                                    labelFor = { labels.getValue(it) },
                                    activityUnreadCount = 3,
                                    moreAttention = attention,
                                ),
                            onSelect = {},
                            modifier = Modifier.fillMaxHeight(),
                            bottomItems =
                                appShellRailFooterTabs(
                                    selectedTab = "home",
                                    helpLabel = "Help",
                                    settingsLabel = "Settings",
                                    helpOpen = helpOpen,
                                    moreAttention = attention,
                                ),
                        )
                        // The Home list pane: no head under the top bar; its notifications go to the
                        // top bar's bell.
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
                                notifications = listOf(RaftHomeNotificationItem(id = "n-offline", title = "Computer offline")),
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
                RaftAppRailHelpOverlay(
                    open = helpOpen,
                    onDismiss = {},
                    bottomInset = 0.dp,
                    onDocumentation = {},
                    onFeedback = {},
                )
                }
            }
        }
}
