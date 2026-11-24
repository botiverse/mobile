package build.raft.webcontainer

import androidx.compose.runtime.staticCompositionLocalOf
import build.raft.webcontainer.jsb.JsbBootstrapScripts
import build.raft.webcontainer.jsb.JsbDispatcher
import build.raft.webcontainer.jsb.JsbErrorCode
import build.raft.webcontainer.jsb.JsbRegistry
import build.raft.webcontainer.jsb.JsbResult
import build.raft.webcontainer.jsb.JsbWireCodec
import build.raft.webcontainer.pool.WebContainerTelemetryEvent
import build.raft.webcontainer.pool.WebContainerTelemetrySink
import build.raft.webcontainer.resilience.WebContainerWatchdog
import build.raft.webcontainer.resilience.WebContainerWatchdogAction
import build.raft.webcontainer.resilience.WebContainerWatchdogEvent
import build.raft.webcontainer.resilience.WebNavigationStateRelay
import kotlinx.coroutines.CoroutineScope
import java.awt.Component

/** Snapshot reported by a [DesktopWebSession] implementation. */
data class DesktopWebSessionState(
    val url: String = "",
    val title: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isLoading: Boolean = false,
    val progress: Int = 0,
)

/** Callbacks invoked by a [DesktopWebSession] into [DesktopWebContainerHostController]. */
interface DesktopWebSessionCallbacks {
    /** Evaluates whether a navigation to [url] is allowed (main frame or subframe). */
    fun onNavigationRequested(
        url: String,
        isMainFrame: Boolean = true,
    ): Boolean

    fun onStateChanged(state: DesktopWebSessionState)

    fun onLoadStarted(url: String)

    fun onLoadCommit(url: String)

    fun onLoadFinished(url: String)

    fun onLoadFailure(reason: String)

    fun onProcessTerminated(didCrash: Boolean)

    fun onJsbMessage(
        rawPayload: String,
        originUrl: String,
        isMainFrame: Boolean,
        reply: (replyJson: String) -> Unit,
    )

    /** Reported when the page requests a new window (`window.open` / `target=_blank`). */
    fun onNewWindowRequested(url: String) = Unit

    /** Reported on the EDT when the native filter already rejected a navigation off-EDT. */
    fun onNavigationBlocked(
        url: String,
        reason: String,
    ) = Unit

    /** Reported when the committed top-level document URL changes (including SPA `pushState`). */
    fun onUrlChanged(url: String) = Unit
}

/**
 * Pluggable native Desktop WebView session SPI.
 *
 * Keeps `:web-container` decoupled from any specific desktop native binding (supporting both a
 * direct macOS `WKWebView` / Windows `WebView2` bridge and an adapter over an existing off-EDT
 * gateway without leaking native dependencies into `:web-container`).
 */
interface DesktopWebSession {
    val component: Component

    fun start()

    fun setVisible(visible: Boolean)

    fun loadUrl(url: String)

    fun goBack()

    fun goForward()

    fun reload()

    fun stopLoading()

    fun evaluateJavaScript(
        script: String,
        callback: ((String?) -> Unit)? = null,
    )

    fun dispose()
}

/** Factory SPI creating a [DesktopWebSession] for a given [WebContainerConfig]. */
fun interface DesktopWebEngine {
    fun createSession(
        config: WebContainerConfig,
        callbacks: DesktopWebSessionCallbacks,
    ): DesktopWebSession?
}

/**
 * CompositionLocal providing the active [DesktopWebEngine] on Compose Desktop.
 *
 * Defaults to `null` (fail closed cleanly until the host app provides an engine implementation).
 */
val LocalDesktopWebEngine = staticCompositionLocalOf<DesktopWebEngine?> { null }

/**
 * Testable non-Composable Desktop host coordinator wiring [DesktopWebSession], navigation guard,
 * [JsbDispatcher], [WebContainerWatchdog], and [WebContainerController].
 */
class DesktopWebContainerHostController(
    private val config: WebContainerConfig,
    engine: DesktopWebEngine,
    var events: WebContainerEvents,
    jsbRegistry: JsbRegistry = JsbRegistry.Empty,
    private val scope: CoroutineScope,
    telemetrySink: WebContainerTelemetrySink? = null,
    private val nowMs: () -> Long = ::webContainerMonotonicMillis,
    private val onReadyChanged: (Boolean) -> Unit = {},
) {
    private val chainState =
        WebNavigationChainState().apply {
            configureBudget(config.navigationPolicy.requestHeaderRule?.maxSameOriginReissues ?: 0)
        }
    private val navigationStateRelay = WebNavigationStateRelay()
    private val watchdog = WebContainerWatchdog()

    @Volatile private var activeRegistry: JsbRegistry = jsbRegistry
    @Volatile private var activeTelemetrySink: WebContainerTelemetrySink? = telemetrySink
    @Volatile private var jsbDispatcher: JsbDispatcher? = createDispatcher(jsbRegistry)

    @Volatile private var disposed: Boolean = false
    private var displayAllowed: Boolean = !config.coverActive
    private var pageReady: Boolean = false
    private var visibleApplied: Boolean = false
    private var boundController: WebContainerController? = null

    private var currentState = DesktopWebSessionState(url = config.url.trim())
    private var loadGeneration: Int = 0
    private var lastSuccessfulUrl: String = ""

    private fun createDispatcher(registry: JsbRegistry): JsbDispatcher? =
        if (config.allowScripts && config.jsbPolicy.isEffectivelyEnabled) {
            JsbDispatcher(
                policy = config.jsbPolicy,
                registry = registry,
                scope = scope,
                surfaceId = config.surfaceId,
                isolationScope = config.isolationScope,
                telemetrySinkProvider = { activeTelemetrySink },
                nowMs = nowMs,
            )
        } else {
            null
        }

    fun updateRuntimeHooks(
        nextRegistry: JsbRegistry,
        nextTelemetrySink: WebContainerTelemetrySink?,
    ) {
        activeTelemetrySink = nextTelemetrySink
        if (activeRegistry !== nextRegistry) {
            activeRegistry = nextRegistry
            jsbDispatcher?.cancelInFlight("jsb_registry_rebound")
            jsbDispatcher = createDispatcher(nextRegistry)
        }
    }

    private val callbacks =
        object : DesktopWebSessionCallbacks {
            override fun onNavigationRequested(
                url: String,
                isMainFrame: Boolean,
            ): Boolean {
                if (disposed) return false
                if (!isMainFrame) {
                    return isAllowedSubframeUrl(url, config.navigationPolicy)
                }
                val decision =
                    evaluateMainFrameNavigation(
                        targetUrl = url,
                        initialUrl = config.url,
                        policy = config.navigationPolicy,
                        method = "GET",
                        chainState = chainState,
                        catchesMainFrameNavigation = events.catchesMainFrameNavigation,
                        routesMainFrameNavigation = events.routesMainFrameNavigation,
                    )
                return when (decision) {
                    WebNavigationDecision.Allow -> true
                    is WebNavigationDecision.InterceptedByHost -> false
                    is WebNavigationDecision.Block -> {
                        notifyBlocked(decision.url, decision.reason)
                        false
                    }
                    is WebNavigationDecision.ReissueWithHeader -> true
                    // Desktop SPI has no per-redirect header-strip API yet: fail closed rather than
                    // leaking origin-scoped headers across origins.
                    is WebNavigationDecision.ReissueWithoutHeader -> {
                        notifyBlocked(decision.url, "desktop_header_strip_unsupported")
                        false
                    }
                }
            }

            override fun onStateChanged(state: DesktopWebSessionState) {
                if (disposed) return
                currentState = state
                notifyNavigationState(force = false)
            }

            override fun onLoadStarted(url: String) {
                if (disposed) return
                jsbDispatcher?.cancelInFlight("main_frame_navigation")
                loadGeneration += 1
                lastSuccessfulUrl = ""
                currentState = currentState.copy(url = url, isLoading = true, progress = 10)
                watchdog.onEvent(WebContainerWatchdogEvent.LoadStarted(loadGeneration), nowMs())
                events.onUrlChange(url)
                notifyNavigationState(force = false)
            }

            override fun onLoadCommit(url: String) {
                if (disposed) return
                currentState = currentState.copy(url = url)
                chainState.markCommitted()
                watchdog.onEvent(WebContainerWatchdogEvent.PageCommitted(loadGeneration), nowMs())
                events.onLoadCommit()
                markPageReady()
                notifyNavigationState(force = false)
            }

            override fun onLoadFinished(url: String) {
                if (disposed) return
                currentState = currentState.copy(url = url, isLoading = false, progress = 100)
                chainState.markCommitted()
                markPageReady()
                val action = watchdog.onEvent(WebContainerWatchdogEvent.PageFinished(loadGeneration), nowMs())
                handleWatchdogAction(action)
                if (lastSuccessfulUrl != url) {
                    lastSuccessfulUrl = url
                    events.onLoadSuccess()
                }
                events.onUrlChange(url)
                notifyNavigationState(force = false)
            }

            override fun onLoadFailure(reason: String) {
                if (disposed) return
                currentState = currentState.copy(isLoading = false)
                watchdog.onEvent(WebContainerWatchdogEvent.LoadFailed(loadGeneration, reason), nowMs())
                events.onLoadFailure(reason)
                notifyNavigationState(force = false)
            }

            override fun onProcessTerminated(didCrash: Boolean) {
                if (disposed) return
                jsbDispatcher?.cancelInFlight("desktop_process_terminated")
                events.onContentProcessGone(didCrash)
                val action = watchdog.onEvent(WebContainerWatchdogEvent.RenderProcessGone(didCrash), nowMs())
                val recovered = action is WebContainerWatchdogAction.TriggerRecoveryReload
                val attempt =
                    (action as? WebContainerWatchdogAction.TriggerRecoveryReload)?.recoveryAttempt
                        ?: watchdog.recentRecoveryCount
                activeTelemetrySink?.onEvent(
                    WebContainerTelemetryEvent.RenderProcessGone(
                        surfaceId = config.surfaceId,
                        didCrash = didCrash,
                        recovered = recovered,
                        attempt = attempt,
                    ),
                )
                if (recovered) {
                    val restoreUrl =
                        sanitizeRendererRestoreUrl(
                            initialUrl = config.url,
                            candidateUrl = currentState.url,
                            policy = config.navigationPolicy,
                        )
                    if (restoreUrl.isNotBlank()) {
                        session?.loadUrl(restoreUrl)
                    }
                } else {
                    handleWatchdogAction(action)
                }
            }

            override fun onJsbMessage(
                rawPayload: String,
                originUrl: String,
                isMainFrame: Boolean,
                reply: (replyJson: String) -> Unit,
            ) {
                if (disposed) return
                val dispatcher = jsbDispatcher
                if (dispatcher == null) {
                    val quickId = JsbWireCodec.extractBestEffortCallId(rawPayload)
                    reply(
                        JsbWireCodec.encodeResponse(
                            quickId,
                            JsbResult.Failure(JsbErrorCode.BRIDGE_DISABLED, "JSB disabled"),
                        ),
                    )
                    return
                }
                dispatcher.dispatch(
                    rawPayload = rawPayload,
                    origin = parseWebContainerOrigin(originUrl),
                    documentUrl = originUrl,
                    isMainFrame = isMainFrame,
                    onReply = reply,
                )
            }

            override fun onNewWindowRequested(url: String) {
                if (disposed) return
                val trimmed = url.trim()
                if (trimmed.isEmpty()) return
                if (!config.navigationPolicy.allowNewWindows) {
                    notifyBlocked(trimmed, "new_window_blocked")
                }
            }

            override fun onNavigationBlocked(
                url: String,
                reason: String,
            ) {
                if (disposed) return
                notifyBlocked(url, reason)
            }

            override fun onUrlChanged(url: String) {
                if (disposed) return
                val trimmed = url.trim()
                if (trimmed.isEmpty()) return
                currentState = currentState.copy(url = trimmed)
                events.onUrlChange(trimmed)
                notifyNavigationState(force = false)
            }
        }

    val session: DesktopWebSession? = engine.createSession(config, callbacks)

    val component: Component?
        get() = session?.component

    fun start() {
        val activeSession = session
        if (activeSession == null) {
            events.onLoadFailure("desktop_engine_unavailable")
            return
        }
        val initial = config.url.trim()
        if (initial.isNotEmpty()) {
            val budget = config.navigationPolicy.requestHeaderRule?.maxSameOriginReissues ?: 0
            chainState.startChain(carriesHeader = false, reissueBudget = budget, starter = WebNavigationStarter.Launch)
            val decision =
                evaluateMainFrameNavigation(
                    targetUrl = initial,
                    initialUrl = config.url,
                    policy = config.navigationPolicy,
                )
            if (decision is WebNavigationDecision.Block) {
                notifyBlocked(decision.url, decision.reason)
                return
            }
        }
        activeSession.start()
        syncVisibility()
        notifyNavigationState(force = true)
    }

    fun setDisplayAllowed(allowed: Boolean) {
        if (displayAllowed == allowed) return
        displayAllowed = allowed
        syncVisibility()
    }

    fun tickWatchdog() {
        if (disposed) return
        val action = watchdog.onEvent(WebContainerWatchdogEvent.Tick, nowMs())
        handleWatchdogAction(action)
    }

    fun bind(controller: WebContainerController) {
        if (boundController === controller) return
        boundController?.unbindDispatcher(this)
        boundController = controller
        controller.bindDispatcher(this) { command -> executeCommand(command) }
    }

    fun unbind(controller: WebContainerController) {
        controller.unbindDispatcher(this)
        if (boundController === controller) boundController = null
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        jsbDispatcher?.cancelInFlight("container_disposed")
        boundController?.unbindDispatcher(this)
        boundController = null
        session?.dispose()
    }

    private fun executeCommand(command: WebContainerCommand) {
        if (disposed) return
        val activeSession = session ?: return
        when (command) {
            WebContainerCommand.GoBack -> if (currentState.canGoBack) activeSession.goBack()
            WebContainerCommand.GoForward -> if (currentState.canGoForward) activeSession.goForward()
            WebContainerCommand.Reload -> activeSession.reload()
            WebContainerCommand.Stop -> activeSession.stopLoading()
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
                        chainState = chainState,
                        catchesMainFrameNavigation = events.catchesMainFrameNavigation,
                        routesMainFrameNavigation = events.routesMainFrameNavigation,
                    )
                when (decision) {
                    WebNavigationDecision.Allow,
                    is WebNavigationDecision.ReissueWithHeader,
                    -> activeSession.loadUrl(target)
                    is WebNavigationDecision.ReissueWithoutHeader ->
                        notifyBlocked(decision.url, "desktop_header_strip_unsupported")
                    is WebNavigationDecision.Block -> notifyBlocked(decision.url, decision.reason)
                    is WebNavigationDecision.InterceptedByHost -> Unit
                }
            }
            is WebContainerCommand.EvaluateJavaScript -> {
                if (!config.allowScripts || command.script.isBlank()) return
                if (command.script.length > config.maxRuntimeJavaScriptLength) return
                val activeOrigin = parseWebContainerOrigin(currentState.url) ?: return
                val locked = config.navigationPolicy.lockedMainFrameOrigin
                if (locked != null && activeOrigin != locked) return
                val initialOrigin = parseWebContainerOrigin(config.url)
                if (initialOrigin != null && activeOrigin != initialOrigin) return
                activeSession.evaluateJavaScript(command.script, null)
            }
            WebContainerCommand.ProbeContent -> probeContent(loadGeneration)
            is WebContainerCommand.EmitJsbEvent -> {
                val script = jsbDispatcher?.buildEventDispatchScript(command.name, command.payload) ?: return
                if (config.allowScripts) {
                    activeSession.evaluateJavaScript(script, null)
                }
            }
        }
    }

    private fun probeContent(expectedGen: Int) {
        val activeSession = session ?: return
        if (!config.allowScripts) {
            events.onContentProbe(null)
            handleWatchdogAction(watchdog.onEvent(WebContainerWatchdogEvent.DomProbeResult(expectedGen, null), nowMs()))
            return
        }
        activeSession.evaluateJavaScript(JsbBootstrapScripts.ContentVisibilityProbeScript) { raw ->
            if (disposed || expectedGen != loadGeneration) return@evaluateJavaScript
            val hasContent = JsbBootstrapScripts.decodeContentProbeResult(raw)
            events.onContentProbe(hasContent)
            handleWatchdogAction(
                watchdog.onEvent(WebContainerWatchdogEvent.DomProbeResult(expectedGen, hasContent), nowMs()),
            )
        }
    }

    private fun handleWatchdogAction(action: WebContainerWatchdogAction) {
        when (action) {
            WebContainerWatchdogAction.None -> Unit
            is WebContainerWatchdogAction.RequestDomProbe -> probeContent(action.generation)
            is WebContainerWatchdogAction.TriggerRecoveryReload -> Unit
            is WebContainerWatchdogAction.EmitTerminalError -> {
                activeTelemetrySink?.onEvent(
                    WebContainerTelemetryEvent.WatchdogTerminalError(
                        surfaceId = config.surfaceId,
                        reason = action.reason,
                    ),
                )
                events.onWatchdogTerminalError(action.reason)
            }
        }
    }

    private fun markPageReady() {
        if (pageReady) return
        pageReady = true
        onReadyChanged(true)
        syncVisibility()
    }

    private fun syncVisibility() {
        val shouldShow = pageReady && displayAllowed
        if (shouldShow == visibleApplied) return
        visibleApplied = shouldShow
        session?.setVisible(shouldShow)
    }

    private fun notifyBlocked(
        url: String,
        reason: String,
    ) {
        activeTelemetrySink?.onEvent(
            WebContainerTelemetryEvent.NavigationBlocked(
                surfaceId = config.surfaceId,
                sanitizedUrl = sanitizeUrlForTelemetry(url),
                reason = reason,
            ),
        )
        events.onNavigationBlocked(url, reason)
    }

    private fun notifyNavigationState(force: Boolean) {
        navigationStateRelay.notify(
            force = force,
            isActive = { !disposed },
            readState = {
                WebNavigationState(
                    url = currentState.url,
                    title = currentState.title,
                    canGoBack = currentState.canGoBack,
                    canGoForward = currentState.canGoForward,
                    isLoading = currentState.isLoading,
                    progress = currentState.progress.coerceIn(0, 100),
                    loadGeneration = loadGeneration,
                )
            },
            deliver = { state -> events.onNavigationStateChange(state) },
        )
    }
}
