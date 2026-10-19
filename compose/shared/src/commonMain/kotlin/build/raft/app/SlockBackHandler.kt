package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import build.raft.app.compat.ui.BackHandler

@Composable
internal fun SlockBackHandler(
    enabled: Boolean,
    label: String? = null,
    onLifecycleLog: ((String) -> Unit)? = null,
    onBack: () -> Unit
) {
    if (!enabled) return

    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnLifecycleLog by rememberUpdatedState(onLifecycleLog)
    DisposableEffect(label) {
        currentOnLifecycleLog?.invoke("registered label=${label ?: "unknown"}")
        onDispose { currentOnLifecycleLog?.invoke("disposed label=${label ?: "unknown"}") }
    }
    BackHandler {
        currentOnLifecycleLog?.invoke("invoked label=${label ?: "unknown"}")
        currentOnBack()
    }
}
