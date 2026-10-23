package build.raft.app.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The Cupertino pull-to-refresh indicator, filling (and clipped to) [modifier]'s box with the
 * spinner centred in it.
 *
 * - iOS: the system `UIActivityIndicatorView` (`.medium`, default system colour, so it follows
 *   light/dark) hosted through `UIKitView`. While [animating] is false it stands still and shows
 *   only its first [revealedTicks] spokes clockwise from 12 o'clock, as UIRefreshControl does while
 *   the content is pulled down; [animating] runs the system spin.
 * - Android never uses the Cupertino style at runtime; its actual draws the design-system spinner.
 */
@Composable
internal expect fun RaftCupertinoActivityIndicator(
    revealedTicks: Int,
    animating: Boolean,
    modifier: Modifier = Modifier,
)

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
