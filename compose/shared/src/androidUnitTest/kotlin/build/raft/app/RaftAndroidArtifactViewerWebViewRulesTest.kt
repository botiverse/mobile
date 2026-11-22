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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tenny's WebView rules for the in-app Artifact viewer, on the real Android host
 * ([RaftAndroidSandboxWebView]) with the viewer's own config ([raftArtifactViewerWebViewConfig]):
 * top-level documents stay on the web origin (anything else is blocked and handed out), the
 * content frame loads as a sub-frame, scripts reach only a top-level document of the web origin,
 * and no bridge is installed.
 */
@RunWith(RobolectricTestRunner::class)
class RaftAndroidArtifactViewerWebViewRulesTest {
    private val webOrigin = "https://raft-app-staging.botiverse.dev"
    private val viewerUrl = "$webOrigin/artifacts/pt_c82e597f-5434-4741-909b-d1c8a7a531c8"
    private val contentFrame =
        "https://w-c82e597f-5434-4741-909b-d1c8a7a531c8.w-staging.raftusercontent.com/_raft/open?ticket=rct1.x"
    private val scope = RaftArtifactViewerScope(webOrigin, "https://api-aws-staging.botiverse.dev/api", "user-1", "server-1")
    private val blocked = mutableListOf<String>()
    private val events = RaftWebViewEvents().apply { onNavigationBlocked = { blocked += it } }
    private val controller = RaftWebViewController()

    private fun host(): Pair<RaftAndroidSandboxWebView, WebView> {
        val host = RaftAndroidSandboxWebView(RuntimeEnvironment.getApplication(), events)
        host.applyConfig(raftArtifactViewerWebViewConfig(viewerUrl, scope, raftArtifactViewerSessionScript(scope, null, null)))
        host.bindController(controller)
        blocked.clear()
        return host to (host.getChildAt(0) as WebView)
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

    @Test
    fun topLevelNavigationStaysOnTheWebOriginAndEverythingElseIsHandedOut() {
        val (_, webView) = host()

        // Same origin: the web app's own routes load in place.
        assertFalse(webView.overrides("$webOrigin/artifacts/pt_other/v/2"))
        assertFalse(webView.overrides("$webOrigin/s/northwind/channel/general"))
        assertEquals(emptyList<String>(), blocked)

        // A link out of an Artifact, the content domain as a top-level page, a lookalike host,
        // plain http or another port of the same host: never loaded in the signed-in WebView.
        val elsewhere =
            listOf(
                "https://example.com/launch-notes",
                contentFrame,
                "https://raft-app-staging.botiverse.dev.evil.test/artifacts/pt_x",
                "http://raft-app-staging.botiverse.dev/artifacts/pt_x",
                "https://raft-app-staging.botiverse.dev:8443/artifacts/pt_x",
            )
        elsewhere.forEach { assertTrue(webView.overrides(it), it) }
        assertEquals(elsewhere, blocked)
    }

    @Test
    fun theContentFrameLoadsAsASubFrame() {
        val (_, webView) = host()
        assertFalse(webView.overrides(contentFrame, mainFrame = false))
        assertEquals(emptyList<String>(), blocked)
        // Disallowed schemes stay blocked in sub-frames too.
        assertTrue(webView.overrides("javascript:alert(1)", mainFrame = false))
    }

    @Test
    fun scriptsReachOnlyATopLevelDocumentOfTheWebOrigin() {
        val (host, webView) = host()
        val client = shadowOf(webView).webViewClient
        client.onPageStarted(webView, viewerUrl, null)
        client.onPageFinished(webView, viewerUrl)
        val afterLoad = shadowOf(webView).lastEvaluatedJavascript

        assertTrue(controller.evaluateJavaScript("window.__onViewer = 1"))
        assertEquals("window.__onViewer = 1", shadowOf(webView).lastEvaluatedJavascript)
        assertTrue(host.evaluateInTopDocument(webOrigin, "window.__kept = 1"))
        assertFalse(host.evaluateInTopDocument("https://w-x.w-staging.raftusercontent.com", "window.__content = 1"))
        assertEquals("window.__kept = 1", shadowOf(webView).lastEvaluatedJavascript)
        // The session script that ran on load is the guarded one (rule 1).
        assertTrue(afterLoad.orEmpty().contains("window.top !== window"), afterLoad)
    }

    @Test
    fun theViewerInstallsNoBridge() {
        val config = raftArtifactViewerWebViewConfig(viewerUrl, scope, "")
        assertFalse(config.hostEventsEnabled)
        assertEquals(webOrigin, config.mainFrameOrigin)
        val (_, webView) = host()
        // No `addJavascriptInterface` anywhere: Robolectric records none.
        assertNull(shadowOf(webView).getJavascriptInterface("raftHostPort"))
    }

    @Test
    fun documentLinkRoutingInterceptsUnembeddedArtifactLinksWhileAllowingEmbeddedReloadAndSignOut() {
        val shownId = "pt_c82e597f-5434-4741-909b-d1c8a7a531c8"
        val otherId = "pt_0b6d3c1e-9a2f-4c7e-8d11-2f3a4b5c6d7e"
        events.routesMainFrameNavigation = { url -> raftArtifactViewerRoutesDocumentAsLink(url, webOrigin, shownId, emptySet()) }
        val (_, webView) = host()

        // Bare `<a href="/artifacts/…">` (e.g. Linked from -> Documents) and un-embedded self-links are
        // intercepted so the WebView never replaces its embedded document with a `NOT_EMBEDDED` load.
        val routed =
            listOf(
                "$webOrigin/artifacts/$otherId",
                "$webOrigin/artifacts/$shownId",
                "$webOrigin/artifacts/$shownId/v/2",
            )
        routed.forEach { assertTrue(webView.overrides(it), it) }
        assertEquals(routed, blocked)

        blocked.clear()
        // Embedded reload of this Artifact, sign-out paths, and post-delete server route continue loading.
        val allowed =
            listOf(
                "$webOrigin/artifacts/$shownId?${raftArtifactViewerEmbedQuery()}",
                "$webOrigin/",
                "$webOrigin/login",
                "$webOrigin/s/northwind",
            )
        allowed.forEach { assertFalse(webView.overrides(it), it) }
        assertEquals(emptyList<String>(), blocked)
    }
}
