package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Focus ownership of the Search page field. The phone Search tab is kept alive (composed, not
 * placed) while another tab is selected or a page is pushed over the shell, and a focused field in
 * it kept the IME: returning to Home from a channel, or opening Members, raised the search keyboard
 * and hid the bottom bar (stdrc 2026-10-21 nav regressions).
 *
 * - [fieldModifier] makes the field unfocusable while the page is not shown and tracks its focus.
 * - [release] drops the field's focus and the IME; the page calls it before opening a result, so
 *   Back to the results restores them without focusing the field.
 */
internal class SharedHomeSearchInputFocus(
    val fieldModifier: Modifier,
    val release: () -> Unit,
)

/** Leaving the page (tab switch, pushed page) releases a focused field. */
internal fun homeSearchInputReleasesOnInactive(inputActive: Boolean, fieldFocused: Boolean): Boolean =
    !inputActive && fieldFocused

/** Dismissing the IME (system Back, the IME's hide key) also ends the field's focus. */
internal fun homeSearchInputReleasesOnImeHidden(
    imeWasVisible: Boolean,
    imeVisible: Boolean,
    fieldFocused: Boolean,
): Boolean = imeWasVisible && !imeVisible && fieldFocused

@Composable
internal fun rememberSharedHomeSearchInputFocus(
    focusRequester: FocusRequester,
    inputActive: Boolean,
    focusInputRequest: Int,
): SharedHomeSearchInputFocus {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusedState = remember { mutableStateOf(false) }
    val release: () -> Unit =
        remember(focusManager, keyboardController) {
            {
                if (focusedState.value) {
                    keyboardController?.hide()
                    focusManager.clearFocus(force = true)
                }
            }
        }
    LaunchedEffect(inputActive) {
        if (homeSearchInputReleasesOnInactive(inputActive, focusedState.value)) release()
    }
    val imeVisible = raftImeVisible()
    var imeWasVisible by remember { mutableStateOf(imeVisible) }
    LaunchedEffect(imeVisible) {
        if (homeSearchInputReleasesOnImeHidden(imeWasVisible, imeVisible, focusedState.value)) {
            focusManager.clearFocus(force = true)
        }
        imeWasVisible = imeVisible
    }
    // Re-tapping the selected Search tab focuses the field; a request made while the page is
    // hidden is dropped, never replayed when it is shown again.
    val focusRequestFence = remember { AppShellScrollToTopRequestFence(focusInputRequest) }
    LaunchedEffect(focusInputRequest) {
        if (focusRequestFence.consume(focusInputRequest) && inputActive) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }
    val fieldModifier =
        Modifier
            .focusProperties { canFocus = inputActive }
            .onFocusChanged { focusedState.value = it.hasFocus }
    return SharedHomeSearchInputFocus(fieldModifier = fieldModifier, release = release)
}
