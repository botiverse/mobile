@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app

import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.UIKit.UIView
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun callbacksFromAnotherWebViewAreIgnoredButTheHostsOwnWebViewIsRecognised() {
        val (host, webView) = host(allowNavigation = true)
        val stranger = WKWebView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0), configuration = WKWebViewConfiguration())

        // A stale/foreign WebView is cancelled silently, even for the trusted URL.
        assertFalse(host.decideNavigationPolicy(stranger, NSURL.URLWithString(trusted), isMainFrame = true))
        assertEquals(emptyList<String>(), blocked)

        // The WebView read back from the view hierarchy is a freshly bridged reference to the
        // host's own WebView; pointer identity must still match it (`===` is not reliable here).
        assertTrue(raftIsSameObjCObject(webView, webView.superview!!.subviews.single()))
        assertFalse(raftIsSameObjCObject(webView, stranger))
        assertFalse(raftIsSameObjCObject(webView, null))
        assertTrue(host.decideNavigationPolicy(webView, NSURL.URLWithString(trusted), isMainFrame = true))
    }

    @Test
    fun mainFrameLinkRoutingCancelsFullDocumentLoadAndHandsUrlToBlockedCallback() {
        val shownId = "pt_c82e597f-5434-4741-909b-d1c8a7a531c8"
        val otherId = "pt_0b6d3c1e-9a2f-4c7e-8d11-2f3a4b5c6d7e"
        val webOrigin = "https://example.test"
        events.routesMainFrameNavigation = { url -> raftArtifactViewerRoutesDocumentAsLink(url, webOrigin, shownId, emptySet()) }
        val open = host(allowNavigation = true)

        val bareOther = "$webOrigin/artifacts/$otherId"
        val bareSame = "$webOrigin/artifacts/$shownId"
        val embeddedSame = "$webOrigin/artifacts/$shownId?${raftArtifactViewerEmbedQuery()}"

        assertFalse(open.allows(bareOther))
        assertFalse(open.allows(bareSame))
        assertEquals(listOf(bareOther, bareSame), blocked)

        blocked.clear()
        assertTrue(open.allows(embeddedSame))
        assertEquals(emptyList<String>(), blocked)
    }

    @Test
    fun hostInitiatedRebuildLoadBypassesLinkRoutingOnceWhileSubsequentPageClicksStillRoute() {
        val originalId = "pt_c82e597f-5434-4741-909b-d1c8a7a531c8"
        val switchedId = "pt_0b6d3c1e-9a2f-4c7e-8d11-2f3a4b5c6d7e"
        val webOrigin = "https://example.test"
        val embeddedOriginal = "$webOrigin/artifacts/$originalId?${raftArtifactViewerEmbedQuery()}"
        val embeddedSwitched = "$webOrigin/artifacts/$switchedId?${raftArtifactViewerEmbedQuery()}"

        val container = UIView(frame = CGRectMake(0.0, 0.0, 320.0, 480.0))
        val host = RaftIosSandboxWebView(container, events)
        hosts += host
        val baseConfig =
            RaftWebViewConfig(
                url = embeddedOriginal,
                allowScripts = true,
                allowNavigation = true,
                domStorageEnabled = true,
                useWideViewPort = false,
                cacheEnabled = false,
                ephemeral = true,
                injectedJavaScript = "/* token-1 */",
                allowedNavigationSchemes = setOf("https"),
                hostEventsEnabled = false,
                mainFrameOrigin = webOrigin,
            )
        host.applyConfig(baseConfig)
        val firstWebView = container.subviews.single() as WKWebView
        assertTrue(host.decideNavigationPolicy(firstWebView, NSURL.URLWithString(embeddedOriginal), isMainFrame = true))

        // After switching in place to switchedId, the viewer routes navigations to originalId as links.
        events.routesMainFrameNavigation = { url -> raftArtifactViewerRoutesDocumentAsLink(url, webOrigin, switchedId, emptySet()) }

        // A config rebuild (e.g. token refresh or Desktop Mode) whose load falls back to src must not
        // cancel its own loadRequest; a second navigation to originalId (page link click) still routes.
        host.applyConfig(baseConfig.copy(injectedJavaScript = "/* token-2 */"))
        val rebuiltWebView = container.subviews.single() as WKWebView
        assertTrue(host.decideNavigationPolicy(rebuiltWebView, NSURL.URLWithString(embeddedOriginal), isMainFrame = true))
        assertEquals(emptyList<String>(), blocked)

        assertFalse(host.decideNavigationPolicy(rebuiltWebView, NSURL.URLWithString(embeddedOriginal), isMainFrame = true))
        assertEquals(listOf(embeddedOriginal), blocked)

        // When the WebView had navigated to embeddedSwitched before the rebuild, the rebuild restores
        // embeddedSwitched instead of reverting to embeddedOriginal and lets its own load through.
        blocked.clear()
        assertTrue(host.decideNavigationPolicy(rebuiltWebView, NSURL.URLWithString(embeddedSwitched), isMainFrame = true))
        host.applyConfig(baseConfig.copy(injectedJavaScript = "/* token-3 */"))
        val restoredWebView = container.subviews.single() as WKWebView
        assertTrue(host.decideNavigationPolicy(restoredWebView, NSURL.URLWithString(embeddedSwitched), isMainFrame = true))
        assertEquals(emptyList<String>(), blocked)
    }
}
