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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RaftTaskListStatusMenuConnectedTest {
    @Test
    fun scrolledInProgressCardOpensItsOwnAnchoredMenu() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val density = context.resources.displayMetrics.density
        val intent =
            Intent(context, RaftCmpActivity::class.java)
                .putExtra("pageName", TasksPanelVisualPage)
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", "task-status-list-recycle-connected-test")
                        .put("fixture", TasksPanelStatusMenuRecycleFixture)
                        .toString()
                )
                .putExtra(RaftCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<RaftCmpActivity>(intent).use { scenario ->
            instrumentation.dismissCompatibilityDialogIfPresent()
            scenario.onActivity { activity -> activity.hideSystemBarsForTaskListStatusTest() }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(RenderSettleMs)

            var capture = captureActivity(scenario)
            var chip = capture.bitmap.findLowestCyanStatusChip(density)
            for (attempt in 0 until MaxScrollAttempts) {
                if (chip != null && chip!!.centerY() > capture.bitmap.height * MinTargetViewportFraction) {
                    break
                }
                swipeUp(instrumentation, capture)
                capture.bitmap.recycle()
                SystemClock.sleep(ScrollSettleMs)
                capture = captureActivity(scenario)
                chip = capture.bitmap.findLowestCyanStatusChip(density)
            }

            val targetChip =
                requireNotNull(chip) {
                    "Expected an In Progress task chip after scrolling the real Tasks LazyColumn"
                }
            assertTrue(
                "Target chip must be low enough to exercise Auto placement; chip=$targetChip root=${capture.bitmap.width}x${capture.bitmap.height}",
                targetChip.centerY() > capture.bitmap.height * MinTargetViewportFraction
            )
            println(
                "TASK_STATUS_REAL_LIST_TARGET root=${capture.bitmap.width}x${capture.bitmap.height} " +
                    "origin=(${capture.screenX},${capture.screenY}) density=$density chip=$targetChip"
            )
            tapScreenPoint(
                instrumentation = instrumentation,
                x = capture.screenX + targetChip.centerX(),
                y = capture.screenY + targetChip.centerY()
            )
            capture.bitmap.recycle()
            SystemClock.sleep(MenuSettleMs)

            val menuCapture = captureActivity(scenario)
            val menu = menuCapture.bitmap.findStatusMenuBounds(density)
            assertNotNull(
                "Expected the real Tasks status menu after tapping chip=$targetChip",
                menu
            )
            val resolvedMenu = requireNotNull(menu)
            val selectedRow = menuCapture.bitmap.findSelectedStatusRow(resolvedMenu, density)
            val anchorGapPx = targetChip.bottom - resolvedMenu.bottom
            println(
                "TASK_STATUS_REAL_LIST_MENU menu=$resolvedMenu selectedRow=$selectedRow " +
                    "anchorGapPx=$anchorGapPx chip=$targetChip density=$density"
            )
            menuCapture.bitmap.recycle()

            assertEquals(
                "The visible In Progress card must open with In Progress selected",
                InProgressRowIndex,
                selectedRow
            )
            assertTrue(
                "Menu must remain anchored to the tapped card; gapPx=$anchorGapPx menu=$resolvedMenu chip=$targetChip density=$density",
                abs(anchorGapPx) <= MaxAnchorGapDp * density
            )
        }
    }

    private fun swipeUp(instrumentation: Instrumentation, capture: RootCapture) {
        val x = capture.screenX + capture.bitmap.width * 0.5f
        val startY = capture.screenY + capture.bitmap.height * 0.78f
        val endY = capture.screenY + capture.bitmap.height * 0.28f
        instrumentation.runShellInput(
            "input swipe ${x.toInt()} ${startY.toInt()} ${x.toInt()} ${endY.toInt()} $SwipeDurationMs"
        )
        instrumentation.waitForIdleSync()
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

    private fun captureActivity(scenario: ActivityScenario<RaftCmpActivity>): RootCapture {
        var capture: RootCapture? = null
        scenario.onActivity { activity ->
            val root = activity.window.decorView.rootView
            require(root.width > 0 && root.height > 0) {
                "Activity root has invalid size ${root.width}x${root.height}"
            }
            val location = IntArray(2)
            root.getLocationOnScreen(location)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { output ->
                root.draw(Canvas(output))
            }
            capture = RootCapture(bitmap, location[0].toFloat(), location[1].toFloat())
        }
        return requireNotNull(capture)
    }

    private fun Bitmap.findLowestCyanStatusChip(density: Float): Rect? {
        val minimumRowPixels = (MinCyanRowWidthDp * density).toInt()
        val bands = mutableListOf<IntRange>()
        var bandStart: Int? = null
        val row = IntArray(width)
        for (y in (height * ToolbarExclusionFraction).toInt() until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            val cyanPixels = row.count { it.isNear(InProgressCyan, ColorTolerance) }
            if (cyanPixels >= minimumRowPixels) {
                if (bandStart == null) bandStart = y
            } else if (bandStart != null) {
                bands += bandStart!!..(y - 1)
                bandStart = null
            }
        }
        bandStart?.let { bands += it until height }

        val band =
            bands
                .filter { it.last - it.first + 1 >= MinCyanBandHeightDp * density }
                .filter { it.last < height - BottomViewportGuardDp * density }
                .maxByOrNull { it.last }
                ?: return null
        var minX = width
        var maxX = -1
        for (y in band) {
            getPixels(row, 0, width, 0, y, width, 1)
            for (x in width / 2 until width) {
                if (row[x].isNear(InProgressCyan, ColorTolerance)) {
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                }
            }
        }
        return if (maxX >= minX) Rect(minX, band.first, maxX + 1, band.last + 1) else null
    }

    private fun Bitmap.findStatusMenuBounds(density: Float): Rect? {
        val candidates = mutableListOf<VerticalBorder>()
        val minimumHeight = (MinMenuBorderHeightDp * density).toInt()
        for (x in width / 2 until width) {
            var start = -1
            var bestStart = -1
            var bestEnd = -1
            for (y in (height * MenuSearchTopFraction).toInt() until height) {
                val black = getPixel(x, y).isNearBlack()
                if (black && start < 0) start = y
                if ((!black || y == height - 1) && start >= 0) {
                    val end = if (black && y == height - 1) y else y - 1
                    if (end - start > bestEnd - bestStart) {
                        bestStart = start
                        bestEnd = end
                    }
                    start = -1
                }
            }
            if (bestStart >= 0 && bestEnd - bestStart + 1 >= minimumHeight) {
                candidates += VerticalBorder(x, bestStart, bestEnd)
            }
        }
        val expectedWidth = StatusMenuWidthDp * density
        val tolerance = MenuPairToleranceDp * density
        for (left in candidates) {
            val right =
                candidates.firstOrNull { candidate ->
                    candidate.x > left.x &&
                        abs((candidate.x - left.x) - expectedWidth) <= tolerance &&
                        abs(candidate.top - left.top) <= tolerance &&
                        abs(candidate.bottom - left.bottom) <= tolerance
                }
            if (right != null) {
                return Rect(left.x, minOf(left.top, right.top), right.x + 1, maxOf(left.bottom, right.bottom) + 1)
            }
        }
        return null
    }

    private fun Bitmap.findSelectedStatusRow(menu: Rect, density: Float): Int? {
        val border = StatusMenuBorderDp * density
        val rowHeight = StatusMenuRowHeightDp * density
        val scanLeft = (menu.right - SelectedIndicatorRightInsetDp * density).toInt()
        val scanRight = (menu.right - SelectedIndicatorLeftInsetDp * density).toInt()
        return (0 until StatusRowCount)
            .map { rowIndex ->
                val top = (menu.top + border + rowIndex * rowHeight).toInt()
                val bottom = (menu.top + border + (rowIndex + 1) * rowHeight).toInt()
                var blackPixels = 0
                for (y in top until bottom.coerceAtMost(height)) {
                    for (x in scanLeft.coerceAtLeast(0) until scanRight.coerceAtMost(width)) {
                        if (getPixel(x, y).isNearBlack()) blackPixels++
                    }
                }
                rowIndex to blackPixels
            }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= MinSelectedIndicatorPixels }
            ?.first
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

    private fun RaftCmpActivity.hideSystemBarsForTaskListStatusTest() {
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

    private data class RootCapture(
        val bitmap: Bitmap,
        val screenX: Float,
        val screenY: Float
    )

    private data class VerticalBorder(
        val x: Int,
        val top: Int,
        val bottom: Int
    )

    private companion object {
        private const val TasksPanelVisualPage = "compose_tasks_panel_visual"
        private const val TasksPanelStatusMenuRecycleFixture = "status-menu-recycle"
        private const val RenderSettleMs = 2_000L
        private const val ScrollSettleMs = 350L
        private const val MenuSettleMs = 600L
        private const val SwipeDurationMs = 360L
        private const val MaxScrollAttempts = 8
        private const val StatusRowCount = 5
        private const val InProgressRowIndex = 1
        private const val ColorTolerance = 12
        private const val MinSelectedIndicatorPixels = 12
        private const val MinCyanRowWidthDp = 40f
        private const val MinCyanBandHeightDp = 8f
        private const val BottomViewportGuardDp = 20f
        private const val MinMenuBorderHeightDp = 150f
        private const val StatusMenuWidthDp = 140f
        private const val MenuPairToleranceDp = 8f
        private const val StatusMenuBorderDp = 2f
        private const val StatusMenuRowHeightDp = 36f
        private const val SelectedIndicatorRightInsetDp = 34f
        private const val SelectedIndicatorLeftInsetDp = 6f
        private const val MaxAnchorGapDp = 20f
        private const val ToolbarExclusionFraction = 0.20f
        private const val MenuSearchTopFraction = 0.12f
        private const val MinTargetViewportFraction = 0.52f
        private val InProgressCyan = Color.rgb(39, 204, 243)
    }
}
