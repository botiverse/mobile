package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import build.raft.app.data.RaftDesktopPaths
import build.raft.webcontainer.DesktopWebEngine
import build.raft.webcontainer.DesktopWebSession
import build.raft.webcontainer.DesktopWebSessionCallbacks
import build.raft.webcontainer.DesktopWebSessionState
import build.raft.webcontainer.LocalDesktopWebEngine
import build.raft.webcontainer.MacOsNativeWebEngine
import build.raft.webcontainer.WebContainerConfig
import build.raft.webcontainer.WebContainerOrigin
import build.raft.webcontainer.WebIsolationScope
import build.raft.webcontainer.WebNavigationDecision
import build.raft.webcontainer.evaluateMainFrameNavigation
import build.raft.webcontainer.isAllowedSubframeUrl
import build.raft.webcontainer.jsb.JsbBootstrapScripts
import build.raft.webcontainer.parseWebContainerOrigin
import build.raft.webcontainer.resource.WebResourceInterceptor
import java.awt.Component
import java.io.File
import java.security.SecureRandom
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val RaftDesktopWebContainerOnWindows: Boolean =
    System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")

private val RaftDesktopWebContainerOnMacOs: Boolean =
    System.getProperty("os.name").orEmpty().lowercase().let { it.contains("mac") || it.contains("darwin") }

@Composable
internal actual fun rememberRaftWebContainerPlatformResourceInterceptor(): WebResourceInterceptor? = null

@Composable
internal actual fun RaftWebContainerPlatformScope(
    config: RaftWebViewConfig,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    // Direct native macOS WKWebView supports allowScripts = false natively via WKWebpagePreferences;
    // fallback Wry cannot disable JS, and Windows WebView2 ships in a follow-up PR.
    val macNativeAvailable = RaftDesktopWebContainerOnMacOs && MacOsNativeWebEngine.Default.isAvailable
    if (RaftDesktopWebContainerOnWindows || (!config.allowScripts && !macNativeAvailable)) {
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
    if (LocalDesktopWebEngine.current != null) {
        content()
    } else {
        CompositionLocalProvider(LocalDesktopWebEngine provides RaftDesktopWebContainerEngine.Default) {
            content()
        }
    }
}

/**
 * Resolves the Desktop WebView storage parameters `(ephemeral, dataDirectory)` for an isolation
 * scope.
 *
 * On macOS, Wry wraps `WKWebView` and ignores `dataDirectory` (any `incognito = false` view shares
 * `WKWebsiteDataStore.defaultDataStore()`), so [WebIsolationScope.Named] fails closed to
 * `ephemeral = true` (`nonPersistentDataStore()`) until Phase 3's direct macOS `WKWebView` bridge
 * uses `WKWebsiteDataStore(forIdentifier:)`. On Linux WebKitGTK, each named scope receives a
 * dedicated subdirectory under `webview-scopes/`.
 */
fun resolveDesktopWebContainerStorage(
    scope: WebIsolationScope,
    baseDataDir: File = RaftDesktopPaths.dataDir,
    isMacOs: Boolean = RaftDesktopWebContainerOnMacOs,
): Pair<Boolean, String?> =
    when (scope) {
        WebIsolationScope.Default ->
            false to File(baseDataDir, "webview").apply { mkdirs() }.path
        WebIsolationScope.Ephemeral ->
            true to null
        is WebIsolationScope.Named ->
            if (isMacOs) {
                true to null
            } else {
                false to File(File(baseDataDir, "webview-scopes"), scope.id).apply { mkdirs() }.path
            }
    }

/**
 * Factory hook creating an off-EDT [RaftDesktopWryBridge] for `:web-container` sessions.
 * Production delegates to [RaftDesktopWryGateway.createForWebContainer]; unit tests supply fakes.
 */
fun interface RaftDesktopWebContainerBridgeFactory {
    fun create(
        initialUrl: String,
        initScript: String,
        ephemeral: Boolean,
        dataDirectory: String?,
    ): RaftDesktopWryBridge
}

/**
 * Desktop [DesktopWebEngine] routing `:web-container` sessions:
 * - On macOS when `libraft_webcontainer_macos.dylib` is loaded, delegates directly to
 *   [MacOsNativeWebEngine] (zero Wry/Rust/reflection/polling, full `allowScripts = false` and
 *   `WKWebsiteDataStore(forIdentifier:)` support, non-blocking `dispatch_async` to Thread 0).
 * - On Linux (or macOS if the native `.dylib` is unavailable), transparently falls back to Raft's
 *   off-EDT [RaftDesktopWryGateway] while keeping `:web-container` completely free of Wry
 *   dependencies.
 */
class RaftDesktopWebContainerEngine(
    private val bridgeFactory: RaftDesktopWebContainerBridgeFactory =
        RaftDesktopWebContainerBridgeFactory { initialUrl, initScript, ephemeral, dataDirectory ->
            RaftDesktopWryGateway.createForWebContainer(
                initialUrl = initialUrl,
                initScript = initScript,
                ephemeral = ephemeral,
                dataDirectory = dataDirectory,
            )
        },
    private val primaryMacOsEngine: DesktopWebEngine = MacOsNativeWebEngine.Default,
    private val isPrimaryMacOsEngineAvailable: () -> Boolean = { MacOsNativeWebEngine.Default.isAvailable },
    private val isWindowsHost: () -> Boolean = { RaftDesktopWebContainerOnWindows },
    private val isMacOsHost: () -> Boolean = { RaftDesktopWebContainerOnMacOs },
    private val baseDataDir: () -> File = { RaftDesktopPaths.dataDir },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val postToEdt: (() -> Unit) -> Unit = { SwingUtilities.invokeLater(it) },
    private val autoStartPollTimer: Boolean = true,
) : DesktopWebEngine {
    override fun createSession(
        config: WebContainerConfig,
        callbacks: DesktopWebSessionCallbacks,
    ): DesktopWebSession? {
        if (isWindowsHost()) return null
        if (isMacOsHost() && isPrimaryMacOsEngineAvailable()) {
            primaryMacOsEngine.createSession(config, callbacks)?.let { session ->
                if (config.isolationScope == WebIsolationScope.Default) {
                    markDesktopMacOsNativeDefaultScopeUsed(baseDataDir())
                }
                return session
            }
        }
        if (!config.allowScripts) return null
        val channelSecret = generateDesktopChannelSecret()
        val initialTarget =
            config.url.trim().takeIf { candidate ->
                candidate.isNotEmpty() &&
                    evaluateMainFrameNavigation(
                        targetUrl = candidate,
                        initialUrl = config.url,
                        policy = config.navigationPolicy,
                    ) !is WebNavigationDecision.Block
            } ?: "about:blank"
        val initScript = buildDesktopContainerInitScript(config = config, channelSecret = channelSecret)
        val (ephemeral, dataDirectory) =
            resolveDesktopWebContainerStorage(
                scope = config.isolationScope,
                baseDataDir = baseDataDir(),
                isMacOs = isMacOsHost(),
            )
        val bridge =
            bridgeFactory.create(
                initialUrl = initialTarget,
                initScript = initScript,
                ephemeral = ephemeral,
                dataDirectory = dataDirectory,
            )
        return RaftDesktopWryWebSession(
            config = config,
            initialUrl = initialTarget,
            channelSecret = channelSecret,
            bridge = bridge,
            callbacks = callbacks,
            nowMs = nowMs,
            postToEdt = postToEdt,
            autoStartPollTimer = autoStartPollTimer,
        )
    }

    companion object {
        val Default: RaftDesktopWebContainerEngine = RaftDesktopWebContainerEngine()
    }
}

/**
 * One [DesktopWebSession] backed by [RaftDesktopWryBridge].
 *
 * Threading invariant:
 * - [onNativeNavigate] executes on Wry's native thread (macOS Thread 0): it is strictly lock-free
 *   and pure (reads only immutable [config], `@Volatile disposed`, and `AtomicLong` in
 *   [topLevelCheck]), posting any UI/blocked notification to the AWT EDT via [postToEdt] and
 *   never touching Swing timers or host controller state off-EDT.
 * - All remaining methods and callbacks run on the AWT EDT while blocking native FFI calls run
 *   strictly off-EDT inside [bridge].
 */
class RaftDesktopWryWebSession(
    private val config: WebContainerConfig,
    private val initialUrl: String,
    private val channelSecret: String,
    private val bridge: RaftDesktopWryBridge,
    private val callbacks: DesktopWebSessionCallbacks,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val postToEdt: (() -> Unit) -> Unit = { SwingUtilities.invokeLater(it) },
    private val autoStartPollTimer: Boolean = true,
) : DesktopWebSession {
    override val component: Component
        get() = bridge.component

    @Volatile private var disposed: Boolean = false
    @Volatile private var verifySoon: Boolean = false
    private var started: Boolean = false
    private var visible: Boolean = false
    private var pollInFlight: Boolean = false
    private var loading: Boolean = false
    private var pendingFinishAfterHref: Boolean = false
    private var commitSent: Boolean = false
    private var currentUrl: String = initialUrl.takeUnless { it == "about:blank" }.orEmpty()
    private var lastTrustedUrl: String? = initialUrl.takeUnless { it == "about:blank" }
    private var lastBlockedUrl: String = ""
    private var restores: Int = 0
    private val topLevelCheck = RaftDesktopWebViewTopLevelCheck()

    private val pollTimer: Timer? =
        if (autoStartPollTimer) {
            Timer(POLL_INTERVAL_MS) { pollOnce() }.apply {
                isRepeats = true
            }
        } else {
            null
        }

    init {
        bridge.addNavigateListener(::onNativeNavigate)
    }

    override fun start() {
        if (disposed || started) return
        started = true
        pollTimer?.start()
        pollOnce()
    }

    override fun setVisible(visible: Boolean) {
        if (disposed || this.visible == visible) return
        this.visible = visible
        if (visible) {
            bridge.reveal()
        } else {
            bridge.hide()
        }
    }

    override fun loadUrl(url: String) {
        if (disposed) return
        bridge.loadUrl(url)
    }

    override fun goBack() {
        if (disposed) return
        bridge.goBackIfPossible { pollOnce() }
    }

    override fun goForward() {
        if (disposed) return
        bridge.goForwardIfPossible { pollOnce() }
    }

    override fun reload() {
        if (disposed) return
        restores = 0
        pendingFinishAfterHref = false
        bridge.reload { pollOnce() }
    }

    override fun stopLoading() {
        if (disposed) return
        loading = false
        pendingFinishAfterHref = false
        bridge.stopLoading { pollOnce() }
    }

    override fun evaluateJavaScript(
        script: String,
        callback: ((String?) -> Unit)?,
    ) {
        if (disposed) return
        bridge.evaluateJavaScript(script) { raw ->
            if (!disposed) callback?.invoke(raw)
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        pollInFlight = false
        pollTimer?.stop()
        bridge.dispose()
    }

    /** Executes one off-EDT poll cycle (exposed for deterministic JVM unit tests). */
    fun pollOnce() {
        if (disposed || !bridge.isReady()) return
        val now = nowMs()
        if (topLevelCheck.timedOut(now)) {
            restoreAfterUntrustedTopLevel(untrustedUrl = null, notifyBlocked = false)
            return
        }
        if (pollInFlight) return
        pollInFlight = true
        val scheduled =
            bridge.poll { snapshot ->
                pollInFlight = false
                if (!disposed) handlePollSnapshot(snapshot)
            }
        if (!scheduled) {
            pollInFlight = false
        }
    }

    /**
     * Native Wry navigation callback (runs on Wry's native thread / macOS Thread 0).
     *
     * Must remain strictly lock-free and pure: reads only immutable [config], `@Volatile disposed`,
     * and `AtomicLong` inside [topLevelCheck]. Dynamic host hooks (`catchesMainFrameNavigation` /
     * `routesMainFrameNavigation`) are enforced on the AWT EDT inside [handleTopLevelHref].
     */
    private fun onNativeNavigate(url: String): Boolean {
        if (disposed) return false
        val trimmed = url.trim()
        // 1. Evaluate pure static main-frame policy first (no mutable chainState / no UI hooks).
        val staticDecision =
            evaluateMainFrameNavigation(
                targetUrl = trimmed,
                initialUrl = config.url,
                policy = config.navigationPolicy,
            )
        when (staticDecision) {
            WebNavigationDecision.Allow,
            is WebNavigationDecision.ReissueWithHeader,
            -> return true
            is WebNavigationDecision.ReissueWithoutHeader -> {
                postToEdt {
                    if (!disposed) callbacks.onNavigationBlocked(trimmed, "desktop_header_strip_unsupported")
                }
                return false
            }
            is WebNavigationDecision.InterceptedByHost -> return false
            is WebNavigationDecision.Block -> {
                // 2. Only declared sub-frames (local about:/blob:/data:, or explicit non-empty
                //    https allowedSubframeHosts) may pass without clearing the main-frame fence,
                //    and they immediately arm the post-navigation top-level href verification.
                if (isDeclaredUnverifiedSubframe(trimmed)) {
                    topLevelCheck.markUnverified(nowMs())
                    verifySoon = true
                    return true
                }
                val reason = staticDecision.reason
                postToEdt {
                    if (!disposed) callbacks.onNavigationBlocked(trimmed, reason)
                }
                return false
            }
        }
    }

    private fun isDeclaredUnverifiedSubframe(url: String): Boolean {
        if (url.isEmpty()) return false
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme in LocalSubframeSchemes) return true
        if (scheme != "https") return false
        // Empty allowedSubframeHosts means NO unverified remote subframes bypass the main-frame fence.
        if (config.navigationPolicy.normalizedSubframeHosts.isEmpty()) return false
        return isAllowedSubframeUrl(url, config.navigationPolicy)
    }

    private fun handlePollSnapshot(snapshot: RaftDesktopWryPollSnapshot) {
        for (raw in snapshot.ipcMessages) {
            handleIpcMessage(raw)
        }
        if (disposed) return
        val now = nowMs()
        if (topLevelCheck.timedOut(now)) {
            restoreAfterUntrustedTopLevel(untrustedUrl = null, notifyBlocked = false)
            return
        }
        val nowLoading = snapshot.isLoading
        if (nowLoading && !loading) {
            loading = true
            pendingFinishAfterHref = false
            commitSent = false
            callbacks.onLoadStarted(currentUrl.ifBlank { initialUrl })
        } else if (!nowLoading && loading) {
            // Defer finishLoad/reveal until HREF_SCRIPT confirms the final top-level URL is trusted.
            loading = false
            pendingFinishAfterHref = true
        }
        topLevelCheck.beginRequest(now)?.let { token ->
            val idle = !nowLoading
            bridge.evaluateJavaScript(HREF_SCRIPT) { result ->
                val href = RaftDesktopWebViewPolicy.decodeEvaluatedString(result)
                if (!disposed) handleTopLevelHref(token, idle, href, snapshot)
            }
        }
        callbacks.onStateChanged(
            DesktopWebSessionState(
                url = currentUrl,
                title = snapshot.title,
                canGoBack = snapshot.canGoBack,
                canGoForward = snapshot.canGoForward,
                isLoading = loading || pendingFinishAfterHref,
                progress = if (loading || pendingFinishAfterHref) 10 else if (commitSent) 100 else 0,
            ),
        )
        val fastPoll = verifySoon || topLevelCheck.armed || pendingFinishAfterHref
        if (verifySoon) verifySoon = false
        updateTimerDelay(if (fastPoll) VERIFY_INTERVAL_MS else POLL_INTERVAL_MS)
    }

    private fun handleTopLevelHref(
        token: Long,
        idle: Boolean,
        href: String?,
        snapshot: RaftDesktopWryPollSnapshot,
    ) {
        val trusted =
            if (href == null) {
                !topLevelCheck.armed
            } else {
                href == "about:blank" || isTrustedTopLevelUrl(href)
            }
        when (topLevelCheck.answer(token, trusted, idle, nowMs())) {
            RaftDesktopWebViewTopLevelCheck.Verdict.Stale -> return
            RaftDesktopWebViewTopLevelCheck.Verdict.Untrusted -> {
                pendingFinishAfterHref = false
                restoreAfterUntrustedTopLevel(untrustedUrl = href, notifyBlocked = true)
                return
            }
            RaftDesktopWebViewTopLevelCheck.Verdict.Trusted -> Unit
        }
        if (href.isNullOrBlank() || href == "about:blank") return
        val changed = href != currentUrl
        // Enforce dynamic EDT-only hooks (`catchesMainFrameNavigation` / `routesMainFrameNavigation`)
        // whenever the top-level URL advances beyond the initial page. Deduplicate via
        // `lastBlockedUrl` while Wry is still reloading `lastTrustedUrl`.
        if (changed && href != initialUrl) {
            if (href == lastBlockedUrl) {
                pendingFinishAfterHref = false
                restoreAfterUntrustedTopLevel(untrustedUrl = null, notifyBlocked = false)
                return
            }
            val allowedByHost = callbacks.onNavigationRequested(href, isMainFrame = true)
            if (!allowedByHost) {
                lastBlockedUrl = href
                pendingFinishAfterHref = false
                // `callbacks.onNavigationRequested` already emitted onNavigationBlocked (or intercepted).
                restoreAfterUntrustedTopLevel(untrustedUrl = null, notifyBlocked = false)
                return
            }
        }
        lastBlockedUrl = ""
        if (href != lastTrustedUrl) restores = 0
        lastTrustedUrl = href
        if (changed) {
            currentUrl = href
            callbacks.onUrlChanged(href)
            if (loading && !commitSent) {
                commitSent = true
                callbacks.onLoadCommit(href)
            }
        }
        if (idle && (pendingFinishAfterHref || (!loading && !commitSent))) {
            pendingFinishAfterHref = false
            finishLoad()
        }
        if (changed) {
            callbacks.onStateChanged(
                DesktopWebSessionState(
                    url = currentUrl,
                    title = snapshot.title,
                    canGoBack = snapshot.canGoBack,
                    canGoForward = snapshot.canGoForward,
                    isLoading = loading,
                    progress = if (loading) 10 else 100,
                ),
            )
        }
    }

    private fun finishLoad() {
        loading = false
        val activeUrl = currentUrl.ifBlank { initialUrl }
        if (!commitSent) {
            commitSent = true
            callbacks.onLoadCommit(activeUrl)
        }
        callbacks.onLoadFinished(activeUrl)
    }

    private fun isTrustedTopLevelUrl(url: String): Boolean {
        val decision =
            evaluateMainFrameNavigation(
                targetUrl = url,
                initialUrl = config.url,
                policy = config.navigationPolicy,
            )
        return decision !is WebNavigationDecision.Block && decision !is WebNavigationDecision.ReissueWithoutHeader
    }

    private fun notifyBlockedOnce(
        url: String,
        reason: String,
    ) {
        if (url.isEmpty() || url == lastBlockedUrl) return
        lastBlockedUrl = url
        callbacks.onNavigationBlocked(url, reason)
    }

    private fun restoreAfterUntrustedTopLevel(
        untrustedUrl: String?,
        notifyBlocked: Boolean,
    ) {
        topLevelCheck.reset()
        if (notifyBlocked && !untrustedUrl.isNullOrBlank()) {
            notifyBlockedOnce(untrustedUrl, "untrusted_top_level")
        }
        bridge.stopLoading()
        val target = lastTrustedUrl?.takeIf { restores < MAX_TOP_LEVEL_RESTORES } ?: "about:blank"
        restores += 1
        bridge.loadUrl(target)
    }

    private fun handleIpcMessage(raw: String) {
        val parsed = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return
        if ((parsed["s"] as? JsonPrimitive)?.contentOrNull != channelSecret) return
        val value = (parsed["v"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        when ((parsed["k"] as? JsonPrimitive)?.contentOrNull) {
            "jsb" -> {
                val originUrl = currentUrl.ifBlank { initialUrl }
                callbacks.onJsbMessage(
                    rawPayload = value,
                    originUrl = originUrl,
                    isMainFrame = true,
                ) { replyJson ->
                    if (disposed) return@onJsbMessage
                    val escapedReply = JsonPrimitive(replyJson).toString()
                    val script =
                        "(function(){try{var b=window.raftBridge;" +
                            "if(b&&typeof b.__dispatchResponse==='function'){b.__dispatchResponse($escapedReply);}}catch(_){}})();"
                    postToEdt {
                        if (!disposed) bridge.evaluateJavaScript(script) {}
                    }
                }
            }
            "open" -> {
                // New-window requests (`window.open` / `target=_blank`) always honour
                // `allowNewWindows` even when `allowNavigation == true`.
                callbacks.onNewWindowRequested(value)
            }
        }
    }

    private fun updateTimerDelay(delayMs: Int) {
        val timer = pollTimer ?: return
        if (timer.delay != delayMs) {
            timer.delay = delayMs
        }
    }

    companion object {
        private val LocalSubframeSchemes = setOf("about", "blob", "data")
        private const val POLL_INTERVAL_MS = 250
        private const val VERIFY_INTERVAL_MS = 50
        private const val MAX_TOP_LEVEL_RESTORES = 2
        private const val HREF_SCRIPT = "\"\" + window.location.href"
    }
}

/**
 * Builds the single Desktop document-start script combining first-paint theme shielding, desktop
 * viewport meta, secret-authenticated JSB transport + `window.open` / `target=_blank` guards,
 * `window.raftBridge` v1 shim, offline bundle descriptor, and caller document-start scripts.
 */
internal fun buildDesktopContainerInitScript(
    config: WebContainerConfig,
    channelSecret: String,
): String {
    if (!config.allowScripts) return ""
    val secretLiteral = JsonPrimitive(channelSecret).toString()
    val jsbAllowedOrigins = config.jsbPolicy.allowedOrigins
    val lockedOrigin = config.navigationPolicy.lockedMainFrameOrigin
    val initialOrigin = parseWebContainerOrigin(config.url)
    val baseOrigins: Set<WebContainerOrigin> =
        when {
            lockedOrigin != null && initialOrigin != null && lockedOrigin != initialOrigin -> emptySet()
            lockedOrigin != null -> setOf(lockedOrigin)
            initialOrigin != null -> setOf(initialOrigin)
            else -> emptySet()
        }
    val windowOpenOrigins: Set<WebContainerOrigin> = baseOrigins + jsbAllowedOrigins
    return buildString {
        if (config.backgroundArgb != 0) {
            appendLine(JsbBootstrapScripts.buildFirstPaintThemeScript(config.backgroundArgb, config.isDarkTheme))
        }
        if (config.desktopViewportMode) {
            appendLine(JsbBootstrapScripts.DesktopViewportMetaScript)
        }
        // Authenticated top-level IPC channel: captures native `window.ipc.postMessage` before page
        // scripts execute so subframes and untrusted origins never learn [channelSecret].
        if (windowOpenOrigins.isNotEmpty()) {
            val openOriginsLiteral =
                windowOpenOrigins.joinToString(",") { JsonPrimitive(it.asOriginString()).toString() }
            val jsbOriginsLiteral =
                jsbAllowedOrigins.joinToString(",") { JsonPrimitive(it.asOriginString()).toString() }
            val portLiteral = JsonPrimitive(config.jsbPolicy.portName).toString()
            appendLine("(function(){")
            appendLine("if(window.top!==window.self)return;")
            appendLine("var openOrigins=[$openOriginsLiteral];")
            appendLine("if(openOrigins.indexOf(window.location.origin)===-1)return;")
            appendLine("var ipc=window.ipc;if(!ipc||typeof ipc.postMessage!=='function')return;")
            appendLine("var ipcPost=Function.prototype.bind.call(ipc.postMessage,ipc);")
            appendLine("var stringify=JSON.stringify;")
            appendLine("var post=function(k,v){try{ipcPost(stringify({s:$secretLiteral,k:k,v:String(v)}));}catch(_){}};")
            appendLine("var activated=function(){return !navigator.userActivation||navigator.userActivation.isActive;};")
            appendLine("var nativeOpen=window.open;")
            appendLine("window.open=function(u){if(!activated()||u===undefined||u===null||u===''){return nativeOpen.apply(window,arguments);}try{post('open',String(new URL(String(u),document.baseURI)));}catch(_){}return null;};")
            appendLine("window.addEventListener('click',function(e){if(!e.isTrusted||e.defaultPrevented||e.button!==0)return;var a=e.target&&e.target.closest?e.target.closest('a[href]'):null;if(!a||(a.target||'').toLowerCase()!=='_blank')return;e.preventDefault();post('open',a.href);});")
            if (config.jsbPolicy.isEffectivelyEnabled) {
                appendLine("var jsbOrigins=[$jsbOriginsLiteral];")
                appendLine("if(jsbOrigins.indexOf(window.location.origin)!==-1&&!window[$portLiteral]){")
                appendLine("Object.defineProperty(window,$portLiteral,{value:{postMessage:function(m){if(typeof m==='string')post('jsb',m);},onmessage:null},writable:false,configurable:false});")
                appendLine("}")
            }
            appendLine("})();")
        }
        if (config.jsbPolicy.isEffectivelyEnabled) {
            appendLine(
                JsbBootstrapScripts.buildPageBridgeScript(
                    portName = config.jsbPolicy.portName,
                    allowedOrigins = config.jsbPolicy.allowedOrigins,
                ),
            )
        }
        config.offlineBundle?.let { bundle ->
            if (baseOrigins.isNotEmpty()) {
                appendLine(JsbBootstrapScripts.buildOfflineBundleScript(bundle, baseOrigins))
            }
        }
        for (script in config.documentStartScripts) {
            val wrapped =
                JsbBootstrapScripts.wrapDocumentStartScript(
                    source = script.source,
                    allowedOrigins = script.allowedOrigins,
                    mainFrameOnly = script.mainFrameOnly,
                )
            if (wrapped.isNotEmpty()) {
                appendLine(wrapped)
            }
        }
    }
}

private fun generateDesktopChannelSecret(): String {
    val bytes = ByteArray(24)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}
