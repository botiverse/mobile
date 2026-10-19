package build.raft.app.shared

import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import build.raft.app.PAGER_SCROLL_PROBE_TARGET_PAGE
import build.raft.app.SLOCK_PAGER_SCROLL_PROBE_PAGE
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the Kuikly pager fix (bytemain/KuiklyUI cc-cata/pager-scrolltopage-native):
 * a programmatic `PagerState.scrollToPage` on a `userScrollEnabled = false`
 * HorizontalPager — the exact AppShell child-tab config — must move the native
 * visible page. The probe page ([SLOCK_PAGER_SCROLL_PROBE_PAGE]) renders three
 * full-screen colored pages and scrolls to a target page after the first frame.
 *
 * Before the fix, scrollToPage only updated `currentPage` (dispatchRawDelta) and
 * never drove the native scroll view, so the visible page stayed on page 0 —
 * `changedRatio` between targetPage=0 and targetPage=2 would be ~0 and this test
 * would fail. With the fix the visible page actually moves (red -> blue) and the
 * pixels change.
 */
class SlockPagerScrollProbeConnectedTest {
    @Test
    fun programmaticScrollToPageMovesVisiblePage() {
        val page0 = captureProbe(targetPage = 0)
        val page2 = captureProbe(targetPage = 2)
        val changed = page0.changedPixelRatio(page2)
        page0.recycle()
        page2.recycle()

        println("SlockPagerScrollProbe changedRatio(page0,page2)=$changed")
        assertTrue(
            "scrollToPage did not move the visible page (changedRatio=$changed). The Kuikly " +
                "HorizontalPager scrollToPage is not driving the native scroll view.",
            changed > 0.5f
        )
    }

    private fun captureProbe(targetPage: Int): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockCmpActivity::class.java)
                .putExtra("pageName", SLOCK_PAGER_SCROLL_PROBE_PAGE)
                .putExtra(
                    "pageData",
                    JSONObject().put(PAGER_SCROLL_PROBE_TARGET_PAGE, targetPage).toString()
                )
        var bitmap: Bitmap? = null
        ActivityScenario.launch<SlockCmpActivity>(intent).use { _ ->
            instrumentation.waitForIdleSync()
            // Let Kuikly render the pager and settle the programmatic scroll.
            Thread.sleep(2_500)
            bitmap = captureScreenshot()
        }
        return requireNotNull(bitmap) { "Failed to capture probe screenshot for targetPage=$targetPage" }
    }

    private fun captureScreenshot(): Bitmap =
        requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
            "Unable to capture pager scroll probe screenshot"
        }

    private fun Bitmap.changedPixelRatio(other: Bitmap): Float {
        require(width == other.width && height == other.height) {
            "Bitmap size mismatch: ${width}x$height vs ${other.width}x${other.height}"
        }
        val row = IntArray(width)
        val otherRow = IntArray(width)
        var changed = 0
        var total = 0
        // Ignore the top/bottom system-UI bands; compare the pager body only.
        val top = (height * 0.15f).toInt()
        val bottom = (height * 0.85f).toInt()
        for (y in top until bottom) {
            getPixels(row, 0, width, 0, y, width, 1)
            other.getPixels(otherRow, 0, width, 0, y, width, 1)
            for (x in row.indices) {
                if (row[x] != otherRow[x]) changed++
                total++
            }
        }
        return if (total == 0) 0f else changed.toFloat() / total.toFloat()
    }
}
