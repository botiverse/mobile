package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

/*
 * Quote replies (botiverse/slock #15536, #proj-chat:#34). A quote of a message
 * in the same conversation is written into the reply's Markdown as a blockquote whose last line
 * names the quoted message:
 *
 *     > the quoted excerpt
 *     > msg=1a2b3c4d
 *
 *     my reply
 *
 * Line-for-line port of botiverse/slock `packages/shared/src/quoteReply.ts` (staging
 * b0b65a4d1263c9bd39adf28a2f2553c5bacb1913): the parser and its fast pre-check, the composer's
 * serializer, the previews' ref stripping and the whole-message excerpt. The preview text
 * ([extractQuoteReplyPreviewText], and [stripQuoteReplyRefsForPreview] hardened for cut and folded
 * previews) follows #16158, quoteReply.ts:282-491 at staging 87ab82603b3c530eb8d96c4359c8109b662de793.
 * The server's excerpt verification stays on the server. `\s` and `trim()` are JavaScript's
 * ([isJsWhitespace]), so an unusual space splits lines and blocks exactly as it does on the web.
 */

/**
 * web `MAX_INLINE_QUOTES_PER_MESSAGE`: the server resolves at most this many quote blocks of one
 * message; any further one stays a plain blockquote.
 */
@RaftCoreInternalApi const val MAX_INLINE_QUOTES_PER_MESSAGE: Int = 10

/** web `ParsedMessageQuoteSegment`: a message's Markdown as its text runs and same-conversation quote blocks, in order. */
@RaftCoreInternalApi sealed interface MessageQuoteSegment {
    data class Text(val text: String) : MessageQuoteSegment

    /** web `ParsedQuoteBlock`. */
    data class QuoteBlock(
        /** 0-based among the message's quote blocks: the key of its `resolvedQuotes` entry. */
        val blockIndex: Int,
        /** The quoted lines without their `> ` markers and without the trailing `msg=` line. */
        val excerpt: String,
        /** The `msg=` reference, lowercased: an 8-hex short id or a full UUID. */
        val refId: String,
        /** The blockquote's lines as written. */
        val rawMarkdown: String,
    ) : MessageQuoteSegment
}

/** JavaScript `\s`: what the web's patterns and `String.prototype.trim` treat as white space. */
private fun Char.isJsWhitespace(): Boolean =
    when (this) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF' -> true
        else -> this in '\u2000'..'\u200A'
    }

private fun String.jsTrim(): String = trim { it.isJsWhitespace() }

private fun String.jsTrimEnd(): String = trimEnd { it.isJsWhitespace() }

/** JavaScript `\s` as a character class. */
private const val JsWhitespaceClass = "[\t\n\u000B\u000C\r \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]"

/** web `hasSameConversationQuoteRef`'s pattern: a quote line holding only a `msg=` reference. */
private val SameConversationQuoteRefLine =
    Regex(
        "(?:^|\n)$JsWhitespaceClass{0,3}>$JsWhitespaceClass*msg=[0-9a-f]{8}" +
            "(?:-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})?$JsWhitespaceClass*(?:\r?\n|\$)",
        RegexOption.IGNORE_CASE,
    )

/** web `SAME_CONV_QUOTE_REF_RE`, matched against a whole trimmed quote line. */
private val SameConversationQuoteRef =
    Regex("msg=([0-9a-f]{8}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})", RegexOption.IGNORE_CASE)

private val MessageUuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)

private val QuoteLineBreak = Regex("\r?\n")

private val QuoteCarriageReturn = Regex("\r\n?")

/**
 * web `hasSameConversationQuoteRef`: a fast pre-check, so a message without a `> msg=<id>` line is
 * never scanned line by line. True does not promise a quote block ([parseMessageQuoteSegments] decides).
 */
@RaftCoreInternalApi fun hasSameConversationQuoteRef(content: String): Boolean {
    if (content.isEmpty() || !content.contains("msg=") || !content.contains(">")) return false
    return SameConversationQuoteRefLine.containsMatchIn(content)
}

/** A message id a quote can point at and whose quotes can be read: a server message's UUID (a local send has none yet). */
@RaftCoreInternalApi fun isQuoteReplyMessageId(id: String): Boolean = MessageUuid.matches(id)

/** web `BLOCKQUOTE_LINE_RE` (`/^\s{0,3}> ?(.*)$/`): the line's text inside the quote, or null when it is no quote line. */
private fun blockquoteLineContent(line: String): String? {
    var index = 0
    while (index < line.length && index < 3 && line[index].isJsWhitespace()) index++
    if (index >= line.length || line[index] != '>') return null
    index++
    if (index < line.length && line[index] == ' ') index++
    val content = line.substring(index)
    // `.` never matches a line terminator and `$` is the line's end: such a line is no quote line.
    if (content.any { it == '\n' || it == '\r' || it == '\u2028' || it == '\u2029' }) return null
    return content
}

/** web `FENCE_OPEN_RE` (`/^\s{0,3}(`{3,}|~{3,})/`): a fence's character, its run's length and where the run ends. */
private class FenceOpen(val char: Char, val length: Int, val end: Int)

private fun fenceOpen(line: String): FenceOpen? {
    var start = 0
    while (start < line.length && start < 3 && line[start].isJsWhitespace()) start++
    if (start >= line.length) return null
    val char = line[start]
    if (char != '`' && char != '~') return null
    var end = start
    while (end < line.length && line[end] == char) end++
    return if (end - start >= 3) FenceOpen(char, end - start, end) else null
}

/** web `^\s{0,3}<char>{<length>,}\s*$`: the line closes the open fence. */
private fun closesFence(line: String, char: Char, length: Int): Boolean {
    var start = 0
    while (start < line.length && start < 3 && line[start].isJsWhitespace()) start++
    var end = start
    while (end < line.length && line[end] == char) end++
    return end - start >= length && line.substring(end).all { it.isJsWhitespace() }
}

/**
 * web `parseMessageQuoteSegments`: the message's Markdown as interleaved text and same-conversation
 * quote blocks (`> <excerpt>` lines ending in `> msg=<id>`). Fenced code is skipped, so a code
 * example holding `> msg=…` is never read as a quote. Every block is returned; the server resolves
 * the first [MAX_INLINE_QUOTES_PER_MESSAGE] of them.
 */
@RaftCoreInternalApi fun parseMessageQuoteSegments(content: String): List<MessageQuoteSegment> {
    if (!hasSameConversationQuoteRef(content)) {
        return if (content.isNotEmpty()) listOf(MessageQuoteSegment.Text(content)) else emptyList()
    }
    val segments = mutableListOf<MessageQuoteSegment>()
    val textLines = mutableListOf<String>()
    val quoteRawLines = mutableListOf<String>()
    val quoteInnerLines = mutableListOf<String>()
    var activeFenceChar: Char? = null
    var activeFenceLength = 0
    var nextBlockIndex = 0

    fun flushText() {
        if (textLines.isEmpty()) return
        val joined = textLines.joinToString("\n")
        if (joined.jsTrim().isNotEmpty()) segments += MessageQuoteSegment.Text(joined.trim('\n'))
        textLines.clear()
    }

    fun flushQuote() {
        if (quoteRawLines.isEmpty()) return
        // Trailing blank quote lines (a bare `>`) are not the last line.
        var lastNonEmpty = quoteInnerLines.size - 1
        while (lastNonEmpty >= 0 && quoteInnerLines[lastNonEmpty].jsTrim().isEmpty()) lastNonEmpty--
        val lastLine = if (lastNonEmpty >= 0) quoteInnerLines[lastNonEmpty].jsTrim() else ""
        val refId = SameConversationQuoteRef.matchEntire(lastLine)?.groupValues?.get(1)
        if (refId != null && lastNonEmpty > 0) {
            var excerptEnd = lastNonEmpty - 1
            while (excerptEnd >= 0 && quoteInnerLines[excerptEnd].jsTrim().isEmpty()) excerptEnd--
            var excerptStart = 0
            while (excerptStart <= excerptEnd && quoteInnerLines[excerptStart].jsTrim().isEmpty()) excerptStart++
            if (excerptStart <= excerptEnd) {
                val excerpt = quoteInnerLines.subList(excerptStart, excerptEnd + 1).joinToString("\n")
                flushText()
                segments +=
                    MessageQuoteSegment.QuoteBlock(
                        blockIndex = nextBlockIndex++,
                        excerpt = excerpt,
                        refId = refId.lowercase(),
                        rawMarkdown = quoteRawLines.joinToString("\n"),
                    )
                quoteRawLines.clear()
                quoteInnerLines.clear()
                return
            }
        }
        // Not a same-conversation quote block: its lines stay in the surrounding text as written.
        textLines += quoteRawLines
        quoteRawLines.clear()
        quoteInnerLines.clear()
    }

    for (line in content.split(QuoteLineBreak)) {
        val fenceChar = activeFenceChar
        if (fenceChar != null) {
            textLines += line
            if (closesFence(line, fenceChar, activeFenceLength)) {
                activeFenceChar = null
                activeFenceLength = 0
            }
            continue
        }
        val quoted = blockquoteLineContent(line)
        if (quoted != null) {
            quoteRawLines += line
            quoteInnerLines += quoted
            continue
        }
        flushQuote()
        val fence = fenceOpen(line)
        // Backtick info strings cannot contain backticks (CommonMark).
        if (fence != null && (fence.char == '~' || !line.substring(fence.end).contains('`'))) {
            activeFenceChar = fence.char
            activeFenceLength = fence.length
        }
        textLines += line
    }
    flushQuote()
    flushText()
    return segments
}

/**
 * web `formatPlainBlockquote`: [excerpt] as a plain Markdown blockquote, without the `msg=` line.
 * What a quote block the server did not resolve shows as.
 */
@RaftCoreInternalApi fun formatPlainBlockquote(excerpt: String): String =
    excerpt.replace(QuoteCarriageReturn, "\n")
        .split("\n")
        .joinToString("\n") { line -> if (line.isNotEmpty()) "> $line" else ">" }

/**
 * web `stripLeadingQuoteReplyBlocks`: the message without the quote blocks it opens with, so quoting
 * a reply quotes its own words rather than what it quoted.
 */
@RaftCoreInternalApi fun stripLeadingQuoteReplyBlocks(content: String): String {
    if (!hasSameConversationQuoteRef(content)) return content
    val segments = parseMessageQuoteSegments(content)
    val start = segments.indexOfFirst { it !is MessageQuoteSegment.QuoteBlock }.let { if (it < 0) segments.size else it }
    if (start == 0) return content
    return segments.drop(start)
        .joinToString("\n\n") { segment ->
            when (segment) {
                is MessageQuoteSegment.Text -> segment.text
                is MessageQuoteSegment.QuoteBlock -> segment.rawMarkdown
            }
        }
        .jsTrim()
}

/** web `AGENT_INBOX_PREVIEW_MAX_CHARS`: a whole-message quote keeps at most this much of its first line. */
private const val WholeMessageQuoteMaxChars = 120

/** web `agentInboxPreview`'s line split. */
private val PreviewLineSplit = Regex("\r\n|[\r\n\u0085\u2028\u2029]")

/** web `AGENT_ONE_LINE_FOLD`: every run of line separators and white space. */
private val OneLineFold = Regex("(?:\r\n|[\n\r\u000B\u000C\u001C\u001D\u001E\u0085\u2028\u2029]|$JsWhitespaceClass)+")

/**
 * web `agentInboxPreview`: the first non-blank line folded to one line, at most
 * [WholeMessageQuoteMaxChars] without splitting a surrogate pair; true when anything was left out.
 */
private fun agentInboxPreview(text: String): Pair<String?, Boolean> {
    if (text.isEmpty()) return null to false
    val firstLine = text.split(PreviewLineSplit).firstOrNull { it.jsTrim().isNotEmpty() } ?: ""
    val folded = firstLine.replace(OneLineFold, " ").jsTrim()
    if (folded.isEmpty()) return null to false
    val cutForLines = firstLine.length < text.jsTrim().length
    if (folded.length <= WholeMessageQuoteMaxChars) return folded to cutForLines
    var cut = WholeMessageQuoteMaxChars
    if (folded[cut - 1].isHighSurrogate()) cut -= 1
    return folded.substring(0, cut) to true
}

/** web `buildWholeMessageQuoteExcerpt`'s answer. */
@RaftCoreInternalApi data class WholeMessageQuoteExcerpt(val excerpt: String, val truncated: Boolean)

/**
 * web `buildWholeMessageQuoteExcerpt`: what quoting a whole message quotes, its own first line (after
 * the quotes it opens with), cut at 120 characters and then ending in `…`.
 */
@RaftCoreInternalApi fun buildWholeMessageQuoteExcerpt(content: String): WholeMessageQuoteExcerpt {
    val source = stripLeadingQuoteReplyBlocks(content).jsTrim().ifEmpty { content.jsTrim() }
    val (preview, truncated) = agentInboxPreview(source)
    if (preview.isNullOrEmpty()) return WholeMessageQuoteExcerpt(excerpt = "", truncated = false)
    return WholeMessageQuoteExcerpt(excerpt = if (truncated) "${preview.jsTrimEnd()}…" else preview, truncated = truncated)
}

/**
 * web `serializeInlineQuoteReply`: the Markdown a quote reply is sent as, `> <excerpt>` lines, then
 * `> msg=<the target's 8-character short id>`, then a blank line and [replyBody] when there is one.
 */
@RaftCoreInternalApi fun serializeInlineQuoteReply(excerpt: String, targetMessageId: String, replyBody: String): String {
    val shortId = targetMessageId.jsTrim().take(8).lowercase()
    val quoteLines =
        excerpt.replace(QuoteCarriageReturn, "\n")
            .jsTrim()
            .split("\n")
            .joinToString("\n") { line ->
                val trimmed = line.jsTrim()
                if (trimmed.isNotEmpty()) "> $trimmed" else ">"
            }
    val body = replyBody.jsTrim()
    return if (body.isNotEmpty()) "$quoteLines\n> msg=$shortId\n\n$body" else "$quoteLines\n> msg=$shortId"
}

/** web `InlineQuoteComposeSegment` (botiverse/slock #16027): one text or quote piece of a multi-quote composer draft. */
@RaftCoreInternalApi sealed interface InlineQuoteComposeSegment {
    data class Text(val text: String) : InlineQuoteComposeSegment

    data class Quote(val excerpt: String, val targetMessageId: String) : InlineQuoteComposeSegment
}

/**
 * web `serializeInlineQuoteBlocks` (botiverse/slock #16027): serialize an ordered sequence of
 * interleaved text and same-conversation quote blocks into the canonical Markdown wire format
 * (`> <excerpt>\n> msg=<shortId>` separated by blank lines), isomorphic with Agent CLI messages.
 */
@RaftCoreInternalApi fun serializeInlineQuoteBlocks(segments: List<InlineQuoteComposeSegment>): String {
    val parts = mutableListOf<String>()
    for (seg in segments) {
        when (seg) {
            is InlineQuoteComposeSegment.Quote -> {
                val serialized = serializeInlineQuoteReply(seg.excerpt, seg.targetMessageId, "")
                if (serialized.isNotEmpty()) parts += serialized
            }
            is InlineQuoteComposeSegment.Text -> {
                val trimmed = seg.text.jsTrim()
                if (trimmed.isNotEmpty()) parts += trimmed
            }
        }
    }
    return parts.joinToString("\n\n")
}

/**
 * web `quoteChipExcerpt` (botiverse/slock #16210, `quoteChipModel.ts`): single-line excerpt for a
 * quote chip; a selected passage (`isPartial`) starts with `…` so it reads differently from a whole
 * message.
 */
@RaftCoreInternalApi fun quoteChipExcerpt(excerpt: String, isPartial: Boolean): String {
    val oneLine = excerpt.replace(JsWhitespaceRun, " ").jsTrim()
    if (!isPartial || oneLine.isEmpty()) return oneLine
    return if (oneLine.startsWith("…")) oneLine else "…$oneLine"
}

/** web `normalizeExcerptKey` (`composerQuoteModel.ts`): duplicate quote comparison key. */
@RaftCoreInternalApi fun normalizeQuoteExcerptKey(excerpt: String): String =
    excerpt.replace(JsWhitespaceRun, " ").jsTrim().lowercase()

/** web `TRUNCATED_QUOTE_REF_LINE_RE`, matched against a whole trimmed quote line: a `msg=` ref a preview cut, ending in `…`. */
private val TruncatedQuoteRefLine =
    Regex("msg=(?:[0-9a-f]{0,8}|[0-9a-f]{8}-[0-9a-f-]{0,28})…", RegexOption.IGNORE_CASE)

/**
 * web `COLLAPSED_QUOTE_REF_TOKEN_RE`: a quote's ref inside one line, where a preview folded the
 * message's lines (` > msg=<id>`, or cut inside it: ` > msg=<prefix>…`). `(?![\s\S])` is
 * JavaScript's `$`, the end of the input only (Kotlin's `$` also matches before a final line break).
 */
private val CollapsedQuoteRefToken =
    Regex(
        "$JsWhitespaceClass+>$JsWhitespaceClass*msg=" +
            "(?:[0-9a-f]{8}(?:-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})?|(?:[0-9a-f]{0,8}|[0-9a-f]{8}-[0-9a-f-]{0,28})…)" +
            "(?=$JsWhitespaceClass|(?![\\s\\S]))",
        RegexOption.IGNORE_CASE,
    )

/** JavaScript `/\s+/g`. */
private val JsWhitespaceRun = Regex("$JsWhitespaceClass+")

/** JavaScript `/\s+>\s+/g`: a quote marker between two words of a folded preview. */
private val FoldedQuoteMarker = Regex("$JsWhitespaceClass+>$JsWhitespaceClass+")

/** web `"extract" | "strip-ref"`: a preview's own words, or its quotes kept as plain blockquotes. */
private enum class QuoteRefCleanup { Extract, StripRef }

/**
 * web `stripTrailingTruncatedQuoteBlock`: a preview cut inside its last quote's `msg=` line
 * (`> excerpt\n> msg=c54e1…`). [QuoteRefCleanup.Extract] keeps the text before that quote, or else
 * the quote's words ending in `…`; [QuoteRefCleanup.StripRef] keeps the quote as a plain blockquote
 * ending in `…`. Anything else, and a ref line inside an open code fence, stays as it is.
 */
private fun stripTrailingTruncatedQuoteBlock(content: String, mode: QuoteRefCleanup): String {
    if (!content.contains('…') || !content.contains("msg=") || !content.contains('>')) return content
    val lines = content.split(QuoteLineBreak)
    var lastNonEmpty = lines.size - 1
    while (lastNonEmpty >= 0 && lines[lastNonEmpty].jsTrim().isEmpty()) lastNonEmpty--
    if (lastNonEmpty < 1) return content
    val lastQuoted = blockquoteLineContent(lines[lastNonEmpty])
    if (lastQuoted == null || !TruncatedQuoteRefLine.matches(lastQuoted.jsTrim())) return content

    // The cut ref line must not sit inside an open fenced code block.
    var activeFenceChar: Char? = null
    var activeFenceLength = 0
    for (index in 0..lastNonEmpty) {
        val line = lines[index]
        val fenceChar = activeFenceChar
        if (fenceChar != null) {
            if (closesFence(line, fenceChar, activeFenceLength)) {
                activeFenceChar = null
                activeFenceLength = 0
            }
            continue
        }
        if (blockquoteLineContent(line) != null) continue
        val fence = fenceOpen(line)
        if (fence != null && (fence.char == '~' || !line.substring(fence.end).contains('`'))) {
            activeFenceChar = fence.char
            activeFenceLength = fence.length
        }
    }
    if (activeFenceChar != null) return content

    var runStart = lastNonEmpty - 1
    while (runStart >= 0 && blockquoteLineContent(lines[runStart]) != null) runStart--
    runStart++
    val excerptLines = (runStart until lastNonEmpty).mapNotNull { blockquoteLineContent(lines[it]) }.toMutableList()
    while (excerptLines.isNotEmpty() && excerptLines.first().jsTrim().isEmpty()) excerptLines.removeAt(0)
    while (excerptLines.isNotEmpty() && excerptLines.last().jsTrim().isEmpty()) excerptLines.removeAt(excerptLines.lastIndex)
    if (excerptLines.isEmpty()) return content

    val before = lines.subList(0, runStart).joinToString("\n").jsTrim()
    val excerptRaw = excerptLines.joinToString("\n")
    val excerptWithEllipsis = if (excerptRaw.endsWith("…")) excerptRaw else "${excerptRaw.jsTrimEnd()}…"
    return when (mode) {
        QuoteRefCleanup.Extract -> before.ifEmpty { excerptWithEllipsis }
        QuoteRefCleanup.StripRef -> {
            val plainQuote = formatPlainBlockquote(excerptWithEllipsis)
            if (before.isNotEmpty()) "$before\n\n$plainQuote" else plainQuote
        }
    }
}

/** web `stripInlineCollapsedQuoteRefs`: every folded ref taken out of a one-line preview (a cut one leaves `…`), spaces folded. */
private fun stripInlineCollapsedQuoteRefs(text: String): String {
    val result = StringBuilder()
    var lastIndex = 0
    for (match in CollapsedQuoteRefToken.findAll(text)) {
        val before = text.substring(lastIndex, match.range.first)
        result.append(if (match.value.endsWith("…")) "${before.jsTrimEnd()}…" else before)
        lastIndex = match.range.last + 1
    }
    if (lastIndex == 0) return text
    result.append(text.substring(lastIndex))
    return result.toString().replace(JsWhitespaceRun, " ").jsTrim()
}

/** The web's lead pattern (`^\s*>\s*`) on `content.slice(from)`: where the words after a quote marker start, or null without one. */
private fun quoteMarkerEnd(content: String, from: Int): Int? {
    var index = from
    while (index < content.length && content[index].isJsWhitespace()) index++
    if (index >= content.length || content[index] != '>') return null
    index++
    while (index < content.length && content[index].isJsWhitespace()) index++
    return index
}

/**
 * web `cleanCollapsedSingleLineQuoteRefs`: a one-line preview of a quote reply, its lines folded
 * (`> excerpt > msg=c54e1234 reply`). [QuoteRefCleanup.Extract] keeps what follows the leading
 * quotes, or else their words; [QuoteRefCleanup.StripRef] only takes the refs out.
 */
private fun cleanCollapsedSingleLineQuoteRefs(content: String, mode: QuoteRefCleanup): String {
    if (mode == QuoteRefCleanup.StripRef) return stripInlineCollapsedQuoteRefs(content)
    if (quoteMarkerEnd(content, 0) == null) return stripInlineCollapsedQuoteRefs(content)
    var cursor = 0
    val excerpts = mutableListOf<String>()
    var lastEndedWithEllipsis = false
    while (cursor < content.length) {
        val afterLead = quoteMarkerEnd(content, cursor) ?: break
        val ref = CollapsedQuoteRefToken.find(content, afterLead) ?: break
        val excerpt = content.substring(afterLead, ref.range.first).replace(FoldedQuoteMarker, " ").jsTrim()
        if (excerpt.isEmpty()) break
        excerpts += excerpt
        lastEndedWithEllipsis = ref.value.endsWith("…")
        cursor = ref.range.last + 1
    }
    if (excerpts.isEmpty()) return stripInlineCollapsedQuoteRefs(content)
    val rest = stripInlineCollapsedQuoteRefs(content.substring(cursor).jsTrim())
    if (rest.isNotEmpty()) return rest
    val fallback = excerpts.joinToString(" ")
    return if (lastEndedWithEllipsis && !fallback.endsWith("…")) "${fallback.jsTrimEnd()}…" else fallback
}

/**
 * web `stripQuoteReplyRefsForPreview`: a message's Markdown with each quote block's `msg=` line taken
 * out and its quote kept as a plain blockquote, for a surface that keeps the whole body (a search
 * hit; a cut Activity body standing in for the message). Also for a preview cut inside a ref or
 * folded to one line.
 */
@RaftCoreInternalApi fun stripQuoteReplyRefsForPreview(content: String): String {
    if (content.isEmpty() || !content.contains("msg=") || !content.contains('>')) return content
    if (hasSameConversationQuoteRef(content)) {
        val parts = mutableListOf<String>()
        var hasQuoteBlock = false
        for (segment in parseMessageQuoteSegments(content)) {
            when (segment) {
                is MessageQuoteSegment.QuoteBlock -> {
                    hasQuoteBlock = true
                    parts += formatPlainBlockquote(segment.excerpt)
                }
                is MessageQuoteSegment.Text -> {
                    val cleaned = stripTrailingTruncatedQuoteBlock(segment.text, QuoteRefCleanup.StripRef)
                    if (cleaned.jsTrim().isNotEmpty()) parts += cleaned
                }
            }
        }
        if (!hasQuoteBlock) return stripTrailingTruncatedQuoteBlock(content, QuoteRefCleanup.StripRef)
        return parts.joinToString("\n\n")
    }
    if (content.contains('\n') || content.contains('\r')) return stripTrailingTruncatedQuoteBlock(content, QuoteRefCleanup.StripRef)
    return cleanCollapsedSingleLineQuoteRefs(content, QuoteRefCleanup.StripRef)
}

/**
 * web `extractQuoteReplyPreviewText` (botiverse/slock #16158): what a one-line preview reads of a
 * message. A quote reply leads with its sender's own words (its text, its quotes dropped); one that
 * is only quotes reads their words, without `>` or `msg=` lines. A preview cut inside a ref or folded
 * to one line loses the ref too. Plain blockquotes, cross-channel refs and code stay as written.
 */
@RaftCoreInternalApi fun extractQuoteReplyPreviewText(content: String): String {
    if (content.isEmpty() || !content.contains("msg=") || !content.contains('>')) return content
    if (hasSameConversationQuoteRef(content)) {
        val quoteBlocks = mutableListOf<MessageQuoteSegment.QuoteBlock>()
        val textParts = mutableListOf<String>()
        for (segment in parseMessageQuoteSegments(content)) {
            when (segment) {
                is MessageQuoteSegment.QuoteBlock -> quoteBlocks += segment
                is MessageQuoteSegment.Text -> {
                    val cleaned = stripTrailingTruncatedQuoteBlock(segment.text, QuoteRefCleanup.Extract).jsTrim()
                    if (cleaned.isNotEmpty()) textParts += cleaned
                }
            }
        }
        if (quoteBlocks.isEmpty()) return stripTrailingTruncatedQuoteBlock(content, QuoteRefCleanup.Extract)
        if (textParts.isNotEmpty()) return textParts.joinToString("\n\n")
        val excerpts = quoteBlocks.map { it.excerpt.jsTrim() }.filter { it.isNotEmpty() }
        return if (excerpts.isNotEmpty()) excerpts.joinToString("\n\n") else content
    }
    if (content.contains('\n') || content.contains('\r')) return stripTrailingTruncatedQuoteBlock(content, QuoteRefCleanup.Extract)
    return cleanCollapsedSingleLineQuoteRefs(content, QuoteRefCleanup.Extract)
}
