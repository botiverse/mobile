package build.raft.app

import build.raft.app.data.ThreadSendMessageMention
import build.raft.app.markdown.raftMarkdownWithoutQuotes

/** `mentions[].type` of a Slack participant (slock `StructuredMentionInput`). */
internal const val ComposerStructuredMentionExternalType = "external"

/** `mentions[].type` of a picked person (web `MentionCandidate.type`). */
internal const val ComposerStructuredMentionUserType = "user"

/** `mentions[].type` of a picked agent (web `MentionCandidate.type`). */
internal const val ComposerStructuredMentionAgentType = "agent"

/**
 * The structured mention a picked person or agent adds, as web `MessageInput.tsx` `doInsertMention`
 * records it: `{ type: "user" | "agent", id, name }`. [id] is the user id (server member `userId`,
 * channel human `id`) or the agent id; [handle] is their `name`, the token after `@`. No offsets:
 * the pick lives while that `@handle` is in the text ([visibleStructuredMentionsIn]).
 *
 * Null, so the token stays name-resolved, for any other kind and for an id that is not a UUID: slock
 * `parseStructuredMentions` answers a non-UUID id with a 400 for the whole message.
 */
internal fun composerPersonOrAgentStructuredMention(
    kind: build.raft.app.model.MemberKind,
    id: String?,
    handle: String,
): ThreadSendMessageMention? {
    val type =
        when (kind) {
            build.raft.app.model.MemberKind.Human -> ComposerStructuredMentionUserType
            build.raft.app.model.MemberKind.Agent -> ComposerStructuredMentionAgentType
            else -> return null
        }
    val cleanId = id?.trim()?.takeIf { it.isStructuredMentionUuid() } ?: return null
    val name = handle.trim().removePrefix("@").takeIf { it.isNotEmpty() && it.length <= 128 } ?: return null
    return ThreadSendMessageMention(type = type, id = cleanId, name = name)
}

/** slock `UUID_RE` (`/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i`). */
private fun String.isStructuredMentionUuid(): Boolean =
    length == 36 &&
        withIndex().all { (index, char) ->
            if (index == 8 || index == 13 || index == 18 || index == 23) {
                char == '-'
            } else {
                char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
            }
        }

/** The structured mentions whose `@handle` is still visible in [text] (rule: never send an @ that is not in the text). */
internal fun List<ThreadSendMessageMention>.visibleStructuredMentionsIn(text: String): List<ThreadSendMessageMention> =
    if (isEmpty()) this else filter { mention -> composerStructuredMentionStillAppears(text, mention.name) }.distinct()

/**
 * Whether a picked mention still has a visible `@handle` in [source]: slock
 * `structuredRaftMentionStillAppears` (packages/shared/src/raftRefs.ts). Only mention prose counts —
 * not code (fenced blocks, inline spans), link labels/URLs, bare URLs, an escaped `\<@x>` or a block
 * quote ([raftMarkdownWithoutQuotes], slock #14349: quoting a message does not mention again); a link
 * to `<@handle>` outside a quote counts. The token ends at a non-handle character or at a Han/Kana/Hangul character.
 */
internal fun composerStructuredMentionStillAppears(source: String, handle: String): Boolean {
    if (handle.isEmpty() || !handle.isRaftHandle()) return false
    if (source.indexOf("@$handle") < 0) return false
    val prose = raftMarkdownWithoutQuotes(source).withoutMarkdownCode()
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
        val next = text.raftCodePointAtCompat(end)
        if (!next.isRaftHandleCodePoint() || next.isCjkSuffixStart()) return true
        from = at + 1
    }
}

private val MarkdownLinkRegex = Regex("""\[[^\]\n]*\]\([^)\n]*\)""")
private val EscapedUserRefRegex = Regex("""\\<@[^>\s]+>""")
private val BareUrlRegex = Regex("""https?://[^\s<>]+""")

private fun String.isRaftHandle(): Boolean {
    var ok = true
    forEachRaftCodePoint { if (!it.isRaftHandleCodePoint()) ok = false }
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
