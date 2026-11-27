package build.raft.designsystem.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityReduceMotionStatusDidChangeNotification

private object RaftIosReducedMotionObserver {
    private val _reduced = MutableStateFlow(UIAccessibilityIsReduceMotionEnabled())

    private var installed = false

    fun ensureObserved(): StateFlow<Boolean> {
        if (!installed) {
            installed = true
            NSNotificationCenter.defaultCenter.addObserverForName(
                name = UIAccessibilityReduceMotionStatusDidChangeNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { _ ->
                _reduced.value = UIAccessibilityIsReduceMotionEnabled()
            }
            // Re-read after registration to close any gap between init and observer install.
            _reduced.value = UIAccessibilityIsReduceMotionEnabled()
        }
        return _reduced
    }
}

@Composable
actual fun rememberRaftReducedMotion(): Boolean {
    val flow = remember { RaftIosReducedMotionObserver.ensureObserved() }
    return flow.collectAsState().value
}
