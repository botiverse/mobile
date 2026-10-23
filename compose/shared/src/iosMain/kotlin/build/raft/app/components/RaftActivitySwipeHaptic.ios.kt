package build.raft.app.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal actual fun rememberRaftActivitySwipeHaptic(): RaftActivitySwipeHaptic = remember { RaftNoActivitySwipeHaptic }

private object RaftNoActivitySwipeHaptic : RaftActivitySwipeHaptic {
    override fun tick() = Unit
}
