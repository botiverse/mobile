package build.raft.app.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Android never renders the Cupertino style at runtime. */
@Composable
internal actual fun rememberRaftCupertinoRefreshHaptic(): RaftCupertinoRefreshHaptic =
    remember { RaftNoCupertinoRefreshHaptic }

private object RaftNoCupertinoRefreshHaptic : RaftCupertinoRefreshHaptic {
    override fun prepare() = Unit

    override fun trigger() = Unit
}
