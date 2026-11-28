package build.raft.app.members

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import build.raft.app.RaftAppSpacing
import build.raft.app.RaftColor
import build.raft.app.RaftDialogFooter
import build.raft.app.RaftDialogPresentation
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftTraceIdNote
import build.raft.app.RaftUnifiedDialog
import build.raft.app.components.raftCardSurface
import build.raft.app.data.AgentIssueDiagnosticsBundleRange
import build.raft.app.data.AgentIssueReportResult
import build.raft.app.data.AGENT_ISSUE_REPORT_PRIVACY_MODE_ON
import build.raft.app.data.AGENT_ISSUE_REPORT_PRIVACY_MODE_UNKNOWN
import build.raft.app.data.AGENT_ISSUE_REPORT_UNCONFIGURED
import build.raft.app.data.AgentIssueTranscriptRequestState
import build.raft.app.data.RaftPrivacyMode
import build.raft.app.data.ScreenError
import build.raft.app.fill
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftCheckboxRow
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftRadioGroup
import build.raft.designsystem.components.RaftRadioRow
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftTextarea
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/** What the report panel's form asks for (web ReportIssueDialog state). */
internal data class MembersAgentIssueReportSelection(
    val description: String,
    val includeRecentMessages: Boolean,
    val includeLiveActivity: Boolean,
    val includeActivityHistory: Boolean,
    val includeRuntimeTranscript: Boolean,
    val includeMachineLogTail: Boolean,
    val includeDiagnosticsBundle: Boolean = false,
    val diagnosticsBundleRange: AgentIssueDiagnosticsBundleRange = AgentIssueDiagnosticsBundleRange.ReportWindow
)

/**
 * The machine-log-tail box (web `includeMachineLogTail`, slock #15306). It is offered when
 * [canIncludeMachineLog] (whoever may edit the Computer: the person who attached it, or an owner
 * or admin, [membersAgentIssueCanIncludeMachineLog]). Until the reporter touches it, it follows
 * [ownAttachedMachine]: ticked only on the reporter's own attached machine (an owner or admin on
 * someone else's machine sees it unticked, since the tail may hold other people's agents' lines),
 * so a machine list that loads after the panel opened still ticks it. Once touched, the
 * reporter's [choice] wins.
 */
internal fun membersAgentIssueMachineLogTailChecked(
    choice: Boolean?,
    canIncludeMachineLog: Boolean,
    ownAttachedMachine: Boolean = canIncludeMachineLog,
): Boolean = canIncludeMachineLog && (choice ?: ownAttachedMachine)

/**
 * Web ReportIssueDialog `canIncludeMachineLog` (slock #15306, server #15305 `canManageComputer`
 * edit): the agent has a machine, and the reporter attached it or holds `editMachines` (server
 * owner or admin). The server checks the same.
 */
internal fun membersAgentIssueCanIncludeMachineLog(
    hasMachine: Boolean,
    attachedByCurrentUser: Boolean,
    serverRole: String?,
): Boolean = hasMachine && (attachedByCurrentUser || serverRole?.trim() == "owner" || serverRole?.trim() == "admin")

/**
 * The agent "Report Issue" panel, web parity with packages/web/src/components/agent/
 * ReportIssueDialog.tsx: agent card, optional description, the "Include" section (all on by
 * default; runtime transcript needs a machine; the runner log tail for whoever may edit the
 * machine, ticked only on the reporter's own, and only with the transcript), and the warning box holding the consent checkbox.
 */
@Composable
internal fun MembersAgentIssueReportDialog(
    agentName: String,
    runtimeTranscriptAvailable: Boolean,
    canIncludeMachineLog: Boolean,
    /** The reporter attached the agent's machine: the log-tail box starts ticked only then. */
    ownAttachedMachine: Boolean = canIncludeMachineLog,
    busy: Boolean,
    errorMessage: String?,
    errorTraceId: String? = null,
    /**
     * Privacy mode now (web ReportIssueDialog `isPrivacyModeOn()`). The entry is only offered when
     * it is off; if it turns on (or unknown, after an environment change) while the panel is open,
     * the panel says why and Submit stays disabled, so the bundle is never sent.
     */
    privacyMode: RaftPrivacyMode = RaftPrivacyMode.Off,
    onSubmit: (MembersAgentIssueReportSelection) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    var description by remember(agentName) { mutableStateOf(TextFieldValue()) }
    var consented by remember(agentName) { mutableStateOf(false) }
    var includeRecentMessages by remember(agentName) { mutableStateOf(true) }
    var includeLiveActivity by remember(agentName) { mutableStateOf(true) }
    var includeActivityHistory by remember(agentName) { mutableStateOf(true) }
    var includeRuntimeTranscript by remember(agentName, runtimeTranscriptAvailable) {
        mutableStateOf(runtimeTranscriptAvailable)
    }
    var machineLogTailChoice by remember(agentName) { mutableStateOf<Boolean?>(null) }
    val includeMachineLogTail = membersAgentIssueMachineLogTailChecked(machineLogTailChoice, canIncludeMachineLog, ownAttachedMachine)
    // Web #16580: Computer diagnostics bundle is attacher-only and opt-in (off by default).
    val canIncludeDiagnosticsBundle = runtimeTranscriptAvailable && ownAttachedMachine
    var includeDiagnosticsBundle by remember(agentName) { mutableStateOf(false) }
    var diagnosticsBundleRange by remember(agentName) {
        mutableStateOf(AgentIssueDiagnosticsBundleRange.ReportWindow)
    }
    // The one commit: the footer's Submit on a wide window, the phone sheet's head button.
    val commitLabel =
        if (busy) {
            stringResource(Res.string.member_report_issue_submitting)
        } else {
            stringResource(Res.string.member_report_issue_submit)
        }
    val privacyNotice = membersAgentIssueReportPrivacyNotice(privacyMode)
    val canCommit = consented && privacyNotice == null
    val commit: () -> Unit = commit@{
        if (privacyNotice != null) return@commit
        onSubmit(
            MembersAgentIssueReportSelection(
                description = description.text,
                includeRecentMessages = includeRecentMessages,
                includeLiveActivity = includeLiveActivity,
                includeActivityHistory = includeActivityHistory,
                includeRuntimeTranscript = includeRuntimeTranscript && runtimeTranscriptAvailable,
                includeMachineLogTail = includeMachineLogTail,
                includeDiagnosticsBundle = canIncludeDiagnosticsBundle && includeDiagnosticsBundle,
                diagnosticsBundleRange = diagnosticsBundleRange
            )
        )
    }
    RaftUnifiedDialog(
        title = stringResource(Res.string.member_report_issue_title),
        dismissEnabled = !busy,
        dismissOnOutsideTap = false,
        keyboardAvoidanceEnabled = true,
        maxWidth = 384.dp,
        contentPadding = 24.dp,
        onDismiss = onDismiss,
        presentation = RaftDialogPresentation.Sheet,
        commitLabel = commitLabel,
        onCommit = commit,
        commitEnabled = canCommit,
        commitLoading = busy
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md)
        ) {
            privacyNotice?.let { notice ->
                item {
                    // First, so it is seen before anything is filled in: why nothing can be sent.
                    RaftBanner(
                        description = notice,
                        status = RaftBannerStatus.Warning,
                        modifier = Modifier.testTag(MEMBERS_AGENT_ISSUE_PRIVACY_NOTICE_TAG)
                    )
                }
            }
            item {
                RaftCard(modifier = Modifier.fillMaxWidth()) {
                    RaftSectionEyebrow(text = stringResource(Res.string.member_report_issue_agent_label))
                    RaftText(
                        text = agentName,
                        style = typography.subhead.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.foreground
                    )
                }
            }
            item {
                RaftField(
                    label = stringResource(Res.string.member_report_issue_description_label),
                    modifier = Modifier.fillMaxWidth(),
                    optional = true,
                    enabled = !busy
                ) {
                    RaftTextarea(
                        value = description,
                        onValueChange = { description = it },
                        placeholder = stringResource(Res.string.member_report_issue_description_placeholder)
                    )
                }
            }
            item {
                Column {
                    // Web #14218: the Include list carries no default-included / summary lines.
                    RaftSectionEyebrow(text = stringResource(Res.string.member_report_issue_include))
                    RaftCheckboxRow(
                        label = stringResource(Res.string.member_report_issue_recent_messages),
                        checked = includeRecentMessages,
                        enabled = !busy,
                        onCheckedChange = { includeRecentMessages = it }
                    )
                    RaftCheckboxRow(
                        label = stringResource(Res.string.member_report_issue_live_activity),
                        checked = includeLiveActivity,
                        enabled = !busy,
                        onCheckedChange = { includeLiveActivity = it }
                    )
                    RaftCheckboxRow(
                        label = stringResource(Res.string.member_report_issue_activity_history),
                        checked = includeActivityHistory,
                        enabled = !busy,
                        onCheckedChange = { includeActivityHistory = it }
                    )
                    // Web: the runtime transcript row only exists for an agent on a machine.
                    if (runtimeTranscriptAvailable) {
                        RaftCheckboxRow(
                            label = stringResource(Res.string.member_report_issue_runtime_transcript),
                            description =
                                stringResource(Res.string.member_report_issue_runtime_transcript_hint)
                                    .takeIf { includeRuntimeTranscript },
                            checked = includeRuntimeTranscript,
                            enabled = !busy,
                            onCheckedChange = { includeRuntimeTranscript = it }
                        )
                    }
                    if (canIncludeMachineLog) {
                        RaftCheckboxRow(
                            label = stringResource(Res.string.member_report_issue_machine_log_tail),
                            description = stringResource(Res.string.member_report_issue_machine_log_tail_disclosure),
                            checked = includeMachineLogTail,
                            enabled = includeRuntimeTranscript && !busy,
                            onCheckedChange = { machineLogTailChoice = it }
                        )
                    }
                    if (canIncludeDiagnosticsBundle) {
                        RaftCheckboxRow(
                            label = stringResource(Res.string.member_report_issue_diagnostics_bundle),
                            description = stringResource(Res.string.member_report_issue_diagnostics_bundle_disclosure),
                            checked = includeDiagnosticsBundle,
                            enabled = includeRuntimeTranscript && !busy,
                            onCheckedChange = { includeDiagnosticsBundle = it },
                            modifier = Modifier.testTag(MEMBERS_AGENT_ISSUE_DIAGNOSTICS_BUNDLE_TAG)
                        )
                        if (includeDiagnosticsBundle && includeRuntimeTranscript) {
                            val rangeGroupLabel = stringResource(Res.string.member_report_issue_diagnostics_bundle_range)
                            RaftRadioGroup(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = 28.dp)
                                        .semantics { contentDescription = rangeGroupLabel }
                                        .testTag(MEMBERS_AGENT_ISSUE_DIAGNOSTICS_BUNDLE_RANGE_TAG)
                            ) {
                                AgentIssueDiagnosticsBundleRange.entries.forEach { option ->
                                    RaftRadioRow(
                                        label = membersAgentIssueDiagnosticsBundleRangeLabel(option),
                                        selected = diagnosticsBundleRange == option,
                                        enabled = !busy,
                                        onClick = { diagnosticsBundleRange = option },
                                        modifier = Modifier.testTag("$MEMBERS_AGENT_ISSUE_DIAGNOSTICS_BUNDLE_RANGE_TAG-${option.wire}")
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                // Web: a warning Banner holding the disclosure and the consent checkbox.
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1043
                            .raftCardSurface(fill = RaftColor.WarningSoft)
                            .padding(start = 12.dp, end = 12.dp, top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        RaftLucideIcon(
                            RaftLucideIconName.TriangleAlert,
                            modifier = Modifier.size(18.dp),
                            color = RaftColor.TextBlackArgb
                        )
                        RaftText(
                            text =
                                membersAgentIssueDisclosure(
                                    template = stringResource(Res.string.member_report_issue_disclosure),
                                    strong = stringResource(Res.string.member_report_issue_disclosure_strong)
                                ),
                            modifier = Modifier.weight(1f),
                            style = typography.callout,
                            color = colors.foreground
                        )
                    }
                    RaftCheckboxRow(
                        label = stringResource(Res.string.member_report_issue_consent),
                        checked = consented,
                        enabled = !busy,
                        onCheckedChange = { consented = it }
                    )
                }
            }
            errorMessage?.takeIf { it.isNotBlank() && it != privacyNotice }?.let { message ->
                item {
                    // Web: the error as is in a warning Banner; the trace ID under it (AGENTS.md).
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        RaftBanner(
                            description = message,
                            status = RaftBannerStatus.Warning
                        )
                        RaftTraceIdNote(errorTraceId)
                    }
                }
            }
        }
        RaftDialogFooter {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                enabled = !busy,
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg
            )
            RaftButton(
                text = commitLabel,
                enabled = canCommit,
                loading = busy,
                // Web ReportIssueDialog submits with the pink primary action, lg in the footer.
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
                onClick = commit
            )
        }
    }
}

/**
 * The submit error as web ReportIssueDialog words it: the failed step's label, then what went
 * wrong (`stepError`); a server mismatch is web `serverUnavailable`, an unexpected failure web
 * `submitFailed`.
 */
@Composable
internal fun membersAgentIssueReportErrorMessage(error: ScreenError, detail: String): String {
    val code = error.code
    val step =
        when {
            code.startsWith("agent_issue_report_messages_failed") || code == "agent_issue_report_hash_failed" ->
                Res.string.member_report_issue_step_collect_failed
            code.startsWith("agent_issue_report_attestation_failed") -> Res.string.member_report_issue_step_attestation_failed
            code.startsWith("agent_issue_report_create_failed") -> Res.string.member_report_issue_step_create_failed
            code.startsWith("agent_issue_report_upload_failed") -> Res.string.member_report_issue_step_upload_failed
            code.startsWith("agent_issue_report_complete_failed") -> Res.string.member_report_issue_step_complete_failed
            else -> null
        }
    if (code == AGENT_ISSUE_REPORT_PRIVACY_MODE_ON) return stringResource(Res.string.member_report_issue_privacy_mode_on)
    if (code == AGENT_ISSUE_REPORT_PRIVACY_MODE_UNKNOWN) return stringResource(Res.string.member_report_issue_privacy_mode_unknown)
    return when {
        step != null -> "${stringResource(step)}: $detail"
        code == "agent_issue_report_server_mismatch" -> stringResource(Res.string.member_report_issue_server_unavailable)
        code == "agent_issue_report_failed" || code == AGENT_ISSUE_REPORT_UNCONFIGURED ->
            stringResource(Res.string.member_report_issue_submit_failed)
        else -> detail
    }
}

internal const val MEMBERS_AGENT_ISSUE_PRIVACY_NOTICE_TAG = "members-agent-issue-privacy-notice"
internal const val MEMBERS_AGENT_ISSUE_DIAGNOSTICS_BUNDLE_TAG = "report-issue-diagnostics-bundle"
internal const val MEMBERS_AGENT_ISSUE_DIAGNOSTICS_BUNDLE_RANGE_TAG = "report-issue-diagnostics-bundle-range"

@Composable
private fun membersAgentIssueDiagnosticsBundleRangeLabel(range: AgentIssueDiagnosticsBundleRange): String =
    when (range) {
        AgentIssueDiagnosticsBundleRange.ReportWindow ->
            stringResource(Res.string.member_report_issue_diagnostics_bundle_range_report_window)
        AgentIssueDiagnosticsBundleRange.Last24h ->
            stringResource(Res.string.member_report_issue_diagnostics_bundle_range_last_24h)
        AgentIssueDiagnosticsBundleRange.Last7d ->
            stringResource(Res.string.member_report_issue_diagnostics_bundle_range_last_7d)
    }

/** Why the open panel can't send (null when privacy mode is off and it may). */
@Composable
internal fun membersAgentIssueReportPrivacyNotice(mode: RaftPrivacyMode): String? =
    when (mode) {
        RaftPrivacyMode.Off -> null
        RaftPrivacyMode.On -> stringResource(Res.string.member_report_issue_privacy_mode_on)
        RaftPrivacyMode.Unknown -> stringResource(Res.string.member_report_issue_privacy_mode_unknown)
    }

/** The disclosure with its `<strong>` span (web `agent.reportIssue.sensitiveDataWarning`). */
internal fun membersAgentIssueDisclosure(template: String, strong: String): AnnotatedString {
    val marker = "%1\$s"
    val index = template.indexOf(marker)
    if (index < 0) return AnnotatedString(template)
    return buildAnnotatedString {
        append(template.substring(0, index))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(strong) }
        append(template.substring(index + marker.length))
    }
}

/** Web `handleCopyReportReference`: report id, server id and the description when present. */
internal fun buildMembersAgentIssueReportReference(
    receipt: AgentIssueReportResult
): String = buildList {
    add("reportId: ${receipt.reportId}")
    add("serverId: ${receipt.serverId}")
    receipt.issueDescription?.takeIf { it.isNotBlank() }?.let { description ->
        add("issueDescription:\n$description")
    }
}.joinToString("\n")

@Composable
private fun MembersAgentIssueReportReferenceRow(
    label: String,
    value: String
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RaftText(
            text = label,
            style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
            color = colors.foregroundMuted
        )
        RaftText(
            text = value,
            style = typography.caption.copy(fontFamily = FontFamily.Monospace),
            color = colors.foreground
        )
    }
}

/**
 * The "Report Submitted" screen (web ReportIssueDialog `submitted`): the ticket line when a
 * ticket was filed, else the warning, report reference, Copy and Retry Ticket; the session-record
 * request outcome; View Ticket (when [onViewTicket] can open it) and Done.
 */
@Composable
internal fun MembersAgentIssueReportReceiptDialog(
    receipt: AgentIssueReportResult,
    referenceCopied: Boolean,
    ticketRetrying: Boolean,
    onCopyReference: () -> Unit,
    onRetryTicket: (() -> Unit)?,
    onViewTicket: ((String) -> Unit)?,
    onDismiss: () -> Unit
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val ticketId = receipt.ticketId
    RaftUnifiedDialog(
        title = stringResource(Res.string.member_report_issue_success_title),
        maxWidth = 384.dp,
        contentPadding = 24.dp,
        onDismiss = onDismiss
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .raftCardSurface(fill = RaftColor.Success),
                contentAlignment = Alignment.Center
            ) {
                RaftLucideIcon(
                    RaftLucideIconName.CircleCheck,
                    modifier = Modifier.size(24.dp),
                    color = RaftColor.TextBlackArgb
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                RaftText(
                    text = stringResource(Res.string.member_report_issue_success_body),
                    style = typography.subhead.copy(fontWeight = FontWeight.Bold),
                    color = colors.foreground,
                    textAlign = TextAlign.Center
                )
                RaftText(
                    text =
                        if (ticketId != null) {
                            stringResource(Res.string.member_report_issue_ticket_filed)
                        } else {
                            stringResource(Res.string.member_report_issue_success_hint)
                        },
                    style = typography.callout,
                    color = colors.foreground,
                    textAlign = TextAlign.Center
                )
            }
            if (ticketId == null) {
                // Kirby 10-27: no card around the warning; the reference and one row of buttons follow
                // it, the main action (Retry Ticket) first.
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaftBanner(
                        description = stringResource(Res.string.member_report_issue_ticket_failed),
                        status = RaftBannerStatus.Warning,
                        size = RaftBannerSize.Sm
                    )
                    RaftSectionEyebrow(text = stringResource(Res.string.member_report_issue_reference_label))
                    MembersAgentIssueReportReferenceRow(
                        label = stringResource(Res.string.member_report_issue_report_id),
                        value = receipt.reportId
                    )
                    MembersAgentIssueReportReferenceRow(
                        label = stringResource(Res.string.member_report_issue_server_id),
                        value = receipt.serverId
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onRetryTicket != null && receipt.ticketRequest != null) {
                            RaftButton(
                                text =
                                    if (ticketRetrying) {
                                        stringResource(Res.string.member_report_issue_retrying_ticket)
                                    } else {
                                        stringResource(Res.string.member_report_issue_retry_ticket)
                                    },
                                variant = RaftButtonVariant.Default,
                                size = RaftButtonSize.Md,
                                enabled = !ticketRetrying,
                                onClick = onRetryTicket
                            )
                        }
                        RaftButton(
                            text =
                                if (referenceCopied) {
                                    stringResource(Res.string.member_report_issue_reference_copied)
                                } else {
                                    stringResource(Res.string.member_report_issue_copy_reference)
                                },
                            variant = RaftButtonVariant.Default,
                            size = RaftButtonSize.Md,
                            onClick = onCopyReference
                        )
                    }
                }
            }
            if (receipt.transcriptRequested) {
                receipt.transcriptRequestState?.let { state ->
                    RaftText(
                        text = stringResource(state.copyResource()),
                        style = typography.caption,
                        color = colors.foregroundHint,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        RaftDialogFooter {
            if (ticketId != null && onViewTicket != null) {
                RaftButton(
                    text = stringResource(Res.string.member_report_issue_view_ticket),
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Lg,
                    onClick = { onViewTicket(ticketId) }
                )
            }
            RaftButton(
                text = stringResource(Res.string.common_done),
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
                onClick = onDismiss
            )
        }
    }
}

private fun AgentIssueTranscriptRequestState.copyResource() =
    when (this) {
        AgentIssueTranscriptRequestState.Requested -> Res.string.member_report_issue_transcript_requested
        AgentIssueTranscriptRequestState.RequestFailed -> Res.string.member_report_issue_transcript_request_failed
        AgentIssueTranscriptRequestState.RequestUnconfirmed -> Res.string.member_report_issue_transcript_request_unconfirmed
    }
