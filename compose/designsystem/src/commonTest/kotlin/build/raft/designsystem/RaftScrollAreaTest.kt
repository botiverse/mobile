package build.raft.designsystem

import build.raft.designsystem.components.RaftScrollbarThumb
import build.raft.designsystem.components.raftLazyScrollExtent
import build.raft.designsystem.components.raftScrollbarThumb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** raft-ui 0.60 ScrollArea's thumb geometry (scroll-geometry.ts), as [raftScrollbarThumb] draws it. */
class RaftScrollAreaTest {
    @Test
    fun noThumbWhenTheContentFits() {
        assertNull(raftScrollbarThumb(viewport = 400f, content = 400f, scroll = 0f, minLength = 24f))
        assertNull(raftScrollbarThumb(viewport = 400f, content = 300f, scroll = 0f, minLength = 24f))
        assertNull(raftScrollbarThumb(viewport = 0f, content = 300f, scroll = 0f, minLength = 24f))
    }

    @Test
    fun theThumbIsTheViewportsShareOfTheRail() {
        // Rail 400 - 2 x 3 = 394; the viewport is half the content: 197 long, at the top.
        assertEquals(RaftScrollbarThumb(offset = 3f, length = 197f), raftScrollbarThumb(400f, 800f, 0f, 24f, gap = 3f))
        // Scrolled to the end, it ends 3 above the bottom.
        val end = assertNotNull(raftScrollbarThumb(400f, 800f, 400f, 24f, gap = 3f))
        assertEquals(397f, end.offset + end.length)
        // Halfway, halfway along what is left of the rail.
        assertEquals(3f + (394f - 197f) / 2f, assertNotNull(raftScrollbarThumb(400f, 800f, 200f, 24f, gap = 3f)).offset)
    }

    @Test
    fun aLongListKeepsTheMinimumThumbLength() {
        val thumb = assertNotNull(raftScrollbarThumb(viewport = 400f, content = 100_000f, scroll = 99_600f, minLength = 24f))
        assertEquals(24f, thumb.length)
        assertEquals(376f, thumb.offset)
    }

    @Test
    fun railInsetsKeepTheStickyHeaderClear() {
        val thumb = assertNotNull(raftScrollbarThumb(400f, 800f, 0f, 24f, gap = 3f, railStart = 40f, railEnd = 40f))
        assertEquals(43f, thumb.offset)
        assertEquals((400f - 80f - 6f) / 2f, thumb.length)
    }

    @Test
    fun overscrollStaysOnTheRail() {
        assertEquals(0f, assertNotNull(raftScrollbarThumb(400f, 800f, -50f, 24f)).offset)
        val past = assertNotNull(raftScrollbarThumb(400f, 800f, 900f, 24f))
        assertEquals(400f, past.offset + past.length)
    }

    @Test
    fun aLazyListIsEstimatedFromItsVisibleRows() {
        // 100 rows of 40 with 2 between, 4 padding at each end: mean 42, 42 x 100 - 2 + 8.
        val extent = assertNotNull(raftLazyScrollExtent(100, firstIndex = 10, firstOffset = 5, visibleSizes = List(10) { 40 }, spacing = 2, paddingBefore = 4, paddingAfter = 4))
        assertEquals(4206f, extent.content)
        assertEquals(425f, extent.scroll)
        assertNull(raftLazyScrollExtent(0, 0, 0, emptyList(), 0, 0, 0))
    }
}
