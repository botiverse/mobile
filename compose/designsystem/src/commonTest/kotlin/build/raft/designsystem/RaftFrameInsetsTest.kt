package build.raft.designsystem

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftFrameInsets
import build.raft.designsystem.components.RaftFramePx
import build.raft.designsystem.components.RaftPopupMargins
import build.raft.designsystem.components.RaftSafeArea
import build.raft.designsystem.components.clearOf
import build.raft.designsystem.components.raftMenuPosition
import build.raft.designsystem.components.raftTooltipClearOfFrame
import kotlin.test.Test
import kotlin.test.assertEquals

/** raft-ui #860 on the app: popups keep clear of the host's frame insets ([RaftFrameInsets]). */
class RaftFrameInsetsTest {
    private val density = Density(1f)
    private val window = IntSize(400, 800)
    private val noShadow = RaftPopupMargins(0, 0, 0)

    @Test
    fun aMenuStaysEightFromARightInset() {
        // A 200 wide menu under an anchor at the right edge, with a 57 column on the right.
        val area = RaftSafeArea().clearOf(RaftFrameInsets(right = 57.dp), density)
        val at = raftMenuPosition(IntRect(330, 100, 370, 140), window, IntSize(200, 120), gap = 4, windowMargin = 8, insets = noShadow, safeArea = area)
        assertEquals(400 - 57 - 8 - 200, at.x)
    }

    @Test
    fun aTooltipAboveFlipsBelowOutOfTheTopBand() {
        val anchor = IntRect(100, 50, 140, 90)
        // Material put it above, at y 10: inside a 48 band.
        val at = raftTooltipClearOfFrame(IntOffset(80, 10), anchor, window, IntSize(80, 32), spacing = 8, frame = RaftFramePx(top = 48))
        assertEquals(IntOffset(80, 98), at)
    }
}
