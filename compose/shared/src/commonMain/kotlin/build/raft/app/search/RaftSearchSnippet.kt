package build.raft.app

/**
 * Line-for-line port of raft-shared `buildSearchSnippet` (packages/shared/src/searchSnippet.ts),
 * the one search snippet cut the server (message previews) and the web (channel-description
 * subtitle of a destination hit) share. Pinned to [RAFT_SEARCH_SNIPPET_SLOCK_SHA]; vectors in
 * `RaftSearchSnippetVectorTest`, guarded by `scripts/check_search_snippet_vector.sh`.
 *
 * The window keeps context around the first hit by DISPLAY WIDTH (CJK / fullwidth / wide emoji
 * count 2), never truncates the hit or splits a surrogate pair, moves a cut inside a Latin word to
 * the nearest space within [RAFT_SEARCH_SNIPPET_WORD_BOUNDARY_REACH], drops leading separators and
 * punctuation ("@" and "#" are kept) and marks an elided side with "…".
 *
 * Indices are UTF-16 units, as in the TypeScript source.
 */
internal const val RAFT_SEARCH_SNIPPET_SLOCK_SHA = "f73ab2dde90f39cae8dcd815a5cb9805d47bef7c"

internal const val RAFT_SEARCH_SNIPPET_WIDTH_BEFORE_HIT = 24
internal const val RAFT_SEARCH_SNIPPET_WIDTH_AFTER_HIT = 136

/** A snippet with no hit keeps its first 180 characters. */
internal const val RAFT_SEARCH_SNIPPET_MAX_LENGTH_WITHOUT_HIT = 180

/** How far (in UTF-16 units) a cut may move to reach a word boundary. */
internal const val RAFT_SEARCH_SNIPPET_WORD_BOUNDARY_REACH = 12
internal const val RAFT_SEARCH_SNIPPET_ELLIPSIS = "…"

/** East Asian Wide / Fullwidth code points take two columns, everything else one. */
internal fun raftSearchSnippetCharWidth(codePoint: Int): Int =
    if (
        codePoint in 0x1100..0x115f || // Hangul Jamo initials
        codePoint in 0x2e80..0x303e || // CJK radicals, Kangxi, CJK symbols and punctuation
        codePoint in 0x3041..0x33ff || // kana, Bopomofo, Hangul compatibility, CJK compatibility
        codePoint in 0x3400..0x4dbf || // CJK extension A
        codePoint in 0x4e00..0x9fff || // CJK unified ideographs
        codePoint in 0xa000..0xa4cf || // Yi
        codePoint in 0xac00..0xd7a3 || // Hangul syllables
        codePoint in 0xf900..0xfaff || // CJK compatibility ideographs
        codePoint in 0xfe30..0xfe4f || // CJK compatibility forms
        codePoint in 0xff00..0xff60 || // fullwidth forms
        codePoint in 0xffe0..0xffe6 || // fullwidth signs
        codePoint in 0x1f300..0x1f64f || // emoji pictographs
        codePoint in 0x1f900..0x1f9ff || // supplemental symbols and pictographs
        codePoint in 0x20000..0x3fffd // CJK extensions B and later
    ) {
        2
    } else {
        1
    }

private fun isLowSurrogate(code: Int): Boolean = code in 0xdc00..0xdfff

private fun isHighSurrogate(code: Int): Boolean = code in 0xd800..0xdbff

/** JS `String.prototype.codePointAt`: the code point at [index], a lone surrogate as itself. */
private fun String.jsCodePointAt(index: Int): Int {
    val high = this[index].code
    if (isHighSurrogate(high) && index + 1 < length) {
        val low = this[index + 1].code
        if (isLowSurrogate(low)) return ((high - 0xd800) shl 10) + (low - 0xdc00) + 0x10000
    }
    return high
}

/** JS regex `\s` (and `String.prototype.trim`): Kotlin's `\s` is ASCII-only on the JVM. */
private fun isJsSpace(char: Char?): Boolean =
    when (char?.code) {
        null -> false
        in 0x09..0x0d, 0x20, 0xa0, 0x1680, in 0x2000..0x200a, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000, 0xfeff -> true
        else -> false
    }

/** Steps back from [index] over whole code points while they fit in [budget] columns. */
private fun snippetStartBefore(text: String, index: Int, budget: Int): Int {
    var start = index
    var width = 0
    while (start > 0) {
        val previous =
            if (start >= 2 && isLowSurrogate(text[start - 1].code) && isHighSurrogate(text[start - 2].code)) {
                start - 2
            } else {
                start - 1
            }
        width += raftSearchSnippetCharWidth(text.jsCodePointAt(previous))
        if (width > budget) break
        start = previous
    }
    return start
}

/** Steps forward from [index] over whole code points while they fit in [budget] columns. */
private fun snippetEndAfter(text: String, index: Int, budget: Int): Int {
    var end = index
    var width = 0
    while (end < text.length) {
        val codePoint = text.jsCodePointAt(end)
        width += raftSearchSnippetCharWidth(codePoint)
        if (width > budget) break
        end += if (codePoint > 0xffff) 2 else 1
    }
    return end
}

// What a cut can leave at the start of the kept text: spaces, separators and closing / sentence
// punctuation. `@` and `#` are not here: they belong to a following handle or channel name.
private const val LEADING_DROP_CHARS = "，。、；：！？,.;:!?）)]】」』》〉”’\"…·"

// What a cut can leave at the end before "…": spaces and separators.
private const val TRAILING_DROP_CHARS = "，、；：,;:·"

private fun isLeadingDrop(char: Char?): Boolean = char != null && (isJsSpace(char) || char in LEADING_DROP_CHARS)

private fun isTrailingDrop(char: Char?): Boolean = char != null && (isJsSpace(char) || char in TRAILING_DROP_CHARS)

/** `[\p{L}\p{N}\p{M}_'’-]` */
private fun matchesNarrowWordChar(char: Char): Boolean =
    when (char.category) {
        CharCategory.UPPERCASE_LETTER, CharCategory.LOWERCASE_LETTER, CharCategory.TITLECASE_LETTER,
        CharCategory.MODIFIER_LETTER, CharCategory.OTHER_LETTER,
        CharCategory.DECIMAL_DIGIT_NUMBER, CharCategory.LETTER_NUMBER, CharCategory.OTHER_NUMBER,
        CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK,
        -> true
        else -> char == '_' || char == '\'' || char == '’' || char == '-'
    }

/** A narrow (Latin-like) letter or digit: a cut between two of these is inside a word. */
private fun isNarrowWordChar(char: Char?): Boolean {
    if (char == null) return false
    return raftSearchSnippetCharWidth(char.code) == 1 && matchesNarrowWordChar(char)
}

/** Moves a start cut inside a word forward past the next space (within reach, never past [limit]). */
private fun startOnWordBoundary(text: String, start: Int, limit: Int): Int {
    if (start <= 0 || !isNarrowWordChar(text.getOrNull(start - 1)) || !isNarrowWordChar(text.getOrNull(start))) return start
    val reach = minOf(limit, start + RAFT_SEARCH_SNIPPET_WORD_BOUNDARY_REACH)
    for (index in start until reach) {
        if (isJsSpace(text.getOrNull(index))) return index + 1
    }
    return start
}

/** Moves an end cut inside a word back to the previous space (within reach, never before [limit]). */
private fun endOnWordBoundary(text: String, end: Int, limit: Int): Int {
    if (end >= text.length || !isNarrowWordChar(text.getOrNull(end - 1)) || !isNarrowWordChar(text.getOrNull(end))) return end
    val reach = maxOf(limit, end - RAFT_SEARCH_SNIPPET_WORD_BOUNDARY_REACH)
    var index = end - 1
    while (index >= reach) {
        if (isJsSpace(text.getOrNull(index))) return index
        index -= 1
    }
    return end
}

private fun dropLeading(text: String, start: Int, limit: Int): Int {
    var next = start
    while (next < limit && isLeadingDrop(text.getOrNull(next))) next += 1
    return next
}

private fun dropTrailing(text: String, end: Int, limit: Int): Int {
    var next = end
    while (next > limit && isTrailingDrop(text.getOrNull(next - 1))) next -= 1
    return next
}

/** `content.replace(/\s+/g, " ").trim()` with JS whitespace. */
private fun compactWhitespace(content: String): String {
    val out = StringBuilder()
    var inSpace = false
    for (char in content) {
        if (isJsSpace(char)) {
            if (!inSpace) out.append(' ')
            inSpace = true
        } else {
            out.append(char)
            inSpace = false
        }
    }
    return out.toString().trim(' ')
}

private class SnippetHit(val index: Int, val length: Int)

/** The index (and token length) of the first case-insensitive hit of any token, or index -1. */
private fun findFirstHit(text: String, tokens: List<String>): SnippetHit {
    val lower = text.lowercase()
    var index = -1
    var length = 0
    for (token in tokens) {
        if (token.isEmpty()) continue
        val at = lower.indexOf(token.lowercase())
        if (at >= 0 && (index == -1 || at < index)) {
            index = at
            length = token.length
        }
    }
    return SnippetHit(index, length)
}

/**
 * The search result snippet of [content] for [tokens]: whitespace collapsed, the first hit kept
 * whole and in view with the display-width context around it, edges on word boundaries and
 * elided sides marked with "…". Content with no hit keeps its start (up to 180 characters).
 */
internal fun buildSearchSnippet(content: String, tokens: List<String>): String {
    val compact = compactWhitespace(content)
    if (compact.isEmpty()) return ""

    val hit = findFirstHit(compact, tokens)
    if (hit.index == -1) {
        if (compact.length <= RAFT_SEARCH_SNIPPET_MAX_LENGTH_WITHOUT_HIT) return compact
        var end = RAFT_SEARCH_SNIPPET_MAX_LENGTH_WITHOUT_HIT - 1
        if (isHighSurrogate(compact[end - 1].code)) end -= 1
        end = dropTrailing(compact, endOnWordBoundary(compact, end, 0), 0)
        return compact.substring(0, end) + RAFT_SEARCH_SNIPPET_ELLIPSIS
    }

    val hitStart = hit.index
    val hitEnd = hit.index + hit.length
    var start = snippetStartBefore(compact, hitStart, RAFT_SEARCH_SNIPPET_WIDTH_BEFORE_HIT)
    if (start > 0) start = dropLeading(compact, startOnWordBoundary(compact, start, hitStart), hitStart)
    var end = snippetEndAfter(compact, hitEnd, RAFT_SEARCH_SNIPPET_WIDTH_AFTER_HIT)
    if (end < compact.length) end = dropTrailing(compact, endOnWordBoundary(compact, end, hitEnd), hitEnd)
    val prefix = if (start > 0) RAFT_SEARCH_SNIPPET_ELLIPSIS else ""
    val suffix = if (end < compact.length) RAFT_SEARCH_SNIPPET_ELLIPSIS else ""
    return prefix + compact.substring(start, end) + suffix
}
