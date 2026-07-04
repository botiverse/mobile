package build.raft.app

import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.input.pointer.nativeDispatchCapture

internal actual fun Modifier.slockNativeDispatchCapture(): Modifier =
    nativeDispatchCapture()
