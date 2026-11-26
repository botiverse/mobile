package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.data.ComputerAgentOwnerNotifyState
import build.raft.app.data.ComputerDeleteMachineResult
import build.raft.app.data.ComputerDeleteRefusalReason
import build.raft.app.data.ComputerOthersAgent
import build.raft.app.data.ComputerOthersAgentsRepository
import build.raft.app.data.ComputerRevokeAndDeleteResult
import build.raft.app.data.DataResult
import build.raft.app.data.applyNotifyFailure
import build.raft.app.data.applyNotifyResult
import build.raft.app.data.beginNotify
import build.raft.app.data.computerDeleteRefusalReason
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.isOwnComputerAgent
import build.raft.app.data.latestNotifiedAt
import build.raft.app.data.nextReminder
import build.raft.app.data.ownerNow
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/*
 * Delete Computer dialog flow (#wg-mobile-sync:#63; botiverse/slock #12882 / #14942 / #15305,
 * web `DeleteComputerDialog.tsx`, `OthersAgentsOnComputerDialog.tsx`, `ForceUnbindAgentsDialog.tsx`,
 * `AgentOwnerNotifyActions.tsx`):
 *
 * - Someone else's Computer (an admin removing it) or a raw daemon: any visible agent on it shows
 *   the "Cannot delete computer" notice; otherwise confirming calls `DELETE /servers/:id/machines/:machineId`.
 * - The viewer's own attached Computer (`isComputer && attachedByCurrentUser`): only the viewer's
 *   own agents (created by them, or their personal assistant) hold it up with "Cannot delete computer".
 *   Other people's agents do not: confirming sends `POST .../revoke-computer-key` with `{ delete: true }`.
 *   - 200 `{ deleted: true }`: key revoked and machine removed in one transaction -> done.
 *   - 409 `others_agents_on_computer`: nothing happened; opens [OthersAgentsOnComputerDialog] listing
 *     every blocking agent with its owner ("No owner" when none), "Notify owner to move it" (once per
 *     24h via `POST .../notify-agent-owners`), and "Force unbind and delete" (outline) -> opens
 *     [ForceUnbindAgentsDialog] (red destructive confirm) which sends `forceAgentIds` matching the list.
 *     A 409 on force refreshes the list and shows `settings_computers_others_agents_force_conflict`.
 *   - Revoked without removal (`no_live_computer_key` or 200 without `deleted: true`): falls back to
 *     `DELETE`; if that fails, explains why in our words (`revokedNotRemoved`).
 */

internal object RaftComputerDeleteDialogTags {
    const val OthersAgentsDialog = "computer-others-agents-dialog"
    const val OthersAgentsForceButton = "computer-others-agents-force"
    const val ForceUnbindBody = "force-unbind-dialog-body"
    const val ForceUnbindConfirm = "force-unbind-confirm"
    const val ErrorBanner = "computer-delete-error-banner"

    fun othersAgentRow(agentId: String): String = "computer-others-agent-$agentId"
    fun notifyButton(agentId: String): String = "computer-notify-$agentId"
    fun notifiedLabel(agentId: String): String = "computer-notified-$agentId"
    fun nextReminderLabel(agentId: String): String = "computer-next-reminder-$agentId"
    fun contactNote(agentId: String): String = "computer-others-agent-contact-$agentId"
    fun forceUnbindAgentRow(agentId: String): String = "force-unbind-agent-$agentId"
}

/** Minimal ownership projection of a visible agent on a Computer, for Delete Computer gating. */
internal data class RaftComputerDeleteAgentRef(
    val creatorUserId: String?,
    val assistantOwnerUserId: String?,
)

/**
 * How many visible agents prevent deleting this Computer before hitting the server
 * (web `DeleteComputerDialog.tsx` `holding`): on the viewer's own attached Computer only their own
 * agents count; on someone else's Computer or a raw daemon every visible agent counts.
 */
internal fun raftComputerDeleteHoldingAgentCount(
    isComputer: Boolean,
    attachedByCurrentUser: Boolean,
    agents: List<RaftComputerDeleteAgentRef>,
    currentUserId: String?,
): Int {
    val attachedByMe = isComputer && attachedByCurrentUser
    return if (attachedByMe) {
        agents.count { isOwnComputerAgent(it.creatorUserId, it.assistantOwnerUserId, currentUserId) }
    } else {
        agents.size
    }
}

private sealed interface RaftComputerDeletePhase {
    data object Confirm : RaftComputerDeletePhase
    data class Others(val agents: List<ComputerOthersAgent>) : RaftComputerDeletePhase
    data class Force(val agents: List<ComputerOthersAgent>) : RaftComputerDeletePhase
}

private sealed interface RaftComputerDeleteFailure {
    data class Server(
        val message: String?,
        val serverCode: String?,
        val httpStatus: Int?,
        val traceId: String?,
    ) : RaftComputerDeleteFailure

    data class RevokedNotRemoved(val reason: ComputerDeleteRefusalReason, val traceId: String?) : RaftComputerDeleteFailure
    data object ForceConflict : RaftComputerDeleteFailure
}

private fun revokedNotRemovedResource(reason: ComputerDeleteRefusalReason): StringResource =
    when (reason) {
        ComputerDeleteRefusalReason.Agents -> Res.string.settings_computers_others_agents_revoked_not_removed_agents
        ComputerDeleteRefusalReason.Migration -> Res.string.settings_computers_others_agents_revoked_not_removed_migration
        ComputerDeleteRefusalReason.Profile -> Res.string.settings_computers_others_agents_revoked_not_removed_profile
        ComputerDeleteRefusalReason.Forbidden -> Res.string.settings_computers_others_agents_revoked_not_removed_forbidden
        ComputerDeleteRefusalReason.Other -> Res.string.settings_computers_others_agents_revoked_not_removed_other
    }

private fun whoElseCanActResource(personalAssistant: Boolean, hasOwner: Boolean): StringResource =
    if (personalAssistant) {
        if (hasOwner) {
            Res.string.settings_computers_others_agents_assistant_owner_only
        } else {
            Res.string.settings_computers_others_agents_assistant_no_owner
        }
    } else {
        if (hasOwner) {
            Res.string.settings_computers_others_agents_contact_admin
        } else {
            Res.string.settings_computers_others_agents_no_owner_contact_admin
        }
    }

@Composable
private fun RaftComputerDeleteFailureBanner(failure: RaftComputerDeleteFailure) {
    val message =
        when (failure) {
            RaftComputerDeleteFailure.ForceConflict ->
                stringResource(Res.string.settings_computers_others_agents_force_conflict)
            is RaftComputerDeleteFailure.RevokedNotRemoved ->
                stringResource(revokedNotRemovedResource(failure.reason))
            is RaftComputerDeleteFailure.Server ->
                failure.message?.trim()?.takeIf { it.isNotEmpty() }
                    ?: when {
                        failure.httpStatus == 401 -> stringResource(Res.string.settings_computers_error_auth)
                        failure.httpStatus == 403 -> stringResource(Res.string.settings_computers_error_forbidden)
                        failure.serverCode == "MACHINE_HAS_ASSIGNED_AGENTS" ->
                            stringResource(Res.string.settings_computers_error_has_agents)
                        else -> stringResource(Res.string.settings_computers_error_server_unavailable)
                    }
        }
    val traceId =
        when (failure) {
            RaftComputerDeleteFailure.ForceConflict -> null
            is RaftComputerDeleteFailure.RevokedNotRemoved -> failure.traceId
            is RaftComputerDeleteFailure.Server -> failure.traceId
        }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RaftBanner(
            description = message,
            status = RaftBannerStatus.Warning,
            size = RaftBannerSize.Sm,
            modifier = Modifier.testTag(RaftComputerDeleteDialogTags.ErrorBanner),
        )
        if (!traceId.isNullOrBlank()) {
            RaftTraceIdNote(traceId)
        }
    }
}

/**
 * Full web-parity `DeleteComputerDialog`: handles own vs other computers, `revoke-computer-key`
 * with `delete: true`, 409 `others_agents_on_computer` -> [OthersAgentsOnComputerDialog] with
 * per-agent "Notify owner to move it", and [ForceUnbindAgentsDialog] with `forceAgentIds`.
 */
@Composable
internal fun RaftComputerDeleteFlowDialog(
    serverId: ServerId?,
    machineId: String,
    computerName: String,
    isComputer: Boolean,
    attachedByCurrentUser: Boolean,
    agents: List<RaftComputerDeleteAgentRef>,
    currentUserId: String?,
    repository: ComputerOthersAgentsRepository,
    onDeleted: () -> Unit,
    onDismiss: () -> Unit,
    timeContext: RaftTimeContext? = null,
) {
    val attachedByMe = isComputer && attachedByCurrentUser
    val holdingCount =
        raftComputerDeleteHoldingAgentCount(
            isComputer = isComputer,
            attachedByCurrentUser = attachedByCurrentUser,
            agents = agents,
            currentUserId = currentUserId,
        )
    if (holdingCount > 0) {
        RaftComputerCannotDeleteDialog(agentCount = holdingCount, onDismiss = onDismiss)
        return
    }
    var phase by remember(machineId) { mutableStateOf<RaftComputerDeletePhase>(RaftComputerDeletePhase.Confirm) }
    var busy by remember(machineId) { mutableStateOf(false) }
    var failure by remember(machineId) { mutableStateOf<RaftComputerDeleteFailure?>(null) }
    var notifyState by remember(machineId) { mutableStateOf(ComputerAgentOwnerNotifyState()) }
    var revoked by remember(machineId) { mutableStateOf(false) }
    var finished by remember(machineId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun remove(forceAgentIds: List<String>): Pair<List<ComputerOthersAgent>?, RaftComputerDeleteFailure?> {
        val targetServerId =
            serverId?.takeIf { it.value.isNotBlank() }
                ?: return null to RaftComputerDeleteFailure.Server(message = null, serverCode = null, httpStatus = null, traceId = null)
        if (attachedByMe) {
            when (val result = repository.revokeKeyAndDeleteComputer(targetServerId, machineId, forceAgentIds)) {
                ComputerRevokeAndDeleteResult.Deleted -> return null to null
                is ComputerRevokeAndDeleteResult.OthersAgents -> return result.agents to null
                ComputerRevokeAndDeleteResult.Revoked -> {
                    revoked = true
                }
                is ComputerRevokeAndDeleteResult.Failed -> {
                    return null to
                        RaftComputerDeleteFailure.Server(
                            message = result.error.serverMessage,
                            serverCode = result.error.serverCode,
                            httpStatus = result.httpStatus,
                            traceId = result.error.traceId,
                        )
                }
            }
        }
        return when (val deleted = repository.deleteMachine(targetServerId, machineId)) {
            ComputerDeleteMachineResult.Deleted -> null to null
            is ComputerDeleteMachineResult.Failed -> {
                val err =
                    if (revoked) {
                        RaftComputerDeleteFailure.RevokedNotRemoved(
                            reason = computerDeleteRefusalReason(deleted.httpStatus, deleted.error.serverCode),
                            traceId = deleted.error.traceId,
                        )
                    } else {
                        RaftComputerDeleteFailure.Server(
                            message = deleted.error.serverMessage,
                            serverCode = deleted.error.serverCode,
                            httpStatus = deleted.httpStatus,
                            traceId = deleted.error.traceId,
                        )
                    }
                null to err
            }
        }
    }

    fun sendNotify(agentIds: List<String>) {
        if (agentIds.isEmpty()) return
        val targetServerId = serverId?.takeIf { it.value.isNotBlank() }
        if (targetServerId == null) {
            notifyState = notifyState.applyNotifyFailure(agentIds)
            return
        }
        notifyState = notifyState.beginNotify(agentIds)
        scope.launch {
            when (val result = repository.notifyAgentOwners(targetServerId, machineId, agentIds)) {
                is DataResult.Content -> {
                    notifyState = notifyState.applyNotifyResult(agentIds, result.data)
                }
                else -> {
                    notifyState = notifyState.applyNotifyFailure(agentIds)
                }
            }
        }
    }

    when (val current = phase) {
        RaftComputerDeletePhase.Confirm -> {
            RaftDialog(
                onDismissRequest = { if (!busy) onDismiss() },
                title = stringResource(Res.string.settings_computers_delete_title),
                size = RaftDialogSize.Sm,
                description = stringResource(Res.string.settings_computers_delete_confirm_named, computerName),
                dismissOnClickOutside = !busy,
                closeLabel = stringResource(Res.string.common_close),
                footer = {
                    RaftButton(
                        text = stringResource(Res.string.common_cancel),
                        onClick = onDismiss,
                        variant = RaftButtonVariant.Ghost,
                        size = RaftButtonSize.Lg,
                        enabled = !busy,
                    )
                    RaftButton(
                        text = stringResource(Res.string.settings_computers_delete_action),
                        onClick = {
                            if (busy) return@RaftButton
                            busy = true
                            failure = null
                            scope.launch {
                                val (others, err) = remove(emptyList())
                                busy = false
                                when {
                                    others != null -> {
                                        phase = RaftComputerDeletePhase.Others(others)
                                        failure = null
                                    }
                                    err != null -> {
                                        failure = err
                                    }
                                    else -> {
                                        finished = true
                                        onDeleted()
                                    }
                                }
                            }
                        },
                        variant = RaftButtonVariant.Danger,
                        size = RaftButtonSize.Lg,
                        loading = busy,
                        loadingLabel = stringResource(Res.string.settings_computers_delete_busy),
                        modifier = Modifier.testTag(RaftComputersRackTags.DeleteConfirm),
                    )
                },
                content =
                    failure?.let { activeFailure ->
                        { RaftComputerDeleteFailureBanner(activeFailure) }
                    },
            )
        }
        is RaftComputerDeletePhase.Others -> {
            OthersAgentsOnComputerDialog(
                agents = current.agents,
                notifyState = notifyState,
                onNotify = ::sendNotify,
                onForceUnbind = {
                    failure = null
                    phase = RaftComputerDeletePhase.Force(current.agents)
                },
                onDismiss = onDismiss,
                timeContext = timeContext,
            )
        }
        is RaftComputerDeletePhase.Force -> {
            ForceUnbindAgentsDialog(
                computerName = computerName,
                agents = current.agents,
                busy = busy,
                failure = failure,
                onConfirm = {
                    if (busy) return@ForceUnbindAgentsDialog
                    busy = true
                    failure = null
                    scope.launch {
                        val (others, err) = remove(current.agents.map { it.id })
                        busy = false
                        when {
                            others != null -> {
                                phase = RaftComputerDeletePhase.Force(others)
                                failure = RaftComputerDeleteFailure.ForceConflict
                            }
                            err != null -> {
                                failure = err
                            }
                            else -> {
                                finished = true
                                onDeleted()
                            }
                        }
                    }
                },
                onBack = {
                    if (!busy && !finished) {
                        failure = null
                        phase = RaftComputerDeletePhase.Others(current.agents)
                    }
                },
            )
        }
    }
}

@Composable
internal fun RaftComputerCannotDeleteDialog(
    agentCount: Int,
    onDismiss: () -> Unit,
) {
    RaftDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_computers_error_title_cannot_delete),
        size = RaftDialogSize.Sm,
        description =
            if (agentCount == 1) {
                stringResource(Res.string.settings_computers_cannot_delete_message_one, agentCount)
            } else {
                stringResource(Res.string.settings_computers_cannot_delete_message_many, agentCount)
            },
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_ok),
                onClick = onDismiss,
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Lg,
            )
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OthersAgentsOnComputerDialog(
    agents: List<ComputerOthersAgent>,
    notifyState: ComputerAgentOwnerNotifyState,
    onNotify: (List<String>) -> Unit,
    onForceUnbind: () -> Unit,
    onDismiss: () -> Unit,
    timeContext: RaftTimeContext? = null,
) {
    val listLabel = stringResource(Res.string.settings_computers_others_agents_list_label)
    val nowEpochMillis = currentRaftTimeContext(timeContext)?.nowEpochMillis ?: defaultNowMillis()
    RaftDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_computers_others_agents_title),
        size = RaftDialogSize.Md,
        dismissOnClickOutside = false,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
            )
            // Outline (Default), not Danger: only ForceUnbindAgentsDialog's confirm button is red.
            RaftButton(
                text = stringResource(Res.string.settings_computers_others_agents_force_unbind_and_delete),
                onClick = onForceUnbind,
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Lg,
                modifier = Modifier.testTag(RaftComputerDeleteDialogTags.OthersAgentsForceButton),
            )
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(RaftComputerDeleteDialogTags.OthersAgentsDialog),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RaftText(
                text = stringResource(Res.string.settings_computers_others_agents_message),
                style = RaftTheme.typography.subhead,
                color = RaftTheme.colors.foregroundMuted,
            )
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .semantics { contentDescription = listLabel },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                agents.forEach { agent ->
                    val owner = notifyState.ownerNow(agent)
                    val notifiedAt = notifyState.latestNotifiedAt(agent)
                    val reminder = notifyState.nextReminder(agent, nowEpochMillis)
                    val isBusy = agent.id in notifyState.busy
                    val isUndelivered = agent.id in notifyState.undelivered
                    val isFailed = agent.id in notifyState.failed
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(RaftComputerDeleteDialogTags.othersAgentRow(agent.id)),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RaftLucideIcon(
                            name = RaftLucideIconName.Bot,
                            modifier = Modifier.padding(top = 2.dp).size(16.dp),
                            color = raftArgbHex(RaftTheme.colors.foregroundMuted),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            RaftText(
                                text = agent.name,
                                style = RaftTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
                                color = RaftTheme.colors.foreground,
                            )
                            RaftText(
                                text =
                                    if (owner != null) {
                                        stringResource(Res.string.settings_computers_others_agents_owner, owner.name)
                                    } else {
                                        stringResource(Res.string.settings_computers_others_agents_no_owner)
                                    },
                                style = RaftTheme.typography.callout,
                                color = RaftTheme.colors.foregroundMuted,
                            )
                            FlowRow(
                                modifier = Modifier.padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                itemVerticalAlignment = Alignment.CenterVertically,
                            ) {
                                RaftButton(
                                    text =
                                        stringResource(
                                            if (notifiedAt != null && owner != null) {
                                                Res.string.settings_computers_others_agents_notify_owner_again
                                            } else {
                                                Res.string.settings_computers_others_agents_notify_owner
                                            }
                                        ),
                                    onClick = { onNotify(listOf(agent.id)) },
                                    variant = RaftButtonVariant.Default,
                                    size = RaftButtonSize.Md,
                                    enabled = owner != null && !reminder.limited,
                                    loading = isBusy,
                                    loadingLabel = stringResource(Res.string.settings_computers_others_agents_notifying_owner),
                                    leadingIcon = { RaftComputersIcon(RaftLucideIconName.Send, size = 14.dp) },
                                    modifier = Modifier.testTag(RaftComputerDeleteDialogTags.notifyButton(agent.id)),
                                )
                                if (owner != null && notifiedAt != null) {
                                    Row(
                                        modifier = Modifier.testTag(RaftComputerDeleteDialogTags.notifiedLabel(agent.id)),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RaftLucideIcon(
                                            name = RaftLucideIconName.Check,
                                            modifier = Modifier.size(16.dp),
                                            color = raftArgbHex(RaftTheme.colors.successStrong),
                                        )
                                        RaftText(
                                            text =
                                                stringResource(
                                                    Res.string.settings_computers_others_agents_owner_notified,
                                                    raftRelativeTimestampLabel(notifiedAt, timeContext, clampFuture = true),
                                                ),
                                            style = RaftTheme.typography.callout,
                                            color = RaftTheme.colors.foregroundMuted,
                                        )
                                    }
                                }
                                if (owner != null && reminder.limited) {
                                    val reminderAt = reminder.at
                                    val reminderText =
                                        if (reminderAt != null) {
                                            stringResource(
                                                Res.string.settings_computers_others_agents_remind_again_at,
                                                raftRelativeTimestampLabel(reminderAt, timeContext, clampFuture = false),
                                            )
                                        } else {
                                            stringResource(Res.string.settings_computers_others_agents_remind_once_per_day)
                                        }
                                    RaftText(
                                        text = reminderText,
                                        style = RaftTheme.typography.callout,
                                        color = RaftTheme.colors.foregroundMuted,
                                        modifier = Modifier.testTag(RaftComputerDeleteDialogTags.nextReminderLabel(agent.id)),
                                    )
                                }
                            }
                            if (isFailed || isUndelivered) {
                                RaftText(
                                    text =
                                        stringResource(
                                            if (isUndelivered) {
                                                Res.string.settings_computers_others_agents_notify_not_delivered
                                            } else {
                                                Res.string.settings_computers_others_agents_notify_failed
                                            }
                                        ),
                                    style = RaftTheme.typography.callout,
                                    color = RaftTheme.colors.dangerStrong,
                                )
                            }
                            RaftText(
                                text = stringResource(whoElseCanActResource(agent.personalAssistant, owner != null)),
                                style = RaftTheme.typography.footnote,
                                color = RaftTheme.colors.foregroundHint,
                                modifier = Modifier.testTag(RaftComputerDeleteDialogTags.contactNote(agent.id)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ForceUnbindAgentsDialog(
    computerName: String,
    agents: List<ComputerOthersAgent>,
    busy: Boolean,
    failure: RaftComputerDeleteFailure?,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    val listLabel = stringResource(Res.string.settings_computers_force_unbind_list_label)
    val title =
        if (agents.size == 1) {
            stringResource(Res.string.settings_computers_force_unbind_title_one, agents.size)
        } else {
            stringResource(Res.string.settings_computers_force_unbind_title_other, agents.size)
        }
    RaftDialog(
        onDismissRequest = { if (!busy) onBack() },
        title = title,
        size = RaftDialogSize.Sm,
        dismissOnClickOutside = !busy,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onBack,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                enabled = !busy,
            )
            RaftButton(
                text = stringResource(Res.string.settings_computers_others_agents_force_unbind_and_delete),
                onClick = onConfirm,
                variant = RaftButtonVariant.Danger,
                size = RaftButtonSize.Lg,
                loading = busy,
                loadingLabel = stringResource(Res.string.settings_computers_force_unbind_unbinding),
                modifier = Modifier.testTag(RaftComputerDeleteDialogTags.ForceUnbindConfirm),
            )
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(RaftComputerDeleteDialogTags.ForceUnbindBody),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RaftBanner(
                description = stringResource(Res.string.settings_computers_force_unbind_message),
                status = RaftBannerStatus.Destructive,
                size = RaftBannerSize.Sm,
            )
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                        .semantics { contentDescription = listLabel },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                agents.forEach { agent ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(RaftComputerDeleteDialogTags.forceUnbindAgentRow(agent.id)),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RaftLucideIcon(
                            name = RaftLucideIconName.Bot,
                            modifier = Modifier.padding(top = 2.dp).size(16.dp),
                            color = raftArgbHex(RaftTheme.colors.foregroundMuted),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            RaftText(
                                text = agent.name,
                                style = RaftTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
                                color = RaftTheme.colors.foreground,
                            )
                            val agentOwner = agent.owner
                            RaftText(
                                text =
                                    if (agentOwner != null) {
                                        stringResource(
                                            Res.string.settings_computers_force_unbind_agent_meta,
                                            agentOwner.name,
                                            computerName,
                                        )
                                    } else {
                                        stringResource(
                                            Res.string.settings_computers_force_unbind_agent_meta_no_owner,
                                            computerName,
                                        )
                                    },
                                style = RaftTheme.typography.callout,
                                color = RaftTheme.colors.foregroundMuted,
                            )
                        }
                    }
                }
            }
            if (failure != null) {
                RaftComputerDeleteFailureBanner(failure)
            }
        }
    }
}
