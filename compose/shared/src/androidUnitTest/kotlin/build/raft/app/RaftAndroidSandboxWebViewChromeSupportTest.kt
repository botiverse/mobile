package build.raft.app

import android.app.Activity
import android.content.MutableContextWrapper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.FrameLayout
import java.time.Duration
import org.junit.After
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
class RaftAndroidSandboxWebViewChromeSupportTest {
    private val createdViews = mutableListOf<RaftAndroidSandboxWebView>()

    @After
    fun tearDown() {
        for (view in createdViews) {
            view.destroy()
        }
        createdViews.clear()
    }

    private fun createAttachedView(
        events: RaftWebViewEvents = RaftWebViewEvents(),
        allowNewWindows: Boolean = true,
    ): Pair<Activity, RaftAndroidSandboxWebView> {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        val host = RaftAndroidSandboxWebView(MutableContextWrapper(activity), events)
        createdViews += host
        activity.setContentView(host)
        controller.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(34))
        host.applyConfig(
            RaftWebViewConfig(
                url = "https://example.com/start",
                allowScripts = true,
                allowNavigation = true,
                domStorageEnabled = true,
                useWideViewPort = false,
                cacheEnabled = true,
                ephemeral = false,
                injectedJavaScript = "",
                allowedNavigationSchemes = setOf("http", "https"),
                hostEventsEnabled = false,
                allowNewWindows = allowNewWindows,
            ),
        )
        return activity to host
    }

    private fun fakeRequest(url: String): WebResourceRequest =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)

            override fun isForMainFrame(): Boolean = true

            override fun isRedirect(): Boolean = false

            override fun hasGesture(): Boolean = true

            override fun getMethod(): String = "GET"

            override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
        }

    @Test
    fun legacySandboxFullscreenPermissionDenyAndWindowOpenWorkCleanly() {
        val blocked = mutableListOf<String>()
        val caught = mutableListOf<String>()
        val events =
            RaftWebViewEvents().apply {
                onNavigationBlocked = { blocked += it }
                catchesMainFrameNavigation = { ask ->
                    if (ask.url.startsWith("https://raft.example.com/oauth/authorize")) {
                        caught += ask.url
                        true
                    } else {
                        false
                    }
                }
            }
        val (activity, host) = createAttachedView(events = events, allowNewWindows = true)
        val webView = host.getChildAt(0) as WebView
        val chromeClient = shadowOf(webView).webChromeClient

        // 1) Fullscreen custom view shows and exits on visibility GONE, settling callback once.
        var hiddenCount = 0
        chromeClient.onShowCustomView(FrameLayout(activity)) { hiddenCount += 1 }
        assertTrue(host.fullscreenController.isFullscreen)
        host.dispatchWindowVisibilityChanged(View.GONE)
        assertFalse(host.fullscreenController.isFullscreen)
        assertEquals(1, hiddenCount)
        host.dispatchWindowVisibilityChanged(View.VISIBLE)

        // 2) Web media permission request is explicitly denied.
        var denied = 0
        var granted: Array<String>? = null
        chromeClient.onPermissionRequest(
            object : PermissionRequest() {
                override fun getOrigin(): Uri = Uri.parse("https://example.com")

                override fun getResources(): Array<String> =
                    arrayOf(RESOURCE_VIDEO_CAPTURE, RESOURCE_AUDIO_CAPTURE)

                override fun grant(resources: Array<String>?) {
                    granted = resources
                }

                override fun deny() {
                    denied += 1
                }
            },
        )
        assertEquals(1, denied)
        assertNull(granted)

        // 3) Multiple windows enabled when allowNewWindows = true; auto popups remain off.
        assertTrue(webView.settings.supportMultipleWindows())
        assertFalse(webView.settings.javaScriptCanOpenWindowsAutomatically)

        var dispatchedTransport: WebView.WebViewTransport? = null
        val handler =
            Handler(Looper.getMainLooper()) { msg ->
                dispatchedTransport = msg.obj as? WebView.WebViewTransport
                true
            }

        // User-gesture popup allowed URL loads in parent WebView.
        val msgAllowed = Message.obtain(handler).apply { obj = webView.WebViewTransport() }
        assertTrue(chromeClient.onCreateWindow(webView, false, true, msgAllowed))
        shadowOf(Looper.getMainLooper()).idle()
        val popupAllowed = assertNotNull(dispatchedTransport?.webView)
        assertTrue(shadowOf(popupAllowed).webViewClient.shouldOverrideUrlLoading(popupAllowed, fakeRequest("https://example.com/popup")))
        assertEquals("https://example.com/popup", shadowOf(webView).lastLoadedUrl)

        // User-gesture popup matching catchesMainFrameNavigation is intercepted by host.
        val msgCaught = Message.obtain(handler).apply { obj = webView.WebViewTransport() }
        assertTrue(chromeClient.onCreateWindow(webView, false, true, msgCaught))
        shadowOf(Looper.getMainLooper()).idle()
        val popupCaught = assertNotNull(dispatchedTransport?.webView)
        assertTrue(
            shadowOf(popupCaught).webViewClient.shouldOverrideUrlLoading(
                popupCaught,
                fakeRequest("https://raft.example.com/oauth/authorize?state=1"),
            ),
        )
        assertEquals(listOf("https://raft.example.com/oauth/authorize?state=1"), caught)

        // Disallowed scheme routes to onNavigationBlocked and never loads in parent.
        val msgBlocked = Message.obtain(handler).apply { obj = webView.WebViewTransport() }
        assertTrue(chromeClient.onCreateWindow(webView, false, true, msgBlocked))
        shadowOf(Looper.getMainLooper()).idle()
        val popupBlocked = assertNotNull(dispatchedTransport?.webView)
        assertTrue(shadowOf(popupBlocked).webViewClient.shouldOverrideUrlLoading(popupBlocked, fakeRequest("mailto:test@example.com")))
        assertEquals(listOf("mailto:test@example.com"), blocked)
    }
}
