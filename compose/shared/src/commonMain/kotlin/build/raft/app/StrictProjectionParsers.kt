package build.raft.app

import build.raft.app.model.MemberKind
import build.raft.app.model.MessageType
import build.raft.app.model.TaskStatus

internal enum class MemberKindWireContract {
    /** Member-detail identity compatibility boundary. */
    Identity,
    /** Frontend message, DM peer, task actor, saved, and search wire: user | agent. */
    InternalActor,
    /** Message sender slots that may carry a Slack-bridge external projection:
     *  user | agent | external_projection, plus `third_party_app` (the agent-facing name for an
     *  external_projection sender, slock `toAgentFacingActorType`) as External.
     *  Assignee/member slots keep InternalActor. */
    MessageSender,
    /** Inbox activity facts: user | agent | system. */
    InboxActor,
    /** Agent creator enrichment: human | agent. */
    AgentCreator,
    /** Client-normalized Thread/cache projection: human | agent | system. */
    Projected,
    /** Client-normalized Thread message author projection: human | agent | system | external. */
    ProjectedMessageAuthor,
}

internal fun String?.parsedMemberKindOrNull(
    contract: MemberKindWireContract = MemberKindWireContract.Identity,
): MemberKind? =
    when (contract) {
        MemberKindWireContract.Identity ->
            when (this?.trim()?.lowercase()) {
                "agent" -> MemberKind.Agent
                "human", "user", "member" -> MemberKind.Human
                "system" -> MemberKind.System
                else -> null
            }
        MemberKindWireContract.InternalActor ->
            when (this?.trim()?.lowercase()) {
                "agent" -> MemberKind.Agent
                "user" -> MemberKind.Human
                else -> null
            }
        MemberKindWireContract.MessageSender ->
            when (this?.trim()?.lowercase()) {
                "agent" -> MemberKind.Agent
                "user" -> MemberKind.Human
                "external_projection", "third_party_app" -> MemberKind.External
                else -> null
            }
        MemberKindWireContract.InboxActor ->
            when (this?.trim()?.lowercase()) {
                "agent" -> MemberKind.Agent
                "user" -> MemberKind.Human
                "system" -> MemberKind.System
                else -> null
            }
        MemberKindWireContract.AgentCreator ->
            when (this?.trim()?.lowercase()) {
                "agent" -> MemberKind.Agent
                "human" -> MemberKind.Human
                else -> null
            }
        MemberKindWireContract.Projected ->
            when (this?.trim()?.lowercase()) {
                "agent" -> MemberKind.Agent
                "human" -> MemberKind.Human
                "system" -> MemberKind.System
                else -> null
            }
        MemberKindWireContract.ProjectedMessageAuthor ->
            when (this?.trim()?.lowercase()) {
                "agent" -> MemberKind.Agent
                "human" -> MemberKind.Human
                "system" -> MemberKind.System
                "external" -> MemberKind.External
                else -> null
            }
    }

internal fun String?.parsedMessageTypeOrNull(): MessageType? =
    when (this?.trim()?.lowercase()) {
        "chat" -> MessageType.Chat
        "system" -> MessageType.System
        else -> null
    }

internal fun String?.parsedTaskStatusOrNull(): TaskStatus? = TaskStatus.fromWireValue(this)

/** Render-time consumer for values already normalized at an ingest boundary. */
internal fun String?.projectedMemberKindOrNull(): MemberKind? =
    parsedMemberKindOrNull(MemberKindWireContract.Projected)

internal fun String?.strictMemberKindOrNull(
    source: String,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
    contract: MemberKindWireContract = MemberKindWireContract.Identity,
): MemberKind? =
    parsedMemberKindOrNull(contract)
        ?: rejectProjectionWireValue(source, "member_kind", this, diagnostics)

internal fun String?.strictMessageTypeOrNull(
    source: String,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): MessageType? =
    parsedMessageTypeOrNull()
        ?: rejectProjectionWireValue(source, "message_type", this, diagnostics)

/**
 * A task status at an ingest boundary. A present but unknown value (a status a newer server
 * added) is logged and kept as [TaskStatus.Unknown], so the task still shows; only a missing
 * or blank status is rejected (null).
 */
internal fun String?.strictTaskStatusOrNull(
    source: String,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): TaskStatus? {
    parsedTaskStatusOrNull()?.let { return it }
    // The app's own token for Unknown, re-read from a projection it wrote: no new diagnostic.
    if (this?.trim()?.lowercase() == TaskStatus.UNKNOWN_WIRE_VALUE) return TaskStatus.Unknown
    if (this.isNullOrBlank()) return rejectProjectionWireValue(source, "task_status", this, diagnostics)
    diagnostics(
        "projection_value_unknown source=${source.strictProjectionDiagnosticToken()} " +
            "field=task_status value=${strictProjectionDiagnosticToken()}",
    )
    return TaskStatus.Unknown
}

/**
 * Logs a server value this build does not know and is about to drop, as a
 * `projection_value_unknown … action=dropped` line (passes [projectionRejectingDiagnosticsLogger]).
 */
internal fun reportUnknownProjectionValueDropped(
    source: String,
    field: String,
    value: String?,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
) {
    diagnostics(
        "projection_value_unknown source=${source.strictProjectionDiagnosticToken()} " +
            "field=$field value=${value.strictProjectionDiagnosticToken()} action=dropped",
    )
}

private fun <T> rejectProjectionWireValue(
    source: String,
    field: String,
    value: String?,
    diagnostics: (String) -> Unit,
): T? {
    diagnostics(
        "projection_value_rejected source=${source.strictProjectionDiagnosticToken()} " +
            "field=$field value=${value.strictProjectionDiagnosticToken()}",
    )
    return null
}

internal val strictProjectionDiagnosticsLogger: (String) -> Unit = { message ->
    raftLogWarn("StrictProjection", message)
}

internal val projectionRejectingDiagnosticsLogger: (String) -> Unit = { message ->
    if (
        message.startsWith("projection_value_rejected") ||
        message.startsWith("projection_value_unknown") ||
        message.startsWith(RealtimeEventDroppedToken)
    ) {
        strictProjectionDiagnosticsLogger(message)
    }
}

/** Prefix of the line a realtime ingress path writes when it drops an event (see [reportRealtimeEventDropped]). */
internal const val RealtimeEventDroppedToken = "realtime_event_dropped"

/**
 * The one diagnostic for a server value the app does not know (a new enum value, kind, type...).
 *
 * Writes `projection_value_unknown source=… field=… value=… action=…` so the line passes
 * [projectionRejectingDiagnosticsLogger] and lands in the local diagnostics ring on Android and
 * iOS (both go through [raftLogWarn]). [fallback] names what the app showed instead (for example
 * `channel_row`, `notice`); null means the row/event was dropped.
 *
 * Use it at every `else -> null` / `?: return null` that drops server data because of an unknown
 * type: the drop may stay, but it must never be silent.
 */
internal fun reportUnknownTypeDropped(
    source: String,
    field: String,
    value: String?,
    fallback: String? = null,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
) {
    val action = if (fallback == null) "dropped" else "fallback_${fallback.strictProjectionDiagnosticToken()}"
    diagnostics(
        "projection_value_unknown source=${source.strictProjectionDiagnosticToken()} " +
            "field=${field.strictProjectionDiagnosticToken()} value=${value.strictProjectionDiagnosticToken()} " +
            "action=$action",
    )
}

/**
 * A list row the app could not read and skipped (the rest of the list still shows). Same token and
 * filter as [reportUnknownTypeDropped]; [reason] is a short code such as `decode_failed`.
 */
internal fun reportProjectionRowDropped(
    source: String,
    reason: String,
    index: Int? = null,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
) {
    diagnostics(
        "projection_value_rejected source=${source.strictProjectionDiagnosticToken()} " +
            "field=row value=${reason.strictProjectionDiagnosticToken()}" +
            (index?.let { " index=$it" } ?: ""),
    )
}

/**
 * A realtime event the mapper/ingress dropped: `realtime_event_dropped eventName=… reason=…`.
 * [keys] are the payload's top-level key names (never values) so a missing field can be spotted.
 */
internal fun reportRealtimeEventDropped(
    eventName: String,
    reason: String,
    keys: Collection<String> = emptyList(),
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
) {
    val keyList = keys.take(24).joinToString(separator = ",") { it.strictProjectionDiagnosticToken() }
    diagnostics(
        "$RealtimeEventDroppedToken eventName=${eventName.realtimeEventNameToken()} " +
            "reason=${reason.strictProjectionDiagnosticToken()}" +
            (if (keyList.isEmpty()) "" else " keys=$keyList"),
    )
}

private fun String.realtimeEventNameToken(): String =
    trim()
        .take(64)
        .map { character ->
            if (character.isLetterOrDigit() || character in "_-:.") character else '_'
        }
        .joinToString(separator = "")
        .ifBlank { "blank" }

private fun String?.strictProjectionDiagnosticToken(): String {
    val clean =
        this
            ?.trim()
            ?.lowercase()
            ?.take(32)
            ?.map { character ->
                if (character.isLetterOrDigit() || character == '_' || character == '-') character else '_'
            }
            ?.joinToString(separator = "")
            .orEmpty()
    return clean.ifBlank { "blank" }
}
