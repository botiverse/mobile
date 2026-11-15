package build.raft.app

import build.raft.app.data.ArtifactLinkTarget
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #4828 review: the real Android host's allow check for the page a WebView rebuilt after its
 * renderer was gone loads ([RaftAndroidSandboxWebView.rendererGoneRestoreAllows], the check
 * `onRenderProcessGone` hands [raftWebViewRendererGoneRestoreUrl]). The shared test
 * (RaftWebViewRendererGoneRestoreTest) passes its own lambda; this one drives the host's fence.
 */
@RunWith(RobolectricTestRunner::class)
class RaftAndroidSandboxWebViewRendererGoneAllowTest {
    private val web = "https://app.raft.build"
    private val id = "pt_c82e597f-5434-4741-909b-d1c8a7a531c8"
    private val warm = raftArtifactViewerWarmUrl(web)
    private val shown = raftArtifactViewerEmbeddedPageUrl(web, ArtifactLinkTarget(id, 3))

    private fun host(src: String, allowNavigation: Boolean, mainFrameOrigin: String?): RaftAndroidSandboxWebView =
        RaftAndroidSandboxWebView(RuntimeEnvironment.getApplication(), RaftWebViewEvents()).apply {
            applyConfig(
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
        }

    @Test
    fun theViewerRestoresTheShownArtifactOnItsOriginOnly() {
        // The Artifact viewer: navigation allowed, locked to the web origin, booted on the warm route.
        val viewer = host(warm, allowNavigation = true, mainFrameOrigin = web)
        assertTrue(viewer.rendererGoneRestoreAllows(shown))
        assertEquals(shown, raftWebViewRendererGoneRestoreUrl(warm, shown, viewer::rendererGoneRestoreAllows))
        // Off the origin lock, another scheme on the same host, or a scheme the host refuses: the src.
        assertFalse(viewer.rendererGoneRestoreAllows("https://evil.example/artifacts/$id"))
        assertFalse(viewer.rendererGoneRestoreAllows("http://app.raft.build/artifacts/$id"))
        assertFalse(viewer.rendererGoneRestoreAllows("ftp://app.raft.build/artifacts/$id"))
        assertEquals(warm, raftWebViewRendererGoneRestoreUrl(warm, "https://evil.example/artifacts/$id", viewer::rendererGoneRestoreAllows))
    }

    @Test
    fun withoutAnOriginLockOnlyTheSrcsOriginComesBack() {
        // The document-start script is scoped to the src's origin: another one never loads in its place.
        val open = host("$web/settings", allowNavigation = true, mainFrameOrigin = null)
        assertTrue(open.rendererGoneRestoreAllows("$web/settings/billing"))
        assertFalse(open.rendererGoneRestoreAllows("https://other.example/settings/billing"))
    }

    @Test
    fun aFencedDocumentComesBackOnlyAsItsOwnMainDocument() {
        val src = "$web/feedback/?embed=raft-settings-v1"
        val fenced = host(src, allowNavigation = false, mainFrameOrigin = null)
        assertTrue(fenced.rendererGoneRestoreAllows(src))
        assertFalse(fenced.rendererGoneRestoreAllows("$web/account/"))
        assertEquals(src, raftWebViewRendererGoneRestoreUrl(src, "$web/account/", fenced::rendererGoneRestoreAllows))
    }
}
