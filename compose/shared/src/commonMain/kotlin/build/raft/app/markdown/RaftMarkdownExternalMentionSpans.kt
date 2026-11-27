package build.raft.app.markdown

import build.raft.app.matchesBodySlice
import build.raft.app.model.ExternalMentionSpan
import com.tencent.kuiklybase.markdown.model.RaftMarkdownTokenTypes
import com.tencent.kuiklybase.markdown.model.parseMarkdownTopLevelNodes
import com.tencent.kuiklybase.markdown.utils.EntityConverter
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * A validated external mention span matched against the parsed Markdown AST
 * (botiverse/slock #17299 `replaceSourceSpansInTree` parity).
 *
 * [range] is in `parserContent` UTF-16 coordinates (inclusive `IntRange`).
 * [visibleText] is the parsed text drawn on the chip (unescaped phrasing text, or the
 * unwrapped code-span text when the span matched a single `CODE_SPAN` node).
 */
internal data class ResolvedExternalMentionSpan(
    val projectionId: String,
    val visibleText: String,
    val range: IntRange,
    val isCodeSpan: Boolean,
)

/**
 * Validates raw [spans] against [rawBody] using exact UTF-16 code-unit slicing
 * (`start >= 0 && end > start && end <= rawBody.length && rawBody.substring(start, end) == label`),
 * returning spans sorted by `(start, end)`.
 */
internal fun validateExternalMentionSpans(
    rawBody: String,
    spans: List<ExternalMentionSpan>,
): List<ExternalMentionSpan> {
    if (rawBody.isEmpty() || spans.isEmpty()) return emptyList()
    return spans
        .filter { span -> span.matchesBodySlice(rawBody) }
        .sortedWith(compareBy<ExternalMentionSpan> { it.start }.thenBy { it.end })
}

/**
 * Projects validated [ExternalMentionSpan] offsets from [rawBody] through
 * [normalizeThreadMarkdownBody] ([normalizedBody]) and `MarkdownParserContent.from` ([parserContent])
 * so AST node offsets in [parserContent] align with the raw wire offsets.
 */
internal fun projectExternalMentionSpansToParserContent(
    rawBody: String,
    normalizedBody: String,
    parserContent: String,
    spans: List<ExternalMentionSpan>,
): List<ExternalMentionSpan> {
    val validated = validateExternalMentionSpans(rawBody, spans)
    if (validated.isEmpty()) return emptyList()
    if (rawBody == normalizedBody && normalizedBody == parserContent) {
        return validated
    }
    val rawToNorm =
        if (rawBody == normalizedBody) {
            null
        } else {
            buildRawToNormalizedOffsetMap(rawBody, normalizedBody) ?: return emptyList()
        }
    val normToParser =
        if (normalizedBody == parserContent) {
            null
        } else {
            buildNormalizedToParserOffsetMap(normalizedBody, parserContent) ?: return emptyList()
        }
    return validated.mapNotNull { span ->
        val normStart = rawToNorm?.get(span.start) ?: span.start
        val normEnd = rawToNorm?.get(span.end) ?: span.end
        if (normStart < 0 || normEnd <= normStart || normEnd > normalizedBody.length) {
            return@mapNotNull null
        }
        if (rawToNorm != null && normalizedBody.substring(normStart, normEnd) != span.label) {
            return@mapNotNull null
        }
        val parserStart = normToParser?.get(normStart) ?: normStart
        val parserEnd = normToParser?.get(normEnd) ?: normEnd
        if (parserStart < 0 || parserEnd <= parserStart || parserEnd > parserContent.length) {
            return@mapNotNull null
        }
        ExternalMentionSpan(
            id = span.id,
            label = span.label,
            start = parserStart,
            end = parserEnd,
        )
    }
}

/**
 * Resolves [spans] against the cached Markdown AST for [parserContent], matching web's
 * `replaceSourceSpansInTree` (`packages/web/src/components/message/remarkMessageTokens.ts`):
 *
 * - Only replaces spans whose exact source text still matches at `[start, end)`.
 * - Never chips inside blockquotes, fenced/indented code blocks, links, autolinks, images,
 *   math, or HTML nodes.
 * - Accepts a span ONLY when the parsed tree holds that exact source range as either:
 *   1. A single whole `CODE_SPAN` node (`node.startOffset == span.start && node.endOffset == span.end`),
 *      rendered with the unwrapped code-span text (without inline-code chrome), OR
 *   2. Plain phrasing leaf tokens inside a single parent (never crossing composite Markdown
 *      structure such as emphasis, links, or line breaks), rendered with backslash-unescaped text.
 * - Overlapping candidate spans keep the earliest accepted match (`start` ascending).
 */
internal fun resolveExternalMentionSpansInMarkdown(
    rawBody: String,
    normalizedBody: String,
    parserContent: String,
    spans: List<ExternalMentionSpan>,
): List<ResolvedExternalMentionSpan> {
    val projected =
        projectExternalMentionSpansToParserContent(
            rawBody = rawBody,
            normalizedBody = normalizedBody,
            parserContent = parserContent,
            spans = spans,
        )
    if (projected.isEmpty()) return emptyList()

    val topLevelNodes = parseMarkdownTopLevelNodes(parserContent)
    val quoteRanges = raftMarkdownQuoteRanges(parserContent)
    val resolved = mutableListOf<ResolvedExternalMentionSpan>()
    var acceptedCursor = 0

    fun visit(node: ASTNode) {
        if (node.type in ExternalMentionSkippedNodeTypes) return
        val children = node.children
        if (children.isEmpty()) return

        var index = 0
        while (index < children.size) {
            val child = children[index]
            if (child.type == MarkdownElementTypes.CODE_SPAN) {
                val matched =
                    projected.firstOrNull { candidate ->
                        candidate.start >= acceptedCursor &&
                            candidate.start == child.startOffset &&
                            candidate.end == child.endOffset &&
                            !quoteRanges.quotes(candidate.start, candidate.end)
                    }
                if (matched != null) {
                    val visible = unwrapExternalMentionCodeSpanText(parserContent.substring(child.startOffset, child.endOffset))
                    if (visible.isNotEmpty()) {
                        resolved +=
                            ResolvedExternalMentionSpan(
                                projectionId = matched.id,
                                visibleText = visible,
                                range = child.startOffset until child.endOffset,
                                isCodeSpan = true,
                            )
                        acceptedCursor = child.endOffset
                    }
                }
                index++
                continue
            }

            if (child.isExternalMentionPhrasingLeaf(children, index)) {
                val runStart = index
                var runEnd = index
                while (runEnd + 1 < children.size && children[runEnd + 1].isExternalMentionPhrasingLeaf(children, runEnd + 1)) {
                    runEnd++
                }
                val runStartOffset = children[runStart].startOffset
                val runEndOffset = children[runEnd].endOffset
                for (candidate in projected) {
                    if (candidate.start < acceptedCursor) continue
                    if (candidate.start < runStartOffset) continue
                    if (candidate.start >= runEndOffset) break
                    if (candidate.end > runEndOffset) continue
                    if (quoteRanges.quotes(candidate.start, candidate.end)) continue
                    if (!isValidExternalMentionEscapeBoundary(parserContent, runStartOffset, runEndOffset, candidate.start)) {
                        continue
                    }
                    if (!isValidExternalMentionEscapeBoundary(parserContent, runStartOffset, runEndOffset, candidate.end)) {
                        continue
                    }
                    val visible =
                        EntityConverter.replaceEntities(
                            parserContent.substring(candidate.start, candidate.end),
                            processEntities = false,
                            processEscapes = true,
                        )
                    if (visible.isNotEmpty()) {
                        resolved +=
                            ResolvedExternalMentionSpan(
                                projectionId = candidate.id,
                                visibleText = visible,
                                range = candidate.start until candidate.end,
                                isCodeSpan = false,
                            )
                        acceptedCursor = candidate.end
                    }
                }
                index = runEnd + 1
                continue
            }

            if (child.type !in ExternalMentionSkippedNodeTypes && child.children.isNotEmpty()) {
                visit(child)
            }
            index++
        }
    }

    topLevelNodes.forEach(::visit)
    return resolved.sortedBy { it.range.first }
}

/**
 * CommonMark §6.1 code-span unwrapping matching `MarkdownState.kt` `markdownInlineCodeSpans`
 * and mdast `inlineCode.value`.
 */
internal fun unwrapExternalMentionCodeSpanText(sourceText: String): String {
    val markerLength = sourceText.takeWhile { it == '`' }.length
    val rawCode =
        if (markerLength > 0 && sourceText.length >= markerLength * 2) {
            sourceText.substring(markerLength, sourceText.length - markerLength)
        } else {
            sourceText
        }
    val normalizedCode =
        rawCode
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\n', ' ')
    return if (
        normalizedCode.length >= 2 &&
        normalizedCode.first() == ' ' &&
        normalizedCode.last() == ' ' &&
        normalizedCode.any { it != ' ' }
    ) {
        normalizedCode.substring(1, normalizedCode.lastIndex)
    } else {
        normalizedCode
    }
}

/**
 * Whether [this] AST node is a plain phrasing leaf (analogous to mdast phrasing `Text` before
 * pattern matchers run) in [siblings] at [index]. Delimiter tokens of enclosing emphasis /
 * strikethrough elements and angle brackets framing `EMAIL_AUTOLINK` are excluded.
 */
internal fun ASTNode.isExternalMentionPhrasingLeaf(
    siblings: List<ASTNode>,
    index: Int,
): Boolean {
    val parentType = parent?.type
    return when (type) {
        MarkdownTokenTypes.TEXT,
        RaftMarkdownTokenTypes.REFERENCE,
        MarkdownTokenTypes.WHITE_SPACE,
        MarkdownTokenTypes.SINGLE_QUOTE,
        MarkdownTokenTypes.DOUBLE_QUOTE,
        MarkdownTokenTypes.LPAREN,
        MarkdownTokenTypes.RPAREN,
        MarkdownTokenTypes.LBRACKET,
        MarkdownTokenTypes.RBRACKET,
        MarkdownTokenTypes.COLON,
        MarkdownTokenTypes.EXCLAMATION_MARK,
        MarkdownTokenTypes.BACKTICK,
        MarkdownTokenTypes.ESCAPED_BACKTICKS,
        GFMTokenTypes.DOLLAR -> true
        MarkdownTokenTypes.LT -> siblings.getOrNull(index + 1)?.type != MarkdownTokenTypes.EMAIL_AUTOLINK
        MarkdownTokenTypes.GT -> siblings.getOrNull(index - 1)?.type != MarkdownTokenTypes.EMAIL_AUTOLINK
        MarkdownTokenTypes.EMPH ->
            parentType != MarkdownElementTypes.EMPH && parentType != MarkdownElementTypes.STRONG
        GFMTokenTypes.TILDE ->
            parentType != GFMElementTypes.STRIKETHROUGH
        else -> false
    }
}

private val ExternalMentionSkippedNodeTypes: Set<IElementType> =
    setOf(
        MarkdownElementTypes.BLOCK_QUOTE,
        MarkdownElementTypes.CODE_BLOCK,
        MarkdownElementTypes.CODE_FENCE,
        MarkdownElementTypes.HTML_BLOCK,
        MarkdownElementTypes.INLINE_LINK,
        MarkdownElementTypes.SHORT_REFERENCE_LINK,
        MarkdownElementTypes.FULL_REFERENCE_LINK,
        MarkdownElementTypes.AUTOLINK,
        MarkdownElementTypes.LINK_DEFINITION,
        MarkdownElementTypes.IMAGE,
        MarkdownTokenTypes.HTML_TAG,
        MarkdownTokenTypes.AUTOLINK,
        MarkdownTokenTypes.EMAIL_AUTOLINK,
        GFMTokenTypes.GFM_AUTOLINK,
        GFMElementTypes.INLINE_MATH,
        GFMElementTypes.BLOCK_MATH,
    )

/**
 * Returns false if [offset] falls in the middle of a CommonMark backslash escape (`\<ascii-punct>`),
 * so a span cannot start or end between `\` and the escaped punctuation character.
 */
private fun isValidExternalMentionEscapeBoundary(
    source: String,
    runStart: Int,
    runEnd: Int,
    offset: Int,
): Boolean {
    if (offset <= runStart || offset >= runEnd) return true
    if (!source[offset].isCommonMarkEscapablePunctuation()) return true
    var backslashes = 0
    var cursor = offset - 1
    while (cursor >= runStart && source[cursor] == '\\') {
        backslashes++
        cursor--
    }
    return backslashes % 2 == 0
}

private fun Char.isCommonMarkEscapablePunctuation(): Boolean =
    this in '!'..'/' || this in ':'..'@' || this in '['..'`' || this in '{'..'~'

/**
 * Replicates [normalizeThreadMarkdownBody]'s exact literal/decoded segments to map every UTF-16
 * index in [rawBody] (`0..rawBody.length`) to its UTF-16 index in [normalizedBody].
 */
private fun buildRawToNormalizedOffsetMap(
    rawBody: String,
    normalizedBody: String,
): IntArray? {
    val literal = rawBody.threadEscapedLineBreakLiteralRanges()
    val map = IntArray(rawBody.length + 1)
    var rawIdx = 0
    var normIdx = 0

    fun advanceDecoded(limit: Int) {
        while (rawIdx < limit) {
            map[rawIdx] = normIdx
            when {
                rawIdx + 3 < limit && rawBody.startsWith("\\r\\n", rawIdx) -> {
                    map[rawIdx + 1] = normIdx
                    map[rawIdx + 2] = normIdx
                    map[rawIdx + 3] = normIdx
                    rawIdx += 4
                    normIdx += 1
                }
                rawIdx + 1 < limit && (rawBody.startsWith("\\n", rawIdx) || rawBody.startsWith("\\r", rawIdx)) -> {
                    map[rawIdx + 1] = normIdx
                    rawIdx += 2
                    normIdx += 1
                }
                else -> {
                    rawIdx += 1
                    normIdx += 1
                }
            }
        }
    }

    for (range in literal) {
        advanceDecoded(range.first)
        while (rawIdx <= range.last && rawIdx < rawBody.length) {
            map[rawIdx] = normIdx
            rawIdx += 1
            normIdx += 1
        }
    }
    advanceDecoded(rawBody.length)
    map[rawBody.length] = normIdx
    return map.takeIf { normIdx == normalizedBody.length }
}

/**
 * Maps every UTF-16 index in [normalizedBody] (`0..normalizedBody.length`) to its UTF-16 index in
 * [parserContent], accounting for `markdownParserLiteralHtmlContent`'s `<` -> `\<` and `>` -> `\>`
 * escapes outside fences, inline code, and autolinks.
 */
private fun buildNormalizedToParserOffsetMap(
    normalizedBody: String,
    parserContent: String,
): IntArray? {
    val map = IntArray(normalizedBody.length + 1)
    var src = 0
    var dst = 0
    while (src < normalizedBody.length && dst < parserContent.length) {
        map[src] = dst
        when {
            normalizedBody[src] == parserContent[dst] -> {
                src += 1
                dst += 1
            }
            parserContent[dst] == '\\' &&
                dst + 1 < parserContent.length &&
                (parserContent[dst + 1] == '<' || parserContent[dst + 1] == '>') &&
                normalizedBody[src] == parserContent[dst + 1] -> {
                src += 1
                dst += 2
            }
            else -> return null
        }
    }
    if (src != normalizedBody.length || dst != parserContent.length) return null
    map[normalizedBody.length] = dst
    return map
}
