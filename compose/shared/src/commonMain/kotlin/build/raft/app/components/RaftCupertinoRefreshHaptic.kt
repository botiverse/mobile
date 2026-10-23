package build.raft.app.components

import androidx.compose.runtime.Composable

/** The tap UIRefreshControl plays when a pull crosses its threshold ([RaftCupertinoRefreshHaptic]); a no-op off iOS. */
@Composable
internal expect fun rememberRaftCupertinoRefreshHaptic(): RaftCupertinoRefreshHaptic

/**
 * UIRefreshControl's commit haptic. The system control is observed (iOS 10 onwards, MacStories,
 * "System Haptics on the iPhone 7": "you will get a subtle tap when you have pulled down far
 * enough to trigger the refresh") to tap once as the pull crosses the threshold; Apple does not
 * document the generator, so iOS plays a light `UIImpactFeedbackGenerator` impact, the subtlest
 * impact style.
 */
internal interface RaftCupertinoRefreshHaptic {
    /** Wakes the Taptic Engine as a pull starts so the tap lands on the threshold frame. */
    fun prepare()

    fun trigger()
}
