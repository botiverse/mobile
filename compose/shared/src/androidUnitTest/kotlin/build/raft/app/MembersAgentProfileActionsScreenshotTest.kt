package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The actions at the end of an agent's profile (`members.agent-profile.actions`, web
 * features/agent/AgentActionRows.tsx, botiverse/slock #15515): the production
 * [MembersAgentProfileActionsSection] for a running agent — a card of action rows with no
 * heading (Stop agent, Restart / Reset, Copy diagnostic info, Report issue, Archive agent), only
 * Archive agent red. Light and dark. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h400dp-xxhdpi")
class MembersAgentProfileActionsScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun actionsLight() = capture("members.agent-profile.actions", RaftAppAppearance.Light)

    @Test
    fun actionsDark() = capture("members.agent-profile.actions.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
                        MembersAgentProfileActionsSection(
                            member = MemberProfile(id = MemberId("agent-actions"), displayName = "Ruby", handle = "Ruby", kind = MemberKind.Agent),
                            detail = null,
                            resolvedStatus = raftAgentStatusIndicatorState(status = AgentStatus.Working, presence = Presence.Active, external = false),
                            canManage = true,
                            lifecycleInFlight = null,
                            diagnosticActionState = null,
                            onRunLifecycleAction = {},
                            onOpenRestartReset = {},
                            onCopyDiagnostics = {},
                            onReportIssue = {},
                            reportIssueOffered = true,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }
            }
        }
}
