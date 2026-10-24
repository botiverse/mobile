package build.raft.app

import build.raft.app.data.RaftSharedKeyValueStoreProvider
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.model.ConversationKind
import build.raft.kuiklybase.datetime.Clock
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A Raft message link opened from outside the app (Android App Link, iOS Universal Link):
 * identity only (#proj-mobile tasks #25/#26, route format of task #24).
 *
 * - `/s/{slug}/channel/{cid}`: the channel;
 * - `…?msg={mid}`: the channel, scrolled to and highlighting {mid};
 * - `…?msg={mid}&thread={cid}:{pid}`: the thread of {pid}, highlighting reply {mid};
 * - `…?thread={cid}:{pid}`: the thread of {pid}, highlighting the parent;
 * - `/s/{slug}/dm/{cid}…`: the same for a DM.
 *
 * Nothing in the link is shown or trusted: the app shell resolves {slug} against the signed-in
 * account's servers and reads the conversation through the API before it opens anything
 * ([RaftMessageLinkResolver]).
 */
internal data class RaftMessageLink(
    val serverSlug: String,
    /** [ConversationKind.Channel] for `/channel/`, [ConversationKind.DirectMessage] for `/dm/`. */
    val routeKind: ConversationKind,
    val channelId: String,
    /** `msg`: the message to highlight (a reply when [threadParentMessageId] is set). */
    val messageId: String? = null,
    /** The `{pid}` of `thread={cid}:{pid}`; its `{cid}` always equals [channelId]. */
    val threadParentMessageId: String? = null,
) {
    val isThread: Boolean
        get() = threadParentMessageId != null

    /** The message the open lands on: the reply, else the thread parent, else none. */
    val focusMessageId: String?
        get() = messageId ?: threadParentMessageId
}

/** Hosts that serve Raft message links in production; both carry the same path and query. */
internal val RaftMessageLinkProductionHosts: Set<String> = setOf("app.raft.build", "open.raft.build")

/**
 * Parses an external message link, or null when the app must not claim it (the platform then
 * leaves it to the browser). Accepts `https` on [RaftMessageLinkProductionHosts], plus exactly the
 * host (and scheme/port) of [configuredWebUrl], the Developer environment's web URL (AGENTS.md
 * "Environment Web Link URL Rule"), so a staging build also takes its staging links.
 *
 * Rejected as a whole: any other path, an empty or extra segment, an id with characters outside
 * `[A-Za-z0-9_-]`, a malformed `thread`, and a `thread` whose channel id differs from the path's.
 * `app=missing` (the open.raft.build fallback marker) and unknown query keys are ignored.
 */
internal fun parseRaftMessageLink(url: String, configuredWebUrl: String? = null): RaftMessageLink? {
    val parsed = url.toRaftLinkUrl() ?: return null
    if (!parsed.isAllowedMessageLinkOrigin(configuredWebUrl?.toRaftLinkUrl())) return null
    val segments = parsed.path.removePrefix("/").split('/')
    if (segments.size != 4 || segments[0] != "s") return null
    val serverSlug = segments[1].raftLinkPercentDecode()?.takeIf(::isRaftLinkIdentifier) ?: return null
    val routeKind =
        when (segments[2]) {
            "channel" -> ConversationKind.Channel
            "dm" -> ConversationKind.DirectMessage
            else -> return null
        }
    val channelId = segments[3].raftLinkPercentDecode()?.takeIf(::isRaftLinkIdentifier) ?: return null
    val query = parsed.queryParameters() ?: return null
    val messageId =
        when (val raw = query["msg"]) {
            null -> null
            else -> raw.takeIf(::isRaftLinkIdentifier) ?: return null
        }
    val threadParentMessageId =
        when (val raw = query["thread"]) {
            null -> null
            else -> {
                val parts = raw.split(':')
                if (parts.size != 2) return null
                val threadChannelId = parts[0].takeIf(::isRaftLinkIdentifier) ?: return null
                // The thread's channel must be the path's channel; otherwise the whole link is refused.
                if (threadChannelId != channelId) return null
                parts[1].takeIf(::isRaftLinkIdentifier) ?: return null
            }
        }
    return RaftMessageLink(
        serverSlug = serverSlug,
        routeKind = routeKind,
        channelId = channelId,
        messageId = messageId,
        threadParentMessageId = threadParentMessageId,
    )
}

private fun isRaftLinkIdentifier(value: String): Boolean =
    value.length in 1..128 && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

private data class RaftLinkUrl(
    val scheme: String,
    val host: String,
    val port: Int?,
    val path: String,
    val rawQuery: String,
)

private fun String.toRaftLinkUrl(): RaftLinkUrl? {
    val raw = trim()
    val schemeEnd = raw.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = raw.substring(0, schemeEnd).lowercase()
    if (scheme != "https" && scheme != "http") return null
    val rest = raw.substring(schemeEnd + 3).substringBefore('#')
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, authorityEnd)
    if (authority.isEmpty() || '@' in authority) return null
    val host = authority.substringBefore(':').lowercase().takeIf(String::isNotEmpty) ?: return null
    val port =
        if (':' in authority) {
            authority.substringAfter(':').toIntOrNull() ?: return null
        } else {
            null
        }
    val pathAndQuery = rest.substring(authorityEnd)
    return RaftLinkUrl(
        scheme = scheme,
        host = host,
        port = port,
        path = pathAndQuery.substringBefore('?'),
        rawQuery = pathAndQuery.substringAfter('?', missingDelimiterValue = ""),
    )
}

private fun RaftLinkUrl.isAllowedMessageLinkOrigin(configured: RaftLinkUrl?): Boolean {
    if (scheme == "https" && host in RaftMessageLinkProductionHosts && (port == null || port == 443)) return true
    return configured != null &&
        configured.host == host &&
        configured.scheme == scheme &&
        configured.port == port
}

/** Decoded query values by key (first occurrence wins); null for a malformed escape. */
private fun RaftLinkUrl.queryParameters(): Map<String, String>? {
    if (rawQuery.isEmpty()) return emptyMap()
    val result = linkedMapOf<String, String>()
    for (part in rawQuery.split('&')) {
        if (part.isEmpty()) continue
        val key = part.substringBefore('=').raftLinkPercentDecode() ?: return null
        val value = part.substringAfter('=', missingDelimiterValue = "").raftLinkPercentDecode() ?: return null
        if (key !in result) result[key] = value
    }
    return result
}

/** RFC 3986 percent-decoding (UTF-8; `+` is a space in a query); null for a malformed escape. */
private fun String.raftLinkPercentDecode(): String? {
    if ('%' !in this && '+' !in this) return this
    val bytes = ArrayList<Byte>(length)
    var index = 0
    while (index < length) {
        val char = this[index]
        when {
            char == '%' -> {
                if (index + 2 >= length) return null
                val value = substring(index + 1, index + 3).toIntOrNull(16) ?: return null
                bytes += value.toByte()
                index += 3
            }
            char == '+' -> {
                bytes += ' '.code.toByte()
                index++
            }
            else -> {
                char.toString().encodeToByteArray().forEach { bytes += it }
                index++
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}

/** A link waiting for a ready app shell (sign-in, restore, a cold start). */
internal data class RaftPendingMessageLink(
    val id: Long,
    val link: RaftMessageLink,
    val receivedAtEpochMillis: Long,
    val source: String,
)

/**
 * The process-wide single slot between a platform link ingress and the app shell. A link that
 * arrives signed out, or before the shell is ready, waits here (newest wins) until a shell with a
 * ready session takes it exactly once; one older than [ttlMillis] is dropped instead, so an
 * abandoned sign-in does not open it much later.
 */
internal class RaftMessageLinkInbox(
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val ttlMillis: Long = DefaultTtlMillis,
) {
    private val lock = SynchronizedObject()
    private var nextId = 1L
    private val mutablePending = MutableStateFlow<RaftPendingMessageLink?>(null)
    val pending: StateFlow<RaftPendingMessageLink?> = mutablePending.asStateFlow()

    fun submit(link: RaftMessageLink, source: String): RaftPendingMessageLink = synchronized(lock) {
        RaftPendingMessageLink(
            id = nextId++,
            link = link,
            receivedAtEpochMillis = nowEpochMillis(),
            source = source,
        ).also { mutablePending.value = it }
    }

    /**
     * Takes the pending link [id] for a shell whose session is ready. Null when it was already
     * taken, replaced by a newer link, or expired (an expired link is cleared).
     */
    fun take(id: Long): RaftPendingMessageLink? = synchronized(lock) {
        val current = mutablePending.value?.takeIf { it.id == id } ?: return null
        mutablePending.value = null
        current.takeIf { nowEpochMillis() - it.receivedAtEpochMillis <= ttlMillis }
    }

    fun clear() = synchronized(lock) { mutablePending.value = null }

    companion object {
        const val DefaultTtlMillis: Long = 30L * 60L * 1000L

        /** The slot the platform ingresses and every app shell of this process share. */
        val Process: RaftMessageLinkInbox = RaftMessageLinkInbox()
    }
}

/**
 * Whether a shell takes the pending link now: only with a signed-in, server-ready session
 * ([sessionReady]); before that (signed out, restoring, choosing a server) it keeps waiting.
 */
internal fun raftMessageLinkShouldTake(pending: RaftPendingMessageLink?, sessionReady: Boolean): Boolean =
    pending != null && sessionReady

/**
 * Platform entry for an external message link (Android MainActivity, iOS scene delegate). Returns
 * false when the URL is not a Raft message link this app takes; the caller then leaves it to the
 * browser. On true the link waits in [RaftMessageLinkInbox.Process] for the app shell.
 */
object RaftMessageLinkIngress {
    /** [configuredWebUrl] is the Developer environment's web URL; null reads the app-scope one. */
    fun accept(url: String, source: String, configuredWebUrl: String? = null): Boolean {
        val webUrl = configuredWebUrl ?: appScopeWebUrlOrNull()
        val link = parseRaftMessageLink(url, webUrl)
        if (link == null) {
            raftLogInfo(RaftMessageLinkLogTag, "message_link_ingress result=not_claimed source=${source.raftLinkLogToken()}")
            return false
        }
        val pending = RaftMessageLinkInbox.Process.submit(link, source.raftLinkLogToken())
        raftLogInfo(
            RaftMessageLinkLogTag,
            "message_link_ingress result=pending id=${pending.id} source=${pending.source} " +
                "kind=${link.routeKind.name} thread=${link.isThread} msg=${link.messageId != null}"
        )
        return true
    }

    /** Whether [url] is a message link the app takes, without queueing it. */
    fun isMessageLink(url: String, configuredWebUrl: String? = null): Boolean =
        parseRaftMessageLink(url, configuredWebUrl ?: appScopeWebUrlOrNull()) != null

    private fun appScopeWebUrlOrNull(): String? =
        runCatching {
            SharedApiEnvironmentStore(
                RaftSharedKeyValueStoreProvider.create(SharedApiEnvironmentStore.Namespace, "API environment").store
            ).current().webUrl
        }.getOrNull()?.takeIf(String::isNotBlank)
}

internal const val RaftMessageLinkLogTag = "RaftMessageLink"

private fun String.raftLinkLogToken(): String =
    trim().ifBlank { "unknown" }.replace(Regex("[^A-Za-z0-9_.:-]"), "_").take(48)
