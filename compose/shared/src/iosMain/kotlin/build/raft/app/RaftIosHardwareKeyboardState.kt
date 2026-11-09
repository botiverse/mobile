package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.GameController.GCKeyboard
import platform.GameController.GCKeyboardDidConnectNotification
import platform.GameController.GCKeyboardDidDisconnectNotification

/**
 * Whether a hardware keyboard is connected, for the whole app: GameController's coalesced keyboard
 * (non-null while any Magic Keyboard, Smart Keyboard, Bluetooth keyboard or the simulator's host
 * keyboard is attached), kept current by its connect/disconnect notifications. Snapshot state, so
 * a composable that reads it (directly, or through the "Enter sends" default) recomposes when a
 * keyboard comes or goes. The GameController names are the ones ComposerAutofocusPolicy.ios.kt
 * already compiled with.
 */
internal object RaftIosHardwareKeyboardState {
    var connected: Boolean by mutableStateOf(GCKeyboard.coalescedKeyboard != null)
        private set

    private var installed = false

    /** Starts following connects and disconnects; idempotent. Main thread (UIKit hooks, composition). */
    fun install() {
        if (installed) return
        installed = true
        val center = NSNotificationCenter.defaultCenter
        // The observers live as long as the process: they are never removed.
        listOf(GCKeyboardDidConnectNotification, GCKeyboardDidDisconnectNotification).forEach { name ->
            center.addObserverForName(name, null, NSOperationQueue.mainQueue) { _ -> refresh() }
        }
        // GameController can find a keyboard that was attached at launch after the first read.
        refresh()
    }

    private fun refresh() {
        val next = GCKeyboard.coalescedKeyboard != null
        if (connected != next) connected = next
    }
}
