package build.raft.app.data

import build.raft.app.shownAuthorName
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private val taskConversationDetailsJson = Json { ignoreUnknownKeys = true }

/**
 * Task v2: what the task dialog's head reads beside the task itself (web `TaskConversationDialog` /
 * `TaskProperties`, botiverse/slock@3f25e17ed). Every call is one the web dialog makes:
 *
 * - `GET /api/tasks/:taskId/history` → `{ events: TaskHistoryEvent[] }` (slock `routes/tasks.ts`
 *   `taskRouter.get("/:taskId/history")`, `services/taskService.ts` `listTaskHistory`): the
 *   append-only lifecycle, oldest first; `{ id, seq, eventType, actorType: "user" | "agent" |
 *   "system", actorName: string | null, payload: object, createdAt }`. 409 for a legacy task card
 *   (no canonical history). Web `taskStore.loadTaskHistory`.
 * - `GET /api/messages/context/:messageId?channelId&before=0&after=0`: the Source row's message
 *   (web `buildThreadParentContextRequest`, `TaskSourceProperty`).
 * - `GET /api/channels/:channelId/threads/:messageId` → `{ threadChannelId, replyCount,
 *   lastReplyAt }`, 404 `THREAD_NOT_FOUND` when the message has no thread (slock `routes/channels.ts`,
 *   `channelService.getThreadInfoForChannel`): a pre-v2 task's earlier discussion (web
 *   `TaskEarlierDiscussionProperty`).
 */
@Serializable
internal data class TaskHistoryResponseWireDto(
    val events: List<TaskHistoryEventWireDto> = emptyList(),
)

@Serializable
internal data class TaskHistoryEventWireDto(
    val id: String,
    val seq: Long? = null,
    val eventType: String,
    val actorType: String,
    val actorName: String? = null,
    val payload: JsonObject = JsonObject(emptyMap()),
    val createdAt: String,
)

/** One canonical task history event (slock `TaskHistoryEvent`). */
internal data class TaskHistoryEvent(
    val id: String,
    val eventType: String,
    val actorType: String,
    val actorName: String?,
    val createdAt: String,
    val payload: JsonObject,
)

/** `{ events }` of `GET /tasks/:id/history`; null for a body that is not that envelope. */
internal fun parseTaskHistory(bodyText: String): List<TaskHistoryEvent>? {
    val wire =
        runCatching { taskConversationDetailsJson.decodeFromString(TaskHistoryResponseWireDto.serializer(), bodyText) }
            .getOrNull() ?: return null
    return wire.events
        .filter { it.id.isNotBlank() && it.eventType.isNotBlank() }
        .map { event ->
            TaskHistoryEvent(
                id = event.id,
                eventType = event.eventType,
                actorType = event.actorType,
                actorName = event.actorName?.trim()?.takeIf { it.isNotBlank() },
                createdAt = event.createdAt,
                payload = event.payload,
            )
        }
}

/** What a history row says happened (web `historyTitle`); [Other] keeps an unknown `eventType`. */
internal sealed interface TaskHistoryKind {
    data object Created : TaskHistoryKind

    data object Amended : TaskHistoryKind

    data object StatusChanged : TaskHistoryKind

    data object AssigneeChanged : TaskHistoryKind

    data object Reopened : TaskHistoryKind

    data object Closed : TaskHistoryKind

    data class Other(val eventType: String) : TaskHistoryKind
}

/** An amended field (web `task.history.field.*`); [Other] keeps the payload key. */
internal sealed interface TaskHistoryField {
    data object Title : TaskHistoryField

    data object Description : TaskHistoryField

    data class Other(val key: String) : TaskHistoryField
}

internal data class TaskHistoryFieldChange(val field: TaskHistoryField, val from: String, val to: String)

/** The line under a history row's title (web `TimelineDetail` / `historyDetail`). */
internal sealed interface TaskHistoryDetail {
    /** "Task #7 · Status: Todo"; [status] null = a status the app does not know (shown as Todo, as web does). */
    data class Created(val taskNumber: String, val status: TaskStatus?) : TaskHistoryDetail

    /** Two status badges, `from → to`. */
    data class Transition(val from: TaskStatus, val to: TaskStatus) : TaskHistoryDetail

    /** A transition between statuses the app does not know: the raw values as text. */
    data class TransitionText(val from: String, val to: String) : TaskHistoryDetail

    data class FieldChanges(val changes: List<TaskHistoryFieldChange>) : TaskHistoryDetail

    /** "Assigned to {name}": [assigneeType] `agent` / `user`, resolved to a display name at render time. */
    data class Assigned(val assigneeType: String, val assigneeId: String) : TaskHistoryDetail

    data object Unassigned : TaskHistoryDetail

    /** Any other payload: `key: value` pairs joined with " · " (empty: no line). */
    data class Raw(val text: String) : TaskHistoryDetail
}

/** One row of the History timeline: title, `actor · time`, the marker's status, the detail line. */
internal data class TaskHistoryRow(
    val id: String,
    val kind: TaskHistoryKind,
    /** `actorName`, else the raw `actorType` (web `event.actorName ?? event.actorType`). */
    val actor: String,
    val createdAt: String,
    /** The status the marker is painted from (web `historyStatus`); null = the neutral marker. */
    val markerStatus: TaskStatus?,
    val detail: TaskHistoryDetail,
)

/** Web `TaskProperties`: resource receipts are CLI-only and stay out of the person-facing timeline. */
internal const val TaskHistoryResourceReceiptEventType = "resource_receipt_recorded"

internal fun List<TaskHistoryEvent>.toTaskHistoryRows(): List<TaskHistoryRow> =
    filter { it.eventType != TaskHistoryResourceReceiptEventType }.map { it.toTaskHistoryRow() }

internal fun TaskHistoryEvent.toTaskHistoryRow(): TaskHistoryRow =
    TaskHistoryRow(
        id = id,
        kind = taskHistoryKind(eventType),
        actor = actorName ?: actorType,
        createdAt = createdAt,
        markerStatus = historyMarkerStatus(),
        detail = historyDetail(),
    )

private fun taskHistoryKind(eventType: String): TaskHistoryKind =
    when (eventType) {
        "created" -> TaskHistoryKind.Created
        "amended" -> TaskHistoryKind.Amended
        "status_changed" -> TaskHistoryKind.StatusChanged
        "assignee_changed" -> TaskHistoryKind.AssigneeChanged
        "reopened" -> TaskHistoryKind.Reopened
        "closed" -> TaskHistoryKind.Closed
        else -> TaskHistoryKind.Other(eventType)
    }

private val TaskHistoryTransitionEventTypes = setOf("status_changed", "closed", "reopened")

/** Web `historyStatus`: the first of `to`, `status`, `from` that is a status; else closed / reopened imply one. */
private fun TaskHistoryEvent.historyMarkerStatus(): TaskStatus? =
    listOf("to", "status", "from")
        .firstNotNullOfOrNull { key -> payload.stringOrNull(key)?.let(::exactTaskStatusOrNull) }
        ?: when (eventType) {
            "closed" -> TaskStatus.Closed
            "reopened" -> TaskStatus.InProgress
            else -> null
        }

/** A wire status exactly as the server writes it (`todo`, `in_progress`, ...); anything else is not one. */
private fun exactTaskStatusOrNull(value: String): TaskStatus? =
    TaskStatus.entries.firstOrNull { it.toWireValue() == value }

private fun TaskHistoryEvent.historyDetail(): TaskHistoryDetail {
    if (eventType == "created") {
        val number = payload.displayValueOrNull("taskNumber") ?: "—"
        val status = payload.stringOrNull("status") ?: "todo"
        return TaskHistoryDetail.Created(taskNumber = number, status = exactTaskStatusOrNull(status))
    }
    if (eventType in TaskHistoryTransitionEventTypes) {
        val from = payload.displayValueOrNull("from")
        val to = payload.displayValueOrNull("to")
        if (from != null && to != null) {
            val fromStatus = exactTaskStatusOrNull(from)
            val toStatus = exactTaskStatusOrNull(to)
            return if (fromStatus != null && toStatus != null) {
                TaskHistoryDetail.Transition(fromStatus, toStatus)
            } else {
                TaskHistoryDetail.TransitionText(from, to)
            }
        }
    }
    val changes = payload["changes"]
    if (eventType == "amended" && changes is JsonObject) {
        return TaskHistoryDetail.FieldChanges(
            changes.map { (key, value) ->
                val field =
                    when (key) {
                        "title" -> TaskHistoryField.Title
                        "description" -> TaskHistoryField.Description
                        else -> TaskHistoryField.Other(key)
                    }
                if (value is JsonObject && "from" in value && "to" in value) {
                    TaskHistoryFieldChange(field, value.jsStringOrDash("from"), value.jsStringOrDash("to"))
                } else {
                    TaskHistoryFieldChange(field, "—", value.jsonDisplayString())
                }
            }
        )
    }
    if (eventType == "assignee_changed") {
        val assigneeId = payload.displayValueOrNull("assigneeId") ?: return TaskHistoryDetail.Unassigned
        val type = if (payload.stringOrNull("assigneeType") == "agent") "agent" else "user"
        return TaskHistoryDetail.Assigned(assigneeType = type, assigneeId = assigneeId)
    }
    return TaskHistoryDetail.Raw(
        payload.entries
            .filterNot { (_, value) -> value is JsonNull || (value is JsonPrimitive && value.booleanOrNull == false) }
            .joinToString(" · ") { (key, value) -> "$key: ${value.jsonDisplayString()}" }
    )
}

private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** JS `String(value)` for a present, non-null, non-empty-string value (web `p.from && p.to`). */
private fun JsonObject.displayValueOrNull(key: String): String? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    if (value is JsonPrimitive) {
        if (value.isString) return value.content.takeIf { it.isNotEmpty() }
        if (value.booleanOrNull == false) return null
        return value.contentOrNull
    }
    return value.toString()
}

/** JS `String(value ?? "—")`. */
private fun JsonObject.jsStringOrDash(key: String): String {
    val value = this[key]
    if (value == null || value is JsonNull) return "—"
    return if (value is JsonPrimitive) value.content else value.toString()
}

/** Web: a string as is, anything else as JSON. */
private fun JsonElement.jsonDisplayString(): String =
    if (this is JsonPrimitive && isString) content else toString()

/** The Source row's message: who said it, its first non-blank line and when (web `TaskSourceProperty`). */
internal data class TaskSourceMessagePreview(
    val messageId: MessageId,
    val author: MemberProfile?,
    val firstLine: String,
    val createdAt: String?,
)

/** The first non-blank line of [body], trimmed (web `content.split("\n").find(line => line.trim())`). */
internal fun taskSourcePreviewLine(body: String): String =
    body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

/** The source message out of a `messages/context` page; null when the page does not hold it. */
internal fun parseTaskSourceMessage(bodyText: String, channelId: ChannelId, messageId: MessageId): TaskSourceMessagePreview? {
    val page = parseSharedMessageContext(bodyText, ThreadId(channelId.value))
    val message = page.messages.firstOrNull { it.id == messageId } ?: return null
    return TaskSourceMessagePreview(
        messageId = message.id,
        // The task head shows this name; an unnamed External sender reads as the localized "External user".
        author = message.author.copy(displayName = message.shownAuthorName()),
        firstLine = taskSourcePreviewLine(message.body),
        createdAt = message.timestampLabel.takeIf { it.isNotBlank() },
    )
}

@Serializable
internal data class TaskEarlierDiscussionWireDto(
    val threadChannelId: String? = null,
    val replyCount: Int? = null,
)

/** A pre-v2 task's host-message thread: its size and (when it exists) its channel. */
internal data class TaskEarlierDiscussion(
    val hostMessageId: MessageId,
    val replyCount: Int,
    val threadChannelId: ChannelId?,
)

internal fun parseTaskEarlierDiscussion(bodyText: String, hostMessageId: MessageId): TaskEarlierDiscussion? {
    val wire =
        runCatching { taskConversationDetailsJson.decodeFromString(TaskEarlierDiscussionWireDto.serializer(), bodyText) }
            .getOrNull() ?: return null
    return TaskEarlierDiscussion(
        hostMessageId = hostMessageId,
        replyCount = (wire.replyCount ?: 0).coerceAtLeast(0),
        threadChannelId = wire.threadChannelId?.trim()?.takeIf { it.isNotBlank() }?.let(::ChannelId),
    )
}

/** The three reads of the task dialog's head, through the shared network client. */
internal class TaskConversationDetailsRepository(
    private val networkClient: RaftNetworkClient,
) {
    suspend fun loadHistory(serverId: ServerId, taskId: TaskId): DataResult<List<TaskHistoryEvent>> {
        val response =
            request {
                networkClient.request(DataRequestPriority.Visible, "tasks/${taskId.value}/history", serverIdOverride = serverId)
            } ?: return networkError("task_history_network")
        if (!response.isSuccessful) return httpError("task_history", response.code)
        val events = parseTaskHistory(response.bodyText.orEmpty()) ?: return httpError("task_history_parse", response.code)
        return DataResult.Content(events)
    }

    suspend fun loadSourceMessage(serverId: ServerId, channelId: ChannelId, messageId: MessageId): DataResult<TaskSourceMessagePreview> {
        val response =
            request {
                networkClient.request(
                    DataRequestPriority.Visible,
                    "messages/context/${messageId.value}",
                    query = listOf("channelId" to channelId.value, "before" to "0", "after" to "0"),
                    serverIdOverride = serverId,
                )
            } ?: return networkError("task_source_network")
        if (!response.isSuccessful) return httpError("task_source", response.code)
        val preview =
            parseTaskSourceMessage(response.bodyText.orEmpty(), channelId, messageId)
                ?: return DataResult.Error(ScreenError(code = "task_source_missing", retryable = false))
        return DataResult.Content(preview)
    }

    /**
     * 404 (`THREAD_NOT_FOUND`: the message has no thread): nothing earlier, 0 replies, and the row
     * hides (web). Any other failure (401 / 403 / 429 / 5xx / network) leaves the count unknown: the
     * row stays, reading "View".
     */
    suspend fun loadEarlierDiscussion(serverId: ServerId, channelId: ChannelId, hostMessageId: MessageId): DataResult<TaskEarlierDiscussion> {
        val response =
            request {
                networkClient.request(
                    DataRequestPriority.Background,
                    "channels/${channelId.value}/threads/${hostMessageId.value}",
                    serverIdOverride = serverId,
                )
            } ?: return networkError("task_earlier_discussion_network")
        if (!response.isSuccessful) {
            return if (response.code == 404) {
                DataResult.Content(TaskEarlierDiscussion(hostMessageId, replyCount = 0, threadChannelId = null))
            } else {
                httpError("task_earlier_discussion", response.code)
            }
        }
        return parseTaskEarlierDiscussion(response.bodyText.orEmpty(), hostMessageId)
            ?.let { DataResult.Content(it) }
            ?: httpError("task_earlier_discussion_parse", response.code)
    }

    private suspend fun request(block: suspend () -> HomeNetworkResponse): HomeNetworkResponse? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }

    private fun <T> networkError(code: String): DataResult<T> = DataResult.Error(ScreenError(code = code, retryable = true))

    private fun <T> httpError(prefix: String, code: Int): DataResult<T> =
        DataResult.Error(ScreenError(code = "${prefix}_$code", retryable = code >= 500))
}
