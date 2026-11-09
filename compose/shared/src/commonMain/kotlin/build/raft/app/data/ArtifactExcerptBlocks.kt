package build.raft.app.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/*
 * A Markdown version's rich excerpt: the first blocks of its entry, as data (botiverse/slock
 * staging `packages/shared/src/artifactExcerptBlocks.ts`, ArtifactPreview `excerpt`'s optional
 * `blocks`; #13472, #13488). Every string in it is agent-made words, shown as text and nothing
 * else: never Markdown or HTML, no links, nothing fetched.
 *
 * [parseArtifactExcerptBlocks] is the web's `parseArtifactExcerptBlocks`: a block whose `type` this
 * client doesn't know (a newer Server's) is left out, the rest drawn; a block of a known type that
 * isn't valid fails the whole list, and so does a list with nothing left. Then the card draws the
 * excerpt's `text`, which is always there; the card itself is never dropped.
 */

/** web `ARTIFACT_EXCERPT_BLOCKS_MAX_ROWS`: blocks in a list, and items in a list block. */
internal const val ARTIFACT_EXCERPT_BLOCKS_MAX_ROWS = 16

/** web `ARTIFACT_EXCERPT_BLOCKS_MAX_TEXT_BYTES`, the schema's bound on one span's text (zod `max`, in UTF-16 units). */
internal const val ARTIFACT_EXCERPT_SPAN_MAX_CHARS = 2_048

/** web `ARTIFACT_EXCERPT_BLOCKS_MAX_SPANS`. */
internal const val ARTIFACT_EXCERPT_BLOCKS_MAX_SPANS = 80

/** web `ARTIFACT_EXCERPT_CODE_MAX_LINES`; a line at most twice `ARTIFACT_EXCERPT_CODE_LINE_MAX_CHARS` (100). */
internal const val ARTIFACT_EXCERPT_CODE_MAX_LINES = 4
internal const val ARTIFACT_EXCERPT_CODE_LINE_MAX_LENGTH = 200

/** web `ARTIFACT_EXCERPT_TABLE_MAX_ROWS` / `_MAX_COLUMNS`. */
internal const val ARTIFACT_EXCERPT_TABLE_MAX_ROWS = 3
internal const val ARTIFACT_EXCERPT_TABLE_MAX_COLUMNS = 8

/** An image's alt: at most twice `ARTIFACT_EXCERPT_IMAGE_ALT_MAX_CHARS` (80). */
internal const val ARTIFACT_EXCERPT_IMAGE_ALT_MAX_LENGTH = 160

/** web `ARTIFACT_EXCERPT_IMAGES_MAX_COUNT`. */
internal const val ARTIFACT_EXCERPT_IMAGES_MAX_COUNT = 65_536

/** Words with the inline styles that survive: bold, italic and code. */
@Immutable
data class ArtifactExcerptSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
)

/** A list's item: [level] 0, or 1 for anything nested; [order] an ordered item's number; [checked] a task's state. */
@Immutable
data class ArtifactExcerptListItem(
    val level: Int,
    val order: Long? = null,
    val checked: Boolean? = null,
    val spans: List<ArtifactExcerptSpan>,
)

/** web `ArtifactExcerptBlock`. */
@Immutable
sealed interface ArtifactExcerptBlock {
    data class Heading(val level: Int, val spans: List<ArtifactExcerptSpan>) : ArtifactExcerptBlock

    data class Paragraph(val spans: List<ArtifactExcerptSpan>) : ArtifactExcerptBlock

    data class Quote(val spans: List<ArtifactExcerptSpan>) : ArtifactExcerptBlock

    data class ListBlock(val items: List<ArtifactExcerptListItem>) : ArtifactExcerptBlock

    data class Code(val lines: List<String>) : ArtifactExcerptBlock

    /** A cell may be empty (no spans). */
    data class Table(val header: List<List<ArtifactExcerptSpan>>, val rows: List<List<List<ArtifactExcerptSpan>>>) : ArtifactExcerptBlock

    /** Not loaded: a label; [alt] may be empty. */
    data class Image(val alt: String) : ArtifactExcerptBlock

    /** A line of [count] (2 or more) images. */
    data class Images(val count: Int) : ArtifactExcerptBlock

    /** A Mermaid or HTML block: a label. */
    data object Diagram : ArtifactExcerptBlock

    data object Rule : ArtifactExcerptBlock
}

/** A card's excerpt: its plain [text] (always there) and the [blocks] this client can draw, else null. */
@Immutable
data class ArtifactExcerpt(
    val text: String,
    val blocks: List<ArtifactExcerptBlock>? = null,
)

/** web `BLOCK_TYPES`. */
internal val ARTIFACT_EXCERPT_BLOCK_TYPES = setOf("heading", "paragraph", "quote", "list", "code", "table", "image", "images", "diagram", "rule")

/** web `isFromANewerServer`: an object naming a string type this client doesn't know. An object with no type is simply not a block. */
private fun JsonElement.isFromANewerServer(): Boolean {
    val type = (this as? JsonObject)?.get("type") as? JsonPrimitive ?: return false
    return type.isString && type.content !in ARTIFACT_EXCERPT_BLOCK_TYPES
}

/**
 * [value] as a block list without the blocks this client doesn't know, or null when it isn't one a
 * client may draw (null too when nothing is left: it would draw nothing).
 */
fun parseArtifactExcerptBlocks(value: JsonElement?): List<ArtifactExcerptBlock>? {
    val array = value as? JsonArray ?: return null
    val known = array.filterNot { it.isFromANewerServer() }
    if (known.isEmpty() || known.size > ARTIFACT_EXCERPT_BLOCKS_MAX_ROWS) return null
    val blocks = ArrayList<ArtifactExcerptBlock>(known.size)
    for (element in known) blocks += element.toBlockOrNull() ?: return null
    return blocks
}

private fun JsonElement.toBlockOrNull(): ArtifactExcerptBlock? {
    val block = this as? JsonObject ?: return null
    return when (block.stringOrNull("type")) {
        "heading" -> {
            val level = block.intIn("level", 1L..6L) ?: return null
            ArtifactExcerptBlock.Heading(level.toInt(), block["spans"].spansOrNull() ?: return null)
        }
        "paragraph" -> ArtifactExcerptBlock.Paragraph(block["spans"].spansOrNull() ?: return null)
        "quote" -> ArtifactExcerptBlock.Quote(block["spans"].spansOrNull() ?: return null)
        "list" -> {
            val items = block["items"] as? JsonArray ?: return null
            if (items.size !in 1..ARTIFACT_EXCERPT_BLOCKS_MAX_ROWS) return null
            ArtifactExcerptBlock.ListBlock(items.map { it.listItemOrNull() ?: return null })
        }
        "code" -> {
            val lines = block["lines"] as? JsonArray ?: return null
            if (lines.size !in 1..ARTIFACT_EXCERPT_CODE_MAX_LINES) return null
            ArtifactExcerptBlock.Code(
                lines.map { line ->
                    line.stringOrNull()?.takeIf { it.length <= ARTIFACT_EXCERPT_CODE_LINE_MAX_LENGTH } ?: return null
                },
            )
        }
        "table" -> {
            val header = block["header"].cellsOrNull() ?: return null
            val rows = block["rows"] as? JsonArray ?: return null
            if (rows.size > ARTIFACT_EXCERPT_TABLE_MAX_ROWS) return null
            ArtifactExcerptBlock.Table(header, rows.map { it.cellsOrNull() ?: return null })
        }
        "image" -> {
            val alt = block["alt"].stringOrNull() ?: return null
            if (alt.length > ARTIFACT_EXCERPT_IMAGE_ALT_MAX_LENGTH) return null
            ArtifactExcerptBlock.Image(alt)
        }
        "images" -> ArtifactExcerptBlock.Images(block.intIn("count", 2L..ARTIFACT_EXCERPT_IMAGES_MAX_COUNT.toLong())?.toInt() ?: return null)
        "diagram" -> ArtifactExcerptBlock.Diagram
        "rule" -> ArtifactExcerptBlock.Rule
        // No type, or one that isn't a string: not a block.
        else -> null
    }
}

private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.stringOrNull(key: String): String? = get(key).stringOrNull()

/** A JSON number that is a whole number within [range] (zod `int().min().max()`); absent or anything else is null. */
private fun JsonElement?.wholeNumberIn(range: LongRange): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString || primitive is JsonNull) return null
    val number = primitive.content.toDoubleOrNull() ?: return null
    if (number.isNaN() || number.isInfinite() || number != kotlin.math.floor(number)) return null
    val whole = number.toLong()
    return whole.takeIf { it in range }
}

private fun JsonObject.intIn(key: String, range: LongRange): Long? = get(key).wholeNumberIn(range)

/** zod `z.literal(true).optional()`: absent is false, `true` is true, anything else (false, null) fails. */
private sealed interface Flag {
    data class Valid(val on: Boolean) : Flag

    data object Invalid : Flag
}

private fun JsonObject.trueFlag(key: String): Flag {
    if (!containsKey(key)) return Flag.Valid(false)
    val primitive = get(key) as? JsonPrimitive ?: return Flag.Invalid
    if (primitive.isString || primitive is JsonNull) return Flag.Invalid
    return if (primitive.booleanOrNull == true) Flag.Valid(true) else Flag.Invalid
}

private fun JsonElement.spanOrNull(): ArtifactExcerptSpan? {
    val span = this as? JsonObject ?: return null
    val text = span.stringOrNull("text") ?: return null
    if (text.isEmpty() || text.length > ARTIFACT_EXCERPT_SPAN_MAX_CHARS) return null
    val bold = span.trueFlag("bold") as? Flag.Valid ?: return null
    val italic = span.trueFlag("italic") as? Flag.Valid ?: return null
    val code = span.trueFlag("code") as? Flag.Valid ?: return null
    return ArtifactExcerptSpan(text = text, bold = bold.on, italic = italic.on, code = code.on)
}

/** A run of spans of [minimum] (1, or 0 for a table's cell) to [ARTIFACT_EXCERPT_BLOCKS_MAX_SPANS]. */
private fun JsonElement?.spansOrNull(minimum: Int = 1): List<ArtifactExcerptSpan>? {
    val array = this as? JsonArray ?: return null
    if (array.size !in minimum..ARTIFACT_EXCERPT_BLOCKS_MAX_SPANS) return null
    return array.map { it.spanOrNull() ?: return null }
}

private fun JsonElement?.cellsOrNull(): List<List<ArtifactExcerptSpan>>? {
    val array = this as? JsonArray ?: return null
    if (array.size !in 1..ARTIFACT_EXCERPT_TABLE_MAX_COLUMNS) return null
    return array.map { it.spansOrNull(minimum = 0) ?: return null }
}

private fun JsonElement.listItemOrNull(): ArtifactExcerptListItem? {
    val item = this as? JsonObject ?: return null
    val level = item.intIn("level", 0L..1L) ?: return null
    val order =
        if (item.containsKey("order")) {
            item.intIn("order", 0L..999_999_999L) ?: return null
        } else {
            null
        }
    val checked =
        if (item.containsKey("checked")) {
            (item["checked"] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.booleanOrNull ?: return null
        } else {
            null
        }
    return ArtifactExcerptListItem(level = level.toInt(), order = order, checked = checked, spans = item["spans"].spansOrNull() ?: return null)
}
