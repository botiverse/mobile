package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

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
 * #13927, #15631). The text stays the record; the App reads this to draw every hand-off to someone
 * else (with or without a reason) as its actor's message with a card ([isTaskHandoff]) and to
 * resolve who held the task before and whether it goes back ([taskHandoffTimeline], web
 * `taskHandoffNotice.ts`).
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

/** `TaskAssignmentNoticeParty`: who changed the assignee, the new assignee, or who held it before. */
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
    /**
     * Who held the task before, when someone held it and it changed hands (botiverse/slock
     * #15631): with [actor] and [assignee], the three people a reassignment involves. Null when
     * nobody held it ([previousAssigneeRecorded] = true) or on lines written before it was
     * recorded ([previousAssigneeRecorded] = false).
     */
    val previousAssignee: TaskAssignmentNoticeParty? = null,
    /**
     * True when `previousAssignee` was explicitly recorded on the line (either a party or `null`
     * for nobody); false when absent (older lines before #15631), so [taskHandoffTimeline] falls
     * back to the latest earlier assignment or release line's holder.
     */
    val previousAssigneeRecorded: Boolean = previousAssignee != null,
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
    // Null (nobody held it) stays apart from absent (an older line).
    val rawPrevious = metadata["previousAssignee"]
    val previousAssignee: TaskAssignmentNoticeParty?
    val previousAssigneeRecorded: Boolean
    when {
        rawPrevious is JsonNull -> {
            previousAssignee = null
            previousAssigneeRecorded = true
        }
        rawPrevious != null -> {
            val parsed = readParty(rawPrevious)
            previousAssignee = parsed
            previousAssigneeRecorded = parsed != null
        }
        else -> {
            previousAssignee = null
            previousAssigneeRecorded = false
        }
    }
    return TaskAssignmentNotice(
        taskNumber = taskNumber.toInt(),
        actor = actor,
        assignee = assignee,
        reason = reason,
        previousAssignee = previousAssignee,
        previousAssigneeRecorded = previousAssigneeRecorded,
    )
}

/** Web `sameParty`. */
fun TaskAssignmentNoticeParty.sameParty(other: TaskAssignmentNoticeParty?): Boolean =
    other != null && type == other.type && id == other.id

/**
 * Web `isTaskHandoff` (botiverse/slock #15631): the task went to someone other than whoever moved
 * it, with or without a reason. Claims ("took") and unassigns stay one-line events.
 */
val TaskAssignmentNotice.isTaskHandoff: Boolean
    get() = assignee != null && !assignee.sameParty(actor)

/** A hand-off (web `readTaskHandoffNotice`): the notice when [isTaskHandoff], else null. */
fun TaskAssignmentNotice?.asTaskHandoff(): TaskAssignmentNotice? = this?.takeIf { it.isTaskHandoff }

/** A hand-off's place in its conversation's timeline (web `TaskHandoffTimeline`, #15631). */
data class TaskHandoffTimeline(
    /**
     * Who held the task before the hand-off: its line's `previousAssignee` (null: nobody), or for
     * a line written before that was recorded, the assignee of the latest earlier assignment or
     * release line here (null when there is none).
     */
    val previous: TaskAssignmentNoticeParty?,
    /**
     * The task goes back to whoever handed it to the actor: the latest earlier line here that gave
     * the task to the actor was made by the new assignee.
     */
    val back: Boolean,
)

/** One line of a task's conversation as [taskHandoffTimeline] reads it. */
data class TaskAssignmentTimelineLine(
    val messageId: String,
    /** The line's assignment notice; null for any other message (and for a non-system one). */
    val notice: TaskAssignmentNotice?,
)

/**
 * Web `taskHandoffTimeline` (#15631): for each hand-off in a task's conversation, who held the
 * task before it and whether it hands the task back. [lines] are in timeline order.
 */
fun taskHandoffTimeline(lines: List<TaskAssignmentTimelineLine>): Map<String, TaskHandoffTimeline> {
    val timeline = LinkedHashMap<String, TaskHandoffTimeline>()
    var holder: TaskAssignmentNoticeParty? = null
    // Who gave the task to whom, latest last.
    val given = mutableListOf<Pair<TaskAssignmentNoticeParty, TaskAssignmentNoticeParty>>()
    for (line in lines) {
        val notice = line.notice ?: continue
        if (notice.isTaskHandoff) {
            val assignee = notice.assignee
            timeline[line.messageId] =
                TaskHandoffTimeline(
                    previous = if (notice.previousAssigneeRecorded) notice.previousAssignee else holder,
                    back = assignee != null && lastGivenBy(given, notice.actor)?.sameParty(assignee) == true,
                )
        }
        notice.assignee?.let { given += it to notice.actor }
        holder = notice.assignee
    }
    return timeline
}

/** Who made the latest line here that gave the task to [party]; null when none did. */
private fun lastGivenBy(
    given: List<Pair<TaskAssignmentNoticeParty, TaskAssignmentNoticeParty>>,
    party: TaskAssignmentNoticeParty,
): TaskAssignmentNoticeParty? {
    for (i in given.indices.reversed()) {
        val (to, by) = given[i]
        if (to.sameParty(party)) return by
    }
    return null
}

/*
 * The notice travels on a projected message as metadata strings, like a channel announcement's
 * (ChannelAnnouncementApi.kt), and is read back where the line is drawn.
 */
private const val NoticeMetadataPrefix = "taskAssignment."
private const val NoticeMetadataTaskNumber = "${NoticeMetadataPrefix}taskNumber"
private const val NoticeMetadataReason = "${NoticeMetadataPrefix}reason"
private const val NoticeMetadataPreviousAssigneeNone = "${NoticeMetadataPrefix}previousAssigneeNone"
private const val ActorPrefix = "${NoticeMetadataPrefix}actor"
private const val AssigneePrefix = "${NoticeMetadataPrefix}assignee"
private const val PreviousAssigneePrefix = "${NoticeMetadataPrefix}previousAssignee"

/** Whether [key] is one of the notice's metadata strings (left out of a system line's details). */
@RaftCoreInternalApi fun isTaskAssignmentNoticeMetadataKey(key: String): Boolean = key.startsWith(NoticeMetadataPrefix)

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
        when {
            previousAssignee != null -> putParty(PreviousAssigneePrefix, previousAssignee)
            previousAssigneeRecorded -> put(NoticeMetadataPreviousAssigneeNone, "1")
        }
    }

/** The notice a projected message's metadata carries; null for any other message. */
fun taskAssignmentNoticeOf(metadata: Map<String, String>): TaskAssignmentNotice? {
    val taskNumber = metadata[NoticeMetadataTaskNumber]?.toIntOrNull() ?: return null
    val actor = metadata.party(ActorPrefix) ?: return null
    val previousParty = metadata.party(PreviousAssigneePrefix)
    val previousNone = metadata[NoticeMetadataPreviousAssigneeNone] == "1"
    return TaskAssignmentNotice(
        taskNumber = taskNumber,
        actor = actor,
        assignee = metadata.party(AssigneePrefix),
        reason = metadata[NoticeMetadataReason]?.takeIf { it.isNotBlank() },
        previousAssignee = previousParty,
        previousAssigneeRecorded = previousParty != null || previousNone,
    )
}
