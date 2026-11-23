package build.raft.app

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/*
 * App entries (#356): an App opened from one of Raft's own entry points (the Apps page, an App's link
 * card) opens in the in-app browser signed in. Its main frame's GET navigations to the App's origin
 * carry `Raft-Host: android` (or `ios`), which the App's Raft SDK reads to start Login with Raft by
 * itself (slock packages/raft-sdk hostAutoSignIn.ts). The rules, pure so each one is unit-tested
 * (RaftAppEntryRulesTest):
 * - raftAppEntryScopeOrigin: the origin an entry is scoped to, or none (an ordinary open);
 * - raftHostHeaderValue: this platform's header value;
 * - raftHostNavigationDecision: what each main-frame navigation of the page does about the header;
 * - RaftAppEntryRegistry: the in-memory marks. A page's data carries only a random token, so page data
 *   alone (a deep link, a restored or replaced page) never marks a page, and a restart leaves none.
 */

/** The request header (slock raft-sdk `RAFT_HOST_HEADER`). */
internal const val RAFT_HOST_HEADER = "Raft-Host"

/** Re-issues with the header one navigation chain may make; the chain starts again at each commit. */
internal const val RAFT_HOST_REISSUE_BUDGET = 10

/**
 * What the opener of an App entry knows: the App's origin ([scopeOrigin], checked again by
 * [raftAppEntryScopeOrigin] when the page claims the entry), the App's name for the page's header, and
 * the Server the sign-in sheet picks first (the conversation's for a card, the Apps page's for the Apps
 * page).
 */
internal data class RaftAppEntryRequest(
    val scopeOrigin: String,
    val appName: String? = null,
    val preferredServerId: String? = null,
    val appLogoUrl: String? = null,
)

/** `Raft-Host: [value]` on the main frame's GET navigations to [origin], and nowhere else: what the WebView is given. */
internal data class RaftHostHeader(val origin: String, val value: String)

/** An entry page's context, built when the page claims its entry ([raftAppEntryContext]). */
internal data class RaftAppEntryContext(
    /** [raftHostHeaderValue]: null on a platform that sends none. */
    val headerValue: String?,
    val scopeOrigin: String,
    val appName: String?,
    val preferredServerId: String?,
    val appLogoUrl: String? = null,
) {
    /** The header the page's WebView sends, or null when there is none to send. */
    val raftHost: RaftHostHeader?
        get() = headerValue?.let { RaftHostHeader(origin = scopeOrigin, value = it) }
}

/** The origin of [url] the entry rules compare ([raftBrowserUrlOf]); null for one with credentials or one they can't read. */
internal fun raftAppEntryOriginOf(url: String): String? = raftBrowserUrlOf(url)?.takeIf { it.userInfo == null }?.origin

/**
 * The origin an entry opening [url] is scoped to, or null: the entry then opens as an ordinary link,
 * with no header (slock desktop `parseWebPreviewAppOrigin`). [origin] must be exactly an origin, in
 * the spelling [raftAppEntryOriginOf] gives (lower case, no default port, no path), and exactly
 * [url]'s, so both have an ASCII host and no credentials. It is https, or http on
 * [RaftBrowserDebugLoopbackHosts] only in a [debug] build, and never one of Raft's own hosts
 * ([raftHosts], [raftBrowserRaftHosts]).
 */
internal fun raftAppEntryScopeOrigin(
    origin: String,
    url: String,
    raftHosts: Collection<String>,
    debug: Boolean,
): String? {
    val scope = raftBrowserUrlOf(origin)?.takeIf { it.userInfo == null && it.origin == origin } ?: return null
    if (raftAppEntryOriginOf(url) != origin) return null
    val secure = scope.scheme == "https"
    val loopback = debug && scope.scheme == "http" && scope.host in RaftBrowserDebugLoopbackHosts
    if (!secure && !loopback) return null
    return origin.takeUnless { raftBrowserIsRaftHost(scope.host, raftHosts) }
}

/**
 * This platform's `Raft-Host` value from [platformName] (`raftPlatformName()`: `android`, `iOS`), as
 * the SDK knows them (slock raft-sdk `RAFT_HOST_PLATFORMS`), or null: Compose desktop has no in-app
 * browser and sends none.
 */
internal fun raftHostHeaderValue(platformName: String): String? =
    when (platformName.lowercase()) {
        "android" -> "android"
        "ios" -> "ios"
        else -> null
    }

/**
 * The entry's context for the page that claimed it ([RaftAppEntryRegistry.claim]) to open [url]: the
 * scope checked again, the header named for [platformName]. Null when the scope does not hold: the page
 * is then an ordinary one.
 */
internal fun raftAppEntryContext(
    request: RaftAppEntryRequest,
    url: String,
    raftHosts: Collection<String>,
    debug: Boolean,
    platformName: String,
): RaftAppEntryContext? {
    val scopeOrigin = raftAppEntryScopeOrigin(request.scopeOrigin, url, raftHosts, debug) ?: return null
    return RaftAppEntryContext(
        headerValue = raftHostHeaderValue(platformName),
        scopeOrigin = scopeOrigin,
        appName = request.appName,
        preferredServerId = request.preferredServerId,
        appLogoUrl = request.appLogoUrl,
    )
}

/**
 * The header value a load the app itself makes of [url] goes out with (the page's first load, its
 * restore, a re-issue, an answer): [host]'s on its origin, else none.
 */
internal fun raftHostHeaderValueFor(url: String, host: RaftHostHeader?): String? =
    host?.takeIf { raftAppEntryOriginOf(url) == it.origin }?.value

/** A main-frame navigation as the platform tells them apart. */
internal enum class RaftHostNavigationKind {
    /** The page's own load of its address, or the restore of it. */
    Initial,

    /** A navigation the shown document started: a link, a GET form, a script. */
    Page,

    /** A server redirect of a navigation. */
    Redirect,

    /** Back or forward. */
    History,

    /** A reload, or a form sent again. */
    Reload,
}

internal enum class RaftHostNavigationDecision {
    /** The navigation goes on as it is. */
    Allow,

    /** The navigation is stopped and its address loaded again, with the header. */
    ReissueWithHeader,

    /** The navigation is stopped and its address loaded again, without the header. */
    ReissueWithoutHeader,
}

private val RaftHostReissuableKinds = setOf(RaftHostNavigationKind.Page, RaftHostNavigationKind.Redirect)

/**
 * What the WebView does about the header for a main-frame navigation to [url] (#356 §3). [host] is the
 * page's [RaftAppEntryContext.raftHost] (null: not an entry page). [carriesHeader]: the request has the
 * header, or its chain was sent with it and nothing committed since (where a platform can't see a
 * redirect's headers). [budgetLeft]: re-issues with the header this chain may still make, from
 * [RAFT_HOST_REISSUE_BUDGET].
 * - [RaftHostNavigationDecision.ReissueWithHeader]: a GET [RaftHostNavigationKind.Page] or
 *   [RaftHostNavigationKind.Redirect] to exactly [host]'s origin, without the header, while the budget
 *   lasts (no platform adds it to the page's own links, and Android's load headers don't follow a
 *   redirect).
 * - [RaftHostNavigationDecision.ReissueWithoutHeader]: a GET carrying the header to any address that is
 *   not exactly on [host]'s origin, whatever the budget (WebKit copies a request's headers onto its
 *   redirect, so a redirect to another origin would take it there). Once re-issued without it, the
 *   request no longer carries it.
 * - [RaftHostNavigationDecision.Allow]: anything else. A POST can't be re-issued (its body is the
 *   page's); back, forward and reload can't without adding history; an address that is not http(s) has
 *   no request header to send.
 */
internal fun raftHostNavigationDecision(
    url: String,
    method: String,
    kind: RaftHostNavigationKind,
    carriesHeader: Boolean,
    host: RaftHostHeader?,
    budgetLeft: Int,
): RaftHostNavigationDecision {
    if (!method.equals("GET", ignoreCase = true) || !raftUrlIsHttpOrHttps(url)) return RaftHostNavigationDecision.Allow
    val onScope = host != null && raftAppEntryOriginOf(url) == host.origin
    return when {
        carriesHeader && !onScope -> RaftHostNavigationDecision.ReissueWithoutHeader
        !carriesHeader && onScope && kind in RaftHostReissuableKinds && budgetLeft > 0 ->
            RaftHostNavigationDecision.ReissueWithHeader
        else -> RaftHostNavigationDecision.Allow
    }
}

/** The scheme only, so an http(s) address the rules can't otherwise read still loses the header. */
private fun raftUrlIsHttpOrHttps(url: String): Boolean {
    val scheme = url.trim().substringBefore("://", missingDelimiterValue = "").lowercase()
    return scheme == "https" || scheme == "http"
}

/**
 * The App entries of this process: a random token (the one thing a page's data carries) to the address
 * the entry opens and its request. In memory only. An entry is claimed by one page, for exactly its
 * address; that page may claim it again (composed again) and releases it when it closes.
 */
internal class RaftAppEntryRegistry(private val newToken: () -> String = ::raftAppEntryToken) {
    private class Entry(val url: String, val request: RaftAppEntryRequest) {
        var owner: String? = null
    }

    private val lock = SynchronizedObject()
    private val entries = HashMap<String, Entry>()

    /** Holds [request] for the page about to open [url]; the token returned goes in that page's data. */
    fun register(url: String, request: RaftAppEntryRequest): String =
        synchronized(lock) {
            val token = newToken()
            entries[token] = Entry(url, request)
            token
        }

    /**
     * The request [token] holds, when it was registered for exactly [url] and no other page has claimed
     * it; from then on it is [owner]'s (the page's `pagerId`). Null for any other token, address or page.
     */
    fun claim(token: String, url: String, owner: String): RaftAppEntryRequest? =
        synchronized(lock) {
            val entry = entries[token]?.takeIf { it.url == url && (it.owner == null || it.owner == owner) }
            entry?.owner = owner
            entry?.request
        }

    /** Drops [token]'s entry when it is [owner]'s or nobody's yet: the page that had it in its data closed. */
    fun release(token: String, owner: String) {
        synchronized(lock) {
            val entry = entries[token] ?: return
            if (entry.owner == null || entry.owner == owner) entries.remove(token)
        }
    }

    /** Drops every entry, as a restart does. */
    fun reset() {
        synchronized(lock) { entries.clear() }
    }

    companion object {
        /** The process' one registry: what an entry's opener fills and the in-app browser page claims from. */
        val process: RaftAppEntryRegistry = RaftAppEntryRegistry()
    }
}

/** A version 4 UUID from the platform's secure random source (`Uuid.random`). */
@OptIn(ExperimentalUuidApi::class)
private fun raftAppEntryToken(): String = Uuid.random().toString()
