package build.raft.app

import build.raft.app.data.ThreadSendMessageMention
import build.raft.app.model.ChannelExternalMemberProfile

/** `mentions[].type` of a Slack participant (slock `StructuredMentionInput`). */
internal const val ComposerStructuredMentionExternalType = "external"

/**
 * The `@` token a Slack participant is mentioned by, exactly as web `MessageInput.tsx` builds it: the
 * first non-blank Slack handle (else the display name without spaces), every character outside
 * Raft's handle alphabet (`[\p{L}\p{N}_-]`) replaced by `-`, at most 128 characters (the server's
 * `parseStructuredMentions` limit). Null when nothing usable is left.
 */
internal fun ChannelExternalMemberProfile.composerMentionHandle(): String? {
    val raw = handles.firstOrNull { it.trim().isNotEmpty() } ?: displayName.filterNot { it.isWhitespace() }
    val handle = buildString {
        raw.forEachCodePoint { codePoint ->
            if (codePoint.isRaftHandleCodePoint()) appendCodePoint(codePoint) else append('-')
        }
    }
    return handle.takeIf { it.isNotEmpty() && it.length <= 128 }
}

/** The structured mentions whose `@handle` is still visible in [text] (rule: never send an @ that is not in the text). */
internal fun List<ThreadSendMessageMention>.visibleStructuredMentionsIn(text: String): List<ThreadSendMessageMention> =
    if (isEmpty()) this else filter { mention -> composerStructuredMentionStillAppears(text, mention.name) }.distinct()

/**
 * Whether a picked mention still has a visible `@handle` in [source]: slock
 * `structuredRaftMentionStillAppears` (packages/shared/src/raftRefs.ts). Only mention prose counts —
 * not code (fenced blocks, inline spans), link labels/URLs, bare URLs or an escaped `\<@x>`; a link to
 * `<@handle>` counts. The token ends at a non-handle character or at a Han/Kana/Hangul character.
 */
internal fun composerStructuredMentionStillAppears(source: String, handle: String): Boolean {
    if (handle.isEmpty() || !handle.isRaftHandle()) return false
    if (source.indexOf("@$handle") < 0) return false
    val prose = source.withoutMarkdownCode()
    // `<@handle>` (an autolink, or a link whose destination is it) is that mention; `\<@handle>` is not.
    val angle = "<@$handle>"
    var angleAt = prose.indexOf(angle)
    while (angleAt >= 0) {
        if (angleAt == 0 || prose[angleAt - 1] != '\\') return true
        angleAt = prose.indexOf(angle, angleAt + 1)
    }
    val text =
        prose
            .replace(MarkdownLinkRegex, " ")
            .replace(EscapedUserRefRegex, "")
            .replace(BareUrlRegex, " ")
    val token = "@$handle"
    var from = 0
    while (true) {
        val at = text.indexOf(token, from)
        if (at < 0) return false
        val end = at + token.length
        if (end >= text.length) return true
        // slock regex: `@handle(?!(?!CJK)[\p{L}\p{N}_-])`.
        val next = text.codePointAtCompat(end)
        if (!next.isRaftHandleCodePoint() || next.isCjkSuffixStart()) return true
        from = at + 1
    }
}

private val MarkdownLinkRegex = Regex("""\[[^\]\n]*\]\([^)\n]*\)""")
private val EscapedUserRefRegex = Regex("""\\<@[^>\s]+>""")
private val BareUrlRegex = Regex("""https?://[^\s<>]+""")

private fun String.isRaftHandle(): Boolean {
    var ok = true
    forEachCodePoint { if (!it.isRaftHandleCodePoint()) ok = false }
    return ok
}

/** Han, Hiragana, Katakana and Hangul (slock `RAFT_MENTION_CJK_SUFFIX_START`). */
private fun Int.isCjkSuffixStart(): Boolean =
    this in 0x2E80..0x2FDF || this == 0x3005 || this == 0x3007 || this in 0x3021..0x3029 || this in 0x3038..0x303B ||
        this in 0x3400..0x4DBF || this in 0x4E00..0x9FFF || this in 0xF900..0xFAFF || this in 0x20000..0x323AF ||
        this in 0x3041..0x309F || this in 0x30A0..0x30FF || this in 0x31F0..0x31FF || this in 0xFF66..0xFF9F ||
        this in 0x1100..0x11FF || this in 0x3130..0x318F || this in 0xA960..0xA97F || this in 0xAC00..0xD7FF ||
        this in 0xFFA0..0xFFDC

/** Markdown code removed: fenced blocks (``` / ~~~, unclosed to the end) and inline backtick spans. */
private fun String.withoutMarkdownCode(): String {
    val out = StringBuilder()
    var fence: String? = null
    for (line in split('\n')) {
        val trimmed = line.trimStart()
        if (fence == null) {
            val opener = when {
                trimmed.startsWith("```") -> trimmed.takeWhile { it == '`' }
                trimmed.startsWith("~~~") -> trimmed.takeWhile { it == '~' }
                else -> null
            }
            if (opener != null) {
                fence = opener
                out.append('\n')
                continue
            }
            out.append(line.withoutInlineCode()).append('\n')
        } else {
            if (trimmed.startsWith(fence) && trimmed.trimEnd().all { it == fence[0] }) fence = null
            out.append('\n')
        }
    }
    return out.toString()
}

private fun String.withoutInlineCode(): String {
    val out = StringBuilder()
    var index = 0
    while (index < length) {
        if (this[index] != '`') {
            out.append(this[index])
            index++
            continue
        }
        var run = 0
        while (index + run < length && this[index + run] == '`') run++
        val ticks = "`".repeat(run)
        var close = indexOf(ticks, index + run)
        while (close >= 0 && close + run < length && this[close + run] == '`') close = indexOf(ticks, close + run + 1)
        if (close < 0) {
            out.append(ticks)
            index += run
        } else {
            out.append(' ')
            index = close + run
        }
    }
    return out.toString()
}

private inline fun String.forEachCodePoint(action: (Int) -> Unit) {
    var index = 0
    while (index < length) {
        val codePoint = codePointAtCompat(index)
        action(codePoint)
        index += if (codePoint >= 0x10000) 2 else 1
    }
}

private fun String.codePointAtCompat(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
        return 0x10000 + ((high.code - 0xD800) shl 10) + (this[index + 1].code - 0xDC00)
    }
    return high.code
}

private fun StringBuilder.appendCodePoint(codePoint: Int) {
    if (codePoint < 0x10000) {
        append(codePoint.toChar())
    } else {
        val offset = codePoint - 0x10000
        append((0xD800 + (offset shr 10)).toChar())
        append((0xDC00 + (offset and 0x3FF)).toChar())
    }
}
