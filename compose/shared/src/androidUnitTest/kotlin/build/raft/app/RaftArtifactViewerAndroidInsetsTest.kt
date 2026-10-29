package build.raft.app

import kotlin.test.Test
import kotlin.test.assertTrue

/** Android's WebView is padded by its host (edge-to-edge window: no resize, no safe-area inset). */
class RaftArtifactViewerAndroidInsetsTest {
    @Test
    fun androidHostsPadTheWebView() = assertTrue(raftWebViewNeedsHostBottomInsets)
}
