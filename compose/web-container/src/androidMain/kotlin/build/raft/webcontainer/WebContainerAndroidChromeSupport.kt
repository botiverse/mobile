package build.raft.webcontainer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Extracts a link URL from an Android [WebView.HitTestResult] for anchor and image-anchor hits.
 */
fun extractAndroidHitTestLinkUrl(hitTestResult: WebView.HitTestResult?): String {
    if (hitTestResult == null) return ""
    val isAnchor =
        hitTestResult.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
            hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
    return if (isAnchor) hitTestResult.extra?.trim().orEmpty() else ""
}

/**
 * Unwraps a live, non-finishing [Activity] from a [Context] (including [ContextWrapper] chains
 * such as `MutableContextWrapper`), or returns `null` when only an application/non-UI context is
 * available or the Activity is already finishing/destroyed.
 */
internal fun findLiveAndroidActivity(context: Context?): Activity? {
    var current = context
    while (current != null) {
        if (current is Activity) {
            return if (!current.isFinishing && !current.isDestroyed) current else null
        }
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

/**
 * Manages Android HTML5 fullscreen custom views (`WebChromeClient.onShowCustomView` /
 * `onHideCustomView`) inside an immersive fullscreen [Dialog].
 *
 * Using a dedicated fullscreen [Dialog]:
 * - Renders above both the host [Activity] and any Compose overlay/sheet hosting the WebView.
 * - Intercepts hardware/gesture Back cleanly to exit fullscreen before navigating the page back.
 * - Isolates system-bar hiding (`WindowInsetsControllerCompat`, applied after `dialog.show()` once
 *   the dialog window's decor view is attached) to the fullscreen window so the host Activity's
 *   edge-to-edge bar styling is never corrupted.
 * - Guarantees [WebChromeClient.CustomViewCallback.onCustomViewHidden] is called **strictly once**
 *   across every exit path (page exit, Back press, dialog dismiss, setup error, background/hide,
 *   detach, navigation, renderer death, keep-alive park, or destroy).
 */
class WebContainerAndroidFullscreenController(
    private val dialogFactory: (Activity) -> Dialog = { activity ->
        Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    },
) {
    private class ActiveSession(
        val dialog: Dialog,
        val container: FrameLayout,
        val customView: View,
        private val settleOnce: () -> Unit,
    ) {
        fun settleCallbackOnce() {
            settleOnce()
        }
    }

    private var activeSession: ActiveSession? = null

    val isFullscreen: Boolean
        get() = activeSession != null

    /**
     * Presents [view] in a fullscreen dialog when [permitted] is true and a live [Activity] can
     * be resolved from [context]. When rejected or if any setup/presentation step throws,
     * [callback] is settled strictly once so Chromium never deadlocks its fullscreen state.
     */
    fun show(
        context: Context,
        view: View?,
        callback: WebChromeClient.CustomViewCallback?,
        permitted: Boolean,
    ): Boolean {
        val settled = AtomicBoolean(false)
        val settleOnce: () -> Unit = {
            if (settled.compareAndSet(false, true)) {
                runCatching { callback?.onCustomViewHidden() }
            }
        }
        if (!permitted || view == null || callback == null) {
            settleOnce()
            return false
        }
        val activity = findLiveAndroidActivity(context)
        if (activity == null) {
            settleOnce()
            return false
        }
        // Tear down any stale fullscreen session before presenting a new one.
        if (activeSession != null) {
            hide()
        }
        var container: FrameLayout? = null
        var session: ActiveSession? = null
        val presented =
            runCatching {
                val frame =
                    FrameLayout(activity).apply {
                        setBackgroundColor(Color.BLACK)
                        keepScreenOn = true
                        layoutParams =
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                    }
                container = frame
                runCatching { (view.parent as? ViewGroup)?.removeView(view) }
                frame.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                val dialog = dialogFactory(activity)
                val nextSession =
                    ActiveSession(
                        dialog = dialog,
                        container = frame,
                        customView = view,
                        settleOnce = settleOnce,
                    )
                session = nextSession
                dialog.setContentView(frame)
                dialog.setCancelable(true)
                dialog.setCanceledOnTouchOutside(false)
                dialog.setOnCancelListener { hideIfSame(nextSession) }
                dialog.setOnDismissListener { hideIfSame(nextSession) }
                dialog.window?.let { window ->
                    window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        window.attributes =
                            window.attributes.apply {
                                layoutInDisplayCutoutMode =
                                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                            }
                    }
                }
                activeSession = nextSession
                dialog.show()
                // Configure edge-to-edge layout and immersive bars AFTER dialog.show(), once the
                // dialog window's decor view is attached (API 30+ Window.getInsetsController() is
                // null before show()).
                dialog.window?.let { window ->
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                    window.setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    runCatching {
                        WindowCompat.getInsetsController(window, window.decorView).apply {
                            hide(WindowInsetsCompat.Type.systemBars())
                            systemBarsBehavior =
                                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        }
                    }
                }
                true
            }.getOrDefault(false)
        if (!presented) {
            val active = session
            if (active != null && activeSession === active) {
                hideIfSame(active)
            } else {
                runCatching { container?.removeView(view) }
                settleOnce()
            }
            return false
        }
        return true
    }

    /**
     * Dismisses the active fullscreen dialog (if any), detaches its custom view, and settles the
     * [WebChromeClient.CustomViewCallback] strictly once. Safe to call repeatedly or re-entrantly.
     */
    fun hide() {
        val current = activeSession ?: return
        hideIfSame(current)
    }

    private fun hideIfSame(target: ActiveSession) {
        if (activeSession !== target) return
        activeSession = null
        runCatching { target.container.removeView(target.customView) }
        runCatching {
            if (target.dialog.isShowing) {
                target.dialog.dismiss()
            }
        }
        target.settleCallbackOnce()
    }
}

/**
 * Ephemeral, script-disabled, network-blocked transport [WebView] coordinator for Android
 * `WebChromeClient.onCreateWindow` (`window.open` / `target="_blank"`).
 *
 * When JavaScript calls `window.open(url)` from a non-anchor element (e.g. a button), Chromium
 * does not populate `HitTestResult.extra`; the only way to receive the target URL is to hand
 * Chromium a temporary [WebView] via `WebView.WebViewTransport`. This helper:
 * - Binds the parent WebView's Chromium profile (when non-default) before touching `settings`,
 *   failing closed if profile binding fails so multi-profile isolation is never breached.
 * - Disables JS, storage, file/content access, images, and nested windows on the transport view.
 * - Blocks every network load in `shouldInterceptRequest` (including through teardown) so no
 *   cookies/requests leak.
 * - Handles `onRenderProcessGone` (tears down cleanly and returns `true` so renderer death never
 *   kills the app process).
 * - Captures the first non-blank URL (including `const w = window.open(""); w.location = url`),
 *   immediately destroys the transport WebView, and routes the URL through the parent WebView's
 *   main-frame navigation policy.
 * - Enforces a bounded concurrent cap and hard timeout so abandoned popups never leak.
 */
class WebContainerAndroidNewWindowTransport(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val fallbackTimeoutMs: Long = DEFAULT_FALLBACK_TIMEOUT_MS,
    private val maxPending: Int = DEFAULT_MAX_PENDING,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val webViewFactory: (Context) -> WebView = { ctx -> WebView(ctx) },
    private val bindProfile: (WebView, String) -> Boolean = ::defaultBindTransportProfile,
) {
    private object DetachedTransportWebViewClient : WebViewClient() {
        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?,
        ): Boolean = true

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse = emptyTransportBlockedResponse()
    }

    private class PendingTransport(
        val popupWebView: WebView,
        private val onUrlCaptured: (String) -> Unit,
        private val onTeardown: (PendingTransport) -> Unit,
    ) {
        private val settled = AtomicBoolean(false)
        var timeoutRunnable: Runnable? = null

        fun finish(capturedUrl: String?, mainHandler: Handler) {
            if (!settled.compareAndSet(false, true)) return
            timeoutRunnable?.let(mainHandler::removeCallbacks)
            timeoutRunnable = null
            onTeardown(this)
            runCatching {
                popupWebView.stopLoading()
                popupWebView.webChromeClient = null
                popupWebView.webViewClient = DetachedTransportWebViewClient
                (popupWebView.context as? MutableContextWrapper)?.let { wrapper ->
                    wrapper.baseContext = wrapper.applicationContext ?: wrapper.baseContext
                }
                popupWebView.destroy()
            }
            val normalized = capturedUrl?.trim().orEmpty()
            if (normalized.isNotEmpty() && !normalized.equals(ABOUT_BLANK_URL, ignoreCase = true)) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    onUrlCaptured(normalized)
                } else {
                    mainHandler.post { onUrlCaptured(normalized) }
                }
            }
        }
    }

    private val pendingTransports = ArrayDeque<PendingTransport>()

    val activeCount: Int
        get() = pendingTransports.size

    @SuppressLint("SetJavaScriptEnabled")
    fun open(
        context: Context,
        resultMsg: Message?,
        profileName: String? = null,
        fallbackUrl: String? = null,
        onUrlCaptured: (String) -> Unit,
    ): Boolean {
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        while (pendingTransports.size >= maxPending) {
            pendingTransports.firstOrNull()?.finish(capturedUrl = null, mainHandler = mainHandler)
                ?: break
        }
        val appContext = context.applicationContext ?: context
        val uiContext = findLiveAndroidActivity(context) ?: appContext
        val wrappedContext = MutableContextWrapper(uiContext)
        val popupWebView =
            runCatching { webViewFactory(wrappedContext) }.getOrNull() ?: return false
        // Bind the parent WebView's Chromium profile immediately after construction and BEFORE
        // touching WebSettings; fail closed if binding fails so we never attach a Default-profile
        // popup to an isolated parent WebView.
        if (!profileName.isNullOrBlank()) {
            val bound = runCatching { bindProfile(popupWebView, profileName) }.getOrDefault(false)
            if (!bound) {
                runCatching {
                    popupWebView.webChromeClient = null
                    popupWebView.webViewClient = DetachedTransportWebViewClient
                    wrappedContext.baseContext = appContext
                    popupWebView.destroy()
                }
                return false
            }
        }
        runCatching {
            popupWebView.settings.apply {
                javaScriptEnabled = false
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                loadsImagesAutomatically = false
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                @Suppress("DEPRECATION")
                databaseEnabled = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
        }
        val normalizedFallback = normalizeTransportFallbackUrl(fallbackUrl)
        val session =
            PendingTransport(
                popupWebView = popupWebView,
                onUrlCaptured = onUrlCaptured,
                onTeardown = { item -> pendingTransports.remove(item) },
            )
        popupWebView.webViewClient =
            object : WebViewClient() {
                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?,
                ): Boolean {
                    session.finish(capturedUrl = normalizedFallback, mainHandler = mainHandler)
                    return true
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = handleTransportUrl(request?.url?.toString())

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    url: String?,
                ): Boolean = handleTransportUrl(url)

                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: Bitmap?,
                ) {
                    val trimmed = url?.trim().orEmpty()
                    if (trimmed.isNotEmpty() && !trimmed.equals(ABOUT_BLANK_URL, ignoreCase = true)) {
                        session.finish(capturedUrl = trimmed, mainHandler = mainHandler)
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse {
                    // On Chromium / OEM WebViews (e.g. Huawei WebView 114), initial popup loads
                    // for `<a target="_blank" rel="noopener noreferrer">` frequently bypass
                    // `shouldOverrideUrlLoading` and hit `shouldInterceptRequest` directly on the
                    // IO thread. Capture the main-frame URL onto mainHandler while still blocking
                    // all network I/O on the ephemeral transport view.
                    if (request?.isForMainFrame == true) {
                        val trimmed = request.url?.toString()?.trim().orEmpty()
                        if (trimmed.isNotEmpty() && !trimmed.equals(ABOUT_BLANK_URL, ignoreCase = true)) {
                            mainHandler.post {
                                session.finish(capturedUrl = trimmed, mainHandler = mainHandler)
                            }
                        }
                    }
                    return emptyTransportBlockedResponse()
                }

                private fun handleTransportUrl(rawUrl: String?): Boolean {
                    val trimmed = rawUrl?.trim().orEmpty()
                    if (trimmed.isEmpty() || trimmed.equals(ABOUT_BLANK_URL, ignoreCase = true)) {
                        return false
                    }
                    session.finish(capturedUrl = trimmed, mainHandler = mainHandler)
                    return true
                }
            }
        val effectiveTimeoutMs =
            if (normalizedFallback != null) {
                minOf(timeoutMs, fallbackTimeoutMs)
            } else {
                timeoutMs
            }
        val timeout = Runnable { session.finish(capturedUrl = normalizedFallback, mainHandler = mainHandler) }
        session.timeoutRunnable = timeout
        pendingTransports.addLast(session)
        mainHandler.postDelayed(timeout, effectiveTimeoutMs)

        transport.webView = popupWebView
        val dispatched =
            runCatching {
                resultMsg.sendToTarget()
                true
            }.getOrDefault(false)
        if (!dispatched) {
            session.finish(capturedUrl = null, mainHandler = mainHandler)
            return false
        }
        return true
    }

    /** Tears down all pending transport WebViews immediately (e.g. host background/detach/destroy). */
    fun cancelAll() {
        val snapshot = pendingTransports.toList()
        for (item in snapshot) {
            item.finish(capturedUrl = null, mainHandler = mainHandler)
        }
    }

    companion object {
        internal const val DEFAULT_TIMEOUT_MS: Long = 2_000L
        internal const val DEFAULT_FALLBACK_TIMEOUT_MS: Long = 350L
        internal const val DEFAULT_MAX_PENDING: Int = 4
        private const val ABOUT_BLANK_URL: String = "about:blank"

        private fun normalizeTransportFallbackUrl(rawUrl: String?): String? {
            val trimmed = rawUrl?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed.equals(ABOUT_BLANK_URL, ignoreCase = true)) return null
            if (trimmed.startsWith("javascript:", ignoreCase = true)) return null
            return trimmed
        }

        private fun emptyTransportBlockedResponse(): WebResourceResponse =
            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

        private fun defaultBindTransportProfile(
            webView: WebView,
            profileName: String,
        ): Boolean {
            if (!runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)) {
                return false
            }
            return runCatching {
                WebViewCompat.setProfile(webView, profileName)
                true
            }.getOrDefault(false)
        }
    }
}
