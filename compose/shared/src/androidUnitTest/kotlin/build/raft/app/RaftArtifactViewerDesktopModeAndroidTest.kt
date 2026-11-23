package build.raft.app

import android.webkit.WebView
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Android Artifact viewer Desktop Mode and pinch-to-zoom settings (#proj-workspace:#360). */
@RunWith(RobolectricTestRunner::class)
class RaftArtifactViewerDesktopModeAndroidTest {
    private val webOrigin = "https://raft-app-staging.botiverse.dev"
    private val viewerUrl = "$webOrigin/artifacts/pt_c82e597f-5434-4741-909b-d1c8a7a531c8"
    private val scope = RaftArtifactViewerScope(webOrigin, "https://api-aws-staging.botiverse.dev/api", "user-1", "server-1")

    @Test
    fun desktopModeIsOfferedOnAndroidPhonesAndTablets() {
        assertTrue(raftArtifactViewerOffersDesktopMode(finePointer = false, windowWidthDp = 390f))
        assertTrue(raftArtifactViewerOffersDesktopMode(finePointer = false, windowWidthDp = 1024f))
    }

    @Test
    fun artifactViewerEnablesPinchZoomWithoutButtonsInDefaultAndDesktopMode() {
        val host = RaftAndroidSandboxWebView(RuntimeEnvironment.getApplication(), RaftWebViewEvents())
        val webView = host.getChildAt(0) as WebView

        // Default (non-Desktop) mode: pinch-to-zoom enabled, +/- overlay buttons hidden, no wide viewport.
        host.applyConfig(
            raftArtifactViewerWebViewConfig(
                url = viewerUrl,
                scope = scope,
                injectedJavaScript = raftArtifactViewerInjectedScript(webOrigin, "", desktopMode = false),
                desktopMode = false,
            )
        )
        assertTrue(webView.settings.supportZoom())
        assertTrue(webView.settings.builtInZoomControls)
        assertFalse(webView.settings.displayZoomControls)
        assertFalse(webView.settings.useWideViewPort)
        assertFalse(webView.settings.loadWithOverviewMode)

        // Toggle Desktop Mode ON: wide viewport + overview on, pinch-to-zoom stays on, buttons stay hidden.
        host.applyConfig(
            raftArtifactViewerWebViewConfig(
                url = viewerUrl,
                scope = scope,
                injectedJavaScript = raftArtifactViewerInjectedScript(webOrigin, "", desktopMode = true),
                desktopMode = true,
            )
        )
        assertTrue(webView.settings.supportZoom())
        assertTrue(webView.settings.builtInZoomControls)
        assertFalse(webView.settings.displayZoomControls)
        assertTrue(webView.settings.useWideViewPort)
        assertTrue(webView.settings.loadWithOverviewMode)

        // Toggle Desktop Mode back OFF: wide viewport off, pinch-to-zoom stays on, buttons stay hidden.
        host.applyConfig(
            raftArtifactViewerWebViewConfig(
                url = viewerUrl,
                scope = scope,
                injectedJavaScript = raftArtifactViewerInjectedScript(webOrigin, "", desktopMode = false),
                desktopMode = false,
            )
        )
        assertTrue(webView.settings.supportZoom())
        assertTrue(webView.settings.builtInZoomControls)
        assertFalse(webView.settings.displayZoomControls)
        assertFalse(webView.settings.useWideViewPort)
        assertFalse(webView.settings.loadWithOverviewMode)
    }

    @Test
    fun unrelatedWebViewKeepsBuiltInZoomControlsOffOutsideDesktopMode() {
        val host = RaftAndroidSandboxWebView(RuntimeEnvironment.getApplication(), RaftWebViewEvents())
        val webView = host.getChildAt(0) as WebView
        val baseConfig =
            RaftWebViewConfig(
                url = "https://example.test/page",
                allowScripts = true,
                allowNavigation = true,
                domStorageEnabled = true,
                useWideViewPort = false,
                cacheEnabled = true,
                ephemeral = false,
                injectedJavaScript = "",
                allowedNavigationSchemes = setOf("https"),
                hostEventsEnabled = false,
            )

        host.applyConfig(baseConfig)
        assertFalse(webView.settings.builtInZoomControls)
        assertFalse(webView.settings.displayZoomControls)

        // Desktop mode on an unrelated surface (e.g. HTML file preview) still toggles zoom with useWideViewPort.
        host.applyConfig(baseConfig.copy(useWideViewPort = true))
        assertTrue(webView.settings.supportZoom())
        assertTrue(webView.settings.builtInZoomControls)
        assertFalse(webView.settings.displayZoomControls)

        host.applyConfig(baseConfig.copy(useWideViewPort = false))
        assertFalse(webView.settings.builtInZoomControls)
        assertFalse(webView.settings.displayZoomControls)
    }
}
