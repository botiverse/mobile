package build.raft.app.data.realtime

import build.raft.app.toRaftAvatarLabel
import build.raft.app.MemberKindWireContract
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.data.ServerPushMode
import build.raft.app.data.PushPreviewMode
import build.raft.app.data.AgentActivityDetailKindWakeCrashLoopBlocked
import build.raft.app.data.AgentActivityStatusProjection
import build.raft.app.commentHostTypeOrNull
import build.raft.app.commentHostRouteKindOrNull
import build.raft.app.parseConversationKindOrNull
import build.raft.app.projectionRejectingDiagnosticsLogger
import build.raft.app.strictMemberKindOrNull
import build.raft.app.strictMessageTypeOrNull
import build.raft.app.strictTaskStatusOrNull
import build.raft.app.model.AgentProfile
import build.raft.app.model.AgentCreated
import build.raft.app.model.AgentStatus
import build.raft.app.model.AgentToolStuck
import build.raft.app.model.AgentWakeCrashLoop
import build.raft.app.model.Attachment
import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.Heartbeat
import build.raft.app.data.WireDelegatedBy
import build.raft.app.data.WireExternalAuthor
import build.raft.app.data.toMessageDelegatedBy
import build.raft.app.data.toExternalMessageAuthor
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.MemberProfile
import build.raft.app.model.providerLabel
import build.raft.app.model.Message
import build.raft.app.model.MessageCommentHostSource
import build.raft.app.model.MessageCommentRef
import build.raft.app.model.MessageConversationContext
import build.raft.app.model.MessageId
import build.raft.app.model.MessageReaction
import build.raft.app.model.MessageMention
import build.raft.app.model.MessageNew
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.MessageType
import build.raft.app.model.MessageUpdated
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.model.PerReceiverNotificationPrefsChanged
import build.raft.app.model.ServerNotificationPreferencesChanged
import build.raft.app.model.PerReceiverPinnedChanged
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.Presence
import build.raft.app.model.RealtimeAgentActivityDetail
import build.raft.app.model.RealtimeAgentActivityEntry
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ComputerRestartDone
import build.raft.app.model.ServerDeleted
import build.raft.app.model.ActivityChanged
import build.raft.app.model.ServerFeaturesChanged
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
import build.raft.app.model.ThreadFollowersUpdated
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.data.AgentTypingWire
import build.raft.app.data.ScopeReadUpdatedWire
import build.raft.app.data.toAgentTypingUpdated
import build.raft.app.data.WireActionCardMetadata
import build.raft.app.data.WireForwardMessageBundle
import build.raft.app.data.projectAgentActivityStatus
import build.raft.app.data.toThreadActionCard
import build.raft.app.data.toForwardMessageBundle
import build.raft.app.data.toScopeReadUpdated
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
    private val diagnosticsLogger: (String) -> Unit = projectionRejectingDiagnosticsLogger,
    // Flag-gated canonical projection gateway (`sync_core_messages_v1`). Default no-op keeps the mapper
    // decoupled and unchanged in behavior (returns null: zero decode, null carry); the provider injects the
    // real gateway when shadow mode is active. The gateway owns the flag gate, the face selection and the
    // ONE canonical decode per frame — the mapper only attaches the returned carry to the event.
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
     * Invoke the flag-gated canonical projection gateway ONCE for this frame, FAIL-OPEN. The gateway is
     * diagnostic-plus-carry only and must never affect the authoritative realtime event: any failure of the
     * gateway (flag lookup, canonical pipeline, or the diagnostics logger itself) is swallowed here so the
     * legacy [Message] still reaches the store/UI unchanged — the carry is just null and the shadow fold
     * lane skips this frame. (AD2: mapper-boundary isolation.)
     */
    private fun observeShadow(eventName: String, element: JsonObject, mapped: Message): CanonicalMessageCarry? =
        runCatching { messageShadowObserver.observe(eventName, element, mapped) }
            .onFailure { error ->
                runCatching {
                    diagnosticsLogger(
                        "message_shadow_observer_failed eventName=$eventName error=${error::class.simpleName}",
                    )
                }
            }
            .getOrNull()

    private fun mapElement(eventName: String, element: JsonObject): RealtimeEvent? = try {
        when (eventName) {
            RealtimeSocketEvents.MessageNew ->
                decodeMessageWire(element)?.toDomain(diagnosticsLogger)
                    ?.let { mapped -> MessageNew(mapped, observeShadow(eventName, element, mapped)) }
            RealtimeSocketEvents.MessageUpdated ->
                decodeMessageWire(element)?.toDomain(diagnosticsLogger)
                    ?.withRealtimeReactionsClearedIfExplicitNull(element)
                    ?.let { mapped -> MessageUpdated(mapped, observeShadow(eventName, element, mapped)) }
            RealtimeSocketEvents.DirectMessageNew -> decodeDirectMessageNew(element)
            RealtimeSocketEvents.ThreadFollowersUpdated ->
                decodeOrNull(ThreadFollowersUpdatedDto.serializer(), element)
                    ?.threadChannelId?.takeIf { it.isNotBlank() }?.let { ThreadFollowersUpdated(ChannelId(it)) }
            // read_receipts_v0 (slock services/readReceiptService.ts emitScopeReadUpdated).
            RealtimeSocketEvents.ScopeReadUpdated ->
                decodeOrNull(ScopeReadUpdatedWire.serializer(), element)?.toScopeReadUpdated()
            RealtimeSocketEvents.ThreadUpdated ->
                decodeOrNull(ThreadUpdatedDto.serializer(), element)?.toDomain()
            RealtimeSocketEvents.ChannelUpdated ->
                decodeChannelRefresh(element, RealtimeRefreshScope.Home, RealtimeSocketEvents.ChannelUpdated)
            RealtimeSocketEvents.ChannelMembersUpdated ->
                decodeChannelRefresh(element, RealtimeRefreshScope.ChannelMembers, RealtimeSocketEvents.ChannelMembersUpdated)
            // Mobile-local: the native host could not replay a reconnect gap (see RealtimeLocalEvents).
            RealtimeLocalEvents.CatchUpRequired ->
                RealtimeRefreshRequired(
                    scope = RealtimeRefreshScope.Home,
                    reason = stringField(element, "reason") ?: RealtimeLocalEvents.CatchUpRequired,
                    serverId = stringField(element, "serverId")?.let(::ServerId),
                )
            RealtimeSocketEvents.TaskCreated ->
                decodeOrNull(TaskCreatedDto.serializer(), element)?.toDomain(diagnosticsLogger)
            RealtimeSocketEvents.TaskUpdated ->
                decodeOrNull(TaskUpdatedDto.serializer(), element)?.toDomain(diagnosticsLogger)
            RealtimeSocketEvents.TaskDeleted ->
                decodeOrNull(TaskDeletedDto.serializer(), element)?.let { TaskDeleted(TaskId(it.taskId)) }
            RealtimeSocketEvents.AgentSeen ->
                decodeOrNull(AgentSeenDto.serializer(), element)?.toDomain()
            RealtimeSocketEvents.AgentActivity ->
                decodeOrNull(AgentActivityDto.serializer(), element)?.toDomain()
            // Agent typing line (slock packages/shared/src/agentTyping.ts AgentTypingEvent).
            RealtimeSocketEvents.AgentTyping ->
                decodeOrNull(AgentTypingWire.serializer(), element)?.toAgentTypingUpdated()
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
            // machine:upgrade-request: the server settled or expired a web upgrade request; the
            // machines list carries the result (payload `{machineId, upgradeRequest}`).
            RealtimeSocketEvents.MachineUpgradeRequest,
            RealtimeSocketEvents.DaemonStatus ->
                decodeSubjectRefresh(element, RealtimeRefreshScope.Machines, eventName)
            RealtimeSocketEvents.ComputerRestartDone ->
                decodeOrNull(ComputerRestartDoneDto.serializer(), element)?.toDomain()
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
            RealtimeSocketEvents.ServerFeaturesChanged ->
                decodeOrNull(ServerFeaturesChangedDto.serializer(), element)
                    ?.takeIf { it.serverId.isNotBlank() }
                    ?.let { ServerFeaturesChanged(ServerId(it.serverId), it.keys) }
            RealtimeSocketEvents.ActivityChanged ->
                decodeOrNull(ActivityChangedDto.serializer(), element)
                    ?.takeIf { it.serverId.isNotBlank() }
                    ?.let { ActivityChanged(ServerId(it.serverId), it.source) }
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
                decodeOrNull(SyncResumeResponseDto.serializer(), element)?.toDomainOrRecovery(diagnosticsLogger)
            RealtimeSocketEvents.Heartbeat ->
                decodeOrNull(HeartbeatDto.serializer(), element)?.let { Heartbeat(it.seq) }
            RealtimeSocketEvents.ReadStateUpdated ->
                decodeOrNull(ReadStateUpdatedDto.serializer(), element)?.toDomain()
            RealtimeSocketEvents.ReadStateUpdatedBulk ->
                decodeOrNull(ReadStateUpdatedBulkDto.serializer(), element)?.toDomain()
            RealtimeSocketEvents.NotificationPrefsUpdated ->
                decodeOrNull(NotificationPrefsUpdatedDto.serializer(), element)?.toDomain(
                    diagnostics = diagnosticsLogger,
                    canonicalServerModePresent = (element["prefs"] as? JsonObject)?.containsKey("serverPushMode") == true
                )
            RealtimeSocketEvents.PinnedUpdated ->
                decodeOrNull(PinnedUpdatedDto.serializer(), element)?.toDomain()
            RealtimeSocketEvents.NotificationPush ->
                decodeOrNull(NotificationPushDto.serializer(), element)?.toDomainOrNull(diagnosticsLogger)
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
    /** Slack-bridge external sender attribution (present iff senderType=external_projection). */
    val externalAuthor: WireExternalAuthor? = null,
    /** `MessageMention[]` (web `messageStore.ts`); null/absent on producers that omit it. */
    val mentions: List<MessageMentionWireDto>? = null,
    /** RFC 077 `DelegatedByLabel | null` (`messageRealtimeProducerRegistry.ts` lists `delegatedBy`). */
    val delegatedBy: WireDelegatedBy? = null,
    /** RFC 077 PR 2b: set when the owner retracted the message; `content` is then the placeholder. */
    val deletedAt: String? = null,
)

/** One `mentions[]` entry: `{ type: "user" | "agent" | "external"; id; name; displayName? }`. */
@Serializable
private data class MessageMentionWireDto(
    val type: String = "",
    val id: String = "",
    val name: String = "",
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
private data class ThreadFollowersUpdatedDto(val threadChannelId: String)

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
    val latestReply: ThreadLatestReplyDto? = null,
    val syncCoreReplyWindow: ThreadRepliesSyncWindowEnvelopeDto? = null,
)

@Serializable
private data class ThreadRepliesSyncWindowEnvelopeDto(
    val producer: String = "",
    val discussion: ThreadRepliesDiscussionDto? = null,
    val window: ThreadRepliesSyncWindowDto? = null,
)

@Serializable
private data class ThreadRepliesDiscussionDto(
    val root: ThreadRepliesRootDto? = null,
    val relation: ThreadRepliesRelationDto? = null,
    val parentScopeKey: ThreadRepliesParentScopeDto? = null,
    val backing: String = "",
)

@Serializable
private data class ThreadRepliesRootDto(
    val kind: String = "",
    val serverId: String = "",
    val id: String = "",
)

@Serializable
private data class ThreadRepliesRelationDto(val kind: String = "")

@Serializable
private data class ThreadRepliesParentScopeDto(
    val serverId: String = "",
    val scopeKind: String = "",
    val scopeId: String = "",
)

@Serializable
private data class ThreadRepliesSyncWindowDto(
    val kind: String = "",
    val scopeCursor: String? = null,
    val epoch: String? = null,
)

@Serializable
private data class ThreadLatestReplyDto(
    val id: String = "",
    val seq: Long? = null,
    val content: String = "",
    val senderId: String = "",
    val senderType: String = "user",
    val senderName: String = "",
    val senderDisplayName: String? = null,
    val senderAvatarUrl: String? = null,
    val createdAt: String = "",
    val messageType: String? = null,
    /** The reply is the enriched message payload, so it carries `mentions` like `message:new`. */
    val mentions: List<MessageMentionWireDto>? = null,
)

@Serializable
private data class TaskInfoWireDto(
    val id: String,
    val revision: Long? = null,
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
private data class AgentSeenDto(
    val agentId: String,
    val lastSeenAt: String,
)

@Serializable
private data class AgentActivityDto(
    val agentId: String,
    val activity: String? = null,
    val detail: String? = null,
    val detailKind: String? = null,
    val entries: List<JsonObject> = emptyList(),
    val timestamp: JsonElement? = null,
    val serverSeq: Long? = null,
    /** Shared `WakeCrashLoopActivityDiagnostic`, sent with detailKind `wake_crash_loop_blocked`. */
    val wakeCrashLoop: WakeCrashLoopDto? = null,
    /** Shared `ToolStuckActivityDiagnostic`, on every frame while the agent is stuck on a tool call. */
    val toolStuck: ToolStuckDto? = null,
)

/** Shared `ToolStuckActivityDiagnostic` (botiverse/slock packages/shared/src/index.ts, #10409). */
@Serializable
internal data class ToolStuckDto(
    val toolName: String? = null,
    val toolDetailKind: String? = null,
    val startedAtMs: Long? = null,
    val quietSinceMs: Long? = null,
    val thresholdMs: Long? = null,
    val queuedMessages: Int? = null,
)

/** The stuck report, or null without a tool name or quiet start (not shown then). */
internal fun ToolStuckDto?.toAgentToolStuck(): AgentToolStuck? {
    if (this == null) return null
    val tool = toolName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val quietSince = quietSinceMs ?: startedAtMs ?: return null
    return AgentToolStuck(
        toolName = tool,
        toolDetailKind = toolDetailKind?.trim()?.takeIf { it.isNotEmpty() },
        startedAtMs = startedAtMs ?: quietSince,
        quietSinceMs = quietSince,
        thresholdMs = (thresholdMs ?: 0L).coerceAtLeast(0L),
        queuedMessages = (queuedMessages ?: 0).coerceAtLeast(0),
    )
}

/**
 * The fields of shared `WakeCrashLoopActivityDiagnostic` the client shows (botiverse/slock
 * 24873fd5be37 packages/shared/src/index.ts; `lastExitReason` from #10860, absent on older servers).
 */
@Serializable
internal data class WakeCrashLoopDto(
    val earlyExitCount: Int? = null,
    val lastExitReason: String? = null,
)

/** The pause carrier of a `wake_crash_loop_blocked` status; null for any other kind or without a count. */
internal fun WakeCrashLoopDto?.toAgentWakeCrashLoop(detailKind: String?): AgentWakeCrashLoop? {
    if (this == null || detailKind?.trim() != AgentActivityDetailKindWakeCrashLoopBlocked) return null
    val count = earlyExitCount?.takeIf { it > 0 } ?: return null
    return AgentWakeCrashLoop(earlyExitCount = count, lastExitReason = lastExitReason?.trim()?.takeIf { it.isNotEmpty() })
}

@Serializable
private data class ServerMembershipRemovedDto(
    val serverId: String,
)

@Serializable
private data class ServerDeletedDto(
    val serverId: String,
)

/**
 * `computer:restart:done` as the server relays it: the Computer's frame (`type`, `requestId`, `ok`,
 * `error?`; packages/shared/src/index.ts) with `machineId` added (services/agentOrchestrator.ts).
 */
@Serializable
private data class ComputerRestartDoneDto(
    val machineId: String? = null,
    val requestId: String? = null,
    val ok: Boolean = false,
    val error: String? = null,
) {
    fun toDomain(): ComputerRestartDone? {
        val machine = machineId?.takeIf { it.isNotBlank() } ?: return null
        val request = requestId?.takeIf { it.isNotBlank() } ?: return null
        return ComputerRestartDone(machineId = machine, requestId = request, ok = ok, error = error?.takeIf { it.isNotBlank() })
    }
}

/** slock `packages/shared/src/activityObjectsContract.ts` `ActivityChangedEvent`: `{ serverId, source }`. */
@Serializable
private data class ActivityChangedDto(
    val serverId: String,
    val source: String = "object",
)

/** slock `packages/shared/src/featuresChanged.ts` `ServerFeaturesChangedEvent`: `{ serverId, keys }`. */
@Serializable
private data class ServerFeaturesChangedDto(
    val serverId: String,
    val keys: List<String> = emptyList(),
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
    // slock #10982 (`socket/syncResume.ts`): additive; kept as raw JSON so only a JSON string is a horizon.
    val horizon: JsonElement? = null,
    val horizonReset: Boolean = false,
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
    val unreadCount: JsonElement? = null,
)

@Serializable
private data class ReadStateScopeWireDto(
    val scopeId: String,
    val maxReadSeq: Long? = null,
    val readStateVersion: Long,
    val unreadCount: JsonElement? = null,
)

@Serializable
private data class ReadStateUpdatedBulkDto(
    val serverId: String? = null,
    val scopes: List<ReadStateScopeWireDto> = emptyList(),
)

@Serializable
private data class NotificationPrefsWireDto(
    val activityMuted: Boolean? = null,
    val muteFromSeq: Long? = null,
    val serverPushMuted: Boolean? = null,
    val serverPushMentionsOnly: Boolean? = null,
    val serverPushMode: String? = null,
    val pushPreviewMode: String? = null,
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
    val serverName: String? = null,
    val channelName: String? = null,
    val parentChannelKind: String? = null,
    val senderId: String? = null,
    val senderType: String? = null,
    val senderName: String? = null,
    val messagePreview: String? = null,
    val mentioned: Boolean? = null,
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

private fun MessageWireDto.toDomain(diagnostics: (String) -> Unit): Message? {
    val authorId =
        senderId?.trim()?.takeIf { it.isNotBlank() }
            ?: run {
                diagnostics("projection_value_rejected source=realtime_message_sender field=member_id value=blank")
                return null
            }
    val resolvedMessageType = messageType.strictMessageTypeOrNull("realtime_message_type", diagnostics) ?: return null
    val senderKind =
        senderType.strictMemberKindOrNull(
            source = "realtime_message_sender",
            diagnostics = diagnostics,
            contract = MemberKindWireContract.MessageSender,
        ) ?: return null
    val external = senderKind == MemberKind.External
    if (external && externalAuthor == null) {
        diagnostics("projection_value_rejected source=realtime_message_sender field=externalAuthor value=missing messageId=$id")
        return null
    }
    val resolvedExternalAuthor =
        if (external) externalAuthor?.toExternalMessageAuthor(senderName) else null
    val authorKind = if (resolvedMessageType == MessageType.System) MemberKind.System else senderKind
    val author = MemberProfile(
        id = MemberId(authorId),
        displayName = resolvedExternalAuthor?.displayName ?: senderName ?: authorId,
        handle = senderName ?: authorId,
        kind = authorKind,
        description = resolvedExternalAuthor?.providerLabel() ?: senderDescription,
        avatarUrl = resolvedExternalAuthor?.avatarUrl,
        avatarLabel = (resolvedExternalAuthor?.displayName ?: senderName ?: authorId).toRaftAvatarLabel(),
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
        // A message update may advance updatedAt when its task state, reply
        // projection, reactions, or body changes. The timeline label remains
        // the immutable creation time; otherwise an in-place update makes an
        // older row appear newer than the rows rendered after it.
        timestampLabel = createdAt ?: "",
        createdAtEpochMillis = parseRaftTimestampEpochMillisOrNull(createdAt),
        body = content,
        messageType = resolvedMessageType,
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
        externalAuthor = resolvedExternalAuthor,
        delegatedBy = delegatedBy.toMessageDelegatedBy(authorKind),
        deletedAt = deletedAt?.takeIf { it.isNotBlank() },
        mentions = mentions.toDomainMentions(),
    )
}

private fun List<MessageMentionWireDto>?.toDomainMentions(): List<MessageMention> =
    orEmpty().mapNotNull { mention ->
        val type = mention.type.trim()
        val id = mention.id.trim()
        if (type.isEmpty() || id.isEmpty()) null else MessageMention(type = type, id = id, name = mention.name)
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
        firstUnreadMessageId = firstUnreadMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        latestReply = latestReply?.toReplyPreview(),
        syncCoreReplyWindow = syncCoreReplyWindow?.toDomain(),
        latestReplyMentions = latestReply?.mentions.toDomainMentions(),
    )
}

private fun ThreadRepliesSyncWindowEnvelopeDto.toDomain(): build.raft.app.network.sync.ThreadRepliesSyncWindowEnvelope? {
    val discussion = discussion ?: return null
    val root = discussion.root ?: return null
    val relation = discussion.relation ?: return null
    val parentScope = discussion.parentScopeKey ?: return null
    val window = window ?: return null
    if (
        producer.isBlank() ||
        root.kind.isBlank() ||
        root.serverId.isBlank() ||
        root.id.isBlank() ||
        relation.kind.isBlank() ||
        parentScope.serverId.isBlank() ||
        parentScope.scopeKind.isBlank() ||
        parentScope.scopeId.isBlank() ||
        discussion.backing.isBlank() ||
        window.kind.isBlank()
    ) {
        return null
    }
    return build.raft.app.network.sync.ThreadRepliesSyncWindowEnvelope(
        producer = producer,
        rootKind = root.kind,
        rootServerId = root.serverId,
        rootMessageId = root.id,
        relationKind = relation.kind,
        parentScopeServerId = parentScope.serverId,
        parentScopeKind = parentScope.scopeKind,
        parentScopeId = parentScope.scopeId,
        backing = discussion.backing,
        windowKind = window.kind,
        scopeCursor = window.scopeCursor,
        epoch = window.epoch,
    )
}

private fun ThreadLatestReplyDto.toReplyPreview(): ReplyPreview? {
    val cleanMessageId = id.trim().takeIf { it.isNotBlank() } ?: return null
    val orderedSeq = seq?.takeIf { it > 0L } ?: return null
    val projectedSenderType =
        if (messageType.equals("system", ignoreCase = true)) {
            "system"
        } else {
            senderType.trim().lowercase()
        }
    if (projectedSenderType !in setOf("user", "agent", "system")) return null
    val cleanSenderName = senderName.trim()
    return ReplyPreview(
        messageId = cleanMessageId,
        seq = orderedSeq,
        content = content,
        senderId = senderId.trim(),
        senderType = projectedSenderType,
        senderName = cleanSenderName,
        senderAvatarUrl = senderAvatarUrl?.trim()?.takeIf { it.isNotBlank() },
        createdAt = createdAt.trim(),
        senderDisplayName = senderDisplayName?.trim()?.takeIf { it.isNotBlank() },
    )
}

private fun TaskCreatedDto.toDomain(diagnostics: (String) -> Unit): TaskCreated? {
    val list = tasks.mapNotNull { it.toDomain(envelopeChannelId = channelId, diagnostics = diagnostics) }
    return if (list.isEmpty()) null else TaskCreated(list)
}

private fun TaskUpdatedDto.toDomain(diagnostics: (String) -> Unit): TaskUpdated? =
    task.toDomain(envelopeChannelId = channelId, diagnostics = diagnostics)?.let(::TaskUpdated)

private fun TaskInfoWireDto.toDomain(
    envelopeChannelId: String? = null,
    diagnostics: (String) -> Unit,
): TaskSummary? {
    val resolvedStatus = status.strictTaskStatusOrNull("realtime_task", diagnostics) ?: return null
    val resolvedMessageId = (messageId ?: id).let(::MessageId)
    return TaskSummary(
        id = TaskId(id),
        revision = revision,
        title = title,
        status = resolvedStatus,
        taskNumber = taskNumber,
        messageId = resolvedMessageId,
        parentMessageId = resolvedMessageId,
        channelId = (channelId ?: envelopeChannelId)?.takeIf { it.isNotBlank() }?.let(::ChannelId),
        threadId = null,
        isThreadTask = channelType?.lowercase() == RaftThreadSurfaceKind.ReplyThread,
        assignee = memberFrom(type = claimedByType, id = claimedById, name = claimedByName, diagnostics = diagnostics),
        creator = memberFrom(type = createdByType, id = createdById, name = createdByName, diagnostics = diagnostics),
        channelName = channelName,
        updatedAtLabel = updatedAt ?: createdAt.orEmpty(),
        canClaim = true,
    )
}

private fun AgentSeenDto.toDomain(): MemberPresenceUpdated? {
    val cleanId = agentId.trim().takeIf { it.isNotBlank() } ?: return null
    val seen = lastSeenAt.trim().takeIf { it.isNotBlank() } ?: return null
    return MemberPresenceUpdated(
        memberId = MemberId(cleanId),
        // The server emits agent:seen only for external agents; presence is
        // derived — Active means "seen", not a runtime state.
        presence = Presence.Active,
        kind = MemberKind.Agent,
        lastSeenAt = seen
    )
}

private fun AgentActivityDto.toDomain(): MemberPresenceUpdated? {
    val projectedActivity = entries.latestAgentActivityProjection()
        ?: AgentStatus.fromAgentActivityWireValue(activity)
            ?.let { status ->
                AgentActivityStatusProjection(
                    status = status,
                    detail = detail,
                    detailKind = detailKind?.trim()?.takeIf { it.isNotBlank() }
                )
            }
        ?: return null
    val status = projectedActivity.status
    return MemberPresenceUpdated(
        memberId = MemberId(agentId),
        presence = status.presence,
        kind = MemberKind.Agent,
        agentStatus = status,
        statusLabel = projectedActivity.detail,
        detailKind = projectedActivity.detailKind,
        activityToolName = projectedActivity.toolName,
        serverSeq = serverSeq,
        wakeCrashLoop = wakeCrashLoop.toAgentWakeCrashLoop(projectedActivity.detailKind),
        toolStuck = toolStuck.toAgentToolStuck(),
        activityEntries = entries.toRealtimeAgentActivityEntries(
            agentId = agentId,
            timestamp = timestamp,
            serverSeq = serverSeq
        ),
    )
}

private fun List<JsonObject>.latestAgentActivityProjection(): AgentActivityStatusProjection? {
    for (index in indices.reversed()) {
        val projection = this[index].toAgentActivityProjection()
        if (projection != null) return projection
    }
    return null
}

private fun JsonObject.toAgentActivityProjection(): AgentActivityStatusProjection? =
    projectAgentActivityStatus(
        kind = stringValue("kind"),
        activity = stringValue("activity"),
        detail = stringValue("detail"),
        text = stringValue("text"),
        toolName = stringValue("toolName"),
        detailKind = stringValue("detailKind")
    )

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
        isSubagent: Boolean = false,
        subagentType: String? = null,
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
            isSubagent = isSubagent,
            subagentType = subagentType,
            expandable = expandable,
            refTextEnabled = refTextEnabled
        )
    return when (normalizedKind) {
        "thinking" ->
            entry(
                type = "thinking",
                title = "Thinking",
                body = stringValue("text"),
                isSubagent = hasSubagentLineage(),
                subagentType = subagentType(),
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
                    toolInput = input,
                    isSubagent = hasSubagentLineage(),
                    subagentType = subagentType()
                )
            }
        }
        "text" ->
            entry(
                type = "text",
                title = "Output",
                body = stringValue("text"),
                isSubagent = hasSubagentLineage(),
                subagentType = subagentType(),
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
            val activity = stringValue("activityKind") ?: stringValue("activity")
            val detail = stringValue("detail").orEmpty()
            val display = resolveTrajectoryStatusDisplay(
                activity = activity,
                detail = detail,
                detailKind = stringValue("detailKind"),
            )
            entry(
                type = "status",
                title = display.first,
                body = display.second.takeIf { it.isNotBlank() },
                statusActivity = activity,
                statusDetail = stringValue("detail"),
                isSubagent = hasSubagentLineage(),
                subagentType = subagentType(),
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

private fun resolveTrajectoryStatusDisplay(
    activity: String?,
    detail: String,
    detailKind: String? = null,
): Pair<String, String> {
    if (activity == "working" && (detailKind == "starting" || detail == "Starting…")) {
        return "Starting" to ""
    }
    if (activity == "online") {
        val lifecycleTitle = when (detailKind) {
            "computer_started" -> "Started"
            "computer_restarted" -> "Restarted"
            "computer_upgraded" -> "Upgraded"
            else -> null
        }
        if (lifecycleTitle != null) return lifecycleTitle to detail
    }
    if (activity == "error" && detailKind == "computer_operation_failed") {
        return "Computer operation failed" to detail
    }
    if (activity == "offline") {
        val primary =
            when {
                detailKind == "stopped" -> "Stopped"
                detailKind == "machine_disconnected" -> "Disconnected"
                detailKind == "runtime_crashed" -> "Crashed"
                detail == "Stopped" -> "Stopped"
                detail == "Machine disconnected" -> "Disconnected"
                detail.startsWith("Crashed") -> "Crashed"
                else -> "Offline"
            }
        val secondary = when {
            primary == "Crashed" -> detail.removePrefix("Crashed").trim().removeSurrounding("(", ")")
            primary == "Stopped" && detail.isNotBlank() && detail != "Stopped" -> detail
            else -> ""
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

private fun JsonObject.subagentType(): String? =
    (this["subagent"] as? JsonObject)
        ?.stringValue("subagentType")
        ?.takeIf { it.isNotBlank() }

private fun JsonObject.hasSubagentLineage(): Boolean =
    this["subagent"] is JsonObject

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

private fun SyncResumeResponseDto.toDomainOrRecovery(diagnostics: (String) -> Unit): RealtimeEvent {
    val projectedMessages = ArrayList<Message>(messages.size)
    messages.forEach { wireMessage ->
        val projected = wireMessage.toDomain(diagnostics)
        if (projected == null) {
            val recoverySeq = maxOf(currentSeq, messages.mapNotNull { it.seq }.maxOrNull() ?: 0L)
            diagnostics(
                "sync_resume_projection_rejected recovery=${if (recoverySeq > 0L) "heartbeat" else "home_refresh"} " +
                    "currentSeq=${currentSeq.coerceAtLeast(0L)}"
            )
            return if (recoverySeq > 0L) {
                Heartbeat(recoverySeq)
            } else {
                RealtimeRefreshRequired(
                    scope = RealtimeRefreshScope.Home,
                    reason = "sync_resume_projection_rejected",
                )
            }
        }
        projectedMessages += projected
    }
    return SyncResumeResponse(
        messages = projectedMessages,
        currentSeq = currentSeq,
        hasMore = hasMore,
        horizon = RealtimeSyncHorizon.parseJson(horizon),
        horizonReset = horizonReset,
    )
}

// Optional counts are hints; malformed counts must not discard a valid watermark.
// Matches server emitReadStateUpdated[Bulk] at slock 2172d9b38 (PR #9113).
private fun JsonElement?.readStateUnreadCountOrNull(): Int? {
    val value = this as? JsonPrimitive ?: return null
    if (value.isString) return null
    return value.content.toIntOrNull()?.takeIf { it >= 0 }
}

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
                readStateVersion = readStateVersion,
                unreadCount = unreadCount.readStateUnreadCountOrNull()
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
            readStateVersion = scope.readStateVersion,
            unreadCount = scope.unreadCount.readStateUnreadCountOrNull()
        )
    }
    if (cleanScopes.isEmpty()) return null
    return PerReceiverReadStateChanged(
        serverId = serverId?.takeIf { it.isNotBlank() }?.let(::ServerId),
        scopes = cleanScopes
    )
}

private fun NotificationPrefsUpdatedDto.toDomain(
    diagnostics: (String) -> Unit,
    canonicalServerModePresent: Boolean
): RealtimeEvent? {
    val cleanScopeId = scopeId.takeIf { it.isNotBlank() } ?: return null
    val serverMode = if (canonicalServerModePresent) {
        ServerPushMode.fromWire(prefs.serverPushMode)
            ?: run {
                diagnostics(
                    "projection_value_rejected source=realtime_server_notification field=server_push_mode value=unknown"
                )
                return null
            }
    } else {
        when {
                prefs.serverPushMuted == true -> ServerPushMode.None
                prefs.serverPushMentionsOnly == true -> ServerPushMode.Mentions
                prefs.serverPushMuted == false || prefs.serverPushMentionsOnly == false -> ServerPushMode.All
                else -> null
            }
    }
    if (serverMode != null) {
        val previewMode =
            prefs.pushPreviewMode?.let { raw ->
                // Only the three canonical wire values are accepted (the retired "private" included
                // among the rejected); an unrecognized value is rejected rather than silently defaulted.
                PushPreviewMode.fromWire(raw)
                    ?: run {
                        diagnostics(
                            "projection_value_rejected source=realtime_server_notification " +
                                "field=push_preview_mode value=unknown"
                        )
                        return null
                    }
            }
        val cleanServerId = serverId?.takeIf { it.isNotBlank() } ?: cleanScopeId
        return ServerNotificationPreferencesChanged(
            serverId = ServerId(cleanServerId),
            serverPushMode = serverMode.wireName,
            pushPreviewMode = previewMode?.wireName,
            prefsVersion = prefsVersion
        )
    }
    val activityMuted = prefs.activityMuted ?: return null
    return PerReceiverNotificationPrefsChanged(
        serverId = serverId?.takeIf { it.isNotBlank() }?.let(::ServerId),
        scopeId = ChannelId(cleanScopeId),
        preference = ChannelActivityNotificationPreference(
            activityMuted = activityMuted,
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
private fun NotificationPushDto.toDomainOrNull(
    diagnostics: (String) -> Unit,
): NotificationPushReceived? {
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
    val cleanSenderId = senderId?.trim()?.takeIf { it.isNotBlank() }
    val cleanSenderType = senderType?.trim()?.takeIf { it.isNotBlank() }
    val senderKind =
        if (cleanSenderId != null && cleanSenderType != null) {
            cleanSenderType.strictMemberKindOrNull(
                source = "notification_push_sender",
                diagnostics = diagnostics,
                contract = MemberKindWireContract.MessageSender,
            )
        } else {
            null
        }
    if ((cleanSenderId == null) != (cleanSenderType == null)) {
        diagnostics("projection_value_rejected source=notification_push_sender field=member_identity value=incomplete")
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
        tag = tag,
        serverName = serverName?.takeIf { it.isNotBlank() },
        channelName = channelName?.takeIf { it.isNotBlank() },
        parentChannelKind =
            parentChannelKind
                ?.let(::parseConversationKindOrNull)
                ?.takeIf { it == ConversationKind.Channel || it == ConversationKind.Joint || it == ConversationKind.DirectMessage },
        senderId = cleanSenderId?.takeIf { senderKind != null }?.let(::MemberId),
        senderKind = senderKind,
        senderName = senderName?.takeIf { it.isNotBlank() },
        messagePreview = messagePreview?.takeIf { it.isNotBlank() },
        mentioned = mentioned
    )
}

private fun memberFrom(
    type: String?,
    id: String?,
    name: String?,
    diagnostics: (String) -> Unit,
): MemberProfile? {
    if (id.isNullOrBlank()) return null
    val label = name?.trim().orEmpty().ifBlank { id }
    val kind =
        type.strictMemberKindOrNull(
            source = "realtime_task_member",
            diagnostics = diagnostics,
            contract = MemberKindWireContract.InternalActor,
        ) ?: return null
    return MemberProfile(
        id = MemberId(id),
        displayName = label,
        handle = label,
        kind = kind,
        avatarLabel = label.toRaftAvatarLabel(),
        presence = Presence.Offline,
    )
}
