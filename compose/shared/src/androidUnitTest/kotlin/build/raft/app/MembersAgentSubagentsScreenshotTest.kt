package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentActivitySubagentState
import build.raft.app.data.groupAgentActivitySubagents
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The agent Activity tab's Sub-agents view (web AgentActivityLog `SubagentsList` /
 * `SubagentDetail`, slock #proj-runtime:#41): the list (one running, one done, one failed) light
 * and dark, and a running sub-agent's page with Stop. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class MembersAgentSubagentsScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun subagentsListLight() = capture("members.agent-activity.subagents", RaftAppAppearance.Light) { List() }

    @Test
    fun subagentsListDark() = capture("members.agent-activity.subagents.dark", RaftAppAppearance.Dark) { List() }

    @Test
    fun subagentDetailLight() = capture("members.agent-activity.subagent-detail", RaftAppAppearance.Light) { Detail() }

    @Composable
    private fun List() {
        val groups = remember { groupAgentActivitySubagents(subagentsFixtureLog()) }
        MembersAgentSubagentsList(
            groups = groups,
            timeContext = SubagentsFixtureTimeContext,
            onOpen = {},
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            modifier = Modifier.fillMaxSize(),
            nowEpochMillis = { SubagentsFixtureNowMillis },
        )
    }

    @Composable
    private fun Detail() {
        val group = remember { groupAgentActivitySubagents(subagentsFixtureLog()).first { it.state == AgentActivitySubagentState.Running } }
        MembersAgentSubagentDetail(
            group = group,
            timeContext = SubagentsFixtureTimeContext,
            stop = MembersAgentSubagentStop(inFlightTaskId = null) {},
            onBack = {},
            horizontalInset = 16.dp,
            modifier = Modifier.fillMaxSize(),
            nowEpochMillis = { SubagentsFixtureNowMillis },
        ) { _, entry, last ->
            MembersAgentActivityRow(entry, SubagentsFixtureTimeContext, onOpenUrl = {}, last = last)
        }
    }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager), LocalInspectionMode provides true) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) { content() }
                }
            }
        }
    }
}
