package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The WebView's load progress is logged at 25 / 50 / 75 / 100 only, each once per load. */
class RaftSandboxWebViewDiagnosticsTest {
    @Test
    fun progressMilestonesAreLoggedOnceEach() {
        assertNull(raftWebViewProgressMilestone(10, logged = 0))
        assertEquals(25, raftWebViewProgressMilestone(30, logged = 0))
        assertNull(raftWebViewProgressMilestone(40, logged = 25))
        assertEquals(75, raftWebViewProgressMilestone(80, logged = 25))
        assertEquals(100, raftWebViewProgressMilestone(100, logged = 75))
        assertNull(raftWebViewProgressMilestone(100, logged = 100))
    }
}
