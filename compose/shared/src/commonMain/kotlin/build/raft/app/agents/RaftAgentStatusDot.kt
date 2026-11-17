package build.raft.app

import build.raft.app.data.agentBlockedState
import build.raft.app.data.statusLabelRes
import build.raft.app.model.AgentStatus
import build.raft.app.model.Presence
import build.raft.app.resources.*

/**
 * The app's agent status dot as a colour family, for hosts that draw it outside Compose (the
 * Android home-screen widget). Resolved by [raftAgentStatusIndicatorState], the same mapping as
 * every dot in the app: working / thinking yellow, online / idle lime, external agents cyan (lime
 * inside the 120s online window), error orange, and offline, disconnected or unknown gray. An
 * agent held by its Computer ([blockedReason], [agentBlockedState]) is orange whatever its last
 * activity, as in the app.
 */
enum class RaftAgentStatusDotKind { Lime, Yellow, Cyan, Orange, Gray }

fun raftAgentStatusDotKind(
    statusWireValue: String?,
    external: Boolean,
    lastSeenAt: String? = null,
    nowEpochMillis: Long? = null,
    blockedReason: String? = null,
): RaftAgentStatusDotKind {
    if (!external && agentBlockedState(blockedReason) != null) return RaftAgentStatusDotKind.Orange
    val state =
        raftAgentStatusIndicatorState(
            status = AgentStatus.fromWireValue(statusWireValue),
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
): StringDesc {
    if (!external) agentBlockedState(blockedReason)?.let { return it.statusLabelRes.desc() }
    val status = AgentStatus.fromWireValue(statusWireValue)
    val detail =
        activityDetail?.trim()?.takeIf { it.isNotBlank() && !it.trimEnd('.', '…').equals(status?.label, ignoreCase = true) }
    return when (status) {
        AgentStatus.Working -> detail?.desc() ?: Res.string.member_row_status_working.desc()
        AgentStatus.Thinking -> detail?.desc() ?: Res.string.member_row_status_thinking.desc()
        AgentStatus.Error -> detail?.desc() ?: Res.string.member_row_status_error.desc()
        AgentStatus.Online -> Res.string.member_row_status_online.desc()
        AgentStatus.Idle -> Res.string.member_row_status_idle.desc()
        AgentStatus.Disconnected -> Res.string.member_row_status_disconnected.desc()
        AgentStatus.Offline -> Res.string.member_row_status_offline.desc()
        null ->
            runtime?.trim()?.takeIf { it.isNotBlank() }?.desc()
                ?: description?.trim()?.takeIf { it.isNotBlank() }?.desc()
                ?: Res.string.member_row_status_offline.desc()
    }
}
