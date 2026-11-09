package build.raft.app

import kotlin.test.Test
import kotlin.test.assertTrue

/** Android keeps the Artifact viewer's Desktop Mode (viewport-meta path) on touch screens narrower than a desktop. */
class RaftArtifactViewerDesktopModeAndroidTest {
    @Test
    fun desktopModeIsOfferedOnAndroidPhonesAndTablets() {
        assertTrue(raftArtifactViewerOffersDesktopMode(finePointer = false, windowWidthDp = 390f))
        assertTrue(raftArtifactViewerOffersDesktopMode(finePointer = false, windowWidthDp = 1024f))
    }
}
