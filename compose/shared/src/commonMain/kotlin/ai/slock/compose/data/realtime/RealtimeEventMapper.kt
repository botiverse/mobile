package ai.slock.compose.data.realtime

import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.Attachment
import ai.slock.compose.model.AttachmentId
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.DirectMessageCreated
import ai.slock.compose.model.Heartbeat
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.MessageType
import ai.slock.compose.model.MessageUpdated
import ai.slock.compose.model.Presence
import ai.slock.compose.model.RealtimeEvent
import ai.slock.compose.model.SyncResumeResponse
import ai.slock.compose.model.TaskCreated
import ai.slock.compose.model.TaskDeleted
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import ai.slock.compose.model.TaskUpdated
import ai.slock.compose.model.ThreadId
import ai.slock.compose.model.ThreadUpdated
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Decodes raw Socket.IO payloads into typed [RealtimeEvent]s.
 *
 * Field names mirror the canonical server emit shapes documented in
 *  - `packages/server/src/services/messageService.ts` — enriched message rows
 *  - `packages/server/src/services/taskService.ts` — `TaskInfo`
 *  - `packages/server/src/services/channelService.ts::getThreadInfo`
 *  - `packages/server/src/services/taskRealtimeEvents.ts`
 *  - `packages/server/src/socket/index.ts` — `sync:resume:response` / `heartbeat`
 *
 * Do NOT add speculative snake_case / alias fallbacks here. If the server
 * renames a field, change it in the DTO with a doc comment pointing at the
 * server commit.
 *
 * Input is a JSON string. Platform glue (Android Socket.IO `JSONObject`,
 * iOS dictionaries, etc.) must stringify the payload before calling [map].
 */
class RealtimeEventMapper {

    /**
     * Single entry point for all platforms.
     *
     * Accepts whatever the Socket.IO / bridge layer happens to hand us:
     *  - `null` → `null`
     *  - `String` (raw JSON, e.g. `"{...}"`)
     *  - `JsonElement` (already parsed)
     *  - `Map` / `List` / primitives (test fixtures, Kuikly bridge dictionaries)
     *  - Any other type whose `toString()` is valid JSON (e.g. Android
     *    `org.json.JSONObject` / `JSONArray`, iOS dictionaries via
     *    `NSJSONSerialization`). We never reach into platform classes from
     *    commonMain — we just rely on their `toString()` contract.
     *
     * Returns `null` for anything we can't decode into a known event.
     */
    fun map(eventName: String, payload: Any?): RealtimeEvent? {
        val element = payload.toJsonObjectOrNull() ?: return null
        return mapElement(eventName, element)
    }

    private fun Any?.toJsonObjectOrNull(): JsonObject? {
        val element = when (this) {
            null -> return null
            is JsonObject -> this
            is JsonElement -> this
            is String -> {
                if (isBlank()) return null
                runCatching { json.parseToJsonElement(this) }.getOrNull() ?: return null
            }
            is Map<*, *>, is List<*>, is Number, is Boolean ->
                this.toJsonElement() ?: return null
            else -> runCatching { json.parseToJsonElement(toString()) }.getOrNull() ?: return null
        }
        return element as? JsonObject
    }

    private fun mapElement(eventName: String, element: JsonObject): RealtimeEvent? = try {
        when (eventName) {
            RealtimeSocketEvents.MessageNew ->
                decodeOrNull(MessageEnvelopeDto.serializer(), element)?.message?.toDomain()?.let(::MessageNew)
            RealtimeSocketEvents.MessageUpdated ->
                decodeOrNull(MessageEnvelopeDto.serializer(), element)?.message?.toDomain()?.let(::MessageUpdated)
            RealtimeSocketEvents.DirectMessageNew -> decodeDirectMessageNew(element)
            RealtimeSocketEvents.ThreadUpdated ->
                decodeOrNull(ThreadUpdatedDto.serializer(), element)?.toDomain()
            RealtimeSocketEvents.TaskCreated ->
                decodeOrNull(TaskCreatedDto.serializer(), element)?.toDomain()
            RealtimeSocketEvents.TaskUpdated ->
                decodeOrNull(TaskUpdatedDto.serializer(), element)?.task?.toDomain()?.let(::TaskUpdated)
            RealtimeSocketEvents.TaskDeleted ->
                decodeOrNull(TaskDeletedDto.serializer(), element)?.let { TaskDeleted(TaskId(it.taskId)) }
            RealtimeSocketEvents.AgentActivity ->
                decodeOrNull(AgentActivityDto.serializer(), element)?.toDomain()
            RealtimeSocketEvents.ServerMemberAdded,
            RealtimeSocketEvents.ServerMemberUpdated,
            RealtimeSocketEvents.ServerMemberLeft,
            RealtimeSocketEvents.ServerMemberRemoved ->
                // Server emits only `{ serverId, userId }`. Membership-change
                // events do not carry presence/profile data; consumers refetch
                // /members. Return null so we don't fabricate presence state.
                null
            RealtimeSocketEvents.SyncResumeResponse ->
                decodeOrNull(SyncResumeResponseDto.serializer(), element)?.toDomain()
            RealtimeSocketEvents.Heartbeat ->
                decodeOrNull(HeartbeatDto.serializer(), element)?.let { Heartbeat(it.seq) }
            else -> null
        }
    } catch (_: SerializationException) {
        null
    }

    private fun decodeDirectMessageNew(element: JsonObject): RealtimeEvent? {
        decodeOrNull(MessageEnvelopeDto.serializer(), element)?.message?.toDomain()
            ?.let { return MessageNew(it) }
        val channelId = (element["channelId"] as? JsonPrimitive)?.contentOrNull
            ?: (element["id"] as? JsonPrimitive)?.contentOrNull
            ?: return null
        return DirectMessageCreated(ChannelId(channelId))
    }

    private fun <T> decodeOrNull(
        serializer: kotlinx.serialization.KSerializer<T>,
        element: JsonObject,
    ): T? = try {
        json.decodeFromJsonElement(serializer, element)
    } catch (_: SerializationException) {
        null
    }

    internal companion object {
        internal val json: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}

private fun Any?.toJsonElement(): JsonElement? = when (this) {
    null -> null
    is JsonElement -> this
    is Map<*, *> -> buildJsonObject {
        for ((key, value) in this@toJsonElement) {
            val name = key?.toString() ?: continue
            put(name, value.toJsonElement() ?: JsonPrimitive(null as String?))
        }
    }
    is List<*> -> buildJsonArray {
        for (v in this@toJsonElement) {
            add(v.toJsonElement() ?: JsonPrimitive(null as String?))
        }
    }
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}

// ── Wire DTOs ────────────────────────────────────────────────────────────────
// Field names match the canonical server emit shapes. See class-level docstring.

@Serializable
private data class MessageEnvelopeDto(
    val message: MessageWireDto,
)

@Serializable
private data class MessageWireDto(
    val id: String,
    val seq: Long? = null,
    val channelId: String? = null,
    val senderType: String? = null,
    val senderId: String? = null,
    val messageType: String? = null,
    val content: String = "",
    val threadId: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val senderName: String? = null,
    val senderDescription: String? = null,
    val roleDescription: String? = null,
    val attachments: List<AttachmentWireDto> = emptyList(),
)

@Serializable
private data class AttachmentWireDto(
    val id: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long? = null,
    val thumbnailUrl: String? = null,
)

@Serializable
private data class ThreadUpdatedDto(
    val parentMessageId: String,
    val threadChannelId: String,
    val replyCount: Int = 0,
    val lastReplyAt: String? = null,
    val participantIds: List<String> = emptyList(),
)

@Serializable
private data class TaskInfoWireDto(
    val id: String,
    val messageId: String? = null,
    val channelId: String? = null,
    val channelName: String? = null,
    val channelType: String? = null,
    val taskNumber: Int? = null,
    val title: String,
    val status: String? = null,
    val createdByType: String? = null,
    val createdById: String? = null,
    val createdByName: String? = null,
    val claimedByType: String? = null,
    val claimedById: String? = null,
    val claimedByName: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
private data class TaskCreatedDto(
    val channelId: String? = null,
    val tasks: List<TaskInfoWireDto> = emptyList(),
)

@Serializable
private data class TaskUpdatedDto(
    val channelId: String? = null,
    val task: TaskInfoWireDto,
)

@Serializable
private data class TaskDeletedDto(
    val channelId: String? = null,
    val taskId: String,
)

@Serializable
private data class AgentActivityDto(
    val agentId: String,
    val activity: String? = null,
    val detail: String? = null,
    val timestamp: String? = null,
    val serverSeq: Long? = null,
)

@Serializable
private data class SyncResumeResponseDto(
    val messages: List<MessageWireDto> = emptyList(),
    val currentSeq: Long = 0L,
    val hasMore: Boolean = false,
)

@Serializable
private data class HeartbeatDto(
    val seq: Long,
    val ts: Long? = null,
)

// ── DTO → domain mappers ─────────────────────────────────────────────────────

private fun MessageWireDto.toDomain(): Message {
    val authorId = senderId ?: "unknown"
    val author = MemberProfile(
        id = MemberId(authorId),
        displayName = senderName ?: authorId,
        handle = senderName ?: authorId,
        kind = if (senderType == "agent") MemberKind.Agent else MemberKind.Human,
        description = senderDescription,
        roleDescription = roleDescription,
        avatarLabel = (senderName ?: authorId).take(1).uppercase(),
        presence = Presence.Offline,
    )
    val channelIdValue = channelId
    // The message wire row only carries the channel it lives in. Thread linkage
    // (`threadChannelId`, `parentMessageId`, `replyCount`) is delivered via
    // `thread:updated` events and REST responses, not via `message:*` events.
    return Message(
        id = MessageId(id),
        threadId = ThreadId(threadId ?: channelIdValue ?: id),
        channelId = channelIdValue?.let(::ChannelId),
        threadChannelId = null,
        seq = seq,
        author = author,
        timestampLabel = updatedAt ?: createdAt ?: "",
        body = content,
        messageType = if (messageType == "system") MessageType.System else MessageType.Chat,
        systemEventType = null,
        metadata = emptyMap(),
        attachments = attachments.map { it.toDomain() },
        replyCount = 0,
        parentMessageId = null,
        updatedAtLabel = updatedAt,
    )
}

private fun AttachmentWireDto.toDomain(): Attachment = Attachment(
    id = AttachmentId(id),
    name = filename,
    mimeType = mimeType,
    sizeLabel = sizeBytes?.let { "$it B" } ?: "",
    thumbnailUrl = thumbnailUrl,
)

private fun ThreadUpdatedDto.toDomain(): ThreadUpdated = ThreadUpdated(
    parentMessageId = MessageId(parentMessageId),
    threadChannelId = ChannelId(threadChannelId),
    replyCount = replyCount,
    lastReplyAt = lastReplyAt,
    participantIds = participantIds.map(::MemberId),
)

private fun TaskCreatedDto.toDomain(): TaskCreated? {
    val list = tasks.map { it.toDomain() }
    return if (list.isEmpty()) null else TaskCreated(list)
}

private fun TaskInfoWireDto.toDomain(): TaskSummary = TaskSummary(
    id = TaskId(id),
    title = title,
    status = TaskStatus.fromWireValue(status) ?: TaskStatus.Todo,
    taskNumber = taskNumber,
    messageId = messageId?.let(::MessageId),
    parentMessageId = null,
    channelId = channelId?.let(::ChannelId),
    threadId = null,
    isThreadTask = channelType?.lowercase() == "thread",
    assignee = memberFrom(type = claimedByType, id = claimedById, name = claimedByName),
    creator = memberFrom(type = createdByType, id = createdById, name = createdByName),
    channelName = channelName,
    updatedAtLabel = updatedAt ?: createdAt.orEmpty(),
    canClaim = true,
)

private fun AgentActivityDto.toDomain(): MemberPresenceUpdated {
    val status = AgentStatus.fromWireValue(activity)
    return MemberPresenceUpdated(
        memberId = MemberId(agentId),
        presence = status?.presence ?: Presence.Offline,
        kind = MemberKind.Agent,
        agentStatus = status,
        statusLabel = detail,
    )
}

private fun SyncResumeResponseDto.toDomain(): SyncResumeResponse = SyncResumeResponse(
    messages = messages.map { it.toDomain() },
    currentSeq = currentSeq,
    hasMore = hasMore,
)

private fun memberFrom(type: String?, id: String?, name: String?): MemberProfile? {
    if (id.isNullOrBlank()) return null
    val label = name?.trim().orEmpty().ifBlank { id }
    return MemberProfile(
        id = MemberId(id),
        displayName = label,
        handle = label,
        kind = if (type == "agent") MemberKind.Agent else MemberKind.Human,
        avatarLabel = label.take(1).uppercase(),
        presence = Presence.Offline,
    )
}
