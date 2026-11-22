package build.raft.designsystem

import androidx.compose.ui.input.pointer.PointerType
import build.raft.designsystem.components.raftPointerTypeHovers
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftStateOverlayTest {
    /** CSS `@media (hover: hover)`: a mouse, a trackpad or a pen hovers; a finger never does. */
    @Test
    fun onlyPointersThatHoverHover() {
        assertTrue(raftPointerTypeHovers(PointerType.Mouse))
        assertTrue(raftPointerTypeHovers(PointerType.Stylus))
        assertTrue(raftPointerTypeHovers(PointerType.Eraser))
        assertFalse(raftPointerTypeHovers(PointerType.Touch))
        assertFalse(raftPointerTypeHovers(PointerType.Unknown))
    }
}
