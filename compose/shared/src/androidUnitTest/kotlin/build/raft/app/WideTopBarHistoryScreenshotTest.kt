package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
 * The wide top bar's Back / Forward arrows (artin 2026-10-27, Slack desktop's history arrows),
 * light and dark: at the leading edge before the workspace button, as on-frame icon buttons. Three
 * bars: just opened (both greyed), after a few picks (Back live, Forward greyed), and after going
 * back (both live). Fixed fixture data; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1024dp-h220dp-mdpi")
class WideTopBarHistoryScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun historyLight() = capture("shell.top-bar.history", RaftAppAppearance.Light)

    @Test
    fun historyDark() = capture("shell.top-bar.history.dark", RaftAppAppearance.Dark)

    private val servers =
        listOf(
            RaftServerSwitcherItem(id = "s1", name = "Botiverse", slug = "botiverse", activityUnreadCount = 0L),
            RaftServerSwitcherItem(id = "s2", name = "Acme Labs", slug = "acme", activityUnreadCount = 3L),
            RaftServerSwitcherItem(id = "s3", name = "Quiet", slug = "quiet", activityUnreadCount = 0L),
        )

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            val topBar = remember { RaftAppShellTopBarState() }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager), LocalRaftAppShellTopBar provides topBar) {
                Column(
                    Modifier.width(1024.dp).background(RaftColor.SurfaceWhite),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    listOf(false to false, true to false, true to true).forEach { (back, forward) ->
                        RaftAppShellTopBar(
                            currentServer = servers.first(),
                            servers = servers,
                            serverMenuOpen = false,
                            onWorkspaceClick = {},
                            onServerPick = {},
                            searchLabel = "Search",
                            onSearchClick = {},
                            windowWidth = 1024.dp,
                            bell = topBar,
                            history = RaftAppShellTopBarHistory(canGoBack = back, canGoForward = forward, onBack = {}, onForward = {}),
                        )
                    }
                }
            }
        }
}
