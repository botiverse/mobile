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
 * A reference chip (`#channel`, `#channel:thread`, `@Some One`) is one box on the web
 * (`inline-flex … truncate`), so it never breaks inside: glue its label wherever the native breaker
 * could break it (between CJK characters, at a space, after a slash). A Latin label without a
 * space or slash has no break inside and stays as it is.
 */
internal fun String.withRaftInlineChipGlue(): String {
    if (none { it.isRaftChipBreakable() }) return this
    val result = StringBuilder(length * 2)
    forEachIndexed { index, char ->
        if (index > 0 && !char.isLowSurrogate()) result.append(RaftWebTextWordJoiner)
        result.append(char)
    }
    return result.toString()
}

private const val RaftWebTextWordJoiner = '⁠'

private fun Char.isRaftAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

// A hyphen is no break for the native breaker (see above), so `@Android-Developer-4` stays as it is.
private fun Char.isRaftChipBreakable(): Boolean =
    isWhitespace() || this == '/' || code >= 0x2E80
