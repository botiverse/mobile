package build.raft.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.DownloadListener
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import build.raft.webcontainer.WebContainerAndroidFullscreenController
import build.raft.webcontainer.WebContainerAndroidInsetMode
import build.raft.webcontainer.WebContainerAndroidKeepAlive
import build.raft.webcontainer.WebContainerAndroidNewWindowTransport
import build.raft.webcontainer.WebJsDialogKind
import build.raft.webcontainer.WebJsDialogRequest
import build.raft.webcontainer.WebJsDialogResult
import build.raft.webcontainer.WebJsDialogTracker
import build.raft.webcontainer.extractAndroidHitTestLinkUrl
import build.raft.webcontainer.filterWebContainerAndroidInsets
import build.raft.webcontainer.isWebJsDialogPermitted
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import build.raft.app.matches
import build.raft.app.settings.matches
import build.raft.app.thread.key
import java.io.ByteArrayInputStream
import java.net.URI
import org.json.JSONObject as AndroidJSONObject

/**
 * Implemented by the hosting Activity to serve `<input type="file">` in sandbox WebViews
 * (the Kuikly host's `SlockSandboxWebViewFileChooserHost`).
 * TODO(cmp-switch): the CMP Android host must implement this (group 6); until then file inputs
 * receive a cancelled selection.
 */
interface RaftWebViewFileChooserHost {
    fun launchSandboxWebViewFileChooser(
        params: WebChromeClient.FileChooserParams,
        callback: ValueCallback<Array<Uri>>,
    ): Boolean
}

/** A main document served from a host cache instead of the network. */
class RaftWebViewDocument(val bytes: ByteArray, val mimeType: String, val source: String)

/** Android host hooks of the sandbox WebView. */
object RaftAndroidWebViewHooks {
    /**
     * Serves main-frame GET documents from a host cache (release notes: androidApp
     * `RaftReleaseNotesDocumentCache.loadForWebView`, called off the main thread).
     * TODO(cmp-switch): installed by the Android host (group 6); null = always network.
     */
    @Volatile
    var mainDocumentProvider: ((url: String) -> RaftWebViewDocument?)? = null
}

@Composable
internal actual fun RaftSandboxWebViewHost(
    config: RaftWebViewConfig,
    events: RaftWebViewEvents,
    controller: RaftWebViewController,
    modifier: Modifier,
) {
    AndroidView(
        factory = { context ->
            val partition = config.effectiveStoragePartition
            val effectiveKey =
                if (partition == RaftWebViewStoragePartition.IsolatedThirdParty) null else config.keepAliveKey
            // Yield any still-loading background prewarm (e.g. raft-app-warm) so it cannot starve
            // this user-initiated foreground WebView on Android's single Chromium renderer process.
            RaftAndroidWebViewKeepAlive.abortInFlightPrewarmForForeground(
                foregroundSurface = config.diagnosticsSurface,
                foregroundKeepAliveKey = effectiveKey,
            )
            WebContainerAndroidKeepAlive.abortInFlightPrewarmForForeground(
                foregroundSurface = config.diagnosticsSurface,
                telemetrySink = RaftWebContainerTelemetrySink,
            )
            val kept =
                effectiveKey?.let { key -> RaftAndroidWebViewKeepAlive.take(key, context, events, partition) }
            if (config.keepAliveKey != null) {
                raftLogInfo(RAFT_WEBVIEW_DIAGNOSTICS_TAG, "webview_attach source=${if (kept != null) "keep_alive" else "new"}")
            }
            kept ?: RaftAndroidSandboxWebView(MutableContextWrapper(context), events, initialStoragePartition = partition)
        },
        modifier = modifier,
        update = { view ->
            view.events = events
            view.applyConfig(config)
            view.bindController(controller)
        },
        onRelease = { view ->
            if (!RaftAndroidWebViewKeepAlive.park(view)) view.destroy()
        },
    )
}

private fun unwrapRaftFileChooserHost(context: Context): RaftWebViewFileChooserHost? {
    var current: Context? = context
    while (current != null) {
        if (current is RaftWebViewFileChooserHost) return current
        current = (current as? ContextWrapper)?.baseContext ?: return null
    }
    return null
}

/** Dedicated Android Chromium WebView profile name for third-party web pages (#proj-workspace:#397). */
internal const val RAFT_ANDROID_THIRD_PARTY_PROFILE_NAME = "raft_third_party"

/**
 * Port of the Kuikly host's `SlockSandboxWebViewRenderView` (androidApp): same settings, sandbox
 * navigation fence, document-start auth script, `raftHostPort` bridge, desktop scale and renderer
 * crash recovery. Kuikly props became [applyConfig], callbacks became [events], the
 * `navigationCommand` call became [bindController].
 */
@SuppressLint("ViewConstructor")
internal class RaftAndroidSandboxWebView(
    context: Context,
    var events: RaftWebViewEvents,
    initialStoragePartition: RaftWebViewStoragePartition = RaftWebViewStoragePartition.Default,
    private val supportsMultiProfile: () -> Boolean = ::raftPlatformSupportsIsolatedWebViewProfile,
    private val applyProfile: (WebView, String) -> Unit = { view, name -> WebViewCompat.setProfile(view, name) },
    private val isForegroundWindowForRecovery: (RaftAndroidSandboxWebView) -> Boolean = { it.isForegroundWindow() },
) : FrameLayout(context) {
    private data class PendingRenderProcessRestore(
        val restoreDocument: Boolean,
        val restoreUrl: String,
        val reason: String,
        val notifyProcessGone: Boolean = true,
        val controllerLoadUrl: String? = null,
    )

    private var webView: WebView? = WebView(context)
    private var destroyed: Boolean = false
    private var pendingRenderProcessRestore: PendingRenderProcessRestore? = null
    internal val hasPendingRenderProcessRestore: Boolean get() = pendingRenderProcessRestore != null
    private var latestWindowVisibility: Int = View.GONE
    internal var storagePartition: RaftWebViewStoragePartition = initialStoragePartition
        private set
    /** True when [RaftWebViewStoragePartition.IsolatedThirdParty] was requested without MULTI_PROFILE: fail closed. */
    private var isolatedProfileUnavailable: Boolean = false
    private var src: String = ""
    private var loadedSrc: String = ""
    private var lastSuccessfulUrl: String = ""
    private var allowedUri: Uri? = null
    private var allowScripts: Boolean = true
    private var allowNavigation: Boolean = false
    private var domStorageEnabled: Boolean = false
    private var useWideViewPort: Boolean = false
    /** #proj-workspace:#360: pinch-to-zoom stays enabled even while [useWideViewPort] is off. */
    private var allowPinchZoom: Boolean = false
    /** task #188: true once a desktop-mode initial scale has been applied, so it can be cleared. */
    private var desktopModeScaleApplied: Boolean = false
    /** task #188: guards the one retry used when the scale prop arrives before layout. */
    private var desktopModeScaleRetryScheduled: Boolean = false
    private var cacheEnabled: Boolean = false
    private var ephemeral: Boolean = true
    private var injectedJavaScript: String = ""
    private var pendingRuntimeJavaScript: String = ""
    private var allowedNavigationSchemes: Set<String> = emptySet()
    private var hostEventsEnabled: Boolean = false
    /** [RaftWebViewConfig.mainFrameOrigin]: every top-level document stays on it. */
    private var mainFrameOrigin: String? = null
    /** [RaftWebViewConfig.raftHost]: `Raft-Host` header attached to scoped main-frame GET navigations. */
    private var raftHost: RaftHostHeader? = null
    /** Top-level document URL last committed on this WebView, or `""` before any commit. */
    private var committedDocumentUrl: String = ""
    /** Whether any top-level document has committed on this WebView yet. */
    private var hasCommittedDocument: Boolean = false
    /** Current in-flight navigation chain carried `Raft-Host` and has not committed since. */
    private var navChainCarriesRaftHost: Boolean = false
    /** Remaining `Raft-Host` re-issues in the current navigation chain. */
    private var navChainReissueBudget: Int = RAFT_HOST_REISSUE_BUDGET
    /** Who started the current in-flight navigation chain (for [RaftWebViewMainFrameCatchAsk]). */
    private var navChainStarter: RaftSignInStarter = RaftSignInStarter.Other
    /** Prior URLs visited by the current navigation chain before the active candidate. */
    private val navChainHops = mutableListOf<String>()
    /** [RaftWebViewConfig.keepAliveKey]: parked past its page instead of destroyed. */
    internal var keepAliveKey: String? = null
        private set
    /** [RaftWebViewConfig.diagnosticsSurface]; `_parked` is appended while kept alive without a page. */
    private var diagnosticsSurface: String = RAFT_WEBVIEW_SURFACE_DEFAULT
    private var parkedForKeepAlive: Boolean = false
    /**
     * The app's light/dark mode the WebView was built in. Its page reads `prefers-color-scheme`
     * from the construction context's theme, which follows the app's choice (androidApp
     * RaftAndroidNightMode); keep-alive does not hand a view of the other mode to a page.
     */
    internal var builtDark: Boolean = RaftAppThemeState.isDark
        private set
    /** [RaftWebViewConfig.backgroundArgb]: the colour before the first paint (0: white). */
    private var placeholderBackground: Int = 0
    /** [RaftWebViewConfig.persistBackgroundAfterPaint]: keeps [placeholderBackground] applied after paint. */
    private var persistBackgroundAfterPaint: Boolean = false
    /** [RaftWebViewConfig.androidInsetMode]: controls which `WindowInsets` types pass to Chromium. */
    private var androidInsetMode: WebContainerAndroidInsetMode = WebContainerAndroidInsetMode.HostHandled
    /** The current WebView painted a page; from then on its background is the default white unless persisted. */
    private var pagePainted: Boolean = false
    internal val isDestroyed: Boolean get() = destroyed
    /** True when the current main-frame navigation failed (network/SSL/HTTP >= 400); cleared on next load. */
    internal var mainFrameLoadFailed: Boolean = false
        private set
    /** True while this view started loading a configured URL and has not yet finished cleanly. */
    internal val isMainFrameLoadInFlight: Boolean
        get() = !destroyed && !mainFrameLoadFailed && loadedSrc.isNotBlank() && (currentIsLoading || lastSuccessfulUrl.isBlank())
    private var currentUrl: String = ""
    private var currentTitle: String = ""
    private var currentProgress: Int = 0
    private var currentIsLoading: Boolean = false
    private var speculativeLinkLoading: Boolean = false

    /** Main-frame loads started ([RaftWebViewNavigationState.loadGeneration]); kept across a rebuilt WebView. */
    private var loadGeneration: Int = 0
    private val navigationStateRelay = RaftWebViewNavigationStateRelay()
    private var lastRoutedBlockedUrl: String = ""
    private var lastRoutedBlockedAtMs: Long = 0L
    /** URL intentionally cancelled by [RaftWebViewEvents.catchesMainFrameNavigation] (mirrors iOS `hostCancelledUrl`). */
    private var lastHostCancelledUrl: String = ""
    private var lastHostCancelledAtMs: Long = 0L
    /**
     * `loadGeneration` of an in-flight navigation whose redirect target was caught & cancelled by
     * [tryCatchMainFrameNavigation] after `onPageStarted` had already started that load (e.g. a
     * same-origin GET form `/login?agree=1` redirecting 302 to `/api/oauth/authorize`). Swallows
     * trailing `onPageFinished`/`onPageCommitVisible`/`onReceivedError` callbacks for the aborted
     * intermediate URL so they never falsely commit or cancel the in-flight native sign-in pipeline.
     */
    private var hostCancelledLoadGeneration: Int = -1
    private var lastLoadFailureReason: String = ""
    private var hostMessageListener: WebViewCompat.WebMessageListener? = null
    /** [startPageDownload]'s `blob:` reads in flight, by token; their port and name hook. */
    private val pendingBlobDownloads = LinkedHashMap<String, RaftWebViewBlobDownloadAssembler>()
    private var downloadMessageListener: WebViewCompat.WebMessageListener? = null
    private var downloadNameScriptHandler: ScriptHandler? = null
    /** The page's `navigator.share` calls being received ([raftWebViewShareShimScript]); one per installed port. */
    private var shareSession: RaftAndroidWebViewShareSession? = null
    private val shareMainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var shareExpiryCheck: Runnable? = null
    private var documentStartScriptHandler: ScriptHandler? = null
    /** [raftWebViewFirstPaintThemeScript] at document start; separate from the auth/bridge script. */
    private var firstPaintScriptHandler: ScriptHandler? = null
    private val bootstrapNavigationFence = RaftWebViewBootstrapNavigationFence()
    private var boundController: RaftWebViewController? = null
    private var allowJsDialogs: Boolean = false
    private var allowNewWindows: Boolean = false
    private val jsDialogTracker = WebJsDialogTracker()
    internal val fullscreenController = WebContainerAndroidFullscreenController()
    internal val newWindowTransport = WebContainerAndroidNewWindowTransport()
    /** Diagnostics: when the current main-frame load started (0: none), and the progress milestones logged. */
    private var loadStartedAtMs: Long = 0L
    private var loggedProgressMilestone: Int = 0
    private var persistedConsoleErrors: Int = 0

    init {
        RaftAndroidWebViewProvider.logOnce(context)
        val initialWebView = checkNotNull(webView)
        prepareWebViewProfile(initialWebView)
        addView(initialWebView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        configureWebView(initialWebView)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        latestWindowVisibility = windowVisibility
        if (isForegroundWindowForRecovery(this)) {
            flushPendingRenderProcessRestore()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        latestWindowVisibility = visibility
        if (visibility != View.VISIBLE) {
            jsDialogTracker.cancelActive()
            fullscreenController.hide()
            newWindowTransport.cancelAll()
        }
        if (isForegroundWindowForRecovery(this)) {
            flushPendingRenderProcessRestore()
        }
    }

    override fun onDetachedFromWindow() {
        latestWindowVisibility = View.GONE
        jsDialogTracker.cancelActive()
        fullscreenController.hide()
        newWindowTransport.cancelAll()
        super.onDetachedFromWindow()
    }

    private fun isForegroundWindow(): Boolean =
        isAttachedToWindow && latestWindowVisibility == View.VISIBLE

    private fun handleLegacyAndroidJsDialog(
        configuredWebView: WebView,
        kind: WebJsDialogKind,
        url: String?,
        message: String?,
        defaultPromptText: String?,
        onConfirm: (String?) -> Unit,
        onCancel: () -> Unit,
    ): Boolean {
        val visibleAndActive = !destroyed && !parkedForKeepAlive && configuredWebView === webView && isForegroundWindow()
        val handler = events.onJsDialog
        if (!allowJsDialogs && handler == null) {
            if (!visibleAndActive) {
                runCatching { onCancel() }
                return true
            }
            return false
        }
        if (!isWebJsDialogPermitted(allowJsDialogs, visibleAndActive, handler != null)) {
            runCatching { onCancel() }
            return true
        }
        val originUrl = url?.trim()?.takeIf { it.isNotEmpty() } ?: currentUrl
        var requestRef: WebJsDialogRequest? = null
        val request =
            WebJsDialogRequest(
                kind = kind,
                originUrl = originUrl,
                message = message,
                defaultPromptText = defaultPromptText,
                onSettled = { requestRef?.let(jsDialogTracker::clearIfSame) },
                onResult = { dialogResult ->
                    val deliver = {
                        runCatching {
                            when (dialogResult) {
                                WebJsDialogResult.AlertDismissed -> onConfirm(null)
                                is WebJsDialogResult.Confirm -> if (dialogResult.confirmed) onConfirm(null) else onCancel()
                                is WebJsDialogResult.Prompt ->
                                    if (dialogResult.value != null) onConfirm(dialogResult.value) else onCancel()
                            }
                        }
                        Unit
                    }
                    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                        deliver()
                    } else {
                        shareMainHandler.post { deliver() }
                    }
                },
            )
        requestRef = request
        jsDialogTracker.begin(request)
        val presented = runCatching { handler!!.invoke(request) }.isSuccess
        if (!presented) {
            request.cancel()
        }
        return true
    }

    private fun destroyDeadWebView(dead: WebView?) {
        removeHostMessageListener()
        removeDownloadSupport()
        removeDocumentStartScript()
        pendingRuntimeJavaScript = ""
        val target = dead ?: webView
        if (target === webView) webView = null
        runCatching {
            target?.let {
                ViewCompat.setOnApplyWindowInsetsListener(it, null)
                it.webChromeClient = null
                it.webViewClient = WebViewClient()
                removeView(it)
                it.destroy()
            }
        }
    }

    private fun flushPendingRenderProcessRestore() {
        if (destroyed || parkedForKeepAlive) return
        val pending = pendingRenderProcessRestore ?: return
        pendingRenderProcessRestore = null
        val controllerUrl = pending.controllerLoadUrl
        if (controllerUrl != null) {
            diagnosticsInfo("render_process_gone_restore_resumed mode=controller_url url=${controllerUrl.formatUrlForLog()}")
            recreateWebView(restoreCurrentDocument = false)
            loadControllerUrl(controllerUrl)
            return
        }
        val restoreUrl =
            if (pending.restoreDocument) {
                raftWebViewRendererGoneRestoreUrl(
                    src,
                    pending.restoreUrl,
                    ::rendererGoneRestoreAllows,
                )
            } else {
                ""
            }
        diagnosticsInfo("render_process_gone_restore_resumed url=${restoreUrl.formatUrlForLog()} shown=${restoreUrl != src}")
        if (pending.notifyProcessGone) {
            events.onContentProcessGone(pending.reason)
            if (destroyed) return
        }
        recreateWebView(restoreCurrentDocument = pending.restoreDocument, restoreUrl = restoreUrl)
        notifyNavigationState(force = true)
    }

    /** Applies the attrs in the Kuikly node's order (src last). Each setter is a no-op when unchanged. */
    fun applyConfig(config: RaftWebViewConfig) {
        if (destroyed) return
        updateStoragePartition(config.effectiveStoragePartition)
        updateAllowScripts(config.allowScripts)
        updateAllowNavigation(config.allowNavigation)
        updateDomStorageEnabled(config.domStorageEnabled)
        updateCacheEnabled(config.cacheEnabled)
        updateEphemeral(config.ephemeral)
        // Before the wide-viewport switch, which reloads: the reloaded document gets the new script
        // (the Artifact viewer's Desktop Mode changes both at once).
        updateInjectedJavaScript(config.injectedJavaScript)
        updateAllowPinchZoom(config.allowPinchZoom)
        updateUseWideViewPort(config.useWideViewPort)
        updateAllowedNavigationSchemes(config.allowedNavigationSchemes)
        updateHostEventsEnabled(config.hostEventsEnabled)
        mainFrameOrigin = config.mainFrameOrigin?.let(::raftDocumentStartScriptOriginRule)
        raftHost = config.raftHost
        keepAliveKey =
            if (storagePartition == RaftWebViewStoragePartition.IsolatedThirdParty) null else config.keepAliveKey
        diagnosticsSurface = config.diagnosticsSurface
        allowJsDialogs = config.allowJsDialogs
        if (!allowJsDialogs) {
            jsDialogTracker.cancelActive()
        }
        updateAllowNewWindows(config.allowNewWindows)
        updatePlaceholderBackground(config.backgroundArgb, config.persistBackgroundAfterPaint)
        updateAndroidInsetMode(config.androidInsetMode)
        updateSrc(config.url)
    }

    /**
     * Binds [target] to [RAFT_ANDROID_THIRD_PARTY_PROFILE_NAME] immediately after construction and
     * before any setting/load when [storagePartition] is [RaftWebViewStoragePartition.IsolatedThirdParty].
     * Fails closed ([isolatedProfileUnavailable] = true) if `MULTI_PROFILE` is unsupported or throws.
     */
    private fun prepareWebViewProfile(target: WebView) {
        if (storagePartition != RaftWebViewStoragePartition.IsolatedThirdParty) {
            isolatedProfileUnavailable = false
            return
        }
        if (!supportsMultiProfile()) {
            isolatedProfileUnavailable = true
            diagnosticsWarn("isolated_profile_unavailable reason=multi_profile_unsupported", always = true)
            return
        }
        val applied =
            runCatching {
                applyProfile(target, RAFT_ANDROID_THIRD_PARTY_PROFILE_NAME)
                true
            }.getOrElse { error ->
                diagnosticsWarn(
                    "isolated_profile_unavailable reason=set_profile_failed errorType=${error::class.simpleName ?: "unknown"}",
                    always = true,
                )
                false
            }
        isolatedProfileUnavailable = !applied
    }

    private fun updateStoragePartition(next: RaftWebViewStoragePartition) {
        if (storagePartition == next) return
        val isolatedChanged =
            (storagePartition == RaftWebViewStoragePartition.IsolatedThirdParty) !=
                (next == RaftWebViewStoragePartition.IsolatedThirdParty)
        storagePartition = next
        if (!isolatedChanged) return
        if (next == RaftWebViewStoragePartition.IsolatedThirdParty && !supportsMultiProfile()) {
            isolatedProfileUnavailable = true
            diagnosticsWarn("isolated_profile_unavailable reason=multi_profile_unsupported", always = true)
            webView?.stopLoading()
            currentIsLoading = false
            return
        }
        // Chromium profiles must be attached right after WebView construction, before any load.
        val previous = webView
        removeHostMessageListener()
        removeDownloadSupport()
        removeDocumentStartScript()
        webView = null
        runCatching {
            previous?.let {
                ViewCompat.setOnApplyWindowInsetsListener(it, null)
                it.stopLoading()
                it.webChromeClient = null
                it.webViewClient = WebViewClient()
                removeView(it)
                it.destroy()
            }
        }
        val pending = pendingRenderProcessRestore
        if (pending != null) {
            pendingRenderProcessRestore =
                pending.copy(restoreDocument = false, restoreUrl = "", notifyProcessGone = false, controllerLoadUrl = null)
        } else {
            recreateWebView(restoreCurrentDocument = false)
        }
    }

    /**
     * Until its first page paints, the WebView shows the app's canvas colour instead of its default
     * white: the page is visible from attach (no cover), and a white rectangle would flash in dark
     * mode. Afterwards white, as a browser, under pages that paint no background of their own —
     * unless [persistAfterPaint] is true, in which case [argb] stays applied across paint and live
     * theme switches.
     */
    private fun updatePlaceholderBackground(argb: Int, persistAfterPaint: Boolean) {
        val changed = placeholderBackground != argb || persistBackgroundAfterPaint != persistAfterPaint
        placeholderBackground = argb
        persistBackgroundAfterPaint = persistAfterPaint
        if (!pagePainted || changed) {
            webView?.setBackgroundColor(
                raftWebViewBackgroundArgb(placeholderBackground, painted = pagePainted, persistAfterPaint = persistBackgroundAfterPaint),
            )
        }
    }

    private fun updateAndroidInsetMode(next: WebContainerAndroidInsetMode) {
        if (androidInsetMode == next) return
        androidInsetMode = next
        webView?.let(ViewCompat::requestApplyInsets)
    }

    private fun onPagePainted() {
        if (pagePainted) return
        pagePainted = true
        webView?.setBackgroundColor(
            raftWebViewBackgroundArgb(placeholderBackground, painted = true, persistAfterPaint = persistBackgroundAfterPaint),
        )
    }

    /** The kept document (keep-alive), as [RaftWebViewKeptDocument], or null when destroyed or failed. */
    internal fun keptDocument(): RaftWebViewKeptDocument? {
        val activeWebView = webView ?: return null
        if (destroyed || mainFrameLoadFailed || pendingRenderProcessRestore != null) return null
        return RaftWebViewKeptDocument(
            src = src,
            currentUrl = activeWebView.url.orEmpty().ifBlank { currentUrl },
            loaded = !currentIsLoading && lastSuccessfulUrl.isNotBlank(),
        )
    }

    /**
     * Keep-alive: runs [script] in the top-level document when it is a loaded document of
     * [origin] (never a sub-frame: `evaluateJavascript` reaches the main frame only).
     */
    internal fun evaluateInTopDocument(origin: String, script: String): Boolean {
        val activeWebView = webView ?: return false
        if (destroyed || mainFrameLoadFailed || pendingRenderProcessRestore != null || !allowScripts || script.isBlank()) return false
        val expected = raftDocumentStartScriptOriginRule(origin) ?: return false
        val active = raftDocumentStartScriptOriginRule(activeWebView.url.orEmpty()) ?: return false
        if (active != expected || (mainFrameOrigin != null && active != mainFrameOrigin)) return false
        if (currentIsLoading || lastSuccessfulUrl.isBlank()) return false
        activeWebView.evaluateJavascript(script, null)
        return true
    }

    /** Keep-alive: detaches from the page that showed it; its callbacks go nowhere until taken again. */
    internal fun detachForKeepAlive() {
        jsDialogTracker.cancelActive()
        fullscreenController.hide()
        newWindowTransport.cancelAll()
        boundController?.unbindCommandDispatcher(this)
        boundController = null
        events = RaftWebViewEvents()
        parkedForKeepAlive = true
        (parent as? android.view.ViewGroup)?.removeView(this)
        (context as? MutableContextWrapper)?.baseContext = context.applicationContext
    }

    /** Keep-alive: shown by a page again, in that page's [hostContext]. */
    internal fun attachFromKeepAlive(hostContext: Context, nextEvents: RaftWebViewEvents) {
        (context as? MutableContextWrapper)?.baseContext = hostContext
        parkedForKeepAlive = false
        events = nextEvents
        navigationStateRelay.reset()
        notifyNavigationState(force = true)
        if (!mainFrameLoadFailed && lastSuccessfulUrl.isNotBlank()) events.onLoadSuccess()
    }

    fun bindController(controller: RaftWebViewController) {
        if (boundController === controller) return
        boundController?.unbindCommandDispatcher(this)
        boundController = controller
        controller.bindCommandDispatcher(this) { command -> executeNavigationCommand(command) }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        parkedForKeepAlive = false
        jsDialogTracker.cancelActive()
        fullscreenController.hide()
        newWindowTransport.cancelAll()
        pendingRenderProcessRestore = null
        boundController?.unbindCommandDispatcher(this)
        boundController = null
        removeHostMessageListener()
        removeDownloadSupport()
        pendingRuntimeJavaScript = ""
        removeDocumentStartScript()
        diagnosticsInfo("destroy_webview loadedSrc=${loadedSrc.formatUrlForLog()}")
        val active = webView
        webView = null
        if (active != null) {
            runCatching {
                ViewCompat.setOnApplyWindowInsetsListener(active, null)
                active.stopLoading()
                active.loadUrl(ABOUT_BLANK_URL)
                active.webChromeClient = null
                active.webViewClient = WebViewClient()
                removeView(active)
                active.destroy()
            }
        }
    }

    /** [restoreUrl]: the page to load in the new WebView (the one shown, [raftWebViewRendererGoneRestoreUrl]); [src] when blank. */
    private fun recreateWebView(restoreCurrentDocument: Boolean, restoreUrl: String = "") {
        jsDialogTracker.cancelActive()
        fullscreenController.hide()
        newWindowTransport.cancelAll()
        diagnosticsInfo("recreate_webview previousSrc=${src.formatUrlForLog()}")
        pendingRenderProcessRestore = null
        loadedSrc = ""
        lastSuccessfulUrl = ""
        mainFrameLoadFailed = false
        allowedUri = src.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        currentUrl = src
        currentTitle = ""
        currentProgress = 0
        currentIsLoading = false
        navigationStateRelay.reset()
        lastRoutedBlockedUrl = ""
        lastRoutedBlockedAtMs = 0L
        lastHostCancelledUrl = ""
        lastHostCancelledAtMs = 0L
        hostCancelledLoadGeneration = -1
        lastLoadFailureReason = ""
        pendingRuntimeJavaScript = ""
        committedDocumentUrl = ""
        hasCommittedDocument = false
        resetNavigationChain()
        val nextWebView = WebView(context)
        webView = nextWebView
        prepareWebViewProfile(nextWebView)
        builtDark = RaftAppThemeState.isDark
        pagePainted = false
        nextWebView.setBackgroundColor(
            raftWebViewBackgroundArgb(placeholderBackground, painted = false, persistAfterPaint = persistBackgroundAfterPaint),
        )
        removeAllViews()
        addView(nextWebView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        configureWebView(nextWebView)
        ViewCompat.requestApplyInsets(nextWebView)
        if (restoreCurrentDocument) restoreCurrentDocumentAfterRecreation(restoreUrl)
    }

    private fun restoreCurrentDocumentAfterRecreation(restoreUrl: String) {
        val activeWebView = webView ?: return
        if (isolatedProfileUnavailable) {
            currentIsLoading = false
            notifyNavigationState(force = true)
            if (src.isNotBlank()) notifyLoadFailure(RAFT_WEBVIEW_ISOLATED_PROFILE_UNAVAILABLE_REASON)
            return
        }
        if (src.isBlank()) {
            activeWebView.loadUrl(ABOUT_BLANK_URL)
            notifyNavigationState(force = true)
            return
        }
        if (!isNavigationSchemeAllowed(src) || !raftMainFrameOriginAllows(mainFrameOrigin, src)) {
            notifyNavigationBlocked(src)
            notifyNavigationState(force = true)
            return
        }
        loadAllowedSrc(src, documentUrl = restoreUrl.ifBlank { src })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(configuredWebView: WebView) {
        ViewCompat.setOnApplyWindowInsetsListener(configuredWebView) { view, insets ->
            val filtered = filterWebContainerAndroidInsets(insets, androidInsetMode)
            ViewCompat.onApplyWindowInsets(view, filtered)
        }
        configuredWebView.settings.apply {
            javaScriptEnabled = allowScripts
            domStorageEnabled = this@RaftAndroidSandboxWebView.domStorageEnabled
            // task #188: without this Android ignores `width=` in the page's viewport meta. Both
            // sides qualified on purpose: inside settings.apply a bare name binds to WebSettings.
            useWideViewPort = this@RaftAndroidSandboxWebView.useWideViewPort
            loadWithOverviewMode = this@RaftAndroidSandboxWebView.useWideViewPort
            // Pinch-to-zoom is on in Desktop Mode (a wide layout fitted to the screen) and on
            // surfaces that opt in (#proj-workspace:#360, the Artifact viewer). Android WebView
            // pinch-zooms only with supportZoom + builtInZoomControls on (its +/- buttons stay hidden).
            setSupportZoom(true)
            builtInZoomControls =
                this@RaftAndroidSandboxWebView.useWideViewPort || this@RaftAndroidSandboxWebView.allowPinchZoom
            displayZoomControls = false
            @Suppress("DEPRECATION")
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(this@RaftAndroidSandboxWebView.allowNewWindows)
            javaScriptCanOpenWindowsAutomatically = false
            cacheMode = if (cacheEnabled) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_NO_CACHE
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        configuredWebView.webChromeClient =
            object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (destroyed || configuredWebView !== webView) return
                    super.onProgressChanged(view, newProgress)
                    currentProgress = newProgress.coerceIn(0, 100)
                    if (speculativeLinkLoading && currentProgress >= 100) {
                        speculativeLinkLoading = false
                        currentIsLoading = false
                    }
                    logProgressMilestone(currentProgress)
                    currentUrl = view?.url.orEmpty().ifBlank { currentUrl }
                    notifyNavigationState()
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (destroyed || configuredWebView !== webView) return
                    super.onReceivedTitle(view, title)
                    currentTitle = title.orEmpty()
                    currentUrl = view?.url.orEmpty().ifBlank { currentUrl }
                    notifyNavigationState()
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    if (destroyed || parkedForKeepAlive || configuredWebView !== webView) return false
                    if (!allowNewWindows) {
                        // Multiple windows are off on this surface: route any anchor link as a blocked page.
                        val link = extractAndroidHitTestLinkUrl(view?.hitTestResult)
                        notifyNavigationBlocked(link, isUserGesture = isUserGesture)
                        return false
                    }
                    if (!isUserGesture || !isForegroundWindow()) {
                        return false
                    }
                    val opened =
                        newWindowTransport.open(
                            context = context,
                            resultMsg = resultMsg,
                            profileName =
                                if (storagePartition == RaftWebViewStoragePartition.IsolatedThirdParty) {
                                    RAFT_ANDROID_THIRD_PARTY_PROFILE_NAME
                                } else {
                                    null
                                },
                            onUrlCaptured = { capturedUrl ->
                                handleLegacyNewWindowUrl(configuredWebView, capturedUrl)
                            },
                        )
                    if (opened) return true
                    val fallbackLink = extractAndroidHitTestLinkUrl(view?.hitTestResult)
                    if (fallbackLink.isNotBlank()) {
                        handleLegacyNewWindowUrl(configuredWebView, fallbackLink)
                    }
                    return false
                }

                override fun onShowCustomView(
                    view: View?,
                    callback: CustomViewCallback?,
                ) {
                    fullscreenController.show(
                        context = context,
                        view = view,
                        callback = callback,
                        permitted = !destroyed && !parkedForKeepAlive && configuredWebView === webView && isForegroundWindow(),
                    )
                }

                override fun onHideCustomView() {
                    fullscreenController.hide()
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    // Per product decision (#proj-mobile:#395 / #397): deny all web camera/microphone/media
                    // permission requests on Android without forwarding system runtime permission prompts.
                    runCatching { request?.deny() }
                }

                override fun onShowFileChooser(
                    view: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    params: FileChooserParams?
                ): Boolean {
                    if (filePathCallback == null) return false
                    if (destroyed || configuredWebView !== webView || params == null) {
                        filePathCallback.onReceiveValue(null)
                        return false
                    }
                    val host = unwrapRaftFileChooserHost(context)
                    if (host == null) {
                        filePathCallback.onReceiveValue(null)
                        return false
                    }
                    return host.launchSandboxWebViewFileChooser(params, filePathCallback)
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    if (isRestrictedGenericMode()) return true
                    if (!destroyed && configuredWebView === webView && consoleMessage != null) logConsoleMessage(consoleMessage)
                    return super.onConsoleMessage(consoleMessage)
                }

                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?,
                ): Boolean {
                    if (result == null) return false
                    return handleLegacyAndroidJsDialog(
                        configuredWebView = configuredWebView,
                        kind = WebJsDialogKind.Alert,
                        url = url,
                        message = message,
                        defaultPromptText = null,
                        onConfirm = { result.confirm() },
                        onCancel = { result.cancel() },
                    )
                }

                override fun onJsConfirm(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?,
                ): Boolean {
                    if (result == null) return false
                    return handleLegacyAndroidJsDialog(
                        configuredWebView = configuredWebView,
                        kind = WebJsDialogKind.Confirm,
                        url = url,
                        message = message,
                        defaultPromptText = null,
                        onConfirm = { result.confirm() },
                        onCancel = { result.cancel() },
                    )
                }

                override fun onJsPrompt(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    defaultValue: String?,
                    result: JsPromptResult?,
                ): Boolean {
                    if (result == null) return false
                    return handleLegacyAndroidJsDialog(
                        configuredWebView = configuredWebView,
                        kind = WebJsDialogKind.Prompt,
                        url = url,
                        message = message,
                        defaultPromptText = defaultValue,
                        onConfirm = { value -> result.confirm(value ?: "") },
                        onCancel = { result.cancel() },
                    )
                }
            }
        configuredWebView.webViewClient =
            object : WebViewClient() {
                /**
                 * task #6: the platform kills the whole app when a renderer process is reclaimed
                 * and no client claims the event. Rebuild the WebView and reload the current
                 * document; the dead back stack is intentionally not restored.
                 */
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    if (destroyed) return true
                    if (configuredWebView !== webView) {
                        runCatching {
                            view?.let {
                                it.webChromeClient = null
                                it.webViewClient = WebViewClient()
                                removeView(it)
                                it.destroy()
                            }
                        }
                        return true
                    }
                    // Logged and joined to the death event (one Hands ticket however many WebViews it took).
                    jsDialogTracker.cancelActive()
                    fullscreenController.hide()
                    newWindowTransport.cancelAll()
                    RaftWebViewRendererGone.record(
                        context,
                        if (parkedForKeepAlive) "${diagnosticsSurface}_parked" else diagnosticsSurface,
                        detail,
                    )
                    diagnosticsWarn(
                        "render_process_gone didCrash=${detail?.didCrash()} " +
                            "priority=${detail?.rendererPriorityAtExit()} " +
                            "loadedSrc=${loadedSrc.formatUrlForLog()}",
                        always = true,
                    )
                    if (parkedForKeepAlive) {
                        destroyDeadWebView(view)
                        RaftAndroidWebViewKeepAlive.dropIfSame(this@RaftAndroidSandboxWebView, "render_process_gone")
                        if (!destroyed) destroy()
                        return true
                    }
                    val restoreDocument = loadedSrc.isNotBlank()
                    // #300: the page it was showing (a kept Artifact viewer is routed in place from
                    // its warm src), checked against the fence before the WebView is rebuilt.
                    val restoreUrl =
                        // Gone during a bootstrap load: the page it was loading, not the placeholder.
                        raftWebViewRendererGoneRestoreUrl(
                            src,
                            bootstrapNavigationFence.pendingTargetUrl ?: currentUrl,
                            ::rendererGoneRestoreAllows,
                        )
                    val reason = if (detail?.didCrash() == true) "render_process_crashed" else "render_process_killed"
                    if (!isForegroundWindowForRecovery(this@RaftAndroidSandboxWebView)) {
                        diagnosticsInfo(
                            "render_process_gone_deferred url=${restoreUrl.formatUrlForLog()} " +
                                "attached=$isAttachedToWindow windowVisibility=$latestWindowVisibility",
                        )
                        pendingRenderProcessRestore =
                            PendingRenderProcessRestore(
                                restoreDocument = restoreDocument,
                                restoreUrl = restoreUrl,
                                reason = reason,
                            )
                        destroyDeadWebView(view)
                        return true
                    }
                    diagnosticsInfo("render_process_gone_restore url=${restoreUrl.formatUrlForLog()} shown=${restoreUrl != src}")
                    // The host's load watchdog: an error state when the page was not up yet.
                    events.onContentProcessGone(reason)
                    destroyDeadWebView(view)
                    if (destroyed) return true
                    recreateWebView(restoreCurrentDocument = restoreDocument, restoreUrl = restoreUrl)
                    notifyNavigationState(force = true)
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    if (destroyed || configuredWebView !== webView) return
                    speculativeLinkLoading = false
                    jsDialogTracker.cancelActive()
                    fullscreenController.hide()
                    super.onPageStarted(view, url, favicon)
                    val target = url.orEmpty()
                    loadStartedAtMs = System.currentTimeMillis()
                    loggedProgressMilestone = 0
                    persistedConsoleErrors = 0
                    diagnosticsInfo("page_started url=${target.formatUrlForLog()}")
                    bootstrapNavigationFence.onMainFrameStarted(target)
                    val sourceOrigin = raftDocumentStartScriptOriginRule(src)
                    val targetOrigin = raftDocumentStartScriptOriginRule(target)
                    if (sourceOrigin == null || targetOrigin != sourceOrigin) pendingRuntimeJavaScript = ""
                    if (isRecentlyHostCancelled(target)) {
                        view?.stopLoading()
                        currentIsLoading = false
                        notifyNavigationState()
                        return
                    }
                    if (target.isNotEmpty() && tryCatchMainFrameNavigation(view, target, stopLoading = true)) {
                        diagnosticsInfo("page_started_caught url=${target.formatUrlForLog()}")
                        return
                    }
                    if (wouldBlockMainFrameNavigation(url)) {
                        view?.stopLoading()
                        handleBlockedMainFrameNavigation(url)
                        return
                    }
                    if (target.isNotEmpty() && invokeRoutesMainFrameNavigation(target, isRedirect = true, isUserGesture = false)) {
                        view?.stopLoading()
                        val now = System.currentTimeMillis()
                        val recentlyRouted =
                            target == lastRoutedBlockedUrl &&
                                (now - lastRoutedBlockedAtMs) in 0L..1_500L
                        if (!recentlyRouted) {
                            diagnosticsInfo("page_started_routed_as_link url=${target.formatUrlForLog()}")
                            lastRoutedBlockedUrl = target
                            lastRoutedBlockedAtMs = now
                            handleBlockedMainFrameNavigation(target)
                        } else {
                            currentIsLoading = false
                            notifyNavigationState()
                        }
                        return
                    }
                    if (bootstrapNavigationFence.pendingTargetUrl == null &&
                        (target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)) &&
                        navChainHops.lastOrNull() != target
                    ) {
                        recordNavChainHop(target)
                    }
                    hostCancelledLoadGeneration = -1
                    currentUrl = target
                    currentTitle = ""
                    currentProgress = 0
                    currentIsLoading = true
                    loadGeneration += 1
                    lastSuccessfulUrl = ""
                    lastLoadFailureReason = ""
                    mainFrameLoadFailed = false
                    notifyUrlChange(target)
                    notifyNavigationState()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (destroyed || configuredWebView !== webView) return
                    speculativeLinkLoading = false
                    super.onPageFinished(view, url)
                    val target = url.orEmpty()
                    diagnosticsInfo("page_finished url=${target.formatUrlForLog()} ${loadElapsedField()}")
                    if (isCurrentLoadHostCancelled(target) || isRecentlyRoutedBlocked(target)) {
                        currentIsLoading = false
                        notifyNavigationState()
                        return
                    }
                    if (url != null && !shouldBlockMainFrameNavigation(url)) {
                        currentUrl = target
                        currentTitle = view?.title.orEmpty().ifBlank { currentTitle }
                        currentProgress = 100
                        currentIsLoading = false
                        if (!mainFrameLoadFailed) {
                            evaluateInjectedJavaScript(url)
                            if (bootstrapNavigationFence.mayUncover(url)) {
                                markDocumentCommitted(target)
                                onPagePainted()
                                notifyLoadSuccess(url, "finished", beforeCallback = ::flushPendingRuntimeJavaScript)
                                if (parkedForKeepAlive) {
                                    RaftAndroidWebViewKeepAlive.onParkedLoadFinished(this@RaftAndroidSandboxWebView)
                                }
                            }
                        }
                        notifyUrlChange(target)
                        notifyNavigationState()
                    }
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    if (destroyed || configuredWebView !== webView) return
                    speculativeLinkLoading = false
                    super.onPageCommitVisible(view, url)
                    val target = url.orEmpty()
                    diagnosticsInfo("page_commit_visible url=${target.formatUrlForLog()} ${loadElapsedField()}")
                    if (isCurrentLoadHostCancelled(target) || isRecentlyRoutedBlocked(target)) {
                        currentIsLoading = false
                        notifyNavigationState()
                        return
                    }
                    if (url != null && !shouldBlockMainFrameNavigation(url)) {
                        currentUrl = target
                        currentTitle = view?.title.orEmpty().ifBlank { currentTitle }
                        notifyUrlChange(target)
                        notifyNavigationState()
                        if (!mainFrameLoadFailed && bootstrapNavigationFence.mayUncover(url)) {
                            markDocumentCommitted(target)
                            onPagePainted()
                            notifyLoadSuccess(url, "commit_visible")
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    if (destroyed || configuredWebView !== webView) true
                    else if (url?.startsWith("raft-onboarding-wake:") == true) shouldBlockMainFrameNavigation(url)
                    else shouldSilentlyBlockSubframeNavigation(url)

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    if (destroyed || configuredWebView !== webView) return true
                    val target = request?.url?.toString()
                    return if (request?.isForMainFrame == true) {
                        handleMainFrameShouldOverrideUrlLoading(view, request, target)
                    } else {
                        shouldSilentlyBlockSubframeNavigation(target)
                    }
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    if (destroyed || configuredWebView !== webView) return
                    super.doUpdateVisitedHistory(view, url, isReload)
                    val target = url.orEmpty()
                    // History API / fragment updates are same-document state changes; keep the
                    // visible URL in sync even when allowNavigation is false.
                    if (target.isBlank() || !isNavigationSchemeAllowed(target)) return
                    currentUrl = target
                    currentTitle = view?.title.orEmpty().ifBlank { currentTitle }
                    notifyUrlChange(target)
                    notifyNavigationState()
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    if (destroyed || configuredWebView !== webView) return null
                    val url = request?.url?.toString()
                    if (request?.isForMainFrame == true && wouldBlockMainFrameNavigation(url)) {
                        val blockedUrl = url.orEmpty()
                        view?.post { handleBlockedMainFrameNavigation(blockedUrl) }
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    if (request?.isForMainFrame == true && request.method.equals("GET", ignoreCase = true)) {
                        hostMainDocumentResponse(url)?.let { return it }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    if (destroyed || configuredWebView !== webView) return
                    if (request?.isForMainFrame == true) {
                        speculativeLinkLoading = false
                        val failedUrl = request.url?.toString().orEmpty()
                        if (isCurrentLoadHostCancelled(failedUrl) || isRecentlyRoutedBlocked(failedUrl)) {
                            currentIsLoading = false
                            notifyNavigationState()
                            return
                        }
                    }
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        val failedUrl = request.url?.toString().orEmpty()
                        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        val reason = (if (modern) error?.description?.toString() else null) ?: "webview_load_failed"
                        val errorCode = if (modern) error?.errorCode ?: 0 else 0
                        mainFrameLoadFailed = true
                        lastSuccessfulUrl = ""
                        currentUrl = failedUrl.ifBlank { currentUrl }
                        currentIsLoading = false
                        resetNavigationChain()
                        diagnosticsWarn("page_error code=$errorCode url=${currentUrl.formatUrlForLog()} ${loadElapsedField()}")
                        notifyNavigationState()
                        notifyLoadFailure(reason)
                        if (parkedForKeepAlive) {
                            RaftAndroidWebViewKeepAlive.dropIfSame(this@RaftAndroidSandboxWebView, "load_error")
                        }
                    }
                }

                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    handler?.cancel()
                    if (destroyed || configuredWebView !== webView) return
                    speculativeLinkLoading = false
                    mainFrameLoadFailed = true
                    lastSuccessfulUrl = ""
                    currentUrl = error?.url.orEmpty().ifBlank { currentUrl }
                    currentIsLoading = false
                    resetNavigationChain()
                    val reason = "ssl_error_${error?.primaryError ?: -1}"
                    diagnosticsWarn("page_error reason=$reason url=${currentUrl.formatUrlForLog()} ${loadElapsedField()}")
                    notifyNavigationState()
                    notifyLoadFailure(reason)
                    if (parkedForKeepAlive) {
                        RaftAndroidWebViewKeepAlive.dropIfSame(this@RaftAndroidSandboxWebView, "ssl_error")
                    }
                }

                /** Main-frame HTTP 4xx/5xx: still renders the page as before, but marks load failed so keep-alive never caches it. */
                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (destroyed || configuredWebView !== webView || request?.isForMainFrame != true) return
                    val failedUrl = request.url?.toString().orEmpty()
                    if (isCurrentLoadHostCancelled(failedUrl) || isRecentlyRoutedBlocked(failedUrl)) return
                    val statusCode = errorResponse?.statusCode ?: -1
                    diagnosticsWarn(
                        "page_http_error status=$statusCode " +
                            "url=${failedUrl.formatUrlForLog()} ${loadElapsedField()}"
                    )
                    if (statusCode >= 400) {
                        mainFrameLoadFailed = true
                        lastSuccessfulUrl = ""
                        if (parkedForKeepAlive) {
                            RaftAndroidWebViewKeepAlive.dropIfSame(this@RaftAndroidSandboxWebView, "http_error")
                        }
                    }
                }
            }
        configuredWebView.setDownloadListener(
            DownloadListener { url, _, contentDisposition, mimeType, _ ->
                if (destroyed || configuredWebView !== webView) return@DownloadListener
                if (speculativeLinkLoading) {
                    speculativeLinkLoading = false
                    currentIsLoading = false
                    notifyNavigationState()
                }
                if (events.onDownloadRequested == null) {
                    notifyNavigationBlocked(url.orEmpty())
                    return@DownloadListener
                }
                startPageDownload(url.orEmpty(), contentDisposition, mimeType)
            }
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun updateAllowScripts(next: Boolean) {
        if (allowScripts == next) return
        allowScripts = next
        if (!next) pendingRuntimeJavaScript = ""
        webView?.settings?.javaScriptEnabled = next
    }

    private fun updateAllowNavigation(next: Boolean) {
        allowNavigation = next
    }

    private fun updateAllowNewWindows(next: Boolean) {
        if (allowNewWindows == next) return
        allowNewWindows = next
        webView?.settings?.setSupportMultipleWindows(next)
        if (!next) {
            newWindowTransport.cancelAll()
        }
    }

    internal fun handleLegacyNewWindowUrl(
        configuredWebView: WebView,
        rawUrl: String,
    ) {
        if (destroyed || parkedForKeepAlive || isolatedProfileUnavailable || configuredWebView !== webView) return
        val target = rawUrl.trim()
        if (target.isEmpty() || target.equals(ABOUT_BLANK_URL, ignoreCase = true)) return
        navChainCarriesRaftHost = false
        navChainReissueBudget = RAFT_HOST_REISSUE_BUDGET
        navChainStarter = if (hasCommittedDocument) RaftSignInStarter.Page else RaftSignInStarter.Other
        navChainHops.clear()
        if (tryCatchMainFrameNavigation(configuredWebView, target, stopLoading = false)) {
            return
        }
        if (shouldRouteMainFrameNavigation(target, isRedirect = false, isUserGesture = true) ||
            shouldBlockMainFrameNavigation(target, isUserGesture = true)
        ) {
            return
        }
        val decision =
            raftHostNavigationDecision(
                url = target,
                method = "GET",
                kind = RaftHostNavigationKind.Page,
                carriesHeader = false,
                host = raftHost,
                budgetLeft = navChainReissueBudget,
                committed = hasCommittedDocument,
            )
        recordNavChainHop(target)
        when (decision) {
            RaftHostNavigationDecision.Allow -> configuredWebView.loadUrl(target)
            RaftHostNavigationDecision.ReissueWithHeader -> {
                navChainReissueBudget = (navChainReissueBudget - 1).coerceAtLeast(0)
                navChainCarriesRaftHost = true
                postReissueLoad(configuredWebView, target, includeHeader = true)
            }
            RaftHostNavigationDecision.ReissueWithoutHeader -> {
                navChainCarriesRaftHost = false
                postReissueLoad(configuredWebView, target, includeHeader = false)
            }
        }
    }

    private fun updateDomStorageEnabled(next: Boolean) {
        if (domStorageEnabled == next) return
        domStorageEnabled = next
        webView?.settings?.domStorageEnabled = next
    }

    private fun applyZoomSettings() {
        val active = webView ?: return
        active.settings.setSupportZoom(true)
        active.settings.builtInZoomControls = useWideViewPort || allowPinchZoom
        active.settings.displayZoomControls = false
    }

    private fun updateAllowPinchZoom(next: Boolean) {
        if (allowPinchZoom == next) return
        allowPinchZoom = next
        applyZoomSettings()
    }

    private fun updateUseWideViewPort(next: Boolean) {
        if (useWideViewPort == next) return
        useWideViewPort = next
        val active = webView ?: return
        // task #188: gate the pair together; set the scale BEFORE the reload so the new document
        // starts fitted, and reload here (not from shared) so setting + document are one step.
        active.settings.useWideViewPort = next
        active.settings.loadWithOverviewMode = next
        applyZoomSettings()
        applyDesktopModeScale(active)
        if (!isolatedProfileUnavailable && active.url != null) {
            mainFrameLoadFailed = false
            active.reload()
        }
    }

    /**
     * task #188: `setInitialScale` fits a page that declares `width=1280`; the ratio comes from the
     * shared `raftFilePreviewDesktopScalePercent` (task #514) and the measured view width.
     */
    private fun applyDesktopModeScale(view: WebView?) {
        if (destroyed || view == null) return
        if (!useWideViewPort) {
            if (desktopModeScaleApplied) {
                view.setInitialScale(0)
                view.zoomOut()
                desktopModeScaleApplied = false
            }
            return
        }
        val viewWidthPx = view.width
        if (viewWidthPx <= 0) {
            if (!desktopModeScaleRetryScheduled) {
                desktopModeScaleRetryScheduled = true
                view.post {
                    desktopModeScaleRetryScheduled = false
                    applyDesktopModeScale(view)
                }
            }
            return
        }
        val dpr = resources.displayMetrics.density
        val percent = raftFilePreviewDesktopScalePercent(viewWidthPx, dpr) ?: return
        view.setInitialScale(percent)
        desktopModeScaleApplied = true
        Log.i(LOG_TAG, "desktop_mode_scale viewWidthPx=$viewWidthPx dpr=$dpr percent=$percent")
    }

    private fun updateCacheEnabled(next: Boolean) {
        if (cacheEnabled == next) return
        cacheEnabled = next
        webView?.settings?.cacheMode = if (next) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_NO_CACHE
    }

    /**
     * Android never isolated WebView storage per view (no non-persistent store API); the flag is
     * recorded only, as in the Kuikly render view.
     */
    private fun updateEphemeral(next: Boolean) {
        ephemeral = next
    }

    private fun updateInjectedJavaScript(next: String) {
        if (injectedJavaScript == next) return
        injectedJavaScript = next
        if (webView == null) return
        removeDocumentStartScript()
        if (src.isNotBlank()) installDocumentStartScript(src)
    }

    private fun updateHostEventsEnabled(next: Boolean) {
        if (hostEventsEnabled == next) return
        hostEventsEnabled = next
        if (src.isNotBlank()) {
            pendingRuntimeJavaScript = ""
            val pending = pendingRenderProcessRestore
            if (pending != null || webView == null) {
                if (pending != null) {
                    pendingRenderProcessRestore = pending.copy(restoreDocument = true)
                }
                return
            }
            loadAllowedSrc(src)
        }
    }

    private fun updateAllowedNavigationSchemes(next: Set<String>) {
        if (allowedNavigationSchemes == next) return
        allowedNavigationSchemes = next
        val activeUrl = currentUrl.ifBlank { src }
        if (activeUrl.isNotBlank() && !isNavigationSchemeAllowed(activeUrl)) {
            webView?.stopLoading()
            currentIsLoading = false
            notifyNavigationState()
            notifyNavigationBlocked(activeUrl)
        }
    }

    private fun executeNavigationCommand(next: String) {
        if (destroyed) return
        if (next.startsWith(RaftWebViewController.COMMAND_EVALUATE_JAVA_SCRIPT_PREFIX)) {
            evaluateRuntimeJavaScript(next.removePrefix(RaftWebViewController.COMMAND_EVALUATE_JAVA_SCRIPT_PREFIX))
            return
        }
        if (next.trim() == RaftWebViewController.COMMAND_PROBE_DOCUMENT_CONTENT) {
            probeDocumentContent()
            return
        }
        if (next.startsWith(RaftWebViewController.COMMAND_LOAD_URL_PREFIX)) {
            loadControllerUrl(next.removePrefix(RaftWebViewController.COMMAND_LOAD_URL_PREFIX))
            return
        }
        var forceNotify = true
        when (next.trim()) {
            RaftWebViewController.COMMAND_GO_BACK -> {
                val active = webView
                if (active?.canGoBack() == true) {
                    hostCancelledLoadGeneration = -1
                    active.goBack()
                }
            }
            RaftWebViewController.COMMAND_GO_FORWARD -> {
                val active = webView
                if (active?.canGoForward() == true) {
                    hostCancelledLoadGeneration = -1
                    active.goForward()
                }
            }
            RaftWebViewController.COMMAND_RELOAD -> {
                if (isolatedProfileUnavailable) {
                    currentIsLoading = false
                    notifyNavigationState(force = true)
                    notifyLoadFailure(RAFT_WEBVIEW_ISOLATED_PROFILE_UNAVAILABLE_REASON)
                    return
                }
                val active = webView
                if (active == null || pendingRenderProcessRestore != null) {
                    pendingRenderProcessRestore = pendingRenderProcessRestore?.copy(restoreDocument = true)
                    if (isForegroundWindowForRecovery(this)) flushPendingRenderProcessRestore()
                    return
                }
                hostCancelledLoadGeneration = -1
                lastSuccessfulUrl = ""
                lastLoadFailureReason = ""
                mainFrameLoadFailed = false
                currentProgress = 0
                currentIsLoading = true
                active.reload()
            }
            RaftWebViewController.COMMAND_STOP -> {
                webView?.stopLoading()
                speculativeLinkLoading = false
                currentIsLoading = false
                forceNotify = false
            }
            else -> return
        }
        notifyNavigationState(force = forceNotify)
    }

    private fun evaluateRuntimeJavaScript(script: String, allowQueue: Boolean = true) {
        if (destroyed || !allowScripts || script.isBlank()) return
        val activeWebView = webView ?: return
        if (pendingRenderProcessRestore != null) return
        if (script.length > RaftWebViewMaxRuntimeJavaScriptLength) {
            Log.w(LOG_TAG, "runtime_js result=dropped reason=oversized length=${script.length}")
            return
        }
        val activeUrl = currentUrl.ifBlank { lastSuccessfulUrl }
        val sourceOrigin = raftDocumentStartScriptOriginRule(src)
        val activeOrigin = raftDocumentStartScriptOriginRule(activeUrl)
        if (sourceOrigin == null || activeOrigin != sourceOrigin ||
            (mainFrameOrigin != null && activeOrigin != mainFrameOrigin) ||
            (!allowNavigation && !isAllowedMainDocumentUrl(activeUrl))
        ) {
            Log.i(
                LOG_TAG,
                "runtime_js result=dropped reason=document_not_ready sameOrigin=${sourceOrigin != null && activeOrigin == sourceOrigin}"
            )
            return
        }
        if (currentIsLoading || lastSuccessfulUrl.isBlank()) {
            if (allowQueue) {
                pendingRuntimeJavaScript = script
                Log.i(LOG_TAG, "runtime_js result=queued reason=document_loading")
            } else {
                Log.i(LOG_TAG, "runtime_js result=dropped reason=document_not_ready sameOrigin=true")
            }
            return
        }
        activeWebView.evaluateJavascript(script, null)
        Log.i(LOG_TAG, "runtime_js result=applied")
    }

    /**
     * The fixed, read-only [RaftWebViewContentProbeScript] (a '1'/'0' answer, nothing of the page's
     * data), so it is not fenced like runtime scripts. No scripts: no answer (null, fail open).
     */
    private fun probeDocumentContent() {
        if (destroyed) return
        val target = webView ?: return
        if (!allowScripts) {
            events.onContentProbe(null)
            return
        }
        target.evaluateJavascript(RaftWebViewContentProbeScript) { value ->
            if (!destroyed && target === webView) events.onContentProbe(raftWebViewDecodeContentProbe(value))
        }
    }

    private fun flushPendingRuntimeJavaScript() {
        val script = pendingRuntimeJavaScript.takeIf { it.isNotBlank() } ?: return
        pendingRuntimeJavaScript = ""
        evaluateRuntimeJavaScript(script, allowQueue = false)
    }

    private fun updateSrc(next: String) {
        val normalized = next.trim()
        if (src == normalized && (normalized.isBlank() || loadedSrc == normalized || isolatedProfileUnavailable)) return
        pendingRuntimeJavaScript = ""
        src = normalized
        loadedSrc = ""
        lastSuccessfulUrl = ""
        lastLoadFailureReason = ""
        mainFrameLoadFailed = false
        committedDocumentUrl = ""
        hasCommittedDocument = false
        lastHostCancelledUrl = ""
        lastHostCancelledAtMs = 0L
        hostCancelledLoadGeneration = -1
        lastRoutedBlockedUrl = ""
        lastRoutedBlockedAtMs = 0L
        resetNavigationChain()
        allowedUri = normalized.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val pending = pendingRenderProcessRestore
        if (isolatedProfileUnavailable) {
            if (pending != null) {
                pendingRenderProcessRestore =
                    pending.copy(restoreDocument = false, restoreUrl = "", notifyProcessGone = false, controllerLoadUrl = null)
            }
            currentUrl = normalized
            currentTitle = ""
            currentProgress = 0
            currentIsLoading = false
            notifyNavigationState(force = true)
            if (normalized.isNotBlank()) notifyLoadFailure(RAFT_WEBVIEW_ISOLATED_PROFILE_UNAVAILABLE_REASON)
            return
        }
        if (normalized.isBlank()) {
            currentUrl = ""
            currentTitle = ""
            currentProgress = 0
            currentIsLoading = false
            notifyNavigationState()
            if (pending != null || webView == null) {
                if (pending != null) {
                    pendingRenderProcessRestore =
                        pending.copy(restoreDocument = false, restoreUrl = "", notifyProcessGone = false, controllerLoadUrl = null)
                }
                return
            }
            webView?.loadUrl(ABOUT_BLANK_URL)
            return
        }
        currentUrl = normalized
        currentTitle = ""
        currentProgress = 0
        currentIsLoading = false
        if (!isNavigationSchemeAllowed(normalized) || !raftMainFrameOriginAllows(mainFrameOrigin, normalized)) {
            if (pending != null) {
                pendingRenderProcessRestore =
                    pending.copy(restoreDocument = false, restoreUrl = "", notifyProcessGone = false, controllerLoadUrl = null)
            }
            notifyNavigationState()
            notifyNavigationBlocked(normalized)
            return
        }
        if (pending != null || webView == null) {
            if (pending != null) {
                pendingRenderProcessRestore =
                    PendingRenderProcessRestore(
                        restoreDocument = true,
                        restoreUrl = normalized,
                        reason = pending.reason,
                        notifyProcessGone = false,
                        controllerLoadUrl = null,
                    )
                if (isForegroundWindowForRecovery(this)) flushPendingRenderProcessRestore()
            }
            return
        }
        loadAllowedSrc(normalized)
    }

    /**
     * Loads [normalized] as this view's src. [documentUrl] is the page actually loaded: the src
     * itself, or after a renderer rebuild the page that was shown ([raftWebViewRendererGoneRestoreUrl]).
     * The src stays [loadedSrc], so the host's unchanged config does not load it over that page.
     */
    private fun loadAllowedSrc(normalized: String, documentUrl: String = normalized) {
        val activeWebView = webView ?: return
        if (isolatedProfileUnavailable) {
            currentIsLoading = false
            notifyNavigationState(force = true)
            notifyLoadFailure(RAFT_WEBVIEW_ISOLATED_PROFILE_UNAVAILABLE_REASON)
            return
        }
        loadedSrc = normalized
        mainFrameLoadFailed = false
        currentUrl = documentUrl
        currentIsLoading = true
        notifyNavigationState()
        val documentStartInstalled = installDocumentStartScript(normalized)
        installHostMessageListener(normalized)
        // The page's downloads and `navigator.share`: for a host that takes downloads, or any origin-locked page.
        if (events.onDownloadRequested != null || mainFrameOrigin != null) installDownloadSupport(normalized) else removeDownloadSupport()
        if (shouldBootstrapInjectedJavaScript(documentStartInstalled)) {
            val bootstrapDocumentUrl = raftInjectedJavaScriptBootstrapBaseUrl(documentUrl) ?: documentUrl
            bootstrapNavigationFence.begin(targetUrl = documentUrl)
            diagnosticsInfo("load_src bootstrap=true url=${documentUrl.formatUrlForLog()}")
            activeWebView.loadDataWithBaseURL(
                bootstrapDocumentUrl,
                injectedJavaScriptBootstrapHtml(documentUrl),
                "text/html",
                "UTF-8",
                bootstrapDocumentUrl
            )
        } else {
            bootstrapNavigationFence.reset()
            diagnosticsInfo("load_src bootstrap=false url=${documentUrl.formatUrlForLog()}")
            startHostNavigationChain(documentUrl)
            loadUrlWithRaftHost(documentUrl, includeHeader = true)
        }
    }

    /** Non-queued [RaftWebViewController.loadUrl]: loads [rawUrl] once with optional `Raft-Host`, without mutating [src]. */
    private fun loadControllerUrl(rawUrl: String) {
        if (destroyed) return
        if (isolatedProfileUnavailable) {
            currentIsLoading = false
            notifyNavigationState(force = true)
            notifyLoadFailure(RAFT_WEBVIEW_ISOLATED_PROFILE_UNAVAILABLE_REASON)
            return
        }
        val target = rawUrl.trim()
        if (target.isEmpty()) return
        if (wouldBlockMainFrameNavigation(target)) {
            handleBlockedMainFrameNavigation(target)
            return
        }
        val pending = pendingRenderProcessRestore
        if (pending != null || webView == null) {
            if (pending != null) {
                pendingRenderProcessRestore =
                    PendingRenderProcessRestore(
                        restoreDocument = false,
                        restoreUrl = "",
                        reason = pending.reason,
                        notifyProcessGone = false,
                        controllerLoadUrl = target,
                    )
                if (isForegroundWindowForRecovery(this)) flushPendingRenderProcessRestore()
            }
            return
        }
        hostCancelledLoadGeneration = -1
        lastSuccessfulUrl = ""
        lastLoadFailureReason = ""
        mainFrameLoadFailed = false
        currentUrl = target
        currentProgress = 0
        currentIsLoading = true
        notifyNavigationState(force = true)
        startHostNavigationChain(target)
        loadUrlWithRaftHost(target, includeHeader = true)
    }

    private fun startHostNavigationChain(targetUrl: String) {
        navChainCarriesRaftHost = false
        navChainReissueBudget = RAFT_HOST_REISSUE_BUDGET
        navChainStarter = if (hasCommittedDocument) RaftSignInStarter.Page else RaftSignInStarter.Launch
        navChainHops.clear()
        recordNavChainHop(targetUrl)
    }

    private fun resetNavigationChain() {
        navChainCarriesRaftHost = false
        navChainReissueBudget = RAFT_HOST_REISSUE_BUDGET
        navChainStarter = RaftSignInStarter.Other
        navChainHops.clear()
    }

    private fun markDocumentCommitted(url: String) {
        committedDocumentUrl = url
        hasCommittedDocument = true
        navChainCarriesRaftHost = false
        navChainReissueBudget = RAFT_HOST_REISSUE_BUDGET
        navChainStarter = RaftSignInStarter.Page
        navChainHops.clear()
    }

    private fun recordNavChainHop(url: String) {
        if (url.isNotEmpty() && navChainHops.size < MAX_NAV_CHAIN_HOPS) {
            navChainHops += url
        }
    }

    private fun loadUrlWithRaftHost(targetUrl: String, includeHeader: Boolean) {
        val activeWebView = webView ?: return
        val headerValue = if (includeHeader) raftHostHeaderValueFor(targetUrl, raftHost) else null
        navChainCarriesRaftHost = headerValue != null
        if (headerValue != null) {
            activeWebView.loadUrl(targetUrl, mapOf(RAFT_HOST_HEADER to headerValue))
        } else {
            activeWebView.loadUrl(targetUrl)
        }
    }

    private fun postReissueLoad(targetView: WebView?, targetUrl: String, includeHeader: Boolean) {
        val view = targetView ?: webView ?: return
        shareMainHandler.post {
            if (!destroyed && !isolatedProfileUnavailable && view === webView) {
                loadUrlWithRaftHost(targetUrl, includeHeader = includeHeader)
            }
        }
    }

    private fun tryCatchMainFrameNavigation(
        view: WebView?,
        target: String,
        stopLoading: Boolean,
    ): Boolean {
        val catchAsk =
            RaftWebViewMainFrameCatchAsk(
                url = target,
                starter = navChainStarter,
                page = committedDocumentUrl,
                committed = hasCommittedDocument,
                hops = navChainHops.toList(),
            )
        if (events.catchesMainFrameNavigation?.invoke(catchAsk) != true) return false
        val wasSpeculative = speculativeLinkLoading
        speculativeLinkLoading = false
        if (stopLoading) view?.stopLoading()
        lastHostCancelledUrl = target
        lastHostCancelledAtMs = System.currentTimeMillis()
        if (currentIsLoading && !wasSpeculative && loadGeneration > 0) {
            hostCancelledLoadGeneration = loadGeneration
            if (committedDocumentUrl.isNotEmpty()) {
                currentUrl = committedDocumentUrl
            }
        }
        currentIsLoading = false
        notifyNavigationState()
        return true
    }

    /**
     * Main-frame `shouldOverrideUrlLoading` order (#356 §3/§5):
     * 1. [RaftWebViewEvents.catchesMainFrameNavigation] (e.g. Raft OAuth authorize catch);
     * 2. existing link routing and navigation fence;
     * 3. [raftHostNavigationDecision] (re-issue pre-commit same-origin GET redirects with
     *    `Raft-Host`, or strip on cross-origin GET redirects, bounded by [RAFT_HOST_REISSUE_BUDGET]).
     */
    private fun handleMainFrameShouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest,
        target: String?,
    ): Boolean {
        val isRedirect = request.isRedirect
        val method = request.method?.takeIf { it.isNotBlank() } ?: "GET"
        if (!isRedirect) {
            navChainCarriesRaftHost = false
            navChainReissueBudget = RAFT_HOST_REISSUE_BUDGET
            navChainStarter = if (hasCommittedDocument) RaftSignInStarter.Page else RaftSignInStarter.Other
            navChainHops.clear()
        }
        if (target != null && tryCatchMainFrameNavigation(view, target, stopLoading = false)) {
            return true
        }
        val isUserGesture = request.hasGesture()
        if (shouldRouteMainFrameNavigation(target, isRedirect = isRedirect, isUserGesture = isUserGesture) ||
            shouldBlockMainFrameNavigation(target, isUserGesture = isUserGesture)
        ) {
            return true
        }
        if (target == null) return false
        val requestHasHeader =
            request.requestHeaders?.entries?.any { (name, value) ->
                name.equals(RAFT_HOST_HEADER, ignoreCase = true) && value.isNotBlank()
            } == true
        val onScope = raftHostHeaderValueFor(target, raftHost) != null
        val carriesHeader =
            if (onScope) {
                // Android loadUrl headers do not survive redirects; a same-origin redirect lacks the
                // header unless the request explicitly reports it.
                requestHasHeader
            } else {
                // Off-origin redirect from a header-bearing chain: conservatively strip by re-issuing.
                requestHasHeader || (isRedirect && navChainCarriesRaftHost)
            }
        val kind = if (isRedirect) RaftHostNavigationKind.Redirect else RaftHostNavigationKind.Page
        val decision =
            raftHostNavigationDecision(
                url = target,
                method = method,
                kind = kind,
                carriesHeader = carriesHeader,
                host = raftHost,
                budgetLeft = navChainReissueBudget,
                committed = hasCommittedDocument,
            )
        recordNavChainHop(target)
        return when (decision) {
            RaftHostNavigationDecision.Allow -> {
                if (!isRedirect) navChainCarriesRaftHost = requestHasHeader
                if (
                    isUserGesture &&
                    !isRedirect &&
                    !currentIsLoading &&
                    (target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)) &&
                    !isSameDocumentIgnoringFragment(currentUrl, target) &&
                    !isRecentlyHostCancelled(target)
                ) {
                    speculativeLinkLoading = true
                    currentProgress = 0
                    currentIsLoading = true
                    notifyNavigationState()
                }
                false
            }
            RaftHostNavigationDecision.ReissueWithHeader -> {
                navChainReissueBudget = (navChainReissueBudget - 1).coerceAtLeast(0)
                navChainCarriesRaftHost = true
                postReissueLoad(view, target, includeHeader = true)
                true
            }
            RaftHostNavigationDecision.ReissueWithoutHeader -> {
                navChainCarriesRaftHost = false
                postReissueLoad(view, target, includeHeader = false)
                true
            }
        }
    }

    /**
     * A new document the host routes as a link ([RaftWebViewEvents.routesMainFrameNavigation]): the
     * page asked for it (`window.open` loads in place here, multiple windows being off), so it is
     * handed over as a blocked navigation and the page stays as it is.
     */
    private fun isSameDocumentIgnoringFragment(left: String, right: String): Boolean {
        val normalizedLeft = left.trim().substringBefore('#')
        val normalizedRight = right.trim().substringBefore('#')
        return normalizedLeft.isNotEmpty() && normalizedLeft == normalizedRight
    }

    private fun isRecentlyHostCancelled(url: String): Boolean =
        url.isNotEmpty() &&
            url == lastHostCancelledUrl &&
            (System.currentTimeMillis() - lastHostCancelledAtMs) in 0L..1_500L

    private fun isCurrentLoadHostCancelled(url: String): Boolean =
        isRecentlyHostCancelled(url) ||
            (hostCancelledLoadGeneration > 0 && hostCancelledLoadGeneration == loadGeneration)

    private fun isRecentlyRoutedBlocked(url: String): Boolean =
        url.isNotEmpty() &&
            url == lastRoutedBlockedUrl &&
            (System.currentTimeMillis() - lastRoutedBlockedAtMs) in 0L..1_500L

    private fun invokeRoutesMainFrameNavigation(
        target: String,
        isRedirect: Boolean,
        isUserGesture: Boolean,
    ): Boolean {
        val withContext = events.routesMainFrameNavigationWithContext
        if (withContext != null) {
            return withContext(
                RaftWebViewMainFrameRouteAsk(
                    url = target,
                    isRedirect = isRedirect,
                    isUserGesture = isUserGesture,
                    committedUrl = committedDocumentUrl,
                ),
            )
        }
        return events.routesMainFrameNavigation?.invoke(target) == true
    }

    private fun shouldRouteMainFrameNavigation(
        url: String?,
        isRedirect: Boolean = false,
        isUserGesture: Boolean = false,
    ): Boolean {
        val target = url ?: return false
        if (wouldBlockMainFrameNavigation(target)) return false
        if (!invokeRoutesMainFrameNavigation(target, isRedirect = isRedirect, isUserGesture = isUserGesture)) return false
        diagnosticsInfo("navigation_routed_as_link url=${target.formatUrlForLog()}")
        lastRoutedBlockedUrl = target
        lastRoutedBlockedAtMs = System.currentTimeMillis()
        handleBlockedMainFrameNavigation(target, isUserGesture = isUserGesture)
        return true
    }

    private fun shouldBlockMainFrameNavigation(url: String?, isUserGesture: Boolean = false): Boolean {
        if (!wouldBlockMainFrameNavigation(url)) return false
        handleBlockedMainFrameNavigation(url, isUserGesture = isUserGesture)
        return true
    }

    private fun handleBlockedMainFrameNavigation(url: String?, isUserGesture: Boolean = false) {
        if (destroyed) return
        currentIsLoading = false
        notifyNavigationState()
        url?.let { notifyNavigationBlocked(it, isUserGesture = isUserGesture) }
    }

    private fun shouldSilentlyBlockSubframeNavigation(url: String?): Boolean {
        val target = url ?: return true
        return !isNavigationSchemeAllowed(target)
    }

    /**
     * Whether a WebView rebuilt after its renderer was gone may load [candidate] instead of its src
     * ([raftWebViewRendererGoneRestoreUrl]): the navigation fence lets it load as a main frame, and it
     * is on the src's origin (the document-start script is scoped to that origin).
     */
    internal fun rendererGoneRestoreAllows(candidate: String): Boolean =
        !wouldBlockMainFrameNavigation(candidate) &&
            raftDocumentStartScriptOriginRule(candidate) == raftDocumentStartScriptOriginRule(src)

    private fun wouldBlockMainFrameNavigation(url: String?): Boolean {
        val target = url ?: return true
        if (src.isBlank() && target == ABOUT_BLANK_URL) return false
        if (!isNavigationSchemeAllowed(target)) return true
        if (!raftMainFrameOriginAllows(mainFrameOrigin, target)) return true
        if (allowNavigation) return false
        return !isAllowedMainDocumentUrl(target)
    }

    private fun isNavigationSchemeAllowed(url: String): Boolean {
        if (allowedNavigationSchemes.isEmpty()) return true
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
        return scheme != null && scheme in allowedNavigationSchemes
    }

    private fun isAllowedMainDocumentUrl(url: String): Boolean {
        if (src.isBlank() && url == ABOUT_BLANK_URL) return true
        if (url == loadedSrc) return true
        return raftIsExactMainDocumentUrl(allowedUri?.toString(), url)
    }

    private fun notifyLoadFailure(reason: String) {
        if (destroyed) return
        val failureKey = "$currentUrl|$reason"
        if (failureKey == lastLoadFailureReason) return
        lastLoadFailureReason = failureKey
        events.onLoadFailure(reason)
    }

    private fun notifyLoadSuccess(url: String, phase: String, beforeCallback: (() -> Unit)? = null) {
        if (destroyed) return
        if (lastSuccessfulUrl == url) {
            beforeCallback?.invoke()
            return
        }
        lastSuccessfulUrl = url
        lastLoadFailureReason = ""
        beforeCallback?.invoke()
        diagnosticsInfo("page_success phase=$phase url=${url.formatUrlForLog()} ${loadElapsedField()}")
        events.onLoadSuccess()
    }

    private fun String.formatUrlForLog(): String =
        if (diagnosticsSurface == SETTINGS_WEB_PAGE_DIAGNOSTICS_NAME) {
            raftOwnWebUrlForLog(this)
        } else {
            raftRedactedUrlForLog()
        }

    /**
     * Load-path lines. For a keep-alive WebView (the Artifact viewer) they also go to the persistent
     * diagnostics ring (raftLogInfo; feedback archives), not only logcat: before, nothing of the
     * viewer's WebView reached a feedback log. URLs are always [raftRedactedUrlForLog] (no query).
     */
    private fun diagnosticsInfo(message: String) {
        if (keepAliveKey != null) raftLogInfo(RAFT_WEBVIEW_DIAGNOSTICS_TAG, message) else Log.i(LOG_TAG, message)
    }

    private fun diagnosticsWarn(message: String, always: Boolean = false) {
        if (always || keepAliveKey != null) raftLogWarn(RAFT_WEBVIEW_DIAGNOSTICS_TAG, message) else Log.w(LOG_TAG, message)
    }

    private fun loadElapsedField(): String =
        "elapsedMs=${if (loadStartedAtMs > 0L) System.currentTimeMillis() - loadStartedAtMs else -1L}"

    private fun logProgressMilestone(progress: Int) {
        val milestone = raftWebViewProgressMilestone(progress, loggedProgressMilestone) ?: return
        loggedProgressMilestone = milestone
        if (keepAliveKey != null) diagnosticsInfo("page_progress percent=$milestone ${loadElapsedField()}")
    }

    private fun notifyNavigationBlocked(url: String, isUserGesture: Boolean = false) {
        if (destroyed) return
        events.onNavigationBlocked(url)
        events.onNavigationBlockedWithGesture?.invoke(url, isUserGesture)
    }

    private fun notifyUrlChange(url: String) {
        if (destroyed) return
        events.onUrlChange(url)
    }

    private fun notifyNavigationState(force: Boolean = false) {
        navigationStateRelay.notify(
            force = force,
            isActive = { !destroyed },
            readState = {
                val activeWebView = webView
                RaftWebViewNavigationState(
                    url = currentUrl,
                    title = currentTitle,
                    canGoBack = activeWebView?.canGoBack() == true,
                    canGoForward = activeWebView?.canGoForward() == true,
                    isLoading = currentIsLoading,
                    progress = currentProgress.coerceIn(0, 100),
                    loadGeneration = loadGeneration,
                )
            },
            deliver = { state -> events.onNavigationStateChange(state) },
        )
    }

    private fun evaluateInjectedJavaScript(pageUrl: String? = null) {
        if (destroyed || !allowScripts) return
        val activeWebView = webView ?: return
        val script = injectedJavaScript.takeIf { it.isNotBlank() } ?: return
        val activeUrl = pageUrl?.takeIf { it.isNotBlank() } ?: activeWebView.url.orEmpty()
        val sourceOrigin = raftDocumentStartScriptOriginRule(src)
        val activeOrigin = raftDocumentStartScriptOriginRule(activeUrl)
        if (sourceOrigin == null || activeOrigin != sourceOrigin) return
        val viewUrl = activeWebView.url?.takeIf { it.isNotBlank() }
        if (viewUrl != null && raftDocumentStartScriptOriginRule(viewUrl) != sourceOrigin) return
        if (mainFrameOrigin != null && activeOrigin != mainFrameOrigin) return
        if (!allowNavigation && !isAllowedMainDocumentUrl(activeUrl)) return
        activeWebView.evaluateJavascript(script, null)
    }

    private fun logConsoleMessage(consoleMessage: ConsoleMessage) {
        val source = consoleMessage.sourceId().orEmpty().formatUrlForLog()
        val message = consoleMessage.message().orEmpty().raftSanitizedLogValue(LOG_MESSAGE_LIMIT)
        val line = consoleMessage.lineNumber()
        when (consoleMessage.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> {
                Log.e(LOG_TAG, "console_error line=$line source=$source message=$message")
                // The viewer's first few page errors also go to the persistent ring (short, no query).
                if (keepAliveKey != null && persistedConsoleErrors < PERSISTED_CONSOLE_ERROR_LIMIT) {
                    persistedConsoleErrors += 1
                    raftLogWarn(
                        RAFT_WEBVIEW_DIAGNOSTICS_TAG,
                        "console_error line=$line source=$source message=${message.take(PERSISTED_CONSOLE_MESSAGE_CHARS)}",
                    )
                }
            }
            ConsoleMessage.MessageLevel.WARNING -> Log.w(LOG_TAG, "console_warning line=$line source=$source message=$message")
            else ->
                Log.i(
                    LOG_TAG,
                    "console_${consoleMessage.messageLevel().name.lowercase()} line=$line source=$source message=$message"
                )
        }
    }

    private fun shouldBootstrapInjectedJavaScript(documentStartInstalled: Boolean): Boolean =
        allowScripts && domStorageEnabled && injectedJavaScript.isNotBlank() && !documentStartInstalled

    private fun installHostMessageListener(targetUrl: String) {
        removeHostMessageListener()
        val activeWebView = webView ?: return
        if (!hostEventsEnabled ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            return
        }
        val originRule = raftDocumentStartScriptOriginRule(targetUrl) ?: return
        runCatching {
            val listener =
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy
                    ) {
                        if (!isMainFrame || sourceOrigin.toString() != originRule) return
                        // Same-origin history routes must not keep access to the native bridge.
                        val activeUrl = view.url.orEmpty()
                        if (!allowNavigation && !isAllowedMainDocumentUrl(activeUrl)) return
                        val raw = message.data ?: return
                        if (!destroyed && view === webView) events.dispatchHostMessage(raw)
                    }
                }
            hostMessageListener = listener
            WebViewCompat.addWebMessageListener(activeWebView, RaftWebViewHostPortName, setOf(originRule), listener)
            Log.i(LOG_TAG, "host_message_listener installed origin=${originRule.raftRedactedUrlForLog()}")
        }.onFailure { Log.w(LOG_TAG, "host_message_listener unavailable", it) }
    }

    /**
     * A file the page saves, kept in the app as a browser keeps it (#proj-mobile:#202):
     * - `blob:` / `data:` (the web's Download saves this way): read in the page right away (the web
     *   revokes the address soon after the click) and posted back in chunks to [RaftWebViewDownloadPortName],
     *   which only the page's own origin, main frame, can reach.
     * - http(s) on the page's own origin: handed to the host by address with the page's `download`
     *   name; the host downloads it with its session.
     * - anything else (another origin): the blocked-navigation route, as before.
     */
    private fun startPageDownload(url: String, contentDisposition: String?, mimeType: String?) {
        val activeWebView = webView ?: return
        val report = events.onDownloadRequested ?: return
        val pageOrigin = raftDocumentStartScriptOriginRule(currentUrl.ifBlank { loadedSrc })
        val trustedPage = pageOrigin != null && (mainFrameOrigin == null || pageOrigin == mainFrameOrigin)
        val lower = url.lowercase()
        when {
            lower.startsWith("blob:") || lower.startsWith("data:") -> {
                if (!trustedPage || downloadMessageListener == null) {
                    diagnosticsWarn("page_download result=failed reason=no_port")
                    report(RaftWebViewDownload.Failed("no_port"))
                    return
                }
                val token = "d" + kotlin.random.Random.nextLong().toULong().toString(16)
                if (pendingBlobDownloads.size >= 4) pendingBlobDownloads.remove(pendingBlobDownloads.keys.first())
                pendingBlobDownloads[token] = RaftWebViewBlobDownloadAssembler(token, url)
                diagnosticsInfo("page_download kind=blob")
                activeWebView.evaluateJavascript(raftWebViewBlobReadScript(url, token), null)
            }
            (lower.startsWith("https:") || lower.startsWith("http:")) &&
                trustedPage && raftDocumentStartScriptOriginRule(url) == pageOrigin -> {
                diagnosticsInfo("page_download kind=same_origin")
                activeWebView.evaluateJavascript(
                    "(function(){var n=window.__raftDownloadNames;return n&&n[${build.raft.app.compat.json.JSONObject.quote(url)}]||'';})()"
                ) { value ->
                    if (destroyed) return@evaluateJavascript
                    val pageName =
                        value?.takeIf { it.length >= 2 && it.startsWith("\"") }
                            ?.let { runCatching { AndroidJSONObject("{\"v\":$it}").optString("v") }.getOrNull() }
                            ?.takeIf { it.isNotBlank() }
                    events.onDownloadRequested?.invoke(
                        RaftWebViewDownload.Url(url, pageName, contentDisposition, mimeType)
                    )
                }
            }
            else -> notifyNavigationBlocked(url)
        }
    }

    /**
     * A message of the page's `navigator.share` ([raftWebViewShareShimScript]). The UI thread only
     * hands it on: it is parsed, decoded and written to the share's staging folder on
     * [RaftWebViewShareIo], in the order posted, and what follows (the next chunk asked for, the share
     * sheet, the Promise settled) comes back here. Nothing thrown leaves this function.
     */
    private fun acceptShareMessage(session: RaftAndroidWebViewShareSession, raw: String) {
        // A session whose port is gone takes nothing more: nothing would abort what it started.
        if (destroyed || shareSession !== session) return
        scheduleShareExpiry(session)
        runShareIo(session) { session.accept(raw) }
    }

    /**
     * Runs [work] on the share thread and its signals back on the main thread. With [rearmExpiry],
     * the expiry check is set again while the session still has a share being received.
     */
    private fun runShareIo(
        session: RaftAndroidWebViewShareSession,
        rearmExpiry: Boolean = false,
        work: () -> List<RaftAndroidStagedShareSignal>?
    ) {
        runCatching {
            RaftWebViewShareIo.execute {
                val signals = runCatching(work).getOrElse { runCatching { session.abortAll("internal") }.getOrDefault(emptyList()) }
                if (!signals.isNullOrEmpty()) shareMainHandler.post { deliverShareSignals(session, signals) }
                if (rearmExpiry && runCatching { session.hasPending() }.getOrDefault(false)) {
                    shareMainHandler.post {
                        if (!destroyed && shareSession === session && shareExpiryCheck == null) {
                            scheduleShareExpiry(session, RaftWebViewShareExpiryRecheckMs)
                        }
                    }
                }
            }
        }.onFailure { Log.w(LOG_TAG, "page_share io unavailable", it) }
    }

    private fun deliverShareSignals(session: RaftAndroidWebViewShareSession, signals: List<RaftAndroidStagedShareSignal>) {
        val current = !destroyed && shareSession === session
        signals.forEach { staged ->
            runCatching {
                when (val signal = staged.signal) {
                    is RaftWebViewShareSignal.Ack ->
                        if (current) webView?.evaluateJavascript(raftWebViewShareAckScript(signal.id), null)
                    is RaftWebViewShareSignal.Failed -> {
                        diagnosticsWarn("page_share result=failed reason=${signal.reason}")
                        settleShare(signal.id, signal.errorName)
                    }
                    is RaftWebViewShareSignal.Ready ->
                        if (current) {
                            presentShare(signal.request, staged.folder)
                        } else {
                            // The page went away while its last bytes were written.
                            discardShareFolder(staged.folder)
                            settleShare(signal.request.id, "AbortError")
                        }
                }
                Unit
            }.onFailure { Log.w(LOG_TAG, "page_share deliver failed", it) }
        }
    }

    /**
     * Gives up shares whose page has gone silent: every share message pushes this check
     * [RaftWebViewShareIdleTimeoutMs] ahead. A share's silence is counted from when its last message
     * was handled on the share thread, which may be later than when it was posted, so the check comes
     * back every [RaftWebViewShareExpiryRecheckMs] until no share is left being received.
     */
    private fun scheduleShareExpiry(
        session: RaftAndroidWebViewShareSession,
        delayMs: Long = RaftWebViewShareIdleTimeoutMs + 1_000L
    ) {
        shareExpiryCheck?.let { shareMainHandler.removeCallbacks(it) }
        val check =
            Runnable {
                shareExpiryCheck = null
                if (!destroyed && shareSession === session) runShareIo(session, rearmExpiry = true) { session.expire() }
            }
        shareExpiryCheck = check
        shareMainHandler.postDelayed(check, delayMs)
    }

    private fun discardShareFolder(folder: java.io.File?) {
        if (folder == null) return
        runCatching { RaftWebViewShareIo.execute { runCatching { folder.deleteRecursively() } } }
    }

    /**
     * Opens the system share sheet for a received share. Its files are already in [folder], the
     * share's own `<uuid>` entry of the app's share staging folder (served by the app's
     * FileProvider, swept by age like an attachment share's). The page's Promise resolves once the
     * sheet is shown, or is rejected when it cannot be.
     */
    private fun presentShare(request: RaftWebViewShareRequest, folder: java.io.File?) {
        val context = webView?.context ?: this.context
        val result =
            runCatching {
                val intent =
                    if (request.files.isEmpty()) {
                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, raftWebViewShareText(request.title, request.text, request.url))
                            if (request.title.isNotBlank()) putExtra(android.content.Intent.EXTRA_SUBJECT, request.title)
                        }
                    } else {
                        checkNotNull(folder) { "share folder" }
                        val uris =
                            ArrayList(
                                request.files.map { file ->
                                    val target = java.io.File(folder, file.name)
                                    check(target.isFile && target.parentFile == folder && target.length() == file.size) { "share file" }
                                    androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
                                }
                            )
                        // Receivers match on a bare `type/subtype`: no parameters, a generic type read from the name.
                        val type =
                            raftWebViewShareIntentType(
                                request.files.map { file ->
                                    build.raft.app.downloads.RaftShareFileName.resolveMimeType(file.name, file.mimeType) { extension ->
                                        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                                    }
                                }
                            )
                        (
                            if (uris.size == 1) {
                                android.content.Intent(android.content.Intent.ACTION_SEND).putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
                            } else {
                                android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE)
                                    .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                            }
                        ).apply {
                            this.type = type
                            if (request.title.isNotBlank()) putExtra(android.content.Intent.EXTRA_SUBJECT, request.title)
                            raftWebViewShareText("", request.text, request.url).takeIf { it.isNotBlank() }
                                ?.let { putExtra(android.content.Intent.EXTRA_TEXT, it) }
                            clipData =
                                android.content.ClipData.newRawUri(request.files.first().name, uris[0]).also { clip ->
                                    uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
                                }
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                val chooser = android.content.Intent.createChooser(intent, request.title.takeIf { it.isNotBlank() })
                if (context !is android.app.Activity) chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            }
        if (result.isSuccess) {
            diagnosticsInfo("page_share result=shown files=${request.files.size}")
            settleShare(request.id, null)
        } else {
            diagnosticsWarn("page_share result=failed type=${result.exceptionOrNull()?.javaClass?.simpleName}")
            discardShareFolder(folder)
            settleShare(request.id, "AbortError")
        }
    }

    private fun settleShare(id: String, errorName: String?) {
        if (destroyed) return
        webView?.evaluateJavascript(raftWebViewShareSettleScript(id, errorName), null)
    }

    /** A chunk of a `blob:` / `data:` save the page is reading back ([startPageDownload]). */
    private fun handleDownloadMessage(raw: String) {
        val token = runCatching { AndroidJSONObject(raw).optString("t") }.getOrNull() ?: return
        val assembler = pendingBlobDownloads[token] ?: return
        val result = assembler.accept(raw) ?: return
        pendingBlobDownloads.remove(token)
        when (result) {
            is RaftWebViewDownload.Failed -> diagnosticsWarn("page_download result=failed reason=${result.reason}")
            is RaftWebViewDownload.Bytes -> diagnosticsInfo("page_download result=read bytes=${result.bytes.size}")
            is RaftWebViewDownload.Url -> Unit
        }
        events.onDownloadRequested?.invoke(result)
    }

    private fun installDownloadSupport(targetUrl: String) {
        removeDownloadSupport()
        val activeWebView = webView ?: return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            return
        }
        val originRule = raftDocumentStartScriptOriginRule(targetUrl) ?: return
        if (mainFrameOrigin != null && originRule != mainFrameOrigin) return
        runCatching {
            val session = RaftAndroidWebViewShareSession(java.io.File(activeWebView.context.cacheDir, RaftWebViewShareStagingDirName))
            val listener =
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy
                    ) {
                        if (destroyed || view !== webView || !isMainFrame || sourceOrigin.toString() != originRule) return
                        val raw = message.data ?: return
                        // A share message goes to the share thread whole; this (UI) thread neither parses nor decodes it.
                        if (raftWebViewIsShareMessage(raw)) {
                            acceptShareMessage(session, raw)
                            return
                        }
                        handleDownloadMessage(raw)
                    }
                }
            WebViewCompat.addWebMessageListener(activeWebView, RaftWebViewDownloadPortName, setOf(originRule), listener)
            downloadMessageListener = listener
            shareSession = session
            downloadNameScriptHandler =
                WebViewCompat.addDocumentStartJavaScript(
                    activeWebView,
                    RaftWebViewDownloadNameHookScript + "\n" + raftWebViewShareShimScript(),
                    setOf(originRule)
                )
        }.onFailure { Log.w(LOG_TAG, "download_support unavailable", it) }
    }

    private fun removeDownloadSupport() {
        if (downloadMessageListener != null) {
            webView?.let { active ->
                runCatching { WebViewCompat.removeWebMessageListener(active, RaftWebViewDownloadPortName) }
            }
        }
        downloadMessageListener = null
        runCatching { downloadNameScriptHandler?.remove() }
        downloadNameScriptHandler = null
        pendingBlobDownloads.clear()
        // Shares still being received end here: their partial files go, their Promises are rejected.
        shareExpiryCheck?.let { shareMainHandler.removeCallbacks(it) }
        shareExpiryCheck = null
        shareSession?.let { session ->
            shareSession = null
            runShareIo(session) { session.close() }
        }
    }

    private fun removeHostMessageListener() {
        webView?.let { active ->
            runCatching { WebViewCompat.removeWebMessageListener(active, RaftWebViewHostPortName) }
        }
        hostMessageListener = null
    }

    private fun installDocumentStartScript(targetUrl: String): Boolean {
        removeDocumentStartScript()
        val activeWebView = webView ?: return false
        installFirstPaintScript(targetUrl)
        val authScript = injectedJavaScript.takeIf { allowScripts && domStorageEnabled && it.isNotBlank() } ?: ""
        val bridge =
            HOST_BRIDGE_SCRIPT.takeIf {
                hostEventsEnabled && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
            }.orEmpty()
        val script = (bridge + "\n" + authScript).trim().takeIf { it.isNotBlank() } ?: return false
        val originRule = raftDocumentStartScriptOriginRule(targetUrl) ?: return false
        if (mainFrameOrigin != null && originRule != mainFrameOrigin) return false
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        return runCatching {
            documentStartScriptHandler = WebViewCompat.addDocumentStartJavaScript(activeWebView, script, setOf(originRule))
            diagnosticsInfo("document_start_script installed origin=${originRule.raftRedactedUrlForLog()} authScript=${authScript.isNotBlank()}")
            true
        }.getOrElse { error ->
            Log.w(LOG_TAG, "document_start_script unavailable fallback=bootstrap", error)
            diagnosticsWarn("document_start_script unavailable fallback=bootstrap errorType=${error::class.simpleName}")
            false
        }
    }

    private fun removeDocumentStartScript() {
        runCatching { documentStartScriptHandler?.remove() }
        documentStartScriptHandler = null
        runCatching { firstPaintScriptHandler?.remove() }
        firstPaintScriptHandler = null
    }

    /**
     * The page's own first paint is the engine's white canvas until its stylesheet and theme
     * script apply: give its root the app's canvas colour and scheme until it loaded (lowest
     * priority, removed after `load`; as iOS). Only on [targetUrl]'s origin, like the auth script.
     * Without document-start scripts the WebView's own canvas background (until the first
     * commit) is all there is.
     */
    private fun installFirstPaintScript(targetUrl: String) {
        val activeWebView = webView ?: return
        if (!allowScripts || placeholderBackground == 0) return
        val originRule = raftDocumentStartScriptOriginRule(targetUrl) ?: return
        if (mainFrameOrigin != null && originRule != mainFrameOrigin) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        runCatching {
            firstPaintScriptHandler =
                WebViewCompat.addDocumentStartJavaScript(
                    activeWebView,
                    raftWebViewFirstPaintThemeScript(placeholderBackground, RaftAppThemeState.isDark),
                    setOf(originRule),
                )
        }.onFailure { error ->
            diagnosticsWarn("first_paint_script unavailable errorType=${error::class.simpleName}")
        }
    }

    /**
     * Feedback carries an in-memory bearer in its bootstrap: never forward page console output for
     * a fenced settings document (hostile page code could print the bearer into logcat).
     */
    private fun isRestrictedGenericMode(): Boolean =
        mainFrameOrigin != null ||
            allowedNavigationSchemes.isNotEmpty() && (!allowNavigation || injectedJavaScript.isBlank())

    private fun injectedJavaScriptBootstrapHtml(targetUrl: String): String {
        val safeScript = injectedJavaScript.replace("</script", "<\\/script", ignoreCase = true)
        val safeTargetUrl = AndroidJSONObject.quote(targetUrl)
        return """
            <!doctype html>
            <html>
              <head>
                <meta name="viewport" content="width=device-width, initial-scale=1" />
                <script>
                  (function() {
                    try {
                      $safeScript
                    } catch (error) {
                      console.error("slock_webview_bootstrap_injected_js_error", error && (error.stack || error.message || error));
                    }
                    window.location.replace($safeTargetUrl);
                  })();
                </script>
              </head>
              <body></body>
            </html>
        """.trimIndent()
    }

    private fun hostMainDocumentResponse(url: String?): WebResourceResponse? {
        val target = url ?: return null
        val provider = RaftAndroidWebViewHooks.mainDocumentProvider ?: return null
        val startedAt = System.currentTimeMillis()
        val document = runCatching { provider(target) }.getOrNull() ?: return null
        Log.i(
            LOG_TAG,
            "host_main_document source=${document.source} bytes=${document.bytes.size} " +
                "durationMs=${System.currentTimeMillis() - startedAt} url=${target.formatUrlForLog()}"
        )
        return WebResourceResponse(document.mimeType, "utf-8", ByteArrayInputStream(document.bytes))
    }

    private companion object {
        const val LOG_TAG = RAFT_WEBVIEW_DIAGNOSTICS_TAG
        const val LOG_MESSAGE_LIMIT = 500
        const val PERSISTED_CONSOLE_ERROR_LIMIT = 5
        const val PERSISTED_CONSOLE_MESSAGE_CHARS = 160
        const val MAX_NAV_CHAIN_HOPS = 32
        const val ABOUT_BLANK_URL = "about:blank"
        const val HOST_BRIDGE_SCRIPT = """
            (function(){'use strict';
              var port = null;
              try { port = window.raftHostPort; } catch (_) {}
              Object.defineProperty(window, 'RaftHost', { value: Object.freeze({ version: 'raft-host-v1', capabilities: ['raft-feedback-v1'], emit: function(kind, payload) {
                if (typeof kind !== 'string' || !port) return;
                try { port.postMessage(JSON.stringify({kind: kind, payload: (payload && typeof payload === 'object' && !Array.isArray(payload)) ? payload : {}})); } catch (_) {}
              } }), writable: false, configurable: false });
            })();
        """
    }
}

/** Exact scheme/authority/path/query comparison used after same-origin history updates. */
internal fun raftIsExactMainDocumentUrl(allowedUrl: String?, targetUrl: String): Boolean {
    val allowed = runCatching { URI(allowedUrl ?: return false) }.getOrNull() ?: return false
    val target = runCatching { URI(targetUrl) }.getOrNull() ?: return false
    return target.scheme == allowed.scheme &&
        target.host == allowed.host &&
        target.port == allowed.port &&
        target.path == allowed.path &&
        target.rawQuery == allowed.rawQuery
}

/**
 * The origin lock ([RaftWebViewConfig.mainFrameOrigin]): with no lock every target passes; with
 * one, only a document on exactly that origin (scheme, host, port) does.
 */
internal fun raftMainFrameOriginAllows(lockedOrigin: String?, targetUrl: String): Boolean {
    val locked = lockedOrigin ?: return true
    return raftDocumentStartScriptOriginRule(targetUrl) == locked
}

internal fun raftDocumentStartScriptOriginRule(targetUrl: String): String? =
    runCatching {
        val uri = URI(targetUrl)
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val defaultPort = if (scheme == "https") 443 else 80
        val port = uri.port.takeIf { it >= 0 && it != defaultPort }?.let { ":$it" }.orEmpty()
        "$scheme://$host$port"
    }.getOrNull()

internal fun raftInjectedJavaScriptBootstrapBaseUrl(targetUrl: String): String? =
    raftDocumentStartScriptOriginRule(targetUrl)?.let { origin -> "$origin$RAFT_WEBVIEW_BOOTSTRAP_PATH" }

/** Holds load success until the real target (not the bootstrap document) has started. */
internal class RaftWebViewBootstrapNavigationFence {
    private var targetUrl: String? = null
    private var targetStarted: Boolean = false

    fun begin(targetUrl: String) {
        this.targetUrl = targetUrl
        targetStarted = false
    }

    fun reset() {
        targetUrl = null
        targetStarted = false
    }

    /** The page a bootstrap load is on its way to, until it started (then the WebView's own URL is it). */
    val pendingTargetUrl: String? get() = targetUrl?.takeIf { !targetStarted }

    fun onMainFrameStarted(url: String) {
        if (url == targetUrl) targetStarted = true
    }

    fun mayUncover(url: String): Boolean {
        val target = targetUrl ?: return true
        return targetStarted && url == target
    }
}

internal const val RAFT_WEBVIEW_DIAGNOSTICS_TAG = "SlockWebView"

/** The next progress milestone (25 / 50 / 75 / 100) [progress] reached past [logged], or null. */
internal fun raftWebViewProgressMilestone(progress: Int, logged: Int): Int? =
    listOf(25, 50, 75, 100).lastOrNull { progress >= it }?.takeIf { it > logged }

internal fun String.raftRedactedUrlForLog(): String {
    val uri = runCatching { Uri.parse(this) }.getOrNull() ?: return "<invalid>"
    val scheme = uri.scheme?.lowercase()?.takeIf { it.matches(Regex("[a-z][a-z0-9+.-]*")) } ?: return "<invalid>"
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return "$scheme:<redacted>"
    val port = uri.port.takeIf { it >= 0 }?.let { value -> ":$value" }.orEmpty()
    val path = uri.encodedPath.orEmpty()
    return "$scheme://${host.raftSanitizedLogValue()}$port${path.raftSanitizedLogValue()}".take(160)
}

private fun String.raftSanitizedLogValue(limit: Int = 160): String =
    replace('\n', ' ').replace('\r', ' ').take(limit)

internal actual val raftWebViewNeedsHostBottomInsets: Boolean = true

@Composable
internal actual fun rememberRaftArtifactWebViewInsetMode(): WebContainerAndroidInsetMode {
    val context = LocalContext.current
    return remember(context) {
        raftAndroidWebViewInsetPolicy(RaftAndroidWebViewProvider.chromiumMajorVersion(context))
    }
}

internal actual val raftWebViewDesktopModeViaViewportMeta: Boolean = true

/**
 * One thread for every page share ([raftWebViewShareShimScript]): parsing, base64 decoding and file
 * writes stay off the UI thread and run in the order the messages were posted. It ends when idle.
 */
private const val RaftWebViewShareExpiryRecheckMs: Long = 10_000L

private val RaftWebViewShareIo: java.util.concurrent.Executor by lazy {
    java.util.concurrent.ThreadPoolExecutor(
        0,
        1,
        30L,
        java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>()
    )
}

/** A share signal with, for a share that is ready, the folder its files are in. */
private class RaftAndroidStagedShareSignal(val signal: RaftWebViewShareSignal, val folder: java.io.File?)

/**
 * The shares one page is sending ([RaftWebViewShareReceiver]) and where their bytes go: each share
 * in its own `<uuid>` entry right under the app's share staging folder (`cache/slock-share-files`,
 * the attachment share's; see `AndroidAttachmentShareStaging`), each chunk appended to its file as
 * it arrives. Entries follow that folder's rules (a day, the newest sixteen), applied here before a
 * new one is made and by the attachment share's own sweep; a failed or abandoned share's entry is
 * removed at once. Used from [RaftWebViewShareIo] only.
 */
private class RaftAndroidWebViewShareSession(private val shareRoot: java.io.File) : RaftWebViewShareSink {
    private val folders = HashMap<String, java.io.File>()
    private val fileNames = HashMap<String, List<String>>()
    private val receiver = RaftWebViewShareReceiver(sink = this, nowMillis = { android.os.SystemClock.elapsedRealtime() })

    private var closed = false

    /** Nothing is taken once the session is closed: a message still on its way then starts no share. */
    @Synchronized
    fun accept(raw: String): List<RaftAndroidStagedShareSignal>? = if (closed) emptyList() else receiver.accept(raw)?.map(::staged)

    @Synchronized
    fun hasPending(): Boolean = receiver.pendingCount > 0

    /** The page's port is gone: every share still being received is given up, and no more are taken. */
    @Synchronized
    fun close(): List<RaftAndroidStagedShareSignal> {
        closed = true
        return receiver.abortAll("closed").map(::staged)
    }

    @Synchronized
    fun expire(): List<RaftAndroidStagedShareSignal> = receiver.expire().map(::staged)

    @Synchronized
    fun abortAll(reason: String): List<RaftAndroidStagedShareSignal> = receiver.abortAll(reason).map(::staged)

    private fun staged(signal: RaftWebViewShareSignal): RaftAndroidStagedShareSignal {
        val folder =
            if (signal is RaftWebViewShareSignal.Ready) {
                fileNames.remove(signal.request.id)
                // The entry's age counts from when the share was complete.
                folders.remove(signal.request.id)?.also { it.setLastModified(System.currentTimeMillis()) }
            } else {
                null
            }
        return RaftAndroidStagedShareSignal(signal, folder)
    }

    override fun open(id: String, fileNames: List<String>) {
        discard(id)
        sweep()
        val folder = java.io.File(shareRoot, java.util.UUID.randomUUID().toString())
        check(folder.mkdirs()) { "share folder" }
        folders[id] = folder
        this.fileNames[id] = fileNames
        fileNames.forEach { name ->
            val target = java.io.File(folder, name)
            check(target.parentFile == folder && target.createNewFile()) { "share file" }
        }
    }

    override fun append(id: String, fileIndex: Int, bytes: ByteArray) {
        val folder = checkNotNull(folders[id]) { "share folder" }
        val target = java.io.File(folder, checkNotNull(fileNames[id]) { "share files" }[fileIndex])
        java.io.FileOutputStream(target, true).use { it.write(bytes) }
    }

    override fun discard(id: String) {
        fileNames.remove(id)
        folders.remove(id)?.deleteRecursively()
    }

    private fun sweep() {
        val entries = shareRoot.listFiles()?.filter { it.isDirectory } ?: return
        val stale = raftWebViewShareStaleStagingEntries(entries.map { it.name to it.lastModified() }, System.currentTimeMillis()).toSet()
        entries.filter { it.name in stale }.forEach { runCatching { it.deleteRecursively() } }
    }
}
