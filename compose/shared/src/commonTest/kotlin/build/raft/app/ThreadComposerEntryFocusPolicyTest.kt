package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadComposerEntryFocusPolicyTest {
    @Test
    fun threadRouteMountClearsInheritedNativeFocus() {
        assertTrue(
            shouldResetThreadComposerInheritedFocus(
                startInReadingMode = true,
                focusRequestNonce = 0,
                handledFocusRequestNonce = 0
            )
        )
    }

    @Test
    fun explicitFocusRequestAfterMountWinsReadingModeFence() {
        assertFalse(
            shouldResetThreadComposerInheritedFocus(
                startInReadingMode = true,
                focusRequestNonce = 1,
                handledFocusRequestNonce = 0
            )
        )
    }

    @Test
    fun standaloneComposerDoesNotClearItsFocus() {
        assertFalse(
            shouldResetThreadComposerInheritedFocus(
                startInReadingMode = false,
                focusRequestNonce = 0,
                handledFocusRequestNonce = 0
            )
        )
    }

    @Test
    fun inheritedFocusResetClearsLogicalAndNativeFocusBeforeHidingKeyboard() {
        val steps = mutableListOf<String>()

        resetInheritedThreadComposerFocus(
            resetFocusedState = { steps += "focused=false" },
            clearFocus = { steps += "clear-focus-force" },
            hideKeyboard = { steps += "hide-keyboard" }
        )

        assertEquals(
            listOf("focused=false", "clear-focus-force", "hide-keyboard"),
            steps
        )
    }
}
