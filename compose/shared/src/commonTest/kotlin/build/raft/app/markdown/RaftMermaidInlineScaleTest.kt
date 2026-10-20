package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RaftMermaidInlineScaleTest {
    @Test
    fun ordinaryDiagramsKeepTheirScale() {
        assertEquals(1.5f, raftMermaidInlineScale(sceneWidth = 600f, sceneHeight = 400f, scale = 1.5f, density = 3f))
    }

    @Test
    fun hugeDiagramsAreScaledToFitComposeConstraints() {
        val scale = raftMermaidInlineScale(sceneWidth = 400f, sceneHeight = 90_000f, scale = 1f, density = 3f)
        val heightPx = 90_000f * scale * 3f
        assertTrue(heightPx <= RaftMermaidMaxInlineSidePx + 0.5f, "height $heightPx px")
        assertTrue(heightPx < 32_767f)
        val wide = raftMermaidInlineScale(sceneWidth = 50_000f, sceneHeight = 30_000f, scale = 2f, density = 2.75f)
        assertTrue(50_000f * wide * 2.75f <= RaftMermaidMaxInlineSidePx + 0.5f)
        assertTrue(30_000f * wide * 2.75f <= RaftMermaidMaxInlineSidePx + 0.5f)
    }

    @Test
    fun degenerateInputsDrawNothing() {
        assertEquals(0f, raftMermaidInlineScale(0f, 0f, 1f, 3f))
        assertEquals(0f, raftMermaidInlineScale(100f, 100f, Float.NaN, 3f))
        assertEquals(0f, raftMermaidInlineScale(Float.POSITIVE_INFINITY, 100f, 1f, 3f))
        assertEquals(0f, raftMermaidInlineScale(100f, 100f, -1f, 3f))
    }
}
