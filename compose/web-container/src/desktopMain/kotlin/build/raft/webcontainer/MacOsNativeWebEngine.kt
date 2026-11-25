package build.raft.webcontainer

import build.raft.webcontainer.jsb.JsbBootstrapScripts
import build.raft.webcontainer.jsb.JsbErrorCode
import build.raft.webcontainer.jsb.JsbResult
import build.raft.webcontainer.jsb.JsbWireCodec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Component
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JPanel
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

/** Parameters passed to [MacOsNativeWebBridgeOps.createSession]. */
internal data class MacOsNativeCreateParams(
    val sessionId: Long,
    val awtCanvas: Component,
    val initialUrl: String,
    val allowScripts: Boolean,
    val cacheEnabled: Boolean,
    val ephemeral: Boolean,
    val namedScopeUuid: String?,
    val jsbPortName: String?,
    val userScriptsJson: String,
    val allowsBackForwardGestures: Boolean,
    val headerName: String?,
    val headerValue: String?,
    val attachInitialHeader: Boolean,
    val initialX: Int,
    val initialY: Int,
    val initialW: Int,
    val initialH: Int,
    val initialVisible: Boolean,
)

/** Abstraction over [MacOsNativeWebBridge] enabling 100% deterministic headless JVM unit tests. */
internal interface MacOsNativeWebBridgeOps {
    val isAvailable: Boolean

    val isLoadedWithoutInitializing: Boolean
        get() = isAvailable

    fun registerSession(
        sessionId: Long,
        handler: MacOsNativeSessionHandler,
    )

    fun unregisterSession(sessionId: Long)

    fun createSession(params: MacOsNativeCreateParams): Boolean

    fun updateBounds(
        sessionId: Long,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        visible: Boolean,
    )

    fun loadUrl(
        sessionId: Long,
        url: String,
        headerName: String?,
        headerValue: String?,
    )

    fun goBack(sessionId: Long)

    fun goForward(sessionId: Long)

    fun reload(sessionId: Long)

    fun stopLoading(sessionId: Long)

    fun evaluateJavaScript(
        sessionId: Long,
        evalId: Long,
        script: String,
        isolatedWorld: Boolean,
    )

    fun completeNavigationDecision(
        sessionId: Long,
        decisionId: Long,
        actionCode: Int,
        reissueUrl: String?,
        headerName: String?,
        headerValue: String?,
    )

    fun completeJsbReply(
        sessionId: Long,
        replyId: Long,
        replyJson: String,
    )

    fun clearIsolationScope(namedScopeUuid: String?)

    fun disposeSession(sessionId: Long)
}

internal object RealMacOsNativeWebBridgeOps : MacOsNativeWebBridgeOps {
    override val isAvailable: Boolean
        get() = MacOsNativeWebBridge.isLoaded

    override val isLoadedWithoutInitializing: Boolean
        get() = MacOsNativeWebBridge.isLoadedIfInitialized

    override fun registerSession(
        sessionId: Long,
        handler: MacOsNativeSessionHandler,
    ) = MacOsNativeWebBridge.registerSession(sessionId, handler)

    override fun unregisterSession(sessionId: Long) = MacOsNativeWebBridge.unregisterSession(sessionId)

    override fun createSession(params: MacOsNativeCreateParams): Boolean =
        MacOsNativeWebBridge.nativeCreateSession(
            sessionId = params.sessionId,
            awtCanvas = params.awtCanvas,
            initialUrl = params.initialUrl,
            allowScripts = params.allowScripts,
            cacheEnabled = params.cacheEnabled,
            ephemeral = params.ephemeral,
            namedScopeUuid = params.namedScopeUuid,
            jsbPortName = params.jsbPortName,
            userScriptsJson = params.userScriptsJson,
            allowsBackForwardGestures = params.allowsBackForwardGestures,
            headerName = params.headerName,
            headerValue = params.headerValue,
            attachInitialHeader = params.attachInitialHeader,
            initialX = params.initialX,
            initialY = params.initialY,
            initialW = params.initialW,
            initialH = params.initialH,
            initialVisible = params.initialVisible,
        )

    override fun updateBounds(
        sessionId: Long,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        visible: Boolean,
    ) = MacOsNativeWebBridge.nativeUpdateBounds(sessionId, x, y, w, h, visible)

    override fun loadUrl(
        sessionId: Long,
        url: String,
        headerName: String?,
        headerValue: String?,
    ) = MacOsNativeWebBridge.nativeLoadUrl(sessionId, url, headerName, headerValue)

    override fun goBack(sessionId: Long) = MacOsNativeWebBridge.nativeGoBack(sessionId)

    override fun goForward(sessionId: Long) = MacOsNativeWebBridge.nativeGoForward(sessionId)

    override fun reload(sessionId: Long) = MacOsNativeWebBridge.nativeReload(sessionId)

    override fun stopLoading(sessionId: Long) = MacOsNativeWebBridge.nativeStopLoading(sessionId)

    override fun evaluateJavaScript(
        sessionId: Long,
        evalId: Long,
        script: String,
        isolatedWorld: Boolean,
    ) = MacOsNativeWebBridge.nativeEvaluateJavaScript(sessionId, evalId, script, isolatedWorld)

    override fun completeNavigationDecision(
        sessionId: Long,
        decisionId: Long,
        actionCode: Int,
        reissueUrl: String?,
        headerName: String?,
        headerValue: String?,
    ) = MacOsNativeWebBridge.nativeCompleteNavigationDecision(
        sessionId = sessionId,
        decisionId = decisionId,
        actionCode = actionCode,
        reissueUrl = reissueUrl,
        headerName = headerName,
        headerValue = headerValue,
    )

    override fun completeJsbReply(
        sessionId: Long,
        replyId: Long,
        replyJson: String,
    ) = MacOsNativeWebBridge.nativeCompleteJsbReply(sessionId, replyId, replyJson)

    override fun clearIsolationScope(namedScopeUuid: String?) =
        MacOsNativeWebBridge.nativeClearIsolationScope(namedScopeUuid)

    override fun disposeSession(sessionId: Long) = MacOsNativeWebBridge.nativeDisposeSession(sessionId)
}

/**
 * Direct lightweight native macOS `WKWebView` implementation of [DesktopWebEngine] (RFC Option A).
 *
 * Highlights:
 * - Zero Java reflection, zero Wry / Rust toolchain, zero polling timers (uses native KVO + async
 *   `WKNavigationDelegate` + `WKScriptMessageHandlerWithReply` + `WKContentWorld` isolation).
 * - Unlocks `allowScripts = false` natively via `WKWebpagePreferences.allowsContentJavaScript`.
 * - Maps [WebIsolationScope.Named] to deterministic UUID-backed `WKWebsiteDataStore(forIdentifier:)`
 *   on macOS 14+ (failing closed to `nonPersistentDataStore` on macOS < 14).
 * - Strictly deadlock-free: every native command dispatches asynchronously to macOS Thread 0 and
 *   every callback posts asynchronously back onto the AWT EDT.
 */
class MacOsNativeWebEngine internal constructor(
    private val bridgeOps: MacOsNativeWebBridgeOps,
    private val isMacOsHost: () -> Boolean,
    private val postToEdt: (() -> Unit) -> Unit,
    private val canvasFactory: () -> Component,
) : DesktopWebEngine {
    constructor() : this(
        bridgeOps = RealMacOsNativeWebBridgeOps,
        isMacOsHost = {
            System.getProperty("os.name").orEmpty().lowercase().let { it.contains("mac") || it.contains("darwin") }
        },
        postToEdt = { SwingUtilities.invokeLater(it) },
        canvasFactory = { Canvas() },
    )

    val isAvailable: Boolean
        get() = isMacOsHost() && bridgeOps.isAvailable

    /**
     * Returns `true` only if the native macOS bridge was ALREADY loaded in this process (never
     * triggers `dlopen` when `mobile_web_container_v1` is OFF).
     */
    val isAlreadyLoaded: Boolean
        get() = isMacOsHost() && bridgeOps.isLoadedWithoutInitializing

    override fun createSession(
        config: WebContainerConfig,
        callbacks: DesktopWebSessionCallbacks,
    ): DesktopWebSession? {
        if (!isAvailable) return null
        val sessionId = nextSessionId.incrementAndGet()
        return MacOsNativeWebSession(
            sessionId = sessionId,
            config = config,
            callbacks = callbacks,
            bridgeOps = bridgeOps,
            postToEdt = postToEdt,
            hostCanvas = canvasFactory(),
        )
    }

    /** Clears website data for [scope] using the native macOS `WKWebsiteDataStore` bridge. */
    fun clearIsolationScope(scope: WebIsolationScope): Boolean {
        if (!isAvailable) return false
        return when (scope) {
            WebIsolationScope.Default -> {
                bridgeOps.clearIsolationScope(namedScopeUuid = null)
                true
            }
            WebIsolationScope.Ephemeral -> true
            is WebIsolationScope.Named -> {
                bridgeOps.clearIsolationScope(namedScopeUuid = deriveDeterministicScopeUuid(scope.id))
                true
            }
        }
    }

    companion object {
        private val nextSessionId = AtomicLong(0L)
        val Default: MacOsNativeWebEngine = MacOsNativeWebEngine()
    }
}

internal class MacOsNativeWebSession(
    internal val sessionId: Long,
    private val config: WebContainerConfig,
    private val callbacks: DesktopWebSessionCallbacks,
    private val bridgeOps: MacOsNativeWebBridgeOps,
    private val postToEdt: (() -> Unit) -> Unit,
    private val hostCanvas: Component,
) : DesktopWebSession, MacOsNativeSessionHandler {
    private var started: Boolean = false
    private var nativeCreated: Boolean = false
    @Volatile private var disposed: Boolean = false
    private var visible: Boolean = false
    private var lastReportedBounds: IntArray? = null
    private var lastObservedUrl: String = ""

    private val nextEvalId = AtomicLong(0L)
    private val pendingEvals = HashMap<Long, (String?) -> Unit>()
    private val pendingCommands = ArrayDeque<() -> Unit>()

    private val hostPanel: JPanel =
        object : JPanel(BorderLayout()) {
            override fun addNotify() {
                super.addNotify()
                ensureNativeCreatedIfReady()
                syncBounds()
            }

            override fun removeNotify() {
                syncVisibilityOff()
                super.removeNotify()
            }
        }.apply {
            isOpaque = false
            add(hostCanvas, BorderLayout.CENTER)
            addComponentListener(
                object : ComponentAdapter() {
                    override fun componentMoved(e: ComponentEvent) = syncBounds()

                    override fun componentResized(e: ComponentEvent) {
                        ensureNativeCreatedIfReady()
                        syncBounds()
                    }

                    override fun componentShown(e: ComponentEvent) {
                        ensureNativeCreatedIfReady()
                        syncBounds()
                    }

                    override fun componentHidden(e: ComponentEvent) = syncVisibilityOff()
                },
            )
            addHierarchyBoundsListener(
                object : HierarchyBoundsAdapter() {
                    override fun ancestorMoved(e: HierarchyEvent) = syncBounds()

                    override fun ancestorResized(e: HierarchyEvent) = syncBounds()
                },
            )
            addHierarchyListener(
                HierarchyListener {
                    if (isShowing) {
                        ensureNativeCreatedIfReady()
                        syncBounds()
                    } else {
                        syncVisibilityOff()
                    }
                },
            )
        }

    override val component: Component
        get() = hostPanel

    override fun start() {
        if (disposed || started) return
        started = true
        bridgeOps.registerSession(sessionId, this)
        ensureNativeCreatedIfReady()
    }

    override fun setVisible(visible: Boolean) {
        if (disposed || this.visible == visible) return
        this.visible = visible
        syncBounds(force = true)
    }

    override fun loadUrl(url: String) {
        if (disposed) return
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        val headerRule = config.navigationPolicy.requestHeaderRule
        val attachHeader = headerRule != null && parseWebContainerOrigin(trimmed) == headerRule.allowedOrigin
        runWhenNativeReady {
            bridgeOps.loadUrl(
                sessionId = sessionId,
                url = trimmed,
                headerName = if (attachHeader) headerRule?.headerName else null,
                headerValue = if (attachHeader) headerRule?.headerValue else null,
            )
        }
    }

    override fun goBack() {
        if (disposed) return
        runWhenNativeReady { bridgeOps.goBack(sessionId) }
    }

    override fun goForward() {
        if (disposed) return
        runWhenNativeReady { bridgeOps.goForward(sessionId) }
    }

    override fun reload() {
        if (disposed) return
        runWhenNativeReady { bridgeOps.reload(sessionId) }
    }

    override fun stopLoading() {
        if (disposed) return
        runWhenNativeReady { bridgeOps.stopLoading(sessionId) }
    }

    override fun evaluateJavaScript(
        script: String,
        callback: ((String?) -> Unit)?,
    ) = evaluateJavaScript(script = script, isolatedWorld = false, callback = callback)

    override fun evaluateJavaScript(
        script: String,
        isolatedWorld: Boolean,
        callback: ((String?) -> Unit)?,
    ) {
        if (disposed || script.isBlank()) {
            callback?.invoke(null)
            return
        }
        val evalId = if (callback != null) nextEvalId.incrementAndGet() else 0L
        if (callback != null) {
            pendingEvals[evalId] = callback
        }
        runWhenNativeReady {
            bridgeOps.evaluateJavaScript(
                sessionId = sessionId,
                evalId = evalId,
                script = script,
                isolatedWorld = isolatedWorld,
            )
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        pendingCommands.clear()
        val callbacksToCancel = pendingEvals.values.toList()
        pendingEvals.clear()
        callbacksToCancel.forEach { runCatching { it(null) } }
        bridgeOps.unregisterSession(sessionId)
        if (nativeCreated) {
            bridgeOps.disposeSession(sessionId)
        }
    }

    /** Exposed internally for unit tests simulating native Canvas displayability without an AWT display. */
    internal fun triggerNativeAttachForTest(
        x: Int = 0,
        y: Int = 0,
        w: Int = 800,
        h: Int = 600,
    ): Boolean {
        if (disposed || !started || nativeCreated) return nativeCreated
        return performNativeCreate(x, y, w, h)
    }

    private fun ensureNativeCreatedIfReady() {
        if (disposed || !started || nativeCreated) return
        val jawtTarget =
            hostCanvas.takeIf { it.isDisplayable }
                ?: SwingUtilities.getWindowAncestor(hostPanel)?.takeIf { it.isDisplayable }
                ?: return
        val bounds = computeWindowRelativeBounds()
        performNativeCreate(bounds[0], bounds[1], bounds[2], bounds[3], jawtTarget)
    }

    private fun performNativeCreate(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        jawtTarget: Component = hostCanvas,
    ): Boolean {
        val initialTarget =
            config.url.trim().takeIf { candidate ->
                candidate.isNotEmpty() &&
                    evaluateMainFrameNavigation(
                        targetUrl = candidate,
                        initialUrl = config.url,
                        policy = config.navigationPolicy,
                    ) !is WebNavigationDecision.Block
            }.orEmpty()
        val headerRule = config.navigationPolicy.requestHeaderRule
        val attachInitialHeader =
            headerRule != null &&
                initialTarget.isNotEmpty() &&
                parseWebContainerOrigin(initialTarget) == headerRule.allowedOrigin
        val (ephemeral, namedUuid) =
            when (val scope = config.isolationScope) {
                WebIsolationScope.Default -> false to null
                WebIsolationScope.Ephemeral -> true to null
                is WebIsolationScope.Named -> false to deriveDeterministicScopeUuid(scope.id)
            }
        val jsbPort = if (config.allowScripts && config.jsbPolicy.isEffectivelyEnabled) config.jsbPolicy.portName else null
        val userScriptsJson = buildMacOsNativeUserScriptsJson(config)
        val initialVisible = computeEffectiveVisible()
        val created =
            bridgeOps.createSession(
                MacOsNativeCreateParams(
                    sessionId = sessionId,
                    awtCanvas = jawtTarget,
                    initialUrl = initialTarget,
                    allowScripts = config.allowScripts,
                    cacheEnabled = config.cacheEnabled,
                    ephemeral = ephemeral,
                    namedScopeUuid = namedUuid,
                    jsbPortName = jsbPort,
                    userScriptsJson = userScriptsJson,
                    allowsBackForwardGestures = config.allowsBackForwardNavigationGestures,
                    headerName = headerRule?.headerName,
                    headerValue = headerRule?.headerValue,
                    attachInitialHeader = attachInitialHeader,
                    initialX = x,
                    initialY = y,
                    initialW = w.coerceAtLeast(1),
                    initialH = h.coerceAtLeast(1),
                    initialVisible = initialVisible,
                ),
            )
        if (created) {
            nativeCreated = true
            val currentBounds = computeWindowRelativeBounds()
            lastReportedBounds =
                intArrayOf(
                    x,
                    y,
                    w.coerceAtLeast(1),
                    h.coerceAtLeast(1),
                    currentBounds[4],
                    currentBounds[5],
                    if (initialVisible) 1 else 0,
                )
            while (pendingCommands.isNotEmpty() && !disposed) {
                pendingCommands.removeFirst().invoke()
            }
        }
        return created
    }

    private fun runWhenNativeReady(action: () -> Unit) {
        if (disposed) return
        if (nativeCreated) {
            action()
        } else {
            pendingCommands.addLast(action)
        }
    }

    private fun computeEffectiveVisible(): Boolean {
        if (!visible || !hostPanel.isShowing) return false
        // WebContainerHost.desktop clips SwingPanel into a 1dp corner Box while covered/unready;
        // keep WKWebView hidden until Compose expands the parent container past the 1dp placeholder
        // so the first reveal never flashes at the bottom-right corner.
        val parent = hostPanel.parent
        if (parent != null && (parent.width <= 2 || parent.height <= 2)) return false
        return true
    }

    private fun computeWindowRelativeBounds(): IntArray {
        val w = hostPanel.width.coerceAtLeast(1)
        val h = hostPanel.height.coerceAtLeast(1)
        val window = SwingUtilities.getWindowAncestor(hostPanel) ?: return intArrayOf(0, 0, w, h, 0, 0)
        val rootContent = (window as? RootPaneContainer)?.contentPane ?: window
        val pt = SwingUtilities.convertPoint(hostPanel, Point(0, 0), rootContent)
        return intArrayOf(pt.x, pt.y, w, h, rootContent.width, rootContent.height)
    }

    private fun syncBounds(force: Boolean = false) {
        if (disposed || !nativeCreated) return
        val bounds = computeWindowRelativeBounds()
        val effectiveVisible = computeEffectiveVisible()
        // Include rootContent width/height in the dedup signature so vertical window resizes
        // always recompute AppKit's non-flipped Y origin even if hostPanel's top-left stayed fixed.
        val signature =
            intArrayOf(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5], if (effectiveVisible) 1 else 0)
        if (!force && lastReportedBounds != null && lastReportedBounds.contentEquals(signature)) return
        lastReportedBounds = signature
        bridgeOps.updateBounds(sessionId, bounds[0], bounds[1], bounds[2], bounds[3], effectiveVisible)
    }

    private fun syncVisibilityOff() {
        if (disposed || !nativeCreated) return
        val bounds =
            lastReportedBounds
                ?: intArrayOf(0, 0, hostPanel.width.coerceAtLeast(1), hostPanel.height.coerceAtLeast(1), 0, 0, 0)
        if (bounds[6] == 0) return
        val next = intArrayOf(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5], 0)
        lastReportedBounds = next
        bridgeOps.updateBounds(sessionId, next[0], next[1], next[2], next[3], visible = false)
    }

    // ---- MacOsNativeSessionHandler callbacks (executed on AWT EDT) -----------------------------

    override fun onNavigationDecisionRequest(
        decisionId: Long,
        url: String,
        isMainFrame: Boolean,
        method: String,
        carriesScopedHeader: Boolean,
        isUserGesture: Boolean,
        starterCode: Int,
    ) {
        if (disposed) {
            bridgeOps.completeNavigationDecision(sessionId, decisionId, ACTION_CANCEL, url, null, null)
            return
        }
        val starter =
            when (starterCode) {
                1 -> WebNavigationStarter.Link
                2 -> WebNavigationStarter.Form
                3 -> WebNavigationStarter.BackForward
                4 -> WebNavigationStarter.Reload
                else -> WebNavigationStarter.Other
            }
        val verdict =
            runCatching {
                callbacks.onNavigationDecision(
                    DesktopNavigationRequest(
                        url = url,
                        isMainFrame = isMainFrame,
                        method = method,
                        requestCarriesScopedHeader = carriesScopedHeader,
                        isUserGesture = isUserGesture,
                        starter = starter,
                    ),
                )
            }.getOrDefault(DesktopNavigationDecision.Cancel)
        when (verdict) {
            DesktopNavigationDecision.Allow ->
                bridgeOps.completeNavigationDecision(sessionId, decisionId, ACTION_ALLOW, null, null, null)
            DesktopNavigationDecision.Cancel ->
                bridgeOps.completeNavigationDecision(sessionId, decisionId, ACTION_CANCEL, url, null, null)
            is DesktopNavigationDecision.ReissueWithHeader ->
                bridgeOps.completeNavigationDecision(
                    sessionId,
                    decisionId,
                    ACTION_REISSUE_WITH_HEADER,
                    verdict.url,
                    verdict.headerName,
                    verdict.headerValue,
                )
            is DesktopNavigationDecision.ReissueWithoutHeader ->
                bridgeOps.completeNavigationDecision(
                    sessionId,
                    decisionId,
                    ACTION_REISSUE_WITHOUT_HEADER,
                    verdict.url,
                    null,
                    null,
                )
        }
    }

    override fun onStateChanged(
        url: String,
        title: String,
        canGoBack: Boolean,
        canGoForward: Boolean,
        isLoading: Boolean,
        progress: Int,
    ) {
        if (disposed) return
        val trimmedUrl = url.trim()
        if (trimmedUrl.isNotEmpty() && trimmedUrl != lastObservedUrl) {
            lastObservedUrl = trimmedUrl
            callbacks.onUrlChanged(trimmedUrl)
        }
        callbacks.onStateChanged(
            DesktopWebSessionState(
                url = trimmedUrl.ifEmpty { lastObservedUrl },
                title = title,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                isLoading = isLoading,
                progress = progress.coerceIn(0, 100),
            ),
        )
    }

    override fun onLoadStarted(url: String) {
        if (disposed) return
        val trimmed = url.trim()
        if (trimmed.isNotEmpty()) lastObservedUrl = trimmed
        callbacks.onLoadStarted(trimmed)
    }

    override fun onLoadCommit(url: String) {
        if (disposed) return
        val trimmed = url.trim()
        if (trimmed.isNotEmpty()) lastObservedUrl = trimmed
        callbacks.onLoadCommit(trimmed.ifEmpty { lastObservedUrl })
    }

    override fun onLoadFinished(url: String) {
        if (disposed) return
        val trimmed = url.trim()
        if (trimmed.isNotEmpty()) lastObservedUrl = trimmed
        callbacks.onLoadFinished(trimmed.ifEmpty { lastObservedUrl })
    }

    override fun onLoadFailure(reason: String) {
        if (!disposed) callbacks.onLoadFailure(reason)
    }

    override fun onProcessTerminated(didCrash: Boolean) {
        if (!disposed) callbacks.onProcessTerminated(didCrash)
    }

    override fun onJsbMessage(
        replyId: Long,
        rawPayload: String,
        frameOriginUrl: String,
        documentUrl: String,
        isMainFrame: Boolean,
    ) {
        if (disposed) {
            val quickId = JsbWireCodec.extractBestEffortCallId(rawPayload)
            bridgeOps.completeJsbReply(
                sessionId,
                replyId,
                JsbWireCodec.encodeResponse(
                    quickId,
                    JsbResult.Failure(JsbErrorCode.BRIDGE_DISABLED, "Container disposed"),
                ),
            )
            return
        }
        var replied = false
        val replyOnce: (String) -> Unit = { replyJson ->
            if (!replied) {
                replied = true
                postToEdt {
                    bridgeOps.completeJsbReply(sessionId, replyId, replyJson)
                }
            }
        }
        val dispatchResult =
            runCatching {
                callbacks.onVerifiedJsbMessage(
                    rawPayload = rawPayload,
                    frameOriginUrl = frameOriginUrl,
                    documentUrl = documentUrl,
                    isMainFrame = isMainFrame,
                    reply = replyOnce,
                )
            }
        if (dispatchResult.isFailure && !replied) {
            val quickId = JsbWireCodec.extractBestEffortCallId(rawPayload)
            replyOnce(
                JsbWireCodec.encodeResponse(
                    quickId,
                    JsbResult.Failure(JsbErrorCode.INTERNAL_ERROR, "Native JSB dispatch error"),
                ),
            )
        }
    }

    override fun onEvalResult(
        evalId: Long,
        result: String?,
    ) {
        val cb = pendingEvals.remove(evalId) ?: return
        if (!disposed) cb(result)
    }

    override fun onNewWindowRequested(url: String) {
        if (!disposed) callbacks.onNewWindowRequested(url)
    }

    companion object {
        const val ACTION_CANCEL: Int = 0
        const val ACTION_ALLOW: Int = 1
        const val ACTION_REISSUE_WITH_HEADER: Int = 2
        const val ACTION_REISSUE_WITHOUT_HEADER: Int = 3
    }
}

/**
 * Serializes all origin-fenced document-start user scripts for macOS `WKUserContentController`.
 *
 * Mirrors iOS `WebContainerHost.ios.kt`: fails closed when allowed origins are empty and guards
 * every script to its allowed origin set and frame boundary.
 */
internal fun buildMacOsNativeUserScriptsJson(config: WebContainerConfig): String {
    if (!config.allowScripts) return "[]"
    val lockedOrigin = config.navigationPolicy.lockedMainFrameOrigin
    val initialOrigin = parseWebContainerOrigin(config.url)
    val defaultOriginSet: Set<WebContainerOrigin> =
        when {
            lockedOrigin != null && initialOrigin != null && lockedOrigin != initialOrigin -> emptySet()
            lockedOrigin != null -> setOf(lockedOrigin)
            initialOrigin != null -> setOf(initialOrigin)
            else -> emptySet()
        }
    val scripts =
        buildList {
            if (config.jsbPolicy.isEffectivelyEnabled) {
                add(
                    JsbBootstrapScripts.buildPageBridgeScript(
                        portName = config.jsbPolicy.portName,
                        allowedOrigins = config.jsbPolicy.allowedOrigins,
                    ) to true,
                )
            }
            if (config.backgroundArgb != 0) {
                add(JsbBootstrapScripts.buildFirstPaintThemeScript(config.backgroundArgb, config.isDarkTheme) to true)
            }
            if (config.desktopViewportMode) {
                add(JsbBootstrapScripts.DesktopViewportMetaScript to true)
            }
            config.offlineBundle?.let { bundle ->
                if (defaultOriginSet.isNotEmpty()) {
                    val script = JsbBootstrapScripts.buildOfflineBundleScript(bundle, allowedOrigins = defaultOriginSet)
                    if (script.isNotBlank()) add(script to true)
                }
            }
            for (script in config.documentStartScripts) {
                val allowedOrigins = script.allowedOrigins.ifEmpty { defaultOriginSet }
                if (allowedOrigins.isNotEmpty() && script.source.isNotBlank()) {
                    val wrapped =
                        JsbBootstrapScripts.wrapDocumentStartScript(
                            source = script.source,
                            allowedOrigins = allowedOrigins,
                            mainFrameOnly = script.mainFrameOnly,
                        )
                    if (wrapped.isNotBlank()) add(wrapped to script.mainFrameOnly)
                }
            }
        }
    return JsonArray(
        scripts.map { (source, mainOnly) ->
            buildJsonObject {
                put("source", source)
                put("mainFrameOnly", mainOnly)
            }
        },
    ).toString()
}
