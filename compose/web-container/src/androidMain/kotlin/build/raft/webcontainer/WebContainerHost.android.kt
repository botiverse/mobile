package build.raft.webcontainer

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import build.raft.webcontainer.jsb.JsbBootstrapScripts
import build.raft.webcontainer.jsb.JsbDispatcher
import build.raft.webcontainer.jsb.JsbErrorCode
import build.raft.webcontainer.jsb.JsbRegistry
import build.raft.webcontainer.jsb.JsbResult
import build.raft.webcontainer.jsb.JsbWireCodec
import build.raft.webcontainer.pool.WebContainerKeepAlivePool
import build.raft.webcontainer.pool.WebContainerPoolKey
import build.raft.webcontainer.pool.WebContainerTelemetryEvent
import build.raft.webcontainer.pool.WebContainerTelemetrySink
import build.raft.webcontainer.pool.WebIsolationClearResult
import build.raft.webcontainer.pool.WebIsolationScopeManager
import build.raft.webcontainer.pool.WebMemoryTrimLevel
import build.raft.webcontainer.pool.WebPooledContainer
import build.raft.webcontainer.resilience.WebContainerWatchdog
import build.raft.webcontainer.resilience.WebContainerWatchdogAction
import build.raft.webcontainer.resilience.WebContainerWatchdogEvent
import build.raft.webcontainer.resilience.WebNavigationStateRelay
import build.raft.webcontainer.resource.WebResourceInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.ByteArrayInputStream

/** Process-wide Android keep-alive pool and isolation-scope lifecycle coordinator. */
object WebContainerAndroidKeepAlive {
    private val pool = WebContainerKeepAlivePool<WebContainerAndroidView>()
    internal val scopeManager = WebIsolationScopeManager()

    internal fun take(
        key: WebContainerPoolKey,
        hostContext: Context,
        events: WebContainerEvents,
        jsbRegistry: JsbRegistry,
        resourceInterceptor: WebResourceInterceptor?,
        telemetrySink: WebContainerTelemetrySink?,
    ): WebContainerAndroidView? {
        val view = pool.take(key) ?: return null
        view.attachFromKeepAlive(
            hostContext = hostContext,
            nextEvents = events,
            nextRegistry = jsbRegistry,
            nextInterceptor = resourceInterceptor,
            nextTelemetry = telemetrySink,
        )
        return view
    }

    internal fun park(view: WebContainerAndroidView): Boolean {
        val key = view.currentPoolKey() ?: return false
        if (scopeManager.hasPendingDeletion(view.isolationScope)) {
            return false
        }
        view.detachForKeepAlive()
        return pool.park(key, view)
    }

    internal fun dropIfSame(view: WebContainerAndroidView): Boolean = pool.dropIfSame(view)

    /** Evicts pooled views under OS memory pressure (`onTrimMemory`). */
    fun onMemoryPressure(level: WebMemoryTrimLevel): Int = pool.onMemoryPressure(level)

    /** Destroys all pooled views immediately. */
    fun clearAll(): Int = pool.clearAll()

    internal fun clearIsolationScope(
        scope: WebIsolationScope,
        deletePlatformStore: (WebIsolationScope) -> Boolean,
    ): WebIsolationClearResult =
        scopeManager.clearIsolationScope(
            scope = scope,
            evictPooled = { pool.evictScope(it) },
            deletePlatformStore = deletePlatformStore,
        )
}

@Composable
internal actual fun WebContainerPlatformHost(
    config: WebContainerConfig,
    events: WebContainerEvents,
    controller: WebContainerController,
    jsbRegistry: JsbRegistry,
    resourceInterceptor: WebResourceInterceptor?,
    telemetrySink: WebContainerTelemetrySink?,
    modifier: Modifier,
) {
    AndroidView(
        factory = { context ->
            val poolKey =
                config.keepAliveKey
                    ?.takeIf { it.isNotBlank() && config.isolationScope !is WebIsolationScope.Ephemeral }
                    ?.let { key ->
                        WebContainerPoolKey(
                            keepAliveKey = key,
                            isolationScope = config.isolationScope,
                            isDarkTheme = config.isDarkTheme,
                        )
                    }
            val reused =
                poolKey?.let { key ->
                    WebContainerAndroidKeepAlive.take(
                        key = key,
                        hostContext = context,
                        events = events,
                        jsbRegistry = jsbRegistry,
                        resourceInterceptor = resourceInterceptor,
                        telemetrySink = telemetrySink,
                    )
                }
            reused ?: WebContainerAndroidView(
                context = MutableContextWrapper(context),
                initialConfig = config,
                events = events,
                jsbRegistry = jsbRegistry,
                resourceInterceptor = resourceInterceptor,
                telemetrySink = telemetrySink,
            )
        },
        modifier = modifier,
        update = { view ->
            view.events = events
            view.telemetrySink = telemetrySink
            view.updateRuntimeHooks(jsbRegistry, resourceInterceptor)
            view.applyConfig(config)
            view.bindController(controller)
        },
        onRelease = { view ->
            if (!WebContainerAndroidKeepAlive.park(view)) {
                view.destroy()
            }
        },
    )
}

private const val ABOUT_BLANK_URL = "about:blank"
private const val WATCHDOG_TICK_MS = 500L
private val SubframeSecFetchDestinations = setOf("iframe", "frame", "fencedframe")
private val SubresourceSafeBuiltInSchemes = setOf("data", "blob", "about")

/**
 * Android WebView container host with:
 * - Zero `@JavascriptInterface` (JSB uses `WebViewCompat.addWebMessageListener` + `JavaScriptReplyProxy`,
 *   rejecting binary `ArrayBuffer` frames cleanly without throwing).
 * - Chromium `ProfileStore` isolation (unique per-instance scrubbable profiles for `Ephemeral`,
 *   fail-closed for `Named` without `MULTI_PROFILE`, strict destroy-before-unregister ordering).
 * - Full navigation fence across `shouldOverrideUrlLoading` (API 21+ & 24+), `onPageStarted`, and
 *   `shouldInterceptRequest` (main frame + subframe documents).
 * - `WebContainerWatchdog` stall/blank detection and rolling-window renderer-gone circuit breaker.
 */
@SuppressLint("ViewConstructor")
internal class WebContainerAndroidView(
    context: Context,
    initialConfig: WebContainerConfig,
    var events: WebContainerEvents,
    private var jsbRegistry: JsbRegistry,
    @Volatile private var resourceInterceptor: WebResourceInterceptor?,
    @Volatile var telemetrySink: WebContainerTelemetrySink?,
) : FrameLayout(context), WebPooledContainer {
    private var webView: WebView = WebView(context)
    @Volatile private var destroyed: Boolean = false
    private var parkedForKeepAlive: Boolean = false
    private var registeredActiveScope: WebIsolationScope? = null
    private var activeEphemeralProfileName: String? = null

    @Volatile private var config: WebContainerConfig = initialConfig
    override var isolationScope: WebIsolationScope = initialConfig.isolationScope
        private set
    override var isDarkTheme: Boolean = initialConfig.isDarkTheme
        private set

    private var isolatedProfileUnavailable: Boolean = false
    private var configured: Boolean = false
    private var loadedSrc: String = ""
    private var currentUrl: String = ""
    private var currentTitle: String = ""
    private var currentProgress: Int = 0
    private var currentLoading: Boolean = false
    private var loadGeneration: Int = 0
    private var mainFrameLoadFailed: Boolean = false
    private var lastSuccessfulUrl: String = ""
    private var pagePainted: Boolean = false

    private val chainState = WebNavigationChainState()
    private val navigationStateRelay = WebNavigationStateRelay()
    private val watchdog = WebContainerWatchdog()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var jsbDispatcher: JsbDispatcher? = null
    private var activeJsbPortName: String? = null
    private val documentStartHandlers = mutableListOf<ScriptHandler>()
    private var boundController: WebContainerController? = null

    private val watchdogTickRunnable =
        object : Runnable {
            override fun run() {
                if (destroyed || parkedForKeepAlive) return
                val action = watchdog.onEvent(WebContainerWatchdogEvent.Tick, webContainerMonotonicMillis())
                handleWatchdogAction(action)
                if (!destroyed && !parkedForKeepAlive) {
                    mainHandler.postDelayed(this, WATCHDOG_TICK_MS)
                }
            }
        }

    init {
        registerActiveScope(isolationScope)
        bindInitialProfileToWebView(webView, isolationScope)
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        configureWebView()
        applyConfig(initialConfig)
        mainHandler.postDelayed(watchdogTickRunnable, WATCHDOG_TICK_MS)
    }

    override fun isHealthy(): Boolean =
        !destroyed && !mainFrameLoadFailed && !isolatedProfileUnavailable

    internal fun currentPoolKey(): WebContainerPoolKey? {
        val key = config.keepAliveKey?.takeIf { it.isNotBlank() } ?: return null
        if (isolationScope is WebIsolationScope.Ephemeral) return null
        return WebContainerPoolKey(
            keepAliveKey = key,
            isolationScope = isolationScope,
            isDarkTheme = isDarkTheme,
        )
    }

    fun updateRuntimeHooks(
        nextRegistry: JsbRegistry,
        nextInterceptor: WebResourceInterceptor?,
    ) {
        resourceInterceptor = nextInterceptor
        if (jsbRegistry !== nextRegistry) {
            jsbRegistry = nextRegistry
            rebindJsbBridge()
        }
    }

    fun bindController(controller: WebContainerController) {
        if (boundController === controller) return
        boundController?.unbindDispatcher(this)
        boundController = controller
        controller.bindDispatcher(this) { command -> executeCommand(command) }
    }

    fun applyConfig(next: WebContainerConfig) {
        if (destroyed) return
        val previous = config
        val scopeChanged = configured && previous.isolationScope != next.isolationScope
        val scriptsChanged =
            !configured ||
                previous.allowScripts != next.allowScripts ||
                previous.jsbPolicy != next.jsbPolicy ||
                previous.documentStartScripts != next.documentStartScripts ||
                previous.offlineBundle != next.offlineBundle ||
                previous.backgroundArgb != next.backgroundArgb ||
                previous.isDarkTheme != next.isDarkTheme ||
                previous.desktopViewportMode != next.desktopViewportMode
        val settingsChanged =
            !configured ||
                previous.allowScripts != next.allowScripts ||
                previous.domStorageEnabled != next.domStorageEnabled ||
                previous.cacheEnabled != next.cacheEnabled ||
                previous.allowPinchZoom != next.allowPinchZoom ||
                previous.desktopViewportMode != next.desktopViewportMode
        val urlChanged = !configured || previous.url.trim() != next.url.trim()

        config = next
        isDarkTheme = next.isDarkTheme
        configured = true
        chainState.configureBudget(next.navigationPolicy.requestHeaderRule?.maxSameOriginReissues ?: 0)

        if (scopeChanged) {
            // Destroy the old WebView FIRST before unregistering the previous isolation scope so
            // any deferred ProfileStore.deleteProfile(oldScope) sees zero live bound WebViews.
            recreateWebView(
                restoreUrl = null,
                loadAfterRecreate = true,
                nextIsolationScope = next.isolationScope,
            )
            return
        }

        if (settingsChanged) {
            applyWebSettings()
        }
        if (scriptsChanged) {
            rebindDocumentStartScripts()
            rebindJsbBridge()
        }
        if (!pagePainted) {
            webView.setBackgroundColor(effectivePlaceholderColor(next.backgroundArgb, painted = false))
        }
        if (urlChanged) {
            loadConfiguredUrl(overrideUrl = null)
        }
    }

    internal fun detachForKeepAlive() {
        boundController?.unbindDispatcher(this)
        boundController = null
        events = WebContainerEvents()
        parkedForKeepAlive = true
        mainHandler.removeCallbacks(watchdogTickRunnable)
        jsbDispatcher?.cancelInFlight("parked_for_keep_alive")
        (parent as? ViewGroup)?.removeView(this)
        (context as? MutableContextWrapper)?.baseContext = context.applicationContext
        unregisterActiveScope()
    }

    internal fun attachFromKeepAlive(
        hostContext: Context,
        nextEvents: WebContainerEvents,
        nextRegistry: JsbRegistry,
        nextInterceptor: WebResourceInterceptor?,
        nextTelemetry: WebContainerTelemetrySink?,
    ) {
        (context as? MutableContextWrapper)?.baseContext = hostContext
        parkedForKeepAlive = false
        events = nextEvents
        telemetrySink = nextTelemetry
        updateRuntimeHooks(nextRegistry, nextInterceptor)
        registerActiveScope(isolationScope)
        navigationStateRelay.reset()
        notifyNavigationState(force = true)
        mainHandler.removeCallbacks(watchdogTickRunnable)
        mainHandler.postDelayed(watchdogTickRunnable, WATCHDOG_TICK_MS)
        if (!mainFrameLoadFailed && lastSuccessfulUrl.isNotBlank()) {
            events.onLoadSuccess()
        }
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        parkedForKeepAlive = false
        mainHandler.removeCallbacks(watchdogTickRunnable)
        boundController?.unbindDispatcher(this)
        boundController = null
        jsbDispatcher?.cancelInFlight("container_destroyed")
        removeJsbBridge()
        clearDocumentStartScripts()
        hostScope.cancel()
        runCatching {
            webView.stopLoading()
            webView.loadUrl(ABOUT_BLANK_URL)
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            removeView(webView)
            webView.destroy()
        }
        scrubActiveEphemeralProfile()
        unregisterActiveScope()
    }

    private fun bindInitialProfileToWebView(
        target: WebView,
        scope: WebIsolationScope,
    ) {
        val ephName =
            if (scope is WebIsolationScope.Ephemeral && AndroidWebContainerProfileStore.supportsMultiProfile()) {
                AndroidWebContainerProfileStore.allocateEphemeralProfileName()
            } else {
                null
            }
        val ok = AndroidWebContainerProfileStore.applyProfile(target, scope, ephemeralProfileName = ephName)
        activeEphemeralProfileName = if (ok) ephName else null
        isolatedProfileUnavailable = !ok
    }

    private fun scrubActiveEphemeralProfile() {
        val eph = activeEphemeralProfileName ?: return
        activeEphemeralProfileName = null
        AndroidWebContainerProfileStore.deleteEphemeralProfile(eph)
    }

    private fun registerActiveScope(scope: WebIsolationScope) {
        if (registeredActiveScope == scope) return
        unregisterActiveScope()
        registeredActiveScope = scope
        WebContainerAndroidKeepAlive.scopeManager.registerActive(scope)
    }

    private fun unregisterActiveScope() {
        val current = registeredActiveScope ?: return
        registeredActiveScope = null
        WebContainerAndroidKeepAlive.scopeManager.unregisterActive(current)
    }

    private fun recreateWebView(
        restoreUrl: String?,
        loadAfterRecreate: Boolean = true,
        nextIsolationScope: WebIsolationScope = isolationScope,
    ) {
        removeJsbBridge()
        clearDocumentStartScripts()
        runCatching {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            removeView(webView)
            webView.destroy()
        }
        scrubActiveEphemeralProfile()
        if (nextIsolationScope != isolationScope) {
            unregisterActiveScope()
            isolationScope = nextIsolationScope
            registerActiveScope(isolationScope)
        }
        loadedSrc = ""
        lastSuccessfulUrl = ""
        mainFrameLoadFailed = false
        currentTitle = ""
        currentProgress = 0
        currentLoading = false
        pagePainted = false
        chainState.reset(clearCommitted = true)
        navigationStateRelay.reset()

        webView = WebView(context)
        bindInitialProfileToWebView(webView, isolationScope)
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        configureWebView()
        applyWebSettings()
        rebindDocumentStartScripts()
        rebindJsbBridge()
        if (loadAfterRecreate) {
            loadConfiguredUrl(overrideUrl = restoreUrl)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applyWebSettings() {
        webView.settings.apply {
            javaScriptEnabled = config.allowScripts
            domStorageEnabled = config.domStorageEnabled
            useWideViewPort = config.desktopViewportMode
            loadWithOverviewMode = config.desktopViewportMode
            setSupportZoom(true)
            builtInZoomControls = config.desktopViewportMode || config.allowPinchZoom
            displayZoomControls = false
            @Suppress("DEPRECATION")
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            cacheMode = if (config.cacheEnabled) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_NO_CACHE
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
    }

    private fun configureWebView() {
        applyWebSettings()
        webView.setBackgroundColor(effectivePlaceholderColor(config.backgroundArgb, painted = false))
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
                    resultMsg: android.os.Message?,
                ): Boolean {
                    if (destroyed) return false
                    val hit = view?.hitTestResult
                    val link = hit?.extra?.takeIf { hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE }.orEmpty()
                    if (!config.navigationPolicy.allowNewWindows) {
                        notifyNavigationBlocked(link, "new_window_blocked")
                        return false
                    }
                    return false
                }
            }

        webView.webViewClient =
            object : WebViewClient() {
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    if (destroyed) return true
                    val didCrash =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            detail?.didCrash() == true
                        } else {
                            false
                        }
                    jsbDispatcher?.cancelInFlight("render_process_gone")
                    events.onContentProcessGone(didCrash)
                    val action =
                        watchdog.onEvent(
                            WebContainerWatchdogEvent.RenderProcessGone(didCrash = didCrash),
                            webContainerMonotonicMillis(),
                        )
                    val shouldRecover = action is WebContainerWatchdogAction.TriggerRecoveryReload
                    val attempt =
                        (action as? WebContainerWatchdogAction.TriggerRecoveryReload)?.recoveryAttempt
                            ?: watchdog.recentRecoveryCount
                    telemetrySink?.onEvent(
                        WebContainerTelemetryEvent.RenderProcessGone(
                            surfaceId = config.surfaceId,
                            didCrash = didCrash,
                            recovered = shouldRecover,
                            attempt = attempt,
                        ),
                    )
                    if (parkedForKeepAlive) {
                        WebContainerAndroidKeepAlive.dropIfSame(this@WebContainerAndroidView)
                        return true
                    }
                    val restoreUrl =
                        if (shouldRecover && loadedSrc.isNotBlank()) {
                            sanitizeRendererRestoreUrl(
                                initialUrl = config.url,
                                candidateUrl = currentUrl,
                                policy = config.navigationPolicy,
                            )
                        } else {
                            null
                        }
                    recreateWebView(
                        restoreUrl = restoreUrl,
                        loadAfterRecreate = shouldRecover,
                    )
                    if (!shouldRecover) {
                        mainFrameLoadFailed = true
                        handleWatchdogAction(action)
                    }
                    notifyNavigationState(force = true)
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    if (destroyed) return
                    super.onPageStarted(view, url, favicon)
                    val target = url.orEmpty()
                    if (target.equals(ABOUT_BLANK_URL, ignoreCase = true) && loadedSrc.isBlank()) {
                        currentLoading = false
                        return
                    }
                    jsbDispatcher?.cancelInFlight("main_frame_navigation")
                    if (target.isNotEmpty() && !target.equals(ABOUT_BLANK_URL, ignoreCase = true)) {
                        val isConfiguredInitialDocument =
                            loadedSrc.isNotBlank() && isSameDocumentIgnoringFragment(loadedSrc, target)
                        val fence =
                            evaluateMainFrameNavigation(
                                targetUrl = target,
                                initialUrl = config.url,
                                policy = config.navigationPolicy,
                                method = "GET",
                                requestCarriesScopedHeader = chainState.carriesScopedHeader,
                                chainState = null,
                                catchesMainFrameNavigation = events.catchesMainFrameNavigation,
                                routesMainFrameNavigation =
                                    events.routesMainFrameNavigation.takeUnless { isConfiguredInitialDocument },
                            )
                        when (fence) {
                            is WebNavigationDecision.Block -> {
                                view?.stopLoading()
                                currentLoading = false
                                notifyNavigationBlocked(fence.url, fence.reason)
                                notifyNavigationState()
                                return
                            }
                            is WebNavigationDecision.InterceptedByHost -> {
                                view?.stopLoading()
                                currentLoading = false
                                notifyNavigationState()
                                return
                            }
                            else -> Unit
                        }
                    }
                    currentUrl = target
                    currentTitle = ""
                    currentProgress = 0
                    currentLoading = true
                    mainFrameLoadFailed = false
                    lastSuccessfulUrl = ""
                    loadGeneration += 1
                    watchdog.onEvent(
                        WebContainerWatchdogEvent.LoadStarted(loadGeneration),
                        webContainerMonotonicMillis(),
                    )
                    events.onUrlChange(target)
                    notifyNavigationState()
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    if (destroyed) return
                    super.onPageCommitVisible(view, url)
                    val target = url.orEmpty()
                    if (target.equals(ABOUT_BLANK_URL, ignoreCase = true) && loadedSrc.isBlank()) {
                        currentLoading = false
                        return
                    }
                    currentUrl = target.ifBlank { currentUrl }
                    currentTitle = view?.title.orEmpty().ifBlank { currentTitle }
                    if (!mainFrameLoadFailed) {
                        markPagePainted()
                        chainState.markCommitted()
                        watchdog.onEvent(
                            WebContainerWatchdogEvent.PageCommitted(loadGeneration),
                            webContainerMonotonicMillis(),
                        )
                        events.onLoadCommit()
                    }
                    events.onUrlChange(currentUrl)
                    notifyNavigationState()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (destroyed) return
                    super.onPageFinished(view, url)
                    val target = url.orEmpty()
                    if (target.equals(ABOUT_BLANK_URL, ignoreCase = true) && loadedSrc.isBlank()) {
                        currentLoading = false
                        return
                    }
                    currentUrl = target.ifBlank { currentUrl }
                    currentTitle = view?.title.orEmpty().ifBlank { currentTitle }
                    currentProgress = 100
                    currentLoading = false
                    if (!mainFrameLoadFailed) {
                        markPagePainted()
                        chainState.markCommitted()
                        val action =
                            watchdog.onEvent(
                                WebContainerWatchdogEvent.PageFinished(loadGeneration),
                                webContainerMonotonicMillis(),
                            )
                        handleWatchdogAction(action)
                        if (lastSuccessfulUrl != currentUrl) {
                            lastSuccessfulUrl = currentUrl
                            events.onLoadSuccess()
                        }
                    }
                    events.onUrlChange(currentUrl)
                    notifyNavigationState()
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    if (destroyed) return true
                    val target = url.orEmpty()
                    return evaluateAndApplyOverride(
                        view = view,
                        target = target,
                        method = "GET",
                        carriesHeader = chainState.carriesScopedHeader,
                        isUserGesture = false,
                        isRedirect = false,
                    )
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    if (destroyed) return true
                    val target = request?.url?.toString().orEmpty()
                    if (request?.isForMainFrame != true) {
                        return !isAllowedSubframeUrl(target, config.navigationPolicy)
                    }
                    val headerRule = config.navigationPolicy.requestHeaderRule
                    val carriesHeader =
                        headerRule != null &&
                            request.requestHeaders?.entries?.any { (k, v) ->
                                k.equals(headerRule.headerName, ignoreCase = true) && v.isNotBlank()
                            } == true
                    val isRedirect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) request.isRedirect else false
                    return evaluateAndApplyOverride(
                        view = view,
                        target = target,
                        method = request.method ?: "GET",
                        carriesHeader = carriesHeader,
                        isUserGesture = request.hasGesture(),
                        isRedirect = isRedirect,
                    )
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    if (destroyed || request == null) return null
                    val cfg = config
                    val url = request.url?.toString().orEmpty()
                    val headers = request.requestHeaders ?: emptyMap()
                    if (request.isForMainFrame) {
                        if (url.equals(ABOUT_BLANK_URL, ignoreCase = true)) {
                            return super.shouldInterceptRequest(view, request)
                        }
                        val decision =
                            evaluateMainFrameNavigation(
                                targetUrl = url,
                                initialUrl = cfg.url,
                                policy = cfg.navigationPolicy,
                                method = request.method ?: "GET",
                                requestCarriesScopedHeader = false,
                                chainState = null,
                            )
                        if (decision is WebNavigationDecision.Block) {
                            mainHandler.post {
                                if (!destroyed) notifyNavigationBlocked(decision.url, decision.reason)
                            }
                            return emptyBlockedResponse()
                        }
                    } else {
                        val dest =
                            headers.entries
                                .firstOrNull { it.key.equals("Sec-Fetch-Dest", ignoreCase = true) }
                                ?.value
                                ?.lowercase()
                        if (dest != null && dest in SubframeSecFetchDestinations) {
                            if (!isAllowedSubframeUrl(url, cfg.navigationPolicy)) {
                                return emptyBlockedResponse()
                            }
                        } else {
                            val scheme = extractUrlScheme(url)
                            val allowedSchemes = cfg.navigationPolicy.normalizedAllowedSchemes
                            if (
                                scheme == null ||
                                (allowedSchemes.isNotEmpty() && scheme !in allowedSchemes && scheme !in SubresourceSafeBuiltInSchemes)
                            ) {
                                return emptyBlockedResponse()
                            }
                        }
                    }
                    val interceptor = resourceInterceptor ?: return super.shouldInterceptRequest(view, request)
                    val req =
                        build.raft.webcontainer.resource.WebResourceRequest(
                            url = url,
                            method = request.method ?: "GET",
                            isMainFrame = request.isForMainFrame,
                            headers = headers,
                        )
                    val intercepted =
                        runCatching { interceptor.intercept(req) }.getOrNull()
                            ?: return super.shouldInterceptRequest(view, request)
                    return WebResourceResponse(
                        intercepted.mimeType,
                        intercepted.encoding,
                        intercepted.statusCode,
                        intercepted.reasonPhrase,
                        intercepted.headers,
                        ByteArrayInputStream(intercepted.body),
                    )
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (destroyed) return
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        mainFrameLoadFailed = true
                        currentLoading = false
                        val reason =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                error?.description?.toString()?.takeIf { it.isNotBlank() } ?: "webview_load_failed"
                            } else {
                                "webview_load_failed"
                            }
                        watchdog.onEvent(
                            WebContainerWatchdogEvent.LoadFailed(loadGeneration, reason),
                            webContainerMonotonicMillis(),
                        )
                        events.onLoadFailure(reason)
                        notifyNavigationState()
                        if (parkedForKeepAlive) {
                            WebContainerAndroidKeepAlive.dropIfSame(this@WebContainerAndroidView)
                        }
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?,
                ) {
                    handler?.cancel()
                    if (destroyed) return
                    mainFrameLoadFailed = true
                    currentLoading = false
                    val reason = "ssl_error_${error?.primaryError ?: -1}"
                    watchdog.onEvent(
                        WebContainerWatchdogEvent.LoadFailed(loadGeneration, reason),
                        webContainerMonotonicMillis(),
                    )
                    events.onLoadFailure(reason)
                    notifyNavigationState()
                    if (parkedForKeepAlive) {
                        WebContainerAndroidKeepAlive.dropIfSame(this@WebContainerAndroidView)
                    }
                }
            }
    }

    private fun evaluateAndApplyOverride(
        view: WebView?,
        target: String,
        method: String,
        carriesHeader: Boolean,
        isUserGesture: Boolean,
        isRedirect: Boolean,
    ): Boolean {
        if (!isRedirect) {
            val budget = config.navigationPolicy.requestHeaderRule?.maxSameOriginReissues ?: 0
            chainState.startChain(
                carriesHeader = carriesHeader,
                reissueBudget = budget,
                starter = if (isUserGesture) WebNavigationStarter.Link else WebNavigationStarter.Page,
            )
        }
        val decision =
            evaluateMainFrameNavigation(
                targetUrl = target,
                initialUrl = config.url,
                policy = config.navigationPolicy,
                method = method,
                requestCarriesScopedHeader = carriesHeader,
                chainState = chainState,
                isUserGesture = isUserGesture,
                isRedirect = isRedirect,
                catchesMainFrameNavigation = events.catchesMainFrameNavigation,
                routesMainFrameNavigation = events.routesMainFrameNavigation,
            )
        return when (decision) {
            WebNavigationDecision.Allow -> false
            is WebNavigationDecision.InterceptedByHost -> true
            is WebNavigationDecision.Block -> {
                notifyNavigationBlocked(decision.url, decision.reason)
                true
            }
            is WebNavigationDecision.ReissueWithHeader -> {
                view?.loadUrl(decision.url, mapOf(decision.headerName to decision.headerValue))
                true
            }
            is WebNavigationDecision.ReissueWithoutHeader -> {
                view?.loadUrl(decision.url)
                true
            }
        }
    }

    private fun emptyBlockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun rebindDocumentStartScripts() {
        clearDocumentStartScripts()
        if (!config.allowScripts || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return
        }
        val defaultOriginSet =
            buildSet {
                parseWebContainerOrigin(config.url)?.let(::add)
                config.navigationPolicy.lockedMainFrameOrigin?.let(::add)
            }
        val defaultOriginStrings = defaultOriginSet.mapTo(LinkedHashSet()) { it.asOriginString() }

        if (config.backgroundArgb != 0) {
            val rules = defaultOriginStrings.ifEmpty { setOf("*") }
            registerDocumentStartScript(
                JsbBootstrapScripts.buildFirstPaintThemeScript(config.backgroundArgb, config.isDarkTheme),
                rules,
            )
        }
        if (config.desktopViewportMode) {
            val rules = defaultOriginStrings.ifEmpty { setOf("*") }
            registerDocumentStartScript(JsbBootstrapScripts.DesktopViewportMetaScript, rules)
        }
        config.offlineBundle?.let { bundle ->
            val rules = defaultOriginStrings.ifEmpty { setOf("*") }
            registerDocumentStartScript(
                JsbBootstrapScripts.buildOfflineBundleScript(bundle, allowedOrigins = defaultOriginSet),
                rules,
            )
        }
        if (config.jsbPolicy.isEffectivelyEnabled) {
            val originRules = config.jsbPolicy.allowedOrigins.mapTo(LinkedHashSet()) { it.asOriginString() }
            registerDocumentStartScript(
                JsbBootstrapScripts.buildPageBridgeScript(config.jsbPolicy.portName, config.jsbPolicy.allowedOrigins),
                originRules,
            )
        }
        for (script in config.documentStartScripts) {
            val originRules =
                if (script.allowedOrigins.isNotEmpty()) {
                    script.allowedOrigins.mapTo(LinkedHashSet()) { it.asOriginString() }
                } else {
                    defaultOriginStrings
                }
            if (originRules.isNotEmpty() && script.source.isNotBlank()) {
                // addDocumentStartJavaScript also injects into same-origin subframes; wrap with
                // mainFrameOnly check when requested so top-frame-only scripts never run in iframes.
                val wrapped =
                    JsbBootstrapScripts.wrapDocumentStartScript(
                        source = script.source,
                        allowedOrigins = emptySet(),
                        mainFrameOnly = script.mainFrameOnly,
                    )
                registerDocumentStartScript(wrapped, originRules)
            }
        }
    }

    private fun registerDocumentStartScript(
        source: String,
        allowedOriginRules: Set<String>,
    ) {
        if (source.isBlank()) return
        runCatching {
            val handler = WebViewCompat.addDocumentStartJavaScript(webView, source, allowedOriginRules)
            documentStartHandlers += handler
        }
    }

    private fun clearDocumentStartScripts() {
        for (handler in documentStartHandlers) {
            runCatching { handler.remove() }
        }
        documentStartHandlers.clear()
    }

    private fun rebindJsbBridge() {
        removeJsbBridge()
        val policy = config.jsbPolicy
        if (!config.allowScripts || !policy.isEffectivelyEnabled) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return

        val dispatcher =
            JsbDispatcher(
                policy = policy,
                registry = jsbRegistry,
                scope = hostScope,
                surfaceId = config.surfaceId,
                isolationScope = isolationScope,
                telemetrySinkProvider = { telemetrySink },
            )
        val allowedOriginRules = policy.allowedOrigins.mapTo(LinkedHashSet()) { it.asOriginString() }
        val listener =
            WebViewCompat.WebMessageListener { view, message, sourceOrigin, isMainFrame, replyProxy ->
                if (destroyed) return@WebMessageListener
                if (message.type != WebMessageCompat.TYPE_STRING) {
                    runCatching {
                        replyProxy.postMessage(
                            JsbWireCodec.encodeResponse(
                                null,
                                JsbResult.Failure(JsbErrorCode.INVALID_MESSAGE, "Binary WebMessage payload is not supported"),
                            ),
                        )
                    }
                    return@WebMessageListener
                }
                val raw = runCatching { message.data }.getOrNull() ?: return@WebMessageListener
                val origin = parseWebContainerOrigin(sourceOrigin.toString())
                val docUrl = view.url.orEmpty().ifBlank { currentUrl }
                dispatcher.dispatch(
                    rawPayload = raw,
                    origin = origin,
                    documentUrl = docUrl,
                    isMainFrame = isMainFrame,
                ) { replyJson ->
                    if (!destroyed) {
                        runCatching { replyProxy.postMessage(replyJson) }
                    }
                }
            }
        runCatching {
            WebViewCompat.addWebMessageListener(webView, policy.portName, allowedOriginRules, listener)
            jsbDispatcher = dispatcher
            activeJsbPortName = policy.portName
        }
    }

    private fun removeJsbBridge() {
        jsbDispatcher?.cancelInFlight("bridge_rebound")
        jsbDispatcher = null
        val port = activeJsbPortName ?: return
        activeJsbPortName = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            runCatching { WebViewCompat.removeWebMessageListener(webView, port) }
        }
    }

    private fun loadConfiguredUrl(overrideUrl: String?) {
        val target = (overrideUrl ?: config.url).trim()
        if (isolatedProfileUnavailable) {
            currentLoading = false
            mainFrameLoadFailed = true
            notifyNavigationState(force = true)
            if (target.isNotEmpty()) events.onLoadFailure("isolated_profile_unavailable")
            return
        }
        if (target.isEmpty()) {
            loadedSrc = ""
            currentUrl = ""
            currentLoading = false
            webView.loadUrl(ABOUT_BLANK_URL)
            notifyNavigationState(force = true)
            return
        }
        val decision =
            evaluateMainFrameNavigation(
                targetUrl = target,
                initialUrl = config.url,
                policy = config.navigationPolicy,
                method = "GET",
                requestCarriesScopedHeader = false,
                chainState = null,
            )
        when (decision) {
            is WebNavigationDecision.Block -> {
                notifyNavigationBlocked(decision.url, decision.reason)
                return
            }
            is WebNavigationDecision.InterceptedByHost -> return
            is WebNavigationDecision.ReissueWithHeader -> {
                loadedSrc = target
                val budget = config.navigationPolicy.requestHeaderRule?.maxSameOriginReissues ?: 0
                chainState.startChain(carriesHeader = true, reissueBudget = budget, starter = WebNavigationStarter.Launch)
                webView.loadUrl(target, mapOf(decision.headerName to decision.headerValue))
            }
            else -> {
                loadedSrc = target
                val budget = config.navigationPolicy.requestHeaderRule?.maxSameOriginReissues ?: 0
                chainState.startChain(carriesHeader = false, reissueBudget = budget, starter = WebNavigationStarter.Launch)
                webView.loadUrl(target)
            }
        }
    }

    private fun executeCommand(command: WebContainerCommand) {
        if (destroyed) return
        when (command) {
            WebContainerCommand.GoBack -> if (webView.canGoBack()) webView.goBack()
            WebContainerCommand.GoForward -> if (webView.canGoForward()) webView.goForward()
            WebContainerCommand.Reload -> {
                mainFrameLoadFailed = false
                webView.reload()
            }
            WebContainerCommand.Stop -> {
                webView.stopLoading()
                currentLoading = false
                notifyNavigationState()
            }
            is WebContainerCommand.LoadUrl -> {
                val target = command.url.trim()
                if (target.isEmpty()) return
                val budget = config.navigationPolicy.requestHeaderRule?.maxSameOriginReissues ?: 0
                chainState.startChain(carriesHeader = false, reissueBudget = budget, starter = WebNavigationStarter.Page)
                val decision =
                    evaluateMainFrameNavigation(
                        targetUrl = target,
                        initialUrl = config.url,
                        policy = config.navigationPolicy,
                        method = "GET",
                        chainState = chainState,
                        catchesMainFrameNavigation = events.catchesMainFrameNavigation,
                        routesMainFrameNavigation = events.routesMainFrameNavigation,
                    )
                when (decision) {
                    WebNavigationDecision.Allow -> webView.loadUrl(target)
                    is WebNavigationDecision.ReissueWithHeader ->
                        webView.loadUrl(decision.url, mapOf(decision.headerName to decision.headerValue))
                    is WebNavigationDecision.ReissueWithoutHeader -> webView.loadUrl(decision.url)
                    is WebNavigationDecision.Block -> notifyNavigationBlocked(decision.url, decision.reason)
                    is WebNavigationDecision.InterceptedByHost -> Unit
                }
            }
            is WebContainerCommand.EvaluateJavaScript -> {
                if (!config.allowScripts || command.script.isBlank()) return
                if (command.script.length > config.maxRuntimeJavaScriptLength) return
                val activeUrl = webView.url.orEmpty()
                val activeOrigin = parseWebContainerOrigin(activeUrl) ?: return
                val locked = config.navigationPolicy.lockedMainFrameOrigin
                if (locked != null && activeOrigin != locked) return
                val initialOrigin = parseWebContainerOrigin(config.url)
                if (initialOrigin != null && activeOrigin != initialOrigin) return
                webView.evaluateJavascript(command.script, null)
            }
            WebContainerCommand.ProbeContent -> probeDocumentContent(loadGeneration)
            is WebContainerCommand.EmitJsbEvent -> {
                val script = jsbDispatcher?.buildEventDispatchScript(command.name, command.payload) ?: return
                if (config.allowScripts) {
                    webView.evaluateJavascript(script, null)
                }
            }
        }
    }

    private fun probeDocumentContent(expectedGeneration: Int) {
        if (!config.allowScripts) {
            events.onContentProbe(null)
            val action =
                watchdog.onEvent(
                    WebContainerWatchdogEvent.DomProbeResult(expectedGeneration, null),
                    webContainerMonotonicMillis(),
                )
            handleWatchdogAction(action)
            return
        }
        webView.evaluateJavascript(JsbBootstrapScripts.ContentVisibilityProbeScript) { raw ->
            if (destroyed || expectedGeneration != loadGeneration) return@evaluateJavascript
            val hasContent = JsbBootstrapScripts.decodeContentProbeResult(raw)
            events.onContentProbe(hasContent)
            val action =
                watchdog.onEvent(
                    WebContainerWatchdogEvent.DomProbeResult(expectedGeneration, hasContent),
                    webContainerMonotonicMillis(),
                )
            handleWatchdogAction(action)
        }
    }

    private fun handleWatchdogAction(action: WebContainerWatchdogAction) {
        when (action) {
            WebContainerWatchdogAction.None -> Unit
            is WebContainerWatchdogAction.RequestDomProbe -> probeDocumentContent(action.generation)
            is WebContainerWatchdogAction.TriggerRecoveryReload -> Unit
            is WebContainerWatchdogAction.EmitTerminalError -> {
                telemetrySink?.onEvent(
                    WebContainerTelemetryEvent.WatchdogTerminalError(
                        surfaceId = config.surfaceId,
                        reason = action.reason,
                    ),
                )
                events.onWatchdogTerminalError(action.reason)
            }
        }
    }

    private fun markPagePainted() {
        if (pagePainted) return
        pagePainted = true
        webView.setBackgroundColor(effectivePlaceholderColor(config.backgroundArgb, painted = true))
    }

    private fun notifyNavigationBlocked(
        url: String,
        reason: String,
    ) {
        telemetrySink?.onEvent(
            WebContainerTelemetryEvent.NavigationBlocked(
                surfaceId = config.surfaceId,
                sanitizedUrl = sanitizeUrlForTelemetry(url),
                reason = reason,
            ),
        )
        events.onNavigationBlocked(url, reason)
    }

    private fun notifyNavigationState(force: Boolean = false) {
        navigationStateRelay.notify(
            force = force,
            isActive = { !destroyed },
            readState = {
                WebNavigationState(
                    url = currentUrl,
                    title = currentTitle,
                    canGoBack = webView.canGoBack(),
                    canGoForward = webView.canGoForward(),
                    isLoading = currentLoading,
                    progress = currentProgress.coerceIn(0, 100),
                    loadGeneration = loadGeneration,
                )
            },
            deliver = { state -> events.onNavigationStateChange(state) },
        )
    }

    private fun effectivePlaceholderColor(
        placeholderArgb: Int,
        painted: Boolean,
    ): Int = if (!painted && placeholderArgb != 0) placeholderArgb else 0xFFFFFFFF.toInt()
}
