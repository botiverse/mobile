package build.raft.app.shared

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RaftFloatingOverlayTouchGuardConnectedTest {
    @Test
    fun menuContentTapDoesNotClickUnderlyingMarkdownLink() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, RaftCmpActivity::class.java)
                .putExtra("pageName", RaftPages.FLOATING_OVERLAY_TOUCH_GUARD_VISUAL)
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", "floating-overlay-touch-guard-connected-test")
                        .toString()
                )
                .putExtra(RaftCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<RaftCmpActivity>(intent).use { scenario ->
            scenario.onActivity { activity -> activity.hideSystemBarsForTouchGuardTest() }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(RenderSettleMs)

            tapDp(instrumentation, xDp = 172f, yDp = 232f)
            SystemClock.sleep(TapSettleMs)

            val screenshot = captureActivityBitmap(scenario)
            val scan = screenshot.scanTouchGuardStatusStrip()
            screenshot.recycle()

            assertTrue(
                "Expected menu overlay tap to update the green status strip; scan=$scan",
                scan.greenPixels >= MinStatusPixels
            )
            assertTrue(
                "Underlying Markdown link received the overlay tap; scan=$scan",
                scan.redPixels <= MaxLeakPixels
            )
        }
    }

    private fun tapDp(instrumentation: Instrumentation, xDp: Float, yDp: Float) {
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val x = xDp * density
        val y = yDp * density
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(downTime, downTime + 80L, MotionEvent.ACTION_UP, x, y, 0))
        instrumentation.waitForIdleSync()
    }

    private fun captureActivityBitmap(scenario: ActivityScenario<RaftCmpActivity>): Bitmap {
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
        return requireNotNull(bitmap) { "Unable to capture overlay touch guard screenshot" }
    }

    private fun Bitmap.scanTouchGuardStatusStrip(): TouchGuardStatusScan {
        val top = (height * 0.78f).toInt()
        val row = IntArray(width)
        var greenPixels = 0
        var redPixels = 0
        for (y in top until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            for (pixel in row) {
                if (pixel.isNear(PassGreen, tolerance = 12)) greenPixels++
                if (pixel.isNear(LeakRed, tolerance = 12)) redPixels++
            }
        }
        return TouchGuardStatusScan(greenPixels = greenPixels, redPixels = redPixels)
    }

    private fun Int.isNear(target: Int, tolerance: Int): Boolean =
        abs(Color.red(this) - Color.red(target)) <= tolerance &&
            abs(Color.green(this) - Color.green(target)) <= tolerance &&
            abs(Color.blue(this) - Color.blue(target)) <= tolerance

    private fun RaftCmpActivity.hideSystemBarsForTouchGuardTest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private data class TouchGuardStatusScan(
        val greenPixels: Int,
        val redPixels: Int
    )

    private companion object {
        private const val RenderSettleMs = 2_000L
        private const val TapSettleMs = 600L
        private const val MinStatusPixels = 2_000
        private const val MaxLeakPixels = 100
        private val PassGreen = Color.rgb(16, 185, 129)
        private val LeakRed = Color.rgb(239, 68, 68)
    }
}
