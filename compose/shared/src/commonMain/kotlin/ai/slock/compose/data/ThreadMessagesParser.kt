package ai.slock.compose.data

import ai.slock.compose.KuiklyThreadAttachmentItem
import ai.slock.compose.KuiklyThreadMessageItem
import ai.slock.compose.KuiklyThreadReactionItem
import ai.slock.compose.KuiklyThreadScreenPayload
import ai.slock.compose.SharedThreadComposerAttachment
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Strict, React-aligned parsers for the thread message timeline + attachment
 * upload responses. The wire shapes mirror what `packages/server/src/routes`
 * actually emits:
 *
 *  - `messages.ts` GET `/api/messages`:
 *      `{ messages: Message[], historyLimited: boolean }`
 *  - `messages.ts` POST `/api/messages`:
 *      a single enriched `Message` row (same shape, no wrapper)
 *  - `attachments.ts` POST `/api/uploads`:
 *      `{ attachments: Attachment[] }`
 *
 * The enriched `Message` row is defined by `enrichWithSenderNames` in
 * `packages/server/src/services/messageService.ts` and the React mirror in
 * `packages/web/src/store/messageStore.ts`:
 *
 *   { id, seq?, channelId, senderType: "user"|"agent", senderId, senderName?,
 *     senderMembershipStatus?, messageType?: "chat"|"system", content,
 *     threadId?, createdAt, attachments?: MessageAttachment[],
 *     reactions?: MessageReaction[] }
 *
 * UI-only fields on [KuiklyThreadMessageItem] (`taskId`, `taskNumber`,
 * `taskStatus`, `taskAssigneeName`, `unreadReplyCount`,
 * `firstUnreadReplyMessageId`, `avatarWireValue`, `gravatarHash`,
 * `systemEventType`, `metadata`) are NOT emitted on this route and are left
 * at their defaults here; they must be populated by joining against the
 * dedicated tasks / thread-metadata / member-profile endpoints upstream.
 */

private val threadMessagesJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
private data class WireMessage(
    val id: String,
    val seq: Long? = null,
    val channelId: String? = null,
    val senderType: String = "",
    val senderId: String = "",
    val senderName: String? = null,
    val senderMembershipStatus: String? = null,
    val messageType: String? = null,
    val content: String = "",
    val threadId: String? = null,
    val createdAt: String? = null,
    val attachments: List<WireAttachment> = emptyList(),
    val reactions: List<WireReaction> = emptyList(),
    val replyCount: Int? = null,
)

@Serializable
private data class WireAttachment(
    val id: String,
    val filename: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val thumbnailUrl: String? = null,
    val rasterPreviewUrl: String? = null,
)

@Serializable
private data class WireReaction(
    val emoji: String,
    val count: Int = 0,
    val reactorIds: List<String> = emptyList(),
    val reactorNames: List<String> = emptyList(),
)

@Serializable
private data class WireMessagesResponse(
    val messages: List<WireMessage> = emptyList(),
    val historyLimited: Boolean = false,
)

@Serializable
private data class WireAttachmentsResponse(
    val attachments: List<WireAttachment> = emptyList(),
)

internal fun parseSharedThreadUploadedAttachments(
    response: JSONObject?
): List<SharedThreadComposerAttachment> {
    val bodyText = response?.optString("bodyText").orEmpty()
    if (bodyText.isBlank()) return emptyList()
    return runCatching {
        threadMessagesJson
            .decodeFromString(WireAttachmentsResponse.serializer(), bodyText)
            .attachments
            .mapIndexed { index, att ->
                SharedThreadComposerAttachment(
                    id = att.id,
                    fileName = att.filename.ifBlank { "Attachment ${index + 1}" },
                    sizeLabel = formatThreadAttachmentSize(att.sizeBytes),
                    mimeType = att.mimeType.ifBlank { null },
                    status = "ready"
                )
            }
            .distinctBy { it.id }
    }.getOrElse { emptyList() }
}

internal fun buildSharedThreadPayloadFromMessagesResponse(
    basePayload: KuiklyThreadScreenPayload,
    bodyText: String,
    session: StoredAuthSession?
): KuiklyThreadScreenPayload =
    runCatching {
        // GET /api/messages returns `{messages, historyLimited}`. The sync route
        // returns a bare JSON array of messages. Decode once and normalize.
        val element = threadMessagesJson.parseToJsonElement(bodyText)
        val (wires: List<WireMessage>, historyLimited: Boolean) =
            when {
                element is JsonObject && "messages" in element -> {
                    val decoded =
                        threadMessagesJson
                            .decodeFromJsonElement(WireMessagesResponse.serializer(), element)
                    decoded.messages to decoded.historyLimited
                }
                element is JsonArray ->
                    threadMessagesJson
                        .decodeFromJsonElement(ListSerializer(WireMessage.serializer()), element) to false
                else ->
                    listOf(
                        threadMessagesJson
                            .decodeFromJsonElement(WireMessage.serializer(), element)
                    ) to false
            }
        val currentUserId = session?.user?.id
        val messages = wires.map { it.toMessageItem(currentUserId) }
        basePayload.copy(
            status = if (messages.isEmpty()) "empty" else "content",
            messages = messages,
            canLoadMoreBefore = basePayload.canLoadMoreBefore || historyLimited,
            canLoadMoreAfter = basePayload.canLoadMoreAfter,
            errorCode = null
        )
    }.getOrElse {
        basePayload.copy(status = "error", errorCode = "thread_payload_parse_failed")
    }

internal fun appendSharedThreadSentMessageFromResponse(
    basePayload: KuiklyThreadScreenPayload,
    bodyText: String,
    session: StoredAuthSession?
): KuiklyThreadScreenPayload {
    // POST /api/messages returns a single Message row, the GET returns
    // `{messages:[...]}`. Decode the body once as a JsonElement and normalize
    // both into List<WireMessage> via the appropriate serializer.
    val sent =
        runCatching {
            val element = threadMessagesJson.parseToJsonElement(bodyText)
            val wires: List<WireMessage> =
                when {
                    element is JsonObject && "messages" in element ->
                        threadMessagesJson
                            .decodeFromJsonElement(WireMessagesResponse.serializer(), element)
                            .messages
                    element is JsonArray ->
                        threadMessagesJson
                            .decodeFromJsonElement(ListSerializer(WireMessage.serializer()), element)
                    else ->
                        listOf(
                            threadMessagesJson
                                .decodeFromJsonElement(WireMessage.serializer(), element)
                        )
                }
            val currentUserId = session?.user?.id
            val existing = basePayload.messages.map { it.id }.toSet()
            wires.map { it.toMessageItem(currentUserId) }.filterNot { it.id in existing }
        }.getOrElse { emptyList() }

    return if (sent.isEmpty()) {
        basePayload.copy(errorCode = null)
    } else {
        basePayload.copy(
            status = "content",
            messages = basePayload.messages + sent,
            errorCode = null
        )
    }
}

private fun WireMessage.toMessageItem(currentUserId: String?): KuiklyThreadMessageItem {
    val resolvedName = senderName?.takeIf { it.isNotBlank() } ?: "Unknown"
    val isSystem = messageType.equals("system", ignoreCase = true)
    return KuiklyThreadMessageItem(
        id = id,
        authorId = senderId.ifBlank { "unknown" },
        authorName = resolvedName,
        authorKind = when {
            isSystem -> ""
            senderType.equals("agent", ignoreCase = true) -> "agent"
            senderType.equals("user", ignoreCase = true) -> "human"
            else -> ""
        },
        isOwn = currentUserId != null && senderId == currentUserId,
        avatarLabel = resolvedName.take(1).uppercase(),
        avatarWireValue = null,
        gravatarHash = null,
        timestampLabel = createdAt.orEmpty(),
        seq = seq,
        messageType = if (isSystem) "System" else "Chat",
        systemEventType = null,
        metadata = emptyMap(),
        body = content,
        replyCount = replyCount ?: 0,
        unreadReplyCount = 0,
        firstUnreadReplyMessageId = null,
        taskId = null,
        taskNumber = null,
        taskStatus = null,
        taskAssigneeName = null,
        reactions = reactions.map { it.toReactionItem(currentUserId) },
        attachments = attachments.mapIndexed { i, a -> a.toAttachmentItem(i) }
    )
}

private fun WireReaction.toReactionItem(currentUserId: String?): KuiklyThreadReactionItem =
    KuiklyThreadReactionItem(
        emoji = emoji,
        count = count,
        selected = currentUserId != null && currentUserId in reactorIds
    )

private fun WireAttachment.toAttachmentItem(index: Int): KuiklyThreadAttachmentItem =
    KuiklyThreadAttachmentItem(
        id = id,
        name = filename.ifBlank { "Attachment ${index + 1}" },
        mimeType = mimeType,
        sizeLabel = formatThreadAttachmentSize(sizeBytes),
        thumbnailUrl = thumbnailUrl
    )

private fun formatThreadAttachmentSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIdx = 0
    while (value >= 1024.0 && unitIdx < units.lastIndex) {
        value /= 1024.0
        unitIdx++
    }
    return if (unitIdx == 0) {
        "$bytes B"
    } else {
        val rounded = (value * 10).toInt() / 10.0
        "$rounded ${units[unitIdx]}"
    }
}
