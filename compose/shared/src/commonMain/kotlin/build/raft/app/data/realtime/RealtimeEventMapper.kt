package build.raft.app.data.realtime

import build.raft.app.KuiklyThreadSurfaceKind
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.commentHostTypeOrNull
import build.raft.app.commentHostRouteKindOrNull
import build.raft.app.parseConversationKindOrNull
import build.raft.app.model.AgentProfile
import build.raft.app.model.AgentCreated
import build.raft.app.model.AgentStatus
import build.raft.app.model.Attachment
import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.Heartbeat
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageCommentHostSource
import build.raft.app.model.MessageCommentRef
import build.raft.app.model.MessageConversationContext
import build.raft.app.model.MessageId
import build.raft.app.model.MessageReaction
import build.raft.app.model.MessageNew
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.MessageType
import build.raft.app.model.MessageUpdated
import build.raft.app.model.PerReceiverNotificationPrefsChanged
import build.raft.app.model.PerReceiverPinnedChanged
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.Presence
import build.raft.app.model.RealtimeAgentActivityDetail
import build.raft.app.model.RealtimeAgentActivityEntry
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerDeleted
import build.raft.app.model.ServerPlanUpdated
import build.raft.app.model.ServerId
import build.raft.app.model.ServerMembershipRemoved
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.TaskCreated
import build.raft.app.model.TaskDeleted
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.TaskUpdated
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadUpdated
import build.raft.app.data.WireActionCardMetadata
import build.raft.app.data.WireForwardMessageBundle
import build.raft.app.data.projectAgentActivityStatus
import build.raft.app.data.toThreadActionCard
import build.raft.app.data.toForwardMessageBundle
import build.raft.app.data.toMessageMetadata
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlin.time.TimeSource

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
class RealtimeEventMapper(
    private val diagnosticsLogger: (String) -> Unit = {},
    // Flag-gated sync-core shadow-compare hook. Default no-op keeps the mapper decoupled and unchanged in
    // behavior; the provider injects the real observer when `sync_core_messages_v0` is in shadow mode.
    private val messageShadowObserver: MessageShadowObserver = MessageShadowObserver.NoOp,
) {

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
        val startedAt = TimeSource.Monotonic.markNow()
        val element = payload.toJsonObjectOrNull()
        val event = element?.let { mapElement(eventName, it) }
        diagnosticsLogger(
            "realtime_payload_parse eventName=$eventName payloadType=${payload.realtimePayloadType()} " +
                "mapped=${if (event != null) "typed" else ""} result=${if (event != null) "success" else "ignored"} " +
                "durationMs=${startedAt.elapsedNow().inWholeMilliseconds} dispatcher=caller"
        )
        return event
    }

    private fun Any?.realtimePayloadType(): String =
        when (this) {
            null -> "null"
            is String -> "String"
            is JsonObject -> "JsonObject"
            is JsonElement -> "JsonElement"
            is Map<*, *> -> "Map"
            is List<*> -> "List"
            is Number -> "Number"
            is Boolean -> "Boolean"
            else -> "Other"
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

    /**
     * Invoke the flag-gated shadow-compare hook, FAIL-OPEN. Shadow-compare is diagnostic-only and must
     * never affect the authoritative realtime event: any failure of the observer (flag lookup, canonical
     * pipeline, or the diagnostics logger itself) is swallowed here so the legacy [Message] still reaches
     * the store/UI unchanged. Returns the receiver untouched. (AD2: mapper-boundary isolation.)
     */
    private fun Message.withShadowObserved(eventName: String, element: JsonObject): Message {
        runCatching { messageShadowObserver.observe(eventName, element, this) }
            .onFailure { error ->
                runCatching {
                    diagnosticsLogger(
                        "message_shadow_observer_failed eventName=$eventName error=${error::class.simpleName}",
                    )
                }
            }
        return this
    }

    private fun mapElement(eventName: String, element: JsonObject): RealtimeEvent? = try {
        when (eventName) {
            RealtimeSocketEvents.MessageNew ->
                decodeMessageWire(element)?.toDomain()
                    ?.withShadowObserved(eventName, element)
                    ?.let(::MessageNew)
            RealtimeSocketEvents.MessageUpdated ->
                decodeMessageWire(element)?.toDomain()
                    ?.withRealtimeReactionsClearedIfExplicitNull(element)
                    ?.withShadowObserved(eventName, element)
                    ?.let(::MessageUpdated)
            RealtimeSocketEvents.DirectMessageNew -> decodeDirectMessageNew(element)
            RealtimeSocketEvents.ThreadUpdated ->
                decodeOrNull(ThreadUpdatedDto.serializer(), element)?.toDomain()
            RealtimeSocketEvents.ChannelUpdated ->
                decodeChannelRefresh(element, RealtimeRefreshScope.Home, RealtimeSocketEvents.ChannelUpdated)
            RealtimeSocketEvents.ChannelMembersUpdated ->
                decodeChannelRefresh(element, RealtimeRefreshScope.ChannelMembers, RealtimeSocketEvents.ChannelMembersUpdated)
            RealtimeSocketEvents.TaskCreated ->
                decodeOrNull(TaskCreatedDto.serializer(), element)?.toDomain()
            RealtimeSocketEvents.TaskUpdated ->
                decodeOrNull(TaskUpdatedDto.serializer(), element)?.toDomain()
            RealtimeSocketEvents.TaskDeleted ->
                decodeOrNull(TaskDeletedDto.serializer(), element)?.let { TaskDeleted(TaskId(it.taskId)) }
            RealtimeSocketEvents.AgentActivity ->
                decodeOrNull(AgentActivityDto.serializer(), element)?.toDomain()
            RealtimeSocketEvents.AgentSession ->
                decodeSubjectRefresh(element, RealtimeRefreshScope.Agents, eventName)
            RealtimeSocketEvents.AgentCreated ->
                decodeSubjectRefresh(element, RealtimeRefreshScope.Agents, eventName)
            RealtimeSocketEvents.AgentDeleted ->
                decodeSubjectRefresh(element, RealtimeRefreshScope.Agents, eventName)
            RealtimeSocketEvents.MachineStatus,
            RealtimeSocketEvents.MachineCapabilities,
            RealtimeSocketEvents.MachineUpdated,
            RealtimeSocketEvents.ComputerUpgradeProgress,
            RealtimeSocketEvents.ComputerUpgradeDone,
            RealtimeSocketEvents.DaemonStatus ->
                decodeSubjectRefresh(element, RealtimeRefreshScope.Machines, eventName)
            RealtimeSocketEvents.ServerMemberAdded,
            RealtimeSocketEvents.ServerMemberUpdated,
            RealtimeSocketEvents.ServerMemberLeft,
            RealtimeSocketEvents.ServerMemberRemoved ->
                decodeServerMemberRefresh(element, eventName)
            RealtimeSocketEvents.ServerMembershipRemoved ->
                decodeOrNull(ServerMembershipRemovedDto.serializer(), element)?.serverId?.let {
                    ServerMembershipRemoved(ServerId(it))
                }
            RealtimeSocketEvents.ServerDeleted -> {
                val dto = decodeOrNull(ServerDeletedDto.serializer(), element)
                dto?.serverId?.let { ServerDeleted(ServerId(it)) }
            }
            RealtimeSocketEvents.ServerPlanUpdated -> {
                val dto = decodeOrNull(ServerPlanUpdatedDto.serializer(), element)
                dto?.let { ServerPlanUpdated(ServerId(it.serverId), it.plan) }
            }
            RealtimeSocketEvents.AnnouncementNew ->
                RealtimeRefreshRequired(
                    scope = RealtimeRefreshScope.Announcements,
                    reason = RealtimeSocketEvents.AnnouncementNew
                )
            RealtimeSocketEvents.ReminderFired,
            RealtimeSocketEvents.ReminderScheduled,
            RealtimeSocketEvents.ReminderCanceled ->
                decodeSubjectRefresh(element, RealtimeRefreshScope.Reminders, eventName)
            RealtimeSocketEvents.SyncResumeResponse ->
                decodeOrNull(SyncResumeResponseDto.serializer(), element)?.toDomain()
            RealtimeSocketEvents.Heartbeat ->
                decodeOrNull(HeartbeatDto.serializer(), element)?.let { Heartbeat(it.seq) }
            RealtimeSocketEvents.ReadStateUpdated ->
                decodeOrNull(ReadStateUpdatedDto.serializer(), element)?.toDomain()
            RealtimeSocketEvents.ReadStateUpdatedBulk ->
                decodeOrNull(ReadStateUpdatedBulkDto.serializer(), element)?.toDomain()
            RealtimeSocketEvents.NotificationPrefsUpdated ->
                decodeOrNull(NotificationPrefsUpdatedDto.serializer(), element)?.toDomain()
            RealtimeSocketEvents.PinnedUpdated ->
                decodeOrNull(PinnedUpdatedDto.serializer(), element)?.toDomain()
            RealtimeSocketEvents.NotificationPush ->
                decodeOrNull(NotificationPushDto.serializer(), element)?.toDomainOrNull()
            else -> null
        }
    } catch (_: SerializationException) {
        null
    }

    private fun decodeDirectMessageNew(element: JsonObject): RealtimeEvent? {
        if ("id" in element || "senderId" in element || "senderType" in element || "content" in element) return null
        val channelId = (element["channelId"] as? JsonPrimitive)?.contentOrNull
            ?: return null
        return DirectMessageCreated(ChannelId(channelId))
    }

    private fun decodeChannelRefresh(
        element: JsonObject,
        scope: RealtimeRefreshScope,
        reason: String
    ): RealtimeRefreshRequired =
        RealtimeRefreshRequired(
            scope = scope,
            reason = reason,
            serverId = stringField(element, "serverId")?.let(::ServerId),
            channelId =
                stringField(element, "channelId")
                    ?.let(::ChannelId)
                    ?: stringField(element, "id")?.let(::ChannelId)
        )

    private fun decodeServerMemberRefresh(element: JsonObject, reason: String): RealtimeRefreshRequired =
        RealtimeRefreshRequired(
            scope = RealtimeRefreshScope.ServerMembers,
            reason = reason,
            serverId = stringField(element, "serverId")?.let(::ServerId),
            memberId =
                stringField(element, "userId")
                    ?.let(::MemberId)
                    ?: stringField(element, "memberId")?.let(::MemberId)
        )

    private fun decodeSubjectRefresh(
        element: JsonObject,
        scope: RealtimeRefreshScope,
        reason: String
    ): RealtimeRefreshRequired =
        RealtimeRefreshRequired(
            scope = scope,
            reason = reason,
            serverId = stringField(element, "serverId")?.let(::ServerId),
            subjectId =
                stringField(element, "agentId")
                    ?: stringField(element, "machineId")
                    ?: stringField(element, "daemonId")
                    ?: stringField(element, "reminderId")
                    ?: stringField(element, "id")
        )

    private fun stringField(element: JsonObject, name: String): String? =
        (element[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun decodeMessageWire(element: JsonObject): MessageWireDto? =
        decodeOrNull(MessageWireDto.serializer(), element)

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
private data class MessageWireDto(
    val id: String,
    val seq: Long? = null,
    val channelId: String? = null,
    val senderType: String? = null,
    val senderId: String? = null,
    val messageType: String? = null,
    val content: String = "",
    val randomId: String? = null,
    val threadId: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val senderName: String? = null,
    val senderDescription: String? = null,
    val senderMembershipStatus: String? = null,
    val attachments: List<AttachmentWireDto> = emptyList(),
    val reactions: List<MessageReactionWireDto>? = null,
    val actionMetadata: WireActionCardMetadata? = null,
    val commentRef: CommentRefWireDto? = null,
    val forwardBundle: WireForwardMessageBundle? = null,
    val conversationContext: ConversationContextWireDto? = null,
)

/**
 * Optional conversation identity on `message:*` emits (server task #86 /
 * mobile task #425). `channelType` carries `channels.type` verbatim
 * (channel|private|joint|dm|thread); the parent fields are set only for
 * thread messages. Absent on servers that pre-date the field.
 */
@Serializable
private data class ConversationContextWireDto(
    val channelType: String? = null,
    val parentMessageId: String? = null,
    val parentChannelId: String? = null,
    val parentChannelType: String? = null,
)

@Serializable
private data class MessageReactionWireDto(
    val emoji: String,
    val count: Int = 0,
    val reactorIds: List<String> = emptyList(),
    val reactorNames: List<String> = emptyList(),
)

@Serializable
private data class AttachmentWireDto(
    val id: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long? = null,
    val thumbnailUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val commentCount: Int = 0,
)

@Serializable
private data class CommentHostSourceWireDto(
    val type: String = "",
    val routeKind: String = "",
    val channelId: String = "",
    val parentMessageId: String? = null,
    val threadChannelId: String? = null,
    val rootThreadChannelId: String? = null,
)

@Serializable
private data class CommentRefWireDto(
    val attachmentId: String = "",
    val filename: String = "",
    val hostMessageId: String? = null,
    val hostSource: CommentHostSourceWireDto? = null,
    val anchorLabel: String? = null,
)

@Serializable
private data class ThreadUpdatedDto(
    val parentMessageId: String,
    val threadChannelId: String? = null,
    val channelId: String? = null,
    val threadId: String? = null,
    val replyCount: Int = 0,
    val lastReplyAt: String? = null,
    val participantIds: List<String> = emptyList(),
    val unreadCount: Int? = null,
    val firstUnreadMessageId: String? = null,
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
    val entries: List<JsonObject> = emptyList(),
    val timestamp: JsonElement? = null,
    val serverSeq: Long? = null,
)

@Serializable
private data class ServerMembershipRemovedDto(
    val serverId: String,
)

@Serializable
private data class ServerDeletedDto(
    val serverId: String,
)

@Serializable
private data class ServerPlanUpdatedDto(
    val serverId: String,
    val plan: String,
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

// RFC 041 per-receiver state push (server PR #4175, `routes/channels.ts`
// emit sites). `readStateVersion`/`prefsVersion` are required: an event we
// cannot version-gate is unusable, so decode failure drops it (fail closed)
// and hydrate reconciles.
@Serializable
private data class ReadStateUpdatedDto(
    val serverId: String? = null,
    val scopeId: String,
    val maxReadSeq: Long? = null,
    val readStateVersion: Long,
)

@Serializable
private data class ReadStateScopeWireDto(
    val scopeId: String,
    val maxReadSeq: Long? = null,
    val readStateVersion: Long,
)

@Serializable
private data class ReadStateUpdatedBulkDto(
    val serverId: String? = null,
    val scopes: List<ReadStateScopeWireDto> = emptyList(),
)

@Serializable
private data class NotificationPrefsWireDto(
    val activityMuted: Boolean = false,
    val muteFromSeq: Long? = null,
)

// task #459: server-qualified notification payload (server PR #4233,
// `buildNotificationPushSocketTargets` — DTO fields checked verbatim against
// staging 31042ed06). Identity fields are the contract: an event whose
// identity cannot yield a safe open target is dropped (fail closed) — the
// banner never guesses, same doctrine as #434.
@Serializable
private data class NotificationPushDto(
    val serverId: String,
    val kind: String,
    val channelId: String? = null,
    val threadId: String? = null,
    val parentChannelId: String? = null,
    val parentMessageId: String? = null,
    val messageId: String,
    val title: String = "",
    val body: String = "",
    val tag: String = "",
    val url: String? = null,
)

@Serializable
private data class NotificationPrefsUpdatedDto(
    val serverId: String? = null,
    val scopeId: String,
    val prefs: NotificationPrefsWireDto = NotificationPrefsWireDto(),
    val prefsVersion: Long,
)

@Serializable
private data class PinnedUpdatedDto(
    val serverId: String? = null,
    val pinned: List<SettingsServerSidebarPinnedItem> = emptyList(),
    val pinnedVersion: Long,
)

// ── DTO → domain mappers ─────────────────────────────────────────────────────

private fun MessageWireDto.toDomain(): Message {
    val authorId = senderId ?: "unknown"
    val author = MemberProfile(
        id = MemberId(authorId),
        displayName = senderName ?: authorId,
        handle = senderName ?: authorId,
        kind =
            when {
                senderType.equals("agent", ignoreCase = true) -> MemberKind.Agent
                messageType.equals("system", ignoreCase = true) -> MemberKind.System
                else -> MemberKind.Human
            },
        description = senderDescription,
        avatarLabel = (senderName ?: authorId).take(1).uppercase(),
        presence = Presence.Offline,
    )
    val channelIdValue = channelId
    // The message wire row only carries the channel it lives in. Thread linkage
    // (`threadChannelId`, `parentMessageId`, `replyCount`) is delivered via
    // `thread:updated` events and REST responses, not via `message:*` events.
    return Message(
        id = MessageId(id),
        threadId = ThreadId(channelIdValue ?: threadId ?: id),
        channelId = channelIdValue?.let(::ChannelId),
        threadChannelId = null,
        seq = seq,
        author = author,
        timestampLabel = updatedAt ?: createdAt ?: "",
        body = content,
        messageType = if (messageType.equals("system", ignoreCase = true)) MessageType.System else MessageType.Chat,
        systemEventType = null,
        metadata = actionMetadata.toThreadActionCard()?.toMessageMetadata().orEmpty(),
        attachments = attachments.map { it.toDomain() },
        randomId = randomId?.takeIf { it.isNotBlank() },
        senderMembershipStatus = senderMembershipStatus,
        replyCount = 0,
        parentMessageId = null,
        updatedAtLabel = updatedAt,
        reactions = reactions?.mapNotNull { it.toDomain() },
        commentRef = commentRef?.toDomain(),
        forwardBundle = forwardBundle.toForwardMessageBundle() ?: actionMetadata.toForwardMessageBundle(),
        conversationContext = conversationContext?.toDomain(),
    )
}

// Kind strings parse through the #417 contract: unknown values become null
// (never a guessed Channel), and the whole context is dropped when it says
// nothing — consumers treat that the same as an old server omitting it.
private fun ConversationContextWireDto.toDomain(): MessageConversationContext? {
    val context = MessageConversationContext(
        channelKind = parseConversationKindOrNull(channelType),
        parentMessageId = parentMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        parentChannelId = parentChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId),
        parentChannelKind = parseConversationKindOrNull(parentChannelType),
    )
    return context.takeIf {
        it.channelKind != null ||
            it.parentMessageId != null ||
            it.parentChannelId != null ||
            it.parentChannelKind != null
    }
}

// A `message:updated` event that omits the `reactions` field is a partial update
// (e.g. a content or attachment edit) and must keep the message's existing reactions.
// An event carrying `reactions: null` (or `[]`) is authoritative-empty and must clear
// them — this is how a web-side "remove last reaction" reaches mobile. kotlinx collapses
// both "field absent" and "explicit null" into a null DTO field, so we consult the raw
// JSON to recover the tri-state: only an explicit JSON null becomes an empty list here.
// `[]` already decodes to a non-null empty list, and an absent field stays null so the
// realtime merge (mergeRealtimeUpdate) preserves existing reactions.
private fun Message.withRealtimeReactionsClearedIfExplicitNull(element: JsonObject): Message =
    if (reactions == null && element["reactions"] is JsonNull) {
        copy(reactions = emptyList())
    } else {
        this
    }

private fun MessageReactionWireDto.toDomain(): MessageReaction? {
    val cleanEmoji = emoji.takeIf { it.isNotBlank() } ?: return null
    if (count < 0) return null
    return MessageReaction(
        emoji = cleanEmoji,
        count = count,
        reactorIds = reactorIds,
        reactorNames = reactorNames
    )
}

private fun AttachmentWireDto.toDomain(): Attachment = Attachment(
    id = AttachmentId(id),
    name = filename,
    mimeType = mimeType,
    sizeBytes = sizeBytes ?: 0L,
    sizeLabel = "",
    thumbnailUrl = thumbnailUrl,
    width = width,
    height = height,
    commentCount = commentCount.coerceAtLeast(0),
)

private fun CommentRefWireDto.toDomain(): MessageCommentRef? {
    val cleanAttachmentId = attachmentId.takeIf { it.isNotBlank() } ?: return null
    val cleanFilename = filename.takeIf { it.isNotBlank() } ?: return null
    return MessageCommentRef(
        attachmentId = AttachmentId(cleanAttachmentId),
        filename = cleanFilename,
        hostMessageId = hostMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        hostSource = hostSource?.toDomain(),
        anchorLabel = anchorLabel?.takeIf { it.isNotBlank() }
    )
}

private fun CommentHostSourceWireDto.toDomain(): MessageCommentHostSource? {
    val cleanType = type.commentHostTypeOrNull() ?: return null
    val cleanRouteKind = routeKind.commentHostRouteKindOrNull() ?: return null
    val cleanChannelId = channelId.takeIf { it.isNotBlank() } ?: return null
    return MessageCommentHostSource(
        type = cleanType,
        routeKind = cleanRouteKind,
        channelId = ChannelId(cleanChannelId),
        parentMessageId = parentMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        threadChannelId = threadChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId),
        rootThreadChannelId = rootThreadChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
    )
}

private fun ThreadUpdatedDto.toDomain(): ThreadUpdated? {
    val cleanParentMessageId = parentMessageId.takeIf { it.isNotBlank() } ?: return null
    val cleanThreadChannelId =
        threadChannelId?.takeIf { it.isNotBlank() }
            ?: channelId?.takeIf { it.isNotBlank() }
            ?: threadId?.takeIf { it.isNotBlank() }
            ?: return null
    return ThreadUpdated(
        parentMessageId = MessageId(cleanParentMessageId),
        threadChannelId = ChannelId(cleanThreadChannelId),
        replyCount = replyCount,
        lastReplyAt = lastReplyAt,
        participantIds = participantIds.map(::MemberId),
        unreadCount = unreadCount,
        firstUnreadMessageId = firstUnreadMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId)
    )
}

private fun TaskCreatedDto.toDomain(): TaskCreated? {
    val list = tasks.map { it.toDomain(envelopeChannelId = channelId) }
    return if (list.isEmpty()) null else TaskCreated(list)
}

private fun TaskUpdatedDto.toDomain(): TaskUpdated =
    TaskUpdated(task.toDomain(envelopeChannelId = channelId))

private fun TaskInfoWireDto.toDomain(envelopeChannelId: String? = null): TaskSummary = TaskSummary(
    id = TaskId(id),
    title = title,
    status = TaskStatus.fromWireValue(status) ?: TaskStatus.Todo,
    taskNumber = taskNumber,
    messageId = messageId?.let(::MessageId),
    parentMessageId = null,
    channelId = (channelId ?: envelopeChannelId)?.takeIf { it.isNotBlank() }?.let(::ChannelId),
    threadId = null,
    isThreadTask = channelType?.lowercase() == KuiklyThreadSurfaceKind.ReplyThread,
    assignee = memberFrom(type = claimedByType, id = claimedById, name = claimedByName),
    creator = memberFrom(type = createdByType, id = createdById, name = createdByName),
    channelName = channelName,
    updatedAtLabel = updatedAt ?: createdAt.orEmpty(),
    canClaim = true,
)

private fun AgentActivityDto.toDomain(): MemberPresenceUpdated? {
    val projectedActivity = entries.latestAgentActivityProjection()
        ?: AgentStatus.fromAgentActivityWireValue(activity)
            ?.let { status -> AgentActivityProjection(status = status, detail = detail) }
        ?: return null
    val status = projectedActivity.status
    return MemberPresenceUpdated(
        memberId = MemberId(agentId),
        presence = status.presence,
        kind = MemberKind.Agent,
        agentStatus = status,
        statusLabel = projectedActivity.detail,
        serverSeq = serverSeq,
        activityEntries = entries.toRealtimeAgentActivityEntries(
            agentId = agentId,
            timestamp = timestamp,
            serverSeq = serverSeq
        ),
    )
}

private data class AgentActivityProjection(
    val status: AgentStatus,
    val detail: String?
)

private fun List<JsonObject>.latestAgentActivityProjection(): AgentActivityProjection? {
    for (index in indices.reversed()) {
        val projection = this[index].toAgentActivityProjection()
        if (projection != null) return projection
    }
    return null
}

private fun JsonObject.toAgentActivityProjection(): AgentActivityProjection? {
    val projection =
        projectAgentActivityStatus(
            kind = stringValue("kind"),
            activity = stringValue("activity"),
            detail = stringValue("detail"),
            text = stringValue("text"),
            toolName = stringValue("toolName")
        ) ?: return null
    return AgentActivityProjection(
        status = projection.status,
        detail = projection.detail
    )
}

private fun List<JsonObject>.toRealtimeAgentActivityEntries(
    agentId: String,
    timestamp: JsonElement?,
    serverSeq: Long?
): List<RealtimeAgentActivityEntry> {
    val timestampLabel = timestamp.activityTimestampString()
    return mapIndexedNotNull { index, entry ->
        entry.toRealtimeAgentActivityEntry(
            agentId = agentId,
            timestamp = timestampLabel,
            serverSeq = serverSeq,
            index = index
        )
    }
}

private fun JsonObject.toRealtimeAgentActivityEntry(
    agentId: String,
    timestamp: String?,
    serverSeq: Long?,
    index: Int
): RealtimeAgentActivityEntry? {
    val kind = stringValue("kind")?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedKind = kind.lowercase()
    val id = realtimeAgentActivityId(
        agentId = agentId,
        serverSeq = serverSeq,
        timestamp = timestamp,
        index = index,
        kind = normalizedKind,
        entry = this
    )
    fun entry(
        type: String,
        title: String,
        body: String? = null,
        details: List<Pair<String, String>> = body?.let { listOf("Detail" to it) } ?: emptyList(),
        toolName: String? = null,
        toolInput: String? = null,
        statusActivity: String? = null,
        statusDetail: String? = null,
        expandable: Boolean = false,
        refTextEnabled: Boolean = false
    ): RealtimeAgentActivityEntry =
        RealtimeAgentActivityEntry(
            id = id,
            type = type,
            title = title,
            body = body?.sanitizeActivityText(),
            timestamp = timestamp,
            details = details.map { (label, value) ->
                RealtimeAgentActivityDetail(label = label, value = value.sanitizeActivityText())
            },
            toolName = toolName,
            toolInput = toolInput?.sanitizeActivityText(),
            statusActivity = statusActivity,
            statusDetail = statusDetail,
            expandable = expandable,
            refTextEnabled = refTextEnabled
        )
    return when (normalizedKind) {
        "thinking" ->
            entry(
                type = "thinking",
                title = "Thinking",
                body = stringValue("text"),
                expandable = true
            )
        "tool_start" -> {
            val toolName = stringValue("toolName").orEmpty()
            if (toolName.shouldHideToolStartInActivityLog()) {
                null
            } else {
                val input = this["toolInput"].toToolInputPreview()
                entry(
                    type = "tool_start",
                    title = toolName.toToolLogLabel(),
                    body = input,
                    details = listOfNotNull(
                        toolName.takeIf { it.isNotBlank() }?.let { "Tool" to it },
                        input?.let { "Input" to it },
                    ),
                    toolName = toolName.takeIf { it.isNotBlank() },
                    toolInput = input
                )
            }
        }
        "text" ->
            entry(
                type = "text",
                title = "Output",
                body = stringValue("text"),
                expandable = true
            )
        "slock_action" ->
            entry(
                type = "slock_action",
                title = stringValue("title")?.takeIf { it.isNotBlank() } ?: "Raft action",
                body = stringValue("text") ?: stringValue("message"),
                expandable = true,
                refTextEnabled = true
            )
        "system" ->
            entry(
                type = "system",
                title = stringValue("title")?.takeIf { it.isNotBlank() } ?: "System",
                body = stringValue("text") ?: stringValue("message"),
                expandable = true,
                refTextEnabled = true
            )
        "compaction_started" ->
            entry(
                type = "compaction_started",
                title = "Context Compaction Started"
            )
        "compaction_finished" ->
            entry(
                type = "compaction_finished",
                title = "Context Compaction Finished"
            )
        "status", "agent_status" -> {
            val activity = stringValue("activity")
            val detail = stringValue("detail").orEmpty()
            val display = resolveTrajectoryStatusDisplay(activity, detail)
            entry(
                type = "status",
                title = display.first,
                body = display.second.takeIf { it.isNotBlank() },
                statusActivity = activity,
                statusDetail = stringValue("detail"),
                refTextEnabled = true
            )
        }
        else ->
            entry(
                type = normalizedKind,
                title = stringValue("summary")
                    ?: stringValue("activity")?.toAgentActivityTitle()
                    ?: normalizedKind.toAgentActivityTitle(),
                body = stringValue("detail")
                    ?: stringValue("message")
                    ?: stringValue("text")
                    ?: stringValue("toolName")
                    ?: stringValue("name"),
                details = listOfNotNull(
                    (stringValue("detail") ?: stringValue("message") ?: stringValue("text"))?.let { "Detail" to it },
                    stringValue("toolName")?.let { "Tool" to it },
                ),
            )
    }
}

private fun realtimeAgentActivityId(
    agentId: String,
    serverSeq: Long?,
    timestamp: String?,
    index: Int,
    kind: String,
    entry: JsonObject
): String {
    val source = serverSeq?.let { "seq:$it" }
        ?: "ts:${timestamp.orEmpty()}:h:${entry.toString().stableActivityHash()}"
    return "realtime:$agentId:$source:$index:$kind"
}

private fun JsonElement?.activityTimestampString(): String? =
    (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonElement?.toToolInputPreview(): String? {
    if (this == null || this is JsonNull) return null
    val raw =
        when (this) {
            is JsonPrimitive -> contentOrNull.orEmpty()
            is JsonObject -> {
                val keys =
                    keys
                        .filterNot { it.isSensitiveActivityKey() }
                        .take(4)
                        .joinToString(", ")
                if (keys.isBlank()) "Structured input" else "Structured input: $keys"
            }
            else -> "Structured input"
        }
    return raw.sanitizeActivityText().takeIf { it.isNotBlank() }
}

private fun resolveTrajectoryStatusDisplay(activity: String?, detail: String): Pair<String, String> {
    if (activity == "working" && detail == "Starting…") return "Starting" to ""
    if (activity == "offline") {
        val primary =
            when {
                detail == "Stopped" -> "Stopped"
                detail == "Machine disconnected" -> "Disconnected"
                detail.startsWith("Crashed") -> "Crashed"
                else -> "Offline"
            }
        val secondary =
            if (primary == "Crashed") {
                detail.removePrefix("Crashed").trim().removeSurrounding("(", ")")
            } else {
                ""
            }
        return primary to secondary
    }
    val primary =
        when (activity) {
            "online" -> "Idle"
            "thinking" -> "Thinking"
            "working" -> "Working"
            "error" -> "Error"
            else -> activity?.toAgentActivityTitle() ?: "Status"
        }
    return primary to detail
}

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun String.shouldHideToolStartInActivityLog(): Boolean =
    normalizedToolName() == "send_message"

private fun String.toToolLogLabel(): String {
    val normalized = normalizedToolName()
    return when (normalized) {
        "read_file" -> "Read file"
        "write_file" -> "Write file"
        "upload_file" -> "Upload file"
        "view_file" -> "View file"
        "schedule_reminder" -> "Schedule reminder"
        "list_reminders" -> "List reminders"
        "cancel_reminder" -> "Cancel reminder"
        "run_command" -> "Run command"
        else -> normalized.toAgentActivityTitle()
    }
}

private fun String.normalizedToolName(): String =
    trim()
        .substringAfterLast("__")
        .substringAfterLast("/")
        .substringAfterLast(".")
        .ifBlank { trim() }

private fun String.toAgentActivityTitle(): String =
    replace("_", " ")
        .replace("-", " ")
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        .ifBlank { "Agent activity" }

private fun String.isSensitiveActivityKey(): Boolean {
    val lower = lowercase()
    return lower == "authorization" ||
        lower == "cookie" ||
        lower.contains("token") ||
        lower.contains("password") ||
        lower.contains("secret") ||
        lower.contains("credential")
}

private fun String.sanitizeActivityText(): String {
    val trimmed = trim()
    if (trimmed.isBlank()) return ""
    val lower = trimmed.lowercase()
    val credentialLike =
        "authorization" in lower ||
            "bearer " in lower ||
            "cookie" in lower ||
            "access_token" in lower ||
            "refresh_token" in lower ||
            "password" in lower ||
            "sk_agent_" in lower ||
            "sk_machine_" in lower
    if (credentialLike) return "[redacted]"
    return if (trimmed.length > 1200) trimmed.take(1200) + "…" else trimmed
}

private fun String.stableActivityHash(): String {
    var hash = 0
    for (char in this) {
        hash = hash * 31 + char.code
    }
    return hash.toString()
}

private fun SyncResumeResponseDto.toDomain(): SyncResumeResponse = SyncResumeResponse(
    messages = messages.map { it.toDomain() },
    currentSeq = currentSeq,
    hasMore = hasMore,
)

private fun ReadStateUpdatedDto.toDomain(): PerReceiverReadStateChanged? {
    val cleanScopeId = scopeId.takeIf { it.isNotBlank() } ?: return null
    val cleanMaxReadSeq = maxReadSeq?.takeIf { it >= 0L } ?: return null
    if (readStateVersion <= 0L) return null
    return PerReceiverReadStateChanged(
        serverId = serverId?.takeIf { it.isNotBlank() }?.let(::ServerId),
        scopes = listOf(
            PerReceiverReadStateScope(
                scopeId = ChannelId(cleanScopeId),
                maxReadSeq = cleanMaxReadSeq,
                readStateVersion = readStateVersion
            )
        )
    )
}

private fun ReadStateUpdatedBulkDto.toDomain(): PerReceiverReadStateChanged? {
    val cleanScopes = scopes.mapNotNull { scope ->
        val cleanScopeId = scope.scopeId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val cleanMaxReadSeq = scope.maxReadSeq?.takeIf { it >= 0L } ?: return@mapNotNull null
        if (scope.readStateVersion <= 0L) return@mapNotNull null
        PerReceiverReadStateScope(
            scopeId = ChannelId(cleanScopeId),
            maxReadSeq = cleanMaxReadSeq,
            readStateVersion = scope.readStateVersion
        )
    }
    if (cleanScopes.isEmpty()) return null
    return PerReceiverReadStateChanged(
        serverId = serverId?.takeIf { it.isNotBlank() }?.let(::ServerId),
        scopes = cleanScopes
    )
}

private fun NotificationPrefsUpdatedDto.toDomain(): PerReceiverNotificationPrefsChanged? {
    val cleanScopeId = scopeId.takeIf { it.isNotBlank() } ?: return null
    return PerReceiverNotificationPrefsChanged(
        serverId = serverId?.takeIf { it.isNotBlank() }?.let(::ServerId),
        scopeId = ChannelId(cleanScopeId),
        preference = ChannelActivityNotificationPreference(
            activityMuted = prefs.activityMuted,
            muteFromSeq = prefs.muteFromSeq,
            activityMuteSupported = true
        ),
        prefsVersion = prefsVersion
    )
}

private fun PinnedUpdatedDto.toDomain(): PerReceiverPinnedChanged? {
    val cleanPinned =
        pinned.mapNotNull { item ->
            val kind = item.kind.trim().lowercase().takeIf { it == "channel" || it == "agent" || it == "human" }
            val id = item.id.trim().takeIf { it.isNotBlank() }
            if (kind != null && id != null) SettingsServerSidebarPinnedItem(kind = kind, id = id) else null
        }
    return PerReceiverPinnedChanged(
        serverId = serverId?.takeIf { it.isNotBlank() }?.let(::ServerId),
        pinned = cleanPinned,
        pinnedVersion = pinnedVersion
    )
}

// Fail-closed per kind: a thread notification without its parent pair (or a
// channel/dm one without a channel id) cannot yield a provable open target —
// drop it rather than let a guessed surface reach the banner (#434/#441).
private fun NotificationPushDto.toDomainOrNull(): NotificationPushReceived? {
    val cleanServerId = serverId.takeIf { it.isNotBlank() } ?: return null
    val cleanMessageId = messageId.takeIf { it.isNotBlank() } ?: return null
    val parsedKind = parseConversationKindOrNull(kind) ?: return null
    val cleanChannelId = channelId?.takeIf { it.isNotBlank() }
    val cleanThreadId = threadId?.takeIf { it.isNotBlank() }
    val cleanParentChannelId = parentChannelId?.takeIf { it.isNotBlank() }
    val cleanParentMessageId = parentMessageId?.takeIf { it.isNotBlank() }
    when (parsedKind) {
        ConversationKind.Thread ->
            if (cleanParentChannelId == null || cleanParentMessageId == null || cleanThreadId == null) return null
        ConversationKind.Channel,
        ConversationKind.Joint,
        ConversationKind.DirectMessage ->
            if (cleanChannelId == null) return null
        else -> return null
    }
    return NotificationPushReceived(
        serverId = ServerId(cleanServerId),
        kind = parsedKind,
        channelId = cleanChannelId?.let(::ChannelId),
        threadId = cleanThreadId?.let(::ChannelId),
        parentChannelId = cleanParentChannelId?.let(::ChannelId),
        parentMessageId = cleanParentMessageId?.let(::MessageId),
        messageId = MessageId(cleanMessageId),
        title = title,
        body = body,
        tag = tag
    )
}

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
