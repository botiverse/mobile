package build.raft.app

import build.raft.app.data.ArtifactLinkTarget
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #300: an Android WebView rebuilt after its renderer was gone reloaded its configured src. For a
 * kept Artifact viewer that src is the warm route (`/artifacts/raft-app-warm`), which the viewer
 * read as leaving its Artifact and closed. The rebuild loads the page that was shown instead.
 */
class RaftWebViewRendererGoneRestoreTest {
    private val web = "https://app.raft.build"
    private val id = "pt_c82e597f-5434-4741-909b-d1c8a7a531c8"
    private val warm = raftArtifactViewerWarmUrl(web)
    private val shown = raftArtifactViewerEmbeddedPageUrl(web, ArtifactLinkTarget(id, 3))
    private val sameOrigin: (String) -> Boolean = { it.startsWith("$web/") }

    @Test
    fun theShownPageComesBackNotTheWarmSrc() {
        assertEquals(shown, raftWebViewRendererGoneRestoreUrl(warm, shown, sameOrigin))
        // And the viewer reads it as staying on its Artifact (the pinned version).
        assertEquals(RaftArtifactViewerNavigation.Stay(3), raftArtifactViewerNavigation(shown, web, id))
    }

    @Test
    fun oneTimeValuesAndTheBootstrapPlaceholderAreNeverReloaded() {
        assertEquals(warm, raftWebViewRendererGoneRestoreUrl(warm, "$web$RAFT_WEBVIEW_BOOTSTRAP_PATH", sameOrigin))
        assertEquals(
            "$web/settings/billing?tab=plan",
            raftWebViewRendererGoneRestoreUrl(warm, "$web/settings/billing?code=abc&tab=plan&state=xyz&access_token=t&ticket=1", sameOrigin),
        )
        assertEquals("$web/connect", raftWebViewRendererGoneRestoreUrl(warm, "$web/connect?handoffCode=h#host=close", sameOrigin))
        // The embed contract's own query stays.
        assertEquals(shown, raftWebViewRendererGoneRestoreUrl(warm, shown, sameOrigin))
    }

    @Test
    fun theSrcWhenTheShownPageCannotLoadThere() {
        for (current in listOf("", "about:blank", "data:text/html,x", "https://evil.example/artifacts/$id")) {
            assertEquals(warm, raftWebViewRendererGoneRestoreUrl(warm, current, sameOrigin), current)
        }
    }
}
