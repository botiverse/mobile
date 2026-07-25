package com.tencent.kuiklybase.markdown.model

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/** A source-accurate inline reference recognized by the Raft Markdown dialect. */
data class RaftMarkdownInlineRef(
    val sourceText: String,
    val startOffset: Int,
    val endOffset: Int,
)

/**
 * Raft's GFM extension for `@member`, `#channel`, `#channel:thread`, and
 * numeric `#task` references.
 *
 * GFM parses first and therefore owns nesting. Raft recognition runs only on
 * source ranges not claimed by Markdown links/definitions, images, code,
 * autolinks, or HTML. The dialect does not need to duplicate Markdown
 * delimiter matching or maintain an exclusion scanner for each new construct.
 */
object RaftMarkdownDialect {
    fun parseInlineRefs(content: String): List<RaftMarkdownInlineRef> {
        if (!content.contains('#') && !content.contains('@')) return emptyList()
        val tree = MarkdownParser(SlockGFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
        val ownedRanges = buildList { tree.collectRaftMarkdownOwnedRanges(this) }
        val refs = mutableListOf<RaftMarkdownInlineRef>()
        var cursor = 0
        while (cursor < content.length) {
            val range = content.raftInlineRefRange(cursor)
            if (range != null && ownedRanges.none { owned -> range.overlaps(owned) }) {
                refs +=
                    RaftMarkdownInlineRef(
                        sourceText = content.substring(range),
                        startOffset = range.first,
                        endOffset = range.last + 1,
                    )
                cursor = range.last + 1
            } else {
                cursor++
            }
        }
        return refs
    }
}

private fun ASTNode.collectRaftMarkdownOwnedRanges(result: MutableList<IntRange>) {
    if (type in RaftMarkdownOwningTypes) {
        result += startOffset until endOffset
        return
    }
    children.forEach { child -> child.collectRaftMarkdownOwnedRanges(result) }
}

private val RaftMarkdownOwningTypes: Set<IElementType> =
    setOf(
        MarkdownElementTypes.CODE_SPAN,
        MarkdownElementTypes.CODE_BLOCK,
        MarkdownElementTypes.CODE_FENCE,
        MarkdownElementTypes.HTML_BLOCK,
        MarkdownElementTypes.AUTOLINK,
        MarkdownElementTypes.LINK_DEFINITION,
        MarkdownElementTypes.INLINE_LINK,
        MarkdownElementTypes.SHORT_REFERENCE_LINK,
        MarkdownElementTypes.FULL_REFERENCE_LINK,
        MarkdownElementTypes.IMAGE,
        MarkdownTokenTypes.HTML_TAG,
        GFMTokenTypes.GFM_AUTOLINK,
    )

private fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last

private fun String.raftInlineRefRange(start: Int): IntRange? {
    if (start >= length || (this[start] != '#' && this[start] != '@')) return null
    if (isRaftMarkdownEscaped(start) || !isRaftInlineRefBoundary(start - 1)) return null
    return if (this[start] == '@') raftMentionRange(start) else raftHashRange(start)
}

private fun String.raftMentionRange(start: Int): IntRange? {
    val nameStart = start + 1
    if (nameStart >= length || !this[nameStart].isLetterOrDigit()) return null
    var end = nameStart + 1
    var count = 1
    while (end < length && count < 64 && this[end].isRaftMentionPart()) {
        end++
        count++
    }
    return start until end
}

private fun String.raftHashRange(start: Int): IntRange? {
    val valueStart = start + 1
    if (valueStart >= length) return null
    if (this[valueStart].isDigit()) {
        var end = valueStart + 1
        while (end < length && this[end].isDigit()) end++
        if (end < length && this[end].isRaftTaskTrailingPart()) return null
        return start until end
    }
    if (!this[valueStart].isLetter()) return null

    var nameEnd = valueStart + 1
    var count = 1
    while (nameEnd < length && count < 64 && this[nameEnd].isRaftChannelPart()) {
        nameEnd++
        count++
    }
    val shortStart = nameEnd + 1
    if (nameEnd < length && this[nameEnd] == ':' && shortStart < length) {
        if (this[shortStart].isWhitespace()) return start until nameEnd
        var shortEnd = shortStart
        while (shortEnd < length && shortEnd - shortStart < 8 && this[shortEnd].isRaftShortIdPart()) {
            shortEnd++
        }
        val shortLength = shortEnd - shortStart
        if (shortLength in 6..8 && (shortEnd >= length || !this[shortEnd].isRaftShortIdPart())) {
            return start until shortEnd
        }
        return null
    }
    return start until nameEnd
}

private fun String.isRaftInlineRefBoundary(index: Int): Boolean =
    index < 0 || !(this[index].isLetterOrDigit() || this[index] == '_')

private fun String.isRaftMarkdownEscaped(index: Int): Boolean {
    var slashCount = 0
    var cursor = index - 1
    while (cursor >= 0 && this[cursor] == '\\') {
        slashCount++
        cursor--
    }
    return slashCount % 2 == 1
}

private fun Char.isRaftMentionPart(): Boolean = isLetterOrDigit() || this == '_' || this == '.' || this == '-'
private fun Char.isRaftChannelPart(): Boolean = isLetterOrDigit() || this == '_' || this == '-'
private fun Char.isRaftShortIdPart(): Boolean = isDigit() || this in 'a'..'f' || this in 'A'..'F'
private fun Char.isRaftTaskTrailingPart(): Boolean = isLetterOrDigit() || this == '_'
