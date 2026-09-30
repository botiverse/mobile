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
}
