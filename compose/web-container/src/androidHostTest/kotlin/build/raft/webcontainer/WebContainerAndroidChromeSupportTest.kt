package build.raft.webcontainer

import android.app.Activity
import android.content.MutableContextWrapper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.FrameLayout
import build.raft.webcontainer.jsb.JsbRegistry
import build.raft.webcontainer.resilience.WebContainerWatchdogPolicy
import java.time.Duration
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebContainerAndroidChromeSupportTest {
    private val createdHosts = mutableListOf<WebContainerAndroidView>()

    @Before
    fun setUp() {
        WebContainerAndroidKeepAlive.clearAll()
    }

    @After
    fun tearDown() {
        for (host in createdHosts) {
            host.destroy()
        }
        createdHosts.clear()
        WebContainerAndroidKeepAlive.clearAll()
    }

    private fun createAttachedHost(
        events: WebContainerEvents = WebContainerEvents(),
        config: WebContainerConfig =
            WebContainerConfig(
                url = "https://example.com/start",
                allowScripts = true,
                navigationPolicy =
                    WebNavigationPolicy(
                        allowNavigation = true,
                        allowedSchemes = setOf("http", "https"),
                        allowNewWindows = true,
                    ),
            ),
    ): Pair<Activity, WebContainerAndroidView> {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        val host =
            WebContainerAndroidView(
                context = MutableContextWrapper(activity),
                initialConfig = config,
                events = events,
                jsbRegistry = JsbRegistry.Empty,
                resourceInterceptor = null,
                telemetrySink = null,
                fileChooser = null,
                watchdogPolicy = WebContainerWatchdogPolicy(),
                nowMs = SystemClock::uptimeMillis,
            )
        createdHosts += host
        activity.setContentView(host)
        controller.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(34))
        return activity to host
    }

    private fun fakeWebResourceRequest(
        url: String,
        isForMainFrame: Boolean = true,
        hasGesture: Boolean = true,
        method: String = "GET",
    ): WebResourceRequest =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)

            override fun isForMainFrame(): Boolean = isForMainFrame

            override fun isRedirect(): Boolean = false

            override fun hasGesture(): Boolean = hasGesture

            override fun getMethod(): String = method

            override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
        }

    @Test
    fun fullscreenShowAndHideSettlesCallbackStrictlyOnceAcrossExitPaths() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().start().resume().visible().get()
        val controller = WebContainerAndroidFullscreenController()
        val customView = View(activity)
        var hiddenCount = 0
        val callback = WebChromeClient.CustomViewCallback { hiddenCount += 1 }

        assertTrue(controller.show(activity, customView, callback, permitted = true))
        assertTrue(controller.isFullscreen)
        assertNotNull(customView.parent)
        assertEquals(0, hiddenCount)

        // Calling hide() twice settles the callback strictly once and detaches the view.
        controller.hide()
        controller.hide()
        assertFalse(controller.isFullscreen)
        assertNull(customView.parent)
        assertEquals(1, hiddenCount)
    }

    @Test
    fun fullscreenRejectsWhenNotPermittedOrNonActivityContextAndStillSettlesCallback() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().start().resume().visible().get()
        val controller = WebContainerAndroidFullscreenController()

        var deniedHidden = 0
        assertFalse(
            controller.show(
                context = activity,
                view = View(activity),
                callback = { deniedHidden += 1 },
                permitted = false,
            ),
        )
        assertEquals(1, deniedHidden)
        assertFalse(controller.isFullscreen)

        var appContextHidden = 0
        assertFalse(
            controller.show(
                context = activity.applicationContext,
                view = View(activity),
                callback = { appContextHidden += 1 },
                permitted = true,
            ),
        )
        assertEquals(1, appContextHidden)
        assertFalse(controller.isFullscreen)

        var nullViewHidden = 0
        assertFalse(
            controller.show(
                context = activity,
                view = null,
                callback = { nullViewHidden += 1 },
                permitted = true,
            ),
        )
        assertEquals(1, nullViewHidden)
    }

    @Test
    fun fullscreenReplacementTearsDownPreviousSessionBeforeShowingNext() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().start().resume().visible().get()
        val controller = WebContainerAndroidFullscreenController()
        val firstView = View(activity)
        val secondView = View(activity)
        var firstHidden = 0
        var secondHidden = 0

        assertTrue(controller.show(activity, firstView, { firstHidden += 1 }, permitted = true))
        assertTrue(controller.show(activity, secondView, { secondHidden += 1 }, permitted = true))
        assertEquals(1, firstHidden)
        assertNull(firstView.parent)
        assertEquals(0, secondHidden)
        assertTrue(controller.isFullscreen)
        assertNotNull(secondView.parent)

        controller.hide()
        assertEquals(1, secondHidden)
        assertFalse(controller.isFullscreen)
    }

    @Test
    fun webContainerFullscreenHidesOnVisibilityGoneAndMainFrameNavigation() {
        val (activity, host) = createAttachedHost()
        val webView = host.getChildAt(0) as WebView
        val chromeClient = shadowOf(webView).webChromeClient
        val webViewClient = shadowOf(webView).webViewClient

        var firstHidden = 0
        chromeClient.onShowCustomView(FrameLayout(activity)) { firstHidden += 1 }
        assertTrue(host.fullscreenController.isFullscreen)
        assertEquals(0, firstHidden)

        // Backgrounding / hiding window exits fullscreen and settles callback once.
        host.dispatchWindowVisibilityChanged(View.GONE)
        assertFalse(host.fullscreenController.isFullscreen)
        assertEquals(1, firstHidden)

        // Show again when visible, then exit on main-frame navigation.
        host.dispatchWindowVisibilityChanged(View.VISIBLE)
        var secondHidden = 0
        chromeClient.onShowCustomView(FrameLayout(activity)) { secondHidden += 1 }
        assertTrue(host.fullscreenController.isFullscreen)

        webViewClient.onPageStarted(webView, "https://example.com/next", null)
        assertFalse(host.fullscreenController.isFullscreen)
        assertEquals(1, secondHidden)
    }

    @Test
    fun webContainerPermissionRequestAlwaysDeniesCameraAndMicrophone() {
        val (_, host) = createAttachedHost()
        val webView = host.getChildAt(0) as WebView
        val chromeClient = shadowOf(webView).webChromeClient

        var deniedCount = 0
        var grantedResources: Array<String>? = null
        val request =
            object : PermissionRequest() {
                override fun getOrigin(): Uri = Uri.parse("https://example.com")

                override fun getResources(): Array<String> =
                    arrayOf(RESOURCE_VIDEO_CAPTURE, RESOURCE_AUDIO_CAPTURE)

                override fun grant(resources: Array<String>?) {
                    grantedResources = resources
                }

                override fun deny() {
                    deniedCount += 1
                }
            }

        chromeClient.onPermissionRequest(request)
        assertEquals(1, deniedCount)
        assertNull(grantedResources)
    }

    @Test
    fun newWindowTransportCapturesUrlIgnoresAboutBlankBlocksNetworkAndCleansUpOnRenderGone() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().start().resume().visible().get()
        val parentWebView = WebView(activity)
        val captured = mutableListOf<String>()
        var dispatchedTransport: WebView.WebViewTransport? = null
        val handler =
            Handler(Looper.getMainLooper()) { msg ->
                dispatchedTransport = msg.obj as? WebView.WebViewTransport
                true
            }
        val transportHelper = WebContainerAndroidNewWindowTransport(timeoutMs = 1_500L, mainHandler = handler)

        // 1) Normal window.open("") followed by w.location.href = "https://oauth.example.com/login"
        val msg1 = Message.obtain(handler).apply { obj = parentWebView.WebViewTransport() }
        assertTrue(transportHelper.open(activity, msg1) { captured += it })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, transportHelper.activeCount)
        val popup1 = assertNotNull(dispatchedTransport?.webView)
        assertFalse(popup1.settings.javaScriptEnabled)
        assertFalse(popup1.settings.domStorageEnabled)
        assertFalse(popup1.settings.supportMultipleWindows())

        val client1 = shadowOf(popup1).webViewClient
        // shouldInterceptRequest blocks subresource network loads without prematurely settling the transport.
        val blockedResp =
            client1.shouldInterceptRequest(
                popup1,
                fakeWebResourceRequest("https://oauth.example.com/leak.js", isForMainFrame = false),
            )
        assertNotNull(blockedResp)
        shadowOf(Looper.getMainLooper()).idle()
        // about:blank is ignored so JS opener can assign location.href next.
        assertFalse(client1.shouldOverrideUrlLoading(popup1, fakeWebResourceRequest("about:blank")))
        assertEquals(1, transportHelper.activeCount)
        assertTrue(captured.isEmpty())

        // Real target URL is captured, cancelled on the transport view, and transport is cleaned up.
        assertTrue(client1.shouldOverrideUrlLoading(popup1, fakeWebResourceRequest("https://oauth.example.com/login")))
        assertEquals(listOf("https://oauth.example.com/login"), captured)
        assertEquals(0, transportHelper.activeCount)

        // 2) Render process gone cleans up cleanly and returns true.
        val msg2 = Message.obtain(handler).apply { obj = parentWebView.WebViewTransport() }
        assertTrue(transportHelper.open(activity, msg2) { captured += it })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, transportHelper.activeCount)
        val popup2 = assertNotNull(dispatchedTransport?.webView)
        assertTrue(shadowOf(popup2).webViewClient.onRenderProcessGone(popup2, null))
        assertEquals(0, transportHelper.activeCount)

        // 3) Abandoned popup is torn down on timeout without leaking.
        val msg3 = Message.obtain(handler).apply { obj = parentWebView.WebViewTransport() }
        assertTrue(transportHelper.open(activity, msg3) { captured += it })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, transportHelper.activeCount)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, transportHelper.activeCount)
        assertEquals(listOf("https://oauth.example.com/login"), captured)

        parentWebView.destroy()
    }

    @Test
    fun webContainerOnCreateWindowRespectsAllowNewWindowsUserGestureAndNavigationPolicy() {
        val blocked = mutableListOf<String>()
        val caught = mutableListOf<String>()
        val events =
            WebContainerEvents(
                onNavigationBlocked = { url, reason -> blocked += "$reason:$url" },
                catchesMainFrameNavigation = { ctx ->
                    if (ctx.url.startsWith("https://raft.example.com/oauth/authorize")) {
                        caught += ctx.url
                        true
                    } else {
                        false
                    }
                },
            )
        val (_, host) = createAttachedHost(events = events)
        val webView = host.getChildAt(0) as WebView
        val chromeClient = shadowOf(webView).webChromeClient

        // Multiple windows enabled when allowNewWindows = true; auto popups remain disabled.
        assertTrue(webView.settings.supportMultipleWindows())
        assertFalse(webView.settings.javaScriptCanOpenWindowsAutomatically)

        // Unsolicited popup (isUserGesture = false) is silently rejected.
        var dispatchedTransport: WebView.WebViewTransport? = null
        val handler =
            Handler(Looper.getMainLooper()) { msg ->
                dispatchedTransport = msg.obj as? WebView.WebViewTransport
                true
            }
        val unsolicitedMsg = Message.obtain(handler).apply { obj = webView.WebViewTransport() }
        assertFalse(chromeClient.onCreateWindow(webView, false, false, unsolicitedMsg))
        assertEquals(0, host.newWindowTransport.activeCount)

        // User-gesture popup allowed URL loads in the parent WebView.
        val allowedMsg = Message.obtain(handler).apply { obj = webView.WebViewTransport() }
        assertTrue(chromeClient.onCreateWindow(webView, false, true, allowedMsg))
        shadowOf(Looper.getMainLooper()).idle()
        val popupAllowed = assertNotNull(dispatchedTransport?.webView)
        assertTrue(
            shadowOf(popupAllowed).webViewClient.shouldOverrideUrlLoading(
                popupAllowed,
                fakeWebResourceRequest("https://example.com/popup-target"),
            ),
        )
        assertEquals("https://example.com/popup-target", shadowOf(webView).lastLoadedUrl)

        // User-gesture popup matching catchesMainFrameNavigation is intercepted by host (not loaded).
        val caughtMsg = Message.obtain(handler).apply { obj = webView.WebViewTransport() }
        assertTrue(chromeClient.onCreateWindow(webView, false, true, caughtMsg))
        shadowOf(Looper.getMainLooper()).idle()
        val popupCaught = assertNotNull(dispatchedTransport?.webView)
        assertTrue(
            shadowOf(popupCaught).webViewClient.shouldOverrideUrlLoading(
                popupCaught,
                fakeWebResourceRequest("https://raft.example.com/oauth/authorize?client_id=1"),
            ),
        )
        assertEquals(listOf("https://raft.example.com/oauth/authorize?client_id=1"), caught)
        assertEquals("https://example.com/popup-target", shadowOf(webView).lastLoadedUrl)

        // Disallowed scheme (e.g. javascript:) is blocked via onNavigationBlocked and never loaded.
        val badSchemeMsg = Message.obtain(handler).apply { obj = webView.WebViewTransport() }
        assertTrue(chromeClient.onCreateWindow(webView, false, true, badSchemeMsg))
        shadowOf(Looper.getMainLooper()).idle()
        val popupBad = assertNotNull(dispatchedTransport?.webView)
        assertTrue(
            shadowOf(popupBad).webViewClient.shouldOverrideUrlLoading(
                popupBad,
                fakeWebResourceRequest("tel:+1234567890"),
            ),
        )
        assertEquals(listOf("scheme_not_allowed:tel:+1234567890"), blocked)

        // Toggling allowNewWindows = false disables supportMultipleWindows and blocks new windows.
        host.applyConfig(
            WebContainerConfig(
                url = "https://example.com/start",
                allowScripts = true,
                navigationPolicy =
                    WebNavigationPolicy(
                        allowNavigation = true,
                        allowedSchemes = setOf("http", "https"),
                        allowNewWindows = false,
                    ),
            ),
        )
        assertFalse(webView.settings.supportMultipleWindows())
        assertFalse(chromeClient.onCreateWindow(webView, false, true, null))
        assertTrue(blocked.any { it.startsWith("new_window_blocked:") })
    }

    @Test
    fun fullscreenSetupExceptionStillSettlesCallbackOnceAndDetachesView() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().start().resume().visible().get()
        val controller =
            WebContainerAndroidFullscreenController(
                dialogFactory = { throw IllegalStateException("dialog_create_failed") },
            )
        val customView = View(activity)
        var hiddenCount = 0
        assertFalse(controller.show(activity, customView, { hiddenCount += 1 }, permitted = true))
        assertEquals(1, hiddenCount)
        assertFalse(controller.isFullscreen)
        assertNull(customView.parent)
    }

    @Test
    fun newWindowTransportBindsRequestedProfileAndFailsClosedWhenBindingFails() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().start().resume().visible().get()
        val parentWebView = WebView(activity)
        val boundProfiles = mutableListOf<String>()
        var shouldBindSucceed = true
        val handler = Handler(Looper.getMainLooper()) { true }
        val transportHelper =
            WebContainerAndroidNewWindowTransport(
                timeoutMs = 1_500L,
                mainHandler = handler,
                bindProfile = { _, name ->
                    boundProfiles += name
                    shouldBindSucceed
                },
            )

        // Successful profile bind opens transport with matching profile name.
        val msgOk = Message.obtain(handler).apply { obj = parentWebView.WebViewTransport() }
        assertTrue(
            transportHelper.open(
                context = activity,
                resultMsg = msgOk,
                profileName = "raft_wc_raft_third_party",
                onUrlCaptured = {},
            ),
        )
        assertEquals(listOf("raft_wc_raft_third_party"), boundProfiles)
        assertEquals(1, transportHelper.activeCount)
        transportHelper.cancelAll()
        assertEquals(0, transportHelper.activeCount)

        // Failed profile bind fails closed and leaves zero active transports.
        shouldBindSucceed = false
        val msgFail = Message.obtain(handler).apply { obj = parentWebView.WebViewTransport() }
        assertFalse(
            transportHelper.open(
                context = activity,
                resultMsg = msgFail,
                profileName = "raft_wc_raft_third_party",
                onUrlCaptured = {},
            ),
        )
        assertEquals(listOf("raft_wc_raft_third_party", "raft_wc_raft_third_party"), boundProfiles)
        assertEquals(0, transportHelper.activeCount)

        parentWebView.destroy()
    }

    @Test
    fun newWindowTransportCapturesMainFrameUrlFromShouldInterceptRequestWhenOverrideIsSkipped() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().start().resume().visible().get()
        val parentWebView = WebView(activity)
        val captured = mutableListOf<String>()
        var dispatchedTransport: WebView.WebViewTransport? = null
        val handler =
            Handler(Looper.getMainLooper()) { msg ->
                dispatchedTransport = msg.obj as? WebView.WebViewTransport
                true
            }
        val transportHelper = WebContainerAndroidNewWindowTransport(timeoutMs = 1_500L, mainHandler = handler)

        val msg = Message.obtain(handler).apply { obj = parentWebView.WebViewTransport() }
        assertTrue(transportHelper.open(activity, msg) { captured += it })
        shadowOf(Looper.getMainLooper()).idle()
        val popup = assertNotNull(dispatchedTransport?.webView)
        val client = shadowOf(popup).webViewClient

        // Main-frame about:blank and subresource requests are blocked without capturing.
        assertNotNull(client.shouldInterceptRequest(popup, fakeWebResourceRequest("about:blank", isForMainFrame = true)))
        assertNotNull(client.shouldInterceptRequest(popup, fakeWebResourceRequest("https://docs.stamp.build/app.js", isForMainFrame = false)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, transportHelper.activeCount)
        assertTrue(captured.isEmpty())

        // Main-frame target URL (e.g. `<a target="_blank" rel="noopener noreferrer">` on Chromium 114 /
        // Huawei WebView where `shouldOverrideUrlLoading` is skipped) is captured while blocking network I/O.
        val blockedResponse =
            client.shouldInterceptRequest(
                popup,
                fakeWebResourceRequest("https://docs.stamp.build/zh-cn/privacy/", isForMainFrame = true),
            )
        assertNotNull(blockedResponse)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("https://docs.stamp.build/zh-cn/privacy/"), captured)
        assertEquals(0, transportHelper.activeCount)
        assertEquals(activity.applicationContext, (popup.context as? MutableContextWrapper)?.baseContext)

        parentWebView.destroy()
    }

    @Test
    fun newWindowTransportUsesAnchorFallbackOnTimeoutButPrefersTransportUrlAndDropsOnCancel() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().start().resume().visible().get()
        val parentWebView = WebView(activity)
        val captured = mutableListOf<String>()
        var dispatchedTransport: WebView.WebViewTransport? = null
        val handler =
            Handler(Looper.getMainLooper()) { msg ->
                dispatchedTransport = msg.obj as? WebView.WebViewTransport
                true
            }
        val transportHelper =
            WebContainerAndroidNewWindowTransport(
                timeoutMs = 2_000L,
                fallbackTimeoutMs = 350L,
                mainHandler = handler,
            )

        // 1) When OEM WebView drops all popup callbacks, anchor fallbackUrl resolves at fallbackTimeoutMs.
        val msgTimeout = Message.obtain(handler).apply { obj = parentWebView.WebViewTransport() }
        assertTrue(
            transportHelper.open(
                context = activity,
                resultMsg = msgTimeout,
                fallbackUrl = "https://docs.stamp.build/zh-cn/terms/",
            ) { captured += it },
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, transportHelper.activeCount)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(0, transportHelper.activeCount)
        assertEquals(listOf("https://docs.stamp.build/zh-cn/terms/"), captured)

        // 2) Authoritative transport URL wins over anchor fallbackUrl (e.g. onclick window.open).
        val msgAuthoritative = Message.obtain(handler).apply { obj = parentWebView.WebViewTransport() }
        assertTrue(
            transportHelper.open(
                context = activity,
                resultMsg = msgAuthoritative,
                fallbackUrl = "https://stamp.build/stale-href",
            ) { captured += it },
        )
        shadowOf(Looper.getMainLooper()).idle()
        val popupAuth = assertNotNull(dispatchedTransport?.webView)
        assertNotNull(
            shadowOf(popupAuth).webViewClient.shouldInterceptRequest(
                popupAuth,
                fakeWebResourceRequest("https://status.stamp.build/", isForMainFrame = true),
            ),
        )
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(0, transportHelper.activeCount)
        assertEquals(listOf("https://docs.stamp.build/zh-cn/terms/", "https://status.stamp.build/"), captured)

        // 3) javascript: fallback is ignored and cancelAll() never fires fallbackUrl.
        val msgJs = Message.obtain(handler).apply { obj = parentWebView.WebViewTransport() }
        assertTrue(
            transportHelper.open(
                context = activity,
                resultMsg = msgJs,
                fallbackUrl = "javascript:void(0)",
            ) { captured += it },
        )
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(1, transportHelper.activeCount)
        transportHelper.cancelAll()
        assertEquals(0, transportHelper.activeCount)

        val msgCancelled = Message.obtain(handler).apply { obj = parentWebView.WebViewTransport() }
        assertTrue(
            transportHelper.open(
                context = activity,
                resultMsg = msgCancelled,
                fallbackUrl = "https://docs.stamp.build/zh-cn/contact/",
            ) { captured += it },
        )
        transportHelper.cancelAll()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(listOf("https://docs.stamp.build/zh-cn/terms/", "https://status.stamp.build/"), captured)

        parentWebView.destroy()
    }

    @Test
    fun webContainerOnCreateWindowRoutesCrossOriginTargetBlankCapturedViaShouldInterceptRequest() {
        val routed = mutableListOf<Triple<String, String, Boolean>>()
        val events =
            WebContainerEvents(
                routesMainFrameNavigation = { candidate ->
                    candidate.startsWith("https://docs.stamp.build/")
                },
                onNavigationBlockedWithGesture = { url, reason, isUserGesture ->
                    routed += Triple(url, reason, isUserGesture)
                },
            )
        val (_, host) =
            createAttachedHost(
                events = events,
                config =
                    WebContainerConfig(
                        url = "https://stamp.build/",
                        allowScripts = true,
                        navigationPolicy =
                            WebNavigationPolicy(
                                allowNavigation = true,
                                allowedSchemes = setOf("http", "https"),
                                allowNewWindows = true,
                            ),
                    ),
            )
        val webView = host.getChildAt(0) as WebView
        val chromeClient = shadowOf(webView).webChromeClient
        var dispatchedTransport: WebView.WebViewTransport? = null
        val handler =
            Handler(Looper.getMainLooper()) { msg ->
                dispatchedTransport = msg.obj as? WebView.WebViewTransport
                true
            }

        val msg = Message.obtain(handler).apply { obj = webView.WebViewTransport() }
        assertTrue(chromeClient.onCreateWindow(webView, false, true, msg))
        shadowOf(Looper.getMainLooper()).idle()
        val popup = assertNotNull(dispatchedTransport?.webView)
        assertNotNull(
            shadowOf(popup).webViewClient.shouldInterceptRequest(
                popup,
                fakeWebResourceRequest("https://docs.stamp.build/zh-cn/welcome/", isForMainFrame = true),
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            listOf(Triple("https://docs.stamp.build/zh-cn/welcome/", "routed_externally", true)),
            routed,
        )
    }
}
