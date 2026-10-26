package build.raft.app.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** No haptics on desktop. */
@Composable
internal actual fun rememberRaftActivitySwipeHaptic(): RaftActivitySwipeHaptic =
    remember {
        object : RaftActivitySwipeHaptic {
            override fun tick() = Unit
        }
    }
