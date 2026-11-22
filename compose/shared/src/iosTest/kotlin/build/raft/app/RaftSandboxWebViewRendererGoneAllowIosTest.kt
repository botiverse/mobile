@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app

import build.raft.app.data.ArtifactLinkTarget
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIView
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #4828 review: the real iOS host's allow check for the page a WebView rebuilt after its web
 * content process was gone loads ([RaftIosSandboxWebView.rendererGoneRestoreAllows], the check
 * `contentProcessDidTerminate` hands [raftWebViewRendererGoneRestoreUrl]). iOS counterpart of
 * RaftAndroidSandboxWebViewRendererGoneAllowTest.
 */
class RaftSandboxWebViewRendererGoneAllowIosTest {
    private val web = "https://app.raft.build"
    private val id = "pt_c82e597f-5434-4741-909b-d1c8a7a531c8"
    private val warm = raftArtifactViewerWarmUrl(web)
    private val shown = raftArtifactViewerEmbeddedPageUrl(web, ArtifactLinkTarget(id, 3))
    private val hosts = mutableListOf<RaftIosSandboxWebView>()

    @AfterTest
    fun disposeHosts() {
        hosts.forEach { it.dispose() }
    }

    private fun host(src: String, allowNavigation: Boolean, mainFrameOrigin: String?): RaftIosSandboxWebView {
        val host = RaftIosSandboxWebView(UIView(frame = CGRectMake(0.0, 0.0, 320.0, 480.0)), RaftWebViewEvents())
        hosts += host
        host.applyConfig(
            RaftWebViewConfig(
                url = src,
                allowScripts = true,
                allowNavigation = allowNavigation,
                domStorageEnabled = true,
                useWideViewPort = false,
                cacheEnabled = false,
                ephemeral = true,
                injectedJavaScript = "",
                allowedNavigationSchemes = setOf("http", "https"),
                hostEventsEnabled = false,
                mainFrameOrigin = mainFrameOrigin,
            )
        )
        return host
    }

    @Test
    fun theViewerRestoresTheShownArtifactOnItsOriginOnly() {
        val viewer = host(warm, allowNavigation = true, mainFrameOrigin = web)
        assertTrue(viewer.rendererGoneRestoreAllows(shown))
        assertEquals(shown, raftWebViewRendererGoneRestoreUrl(warm, shown, viewer::rendererGoneRestoreAllows))
        assertFalse(viewer.rendererGoneRestoreAllows("https://evil.example/artifacts/$id"))
        assertFalse(viewer.rendererGoneRestoreAllows("http://app.raft.build/artifacts/$id"))
        assertFalse(viewer.rendererGoneRestoreAllows("ftp://app.raft.build/artifacts/$id"))
        assertEquals(warm, raftWebViewRendererGoneRestoreUrl(warm, "https://evil.example/artifacts/$id", viewer::rendererGoneRestoreAllows))
    }
}
