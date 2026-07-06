package build.raft.app.data

import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Presence

internal data class AgentActivityStatusProjection(
    val status: AgentStatus,
    val detail: String? = null
) {
    val presence: Presence
        get() = status.presence
}

internal fun projectAgentActivityStatus(
    kind: String?,
    activity: String? = null,
    detail: String? = null,
    text: String? = null,
    toolName: String? = null
): AgentActivityStatusProjection? {
    val normalizedKind = kind?.trim()?.lowercase() ?: return null
    return when (normalizedKind) {
        "thinking" ->
            AgentActivityStatusProjection(
                status = AgentStatus.Thinking,
                detail = text.projectionNonBlankOrNull() ?: detail.projectionNonBlankOrNull() ?: "Thinking..."
            )
        "tool_start" ->
            AgentActivityStatusProjection(
                status = AgentStatus.Working,
                detail = toolName?.toAgentToolActivityLabel() ?: "Working"
            )
        "compaction_started" ->
            AgentActivityStatusProjection(
                status = AgentStatus.Working,
                detail = "Compacting context..."
            )
        "compaction_finished" ->
            AgentActivityStatusProjection(
                status = AgentStatus.Online,
                detail = ""
            )
        "status", "agent_status" -> {
            val status = AgentStatus.fromAgentActivityWireValue(activity)
                ?: AgentStatus.fromWireValue(activity)
                ?: return null
            AgentActivityStatusProjection(
                status = status,
                detail = detail ?: ""
            )
        }
        else -> null
    }
}

internal fun List<AgentActivityEntry>.latestAgentActivityStatusProjection(): AgentActivityStatusProjection? {
    for (index in indices.reversed()) {
        val entry = this[index]
        val projection =
            projectAgentActivityStatus(
                kind = entry.type,
                activity = entry.statusActivity,
                detail = entry.statusDetail ?: entry.body,
                text = entry.body,
                toolName = entry.toolName
            )
        if (projection != null) return projection
    }
    return null
}

internal fun AgentDetailSummary?.agentActivityStatusFallbackProjection(): AgentActivityStatusProjection? {
    if (this == null) return null
    val status = AgentStatus.fromWireValue(activity) ?: AgentStatus.fromWireValue(status) ?: return null
    return AgentActivityStatusProjection(
        status = status,
        detail = activityDetail.projectionNonBlankOrNull()
    )
}

internal fun AgentActivityStatusProjection.toMemberPresenceUpdated(
    agentId: MemberId,
    serverSeq: Long? = null
): MemberPresenceUpdated =
    MemberPresenceUpdated(
        memberId = agentId,
        presence = presence,
        kind = MemberKind.Agent,
        agentStatus = status,
        statusLabel = detail,
        serverSeq = serverSeq
    )

private fun String.toAgentToolActivityLabel(): String {
    val normalized = trim()
        .substringAfterLast("__")
        .substringAfterLast("/")
        .substringAfterLast(".")
        .takeIf { it.isNotBlank() }
        ?: return "Working"
    return when (normalized) {
        "read_file" -> "Reading file..."
        "write_file" -> "Writing file..."
        "upload_file" -> "Uploading file..."
        "view_file" -> "Viewing file..."
        "schedule_reminder" -> "Scheduling reminder..."
        "list_reminders" -> "Listing reminders..."
        "cancel_reminder" -> "Canceling reminder..."
        "run_command" -> "Running command..."
        else -> "Using ${normalized.truncateAgentToolLabel()}..."
    }
}

private fun String.truncateAgentToolLabel(): String =
    if (length <= 24) this else take(22) + ".."

private fun String?.projectionNonBlankOrNull(): String? =
    this?.takeIf { it.isNotBlank() }
