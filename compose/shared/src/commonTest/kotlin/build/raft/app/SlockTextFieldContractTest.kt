package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockTextFieldContractTest {
    @Test
    fun oneLineFieldUsesNativeSingleLineEditor() {
        assertTrue(slockTextFieldSingleLine(maxLines = 1))
    }

    @Test
    fun multilineFieldKeepsNativeTextAreaEditor() {
        assertFalse(slockTextFieldSingleLine(maxLines = 2))
        assertFalse(slockTextFieldSingleLine(maxLines = Int.MAX_VALUE))
    }

    @Test
    fun autoFocusRetriesWhileTheNativeEditorIsStillUnfocused() {
        assertTrue(
            slockTextFieldAutoFocusShouldRetry(
                autoFocus = true,
                isFocused = false,
                completedAttempts = 0
            )
        )
        assertTrue(
            slockTextFieldAutoFocusShouldRetry(
                autoFocus = true,
                isFocused = false,
                completedAttempts = 2
            )
        )
    }

    @Test
    fun autoFocusStopsAfterFocusOrTheBoundedAttemptBudget() {
        assertFalse(
            slockTextFieldAutoFocusShouldRetry(
                autoFocus = true,
                isFocused = true,
                completedAttempts = 1
            )
        )
        assertFalse(
            slockTextFieldAutoFocusShouldRetry(
                autoFocus = true,
                isFocused = false,
                completedAttempts = 3
            )
        )
        assertFalse(
            slockTextFieldAutoFocusShouldRetry(
                autoFocus = false,
                isFocused = false,
                completedAttempts = 0
            )
        )
    }
}
