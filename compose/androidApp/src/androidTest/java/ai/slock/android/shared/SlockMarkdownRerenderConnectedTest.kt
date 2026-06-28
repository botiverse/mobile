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
            Thread.sleep(1_800)
            instrumentation.waitForIdleSync()

            val screenshot = captureActivityBitmap(scenario)
            val pinkPixels = screenshot.countPixelsNear(ChannelChipPink)
            val cyanPixels = screenshot.countPixelsNear(ThreadChipCyan)
            val cyanFamilyPixels = screenshot.countCyanFamilyPixels()
            val yellowPixels = screenshot.countPixelsNear(HighlightedMentionYellow)
            screenshot.recycle()

            assertTrue(
                "Expected final #markdown专修 channel chip after repeated markdown updates; pinkPixels=$pinkPixels",
                pinkPixels > 80
            )
            assertTrue(
                "Expected final #markdown专修:9579b48a thread chip after repeated markdown updates; " +
                    "cyanPixels=$cyanPixels cyanFamilyPixels=$cyanFamilyPixels",
                cyanFamilyPixels > 80
            )
            assertTrue(
                "Expected final highlighted @artin mention chip after repeated markdown updates; yellowPixels=$yellowPixels",
                yellowPixels > 80
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

    private fun Bitmap.countCyanFamilyPixels(): Int {
        val row = IntArray(width)
        var count = 0
        for (y in 0 until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            for (pixel in row) {
                if (Color.red(pixel) in 145..225 &&
                    Color.green(pixel) in 210..255 &&
                    Color.blue(pixel) in 225..255
                ) {
                    count++
                }
            }
        }
        return count
    }

    private fun Int.isNear(target: Int, tolerance: Int): Boolean =
        abs(Color.red(this) - Color.red(target)) <= tolerance &&
            abs(Color.green(this) - Color.green(target)) <= tolerance &&
            abs(Color.blue(this) - Color.blue(target)) <= tolerance

    private companion object {
        private val ChannelChipPink = Color.rgb(254, 216, 229)
        private val ThreadChipCyan = Color.rgb(190, 239, 251)
        private val HighlightedMentionYellow = Color.rgb(255, 212, 64)
    }
}
