package build.raft.designsystem.haptics

import androidx.compose.runtime.Composable

@Composable
actual fun rememberRaftHaptics(): RaftHaptics = RaftNoHaptics

private object RaftNoHaptics : RaftHaptics {
    override fun longPress() {}

    override fun threshold() {}

    override fun tick() {}

    override fun result(success: Boolean) {}
}
