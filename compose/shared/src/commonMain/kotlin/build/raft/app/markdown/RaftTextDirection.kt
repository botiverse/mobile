package build.raft.app.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import com.tencent.kuiklybase.markdown.model.RaftMarkdownTokenTypes
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * Text direction derived from a block's own letters (botiverse/slock #15039 `utils/textDirection.ts`).
 *
 * Hebrew, Arabic, and other right-to-left scripts yield [Rtl]; any other letter yields [Ltr];
 * text with no deciding letters yields `null`. Leading/inline `@mentions`, `#refs`, `http(s)://`
 * URLs, and inline `` `code` `` are skipped so a Hebrew message starting with `@alice` still lays
 * out right to left.
 */
enum class RaftTextDirection {
    Ltr,
    Rtl,
}

/** Unicode First Strong Isolate (UAX #9): isolates links/mentions/refs inside RTL paragraphs. */
internal const val RAFT_BIDI_FSI = '\u2068'

/** Unicode Pop Directional Isolate (UAX #9), closing [RAFT_BIDI_FSI]. */
internal const val RAFT_BIDI_PDI = '\u2069'

// Matches web `SKIPPED_TOKENS = /(?:^|\s)[@#][^\s]+|https?:\/\/\S+|`[^`]*`/gu`.
private val SkippedDirectionTokens = Regex("""(?:^|\s)[@#]\S+|https?://\S+|`[^`]*`""")

/** Matches web `RTL_LETTER = /[\u0590-\u08FF\uFB1D-\uFDFF\uFE70-\uFEFC]/u` (excludes U+FEFD..U+FEFF, notably U+FEFF BOM). */
internal fun isRaftRtlScriptChar(char: Char): Boolean {
    val code = char.code
    return (code in 0x0590..0x08FF) || (code in 0xFB1D..0xFDFF) || (code in 0xFE70..0xFEFC)
}

/**
 * Resolves the direction of plain or raw-markdown [text], skipping `@mentions`, `#refs`,
 * `http(s)://` URLs, and inline `` `code` ``. Returns `null` when no letter remains.
 */
fun raftTextDirection(text: String): RaftTextDirection? {
    if (text.isEmpty()) return null
    val withoutBom = if ('\uFEFF' in text) text.replace("\uFEFF", "") else text
    val cleaned = SkippedDirectionTokens.replace(withoutBom, " ")
    for (char in cleaned) {
        if (isRaftRtlScriptChar(char)) return RaftTextDirection.Rtl
        if (char.isLetter()) return RaftTextDirection.Ltr
    }
    return null
}

private val SkippedMarkdownDirectionNodeTypes: Set<IElementType> =
    setOf(
        MarkdownElementTypes.CODE_SPAN,
        MarkdownElementTypes.CODE_FENCE,
        MarkdownElementTypes.CODE_BLOCK,
        MarkdownElementTypes.INLINE_LINK,
        MarkdownElementTypes.FULL_REFERENCE_LINK,
        MarkdownElementTypes.SHORT_REFERENCE_LINK,
        MarkdownElementTypes.AUTOLINK,
        GFMTokenTypes.GFM_AUTOLINK,
        MarkdownElementTypes.IMAGE,
        GFMElementTypes.INLINE_MATH,
        GFMElementTypes.BLOCK_MATH,
        RaftMarkdownTokenTypes.REFERENCE,
        MarkdownTokenTypes.LIST_BULLET,
        MarkdownTokenTypes.LIST_NUMBER,
        GFMTokenTypes.CHECK_BOX,
        MarkdownTokenTypes.BLOCK_QUOTE,
        MarkdownTokenTypes.ATX_HEADER,
    )

/**
 * Resolves a Markdown AST block's direction (web `blockDirection`), skipping links, images,
 * inline/block code, math, and list/quote syntax markers, then running [raftTextDirection] so
 * inline `@mentions`, `#refs`, and bare URLs inside text nodes are skipped too.
 */
fun raftMarkdownBlockDirection(content: String, node: ASTNode): RaftTextDirection? {
    val buffer = StringBuilder()
    appendReadableMarkdownDirectionText(content, node, buffer)
    return raftTextDirection(buffer.toString())
}

private fun appendReadableMarkdownDirectionText(content: String, node: ASTNode, out: StringBuilder) {
    if (node.type in SkippedMarkdownDirectionNodeTypes) {
        out.append(' ')
        return
    }
    if (node.children.isEmpty()) {
        val start = node.startOffset.coerceIn(0, content.length)
        val end = node.endOffset.coerceIn(start, content.length)
        if (start < end) out.append(content, start, end)
        return
    }
    for (child in node.children) {
        appendReadableMarkdownDirectionText(content, child, out)
    }
}

/** Converts a resolved [RaftTextDirection] to Compose [TextDirection] (`null` -> [TextDirection.Content]). */
fun RaftTextDirection?.toComposeTextDirection(): TextDirection =
    when (this) {
        RaftTextDirection.Rtl -> TextDirection.Rtl
        RaftTextDirection.Ltr -> TextDirection.Ltr
        null -> TextDirection.Content
    }

/** Converts a resolved [RaftTextDirection] to Compose [LayoutDirection], falling back to [fallback]. */
fun RaftTextDirection?.toComposeLayoutDirection(fallback: LayoutDirection): LayoutDirection =
    when (this) {
        RaftTextDirection.Rtl -> LayoutDirection.Rtl
        RaftTextDirection.Ltr -> LayoutDirection.Ltr
        null -> fallback
    }

/** Applies [direction] to this [TextStyle] when decided; returns `this` unchanged when `null`. */
fun TextStyle.withRaftTextDirection(direction: RaftTextDirection?): TextStyle =
    when (direction) {
        RaftTextDirection.Rtl -> if (textDirection == TextDirection.Rtl) this else copy(textDirection = TextDirection.Rtl)
        RaftTextDirection.Ltr -> if (textDirection == TextDirection.Ltr) this else copy(textDirection = TextDirection.Ltr)
        null -> this
    }

/**
 * Isolates every link/mention/ref [LinkAnnotation] in this [AnnotatedString] with Unicode
 * First Strong Isolate ([RAFT_BIDI_FSI] ... [RAFT_BIDI_PDI]) so tokens like `@alice`, `#general`,
 * and URLs keep their own reading order inside an RTL paragraph (web `[&_a]:[unicode-bidi:plaintext]`,
 * botiverse/slock #15039).
 *
 * Call only on RTL text so LTR paragraphs incur zero extra characters or allocations.
 * [RAFT_BIDI_FSI] and [RAFT_BIDI_PDI] are stripped by [raftCopyText] for copy and accessibility.
 */
internal fun AnnotatedString.withRaftRtlLinkBidiIsolation(): AnnotatedString {
    if (isEmpty()) return this
    val originalLength = length
    val gaps =
        getStringAnnotations(RaftInlineContentAnnotationTag, 0, originalLength)
            .filter { it.item.startsWith(RaftInlineGapInlineIdPrefix) && it.end > it.start }
    val gapEndingAt = if (gaps.isEmpty()) emptyMap() else gaps.associateBy { it.end }
    val gapStartingAt = if (gaps.isEmpty()) emptyMap() else gaps.associateBy { it.start }
    val rawRanges =
        getLinkAnnotations(0, originalLength)
            .asSequence()
            .filter { range ->
                val start = range.start.coerceIn(0, originalLength)
                val end = range.end.coerceIn(start, originalLength)
                start < end && (range.item as? LinkAnnotation.Clickable)?.tag != RaftMarkdownBodyClickTag
            }
            .map { range ->
                var start = range.start.coerceIn(0, originalLength)
                var end = range.end.coerceIn(start, originalLength)
                // Keep a chip's glued gap placeholders ([gap][\u2060][label][\u2060][gap]) inside
                // the same single FSI..PDI isolate so UAX #9 never detaches painted padding from
                // the chip label and chip gap links remain intact.
                if (start >= 1 && text[start - 1] == '\u2060') {
                    gapEndingAt[start - 1]?.let { gap -> start = minOf(start, gap.start.coerceIn(0, originalLength)) }
                }
                if (end < originalLength && text[end] == '\u2060') {
                    gapStartingAt[end + 1]?.let { gap -> end = maxOf(end, gap.end.coerceIn(end, originalLength)) }
                }
                start until end
            }
            .sortedWith(compareBy<IntRange> { it.first }.thenByDescending { it.last })
            .toList()
    if (rawRanges.isEmpty()) return this

    // Keep outermost non-overlapping link/chip ranges so each link or chip is isolated once.
    val ranges = ArrayList<IntRange>(rawRanges.size)
    var lastEnd = -1
    for (range in rawRanges) {
        if (range.first >= lastEnd) {
            ranges += range
            lastEnd = range.last + 1
        }
    }
    if (ranges.isEmpty()) return this

    val fsiAt = IntArray(originalLength + 1)
    val pdiAt = IntArray(originalLength + 1)
    for (range in ranges) {
        fsiAt[range.first]++
        pdiAt[range.last + 1]++
    }

    // For each original boundary i in 0..originalLength:
    //   - PDI marks closing a link that ended at i are emitted BEFORE i
    //   - FSI marks opening a link that starts at i are emitted AFTER PDI and BEFORE char[i]
    // Therefore:
    //   - an end offset maps right BEFORE PDI at i (so the isolate mark stays outside the link/box)
    //   - a start offset maps right AFTER FSI at i (so the isolate mark stays outside the link/box)
    val endMap = IntArray(originalLength + 1)
    val startMap = IntArray(originalLength + 1)
    val outText = StringBuilder(originalLength + ranges.size * 2)
    for (i in 0..originalLength) {
        endMap[i] = outText.length
        repeat(pdiAt[i]) { outText.append(RAFT_BIDI_PDI) }
        repeat(fsiAt[i]) { outText.append(RAFT_BIDI_FSI) }
        startMap[i] = outText.length
        if (i < originalLength) outText.append(text[i])
    }

    fun mapRange(start: Int, end: Int): Pair<Int, Int> {
        val clampedStart = start.coerceIn(0, originalLength)
        val clampedEnd = end.coerceIn(clampedStart, originalLength)
        val mappedStart = startMap[clampedStart]
        val mappedEnd = maxOf(mappedStart, endMap[clampedEnd])
        return mappedStart to mappedEnd
    }

    val original = this
    return AnnotatedString.Builder(outText.length)
        .apply {
            append(outText.toString())
            original.spanStyles.forEach { span ->
                val (s, e) = mapRange(span.start, span.end)
                addStyle(span.item, s, e)
            }
            original.paragraphStyles.forEach { para ->
                val (s, e) = mapRange(para.start, para.end)
                addStyle(para.item, s, e)
            }
            original.getStringAnnotations(0, originalLength).forEach { ann ->
                val (s, e) = mapRange(ann.start, ann.end)
                addStringAnnotation(ann.tag, ann.item, s, e)
            }
            original.getLinkAnnotations(0, originalLength).forEach { ann ->
                val (s, e) = mapRange(ann.start, ann.end)
                when (val link = ann.item) {
                    is LinkAnnotation.Url -> addLink(link, s, e)
                    is LinkAnnotation.Clickable -> addLink(link, s, e)
                }
            }
        }
        .toAnnotatedString()
}
