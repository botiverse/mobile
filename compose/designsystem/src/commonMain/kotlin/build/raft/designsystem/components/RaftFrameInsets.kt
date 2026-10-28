package build.raft.designsystem.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The parts of the window a host keeps for itself (raft-ui `--raft-frame-inset-top`, `-start`,
 * `-end`; design/patterns/page-structure.md "Frame insets"): an iPhone Duo's system column, a
 * desktop window's own controls. Popups keep clear of them: a [RaftDropdownMenu] stays
 * [RaftFrameInsetsDefaults.PopupGap] inside each one, a [RaftTooltip] that would open into one
 * opens the other way.
 *
 * Physical sides, not start / end: the host knows which edge its column is on, and an iPhone Duo's
 * column does not move with the language (raft-design #859). Zero everywhere (the default) changes
 * nothing.
 */
@Immutable
data class RaftFrameInsets(val top: Dp = 0.dp, val left: Dp = 0.dp, val right: Dp = 0.dp) {
    val isZero: Boolean get() = top <= 0.dp && left <= 0.dp && right <= 0.dp

    companion object {
        val Zero = RaftFrameInsets()
    }
}

object RaftFrameInsetsDefaults {
    /** The room a menu keeps from an inset (raft-ui `POPUP_FRAME_GAP`). */
    val PopupGap: Dp = 8.dp
}

/** See [RaftFrameInsets]. Provided by the host shell; none on a phone without a system column. */
val LocalRaftFrameInsets = staticCompositionLocalOf { RaftFrameInsets.Zero }
