package build.raft.app.shared

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import build.raft.app.SLOCK_CHILD_PAGE_VISIBILITY_PROBE_PAGE
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockChildPageVisibilityConnectedTest {
    @Test
    fun probeReadinessRequiresASingleDominantProbeColor() {
        assertFalse(childPageProbeReady(redRatio = 0.49f, blueRatio = 0.02f))
        assertFalse(childPageProbeReady(redRatio = 0.3f, blueRatio = 0.3f))
        assertFalse(childPageProbeReady(redRatio = 0.0f, blueRatio = 0.0f))
        assertTrue(childPageProbeReady(redRatio = 0.51f, blueRatio = 0.0f))
        assertTrue(childPageProbeReady(redRatio = 0.0f, blueRatio = 0.51f))
    }

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
            // (redRatio=0.0 blueRatio=0.0 — nothing painted yet). Ready only
            // once a SINGLE probe color dominates the sampled region: a union
            // threshold would accept transition frames (e.g. red 49% + blue 2%)
            // and re-create the race. A blue-dominant frame is ready too — the
            // overdraw assertion afterwards reports that as the real failure.
            // 30s: a fresh SlockKuiklyActivity + Kuikly init on a loaded 2-core CI
            // runner can exceed 15s before first paint (observed 9-19s locally and
            // one 15s CI timeout on an otherwise-green class).
            val deadline = SystemClock.uptimeMillis() + 30_000
            while (true) {
                val candidate = captureScreenshot()
                val redRatio = candidate.matchingPixelRatio { red, green, blue ->
                    red > 220 && green < 60 && blue < 60
                }
                val blueRatio = candidate.matchingPixelRatio { red, green, blue ->
                    red < 60 && green < 60 && blue > 220
                }
                if (childPageProbeReady(redRatio, blueRatio)) {
                    bitmap = candidate
                    break
                }
                candidate.recycle()
                if (SystemClock.uptimeMillis() >= deadline) {
                    error(
                        "Child page visibility probe never reached a dominant probe color " +
                            "within 30s (redRatio=$redRatio blueRatio=$blueRatio)"
                    )
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

internal fun childPageProbeReady(redRatio: Float, blueRatio: Float): Boolean =
    redRatio > 0.5f || blueRatio > 0.5f
