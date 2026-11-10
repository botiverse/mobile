package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import build.raft.app.compat.json.JSONObject

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
)

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
     * A file the page saves, which the WebView cannot (Android `DownloadListener`, an iOS
     * attachment or undisplayable response, an iOS download link). Null: the host did not ask, and
     * Android hands the address to [onNavigationBlocked] as before while iOS loads as before.
     */
    var onDownloadRequested: ((String) -> Unit)? = null
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
            "feedback:ready" -> "ready"
            "feedback:close" -> "close"
            "feedback:auth-required" -> "auth-required"
            "feedback:integration-unavailable" -> "integration-unavailable"
            FeedbackDiagnosticsBridge.START_KIND -> FeedbackDiagnosticsBridge.START_KIND
            FeedbackDiagnosticsBridge.CANCEL_KIND -> FeedbackDiagnosticsBridge.CANCEL_KIND
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
