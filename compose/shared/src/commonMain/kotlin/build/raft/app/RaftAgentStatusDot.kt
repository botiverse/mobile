package build.raft.app

import build.raft.app.model.AgentStatus
import build.raft.app.model.Presence

/**
 * The app's agent status dot as a colour family, for hosts that draw it outside Compose (the
 * Android home-screen widget). Resolved by [raftAgentStatusIndicatorState], the same mapping as
 * every dot in the app: working / thinking yellow, online / idle lime, external agents cyan (lime
 * inside the 120s online window), error orange, and offline, disconnected or unknown gray.
 */
enum class RaftAgentStatusDotKind { Lime, Yellow, Cyan, Orange, Gray }

fun raftAgentStatusDotKind(
    statusWireValue: String?,
    external: Boolean,
    lastSeenAt: String? = null,
    nowEpochMillis: Long? = null,
): RaftAgentStatusDotKind {
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
