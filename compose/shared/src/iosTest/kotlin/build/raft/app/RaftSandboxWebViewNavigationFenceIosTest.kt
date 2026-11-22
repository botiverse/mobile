@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app

import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.UIKit.UIView
import platform.WebKit.WKUserScript
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
}
