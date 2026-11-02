package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
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
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * xxchan 2026-11-02 (Android tablet): Members › an agent in the wide right pane showed an empty
 * status-bar-high strip above its head, because the head padded the top safe area again under the
 * shell's top bar, which had already cleared it. Captured through the real [RaftAppShellSurface]
 * with a 24dp status bar: the top bar clears it once, and the agent head sits right under the
 * bar's seam beside the Members list. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1024dp-h360dp-mdpi")
class WideMembersDetailTopInsetScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun agentDetailUnderTopBarLight() = capture("members.wide.agent-detail-top-inset", RaftAppAppearance.Light)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData =
                            PageData(
                                JSONObject().apply {
                                    // Own window id: the inset map is process-wide and other tests
                                    // publish live values for the default window.
                                    put("slockWindowId", "test:wide-members-top-inset")
                                    put("statusBarHeight", "24")
                                },
                            )
                    }
                }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftAppShellTopBar provides remember { RaftAppShellTopBarState() },
            ) {
                RaftDesignSystemTheme { WideMembersShell() }
            }
        }
}

@Composable
private fun WideMembersShell() {
    val topSafeArea = raftWindowTopSafeAreaPadding()
    val servers = listOf(RaftServerSwitcherItem(id = "s1", name = "Raft", slug = "raft", activityUnreadCount = 0L))
    RaftAppShellSurface(
        shellScope =
            RaftAppShellScope(
                topSafeArea = topSafeArea,
                bottomSafeArea = 0.dp,
                bottomTabBarContentInset = 0.dp,
                bottomTabBarOverlaysContent = false,
            ),
        bottomTabs = emptyList(),
        onBottomTabSelected = {},
        showBottomTabs = false,
        leadingNavigation = {
            RaftNavigationRailTabBar(
                items =
                    listOf(
                        RaftBottomTabItemPayload("home", "Home", RaftLucideIconName.MessageSquare),
                        RaftBottomTabItemPayload("members", "Members", RaftLucideIconName.Users, selected = true),
                    ),
                onSelect = {},
                modifier = Modifier.fillMaxHeight(),
            )
        },
        topNavigation = {
            RaftAppShellTopBar(
                currentServer = servers.first(),
                servers = servers,
                serverMenuOpen = false,
                onWorkspaceClick = {},
                onServerPick = {},
                searchLabel = "Search",
                onSearchClick = {},
                windowWidth = 1024.dp,
                topSafeArea = topSafeArea,
            )
        },
    ) {
        Row(Modifier.fillMaxSize()) {
            // The Members list pane (content not under test).
            Box(Modifier.width(320.dp).fillMaxHeight().background(RaftColor.SurfaceWhite))
            Spacer(Modifier.width(1.dp).fillMaxHeight().background(RaftColor.BorderSeam))
            Column(Modifier.weight(1f).fillMaxHeight().background(RaftColor.SurfaceWhite)) {
                val state = raftAgentStatusIndicatorState(status = AgentStatus.Online, presence = Presence.Active, external = false)
                MembersDetailHeader(
                    member = wideCindy,
                    currentUserId = "human-artin",
                    currentServerId = "s1",
                    detail = null,
                    deleted = false,
                    canManage = true,
                    agentStatusIndicatorState = state,
                    onBack = {},
                    onOpenDirectMessage = {},
                    onCopyMemberId = {},
                    onManagementMenuAnchorResolverReady = {},
                    onRequestManagementMenu = {},
                )
                Box(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

private val wideCindy =
    MemberProfile(
        id = MemberId("agent-cindy"),
        displayName = "Cindy",
        handle = "Cindy",
        kind = MemberKind.Agent,
        description = "Chief of Staff",
    )
