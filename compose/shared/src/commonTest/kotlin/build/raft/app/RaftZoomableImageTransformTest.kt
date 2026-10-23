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

    @Test
    fun panIsBoundedByTheFittedImageNotTheViewport() {
        // A tall image (1:4) fits a 1000x2000 viewport as 500x2000: letterboxed left and right.
        val tall = Size(1000f, 4000f)
        assertEquals(Size(500f, 2000f), raftZoomableImageFittedSize(tall, viewport))
        // At 2.5x it is 1250 wide: 125 px of it can hide past each side, not the viewport's 750.
        assertEquals(Offset(125f, 1500f), raftZoomableImageMaxPan(2.5f, viewport, tall))

        val zoomed = RaftZoomableImageTransform(scale = 2.5f)
        val dragged = raftZoomableImageTransformBy(zoomed, viewport, centre, 1f, Offset(-2000f, -5000f), tall)
        assertEquals(Offset(-125f, -1500f), dragged.pan)
        // The image's right edge stops on the viewport's right edge: no blank space to its right.
        assertEquals(viewport.width, centre.x + dragged.pan.x + 500f * 2.5f / 2f)

        // Still narrower than the viewport (1.5x: 750 wide): it stays centred horizontally.
        val narrow = raftZoomableImageTransformBy(RaftZoomableImageTransform(scale = 1.5f), viewport, centre, 1f, Offset(300f, 0f), tall)
        assertEquals(0f, narrow.pan.x)
    }

    @Test
    fun unknownOrInvalidImageSizeFallsBackToTheViewport() {
        listOf(Size.Unspecified, Size.Zero, Size(0f, 10f), Size(Float.NaN, 10f)).forEach { content ->
            assertEquals(viewport, raftZoomableImageFittedSize(content, viewport), "content $content")
            assertEquals(Offset(750f, 1500f), raftZoomableImageMaxPan(2.5f, viewport, content), "content $content")
        }
    }

    @Test
    fun doubleTapOnTheLetterboxZoomsWithoutBlankSpace() {
        val tall = Size(1000f, 4000f)
        // x = 900 is on the letterbox right of the 500-wide image.
        val target = raftZoomableImageDoubleTapTarget(RaftZoomableImageTransform(), viewport, Offset(900f, 1000f), tall)

        assertEquals(RaftZoomableImageDoubleTapScale, target.scale)
        assertEquals(Offset(-125f, 0f), target.pan, "the image's right edge lands on the viewport's")
    }

    @Test
    fun theLoadedImageSizeReclampsAnExistingZoom() {
        val state = RaftZoomableImageState()
        state.viewport = viewport
        state.transformBy(focus = Offset(1000f, 1000f), zoomChange = 2.5f, panChange = Offset.Zero)
        assertEquals(Offset(-750f, 0f), state.transform.pan, "viewport bounds while the size is unknown")

        state.contentSize = Size(1000f, 4000f)

        assertEquals(RaftZoomableImageTransform(scale = 2.5f, pan = Offset(-125f, 0f)), state.transform)
        state.reset()
        assertEquals(RaftZoomableImageTransform(), state.transform)
    }

    @Test
    fun aPanPushedAgainstTheSideEdgeAccumulatesAHandOff() {
        val tall = Size(1000f, 4000f)
        val atLeftEdge = RaftZoomableImageTransform(scale = 2.5f, pan = Offset(125f, 0f))
        val slop = 8f

        // Finger moving right with the image's left edge already on the viewport's: pushes.
        val first = raftZoomableImageEdgePush(Offset.Zero, atLeftEdge, viewport, tall, Offset(5f, 1f))
        val second = raftZoomableImageEdgePush(first, atLeftEdge, viewport, tall, Offset(6f, -1f))
        assertEquals(Offset(11f, 0f), second)
        assertTrue(raftZoomableImageHandsOffToPager(second, slop))
        assertTrue(!raftZoomableImageHandsOffToPager(first, slop), "below the slop the pan keeps it")

        // Moving away from that edge, mostly vertically, or not at an edge: no push.
        assertEquals(Offset.Zero, raftZoomableImageEdgePush(first, atLeftEdge, viewport, tall, Offset(-5f, 0f)))
        assertEquals(Offset.Zero, raftZoomableImageEdgePush(first, atLeftEdge, viewport, tall, Offset(2f, 9f)))
        val inside = atLeftEdge.copy(pan = Offset(40f, 0f))
        assertEquals(Offset.Zero, raftZoomableImageEdgePush(first, inside, viewport, tall, Offset(5f, 0f)))
        // At 1x there is nothing to hand over from.
        assertEquals(Offset.Zero, raftZoomableImageEdgePush(Offset.Zero, RaftZoomableImageTransform(), viewport, tall, Offset(9f, 0f)))
        // Right edge, finger moving left.
        val atRightEdge = atLeftEdge.copy(pan = Offset(-125f, 0f))
        assertEquals(Offset(-12f, 0f), raftZoomableImageEdgePush(Offset.Zero, atRightEdge, viewport, tall, Offset(-12f, 0f)))
    }
}
