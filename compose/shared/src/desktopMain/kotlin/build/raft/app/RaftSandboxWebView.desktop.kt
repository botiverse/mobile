package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
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
 */
@Composable
internal actual fun RaftSandboxWebViewHost(
    config: RaftWebViewConfig,
    events: RaftWebViewEvents,
    controller: RaftWebViewController,
    modifier: Modifier,
) {
    if (!config.allowScripts) {
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
        ) {
            RaftDesktopWebViewPolicy(
                url = config.url,
                allowNavigation = config.allowNavigation,
                allowedNavigationSchemes = config.allowedNavigationSchemes,
                mainFrameOrigin = config.mainFrameOrigin,
                hostEventsEnabled = config.hostEventsEnabled,
                injectedJavaScript = config.injectedJavaScript,
            )
        }
    key(policy, config.ephemeral) {
        val host = remember { RaftDesktopWryWebView(policy, config.ephemeral, events) }
        host.events = events
        SwingPanel(background = Color.White, factory = { host.panel }, modifier = modifier)
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

/** Poll interval for address / loading / title / host messages (iOS polls at the same rate). */
private const val RaftDesktopWebViewPollMs = 250L

/** Quicker check after a navigation that may have been top-level ([RaftDesktopWebViewNavigation.AllowUnverifiedFrame]). */
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

    fun nextPollDelayMs(): Long =
        if (verifySoon) {
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
        panel.evaluateJavaScript(HrefScript) { result ->
            val href = RaftDesktopWebViewPolicy.decodeEvaluatedString(result)
            if (href != null) SwingUtilities.invokeLater { onTopLevelUrl(href) }
        }
        val nowLoading = panel.isLoading()
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

    private fun onTopLevelUrl(href: String) {
        if (disposed || href.isBlank()) return
        if (!policy.isTrustedTopLevel(href)) {
            // Before the first document the engine reports about:blank: nothing to fence yet.
            if (href == "about:blank") return
            if (href != lastBlockedUrl) {
                lastBlockedUrl = href
                events.onNavigationBlocked(href)
            }
            panel.stopLoading()
            val back = lastTrustedUrl?.takeIf { restores < RaftDesktopWebViewMaxRestores } ?: "about:blank"
            restores += 1
            panel.loadUrl(back)
            return
        }
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

    private fun onChannelMessage(raw: String) {
        when (val message = policy.unwrapChannelMessage(raw) ?: return) {
            is RaftDesktopWebViewChannelMessage.OpenWindow -> events.onNavigationBlocked(message.url)
            // The secret already ties the message to the guarded top-level script; the address check
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
