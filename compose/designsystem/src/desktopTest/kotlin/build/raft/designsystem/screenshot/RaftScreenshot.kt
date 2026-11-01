package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import com.dropbox.differ.Image
import com.dropbox.differ.ImageComparator
import com.dropbox.differ.Mask
import com.github.takahirom.roborazzi.AwtRoboCanvas
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.JvmImageIoFormat
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.roborazziSystemPropertyTaskType
import io.github.takahirom.roborazzi.processOutputImageAndReportWithDefaults
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import org.jetbrains.skia.EncodedImageFormat

/**
 * Offscreen screenshot harness: renders a composable with skiko ([ImageComposeScene], no
 * window, no GPU) and hands the image to Roborazzi, which compares, records and reports it
 * against the committed golden `<goldenDir>/<name>.png`. Rendering is unchanged from before
 * Roborazzi: Roborazzi only sees the decoded PNG of our own render.
 *
 * Mode comes from Roborazzi's `roborazzi.test.*` system properties (set by its Gradle tasks):
 * - verify (`desktopTest`, `verifyRoborazziDesktop`): fail when the render differs from the
 *   golden beyond [Tolerance]; `<name>_actual.png` and `<name>_compare.png` go to `<reportDir>`.
 * - record (`recordRoborazziDesktop`): write the golden when it is missing or the render
 *   differs beyond [Tolerance]; a golden within tolerance is left untouched (same bytes).
 * - compare (`compareRoborazziDesktop`): write the report images, never fail.
 */
@OptIn(ExperimentalRoborazziApi::class)
object RaftScreenshot {
    /** Logical-pixel density of every capture: 1dp = 2px. */
    const val DENSITY = 2f

    /**
     * A pixel matches when every ARGB channel is within [channelDelta]; a capture passes
     * when at most [maxMismatchRatio] of its pixels do not match. Sized for anti-aliasing
     * jitter, not for layout or colour changes.
     */
    object Tolerance {
        const val channelDelta = 8
        const val maxMismatchRatio = 0.001
    }

    private val goldenDir = File(System.getProperty("raft.screenshot.goldenDir") ?: "screenshots")
    private val reportDir = File(System.getProperty("raft.screenshot.reportDir") ?: "build/reports/screenshots")

    /**
     * No Roborazzi mode set (an IDE run of one test): verify. A plain record becomes
     * compare-and-record so a golden that still matches is not re-encoded.
     */
    private val taskType: RoborazziTaskType =
        when (val requested = roborazziSystemPropertyTaskType()) {
            RoborazziTaskType.None -> RoborazziTaskType.Verify
            RoborazziTaskType.Record -> RoborazziTaskType.CompareAndRecord
            else -> requested
        }

    /**
     * Renders [content] inside [RaftTheme] ([appearance], on the theme canvas with 16dp of
     * padding), sized to the content, and records or verifies `<name>.png`.
     *
     * [scale] is pinned, [RaftScale.Mobile] by default, whatever the capture size: the
     * theme's own choice (a fine pointer and a 768dp window) would flip with the scene's
     * width. Themes nested in [content] inherit it.
     */
    fun capture(
        name: String,
        appearance: RaftAppearance,
        maxWidthDp: Int = 1200,
        maxHeightDp: Int = 2400,
        // Frames rendered before the captured one: a popup lays itself out a frame late.
        settleFrames: Int = 0,
        scale: RaftScale = RaftScale.Mobile,
        content: @Composable () -> Unit,
    ) {
        val framed: @Composable (Modifier) -> Unit = { modifier ->
            RaftTheme(appearance = appearance, systemDark = false, scale = scale) {
                Box(
                    modifier
                        .background(RaftTheme.colors.layerCanvas)
                        .padding(16.dp),
                ) { content() }
            }
        }
        val density = Density(DENSITY)
        // Pass 1: measure the content at its natural size.
        var measured = IntSize.Zero
        ImageComposeScene(
            width = (maxWidthDp * DENSITY).toInt(),
            height = (maxHeightDp * DENSITY).toInt(),
            density = density,
        ) {
            Box(Modifier.wrapContentSize(Alignment.TopStart)) {
                framed(Modifier.onSizeChanged { measured = it })
            }
        }.use { it.render() }
        check(measured.width > 0 && measured.height > 0) { "$name rendered nothing" }
        // Pass 2: render at exactly that size.
        val png =
            ImageComposeScene(width = measured.width, height = measured.height, density = density) {
                framed(Modifier)
            }.use { scene ->
                repeat(settleFrames) { scene.render() }
                val image = scene.render()
                image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encode failed for $name")
            }
        val actual = ImageIO.read(ByteArrayInputStream(png))
        // load() copies the image in with AlphaComposite.Src (a straight pixel copy, alpha
        // included), exactly as Roborazzi loads the golden; the loader hands it our render.
        val canvas =
            AwtRoboCanvas.load(
                file = File(reportDir, "$name.png"),
                bufferedImageType = BufferedImage.TYPE_INT_ARGB,
                imageIoFormat = JvmImageIoFormat(awtImageLoader = { actual }),
            )
        try {
            processOutputImageAndReportWithDefaults(
                canvas = canvas,
                goldenFile = File(goldenDir, "$name.png"),
                roborazziOptions = options(name, png),
                density = density,
            )
        } finally {
            canvas.release()
        }
    }

    private fun options(name: String, png: ByteArray) =
        RoborazziOptions(
            taskType = taskType,
            compareOptions =
                RoborazziOptions.CompareOptions(
                    outputDirectoryPath = reportDir.absolutePath,
                    imageComparator = ToleranceComparator,
                    resultValidator = { result ->
                        result.pixelDifferences.toDouble() / result.pixelCount <= Tolerance.maxMismatchRatio
                    },
                ),
            recordOptions =
                RoborazziOptions.RecordOptions(
                    // Never rescale: goldens are at DENSITY, pixel for pixel.
                    resizeScale = 1.0,
                    imageIoFormat =
                        JvmImageIoFormat(
                            awtImageWriter = { file, _, image ->
                                // The render itself (a recorded golden, or `<name>_actual.png`) is
                                // written as skia encoded it, as before Roborazzi; Roborazzi's own
                                // comparison image goes through ImageIO.
                                file.parentFile?.mkdirs()
                                if (file.name == "$name.png" || file.name == "${name}_actual.png") {
                                    file.writeBytes(png)
                                } else {
                                    ImageIO.write(image, "png", file)
                                }
                            },
                        ),
                ),
        )

    /**
     * Counts the pixels where some ARGB channel differs by more than [Tolerance.channelDelta]:
     * the comparison this harness made before Roborazzi, in place of its colour-distance one.
     */
    private object ToleranceComparator : ImageComparator {
        override fun compare(left: Image, right: Image, diff: Mask?): ImageComparator.ComparisonResult {
            val width = maxOf(left.width, right.width)
            val height = maxOf(left.height, right.height)
            var mismatched = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val same =
                        x < left.width && y < left.height && x < right.width && y < right.height &&
                            left.getPixel(x, y).let { a ->
                                val e = right.getPixel(x, y)
                                within(a.a, e.a) && within(a.r, e.r) && within(a.g, e.g) && within(a.b, e.b)
                            }
                    if (!same) mismatched++
                    diff?.setValue(x, y, if (same) 0f else 1f)
                }
            }
            return ImageComparator.ComparisonResult(
                pixelDifferences = mismatched,
                pixelCount = width * height,
                width = width,
                height = height,
            )
        }

        // differ hands channels over as n / 255f; rounding recovers n exactly.
        private fun within(a: Float, b: Float) =
            abs((a * 255f).roundToInt() - (b * 255f).roundToInt()) <= Tolerance.channelDelta
    }
}
