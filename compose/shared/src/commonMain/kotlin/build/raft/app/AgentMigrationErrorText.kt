package build.raft.app

import build.raft.app.resources.*
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
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import org.jetbrains.compose.resources.StringResource

internal fun migrationCopyResource(copy: MigrationCopy): StringResource =
    when (copy) {
        MigrationCopy.ErrorUiDisabled -> Res.string.agent_migration_error_ui_disabled
        MigrationCopy.ErrorNotSupported -> Res.string.agent_migration_error_not_supported
        MigrationCopy.ErrorAgentNotFound -> Res.string.agent_migration_error_agent_not_found
        MigrationCopy.ErrorTargetRequired -> Res.string.agent_migration_error_target_required
        MigrationCopy.ErrorNoSourceMachine -> Res.string.agent_migration_error_no_source_machine
        MigrationCopy.ErrorTargetNotInServer -> Res.string.agent_migration_error_target_not_in_server
        MigrationCopy.ErrorTargetMatchesSource -> Res.string.agent_migration_error_target_matches_source
        MigrationCopy.ErrorCapabilityInsufficient -> Res.string.agent_migration_error_capability_insufficient
        MigrationCopy.ErrorTargetOffline -> Res.string.agent_migration_error_target_offline
        MigrationCopy.ErrorHostedUnavailable -> Res.string.agent_migration_error_hosted_unavailable
        MigrationCopy.ErrorHostedAlreadyUsed -> Res.string.agent_migration_error_hosted_already_used
        MigrationCopy.ErrorHostedModelInvalid -> Res.string.agent_migration_error_hosted_model_invalid
        MigrationCopy.ErrorHostedReviewNotReady -> Res.string.agent_migration_error_hosted_review_not_ready
        MigrationCopy.ErrorHostedMemoryUnimportable -> Res.string.agent_migration_error_hosted_memory_unimportable
        MigrationCopy.ErrorNotCancelable -> Res.string.agent_migration_error_not_cancelable
        MigrationCopy.ErrorAlreadyInProgress -> Res.string.agent_migration_error_already_in_progress
        MigrationCopy.ErrorProPlanRequired -> Res.string.agent_migration_error_pro_plan_required
        MigrationCopy.ErrorTransportNotProvisioned -> Res.string.agent_migration_error_transport_not_provisioned
        MigrationCopy.ErrorTransportProvisionFailed -> Res.string.agent_migration_error_transport_provision_failed
        MigrationCopy.ErrorWorkspaceAlreadyExists -> Res.string.agent_migration_error_workspace_already_exists
        MigrationCopy.ErrorWorkspaceAlreadyExistsAt -> Res.string.agent_migration_error_workspace_already_exists_at
        MigrationCopy.ErrorWorkspaceCompleteOldCopy -> Res.string.agent_migration_error_workspace_complete_old_copy
        MigrationCopy.ErrorWorkspaceCompleteOldCopyAt -> Res.string.agent_migration_error_workspace_complete_old_copy_at
        MigrationCopy.ErrorObjectStoreUnreachable -> Res.string.agent_migration_error_object_store_unreachable
        MigrationCopy.ErrorFailedGeneric -> Res.string.agent_migration_error_failed_generic
        MigrationCopy.ErrorControlManifestInvalid -> Res.string.agent_migration_error_control_manifest_invalid
        MigrationCopy.ErrorEntryCountLimitExceeded -> Res.string.agent_migration_error_entry_count_limit_exceeded
        MigrationCopy.ErrorEntryCountLimitExceededWithDetails -> Res.string.agent_migration_error_entry_count_limit_exceeded_with_details
        MigrationCopy.ErrorEntryCountLimitExceededWithDetailsWithPaths -> Res.string.agent_migration_error_entry_count_limit_exceeded_with_details_with_paths
        MigrationCopy.ErrorRefInvalid -> Res.string.agent_migration_error_ref_invalid
        MigrationCopy.ErrorRevisionInvalid -> Res.string.agent_migration_error_revision_invalid
        MigrationCopy.ErrorNotFound -> Res.string.agent_migration_error_not_found
        MigrationCopy.ErrorRevisionStale -> Res.string.agent_migration_error_revision_stale
        MigrationCopy.ErrorConcurrentUpdate -> Res.string.agent_migration_error_concurrent_update
        MigrationCopy.ErrorCancelFailed -> Res.string.agent_migration_error_cancel_failed
        MigrationCopy.ErrorStartFailed -> Res.string.agent_migration_error_start_failed
        MigrationCopy.ErrorStatusFailed -> Res.string.agent_migration_error_status_failed
        MigrationCopy.ErrorTargetRuntimeDetectionPending -> Res.string.agent_migration_error_target_runtime_detection_pending
        MigrationCopy.ErrorTargetRuntimeDetectionIncomplete -> Res.string.agent_migration_error_target_runtime_detection_incomplete
        MigrationCopy.ErrorTransportLost -> Res.string.agent_migration_error_transport_lost
        MigrationCopy.ErrorTransportLostEvent -> Res.string.agent_migration_error_transport_lost_event
        MigrationCopy.ErrorTransportLostAt -> Res.string.agent_migration_error_transport_lost_at
        MigrationCopy.ErrorTransportLostRetryReady -> Res.string.agent_migration_error_transport_lost_retry_ready
        MigrationCopy.ErrorTransportLostRetryOneOffline -> Res.string.agent_migration_error_transport_lost_retry_one_offline
        MigrationCopy.ErrorTransportLostRetryBothOffline -> Res.string.agent_migration_error_transport_lost_retry_both_offline
        MigrationCopy.ErrorTransportLostRetryUnknown -> Res.string.agent_migration_error_transport_lost_retry_unknown
        MigrationCopy.ErrorBundleTooLargeWithLimit -> Res.string.agent_migration_error_bundle_too_large_with_limit
        MigrationCopy.ErrorBundleTooLargeWithLimitWithItems -> Res.string.agent_migration_error_bundle_too_large_with_limit_with_items
        MigrationCopy.ErrorBundleTooLarge -> Res.string.agent_migration_error_bundle_too_large
        MigrationCopy.ErrorBundleTooLargeWithItems -> Res.string.agent_migration_error_bundle_too_large_with_items
        MigrationCopy.ErrorManifestTooLargeWithCount -> Res.string.agent_migration_error_manifest_too_large_with_count
        MigrationCopy.ErrorManifestTooLargeWithCountWithPaths -> Res.string.agent_migration_error_manifest_too_large_with_count_with_paths
        MigrationCopy.ErrorManifestTooLarge -> Res.string.agent_migration_error_manifest_too_large
        MigrationCopy.ErrorManifestTooLargeWithPaths -> Res.string.agent_migration_error_manifest_too_large_with_paths
        MigrationCopy.ErrorInsufficientDiskWithSizes -> Res.string.agent_migration_error_insufficient_disk_with_sizes
        MigrationCopy.ErrorInsufficientDisk -> Res.string.agent_migration_error_insufficient_disk
        MigrationCopy.AbortPrepDeadline -> Res.string.agent_migration_abort_prep_deadline
        MigrationCopy.AbortTransferDeadline -> Res.string.agent_migration_abort_transfer_deadline
        MigrationCopy.AbortArrivalDeadline -> Res.string.agent_migration_abort_arrival_deadline
        MigrationCopy.AbortedDetailPrepDeadline -> Res.string.agent_migration_aborted_detail_prep_deadline
        MigrationCopy.AbortedDetailPrepDeadlineAt -> Res.string.agent_migration_aborted_detail_prep_deadline_at
        MigrationCopy.AbortedDetailTransferDeadline -> Res.string.agent_migration_aborted_detail_transfer_deadline
        MigrationCopy.AbortedDetailArrivalDeadline -> Res.string.agent_migration_aborted_detail_arrival_deadline
        MigrationCopy.AbortedDetailFallback -> Res.string.agent_migration_aborted_detail_fallback
        MigrationCopy.ErrorResumableGeneric -> Res.string.agent_migration_error_resumable_generic
        MigrationCopy.HostedAgentNotCreated -> Res.string.agent_migration_hosted_agent_not_created
        MigrationCopy.HostedTransportLost -> Res.string.agent_migration_hosted_transport_lost
        MigrationCopy.TechnicalDetails -> Res.string.agent_migration_technical_details
        MigrationCopy.TechnicalErrorCode -> Res.string.agent_migration_technical_error_code
        MigrationCopy.StillOnSource -> Res.string.agent_migration_detail_migration_still_on_source
        MigrationCopy.FailedAfterMove -> Res.string.agent_migration_detail_migration_failed_after_move
        MigrationCopy.HostedFailedAfterMove -> Res.string.agent_migration_hosted_failed_after_move
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
                text = stringResource(Res.string.agent_migration_technical_details),
                style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
                color = colors.foregroundMuted
            )
        }
        if (open) {
            Row(Modifier.padding(start = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RaftText(
                    text = stringResource(Res.string.agent_migration_technical_error_code) + ":",
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
