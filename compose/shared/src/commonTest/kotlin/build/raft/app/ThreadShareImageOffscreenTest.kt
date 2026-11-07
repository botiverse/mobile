package build.raft.app

import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
