package build.raft.app

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives the real Android sandbox WebView host (its WebViewClient and controller commands) to check
 * the native navigation fence: a fenced document (allowNavigation = false) may only stay on its
 * exact main document, and runtime scripts (auth updates) are only delivered while it does.
 */
@RunWith(RobolectricTestRunner::class)
class RaftAndroidSandboxWebViewNavigationFenceTest {
    private val trusted = "https://example.test/feedback/?embed=raft-settings-v1"
    private val blocked = mutableListOf<String>()
    private val events = RaftWebViewEvents().apply { onNavigationBlocked = { blocked += it } }
    private val controller = RaftWebViewController()

    private fun host(
        allowNavigation: Boolean,
        injectedJavaScript: String = "",
    ): WebView {
        val host = RaftAndroidSandboxWebView(RuntimeEnvironment.getApplication(), events)
        host.applyConfig(
            RaftWebViewConfig(
                url = trusted,
                allowScripts = true,
                allowNavigation = allowNavigation,
                domStorageEnabled = true,
                useWideViewPort = false,
                cacheEnabled = false,
                ephemeral = true,
                injectedJavaScript = injectedJavaScript,
                allowedNavigationSchemes = setOf("https"),
                hostEventsEnabled = false,
            )
        )
        host.bindController(controller)
        blocked.clear()
        return host.getChildAt(0) as WebView
    }

    private fun request(url: String, mainFrame: Boolean) =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)
            override fun isForMainFrame(): Boolean = mainFrame
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = true
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }

    private fun WebView.overrides(url: String, mainFrame: Boolean = true): Boolean =
        shadowOf(this).webViewClient.shouldOverrideUrlLoading(this, request(url, mainFrame))

    private fun WebView.finishLoading(url: String) {
        val client = shadowOf(this).webViewClient
        client.onPageStarted(this, url, null)
        client.onPageFinished(this, url)
    }

    @Test
    fun fencedHostBlocksMainFrameNavigationAwayFromTheTrustedDocument() {
        val webView = host(allowNavigation = false)

        assertTrue(webView.overrides("https://evil.test/feedback/?embed=raft-settings-v1"))
        assertTrue(webView.overrides("https://example.test/account/"))
        assertTrue(webView.overrides("https://example.test/feedback/?embed=other"))
        assertTrue(webView.overrides("http://example.test/feedback/?embed=raft-settings-v1"))
        assertEquals(
            listOf(
                "https://evil.test/feedback/?embed=raft-settings-v1",
                "https://example.test/account/",
                "https://example.test/feedback/?embed=other",
                "http://example.test/feedback/?embed=raft-settings-v1",
            ),
            blocked,
        )

        blocked.clear()
        assertFalse(webView.overrides(trusted))
        assertFalse(webView.overrides("$trusted#draft"))
        assertEquals(emptyList<String>(), blocked)
    }

    @Test
    fun runtimeScriptsReachOnlyTheTrustedDocumentOfAFencedHost() {
        val webView = host(allowNavigation = false)
        webView.finishLoading(trusted)

        assertTrue(controller.evaluateJavaScript("window.__onTrusted = 1"))
        assertEquals("window.__onTrusted = 1", shadowOf(webView).lastEvaluatedJavascript)

        // A same-origin history route is a different document: it must not receive the update.
        shadowOf(webView).webViewClient.doUpdateVisitedHistory(webView, "https://example.test/account/", false)
        controller.evaluateJavaScript("window.__onHistoryRoute = 1")
        assertEquals("window.__onTrusted = 1", shadowOf(webView).lastEvaluatedJavascript)

        // A fragment change stays on the trusted document.
        shadowOf(webView).webViewClient.doUpdateVisitedHistory(webView, "$trusted#draft", false)
        controller.evaluateJavaScript("window.__onFragment = 1")
        assertEquals("window.__onFragment = 1", shadowOf(webView).lastEvaluatedJavascript)
    }

    @Test
    fun unlockedHostNeverEvaluatesInjectedJavaScriptOnOffOriginPageFinished() {
        val tokenScript = "window.localStorage.setItem('slock_access_token', 'secret');"
        val webView = host(allowNavigation = true, injectedJavaScript = tokenScript)
        assertEquals(null, shadowOf(webView).lastEvaluatedJavascript)

        // Navigating to a third-party site (e.g. Stripe checkout or an App's external page) on an
        // unlocked Settings WebView must never evaluate injectedJavaScript on onPageFinished.
        webView.finishLoading("https://evil.test/login")
        assertEquals(null, shadowOf(webView).lastEvaluatedJavascript)

        webView.finishLoading("https://checkout.stripe.com/c/pay/cs_test")
        assertEquals(null, shadowOf(webView).lastEvaluatedJavascript)

        // Same-origin Raft routes still receive the bootstrap script (including explicit default port).
        webView.finishLoading("https://example.test:443/s/workspace/settings/billing")
        assertEquals(tokenScript, shadowOf(webView).lastEvaluatedJavascript)

        // Even a stale same-origin callback cannot evaluate once webView.url has moved off-origin
        // or to a non-http(s) document like about:blank.
        val sentinel = "window.__sentinel = 1"
        webView.evaluateJavascript(sentinel, null)
        assertEquals(sentinel, shadowOf(webView).lastEvaluatedJavascript)

        webView.loadUrl("https://evil.test/next")
        shadowOf(webView).webViewClient.onPageFinished(webView, "https://example.test/s/workspace/settings/billing")
        assertEquals(sentinel, shadowOf(webView).lastEvaluatedJavascript)

        webView.loadUrl("about:blank")
        shadowOf(webView).webViewClient.onPageFinished(webView, "https://example.test/s/workspace/settings/billing")
        assertEquals(sentinel, shadowOf(webView).lastEvaluatedJavascript)
    }

    @Test
    fun unconditionalStopInsideNavigationStateChangeNeverRecurses() {
        val states = mutableListOf<RaftWebViewNavigationState>()
        events.onNavigationStateChange = { state ->
            states += state
            // Reproduce the exact 31d6124b / ce9054d1 trigger: synchronously stopping on every callback.
            if (state.url.startsWith("https://api.raft.build/")) {
                controller.stop()
            }
        }
        val webView = host(allowNavigation = true)
        states.clear()

        shadowOf(webView).webViewClient.onPageStarted(
            webView,
            "https://api.raft.build/api/oauth/authorize?client_id=billing",
            null,
        )

        assertTrue(states.size in 1..2, "expected bounded navigation callbacks, got ${states.size}")
        assertFalse(states.last().isLoading)
    }

    @Test
    fun onPageStartedInterceptsRoutedNavigationAndDeduplicatesRecentOverride() {
        val routedUrl = "https://api.raft.build/api/oauth/authorize?client_id=billing"
        val observedUrls = mutableListOf<String>()
        events.routesMainFrameNavigation = { candidate -> candidate.startsWith("https://api.raft.build/") }
        events.onNavigationStateChange = { state -> observedUrls += state.url }
        val webView = host(allowNavigation = true)
        observedUrls.clear()
        blocked.clear()

        // Case 1: WebView fires onPageStarted directly without prior shouldOverrideUrlLoading (e.g. POST/redirect).
        shadowOf(webView).webViewClient.onPageStarted(webView, routedUrl, null)
        assertEquals(listOf(routedUrl), blocked)
        assertFalse(observedUrls.any { it == routedUrl }, "routed URL must not overwrite currentUrl")

        // Case 2: WebView fires shouldOverrideUrlLoading followed immediately by onPageStarted for the same URL.
        blocked.clear()
        observedUrls.clear()
        assertTrue(webView.overrides(routedUrl))
        shadowOf(webView).webViewClient.onPageStarted(webView, routedUrl, null)
        assertEquals(listOf(routedUrl), blocked, "must not fire onNavigationBlocked twice for same navigation")
        assertFalse(observedUrls.any { it == routedUrl })
    }
}
