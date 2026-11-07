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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.delay

/*
 * The share image laid out again, off screen, at a width the user picks (artin, 2026-11-06):
 * the selected messages are composed once more at the image's own width, every wide block made to
 * fit it (no sideways scrolling: [LocalRaftMarkdownFitWidth]), and rasterised row by row into the
 * card [renderThreadShareImageRows] draws. The web caps its share image at 768px and forces
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

/** Waits for the off-screen rows to settle (avatars, status dots, Markdown layout) before the snapshot. */
internal val ThreadShareImageDefaultSettle: suspend () -> Unit = {
    repeat(4) { withFrameNanos {} }
    delay(250)
}

/**
 * Composes [rowCount] rows ([row]) off screen as the share image's rows for a card
 * [cardWidthDp] wide, and hands the finished image to [onRendered] (null when it could not be
 * made); [onRendering] says a new one is on its way. It takes no room and draws nothing where it
 * sits; put it in the conversation's composition, so the rows read the same theme, text size
 * and resolvers as the live ones.
 *
 * The rows are laid out at the image's scale (the screen's, 2 to 3 px per dp, lowered to keep
 * the image within [ThreadShareImagePixelBudget]); [renderKey] changing renders again.
 */
@Composable
internal fun ThreadShareImageOffscreenRenderer(
    cardWidthDp: Int,
    rowCount: Int,
    renderKey: Any?,
    background: Color,
    onRendering: () -> Unit,
    onRendered: (ImageBitmap?) -> Unit,
    settle: suspend () -> Unit = ThreadShareImageDefaultSettle,
    row: @Composable (index: Int) -> Unit,
) {
    val screenDensity = LocalDensity.current
    val preferredScale = threadShareImagePreferredScale(screenDensity.density)
    var scale by remember(cardWidthDp, rowCount, renderKey, preferredScale) { mutableStateOf(preferredScale) }
    val renderDensity = remember(scale, screenDensity.fontScale) { Density(scale, screenDensity.fontScale) }
    val layers = remember { mutableStateMapOf<Int, GraphicsLayer>() }
    val rowSizes = remember { mutableStateMapOf<Int, IntSize>() }
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val currentOnRendering by rememberUpdatedState(onRendering)
    val currentOnRendered by rememberUpdatedState(onRendered)
    val currentBackground by rememberUpdatedState(background)
    LaunchedEffect(cardWidthDp, rowCount, renderKey, scale) {
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
        val rowsHeightDp = sizes.sumOf { it!!.height } / scale
        val planned =
            threadShareImageScale(
                cardWidthDp = cardWidthDp.toFloat(),
                cardHeightDp = rowsHeightDp + threadShareImageChromeHeightDp(rowCount),
                preferredScale = preferredScale,
            )
        if (planned < scale * 0.98f) {
            // Too many pixels: lay the rows out again at the lower scale (this effect restarts).
            scale = planned
            return@LaunchedEffect
        }
        // The header's 12px domain is a fixed size, as on the web; the rows keep the user's text size.
        val imageDensity = Density(scale, 1f)
        val measurer = TextMeasurer(fontFamilyResolver, imageDensity, LayoutDirection.Ltr, cacheSize = 0)
        val image =
            try {
                val rowLayers = (0 until rowCount).map { index -> layers[index] ?: error("share image row $index has no layer") }
                // Each row's layer is drawn into the card's software bitmap (a GraphicsLayer re-runs
                // its recording on a software canvas): no row bitmap is held on its own.
                renderThreadShareImageRows(
                    rowSizes = sizes.map { it!! },
                    density = imageDensity,
                    measurer = measurer,
                    background = currentBackground,
                ) { index -> drawLayer(rowLayers[index]) }
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
