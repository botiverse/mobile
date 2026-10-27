package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentIssueReportResult
import build.raft.app.data.AgentIssueTicketRequest
import build.raft.app.data.AgentIssueTranscriptRequestState
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * JVM screenshots of the agent Report Issue panel and its "Report Submitted" screen (web
 * ReportIssueDialog), light and dark:
 * - `members.report-issue`: the panel for an agent on the reporter's own machine — the Include
 *   section with both disclosures, every box on, the transcript hint, the machine runner log tail
 *   with its disclosure, and the warning box with the consent checkbox.
 * - `members.report-issue.submitted`: the ticket was filed (View Ticket + Done), session record
 *   requested.
 * - `members.report-issue.ticket-failed`: uploaded but no ticket — warning, report reference,
 *   Copy Report Reference and Retry Ticket.
 * Verify / record as [RaftRobolectricScreenshot]; reports land in `build/reports/home-screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1500dp-xxhdpi")
class MembersAgentIssueReportScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun panelLight() = capture("members.report-issue", RaftAppAppearance.Light) { PanelContent() }

    @Test
    fun panelDark() = capture("members.report-issue.dark", RaftAppAppearance.Dark) { PanelContent() }

    @Test
    fun submittedLight() = capture("members.report-issue.submitted", RaftAppAppearance.Light) { ReceiptContent(filedReceipt) }

    @Test
    fun submittedDark() = capture("members.report-issue.submitted.dark", RaftAppAppearance.Dark) { ReceiptContent(filedReceipt) }

    @Test
    fun ticketFailedLight() =
        capture("members.report-issue.ticket-failed", RaftAppAppearance.Light) { ReceiptContent(ticketFailedReceipt) }

    @Test
    fun ticketFailedDark() =
        capture("members.report-issue.ticket-failed.dark", RaftAppAppearance.Dark) { ReceiptContent(ticketFailedReceipt) }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
            root = { ShadowDialog.getLatestDialog()?.window?.decorView },
        ) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { content() }
            }
        }
}

private val ticketRequest =
    AgentIssueTicketRequest(
        message = "Issue report for Cindy",
        feedbackReportId = "7e91c120-f424-42d7-96e5-21eb816866d0",
        submissionId = "0d9b2c4e-6f1a-4b3c-9d8e-7f6a5b4c3d2e",
        locale = "en",
    )

private val filedReceipt =
    AgentIssueReportResult(
        reportId = "7e91c120-f424-42d7-96e5-21eb816866d0",
        artifactId = "95d629bd-3ed7-42ff-97eb-3ed35c6dc6ab",
        serverId = "65598229-cd0a-4e92-96f3-c33e7e2e4cc1",
        issueDescription = "The agent stopped responding after a long-running command.",
        transcriptRequested = true,
        transcriptRequestState = AgentIssueTranscriptRequestState.Requested,
        ticketId = "3f6c2a10-8d4b-4e2f-9a1c-5b7d8e9f0a1b",
        ticketRequest = ticketRequest,
    )

private val ticketFailedReceipt =
    filedReceipt.copy(
        ticketId = null,
        transcriptRequestState = AgentIssueTranscriptRequestState.RequestUnconfirmed,
    )

@Composable
private fun PanelContent() {
    MembersAgentIssueReportDialog(
        agentName = "Cindy",
        runtimeTranscriptAvailable = true,
        canIncludeMachineLog = true,
        busy = false,
        errorMessage = null,
        onSubmit = {},
        onDismiss = {},
    )
}

@Composable
private fun ReceiptContent(receipt: AgentIssueReportResult) {
    MembersAgentIssueReportReceiptDialog(
        receipt = receipt,
        referenceCopied = false,
        ticketRetrying = false,
        onCopyReference = {},
        onRetryTicket = {},
        onViewTicket = {},
        onDismiss = {},
    )
}
