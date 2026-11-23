package build.raft.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

/**
 * The window's back event from Esc. Compose Desktop's AWT window sends an Esc that nothing in the
 * window took (not the focused content, not the window's key handlers) as a back event to the
 * window's navigation-event dispatcher (`BackNavigationEventInput`); the back handlers registered
 * there ([build.raft.app.RaftBackHandler], NavDisplay, popups) get it. The Nucleus Tao window has no
 * such input, so the host adds it: [onKeyEvent] goes last in the window's key handling (Main.kt)
 * and [RaftDesktopEscapeBackRegistration] attaches it to the window's dispatcher.
 */
internal class RaftDesktopEscapeBack : NavigationEventInput() {
    /** True when [event] is an Esc press, sent as Back. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || event.key != Key.Escape) return false
        dispatchOnBackCompleted()
        return true
    }
}

/** Attaches [input] to the window's navigation-event dispatcher while the window's content is composed. */
@Composable
internal fun RaftDesktopEscapeBackRegistration(input: RaftDesktopEscapeBack) {
    val owner = LocalNavigationEventDispatcherOwner.current ?: return
    DisposableEffect(owner, input) {
        val dispatcher = owner.navigationEventDispatcher
        dispatcher.addInput(input)
        onDispose { dispatcher.removeInput(input) }
    }
}
