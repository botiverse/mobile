package build.raft.app.shared

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
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

class SlockTaskStatusMenuConnectedTest {
    @Test
    fun lowAnchoredTaskStatusMenuSelectsDone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", SlockKuiklyPages.FLOATING_OVERLAY_TOUCH_GUARD_VISUAL)
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", "task-status-menu-connected-test")
                        .put("fixture", "task-status-menu")
                        .toString()
                )
                .putExtra(SlockKuiklyActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            scenario.onActivity { activity -> activity.hideSystemBarsForTaskStatusMenuTest() }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(RenderSettleMs)

            val density = instrumentation.targetContext.resources.displayMetrics.density
            val initialCapture = captureActivity(scenario)
            val menuBounds = initialCapture.bitmap.findLowerRightMenuBounds(density)
            assertTrue(
                "Task status menu did not render in the lower-right viewport; " +
                    "root=${initialCapture.bitmap.width}x${initialCapture.bitmap.height} " +
                    "origin=(${initialCapture.screenX},${initialCapture.screenY}) density=$density",
                menuBounds != null
            )
            val resolvedMenuBounds = requireNotNull(menuBounds)
            val tap = resolvedMenuBounds.doneRowCenter(density)
            println(
                "TASK_STATUS_MENU_GEOMETRY " +
                    "root=${initialCapture.bitmap.width}x${initialCapture.bitmap.height} " +
                    "origin=(${initialCapture.screenX},${initialCapture.screenY}) " +
                    "menu=$resolvedMenuBounds density=$density tap=$tap"
            )
            initialCapture.bitmap.recycle()

            tapScreenPoint(
                instrumentation = instrumentation,
                x = initialCapture.screenX + tap.first,
                y = initialCapture.screenY + tap.second
            )
            SystemClock.sleep(TapSettleMs)

            val resultCapture = captureActivity(scenario)
            val greenPixels = resultCapture.bitmap.countPixelsNear(PassGreen)
            resultCapture.bitmap.recycle()
            assertTrue(
                "Expected the low-anchored status menu to select Done; " +
                    "greenPixels=$greenPixels menu=$resolvedMenuBounds density=$density tap=$tap",
                greenPixels >= MinStatusPixels
            )
        }
    }

    private fun tapScreenPoint(instrumentation: Instrumentation, x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(downTime, downTime + 80L, MotionEvent.ACTION_UP, x, y, 0))
        instrumentation.waitForIdleSync()
    }

    private fun captureActivity(scenario: ActivityScenario<SlockKuiklyActivity>): RootCapture {
        var capture: RootCapture? = null
        scenario.onActivity { activity ->
            val root = activity.window.decorView.rootView
            require(root.width > 0 && root.height > 0) {
                "Activity root has invalid size ${root.width}x${root.height}"
            }
            val screenLocation = IntArray(2)
            root.getLocationOnScreen(screenLocation)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { output ->
                root.draw(Canvas(output))
            }
            capture =
                RootCapture(
                    bitmap = bitmap,
                    screenX = screenLocation[0].toFloat(),
                    screenY = screenLocation[1].toFloat()
                )
        }
        return requireNotNull(capture) { "Unable to capture task status menu screenshot" }
    }

    private fun Bitmap.findLowerRightMenuBounds(density: Float): Rect? {
        val searchLeft = width / 2
        val searchTop = height / 2
        val row = IntArray(width)
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        var blackPixels = 0
        for (y in searchTop until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            for (x in searchLeft until width) {
                if (!row[x].isNearBlack()) continue
                blackPixels++
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
            }
        }
        val bounds = Rect(minX, minY, maxX + 1, maxY + 1)
        return if (
            blackPixels >= MinMenuBlackPixels &&
            maxX >= minX &&
            maxY >= minY &&
            bounds.width() >= MinMenuWidthDp * density &&
            bounds.height() >= MinMenuHeightDp * density
        ) {
            bounds
        } else {
            null
        }
    }

    private fun Rect.doneRowCenter(density: Float): Pair<Float, Float> {
        val surfaceWidth = StatusMenuWidthDp * density
        val border = StatusMenuBorderDp * density
        val rowHeight = StatusMenuRowHeightDp * density
        return Pair(
            left + surfaceWidth / 2f,
            top + border + (DoneRowIndex + 0.5f) * rowHeight
        )
    }

    private fun Bitmap.countPixelsNear(target: Int): Int {
        val row = IntArray(width)
        var count = 0
        for (y in (height * 0.78f).toInt() until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            count += row.count { pixel -> pixel.isNear(target, tolerance = 12) }
        }
        return count
    }

    private fun Int.isNear(target: Int, tolerance: Int): Boolean =
        abs(Color.red(this) - Color.red(target)) <= tolerance &&
            abs(Color.green(this) - Color.green(target)) <= tolerance &&
            abs(Color.blue(this) - Color.blue(target)) <= tolerance

    private fun Int.isNearBlack(): Boolean =
        Color.alpha(this) >= 180 &&
            Color.red(this) <= 24 &&
            Color.green(this) <= 24 &&
            Color.blue(this) <= 24

    private fun SlockKuiklyActivity.hideSystemBarsForTaskStatusMenuTest() {
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

    private companion object {
        private const val RenderSettleMs = 2_000L
        private const val TapSettleMs = 600L
        private const val MinStatusPixels = 2_000
        private const val MinMenuBlackPixels = 100
        private const val MinMenuWidthDp = 120f
        private const val MinMenuHeightDp = 150f
        private const val StatusMenuWidthDp = 140f
        private const val StatusMenuBorderDp = 2f
        private const val StatusMenuRowHeightDp = 36f
        private const val DoneRowIndex = 3
        private val PassGreen = Color.rgb(16, 185, 129)
    }

    private data class RootCapture(
        val bitmap: Bitmap,
        val screenX: Float,
        val screenY: Float
    )
}
