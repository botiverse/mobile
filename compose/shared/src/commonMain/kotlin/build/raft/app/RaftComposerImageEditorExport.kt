package build.raft.app

import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Full-resolution export of the composer image editor.
 *
 * The editor keeps every edit (strokes, mosaic tiles, text positions) in the pixels of its
 * on-screen canvas: the crop region fitted into the stage. Saving used to snapshot that canvas, so
 * a 4000 px photo came out at roughly screen size. The export instead re-renders the same edit
 * stack into an offscreen bitmap the size of the crop region at the source's own resolution: the
 * canvas is scaled from editor-canvas pixels to export pixels, so strokes, mosaic blocks and text
 * keep the proportions they had on screen while the photo underneath keeps its detail.
 */

/** Longest side of a saved edit; larger sources are scaled down to it. */
internal const val RaftComposerImageExportMaxLongEdgePx: Int = 8192

/** Pixel budget of a saved edit (~40 MP, 160 MB as ARGB_8888); larger sources are scaled down. */
internal const val RaftComposerImageExportMaxPixels: Long = 40_000_000L

/** Pencil stroke width in editor-canvas pixels (on screen and, scaled, in the export). */
internal const val RaftComposerImagePencilStrokeWidthPx: Float = 6f

/**
 * Size of the saved image: the crop region of a [sourceWidth] x [sourceHeight] source at the
 * source's own resolution, scaled down only when it exceeds [maxLongEdgePx] or [maxPixels].
 */
internal fun raftComposerImageExportSize(
    sourceWidth: Int,
    sourceHeight: Int,
    cropRect: RaftComposerImageCropRect = RaftComposerImageCropRect.Uncropped,
    maxLongEdgePx: Int = RaftComposerImageExportMaxLongEdgePx,
    maxPixels: Long = RaftComposerImageExportMaxPixels,
): IntSize? {
    if (sourceWidth <= 0 || sourceHeight <= 0) return null
    val (_, region) = raftComposerBackgroundImageSrcRect(sourceWidth, sourceHeight, cropRect)
    if (region.width <= 0 || region.height <= 0) return null
    var scale = 1.0
    val longEdge = max(region.width, region.height)
    if (maxLongEdgePx in 1 until longEdge) {
        scale = min(scale, maxLongEdgePx.toDouble() / longEdge)
    }
    val pixels = region.width.toLong() * region.height.toLong()
    if (maxPixels in 1 until pixels) {
        scale = min(scale, sqrt(maxPixels.toDouble() / pixels))
    }
    if (scale >= 1.0) return region
    return IntSize(
        // The epsilon keeps exact products (3000 * 8192 / 12000 = 2048) from flooring one short.
        width = floor(region.width * scale + 1e-6).toInt().coerceAtLeast(1),
        height = floor(region.height * scale + 1e-6).toInt().coerceAtLeast(1),
    )
}

/**
 * Decode bound (longer edge) for the export's source: the original size, but no more than
 * [maxLongEdgePx] and no more than [maxPixels] for a source of the displayed aspect ratio, so the
 * decoded bitmap itself stays within the export budget. [displayWidth] x [displayHeight] is the
 * editor's (screen-bounded) decode, used only for its aspect ratio.
 */
internal fun raftComposerImageExportDecodeEdgePx(
    displayWidth: Int,
    displayHeight: Int,
    maxLongEdgePx: Int = RaftComposerImageExportMaxLongEdgePx,
    maxPixels: Long = RaftComposerImageExportMaxPixels,
): Int {
    if (displayWidth <= 0 || displayHeight <= 0) return maxLongEdgePx
    val ratio = max(displayWidth, displayHeight).toDouble() / min(displayWidth, displayHeight)
    val pixelBoundEdge = floor(sqrt(maxPixels.toDouble() * ratio)).toInt()
    return min(maxLongEdgePx, pixelBoundEdge).coerceAtLeast(1)
}

/** Maps a point in editor-canvas pixels to the pixels of an export of [exportSize]. */
internal fun raftComposerImageCanvasToExport(
    point: Offset,
    canvasSize: IntSize,
    exportSize: IntSize,
): Offset {
    if (canvasSize.width <= 0 || canvasSize.height <= 0) return point
    return Offset(
        x = point.x * exportSize.width / canvasSize.width,
        y = point.y * exportSize.height / canvasSize.height,
    )
}

/**
 * Maps a point in editor-canvas pixels to pixels of the whole (uncropped) source image: the
 * canvas shows exactly the crop region, so the canvas spans the crop's source rectangle.
 */
internal fun raftComposerImageCanvasToSource(
    point: Offset,
    canvasSize: IntSize,
    cropRect: RaftComposerImageCropRect,
    sourceWidth: Int,
    sourceHeight: Int,
): Offset {
    if (canvasSize.width <= 0 || canvasSize.height <= 0) return point
    val (origin, region) = raftComposerBackgroundImageSrcRect(sourceWidth, sourceHeight, cropRect)
    return Offset(
        x = origin.x + point.x * region.width / canvasSize.width,
        y = origin.y + point.y * region.height / canvasSize.height,
    )
}

/** Measures and styles text annotations the way the on-screen annotation boxes do. */
internal class RaftComposerImageExportText(
    val measurer: TextMeasurer,
    val style: TextStyle,
    val borderColor: Color,
)

/**
 * Renders [content] over [source] into a new bitmap of [raftComposerImageExportSize]. Edit
 * coordinates are editor-canvas pixels of a canvas of [canvasSize]; [density] is the editor's, so
 * dp-sized text boxes match the screen. Mosaic block colours are sampled from [mosaicSource] (the
 * editor's on-screen decode), so each block has the colour the user saw rather than one
 * full-resolution pixel. Returns null when sizes are unknown.
 */
internal fun renderRaftComposerImageExport(
    source: ImageBitmap,
    content: RaftComposerImageEditorContent,
    canvasSize: IntSize,
    density: Density,
    text: RaftComposerImageExportText?,
    mosaicSource: ImageBitmap = source,
    maxLongEdgePx: Int = RaftComposerImageExportMaxLongEdgePx,
    maxPixels: Long = RaftComposerImageExportMaxPixels,
): ImageBitmap? {
    if (canvasSize.width <= 0 || canvasSize.height <= 0) return null
    val exportSize =
        raftComposerImageExportSize(
            sourceWidth = source.width,
            sourceHeight = source.height,
            cropRect = content.cropRect,
            maxLongEdgePx = maxLongEdgePx,
            maxPixels = maxPixels,
        ) ?: return null
    val bitmap = ImageBitmap(exportSize.width, exportSize.height)
    val canvas = Canvas(bitmap)
    canvas.scale(
        sx = exportSize.width.toFloat() / canvasSize.width,
        sy = exportSize.height.toFloat() / canvasSize.height,
    )
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, canvasSize.toSize()) {
        drawRaftComposerBackgroundImage(image = source, cropRect = content.cropRect)
        drawRaftComposerImageDrawOperations(image = mosaicSource, content = content, cropRect = content.cropRect)
        if (text != null) {
            content.textAnnotations.forEach { annotation ->
                drawRaftComposerImageTextAnnotation(annotation, canvasSize, text)
            }
        }
    }
    return bitmap
}

/** Mosaic + pencil operations of [content] in order (shared by the screen and the export). */
internal fun DrawScope.drawRaftComposerImageDrawOperations(
    image: ImageBitmap?,
    content: RaftComposerImageEditorContent,
    cropRect: RaftComposerImageCropRect,
) {
    content.raftComposerImageResolvedDrawOperations().forEach { operation ->
        when (operation) {
            is RaftComposerImageDrawOperation.Mosaic ->
                drawRaftComposerImageMosaic(
                    image = image,
                    tiles = operation.tiles,
                    cropRect = cropRect,
                )
            is RaftComposerImageDrawOperation.Pencil ->
                operation.stroke.points.windowed(2).forEach { segment ->
                    drawLine(
                        color = operation.stroke.color,
                        start = segment[0],
                        end = segment[1],
                        strokeWidth = RaftComposerImagePencilStrokeWidthPx,
                        cap = StrokeCap.Round,
                    )
                }
        }
    }
}

/** On-screen annotation box metrics (RaftFilePreviewComposerImageEditor's text Box). */
private val RaftComposerImageTextMinWidth = 48.dp
private val RaftComposerImageTextMaxWidth = 260.dp
private val RaftComposerImageTextPaddingHorizontal = 5.dp
private val RaftComposerImageTextPaddingVertical = 2.dp
private val RaftComposerImageTextBorderWidth = 1.dp
internal val RaftComposerImageTextBackground = Color(0xCCFFFFFF) // raft-color: text annotation drawn on the photo
internal const val RaftComposerImageTextMaxLines = 3

/**
 * Top-left of an annotation box of [boxSize] in a canvas of [canvasSize]: the editor stacks the
 * boxes in a `contentAlignment = Center` Box and moves them with `Modifier.offset(position)`.
 */
internal fun raftComposerImageTextBoxTopLeft(
    position: Offset,
    boxSize: IntSize,
    canvasSize: IntSize,
): IntOffset =
    Alignment.Center.align(boxSize, canvasSize, LayoutDirection.Ltr) +
        IntOffset(position.x.roundToInt(), position.y.roundToInt())

private fun DrawScope.drawRaftComposerImageTextAnnotation(
    annotation: RaftComposerImageTextAnnotation,
    canvasSize: IntSize,
    text: RaftComposerImageExportText,
) {
    val paddingH = RaftComposerImageTextPaddingHorizontal.roundToPx()
    val paddingV = RaftComposerImageTextPaddingVertical.roundToPx()
    val minWidth = RaftComposerImageTextMinWidth.roundToPx()
    val maxWidth = RaftComposerImageTextMaxWidth.roundToPx()
    val layout =
        text.measurer.measure(
            text = annotation.text,
            style = text.style,
            overflow = TextOverflow.Ellipsis,
            maxLines = RaftComposerImageTextMaxLines,
            constraints =
                Constraints(
                    minWidth = (minWidth - 2 * paddingH).coerceAtLeast(0),
                    maxWidth = (maxWidth - 2 * paddingH).coerceAtLeast(1),
                ),
            density = this,
            layoutDirection = LayoutDirection.Ltr,
        )
    val boxSize =
        IntSize(
            width = (layout.size.width + 2 * paddingH).coerceIn(minWidth, maxWidth),
            height = layout.size.height + 2 * paddingV,
        )
    val topLeft = raftComposerImageTextBoxTopLeft(annotation.position, boxSize, canvasSize)
    val boxOffset = Offset(topLeft.x.toFloat(), topLeft.y.toFloat())
    val boxSizeF = boxSize.toSize()
    drawRect(color = RaftComposerImageTextBackground, topLeft = boxOffset, size = boxSizeF)
    val border = RaftComposerImageTextBorderWidth.toPx()
    drawRect(
        color = text.borderColor,
        topLeft = boxOffset + Offset(border / 2f, border / 2f),
        size = Size(boxSizeF.width - border, boxSizeF.height - border),
        style = Stroke(width = border),
    )
    drawText(
        textLayoutResult = layout,
        topLeft = boxOffset + Offset(paddingH.toFloat(), paddingV.toFloat()),
    )
}
