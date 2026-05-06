package ai.slock.android.core.data.realtime

import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.Presence
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.model.ThreadId
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

class RealtimeEventMapper(
    private val gson: Gson = Gson()
) {
    fun map(eventName: String, payload: Any?): RealtimeEvent? {
        val root = payload.toJsonObject()
        return when (eventName) {
            RealtimeSocketEvents.MessageNew -> root.unwrap("message").toMessage()?.let(RealtimeEvent::MessageNew)
            RealtimeSocketEvents.MessageUpdated -> root.unwrap("message").toMessage()?.let(RealtimeEvent::MessageUpdated)
            RealtimeSocketEvents.ThreadUpdated -> root.toThreadUpdated()
            RealtimeSocketEvents.TaskCreated -> root.toTasksCreated()
            RealtimeSocketEvents.TaskUpdated -> root.unwrap("task").toTaskSummary()?.let(RealtimeEvent::TaskUpdated)
            RealtimeSocketEvents.TaskDeleted -> root.string("taskId", "id")?.let { RealtimeEvent.TaskDeleted(TaskId(it)) }
            RealtimeSocketEvents.SyncResumeResponse -> root.toSyncResumeResponse()
            RealtimeSocketEvents.Heartbeat -> root.long("seq")?.let(RealtimeEvent::Heartbeat)
            else -> null
        }
    }

    private fun Any?.toJsonObject(): JsonObject =
        when (this) {
            null -> JsonObject()
            is JsonObject -> this
            is JsonElement -> if (isJsonObject) asJsonObject else JsonObject()
            is Map<*, *> -> gson.toJsonTree(this).asJsonObject
            is String -> runCatching { gson.fromJson(this, JsonElement::class.java) }
                .getOrNull()
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?: JsonObject()
            else -> runCatching { gson.toJsonTree(this).asJsonObject }.getOrDefault(JsonObject())
        }
}

private fun JsonObject.toThreadUpdated(): RealtimeEvent.ThreadUpdated? {
    val parentMessageId = string("parentMessageId", "messageId") ?: return null
    val threadChannelId = string("threadChannelId", "channelId") ?: return null
    return RealtimeEvent.ThreadUpdated(
        parentMessageId = MessageId(parentMessageId),
        threadChannelId = ChannelId(threadChannelId),
        replyCount = int("replyCount", "replies") ?: 0,
        lastReplyAt = string("lastReplyAt", "updatedAt", "latestActivityAt"),
        participantIds = stringList("participantIds", "participants").map(::MemberId)
    )
}

private fun JsonObject.toTasksCreated(): RealtimeEvent.TaskCreated? {
    val tasks = when {
        get("tasks")?.isJsonArray == true -> getAsJsonArray("tasks").mapNotNull { it.asObject().toTaskSummary() }
        get("task")?.isJsonObject == true -> listOfNotNull(getAsJsonObject("task").toTaskSummary())
        else -> listOfNotNull(toTaskSummary())
    }
    return RealtimeEvent.TaskCreated(tasks).takeIf { tasks.isNotEmpty() }
}

private fun JsonObject.toSyncResumeResponse(): RealtimeEvent.SyncResumeResponse {
    val messages = items("messages", "items").mapNotNull { it.asObject().toMessage() }
    return RealtimeEvent.SyncResumeResponse(
        messages = messages,
        currentSeq = long("currentSeq", "seq", "lastSeq") ?: messages.maxOfOrNull { it.seq ?: 0L } ?: 0L,
        hasMore = boolean("hasMore") ?: false
    )
}

private fun JsonObject.toMessage(): Message? {
    val id = string("id", "messageId") ?: return null
    val threadId = string("threadId", "threadChannelId", "channelId") ?: return null
    val author = unwrap("author", "sender", "user").toMemberProfile()
        ?: MemberProfile(
            id = MemberId(string("authorId", "senderId", "userId") ?: "unknown-author"),
            displayName = string("authorName", "senderName", "userName") ?: "Unknown",
            handle = string("authorHandle", "senderHandle", "email") ?: "unknown",
            kind = MemberKind.Human,
            presence = Presence.Offline
        )
    return Message(
        id = MessageId(id),
        threadId = ThreadId(threadId),
        channelId = string("channelId")?.let(::ChannelId),
        seq = long("seq"),
        author = author,
        timestampLabel = string("timestampLabel", "createdAt", "updatedAt") ?: "",
        body = string("body", "content", "text") ?: "",
        replyCount = int("replyCount", "replies") ?: 0,
        parentMessageId = string("parentMessageId")?.let(::MessageId),
        updatedAtLabel = string("updatedAt")
    )
}

private fun JsonObject.toTaskSummary(): TaskSummary? {
    val id = string("id", "taskId") ?: return null
    val title = string("title", "body", "content") ?: return null
    return TaskSummary(
        id = TaskId(id),
        title = title,
        status = string("status", "taskStatus")?.toTaskStatus() ?: TaskStatus.Todo,
        taskNumber = int("taskNumber", "number"),
        messageId = string("messageId")?.let(::MessageId),
        assignee = unwrap("assignee", "claimedBy").toMemberProfile(),
        channelName = string("channelName"),
        updatedAtLabel = string("updatedAt", "createdAt") ?: "",
        canClaim = boolean("canClaim") ?: true
    )
}

private fun JsonObject.toMemberProfile(): MemberProfile? {
    val id = string("id", "userId", "memberId") ?: return null
    val displayName = string("displayName", "name", "email") ?: "Unknown"
    return MemberProfile(
        id = MemberId(id),
        displayName = displayName,
        handle = string("handle", "email") ?: displayName,
        kind = if (boolean("isAgent") == true || string("kind", "type") == "agent") MemberKind.Agent else MemberKind.Human,
        roleDescription = string("description", "roleDescription"),
        avatarLabel = displayName.take(1).uppercase(),
        presence = Presence.Offline
    )
}

private fun JsonObject.unwrap(vararg names: String): JsonObject {
    for (name in names) {
        val value = get(name)
        if (value != null && value.isJsonObject) return value.asJsonObject
    }
    return this
}

private fun JsonObject.items(vararg names: String): List<JsonElement> {
    for (name in names) {
        val value = get(name)
        if (value != null && value.isJsonArray) return value.asJsonArray.toList()
    }
    return emptyList()
}

private fun JsonArray.toList(): List<JsonElement> =
    (0 until size()).map { get(it) }

private fun JsonElement.asObject(): JsonObject =
    if (isJsonObject) asJsonObject else JsonObject()

private fun JsonObject.string(vararg names: String): String? {
    for (name in names) {
        val value = get(name)
        if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            return value.asString.takeIf { it.isNotBlank() }
        }
    }
    return null
}

private fun JsonObject.int(vararg names: String): Int? {
    for (name in names) {
        val value = get(name)
        if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) return value.asInt
    }
    return null
}

private fun JsonObject.long(vararg names: String): Long? {
    for (name in names) {
        val value = get(name)
        if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) return value.asLong
    }
    return null
}

private fun JsonObject.boolean(vararg names: String): Boolean? {
    for (name in names) {
        val value = get(name)
        if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) return value.asBoolean
    }
    return null
}

private fun JsonObject.stringList(vararg names: String): List<String> {
    for (name in names) {
        val value = get(name)
        if (value != null && value.isJsonArray) {
            return value.asJsonArray.mapNotNull { element ->
                when {
                    element.isJsonPrimitive && element.asJsonPrimitive.isString -> element.asString
                    element.isJsonObject -> element.asJsonObject.string("id", "userId", "memberId")
                    else -> null
                }
            }
        }
    }
    return emptyList()
}

private fun String.toTaskStatus(): TaskStatus? =
    when (lowercase()) {
        "todo", "to_do", "open" -> TaskStatus.Todo
        "in_progress", "inprogress", "progress" -> TaskStatus.InProgress
        "in_review", "inreview", "review" -> TaskStatus.InReview
        "done", "closed", "complete", "completed" -> TaskStatus.Done
        else -> null
    }
