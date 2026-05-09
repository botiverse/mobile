package ai.slock.android.core.data.realtime

import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.Attachment
import ai.slock.android.core.model.AttachmentId
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.MessageType
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
import org.json.JSONArray
import org.json.JSONObject

class RealtimeEventMapper(
    private val gson: Gson = Gson()
) {
    fun map(eventName: String, payload: Any?): RealtimeEvent? {
        val root = payload.toJsonObject()
        return when (eventName) {
            RealtimeSocketEvents.MessageNew,
            RealtimeSocketEvents.DirectMessageNew -> root.unwrap("message").toMessage()?.let(RealtimeEvent::MessageNew)
            RealtimeSocketEvents.MessageUpdated -> root.unwrap("message").toMessage()?.let(RealtimeEvent::MessageUpdated)
            RealtimeSocketEvents.ThreadUpdated -> root.toThreadUpdated()
            RealtimeSocketEvents.TaskCreated -> root.toTasksCreated()
            RealtimeSocketEvents.TaskUpdated -> root.unwrap("task").toTaskSummary()?.let(RealtimeEvent::TaskUpdated)
            RealtimeSocketEvents.TaskDeleted -> root.string("taskId", "id")?.let { RealtimeEvent.TaskDeleted(TaskId(it)) }
            RealtimeSocketEvents.AgentActivity -> root.toMemberPresenceUpdated(defaultKind = MemberKind.Agent)
            RealtimeSocketEvents.ServerMemberAdded,
            RealtimeSocketEvents.ServerMemberUpdated,
            RealtimeSocketEvents.ServerMemberLeft,
            RealtimeSocketEvents.ServerMemberRemoved -> root.toMemberPresenceUpdated()
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
            is JSONObject -> parseObject(toString())
            is JSONArray -> JsonObject()
            is Map<*, *> -> gson.toJsonTree(this).asJsonObject
            is String -> parseObject(this)
            else -> runCatching { gson.toJsonTree(this).asJsonObject }.getOrDefault(JsonObject())
        }

    private fun parseObject(value: String): JsonObject =
        runCatching { gson.fromJson(value, JsonElement::class.java) }
            .getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: JsonObject()
}

private fun JsonObject.toThreadUpdated(): RealtimeEvent.ThreadUpdated? {
    val parentMessageId = string(
        "parentMessageId",
        "parent_message_id",
        "parentId",
        "parent_id",
        "rootMessageId",
        "root_message_id",
        "messageId",
        "message_id"
    ) ?: return null
    val threadChannelId = string(
        "threadChannelId",
        "thread_channel_id",
        "replyThreadChannelId",
        "reply_thread_channel_id",
        "replyChannelId",
        "reply_channel_id",
        "replyThreadId",
        "reply_thread_id",
        "threadId",
        "thread_id",
        "channelId",
        "channel_id"
    )
        ?: return null
    return RealtimeEvent.ThreadUpdated(
        parentMessageId = MessageId(parentMessageId),
        threadChannelId = ChannelId(threadChannelId),
        replyCount = int(
            "replyCount",
            "reply_count",
            "replies",
            "threadReplyCount",
            "thread_reply_count",
            "repliesCount",
            "replies_count",
            "threadRepliesCount",
            "thread_replies_count",
            "commentsCount",
            "comments_count",
            "childCount",
            "child_count"
        ) ?: 0,
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

private fun JsonObject.toMemberPresenceUpdated(
    defaultKind: MemberKind? = null
): RealtimeEvent.MemberPresenceUpdated? {
    val profileObject = firstObject("member", "user", "agent", "profile") ?: this
    val memberId = string("memberId", "userId", "agentId", "id")
        ?: profileObject.string("memberId", "userId", "agentId", "id", "_id")
        ?: return null
    val presence = string("presence", "status", "activity", "availability")
        .toPresenceOrNull()
        ?: profileObject.string("presence", "status", "activity", "availability").toPresenceOrNull()
        ?: return null
    val kind = defaultKind ?: when {
        firstObject("agent") != null ||
            boolean("isAgent") == true ||
            profileObject.boolean("isAgent") == true ||
            string("kind", "type", "memberType") == "agent" ||
            profileObject.string("kind", "type", "memberType") == "agent" -> MemberKind.Agent
        firstObject("user", "member") != null -> MemberKind.Human
        else -> null
    }
    return RealtimeEvent.MemberPresenceUpdated(
        memberId = MemberId(memberId),
        presence = presence,
        kind = kind,
        statusLabel = string("detail", "statusLabel", "message")
            ?: profileObject.string("detail", "statusLabel", "message")
    )
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
    val rawThreadId = string("threadId", "thread_id")
        ?: firstObject("thread", "replyThread", "messageThread", "repliesThread")
            ?.string("id", "threadId", "thread_id", "channelId", "channel_id")
    val rawChannelId = string("channelId", "channel_id")
        ?: firstObject("channel", "parentChannel")
            ?.string("id", "channelId", "channel_id")
    val explicitThreadChannelId = string(
        "threadChannelId",
        "thread_channel_id",
        "replyThreadChannelId",
        "reply_thread_channel_id",
        "repliesThreadChannelId",
        "replies_thread_channel_id",
        "childThreadChannelId",
        "child_thread_channel_id",
        "threadChannel",
        "thread_channel",
        "replyChannelId",
        "reply_channel_id",
        "replyThreadId",
        "reply_thread_id",
        "commentsThreadChannelId",
        "comments_thread_channel_id"
    )
    val parentMessageId = string(
        "parentMessageId",
        "parent_message_id",
        "parentId",
        "parent_id",
        "rootMessageId",
        "root_message_id"
    )?.let(::MessageId)
    val replyCount = int(
        "replyCount",
        "reply_count",
        "replies",
        "threadReplyCount",
        "thread_reply_count",
        "repliesCount",
        "replies_count",
        "threadRepliesCount",
        "thread_replies_count",
        "commentsCount",
        "comments_count",
        "childCount",
        "child_count"
    )
        ?: firstObject("thread", "replyThread", "messageThread", "repliesThread")?.int(
            "replyCount",
            "reply_count",
            "replies",
            "repliesCount",
            "replies_count",
            "childCount",
            "child_count"
        )
        ?: 0
    val topLevelReplyThreadId = rawThreadId
        ?.takeIf { parentMessageId == null && explicitThreadChannelId == null }
        ?.takeIf { replyCount > 0 }
        ?.takeIf { it != rawChannelId }
    val threadId = when {
        topLevelReplyThreadId != null -> rawChannelId
        else -> rawThreadId ?: rawChannelId ?: explicitThreadChannelId
    } ?: return null
    val author = firstObject("author", "sender", "user", "createdBy", "createdByUser", "senderUser", "agent", "authorAgent")
        ?.toMemberProfile()
        ?: toMessageAuthorProfile()
    return Message(
        id = MessageId(id),
        threadId = ThreadId(threadId),
        channelId = string("channelId", "channel_id")?.let(::ChannelId),
        threadChannelId = (explicitThreadChannelId ?: topLevelReplyThreadId)?.let(::ChannelId),
        seq = long("seq"),
        author = author,
        timestampLabel = string("timestampLabel", "createdAt", "updatedAt") ?: "",
        body = string("body", "content", "text") ?: "",
        messageType = toMessageType(),
        systemEventType = systemEventType(),
        metadata = systemMetadata(),
        attachments = attachmentItems().mapNotNull { it.asObject().toAttachment() },
        replyCount = replyCount,
        parentMessageId = parentMessageId,
        updatedAtLabel = string("updatedAt")
    )
}

private fun JsonObject.attachmentItems(): List<JsonElement> =
    items("attachments", "files", "images", "media")

private fun JsonObject.toAttachment(): Attachment? {
    val id = string("id", "attachmentId", "attachment_id", "fileId", "file_id", "_id")
        ?: firstObject("attachment", "file", "image", "media")
            ?.string("id", "attachmentId", "attachment_id", "fileId", "file_id", "_id")
        ?: return null
    val nested = firstObject("attachment", "file", "image", "media")
    return Attachment(
        id = AttachmentId(id),
        name = string(
            "name",
            "fileName",
            "filename",
            "originalName",
            "original_name",
            "title",
            "label"
        ) ?: nested?.string(
            "name",
            "fileName",
            "filename",
            "originalName",
            "original_name",
            "title",
            "label"
        ) ?: "Attachment",
        mimeType = string(
            "mimeType",
            "mime_type",
            "contentType",
            "content_type",
            "type"
        ) ?: nested?.string(
            "mimeType",
            "mime_type",
            "contentType",
            "content_type",
            "type"
        ) ?: "application/octet-stream",
        sizeLabel = string("sizeLabel", "size_label", "size", "fileSize", "file_size")
            ?: nested?.string("sizeLabel", "size_label", "size", "fileSize", "file_size")
            ?: sizeNumberLabel()
            ?: nested?.sizeNumberLabel()
            ?: "",
        thumbnailUrl = string(
            "thumbnailUrl",
            "thumbnail_url",
            "thumbUrl",
            "thumb_url",
            "previewUrl",
            "preview_url",
            "imageUrl",
            "image_url"
        ) ?: nested?.string(
            "thumbnailUrl",
            "thumbnail_url",
            "thumbUrl",
            "thumb_url",
            "previewUrl",
            "preview_url",
            "imageUrl",
            "image_url"
        )
    )
}

private fun JsonObject.sizeNumberLabel(): String? =
    long("sizeBytes", "size_bytes", "bytes", "byteSize", "byte_size")
        ?.let { bytes -> "$bytes B" }

private fun JsonObject.toTaskSummary(): TaskSummary? {
    val id = string("id", "taskId") ?: return null
    val title = string("title", "body", "content") ?: return null
    val messageId = string("messageId")?.let(::MessageId)
    val explicitThreadId = string(
        "threadChannelId",
        "replyThreadChannelId",
        "replyThreadId",
        "repliesThreadChannelId",
        "childThreadChannelId",
        "commentsThreadChannelId"
    )
    val nestedThreadId = firstObject("thread", "replyThread", "messageThread", "repliesThread")
        ?.string(
            "threadChannelId",
            "replyThreadChannelId",
            "replyThreadId",
            "id",
            "channelId"
        )
    val fallbackThreadId = string("threadId") ?: nestedThreadId
    val channelType = string("channelType", "type", "kind")?.lowercase()
    val channelId = string("channelId", "parentChannelId")
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
        taskNumber = int("taskNumber", "number"),
        messageId = messageId,
        parentMessageId = parentMessageId,
        channelId = channelId?.let(::ChannelId),
        threadId = taskThreadId?.let(::ThreadId),
        isThreadTask =
            parentMessageId != null ||
                explicitThreadId != null ||
                (channelType == "thread" && fallbackThreadId != null),
        assignee = firstObject("assignee", "claimedBy", "assignedTo")?.toMemberProfile()
            ?: claimedByFallback(),
        channelName = string("channelName"),
        updatedAtLabel = string("updatedAt", "createdAt") ?: "",
        canClaim = boolean("canClaim") ?: true
    )
}

private fun JsonObject.claimedByFallback(): MemberProfile? {
    val claimedById = string("claimedById", "assigneeId", "assignedToId", "taskAssigneeId")
        ?: return null
    val claimedByType =
        string("claimedByType", "assigneeType", "assignedToType", "taskAssigneeType")
    val label = string("claimedByName", "assigneeName", "assignedToName", "claimedByHandle")
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

private fun JsonObject.toMemberProfile(): MemberProfile? {
    val id = memberId() ?: return null
    val displayName = memberDisplayName() ?: id
    return MemberProfile(
        id = MemberId(id),
        displayName = displayName,
        handle = memberHandle(displayName),
        kind = memberKind(),
        roleDescription = memberRoleDescription(),
        avatarUrl = memberAvatarUrl(),
        gravatarHash = memberGravatarHash(),
        avatarLabel = displayName.take(1).uppercase(),
        presence = memberPresence()
    )
}

private fun JsonObject.memberId(): String? =
    string("id", "_id", "userId", "memberId", "agentId")
        ?: nestedMemberObject()?.memberId()

private fun JsonObject.memberDisplayName(): String? =
    string(
        "displayName",
        "display_name",
        "name",
        "username",
        "title",
        "label",
        "handle",
        "email"
    )
        ?: nestedMemberObject()?.memberDisplayName()
        ?: firstObject("metadata", "meta")?.string(
            "displayName",
            "display_name",
            "name",
            "username",
            "title",
            "label"
        )

private fun JsonObject.memberHandle(displayName: String): String =
    string("handle", "username", "email")
        ?: nestedMemberObject()?.string("handle", "username", "email")
        ?: displayName

private fun JsonObject.memberRoleDescription(): String? =
    string("description", "roleDescription", "role")
        ?: nestedMemberObject()?.string("description", "roleDescription", "role")

private fun JsonObject.memberAvatarUrl(): String? =
    avatarWireValue("avatarUrl", "avatar", "avatar_url", "imageUrl", "profileImageUrl", "profileAvatar")
        ?: nestedMemberObject()?.memberAvatarUrl()

private fun JsonObject.memberGravatarHash(): String? =
    string("gravatarHash", "gravatar_hash", "gravatar", "gravatarMd5", "gravatar_md5")
        ?: nestedMemberObject()?.memberGravatarHash()

private fun JsonObject.memberPresence(): Presence =
    string("presence", "status", "activity", "availability").toPresenceOrNull()
        ?: nestedMemberObject()?.memberPresence()
        ?: Presence.Offline

private fun JsonObject.memberKind(): MemberKind {
    val kindValue = string("kind", "type", "memberType")?.lowercase()
        ?: nestedMemberObject()?.string("kind", "type", "memberType")?.lowercase()
    val hasAgentId = string("agentId") != null || nestedMemberObject()?.string("agentId") != null
    return if (boolean("isAgent") == true || kindValue == "agent" || hasAgentId || firstObject("agent") != null) {
        MemberKind.Agent
    } else {
        MemberKind.Human
    }
}

private fun JsonObject.nestedMemberObject(): JsonObject? =
    firstObject("profile", "user", "account", "member", "agent")

private fun JsonObject.toMessageAuthorProfile(): MemberProfile {
    val nestedAuthor = firstObject("author", "sender", "user", "createdBy", "createdByUser", "senderUser", "agent", "authorAgent")
        ?.toMemberProfile()
    val authorId = string(
        "authorId",
        "senderId",
        "userId",
        "createdById",
        "createdByUserId",
        "senderUserId",
        "agentId",
        "authorAgentId",
        "agentUserId"
    ) ?: nestedAuthor?.id?.value ?: "unknown-author"
    val displayName = string(
        "authorDisplayName",
        "authorName",
        "senderDisplayName",
        "senderName",
        "userDisplayName",
        "userName",
        "createdByName",
        "createdByDisplayName",
        "createdByUsername",
        "createdByUserDisplayName",
        "agentName",
        "agentDisplayName",
        "agentUsername",
        "agentTitle",
        "authorAgentName",
        "authorAgentDisplayName",
        "senderAgentName",
        "senderAgentDisplayName",
        "profileDisplayName",
        "profileName",
        "authorHandle",
        "senderHandle",
        "email"
    ) ?: nestedAuthor?.displayName ?: authorId
    val handle = string(
        "authorHandle",
        "senderHandle",
        "userHandle",
        "agentHandle",
        "agentUsername",
        "handle",
        "email"
    ) ?: nestedAuthor?.handle ?: displayName
    val isAgent = boolean("authorIsAgent", "senderIsAgent", "isAgent") == true ||
        string("authorKind", "senderKind", "kind", "type") == "agent" ||
        string("agentId", "authorAgentId", "agentUserId") != null ||
        nestedAuthor?.kind == MemberKind.Agent
    return MemberProfile(
        id = MemberId(authorId),
        displayName = displayName,
        handle = handle,
        kind = if (isAgent) MemberKind.Agent else MemberKind.Human,
        roleDescription = string("authorDescription", "senderDescription", "agentDescription", "roleDescription")
            ?: nestedAuthor?.roleDescription,
        avatarUrl = avatarWireValue(
            "authorAvatarUrl",
            "authorAvatar",
            "senderAvatarUrl",
            "senderAvatar",
            "agentAvatarUrl",
            "agentAvatar",
            "authorAgentAvatarUrl",
            "authorAgentAvatar",
            "senderAgentAvatarUrl",
            "senderAgentAvatar",
            "profileAvatar",
            "avatarUrl",
            "avatar"
        )
            ?: nestedAuthor?.avatarUrl,
        gravatarHash = string("authorGravatarHash", "senderGravatarHash", "gravatarHash", "gravatar_hash")
            ?: nestedAuthor?.gravatarHash,
        avatarLabel = displayName.take(1).uppercase(),
        presence = string("authorPresence", "senderPresence", "agentPresence", "presence", "status", "activity").toPresence(
            nestedAuthor?.presence ?: Presence.Offline
        )
    )
}

private fun String?.toPresence(fallback: Presence = Presence.Offline): Presence =
    toPresenceOrNull() ?: fallback

private fun String?.toPresenceOrNull(): Presence? =
    when (this?.lowercase()) {
        "active", "online", "working", "available" -> Presence.Active
        "away", "idle", "busy" -> Presence.Away
        "offline", "inactive" -> Presence.Offline
        else -> null
    }

private fun JsonObject.unwrap(vararg names: String): JsonObject {
    for (name in names) {
        val value = get(name)
        if (value != null && value.isJsonObject) return value.asJsonObject
    }
    return this
}

private fun JsonObject.firstObject(vararg names: String): JsonObject? {
    for (name in names) {
        val value = get(name)
        if (value != null && value.isJsonObject) return value.asJsonObject
    }
    return null
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

private fun JsonObject.avatarWireValue(vararg names: String): String? {
    for (name in names) {
        val value = get(name)?.toAvatarWireValue()
        if (!value.isNullOrBlank()) return value
    }
    return null
}

private fun JsonElement.toAvatarWireValue(): String? {
    if (isJsonPrimitive && asJsonPrimitive.isString) {
        return asString.takeIf { it.isNotBlank() }
    }
    if (!isJsonObject) return null
    val avatarObject = asJsonObject
    avatarObject.string(
        "wireValue",
        "value",
        "url",
        "href",
        "src",
        "avatarUrl",
        "avatar_url",
        "imageUrl",
        "profileImageUrl"
    )?.let { return it }
    avatarObject.avatarWireValue("data", "profile")?.let { return it }
    avatarObject.string("pixel", "pixelKey", "key", "icon")?.let { key ->
        return "pixel:${key.removePrefix("pixel:")}"
    }
    avatarObject.string("random", "randomSeed", "seed")?.let { seed ->
        return "random:${seed.removePrefix("random:")}"
    }
    val type = avatarObject.string("type", "kind", "mode")?.lowercase()
    val typedValue = avatarObject.string("id", "name", "slug")
    return when (type) {
        "pixel" -> "pixel:${typedValue?.removePrefix("pixel:") ?: "robot"}"
        "random" -> "random:${typedValue?.removePrefix("random:") ?: "default"}"
        "url", "image", "photo" -> typedValue
        else -> null
    }
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

private fun JsonObject.toMessageType(): MessageType {
    val type = string("type", "messageType", "kind", "messageKind")?.lowercase()
    return if (type == "system" || type == "event" || systemEventType() != null) {
        MessageType.System
    } else {
        MessageType.Chat
    }
}

private fun JsonObject.systemEventType(): String? =
    string(
        "systemEventType",
        "system_event_type",
        "eventType",
        "event_type",
        "systemType",
        "subtype",
        "action"
    ) ?: firstObject("systemEvent", "event")?.string("type", "eventType", "name", "action")

private fun JsonObject.systemMetadata(): Map<String, String> {
    val metadataObject = firstObject("metadata", "meta", "data", "payload") ?: return emptyMap()
    return metadataObject.entrySet().mapNotNull { (key, value) ->
        val stringValue = when {
            value.isJsonPrimitive -> value.asJsonPrimitive.asString
            value.isJsonObject -> value.asJsonObject.string("id", "name", "label", "value")
            else -> null
        }?.takeIf { it.isNotBlank() }
        stringValue?.let { key to it }
    }.toMap()
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
