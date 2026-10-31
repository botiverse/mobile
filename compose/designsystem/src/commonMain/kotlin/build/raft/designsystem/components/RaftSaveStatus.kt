package build.raft.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftStrings
import build.raft.designsystem.theme.RaftTheme
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What a [RaftSaveStatus] line says. */
@Immutable
sealed interface RaftSaveStatusState {
    /** Nothing has happened, or the change is still saving: the line is empty and keeps its height. */
    data object Idle : RaftSaveStatusState

    /** The change is stored: a check and "Saved" ([build.raft.designsystem.theme.RaftStrings.saved]). */
    data object Saved : RaftSaveStatusState

    /** What failed, in `danger-strong`, until the next change. Put the control back to its old value. */
    data class Failed(val message: String) : RaftSaveStatusState
}

object RaftSaveStatusDefaults {
    /** How long "Saved" stays before the line empties again (raft-ui SaveStatus). */
    val SavedDuration: Duration = 2.seconds

    /** The gap between the control and the line. [RaftField] and [RaftListRow] keep it themselves. */
    val Gap = 4.dp
}

/**
 * raft-ui 0.54.0 SaveStatus (wip; raft-design #906, stdrc 2026-10-30): the line under a setting
 * that applies at once, a select, a segmented control or a settings row with a switch. It is
 * always there, one `text-footnote` line (18dp) tall, so "Saved" coming and going never resizes
 * the card or moves a switch centred on its row.
 *
 * - [RaftSaveStatusState.Idle]: empty. Saving shows nothing, since the control already shows the new value.
 * - [RaftSaveStatusState.Saved]: Lucide `circle-check` at 12dp, 4dp before "Saved", `foreground-muted`;
 *   for [RaftSaveStatusDefaults.SavedDuration], then idle again ([rememberRaftSaveStatus] times it).
 * - [RaftSaveStatusState.Failed]: the message in `danger-strong`, wrapping (the setting grows rather
 *   than cutting it off). Write what didn't happen and what to do: "Couldn't mute this server. Try again."
 *
 * It sits 4dp under the control. Pass the state to [RaftField] `saveStatus` (after the control,
 * before the description) or [RaftListRow] `saveStatus` (after the description) and they place it;
 * anywhere else put it [RaftSaveStatusDefaults.Gap] under the control yourself.
 *
 * "Saved" is announced politely and a failure assertively, as raft-ui's `role="status"` and
 * `role="alert"`; the line stays in the tree while empty so the words are announced when they arrive.
 */
@Composable
fun RaftSaveStatus(
    state: RaftSaveStatusState,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val style = RaftTheme.typography.footnote
    val line = with(LocalDensity.current) { style.lineHeight.toDp() }
    Box(modifier.heightIn(min = line), contentAlignment = Alignment.CenterStart) {
        when (state) {
            is RaftSaveStatusState.Failed ->
                RaftText(
                    state.message,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    style = style,
                    color = colors.dangerStrong,
                )
            else ->
                Row(
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state == RaftSaveStatusState.Saved) {
                        RaftGlyphIcon(RaftGlyph.CircleCheck, size = 12.dp, color = colors.foregroundMuted)
                        RaftText(LocalRaftStrings.current.saved, style = style, color = colors.foregroundMuted, maxLines = 1)
                    }
                }
        }
    }
}

/**
 * Holds a [RaftSaveStatus]'s state for one setting: call [saving] when a change starts (it empties
 * the line, so each "Saved" is announced again), then [saved] or [failed]. "Saved" clears itself
 * after [savedDuration]; a failure stays until the next [saving].
 */
@Stable
class RaftSaveStatusHolder internal constructor(
    private val scope: CoroutineScope,
    private val savedDuration: Duration,
) {
    var state: RaftSaveStatusState by mutableStateOf(RaftSaveStatusState.Idle)
        private set

    private var clear: Job? = null

    fun saving() {
        clear?.cancel()
        state = RaftSaveStatusState.Idle
    }

    fun saved() {
        clear?.cancel()
        state = RaftSaveStatusState.Saved
        clear =
            scope.launch {
                delay(savedDuration)
                state = RaftSaveStatusState.Idle
            }
    }

    fun failed(message: String) {
        clear?.cancel()
        state = RaftSaveStatusState.Failed(message)
    }
}

@Composable
fun rememberRaftSaveStatus(savedDuration: Duration = RaftSaveStatusDefaults.SavedDuration): RaftSaveStatusHolder {
    val scope = rememberCoroutineScope()
    return remember(scope, savedDuration) { RaftSaveStatusHolder(scope, savedDuration) }
}
