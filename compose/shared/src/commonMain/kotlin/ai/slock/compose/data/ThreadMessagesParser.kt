package ai.slock.compose.data

import ai.slock.compose.KuiklyThreadAttachmentItem
import ai.slock.compose.KuiklyThreadMessageCommentHostSource
import ai.slock.compose.KuiklyThreadMessageCommentRef
import ai.slock.compose.KuiklyThreadMessageItem
import ai.slock.compose.KuiklyThreadReactionItem
import ai.slock.compose.KuiklyThreadScreenPayload
import ai.slock.compose.KuiklyThreadSurfaceKind
import ai.slock.compose.SharedThreadComposerAttachment
import ai.slock.compose.matchesResolvedReplyThreadTimeline
import ai.slock.compose.replyThreadContentChannelId
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
 * Message footer metadata mirrors React's `MessageItem`: task badge fields,
 * reply/new counts, and reactions are parsed here when the message route emits
 * them. Member-profile-only fields (`avatarWireValue`, `gravatarHash`) and
 * system-detail fields (`systemEventType`, `metadata`) are still populated by
 * their dedicated upstream joins.
 */

private val threadMessagesJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
internal data class WireCommentHostSource(
    val type: String = "",
    val routeKind: String = "",
    val channelId: String = "",
    val parentMessageId: String? = null,
    val threadChannelId: String? = null,
    val rootThreadChannelId: String? = null,
)

@Serializable
internal data class WireCommentRef(
    val attachmentId: String = "",
    val filename: String = "",
    val hostMessageId: String? = null,
    val hostSource: WireCommentHostSource? = null,
    val anchorLabel: String? = null,
)

@Serializable
internal data class WireMessage(
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
    val threadSummary: WireThreadSummary? = null,
    val replyCount: Int? = null,
    val unreadReplyCount: Int? = null,
    val threadUnreadCount: Int? = null,
    val firstUnreadReplyMessageId: String? = null,
    val isSaved: Boolean = false,
    val taskStatus: String? = null,
    val taskNumber: Int? = null,
    val taskAssigneeType: String? = null,
    val taskAssigneeId: String? = null,
    val taskAssigneeName: String? = null,
    val claimedByName: String? = null,
    val taskClaimedByName: String? = null,
    val actionMetadata: WireActionCardMetadata? = null,
    val commentRef: WireCommentRef? = null,
)

@Serializable
internal data class WireThreadSummary(
    val threadChannelId: String? = null,
    val replyCount: Int? = null,
    val unreadCount: Int? = null,
    val firstUnreadMessageId: String? = null,
)

@Serializable
internal data class WireAttachment(
    val id: String,
    val filename: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val thumbnailUrl: String? = null,
    val rasterPreviewUrl: String? = null,
    val commentCount: Int = 0,
)

@Serializable
internal data class WireReaction(
    val emoji: String,
    val count: Int = 0,
    val reactorIds: List<String> = emptyList(),
    val reactorNames: List<String> = emptyList(),
)

@Serializable
internal data class WireMessagesResponse(
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

@OptIn(ExperimentalEncodingApi::class)
internal fun parseSharedThreadPickedAttachmentFiles(
    response: JSONObject?
): List<AttachmentUploadFile> {
    val filesJson = response?.optJSONArray("files")
        ?: response?.optString("bodyText").orEmpty()
            .takeIf { it.isNotBlank() }
            ?.let { bodyText -> runCatching { JSONObject(bodyText).optJSONArray("files") }.getOrNull() }
        ?: return emptyList()
    return buildList {
        for (index in 0 until filesJson.length()) {
            val fileJson = filesJson.optJSONObject(index) ?: continue
            val fileName = fileJson.optString("fileName").takeIf { it.isNotBlank() }
                ?: "attachment-${index + 1}"
            val fileRef = fileJson.optString("fileRef").takeIf { it.isNotBlank() }
            val encoded = fileJson.optString("bodyBase64").takeIf { it.isNotBlank() }
            val bytes = encoded?.let { runCatching { Base64.decode(it) }.getOrNull() }
            if (fileRef == null && bytes == null) continue
            add(
                AttachmentUploadFile(
                    id = fileJson.optString("id").takeIf { it.isNotBlank() }
                        ?: "picked-${index + 1}",
                    fileName = fileName,
                    mimeType = fileJson.optString("mimeType").takeIf { it.isNotBlank() },
                    sizeBytes = fileJson.optLong("sizeBytes", bytes?.size?.toLong() ?: 0L).takeIf { it > 0L },
                    previewUri = fileJson.optString("previewUri").takeIf { it.isNotBlank() },
                    bytes = bytes ?: ByteArray(0),
                    fileRef = fileRef
                )
            )
        }
    }
}

internal fun buildSharedThreadPayloadFromMessagesPage(
    basePayload: KuiklyThreadScreenPayload,
    page: ThreadMessagesPage,
    session: StoredAuthSession?
): KuiklyThreadScreenPayload {
    val currentUserId = session?.user?.id
    val messages =
        page.messages
            .filter { it.belongsToBasePayloadThread(basePayload) }
            .map { it.toMessageItem(currentUserId) }
    return basePayload.copy(
        status = if (messages.isEmpty()) "empty" else "content",
        messages = messages,
        canLoadMoreBefore = basePayload.canLoadMoreBefore || (page.canLoadMoreBefore ?: false),
        canLoadMoreAfter = page.canLoadMoreAfter ?: basePayload.canLoadMoreAfter,
        errorCode = null
    )
}

internal fun appendSharedThreadSentMessageFromPage(
    basePayload: KuiklyThreadScreenPayload,
    page: ThreadMessagesPage,
    session: StoredAuthSession?
): KuiklyThreadScreenPayload {
    val sent =
        page.messages
            .filter { it.belongsToBasePayloadThread(basePayload) }
            .map { it.toMessageItem(session?.user?.id) }
            .filterNot { it.id in basePayload.messages.map { message -> message.id }.toSet() }

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
    val summary = threadSummary
    return KuiklyThreadMessageItem(
        id = id,
        authorId = senderId.ifBlank { "unknown" },
        authorName = resolvedName,
        authorKind = when {
            isSystem -> "system"
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
        metadata = actionMetadata.toThreadActionCard()?.toMessageMetadata().orEmpty(),
        body = content,
        threadChannelId = firstNonBlank(threadId, summary?.threadChannelId),
        replyCount = replyCount ?: summary?.replyCount ?: 0,
        unreadReplyCount = unreadReplyCount ?: threadUnreadCount ?: summary?.unreadCount ?: 0,
        firstUnreadReplyMessageId = firstNonBlank(firstUnreadReplyMessageId, summary?.firstUnreadMessageId),
        isSaved = isSaved,
        taskId = if (taskNumber != null || taskStatus != null) id else null,
        taskNumber = taskNumber,
        taskStatus = taskStatus,
        taskAssigneeId = taskAssigneeId?.trim()?.takeIf { it.isNotBlank() },
        taskAssigneeName = firstNonBlank(taskAssigneeName, claimedByName, taskClaimedByName),
        reactions = reactions.map { it.toReactionItem(currentUserId) }.filter { it.count > 0 },
        attachments = attachments.mapIndexed { i, a -> a.toAttachmentItem(i) },
        commentRef = commentRef?.toMessageCommentRef()
    )
}

private fun WireMessage.belongsToBasePayloadThread(basePayload: KuiklyThreadScreenPayload): Boolean {
    if (basePayload.surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) return true
    val selectedThreadId = basePayload.replyThreadContentChannelId() ?: return true
    return matchesResolvedReplyThreadTimeline(
        resolvedThreadChannelId = selectedThreadId,
        messageChannelId = channelId,
        messageThreadId = threadId
    )
}

private fun firstNonBlank(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }?.trim()

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
        thumbnailUrl = thumbnailUrl,
        commentCount = commentCount.coerceAtLeast(0)
    )

private fun WireCommentRef.toMessageCommentRef(): KuiklyThreadMessageCommentRef? {
    val cleanAttachmentId = attachmentId.takeIf { it.isNotBlank() } ?: return null
    val cleanFilename = filename.takeIf { it.isNotBlank() } ?: return null
    return KuiklyThreadMessageCommentRef(
        attachmentId = cleanAttachmentId,
        filename = cleanFilename,
        hostMessageId = hostMessageId?.takeIf { it.isNotBlank() },
        hostSource = hostSource?.toMessageCommentHostSource(),
        anchorLabel = anchorLabel?.takeIf { it.isNotBlank() }
    )
}

private fun WireCommentHostSource.toMessageCommentHostSource(): KuiklyThreadMessageCommentHostSource? {
    val cleanType = type.takeIf { it == "channel" || it == "thread" } ?: return null
    val cleanRouteKind = routeKind.takeIf { it == "channel" || it == "dm" } ?: return null
    val cleanChannelId = channelId.takeIf { it.isNotBlank() } ?: return null
    return KuiklyThreadMessageCommentHostSource(
        type = cleanType,
        routeKind = cleanRouteKind,
        channelId = cleanChannelId,
        parentMessageId = parentMessageId?.takeIf { it.isNotBlank() },
        threadChannelId = threadChannelId?.takeIf { it.isNotBlank() },
        rootThreadChannelId = rootThreadChannelId?.takeIf { it.isNotBlank() }
    )
}

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
