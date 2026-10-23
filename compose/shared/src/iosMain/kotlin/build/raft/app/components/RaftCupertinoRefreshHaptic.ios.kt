package build.raft.app.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle

@Composable
internal actual fun rememberRaftCupertinoRefreshHaptic(): RaftCupertinoRefreshHaptic =
    remember { RaftIosCupertinoRefreshHaptic() }

private class RaftIosCupertinoRefreshHaptic : RaftCupertinoRefreshHaptic {
    private val generator = UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)

    override fun prepare() {
        generator.prepare()
    }

    override fun trigger() {
        generator.impactOccurred()
    }
}
