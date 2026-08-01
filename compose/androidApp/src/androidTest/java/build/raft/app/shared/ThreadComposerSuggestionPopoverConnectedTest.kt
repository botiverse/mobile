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
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ThreadComposerSuggestionPopoverConnectedTest {
    @Test
    fun openSuggestionCandidateMovesAndRemainsHittableAfterAncestorRelayout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", SlockKuiklyPages.THREAD_COMPOSER_VISUAL)
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", "composer-suggestion-reposition-oracle")
                        .put("visualFixture", RepositionOracleFixture)
                        .toString()
                )
                .putExtra(SlockKuiklyActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            scenario.onActivity { activity -> activity.hideSystemBarsForSuggestionTest() }
            instrumentation.waitForIdleSync()

            val initialCandidate =
                requireNotNull(device.wait(Until.findObject(By.text(CandidateLabel)), CandidateWaitMs)) {
                    "Initial suggestion candidate was not exposed before the relayout oracle"
                }
            val initialBounds = Rect(initialCandidate.visibleBounds)
            assertValidCandidateBounds("initial", initialBounds)
            val beforeRelayout = captureActivityBitmap(scenario)

            val movedCandidate = waitForMovedCandidate(device, initialBounds, instrumentation)
            val movedBounds = Rect(movedCandidate.visibleBounds)
            assertValidCandidateBounds("moved", movedBounds)
            val afterRelayout = captureActivityBitmap(scenario)
            val expectedShiftPx = RepositionShiftDp * context.resources.displayMetrics.density
            val actualShiftPx = movedBounds.centerY() - initialBounds.centerY()
            assertTrue(
                "Candidate hit bounds did not follow the visual anchor relayout; expectedShift=$expectedShiftPx actualShift=$actualShiftPx initial=$initialBounds moved=$movedBounds",
                abs(actualShiftPx - expectedShiftPx) <= MaxRepositionErrorPx
            )

            val oldVisualDiff =
                beforeRelayout.changedPixelsComparedWith(
                    afterRelayout,
                    initialBounds.toPixelRegion(beforeRelayout.width, beforeRelayout.height)
                )
            val newVisualDiff =
                beforeRelayout.changedPixelsComparedWith(
                    afterRelayout,
                    movedBounds.toPixelRegion(beforeRelayout.width, beforeRelayout.height)
                )
            assertTrue(
                "Suggestion visuals did not leave the old candidate bounds; changedPixels=$oldVisualDiff bounds=$initialBounds",
                oldVisualDiff >= MinCandidateRelayoutChangedPixels
            )
            assertTrue(
                "Suggestion visuals did not enter the moved candidate bounds; changedPixels=$newVisualDiff bounds=$movedBounds",
                newVisualDiff >= MinCandidateRelayoutChangedPixels
            )

            movedCandidate.click()
            instrumentation.waitForIdleSync()
            assertTrue(
                "Candidate tap at moved hit bounds did not update the composer input",
                device.wait(Until.hasObject(By.textContains("@scroll-candidate-1")), CandidateTapWaitMs)
            )
            assertTrue(
                "Candidate tap at moved hit bounds did not close the popover",
                device.wait(Until.gone(By.text(CandidateLabel)), CandidateTapWaitMs)
            )
            val afterTap = captureActivityBitmap(scenario)
            val uncoveredUnderlay =
                afterTap.countNearColor(
                    UnderlayCyan,
                    movedBounds.toPixelRegion(afterTap.width, afterTap.height)
                )
            val leakAfterTap = afterTap.countNearColor(LeakRed, StatusRegionDp, instrumentation)
            assertTrue(
                "Popover did not uncover its underlay after moved candidate tap; cyanPixels=$uncoveredUnderlay",
                uncoveredUnderlay >= MinMovedCandidateUnderlayPixels
            )
            assertTrue("Moved candidate tap reached the underlying message; leakPixels=$leakAfterTap", leakAfterTap <= MaxLeakPixels)

            beforeRelayout.recycle()
            afterRelayout.recycle()
            afterTap.recycle()
        }
    }

    @Test
    fun overflowingSuggestionsScrollAndCandidateTapDoesNotReachUnderlyingMessage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", SlockKuiklyPages.THREAD_COMPOSER_VISUAL)
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", "composer-suggestion-scroll-oracle")
                        .put("visualFixture", "member_suggestions_scroll_oracle")
                        .toString()
                )
                .putExtra(SlockKuiklyActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            scenario.onActivity { activity -> activity.hideSystemBarsForSuggestionTest() }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(RenderSettleMs)

            val beforeDrag = captureActivityBitmap(scenario)
            swipeDp(instrumentation, xDp = 190f, startYDp = 188f, endYDp = 62f)
            SystemClock.sleep(GestureSettleMs)
            val afterDrag = captureActivityBitmap(scenario)

            val panelDiff = beforeDrag.changedPixelsComparedWith(afterDrag, PanelRegionDp, instrumentation)
            val underlayBeforeDrag = beforeDrag.countNearColor(UnderlayCyan, PanelRegionDp, instrumentation)
            val underlayAfterDrag = afterDrag.countNearColor(UnderlayCyan, PanelRegionDp, instrumentation)
            val leakAfterDrag = afterDrag.countNearColor(LeakRed, StatusRegionDp, instrumentation)
            assertTrue("Suggestion rows did not move after vertical drag; changedPixels=$panelDiff", panelDiff >= MinPanelChangedPixels)
            assertTrue(
                "Suggestion popover dismissed during drag; underlayBefore=$underlayBeforeDrag underlayAfter=$underlayAfterDrag",
                underlayAfterDrag <= underlayBeforeDrag + MaxUnderlayPixelIncreaseDuringDrag
            )
            assertTrue("Underlying message activated during drag; leakPixels=$leakAfterDrag", leakAfterDrag <= MaxLeakPixels)

            tapDp(instrumentation, xDp = 190f, yDp = 154f)
            SystemClock.sleep(GestureSettleMs)
            val afterTap = captureActivityBitmap(scenario)

            val inputDiff = afterDrag.changedPixelsComparedWith(afterTap, ComposerInputRegionDp, instrumentation)
            val uncoveredUnderlay = afterTap.countNearColor(UnderlayCyan, PanelRegionDp, instrumentation)
            val leakAfterTap = afterTap.countNearColor(LeakRed, StatusRegionDp, instrumentation)
            assertTrue("Candidate tap did not update composer input; changedPixels=$inputDiff", inputDiff >= MinInputChangedPixels)
            assertTrue("Candidate tap did not close the suggestion popover; underlayPixels=$uncoveredUnderlay", uncoveredUnderlay >= MinUncoveredUnderlayPixels)
            assertTrue("Candidate tap reached the underlying message; leakPixels=$leakAfterTap", leakAfterTap <= MaxLeakPixels)

            beforeDrag.recycle()
            afterDrag.recycle()
            afterTap.recycle()
        }
    }

    private fun swipeDp(
        instrumentation: Instrumentation,
        xDp: Float,
        startYDp: Float,
        endYDp: Float
    ) {
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val x = xDp * density
        val startY = startYDp * density
        val endY = endYDp * density
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY, 0))
        repeat(SwipeSteps) { step ->
            val fraction = (step + 1f) / SwipeSteps
            val eventTime = downTime + ((SwipeDurationMs * fraction).toLong())
            val y = startY + ((endY - startY) * fraction)
            instrumentation.sendPointerSync(MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, x, y, 0))
        }
        instrumentation.sendPointerSync(
            MotionEvent.obtain(downTime, downTime + SwipeDurationMs, MotionEvent.ACTION_UP, x, endY, 0)
        )
        instrumentation.waitForIdleSync()
    }

    private fun tapDp(instrumentation: Instrumentation, xDp: Float, yDp: Float) {
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val x = xDp * density
        val y = yDp * density
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(downTime, downTime + TapDurationMs, MotionEvent.ACTION_UP, x, y, 0))
        instrumentation.waitForIdleSync()
    }

    private fun captureActivityBitmap(scenario: ActivityScenario<SlockKuiklyActivity>): Bitmap {
        var bitmap: Bitmap? = null
        scenario.onActivity { activity ->
            val root = activity.window.decorView.rootView
            require(root.width > 0 && root.height > 0)
            bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { output ->
                root.draw(Canvas(output))
            }
        }
        return requireNotNull(bitmap)
    }

    private fun Bitmap.changedPixelsComparedWith(
        other: Bitmap,
        regionDp: DpRegion,
        instrumentation: Instrumentation
    ): Int = changedPixelsComparedWith(other, regionDp.toPixelRegion(instrumentation, width, height))

    private fun Bitmap.changedPixelsComparedWith(
        other: Bitmap,
        region: PixelRegion
    ): Int {
        var changed = 0
        for (y in region.top until region.bottom) {
            for (x in region.left until region.right) {
                val first = getPixel(x, y)
                val second = other.getPixel(x, y)
                if (
                    abs(Color.red(first) - Color.red(second)) > PixelDifferenceTolerance ||
                    abs(Color.green(first) - Color.green(second)) > PixelDifferenceTolerance ||
                    abs(Color.blue(first) - Color.blue(second)) > PixelDifferenceTolerance
                ) {
                    changed++
                }
            }
        }
        return changed
    }

    private fun Bitmap.countNearColor(
        target: Int,
        regionDp: DpRegion,
        instrumentation: Instrumentation
    ): Int = countNearColor(target, regionDp.toPixelRegion(instrumentation, width, height))

    private fun Bitmap.countNearColor(
        target: Int,
        region: PixelRegion
    ): Int {
        var count = 0
        for (y in region.top until region.bottom) {
            for (x in region.left until region.right) {
                if (getPixel(x, y).isNear(target, ColorTolerance)) count++
            }
        }
        return count
    }

    private fun Int.isNear(target: Int, tolerance: Int): Boolean =
        abs(Color.red(this) - Color.red(target)) <= tolerance &&
            abs(Color.green(this) - Color.green(target)) <= tolerance &&
            abs(Color.blue(this) - Color.blue(target)) <= tolerance

    private fun DpRegion.toPixelRegion(
        instrumentation: Instrumentation,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): PixelRegion {
        val density = instrumentation.targetContext.resources.displayMetrics.density
        return PixelRegion(
            left = (left * density).toInt().coerceIn(0, bitmapWidth),
            top = (top * density).toInt().coerceIn(0, bitmapHeight),
            right = (right * density).toInt().coerceIn(0, bitmapWidth),
            bottom = (bottom * density).toInt().coerceIn(0, bitmapHeight)
        )
    }

    private fun Rect.toPixelRegion(bitmapWidth: Int, bitmapHeight: Int): PixelRegion =
        PixelRegion(
            left = left.coerceIn(0, bitmapWidth),
            top = top.coerceIn(0, bitmapHeight),
            right = right.coerceIn(0, bitmapWidth),
            bottom = bottom.coerceIn(0, bitmapHeight)
        )

    private fun waitForMovedCandidate(
        device: UiDevice,
        initialBounds: Rect,
        instrumentation: Instrumentation
    ): androidx.test.uiautomator.UiObject2 {
        val deadline = SystemClock.uptimeMillis() + CandidateMoveWaitMs
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val candidate = device.findObject(By.text(CandidateLabel))
            if (candidate != null && abs(candidate.visibleBounds.centerY() - initialBounds.centerY()) >= MinCandidateMovePx) {
                return candidate
            }
            SystemClock.sleep(CandidateMovePollMs)
        }
        error("Suggestion candidate did not move after the relayout oracle; initial=$initialBounds")
    }

    private fun assertValidCandidateBounds(label: String, bounds: Rect) {
        assertTrue("$label candidate bounds are empty or inverted: $bounds", bounds.left < bounds.right && bounds.top < bounds.bottom)
    }

    private fun SlockKuiklyActivity.hideSystemBarsForSuggestionTest() {
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

    private data class DpRegion(val left: Float, val top: Float, val right: Float, val bottom: Float)
    private data class PixelRegion(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private companion object {
        private const val RenderSettleMs = 2_000L
        private const val GestureSettleMs = 700L
        private const val SwipeDurationMs = 600L
        private const val SwipeSteps = 12
        private const val TapDurationMs = 80L
        private const val CandidateWaitMs = 5_000L
        private const val CandidateMoveWaitMs = 10_000L
        private const val CandidateMovePollMs = 100L
        private const val CandidateTapWaitMs = 3_000L
        private const val PixelDifferenceTolerance = 18
        private const val ColorTolerance = 16
        private const val MinPanelChangedPixels = 8_000
        private const val MinInputChangedPixels = 120
        private const val MinCandidateMovePx = 80
        private const val MaxRepositionErrorPx = 12f
        private const val MinCandidateRelayoutChangedPixels = 600
        private const val MinMovedCandidateUnderlayPixels = 600
        private const val MaxUnderlayPixelIncreaseDuringDrag = 600
        private const val MinUncoveredUnderlayPixels = 20_000
        private const val MaxLeakPixels = 100
        private val PanelRegionDp = DpRegion(left = 34f, top = 8f, right = 352f, bottom = 216f)
        private val ComposerInputRegionDp = DpRegion(left = 42f, top = 228f, right = 330f, bottom = 278f)
        private val StatusRegionDp = DpRegion(left = 0f, top = 720f, right = 412f, bottom = 915f)
        private val UnderlayCyan = Color.rgb(34, 211, 238)
        private val LeakRed = Color.rgb(239, 68, 68)
        private const val RepositionOracleFixture = "member_suggestions_reposition_oracle"
        private const val CandidateLabel = "Scrollable candidate 1"
        private const val RepositionShiftDp = 120f
    }
}
