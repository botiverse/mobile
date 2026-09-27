package com.tencent.kuiklybase.markdown.model

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/** A source-accurate inline reference recognized by the Raft Markdown dialect. */
data class RaftMarkdownInlineRef(
    val sourceText: String,
    val startOffset: Int,
    val endOffset: Int,
)

/**
 * Raft's GFM extension for `@member`, `#channel`, `#channel:thread`,
 * `dm:@peer:thread`, and numeric `#task` references.
 *
 * GFM parses first and therefore owns nesting. Raft recognition runs only on
 * source ranges not claimed by Markdown links/definitions, images, code,
 * autolinks, or HTML. The dialect does not need to duplicate Markdown
 * delimiter matching or maintain an exclusion scanner for each new construct.
 */
object RaftMarkdownDialect {
    /** Collects refs from the already-built Raft Markdown AST. */
    fun inlineRefs(tree: ASTNode, content: String): List<RaftMarkdownInlineRef> {
        val refs = mutableListOf<RaftMarkdownInlineRef>()
        tree.collectRaftMarkdownInlineRefs(content, refs)
        return refs
    }
}

private fun ASTNode.collectRaftMarkdownInlineRefs(
    content: String,
    result: MutableList<RaftMarkdownInlineRef>,
) {
    if (type in RaftMarkdownOwningTypes) {
        return
    }
    if (type == RaftMarkdownTokenTypes.REFERENCE) {
        result +=
            RaftMarkdownInlineRef(
                sourceText = content.substring(startOffset, endOffset),
                startOffset = startOffset,
                endOffset = endOffset,
            )
        return
    }
    children.forEach { child -> child.collectRaftMarkdownInlineRefs(content, result) }
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

internal fun String.raftMarkdownSyntaxCandidateRanges(start: Int = 0, end: Int = length): List<IntRange> {
    val result = mutableListOf<IntRange>()
    var cursor = start.coerceAtLeast(0)
    val boundedEnd = end.coerceAtMost(length)
    while (cursor < boundedEnd) {
        val range = raftInlineRefRange(cursor)?.takeIf { it.last < boundedEnd }
        if (range != null) {
            result += range
            cursor = range.last + 1
        } else {
            cursor++
        }
    }
    return result
}

private fun String.raftInlineRefRange(start: Int): IntRange? {
    if (start >= length || (this[start] != '#' && this[start] != '@' && !startsWith("dm:@", start, ignoreCase = true))) {
        return null
    }
    if (isRaftMarkdownEscaped(start) || !isRaftInlineRefBoundary(start - 1)) return null
    return when {
        startsWith("dm:@", start, ignoreCase = true) -> raftDirectMessageThreadRange(start)
        this[start] == '@' -> raftMentionRange(start)
        else -> raftHashRange(start)
    }
}

private fun String.raftDirectMessageThreadRange(start: Int): IntRange? {
    val peerStart = start + 4
    if (peerStart >= length || !this[peerStart].isRaftAsciiWordCharacter()) return null
    var peerEnd = peerStart + 1
    var count = 1
    while (peerEnd < length && count < 64 && this[peerEnd].isRaftDirectMessagePeerPart()) {
        peerEnd++
        count++
    }
    // `dm:@name~agent` / `dm:@name~human` pins the peer kind when a human and
    // an agent share a name (Web `RAFT_REF_DM_PEER_PATTERN`). `~` never occurs
    // in a name, so any other suffix is not a DM target: claim the whole
    // malformed token so the app layer renders it as plain text instead of
    // letting the `@name` inside it become a different target.
    if (peerEnd < length && this[peerEnd] == '~') {
        val kindEnd = raftDirectMessagePeerKindEnd(peerEnd)
        if (kindEnd == null) return raftMalformedDirectMessageKindRange(start, peerEnd)
        peerEnd = kindEnd
    }
    // A DM thread is an atomic form. A missing or malformed suffix must fall
    // back to the ordinary @mention candidate inside it, never a partial DM ref.
    if (peerEnd >= length || this[peerEnd] != ':') return null
    val shortStart = peerEnd + 1
    var shortEnd = shortStart
    while (shortEnd < length && shortEnd - shortStart < 8 && this[shortEnd].isRaftShortIdPart()) {
        shortEnd++
    }
    val shortLength = shortEnd - shortStart
    if (shortLength !in 6..8 || (shortEnd < length && this[shortEnd].isRaftShortIdPart())) return null
    return start until shortEnd
}

private val RaftDirectMessagePeerKinds = listOf("agent", "human")

/** End offset after a valid `~agent` / `~human` suffix starting at [tildeIndex], or null. */
private fun String.raftDirectMessagePeerKindEnd(tildeIndex: Int): Int? {
    val kindStart = tildeIndex + 1
    val kind = RaftDirectMessagePeerKinds.firstOrNull { startsWith(it, kindStart) } ?: return null
    val kindEnd = kindStart + kind.length
    if (kindEnd < length && (this[kindEnd].isRaftDirectMessagePeerPart() || this[kindEnd] == '~')) return null
    return kindEnd
}

/**
 * `dm:@name~<unknown>[:short]`, mirroring Web `createRaftMalformedDmKindRefRegex`
 * (`dm:@[\w.-]+~[\w.~-]*(?::[\da-f]{6,8})?`).
 */
private fun String.raftMalformedDirectMessageKindRange(start: Int, tildeIndex: Int): IntRange {
    var end = tildeIndex + 1
    while (end < length && (this[end].isRaftDirectMessagePeerPart() || this[end] == '~')) end++
    if (end < length && this[end] == ':') {
        val shortStart = end + 1
        var shortEnd = shortStart
        while (shortEnd < length && shortEnd - shortStart < 8 && this[shortEnd].isRaftShortIdPart()) shortEnd++
        if (shortEnd - shortStart >= 6) end = shortEnd
    }
    return start until end
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
    // `.` is legal inside a Raft handle, but sentence punctuation is not part
    // of the mention. Keep internal dots and return trailing dots to Markdown
    // text, matching Web/GFM trailing-punctuation behavior.
    while (end > nameStart && this[end - 1] == '.') end--
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
private fun Char.isRaftDirectMessagePeerPart(): Boolean = isRaftAsciiWordCharacter() || this == '.' || this == '-'
private fun Char.isRaftAsciiWordCharacter(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || isDigit() || this == '_'
private fun Char.isRaftChannelPart(): Boolean = isLetterOrDigit() || this == '_' || this == '-'
private fun Char.isRaftShortIdPart(): Boolean = isDigit() || this in 'a'..'f' || this in 'A'..'F'
private fun Char.isRaftTaskTrailingPart(): Boolean = isLetterOrDigit() || this == '_'
