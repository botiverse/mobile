package build.raft.designsystem

import build.raft.designsystem.motion.RaftMotion
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftMotionTest {
    @Test
    fun fadeThroughSplitsItsTimeBetweenTheTwoPages() {
        assertEquals(105, RaftMotion.FadeThroughOutgoingMillis)
        assertEquals(195, RaftMotion.FadeThroughIncomingMillis)
        assertEquals(RaftMotion.FadeThroughMillis, RaftMotion.FadeThroughOutgoingMillis + RaftMotion.FadeThroughIncomingMillis)
    }
}
