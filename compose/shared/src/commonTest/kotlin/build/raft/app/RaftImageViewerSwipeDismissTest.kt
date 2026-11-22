package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftImageViewerSwipeDismissTest {
    private val slop = 10f

    @Test
    fun onlyAMostlyVerticalDownwardDragIsClaimed() {
        assertEquals(RaftImageSwipeDismissDecision.Undecided, raftImageSwipeDismissDecision(4f, 9f, slop))
        assertEquals(RaftImageSwipeDismissDecision.Claim, raftImageSwipeDismissDecision(0f, 12f, slop))
        assertEquals(RaftImageSwipeDismissDecision.Claim, raftImageSwipeDismissDecision(-7f, 12f, slop), "a little sideways is fine")
        // Diagonal or sideways: the gallery pager / back swipe gets it. Upward: nothing.
        assertEquals(RaftImageSwipeDismissDecision.Release, raftImageSwipeDismissDecision(10f, 12f, slop))
        assertEquals(RaftImageSwipeDismissDecision.Release, raftImageSwipeDismissDecision(-14f, 3f, slop))
        assertEquals(RaftImageSwipeDismissDecision.Release, raftImageSwipeDismissDecision(0f, -12f, slop))
    }

    @Test
    fun releaseClosesPastTheDistanceOrOnAFastDownwardFling() {
        fun closes(dy: Float, velocity: Float) = raftImageSwipeDismissCloses(dy, velocity, 300f, 2500f)

        assertTrue(closes(dy = 320f, velocity = 0f), "far enough")
        assertTrue(closes(dy = 60f, velocity = 3000f), "fast fling down")
        assertFalse(closes(dy = 200f, velocity = 400f), "short and slow: springs back")
        assertFalse(closes(dy = 200f, velocity = -3000f), "flung back up: springs back")
        assertFalse(closes(dy = -40f, velocity = 3000f), "ended above the start")
    }
}
