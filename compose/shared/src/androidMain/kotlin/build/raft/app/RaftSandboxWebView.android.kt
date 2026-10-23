package build.raft.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.DownloadListener
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.net.URI
import org.json.JSONObject as AndroidJSONObject

/**
 * Implemented by the hosting Activity to serve `<input type="file">` in sandbox WebViews
 * (the Kuikly host's `SlockSandboxWebViewFileChooserHost`).
 * TODO(cmp-switch): the CMP Android host must implement this (group 6); until then file inputs
 * receive a cancelled selection.
 */
interface RaftWebViewFileChooserHost {
    fun launchSandboxWebViewFileChooser(
        params: WebChromeClient.FileChooserParams,
        callback: ValueCallback<Array<Uri>>,
    ): Boolean
}

/** A main document served from a host cache instead of the network. */
class RaftWebViewDocument(val bytes: ByteArray, val mimeType: String, val source: String)

/** Android host hooks of the sandbox WebView. */
object RaftAndroidWebViewHooks {
    /**
     * Serves main-frame GET documents from a host cache (release notes: androidApp
     * `RaftReleaseNotesDocumentCache.loadForWebView`, called off the main thread).
     * TODO(cmp-switch): installed by the Android host (group 6); null = always network.
     */
    @Volatile
    var mainDocumentProvider: ((url: String) -> RaftWebViewDocument?)? = null
}

@Composable
internal actual fun RaftSandboxWebViewHost(
    config: RaftWebViewConfig,
    events: RaftWebViewEvents,
    controller: RaftWebViewController,
    modifier: Modifier,
) {
    AndroidView(
        factory = { context -> RaftAndroidSandboxWebView(context, events) },
        modifier = modifier,
        update = { view ->
            view.events = events
            view.applyConfig(config)
            view.bindController(controller)
        },
        onRelease = { view -> view.destroy() },
    )
}

private fun unwrapRaftFileChooserHost(context: Context): RaftWebViewFileChooserHost? {
    var current: Context? = context
    while (current != null) {
        if (current is RaftWebViewFileChooserHost) return current
        current = (current as? ContextWrapper)?.baseContext ?: return null
    }
    return null
}

/**
 * Port of the Kuikly host's `SlockSandboxWebViewRenderView` (androidApp): same settings, sandbox
 * navigation fence, document-start auth script, `raftHostPort` bridge, desktop scale and renderer
 * crash recovery. Kuikly props became [applyConfig], callbacks became [events], the
 * `navigationCommand` call became [bindController].
 */
@SuppressLint("ViewConstructor")
internal class RaftAndroidSandboxWebView(
    context: Context,
    var events: RaftWebViewEvents,
) : FrameLayout(context) {
    private var webView = WebView(context)
    private var destroyed: Boolean = false
    private var src: String = ""
    private var loadedSrc: String = ""
    private var lastSuccessfulUrl: String = ""
    private var allowedUri: Uri? = null
    private var allowScripts: Boolean = true
    private var allowNavigation: Boolean = false
    private var domStorageEnabled: Boolean = false
    private var useWideViewPort: Boolean = false
    /** task #188: true once a desktop-mode initial scale has been applied, so it can be cleared. */
    private var desktopModeScaleApplied: Boolean = false
    /** task #188: guards the one retry used when the scale prop arrives before layout. */
    private var desktopModeScaleRetryScheduled: Boolean = false
    private var cacheEnabled: Boolean = false
    private var ephemeral: Boolean = true
    private var injectedJavaScript: String = ""
    private var pendingRuntimeJavaScript: String = ""
    private var allowedNavigationSchemes: Set<String> = emptySet()
    private var hostEventsEnabled: Boolean = false
    private var suppressLongPress: Boolean = false
    private var backgroundArgb: Int? = null
    private var currentUrl: String = ""
    private var currentTitle: String = ""
    private var currentProgress: Int = 0
    private var currentIsLoading: Boolean = false
    private var lastNavigationStateKey: String = ""
    private var lastLoadFailureReason: String = ""
    private var hostMessageListener: WebViewCompat.WebMessageListener? = null
    private var documentStartScriptHandler: ScriptHandler? = null
    private val bootstrapNavigationFence = RaftWebViewBootstrapNavigationFence()
    private var boundController: RaftWebViewController? = null

    init {
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        configureWebView()
    }

    /** Applies the attrs in the Kuikly node's order (src last). Each setter is a no-op when unchanged. */
    fun applyConfig(config: RaftWebViewConfig) {
        if (destroyed) return
        updateAllowScripts(config.allowScripts)
        updateAllowNavigation(config.allowNavigation)
        updateDomStorageEnabled(config.domStorageEnabled)
        updateUseWideViewPort(config.useWideViewPort)
        updateCacheEnabled(config.cacheEnabled)
        updateEphemeral(config.ephemeral)
        updateInjectedJavaScript(config.injectedJavaScript)
        updateAllowedNavigationSchemes(config.allowedNavigationSchemes)
        updateHostEventsEnabled(config.hostEventsEnabled)
        updateLongPressAndBackground(config.suppressLongPress, config.backgroundArgb)
        updateSrc(config.url)
    }

    private fun updateLongPressAndBackground(nextSuppressLongPress: Boolean, nextBackgroundArgb: Int?) {
        if (suppressLongPress == nextSuppressLongPress && backgroundArgb == nextBackgroundArgb) return
        suppressLongPress = nextSuppressLongPress
        backgroundArgb = nextBackgroundArgb
        applyLongPressAndBackground()
    }

    /**
     * Embedded product surfaces drop the WebView's own long-press affordances: text selection,
     * the selection haptic and the context menu. A consuming long-click listener stops WebView's
     * performLongClick selection path; the page-side CSS (`user-select:none`) covers the rest.
     * Re-applied on every WebView (re)creation.
     */
    private fun applyLongPressAndBackground() {
        if (suppressLongPress) {
            webView.setOnLongClickListener { true }
            webView.isLongClickable = false
            webView.isHapticFeedbackEnabled = false
        } else {
            webView.setOnLongClickListener(null)
            webView.isLongClickable = true
            webView.isHapticFeedbackEnabled = true
        }
        backgroundArgb?.let { argb ->
            webView.setBackgroundColor(argb)
            setBackgroundColor(argb)
        }
    }

    fun bindController(controller: RaftWebViewController) {
        if (boundController === controller) return
        boundController?.unbindCommandDispatcher(this)
        boundController = controller
        controller.bindCommandDispatcher(this) { command -> executeNavigationCommand(command) }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        boundController?.unbindCommandDispatcher(this)
        boundController = null
        removeHostMessageListener()
        pendingRuntimeJavaScript = ""
        removeDocumentStartScript()
        Log.i(LOG_TAG, "destroy_webview loadedSrc=${loadedSrc.raftRedactedUrlForLog()}")
        webView.stopLoading()
        webView.loadUrl(ABOUT_BLANK_URL)
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        removeView(webView)
        webView.destroy()
    }

    private fun recreateWebView(restoreCurrentDocument: Boolean) {
        Log.i(LOG_TAG, "recreate_webview previousSrc=${src.raftRedactedUrlForLog()}")
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
        applyLongPressAndBackground()
        if (restoreCurrentDocument) restoreCurrentDocumentAfterRecreation()
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
            domStorageEnabled = this@RaftAndroidSandboxWebView.domStorageEnabled
            // task #188: without this Android ignores `width=` in the page's viewport meta. Both
            // sides qualified on purpose: inside settings.apply a bare name binds to WebSettings.
            useWideViewPort = this@RaftAndroidSandboxWebView.useWideViewPort
            loadWithOverviewMode = this@RaftAndroidSandboxWebView.useWideViewPort
            @Suppress("DEPRECATION")
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            cacheMode = if (cacheEnabled) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_NO_CACHE
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
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

                override fun onShowFileChooser(
                    view: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    params: FileChooserParams?
                ): Boolean {
                    if (filePathCallback == null) return false
                    if (destroyed || params == null) {
                        filePathCallback.onReceiveValue(null)
                        return false
                    }
                    val host = unwrapRaftFileChooserHost(context)
                    if (host == null) {
                        filePathCallback.onReceiveValue(null)
                        return false
                    }
                    return host.launchSandboxWebViewFileChooser(params, filePathCallback)
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    if (isRestrictedGenericMode()) return true
                    if (!destroyed && consoleMessage != null) logConsoleMessage(consoleMessage)
                    return super.onConsoleMessage(consoleMessage)
                }
            }
        webView.webViewClient =
            object : WebViewClient() {
                /**
                 * task #6: the platform kills the whole app when a renderer process is reclaimed
                 * and no client claims the event. Rebuild the WebView and reload the current
                 * document; the dead back stack is intentionally not restored.
                 */
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    if (destroyed) return true
                    Log.w(
                        LOG_TAG,
                        "render_process_gone didCrash=${detail?.didCrash()} " +
                            "priority=${detail?.rendererPriorityAtExit()} " +
                            "loadedSrc=${loadedSrc.raftRedactedUrlForLog()}"
                    )
                    val restoreDocument = loadedSrc.isNotBlank()
                    removeHostMessageListener()
                    pendingRuntimeJavaScript = ""
                    runCatching {
                        view?.let {
                            it.webChromeClient = null
                            it.webViewClient = WebViewClient()
                            removeView(it)
                            it.destroy()
                        }
                    }
                    recreateWebView(restoreCurrentDocument = restoreDocument)
                    notifyNavigationState(force = true)
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    if (destroyed) return
                    super.onPageStarted(view, url, favicon)
                    val target = url.orEmpty()
                    Log.i(LOG_TAG, "page_started url=${target.raftRedactedUrlForLog()}")
                    bootstrapNavigationFence.onMainFrameStarted(target)
                    val sourceOrigin = raftDocumentStartScriptOriginRule(src)
                    val targetOrigin = raftDocumentStartScriptOriginRule(target)
                    if (sourceOrigin == null || targetOrigin != sourceOrigin) pendingRuntimeJavaScript = ""
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
                    Log.i(LOG_TAG, "page_finished url=${target.raftRedactedUrlForLog()}")
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
                    Log.i(LOG_TAG, "page_commit_visible url=${target.raftRedactedUrlForLog()}")
                    if (url != null && !shouldBlockMainFrameNavigation(url)) {
                        currentUrl = target
                        currentTitle = view?.title.orEmpty().ifBlank { currentTitle }
                        notifyUrlChange(target)
                        notifyNavigationState()
                        if (bootstrapNavigationFence.mayUncover(url)) notifyLoadSuccess(url, "commit_visible")
                    }
                }

                @Deprecated("Deprecated in Java")
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
                    // History API / fragment updates are same-document state changes; keep the
                    // visible URL in sync even when allowNavigation is false.
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
                        hostMainDocumentResponse(url)?.let { return it }
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
                        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        val reason = (if (modern) error?.description?.toString() else null) ?: "webview_load_failed"
                        val errorCode = if (modern) error?.errorCode ?: 0 else 0
                        currentUrl = request.url?.toString().orEmpty().ifBlank { currentUrl }
                        currentIsLoading = false
                        Log.w(LOG_TAG, "page_error code=$errorCode url=${currentUrl.raftRedactedUrlForLog()}")
                        notifyNavigationState()
                        notifyLoadFailure(reason)
                    }
                }

                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    handler?.cancel()
                    if (destroyed) return
                    currentUrl = error?.url.orEmpty().ifBlank { currentUrl }
                    currentIsLoading = false
                    val reason = "ssl_error_${error?.primaryError ?: -1}"
                    Log.w(LOG_TAG, "page_error reason=$reason url=${currentUrl.raftRedactedUrlForLog()}")
                    notifyNavigationState()
                    notifyLoadFailure(reason)
                }
            }
        webView.setDownloadListener(
            DownloadListener { url, _, _, _, _ ->
                if (!destroyed) notifyNavigationBlocked(url.orEmpty())
            }
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun updateAllowScripts(next: Boolean) {
        if (allowScripts == next) return
        allowScripts = next
        if (!next) pendingRuntimeJavaScript = ""
        webView.settings.javaScriptEnabled = next
    }

    private fun updateAllowNavigation(next: Boolean) {
        allowNavigation = next
    }

    private fun updateDomStorageEnabled(next: Boolean) {
        if (domStorageEnabled == next) return
        domStorageEnabled = next
        webView.settings.domStorageEnabled = next
    }

    private fun updateUseWideViewPort(next: Boolean) {
        if (useWideViewPort == next) return
        useWideViewPort = next
        // task #188: gate the pair together; set the scale BEFORE the reload so the new document
        // starts fitted, and reload here (not from shared) so setting + document are one step.
        webView.settings.useWideViewPort = next
        webView.settings.loadWithOverviewMode = next
        applyDesktopModeScale(webView)
        if (webView.url != null) webView.reload()
    }

    /**
     * task #188: `setInitialScale` fits a page that declares `width=1280`; the ratio comes from the
     * shared `raftFilePreviewDesktopScalePercent` (task #514) and the measured view width.
     */
    private fun applyDesktopModeScale(view: WebView?) {
        if (destroyed || view == null) return
        if (!useWideViewPort) {
            if (desktopModeScaleApplied) {
                view.setInitialScale(0)
                view.zoomOut()
                desktopModeScaleApplied = false
            }
            return
        }
        val viewWidthPx = view.width
        if (viewWidthPx <= 0) {
            if (!desktopModeScaleRetryScheduled) {
                desktopModeScaleRetryScheduled = true
                view.post {
                    desktopModeScaleRetryScheduled = false
                    applyDesktopModeScale(view)
                }
            }
            return
        }
        val dpr = resources.displayMetrics.density
        val percent = raftFilePreviewDesktopScalePercent(viewWidthPx, dpr) ?: return
        view.setInitialScale(percent)
        desktopModeScaleApplied = true
        Log.i(LOG_TAG, "desktop_mode_scale viewWidthPx=$viewWidthPx dpr=$dpr percent=$percent")
    }

    private fun updateCacheEnabled(next: Boolean) {
        if (cacheEnabled == next) return
        cacheEnabled = next
        webView.settings.cacheMode = if (next) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_NO_CACHE
    }

    /**
     * Android never isolated WebView storage per view (no non-persistent store API); the flag is
     * recorded only, as in the Kuikly render view.
     */
    private fun updateEphemeral(next: Boolean) {
        ephemeral = next
    }

    private fun updateInjectedJavaScript(next: String) {
        if (injectedJavaScript == next) return
        injectedJavaScript = next
        removeDocumentStartScript()
        if (src.isNotBlank()) installDocumentStartScript(src)
    }

    private fun updateHostEventsEnabled(next: Boolean) {
        if (hostEventsEnabled == next) return
        hostEventsEnabled = next
        if (src.isNotBlank()) {
            pendingRuntimeJavaScript = ""
            loadAllowedSrc(src)
        }
    }

    private fun updateAllowedNavigationSchemes(next: Set<String>) {
        if (allowedNavigationSchemes == next) return
        allowedNavigationSchemes = next
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
        if (next.startsWith(RaftWebViewController.COMMAND_EVALUATE_JAVA_SCRIPT_PREFIX)) {
            evaluateRuntimeJavaScript(next.removePrefix(RaftWebViewController.COMMAND_EVALUATE_JAVA_SCRIPT_PREFIX))
            return
        }
        when (next.trim()) {
            RaftWebViewController.COMMAND_GO_BACK -> if (webView.canGoBack()) webView.goBack()
            RaftWebViewController.COMMAND_GO_FORWARD -> if (webView.canGoForward()) webView.goForward()
            RaftWebViewController.COMMAND_RELOAD -> {
                lastSuccessfulUrl = ""
                lastLoadFailureReason = ""
                currentProgress = 0
                currentIsLoading = true
                webView.reload()
            }
            RaftWebViewController.COMMAND_STOP -> {
                webView.stopLoading()
                currentIsLoading = false
            }
            else -> return
        }
        notifyNavigationState(force = true)
    }

    private fun evaluateRuntimeJavaScript(script: String, allowQueue: Boolean = true) {
        if (destroyed || !allowScripts || script.isBlank()) return
        if (script.length > RaftWebViewMaxRuntimeJavaScriptLength) {
            Log.w(LOG_TAG, "runtime_js result=dropped reason=oversized length=${script.length}")
            return
        }
        val activeUrl = currentUrl.ifBlank { lastSuccessfulUrl }
        val sourceOrigin = raftDocumentStartScriptOriginRule(src)
        val activeOrigin = raftDocumentStartScriptOriginRule(activeUrl)
        if (sourceOrigin == null || activeOrigin != sourceOrigin ||
            (!allowNavigation && !isAllowedMainDocumentUrl(activeUrl))
        ) {
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
        webView.evaluateJavascript(script, null)
        Log.i(LOG_TAG, "runtime_js result=applied")
    }

    private fun flushPendingRuntimeJavaScript() {
        val script = pendingRuntimeJavaScript.takeIf { it.isNotBlank() } ?: return
        pendingRuntimeJavaScript = ""
        evaluateRuntimeJavaScript(script, allowQueue = false)
    }

    private fun updateSrc(next: String) {
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
            webView.loadUrl(ABOUT_BLANK_URL)
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
            val bootstrapDocumentUrl = raftInjectedJavaScriptBootstrapBaseUrl(normalized) ?: normalized
            bootstrapNavigationFence.begin(targetUrl = normalized)
            Log.i(LOG_TAG, "load_src bootstrap=true url=${normalized.raftRedactedUrlForLog()}")
            webView.loadDataWithBaseURL(
                bootstrapDocumentUrl,
                injectedJavaScriptBootstrapHtml(normalized),
                "text/html",
                "UTF-8",
                bootstrapDocumentUrl
            )
        } else {
            bootstrapNavigationFence.reset()
            Log.i(LOG_TAG, "load_src bootstrap=false url=${normalized.raftRedactedUrlForLog()}")
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
        return raftIsExactMainDocumentUrl(allowedUri?.toString(), url)
    }

    private fun notifyLoadFailure(reason: String) {
        if (destroyed) return
        val failureKey = "$currentUrl|$reason"
        if (failureKey == lastLoadFailureReason) return
        lastLoadFailureReason = failureKey
        events.onLoadFailure(reason)
    }

    private fun notifyLoadSuccess(url: String, phase: String, beforeCallback: (() -> Unit)? = null) {
        if (destroyed) return
        if (lastSuccessfulUrl == url) {
            beforeCallback?.invoke()
            return
        }
        lastSuccessfulUrl = url
        lastLoadFailureReason = ""
        beforeCallback?.invoke()
        Log.i(LOG_TAG, "page_success phase=$phase url=${url.raftRedactedUrlForLog()}")
        events.onLoadSuccess()
    }

    private fun notifyNavigationBlocked(url: String) {
        if (destroyed) return
        events.onNavigationBlocked(url)
    }

    private fun notifyUrlChange(url: String) {
        if (destroyed) return
        events.onUrlChange(url)
    }

    private fun notifyNavigationState(force: Boolean = false) {
        if (destroyed) return
        val state =
            RaftWebViewNavigationState(
                url = currentUrl,
                title = currentTitle,
                canGoBack = webView.canGoBack(),
                canGoForward = webView.canGoForward(),
                isLoading = currentIsLoading,
                progress = currentProgress.coerceIn(0, 100)
            )
        val stateKey = state.toString()
        if (!force && stateKey == lastNavigationStateKey) return
        lastNavigationStateKey = stateKey
        events.onNavigationStateChange(state)
    }

    private fun evaluateInjectedJavaScript() {
        if (destroyed) return
        val script = injectedJavaScript.takeIf { allowScripts && it.isNotBlank() } ?: return
        webView.evaluateJavascript(script, null)
    }

    private fun logConsoleMessage(consoleMessage: ConsoleMessage) {
        val source = consoleMessage.sourceId().orEmpty().raftRedactedUrlForLog()
        val message = consoleMessage.message().orEmpty().raftSanitizedLogValue(LOG_MESSAGE_LIMIT)
        val line = consoleMessage.lineNumber()
        when (consoleMessage.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> Log.e(LOG_TAG, "console_error line=$line source=$source message=$message")
            ConsoleMessage.MessageLevel.WARNING -> Log.w(LOG_TAG, "console_warning line=$line source=$source message=$message")
            else ->
                Log.i(
                    LOG_TAG,
                    "console_${consoleMessage.messageLevel().name.lowercase()} line=$line source=$source message=$message"
                )
        }
    }

    private fun shouldBootstrapInjectedJavaScript(documentStartInstalled: Boolean): Boolean =
        allowScripts && domStorageEnabled && injectedJavaScript.isNotBlank() && !documentStartInstalled

    private fun installHostMessageListener(targetUrl: String) {
        removeHostMessageListener()
        if (!hostEventsEnabled ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            return
        }
        val originRule = raftDocumentStartScriptOriginRule(targetUrl) ?: return
        runCatching {
            val listener =
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy
                    ) {
                        if (!isMainFrame || sourceOrigin.toString() != originRule) return
                        // Same-origin history routes must not keep access to the native bridge.
                        val activeUrl = view.url.orEmpty()
                        if (!allowNavigation && !isAllowedMainDocumentUrl(activeUrl)) return
                        val raw = message.data ?: return
                        if (!destroyed) events.dispatchHostMessage(raw)
                    }
                }
            hostMessageListener = listener
            WebViewCompat.addWebMessageListener(webView, RaftWebViewHostPortName, setOf(originRule), listener)
            Log.i(LOG_TAG, "host_message_listener installed origin=${originRule.raftRedactedUrlForLog()}")
        }.onFailure { Log.w(LOG_TAG, "host_message_listener unavailable", it) }
    }

    private fun removeHostMessageListener() {
        runCatching { WebViewCompat.removeWebMessageListener(webView, RaftWebViewHostPortName) }
        hostMessageListener = null
    }

    private fun installDocumentStartScript(targetUrl: String): Boolean {
        removeDocumentStartScript()
        val authScript = injectedJavaScript.takeIf { allowScripts && domStorageEnabled && it.isNotBlank() } ?: ""
        val bridge =
            HOST_BRIDGE_SCRIPT.takeIf {
                hostEventsEnabled && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
            }.orEmpty()
        val script = (bridge + "\n" + authScript).trim().takeIf { it.isNotBlank() } ?: return false
        val originRule = raftDocumentStartScriptOriginRule(targetUrl) ?: return false
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        return runCatching {
            documentStartScriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(originRule))
            Log.i(LOG_TAG, "document_start_script installed origin=${originRule.raftRedactedUrlForLog()}")
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

    /**
     * Feedback carries an in-memory bearer in its bootstrap: never forward page console output for
     * a fenced settings document (hostile page code could print the bearer into logcat).
     */
    private fun isRestrictedGenericMode(): Boolean =
        allowedNavigationSchemes.isNotEmpty() && (!allowNavigation || injectedJavaScript.isBlank())

    private fun injectedJavaScriptBootstrapHtml(targetUrl: String): String {
        val safeScript = injectedJavaScript.replace("</script", "<\\/script", ignoreCase = true)
        val safeTargetUrl = AndroidJSONObject.quote(targetUrl)
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

    private fun hostMainDocumentResponse(url: String?): WebResourceResponse? {
        val target = url ?: return null
        val provider = RaftAndroidWebViewHooks.mainDocumentProvider ?: return null
        val startedAt = System.currentTimeMillis()
        val document = runCatching { provider(target) }.getOrNull() ?: return null
        Log.i(
            LOG_TAG,
            "host_main_document source=${document.source} bytes=${document.bytes.size} " +
                "durationMs=${System.currentTimeMillis() - startedAt} url=${target.raftRedactedUrlForLog()}"
        )
        return WebResourceResponse(document.mimeType, "utf-8", ByteArrayInputStream(document.bytes))
    }

    private companion object {
        const val LOG_TAG = "SlockWebView"
        const val LOG_MESSAGE_LIMIT = 500
        const val ABOUT_BLANK_URL = "about:blank"
        const val HOST_BRIDGE_SCRIPT = """
            (function(){'use strict';
              var port = null;
              try { port = window.raftHostPort; } catch (_) {}
              Object.defineProperty(window, 'RaftHost', { value: Object.freeze({ version: 'raft-host-v1', capabilities: ['raft-feedback-v1'], emit: function(kind, payload) {
                if (typeof kind !== 'string' || !port) return;
                try { port.postMessage(JSON.stringify({kind: kind, payload: (payload && typeof payload === 'object' && !Array.isArray(payload)) ? payload : {}})); } catch (_) {}
              } }), writable: false, configurable: false });
            })();
        """
    }
}

/** Exact scheme/authority/path/query comparison used after same-origin history updates. */
internal fun raftIsExactMainDocumentUrl(allowedUrl: String?, targetUrl: String): Boolean {
    val allowed = runCatching { URI(allowedUrl ?: return false) }.getOrNull() ?: return false
    val target = runCatching { URI(targetUrl) }.getOrNull() ?: return false
    return target.scheme == allowed.scheme &&
        target.host == allowed.host &&
        target.port == allowed.port &&
        target.path == allowed.path &&
        target.rawQuery == allowed.rawQuery
}

internal fun raftDocumentStartScriptOriginRule(targetUrl: String): String? =
    runCatching {
        val uri = URI(targetUrl)
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
        "$scheme://$host$port"
    }.getOrNull()

internal fun raftInjectedJavaScriptBootstrapBaseUrl(targetUrl: String): String? =
    raftDocumentStartScriptOriginRule(targetUrl)?.let { origin -> "$origin/__raft_mobile_bootstrap__" }

/** Holds load success until the real target (not the bootstrap document) has started. */
internal class RaftWebViewBootstrapNavigationFence {
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

private fun String.raftRedactedUrlForLog(): String {
    val uri = runCatching { Uri.parse(this) }.getOrNull() ?: return "<invalid>"
    val scheme = uri.scheme?.lowercase()?.takeIf { it.matches(Regex("[a-z][a-z0-9+.-]*")) } ?: return "<invalid>"
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return "$scheme:<redacted>"
    val port = uri.port.takeIf { it >= 0 }?.let { value -> ":$value" }.orEmpty()
    val path = uri.encodedPath.orEmpty()
    return "$scheme://${host.raftSanitizedLogValue()}$port${path.raftSanitizedLogValue()}".take(160)
}

private fun String.raftSanitizedLogValue(limit: Int = 160): String =
    replace('\n', ' ').replace('\r', ' ').take(limit)
