package build.raft.app

import build.raft.app.data.agentBlockedState
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.statusLabelRes
import build.raft.app.model.AgentDeliveryHold
import build.raft.app.model.AgentReadiness
import build.raft.app.model.AgentStatus
import build.raft.app.model.Presence
import build.raft.app.model.attentionReadinessOrNull
import build.raft.app.model.storedStartFailureAttentionOf
import build.raft.app.resources.*

/**
 * The app's agent status dot as a colour family, for hosts that draw it outside Compose (the
 * Android home-screen widget). Resolved by [raftAgentStatusIndicatorState], the same mapping as
 * every dot in the app: working / thinking yellow, online / idle lime, external agents cyan (lime
 * inside the 120s online window), error orange, and offline, disconnected or unknown gray. An
 * agent held by its Computer ([blockedReason], [agentBlockedState]) or needing a person
 * ([readiness] blocked except `computer_offline`, or a non-retryable stored failed start while
 * offline and not stopped) is orange whatever its last activity, as in the app.
 */
enum class RaftAgentStatusDotKind { Lime, Yellow, Cyan, Orange, Gray }

fun raftAgentStatusDotKind(
    statusWireValue: String?,
    external: Boolean,
    lastSeenAt: String? = null,
    nowEpochMillis: Long? = null,
    blockedReason: String? = null,
    deliveryHold: AgentDeliveryHold? = null,
    runningWorkers: Int? = null,
    runtime: String? = null,
    readiness: AgentReadiness? = null,
    lastRuntimeErrorSpawnFailureReason: String? = null,
    lifecycleStatus: String? = null,
): RaftAgentStatusDotKind {
    if (!external && agentBlockedState(blockedReason, runtime) != null) return RaftAgentStatusDotKind.Orange
    if (!external && readiness.attentionReadinessOrNull() != null) return RaftAgentStatusDotKind.Orange
    val parsedStatus = AgentStatus.fromWireValue(statusWireValue)
    val heldByOfflineComputer = readiness?.state == "blocked" && readiness.primary?.code == "computer_offline"
    if (
        !external &&
        !heldByOfflineComputer &&
        parsedStatus == AgentStatus.Offline &&
        lifecycleStatus?.trim()?.lowercase() != "stopped" &&
        storedStartFailureAttentionOf(lastRuntimeErrorSpawnFailureReason, liveSpawnFailure = null, model = null) != null
    ) {
        return RaftAgentStatusDotKind.Orange
    }
    val nowMs = nowEpochMillis ?: defaultNowMillis()
    if (
        deliveryHold != null &&
        agentDeliveryHoldOf(
            status = parsedStatus,
            deliveryHold = deliveryHold,
            nowMs = nowMs,
            external = external,
        ) != null
    ) {
        return RaftAgentStatusDotKind.Orange
    }
    if (
        agentRunningWorkersDisplayOf(
            status = parsedStatus,
            runningWorkers = runningWorkers,
            nowMs = nowMs,
            external = external,
            deliveryHold = deliveryHold,
        ) != null
    ) {
        return RaftAgentStatusDotKind.Yellow
    }
    val state =
        raftAgentStatusIndicatorState(
            status = parsedStatus,
            // No live presence outside the app: an agent without a known status reads offline.
            presence = Presence.Offline,
            external = external,
            lastSeenAt = lastSeenAt,
            nowEpochMillis = nowEpochMillis,
        )
    return when (state.color) {
        RaftColor.AccentLime -> RaftAgentStatusDotKind.Lime
        RaftColor.BrandYellow -> RaftAgentStatusDotKind.Yellow
        RaftColor.AccentCyan -> RaftAgentStatusDotKind.Cyan
        RaftColor.AccentOrange -> RaftAgentStatusDotKind.Orange
        else -> RaftAgentStatusDotKind.Gray
    }
}

/**
 * The words beside an agent's light outside the app (the Android home-screen widget), from the
 * agent list's row: the app's own status words (raft-design patterns/status.md, "Working…" and
 * "Thinking…" with their ellipsis), resolved later in the app language.
 *
 * - A held agent ([blockedReason]) says why: "Computer update required", "Computer access revoked…".
 * - Working, thinking or in error: what it is doing ([activityDetail], the daemon's own words), else
 *   the status word.
 * - Online, idle, offline, disconnected: the status word.
 * - No status: [runtime], else [description], else "Offline" (the light is gray too).
 */
fun raftAgentWidgetStatusText(
    statusWireValue: String?,
    activityDetail: String?,
    blockedReason: String?,
    external: Boolean,
    runtime: String?,
    description: String?,
    runningWorkers: Int? = null,
): StringDesc {
    if (!external) agentBlockedState(blockedReason, runtime)?.let { return it.statusLabelRes.desc() }
    val status = AgentStatus.fromWireValue(statusWireValue)
    val detail =
        activityDetail?.trim()?.takeIf { it.isNotBlank() && !it.trimEnd('.', '…').equals(status?.label, ignoreCase = true) }
    return when (status) {
        AgentStatus.Working -> detail?.desc() ?: Res.string.member_row_status_working.desc()
        AgentStatus.Thinking -> detail?.desc() ?: Res.string.member_row_status_thinking.desc()
        AgentStatus.Error -> detail?.desc() ?: Res.string.member_row_status_error.desc()
        AgentStatus.Online -> {
            val workers =
                agentRunningWorkersDisplayOf(
                    status = status,
                    runningWorkers = runningWorkers,
                    external = external,
                )
            when {
                workers == null -> Res.string.member_row_status_online.desc()
                workers.count == 1 -> Res.string.agent_status_working_through_worker.desc()
                else -> Res.string.agent_status_working_through_workers.format(workers.count)
            }
        }
        AgentStatus.Idle -> Res.string.member_row_status_idle.desc()
        AgentStatus.Disconnected -> Res.string.member_row_status_disconnected.desc()
        AgentStatus.Offline -> Res.string.member_row_status_offline.desc()
        null ->
            runtime?.trim()?.takeIf { it.isNotBlank() }?.desc()
                ?: description?.trim()?.takeIf { it.isNotBlank() }?.desc()
                ?: Res.string.member_row_status_offline.desc()
    }
}
