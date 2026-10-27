package build.raft.app

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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.components.raftCardSurface
import build.raft.app.data.AgentIssueReportResult
import build.raft.app.data.AgentIssueTranscriptRequestState
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
    val includeMachineLogTail: Boolean
)

/**
 * The machine-log-tail box (web `includeMachineLogTail`): until the reporter touches it, it
 * follows ownership ([canIncludeMachineLog]), so a machine list that loads after the panel opened
 * still ticks it; once touched, the reporter's [choice] wins.
 */
internal fun membersAgentIssueMachineLogTailChecked(choice: Boolean?, canIncludeMachineLog: Boolean): Boolean =
    canIncludeMachineLog && (choice ?: true)

/**
 * The agent "Report Issue" panel, web parity with packages/web/src/components/agent/
 * ReportIssueDialog.tsx: agent card, optional description, the "Include" section (all on by
 * default; runtime transcript needs a machine; the runner log tail only for the reporter's own
 * machine and only with the transcript), and the warning box holding the consent checkbox.
 */
@Composable
internal fun MembersAgentIssueReportDialog(
    agentName: String,
    runtimeTranscriptAvailable: Boolean,
    canIncludeMachineLog: Boolean,
    busy: Boolean,
    errorMessage: String?,
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
    val includeMachineLogTail = membersAgentIssueMachineLogTailChecked(machineLogTailChoice, canIncludeMachineLog)
    RaftUnifiedDialog(
        title = stringResource(Res.string.member_report_issue_title),
        dismissEnabled = !busy,
        keyboardAvoidanceEnabled = true,
        maxWidth = 384.dp,
        contentPadding = 24.dp,
        titleFontSize = 18,
        titleLineHeight = 22,
        onDismiss = onDismiss
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md)
        ) {
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
                    RaftSectionEyebrow(text = stringResource(Res.string.member_report_issue_include))
                    Column(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RaftText(
                            text = stringResource(Res.string.member_report_issue_default_included),
                            style = typography.caption,
                            color = colors.foregroundHint
                        )
                        RaftText(
                            text = stringResource(Res.string.member_report_issue_structured_summary),
                            style = typography.caption,
                            color = colors.foregroundHint
                        )
                    }
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
                    RaftCheckboxRow(
                        label = stringResource(Res.string.member_report_issue_runtime_transcript),
                        description =
                            stringResource(Res.string.member_report_issue_runtime_transcript_hint)
                                .takeIf { includeRuntimeTranscript && runtimeTranscriptAvailable },
                        checked = includeRuntimeTranscript,
                        enabled = runtimeTranscriptAvailable && !busy,
                        onCheckedChange = { includeRuntimeTranscript = it }
                    )
                    if (canIncludeMachineLog) {
                        RaftCheckboxRow(
                            label = stringResource(Res.string.member_report_issue_machine_log_tail),
                            description = stringResource(Res.string.member_report_issue_machine_log_tail_disclosure),
                            checked = includeMachineLogTail,
                            enabled = includeRuntimeTranscript && !busy,
                            onCheckedChange = { machineLogTailChoice = it }
                        )
                    }
                    if (!runtimeTranscriptAvailable) {
                        RaftText(
                            text = stringResource(Res.string.member_report_issue_runtime_transcript_unavailable),
                            style = typography.caption,
                            color = colors.foregroundHint
                        )
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
            errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                item {
                    RaftBanner(
                        description = stringResource(Res.string.member_report_issue_failed, message),
                        status = RaftBannerStatus.Warning,
                        size = RaftBannerSize.Sm
                    )
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
                text =
                    if (busy) {
                        stringResource(Res.string.member_report_issue_submitting)
                    } else {
                        stringResource(Res.string.member_report_issue_submit)
                    },
                leadingIcon = { RaftSlotIcon(RaftLucideIconName.Bug) },
                enabled = consented,
                loading = busy,
                // Web ReportIssueDialog submits with the pink primary action, lg in the footer.
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
                onClick = {
                    onSubmit(
                        MembersAgentIssueReportSelection(
                            description = description.text,
                            includeRecentMessages = includeRecentMessages,
                            includeLiveActivity = includeLiveActivity,
                            includeActivityHistory = includeActivityHistory,
                            includeRuntimeTranscript = includeRuntimeTranscript && runtimeTranscriptAvailable,
                            includeMachineLogTail = includeMachineLogTail
                        )
                    )
                }
            )
        }
    }
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
        titleFontSize = 18,
        titleLineHeight = 22,
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
                    .raftCardSurface(fill = RaftColor.SuccessSoft),
                contentAlignment = Alignment.Center
            ) {
                RaftLucideIcon(
                    RaftLucideIconName.CircleCheckBig,
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
                    color = colors.foregroundMuted,
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
