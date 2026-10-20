package build.raft.designsystem.components

// Pure text helpers for avatar initials, kept apart from the Avatar composables so model and
// data code can derive a label without loading the avatar's drawing resources.

/**
 * Initials for a person without a photo: the first character of the first
 * [RaftAvatarSize.maxInitials] words of [name], upper-cased. A character is a whole
 * user-perceived one ([raftFirstGrapheme]), so an emoji or an accented letter written
 * with a combining mark is never split.
 */
fun raftAvatarInitials(name: String, size: RaftAvatarSize = RaftAvatarSize.Md): String =
    name.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .take(size.maxInitials)
        .joinToString("") { raftFirstGrapheme(it).uppercase() }

/**
 * The first user-perceived character of [text]: its first code point (both halves of a
 * surrogate pair) plus what attaches to it: combining marks, variation selectors, emoji
 * skin tones, keycaps, tag sequences, a second regional indicator (a flag), and emoji
 * joined with a zero-width joiner. A close approximation of a Unicode grapheme cluster
 * without platform break iterators (commonMain has none).
 */
internal fun raftFirstGrapheme(text: String): String {
    if (text.isEmpty()) return text
    var end = codePointEnd(text, 0)
    val first = codePointAt(text, 0)
    if (isRegionalIndicator(first) && end < text.length && isRegionalIndicator(codePointAt(text, end))) {
        return text.substring(0, codePointEnd(text, end))
    }
    while (end < text.length) {
        val cp = codePointAt(text, end)
        end =
            when {
                isGraphemeExtend(cp) -> codePointEnd(text, end)
                // ZWJ joins the next code point into the same character.
                cp == 0x200D && codePointEnd(text, end) < text.length -> codePointEnd(text, codePointEnd(text, end))
                else -> break
            }
    }
    return text.substring(0, end)
}

private fun codePointAt(text: String, index: Int): Int {
    val high = text[index]
    if (high.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
        return ((high.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00) + 0x10000
    }
    return high.code
}

private fun codePointEnd(text: String, index: Int): Int =
    if (text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) index + 2 else index + 1

private fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

private fun isGraphemeExtend(cp: Int): Boolean =
    cp in 0x0300..0x036F || // combining diacritical marks
        cp in 0x1AB0..0x1AFF ||
        cp in 0x1DC0..0x1DFF ||
        cp in 0x20D0..0x20FF || // combining marks for symbols, the keycap
        cp in 0xFE20..0xFE2F ||
        cp in 0xFE00..0xFE0F || // variation selectors
        cp in 0xE0100..0xE01EF ||
        cp in 0x1F3FB..0x1F3FF || // emoji skin tones
        cp in 0xE0020..0xE007F // tags (subdivision flags)
