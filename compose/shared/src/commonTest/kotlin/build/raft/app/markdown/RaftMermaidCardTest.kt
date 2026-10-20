package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals

class RaftMermaidCardTest {
    @Test
    fun zoomStepsMatchTheWebFactor() {
        assertEquals(1.2f, raftMermaidZoomIn(1f), 0.0001f)
        assertEquals(1f / 1.2f, raftMermaidZoomOut(1f), 0.0001f)
    }

    @Test
    fun zoomStaysWithinBounds() {
        assertEquals(RaftMermaidMaxScale, raftMermaidZoomIn(RaftMermaidMaxScale))
        assertEquals(RaftMermaidMaxScale, raftMermaidZoomIn(3.9f))
        assertEquals(RaftMermaidMinScale, raftMermaidZoomOut(RaftMermaidMinScale))
        assertEquals(RaftMermaidMinScale, raftMermaidZoomOut(0.26f))
    }

    @Test
    fun zoomingInThenOutReturnsToTheStart() {
        assertEquals(1f, raftMermaidZoomOut(raftMermaidZoomIn(1f)), 0.0001f)
    }
}
