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
     *  user | agent | external_projection. Assignee/member slots keep InternalActor. */
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
                "external_projection" -> MemberKind.External
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

internal fun String?.strictTaskStatusOrNull(
    source: String,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): TaskStatus? =
    parsedTaskStatusOrNull()
        ?: rejectProjectionWireValue(source, "task_status", this, diagnostics)

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
    slockLogWarn("StrictProjection", message)
}

internal val projectionRejectingDiagnosticsLogger: (String) -> Unit = { message ->
    if (message.startsWith("projection_value_rejected")) {
        strictProjectionDiagnosticsLogger(message)
    }
}

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
