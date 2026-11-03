package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.GameController.GCKeyboard
import platform.GameController.GCKeyboardDidConnectNotification
import platform.GameController.GCKeyboardDidDisconnectNotification

// GameController's coalesced keyboard: non-null while any hardware keyboard (a Magic Keyboard,
// a Bluetooth keyboard, the simulator's host keyboard) is connected.
@Composable
internal actual fun rememberRaftHardwareKeyboardConnected(): Boolean {
    var connected by remember { mutableStateOf(GCKeyboard.coalescedKeyboard != null) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val observers =
            listOf(GCKeyboardDidConnectNotification, GCKeyboardDidDisconnectNotification).map { name ->
                center.addObserverForName(name, null, NSOperationQueue.mainQueue) { _ ->
                    connected = GCKeyboard.coalescedKeyboard != null
                }
            }
        connected = GCKeyboard.coalescedKeyboard != null
        onDispose { observers.forEach { center.removeObserver(it) } }
    }
    return connected
}
