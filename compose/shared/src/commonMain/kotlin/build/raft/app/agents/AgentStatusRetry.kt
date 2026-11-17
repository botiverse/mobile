package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import build.raft.app.data.AgentActivityDetailKindRuntimeUnavailable
import build.raft.app.data.AgentActivityDetailKindWakeCrashLoopBlocked
import build.raft.app.data.AgentBlockedState
import build.raft.app.data.normalizedAgentRuntimeStatus
import build.raft.app.model.AgentCredentialHoldReason
import build.raft.app.model.AgentSpawnFailure
import build.raft.app.model.AgentStatus
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/** RFC 071 §9: the terminal-failure breaker paused automatic wakes; a manual start lifts it. */
internal const val AgentActivityDetailKindTerminalFailurePaused = "terminal_failure_paused"

/**
 * Web `MANUAL_START_DETAIL_KINDS` (utils/activity.ts, botiverse/slock #13345): detail kinds of an
 * agent that stays down until someone starts it by hand, though its light is not the error one.
 */
private val ManualStartDetailKinds =
    setOf(
        // task #1123: a failed start the daemon gave up on (the server pauses automatic restarts).
        AgentActivityDetailKindRuntimeUnavailable,
        // task #1119: the wake crash-loop breaker paused automatic wakes.
        AgentActivityDetailKindWakeCrashLoopBlocked,
        AgentActivityDetailKindTerminalFailurePaused,
    )

/**
 * Web `RESTART_CANNOT_FIX_SPAWN_FAILURES`: failed starts a restart resumes into the same failure;
 * only Reset session gets out of them (their status copy says so).
 */
private val RestartCannotFixSpawnFailures = setOf("session_needs_reset", "session_runtime_mismatch")

/** A rate limit or quota error: a restart cannot lift it and only sends more requests into it. */
private const val RateLimitErrorClass = "RateLimitError"

/**
 * Web `agentNeedsManualRestart` (botiverse/slock #13345): whether the agent cannot run until
 * someone restarts it, so its profile status offers Retry (the Actions section's Restart) to
 * whoever may restart it:
 *
 * - an invalid credential ([credentialReason], [agentCredentialInvalidReason], botiverse/slock
 *   #13276): held until a restart, whether its own error or another agent's on the same sign-in;
 * - the error light, unless the kept error is a rate limit ([runtimeErrorClass] `RateLimitError`);
 * - an agent whose automatic starts stopped ([ManualStartDetailKinds]): for a failed start, only
 *   once the server paused automatic restarts ([AgentSpawnFailure.autoRestartPaused]; before
 *   that it is still retrying and a tap would race its start), and never for one only Reset
 *   session fixes.
 *
 * Never for an external agent, a held one ([blocked]: a restart cannot reach it), or one only
 * guessed online.
 */
internal fun agentNeedsManualRestart(
    status: AgentStatus?,
    detailKind: String?,
    spawnFailure: AgentSpawnFailure?,
    runtimeErrorClass: String?,
    external: Boolean,
    blocked: AgentBlockedState?,
    likelyOnline: Boolean,
    credentialReason: AgentCredentialHoldReason? = null,
): Boolean {
    if (external || blocked != null || likelyOnline) return false
    if (credentialReason != null) return true
    if (status == AgentStatus.Error) return runtimeErrorClass?.trim() != RateLimitErrorClass
    if (detailKind?.trim() !in ManualStartDetailKinds) return false
    val failure = spawnFailure ?: return true
    // The other reasons (runtime missing, bad config, ...): a restart is still the last step once
    // the cause is fixed, so Retry stays for them.
    return failure.autoRestartPaused && failure.reason !in RestartCannotFixSpawnFailures
}

/**
 * The detail kind the profile status reads (web `displayState.activityDetailKind`): the live one,
 * else the loaded profile's while the live status is unknown or the same as the profile's.
 */
internal fun membersAgentStatusDetailKind(
    indicator: RaftAgentStatusIndicatorState?,
    summaryStatus: String?,
    summaryActivity: String?,
    summaryDetailKind: String?,
): String? {
    indicator?.detailKind?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val liveStatus = indicator?.status
    if (liveStatus != null && liveStatus != normalizedAgentRuntimeStatus(activity = summaryActivity, lifecycleStatus = summaryStatus)) {
        return null
    }
    return summaryDetailKind?.trim()?.takeIf { it.isNotEmpty() }
}

internal object AgentStatusRetryTestTags {
    const val Retry = "agent-status-retry"
    const val Blocked = "agent-status-blocked"
}

/**
 * Web AgentStatusBadge's Retry: a quiet text link with no spinner; while the restart runs it says
 * so ("Restarting…") and is disabled.
 */
@Composable
internal fun AgentStatusRetryLink(
    inProgress: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RaftTextLink(
        text = stringResource(if (inProgress) Res.string.agent_status_retrying else Res.string.agent_status_retry),
        onClick = onRetry,
        enabled = !inProgress,
        variant = RaftTextLinkVariant.Strong,
        style = RaftTheme.typography.footnote,
        modifier = modifier.testTag(AgentStatusRetryTestTags.Retry),
    )
}

/**
 * A held agent's status sentence (web AgentStatusBadge wraps the chip for it; the app's chip is
 * one line, so the whole sentence sits on its own lines under it, as a failed start's copy does).
 */
@Composable
internal fun AgentBlockedStatusNotice(blocked: AgentBlockedState, modifier: Modifier = Modifier) {
    val text = blocked.localizedStatusLabel()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RaftText(
            text = text,
            style = RaftTheme.typography.footnote,
            color = RaftTheme.colors.foregroundMuted,
            modifier = Modifier
                .weight(1f, fill = false)
                .testTag(AgentStatusRetryTestTags.Blocked)
                .semantics { contentDescription = text },
        )
    }
}
