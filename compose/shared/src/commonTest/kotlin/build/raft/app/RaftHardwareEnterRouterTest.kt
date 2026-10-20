package build.raft.app

import build.raft.app.RaftHardwareEnterRouter.Phase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftHardwareEnterRouterTest {
    private var sends = 0
    private val send: () -> Unit = { sends++ }

    @Test
    fun plainEnterSendsOnKeyUp() {
        val router = RaftHardwareEnterRouter()
        assertTrue(router.onEnter(Phase.Down, shiftPressed = false, singleLine = false, send))
        assertEquals(0, sends)
        assertTrue(router.onEnter(Phase.Up, shiftPressed = false, singleLine = false, send))
        assertEquals(1, sends)
    }

    @Test
    fun shiftReleasedBeforeEnterStillInsertsNewline() {
        val router = RaftHardwareEnterRouter()
        // Multi-line: the field handles the key-down (inserts the newline).
        assertFalse(router.onEnter(Phase.Down, shiftPressed = true, singleLine = false, send))
        // Shift is released a moment before Enter.
        assertFalse(router.onEnter(Phase.Up, shiftPressed = false, singleLine = false, send))
        assertEquals(0, sends)
    }

    @Test
    fun shiftPressedAfterEnterDownStillSends() {
        val router = RaftHardwareEnterRouter()
        assertTrue(router.onEnter(Phase.Down, shiftPressed = false, singleLine = false, send))
        assertTrue(router.onEnter(Phase.Up, shiftPressed = true, singleLine = false, send))
        assertEquals(1, sends)
    }

    @Test
    fun singleLineShiftEnterIsSwallowedWithoutSending() {
        val router = RaftHardwareEnterRouter()
        assertTrue(router.onEnter(Phase.Down, shiftPressed = true, singleLine = true, send))
        assertTrue(router.onEnter(Phase.Up, shiftPressed = true, singleLine = true, send))
        assertEquals(0, sends)
    }

    @Test
    fun keyUpWithoutMatchingKeyDownDoesNothing() {
        val router = RaftHardwareEnterRouter()
        assertFalse(router.onEnter(Phase.Up, shiftPressed = false, singleLine = false, send))
        assertEquals(0, sends)
        // A key-up only completes one key-down.
        router.onEnter(Phase.Down, shiftPressed = false, singleLine = false, send)
        router.onEnter(Phase.Up, shiftPressed = false, singleLine = false, send)
        router.onEnter(Phase.Up, shiftPressed = false, singleLine = false, send)
        assertEquals(1, sends)
    }
}
