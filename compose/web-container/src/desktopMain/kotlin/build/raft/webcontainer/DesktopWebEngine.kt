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

/** Rich navigation request metadata reported by native desktop WebView engines (macOS WKWebView / Windows WebView2). */
data class DesktopNavigationRequest(
    val url: String,
    val isMainFrame: Boolean = true,
    val method: String = "GET",
    val requestCarriesScopedHeader: Boolean = false,
    val isUserGesture: Boolean = false,
    val starter: WebNavigationStarter = WebNavigationStarter.Other,
)

/** Navigation policy verdict returned by [DesktopWebSessionCallbacks.onNavigationDecision]. */
sealed interface DesktopNavigationDecision {
    data object Allow : DesktopNavigationDecision

    data object Cancel : DesktopNavigationDecision

    data class ReissueWithHeader(
        val url: String,
        val headerName: String,
        val headerValue: String,
    ) : DesktopNavigationDecision

    data class ReissueWithoutHeader(
        val url: String,
    ) : DesktopNavigationDecision
}

/** Callbacks invoked by a [DesktopWebSession] into [DesktopWebContainerHostController]. */
interface DesktopWebSessionCallbacks {
    /** Evaluates whether a navigation to [url] is allowed (main frame or subframe). */
    fun onNavigationRequested(
        url: String,
        isMainFrame: Boolean = true,
    ): Boolean

    /**
     * Full policy evaluation supporting scoped request headers and re-issue directives for direct
     * native engines (e.g. macOS `WKWebView`). Defaults to delegating to [onNavigationRequested].
     */
    fun onNavigationDecision(request: DesktopNavigationRequest): DesktopNavigationDecision =
        if (onNavigationRequested(request.url, request.isMainFrame)) {
            DesktopNavigationDecision.Allow
        } else {
            DesktopNavigationDecision.Cancel
        }

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

    /**
     * JSB delivery distinguishing the native frame's verified `securityOrigin` from the active
     * top-level document URL (fail closed on opaque/sandboxed frames or `allowNavigation=false`
     * pushState drift). Defaults to delegating to [onJsbMessage].
     */
    fun onVerifiedJsbMessage(
        rawPayload: String,
        frameOriginUrl: String,
        documentUrl: String,
        isMainFrame: Boolean,
        reply: (replyJson: String) -> Unit,
    ) {
        onJsbMessage(rawPayload, frameOriginUrl, isMainFrame, reply)
    }

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

    /** Stable engine identifier for container tracing/diagnostics (e.g. `macos_wkwebview`, `desktop_wry`). */
    val engineName: String
        get() = "desktop_web"

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

    /**
     * Evaluates [script] inside an isolated engine content world (e.g. macOS
     * `WKContentWorld.defaultClientWorld`) when [isolatedWorld] is `true`. Engines without
     * content-world separation fall back to [evaluateJavaScript].
     */
    fun evaluateJavaScript(
        script: String,
        isolatedWorld: Boolean,
        callback: ((String?) -> Unit)? = null,
    ) = evaluateJavaScript(script, callback)

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
            override fun onNavigationDecision(request: DesktopNavigationRequest): DesktopNavigationDecision {
                if (disposed) return DesktopNavigationDecision.Cancel
                if (!request.isMainFrame) {
                    return if (isAllowedSubframeUrl(request.url, config.navigationPolicy)) {
                        DesktopNavigationDecision.Allow
                    } else {
                        DesktopNavigationDecision.Cancel
                    }
                }
                if (request.starter == WebNavigationStarter.Link || request.starter == WebNavigationStarter.Form) {
                    val budget = config.navigationPolicy.requestHeaderRule?.maxSameOriginReissues ?: 0
                    chainState.startChain(
                        carriesHeader = request.requestCarriesScopedHeader,
                        reissueBudget = budget,
                        starter = request.starter,
                    )
                }
                val decision =
                    evaluateMainFrameNavigation(
                        targetUrl = request.url,
                        initialUrl = config.url,
                        policy = config.navigationPolicy,
                        method = request.method,
                        requestCarriesScopedHeader = request.requestCarriesScopedHeader,
                        chainState = chainState,
                        isUserGesture = request.isUserGesture,
                        catchesMainFrameNavigation = events.catchesMainFrameNavigation,
                        routesMainFrameNavigation = events.routesMainFrameNavigation,
                    )
                return when (decision) {
                    WebNavigationDecision.Allow -> DesktopNavigationDecision.Allow
                    is WebNavigationDecision.InterceptedByHost -> DesktopNavigationDecision.Cancel
                    is WebNavigationDecision.Block -> {
                        notifyBlocked(decision.url, decision.reason)
                        DesktopNavigationDecision.Cancel
                    }
                    is WebNavigationDecision.ReissueWithHeader ->
                        DesktopNavigationDecision.ReissueWithHeader(
                            url = decision.url,
                            headerName = decision.headerName,
                            headerValue = decision.headerValue,
                        )
                    is WebNavigationDecision.ReissueWithoutHeader ->
                        DesktopNavigationDecision.ReissueWithoutHeader(url = decision.url)
                }
            }

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
                    // Wry bridge has no per-redirect header-strip API: fail closed rather than
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
                val effectiveUrl = url.trim().ifEmpty { currentState.url }
                currentState = currentState.copy(url = effectiveUrl, isLoading = true, progress = 10)
                watchdog.onEvent(WebContainerWatchdogEvent.LoadStarted(loadGeneration), nowMs())
                if (effectiveUrl.isNotEmpty()) {
                    events.onUrlChange(effectiveUrl)
                }
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
                if (reason == MacOsNativeWebSession.NATIVE_CREATE_FAILED_REASON) {
                    runCatching {
                        activeTelemetrySink?.onEvent(
                            WebContainerTelemetryEvent.StartupFailed(
                                reason = reason,
                                engine = activeEngineName,
                            ),
                        )
                    }
                }
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
                runCatching {
                    activeTelemetrySink?.onEvent(
                        WebContainerTelemetryEvent.RenderProcessGone(
                            surfaceId = config.surfaceId,
                            didCrash = didCrash,
                            recovered = recovered,
                            attempt = attempt,
                            parked = false,
                            engine = activeEngineName,
                        ),
                    )
                }
                if (recovered) {
                    val restoreUrl =
                        sanitizeRendererRestoreUrl(
                            initialUrl = config.url,
                            candidateUrl = currentState.url,
                            policy = config.navigationPolicy,
                            isAllowedCandidate = { candidate ->
                                config.navigationPolicy.allowNavigation ||
                                    isSameDocumentIgnoringFragment(config.url.trim(), candidate)
                            },
                        )
                    if (restoreUrl.isNotBlank()) {
                        val budget = config.navigationPolicy.requestHeaderRule?.maxSameOriginReissues ?: 0
                        val headerRule = config.navigationPolicy.requestHeaderRule
                        val carriesHeader =
                            headerRule != null && parseWebContainerOrigin(restoreUrl) == headerRule.allowedOrigin
                        chainState.startChain(
                            carriesHeader = carriesHeader,
                            reissueBudget = budget,
                            starter = WebNavigationStarter.Launch,
                        )
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
                onVerifiedJsbMessage(
                    rawPayload = rawPayload,
                    frameOriginUrl = originUrl,
                    documentUrl = originUrl,
                    isMainFrame = isMainFrame,
                    reply = reply,
                )
            }

            override fun onVerifiedJsbMessage(
                rawPayload: String,
                frameOriginUrl: String,
                documentUrl: String,
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
                val frameOrigin = parseWebContainerOrigin(frameOriginUrl)
                val docOrigin = parseWebContainerOrigin(documentUrl)
                val fencedDocumentAllowed =
                    config.navigationPolicy.allowNavigation ||
                        (documentUrl.isNotBlank() && isSameDocumentIgnoringFragment(config.url.trim(), documentUrl))
                val verifiedOrigin =
                    when {
                        frameOrigin == null -> null
                        isMainFrame && (docOrigin == null || frameOrigin != docOrigin || !fencedDocumentAllowed) -> null
                        else -> frameOrigin
                    }
                dispatcher.dispatch(
                    rawPayload = rawPayload,
                    origin = verifiedOrigin,
                    documentUrl = documentUrl.ifBlank { currentState.url },
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

    private val activeEngineName: String
        get() = session?.engineName ?: "desktop_web"

    val component: Component?
        get() = session?.component

    fun start() {
        val activeSession = session
        if (activeSession == null) {
            runCatching {
                activeTelemetrySink?.onEvent(
                    WebContainerTelemetryEvent.StartupFailed(
                        reason = "desktop_engine_unavailable",
                        engine = "desktop_web",
                    ),
                )
            }
            events.onLoadFailure("desktop_engine_unavailable")
            return
        }
        val initial = config.url.trim()
        if (initial.isNotEmpty()) {
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
            val budget = config.navigationPolicy.requestHeaderRule?.maxSameOriginReissues ?: 0
            chainState.startChain(
                carriesHeader = decision is WebNavigationDecision.ReissueWithHeader,
                reissueBudget = budget,
                starter = WebNavigationStarter.Launch,
            )
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
                val activeUrl = currentState.url
                val activeOrigin = parseWebContainerOrigin(activeUrl) ?: return
                val locked = config.navigationPolicy.lockedMainFrameOrigin
                if (locked != null && activeOrigin != locked) return
                val initialOrigin = parseWebContainerOrigin(config.url)
                if (initialOrigin != null && activeOrigin != initialOrigin) return
                if (!config.navigationPolicy.allowNavigation && !isSameDocumentIgnoringFragment(config.url.trim(), activeUrl)) return
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
        activeSession.evaluateJavaScript(JsbBootstrapScripts.ContentVisibilityProbeScript, isolatedWorld = true) { raw ->
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
                runCatching {
                    activeTelemetrySink?.onEvent(
                        WebContainerTelemetryEvent.WatchdogTerminalError(
                            surfaceId = config.surfaceId,
                            reason = action.reason,
                            engine = activeEngineName,
                        ),
                    )
                }
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
