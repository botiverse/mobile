package build.raft.app

import build.raft.designsystem.gesture.RaftBlockBackSwipe
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import build.raft.app.compat.ui.BackHandler
import build.raft.app.compat.ui.raftBackHandlerEnabled

@Composable
internal fun RaftBackHandler(
    enabled: Boolean,
    label: String? = null,
    onLifecycleLog: ((String) -> Unit)? = null,
    /**
     * An enabled handler marks transient UI (overlay, dialog, menu, selection mode) that Back
     * dismisses first; while it is enabled the host's full-page back swipe must not pop the page
     * underneath, so it is blocked. Page-level handlers that only redirect the page's own Back
     * (web view history, closing the page) pass false and keep the swipe.
     */
    blocksBackSwipe: Boolean = true,
    onBack: () -> Unit
) {
    // Inactive kept-alive pages (hidden shell tabs) must not register: see LocalRaftPageActive.
    if (!raftBackHandlerEnabled(enabled)) return
    RaftBlockBackSwipe(enabled = blocksBackSwipe)

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
