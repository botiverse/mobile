package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import build.raft.app.data.ThreadAgentFollower
import build.raft.app.data.ThreadAgentFollowerRoster
import build.raft.app.data.ThreadAgentFollowersState
import build.raft.app.model.AgentStatus
import build.raft.app.model.Presence
import kotlin.test.Test
import kotlinx.coroutines.flow.flowOf
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The thread's "Watching agents" popover (web ThreadAgentFollowers, artin 2026-11-02): the
 * popover layer with a seam under the headline title, member rows (the activity in the sans
 * face), and a joint channel's peer agent named by its server right after its name.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h480dp-xxhdpi")
class ThreadAgentFollowersPanelScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun light() = capture("thread.followers.panel", RaftAppAppearance.Light)

    @Test fun dark() = capture("thread.followers.panel.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(24.dp)) {
                        // Online, not Working: a working light pulses forever and the capture never idles.
                        val resolver: RaftAgentStatusResolver =
                            remember {
                                {
                                    flowOf(
                                        raftAgentStatusIndicatorState(
                                            status = AgentStatus.Online,
                                            presence = Presence.Active,
                                            statusLabel = "online",
                                            external = false,
                                        )
                                    )
                                }
                            }
                        ThreadAgentFollowersPanel(
                            state = ThreadAgentFollowersState(roster = roster, open = true),
                            dispatch = {},
                            onOpenAgentProfile = {},
                            agentStatusResolver = resolver,
                        )
                    }
                }
            }
        }
}

private val roster =
    ThreadAgentFollowerRoster(
        threadChannelId = "thread-1",
        canManage = true,
        agents =
            listOf(
                ThreadAgentFollower(id = "agent-lagoon", name = "Lagoon", status = "active", serverId = "s-1", isCurrentServer = true, canRemove = true),
                ThreadAgentFollower(
                    id = "agent-hanxin",
                    name = "HanXin",
                    status = "active",
                    serverId = "s-2",
                    serverName = "Raft Mobile",
                    isCurrentServer = false,
                    canRemove = false,
                ),
            ),
    )
