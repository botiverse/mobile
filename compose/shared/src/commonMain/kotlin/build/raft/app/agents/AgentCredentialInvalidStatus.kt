package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.data.AgentActivityDetailKindIdle
import build.raft.app.data.AgentBlockedState
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.normalizedAgentRuntimeStatus
import build.raft.app.model.AgentCredentialHoldReason
import build.raft.app.model.AgentStatus
import build.raft.app.model.getModelLabel
import build.raft.app.resources.*

/** A kept runtime error of this class is a rejected sign-in (web `credentialInvalidReasonOf`). */
private const val AuthErrorClass = "AuthError"

/** A kept runtime error of this class is an exhausted provider balance. */
private const val BillingErrorClass = "BillingError"

/**
 * Web `credentialInvalidReasonOf` (utils/activity.ts, botiverse/slock #13276): why the agent cannot
 * run until someone fixes its provider credential, from typed data only (never the text):
 *
 * - held for another agent's error on the same sign-in: the Online idle status carries
 *   [credentialHold] (an older daemon sends none: Online, as before);
 * - its own error: the kept runtime error, while the live status is that error (the same message:
 *   a kept record describes the error shown, never an older one). [lastRuntimeErrorRequiredAction]
 *   first; an error kept before that field falls back to its class (`AuthError` a rejected
 *   sign-in, `BillingError` an exhausted balance).
 */
internal fun agentCredentialInvalidReason(
    status: AgentStatus?,
    activityDetail: String?,
    detailKind: String?,
    credentialHold: AgentCredentialHoldReason?,
    lastRuntimeErrorMessage: String?,
    lastRuntimeErrorClass: String?,
    lastRuntimeErrorRequiredAction: AgentCredentialHoldReason?,
): AgentCredentialHoldReason? {
    if (status == AgentStatus.Online && detailKind?.trim() == AgentActivityDetailKindIdle && credentialHold != null) {
        return credentialHold
    }
    if (status != AgentStatus.Error) return null
    val message = lastRuntimeErrorMessage?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (message != activityDetail?.trim()) return null
    lastRuntimeErrorRequiredAction?.let { return it }
    return when (lastRuntimeErrorClass?.trim()) {
        AuthErrorClass -> AgentCredentialHoldReason.UserReauth
        BillingErrorClass -> AgentCredentialHoldReason.Billing
        else -> null
    }
}

/**
 * The agent page status's credential reason (web AgentStatusBadge `credentialReason`): none for an
 * external agent, a held one ([blocked]) or one only guessed online. The live status and detail kind
 * are the indicator's ([membersAgentStatusDetailKind]); the error text and the carriers are the
 * profile's, which the realtime frames keep current.
 */
internal fun membersAgentCredentialInvalidReason(
    indicator: RaftAgentStatusIndicatorState?,
    summary: AgentDetailSummary?,
    external: Boolean,
    blocked: AgentBlockedState?,
): AgentCredentialHoldReason? {
    if (summary == null || external || blocked != null || indicator?.likelyOnline == true) return null
    return agentCredentialInvalidReason(
        status = indicator?.status,
        activityDetail = summary.activityDetail,
        detailKind =
            membersAgentStatusDetailKind(
                indicator = indicator,
                summaryStatus = summary.status,
                summaryActivity = summary.activity,
                summaryDetailKind = summary.activityDetailKind,
            ),
        credentialHold = summary.credentialHold,
        lastRuntimeErrorMessage = summary.lastRuntimeErrorMessage,
        lastRuntimeErrorClass = summary.lastRuntimeErrorClass,
        lastRuntimeErrorRequiredAction = summary.lastRuntimeErrorRequiredAction,
    )
}

/**
 * Web `formatCredentialInvalidText`: the status words for [reason], naming the agent's runtime
 * (shared `getRuntimeDisplayName`) or model (shared `getModelLabel`).
 */
@Composable
internal fun agentCredentialInvalidText(reason: AgentCredentialHoldReason, runtime: String?, model: String?): String =
    when (reason) {
        AgentCredentialHoldReason.UserReauth ->
            stringResource(Res.string.agent_status_credential_sign_in, observeAgentRuntimeDisplayName(runtime?.trim().orEmpty()))
        AgentCredentialHoldReason.UserModelAccess ->
            model?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { stringResource(Res.string.agent_status_credential_plan_model, getModelLabel(runtime?.trim().orEmpty(), it)) }
                ?: stringResource(Res.string.agent_status_credential_plan_default_model)
        AgentCredentialHoldReason.Billing -> stringResource(Res.string.agent_status_credential_billing)
    }

/**
 * Web `agentErrorRecordStale` (store/agentErrorRecordRefresh.ts, botiverse/slock #13276): the live
 * error the open profile's kept record does not describe yet (the server stores the error, with its
 * `requiredAction`, after it pushes the error status), or null. The page re-reads the profile once
 * per such detail.
 */
internal fun membersAgentErrorRecordStaleDetail(liveStatus: AgentStatus?, summary: AgentDetailSummary?): String? {
    if (liveStatus != AgentStatus.Error || summary == null) return null
    val detail = summary.activityDetail?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return detail.takeIf { it != summary.lastRuntimeErrorMessage?.trim() }
}

/** Shared `SpawnFailureReason` `runtime_login_required` (slock #15273): the runtime is not signed in on the Computer. */
internal const val RuntimeLoginRequiredSpawnFailure = "runtime_login_required"

/**
 * Web `runtimeSignInNeededOf` (utils/activity.ts, slock #15282): the agent cannot start because its
 * runtime is not signed in on its Computer, from the live failed start ([spawnFailureReason]) or the
 * kept record ([lastRuntimeErrorSpawnFailureReason]). Only while it is offline or in error, and
 * never for a held, paused, external or only-guessed-online agent.
 */
internal fun agentRuntimeSignInNeeded(
    status: AgentStatus?,
    spawnFailureReason: String?,
    lastRuntimeErrorSpawnFailureReason: String?,
    blocked: Boolean,
    likelyOnline: Boolean,
    external: Boolean,
    paused: Boolean = false,
): Boolean {
    if (status != AgentStatus.Offline && status != AgentStatus.Error) return false
    if (blocked || paused || likelyOnline || external) return false
    return spawnFailureReason?.trim() == RuntimeLoginRequiredSpawnFailure ||
        lastRuntimeErrorSpawnFailureReason?.trim() == RuntimeLoginRequiredSpawnFailure
}

/**
 * [agentRuntimeSignInNeeded] for the agent profile: the live status when known, else the loaded
 * profile's; the failed start's reason from the live carrier or the profile.
 */
internal fun membersAgentRuntimeSignInNeeded(
    indicator: RaftAgentStatusIndicatorState?,
    summary: AgentDetailSummary?,
    external: Boolean,
    blocked: AgentBlockedState?,
    liveSpawnFailureReason: String?,
    paused: Boolean = false,
): Boolean {
    if (summary == null) return false
    val status = indicator?.status ?: normalizedAgentRuntimeStatus(activity = summary.activity, lifecycleStatus = summary.status)
    return agentRuntimeSignInNeeded(
        status = status,
        spawnFailureReason = liveSpawnFailureReason ?: summary.spawnFailure?.reason,
        lastRuntimeErrorSpawnFailureReason = summary.lastRuntimeErrorSpawnFailureReason,
        blocked = blocked != null,
        likelyOnline = indicator?.likelyOnline == true,
        external = external,
        paused = paused,
    )
}

/** Web `agent.status.runtimeSignIn.full` / `.short`: the status words, naming the runtime when [full]. */
@Composable
internal fun agentRuntimeSignInText(runtime: String?, full: Boolean): String {
    val name = runtime?.trim()?.takeIf { it.isNotEmpty() }?.let { observeAgentRuntimeDisplayName(it) }
    return if (full && name != null) {
        stringResource(Res.string.agent_status_runtime_sign_in_full, name)
    } else {
        stringResource(Res.string.agent_status_runtime_sign_in_short)
    }
}
