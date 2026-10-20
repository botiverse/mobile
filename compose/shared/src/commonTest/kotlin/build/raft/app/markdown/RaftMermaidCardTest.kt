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
    @Test
    fun wideDiagramFitsTheAvailableMessageWidthAtDefaultZoom() {
        val scale = raftMermaidCardInlineScale(diagramWidth = 1200f, availableWidth = 300f, zoom = 1f)
        assertEquals(300f, 1200f * scale, 0.0001f)
        assertEquals(600f, 1200f * raftMermaidCardInlineScale(1200f, 600f, 1f), 0.0001f)
    }

    @Test
    fun narrowDiagramKeepsItsNaturalSizeAndZoomCanOverflowForPanning() {
        assertEquals(1f, raftMermaidCardInlineScale(100f, 300f, 1f))
        assertEquals(600f, 1200f * raftMermaidCardInlineScale(1200f, 300f, 2f), 0.0001f)
    }

    @Test
    fun unmeasuredOrUnboundedViewportDoesNotGenerateAnInvalidScale() {
        assertEquals(1.2f, raftMermaidCardInlineScale(0f, 300f, 1.2f))
        assertEquals(1.2f, raftMermaidCardInlineScale(1200f, 0f, 1.2f))
        assertEquals(1.2f, raftMermaidCardInlineScale(1200f, Float.POSITIVE_INFINITY, 1.2f))
    }
}
