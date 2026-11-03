package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Geometry of the composer image editor's rotate, crop-aspect and view-zoom steps.
 *
 * The editor keeps every edit in the pixels of its unrotated canvas: the crop region of the photo
 * fitted into the stage. A quarter turn does not move the edits; the canvas is laid out unrotated
 * and turned as a whole (on screen with a graphics layer, in the export with the canvas matrix),
 * so a stroke stays on the pixels it was drawn over. Only the fit changes: a turned canvas has to
 * fit the stage with its sides swapped, so its scale changes and the edits are rescaled with it
 * (the same remap a crop uses, with the crop unchanged).
 */

/** Clockwise quarter turns, normalised to 0..3. */
internal fun raftComposerImageNormalizeQuarterTurns(turns: Int): Int = ((turns % 4) + 4) % 4

/** Whether [turns] swaps the image's width and height. */
internal fun raftComposerImageTurnSwapsSides(turns: Int): Boolean =
    raftComposerImageNormalizeQuarterTurns(turns) % 2 == 1

/**
 * The box the unrotated canvas must fit so that, turned by [turns], it fits [stageWidth] x
 * [stageHeight]: the stage itself, or the stage with its sides swapped for an odd turn.
 */
internal fun raftComposerImageEditorFitBox(stageWidth: Float, stageHeight: Float, turns: Int): Size =
    if (raftComposerImageTurnSwapsSides(turns)) Size(stageHeight, stageWidth) else Size(stageWidth, stageHeight)

/**
 * Unrotated layout size of the editor canvas for an image of [imageWidth] x [imageHeight] cropped
 * to [cropRect] and turned by [turns], in the unit of [stageWidth] / [stageHeight].
 */
internal fun raftComposerImageEditorCanvasSize(
    imageWidth: Int,
    imageHeight: Int,
    stageWidth: Float,
    stageHeight: Float,
    cropRect: RaftComposerImageCropRect,
    turns: Int,
): Size? {
    val box = raftComposerImageEditorFitBox(stageWidth, stageHeight, turns)
    return raftComposerImageEditorCaptureSize(
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        maxWidth = box.width,
        maxHeight = box.height,
        cropRect = cropRect,
    )
}

/**
 * Remaps every edit of [content] from a canvas of [oldCanvasSize] showing its crop to a canvas of
 * [newCanvasSize] showing [newCrop]. Unlike [raftRemapContentForCrop] it also rescales when the
 * crop is unchanged (a turn changes only the canvas size).
 */
internal fun raftRemapContentForCanvas(
    content: RaftComposerImageEditorContent,
    newCrop: RaftComposerImageCropRect,
    oldCanvasSize: Size,
    newCanvasSize: Size,
): RaftComposerImageEditorContent {
    val oldCrop = content.cropRect
    if (oldCrop == newCrop && oldCanvasSize == newCanvasSize) return content
    return content.copy(
        cropRect = newCrop,
        strokes = content.strokes.map { raftRemapStroke(it, oldCrop, newCrop, oldCanvasSize, newCanvasSize) },
        textAnnotations = raftRemapTextAnnotations(content.textAnnotations, oldCrop, newCrop, oldCanvasSize, newCanvasSize),
        mosaicTiles = raftRemapMosaicTiles(content.mosaicTiles, oldCrop, newCrop, oldCanvasSize, newCanvasSize),
        drawOperations =
            content.raftComposerImageResolvedDrawOperations().map { op ->
                when (op) {
                    is RaftComposerImageDrawOperation.Mosaic ->
                        RaftComposerImageDrawOperation.Mosaic(
                            raftRemapMosaicTiles(op.tiles, oldCrop, newCrop, oldCanvasSize, newCanvasSize)
                        )
                    is RaftComposerImageDrawOperation.Pencil ->
                        RaftComposerImageDrawOperation.Pencil(
                            raftRemapStroke(op.stroke, oldCrop, newCrop, oldCanvasSize, newCanvasSize)
                        )
                }
            },
    )
}

/**
 * Turns [content] by [deltaTurns] clockwise quarter turns. [stageWidth] / [stageHeight] are the
 * stage in canvas pixels; the edits are rescaled from the old fit to the new one.
 */
internal fun raftApplyRotateToContent(
    content: RaftComposerImageEditorContent,
    imageWidth: Int,
    imageHeight: Int,
    stageWidth: Float,
    stageHeight: Float,
    deltaTurns: Int = 1,
): RaftComposerImageEditorContent {
    val oldTurns = content.rotationQuarterTurns
    val newTurns = raftComposerImageNormalizeQuarterTurns(oldTurns + deltaTurns)
    if (newTurns == raftComposerImageNormalizeQuarterTurns(oldTurns)) return content
    val turned = content.copy(rotationQuarterTurns = newTurns)
    val oldCanvas =
        raftComposerImageEditorCanvasSize(imageWidth, imageHeight, stageWidth, stageHeight, content.cropRect, oldTurns)
            ?: return turned
    val newCanvas =
        raftComposerImageEditorCanvasSize(imageWidth, imageHeight, stageWidth, stageHeight, content.cropRect, newTurns)
            ?: return turned
    return raftRemapContentForCanvas(turned, content.cropRect, oldCanvas, newCanvas)
}

/**
 * Size of the export once turned: the unrotated render's sides, swapped for an odd turn.
 */
internal fun raftComposerImageRotatedSize(size: IntSize, turns: Int): IntSize =
    if (raftComposerImageTurnSwapsSides(turns)) IntSize(size.height, size.width) else size

/**
 * Where a point of an unrotated picture of [size] lands once the picture is turned by [turns]
 * clockwise quarter turns (the turned picture's own top-left is the origin).
 */
internal fun raftComposerImageRotatePoint(point: Offset, size: Size, turns: Int): Offset =
    when (raftComposerImageNormalizeQuarterTurns(turns)) {
        1 -> Offset(size.height - point.y, point.x)
        2 -> Offset(size.width - point.x, size.height - point.y)
        3 -> Offset(point.y, size.width - point.x)
        else -> point
    }

/** Inverse of [raftComposerImageRotatePoint]: [size] is the unrotated picture's size. */
internal fun raftComposerImageUnrotatePoint(point: Offset, size: Size, turns: Int): Offset =
    when (raftComposerImageNormalizeQuarterTurns(turns)) {
        1 -> Offset(point.y, size.height - point.x)
        2 -> Offset(size.width - point.x, size.height - point.y)
        3 -> Offset(size.width - point.y, point.x)
        else -> point
    }

/*
 * View transform: how the stage shows the canvas. The canvas (unrotated, [canvasSize]) is centred
 * in the stage, turned about its centre, then zoomed about its centre and panned. This mirrors the
 * graphics layer the editor applies; pointer input arrives already in canvas coordinates, these are
 * for tests and for clamping the pan.
 */
internal data class RaftComposerImageViewTransform(
    val zoom: Float = 1f,
    val pan: Offset = Offset.Zero,
) {
    val isIdentity: Boolean get() = zoom == 1f && pan == Offset.Zero

    companion object {
        val Identity = RaftComposerImageViewTransform()
        const val MinZoom = 1f
        const val MaxZoom = 6f
    }
}

/** A canvas point to stage coordinates under [turns] and [view]. */
internal fun raftComposerImageCanvasToView(
    point: Offset,
    canvasSize: Size,
    stageSize: Size,
    turns: Int,
    view: RaftComposerImageViewTransform = RaftComposerImageViewTransform.Identity,
): Offset {
    val centred = point - Offset(canvasSize.width / 2f, canvasSize.height / 2f)
    val turned =
        when (raftComposerImageNormalizeQuarterTurns(turns)) {
            1 -> Offset(-centred.y, centred.x)
            2 -> Offset(-centred.x, -centred.y)
            3 -> Offset(centred.y, -centred.x)
            else -> centred
        }
    return Offset(stageSize.width / 2f, stageSize.height / 2f) + turned * view.zoom + view.pan
}

/** Inverse of [raftComposerImageCanvasToView]. */
internal fun raftComposerImageViewToCanvas(
    point: Offset,
    canvasSize: Size,
    stageSize: Size,
    turns: Int,
    view: RaftComposerImageViewTransform = RaftComposerImageViewTransform.Identity,
): Offset {
    val zoom = if (view.zoom == 0f) 1f else view.zoom
    val turned = (point - view.pan - Offset(stageSize.width / 2f, stageSize.height / 2f)) / zoom
    val centred =
        when (raftComposerImageNormalizeQuarterTurns(turns)) {
            1 -> Offset(turned.y, -turned.x)
            2 -> Offset(-turned.x, -turned.y)
            3 -> Offset(-turned.y, turned.x)
            else -> turned
        }
    return centred + Offset(canvasSize.width / 2f, canvasSize.height / 2f)
}

/**
 * Applies one pinch/pan step: zoom by [zoomChange] about [centroid] (stage coordinates, relative to
 * the stage centre's origin like [RaftComposerImageViewTransform.pan]) then move by [panChange].
 * The zoom stays within [RaftComposerImageViewTransform.MinZoom]..MaxZoom and the pan keeps the
 * zoomed picture ([visibleSize], the turned canvas on the stage) covering the stage centre.
 */
internal fun RaftComposerImageViewTransform.raftComposerImagePinch(
    centroid: Offset,
    zoomChange: Float,
    panChange: Offset,
    stageSize: Size,
    visibleSize: Size,
): RaftComposerImageViewTransform {
    val nextZoom =
        (zoom * zoomChange).coerceIn(RaftComposerImageViewTransform.MinZoom, RaftComposerImageViewTransform.MaxZoom)
    val appliedChange = if (zoom == 0f) 1f else nextZoom / zoom
    val stageCentre = Offset(stageSize.width / 2f, stageSize.height / 2f)
    // Keep the point under the centroid fixed while zooming.
    val fromCentre = centroid - stageCentre
    var nextPan = (pan - fromCentre) * appliedChange + fromCentre + panChange
    val maxX = max(0f, (visibleSize.width * nextZoom - stageSize.width) / 2f)
    val maxY = max(0f, (visibleSize.height * nextZoom - stageSize.height) / 2f)
    nextPan = Offset(nextPan.x.coerceIn(-maxX, maxX), nextPan.y.coerceIn(-maxY, maxY))
    if (nextZoom <= RaftComposerImageViewTransform.MinZoom) nextPan = Offset.Zero
    return RaftComposerImageViewTransform(zoom = nextZoom, pan = nextPan)
}

/*
 * Crop aspect: the crop rectangle is normalised to the unrotated image, so an aspect in image
 * pixels w/h is a normalised ratio (w / h) * imageHeight / imageWidth.
 */
internal enum class RaftComposerImageCropAspect {
    Free,
    Square,
    Original,
}

/** The aspect (pixel width / pixel height) the crop must keep, or null for a free crop. */
internal fun RaftComposerImageCropAspect.pixelRatio(imageWidth: Int, imageHeight: Int): Float? =
    when (this) {
        RaftComposerImageCropAspect.Free -> null
        RaftComposerImageCropAspect.Square -> 1f
        RaftComposerImageCropAspect.Original ->
            if (imageWidth > 0 && imageHeight > 0) imageWidth.toFloat() / imageHeight else null
    }

/** Pixel aspect of [cropRect] over an image of [imageWidth] x [imageHeight]. */
internal fun raftCropPixelRatio(cropRect: RaftComposerImageCropRect, imageWidth: Int, imageHeight: Int): Float =
    (cropRect.widthFraction * imageWidth) / (cropRect.heightFraction * imageHeight)

/** Normalised height per normalised width for a pixel [ratio]. */
private fun raftCropHeightPerWidth(ratio: Float, imageWidth: Int, imageHeight: Int): Float =
    imageWidth.toFloat() / (ratio * imageHeight)

/**
 * The largest crop of pixel [ratio] that fits the image, centred on [current]'s centre and moved
 * inside the image where needed. Choosing an aspect starts from this rectangle.
 */
internal fun raftCropRectForAspect(
    current: RaftComposerImageCropRect,
    ratio: Float,
    imageWidth: Int,
    imageHeight: Int,
): RaftComposerImageCropRect {
    if (imageWidth <= 0 || imageHeight <= 0 || ratio <= 0f || !ratio.isFinite()) return current
    val k = raftCropHeightPerWidth(ratio, imageWidth, imageHeight)
    var width = 1f
    var height = k
    if (height > 1f) {
        height = 1f
        width = 1f / k
    }
    val cx = (current.left + current.right) / 2f
    val cy = (current.top + current.bottom) / 2f
    val left = (cx - width / 2f).coerceIn(0f, 1f - width)
    val top = (cy - height / 2f).coerceIn(0f, 1f - height)
    return RaftComposerImageCropRect(left = left, top = top, right = left + width, bottom = top + height)
}

/**
 * [raftCropApplyDrag] that keeps the crop at pixel [ratio] (null: free). A corner keeps its
 * opposite corner fixed; an edge keeps its opposite edge and grows the other side about the
 * centre; Center moves the rectangle unchanged.
 */
internal fun raftCropApplyDragWithAspect(
    handle: RaftCropDragHandle,
    cropRect: RaftComposerImageCropRect,
    dragAmount: Offset,
    canvasSize: Size,
    ratio: Float?,
    imageWidth: Int,
    imageHeight: Int,
    minDimensionFraction: Float = 0.1f,
): RaftComposerImageCropRect {
    val free = raftCropApplyDrag(handle, cropRect, dragAmount, canvasSize, minDimensionFraction)
    if (ratio == null || ratio <= 0f || !ratio.isFinite() || imageWidth <= 0 || imageHeight <= 0) return free
    val k = raftCropHeightPerWidth(ratio, imageWidth, imageHeight)
    // Smallest width whose height also meets the minimum.
    val minWidth = max(minDimensionFraction, minDimensionFraction / k)
    fun corner(anchorX: Float, anchorY: Float, growsRight: Boolean, growsDown: Boolean): RaftComposerImageCropRect {
        val freeWidth = free.right - free.left
        val freeHeight = free.bottom - free.top
        // Follow whichever side the finger moved further.
        var width = max(freeWidth, freeHeight / k)
        val availableWidth = if (growsRight) 1f - anchorX else anchorX
        val availableHeight = if (growsDown) 1f - anchorY else anchorY
        width = min(width, min(availableWidth, availableHeight / k)).coerceAtLeast(min(minWidth, availableWidth))
        val height = width * k
        val left = if (growsRight) anchorX else anchorX - width
        val top = if (growsDown) anchorY else anchorY - height
        return RaftComposerImageCropRect(left, top, left + width, top + height)
    }
    fun horizontalEdge(): RaftComposerImageCropRect {
        // Left / Right: width from the drag, height follows about the vertical centre.
        val anchorRight = handle == RaftCropDragHandle.Left
        val anchorX = if (anchorRight) cropRect.right else cropRect.left
        val cy = (cropRect.top + cropRect.bottom) / 2f
        val availableWidth = if (anchorRight) anchorX else 1f - anchorX
        var width = (free.right - free.left).coerceAtMost(availableWidth)
        width = min(width, 1f / k).coerceAtLeast(min(minWidth, availableWidth))
        val height = width * k
        val top = (cy - height / 2f).coerceIn(0f, max(0f, 1f - height))
        val left = if (anchorRight) anchorX - width else anchorX
        return RaftComposerImageCropRect(left, top, left + width, top + height)
    }
    fun verticalEdge(): RaftComposerImageCropRect {
        val anchorBottom = handle == RaftCropDragHandle.Top
        val anchorY = if (anchorBottom) cropRect.bottom else cropRect.top
        val cx = (cropRect.left + cropRect.right) / 2f
        val availableHeight = if (anchorBottom) anchorY else 1f - anchorY
        var height = (free.bottom - free.top).coerceAtMost(availableHeight)
        height = min(height, k).coerceAtLeast(min(minWidth * k, availableHeight))
        val width = height / k
        val left = (cx - width / 2f).coerceIn(0f, max(0f, 1f - width))
        val top = if (anchorBottom) anchorY - height else anchorY
        return RaftComposerImageCropRect(left, top, left + width, top + height)
    }
    return when (handle) {
        RaftCropDragHandle.TopLeft -> corner(cropRect.right, cropRect.bottom, growsRight = false, growsDown = false)
        RaftCropDragHandle.TopRight -> corner(cropRect.left, cropRect.bottom, growsRight = true, growsDown = false)
        RaftCropDragHandle.BottomLeft -> corner(cropRect.right, cropRect.top, growsRight = false, growsDown = true)
        RaftCropDragHandle.BottomRight -> corner(cropRect.left, cropRect.top, growsRight = true, growsDown = true)
        RaftCropDragHandle.Left, RaftCropDragHandle.Right -> horizontalEdge()
        RaftCropDragHandle.Top, RaftCropDragHandle.Bottom -> verticalEdge()
        RaftCropDragHandle.Center, RaftCropDragHandle.None -> free
    }
}

/** Whether [cropRect] already has pixel [ratio] (within a pixel's rounding). */
internal fun raftCropMatchesRatio(
    cropRect: RaftComposerImageCropRect,
    ratio: Float,
    imageWidth: Int,
    imageHeight: Int,
): Boolean = abs(raftCropPixelRatio(cropRect, imageWidth, imageHeight) - ratio) <= 0.01f * ratio

/** Pen widths, in editor-canvas pixels like [RaftComposerImagePencilStrokeWidthPx]. */
internal enum class RaftComposerImagePenWidth(val px: Float) {
    Thin(RaftComposerImagePencilStrokeWidthPx),
    Thick(RaftComposerImagePencilThickStrokeWidthPx),
}

internal const val RaftComposerImagePencilThickStrokeWidthPx: Float = 16f

/** Editor keyboard shortcuts (desktop; hardware keyboards on tablets). */
internal enum class RaftComposerImageEditorShortcut {
    Undo,
    Redo,
}

/**
 * Cmd/Ctrl+Z undoes, Cmd/Ctrl+Shift+Z and Ctrl+Y redo. [command] is Cmd or Ctrl held.
 */
internal fun raftComposerImageEditorShortcut(
    key: androidx.compose.ui.input.key.Key,
    command: Boolean,
    shift: Boolean,
): RaftComposerImageEditorShortcut? =
    when {
        !command -> null
        key == androidx.compose.ui.input.key.Key.Z ->
            if (shift) RaftComposerImageEditorShortcut.Redo else RaftComposerImageEditorShortcut.Undo
        key == androidx.compose.ui.input.key.Key.Y && !shift -> RaftComposerImageEditorShortcut.Redo
        else -> null
    }
