package build.raft.app.components

import androidx.compose.runtime.Composable

/**
 * The tick when an Activity row's swipe layer turns solid (raft-design #775): a single short tick on Android,
 * played once per outward crossing of the release threshold. A no-op elsewhere (iOS has no spec'd haptic here).
 */
internal interface RaftActivitySwipeHaptic {
    fun tick()
}

@Composable
internal expect fun rememberRaftActivitySwipeHaptic(): RaftActivitySwipeHaptic
