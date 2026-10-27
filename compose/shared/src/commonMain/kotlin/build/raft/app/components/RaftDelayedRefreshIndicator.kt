package build.raft.app.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** A silent refresh that lands within this time never shows the header refresh indicator. */
internal const val RaftRefreshIndicatorShowDelayMillis = 300L

/** Once shown, the header refresh indicator stays at least this long, so it never just flashes. */
internal const val RaftRefreshIndicatorMinVisibleMillis = 500L

/**
 * Indicator visibility for a stream of "work in flight" flags: it shows only once the work has
 * run [showDelayMillis] without a break, and once shown it stays at least [minVisibleMillis]
 * (work that resumes within that hold keeps it up without a new delay). Starts hidden.
 */
internal fun Flow<Boolean>.raftDelayedIndicatorVisibility(
    showDelayMillis: Long = RaftRefreshIndicatorShowDelayMillis,
    minVisibleMillis: Long = RaftRefreshIndicatorMinVisibleMillis,
    timeSource: TimeSource = TimeSource.Monotonic,
): Flow<Boolean> =
    channelFlow {
        var shownSince: TimeMark? = null
        send(false)
        distinctUntilChanged().collectLatest { active ->
            val since = shownSince
            if (active) {
                if (since != null) return@collectLatest
                // A newer flag (the work ended) cancels this wait: nothing showed.
                delay(showDelayMillis)
                shownSince = timeSource.markNow()
                send(true)
            } else if (since != null) {
                val remaining = minVisibleMillis - since.elapsedNow().inWholeMilliseconds
                // Work that resumes cancels this hold and finds the indicator still up.
                if (remaining > 0L) delay(remaining)
                shownSince = null
                send(false)
            }
        }
    }

/** [active] as a header refresh indicator shows it: [raftDelayedIndicatorVisibility]. */
@Composable
internal fun rememberRaftDelayedIndicatorVisible(active: Boolean): Boolean {
    val latestActive by rememberUpdatedState(active)
    val visible by remember { snapshotFlow { latestActive }.raftDelayedIndicatorVisibility() }
        .collectAsState(initial = false)
    return visible
}
