package build.raft.app

import androidx.compose.runtime.Composable

// The Compose desktop app is driven by a keyboard and a mouse or trackpad.
@Composable
internal actual fun rememberRaftHardwareKeyboardConnected(): Boolean = true
