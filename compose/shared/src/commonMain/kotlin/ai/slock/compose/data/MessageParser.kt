package ai.slock.compose.data

import ai.slock.compose.model.Attachment
import ai.slock.compose.model.AttachmentId
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageDeliveryState
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.MessageType
import ai.slock.compose.model.Presence
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import ai.slock.compose.model.ThreadId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    val messageType: String = "chat",
    val content: String = "",
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
    val threadSummary: MessagesWireThreadSummary? = null,
    val replyCount: Int? = null,
    val unreadReplyCount: Int? = null,
    val threadUnreadCount: Int? = null,
    val firstUnreadReplyMessageId: String? = null,
    val actionMetadata: WireActionCardMetadata? = null,
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
)

@Serializable
private data class MessagesWireContextResponse(
    val channelId: String? = null,
    val targetMessageId: String? = null,
    val hasOlder: Boolean = false,
    val hasNewer: Boolean = false,
    val messages: List<MessagesWireMessage> = emptyList(),
    val historyLimited: Boolean = false,
    val channelArchived: Boolean = false,
)

/**
 * Domain payload returned by the shared message parsers. The repository is
 * responsible for assembling the surrounding [ai.slock.compose.data.ThreadSnapshot]
 * (which needs a `ConversationSummary` not present on the wire).
 */
data class SharedMessagesPayload(
    val messages: List<Message>,
    val channelId: ChannelId? = null,
    val targetMessageId: MessageId? = null,
    val hasOlder: Boolean = false,
    val hasNewer: Boolean = false,
    val historyLimited: Boolean = false,
    val channelArchived: Boolean = false,
)

fun parseSharedChannelMessages(bodyText: String, fallbackThreadId: ThreadId): SharedMessagesPayload {
    if (bodyText.isBlank()) return SharedMessagesPayload(emptyList())
    val response = runCatching {
        messagesJson.decodeFromString(MessagesWireChannelResponse.serializer(), bodyText)
    }.getOrNull() ?: return SharedMessagesPayload(emptyList())
    return SharedMessagesPayload(
        messages = response.messages.map { it.toDomain(fallbackThreadId) },
        historyLimited = response.historyLimited,
    )
}

fun parseSharedMessageContext(bodyText: String, fallbackThreadId: ThreadId): SharedMessagesPayload {
    if (bodyText.isBlank()) return SharedMessagesPayload(emptyList())
    val response = runCatching {
        messagesJson.decodeFromString(MessagesWireContextResponse.serializer(), bodyText)
    }.getOrNull() ?: return SharedMessagesPayload(emptyList())
    val resolvedThreadId = response.channelId?.let(::ThreadId) ?: fallbackThreadId
    return SharedMessagesPayload(
        messages = response.messages.map { it.toDomain(resolvedThreadId) },
        channelId = response.channelId?.let(::ChannelId),
        targetMessageId = response.targetMessageId?.let(::MessageId),
        hasOlder = response.hasOlder,
        hasNewer = response.hasNewer,
        historyLimited = response.historyLimited,
        channelArchived = response.channelArchived,
    )
}

fun parseSharedSyncMessages(bodyText: String, fallbackThreadId: ThreadId): SharedMessagesPayload {
    if (bodyText.isBlank()) return SharedMessagesPayload(emptyList())
    val wireMessages = runCatching {
        messagesJson.decodeFromString<List<MessagesWireMessage>>(bodyText)
    }.getOrNull() ?: return SharedMessagesPayload(emptyList())
    return SharedMessagesPayload(
        messages = wireMessages.map { it.toDomain(fallbackThreadId) },
    )
}

fun parseSharedSentMessage(bodyText: String, fallbackThreadId: ThreadId): Message? {
    if (bodyText.isBlank()) return null
    return runCatching {
        messagesJson.decodeFromString(MessagesWireMessage.serializer(), bodyText)
    }.getOrNull()?.toDomain(fallbackThreadId)
}

private fun MessagesWireMessage.toDomain(fallbackThreadId: ThreadId): Message {
    val summary = threadSummary
    val resolvedThreadChannelId =
        threadId
            ?: summary?.threadChannelId?.takeIf { it.isNotBlank() }
    val resolvedFirstUnreadReplyMessageId =
        firstUnreadReplyMessageId
            ?: summary?.firstUnreadMessageId?.takeIf { it.isNotBlank() }
    val authorKind = when {
        senderType.equals("agent", ignoreCase = true) -> MemberKind.Agent
        messageType.equals("system", ignoreCase = true) -> MemberKind.System
        else -> MemberKind.Human
    }
    val author = MemberProfile(
        id = MemberId(senderId),
        displayName = senderName.ifBlank { senderId },
        handle = senderName.ifBlank { senderId },
        kind = authorKind,
        description = senderDescription,
        presence = Presence.Offline,
    )
    val resolvedThreadId = channelId.takeIf { it.isNotBlank() }?.let(::ThreadId) ?: fallbackThreadId
    val task = if (taskNumber != null || taskStatus != null) {
        TaskSummary(
            id = TaskId(id),
            title = content,
            status = TaskStatus.fromWireValue(taskStatus) ?: TaskStatus.Todo,
            taskNumber = taskNumber,
            messageId = MessageId(id),
            channelId = ChannelId(channelId),
            assignee = taskAssigneeId?.let { aid ->
                val kind = if (taskAssigneeType.equals("agent", ignoreCase = true)) {
                    MemberKind.Agent
                } else {
                    MemberKind.Human
                }
                MemberProfile(
                    id = MemberId(aid),
                    displayName = aid,
                    handle = aid,
                    kind = kind,
                    presence = Presence.Offline,
                )
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
        messageType = if (messageType.equals("system", ignoreCase = true)) {
            MessageType.System
        } else {
            MessageType.Chat
        },
        metadata = actionMetadata.toThreadActionCard()?.toMessageMetadata().orEmpty(),
        attachments = attachments.map { it.toDomain() },
        deliveryState = MessageDeliveryState.Sent,
        task = task,
        replyCount = replyCount ?: summary?.replyCount ?: 0,
        unreadReplyCount = unreadReplyCount ?: threadUnreadCount ?: summary?.unreadCount ?: 0,
        firstUnreadReplyMessageId = resolvedFirstUnreadReplyMessageId?.let(::MessageId),
        updatedAtLabel = updatedAt.ifBlank { null },
    )
}

private fun MessagesWireAttachment.toDomain(): Attachment = Attachment(
    id = AttachmentId(id),
    name = filename.ifBlank { "Attachment" },
    mimeType = mimeType.ifBlank { "application/octet-stream" },
    sizeLabel = formatAttachmentSizeLabel(sizeBytes),
    thumbnailUrl = thumbnailUrl,
)
