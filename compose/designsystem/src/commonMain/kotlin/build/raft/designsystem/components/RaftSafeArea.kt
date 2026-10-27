package build.raft.designsystem.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The window's horizontal safe area on PHYSICAL edges: the sensor housing of an iPhone in
 * landscape, a side status bar, a host tab bar laid out along a side. Zero unless the app's page
 * host provides it (Android, previews and screenshot tests get zero).
 *
 * Apple's rule: backgrounds run edge to edge, content gets out of the way. A full-width bar or
 * row keeps its background (header frame, selection wash, composer surface) on the full width and
 * pads only its content with [raftSafeAreaHorizontalPadding].
 */
@Immutable
data class RaftSafeAreaInsets(val left: Dp = 0.dp, val right: Dp = 0.dp) {
    val isZero: Boolean get() = left <= 0.dp && right <= 0.dp

    companion object {
        val Zero = RaftSafeAreaInsets()
    }
}

/** See [RaftSafeAreaInsets]. Provided by the page host (shared `RaftPageHorizontalSafeAreaShell`). */
val LocalRaftSafeAreaInsets = compositionLocalOf { RaftSafeAreaInsets.Zero }

/**
 * Pads the horizontal safe area ([LocalRaftSafeAreaInsets]) on the given [sides], minus what an
 * enclosing layout has already padded or consumed, and consumes it for the content: it works like
 * `windowInsetsPadding`, so a row inside a header, a list inside an inset card or an embedded page
 * inside a padded pane does not pad it twice. Put it after the background and before the
 * component's own content padding. A no-op where the safe area is zero.
 */
fun Modifier.raftSafeAreaHorizontalPadding(sides: WindowInsetsSides = WindowInsetsSides.Horizontal): Modifier =
    composed {
        val insets = LocalRaftSafeAreaInsets.current
        val windowInsets = remember(insets, sides) { insets.asWindowInsets().only(sides) }
        windowInsetsPadding(windowInsets)
    }

/**
 * Marks the safe area on [sides] as handled without padding: for a container that is away from
 * that edge anyway (the inner side of a split pane, a centred card), so its rows do not pad.
 */
fun Modifier.raftConsumeSafeAreaHorizontal(sides: WindowInsetsSides = WindowInsetsSides.Horizontal): Modifier =
    composed {
        val insets = LocalRaftSafeAreaInsets.current
        val windowInsets = remember(insets, sides) { insets.asWindowInsets().only(sides) }
        consumeWindowInsets(windowInsets)
    }

private fun RaftSafeAreaInsets.asWindowInsets(): WindowInsets =
    WindowInsets(left = left.coerceAtLeast(0.dp), right = right.coerceAtLeast(0.dp))
