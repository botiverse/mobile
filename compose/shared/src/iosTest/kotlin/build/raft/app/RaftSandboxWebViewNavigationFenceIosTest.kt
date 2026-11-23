@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app

import platform.CoreGraphics.CGRectMake
import platform.Foundation.*
import platform.UIKit.UIView
import platform.WebKit.WKUserScript
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * iOS counterpart of `RaftAndroidSandboxWebViewNavigationFenceTest`: drives the real
 * [RaftIosSandboxWebView] navigation fence (what its WKNavigationDelegate decides with) against
 * the host's own WKWebView. A fenced document (allowNavigation = false) may only stay on its
 * exact main document; disallowed schemes are blocked in every frame; callbacks from a WebView
 * that is not the host's current one are ignored.
 */
class RaftSandboxWebViewNavigationFenceIosTest {
    private val trusted = "https://example.test/feedback/?embed=raft-settings-v1"
    private val blocked = mutableListOf<String>()
    private val events = RaftWebViewEvents().apply { onNavigationBlocked = { blocked += it } }
    private val hosts = mutableListOf<RaftIosSandboxWebView>()

    @AfterTest
    fun disposeHosts() {
        hosts.forEach { it.dispose() }
    }

    private fun host(allowNavigation: Boolean): Pair<RaftIosSandboxWebView, WKWebView> {
        val container = UIView(frame = CGRectMake(0.0, 0.0, 320.0, 480.0))
        val host = RaftIosSandboxWebView(container, events)
        hosts += host
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
        blocked.clear()
        val webView = container.subviews.single() as WKWebView
        return host to webView
    }

    /** True when the host lets the navigation proceed (WKNavigationActionPolicyAllow). */
    private fun Pair<RaftIosSandboxWebView, WKWebView>.allows(url: String, mainFrame: Boolean = true): Boolean =
        first.decideNavigationPolicy(second, NSURL.URLWithString(url), mainFrame)

    @Test
    fun fencedHostBlocksMainFrameNavigationAwayFromTheTrustedDocument() {
        val fenced = host(allowNavigation = false)

        assertFalse(fenced.allows("https://evil.test/feedback/?embed=raft-settings-v1"))
        assertFalse(fenced.allows("https://example.test/account/"))
        assertFalse(fenced.allows("https://example.test/feedback/?embed=other"))
        assertFalse(fenced.allows("http://example.test/feedback/?embed=raft-settings-v1"))
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
        assertTrue(fenced.allows(trusted))
        assertTrue(fenced.allows("$trusted#draft"))
        assertEquals(emptyList<String>(), blocked)
    }

    @Test
    fun subframesFollowOnlyTheSchemeAllowListAndOpenHostsStillBlockOtherSchemes() {
        val fenced = host(allowNavigation = false)
        // Subframes are not held to the main document, only to the scheme allow-list.
        assertTrue(fenced.allows("https://cdn.example.test/embed.html", mainFrame = false))

        val open = host(allowNavigation = true)
        assertTrue(open.allows("https://other.test/anything"))
        assertFalse(open.allows("http://other.test/anything"))
        assertFalse(open.allows("file:///private/var/index.html"))
        assertFalse(open.allows("javascript:alert(1)"))
        assertEquals(
            listOf("http://other.test/anything", "file:///private/var/index.html", "javascript:alert(1)"),
            blocked,
        )
    }

    @Test
    fun injectedUserScriptTargetsMainFrameOnlyEvenOnUnlockedHosts() {
        val container = UIView(frame = CGRectMake(0.0, 0.0, 320.0, 480.0))
        val host = RaftIosSandboxWebView(container, events)
        hosts += host
        val tokenScript = "/* settings-auth-script */"
        host.applyConfig(
            RaftWebViewConfig(
                url = trusted,
                allowScripts = true,
                allowNavigation = true,
                domStorageEnabled = true,
                useWideViewPort = false,
                cacheEnabled = false,
                ephemeral = true,
                injectedJavaScript = tokenScript,
                allowedNavigationSchemes = setOf("https"),
                hostEventsEnabled = false,
                mainFrameOrigin = null,
            )
        )
        val webView = container.subviews.single() as WKWebView
        val scripts = webView.configuration.userContentController.userScripts.filterIsInstance<WKUserScript>()
        val injected = scripts.single { it.source == tokenScript }
        assertTrue(injected.forMainFrameOnly)
    }

    @Test
    fun newWindowActionCancelsWithoutMutatingUrlAndReportsBlockedOnce() {
        val urlChanges = mutableListOf<String>()
        events.onUrlChange = { urlChanges += it }
        val (host, webView) = host(allowNavigation = true)
        urlChanges.clear()
        blocked.clear()

        val externalTarget = "https://third-party.example/app"
        assertFalse(
            host.decideNavigationAction(
                source = webView,
                targetURL = NSURL.URLWithString(externalTarget),
                isNewWindow = true,
                isMainFrame = false,
            )
        )
        assertEquals(listOf(externalTarget), blocked)
        assertEquals(emptyList<String>(), urlChanges)

        // Same-origin target="_blank" also routes through onNavigationBlocked without mutating URL.
        blocked.clear()
        val sameOriginTarget = "https://example.test/other"
        assertFalse(
            host.decideNavigationAction(
                source = webView,
                targetURL = NSURL.URLWithString(sameOriginTarget),
                isNewWindow = true,
                isMainFrame = false,
            )
        )
        assertEquals(listOf(sameOriginTarget), blocked)
        assertEquals(emptyList<String>(), urlChanges)

        // Foreign WebView new-window action is silently rejected.
        blocked.clear()
        val stranger = WKWebView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0), configuration = WKWebViewConfiguration())
        assertFalse(
            host.decideNavigationAction(
                source = stranger,
                targetURL = NSURL.URLWithString(externalTarget),
                isNewWindow = true,
                isMainFrame = false,
            )
        )
        assertEquals(emptyList<String>(), blocked)
        assertEquals(emptyList<String>(), urlChanges)
    }

    @Test
    fun raftIosBuildRequestAttachesHeaderOnlyOnScopedOrigin() {
        val raftHost = RaftHostHeader(origin = "https://app.example", value = "ios")
        val onScope =
            raftIosBuildRequest(
                url = NSURL.URLWithString("https://app.example/home")!!,
                cacheEnabled = false,
                raftHost = raftHost,
            )
        assertEquals("ios", onScope.valueForHTTPHeaderField(RAFT_HOST_HEADER))

        val stripped =
            raftIosBuildRequest(
                url = NSURL.URLWithString("https://app.example/home")!!,
                cacheEnabled = false,
                raftHost = raftHost,
                includeHeader = false,
            )
        assertNull(stripped.valueForHTTPHeaderField(RAFT_HOST_HEADER))

        val offScope =
            raftIosBuildRequest(
                url = NSURL.URLWithString("https://other.example/home")!!,
                cacheEnabled = false,
                raftHost = raftHost,
            )
        assertNull(offScope.valueForHTTPHeaderField(RAFT_HOST_HEADER))

        val noHost =
            raftIosBuildRequest(
                url = NSURL.URLWithString("https://app.example/home")!!,
                cacheEnabled = false,
                raftHost = null,
            )
        assertNull(noHost.valueForHTTPHeaderField(RAFT_HOST_HEADER))
    }

    @Test
    fun decideMainFramePolicyReissuesScopedLinksStripsCrossOriginAndResetsBudget() {
        val appOrigin = "https://app.example"
        val startUrl = "$appOrigin/start"
        val container = UIView(frame = CGRectMake(0.0, 0.0, 320.0, 480.0))
        val localEvents = RaftWebViewEvents()
        val host = RaftIosSandboxWebView(container, localEvents)
        hosts += host
        host.applyConfig(
            RaftWebViewConfig(
                url = startUrl,
                allowScripts = true,
                allowNavigation = true,
                domStorageEnabled = true,
                useWideViewPort = false,
                cacheEnabled = false,
                ephemeral = false,
                injectedJavaScript = "",
                allowedNavigationSchemes = setOf("https"),
                hostEventsEnabled = false,
                raftHost = RaftHostHeader(origin = appOrigin, value = "ios"),
            )
        )
        val webView = container.subviews.single() as WKWebView

        // Initial load carries Raft-Host -> Allow.
        assertEquals(
            RaftIosMainFramePolicyOutcome.Allow,
            host.decideMainFramePolicy(
                source = webView,
                targetURL = NSURL.URLWithString(startUrl),
                method = "GET",
                rawNavigationType = WK_NAVIGATION_TYPE_OTHER,
                requestCarriesHeader = true,
            ),
        )

        // Same-origin redirect carrying header -> Allow (WebKit preserved Raft-Host).
        assertEquals(
            RaftIosMainFramePolicyOutcome.Allow,
            host.decideMainFramePolicy(
                source = webView,
                targetURL = NSURL.URLWithString("$appOrigin/next"),
                method = "GET",
                rawNavigationType = WK_NAVIGATION_TYPE_OTHER,
                requestCarriesHeader = true,
            ),
        )

        // Cross-origin redirect where WebKit copied Raft-Host -> ReissueWithoutHeader.
        val crossOrigin = "https://third-party.example/bounce"
        assertEquals(
            RaftIosMainFramePolicyOutcome.ReissueWithoutHeader(crossOrigin),
            host.decideMainFramePolicy(
                source = webView,
                targetURL = NSURL.URLWithString(crossOrigin),
                method = "GET",
                rawNavigationType = WK_NAVIGATION_TYPE_OTHER,
                requestCarriesHeader = true,
            ),
        )

        // Commit resets chain; link activation on scoped origin without header -> ReissueWithHeader.
        host.didCommit(webView)
        val linkUrl = "$appOrigin/settings"
        assertEquals(
            RaftIosMainFramePolicyOutcome.ReissueWithHeader(linkUrl),
            host.decideMainFramePolicy(
                source = webView,
                targetURL = NSURL.URLWithString(linkUrl),
                method = "GET",
                rawNavigationType = WK_NAVIGATION_TYPE_LINK_ACTIVATED,
                requestCarriesHeader = false,
            ),
        )

        // POST, Back/Forward, Reload, FormResubmitted never re-issue.
        assertEquals(
            RaftIosMainFramePolicyOutcome.Allow,
            host.decideMainFramePolicy(
                source = webView,
                targetURL = NSURL.URLWithString("$appOrigin/submit"),
                method = "POST",
                rawNavigationType = WK_NAVIGATION_TYPE_FORM_SUBMITTED,
                requestCarriesHeader = false,
            ),
        )
        assertEquals(
            RaftIosMainFramePolicyOutcome.Allow,
            host.decideMainFramePolicy(
                source = webView,
                targetURL = NSURL.URLWithString("$appOrigin/back"),
                method = "GET",
                rawNavigationType = WK_NAVIGATION_TYPE_BACK_FORWARD,
                requestCarriesHeader = false,
            ),
        )
        assertEquals(
            RaftIosMainFramePolicyOutcome.Allow,
            host.decideMainFramePolicy(
                source = webView,
                targetURL = NSURL.URLWithString("$appOrigin/reload"),
                method = "GET",
                rawNavigationType = WK_NAVIGATION_TYPE_RELOAD,
                requestCarriesHeader = false,
            ),
        )
        assertEquals(
            RaftIosMainFramePolicyOutcome.Allow,
            host.decideMainFramePolicy(
                source = webView,
                targetURL = NSURL.URLWithString("$appOrigin/resubmit"),
                method = "GET",
                rawNavigationType = WK_NAVIGATION_TYPE_FORM_RESUBMITTED,
                requestCarriesHeader = false,
            ),
        )

        // Hop budget caps same-origin redirect re-issues at RAFT_HOST_REISSUE_BUDGET and resets on didCommit.
        host.didCommit(webView)
        host.decideMainFramePolicy(
            source = webView,
            targetURL = NSURL.URLWithString("$appOrigin/chain-0"),
            method = "GET",
            rawNavigationType = WK_NAVIGATION_TYPE_LINK_ACTIVATED,
            requestCarriesHeader = true,
        )
        for (i in 1..RAFT_HOST_REISSUE_BUDGET) {
            assertEquals(
                RaftIosMainFramePolicyOutcome.ReissueWithHeader("$appOrigin/chain-$i"),
                host.decideMainFramePolicy(
                    source = webView,
                    targetURL = NSURL.URLWithString("$appOrigin/chain-$i"),
                    method = "GET",
                    rawNavigationType = WK_NAVIGATION_TYPE_OTHER,
                    requestCarriesHeader = false,
                ),
            )
        }
        assertEquals(
            RaftIosMainFramePolicyOutcome.Allow,
            host.decideMainFramePolicy(
                source = webView,
                targetURL = NSURL.URLWithString("$appOrigin/chain-over"),
                method = "GET",
                rawNavigationType = WK_NAVIGATION_TYPE_OTHER,
                requestCarriesHeader = false,
            ),
        )
        host.didCommit(webView)
        assertEquals(
            RaftIosMainFramePolicyOutcome.ReissueWithHeader("$appOrigin/fresh"),
            host.decideMainFramePolicy(
                source = webView,
                targetURL = NSURL.URLWithString("$appOrigin/fresh"),
                method = "GET",
                rawNavigationType = WK_NAVIGATION_TYPE_LINK_ACTIVATED,
                requestCarriesHeader = false,
            ),
        )
    }

    @Test
    fun catchesMainFrameNavigationRunsBeforeRoutingAndFenceWithChainContext() {
        val asks = mutableListOf<RaftWebViewMainFrameCatchAsk>()
        val localBlocked = mutableListOf<String>()
        var routedCalls = 0
        val localEvents =
            RaftWebViewEvents().apply {
                catchesMainFrameNavigation = { ask ->
                    asks += ask
                    ask.url.startsWith("https://raft.example/oauth/authorize")
                }
                routesMainFrameNavigation = {
                    routedCalls += 1
                    true
                }
                onNavigationBlocked = { localBlocked += it }
            }
        val appStart = "https://app.example/start"
        val container = UIView(frame = CGRectMake(0.0, 0.0, 320.0, 480.0))
        val host = RaftIosSandboxWebView(container, localEvents)
        hosts += host
        host.applyConfig(
            RaftWebViewConfig(
                url = appStart,
                allowScripts = true,
                allowNavigation = false,
                domStorageEnabled = true,
                useWideViewPort = false,
                cacheEnabled = false,
                ephemeral = false,
                injectedJavaScript = "",
                allowedNavigationSchemes = setOf("https"),
                hostEventsEnabled = false,
                raftHost = RaftHostHeader(origin = "https://app.example", value = "ios"),
            )
        )
        val webView = container.subviews.single() as WKWebView

        assertEquals(
            RaftIosMainFramePolicyOutcome.Allow,
            host.decideMainFramePolicy(
                source = webView,
                targetURL = NSURL.URLWithString(appStart),
                rawNavigationType = WK_NAVIGATION_TYPE_OTHER,
                requestCarriesHeader = true,
            ),
        )
        val authorizeUrl = "https://raft.example/oauth/authorize?client_id=app1"
        assertEquals(
            RaftIosMainFramePolicyOutcome.Cancel,
            host.decideMainFramePolicy(
                source = webView,
                targetURL = NSURL.URLWithString(authorizeUrl),
                rawNavigationType = WK_NAVIGATION_TYPE_OTHER,
                requestCarriesHeader = true,
            ),
        )
        assertEquals(
            RaftWebViewMainFrameCatchAsk(
                url = authorizeUrl,
                starter = RaftSignInStarter.Launch,
                page = "",
                committed = false,
                hops = listOf(appStart),
            ),
            asks.last(),
        )
        assertEquals(0, routedCalls)
        assertEquals(emptyList<String>(), localBlocked)
    }

    @Test
    fun hostCancelledFailureSwallowsMatching102AndCancelledErrorsOnly() {
        val target = "https://app.example/next"
        assertTrue(
            raftIosShouldSwallowHostCancelledFailure(
                hostCancelledUrl = target,
                errorDomain = "WebKitErrorDomain",
                errorCode = 102L,
                failingUrl = target,
            )
        )
        assertTrue(
            raftIosShouldSwallowHostCancelledFailure(
                hostCancelledUrl = target,
                errorDomain = NSURLErrorDomain,
                errorCode = NSURLErrorCancelled,
                failingUrl = target,
            )
        )
        assertTrue(
            raftIosShouldSwallowHostCancelledFailure(
                hostCancelledUrl = target,
                errorDomain = "WebKitErrorDomain",
                errorCode = 102L,
                failingUrl = null,
            )
        )
        // Must not swallow when no host cancellation is pending, or for other URLs/errors.
        assertFalse(
            raftIosShouldSwallowHostCancelledFailure(
                hostCancelledUrl = null,
                errorDomain = NSURLErrorDomain,
                errorCode = NSURLErrorCancelled,
                failingUrl = target,
            )
        )
        assertFalse(
            raftIosShouldSwallowHostCancelledFailure(
                hostCancelledUrl = target,
                errorDomain = "WebKitErrorDomain",
                errorCode = 102L,
                failingUrl = "https://other.example/unrelated",
            )
        )
        assertFalse(
            raftIosShouldSwallowHostCancelledFailure(
                hostCancelledUrl = target,
                errorDomain = NSURLErrorDomain,
                errorCode = -1009L,
                failingUrl = target,
            )
        )
    }

    @Test
    fun allowsBackForwardNavigationGesturesWiresToWkWebView() {
        val (_, defaultWebView) = host(allowNavigation = true)
        assertFalse(defaultWebView.allowsBackForwardNavigationGestures)

        val container = UIView(frame = CGRectMake(0.0, 0.0, 320.0, 480.0))
        val swipeHost = RaftIosSandboxWebView(container, events)
        hosts += swipeHost
        swipeHost.applyConfig(
            RaftWebViewConfig(
                url = trusted,
                allowScripts = true,
                allowNavigation = true,
                domStorageEnabled = true,
                useWideViewPort = false,
                cacheEnabled = false,
                ephemeral = true,
                injectedJavaScript = "",
                allowedNavigationSchemes = setOf("https"),
                hostEventsEnabled = false,
                allowsBackForwardNavigationGestures = true,
            )
        )
        val swipeWebView = container.subviews.single() as WKWebView
        assertTrue(swipeWebView.allowsBackForwardNavigationGestures)
    }

    @Test
    fun isolatedThirdPartyNeverTakesSpareAndSelectsNamedOrNonPersistentStoreByOsVersion() {
        assertFalse(
            raftIosCanUseSpareWebView(
                ephemeral = false,
                allowScripts = true,
                allowPinchZoom = false,
                hasExistingWebView = false,
                storagePartition = RaftWebViewStoragePartition.IsolatedThirdParty,
            )
        )
        assertTrue(
            raftIosCanUseSpareWebView(
                ephemeral = false,
                allowScripts = true,
                allowPinchZoom = false,
                hasExistingWebView = false,
                storagePartition = RaftWebViewStoragePartition.Default,
            )
        )

        val defaultStore = WKWebsiteDataStore.defaultDataStore()

        val ios17Config =
            raftIosWebViewConfiguration(
                ephemeral = false,
                allowScripts = true,
                storagePartition = RaftWebViewStoragePartition.IsolatedThirdParty,
                osMajorVersion = 17L,
            )
        assertTrue(ios17Config.websiteDataStore.persistent)
        assertFalse(raftIsSameObjCObject(ios17Config.websiteDataStore, defaultStore))

        val ios16Config =
            raftIosWebViewConfiguration(
                ephemeral = false,
                allowScripts = true,
                storagePartition = RaftWebViewStoragePartition.IsolatedThirdParty,
                osMajorVersion = 16L,
            )
        assertFalse(ios16Config.websiteDataStore.persistent)
        assertFalse(raftIsSameObjCObject(ios16Config.websiteDataStore, defaultStore))
    }
}
