package build.raft.app

import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftScale
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityFilterBarHeightTest {
    @Test
    fun phoneKeepsItsToolbarWhateverTheInput() {
        assertEquals(54.dp, activityFilterBarHeight(RaftScale.Compact, RaftPointerInput.Touch))
        assertEquals(54.dp, activityFilterBarHeight(RaftScale.Compact, RaftPointerInput.Fine))
    }

    @Test
    fun wideTakesTheListToolbarLine() {
        // raft-design spacing.md: the list toolbar line is 49 by touch, 44 with a mouse (web inbox toolbar
        // `h-[calc(--spacing(12)+1px)] md:pointer-fine:h-(--header-height)`).
        assertEquals(49.dp, activityFilterBarHeight(RaftScale.Wide, RaftPointerInput.Touch))
        assertEquals(44.dp, activityFilterBarHeight(RaftScale.Wide, RaftPointerInput.Fine))
    }

    @Test
    fun columnMarginIsTheRowsEdge() {
        // raft-design lists.md, One edge: 20 from the column's edge on a touch screen from 768, 16 with a
        // mouse; the phone keeps its 16 here.
        assertEquals(16.dp, activityColumnMargin(RaftScale.Compact, RaftPointerInput.Touch))
        assertEquals(20.dp, activityColumnMargin(RaftScale.Wide, RaftPointerInput.Touch))
        assertEquals(16.dp, activityColumnMargin(RaftScale.Wide, RaftPointerInput.Fine))
    }
}
