package build.raft.designsystem.gesture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftBackSwipeDismissTest {
    @Test
    fun onlyABackDirectionHorizontalDragIsClaimed() {
        assertEquals(RaftBackSwipeDismissDecision.Undecided, raftBackSwipeDismissDecision(4f, 2f, 8f, isLtr = true))
        assertEquals(RaftBackSwipeDismissDecision.Claim, raftBackSwipeDismissDecision(12f, 3f, 8f, isLtr = true))
        assertEquals(RaftBackSwipeDismissDecision.Release, raftBackSwipeDismissDecision(-12f, 3f, 8f, isLtr = true))
        assertEquals(RaftBackSwipeDismissDecision.Release, raftBackSwipeDismissDecision(10f, 14f, 8f, isLtr = true))
        // RTL: back is leftward.
        assertEquals(RaftBackSwipeDismissDecision.Claim, raftBackSwipeDismissDecision(-12f, 3f, 8f, isLtr = false))
        assertEquals(RaftBackSwipeDismissDecision.Release, raftBackSwipeDismissDecision(12f, 3f, 8f, isLtr = false))
        assertEquals(-5f, raftBackSwipeDismissProgress(5f, isLtr = false))
    }

    @Test
    fun releaseDismissesPastAThirdOrOnABackFling() {
        assertFalse(raftBackSwipeDismissCommits(offsetPx = 0f, widthPx = 300f, backVelocityPxPerS = 2000f))
        assertFalse(raftBackSwipeDismissCommits(offsetPx = 90f, widthPx = 300f, backVelocityPxPerS = 0f))
        assertTrue(raftBackSwipeDismissCommits(offsetPx = 100f, widthPx = 300f, backVelocityPxPerS = 0f))
        assertTrue(raftBackSwipeDismissCommits(offsetPx = 20f, widthPx = 300f, backVelocityPxPerS = 900f))
        // Flung back toward the start: stays open even past a third.
        assertFalse(raftBackSwipeDismissCommits(offsetPx = 200f, widthPx = 300f, backVelocityPxPerS = -900f))
    }
}
