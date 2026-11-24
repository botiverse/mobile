@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.CEnum
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGColorGetAlpha
import platform.CoreGraphics.CGRectMake
// Foundation categories (NSHTTPURLRequest, NSMutableHTTPURLRequest) import as extensions.
import platform.Foundation.*
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.WebKit.WKAudiovisualMediaTypeAll
import platform.WebKit.WKBackForwardListItem
import platform.WebKit.WKDownload
import platform.WebKit.WKDownloadDelegateProtocol
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigationResponse
import platform.WebKit.WKNavigationResponsePolicy
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebpagePreferences
import platform.WebKit.WKWebsiteDataStore
import platform.WebKit.WKWindowFeatures
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy
import kotlin.time.TimeSource

@Composable
internal actual fun RaftSandboxWebViewHost(
    config: RaftWebViewConfig,
    events: RaftWebViewEvents,
    controller: RaftWebViewController,
    modifier: Modifier,
) {
    UIKitView(
        factory = { RaftIosSandboxWebViewContainer(events).also { it.webViewHost.applyInitialBackground(config.backgroundArgb) } },
        modifier = modifier,
        update = { view ->
            view.webViewHost.events = events
            view.webViewHost.applyConfig(config)
            view.webViewHost.bindController(controller)
        },
        onRelease = { view -> view.webViewHost.dispose() },
    )
}

/** Hosts the WKWebView; re-applies the desktop fit once measured (the `.m` layoutSubviews). */
internal class RaftIosSandboxWebViewContainer(events: RaftWebViewEvents) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    val webViewHost = RaftIosSandboxWebView(this, events)

    override fun layoutSubviews() {
        super.layoutSubviews()
        webViewHost.layout()
    }

    /** A covered page's views leave the window: pause the navigation-state poll until it returns. */
    override fun didMoveToWindow() {
        super.didMoveToWindow()
        webViewHost.onWindowAttachmentChanged(attached = window != null)
    }
}

/** A committed page is shown at the latest this long after its commit ([RaftIosSandboxWebView.reveal]). */
private const val RevealCapSeconds = 1.5

/** Poll interval standing in for the `.m` KVO on estimatedProgress/title/canGoBack/canGoForward/loading/URL. */
private const val RaftIosNavigationStatePollSeconds = 0.25

private const val RaftIosHostBridgeScript =
    "(function(){var h=window.webkit&&window.webkit.messageHandlers&&window.webkit.messageHandlers.raftHostPort;if(!h)return;" +
        "Object.defineProperty(window,\"raftHostPort\",{value:{postMessage:function(m){h.postMessage(m);}},writable:false,configurable:false});" +
        "Object.defineProperty(window,\"RaftHost\",{value:Object.freeze({version:\"raft-host-v1\",capabilities:[\"raft-feedback-v1\"]," +
        "emit:function(k,p){if(typeof k===\"string\")h.postMessage(JSON.stringify({kind:k,payload:(p&&typeof p===\"object\"&&!Array.isArray(p))?p:{}}));}})," +
        "writable:false,configurable:false});})();"

/**
 * True when [a] and [b] refer to the same Objective-C object.
 *
 * Do not use `===` for this. Kotlin/Native hands an Objective-C object that comes back from
 * Objective-C (a WKNavigationDelegate argument, a getter such as
 * `configuration.userContentController`) to Kotlin as a newly bridged reference, so `===` against
 * the reference Kotlin already holds can be false for the very same object. With `source !== webView`
 * every delegate callback looked like it came from a stale WebView: the main document's
 * navigation policy was cancelled without a blocked/failure event and the page stayed blank white
 * (HTML attachment preview on iOS). The Objective-C `.m` compared pointers; so does this.
 */
internal fun raftIsSameObjCObject(a: Any?, b: Any?): Boolean =
    a != null && b != null && a.objcPtr() == b.objcPtr()

/**
 * Port of the Kuikly iOS `RaftSandboxWebView.m`: WKWebView rebuilt whenever a configuration-time
 * attribute changes (scripts, navigation policy, storage, ephemeral store, injected script, host
 * bridge), sandbox navigation fence, `raftHostPort` bridge with origin + document checks, KVO
 * navigation state and web content process recovery (task #6). Desktop width comes from the injected
 * viewport meta ([raftWebViewDesktopModeViaViewportMeta]), which WKWebView honours on its own.
 */
internal class RaftIosSandboxWebView(
    private val container: UIView,
    var events: RaftWebViewEvents,
) {
    private var webView: WKWebView? = null
    private var src: String = ""
    private var allowedURL: NSURL? = null
    private var allowScripts = true
    private var allowNavigation = false
    private var domStorageEnabled = false
    private var cacheEnabled = false
    private var ephemeral = true
    private var storagePartition = RaftWebViewStoragePartition.Ephemeral
    private var injectedJavaScript = ""
    private var allowedNavigationSchemes: Set<String> = emptySet()
    private var hostEventsEnabled = false
    /** [RaftWebViewConfig.showsFormAccessoryBar], applied to every WKWebView this host shows ([RaftIosWebView]). */
    private var showsFormAccessoryBar = false
    /** [RaftWebViewConfig.allowsBackForwardNavigationGestures], wired to `WKWebView.allowsBackForwardNavigationGestures`. */
    private var allowsBackForwardNavigationGestures = false
    /** [RaftWebViewConfig.allowPinchZoom], applied via `WKWebViewConfiguration.ignoresViewportScaleLimits` at build time. */
    private var allowPinchZoom = false
    /** [RaftWebViewConfig.mainFrameOrigin]: every top-level document stays on it. */
    private var mainFrameOrigin: String? = null
    /** [RaftWebViewConfig.raftHost]: `Raft-Host` header attached to scoped main-frame GET navigations. */
    private var raftHost: RaftHostHeader? = null
    private var currentURL = ""
    private var currentTitle = ""
    private var currentLoading = false
    private var currentProgress = 0

    /** Main-frame loads started ([RaftWebViewNavigationState.loadGeneration]). */
    private var loadGeneration = 0
    private val navigationStateRelay = RaftWebViewNavigationStateRelay()
    private var lastSuccessfulURL = ""
    private var lastLoadFailureKey = ""
    /** A navigation became a download: its "Frame load interrupted" is no load failure. */
    private var downloadCancelled = false
    /** A navigation was intentionally cancelled by host policy (re-issue or sign-in catch); swallow 102/-999. */
    private var hostCancelledUrl: String? = null
    /** The page's downloads in flight (WKDownload holds its delegate weakly). */
    private val activeDownloads = mutableSetOf<RaftIosWebViewDownloadDelegate>()
    private var stateTimer: NSTimer? = null
    private var disposed = false
    private var configured = false
    private var boundController: RaftWebViewController? = null
    /** When the current main-frame load started ([loadClock]), for the `elapsedMs` of the load lines; null before one. */
    private var loadStartedAt: TimeSource.Monotonic.ValueTimeMark? = null
    /** When this page asked its WKWebView for the document (`load_src`): `sinceRequestMs` of the load lines. */
    private var requestedAt: TimeSource.Monotonic.ValueTimeMark? = null
    /** When this host was made (the page attached its WebView): `sinceAttachMs` of `load_src`. */
    private val attachedAt = TimeSource.Monotonic.markNow()
    /** Whether the current WKWebView is the app's spare ([RaftIosWebViewSpare]) rather than a new one. */
    private var usingSpare = false
    /** [RaftWebViewConfig.backgroundArgb]: the colour before the first paint (0: white). */
    private var placeholderBackground = 0
    /** The current WKWebView finished a page; from then on it is opaque white, as Safari. */
    private var pagePainted = false
    /** The current WKWebView is shown ([reveal]): its page finished and painted its scheme, or [RevealCapSeconds] after the commit. */
    private var pageRevealed = false
    /** The current WKWebView's first page finished (its background was probed once). */
    private var pageFinished = false
    /** Reveals the page [RevealCapSeconds] after its commit if it has not been yet. */
    private var revealTimer: NSTimer? = null
    /** Host-initiated load URL awaiting its own [decideMainFramePolicy] callback (not a page link). */
    private var pendingHostLoadUrl: String? = null
    /** Whether [pendingHostLoadUrl] is a `Raft-Host` re-issue continuing the active navigation chain. */
    private var pendingHostLoadIsReissue = false
    /** Committed top-level document URL currently shown, or `""` before any commit. */
    private var committedDocumentUrl = ""
    /** Whether any top-level document has committed on this host yet. */
    private var hasCommittedDocument = false
    /** True while a provisional main-frame navigation is in flight (before commit/fail). */
    private var navInFlight = false
    /** True while the active navigation chain carried `Raft-Host` and has not committed since. */
    private var navChainCarriesRaftHost = false
    /** Remaining `Raft-Host` re-issues in the current navigation chain. */
    private var navChainReissueBudget = RAFT_HOST_REISSUE_BUDGET
    /** Who started the current in-flight navigation chain (for [RaftWebViewMainFrameCatchAsk]). */
    private var navChainStarter = RaftSignInStarter.Other
    /** Prior URLs visited by the current navigation chain before the active candidate. */
    private val navChainHops = mutableListOf<String>()
    private var diagnosticsSurface: String = RAFT_WEBVIEW_SURFACE_DEFAULT

    private val delegate = RaftIosWebViewDelegate(this)
    private val scriptMessageHandler = RaftIosWebViewScriptMessageHandler(this)

    init {
        container.backgroundColor = UIColor.whiteColor
    }

    /** Before the first config: the canvas colour from the start (UIKitView may show the container before `update`). */
    fun applyInitialBackground(argb: Int) {
        if (argb != 0 && !configured) container.backgroundColor = raftIosColor(argb)
    }

    /**
     * The page is visible from attach (no cover): with a canvas colour set, the WKWebView is not
     * shown ([pageRevealed]) and is transparent over the canvas-coloured container, so no white
     * rectangle in dark mode. Opaque white, as Safari, only once its first page finished without a
     * background of its own ([pagePainted]); a page with its own background stays over the canvas.
     */
    private fun applyBackground() {
        val view = webView
        val painted = pagePainted && view != null
        val color = raftIosColor(raftWebViewBackgroundArgb(placeholderBackground, painted))
        container.backgroundColor = color
        if (view == null) return
        // Before the page's document commits the WKWebView shows whatever it had (a spare's blank
        // warm-up document, layers not yet redrawn): not shown, the canvas container is.
        view.alpha = if (raftWebViewShownBeforeCommit(placeholderBackground, pageRevealed)) 1.0 else 0.0
        view.opaque = painted || placeholderBackground == 0
        view.backgroundColor = if (view.opaque) color else UIColor.clearColor
        view.scrollView.backgroundColor = view.backgroundColor
    }

    /**
     * Kuikly applied attrs one by one and each change could rebuild the WebView; here the whole
     * config is applied at once and the WebView is rebuilt at most once.
     */
    fun applyConfig(config: RaftWebViewConfig) {
        if (disposed) return
        val nextPartition = config.effectiveStoragePartition
        val rebuild =
            !configured ||
                allowScripts != config.allowScripts ||
                allowNavigation != config.allowNavigation ||
                domStorageEnabled != config.domStorageEnabled ||
                storagePartition != nextPartition ||
                injectedJavaScript != config.injectedJavaScript ||
                hostEventsEnabled != config.hostEventsEnabled ||
                mainFrameOrigin != config.mainFrameOrigin?.let(::raftWebOriginOf) ||
                allowPinchZoom != config.allowPinchZoom
        allowScripts = config.allowScripts
        // Injected user scripts always run main-frame-only; navigation policy changes still
        // rebuild the WebView so fencing takes effect on a clean configuration.
        allowNavigation = config.allowNavigation
        domStorageEnabled = config.domStorageEnabled
        cacheEnabled = config.cacheEnabled
        ephemeral = nextPartition == RaftWebViewStoragePartition.Ephemeral
        storagePartition = nextPartition
        injectedJavaScript = config.injectedJavaScript
        hostEventsEnabled = config.hostEventsEnabled
        mainFrameOrigin = config.mainFrameOrigin?.let(::raftWebOriginOf)
        raftHost = config.raftHost
        allowPinchZoom = config.allowPinchZoom
        diagnosticsSurface = config.diagnosticsSurface
        showsFormAccessoryBar = config.showsFormAccessoryBar
        allowsBackForwardNavigationGestures = config.allowsBackForwardNavigationGestures
        (webView as? RaftIosWebView)?.showsFormAccessoryBar = showsFormAccessoryBar
        webView?.allowsBackForwardNavigationGestures = allowsBackForwardNavigationGestures
        configured = true
        if (placeholderBackground != config.backgroundArgb) {
            placeholderBackground = config.backgroundArgb
            applyBackground()
        }
        val previousWebView = webView
        val previousUrl = currentURL
        val schemesUnchanged = allowedNavigationSchemes == config.allowedNavigationSchemes
        val srcChanged = updateSrcState(config.url.trim())
        if (rebuild) {
            val restoreUrl =
                if (!srcChanged && previousWebView != null && schemesUnchanged) {
                    raftWebViewRendererGoneRestoreUrl(src, previousUrl, ::rendererGoneRestoreAllows)
                } else {
                    null
                }
            rebuildWebView(restoreUrl)
        } else if (srcChanged) {
            loadCurrentSrc()
        }
        updateAllowedNavigationSchemes(config.allowedNavigationSchemes)
    }

    fun bindController(controller: RaftWebViewController) {
        if (boundController === controller) return
        boundController?.unbindCommandDispatcher(this)
        boundController = controller
        controller.bindCommandDispatcher(this) { command -> executeNavigationCommand(command) }
    }

    fun layout() {
        webView?.setFrame(container.bounds)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        boundController?.unbindCommandDispatcher(this)
        boundController = null
        tearDownWebView()
        webView = null
    }

    private fun tearDownWebView() {
        revealTimer?.invalidate()
        revealTimer = null
        pendingHostLoadUrl = null
        pendingHostLoadIsReissue = false
        hostCancelledUrl = null
        navInFlight = false
        resetNavigationChain()
        val previous = webView ?: return
        stopObserving(previous)
        previous.navigationDelegate = null
        previous.UIDelegate = null
        previous.stopLoading()
        // WKUserContentController retains its handlers strongly; break the cycle explicitly.
        previous.configuration.userContentController.removeScriptMessageHandlerForName(RaftWebViewHostPortName)
        previous.removeFromSuperview()
    }

    /** [restoreUrl]: after the web content process was gone, the page that was shown ([raftWebViewRendererGoneRestoreUrl]). */
    private fun rebuildWebView(restoreUrl: String? = null) {
        tearDownWebView()
        committedDocumentUrl = ""
        hasCommittedDocument = false
        // The app's spare WKWebView (created after launch, RaftIosWebViewSpare) when this page's
        // configuration is the one it was built with: default persistent store, scripts on, default
        // viewport scale limits. It saves the WebKit start-up (web content / networking processes,
        // the data store) on the first load. Not after a crash (contentProcessDidTerminate
        // rebuilds while a WebView already existed), and never for IsolatedThirdParty.
        val spare =
            if (
                raftIosCanUseSpareWebView(
                    ephemeral = ephemeral,
                    allowScripts = allowScripts,
                    allowPinchZoom = allowPinchZoom,
                    hasExistingWebView = webView != null,
                    storagePartition = storagePartition,
                )
            ) {
                RaftIosWebViewSpare.take()
            } else {
                null
            }
        val userContentController =
            spare?.configuration?.userContentController?.also { it.removeAllUserScripts() } ?: WKUserContentController()
        if (hostEventsEnabled) {
            userContentController.addScriptMessageHandler(scriptMessageHandler, name = RaftWebViewHostPortName)
            userContentController.addUserScript(
                WKUserScript(
                    source = RaftIosHostBridgeScript,
                    injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                    forMainFrameOnly = true
                )
            )
        }
        if (allowScripts && placeholderBackground != 0) {
            // The page's own first paint is the engine's white canvas until its stylesheet and
            // theme script apply: the canvas colour and scheme until it loaded (dark mode, no flash).
            userContentController.addUserScript(
                WKUserScript(
                    source = raftWebViewFirstPaintThemeScript(placeholderBackground, RaftAppThemeState.isDark),
                    injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                    forMainFrameOnly = true
                )
            )
        }
        if (allowScripts && injectedJavaScript.isNotEmpty()) {
            userContentController.addUserScript(
                WKUserScript(
                    source = injectedJavaScript,
                    injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                    // Document-start scripts (including session auth scripts) target only the
                    // top-level document and never run inside subframes (Tenny's WebView rule 1).
                    forMainFrameOnly = true
                )
            )
        }
        val next =
            spare?.also { it.setFrame(container.bounds) }
                ?: run {
                    val configuration =
                        raftIosWebViewConfiguration(
                            ephemeral = ephemeral,
                            allowScripts = allowScripts,
                            ignoresViewportScaleLimits = allowPinchZoom,
                            storagePartition = storagePartition,
                        )
                    configuration.userContentController = userContentController
                    RaftIosWebView(frame = container.bounds, configuration = configuration)
                }
        next.showsFormAccessoryBar = showsFormAccessoryBar
        usingSpare = spare != null
        if (spare != null) {
            diagnosticsInfo(
                "spare_state opaque=${spare.opaque} bgAlpha=${raftIosColorAlpha(spare.backgroundColor)} " +
                    "scrollBgAlpha=${raftIosColorAlpha(spare.scrollView.backgroundColor)} " +
                    "style=${spare.traitCollection.userInterfaceStyle} inWindow=${container.window != null}"
            )
        }
        next.navigationDelegate = delegate
        next.UIDelegate = delegate
        next.allowsBackForwardNavigationGestures = allowsBackForwardNavigationGestures
        next.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        webView = next
        pagePainted = false
        pageRevealed = false
        pageFinished = false
        applyBackground()
        container.addSubview(next)
        startObserving(next)
        currentProgress = 0
        currentLoading = false
        currentTitle = ""
        navigationStateRelay.reset()
        if (spare != null && placeholderBackground != 0) {
            // Until this page's document commits, the spare keeps showing its blank warm-up
            // document, which it laid out white (opaque, light): give it the canvas colour first.
            next.evaluateJavaScript(
                raftWebViewSpareBlankCanvasScript(placeholderBackground, RaftAppThemeState.isDark),
                completionHandler = null
            )
        }
        loadCurrentSrc(restoreUrl)
    }

    /**
     * TODO(cmp-switch): Kotlin/Native cannot override the NSObject KVO category method
     * (`observeValueForKeyPath:`), so the `.m` key-value observation is replaced by a light poll
     * while a WebView is attached; [notifyNavigationState] de-duplicates unchanged states. The poll
     * only runs while the container is in a window (paused while the page is covered).
     */
    private fun startObserving(target: WKWebView) {
        stopObserving(target)
        if (container.window == null) return
        stateTimer =
            NSTimer.scheduledTimerWithTimeInterval(RaftIosNavigationStatePollSeconds, repeats = true) { _ ->
                onObservedChange(target)
            }
    }

    private fun stopObserving(@Suppress("UNUSED_PARAMETER") target: WKWebView) {
        stateTimer?.invalidate()
        stateTimer = null
    }

    fun onWindowAttachmentChanged(attached: Boolean) {
        if (disposed) return
        val view = webView ?: return
        if (attached) {
            if (stateTimer == null) {
                startObserving(view)
                // Catch up on whatever changed while the poll was paused.
                onObservedChange(view)
            }
        } else {
            stopObserving(view)
        }
    }

    internal fun onObservedChange(source: Any?) {
        val view = webView ?: return
        if (!raftIsSameObjCObject(source, view)) return
        currentURL = view.URL?.absoluteString ?: currentURL
        currentTitle = view.title ?: ""
        currentProgress = (view.estimatedProgress * 100.0 + 0.5).toInt().coerceIn(0, 100)
        currentLoading = view.loading
        notifyNavigationState(force = false)
    }

    private fun updateAllowedNavigationSchemes(next: Set<String>) {
        if (allowedNavigationSchemes == next) return
        allowedNavigationSchemes = next
        if (src.isEmpty()) return
        var activeURL = webView?.URL
        if (activeURL == null || activeURL.absoluteString == "about:blank") activeURL = NSURL.URLWithString(src)
        if (activeURL != null && !isNavigationSchemeAllowed(activeURL)) {
            webView?.stopLoading()
            currentLoading = false
            notifyNavigationState(force = false)
            notifyNavigationBlocked(activeURL.absoluteString ?: src)
        }
    }

    private fun executeNavigationCommand(navigationCommand: String) {
        if (disposed) return
        val view = webView ?: return
        val command = navigationCommand.trim()
        if (command.startsWith(RaftWebViewController.COMMAND_EVALUATE_JAVA_SCRIPT_PREFIX)) {
            val script = command.removePrefix(RaftWebViewController.COMMAND_EVALUATE_JAVA_SCRIPT_PREFIX)
            if (script.length > RaftWebViewMaxRuntimeJavaScriptLength) return
            val activeURL = view.URL
            val sameTrustedDocument =
                activeURL != null && isNavigationSchemeAllowed(activeURL) && isSameOriginURL(activeURL) &&
                    isOnLockedOrigin(activeURL) &&
                    (allowNavigation || isAllowedMainDocumentURL(activeURL))
            if (sameTrustedDocument && script.isNotEmpty()) view.evaluateJavaScript(script, completionHandler = null)
            return
        }
        if (command == RaftWebViewController.COMMAND_PROBE_DOCUMENT_CONTENT) {
            probeDocumentContent(view)
            return
        }
        if (command.startsWith(RaftWebViewController.COMMAND_LOAD_URL_PREFIX)) {
            loadControllerUrl(command.removePrefix(RaftWebViewController.COMMAND_LOAD_URL_PREFIX))
            return
        }
        var forceNotify = true
        when (command) {
            RaftWebViewController.COMMAND_GO_BACK -> if (canGoBack(view)) view.goBack()
            RaftWebViewController.COMMAND_GO_FORWARD -> if (view.canGoForward) view.goForward()
            RaftWebViewController.COMMAND_RELOAD -> {
                lastSuccessfulURL = ""
                lastLoadFailureKey = ""
                currentProgress = 0
                currentLoading = true
                view.reload()
            }
            RaftWebViewController.COMMAND_STOP -> {
                view.stopLoading()
                currentLoading = false
                forceNotify = false
            }
            else -> return
        }
        notifyNavigationState(force = forceNotify)
    }

    /**
     * The fixed, read-only [RaftWebViewContentProbeScript] (a '1'/'0' string, nothing of the page's
     * data), so it is not fenced like runtime scripts. Main frame only (WKWebView's default world).
     * No scripts or no readable answer: null (fail open).
     */
    private fun probeDocumentContent(view: WKWebView) {
        if (!allowScripts) {
            events.onContentProbe(null)
            return
        }
        view.evaluateJavaScript(RaftWebViewContentProbeScript) { result, _ ->
            if (!disposed && raftIsSameObjCObject(view, webView)) {
                events.onContentProbe(raftWebViewDecodeContentProbe(result as? String))
            }
        }
    }

    /** Returns true when the source changed (state reset, load pending). */
    private fun updateSrcState(nextSrc: String): Boolean {
        if (src == nextSrc) return false
        src = nextSrc
        allowedURL = if (nextSrc.isNotEmpty()) NSURL.URLWithString(nextSrc) else null
        currentURL = nextSrc
        currentTitle = ""
        currentProgress = 0
        currentLoading = false
        lastSuccessfulURL = ""
        lastLoadFailureKey = ""
        committedDocumentUrl = ""
        hasCommittedDocument = false
        hostCancelledUrl = null
        navInFlight = false
        resetNavigationChain()
        return true
    }

    /** Loads [src], or [documentUrl] (the page shown before a rebuild) when given; [src] stays the source. */
    private fun loadCurrentSrc(documentUrl: String? = null) {
        val view = webView ?: return
        if (src.isEmpty()) {
            pendingHostLoadUrl = null
            pendingHostLoadIsReissue = false
            navInFlight = false
            resetNavigationChain()
            currentURL = ""
            currentTitle = ""
            currentProgress = 0
            currentLoading = false
            notifyNavigationState(force = false)
            view.loadHTMLString("", baseURL = null)
            return
        }
        val target = documentUrl?.takeIf { it.isNotBlank() } ?: src
        val url = NSURL.URLWithString(target)
        if (url == null) {
            pendingHostLoadUrl = null
            pendingHostLoadIsReissue = false
            navInFlight = false
            notifyNavigationState(force = false)
            if (allowedNavigationSchemes.isNotEmpty()) notifyNavigationBlocked(src) else notifyLoadFailure("invalid_preview_url")
            return
        }
        if (!isNavigationSchemeAllowed(url) || !isOnLockedOrigin(url)) {
            pendingHostLoadUrl = null
            pendingHostLoadIsReissue = false
            navInFlight = false
            notifyNavigationState(force = false)
            notifyNavigationBlocked(src)
            return
        }
        currentLoading = true
        notifyNavigationState(force = false)
        requestedAt = TimeSource.Monotonic.markNow()
        diagnosticsInfo(
            "load_src spare=$usingSpare sinceAttachMs=${attachedAt.elapsedNow().inWholeMilliseconds} " +
                "url=${formatUrlForLog(target)}"
        )
        val resolvedTarget = url.absoluteString ?: target
        val request = raftIosBuildRequest(url = url, cacheEnabled = cacheEnabled, raftHost = raftHost, includeHeader = true)
        startHostNavigationChain(resolvedTarget, carriesHeader = request.valueForHTTPHeaderField(RAFT_HOST_HEADER) != null)
        view.loadRequest(request)
    }

    /** Non-queued [RaftWebViewController.loadUrl]: loads [rawUrl] once with optional `Raft-Host`, without mutating [src]. */
    private fun loadControllerUrl(rawUrl: String) {
        val view = webView ?: return
        val target = rawUrl.trim()
        if (target.isEmpty()) return
        val url = NSURL.URLWithString(target)
        if (url == null) {
            if (allowedNavigationSchemes.isNotEmpty()) notifyNavigationBlocked(target) else notifyLoadFailure("invalid_preview_url")
            return
        }
        if (!isNavigationSchemeAllowed(url) || !isOnLockedOrigin(url) || (!allowNavigation && !isAllowedMainDocumentURL(url))) {
            handleBlockedMainFrameNavigation(target)
            return
        }
        lastSuccessfulURL = ""
        lastLoadFailureKey = ""
        currentLoading = true
        notifyNavigationState(force = true)
        val resolvedTarget = url.absoluteString ?: target
        val request = raftIosBuildRequest(url = url, cacheEnabled = cacheEnabled, raftHost = raftHost, includeHeader = true)
        startHostNavigationChain(resolvedTarget, carriesHeader = request.valueForHTTPHeaderField(RAFT_HOST_HEADER) != null)
        view.loadRequest(request)
    }

    private fun startHostNavigationChain(targetUrl: String, carriesHeader: Boolean) {
        pendingHostLoadUrl = targetUrl
        pendingHostLoadIsReissue = false
        hostCancelledUrl = null
        navInFlight = true
        navChainCarriesRaftHost = carriesHeader
        navChainReissueBudget = RAFT_HOST_REISSUE_BUDGET
        navChainStarter = if (hasCommittedDocument) RaftSignInStarter.Page else RaftSignInStarter.Launch
        navChainHops.clear()
    }

    private fun resetNavigationChain() {
        navChainCarriesRaftHost = false
        navChainReissueBudget = RAFT_HOST_REISSUE_BUDGET
        navChainStarter = RaftSignInStarter.Other
        navChainHops.clear()
    }

    private fun recordNavChainHop(url: String) {
        if (url.isNotEmpty() && navChainHops.size < 32) {
            navChainHops += url
        }
    }

    private fun buildReissuedRequest(
        original: NSURLRequest,
        url: NSURL,
        includeHeader: Boolean,
    ): NSMutableURLRequest {
        val mutable =
            (original.mutableCopy() as? NSMutableURLRequest)
                ?: NSMutableURLRequest.requestWithURL(
                    url,
                    cachePolicy = if (cacheEnabled) NSURLRequestUseProtocolCachePolicy else NSURLRequestReloadIgnoringLocalCacheData,
                    timeoutInterval = 60.0,
                )
        val headerValue =
            if (includeHeader) {
                url.absoluteString?.let { raftHostHeaderValueFor(it, raftHost) }
            } else {
                null
            }
        mutable.setValue(headerValue, forHTTPHeaderField = RAFT_HOST_HEADER)
        return mutable
    }

    // ---- delegate callbacks ----------------------------------------------------------------

    /**
     * A download link (`<a download>`, a `blob:` save): a host that asked keeps it in the app
     * ([RaftWebViewEvents.onDownloadRequested]) as a browser does, WKWebView downloading it
     * (WKDownload, the page's own cookies; [didBecomeDownload]).
     */
    internal fun decidePolicy(source: WKWebView, action: WKNavigationAction): RaftIosNavigationDecision {
        if (events.onDownloadRequested != null && action.shouldPerformDownload && raftIsSameObjCObject(source, webView)) {
            return RaftIosNavigationDecision.Download
        }
        val targetFrame = action.targetFrame
        val targetURL = action.request.URL
        if (targetFrame == null) {
            return if (
                decideNavigationAction(
                    source = source,
                    targetURL = targetURL,
                    isNewWindow = true,
                    isMainFrame = false,
                )
            ) {
                RaftIosNavigationDecision.Allow
            } else {
                RaftIosNavigationDecision.Cancel
            }
        }
        val isMainFrame = targetFrame.mainFrame
        if (!isMainFrame) {
            return if (decideNavigationPolicy(source, targetURL, isMainFrame = false)) {
                RaftIosNavigationDecision.Allow
            } else {
                RaftIosNavigationDecision.Cancel
            }
        }
        val method = action.request.HTTPMethod?.takeIf { it.isNotBlank() } ?: "GET"
        val requestCarriesHeader = !action.request.valueForHTTPHeaderField(RAFT_HOST_HEADER).isNullOrBlank()
        val outcome =
            decideMainFramePolicy(
                source = source,
                targetURL = targetURL,
                method = method,
                rawNavigationType = raftIosNavigationTypeRaw(action),
                requestCarriesHeader = requestCarriesHeader,
            )
        return when (outcome) {
            RaftIosMainFramePolicyOutcome.Allow -> RaftIosNavigationDecision.Allow
            RaftIosMainFramePolicyOutcome.Cancel -> RaftIosNavigationDecision.Cancel
            is RaftIosMainFramePolicyOutcome.Reissue -> {
                if (targetURL != null) {
                    val includeHeader = outcome is RaftIosMainFramePolicyOutcome.ReissueWithHeader
                    val reissued = buildReissuedRequest(action.request, targetURL, includeHeader = includeHeader)
                    pendingHostLoadUrl = targetURL.absoluteString ?: outcome.url
                    pendingHostLoadIsReissue = true
                    dispatch_async(dispatch_get_main_queue()) {
                        if (!disposed && raftIsSameObjCObject(source, webView)) {
                            source.loadRequest(reissued)
                        }
                    }
                }
                RaftIosNavigationDecision.Cancel
            }
        }
    }

    /**
     * Policy gate for [decidePolicy] that distinguishes new-window actions (`targetFrame == null`,
     * e.g. `target="_blank"`) from in-place main-frame/subframe navigations. New-window actions
     * are cancelled directly and reported via [notifyNavigationBlocked] without mutating
     * [currentURL] or emitting [RaftWebViewEvents.onUrlChange].
     */
    internal fun decideNavigationAction(
        source: WKWebView,
        targetURL: NSURL?,
        isNewWindow: Boolean,
        isMainFrame: Boolean,
    ): Boolean {
        if (!raftIsSameObjCObject(source, webView)) return false
        if (isNewWindow) {
            notifyNavigationBlocked(targetURL?.absoluteString ?: "")
            return false
        }
        return decideNavigationPolicy(source, targetURL, isMainFrame = isMainFrame)
    }

    /**
     * A response WKWebView would not show (an attachment, a type it can't display): a host that
     * asked keeps it in the app as a download; any other host loads as before.
     */
    internal fun decideResponsePolicy(source: WKWebView, response: WKNavigationResponse): RaftIosNavigationDecision {
        if (events.onDownloadRequested == null || !raftIsSameObjCObject(source, webView)) return RaftIosNavigationDecision.Allow
        val disposition = (response.response as? NSHTTPURLResponse)?.valueForHTTPHeaderField("Content-Disposition")
        val attachment = disposition?.trim()?.lowercase()?.startsWith("attachment") == true
        if (!attachment && response.canShowMIMEType) return RaftIosNavigationDecision.Allow
        return RaftIosNavigationDecision.Download
    }

    /** WKWebView turned a navigation into [download]: its file lands in a private temporary folder, then the host takes it. */
    internal fun didBecomeDownload(source: WKWebView, download: WKDownload) {
        if (!raftIsSameObjCObject(source, webView) || events.onDownloadRequested == null) {
            download.cancel(null)
            return
        }
        downloadCancelled = true
        val delegate = RaftIosWebViewDownloadDelegate(this)
        activeDownloads += delegate
        download.delegate = delegate
        diagnosticsInfo("page_download kind=wkdownload")
    }

    internal fun finishDownload(delegate: RaftIosWebViewDownloadDelegate, result: RaftWebViewDownload) {
        activeDownloads -= delegate
        if (disposed) return
        when (result) {
            is RaftWebViewDownload.Failed -> diagnosticsWarn("page_download result=failed reason=${result.reason}")
            is RaftWebViewDownload.Bytes -> diagnosticsInfo("page_download result=read bytes=${result.bytes.size}")
            is RaftWebViewDownload.Url -> Unit
        }
        events.onDownloadRequested?.invoke(result)
    }

    /**
     * The navigation fence. Split from [decidePolicy] because Kotlin/Native imports the
     * WKNavigationAction properties as final, so tests cannot stand one in; they call this or
     * [decideMainFramePolicy].
     */
    internal fun decideNavigationPolicy(source: WKWebView, targetURL: NSURL?, isMainFrame: Boolean): Boolean {
        if (!raftIsSameObjCObject(source, webView)) return false
        if (!isMainFrame) return true
        return decideMainFramePolicy(
            source = source,
            targetURL = targetURL,
            method = "GET",
            rawNavigationType = WK_NAVIGATION_TYPE_LINK_ACTIVATED,
            requestCarriesHeader = false,
        ) == RaftIosMainFramePolicyOutcome.Allow
    }

    /**
     * Pure main-frame policy gate testable in `iosTest` without faking final `WKNavigationAction`
     * (#356 §3/§5). Order:
     * 1. [RaftWebViewEvents.catchesMainFrameNavigation];
     * 2. existing scheme/origin/navigation fence and [RaftWebViewEvents.routesMainFrameNavigation];
     * 3. [raftHostNavigationDecision] (re-issue with `Raft-Host` on pre-commit same-origin GET
     *    redirects, or strip on cross-origin GET redirects where WebKit copied headers).
     */
    internal fun decideMainFramePolicy(
        source: WKWebView,
        targetURL: NSURL?,
        method: String = "GET",
        rawNavigationType: Long = WK_NAVIGATION_TYPE_LINK_ACTIVATED,
        requestCarriesHeader: Boolean = false,
    ): RaftIosMainFramePolicyOutcome {
        if (!raftIsSameObjCObject(source, webView)) return RaftIosMainFramePolicyOutcome.Cancel
        val targetString = targetURL?.absoluteString ?: ""
        val isPendingHostLoad = pendingHostLoadUrl != null && pendingHostLoadUrl == targetString
        val isPendingReissue = isPendingHostLoad && pendingHostLoadIsReissue
        if (isPendingHostLoad) {
            pendingHostLoadUrl = null
            pendingHostLoadIsReissue = false
        }
        if (isPendingReissue) {
            currentURL = targetString
            events.onUrlChange(currentURL)
            notifyNavigationState(force = false)
            return RaftIosMainFramePolicyOutcome.Allow
        }

        val navKind =
            if (isPendingHostLoad) {
                RaftHostNavigationKind.Initial
            } else {
                when (rawNavigationType) {
                    WK_NAVIGATION_TYPE_BACK_FORWARD -> {
                        navChainCarriesRaftHost = false
                        navChainReissueBudget = RAFT_HOST_REISSUE_BUDGET
                        navChainStarter = RaftSignInStarter.Other
                        navChainHops.clear()
                        RaftHostNavigationKind.History
                    }
                    WK_NAVIGATION_TYPE_RELOAD, WK_NAVIGATION_TYPE_FORM_RESUBMITTED -> {
                        navChainCarriesRaftHost = false
                        navChainReissueBudget = RAFT_HOST_REISSUE_BUDGET
                        navChainStarter = RaftSignInStarter.Other
                        navChainHops.clear()
                        RaftHostNavigationKind.Reload
                    }
                    WK_NAVIGATION_TYPE_OTHER -> {
                        if (navInFlight) {
                            RaftHostNavigationKind.Redirect
                        } else {
                            navChainCarriesRaftHost = false
                            navChainReissueBudget = RAFT_HOST_REISSUE_BUDGET
                            navChainStarter = if (hasCommittedDocument) RaftSignInStarter.Page else RaftSignInStarter.Other
                            navChainHops.clear()
                            RaftHostNavigationKind.Page
                        }
                    }
                    else -> {
                        navChainCarriesRaftHost = false
                        navChainReissueBudget = RAFT_HOST_REISSUE_BUDGET
                        navChainStarter = if (hasCommittedDocument) RaftSignInStarter.Page else RaftSignInStarter.Other
                        navChainHops.clear()
                        RaftHostNavigationKind.Page
                    }
                }
            }

        if (targetString.isNotEmpty()) {
            val catchAsk =
                RaftWebViewMainFrameCatchAsk(
                    url = targetString,
                    starter = navChainStarter,
                    page = committedDocumentUrl,
                    committed = hasCommittedDocument,
                    hops = navChainHops.toList(),
                )
            if (events.catchesMainFrameNavigation?.invoke(catchAsk) == true) {
                hostCancelledUrl = targetString
                navInFlight = false
                currentLoading = false
                notifyNavigationState(force = false)
                return RaftIosMainFramePolicyOutcome.Cancel
            }
        }

        if (targetURL == null || !isNavigationSchemeAllowed(targetURL) ||
            !isOnLockedOrigin(targetURL) ||
            (!allowNavigation && !isAllowedMainDocumentURL(targetURL))
        ) {
            navInFlight = false
            handleBlockedMainFrameNavigation(targetString)
            return RaftIosMainFramePolicyOutcome.Cancel
        }
        if (!isPendingHostLoad && events.routesMainFrameNavigation?.invoke(targetString) == true) {
            navInFlight = false
            diagnosticsInfo("navigation_routed_as_link url=${formatUrlForLog(targetString)}")
            handleBlockedMainFrameNavigation(targetString)
            return RaftIosMainFramePolicyOutcome.Cancel
        }

        val onScope = raftHostHeaderValueFor(targetString, raftHost) != null
        val carriesHeader =
            if (onScope) {
                requestCarriesHeader
            } else {
                requestCarriesHeader || (navKind == RaftHostNavigationKind.Redirect && navChainCarriesRaftHost)
            }
        val hostDecision =
            raftHostNavigationDecision(
                url = targetString,
                method = method,
                kind = navKind,
                carriesHeader = carriesHeader,
                host = raftHost,
                budgetLeft = navChainReissueBudget,
                committed = hasCommittedDocument,
            )
        recordNavChainHop(targetString)
        return when (hostDecision) {
            RaftHostNavigationDecision.ReissueWithHeader -> {
                navChainReissueBudget = (navChainReissueBudget - 1).coerceAtLeast(0)
                navChainCarriesRaftHost = true
                hostCancelledUrl = targetString
                navInFlight = true
                RaftIosMainFramePolicyOutcome.ReissueWithHeader(targetString)
            }
            RaftHostNavigationDecision.ReissueWithoutHeader -> {
                navChainCarriesRaftHost = false
                hostCancelledUrl = targetString
                navInFlight = true
                RaftIosMainFramePolicyOutcome.ReissueWithoutHeader(targetString)
            }
            RaftHostNavigationDecision.Allow -> {
                navChainCarriesRaftHost = requestCarriesHeader
                navInFlight = true
                currentURL = targetString
                events.onUrlChange(currentURL)
                notifyNavigationState(force = false)
                RaftIosMainFramePolicyOutcome.Allow
            }
        }
    }

    internal fun didStartProvisional(source: WKWebView) {
        if (!raftIsSameObjCObject(source, webView)) return
        currentURL = source.URL?.absoluteString ?: currentURL
        loadStartedAt = TimeSource.Monotonic.markNow()
        diagnosticsInfo("page_started url=${formatUrlForLog(currentURL)} ${loadElapsedField()}")
        currentTitle = ""
        currentProgress = (source.estimatedProgress * 100.0 + 0.5).toInt().coerceIn(0, 100)
        currentLoading = true
        // The new load's own signal: WebKit's progress need not pass through 0 between two loads.
        loadGeneration += 1
        lastSuccessfulURL = ""
        lastLoadFailureKey = ""
        notifyNavigationState(force = false)
    }

    internal fun didCommit(source: WKWebView) {
        if (!raftIsSameObjCObject(source, webView)) return
        if (raftWebViewIgnoresSpareBlankNavigation(usingSpare, source.URL?.absoluteString, src)) return
        pendingHostLoadUrl = null
        pendingHostLoadIsReissue = false
        hostCancelledUrl = null
        navInFlight = false
        currentURL = source.URL?.absoluteString ?: currentURL
        committedDocumentUrl = currentURL
        hasCommittedDocument = true
        navChainCarriesRaftHost = false
        navChainReissueBudget = RAFT_HOST_REISSUE_BUDGET
        navChainStarter = RaftSignInStarter.Page
        navChainHops.clear()
        currentTitle = source.title ?: currentTitle
        currentLoading = source.loading
        // Android's name for the first paint of the new document; WebKit's nearest is the commit.
        diagnosticsInfo("page_commit_visible url=${formatUrlForLog(currentURL)} ${loadElapsedField()}")
        if (!pageRevealed && revealTimer == null) {
            // Not shown yet: what the page paints right after its commit may not be its theme yet.
            revealTimer = NSTimer.scheduledTimerWithTimeInterval(RevealCapSeconds, repeats = false) { _ ->
                revealTimer = null
                reveal(source, "cap")
            }
            probePageTheme(source, "commit")
        }
        events.onUrlChange(currentURL)
        notifyNavigationState(force = false)
        // SlockSandboxWebViewPublishNavigationCommit: only the active WebView publishes commits
        // (guarded by the identity check at the top).
        events.onLoadCommit()
    }

    internal fun didFinish(source: WKWebView) {
        if (!raftIsSameObjCObject(source, webView)) return
        if (raftWebViewIgnoresSpareBlankNavigation(usingSpare, source.URL?.absoluteString, src)) return
        if (!pageFinished) {
            pageFinished = true
            if (!allowScripts || placeholderBackground == 0) {
                pagePainted = true
                reveal(source, "finished")
            } else {
                probePageTheme(source, "finished")
            }
        }
        currentURL = source.URL?.absoluteString ?: currentURL
        currentTitle = source.title ?: ""
        currentProgress = 100
        currentLoading = false
        diagnosticsInfo("page_finished url=${formatUrlForLog(currentURL)} ${loadElapsedField()}")
        events.onUrlChange(currentURL)
        notifyNavigationState(force = false)
        notifyLoadSuccess(currentURL)
    }

    /**
     * Reads the page's scheme and root/body background ([RaftWebViewPageThemeProbeScript]) and
     * logs it with the view's and host's trait style. After `finished`: white behind the page only
     * if it paints no background of its own; shown once its scheme is the app's (else at the cap).
     */
    private fun probePageTheme(view: WKWebView, phase: String) {
        view.evaluateJavaScript(RaftWebViewPageThemeProbeScript) { result, _ ->
            if (disposed || !raftIsSameObjCObject(view, webView)) return@evaluateJavaScript
            val theme = raftWebViewDecodePageTheme(result as? String)
            val appDark = RaftAppThemeState.isDark
            diagnosticsInfo(
                "page_theme phase=$phase ${theme?.logFields() ?: "probe=none"} appDark=$appDark " +
                    "viewStyle=${view.traitCollection.userInterfaceStyle} hostStyle=${container.traitCollection.userInterfaceStyle}"
            )
            if (phase != "finished") return@evaluateJavaScript
            if (theme == null || !raftWebViewPageHasOwnBackground(theme, placeholderBackground)) {
                pagePainted = true
                applyBackground()
            }
            if (theme == null || theme.prefersDark == appDark) reveal(view, "finished")
        }
    }

    private fun reveal(view: WKWebView, reason: String) {
        if (pageRevealed || disposed || !raftIsSameObjCObject(view, webView)) return
        pageRevealed = true
        revealTimer?.invalidate()
        revealTimer = null
        diagnosticsInfo("page_reveal reason=$reason ${loadElapsedField()}")
        applyBackground()
    }

    internal fun didFail(source: WKWebView, error: NSError) {
        if (!raftIsSameObjCObject(source, webView)) return
        val failingUrl = error.userInfo["NSErrorFailingURLStringKey"] as? String
        if (raftIosShouldSwallowHostCancelledFailure(hostCancelledUrl, error.domain, error.code, failingUrl)) {
            return
        }
        pendingHostLoadUrl = null
        pendingHostLoadIsReissue = false
        navInFlight = false
        navChainCarriesRaftHost = false
        navChainReissueBudget = RAFT_HOST_REISSUE_BUDGET
        navChainHops.clear()
        hostCancelledUrl = null
        // WebKitErrorFrameLoadInterruptedByPolicyChange after a download this host took.
        if (downloadCancelled && error.domain == "WebKitErrorDomain" && error.code == 102L) {
            downloadCancelled = false
            return
        }
        currentURL = source.URL?.absoluteString ?: currentURL
        currentLoading = false
        diagnosticsWarn("page_error code=${error.code} url=${formatUrlForLog(currentURL)} ${loadElapsedField()}")
        notifyNavigationState(force = false)
        notifyLoadFailure(error.localizedDescription)
    }

    /**
     * task #6: rebuild and reload after the web content process was reclaimed. The host's load
     * watchdog hears of it first: an error state when the page was not up yet (dm #102).
     */
    internal fun contentProcessDidTerminate(source: WKWebView) {
        if (!raftIsSameObjCObject(source, webView)) return
        events.onContentProcessGone("web_content_process_terminated")
        if (disposed) return
        lastSuccessfulURL = ""
        lastLoadFailureKey = ""
        // #300: the page that was shown (a kept Artifact viewer is routed in place from its warm src,
        // which the viewer would read as leaving its Artifact), if it may load here.
        val restoreUrl =
            raftWebViewRendererGoneRestoreUrl(src, currentURL, ::rendererGoneRestoreAllows)
        diagnosticsInfo("content_process_gone_restore url=${formatUrlForLog(restoreUrl)} shown=${restoreUrl != src}")
        rebuildWebView(restoreUrl)
        notifyNavigationState(force = true)
    }

    internal fun createWindowBlocked(source: WKWebView, action: WKNavigationAction) {
        if (!raftIsSameObjCObject(source, webView)) return
        notifyNavigationBlocked(action.request.URL?.absoluteString ?: "")
    }

    internal fun didReceiveScriptMessage(controller: WKUserContentController, message: WKScriptMessage) {
        val view = webView ?: return
        if (message.name != RaftWebViewHostPortName || !hostEventsEnabled) return
        if (!raftIsSameObjCObject(controller, view.configuration.userContentController) || !message.frameInfo.mainFrame) return
        val expectedURL = NSURL.URLWithString(src) ?: return
        val scheme = expectedURL.scheme?.lowercase() ?: return
        if (scheme != "https" && scheme != "http") return
        val origin = message.frameInfo.securityOrigin
        val defaultPort = if (scheme == "https") 443L else 80L
        val expectedPort = expectedURL.port?.longValue ?: defaultPort
        val actualPort = origin.port.toLong().takeIf { it > 0L } ?: defaultPort
        if (origin.protocol.lowercase() != scheme ||
            origin.host.lowercase() != expectedURL.host?.lowercase() ||
            actualPort != expectedPort
        ) {
            return
        }
        val activeURL = view.URL
        if (!allowNavigation && (activeURL == null || !isAllowedMainDocumentURL(activeURL))) return
        val body = message.body as? String ?: return
        events.dispatchHostMessage(body)
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun isNavigationSchemeAllowed(targetURL: NSURL): Boolean {
        if (allowedNavigationSchemes.isEmpty()) return true
        if (src.isEmpty() && targetURL.absoluteString == "about:blank") return true
        val scheme = targetURL.scheme?.lowercase() ?: return false
        return scheme.isNotEmpty() && scheme in allowedNavigationSchemes
    }

    private fun handleBlockedMainFrameNavigation(url: String) {
        currentLoading = false
        notifyNavigationState(force = false)
        notifyNavigationBlocked(url)
    }

    private fun notifyNavigationState(force: Boolean) {
        navigationStateRelay.notify(
            force = force,
            isActive = { !disposed },
            readState = {
                val view = webView
                RaftWebViewNavigationState(
                    url = currentURL,
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

    private fun notifyLoadSuccess(url: String) {
        if (lastSuccessfulURL == url) return
        lastSuccessfulURL = url
        lastLoadFailureKey = ""
        diagnosticsInfo("page_success phase=finished url=${formatUrlForLog(url)} ${loadElapsedField()}")
        events.onLoadSuccess()
    }

    private fun formatUrlForLog(url: String): String =
        if (diagnosticsSurface == SETTINGS_WEB_PAGE_DIAGNOSTICS_NAME) {
            raftOwnWebUrlForLog(url)
        } else {
            raftWebViewUrlForLog(url)
        }

    private fun notifyLoadFailure(reason: String) {
        val failureKey = "$currentURL|$reason"
        if (lastLoadFailureKey == failureKey) return
        lastLoadFailureKey = failureKey
        events.onLoadFailure(reason)
    }

    private fun notifyNavigationBlocked(url: String) {
        events.onNavigationBlocked(url)
    }

    /**
     * Load-path lines, with the field names of Android's `SlockWebView` lines (page_started,
     * page_commit_visible, page_finished, page_success, `elapsedMs`), to the app's diagnostics log.
     * URLs without query or fragment ([raftWebViewUrlForLog]): a Settings URL can carry tokens.
     */
    private fun diagnosticsInfo(message: String) {
        raftLogInfo(RaftIosWebViewDiagnosticsTag, message)
    }

    private fun diagnosticsWarn(message: String) {
        raftLogWarn(RaftIosWebViewDiagnosticsTag, message)
    }

    private fun loadElapsedField(): String =
        "elapsedMs=${loadStartedAt?.elapsedNow()?.inWholeMilliseconds ?: -1L} " +
            "sinceRequestMs=${requestedAt?.elapsedNow()?.inWholeMilliseconds ?: -1L} spare=$usingSpare"

    /**
     * Back is possible, not counting the spare's own blank document ([RaftIosWebViewSpare] loads
     * one to start WebKit): Back from the page's first document closes the page, as in a new WebView.
     */
    private fun canGoBack(view: WKWebView): Boolean {
        if (!view.canGoBack) return false
        if (!usingSpare) return true
        val backList = view.backForwardList.backList
        val first = backList.firstOrNull() as? WKBackForwardListItem
        return !(backList.size == 1 && first?.URL?.absoluteString == RaftIosWebViewSpare.BLANK_URL)
    }

    private fun isAllowedMainDocumentURL(targetURL: NSURL): Boolean {
        val allowed = allowedURL ?: return false
        if (targetURL.absoluteString == src) return true
        return (allowed.scheme ?: "") == (targetURL.scheme ?: "") &&
            (allowed.host ?: "") == (targetURL.host ?: "") &&
            (allowed.path ?: "") == (targetURL.path ?: "") &&
            (allowed.query ?: "") == (targetURL.query ?: "") &&
            (allowed.port?.longValue ?: -1L) == (targetURL.port?.longValue ?: -1L)
    }

    /**
     * Whether a WebView rebuilt after its web content process was gone may load [candidate] instead
     * of its src ([raftWebViewRendererGoneRestoreUrl]): an allowed scheme, on the origin lock, a main
     * document the fence lets load, and on the src's origin.
     */
    internal fun rendererGoneRestoreAllows(candidate: String): Boolean {
        val url = NSURL.URLWithString(candidate) ?: return false
        return isNavigationSchemeAllowed(url) && isOnLockedOrigin(url) &&
            (allowNavigation || isAllowedMainDocumentURL(url)) &&
            raftWebOriginOf(candidate) == raftWebOriginOf(src)
    }

    /** True unless an origin lock is set and [targetURL] is on another origin. */
    private fun isOnLockedOrigin(targetURL: NSURL): Boolean {
        val locked = mainFrameOrigin ?: return true
        return raftWebOriginOf(targetURL.absoluteString ?: "") == locked
    }

    private fun isSameOriginURL(targetURL: NSURL): Boolean {
        val allowed = allowedURL ?: return false
        val allowedScheme = allowed.scheme?.lowercase() ?: ""
        val targetScheme = targetURL.scheme?.lowercase() ?: ""
        val defaultPort = if (allowedScheme == "https") 443L else 80L
        val allowedPort = allowed.port?.longValue ?: defaultPort
        val targetPort = targetURL.port?.longValue ?: defaultPort
        return allowedScheme == targetScheme &&
            (allowed.host?.lowercase() ?: "") == (targetURL.host?.lowercase() ?: "") &&
            allowedPort == targetPort
    }
}

private class RaftIosWebViewDelegate(
    private val owner: RaftIosSandboxWebView,
) : NSObject(), WKNavigationDelegateProtocol, WKUIDelegateProtocol {
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit
    ) {
        decisionHandler(
            when (owner.decidePolicy(webView, decidePolicyForNavigationAction)) {
                RaftIosNavigationDecision.Allow -> WKNavigationActionPolicy.WKNavigationActionPolicyAllow
                RaftIosNavigationDecision.Cancel -> WKNavigationActionPolicy.WKNavigationActionPolicyCancel
                RaftIosNavigationDecision.Download -> WKNavigationActionPolicy.WKNavigationActionPolicyDownload
            }
        )
    }

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationResponse: WKNavigationResponse,
        decisionHandler: (WKNavigationResponsePolicy) -> Unit
    ) {
        decisionHandler(
            when (owner.decideResponsePolicy(webView, decidePolicyForNavigationResponse)) {
                RaftIosNavigationDecision.Allow -> WKNavigationResponsePolicy.WKNavigationResponsePolicyAllow
                RaftIosNavigationDecision.Cancel -> WKNavigationResponsePolicy.WKNavigationResponsePolicyCancel
                RaftIosNavigationDecision.Download -> WKNavigationResponsePolicy.WKNavigationResponsePolicyDownload
            }
        )
    }

    override fun webView(webView: WKWebView, navigationAction: WKNavigationAction, didBecomeDownload: WKDownload) {
        owner.didBecomeDownload(webView, didBecomeDownload)
    }

    override fun webView(webView: WKWebView, navigationResponse: WKNavigationResponse, didBecomeDownload: WKDownload) {
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
        windowFeatures: WKWindowFeatures
    ): WKWebView? {
        owner.createWindowBlocked(webView, forNavigationAction)
        return null
    }
}

internal enum class RaftIosNavigationDecision { Allow, Cancel, Download }

/** Main-frame policy outcome produced by [RaftIosSandboxWebView.decideMainFramePolicy] (#356 §3/§5). */
internal sealed interface RaftIosMainFramePolicyOutcome {
    data object Allow : RaftIosMainFramePolicyOutcome

    data object Cancel : RaftIosMainFramePolicyOutcome

    sealed interface Reissue : RaftIosMainFramePolicyOutcome {
        val url: String
    }

    data class ReissueWithHeader(override val url: String) : Reissue

    data class ReissueWithoutHeader(override val url: String) : Reissue
}

/** ABI-stable WebKit `WKNavigationType` raw values (iOS 8+). */
internal const val WK_NAVIGATION_TYPE_LINK_ACTIVATED: Long = 0L
internal const val WK_NAVIGATION_TYPE_FORM_SUBMITTED: Long = 1L
internal const val WK_NAVIGATION_TYPE_BACK_FORWARD: Long = 2L
internal const val WK_NAVIGATION_TYPE_RELOAD: Long = 3L
internal const val WK_NAVIGATION_TYPE_FORM_RESUBMITTED: Long = 4L
internal const val WK_NAVIGATION_TYPE_OTHER: Long = -1L

internal fun raftIosNavigationTypeRaw(action: WKNavigationAction): Long =
    when (val navType: Any = action.navigationType) {
        is Long -> navType
        is Int -> navType.toLong()
        is CEnum -> (navType.value as? Number)?.toLong() ?: WK_NAVIGATION_TYPE_OTHER
        else -> WK_NAVIGATION_TYPE_OTHER
    }

/**
 * Swallows cancelled provisional/committed load errors produced by host-initiated cancellations
 * (`NSURLErrorCancelled` / `-999`, or `WebKitErrorDomain` `102` when matching [hostCancelledUrl])
 * so `Raft-Host` re-issues and sign-in catches never surface as error panels.
 */
internal fun raftIosShouldSwallowHostCancelledFailure(
    hostCancelledUrl: String?,
    errorDomain: String?,
    errorCode: Long,
    failingUrl: String?,
): Boolean {
    if (hostCancelledUrl == null) return false
    val isCancelledError =
        (errorDomain == NSURLErrorDomain && errorCode == NSURLErrorCancelled) ||
            (errorDomain == "WebKitErrorDomain" && errorCode == 102L)
    if (!isCancelledError) return false
    return failingUrl.isNullOrEmpty() || failingUrl == hostCancelledUrl
}

/** Builds a request for [url] carrying `Raft-Host` only when [includeHeader] and [url] is on [raftHost]'s origin. */
internal fun raftIosBuildRequest(
    url: NSURL,
    cacheEnabled: Boolean,
    raftHost: RaftHostHeader?,
    includeHeader: Boolean = true,
): NSMutableURLRequest {
    val cachePolicy =
        if (cacheEnabled) NSURLRequestUseProtocolCachePolicy else NSURLRequestReloadIgnoringLocalCacheData
    val request = NSMutableURLRequest.requestWithURL(url, cachePolicy = cachePolicy, timeoutInterval = 60.0)
    val headerValue =
        if (includeHeader) {
            url.absoluteString?.let { raftHostHeaderValueFor(it, raftHost) }
        } else {
            null
        }
    if (headerValue != null) {
        request.setValue(headerValue, forHTTPHeaderField = RAFT_HOST_HEADER)
    }
    return request
}

/**
 * Fixed RFC 4122 UUID for the isolated persistent `WKWebsiteDataStore` holding third-party web
 * content (#proj-workspace:#397), separate from `WKWebsiteDataStore.defaultDataStore()`.
 */
internal const val RAFT_IOS_THIRD_PARTY_DATA_STORE_UUID = "9B4E6F20-5C8A-4D1E-A3B7-1F6C8E2D4A90"

private fun raftIosOsMajorVersion(): Long =
    NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion }

/**
 * Dedicated isolated `WKWebsiteDataStore` for [RaftWebViewStoragePartition.IsolatedThirdParty]:
 * - iOS 17+: persistent named store via `+[WKWebsiteDataStore dataStoreForIdentifier:]` with
 *   [RAFT_IOS_THIRD_PARTY_DATA_STORE_UUID] (isolated from `defaultDataStore()`, survives restarts).
 * - iOS 15–16 (or if the selector is unavailable): `nonPersistentDataStore()` (still 100% isolated).
 */
internal fun raftIosIsolatedThirdPartyDataStore(
    osMajorVersion: Long = raftIosOsMajorVersion(),
): WKWebsiteDataStore {
    if (osMajorVersion >= 17L) {
        val uuid = NSUUID(uUIDString = RAFT_IOS_THIRD_PARTY_DATA_STORE_UUID)
        return WKWebsiteDataStore.dataStoreForIdentifier(uuid)
    }
    return WKWebsiteDataStore.nonPersistentDataStore()
}

/**
 * One page download (WKDownload): its file goes to a fresh private temporary folder under the name
 * WebKit suggests (the page's `download` name, else the response's Content-Disposition, else the
 * address), then its bytes go to the host ([RaftWebViewDownload.Bytes]) and the folder is removed.
 */
internal class RaftIosWebViewDownloadDelegate(
    private val owner: RaftIosSandboxWebView,
) : NSObject(), WKDownloadDelegateProtocol {
    private var destination: NSURL? = null
    private var fileName: String = "download"
    private var mimeType: String? = null
    private var finished = false

    override fun download(
        download: WKDownload,
        decideDestinationUsingResponse: NSURLResponse,
        suggestedFilename: String,
        completionHandler: (NSURL?) -> Unit
    ) {
        val expected = decideDestinationUsingResponse.expectedContentLength
        if (expected > RaftWebViewDownloadMaxBytes) {
            completionHandler(null)
            finish(RaftWebViewDownload.Failed("too_large"))
            return
        }
        mimeType = decideDestinationUsingResponse.MIMEType
        val disposition = (decideDestinationUsingResponse as? NSHTTPURLResponse)?.valueForHTTPHeaderField("Content-Disposition")
        fileName =
            raftWebViewDownloadFileName(
                pageFileName = suggestedFilename,
                contentDisposition = disposition,
                url = decideDestinationUsingResponse.URL?.absoluteString ?: "",
                mimeType = mimeType,
            )
        val folder =
            NSURL.fileURLWithPath(NSTemporaryDirectory())
                .URLByAppendingPathComponent("raft-web-downloads", isDirectory = true)
                ?.URLByAppendingPathComponent(NSUUID().UUIDString, isDirectory = true)
        if (folder == null || !NSFileManager.defaultManager.createDirectoryAtURL(folder, true, null, null)) {
            completionHandler(null)
            finish(RaftWebViewDownload.Failed("storage_prepare"))
            return
        }
        val target = folder.URLByAppendingPathComponent(fileName, isDirectory = false)
        destination = target
        completionHandler(target)
    }

    override fun downloadDidFinish(download: WKDownload) {
        val target = destination
        val data: NSData? = target?.let { NSData.create(contentsOfURL = it) }
        target?.URLByDeletingLastPathComponent?.let { NSFileManager.defaultManager.removeItemAtURL(it, null) }
        finish(
            if (data == null) RaftWebViewDownload.Failed("read_failed")
            else RaftWebViewDownload.Bytes(data.toRaftDownloadByteArray(), fileName, mimeType)
        )
    }

    override fun download(download: WKDownload, didFailWithError: NSError, resumeData: NSData?) {
        destination?.URLByDeletingLastPathComponent?.let { NSFileManager.defaultManager.removeItemAtURL(it, null) }
        finish(RaftWebViewDownload.Failed("download_failed code=${didFailWithError.code}"))
    }

    private fun finish(result: RaftWebViewDownload) {
        if (finished) return
        finished = true
        owner.finishDownload(this, result)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toRaftDownloadByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return out
}

private class RaftIosWebViewScriptMessageHandler(
    private val owner: RaftIosSandboxWebView,
) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage
    ) {
        owner.didReceiveScriptMessage(userContentController, didReceiveScriptMessage)
    }
}

/** [argb] (0xAARRGGBB) as a UIColor. */
private fun raftIosColor(argb: Int): UIColor =
    UIColor.colorWithRed(
        red = ((argb ushr 16) and 0xFF) / 255.0,
        green = ((argb ushr 8) and 0xFF) / 255.0,
        blue = (argb and 0xFF) / 255.0,
        alpha = ((argb ushr 24) and 0xFF) / 255.0,
    )

/** [color]'s alpha (diagnostics); -1 without a colour. */
private fun raftIosColorAlpha(color: UIColor?): Double = color?.CGColor?.let { CGColorGetAlpha(it) } ?: -1.0

/** Android's `RAFT_WEBVIEW_DIAGNOSTICS_TAG`: the same tag for the same load lines. */
private const val RaftIosWebViewDiagnosticsTag = "SlockWebView"

/**
 * Whether a page may take [RaftIosWebViewSpare] instead of building a fresh [RaftIosWebView]:
 * the spare is built with the default persistent store, scripts on, and default viewport scale
 * limits (`ignoresViewportScaleLimits = false`). Surfaces that opt into [allowPinchZoom]
 * (#proj-workspace:#360, the Artifact viewer) or an isolated storage partition
 * ([RaftWebViewStoragePartition.IsolatedThirdParty], #proj-workspace:#397) build their own
 * WKWebView so their store and configuration take effect at initialization time.
 */
internal fun raftIosCanUseSpareWebView(
    ephemeral: Boolean,
    allowScripts: Boolean,
    allowPinchZoom: Boolean,
    hasExistingWebView: Boolean,
    storagePartition: RaftWebViewStoragePartition? = null,
): Boolean {
    val partition =
        storagePartition ?: if (ephemeral) RaftWebViewStoragePartition.Ephemeral else RaftWebViewStoragePartition.Default
    return partition == RaftWebViewStoragePartition.Default && allowScripts && !allowPinchZoom && !hasExistingWebView
}

/**
 * The configuration every page's WKWebView is built with (the spare too, so a page can take it):
 * [storagePartition] (or [ephemeral] when null) picks the default, non-persistent, or isolated
 * third-party store; [ignoresViewportScaleLimits] lets the reader pinch-to-zoom regardless of
 * viewport meta limits (#proj-workspace:#360). The user content controller (scripts, host bridge)
 * is per page and set by the caller.
 */
internal fun raftIosWebViewConfiguration(
    ephemeral: Boolean,
    allowScripts: Boolean,
    ignoresViewportScaleLimits: Boolean = false,
    storagePartition: RaftWebViewStoragePartition? = null,
    osMajorVersion: Long = raftIosOsMajorVersion(),
): WKWebViewConfiguration {
    val partition =
        storagePartition ?: if (ephemeral) RaftWebViewStoragePartition.Ephemeral else RaftWebViewStoragePartition.Default
    val configuration = WKWebViewConfiguration()
    configuration.websiteDataStore =
        when (partition) {
            RaftWebViewStoragePartition.Default -> WKWebsiteDataStore.defaultDataStore()
            RaftWebViewStoragePartition.Ephemeral -> WKWebsiteDataStore.nonPersistentDataStore()
            RaftWebViewStoragePartition.IsolatedThirdParty -> raftIosIsolatedThirdPartyDataStore(osMajorVersion)
        }
    configuration.allowsInlineMediaPlayback = true
    configuration.ignoresViewportScaleLimits = ignoresViewportScaleLimits
    configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
    configuration.mediaTypesRequiringUserActionForPlayback = WKAudiovisualMediaTypeAll
    val pagePreferences = WKWebpagePreferences()
    pagePreferences.allowsContentJavaScript = allowScripts
    configuration.defaultWebpagePreferences = pagePreferences
    return configuration
}

/** WKWebView applies the safe area and the keyboard itself. */
internal actual val raftWebViewNeedsHostBottomInsets: Boolean = false

/**
 * WKWebView lays a `width=1280` viewport meta out at 1280 CSS px and fits it to the view, pinch-zoomable,
 * with no flag. `pageZoom` (task #514, before) scaled CSS px without widening the layout: a page with
 * its own `width=device-width` (the Artifact page) drew at ~0.3x in the top-left corner (1.19.2).
 */
internal actual val raftWebViewDesktopModeViaViewportMeta: Boolean = true
