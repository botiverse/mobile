@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package build.raft.webcontainer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import build.raft.webcontainer.jsb.JsbBootstrapScripts
import build.raft.webcontainer.jsb.JsbDispatcher
import build.raft.webcontainer.jsb.JsbErrorCode
import build.raft.webcontainer.jsb.JsbRegistry
import build.raft.webcontainer.jsb.JsbResult
import build.raft.webcontainer.jsb.JsbWireCodec
import build.raft.webcontainer.pool.WEB_CONTAINER_SPARE_BLANK_URL
import build.raft.webcontainer.pool.WebContainerTelemetryEvent
import build.raft.webcontainer.pool.WebContainerTelemetrySink
import build.raft.webcontainer.pool.isIosWarmSpareEligible
import build.raft.webcontainer.pool.shouldIgnoreWarmSpareBlankNavigation
import build.raft.webcontainer.resilience.WebContainerWatchdog
import build.raft.webcontainer.resilience.WebContainerWatchdogAction
import build.raft.webcontainer.resilience.WebContainerWatchdogEvent
import build.raft.webcontainer.resilience.WebNavigationStateRelay
import build.raft.webcontainer.resource.WebResourceInterceptor
import build.raft.webcontainer.resource.WebResourceRequest
import build.raft.webcontainer.resource.isValidIosCustomScheme
import kotlinx.cinterop.CEnum
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.interpretObjCPointerOrNull
import kotlinx.cinterop.invoke
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.Foundation.*
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.WebKit.WKAudiovisualMediaTypeAll
import platform.WebKit.WKBackForwardListItem
import platform.WebKit.WKContentWorld
import platform.WebKit.WKDownload
import platform.WebKit.WKDownloadDelegateProtocol
import platform.WebKit.WKFrameInfo
import platform.WebKit.WKMediaCaptureType
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigationResponse
import platform.WebKit.WKNavigationResponsePolicy
import platform.WebKit.WKPermissionDecision
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerWithReplyProtocol
import platform.WebKit.WKSecurityOrigin
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKURLSchemeHandlerProtocol
import platform.WebKit.WKURLSchemeTaskProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebpagePreferences
import platform.WebKit.WKWindowFeatures
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.objc.class_addMethod
import platform.objc.class_getInstanceMethod
import platform.objc.class_getSuperclass
import platform.objc.method_getImplementation
import platform.objc.method_getTypeEncoding
import platform.objc.object_getClass
import platform.objc.sel_registerName
import platform.posix.memcpy

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
    UIKitView(
        factory = {
            WebContainerIosContainerView(
                events = events,
                jsbRegistry = jsbRegistry,
                resourceInterceptor = resourceInterceptor,
                telemetrySink = telemetrySink,
            ).also { it.host.applyInitialBackground(config.backgroundArgb) }
        },
        modifier = modifier,
        update = { view ->
            view.host.events = events
            view.host.telemetrySink = telemetrySink
            view.host.updateRuntimeHooks(jsbRegistry, resourceInterceptor)
            view.host.applyConfig(config)
            view.host.bindController(controller)
        },
        onRelease = { view -> view.host.dispose() },
    )
}

/** Pointer-identity equality for Objective-C objects returned across Kotlin/Native bridges. */
internal fun isSameObjCObject(a: Any?, b: Any?): Boolean =
    a != null && b != null && a.objcPtr() == b.objcPtr()

private const val IOS_POLL_INTERVAL_SECONDS = 0.25
private const val IOS_WATCHDOG_ONLY_INTERVAL_SECONDS = 1.0
private const val IOS_REVEAL_CAP_SECONDS = 1.5

internal class WebContainerIosContainerView(
    events: WebContainerEvents,
    jsbRegistry: JsbRegistry,
    resourceInterceptor: WebResourceInterceptor?,
    telemetrySink: WebContainerTelemetrySink?,
) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    val host =
        WebContainerIosHost(
            container = this,
            events = events,
            jsbRegistry = jsbRegistry,
            resourceInterceptor = resourceInterceptor,
            telemetrySink = telemetrySink,
        )

    override fun layoutSubviews() {
        super.layoutSubviews()
        host.layout()
    }

    override fun didMoveToWindow() {
        super.didMoveToWindow()
        host.onWindowAttachmentChanged(attached = window != null)
    }
}

/**
 * iOS `WKWebView` container host with:
 * - Dual `WKContentWorld` isolation: `window.raftBridge` (`WKScriptMessageHandlerWithReply`) in
 *   `WKContentWorld.pageWorld` with strict `frameInfo.securityOrigin` + `WKWebView.URL` matching
 *   (fail closed on opaque/null frame origins); internal DOM probes in
 *   `WKContentWorld.defaultClientWorld`.
 * - Origin-fenced document-start user scripts and offline bundle scripts (closing `WKUserScript`'s
 *   lack of native origin filtering).
 * - Custom-scheme-only `WKURLSchemeHandler` (strictly rejecting `http`/`https`).
 * - Host-cancelled navigation tracking (`WebKitErrorDomain:102` / `-999` swallowing +
 *   `pendingHostLoadUrl` de-duplication).
 * - Rolling-window `webViewWebContentProcessDidTerminate` circuit breaker via [WebContainerWatchdog].
 */
internal class WebContainerIosHost(
    private val container: UIView,
    var events: WebContainerEvents,
    private var jsbRegistry: JsbRegistry,
    private var resourceInterceptor: WebResourceInterceptor?,
    var telemetrySink: WebContainerTelemetrySink?,
) {
    private var webView: WKWebView? = null
    private var config: WebContainerConfig? = null
    private var registeredActiveScope: WebIsolationScope? = null
    private var disposed: Boolean = false

    private var currentUrl: String = ""
    private var currentTitle: String = ""
    private var currentProgress: Int = 0
    private var currentLoading: Boolean = false
    private var loadGeneration: Int = 0
    private var lastSuccessfulUrl: String = ""
    private var pagePainted: Boolean = false
    private var pageRevealed: Boolean = false
    private var activeJsbPortName: String? = null
    private var pendingHostLoadUrl: String? = null
    private var pendingHostLoadIsReissue: Boolean = false
    private var hostCancelledUrl: String? = null

    private val chainState = WebNavigationChainState()
    private val navigationStateRelay = WebNavigationStateRelay()
    private val watchdog = WebContainerWatchdog()
    private val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var jsbDispatcher: JsbDispatcher? = null
    private var boundController: WebContainerController? = null
    private val jsDialogTracker = WebJsDialogTracker()
    private var stateTimer: NSTimer? = null
    private var revealTimer: NSTimer? = null

    private val navDelegate = WebContainerIosNavigationDelegate(this)
    private val jsbReplyHandler = WebContainerIosJsbReplyHandler(this)
    private val kvoObserver =
        WebContainerIosKvoObserver { observed ->
            if (!disposed && isSameObjCObject(observed, webView)) {
                syncObservedState(observed)
            }
        }
    private var usingSpare: Boolean = false
    private var spareCommitted: Boolean = false
    private var activeSchemeHandler: WebContainerIosSchemeHandler? = null
    private val activeDownloads = mutableListOf<WebContainerIosDownloadDelegate>()
    private var downloadCancelledUrl: String? = null
    private var appInBackground: Boolean =
        UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateBackground
    private var windowAttached: Boolean = container.window != null
    private val lifecycleObservers = mutableListOf<NSObjectProtocol>()

    init {
        container.backgroundColor = UIColor.whiteColor
        val center = NSNotificationCenter.defaultCenter
        lifecycleObservers +=
            center.addObserverForName(
                name = UIApplicationDidEnterBackgroundNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { _ ->
                if (!disposed) {
                    appInBackground = true
                    jsDialogTracker.cancelActive()
                    syncWatchdogLifecycle()
                }
            }
        lifecycleObservers +=
            center.addObserverForName(
                name = UIApplicationWillEnterForegroundNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { _ ->
                if (!disposed) {
                    appInBackground = false
                    syncWatchdogLifecycle(syncStateOnResume = true)
                }
            }
        syncWatchdogLifecycle()
    }

    fun applyInitialBackground(argb: Int) {
        if (argb != 0 && config == null) {
            container.backgroundColor = iosColorFromArgb(argb)
        }
    }

    fun updateRuntimeHooks(
        nextRegistry: JsbRegistry,
        nextInterceptor: WebResourceInterceptor?,
    ) {
        resourceInterceptor = nextInterceptor
        activeSchemeHandler?.interceptor = nextInterceptor
        if (jsbRegistry !== nextRegistry) {
            jsbRegistry = nextRegistry
            config?.let { rebuildJsbDispatcher(it) }
        }
    }

    fun bindController(controller: WebContainerController) {
        if (boundController === controller) return
        boundController?.unbindDispatcher(this)
        boundController = controller
        controller.bindDispatcher(this) { command -> executeCommand(command) }
    }

    fun layout() {
        webView?.setFrame(container.bounds)
    }

    fun onWindowAttachmentChanged(attached: Boolean) {
        if (disposed) return
        windowAttached = attached
        if (!attached) {
            jsDialogTracker.cancelActive()
        }
        syncWatchdogLifecycle(syncStateOnResume = attached)
    }

    fun applyConfig(next: WebContainerConfig) {
        if (disposed) return
        val prev = config
        val needsRebuild =
            prev == null ||
                prev.isolationScope != next.isolationScope ||
                prev.allowScripts != next.allowScripts ||
                prev.allowPinchZoom != next.allowPinchZoom ||
                prev.desktopViewportMode != next.desktopViewportMode ||
                prev.jsbPolicy != next.jsbPolicy ||
                prev.documentStartScripts != next.documentStartScripts ||
                prev.offlineBundle != next.offlineBundle ||
                (usingSpare && next.allowsBackForwardNavigationGestures) ||
                (!pagePainted && (prev.backgroundArgb != next.backgroundArgb || prev.isDarkTheme != next.isDarkTheme))
        val backgroundChanged =
            prev == null ||
                prev.backgroundArgb != next.backgroundArgb ||
                prev.persistBackgroundAfterPaint != next.persistBackgroundAfterPaint
        val urlChanged = prev == null || prev.url.trim() != next.url.trim()

        config = next
        if (!next.allowJsDialogs) {
            jsDialogTracker.cancelActive()
        }
        chainState.configureBudget(next.navigationPolicy.requestHeaderRule?.maxSameOriginReissues ?: 0)
        webView?.allowsBackForwardNavigationGestures = next.allowsBackForwardNavigationGestures
        (webView as? WebContainerIosWebView)?.showsFormAccessoryBar = next.showsFormAccessoryBar

        if (needsRebuild) {
            // Tear down existing WKWebView BEFORE switching registered active scope so deferred
            // store deletion on the previous scope never fires while the old WKWebView is alive.
            rebuildWebView(next, restoreUrl = null)
        } else {
            registerActiveScope(next.isolationScope)
            if (backgroundChanged) {
                applyBackgroundVisuals(next)
            }
            if (urlChanged) {
                loadUrlInternal(next, overrideUrl = null)
            }
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        jsDialogTracker.cancelActive()
        removeLifecycleObservers()
        boundController?.unbindDispatcher(this)
        boundController = null
        jsbDispatcher?.cancelInFlight("container_disposed")
        tearDownWebView()
        hostScope.cancel()
        unregisterActiveScope()
    }

    private fun removeLifecycleObservers() {
        val center = NSNotificationCenter.defaultCenter
        for (observer in lifecycleObservers) {
            center.removeObserver(observer)
        }
        lifecycleObservers.clear()
    }

    private fun registerActiveScope(scope: WebIsolationScope) {
        if (registeredActiveScope == scope) return
        unregisterActiveScope()
        registeredActiveScope = scope
        IosWebContainerDataStoreRegistry.scopeManager.registerActive(scope)
    }

    private fun unregisterActiveScope() {
        val current = registeredActiveScope ?: return
        registeredActiveScope = null
        IosWebContainerDataStoreRegistry.scopeManager.unregisterActive(current)
    }

    private fun tearDownWebView() {
        jsDialogTracker.cancelActive()
        stopPolling()
        kvoObserver.detach()
        revealTimer?.invalidate()
        revealTimer = null
        pendingHostLoadUrl = null
        pendingHostLoadIsReissue = false
        hostCancelledUrl = null
        downloadCancelledUrl = null
        usingSpare = false
        spareCommitted = false
        for (download in activeDownloads.toList()) {
            download.cancelAndCleanup()
        }
        activeDownloads.clear()
        activeSchemeHandler?.invalidate()
        activeSchemeHandler = null
        val previous = webView ?: return
        webView = null
        previous.navigationDelegate = null
        previous.UIDelegate = null
        previous.stopLoading()
        activeJsbPortName?.let { port ->
            previous.configuration.userContentController.removeScriptMessageHandlerForName(port, WKContentWorld.pageWorld)
        }
        activeJsbPortName = null
        previous.removeFromSuperview()
    }

    private fun rebuildJsbDispatcher(cfg: WebContainerConfig) {
        jsbDispatcher?.cancelInFlight("jsb_rebound")
        jsbDispatcher =
            if (cfg.allowScripts && cfg.jsbPolicy.isEffectivelyEnabled) {
                JsbDispatcher(
                    policy = cfg.jsbPolicy,
                    registry = jsbRegistry,
                    scope = hostScope,
                    surfaceId = cfg.surfaceId,
                    isolationScope = cfg.isolationScope,
                    telemetrySinkProvider = { telemetrySink },
                )
            } else {
                null
            }
    }

    private fun rebuildWebView(
        cfg: WebContainerConfig,
        restoreUrl: String?,
    ) {
        val hadPreviousView = webView != null
        tearDownWebView()
        registerActiveScope(cfg.isolationScope)
        rebuildJsbDispatcher(cfg)

        val defaultOriginSet =
            buildSet {
                parseWebContainerOrigin(cfg.url)?.let(::add)
                cfg.navigationPolicy.lockedMainFrameOrigin?.let(::add)
            }

        val customScheme = cfg.offlineBundle?.iosCustomScheme?.takeIf(::isValidIosCustomScheme)
        val ignoresScaleLimits = cfg.allowPinchZoom || cfg.desktopViewportMode
        val spare =
            if (
                isIosWarmSpareEligible(
                    isolationScope = cfg.isolationScope,
                    allowScripts = cfg.allowScripts,
                    hasCustomScheme = customScheme != null,
                    allowsBackForwardNavigationGestures = cfg.allowsBackForwardNavigationGestures,
                    isRecoveryOrRebuild = restoreUrl != null || hadPreviousView,
                )
            ) {
                WebContainerIosSparePool.take(
                    ignoresViewportScaleLimits = ignoresScaleLimits,
                    telemetrySink = telemetrySink,
                )
            } else {
                null
            }

        val userContentController =
            spare?.configuration?.userContentController?.also { it.removeAllUserScripts() } ?: WKUserContentController()
        if (cfg.allowScripts && cfg.jsbPolicy.isEffectivelyEnabled) {
            val portName = cfg.jsbPolicy.portName
            // Web-accessible bridge lives in WKContentWorld.pageWorld so page JS can call window.raftBridge;
            // internal probes execute strictly in WKContentWorld.defaultClientWorld.
            userContentController.addScriptMessageHandlerWithReply(jsbReplyHandler, WKContentWorld.pageWorld, portName)
            activeJsbPortName = portName
            userContentController.addUserScript(
                WKUserScript(
                    source = JsbBootstrapScripts.buildPageBridgeScript(portName, cfg.jsbPolicy.allowedOrigins),
                    injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                    forMainFrameOnly = true,
                ),
            )
        }
        if (cfg.allowScripts && cfg.backgroundArgb != 0) {
            userContentController.addUserScript(
                WKUserScript(
                    source = JsbBootstrapScripts.buildFirstPaintThemeScript(cfg.backgroundArgb, cfg.isDarkTheme),
                    injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                    forMainFrameOnly = true,
                ),
            )
        }
        if (cfg.allowScripts && cfg.desktopViewportMode) {
            userContentController.addUserScript(
                WKUserScript(
                    source = JsbBootstrapScripts.DesktopViewportMetaScript,
                    injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                    forMainFrameOnly = true,
                ),
            )
        }
        if (cfg.allowScripts) {
            cfg.offlineBundle?.let { bundle ->
                val bundleOrigins = defaultOriginSet
                if (bundleOrigins.isNotEmpty()) {
                    val script = JsbBootstrapScripts.buildOfflineBundleScript(bundle, allowedOrigins = bundleOrigins)
                    if (script.isNotBlank()) {
                        userContentController.addUserScript(
                            WKUserScript(
                                source = script,
                                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                                forMainFrameOnly = true,
                            ),
                        )
                    }
                }
            }
            for (script in cfg.documentStartScripts) {
                val allowedOrigins = script.allowedOrigins.ifEmpty { defaultOriginSet }
                // WKUserScript has no native origin filter: fail closed if no allowed origins exist,
                // and always wrap in a runtime origin + frame guard.
                if (allowedOrigins.isNotEmpty() && script.source.isNotBlank()) {
                    val wrapped =
                        JsbBootstrapScripts.wrapDocumentStartScript(
                            source = script.source,
                            allowedOrigins = allowedOrigins,
                            mainFrameOnly = script.mainFrameOnly,
                        )
                    userContentController.addUserScript(
                        WKUserScript(
                            source = wrapped,
                            injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                            forMainFrameOnly = script.mainFrameOnly,
                        ),
                    )
                }
            }
        }

        val nextView =
            if (spare != null) {
                spare.setFrame(container.bounds)
                usingSpare = true
                spare
            } else {
                val configuration = WKWebViewConfiguration()
                configuration.websiteDataStore = IosWebContainerDataStoreRegistry.dataStoreFor(cfg.isolationScope)
                configuration.userContentController = userContentController
                configuration.allowsInlineMediaPlayback = true
                configuration.ignoresViewportScaleLimits = ignoresScaleLimits
                configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
                configuration.mediaTypesRequiringUserActionForPlayback = WKAudiovisualMediaTypeAll
                val pagePrefs = WKWebpagePreferences()
                pagePrefs.allowsContentJavaScript = cfg.allowScripts
                configuration.defaultWebpagePreferences = pagePrefs
                if (customScheme != null) {
                    val handler = WebContainerIosSchemeHandler(resourceInterceptor)
                    activeSchemeHandler = handler
                    configuration.setURLSchemeHandler(handler, forURLScheme = customScheme.trim().lowercase())
                }
                usingSpare = false
                WebContainerIosWebView(frame = container.bounds, configuration = configuration)
            }

        nextView.showsFormAccessoryBar = cfg.showsFormAccessoryBar
        nextView.navigationDelegate = navDelegate
        nextView.UIDelegate = navDelegate
        nextView.allowsBackForwardNavigationGestures = cfg.allowsBackForwardNavigationGestures
        nextView.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        webView = nextView
        pagePainted = false
        pageRevealed = false
        spareCommitted = false
        currentProgress = 0
        currentLoading = false
        currentTitle = ""
        lastSuccessfulUrl = ""
        chainState.reset(clearCommitted = true)
        navigationStateRelay.reset()
        // Attach KVO strictly after resetting per-view state/relay so initial observations stay clean.
        kvoObserver.attach(nextView)
        applyBackgroundVisuals(cfg)
        container.addSubview(nextView)
        syncWatchdogLifecycle()
        if (spare != null && cfg.backgroundArgb != 0) {
            nextView.evaluateJavaScript(
                JsbBootstrapScripts.buildBlankCanvasPaintScript(cfg.backgroundArgb, cfg.isDarkTheme),
                null,
                WKContentWorld.defaultClientWorld,
                null,
            )
        }
        if (spare != null) {
            // Non-blocking anti-zombie liveness probe in isolated defaultClientWorld: if iOS killed
            // the spare's WebContent process silently before any real document commits, seamlessly
            // rebuild a fresh view (once committed, normal contentProcessDidTerminate watchdog owns recovery).
            nextView.evaluateJavaScript("'1'", null, WKContentWorld.defaultClientWorld) { _, err ->
                if (disposed || spareCommitted || pagePainted || !isSameObjCObject(nextView, webView)) {
                    return@evaluateJavaScript
                }
                if (err != null && err.domain == "WKErrorDomain" && err.code == 2L) {
                    runCatching {
                        telemetrySink?.onEvent(
                            WebContainerTelemetryEvent.PoolEvent(
                                action = "ios_spare_zombie_rebuilt",
                                keepAliveKey = "ios_default_spare",
                                poolSizeAfter = 0,
                                engine = IOS_WKWEBVIEW_ENGINE,
                            ),
                        )
                    }
                    config?.let { activeCfg -> rebuildWebView(activeCfg, restoreUrl = null) }
                }
            }
        }
        loadUrlInternal(cfg, overrideUrl = restoreUrl)
    }

    private fun applyBackgroundVisuals(cfg: WebContainerConfig) {
        val view = webView
        val hasPlaceholder = cfg.backgroundArgb != 0
        val effectiveArgb =
            resolveWebContainerBackgroundArgb(
                backgroundArgb = cfg.backgroundArgb,
                painted = pagePainted,
                persistAfterPaint = cfg.persistBackgroundAfterPaint,
            )
        val color = iosColorFromArgb(effectiveArgb)
        container.backgroundColor = color
        if (view == null) return
        val showView = !hasPlaceholder || pageRevealed
        view.alpha = if (showView) 1.0 else 0.0
        view.opaque = pagePainted || !hasPlaceholder
        view.backgroundColor = if (view.opaque) color else UIColor.clearColor
        view.scrollView.backgroundColor = view.backgroundColor
        view.underPageBackgroundColor = color
    }

    private fun revealView() {
        val cfg = config ?: return
        if (pageRevealed) return
        pageRevealed = true
        revealTimer?.invalidate()
        revealTimer = null
        applyBackgroundVisuals(cfg)
    }

    private fun isWatchdogActive(): Boolean = !disposed && windowAttached && !appInBackground

    private fun syncWatchdogLifecycle(syncStateOnResume: Boolean = false) {
        if (disposed) {
            stopPolling()
            return
        }
        if (isWatchdogActive()) {
            watchdog.onEvent(WebContainerWatchdogEvent.Foregrounded, webContainerMonotonicMillis())
            val view = webView
            if (view != null) {
                if (stateTimer == null) {
                    startPolling(view)
                }
                if (syncStateOnResume) {
                    syncObservedState(view)
                }
            }
        } else {
            watchdog.onEvent(WebContainerWatchdogEvent.Backgrounded, webContainerMonotonicMillis())
            stopPolling()
        }
    }

    private fun startPolling(target: WKWebView) {
        stopPolling()
        if (!isWatchdogActive()) return
        val kvoActive = kvoObserver.isAttached
        val interval = if (kvoActive) IOS_WATCHDOG_ONLY_INTERVAL_SECONDS else IOS_POLL_INTERVAL_SECONDS
        stateTimer =
            NSTimer.scheduledTimerWithTimeInterval(interval, repeats = true) { _ ->
                if (!isWatchdogActive() || !isSameObjCObject(target, webView)) return@scheduledTimerWithTimeInterval
                if (!kvoActive) {
                    syncObservedState(target)
                }
                val action = watchdog.onEvent(WebContainerWatchdogEvent.Tick, webContainerMonotonicMillis())
                handleWatchdogAction(action)
            }
    }

    private fun stopPolling() {
        stateTimer?.invalidate()
        stateTimer = null
    }

    private fun syncObservedState(view: WKWebView) {
        val observedUrl = view.URL?.absoluteString
        if (!shouldIgnoreWarmSpareBlankNavigation(usingSpare, observedUrl, config?.url.orEmpty())) {
            currentUrl = observedUrl ?: currentUrl
        }
        currentTitle = view.title ?: ""
        currentProgress = (view.estimatedProgress * 100.0 + 0.5).toInt().coerceIn(0, 100)
        currentLoading = view.loading
        notifyNavigationState(force = false)
    }

    /** Excludes a warm spare's initial `about:blank` entry so Back on the first real page closes cleanly. */
    private fun canGoBack(view: WKWebView): Boolean {
        if (!view.canGoBack) return false
        if (!usingSpare) return true
        val backList = view.backForwardList.backList
        val first = backList.firstOrNull() as? WKBackForwardListItem
        return !(backList.size == 1 && first?.URL?.absoluteString == WEB_CONTAINER_SPARE_BLANK_URL)
    }

    private fun loadUrlInternal(
        cfg: WebContainerConfig,
        overrideUrl: String?,
    ) {
        val view = webView ?: return
        val target = (overrideUrl ?: cfg.url).trim()
        if (target.isEmpty()) {
            pendingHostLoadUrl = null
            pendingHostLoadIsReissue = false
            hostCancelledUrl = null
            currentUrl = ""
            currentLoading = false
            view.loadHTMLString("", baseURL = null)
            notifyNavigationState(force = true)
            return
        }
        val nsUrl = NSURL.URLWithString(target)
        if (nsUrl == null) {
            events.onLoadFailure("invalid_url")
            return
        }
        val resolvedTarget = nsUrl.absoluteString ?: target
        val decision =
            evaluateMainFrameNavigation(
                targetUrl = resolvedTarget,
                initialUrl = cfg.url,
                policy = cfg.navigationPolicy,
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
                val budget = cfg.navigationPolicy.requestHeaderRule?.maxSameOriginReissues ?: 0
                chainState.startChain(carriesHeader = true, reissueBudget = budget, starter = WebNavigationStarter.Launch)
                pendingHostLoadUrl = resolvedTarget
                pendingHostLoadIsReissue = false
                hostCancelledUrl = null
                view.loadRequest(buildRequest(nsUrl, cfg.cacheEnabled, decision.headerName to decision.headerValue))
            }
            else -> {
                val budget = cfg.navigationPolicy.requestHeaderRule?.maxSameOriginReissues ?: 0
                chainState.startChain(carriesHeader = false, reissueBudget = budget, starter = WebNavigationStarter.Launch)
                pendingHostLoadUrl = resolvedTarget
                pendingHostLoadIsReissue = false
                hostCancelledUrl = null
                view.loadRequest(buildRequest(nsUrl, cfg.cacheEnabled, null))
            }
        }
    }

    private fun buildRequest(
        url: NSURL,
        cacheEnabled: Boolean,
        header: Pair<String, String>?,
    ): NSMutableURLRequest {
        val cachePolicy =
            if (cacheEnabled) NSURLRequestUseProtocolCachePolicy else NSURLRequestReloadIgnoringLocalCacheData
        val request = NSMutableURLRequest.requestWithURL(url, cachePolicy = cachePolicy, timeoutInterval = 60.0)
        if (header != null) {
            request.setValue(header.second, forHTTPHeaderField = header.first)
        }
        return request
    }

    private fun executeCommand(command: WebContainerCommand) {
        if (disposed) return
        val view = webView ?: return
        val cfg = config ?: return
        when (command) {
            WebContainerCommand.GoBack -> if (canGoBack(view)) view.goBack()
            WebContainerCommand.GoForward -> if (view.canGoForward) view.goForward()
            WebContainerCommand.Reload -> view.reload()
            WebContainerCommand.Stop -> {
                view.stopLoading()
                currentLoading = false
                notifyNavigationState(force = false)
            }
            is WebContainerCommand.LoadUrl -> {
                val target = command.url.trim()
                val nsUrl = NSURL.URLWithString(target) ?: return
                val resolvedTarget = nsUrl.absoluteString ?: target
                val budget = cfg.navigationPolicy.requestHeaderRule?.maxSameOriginReissues ?: 0
                chainState.startChain(carriesHeader = false, reissueBudget = budget, starter = WebNavigationStarter.Page)
                val decision =
                    evaluateMainFrameNavigation(
                        targetUrl = resolvedTarget,
                        initialUrl = cfg.url,
                        policy = cfg.navigationPolicy,
                        method = "GET",
                        chainState = chainState,
                        catchesMainFrameNavigation = events.catchesMainFrameNavigation,
                        routesMainFrameNavigation = events.routesMainFrameNavigation,
                    )
                when (decision) {
                    WebNavigationDecision.Allow -> {
                        pendingHostLoadUrl = resolvedTarget
                        pendingHostLoadIsReissue = false
                        hostCancelledUrl = null
                        view.loadRequest(buildRequest(nsUrl, cfg.cacheEnabled, null))
                    }
                    is WebNavigationDecision.ReissueWithHeader -> {
                        pendingHostLoadUrl = resolvedTarget
                        pendingHostLoadIsReissue = false
                        hostCancelledUrl = null
                        view.loadRequest(buildRequest(nsUrl, cfg.cacheEnabled, decision.headerName to decision.headerValue))
                    }
                    is WebNavigationDecision.ReissueWithoutHeader -> {
                        pendingHostLoadUrl = resolvedTarget
                        pendingHostLoadIsReissue = false
                        hostCancelledUrl = null
                        view.loadRequest(buildRequest(nsUrl, cfg.cacheEnabled, null))
                    }
                    is WebNavigationDecision.Block -> notifyNavigationBlocked(decision.url, decision.reason)
                    is WebNavigationDecision.InterceptedByHost -> Unit
                }
            }
            is WebContainerCommand.EvaluateJavaScript -> {
                if (!cfg.allowScripts || command.script.isBlank()) return
                if (command.script.length > cfg.maxRuntimeJavaScriptLength) return
                val activeUrl = view.URL?.absoluteString.orEmpty()
                val activeOrigin = parseWebContainerOrigin(activeUrl) ?: return
                val locked = cfg.navigationPolicy.lockedMainFrameOrigin
                if (locked != null && activeOrigin != locked) return
                val initialOrigin = parseWebContainerOrigin(cfg.url)
                if (initialOrigin != null && activeOrigin != initialOrigin) return
                if (!cfg.navigationPolicy.allowNavigation && !isSameDocumentIgnoringFragment(cfg.url.trim(), activeUrl)) return
                view.evaluateJavaScript(command.script, completionHandler = null)
            }
            WebContainerCommand.ProbeContent -> probeDocumentContent(view, loadGeneration)
            is WebContainerCommand.EmitJsbEvent -> {
                val script = jsbDispatcher?.buildEventDispatchScript(command.name, command.payload) ?: return
                if (cfg.allowScripts) {
                    view.evaluateJavaScript(script, completionHandler = null)
                }
            }
        }
    }

    /** Runs the internal read-only DOM visibility probe strictly inside `WKContentWorld.defaultClientWorld`. */
    private fun probeDocumentContent(
        view: WKWebView,
        expectedGeneration: Int,
    ) {
        val cfg = config ?: return
        if (!cfg.allowScripts) {
            events.onContentProbe(null)
            val action =
                watchdog.onEvent(
                    WebContainerWatchdogEvent.DomProbeResult(expectedGeneration, null),
                    webContainerMonotonicMillis(),
                )
            handleWatchdogAction(action)
            return
        }
        view.evaluateJavaScript(
            JsbBootstrapScripts.ContentVisibilityProbeScript,
            null,
            WKContentWorld.defaultClientWorld,
        ) { result, _ ->
            if (disposed || !isSameObjCObject(view, webView) || expectedGeneration != loadGeneration) {
                return@evaluateJavaScript
            }
            val hasContent = JsbBootstrapScripts.decodeContentProbeResult(result as? String)
            events.onContentProbe(hasContent)
            val action =
                watchdog.onEvent(
                    WebContainerWatchdogEvent.DomProbeResult(expectedGeneration, hasContent),
                    webContainerMonotonicMillis(),
                )
            handleWatchdogAction(action)
        }
    }

    internal fun onJsbScriptMessage(
        controller: WKUserContentController,
        message: WKScriptMessage,
        replyHandler: (Any?, String?) -> Unit,
    ) {
        var settled = false
        val replyOnMainOnce: (String) -> Unit = { replyJson ->
            dispatch_async(dispatch_get_main_queue()) {
                if (!settled) {
                    settled = true
                    replyHandler(replyJson, null)
                }
            }
        }
        val raw = message.body as? String ?: ""
        val quickCallId = JsbWireCodec.extractBestEffortCallId(raw)
        val activeView = webView
        if (disposed || activeView == null || !isSameObjCObject(controller, activeView.configuration.userContentController)) {
            replyOnMainOnce(
                JsbWireCodec.encodeResponse(
                    quickCallId,
                    JsbResult.Failure(JsbErrorCode.BRIDGE_DISABLED, "Stale WebView"),
                ),
            )
            return
        }
        val dispatcher = jsbDispatcher
        if (dispatcher == null) {
            replyOnMainOnce(
                JsbWireCodec.encodeResponse(
                    quickCallId,
                    JsbResult.Failure(JsbErrorCode.BRIDGE_DISABLED, "JSB disabled"),
                ),
            )
            return
        }
        val frameInfo = message.frameInfo
        val isMainFrame = frameInfo.mainFrame
        val securityOrigin = frameInfo.securityOrigin
        val secPort = securityOrigin.port
        val secHost =
            if (securityOrigin.host.contains(':') && !securityOrigin.host.startsWith("[")) {
                "[${securityOrigin.host}]"
            } else {
                securityOrigin.host
            }
        val rawSecOrigin =
            if (securityOrigin.protocol.isNotEmpty() && secHost.isNotEmpty()) {
                if (secPort > 0L) "${securityOrigin.protocol}://$secHost:$secPort"
                else "${securityOrigin.protocol}://$secHost"
            } else {
                ""
            }
        val frameOrigin = parseWebContainerOrigin(rawSecOrigin)
        val docUrl = activeView.URL?.absoluteString.orEmpty()
        val docOrigin = parseWebContainerOrigin(docUrl)
        val cfg = config
        val fencedDocumentAllowed =
            cfg == null ||
                cfg.navigationPolicy.allowNavigation ||
                (docUrl.isNotEmpty() && isSameDocumentIgnoringFragment(cfg.url.trim(), docUrl))
        // Fail closed: opaque/sandboxed frames (frameOrigin == null) NEVER inherit main-frame docOrigin,
        // main-frame calls must match WKWebView.URL when both are present, and fenced allowNavigation=false
        // hosts reject pushState-diverged documents.
        val verifiedOrigin =
            when {
                frameOrigin == null -> null
                isMainFrame && (docOrigin == null || frameOrigin != docOrigin || !fencedDocumentAllowed) -> null
                else -> frameOrigin
            }
        dispatcher.dispatch(
            rawPayload = raw,
            origin = verifiedOrigin,
            documentUrl = docUrl.ifEmpty { currentUrl },
            isMainFrame = isMainFrame,
            onReply = replyOnMainOnce,
        )
    }

    // ---- WKNavigationDelegate / WKUIDelegate callbacks ----------------------------------------

    internal fun decideNavigationPolicy(
        source: WKWebView,
        action: WKNavigationAction,
    ): WKNavigationActionPolicy {
        if (disposed || !isSameObjCObject(source, webView)) {
            return WKNavigationActionPolicy.WKNavigationActionPolicyCancel
        }
        val cfg = config ?: return WKNavigationActionPolicy.WKNavigationActionPolicyCancel
        val targetUrl = action.request.URL?.absoluteString.orEmpty()
        val targetFrame = action.targetFrame
        if (events.onDownloadRequested != null && action.shouldPerformDownload) {
            val fromMainOrNewWindow = targetFrame == null || targetFrame.mainFrame
            if (fromMainOrNewWindow && isAllowedIosDownload(targetUrl, cfg)) {
                return WKNavigationActionPolicy.WKNavigationActionPolicyDownload
            }
            notifyNavigationBlocked(targetUrl, "download_blocked")
            return WKNavigationActionPolicy.WKNavigationActionPolicyCancel
        }
        val navTypeRaw = iosNavigationTypeRaw(action)
        val isUserClickOrForm = navTypeRaw == 0L || navTypeRaw == 1L
        if (targetFrame == null) {
            if (!cfg.navigationPolicy.allowNewWindows) {
                notifyNavigationBlocked(targetUrl, "new_window_blocked", isUserGesture = isUserClickOrForm)
            }
            return WKNavigationActionPolicy.WKNavigationActionPolicyCancel
        }
        if (!targetFrame.mainFrame) {
            return if (isAllowedSubframeUrl(targetUrl, cfg.navigationPolicy)) {
                WKNavigationActionPolicy.WKNavigationActionPolicyAllow
            } else {
                WKNavigationActionPolicy.WKNavigationActionPolicyCancel
            }
        }

        val isPendingHostLoad = pendingHostLoadUrl != null && pendingHostLoadUrl == targetUrl
        if (isPendingHostLoad) {
            pendingHostLoadUrl = null
            pendingHostLoadIsReissue = false
            return WKNavigationActionPolicy.WKNavigationActionPolicyAllow
        }

        val headerRule = cfg.navigationPolicy.requestHeaderRule
        val carriesHeader =
            headerRule != null && !action.request.valueForHTTPHeaderField(headerRule.headerName).isNullOrBlank()
        if (isUserClickOrForm) {
            val budget = headerRule?.maxSameOriginReissues ?: 0
            chainState.startChain(
                carriesHeader = carriesHeader,
                reissueBudget = budget,
                starter = if (navTypeRaw == 1L) WebNavigationStarter.Form else WebNavigationStarter.Link,
            )
        }
        val decision =
            evaluateMainFrameNavigation(
                targetUrl = targetUrl,
                initialUrl = cfg.url,
                policy = cfg.navigationPolicy,
                method = action.request.HTTPMethod ?: "GET",
                requestCarriesScopedHeader = carriesHeader,
                chainState = chainState,
                isUserGesture = isUserClickOrForm,
                catchesMainFrameNavigation = events.catchesMainFrameNavigation,
                routesMainFrameNavigation = events.routesMainFrameNavigation,
            )
        return when (decision) {
            WebNavigationDecision.Allow -> WKNavigationActionPolicy.WKNavigationActionPolicyAllow
            is WebNavigationDecision.InterceptedByHost -> {
                hostCancelledUrl = targetUrl
                WKNavigationActionPolicy.WKNavigationActionPolicyCancel
            }
            is WebNavigationDecision.Block -> {
                hostCancelledUrl = targetUrl
                notifyNavigationBlocked(decision.url, decision.reason, isUserGesture = isUserClickOrForm)
                WKNavigationActionPolicy.WKNavigationActionPolicyCancel
            }
            is WebNavigationDecision.ReissueWithHeader -> {
                hostCancelledUrl = targetUrl
                action.request.URL?.let { nsUrl ->
                    val reissued = buildRequest(nsUrl, cfg.cacheEnabled, decision.headerName to decision.headerValue)
                    pendingHostLoadUrl = targetUrl
                    pendingHostLoadIsReissue = true
                    dispatch_async(dispatch_get_main_queue()) {
                        if (!disposed && isSameObjCObject(source, webView)) {
                            source.loadRequest(reissued)
                        }
                    }
                }
                WKNavigationActionPolicy.WKNavigationActionPolicyCancel
            }
            is WebNavigationDecision.ReissueWithoutHeader -> {
                hostCancelledUrl = targetUrl
                action.request.URL?.let { nsUrl ->
                    val reissued = buildRequest(nsUrl, cfg.cacheEnabled, null)
                    pendingHostLoadUrl = targetUrl
                    pendingHostLoadIsReissue = true
                    dispatch_async(dispatch_get_main_queue()) {
                        if (!disposed && isSameObjCObject(source, webView)) {
                            source.loadRequest(reissued)
                        }
                    }
                }
                WKNavigationActionPolicy.WKNavigationActionPolicyCancel
            }
        }
    }

    internal fun didStartProvisional(source: WKWebView) {
        if (disposed || !isSameObjCObject(source, webView)) return
        if (shouldIgnoreWarmSpareBlankNavigation(usingSpare, source.URL?.absoluteString, config?.url.orEmpty())) return
        downloadCancelledUrl = null
        jsDialogTracker.cancelActive()
        jsbDispatcher?.cancelInFlight("main_frame_navigation")
        loadGeneration += 1
        currentLoading = true
        currentProgress = 0
        lastSuccessfulUrl = ""
        currentUrl = source.URL?.absoluteString ?: currentUrl
        watchdog.onEvent(WebContainerWatchdogEvent.LoadStarted(loadGeneration), webContainerMonotonicMillis())
        events.onUrlChange(currentUrl)
        notifyNavigationState(force = false)
    }

    internal fun didCommit(source: WKWebView) {
        if (disposed || !isSameObjCObject(source, webView)) return
        if (shouldIgnoreWarmSpareBlankNavigation(usingSpare, source.URL?.absoluteString, config?.url.orEmpty())) return
        spareCommitted = true
        hostCancelledUrl = null
        downloadCancelledUrl = null
        currentUrl = source.URL?.absoluteString ?: currentUrl
        chainState.markCommitted()
        watchdog.onEvent(WebContainerWatchdogEvent.PageCommitted(loadGeneration), webContainerMonotonicMillis())
        events.onUrlChange(currentUrl)
        events.onLoadCommit()
        revealTimer?.invalidate()
        revealTimer =
            NSTimer.scheduledTimerWithTimeInterval(IOS_REVEAL_CAP_SECONDS, repeats = false) { _ ->
                if (!disposed && isSameObjCObject(source, webView)) {
                    revealView()
                }
            }
        notifyNavigationState(force = false)
    }

    internal fun didFinish(source: WKWebView) {
        if (disposed || !isSameObjCObject(source, webView)) return
        if (shouldIgnoreWarmSpareBlankNavigation(usingSpare, source.URL?.absoluteString, config?.url.orEmpty())) return
        spareCommitted = true
        hostCancelledUrl = null
        downloadCancelledUrl = null
        currentUrl = source.URL?.absoluteString ?: currentUrl
        currentTitle = source.title ?: currentTitle
        currentLoading = false
        currentProgress = 100
        pagePainted = true
        if (pageRevealed) {
            config?.let(::applyBackgroundVisuals)
        } else {
            revealView()
        }
        chainState.markCommitted()
        val action = watchdog.onEvent(WebContainerWatchdogEvent.PageFinished(loadGeneration), webContainerMonotonicMillis())
        handleWatchdogAction(action)
        if (lastSuccessfulUrl != currentUrl) {
            lastSuccessfulUrl = currentUrl
            events.onLoadSuccess()
        }
        events.onUrlChange(currentUrl)
        notifyNavigationState(force = false)
    }

    internal fun decideResponsePolicy(
        source: WKWebView,
        response: WKNavigationResponse,
    ): WKNavigationResponsePolicy {
        if (disposed || !isSameObjCObject(source, webView) || events.onDownloadRequested == null) {
            return WKNavigationResponsePolicy.WKNavigationResponsePolicyAllow
        }
        val cfg = config ?: return WKNavigationResponsePolicy.WKNavigationResponsePolicyCancel
        val disposition = (response.response as? NSHTTPURLResponse)?.valueForHTTPHeaderField("Content-Disposition")
        val isAttachment = disposition?.trim()?.lowercase()?.startsWith("attachment") == true
        if (!isAttachment && response.canShowMIMEType) {
            return WKNavigationResponsePolicy.WKNavigationResponsePolicyAllow
        }
        val respUrl = response.response.URL?.absoluteString.orEmpty()
        if (!response.forMainFrame || !isAllowedIosDownload(respUrl, cfg)) {
            if (response.forMainFrame) {
                notifyNavigationBlocked(respUrl, "download_blocked")
            }
            return WKNavigationResponsePolicy.WKNavigationResponsePolicyCancel
        }
        return WKNavigationResponsePolicy.WKNavigationResponsePolicyDownload
    }

    internal fun didBecomeDownload(
        source: WKWebView,
        download: WKDownload,
    ) {
        if (disposed || !isSameObjCObject(source, webView) || events.onDownloadRequested == null) {
            download.cancel(null)
            return
        }
        downloadCancelledUrl = download.originalRequest?.URL?.absoluteString.orEmpty()
        val delegate = WebContainerIosDownloadDelegate(this, download)
        activeDownloads += delegate
        download.delegate = delegate
    }

    internal fun finishDownload(
        delegate: WebContainerIosDownloadDelegate,
        result: WebDownloadResult,
    ) {
        activeDownloads.removeAll { it === delegate }
        if (activeDownloads.isEmpty()) {
            downloadCancelledUrl = null
        }
        if (disposed) return
        events.onDownloadRequested?.invoke(result)
    }

    internal fun didFail(
        source: WKWebView,
        error: NSError,
    ) {
        if (disposed || !isSameObjCObject(source, webView)) return
        val failingUrl = error.userInfo[NSURLErrorFailingURLStringErrorKey] as? String
        val cancelledDownloadUrl = downloadCancelledUrl
        val isDownloadInterrupt =
            cancelledDownloadUrl != null &&
                error.domain == "WebKitErrorDomain" &&
                error.code == 102L &&
                (failingUrl.isNullOrEmpty() || cancelledDownloadUrl.isEmpty() || failingUrl == cancelledDownloadUrl)
        if (isDownloadInterrupt) {
            downloadCancelledUrl = null
            currentLoading = false
            watchdog.onEvent(WebContainerWatchdogEvent.LoadCancelled(loadGeneration), webContainerMonotonicMillis())
            notifyNavigationState(force = false)
            return
        }
        val isCancelled =
            (error.domain == NSURLErrorDomain && error.code == NSURLErrorCancelled) ||
                (error.domain == "WebKitErrorDomain" && error.code == 102L &&
                    hostCancelledUrl != null && (failingUrl.isNullOrEmpty() || failingUrl == hostCancelledUrl))
        if (isCancelled) {
            if (failingUrl == null || failingUrl == hostCancelledUrl) {
                hostCancelledUrl = null
            }
            return
        }
        currentLoading = false
        val reason = "${error.domain}:${error.code}"
        watchdog.onEvent(WebContainerWatchdogEvent.LoadFailed(loadGeneration, reason), webContainerMonotonicMillis())
        events.onLoadFailure(reason)
        notifyNavigationState(force = false)
    }

    internal fun contentProcessDidTerminate(source: WKWebView) {
        if (disposed || !isSameObjCObject(source, webView)) return
        val cfg = config ?: return
        jsDialogTracker.cancelActive()
        jsbDispatcher?.cancelInFlight("web_content_process_terminated")
        events.onContentProcessGone(true)
        val action =
            watchdog.onEvent(
                WebContainerWatchdogEvent.RenderProcessGone(didCrash = true),
                webContainerMonotonicMillis(),
            )
        val shouldRecover = action is WebContainerWatchdogAction.TriggerRecoveryReload
        val attempt =
            (action as? WebContainerWatchdogAction.TriggerRecoveryReload)?.recoveryAttempt
                ?: watchdog.recentRecoveryCount
        runCatching {
            telemetrySink?.onEvent(
                WebContainerTelemetryEvent.RenderProcessGone(
                    surfaceId = cfg.surfaceId,
                    didCrash = true,
                    recovered = shouldRecover,
                    attempt = attempt,
                    parked = false,
                    engine = IOS_WKWEBVIEW_ENGINE,
                ),
            )
        }
        if (shouldRecover) {
            val restoreUrl =
                sanitizeRendererRestoreUrl(
                    initialUrl = cfg.url,
                    candidateUrl = currentUrl,
                    policy = cfg.navigationPolicy,
                    isAllowedCandidate = { candidate ->
                        cfg.navigationPolicy.allowNavigation ||
                            isSameDocumentIgnoringFragment(cfg.url.trim(), candidate)
                    },
                )
            rebuildWebView(cfg, restoreUrl = restoreUrl)
        } else {
            handleWatchdogAction(action)
        }
    }

    internal fun createWindowBlocked(
        source: WKWebView,
        action: WKNavigationAction,
    ) {
        if (disposed || !isSameObjCObject(source, webView)) return
        val navTypeRaw = iosNavigationTypeRaw(action)
        val isUserGesture = navTypeRaw == 0L || navTypeRaw == 1L
        notifyNavigationBlocked(
            action.request.URL?.absoluteString.orEmpty(),
            "new_window_blocked",
            isUserGesture = isUserGesture,
        )
    }

    internal fun handleJsAlert(
        source: WKWebView,
        message: String,
        frame: WKFrameInfo,
        completionHandler: () -> Unit,
    ) {
        dispatchIosJsDialog(
            source = source,
            kind = WebJsDialogKind.Alert,
            frame = frame,
            message = message,
            defaultPromptText = null,
            deliverResult = { completionHandler() },
        )
    }

    internal fun handleJsConfirm(
        source: WKWebView,
        message: String,
        frame: WKFrameInfo,
        completionHandler: (Boolean) -> Unit,
    ) {
        dispatchIosJsDialog(
            source = source,
            kind = WebJsDialogKind.Confirm,
            frame = frame,
            message = message,
            defaultPromptText = null,
            deliverResult = { result ->
                completionHandler((result as? WebJsDialogResult.Confirm)?.confirmed == true)
            },
        )
    }

    internal fun handleJsPrompt(
        source: WKWebView,
        prompt: String,
        defaultText: String?,
        frame: WKFrameInfo,
        completionHandler: (String?) -> Unit,
    ) {
        dispatchIosJsDialog(
            source = source,
            kind = WebJsDialogKind.Prompt,
            frame = frame,
            message = prompt,
            defaultPromptText = defaultText,
            deliverResult = { result ->
                completionHandler((result as? WebJsDialogResult.Prompt)?.value)
            },
        )
    }

    private fun dispatchIosJsDialog(
        source: WKWebView,
        kind: WebJsDialogKind,
        frame: WKFrameInfo,
        message: String,
        defaultPromptText: String?,
        deliverResult: (WebJsDialogResult) -> Unit,
    ) {
        val visibleAndActive = !disposed && windowAttached && !appInBackground && isSameObjCObject(source, webView)
        val handler = events.onJsDialog
        if (!isWebJsDialogPermitted(config?.allowJsDialogs == true, visibleAndActive, handler != null)) {
            val defaultCancel =
                when (kind) {
                    WebJsDialogKind.Alert -> WebJsDialogResult.AlertDismissed
                    WebJsDialogKind.Confirm -> WebJsDialogResult.Confirm(confirmed = false)
                    WebJsDialogKind.Prompt -> WebJsDialogResult.Prompt(value = null)
                }
            runOnIosMainThread { deliverResult(defaultCancel) }
            return
        }
        val originUrl = iosJsDialogOriginUrl(source, frame)
        var requestRef: WebJsDialogRequest? = null
        val request =
            WebJsDialogRequest(
                kind = kind,
                originUrl = originUrl,
                message = message,
                defaultPromptText = defaultPromptText,
                onSettled = { requestRef?.let(jsDialogTracker::clearIfSame) },
                onResult = { dialogResult -> runOnIosMainThread { deliverResult(dialogResult) } },
            )
        requestRef = request
        jsDialogTracker.begin(request)
        val presented = runCatching { handler!!.invoke(request) }.isSuccess
        if (!presented) {
            request.cancel()
        }
    }

    private fun iosJsDialogOriginUrl(
        source: WKWebView,
        frame: WKFrameInfo,
    ): String {
        val sec = frame.securityOrigin
        val secHost =
            if (sec.host.contains(':') && !sec.host.startsWith("[")) {
                "[${sec.host}]"
            } else {
                sec.host
            }
        if (sec.protocol.isNotEmpty() && secHost.isNotEmpty()) {
            return if (sec.port > 0L) "${sec.protocol}://$secHost:${sec.port}" else "${sec.protocol}://$secHost"
        }
        return source.URL?.absoluteString?.takeIf { it.isNotBlank() } ?: currentUrl
    }

    private inline fun runOnIosMainThread(crossinline block: () -> Unit) {
        if (NSThread.isMainThread) {
            block()
        } else {
            dispatch_async(dispatch_get_main_queue()) { block() }
        }
    }

    /**
     * Every web page's camera / microphone request is refused ([WEB_MEDIA_CAPTURE_DENIED_REASON]):
     * WebKit gets `WKPermissionDecisionDeny` before it reaches `tccd`, so no page raises a system
     * prompt. A request from a WebView that is not this host's is refused too.
     */
    internal fun decideMediaCapturePermission(
        source: WKWebView,
        originLabel: String,
        isMainFrame: Boolean,
        type: WKMediaCaptureType,
    ): WKPermissionDecision {
        if (disposed || !isSameObjCObject(source, webView)) {
            return WKPermissionDecision.WKPermissionDecisionDeny
        }
        val kind = type.toWebMediaCaptureKind()
        val surfaceId = config?.surfaceId ?: "default"
        runCatching {
            telemetrySink?.onEvent(
                WebContainerTelemetryEvent.MediaCapturePermission(
                    surfaceId = surfaceId,
                    kind = kind.name,
                    grantedPrompt = false,
                    reason = WEB_MEDIA_CAPTURE_DENIED_REASON,
                    missingKeys = emptyList(),
                    origin = originLabel,
                    isMainFrame = isMainFrame,
                ),
            )
        }
        val logLine =
            "[WebContainer][info] media_capture_permission decision=deny surface=$surfaceId " +
                "kind=${kind.name} reason=$WEB_MEDIA_CAPTURE_DENIED_REASON mainFrame=$isMainFrame origin=$originLabel"
        NSLog(logLine.replace("%", "%%"))
        return WKPermissionDecision.WKPermissionDecisionDeny
    }

    private fun handleWatchdogAction(action: WebContainerWatchdogAction) {
        when (action) {
            WebContainerWatchdogAction.None -> Unit
            is WebContainerWatchdogAction.RequestDomProbe -> {
                webView?.let { probeDocumentContent(it, action.generation) }
            }
            is WebContainerWatchdogAction.TriggerRecoveryReload -> Unit
            is WebContainerWatchdogAction.EmitTerminalError -> {
                val cfg = config
                if (cfg != null) {
                    runCatching {
                        telemetrySink?.onEvent(
                            WebContainerTelemetryEvent.WatchdogTerminalError(
                                surfaceId = cfg.surfaceId,
                                reason = action.reason,
                                engine = IOS_WKWEBVIEW_ENGINE,
                            ),
                        )
                    }
                }
                events.onWatchdogTerminalError(action.reason)
            }
        }
    }

    private fun notifyNavigationBlocked(
        url: String,
        reason: String,
        isUserGesture: Boolean = false,
    ) {
        val cfg = config
        if (cfg != null) {
            telemetrySink?.onEvent(
                WebContainerTelemetryEvent.NavigationBlocked(
                    surfaceId = cfg.surfaceId,
                    sanitizedUrl = sanitizeUrlForTelemetry(url),
                    reason = reason,
                ),
            )
        }
        events.onNavigationBlocked(url, reason)
        events.onNavigationBlockedWithGesture?.invoke(url, reason, isUserGesture)
    }

    private fun notifyNavigationState(force: Boolean) {
        navigationStateRelay.notify(
            force = force,
            isActive = { !disposed },
            readState = {
                val view = webView
                WebNavigationState(
                    url = currentUrl,
                    title = currentTitle,
                    canGoBack = view?.let(::canGoBack) ?: false,
                    canGoForward = view?.canGoForward ?: false,
                    isLoading = currentLoading,
                    progress = currentProgress.coerceIn(0, 100),
                    loadGeneration = loadGeneration,
                )
            },
            deliver = { state -> events.onNavigationStateChange(state) },
        )
    }
}

private class WebContainerIosNavigationDelegate(
    private val owner: WebContainerIosHost,
) : NSObject(), WKNavigationDelegateProtocol, WKUIDelegateProtocol {
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        decisionHandler(owner.decideNavigationPolicy(webView, decidePolicyForNavigationAction))
    }

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationResponse: WKNavigationResponse,
        decisionHandler: (WKNavigationResponsePolicy) -> Unit,
    ) {
        decisionHandler(owner.decideResponsePolicy(webView, decidePolicyForNavigationResponse))
    }

    override fun webView(
        webView: WKWebView,
        navigationAction: WKNavigationAction,
        didBecomeDownload: WKDownload,
    ) {
        owner.didBecomeDownload(webView, didBecomeDownload)
    }

    override fun webView(
        webView: WKWebView,
        navigationResponse: WKNavigationResponse,
        didBecomeDownload: WKDownload,
    ) {
        owner.didBecomeDownload(webView, didBecomeDownload)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didStartProvisionalNavigation: WKNavigation?) {
        owner.didStartProvisional(webView)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didCommitNavigation: WKNavigation?) {
        owner.didCommit(webView)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        owner.didFinish(webView)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
        owner.didFail(webView, withError)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
        owner.didFail(webView, withError)
    }

    override fun webViewWebContentProcessDidTerminate(webView: WKWebView) {
        owner.contentProcessDidTerminate(webView)
    }

    override fun webView(
        webView: WKWebView,
        createWebViewWithConfiguration: WKWebViewConfiguration,
        forNavigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures,
    ): WKWebView? {
        owner.createWindowBlocked(webView, forNavigationAction)
        return null
    }

    override fun webView(
        webView: WKWebView,
        requestMediaCapturePermissionForOrigin: WKSecurityOrigin,
        initiatedByFrame: WKFrameInfo,
        type: WKMediaCaptureType,
        decisionHandler: (WKPermissionDecision) -> Unit,
    ) {
        decisionHandler(
            owner.decideMediaCapturePermission(
                source = webView,
                originLabel = formatIosSecurityOriginForLog(requestMediaCapturePermissionForOrigin),
                isMainFrame = initiatedByFrame.mainFrame,
                type = type,
            ),
        )
    }

    override fun webView(
        webView: WKWebView,
        runJavaScriptAlertPanelWithMessage: String,
        initiatedByFrame: WKFrameInfo,
        completionHandler: () -> Unit,
    ) {
        owner.handleJsAlert(webView, runJavaScriptAlertPanelWithMessage, initiatedByFrame, completionHandler)
    }

    override fun webView(
        webView: WKWebView,
        runJavaScriptConfirmPanelWithMessage: String,
        initiatedByFrame: WKFrameInfo,
        completionHandler: (Boolean) -> Unit,
    ) {
        owner.handleJsConfirm(webView, runJavaScriptConfirmPanelWithMessage, initiatedByFrame, completionHandler)
    }

    override fun webView(
        webView: WKWebView,
        runJavaScriptTextInputPanelWithPrompt: String,
        defaultText: String?,
        initiatedByFrame: WKFrameInfo,
        completionHandler: (String?) -> Unit,
    ) {
        owner.handleJsPrompt(
            webView,
            runJavaScriptTextInputPanelWithPrompt,
            defaultText,
            initiatedByFrame,
            completionHandler,
        )
    }
}

private class WebContainerIosJsbReplyHandler(
    private val owner: WebContainerIosHost,
) : NSObject(), WKScriptMessageHandlerWithReplyProtocol {
    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
        replyHandler: (Any?, String?) -> Unit,
    ) {
        owner.onJsbScriptMessage(userContentController, didReceiveScriptMessage, replyHandler)
    }
}

private class WebContainerIosSchemeHandler(
    var interceptor: WebResourceInterceptor?,
) : NSObject(), WKURLSchemeHandlerProtocol {
    private val activeTasks = mutableListOf<WKURLSchemeTaskProtocol>()
    private var invalidated: Boolean = false

    fun invalidate() {
        invalidated = true
        activeTasks.clear()
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, startURLSchemeTask: WKURLSchemeTaskProtocol) {
        activeTasks += startURLSchemeTask
        val request = startURLSchemeTask.request
        val url = request.URL
        val urlString = url?.absoluteString.orEmpty()
        if (invalidated || url == null || urlString.isEmpty()) {
            activeTasks.removeAll { isSameObjCObject(it, startURLSchemeTask) }
            startURLSchemeTask.didFailWithError(NSError.errorWithDomain(NSURLErrorDomain, -1000L, null))
            return
        }
        val hit =
            runCatching {
                interceptor?.intercept(
                    WebResourceRequest(
                        url = urlString,
                        method = request.HTTPMethod ?: "GET",
                        isMainFrame = false,
                        headers = emptyMap(),
                    ),
                )
            }.getOrNull()
        val stillActive = activeTasks.removeAll { isSameObjCObject(it, startURLSchemeTask) }
        if (!stillActive || invalidated) return
        if (hit == null) {
            startURLSchemeTask.didFailWithError(NSError.errorWithDomain(NSURLErrorDomain, -1008L, null))
            return
        }
        val headers = LinkedHashMap<Any?, Any?>()
        hit.headers.forEach { (k, v) -> headers[k] = v }
        if (headers.keys.none { (it as? String)?.equals("Content-Type", ignoreCase = true) == true }) {
            headers["Content-Type"] = "${hit.mimeType}; charset=${hit.encoding}"
        }
        val response = NSHTTPURLResponse(url, hit.statusCode.toLong(), "HTTP/1.1", headers)
        startURLSchemeTask.didReceiveResponse(response)
        startURLSchemeTask.didReceiveData(hit.body.toIosNSData())
        startURLSchemeTask.didFinish()
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, stopURLSchemeTask: WKURLSchemeTaskProtocol) {
        activeTasks.removeAll { isSameObjCObject(it, stopURLSchemeTask) }
    }
}

private fun ByteArray.toIosNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}

private fun iosColorFromArgb(argb: Int): UIColor =
    UIColor.colorWithRed(
        red = ((argb ushr 16) and 0xFF) / 255.0,
        green = ((argb ushr 8) and 0xFF) / 255.0,
        blue = (argb and 0xFF) / 255.0,
        alpha = ((argb ushr 24) and 0xFF) / 255.0,
    )

private val IosDownloadAllowedSchemes: Set<String> = setOf("http", "https", "blob", "data")

private fun isAllowedIosDownload(
    url: String,
    cfg: WebContainerConfig,
): Boolean {
    val scheme = extractUrlScheme(url) ?: return false
    if (scheme !in IosDownloadAllowedSchemes) return false
    if (scheme == "http" || scheme == "https") {
        val allowedSchemes = cfg.navigationPolicy.normalizedAllowedSchemes
        if (allowedSchemes.isNotEmpty() && scheme !in allowedSchemes) return false
        val locked = cfg.navigationPolicy.lockedMainFrameOrigin
        if (locked != null && parseWebContainerOrigin(url) != locked) return false
    }
    return true
}

/**
 * Container-owned `WKWebView` subclass honoring [WebContainerConfig.showsFormAccessoryBar] via a
 * runtime Objective-C `inputAccessoryView` override on this subclass only (zero private WebKit
 * class usage).
 */
internal class WebContainerIosWebView(
    frame: CValue<CGRect>,
    configuration: WKWebViewConfiguration,
) : WKWebView(frame = frame, configuration = configuration) {
    var showsFormAccessoryBar: Boolean = false

    init {
        WebContainerIosAccessoryHook.install(this)
    }
}

private typealias WebContainerIosObjCGetter = CFunction<(COpaquePointer?, COpaquePointer?) -> COpaquePointer?>

private object WebContainerIosAccessoryHook {
    private const val SELECTOR = "inputAccessoryView"
    private var installed = false
    private var inherited: CPointer<WebContainerIosObjCGetter>? = null

    fun install(view: WebContainerIosWebView) {
        if (installed) return
        installed = true
        val selector = sel_registerName(SELECTOR)
        val ownClass = object_getClass(view)
        val inheritedMethod = class_getInstanceMethod(class_getSuperclass(ownClass), selector)
        inherited = method_getImplementation(inheritedMethod)?.reinterpret()
        val implementation =
            staticCFunction { self: COpaquePointer?, cmd: COpaquePointer? ->
                val webView = self?.let { interpretObjCPointerOrNull<WebContainerIosWebView>(it.rawValue) }
                if (webView?.showsFormAccessoryBar == true) inherited?.invoke(self, cmd) else null
            }
        class_addMethod(
            ownClass,
            selector,
            implementation.reinterpret(),
            method_getTypeEncoding(inheritedMethod)?.toKString() ?: "@@:",
        )
    }
}

/**
 * Streams one `WKDownload` into a private temporary directory, validates [WEB_CONTAINER_DOWNLOAD_MAX_BYTES],
 * delivers bytes via [WebContainerIosHost.finishDownload], and unconditionally deletes the temp directory.
 */
internal class WebContainerIosDownloadDelegate(
    private val owner: WebContainerIosHost,
    private val download: WKDownload,
) : NSObject(), WKDownloadDelegateProtocol {
    private var destination: NSURL? = null
    private var fileName: String = "download"
    private var mimeType: String? = null
    private var finished: Boolean = false

    fun cancelAndCleanup() {
        finished = true
        download.delegate = null
        download.cancel(null)
        cleanupTempFolder()
    }

    override fun download(
        download: WKDownload,
        decideDestinationUsingResponse: NSURLResponse,
        suggestedFilename: String,
        completionHandler: (NSURL?) -> Unit,
    ) {
        if (finished) {
            completionHandler(null)
            return
        }
        val expected = decideDestinationUsingResponse.expectedContentLength
        if (expected > WEB_CONTAINER_DOWNLOAD_MAX_BYTES) {
            completionHandler(null)
            finish(WebDownloadResult.Failed("too_large"))
            return
        }
        mimeType = decideDestinationUsingResponse.MIMEType
        val disposition = (decideDestinationUsingResponse as? NSHTTPURLResponse)?.valueForHTTPHeaderField("Content-Disposition")
        fileName =
            resolveWebDownloadFileName(
                pageFileName = suggestedFilename,
                contentDisposition = disposition,
                url = decideDestinationUsingResponse.URL?.absoluteString.orEmpty(),
                mimeType = mimeType,
            )
        val folder =
            NSURL.fileURLWithPath(NSTemporaryDirectory())
                .URLByAppendingPathComponent("raft-wc-downloads", isDirectory = true)
                ?.URLByAppendingPathComponent(NSUUID().UUIDString, isDirectory = true)
        if (folder == null || !NSFileManager.defaultManager.createDirectoryAtURL(folder, true, null, null)) {
            completionHandler(null)
            finish(WebDownloadResult.Failed("storage_prepare"))
            return
        }
        val target = folder.URLByAppendingPathComponent(fileName, isDirectory = false)
        if (target == null) {
            NSFileManager.defaultManager.removeItemAtURL(folder, null)
            completionHandler(null)
            finish(WebDownloadResult.Failed("storage_prepare"))
            return
        }
        destination = target
        completionHandler(target)
    }

    override fun downloadDidFinish(download: WKDownload) {
        val target = destination
        val fileSize =
            target?.path?.let { path ->
                (NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue
            } ?: -1L
        if (fileSize > WEB_CONTAINER_DOWNLOAD_MAX_BYTES) {
            cleanupTempFolder()
            finish(WebDownloadResult.Failed("too_large"))
            return
        }
        val data: NSData? = target?.let { NSData.create(contentsOfURL = it) }
        cleanupTempFolder()
        val result =
            when {
                data == null -> WebDownloadResult.Failed("read_failed")
                data.length > WEB_CONTAINER_DOWNLOAD_MAX_BYTES.toULong() -> WebDownloadResult.Failed("too_large")
                else -> WebDownloadResult.Bytes(data.toWebContainerByteArray(), fileName, mimeType)
            }
        finish(result)
    }

    override fun download(
        download: WKDownload,
        didFailWithError: NSError,
        resumeData: NSData?,
    ) {
        cleanupTempFolder()
        finish(WebDownloadResult.Failed("download_failed code=${didFailWithError.code}"))
    }

    private fun cleanupTempFolder() {
        val target = destination ?: return
        destination = null
        target.URLByDeletingLastPathComponent?.let { folder ->
            NSFileManager.defaultManager.removeItemAtURL(folder, null)
        }
    }

    private fun finish(result: WebDownloadResult) {
        if (finished) return
        finished = true
        owner.finishDownload(this, result)
    }
}

private fun NSData.toWebContainerByteArray(): ByteArray {
    val size = length.toInt()
    if (size <= 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return out
}

private fun iosNavigationTypeRaw(action: WKNavigationAction): Long =
    when (val navType: Any = action.navigationType) {
        is Long -> navType
        is Int -> navType.toLong()
        is CEnum -> (navType.value as? Number)?.toLong() ?: -1L
        else -> -1L
    }
