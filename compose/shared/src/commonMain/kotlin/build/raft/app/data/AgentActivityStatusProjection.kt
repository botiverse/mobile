package build.raft.app.data

import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Presence

internal data class AgentActivityStatusProjection(
    val status: AgentStatus,
    val detail: String? = null,
    /**
     * task #137(b): freshness evidence of the source snapshot — the
     * server-assigned trajectory timestamp (epoch millis) of the entry this
     * projection was taken from. Null for evidence-less fallbacks (detail
     * summary, current-activity list entry). The presence registry keeps a
     * per-owner/member watermark of the newest evidence seen and rejects
     * older / evidence-less projections, so a late stale REST snapshot can
     * never displace a fresher one.
     */
    val evidenceTimestamp: Long? = null
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
            val status =
                normalizedAgentRuntimeStatus(
                    activity = activity,
                    lifecycleStatus = null
                ) ?: return null
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
        // The chosen entry's server timestamp is the projection's freshness
        // evidence (task #137(b)); trajectory entries carry epoch millis,
        // evidence-less fallbacks carry none.
        if (projection != null) {
            return projection.copy(evidenceTimestamp = entry.timestamp?.toLongOrNull())
        }
    }
    return null
}

internal fun AgentDetailSummary?.agentActivityStatusFallbackProjection(): AgentActivityStatusProjection? {
    if (this == null) return null
    val status = normalizedAgentRuntimeStatus(activity = activity, lifecycleStatus = status) ?: return null
    return AgentActivityStatusProjection(
        status = status,
        detail = activityDetail.projectionNonBlankOrNull()
    )
}

internal fun normalizedAgentRuntimeStatus(
    activity: String?,
    lifecycleStatus: String?
): AgentStatus? {
    // `active` describes account lifecycle, not runtime liveness. Treat it as
    // unknown even if it arrives in the activity slot from a mixed read model,
    // then continue to any explicit offline lifecycle fact.
    val normalizedActivity = activity?.trim()
    val activityStatus =
        if (normalizedActivity.equals("active", ignoreCase = true)) {
            null
        } else {
            AgentStatus.fromAgentActivityWireValue(normalizedActivity)
                ?: AgentStatus.fromWireValue(normalizedActivity)
        }
    if (activityStatus != null) return activityStatus

    val normalizedLifecycleStatus = lifecycleStatus?.trim()
    return when {
        normalizedLifecycleStatus.equals("active", ignoreCase = true) -> null
        normalizedLifecycleStatus.equals("stopped", ignoreCase = true) -> AgentStatus.Offline
        else -> AgentStatus.fromWireValue(normalizedLifecycleStatus)
    }
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
