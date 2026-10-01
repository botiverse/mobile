package build.raft.app

import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SlockFilePreviewCropMathTest {

    @Test
    fun handleDetectionIdentifiesCornersEdgesAndCenter() {
        val canvasSize = Size(1000f, 1000f)
        val cropRect = SlockComposerImageCropRect(0.2f, 0.2f, 0.8f, 0.8f)
        val hitRadius = 30f

        // Corners
        assertEquals(
            SlockCropDragHandle.TopLeft,
            slockCropDetectHandle(Offset(200f, 200f), cropRect, canvasSize, hitRadius)
        )
        assertEquals(
            SlockCropDragHandle.TopRight,
            slockCropDetectHandle(Offset(800f, 200f), cropRect, canvasSize, hitRadius)
        )
        assertEquals(
            SlockCropDragHandle.BottomLeft,
            slockCropDetectHandle(Offset(200f, 800f), cropRect, canvasSize, hitRadius)
        )
        assertEquals(
            SlockCropDragHandle.BottomRight,
            slockCropDetectHandle(Offset(800f, 800f), cropRect, canvasSize, hitRadius)
        )

        // Edges (middle of edge, excluding corner radius)
        assertEquals(
            SlockCropDragHandle.Top,
            slockCropDetectHandle(Offset(500f, 200f), cropRect, canvasSize, hitRadius)
        )
        assertEquals(
            SlockCropDragHandle.Bottom,
            slockCropDetectHandle(Offset(500f, 800f), cropRect, canvasSize, hitRadius)
        )
        assertEquals(
            SlockCropDragHandle.Left,
            slockCropDetectHandle(Offset(200f, 500f), cropRect, canvasSize, hitRadius)
        )
        assertEquals(
            SlockCropDragHandle.Right,
            slockCropDetectHandle(Offset(800f, 500f), cropRect, canvasSize, hitRadius)
        )

        // Center
        assertEquals(
            SlockCropDragHandle.Center,
            slockCropDetectHandle(Offset(500f, 500f), cropRect, canvasSize, hitRadius)
        )

        // Outside crop box
        assertEquals(
            SlockCropDragHandle.None,
            slockCropDetectHandle(Offset(50f, 50f), cropRect, canvasSize, hitRadius)
        )
    }

    @Test
    fun handleDetectionZeroCanvasReturnsNone() {
        val cropRect = SlockComposerImageCropRect.Uncropped
        assertEquals(
            SlockCropDragHandle.None,
            slockCropDetectHandle(Offset(10f, 10f), cropRect, Size.Zero)
        )
    }

    @Test
    fun cropDragCornerResizesBoundingBox() {
        val canvasSize = Size(1000f, 1000f)
        val initial = SlockComposerImageCropRect(0.1f, 0.1f, 0.9f, 0.9f)

        // Drag TopLeft inward (+50px, +50px)
        val afterTopLeft = slockCropApplyDrag(
            handle = SlockCropDragHandle.TopLeft,
            cropRect = initial,
            dragAmount = Offset(50f, 50f),
            canvasSize = canvasSize,
        )
        assertEquals(0.15f, afterTopLeft.left, 0.001f)
        assertEquals(0.15f, afterTopLeft.top, 0.001f)
        assertEquals(0.9f, afterTopLeft.right, 0.001f)
        assertEquals(0.9f, afterTopLeft.bottom, 0.001f)

        // Drag BottomRight outward (+50px, +50px)
        val afterBottomRight = slockCropApplyDrag(
            handle = SlockCropDragHandle.BottomRight,
            cropRect = afterTopLeft,
            dragAmount = Offset(50f, 50f),
            canvasSize = canvasSize,
        )
        assertEquals(0.15f, afterBottomRight.left, 0.001f)
        assertEquals(0.15f, afterBottomRight.top, 0.001f)
        assertEquals(0.95f, afterBottomRight.right, 0.001f)
        assertEquals(0.95f, afterBottomRight.bottom, 0.001f)
    }

    @Test
    fun cropDragCornerRespectsMinDimensionFraction() {
        val canvasSize = Size(1000f, 1000f)
        val initial = SlockComposerImageCropRect(0.1f, 0.1f, 0.5f, 0.5f)

        // Drag TopLeft past right boundary
        val clamped = slockCropApplyDrag(
            handle = SlockCropDragHandle.TopLeft,
            cropRect = initial,
            dragAmount = Offset(800f, 800f),
            canvasSize = canvasSize,
            minDimensionFraction = 0.1f,
        )
        assertEquals(0.4f, clamped.left, 0.001f)
        assertEquals(0.4f, clamped.top, 0.001f)
    }

    @Test
    fun cropDragCenterTranslatesWithoutChangingSize() {
        val canvasSize = Size(1000f, 1000f)
        val initial = SlockComposerImageCropRect(0.2f, 0.2f, 0.6f, 0.6f)
        val width = initial.widthFraction
        val height = initial.heightFraction

        // Drag Center (+100px, +100px)
        val moved = slockCropApplyDrag(
            handle = SlockCropDragHandle.Center,
            cropRect = initial,
            dragAmount = Offset(100f, 100f),
            canvasSize = canvasSize,
        )
        assertEquals(0.3f, moved.left, 0.001f)
        assertEquals(0.3f, moved.top, 0.001f)
        assertEquals(0.7f, moved.right, 0.001f)
        assertEquals(0.7f, moved.bottom, 0.001f)
        assertEquals(width, moved.widthFraction, 0.001f)
        assertEquals(height, moved.heightFraction, 0.001f)

        // Drag Center beyond right/bottom boundary
        val clamped = slockCropApplyDrag(
            handle = SlockCropDragHandle.Center,
            cropRect = moved,
            dragAmount = Offset(500f, 500f),
            canvasSize = canvasSize,
        )
        assertEquals(0.6f, clamped.left, 0.001f)
        assertEquals(0.6f, clamped.top, 0.001f)
        assertEquals(1.0f, clamped.right, 0.001f)
        assertEquals(1.0f, clamped.bottom, 0.001f)
    }

    @Test
    fun cropDragNoneDoesNotMutate() {
        val initial = SlockComposerImageCropRect(0.2f, 0.2f, 0.6f, 0.6f)
        val result = slockCropApplyDrag(
            handle = SlockCropDragHandle.None,
            cropRect = initial,
            dragAmount = Offset(100f, 100f),
            canvasSize = Size(1000f, 1000f),
        )
        assertEquals(initial, result)
    }

    @Test
    fun remapPointAccuratelyTranslatesAcrossCropSpaces() {
        val oldCrop = SlockComposerImageCropRect.Uncropped
        val newCrop = SlockComposerImageCropRect(0.25f, 0.25f, 0.75f, 0.75f)
        val oldCanvas = Size(1000f, 1000f)
        val newCanvas = Size(500f, 500f)

        // Center of old canvas (500, 500) corresponds to norm (0.5, 0.5)
        // In newCrop (0.25..0.75), norm 0.5 is at relative 0.5 -> center of newCanvas (250, 250)
        val remappedCenter = slockRemapPoint(
            point = Offset(500f, 500f),
            oldCrop = oldCrop,
            newCrop = newCrop,
            oldCanvasSize = oldCanvas,
            newCanvasSize = newCanvas,
        )
        assertEquals(250f, remappedCenter.x, 0.01f)
        assertEquals(250f, remappedCenter.y, 0.01f)
    }

    @Test
    fun remapStrokePreservesColorAndRemapsPoints() {
        val stroke = SlockComposerImageStroke(
            points = listOf(Offset(500f, 500f), Offset(600f, 600f)),
            color = Color.Red,
        )
        val oldCrop = SlockComposerImageCropRect.Uncropped
        val newCrop = SlockComposerImageCropRect(0.25f, 0.25f, 0.75f, 0.75f)
        val oldCanvas = Size(1000f, 1000f)
        val newCanvas = Size(500f, 500f)

        val remapped = slockRemapStroke(stroke, oldCrop, newCrop, oldCanvas, newCanvas)
        assertEquals(Color.Red, remapped.color)
        assertEquals(2, remapped.points.size)
        assertEquals(250f, remapped.points[0].x, 0.01f)
        assertEquals(250f, remapped.points[0].y, 0.01f)
    }

    @Test
    fun captureSizeCalculatesEffectiveCropAspectFit() {
        // Square stage 400x400, image 1000x500 (2:1 aspect ratio)
        val uncroppedCapture = slockComposerImageEditorCaptureSize(
            imageWidth = 1000,
            imageHeight = 500,
            maxWidth = 400f,
            maxHeight = 400f,
            cropRect = SlockComposerImageCropRect.Uncropped,
        )
        assertEquals(400f, uncroppedCapture?.width)
        assertEquals(200f, uncroppedCapture?.height)

        // Crop to left half (500x500 square)
        val croppedSquare = slockComposerImageEditorCaptureSize(
            imageWidth = 1000,
            imageHeight = 500,
            maxWidth = 400f,
            maxHeight = 400f,
            cropRect = SlockComposerImageCropRect(0f, 0f, 0.5f, 1f),
        )
        assertEquals(400f, croppedSquare?.width)
        assertEquals(400f, croppedSquare?.height)
    }

    @Test
    fun backgroundImageSrcRectCalculatesCorrectSubRectangle() {
        val imageWidth = 1000
        val imageHeight = 800

        // Uncropped
        val (uncroppedOffset, uncroppedSize) = slockComposerBackgroundImageSrcRect(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            cropRect = SlockComposerImageCropRect.Uncropped,
        )
        assertEquals(IntOffset(0, 0), uncroppedOffset)
        assertEquals(IntSize(1000, 800), uncroppedSize)

        // Sub-crop
        val (croppedOffset, croppedSize) = slockComposerBackgroundImageSrcRect(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            cropRect = SlockComposerImageCropRect(0.2f, 0.3f, 0.7f, 0.8f),
        )
        assertEquals(IntOffset(200, 240), croppedOffset)
        assertEquals(IntSize(500, 400), croppedSize)

        // Zero/negative dimensions
        val (zeroOffset, zeroSize) = slockComposerBackgroundImageSrcRect(
            imageWidth = 0,
            imageHeight = 0,
        )
        assertEquals(IntOffset.Zero, zeroOffset)
        assertEquals(IntSize.Zero, zeroSize)
    }

    @Test
    fun applyCropToContentUpdatesCropRectAndRemapsStrokesAndAnnotations() {
        val initialContent = SlockComposerImageEditorContent(
            strokes = listOf(
                SlockComposerImageStroke(
                    points = listOf(Offset(250f, 250f), Offset(300f, 300f)),
                    color = Color.Red,
                )
            ),
            textAnnotations = listOf(
                SlockComposerImageTextAnnotation(
                    id = 1,
                    text = "Hello",
                    position = Offset(250f, 250f),
                )
            ),
            mosaicTiles = setOf(
                SlockComposerImageMosaicTile(
                    column = 10,
                    row = 10,
                )
            ),
            cropRect = SlockComposerImageCropRect.Uncropped,
        )

        // Sub-crop: center region (0.25..0.75 in both X and Y)
        val newCrop = SlockComposerImageCropRect(0.25f, 0.25f, 0.75f, 0.75f)
        val imageWidth = 1000
        val imageHeight = 1000
        val maxWidth = 500f
        val maxHeight = 500f

        val updated = slockApplyCropToContent(
            content = initialContent,
            newCrop = newCrop,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
        )

        assertEquals(newCrop, updated.cropRect)
        assertEquals(1, updated.strokes.size)
        // Center (250, 250) of old 500x500 canvas corresponds to norm (0.5, 0.5)
        // In newCrop (0.25..0.75), norm 0.5 is at relative 0.5 -> center (250, 250) of new 500x500 canvas
        assertEquals(250f, updated.strokes[0].points[0].x, 0.1f)
        assertEquals(250f, updated.strokes[0].points[0].y, 0.1f)
        assertEquals(1, updated.textAnnotations.size)
        assertEquals(250f, updated.textAnnotations[0].position.x, 0.1f)
        assertEquals(250f, updated.textAnnotations[0].position.y, 0.1f)
    }

    @Test
    fun applyCropToContentWithSameCropReturnsIdenticalContent() {
        val content = SlockComposerImageEditorContent(
            cropRect = SlockComposerImageCropRect(0.1f, 0.1f, 0.9f, 0.9f)
        )
        val result = slockApplyCropToContent(
            content = content,
            newCrop = SlockComposerImageCropRect(0.1f, 0.1f, 0.9f, 0.9f),
            imageWidth = 1000,
            imageHeight = 1000,
            maxWidth = 500f,
            maxHeight = 500f,
        )
        assertEquals(content, result)
    }
}
