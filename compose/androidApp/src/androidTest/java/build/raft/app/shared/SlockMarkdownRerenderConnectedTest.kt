package build.raft.app.shared

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

class SlockMarkdownRerenderConnectedTest {
    @Test
    fun inlineMarkdownChipsRenderAcrossLazyColumnRows() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", SlockKuiklyPages.MARKDOWN_RERENDER_VISUAL)
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", "markdown-rerender-connected-test")
                        .toString()
                )
                .putExtra(SlockCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            val result =
                waitForMarkdownChipRows(
                    scenario = scenario,
                    instrumentation = instrumentation,
                    outputDir = context.getExternalFilesDir("markdown-rerender"),
                    minRows = MinVisibleChipRows
                )

            assertTrue(
                "Expected repeated #markdown专修 channel chips in the LazyColumn; bestScan=$result",
                result.pinkRows >= MinVisibleChipRows
            )
            assertTrue(
                "Expected repeated highlighted @artin mention chips in the LazyColumn; bestScan=$result",
                result.yellowRows >= MinVisibleChipRows
            )
        }
    }

    private fun waitForMarkdownChipRows(
        scenario: ActivityScenario<SlockKuiklyActivity>,
        instrumentation: Instrumentation,
        outputDir: File?,
        minRows: Int
    ): MarkdownChipScanResult {
        val deadline = SystemClock.uptimeMillis() + RenderTimeoutMs
        var best = MarkdownChipScanResult()
        do {
            instrumentation.waitForIdleSync()
            val screenshot = captureActivityBitmap(scenario)
            val current = screenshot.scanMarkdownChipRows()
            if (current.pinkRows >= minRows && current.yellowRows >= minRows) {
                screenshot.saveDebugCopy(outputDir, "markdown-rerender-pass.png")
                screenshot.recycle()
                return current
            }
            if (current.score >= best.score) {
                best = current
                screenshot.saveDebugCopy(outputDir, "markdown-rerender-best.png")
            }
            screenshot.recycle()
            SystemClock.sleep(PollIntervalMs)
        } while (SystemClock.uptimeMillis() < deadline)
        return best
    }

    private fun captureActivityBitmap(scenario: ActivityScenario<SlockKuiklyActivity>): Bitmap {
        var bitmap: Bitmap? = null
        scenario.onActivity { activity ->
            val root = activity.window.decorView.rootView
            require(root.width > 0 && root.height > 0) {
                "Activity root has invalid size ${root.width}x${root.height}"
            }
            bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { output ->
                root.draw(Canvas(output))
            }
        }
        return requireNotNull(bitmap) { "Unable to capture markdown rerender screenshot" }
    }

    private fun Bitmap.scanMarkdownChipRows(): MarkdownChipScanResult =
        MarkdownChipScanResult(
            pinkPixels = countPixelsNear(ChannelChipPink),
            yellowPixels = countPixelsNear(HighlightedMentionYellow),
            pinkRows = countVerticalColorClusters(ChannelChipPink),
            yellowRows = countVerticalColorClusters(HighlightedMentionYellow)
        )

    private fun Bitmap.countVerticalColorClusters(
        target: Int,
        tolerance: Int = 18,
        rowThreshold: Int = 20,
        maxGap: Int = 6
    ): Int {
        val row = IntArray(width)
        var clusters = 0
        var inCluster = false
        var gap = 0
        for (y in 0 until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            var rowHits = 0
            for (pixel in row) {
                if (pixel.isNear(target, tolerance)) rowHits++
            }
            if (rowHits >= rowThreshold) {
                if (!inCluster) clusters++
                inCluster = true
                gap = 0
            } else if (inCluster) {
                gap++
                if (gap > maxGap) {
                    inCluster = false
                    gap = 0
                }
            }
        }
        return clusters
    }

    private fun Bitmap.countPixelsNear(target: Int, tolerance: Int = 18): Int {
        val row = IntArray(width)
        var count = 0
        for (y in 0 until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            for (pixel in row) {
                if (pixel.isNear(target, tolerance)) count++
            }
        }
        return count
    }

    private fun Int.isNear(target: Int, tolerance: Int): Boolean =
        abs(Color.red(this) - Color.red(target)) <= tolerance &&
            abs(Color.green(this) - Color.green(target)) <= tolerance &&
            abs(Color.blue(this) - Color.blue(target)) <= tolerance

    private fun Bitmap.saveDebugCopy(outputDir: File?, name: String) {
        val directory = outputDir ?: return
        directory.mkdirs()
        FileOutputStream(File(directory, name)).use { stream ->
            compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }

    private data class MarkdownChipScanResult(
        val pinkPixels: Int = 0,
        val yellowPixels: Int = 0,
        val pinkRows: Int = 0,
        val yellowRows: Int = 0
    ) {
        val score: Int
            get() = pinkRows + yellowRows
    }

    private companion object {
        private const val MinVisibleChipRows = 8
        private const val RenderTimeoutMs = 25_000L
        private const val PollIntervalMs = 120L
        private val ChannelChipPink = Color.rgb(254, 216, 229)
        private val HighlightedMentionYellow = Color.rgb(255, 212, 64)
    }
}
