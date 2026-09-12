package build.raft.app.data

import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadId
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

    suspend fun attachmentPreview(attachmentId: AttachmentId): DataResult<AttachmentPreview> =
        transport.attachmentPreview(attachmentId).mapFilesBody(
            "attachment_preview_contract_mismatch",
            AttachmentPreviewResponseDto.serializer()
        ) { wire ->
            val status = wire.status.takeIf { it.isNotBlank() } ?: return@mapFilesBody null
            AttachmentPreview(
                status = status,
                content = wire.data?.toAttachmentPreviewContent(),
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

    /**
     * Same `GET /api/messages/context/:messageId` wire, parsed into full
     * [SharedMessagesPayload] (messages preserved) for quoted-permalink
     * previews. Mirrors `parseSharedMessageContext`; the count-only
     * [messageContext] above keeps the legacy summary shape.
     */
    suspend fun messageContextPayload(
        messageId: MessageId,
        channelId: ChannelId? = null,
        fallbackThreadId: ThreadId,
    ): DataResult<SharedMessagesPayload> {
        val response = transport.messageContext(messageId, channelId)
        if (!response.isSuccessful) {
            return DataResult.Error(ScreenError("message_context_contract_mismatch", retryable = response.code >= 500))
        }
        val body =
            response.bodyText?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError("message_context_contract_mismatch", retryable = false))
        val payload = parseSharedMessageContext(body, fallbackThreadId)
        if (payload.messages.isEmpty()) {
            return DataResult.Error(ScreenError("message_context_contract_mismatch", retryable = false))
        }
        return DataResult.Content(payload)
    }

    suspend fun addReaction(messageId: MessageId, emoji: String): DataResult<MessageMutationSummary> =
        transport.addReaction(messageId, emoji).mapMessageMutation("message_reaction_contract_mismatch")

    suspend fun removeReaction(messageId: MessageId, emoji: String): DataResult<MessageMutationSummary> =
        transport.removeReaction(messageId, emoji).mapMessageMutation("message_reaction_contract_mismatch")
}

internal data class FilesPage(val itemCount: Int, val nextCursor: String?)
internal data class AttachmentUrlTarget(val url: String, val expiresAt: String?)
/**
 * Typed projection of `GET attachments/:id/preview`
 * (`packages/shared/src/attachmentPreview.ts` AttachmentPreviewResponse).
 * `content == null` means the server answered but the payload kind is not
 * previewable here (unsupported status, pdf, or an unknown future kind) —
 * callers fall back to their legacy path.
 */
internal sealed interface AttachmentPreviewContent {
    data class Text(val text: String) : AttachmentPreviewContent
    data class Markdown(val markdown: String) : AttachmentPreviewContent
    data class Csv(
        val delimiter: String,
        val headers: List<String>,
        val rows: List<List<String>>,
        val rowCount: Int,
        val columnCount: Int,
    ) : AttachmentPreviewContent
    /**
     * Bounded server-parsed XLSX preview (web #7168 contract): first row is the header,
     * middle empty rows retain their position, formulas/macros are not evaluated.
     */
    data class Xlsx(
        val sheets: List<XlsxSheetPreview>,
        val sheetCount: Int,
        val truncated: Boolean,
    ) : AttachmentPreviewContent
    data class Diff(
        val files: Int,
        val hunks: Int,
        val additions: Int,
        val deletions: Int,
    ) : AttachmentPreviewContent
    data object Pdf : AttachmentPreviewContent
}

internal data class AttachmentPreview(
    val status: String,
    val content: AttachmentPreviewContent?,
    val truncated: Boolean,
) {
    val isOk: Boolean
        get() = status == "ok"
}

internal data class XlsxSheetPreview(
    val name: String,
    val headers: List<String>,
    val rows: List<List<String>>,
    val rowCount: Int,
    val columnCount: Int,
    val truncated: Boolean,
)
internal data class MessageSearchPage(val resultCount: Int, val hasMore: Boolean)
internal data class MessageContextSummary(
    val channelId: String,
    val targetMessageId: String?,
    val messageCount: Int,
    val historyLimited: Boolean
)
internal data class MessageMutationSummary(
    val messageId: String,
    val reactions: List<MessageReactionSummary>
) {
    val reactionCount: Int get() = reactions.size
}

internal data class MessageReactionSummary(
    val emoji: String,
    val count: Int,
    val reactorIds: List<String>,
    val reactorNames: List<String>
)

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
        wire.toMessageMutationSummary()
    }

internal fun MessageMutationResponseDto.toMessageMutationSummary(): MessageMutationSummary? {
    val id = this.id.takeIf { it.isNotBlank() } ?: return null
    return MessageMutationSummary(
        messageId = id,
        reactions =
            reactions.map { reaction ->
                val emoji = reaction.emoji.takeIf { it.isNotBlank() } ?: return null
                if (reaction.count < 0) return null
                MessageReactionSummary(
                    emoji = emoji,
                    count = reaction.count,
                    reactorIds = reaction.reactorIds,
                    reactorNames = reaction.reactorNames
                )
            }
    )
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
private data class AttachmentPreviewDataDto(
    val kind: String? = null,
    // text
    val text: String? = null,
    // markdown
    val markdown: String? = null,
    // csv
    val delimiter: String? = null,
    val headers: List<String>? = null,
    val rows: List<List<String>>? = null,
    val rowCount: Int? = null,
    val columnCount: Int? = null,
    // xlsx
    val sheets: List<XlsxSheetPreviewDto>? = null,
    val sheetCount: Int? = null,
    // diff
    val stats: AttachmentPreviewDiffStatsDto? = null,
    val truncated: Boolean? = null,
)

@Serializable
private data class XlsxSheetPreviewDto(
    val name: String? = null,
    val headers: List<String>? = null,
    val rows: List<List<String>>? = null,
    val rowCount: Int? = null,
    val columnCount: Int? = null,
    val truncated: Boolean? = null,
)

@Serializable
private data class AttachmentPreviewDiffStatsDto(
    val files: Int = 0,
    val hunks: Int = 0,
    val additions: Int = 0,
    val deletions: Int = 0,
)

internal fun parseSharedAttachmentPreview(bodyText: String): AttachmentPreview? = runCatching {
    val wire = filesMessagesApiJson.decodeFromString(AttachmentPreviewResponseDto.serializer(), bodyText)
    val status = wire.status.takeIf { it.isNotBlank() } ?: return@runCatching null
    AttachmentPreview(
        status = status,
        content = wire.data?.toAttachmentPreviewContent(),
        truncated = wire.truncated ?: false,
    )
}.getOrNull()

private fun AttachmentPreviewDataDto.toAttachmentPreviewContent(): AttachmentPreviewContent? =
    when (kind) {
        "text" -> text?.let { AttachmentPreviewContent.Text(it) }
        "markdown" -> markdown?.let { AttachmentPreviewContent.Markdown(it) }
        "csv" ->
            AttachmentPreviewContent.Csv(
                delimiter = delimiter ?: ",",
                headers = headers.orEmpty(),
                rows = rows.orEmpty(),
                rowCount = rowCount ?: rows.orEmpty().size,
                columnCount = columnCount ?: headers.orEmpty().size,
            )
        "diff" ->
            AttachmentPreviewContent.Diff(
                files = stats?.files ?: 0,
                hunks = stats?.hunks ?: 0,
                additions = stats?.additions ?: 0,
                deletions = stats?.deletions ?: 0,
            )
        "xlsx" -> {
            val parsedSheets = sheets?.mapNotNull { it.toXlsxSheetPreview() }.orEmpty()
            if (parsedSheets.isEmpty()) {
                // A sheet-less xlsx payload is not renderable; fall back to the legacy path.
                null
            } else {
                AttachmentPreviewContent.Xlsx(
                    sheets = parsedSheets,
                    sheetCount = sheetCount ?: parsedSheets.size,
                    truncated = truncated ?: false,
                )
            }
        }
        "pdf" -> AttachmentPreviewContent.Pdf
        else -> null
    }

private fun XlsxSheetPreviewDto.toXlsxSheetPreview(): XlsxSheetPreview? {
    val sheetName = name?.takeIf { it.isNotBlank() } ?: return null
    val sheetHeaders = headers ?: return null
    return XlsxSheetPreview(
        name = sheetName,
        headers = sheetHeaders,
        rows = rows.orEmpty(),
        rowCount = rowCount ?: rows.orEmpty().size,
        columnCount = columnCount ?: sheetHeaders.size,
        truncated = truncated ?: false,
    )
}

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
internal data class MessageMutationResponseDto(
    val id: String,
    val reactions: List<MessageReactionDto> = emptyList(),
)

@Serializable
internal data class MessageReactionDto(
    val emoji: String,
    val count: Int,
    val reactorIds: List<String> = emptyList(),
    val reactorNames: List<String> = emptyList(),
)

@Serializable
data class ReactionRequestBody(val emoji: String)

internal fun buildSharedReactionBody(emoji: String): String =
    buildSharedReactionBody(ReactionRequestBody(emoji = emoji))

fun buildSharedReactionBody(request: ReactionRequestBody): String =
    filesMessagesApiJson.encodeToString(request)
