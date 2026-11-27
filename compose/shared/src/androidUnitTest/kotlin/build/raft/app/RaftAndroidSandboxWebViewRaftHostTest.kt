package build.raft.app

import android.net.Uri
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives [RaftAndroidSandboxWebView] under Robolectric to verify PR 2 plumbing:
 * - `Raft-Host` header attachment on first load, same-origin GET re-issue, cross-origin stripping,
 *   POST/sub-frame exclusion, and hop budget / commit reset (#proj-workspace:#356);
 * - `catchesMainFrameNavigation` precedence and chain metadata;
 * - non-queued [RaftWebViewController.loadUrl];
 * - `IsolatedThirdParty` Android `MULTI_PROFILE` attachment order and fail-closed behavior (#397).
 */
@RunWith(RobolectricTestRunner::class)
class RaftAndroidSandboxWebViewRaftHostTest {
    private val appOrigin = "https://app.example"
    private val appStartUrl = "$appOrigin/home"
    private val raftHost = RaftHostHeader(origin = appOrigin, value = "android")

    private fun baseConfig(
        url: String = appStartUrl,
        allowNavigation: Boolean = true,
        raftHost: RaftHostHeader? = this.raftHost,
        storagePartition: RaftWebViewStoragePartition? = null,
        ephemeral: Boolean = false,
        allowedNavigationSchemes: Set<String> = setOf("https"),
    ) =
        RaftWebViewConfig(
            url = url,
            allowScripts = true,
            allowNavigation = allowNavigation,
            domStorageEnabled = true,
            useWideViewPort = false,
            cacheEnabled = false,
            ephemeral = ephemeral,
            storagePartition = storagePartition,
            injectedJavaScript = "",
            allowedNavigationSchemes = allowedNavigationSchemes,
            hostEventsEnabled = false,
            raftHost = raftHost,
        )

    private fun request(
        url: String,
        mainFrame: Boolean = true,
        redirect: Boolean = false,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
    ): WebResourceRequest =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)
            override fun isForMainFrame(): Boolean = mainFrame
            override fun isRedirect(): Boolean = redirect
            override fun hasGesture(): Boolean = !redirect
            override fun getMethod(): String = method
            override fun getRequestHeaders(): Map<String, String> = headers
        }

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun firstLoadAttachesRaftHostOnlyOnScopedOrigin() {
        val events = RaftWebViewEvents()
        val host = RaftAndroidSandboxWebView(RuntimeEnvironment.getApplication(), events)
        host.applyConfig(baseConfig(url = appStartUrl, raftHost = raftHost))
        val webView = host.getChildAt(0) as WebView

        assertEquals(appStartUrl, shadowOf(webView).lastLoadedUrl)
        assertEquals(mapOf(RAFT_HOST_HEADER to "android"), shadowOf(webView).lastAdditionalHttpHeaders)

        // Off-origin initial load never carries Raft-Host.
        val offOriginHost = RaftAndroidSandboxWebView(RuntimeEnvironment.getApplication(), events)
        offOriginHost.applyConfig(baseConfig(url = "https://other.example/start", raftHost = raftHost))
        val offOriginWebView = offOriginHost.getChildAt(0) as WebView
        assertEquals("https://other.example/start", shadowOf(offOriginWebView).lastLoadedUrl)
        assertNull(shadowOf(offOriginWebView).lastAdditionalHttpHeaders)

        // Null raftHost never attaches Raft-Host even on the same origin.
        val plainHost = RaftAndroidSandboxWebView(RuntimeEnvironment.getApplication(), events)
        plainHost.applyConfig(baseConfig(url = appStartUrl, raftHost = null))
        val plainWebView = plainHost.getChildAt(0) as WebView
        assertEquals(appStartUrl, shadowOf(plainWebView).lastLoadedUrl)
        assertNull(shadowOf(plainWebView).lastAdditionalHttpHeaders)
    }

    @Test
    fun sameOriginGetRedirectReissuesWithHeaderAndStripsCrossOriginRedirect() {
        val events = RaftWebViewEvents()
        val host = RaftAndroidSandboxWebView(RuntimeEnvironment.getApplication(), events)
        host.applyConfig(baseConfig())
        val webView = host.getChildAt(0) as WebView
        val client = shadowOf(webView).webViewClient

        // Same-origin GET server redirect loses Android's loadUrl header -> re-issued with Raft-Host.
        val sameOriginRedirect = "$appOrigin/dashboard"
        assertTrue(client.shouldOverrideUrlLoading(webView, request(sameOriginRedirect, redirect = true)))
        idleMainLooper()
        assertEquals(sameOriginRedirect, shadowOf(webView).lastLoadedUrl)
        assertEquals(mapOf(RAFT_HOST_HEADER to "android"), shadowOf(webView).lastAdditionalHttpHeaders)

        // Cross-origin GET redirect from that header-bearing chain -> re-issued WITHOUT Raft-Host.
        val crossOriginRedirect = "https://third-party.example/landing"
        assertTrue(client.shouldOverrideUrlLoading(webView, request(crossOriginRedirect, redirect = true)))
        idleMainLooper()
        assertEquals(crossOriginRedirect, shadowOf(webView).lastLoadedUrl)
        assertNull(shadowOf(webView).lastAdditionalHttpHeaders)

        // A further cross-origin redirect after stripping does not loop.
        val secondCrossOrigin = "https://third-party.example/final"
        assertFalse(client.shouldOverrideUrlLoading(webView, request(secondCrossOrigin, redirect = true)))

        // Document-initiated same-origin GET link / form (non-redirect) is allowed natively so
        // Chromium preserves Sec-Fetch-Site: same-origin and Referer (e.g. Stamp /login?agree=1).
        client.onPageFinished(webView, appStartUrl)
        val sameOriginForm = "$appOrigin/login?terms_version=2026-10-20&agree=1"
        assertFalse(client.shouldOverrideUrlLoading(webView, request(sameOriginForm, redirect = false)))
        idleMainLooper()
        assertEquals(crossOriginRedirect, shadowOf(webView).lastLoadedUrl)
    }

    @Test
    fun postAndSubframeRequestsNeverReceiveRaftHost() {
        val events = RaftWebViewEvents()
        val host = RaftAndroidSandboxWebView(RuntimeEnvironment.getApplication(), events)
        host.applyConfig(baseConfig())
        val webView = host.getChildAt(0) as WebView
        val client = shadowOf(webView).webViewClient

        // POST on scoped origin is allowed as-is (never re-issued as GET).
        assertFalse(client.shouldOverrideUrlLoading(webView, request("$appOrigin/submit", method = "POST")))
        idleMainLooper()
        assertEquals(appStartUrl, shadowOf(webView).lastLoadedUrl)

        // Sub-frame request on scoped origin is never re-issued or stamped.
        assertFalse(client.shouldOverrideUrlLoading(webView, request("$appOrigin/iframe", mainFrame = false)))
        idleMainLooper()
        assertEquals(appStartUrl, shadowOf(webView).lastLoadedUrl)
    }

    @Test
    fun reissueBudgetCapsChainAndResetsAfterCommit() {
        val events = RaftWebViewEvents()
        val host = RaftAndroidSandboxWebView(RuntimeEnvironment.getApplication(), events)
        host.applyConfig(baseConfig())
        val webView = host.getChildAt(0) as WebView
        val client = shadowOf(webView).webViewClient

        for (i in 1..RAFT_HOST_REISSUE_BUDGET) {
            assertTrue(
                client.shouldOverrideUrlLoading(webView, request("$appOrigin/hop-$i", redirect = true)),
                "expected re-issue on hop $i",
            )
            idleMainLooper()
        }
        // Budget exhausted: next same-origin redirect proceeds without re-issue.
        assertFalse(client.shouldOverrideUrlLoading(webView, request("$appOrigin/hop-over-budget", redirect = true)))

        // After commit, document-initiated links and post-commit same-origin redirects stay native
        // (never cancelled & re-issued via loadUrl, preserving Sec-Fetch-Site: same-origin).
        client.onPageCommitVisible(webView, "$appOrigin/hop-over-budget")
        assertFalse(client.shouldOverrideUrlLoading(webView, request("$appOrigin/after-commit", redirect = false)))
        assertFalse(client.shouldOverrideUrlLoading(webView, request("$appOrigin/after-commit-redirect", redirect = true)))
        idleMainLooper()
        assertEquals("$appOrigin/hop-$RAFT_HOST_REISSUE_BUDGET", shadowOf(webView).lastLoadedUrl)
    }

    @Test
    fun catchesMainFrameNavigationRunsBeforeRoutingAndFenceWithChainContext() {
        val asks = mutableListOf<RaftWebViewMainFrameCatchAsk>()
        val routed = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        val events =
            RaftWebViewEvents().apply {
                catchesMainFrameNavigation = { ask ->
                    asks += ask
                    ask.url.startsWith("https://raft.example/oauth/authorize")
                }
                routesMainFrameNavigation = { url ->
                    routed += url
                    true
                }
                onNavigationBlocked = { blocked += it }
            }
        val host = RaftAndroidSandboxWebView(RuntimeEnvironment.getApplication(), events)
        // Even with allowNavigation = false and routesMainFrameNavigation returning true, catch wins first.
        host.applyConfig(baseConfig(allowNavigation = false))
        val webView = host.getChildAt(0) as WebView
        val client = shadowOf(webView).webViewClient

        // Server 302 from initial Launch load -> caught with starter=Launch, committed=false, hops=[appStartUrl].
        val authUrl = "https://raft.example/oauth/authorize?client_id=app1"
        assertTrue(client.shouldOverrideUrlLoading(webView, request(authUrl, redirect = true)))
        assertEquals(1, asks.size)
        assertEquals(
            RaftWebViewMainFrameCatchAsk(
                url = authUrl,
                starter = RaftSignInStarter.Launch,
                page = "",
                committed = false,
                hops = listOf(appStartUrl),
            ),
            asks.single(),
        )
        assertEquals(emptyList(), routed)
        assertEquals(emptyList(), blocked)

        // Commit the start page, then a user-initiated GET form/link runs natively (returns false,
        // preserving Sec-Fetch-Site: same-origin) and its 302 to authorize is caught with
        // starter=Page, committed=true, hops=[loginStart]; trailing onPageFinished(loginStart) is
        // swallowed so onLoadSuccess never drops the in-flight sign-in session.
        var loadSuccessCount = 0
        events.onLoadSuccess = { loadSuccessCount += 1 }
        client.onPageFinished(webView, appStartUrl)
        assertEquals(1, loadSuccessCount)
        host.applyConfig(baseConfig(allowNavigation = true))
        events.routesMainFrameNavigation = null
        asks.clear()
        val loginStart = "$appOrigin/login/raft"
        assertFalse(client.shouldOverrideUrlLoading(webView, request(loginStart, redirect = false)))
        client.onPageStarted(webView, loginStart, null)
        assertTrue(client.shouldOverrideUrlLoading(webView, request(authUrl, redirect = true)))
        client.onPageFinished(webView, loginStart)
        client.onPageCommitVisible(webView, loginStart)
        assertEquals(1, loadSuccessCount, "trailing callbacks from host-cancelled redirect chain must not fire onLoadSuccess")
        assertEquals(listOf(loginStart, loginStart, authUrl), asks.map { it.url })
        assertEquals(
            RaftWebViewMainFrameCatchAsk(
                url = authUrl,
                starter = RaftSignInStarter.Page,
                page = appStartUrl,
                committed = true,
                hops = listOf(loginStart),
            ),
            asks.last(),
        )
        // After approval, loading the OAuth callback clears the host-cancelled fence and commits normally.
        val callbackUrl = "$appOrigin/login/raft/callback?code=ok"
        val controller = RaftWebViewController()
        host.bindController(controller)
        assertTrue(controller.loadUrl(callbackUrl))
        client.onPageStarted(webView, callbackUrl, null)
        client.onPageFinished(webView, callbackUrl)
        assertEquals(2, loadSuccessCount, "OAuth callback load after cancelled authorize must commit normally")
    }

    @Test
    fun controllerLoadUrlLoadsImmediatelyWithScopedHeaderAndNeverQueuesAcrossDetach() {
        val events = RaftWebViewEvents()
        val controller = RaftWebViewController()
        assertFalse(controller.loadUrl("$appOrigin/before-attach"))

        val host = RaftAndroidSandboxWebView(RuntimeEnvironment.getApplication(), events)
        host.applyConfig(baseConfig())
        host.bindController(controller)
        val webView = host.getChildAt(0) as WebView
        assertEquals(appStartUrl, shadowOf(webView).lastLoadedUrl)

        val callbackUrl = "$appOrigin/oauth/callback?code=abc&state=123"
        assertTrue(controller.loadUrl(callbackUrl))
        assertEquals(callbackUrl, shadowOf(webView).lastLoadedUrl)
        assertEquals(mapOf(RAFT_HOST_HEADER to "android"), shadowOf(webView).lastAdditionalHttpHeaders)

        val externalUrl = "https://other.example/page"
        assertTrue(controller.loadUrl(externalUrl))
        assertEquals(externalUrl, shadowOf(webView).lastLoadedUrl)
        assertNull(shadowOf(webView).lastAdditionalHttpHeaders)

        // Destroy detaches controller; loadUrl while detached returns false and does not replay on a new host.
        host.destroy()
        assertFalse(controller.loadUrl("$appOrigin/while-detached"))
        val secondHost = RaftAndroidSandboxWebView(RuntimeEnvironment.getApplication(), events)
        secondHost.applyConfig(baseConfig())
        secondHost.bindController(controller)
        val secondWebView = secondHost.getChildAt(0) as WebView
        assertEquals(appStartUrl, shadowOf(secondWebView).lastLoadedUrl)
    }

    @Test
    fun isolatedThirdPartyAppliesProfileBeforeFirstLoadWhenMultiProfileSupported() {
        val events = RaftWebViewEvents()
        val appliedProfiles = mutableListOf<Pair<String, String?>>()
        val host =
            RaftAndroidSandboxWebView(
                context = RuntimeEnvironment.getApplication(),
                events = events,
                initialStoragePartition = RaftWebViewStoragePartition.IsolatedThirdParty,
                supportsMultiProfile = { true },
                applyProfile = { view, name ->
                    // Assert profile is bound before any loadUrl call touches the default profile.
                    appliedProfiles += name to shadowOf(view).lastLoadedUrl
                },
            )
        host.applyConfig(
            baseConfig(
                url = "https://third-party.example/page",
                storagePartition = RaftWebViewStoragePartition.IsolatedThirdParty,
                raftHost = null,
            )
        )
        val webView = host.getChildAt(0) as WebView

        assertEquals(listOf<Pair<String, String?>>(RAFT_ANDROID_THIRD_PARTY_PROFILE_NAME to null), appliedProfiles)
        assertEquals("https://third-party.example/page", shadowOf(webView).lastLoadedUrl)
    }

    @Test
    fun isolatedThirdPartyFailsClosedWithoutLoadingWhenMultiProfileUnsupported() {
        val failures = mutableListOf<String>()
        val events = RaftWebViewEvents().apply { onLoadFailure = { failures += it } }
        var applyProfileCalls = 0
        val controller = RaftWebViewController()
        val host =
            RaftAndroidSandboxWebView(
                context = RuntimeEnvironment.getApplication(),
                events = events,
                initialStoragePartition = RaftWebViewStoragePartition.IsolatedThirdParty,
                supportsMultiProfile = { false },
                applyProfile = { _, _ -> applyProfileCalls += 1 },
            )
        val config =
            baseConfig(
                url = "https://third-party.example/secret",
                storagePartition = RaftWebViewStoragePartition.IsolatedThirdParty,
                raftHost = null,
            )
        host.applyConfig(config)
        host.bindController(controller)
        val webView = host.getChildAt(0) as WebView

        assertEquals(0, applyProfileCalls)
        assertNull(shadowOf(webView).lastLoadedUrl)
        assertEquals(listOf(RAFT_WEBVIEW_ISOLATED_PROFILE_UNAVAILABLE_REASON), failures)

        // Repeated recomposition with the same config must not spam onLoadFailure or call loadUrl.
        host.applyConfig(config)
        assertNull(shadowOf(webView).lastLoadedUrl)
        assertEquals(listOf(RAFT_WEBVIEW_ISOLATED_PROFILE_UNAVAILABLE_REASON), failures)

        // Controller loadUrl and reload also stay fail-closed.
        controller.loadUrl("https://third-party.example/other")
        controller.reload()
        assertNull(shadowOf(webView).lastLoadedUrl)
    }

    @Test
    fun sameOriginReissuePreservesOriginalRefererAndCrossOriginStripDoesNot() {
        val events = RaftWebViewEvents()
        val host = RaftAndroidSandboxWebView(RuntimeEnvironment.getApplication(), events)
        host.applyConfig(baseConfig())
        val webView = host.getChildAt(0) as WebView
        val client = shadowOf(webView).webViewClient

        // Pre-commit same-origin redirect carrying browser Referer preserves it alongside Raft-Host.
        val sameOriginRedirect = "$appOrigin/dashboard"
        assertTrue(
            client.shouldOverrideUrlLoading(
                webView,
                request(
                    url = sameOriginRedirect,
                    redirect = true,
                    headers = mapOf("referer" to appStartUrl),
                ),
            ),
        )
        idleMainLooper()
        assertEquals(sameOriginRedirect, shadowOf(webView).lastLoadedUrl)
        assertEquals(
            linkedMapOf("Referer" to appStartUrl, RAFT_HOST_HEADER to "android"),
            shadowOf(webView).lastAdditionalHttpHeaders,
        )

        // Cross-origin redirect strips Raft-Host and does NOT inject Referer.
        val crossOriginRedirect = "https://third-party.example/landing"
        assertTrue(
            client.shouldOverrideUrlLoading(
                webView,
                request(
                    url = crossOriginRedirect,
                    redirect = true,
                    headers = mapOf("Referer" to sameOriginRedirect),
                ),
            ),
        )
        idleMainLooper()
        assertEquals(crossOriginRedirect, shadowOf(webView).lastLoadedUrl)
        assertNull(shadowOf(webView).lastAdditionalHttpHeaders)
    }
}
