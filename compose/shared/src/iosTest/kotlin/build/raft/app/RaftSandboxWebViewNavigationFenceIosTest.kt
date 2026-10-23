@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app

import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UIView
import platform.WebKit.WKFrameInfo
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * iOS counterpart of `RaftAndroidSandboxWebViewNavigationFenceTest`: drives the real
 * [RaftIosSandboxWebView] navigation policy (the code its WKNavigationDelegate calls) with
 * WKNavigationAction stand-ins. A fenced document (allowNavigation = false) may only stay on its
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
        first.decidePolicy(second, FakeNavigationAction(url, mainFrame))

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
        assertFalse(host.decidePolicy(stranger, FakeNavigationAction(trusted, mainFrame = true)))
        assertEquals(emptyList<String>(), blocked)

        // The WebView read back from the view hierarchy is a freshly bridged reference to the
        // host's own WebView; pointer identity must still match it (`===` is not reliable here).
        assertTrue(raftIsSameObjCObject(webView, webView.superview!!.subviews.single()))
        assertFalse(raftIsSameObjCObject(webView, stranger))
        assertFalse(raftIsSameObjCObject(webView, null))
        assertTrue(host.decidePolicy(webView, FakeNavigationAction(trusted, mainFrame = true)))
    }
}

private class FakeFrameInfo(private val isMain: Boolean) : WKFrameInfo() {
    override val mainFrame: Boolean
        get() = isMain
}

private class FakeNavigationAction(url: String, mainFrame: Boolean) : WKNavigationAction() {
    private val fakeRequest = NSURLRequest.requestWithURL(NSURL.URLWithString(url)!!)
    private val fakeFrame = FakeFrameInfo(mainFrame)

    override val request: NSURLRequest
        get() = fakeRequest

    override val targetFrame: WKFrameInfo?
        get() = fakeFrame
}
