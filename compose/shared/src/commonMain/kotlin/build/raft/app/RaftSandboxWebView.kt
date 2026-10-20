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
    var onUrlChange: (String) -> Unit = {}
    var onNavigationStateChange: (RaftWebViewNavigationState) -> Unit = {}
    var onHostMessage: (String, JSONObject) -> Unit = { _, _ -> }

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
            else -> null
        } ?: return null
    return acceptedKind to payload
}

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
