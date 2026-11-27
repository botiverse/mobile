package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.members.MembersAgentActivityLoadState
import build.raft.app.members.MembersAgentActivityTab
import build.raft.app.model.MemberId
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The top of the agent Activity tab by touch: the view switch (Raw log / Sub-agents) is no bar of
 * its own, as web AgentActivityLog's view toolbar: no divider under it, at least the header height
 * with 12dp above and below (`min-h-(--header-height) py-3`). Verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class MembersAgentActivityViewSwitchScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun viewSwitchLight() =
        RaftRobolectricScreenshot.capture(
            "members.agent-activity.view-switch",
            RaftAppAppearance.Light,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
        ) {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager), LocalInspectionMode provides true) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) { Tab() }
                }
            }
        }

    @Composable
    private fun Tab() {
        // Sub-agents in the log, so the switch shows (Raw log / Sub-agents); no Overview.
        val detail =
            remember {
                AgentDetailSnapshot(
                    agentId = MemberId("agent-1"),
                    directMessages = emptyList(),
                    reminders = emptyList(),
                    activity = subagentsFixtureLog(),
                )
            }
        MembersAgentActivityTab(
            detail = detail,
            serverId = null,
            agentStatusIndicatorState = null,
            loadState = MembersAgentActivityLoadState.Idle,
            diagnosticActionState = null,
            onCopyDiagnostics = {},
            timeContext = SubagentsFixtureTimeContext,
            onOpenUrl = {},
            modifier = Modifier.fillMaxSize(),
            autoScrollToLatest = false,
        )
    }
}
