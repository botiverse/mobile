package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.Presence
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import ai.slock.compose.model.ThreadId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

private val tasksJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

class SharedTasksDataSource(
    private val api: SlockTasksApi
) {
    private val typedApi = SlockTasksApiClient(api)

    suspend fun loadTasks(
        serverId: ServerId,
        filter: TaskFilter = TaskFilter()
    ): DataResult<TaskSnapshot> =
        try {
            typedApi.serverTasks(serverId, DataRequestPriority.Visible, filter)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "tasks_network", retryable = true))
        }

    suspend fun updateTaskStatus(
        taskId: TaskId,
        status: TaskStatus
    ): DataResult<TaskActionResult> =
        try {
            typedApi.updateTaskStatus(taskId, status)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "tasks_status_network", retryable = true))
        }
}

data class TaskCreateDraft(
    val title: String,
    val description: String? = null
)

/**
 * Typed Tasks boundary.
 *
 * Server/channel task lists parse the upstream `tasks` wrapper, create uses a
 * `{ tasks: [...] }` body, message conversion uses `{ messageId }`, and status
 * mutation sends the canonical API status value. Unsupported platform routes
 * return 501 instead of exposing guessed wrappers to shared UI.
 */
interface SlockTasksApi {
    suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse
    suspend fun channelTasks(channelId: ChannelId, priority: DataRequestPriority): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun taskByNumber(channelId: ChannelId, taskNumber: Int, priority: DataRequestPriority): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun createTasks(channelId: ChannelId, drafts: List<TaskCreateDraft>, priority: DataRequestPriority): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun claimTask(taskId: TaskId): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun convertMessage(messageId: MessageId): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun unclaimTask(taskId: TaskId): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): HomeNetworkResponse
    suspend fun deleteTask(taskId: TaskId): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
}

fun buildSharedTaskStatusBody(status: TaskStatus): String =
    tasksJson.encodeToString(mapOf("status" to status.toApiValue()))

fun buildSharedTasksCreateBody(drafts: List<TaskCreateDraft>): String =
    tasksJson.encodeToString(
        mapOf(
            "tasks" to
                drafts.map { draft ->
                    buildMap {
                        put("title", draft.title.trim())
                        draft.description?.trim()?.takeIf { it.isNotBlank() }?.let { put("description", it) }
                    }
                }
        )
    )

fun buildSharedTaskConvertBody(messageId: MessageId): String =
    tasksJson.encodeToString(mapOf("messageId" to messageId.value))

internal class SlockTasksApiClient(
    private val transport: SlockTasksApi
) {
    suspend fun serverTasks(
        serverId: ServerId,
        priority: DataRequestPriority,
        filter: TaskFilter = TaskFilter()
    ): DataResult<TaskSnapshot> =
        transport.serverTasks(serverId, priority).toTasksResult(
            filter = filter,
            source = "tasks_server"
        )

    suspend fun channelTasks(
        channelId: ChannelId,
        priority: DataRequestPriority,
        filter: TaskFilter = TaskFilter()
    ): DataResult<TaskSnapshot> =
        transport.channelTasks(channelId, priority).toTasksResult(
            filter = filter,
            source = "tasks_channel"
        )

    suspend fun taskByNumber(
        channelId: ChannelId,
        taskNumber: Int,
        priority: DataRequestPriority
    ): DataResult<TaskActionResult> =
        transport.taskByNumber(channelId, taskNumber, priority).toTaskActionResult(source = "tasks_by_number")

    suspend fun createTasks(
        channelId: ChannelId,
        drafts: List<TaskCreateDraft>,
        priority: DataRequestPriority
    ): DataResult<TaskSnapshot> =
        transport.createTasks(channelId, drafts, priority).toTasksResult(source = "tasks_create")

    suspend fun claimTask(taskId: TaskId): DataResult<TaskActionResult> =
        transport.claimTask(taskId).toTaskActionResult(source = "tasks_claim")

    suspend fun convertMessage(messageId: MessageId): DataResult<TaskActionResult> =
        transport.convertMessage(messageId).toTaskActionResult(source = "tasks_convert_message")

    suspend fun unclaimTask(taskId: TaskId): DataResult<TaskActionResult> =
        transport.unclaimTask(taskId).toTaskActionResult(source = "tasks_unclaim")

    suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): DataResult<TaskActionResult> =
        transport.updateTaskStatus(taskId, status).toTaskActionResult(source = "tasks_status")

    suspend fun deleteTask(taskId: TaskId): DataResult<Unit> =
        transport.deleteTask(taskId).toTaskDeleteResult()
}

internal fun HomeNetworkResponse.toTasksResult(
    filter: TaskFilter = TaskFilter(),
    source: String = "tasks"
): DataResult<TaskSnapshot> {
    if (code == 401 || code == 403) {
        return DataResult.NoPermission(PermissionBlocker(code = "tasks_$code"))
    }
    if (!isSuccessful) {
        return DataResult.Error(ScreenError(code = "tasks_$code", retryable = true))
    }
    val root = bodyText?.toTasksJsonObject()
        ?: return DataResult.Error(ScreenError(code = "${source}_contract_mismatch", retryable = false))
    val items = root["tasks"] as? JsonArray
        ?: return DataResult.Error(ScreenError(code = "${source}_contract_mismatch", retryable = false))
    val snapshot = root.toTaskSnapshot().filteredBy(filter)
    return if (items.isEmpty()) {
        DataResult.Empty(EmptyReason.NoTasks)
    } else {
        DataResult.Content(snapshot)
    }
}

internal fun HomeNetworkResponse.toTaskActionResult(source: String = "tasks_action"): DataResult<TaskActionResult> {
    if (code == 401 || code == 403) {
        return DataResult.NoPermission(PermissionBlocker(code = "tasks_$code"))
    }
    if (!isSuccessful) {
        return DataResult.Error(ScreenError(code = "tasks_$code", retryable = true))
    }
    val root = bodyText?.toTasksJsonObject()
        ?: return DataResult.Error(ScreenError(code = "${source}_contract_mismatch", retryable = false))
    val task =
        root.firstObject("task")?.toTaskSummary()
            ?: return DataResult.Error(ScreenError(code = "${source}_contract_mismatch", retryable = false))
    return DataResult.Content(TaskActionResult(task))
}

internal fun HomeNetworkResponse.toTaskDeleteResult(): DataResult<Unit> {
    if (code == 401 || code == 403) {
        return DataResult.NoPermission(PermissionBlocker(code = "tasks_$code"))
    }
    if (!isSuccessful) {
        return DataResult.Error(ScreenError(code = "tasks_$code", retryable = true))
    }
    val root = bodyText?.toTasksJsonObject()
        ?: return DataResult.Error(ScreenError(code = "tasks_delete_contract_mismatch", retryable = false))
    return if (root.boolean("ok") == true) {
        DataResult.Content(Unit)
    } else {
        DataResult.Error(ScreenError(code = "tasks_delete_contract_mismatch", retryable = false))
    }
}

private fun JsonObject.toTaskSnapshot(): TaskSnapshot {
    val tasks =
        items("tasks")
            .mapNotNull { (it as? JsonObject)?.toTaskSummary() }
    return TaskSnapshot(
        tasks = tasks,
        summaryCounts = tasks.summaryCounts()
    )
}

private fun TaskSnapshot.filteredBy(filter: TaskFilter): TaskSnapshot {
    val filtered =
        tasks.filter { task ->
            filter.statuses.isEmpty() || task.status in filter.statuses
        }
    return copy(tasks = filtered, summaryCounts = filtered.summaryCounts())
}

private fun JsonObject.toTaskSummary(): TaskSummary? {
    val id = string("id", "taskId") ?: return null
    val taskNumber = int("taskNumber", "number")
    val title = taskDisplayTitle(taskNumber) ?: return null
    val assignee =
        firstObject(
            "assignee",
            "claimedBy",
            "assignedTo",
            "assigneeUser",
            "claimedByUser",
            "claimedByAgent"
        )?.toMemberProfile()
            ?: claimedByFallback()
    val messageId = string("messageId")?.let(::MessageId)
    val explicitThreadId =
        string(
            "threadChannelId",
            "replyThreadChannelId",
            "replyThreadId",
            "repliesThreadChannelId",
            "childThreadChannelId",
            "commentsThreadChannelId"
        )
    val nestedThreadId =
        firstObject("thread", "replyThread", "messageThread", "repliesThread")
            ?.string("threadChannelId", "replyThreadChannelId", "replyThreadId", "id", "channelId")
    val fallbackThreadId = string("threadId") ?: nestedThreadId
    val channelType = string("channelType", "type", "kind")?.lowercase()
    val channelId =
        string("channelId", "parentChannelId")
            ?: firstObject("channel", "parentChannel", "conversation")
                ?.string("id", "channelId", "parentChannelId")
            ?: if (channelType == "channel") fallbackThreadId else null
    val taskThreadId = explicitThreadId ?: fallbackThreadId
    val parentMessageId =
        string("parentMessageId", "parentId", "rootMessageId", "threadParentMessageId")
            ?.let(::MessageId) ?: if (taskThreadId != null) messageId else null
    return TaskSummary(
        id = TaskId(id),
        title = title,
        status = string("status", "taskStatus")?.toTaskStatus() ?: TaskStatus.Todo,
        taskNumber = taskNumber,
        messageId = messageId,
        parentMessageId = parentMessageId,
        channelId = channelId?.let(::ChannelId),
        threadId = taskThreadId?.let(::ThreadId),
        isThreadTask =
            parentMessageId != null ||
                explicitThreadId != null ||
                (channelType == "thread" && fallbackThreadId != null),
        assignee = assignee,
        channelName =
            string("channelName")
                ?: firstObject("channel", "parentChannel", "conversation")
                    ?.string("name", "channelName", "slug", "channelSlug"),
        updatedAtLabel =
            string(
                "updatedAt",
                "updated_at",
                "latestActivityAt",
                "latest_activity_at",
                "lastActivityAt",
                "last_activity_at",
                "createdAt",
                "created_at",
                "timestamp",
                "time"
            ).orEmpty(),
        canClaim = boolean("canClaim") ?: true
    )
}

private fun JsonObject.taskDisplayTitle(taskNumber: Int?): String? {
    val candidates =
        listOfNotNull(
            string("taskTitle", "task_title", "displayTitle", "display_title"),
            string("title"),
            string("content"),
            string("description"),
            string("body"),
            string("text"),
            string(
                "messageBody",
                "messageContent",
                "messageText",
                "parentMessageBody",
                "parentMessageContent",
                "parentMessagePreview",
                "rootMessagePreview",
                "originalMessagePreview",
                "sourceMessageBody",
                "sourceMessageContent",
                "sourceMessagePreview",
                "messagePreview",
                "lastMessagePreview",
                "latestActivityPreview",
                "preview"
            ),
            firstObject(
                "message",
                "parentMessage",
                "sourceMessage",
                "originMessage",
                "rootMessage",
                "latestActivity",
                "lastMessage"
            )?.taskTextValue()
        )
    return candidates.firstNotNullOfOrNull { candidate ->
        candidate.takeMeaningfulTaskTitle(taskNumber)
    }
}

private fun JsonElement.taskTextValue(): String? =
    when (this) {
        is JsonPrimitive -> contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        is JsonArray -> firstNotNullOfOrNull { it.taskTextValue() }
        is JsonObject ->
            string(
                "body",
                "content",
                "text",
                "plainText",
                "plain_text",
                "markdown",
                "messageBody",
                "messageContent",
                "messageText",
                "preview",
                "messagePreview",
                "parentMessagePreview",
                "rootMessagePreview",
                "originalMessagePreview",
                "sourceMessagePreview",
                "lastMessagePreview",
                "latestActivityPreview",
                "summary",
                "description",
                "detail",
                "details",
                "title"
            )
                ?: listOf(
                    "content",
                    "body",
                    "message",
                    "parentMessage",
                    "sourceMessage",
                    "originMessage",
                    "rootMessage",
                    "latestActivity",
                    "lastMessage",
                    "children",
                    "data",
                    "payload"
                ).firstNotNullOfOrNull { key -> this[key]?.taskTextValue() }
        else -> null
    }

private fun JsonObject.toMemberProfile(): MemberProfile? {
    val id =
        string("id", "memberId", "userId", "agentId", "claimedById", "assigneeId", "assignedToId")
            ?: return null
    val label =
        string("displayName", "name", "username", "handle", "claimedByName", "assigneeName", "assignedToName")
            ?: id
    val kindWire = string("kind", "type", "memberKind", "claimedByType", "assigneeType", "assignedToType")
    return MemberProfile(
        id = MemberId(id),
        displayName = label,
        handle = string("handle", "username", "claimedByHandle", "assigneeHandle", "assignedToHandle") ?: label,
        kind = if (kindWire == "agent") MemberKind.Agent else MemberKind.Human,
        avatarLabel = label.take(1).uppercase(),
        presence = Presence.Offline
    )
}

private fun JsonObject.claimedByFallback(): MemberProfile? {
    val claimedById =
        string("claimedById", "assigneeId", "assignedToId", "taskAssigneeId") ?: return null
    val claimedByType =
        string("claimedByType", "assigneeType", "assignedToType", "taskAssigneeType")
    val label =
        string("claimedByName", "assigneeName", "assignedToName", "claimedByHandle")
            ?: claimedById
    return MemberProfile(
        id = MemberId(claimedById),
        displayName = label,
        handle = string("claimedByHandle", "assigneeHandle", "assignedToHandle") ?: label,
        kind = if (claimedByType == "agent") MemberKind.Agent else MemberKind.Human,
        avatarLabel = label.take(1).uppercase(),
        presence = Presence.Offline
    )
}

private fun String.toTasksJsonObject(): JsonObject? =
    runCatching { tasksJson.parseToJsonElement(this) as? JsonObject }.getOrNull()

private fun JsonObject.items(vararg names: String): List<JsonElement> {
    for (name in names) {
        when (val value = this[name]) {
            is JsonArray -> return value.toList()
            is JsonObject -> value.items(*names).takeIf { it.isNotEmpty() }?.let { return it }
            else -> Unit
        }
    }
    return emptyList()
}

private fun JsonObject.firstObject(vararg names: String): JsonObject? =
    names.firstNotNullOfOrNull { this[it] as? JsonObject }

private fun JsonObject.string(vararg names: String): String? {
    for (name in names) {
        val value = this[name]
        if (value is JsonPrimitive) {
            value.contentOrNull?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        }
    }
    return null
}

private fun JsonObject.int(vararg names: String): Int? {
    for (name in names) {
        val value = this[name]
        if (value is JsonPrimitive) {
            value.intOrNull?.let { return it }
            value.contentOrNull?.trim()?.toIntOrNull()?.let { return it }
        }
    }
    return null
}

private fun JsonObject.boolean(vararg names: String): Boolean? {
    for (name in names) {
        val value = this[name]
        if (value is JsonPrimitive) {
            value.booleanOrNull?.let { return it }
            value.contentOrNull?.lowercase()?.let {
                when (it) {
                    "true", "1", "yes" -> return true
                    "false", "0", "no" -> return false
                }
            }
        }
    }
    return null
}

private fun String.takeMeaningfulTaskTitle(taskNumber: Int?): String? {
    val trimmed = trim()
    if (trimmed.isBlank()) return null
    val number = taskNumber ?: return trimmed
    val withoutNumberPrefix = trimmed.withoutTaskNumberPrefix(number)
    if (withoutNumberPrefix.isNotBlank() && withoutNumberPrefix != trimmed) return withoutNumberPrefix
    val placeholders = setOf("#$number", number.toString(), "task #$number", "task $number")
    return trimmed.takeUnless { it.lowercase() in placeholders }
}

private fun String.withoutTaskNumberPrefix(number: Int): String {
    var index = 0
    index = skipWhitespace(index)
    val lower = lowercase()
    if (lower.startsWith("task", index)) {
        index += "task".length
        index = skipWhitespace(index)
    }
    if (getOrNull(index) == '#') index += 1
    val numberStart = index
    while (index < length && this[index].isDigit()) index += 1
    if (numberStart == index) return trim()
    if (substring(numberStart, index).toIntOrNull() != number) return trim()
    index = skipWhitespace(index)
    if (getOrNull(index) in setOf('-', ':', '：', ',', '，')) index += 1
    index = skipWhitespace(index)
    return substring(index).trim()
}

private fun String.skipWhitespace(start: Int): Int {
    var index = start
    while (index < length && this[index].isWhitespace()) index += 1
    return index
}

private fun String.toTaskStatus(): TaskStatus? =
    when (lowercase()) {
        "todo", "to_do", "open" -> TaskStatus.Todo
        "in_progress", "inprogress", "progress" -> TaskStatus.InProgress
        "in_review", "inreview", "review" -> TaskStatus.InReview
        "done", "complete", "completed" -> TaskStatus.Done
        "closed" -> TaskStatus.Closed
        else -> null
    }

private fun TaskStatus.toApiValue(): String =
    when (this) {
        TaskStatus.Todo -> "todo"
        TaskStatus.InProgress -> "in_progress"
        TaskStatus.InReview -> "in_review"
        TaskStatus.Done -> "done"
        TaskStatus.Closed -> "closed"
    }
