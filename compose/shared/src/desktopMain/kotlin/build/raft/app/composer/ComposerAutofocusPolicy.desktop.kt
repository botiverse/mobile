package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.PlatformTextInputSession

// The Compose desktop app is driven by a keyboard and a mouse or trackpad.
@Composable
internal actual fun rememberRaftHardwareKeyboardConnected(): Boolean = true

@Composable
internal actual fun rememberRaftPlatformHostWindowToken(): Any? = null

internal actual fun PlatformTextInputSession.belongsToRaftPlatformHostWindow(hostToken: Any?): Boolean = true
