package build.raft.app

import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ActivityRowLayoutTest {
    @Test
    fun phoneTouchIsTheWebCompactCellWithTwoExcerptLines() {
        // raft-design lists.md / inbox-item.md density="compact" by touch: the shape 8px in, content 12px
        // inside it (20px from the screen's edge), 8px above and below with a 56px minimum, rows 2px
        // apart, no dividers; the phone keeps its two reserved excerpt lines (artin #3653, 2026-11-20).
        val layout = ActivityRowLayout.of(RaftScale.Compact, RaftPointerInput.Touch)
        assertEquals(8.dp, layout.shapeInset)
        assertEquals(12.dp, layout.contentPadX)
        assertEquals(20.dp, layout.contentEdge)
        assertEquals(8.dp, layout.padY)
        assertEquals(56.dp, layout.minHeight)
        assertEquals(2, layout.previewLines)
        assertFalse(layout.dividers)
        assertEquals(2.dp, layout.rowGap)
        assertEquals(8.dp, layout.listTop)
    }

    @Test
    fun phoneFineKeepsTheNarrowMouseBlockPadding() {
        // With a mouse in a window narrower than 768px: py-3 and no touch minimum; the inline padding
        // only narrows from md, so the content stays 20px in.
        val layout = ActivityRowLayout.of(RaftScale.Compact, RaftPointerInput.Fine)
        assertEquals(20.dp, layout.contentEdge)
        assertEquals(12.dp, layout.padY)
        assertEquals(0.dp, layout.minHeight)
        assertEquals(2, layout.previewLines)
        assertFalse(layout.dividers)
        assertEquals(2.dp, layout.rowGap)
    }

    @Test
    fun wideTouchIsTheWebCompactCell() {
        // raft-design lists.md: the shape 8px in from each side, content 12px inside it (20px from the
        // column's edge), 8px above and below with a 56px minimum by touch, rows 2px apart, no dividers;
        // inbox-item.md density="compact": one excerpt line.
        val layout = ActivityRowLayout.of(RaftScale.Wide, RaftPointerInput.Touch)
        assertEquals(8.dp, layout.shapeInset)
        assertEquals(12.dp, layout.contentPadX)
        assertEquals(20.dp, layout.contentEdge)
        assertEquals(8.dp, layout.padY)
        assertEquals(56.dp, layout.minHeight)
        assertEquals(1, layout.previewLines)
        assertFalse(layout.dividers)
        assertEquals(2.dp, layout.rowGap)
        assertEquals(8.dp, layout.listTop)
    }

    @Test
    fun wideFineTakesTheMouseInsets() {
        // With a mouse: content 8px inside the shape, 16px from the column's edge; no touch minimum.
        val layout = ActivityRowLayout.of(RaftScale.Wide, RaftPointerInput.Fine)
        assertEquals(8.dp, layout.shapeInset)
        assertEquals(16.dp, layout.contentEdge)
        assertEquals(8.dp, layout.padY)
        assertEquals(0.dp, layout.minHeight)
        assertEquals(1, layout.previewLines)
        assertFalse(layout.dividers)
    }
}
