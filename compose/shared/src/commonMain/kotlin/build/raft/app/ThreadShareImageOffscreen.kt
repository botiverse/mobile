package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tencent.kuiklybase.markdown.compose.LocalRaftMarkdownFitWidth
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/*
 * The share image laid out again, off screen, at a width the user picks (artin, 2026-11-06):
 * the selected messages are composed once more at the image's own width, every wide block made to
 * fit it (no sideways scrolling: [LocalRaftMarkdownFitWidth]), recorded row by row into the
 * card [recordThreadShareImageRows] draws, and rasterised off the main thread. The web caps its share image at 768px and forces
 * tables to fit (slock `selectScreenshot.ts`); mobile offers that and narrower / wider widths.
 *
 * Android and desktop render this way. iOS still exports the image from its native screenshot of
 * the live rows (`exportThreadShareImage`); it switches by answering true from
 * [threadShareImageRendersOffscreen] once its GraphicsLayer snapshot and PNG save are wired.
 */

/** The width presets of Share preview's picker, as the whole card's width in dp. */
internal enum class ThreadShareImageWidth(val widthDp: Int?) {
    Phone(390),
    Tablet(600),
    Wide(768),
    Custom(null),
}

internal const val ThreadShareImageDefaultWidthDp = 390
internal const val ThreadShareImageCustomMinWidthDp = 320
internal const val ThreadShareImageCustomMaxWidthDp = 1080

/** How long the custom width slider rests before the image is laid out again at its width. */
internal const val ThreadShareImageWidthDebounceMs = 250L

/** The picker's choice: a preset, or [Custom][ThreadShareImageWidth.Custom] at [customWidthDp]. */
internal data class ThreadShareImageWidthChoice(
    val preset: ThreadShareImageWidth = ThreadShareImageWidth.Phone,
    val customWidthDp: Int = ThreadShareImageDefaultWidthDp,
) {
    val widthDp: Int
        get() = preset.widthDp ?: customWidthDp.coerceIn(ThreadShareImageCustomMinWidthDp, ThreadShareImageCustomMaxWidthDp)

    /** Picking Custom starts from the width shown now, so the image does not jump. */
    fun select(next: ThreadShareImageWidth): ThreadShareImageWidthChoice =
        if (next == ThreadShareImageWidth.Custom && preset != ThreadShareImageWidth.Custom) {
            copy(preset = next, customWidthDp = widthDp)
        } else {
            copy(preset = next)
        }
}

/**
 * Web `SCREENSHOT_PIXEL_BUDGET` (slock `screenshotBudget.ts`): the most pixels one share image
 * may hold. Past it a phone runs out of memory, so the image is drawn at a lower scale instead.
 */
internal const val ThreadShareImagePixelBudget = 16_000_000L

/** The longest side, in px, one share image (and one row's snapshot) may have: a GPU texture limit. */
internal const val ThreadShareImageMaxSidePx = 16_000

/** Web `resolveCapturePixelRatio`: the screen's pixel ratio, at least 2 and at most 3. */
internal fun threadShareImagePreferredScale(deviceDensity: Float): Float = deviceDensity.coerceIn(2f, 3f)

/**
 * The scale (px per dp) to draw a card [cardWidthDp] x [cardHeightDp] at: [preferredScale] when
 * the image stays within [budget] pixels and [ThreadShareImageMaxSidePx] a side, else the largest
 * scale that does (it may go below 1 for a very long selection: a softer image, never a failed one).
 */
internal fun threadShareImageScale(
    cardWidthDp: Float,
    cardHeightDp: Float,
    preferredScale: Float,
    budget: Long = ThreadShareImagePixelBudget,
): Float {
    if (cardWidthDp <= 0f || cardHeightDp <= 0f) return preferredScale
    var scale =
        min(
            min(preferredScale, sqrt(budget.toDouble() / (cardWidthDp.toDouble() * cardHeightDp)).toFloat()),
            ThreadShareImageMaxSidePx / maxOf(cardWidthDp, cardHeightDp),
        )
    // Rounding up to whole pixels can still tip it over; step down until it fits.
    while (scale > 0.05f && ceil(cardWidthDp * scale).toLong() * ceil(cardHeightDp * scale).toLong() > budget) {
        scale *= 0.99f
    }
    return scale
}

/**
 * The scale to lay the rows out again at, or null when the card drawn from [rowSizes] (laid out at
 * [scale]) may be drawn as it is. First the plan in dp ([threadShareImageScale]); then the card's
 * real size in px ([threadShareImageWidthPx], [threadShareImageHeightPx]): every row, frame and gap
 * rounds to whole pixels, which over many rows can still take it past [budget] pixels or
 * [maxSidePx] a side, so the scale steps down again until the real card fits.
 */
internal fun threadShareImageRelayoutScale(
    cardWidthDp: Int,
    rowSizes: List<IntSize>,
    scale: Float,
    preferredScale: Float,
    budget: Long = ThreadShareImagePixelBudget,
    maxSidePx: Int = ThreadShareImageMaxSidePx,
): Float? {
    if (rowSizes.isEmpty()) return null
    val rowsHeightDp = rowSizes.sumOf { it.height } / scale
    val planned =
        threadShareImageScale(
            cardWidthDp = cardWidthDp.toFloat(),
            cardHeightDp = rowsHeightDp + threadShareImageChromeHeightDp(rowSizes.size),
            preferredScale = preferredScale,
            budget = budget,
        )
    if (planned < scale * 0.98f) return planned
    val widthPx = threadShareImageWidthPx(rowSizes.maxOf { it.width }, scale).toLong()
    val heightPx = threadShareImageHeightPx(rowSizes.map { it.height }, scale).toLong()
    val longestPx = maxOf(widthPx, heightPx)
    if (widthPx * heightPx <= budget && longestPx <= maxSidePx) return null
    // Past the floor the rows are a smear anyway: draw what there is (an out-of-memory comes back as no image).
    if (scale <= ThreadShareImageMinScale) return null
    val factor =
        min(
            sqrt(budget.toDouble() / (widthPx.toDouble() * heightPx)).toFloat(),
            maxSidePx.toFloat() / longestPx,
        )
    // A little under the exact factor: the next layout rounds again.
    return (scale * min(factor, 1f) * 0.99f).coerceAtLeast(ThreadShareImageMinScale)
}

/** The lowest scale the share image is laid out at. */
internal const val ThreadShareImageMinScale = 0.05f

/**
 * The share image and the copy Share preview shows: [preview] is [image] scaled down to the
 * preview's width at the screen's density (or [image] itself when it is already that small), so
 * the preview never draws a bitmap above the GPU's texture limit.
 */
internal class ThreadShareRenderedImage(val image: ImageBitmap, val preview: ImageBitmap)

/** The preview is about this wide (the share preview dialog's content width, with room to spare). */
internal const val ThreadShareImagePreviewWidthDp = 400

/** The longest side of the preview's copy: 4096 px, the smallest max texture size the app meets. */
internal const val ThreadShareImagePreviewMaxSidePx = 4096

/**
 * The size of the preview's copy of a [width] x [height] image: at most [maxWidthPx] wide and
 * [maxSidePx] on either side, keeping the image's aspect; never larger than the image.
 */
internal fun threadShareImagePreviewSize(
    width: Int,
    height: Int,
    maxWidthPx: Int,
    maxSidePx: Int = ThreadShareImagePreviewMaxSidePx,
): IntSize {
    if (width <= 0 || height <= 0) return IntSize(width.coerceAtLeast(0), height.coerceAtLeast(0))
    val factor =
        minOf(
            1.0,
            maxWidthPx.coerceAtLeast(1).toDouble() / width,
            maxSidePx.toDouble() / width,
            maxSidePx.toDouble() / height,
        )
    if (factor >= 1.0) return IntSize(width, height)
    return IntSize((width * factor).roundToInt().coerceAtLeast(1), (height * factor).roundToInt().coerceAtLeast(1))
}

/**
 * [image] scaled to [size] for the preview: halved while it is more than twice the target (a
 * single bilinear step that far would drop whole text strokes), then drawn at [size]. Any thread.
 */
internal fun downscaleThreadShareImage(image: ImageBitmap, size: IntSize): ImageBitmap {
    if (size.width == image.width && size.height == image.height) return image
    var current = image
    while (current.width / 2 >= size.width && current.height / 2 >= size.height && current.width >= 2 && current.height >= 2) {
        current = drawThreadShareImageScaled(current, IntSize(current.width / 2, current.height / 2))
    }
    return if (current.width == size.width && current.height == size.height) current else drawThreadShareImageScaled(current, size)
}

private fun drawThreadShareImageScaled(source: ImageBitmap, size: IntSize): ImageBitmap {
    val target = ImageBitmap(size.width, size.height)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(target), Size(size.width.toFloat(), size.height.toFloat())) {
        drawImage(source, dstSize = size, filterQuality = FilterQuality.High)
    }
    return target
}

/** Waits for the off-screen rows to settle (avatars, status dots, Markdown layout) before the snapshot. */
internal val ThreadShareImageDefaultSettle: suspend () -> Unit = {
    repeat(4) { withFrameNanos {} }
    delay(250)
}

/**
 * Composes [rowCount] rows ([row]) off screen as the share image's rows for a card
 * [cardWidthDp] wide, and hands the finished image to [onRendered] (null when it could not be
 * made, with the copy Share preview shows); [onRendering] says a new one is on its way. It takes no room and draws nothing where it
 * sits; put it in the conversation's composition, so the rows read the same theme, text size
 * and resolvers as the live ones.
 *
 * The rows are laid out at the image's scale (the screen's, 2 to 3 px per dp, lowered to keep
 * the image within [ThreadShareImagePixelBudget]); [renderKey] changing renders again. The rows
 * are recorded on the main thread and rasterised on [rasterContext].
 */
@Composable
internal fun ThreadShareImageOffscreenRenderer(
    cardWidthDp: Int,
    rowCount: Int,
    renderKey: Any?,
    background: Color,
    onRendering: () -> Unit,
    onRendered: (ThreadShareRenderedImage?) -> Unit,
    settle: suspend () -> Unit = ThreadShareImageDefaultSettle,
    rasterContext: CoroutineContext = Dispatchers.Default,
    row: @Composable (index: Int) -> Unit,
) {
    val screenDensity = LocalDensity.current
    val preferredScale = threadShareImagePreferredScale(screenDensity.density)
    val previewMaxWidthPx = (ThreadShareImagePreviewWidthDp * screenDensity.density).roundToInt()
    var scale by remember(cardWidthDp, rowCount, renderKey, preferredScale) { mutableStateOf(preferredScale) }
    val renderDensity = remember(scale, screenDensity.fontScale) { Density(scale, screenDensity.fontScale) }
    val layers = remember { mutableStateMapOf<Int, GraphicsLayer>() }
    val rowSizes = remember { mutableStateMapOf<Int, IntSize>() }
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val currentOnRendering by rememberUpdatedState(onRendering)
    val currentOnRendered by rememberUpdatedState(onRendered)
    val currentBackground by rememberUpdatedState(background)
    LaunchedEffect(cardWidthDp, rowCount, renderKey, scale, previewMaxWidthPx) {
        currentOnRendering()
        if (rowCount <= 0) {
            currentOnRendered(null)
            return@LaunchedEffect
        }
        settle()
        val sizes = (0 until rowCount).map { index -> rowSizes[index] }
        if (sizes.any { it == null || it.width <= 0 }) {
            currentOnRendered(null)
            return@LaunchedEffect
        }
        val measured = sizes.map { it!! }
        val relayout =
            threadShareImageRelayoutScale(
                cardWidthDp = cardWidthDp,
                rowSizes = measured,
                scale = scale,
                preferredScale = preferredScale,
            )
        if (relayout != null) {
            // Too many pixels: lay the rows out again at the lower scale (this effect restarts).
            scale = relayout
            return@LaunchedEffect
        }
        // The header's 12px domain is a fixed size, as on the web; the rows keep the user's text size.
        val imageDensity = Density(scale, 1f)
        val measurer = TextMeasurer(fontFamilyResolver, imageDensity, LayoutDirection.Ltr, cacheSize = 0)
        val image =
            try {
                val rowLayers = (0 until rowCount).map { index -> layers[index] ?: error("share image row $index has no layer") }
                // Main thread: each row's layer re-runs its drawing into the recording (a GraphicsLayer
                // reads the composition's state there), so only the commands are captured here.
                val recording =
                    recordThreadShareImageRows(
                        rowSizes = measured,
                        density = imageDensity,
                        measurer = measurer,
                        background = currentBackground,
                    ) { index -> drawLayer(rowLayers[index]) }
                if (recording == null) {
                    null
                } else {
                    try {
                        // The pixels (up to the 16M px budget) and the preview's copy, off the main thread.
                        // A new width cancels this effect; the bands stop at the next check.
                        withContext(rasterContext) {
                            val job = coroutineContext[Job]
                            val full = recording.rasterise(checkCancelled = { job?.ensureActive() })
                            ensureActive()
                            val previewSize = threadShareImagePreviewSize(full.width, full.height, maxWidthPx = previewMaxWidthPx)
                            ThreadShareRenderedImage(image = full, preview = downscaleThreadShareImage(full, previewSize))
                        }
                    } finally {
                        recording.release()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Includes OutOfMemoryError on the JVM: no image rather than a crash.
                null
            }
        currentOnRendered(image)
    }
    Box(
        Modifier
            .clearAndSetSemantics {}
            // No room, nothing drawn here: the rows are only recorded into their layers.
            .layout { measurable, _ ->
                val placeable = measurable.measure(Constraints())
                layout(0, 0) { placeable.place(0, 0) }
            },
    ) {
        CompositionLocalProvider(
            LocalDensity provides renderDensity,
            LocalThreadShareCaptureActive provides true,
            LocalRaftMarkdownFitWidth provides true,
        ) {
            Column(Modifier.requiredWidth(cardWidthDp.dp - ThreadShareImageRowInset * 2)) {
                repeat(rowCount) { index ->
                    key(index) {
                        val layer = rememberGraphicsLayer()
                        DisposableEffect(layer) {
                            layers[index] = layer
                            onDispose {
                                if (layers[index] === layer) layers.remove(index)
                                rowSizes.remove(index)
                            }
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .onSizeChanged { rowSizes[index] = it }
                                .drawWithContent { layer.record { this@drawWithContent.drawContent() } }
                                .background(background),
                        ) {
                            row(index)
                        }
                    }
                }
            }
        }
    }
}

/** Name prefix of the share image's PNG in the app cache (Android's legacy export uses the same). */
internal const val ThreadShareImageFilePrefix = "raft-message-share-"

/**
 * The file path of [fileRef], a `file:` URI from [RaftPngFileWriter] (Android `file:///…`, desktop
 * `file:/…`, percent-encoded), for `saveThreadShareImage`; a plain path comes back as it is.
 */
internal fun threadShareImageFilePath(fileRef: String): String? {
    if (fileRef.isBlank()) return null
    if (!fileRef.startsWith("file:")) return fileRef
    val rest = fileRef.removePrefix("file:")
    // `file:///p` and `file://localhost/p` name the path after the authority; `file:/p` has none.
    val path =
        when {
            rest.startsWith("///") -> rest.substring(2)
            rest.startsWith("//") -> rest.substring(2).let { authorityAndPath -> authorityAndPath.substring(authorityAndPath.indexOf('/').coerceAtLeast(0)) }
            else -> rest
        }
    return threadSharePercentDecode(path).takeIf { it.isNotBlank() }
}

private fun threadSharePercentDecode(value: String): String {
    if ('%' !in value) return value
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%' && index + 2 <= value.lastIndex) {
            val code = value.substring(index + 1, index + 3).toIntOrNull(16)
            if (code != null) {
                bytes += code.toByte()
                index += 3
                continue
            }
        }
        char.toString().encodeToByteArray().forEach { bytes += it }
        index++
    }
    return bytes.toByteArray().decodeToString()
}

/**
 * Whether this platform makes the share image off screen ([ThreadShareImageOffscreenRenderer],
 * with the width picker); false keeps the native screenshot of the live rows (iOS for now).
 */
internal expect val threadShareImageRendersOffscreen: Boolean
