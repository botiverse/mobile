@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.objcPtr
import platform.CoreGraphics.CGColorGetAlpha
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSTimer
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLRequestUseProtocolCachePolicy
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.WebKit.WKAudiovisualMediaTypeAll
import platform.WebKit.WKBackForwardListItem
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
    private var injectedJavaScript = ""
    private var allowedNavigationSchemes: Set<String> = emptySet()
    private var hostEventsEnabled = false
    /** [RaftWebViewConfig.mainFrameOrigin]: every top-level document stays on it. */
    private var mainFrameOrigin: String? = null
    private var currentURL = ""
    private var currentTitle = ""
    private var currentLoading = false
    private var currentProgress = 0
    private var lastNavigationStateKey = ""
    private var lastSuccessfulURL = ""
    private var lastLoadFailureKey = ""
    /** A download was cancelled by policy: its "Frame load interrupted" is no load failure. */
    private var downloadCancelled = false
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
        val rebuild =
            !configured ||
                allowScripts != config.allowScripts ||
                allowNavigation != config.allowNavigation ||
                domStorageEnabled != config.domStorageEnabled ||
                ephemeral != config.ephemeral ||
                injectedJavaScript != config.injectedJavaScript ||
                hostEventsEnabled != config.hostEventsEnabled ||
                mainFrameOrigin != config.mainFrameOrigin?.let(::raftWebOriginOf)
        allowScripts = config.allowScripts
        // The auth user-script's main-frame scope is selected from this policy at configuration
        // time, so a policy change rebuilds (a broad script must not survive into a fenced document).
        allowNavigation = config.allowNavigation
        domStorageEnabled = config.domStorageEnabled
        cacheEnabled = config.cacheEnabled
        ephemeral = config.ephemeral
        injectedJavaScript = config.injectedJavaScript
        hostEventsEnabled = config.hostEventsEnabled
        mainFrameOrigin = config.mainFrameOrigin?.let(::raftWebOriginOf)
        configured = true
        if (placeholderBackground != config.backgroundArgb) {
            placeholderBackground = config.backgroundArgb
            applyBackground()
        }
        val srcChanged = updateSrcState(config.url.trim())
        if (rebuild) {
            rebuildWebView()
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
        val previous = webView ?: return
        stopObserving(previous)
        previous.navigationDelegate = null
        previous.UIDelegate = null
        previous.stopLoading()
        // WKUserContentController retains its handlers strongly; break the cycle explicitly.
        previous.configuration.userContentController.removeScriptMessageHandlerForName(RaftWebViewHostPortName)
        previous.removeFromSuperview()
    }

    private fun rebuildWebView() {
        tearDownWebView()
        // The app's spare WKWebView (created after launch, RaftIosWebViewSpare) when this page's
        // configuration is the one it was built with: persistent store, scripts on. It saves the
        // WebKit start-up (web content / networking processes, the data store) on the first load.
        // Not after a crash (contentProcessDidTerminate rebuilds while a WebView already existed).
        val spare = if (!ephemeral && allowScripts && webView == null) RaftIosWebViewSpare.take() else null
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
                    // Under an origin lock the script (a session) never enters a sub-frame: the content
                    // frame of another origin must not see it (Tenny's WebView rule 1).
                    forMainFrameOnly = mainFrameOrigin != null || !allowNavigation
                )
            )
        }
        val next =
            spare?.also { it.setFrame(container.bounds) }
                ?: run {
                    val configuration = raftIosWebViewConfiguration(ephemeral = ephemeral, allowScripts = allowScripts)
                    configuration.userContentController = userContentController
                    WKWebView(frame = container.bounds, configuration = configuration)
                }
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
        next.allowsBackForwardNavigationGestures = false
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
        lastNavigationStateKey = ""
        if (spare != null && placeholderBackground != 0) {
            // Until this page's document commits, the spare keeps showing its blank warm-up
            // document, which it laid out white (opaque, light): give it the canvas colour first.
            next.evaluateJavaScript(
                raftWebViewSpareBlankCanvasScript(placeholderBackground, RaftAppThemeState.isDark),
                completionHandler = null
            )
        }
        loadCurrentSrc()
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
            }
            else -> return
        }
        notifyNavigationState(force = true)
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
        return true
    }

    private fun loadCurrentSrc() {
        val view = webView ?: return
        if (src.isEmpty()) {
            currentURL = ""
            currentTitle = ""
            currentProgress = 0
            currentLoading = false
            notifyNavigationState(force = false)
            view.loadHTMLString("", baseURL = null)
            return
        }
        val url = NSURL.URLWithString(src)
        if (url == null) {
            notifyNavigationState(force = false)
            if (allowedNavigationSchemes.isNotEmpty()) notifyNavigationBlocked(src) else notifyLoadFailure("invalid_preview_url")
            return
        }
        if (!isNavigationSchemeAllowed(url) || !isOnLockedOrigin(url)) {
            notifyNavigationState(force = false)
            notifyNavigationBlocked(src)
            return
        }
        currentLoading = true
        notifyNavigationState(force = false)
        requestedAt = TimeSource.Monotonic.markNow()
        diagnosticsInfo(
            "load_src spare=$usingSpare sinceAttachMs=${attachedAt.elapsedNow().inWholeMilliseconds} " +
                "url=${raftWebViewUrlForLog(src)}"
        )
        val cachePolicy = if (cacheEnabled) NSURLRequestUseProtocolCachePolicy else NSURLRequestReloadIgnoringLocalCacheData
        view.loadRequest(NSURLRequest.requestWithURL(url, cachePolicy = cachePolicy, timeoutInterval = 60.0))
    }

    // ---- delegate callbacks ----------------------------------------------------------------

    internal fun decidePolicy(source: WKWebView, action: WKNavigationAction): Boolean {
        // A download link (`<a download>`, e.g. a `blob:` save): WKWebView has no download here, so a
        // host that asked takes it ([RaftWebViewEvents.onDownloadRequested]).
        val download = events.onDownloadRequested
        if (download != null && action.shouldPerformDownload && raftIsSameObjCObject(source, webView)) {
            downloadCancelled = true
            download(action.request.URL?.absoluteString ?: "")
            return false
        }
        return decideNavigationPolicy(source, action.request.URL, isMainFrame = action.targetFrame?.mainFrame ?: true)
    }

    /**
     * A response WKWebView would not show (an attachment, a type it can't display): a host that
     * asked takes it as a download; any other host loads as before.
     */
    internal fun decideResponsePolicy(source: WKWebView, response: WKNavigationResponse): Boolean {
        val download = events.onDownloadRequested ?: return true
        if (!raftIsSameObjCObject(source, webView)) return true
        val disposition = (response.response as? NSHTTPURLResponse)?.valueForHTTPHeaderField("Content-Disposition")
        val attachment = disposition?.trim()?.lowercase()?.startsWith("attachment") == true
        if (!attachment && response.canShowMIMEType) return true
        downloadCancelled = true
        download(response.response.URL?.absoluteString ?: "")
        return false
    }

    /**
     * The navigation fence. Split from [decidePolicy] because Kotlin/Native imports the
     * WKNavigationAction properties as final, so tests cannot stand one in; they call this.
     */
    internal fun decideNavigationPolicy(source: WKWebView, targetURL: NSURL?, isMainFrame: Boolean): Boolean {
        if (!raftIsSameObjCObject(source, webView)) return false
        if (isMainFrame) {
            if (targetURL == null || !isNavigationSchemeAllowed(targetURL) ||
                !isOnLockedOrigin(targetURL) ||
                (!allowNavigation && !isAllowedMainDocumentURL(targetURL))
            ) {
                handleBlockedMainFrameNavigation(targetURL?.absoluteString ?: "")
                return false
            }
            currentURL = targetURL.absoluteString ?: ""
            events.onUrlChange(currentURL)
            notifyNavigationState(force = false)
        }
        return true
    }

    internal fun didStartProvisional(source: WKWebView) {
        if (!raftIsSameObjCObject(source, webView)) return
        currentURL = source.URL?.absoluteString ?: currentURL
        loadStartedAt = TimeSource.Monotonic.markNow()
        diagnosticsInfo("page_started url=${raftWebViewUrlForLog(currentURL)} ${loadElapsedField()}")
        currentTitle = ""
        currentProgress = (source.estimatedProgress * 100.0 + 0.5).toInt().coerceIn(0, 100)
        currentLoading = true
        lastSuccessfulURL = ""
        lastLoadFailureKey = ""
        notifyNavigationState(force = false)
    }

    internal fun didCommit(source: WKWebView) {
        if (!raftIsSameObjCObject(source, webView)) return
        if (raftWebViewIgnoresSpareBlankNavigation(usingSpare, source.URL?.absoluteString, src)) return
        currentURL = source.URL?.absoluteString ?: currentURL
        currentTitle = source.title ?: currentTitle
        currentLoading = source.loading
        // Android's name for the first paint of the new document; WebKit's nearest is the commit.
        diagnosticsInfo("page_commit_visible url=${raftWebViewUrlForLog(currentURL)} ${loadElapsedField()}")
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
        diagnosticsInfo("page_finished url=${raftWebViewUrlForLog(currentURL)} ${loadElapsedField()}")
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
        if (!raftIsSameObjCObject(source, webView) || (error.domain == NSURLErrorDomain && error.code == NSURLErrorCancelled)) return
        // WebKitErrorFrameLoadInterruptedByPolicyChange after a download this host took.
        if (downloadCancelled && error.domain == "WebKitErrorDomain" && error.code == 102L) {
            downloadCancelled = false
            return
        }
        currentURL = source.URL?.absoluteString ?: currentURL
        currentLoading = false
        diagnosticsWarn("page_error code=${error.code} url=${raftWebViewUrlForLog(currentURL)} ${loadElapsedField()}")
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
        rebuildWebView()
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
        if (disposed) return
        val view = webView
        val state =
            RaftWebViewNavigationState(
                url = currentURL,
                title = currentTitle,
                canGoBack = view?.let(::canGoBack) ?: false,
                canGoForward = view?.canGoForward ?: false,
                isLoading = currentLoading,
                progress = currentProgress.coerceIn(0, 100)
            )
        val stateKey = state.toString()
        if (!force && stateKey == lastNavigationStateKey) return
        lastNavigationStateKey = stateKey
        events.onNavigationStateChange(state)
    }

    private fun notifyLoadSuccess(url: String) {
        if (lastSuccessfulURL == url) return
        lastSuccessfulURL = url
        lastLoadFailureKey = ""
        diagnosticsInfo("page_success phase=finished url=${raftWebViewUrlForLog(url)} ${loadElapsedField()}")
        events.onLoadSuccess()
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
        val allow = owner.decidePolicy(webView, decidePolicyForNavigationAction)
        decisionHandler(
            if (allow) WKNavigationActionPolicy.WKNavigationActionPolicyAllow
            else WKNavigationActionPolicy.WKNavigationActionPolicyCancel
        )
    }

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationResponse: WKNavigationResponse,
        decisionHandler: (WKNavigationResponsePolicy) -> Unit
    ) {
        val allow = owner.decideResponsePolicy(webView, decidePolicyForNavigationResponse)
        decisionHandler(
            if (allow) WKNavigationResponsePolicy.WKNavigationResponsePolicyAllow
            else WKNavigationResponsePolicy.WKNavigationResponsePolicyCancel
        )
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
 * The configuration every page's WKWebView is built with (the spare too, so a page can take it):
 * [ephemeral] picks the non-persistent store, otherwise the app's default one. The user content
 * controller (scripts, host bridge) is per page and set by the caller.
 */
internal fun raftIosWebViewConfiguration(ephemeral: Boolean, allowScripts: Boolean): WKWebViewConfiguration {
    val configuration = WKWebViewConfiguration()
    configuration.websiteDataStore =
        if (ephemeral) WKWebsiteDataStore.nonPersistentDataStore() else WKWebsiteDataStore.defaultDataStore()
    configuration.allowsInlineMediaPlayback = true
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
