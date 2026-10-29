package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import build.raft.app.data.RaftDesktopPaths
import io.github.kdroidfilter.webview.wry.Rgba
import io.github.kdroidfilter.webview.wry.WryWebViewPanel
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.coroutines.delay

/**
 * Desktop sandbox WebView: the OS engine (macOS WKWebView, Windows WebView2, Linux WebKitGTK)
 * through Wry ([WryWebViewPanel], io.github.kdroidfilter:wrywebview), embedded in the Compose window
 * as a native child view. Security rules: [RaftDesktopWebViewPolicy].
 *
 * Wry's engine has no switch to turn JavaScript off, so a surface that asks for no scripts keeps
 * the notice (fail closed). Any change to the policy inputs or the data store rebuilds the WebView.
 *
 * Windows keeps the notice too for now: WebView2 injects the document-start script into every
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
        RaftDesktopUnsupportedNotice("Web content is not available on desktop yet.", modifier)
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
        val host = remember { RaftDesktopWryWebView(policy, config.ephemeral, events) }
        host.events = events
        SwingPanel(factory = { host.panel.apply { background = java.awt.Color.WHITE } }, modifier = modifier)
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

/** Times the host takes a fenced-off top-level page back to the last trusted address before giving up. */
private const val RaftDesktopWebViewMaxRestores = 2

/**
 * One native WebView. Everything except [onNavigation] (Wry calls it on the engine's thread) runs
 * on the AWT event thread, which is Compose desktop's main thread.
 */
internal class RaftDesktopWryWebView(
    private val policy: RaftDesktopWebViewPolicy,
    ephemeral: Boolean,
    var events: RaftWebViewEvents,
) {
    private val initialUrl = policy.initialUrl()

    val panel =
        WryWebViewPanel(
            initialUrl = initialUrl ?: "about:blank",
            dataDirectory = if (ephemeral) null else File(RaftDesktopPaths.dataDir, "webview").apply { mkdirs() }.path,
            initScript = policy.documentStartScript(),
            supportZoom = false,
            backgroundColor = Rgba(255u, 255u, 255u, 255u),
            transparent = false,
            enableNavigationGestures = false,
            incognito = ephemeral,
            autoplayWithoutUserInteraction = false,
        )

    @Volatile private var disposed = false
    @Volatile private var verifySoon = false
    private val topLevelCheck = RaftDesktopWebViewTopLevelCheck()
    private var boundController: RaftWebViewController? = null
    private var currentUrl = initialUrl ?: ""
    private var lastTrustedUrl: String? = initialUrl
    private var lastBlockedUrl = ""
    private var restores = 0
    private var currentTitle = ""
    private var loading = false
    private var progress = 0
    private var commitSent = false
    private var lastStateKey = ""

    init {
        // Wry asks for every navigation, main frame and sub-frames alike (URL only).
        panel.addNavigateListener(::onNavigation)
    }

    fun start() {
        if (initialUrl == null) events.onNavigationBlocked(policy.url.trim())
        notifyState(force = true)
    }

    /** Quick polls while a declared-frame navigation is unverified, so its timeout is honoured. */
    fun nextPollDelayMs(): Long =
        if (verifySoon || topLevelCheck.armed) {
            verifySoon = false
            RaftDesktopWebViewVerifyMs
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

    /**
     * The native view goes with the panel ([WryWebViewPanel.removeNotify]); this stops callbacks.
     * The navigation listener stays registered (Wry reads the list on its own thread); it only
     * reads the immutable policy.
     */
    fun dispose() {
        disposed = true
        boundController?.unbindCommandDispatcher(this)
        boundController = null
    }

    private fun onNavigation(url: String): Boolean =
        when (policy.navigation(url)) {
            RaftDesktopWebViewNavigation.Allow -> true
            RaftDesktopWebViewNavigation.AllowUnverifiedFrame -> {
                topLevelCheck.markUnverified(System.currentTimeMillis())
                verifySoon = true
                true
            }
            RaftDesktopWebViewNavigation.Block -> {
                SwingUtilities.invokeLater { if (!disposed) events.onNavigationBlocked(url) }
                false
            }
        }

    fun poll() {
        if (disposed || !panel.isReady()) return
        for (raw in panel.drainIpcMessages()) onChannelMessage(raw)
        val now = System.currentTimeMillis()
        if (topLevelCheck.timedOut(now)) {
            // A declared-frame navigation happened and the page does not answer: it may be a foreign
            // top-level page holding its main thread. Fail closed, without waiting for it.
            takeBack(blockedUrl = null)
            return
        }
        val nowLoading = panel.isLoading()
        topLevelCheck.beginRequest(now)?.let { token ->
            val idle = !nowLoading
            panel.evaluateJavaScript(HrefScript) { result ->
                val href = RaftDesktopWebViewPolicy.decodeEvaluatedString(result)
                SwingUtilities.invokeLater { onTopLevelUrl(token, idle, href) }
            }
        }
        currentTitle = panel.getTitle().orEmpty()
        if (nowLoading && !loading) {
            loading = true
            progress = 10
            commitSent = false
        } else if (!nowLoading && loading) {
            loading = false
            progress = 100
            if (!commitSent) {
                commitSent = true
                events.onLoadCommit()
            }
            events.onLoadSuccess()
        }
        notifyState(force = false)
    }

    private fun onTopLevelUrl(token: Long, idle: Boolean, href: String?) {
        if (disposed) return
        // Before the first document the engine reports about:blank: nothing to fence yet.
        // No readable answer (script error) is trusted only while no declared-frame check is pending.
        val trusted =
            if (href == null) !topLevelCheck.armed else href == "about:blank" || policy.isTrustedTopLevel(href)
        val verdict = topLevelCheck.answer(token, trusted, idle, System.currentTimeMillis())
        if (verdict == RaftDesktopWebViewTopLevelCheck.Verdict.Stale) return
        if (verdict == RaftDesktopWebViewTopLevelCheck.Verdict.Untrusted) {
            takeBack(blockedUrl = href?.takeIf { it.isNotBlank() })
            return
        }
        if (href == null || href.isBlank() || href == "about:blank") return
        lastBlockedUrl = ""
        // A trusted page the user reached (not the one restored to) re-arms the restore budget.
        if (href != lastTrustedUrl) restores = 0
        lastTrustedUrl = href
        if (href == currentUrl) return
        currentUrl = href
        events.onUrlChange(href)
        if (loading && !commitSent) {
            commitSent = true
            events.onLoadCommit()
        }
        notifyState(force = false)
    }

    /** The top-level document left the fence (or stopped answering): report it and load back. */
    private fun takeBack(blockedUrl: String?) {
        topLevelCheck.reset()
        if (blockedUrl != null && blockedUrl != lastBlockedUrl) {
            lastBlockedUrl = blockedUrl
            events.onNavigationBlocked(blockedUrl)
        }
        panel.stopLoading()
        val back = lastTrustedUrl?.takeIf { restores < RaftDesktopWebViewMaxRestores } ?: "about:blank"
        restores += 1
        panel.loadUrl(back)
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
            panel.evaluateJavaScript(policy.guardRuntimeScript(script)) {}
            return
        }
        when (command) {
            RaftWebViewController.COMMAND_GO_BACK -> if (panel.canGoBack()) panel.goBack()
            RaftWebViewController.COMMAND_GO_FORWARD -> if (panel.canGoForward()) panel.goForward()
            RaftWebViewController.COMMAND_RELOAD -> {
                restores = 0
                progress = 0
                panel.reload()
            }
            RaftWebViewController.COMMAND_STOP -> panel.stopLoading()
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
                canGoBack = panel.canGoBack(),
                canGoForward = panel.canGoForward(),
                isLoading = loading,
                progress = progress.coerceIn(0, 100),
            )
        val key = state.toString()
        if (!force && key == lastStateKey) return
        lastStateKey = key
        events.onNavigationStateChange(state)
    }

    private companion object {
        /** The committed top-level address, History API changes included. */
        const val HrefScript = "\"\" + window.location.href"
    }
}
