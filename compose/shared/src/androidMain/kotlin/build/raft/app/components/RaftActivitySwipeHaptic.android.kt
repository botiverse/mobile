package build.raft.app.components

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** One short tick (~10ms): SEGMENT_TICK on API 34+, else CLOCK_TICK. */
@Composable
internal actual fun rememberRaftActivitySwipeHaptic(): RaftActivitySwipeHaptic {
    val view = LocalView.current
    return remember(view) {
        object : RaftActivitySwipeHaptic {
            override fun tick() {
                val constant =
                    if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
                view.performHapticFeedback(constant)
            }
        }
    }
}
