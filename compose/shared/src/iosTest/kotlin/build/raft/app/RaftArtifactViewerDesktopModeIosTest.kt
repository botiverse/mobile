package build.raft.app

import kotlin.test.Test
import kotlin.test.assertTrue

/** iOS offers the Artifact viewer's Desktop Mode: WKWebView widens through the injected viewport meta. */
class RaftArtifactViewerDesktopModeIosTest {
    @Test
    fun desktopModeIsOfferedOnIosThroughTheViewportMeta() {
        assertTrue(raftWebViewDesktopModeViaViewportMeta)
        assertTrue(raftArtifactViewerOffersDesktopMode(finePointer = false, windowWidthDp = 390f))
        assertTrue(raftArtifactViewerOffersDesktopMode(finePointer = false, windowWidthDp = 1024f))
        // The viewer injects the guarded width=1280 meta on iOS too.
        val script = raftArtifactViewerInjectedScript("https://app.raft.build", "", desktopMode = true)
        assertTrue("width=$RaftFilePreviewDesktopViewportWidth" in script)
    }
}
