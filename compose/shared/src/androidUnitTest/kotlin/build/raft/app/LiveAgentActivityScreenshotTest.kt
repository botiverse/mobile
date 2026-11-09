package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.RaftLiveAgentActivityItem
import build.raft.app.model.AgentStatus
import build.raft.designsystem.components.RaftTopBarDefaults
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Live agent activity (Web `WorkingNowSummary`, raft-design people-and-agents/working-agents.md):
 * the summary (3 faces and the count) before the bell on the phone's Home bar, and Working now open
 * under it, 300dp wide and aligned to the summary's end, one row per agent in the order they started
 * with what it is doing. Fixed fixture data; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h560dp-xxhdpi")
class LiveAgentActivityScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun homeBarLight() = capture("screens.home.working-now", RaftAppAppearance.Light) { PhoneHome(open = false) }

    @Test
    fun homeBarDark() = capture("screens.home.working-now.dark", RaftAppAppearance.Dark) { PhoneHome(open = false) }

    @Test
    fun homeBarOpenLight() = capture("screens.home.working-now.open", RaftAppAppearance.Light) { PhoneHome(open = true) }

    @Test
    fun homeBarOpenDark() = capture("screens.home.working-now.open.dark", RaftAppAppearance.Dark) { PhoneHome(open = true) }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        captureLiveAgentActivity(activities, name, appearance, content)
}

/** The wide layout: the summary before the bell at the right of the top bar, Working now open under it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1024dp-h420dp-mdpi")
class LiveAgentActivityTopBarScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun topBarLight() = captureLiveAgentActivity(activities, "shell.top-bar.working-now", RaftAppAppearance.Light) { WideTopBar() }

    @Test
    fun topBarDark() = captureLiveAgentActivity(activities, "shell.top-bar.working-now.dark", RaftAppAppearance.Dark) { WideTopBar() }
}

private fun captureLiveAgentActivity(
    activities: RobolectricComposeActivityRule,
    name: String,
    appearance: RaftAppAppearance,
    content: @Composable () -> Unit,
) = RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
    val pager =
        remember {
            object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
        }
    CompositionLocalProvider(
        LocalActivity provides RaftPageActivity(pager),
        LocalRaftWorkingNow provides remember { workingNowFixtureSource() },
    ) { content() }
}

internal val WorkingNowFixtureItems =
    listOf(
        RaftLiveAgentActivityItem("agent-niko", "Niko", "pixel:random:niko", AgentStatus.Working, startedAt = 1),
        RaftLiveAgentActivityItem("agent-kirby", "Kirby", "pixel:random:kirby", AgentStatus.Thinking, startedAt = 2),
        RaftLiveAgentActivityItem("agent-tenny", "Tenny", "pixel:random:tenny", AgentStatus.Working, startedAt = 3),
        RaftLiveAgentActivityItem("agent-volta", "Volta", "pixel:random:volta", AgentStatus.Working, startedAt = 4),
    )

/** What each fixture agent's light and row say: a runtime detail, Thinking…, a tool label, and no detail. */
private val WorkingNowFixtureStates: Map<String, RaftAgentStatusIndicatorState> =
    mapOf(
        "agent-niko" to working("Editing WorkingNowSummary.kt · #25"),
        "agent-kirby" to raftAgentStatusIndicatorState(AgentStatus.Thinking, AgentStatus.Thinking.presence, "Thinking", external = false),
        "agent-tenny" to working("Using read_file...").copy(activityToolName = "read_file"),
        "agent-volta" to working("Working"),
    )

private fun working(label: String) =
    raftAgentStatusIndicatorState(AgentStatus.Working, AgentStatus.Working.presence, label, external = false)

private val WorkingNowFixtureResolver: RaftAgentStatusResolver = { id ->
    val state = WorkingNowFixtureStates[id]
    RaftAgentStatusFlow(initial = state, upstream = flowOf(state))
}

internal fun workingNowFixtureSource(): RaftWorkingNowSource =
    RaftWorkingNowSource(
        serverId = "server-1",
        items = MutableStateFlow(WorkingNowFixtureItems),
        agentStatusResolver = WorkingNowFixtureResolver,
        subscriptions = null,
        onOpenAgent = {},
    )

@Composable
private fun PhoneHome(open: Boolean) {
    Box(Modifier.fillMaxSize()) {
        RaftStructuralHomeShell(
            currentServerName = "Raft",
            currentServerId = "server-1",
            channels =
                listOf(
                    RaftHomeConversationItem(id = "c-mobile", name = "proj-mobile", kind = "channel", unreadCount = 2),
                    RaftHomeConversationItem(id = "c-general", name = "general", kind = "channel"),
                ),
            directMessages =
                listOf(
                    RaftHomeConversationItem(id = "dm-cindy", name = "Cindy", kind = RaftThreadSurfaceKind.DirectMessage, avatarLabel = "CI", peerType = "human"),
                ),
            contentRefreshEnabled = false,
            applyTopSafeArea = false,
            selectedTabKey = "home",
            showBottomBar = true,
            searchAndActivityOnShellNav = true,
            outerPadding = 0.dp,
        )
        if (open) {
            // The popover as it opens: 4dp under the bar, its end on the summary's end (the bell's
            // 44dp target starts 48dp from the window's edge; the summary ends where it starts).
            WorkingNowOpenPanel(top = RaftAppSize.PageHeaderHeight + 4.dp, end = 48.dp)
        }
    }
}

@Composable
private fun WideTopBar() {
    val topBar = remember { RaftAppShellTopBarState() }
    CompositionLocalProvider(LocalRaftAppShellTopBar provides topBar) {
        build.raft.app.components.RaftDesignSystemTheme {
        Box(Modifier.width(1024.dp).height(420.dp).background(RaftTheme.colors.layerCanvas)) {
            Column {
                RaftAppShellTopBar(
                    currentServer = RaftServerSwitcherItem(id = "s1", name = "Botiverse", slug = "botiverse", activityUnreadCount = 0L),
                    servers = listOf(RaftServerSwitcherItem(id = "s1", name = "Botiverse", slug = "botiverse", activityUnreadCount = 0L)),
                    serverMenuOpen = false,
                    onWorkspaceClick = {},
                    onServerPick = {},
                    searchLabel = "Search",
                    onSearchClick = {},
                    windowWidth = 1024.dp,
                    modifier = Modifier.width(1024.dp),
                    bell = topBar,
                    history = RaftAppShellTopBarHistory(canGoBack = true, canGoForward = false, onBack = {}, onForward = {}),
                )
            }
            // The bell is the bar's last 36dp item, 12dp from the edge; 4dp before it the summary ends.
            WorkingNowOpenPanel(top = RaftTopBarDefaults.Height + 4.dp, end = 12.dp + 36.dp + 4.dp)
        }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.WorkingNowOpenPanel(top: Dp, end: Dp) {
    Box(Modifier.align(Alignment.TopEnd).padding(top = top, end = end)) {
        RaftWorkingNowPanel(items = WorkingNowFixtureItems, agentStatusResolver = WorkingNowFixtureResolver, onOpenAgent = {})
    }
}
