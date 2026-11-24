package build.raft.app

import android.net.Uri
import android.os.Looper
import android.webkit.TestWebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    @Test
    fun caughtSignInNavigationSwallowsSpuriousPageStartedAndErrorCallbacks() {
        val authorizeUrl = "https://api.raft.build/api/oauth/authorize?client_id=raft-billing-admin"
        val observedUrls = mutableListOf<String>()
        val loadFailures = mutableListOf<String>()
        events.catchesMainFrameNavigation = { ask -> ask.url == authorizeUrl }
        events.routesMainFrameNavigation = { candidate -> candidate.startsWith("https://api.raft.build/") }
        events.onNavigationStateChange = { state -> observedUrls += state.url }
        events.onLoadFailure = { reason -> loadFailures += reason }
        val webView = host(allowNavigation = true)
        observedUrls.clear()
        blocked.clear()
        loadFailures.clear()

        // Step 1: shouldOverrideUrlLoading catches the OAuth authorize redirect for native Log in with Raft.
        assertTrue(webView.overrides(authorizeUrl))

        // Step 2: Vendor WebViews (e.g. Huawei WebView 114) may still emit onPageStarted / onReceivedError
        // for the cancelled redirect. Neither may pollute currentUrl, open external browser, or fail the page.
        shadowOf(webView).webViewClient.onPageStarted(webView, authorizeUrl, null)
        shadowOf(webView).webViewClient.onReceivedError(webView, request(authorizeUrl, mainFrame = true), null)

        assertEquals(emptyList<String>(), blocked, "caught authorize must not fall through to external browser")
        assertEquals(emptyList<String>(), loadFailures, "cancelled authorize must not surface load error")
        assertFalse(observedUrls.any { it == authorizeUrl }, "cancelled authorize must not overwrite currentUrl")
    }

    @Test
    fun onPageStartedInvokesCatchesBeforeRoutesStopsLoadingAndDeduplicatesOverrideCatch() {
        val stampOrigin = "https://stamp.build"
        val stampPulls = "$stampOrigin/pulls"
        val stampLogin = "$stampOrigin/auth/raft/login"
        val authorizeUrl =
            "https://api.raft.build/api/oauth/authorize?client_id=stamp&redirect_uri=https%3A%2F%2Fstamp.build%2Fauth%2Fcallback"
        val caughtAsks = mutableListOf<RaftWebViewMainFrameCatchAsk>()
        val routedUrls = mutableListOf<String>()
        val observedUrls = mutableListOf<String>()
        val loadFailures = mutableListOf<String>()
        var loadSuccessCount = 0
        val signInEvents =
            RaftWebViewEvents().apply {
                onNavigationBlocked = { blocked += it }
                catchesMainFrameNavigation = { ask ->
                    caughtAsks += ask
                    ask.url == authorizeUrl
                }
                routesMainFrameNavigation = { candidate ->
                    routedUrls += candidate
                    candidate.startsWith("https://api.raft.build/")
                }
                onNavigationStateChange = { state -> observedUrls += state.url }
                onLoadFailure = { reason -> loadFailures += reason }
                onLoadSuccess = { loadSuccessCount += 1 }
            }
        val sandboxHost = RaftAndroidSandboxWebView(RuntimeEnvironment.getApplication(), signInEvents)
        sandboxHost.applyConfig(
            RaftWebViewConfig(
                url = stampPulls,
                allowScripts = true,
                allowNavigation = true,
                domStorageEnabled = true,
                useWideViewPort = false,
                cacheEnabled = true,
                ephemeral = false,
                injectedJavaScript = "",
                allowedNavigationSchemes = setOf("http", "https"),
                hostEventsEnabled = false,
                raftHost = RaftHostHeader(origin = stampOrigin, value = "android"),
            ),
        )
        val webView = sandboxHost.getChildAt(0) as WebView
        val client = shadowOf(webView).webViewClient
        webView.finishLoading(stampPulls)
        caughtAsks.clear()
        routedUrls.clear()
        observedUrls.clear()
        blocked.clear()
        loadFailures.clear()
        loadSuccessCount = 0

        // Step 1: User submits Stamp consent GET form -> allowed natively (false) so Chromium keeps
        // Sec-Fetch-Site: same-origin, then onPageStarted begins loading stampLogin.
        assertFalse(client.shouldOverrideUrlLoading(webView, request(stampLogin, mainFrame = true)))
        assertEquals(1, caughtAsks.size)
        caughtAsks.clear()
        routedUrls.clear()
        client.onPageStarted(webView, stampLogin, null)
        caughtAsks.clear()
        routedUrls.clear()
        observedUrls.clear()

        // Step 2: Older Android WebView (e.g. Huawei WebView 114) follows the 302 directly to onPageStarted
        // without calling shouldOverrideUrlLoading for authorizeUrl. catchesMainFrameNavigation MUST run first.
        client.onPageStarted(webView, authorizeUrl, null)

        assertEquals(
            listOf(
                RaftWebViewMainFrameCatchAsk(
                    url = authorizeUrl,
                    starter = RaftSignInStarter.Page,
                    page = stampPulls,
                    committed = true,
                    hops = listOf(stampLogin),
                ),
            ),
            caughtAsks,
        )
        assertEquals(emptyList<String>(), routedUrls, "routesMainFrameNavigation must not run after catch")
        assertEquals(emptyList<String>(), blocked, "caught authorize must not escape to external browser")
        assertFalse(observedUrls.any { it == authorizeUrl }, "caught authorize must not overwrite currentUrl")

        // Step 3: Trailing callbacks for the cancelled authorizeUrl OR aborted intermediate stampLogin
        // must be swallowed without re-catching, failing, or firing onLoadSuccess/commit.
        client.onPageStarted(webView, authorizeUrl, null)
        client.onReceivedError(webView, request(authorizeUrl, mainFrame = true), null)
        client.onPageCommitVisible(webView, authorizeUrl)
        client.onPageFinished(webView, authorizeUrl)
        client.onPageFinished(webView, stampLogin)
        client.onPageCommitVisible(webView, stampLogin)

        assertEquals(1, caughtAsks.size, "duplicate onPageStarted within fence must not invoke catch twice")
        assertEquals(emptyList<String>(), routedUrls)
        assertEquals(emptyList<String>(), blocked)
        assertEquals(emptyList<String>(), loadFailures)
        assertFalse(sandboxHost.mainFrameLoadFailed)
        assertEquals(0, loadSuccessCount, "cancelled authorize must not fire onLoadSuccess/commit")

        // Step 4: When shouldOverrideUrlLoading DOES catch a new authorizeUrl first, the subsequent
        // onPageStarted for that URL must be swallowed without invoking catchesMainFrameNavigation a second time.
        val secondAuthorize = "$authorizeUrl&state=s2"
        signInEvents.catchesMainFrameNavigation = { ask ->
            caughtAsks += ask
            ask.url == secondAuthorize
        }
        caughtAsks.clear()
        assertTrue(client.shouldOverrideUrlLoading(webView, request(secondAuthorize, mainFrame = true)))
        client.onPageStarted(webView, secondAuthorize, null)
        assertEquals(1, caughtAsks.size, "override + pageStarted for same URL must invoke catch once")
        assertEquals(emptyList<String>(), routedUrls)
        assertEquals(emptyList<String>(), blocked)
    }

    @Test
    fun mainFrameLoadErrorPoisonsKeptDocumentAndDropsParkedKeepAliveView() {
        val app = RuntimeEnvironment.getApplication()
        val warmOrigin = "https://app.raft.build"
        val warmUrl = "$warmOrigin/artifacts/raft-app-warm?embed=1"
        val keepAliveKey = "artifact:test:poison"
        val warmConfig =
            RaftWebViewConfig(
                url = warmUrl,
                allowScripts = true,
                allowNavigation = true,
                domStorageEnabled = true,
                useWideViewPort = false,
                cacheEnabled = true,
                ephemeral = false,
                injectedJavaScript = "",
                allowedNavigationSchemes = setOf("https"),
                hostEventsEnabled = false,
                mainFrameOrigin = warmOrigin,
                keepAliveKey = keepAliveKey,
                diagnosticsSurface = "artifact_viewer",
            )
        RaftAndroidWebViewKeepAlive.drop("test_setup")
        try {
            // Part A: Non-parked view receives main-frame onReceivedError followed by Chromium error page
            // onPageCommitVisible + onPageFinished -> must stay failed, never set loaded, refuse park().
            var loadSuccesses = 0
            val loadFailures = mutableListOf<String>()
            val activeEvents =
                RaftWebViewEvents().apply {
                    onLoadSuccess = { loadSuccesses += 1 }
                    onLoadFailure = { loadFailures += it }
                }
            val activeController = RaftWebViewController()
            val activeHost = RaftAndroidSandboxWebView(app, activeEvents)
            activeHost.applyConfig(warmConfig)
            activeHost.bindController(activeController)
            val activeWebView = activeHost.getChildAt(0) as WebView
            val activeClient = shadowOf(activeWebView).webViewClient

            val timeoutError = TestWebResourceError(WebViewClient.ERROR_TIMEOUT, "net::ERR_TIMED_OUT")
            activeClient.onPageStarted(activeWebView, warmUrl, null)
            activeClient.onReceivedError(activeWebView, request(warmUrl, mainFrame = true), timeoutError)
            activeClient.onPageCommitVisible(activeWebView, warmUrl)
            activeClient.onPageFinished(activeWebView, warmUrl)

            assertTrue(activeHost.mainFrameLoadFailed)
            assertEquals(0, loadSuccesses, "error page commit/finish must never fire onLoadSuccess")
            assertEquals(listOf("net::ERR_TIMED_OUT"), loadFailures)
            assertNull(activeHost.keptDocument(), "failed view must return null keptDocument()")
            assertFalse(activeHost.evaluateInTopDocument(warmOrigin, "window.__nav = 1"))
            assertFalse(RaftAndroidWebViewKeepAlive.park(activeHost), "keepAlive.park() must refuse a failed view")
            assertNull(RaftAndroidWebViewKeepAlive.kept(keepAliveKey))

            // Reload clears mainFrameLoadFailed and allows a healthy page to load and park normally.
            activeController.reload()
            activeClient.onPageStarted(activeWebView, warmUrl, null)
            activeClient.onPageCommitVisible(activeWebView, warmUrl)
            activeClient.onPageFinished(activeWebView, warmUrl)

            assertFalse(activeHost.mainFrameLoadFailed)
            assertEquals(1, loadSuccesses)
            assertEquals(true, activeHost.keptDocument()?.loaded)
            assertTrue(RaftAndroidWebViewKeepAlive.park(activeHost))
            assertNotNull(RaftAndroidWebViewKeepAlive.kept(keepAliveKey))
            RaftAndroidWebViewKeepAlive.drop("test_reset_part_a")

            // Part B: Prewarmed parked view hits net::ERR_TIMED_OUT -> dropped & destroyed immediately.
            RaftAndroidWebViewKeepAlive.prewarm(app, warmConfig)
            val parkedView = assertNotNull(RaftAndroidWebViewKeepAlive.kept(keepAliveKey))
            assertFalse(parkedView.isDestroyed)
            assertFalse(parkedView.mainFrameLoadFailed)
            // dropIfSame for a different instance must leave the parked view untouched.
            val unrelatedView = RaftAndroidSandboxWebView(app, RaftWebViewEvents())
            RaftAndroidWebViewKeepAlive.dropIfSame(unrelatedView, "load_error")
            assertNotNull(RaftAndroidWebViewKeepAlive.kept(keepAliveKey))
            assertFalse(parkedView.isDestroyed)
            unrelatedView.destroy()

            val parkedWebView = parkedView.getChildAt(0) as WebView
            val parkedClient = shadowOf(parkedWebView).webViewClient

            parkedClient.onPageStarted(parkedWebView, warmUrl, null)
            parkedClient.onReceivedError(parkedWebView, request(warmUrl, mainFrame = true), timeoutError)
            // Even if Chromium emits error page commit/finish callbacks afterwards, view is already dropped.
            parkedClient.onPageCommitVisible(parkedWebView, warmUrl)
            parkedClient.onPageFinished(parkedWebView, warmUrl)

            assertTrue(parkedView.mainFrameLoadFailed)
            assertTrue(parkedView.isDestroyed, "parked view must be destroyed on main-frame load error")
            assertNull(parkedView.keptDocument())
            assertNull(RaftAndroidWebViewKeepAlive.kept(keepAliveKey))
            assertNull(raftWebViewKeptDocument(keepAliveKey))
            assertNull(RaftAndroidWebViewKeepAlive.take(keepAliveKey, app, RaftWebViewEvents()))

            // Part C: Main-frame HTTP >= 400 drops parked view; subresource HTTP error does not; SSL error drops it.
            RaftAndroidWebViewKeepAlive.prewarm(app, warmConfig)
            val httpErrView = assertNotNull(RaftAndroidWebViewKeepAlive.kept(keepAliveKey))
            val httpErrWebView = httpErrView.getChildAt(0) as WebView
            shadowOf(httpErrWebView).webViewClient.onReceivedHttpError(
                httpErrWebView,
                request(warmUrl, mainFrame = true),
                WebResourceResponse("text/html", "utf-8", 502, "Bad Gateway", emptyMap(), ByteArrayInputStream(ByteArray(0))),
            )
            assertTrue(httpErrView.mainFrameLoadFailed)
            assertTrue(httpErrView.isDestroyed)
            assertNull(RaftAndroidWebViewKeepAlive.kept(keepAliveKey))

            RaftAndroidWebViewKeepAlive.prewarm(app, warmConfig)
            val sslErrView = assertNotNull(RaftAndroidWebViewKeepAlive.kept(keepAliveKey))
            val sslErrWebView = sslErrView.getChildAt(0) as WebView
            // Subresource 404 must NOT poison the parked main document.
            shadowOf(sslErrWebView).webViewClient.onReceivedHttpError(
                sslErrWebView,
                request("$warmOrigin/favicon.ico", mainFrame = false),
                WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0))),
            )
            assertFalse(sslErrView.mainFrameLoadFailed)
            assertFalse(sslErrView.isDestroyed)
            assertNotNull(RaftAndroidWebViewKeepAlive.kept(keepAliveKey))

            // Main-frame SSL error drops and destroys the parked view.
            shadowOf(sslErrWebView).webViewClient.onReceivedSslError(sslErrWebView, null, null)
            assertTrue(sslErrView.mainFrameLoadFailed)
            assertTrue(sslErrView.isDestroyed)
            assertNull(RaftAndroidWebViewKeepAlive.kept(keepAliveKey))
        } finally {
            RaftAndroidWebViewKeepAlive.drop("test_cleanup")
        }
    }
}
