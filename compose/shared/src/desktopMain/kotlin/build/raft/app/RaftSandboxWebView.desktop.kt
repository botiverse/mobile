package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.awt.Component
import javax.swing.SwingUtilities
import kotlinx.coroutines.delay

/**
 * Factory for the desktop WebView's off-EDT Wry bridge. Production always uses
 * [RaftDesktopWryGateway.create]; desktop Compose UI tests can substitute a fake bridge without
 * global mutable state.
 */
internal val LocalRaftDesktopWryBridgeFactory =
    staticCompositionLocalOf<(policy: RaftDesktopWebViewPolicy, ephemeral: Boolean) -> RaftDesktopWryBridge> {
        { policy, ephemeral -> RaftDesktopWryGateway.create(policy, ephemeral) }
    }

/**
 * Desktop sandbox WebView: the OS engine (macOS WKWebView, Windows WebView2, Linux WebKitGTK)
 * through Wry (`io.github.kdroidfilter:wrywebview`), embedded in the Compose window as a native
 * child view via the single off-EDT gateway ([RaftDesktopWryGateway]). Security rules:
 * [RaftDesktopWebViewPolicy].
 *
 * Wry's engine has no switch to turn JavaScript off, so a surface that asks for no scripts keeps
 * "Open in browser" (fail closed). Any change to the policy inputs or the data store rebuilds the WebView.
 *
 * Windows offers "Open in browser" too for now: WebView2 injects the document-start script into every
 * frame, a path not yet verified on a Windows machine (Tenny, #3968); it ships separately.
 */
@Composable
internal actual fun RaftSandboxWebViewHost(
    config: RaftWebViewConfig,
    events: RaftWebViewEvents,
    controller: RaftWebViewController,
    modifier: Modifier,
) {
    if (!config.allowScripts || RaftDesktopWebViewOnWindows) {
        // The Artifact viewer loads an embed URL and has its own Open in browser in its top bar,
        // so it gets no second button here.
        RaftDesktopOpenInBrowser(
            config.url
                .takeIf { config.diagnosticsSurface != RAFT_ARTIFACT_VIEWER_WEBVIEW_SURFACE }
                ?.let { raw ->
                    if (config.diagnosticsSurface == SETTINGS_WEB_PAGE_DIAGNOSTICS_NAME) {
                        stripRaftOwnWebPageEmbedParams(raw)
                    } else {
                        raw
                    }
                },
            kind = "web_content",
            modifier = modifier,
        )
        return
    }
    val bridgeFactory = LocalRaftDesktopWryBridgeFactory.current
    val policy =
        remember(
            config.url,
            config.allowNavigation,
            config.allowedNavigationSchemes,
            config.mainFrameOrigin,
            config.hostEventsEnabled,
            config.injectedJavaScript,
            config.frameHosts,
        ) {
            RaftDesktopWebViewPolicy(
                url = config.url,
                allowNavigation = config.allowNavigation,
                allowedNavigationSchemes = config.allowedNavigationSchemes,
                mainFrameOrigin = config.mainFrameOrigin,
                hostEventsEnabled = config.hostEventsEnabled,
                injectedJavaScript = config.injectedJavaScript,
                frameHosts = config.frameHosts,
            )
        }
    val effectiveEphemeral = config.effectiveStoragePartition != RaftWebViewStoragePartition.Default
    key(policy, effectiveEphemeral) {
        var pageReady by remember { mutableStateOf(false) }
        val displayAllowed = !config.coverActive
        val host =
            remember {
                RaftDesktopWryWebView(
                    policy = policy,
                    bridge = bridgeFactory(policy, effectiveEphemeral),
                    events = events,
                    initialDisplayAllowed = displayAllowed,
                    onReadyChanged = { pageReady = it },
                )
            }
        host.events = events
        val showNative = pageReady && displayAllowed
        LaunchedEffect(host, displayAllowed) {
            host.setDisplayAllowed(displayAllowed)
        }
        // #wg-mobile-sync:#242: SwingPanel registers a Skiko ClipRectangle for its clipped Compose
        // bounds, which would punch a blank hole through Compose loading covers (like
        // RaftArtifactViewerSplashCover). While loading or covered, clip the SwingPanel slot to a
        // 1dp corner (non-empty so Wry's host Canvas stays showing and initializes) while keeping
        // the interop view at full viewport dimensions via requiredSize so the web page lays out
        // off-screen at its real width, then expand the same SwingPanel in place once ready.
        BoxWithConstraints(modifier = modifier) {
            val fullWidth = maxWidth.coerceAtLeast(1.dp)
            val fullHeight = maxHeight.coerceAtLeast(1.dp)
            Box(
                modifier =
                    if (showNative) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.align(Alignment.BottomEnd).size(1.dp).clipToBounds()
                    },
            ) {
                SwingPanel(
                    background = if (showNative) Color.White else Color.Transparent,
                    factory = { host.view },
                    modifier =
                        if (showNative) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier.requiredSize(fullWidth, fullHeight)
                        },
                )
            }
        }
        DisposableEffect(controller) {
            host.bind(controller)
            onDispose { host.unbind(controller) }
        }
        DisposableEffect(Unit) { onDispose { host.dispose() } }
        LaunchedEffect(Unit) {
            host.start()
            while (true) {
                host.poll()
                delay(host.nextPollDelayMs())
            }
        }
    }
}

private val RaftDesktopWebViewOnWindows: Boolean =
    System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")

/** Poll interval for address / loading / title / host messages (iOS polls at the same rate). */
private const val RaftDesktopWebViewPollMs = 250L

/** Poll interval while a declared-frame navigation ([RaftDesktopWebViewNavigation.AllowUnverifiedFrame]) is unverified. */
private const val RaftDesktopWebViewVerifyMs = 50L

/**
 * Longest the WebView stays hidden after its first load finished while the page still looks busy
 * ([RaftDesktopWebViewReadyScript]); then it shows anyway.
 */
private const val RaftDesktopWebViewRevealDeadlineMs = 2_500L

/**
 * Whether the page is past its loading frame: loaded, and no `aria-busy` region left (the web's
 * boot shell and its pending server frame are `aria-busy`; ShellFrame `pending`). Until then the
 * native view stays off screen and the host's own loading state shows, so the web's full-app
 * skeleton never appears inside a Settings section.
 */
private const val RaftDesktopWebViewReadyScript =
    "(function(){try{return document.readyState==='complete'&&!!document.body&&" +
        "!document.querySelector('[aria-busy=\"true\"]')?'1':'0'}catch(e){return '0'}})()"

/** Times the host takes a fenced-off top-level page back to the last trusted address before giving up. */
private const val RaftDesktopWebViewMaxRestores = 2

/**
 * One native WebView host state machine.
 *
 * Everything except [onNavigation] (Wry calls it on the engine's thread) runs on the AWT EDT,
 * while ALL blocking native Wry calls run strictly off-EDT via [bridge] ([RaftDesktopWryGateway])
 * so the AWT EDT never synchronously waits on macOS Thread 0.
 */
internal class RaftDesktopWryWebView(
    private val policy: RaftDesktopWebViewPolicy,
    private val bridge: RaftDesktopWryBridge,
    var events: RaftWebViewEvents,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val postToEdt: (() -> Unit) -> Unit = { SwingUtilities.invokeLater(it) },
    initialDisplayAllowed: Boolean = true,
    private val onReadyChanged: (Boolean) -> Unit = {},
) {
    private val initialUrl = policy.initialUrl()

    /** What Compose hosts: keeps the native view off screen until [revealWhenReady] and when the page leaves. */
    val view: Component get() = bridge.component

    private var pageReady = false
    private var displayAllowed = initialDisplayAllowed
    private var bridgeShown = false
    private var loadFinishedAt = 0L
    private var readyCheckPending = false
    private var pollInFlight = false

    @Volatile private var disposed = false
    @Volatile private var verifySoon = false
    private val topLevelCheck = RaftDesktopWebViewTopLevelCheck()
    private var boundController: RaftWebViewController? = null
    private var currentUrl = initialUrl ?: ""
    private var lastTrustedUrl: String? = initialUrl
    private var lastBlockedUrl = ""
    private var restores = 0
    private var currentTitle = ""
    private var canGoBack = false
    private var canGoForward = false
    private var loading = false
    private var progress = 0

    /** Main-frame loads started ([RaftWebViewNavigationState.loadGeneration]). */
    private var loadGeneration = 0
    private var commitSent = false
    private val navigationStateRelay = RaftWebViewNavigationStateRelay()

    init {
        // Wry asks for every navigation, main frame and sub-frames alike (URL only).
        bridge.addNavigateListener(::onNavigation)
    }

    fun start() {
        if (initialUrl == null) notifyNavigationBlocked(policy.url.trim())
        notifyState(force = true)
    }

    /** Quick polls while a declared-frame navigation is unverified (its timeout) or the page is about to show. */
    fun nextPollDelayMs(): Long =
        if (verifySoon || topLevelCheck.armed || (!bridgeShown && loadFinishedAt != 0L)) {
            verifySoon = false
            RaftDesktopWebViewVerifyMs
        } else {
            RaftDesktopWebViewPollMs
        }

    fun setDisplayAllowed(allowed: Boolean) {
        if (displayAllowed == allowed) return
        displayAllowed = allowed
        syncBridgeVisibility()
    }

    fun bind(controller: RaftWebViewController) {
        boundController = controller
        controller.bindCommandDispatcher(this) { command -> execute(command) }
    }

    fun unbind(controller: RaftWebViewController) {
        controller.unbindCommandDispatcher(this)
        if (boundController === controller) boundController = null
    }

    /**
     * The native view goes with the panel's `removeNotify`; this stops callbacks and off-EDT work.
     * The navigation listener stays registered (Wry reads the list on its own thread); it only
     * reads the immutable policy and `@Volatile disposed`.
     */
    fun dispose() {
        disposed = true
        pollInFlight = false
        bridge.dispose()
        boundController?.unbindCommandDispatcher(this)
        boundController = null
    }

    private fun onNavigation(url: String): Boolean {
        if (disposed) return false
        return when (policy.navigation(url)) {
            RaftDesktopWebViewNavigation.Allow -> true
            RaftDesktopWebViewNavigation.AllowUnverifiedFrame -> {
                topLevelCheck.markUnverified(nowMs())
                verifySoon = true
                true
            }
            RaftDesktopWebViewNavigation.Block -> {
                postToEdt { if (!disposed) notifyNavigationBlocked(url) }
                false
            }
        }
    }

    fun poll() {
        if (disposed || !bridge.isReady()) return
        val now = nowMs()
        if (topLevelCheck.timedOut(now)) {
            // A declared-frame navigation happened and the page does not answer: it may be a foreign
            // top-level page holding its main thread. Fail closed, without waiting for it.
            takeBack(blockedUrl = null)
            return
        }
        // Coalesce overlapping polls while the off-EDT worker is waiting on macOS Thread 0 (e.g.
        // during Chinese IME or live window resize) so pending polls never pile up in the queue.
        if (pollInFlight) return
        pollInFlight = true
        val started =
            bridge.poll { snapshot ->
                pollInFlight = false
                if (!disposed) onPollSnapshot(snapshot)
            }
        if (!started) {
            pollInFlight = false
        }
    }

    private fun onPollSnapshot(snapshot: RaftDesktopWryPollSnapshot) {
        for (raw in snapshot.ipcMessages) onChannelMessage(raw)
        if (disposed) return
        val now = nowMs()
        if (topLevelCheck.timedOut(now)) {
            takeBack(blockedUrl = null)
            return
        }
        val nowLoading = snapshot.isLoading
        canGoBack = snapshot.canGoBack
        canGoForward = snapshot.canGoForward
        topLevelCheck.beginRequest(now)?.let { token ->
            val idle = !nowLoading
            bridge.evaluateJavaScript(HrefScript) { result ->
                val href = RaftDesktopWebViewPolicy.decodeEvaluatedString(result)
                if (!disposed) onTopLevelUrl(token, idle, href)
            }
        }
        currentTitle = snapshot.title
        if (nowLoading && !loading) {
            loading = true
            progress = 10
            loadGeneration += 1
            commitSent = false
        } else if (!nowLoading && loading) {
            finishLoad(now)
        }
        revealWhenReady()
        notifyState(force = false)
    }

    private fun finishLoad(now: Long) {
        loading = false
        progress = 100
        if (loadFinishedAt == 0L) loadFinishedAt = now
        if (!commitSent) {
            commitSent = true
            events.onLoadCommit()
        }
        events.onLoadSuccess()
    }

    /** Shows the native view once the page is past its loading frame, or at the deadline. */
    private fun revealWhenReady() {
        if (pageReady || loadFinishedAt == 0L || readyCheckPending) return
        if (nowMs() - loadFinishedAt >= RaftDesktopWebViewRevealDeadlineMs) {
            markPageReady()
            return
        }
        readyCheckPending = true
        bridge.evaluateJavaScript(RaftDesktopWebViewReadyScript) { result ->
            val ready = RaftDesktopWebViewPolicy.decodeEvaluatedString(result) == "1"
            readyCheckPending = false
            if (ready && !disposed) markPageReady()
        }
    }

    private fun markPageReady() {
        if (pageReady) return
        pageReady = true
        onReadyChanged(true)
        syncBridgeVisibility()
    }

    private fun syncBridgeVisibility() {
        if (disposed) return
        val shouldShow = pageReady && displayAllowed
        if (shouldShow == bridgeShown) return
        bridgeShown = shouldShow
        if (shouldShow) {
            bridge.reveal()
        } else {
            bridge.hide()
        }
    }

    private fun onTopLevelUrl(token: Long, idle: Boolean, href: String?) {
        if (disposed) return
        // Before the first document the engine reports about:blank: nothing to fence yet.
        // No readable answer (script error) is trusted only while no declared-frame check is pending.
        val trusted =
            if (href == null) !topLevelCheck.armed else href == "about:blank" || policy.isTrustedTopLevel(href)
        val verdict = topLevelCheck.answer(token, trusted, idle, nowMs())
        if (verdict == RaftDesktopWebViewTopLevelCheck.Verdict.Stale) return
        if (verdict == RaftDesktopWebViewTopLevelCheck.Verdict.Untrusted) {
            takeBack(blockedUrl = href?.takeIf { it.isNotBlank() })
            return
        }
        if (href == null || href.isBlank() || href == "about:blank") return
        // Enforce top-level-only route checks on the AWT thread (never on sub-frames or Wry's thread).
        if (href != initialUrl && events.routesMainFrameNavigation?.invoke(href) == true) {
            takeBack(blockedUrl = href)
            return
        }
        lastBlockedUrl = ""
        // A trusted page the user reached (not the one restored to) re-arms the restore budget.
        if (href != lastTrustedUrl) restores = 0
        lastTrustedUrl = href
        val urlChanged = href != currentUrl
        if (urlChanged) {
            currentUrl = href
            events.onUrlChange(href)
            if (loading && !commitSent) {
                commitSent = true
                events.onLoadCommit()
            }
        }
        if (idle && !loading && !commitSent) {
            // Fast/cached initial load finished before any poll saw `isLoading == true`: complete
            // commit, success, and reveal so the native view never stays parked off-screen.
            finishLoad(nowMs())
            revealWhenReady()
            notifyState(force = false)
            return
        }
        if (urlChanged) {
            notifyState(force = false)
        }
    }

    /** The top-level document left the fence (or stopped answering): report it and load back. */
    private fun takeBack(blockedUrl: String?) {
        topLevelCheck.reset()
        if (blockedUrl != null && blockedUrl != lastBlockedUrl) {
            lastBlockedUrl = blockedUrl
            notifyNavigationBlocked(blockedUrl)
        }
        bridge.stopLoading()
        val back = lastTrustedUrl?.takeIf { restores < RaftDesktopWebViewMaxRestores } ?: "about:blank"
        restores += 1
        bridge.loadUrl(back)
    }

    private fun onChannelMessage(raw: String) {
        when (val message = policy.unwrapChannelMessage(raw) ?: return) {
            is RaftDesktopWebViewChannelMessage.OpenWindow -> notifyNavigationBlocked(message.url)
            // The secret already ties the message to the trusted top-level script; the address check
            // is the iOS one (the active document is the configured page / origin).
            is RaftDesktopWebViewChannelMessage.Host ->
                if (policy.canRunRuntimeScript(currentUrl)) events.dispatchHostMessage(message.raw)
        }
    }

    private fun notifyNavigationBlocked(url: String, isUserGesture: Boolean = false) {
        events.onNavigationBlocked(url)
        events.onNavigationBlockedWithGesture?.invoke(url, isUserGesture)
    }

    private fun execute(navigationCommand: String) {
        if (disposed) return
        val command = navigationCommand.trim()
        if (command.startsWith(RaftWebViewController.COMMAND_EVALUATE_JAVA_SCRIPT_PREFIX)) {
            val script = command.removePrefix(RaftWebViewController.COMMAND_EVALUATE_JAVA_SCRIPT_PREFIX)
            if (script.isEmpty() || script.length > RaftWebViewMaxRuntimeJavaScriptLength) return
            if (!policy.canRunRuntimeScript(currentUrl)) return
            bridge.evaluateJavaScript(policy.guardRuntimeScript(script)) {}
            return
        }
        if (command == RaftWebViewController.COMMAND_PROBE_DOCUMENT_CONTENT) {
            // The fixed, read-only probe ('1'/'0'), so not fenced like runtime scripts.
            bridge.evaluateJavaScript(RaftWebViewContentProbeScript) { result ->
                val answer = raftWebViewDecodeContentProbe(RaftDesktopWebViewPolicy.decodeEvaluatedString(result) ?: result)
                if (!disposed) events.onContentProbe(answer)
            }
            return
        }
        if (command.startsWith(RaftWebViewController.COMMAND_LOAD_URL_PREFIX)) {
            val target = command.removePrefix(RaftWebViewController.COMMAND_LOAD_URL_PREFIX).trim()
            if (target.isNotEmpty() && policy.navigation(target) == RaftDesktopWebViewNavigation.Allow) {
                bridge.loadUrl(target)
            }
            return
        }
        var forceNotify = true
        when (command) {
            RaftWebViewController.COMMAND_GO_BACK -> bridge.goBackIfPossible { poll() }
            RaftWebViewController.COMMAND_GO_FORWARD -> bridge.goForwardIfPossible { poll() }
            RaftWebViewController.COMMAND_RELOAD -> {
                restores = 0
                progress = 0
                bridge.reload { poll() }
            }
            RaftWebViewController.COMMAND_STOP -> {
                loading = false
                bridge.stopLoading { poll() }
                forceNotify = false
            }
            else -> return
        }
        notifyState(force = forceNotify)
    }

    private fun notifyState(force: Boolean) {
        navigationStateRelay.notify(
            force = force,
            isActive = { !disposed },
            readState = {
                RaftWebViewNavigationState(
                    url = currentUrl,
                    title = currentTitle,
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                    isLoading = loading,
                    progress = progress.coerceIn(0, 100),
                    loadGeneration = loadGeneration,
                )
            },
            deliver = { state -> events.onNavigationStateChange(state) },
        )
    }

    internal companion object {
        /** The committed top-level address, History API changes included. */
        const val HrefScript = "\"\" + window.location.href"
    }
}
