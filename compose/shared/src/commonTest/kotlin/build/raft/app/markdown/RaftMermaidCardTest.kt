package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals

class RaftMermaidCardTest {
    @Test
    fun zoomStaysWithinBounds() {
        assertEquals(RaftMermaidMaxScale, raftMermaidZoomIn(RaftMermaidMaxScale))
        assertEquals(RaftMermaidMaxScale, raftMermaidZoomIn(3.9f))
        assertEquals(RaftMermaidMinScale, raftMermaidZoomOut(RaftMermaidMinScale))
        assertEquals(RaftMermaidMinScale, raftMermaidZoomOut(0.26f))
    }
}
