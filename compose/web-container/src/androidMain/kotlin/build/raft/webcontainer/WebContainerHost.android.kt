package build.raft.webcontainer

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.DownloadListener
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
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
import build.raft.webcontainer.pool.WebContainerKeptSnapshot
import build.raft.webcontainer.pool.WebContainerPoolKey
import build.raft.webcontainer.pool.WebContainerTelemetryEvent
import build.raft.webcontainer.pool.WebContainerTelemetrySink
import build.raft.webcontainer.pool.WebIsolationClearResult
import build.raft.webcontainer.pool.WebIsolationScopeManager
import build.raft.webcontainer.pool.WebMemoryTrimLevel
import build.raft.webcontainer.pool.WebPooledContainer
import build.raft.webcontainer.resilience.WebContainerPhase
import build.raft.webcontainer.resilience.WebContainerWatchdog
import build.raft.webcontainer.resilience.WebContainerWatchdogAction
import build.raft.webcontainer.resilience.WebContainerWatchdogEvent
import build.raft.webcontainer.resilience.WebContainerWatchdogPolicy
import build.raft.webcontainer.resilience.WebNavigationStateRelay
import build.raft.webcontainer.resource.WebResourceInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Host-supplied Android `<input type="file">` launcher for [WebContainerView].
 *
 * Implementations own [callback] (matching `WebChromeClient.onShowFileChooser`): when returning
 * `false` without launching a chooser, the implementation invokes `callback.onReceiveValue(null)`.
 */
fun interface WebContainerAndroidFileChooser {
    fun launch(
        context: Context,
        params: WebChromeClient.FileChooserParams,
        callback: ValueCallback<Array<Uri>>,
    ): Boolean
}

/** Optional CompositionLocal supplying an Android file-chooser bridge to [WebContainerView]. */
val LocalAndroidWebFileChooser = staticCompositionLocalOf<WebContainerAndroidFileChooser?> { null }

internal const val ANDROID_WEBVIEW_ENGINE: String = "android_webview"

/** Process-wide Android keep-alive pool and isolation-scope lifecycle coordinator. */
object WebContainerAndroidKeepAlive {
    /** Safety ceiling for a parked background prewarm to finish loading before being aborted. */
    const val PREWARM_LOAD_TIMEOUT_MS = 12_000L

    @Volatile
    private var poolTelemetrySink: WebContainerTelemetrySink? = null

    private val pool =
        WebContainerKeepAlivePool<WebContainerAndroidView>(
            telemetrySink =
                WebContainerTelemetrySink { event ->
                    runCatching {
                        val attributed =
                            if (event is WebContainerTelemetryEvent.PoolEvent && event.engine == null) {
                                event.copy(engine = ANDROID_WEBVIEW_ENGINE)
                            } else {
                                event
                            }
                        poolTelemetrySink?.onEvent(attributed)
                    }
                },
        )
    internal val scopeManager = WebIsolationScopeManager()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val prewarmTimeouts = LinkedHashMap<WebContainerAndroidView, Runnable>()
    internal var prewarmLoadTimeoutMs: Long = PREWARM_LOAD_TIMEOUT_MS

    internal fun take(
        key: WebContainerPoolKey,
        hostContext: Context,
        events: WebContainerEvents,
        jsbRegistry: JsbRegistry,
        resourceInterceptor: WebResourceInterceptor?,
        telemetrySink: WebContainerTelemetrySink?,
        fileChooser: WebContainerAndroidFileChooser?,
    ): WebContainerAndroidView? {
        if (telemetrySink != null) poolTelemetrySink = telemetrySink
        val view = pool.take(key) ?: return null
        cancelPrewarmTimeout(view)
        view.attachFromKeepAlive(
            hostContext = hostContext,
            nextEvents = events,
            nextRegistry = jsbRegistry,
            nextInterceptor = resourceInterceptor,
            nextTelemetry = telemetrySink,
            nextFileChooser = fileChooser,
        )
        return view
    }

    internal fun park(
        view: WebContainerAndroidView,
        telemetrySink: WebContainerTelemetrySink? = null,
    ): Boolean {
        if (telemetrySink != null) {
            poolTelemetrySink = telemetrySink
            view.ensureTelemetrySink(telemetrySink)
        }
        val key = view.currentPoolKey() ?: return false
        if (scopeManager.hasPendingDeletion(view.isolationScope)) {
            return false
        }
        view.detachForKeepAlive()
        val parked = pool.park(key, view)
        if (parked && view.isLoadInFlight()) {
            armPrewarmTimeout(view, telemetrySink ?: poolTelemetrySink)
        } else {
            cancelPrewarmTimeout(view)
        }
        return parked
    }

    internal fun dropIfSame(view: WebContainerAndroidView): Boolean {
        cancelPrewarmTimeout(view)
        return pool.dropIfSame(view)
    }

    internal fun onParkedLoadFinished(view: WebContainerAndroidView) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { cancelPrewarmTimeout(view) }
            return
        }
        cancelPrewarmTimeout(view)
    }

    /**
     * Aborts and destroys any parked prewarm whose main-frame load is still in flight so a
     * slow/stuck background warm-up cannot starve a foreground WebView on Android's shared
     * Chromium renderer process. Already finished parked views are preserved for warm-hit reuse.
     */
    fun abortInFlightPrewarmForForeground(
        foregroundSurface: String,
        exceptKey: WebContainerPoolKey? = null,
        telemetrySink: WebContainerTelemetrySink? = null,
    ): Int {
        if (Looper.myLooper() != Looper.getMainLooper()) return 0
        if (telemetrySink != null) poolTelemetrySink = telemetrySink
        val aborted = pool.abortInFlightLoads(exceptKey = exceptKey)
        pruneInactivePrewarmTimeouts()
        if (aborted > 0) {
            val sanitizedSurface =
                foregroundSurface.trim().filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifEmpty { "unknown" }
            runCatching {
                (telemetrySink ?: poolTelemetrySink)?.onEvent(
                    WebContainerTelemetryEvent.PoolEvent(
                        action = "prewarm_aborted_for_foreground",
                        keepAliveKey = sanitizedSurface,
                        poolSizeAfter = pool.size,
                        engine = ANDROID_WEBVIEW_ENGINE,
                    ),
                )
            }
        }
        return aborted
    }

    /** Returns a read-only snapshot of the healthy parked document matching the given pool key. */
    fun keptDocument(
        keepAliveKey: String,
        isolationScope: WebIsolationScope = WebIsolationScope.Default,
        isDarkTheme: Boolean = false,
    ): WebContainerKeptSnapshot? {
        if (Looper.myLooper() != Looper.getMainLooper()) return null
        val trimmedKey = keepAliveKey.trim()
        if (trimmedKey.isEmpty() || isolationScope is WebIsolationScope.Ephemeral) return null
        val key = WebContainerPoolKey(trimmedKey, isolationScope, isDarkTheme)
        return pool.peek(key)?.keptSnapshot()
    }

    /**
     * Evaluates [script] in the top-level document of a healthy parked container when its active
     * origin strictly matches [expectedOrigin] and the page has finished loading.
     */
    fun evaluateInKept(
        keepAliveKey: String,
        expectedOrigin: WebContainerOrigin,
        script: String,
        isolationScope: WebIsolationScope = WebIsolationScope.Default,
        isDarkTheme: Boolean = false,
    ): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        val trimmedKey = keepAliveKey.trim()
        if (trimmedKey.isEmpty() || isolationScope is WebIsolationScope.Ephemeral) return false
        val key = WebContainerPoolKey(trimmedKey, isolationScope, isDarkTheme)
        return pool.peek(key)?.evaluateInTopDocument(expectedOrigin, script) == true
    }

    /**
     * Prewarms and immediately parks a [WebContainerAndroidView] for [config] on the main thread,
     * swapping the Activity context to `applicationContext` upon parking so no Activity leaks.
     */
    fun prewarm(
        context: Context,
        config: WebContainerConfig,
        jsbRegistry: JsbRegistry = JsbRegistry.Empty,
        resourceInterceptor: WebResourceInterceptor? = null,
        telemetrySink: WebContainerTelemetrySink? = null,
    ): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        val keyStr = config.keepAliveKey?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (config.isolationScope is WebIsolationScope.Ephemeral) return false
        if (scopeManager.hasPendingDeletion(config.isolationScope)) return false
        if (telemetrySink != null) poolTelemetrySink = telemetrySink
        val poolKey = WebContainerPoolKey(keyStr, config.isolationScope, config.isDarkTheme)
        if (pool.peek(poolKey) != null) return true
        val view =
            WebContainerAndroidView(
                context = MutableContextWrapper(context),
                initialConfig = config,
                events = WebContainerEvents(),
                jsbRegistry = jsbRegistry,
                resourceInterceptor = resourceInterceptor,
                telemetrySink = telemetrySink,
                fileChooser = null,
            )
        if (park(view, telemetrySink = telemetrySink)) {
            return true
        }
        view.destroy()
        return false
    }

    /** Evicts pooled views under OS memory pressure (`onTrimMemory`). */
    fun onMemoryPressure(level: WebMemoryTrimLevel): Int =
        pool.onMemoryPressure(level).also { pruneInactivePrewarmTimeouts() }

    /** Destroys all pooled views immediately. */
    fun clearAll(): Int {
        clearAllPrewarmTimeouts()
        val cleared = pool.clearAll()
        poolTelemetrySink = null
        return cleared
    }

    internal fun clearIsolationScope(
        scope: WebIsolationScope,
        deletePlatformStore: (WebIsolationScope) -> Boolean,
    ): WebIsolationClearResult =
        scopeManager.clearIsolationScope(
            scope = scope,
            evictPooled = { target ->
                pool.evictScope(target).also { pruneInactivePrewarmTimeouts() }
            },
            deletePlatformStore = deletePlatformStore,
        )

    private fun armPrewarmTimeout(
        view: WebContainerAndroidView,
        telemetrySink: WebContainerTelemetrySink?,
    ) {
        cancelPrewarmTimeout(view)
        val runnable =
            Runnable {
                prewarmTimeouts.remove(view)
                if (!view.isLoadInFlight()) return@Runnable
                if (pool.dropIfSame(view)) {
                    runCatching {
                        (telemetrySink ?: poolTelemetrySink)?.onEvent(
                            WebContainerTelemetryEvent.PoolEvent(
                                action = "prewarm_aborted_timeout",
                                keepAliveKey = view.currentPoolKey()?.keepAliveKey ?: "unknown",
                                poolSizeAfter = pool.size,
                                engine = ANDROID_WEBVIEW_ENGINE,
                            ),
                        )
                    }
                }
            }
        prewarmTimeouts[view] = runnable
        mainHandler.postDelayed(runnable, prewarmLoadTimeoutMs)
    }

    private fun cancelPrewarmTimeout(view: WebContainerAndroidView) {
        prewarmTimeouts.remove(view)?.let { mainHandler.removeCallbacks(it) }
    }

    private fun pruneInactivePrewarmTimeouts() {
        val stale = prewarmTimeouts.entries.filter { (view, _) -> !view.isHealthy() }.map { it.key }
        for (view in stale) {
            cancelPrewarmTimeout(view)
        }
    }

    private fun clearAllPrewarmTimeouts() {
        val runnables = prewarmTimeouts.values.toList()
        prewarmTimeouts.clear()
        for (runnable in runnables) {
            mainHandler.removeCallbacks(runnable)
        }
    }
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
    val fileChooser = LocalAndroidWebFileChooser.current
    AndroidView(
        factory = { context ->
            val poolKey =
                config.keepAliveKey
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && config.isolationScope !is WebIsolationScope.Ephemeral }
                    ?.let { key ->
                        WebContainerPoolKey(
                            keepAliveKey = key,
                            isolationScope = config.isolationScope,
                            isDarkTheme = config.isDarkTheme,
                        )
                    }
            WebContainerAndroidKeepAlive.abortInFlightPrewarmForForeground(
                foregroundSurface = config.surfaceId,
                exceptKey = poolKey,
                telemetrySink = telemetrySink,
            )
            val reused =
                poolKey?.let { key ->
                    WebContainerAndroidKeepAlive.take(
                        key = key,
                        hostContext = context,
                        events = events,
                        jsbRegistry = jsbRegistry,
                        resourceInterceptor = resourceInterceptor,
                        telemetrySink = telemetrySink,
                        fileChooser = fileChooser,
                    )
                }
            reused ?: WebContainerAndroidView(
                context = MutableContextWrapper(context),
                initialConfig = config,
                events = events,
                jsbRegistry = jsbRegistry,
                resourceInterceptor = resourceInterceptor,
                telemetrySink = telemetrySink,
                fileChooser = fileChooser,
            )
        },
        modifier = modifier,
        update = { view ->
            view.events = events
            view.telemetrySink = telemetrySink
            view.fileChooser = fileChooser
            view.updateRuntimeHooks(jsbRegistry, resourceInterceptor)
            view.applyConfig(config)
            view.bindController(controller)
        },
        onRelease = { view ->
            if (!WebContainerAndroidKeepAlive.park(view, telemetrySink = telemetrySink)) {
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
    var fileChooser: WebContainerAndroidFileChooser? = null,
    watchdogPolicy: WebContainerWatchdogPolicy = WebContainerWatchdogPolicy(),
    private val nowMs: () -> Long = ::webContainerMonotonicMillis,
) : FrameLayout(context), WebPooledContainer {
    private var webView: WebView = WebView(context)
    @Volatile private var destroyed: Boolean = false
    private var parkedForKeepAlive: Boolean = false
    private var lastWindowVisibility: Int = View.GONE
    private var watchdogTickScheduled: Boolean = false
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
    private var desktopModeScaleApplied: Boolean = false
    private var desktopModeScaleRetryScheduled: Boolean = false

    private val chainState = WebNavigationChainState()
    private val navigationStateRelay = WebNavigationStateRelay()
    private val watchdog = WebContainerWatchdog(watchdogPolicy)
    internal val watchdogPhase: WebContainerPhase get() = watchdog.phase
    internal val isWatchdogBackgrounded: Boolean get() = watchdog.isBackgrounded
    internal val isWatchdogTickScheduled: Boolean get() = watchdogTickScheduled
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var jsbDispatcher: JsbDispatcher? = null
    private var activeJsbPortName: String? = null
    private var activeJsbOriginRules: Set<String> = emptySet()
    private val documentStartHandlers = mutableListOf<ScriptHandler>()
    private val pendingBlobDownloads = LinkedHashMap<String, WebContainerBlobDownloadAssembler>()
    private var downloadBridgeOrigin: String? = null
    private var downloadNameScriptHandler: ScriptHandler? = null
    private val downloadJson = Json { ignoreUnknownKeys = true }
    private var boundController: WebContainerController? = null
    private val jsDialogTracker = WebJsDialogTracker()
    internal val fullscreenController = WebContainerAndroidFullscreenController()
    internal val newWindowTransport = WebContainerAndroidNewWindowTransport()

    private val watchdogTickRunnable =
        object : Runnable {
            override fun run() {
                watchdogTickScheduled = false
                if (!isWatchdogActive()) return
                val action = watchdog.onEvent(WebContainerWatchdogEvent.Tick, nowMs())
                handleWatchdogAction(action)
                scheduleWatchdogTickIfNeeded()
            }
        }

    init {
        registerActiveScope(isolationScope)
        bindInitialProfileToWebView(webView, isolationScope)
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        configureWebView()
        applyConfig(initialConfig)
        syncWatchdogLifecycle()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastWindowVisibility = windowVisibility
        syncWatchdogLifecycle()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        lastWindowVisibility = visibility
        if (visibility != View.VISIBLE) {
            jsDialogTracker.cancelActive()
            fullscreenController.hide()
            newWindowTransport.cancelAll()
        }
        syncWatchdogLifecycle()
    }

    override fun onDetachedFromWindow() {
        lastWindowVisibility = View.GONE
        jsDialogTracker.cancelActive()
        fullscreenController.hide()
        newWindowTransport.cancelAll()
        syncWatchdogLifecycle()
        super.onDetachedFromWindow()
    }

    private fun isWatchdogActive(): Boolean =
        !destroyed && !parkedForKeepAlive && isAttachedToWindow && lastWindowVisibility == View.VISIBLE

    private fun syncWatchdogLifecycle() {
        if (destroyed) {
            cancelWatchdogTick()
            return
        }
        if (isWatchdogActive()) {
            watchdog.onEvent(WebContainerWatchdogEvent.Foregrounded, nowMs())
            scheduleWatchdogTickIfNeeded()
        } else {
            watchdog.onEvent(WebContainerWatchdogEvent.Backgrounded, nowMs())
            cancelWatchdogTick()
        }
    }

    private fun scheduleWatchdogTickIfNeeded() {
        if (watchdogTickScheduled || !isWatchdogActive()) return
        watchdogTickScheduled = true
        mainHandler.postDelayed(watchdogTickRunnable, WATCHDOG_TICK_MS)
    }

    private fun cancelWatchdogTick() {
        watchdogTickScheduled = false
        mainHandler.removeCallbacks(watchdogTickRunnable)
    }

    override fun isHealthy(): Boolean =
        !destroyed && !mainFrameLoadFailed && !isolatedProfileUnavailable

    override fun isLoadInFlight(): Boolean =
        !destroyed && !mainFrameLoadFailed && loadedSrc.isNotBlank() && (currentLoading || lastSuccessfulUrl.isBlank())

    internal fun keptSnapshot(): WebContainerKeptSnapshot? {
        if (!isHealthy()) return null
        return WebContainerKeptSnapshot(
            configuredUrl = config.url,
            currentUrl = webView.url.orEmpty().ifBlank { currentUrl },
            loaded = !currentLoading && lastSuccessfulUrl.isNotBlank(),
        )
    }

    internal fun evaluateInTopDocument(
        expectedOrigin: WebContainerOrigin,
        script: String,
    ): Boolean {
        if (!isHealthy() || !config.allowScripts || script.isBlank()) return false
        if (script.length > config.maxRuntimeJavaScriptLength) return false
        if (currentLoading || lastSuccessfulUrl.isBlank()) return false
        val activeUrl = webView.url.orEmpty()
        if (activeUrl.isBlank()) return false
        val activeOrigin = parseWebContainerOrigin(activeUrl) ?: return false
        val lockedOrigin = config.navigationPolicy.lockedMainFrameOrigin
        if (activeOrigin != expectedOrigin) return false
        if (lockedOrigin != null && activeOrigin != lockedOrigin) return false
        if (!config.navigationPolicy.allowNavigation && !isSameDocumentIgnoringFragment(config.url.trim(), activeUrl)) {
            return false
        }
        webView.evaluateJavascript(script, null)
        return true
    }

    internal fun currentPoolKey(): WebContainerPoolKey? {
        val key = config.keepAliveKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (isolationScope is WebIsolationScope.Ephemeral) return null
        // Never park a WebView whose live theme diverged from its Chromium construction theme.
        if (config.isDarkTheme != isDarkTheme) return null
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
            rebuildJsbDispatcher()
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
        val wasConfigured = configured
        val scopeChanged = wasConfigured && previous.isolationScope != next.isolationScope
        val scriptsChanged =
            !wasConfigured ||
                previous.allowScripts != next.allowScripts ||
                previous.jsbPolicy != next.jsbPolicy ||
                previous.documentStartScripts != next.documentStartScripts ||
                previous.offlineBundle != next.offlineBundle ||
                previous.backgroundArgb != next.backgroundArgb ||
                previous.isDarkTheme != next.isDarkTheme ||
                previous.desktopViewportMode != next.desktopViewportMode
        val jsbTransportChanged =
            !wasConfigured ||
                previous.allowScripts != next.allowScripts ||
                previous.jsbPolicy.isEffectivelyEnabled != next.jsbPolicy.isEffectivelyEnabled ||
                previous.jsbPolicy.portName != next.jsbPolicy.portName ||
                previous.jsbPolicy.allowedOrigins != next.jsbPolicy.allowedOrigins
        val jsbPolicyChanged =
            !wasConfigured ||
                previous.allowScripts != next.allowScripts ||
                previous.jsbPolicy != next.jsbPolicy
        val settingsChanged =
            !wasConfigured ||
                previous.allowScripts != next.allowScripts ||
                previous.domStorageEnabled != next.domStorageEnabled ||
                previous.cacheEnabled != next.cacheEnabled ||
                previous.allowPinchZoom != next.allowPinchZoom ||
                previous.desktopViewportMode != next.desktopViewportMode ||
                previous.navigationPolicy.allowNewWindows != next.navigationPolicy.allowNewWindows
        val desktopModeChanged = wasConfigured && previous.desktopViewportMode != next.desktopViewportMode
        val urlChanged = !wasConfigured || previous.url.trim() != next.url.trim()
        val desiredDownloadOrigin = resolveDesiredDownloadOrigin(next)
        val downloadBridgeChanged = !wasConfigured || desiredDownloadOrigin != downloadBridgeOrigin

        config = next
        configured = true
        if (!next.allowJsDialogs) {
            jsDialogTracker.cancelActive()
        }
        if (!next.navigationPolicy.allowNewWindows) {
            newWindowTransport.cancelAll()
        }
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
        }
        if (jsbTransportChanged) {
            rebindJsbBridge()
        } else if (jsbPolicyChanged) {
            rebuildJsbDispatcher()
        }
        if (downloadBridgeChanged) {
            rebindDownloadBridge()
        }
        if (desktopModeChanged || (!wasConfigured && next.desktopViewportMode)) {
            applyDesktopModeScale()
        }
        if (!pagePainted) {
            webView.setBackgroundColor(effectivePlaceholderColor(next.backgroundArgb, painted = false))
        }
        if (urlChanged) {
            loadConfiguredUrl(overrideUrl = null)
        } else if (desktopModeChanged && !isolatedProfileUnavailable && webView.url != null) {
            mainFrameLoadFailed = false
            webView.reload()
        }
    }

    internal fun ensureTelemetrySink(sink: WebContainerTelemetrySink) {
        if (telemetrySink == null) {
            telemetrySink = sink
        }
    }

    internal fun detachForKeepAlive() {
        jsDialogTracker.cancelActive()
        fullscreenController.hide()
        newWindowTransport.cancelAll()
        boundController?.unbindDispatcher(this)
        boundController = null
        events = WebContainerEvents()
        fileChooser = null
        parkedForKeepAlive = true
        syncWatchdogLifecycle()
        jsbRegistry = JsbRegistry.Empty
        jsbDispatcher?.cancelInFlight("parked_for_keep_alive")
        jsbDispatcher = null
        pendingBlobDownloads.clear()
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
        nextFileChooser: WebContainerAndroidFileChooser?,
    ) {
        (context as? MutableContextWrapper)?.baseContext = hostContext
        parkedForKeepAlive = false
        events = nextEvents
        telemetrySink = nextTelemetry
        fileChooser = nextFileChooser
        resourceInterceptor = nextInterceptor
        jsbRegistry = nextRegistry
        rebuildJsbDispatcher()
        if (resolveDesiredDownloadOrigin(config) != downloadBridgeOrigin) {
            rebindDownloadBridge()
        }
        registerActiveScope(isolationScope)
        navigationStateRelay.reset()
        notifyNavigationState(force = true)
        if (isAttachedToWindow) {
            lastWindowVisibility = windowVisibility
        }
        syncWatchdogLifecycle()
        if (!mainFrameLoadFailed && lastSuccessfulUrl.isNotBlank()) {
            events.onLoadSuccess()
        }
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        parkedForKeepAlive = false
        jsDialogTracker.cancelActive()
        fullscreenController.hide()
        newWindowTransport.cancelAll()
        WebContainerAndroidKeepAlive.onParkedLoadFinished(this)
        fileChooser = null
        cancelWatchdogTick()
        boundController?.unbindDispatcher(this)
        boundController = null
        jsbDispatcher?.cancelInFlight("container_destroyed")
        removeJsbBridge()
        removeDownloadBridge()
        clearDocumentStartScripts()
        hostScope.cancel()
        runCatching {
            webView.stopLoading()
            webView.loadUrl(ABOUT_BLANK_URL)
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.setDownloadListener(null)
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
        jsDialogTracker.cancelActive()
        fullscreenController.hide()
        newWindowTransport.cancelAll()
        removeJsbBridge()
        removeDownloadBridge()
        clearDocumentStartScripts()
        runCatching {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.setDownloadListener(null)
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
        isDarkTheme = config.isDarkTheme
        desktopModeScaleApplied = false
        desktopModeScaleRetryScheduled = false
        chainState.reset(clearCommitted = true)
        navigationStateRelay.reset()

        webView = WebView(context)
        bindInitialProfileToWebView(webView, isolationScope)
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        configureWebView()
        applyWebSettings()
        applyDesktopModeScale()
        rebindDocumentStartScripts()
        rebindJsbBridge()
        rebindDownloadBridge()
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
            setSupportMultipleWindows(config.navigationPolicy.allowNewWindows)
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
        val configuredWebView = webView
        applyWebSettings()
        configuredWebView.setBackgroundColor(effectivePlaceholderColor(config.backgroundArgb, painted = false))
        configuredWebView.webChromeClient =
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
                    if (destroyed || parkedForKeepAlive || configuredWebView !== webView) return false
                    if (!config.navigationPolicy.allowNewWindows) {
                        val link = extractAndroidHitTestLinkUrl(view?.hitTestResult)
                        notifyNavigationBlocked(link, "new_window_blocked")
                        return false
                    }
                    if (!isUserGesture || !isForegroundVisibleAndActive(configuredWebView)) {
                        return false
                    }
                    val opened =
                        newWindowTransport.open(
                            context = context,
                            resultMsg = resultMsg,
                            profileName = activeTransportProfileName(),
                            onUrlCaptured = { capturedUrl ->
                                handleAndroidNewWindowUrl(configuredWebView, capturedUrl)
                            },
                        )
                    if (opened) return true
                    val fallbackLink = extractAndroidHitTestLinkUrl(view?.hitTestResult)
                    if (fallbackLink.isNotBlank()) {
                        handleAndroidNewWindowUrl(configuredWebView, fallbackLink)
                    }
                    return false
                }

                override fun onShowCustomView(
                    view: View?,
                    callback: CustomViewCallback?,
                ) {
                    fullscreenController.show(
                        context = context,
                        view = view,
                        callback = callback,
                        permitted = isForegroundVisibleAndActive(configuredWebView),
                    )
                }

                override fun onHideCustomView() {
                    fullscreenController.hide()
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    // Per product decision (#proj-mobile:#395 / #397): deny all web camera/microphone/media
                    // permission requests on Android without forwarding system runtime permission prompts.
                    runCatching { request?.deny() }
                }

                override fun onShowFileChooser(
                    view: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    if (filePathCallback == null) return false
                    var delivered = false
                    val singleShotCallback =
                        ValueCallback<Array<Uri>> { uris ->
                            if (!delivered) {
                                delivered = true
                                filePathCallback.onReceiveValue(uris)
                            }
                        }
                    if (destroyed || fileChooserParams == null) {
                        singleShotCallback.onReceiveValue(null)
                        return false
                    }
                    val chooser = fileChooser
                    if (chooser == null) {
                        singleShotCallback.onReceiveValue(null)
                        return false
                    }
                    val launched =
                        runCatching { chooser.launch(context, fileChooserParams, singleShotCallback) }
                            .getOrDefault(false)
                    if (!launched) {
                        singleShotCallback.onReceiveValue(null)
                    }
                    return launched
                }

                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?,
                ): Boolean {
                    if (result == null) return false
                    return handleAndroidJsDialog(
                        configuredWebView = configuredWebView,
                        kind = WebJsDialogKind.Alert,
                        url = url,
                        message = message,
                        defaultPromptText = null,
                        onConfirm = { result.confirm() },
                        onCancel = { result.cancel() },
                    )
                }

                override fun onJsConfirm(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?,
                ): Boolean {
                    if (result == null) return false
                    return handleAndroidJsDialog(
                        configuredWebView = configuredWebView,
                        kind = WebJsDialogKind.Confirm,
                        url = url,
                        message = message,
                        defaultPromptText = null,
                        onConfirm = { result.confirm() },
                        onCancel = { result.cancel() },
                    )
                }

                override fun onJsPrompt(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    defaultValue: String?,
                    result: JsPromptResult?,
                ): Boolean {
                    if (result == null) return false
                    return handleAndroidJsDialog(
                        configuredWebView = configuredWebView,
                        kind = WebJsDialogKind.Prompt,
                        url = url,
                        message = message,
                        defaultPromptText = defaultValue,
                        onConfirm = { value -> result.confirm(value ?: "") },
                        onCancel = { result.cancel() },
                    )
                }
            }

        webView.webViewClient =
            object : WebViewClient() {
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    if (destroyed) return true
                    val api26 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    val didCrash = api26 && detail?.didCrash() == true
                    val rendererPriority = if (api26 && detail != null) detail.rendererPriorityAtExit() else null
                    jsDialogTracker.cancelActive()
                    fullscreenController.hide()
                    newWindowTransport.cancelAll()
                    jsbDispatcher?.cancelInFlight("render_process_gone")
                    if (parkedForKeepAlive) {
                        runCatching {
                            telemetrySink?.onEvent(
                                WebContainerTelemetryEvent.RenderProcessGone(
                                    surfaceId = config.surfaceId,
                                    didCrash = didCrash,
                                    recovered = true,
                                    attempt = 0,
                                    rendererPriorityAtExit = rendererPriority,
                                    parked = true,
                                    engine = ANDROID_WEBVIEW_ENGINE,
                                ),
                            )
                        }
                        WebContainerAndroidKeepAlive.dropIfSame(this@WebContainerAndroidView)
                        return true
                    }
                    events.onContentProcessGone(didCrash)
                    val action =
                        watchdog.onEvent(
                            WebContainerWatchdogEvent.RenderProcessGone(didCrash = didCrash),
                            nowMs(),
                        )
                    val shouldRecover = action is WebContainerWatchdogAction.TriggerRecoveryReload
                    val attempt =
                        (action as? WebContainerWatchdogAction.TriggerRecoveryReload)?.recoveryAttempt
                            ?: watchdog.recentRecoveryCount
                    runCatching {
                        telemetrySink?.onEvent(
                            WebContainerTelemetryEvent.RenderProcessGone(
                                surfaceId = config.surfaceId,
                                didCrash = didCrash,
                                recovered = shouldRecover,
                                attempt = attempt,
                                rendererPriorityAtExit = rendererPriority,
                                parked = false,
                                engine = ANDROID_WEBVIEW_ENGINE,
                            ),
                        )
                    }
                    val restoreUrl =
                        if (shouldRecover && loadedSrc.isNotBlank()) {
                            sanitizeRendererRestoreUrl(
                                initialUrl = config.url,
                                candidateUrl = currentUrl,
                                policy = config.navigationPolicy,
                                isAllowedCandidate = { candidate ->
                                    config.navigationPolicy.allowNavigation ||
                                        isSameDocumentIgnoringFragment(config.url.trim(), candidate)
                                },
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
                    jsDialogTracker.cancelActive()
                    fullscreenController.hide()
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
                        nowMs(),
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
                    events.onUrlChange(currentUrl)
                    if (!mainFrameLoadFailed) {
                        markPagePainted()
                        chainState.markCommitted()
                        watchdog.onEvent(
                            WebContainerWatchdogEvent.PageCommitted(loadGeneration),
                            nowMs(),
                        )
                        events.onLoadCommit()
                    }
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
                                nowMs(),
                            )
                        handleWatchdogAction(action)
                        if (lastSuccessfulUrl != currentUrl) {
                            lastSuccessfulUrl = currentUrl
                            events.onLoadSuccess()
                        }
                        if (parkedForKeepAlive) {
                            WebContainerAndroidKeepAlive.onParkedLoadFinished(this@WebContainerAndroidView)
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

                override fun doUpdateVisitedHistory(
                    view: WebView?,
                    url: String?,
                    isReload: Boolean,
                ) {
                    if (destroyed) return
                    super.doUpdateVisitedHistory(view, url, isReload)
                    val target = url.orEmpty()
                    if (target.isBlank() || target.equals(ABOUT_BLANK_URL, ignoreCase = true)) return
                    val scheme = extractUrlScheme(target) ?: return
                    val allowedSchemes = config.navigationPolicy.normalizedAllowedSchemes
                    if (allowedSchemes.isNotEmpty() && scheme !in allowedSchemes) return
                    val locked = config.navigationPolicy.lockedMainFrameOrigin
                    if (locked != null && parseWebContainerOrigin(target) != locked) return
                    if (!config.navigationPolicy.allowNavigation && !isSameDocumentIgnoringFragment(config.url.trim(), target)) {
                        return
                    }
                    currentUrl = target
                    currentTitle = view?.title.orEmpty().ifBlank { currentTitle }
                    events.onUrlChange(target)
                    notifyNavigationState()
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
                        lastSuccessfulUrl = ""
                        currentLoading = false
                        val reason =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                error?.description?.toString()?.takeIf { it.isNotBlank() } ?: "webview_load_failed"
                            } else {
                                "webview_load_failed"
                            }
                        watchdog.onEvent(
                            WebContainerWatchdogEvent.LoadFailed(loadGeneration, reason),
                            nowMs(),
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
                    lastSuccessfulUrl = ""
                    currentLoading = false
                    val reason = "ssl_error_${error?.primaryError ?: -1}"
                    watchdog.onEvent(
                        WebContainerWatchdogEvent.LoadFailed(loadGeneration, reason),
                        nowMs(),
                    )
                    events.onLoadFailure(reason)
                    notifyNavigationState()
                    if (parkedForKeepAlive) {
                        WebContainerAndroidKeepAlive.dropIfSame(this@WebContainerAndroidView)
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (destroyed || request?.isForMainFrame != true) return
                    val statusCode = errorResponse?.statusCode ?: -1
                    if (statusCode >= 400) {
                        mainFrameLoadFailed = true
                        lastSuccessfulUrl = ""
                        watchdog.onEvent(
                            WebContainerWatchdogEvent.LoadFailed(loadGeneration, "http_$statusCode"),
                            nowMs(),
                        )
                        if (parkedForKeepAlive) {
                            WebContainerAndroidKeepAlive.dropIfSame(this@WebContainerAndroidView)
                        }
                    }
                }
            }
        webView.setDownloadListener(
            DownloadListener { url, _, contentDisposition, mimeType, _ ->
                if (destroyed) return@DownloadListener
                if (events.onDownloadRequested == null) {
                    notifyNavigationBlocked(url.orEmpty(), "download_unhandled")
                    return@DownloadListener
                }
                startPageDownload(url.orEmpty(), contentDisposition, mimeType)
            },
        )
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

    private fun isForegroundVisibleAndActive(configuredWebView: WebView): Boolean =
        !destroyed &&
            !parkedForKeepAlive &&
            configuredWebView === webView &&
            isAttachedToWindow &&
            lastWindowVisibility == View.VISIBLE

    private fun activeTransportProfileName(): String? =
        when (val scope = isolationScope) {
            WebIsolationScope.Default -> null
            WebIsolationScope.Ephemeral -> activeEphemeralProfileName
            is WebIsolationScope.Named -> AndroidWebContainerProfileStore.profileNameFor(scope)
        }

    internal fun handleAndroidNewWindowUrl(
        configuredWebView: WebView,
        rawUrl: String,
    ) {
        if (destroyed || parkedForKeepAlive || isolatedProfileUnavailable || configuredWebView !== webView) return
        val target = rawUrl.trim()
        if (target.isEmpty() || target.equals(ABOUT_BLANK_URL, ignoreCase = true)) return
        val budget = config.navigationPolicy.requestHeaderRule?.maxSameOriginReissues ?: 0
        chainState.startChain(
            carriesHeader = false,
            reissueBudget = budget,
            starter = WebNavigationStarter.Link,
        )
        val decision =
            evaluateMainFrameNavigation(
                targetUrl = target,
                initialUrl = config.url,
                policy = config.navigationPolicy,
                method = "GET",
                requestCarriesScopedHeader = false,
                chainState = chainState,
                isUserGesture = true,
                isRedirect = false,
                catchesMainFrameNavigation = events.catchesMainFrameNavigation,
                routesMainFrameNavigation = events.routesMainFrameNavigation,
            )
        when (decision) {
            WebNavigationDecision.Allow -> configuredWebView.loadUrl(target)
            is WebNavigationDecision.ReissueWithHeader ->
                configuredWebView.loadUrl(decision.url, mapOf(decision.headerName to decision.headerValue))
            is WebNavigationDecision.ReissueWithoutHeader -> configuredWebView.loadUrl(decision.url)
            is WebNavigationDecision.InterceptedByHost -> Unit
            is WebNavigationDecision.Block -> notifyNavigationBlocked(decision.url, decision.reason)
        }
    }

    private fun handleAndroidJsDialog(
        configuredWebView: WebView,
        kind: WebJsDialogKind,
        url: String?,
        message: String?,
        defaultPromptText: String?,
        onConfirm: (String?) -> Unit,
        onCancel: () -> Unit,
    ): Boolean {
        val visibleAndActive = isForegroundVisibleAndActive(configuredWebView)
        val handler = events.onJsDialog
        if (!config.allowJsDialogs && handler == null) {
            if (!visibleAndActive) {
                runCatching { onCancel() }
                return true
            }
            return false
        }
        if (!isWebJsDialogPermitted(config.allowJsDialogs, visibleAndActive, handler != null)) {
            runCatching { onCancel() }
            return true
        }
        val originUrl = url?.trim()?.takeIf { it.isNotEmpty() } ?: currentUrl
        var requestRef: WebJsDialogRequest? = null
        val request =
            WebJsDialogRequest(
                kind = kind,
                originUrl = originUrl,
                message = message,
                defaultPromptText = defaultPromptText,
                onSettled = { requestRef?.let(jsDialogTracker::clearIfSame) },
                onResult = { dialogResult ->
                    val deliver = {
                        runCatching {
                            when (dialogResult) {
                                WebJsDialogResult.AlertDismissed -> onConfirm(null)
                                is WebJsDialogResult.Confirm -> if (dialogResult.confirmed) onConfirm(null) else onCancel()
                                is WebJsDialogResult.Prompt ->
                                    if (dialogResult.value != null) onConfirm(dialogResult.value) else onCancel()
                            }
                        }
                        Unit
                    }
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        deliver()
                    } else {
                        mainHandler.post { deliver() }
                    }
                },
            )
        requestRef = request
        jsDialogTracker.begin(request)
        val presented = runCatching { handler!!.invoke(request) }.isSuccess
        if (!presented) {
            request.cancel()
        }
        return true
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
            if (defaultOriginSet.isNotEmpty()) {
                registerDocumentStartScript(
                    JsbBootstrapScripts.buildOfflineBundleScript(bundle, allowedOrigins = defaultOriginSet),
                    defaultOriginStrings,
                )
            }
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

    private fun rebuildJsbDispatcher() {
        jsbDispatcher?.cancelInFlight("jsb_rebound")
        val policy = config.jsbPolicy
        jsbDispatcher =
            if (!destroyed && !parkedForKeepAlive && config.allowScripts && policy.isEffectivelyEnabled) {
                JsbDispatcher(
                    policy = policy,
                    registry = jsbRegistry,
                    scope = hostScope,
                    surfaceId = config.surfaceId,
                    isolationScope = isolationScope,
                    telemetrySinkProvider = { telemetrySink },
                )
            } else {
                null
            }
    }

    private fun rebindJsbBridge() {
        val policy = config.jsbPolicy
        if (!config.allowScripts || !policy.isEffectivelyEnabled) {
            removeJsbBridge()
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            removeJsbBridge()
            return
        }
        val allowedOriginRules = policy.allowedOrigins.mapTo(LinkedHashSet()) { it.asOriginString() }
        if (activeJsbPortName == policy.portName && activeJsbOriginRules == allowedOriginRules) {
            rebuildJsbDispatcher()
            return
        }
        removeJsbBridge()
        rebuildJsbDispatcher()
        val listener =
            WebViewCompat.WebMessageListener { view, message, sourceOrigin, isMainFrame, replyProxy ->
                if (destroyed || parkedForKeepAlive) {
                    runCatching {
                        replyProxy.postMessage(
                            JsbWireCodec.encodeResponse(
                                null,
                                JsbResult.Failure(JsbErrorCode.BRIDGE_DISABLED, "Container is inactive"),
                            ),
                        )
                    }
                    return@WebMessageListener
                }
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
                val dispatcher = jsbDispatcher
                if (dispatcher == null) {
                    val quickId = JsbWireCodec.extractBestEffortCallId(raw)
                    runCatching {
                        replyProxy.postMessage(
                            JsbWireCodec.encodeResponse(
                                quickId,
                                JsbResult.Failure(JsbErrorCode.BRIDGE_DISABLED, "JSB disabled"),
                            ),
                        )
                    }
                    return@WebMessageListener
                }
                val activeUrl = view.url.orEmpty()
                val rawOrigin = parseWebContainerOrigin(sourceOrigin.toString())
                val fencedDocumentAllowed =
                    config.navigationPolicy.allowNavigation ||
                        (activeUrl.isNotEmpty() && isSameDocumentIgnoringFragment(config.url.trim(), activeUrl))
                val verifiedOrigin = if (isMainFrame && !fencedDocumentAllowed) null else rawOrigin
                val docUrl = activeUrl.ifBlank { currentUrl }
                dispatcher.dispatch(
                    rawPayload = raw,
                    origin = verifiedOrigin,
                    documentUrl = docUrl,
                    isMainFrame = isMainFrame,
                ) { replyJson ->
                    if (!destroyed && !parkedForKeepAlive) {
                        runCatching { replyProxy.postMessage(replyJson) }
                    }
                }
            }
        runCatching {
            WebViewCompat.addWebMessageListener(webView, policy.portName, allowedOriginRules, listener)
            activeJsbPortName = policy.portName
            activeJsbOriginRules = allowedOriginRules
        }
    }

    private fun removeJsbBridge() {
        jsbDispatcher?.cancelInFlight("bridge_rebound")
        jsbDispatcher = null
        activeJsbOriginRules = emptySet()
        val port = activeJsbPortName ?: return
        activeJsbPortName = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            runCatching { WebViewCompat.removeWebMessageListener(webView, port) }
        }
    }

    private fun resolveDesiredDownloadOrigin(cfg: WebContainerConfig = config): String? {
        val shouldInstall =
            cfg.allowScripts &&
                (events.onDownloadRequested != null ||
                    !cfg.keepAliveKey.isNullOrBlank() ||
                    cfg.navigationPolicy.lockedMainFrameOrigin != null)
        if (!shouldInstall) return null
        val candidate = parseWebContainerOrigin(cfg.url) ?: return null
        if (candidate.scheme != "http" && candidate.scheme != "https") return null
        val locked = cfg.navigationPolicy.lockedMainFrameOrigin
        if (locked != null && candidate != locked) return null
        return candidate.asOriginString()
    }

    private fun rebindDownloadBridge() {
        val originRule = resolveDesiredDownloadOrigin()
        if (originRule == null) {
            removeDownloadBridge()
            return
        }
        if (downloadBridgeOrigin == originRule) return
        removeDownloadBridge()
        if (
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            return
        }
        runCatching {
            val listener =
                WebViewCompat.WebMessageListener { _, message, sourceOrigin, isMainFrame, _ ->
                    if (destroyed || parkedForKeepAlive || events.onDownloadRequested == null || !isMainFrame) {
                        return@WebMessageListener
                    }
                    val normalizedSource = parseWebContainerOrigin(sourceOrigin.toString())?.asOriginString()
                    if (normalizedSource != originRule) return@WebMessageListener
                    if (message.type != WebMessageCompat.TYPE_STRING) return@WebMessageListener
                    val raw = runCatching { message.data }.getOrNull() ?: return@WebMessageListener
                    handleDownloadMessage(raw)
                }
            WebViewCompat.addWebMessageListener(
                webView,
                WEB_CONTAINER_DOWNLOAD_PORT_NAME,
                setOf(originRule),
                listener,
            )
            downloadBridgeOrigin = originRule
            downloadNameScriptHandler =
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    WEB_CONTAINER_DOWNLOAD_NAME_HOOK_SCRIPT,
                    setOf(originRule),
                )
        }
    }

    private fun removeDownloadBridge() {
        if (downloadBridgeOrigin != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            runCatching { WebViewCompat.removeWebMessageListener(webView, WEB_CONTAINER_DOWNLOAD_PORT_NAME) }
        }
        downloadBridgeOrigin = null
        runCatching { downloadNameScriptHandler?.remove() }
        downloadNameScriptHandler = null
        pendingBlobDownloads.clear()
    }

    private fun startPageDownload(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        val report = events.onDownloadRequested ?: return
        val pageOrigin = parseWebContainerOrigin(currentUrl.ifBlank { loadedSrc })
        val liveOrigin = parseWebContainerOrigin(webView.url.orEmpty())
        val locked = config.navigationPolicy.lockedMainFrameOrigin
        val trustedPage =
            pageOrigin != null &&
                liveOrigin != null &&
                liveOrigin == pageOrigin &&
                (locked == null || liveOrigin == locked)
        val lower = url.lowercase()
        when {
            lower.startsWith("blob:") || lower.startsWith("data:") -> {
                if (
                    !trustedPage ||
                    !config.allowScripts ||
                    downloadBridgeOrigin == null ||
                    liveOrigin.asOriginString() != downloadBridgeOrigin
                ) {
                    report(WebDownloadResult.Failed("no_port"))
                    return
                }
                val token = "d" + Random.nextLong().toULong().toString(16)
                if (pendingBlobDownloads.size >= 4) {
                    pendingBlobDownloads.remove(pendingBlobDownloads.keys.first())
                }
                pendingBlobDownloads[token] = WebContainerBlobDownloadAssembler(token, url)
                webView.evaluateJavascript(buildWebContainerBlobReadScript(url, token), null)
            }
            (lower.startsWith("https:") || lower.startsWith("http:")) &&
                trustedPage && parseWebContainerOrigin(url) == liveOrigin -> {
                if (!config.allowScripts) {
                    report(WebDownloadResult.Url(url, null, contentDisposition, mimeType))
                    return
                }
                val urlLiteral = JsonPrimitive(url).toString()
                val queryScript =
                    "(function(){var n=window.__raftWcDownloadNames||window.__raftDownloadNames;return (n&&n[$urlLiteral])||'';})()"
                webView.evaluateJavascript(queryScript) { value ->
                    if (destroyed) return@evaluateJavascript
                    val pageName =
                        value?.let { raw ->
                            runCatching {
                                (downloadJson.parseToJsonElement(raw) as? JsonPrimitive)
                                    ?.takeIf { it.isString }
                                    ?.contentOrNull
                                    ?.takeIf { it.isNotBlank() }
                            }.getOrNull()
                        }
                    events.onDownloadRequested?.invoke(
                        WebDownloadResult.Url(url, pageName, contentDisposition, mimeType),
                    )
                }
            }
            else -> notifyNavigationBlocked(url, "download_off_origin")
        }
    }

    private fun handleDownloadMessage(raw: String) {
        val root = runCatching { downloadJson.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return
        val token = (root["t"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return
        val assembler = pendingBlobDownloads[token] ?: return
        val result = assembler.accept(root) ?: return
        pendingBlobDownloads.remove(token)
        events.onDownloadRequested?.invoke(result)
    }

    private fun applyDesktopModeScale() {
        if (destroyed) return
        if (!config.desktopViewportMode) {
            if (desktopModeScaleApplied) {
                webView.setInitialScale(0)
                webView.zoomOut()
                desktopModeScaleApplied = false
            }
            return
        }
        val viewWidthPx = webView.width
        if (viewWidthPx <= 0) {
            if (!desktopModeScaleRetryScheduled) {
                desktopModeScaleRetryScheduled = true
                webView.post {
                    desktopModeScaleRetryScheduled = false
                    applyDesktopModeScale()
                }
            }
            return
        }
        val density = resources.displayMetrics.density
        if (density <= 0f) return
        val widthDp = viewWidthPx / density
        val percent = ((widthDp * 100.0) / 1280.0).roundToInt().coerceIn(20, 100)
        webView.setInitialScale(percent)
        desktopModeScaleApplied = true
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
                if (!config.navigationPolicy.allowNavigation && !isSameDocumentIgnoringFragment(config.url.trim(), activeUrl)) return
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
                    nowMs(),
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
                    nowMs(),
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
                runCatching {
                    telemetrySink?.onEvent(
                        WebContainerTelemetryEvent.WatchdogTerminalError(
                            surfaceId = config.surfaceId,
                            reason = action.reason,
                            engine = ANDROID_WEBVIEW_ENGINE,
                        ),
                    )
                }
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
