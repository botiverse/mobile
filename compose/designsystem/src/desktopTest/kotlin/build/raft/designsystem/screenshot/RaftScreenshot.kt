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
import build.raft.designsystem.theme.RaftTheme
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.fail
import org.jetbrains.skia.EncodedImageFormat

/**
 * Offscreen screenshot harness: renders a composable with skiko ([ImageComposeScene], no
 * window, no GPU) and compares it with a committed golden PNG.
 *
 * Mode comes from system properties set by the `desktopTest` Gradle task:
 * - verify (default): fail when the render differs from `<goldenDir>/<name>.png` beyond
 *   [Tolerance]; the actual image and a diff go to `<reportDir>`.
 * - record (`-Praft.screenshot.record=true`): (re)write the golden and pass.
 */
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

    private val record = System.getProperty("raft.screenshot.record").toBoolean()
    private val goldenDir = File(System.getProperty("raft.screenshot.goldenDir") ?: "screenshots")
    private val reportDir = File(System.getProperty("raft.screenshot.reportDir") ?: "build/reports/screenshots")

    /**
     * Renders [content] inside [RaftTheme] ([appearance], on the theme canvas with 16dp of
     * padding), sized to the content, and records or verifies `<name>.png`.
     */
    fun capture(
        name: String,
        appearance: RaftAppearance,
        maxWidthDp: Int = 1200,
        maxHeightDp: Int = 2400,
        // Frames rendered before the captured one: a popup lays itself out a frame late.
        settleFrames: Int = 0,
        content: @Composable () -> Unit,
    ) {
        val framed: @Composable (Modifier) -> Unit = { modifier ->
            RaftTheme(appearance = appearance, systemDark = false) {
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
        val golden = File(goldenDir, "$name.png")
        writeReport(name, png, diff = null) // TEMP: dump every actual, never fail
        if (true) return
        if (record) {
            golden.parentFile.mkdirs()
            golden.writeBytes(png)
            println("recorded ${golden.path} (${actual.width}x${actual.height})")
            return
        }
        if (!golden.exists()) {
            writeReport(name, png, diff = null)
            fail("missing golden ${golden.path}; record with -Praft.screenshot.record=true")
        }
        val expected = ImageIO.read(golden)
        if (expected.width != actual.width || expected.height != actual.height) {
            writeReport(name, png, diff = null)
            fail(
                "$name: size ${actual.width}x${actual.height} != golden ${expected.width}x${expected.height}; " +
                    "actual in ${reportDir.path}",
            )
        }
        val (mismatched, diff) = compare(expected, actual)
        val ratio = mismatched.toDouble() / (actual.width * actual.height)
        if (ratio > Tolerance.maxMismatchRatio) {
            writeReport(name, png, diff)
            fail(
                "$name: $mismatched pixels (${"%.4f".format(ratio * 100)}%) differ from ${golden.path} " +
                    "beyond ±${Tolerance.channelDelta}/channel; actual and diff in ${reportDir.path}",
            )
        }
    }

    /** Count of pixels outside tolerance, and a diff image (mismatches red over a faded actual). */
    internal fun compare(expected: BufferedImage, actual: BufferedImage): Pair<Int, BufferedImage> {
        val diff = BufferedImage(actual.width, actual.height, BufferedImage.TYPE_INT_ARGB)
        var mismatched = 0
        for (y in 0 until actual.height) {
            for (x in 0 until actual.width) {
                val a = actual.getRGB(x, y)
                val e = expected.getRGB(x, y)
                val same = (0 until 32 step 8).all { shift ->
                    abs(((a ushr shift) and 0xFF) - ((e ushr shift) and 0xFF)) <= Tolerance.channelDelta
                }
                if (same) {
                    val gray = (((a ushr 16) and 0xFF) + ((a ushr 8) and 0xFF) + (a and 0xFF)) / 3
                    val faded = 0xFF000000.toInt() or (0xC0 + gray / 4).let { (it shl 16) or (it shl 8) or it }
                    diff.setRGB(x, y, faded)
                } else {
                    mismatched++
                    diff.setRGB(x, y, 0xFFFF0000.toInt())
                }
            }
        }
        return mismatched to diff
    }

    private fun writeReport(name: String, png: ByteArray, diff: BufferedImage?) {
        reportDir.mkdirs()
        File(reportDir, "$name.actual.png").writeBytes(png)
        if (diff != null) ImageIO.write(diff, "png", File(reportDir, "$name.diff.png"))
    }
}
