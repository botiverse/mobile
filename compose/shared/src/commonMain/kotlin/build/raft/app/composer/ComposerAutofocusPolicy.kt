package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import build.raft.app.data.SharedAdaptiveWorkspaceSession
import build.raft.app.data.visibleSideThread
import build.raft.designsystem.focus.RaftAutofocusGuard
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

/*
 * Opening a conversation puts the keyboard focus in its composer, as on the web (botiverse/slock
 * MessageInput `autoFocus`, default mode "desktop": focus on mount and on every channel change
 * when `(hover: hover)` matches; ChatPanel and the thread panel both pass it, so the thread panel,
 * mounted after the channel, ends up with the focus).
 *
 * Here: the Compose desktop app always; a phone or tablet only while a hardware keyboard is
 * connected. A touch-only device never takes focus on open, so no soft keyboard comes up by itself.
 * The request is focus only; it never asks for the soft keyboard ([ComposerAutofocusOnOpenEffect]).
 *
 * An open that lands on one message (a message link, an Activity row, a search result, a
 * permalink: the route's focused message) leaves the composer alone on a phone or tablet
 * ([composerAutofocusSkipsMessageOpen]).
 */

/** Whether opening a conversation focuses its composer: a desktop host, or a connected hardware keyboard. */
internal fun composerAutofocusOnOpen(desktopHost: Boolean, hardwareKeyboardConnected: Boolean): Boolean =
    desktopHost || hardwareKeyboardConnected

/**
 * A hardware keyboard is connected now (Android: a keyboard the configuration reports as present
 * and not hidden; iOS: `GCKeyboard.coalescedKeyboard`; desktop: always). Recomposes when one is
 * connected or removed.
 */
@Composable
internal expect fun rememberRaftHardwareKeyboardConnected(): Boolean

/**
 * Whether an open that lands on one message ([opensOnMessage]: the route has a focused message)
 * leaves the composer unfocused: on every host but desktop. On a phone or tablet the focus can
 * still raise the soft keyboard (Android shows it beside a hardware keyboard when the user's
 * setting says so, and cloud phones and emulators report a keyboard), and its inset pushed the
 * message the user came for off the screen. Desktop has no soft keyboard and keeps the web's
 * focus on open.
 */
internal fun composerAutofocusSkipsMessageOpen(desktopHost: Boolean, opensOnMessage: Boolean): Boolean =
    opensOnMessage && !desktopHost

@Composable
internal fun rememberComposerAutofocusOnOpen(): Boolean =
    composerAutofocusOnOpen(
        desktopHost = raftPlatformHasFinePointer(),
        hardwareKeyboardConnected = rememberRaftHardwareKeyboardConnected(),
    )

/**
 * Whether the conversation page [page] leaves the focus to the reply thread beside it: it is the
 * wide detail's main conversation while a side thread is shown for it. The side thread's own page
 * (its close request is the side thread's) and pages outside the wide detail (null) never yield.
 * When the side thread closes this turns false and the conversation takes the focus back only if
 * the focus was in the thread and left with it ([ComposerFocusOnOpen.restoreGeneration];
 * raft-design "Moving focus" rule 4).
 */
internal fun SharedAdaptiveWorkspaceSession.composerAutofocusYieldsToSideThread(
    page: RaftAdaptiveDetailCloseRequest?,
): Boolean {
    if (page == null) return false
    val side = visibleSideThread() ?: return false
    return !(side.identity.key == page.identityKey && side.generation == page.generation)
}

/**
 * Frames an open request waits for [RaftAutofocusGuard] to clear: the click or Enter that opened
 * the conversation may still be closing its menu, or blurring the search field it came from (the
 * outside-tap blur lands two frames after release). Still blocked after that, the user is in
 * something else, and the composer leaves the focus where it is.
 */
internal const val ComposerAutofocusSettleFrames = 6

/**
 * Frames a restore waits for the closed side thread to leave the composition and drop its focus;
 * also how long the focus leaving the thread's composer waits before it counts as the user moving
 * on (the composer leaving with its closing thread blurs it too).
 */
internal const val ComposerFocusRestoreSettleFrames = 2

internal enum class ComposerAutofocusOutcome {
    Focused,
    Blocked,
    Disabled,

    /** The open lands on one message: the composer stays unfocused ([composerAutofocusSkipsMessageOpen]). */
    OpensOnMessage,
}

/**
 * One conversation page's automatic composer focus (raft-design "Moving focus"), remembered by the
 * page, not the composer, so a composer rebuilt under the page (its instance id swapped when the
 * conversation finishes loading) does not count as a new open.
 *
 * - The open request runs once per conversation ([ComposerAutofocusOnOpenEffect]'s key, the
 *   conversation's identity): it waits for the page's first showing ([shown]), then for
 *   [RaftAutofocusGuard] to clear, and reads [enabled] only then. A later change of [enabled]
 *   (a hardware keyboard connected again, the window widened, a side thread closed) or of [shown]
 *   (a page over this one popped) never runs it again: only a person opening the conversation does.
 * - [restoreGeneration] counts the side threads beside this conversation that closed; each one
 *   puts the focus back in the composer only when the focus was in the side thread's composer at
 *   the close ([sideThreadHeldFocus]) and went with it. Focus the user moved elsewhere while the
 *   thread was open (Members search, a row, a button) stays there.
 *
 * Main thread only.
 */
@Stable
internal class ComposerFocusOnOpen(
    enabled: Boolean = false,
    shown: Boolean = true,
    yieldsToSideThread: Boolean = false,
    opensOnMessage: Boolean = false,
) {
    /** The platform allows it (desktop / hardware keyboard) and no reply thread beside takes it. */
    var enabled: Boolean by mutableStateOf(enabled)

    /** The page is on screen. */
    var shown: Boolean by mutableStateOf(shown)

    /**
     * The open lands on one message and the host can raise a soft keyboard
     * ([composerAutofocusSkipsMessageOpen]): the open request leaves the composer alone. Read when
     * the request runs, like [enabled]; a side thread closing still restores the focus it took.
     */
    var opensOnMessage: Boolean by mutableStateOf(opensOnMessage)

    /** +1 each time the reply thread beside this conversation closes. */
    var restoreGeneration: Int by mutableStateOf(0)

    /** A reply thread beside this conversation takes the focus. */
    var yieldsToSideThread: Boolean by mutableStateOf(yieldsToSideThread)
        private set

    /**
     * While a side thread was shown: whether its composer held the focus last. Recorded while the
     * thread is open, so it says where the focus was when the thread closed.
     */
    internal var sideThreadHeldFocus: Boolean = false

    internal var openedConversation: Any? = null

    /** The page's current answers; a side thread that stops being shown beside it asks for a restore. */
    internal fun update(enabled: Boolean, shown: Boolean, yieldsToSideThread: Boolean, opensOnMessage: Boolean = false) {
        if (this.enabled != enabled) this.enabled = enabled
        if (this.opensOnMessage != opensOnMessage) this.opensOnMessage = opensOnMessage
        if (this.shown != shown) this.shown = shown
        if (this.yieldsToSideThread && !yieldsToSideThread) restoreGeneration += 1
        if (this.yieldsToSideThread != yieldsToSideThread) this.yieldsToSideThread = yieldsToSideThread
    }
}

/**
 * The conversation composers that hold the focus now, by their page's [ComposerFocusOnOpen]
 * ([composerReportsFocus]). Kept apart from [RaftAutofocusGuard], where a composer never reports.
 * Main thread only.
 */
internal object ComposerFocusHolders {
    var holders: Set<ComposerFocusOnOpen> by mutableStateOf(emptySet())
        private set

    fun set(page: ComposerFocusOnOpen, focused: Boolean) {
        val next = if (focused) holders + page else holders - page
        if (next != holders) holders = next
    }
}

/** Reports the composer editor's focus to [ComposerFocusHolders] for its page [focus]. */
@Composable
internal fun Modifier.composerReportsFocus(focus: ComposerFocusOnOpen?): Modifier {
    if (focus == null) return this
    DisposableEffect(focus) {
        onDispose { ComposerFocusHolders.set(focus, false) }
    }
    return onFocusChanged { ComposerFocusHolders.set(focus, it.isFocused) }
}

/**
 * The page's [ComposerFocusOnOpen]: [policy] (desktop or a hardware keyboard,
 * [rememberComposerAutofocusOnOpen]), [shown] (the page is on screen) and [yieldsToSideThread]
 * ([composerAutofocusYieldsToSideThread]), and [opensOnMessage] ([composerAutofocusSkipsMessageOpen]).
 * Updated after each composition; the composer reads it
 * when its requests run, so a change here never recomposes the composer.
 */
@Composable
internal fun rememberComposerFocusOnOpen(
    policy: Boolean,
    shown: Boolean,
    yieldsToSideThread: Boolean,
    opensOnMessage: Boolean = false,
): ComposerFocusOnOpen {
    val focus =
        remember {
            ComposerFocusOnOpen(
                enabled = policy && !yieldsToSideThread,
                shown = shown,
                yieldsToSideThread = yieldsToSideThread,
                opensOnMessage = opensOnMessage,
            )
        }
    SideEffect {
        focus.update(
            enabled = policy && !yieldsToSideThread,
            shown = shown,
            yieldsToSideThread = yieldsToSideThread,
            opensOnMessage = opensOnMessage,
        )
    }
    return focus
}

/**
 * The composer side of [focus]: focuses [focusRequester] once per [conversation] (the
 * conversation's identity: its surface kind and thread / channel, never the composer instance)
 * unless [blocked] stays true for [ComposerAutofocusSettleFrames] frames, and focuses it again
 * after a side thread closes only when the thread's composer had the focus and took it along.
 * The editor carries [composerReportsFocus] for the same [focus]. Focus only: on Android and iOS the soft
 * keyboard stays down while a hardware keyboard is connected, and desktop has none.
 */
@Composable
internal fun ComposerAutofocusOnOpenEffect(
    conversation: Any?,
    focus: ComposerFocusOnOpen?,
    focusRequester: FocusRequester,
    blocked: () -> Boolean = { RaftAutofocusGuard.blocksAutofocus },
    onOutcome: (ComposerAutofocusOutcome) -> Unit = {},
) {
    if (focus == null || conversation == null) return
    val currentRequester by rememberUpdatedState(focusRequester)
    val currentOnOutcome by rememberUpdatedState(onOutcome)
    LaunchedEffect(focus, conversation) {
        if (focus.openedConversation == conversation) return@LaunchedEffect
        // Opened behind another page or before the page appeared: the open is its first showing.
        snapshotFlow { focus.shown }.first { it }
        var frames = 0
        while (blocked() && frames < ComposerAutofocusSettleFrames) {
            withFrameNanos { }
            frames += 1
        }
        // Read when the request runs: what the page says now, not when it was composed.
        val outcome =
            when {
                blocked() -> ComposerAutofocusOutcome.Blocked
                !focus.enabled || !focus.shown -> ComposerAutofocusOutcome.Disabled
                focus.opensOnMessage -> ComposerAutofocusOutcome.OpensOnMessage
                currentRequester.tryRequestFocus() -> ComposerAutofocusOutcome.Focused
                else -> ComposerAutofocusOutcome.Blocked
            }
        focus.openedConversation = conversation
        currentOnOutcome(outcome)
    }
    LaunchedEffect(focus) {
        // Where the focus is while a side thread is open: in another composer (the thread's), or
        // moved elsewhere by the user. The thread's composer leaving with the closing thread blurs
        // it as well; the close (yieldsToSideThread false) arrives within the settle frames and
        // cancels that wait, so the record keeps "the thread had it".
        snapshotFlow { focus.yieldsToSideThread to ComposerFocusHolders.holders.any { it !== focus } }
            .collectLatest { (yielding, threadHasFocus) ->
                if (!yielding) return@collectLatest
                if (threadHasFocus) {
                    focus.sideThreadHeldFocus = true
                } else {
                    repeat(ComposerFocusRestoreSettleFrames) { withFrameNanos { } }
                    if (focus.yieldsToSideThread) focus.sideThreadHeldFocus = false
                }
            }
    }
    LaunchedEffect(focus) {
        // Only side threads that close from now on: one that closed while this composer was not
        // mounted is history, not a request.
        var handled = focus.restoreGeneration
        snapshotFlow { focus.restoreGeneration }.collect { generation ->
            if (generation <= handled) return@collect
            handled = generation
            // Let the closed side thread leave the composition and its focused element drop the focus.
            repeat(ComposerFocusRestoreSettleFrames) { withFrameNanos { } }
            val threadHadFocus = focus.sideThreadHeldFocus
            focus.sideThreadHeldFocus = false
            // Rule 4: back only if the focus went with the thread (no composer has it now).
            if (threadHadFocus && focus.enabled && focus.shown && ComposerFocusHolders.holders.isEmpty() && !blocked()) {
                currentRequester.tryRequestFocus()
            }
        }
    }
}

/** The editor is not attached (a read-only or not yet mounted composer): nothing to focus. */
private fun FocusRequester.tryRequestFocus(): Boolean =
    try {
        requestFocus()
        true
    } catch (_: IllegalStateException) {
        false
    }
