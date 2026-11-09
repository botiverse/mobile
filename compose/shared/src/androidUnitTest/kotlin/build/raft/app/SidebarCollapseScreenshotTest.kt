package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.RaftListPaneKind
import build.raft.designsystem.components.RaftSidebarItem
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The wide shell with the rail's Collapse / Expand Sidebar (web SidebarToggleRailItem) heading the
 * footer: Home's sidebar shown (PanelLeftClose), then collapsed (PanelLeftOpen) with the
 * conversation taking the whole width beside the rail. Fixed fixture rows; verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class SidebarCollapseScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun expandedLight() = capture("shell.sidebar-collapse.expanded", RaftAppAppearance.Light, hidden = false)

    @Test
    fun expandedDark() = capture("shell.sidebar-collapse.expanded.dark", RaftAppAppearance.Dark, hidden = false)

    @Test
    fun collapsedLight() = capture("shell.sidebar-collapse.collapsed", RaftAppAppearance.Light, hidden = true)

    @Test
    fun collapsedDark() = capture("shell.sidebar-collapse.collapsed.dark", RaftAppAppearance.Dark, hidden = true)

    private fun capture(name: String, appearance: RaftAppAppearance, hidden: Boolean) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                val servers = listOf(RaftServerSwitcherItem(id = "s1", name = "Botiverse", slug = "botiverse", activityUnreadCount = 0L))
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
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
                                        RaftBottomTabItemPayload("home", "Home", RaftLucideIconName.House, selected = true),
                                        RaftBottomTabItemPayload("activity", "Activity", RaftLucideIconName.Activity),
                                        RaftBottomTabItemPayload("tasks", "Tasks", RaftLucideIconName.SquareCheckBig),
                                        RaftBottomTabItemPayload("members", "Members", RaftLucideIconName.Users),
                                    ),
                                onSelect = {},
                                modifier = Modifier.fillMaxHeight(),
                                bottomItems =
                                    listOf(
                                        RaftBottomTabItemPayload("help", "Help", RaftLucideIconName.CircleQuestionMark),
                                        RaftBottomTabItemPayload("settings", "Settings", RaftLucideIconName.Settings),
                                    ),
                                sidebarToggle = RaftRailSidebarToggle(hidden = hidden, onHiddenChange = {}, shortcutLabel = "Ctrl+\\"),
                            )
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                RaftAdaptiveListDetailSplit(
                                    kind = RaftListPaneKind.Sidebar,
                                    widths = remember { RaftListPaneWidths(store = null) },
                                    dualPane = true,
                                    active = true,
                                    hasSelection = true,
                                    detailBottomInset = 0.dp,
                                    listPane = { FixtureSidebar() },
                                    detailPane = { modifier -> FixtureConversation(modifier) },
                                    collapsed = hidden,
                                )
                            }
                        }
                    }
                }
            }
        }

    @Composable
    private fun FixtureSidebar() {
        Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite).padding(horizontal = 8.dp, vertical = 4.dp)) {
            listOf("triage", "infra-hosts", "proj-workflow", "design", "general").forEach { channel ->
                RaftSidebarItem(title = "# $channel", selected = channel == "design", onClick = {})
            }
        }
    }

    @Composable
    private fun FixtureConversation(modifier: Modifier) {
        Column(modifier.background(RaftColor.SurfaceWhite)) {
            RaftText(
                "# design",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                style = RaftTheme.typography.headline,
            )
            Box(Modifier.weight(1f).padding(16.dp), contentAlignment = Alignment.BottomStart) {
                RaftText("Ada: the list column now follows the web widths")
            }
        }
    }
}
