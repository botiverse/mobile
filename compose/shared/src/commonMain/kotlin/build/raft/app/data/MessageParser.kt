package build.raft.app.data

import build.raft.app.MemberKindWireContract
import build.raft.app.strictMemberKindOrNull
import build.raft.app.strictMessageTypeOrNull
import build.raft.app.strictProjectionDiagnosticsLogger
import build.raft.app.strictTaskStatusOrNull
import build.raft.app.commentHostTypeOrNull
import build.raft.app.commentHostRouteKindOrNull
import build.raft.app.model.Attachment
import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageCommentHostSource
import build.raft.app.model.MessageCommentRef
import build.raft.app.model.MessageDeliveryState
import build.raft.app.model.MessageId
import build.raft.app.model.MessageReaction
import build.raft.app.model.MessageType
import build.raft.app.model.Presence
import build.raft.app.model.TaskId
import build.raft.app.model.TaskSummary
import build.raft.app.model.ThreadId
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.network.sync.ProductionFace
import build.raft.app.network.sync.decodeCanonicalMessageWire
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Strict parser for the `/api/messages` endpoints.
 *
 * Canonical wire contracts (see `packages/server/src/routes/messages.ts` and
 * `services/messageService.ts`):
 *
 * - `GET /api/messages/channel/:channelId`
 *     → `{ messages: WireMessage[], historyLimited: boolean }`
 *
 * - `GET /api/messages/context/:messageId`
 *     → `{ channelId, targetMessageId, hasOlder, hasNewer,
 *          messages: WireMessage[], historyLimited, channelArchived }`
 *
 * - `GET /api/messages/sync` → `WireMessage[]` (bare array)
 *
 * - `POST /api/messages` → `WireMessage` (the enriched outgoing message)
 *
 * Every field name matches the upstream TypeScript source verbatim — no
 * snake_case, no nested envelopes, no field aliases. Drift fails fast at
 * decode time.
 */

private val messagesJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
private data class MessagesWireAttachment(
    val id: String,
    val filename: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val thumbnailUrl: String? = null,
    val commentCount: Int = 0,
)

@Serializable
private data class MessagesWireCommentHostSource(
    val type: String = "",
    val routeKind: String = "",
    val channelId: String = "",
    val parentMessageId: String? = null,
    val threadChannelId: String? = null,
    val rootThreadChannelId: String? = null,
)

@Serializable
private data class MessagesWireCommentRef(
    val attachmentId: String = "",
    val filename: String = "",
    val hostMessageId: String? = null,
    val hostSource: MessagesWireCommentHostSource? = null,
    val anchorLabel: String? = null,
)

@Serializable
private data class MessagesWireMessage(
    val id: String,
    val seq: Long? = null,
    val channelId: String,
    val senderType: String,
    val senderId: String,
    val senderName: String = "",
    val senderDescription: String? = null,
    val senderMembershipStatus: String? = null,
    val messageType: String? = null,
    val content: String = "",
    val randomId: String? = null,
    /** Anchor marker on a thread PARENT message — points to the thread channel
     *  where replies live. `null` on ordinary messages and on thread replies. */
    val threadId: String? = null,
    val taskStatus: String? = null,
    val taskNumber: Int? = null,
    val taskAssigneeType: String? = null,
    val taskAssigneeId: String? = null,
    val taskClaimedAt: String? = null,
    val taskCompletedAt: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val attachments: List<MessagesWireAttachment> = emptyList(),
    val reactions: List<MessagesWireReaction> = emptyList(),
    val threadSummary: MessagesWireThreadSummary? = null,
    val replyCount: Int? = null,
    val unreadReplyCount: Int? = null,
    val threadUnreadCount: Int? = null,
    val firstUnreadReplyMessageId: String? = null,
    val actionMetadata: WireActionCardMetadata? = null,
    val commentRef: MessagesWireCommentRef? = null,
    val forwardBundle: WireForwardMessageBundle? = null,
)

@Serializable
private data class MessagesWireReaction(
    val emoji: String,
    val count: Int = 0,
    val reactorIds: List<String> = emptyList(),
    val reactorNames: List<String> = emptyList(),
)

@Serializable
private data class MessagesWireThreadSummary(
    val threadChannelId: String? = null,
    val replyCount: Int? = null,
    val unreadCount: Int? = null,
    val firstUnreadMessageId: String? = null,
)

@Serializable
private data class MessagesWireChannelResponse(
    val messages: List<MessagesWireMessage> = emptyList(),
    val historyLimited: Boolean = false,
    val messageWindow: MessagesWireMessageWindow? = null,
)

@Serializable
private data class MessagesWireContextResponse(
    val channelId: String? = null,
    val targetMessageId: String? = null,
    val canonicalTarget: MessagesWireCanonicalTarget? = null,
    val hasOlder: Boolean = false,
    val hasNewer: Boolean = false,
    val messages: List<MessagesWireMessage> = emptyList(),
    val historyLimited: Boolean = false,
    val channelArchived: Boolean = false,
    val messageWindow: MessagesWireMessageWindow? = null,
)

@Serializable
private data class MessagesWireMessageWindow(
    val schemaVersion: Int? = null,
    val domain: String? = null,
    val serverId: String? = null,
    val receiverKind: String? = null,
    val receiverId: String? = null,
    val scopeId: String? = null,
    val coveredAfterSeq: Long? = null,
    val coveredFromSeq: Long? = null,
    val coveredThroughSeq: Long? = null,
    val remoteHighWaterSeq: Long? = null,
    val hasGap: Boolean? = null,
    val hasNewer: Boolean? = null,
    val completeThroughLatest: Boolean? = null,
)

@Serializable
private data class MessagesWireCanonicalTarget(
    val kind: String? = null,
    val channelId: String? = null,
    val messageId: String? = null,
    val threadParentMessageId: String? = null,
    val threadChannelId: String? = null,
)

@Serializable
private data class MessagesWireSendResponse(
    val message: MessagesWireMessage? = null,
    val pendingMentionActions: List<MessagesWirePendingMentionAction> = emptyList()
)

@Serializable
private data class MessagesWirePendingMentionAction(
    val resolutionId: String? = null,
    val id: String? = null,
    val messageId: String? = null,
    val channelId: String? = null,
    val channelName: String? = null,
    val targetType: String? = null,
    val targetHandle: String? = null,
    val targetAvatarUrl: String? = null,
    val reason: String? = null,
    val availableActions: List<String> = emptyList(),
    val expiresAt: String? = null
)

@Serializable
private data class MessagesWireMentionActionExecuteResponse(
    val results: List<MessagesWireMentionActionExecuteResult> = emptyList()
)

@Serializable
private data class MessagesWireMentionActionExecuteResult(
    val resolutionId: String,
    val status: String,
    val reason: String? = null
)

/**
 * Domain payload returned by the shared message parsers. The repository is
 * responsible for assembling the surrounding [build.raft.app.data.ThreadSnapshot]
 * (which needs a `ConversationSummary` not present on the wire).
 */
data class SharedMessagesPayload(
    val messages: List<Message>,
    val canonicalRows: List<CanonicalMessageRow> = emptyList(),
    val canonicalRowsComplete: Boolean = false,
    val messageWindow: SharedMessageWindowCoverage? = null,
    val channelId: ChannelId? = null,
    val targetMessageId: MessageId? = null,
    val canonicalTarget: SharedMessagesCanonicalTarget? = null,
    val hasOlder: Boolean = false,
    val hasNewer: Boolean = false,
    val historyLimited: Boolean = false,
    val channelArchived: Boolean = false,
)

data class SharedMessageWindowCoverage(
    val schemaVersion: Int,
    val domain: String,
    val serverId: String,
    val receiverKind: String,
    val receiverId: String,
    val scopeId: String,
    val coveredAfterSeq: Long,
    val coveredFromSeq: Long,
    val coveredThroughSeq: Long,
    val remoteHighWaterSeq: Long,
    val hasGap: Boolean,
    val hasNewer: Boolean,
    val completeThroughLatest: Boolean,
)

private fun MessagesWireMessageWindow.toDomainOrNull(): SharedMessageWindowCoverage? {
    val version = schemaVersion ?: return null
    val resolvedDomain = domain?.takeIf { it.isNotBlank() } ?: return null
    val resolvedServerId = serverId?.takeIf { it.isNotBlank() } ?: return null
    val resolvedReceiverKind = receiverKind?.takeIf { it.isNotBlank() } ?: return null
    val resolvedReceiverId = receiverId?.takeIf { it.isNotBlank() } ?: return null
    val resolvedScopeId = scopeId?.takeIf { it.isNotBlank() } ?: return null
    val resolvedCoveredAfterSeq = coveredAfterSeq ?: return null
    val resolvedCoveredFromSeq = coveredFromSeq ?: return null
    val resolvedCoveredThroughSeq = coveredThroughSeq ?: return null
    val resolvedRemoteHighWaterSeq = remoteHighWaterSeq ?: return null
    val resolvedHasGap = hasGap ?: return null
    val resolvedHasNewer = hasNewer ?: return null
    val resolvedCompleteThroughLatest = completeThroughLatest ?: return null
    val validBounds =
        resolvedCoveredAfterSeq >= 0L &&
            resolvedCoveredFromSeq >= 0L &&
            resolvedCoveredThroughSeq >= 0L &&
            resolvedRemoteHighWaterSeq >= 0L &&
            resolvedCoveredAfterSeq < resolvedCoveredFromSeq &&
            resolvedCoveredThroughSeq <= resolvedRemoteHighWaterSeq &&
            (
                resolvedCoveredFromSeq <= resolvedCoveredThroughSeq ||
                    resolvedCoveredFromSeq == resolvedCoveredThroughSeq + 1L
            )
    if (version != 1 || resolvedDomain != "receiver_visible_messages_v1" || !validBounds) return null
    return SharedMessageWindowCoverage(
        schemaVersion = version,
        domain = resolvedDomain,
        serverId = resolvedServerId,
        receiverKind = resolvedReceiverKind,
        receiverId = resolvedReceiverId,
        scopeId = resolvedScopeId,
        coveredAfterSeq = resolvedCoveredAfterSeq,
        coveredFromSeq = resolvedCoveredFromSeq,
        coveredThroughSeq = resolvedCoveredThroughSeq,
        remoteHighWaterSeq = resolvedRemoteHighWaterSeq,
        hasGap = resolvedHasGap,
        hasNewer = resolvedHasNewer,
        completeThroughLatest = resolvedCompleteThroughLatest,
    )
}

private data class CanonicalRowsDecode(
    val rows: List<CanonicalMessageRow>,
    val complete: Boolean,
)

private fun decodeCanonicalRows(elements: JsonArray): CanonicalRowsDecode {
    val rows = ArrayList<CanonicalMessageRow>(elements.size)
    for (element in elements) {
        val raw = element as? JsonObject ?: return CanonicalRowsDecode(emptyList(), complete = false)
        val decoded = decodeCanonicalMessageWire(raw, ProductionFace.MessageNew)
            ?: return CanonicalRowsDecode(emptyList(), complete = false)
        rows += decoded.row
    }
    return CanonicalRowsDecode(rows, complete = true)
}

private fun parseRawMessages(bodyText: String, objectEnvelope: Boolean): CanonicalRowsDecode =
    runCatching {
        val root = messagesJson.parseToJsonElement(bodyText)
        val elements = if (objectEnvelope) root.jsonObject["messages"]?.jsonArray else root.jsonArray
        elements?.let(::decodeCanonicalRows) ?: CanonicalRowsDecode(emptyList(), complete = false)
    }.getOrElse { CanonicalRowsDecode(emptyList(), complete = false) }

data class SharedMessagesCanonicalTarget(
    val kind: String,
    val channelId: ChannelId?,
    val messageId: MessageId?,
    val threadParentMessageId: MessageId?,
    val threadChannelId: ChannelId?,
)

data class SharedSentMessagePayload(
    val message: Message?,
    val pendingMentionActions: List<PendingMentionAction> = emptyList(),
    val projectionRejected: Boolean = false,
)

fun parseSharedChannelMessages(
    bodyText: String,
    fallbackThreadId: ThreadId,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): SharedMessagesPayload {
    if (bodyText.isBlank()) return SharedMessagesPayload(emptyList())
    val response = runCatching {
        messagesJson.decodeFromString(MessagesWireChannelResponse.serializer(), bodyText)
    }.getOrNull() ?: return SharedMessagesPayload(emptyList())
    val canonical = parseRawMessages(bodyText, objectEnvelope = true)
    return SharedMessagesPayload(
        messages = response.messages.mapNotNull { it.toDomain(fallbackThreadId, diagnostics) },
        canonicalRows = canonical.rows,
        canonicalRowsComplete = canonical.complete,
        messageWindow = response.messageWindow?.toDomainOrNull(),
        historyLimited = response.historyLimited,
    )
}

fun parseSharedMessageContext(
    bodyText: String,
    fallbackThreadId: ThreadId,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): SharedMessagesPayload {
    if (bodyText.isBlank()) return SharedMessagesPayload(emptyList())
    val response = runCatching {
        messagesJson.decodeFromString(MessagesWireContextResponse.serializer(), bodyText)
    }.getOrNull() ?: return SharedMessagesPayload(emptyList())
    val canonical = parseRawMessages(bodyText, objectEnvelope = true)
    val resolvedThreadId = response.channelId?.let(::ThreadId) ?: fallbackThreadId
    return SharedMessagesPayload(
        messages = response.messages.mapNotNull { it.toDomain(resolvedThreadId, diagnostics) },
        canonicalRows = canonical.rows,
        canonicalRowsComplete = canonical.complete,
        messageWindow = response.messageWindow?.toDomainOrNull(),
        channelId = response.channelId?.let(::ChannelId),
        targetMessageId = response.targetMessageId?.let(::MessageId),
        canonicalTarget = response.canonicalTarget?.toDomain(),
        hasOlder = response.hasOlder,
        hasNewer = response.hasNewer,
        historyLimited = response.historyLimited,
        channelArchived = response.channelArchived,
    )
}

private fun MessagesWireCanonicalTarget.toDomain(): SharedMessagesCanonicalTarget? {
    val resolvedKind = kind?.takeIf { it.isNotBlank() } ?: return null
    return SharedMessagesCanonicalTarget(
        kind = resolvedKind,
        channelId = channelId?.takeIf { it.isNotBlank() }?.let(::ChannelId),
        messageId = messageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        threadParentMessageId = threadParentMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        threadChannelId = threadChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId),
    )
}

fun parseSharedSyncMessages(
    bodyText: String,
    fallbackThreadId: ThreadId,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): SharedMessagesPayload {
    if (bodyText.isBlank()) return SharedMessagesPayload(emptyList())
    val wireMessages = runCatching {
        messagesJson.decodeFromString<List<MessagesWireMessage>>(bodyText)
    }.getOrNull() ?: return SharedMessagesPayload(emptyList())
    val canonical = parseRawMessages(bodyText, objectEnvelope = false)
    return SharedMessagesPayload(
        messages = wireMessages.mapNotNull { it.toDomain(fallbackThreadId, diagnostics) },
        canonicalRows = canonical.rows,
        canonicalRowsComplete = canonical.complete,
    )
}

fun parseSharedSentMessage(
    bodyText: String,
    fallbackThreadId: ThreadId,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): Message? {
    if (bodyText.isBlank()) return null
    return runCatching {
        messagesJson.decodeFromString(MessagesWireMessage.serializer(), bodyText)
    }.getOrNull()?.toDomain(fallbackThreadId, diagnostics)
}

fun parseSharedSentMessagePayload(
    bodyText: String,
    fallbackThreadId: ThreadId,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): SharedSentMessagePayload {
    if (bodyText.isBlank()) return SharedSentMessagePayload(message = null)
    val rawMessage = runCatching {
        messagesJson.decodeFromString(MessagesWireMessage.serializer(), bodyText)
    }.getOrNull()
    if (rawMessage != null) {
        val message = rawMessage.toDomain(fallbackThreadId, diagnostics)
        return SharedSentMessagePayload(
            message = message,
            projectionRejected = message == null,
        )
    }
    val wrapped = runCatching {
        messagesJson.decodeFromString(MessagesWireSendResponse.serializer(), bodyText)
    }.getOrNull()
    val wrappedMessage = wrapped?.message
    val message = wrappedMessage?.toDomain(fallbackThreadId, diagnostics)
    return SharedSentMessagePayload(
        message = message,
        pendingMentionActions = wrapped?.pendingMentionActions.orEmpty().mapNotNull { it.toDomain() },
        projectionRejected = wrappedMessage != null && message == null,
    )
}

fun parsePendingMentionActionExecuteResults(bodyText: String): List<PendingMentionActionExecuteResult> {
    if (bodyText.isBlank()) return emptyList()
    return runCatching {
        messagesJson.decodeFromString(MessagesWireMentionActionExecuteResponse.serializer(), bodyText)
            .results
            .map {
                PendingMentionActionExecuteResult(
                    resolutionId = it.resolutionId,
                    status = it.status,
                    reason = it.reason
                )
            }
    }.getOrDefault(emptyList())
}

private fun MessagesWireMessage.toDomain(
    fallbackThreadId: ThreadId,
    diagnostics: (String) -> Unit,
): Message? {
    val summary = threadSummary
    val resolvedThreadChannelId =
        threadId
            ?: summary?.threadChannelId?.takeIf { it.isNotBlank() }
    val resolvedFirstUnreadReplyMessageId =
        firstUnreadReplyMessageId
            ?: summary?.firstUnreadMessageId?.takeIf { it.isNotBlank() }
    val resolvedMessageType = messageType.strictMessageTypeOrNull("rest_message_type", diagnostics) ?: return null
    val senderKind =
        senderType.strictMemberKindOrNull(
            source = "rest_message_sender",
            diagnostics = diagnostics,
            contract = MemberKindWireContract.InternalActor,
        ) ?: return null
    val authorKind = if (resolvedMessageType == MessageType.System) MemberKind.System else senderKind
    val author = MemberProfile(
        id = MemberId(senderId),
        displayName = senderName.ifBlank { senderId },
        handle = senderName.ifBlank { senderId },
        kind = authorKind,
        description = senderDescription,
        presence = Presence.Offline,
    )
    val resolvedThreadId = channelId.takeIf { it.isNotBlank() }?.let(::ThreadId) ?: fallbackThreadId
    val hasTaskProjection = taskNumber != null || taskStatus != null
    val resolvedTaskStatus =
        if (hasTaskProjection) taskStatus.strictTaskStatusOrNull("rest_message_task", diagnostics) else null
    val task = if (hasTaskProjection && resolvedTaskStatus != null) {
        TaskSummary(
            id = TaskId(id),
            title = content,
            status = resolvedTaskStatus,
            taskNumber = taskNumber,
            messageId = MessageId(id),
            channelId = ChannelId(channelId),
            assignee = taskAssigneeId?.let { aid ->
                val kind =
                    taskAssigneeType.strictMemberKindOrNull(
                        source = "rest_message_task_assignee",
                        diagnostics = diagnostics,
                        contract = MemberKindWireContract.InternalActor,
                    )
                kind?.let {
                    MemberProfile(
                        id = MemberId(aid),
                        displayName = aid,
                        handle = aid,
                        kind = it,
                        presence = Presence.Offline,
                    )
                }
            },
            updatedAtLabel = updatedAt,
        )
    } else {
        null
    }
    return Message(
        id = MessageId(id),
        threadId = resolvedThreadId,
        channelId = ChannelId(channelId),
        threadChannelId = resolvedThreadChannelId?.let(::ChannelId),
        seq = seq,
        author = author,
        timestampLabel = createdAt,
        body = content,
        messageType = resolvedMessageType,
        metadata = actionMetadata.toThreadActionCard()?.toMessageMetadata().orEmpty(),
        attachments = attachments.map { it.toDomain() },
        randomId = randomId?.takeIf { it.isNotBlank() },
        reactions = reactions.mapNotNull { it.toDomain() }.takeIf { it.isNotEmpty() },
        senderMembershipStatus = senderMembershipStatus,
        forwardBundle = forwardBundle.toForwardMessageBundle() ?: actionMetadata.toForwardMessageBundle(),
        deliveryState = MessageDeliveryState.Sent,
        task = task,
        replyCount = replyCount ?: summary?.replyCount ?: 0,
        unreadReplyCount = unreadReplyCount ?: threadUnreadCount ?: summary?.unreadCount ?: 0,
        firstUnreadReplyMessageId = resolvedFirstUnreadReplyMessageId?.let(::MessageId),
        updatedAtLabel = updatedAt.ifBlank { null },
        commentRef = commentRef?.toDomain(),
    )
}

private fun MessagesWirePendingMentionAction.toDomain(): PendingMentionAction? {
    val resolvedId = resolutionId?.takeIf { it.isNotBlank() } ?: id?.takeIf { it.isNotBlank() } ?: return null
    return PendingMentionAction(
        resolutionId = resolvedId,
        messageId = messageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        channelId = channelId?.takeIf { it.isNotBlank() }?.let(::ChannelId),
        channelName = channelName?.takeIf { it.isNotBlank() },
        targetType = targetType?.takeIf { it.isNotBlank() } ?: "unknown",
        targetHandle = targetHandle.orEmpty(),
        targetAvatarUrl = targetAvatarUrl.normalizedPendingMentionAvatarUrl(),
        reason = reason.orEmpty(),
        availableActions = availableActions.map { it.trim() }.filter { it.isNotBlank() },
        expiresAt = expiresAt?.takeIf { it.isNotBlank() }
    )
}

private fun MessagesWireAttachment.toDomain(): Attachment = Attachment(
    id = AttachmentId(id),
    name = filename.ifBlank { "Attachment" },
    mimeType = mimeType.ifBlank { "application/octet-stream" },
    sizeBytes = sizeBytes,
    sizeLabel = "",
    width = width,
    height = height,
    thumbnailUrl = thumbnailUrl,
    commentCount = commentCount.coerceAtLeast(0),
)

private fun MessagesWireCommentRef.toDomain(): MessageCommentRef? {
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

private fun MessagesWireCommentHostSource.toDomain(): MessageCommentHostSource? {
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

private fun MessagesWireReaction.toDomain(): MessageReaction? {
    val resolvedEmoji = emoji.takeIf { it.isNotBlank() } ?: return null
    return MessageReaction(
        emoji = resolvedEmoji,
        count = count,
        reactorIds = reactorIds,
        reactorNames = reactorNames,
    )
}
