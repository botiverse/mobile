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
        // raft-ui listColumnMargin: 20 everywhere, 16 only from 768 under a mouse or trackpad. The
        // phone's toolbar sits on the rows' 20 line, with a finger or a mouse.
        assertEquals(20.dp, activityColumnMargin(RaftScale.Compact, RaftPointerInput.Touch))
        assertEquals(20.dp, activityColumnMargin(RaftScale.Compact, RaftPointerInput.Fine))
        assertEquals(20.dp, activityColumnMargin(RaftScale.Wide, RaftPointerInput.Touch))
        assertEquals(16.dp, activityColumnMargin(RaftScale.Wide, RaftPointerInput.Fine))
    }
}
