package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import build.raft.app.compat.json.JSONObject
import build.raft.webcontainer.WebJsDialogRequest

/**
 * Which WebView storage partition a host runs in (#proj-workspace:#397):
 * - [Default]: the platform's default persistent store (Raft's own pages and Raft App entries).
 * - [Ephemeral]: non-persistent storage (iOS `nonPersistentDataStore()`, Desktop incognito;
 *   Android has no per-view ephemeral store).
 * - [IsolatedThirdParty]: a dedicated third-party partition separate from [Default] (Android
 *   `MULTI_PROFILE` profile `"raft_third_party"`, failing closed when unsupported; iOS 17+ named
 *   persistent `WKWebsiteDataStore`, falling back to `nonPersistentDataStore()` on iOS 15–16).
 */
internal enum class RaftWebViewStoragePartition {
    Default,
    Ephemeral,
    IsolatedThirdParty,
}

/** Load-failure reason when [RaftWebViewStoragePartition.IsolatedThirdParty] is unavailable on Android. */
internal const val RAFT_WEBVIEW_ISOLATED_PROFILE_UNAVAILABLE_REASON = "isolated_profile_unavailable"

/**
 * Main-frame navigation context handed synchronously to [RaftWebViewEvents.catchesMainFrameNavigation]
 * before routing, fencing, or `Raft-Host` re-issue (#proj-workspace:#356 §5). Carries the
 * platform-observed fields needed to build [RaftSignInAsk].
 */
internal data class RaftWebViewMainFrameCatchAsk(
    val url: String,
    val starter: RaftSignInStarter,
    /** Committed top-level document URL currently shown, or `""` before any document commits. */
    val page: String,
    /** Whether this WebView has committed any document yet. */
    val committed: Boolean,
    /** Prior URLs in the current navigation/redirect chain before [url], in order. */
    val hops: List<String>,
)

/**
 * Attributes of the sandbox WebView (formerly the Kuikly `RaftSandboxWebView` view attrs).
 * [allowedNavigationSchemes] is already normalized (lower case, no trailing ':').
 */
internal data class RaftWebViewConfig(
    val url: String,
    val allowScripts: Boolean,
    val allowNavigation: Boolean,
    val domStorageEnabled: Boolean,
    val useWideViewPort: Boolean,
    val cacheEnabled: Boolean,
    val ephemeral: Boolean,
    val injectedJavaScript: String,
    val allowedNavigationSchemes: Set<String>,
    val hostEventsEnabled: Boolean,
    /**
     * Origin lock (the Artifact viewer, Tenny's WebView rules): when set (`https://host[:port]`),
     * every top-level document stays on this origin. A top-level navigation anywhere else is
     * blocked and handed to [RaftWebViewEvents.onNavigationBlocked] (the system browser), the
     * injected script runs only in top-level documents of this origin, and runtime scripts reach
     * only such a document. Sub-frames (the content frame) load as the page asks, without any
     * script or bridge of the host's. Null: the surface's own [allowNavigation] rules.
     */
    val mainFrameOrigin: String? = null,
    /**
     * Hostnames (each also covering its subdomains, https only) whose documents this page loads as
     * sub-frames, e.g. the Artifact content domain. Only the desktop host needs it: its engine
     * cannot tell a sub-frame navigation from a top-level one, so anything off the top-level
     * fence that is not listed here (or about:/blob:/data:) is refused before it loads. Android
     * and iOS see the frame and ignore this.
     */
    val frameHosts: Set<String> = emptySet(),
    /**
     * Keeps the native WebView (and its document) when the composable leaves, under this key, so
     * the next host with the same key gets it back at once ([RaftWebViewKeepAlive]). A different
     * key, [RaftWebViewKeepAlive.clear] or the keep-alive time running out destroys it. Null:
     * destroyed with the composable, as before.
     */
    val keepAliveKey: String? = null,
    /**
     * ARGB the native view shows until its first page has painted (the app's canvas colour, so a
     * page shown before its first paint is no white rectangle in dark mode); after that the
     * engine's default white, as in a browser, for pages that paint no background. 0: white from
     * the start.
     */
    val backgroundArgb: Int = 0,
    /**
     * Which product surface this WebView is (`artifact_viewer`, `in_app_browser`, ...), for
     * diagnostics only: renderer-death logs and tickets name it (Android `RaftWebViewRendererGone`).
     */
    val diagnosticsSurface: String = RAFT_WEBVIEW_SURFACE_DEFAULT,
    /**
     * iOS: whether WebKit's form accessory bar (the "↑ ↓ ✓" bar over the keyboard) shows while a
     * field has focus. Off for the app's own pages: on iOS 26 the bar floats over the page without
     * shrinking its visual viewport, so it covered the Artifact comment composer
     * (#proj-mobile:#194). On for the in-app browser, which shows other sites' forms as Safari does.
     * Android and desktop have no such bar.
     */
    val showsFormAccessoryBar: Boolean = false,
    /**
     * Mobile pinch-to-zoom outside Desktop Mode (#proj-workspace:#360, the Artifact viewer):
     * Android enables `supportZoom` and `builtInZoomControls` (with `displayZoomControls = false`,
     * no +/- overlay buttons) even while [useWideViewPort] is off; iOS sets
     * `WKWebViewConfiguration.ignoresViewportScaleLimits = true` so pages remain pinch-zoomable
     * regardless of viewport scale limits. Desktop ignores this. Off by default so unrelated
     * surfaces (Settings, Billing, in-app browser) keep their existing zoom behavior.
     */
    val allowPinchZoom: Boolean = false,
    /**
     * Explicit storage partition (#proj-workspace:#397), or null to derive from [ephemeral]
     * ([RaftWebViewStoragePartition.Ephemeral] when `ephemeral == true`, else
     * [RaftWebViewStoragePartition.Default]). Platform hosts read [effectiveStoragePartition].
     */
    val storagePartition: RaftWebViewStoragePartition? = null,
    /**
     * iOS: whether WKWebView handles left/right edge swipes as page back/forward
     * (`WKWebView.allowsBackForwardNavigationGestures`, #proj-workspace:#397). Off by default so
     * pushed pages keep the app's own edge-back gesture. Android and desktop ignore this.
     */
    val allowsBackForwardNavigationGestures: Boolean = false,
    /**
     * App entry `Raft-Host` header (#proj-workspace:#356): when set, main-frame GET navigations to
     * [RaftHostHeader.origin] carry `Raft-Host: <value>`, and cross-origin GET redirects strip it.
     */
    val raftHost: RaftHostHeader? = null,
    /**
     * Desktop: true while a Compose loading cover (e.g. `RaftArtifactViewerSplashCover`) is drawn
     * over this WebView. Keeps the heavyweight `SwingPanel` clipped to a 1dp corner and the native
     * Wry view parked off-screen so Skiko's `ClipRectangle` never punches a blank hole through the
     * Compose cover (#wg-mobile-sync:#242). Android and iOS ignore this.
     */
    val coverActive: Boolean = false,
    /**
     * Whether visible JavaScript modal dialogs (`alert` / `confirm` / `prompt`) are permitted to
     * present through [RaftWebViewEvents.onJsDialog]. Off by default and flipped off whenever a
     * kept-alive panel is collapsed or hidden so background pages can never hijack the foreground.
     */
    val allowJsDialogs: Boolean = false,
) {
    val effectiveStoragePartition: RaftWebViewStoragePartition
        get() =
            storagePartition ?: if (ephemeral) {
                RaftWebViewStoragePartition.Ephemeral
            } else {
                RaftWebViewStoragePartition.Default
            }
}

internal const val RAFT_WEBVIEW_SURFACE_DEFAULT = "web_view"

/**
 * Events of the sandbox WebView (formerly the Kuikly view events). Platform views read these
 * fields at dispatch time, so the composable can swap lambdas without rebuilding the view.
 */
internal class RaftWebViewEvents {
    var onLoadCommit: () -> Unit = {}
    var onLoadSuccess: () -> Unit = {}
    var onLoadFailure: (String) -> Unit = {}
    var onNavigationBlocked: (String) -> Unit = {}
    /**
     * Synchronous main-frame navigation catch invoked before routing, fencing, and `Raft-Host`
     * re-issue (#proj-workspace:#356 §5, e.g. catching Raft's OAuth authorize endpoint). Returning
     * true cancels the navigation without firing [onNavigationBlocked] or [onLoadFailure].
     */
    var catchesMainFrameNavigation: ((RaftWebViewMainFrameCatchAsk) -> Boolean)? = null
    /**
     * A new top-level document navigation the page asked for that the host routes as a link instead
     * of loading it here (true): it goes to [onNavigationBlocked] and the page stays. Read on
     * Android (`shouldOverrideUrlLoading`) and iOS (`decideNavigationPolicy`, excluding the host's
     * own `loadCurrentSrc` loads). Null: every allowed navigation loads, as before.
     */
    var routesMainFrameNavigation: ((String) -> Boolean)? = null
    /**
     * A file the page saves (`<a download>`, a `blob:` save, an attachment response), kept in the
     * app as a browser keeps it ([RaftWebViewDownload]): Android reads a `blob:` in the page and
     * hands an http(s) one on its own origin over by address; iOS downloads it (WKDownload). Null:
     * the host did not ask, and Android hands the address to [onNavigationBlocked] as before while
     * iOS loads as before.
     */
    var onDownloadRequested: ((RaftWebViewDownload) -> Unit)? = null
    /**
     * Single-shot JavaScript dialog (`alert` / `confirm` / `prompt`) emitted only while
     * [RaftWebViewConfig.allowJsDialogs] is true and the host view is visible.
     */
    var onJsDialog: ((WebJsDialogRequest) -> Unit)? = null
    var onUrlChange: (String) -> Unit = {}
    var onNavigationStateChange: (RaftWebViewNavigationState) -> Unit = {}
    var onHostMessage: (String, JSONObject) -> Unit = { _, _ -> }
    /** Answer to [RaftWebViewController.probeDocumentContent]: [raftWebViewDecodeContentProbe] of [RaftWebViewContentProbeScript]. */
    var onContentProbe: (Boolean?) -> Unit = {}
    /**
     * The web content process (iOS `webViewWebContentProcessDidTerminate`) or renderer (Android
     * `onRenderProcessGone`) died. The platform host still rebuilds and reloads its WebView.
     */
    var onContentProcessGone: (String) -> Unit = {}

    /** Raw `raftHostPort` message from the page: validated and mapped to an accepted kind. */
    fun dispatchHostMessage(raw: String) {
        val (kind, payload) = raftDecodeWebViewHostMessage(raw) ?: return
        onHostMessage(kind, payload)
    }
}

/** The `hostMessage` contract of the Kuikly view event, unchanged. */
internal fun raftDecodeWebViewHostMessage(raw: String): Pair<String, JSONObject>? {
    if (raw.isBlank()) return null
    val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val kind = parsed.optString("kind", "").trim()
    val payload = parsed.optJSONObject("payload") ?: return null
    val acceptedKind =
        when (kind) {
            "ready", "close", "auth-required", "integration-unavailable" -> kind
            // botiverse/slock `packages/web/src/embed/hostBridge.ts`: the user signed out on the page.
            RAFT_HOST_EVENT_SESSION_LOGOUT -> RAFT_HOST_EVENT_SESSION_LOGOUT
            else -> null
        } ?: return null
    return acceptedKind to payload
}

/** Web `emitHostEvent("session:logout")`: an explicit sign-out completed on the page (payload `{}`). */
internal const val RAFT_HOST_EVENT_SESSION_LOGOUT = "session:logout"

/** `window.RaftHost` bridge installed at document start when host events are enabled. */
internal const val RaftWebViewHostPortName = "raftHostPort"
internal const val RaftWebViewMaxRuntimeJavaScriptLength = 64 * 1024

/**
 * Platform sandbox WebView: Android `AndroidView(WebView)` (port of `SlockSandboxWebViewRenderView`),
 * iOS `UIKitView(WKWebView)` (port of `RaftSandboxWebView.m`). Commands from [controller] are the
 * Kuikly `navigationCommand` strings (`goBack`, `reload`, `evaluateJavaScript:<js>`, ...).
 */
@Composable
internal expect fun RaftSandboxWebViewHost(
    config: RaftWebViewConfig,
    events: RaftWebViewEvents,
    controller: RaftWebViewController,
    modifier: Modifier,
)

/**
 * Whether a host must pad the platform WebView by the keyboard and the bottom system bar itself.
 * Android: the window is edge-to-edge, so neither resizes the WebView, and Android WebView gives
 * the page no bottom safe-area inset. iOS: WKWebView handles both. Desktop: neither exists.
 */
internal expect val raftWebViewNeedsHostBottomInsets: Boolean

/**
 * How a host lays a page out at a desktop width (`useWideViewPort`, task #188, the Artifact
 * viewer's Desktop Mode). Android: the page's viewport meta says `width=1280` (injected) and
 * `useWideViewPort` + `setInitialScale` fit it. iOS: the same injected meta, which WKWebView lays
 * out and fits by itself (its `pageZoom` only shrank CSS px, never widened the layout: 1.19.2).
 * Desktop: no such mode.
 */
internal expect val raftWebViewDesktopModeViaViewportMeta: Boolean

/** The placeholder document Android loads first to install the injected script (never a page to restore). */
internal const val RAFT_WEBVIEW_BOOTSTRAP_PATH = "/__raft_mobile_bootstrap__"

/** Query keys that carry a one-time credential (a code, ticket, token, nonce): never replayed by a reload. */
private fun raftWebViewOneTimeQueryKey(key: String): Boolean {
    val k = key.lowercase()
    return k == "state" || k == "sig" || k == "otp" ||
        listOf("token", "ticket", "code", "nonce", "secret", "signature").any { it in k }
}

/**
 * Which address a WebView rebuilt after its renderer (or web content process) was gone loads
 * (#300): the top-level page it was showing ([currentUrl]) when [allowsMainFrame] would let it load
 * there, else the configured [src]. A page reached in place (a kept Artifact viewer booted on the
 * warm route, then routed to an Artifact) must not come back as [src], which the viewer reads as
 * leaving its Artifact. The page loads afresh, so an Artifact page gets a new entry ticket for its
 * content. Never replayed: a `#host=<name>` fragment (a one-time ask to the App, e.g. the viewer's
 * `#host=close`), a one-time query value ([raftWebViewOneTimeQueryKey]), or the bootstrap
 * placeholder ([RAFT_WEBVIEW_BOOTSTRAP_PATH]: [src] then).
 */
internal fun raftWebViewRendererGoneRestoreUrl(
    src: String,
    currentUrl: String,
    allowsMainFrame: (String) -> Boolean,
): String {
    val current = currentUrl.trim()
    val scheme = current.substringBefore(':', "").lowercase()
    if (current.isEmpty() || (scheme != "http" && scheme != "https")) return src
    val fragment = current.substringAfter('#', "")
    val beforeFragment = current.substringBefore('#')
    val path = beforeFragment.substringAfter("://", "").let { rest ->
        val start = rest.indexOfFirst { it == '/' || it == '?' }
        if (start < 0 || rest[start] == '?') "/" else rest.substring(start).substringBefore('?')
    }
    if (path.startsWith(RAFT_WEBVIEW_BOOTSTRAP_PATH)) return src
    val base = beforeFragment.substringBefore('?')
    val query =
        beforeFragment.substringAfter('?', "").split('&')
            .filter { it.isNotEmpty() && !raftWebViewOneTimeQueryKey(it.substringBefore('=')) }
            .joinToString("&")
    val restored =
        base + (if (query.isEmpty()) "" else "?$query") +
            (if (fragment.isEmpty() || fragment.startsWith("host=")) "" else "#$fragment")
    return if (allowsMainFrame(restored)) restored else src
}
