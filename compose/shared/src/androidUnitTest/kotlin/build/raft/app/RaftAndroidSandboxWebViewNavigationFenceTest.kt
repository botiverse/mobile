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
 * Replaces the source-text regexes of `scripts/tests/feedback-host-scripts.test.mjs` case 5.
 */
@RunWith(RobolectricTestRunner::class)
class RaftAndroidSandboxWebViewNavigationFenceTest {
    private val trusted = "https://example.test/feedback/?embed=raft-settings-v1"
    private val blocked = mutableListOf<String>()
    private val events = RaftWebViewEvents().apply { onNavigationBlocked = { blocked += it } }
    private val controller = RaftWebViewController()

    private fun host(allowNavigation: Boolean): WebView {
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
                injectedJavaScript = "",
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
    fun disallowedSchemesAreBlockedInSubframesAndEvenWhenNavigationIsAllowed() {
        val fenced = host(allowNavigation = false)
        assertTrue(fenced.overrides("javascript:alert(1)", mainFrame = false))
        assertTrue(fenced.overrides("intent://scan/#Intent;scheme=zxing;end", mainFrame = false))

        val open = host(allowNavigation = true)
        assertFalse(open.overrides("https://other.test/anything"))
        assertTrue(open.overrides("http://other.test/anything"))
        assertTrue(open.overrides("file:///android_asset/index.html"))
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
    fun hostThatAllowsNavigationDeliversRuntimeScriptsToSameOriginRoutes() {
        val webView = host(allowNavigation = true)
        webView.finishLoading(trusted)

        shadowOf(webView).webViewClient.doUpdateVisitedHistory(webView, "https://example.test/account/", false)
        controller.evaluateJavaScript("window.__onRoute = 1")
        assertEquals("window.__onRoute = 1", shadowOf(webView).lastEvaluatedJavascript)

        // Cross-origin documents never receive runtime scripts.
        shadowOf(webView).webViewClient.doUpdateVisitedHistory(webView, "https://evil.test/", false)
        controller.evaluateJavaScript("window.__onEvil = 1")
        assertEquals("window.__onRoute = 1", shadowOf(webView).lastEvaluatedJavascript)
    }
}
