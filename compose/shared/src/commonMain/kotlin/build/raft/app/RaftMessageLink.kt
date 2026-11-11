package build.raft.app

import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.RaftAppLogin
import build.raft.app.data.RaftClientConfigAppHosts
import build.raft.app.data.RaftSharedKeyValueStoreProvider
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.parseArtifactOpenAddress
import build.raft.app.links.isRaftPermalinkChannelId
import build.raft.app.links.isRaftPermalinkMessageId
import build.raft.app.links.parseRaftConversationLink
import build.raft.app.model.ConversationKind
import kotlin.time.Clock
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
 * The path and query are the web's permalink grammar ([parseRaftConversationLink]): rejected as a
 * whole are any other path, an empty or extra segment, ids that are not hex (UUID or 8-hex short id),
 * a malformed `thread`, and a `thread` whose channel id differs from the path's. A UUID followed by
 * more text (a link glued to prose) is read as the UUID. `app=missing` (the open.raft.build fallback
 * marker) and unknown query keys are ignored.
 */
internal fun parseRaftMessageLink(
    url: String,
    configuredWebUrl: String? = null,
    /**
     * Raft's own app hosts from the client config (`appHosts`), added to the hosts above (a union,
     * as the web reads them): `https`, default port, the host matched exactly.
     */
    appHosts: Set<String> = RaftClientConfigAppHosts.current,
): RaftMessageLink? {
    val origin = url.toRaftLinkUrl() ?: return null
    if (!origin.isAllowedMessageLinkOrigin(configuredWebUrl?.toRaftLinkUrl(), appHosts)) return null
    // The origin is decided above; the path and query are the web's grammar (links/RaftMessageLinks).
    val link = parseRaftConversationLink(url, setOf(origin.host)) ?: return null
    return RaftMessageLink(
        serverSlug = link.serverSlug,
        routeKind = link.routeKind,
        channelId = link.channelId.value,
        messageId = link.messageId?.value,
        threadParentMessageId = link.threadParentMessageId?.value,
    )
}

/**
 * An Artifacts home address opened from outside the app (Android App Link, iOS Universal Link):
 * `/s/{slug}/artifacts?artifact={id}[&version={n}]`, the address the web keeps an open Artifact in
 * (slock v1.21.61 #13670). The origin is a message link's (the hosts [parseRaftMessageLink] takes);
 * the path and query are [parseArtifactOpenAddress]'s. Null when the app must not claim it.
 */
internal fun parseRaftArtifactDeepLink(
    url: String,
    configuredWebUrl: String? = null,
    appHosts: Set<String> = RaftClientConfigAppHosts.current,
): ArtifactLinkTarget? {
    val origin = url.toRaftLinkUrl() ?: return null
    if (!origin.isAllowedMessageLinkOrigin(configuredWebUrl?.toRaftLinkUrl(), appHosts)) return null
    return parseArtifactOpenAddress(url, setOf(origin.host))
}

/**
 * An external channel invite link opened from outside the app (the invite email's
 * `/external-invite/{token}`, slock `emailService.externalChannelInviteUrl`): its token, or null
 * when the app must not claim it. The origin is a message link's (the hosts [parseRaftMessageLink]
 * takes); the path is exactly `/external-invite/{token}` (web `externalLinkSignIn`), the token
 * percent-decoded. Identity only: the invite page reads everything else from the server.
 */
internal fun parseRaftExternalInviteLink(
    url: String,
    configuredWebUrl: String? = null,
    appHosts: Set<String> = RaftClientConfigAppHosts.current,
): String? {
    val origin = url.toRaftLinkUrl() ?: return null
    if (!origin.isAllowedMessageLinkOrigin(configuredWebUrl?.toRaftLinkUrl(), appHosts)) return null
    val segments = origin.path.removePrefix("/").removeSuffix("/").split('/')
    if (segments.size != 2 || segments[0] != "external-invite") return null
    val token = segments[1].raftLinkPercentDecode()?.takeIf { it.isNotBlank() && '/' !in it && it.length <= 512 } ?: return null
    return token
}

/**
 * The web link of [link], the inverse of [parseRaftMessageLink]: `{web}/s/{slug}/{channel|dm}/{cid}`
 * with `?msg={mid}` and/or `thread={cid}%3A{pid}` (the path's channel, as the parser requires).
 * [webUrl] is the Developer environment's web URL (AGENTS.md "Environment Web Link URL Rule");
 * blank or null is production. Null when an id is one the parser would refuse, so a link this
 * builds always parses back to [link] with the same [webUrl].
 */
/**
 * The web link a Raft Desktop deep link stands for, or null. Raft Desktop's "Open in Raft Desktop"
 * and copied links put the web path and query behind the app scheme (slock
 * `packages/web/src/utils/desktopAppLink.ts`): `raft:///s/{slug}/channel/{cid}?msg={mid}`, and
 * `raft://s/…` is read the same way (slock `desktopRouteFromDeepLink`). The path and query are put
 * on [webUrl], the Developer environment's web URL (AGENTS.md "Environment Web Link URL Rule");
 * blank or null is production. Only `/s/{slug}/…` is converted; [parseRaftMessageLink] then decides
 * whether the app opens it. `raft` and the per-build `raft-{variant}` callback schemes are accepted.
 */
internal fun raftSchemeLinkToWebLink(url: String, webUrl: String?): String? {
    val raw = url.trim()
    val schemeEnd = raw.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = raw.substring(0, schemeEnd).lowercase()
    if (scheme != "raft" && !scheme.startsWith("raft-")) return null
    val rest = raw.substring(schemeEnd + 3).substringBefore('#')
    val pathAndQuery = if (rest.startsWith("/")) rest else "/$rest"
    val path = pathAndQuery.substringBefore('?')
    val segments = path.removePrefix("/").split('/')
    if (segments.size < 2 || segments[0] != "s" || segments[1].isEmpty()) return null
    if ('\\' in pathAndQuery || '@' in path) return null
    return sharedSettingsResolvedWebBaseUrl(webUrl) + pathAndQuery
}

internal fun buildRaftMessageLink(link: RaftMessageLink, webUrl: String?): String? {
    if (!isRaftLinkIdentifier(link.serverSlug) || !isRaftPermalinkChannelId(link.channelId)) return null
    if (!listOfNotNull(link.messageId, link.threadParentMessageId).all(::isRaftPermalinkMessageId)) return null
    val routeKind =
        when (link.routeKind) {
            ConversationKind.DirectMessage -> "dm"
            else -> "channel"
        }
    val query =
        listOfNotNull(
            link.messageId?.let { "msg=$it" },
            link.threadParentMessageId?.let { "thread=${link.channelId}%3A$it" },
        ).joinToString("&")
    val base = sharedSettingsResolvedWebBaseUrl(webUrl)
    val path = "$base/s/${link.serverSlug}/$routeKind/${link.channelId}"
    return if (query.isEmpty()) path else "$path?$query"
}

/**
 * The pending sign-in request a sign-up email's link resumes, or null. A sign-up started in a
 * native browser sign-in (this app, or Raft Desktop) gets
 * `/?verify={token}&returnTo=/login/app?request={id}` (slock `verificationEmailUrl`; the web's
 * register page sends the page it was on as `returnTo`, and the sign-in page reads `request`).
 * Null for any other `?verify=` link (a web sign-up) and on hosts [parseRaftMessageLink] refuses.
 */
internal fun raftAppLoginVerificationLinkRequest(
    url: String,
    configuredWebUrl: String? = null,
    appHosts: Set<String> = RaftClientConfigAppHosts.current,
): String? {
    val parsed = url.toRaftLinkUrl() ?: return null
    if (!parsed.isAllowedMessageLinkOrigin(configuredWebUrl?.toRaftLinkUrl(), appHosts)) return null
    if (parsed.path != "" && parsed.path != "/") return null
    val query = parsed.queryParameters() ?: return null
    if (query["verify"].isNullOrBlank()) return null
    val returnTo = query["returnTo"] ?: return null
    if (returnTo.substringBefore('?') != "/login/app") return null
    return RaftLinkUrl("https", parsed.host, null, "", returnTo.substringAfter('?', "")).queryParameters()
        ?.get("request")
        ?.takeIf(String::isNotBlank)
}

/**
 * Whether iOS reopens a sign-up email's link in its web sign-in sheet: only when the request it
 * names is the one this app is waiting for ([isWaitingFor], the stored pending request). The sheet
 * shares the sign-up's browser session, so the web verifies, lands on that request and calls back
 * as a normal browser login. A Raft Desktop sign-up has the same link but another request: that,
 * an expired request and a web sign-up go to in-app Safari instead.
 */
internal fun raftAppLoginVerificationLinkResumes(
    url: String,
    configuredWebUrl: String?,
    appHosts: Set<String> = RaftClientConfigAppHosts.current,
    isWaitingFor: (String) -> Boolean,
): Boolean = raftAppLoginVerificationLinkRequest(url, configuredWebUrl, appHosts)?.let(isWaitingFor) == true

/** The path and fragment of a link on one of Raft's own web origins ([raftOwnWebLinkPath]). */
internal data class RaftOwnWebLinkPath(
    /** The path as written, `/` first; empty for a bare origin. */
    val path: String,
    /** What follows `#`, or null when there is none. */
    val fragment: String?,
)

/**
 * The path of [url] when it is on an origin [parseRaftMessageLink] trusts (production hosts, the
 * client config's [appHosts], exactly the Developer environment's [configuredWebUrl]); null for
 * any other URL, which the caller leaves to the browser.
 */
internal fun raftOwnWebLinkPath(
    url: String,
    configuredWebUrl: String? = null,
    appHosts: Set<String> = RaftClientConfigAppHosts.current,
): RaftOwnWebLinkPath? {
    val parsed = url.toRaftLinkUrl() ?: return null
    if (!parsed.isAllowedMessageLinkOrigin(configuredWebUrl?.toRaftLinkUrl(), appHosts)) return null
    return RaftOwnWebLinkPath(
        path = parsed.path,
        fragment = url.trim().substringAfter('#', missingDelimiterValue = "").takeIf { '#' in url },
    )
}

internal fun isRaftLinkIdentifier(value: String): Boolean =
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

private fun RaftLinkUrl.isAllowedMessageLinkOrigin(configured: RaftLinkUrl?, appHosts: Set<String>): Boolean {
    if (scheme == "https" && host in RaftMessageLinkProductionHosts && (port == null || port == 443)) return true
    if (scheme == "https" && host in appHosts && (port == null || port == 443)) return true
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

/** An Artifact link waiting for a ready app shell, as [RaftPendingMessageLink] waits. */
internal data class RaftPendingArtifactLink(
    val id: Long,
    val target: ArtifactLinkTarget,
    val receivedAtEpochMillis: Long,
    val source: String,
)

/**
 * The process-wide single slot for an external Artifact link ([parseRaftArtifactDeepLink]): the
 * same rules as [RaftMessageLinkInbox] (newest wins, taken once by a shell with a ready session,
 * dropped after [ttlMillis]).
 */
internal class RaftArtifactLinkInbox(
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val ttlMillis: Long = RaftMessageLinkInbox.DefaultTtlMillis,
) {
    private val lock = SynchronizedObject()
    private var nextId = 1L
    private val mutablePending = MutableStateFlow<RaftPendingArtifactLink?>(null)
    val pending: StateFlow<RaftPendingArtifactLink?> = mutablePending.asStateFlow()

    fun submit(target: ArtifactLinkTarget, source: String): RaftPendingArtifactLink = synchronized(lock) {
        RaftPendingArtifactLink(nextId++, target, nowEpochMillis(), source).also { mutablePending.value = it }
    }

    /** Takes the pending link [id]; null when it was already taken, replaced, or expired (then cleared). */
    fun take(id: Long): RaftPendingArtifactLink? = synchronized(lock) {
        val current = mutablePending.value?.takeIf { it.id == id } ?: return null
        mutablePending.value = null
        current.takeIf { nowEpochMillis() - it.receivedAtEpochMillis <= ttlMillis }
    }

    fun clear() = synchronized(lock) { mutablePending.value = null }

    companion object {
        /** The slot the platform ingresses and every app shell of this process share. */
        val Process: RaftArtifactLinkInbox = RaftArtifactLinkInbox()
    }
}

/** An external invite link waiting for a ready app shell, as [RaftPendingMessageLink] waits. */
internal data class RaftPendingExternalInviteLink(
    val id: Long,
    val token: String,
    val receivedAtEpochMillis: Long,
    val source: String,
)

/**
 * The process-wide single slot for an external invite link ([parseRaftExternalInviteLink]): the
 * same rules as [RaftMessageLinkInbox] (newest wins, taken once by a shell with a ready session,
 * dropped after [ttlMillis]), so a link opened signed out opens once signed in.
 */
internal class RaftExternalInviteLinkInbox(
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val ttlMillis: Long = RaftMessageLinkInbox.DefaultTtlMillis,
) {
    private val lock = SynchronizedObject()
    private var nextId = 1L
    private val mutablePending = MutableStateFlow<RaftPendingExternalInviteLink?>(null)
    val pending: StateFlow<RaftPendingExternalInviteLink?> = mutablePending.asStateFlow()

    fun submit(token: String, source: String): RaftPendingExternalInviteLink = synchronized(lock) {
        RaftPendingExternalInviteLink(nextId++, token, nowEpochMillis(), source).also { mutablePending.value = it }
    }

    /** Takes the pending link [id]; null when it was already taken, replaced, or expired (then cleared). */
    fun take(id: Long): RaftPendingExternalInviteLink? = synchronized(lock) {
        val current = mutablePending.value?.takeIf { it.id == id } ?: return null
        mutablePending.value = null
        current.takeIf { nowEpochMillis() - it.receivedAtEpochMillis <= ttlMillis }
    }

    fun clear() = synchronized(lock) { mutablePending.value = null }

    companion object {
        /** The slot the platform ingresses and every app shell of this process share. */
        val Process: RaftExternalInviteLinkInbox = RaftExternalInviteLinkInbox()
    }
}

/**
 * Whether a shell takes the pending link now: only with a signed-in, server-ready session
 * ([sessionReady]); before that (signed out, restoring, choosing a server) it keeps waiting.
 */
internal fun raftMessageLinkShouldTake(pending: RaftPendingMessageLink?, sessionReady: Boolean): Boolean =
    pending != null && sessionReady

/**
 * Platform entry for an external message link (Android MainActivity, iOS scene delegate): a web
 * link, or a Raft Desktop `raft://` link ([raftSchemeLinkToWebLink]). Returns
 * false when the URL is not a Raft message link this app takes; the caller then leaves it to the
 * browser. On true the link waits in [RaftMessageLinkInbox.Process] for the app shell, or, for an
 * Artifacts home address ([parseRaftArtifactDeepLink]), in [RaftArtifactLinkInbox.Process], or, for
 * an external channel invite ([parseRaftExternalInviteLink]), in [RaftExternalInviteLinkInbox.Process].
 */
object RaftMessageLinkIngress {
    /** [configuredWebUrl] is the Developer environment's web URL; null reads the app-scope one. */
    fun accept(url: String, source: String, configuredWebUrl: String? = null): Boolean {
        val webUrl = configuredWebUrl ?: appScopeWebUrlOrNull()
        val link =
            parseRaftMessageLink(url, webUrl)
                ?: raftSchemeLinkToWebLink(url, webUrl)?.let { parseRaftMessageLink(it, webUrl) }
        if (link == null) {
            val artifact =
                parseRaftArtifactDeepLink(url, webUrl)
                    ?: raftSchemeLinkToWebLink(url, webUrl)?.let { parseRaftArtifactDeepLink(it, webUrl) }
            if (artifact != null) {
                val pending = RaftArtifactLinkInbox.Process.submit(artifact, source.raftLinkLogToken())
                raftLogInfo(
                    RaftMessageLinkLogTag,
                    "artifact_link_ingress result=pending id=${pending.id} source=${pending.source} version=${artifact.version != null}"
                )
                return true
            }
            val inviteToken = parseRaftExternalInviteLink(url, webUrl)
            if (inviteToken != null) {
                val pending = RaftExternalInviteLinkInbox.Process.submit(inviteToken, source.raftLinkLogToken())
                raftLogInfo(RaftMessageLinkLogTag, "external_invite_link_ingress result=pending id=${pending.id} source=${pending.source}")
                return true
            }
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

    /** [raftAppLoginVerificationLinkResumes] against the stored pending sign-in; [configuredWebUrl] as in [accept]. */
    fun resumesPendingAppLogin(url: String, configuredWebUrl: String? = null): Boolean =
        raftAppLoginVerificationLinkResumes(url, configuredWebUrl ?: appScopeWebUrlOrNull(), isWaitingFor = RaftAppLogin::isWaitingFor)

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
