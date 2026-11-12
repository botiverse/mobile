package build.raft.app

import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.artifactCanonicalId
import build.raft.app.data.artifactIdOrNull
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
 * frame ticket from its viewer answer, Porthole's sandbox, the content-domain pin). It is opened
 * with the embed contract ([raftArtifactViewerEmbeddedUrl]), so the page draws no title bar of its own
 * under the native one (Back and the title) but keeps its toolbar and full ⋯ menu, and an address
 * that leaves the Artifact closes the viewer
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

    /**
     * Which session the kept WebView last had ([raftArtifactViewerSessionMark] of its access token,
     * never the token itself): the token it was booted or navigated with, or last synced while shown.
     */
    private var keptSessionMark: Int? = null

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
    fun rememberKeptDocument(
        scope: RaftArtifactViewerScope?,
        target: ArtifactLinkTarget,
        documentUrl: String,
        accessToken: String,
    ): Boolean {
        if (scope == null) return false
        adopt(scope)
        if (!raftArtifactViewerDocumentUrlIsKeepable(scope.webOrigin, documentUrl)) {
            dropKeptDocument()
            return false
        }
        keptDocument = RaftArtifactKeptDocument(target, documentUrl)
        keptSessionMark = raftArtifactViewerSessionMark(accessToken)
        return true
    }

    /** A WebView was prewarmed for [scope] on the viewer's route with no Artifact, signed in with [accessToken]. */
    fun markKeptWarm(scope: RaftArtifactViewerScope?, accessToken: String) {
        if (scope == null) return
        adopt(scope)
        keptDocument = null
        keptSessionMark = raftArtifactViewerSessionMark(accessToken)
    }

    /** The shown viewer's document got [accessToken] (a rotation synced while it is on screen). */
    fun noteKeptSession(scope: RaftArtifactViewerScope?, accessToken: String) {
        if (scope == null || scope != this.scope) return
        keptSessionMark = raftArtifactViewerSessionMark(accessToken)
    }

    /** Whether the kept document shows exactly [target] for [scope] (show it at once): either id form. */
    fun keptDocumentShows(scope: RaftArtifactViewerScope?, target: ArtifactLinkTarget): Boolean =
        scope != null && scope == this.scope && keptDocument?.target?.key == target.key

    /**
     * Whether a kept document of [scope] may be shown again (as it is, or routed to another
     * Artifact): only with the session it was parked with. After a token refresh while it was
     * parked, its web app may have lost its session (requests got 401) and route itself to `/`
     * on reuse (Hands dd109a01), so a new document loads instead.
     */
    fun keptDocumentUsable(scope: RaftArtifactViewerScope?, accessToken: String): Boolean =
        scope != null && scope == this.scope && keptSessionMark != null &&
            keptSessionMark == raftArtifactViewerSessionMark(accessToken)

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
        keptSessionMark = null
        // Always tell the host: a WebView may be kept (prewarmed) with no document recorded yet.
        onKeptDocumentDropped()
    }
}

/** A fingerprint of [accessToken] to tell sessions apart in memory; never logged, never the token. */
internal fun raftArtifactViewerSessionMark(accessToken: String): Int = accessToken.trim().hashCode()

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
 * honoured by the Artifact page since slock #11691): the App draws the title bar (Back, the title),
 * and the page draws no second one but keeps its own toolbar under it (where it lives, its original
 * message, presence, Comments, Find, ⋯ with every action the web has). No `chrome=none` (slock
 * #12362, owner decision 2026-11-10): that drops the toolbar and with it most of the web's actions.
 * The web reads it once, from the document's entry URL, and keeps it for the session.
 *
 * `back=host` asks the page for a Back at its toolbar's left edge that closes the viewer by setting
 * its address's fragment to `#host=close` ([raftArtifactViewerNavigation]), the same address-only
 * contract as `#panel=` (no bridge). Proposed to the web (2026-11-10); a web that does not know it
 * ignores it, and the App's own bar keeps Back meanwhile.
 */
internal val RaftArtifactViewerEmbedQuery: String =
    "embed=$SharedSettingsEmbedContract&shell=$SharedSettingsEmbedHostShell&back=$RAFT_ARTIFACT_VIEWER_HOST_BACK" +
        "&$RAFT_ARTIFACT_VIEWER_HOST_MENU_PARAM=$RAFT_ARTIFACT_VIEWER_HOST_MENU"

/**
 * One bar (artin, "双栏什么时候可以解决"; proposed to the web 2026-11-12): `hostMenu` names the App's
 * own items the page may put in its ⋯ ([RaftArtifactViewerHostAction]; a web that does not know it
 * ignores it). A tap sets the address's fragment to `#host=<name>`, as Back's `#host=close`. Desktop
 * Mode's state is `hostDesktop=on|off` in the query (absent: not offered here), kept there by the App
 * ([raftArtifactViewerHostDesktopScript]). A page that draws the App's items and Back in its toolbar
 * says so with `hostToolbar=1` in its address ([raftArtifactViewerPageDrawsHostBar]): only then does
 * the App leave its own bar out.
 */
internal const val RAFT_ARTIFACT_VIEWER_HOST_MENU_PARAM = "hostMenu"
internal const val RAFT_ARTIFACT_VIEWER_HOST_DESKTOP_PARAM = "hostDesktop"
internal const val RAFT_ARTIFACT_VIEWER_HOST_TOOLBAR_PARAM = "hostToolbar"

/** The `#host=<name>` actions the App offers the page's ⋯. */
internal enum class RaftArtifactViewerHostAction(val wireName: String) {
    DesktopMode("desktop-mode"),
    OpenInBrowser("open-in-browser"),
}

/** Every [RaftArtifactViewerHostAction], comma-separated (a const: [RaftArtifactViewerEmbedQuery] is read before it). */
internal const val RAFT_ARTIFACT_VIEWER_HOST_MENU = "desktop-mode,open-in-browser"

/** The App action [url]'s fragment asks for (`#host=desktop-mode`, `#host=open-in-browser`); null for any other. */
internal fun raftArtifactViewerHostAction(url: String): RaftArtifactViewerHostAction? {
    val fragment = url.substringAfter('#', "")
    if (!fragment.startsWith("host=")) return null
    val name = fragment.removePrefix("host=")
    return RaftArtifactViewerHostAction.entries.firstOrNull { it.wireName == name }
}

/**
 * Whether a `#host=<name>` [url] is a new tap: not the one last handled ([handledUrl]) while its
 * fragment was not yet taken off ([handledCleared] false: a platform's second callback, or Android's
 * reload for Desktop Mode at that address) or within [RAFT_ARTIFACT_VIEWER_HOST_ACTION_REPEAT_MS] of it.
 */
internal fun raftArtifactViewerHostActionIsNew(url: String, handledUrl: String?, handledCleared: Boolean, sinceHandledMs: Long): Boolean =
    url != handledUrl || (handledCleared && sinceHandledMs >= RAFT_ARTIFACT_VIEWER_HOST_ACTION_REPEAT_MS)

internal const val RAFT_ARTIFACT_VIEWER_HOST_ACTION_REPEAT_MS = 500L

private fun raftArtifactViewerQueryParam(url: String, name: String): String? =
    url.substringBefore('#').substringAfter('?', "").split('&')
        .firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', "")

/** Whether the page at [url] draws the only bar: its toolbar with Back and the App's items (`hostToolbar=1`). */
internal fun raftArtifactViewerPageDrawsHostBar(url: String): Boolean =
    raftArtifactViewerQueryParam(url, RAFT_ARTIFACT_VIEWER_HOST_TOOLBAR_PARAM) == "1"

/** `hostDesktop` as the App writes it: `on`/`off`, null (absent) where Desktop Mode is not offered. */
internal fun raftArtifactViewerHostDesktopValue(desktopMode: Boolean?): String? =
    desktopMode?.let { if (it) "on" else "off" }

/** Whether [url] already says [desktopMode] ([raftArtifactViewerHostDesktopValue]). */
internal fun raftArtifactViewerAddressSaysDesktop(url: String, desktopMode: Boolean?): Boolean =
    raftArtifactViewerQueryParam(url, RAFT_ARTIFACT_VIEWER_HOST_DESKTOP_PARAM) == raftArtifactViewerHostDesktopValue(desktopMode)

/** [url] with `hostDesktop` set to [desktopMode] (removed when null); the rest stays as it is. */
internal fun raftArtifactViewerWithHostDesktop(url: String, desktopMode: Boolean?): String {
    val fragment = url.indexOf('#').let { if (it < 0) "" else url.substring(it) }
    val beforeFragment = url.removeSuffix(fragment)
    val path = beforeFragment.substringBefore('?')
    val kept =
        beforeFragment.substringAfter('?', missingDelimiterValue = "")
            .split('&')
            .filter { it.isNotEmpty() && it.substringBefore('=') != RAFT_ARTIFACT_VIEWER_HOST_DESKTOP_PARAM }
    val params = kept + listOfNotNull(raftArtifactViewerHostDesktopValue(desktopMode)?.let { "$RAFT_ARTIFACT_VIEWER_HOST_DESKTOP_PARAM=$it" })
    return if (params.isEmpty()) path + fragment else path + "?" + params.joinToString("&") + fragment
}

/**
 * Puts Desktop Mode's state in the page's address (`hostDesktop`, replaced: no history entry, no
 * load) and tells the page (`raft:host-state`). [clearHostAction]: also takes a handled `#host=<name>`
 * off, so the next tap on the same item is a new address.
 */
internal fun raftArtifactViewerHostDesktopScript(webOrigin: String, desktopMode: Boolean?, clearHostAction: Boolean = false): String {
    val param = Json.encodeToString(String.serializer(), RAFT_ARTIFACT_VIEWER_HOST_DESKTOP_PARAM)
    val value = raftArtifactViewerHostDesktopValue(desktopMode)?.let { Json.encodeToString(String.serializer(), it) } ?: "null"
    return raftArtifactViewerGuardedScript(
        webOrigin,
        """
        var url = new URL(window.location.href);
        var value = $value;
        if (value === null) url.searchParams.delete($param); else url.searchParams.set($param, value);
        if (${clearHostAction} && /^#host=/.test(url.hash) && url.hash !== ${Json.encodeToString(String.serializer(), RAFT_ARTIFACT_VIEWER_CLOSE_FRAGMENT)}) url.hash = '';
        if (url.href !== window.location.href) window.history.replaceState(window.history.state, '', url.href);
        window.dispatchEvent(new Event('raft:host-state'));
        """.trimIndent(),
    )
}

internal const val RAFT_ARTIFACT_VIEWER_HOST_BACK = "host"

/** The fragment the page writes when its host Back is pressed: the viewer closes. */
internal const val RAFT_ARTIFACT_VIEWER_CLOSE_FRAGMENT = "#host=close"

private val RaftArtifactViewerEmbedKeys =
    setOf("embed", "shell", "chrome", "back", RAFT_ARTIFACT_VIEWER_HOST_MENU_PARAM, RAFT_ARTIFACT_VIEWER_HOST_TOOLBAR_PARAM)

/**
 * [url] with the embed contract in its query: any `embed`/`shell` already there is replaced and a `chrome` dropped, every
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

internal sealed interface RaftArtifactViewerBlockedTarget {
    /** Off the web origin, or a web page that is not an Artifact or a message: the system browser. */
    data class SystemBrowser(val url: String) : RaftArtifactViewerBlockedTarget

    /** A file the page saves, which the WebView cannot save. */
    data object Download : RaftArtifactViewerBlockedTarget

    /** A Raft message: opened natively. */
    data class OpenMessage(val url: String) : RaftArtifactViewerBlockedTarget

    /** Another Artifact's page: opened in the in-app viewer. */
    data class OpenArtifact(val target: ArtifactLinkTarget) : RaftArtifactViewerBlockedTarget

    data object Ignore : RaftArtifactViewerBlockedTarget
}

/**
 * Where an address the WebView did not load goes: a top-level navigation the origin lock refused,
 * or a new window the page asked for (`target=_blank`, `window.open`: Open in new page, Ask AI's
 * links). Routed like any link: off the web origin, the system browser (never the signed-in
 * WebView); on it, this Artifact stays as it is, another Artifact opens in the in-app viewer, a
 * message natively, any other page in the browser. A `blob:` address, or one of the web's API
 * (`/api/…`), is a file the page saves (the web's Download / Download all and the Files panel's rows
 * save through a `blob:` link), which the WebView cannot save. Anything else (blank, other schemes)
 * is dropped.
 */
internal fun raftArtifactViewerBlockedNavigationTarget(url: String, webOrigin: String, artifactId: String): RaftArtifactViewerBlockedTarget {
    if (url.trim().startsWith("blob:", ignoreCase = true)) return RaftArtifactViewerBlockedTarget.Download
    val origin = raftWebOriginOf(url) ?: return RaftArtifactViewerBlockedTarget.Ignore
    if (origin != raftWebOriginOf(webOrigin)) return RaftArtifactViewerBlockedTarget.SystemBrowser(url)
    val path = url.substringAfter("://").let { rest -> rest.indexOf('/').let { if (it < 0) "/" else rest.substring(it) } }
    if (path.startsWith("/api/")) return RaftArtifactViewerBlockedTarget.Download
    return when (val navigation = raftArtifactViewerNavigation(url, webOrigin, artifactId)) {
        is RaftArtifactViewerNavigation.Stay -> RaftArtifactViewerBlockedTarget.Ignore
        is RaftArtifactViewerNavigation.OpenMessage -> RaftArtifactViewerBlockedTarget.OpenMessage(navigation.url)
        is RaftArtifactViewerNavigation.OpenArtifact -> RaftArtifactViewerBlockedTarget.OpenArtifact(navigation.target)
        else -> RaftArtifactViewerBlockedTarget.SystemBrowser(url)
    }
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

    /** Another Artifact's page: close the viewer, open that one in the in-app viewer. */
    data class OpenArtifact(val target: ArtifactLinkTarget) : RaftArtifactViewerNavigation

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
    // The page's own Back (`back=host`): it asks the App to close the viewer.
    if (raftArtifactViewerAsksHostClose(url)) return RaftArtifactViewerNavigation.Close
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
    val otherId = artifact.getOrNull(1)?.takeIf { artifact.firstOrNull() == "artifacts" && it != artifactId }?.let(::artifactIdOrNull)
    if (otherId != null && artifactCanonicalId(otherId) != artifactCanonicalId(artifactId)) {
        when (artifact.size) {
            2 -> return RaftArtifactViewerNavigation.OpenArtifact(ArtifactLinkTarget(otherId))
            4 -> if (artifact[2] == "v") artifact[3].toIntOrNull()?.takeIf { it > 0 }?.let { return RaftArtifactViewerNavigation.OpenArtifact(ArtifactLinkTarget(otherId, it)) }
        }
    }
    if (parseRaftMessageLink(url, configuredWebUrl = webOrigin) != null) return RaftArtifactViewerNavigation.OpenMessage(url)
    return RaftArtifactViewerNavigation.Close
}

/** Whether [url] carries the page's own Back (`#host=close`, [RAFT_ARTIFACT_VIEWER_CLOSE_FRAGMENT]). */
internal fun raftArtifactViewerAsksHostClose(url: String): Boolean =
    url.substringAfter('#', "") == RAFT_ARTIFACT_VIEWER_CLOSE_FRAGMENT.removePrefix("#")

/** What the viewer does when its page's address leaves the Artifact. */
internal enum class RaftArtifactViewerLeaveAction {
    /** Close the viewer (and open the message / other Artifact the address is). */
    Leave,

    /** Drop the kept document and load the Artifact's page once in a new WebView. */
    ReloadFresh,
}

/** How soon after a kept document is shown again its own wander off reads as the document's, not the user's. */
internal const val RAFT_ARTIFACT_VIEWER_KEPT_SELF_NAVIGATION_WINDOW_MS = 5_000L

/**
 * A reused kept document that, right after it is shown again, routes itself somewhere that is no
 * Artifact or message ([RaftArtifactViewerNavigation.Close] that is not the page's own Back,
 * `#host=close`) did not do it for the user: its web app was parked for minutes, and may have lost
 * its session meanwhile and gone to the web root (Hands dd109a01: the viewer closed 0.8 s after it
 * opened). The viewer then loads the Artifact once in a fresh WebView ([reloadAvailable]: one per
 * open) instead of closing; a fresh document that leaves again, or the page's own Back, closes it.
 */
internal fun raftArtifactViewerLeaveAction(
    navigation: RaftArtifactViewerNavigation,
    url: String,
    reusedKept: Boolean,
    sinceShownMs: Long,
    reloadAvailable: Boolean,
): RaftArtifactViewerLeaveAction =
    if (
        navigation == RaftArtifactViewerNavigation.Close && reusedKept && reloadAvailable &&
        !raftArtifactViewerAsksHostClose(url) && sinceShownMs in 0..RAFT_ARTIFACT_VIEWER_KEPT_SELF_NAVIGATION_WINDOW_MS
    ) {
        RaftArtifactViewerLeaveAction.ReloadFresh
    } else {
        RaftArtifactViewerLeaveAction.Leave
    }

/**
 * Where an address goes, for the viewer's logs: its path only (no query, no fragment: nothing
 * secret a query may carry), or `#host=close` for the page's own Back.
 */
internal fun raftArtifactViewerLogDestination(url: String): String {
    if (raftArtifactViewerAsksHostClose(url)) return RAFT_ARTIFACT_VIEWER_CLOSE_FRAGMENT
    val rest = url.substringAfter("://", "")
    val start = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val path = if (start < 0) "/" else rest.substring(start).substringBefore('#').substringBefore('?')
    return path.filter { it.isLetterOrDigit() || it in "/-_." }.take(96).ifEmpty { "/" }
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

/**
 * Desktop Mode (artin 11-03, "artifact 预览应该要支持一个桌面模式"): the viewer lays the page out at a
 * desktop width ([RaftFilePreviewDesktopViewportWidth] CSS px, the HTML attachment preview's) and
 * fits it to the screen, zoomable, as a phone browser's "Request desktop site" does. Offered on
 * touch devices narrower than that width; the Compose app (a fine pointer, web `pointer: fine`) and
 * a screen already that wide show the page as a desktop does without it.
 *
 * Only where the WebView widens through the injected viewport meta ([viaViewportMeta]: Android and
 * iOS). iOS's earlier `WKWebView.pageZoom` path left the Artifact page a blank, ~0.3x-wide strip
 * (1.19.2 TestFlight, artin 11-04), which is why 1.19.2 hid it there.
 */
internal fun raftArtifactViewerOffersDesktopMode(
    finePointer: Boolean,
    windowWidthDp: Float,
    viaViewportMeta: Boolean = raftWebViewDesktopModeViaViewportMeta,
): Boolean =
    viaViewportMeta && !finePointer && windowWidthDp > 0f && windowWidthDp < RaftFilePreviewDesktopViewportWidth

/**
 * What the viewer's WebView injects: the session writes ([raftArtifactViewerSessionScript]) and, in
 * Desktop Mode on a host that widens through the page's viewport meta ([viaViewportMeta]: Android,
 * whose `useWideViewPort` honours `width=`, and iOS, whose WKWebView always does), the desktop
 * viewport, guarded like the session (rule 1: a top-level document of the web origin only).
 */
internal fun raftArtifactViewerInjectedScript(
    webOrigin: String,
    sessionScript: String,
    desktopMode: Boolean,
    viaViewportMeta: Boolean = raftWebViewDesktopModeViaViewportMeta,
): String {
    if (!desktopMode || !viaViewportMeta) return sessionScript
    val viewport = raftArtifactViewerGuardedScript(webOrigin, raftFilePreviewDesktopViewportScript())
    return if (sessionScript.isBlank()) viewport else sessionScript + "\n" + viewport
}
