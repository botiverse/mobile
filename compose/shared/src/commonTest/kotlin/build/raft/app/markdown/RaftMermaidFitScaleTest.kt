package build.raft.app.markdown

import build.raft.mermaid.compose.MermaidMaxSidePx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RaftMermaidFitScaleTest {
    @Test
    fun wideDiagramFitsTheAvailableMessageWidth() {
        val scale = raftMermaidFitScale(sceneWidth = 1200f, sceneHeight = 400f, availableWidth = 300f, density = 3f)
        assertEquals(300f, 1200f * scale, 0.0001f)
        assertEquals(600f, 1200f * raftMermaidFitScale(1200f, 400f, availableWidth = 600f, density = 3f), 0.0001f)
    }

    @Test
    fun narrowDiagramKeepsItsNaturalSize() {
        assertEquals(1f, raftMermaidFitScale(100f, 80f, availableWidth = 300f, density = 3f))
    }

    @Test
    fun fullScreenFitContainsBothSides() {
        val scale = raftMermaidFitScale(600f, 1200f, availableWidth = 300f, availableHeight = 400f, density = 3f)
        assertEquals(400f, 1200f * scale, 0.01f)
        assertTrue(600f * scale <= 300f)
    }

    @Test
    fun unmeasuredOrUnboundedViewportDoesNotConstrain() {
        assertEquals(1f, raftMermaidFitScale(1200f, 400f, availableWidth = 0f, density = 3f))
        assertEquals(1f, raftMermaidFitScale(1200f, 400f, availableWidth = Float.POSITIVE_INFINITY, density = 3f))
        assertEquals(1f, raftMermaidFitScale(1200f, 400f, availableWidth = Float.NaN, density = 3f))
    }

    @Test
    fun hugeDiagramsStayWithinComposeConstraints() {
        val scale = raftMermaidFitScale(sceneWidth = 400f, sceneHeight = 90_000f, availableWidth = 600f, density = 3f)
        val heightPx = 90_000f * scale * 3f
        assertTrue(heightPx <= MermaidMaxSidePx + 0.5f, "height $heightPx px")
        assertTrue(heightPx < 32_767f)
    }

    @Test
    fun degenerateScenesDrawNothing() {
        assertEquals(0f, raftMermaidFitScale(0f, 0f, availableWidth = 300f, density = 3f))
        assertEquals(0f, raftMermaidFitScale(Float.POSITIVE_INFINITY, 100f, availableWidth = 300f, density = 3f))
        assertEquals(0f, raftMermaidFitScale(100f, 100f, availableWidth = 300f, density = Float.NaN))
    }

    @Test
    fun inlineViewportHeightPadsTheFittedDiagramWithinLimits() {
        // Fitted height + 16dp above and below.
        assertEquals(332f, raftMermaidInlineViewportHeight(600f, 0.5f, density = 3f, minHeight = 160f, verticalPadding = 16f))
        // A short diagram keeps the minimum height.
        assertEquals(160f, raftMermaidInlineViewportHeight(40f, 1f, density = 3f, minHeight = 160f, verticalPadding = 16f))
        // Never taller than Compose can lay out.
        val tall = raftMermaidInlineViewportHeight(90_000f, 1f, density = 3f, minHeight = 160f, verticalPadding = 16f)
        assertTrue(tall * 3f <= MermaidMaxSidePx + 0.5f, "height $tall dp")
    }
}
