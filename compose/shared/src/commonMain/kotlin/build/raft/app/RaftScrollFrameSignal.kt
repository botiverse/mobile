package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Scroll start/stop edges for host frame probes (Android `RaftScrollFrameProbe` measures vsync
 * cadence only while a list is scrolling and logs one line per scroll session). Null sink = no-op,
 * so iOS / desktop pay one snapshot read per scroll edge and nothing per frame.
 *
 * Main thread only: the sink is invoked from the composition's coroutine dispatcher.
 */
object RaftScrollFrameSignal {
    var sink: ((list: String, scrolling: Boolean) -> Unit)? = null
}

/** Reports [isScrolling] edges of one list to [RaftScrollFrameSignal]; [list] names it in the log. */
@Composable
internal fun RaftScrollFrameSignalEffect(list: String, isScrolling: () -> Boolean) {
    LaunchedEffect(list) {
        try {
            snapshotFlow(isScrolling)
                .distinctUntilChanged()
                .collect { scrolling -> RaftScrollFrameSignal.sink?.invoke(list, scrolling) }
        } finally {
            // The list left composition mid-fling: close its session instead of leaving it open.
            RaftScrollFrameSignal.sink?.invoke(list, false)
        }
    }
}
