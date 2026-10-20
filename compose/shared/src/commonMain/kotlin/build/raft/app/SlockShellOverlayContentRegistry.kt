package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Size
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Process-local bridge for shell overlays whose visual content cannot be reduced to the
 * serializable menu contract (for example the message menu's reaction atlas and dividers).
 *
 * The requesting child page registers the actual composable before posting the normal shell
 * request. The app-shell composition consumes it and renders it in its window-level surface;
 * the event payload remains the lifecycle/anchor contract and never carries a bitmap or lambda.
 */
internal data class SlockShellOverlayContentEntry(
    val requestId: String,
    val contentSize: Size,
    val viewportGap: Float,
    val horizontalOffset: Float,
    val verticalOffset: Float,
    val content: @Composable (onSelected: (String) -> Unit) -> Unit,
    val onSelected: (String) -> Unit,
)

internal object SlockShellOverlayContentRegistry {
    private val lock = SynchronizedObject()
    private val entries = mutableMapOf<String, SlockShellOverlayContentEntry>()

    fun register(entry: SlockShellOverlayContentEntry) {
        synchronized(lock) { entries[entry.requestId] = entry }
    }

    fun take(requestId: String): SlockShellOverlayContentEntry? = synchronized(lock) { entries.remove(requestId) }

    fun remove(requestId: String) {
        synchronized(lock) { entries.remove(requestId) }
    }
}
