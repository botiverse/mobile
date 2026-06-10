package ai.slock.compose.data

import ai.slock.compose.model.Attachment
import ai.slock.compose.model.AttachmentId
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MessageId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Strict canonical parsers for attachment-shaped server payloads.
 *
 * Canonical contracts (camelCase, fail-fast on contract drift):
 *
 *  - POST `/api/messages/channels/:id/attachments`
 *      → `{attachments: WireAttachmentDto[]}`
 *
 *  - GET  `/api/channels/:id/files`
 *      → `{files: WireChannelFileDto[], nextCursor: string | null}`
 *
 *  WireAttachmentDto ::= `{id, filename, mimeType, sizeBytes, width?, height?,
 *                      thumbnailUrl?, rasterPreviewUrl?}`
 *  WireChannelFileDto ::= WireAttachmentDto & `{createdAt?, messageId?,
 *                      source?: {type?, parentMessageId?, parentMessageShortId?}}`
 *  (Some channel-file responses embed the attachment under an `attachment`
 *  object — both shapes are accepted via [WireChannelFileDto.toChannelFileItem].)
 */

private val attachmentsJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
private data class WireAttachmentDto(
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
private data class WireAttachmentUploadResponseDto(
    val attachments: List<WireAttachmentDto> = emptyList(),
)

@Serializable
private data class WireChannelFileSourceDto(
    val type: String? = null,
    val parentMessageId: String? = null,
    val parentMessageShortId: String? = null,
)

@Serializable
private data class WireChannelFileUploaderDto(
    val type: String? = null,
    val id: String? = null,
    val name: String? = null,
    val displayName: String? = null,
)

@Serializable
private data class WireChannelFileDto(
    val id: String? = null,
    val channelId: String? = null,
    val filename: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val thumbnailUrl: String? = null,
    val createdAt: String? = null,
    val messageId: String? = null,
    val uploader: WireChannelFileUploaderDto? = null,
    val source: WireChannelFileSourceDto? = null,
    val attachment: WireAttachmentDto? = null,
)

@Serializable
private data class WireChannelFilesResponseDto(
    val files: List<WireChannelFileDto> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
private data class WireAttachmentOpenTargetDto(
    val url: String,
    val expiresAt: String? = null,
)

fun parseSharedAttachmentUploadResult(bodyText: String): AttachmentUploadResult {
    if (bodyText.isBlank()) return AttachmentUploadResult(emptyList())
    return runCatching {
        attachmentsJson
            .decodeFromString(WireAttachmentUploadResponseDto.serializer(), bodyText)
            .attachments
            .mapIndexed { index, att -> att.toDomain(index) }
    }.getOrElse { emptyList() }
        .let { AttachmentUploadResult(it) }
}

fun parseSharedAttachmentOpenTarget(
    bodyText: String,
    attachmentId: AttachmentId,
): AttachmentOpenTarget? {
    if (bodyText.isBlank()) return null
    return runCatching {
        attachmentsJson.decodeFromString(WireAttachmentOpenTargetDto.serializer(), bodyText)
    }.getOrNull()?.let { wire ->
        AttachmentOpenTarget(
            attachmentId = attachmentId,
            url = wire.url,
            expiresAt = wire.expiresAt,
        )
    }
}

fun parseSharedChannelFilesSnapshot(
    bodyText: String,
    channelId: ChannelId,
): ChannelFilesSnapshot {
    if (bodyText.isBlank()) {
        return ChannelFilesSnapshot(channelId = channelId, items = emptyList())
    }
    val response = runCatching {
        attachmentsJson.decodeFromString(WireChannelFilesResponseDto.serializer(), bodyText)
    }.getOrNull() ?: return ChannelFilesSnapshot(channelId = channelId, items = emptyList())
    val items = response.files.mapIndexedNotNull { index, file -> file.toChannelFileItem(index) }
    val nextCursor = response.nextCursor
    return ChannelFilesSnapshot(
        channelId = channelId,
        items = items,
        hasMore = nextCursor != null,
        nextCursor = nextCursor,
    )
}

private fun WireAttachmentDto.toDomain(index: Int): Attachment =
    Attachment(
        id = AttachmentId(id),
        name = filename.ifBlank { "Attachment ${index + 1}" },
        mimeType = mimeType.ifBlank { "application/octet-stream" },
        sizeLabel = formatAttachmentSizeLabel(sizeBytes),
        thumbnailUrl = thumbnailUrl,
        width = width,
        height = height,
    )

private fun WireChannelFileDto.toChannelFileItem(index: Int): ChannelFileItem? {
    val attachmentWire = attachment ?: id?.let {
        WireAttachmentDto(
            id = it,
            filename = filename,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            width = width,
            height = height,
            thumbnailUrl = thumbnailUrl,
        )
    } ?: return null
    return ChannelFileItem(
        attachment = attachmentWire.toDomain(index),
        createdAt = createdAt,
        messageId = messageId?.let(::MessageId),
        channelId = channelId?.let(::ChannelId),
        uploaderType = uploader?.type,
        uploaderId = uploader?.id,
        uploaderName = uploader?.name,
        uploaderDisplayName = uploader?.displayName,
        sourceType = source?.type,
        sourceParentMessageId = source?.parentMessageId?.let(::MessageId),
        sourceParentMessageShortId = source?.parentMessageShortId,
    )
}
