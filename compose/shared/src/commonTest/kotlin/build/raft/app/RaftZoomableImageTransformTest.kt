package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RaftZoomableImageTransformTest {
    private val viewport = Size(1000f, 2000f)
    private val centre = Offset(500f, 1000f)

    /** Unscaled image point (offset from the image centre) drawn at viewport position [at]. */
    private fun RaftZoomableImageTransform.imagePointAt(at: Offset): Offset = (at - centre - pan) / scale

    private fun assertClose(expected: Offset, actual: Offset, message: String) {
        assertTrue(abs(expected.x - actual.x) < 0.01f && abs(expected.y - actual.y) < 0.01f, "$message: expected $expected, was $actual")
    }

    @Test
    fun pinchKeepsTheImagePointUnderTheFingersCentroid() {
        val start = RaftZoomableImageTransform(scale = 1.5f, pan = Offset(-100f, 50f))
        val centroid = Offset(700f, 1200f)

        val zoomed = raftZoomableImageTransformBy(start, viewport, centroid, zoomChange = 2f, panChange = Offset.Zero)

        assertEquals(3f, zoomed.scale)
        assertClose(start.imagePointAt(centroid), zoomed.imagePointAt(centroid), "point under the centroid")
    }

    @Test
    fun pinchClampsScaleAndKeepsTheImageCoveringTheViewport() {
        val zoomedIn = raftZoomableImageTransformBy(RaftZoomableImageTransform(), viewport, Offset(0f, 0f), 10f, Offset.Zero)
        assertEquals(RaftZoomableImageMaxScale, zoomedIn.scale)
        // A corner focus keeps that corner in place: the image edge lands exactly on the clamp bound.
        assertEquals(Offset(1500f, 3000f), zoomedIn.pan)

        val zoomedOut = raftZoomableImageTransformBy(zoomedIn, viewport, centre, 0.1f, Offset.Zero)
        assertEquals(RaftZoomableImageTransform(), zoomedOut)
    }

    @Test
    fun panMovesTheZoomedImageWithinItsBounds() {
        val zoomed = RaftZoomableImageTransform(scale = 2f, pan = Offset(-400f, 0f))

        val panned = raftZoomableImageTransformBy(zoomed, viewport, centre, 1f, Offset(-300f, 200f))

        assertEquals(RaftZoomableImageTransform(scale = 2f, pan = Offset(-500f, 200f)), panned)
    }

    @Test
    fun doubleTapAtOneXZoomsAroundTheTapPoint() {
        val tap = Offset(800f, 600f)

        val target = raftZoomableImageDoubleTapTarget(RaftZoomableImageTransform(), viewport, tap)

        assertEquals(RaftZoomableImageDoubleTapScale, target.scale)
        assertClose(RaftZoomableImageTransform().imagePointAt(tap), target.imagePointAt(tap), "point under the tap")
    }

    @Test
    fun doubleTapWhenZoomedOrPannedGoesBackToOneX() {
        listOf(
            RaftZoomableImageTransform(scale = 2.5f, pan = Offset(-300f, 400f)),
            RaftZoomableImageTransform(scale = 1.2f),
            RaftZoomableImageTransform(scale = 4f, pan = Offset.Zero)
        ).forEach { zoomed ->
            assertEquals(
                RaftZoomableImageTransform(),
                raftZoomableImageDoubleTapTarget(zoomed, viewport, Offset(10f, 10f)),
                "from $zoomed"
            )
        }
    }

    @Test
    fun animationFramesRunFromStartToTarget() {
        val from = RaftZoomableImageTransform()
        val to = RaftZoomableImageTransform(scale = 2.5f, pan = Offset(-600f, 300f))

        assertEquals(from, raftZoomableImageLerp(from, to, 0f))
        assertEquals(RaftZoomableImageTransform(scale = 1.75f, pan = Offset(-300f, 150f)), raftZoomableImageLerp(from, to, 0.5f))
        assertEquals(to, raftZoomableImageLerp(from, to, 1f))
        assertEquals(to, raftZoomableImageLerp(from, to, 1.3f), "overshoot is clamped")
    }
}
