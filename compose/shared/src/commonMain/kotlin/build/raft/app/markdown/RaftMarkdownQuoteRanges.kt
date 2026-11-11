package build.raft.app.markdown

import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/*
 * An `@` inside a block quote is not a mention (botiverse/slock #14349, squash
 * 8b324501027a4f6050b2f8db49c259ae53dd18c9): a quote repeats someone else's words, so the
 * Server does not notify a quoted handle and the web draws it as text. This is raft-shared
 * `markdownRanges.ts` (`markdownStructure(...).quotes`, `drawnQuoteEnd`) as used by
 * `raftRefs.ts` `visitMentionProse`, ported once for every mobile caller: the message
 * renderer's mention chips ([raftMarkdownQuoteRanges] in the token planner), the composer's
 * picked-mention check (`composerStructuredMentionStillAppears`) and the read-progress
 * fallback that reads mentions from a body.
 */

/**
 * The top-level block quotes of [source] where the web draws them, by source offset (start
 * inclusive, end exclusive). A quote nested in another is inside its range. Empty without a `>`.
 *
 * Parsed with CommonMark's lazy continuation (as the web's mdast is), then cut at
 * [raftDrawnQuoteEnd]. The renderer's own parse ends a quote at any unmarked line; only these
 * ranges decide whether an `@handle` is quoted, so a chip is drawn exactly where the Server notifies.
 */
internal fun raftMarkdownQuoteRanges(source: String): List<RaftMarkdownQuoteRange> {
    if ('>' !in source) return emptyList()
    val tree = MarkdownParser(RaftGFMFlavourDescriptor(quoteEndsAtUnmarkedLine = false)).buildMarkdownTreeFromString(source)
    val quotes = mutableListOf<RaftMarkdownQuoteRange>()
    fun visit(node: ASTNode) {
        when (node.type) {
            in RaftQuoteOpaqueTypes -> return
            MarkdownElementTypes.BLOCK_QUOTE -> {
                quotes += RaftMarkdownQuoteRange(node.startOffset, raftDrawnQuoteEnd(source, node) ?: node.endOffset)
                return
            }
        }
        node.children.forEach(::visit)
    }
    visit(tree)
    return quotes
}

/** A quote's source range: [start] inclusive, [end] exclusive. */
internal data class RaftMarkdownQuoteRange(val start: Int, val end: Int)

/** Whether the source range [start] until [end] lies inside one of these quotes. */
internal fun List<RaftMarkdownQuoteRange>.quotes(start: Int, end: Int): Boolean =
    any { quote -> start >= quote.start && end <= quote.end }

/** [source] with every quoted character but line breaks blanked to a space (offsets unchanged). */
internal fun raftMarkdownWithoutQuotes(source: String): String {
    val quotes = raftMarkdownQuoteRanges(source)
    if (quotes.isEmpty()) return source
    val chars = source.toCharArray()
    for (quote in quotes) {
        for (index in quote.start until quote.end.coerceAtMost(chars.size)) {
            if (chars[index] != '\n' && chars[index] != '\r') chars[index] = ' '
        }
    }
    return chars.concatToString()
}

/**
 * raft-shared `drawnQuoteEnd`: where a top-level quote ends as it is drawn, or null when that is
 * where the parser ends it. CommonMark's lazy continuation pulls an unquoted line after
 * `> quote` into the quote; writers mean a new paragraph. So the quote ends where the first line
 * of its last paragraph that does not start with `>` starts (after a hard break too, and inside
 * emphasis). A code span, formula, HTML or link that line starts inside is kept whole in the quote.
 * A quote that ends in a nested quote keeps its lazy lines.
 */
internal fun raftDrawnQuoteEnd(source: String, quote: ASTNode): Int? {
    val last = quote.children.lastOrNull { it.type !in RaftQuoteTrivia } ?: return null
    if (last.type != MarkdownElementTypes.PARAGRAPH) return null
    val paragraphEnd = last.endOffset
    var newline = source.indexOf('\n', last.startOffset)
    while (newline != -1 && newline < paragraphEnd) {
        val lineEnd = source.indexOf('\n', newline + 1).let { if (it == -1) source.length else it }
        if (!RaftQuotedLine.containsMatchIn(source.substring(newline + 1, lineEnd))) {
            val end = pastAtomicInline(last, newline + 1)
            return if (end < paragraphEnd) end else null
        }
        newline = source.indexOf('\n', newline + 1)
    }
    return null
}

/** [offset], or the end of the atomic inline node under [parent] it falls inside. */
private fun pastAtomicInline(parent: ASTNode, offset: Int): Int {
    for (child in parent.children) {
        if (offset <= child.startOffset || offset >= child.endOffset) continue
        if (child.type in RaftAtomicInlineTypes) return child.endOffset
        return if (child.children.isNotEmpty()) pastAtomicInline(child, offset) else offset
    }
    return offset
}

private val RaftQuotedLine = Regex("""^\s*>""")

/** Tokens a block quote holds besides its blocks: its `>` markers and line breaks. */
private val RaftQuoteTrivia: Set<IElementType> =
    setOf(MarkdownTokenTypes.BLOCK_QUOTE, MarkdownTokenTypes.EOL, MarkdownTokenTypes.WHITE_SPACE)

/** Code and math: a `>` in them is source (raft-shared `CODE_TYPES` are not visited into). */
private val RaftQuoteOpaqueTypes: Set<IElementType> =
    setOf(
        MarkdownElementTypes.CODE_FENCE,
        MarkdownElementTypes.CODE_SPAN,
        MarkdownElementTypes.HTML_BLOCK,
        GFMElementTypes.BLOCK_MATH,
        GFMElementTypes.INLINE_MATH,
    )

/** raft-shared `ATOMIC_INLINE_TYPES`: inline code, math, HTML and links are drawn whole. */
private val RaftAtomicInlineTypes: Set<IElementType> =
    setOf(
        MarkdownElementTypes.CODE_SPAN,
        GFMElementTypes.INLINE_MATH,
        MarkdownTokenTypes.HTML_TAG,
        MarkdownElementTypes.INLINE_LINK,
        MarkdownElementTypes.FULL_REFERENCE_LINK,
        MarkdownElementTypes.SHORT_REFERENCE_LINK,
        MarkdownElementTypes.AUTOLINK,
        MarkdownElementTypes.IMAGE,
        MarkdownElementTypes.LINK_DEFINITION,
        GFMTokenTypes.GFM_AUTOLINK,
    )
