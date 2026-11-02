package build.raft.app

import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.artifactCanonicalId
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.ArtifactViewerPanel
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.artifactWebUrl
import build.raft.app.data.isArtifactContentHost
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The in-app Artifact viewer: a native page whose header is painted from the opener's card at
 * once, over the web viewer's own page (`<web>/artifacts/<id>[/v/<n>]`, the canonical link's page
 * "phones open in a web view") in a signed-in WebView. The web page keeps every check it has (the
 * frame ticket from its viewer answer, Porthole's sandbox, the content-domain pin). It is opened
 * with the embed contract ([raftArtifactViewerEmbeddedUrl]), so the page draws no header under the
 * native one, and an address that leaves the Artifact closes the viewer
 * ([raftArtifactViewerNavigation]): its original message opens natively.
 *
 * Tenny's WebView rules, all enforced here and in the platform hosts:
 * 1. The session (`slock_access_token` in localStorage) is written only into a top-level document
 *    of the configured web origin: the platform script is scoped to that origin and top-level
 *    frames, and the script itself returns unless `window.top === window` and `location.origin`
 *    is the web origin ([raftArtifactViewerGuardedScript]). Never the content domain.
 * 2. Every top-level document stays on the web origin ([RaftWebViewConfig.mainFrameOrigin]); a
 *    top-level navigation anywhere else is blocked and opened in the system browser
 *    ([raftArtifactViewerBlockedNavigationTarget]). Sub-frames (the content frame) load freely.
 * 3. No native bridge: host events stay off (no message listener, no JS interface), so neither the
 *    page nor the content frame can call into the app.
 *
 * Tenny's cache rules ([RaftArtifactViewerCache]): per web origin + account + server, cleared on
 * sign-out or a switch, nothing reused for an unknown subject; a 403/404 replaces the screen and
 * drops the cached copy; a frame ticket is never held by the app at all.
 */
internal data class RaftArtifactViewerScope(
    /** `https://host[:port]` of the environment's web app. */
    val webOrigin: String,
    val environmentId: String,
    val accountId: String,
    val serverId: String,
) {
    /** The WebView keep-alive key: a kept document is never shown under another scope. */
    val keepAliveKey: String get() = "artifact-viewer|$webOrigin|$environmentId|$accountId|$serverId"

    companion object {
        /** Null (no reuse, no cache) when any part of the subject is unknown. */
        fun of(webBaseUrl: String?, owner: AuthenticatedRouteOwner?): RaftArtifactViewerScope? {
            val origin = webBaseUrl?.let(::raftWebOriginOf) ?: return null
            val o = owner ?: return null
            if (o.environmentId.isBlank() || o.accountId.isBlank() || o.serverId.isBlank()) return null
            return RaftArtifactViewerScope(origin, o.environmentId.trim(), o.accountId.trim(), o.serverId.trim())
        }
    }
}

/** What the native header shows: an opener's card, or the last one seen in this scope. Never a ticket or URL. */
internal data class RaftArtifactViewerHeader(
    val id: String,
    val title: String,
    val kind: ArtifactKind,
    /** The version the viewer opens (the pinned one, else the latest known). */
    val version: Int?,
) {
    companion object {
        fun of(summary: ArtifactSummary, target: ArtifactLinkTarget): RaftArtifactViewerHeader =
            RaftArtifactViewerHeader(
                id = summary.id,
                title = summary.title,
                kind = summary.kind,
                version = target.version ?: summary.latestVersion.takeIf { it > 0 } ?: summary.version.takeIf { it > 0 },
            )
    }
}

/**
 * The viewer's memory across opens: last headers, and which Artifact the kept WebView document
 * shows. One scope at a time: a different scope (another account, server or environment) clears
 * everything first, and a null scope (unknown subject) reads and writes nothing.
 */
internal class RaftArtifactViewerCache(
    private val maxEntries: Int = 64,
    /** A kept document must be dropped (another scope, a 403/404, sign-out). */
    private val onKeptDocumentDropped: () -> Unit = {},
) {
    private var scope: RaftArtifactViewerScope? = null
    private val headers = LinkedHashMap<String, RaftArtifactViewerHeader>()
    private var keptDocument: RaftArtifactKeptDocument? = null

    fun header(scope: RaftArtifactViewerScope?, id: String): RaftArtifactViewerHeader? =
        if (scope == null || scope != this.scope) null else headers[id]

    fun remember(scope: RaftArtifactViewerScope?, header: RaftArtifactViewerHeader) {
        if (scope == null || header.id.isBlank()) return
        adopt(scope)
        headers.remove(header.id)
        headers[header.id] = header
        while (headers.size > maxEntries) headers.remove(headers.keys.first())
    }

    /**
     * Records that the kept WebView now shows [target] at [documentUrl]. Refused (and nothing
     * kept) unless the URL is a top-level page of the scope's own web origin: never the content
     * domain, never anything carrying a frame ticket.
     */
    fun rememberKeptDocument(scope: RaftArtifactViewerScope?, target: ArtifactLinkTarget, documentUrl: String): Boolean {
        if (scope == null) return false
        adopt(scope)
        if (!raftArtifactViewerDocumentUrlIsKeepable(scope.webOrigin, documentUrl)) {
            dropKeptDocument()
            return false
        }
        keptDocument = RaftArtifactKeptDocument(target, documentUrl)
        return true
    }

    /** A WebView was prewarmed for [scope] on the viewer's route with no Artifact. */
    fun markKeptWarm(scope: RaftArtifactViewerScope?) {
        if (scope == null) return
        adopt(scope)
        keptDocument = null
    }

    /** Whether the kept document shows exactly [target] for [scope] (show it at once): either id form. */
    fun keptDocumentShows(scope: RaftArtifactViewerScope?, target: ArtifactLinkTarget): Boolean =
        scope != null && scope == this.scope && keptDocument?.target?.key == target.key

    /** Whether a kept document of [scope] may be reused for another Artifact (client-side navigation). */
    fun keptDocumentUsable(scope: RaftArtifactViewerScope?): Boolean = scope != null && scope == this.scope

    /** 403/404 (the Artifact is gone or not theirs): its header and any kept document showing it go. */
    fun evict(scope: RaftArtifactViewerScope?, id: String) {
        if (scope == null || scope != this.scope) return
        headers.remove(id)
        if (keptDocument?.target?.canonicalId == artifactCanonicalId(id)) dropKeptDocument()
    }

    /**
     * The viewer's document left its Artifact (its original message, the server after a Delete):
     * what it shows now is no Artifact page, so it is not kept for the next open.
     */
    fun forgetKeptDocument(scope: RaftArtifactViewerScope?) {
        if (scope == null || scope != this.scope) return
        dropKeptDocument()
    }

    /** Sign-out, an account/server/environment switch. */
    fun clear() {
        scope = null
        headers.clear()
        dropKeptDocument()
    }

    private fun adopt(next: RaftArtifactViewerScope) {
        if (scope == next) return
        // Another subject: nothing of the previous one survives (a first scope has nothing to drop;
        // a WebView prewarmed for it is keyed by the same scope).
        if (scope != null) clear()
        scope = next
    }

    private fun dropKeptDocument() {
        keptDocument = null
        // Always tell the host: a WebView may be kept (prewarmed) with no document recorded yet.
        onKeptDocumentDropped()
    }
}

internal data class RaftArtifactKeptDocument(val target: ArtifactLinkTarget, val documentUrl: String)

/** The process' viewer memory; dropping it also destroys the kept WebView. */
internal object RaftArtifactViewerMemory {
    val cache = RaftArtifactViewerCache(onKeptDocumentDropped = ::raftWebViewKeepAliveClear)

    /** Sign-out / account switch (with the other per-viewer memory caches). */
    fun clear() = cache.clear()
}

/** `scheme://host[:port]`, lower case, default ports dropped; null for anything but http(s). */
internal fun raftWebOriginOf(url: String): String? {
    val value = url.trim()
    val schemeEnd = value.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = value.substring(0, schemeEnd).lowercase()
    if (scheme != "https" && scheme != "http") return null
    val rest = value.substring(schemeEnd + 3)
    val authority = rest.substring(0, rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it })
    if (authority.isEmpty() || '@' in authority || authority.startsWith("[")) return null
    val host = authority.substringBefore(':').lowercase().removeSuffix(".")
    if (host.isEmpty() || host.any { !(it.isLetterOrDigit() || it == '.' || it == '-') }) return null
    val portText = authority.substringAfter(':', "")
    val port =
        if (portText.isEmpty()) null
        else portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    val defaultPort = if (scheme == "https") 443 else 80
    return if (port == null || port == defaultPort) "$scheme://$host" else "$scheme://$host:$port"
}

/** Only a top-level page of [webOrigin] itself, never the content domain or a ticket-bearing URL. */
internal fun raftArtifactViewerDocumentUrlIsKeepable(webOrigin: String, documentUrl: String): Boolean {
    val origin = raftWebOriginOf(documentUrl) ?: return false
    if (origin != raftWebOriginOf(webOrigin)) return false
    val host = origin.substringAfter("://").substringBefore(':')
    if (isArtifactContentHost(host)) return false
    val lower = documentUrl.lowercase()
    return "ticket=" !in lower && "/_raft/" !in lower
}

/** The web viewer page for [target]: the canonical link's page on the environment's web origin. */
internal fun raftArtifactViewerPageUrl(webBaseUrl: String, target: ArtifactLinkTarget): String =
    artifactWebUrl(webBaseUrl, target)

/** The path of [raftArtifactViewerPageUrl] (what client-side navigation pushes). */
internal fun raftArtifactViewerPagePath(target: ArtifactLinkTarget): String =
    "/artifacts/" + target.id + (target.version?.let { "/v/$it" } ?: "")

/**
 * The embed contract's query (`embed=raft-settings-v1&shell=host`, slock `packages/web/src/embed.ts`,
 * honoured by the Artifact page since slock #11691) with `chrome=none` (slock #12362): the App draws
 * the one bar (Back, the title, where it lives, the version, who read it, Comments, ⋮), so the page
 * draws only its content. The web reads it once, from the document's entry URL, and keeps it for
 * the session.
 */
internal val RaftArtifactViewerEmbedQuery: String = "embed=$SharedSettingsEmbedContract&shell=$SharedSettingsEmbedHostShell&chrome=none"

private val RaftArtifactViewerEmbedKeys = setOf("embed", "shell", "chrome")

/**
 * [url] with the embed contract in its query: any `embed`/`shell`/`chrome` already there is replaced, every
 * other parameter, the path (a pinned `/v/<n>` too) and the fragment stay as they are.
 */
internal fun raftArtifactViewerEmbeddedUrl(url: String): String {
    val fragment = url.indexOf('#').let { if (it < 0) "" else url.substring(it) }
    val beforeFragment = url.removeSuffix(fragment)
    val path = beforeFragment.substringBefore('?')
    val kept =
        beforeFragment.substringAfter('?', missingDelimiterValue = "")
            .split('&')
            .filter { it.isNotEmpty() && it.substringBefore('=') !in RaftArtifactViewerEmbedKeys }
    return path + "?" + (kept + RaftArtifactViewerEmbedQuery).joinToString("&") + fragment
}

/** What the viewer's WebView loads: [raftArtifactViewerPageUrl] under the App's title bar. */
internal fun raftArtifactViewerEmbeddedPageUrl(webBaseUrl: String, target: ArtifactLinkTarget): String =
    raftArtifactViewerEmbeddedUrl(raftArtifactViewerPageUrl(webBaseUrl, target))

/**
 * The page a prewarmed WebView boots: the canonical link's route with no Artifact (an address the
 * web reads as no Artifact, so it locates nothing, reads nothing and moves no read position), so
 * the web app, its session and the viewer's code are ready when an Artifact is opened. It carries
 * the embed contract: the web latches it from this first address, so the Artifact this document
 * later routes to is drawn without its own header too.
 */
internal fun raftArtifactViewerWarmUrl(webBaseUrl: String): String =
    raftArtifactViewerEmbeddedUrl(webBaseUrl.trim().trimEnd('/') + "/artifacts/" + RAFT_ARTIFACT_VIEWER_WARM_SEGMENT)

internal const val RAFT_ARTIFACT_VIEWER_WARM_SEGMENT = "raft-app-warm"

/**
 * Wraps [body] so it runs only in a top-level document of [webOrigin] (Tenny's rule 1): whatever
 * frame or origin a platform hands it to, the content domain never gets the session.
 */
internal fun raftArtifactViewerGuardedScript(webOrigin: String, body: String): String {
    val origin = Json.encodeToString(String.serializer(), webOrigin)
    return """
        (function() {
          try {
            if (window.top !== window || window.location.origin !== $origin) return;
            $body
          } catch (error) {}
        })();
    """.trimIndent()
}

/**
 * Client-side navigation of a kept (already booted) web viewer document to [target]: the web app
 * routes with React Router's BrowserRouter, which follows `popstate`. The session is written again
 * first (the kept document may have been booted with an older access token).
 */
internal fun raftArtifactViewerNavigateScript(webOrigin: String, sessionWrites: String, target: ArtifactLinkTarget): String {
    val path = Json.encodeToString(String.serializer(), raftArtifactViewerPagePath(target))
    // The address keeps the embed contract (the web's keeper would put it back; this saves a write).
    val address = Json.encodeToString(String.serializer(), raftArtifactViewerPagePath(target) + "?" + RaftArtifactViewerEmbedQuery)
    return raftArtifactViewerGuardedScript(
        webOrigin,
        """
        $sessionWrites;
        var path = $path;
        if (window.location.pathname !== path) {
          var previous = window.history.state || {};
          var index = typeof previous.idx === 'number' ? previous.idx + 1 : 0;
          window.history.pushState({usr: null, key: Math.random().toString(36).slice(2, 10), idx: index}, '', $address);
          window.dispatchEvent(new PopStateEvent('popstate', {state: window.history.state}));
        }
        """.trimIndent(),
    )
}

/**
 * Opens or closes the page's list without a reload (slock #12364): its address's fragment becomes
 * `#panel=<panel>` (replaced, never a new history entry) and the page is told (`hashchange`, which it
 * follows in the host shell). The page writes the list it shows back to the fragment, which the
 * viewer reads from the address ([build.raft.app.data.artifactViewerPanelOf]). No bridge: only the
 * address.
 */
internal fun raftArtifactViewerPanelScript(webOrigin: String, panel: ArtifactViewerPanel): String {
    val hash = Json.encodeToString(String.serializer(), "#panel=" + panel.wire)
    return raftArtifactViewerGuardedScript(
        webOrigin,
        """
        var hash = $hash;
        if (window.location.hash !== hash) {
          var url = new URL(window.location.href);
          url.hash = hash;
          window.history.replaceState(window.history.state, '', url.toString());
          window.dispatchEvent(new HashChangeEvent('hashchange'));
        }
        """.trimIndent(),
    )
}

internal enum class RaftArtifactViewerBlockedTarget { SystemBrowser, Ignore }

/**
 * A top-level navigation the origin lock refused: an http(s) page opens in the system browser
 * (never in the signed-in WebView); anything else (blank, other schemes) is dropped.
 */
internal fun raftArtifactViewerBlockedNavigationTarget(url: String, webOrigin: String): RaftArtifactViewerBlockedTarget {
    val origin = raftWebOriginOf(url) ?: return RaftArtifactViewerBlockedTarget.Ignore
    if (origin == raftWebOriginOf(webOrigin)) return RaftArtifactViewerBlockedTarget.Ignore
    return RaftArtifactViewerBlockedTarget.SystemBrowser
}

/**
 * Where a top-level address of the viewer's WebView takes the viewer. Under the embed contract the
 * page is the Artifact alone: its address is `/artifacts/<id>` (or `/v/<n>`, a version picked in
 * its ⋯); anything else the web app routes to (its original message, the server after a Delete)
 * is not shown under the Artifact's title bar.
 */
internal sealed interface RaftArtifactViewerNavigation {
    /**
     * Still this Artifact; [version] is the one in the address (null: the latest). [shown]: the
     * `shown=<n>` the page adds (replaced, not pushed) when it read the latest again in place
     * (slock #12043; host-shell embed, unpinned only); null when absent or malformed.
     */
    data class Stay(val version: Int?, val shown: Int? = null) : RaftArtifactViewerNavigation

    /** A Raft message (the Artifact's original message): close the viewer, open it natively. */
    data class OpenMessage(val url: String) : RaftArtifactViewerNavigation

    /** Off the web origin: the system browser (never in the signed-in WebView). */
    data class SystemBrowser(val url: String) : RaftArtifactViewerNavigation

    /** Anywhere else in the web app: close the viewer. */
    data object Close : RaftArtifactViewerNavigation

    /** Not a page (blank, another scheme): nothing to do. */
    data object Ignore : RaftArtifactViewerNavigation
}

/**
 * [url] (a top-level address of the viewer's WebView showing [artifactId]) as a
 * [RaftArtifactViewerNavigation]. A message link is one [parseRaftMessageLink] takes on [webOrigin].
 */
internal fun raftArtifactViewerNavigation(url: String, webOrigin: String, artifactId: String): RaftArtifactViewerNavigation {
    val origin = raftWebOriginOf(url) ?: return RaftArtifactViewerNavigation.Ignore
    if (origin != raftWebOriginOf(webOrigin)) return RaftArtifactViewerNavigation.SystemBrowser(url)
    val pathAndQuery = url.substringAfter("://").let { rest ->
        val start = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (start < 0) "/" else rest.substring(start).substringBefore('#')
    }
    val path = if (pathAndQuery.startsWith('/')) pathAndQuery.substringBefore('?') else "/"
    val segments = path.split('/').filter { it.isNotEmpty() }
    // `/artifacts/<id>[/v/<n>]`, or the same under a server (`/s/<slug>/artifacts/…`).
    val artifact = if (segments.size >= 2 && segments[0] == "s") segments.drop(2) else segments
    // Either id form: the web writes the short id when a version is picked (slock `artifactPagePath`).
    val shown = artifact.getOrNull(1)?.let(::artifactCanonicalId)
    if (artifact.firstOrNull() == "artifacts" && shown == artifactCanonicalId(artifactId)) {
        // `shown` never decides staying or leaving: only the path does.
        val shownParam = artifactShownParam(pathAndQuery.substringAfter('?', ""))
        when (artifact.size) {
            2 -> return RaftArtifactViewerNavigation.Stay(null, shownParam)
            4 -> if (artifact[2] == "v") artifact[3].toIntOrNull()?.takeIf { it > 0 }?.let { return RaftArtifactViewerNavigation.Stay(it, shownParam) }
        }
    }
    if (parseRaftMessageLink(url, configuredWebUrl = webOrigin) != null) return RaftArtifactViewerNavigation.OpenMessage(url)
    return RaftArtifactViewerNavigation.Close
}

/** The positive integer value of the first `shown` query parameter in [query], else null. */
private fun artifactShownParam(query: String): Int? =
    query.split('&').firstOrNull { it.substringBefore('=') == "shown" }
        ?.substringAfter('=', "")?.toIntOrNull()?.takeIf { it > 0 }

/**
 * The version the native header shows, once the page has an address: the pinned one (`/v/<n>`),
 * else the one the page says it shows (`shown=<n>`, added when View latest on the latest read it
 * again in place, slock #12043), else the latest as last read. Before any address: the opener's
 * card or the cached header.
 */
internal fun raftArtifactViewerShownVersion(
    addressed: RaftArtifactViewerNavigation.Stay?,
    latestVersion: Int?,
    headerVersion: Int?,
): Int? = addressed?.let { it.version ?: it.shown ?: latestVersion } ?: headerVersion

/**
 * Whether the page moving to [next] needs the latest version read again: from a pinned version to
 * the latest (View latest on a pinned version, Back to Latest), where the latest may have been
 * published after the viewer opened.
 */
internal fun raftArtifactViewerRereadsLatest(
    previous: RaftArtifactViewerNavigation.Stay?,
    next: RaftArtifactViewerNavigation.Stay,
): Boolean = previous?.version != null && next.version == null

/**
 * The bottom padding of the viewer's WebView, in px. Where the WebView does not inset itself
 * ([hostPadsWebView]: Android, whose window is edge-to-edge, so neither the keyboard nor the
 * navigation bar resizes it and the page gets no `env(safe-area-inset-bottom)`), the web page
 * ends above the keyboard while it is up, else above the navigation bar: the IME inset already
 * covers the navigation bar, so the larger one. Elsewhere (iOS: WKWebView applies the safe area
 * and the keyboard itself) nothing.
 */
internal fun raftArtifactViewerWebBottomInsetPx(
    imeBottomPx: Int,
    navigationBarBottomPx: Int,
    hostPadsWebView: Boolean,
): Int = if (hostPadsWebView) maxOf(imeBottomPx, navigationBarBottomPx).coerceAtLeast(0) else 0
