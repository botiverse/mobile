package build.raft.app

import build.raft.app.RaftHardwareEnterRouter.Phase
import build.raft.app.data.RaftComposerSendKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftHardwareEnterRouterTest {
    private var sends = 0
    private var taskSends = 0
    private val send: () -> Unit = { sends++ }
    private val sendAsTask: () -> Unit = { taskSends++ }

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

    @Test
    fun hardwareKeyboardEnterModeSendsOnPlainEnterAndModEnterAndNewlinesOnShiftEnter() {
        assertEquals(
            RaftHardwareEnterOutcome.Send,
            raftHardwareEnterOutcome(
                mode = RaftComposerSendKey.Enter,
                hardwareKeyboard = true,
                softEnterSends = false,
                modPressed = false,
                shiftPressed = false,
                altPressed = false,
            ),
        )
        assertEquals(
            RaftHardwareEnterOutcome.Send,
            raftHardwareEnterOutcome(
                mode = RaftComposerSendKey.Enter,
                hardwareKeyboard = true,
                softEnterSends = false,
                modPressed = true,
                shiftPressed = false,
                altPressed = false,
            ),
        )
        assertEquals(
            RaftHardwareEnterOutcome.Newline,
            raftHardwareEnterOutcome(
                mode = RaftComposerSendKey.Enter,
                hardwareKeyboard = true,
                softEnterSends = false,
                modPressed = false,
                shiftPressed = true,
                altPressed = false,
            ),
        )
        assertEquals(
            RaftHardwareEnterOutcome.Newline,
            raftHardwareEnterOutcome(
                mode = RaftComposerSendKey.Enter,
                hardwareKeyboard = true,
                softEnterSends = false,
                modPressed = false,
                shiftPressed = false,
                altPressed = true,
            ),
        )
    }

    @Test
    fun hardwareKeyboardModEnterModeNewlinesOnPlainEnterAndSendsOnModEnter() {
        assertEquals(
            RaftHardwareEnterOutcome.Newline,
            raftHardwareEnterOutcome(
                mode = RaftComposerSendKey.ModEnter,
                hardwareKeyboard = true,
                softEnterSends = true,
                modPressed = false,
                shiftPressed = false,
                altPressed = false,
            ),
        )
        assertEquals(
            RaftHardwareEnterOutcome.Send,
            raftHardwareEnterOutcome(
                mode = RaftComposerSendKey.ModEnter,
                hardwareKeyboard = true,
                softEnterSends = false,
                modPressed = true,
                shiftPressed = false,
                altPressed = false,
            ),
        )
        assertEquals(
            RaftHardwareEnterOutcome.Newline,
            raftHardwareEnterOutcome(
                mode = RaftComposerSendKey.ModEnter,
                hardwareKeyboard = true,
                softEnterSends = false,
                modPressed = false,
                shiftPressed = true,
                altPressed = false,
            ),
        )
    }

    @Test
    fun modShiftEnterSendsAsTaskOnlyWhenAllowed() {
        assertEquals(
            RaftHardwareEnterOutcome.SendAsTask,
            raftHardwareEnterOutcome(
                mode = RaftComposerSendKey.ModEnter,
                hardwareKeyboard = true,
                softEnterSends = false,
                modPressed = true,
                shiftPressed = true,
                altPressed = false,
                canSendAsTask = true,
            ),
        )
        assertEquals(
            RaftHardwareEnterOutcome.SendAsTask,
            raftHardwareEnterOutcome(
                mode = RaftComposerSendKey.Enter,
                hardwareKeyboard = true,
                softEnterSends = false,
                modPressed = true,
                shiftPressed = true,
                altPressed = false,
                canSendAsTask = true,
            ),
        )
        assertEquals(
            RaftHardwareEnterOutcome.Newline,
            raftHardwareEnterOutcome(
                mode = RaftComposerSendKey.ModEnter,
                hardwareKeyboard = true,
                softEnterSends = false,
                modPressed = true,
                shiftPressed = true,
                altPressed = false,
                canSendAsTask = false,
            ),
        )
    }

    @Test
    fun touchOnlyMobileFollowsSoftEnterPreferenceAndStillAllowsModEnter() {
        assertEquals(
            RaftHardwareEnterOutcome.Newline,
            raftHardwareEnterOutcome(
                mode = RaftComposerSendKey.Enter,
                hardwareKeyboard = false,
                softEnterSends = false,
                modPressed = false,
                shiftPressed = false,
                altPressed = false,
            ),
        )
        assertEquals(
            RaftHardwareEnterOutcome.Send,
            raftHardwareEnterOutcome(
                mode = RaftComposerSendKey.Enter,
                hardwareKeyboard = false,
                softEnterSends = true,
                modPressed = false,
                shiftPressed = false,
                altPressed = false,
            ),
        )
        assertEquals(
            RaftHardwareEnterOutcome.Send,
            raftHardwareEnterOutcome(
                mode = RaftComposerSendKey.ModEnter,
                hardwareKeyboard = false,
                softEnterSends = false,
                modPressed = true,
                shiftPressed = false,
                altPressed = false,
            ),
        )
    }

    @Test
    fun routerCarriesSendAndSendAsTaskFromKeyDownToKeyUpEvenIfModifiersReleaseEarly() {
        val router = RaftHardwareEnterRouter()
        // Mod+Enter in ModEnter mode: Ctrl/Cmd released before Enter key-up.
        assertTrue(router.onEnter(Phase.Down, RaftHardwareEnterOutcome.Send, singleLine = false, send, sendAsTask))
        assertEquals(0, sends)
        assertTrue(router.onEnter(Phase.Up, RaftHardwareEnterOutcome.Newline, singleLine = false, send, sendAsTask))
        assertEquals(1, sends)
        assertEquals(0, taskSends)

        // Mod+Shift+Enter sends as task on key-up.
        assertTrue(router.onEnter(Phase.Down, RaftHardwareEnterOutcome.SendAsTask, singleLine = false, send, sendAsTask))
        assertEquals(0, taskSends)
        assertTrue(router.onEnter(Phase.Up, RaftHardwareEnterOutcome.Newline, singleLine = false, send, sendAsTask))
        assertEquals(1, sends)
        assertEquals(1, taskSends)

        // Reset cancels pending send (e.g. IME composition started).
        assertTrue(router.onEnter(Phase.Down, RaftHardwareEnterOutcome.Send, singleLine = false, send, sendAsTask))
        router.reset()
        assertFalse(router.onEnter(Phase.Up, RaftHardwareEnterOutcome.Send, singleLine = false, send, sendAsTask))
        assertEquals(1, sends)
    }

    @Test
    fun effectiveImeSendActionFollowsHardwareKeyboardAndSoftPreference() {
        // Hardware keyboard: Enter mode requests Send IME action; ModEnter keeps newline default.
        assertTrue(
            threadComposerEffectiveImeActionRequested(
                hardwareKeyboard = true,
                composerSendKey = RaftComposerSendKey.Enter,
                composerSendKeyEnabled = false,
            ),
        )
        assertFalse(
            threadComposerEffectiveImeActionRequested(
                hardwareKeyboard = true,
                composerSendKey = RaftComposerSendKey.ModEnter,
                composerSendKeyEnabled = true,
            ),
        )
        // Touch-only mobile: follows soft-keyboard Enter to send toggle.
        assertTrue(
            threadComposerEffectiveImeActionRequested(
                hardwareKeyboard = false,
                composerSendKey = RaftComposerSendKey.ModEnter,
                composerSendKeyEnabled = true,
            ),
        )
        assertFalse(
            threadComposerEffectiveImeActionRequested(
                hardwareKeyboard = false,
                composerSendKey = RaftComposerSendKey.Enter,
                composerSendKeyEnabled = false,
            ),
        )
    }
}
