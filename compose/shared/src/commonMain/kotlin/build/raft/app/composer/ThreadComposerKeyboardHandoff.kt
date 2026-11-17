package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The composer's keyboard across a trip to the image editor (dm #119).
 *
 * With the keyboard up, adding an image brought the keyboard back up over the image editor: the
 * composer kept its focus while the picker ran, so coming back to the window raised the keyboard
 * for it again, and the editor page then opened over the conversation with the keyboard still up.
 * Now the keyboard goes down before the picker (or the editor) opens, and comes back when the user
 * is back at the composer: at once when nothing opened over the conversation (the picker was
 * cancelled, the files went straight into the tray), or when the conversation page is resumed
 * again after the page that opened over it (the editor, a preview) closed.
 *
 * Only an open keyboard takes the trip; with the keyboard down nothing changes, and there is no
 * software keyboard on desktop. Single main-thread owner, like the composer's other state.
 */
internal class ThreadComposerKeyboardHandoff(
    private val putKeyboardDown: () -> Unit,
    private val bringKeyboardBack: () -> Unit,
    private val pageResumed: () -> Boolean,
) {
    internal enum class Phase {
        /** No trip. */
        Idle,

        /** The keyboard went down; the picker or the open step has not finished. */
        Leaving,

        /** A page opened over the conversation; the conversation page has not gone under it yet. */
        Covered,

        /** The conversation page is under the page that opened over it. */
        Away,
    }

    var phase: Phase = Phase.Idle
        private set

    /** About to leave the composer for the picker or the editor: an open keyboard goes down first. */
    fun leave(keyboardOpen: Boolean) {
        if (!keyboardOpen) return
        phase = Phase.Leaving
        putKeyboardDown()
    }

    /** The editor (or a preview) opened over the conversation: the keyboard waits for its close. */
    fun pageOpened() {
        if (phase != Phase.Leaving) return
        phase = if (pageResumed()) Phase.Covered else Phase.Away
    }

    /**
     * The picker or the open step finished. When nothing opened over the conversation the user is
     * still at the composer, and the keyboard comes back now.
     */
    fun settle() {
        if (phase != Phase.Leaving) return
        phase = Phase.Idle
        bringKeyboardBack()
    }

    /**
     * The conversation page's lifecycle moved. Leaving ignores it: the picker is its own window on
     * Android and pauses the page on its own. Back at resumed after the covering page: the keyboard
     * comes back.
     */
    fun onPageLifecycle(resumed: Boolean) {
        when {
            phase == Phase.Covered && !resumed -> phase = Phase.Away
            phase == Phase.Away && resumed -> {
                phase = Phase.Idle
                bringKeyboardBack()
            }
        }
    }
}

/**
 * The conversation page's [ThreadComposerKeyboardHandoff]: puts the keyboard down by clearing the
 * focus and hiding the IME (the composer's own focus would raise it again on the way back), and
 * follows the page's lifecycle for the return. [onBringBack] focuses the composer and shows the
 * keyboard (the draft store's RequestFocus).
 */
@Composable
internal fun rememberThreadComposerKeyboardHandoff(key: Any?, onBringBack: () -> Unit): ThreadComposerKeyboardHandoff {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentFocusManager by rememberUpdatedState(focusManager)
    val currentKeyboardController by rememberUpdatedState(keyboardController)
    val currentLifecycle by rememberUpdatedState(lifecycle)
    val currentOnBringBack by rememberUpdatedState(onBringBack)
    val handoff =
        remember(key) {
            ThreadComposerKeyboardHandoff(
                putKeyboardDown = {
                    currentFocusManager.clearFocus(force = true)
                    currentKeyboardController?.hide()
                },
                bringKeyboardBack = { currentOnBringBack() },
                pageResumed = { currentLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) },
            )
        }
    DisposableEffect(handoff, lifecycle) {
        val observer =
            LifecycleEventObserver { _, _ ->
                handoff.onPageLifecycle(resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return handoff
}
