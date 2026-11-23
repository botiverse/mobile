@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app

import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIView
import platform.WebKit.WKUserScript
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** iOS Artifact viewer Desktop Mode and pinch-to-zoom configuration (#proj-workspace:#360). */
class RaftArtifactViewerDesktopModeIosTest {
    private val webOrigin = "https://raft-app-staging.botiverse.dev"
    private val viewerUrl = "$webOrigin/artifacts/pt_c82e597f-5434-4741-909b-d1c8a7a531c8"
    private val scope = RaftArtifactViewerScope(webOrigin, "https://api-aws-staging.botiverse.dev/api", "user-1", "server-1")
    private val hosts = mutableListOf<RaftIosSandboxWebView>()

    @AfterTest
    fun disposeHosts() {
        hosts.forEach { it.dispose() }
    }

    @Test
    fun desktopModeIsOfferedOnIosThroughTheViewportMeta() {
        assertTrue(raftWebViewDesktopModeViaViewportMeta)
        assertTrue(raftArtifactViewerOffersDesktopMode(finePointer = false, windowWidthDp = 390f))
        assertTrue(raftArtifactViewerOffersDesktopMode(finePointer = false, windowWidthDp = 1024f))
        // The viewer injects the guarded width=1280 meta on iOS too.
        val script = raftArtifactViewerInjectedScript("https://app.raft.build", "", desktopMode = true)
        assertTrue("width=$RaftFilePreviewDesktopViewportWidth" in script)
    }

    @Test
    fun iosConfigurationHonorsIgnoresViewportScaleLimitsAndSpareEligibility() {
        assertFalse(raftIosWebViewConfiguration(ephemeral = false, allowScripts = true).ignoresViewportScaleLimits)
        assertTrue(
            raftIosWebViewConfiguration(
                ephemeral = false,
                allowScripts = true,
                ignoresViewportScaleLimits = true,
            ).ignoresViewportScaleLimits
        )
        // Only default-scale persistent scripted WebViews take the prewarmed spare.
        assertTrue(raftIosCanUseSpareWebView(ephemeral = false, allowScripts = true, allowPinchZoom = false, hasExistingWebView = false))
        assertFalse(raftIosCanUseSpareWebView(ephemeral = false, allowScripts = true, allowPinchZoom = true, hasExistingWebView = false))
        assertFalse(raftIosCanUseSpareWebView(ephemeral = true, allowScripts = true, allowPinchZoom = false, hasExistingWebView = false))
        assertFalse(raftIosCanUseSpareWebView(ephemeral = false, allowScripts = false, allowPinchZoom = false, hasExistingWebView = false))
        assertFalse(raftIosCanUseSpareWebView(ephemeral = false, allowScripts = true, allowPinchZoom = false, hasExistingWebView = true))
    }

    @Test
    fun artifactViewerEnablesIgnoresViewportScaleLimitsAcrossDefaultAndDesktopMode() {
        val container = UIView(frame = CGRectMake(0.0, 0.0, 390.0, 844.0))
        val host = RaftIosSandboxWebView(container, RaftWebViewEvents())
        hosts += host

        // Default (non-Desktop) mode: ignoresViewportScaleLimits is on, no desktop viewport meta script.
        val defaultConfig =
            raftArtifactViewerWebViewConfig(
                url = viewerUrl,
                scope = scope,
                injectedJavaScript = raftArtifactViewerInjectedScript(webOrigin, "", desktopMode = false),
                desktopMode = false,
            )
        assertTrue(defaultConfig.allowPinchZoom)
        assertFalse(defaultConfig.useWideViewPort)
        host.applyConfig(defaultConfig.copy(ephemeral = true, cacheEnabled = false))
        val defaultWebView = assertIs<RaftIosWebView>(container.subviews.single())
        assertTrue(defaultWebView.configuration.ignoresViewportScaleLimits)
        assertFalse(
            defaultWebView.configuration.userContentController.userScripts
                .filterIsInstance<WKUserScript>()
                .any { "width=$RaftFilePreviewDesktopViewportWidth" in it.source }
        )

        // Toggle Desktop Mode ON: rebuilds with width=1280 viewport meta script and keeps ignoresViewportScaleLimits on.
        val desktopConfig =
            raftArtifactViewerWebViewConfig(
                url = viewerUrl,
                scope = scope,
                injectedJavaScript = raftArtifactViewerInjectedScript(webOrigin, "", desktopMode = true),
                desktopMode = true,
            )
        assertTrue(desktopConfig.allowPinchZoom)
        assertTrue(desktopConfig.useWideViewPort)
        host.applyConfig(desktopConfig.copy(ephemeral = true, cacheEnabled = false))
        val desktopWebView = assertIs<RaftIosWebView>(container.subviews.single())
        assertTrue(desktopWebView.configuration.ignoresViewportScaleLimits)
        assertTrue(
            desktopWebView.configuration.userContentController.userScripts
                .filterIsInstance<WKUserScript>()
                .any { "width=$RaftFilePreviewDesktopViewportWidth" in it.source }
        )

        // Toggle Desktop Mode back OFF: rebuilds without desktop meta script and keeps ignoresViewportScaleLimits on.
        host.applyConfig(defaultConfig.copy(ephemeral = true, cacheEnabled = false))
        val backToDefaultWebView = assertIs<RaftIosWebView>(container.subviews.single())
        assertTrue(backToDefaultWebView.configuration.ignoresViewportScaleLimits)
        assertFalse(
            backToDefaultWebView.configuration.userContentController.userScripts
                .filterIsInstance<WKUserScript>()
                .any { "width=$RaftFilePreviewDesktopViewportWidth" in it.source }
        )
    }

    @Test
    fun unrelatedWebViewKeepsIgnoresViewportScaleLimitsOff() {
        val container = UIView(frame = CGRectMake(0.0, 0.0, 390.0, 844.0))
        val host = RaftIosSandboxWebView(container, RaftWebViewEvents())
        hosts += host
        host.applyConfig(
            RaftWebViewConfig(
                url = "https://example.test/page",
                allowScripts = true,
                allowNavigation = true,
                domStorageEnabled = true,
                useWideViewPort = false,
                cacheEnabled = true,
                ephemeral = true,
                injectedJavaScript = "",
                allowedNavigationSchemes = setOf("https"),
                hostEventsEnabled = false,
            )
        )
        val webView = assertIs<RaftIosWebView>(container.subviews.single())
        assertFalse(webView.configuration.ignoresViewportScaleLimits)
    }
}
