package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftAppSectionEyebrow
import build.raft.app.components.raftCardSurface
import build.raft.app.components.RaftProBadge
import build.raft.app.components.RaftProUpgradeDialog
import build.raft.app.data.AGENT_MIGRATION_STEP_COUNT
import build.raft.app.data.AgentMigrationActionError
import build.raft.app.data.AgentMigrationEffect
import build.raft.app.data.AgentMigrationEnding
import build.raft.app.data.AgentMigrationHostedImportSummary
import build.raft.app.data.AgentMigrationIntent
import build.raft.app.data.AgentMigrationNotice
import build.raft.app.data.AgentMigrationState
import build.raft.app.data.AgentMigrationTarget
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
import build.raft.designsystem.components.RaftRadioGroup
import build.raft.designsystem.components.RaftRadioRow
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource

/** A computer the migration can name or target (from the server's machine list). */
internal data class AgentMigrationComputer(
    val id: String,
    val name: String,
    val isComputer: Boolean
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
    val onAgentPlacementChanged: () -> Unit
)

private const val HOSTED_TARGET_CHOICE = "__cloud__"

/**
 * Web `AgentMigrationSection`: move the agent to another computer or to the
 * cloud, follow the move, cancel it, and confirm the cloud review. A Pro
 * feature: the title carries the Pro tag and, on a plan without it, the
 * entry opens the upgrade sheet instead of the start dialog.
 */
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

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)) {
            RaftAppSectionEyebrow(label = stringResource(MR.strings.agent_migration_section_title))
            RaftProBadge()
        }
        if (notice == null || notice.isCompleted || notice.isCanceled) {
            RaftText(
                text = stringResource(MR.strings.agent_migration_section_description),
                style = typography.caption,
                color = colors.foregroundHint
            )
            if (notice != null && ended == AgentMigrationEnding.Completed) {
                RaftText(
                    text = agentMigrationLastSummary(notice, inputs),
                    style = typography.caption,
                    color = colors.foregroundHint
                )
            }
        }
        if (notice != null && ended != AgentMigrationEnding.Completed && ended != AgentMigrationEnding.Canceled) {
            AgentMigrationProgressPanel(
                notice = notice,
                state = state,
                inputs = inputs,
                onShowCancel = {
                    inputs.onIntent(AgentMigrationIntent.ClearCancelError)
                    showCancel = true
                }
            )
        }
        state.statusError?.let { error ->
            RaftBanner(
                title = stringResource(MR.strings.agent_migration_detail_migration_status_unavailable),
                description = agentMigrationErrorText(error),
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm
            )
        }
        if (!active && state.loaded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm, Alignment.End)
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
    LaunchedEffect(notice?.canCancel(), state.canceling, state.cancelError) {
        // The cancel landed (or the move can no longer be canceled): the panel shows the result.
        if (showCancel && !state.canceling && (state.cancelError != null || notice?.canCancel() != true)) {
            showCancel = false
        }
    }
    if (showCancel && notice != null) {
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

@Composable
private fun agentMigrationLastSummary(notice: AgentMigrationNotice, inputs: AgentMigrationSectionInputs): String {
    val source = agentMigrationComputerLabel(inputs, notice.sourceMachineId)
    val target = agentMigrationTargetLabel(inputs, notice)
    val endedAt = (if (notice.isFailed) notice.abortedAt else notice.completedAt) ?: notice.updatedAt
    val time = endedAt?.let(inputs.formatTimestamp)
    return when {
        notice.isFailed && time != null -> stringResource(MR.strings.agent_migration_last_failed_with_time, source, target, time)
        notice.isFailed -> stringResource(MR.strings.agent_migration_last_failed, source, target)
        time != null -> stringResource(MR.strings.agent_migration_last_moved_with_time, source, target, time)
        else -> stringResource(MR.strings.agent_migration_last_moved, source, target)
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

@Composable
private fun AgentMigrationProgressPanel(
    notice: AgentMigrationNotice,
    state: AgentMigrationState,
    inputs: AgentMigrationSectionInputs,
    onShowCancel: () -> Unit
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val copy = agentMigrationNoticeCopy(notice, inputs)
    val attention = notice.needsAttention()
    if (notice.endedComputerMigration() == AgentMigrationEnding.Failed) {
        Column(verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)) {
            RaftBanner(
                title = agentMigrationLastSummary(notice, inputs),
                description = listOfNotNull(copy.placement, copy.message).joinToString("\n"),
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm
            )
            AgentMigrationReference(notice, inputs)
        }
        return
    }
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
    val steps =
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
    val current = if (notice.isCompleted) AGENT_MIGRATION_STEP_COUNT else notice.progressStep()
    val reviewSummary = if (notice.isHostedReviewPending()) notice.hosted?.importSummary else null
    Column(verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)) {
        RaftText(text = header, style = typography.subhead.copy(fontWeight = FontWeight.SemiBold), color = colors.foreground)
        steps.forEachIndexed { index, label ->
            val icon =
                when {
                    index < current -> RaftLucideIconName.CircleCheck
                    index == current && attention -> RaftLucideIconName.TriangleAlert
                    index == current && notice.isActive -> RaftLucideIconName.LoaderCircle
                    else -> RaftLucideIconName.Circle
                }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)) {
                RaftLucideIcon(
                    name = icon,
                    modifier = Modifier.size(16.dp),
                    color = if (index <= current) RaftColor.TextBlackArgb else RaftColor.TextMutedGrayArgb
                )
                RaftText(
                    text = stringResource(label),
                    style = typography.callout,
                    color = if (index <= current) colors.foreground else colors.foregroundHint
                )
            }
        }
        if (attention) {
            RaftBanner(
                title = copy.title,
                description = listOfNotNull(copy.placement, copy.message).joinToString("\n"),
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm
            )
            AgentMigrationReference(notice, inputs)
        } else {
            copy.title?.takeIf { notice.isCancelRequested }?.let {
                RaftText(text = it, style = typography.callout.copy(fontWeight = FontWeight.SemiBold), color = colors.foreground)
            }
            RaftText(text = copy.message, style = typography.callout, color = colors.foregroundMuted)
        }
        state.cancelError?.let { error ->
            RaftBanner(
                description =
                    stringResource(
                        if (error.code == build.raft.app.data.AgentMigrationErrorCodes.RevisionStale ||
                            error.code == build.raft.app.data.AgentMigrationErrorCodes.ConcurrentUpdate
                        ) {
                            MR.strings.agent_migration_cancel_dialog_stale_error
                        } else {
                            MR.strings.agent_migration_cancel_dialog_generic_error
                        }
                    ) + " (" + error.code + ")",
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm
            )
        }
        if (reviewSummary != null) {
            AgentMigrationHostedReviewCard(notice, reviewSummary, state, inputs, onShowCancel)
        } else if (notice.isActive && notice.canCancel()) {
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

@Composable
private fun AgentMigrationReference(notice: AgentMigrationNotice, inputs: AgentMigrationSectionInputs) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)) {
        RaftText(
            text = stringResource(MR.strings.agent_migration_reference_label),
            style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
            color = colors.foregroundMuted
        )
        RaftText(text = notice.migrationRef, style = typography.monoMeta, color = colors.foreground, modifier = Modifier.weight(1f, fill = false))
        RaftButton(
            text = stringResource(MR.strings.common_copy),
            onClick = { inputs.onCopy(notice.migrationRef) },
            variant = RaftButtonVariant.Ghost,
            size = RaftButtonSize.Md
        )
    }
}

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
    Column(
        modifier = Modifier.fillMaxWidth().raftCardSurface().padding(RaftAppSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
    ) {
        RaftText(
            text = stringResource(MR.strings.agent_migration_hosted_review_title),
            style = typography.subhead.copy(fontWeight = FontWeight.SemiBold),
            color = colors.foreground
        )
        AgentMigrationReviewItem(
            term = stringResource(MR.strings.agent_migration_hosted_review_imported_term),
            detail = agentMigrationFileCount(summary.importedFileCount, summary.importedBytes)
        )
        if (skipped.isEmpty()) {
            AgentMigrationReviewItem(
                term = stringResource(MR.strings.agent_migration_hosted_review_skipped_none),
                detail = stringResource(MR.strings.agent_migration_hosted_review_nothing_skipped)
            )
        } else {
            skipped.forEach { group ->
                val shown = group.largestPaths.take(5)
                AgentMigrationReviewItem(
                    term = stringResource(MR.strings.agent_migration_hosted_review_skipped_term, agentMigrationSkipReason(group.reason)),
                    detail = agentMigrationFileCount(group.count, group.bytes),
                    paths = shown
                )
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm, Alignment.End)
        ) {
            RaftButton(
                text = stringResource(MR.strings.agent_migration_detail_cancel),
                onClick = onShowCancel,
                size = RaftButtonSize.Md,
                enabled = !state.confirming && notice.canCancel()
            )
            RaftButton(
                text = stringResource(MR.strings.agent_migration_hosted_confirm),
                onClick = { inputs.onIntent(AgentMigrationIntent.ConfirmHosted) },
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Md,
                loading = state.confirming,
                loadingLabel = stringResource(MR.strings.agent_migration_hosted_confirming)
            )
        }
    }
}

@Composable
private fun AgentMigrationReviewItem(term: String, detail: String, paths: List<String> = emptyList()) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RaftText(text = term, style = typography.caption.copy(fontWeight = FontWeight.SemiBold), color = colors.foregroundMuted)
        RaftText(text = detail, style = typography.callout, color = colors.foreground)
        paths.forEach { path ->
            RaftText(text = path, style = typography.monoMeta, color = colors.foregroundMuted)
        }
    }
}

@Composable
private fun agentMigrationFileCount(files: Int, bytes: Long): String {
    val size = formatAgentMigrationBytes(bytes)
    return if (files == 1) {
        stringResource(MR.strings.agent_migration_hosted_review_file_count_one, files, size)
    } else {
        stringResource(MR.strings.agent_migration_hosted_review_file_count_other, files, size)
    }
}

internal fun formatAgentMigrationBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit += 1
    }
    val rounded = if (unit == 0 || value >= 10) value.toLong().toString() else ((value * 10).toLong() / 10.0).toString()
    return "$rounded ${units[unit]}"
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

@Composable
private fun AgentMigrationStartDialog(
    state: AgentMigrationState,
    inputs: AgentMigrationSectionInputs,
    initialTarget: String?,
    onStart: (AgentMigrationTarget) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val targets = remember(inputs.computers, inputs.sourceMachineId) {
        inputs.computers.filter { it.isComputer && it.id != inputs.sourceMachineId }
    }
    var selected by remember(targets, initialTarget) {
        mutableStateOf(
            targets.firstOrNull { it.id == initialTarget }?.id
                ?: targets.firstOrNull()?.id
                ?: HOSTED_TARGET_CHOICE.takeIf { inputs.hostedAvailable }
        )
    }
    val hostedSelected = inputs.hostedAvailable && selected == HOSTED_TARGET_CHOICE
    val source = agentMigrationComputerLabel(inputs, inputs.sourceMachineId)
    RaftUnifiedDialog(
        title =
            stringResource(
                if (inputs.hostedAvailable) MR.strings.agent_migration_hosted_dialog_title
                else MR.strings.agent_migration_detail_move_to_another_computer
            ),
        onDismiss = onDismiss,
        dismissEnabled = !state.starting,
        maxWidth = 520.dp,
        titleMaxLines = 2
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md)
        ) {
            item {
                RaftText(
                    text =
                        stringResource(
                            if (inputs.hostedAvailable) MR.strings.agent_migration_hosted_target_label
                            else MR.strings.agent_migration_detail_target_computer
                        ),
                    style = typography.subhead.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foreground
                )
            }
            item {
                if (targets.isEmpty() && !inputs.hostedAvailable) {
                    RaftText(
                        text = stringResource(MR.strings.agent_migration_detail_no_other_attached_computer),
                        style = typography.callout,
                        color = colors.foregroundHint
                    )
                } else {
                    RaftRadioGroup {
                        targets.forEach { computer ->
                            RaftRadioRow(
                                label = computer.name,
                                selected = selected == computer.id,
                                enabled = !state.starting,
                                onClick = { selected = computer.id }
                            )
                        }
                        if (inputs.hostedAvailable) {
                            RaftRadioRow(
                                label = stringResource(MR.strings.agent_migration_hosted_option),
                                selected = hostedSelected,
                                enabled = !state.starting,
                                onClick = { selected = HOSTED_TARGET_CHOICE }
                            )
                        }
                    }
                }
            }
            item {
                val notes =
                    if (hostedSelected) {
                        listOf(
                            stringResource(MR.strings.agent_migration_hosted_note_stopped, source),
                            stringResource(MR.strings.agent_migration_hosted_note_files, source),
                            stringResource(MR.strings.agent_migration_hosted_note_session),
                            stringResource(MR.strings.agent_migration_hosted_note_github),
                            stringResource(MR.strings.agent_migration_hosted_note_final)
                        ).map { "• $it" }
                    } else {
                        listOf(
                            stringResource(MR.strings.agent_migration_detail_migration_mode_stop_before_export),
                            stringResource(MR.strings.agent_migration_detail_migration_session_reset_detailed)
                        )
                    }
                Column(verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)) {
                    notes.forEach { RaftText(text = it, style = typography.callout, color = colors.foregroundMuted) }
                }
            }
            state.startError?.let { error ->
                item {
                    RaftBanner(description = agentMigrationErrorText(error), status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
                }
            }
        }
        RaftDialogFooter {
            RaftButton(
                text = stringResource(MR.strings.agent_migration_detail_cancel),
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                enabled = !state.starting
            )
            RaftButton(
                text =
                    stringResource(
                        if (hostedSelected) MR.strings.agent_migration_hosted_start
                        else MR.strings.agent_migration_detail_start_migration
                    ),
                onClick = {
                    val choice = selected ?: return@RaftButton
                    onStart(
                        if (choice == HOSTED_TARGET_CHOICE) AgentMigrationTarget.Cloud
                        else AgentMigrationTarget.Computer(choice)
                    )
                },
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
                enabled = selected != null,
                loading = state.starting,
                loadingLabel = stringResource(MR.strings.agent_migration_detail_starting)
            )
        }
    }
}

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
    RaftUnifiedDialog(
        title = stringResource(MR.strings.agent_migration_cancel_dialog_title),
        onDismiss = onDismiss,
        dismissEnabled = !state.canceling,
        maxWidth = 448.dp
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md)) {
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
                style = typography.callout,
                color = colors.foregroundMuted
            )
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
        }
        RaftDialogFooter {
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
    }
}
