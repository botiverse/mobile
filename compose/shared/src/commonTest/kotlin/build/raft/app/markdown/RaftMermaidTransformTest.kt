package build.raft.app.markdown

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun zoomIsClampedToTheToolbarRange() {
        val center = Offset(150f, 100f)
        val max = raftMermaidTransformBy(RaftMermaidTransform(3f), fitted, viewport, center, 10f, Offset.Zero)
        assertEquals(RaftMermaidMaxScale, max.zoom)
        val min = raftMermaidTransformBy(RaftMermaidTransform(0.5f), fitted, viewport, center, 0.01f, Offset.Zero)
        assertEquals(RaftMermaidMinScale, min.zoom)
        assertOffset(Offset.Zero, min.pan)
        val invalid = raftMermaidTransformBy(RaftMermaidTransform(2f), fitted, viewport, center, Float.NaN, Offset.Zero)
        assertEquals(2f, invalid.zoom)
    }

    @Test
    fun dragPansOnlyWithinBounds() {
        val zoomed = RaftMermaidTransform(zoom = 2f)
        // Content 600 x 300 in a 300 x 200 viewport: 150 / 50 px of slack.
        val moved = raftMermaidTransformBy(zoomed, fitted, viewport, Offset(150f, 100f), 1f, Offset(1000f, -1000f))
        assertOffset(Offset(150f, -50f), moved.pan)
        val atFit = raftMermaidTransformBy(RaftMermaidTransform(), fitted, viewport, Offset(150f, 100f), 1f, Offset(80f, 80f))
        assertOffset(Offset.Zero, atFit.pan)
    }

    @Test
    fun onlyAnOverflowingDiagramCanBePanned() {
        assertFalse(raftMermaidCanPan(1f, fitted, viewport))
        assertFalse(raftMermaidCanPan(0.5f, fitted, viewport))
        assertTrue(raftMermaidCanPan(1.2f, fitted, viewport))
    }

    @Test
    fun doubleTapTogglesBetweenFitAndTwiceTheSize() {
        val tap = Offset(150f, 100f)
        val zoomed = raftMermaidDoubleTap(RaftMermaidTransform(), fitted, viewport, tap)
        assertEquals(RaftMermaidDoubleTapZoom, zoomed.zoom, 0.0001f)
        assertEquals(RaftMermaidTransform(), raftMermaidDoubleTap(zoomed, fitted, viewport, tap))
        assertEquals(RaftMermaidTransform(), raftMermaidDoubleTap(RaftMermaidTransform(0.5f), fitted, viewport, tap))
    }

    @Test
    fun toolbarButtonsAndGesturesShareOneState() {
        val state = RaftMermaidViewportState()
        state.fitted = fitted
        state.viewport = viewport
        state.zoomIn()
        assertEquals(1.2f, state.zoom, 0.0001f)
        assertTrue(state.canPan)
        state.transformBy(Offset(150f, 100f), zoomChange = 2f, panChange = Offset.Zero)
        assertEquals(2.4f, state.zoom, 0.0001f)
        state.zoomOut()
        assertEquals(2f, state.zoom, 0.0001f)
        state.doubleTap(Offset(10f, 10f))
        assertEquals(RaftMermaidTransform(), state.transform)
        assertFalse(state.canPan)
        assertTrue(state.canZoomIn && state.canZoomOut)
    }
}
