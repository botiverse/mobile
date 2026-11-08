package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An iPad with a hardware keyboard takes keyboard behaviour; touch-only devices keep the iOS one. */
class RaftIosHardwareKeyboardTest {
    @Test
    fun enterSendsByDefaultWhileAHardwareKeyboardIsConnected() {
        assertTrue(raftIosComposerEnterSendsByDefault(onMac = false, hardwareKeyboard = true))
        assertTrue(raftIosComposerEnterSendsByDefault(onMac = true, hardwareKeyboard = true))
        assertFalse(raftIosComposerEnterSendsByDefault(onMac = false, hardwareKeyboard = false), "touch only")
    }

    @Test
    fun onlyTheShortcutsBarOfAHardwareKeyboardIsIgnored() {
        assertTrue(raftIosIgnoresKeyboardFrame(hardwareKeyboard = true, overlapPoints = 55.0))
        assertTrue(raftIosIgnoresKeyboardFrame(hardwareKeyboard = true, overlapPoints = 99.0))
        // A real software keyboard, with or without a hardware keyboard attached.
        assertFalse(raftIosIgnoresKeyboardFrame(hardwareKeyboard = true, overlapPoints = 100.0))
        assertFalse(raftIosIgnoresKeyboardFrame(hardwareKeyboard = true, overlapPoints = 320.0))
        // Touch only: every frame lifts, the small ones too (iPhone, iPad without a keyboard).
        assertFalse(raftIosIgnoresKeyboardFrame(hardwareKeyboard = false, overlapPoints = 55.0))
        assertFalse(raftIosIgnoresKeyboardFrame(hardwareKeyboard = false, overlapPoints = 320.0))
        // No keyboard frame: nothing to ignore.
        assertFalse(raftIosIgnoresKeyboardFrame(hardwareKeyboard = true, overlapPoints = 0.0))
    }
}
