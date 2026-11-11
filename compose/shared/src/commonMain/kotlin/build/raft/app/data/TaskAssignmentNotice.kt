package build.raft.app.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/*
 * The structure of an assignment line in a task's conversation ("📌 Otter assigned #51 "…" to
 * @xxchan: <reason>", "📌 Otter took #51 …", "🔓 Otter unassigned #51 …"), carried as the system
 * message's `actionMetadata` (botiverse/slock `packages/shared/src/taskAssignmentNotice.ts`,
 * #13927). The text stays the record; the App reads this to draw a hand-off with a reason as a
 * card ([isTaskHandoffWithReason]) and to see when the task later comes back to whoever handed it
 * off ([taskHandoffReturns], web `taskHandoffNotice.ts`).
 */

/** `TASK_ASSIGNMENT_NOTICE_KIND`: the system message's `actionMetadata.kind` (the stored spelling). */
const val TASK_ASSIGNMENT_NOTICE_KIND: String = "task-assignment"

/** `TaskAssignmentNoticeParty.type`. */
enum class TaskAssignmentPartyType(val wireValue: String) {
    User("user"),
    Agent("agent"),
    ;

    companion object {
        fun fromWire(value: String?): TaskAssignmentPartyType? = entries.firstOrNull { it.wireValue == value }
    }
}

/** `TaskAssignmentNoticeParty`: who changed the assignee, or the new assignee. */
data class TaskAssignmentNoticeParty(
    val type: TaskAssignmentPartyType,
    val id: String,
    /** Handle (`@name`), when it could be resolved. */
    val name: String?,
    /** The name people read, when it could be resolved. */
    val displayName: String?,
)

/** `TaskAssignmentNoticeMetadata`. */
data class TaskAssignmentNotice(
    val taskNumber: Int,
    /** Who changed the assignee. */
    val actor: TaskAssignmentNoticeParty,
    /** The new assignee; null when the assignee was cleared. */
    val assignee: TaskAssignmentNoticeParty?,
    /** A hand-off to someone else: what the task waits on them for. */
    val reason: String? = null,
)

private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun readParty(value: JsonElement?): TaskAssignmentNoticeParty? {
    val party = value as? JsonObject ?: return null
    val type = TaskAssignmentPartyType.fromWire(party["type"].stringOrNull()) ?: return null
    val id = party["id"].stringOrNull()?.takeIf { it.isNotEmpty() } ?: return null
    return TaskAssignmentNoticeParty(
        type = type,
        id = id,
        name = party["name"].stringOrNull()?.takeIf { it.isNotEmpty() },
        displayName = party["displayName"].stringOrNull()?.takeIf { it.isNotEmpty() },
    )
}

/** `readTaskAssignmentNotice`, rule for rule: the assignment line's structure, null for anything else. */
fun readTaskAssignmentNotice(actionMetadata: JsonElement?): TaskAssignmentNotice? {
    val metadata = actionMetadata as? JsonObject ?: return null
    if (metadata["kind"].stringOrNull() != TASK_ASSIGNMENT_NOTICE_KIND) return null
    val taskNumber = (metadata["taskNumber"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
    val actor = readParty(metadata["actor"]) ?: return null
    val rawAssignee = metadata["assignee"]
    val assignee = if (rawAssignee is JsonNull) null else readParty(rawAssignee) ?: return null
    val reason = metadata["reason"].stringOrNull()?.takeIf { it.isNotBlank() }
    return TaskAssignmentNotice(taskNumber = taskNumber.toInt(), actor = actor, assignee = assignee, reason = reason)
}

/** Web `sameParty`. */
fun TaskAssignmentNoticeParty.sameParty(other: TaskAssignmentNoticeParty?): Boolean =
    other != null && type == other.type && id == other.id

/** `isTaskHandoffWithReason`: the task went to someone other than the actor, saying why. */
val TaskAssignmentNotice.isTaskHandoffWithReason: Boolean
    get() = reason != null && assignee != null && !assignee.sameParty(actor)

/** A hand-off with a reason (web `readTaskHandoffNotice`): the notice when [isTaskHandoffWithReason], else null. */
fun TaskAssignmentNotice?.asTaskHandoff(): TaskAssignmentNotice? = this?.takeIf { it.isTaskHandoffWithReason }

/** When a hand-off came back to whoever made it (web `TaskHandoffReturn`). */
data class TaskHandoffReturn(
    /** When it came back (the later line's `createdAt`). */
    val at: String,
    /** Who gave it back: the hand-off's actor (took it back) or someone else (handed it back). */
    val by: TaskAssignmentNoticeParty,
)

/** One line of a task's conversation as [taskHandoffReturns] reads it. */
data class TaskAssignmentTimelineLine(
    val messageId: String,
    val createdAt: String,
    /** The line's assignment notice; null for any other message (and for a non-system one). */
    val notice: TaskAssignmentNotice?,
)

/**
 * Web `taskHandoffReturns`: for each hand-off with a reason, the first later assignment line that
 * gives the task back to whoever handed it off (they took it, or someone assigned it to them). A
 * hand-off another one replaced before it came back stays open: only a return to its own actor
 * closes it. [lines] are in timeline order.
 */
fun taskHandoffReturns(lines: List<TaskAssignmentTimelineLine>): Map<String, TaskHandoffReturn> {
    val returns = LinkedHashMap<String, TaskHandoffReturn>()
    val open = mutableListOf<Pair<String, TaskAssignmentNoticeParty>>()
    for (line in lines) {
        val notice = line.notice ?: continue
        for (i in open.indices.reversed()) {
            val (id, actor) = open[i]
            if (actor.sameParty(notice.assignee)) {
                returns[id] = TaskHandoffReturn(at = line.createdAt, by = notice.actor)
                open.removeAt(i)
            }
        }
        if (notice.isTaskHandoffWithReason) open += line.messageId to notice.actor
    }
    return returns
}

/*
 * The notice travels on a projected message as metadata strings, like a channel announcement's
 * (ChannelAnnouncementApi.kt), and is read back where the line is drawn.
 */
private const val NoticeMetadataPrefix = "taskAssignment."
private const val NoticeMetadataTaskNumber = "${NoticeMetadataPrefix}taskNumber"
private const val NoticeMetadataReason = "${NoticeMetadataPrefix}reason"
private const val ActorPrefix = "${NoticeMetadataPrefix}actor"
private const val AssigneePrefix = "${NoticeMetadataPrefix}assignee"

/** Whether [key] is one of the notice's metadata strings (left out of a system line's details). */
internal fun isTaskAssignmentNoticeMetadataKey(key: String): Boolean = key.startsWith(NoticeMetadataPrefix)

private fun MutableMap<String, String>.putParty(prefix: String, party: TaskAssignmentNoticeParty) {
    put("${prefix}Type", party.type.wireValue)
    put("${prefix}Id", party.id)
    party.name?.let { put("${prefix}Name", it) }
    party.displayName?.let { put("${prefix}DisplayName", it) }
}

private fun Map<String, String>.party(prefix: String): TaskAssignmentNoticeParty? {
    val type = TaskAssignmentPartyType.fromWire(this["${prefix}Type"]) ?: return null
    val id = this["${prefix}Id"]?.takeIf { it.isNotEmpty() } ?: return null
    return TaskAssignmentNoticeParty(type, id, this["${prefix}Name"]?.takeIf { it.isNotEmpty() }, this["${prefix}DisplayName"]?.takeIf { it.isNotEmpty() })
}

/** The metadata strings the notice projects to (no assignee keys: the assignee was cleared). */
fun TaskAssignmentNotice.toMessageMetadata(): Map<String, String> =
    buildMap {
        put(NoticeMetadataTaskNumber, taskNumber.toString())
        putParty(ActorPrefix, actor)
        assignee?.let { putParty(AssigneePrefix, it) }
        reason?.let { put(NoticeMetadataReason, it) }
    }

/** The notice a projected message's metadata carries; null for any other message. */
fun taskAssignmentNoticeOf(metadata: Map<String, String>): TaskAssignmentNotice? {
    val taskNumber = metadata[NoticeMetadataTaskNumber]?.toIntOrNull() ?: return null
    val actor = metadata.party(ActorPrefix) ?: return null
    return TaskAssignmentNotice(
        taskNumber = taskNumber,
        actor = actor,
        assignee = metadata.party(AssigneePrefix),
        reason = metadata[NoticeMetadataReason]?.takeIf { it.isNotBlank() },
    )
}
