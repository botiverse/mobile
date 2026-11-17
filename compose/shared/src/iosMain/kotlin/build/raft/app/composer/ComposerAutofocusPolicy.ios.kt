package build.raft.app

import androidx.compose.runtime.Composable

// GameController's coalesced keyboard: non-null while any hardware keyboard (a Magic Keyboard,
// a Bluetooth keyboard, the simulator's host keyboard) is connected (RaftIosHardwareKeyboardState).
@Composable
internal actual fun rememberRaftHardwareKeyboardConnected(): Boolean {
    RaftIosHardwareKeyboardState.install()
    return RaftIosHardwareKeyboardState.connected
}
