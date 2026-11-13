package build.raft.app.data.runtimeform.v2

/**
 * Runtime form v2 hints are server copy (`uiSchema.localization.<field>.hint`). The one markup they may
 * carry is an inline Markdown link `[text](https://…)`, drawn as a tappable link; nothing else is
 * interpreted, and a link whose target is not an absolute https URL (or is malformed) stays plain text.
 * A port of slock web `runtimeFormV2Hint.ts`.
 */
internal sealed interface RuntimeFormV2HintPart {
    val text: String

    data class Plain(override val text: String) : RuntimeFormV2HintPart

    data class Link(override val text: String, val href: String) : RuntimeFormV2HintPart
}

private val RuntimeFormV2HintInlineLink = Regex("""\[([^\[\]\n]+)]\(([^()\s]+)\)""")

/** Web `httpsHref`: an absolute https URL with a host and no user info, else null. */
internal fun runtimeFormV2HintHttpsHref(raw: String): String? {
    if (!raw.startsWith("https://")) return null
    val authority = raw.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#')
    if ('@' in authority) return null
    val host = authority.substringBefore(':')
    if (host.isEmpty()) return null
    val port = authority.substringAfter(':', "")
    if (authority.contains(':') && (port.isEmpty() || port.any { !it.isDigit() } || port.toIntOrNull()?.let { it > 65535 } != false)) {
        return null
    }
    if (host.any { it.isWhitespace() || it in "<>\"\\^`{|}%" }) return null
    return raw
}

/** Splits a hint into plain text and https links; adjacent text is merged. */
internal fun parseRuntimeFormV2Hint(hint: String): List<RuntimeFormV2HintPart> {
    val parts = mutableListOf<RuntimeFormV2HintPart>()
    fun pushText(text: String) {
        if (text.isEmpty()) return
        val last = parts.lastOrNull()
        if (last is RuntimeFormV2HintPart.Plain) parts[parts.lastIndex] = RuntimeFormV2HintPart.Plain(last.text + text) else parts += RuntimeFormV2HintPart.Plain(text)
    }
    var cursor = 0
    for (match in RuntimeFormV2HintInlineLink.findAll(hint)) {
        val start = match.range.first
        pushText(hint.substring(cursor, start))
        val href = runtimeFormV2HintHttpsHref(match.groupValues[2])
        if (href != null) parts += RuntimeFormV2HintPart.Link(match.groupValues[1], href) else pushText(match.value)
        cursor = match.range.last + 1
    }
    pushText(hint.substring(cursor))
    return parts
}

/** Whether [hint] has at least one link to draw (otherwise it is drawn as plain text, as before). */
internal fun runtimeFormV2HintHasLinks(hint: String?): Boolean =
    hint != null && parseRuntimeFormV2Hint(hint).any { it is RuntimeFormV2HintPart.Link }
