package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * How long an outgoing message must stay sending before its sending indicator shows. A send
 * that lands sooner never flashes a spinner; a failed send shows its failure row immediately
 * (that row is driven by the failed state, not by this delay).
 */
internal const val ThreadSendingIndicatorDelayMs: Long = 5_000L

/**
 * One run of the delayed sending indicator for a single (message, sending state) pair: hidden
 * at once, then, if [isSending], shown after [delayMs]. Cancelling the run (the state changed)
 * leaves it hidden; the next run starts the wait over.
 */
internal suspend fun runDelayedSendingVisibility(
    isSending: Boolean,
    delayMs: Long,
    setVisible: suspend (Boolean) -> Unit,
) {
    setVisible(false)
    if (!isSending) return
    delay(delayMs)
    setVisible(true)
}

/**
 * Whether the sending indicator of the message [localId] should draw: only after it has been
 * sending for [delayMs] without interruption. Keyed on [localId] and [isSending], so a state
 * change (sent, failed, retried) restarts from hidden. Callers keep the indicator's space
 * reserved while sending, so it appears without a layout jump.
 */
@Composable
internal fun rememberDelayedSendingVisible(
    localId: String,
    isSending: Boolean,
    delayMs: Long = ThreadSendingIndicatorDelayMs,
): Boolean {
    var visible by remember(localId, isSending) { mutableStateOf(false) }
    // Received and sent rows (nearly every row) launch nothing.
    if (isSending) {
        LaunchedEffect(localId, delayMs) {
            runDelayedSendingVisibility(isSending = true, delayMs = delayMs) { visible = it }
        }
    }
    return isSending && visible
}
