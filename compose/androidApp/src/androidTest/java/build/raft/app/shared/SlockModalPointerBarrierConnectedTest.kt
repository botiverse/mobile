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

class SlockModalPointerBarrierConnectedTest {
    @Test
    fun modalTextFieldTapMovesCursorAwayFromEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockCmpActivity::class.java)
                .putExtra("pageName", SlockKuiklyPages.FLOATING_OVERLAY_TOUCH_GUARD_VISUAL)
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", "modal-text-field-pointer-connected-test")
                        .put("fixture", "modal-text-field")
                        .toString()
                )
                .putExtra(SlockCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockCmpActivity>(intent).use { scenario ->
            scenario.onActivity { activity -> activity.hideSystemBarsForPointerBarrierTest() }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(RenderSettleMs)

            val initialScreenshot = captureActivityBitmap(scenario)
            val statusBounds = requireNotNull(initialScreenshot.findBoundsNear(FixturePink)) {
                "Modal TextField cursor fixture did not render its pink status surface"
            }
            initialScreenshot.recycle()
            val density = instrumentation.targetContext.resources.displayMetrics.density
            tapPx(
                scenario,
                x = statusBounds.left + (60f * density),
                y = statusBounds.top - (33f * density)
            )
            SystemClock.sleep(TapSettleMs)

            val screenshot = captureActivityBitmap(scenario)
            val cursorMovedGreenPixels = screenshot.countPixelsNear(PassGreen)
            screenshot.recycle()

            assertTrue(
                "Modal TextField did not receive the cursor-position tap; greenPixels=$cursorMovedGreenPixels",
                cursorMovedGreenPixels >= MinActionPixels
            )
        }
    }

    @Test
    fun modalTextFieldBlankTapDoesNotReachUnderlay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockCmpActivity::class.java)
                .putExtra("pageName", SlockKuiklyPages.FLOATING_OVERLAY_TOUCH_GUARD_VISUAL)
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", "modal-text-field-blank-pointer-connected-test")
                        .put("fixture", "modal-text-field")
                        .toString()
                )
                .putExtra(SlockCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockCmpActivity>(intent).use { scenario ->
            scenario.onActivity { activity -> activity.hideSystemBarsForPointerBarrierTest() }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(RenderSettleMs)

            val initialScreenshot = captureActivityBitmap(scenario)
            val statusBounds = requireNotNull(initialScreenshot.findBoundsNear(FixturePink)) {
                "Modal TextField blank fixture did not render its pink status surface"
            }
            initialScreenshot.recycle()
            val density = instrumentation.targetContext.resources.displayMetrics.density
            // Right side of the visual input, beyond the rendered text/native editor.
            tapPx(
                scenario,
                x = statusBounds.right - (20f * density),
                y = statusBounds.top - (33f * density)
            )
            SystemClock.sleep(TapSettleMs)

            val screenshot = captureActivityBitmap(scenario)
            val underlayLeakRedPixels = screenshot.countPixelsNear(LeakRedUnderScrim)
            screenshot.recycle()

            assertTrue(
                "Modal TextField blank area reached the underlay; redPixels=$underlayLeakRedPixels",
                underlayLeakRedPixels <= MaxLeakPixels
            )
        }
    }

    @Test
    fun modalContentTapDoesNotClickUnderlyingMarkdownLink() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockCmpActivity::class.java)
                .putExtra("pageName", SlockKuiklyPages.FLOATING_OVERLAY_TOUCH_GUARD_VISUAL)
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", "modal-pointer-barrier-connected-test")
                        .put("fixture", "modal")
                        .toString()
                )
                .putExtra(SlockCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockCmpActivity>(intent).use { scenario ->
            scenario.onActivity { activity -> activity.hideSystemBarsForPointerBarrierTest() }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(RenderSettleMs)

            val initialScreenshot = captureActivityBitmap(scenario)
            val initialPinkPixels = initialScreenshot.countPixelsNear(FixturePink)
            val actionBounds = initialScreenshot.findBoundsNear(FixturePink)
            initialScreenshot.recycle()
            assertTrue(
                "Modal pointer-barrier fixture did not render its pink action; pinkPixels=$initialPinkPixels",
                initialPinkPixels >= MinFixturePixels
            )
            requireNotNull(actionBounds) { "Modal pointer-barrier action bounds were missing" }

            // The action follows the modal header inside the centered card.
            tapPx(scenario, x = actionBounds.centerX, y = actionBounds.centerY)
            SystemClock.sleep(TapSettleMs)

            val screenshot = captureActivityBitmap(scenario)
            val scan = screenshot.scanStatusStrip()
            val actionGreenPixels = screenshot.countPixelsNear(PassGreen)
            screenshot.recycle()

            assertTrue(
                "Expected modal action to update its undimmed green surface; actionGreenPixels=$actionGreenPixels",
                actionGreenPixels >= MinActionPixels
            )
            assertTrue("Underlying Markdown link received the modal tap; scan=$scan", scan.redPixels <= MaxLeakPixels)
        }
    }

    private fun tapPx(
        scenario: ActivityScenario<SlockCmpActivity>,
        x: Float,
        y: Float
    ) {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 80L, MotionEvent.ACTION_UP, x, y, 0)
        try {
            scenario.onActivity { activity ->
                assertTrue("Failed to dispatch pointer down", activity.window.decorView.dispatchTouchEvent(down))
                assertTrue("Failed to dispatch pointer up", activity.window.decorView.dispatchTouchEvent(up))
            }
        } finally {
            down.recycle()
            up.recycle()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun captureActivityBitmap(scenario: ActivityScenario<SlockCmpActivity>): Bitmap {
        var bitmap: Bitmap? = null
        scenario.onActivity { activity ->
            val root = activity.window.decorView.rootView
            require(root.width > 0 && root.height > 0) { "Activity root has invalid size ${root.width}x${root.height}" }
            bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { output -> root.draw(Canvas(output)) }
        }
        return requireNotNull(bitmap) { "Unable to capture modal pointer barrier screenshot" }
    }

    private fun Bitmap.scanStatusStrip(): StatusScan {
        val top = (height * 0.78f).toInt()
        val row = IntArray(width)
        var redPixels = 0
        for (y in top until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            for (pixel in row) {
                if (pixel.isNear(LeakRedUnderScrim, tolerance = 12)) redPixels++
            }
        }
        return StatusScan(redPixels)
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

    private fun Bitmap.findBoundsNear(target: Int): PixelBounds? {
        val row = IntArray(width)
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            for (x in row.indices) {
                if (row[x].isNear(target, tolerance = 12)) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        return if (right >= left && bottom >= top) PixelBounds(left, top, right, bottom) else null
    }

    private fun Int.isNear(target: Int, tolerance: Int): Boolean =
        abs(Color.red(this) - Color.red(target)) <= tolerance &&
            abs(Color.green(this) - Color.green(target)) <= tolerance &&
            abs(Color.blue(this) - Color.blue(target)) <= tolerance

    private fun SlockCmpActivity.hideSystemBarsForPointerBarrierTest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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

    private data class StatusScan(val redPixels: Int)

    private data class PixelBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    private companion object {
        private const val RenderSettleMs = 2_000L
        private const val TapSettleMs = 600L
        private const val MinActionPixels = 10_000
        private const val MaxLeakPixels = 100
        private const val MinFixturePixels = 2_000
        private val PassGreen = Color.rgb(16, 185, 129)
        private val LeakRedUnderScrim = Color.rgb(96, 27, 27)
        private val FixturePink = Color.rgb(254, 125, 168)
    }
}
