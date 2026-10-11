package build.raft.app.shared

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.DownloadListener
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.tencent.kuikly.core.render.android.expand.component.KRView
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import java.io.ByteArrayInputStream
import java.net.URI
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
    private var pendingRuntimeJavaScript: String = ""
    private var allowedNavigationSchemes: Set<String> = emptySet()
    private var hostEventsEnabled: Boolean = false
    private var currentUrl: String = ""
    private var currentTitle: String = ""
    private var currentProgress: Int = 0
    private var currentIsLoading: Boolean = false
    private var lastNavigationStateKey: String = ""
    private var lastLoadFailureReason: String = ""
    private var loadSuccessCallback: KuiklyRenderCallback? = null
    private var loadFailureCallback: KuiklyRenderCallback? = null
    private var navigationBlockedCallback: KuiklyRenderCallback? = null
    private var urlChangeCallback: KuiklyRenderCallback? = null
    private var navigationStateChangeCallback: KuiklyRenderCallback? = null
    private var hostMessageCallback: KuiklyRenderCallback? = null
    private var hostMessageListener: WebViewCompat.WebMessageListener? = null
    private var documentStartScriptHandler: ScriptHandler? = null
    private val bootstrapNavigationFence = WebViewBootstrapNavigationFence()

    init {
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        configureWebView()
    }

    override fun setProp(propKey: String, propValue: Any): Boolean {
        if (destroyed && !propKey.isEventProp()) {
            Log.w(
                LOG_TAG,
                "post_destroy_prop prop=$propKey action=recreate previousSrc=${src.redactedUrlForLog()}"
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
            PROP_ALLOWED_NAVIGATION_SCHEMES -> {
                updateAllowedNavigationSchemes(propValue.toString())
                true
            }
            PROP_HOST_EVENTS_ENABLED -> {
                updateHostEventsEnabled(propValue.toBooleanValue(default = false))
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
            EVENT_NAVIGATION_STATE_CHANGE -> {
                navigationStateChangeCallback = propValue as? KuiklyRenderCallback
                notifyNavigationState(force = true)
                true
            }
            EVENT_HOST_MESSAGE -> {
                hostMessageCallback = propValue as? KuiklyRenderCallback
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
            PROP_ALLOWED_NAVIGATION_SCHEMES -> {
                updateAllowedNavigationSchemes("")
                true
            }
            PROP_HOST_EVENTS_ENABLED -> {
                updateHostEventsEnabled(false)
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
            EVENT_NAVIGATION_STATE_CHANGE -> {
                navigationStateChangeCallback = null
                true
            }
            EVENT_HOST_MESSAGE -> {
                hostMessageCallback = null
                true
            }
            else -> super.resetProp(propKey)
        }
    }

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? =
        when (method) {
            METHOD_NAVIGATION_COMMAND -> {
                executeNavigationCommand(params.orEmpty())
                null
            }
            else -> super.call(method, params, callback)
        }

    override fun setLayoutParams(params: ViewGroup.LayoutParams?) {
        super.setLayoutParams(params)
        if (destroyed) {
            recreateWebViewAfterDestroy(restoreCurrentDocument = true)
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
        navigationStateChangeCallback = null
        hostMessageCallback = null
        removeHostMessageListener()
        pendingRuntimeJavaScript = ""
        removeDocumentStartScript()
        Log.i(LOG_TAG, "destroy_webview loadedSrc=${loadedSrc.redactedUrlForLog()}")
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private fun recreateWebViewAfterDestroy(restoreCurrentDocument: Boolean = false) {
        Log.i(LOG_TAG, "recreate_after_destroy previousSrc=${src.redactedUrlForLog()}")
        destroyed = false
        loadedSrc = ""
        lastSuccessfulUrl = ""
        allowedUri = src.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        currentUrl = src
        currentTitle = ""
        currentProgress = 0
        currentIsLoading = false
        lastNavigationStateKey = ""
        lastLoadFailureReason = ""
        pendingRuntimeJavaScript = ""
        webView = WebView(context)
        removeAllViews()
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        configureWebView()
        if (restoreCurrentDocument) {
            restoreCurrentDocumentAfterRecreation()
        }
    }

    private fun restoreCurrentDocumentAfterRecreation() {
        if (src.isBlank()) {
            webView.loadUrl(ABOUT_BLANK_URL)
            notifyNavigationState(force = true)
            return
        }
        if (!isNavigationSchemeAllowed(src)) {
            notifyNavigationBlocked(src)
            notifyNavigationState(force = true)
            return
        }
        loadAllowedSrc(src)
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
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (destroyed) return
                    super.onProgressChanged(view, newProgress)
                    currentProgress = newProgress.coerceIn(0, 100)
                    currentUrl = view?.url.orEmpty().ifBlank { currentUrl }
                    notifyNavigationState()
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (destroyed) return
                    super.onReceivedTitle(view, title)
                    currentTitle = title.orEmpty()
                    currentUrl = view?.url.orEmpty().ifBlank { currentUrl }
                    notifyNavigationState()
                }

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
                    if (isRestrictedGenericMode()) {
                        return true
                    }
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
                    val target = url.orEmpty()
                    Log.i(LOG_TAG, "page_started url=${target.redactedUrlForLog()}")
                    bootstrapNavigationFence.onMainFrameStarted(target)
                    val sourceOrigin = documentStartScriptOriginRule(src)
                    val targetOrigin = documentStartScriptOriginRule(target)
                    if (sourceOrigin == null || targetOrigin != sourceOrigin) {
                        pendingRuntimeJavaScript = ""
                    }
                    if (wouldBlockMainFrameNavigation(url)) {
                        view?.stopLoading()
                        handleBlockedMainFrameNavigation(url)
                        return
                    }
                    currentUrl = target
                    currentTitle = ""
                    currentProgress = 0
                    currentIsLoading = true
                    lastSuccessfulUrl = ""
                    lastLoadFailureReason = ""
                    notifyUrlChange(target)
                    notifyNavigationState()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (destroyed) return
                    super.onPageFinished(view, url)
                    val target = url.orEmpty()
                    Log.i(LOG_TAG, "page_finished url=${target.redactedUrlForLog()}")
                    if (url != null && !shouldBlockMainFrameNavigation(url)) {
                        currentUrl = target
                        currentTitle = view?.title.orEmpty().ifBlank { currentTitle }
                        currentProgress = 100
                        currentIsLoading = false
                        evaluateInjectedJavaScript()
                        if (bootstrapNavigationFence.mayUncover(url)) {
                            notifyLoadSuccess(url, "finished", beforeCallback = ::flushPendingRuntimeJavaScript)
                        }
                        notifyUrlChange(target)
                        notifyNavigationState()
                    }
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    if (destroyed) return
                    super.onPageCommitVisible(view, url)
                    val target = url.orEmpty()
                    Log.i(LOG_TAG, "page_commit_visible url=${target.redactedUrlForLog()}")
                    if (url != null && !shouldBlockMainFrameNavigation(url)) {
                        currentUrl = target
                        currentTitle = view?.title.orEmpty().ifBlank { currentTitle }
                        notifyUrlChange(target)
                        notifyNavigationState()
                        if (bootstrapNavigationFence.mayUncover(url)) notifyLoadSuccess(url, "commit_visible")
                    }
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    if (destroyed) true
                    else if (url?.startsWith("raft-onboarding-wake:") == true) shouldBlockMainFrameNavigation(url)
                    else shouldSilentlyBlockSubframeNavigation(url)

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    if (destroyed) return true
                    val target = request?.url?.toString()
                    return if (request?.isForMainFrame == true) {
                        shouldBlockMainFrameNavigation(target)
                    } else {
                        shouldSilentlyBlockSubframeNavigation(target)
                    }
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    if (destroyed) return
                    super.doUpdateVisitedHistory(view, url, isReload)
                    val target = url.orEmpty()
                    // History API / fragment updates are same-document state changes, not a new
                    // main-frame navigation. Keep the visible URL/history state in sync even when
                    // allowNavigation is false; scheme gating still guards malformed callbacks.
                    if (target.isBlank() || !isNavigationSchemeAllowed(target)) return
                    currentUrl = target
                    currentTitle = view?.title.orEmpty().ifBlank { currentTitle }
                    notifyUrlChange(target)
                    notifyNavigationState()
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    if (destroyed) return null
                    val url = request?.url?.toString()
                    if (request?.isForMainFrame == true && wouldBlockMainFrameNavigation(url)) {
                        val blockedUrl = url.orEmpty()
                        view?.post { handleBlockedMainFrameNavigation(blockedUrl) }
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
                        currentUrl = request.url?.toString().orEmpty().ifBlank { currentUrl }
                        currentIsLoading = false
                        val errorCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.errorCode ?: 0 else 0
                        Log.w(LOG_TAG, "page_error code=$errorCode url=${currentUrl.redactedUrlForLog()}")
                        notifyNavigationState()
                        notifyLoadFailure(reason)
                    }
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    if (destroyed) return
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    currentUrl = failingUrl.orEmpty().ifBlank { currentUrl }
                    currentIsLoading = false
                    Log.w(
                        LOG_TAG,
                        "page_error code=$errorCode url=${currentUrl.redactedUrlForLog()}"
                    )
                    notifyNavigationState()
                    notifyLoadFailure(description ?: "webview_load_failed")
                }

                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    if (destroyed) {
                        handler?.cancel()
                        return
                    }
                    handler?.cancel()
                    currentUrl = error?.url.orEmpty().ifBlank { currentUrl }
                    currentIsLoading = false
                    val reason = "ssl_error_${error?.primaryError ?: -1}"
                    Log.w(LOG_TAG, "page_error reason=$reason url=${currentUrl.redactedUrlForLog()}")
                    notifyNavigationState()
                    notifyLoadFailure(reason)
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
        if (!next) pendingRuntimeJavaScript = ""
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
        if (injectedJavaScript == next) return
        injectedJavaScript = next
        removeDocumentStartScript()
        if (src.isNotBlank()) installDocumentStartScript(src)
    }

    private fun updateHostEventsEnabled(next: Boolean) {
        if (destroyed || hostEventsEnabled == next) return
        hostEventsEnabled = next
        if (src.isNotBlank()) {
            pendingRuntimeJavaScript = ""
            loadAllowedSrc(src)
        }
    }

    private fun updateAllowedNavigationSchemes(next: String) {
        if (destroyed) return
        val normalized =
            next.split(',')
                .asSequence()
                .map { value -> value.trim().removeSuffix(":").lowercase() }
                .filter { value -> value.isNotEmpty() }
                .toSet()
        if (allowedNavigationSchemes == normalized) return
        allowedNavigationSchemes = normalized
        val activeUrl = currentUrl.ifBlank { src }
        if (activeUrl.isNotBlank() && !isNavigationSchemeAllowed(activeUrl)) {
            webView.stopLoading()
            currentIsLoading = false
            notifyNavigationState()
            notifyNavigationBlocked(activeUrl)
        }
    }

    private fun executeNavigationCommand(next: String) {
        if (destroyed) return
        if (next.startsWith(COMMAND_EVALUATE_JAVA_SCRIPT_PREFIX)) {
            evaluateRuntimeJavaScript(next.removePrefix(COMMAND_EVALUATE_JAVA_SCRIPT_PREFIX))
            return
        }
        when (next.trim()) {
            COMMAND_GO_BACK -> if (webView.canGoBack()) webView.goBack()
            COMMAND_GO_FORWARD -> if (webView.canGoForward()) webView.goForward()
            COMMAND_RELOAD -> {
                lastSuccessfulUrl = ""
                lastLoadFailureReason = ""
                currentProgress = 0
                currentIsLoading = true
                webView.reload()
            }
            COMMAND_STOP -> {
                webView.stopLoading()
                currentIsLoading = false
            }
            else -> return
        }
        notifyNavigationState(force = true)
    }

    private fun evaluateRuntimeJavaScript(script: String, allowQueue: Boolean = true) {
        if (destroyed || !allowScripts || script.isBlank()) return
        if (script.length > MAX_RUNTIME_JAVA_SCRIPT_LENGTH) {
            Log.w(LOG_TAG, "runtime_js result=dropped reason=oversized length=${script.length}")
            return
        }
        val activeUrl = currentUrl.ifBlank { lastSuccessfulUrl }
        val sourceOrigin = documentStartScriptOriginRule(src)
        val activeOrigin = documentStartScriptOriginRule(activeUrl)
        if (sourceOrigin == null || activeOrigin != sourceOrigin) {
            Log.i(
                LOG_TAG,
                "runtime_js result=dropped reason=document_not_ready sameOrigin=${sourceOrigin != null && activeOrigin == sourceOrigin}"
            )
            return
        }
        if (currentIsLoading || lastSuccessfulUrl.isBlank()) {
            if (allowQueue) {
                pendingRuntimeJavaScript = script
                Log.i(LOG_TAG, "runtime_js result=queued reason=document_loading")
            } else {
                Log.i(LOG_TAG, "runtime_js result=dropped reason=document_not_ready sameOrigin=true")
            }
            return
        }
        applyRuntimeJavaScript(script)
    }

    private fun flushPendingRuntimeJavaScript() {
        val script = pendingRuntimeJavaScript.takeIf { it.isNotBlank() } ?: return
        pendingRuntimeJavaScript = ""
        evaluateRuntimeJavaScript(script, allowQueue = false)
    }

    private fun applyRuntimeJavaScript(script: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript(script, null)
        } else {
            @Suppress("DEPRECATION")
            webView.loadUrl("javascript:$script")
        }
        Log.i(LOG_TAG, "runtime_js result=applied")
    }

    private fun updateSrc(next: String) {
        if (destroyed) return
        val normalized = next.trim()
        if (src == normalized && (normalized.isBlank() || loadedSrc == normalized)) return
        pendingRuntimeJavaScript = ""
        src = normalized
        loadedSrc = ""
        lastSuccessfulUrl = ""
        lastLoadFailureReason = ""
        allowedUri = normalized.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (normalized.isBlank()) {
            currentUrl = ""
            currentTitle = ""
            currentProgress = 0
            currentIsLoading = false
            notifyNavigationState()
            webView.loadUrl("about:blank")
            return
        }
        currentUrl = normalized
        currentTitle = ""
        currentProgress = 0
        currentIsLoading = false
        if (!isNavigationSchemeAllowed(normalized)) {
            notifyNavigationState()
            notifyNavigationBlocked(normalized)
            return
        }
        loadAllowedSrc(normalized)
    }

    private fun loadAllowedSrc(normalized: String) {
        loadedSrc = normalized
        currentUrl = normalized
        currentIsLoading = true
        notifyNavigationState()
        val documentStartInstalled = installDocumentStartScript(normalized)
        installHostMessageListener(normalized)
        if (shouldBootstrapInjectedJavaScript(documentStartInstalled)) {
            val bootstrapDocumentUrl = injectedJavaScriptBootstrapBaseUrl(normalized) ?: normalized
            bootstrapNavigationFence.begin(targetUrl = normalized)
            Log.i(LOG_TAG, "load_src bootstrap=true url=${normalized.redactedUrlForLog()}")
            webView.loadDataWithBaseURL(
                bootstrapDocumentUrl,
                injectedJavaScriptBootstrapHtml(normalized),
                "text/html",
                "UTF-8",
                bootstrapDocumentUrl
            )
        } else {
            bootstrapNavigationFence.reset()
            Log.i(LOG_TAG, "load_src bootstrap=false url=${normalized.redactedUrlForLog()}")
            webView.loadUrl(normalized)
        }
    }

    private fun shouldBlockMainFrameNavigation(url: String?): Boolean {
        if (!wouldBlockMainFrameNavigation(url)) return false
        handleBlockedMainFrameNavigation(url)
        return true
    }

    private fun handleBlockedMainFrameNavigation(url: String?) {
        if (destroyed) return
        currentIsLoading = false
        notifyNavigationState()
        url?.let(::notifyNavigationBlocked)
    }

    private fun shouldSilentlyBlockSubframeNavigation(url: String?): Boolean {
        val target = url ?: return true
        return !isNavigationSchemeAllowed(target)
    }

    private fun wouldBlockMainFrameNavigation(url: String?): Boolean {
        val target = url ?: return true
        if (src.isBlank() && target == ABOUT_BLANK_URL) return false
        if (!isNavigationSchemeAllowed(target)) return true
        if (allowNavigation) return false
        return !isAllowedMainDocumentUrl(target)
    }

    private fun isNavigationSchemeAllowed(url: String): Boolean {
        if (allowedNavigationSchemes.isEmpty()) return true
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
        return scheme != null && scheme in allowedNavigationSchemes
    }

    private fun isAllowedMainDocumentUrl(url: String): Boolean {
        if (src.isBlank() && url == ABOUT_BLANK_URL) return true
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
        val failureKey = "$currentUrl|$reason"
        if (failureKey == lastLoadFailureReason) return
        lastLoadFailureReason = failureKey
        loadFailureCallback?.invoke(mapOf("reason" to reason))
    }

    private fun notifyLoadSuccess(
        url: String,
        phase: String,
        beforeCallback: (() -> Unit)? = null,
    ) {
        if (destroyed) return
        if (lastSuccessfulUrl == url) {
            beforeCallback?.invoke()
            return
        }
        lastSuccessfulUrl = url
        lastLoadFailureReason = ""
        beforeCallback?.invoke()
        Log.i(LOG_TAG, "page_success phase=$phase url=${url.redactedUrlForLog()}")
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

    private fun notifyNavigationState(force: Boolean = false) {
        if (destroyed) return
        val state =
            mapOf(
                "url" to currentUrl,
                "title" to currentTitle,
                "canGoBack" to webView.canGoBack(),
                "canGoForward" to webView.canGoForward(),
                "isLoading" to currentIsLoading,
                "progress" to currentProgress.coerceIn(0, 100)
            )
        val stateKey = state.entries.joinToString(separator = "\u0000") { entry -> "${entry.key}=${entry.value}" }
        if (!force && stateKey == lastNavigationStateKey) return
        lastNavigationStateKey = stateKey
        navigationStateChangeCallback?.invoke(state)
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

    private fun logConsoleMessage(consoleMessage: ConsoleMessage) {
        val source = consoleMessage.sourceId().orEmpty().redactedUrlForLog()
        val message = consoleMessage.message().orEmpty().sanitizedLogValue(LOG_MESSAGE_LIMIT)
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

    private fun shouldBootstrapInjectedJavaScript(documentStartInstalled: Boolean): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
            allowScripts &&
            domStorageEnabled &&
            injectedJavaScript.isNotBlank() &&
            !documentStartInstalled

    private fun installHostMessageListener(targetUrl: String) {
        removeHostMessageListener()
        if (!hostEventsEnabled || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val originRule = documentStartScriptOriginRule(targetUrl) ?: return
        runCatching {
            val listener = object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(view: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
                    if (!isMainFrame || sourceOrigin.toString() != originRule) return
                    val raw = message.data ?: return
                    hostMessageCallback?.invoke(mapOf("message" to raw))
                }
            }
            hostMessageListener = listener
            WebViewCompat.addWebMessageListener(webView, "raftHostPort", setOf(originRule), listener)
            Log.i(LOG_TAG, "host_message_listener installed origin=${originRule.redactedUrlForLog()}")
        }.onFailure { Log.w(LOG_TAG, "host_message_listener unavailable", it) }
    }

    private fun removeHostMessageListener() {
        runCatching { WebViewCompat.removeWebMessageListener(webView, "raftHostPort") }
        hostMessageListener = null
    }

    private fun installDocumentStartScript(targetUrl: String): Boolean {
        removeDocumentStartScript()
        val authScript = injectedJavaScript.takeIf { allowScripts && domStorageEnabled && it.isNotBlank() } ?: ""
        val script = (hostBridgeScript.takeIf { hostEventsEnabled && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) } .orEmpty() + "\n" + authScript).trim().takeIf { it.isNotBlank() } ?: return false
        val originRule = documentStartScriptOriginRule(targetUrl) ?: return false
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        return runCatching {
            documentStartScriptHandler =
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    script,
                    setOf(originRule)
                )
            Log.i(LOG_TAG, "document_start_script installed origin=${originRule.redactedUrlForLog()}")
            true
        }.getOrElse { error ->
            Log.w(LOG_TAG, "document_start_script unavailable fallback=bootstrap", error)
            false
        }
    }

    private fun removeDocumentStartScript() {
        runCatching { documentStartScriptHandler?.remove() }
        documentStartScriptHandler = null
    }

    private fun isRestrictedGenericMode(): Boolean =
        allowedNavigationSchemes.isNotEmpty() && injectedJavaScript.isBlank()

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
                "url=${target.redactedUrlForLog()}"
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
        private const val LOG_MESSAGE_LIMIT = 500
        private const val ABOUT_BLANK_URL = "about:blank"
        private const val PROP_SRC = "src"
        private const val PROP_ALLOW_SCRIPTS = "allowScripts"
        private const val PROP_ALLOW_NAVIGATION = "allowNavigation"
        private const val PROP_DOM_STORAGE_ENABLED = "domStorageEnabled"
        private const val PROP_CACHE_ENABLED = "cacheEnabled"
        private const val PROP_EPHEMERAL = "ephemeral"
        private const val PROP_INJECTED_JAVASCRIPT = "injectedJavaScript"
        private const val PROP_ALLOWED_NAVIGATION_SCHEMES = "allowedNavigationSchemes"
        private const val PROP_HOST_EVENTS_ENABLED = "hostEventsEnabled"
        private const val METHOD_NAVIGATION_COMMAND = "navigationCommand"
        private const val COMMAND_EVALUATE_JAVA_SCRIPT_PREFIX = "evaluateJavaScript:"
        private const val MAX_RUNTIME_JAVA_SCRIPT_LENGTH = 64 * 1024
        private const val EVENT_LOAD_SUCCESS = "loadSuccess"
        private const val EVENT_LOAD_FAILURE = "loadFailure"
        private const val EVENT_NAVIGATION_BLOCKED = "navigationBlocked"
        private const val EVENT_URL_CHANGE = "urlChange"
        private const val EVENT_NAVIGATION_STATE_CHANGE = "navigationStateChange"
        private const val EVENT_HOST_MESSAGE = "hostMessage"
        private const val COMMAND_GO_BACK = "goBack"
        private const val COMMAND_GO_FORWARD = "goForward"
        private const val COMMAND_RELOAD = "reload"
        private const val COMMAND_STOP = "stop"
        private const val hostBridgeScript = """
            (function(){'use strict';
              var port = null;
              try { port = window.raftHostPort; } catch (_) {}
              Object.defineProperty(window, 'RaftHost', { value: Object.freeze({ version: 'raft-host-v1', capabilities: ['raft-feedback-v1'], emit: function(kind, payload) {
                if (typeof kind !== 'string' || !port) return;
                try { port.postMessage(JSON.stringify({kind: kind, payload: (payload && typeof payload === 'object' && !Array.isArray(payload)) ? payload : {}})); } catch (_) {}
              } }), writable: false, configurable: false });
            })();
        """

        private fun String.isEventProp(): Boolean =
            this == EVENT_LOAD_SUCCESS ||
                this == EVENT_LOAD_FAILURE ||
                this == EVENT_NAVIGATION_BLOCKED ||
                this == EVENT_URL_CHANGE ||
                this == EVENT_NAVIGATION_STATE_CHANGE ||
                this == EVENT_HOST_MESSAGE
    }
}

internal fun documentStartScriptOriginRule(targetUrl: String): String? =
    runCatching {
        val uri = URI(targetUrl)
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
        "$scheme://$host$port"
    }.getOrNull()

internal fun injectedJavaScriptBootstrapBaseUrl(targetUrl: String): String? =
    documentStartScriptOriginRule(targetUrl)?.let { origin -> "$origin/__raft_mobile_bootstrap__" }

internal class WebViewBootstrapNavigationFence {
    private var targetUrl: String? = null
    private var targetStarted: Boolean = false

    fun begin(targetUrl: String) {
        this.targetUrl = targetUrl
        targetStarted = false
    }

    fun reset() {
        targetUrl = null
        targetStarted = false
    }

    fun onMainFrameStarted(url: String) {
        if (url == targetUrl) targetStarted = true
    }

    fun mayUncover(url: String): Boolean {
        val target = targetUrl ?: return true
        return targetStarted && url == target
    }
}

private fun String.redactedUrlForLog(): String {
    val uri = runCatching { Uri.parse(this) }.getOrNull() ?: return "<invalid>"
    val scheme = uri.scheme?.lowercase()?.takeIf { it.matches(Regex("[a-z][a-z0-9+.-]*")) } ?: return "<invalid>"
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return "$scheme:<redacted>"
    val port = uri.port.takeIf { it >= 0 }?.let { value -> ":$value" }.orEmpty()
    val path = uri.encodedPath.orEmpty()
    return "$scheme://${host.sanitizedLogValue()}$port${path.sanitizedLogValue()}".take(160)
}

private fun String.sanitizedLogValue(limit: Int = 160): String =
    replace('\n', ' ').replace('\r', ' ').take(limit)
