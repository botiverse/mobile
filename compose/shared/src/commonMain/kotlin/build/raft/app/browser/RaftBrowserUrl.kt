package build.raft.app

import build.raft.app.data.RaftClientConfigAppHosts
import build.raft.app.links.RaftHttpUrl
import build.raft.app.links.parseRaftHttpUrl
import build.raft.app.links.raftComparableHostname
import build.raft.app.links.raftPermalinkHostnames
import build.raft.app.thread.RaftMessageLinkProductionHosts

/*
 * How the in-app browser's rules read an address (#356): the App entries (RaftAppEntry.kt) and Raft's
 * sign-in from a page (RaftBrowserSignInRules.kt). Pure and strict: what the WHATWG parser behind
 * slock's desktop rules would rewrite first (a host outside ASCII, a percent-encoded or full-width
 * dot, whitespace, a backslash) is refused here instead, so a rule that can't read an address fails
 * closed.
 */

/** The longest address the rules read (slock desktop `WEB_PREVIEW_MAX_URL_LENGTH`). */
internal const val RAFT_BROWSER_MAX_URL_LENGTH = 8192

/** The IPv6 loopback as the WHATWG parser writes it: the one bracketed host the rules read. */
internal const val RAFT_BROWSER_IPV6_LOOPBACK = "[::1]"

/**
 * Hosts a debug build may also reach over plain http (the API it is built against, an App under
 * development): this device, and the Android emulator's address for the computer it runs on.
 */
internal val RaftBrowserDebugLoopbackHosts: Set<String> =
    setOf("localhost", "127.0.0.1", RAFT_BROWSER_IPV6_LOOPBACK, "10.0.2.2")

/** Stands in for [RAFT_BROWSER_IPV6_LOOPBACK] while [parseRaftHttpUrl], which reads no bracketed host, reads the rest. */
private const val RAFT_BROWSER_IPV6_LOOPBACK_STAND_IN = "ipv6-loopback"

/**
 * An http(s) address as the browser rules read it, or null: at most [RAFT_BROWSER_MAX_URL_LENGTH]
 * characters, read by [parseRaftHttpUrl] (scheme and host lower-cased, the default port dropped, an
 * empty path `/`), with a host of ASCII letters, digits, dots and hyphens, or the IPv6 loopback.
 * Credentials stay in [RaftHttpUrl.userInfo], for each rule to refuse.
 */
internal fun raftBrowserUrlOf(value: String): RaftHttpUrl? {
    if (value.isEmpty() || value.length > RAFT_BROWSER_MAX_URL_LENGTH || value.any { it.code == 0x7f }) return null
    val url = parseRaftHttpUrl(value) ?: return raftBrowserIpv6LoopbackUrlOf(value)
    return url.takeIf { it.host.all(::isRaftBrowserHostChar) }
}

private fun isRaftBrowserHostChar(char: Char): Boolean =
    char in 'a'..'z' || char in '0'..'9' || char == '.' || char == '-'

/** `http(s)://[::1][:port]…`: read as [parseRaftHttpUrl] reads a named host, the name put back after. */
private fun raftBrowserIpv6LoopbackUrlOf(value: String): RaftHttpUrl? {
    val raw = value.trim()
    val hostStart = raw.indexOf("://") + 3
    if (hostStart < 3 || !raw.startsWith(RAFT_BROWSER_IPV6_LOOPBACK, startIndex = hostStart)) return null
    val standIn =
        raw.replaceRange(hostStart, hostStart + RAFT_BROWSER_IPV6_LOOPBACK.length, RAFT_BROWSER_IPV6_LOOPBACK_STAND_IN)
    return parseRaftHttpUrl(standIn)
        ?.takeIf { it.host == RAFT_BROWSER_IPV6_LOOPBACK_STAND_IN }
        ?.copy(host = RAFT_BROWSER_IPV6_LOOPBACK)
}

/** WHATWG `URL.origin`: `scheme://host[:port]`, without credentials, path, query or fragment. */
internal val RaftHttpUrl.origin: String
    get() = if (port == null) "$scheme://$host" else "$scheme://$host:$port"

/**
 * Whether [host] is one of Raft's own ([raftHosts]), both compared as [raftComparableHostname]
 * spells them (slock `isRaftAppHostname`): `app.raft.build.` is `app.raft.build`.
 */
internal fun raftBrowserIsRaftHost(host: String, raftHosts: Collection<String>): Boolean {
    val comparable = raftComparableHostname(host)
    return raftHosts.any { raftComparableHostname(it.trim()) == comparable }
}

/**
 * Raft's own hosts (#356 §3), in comparable spelling: the environment's web and API hosts, the
 * production link hosts ([RaftMessageLinkProductionHosts]), the web's fixed permalink hosts and the
 * client config's app hosts ([raftPermalinkHostnames]). An App is never on one of them.
 */
internal fun raftBrowserRaftHosts(
    webUrl: String?,
    apiBaseUrl: String?,
    appHosts: Collection<String> = RaftClientConfigAppHosts.current,
): Set<String> =
    buildSet {
        addAll(raftPermalinkHostnames(appHosts, webUrl))
        addAll(RaftMessageLinkProductionHosts)
        apiBaseUrl?.let(::parseRaftHttpUrl)?.host?.let(::raftComparableHostname)?.takeIf { it.isNotEmpty() }?.let(::add)
    }

/** One `name=value` pair of a query: [raw] as it is written, [name] and [value] decoded. */
internal data class RaftBrowserQueryPair(val raw: String, val name: String, val value: String)

/**
 * A query as `URLSearchParams` reads it: pairs split at `&` (empty ones skipped), a name ending at its
 * pair's first `=`, `+` a space and `%XX` UTF-8; one leading `?` is skipped. Unlike the web, which keeps
 * a malformed escape as written, [parse] gives null for a query with one (or with bytes that are not
 * UTF-8, [strictPercentDecodeUtf8OrNull]): a rule refuses such a query.
 */
internal class RaftBrowserQuery private constructor(val pairs: List<RaftBrowserQueryPair>) {
    /** `getAll`: every value of [name], in order. */
    fun all(name: String): List<String> = pairs.filter { it.name == name }.map { it.value }

    /** `get`: the first value of [name], or null. */
    fun first(name: String): String? = pairs.firstOrNull { it.name == name }?.value

    companion object {
        fun parse(query: String?): RaftBrowserQuery? {
            val pairs = ArrayList<RaftBrowserQueryPair>()
            for (raw in query.orEmpty().removePrefix("?").split('&')) {
                if (raw.isEmpty()) continue
                val separator = raw.indexOf('=')
                val nameText = if (separator < 0) raw else raw.substring(0, separator)
                val valueText = if (separator < 0) "" else raw.substring(separator + 1)
                val name = raftBrowserQueryDecoded(nameText) ?: return null
                val value = raftBrowserQueryDecoded(valueText) ?: return null
                pairs += RaftBrowserQueryPair(raw = raw, name = name, value = value)
            }
            return RaftBrowserQuery(pairs)
        }
    }
}

/** A query component as `application/x-www-form-urlencoded` decodes it: `+` a space, then `%XX`. */
private fun raftBrowserQueryDecoded(text: String): String? {
    val spaced = text.replace('+', ' ')
    return if ('%' in spaced) strictPercentDecodeUtf8OrNull(spaced) else spaced
}
