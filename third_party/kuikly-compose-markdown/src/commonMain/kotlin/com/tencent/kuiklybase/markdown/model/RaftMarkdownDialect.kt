package com.tencent.kuiklybase.markdown.model

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/** A source-accurate inline reference recognized by the Raft Markdown dialect. */
data class RaftMarkdownInlineRef(
    val sourceText: String,
    val startOffset: Int,
    val endOffset: Int,
)

/**
 * Raft's GFM extension for `@member`, `#channel`, `#channel:thread`,
 * `dm:@peer:thread`, numeric `#task`, and conversation-qualified task
 * (`#channel:#7`, `dm:@peer:#7`) and message (`#channel msg=id`,
 * `#channel:thread msg=id`, `dm:@peer msg=id`, `dm:@peer:thread msg=id`) references.
 *
 * GFM parses first and therefore owns nesting. Raft recognition runs only on
 * source ranges not claimed by Markdown links/definitions, images, code,
 * math, autolinks, or HTML. The dialect does not need to duplicate Markdown
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
        // Math is source, like code: an `@name` in a formula is not a mention
        // (web: raft-shared markdownStructure CODE_TYPES, slock #10813).
        GFMElementTypes.INLINE_MATH,
        GFMElementTypes.BLOCK_MATH,
    )

internal fun String.raftMarkdownSyntaxCandidateRanges(start: Int = 0, end: Int = length): List<IntRange> {
    val result = mutableListOf<IntRange>()
    var cursor = start.coerceAtLeast(0)
    val boundedEnd = end.coerceAtMost(length)
    while (cursor < boundedEnd) {
        val range = raftInlineRefRange(cursor, boundedEnd)?.takeIf { it.last < boundedEnd }
        if (range != null) {
            result += range
            cursor = range.last + 1
        } else {
            cursor++
        }
    }
    return result
}

/**
 * The ref starting at [start]. [messageRefs] false reads only its target: the `#channel` of a
 * `#channel msg=id` whose tail the lexer gave to something else.
 */
internal fun String.raftInlineRefRange(start: Int, boundedEnd: Int = length, messageRefs: Boolean = true): IntRange? {
    if (start >= length || (this[start] != '#' && this[start] != '@' && !startsWith("dm:@", start, ignoreCase = true))) {
        return null
    }
    if (isRaftMarkdownEscaped(start)) return null
    return when {
        startsWith("dm:@", start, ignoreCase = true) -> raftDirectMessageRange(start, boundedEnd, messageRefs)
        this[start] == '@' -> if (isRaftMentionBoundary(start - 1)) raftMentionRange(start) else null
        else -> raftHashRange(start, boundedEnd, messageRefs)
    }
}

private fun String.raftDirectMessageRange(start: Int, boundedEnd: Int, messageRefs: Boolean): IntRange? {
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
    // A peer longer than the scan cap is not a DM target at all.
    if (peerEnd < length && (this[peerEnd].isRaftDirectMessagePeerPart() || this[peerEnd] == '~')) return null
    if (messageRefs) raftMessageRefTailEnd(peerEnd, boundedEnd)?.let { end -> return start until end }
    // `dm:@peer:#7` is a task in the DM (Web `createRaftDmTaskRefRegex`, run
    // before the DM thread and bare DM passes).
    raftTaskRefEnd(peerEnd)?.let { taskEnd -> return start until taskEnd }
    // `dm:@peer:short` is the DM thread form. Without a valid short id the
    // token is the plain DM form `dm:@peer` (Web `createRaftDmRefRegex`: the
    // thread pass runs first, then the DM pass claims the peer part only).
    if (peerEnd < length && this[peerEnd] == ':') {
        val shortStart = peerEnd + 1
        var shortEnd = shortStart
        while (shortEnd < length && shortEnd - shortStart < 8 && this[shortEnd].isRaftShortIdPart()) {
            shortEnd++
        }
        val shortLength = shortEnd - shortStart
        if (shortLength in 6..8 && (shortEnd >= length || !this[shortEnd].isRaftShortIdPart())) {
            if (messageRefs) raftMessageRefTailEnd(shortEnd, boundedEnd)?.let { end -> return start until end }
            return start until shortEnd
        }
    }
    return start until peerEnd
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

private fun String.raftHashRange(start: Int, boundedEnd: Int, messageRefs: Boolean): IntRange? {
    val valueStart = start + 1
    if (valueStart >= length) return null
    if (messageRefs) raftChannelMessageRefEnd(valueStart, boundedEnd)?.let { end -> return start until end }
    if (this[valueStart].isDigit()) {
        if (!isRaftTaskRefBoundary(start - 1)) return null
        var end = valueStart + 1
        while (end < length && this[end].isDigit()) end++
        if (end < length && this[end].isRaftAsciiWordCharacter()) return null
        return start until end
    }
    if (!this[valueStart].isLetter()) return null

    var nameEnd = valueStart + 1
    var count = 1
    while (nameEnd < length && count < 64 && this[nameEnd].isRaftChannelPart()) {
        nameEnd++
        count++
    }
    // `#channel:#7` is a task of that channel (Web `createRaftChannelTaskRefRegex`,
    // run before the channel and bare task passes). A `:#` that does not end in a
    // well-formed task number leaves `#channel` as a channel ref, as on the Web.
    raftTaskRefEnd(nameEnd)?.let { taskEnd -> return start until taskEnd }
    if (startsWith(RaftTaskRefSeparator, nameEnd)) return start until nameEnd
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

/**
 * End offset of `name[:short] msg=id` with the name at [nameStart], or null (Web
 * `createRaftMessageRefRegex`). It runs before every other `#` form, as the Web's message pass
 * does, so the whole token is claimed even when the name reads as a number or a short id.
 */
private fun String.raftChannelMessageRefEnd(nameStart: Int, boundedEnd: Int): Int? {
    var nameEnd = nameStart
    while (nameEnd < boundedEnd && this[nameEnd].isRaftChannelPart()) nameEnd++
    if (nameEnd == nameStart || nameEnd >= boundedEnd) return null
    if (this[nameEnd] != ':') return raftMessageRefTailEnd(nameEnd, boundedEnd)
    val shortStart = nameEnd + 1
    var shortEnd = shortStart
    while (shortEnd < boundedEnd && shortEnd - shortStart < 8 && this[shortEnd].isRaftShortIdPart()) shortEnd++
    if (shortEnd - shortStart < 6) return null
    return raftMessageRefTailEnd(shortEnd, boundedEnd)
}

/**
 * End offset of the `\s+msg=<id>` tail at [targetEnd], or null. The id is Web
 * `RAFT_REF_MESSAGE_ID_PATTERN` (`[A-Za-z0-9][A-Za-z0-9-]{1,63}`), which has no right boundary:
 * `msg=abc_d` is the id `abc` followed by text. The whitespace may hold a soft line break (the Web
 * matches inside one text node), but neither a hard break nor a blank line, which end that node.
 */
private fun String.raftMessageRefTailEnd(targetEnd: Int, boundedEnd: Int): Int? {
    var keyStart = targetEnd
    var lineBreaks = 0
    var spacesBeforeBreak = 0
    var spaces = 0
    while (keyStart < boundedEnd && this[keyStart].isWhitespace()) {
        when (this[keyStart]) {
            '\n' -> {
                lineBreaks++
                spacesBeforeBreak = spaces
            }
            ' ' -> spaces++
            '\r' -> Unit
            else -> spaces = 0
        }
        keyStart++
    }
    if (keyStart == targetEnd || lineBreaks > 1 || spacesBeforeBreak >= 2) return null
    if (!startsWith(RaftMessageRefKey, keyStart, ignoreCase = true)) return null
    val idStart = keyStart + RaftMessageRefKey.length
    if (idStart >= boundedEnd || !this[idStart].isRaftMessageIdStart()) return null
    var idEnd = idStart + 1
    while (idEnd < boundedEnd && idEnd - idStart < RaftMessageIdMaxLength && this[idEnd].isRaftMessageIdPart()) idEnd++
    return idEnd.takeIf { it - idStart >= 2 }
}

private const val RaftMessageRefKey = "msg="
private const val RaftMessageIdMaxLength = 64

/**
 * The task ref separator (Web `RAFT_TASK_REF_SEPARATOR`): a task ref is its
 * conversation, `:#`, then the task number (`#proj:#7`, `dm:@peer:#7`).
 */
internal const val RaftTaskRefSeparator = ":#"

/**
 * End offset of a `:#N` task-ref tail starting at [separatorIndex], or null.
 * Mirrors Web `RAFT_REF_TASK_NUMBER_PATTERN` (`[1-9]\d*`) and
 * `RAFT_TASK_REF_END_PATTERN`: the number must end the token, so no letter,
 * digit, `_`, `/`, `-` or a second separator may follow (`#proj:#7a`,
 * `#proj:#7/x` and `#proj:#7:#8` are not task refs).
 */
private fun String.raftTaskRefEnd(separatorIndex: Int): Int? {
    if (!startsWith(RaftTaskRefSeparator, separatorIndex)) return null
    val numberStart = separatorIndex + RaftTaskRefSeparator.length
    if (numberStart >= length || this[numberStart] !in '1'..'9') return null
    var end = numberStart + 1
    while (end < length && this[end].isDigit()) end++
    if (end < length && (this[end].isLetterOrDigit() || this[end] == '_' || this[end] == '/' || this[end] == '-')) return null
    if (startsWith(RaftTaskRefSeparator, end)) return null
    return end
}

// Left boundaries follow Web `raftRefs.ts`, per ref kind:
// - `#channel`, `#channel:short` and `dm:@peer` have none
//   (`createRaftChannelRefRegex` is a bare `#name`), so `去#proj` and `a#b`
//   are channel candidates; only a known channel becomes a chip.
// - `@name` needs a non letter/digit/`_`/`-` before it
//   (`createRaftUserRefRegex`), which keeps `alice@example.com` plain.
// - `#12` needs a non ASCII-word, non-`/` character before it and an ASCII
//   word boundary after it (`createRaftBareTaskRefRegex`, `\w`/`\b` are
//   ASCII), so `去#12` and `#12，` are tasks but `word#2` and `path/#9` are not.
private fun String.isRaftMentionBoundary(index: Int): Boolean =
    index < 0 || !(this[index].isLetterOrDigit() || this[index] == '_' || this[index] == '-')

private fun String.isRaftTaskRefBoundary(index: Int): Boolean =
    index < 0 || !(this[index].isRaftAsciiWordCharacter() || this[index] == '/')

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
private fun Char.isRaftMessageIdStart(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
private fun Char.isRaftMessageIdPart(): Boolean = isRaftMessageIdStart() || this == '-'
private fun Char.isRaftShortIdPart(): Boolean = isDigit() || this in 'a'..'f' || this in 'A'..'F'
