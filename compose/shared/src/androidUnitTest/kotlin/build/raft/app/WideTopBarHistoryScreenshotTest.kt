package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
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
 * light and dark: just left of the centred search field (8dp before it, outside its width), as
 * on-frame icon buttons, the workspace button back at the leading edge. Three 1024dp bars: just
 * opened (both greyed), after a few picks (Back live, Forward greyed), and after going back (both
 * live); then the narrowest wide layout (840dp) with a long server name, where the name ellipsizes
 * and the arrows stay clear of it. Fixed fixture data; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1024dp-h280dp-mdpi")
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

    private val longNamed =
        RaftServerSwitcherItem(id = "s1", name = "Botiverse Research Group", slug = "botiverse", activityUnreadCount = 0L)

    @Composable
    private fun Bar(topBar: RaftAppShellTopBarState, current: RaftServerSwitcherItem, width: Dp, back: Boolean, forward: Boolean) {
        RaftAppShellTopBar(
            currentServer = current,
            servers = listOf(current) + servers.drop(1),
            serverMenuOpen = false,
            onWorkspaceClick = {},
            onServerPick = {},
            searchLabel = "Search",
            onSearchClick = {},
            windowWidth = width,
            modifier = Modifier.width(width),
            bell = topBar,
            history = RaftAppShellTopBarHistory(canGoBack = back, canGoForward = forward, onBack = {}, onForward = {}),
        )
    }

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
                        Bar(topBar, servers.first(), 1024.dp, back, forward)
                    }
                    Bar(topBar, longNamed, 840.dp, back = true, forward = false)
                }
            }
        }
}
