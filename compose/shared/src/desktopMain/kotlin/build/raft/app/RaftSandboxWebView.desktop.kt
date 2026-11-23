package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import build.raft.app.data.RaftDesktopPaths
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import dev.nucleusframework.webview.web.NativeWebView
import dev.nucleusframework.webview.web.linux.LinuxWebKitNativeWebView
import dev.nucleusframework.webview.web.macos.MacOsWebKitNativeWebView
import dev.nucleusframework.window.tao.NativeView
import dev.nucleusframework.window.tao.NucleusPlatformView
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Desktop sandbox WebView: the OS engine (macOS WKWebView, Linux WebKitGTK) through
 * ComposeNativeWebview (dev.nucleusframework:composewebview), embedded as a Nucleus Tao
 * [NativeView]. The native view sits under the Compose surface, which leaves a hole where this
 * composable draws: Compose drawn after it (a page's splash, popups, menus) and the loading cover
 * below draw over the page, and a page that is not drawn (under another page of the stack) is
 * hidden by the opaque Compose content over it. Security rules: [RaftDesktopWebViewPolicy].
 *
 * Every native call runs on the Tao main thread (the macOS main thread), which is the Compose UI
 * thread: no hop between the UI thread and AppKit, so no wait cycle between them either.
 *
 * The engine has no switch to turn JavaScript off here, so a surface that asks for no scripts keeps
 * "Open in browser" (fail closed). Any change to the policy inputs or the data store rebuilds the
 * WebView.
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
        RaftDesktopWebViewOpenInBrowser(config, modifier)
        return
    }
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
    key(policy, config.ephemeral) {
        val host = remember { RaftDesktopNativeWebView.createOrNull(policy, config.ephemeral, events) }
        if (host == null) {
            // No engine here (its native library did not load): the page opens in the browser.
            RaftDesktopWebViewOpenInBrowser(config, modifier)
            return@key
        }
        host.events = events
        // Registered before NativeView on purpose: Compose forgets in reverse order, so NativeView
        // first takes the native view out of the window and only then is it released here.
        DisposableEffect(Unit) { onDispose { host.dispose() } }
        DisposableEffect(controller) {
            host.bind(controller)
            onDispose { host.unbind(controller) }
        }
        LaunchedEffect(Unit) {
            host.start()
            while (true) {
                host.poll()
                delay(host.nextPollDelayMs())
            }
        }
        NativeView(factory = { host.platformView }, modifier = modifier) {
            // Until the page is past its loading frame (or the reveal deadline), the App's own
            // loading state covers it, in the canvas colour: never the engine's white or a web
            // skeleton inside a Settings section (#242).
            if (!host.revealed) RaftDesktopWebViewLoadingCover(config.backgroundArgb)
        }
    }
}

@Composable
private fun RaftDesktopWebViewOpenInBrowser(config: RaftWebViewConfig, modifier: Modifier) {
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
}

/** The loading state over a page not yet shown: the canvas colour (white when none) and a spinner. */
@Composable
private fun RaftDesktopWebViewLoadingCover(backgroundArgb: Int) {
    val background = if (backgroundArgb == 0) Color.White else Color(backgroundArgb)
    Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center) {
        RaftSpinner(size = RaftSpinnerSize.Md)
    }
}

private val RaftDesktopWebViewOnWindows: Boolean =
    System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")

/** Poll interval for address / loading / title / host messages (iOS polls at the same rate). */
private const val RaftDesktopWebViewPollMs = 250L

/** Poll interval while a declared-frame navigation was just allowed or the page is about to show. */
private const val RaftDesktopWebViewFastPollMs = 50L

/** How long after a declared-frame navigation the top-level address is read at the fast interval. */
private const val RaftDesktopWebViewFrameWatchMs = 1_000L

/**
 * Longest the WebView stays covered after its first load finished while the page still looks busy
 * ([RaftDesktopWebViewReadyScript]); then it shows anyway.
 */
private const val RaftDesktopWebViewRevealDeadlineMs = 2_500L

/**
 * Whether the page is past its loading frame: loaded, and no `aria-busy` region left (the web's
 * boot shell and its pending server frame are `aria-busy`; ShellFrame `pending`). Until then the
 * loading cover stays over the page, so the web's full-app skeleton never appears inside a
 * Settings section.
 */
private const val RaftDesktopWebViewReadyScript =
    "(function(){try{return document.readyState==='complete'&&!!document.body&&" +
        "!document.querySelector('[aria-busy=\"true\"]')?'1':'0'}catch(e){return '0'}})()"

/** Times the host takes a fenced-off top-level page back to the last trusted address before giving up. */
private const val RaftDesktopWebViewMaxRestores = 2

/**
 * One native WebView ([NativeWebView], ComposeNativeWebview's macOS / Linux engine). Everything runs
 * on the Tao main thread: the poll, the commands, and the engine's callbacks (navigation decisions,
 * script results), which WebKit delivers on the main thread.
 *
 * The engine reports state only when asked (no callback for the address, loading or title), so
 * [poll] reads it every [RaftDesktopWebViewPollMs] (the library's own WebView composable polls the
 * same way) and drains the page's messages. The top-level address is the engine's own
 * (`WKWebView.URL`, History API changes included), read without asking the page, so a page that
 * blocks its main thread cannot keep it from being checked.
 */
internal class RaftDesktopNativeWebView private constructor(
    private val policy: RaftDesktopWebViewPolicy,
    private val webView: NativeWebView,
    val platformView: NucleusPlatformView,
    var events: RaftWebViewEvents,
) {
    private val initialUrl = policy.initialUrl()

    /** Callbacks the engine makes from inside its own handlers run one step later, on the main thread. */
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Whether the page is shown (the loading cover is gone): Compose state, read by the host. */
    var revealed by mutableStateOf(false)
        private set
    private var loadFinishedAt = 0L
    private var readyCheckPending = false
    private var fastPollUntil = 0L

    private var disposed = false
    private var boundController: RaftWebViewController? = null
    private var currentUrl = initialUrl ?: ""
    private var lastTrustedUrl: String? = initialUrl
    private var lastBlockedUrl = ""
    private var restores = 0
    private var currentTitle = ""
    private var loading = false
    private var progress = 0

    /** Main-frame loads started ([RaftWebViewNavigationState.loadGeneration]). */
    private var loadGeneration = 0
    private var commitSent = false
    private var lastStateKey = ""

    private val navigationListener: (String) -> Boolean = ::onNavigation

    init {
        // The engine asks for every navigation, main frame and sub-frames alike (URL only); it
        // loads about:, data: and blob: addresses without asking.
        webView.addNavigateListener(navigationListener)
    }

    fun start() {
        if (initialUrl == null) {
            events.onNavigationBlocked(policy.url.trim())
            webView.loadUrl("about:blank")
        } else {
            webView.loadUrl(initialUrl)
        }
        notifyState(force = true)
    }

    /** Quick polls while a declared-frame navigation is fresh or the page is about to show. */
    fun nextPollDelayMs(): Long =
        if (System.currentTimeMillis() < fastPollUntil || (!revealed && loadFinishedAt != 0L)) {
            RaftDesktopWebViewFastPollMs
        } else {
            RaftDesktopWebViewPollMs
        }

    fun bind(controller: RaftWebViewController) {
        boundController = controller
        controller.bindCommandDispatcher(this) { command -> execute(command) }
    }

    fun unbind(controller: RaftWebViewController) {
        controller.unbindCommandDispatcher(this)
        if (boundController === controller) boundController = null
    }

    /** Stops callbacks and releases the engine's view (NativeView took it out of the window first). */
    fun dispose() {
        if (disposed) return
        disposed = true
        boundController?.unbindCommandDispatcher(this)
        boundController = null
        mainScope.cancel()
        webView.removeNavigateListener(navigationListener)
        webView.destroy()
    }

    private fun onNavigation(url: String): Boolean {
        if (disposed) return false
        return when (policy.navigation(url)) {
            RaftDesktopWebViewNavigation.Allow -> true
            RaftDesktopWebViewNavigation.AllowUnverifiedFrame -> {
                // Maybe a sub-frame, maybe the top-level document: the poll reads the top-level
                // address at the fast interval for a while and takes the page back if it left.
                fastPollUntil = System.currentTimeMillis() + RaftDesktopWebViewFrameWatchMs
                true
            }
            RaftDesktopWebViewNavigation.Block -> {
                mainScope.launch { if (!disposed) events.onNavigationBlocked(url) }
                false
            }
        }
    }

    fun poll() {
        if (disposed || !webView.isReady()) return
        for (raw in webView.drainIpcMessages()) onChannelMessage(raw)
        if (!checkTopLevelUrl()) return
        val nowLoading = webView.isLoading()
        currentTitle = webView.getTitle().orEmpty()
        if (nowLoading && !loading) {
            loading = true
            progress = 10
            loadGeneration += 1
            commitSent = false
        } else if (!nowLoading && loading) {
            loading = false
            progress = 100
            if (loadFinishedAt == 0L) loadFinishedAt = System.currentTimeMillis()
            if (!commitSent) {
                commitSent = true
                events.onLoadCommit()
            }
            events.onLoadSuccess()
        }
        revealWhenReady()
        notifyState(force = false)
    }

    /** Removes the loading cover once the page is past its loading frame, or at the deadline. */
    private fun revealWhenReady() {
        if (revealed || loadFinishedAt == 0L || readyCheckPending) return
        if (System.currentTimeMillis() - loadFinishedAt >= RaftDesktopWebViewRevealDeadlineMs) {
            revealed = true
            return
        }
        readyCheckPending = true
        webView.evaluateJavaScript(RaftDesktopWebViewReadyScript) { result ->
            readyCheckPending = false
            if (!disposed && RaftDesktopWebViewPolicy.decodeEvaluatedString(result) == "1") revealed = true
        }
    }

    /**
     * The top-level fence, after the fact: a top-level document off the fence (one a declared-frame
     * navigation let through, or an about:/data:/blob: one the engine loads without asking) is
     * reported and taken back. False when the page was taken back.
     */
    private fun checkTopLevelUrl(): Boolean {
        val href = webView.getCurrentUrl()?.trim().orEmpty()
        // Before the first document the engine reports nothing or about:blank: nothing to fence yet.
        if (href.isEmpty() || href == "about:blank") return true
        if (!policy.isTrustedTopLevel(href)) {
            takeBack(blockedUrl = href)
            return false
        }
        // Top-level-only route checks, never on sub-frames.
        if (href != initialUrl && events.routesMainFrameNavigation?.invoke(href) == true) {
            takeBack(blockedUrl = href)
            return false
        }
        lastBlockedUrl = ""
        // A trusted page the user reached (not the one restored to) re-arms the restore budget.
        if (href != lastTrustedUrl) restores = 0
        lastTrustedUrl = href
        if (href == currentUrl) return true
        currentUrl = href
        // No commit here: while a new document loads the engine already reports its address
        // (the provisional one), before the document exists. The commit is sent when the load ends.
        events.onUrlChange(href)
        notifyState(force = false)
        return true
    }

    /** The top-level document left the fence: report it and load back. */
    private fun takeBack(blockedUrl: String) {
        if (blockedUrl != lastBlockedUrl) {
            lastBlockedUrl = blockedUrl
            events.onNavigationBlocked(blockedUrl)
        }
        webView.stopLoading()
        val back = lastTrustedUrl?.takeIf { restores < RaftDesktopWebViewMaxRestores } ?: "about:blank"
        restores += 1
        webView.loadUrl(back)
    }

    private fun onChannelMessage(raw: String) {
        when (val message = policy.unwrapChannelMessage(raw) ?: return) {
            is RaftDesktopWebViewChannelMessage.OpenWindow -> events.onNavigationBlocked(message.url)
            // The secret already ties the message to the trusted top-level script; the address check
            // is the iOS one (the active document is the configured page / origin).
            is RaftDesktopWebViewChannelMessage.Host ->
                if (policy.canRunRuntimeScript(currentUrl)) events.dispatchHostMessage(message.raw)
        }
    }

    private fun execute(navigationCommand: String) {
        if (disposed) return
        val command = navigationCommand.trim()
        if (command.startsWith(RaftWebViewController.COMMAND_EVALUATE_JAVA_SCRIPT_PREFIX)) {
            val script = command.removePrefix(RaftWebViewController.COMMAND_EVALUATE_JAVA_SCRIPT_PREFIX)
            if (script.isEmpty() || script.length > RaftWebViewMaxRuntimeJavaScriptLength) return
            if (!policy.canRunRuntimeScript(currentUrl)) return
            webView.evaluateJavaScript(policy.guardRuntimeScript(script)) {}
            return
        }
        if (command == RaftWebViewController.COMMAND_PROBE_DOCUMENT_CONTENT) {
            // The fixed, read-only probe ('1'/'0'), so not fenced like runtime scripts.
            webView.evaluateJavaScript(RaftWebViewContentProbeScript) { result ->
                val answer = raftWebViewDecodeContentProbe(RaftDesktopWebViewPolicy.decodeEvaluatedString(result) ?: result)
                mainScope.launch { if (!disposed) events.onContentProbe(answer) }
            }
            return
        }
        when (command) {
            RaftWebViewController.COMMAND_GO_BACK -> if (webView.canGoBack()) webView.goBack()
            RaftWebViewController.COMMAND_GO_FORWARD -> if (webView.canGoForward()) webView.goForward()
            RaftWebViewController.COMMAND_RELOAD -> {
                restores = 0
                progress = 0
                webView.reload()
            }
            RaftWebViewController.COMMAND_STOP -> webView.stopLoading()
            else -> return
        }
        notifyState(force = true)
    }

    private fun notifyState(force: Boolean) {
        if (disposed) return
        val state =
            RaftWebViewNavigationState(
                url = currentUrl,
                title = currentTitle,
                canGoBack = webView.canGoBack(),
                canGoForward = webView.canGoForward(),
                isLoading = loading,
                progress = progress.coerceIn(0, 100),
                loadGeneration = loadGeneration,
            )
        val key = state.toString()
        if (!force && key == lastStateKey) return
        lastStateKey = key
        events.onNavigationStateChange(state)
    }

    companion object {
        private const val TAG = "RaftDesktopWebView"

        private val osName: String = System.getProperty("os.name").orEmpty().lowercase()

        /**
         * A new native WebView for [policy] (macOS WKWebView, Linux WebKitGTK), or null where the
         * engine is not available (another OS, or its native library did not load).
         *
         * `transparent`: the engine then adds no style of its own; opaque, it forces a white
         * `html, body` background into every frame (`!important`), which would override the
         * pages' own (dark mode) and an Artifact's. A page that paints no background still shows
         * the engine's default white, as in a browser. Ephemeral: a non-persistent data store, so
         * no token reaches ~/Library/WebKit; the data directory only matters on Linux.
         */
        fun createOrNull(
            policy: RaftDesktopWebViewPolicy,
            ephemeral: Boolean,
            events: RaftWebViewEvents,
        ): RaftDesktopNativeWebView? {
            val dataDirectory = if (ephemeral) null else File(RaftDesktopPaths.dataDir, "webview").apply { mkdirs() }.path
            val initScript = policy.documentStartScript()
            return runCatching {
                when {
                    osName.startsWith("mac") -> {
                        val view =
                            MacOsWebKitNativeWebView(
                                dataDirectory = dataDirectory,
                                initScript = initScript,
                                incognito = ephemeral,
                                javascriptEnabled = true,
                                transparent = true,
                                backgroundColor = Color.Transparent,
                            )
                        RaftDesktopNativeWebView(policy, view, view.asPlatformView(), events)
                    }
                    osName.startsWith("linux") -> {
                        val view =
                            LinuxWebKitNativeWebView(
                                dataDirectory = dataDirectory,
                                initScript = initScript,
                                incognito = ephemeral,
                                javascriptEnabled = true,
                                transparent = true,
                                backgroundColor = Color.Transparent,
                            )
                        RaftDesktopNativeWebView(policy, view, view.asPlatformView(), events)
                    }
                    else -> null
                }
            }.onFailure { raftLogWarn(TAG, "desktop_webview_unavailable error=${it::class.simpleName}") }
                .getOrNull()
        }
    }
}
