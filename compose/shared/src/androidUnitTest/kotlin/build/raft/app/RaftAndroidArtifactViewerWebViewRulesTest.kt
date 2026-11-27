package build.raft.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import build.raft.webcontainer.WebContainerAndroidInsetMode
import java.time.Duration
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
    fun pinchZoomStaysEnabledWithoutButtonsAcrossDesktopModeTogglesAndRendererRecovery() {
        val (host, webView) = host()

        assertTrue(webView.settings.supportZoom())
        assertTrue(webView.settings.builtInZoomControls)
        assertFalse(webView.settings.displayZoomControls)
        assertFalse(webView.settings.useWideViewPort)
        assertFalse(webView.settings.loadWithOverviewMode)

        host.applyConfig(
            raftArtifactViewerWebViewConfig(
                viewerUrl,
                scope,
                raftArtifactViewerInjectedScript(webOrigin, raftArtifactViewerSessionScript(scope, null, null), desktopMode = true),
                desktopMode = true,
            )
        )
        assertTrue(webView.settings.supportZoom())
        assertTrue(webView.settings.builtInZoomControls)
        assertFalse(webView.settings.displayZoomControls)
        assertTrue(webView.settings.useWideViewPort)
        assertTrue(webView.settings.loadWithOverviewMode)

        host.applyConfig(
            raftArtifactViewerWebViewConfig(
                viewerUrl,
                scope,
                raftArtifactViewerInjectedScript(webOrigin, raftArtifactViewerSessionScript(scope, null, null), desktopMode = false),
                desktopMode = false,
            )
        )
        assertTrue(webView.settings.supportZoom())
        assertTrue(webView.settings.builtInZoomControls)
        assertFalse(webView.settings.displayZoomControls)
        assertFalse(webView.settings.useWideViewPort)
        assertFalse(webView.settings.loadWithOverviewMode)

        // Renderer recovery rebuilds a foreground WebView and preserves pinch-to-zoom without buttons.
        val activityController = Robolectric.buildActivity(Activity::class.java).create()
        activityController.get().setContentView(host)
        activityController.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(34))
        assertTrue(shadowOf(webView).webViewClient.onRenderProcessGone(webView, null))
        val recreated = host.getChildAt(0) as WebView
        assertTrue(recreated.settings.supportZoom())
        assertTrue(recreated.settings.builtInZoomControls)
        assertFalse(recreated.settings.displayZoomControls)
    }

    @Test
    fun chromiumMajorVersionParserAndProviderAccessorHandleRealAndMalformedVersions() {
        assertEquals(139, raftWebViewChromiumMajorVersion("139.0.7258.143"))
        assertEquals(144, raftWebViewChromiumMajorVersion(" 144.0.0.1 "))
        assertEquals(145, raftWebViewChromiumMajorVersion("145"))
        assertNull(raftWebViewChromiumMajorVersion(null))
        assertNull(raftWebViewChromiumMajorVersion(""))
        assertNull(raftWebViewChromiumMajorVersion("   "))
        assertNull(raftWebViewChromiumMajorVersion("0.1.2"))
        assertNull(raftWebViewChromiumMajorVersion("-139.0.0"))
        assertNull(raftWebViewChromiumMajorVersion("139a.0.0"))
        assertNull(raftWebViewChromiumMajorVersion("unknown"))

        try {
            RaftAndroidWebViewProvider.resetForTest()
            RaftAndroidWebViewProvider.packageLoader = { "com.google.android.webview" to "144.0.7500.12" }
            val app = RuntimeEnvironment.getApplication()
            assertEquals(144, RaftAndroidWebViewProvider.chromiumMajorVersion(app))
            assertEquals(
                WebContainerAndroidInsetMode.NativeImeAndBottomSystemBars,
                raftAndroidWebViewInsetPolicy(RaftAndroidWebViewProvider.chromiumMajorVersion(app)),
            )
        } finally {
            RaftAndroidWebViewProvider.resetForTest()
        }
    }

    @Test
    fun legacyAndroidWebViewPersistsBackgroundAcrossPaintThemeSwitchAndAppliesInsetModes() {
        val darkCanvas = 0xFF1B1B19.toInt()
        val lightCanvas = 0xFFFAF9F7.toInt()
        val host = RaftAndroidSandboxWebView(RuntimeEnvironment.getApplication(), events)
        val initialConfig =
            raftArtifactViewerWebViewConfig(
                url = viewerUrl,
                scope = scope,
                injectedJavaScript = "",
                backgroundArgb = darkCanvas,
                persistBackgroundAfterPaint = true,
                androidInsetMode = WebContainerAndroidInsetMode.HostHandled,
            )
        host.applyConfig(initialConfig)
        val webView = host.getChildAt(0) as WebView

        assertEquals(darkCanvas, (webView.background as ColorDrawable).color)
        val client = shadowOf(webView).webViewClient
        client.onPageCommitVisible(webView, viewerUrl)
        client.onPageFinished(webView, viewerUrl)
        assertEquals(darkCanvas, (webView.background as ColorDrawable).color)

        // Live theme switch updates the already-painted WebView background immediately.
        host.applyConfig(initialConfig.copy(backgroundArgb = lightCanvas))
        assertEquals(lightCanvas, (webView.background as ColorDrawable).color)

        // Turning off persistence reverts a painted WebView back to default white.
        host.applyConfig(initialConfig.copy(backgroundArgb = darkCanvas, persistBackgroundAfterPaint = false))
        assertEquals(Color.WHITE, (webView.background as ColorDrawable).color)

        val rawInsets =
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 72, 0, 0))
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 72, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 48))
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 48))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 580))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 72, 0, 0))
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 72, 0, 0))
                .build()

        val hostHandled = ViewCompat.dispatchApplyWindowInsets(webView, rawInsets)
        assertEquals(Insets.NONE, hostHandled.getInsets(WindowInsetsCompat.Type.ime()))
        assertEquals(Insets.NONE, hostHandled.getInsets(WindowInsetsCompat.Type.systemBars()))

        host.applyConfig(initialConfig.copy(androidInsetMode = WebContainerAndroidInsetMode.NativeImeHostNavBar))
        val imeOnly = ViewCompat.dispatchApplyWindowInsets(webView, rawInsets)
        assertEquals(Insets.of(0, 0, 0, 580), imeOnly.getInsets(WindowInsetsCompat.Type.ime()))
        assertEquals(Insets.NONE, imeOnly.getInsets(WindowInsetsCompat.Type.systemBars()))

        host.applyConfig(initialConfig.copy(androidInsetMode = WebContainerAndroidInsetMode.NativeImeAndBottomSystemBars))
        val imeAndBottom = ViewCompat.dispatchApplyWindowInsets(webView, rawInsets)
        assertEquals(Insets.of(0, 0, 0, 580), imeAndBottom.getInsets(WindowInsetsCompat.Type.ime()))
        assertEquals(Insets.NONE, imeAndBottom.getInsets(WindowInsetsCompat.Type.statusBars()))
        assertEquals(Insets.of(0, 0, 0, 48), imeAndBottom.getInsets(WindowInsetsCompat.Type.navigationBars()))
    }
}
