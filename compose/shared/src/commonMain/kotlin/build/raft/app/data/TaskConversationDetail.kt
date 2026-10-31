package build.raft.app.data

import build.raft.app.RaftTaskConversationChannelKind
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.TaskId
import build.raft.app.model.TaskSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val taskConversationDetailJson = Json { ignoreUnknownKeys = true }

/**
 * Task v2: the parent context of a task conversation channel as `GET /api/channels/:id` returns it
 * (slock `routes/channels.ts` spreads the `channels` row: `type: "task"`, `taskId`, and
 * `parentChannelId` = the channel holding the task, this server's local channel for a joint
 * projection; `schema.ts` `channels`).
 */
@Serializable
internal data class TaskConversationChannelDetailWireDto(
    val id: String,
    val type: String,
    val parentChannelId: String? = null,
    val taskId: String? = null,
)

/** A task conversation's parent context: the task and the (local) channel that holds it. */
data class TaskConversationChannelRef(
    val conversationChannelId: ChannelId,
    val parentChannelId: ChannelId,
    val taskId: TaskId,
)

/** The parent context of a `type: "task"` channel detail; `null` for any other channel or a partial row. */
internal fun parseTaskConversationChannelDetail(bodyText: String): TaskConversationChannelRef? {
    val wire =
        runCatching {
            taskConversationDetailJson.decodeFromString(TaskConversationChannelDetailWireDto.serializer(), bodyText)
        }.getOrNull() ?: return null
    if (wire.type != RaftTaskConversationChannelKind) return null
    val id = wire.id.trim().takeIf { it.isNotBlank() } ?: return null
    val parent = wire.parentChannelId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val task = wire.taskId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return TaskConversationChannelRef(ChannelId(id), ChannelId(parent), TaskId(task))
}

/**
 * `#channel:#7` / `dm:@peer:#7` (web `formatTaskConversationRef` over shared `formatRaftChannelTaskRef`
 * / `formatRaftDmTaskRef`, separator `:#`); the bare `#7` without a name.
 */
internal fun formatTaskConversationRef(
    taskNumber: Int,
    channelName: String?,
    parentKind: ConversationKind?,
): String {
    val name = channelName?.trim()?.removePrefix("#")?.removePrefix("@")?.takeIf { it.isNotBlank() }
    return when {
        name == null -> "#$taskNumber"
        parentKind == ConversationKind.DirectMessage -> "dm:@$name:#$taskNumber"
        else -> "#$name:#$taskNumber"
    }
}

/** `#channel:#7 title` (web `formatTaskConversationTitle`). */
internal fun formatTaskConversationTitle(ref: String, title: String?): String =
    title?.trim()?.takeIf { it.isNotBlank() }?.let { "$ref $it" } ?: ref

/**
 * The conversation summary of a task conversation. A task conversation has no member rows of its
 * own (slock `taskConversationService.ts` writes only follows), so its write authority is the task's
 * channel: [parent]'s `joined` / permissions (no "as task" sends). Without the parent the page fails
 * closed (read-only). The title is the task address `#channel:#7 title` when [task] is known; with
 * [taskPending] (the task is still being read) the name is blank so the page keeps its title.
 */
internal fun ConversationSummary.withTaskConversationContext(
    parent: ConversationSummary?,
    task: TaskSummary?,
    taskPending: Boolean = false,
): ConversationSummary {
    val title = task?.let { taskConversationDisplayTitle(parent, it) }
    val joined = parent?.joined ?: false
    return copy(
        kind = ConversationKind.Task,
        channelType = RaftTaskConversationChannelKind,
        name = title ?: if (taskPending) "" else name,
        joined = joined,
        permissions =
            (parent?.permissions ?: permissions).copy(
                canSend = joined && parent?.permissions?.canSend == true,
                canAttach = joined && parent?.permissions?.canAttach == true,
                canCreateTask = false,
            ),
    )
}

/** `#channel:#7 title` (or the bare task title without a number) for a task conversation header. */
internal fun taskConversationDisplayTitle(parent: ConversationSummary?, task: TaskSummary): String? =
    task.taskNumber?.let { number ->
        formatTaskConversationTitle(
            ref = formatTaskConversationRef(number, parent?.name ?: task.channelName, parent?.kind),
            title = task.title,
        )
    } ?: task.title.takeIf { it.isNotBlank() }
