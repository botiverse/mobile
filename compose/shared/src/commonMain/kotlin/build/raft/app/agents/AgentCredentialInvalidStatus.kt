package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.data.AgentActivityDetailKindIdle
import build.raft.app.data.AgentBlockedState
import build.raft.app.data.AgentDetailSummary
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
            stringResource(Res.string.agent_status_credential_sign_in, raftAgentRuntimeDisplayName(runtime?.trim().orEmpty()))
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
