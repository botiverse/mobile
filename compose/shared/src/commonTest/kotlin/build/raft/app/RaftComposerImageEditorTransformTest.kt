package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftComposerImageEditorTransformTest {

    private fun assertNear(expected: Float, actual: Float, what: String = "", eps: Float = 0.01f) =
        assertTrue(abs(expected - actual) <= eps, "$what: expected $expected, got $actual")

    private fun assertNear(expected: Offset, actual: Offset, what: String = "", eps: Float = 0.01f) {
        assertNear(expected.x, actual.x, "$what x", eps)
        assertNear(expected.y, actual.y, "$what y", eps)
    }

    @Test
    fun quarterTurnsNormalise() {
        assertEquals(0, raftComposerImageNormalizeQuarterTurns(4))
        assertEquals(3, raftComposerImageNormalizeQuarterTurns(-1))
        assertEquals(1, raftComposerImageNormalizeQuarterTurns(5))
        assertTrue(raftComposerImageTurnSwapsSides(1))
        assertFalse(raftComposerImageTurnSwapsSides(2))
        assertTrue(raftComposerImageTurnSwapsSides(-1))
    }

    @Test
    fun turnedCanvasFitsTheStageWithSwappedSides() {
        // 4:3 photo on a 400x800 stage: upright it is 400x300; turned it must be 300 wide on
        // screen, so the unrotated canvas is 400 tall... i.e. fit into 800x400 -> 533.3x400.
        val upright = assertNotNull(
            raftComposerImageEditorCanvasSize(4000, 3000, 400f, 800f, RaftComposerImageCropRect.Uncropped, 0)
        )
        assertNear(400f, upright.width)
        assertNear(300f, upright.height)
        val turned = assertNotNull(
            raftComposerImageEditorCanvasSize(4000, 3000, 400f, 800f, RaftComposerImageCropRect.Uncropped, 1)
        )
        assertNear(533.33f, turned.width)
        assertNear(400f, turned.height)
        // On screen the turned canvas spans turned.height x turned.width = 400 x 533, inside the stage.
        assertTrue(turned.height <= 400.01f && turned.width <= 800.01f)
    }

    @Test
    fun rotateKeepsEditsOnTheSamePhotoPixels() {
        val stroke = RaftComposerImageStroke(points = listOf(Offset(100f, 150f), Offset(200f, 150f)))
        val content = RaftComposerImageEditorContent().raftComposerImageAppendStroke(stroke)
        // Upright canvas 400x300 (scale 0.1 of 4000x3000); turned canvas 533.3x400 (scale 0.1333).
        val turned = raftApplyRotateToContent(content, 4000, 3000, 400f, 800f)
        assertEquals(1, turned.rotationQuarterTurns)
        val first = turned.strokes.single().points.first()
        assertNear(Offset(133.33f, 200f), first, "rescaled with the fit")
        // The draw operation list follows the strokes.
        val op = turned.raftComposerImageResolvedDrawOperations().single() as RaftComposerImageDrawOperation.Pencil
        assertNear(first, op.stroke.points.first(), "op")
        // Four turns are the identity (up to float rounding).
        var full = content
        repeat(4) { full = raftApplyRotateToContent(full, 4000, 3000, 400f, 800f) }
        assertEquals(0, full.rotationQuarterTurns)
        assertNear(Offset(100f, 150f), full.strokes.single().points.first(), "full circle")
    }

    @Test
    fun cropWhileTurnedFitsTheSwappedStage() {
        val content = RaftComposerImageEditorContent(rotationQuarterTurns = 1)
            .raftComposerImageAppendStroke(RaftComposerImageStroke(points = listOf(Offset(266.67f, 200f), Offset(266.67f, 200f))))
        // Crop to the right half of the photo; the centre point moves to the crop's left edge.
        val cropped =
            raftApplyCropToContent(
                content = content,
                newCrop = RaftComposerImageCropRect(0.5f, 0f, 1f, 1f),
                imageWidth = 4000,
                imageHeight = 3000,
                maxWidth = 400f,
                maxHeight = 800f,
            )
        // Turned fit box 800x400: the 2000x3000 half fits as 266.67x400.
        assertNear(Offset(0f, 200f), cropped.strokes.single().points.first(), "crop edge", eps = 0.05f)
    }

    @Test
    fun rotatePointAndItsInverse() {
        val size = Size(200f, 100f)
        val p = Offset(30f, 10f)
        assertEquals(Offset(90f, 30f), raftComposerImageRotatePoint(p, size, 1))
        assertEquals(Offset(170f, 90f), raftComposerImageRotatePoint(p, size, 2))
        assertEquals(Offset(10f, 170f), raftComposerImageRotatePoint(p, size, 3))
        (0..3).forEach { turns ->
            assertNear(p, raftComposerImageUnrotatePoint(raftComposerImageRotatePoint(p, size, turns), size, turns), "turn $turns")
        }
        assertEquals(IntSize(100, 200), raftComposerImageRotatedSize(IntSize(200, 100), 3))
        assertEquals(IntSize(200, 100), raftComposerImageRotatedSize(IntSize(200, 100), 2))
    }

    @Test
    fun canvasToViewMapping() {
        val canvas = Size(200f, 100f)
        val stage = Size(400f, 400f)
        // Upright, no zoom: centred.
        assertNear(Offset(100f, 150f), raftComposerImageCanvasToView(Offset.Zero, canvas, stage, 0))
        // One turn: the canvas's top-left corner goes to the turned picture's top-right.
        assertNear(Offset(250f, 100f), raftComposerImageCanvasToView(Offset.Zero, canvas, stage, 1))
        val view = RaftComposerImageViewTransform(zoom = 2f, pan = Offset(10f, -20f))
        (0..3).forEach { turns ->
            val point = Offset(37f, 81f)
            val onScreen = raftComposerImageCanvasToView(point, canvas, stage, turns, view)
            assertNear(point, raftComposerImageViewToCanvas(onScreen, canvas, stage, turns, view), "round trip $turns")
        }
        // Zoom 2 about the centre doubles the distance from the stage centre, then pans.
        assertNear(Offset(10f, 80f), raftComposerImageCanvasToView(Offset.Zero, canvas, stage, 0, view))
    }

    @Test
    fun pinchKeepsTheCentroidPointAndClampsThePan() {
        val stage = Size(400f, 400f)
        val visible = Size(400f, 300f)
        val zoomed =
            RaftComposerImageViewTransform.Identity.raftComposerImagePinch(
                centroid = Offset(300f, 200f),
                zoomChange = 2f,
                panChange = Offset.Zero,
                stageSize = stage,
                visibleSize = visible,
            )
        assertEquals(2f, zoomed.zoom)
        // The stage point under the fingers (300, 200) still shows the same canvas point.
        val canvas = Size(400f, 300f)
        val before = raftComposerImageViewToCanvas(Offset(300f, 200f), canvas, stage, 0)
        val after = raftComposerImageViewToCanvas(Offset(300f, 200f), canvas, stage, 0, zoomed)
        assertNear(before, after, "centroid")
        // Panning far is clamped to the zoomed picture's overhang: (800 - 400) / 2 = 200.
        val far = zoomed.raftComposerImagePinch(Offset(200f, 200f), 1f, Offset(1000f, 1000f), stage, visible)
        assertEquals(Offset(200f, 100f), far.pan)
        // Zooming back out to 1 recentres.
        val reset = far.raftComposerImagePinch(Offset(200f, 200f), 0.1f, Offset.Zero, stage, visible)
        assertEquals(RaftComposerImageViewTransform.Identity, reset)
    }

    @Test
    fun aspectChoicesStartFromTheLargestCentredRect() {
        assertNull(RaftComposerImageCropAspect.Free.pixelRatio(4000, 3000))
        assertEquals(1f, RaftComposerImageCropAspect.Square.pixelRatio(4000, 3000))
        val square = raftCropRectForAspect(RaftComposerImageCropRect.Uncropped, 1f, 4000, 3000)
        assertNear(0.125f, square.left)
        assertNear(0.875f, square.right)
        assertNear(0f, square.top)
        assertNear(1f, square.bottom)
        assertTrue(raftCropMatchesRatio(square, 1f, 4000, 3000))
        // Original on an uncropped image is the whole image.
        val original = raftCropRectForAspect(RaftComposerImageCropRect.Uncropped, 4000f / 3000f, 4000, 3000)
        assertNear(0f, original.left)
        assertNear(1f, original.right)
        assertNear(1f, original.bottom)
        // A square from a crop near the right edge stays inside the image.
        val shifted = raftCropRectForAspect(RaftComposerImageCropRect(0.8f, 0f, 1f, 1f), 1f, 4000, 3000)
        assertNear(1f, shifted.right)
        assertNear(0.25f, shifted.left)
    }

    @Test
    fun aspectLockedDragsKeepTheRatio() {
        val canvas = Size(400f, 300f)
        val start = raftCropRectForAspect(RaftComposerImageCropRect.Uncropped, 1f, 4000, 3000)
        // Corner: drag the top-left corner inwards; bottom-right stays put.
        val corner =
            raftCropApplyDragWithAspect(RaftCropDragHandle.TopLeft, start, Offset(40f, 10f), canvas, 1f, 4000, 3000)
        assertTrue(raftCropMatchesRatio(corner, 1f, 4000, 3000), "corner keeps 1:1: $corner")
        assertNear(start.right, corner.right)
        assertNear(start.bottom, corner.bottom)
        assertTrue(corner.left > start.left)
        // Edge: drag the right edge inwards; height follows about the centre.
        val edge =
            raftCropApplyDragWithAspect(RaftCropDragHandle.Right, start, Offset(-60f, 0f), canvas, 1f, 4000, 3000)
        assertTrue(raftCropMatchesRatio(edge, 1f, 4000, 3000), "edge keeps 1:1: $edge")
        assertNear(start.left, edge.left)
        assertNear(0.5f, (edge.top + edge.bottom) / 2f, "vertical centre")
        // Growing past the image is clamped, still at the ratio.
        val grown =
            raftCropApplyDragWithAspect(RaftCropDragHandle.BottomRight, corner, Offset(500f, 500f), canvas, 1f, 4000, 3000)
        assertTrue(raftCropMatchesRatio(grown, 1f, 4000, 3000), "clamped keeps 1:1: $grown")
        assertTrue(grown.right <= 1.0001f && grown.bottom <= 1.0001f)
        // Free is the plain drag.
        assertEquals(
            raftCropApplyDrag(RaftCropDragHandle.Right, start, Offset(-60f, 0f), canvas),
            raftCropApplyDragWithAspect(RaftCropDragHandle.Right, start, Offset(-60f, 0f), canvas, null, 4000, 3000),
        )
    }

    @Test
    fun undoRedoCoversRotateAndPenWidth() {
        val state = RaftComposerImageEditorState()
        val thick =
            RaftComposerImageStroke(points = listOf(Offset(1f, 1f), Offset(5f, 5f)), width = RaftComposerImagePenWidth.Thick.px)
        state.update(state.history.apply(state.history.content.raftComposerImageAppendStroke(thick)))
        state.update(state.history.apply(raftApplyRotateToContent(state.history.content, 400, 300, 400f, 400f)))
        assertEquals(1, state.history.content.rotationQuarterTurns)
        assertTrue(state.history.canUndo)
        state.undo()
        assertEquals(0, state.history.content.rotationQuarterTurns)
        assertEquals(RaftComposerImagePenWidth.Thick.px, state.history.content.strokes.single().width)
        state.undo()
        assertTrue(state.history.content.strokes.isEmpty())
        assertFalse(state.history.canUndo)
        state.redo()
        state.redo()
        assertEquals(1, state.history.content.rotationQuarterTurns)
        assertFalse(state.history.canRedo)
        // A new edit after undo drops the redo branch.
        state.undo()
        state.update(state.history.apply(state.history.content.copy(cropRect = RaftComposerImageCropRect(0f, 0f, 0.5f, 0.5f))))
        assertFalse(state.history.canRedo)
    }
}
