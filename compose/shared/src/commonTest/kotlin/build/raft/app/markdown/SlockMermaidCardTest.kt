package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals

class SlockMermaidCardTest {
    @Test
    fun zoomStepsMatchTheWebFactor() {
        assertEquals(1.2f, slockMermaidZoomIn(1f), 0.0001f)
        assertEquals(1f / 1.2f, slockMermaidZoomOut(1f), 0.0001f)
    }

    @Test
    fun zoomStaysWithinBounds() {
        assertEquals(SlockMermaidMaxScale, slockMermaidZoomIn(SlockMermaidMaxScale))
        assertEquals(SlockMermaidMaxScale, slockMermaidZoomIn(3.9f))
        assertEquals(SlockMermaidMinScale, slockMermaidZoomOut(SlockMermaidMinScale))
        assertEquals(SlockMermaidMinScale, slockMermaidZoomOut(0.26f))
    }

    @Test
    fun zoomingInThenOutReturnsToTheStart() {
        assertEquals(1f, slockMermaidZoomOut(slockMermaidZoomIn(1f)), 0.0001f)
    }
    @Test
    fun wideDiagramFitsTheAvailableMessageWidthAtDefaultZoom() {
        val scale = slockMermaidInlineScale(diagramWidth = 1200f, availableWidth = 300f, zoom = 1f)
        assertEquals(300f, 1200f * scale, 0.0001f)
        assertEquals(600f, 1200f * slockMermaidInlineScale(1200f, 600f, 1f), 0.0001f)
    }

    @Test
    fun narrowDiagramKeepsItsNaturalSizeAndZoomCanOverflowForPanning() {
        assertEquals(1f, slockMermaidInlineScale(100f, 300f, 1f))
        assertEquals(600f, 1200f * slockMermaidInlineScale(1200f, 300f, 2f), 0.0001f)
    }

    @Test
    fun unmeasuredOrUnboundedViewportDoesNotGenerateAnInvalidScale() {
        assertEquals(1.2f, slockMermaidInlineScale(0f, 300f, 1.2f))
        assertEquals(1.2f, slockMermaidInlineScale(1200f, 0f, 1.2f))
        assertEquals(1.2f, slockMermaidInlineScale(1200f, Float.POSITIVE_INFINITY, 1.2f))
    }
}
