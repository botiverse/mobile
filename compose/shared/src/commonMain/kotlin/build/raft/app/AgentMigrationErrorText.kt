package build.raft.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.data.AgentMigrationActionError
import build.raft.app.data.MigrationCopy
import build.raft.app.data.MigrationErrorContext
import build.raft.app.data.MigrationErrorInput
import build.raft.app.data.MigrationErrorPresentation
import build.raft.app.data.MigrationText
import build.raft.app.data.migrationErrorPresentation
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.tmm.kmmresource.resource.StringResource

internal fun migrationCopyResource(copy: MigrationCopy): StringResource =
    when (copy) {
        MigrationCopy.ErrorUiDisabled -> MR.strings.agent_migration_error_ui_disabled
        MigrationCopy.ErrorNotSupported -> MR.strings.agent_migration_error_not_supported
        MigrationCopy.ErrorAgentNotFound -> MR.strings.agent_migration_error_agent_not_found
        MigrationCopy.ErrorTargetRequired -> MR.strings.agent_migration_error_target_required
        MigrationCopy.ErrorNoSourceMachine -> MR.strings.agent_migration_error_no_source_machine
        MigrationCopy.ErrorTargetNotInServer -> MR.strings.agent_migration_error_target_not_in_server
        MigrationCopy.ErrorTargetMatchesSource -> MR.strings.agent_migration_error_target_matches_source
        MigrationCopy.ErrorCapabilityInsufficient -> MR.strings.agent_migration_error_capability_insufficient
        MigrationCopy.ErrorTargetOffline -> MR.strings.agent_migration_error_target_offline
        MigrationCopy.ErrorHostedUnavailable -> MR.strings.agent_migration_error_hosted_unavailable
        MigrationCopy.ErrorHostedAlreadyUsed -> MR.strings.agent_migration_error_hosted_already_used
        MigrationCopy.ErrorHostedModelInvalid -> MR.strings.agent_migration_error_hosted_model_invalid
        MigrationCopy.ErrorHostedReviewNotReady -> MR.strings.agent_migration_error_hosted_review_not_ready
        MigrationCopy.ErrorHostedMemoryUnimportable -> MR.strings.agent_migration_error_hosted_memory_unimportable
        MigrationCopy.ErrorNotCancelable -> MR.strings.agent_migration_error_not_cancelable
        MigrationCopy.ErrorAlreadyInProgress -> MR.strings.agent_migration_error_already_in_progress
        MigrationCopy.ErrorProPlanRequired -> MR.strings.agent_migration_error_pro_plan_required
        MigrationCopy.ErrorTransportNotProvisioned -> MR.strings.agent_migration_error_transport_not_provisioned
        MigrationCopy.ErrorTransportProvisionFailed -> MR.strings.agent_migration_error_transport_provision_failed
        MigrationCopy.ErrorWorkspaceAlreadyExists -> MR.strings.agent_migration_error_workspace_already_exists
        MigrationCopy.ErrorWorkspaceAlreadyExistsAt -> MR.strings.agent_migration_error_workspace_already_exists_at
        MigrationCopy.ErrorWorkspaceCompleteOldCopy -> MR.strings.agent_migration_error_workspace_complete_old_copy
        MigrationCopy.ErrorWorkspaceCompleteOldCopyAt -> MR.strings.agent_migration_error_workspace_complete_old_copy_at
        MigrationCopy.ErrorObjectStoreUnreachable -> MR.strings.agent_migration_error_object_store_unreachable
        MigrationCopy.ErrorFailedGeneric -> MR.strings.agent_migration_error_failed_generic
        MigrationCopy.ErrorControlManifestInvalid -> MR.strings.agent_migration_error_control_manifest_invalid
        MigrationCopy.ErrorEntryCountLimitExceeded -> MR.strings.agent_migration_error_entry_count_limit_exceeded
        MigrationCopy.ErrorEntryCountLimitExceededWithDetails -> MR.strings.agent_migration_error_entry_count_limit_exceeded_with_details
        MigrationCopy.ErrorEntryCountLimitExceededWithDetailsWithPaths -> MR.strings.agent_migration_error_entry_count_limit_exceeded_with_details_with_paths
        MigrationCopy.ErrorRefInvalid -> MR.strings.agent_migration_error_ref_invalid
        MigrationCopy.ErrorRevisionInvalid -> MR.strings.agent_migration_error_revision_invalid
        MigrationCopy.ErrorNotFound -> MR.strings.agent_migration_error_not_found
        MigrationCopy.ErrorRevisionStale -> MR.strings.agent_migration_error_revision_stale
        MigrationCopy.ErrorConcurrentUpdate -> MR.strings.agent_migration_error_concurrent_update
        MigrationCopy.ErrorCancelFailed -> MR.strings.agent_migration_error_cancel_failed
        MigrationCopy.ErrorStartFailed -> MR.strings.agent_migration_error_start_failed
        MigrationCopy.ErrorStatusFailed -> MR.strings.agent_migration_error_status_failed
        MigrationCopy.ErrorTargetRuntimeDetectionPending -> MR.strings.agent_migration_error_target_runtime_detection_pending
        MigrationCopy.ErrorTargetRuntimeDetectionIncomplete -> MR.strings.agent_migration_error_target_runtime_detection_incomplete
        MigrationCopy.ErrorTransportLost -> MR.strings.agent_migration_error_transport_lost
        MigrationCopy.ErrorTransportLostEvent -> MR.strings.agent_migration_error_transport_lost_event
        MigrationCopy.ErrorTransportLostAt -> MR.strings.agent_migration_error_transport_lost_at
        MigrationCopy.ErrorTransportLostRetryReady -> MR.strings.agent_migration_error_transport_lost_retry_ready
        MigrationCopy.ErrorTransportLostRetryOneOffline -> MR.strings.agent_migration_error_transport_lost_retry_one_offline
        MigrationCopy.ErrorTransportLostRetryBothOffline -> MR.strings.agent_migration_error_transport_lost_retry_both_offline
        MigrationCopy.ErrorTransportLostRetryUnknown -> MR.strings.agent_migration_error_transport_lost_retry_unknown
        MigrationCopy.ErrorBundleTooLargeWithLimit -> MR.strings.agent_migration_error_bundle_too_large_with_limit
        MigrationCopy.ErrorBundleTooLargeWithLimitWithItems -> MR.strings.agent_migration_error_bundle_too_large_with_limit_with_items
        MigrationCopy.ErrorBundleTooLarge -> MR.strings.agent_migration_error_bundle_too_large
        MigrationCopy.ErrorBundleTooLargeWithItems -> MR.strings.agent_migration_error_bundle_too_large_with_items
        MigrationCopy.ErrorManifestTooLargeWithCount -> MR.strings.agent_migration_error_manifest_too_large_with_count
        MigrationCopy.ErrorManifestTooLargeWithCountWithPaths -> MR.strings.agent_migration_error_manifest_too_large_with_count_with_paths
        MigrationCopy.ErrorManifestTooLarge -> MR.strings.agent_migration_error_manifest_too_large
        MigrationCopy.ErrorManifestTooLargeWithPaths -> MR.strings.agent_migration_error_manifest_too_large_with_paths
        MigrationCopy.ErrorInsufficientDiskWithSizes -> MR.strings.agent_migration_error_insufficient_disk_with_sizes
        MigrationCopy.ErrorInsufficientDisk -> MR.strings.agent_migration_error_insufficient_disk
        MigrationCopy.AbortPrepDeadline -> MR.strings.agent_migration_abort_prep_deadline
        MigrationCopy.AbortTransferDeadline -> MR.strings.agent_migration_abort_transfer_deadline
        MigrationCopy.AbortArrivalDeadline -> MR.strings.agent_migration_abort_arrival_deadline
        MigrationCopy.AbortedDetailPrepDeadline -> MR.strings.agent_migration_aborted_detail_prep_deadline
        MigrationCopy.AbortedDetailPrepDeadlineAt -> MR.strings.agent_migration_aborted_detail_prep_deadline_at
        MigrationCopy.AbortedDetailTransferDeadline -> MR.strings.agent_migration_aborted_detail_transfer_deadline
        MigrationCopy.AbortedDetailArrivalDeadline -> MR.strings.agent_migration_aborted_detail_arrival_deadline
        MigrationCopy.AbortedDetailFallback -> MR.strings.agent_migration_aborted_detail_fallback
        MigrationCopy.ErrorResumableGeneric -> MR.strings.agent_migration_error_resumable_generic
        MigrationCopy.HostedAgentNotCreated -> MR.strings.agent_migration_hosted_agent_not_created
        MigrationCopy.HostedTransportLost -> MR.strings.agent_migration_hosted_transport_lost
        MigrationCopy.TechnicalDetails -> MR.strings.agent_migration_technical_details
        MigrationCopy.TechnicalErrorCode -> MR.strings.agent_migration_technical_error_code
        MigrationCopy.StillOnSource -> MR.strings.agent_migration_detail_migration_still_on_source
        MigrationCopy.FailedAfterMove -> MR.strings.agent_migration_detail_migration_failed_after_move
        MigrationCopy.HostedFailedAfterMove -> MR.strings.agent_migration_hosted_failed_after_move
    }

@Composable
internal fun MigrationText.resolve(): String =
    if (args.isEmpty()) stringResource(migrationCopyResource(copy)) else stringResource(migrationCopyResource(copy), *args.toTypedArray())

/** The message sentences of [presentation], joined by one space as the web does. */
@Composable
internal fun MigrationErrorPresentation.resolvedMessage(): String =
    (messageParts.map { it.resolve() } + issues.map { "\u2022 " + it.resolve() }).joinToString(" ")

/**
 * Web MigrationErrorContent's collapsed "Technical details": the error code, one tap away. A
 * chevron row like the "Last migration" toggle above it; closed by default.
 */
@Composable
internal fun AgentMigrationTechnicalDetails(code: String, modifier: Modifier = Modifier) {
    val colors = RaftTheme.colors
    var open by remember(code) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.clickable(role = Role.Button) { open = !open },
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            RaftLucideIcon(
                name = if (open) RaftLucideIconName.ChevronDown else RaftLucideIconName.ChevronRight,
                modifier = Modifier.padding(top = 2.dp).size(16.dp),
                color = raftArgbHex(colors.foregroundMuted)
            )
            RaftText(
                text = stringResource(MR.strings.agent_migration_technical_details),
                style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
                color = colors.foregroundMuted
            )
        }
        if (open) {
            Row(Modifier.padding(start = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RaftText(
                    text = stringResource(MR.strings.agent_migration_technical_error_code) + ":",
                    style = RaftTheme.typography.caption,
                    color = colors.foregroundMuted
                )
                RaftText(text = code, style = RaftTheme.typography.monoMeta, color = colors.foregroundMuted)
            }
        }
    }
}

/** A refused migration request as a warning banner: friendly copy, the raw server text never in the body. */
@Composable
internal fun AgentMigrationActionErrorBanner(
    error: AgentMigrationActionError,
    context: MigrationErrorContext,
    modifier: Modifier = Modifier,
    title: String? = null
) {
    val presentation =
        remember(error, context) {
            migrationErrorPresentation(MigrationErrorInput(code = error.code, rawMessage = error.serverMessage, context = context))
        }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RaftBanner(
            title = title,
            description = presentation.resolvedMessage(),
            status = RaftBannerStatus.Warning,
            size = RaftBannerSize.Sm
        )
        presentation.technicalCode?.let { AgentMigrationTechnicalDetails(it) }
    }
}
