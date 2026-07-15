package build.raft.app.shared

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.tencent.kuikly.core.render.android.expand.component.KRView
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import java.io.ByteArrayInputStream
import org.json.JSONObject

internal class SlockSandboxWebViewRenderView(context: Context) : KRView(context) {
    private var webView = WebView(context)
    private val releaseNotesDocumentCache = SlockReleaseNotesDocumentCache(context.applicationContext)
    private var destroyed: Boolean = false
    private var src: String = ""
    private var loadedSrc: String = ""
    private var lastSuccessfulUrl: String = ""
    private var allowedUri: Uri? = null
    private var allowScripts: Boolean = true
    private var allowNavigation: Boolean = false
    private var domStorageEnabled: Boolean = false
    private var cacheEnabled: Boolean = false
    private var ephemeral: Boolean = true
    private var injectedJavaScript: String = ""
    private var loadSuccessCallback: KuiklyRenderCallback? = null
    private var loadFailureCallback: KuiklyRenderCallback? = null
    private var navigationBlockedCallback: KuiklyRenderCallback? = null
    private var urlChangeCallback: KuiklyRenderCallback? = null
    private var hostMessageCallback: KuiklyRenderCallback? = null

    // Host↔web event bridge. See `docs/host-web-event-bridge-2026-07-15.md`.
    // Opt-in per WebView; installed only when `hostEventsEnabled` AND both
    // `DOCUMENT_START_SCRIPT` + `WEB_MESSAGE_LISTENER` features are supported by
    // this device's WebView provider (fail-closed on old WebView versions).
    private var hostEventsEnabled: Boolean = false
    private var trustedOrigin: String? = null
    private var bridgeScriptHandle: ScriptHandler? = null
    private var bridgeListenerName: String? = null

    init {
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        configureWebView()
    }

    override fun setProp(propKey: String, propValue: Any): Boolean {
        if (destroyed && !propKey.isEventProp()) {
            Log.w(
                LOG_TAG,
                "post_destroy_prop prop=$propKey action=recreate previousSrc=${src.take(LOG_URL_LIMIT)}"
            )
            recreateWebViewAfterDestroy()
        } else if (destroyed) {
            Log.w(LOG_TAG, "post_destroy_event_prop prop=$propKey action=register_callback")
        }
        return when (propKey) {
            PROP_SRC -> {
                updateSrc(propValue.toString())
                true
            }
            PROP_ALLOW_SCRIPTS -> {
                updateAllowScripts(propValue.toBooleanValue(default = true))
                true
            }
            PROP_ALLOW_NAVIGATION -> {
                updateAllowNavigation(propValue.toBooleanValue(default = false))
                true
            }
            PROP_DOM_STORAGE_ENABLED -> {
                updateDomStorageEnabled(propValue.toBooleanValue(default = false))
                true
            }
            PROP_CACHE_ENABLED -> {
                updateCacheEnabled(propValue.toBooleanValue(default = false))
                true
            }
            PROP_EPHEMERAL -> {
                updateEphemeral(propValue.toBooleanValue(default = true))
                true
            }
            PROP_INJECTED_JAVASCRIPT -> {
                updateInjectedJavaScript(propValue.toString())
                true
            }
            PROP_HOST_EVENTS_ENABLED -> {
                updateHostEventsEnabled(propValue.toBooleanValue(default = false))
                true
            }
            EVENT_HOST_MESSAGE -> {
                hostMessageCallback = propValue as? KuiklyRenderCallback
                true
            }
            EVENT_LOAD_SUCCESS -> {
                loadSuccessCallback = propValue as? KuiklyRenderCallback
                true
            }
            EVENT_LOAD_FAILURE -> {
                loadFailureCallback = propValue as? KuiklyRenderCallback
                true
            }
            EVENT_NAVIGATION_BLOCKED -> {
                navigationBlockedCallback = propValue as? KuiklyRenderCallback
                true
            }
            EVENT_URL_CHANGE -> {
                urlChangeCallback = propValue as? KuiklyRenderCallback
                true
            }
            else -> super.setProp(propKey, propValue)
        }
    }

    override fun resetProp(propKey: String): Boolean {
        if (destroyed) {
            Log.w(LOG_TAG, "post_destroy_reset_prop prop=$propKey action=ignored")
            return true
        }
        return when (propKey) {
            PROP_SRC -> {
                updateSrc("")
                true
            }
            PROP_ALLOW_SCRIPTS -> {
                updateAllowScripts(true)
                true
            }
            PROP_ALLOW_NAVIGATION -> {
                updateAllowNavigation(false)
                true
            }
            PROP_DOM_STORAGE_ENABLED -> {
                updateDomStorageEnabled(false)
                true
            }
            PROP_CACHE_ENABLED -> {
                updateCacheEnabled(false)
                true
            }
            PROP_EPHEMERAL -> {
                updateEphemeral(true)
                true
            }
            PROP_INJECTED_JAVASCRIPT -> {
                updateInjectedJavaScript("")
                true
            }
            PROP_HOST_EVENTS_ENABLED -> {
                updateHostEventsEnabled(false)
                true
            }
            EVENT_HOST_MESSAGE -> {
                hostMessageCallback = null
                true
            }
            EVENT_LOAD_SUCCESS -> {
                loadSuccessCallback = null
                true
            }
            EVENT_LOAD_FAILURE -> {
                loadFailureCallback = null
                true
            }
            EVENT_NAVIGATION_BLOCKED -> {
                navigationBlockedCallback = null
                true
            }
            EVENT_URL_CHANGE -> {
                urlChangeCallback = null
                true
            }
            else -> super.resetProp(propKey)
        }
    }

    override fun setLayoutParams(params: ViewGroup.LayoutParams?) {
        super.setLayoutParams(params)
        if (destroyed) {
            recreateWebViewAfterDestroy()
        }
        webView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    override fun onDestroy() {
        if (destroyed) {
            super.onDestroy()
            return
        }
        destroyed = true
        loadSuccessCallback = null
        loadFailureCallback = null
        navigationBlockedCallback = null
        urlChangeCallback = null
        hostMessageCallback = null
        // Detach the host↔web bridge BEFORE stopping the webview so the message channel
        // and document-start script are gone; anything queued on the port is dropped by
        // the ABA guard (bridgeListenerName == null after detach).
        detachHostBridge()
        Log.i(LOG_TAG, "destroy_webview loadedSrc=${loadedSrc.take(LOG_URL_LIMIT)}")
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private fun recreateWebViewAfterDestroy() {
        Log.i(LOG_TAG, "recreate_after_destroy previousSrc=${src.take(LOG_URL_LIMIT)}")
        destroyed = false
        src = ""
        loadedSrc = ""
        lastSuccessfulUrl = ""
        allowedUri = null
        trustedOrigin = null
        bridgeScriptHandle = null
        bridgeListenerName = null
        webView = WebView(context)
        removeAllViews()
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        configureWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = allowScripts
            domStorageEnabled = this@SlockSandboxWebViewRenderView.domStorageEnabled
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            cacheMode = if (cacheEnabled) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_NO_CACHE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
        }
        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    if (destroyed) return false
                    notifyNavigationBlocked("")
                    return false
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    if (!destroyed && consoleMessage != null) {
                        logConsoleMessage(consoleMessage)
                    }
                    return super.onConsoleMessage(consoleMessage)
                }
            }
        webView.webViewClient =
            object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    if (destroyed) return
                    super.onPageStarted(view, url, favicon)
                    Log.i(LOG_TAG, "page_started url=${url.orEmpty().take(LOG_URL_LIMIT)}")
                    url?.let { notifyUrlChange(it) }
                    if (url != null && !allowNavigation && !isAllowedMainDocumentUrl(url)) {
                        view?.stopLoading()
                        notifyNavigationBlocked(url)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (destroyed) return
                    super.onPageFinished(view, url)
                    Log.i(LOG_TAG, "page_finished url=${url.orEmpty().take(LOG_URL_LIMIT)}")
                    url?.let { notifyUrlChange(it) }
                    if (url != null && (allowNavigation || isAllowedMainDocumentUrl(url))) {
                        evaluateInjectedJavaScript()
                        notifyLoadSuccess(url, "finished")
                    }
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    if (destroyed) return
                    super.onPageCommitVisible(view, url)
                    Log.i(LOG_TAG, "page_commit_visible url=${url.orEmpty().take(LOG_URL_LIMIT)}")
                    url?.let { notifyUrlChange(it) }
                    if (url != null && (allowNavigation || isAllowedMainDocumentUrl(url))) {
                        notifyLoadSuccess(url, "commit_visible")
                    }
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    if (destroyed) true else shouldBlockNavigation(url)

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                    if (destroyed) true else shouldBlockNavigation(request?.url?.toString())

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    if (destroyed) return null
                    val url = request?.url?.toString()
                    if (request?.isForMainFrame == true && !allowNavigation && shouldBlockNavigation(url)) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    if (request?.isForMainFrame == true && request.method.equals("GET", ignoreCase = true)) {
                        releaseNotesMainDocumentResponse(url)?.let { return it }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    if (destroyed) return
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        val reason =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                error?.description?.toString()
                            } else {
                                null
                            } ?: "webview_load_failed"
                        Log.w(LOG_TAG, "page_error reason=$reason url=${request.url?.toString().orEmpty().take(LOG_URL_LIMIT)}")
                        notifyLoadFailure(reason)
                    }
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    if (destroyed) return
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    Log.w(LOG_TAG, "page_error reason=${description.orEmpty()} url=${failingUrl.orEmpty().take(LOG_URL_LIMIT)}")
                    notifyLoadFailure(description ?: "webview_load_failed")
                }
            }
        webView.setDownloadListener(
            DownloadListener { url, _, _, _, _ ->
                if (!destroyed) {
                    notifyNavigationBlocked(url.orEmpty())
                }
            }
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun updateAllowScripts(next: Boolean) {
        if (destroyed) return
        if (allowScripts == next) return
        allowScripts = next
        webView.settings.javaScriptEnabled = next
    }

    private fun updateAllowNavigation(next: Boolean) {
        if (destroyed) return
        allowNavigation = next
    }

    private fun updateDomStorageEnabled(next: Boolean) {
        if (destroyed) return
        if (domStorageEnabled == next) return
        domStorageEnabled = next
        webView.settings.domStorageEnabled = next
    }

    private fun updateCacheEnabled(next: Boolean) {
        if (destroyed) return
        if (cacheEnabled == next) return
        cacheEnabled = next
        webView.settings.cacheMode = if (next) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_NO_CACHE
    }

    private fun updateEphemeral(next: Boolean) {
        if (destroyed) return
        ephemeral = next
    }

    private fun updateInjectedJavaScript(next: String) {
        if (destroyed) return
        injectedJavaScript = next
    }

    private fun updateSrc(next: String) {
        if (destroyed) return
        val normalized = next.trim()
        if (src == normalized) return
        // Src changing invalidates the bridge — a WebView whose initial `src` was
        // `app.raft.build` never gains permission to talk to a different origin
        // (design §"Redirects and navigation"). Tear down before the new load.
        detachHostBridge()
        trustedOrigin = null
        src = normalized
        loadedSrc = ""
        lastSuccessfulUrl = ""
        allowedUri = normalized.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        // Capture the exact origin — the single authority for both `addWebMessageListener`
        // rules and the callback-side re-check. Same origin format both sides emit.
        trustedOrigin = allowedUri?.let(::originOf)
        if (normalized.isBlank()) {
            webView.loadUrl("about:blank")
            return
        }
        // If host events are already enabled and eligibility holds, install now.
        maybeAttachHostBridge()
        loadedSrc = normalized
        if (shouldBootstrapInjectedJavaScript()) {
            Log.i(LOG_TAG, "load_src bootstrap=true url=${normalized.take(LOG_URL_LIMIT)}")
            webView.loadDataWithBaseURL(
                normalized,
                injectedJavaScriptBootstrapHtml(normalized),
                "text/html",
                "UTF-8",
                normalized
            )
        } else {
            Log.i(LOG_TAG, "load_src bootstrap=false url=${normalized.take(LOG_URL_LIMIT)}")
            webView.loadUrl(normalized)
        }
    }

    private fun shouldBlockNavigation(url: String?): Boolean {
        if (allowNavigation) return false
        val target = url ?: return true
        if (isAllowedMainDocumentUrl(target)) return false
        notifyNavigationBlocked(target)
        return true
    }

    private fun isAllowedMainDocumentUrl(url: String): Boolean {
        if (url == loadedSrc) return true
        val allowed = allowedUri ?: return false
        val target = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return target.scheme == allowed.scheme &&
            target.host == allowed.host &&
            target.port == allowed.port &&
            target.path == allowed.path &&
            target.encodedQuery == allowed.encodedQuery
    }

    private fun notifyLoadFailure(reason: String) {
        if (destroyed) return
        loadFailureCallback?.invoke(mapOf("reason" to reason))
    }

    private fun notifyLoadSuccess(url: String, phase: String) {
        if (destroyed) return
        if (lastSuccessfulUrl == url) return
        lastSuccessfulUrl = url
        Log.i(LOG_TAG, "page_success phase=$phase url=${url.take(LOG_URL_LIMIT)}")
        loadSuccessCallback?.invoke(emptyMap<String, Any>())
    }

    private fun notifyNavigationBlocked(url: String) {
        if (destroyed) return
        navigationBlockedCallback?.invoke(mapOf("url" to url))
    }

    private fun notifyUrlChange(url: String) {
        if (destroyed) return
        urlChangeCallback?.invoke(mapOf("url" to url))
    }

    private fun evaluateInjectedJavaScript() {
        if (destroyed) return
        val script = injectedJavaScript.takeIf { allowScripts && it.isNotBlank() } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript(script, null)
        } else {
            @Suppress("DEPRECATION")
            webView.loadUrl("javascript:$script")
        }
    }

    private fun updateHostEventsEnabled(next: Boolean) {
        if (destroyed) return
        if (hostEventsEnabled == next) return
        hostEventsEnabled = next
        if (next) {
            maybeAttachHostBridge()
        } else {
            detachHostBridge()
        }
    }

    /**
     * Install the bridge if and only if every precondition holds:
     *  - `hostEventsEnabled` was set true by the caller;
     *  - `trustedOrigin` was captured from a non-blank, parseable `src`;
     *  - the device's WebView provider supports BOTH `DOCUMENT_START_SCRIPT` and
     *    `WEB_MESSAGE_LISTENER` (either false ⇒ fail-closed no-op — see
     *    §"Support / fallback" in the design).
     *
     * If already installed, do nothing (idempotent).
     */
    private fun maybeAttachHostBridge() {
        if (destroyed) return
        if (!hostEventsEnabled) return
        if (bridgeListenerName != null) return  // already attached
        val origin = trustedOrigin ?: return
        if (!isBridgeFeatureSupported()) {
            Log.w(LOG_TAG, "host_bridge_unsupported skipping install; hostEventsEnabled becomes no-op")
            return
        }
        val allowed = setOf(origin)
        val listener = object : WebViewCompat.WebMessageListener {
            override fun onPostMessage(
                view: WebView,
                message: WebMessageCompat,
                sourceOrigin: Uri,
                isMainFrame: Boolean,
                replyProxy: JavaScriptReplyProxy,
            ) {
                // Callback-side re-check on TOP of `addWebMessageListener`'s own
                // origin filter. Belt-and-braces per @MingQi review.
                if (!isMainFrame) return
                if (originOf(sourceOrigin) != origin) return
                val raw = message.data ?: return
                val bytes = raw.toByteArray(Charsets.UTF_8).size
                if (bytes > MAX_MESSAGE_UTF8_BYTES) {
                    Log.w(LOG_TAG, "host_message_oversized bytes=$bytes cap=$MAX_MESSAGE_UTF8_BYTES dropped")
                    return
                }
                notifyHostMessage(raw)
            }
        }
        try {
            WebViewCompat.addWebMessageListener(webView, BRIDGE_LISTENER_NAME, allowed, listener)
            bridgeScriptHandle = WebViewCompat.addDocumentStartJavaScript(webView, bridgeScript(), allowed)
            bridgeListenerName = BRIDGE_LISTENER_NAME
            Log.i(LOG_TAG, "host_bridge_attached origin=$origin")
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "host_bridge_attach_failed reason=${t.message}")
            // Roll back to a clean state so a later `maybeAttachHostBridge()` can retry
            // without a half-installed listener/script pair.
            try { bridgeScriptHandle?.remove() } catch (_: Throwable) { /* best effort */ }
            bridgeScriptHandle = null
            try { WebViewCompat.removeWebMessageListener(webView, BRIDGE_LISTENER_NAME) } catch (_: Throwable) { /* best effort */ }
            bridgeListenerName = null
        }
    }

    private fun detachHostBridge() {
        val name = bridgeListenerName ?: run {
            bridgeScriptHandle = null
            return
        }
        try { bridgeScriptHandle?.remove() } catch (_: Throwable) { /* best effort */ }
        bridgeScriptHandle = null
        try { WebViewCompat.removeWebMessageListener(webView, name) } catch (_: Throwable) { /* best effort */ }
        bridgeListenerName = null
    }

    private fun isBridgeFeatureSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    private fun originOf(uri: Uri): String? {
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port = uri.port
        return if (port < 0) "$scheme://$host" else "$scheme://$host:$port"
    }

    private fun bridgeScript(): String {
        // The bridge script that runs at document-start on the target document. Defines
        // `window.RaftHost` as a frozen object; the auth-seed script (which runs later
        // via `evaluateInjectedJavaScript`) MUST NOT overwrite this — the
        // `defineProperty(..., writable=false, configurable=false)` + `Object.freeze`
        // combination throws in strict mode / silently no-ops in sloppy mode if a later
        // script tries. `RAFT_HOST_VERSION` is imported from the KMP shared constant
        // and pinned to `raft-host-v1`; the web helper checks it before calling `emit`.
        return """
            (function () {
              'use strict';
              var post = function (m) {
                try { window.$BRIDGE_LISTENER_NAME.postMessage(m); } catch (_) {}
              };
              Object.defineProperty(window, 'RaftHost', {
                value: Object.freeze({
                  version: '$RAFT_HOST_VERSION',
                  emit: function (kind, payload) {
                    if (typeof kind !== 'string') return;
                    try { post(JSON.stringify({ kind: kind, payload: payload || {} })); } catch (_) {}
                  }
                }),
                writable: false, configurable: false
              });
            })();
        """.trimIndent()
    }

    private fun notifyHostMessage(raw: String) {
        if (destroyed) return
        hostMessageCallback?.invoke(mapOf("message" to raw))
    }

    private fun logConsoleMessage(consoleMessage: ConsoleMessage) {
        val source = consoleMessage.sourceId().orEmpty().take(LOG_URL_LIMIT)
        val message = consoleMessage.message().orEmpty().take(LOG_MESSAGE_LIMIT)
        val line = consoleMessage.lineNumber()
        when (consoleMessage.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR ->
                Log.e(LOG_TAG, "console_error line=$line source=$source message=$message")
            ConsoleMessage.MessageLevel.WARNING ->
                Log.w(LOG_TAG, "console_warning line=$line source=$source message=$message")
            else ->
                Log.i(
                    LOG_TAG,
                    "console_${consoleMessage.messageLevel().name.lowercase()} line=$line source=$source message=$message"
                )
        }
    }

    private fun shouldBootstrapInjectedJavaScript(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
            allowScripts &&
            domStorageEnabled &&
            injectedJavaScript.isNotBlank()

    private fun injectedJavaScriptBootstrapHtml(targetUrl: String): String {
        val safeScript = injectedJavaScript.replace("</script", "<\\/script", ignoreCase = true)
        val safeTargetUrl = JSONObject.quote(targetUrl)
        return """
            <!doctype html>
            <html>
              <head>
                <meta name="viewport" content="width=device-width, initial-scale=1" />
                <script>
                  (function() {
                    try {
                      $safeScript
                    } catch (error) {
                      console.error("slock_webview_bootstrap_injected_js_error", error && (error.stack || error.message || error));
                    }
                    window.location.replace($safeTargetUrl);
                  })();
                </script>
              </head>
              <body></body>
            </html>
        """.trimIndent()
    }

    private fun releaseNotesMainDocumentResponse(url: String?): WebResourceResponse? {
        val target = url?.takeIf(SlockReleaseNotesDocumentCache::isReleaseNotesUrl) ?: return null
        val startedAt = System.currentTimeMillis()
        val document = releaseNotesDocumentCache.loadForWebView(target) ?: return null
        Log.i(
            LOG_TAG,
            "release_notes_main_document source=${document.source} " +
                "bytes=${document.bytes.size} durationMs=${System.currentTimeMillis() - startedAt} " +
                "url=${target.take(LOG_URL_LIMIT)}"
        )
        return WebResourceResponse(
            document.mimeType,
            "utf-8",
            ByteArrayInputStream(document.bytes)
        )
    }

    private fun Any.toBooleanValue(default: Boolean): Boolean =
        when (this) {
            is Boolean -> this
            is String -> this.equals("true", ignoreCase = true)
            is Number -> this.toInt() != 0
            else -> default
        }

    companion object {
        const val VIEW_NAME = "SlockSandboxWebView"
        private const val LOG_TAG = "SlockWebView"
        private const val LOG_URL_LIMIT = 160
        private const val LOG_MESSAGE_LIMIT = 500
        private const val PROP_SRC = "src"
        private const val PROP_ALLOW_SCRIPTS = "allowScripts"
        private const val PROP_ALLOW_NAVIGATION = "allowNavigation"
        private const val PROP_DOM_STORAGE_ENABLED = "domStorageEnabled"
        private const val PROP_CACHE_ENABLED = "cacheEnabled"
        private const val PROP_EPHEMERAL = "ephemeral"
        private const val PROP_INJECTED_JAVASCRIPT = "injectedJavaScript"
        private const val PROP_HOST_EVENTS_ENABLED = "hostEventsEnabled"
        private const val EVENT_LOAD_SUCCESS = "loadSuccess"
        private const val EVENT_LOAD_FAILURE = "loadFailure"
        private const val EVENT_NAVIGATION_BLOCKED = "navigationBlocked"
        private const val EVENT_URL_CHANGE = "urlChange"
        private const val EVENT_HOST_MESSAGE = "hostMessage"

        // Bridge protocol (design r3+delta v2). Version is duplicated with
        // `SlockSandboxWebViewEvent.RAFT_HOST_VERSION` in KMP shared; a static
        // guard in `SlockSandboxWebViewRenderViewBridgeContractTest` (see
        // commonTest side) fails if the two ever drift.
        private const val RAFT_HOST_VERSION = "raft-host-v1"
        // Name of the JS-side proxy injected by `addWebMessageListener`. The
        // bridge script binds `window.RaftHost.emit` to `raftHostPort.postMessage`.
        private const val BRIDGE_LISTENER_NAME = "raftHostPort"
        // §Safety limits: 4 KB UTF-8 byte cap on the raw wire string.
        private const val MAX_MESSAGE_UTF8_BYTES = 4096

        private fun String.isEventProp(): Boolean =
            this == EVENT_LOAD_SUCCESS ||
                this == EVENT_LOAD_FAILURE ||
                this == EVENT_NAVIGATION_BLOCKED ||
                this == EVENT_URL_CHANGE ||
                this == EVENT_HOST_MESSAGE
    }
}
