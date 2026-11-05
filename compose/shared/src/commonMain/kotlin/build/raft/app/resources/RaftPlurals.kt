package build.raft.app.resources

import kotlin.math.abs

/*
 * CLDR plural categories for count strings.
 *
 * The catalogs have no <plurals>. English (and es, pt, ko, ja, zh) choose between two keys at the
 * call site: `<name>_one` for a count of 1 and `<name>_many` / `<name>_other` (or the bare name)
 * for the rest. Russian needs one / few / many / other (1, 21, 101 / 2-4, 22-24 / 0, 5-20, 25-30 /
 * fractions), which two keys cannot express, so a catalog string may carry the web's ICU plural
 * syntax for the count it formats:
 *
 *   <string name="thread_message_count_many">{%1$d, plural, one {%1$d сообщение} few {%1$d сообщения} many {%1$d сообщений} other {%1$d сообщения}}</string>
 *
 * The app's string layer ([stringResource], [StringDesc.resolve]) resolves the block after the
 * `%N$d` placeholders are filled in, so the selector is the number itself. A string without a block,
 * which is every English string and every Russian string not yet given few / many forms, is shown
 * as is: Russian falls back to its `_many` / `_other` text for few / many, never to an error.
 * A branch is chosen by `=N` first (`=0`, `=1`), then by category, then `other`.
 */

/** The CLDR plural category of the integer [count] in [language] (`one`, `few`, `many`, `other`). */
internal fun raftPluralCategory(language: String, count: Long): String {
    val n = abs(count)
    return when (language.lowercase()) {
        "ru" -> {
            val mod10 = n % 10
            val mod100 = n % 100
            when {
                mod10 == 1L && mod100 != 11L -> "one"
                mod10 in 2L..4L && mod100 !in 12L..14L -> "few"
                else -> "many"
            }
        }
        "pt", "fr" -> if (n <= 1L) "one" else "other"
        "ja", "ko", "zh" -> "other"
        else -> if (n == 1L) "one" else "other"
    }
}

// The selector is the substituted count: digits, or a count a call site already grouped ("1,200").
private val PluralHeader = Regex("""\{\s*(-?\d[\d,.]*)\s*,\s*plural\s*,""")

/**
 * [text] with every `{N, plural, one {...} few {...} many {...} other {...}}` block replaced by the
 * branch [language] selects for N. Text without a well-formed block is returned unchanged.
 */
internal fun resolveRaftPluralBlocks(text: String, language: String): String {
    if (!text.contains("plural")) return text
    val out = StringBuilder()
    var cursor = 0
    while (cursor < text.length) {
        val header = PluralHeader.find(text, cursor) ?: break
        val parsed = parsePluralBranches(text, header.range.last + 1)
        val count = header.groupValues[1].filter(Char::isDigit).toLongOrNull()?.let { if (header.groupValues[1].startsWith("-")) -it else it }
        val chosen =
            if (parsed == null || count == null) {
                null
            } else {
                val (branches, end) = parsed
                (branches["=${abs(count)}"] ?: branches[raftPluralCategory(language, count)] ?: branches["other"])?.let { it to end }
            }
        if (chosen == null) {
            // Malformed or no usable branch: keep the text through the header and look further on.
            out.append(text, cursor, header.range.last + 1)
            cursor = header.range.last + 1
        } else {
            out.append(text, cursor, header.range.first)
            out.append(chosen.first)
            cursor = chosen.second
        }
    }
    out.append(text, cursor, text.length)
    return out.toString()
}

/** The `selector {branch}` pairs after a header up to the block's closing brace, and the index after it. */
private fun parsePluralBranches(text: String, start: Int): Pair<Map<String, String>, Int>? {
    val branches = LinkedHashMap<String, String>()
    var i = start
    while (true) {
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length) return null
        if (text[i] == '}') return branches to (i + 1)
        val selectorStart = i
        while (i < text.length && !text[i].isWhitespace() && text[i] != '{' && text[i] != '}') i++
        val selector = text.substring(selectorStart, i)
        while (i < text.length && text[i].isWhitespace()) i++
        if (selector.isEmpty() || i >= text.length || text[i] != '{') return null
        var depth = 0
        val bodyStart = i + 1
        while (i < text.length) {
            if (text[i] == '{') depth++
            if (text[i] == '}') depth--
            i++
            if (depth == 0) break
        }
        if (depth != 0) return null
        branches[selector] = text.substring(bodyStart, i - 1)
    }
}
