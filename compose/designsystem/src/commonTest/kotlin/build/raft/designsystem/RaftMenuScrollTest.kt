package build.raft.designsystem

import androidx.compose.ui.geometry.Rect
import build.raft.designsystem.components.raftMenuFocusRevealRect
import build.raft.designsystem.components.raftOverflowFadeY
import kotlin.test.Test
import kotlin.test.assertEquals

/** raft-ui 0.106.0: a long menu's rows scroll, their cut edges fade, the focused row stays clear. */
class RaftMenuScrollTest {
    @Test
    fun aScrollerThatFitsHasNoFade() {
        assertEquals(0f to 0f, raftOverflowFadeY(scrolled = 0, maxScroll = 0, fadeSize = 24f))
        // ScrollState's max before the first layout.
        assertEquals(0f to 0f, raftOverflowFadeY(scrolled = 0, maxScroll = Int.MAX_VALUE, fadeSize = 24f))
    }

    @Test
    fun theFadeIsThePxLeftPastEachEdgeAtMost24() {
        // At the top: nothing above, the full fade below.
        assertEquals(0f to 24f, raftOverflowFadeY(scrolled = 0, maxScroll = 300, fadeSize = 24f))
        // Scrolled 10px: the top fade grows in with it.
        assertEquals(10f to 24f, raftOverflowFadeY(scrolled = 10, maxScroll = 300, fadeSize = 24f))
        // In the middle: both edges at 24.
        assertEquals(24f to 24f, raftOverflowFadeY(scrolled = 150, maxScroll = 300, fadeSize = 24f))
        // 6px from the end, then at the end.
        assertEquals(24f to 6f, raftOverflowFadeY(scrolled = 294, maxScroll = 300, fadeSize = 24f))
        assertEquals(24f to 0f, raftOverflowFadeY(scrolled = 300, maxScroll = 300, fadeSize = 24f))
    }

    @Test
    fun aFocusedRowIsRevealedWithTheFadesLengthAboveAndBelow() {
        // `scroll-py-6`: a 36px row is kept in view 24px clear of either edge.
        assertEquals(Rect(0f, -24f, 240f, 60f), raftMenuFocusRevealRect(width = 240, height = 36, clearance = 24f))
    }
}
