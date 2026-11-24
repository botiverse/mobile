package build.raft.app.data

import build.raft.app.SharedThreadComposerAttachment
import build.raft.app.optJsonArray
import build.raft.app.optJsonObject
import build.raft.app.optLong
import build.raft.app.optString
import build.raft.app.parseRaftJsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Pure wire shapes and attachment/file response parsers for thread messages (`GET/POST /api/messages`,
 * `POST /api/uploads`). Kept separate from [ThreadMessagesParser] UI-item projections so `:shared-data`
 * (`ThreadApi`, `MessageParser`, `BridgeAuth`) never depends on thread UI models.
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
    val senderDescription: String? = null,
    val senderMembershipStatus: String? = null,
    val messageType: String? = null,
    val content: String = "",
    val randomId: String? = null,
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
    val forwardBundle: WireForwardMessageBundle? = null,
    /** Slack-bridge external sender attribution (present iff senderType=external_projection). */
    @Serializable(with = LenientWireExternalAuthorSerializer::class)
    val externalAuthor: WireExternalAuthor? = null,
    /** RFC 077 `DelegatedByLabel | null`: the assistant that sent it for the sender. */
    val delegatedBy: WireDelegatedBy? = null,
    /**
     * RFC 077 PR 2b: set when the owner retracted the message; `content` is then the placeholder.
     * The server spreads the full `messages` row (`messageService.ts` `...visibleRow`), ISO or null.
     */
    val deletedAt: String? = null,
    /** Resolved mention facts; see [WireMessageMention]. */
    val mentions: List<WireMessageMention> = emptyList(),
    /**
     * Quote replies verified for this viewer (`ResolvedInlineQuotesMap`, [decodeResolvedInlineQuotes]):
     * on the pages, the sync page and the sender's own send this client reads with this row.
     */
    val resolvedQuotes: JsonElement? = null,
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
    response: JsonObject?
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
                    sizeLabel = "",
                    sizeBytes = att.sizeBytes,
                    mimeType = att.mimeType.ifBlank { null },
                    status = "ready"
                )
            }
            .distinctBy { it.id }
    }.getOrElse { emptyList() }
}

@OptIn(ExperimentalEncodingApi::class)
internal fun parseSharedThreadPickedPlatformFiles(
    response: JsonObject?
): List<PlatformFile> {
    val filesJson = response?.optJsonArray("files")
        ?: response?.optString("bodyText").orEmpty()
            .takeIf { it.isNotBlank() }
            ?.let { bodyText -> runCatching { parseRaftJsonObject(bodyText).optJsonArray("files") }.getOrNull() }
        ?: return emptyList()
    return buildList {
        for (index in 0 until filesJson.size) {
            val fileJson = filesJson.optJsonObject(index) ?: continue
            val fileName = fileJson.optString("fileName").takeIf { it.isNotBlank() }
                ?: "attachment-${index + 1}"
            val fileRef = fileJson.optString("fileRef").takeIf { it.isNotBlank() }
            val encoded = fileJson.optString("bodyBase64").takeIf { it.isNotBlank() }
            val bytes = encoded?.let { runCatching { Base64.decode(it) }.getOrNull() }
            if (fileRef == null && bytes == null) continue
            add(
                PlatformFile(
                    id = fileJson.optString("id").takeIf { it.isNotBlank() }
                        ?: "picked-${index + 1}",
                    fileName = fileName,
                    mimeType = fileJson.optString("mimeType").takeIf { it.isNotBlank() },
                    sizeBytes = fileJson.optLong("sizeBytes", bytes?.size?.toLong() ?: 0L).takeIf { it > 0L },
                    previewUri = fileJson.optString("previewUri").takeIf { it.isNotBlank() },
                    bytes = bytes ?: ByteArray(0),
                    fileRef = fileRef,
                    source = fileJson.optString("source").takeIf { it.isNotBlank() } ?: "platform_picker"
                )
            )
        }
    }
}
