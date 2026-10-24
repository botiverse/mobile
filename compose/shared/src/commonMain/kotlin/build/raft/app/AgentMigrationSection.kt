package build.raft.app

import build.raft.app.format.RaftFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.Role
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.components.RaftListRowGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftProBadge
import build.raft.app.components.RaftProUpgradeDialog
import build.raft.app.data.AGENT_MIGRATION_STEP_COUNT
import build.raft.app.data.AgentMigrationActionError
import build.raft.app.data.AgentMigrationEffect
import build.raft.app.data.AgentMigrationEnding
import build.raft.app.data.AgentMigrationErrorCodes
import build.raft.app.data.AgentMigrationHostedImportSummary
import build.raft.app.data.AgentMigrationIntent
import build.raft.app.data.AgentMigrationNotice
import build.raft.app.data.AgentMigrationState
import build.raft.app.data.AgentMigrationTarget
import build.raft.app.data.ComputerSizeStatus
import build.raft.app.data.HOSTED_ARRIVAL_ATTENTION_REASONS
import build.raft.app.data.RaftProGateDecision
import build.raft.app.data.canCancel
import build.raft.app.data.endedComputerMigration
import build.raft.app.data.isAborted
import build.raft.app.data.isActive
import build.raft.app.data.isCancelRequested
import build.raft.app.data.isCanceled
import build.raft.app.data.isCompleted
import build.raft.app.data.isFailed
import build.raft.app.data.isHosted
import build.raft.app.data.isHostedReviewPending
import build.raft.app.data.needsAttention
import build.raft.app.data.progressStep
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.RaftDescriptionValue
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSheet
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.components.RaftStep
import build.raft.designsystem.components.RaftStepStatus
import build.raft.designsystem.components.RaftSteps
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.coroutines.delay

/**
 * A computer the migration can name or target (from the server's machine list), with what the
 * target list says about it (web AgentMigrationDialog `TargetComputerList`): its free memory and
 * disk (reported only while online), how many agents it has, and the facts deciding whether the
 * viewer may read its runtime usage.
 */
internal data class AgentMigrationComputer(
    val id: String,
    val name: String,
    val isComputer: Boolean,
    val online: Boolean = false,
    val offline: Boolean = false,
    val lastHeartbeat: String? = null,
    /** Server `agentCount`: active agents on it; null when not known. */
    val agentCount: Int? = null,
    val diskStatus: ComputerSizeStatus? = null,
    val memoryStatus: ComputerSizeStatus? = null,
    val attachedByCurrentUser: Boolean = false,
    val creatorUserId: String? = null
)

/** Everything the section needs besides the store state; built by the detail page. */
internal class AgentMigrationSectionInputs(
    val sourceMachineId: String?,
    val computers: List<AgentMigrationComputer>,
    /** The server's `agent_migration_hosted_v0` gate: offer the cloud as a target. */
    val hostedAvailable: Boolean,
    val proGate: RaftProGateDecision,
    /** Billing is still loading: the entry waits instead of guessing (web `billingChecking`). */
    val proGateChecking: Boolean,
    val onIntent: (AgentMigrationIntent) -> Unit,
    val onOpenBilling: () -> Unit,
    val onCopy: (String) -> Unit,
    val formatTimestamp: (String) -> String?,
    /** A watched move ended with the agent elsewhere: reload the agent (web re-reads agents). */
    val onAgentPlacementChanged: () -> Unit,
    /** The agent's runtime: each target's quota line reads that runtime's account there. */
    val agentRuntime: String? = null
)

/** The target option for the cloud; never a machine id (web `HOSTED_TARGET_CHOICE`). */
internal const val HOSTED_TARGET_CHOICE = "__cloud__"

/** Web profile section: `Separator`, then `px-5 py-4`. */
private val AgentMigrationSectionPaddingX = 20.dp
private val AgentMigrationSectionPaddingY = 16.dp

/** Web `mt-3` between the section's blocks, `gap-3` inside the progress panel. */
private val AgentMigrationBlockGap = 12.dp

/**
 * Web `AgentMigrationSection`: move the agent to another computer or to the
 * cloud, follow the move, cancel it, and confirm the cloud review. A Pro
 * feature: the title carries the Pro tag and, on a plan without it, the
 * entry opens the upgrade sheet instead of the start dialog. Draws its own
 * full-width separator above, as every profile section.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentMigrationSection(
    state: AgentMigrationState,
    inputs: AgentMigrationSectionInputs,
    modifier: Modifier = Modifier
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val notice = state.notice
    val ended = notice?.endedComputerMigration()
    val active = notice?.isActive == true
    val retryOnTarget = ended == AgentMigrationEnding.Failed && notice.flippedAt == null
    var startDialogTarget by remember { mutableStateOf<String?>(null) }
    var showStartDialog by remember { mutableStateOf(false) }
    var showUpgrade by remember { mutableStateOf(false) }
    var showCancel by remember { mutableStateOf(false) }

    fun openStart(target: String?) {
        if (inputs.proGate == RaftProGateDecision.UpgradeRequired) {
            showUpgrade = true
            return
        }
        inputs.onIntent(AgentMigrationIntent.ClearStartError)
        startDialogTarget = target
        showStartDialog = true
    }

    Column(modifier = modifier.fillMaxWidth()) {
        RaftSeparator()
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AgentMigrationSectionPaddingX, vertical = AgentMigrationSectionPaddingY)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RaftSectionEyebrow(text = stringResource(MR.strings.agent_migration_section_title))
                RaftProBadge()
            }
            val showCancelDialog = {
                inputs.onIntent(AgentMigrationIntent.ClearCancelError)
                showCancel = true
            }
            // What migration does, unless a running (or unsettled) migration's block is shown instead.
            if (notice == null || ended != null || notice.isCompleted || notice.isCanceled) {
                RaftText(
                    text = stringResource(MR.strings.agent_migration_section_description),
                    modifier = Modifier.padding(top = 4.dp),
                    style = typography.caption,
                    color = colors.foregroundHint
                )
            }
            // An ended move between computers (other than a cancel) folds into one line (web
            // LastMigration, botiverse/slock #10951); a new migration starts folded again.
            if (notice != null && (ended == AgentMigrationEnding.Completed || ended == AgentMigrationEnding.Failed)) {
                key(notice.migrationRef) {
                    AgentLastMigration(
                        notice = notice,
                        state = state,
                        inputs = inputs,
                        onShowCancel = showCancelDialog,
                        modifier = Modifier.padding(top = AgentMigrationBlockGap)
                    )
                }
            }
            // Running moves, moves to the cloud and unsettled cancels keep the progress block.
            if (notice != null && ended == null) {
                AgentMigrationProgressPanel(
                    notice = notice,
                    state = state,
                    inputs = inputs,
                    onShowCancel = showCancelDialog,
                    modifier = Modifier.padding(top = AgentMigrationBlockGap)
                )
            }
            state.statusError?.let { error ->
                RaftBanner(
                    title = stringResource(MR.strings.agent_migration_detail_migration_status_unavailable),
                    description = agentMigrationErrorText(error),
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                    modifier = Modifier.padding(top = AgentMigrationBlockGap)
                )
            }
            // While a migration runs, the progress block owns the only action (Cancel).
            if (!active && state.loaded) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = AgentMigrationBlockGap),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (retryOnTarget) {
                        RaftButton(
                            text = stringResource(MR.strings.agent_migration_detail_try_migration_again),
                            onClick = { openStart(notice?.targetMachineId) },
                            size = RaftButtonSize.Md,
                            enabled = !inputs.proGateChecking
                        )
                    }
                    RaftButton(
                        text =
                            stringResource(
                                when {
                                    retryOnTarget -> MR.strings.agent_migration_migrate_elsewhere
                                    // A failed move to the cloud keeps its single Try Again; a computer move
                                    // that failed after the switch starts over from where the agent now is.
                                    notice?.isFailed == true && ended != AgentMigrationEnding.Failed ->
                                        MR.strings.agent_migration_detail_try_migration_again
                                    inputs.hostedAvailable -> MR.strings.agent_migration_hosted_start_entry
                                    else -> MR.strings.agent_migration_detail_start_migration
                                }
                            ),
                        onClick = { openStart(null) },
                        size = RaftButtonSize.Md,
                        enabled = !inputs.proGateChecking
                    )
                }
            }
        }
    }

    LaunchedEffect(state.effect) {
        when (state.effect) {
            AgentMigrationEffect.Started -> showStartDialog = false
            AgentMigrationEffect.ProRequired -> {
                showStartDialog = false
                showUpgrade = true
            }
            AgentMigrationEffect.AgentPlacementChanged -> inputs.onAgentPlacementChanged()
            null -> return@LaunchedEffect
        }
        inputs.onIntent(AgentMigrationIntent.ConsumeEffect)
    }
    if (showStartDialog) {
        AgentMigrationStartDialog(
            state = state,
            inputs = inputs,
            initialTarget = startDialogTarget,
            onStart = { inputs.onIntent(AgentMigrationIntent.Start(it)) },
            onDismiss = { if (!state.starting) showStartDialog = false }
        )
    }
    if (showUpgrade) {
        RaftProUpgradeDialog(
            title = stringResource(MR.strings.raft_pro_migration_title),
            description = stringResource(MR.strings.raft_pro_migration_description),
            onUpgrade = {
                showUpgrade = false
                inputs.onOpenBilling()
            },
            onDismiss = { showUpgrade = false }
        )
    }
    // Web ConfirmDialog: a cancel that went through closes the dialog; a failed one keeps it
    // open with the error. It shows only while the move can still be canceled.
    var cancelSent by remember { mutableStateOf(false) }
    LaunchedEffect(state.canceling, state.cancelError) {
        if (state.canceling) {
            cancelSent = true
        } else if (cancelSent) {
            cancelSent = false
            if (state.cancelError == null) showCancel = false
        }
    }
    if (showCancel && notice != null && (notice.canCancel() || state.canceling)) {
        AgentMigrationCancelDialog(
            notice = notice,
            state = state,
            inputs = inputs,
            onConfirm = { inputs.onIntent(AgentMigrationIntent.Cancel) },
            onDismiss = { if (!state.canceling) showCancel = false }
        )
    }
}

@Composable
private fun agentMigrationComputerLabel(inputs: AgentMigrationSectionInputs, machineId: String?): String {
    if (machineId.isNullOrBlank()) return stringResource(MR.strings.agent_migration_detail_unknown_computer)
    return inputs.computers.firstOrNull { it.id == machineId }?.name ?: machineId.take(8)
}

@Composable
private fun agentMigrationTargetLabel(inputs: AgentMigrationSectionInputs, notice: AgentMigrationNotice): String =
    if (notice.isHosted) {
        stringResource(MR.strings.agent_migration_hosted_target_name)
    } else {
        agentMigrationComputerLabel(inputs, notice.targetMachineId)
    }

/** Web `lastMigrationFacts`: where an ended move between computers went, who started it, and when it ended. */
private data class AgentLastMigrationFacts(val source: String, val target: String, val startedBy: String?, val endedAt: String?)

@Composable
private fun agentLastMigrationFacts(notice: AgentMigrationNotice, inputs: AgentMigrationSectionInputs): AgentLastMigrationFacts {
    val endedAt = (if (notice.isFailed) notice.abortedAt else notice.completedAt) ?: notice.updatedAt
    return AgentLastMigrationFacts(
        source = agentMigrationComputerLabel(inputs, notice.sourceMachineId),
        target = agentMigrationTargetLabel(inputs, notice),
        startedBy = notice.initiatedBy?.label,
        endedAt = endedAt?.let(inputs.formatTimestamp)
    )
}

/**
 * Web `LastMigration` (botiverse/slock 24873fd5be37, #10951): the last move between computers inside
 * the migration section, between its description and buttons. Folded it is one line (Last
 * migration: from → to · who · when · outcome) and the whole line toggles the details, in an inset
 * group: the facts and the migration ID for a completed move; the stopped steps, why, and the ID for
 * a failed one. A failure starts open because the user has to read why before trying again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentLastMigration(
    notice: AgentMigrationNotice,
    state: AgentMigrationState,
    inputs: AgentMigrationSectionInputs,
    onShowCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val failed = notice.isFailed
    var open by remember { mutableStateOf(failed) }
    val facts = agentLastMigrationFacts(notice, inputs)
    val outcome = stringResource(if (failed) MR.strings.agent_migration_last_failed_outcome else MR.strings.agent_migration_last_completed)
    val summary =
        buildAnnotatedString {
            append(
                (listOf(stringResource(MR.strings.agent_migration_last_route, facts.source, facts.target)) +
                    listOfNotNull(facts.startedBy, facts.endedAt) + "")
                    .joinToString(" · ")
            )
            // The outcome is a word, not a status dot: dots are for presence and tasks.
            if (failed) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.foreground)) { append(outcome) }
            } else {
                append(outcome)
            }
        }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AgentMigrationBlockGap)) {
        // One row is the toggle: chevron, label, summary.
        Row(
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open },
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RaftLucideIcon(
                name = if (open) RaftLucideIconName.ChevronDown else RaftLucideIconName.ChevronRight,
                modifier = Modifier.padding(top = 2.dp).size(16.dp),
                color = raftArgbHex(colors.foregroundMuted)
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // A small label inside the migration section, not a second section heading.
                RaftText(
                    text = stringResource(MR.strings.agent_migration_last_title),
                    style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foregroundMuted
                )
                RaftText(text = summary, style = typography.caption, color = colors.foregroundMuted)
            }
        }
        if (open) {
            // The DS inset group holds the details, so the steps and banner read as this row's.
            RaftListRowGroup(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    if (failed) {
                        AgentMigrationProgressPanel(notice = notice, state = state, inputs = inputs, onShowCancel = onShowCancel)
                    } else {
                        RaftDescriptionList(direction = RaftDescriptionListDirection.Horizontal) {
                            Item(term = stringResource(MR.strings.agent_migration_last_source), value = facts.source)
                            Item(term = stringResource(MR.strings.agent_migration_detail_target_computer), value = facts.target)
                            facts.startedBy?.let { Item(term = stringResource(MR.strings.agent_migration_last_started_by), value = it) }
                            facts.endedAt?.let { Item(term = stringResource(MR.strings.agent_migration_last_finished_at), value = it) }
                            Item(term = stringResource(MR.strings.agent_migration_reference_label)) {
                                AgentMigrationReferenceValue(notice, inputs)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun agentMigrationErrorText(error: AgentMigrationActionError): String {
    val base = error.serverMessage ?: stringResource(MR.strings.agent_migration_generic_error)
    return base + "\n" + stringResource(MR.strings.agent_migration_error_code, error.code)
}

/** Web `migrationNoticePresentation`: a title (when the state has one) and the line under it. */
private data class AgentMigrationNoticeCopy(val title: String?, val placement: String?, val message: String)

@Composable
private fun agentMigrationNoticeCopy(notice: AgentMigrationNotice, inputs: AgentMigrationSectionInputs): AgentMigrationNoticeCopy {
    val source = agentMigrationComputerLabel(inputs, notice.sourceMachineId)
    val target = agentMigrationTargetLabel(inputs, notice)
    fun status(message: String) = AgentMigrationNoticeCopy(null, null, message)
    val hostedAttention =
        notice.isHosted && (notice.state == "arriving" || notice.state == "starting") &&
            notice.failureReason in HOSTED_ARRIVAL_ATTENTION_REASONS
    if (hostedAttention) {
        return AgentMigrationNoticeCopy(
            stringResource(MR.strings.agent_migration_hosted_header_needs_attention),
            null,
            stringResource(MR.strings.agent_migration_hosted_failed_after_move)
        )
    }
    if (notice.isHosted) {
        when (notice.state) {
            "ready" -> return status(stringResource(MR.strings.agent_migration_hosted_checking_files))
            "in_transit" ->
                return status(
                    if (notice.isHostedReviewPending()) {
                        stringResource(MR.strings.agent_migration_hosted_waiting_for_review, source)
                    } else {
                        stringResource(MR.strings.agent_migration_hosted_checking_files)
                    }
                )
            "arriving", "starting" -> return status(stringResource(MR.strings.agent_migration_hosted_switching))
        }
    }
    if (notice.isFailed) {
        val title =
            stringResource(
                if (notice.isAborted) MR.strings.agent_migration_detail_migration_aborted else MR.strings.agent_migration_detail_migration_failed
            )
        return if (notice.flippedAt != null) {
            AgentMigrationNoticeCopy(
                title,
                null,
                if (notice.isHosted) {
                    stringResource(MR.strings.agent_migration_hosted_failed_after_move)
                } else {
                    stringResource(MR.strings.agent_migration_detail_migration_failed_after_move, source, target)
                }
            )
        } else {
            val reason = notice.transportErrorCode ?: notice.failureReason ?: notice.abortReason
            AgentMigrationNoticeCopy(
                title,
                stringResource(MR.strings.agent_migration_detail_migration_still_on_source, source),
                notice.transportErrorMessage?.takeIf { it.isNotBlank() }
                    ?: reason?.let { stringResource(MR.strings.agent_migration_error_code, it) }
                    ?: stringResource(MR.strings.agent_migration_generic_error)
            )
        }
    }
    return when (notice.state) {
        "cancel_requested_pre_flip" ->
            if (notice.cancelNeedsAttention) {
                AgentMigrationNoticeCopy(
                    stringResource(MR.strings.agent_migration_cancellation_needs_attention),
                    null,
                    stringResource(MR.strings.agent_migration_cancel_pre_attention_message)
                )
            } else {
                AgentMigrationNoticeCopy(
                    stringResource(MR.strings.agent_migration_canceling_title),
                    null,
                    if (notice.isHosted) {
                        stringResource(MR.strings.agent_migration_hosted_canceling_message, source)
                    } else {
                        stringResource(MR.strings.agent_migration_cancel_pre_message)
                    }
                )
            }
        "cancel_requested_post_flip" ->
            if (notice.cancelNeedsAttention) {
                AgentMigrationNoticeCopy(
                    stringResource(MR.strings.agent_migration_cancellation_needs_attention),
                    null,
                    stringResource(MR.strings.agent_migration_cancel_post_attention_message)
                )
            } else {
                AgentMigrationNoticeCopy(
                    stringResource(MR.strings.agent_migration_stopping_migrated_agent_title),
                    null,
                    stringResource(MR.strings.agent_migration_cancel_post_message)
                )
            }
        "canceled_pre_flip" ->
            AgentMigrationNoticeCopy(
                stringResource(MR.strings.agent_migration_canceled_title),
                null,
                if (notice.isHosted) {
                    stringResource(MR.strings.agent_migration_hosted_canceled_message, source)
                } else {
                    stringResource(MR.strings.agent_migration_canceled_pre_message)
                }
            )
        "canceled_post_flip" ->
            AgentMigrationNoticeCopy(
                stringResource(MR.strings.agent_migration_canceled_title),
                null,
                stringResource(MR.strings.agent_migration_canceled_post_message)
            )
        "provisioning" ->
            status(
                when {
                    notice.transportControlRegisteredAt != null ->
                        if (notice.isHosted) {
                            stringResource(MR.strings.agent_migration_hosted_uploading, source)
                        } else {
                            stringResource(MR.strings.agent_migration_detail_migration_uploading, source, target)
                        }
                    notice.sourceQuiescedAt == null ->
                        stringResource(MR.strings.agent_migration_detail_migration_stopping_source, source)
                    else -> stringResource(MR.strings.agent_migration_detail_migration_packing, source)
                }
            )
        "prep" -> status(stringResource(MR.strings.agent_migration_detail_migration_packing, source))
        "ready" -> {
            val progress = notice.transferProgress
            status(
                if (progress != null && progress.totalChunks > 0 && progress.receivedChunks >= progress.totalChunks) {
                    stringResource(MR.strings.agent_migration_detail_migration_unpacking, source, target)
                } else {
                    stringResource(MR.strings.agent_migration_detail_migration_ready, source, target)
                }
            )
        }
        "in_transit" -> status(stringResource(MR.strings.agent_migration_detail_migration_unpacking, source, target))
        "arriving" -> status(stringResource(MR.strings.agent_migration_detail_migration_arriving, source, target))
        "starting" ->
            if (notice.failureReason == "auto_start_failed") {
                AgentMigrationNoticeCopy(
                    stringResource(MR.strings.agent_migration_detail_agent_did_not_start),
                    null,
                    stringResource(MR.strings.agent_migration_detail_agent_did_not_start_message, source, target)
                )
            } else {
                status(stringResource(MR.strings.agent_migration_detail_migration_starting, source, target))
            }
        "completed" ->
            status(
                if (notice.isHosted) {
                    stringResource(MR.strings.agent_migration_hosted_completed)
                } else {
                    stringResource(MR.strings.agent_migration_detail_migration_completed, source, target)
                }
            )
        else -> status(stringResource(MR.strings.agent_migration_error_code, notice.state))
    }
}

/** Web MigrationProgressPanel `failed`: the move failed, or stalled where only a person can help. */
internal fun AgentMigrationNotice.failedLook(): Boolean =
    isFailed ||
        (state == "starting" && failureReason == "auto_start_failed") ||
        (isHosted && (state == "arriving" || state == "starting") && failureReason in HOSTED_ARRIVAL_ATTENTION_REASONS)

/** How the step at [index] (from 1) marks itself: its number, or a spinner while it runs. */
internal enum class AgentMigrationStepMarker { Number, Spinner }

/**
 * The progress Steps of [MigrationProgressPanel]: [current] (from 1) and its [status] as the web
 * passes them to raft-ui `Steps`. The step in progress spins while the move works on it; a
 * move waiting for the user's review shows its number.
 */
internal data class AgentMigrationStepsModel(val current: Int, val status: RaftStepStatus, val running: Boolean) {
    fun marker(index: Int): AgentMigrationStepMarker =
        if (running && index == current && status == RaftStepStatus.Process) AgentMigrationStepMarker.Spinner else AgentMigrationStepMarker.Number
}

internal fun agentMigrationStepsModel(notice: AgentMigrationNotice): AgentMigrationStepsModel {
    val completed = notice.isCompleted
    val status =
        when {
            completed -> RaftStepStatus.Finish
            notice.failedLook() || notice.cancelNeedsAttention -> RaftStepStatus.Error
            notice.isCanceled -> RaftStepStatus.Wait
            else -> RaftStepStatus.Process
        }
    val current = if (completed) AGENT_MIGRATION_STEP_COUNT else notice.progressStep() + 1
    return AgentMigrationStepsModel(current, status, running = notice.isActive && !notice.isHostedReviewPending())
}

@Composable
private fun AgentMigrationProgressSteps(notice: AgentMigrationNotice) {
    val model = agentMigrationStepsModel(notice)
    val labels =
        if (notice.isHosted) {
            listOf(
                MR.strings.agent_migration_hosted_step_upload,
                MR.strings.agent_migration_hosted_step_check_files,
                MR.strings.agent_migration_hosted_step_review,
                MR.strings.agent_migration_hosted_step_switch,
                MR.strings.agent_migration_hosted_step_complete
            )
        } else {
            listOf(
                MR.strings.agent_migration_detail_migration_step_stop_agent,
                MR.strings.agent_migration_detail_migration_step_pack_workspace,
                MR.strings.agent_migration_detail_migration_step_transfer,
                MR.strings.agent_migration_detail_migration_step_start_on_target,
                MR.strings.agent_migration_detail_migration_step_complete
            )
        }
    val spinner: @Composable () -> Unit = { RaftSpinner(size = RaftSpinnerSize.Xs) }
    RaftSteps(
        steps =
            labels.mapIndexed { offset, label ->
                RaftStep(
                    title = stringResource(label),
                    icon = if (model.marker(offset + 1) == AgentMigrationStepMarker.Spinner) spinner else null
                )
            },
        current = model.current,
        status = model.status,
        finishLabel = stringResource(MR.strings.agent_migration_step_done),
        errorLabel = stringResource(MR.strings.agent_migration_step_failed)
    )
}

/** Web MigrationErrorContent: where the agent is now (bold), then why. */
private fun agentMigrationNoticeText(copy: AgentMigrationNoticeCopy, boldPlacement: Boolean): AnnotatedString =
    buildAnnotatedString {
        copy.placement?.let { placement ->
            if (boldPlacement) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(placement) } else append(placement)
            append("\n")
        }
        append(copy.message)
    }

@Composable
private fun AgentMigrationProgressPanel(
    notice: AgentMigrationNotice,
    state: AgentMigrationState,
    inputs: AgentMigrationSectionInputs,
    onShowCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val copy = agentMigrationNoticeCopy(notice, inputs)
    // A failed move between computers (the open "Last migration" row): the steps, stopped at the
    // one that failed, then why, then the reference (web MigrationProgressPanel, botiverse/slock
    // #10951). The row's summary already names the move, so the banner has no title.
    if (notice.endedComputerMigration() == AgentMigrationEnding.Failed) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AgentMigrationBlockGap)) {
            AgentMigrationProgressSteps(notice)
            RaftBanner(
                description = agentMigrationNoticeText(copy, boldPlacement = false),
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm
            )
            RaftDescriptionList(direction = RaftDescriptionListDirection.Horizontal) {
                Item(term = stringResource(MR.strings.agent_migration_reference_label)) {
                    AgentMigrationReferenceValue(notice, inputs)
                }
            }
        }
        return
    }
    val failed = notice.failedLook()
    val attention = notice.needsAttention()
    val target = agentMigrationTargetLabel(inputs, notice)
    val header =
        when {
            notice.isCompleted ->
                if (notice.isHosted) stringResource(MR.strings.agent_migration_hosted_header_completed)
                else stringResource(MR.strings.agent_migration_detail_moved_to_target, "", target)
            notice.isCanceled ->
                if (notice.isHosted) stringResource(MR.strings.agent_migration_hosted_header_canceled)
                else stringResource(MR.strings.agent_migration_detail_migration_to_target_canceled, "", target)
            attention ->
                if (notice.isHosted) stringResource(MR.strings.agent_migration_hosted_header_needs_attention)
                else stringResource(MR.strings.agent_migration_detail_migration_to_target_needs_attention, "", target)
            notice.isCancelRequested ->
                if (notice.isHosted) stringResource(MR.strings.agent_migration_hosted_header_canceling)
                else stringResource(MR.strings.agent_migration_detail_canceling_migration_to_target, "", target)
            notice.isActive ->
                if (notice.isHosted) stringResource(MR.strings.agent_migration_hosted_header_active)
                else stringResource(MR.strings.agent_migration_detail_moving_to_target, "", target)
            else ->
                if (notice.isHosted) stringResource(MR.strings.agent_migration_hosted_header_idle)
                else stringResource(MR.strings.agent_migration_detail_migration_to_target, "", target)
        }
    val reviewSummary = if (notice.isHostedReviewPending()) notice.hosted?.importSummary else null
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AgentMigrationBlockGap)) {
        RaftText(text = header, style = typography.subhead.copy(fontWeight = FontWeight.SemiBold), color = colors.foreground)
        AgentMigrationProgressSteps(notice)
        // Problems keep the warning banner; normal progress is plain text.
        if (attention) {
            RaftBanner(
                title = copy.title.takeIf { failed || notice.isCancelRequested },
                description = agentMigrationNoticeText(copy, boldPlacement = true),
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm
            )
            // The reference is for support, so it only appears when something needs attention.
            AgentMigrationReference(notice, inputs)
        } else {
            Column {
                copy.title?.takeIf { notice.isCancelRequested }?.let {
                    RaftText(text = it, style = typography.callout.copy(fontWeight = FontWeight.SemiBold), color = colors.foreground)
                }
                RaftText(text = copy.message, style = typography.callout, color = colors.foregroundMuted)
            }
        }
        if (reviewSummary != null) {
            AgentMigrationHostedReviewCard(notice, reviewSummary, state, inputs, onShowCancel)
        } else if (notice.isActive && notice.canCancel()) {
            // While the review waits, its own Cancel sits beside Confirm.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                RaftButton(
                    text = stringResource(MR.strings.agent_migration_detail_cancel),
                    onClick = onShowCancel,
                    size = RaftButtonSize.Md
                )
            }
        }
    }
}

/** Web MigrationReference: the label, then [AgentMigrationReferenceValue]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentMigrationReference(notice: AgentMigrationNotice, inputs: AgentMigrationSectionInputs) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.Center
    ) {
        RaftText(
            text = stringResource(MR.strings.agent_migration_reference_label),
            modifier = Modifier.align(Alignment.CenterVertically),
            style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
            color = RaftTheme.colors.foregroundMuted
        )
        AgentMigrationReferenceValue(notice, inputs)
    }
}

/**
 * Web MigrationReferenceValue: the ID in Space Mono and a copy button that turns into a check for
 * 2s, for a row whose label sits elsewhere.
 */
@Composable
private fun AgentMigrationReferenceValue(notice: AgentMigrationNotice, inputs: AgentMigrationSectionInputs) {
    var copied by remember(notice.migrationRef) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftText(
            text = notice.migrationRef,
            modifier = Modifier.weight(1f, fill = false),
            style = RaftTheme.typography.monoMeta,
            color = RaftTheme.colors.foreground
        )
        RaftIconButton(
            onClick = {
                inputs.onCopy(notice.migrationRef)
                copied = true
            },
            tooltip =
                stringResource(
                    if (copied) MR.strings.agent_migration_reference_copied else MR.strings.agent_migration_reference_copy
                ),
            variant = RaftButtonVariant.Ghost
        ) {
            RaftGlyphIcon(if (copied) RaftGlyph.Check else RaftGlyph.Copy, size = 14.dp)
        }
    }
}

/** Web HostedMigrationReview: a Card with what moves and what stays, and the final Confirm. */
@Composable
private fun AgentMigrationHostedReviewCard(
    notice: AgentMigrationNotice,
    summary: AgentMigrationHostedImportSummary,
    state: AgentMigrationState,
    inputs: AgentMigrationSectionInputs,
    onShowCancel: () -> Unit
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val source = agentMigrationComputerLabel(inputs, notice.sourceMachineId)
    val skipped = summary.skipped.filter { it.count > 0 }
    RaftCard(
        modifier = Modifier.fillMaxWidth(),
        title = stringResource(MR.strings.agent_migration_hosted_review_title),
        footer = {
            Spacer(Modifier.weight(1f))
            RaftButton(
                text = stringResource(MR.strings.agent_migration_detail_cancel),
                onClick = onShowCancel,
                size = RaftButtonSize.Md,
                enabled = !state.confirming && notice.canCancel()
            )
            RaftButton(
                text = stringResource(MR.strings.agent_migration_hosted_confirm),
                onClick = { inputs.onIntent(AgentMigrationIntent.ConfirmHosted) },
                variant = RaftButtonVariant.Warning,
                size = RaftButtonSize.Md,
                loading = state.confirming,
                loadingLabel = stringResource(MR.strings.agent_migration_hosted_confirming)
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AgentMigrationBlockGap)) {
            RaftDescriptionList {
                Item(
                    term = stringResource(MR.strings.agent_migration_hosted_review_imported_term),
                    value = agentMigrationFileCount(summary.importedFileCount, summary.importedBytes),
                    copyable = false
                )
                if (skipped.isEmpty()) {
                    Item(
                        term = stringResource(MR.strings.agent_migration_hosted_review_skipped_none),
                        value = stringResource(MR.strings.agent_migration_hosted_review_nothing_skipped),
                        copyable = false
                    )
                } else {
                    skipped.forEach { group ->
                        val shown = group.largestPaths.take(5)
                        val more = group.count - shown.size
                        Item(term = stringResource(MR.strings.agent_migration_hosted_review_skipped_term, agentMigrationSkipReason(group.reason))) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                RaftDescriptionValue(agentMigrationFileCount(group.count, group.bytes), copyable = false)
                                shown.forEach { path ->
                                    RaftText(text = path, style = typography.monoMeta, color = colors.foregroundMuted)
                                }
                                if (shown.isNotEmpty() && more > 0) {
                                    RaftText(
                                        text = stringResource(MR.strings.agent_migration_hosted_review_more_paths, more),
                                        style = typography.caption,
                                        color = colors.foregroundHint
                                    )
                                }
                            }
                        }
                    }
                }
            }
            RaftText(
                text = stringResource(MR.strings.agent_migration_hosted_review_final, source),
                style = typography.callout,
                color = colors.foreground
            )
            notice.transferDeadlineAt?.let(inputs.formatTimestamp)?.let { deadline ->
                RaftText(
                    text = stringResource(MR.strings.agent_migration_hosted_review_deadline, source, "", deadline),
                    style = typography.caption,
                    color = colors.foregroundHint
                )
            }
            state.confirmError?.let { error ->
                RaftBanner(description = agentMigrationErrorText(error), status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
            }
        }
    }
}

@Composable
private fun agentMigrationFileCount(files: Int, bytes: Long): String {
    val size = RaftFormat.bytes(bytes)
    return if (files == 1) {
        stringResource(MR.strings.agent_migration_hosted_review_file_count_one, files, size)
    } else {
        stringResource(MR.strings.agent_migration_hosted_review_file_count_other, files, size)
    }
}

@Composable
private fun agentMigrationSkipReason(reason: String): String =
    stringResource(
        when (reason) {
            "symlink" -> MR.strings.agent_migration_hosted_skip_symlink
            "git" -> MR.strings.agent_migration_hosted_skip_git
            "raft_state" -> MR.strings.agent_migration_hosted_skip_raft_state
            "path_rule" -> MR.strings.agent_migration_hosted_skip_path_rule
            "reserved" -> MR.strings.agent_migration_hosted_skip_reserved
            "file_too_large" -> MR.strings.agent_migration_hosted_skip_file_too_large
            "not_text" -> MR.strings.agent_migration_hosted_skip_not_text
            "over_budget" -> MR.strings.agent_migration_hosted_skip_over_budget
            "provider_rejected" -> MR.strings.agent_migration_hosted_skip_provider_rejected
            else -> MR.strings.agent_migration_hosted_skip_other
        }
    )

/** The computers a move can go to: attached computers other than the agent's own. */
internal fun agentMigrationTargets(inputs: AgentMigrationSectionInputs): List<AgentMigrationComputer> =
    inputs.computers.filter { it.isComputer && it.id != inputs.sourceMachineId }

/**
 * Web AgentMigrationDialog's pick: the [choice] if it is offered, else the first computer; the
 * cloud when it is chosen or is the only place to go. Null: nowhere to go.
 */
internal fun agentMigrationResolvedTarget(
    choice: String?,
    targets: List<AgentMigrationComputer>,
    hostedAvailable: Boolean
): String? =
    when {
        hostedAvailable && (choice == HOSTED_TARGET_CHOICE || targets.isEmpty()) -> HOSTED_TARGET_CHOICE
        targets.any { it.id == choice } -> choice
        else -> targets.firstOrNull()?.id
    }

/**
 * Web AgentMigrationDialog (`narrow="sheet"`): on a phone a 90% sheet with × at the start and
 * the one commit button at the end of its head; on a tablet the centred dialog.
 */
@Composable
private fun AgentMigrationStartDialog(
    state: AgentMigrationState,
    inputs: AgentMigrationSectionInputs,
    initialTarget: String?,
    onStart: (AgentMigrationTarget) -> Unit,
    onDismiss: () -> Unit
) {
    val targets = remember(inputs.computers, inputs.sourceMachineId) { agentMigrationTargets(inputs) }
    var choice by remember(targets, initialTarget) {
        mutableStateOf(targets.firstOrNull { it.id == initialTarget }?.id ?: targets.firstOrNull()?.id)
    }
    val selected = agentMigrationResolvedTarget(choice, targets, inputs.hostedAvailable)
    // Read here: the sheet's content is hosted outside the page, where the local does not reach.
    val usage = LocalMembersRuntimeAccountUsageAccess.current
    RaftDialogSheet(
        onDismissRequest = { if (!state.starting) onDismiss() },
        title = agentMigrationStartDialogTitle(inputs),
        commitLabel = agentMigrationStartCommitLabel(state, inputs, selected),
        onCommit = {
            val target = selected ?: return@RaftDialogSheet
            onStart(if (target == HOSTED_TARGET_CHOICE) AgentMigrationTarget.Cloud else AgentMigrationTarget.Computer(target))
        },
        commitEnabled = !state.starting && selected != null,
        // Migrating pauses the agent but can be undone (migrate back): raft-ui button.md
        // "Which color" puts interrupting, reversible actions on warning (orange).
        commitVariant = RaftButtonVariant.Warning,
        closeLabel = stringResource(MR.strings.common_close),
        cancelLabel = stringResource(MR.strings.agent_migration_detail_cancel)
    ) {
        AgentMigrationStartForm(state, inputs, targets, selected, onSelect = { choice = it }, usage = usage)
    }
}

@Composable
internal fun agentMigrationStartDialogTitle(inputs: AgentMigrationSectionInputs): String =
    stringResource(
        if (inputs.hostedAvailable) MR.strings.agent_migration_hosted_dialog_title
        else MR.strings.agent_migration_detail_move_to_another_computer
    )

@Composable
internal fun agentMigrationStartCommitLabel(state: AgentMigrationState, inputs: AgentMigrationSectionInputs, selected: String?): String =
    stringResource(
        when {
            state.starting -> MR.strings.agent_migration_detail_starting
            selected == HOSTED_TARGET_CHOICE -> MR.strings.agent_migration_hosted_start
            // Title "Move Agent" offers both destinations, so the button keeps one.
            inputs.hostedAvailable -> MR.strings.agent_migration_detail_start_migration
            else -> MR.strings.agent_migration_move_confirm
        }
    )

/**
 * The dialog's body: the target picked from an always-open list ([AgentMigrationTargetList]),
 * then what happens next. [initialQuery] is for fixtures.
 */
@Composable
internal fun ColumnScope.AgentMigrationStartForm(
    state: AgentMigrationState,
    inputs: AgentMigrationSectionInputs,
    targets: List<AgentMigrationComputer>,
    selected: String?,
    onSelect: (String) -> Unit,
    usage: MembersRuntimeAccountUsageAccess? = null,
    initialQuery: String = ""
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val hostedSelected = selected == HOSTED_TARGET_CHOICE
    val source = agentMigrationComputerLabel(inputs, inputs.sourceMachineId)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        RaftText(
            text =
                stringResource(
                    if (inputs.hostedAvailable) MR.strings.agent_migration_hosted_target_label
                    else MR.strings.agent_migration_detail_target_computer
                ),
            style = typography.subhead.copy(fontWeight = FontWeight.SemiBold),
            color = colors.foreground
        )
        // Choosing is the dialog's job: an always-open list at any count (raft-design #767).
        AgentMigrationTargetList(
            targets = targets,
            hostedOption = if (inputs.hostedAvailable) stringResource(MR.strings.agent_migration_hosted_option) else null,
            selected = selected,
            enabled = !state.starting,
            agentRuntime = inputs.agentRuntime,
            usage = usage,
            onSelect = onSelect,
            initialQuery = initialQuery
        )
        // The one choice first; what happens next follows as the dialog's body text.
        if (hostedSelected) {
            // Said before starting: the move to the cloud is final once confirmed.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    stringResource(MR.strings.agent_migration_hosted_note_stopped, source),
                    stringResource(MR.strings.agent_migration_hosted_note_files, source),
                    stringResource(MR.strings.agent_migration_hosted_note_session),
                    stringResource(MR.strings.agent_migration_hosted_note_github),
                    stringResource(MR.strings.agent_migration_hosted_note_final)
                ).forEach { note ->
                    Row {
                        RaftText(text = "•", modifier = Modifier.width(20.dp), style = typography.subhead, color = colors.foregroundMuted)
                        RaftText(text = note, modifier = Modifier.weight(1f), style = typography.subhead, color = colors.foregroundMuted)
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RaftText(
                    text = stringResource(MR.strings.agent_migration_detail_migration_mode_stop_before_export),
                    style = typography.subhead,
                    color = colors.foregroundMuted
                )
                RaftText(
                    text = stringResource(MR.strings.agent_migration_detail_migration_session_reset_detailed),
                    style = typography.subhead,
                    color = colors.foregroundMuted
                )
            }
        }
        state.startError?.let { error ->
            RaftBanner(description = agentMigrationErrorText(error), status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
        }
    }
}

/** Web AgentMigrationCancelDialog (a ConfirmDialog, `max-w-md`, orange confirm). */
@Composable
private fun AgentMigrationCancelDialog(
    notice: AgentMigrationNotice,
    state: AgentMigrationState,
    inputs: AgentMigrationSectionInputs,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val source = agentMigrationComputerLabel(inputs, notice.sourceMachineId)
    val target = agentMigrationComputerLabel(inputs, notice.targetMachineId)
    RaftDialog(
        onDismissRequest = { if (!state.canceling) onDismiss() },
        title = stringResource(MR.strings.agent_migration_cancel_dialog_title),
        size = RaftDialogSize.Md,
        dismissOnClickOutside = false,
        closeLabel = stringResource(MR.strings.common_close),
        footer = {
            RaftButton(
                text = stringResource(MR.strings.agent_migration_cancel_dialog_keep_action),
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                enabled = !state.canceling
            )
            RaftButton(
                text = stringResource(MR.strings.agent_migration_cancel_dialog_confirm),
                onClick = onConfirm,
                variant = RaftButtonVariant.Warning,
                size = RaftButtonSize.Lg,
                enabled = notice.canCancel(),
                loading = state.canceling,
                loadingLabel = stringResource(MR.strings.agent_migration_cancel_dialog_requesting)
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AgentMigrationBlockGap)) {
            Column {
                RaftText(
                    text = stringResource(MR.strings.agent_migration_cancel_dialog_safety_title),
                    style = typography.subhead.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foreground
                )
                RaftText(
                    text =
                        if (notice.isHosted) {
                            stringResource(MR.strings.agent_migration_hosted_cancel_dialog_safe_message, source)
                        } else {
                            stringResource(MR.strings.agent_migration_cancel_dialog_safe_message, source, target)
                        },
                    style = typography.subhead,
                    color = colors.foregroundMuted
                )
            }
            AgentMigrationReference(notice, inputs)
            RaftText(
                text =
                    if (notice.isHosted) {
                        stringResource(MR.strings.agent_migration_hosted_cancel_dialog_explanation, source)
                    } else {
                        stringResource(MR.strings.agent_migration_cancel_dialog_explanation)
                    },
                style = typography.callout,
                color = colors.foregroundMuted
            )
            // A problem is orange (warning); the dialog stays open on it.
            state.cancelError?.let { error ->
                RaftBanner(
                    description =
                        stringResource(
                            if (error.code == AgentMigrationErrorCodes.RevisionStale || error.code == AgentMigrationErrorCodes.ConcurrentUpdate) {
                                MR.strings.agent_migration_cancel_dialog_stale_error
                            } else {
                                MR.strings.agent_migration_cancel_dialog_generic_error
                            }
                        ) + " (" + error.code + ")",
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Md
                )
            }
        }
    }
}
