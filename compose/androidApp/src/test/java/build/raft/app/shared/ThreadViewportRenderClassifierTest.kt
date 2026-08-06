package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadViewportRenderClassifierTest {
    @Test
    fun structuralContainersWithoutIntersectingLeavesClassifyMaterializationBoundary() {
        assertEquals(
            ThreadViewportRenderStage.MATERIALIZATION_OR_VISIBILITY,
            classifyThreadViewportRenderStage(
                viewportIntersectingLeafDescendants = 0,
                windowPixels = pixels(whitePermille = 1_000),
                localPixels = pixels(whitePermille = 1_000),
            ),
        )
    }

    @Test
    fun intersectingLeavesWithBlankWindowAndLocalDrawClassifyNativeSubtreeBoundary() {
        assertEquals(
            ThreadViewportRenderStage.NATIVE_SUBTREE_DRAW,
            classifyThreadViewportRenderStage(
                viewportIntersectingLeafDescendants = 3,
                windowPixels = pixels(whitePermille = 1_000),
                localPixels = pixels(whitePermille = 899),
            ),
        )
    }

    @Test
    fun paintedLocalTreeWithBlankWindowClassifiesPresentationBoundary() {
        assertEquals(
            ThreadViewportRenderStage.WINDOW_OR_SURFACE_PRESENTATION,
            classifyThreadViewportRenderStage(
                viewportIntersectingLeafDescendants = 3,
                windowPixels = pixels(whitePermille = 899),
                localPixels = pixels(whitePermille = 615),
            ),
        )
    }

    @Test
    fun paintedLocalAndWindowFramesClassifyHealthyControl() {
        assertEquals(
            ThreadViewportRenderStage.HEALTHY_CONTROL,
            classifyThreadViewportRenderStage(
                viewportIntersectingLeafDescendants = 3,
                windowPixels = pixels(whitePermille = 615),
                localPixels = pixels(whitePermille = 615),
            ),
        )
    }

    @Test
    fun paintedWindowButBlankLaterLocalDrawIsExplicitlyInconclusive() {
        assertEquals(
            ThreadViewportRenderStage.INCONCLUSIVE,
            classifyThreadViewportRenderStage(
                viewportIntersectingLeafDescendants = 3,
                windowPixels = pixels(whitePermille = 615),
                localPixels = pixels(whitePermille = 899),
            ),
        )
    }

    private fun pixels(whitePermille: Int): ThreadViewportPixelStats {
        val sampleCount = 1_000
        val whiteCount = whitePermille
        return ThreadViewportPixelStats(
            sampleCount = sampleCount,
            nonWhiteCount = sampleCount - whiteCount,
            darkCount = 0,
            whitePermille = whitePermille,
        )
    }
}
