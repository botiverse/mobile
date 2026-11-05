package build.raft.app.markdown

import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes

/**
 * Message text wraps where the web's does (Chrome line breaking in raft-ui MessageItem), not where
 * Android's native breaker would:
 *
 * - After a hyphen inside a word (`read-state`, `#visual-testing` in a link label): Chrome breaks
 *   there, Minikin only treats it as a hyphenation point and never breaks at it while hyphenation
 *   is off. A zero-width space marks the break. Not before a digit (`slice-1`, UAX #14 LB25).
 * - Never right after a slash before a letter or digit (`traversal/layout`, `Android/OHOS`): Chrome
 *   keeps the run whole, Minikin breaks after every slash. A word joiner glues them.
 *
 * Inline code applies the same rules to its own runs (`withRaftInlineCodeWebBreaks`). Both marks
 * are invisible, and copy drops them (`RaftCopyText`).
 */
internal fun String.withRaftWebTextBreaks(): String {
    if (length < 3 || (indexOf('-') < 0 && indexOf('/') < 0)) return this
    val result = StringBuilder(length + 4)
    forEachIndexed { index, char ->
        result.append(char)
        val next = getOrNull(index + 1) ?: return@forEachIndexed
        when (char) {
            '-' -> {
                val previous = getOrNull(index - 1)
                if (previous != null && previous.isLetterOrDigit() && next.isLetter()) {
                    result.append(RAFT_RICHTEXT_INLINE_CODE_CHAR_WRAP_BREAK)
                }
            }
            '/' -> if (next.isRaftAsciiLetterOrDigit()) result.append(RaftWebTextWordJoiner)
        }
    }
    return result.toString()
}

/**
 * [withRaftWebTextBreaks] for the text of [node], outside tables: a table's columns are sized from
 * their cells' words and keep the native breaker.
 */
internal fun String.withRaftWebTextBreaks(node: ASTNode): String =
    if (node.raftInsideTable()) this else withRaftWebTextBreaks()

private fun ASTNode.raftInsideTable(): Boolean {
    var current = parent
    while (current != null) {
        if (current.type == GFMElementTypes.TABLE) return true
        current = current.parent
    }
    return false
}

/**
 * A reference chip (`#channel`, `#对话流专修:661a12bf`, `@Some One`) is one box on the web
 * (`inline-flex … truncate`), so it never breaks inside. A word joiner goes only where the native
 * breakers (Minikin, Skia) could break the label: around a space, after a `/` or a `-`, and
 * between two characters where one is CJK. Never inside a grapheme cluster (a surrogate pair, a
 * ZWJ sequence, a variation selector or a combining mark), so emoji stay whole. A label with none
 * of these stays as it is. Copy and accessibility drop the joiners ([raftCopyText]).
 */
internal fun String.withRaftInlineChipGlue(): String {
    if (none { it == ' ' || it == '/' || it == '-' || it.code >= RaftCjkFirst }) return this
    val result = StringBuilder(length + 8)
    var index = 0
    while (index < length) {
        val codePoint = raftCodePointAt(index)
        val width = if (codePoint > 0xFFFF) 2 else 1
        val nextIndex = index + width
        result.append(this, index, nextIndex)
        if (nextIndex < length) {
            val next = raftCodePointAt(nextIndex)
            if (raftChipBreakBetween(codePoint, next)) result.append(RaftWebTextWordJoiner)
        }
        index = nextIndex
    }
    return result.toString()
}

/** Whether the native breaker could break between [before] and [after] inside a chip label. */
private fun raftChipBreakBetween(before: Int, after: Int): Boolean {
    // Never inside a grapheme cluster.
    if (after.isRaftClusterExtender() || before == RaftZeroWidthJoiner) return false
    return when {
        before == ' '.code || after == ' '.code -> true
        before == '/'.code || before == '-'.code -> true
        else -> before.isRaftCjk() || after.isRaftCjk()
    }
}

private const val RaftWebTextWordJoiner = '\u2060'
private const val RaftZeroWidthJoiner = 0x200D
private const val RaftCjkFirst = 0x2E80

private fun String.raftCodePointAt(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) return ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000
    }
    return high.code
}

/** A code point that joins the one before it into one grapheme (UAX #29 Extend / ZWJ / SpacingMark, roughly). */
private fun Int.isRaftClusterExtender(): Boolean =
    this == RaftZeroWidthJoiner ||
        this in 0xFE00..0xFE0F || // variation selectors (VS16: ❤️)
        this in 0xE0100..0xE01EF ||
        this in 0x1F3FB..0x1F3FF || // emoji skin tones
        this in 0xE0020..0xE007F || // emoji tag sequences
        this in 0x0300..0x036F || // combining diacritics
        this in 0x1AB0..0x1AFF ||
        this in 0x1DC0..0x1DFF ||
        this in 0x20D0..0x20FF || // combining marks for symbols (keycaps)
        this in 0xFE20..0xFE2F ||
        this in 0x3099..0x309A // kana voicing marks

/** CJK ideographs, kana, Hangul, CJK punctuation and full-width forms: a break may fall on either side. */
private fun Int.isRaftCjk(): Boolean =
    this in 0x2E80..0x2FDF ||
        this in 0x3000..0x30FF ||
        this in 0x3100..0x31FF ||
        this in 0x3400..0x4DBF ||
        this in 0x4E00..0x9FFF ||
        this in 0xAC00..0xD7AF ||
        this in 0xF900..0xFAFF ||
        this in 0xFF00..0xFFEF ||
        this in 0x20000..0x3FFFF

private fun Char.isRaftAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
