@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package build.raft.webcontainer

import build.raft.webcontainer.pool.WEB_CONTAINER_SPARE_BLANK_URL
import build.raft.webcontainer.pool.WebContainerSpareSlot
import build.raft.webcontainer.pool.WebContainerTelemetryEvent
import build.raft.webcontainer.pool.WebContainerTelemetrySink
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.alloc
import kotlinx.cinterop.interpretObjCPointerOrNull
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSThread
import platform.Foundation.NSTimer
import platform.Foundation.addObserver
import platform.Foundation.removeObserver
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIColor
import platform.WebKit.WKAudiovisualMediaTypeAll
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebpagePreferences
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.objc.class_addMethod
import platform.objc.object_getClass
import platform.objc.sel_registerName

/** Process-lifetime sentinel pointer passed as the KVO `context:` for [WebContainerIosKvoObserver]. */
private val WebContainerIosKvoContext: COpaquePointer = nativeHeap.alloc<ByteVar>().ptr

/** KVO key paths observed on `WKWebView` (top-level: K/N disallows fields on NSObject subclass companions). */
private val WebContainerIosKvoObservedKeyPaths: List<String> =
    listOf(
        "URL",
        "title",
        "estimatedProgress",
        "loading",
        "canGoBack",
        "canGoForward",
    )

/**
 * Top-level Objective-C KVO trampoline invoked by `staticCFunction` without capturing Kotlin state.
 */
private fun handleWebContainerIosKvoCallback(
    selfPtr: COpaquePointer?,
    objectPtr: COpaquePointer?,
    context: COpaquePointer?,
) {
    runCatching {
        if (context != WebContainerIosKvoContext) return
        val observer = selfPtr?.let { interpretObjCPointerOrNull<WebContainerIosKvoObserver>(it.rawValue) } ?: return
        val view = objectPtr?.let { interpretObjCPointerOrNull<WKWebView>(it.rawValue) } ?: return
        if (NSThread.isMainThread) {
            observer.deliverChange(view)
        } else {
            dispatch_async(dispatch_get_main_queue()) {
                runCatching { observer.deliverChange(view) }
            }
        }
    }
}

private object WebContainerIosKvoHook {
    private const val SELECTOR = "observeValueForKeyPath:ofObject:change:context:"
    private const val TYPE_ENCODING = "v@:@@@^v"
    private var installed = false

    fun install(observer: WebContainerIosKvoObserver): Boolean {
        if (installed) return true
        val ownClass = object_getClass(observer) ?: return false
        val selector = sel_registerName(SELECTOR)
        val implementation =
            staticCFunction {
                selfPtr: COpaquePointer?,
                _: COpaquePointer?,
                _: COpaquePointer?,
                objectPtr: COpaquePointer?,
                _: COpaquePointer?,
                context: COpaquePointer?,
            ->
                handleWebContainerIosKvoCallback(selfPtr, objectPtr, context)
            }
        val added = class_addMethod(ownClass, selector, implementation.reinterpret(), TYPE_ENCODING)
        installed = added
        return installed
    }
}

/**
 * Native Objective-C KVO observer for `WKWebView` navigation properties (`URL`, `title`,
 * `estimatedProgress`, `loading`, `canGoBack`, `canGoForward`), eliminating 250ms property polling
 * in pure Kotlin/Native without touching Swift or `objc-entry-points.txt`.
 *
 * Guarantees strict 1-to-1 balanced `addObserver`/`removeObserver` registration with rollback on
 * partial failure so `WKWebView` deallocation can never raise a dangling-KVO exception.
 */
internal class WebContainerIosKvoObserver(
    private val onChanged: (WKWebView) -> Unit,
) : NSObject() {
    private var observedView: WKWebView? = null
    private val registeredKeyPaths = mutableListOf<String>()

    val isAttached: Boolean
        get() = observedView != null && registeredKeyPaths.isNotEmpty()

    fun attach(view: WKWebView): Boolean {
        detach()
        if (!WebContainerIosKvoHook.install(this)) return false
        observedView = view
        for (keyPath in WebContainerIosKvoObservedKeyPaths) {
            val ok =
                runCatching {
                    view.addObserver(
                        observer = this,
                        forKeyPath = keyPath,
                        options = NSKeyValueObservingOptionNew,
                        context = WebContainerIosKvoContext,
                    )
                }.isSuccess
            if (!ok) {
                detach()
                return false
            }
            registeredKeyPaths += keyPath
        }
        return true
    }

    fun detach() {
        val view = observedView ?: return
        val paths = registeredKeyPaths.toList()
        observedView = null
        registeredKeyPaths.clear()
        for (keyPath in paths) {
            runCatching {
                view.removeObserver(
                    observer = this,
                    forKeyPath = keyPath,
                    context = WebContainerIosKvoContext,
                )
            }
        }
    }

    internal fun deliverChange(view: WKWebView) {
        if (isSameObjCObject(view, observedView)) {
            onChanged(view)
        }
    }
}

private data class WarmedIosSpare(
    val view: WebContainerIosWebView,
    val ignoresViewportScaleLimits: Boolean,
)

/**
 * Main-thread warm spare `WKWebView` pool for `:web-container` on iOS.
 *
 * Improvements over legacy warm-up:
 * - Matches `WKWebViewConfiguration.ignoresViewportScaleLimits` strictly on `take()` (without
 *   tampering with `scrollView.pinchGestureRecognizer`), so `allowPinchZoom = true` surfaces
 *   (Artifact Viewer), viewport-respecting pages (`file_preview_html`), and `user-scalable=no`
 *   pages (`settings_*`) behave identically across warm-spare and cold-start opens.
 * - Multi-layer anti-zombie defense:
 *   1. Evicts immediately if `webViewWebContentProcessDidTerminate` fires while parked.
 *   2. Drops on `UIApplicationDidReceiveMemoryWarningNotification` and
 *      `UIApplicationDidEnterBackgroundNotification` (refilling on foreground return) so iOS
 *      background jetsam never leaves a dead spare waiting for the next open.
 *   3. Enforces a 5-minute idle TTL and `about:blank` URL check on `take()`, paired with a
 *      `WKContentWorld.defaultClientWorld` liveness probe inside [WebContainerIosHost].
 * - Wired into [IosWebContainerDataStoreRegistry.clearIsolationScope] so `Default` data-store
 *   clears evict any idle spare first.
 */
object WebContainerIosSparePool {
    private const val WARM_DELAY_SECONDS = 2.0
    private const val REFILL_DELAY_SECONDS = 5.0
    private const val MAX_SPARE_IDLE_MS = 5 * 60_000L
    private const val SPARE_TELEMETRY_KEY = "ios_default_spare"

    private val slot = WebContainerSpareSlot<WarmedIosSpare>(retire = { retire(it.view) })
    private var preferredIgnoresViewportScaleLimits: Boolean = false
    private var warmupDelegate: WebContainerIosSpareWarmupDelegate? = null
    private var refillTimer: NSTimer? = null
    private var installed: Boolean = false

    /** Schedules warm spare maintenance on the main thread (idempotent). */
    fun schedule(preferredIgnoresViewportScaleLimits: Boolean = false) {
        if (!NSThread.isMainThread) {
            dispatch_async(dispatch_get_main_queue()) { schedule(preferredIgnoresViewportScaleLimits) }
            return
        }
        if (!installed || !slot.hasItem) {
            this.preferredIgnoresViewportScaleLimits = preferredIgnoresViewportScaleLimits
        }
        if (installed) {
            if (!slot.hasItem) refillLater(WARM_DELAY_SECONDS)
            return
        }
        installed = true
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(UIApplicationDidReceiveMemoryWarningNotification, null, NSOperationQueue.mainQueue) { _ ->
            drop("memory_warning")
        }
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            drop("background")
        }
        center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ ->
            refillLater(WARM_DELAY_SECONDS)
        }
        refillLater(WARM_DELAY_SECONDS)
    }

    /** Takes a ready, non-expired warm spare matching [ignoresViewportScaleLimits], or returns `null`. */
    internal fun take(
        ignoresViewportScaleLimits: Boolean,
        telemetrySink: WebContainerTelemetrySink? = null,
    ): WebContainerIosWebView? {
        if (!NSThread.isMainThread) return null
        val candidate =
            slot.take(maxIdleMs = MAX_SPARE_IDLE_MS) { spare ->
                spare.ignoresViewportScaleLimits == ignoresViewportScaleLimits
            }
        if (candidate == null) {
            preferredIgnoresViewportScaleLimits = ignoresViewportScaleLimits
            val action =
                when {
                    slot.isReady -> "ios_spare_zoom_mismatch"
                    slot.hasItem -> "ios_spare_warming"
                    else -> "ios_spare_miss"
                }
            telemetrySink?.onEvent(
                WebContainerTelemetryEvent.PoolEvent(
                    action = action,
                    keepAliveKey = SPARE_TELEMETRY_KEY,
                    poolSizeAfter = if (slot.hasItem) 1 else 0,
                ),
            )
            if (installed && !slot.hasItem) refillLater(REFILL_DELAY_SECONDS)
            return null
        }
        val view = candidate.view
        view.navigationDelegate = null
        warmupDelegate = null
        val url = view.URL?.absoluteString
        if (url != WEB_CONTAINER_SPARE_BLANK_URL) {
            retire(view)
            telemetrySink?.onEvent(
                WebContainerTelemetryEvent.PoolEvent(
                    action = "ios_spare_rejected_unexpected_url",
                    keepAliveKey = SPARE_TELEMETRY_KEY,
                    poolSizeAfter = 0,
                ),
            )
            if (installed) refillLater(REFILL_DELAY_SECONDS)
            return null
        }
        preferredIgnoresViewportScaleLimits = ignoresViewportScaleLimits
        telemetrySink?.onEvent(
            WebContainerTelemetryEvent.PoolEvent(
                action = "ios_spare_hit",
                keepAliveKey = SPARE_TELEMETRY_KEY,
                poolSizeAfter = 0,
            ),
        )
        if (installed) refillLater(REFILL_DELAY_SECONDS)
        return view
    }

    /** Drops any parked or warming spare and returns `1` if evicted synchronously on main, else `0`. */
    fun drop(
        @Suppress("UNUSED_PARAMETER") reason: String = "manual",
    ): Int {
        if (!NSThread.isMainThread) {
            dispatch_async(dispatch_get_main_queue()) { drop(reason) }
            return 0
        }
        refillTimer?.invalidate()
        refillTimer = null
        return slot.drop()
    }

    private fun refillLater(delaySeconds: Double) {
        if (slot.hasItem || refillTimer != null) return
        refillTimer =
            NSTimer.scheduledTimerWithTimeInterval(delaySeconds, repeats = false) { _ ->
                refillTimer = null
                fill()
            }
    }

    private fun fill() {
        if (slot.hasItem) return
        if (UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive) return
        val ignoresScaleLimits = preferredIgnoresViewportScaleLimits
        runCatching {
            val configuration = WKWebViewConfiguration()
            configuration.websiteDataStore = WKWebsiteDataStore.defaultDataStore()
            configuration.allowsInlineMediaPlayback = true
            configuration.ignoresViewportScaleLimits = ignoresScaleLimits
            configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
            configuration.mediaTypesRequiringUserActionForPlayback = WKAudiovisualMediaTypeAll
            val pagePrefs = WKWebpagePreferences()
            pagePrefs.allowsContentJavaScript = true
            configuration.defaultWebpagePreferences = pagePrefs

            val view = WebContainerIosWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = configuration)
            view.opaque = false
            view.backgroundColor = UIColor.clearColor
            view.scrollView.backgroundColor = UIColor.clearColor
            val generation = slot.put(WarmedIosSpare(view = view, ignoresViewportScaleLimits = ignoresScaleLimits))
            val delegate =
                WebContainerIosSpareWarmupDelegate(
                    onReady = { slot.markReady(generation) },
                    onFailedOrTerminated = { dead -> slot.invalidateIf { isSameObjCObject(it.view, dead) } },
                )
            warmupDelegate = delegate
            view.navigationDelegate = delegate
            view.loadHTMLString("", baseURL = null)
        }
    }

    private fun retire(view: WebContainerIosWebView) {
        view.navigationDelegate = null
        view.UIDelegate = null
        view.stopLoading()
        view.configuration.userContentController.removeAllUserScripts()
        warmupDelegate = null
    }
}

private class WebContainerIosSpareWarmupDelegate(
    private val onReady: () -> Unit,
    private val onFailedOrTerminated: (WebContainerIosWebView) -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {
    private var loadSettled = false
    private var invalidated = false

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        if (loadSettled || invalidated) return
        loadSettled = true
        onReady()
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
        if (loadSettled || invalidated) return
        loadSettled = true
        invalidate(webView)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
        if (loadSettled || invalidated) return
        loadSettled = true
        invalidate(webView)
    }

    override fun webViewWebContentProcessDidTerminate(webView: WKWebView) {
        // Must still evict a ready parked spare if iOS terminates its WebContent process while idle.
        invalidate(webView)
    }

    private fun invalidate(webView: WKWebView) {
        if (invalidated) return
        invalidated = true
        (webView as? WebContainerIosWebView)?.let(onFailedOrTerminated)
    }
}
