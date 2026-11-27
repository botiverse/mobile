package build.raft.webcontainer

import android.app.Activity
import android.content.MutableContextWrapper
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.webkit.TestWebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
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

/**
 * Regression tests for Android `:web-container` quick sign-in interception (Hands f5424436):
 * - Path A: initial launch URL (`https://stamp.build/`) records itself as the first chain hop so
 *   both `shouldOverrideUrlLoading(isRedirect=true)` and `onPageStarted` deliver
 *   `starter = Launch` + `priorHops = ["https://stamp.build/"]` to `catchesMainFrameNavigation`
 *   without triggering a 15s watchdog `load_stall_timeout`.
 * - Path B (intermediate redirect & direct link): once `catchesMainFrameNavigation` returns `true`
 *   for `api.raft.build/api/oauth/authorize`, trailing `doUpdateVisitedHistory`, `onPageStarted`,
 *   `onPageCommitVisible`, `onPageFinished`, `onReceivedError`, and `shouldInterceptRequest`
 *   callbacks are suppressed so the host never observes `api.raft.build` in
 *   `onNavigationStateChange` (which would launch the system browser and kill the native sign-in
 *   sheet with `hidden_page`), receives no spurious `onLoadCommit`, triggers no 15s stall timeout,
 *   and still allows subsequent SPA `doUpdateVisitedHistory` updates on the committed page.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebContainerAndroidSignInInterceptionTest {
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
        initialUrl: String,
        events: WebContainerEvents,
        requestHeaderRule: WebRequestHeaderRule? = null,
    ): WebContainerAndroidView {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        val host =
            WebContainerAndroidView(
                context = MutableContextWrapper(activity),
                initialConfig =
                    WebContainerConfig(
                        url = initialUrl,
                        allowScripts = true,
                        navigationPolicy =
                            WebNavigationPolicy(
                                allowNavigation = true,
                                allowedSchemes = setOf("http", "https"),
                                allowNewWindows = false,
                                requestHeaderRule = requestHeaderRule,
                            ),
                    ),
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
        return host
    }

    private fun fakeRequest(
        url: String,
        isForMainFrame: Boolean = true,
        hasGesture: Boolean = false,
        isRedirect: Boolean = false,
        method: String = "GET",
    ): WebResourceRequest =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)
            override fun isForMainFrame(): Boolean = isForMainFrame
            override fun isRedirect(): Boolean = isRedirect
            override fun hasGesture(): Boolean = hasGesture
            override fun getMethod(): String = method
            override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
        }

    @Test
    fun initialLaunchRedirectCarriesLaunchStarterAndInitialHopIntoCatchHookWithoutStall() {
        val caught = mutableListOf<WebMainFrameInterceptContext>()
        val blocked = mutableListOf<String>()
        val observedStateUrls = mutableListOf<String>()
        val watchdogErrors = mutableListOf<String>()
        val authorizeUrl = "https://api.raft.build/api/oauth/authorize?client_id=stamp&redirect_uri=https%3A%2F%2Fstamp.build%2Fcb"

        val host =
            createAttachedHost(
                initialUrl = "https://stamp.build/",
                events =
                    WebContainerEvents(
                        onNavigationStateChange = { observedStateUrls += it.url },
                        onNavigationBlocked = { url, _ -> blocked += url },
                        onWatchdogTerminalError = { watchdogErrors += it },
                        catchesMainFrameNavigation = { ctx ->
                            if (ctx.url.startsWith("https://api.raft.build/api/oauth/authorize")) {
                                caught += ctx
                                true
                            } else {
                                false
                            }
                        },
                        routesMainFrameNavigation = { url -> url.startsWith("https://api.raft.build/") },
                    ),
            )
        val webView = host.getChildAt(0) as WebView
        val client = shadowOf(webView).webViewClient

        // 1) Initial load starts on https://stamp.build/ (must deduplicate against initialHop) and
        //    redirects 303 to /sign-in on the same origin.
        client.onPageStarted(webView, "https://stamp.build/", null)
        assertFalse(
            client.shouldOverrideUrlLoading(
                webView,
                fakeRequest("https://stamp.build/sign-in", isRedirect = true),
            ),
        )
        // 2) Second redirect to api.raft.build/api/oauth/authorize delivered via onPageStarted (no prior override):
        //    must still carry starter=Launch and deduplicated priorHops=["https://stamp.build/", "https://stamp.build/sign-in"].
        client.onPageStarted(webView, authorizeUrl, null)

        assertEquals(1, caught.size)
        assertEquals(WebNavigationStarter.Launch, caught.single().starter)
        assertEquals(listOf("https://stamp.build/", "https://stamp.build/sign-in"), caught.single().priorHops)
        assertTrue(blocked.isEmpty(), "Intercepted authorize URL must not fall through to onNavigationBlocked")
        assertFalse(
            observedStateUrls.any { it.startsWith("https://api.raft.build/") },
            "Intercepted authorize URL must never leak into onNavigationStateChange",
        )

        // 3) Watchdog must cancel cleanly on host interception (no 15s load_stall_timeout).
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(17))
        assertTrue(watchdogErrors.isEmpty(), "Host-cancelled redirect must not trigger watchdog stall error: $watchdogErrors")
    }

    @Test
    fun hostCancelledIntermediateRedirectSuppressesCallbacksWithoutBlockingLaterSpaUpdatesOrStalling() {
        val caught = mutableListOf<WebMainFrameInterceptContext>()
        val observedStateUrls = mutableListOf<String>()
        val observedUrlChanges = mutableListOf<String>()
        val watchdogErrors = mutableListOf<String>()
        var commitCount = 0
        val loadFailures = mutableListOf<String>()
        val signInUrl = "https://stamp.build/sign-in"
        val intermediateUrl = "https://stamp.build/api/auth/raft?agree=1"
        val authorizeUrl = "https://api.raft.build/api/oauth/authorize?client_id=stamp&redirect_uri=https%3A%2F%2Fstamp.build%2Fcb"

        val host =
            createAttachedHost(
                initialUrl = signInUrl,
                events =
                    WebContainerEvents(
                        onNavigationStateChange = { observedStateUrls += it.url },
                        onUrlChange = { observedUrlChanges += it },
                        onLoadCommit = { commitCount += 1 },
                        onLoadFailure = { loadFailures += it },
                        onWatchdogTerminalError = { watchdogErrors += it },
                        catchesMainFrameNavigation = { ctx ->
                            if (ctx.url == authorizeUrl) {
                                caught += ctx
                                true
                            } else {
                                false
                            }
                        },
                        routesMainFrameNavigation = { url -> url.startsWith("https://api.raft.build/") },
                    ),
            )
        val webView = host.getChildAt(0) as WebView
        val client = shadowOf(webView).webViewClient

        // Commit https://stamp.build/sign-in first.
        client.onPageStarted(webView, signInUrl, null)
        client.onPageCommitVisible(webView, signInUrl)
        client.onPageFinished(webView, signInUrl)
        val commitsAfterSignInPage = commitCount
        assertEquals(1, commitsAfterSignInPage)

        // User taps "Sign in with Raft": page navigates to intermediate URL, which redirects 302 to authorizeUrl.
        assertFalse(client.shouldOverrideUrlLoading(webView, fakeRequest(intermediateUrl, hasGesture = true)))
        client.onPageStarted(webView, intermediateUrl, null)
        assertTrue(client.shouldOverrideUrlLoading(webView, fakeRequest(authorizeUrl, isRedirect = true)))
        assertEquals(1, caught.size)
        assertEquals(WebNavigationStarter.Link, caught.single().starter)
        assertEquals(listOf(intermediateUrl), caught.single().priorHops)

        // Trailing Android WebView callbacks for the cancelled authorizeUrl / aborted intermediate load:
        client.doUpdateVisitedHistory(webView, authorizeUrl, false)
        client.doUpdateVisitedHistory(webView, intermediateUrl, false)
        client.onPageStarted(webView, authorizeUrl, null)
        client.onPageCommitVisible(webView, intermediateUrl)
        client.onPageFinished(webView, intermediateUrl)
        val blockedResp = client.shouldInterceptRequest(webView, fakeRequest(authorizeUrl))
        assertNotNull(blockedResp, "Cancelled authorize URL must be blocked if Chromium still invokes shouldInterceptRequest")
        client.onReceivedError(webView, fakeRequest(authorizeUrl), TestWebResourceError(-1, "ERR_ABORTED"))

        assertEquals(1, caught.size, "Duplicate onPageStarted must not re-invoke catchesMainFrameNavigation")
        assertEquals(commitsAfterSignInPage, commitCount, "Aborted intermediate load must not trigger onLoadCommit")
        assertTrue(loadFailures.isEmpty(), "Cancelled authorize navigation must not surface onLoadFailure")
        assertFalse(
            observedStateUrls.any { it.startsWith("https://api.raft.build/") },
            "doUpdateVisitedHistory / notifyNavigationState must not leak authorizeUrl to onNavigationStateChange",
        )
        assertFalse(
            observedUrlChanges.any { it.startsWith("https://api.raft.build/") },
            "doUpdateVisitedHistory must not leak authorizeUrl to onUrlChange",
        )
        assertEquals(signInUrl, observedStateUrls.last(), "Visible URL must remain on the committed sign-in page")

        // Subsequent SPA history.pushState on the committed page still propagates through doUpdateVisitedHistory.
        val spaStepUrl = "https://stamp.build/sign-in?step=help"
        client.doUpdateVisitedHistory(webView, spaStepUrl, false)
        assertEquals(spaStepUrl, observedStateUrls.last())

        // No 15s watchdog stall timeout after cancelling the intermediate load.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(17))
        assertTrue(watchdogErrors.isEmpty(), "Host-cancelled intermediate load must not trigger watchdog stall error: $watchdogErrors")
    }

    @Test
    fun hostCancelledDirectLinkSuppressesTrailingCallbacksAndKeepsCommittedPageInteractive() {
        val caught = mutableListOf<WebMainFrameInterceptContext>()
        val observedStateUrls = mutableListOf<String>()
        val loadFailures = mutableListOf<String>()
        val signInUrl = "https://stamp.build/sign-in"
        val authorizeUrl = "https://api.raft.build/api/oauth/authorize?client_id=stamp&redirect_uri=https%3A%2F%2Fstamp.build%2Fcb"

        val host =
            createAttachedHost(
                initialUrl = signInUrl,
                events =
                    WebContainerEvents(
                        onNavigationStateChange = { observedStateUrls += it.url },
                        onLoadFailure = { loadFailures += it },
                        catchesMainFrameNavigation = { ctx ->
                            if (ctx.url == authorizeUrl) {
                                caught += ctx
                                true
                            } else {
                                false
                            }
                        },
                        routesMainFrameNavigation = { url -> url.startsWith("https://api.raft.build/") },
                    ),
            )
        val webView = host.getChildAt(0) as WebView
        val client = shadowOf(webView).webViewClient

        client.onPageStarted(webView, signInUrl, null)
        client.onPageCommitVisible(webView, signInUrl)
        client.onPageFinished(webView, signInUrl)

        // Direct link tap from committed page (currentLoading == false) straight to authorizeUrl.
        assertTrue(client.shouldOverrideUrlLoading(webView, fakeRequest(authorizeUrl, hasGesture = true)))
        assertEquals(1, caught.size)
        assertEquals(WebNavigationStarter.Link, caught.single().starter)
        assertTrue(caught.single().priorHops.isEmpty())

        // Trailing callbacks for authorizeUrl are suppressed.
        client.doUpdateVisitedHistory(webView, authorizeUrl, false)
        client.onPageStarted(webView, authorizeUrl, null)
        assertNotNull(client.shouldInterceptRequest(webView, fakeRequest(authorizeUrl)))
        client.onReceivedError(webView, fakeRequest(authorizeUrl), TestWebResourceError(-1, "ERR_ABORTED"))

        assertEquals(1, caught.size)
        assertTrue(loadFailures.isEmpty())
        assertFalse(observedStateUrls.any { it.startsWith("https://api.raft.build/") })
        assertEquals(signInUrl, observedStateUrls.last())

        // Subsequent SPA URL updates on the committed page remain active.
        val spaNextUrl = "https://stamp.build/sign-in#faq"
        client.doUpdateVisitedHistory(webView, spaNextUrl, false)
        assertEquals(spaNextUrl, observedStateUrls.last())
    }

    @Test
    fun speculativeLinkLoadingStartsImmediatelyOnUserLinkClickAndClearsOnHashRedirectOrCompletion() {
        val observedStates = mutableListOf<WebNavigationState>()
        val pageUrl = "https://stamp.build/pulls"
        val nextUrl = "https://stamp.build/pulls/42"
        val intermediateLoginUrl = "https://stamp.build/api/auth/raft"
        val authorizeUrl = "https://api.raft.build/api/oauth/authorize?client_id=stamp&redirect_uri=https%3A%2F%2Fstamp.build%2Fcb"

        val host =
            createAttachedHost(
                initialUrl = pageUrl,
                events =
                    WebContainerEvents(
                        onNavigationStateChange = { observedStates += it },
                        catchesMainFrameNavigation = { ctx -> ctx.url == authorizeUrl },
                        routesMainFrameNavigation = { url -> url.startsWith("https://api.raft.build/") },
                    ),
            )
        val webView = host.getChildAt(0) as WebView
        val client = shadowOf(webView).webViewClient
        val chromeClient = shadowOf(webView).webChromeClient

        client.onPageStarted(webView, pageUrl, null)
        client.onPageCommitVisible(webView, pageUrl)
        client.onPageFinished(webView, pageUrl)
        assertFalse(observedStates.last().isLoading)

        // 1) Fragment-only link tap on the same document must NOT start speculative loading.
        val countBeforeHash = observedStates.size
        assertFalse(client.shouldOverrideUrlLoading(webView, fakeRequest("$pageUrl#comments", hasGesture = true)))
        assertEquals(countBeforeHash, observedStates.size)
        assertFalse(observedStates.last().isLoading)

        // 2) Normal user-gesture link tap immediately marks isLoading=true before onPageStarted fires.
        assertFalse(client.shouldOverrideUrlLoading(webView, fakeRequest(nextUrl, hasGesture = true)))
        assertTrue(observedStates.last().isLoading, "User link tap should immediately show loading state")
        assertEquals(0, observedStates.last().progress)
        client.onPageStarted(webView, nextUrl, null)
        client.onPageFinished(webView, nextUrl)
        assertFalse(observedStates.last().isLoading)
        assertEquals(100, observedStates.last().progress)

        // 3) User link tap whose 302 redirect is intercepted by host BEFORE onPageStarted clears loading cleanly.
        assertFalse(client.shouldOverrideUrlLoading(webView, fakeRequest(intermediateLoginUrl, hasGesture = true)))
        assertTrue(observedStates.last().isLoading)
        assertTrue(client.shouldOverrideUrlLoading(webView, fakeRequest(authorizeUrl, isRedirect = true)))
        assertFalse(observedStates.last().isLoading, "Host-intercepted redirect must clear speculative loading")
        assertEquals(nextUrl, observedStates.last().url)

        // 4) User link tap that finishes without onPageStarted (e.g. HTTP 204 reporting progress=100) clears loading.
        assertFalse(client.shouldOverrideUrlLoading(webView, fakeRequest("https://stamp.build/no-content", hasGesture = true)))
        assertTrue(observedStates.last().isLoading)
        chromeClient.onProgressChanged(webView, 100)
        assertFalse(observedStates.last().isLoading)
    }

    @Test
    fun stampGithubOAuthServerRedirectStaysInWebViewAndStripsRaftHostWhileDirectLinkRoutesOut() {
        val stampOrigin = parseWebContainerOrigin("https://stamp.build")!!
        val blocked = mutableListOf<Pair<String, String>>()
        val settingsUrl = "https://stamp.build/settings"
        val githubStartUrl = "https://stamp.build/auth/github/start"
        val githubAuthorizeUrl = "https://github.com/login/oauth/authorize?client_id=stamp&state=csrf-token"
        val githubSessionUrl = "https://github.com/session"
        val stampCallbackUrl = "https://stamp.build/auth/github/callback?code=gh-code&state=csrf-token"
        val externalRepoUrl = "https://github.com/botiverse/mobile/pull/5955"
        val raftHostUrl = "https://app.raft.build/s/hq/c/general"

        val host =
            createAttachedHost(
                initialUrl = settingsUrl,
                requestHeaderRule =
                    WebRequestHeaderRule(
                        headerName = "Raft-Host",
                        headerValue = "mobile-v1",
                        allowedOrigin = stampOrigin,
                        maxSameOriginReissues = 5,
                    ),
                events =
                    WebContainerEvents(
                        onNavigationBlocked = { url, reason -> blocked += url to reason },
                        routesMainFrameNavigationWithContext = { ctx ->
                            val targetOrigin = parseWebContainerOrigin(ctx.url)
                            val isRaftHost = targetOrigin?.host == "app.raft.build" || targetOrigin?.host == "api.raft.build"
                            if (isRaftHost) {
                                true
                            } else if (ctx.isRedirect) {
                                false
                            } else {
                                parseWebContainerOrigin(ctx.committedDocumentUrl) == stampOrigin && targetOrigin != stampOrigin
                            }
                        },
                    ),
            )
        val webView = host.getChildAt(0) as WebView
        val client = shadowOf(webView).webViewClient

        // 1) Commit https://stamp.build/settings.
        client.onPageStarted(webView, settingsUrl, null)
        client.onPageCommitVisible(webView, settingsUrl)
        client.onPageFinished(webView, settingsUrl)

        // 2) User taps "使用 GitHub 授权登录" -> same-origin /auth/github/start attaches Raft-Host and starts load.
        assertTrue(client.shouldOverrideUrlLoading(webView, fakeRequest(githubStartUrl, hasGesture = true)))
        assertEquals(githubStartUrl, shadowOf(webView).lastLoadedUrl)
        assertEquals(mapOf("Raft-Host" to "mobile-v1"), shadowOf(webView).lastAdditionalHttpHeaders)
        client.onPageStarted(webView, githubStartUrl, null)

        // 3) Stamp responds 302 -> github.com/login/oauth/authorize: must stay in WebView & strip Raft-Host (NOT blocked/routed out!).
        assertTrue(client.shouldOverrideUrlLoading(webView, fakeRequest(githubAuthorizeUrl, isRedirect = true)))
        assertEquals(githubAuthorizeUrl, shadowOf(webView).lastLoadedUrl)
        assertNull(shadowOf(webView).lastAdditionalHttpHeaders, "Cross-origin OAuth redirect must strip Raft-Host")
        assertTrue(blocked.isEmpty(), "OAuth 302 redirect must never route out to external browser: $blocked")

        // 4) Android onPageStarted for the redirected github.com URL must ALSO stay in WebView.
        client.onPageStarted(webView, githubAuthorizeUrl, null)
        assertTrue(blocked.isEmpty(), "onPageStarted for GitHub OAuth redirect must not route out: $blocked")
        client.onPageCommitVisible(webView, githubAuthorizeUrl)
        client.onPageFinished(webView, githubAuthorizeUrl)

        // 5) Follow-up POST on github.com and callback back to stamp.build both stay in the same WebView.
        assertFalse(
            client.shouldOverrideUrlLoading(
                webView,
                fakeRequest(githubSessionUrl, hasGesture = true, isRedirect = false, method = "POST"),
            ),
        )
        assertTrue(blocked.isEmpty())
        assertTrue(
            client.shouldOverrideUrlLoading(
                webView,
                fakeRequest(stampCallbackUrl, isRedirect = true),
            ),
        )
        assertEquals(stampCallbackUrl, shadowOf(webView).lastLoadedUrl)
        assertTrue(blocked.isEmpty())
        client.onPageStarted(webView, stampCallbackUrl, null)
        client.onPageCommitVisible(webView, stampCallbackUrl)
        client.onPageFinished(webView, stampCallbackUrl)

        // 6) Once committed back on stamp.build, a direct cross-origin link click DOES route out cleanly.
        assertTrue(
            client.shouldOverrideUrlLoading(
                webView,
                fakeRequest(externalRepoUrl, hasGesture = true, isRedirect = false),
            ),
        )
        assertEquals(listOf(externalRepoUrl to "routed_externally"), blocked)

        // 7) Server redirects to Raft own hosts are STILL routed out.
        assertTrue(
            client.shouldOverrideUrlLoading(
                webView,
                fakeRequest(raftHostUrl, isRedirect = true),
            ),
        )
        assertEquals(raftHostUrl to "routed_externally", blocked.last())
    }
}
