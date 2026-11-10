package build.raft.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val filesMessagesApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

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
    /** Why a `status: "unsupported"` preview has no body: `too_large`, `unavailable`, `unsupported`. */
    val reason: String? = null,
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
private data class AttachmentPreviewResponseDto(
    val status: String,
    val data: AttachmentPreviewDataDto? = null,
    val truncated: Boolean? = null,
    val reason: String? = null,
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
        reason = wire.reason?.takeIf { it.isNotBlank() },
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
