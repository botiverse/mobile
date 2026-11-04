package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse

/** iOS does not offer the Artifact viewer's Desktop Mode: its pageZoom path left a blank strip (1.19.2). */
class RaftArtifactViewerDesktopModeIosTest {
    @Test
    fun desktopModeIsNotOfferedOnIos() {
        assertFalse(raftArtifactViewerOffersDesktopMode(finePointer = false, windowWidthDp = 390f))
        assertFalse(raftArtifactViewerOffersDesktopMode(finePointer = false, windowWidthDp = 1024f))
    }
}
