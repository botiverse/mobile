package ai.slock.android.shared

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SlockMarkdownRerenderConnectedTest {
    @Test
    fun inlineMarkdownChipsRenderAfterRepeatedUpdatesInSameSlot() {
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
                .putExtra(SlockKuiklyActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            Thread.sleep(1_200)
            instrumentation.waitForIdleSync()

            val screenshot = captureActivityBitmap(scenario)
            val pinkPixels = screenshot.countPixelsNear(ChannelChipPink)
            val yellowPixels = screenshot.countPixelsNear(HighlightedMentionYellow)
            val pinkRows = screenshot.countVerticalColorClusters(ChannelChipPink)
            val yellowRows = screenshot.countVerticalColorClusters(HighlightedMentionYellow)
            screenshot.recycle()

            assertTrue(
                "Expected repeated #markdown专修 channel chips in the LazyColumn; pinkPixels=$pinkPixels pinkRows=$pinkRows",
                pinkRows >= 8
            )
            assertTrue(
                "Expected repeated highlighted @artin mention chips in the LazyColumn; yellowPixels=$yellowPixels yellowRows=$yellowRows",
                yellowRows >= 8
            )
        }
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

    private fun Int.isNear(target: Int, tolerance: Int): Boolean =
        abs(Color.red(this) - Color.red(target)) <= tolerance &&
            abs(Color.green(this) - Color.green(target)) <= tolerance &&
            abs(Color.blue(this) - Color.blue(target)) <= tolerance

    private companion object {
        private val ChannelChipPink = Color.rgb(254, 216, 229)
        private val HighlightedMentionYellow = Color.rgb(255, 212, 64)
    }
}
