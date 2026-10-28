package build.raft.app

import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.artifactWebUrl
import build.raft.app.data.isArtifactContentHost
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The in-app Artifact viewer: a native page whose header is painted from the opener's card at
 * once, over the web viewer's own page (`<web>/artifacts/<id>[/v/<n>]`, the canonical link's page
 * "phones open in a web view") in a signed-in WebView. The web page keeps every check it has (the
 * frame ticket from its viewer answer, Porthole's sandbox, the content-domain pin).
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

    /** Whether the kept document shows exactly [target] for [scope] (show it at once). */
    fun keptDocumentShows(scope: RaftArtifactViewerScope?, target: ArtifactLinkTarget): Boolean =
        scope != null && scope == this.scope && keptDocument?.target == target

    /** Whether a kept document of [scope] may be reused for another Artifact (client-side navigation). */
    fun keptDocumentUsable(scope: RaftArtifactViewerScope?): Boolean = scope != null && scope == this.scope

    /** 403/404 (the Artifact is gone or not theirs): its header and any kept document showing it go. */
    fun evict(scope: RaftArtifactViewerScope?, id: String) {
        if (scope == null || scope != this.scope) return
        headers.remove(id)
        if (keptDocument?.target?.id == id) dropKeptDocument()
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
 * The page a prewarmed WebView boots: the canonical link's route with no Artifact (an address the
 * web reads as no Artifact, so it locates nothing, reads nothing and moves no read position), so
 * the web app, its session and the viewer's code are ready when an Artifact is opened.
 */
internal fun raftArtifactViewerWarmUrl(webBaseUrl: String): String =
    webBaseUrl.trim().trimEnd('/') + "/artifacts/" + RAFT_ARTIFACT_VIEWER_WARM_SEGMENT

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
    return raftArtifactViewerGuardedScript(
        webOrigin,
        """
        $sessionWrites;
        var path = $path;
        if (window.location.pathname !== path) {
          var previous = window.history.state || {};
          var index = typeof previous.idx === 'number' ? previous.idx + 1 : 0;
          window.history.pushState({usr: null, key: Math.random().toString(36).slice(2, 10), idx: index}, '', path);
          window.dispatchEvent(new PopStateEvent('popstate', {state: window.history.state}));
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
