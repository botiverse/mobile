package build.raft.app.shared

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SlockForegroundBannerPointerBarrierConnectedTest {
    @Test
    fun tappingForegroundBannerDoesNotClickUnderlyingContent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", "compose_foreground_banner_visual")
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", "foreground-banner-pointer-barrier-test")
                        .toString()
                )
                .putExtra(SlockCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            SystemClock.sleep(RENDER_SETTLE_MS)

            tapDp(instrumentation, xDp = 195f, yDp = 55f)
            SystemClock.sleep(TAP_SETTLE_MS)

            val screenshot = captureActivityBitmap(scenario)
            val bannerActionPixels = screenshot.countPixelsNear(BANNER_ACTION_GREEN)
            val leakedUnderlayPixels = screenshot.countPixelsNear(UNDERLAY_LEAK_RED)
            screenshot.recycle()

            assertTrue(
                "Foreground banner did not receive the tap; actionPixels=$bannerActionPixels",
                bannerActionPixels >= MIN_RESULT_PIXELS
            )
            assertTrue(
                "Foreground banner tap reached the content underneath; leakPixels=$leakedUnderlayPixels",
                leakedUnderlayPixels <= MAX_LEAK_PIXELS
            )
        }
    }

    private fun tapDp(
        instrumentation: android.app.Instrumentation,
        xDp: Float,
        yDp: Float
    ) {
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val x = xDp * density
        val y = yDp * density
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        )
        instrumentation.sendPointerSync(
            MotionEvent.obtain(downTime, downTime + 80L, MotionEvent.ACTION_UP, x, y, 0)
        )
        instrumentation.waitForIdleSync()
    }

    private fun captureActivityBitmap(scenario: ActivityScenario<SlockKuiklyActivity>): Bitmap {
        var bitmap: Bitmap? = null
        scenario.onActivity { activity ->
            val root = activity.window.decorView.rootView
            require(root.width > 0 && root.height > 0) {
                "Activity root has invalid size ${root.width}x${root.height}"
            }
            bitmap =
                Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { output ->
                    root.draw(Canvas(output))
                }
        }
        return requireNotNull(bitmap) { "Unable to capture foreground banner pointer result" }
    }

    private fun Bitmap.countPixelsNear(target: Int): Int {
        val row = IntArray(width)
        var count = 0
        for (y in 0 until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            for (pixel in row) {
                if (pixel.isNear(target, tolerance = 12)) count++
            }
        }
        return count
    }

    private fun Int.isNear(target: Int, tolerance: Int): Boolean =
        abs(Color.red(this) - Color.red(target)) <= tolerance &&
            abs(Color.green(this) - Color.green(target)) <= tolerance &&
            abs(Color.blue(this) - Color.blue(target)) <= tolerance

    private companion object {
        const val RENDER_SETTLE_MS = 1_500L
        const val TAP_SETTLE_MS = 350L
        const val MIN_RESULT_PIXELS = 500
        const val MAX_LEAK_PIXELS = 32
        val BANNER_ACTION_GREEN: Int = Color.rgb(169, 216, 119)
        val UNDERLAY_LEAK_RED: Int = Color.rgb(249, 114, 100)
    }
}
