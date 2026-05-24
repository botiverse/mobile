package ai.slock.compose.data

import ai.slock.compose.model.AttachmentId
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MessageId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val filesMessagesApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

/**
 * Typed files/search/message-adjacent boundary.
 *
 * Server contracts:
 *  - `packages/server/src/routes/files.ts` (channel files, attachment URL,
 *    attachment preview).
 *  - `packages/server/src/routes/messages.ts` (search, context, reactions).
 *
 * Field names are exact camelCase, no snake_case or speculative aliases.
 */
internal interface SlockFilesMessagesApi {
    suspend fun channelFiles(channelId: ChannelId, limit: Int = 50, cursor: String? = null): HomeNetworkResponse
    suspend fun attachmentUrl(attachmentId: AttachmentId, disposition: String? = null): HomeNetworkResponse
    suspend fun attachmentPreview(attachmentId: AttachmentId): HomeNetworkResponse
    suspend fun attachmentHtmlPreviewUrl(attachmentId: AttachmentId): HomeNetworkResponse
    suspend fun messageSearch(query: String, limit: Int = 20, offset: Int = 0, sort: String? = null): HomeNetworkResponse
    suspend fun messageContext(messageId: MessageId, channelId: ChannelId? = null): HomeNetworkResponse
    suspend fun addReaction(messageId: MessageId, emoji: String): HomeNetworkResponse
    suspend fun removeReaction(messageId: MessageId, emoji: String): HomeNetworkResponse
}

internal class SlockFilesMessagesApiClient(
    private val transport: SlockFilesMessagesApi
) {
    suspend fun channelFiles(channelId: ChannelId, limit: Int = 50, cursor: String? = null): DataResult<FilesPage> =
        transport.channelFiles(channelId, limit, cursor).mapFilesBody(
            "channel_files_contract_mismatch",
            ChannelFilesResponseDto.serializer()
        ) { wire -> FilesPage(itemCount = wire.files.size, nextCursor = wire.nextCursor) }

    suspend fun attachmentUrl(attachmentId: AttachmentId, disposition: String? = null): DataResult<AttachmentUrlTarget> =
        transport.attachmentUrl(attachmentId, disposition).mapFilesBody(
            "attachment_url_contract_mismatch",
            AttachmentUrlResponseDto.serializer()
        ) { wire ->
            val url = wire.url.takeIf { it.isNotBlank() } ?: return@mapFilesBody null
            AttachmentUrlTarget(url = url, expiresAt = wire.expiresAt)
        }

    suspend fun attachmentPreview(attachmentId: AttachmentId): DataResult<AttachmentPreviewSummary> =
        transport.attachmentPreview(attachmentId).mapFilesBody(
            "attachment_preview_contract_mismatch",
            AttachmentPreviewResponseDto.serializer()
        ) { wire ->
            val status = wire.status.takeIf { it.isNotBlank() } ?: return@mapFilesBody null
            AttachmentPreviewSummary(
                status = status,
                kind = wire.data?.kind,
                truncated = wire.truncated ?: false,
            )
        }

    suspend fun attachmentHtmlPreviewUrl(attachmentId: AttachmentId): DataResult<AttachmentUrlTarget> =
        transport.attachmentHtmlPreviewUrl(attachmentId).mapFilesBody(
            "attachment_html_preview_url_contract_mismatch",
            AttachmentHtmlPreviewUrlDto.serializer()
        ) { wire ->
            val url = wire.url.takeIf { it.isNotBlank() } ?: return@mapFilesBody null
            val expiresAt = wire.expiresAt.takeIf { it.isNotBlank() } ?: return@mapFilesBody null
            AttachmentUrlTarget(url = url, expiresAt = expiresAt)
        }

    suspend fun messageSearch(
        query: String,
        limit: Int = 20,
        offset: Int = 0,
        sort: String? = null,
    ): DataResult<MessageSearchPage> =
        transport.messageSearch(query, limit, offset, sort).mapFilesBody(
            "message_search_contract_mismatch",
            MessageSearchResponseDto.serializer()
        ) { wire -> MessageSearchPage(resultCount = wire.results.size, hasMore = wire.hasMore ?: false) }

    suspend fun messageContext(messageId: MessageId, channelId: ChannelId? = null): DataResult<MessageContextSummary> =
        transport.messageContext(messageId, channelId).mapFilesBody(
            "message_context_contract_mismatch",
            MessageContextResponseDto.serializer()
        ) { wire ->
            val cid = wire.channelId.takeIf { it.isNotBlank() } ?: return@mapFilesBody null
            MessageContextSummary(
                channelId = cid,
                targetMessageId = wire.targetMessageId,
                messageCount = wire.messages.size,
                historyLimited = wire.historyLimited ?: false,
            )
        }

    suspend fun addReaction(messageId: MessageId, emoji: String): DataResult<MessageMutationSummary> =
        transport.addReaction(messageId, emoji).mapMessageMutation("message_reaction_contract_mismatch")

    suspend fun removeReaction(messageId: MessageId, emoji: String): DataResult<MessageMutationSummary> =
        transport.removeReaction(messageId, emoji).mapMessageMutation("message_reaction_contract_mismatch")
}

internal data class FilesPage(val itemCount: Int, val nextCursor: String?)
internal data class AttachmentUrlTarget(val url: String, val expiresAt: String?)
internal data class AttachmentPreviewSummary(val status: String, val kind: String?, val truncated: Boolean)
internal data class MessageSearchPage(val resultCount: Int, val hasMore: Boolean)
internal data class MessageContextSummary(
    val channelId: String,
    val targetMessageId: String?,
    val messageCount: Int,
    val historyLimited: Boolean
)
internal data class MessageMutationSummary(val messageId: String, val reactionCount: Int)

private inline fun <Wire, T> HomeNetworkResponse.mapFilesBody(
    contractCode: String,
    serializer: KSerializer<Wire>,
    crossinline transform: (Wire) -> T?,
): DataResult<T> {
    if (!isSuccessful) {
        return DataResult.Error(ScreenError(code = contractCode, retryable = code >= 500))
    }
    val body = bodyText?.takeIf { it.isNotBlank() }
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    val wire = try {
        filesMessagesApiJson.decodeFromString(serializer, body)
    } catch (_: SerializationException) {
        return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    }
    val value = transform(wire)
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    return DataResult.Content(value)
}

private fun HomeNetworkResponse.mapMessageMutation(contractCode: String): DataResult<MessageMutationSummary> =
    mapFilesBody(contractCode, MessageMutationResponseDto.serializer()) { wire ->
        val id = wire.id.takeIf { it.isNotBlank() } ?: return@mapFilesBody null
        MessageMutationSummary(messageId = id, reactionCount = wire.reactions.size)
    }

// ── Wire DTOs ──

@Serializable
private data class ChannelFilesResponseDto(
    val files: List<JsonObject>,
    val nextCursor: String? = null,
)

@Serializable
private data class AttachmentUrlResponseDto(
    val url: String,
    val expiresAt: String? = null,
)

@Serializable
private data class AttachmentPreviewResponseDto(
    val status: String,
    val data: AttachmentPreviewDataDto? = null,
    val truncated: Boolean? = null,
)

@Serializable
private data class AttachmentPreviewDataDto(val kind: String? = null)

@Serializable
private data class AttachmentHtmlPreviewUrlDto(
    val url: String,
    val expiresAt: String,
)

@Serializable
private data class MessageSearchResponseDto(
    val results: List<JsonObject>,
    val hasMore: Boolean? = null,
)

@Serializable
private data class MessageContextResponseDto(
    val channelId: String,
    val messages: List<JsonObject>,
    val targetMessageId: String? = null,
    val historyLimited: Boolean? = null,
)

@Serializable
private data class MessageMutationResponseDto(
    val id: String,
    val reactions: List<JsonObject> = emptyList(),
)

@Serializable
data class ReactionRequestBody(val emoji: String)

internal fun buildSharedReactionBody(emoji: String): String =
    buildSharedReactionBody(ReactionRequestBody(emoji = emoji))

fun buildSharedReactionBody(request: ReactionRequestBody): String =
    filesMessagesApiJson.encodeToString(request)
