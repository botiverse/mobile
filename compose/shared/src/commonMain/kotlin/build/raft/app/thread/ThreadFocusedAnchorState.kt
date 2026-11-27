package build.raft.app.thread

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The message list's focused-message anchoring bookkeeping: one per thread open, remembered by
 * `ThreadMessagesListContent` under its thread instance key, so a new thread starts fresh. Each
 * property is its own snapshot state; the list reads and writes them under their old local names.
 */
@Stable
internal class ThreadFocusedAnchorState {
    // Focused-message anchoring is one-shot per explicit request (message id +
    // request nonce). Timeline growth (secondary refresh hydrating the origin,
    // realtime arrivals, older pages) re-runs the effect only to retry a target
    // that was not yet measurable — never to re-scroll a consumed request, and
    // never against the user: one user drag stamps the pending identity as
    // interrupted and retires it (task #554 — thread jumped back to the focused
    // message ~1s after entry, cancelling the active drag).
    var consumedFocusedMessageAnchorIdentity by mutableStateOf<String?>(null)
    var scrollInterruptedFocusedMessageAnchorIdentity by mutableStateOf<String?>(null)
    // task #567 anchor session bookkeeping. Elapsed time accumulates across
    // effect restarts (structural data changes restart the anchor effect; the
    // hard cap must span restarts or a busy thread could hold the one-shot
    // open forever). Reset when a new anchor identity arrives (re-tap).
    var focusedAnchorSessionIdentity by mutableStateOf<String?>(null)
    var focusedAnchorSessionElapsedMs by mutableStateOf(0L)
    var focusedAnchorSessionReanchors by mutableStateOf(0)
    var focusedAnchorStartUserScrollGeneration by mutableStateOf<Int?>(null)
    var correctedFocusedOriginIdentity by mutableStateOf<String?>(null)
    var focusedAnchorPlacementState by mutableStateOf(resetThreadFocusedAnchorPlacement(identity = null))
    var bottomAnchorFocusedYieldElapsedMs by mutableStateOf(0L)
}
