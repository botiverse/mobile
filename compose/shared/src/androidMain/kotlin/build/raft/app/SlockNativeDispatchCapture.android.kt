package build.raft.app

import androidx.compose.ui.Modifier
import build.raft.app.compat.ui.nativeDispatchCapture

internal actual fun Modifier.slockNativeDispatchCapture(): Modifier =
    nativeDispatchCapture()
