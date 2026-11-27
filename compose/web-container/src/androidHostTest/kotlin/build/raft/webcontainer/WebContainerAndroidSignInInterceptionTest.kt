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
}
