package build.raft.app.markdown

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftMermaidSwipeDismissTest {
    @Test
    fun onlyAMostlyHorizontalRightwardDragPastTheSlopIsClaimed() {
        val slop = 8f
        assertEquals(RaftMermaidSwipeClaim.Undecided, raftMermaidSwipeClaim(Offset(4f, 1f), slop))
        assertEquals(RaftMermaidSwipeClaim.Claim, raftMermaidSwipeClaim(Offset(20f, 4f), slop))
        // Diagonal or vertical drags stay with the diagram.
        assertEquals(RaftMermaidSwipeClaim.Reject, raftMermaidSwipeClaim(Offset(10f, 12f), slop))
        assertEquals(RaftMermaidSwipeClaim.Reject, raftMermaidSwipeClaim(Offset(0f, 20f), slop))
        // A leftward drag is never a dismiss.
        assertEquals(RaftMermaidSwipeClaim.Reject, raftMermaidSwipeClaim(Offset(-20f, 0f), slop))
    }

    @Test
    fun aSwipeDismissesPastAThirdOrOnAFlick() {
        assertTrue(raftMermaidSwipeDismisses(offsetPx = 140f, widthPx = 390f, velocityX = 0f))
        assertFalse(raftMermaidSwipeDismisses(offsetPx = 100f, widthPx = 390f, velocityX = 200f))
        assertTrue(raftMermaidSwipeDismisses(offsetPx = 40f, widthPx = 390f, velocityX = 1500f))
        assertFalse(raftMermaidSwipeDismisses(offsetPx = 0f, widthPx = 390f, velocityX = 1500f))
    }
}
