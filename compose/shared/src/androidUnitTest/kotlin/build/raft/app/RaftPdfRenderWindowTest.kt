package build.raft.app

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Render-window policy of the Android PDF viewer (pure geometry; no PdfRenderer): which pages get
 * a bitmap for a viewport, at what size, and that memory stays bounded at any zoom.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftPdfRenderWindowTest {
    // 1000 px wide viewport, no margins/gaps: every A4-ish page (1000 x 1414) is easy to reason about.
    private fun uniformLayout(pages: Int, width: Int = 1000, height: Int = 1414): RaftPdfPageLayout =
        raftPdfPageLayout(List(pages) { width to height }, viewportWidth = 1000f, sideMargin = 0f, gap = 0f)

    @Test
    fun headerCheckRejectsFilesThatAreNotPdf() {
        listOf(
            ByteArray(0),
            "%PD".encodeToByteArray(),
            "PK\u0003\u0004 zip archive".encodeToByteArray(),
            ByteArray(2048) { 'a'.code.toByte() } + "%PDF-1.7".encodeToByteArray()
        ).forEach { bytes -> assertFalse(raftPdfHasHeader(bytes), bytes.decodeToString().take(12)) }
        // Leading junk before the marker is allowed within the first KiB.
        assertTrue(raftPdfHasHeader("\u0000\u0000junk%PDF-1.4\n".encodeToByteArray()))
    }

    @Test
    fun renderWindowNeverExceedsItsCapAndKeepsTheVisiblePages() {
        // Tiny pages: a viewport shows 10 pages, the prefetch band reaches 20.
        val layout = raftPdfPageLayout(List(100) { 1000 to 100 }, viewportWidth = 1000f, sideMargin = 0f, gap = 0f)
        val window = layout.renderWindow(offsetY = 5000f, viewportHeight = 1000f, scale = 1f, maxPages = 12)

        assertEquals(12, window.count())
        assertTrue((50..59).all { it in window }, "visible pages 50-59 stay in $window")
        // Growth alternates below/above, starting below (reading direction).
        assertEquals(49..60, window)
    }

    @Test
    fun currentPageIsThePageUnderTheViewportCentre() {
        val layout = raftPdfPageLayout(List(5) { 1000 to 1000 }, viewportWidth = 1000f, sideMargin = 0f, gap = 100f)
        // Page i spans [100 + 1100 i, 1100 + 1100 i); the viewport is 1000 px tall.
        listOf(
            Triple(0f, 1f, 0), // centre at 500: page 0
            Triple(1100f, 1f, 1), // centre at 1600: page 1
            Triple(650f, 1f, 1), // centre at 1150, in the gap after page 0: the next page
            Triple(7000f, 2f, 3), // 2x, centre at 3750 (1x): page 3
            Triple(1e9f, 1f, 4), // past the end: the last page
        ).forEach { (offsetY, scale, expected) ->
            assertEquals(expected, layout.currentPage(offsetY, viewportHeight = 1000f, scale = scale), "offsetY=$offsetY scale=$scale")
        }
    }

    @Test
    fun planStaysWithinTheByteBudgetAtEveryZoom() {
        val layout = uniformLayout(pages = 50)
        val budget = 24L shl 20
        listOf(1f, 1.5f, 2f, 3f, 5f).forEach { zoom ->
            val plan = layout.renderPlan(offsetY = 1414f * 20 * zoom, viewportHeight = 2200f, scale = zoom, budgetBytes = budget)
            val bytes = plan.sumOf { it.bytes }
            assertTrue(plan.isNotEmpty(), "zoom=$zoom renders the visible page")
            assertTrue(bytes <= budget, "zoom=$zoom uses $bytes of $budget bytes")
            plan.forEach { render ->
                assertTrue(render.width.toLong() * render.height <= RaftPdfMaxPageBitmapPixels, "zoom=$zoom page ${render.pageIndex}")
            }
        }
    }

    @Test
    fun bottomPaddingLetsTheLastPageScrollClearOfTheNavigationBar() {
        val pages = List(3) { 1000 to 1000 }
        val plain = raftPdfPageLayout(pages, viewportWidth = 1000f, sideMargin = 0f, gap = 0f)
        val padded = raftPdfPageLayout(pages, viewportWidth = 1000f, sideMargin = 0f, gap = 0f, bottomPadding = 126f)

        assertEquals(plain.contentHeight + 126f, padded.contentHeight)
        assertEquals(plain.maxOffsets(2000f, 1f).second + 126f, padded.maxOffsets(2000f, 1f).second)
        // Zoomed, the padding scales with the content: the last page still ends above the bar.
        assertEquals((3000f + 126f) * 2f - 2000f, padded.maxOffsets(2000f, 2f).second)
    }
}
