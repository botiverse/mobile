package build.raft.app

import androidx.compose.ui.Modifier

/** Kuikly's Android native dispatch capture has no CMP counterpart (one touch system). */
internal actual fun Modifier.raftNativeDispatchCapture(): Modifier = this
