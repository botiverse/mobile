package build.raft.app.shared

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import build.raft.app.SLOCK_CHILD_PAGE_VISIBILITY_PROBE_PAGE
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockChildPageVisibilityConnectedTest {
    @Test
    fun inactiveChildPageDoesNotPaintOverActiveChildPage() {
        val bitmap = captureProbe()
        val redRatio = bitmap.matchingPixelRatio { red, green, blue ->
            red > 220 && green < 60 && blue < 60
        }
        val blueRatio = bitmap.matchingPixelRatio { red, green, blue ->
            red < 60 && green < 60 && blue > 220
        }
        bitmap.recycle()

        println("SlockChildPageVisibilityProbe redRatio=$redRatio blueRatio=$blueRatio")
        assertTrue(
            "Inactive child page painted over the active page (redRatio=$redRatio blueRatio=$blueRatio)",
            redRatio > 0.5f && blueRatio < 0.1f
        )
    }

    private fun captureProbe(): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", SLOCK_CHILD_PAGE_VISIBILITY_PROBE_PAGE)
                .putExtra("pageData", "{}")
        var bitmap: Bitmap? = null
        ActivityScenario.launch<SlockKuiklyActivity>(intent).use {
            instrumentation.waitForIdleSync()
            // Bounded readiness poll (AGENTS.md device-wait rule): the previous
            // fixed 2.5s sleep raced first render on a slow CI emulator
            // (redRatio=0.0 blueRatio=0.0 — nothing painted yet). The page is
            // ready once either probe color covers most of the sampled region;
            // the overdraw assertion afterwards judges which color won.
            val deadline = SystemClock.uptimeMillis() + 15_000
            var lastCoverage = 0f
            while (true) {
                val candidate = captureScreenshot()
                val coverage =
                    candidate.matchingPixelRatio { red, green, blue ->
                        (red > 220 && green < 60 && blue < 60) || (red < 60 && green < 60 && blue > 220)
                    }
                lastCoverage = coverage
                if (coverage > 0.5f) {
                    bitmap = candidate
                    break
                }
                candidate.recycle()
                if (SystemClock.uptimeMillis() >= deadline) {
                    error("Child page visibility probe never rendered within 15s (probe color coverage=$lastCoverage)")
                }
                SystemClock.sleep(250)
            }
        }
        return requireNotNull(bitmap) { "Failed to capture child page visibility probe screenshot" }
    }

    private fun captureScreenshot(): Bitmap =
        requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
            "Unable to capture child page visibility probe screenshot"
        }

    private fun Bitmap.matchingPixelRatio(matches: (red: Int, green: Int, blue: Int) -> Boolean): Float {
        val row = IntArray(width)
        var matched = 0
        var total = 0
        val left = (width * 0.15f).toInt()
        val right = (width * 0.85f).toInt()
        val top = (height * 0.15f).toInt()
        val bottom = (height * 0.85f).toInt()
        for (y in top until bottom) {
            getPixels(row, 0, width, 0, y, width, 1)
            for (x in left until right) {
                val pixel = row[x]
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                if (matches(red, green, blue)) {
                    matched++
                }
                total++
            }
        }
        return if (total == 0) 0f else matched.toFloat() / total.toFloat()
    }
}
