package build.raft.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/** What a [RaftSaveStatus] line says. */
@Immutable
sealed interface RaftSaveStatusState {
    /**
     * Nothing to say: nothing has happened, the change is still saving, or it is stored. The line
     * is gone and takes no room; the control showing its new value is the confirmation.
     */
    data object Idle : RaftSaveStatusState

    /** What failed, in `danger-strong`, until the next change. Put the control back to its old value. */
    data class Failed(val message: String) : RaftSaveStatusState
}

object RaftSaveStatusDefaults {
    /** The gap between the control and a failure. [RaftField] and [RaftListRow] keep it themselves. */
    val Gap = 4.dp
}

/**
 * raft-ui 0.84.0 SaveStatus (raft-design #1137; cindyz 2026-11-10): the line under a setting that
 * applies at once, a select, a segmented control or a settings row with a switch. It shows only a
 * failure: a setting that applies at once needs no "Saved", since the control showing its new value
 * is the confirmation (as in the phone's own settings).
 *
 * - [RaftSaveStatusState.Idle]: nothing at all, no reserved line, so a setting with it is as tall
 *   as one without it.
 * - [RaftSaveStatusState.Failed]: the message in `danger-strong` `text-footnote`, wrapping (the
 *   setting grows by it rather than cutting it off). Write what didn't happen and what to do:
 *   "Couldn't mute this server. Try again."
 *
 * It sits 4dp under the control. Pass the state to [RaftField] `saveStatus` (after the control,
 * before the description) or [RaftListRow] `saveStatus` (after the description) and they place it;
 * anywhere else put it [RaftSaveStatusDefaults.Gap] under the control yourself (in a column spaced
 * by the gap, an idle line leaves no gap either). A failure is announced assertively, as raft-ui's
 * `role="alert"`.
 */
@Composable
fun RaftSaveStatus(
    state: RaftSaveStatusState,
    modifier: Modifier = Modifier,
) {
    if (state !is RaftSaveStatusState.Failed) return
    RaftText(
        state.message,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        style = RaftTheme.typography.footnote,
        color = RaftTheme.colors.dangerStrong,
    )
}

/**
 * Holds a [RaftSaveStatus]'s state for one setting: call [saving] when a change starts, then
 * [saved] or [failed]. Saving and saved both leave the line empty; a failure stays until the next
 * [saving] (or [saved]).
 */
@Stable
class RaftSaveStatusHolder internal constructor() {
    var state: RaftSaveStatusState by mutableStateOf(RaftSaveStatusState.Idle)
        private set

    fun saving() {
        state = RaftSaveStatusState.Idle
    }

    /** The change is stored: nothing to say, the control already shows it. */
    fun saved() {
        state = RaftSaveStatusState.Idle
    }

    fun failed(message: String) {
        state = RaftSaveStatusState.Failed(message)
    }
}

@Composable
fun rememberRaftSaveStatus(): RaftSaveStatusHolder = remember { RaftSaveStatusHolder() }
