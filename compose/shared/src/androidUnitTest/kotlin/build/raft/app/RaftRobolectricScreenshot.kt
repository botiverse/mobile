package build.raft.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ViewRootForTest
import build.raft.designsystem.components.LocalRaftSpinnerStillStep
import build.raft.designsystem.components.applyRaftSystemBarsAppearance
import com.dropbox.differ.Image
import com.dropbox.differ.ImageComparator
import com.dropbox.differ.Mask
import com.github.takahirom.roborazzi.AwtImageLoader
import com.github.takahirom.roborazzi.AwtImageWriter
import com.github.takahirom.roborazzi.AwtRoboCanvas
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.JvmImageIoFormat
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.processOutputImageAndReportWithDefaults
import com.github.takahirom.roborazzi.roborazziSystemPropertyTaskType
import java.awt.image.BufferedImage
import java.io.File
import java.time.Duration
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertNotNull
import org.robolectric.Shadows.shadowOf

/**
 * Robolectric native-graphics screenshots of shared Compose UI, no emulator. The test class
 * carries `@GraphicsMode(NATIVE)` and a `@Config` viewport; [capture] applies [appearance],
 * renders [content] in a fresh activity, draws the compose root into a bitmap and hands it to
 * Roborazzi, which compares, records and reports it against the golden
 * `src/androidUnitTest/screenshots/<name>.png`. The capture is ours, not Roborazzi's: Roborazzi
 * sees the bitmap's pixels and the golden as [BitmapFactory] decodes it, exactly the two images
 * this helper compared before Roborazzi, and the comparison is the same rule ([CHANNEL_DELTA],
 * [MAX_MISMATCH_RATIO]; the design system's RaftScreenshot uses it too).
 *
 * Mode comes from Roborazzi's `roborazzi.test.*` system properties (set by its Gradle tasks):
 * - verify (`:shared:testDebugUnitTest`, `:shared:verifyRoborazziDebug`): fail when the render
 *   differs from the golden beyond the tolerance, or the golden is missing; `<name>_actual.png`
 *   and `<name>_compare.png` (golden, diff, actual) go to `<reportDir>`.
 * - record (`:shared:recordRoborazziDebug`): write the golden when it is missing or the render
 *   differs beyond the tolerance; a golden within tolerance keeps its bytes.
 * - compare (`:shared:compareRoborazziDebug`): write the report images, never fail.
 * Goldens and `<name>_actual.png` are the bitmap as [Bitmap.compress] encodes it, the bytes this
 * helper recorded before Roborazzi.
 *
 * Text rasterises differently from a phone, so these guard layout and colours against
 * regressions; cross-platform parity stays the connected capture's job.
 *
 * CJK goldens (zh-CN / ja qualifiers) are CI's render. Robolectric bundles the fonts, so the
 * layout and the glyph forms match on every machine, but some Noto CJK glyphs rasterise a pixel
 * differently on another host: 0.1–0.25% of a page's pixels, over the tolerance. Record those
 * through CI (`test.yml` with `record_screenshots=true`, artifact `shared-screenshots-recorded`),
 * not locally (settings.storage.zh's golden is CI's render too).
 */
@OptIn(ExperimentalRoborazziApi::class)
internal object RaftRobolectricScreenshot {
    private const val GOLDEN_DIR = "src/androidUnitTest/screenshots"

    /** A pixel matches when every ARGB channel is within this many levels of the golden's. */
    private const val CHANNEL_DELTA = 8

    /** A capture passes when at most this share of its pixels do not match. */
    private const val MAX_MISMATCH_RATIO = 0.001

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

    fun capture(
        name: String,
        appearance: RaftAppAppearance,
        reportDir: String,
        activities: RobolectricComposeActivityRule,
        // The view drawn: the activity's compose root by default; a dialog test passes the
        // dialog window's decor view (a RaftDialog renders in its own window).
        root: (ComponentActivity) -> View? = { it.window.decorView },
        // Runs on the settled compose root before the capture (a pointer hovering it), then settles again.
        interact: ((View) -> Unit)? = null,
        content: @Composable (ComponentActivity) -> Unit,
    ) {
        val previousAppearance = RaftAppThemeState.appearance
        try {
            RaftAppThemeState.apply(appearance)
            val activity = activities.launch()
            // The host's bar icons as the app sets them for this mode: a dialog window copies them
            // (#3847), so leaving them to whatever ran before made dialog captures differ by a row.
            activity.window.applyRaftSystemBarsAppearance(RaftAppThemeState.isDark)
            // Moko string resources read the app context through the shared SQLDelight holder.
            build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
            // Spinners hold still on their first step: a capture is one deterministic frame.
            activity.setContent { CompositionLocalProvider(LocalRaftSpinnerStillStep provides 0) { content(activity) } }
            settle()
            val composeView = root(activity)?.let(::findComposeRoot)
            assertNotNull(composeView, "no compose root")
            if (interact != null) {
                interact(composeView)
                settle()
            }
            val bitmap = Bitmap.createBitmap(composeView.width, composeView.height, Bitmap.Config.ARGB_8888)
            composeView.draw(android.graphics.Canvas(bitmap))
            verifyOrRecord(name, bitmap, File(reportDir))
        } finally {
            RaftAppThemeState.apply(previousAppearance)
        }
    }

    /**
     * Compares [actual] with the golden `<name>.png` and verifies or records it (see the class
     * doc); report images go to [reportDir]. For tests that draw their own bitmap.
     */
    fun verifyOrRecord(name: String, actual: Bitmap, reportDir: File) {
        val golden = File(GOLDEN_DIR, "$name.png")
        // Src: a straight copy of the pixels, alpha included, as Roborazzi loads the golden.
        val canvas =
            AwtRoboCanvas(
                width = actual.width,
                height = actual.height,
                filled = true,
                bufferedImageType = BufferedImage.TYPE_INT_ARGB,
            ).apply { drawImage(actual.toArgbImage(), AwtRoboCanvas.CompositeMode.Src) }
        try {
            processOutputImageAndReportWithDefaults(
                canvas = canvas,
                goldenFile = golden,
                roborazziOptions = options(golden, actual, reportDir),
            )
        } finally {
            canvas.release()
        }
    }

    private fun options(golden: File, actual: Bitmap, reportDir: File) =
        RoborazziOptions(
            taskType = taskType,
            compareOptions =
                RoborazziOptions.CompareOptions(
                    outputDirectoryPath = reportDir.absolutePath,
                    imageComparator = ToleranceComparator,
                    resultValidator = { result ->
                        result.pixelDifferences.toDouble() / result.pixelCount <= MAX_MISMATCH_RATIO
                    },
                ),
            recordOptions =
                RoborazziOptions.RecordOptions(
                    // Never rescale: goldens are the bitmap, pixel for pixel.
                    resizeScale = 1.0,
                    imageIoFormat =
                        JvmImageIoFormat(
                            awtImageWriter =
                                AwtImageWriter { file, _, image ->
                                    // A recorded golden or `<name>_actual.png` is the bitmap as
                                    // Bitmap.compress encodes it, as before Roborazzi; Roborazzi's
                                    // comparison image goes through ImageIO.
                                    file.parentFile?.mkdirs()
                                    if (file.name == golden.name || file.name == "${golden.nameWithoutExtension}_actual.png") {
                                        file.outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                    } else {
                                        ImageIO.write(image, "png", file)
                                    }
                                },
                            // Only the golden is loaded: decoded by BitmapFactory and read back
                            // through getPixels, the values the comparison saw before Roborazzi.
                            awtImageLoader =
                                AwtImageLoader { file ->
                                    val decoded = BitmapFactory.decodeFile(file.path) ?: error("unreadable golden ${file.path}")
                                    decoded.toArgbImage()
                                },
                        ),
                ),
        )

    /** The bitmap's pixels as `Bitmap.getPixel` reads them (unpremultiplied ARGB), unconverted. */
    private fun Bitmap.toArgbImage(): BufferedImage {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, width, height, pixels, 0, width)
        }
    }

    private fun settle() {
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200)) }
    }

    private fun findComposeRoot(view: View): View? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findComposeRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    /**
     * Counts the pixels where some ARGB channel differs by more than [CHANNEL_DELTA]: the
     * comparison this helper made before Roborazzi, in place of Roborazzi's colour distance.
     * Roborazzi only calls it for images of the same size (a size change fails on its own).
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
        private fun within(a: Float, b: Float) = abs((a * 255f).roundToInt() - (b * 255f).roundToInt()) <= CHANNEL_DELTA
    }
}
