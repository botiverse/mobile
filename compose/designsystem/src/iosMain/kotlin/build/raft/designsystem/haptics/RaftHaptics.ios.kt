package build.raft.designsystem.haptics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UISelectionFeedbackGenerator

@Composable
actual fun rememberRaftHaptics(): RaftHaptics = remember { RaftIosHaptics() }

private class RaftIosHaptics : RaftHaptics {
    private val medium = UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium)
    private val light = UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
    private val selection = UISelectionFeedbackGenerator()
    private val notification = UINotificationFeedbackGenerator()

    override fun longPress() {
        medium.impactOccurred()
    }

    override fun threshold() {
        light.impactOccurred()
    }

    override fun tick() {
        selection.selectionChanged()
    }

    override fun result(success: Boolean) {
        notification.notificationOccurred(
            if (success) UINotificationFeedbackType.UINotificationFeedbackTypeSuccess else UINotificationFeedbackType.UINotificationFeedbackTypeError
        )
    }

    override fun prepare() {
        light.prepare()
        selection.prepare()
    }
}
