package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.RaftListPaneKind
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Wide pane roles (artin 2026-10-29): channel settings, and a member's profile, opened from a
 * conversation on the wide layout (and Add Member, the members page's add view) are a supporting
 * pane right of it (web ChannelSettingsSheet /
 * ProfilePanel, at most 420 wide), the rail, the sidebar and the conversation staying. The
 * conversation is a fixture; the panes are the channel settings fixture of
 * [ChannelSettingsScreenshotTest] and the agent profile visual fixture. Verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class WideChannelSettingsPaneScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun channelSettingsPaneLight() = capture("shell.wide.channel-settings", RaftAppAppearance.Light)

    @Test
    fun channelSettingsPaneDark() = capture("shell.wide.channel-settings.dark", RaftAppAppearance.Dark)

    // artin 2026-10-29: an agent's profile opened from the chat (avatar, mention) is the same pane
    // (web ProfilePanel / AgentDetailPanel beside the conversation); its first page shows ×.
    @Test
    fun agentProfilePaneLight() = capture("shell.wide.agent-profile", RaftAppAppearance.Light, AgentProfilePane)

    @Test
    fun agentProfilePaneDark() = capture("shell.wide.agent-profile.dark", RaftAppAppearance.Dark, AgentProfilePane)

    // artin 2026-10-30 (web parity): Add Member is the members page's add view, so on the wide
    // layout it is this pane too (web ChannelMembers `presentation="panel"`), never a centred modal.
    @Test
    fun addMemberPaneLight() = capture("shell.wide.add-member", RaftAppAppearance.Light, AddMemberPane)

    @Test
    fun addMemberPaneDark() = capture("shell.wide.add-member.dark", RaftAppAppearance.Dark, AddMemberPane)

    private fun capture(name: String, appearance: RaftAppAppearance, pane: PaneFixture = ChannelSettingsPane) =
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
                                bottomItems = listOf(RaftBottomTabItemPayload("settings", "Settings", RaftLucideIconName.Settings)),
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
                                    detailPane = { modifier ->
                                        RaftSupportingPaneLayout(
                                            modifier = modifier.fillMaxSize(),
                                            // The pane's first page, as RaftSupportingPaneStack provides it.
                                            supporting = {
                                                CompositionLocalProvider(LocalRaftPaneBackCloses provides true) {
                                                    pane.content()
                                                }
                                            },
                                        ) { detailModifier -> FixtureConversation(detailModifier, pane.lines) }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

    @Composable
    private fun FixtureSidebar() {
        Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
            RaftText("Botiverse", modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), style = RaftTheme.typography.headline)
            listOf("design", "general", "infra-hosts", "proj-workflow", "triage").forEach { channel ->
                RaftListRow(title = "# $channel", selected = channel == "triage", onClick = {})
            }
        }
    }

    /** What the pane shows, and the two lines of the conversation beside it. */
    private class PaneFixture(val lines: Pair<String, String>, val content: @Composable () -> Unit)

    private val ChannelSettingsPane =
        PaneFixture(
            "Ada: the channel settings now open beside the conversation" to "Linus: and the chat stays where it was",
        ) { ChannelSettingsVisualContent() }

    private val AddMemberPane =
        PaneFixture(
            "Ada: can we add Cindy to #design?" to "Linus: Add Member opens beside the chat",
        ) { ChannelAddMemberPanelVisualScreen() }

    private val AgentProfilePane =
        PaneFixture(
            "Ada: @Product-UX-Designer can you check the release notes?" to "Linus: the profile opens beside the chat",
        ) { MembersAgentDetailVisualContent() }

    @Composable
    private fun FixtureConversation(modifier: Modifier, lines: Pair<String, String>) {
        Column(modifier.background(RaftColor.SurfaceWhite)) {
            RaftText("# triage", modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), style = RaftTheme.typography.headline)
            Column(Modifier.weight(1f).padding(16.dp)) {
                RaftText(lines.first)
                RaftText(lines.second, modifier = Modifier.padding(top = 12.dp))
            }
            Box(Modifier.height(56.dp))
        }
    }
}
