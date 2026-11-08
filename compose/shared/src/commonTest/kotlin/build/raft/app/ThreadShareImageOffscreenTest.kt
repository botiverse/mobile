package build.raft.app

import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The off-screen share image (artin, 2026-11-06): width presets, the pixel budget that lowers the
 * scale of a long selection (web `planScreenshotPixelRatio`), the selection's author headers, and
 * the cache file handed to `saveThreadShareImage`.
 */
class ThreadShareImageOffscreenTest {
    @Test
    fun presetsAreThePhoneTabletAndWebWidthsAndPhoneIsTheDefault() {
        assertEquals(listOf(390, 600, 768, null), ThreadShareImageWidth.entries.map { it.widthDp })
        assertEquals(390, ThreadShareImageWidthChoice().widthDp)
    }

    @Test
    fun customStartsFromTheShownWidthAndStaysInItsRange() {
        val custom = ThreadShareImageWidthChoice().select(ThreadShareImageWidth.Wide).select(ThreadShareImageWidth.Custom)
        assertEquals(768, custom.widthDp, "picking Custom keeps the image where it was")
        assertEquals(1080, custom.copy(customWidthDp = 4000).widthDp)
        assertEquals(320, custom.copy(customWidthDp = 10).widthDp)
        // Back to a preset and to Custom again: the slider starts from that preset's width.
        val moved = custom.copy(customWidthDp = 540)
        assertEquals(390, moved.select(ThreadShareImageWidth.Phone).widthDp)
        assertEquals(390, moved.select(ThreadShareImageWidth.Phone).select(ThreadShareImageWidth.Custom).widthDp)
    }

    @Test
    fun aShortSelectionKeepsTheScreenScale() {
        assertEquals(3f, threadShareImageScale(390f, 600f, preferredScale = 3f))
        assertEquals(2f, threadShareImagePreferredScale(1f), "desktop at 1x still exports at 2x, as the web")
        assertEquals(3f, threadShareImagePreferredScale(3.5f))
    }

    @Test
    fun aLongSelectionIsDrawnAtALowerScaleWithinTheBudget() {
        val width = 768f
        val height = 12_000f
        val scale = threadShareImageScale(width, height, preferredScale = 3f)
        assertTrue(scale < 3f)
        assertTrue(ceil(width * scale).toLong() * ceil(height * scale).toLong() <= ThreadShareImagePixelBudget)
        assertTrue(height * scale <= ThreadShareImageMaxSidePx)
    }

    @Test
    fun aTallNarrowImageStaysUnderTheTextureLimit() {
        val scale = threadShareImageScale(320f, 9_000f, preferredScale = 3f)
        assertTrue(9_000f * scale <= ThreadShareImageMaxSidePx, "scale $scale")
    }

    /**
     * Many short rows: the dp plan fits, but each row, frame and gap rounds to whole px at the
     * lowered scale, and 500 of them take the real card past the 16,000 px side. The renderer
     * checks the card's real size and steps the scale down until it fits.
     */
    @Test
    fun manyRowsStepTheScaleDownUntilTheRealCardFits() {
        val cardWidthDp = 390
        val rowCount = 500
        val rowHeightDp = 10f
        // As the rows lay out at a scale: each one's px height and width rounded up.
        fun layout(scale: Float) =
            List(rowCount) { IntSize(ceil((cardWidthDp - 36) * scale).toInt(), ceil(rowHeightDp * scale).toInt()) }
        fun realSize(rows: List<IntSize>, scale: Float) =
            threadShareImageWidthPx(rows.maxOf { it.width }, scale).toLong() to threadShareImageHeightPx(rows.map { it.height }, scale).toLong()

        // The dp plan alone (what the renderer used to stop at) leaves the real card too tall.
        var planOnly = 3f
        repeat(10) {
            val rows = layout(planOnly)
            val planned =
                threadShareImageScale(
                    cardWidthDp.toFloat(),
                    rows.sumOf { it.height } / planOnly + threadShareImageChromeHeightDp(rowCount),
                    preferredScale = 3f,
                )
            if (planned < planOnly * 0.98f) planOnly = planned
        }
        val (_, planOnlyHeight) = realSize(layout(planOnly), planOnly)
        assertTrue(planOnlyHeight > ThreadShareImageMaxSidePx, "precondition: the dp plan overshoots ($planOnlyHeight px)")

        // The renderer's loop: lay out, then relayout at the returned scale until it says done.
        var scale = 3f
        var layouts = 0
        while (true) {
            val next = threadShareImageRelayoutScale(cardWidthDp, layout(scale), scale, preferredScale = 3f) ?: break
            assertTrue(next < scale, "each relayout lowers the scale")
            scale = next
            layouts++
            assertTrue(layouts < 20, "the scale settles")
        }
        val (width, height) = realSize(layout(scale), scale)
        assertTrue(height <= ThreadShareImageMaxSidePx, "height $height px at $scale")
        assertTrue(width <= ThreadShareImageMaxSidePx)
        assertTrue(width * height <= ThreadShareImagePixelBudget, "${width}x$height px")
        assertTrue(scale > planOnly * 0.9f, "only as far down as needed: $scale vs $planOnly")
    }

    @Test
    fun aWideLongCardKeepsWithinThePixelBudget() {
        // 768dp wide: the pixel count, not the side, is what the rounding tips over.
        val rows = List(500) { IntSize(ceil(732 * 1.3791137f).toInt(), ceil(13.3f * 1.3791137f).toInt()) }
        val next = threadShareImageRelayoutScale(768, rows, 1.3791137f, preferredScale = 3f)
        val width = threadShareImageWidthPx(rows.maxOf { it.width }, 1.3791137f).toLong()
        val height = threadShareImageHeightPx(rows.map { it.height }, 1.3791137f).toLong()
        assertTrue(width * height > ThreadShareImagePixelBudget, "precondition: ${width}x$height px")
        assertNotNull(next)
        assertTrue(next < 1.3791137f)
    }

    @Test
    fun aCardThatFitsIsDrawnAtItsScale() {
        val rows = List(3) { IntSize(1062, 300) }
        assertNull(threadShareImageRelayoutScale(390, rows, 3f, preferredScale = 3f))
        assertNull(threadShareImageRelayoutScale(390, emptyList(), 3f, preferredScale = 3f))
    }

    @Test
    fun thePreviewCopyIsAbout400dpWideAndUnderTheTextureLimit() {
        // A phone at 3x: 1200px wide; a 768dp image at 3x (2304px) halves to about that.
        assertEquals(IntSize(1200, 2604), threadShareImagePreviewSize(2304, 5000, maxWidthPx = 1200))
        // Already small: unchanged.
        assertEquals(IntSize(1170, 900), threadShareImagePreviewSize(1170, 900, maxWidthPx = 1200))
        // Tall: the 4096 px side wins over the width.
        val tall = threadShareImagePreviewSize(1170, 16_000, maxWidthPx = 1200)
        assertEquals(4096, tall.height)
        assertTrue(tall.width in 299..300, "keeps the aspect: $tall")
        assertTrue(tall.width <= ThreadShareImagePreviewMaxSidePx && tall.height <= ThreadShareImagePreviewMaxSidePx)
    }

    @Test
    fun rasterBandsCoverTheImageOnce() {
        assertEquals(listOf(0 until 2048, 2048 until 4096, 4096 until 5000), threadShareImageRasterBands(5000))
        assertEquals(listOf(0 until 100), threadShareImageRasterBands(100))
        assertEquals(emptyList(), threadShareImageRasterBands(0))
    }

    @Test
    fun theSelectionRegroupsAuthorsAsTheLiveRowsDo() {
        val first = message("m1", "2026-10-21T16:20:00Z")
        val second = message("m2", "2026-10-21T16:21:00Z")
        val third = message("m3", "2026-10-21T16:22:00Z")
        val timeline = listOf(first, second, third)
        // m2 and m3 picked: m2 opens the image's run (its predecessor is not picked), m3 continues it.
        assertContentEquals(
            listOf(false, true),
            threadShareImageSuppressesAuthor(timeline, listOf(second, third), compactConsecutiveMessages = true, localOffsetMinutes = 0),
        )
        // Not compacting: every message shows its author.
        assertContentEquals(
            listOf(false, false),
            threadShareImageSuppressesAuthor(timeline, listOf(second, third), compactConsecutiveMessages = false, localOffsetMinutes = 0),
        )
    }

    @Test
    fun theCacheFileRefBecomesAPath() {
        assertEquals("/data/user/0/build.raft.app/cache/raft_snapshots/a.png", threadShareImageFilePath("file:///data/user/0/build.raft.app/cache/raft_snapshots/a.png"))
        assertEquals("/Users/me/Library/Application Support/Raft/cache/a.png", threadShareImageFilePath("file:/Users/me/Library/Application%20Support/Raft/cache/a.png"))
        assertEquals("/tmp/a.png", threadShareImageFilePath("/tmp/a.png"))
        assertEquals(null, threadShareImageFilePath(""))
    }

    private fun message(id: String, timestamp: String) =
        RaftThreadMessageItem(
            id = id,
            authorId = "agent-cindy",
            authorName = "Cindy",
            authorDescription = "",
            authorKind = "agent",
            avatarLabel = "CI",
            timestampLabel = timestamp,
            body = "Message $id",
        )
}
