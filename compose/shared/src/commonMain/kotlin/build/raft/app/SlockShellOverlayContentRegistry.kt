package build.raft.app

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.ui.geometry.Size

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
    private val entries = mutableMapOf<String, SlockShellOverlayContentEntry>()

    fun register(entry: SlockShellOverlayContentEntry) {
        entries[entry.requestId] = entry
    }

    fun take(requestId: String): SlockShellOverlayContentEntry? = entries.remove(requestId)

    fun remove(requestId: String) {
        entries.remove(requestId)
    }
}
