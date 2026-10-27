package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import build.raft.app.data.AgentActivityDetailKindWakeCrashLoopBlocked
import build.raft.app.data.defaultNowMillis
import build.raft.app.model.AgentStatus
import build.raft.app.model.AgentToolStuck
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

// Web AgentToolStuckNotice (botiverse/slock #10446): the agent is stuck on one tool call, so
// new messages queue behind it. The profile and the DM header say so instead of a plain busy.

private const val ToolStuckMinuteMillis = 60_000L

/**
 * Whole minutes the agent has been quiet on the tool call, at least 1, never below the reported
 * threshold (web `toolStuckMinutes`). From a live frame it is the quiet time when the server sent
 * it plus the time since this device received it, both differences on one clock each, so a skewed
 * device clock doesn't shift it (Lagoon's review). A REST snapshot has no frame time: it falls back
 * to this device's clock against `quietSinceMs` (the agent's Computer clock).
 */
internal fun agentToolStuckMinutes(toolStuck: AgentToolStuck, nowEpochMillis: Long): Long {
    val elapsed =
        toolStuck.quietMillisAtFrame?.let { atFrame ->
            atFrame + (nowEpochMillis - toolStuck.receivedAtMs).coerceAtLeast(0L)
        } ?: (nowEpochMillis - toolStuck.quietSinceMs)
    val quietMillis = maxOf(elapsed, toolStuck.thresholdMs)
    return maxOf(1L, quietMillis / ToolStuckMinuteMillis)
}

/**
 * The stuck report to show, if any (web `visibleToolStuck`). An offline agent, or one whose
 * automatic wakes are blocked, says that instead, even if a stuck report is still stored.
 */
internal fun visibleAgentToolStuck(
    toolStuck: AgentToolStuck?,
    status: AgentStatus?,
    detailKind: String? = null,
    wakeBlocked: Boolean = false,
): AgentToolStuck? {
    if (toolStuck == null) return null
    if (status == AgentStatus.Offline || status == AgentStatus.Disconnected) return null
    if (wakeBlocked || detailKind == AgentActivityDetailKindWakeCrashLoopBlocked) return null
    return toolStuck
}

/** [visibleAgentToolStuck] of a live status. */
internal fun RaftAgentStatusIndicatorState?.visibleToolStuck(): AgentToolStuck? =
    this?.let { state ->
        visibleAgentToolStuck(
            toolStuck = state.toolStuck,
            status = state.status,
            detailKind = state.detailKind,
            wakeBlocked = state.wakeCrashLoop != null,
        )
    }

/**
 * The profile's stuck report: the live status's; the loaded profile's (`GET /agents/:id`
 * `toolStuck`) only while nothing live has been observed. A live frame without it means
 * "not stuck", so a stored report never outlives it.
 */
internal fun membersAgentToolStuck(
    indicator: RaftAgentStatusIndicatorState?,
    summaryToolStuck: AgentToolStuck?,
): AgentToolStuck? {
    if (indicator?.status != null) return indicator.visibleToolStuck()
    return visibleAgentToolStuck(toolStuck = summaryToolStuck, status = null)
}

/** "Stuck on bash · no output for 12 min · 3 messages waiting"; the queue part only when > 0. */
internal fun agentToolStuckText(
    stuckLine: String,
    queuedMessages: Int,
    waitingOne: String,
    waitingOther: String,
): String =
    when {
        queuedMessages <= 0 -> stuckLine
        queuedMessages == 1 -> "$stuckLine · $waitingOne"
        else -> "$stuckLine · $waitingOther"
    }

/** The stuck line in this locale; re-read whenever the live clock ticks (once a minute). */
@Composable
internal fun localizedAgentToolStuckText(toolStuck: AgentToolStuck): String {
    val now = LocalRaftTimeContextProvider.current?.current?.nowEpochMillis ?: defaultNowMillis()
    val minutes = agentToolStuckMinutes(toolStuck, now)
    return agentToolStuckText(
        stuckLine = stringResource(Res.string.agent_status_tool_stuck, toolStuck.toolName, minutes.toInt()),
        queuedMessages = toolStuck.queuedMessages,
        waitingOne = stringResource(Res.string.agent_status_tool_stuck_waiting_one),
        waitingOther = stringResource(Res.string.agent_status_tool_stuck_waiting_other, toolStuck.queuedMessages),
    )
}

/**
 * The profile's notice under the status chip: the stuck line in the warning colour, the hint,
 * and (for whoever can control the agent) Stop Current Turn — the agent itself stays.
 */
@Composable
internal fun AgentToolStuckNotice(
    toolStuck: AgentToolStuck,
    onStopTurn: (() -> Unit)?,
    stopTurnEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val text = localizedAgentToolStuckText(toolStuck)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftText(
                text = text,
                style = typography.footnote,
                color = colors.warningStrong,
                modifier = Modifier.weight(1f, fill = false).semantics { contentDescription = text },
            )
            if (onStopTurn != null) {
                RaftTextLink(
                    text = stringResource(Res.string.agent_stop_turn),
                    onClick = onStopTurn,
                    variant = RaftTextLinkVariant.Strong,
                    style = typography.footnote,
                    enabled = stopTurnEnabled,
                )
            }
        }
        RaftText(
            text = stringResource(Res.string.agent_status_tool_stuck_hint),
            style = typography.caption,
            color = colors.foregroundHint,
        )
    }
}
