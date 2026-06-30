package com.tencent.kuiklybase.markdown.model

data class MarkdownParserContent(
    val sourceContent: String,
    val parserContent: String
) {
    companion object {
        fun from(content: String): MarkdownParserContent =
            MarkdownParserContent(
                sourceContent = content,
                parserContent = content.markdownParserLiteralHtmlContent()
            )
    }
}

/**
 * Parser-input normalization for Slock's markdown renderer.
 *
 * The product treats raw HTML as literal message text. Escape HTML tags and
 * comments before the Markdown parser sees them so the parser never enters
 * HTML block/comment mode and therefore cannot swallow same-line text.
 */
private fun String.markdownParserLiteralHtmlContent(): String {
    if (!contains('<')) return this

    val protectedRanges = markdownHtmlProtectionRanges()
    val builder = StringBuilder(length)
    var index = 0
    while (index < length) {
        val tagMatch = MarkdownHtmlTagRegex.find(this, index)
        val commentMatch = MarkdownHtmlCommentRegex.find(this, index)
        val nextMatch =
            when {
                tagMatch == null -> commentMatch
                commentMatch == null -> tagMatch
                commentMatch.range.first < tagMatch.range.first -> commentMatch
                else -> tagMatch
            }

        if (nextMatch == null) {
            builder.append(substring(index))
            break
        }

        builder.append(substring(index, nextMatch.range.first))
        if (protectedRanges.any { nextMatch.range.first in it } || nextMatch.isAlreadyEscapedIn(this)) {
            builder.append(nextMatch.value)
        } else {
            builder.append(nextMatch.value.escapeMarkdownHtmlLiteralText())
        }
        index = nextMatch.range.last + 1
    }
    return builder.toString()
}

private fun String.markdownHtmlProtectionRanges(): List<IntRange> =
    buildList {
        addAll(markdownFenceRanges())
        addAll(markdownInlineCodeRanges())
        addAll(MarkdownAutolinkRegex.findAll(this@markdownHtmlProtectionRanges).map { it.range })
    }

private fun String.markdownFenceRanges(): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    val fenceRegex = Regex("""(?m)^(```|~~~).*$""")
    var searchIndex = 0
    while (searchIndex < length) {
        val start = fenceRegex.find(this, searchIndex) ?: break
        val marker = start.value.take(3)
        val end = fenceRegex.find(this, start.range.last + 1) {
            it.value.startsWith(marker)
        }
        if (end == null) {
            ranges += start.range.first until length
            break
        }
        ranges += start.range.first..end.range.last
        searchIndex = end.range.last + 1
    }
    return ranges
}

private fun String.markdownInlineCodeRanges(): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var index = 0
    while (index < length) {
        val start = indexOf('`', startIndex = index)
        if (start < 0) break
        var markerLength = 1
        while (start + markerLength < length && this[start + markerLength] == '`') {
            markerLength++
        }
        val marker = substring(start, start + markerLength)
        val end = indexOf(marker, startIndex = start + markerLength)
        if (end < 0) break
        ranges += start until (end + markerLength)
        index = end + markerLength
    }
    return ranges
}

private fun Regex.find(input: CharSequence, startIndex: Int, predicate: (MatchResult) -> Boolean): MatchResult? {
    var match = find(input, startIndex)
    while (match != null) {
        if (predicate(match)) return match
        match = match.next()
    }
    return null
}

private fun MatchResult.isAlreadyEscapedIn(content: String): Boolean {
    val previous = range.first - 1
    return previous >= 0 && content[previous] == '\\'
}

private fun String.escapeMarkdownHtmlLiteralText(): String =
    replace("<", "\\<").replace(">", "\\>")

private val MarkdownHtmlTagRegex =
    Regex("""</?[A-Za-z][A-Za-z0-9:-]*(?:\s+[^<>]*)?>""")

private val MarkdownHtmlCommentRegex =
    Regex("""<!--.*?-->""", setOf(RegexOption.DOT_MATCHES_ALL))

private val MarkdownAutolinkRegex =
    Regex("""<https?://[^>\s]+>""")
