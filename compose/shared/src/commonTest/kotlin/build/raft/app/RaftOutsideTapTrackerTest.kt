package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RaftOutsideTapTrackerTest {
    @Test
    fun hoverWithoutAPressNeverDismisses() {
        // The barrier appears under the mouse that just clicked the trigger: enter, then moves.
        assertEquals(false, RaftOutsideTapTracker().onEvent(anyPressed = false, moved = false))
        assertEquals(false, RaftOutsideTapTracker().onEvent(anyPressed = false, moved = true))
    }

    @Test
    fun pressAndReleaseInPlaceDismisses() {
        val tap = RaftOutsideTapTracker()
        assertNull(tap.onEvent(anyPressed = true, moved = false))
        assertEquals(true, tap.onEvent(anyPressed = false, moved = false))
    }

    @Test
    fun pressDragAndReleaseDoesNotDismiss() {
        val tap = RaftOutsideTapTracker()
        assertNull(tap.onEvent(anyPressed = true, moved = false))
        assertNull(tap.onEvent(anyPressed = true, moved = true))
        assertEquals(false, tap.onEvent(anyPressed = false, moved = false))
    }

    @Test
    fun aSecondFingerKeepsTheGestureOpenUntilAllAreUp() {
        val tap = RaftOutsideTapTracker()
        assertNull(tap.onEvent(anyPressed = true, moved = false))
        assertNull(tap.onEvent(anyPressed = true, moved = false))
        assertEquals(true, tap.onEvent(anyPressed = false, moved = false))
    }
}
