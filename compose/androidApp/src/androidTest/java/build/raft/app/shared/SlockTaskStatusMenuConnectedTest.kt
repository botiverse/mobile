package build.raft.app.shared

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SlockTaskStatusMenuConnectedTest {
    @Test
    fun threadTaskStatusChipProducesMessageOwnedAnchor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockCmpActivity::class.java)
                .putExtra("pageName", "compose_thread_message_row_visual")
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", "thread-task-status-anchor-owner")
                        .put("visualFixture", "default")
                        .put("interactionProbe", "task-status-owner")
                        .toString()
                )
                .putExtra(SlockCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockCmpActivity>(intent).use { scenario ->
            instrumentation.dismissCompatibilityDialogIfPresent()
            scenario.onActivity { activity -> activity.hideSystemBarsForTaskStatusMenuTest() }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(RenderSettleMs)

            val root = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
            val target = requireNotNull(root.findAccessibilityNodeInfosByText("#246").firstOrNull()) {
                "Missing real Thread task-status chip"
            }
            val bounds = Rect()
            target.getBoundsInScreen(bounds)
            tapScreenPoint(instrumentation, bounds.exactCenterX(), bounds.exactCenterY())
            SystemClock.sleep(TapSettleMs)

            val capture = captureActivity(scenario)
            val greenPixels = capture.bitmap.countAllPixelsNear(PassGreen)
            capture.bitmap.recycle()
            assertTrue(
                "Real Thread task-status producer did not reach its message-owner consumer; bounds=$bounds greenPixels=$greenPixels",
                greenPixels >= MinThreadProbeGreenPixels
            )
        }
    }

    @Test
    fun lazyExitAndRemountInvalidatesOldAnchorResolverLeases() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockCmpActivity::class.java)
                .putExtra("pageName", SlockKuiklyPages.FLOATING_OVERLAY_TOUCH_GUARD_VISUAL)
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", "anchor-lease-lazy-connected-test")
                        .put("fixture", "anchor-lease-lazy")
                        .toString()
                )
                .putExtra(SlockCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockCmpActivity>(intent).use { scenario ->
            instrumentation.dismissCompatibilityDialogIfPresent()
            scenario.onActivity { activity -> activity.hideSystemBarsForTaskStatusMenuTest() }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(LazyLeaseSettleMs)

            val capture = captureActivity(scenario)
            val greenPixels = capture.bitmap.countPixelsNear(PassGreen)
            val redPixels = capture.bitmap.countPixelsNear(FailRed)
            val orangePixels = capture.bitmap.countPixelsNear(FailOrange)
            val magentaPixels = capture.bitmap.countPixelsNear(FailMagenta)
            val bluePixels = capture.bitmap.countPixelsNear(FailBlue)
            capture.bitmap.recycle()
            assertTrue(
                "Expected real LazyRow A→B→A lifecycle to invalidate both exited resolvers and keep only remounted A; " +
                    "green=$greenPixels redAB=$redPixels orangeBStillLive=$orangePixels " +
                    "magentaSameA=$magentaPixels blueNewANull=$bluePixels",
                greenPixels >= MinLazyLeaseStatusPixels
            )
        }
    }

    @Test
    fun lowAnchoredTaskStatusMenuSelectsDone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockCmpActivity::class.java)
                .putExtra("pageName", SlockKuiklyPages.FLOATING_OVERLAY_TOUCH_GUARD_VISUAL)
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", "task-status-menu-connected-test")
                        .put("fixture", "task-status-menu")
                        .toString()
                )
                .putExtra(SlockCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockCmpActivity>(intent).use { scenario ->
            instrumentation.dismissCompatibilityDialogIfPresent()
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
        instrumentation.runShellInput("input tap ${x.toInt()} ${y.toInt()}")
        instrumentation.waitForIdleSync()
    }

    private fun Instrumentation.runShellInput(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use { input ->
            input.readBytes()
        }
    }

    private fun Instrumentation.dismissCompatibilityDialogIfPresent() {
        repeat(5) {
            val dismiss =
                uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText("Don't Show Again")
                    ?.firstOrNull()
            if (dismiss?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                waitForIdleSync()
                SystemClock.sleep(300L)
                return
            }
            SystemClock.sleep(200L)
        }
    }

    private fun captureActivity(scenario: ActivityScenario<SlockCmpActivity>): RootCapture {
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

    private fun Bitmap.countAllPixelsNear(target: Int): Int {
        val row = IntArray(width)
        var count = 0
        for (y in 0 until height) {
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

    private fun SlockCmpActivity.hideSystemBarsForTaskStatusMenuTest() {
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
        private const val LazyLeaseSettleMs = 2_000L
        private const val MinStatusPixels = 2_000
        private const val MinLazyLeaseStatusPixels = 10_000
        private const val MinThreadProbeGreenPixels = 2_000
        private const val MinMenuBlackPixels = 100
        private const val MinMenuWidthDp = 120f
        private const val MinMenuHeightDp = 150f
        private const val StatusMenuWidthDp = 140f
        private const val StatusMenuBorderDp = 2f
        private const val StatusMenuRowHeightDp = 36f
        private const val DoneRowIndex = 3
        private val PassGreen = Color.rgb(16, 185, 129)
        private val FailRed = Color.rgb(239, 68, 68)
        private val FailOrange = Color.rgb(249, 115, 22)
        private val FailMagenta = Color.rgb(217, 70, 239)
        private val FailBlue = Color.rgb(59, 130, 246)
    }

    private data class RootCapture(
        val bitmap: Bitmap,
        val screenX: Float,
        val screenY: Float
    )
}
