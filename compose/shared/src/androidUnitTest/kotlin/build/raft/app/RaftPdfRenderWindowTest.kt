package build.raft.app

import kotlin.test.assertContentEquals
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
    fun fixturePagesLayOutInOneColumnFittingTheWidth() {
        val fixture = checkNotNull(javaClass.getResourceAsStream("/pdf/two-page.pdf")).readBytes()
        assertTrue(raftPdfHasHeader(fixture), "fixture starts with %PDF-")
        val mediaBoxes =
            Regex("""/MediaBox \[0 0 (\d+) (\d+)]""")
                .findAll(fixture.decodeToString())
                .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
                .toList()
        assertEquals(listOf(595 to 842, 842 to 595), mediaBoxes)

        val layout = raftPdfPageLayout(mediaBoxes, viewportWidth = 1080f, sideMargin = 20f, gap = 10f)

        assertEquals(1040f, layout.pageWidth)
        assertEquals(10f, layout.pageTops[0])
        assertEquals(1040f * 842f / 595f, layout.pageHeights[0], 0.01f)
        assertEquals(layout.pageBottom(0) + 10f, layout.pageTops[1], 0.01f)
        assertEquals(1040f * 595f / 842f, layout.pageHeights[1], 0.01f)
        assertEquals(layout.pageBottom(1) + 10f, layout.contentHeight, 0.01f)
    }

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
    fun renderWindowIsTheVisiblePagesPlusHalfAViewportOfPrefetch() {
        val layout = uniformLayout(pages = 20)
        // (offsetY, viewportHeight, scale) -> expected window.
        val cases =
            listOf(
                Triple(0f, 2000f, 1f) to 0..2, // pages 0-1 visible, prefetch reaches page 2
                Triple(1414f * 5, 1414f, 1f) to 4..6, // exactly page 5 on screen, one page each side
                Triple(1414f * 19, 1414f, 1f) to 18..19, // last page: no page past the end
                Triple(1414f * 10 * 2f, 1414f, 2f) to 9..10, // zoomed 2x: half a page on screen
            )
        cases.forEach { (viewport, expected) ->
            val (offsetY, height, scale) = viewport
            assertEquals(expected, layout.renderWindow(offsetY, height, scale), "viewport=$viewport")
        }
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
    fun emptyOrUnmeasuredViewportsRenderNothing() {
        assertTrue(uniformLayout(pages = 0).renderWindow(0f, 1000f, 1f).isEmpty())
        assertTrue(uniformLayout(pages = 3).renderWindow(0f, 0f, 1f).isEmpty())
        assertTrue(uniformLayout(pages = 3).renderPlan(0f, 0f, 1f, budgetBytes = 64L shl 20).isEmpty())
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
    fun renderScaleSnapsUpToTheNextStep() {
        listOf(0.5f to 1f, 1f to 1f, 1.2f to 1.5f, 1.5f to 1.5f, 2.6f to 3f, 4.9f to 5f, 9f to 5f).forEach { (zoom, step) ->
            assertEquals(step, raftPdfRenderScaleStep(zoom), "zoom=$zoom")
        }
    }

    @Test
    fun visiblePagesFollowTheZoomStepAndPrefetchPagesStayAtOneX() {
        val layout = uniformLayout(pages = 10)
        val budget = 96L shl 20
        // Zoom 1.2 on page 3: render step 1.5 for the visible page, 1x for its neighbours.
        val plan = layout.renderPlan(offsetY = 1414f * 3 * 1.2f, viewportHeight = 1000f, scale = 1.2f, budgetBytes = budget)

        assertEquals(3, plan.first().pageIndex, "the visible page renders first")
        assertEquals(1500 to (1414 * 1.5f).toInt(), plan.first().width to plan.first().height)
        plan.drop(1).forEach { render ->
            assertEquals(1000 to 1414, render.width to render.height, "prefetch page ${render.pageIndex} at 1x")
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
    fun cappedPagesGetViewportSizedDetailPatchesOnly() {
        val layout = uniformLayout(pages = 4)
        // 1x: whole pages fit the cap, no patch.
        assertTrue(layout.detailRegions(0f, 0f, 1000f, 2000f, scale = 1f).isEmpty())

        // 4x, looking at the middle of page 1: the page at 4x (4000 x 5656) exceeds the 4 MP cap.
        val offsetX = 1500f
        val offsetY = (1414f + 600f) * 4f
        val regions = layout.detailRegions(offsetX, offsetY, viewportWidth = 1000f, viewportHeight = 2000f, scale = 4f)

        assertEquals(listOf(1), regions.map { it.pageIndex })
        val region = regions.single()
        assertContentEquals(floatArrayOf(375f, 2014f, 625f, 2514f), floatArrayOf(region.left, region.top, region.right, region.bottom))
        assertEquals(1000 to 2000, region.bitmapSize(4f), "one bitmap pixel per screen pixel")
    }

    @Test
    fun detailPatchesAreClippedToEachVisiblePage() {
        val layout = raftPdfPageLayout(List(3) { 1000 to 1000 }, viewportWidth = 1000f, sideMargin = 0f, gap = 0f)
        // 3x straddling pages 0 and 1 (boundary at 1000 content px = 3000 screen px).
        val regions = layout.detailRegions(offsetX = 0f, offsetY = 2400f, viewportWidth = 1000f, viewportHeight = 1200f, scale = 3f)

        assertEquals(listOf(0, 1), regions.map { it.pageIndex })
        assertEquals(1000f, regions[0].bottom)
        assertEquals(1000f, regions[1].top)
        val pixels = regions.sumOf { it.bitmapSize(3f).let { (w, h) -> w.toLong() * h } }
        // About one viewport: each patch edge rounds out by under one content pixel.
        assertTrue(pixels <= (1000L + 3L) * (1200L + 6L), "patches cover one viewport: $pixels px")
    }

    @Test
    fun budgetComesFromTheHeapWithinBounds() {
        assertEquals(24L shl 20, raftPdfBitmapBudgetBytes(64L shl 20))
        assertEquals(64L shl 20, raftPdfBitmapBudgetBytes(512L shl 20))
        assertEquals(96L shl 20, raftPdfBitmapBudgetBytes(4L shl 30))
    }
}
