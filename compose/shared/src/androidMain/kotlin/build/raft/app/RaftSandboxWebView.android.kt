package build.raft.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
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
        factory = { context ->
            val kept = config.keepAliveKey?.let { key -> RaftAndroidWebViewKeepAlive.take(key, context, events) }
            if (config.keepAliveKey != null) {
                raftLogInfo(RAFT_WEBVIEW_DIAGNOSTICS_TAG, "webview_attach source=${if (kept != null) "keep_alive" else "new"}")
            }
            kept ?: RaftAndroidSandboxWebView(MutableContextWrapper(context), events)
        },
        modifier = modifier,
        update = { view ->
            view.events = events
            view.applyConfig(config)
            view.bindController(controller)
        },
        onRelease = { view ->
            if (!RaftAndroidWebViewKeepAlive.park(view)) view.destroy()
        },
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
    /** #proj-workspace:#360: pinch-to-zoom stays enabled even while [useWideViewPort] is off. */
    private var allowPinchZoom: Boolean = false
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
    /** [RaftWebViewConfig.mainFrameOrigin]: every top-level document stays on it. */
    private var mainFrameOrigin: String? = null
    /** [RaftWebViewConfig.keepAliveKey]: parked past its page instead of destroyed. */
    internal var keepAliveKey: String? = null
        private set
    /** [RaftWebViewConfig.diagnosticsSurface]; `_parked` is appended while kept alive without a page. */
    private var diagnosticsSurface: String = RAFT_WEBVIEW_SURFACE_DEFAULT
    private var parkedForKeepAlive: Boolean = false
    /**
     * The app's light/dark mode the WebView was built in. Its page reads `prefers-color-scheme`
     * from the construction context's theme, which follows the app's choice (androidApp
     * RaftAndroidNightMode); keep-alive does not hand a view of the other mode to a page.
     */
    internal var builtDark: Boolean = RaftAppThemeState.isDark
        private set
    /** [RaftWebViewConfig.backgroundArgb]: the colour before the first paint (0: white). */
    private var placeholderBackground: Int = 0
    /** The current WebView painted a page; from then on its background is the default white. */
    private var pagePainted: Boolean = false
    internal val isDestroyed: Boolean get() = destroyed
    private var currentUrl: String = ""
    private var currentTitle: String = ""
    private var currentProgress: Int = 0
    private var currentIsLoading: Boolean = false

    /** Main-frame loads started ([RaftWebViewNavigationState.loadGeneration]); kept across a rebuilt WebView. */
    private var loadGeneration: Int = 0
    private var lastNavigationStateKey: String = ""
    private var lastLoadFailureReason: String = ""
    private var hostMessageListener: WebViewCompat.WebMessageListener? = null
    /** [startPageDownload]'s `blob:` reads in flight, by token; their port and name hook. */
    private val pendingBlobDownloads = LinkedHashMap<String, RaftWebViewBlobDownloadAssembler>()
    private var downloadMessageListener: WebViewCompat.WebMessageListener? = null
    private var downloadNameScriptHandler: ScriptHandler? = null
    /** The page's `navigator.share` calls being received ([raftWebViewShareShimScript]); one per installed port. */
    private var shareSession: RaftAndroidWebViewShareSession? = null
    private val shareMainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var shareExpiryCheck: Runnable? = null
    private var documentStartScriptHandler: ScriptHandler? = null
    /** [raftWebViewFirstPaintThemeScript] at document start; separate from the auth/bridge script. */
    private var firstPaintScriptHandler: ScriptHandler? = null
    private val bootstrapNavigationFence = RaftWebViewBootstrapNavigationFence()
    private var boundController: RaftWebViewController? = null
    /** Diagnostics: when the current main-frame load started (0: none), and the progress milestones logged. */
    private var loadStartedAtMs: Long = 0L
    private var loggedProgressMilestone: Int = 0
    private var persistedConsoleErrors: Int = 0

    init {
        RaftAndroidWebViewProvider.logOnce(context)
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        configureWebView()
    }

    /** Applies the attrs in the Kuikly node's order (src last). Each setter is a no-op when unchanged. */
    fun applyConfig(config: RaftWebViewConfig) {
        if (destroyed) return
        updateAllowScripts(config.allowScripts)
        updateAllowNavigation(config.allowNavigation)
        updateDomStorageEnabled(config.domStorageEnabled)
        updateCacheEnabled(config.cacheEnabled)
        updateEphemeral(config.ephemeral)
        // Before the wide-viewport switch, which reloads: the reloaded document gets the new script
        // (the Artifact viewer's Desktop Mode changes both at once).
        updateInjectedJavaScript(config.injectedJavaScript)
        updateAllowPinchZoom(config.allowPinchZoom)
        updateUseWideViewPort(config.useWideViewPort)
        updateAllowedNavigationSchemes(config.allowedNavigationSchemes)
        updateHostEventsEnabled(config.hostEventsEnabled)
        mainFrameOrigin = config.mainFrameOrigin?.let(::raftDocumentStartScriptOriginRule)
        keepAliveKey = config.keepAliveKey
        diagnosticsSurface = config.diagnosticsSurface
        updatePlaceholderBackground(config.backgroundArgb)
        updateSrc(config.url)
    }

    /**
     * Until its first page paints, the WebView shows the app's canvas colour instead of its default
     * white: the page is visible from attach (no cover), and a white rectangle would flash in dark
     * mode. Afterwards white, as a browser, under pages that paint no background of their own.
     */
    private fun updatePlaceholderBackground(argb: Int) {
        placeholderBackground = argb
        if (!pagePainted) webView.setBackgroundColor(raftWebViewBackgroundArgb(argb, painted = false))
    }

    private fun onPagePainted() {
        if (pagePainted) return
        pagePainted = true
        webView.setBackgroundColor(raftWebViewBackgroundArgb(placeholderBackground, painted = true))
    }

    /** The kept document (keep-alive), as [RaftWebViewKeptDocument]. */
    internal fun keptDocument(): RaftWebViewKeptDocument =
        RaftWebViewKeptDocument(
            src = src,
            currentUrl = webView.url.orEmpty().ifBlank { currentUrl },
            loaded = !currentIsLoading && lastSuccessfulUrl.isNotBlank(),
        )

    /**
     * Keep-alive: runs [script] in the top-level document when it is a loaded document of
     * [origin] (never a sub-frame: `evaluateJavascript` reaches the main frame only).
     */
    internal fun evaluateInTopDocument(origin: String, script: String): Boolean {
        if (destroyed || !allowScripts || script.isBlank()) return false
        val expected = raftDocumentStartScriptOriginRule(origin) ?: return false
        val active = raftDocumentStartScriptOriginRule(webView.url.orEmpty()) ?: return false
        if (active != expected || (mainFrameOrigin != null && active != mainFrameOrigin)) return false
        if (currentIsLoading || lastSuccessfulUrl.isBlank()) return false
        webView.evaluateJavascript(script, null)
        return true
    }

    /** Keep-alive: detaches from the page that showed it; its callbacks go nowhere until taken again. */
    internal fun detachForKeepAlive() {
        boundController?.unbindCommandDispatcher(this)
        boundController = null
        events = RaftWebViewEvents()
        parkedForKeepAlive = true
        (parent as? android.view.ViewGroup)?.removeView(this)
        (context as? MutableContextWrapper)?.baseContext = context.applicationContext
    }

    /** Keep-alive: shown by a page again, in that page's [hostContext]. */
    internal fun attachFromKeepAlive(hostContext: Context, nextEvents: RaftWebViewEvents) {
        (context as? MutableContextWrapper)?.baseContext = hostContext
        parkedForKeepAlive = false
        events = nextEvents
        lastNavigationStateKey = ""
        notifyNavigationState(force = true)
        if (lastSuccessfulUrl.isNotBlank()) events.onLoadSuccess()
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
        removeDownloadSupport()
        pendingRuntimeJavaScript = ""
        removeDocumentStartScript()
        diagnosticsInfo("destroy_webview loadedSrc=${loadedSrc.formatUrlForLog()}")
        webView.stopLoading()
        webView.loadUrl(ABOUT_BLANK_URL)
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        removeView(webView)
        webView.destroy()
    }

    /** [restoreUrl]: the page to load in the new WebView (the one shown, [raftWebViewRendererGoneRestoreUrl]); [src] when blank. */
    private fun recreateWebView(restoreCurrentDocument: Boolean, restoreUrl: String = "") {
        diagnosticsInfo("recreate_webview previousSrc=${src.formatUrlForLog()}")
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
        builtDark = RaftAppThemeState.isDark
        pagePainted = false
        webView.setBackgroundColor(raftWebViewBackgroundArgb(placeholderBackground, painted = false))
        removeAllViews()
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        configureWebView()
        if (restoreCurrentDocument) restoreCurrentDocumentAfterRecreation(restoreUrl)
    }

    private fun restoreCurrentDocumentAfterRecreation(restoreUrl: String) {
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
        loadAllowedSrc(src, documentUrl = restoreUrl.ifBlank { src })
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
            // Pinch-to-zoom is on in Desktop Mode (a wide layout fitted to the screen) and on
            // surfaces that opt in (#proj-workspace:#360, the Artifact viewer). Android WebView
            // pinch-zooms only with supportZoom + builtInZoomControls on (its +/- buttons stay hidden).
            setSupportZoom(true)
            builtInZoomControls =
                this@RaftAndroidSandboxWebView.useWideViewPort || this@RaftAndroidSandboxWebView.allowPinchZoom
            displayZoomControls = false
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
                    logProgressMilestone(currentProgress)
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
                    // A new window (multiple windows are off, so the engine normally navigates this
                    // one instead): the link's address, routed by the host like any blocked page.
                    val hit = view?.hitTestResult
                    val link = hit?.extra?.takeIf { hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE }.orEmpty()
                    notifyNavigationBlocked(link)
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
                    // Logged and joined to the death event (one Hands ticket however many WebViews it took).
                    RaftWebViewRendererGone.record(
                        context,
                        if (parkedForKeepAlive) "${diagnosticsSurface}_parked" else diagnosticsSurface,
                        detail,
                    )
                    diagnosticsWarn(
                        "render_process_gone didCrash=${detail?.didCrash()} " +
                            "priority=${detail?.rendererPriorityAtExit()} " +
                            "loadedSrc=${loadedSrc.formatUrlForLog()}",
                        always = true,
                    )
                    val restoreDocument = loadedSrc.isNotBlank()
                    // #300: the page it was showing (a kept Artifact viewer is routed in place from
                    // its warm src), checked against the fence before the WebView is rebuilt.
                    val restoreUrl =
                        // Gone during a bootstrap load: the page it was loading, not the placeholder.
                        raftWebViewRendererGoneRestoreUrl(
                            src,
                            bootstrapNavigationFence.pendingTargetUrl ?: currentUrl,
                            ::rendererGoneRestoreAllows,
                        )
                    diagnosticsInfo("render_process_gone_restore url=${restoreUrl.formatUrlForLog()} shown=${restoreUrl != src}")
                    // The host's load watchdog: an error state when the page was not up yet.
                    events.onContentProcessGone(if (detail?.didCrash() == true) "render_process_crashed" else "render_process_killed")
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
                    recreateWebView(restoreCurrentDocument = restoreDocument, restoreUrl = restoreUrl)
                    notifyNavigationState(force = true)
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    if (destroyed) return
                    super.onPageStarted(view, url, favicon)
                    val target = url.orEmpty()
                    loadStartedAtMs = System.currentTimeMillis()
                    loggedProgressMilestone = 0
                    persistedConsoleErrors = 0
                    diagnosticsInfo("page_started url=${target.formatUrlForLog()}")
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
                    loadGeneration += 1
                    lastSuccessfulUrl = ""
                    lastLoadFailureReason = ""
                    notifyUrlChange(target)
                    notifyNavigationState()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (destroyed) return
                    super.onPageFinished(view, url)
                    val target = url.orEmpty()
                    diagnosticsInfo("page_finished url=${target.formatUrlForLog()} ${loadElapsedField()}")
                    if (url != null && !shouldBlockMainFrameNavigation(url)) {
                        currentUrl = target
                        currentTitle = view?.title.orEmpty().ifBlank { currentTitle }
                        currentProgress = 100
                        currentIsLoading = false
                        evaluateInjectedJavaScript(url)
                        if (bootstrapNavigationFence.mayUncover(url)) {
                            onPagePainted()
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
                    diagnosticsInfo("page_commit_visible url=${target.formatUrlForLog()} ${loadElapsedField()}")
                    if (url != null && !shouldBlockMainFrameNavigation(url)) {
                        currentUrl = target
                        currentTitle = view?.title.orEmpty().ifBlank { currentTitle }
                        notifyUrlChange(target)
                        notifyNavigationState()
                        if (bootstrapNavigationFence.mayUncover(url)) {
                            onPagePainted()
                            notifyLoadSuccess(url, "commit_visible")
                        }
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
                        shouldRouteMainFrameNavigation(target) || shouldBlockMainFrameNavigation(target)
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
                        diagnosticsWarn("page_error code=$errorCode url=${currentUrl.formatUrlForLog()} ${loadElapsedField()}")
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
                    diagnosticsWarn("page_error reason=$reason url=${currentUrl.formatUrlForLog()} ${loadElapsedField()}")
                    notifyNavigationState()
                    notifyLoadFailure(reason)
                }

                /** Diagnostics only: a main-frame HTTP error (4xx/5xx) still renders the error page as before. */
                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (destroyed || request?.isForMainFrame != true) return
                    diagnosticsWarn(
                        "page_http_error status=${errorResponse?.statusCode ?: -1} " +
                            "url=${request.url?.toString().orEmpty().formatUrlForLog()} ${loadElapsedField()}"
                    )
                }
            }
        webView.setDownloadListener(
            DownloadListener { url, _, contentDisposition, mimeType, _ ->
                if (destroyed) return@DownloadListener
                if (events.onDownloadRequested == null) {
                    notifyNavigationBlocked(url.orEmpty())
                    return@DownloadListener
                }
                startPageDownload(url.orEmpty(), contentDisposition, mimeType)
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

    private fun applyZoomSettings() {
        webView.settings.setSupportZoom(true)
        webView.settings.builtInZoomControls = useWideViewPort || allowPinchZoom
        webView.settings.displayZoomControls = false
    }

    private fun updateAllowPinchZoom(next: Boolean) {
        if (allowPinchZoom == next) return
        allowPinchZoom = next
        applyZoomSettings()
    }

    private fun updateUseWideViewPort(next: Boolean) {
        if (useWideViewPort == next) return
        useWideViewPort = next
        // task #188: gate the pair together; set the scale BEFORE the reload so the new document
        // starts fitted, and reload here (not from shared) so setting + document are one step.
        webView.settings.useWideViewPort = next
        webView.settings.loadWithOverviewMode = next
        applyZoomSettings()
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
        if (next.trim() == RaftWebViewController.COMMAND_PROBE_DOCUMENT_CONTENT) {
            probeDocumentContent()
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
            (mainFrameOrigin != null && activeOrigin != mainFrameOrigin) ||
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

    /**
     * The fixed, read-only [RaftWebViewContentProbeScript] (a '1'/'0' answer, nothing of the page's
     * data), so it is not fenced like runtime scripts. No scripts: no answer (null, fail open).
     */
    private fun probeDocumentContent() {
        if (destroyed) return
        if (!allowScripts) {
            events.onContentProbe(null)
            return
        }
        val target = webView
        target.evaluateJavascript(RaftWebViewContentProbeScript) { value ->
            if (!destroyed && target === webView) events.onContentProbe(raftWebViewDecodeContentProbe(value))
        }
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
        if (!isNavigationSchemeAllowed(normalized) || !raftMainFrameOriginAllows(mainFrameOrigin, normalized)) {
            notifyNavigationState()
            notifyNavigationBlocked(normalized)
            return
        }
        loadAllowedSrc(normalized)
    }

    /**
     * Loads [normalized] as this view's src. [documentUrl] is the page actually loaded: the src
     * itself, or after a renderer rebuild the page that was shown ([raftWebViewRendererGoneRestoreUrl]).
     * The src stays [loadedSrc], so the host's unchanged config does not load it over that page.
     */
    private fun loadAllowedSrc(normalized: String, documentUrl: String = normalized) {
        loadedSrc = normalized
        currentUrl = documentUrl
        currentIsLoading = true
        notifyNavigationState()
        val documentStartInstalled = installDocumentStartScript(normalized)
        installHostMessageListener(normalized)
        // The page's downloads and `navigator.share`: for a host that takes downloads, or any origin-locked page.
        if (events.onDownloadRequested != null || mainFrameOrigin != null) installDownloadSupport(normalized) else removeDownloadSupport()
        if (shouldBootstrapInjectedJavaScript(documentStartInstalled)) {
            val bootstrapDocumentUrl = raftInjectedJavaScriptBootstrapBaseUrl(documentUrl) ?: documentUrl
            bootstrapNavigationFence.begin(targetUrl = documentUrl)
            diagnosticsInfo("load_src bootstrap=true url=${documentUrl.formatUrlForLog()}")
            webView.loadDataWithBaseURL(
                bootstrapDocumentUrl,
                injectedJavaScriptBootstrapHtml(documentUrl),
                "text/html",
                "UTF-8",
                bootstrapDocumentUrl
            )
        } else {
            bootstrapNavigationFence.reset()
            diagnosticsInfo("load_src bootstrap=false url=${documentUrl.formatUrlForLog()}")
            webView.loadUrl(documentUrl)
        }
    }

    /**
     * A new document the host routes as a link ([RaftWebViewEvents.routesMainFrameNavigation]): the
     * page asked for it (`window.open` loads in place here, multiple windows being off), so it is
     * handed over as a blocked navigation and the page stays as it is.
     */
    private fun shouldRouteMainFrameNavigation(url: String?): Boolean {
        val target = url ?: return false
        if (wouldBlockMainFrameNavigation(target)) return false
        if (events.routesMainFrameNavigation?.invoke(target) != true) return false
        diagnosticsInfo("navigation_routed_as_link url=${target.formatUrlForLog()}")
        handleBlockedMainFrameNavigation(target)
        return true
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

    /**
     * Whether a WebView rebuilt after its renderer was gone may load [candidate] instead of its src
     * ([raftWebViewRendererGoneRestoreUrl]): the navigation fence lets it load as a main frame, and it
     * is on the src's origin (the document-start script is scoped to that origin).
     */
    internal fun rendererGoneRestoreAllows(candidate: String): Boolean =
        !wouldBlockMainFrameNavigation(candidate) &&
            raftDocumentStartScriptOriginRule(candidate) == raftDocumentStartScriptOriginRule(src)

    private fun wouldBlockMainFrameNavigation(url: String?): Boolean {
        val target = url ?: return true
        if (src.isBlank() && target == ABOUT_BLANK_URL) return false
        if (!isNavigationSchemeAllowed(target)) return true
        if (!raftMainFrameOriginAllows(mainFrameOrigin, target)) return true
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
        diagnosticsInfo("page_success phase=$phase url=${url.formatUrlForLog()} ${loadElapsedField()}")
        events.onLoadSuccess()
    }

    private fun String.formatUrlForLog(): String =
        if (diagnosticsSurface == SETTINGS_WEB_PAGE_DIAGNOSTICS_NAME) {
            raftOwnWebUrlForLog(this)
        } else {
            raftRedactedUrlForLog()
        }

    /**
     * Load-path lines. For a keep-alive WebView (the Artifact viewer) they also go to the persistent
     * diagnostics ring (raftLogInfo; feedback archives), not only logcat: before, nothing of the
     * viewer's WebView reached a feedback log. URLs are always [raftRedactedUrlForLog] (no query).
     */
    private fun diagnosticsInfo(message: String) {
        if (keepAliveKey != null) raftLogInfo(RAFT_WEBVIEW_DIAGNOSTICS_TAG, message) else Log.i(LOG_TAG, message)
    }

    private fun diagnosticsWarn(message: String, always: Boolean = false) {
        if (always || keepAliveKey != null) raftLogWarn(RAFT_WEBVIEW_DIAGNOSTICS_TAG, message) else Log.w(LOG_TAG, message)
    }

    private fun loadElapsedField(): String =
        "elapsedMs=${if (loadStartedAtMs > 0L) System.currentTimeMillis() - loadStartedAtMs else -1L}"

    private fun logProgressMilestone(progress: Int) {
        val milestone = raftWebViewProgressMilestone(progress, loggedProgressMilestone) ?: return
        loggedProgressMilestone = milestone
        if (keepAliveKey != null) diagnosticsInfo("page_progress percent=$milestone ${loadElapsedField()}")
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
                progress = currentProgress.coerceIn(0, 100),
                loadGeneration = loadGeneration,
            )
        val stateKey = state.toString()
        if (!force && stateKey == lastNavigationStateKey) return
        lastNavigationStateKey = stateKey
        events.onNavigationStateChange(state)
    }

    private fun evaluateInjectedJavaScript(pageUrl: String? = null) {
        if (destroyed || !allowScripts) return
        val script = injectedJavaScript.takeIf { it.isNotBlank() } ?: return
        val activeUrl = pageUrl?.takeIf { it.isNotBlank() } ?: webView.url.orEmpty()
        val sourceOrigin = raftDocumentStartScriptOriginRule(src)
        val activeOrigin = raftDocumentStartScriptOriginRule(activeUrl)
        if (sourceOrigin == null || activeOrigin != sourceOrigin) return
        val viewUrl = webView.url?.takeIf { it.isNotBlank() }
        if (viewUrl != null && raftDocumentStartScriptOriginRule(viewUrl) != sourceOrigin) return
        if (mainFrameOrigin != null && activeOrigin != mainFrameOrigin) return
        if (!allowNavigation && !isAllowedMainDocumentUrl(activeUrl)) return
        webView.evaluateJavascript(script, null)
    }

    private fun logConsoleMessage(consoleMessage: ConsoleMessage) {
        val source = consoleMessage.sourceId().orEmpty().formatUrlForLog()
        val message = consoleMessage.message().orEmpty().raftSanitizedLogValue(LOG_MESSAGE_LIMIT)
        val line = consoleMessage.lineNumber()
        when (consoleMessage.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> {
                Log.e(LOG_TAG, "console_error line=$line source=$source message=$message")
                // The viewer's first few page errors also go to the persistent ring (short, no query).
                if (keepAliveKey != null && persistedConsoleErrors < PERSISTED_CONSOLE_ERROR_LIMIT) {
                    persistedConsoleErrors += 1
                    raftLogWarn(
                        RAFT_WEBVIEW_DIAGNOSTICS_TAG,
                        "console_error line=$line source=$source message=${message.take(PERSISTED_CONSOLE_MESSAGE_CHARS)}",
                    )
                }
            }
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

    /**
     * A file the page saves, kept in the app as a browser keeps it (#proj-mobile:#202):
     * - `blob:` / `data:` (the web's Download saves this way): read in the page right away (the web
     *   revokes the address soon after the click) and posted back in chunks to [RaftWebViewDownloadPortName],
     *   which only the page's own origin, main frame, can reach.
     * - http(s) on the page's own origin: handed to the host by address with the page's `download`
     *   name; the host downloads it with its session.
     * - anything else (another origin): the blocked-navigation route, as before.
     */
    private fun startPageDownload(url: String, contentDisposition: String?, mimeType: String?) {
        val report = events.onDownloadRequested ?: return
        val pageOrigin = raftDocumentStartScriptOriginRule(currentUrl.ifBlank { loadedSrc })
        val trustedPage = pageOrigin != null && (mainFrameOrigin == null || pageOrigin == mainFrameOrigin)
        val lower = url.lowercase()
        when {
            lower.startsWith("blob:") || lower.startsWith("data:") -> {
                if (!trustedPage || downloadMessageListener == null) {
                    diagnosticsWarn("page_download result=failed reason=no_port")
                    report(RaftWebViewDownload.Failed("no_port"))
                    return
                }
                val token = "d" + kotlin.random.Random.nextLong().toULong().toString(16)
                if (pendingBlobDownloads.size >= 4) pendingBlobDownloads.remove(pendingBlobDownloads.keys.first())
                pendingBlobDownloads[token] = RaftWebViewBlobDownloadAssembler(token, url)
                diagnosticsInfo("page_download kind=blob")
                webView.evaluateJavascript(raftWebViewBlobReadScript(url, token), null)
            }
            (lower.startsWith("https:") || lower.startsWith("http:")) &&
                trustedPage && raftDocumentStartScriptOriginRule(url) == pageOrigin -> {
                diagnosticsInfo("page_download kind=same_origin")
                webView.evaluateJavascript(
                    "(function(){var n=window.__raftDownloadNames;return n&&n[${build.raft.app.compat.json.JSONObject.quote(url)}]||'';})()"
                ) { value ->
                    if (destroyed) return@evaluateJavascript
                    val pageName =
                        value?.takeIf { it.length >= 2 && it.startsWith("\"") }
                            ?.let { runCatching { AndroidJSONObject("{\"v\":$it}").optString("v") }.getOrNull() }
                            ?.takeIf { it.isNotBlank() }
                    events.onDownloadRequested?.invoke(
                        RaftWebViewDownload.Url(url, pageName, contentDisposition, mimeType)
                    )
                }
            }
            else -> notifyNavigationBlocked(url)
        }
    }

    /**
     * A message of the page's `navigator.share` ([raftWebViewShareShimScript]). The UI thread only
     * hands it on: it is parsed, decoded and written to the share's staging folder on
     * [RaftWebViewShareIo], in the order posted, and what follows (the next chunk asked for, the share
     * sheet, the Promise settled) comes back here. Nothing thrown leaves this function.
     */
    private fun acceptShareMessage(session: RaftAndroidWebViewShareSession, raw: String) {
        // A session whose port is gone takes nothing more: nothing would abort what it started.
        if (destroyed || shareSession !== session) return
        scheduleShareExpiry(session)
        runShareIo(session) { session.accept(raw) }
    }

    /**
     * Runs [work] on the share thread and its signals back on the main thread. With [rearmExpiry],
     * the expiry check is set again while the session still has a share being received.
     */
    private fun runShareIo(
        session: RaftAndroidWebViewShareSession,
        rearmExpiry: Boolean = false,
        work: () -> List<RaftAndroidStagedShareSignal>?
    ) {
        runCatching {
            RaftWebViewShareIo.execute {
                val signals = runCatching(work).getOrElse { runCatching { session.abortAll("internal") }.getOrDefault(emptyList()) }
                if (!signals.isNullOrEmpty()) shareMainHandler.post { deliverShareSignals(session, signals) }
                if (rearmExpiry && runCatching { session.hasPending() }.getOrDefault(false)) {
                    shareMainHandler.post {
                        if (!destroyed && shareSession === session && shareExpiryCheck == null) {
                            scheduleShareExpiry(session, RaftWebViewShareExpiryRecheckMs)
                        }
                    }
                }
            }
        }.onFailure { Log.w(LOG_TAG, "page_share io unavailable", it) }
    }

    private fun deliverShareSignals(session: RaftAndroidWebViewShareSession, signals: List<RaftAndroidStagedShareSignal>) {
        val current = !destroyed && shareSession === session
        signals.forEach { staged ->
            runCatching {
                when (val signal = staged.signal) {
                    is RaftWebViewShareSignal.Ack ->
                        if (current) webView.evaluateJavascript(raftWebViewShareAckScript(signal.id), null)
                    is RaftWebViewShareSignal.Failed -> {
                        diagnosticsWarn("page_share result=failed reason=${signal.reason}")
                        settleShare(signal.id, signal.errorName)
                    }
                    is RaftWebViewShareSignal.Ready ->
                        if (current) {
                            presentShare(signal.request, staged.folder)
                        } else {
                            // The page went away while its last bytes were written.
                            discardShareFolder(staged.folder)
                            settleShare(signal.request.id, "AbortError")
                        }
                }
                Unit
            }.onFailure { Log.w(LOG_TAG, "page_share deliver failed", it) }
        }
    }

    /**
     * Gives up shares whose page has gone silent: every share message pushes this check
     * [RaftWebViewShareIdleTimeoutMs] ahead. A share's silence is counted from when its last message
     * was handled on the share thread, which may be later than when it was posted, so the check comes
     * back every [RaftWebViewShareExpiryRecheckMs] until no share is left being received.
     */
    private fun scheduleShareExpiry(
        session: RaftAndroidWebViewShareSession,
        delayMs: Long = RaftWebViewShareIdleTimeoutMs + 1_000L
    ) {
        shareExpiryCheck?.let { shareMainHandler.removeCallbacks(it) }
        val check =
            Runnable {
                shareExpiryCheck = null
                if (!destroyed && shareSession === session) runShareIo(session, rearmExpiry = true) { session.expire() }
            }
        shareExpiryCheck = check
        shareMainHandler.postDelayed(check, delayMs)
    }

    private fun discardShareFolder(folder: java.io.File?) {
        if (folder == null) return
        runCatching { RaftWebViewShareIo.execute { runCatching { folder.deleteRecursively() } } }
    }

    /**
     * Opens the system share sheet for a received share. Its files are already in [folder], the
     * share's own `<uuid>` entry of the app's share staging folder (served by the app's
     * FileProvider, swept by age like an attachment share's). The page's Promise resolves once the
     * sheet is shown, or is rejected when it cannot be.
     */
    private fun presentShare(request: RaftWebViewShareRequest, folder: java.io.File?) {
        val context = webView.context
        val result =
            runCatching {
                val intent =
                    if (request.files.isEmpty()) {
                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, raftWebViewShareText(request.title, request.text, request.url))
                            if (request.title.isNotBlank()) putExtra(android.content.Intent.EXTRA_SUBJECT, request.title)
                        }
                    } else {
                        checkNotNull(folder) { "share folder" }
                        val uris =
                            ArrayList(
                                request.files.map { file ->
                                    val target = java.io.File(folder, file.name)
                                    check(target.isFile && target.parentFile == folder && target.length() == file.size) { "share file" }
                                    androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
                                }
                            )
                        // Receivers match on a bare `type/subtype`: no parameters, a generic type read from the name.
                        val type =
                            raftWebViewShareIntentType(
                                request.files.map { file ->
                                    build.raft.app.downloads.RaftShareFileName.resolveMimeType(file.name, file.mimeType) { extension ->
                                        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                                    }
                                }
                            )
                        (
                            if (uris.size == 1) {
                                android.content.Intent(android.content.Intent.ACTION_SEND).putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
                            } else {
                                android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE)
                                    .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                            }
                        ).apply {
                            this.type = type
                            if (request.title.isNotBlank()) putExtra(android.content.Intent.EXTRA_SUBJECT, request.title)
                            raftWebViewShareText("", request.text, request.url).takeIf { it.isNotBlank() }
                                ?.let { putExtra(android.content.Intent.EXTRA_TEXT, it) }
                            clipData =
                                android.content.ClipData.newRawUri(request.files.first().name, uris[0]).also { clip ->
                                    uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
                                }
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                val chooser = android.content.Intent.createChooser(intent, request.title.takeIf { it.isNotBlank() })
                if (context !is android.app.Activity) chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            }
        if (result.isSuccess) {
            diagnosticsInfo("page_share result=shown files=${request.files.size}")
            settleShare(request.id, null)
        } else {
            diagnosticsWarn("page_share result=failed type=${result.exceptionOrNull()?.javaClass?.simpleName}")
            discardShareFolder(folder)
            settleShare(request.id, "AbortError")
        }
    }

    private fun settleShare(id: String, errorName: String?) {
        if (destroyed) return
        webView.evaluateJavascript(raftWebViewShareSettleScript(id, errorName), null)
    }

    /** A chunk of a `blob:` / `data:` save the page is reading back ([startPageDownload]). */
    private fun handleDownloadMessage(raw: String) {
        val token = runCatching { AndroidJSONObject(raw).optString("t") }.getOrNull() ?: return
        val assembler = pendingBlobDownloads[token] ?: return
        val result = assembler.accept(raw) ?: return
        pendingBlobDownloads.remove(token)
        when (result) {
            is RaftWebViewDownload.Failed -> diagnosticsWarn("page_download result=failed reason=${result.reason}")
            is RaftWebViewDownload.Bytes -> diagnosticsInfo("page_download result=read bytes=${result.bytes.size}")
            is RaftWebViewDownload.Url -> Unit
        }
        events.onDownloadRequested?.invoke(result)
    }

    private fun installDownloadSupport(targetUrl: String) {
        removeDownloadSupport()
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            return
        }
        val originRule = raftDocumentStartScriptOriginRule(targetUrl) ?: return
        if (mainFrameOrigin != null && originRule != mainFrameOrigin) return
        runCatching {
            val session = RaftAndroidWebViewShareSession(java.io.File(webView.context.cacheDir, RaftWebViewShareStagingDirName))
            val listener =
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy
                    ) {
                        if (destroyed || !isMainFrame || sourceOrigin.toString() != originRule) return
                        val raw = message.data ?: return
                        // A share message goes to the share thread whole; this (UI) thread neither parses nor decodes it.
                        if (raftWebViewIsShareMessage(raw)) {
                            acceptShareMessage(session, raw)
                            return
                        }
                        handleDownloadMessage(raw)
                    }
                }
            WebViewCompat.addWebMessageListener(webView, RaftWebViewDownloadPortName, setOf(originRule), listener)
            downloadMessageListener = listener
            shareSession = session
            downloadNameScriptHandler =
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    RaftWebViewDownloadNameHookScript + "\n" + raftWebViewShareShimScript(),
                    setOf(originRule)
                )
        }.onFailure { Log.w(LOG_TAG, "download_support unavailable", it) }
    }

    private fun removeDownloadSupport() {
        if (downloadMessageListener != null) {
            runCatching { WebViewCompat.removeWebMessageListener(webView, RaftWebViewDownloadPortName) }
        }
        downloadMessageListener = null
        runCatching { downloadNameScriptHandler?.remove() }
        downloadNameScriptHandler = null
        pendingBlobDownloads.clear()
        // Shares still being received end here: their partial files go, their Promises are rejected.
        shareExpiryCheck?.let { shareMainHandler.removeCallbacks(it) }
        shareExpiryCheck = null
        shareSession?.let { session ->
            shareSession = null
            runShareIo(session) { session.close() }
        }
    }

    private fun removeHostMessageListener() {
        runCatching { WebViewCompat.removeWebMessageListener(webView, RaftWebViewHostPortName) }
        hostMessageListener = null
    }

    private fun installDocumentStartScript(targetUrl: String): Boolean {
        removeDocumentStartScript()
        installFirstPaintScript(targetUrl)
        val authScript = injectedJavaScript.takeIf { allowScripts && domStorageEnabled && it.isNotBlank() } ?: ""
        val bridge =
            HOST_BRIDGE_SCRIPT.takeIf {
                hostEventsEnabled && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
            }.orEmpty()
        val script = (bridge + "\n" + authScript).trim().takeIf { it.isNotBlank() } ?: return false
        val originRule = raftDocumentStartScriptOriginRule(targetUrl) ?: return false
        if (mainFrameOrigin != null && originRule != mainFrameOrigin) return false
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        return runCatching {
            documentStartScriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(originRule))
            diagnosticsInfo("document_start_script installed origin=${originRule.raftRedactedUrlForLog()} authScript=${authScript.isNotBlank()}")
            true
        }.getOrElse { error ->
            Log.w(LOG_TAG, "document_start_script unavailable fallback=bootstrap", error)
            diagnosticsWarn("document_start_script unavailable fallback=bootstrap errorType=${error::class.simpleName}")
            false
        }
    }

    private fun removeDocumentStartScript() {
        runCatching { documentStartScriptHandler?.remove() }
        documentStartScriptHandler = null
        runCatching { firstPaintScriptHandler?.remove() }
        firstPaintScriptHandler = null
    }

    /**
     * The page's own first paint is the engine's white canvas until its stylesheet and theme
     * script apply: give its root the app's canvas colour and scheme until it loaded (lowest
     * priority, removed after `load`; as iOS). Only on [targetUrl]'s origin, like the auth script.
     * Without document-start scripts the WebView's own canvas background (until the first
     * commit) is all there is.
     */
    private fun installFirstPaintScript(targetUrl: String) {
        if (!allowScripts || placeholderBackground == 0) return
        val originRule = raftDocumentStartScriptOriginRule(targetUrl) ?: return
        if (mainFrameOrigin != null && originRule != mainFrameOrigin) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        runCatching {
            firstPaintScriptHandler =
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    raftWebViewFirstPaintThemeScript(placeholderBackground, RaftAppThemeState.isDark),
                    setOf(originRule),
                )
        }.onFailure { error ->
            diagnosticsWarn("first_paint_script unavailable errorType=${error::class.simpleName}")
        }
    }

    /**
     * Feedback carries an in-memory bearer in its bootstrap: never forward page console output for
     * a fenced settings document (hostile page code could print the bearer into logcat).
     */
    private fun isRestrictedGenericMode(): Boolean =
        mainFrameOrigin != null ||
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
                "durationMs=${System.currentTimeMillis() - startedAt} url=${target.formatUrlForLog()}"
        )
        return WebResourceResponse(document.mimeType, "utf-8", ByteArrayInputStream(document.bytes))
    }

    private companion object {
        const val LOG_TAG = RAFT_WEBVIEW_DIAGNOSTICS_TAG
        const val LOG_MESSAGE_LIMIT = 500
        const val PERSISTED_CONSOLE_ERROR_LIMIT = 5
        const val PERSISTED_CONSOLE_MESSAGE_CHARS = 160
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

/**
 * The origin lock ([RaftWebViewConfig.mainFrameOrigin]): with no lock every target passes; with
 * one, only a document on exactly that origin (scheme, host, port) does.
 */
internal fun raftMainFrameOriginAllows(lockedOrigin: String?, targetUrl: String): Boolean {
    val locked = lockedOrigin ?: return true
    return raftDocumentStartScriptOriginRule(targetUrl) == locked
}

internal fun raftDocumentStartScriptOriginRule(targetUrl: String): String? =
    runCatching {
        val uri = URI(targetUrl)
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val defaultPort = if (scheme == "https") 443 else 80
        val port = uri.port.takeIf { it >= 0 && it != defaultPort }?.let { ":$it" }.orEmpty()
        "$scheme://$host$port"
    }.getOrNull()

internal fun raftInjectedJavaScriptBootstrapBaseUrl(targetUrl: String): String? =
    raftDocumentStartScriptOriginRule(targetUrl)?.let { origin -> "$origin$RAFT_WEBVIEW_BOOTSTRAP_PATH" }

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

    /** The page a bootstrap load is on its way to, until it started (then the WebView's own URL is it). */
    val pendingTargetUrl: String? get() = targetUrl?.takeIf { !targetStarted }

    fun onMainFrameStarted(url: String) {
        if (url == targetUrl) targetStarted = true
    }

    fun mayUncover(url: String): Boolean {
        val target = targetUrl ?: return true
        return targetStarted && url == target
    }
}

internal const val RAFT_WEBVIEW_DIAGNOSTICS_TAG = "SlockWebView"

/** The next progress milestone (25 / 50 / 75 / 100) [progress] reached past [logged], or null. */
internal fun raftWebViewProgressMilestone(progress: Int, logged: Int): Int? =
    listOf(25, 50, 75, 100).lastOrNull { progress >= it }?.takeIf { it > logged }

internal fun String.raftRedactedUrlForLog(): String {
    val uri = runCatching { Uri.parse(this) }.getOrNull() ?: return "<invalid>"
    val scheme = uri.scheme?.lowercase()?.takeIf { it.matches(Regex("[a-z][a-z0-9+.-]*")) } ?: return "<invalid>"
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return "$scheme:<redacted>"
    val port = uri.port.takeIf { it >= 0 }?.let { value -> ":$value" }.orEmpty()
    val path = uri.encodedPath.orEmpty()
    return "$scheme://${host.raftSanitizedLogValue()}$port${path.raftSanitizedLogValue()}".take(160)
}

private fun String.raftSanitizedLogValue(limit: Int = 160): String =
    replace('\n', ' ').replace('\r', ' ').take(limit)

internal actual val raftWebViewNeedsHostBottomInsets: Boolean = true

internal actual val raftWebViewDesktopModeViaViewportMeta: Boolean = true

/**
 * One thread for every page share ([raftWebViewShareShimScript]): parsing, base64 decoding and file
 * writes stay off the UI thread and run in the order the messages were posted. It ends when idle.
 */
private const val RaftWebViewShareExpiryRecheckMs: Long = 10_000L

private val RaftWebViewShareIo: java.util.concurrent.Executor by lazy {
    java.util.concurrent.ThreadPoolExecutor(
        0,
        1,
        30L,
        java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>()
    )
}

/** A share signal with, for a share that is ready, the folder its files are in. */
private class RaftAndroidStagedShareSignal(val signal: RaftWebViewShareSignal, val folder: java.io.File?)

/**
 * The shares one page is sending ([RaftWebViewShareReceiver]) and where their bytes go: each share
 * in its own `<uuid>` entry right under the app's share staging folder (`cache/slock-share-files`,
 * the attachment share's; see `AndroidAttachmentShareStaging`), each chunk appended to its file as
 * it arrives. Entries follow that folder's rules (a day, the newest sixteen), applied here before a
 * new one is made and by the attachment share's own sweep; a failed or abandoned share's entry is
 * removed at once. Used from [RaftWebViewShareIo] only.
 */
private class RaftAndroidWebViewShareSession(private val shareRoot: java.io.File) : RaftWebViewShareSink {
    private val folders = HashMap<String, java.io.File>()
    private val fileNames = HashMap<String, List<String>>()
    private val receiver = RaftWebViewShareReceiver(sink = this, nowMillis = { android.os.SystemClock.elapsedRealtime() })

    private var closed = false

    /** Nothing is taken once the session is closed: a message still on its way then starts no share. */
    @Synchronized
    fun accept(raw: String): List<RaftAndroidStagedShareSignal>? = if (closed) emptyList() else receiver.accept(raw)?.map(::staged)

    @Synchronized
    fun hasPending(): Boolean = receiver.pendingCount > 0

    /** The page's port is gone: every share still being received is given up, and no more are taken. */
    @Synchronized
    fun close(): List<RaftAndroidStagedShareSignal> {
        closed = true
        return receiver.abortAll("closed").map(::staged)
    }

    @Synchronized
    fun expire(): List<RaftAndroidStagedShareSignal> = receiver.expire().map(::staged)

    @Synchronized
    fun abortAll(reason: String): List<RaftAndroidStagedShareSignal> = receiver.abortAll(reason).map(::staged)

    private fun staged(signal: RaftWebViewShareSignal): RaftAndroidStagedShareSignal {
        val folder =
            if (signal is RaftWebViewShareSignal.Ready) {
                fileNames.remove(signal.request.id)
                // The entry's age counts from when the share was complete.
                folders.remove(signal.request.id)?.also { it.setLastModified(System.currentTimeMillis()) }
            } else {
                null
            }
        return RaftAndroidStagedShareSignal(signal, folder)
    }

    override fun open(id: String, fileNames: List<String>) {
        discard(id)
        sweep()
        val folder = java.io.File(shareRoot, java.util.UUID.randomUUID().toString())
        check(folder.mkdirs()) { "share folder" }
        folders[id] = folder
        this.fileNames[id] = fileNames
        fileNames.forEach { name ->
            val target = java.io.File(folder, name)
            check(target.parentFile == folder && target.createNewFile()) { "share file" }
        }
    }

    override fun append(id: String, fileIndex: Int, bytes: ByteArray) {
        val folder = checkNotNull(folders[id]) { "share folder" }
        val target = java.io.File(folder, checkNotNull(fileNames[id]) { "share files" }[fileIndex])
        java.io.FileOutputStream(target, true).use { it.write(bytes) }
    }

    override fun discard(id: String) {
        fileNames.remove(id)
        folders.remove(id)?.deleteRecursively()
    }

    private fun sweep() {
        val entries = shareRoot.listFiles()?.filter { it.isDirectory } ?: return
        val stale = raftWebViewShareStaleStagingEntries(entries.map { it.name to it.lastModified() }, System.currentTimeMillis()).toSet()
        entries.filter { it.name in stale }.forEach { runCatching { it.deleteRecursively() } }
    }
}
