package build.raft.app

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId

/** Identity carried by a canonical Raft message permalink. */
internal data class SlockMessagePermalinkTarget(
    val rawUrl: String,
    val serverSlug: String,
    val routeKind: ConversationKind,
    val channelId: ChannelId,
    val messageId: MessageId,
    val threadParentMessageId: MessageId? = null
)

/**
 * Parses the canonical `/s/{serverSlug}/{channel|dm}/{channelId}` contract.
 * The configured web origin is an exact additional allow-list entry.
 */
internal fun String.toSlockMessagePermalinkTarget(
    configuredWebUrl: String? = null
): SlockMessagePermalinkTarget? {
    val parsed = parseSlockAbsoluteUrl() ?: return null
    if (parsed.host !in slockPermalinkAllowedHosts(configuredWebUrl)) return null
    val segments = parsed.path.split('/').filter(String::isNotBlank)
    if (segments.size != 4 || segments[0] != "s") return null

    val serverSlug = segments[1].takeIf(String::isNotBlank) ?: return null
    val routeKind = when (segments[2].lowercase()) {
        "channel" -> ConversationKind.Channel
        "dm" -> ConversationKind.DirectMessage
        else -> return null
    }
    val channelId = segments[3].takeIf(String::isNotBlank) ?: return null
    val threadValue = parsed.query.queryParamValue("thread")
    val threadParentId =
        threadValue?.split(':', limit = 2)
            ?.takeIf { parts -> parts.size == 2 && parts[0] == channelId && parts[1].isNotBlank() }
            ?.get(1)
            ?: if (threadValue == null) null else return null
    val messageId =
        parsed.query.queryParamValue("msg")?.takeIf(String::isNotBlank)
            ?: threadParentId
            ?: return null

    return SlockMessagePermalinkTarget(
        rawUrl = parsed.raw,
        serverSlug = serverSlug,
        routeKind = routeKind,
        channelId = ChannelId(channelId),
        messageId = MessageId(messageId),
        threadParentMessageId = threadParentId?.let(::MessageId)
    )
}

private data class SlockAbsoluteUrl(
    val raw: String,
    val host: String,
    val path: String,
    val query: String
)

private fun String.parseSlockAbsoluteUrl(): SlockAbsoluteUrl? {
    val raw = trim()
    val schemeEnd = raw.indexOf("://")
    if (schemeEnd <= 0 || raw.substring(0, schemeEnd).lowercase() !in setOf("https", "http")) return null
    val authorityAndPath = raw.substring(schemeEnd + 3).substringBefore('#')
    val pathStart = authorityAndPath.indexOf('/')
    val authority = if (pathStart >= 0) authorityAndPath.substring(0, pathStart) else authorityAndPath
    if (authority.isBlank() || '@' in authority) return null
    val host = authority.substringBefore(':').lowercase().takeIf(String::isNotBlank) ?: return null
    val pathAndQuery = if (pathStart >= 0) authorityAndPath.substring(pathStart) else ""
    return SlockAbsoluteUrl(
        raw = raw,
        host = host,
        path = pathAndQuery.substringBefore('?'),
        query = pathAndQuery.substringAfter('?', missingDelimiterValue = "")
    )
}

private fun slockPermalinkAllowedHosts(configuredWebUrl: String?): Set<String> =
    buildSet {
        add("app.slock.ai")
        add("staging.slock.ai")
        add("app.raft.build")
        configuredWebUrl?.parseSlockAbsoluteUrl()?.host?.let(::add)
    }

private fun String.queryParamValue(name: String): String? =
    split('&').firstNotNullOfOrNull { part ->
        val pieces = part.split('=', limit = 2)
        pieces.getOrNull(1)?.takeIf { pieces.firstOrNull() == name }?.slockPercentDecode()
    }

private fun String.slockPercentDecode(): String {
    if ('%' !in this && '+' !in this) return this
    val result = StringBuilder(length)
    var index = 0
    while (index < length) {
        when (val char = this[index]) {
            '+' -> {
                result.append(' ')
                index++
            }
            '%' -> {
                val bytes = mutableListOf<Byte>()
                var cursor = index
                while (cursor + 2 < length && this[cursor] == '%') {
                    val value = substring(cursor + 1, cursor + 3).toIntOrNull(16) ?: break
                    bytes += value.toByte()
                    cursor += 3
                }
                if (bytes.isEmpty()) {
                    result.append(char)
                    index++
                } else {
                    result.append(ByteArray(bytes.size) { bytes[it] }.decodeToString())
                    index = cursor
                }
            }
            else -> {
                result.append(char)
                index++
            }
        }
    }
    return result.toString()
}
