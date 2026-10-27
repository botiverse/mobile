package build.raft.designsystem.haptics

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

@Composable
actual fun rememberRaftHaptics(): RaftHaptics {
    val view = LocalView.current
    return remember(view) { RaftAndroidHaptics(view) }
}

private class RaftAndroidHaptics(private val view: View) : RaftHaptics {
    override fun longPress() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    override fun threshold() {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CONTEXT_CLICK
        )
    }

    override fun tick() {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        )
    }

    override fun result(success: Boolean) {
        view.performHapticFeedback(
            when {
                Build.VERSION.SDK_INT >= 30 && success -> HapticFeedbackConstants.CONFIRM
                Build.VERSION.SDK_INT >= 30 -> HapticFeedbackConstants.REJECT
                success -> HapticFeedbackConstants.CONTEXT_CLICK
                else -> HapticFeedbackConstants.LONG_PRESS
            }
        )
    }
}
