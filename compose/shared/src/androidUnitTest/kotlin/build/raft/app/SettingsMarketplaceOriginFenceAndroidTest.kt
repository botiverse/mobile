package build.raft.app

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import build.raft.app.settings.settingsWebViewBlockedNavigationUrlOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies Android origin-locked Marketplace WebView navigation: same-origin Raft routes load in
 * place, while off-origin sites (such as third-party App homepages), scheme/port downgrades, and
 * `about:blank` popups are intercepted via `onNavigationBlocked` and filtered cleanly before
 * reaching `RaftLinkNavigator`.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsMarketplaceOriginFenceAndroidTest {
    private val marketplaceUrl = "https://app.raft.build/s/workspace/settings/integrations?embed=raft-settings-v1&shell=host"

    private fun request(url: String, mainFrame: Boolean = true) =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)
            override fun isForMainFrame(): Boolean = mainFrame
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = true
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }

    @Test
    fun originLockedMarketplaceBlocksOffOriginNavigationAndRoutesThroughBlockedFilter() {
        val blocked = mutableListOf<String>()
        val openedExternal = mutableListOf<String>()
        val events =
            RaftWebViewEvents().apply {
                onNavigationBlocked = { url ->
                    blocked += url
                    settingsWebViewBlockedNavigationUrlOrNull(url)?.let { openedExternal += it }
                }
            }
        val host = RaftAndroidSandboxWebView(RuntimeEnvironment.getApplication(), events)
        host.applyConfig(
            RaftWebViewConfig(
                url = marketplaceUrl,
                allowScripts = true,
                allowNavigation = true,
                domStorageEnabled = true,
                useWideViewPort = false,
                cacheEnabled = true,
                ephemeral = false,
                injectedJavaScript = "",
                allowedNavigationSchemes = setOf("http", "https"),
                mainFrameOrigin = raftWebOriginOf(marketplaceUrl),
                hostEventsEnabled = false,
            )
        )
        blocked.clear()
        openedExternal.clear()
        val webView = host.getChildAt(0) as WebView
        val client = shadowOf(webView).webViewClient

        // Same-origin Raft routes stay inside the Apps WebView.
        assertFalse(client.shouldOverrideUrlLoading(webView, request("https://app.raft.build/s/workspace/settings/integrations/stamp")))
        assertEquals(emptyList<String>(), blocked)
        assertEquals(emptyList<String>(), openedExternal)

        // Third-party App homepages and same-host scheme/port downgrades are blocked and routed to link opening.
        assertTrue(client.shouldOverrideUrlLoading(webView, request("https://stamp.build/")))
        assertTrue(client.shouldOverrideUrlLoading(webView, request("http://app.raft.build/s/workspace/settings/integrations")))
        assertTrue(client.shouldOverrideUrlLoading(webView, request("https://app.raft.build:8443/other")))
        assertEquals(
            listOf(
                "https://stamp.build/",
                "http://app.raft.build/s/workspace/settings/integrations",
                "https://app.raft.build:8443/other",
            ),
            openedExternal,
        )

        // Blank popup placeholders (e.g. MCP OAuth window.open("about:blank")) are blocked without opening external links.
        assertTrue(client.shouldOverrideUrlLoading(webView, request("about:blank")))
        assertEquals(4, blocked.size)
        assertEquals(3, openedExternal.size)

        // Allowed-scheme subframes follow the scheme allow-list while disallowed subframe schemes are blocked.
        assertFalse(client.shouldOverrideUrlLoading(webView, request("https://cdn.example.test/embed.html", mainFrame = false)))
        assertTrue(client.shouldOverrideUrlLoading(webView, request("javascript:alert(1)", mainFrame = false)))
        assertEquals(3, openedExternal.size)
    }
}
