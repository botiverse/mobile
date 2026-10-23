package build.raft.app.components

import androidx.compose.runtime.Composable

/**
 * The tick when an Activity row's pull crosses into full Done (raft-design #785: past 60% of the row, Done
 * covers the buttons): a single short tick on Android, played once per outward crossing. A no-op elsewhere
 * (iOS has no spec'd haptic here).
 */
internal interface RaftActivitySwipeHaptic {
    fun tick()
}

@Composable
internal expect fun rememberRaftActivitySwipeHaptic(): RaftActivitySwipeHaptic
