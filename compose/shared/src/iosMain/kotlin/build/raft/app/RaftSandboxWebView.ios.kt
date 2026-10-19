@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlin.math.roundToInt
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSTimer
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLRequestUseProtocolCachePolicy
import platform.UIKit.UIColor
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.WebKit.WKAudiovisualMediaTypeAll
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
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

@Composable
internal actual fun RaftSandboxWebViewHost(
    config: RaftWebViewConfig,
    events: RaftWebViewEvents,
    controller: SlockWebViewController,
    modifier: Modifier,
) {
    UIKitView(
        factory = { RaftIosSandboxWebViewContainer(events) },
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

/** Poll interval standing in for the `.m` KVO on estimatedProgress/title/canGoBack/canGoForward/loading/URL. */
private const val RaftIosNavigationStatePollSeconds = 0.25

private const val RaftIosHostBridgeScript =
    "(function(){var h=window.webkit&&window.webkit.messageHandlers&&window.webkit.messageHandlers.raftHostPort;if(!h)return;" +
        "Object.defineProperty(window,\"raftHostPort\",{value:{postMessage:function(m){h.postMessage(m);}},writable:false,configurable:false});" +
        "Object.defineProperty(window,\"RaftHost\",{value:Object.freeze({version:\"raft-host-v1\",capabilities:[\"raft-feedback-v1\"]," +
        "emit:function(k,p){if(typeof k===\"string\")h.postMessage(JSON.stringify({kind:k,payload:(p&&typeof p===\"object\"&&!Array.isArray(p))?p:{}}));}})," +
        "writable:false,configurable:false});})();"

/**
 * Port of the Kuikly iOS `SlockSandboxWebView.m`: WKWebView rebuilt whenever a configuration-time
 * attribute changes (scripts, navigation policy, storage, ephemeral store, injected script, host
 * bridge), sandbox navigation fence, `raftHostPort` bridge with origin + document checks, KVO
 * navigation state, desktop `pageZoom` (task #514) and web content process recovery (task #6).
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
    private var useWideViewPort = false
    private var allowedNavigationSchemes: Set<String> = emptySet()
    private var hostEventsEnabled = false
    private var currentURL = ""
    private var currentTitle = ""
    private var currentLoading = false
    private var currentProgress = 0
    private var lastNavigationStateKey = ""
    private var lastSuccessfulURL = ""
    private var lastLoadFailureKey = ""
    private var stateTimer: NSTimer? = null
    private var disposed = false
    private var configured = false
    private var boundController: SlockWebViewController? = null

    private val delegate = RaftIosWebViewDelegate(this)
    private val scriptMessageHandler = RaftIosWebViewScriptMessageHandler(this)

    init {
        container.backgroundColor = UIColor.whiteColor
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
                hostEventsEnabled != config.hostEventsEnabled
        allowScripts = config.allowScripts
        // The auth user-script's main-frame scope is selected from this policy at configuration
        // time, so a policy change rebuilds (a broad script must not survive into a fenced document).
        allowNavigation = config.allowNavigation
        domStorageEnabled = config.domStorageEnabled
        cacheEnabled = config.cacheEnabled
        ephemeral = config.ephemeral
        injectedJavaScript = config.injectedJavaScript
        hostEventsEnabled = config.hostEventsEnabled
        configured = true
        val srcChanged = updateSrcState(config.url.trim())
        if (rebuild) {
            rebuildWebView()
        } else if (srcChanged) {
            loadCurrentSrc()
        }
        updateAllowedNavigationSchemes(config.allowedNavigationSchemes)
        if (useWideViewPort != config.useWideViewPort) {
            useWideViewPort = config.useWideViewPort
            applyDesktopModeScale()
        }
    }

    fun bindController(controller: SlockWebViewController) {
        if (boundController === controller) return
        boundController?.unbindCommandDispatcher(this)
        boundController = controller
        controller.bindCommandDispatcher(this) { command -> executeNavigationCommand(command) }
    }

    fun layout() {
        webView?.setFrame(container.bounds)
        // task #514: the widen flag can arrive before the first layout pass (width 0).
        applyDesktopModeScale()
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
        val configuration = WKWebViewConfiguration()
        configuration.websiteDataStore =
            if (ephemeral) WKWebsiteDataStore.nonPersistentDataStore() else WKWebsiteDataStore.defaultDataStore()
        val userContentController = WKUserContentController()
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
        if (allowScripts && injectedJavaScript.isNotEmpty()) {
            userContentController.addUserScript(
                WKUserScript(
                    source = injectedJavaScript,
                    injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                    forMainFrameOnly = !allowNavigation
                )
            )
        }
        configuration.userContentController = userContentController
        configuration.allowsInlineMediaPlayback = true
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        configuration.mediaTypesRequiringUserActionForPlayback = WKAudiovisualMediaTypeAll
        val pagePreferences = WKWebpagePreferences()
        pagePreferences.allowsContentJavaScript = allowScripts
        configuration.defaultWebpagePreferences = pagePreferences

        val next = WKWebView(frame = container.bounds, configuration = configuration)
        next.navigationDelegate = delegate
        next.UIDelegate = delegate
        next.backgroundColor = UIColor.whiteColor
        next.opaque = true
        next.allowsBackForwardNavigationGestures = false
        next.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        webView = next
        container.addSubview(next)
        startObserving(next)
        currentProgress = 0
        currentLoading = false
        currentTitle = ""
        lastNavigationStateKey = ""
        applyDesktopModeScale()
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
        if (source !== view) return
        currentURL = view.URL?.absoluteString ?: currentURL
        currentTitle = view.title ?: ""
        currentProgress = (view.estimatedProgress * 100.0 + 0.5).toInt().coerceIn(0, 100)
        currentLoading = view.loading
        notifyNavigationState(force = false)
    }

    /** task #514: desktop fit via `pageZoom`, ratio from the shared formula (physical pixels). */
    private fun applyDesktopModeScale() {
        val view = webView ?: return
        if (!useWideViewPort) {
            if (view.pageZoom != 1.0) view.pageZoom = 1.0
            return
        }
        val widthPoints = container.bounds.useContents { size.width }
        if (widthPoints <= 0.0) return
        val scale = view.window?.screen?.scale?.takeIf { it > 0.0 } ?: UIScreen.mainScreen.scale.takeIf { it > 0.0 } ?: 1.0
        val viewportPx = (widthPoints * scale).roundToInt()
        val percent = slockFilePreviewDesktopScalePercent(viewportPx, scale.toFloat()) ?: return
        view.pageZoom = maxOf(percent / 100.0, 0.01)
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
        if (command.startsWith(SlockWebViewController.COMMAND_EVALUATE_JAVA_SCRIPT_PREFIX)) {
            val script = command.removePrefix(SlockWebViewController.COMMAND_EVALUATE_JAVA_SCRIPT_PREFIX)
            if (script.length > RaftWebViewMaxRuntimeJavaScriptLength) return
            val activeURL = view.URL
            val sameTrustedDocument =
                activeURL != null && isNavigationSchemeAllowed(activeURL) && isSameOriginURL(activeURL) &&
                    (allowNavigation || isAllowedMainDocumentURL(activeURL))
            if (sameTrustedDocument && script.isNotEmpty()) view.evaluateJavaScript(script, completionHandler = null)
            return
        }
        when (command) {
            SlockWebViewController.COMMAND_GO_BACK -> if (view.canGoBack) view.goBack()
            SlockWebViewController.COMMAND_GO_FORWARD -> if (view.canGoForward) view.goForward()
            SlockWebViewController.COMMAND_RELOAD -> {
                lastSuccessfulURL = ""
                lastLoadFailureKey = ""
                currentProgress = 0
                currentLoading = true
                view.reload()
            }
            SlockWebViewController.COMMAND_STOP -> {
                view.stopLoading()
                currentLoading = false
            }
            else -> return
        }
        notifyNavigationState(force = true)
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
        if (!isNavigationSchemeAllowed(url)) {
            notifyNavigationState(force = false)
            notifyNavigationBlocked(src)
            return
        }
        currentLoading = true
        notifyNavigationState(force = false)
        val cachePolicy = if (cacheEnabled) NSURLRequestUseProtocolCachePolicy else NSURLRequestReloadIgnoringLocalCacheData
        view.loadRequest(NSURLRequest.requestWithURL(url, cachePolicy = cachePolicy, timeoutInterval = 60.0))
    }

    // ---- delegate callbacks ----------------------------------------------------------------

    internal fun decidePolicy(source: WKWebView, action: WKNavigationAction): Boolean {
        if (source !== webView) return false
        val targetURL = action.request.URL
        val isMainFrame = action.targetFrame?.mainFrame ?: true
        if (isMainFrame) {
            if (targetURL == null || !isNavigationSchemeAllowed(targetURL) ||
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
        if (source !== webView) return
        currentURL = source.URL?.absoluteString ?: currentURL
        currentTitle = ""
        currentProgress = (source.estimatedProgress * 100.0 + 0.5).toInt().coerceIn(0, 100)
        currentLoading = true
        lastSuccessfulURL = ""
        lastLoadFailureKey = ""
        notifyNavigationState(force = false)
    }

    internal fun didCommit(source: WKWebView) {
        if (source !== webView) return
        currentURL = source.URL?.absoluteString ?: currentURL
        currentTitle = source.title ?: currentTitle
        currentLoading = source.loading
        events.onUrlChange(currentURL)
        notifyNavigationState(force = false)
        // SlockSandboxWebViewPublishNavigationCommit: only the active WebView publishes commits.
        if (source === webView) events.onLoadCommit()
    }

    internal fun didFinish(source: WKWebView) {
        if (source !== webView) return
        currentURL = source.URL?.absoluteString ?: currentURL
        currentTitle = source.title ?: ""
        currentProgress = 100
        currentLoading = false
        events.onUrlChange(currentURL)
        notifyNavigationState(force = false)
        notifyLoadSuccess(currentURL)
    }

    internal fun didFail(source: WKWebView, error: NSError) {
        if (source !== webView || (error.domain == NSURLErrorDomain && error.code == NSURLErrorCancelled)) return
        currentURL = source.URL?.absoluteString ?: currentURL
        currentLoading = false
        notifyNavigationState(force = false)
        notifyLoadFailure(error.localizedDescription)
    }

    /** task #6: rebuild and reload after the web content process was reclaimed. */
    internal fun contentProcessDidTerminate(source: WKWebView) {
        if (source !== webView) return
        lastSuccessfulURL = ""
        lastLoadFailureKey = ""
        rebuildWebView()
        notifyNavigationState(force = true)
    }

    internal fun createWindowBlocked(source: WKWebView, action: WKNavigationAction) {
        if (source !== webView) return
        notifyNavigationBlocked(action.request.URL?.absoluteString ?: "")
    }

    internal fun didReceiveScriptMessage(controller: WKUserContentController, message: WKScriptMessage) {
        val view = webView ?: return
        if (message.name != RaftWebViewHostPortName || !hostEventsEnabled) return
        if (controller !== view.configuration.userContentController || !message.frameInfo.mainFrame) return
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
            SlockWebViewNavigationState(
                url = currentURL,
                title = currentTitle,
                canGoBack = view?.canGoBack ?: false,
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

    private fun isAllowedMainDocumentURL(targetURL: NSURL): Boolean {
        val allowed = allowedURL ?: return false
        if (targetURL.absoluteString == src) return true
        return (allowed.scheme ?: "") == (targetURL.scheme ?: "") &&
            (allowed.host ?: "") == (targetURL.host ?: "") &&
            (allowed.path ?: "") == (targetURL.path ?: "") &&
            (allowed.query ?: "") == (targetURL.query ?: "") &&
            (allowed.port?.longValue ?: -1L) == (targetURL.port?.longValue ?: -1L)
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
