package build.raft.app.markdown

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftMermaidTransformTest {
    private val viewport = Size(300f, 200f)
    private val fitted = Size(300f, 150f)

    private fun assertOffset(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 0.01f, "x of $actual")
        assertEquals(expected.y, actual.y, 0.01f, "y of $actual")
    }

    @Test
    fun panStaysInsideTheContentBoundsAndCentresContentThatFits() {
        val content = Size(600f, 150f)
        // 150px of slack each way horizontally; vertically the content fits, so it stays centred.
        assertOffset(Offset(150f, 0f), raftMermaidClampPan(Offset(500f, 80f), content, viewport))
        assertOffset(Offset(-150f, 0f), raftMermaidClampPan(Offset(-500f, -80f), content, viewport))
        assertOffset(Offset(40f, 0f), raftMermaidClampPan(Offset(40f, 0f), content, viewport))
        assertOffset(Offset.Zero, raftMermaidClampPan(Offset(Float.NaN, 10f), content, viewport))
    }

    @Test
    fun pinchKeepsTheDiagramPointUnderTheFingers() {
        val centroid = Offset(75f, 100f)
        val start = RaftMermaidTransform()
        val before = raftMermaidContentOrigin(start.pan, fitted, viewport)
        val pointBefore = (centroid - before) / 1f
        val zoomed = raftMermaidTransformBy(start, fitted, viewport, centroid, zoomChange = 2f, panChange = Offset.Zero)
        assertEquals(2f, zoomed.zoom, 0.0001f)
        val after = raftMermaidContentOrigin(zoomed.pan, fitted * 2f, viewport)
        assertOffset(pointBefore, (centroid - after) / 2f)
    }

    @Test
    fun doubleTapTogglesBetweenFitAndTwiceTheSize() {
        val tap = Offset(150f, 100f)
        val zoomed = raftMermaidDoubleTap(RaftMermaidTransform(), fitted, viewport, tap)
        assertEquals(RaftMermaidDoubleTapZoom, zoomed.zoom, 0.0001f)
        assertEquals(RaftMermaidTransform(), raftMermaidDoubleTap(zoomed, fitted, viewport, tap))
        assertEquals(RaftMermaidTransform(), raftMermaidDoubleTap(RaftMermaidTransform(0.5f), fitted, viewport, tap))
    }
}
