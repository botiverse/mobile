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
    val evidenceTimestamp: Long? = null,
    /**
     * Server `detailKind` of the status this projection came from
     * ("starting", "runtime_starting", "compacting_context", ...). For a
     * client-observed `compaction_started` entry it is set to
     * `compacting_context` so the UI localizes both the same way.
     */
    val detailKind: String? = null,
    /**
     * Set when [detail] is a client-built tool label (a `tool_start` entry):
     * the normalized tool name, so the UI can localize the label instead of
     * printing the English [detail].
     */
    val toolName: String? = null
) {
    val presence: Presence
        get() = status.presence
}

internal fun projectAgentActivityStatus(
    kind: String?,
    activity: String? = null,
    detail: String? = null,
    text: String? = null,
    toolName: String? = null,
    detailKind: String? = null
): AgentActivityStatusProjection? {
    val normalizedKind = kind?.trim()?.lowercase() ?: return null
    return when (normalizedKind) {
        "thinking" ->
            AgentActivityStatusProjection(
                status = AgentStatus.Thinking,
                detail = text.projectionNonBlankOrNull() ?: detail.projectionNonBlankOrNull() ?: "Thinking..."
            )
        "tool_start" -> {
            val normalizedTool = toolName?.normalizedAgentToolName()
            AgentActivityStatusProjection(
                status = AgentStatus.Working,
                detail = normalizedTool?.toAgentToolActivityLabel() ?: "Working",
                toolName = normalizedTool
            )
        }
        "compaction_started" ->
            AgentActivityStatusProjection(
                status = AgentStatus.Working,
                detail = "Compacting context...",
                detailKind = AgentActivityDetailKindCompactingContext
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
                detail = detail ?: "",
                detailKind = detailKind?.trim()?.takeIf { it.isNotBlank() }
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
                toolName = entry.toolName,
                detailKind = entry.statusDetailKind
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
        detailKind = detailKind,
        activityToolName = toolName,
        serverSeq = serverSeq
    )

internal const val AgentActivityDetailKindCompactingContext = "compacting_context"

/** Shared detail kind of a status whose automatic wakes the server paused (it carries `wakeCrashLoop`). */
internal const val AgentActivityDetailKindWakeCrashLoopBlocked = "wake_crash_loop_blocked"

/** `mcp__chat__read_file` / `chat/read_file` / `chat.read_file` -> `read_file`; null when nothing is left. */
internal fun String.normalizedAgentToolName(): String? =
    trim()
        .substringAfterLast("__")
        .substringAfterLast("/")
        .substringAfterLast(".")
        .takeIf { it.isNotBlank() }

/** English fallback for a normalized tool name; the UI localizes the same mapping. */
private fun String.toAgentToolActivityLabel(): String {
    val normalized = this
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

internal fun String.truncateAgentToolLabel(): String =
    if (length <= 24) this else take(22) + ".."

private fun String?.projectionNonBlankOrNull(): String? =
    this?.takeIf { it.isNotBlank() }
