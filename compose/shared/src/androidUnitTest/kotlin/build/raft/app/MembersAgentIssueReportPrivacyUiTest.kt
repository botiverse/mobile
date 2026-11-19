package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import build.raft.app.data.RaftPrivacyMode
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `RAFT_PRIVACY_MODE` and the agent Report issue (web AgentDetailPanel + ReportIssueDialog
 * `isPrivacyModeOn()`), on a real composition: the profile's action row is only there when privacy
 * mode is off, and a panel left open when it turns on (or back to unknown) says why and never
 * submits.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class MembersAgentIssueReportPrivacyUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun theProfileOffersReportIssueOnlyWhenOffered() = runRobolectricComposeUiTest {
        var offered by mutableStateOf(false)
        setContent {
            Page {
                MembersAgentProfileActionsSection(
                    member = MemberProfile(id = MemberId("agent-1"), displayName = "Ruby", handle = "Ruby", kind = MemberKind.Agent),
                    detail = null,
                    resolvedStatus = raftAgentStatusIndicatorState(status = AgentStatus.Working, presence = Presence.Active, external = false),
                    canManage = true,
                    lifecycleInFlight = null,
                    diagnosticActionState = null,
                    onRunLifecycleAction = {},
                    onOpenRestartReset = {},
                    onCopyDiagnostics = {},
                    onReportIssue = {},
                    reportIssueOffered = offered,
                )
            }
        }
        waitForIdle()
        assertEquals(0, count("Report issue"), "hidden while privacy mode is unknown or on")
        onNodeWithText("Copy diagnostic info").assertExists()

        runOnIdle { offered = true }
        waitForIdle()
        assertEquals(1, count("Report issue"), "offered once privacy mode is off")
    }

    @Test
    fun anOpenPanelSaysWhyAndNeverSubmitsWhenPrivacyModeIsNotOff() = runRobolectricComposeUiTest {
        var mode by mutableStateOf(RaftPrivacyMode.Off)
        val submits = mutableListOf<MembersAgentIssueReportSelection>()
        setContent {
            Page {
                MembersAgentIssueReportDialog(
                    agentName = "Cindy",
                    runtimeTranscriptAvailable = false,
                    canIncludeMachineLog = false,
                    busy = false,
                    errorMessage = null,
                    privacyMode = mode,
                    onSubmit = { submits += it },
                    onDismiss = {},
                )
            }
        }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        onNodeWithTag(MEMBERS_AGENT_ISSUE_PRIVACY_NOTICE_TAG).assertDoesNotExist()
        onNodeWithText("I agree to send this report to the Raft team.").performClick()
        waitForIdle()
        assertTrue(reportButtonsEnabled().any { it }, "off: consent enables Report")

        val expected =
            mapOf(
                RaftPrivacyMode.On to "This server runs in privacy mode, so the report can't be sent to Raft.",
                RaftPrivacyMode.Unknown to "Can't send yet: this server hasn't said whether privacy mode is on.",
            )
        for ((state, text) in expected) {
            runOnIdle { mode = state }
            waitForIdle()
            onNodeWithTag(MEMBERS_AGENT_ISSUE_PRIVACY_NOTICE_TAG).assertExists()
            onNodeWithText(text, substring = true).assertExists()
            val enabled = reportButtonsEnabled()
            assertTrue(enabled.isNotEmpty() && enabled.none { it }, "$state: Report is disabled, was $enabled")
            onAllNodesReport().forEach { it.performClick() }
            waitForIdle()
            assertEquals(emptyList(), submits, "$state: nothing submitted")
        }
    }

    private fun ComposeUiTest.onAllNodesReport() =
        onAllNodes(hasText("Report") and hasClickAction()).let { nodes ->
            List(nodes.fetchSemanticsNodes().size) { nodes[it] }
        }

    private fun ComposeUiTest.reportButtonsEnabled(): List<Boolean> =
        onAllNodes(hasText("Report") and hasClickAction()).fetchSemanticsNodes().map { node ->
            !node.config.contains(SemanticsProperties.Disabled)
        }

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { RaftDesignSystemTheme { content() } }
    }

    private fun ComposeUiTest.count(text: String): Int = onAllNodes(hasText(text)).fetchSemanticsNodes().size
}
