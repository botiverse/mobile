package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import build.raft.app.data.SharedAdaptiveWorkspaceSession
import build.raft.app.data.visibleSideThread
import build.raft.designsystem.focus.RaftAutofocusGuard

/*
 * Opening a conversation puts the keyboard focus in its composer, as on the web (botiverse/slock
 * MessageInput `autoFocus`, default mode "desktop": focus on mount and on every channel change
 * when `(hover: hover)` matches; ChatPanel and the thread panel both pass it, so the thread panel,
 * mounted after the channel, ends up with the focus).
 *
 * Here: the Compose desktop app always; a phone or tablet only while a hardware keyboard is
 * connected. A touch-only device never takes focus on open, so no soft keyboard comes up by itself.
 * The request is focus only; it never asks for the soft keyboard ([ComposerAutofocusOnOpenEffect]).
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
 * When the side thread closes this turns false and the conversation takes the focus back, as the
 * web's thread panel returns it on close.
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

internal enum class ComposerAutofocusOutcome { Focused, Blocked, Disabled }

/**
 * Focuses [focusRequester] once per [key] (the conversation's composer instance) while [enabled],
 * unless [blocked] stays true for [ComposerAutofocusSettleFrames] frames. Focus only: on Android
 * and iOS the soft keyboard stays down while a hardware keyboard is connected, and desktop has none.
 */
@Composable
internal fun ComposerAutofocusOnOpenEffect(
    key: Any?,
    enabled: Boolean,
    focusRequester: FocusRequester,
    blocked: () -> Boolean = { RaftAutofocusGuard.blocksAutofocus },
    onOutcome: (ComposerAutofocusOutcome) -> Unit = {},
) {
    LaunchedEffect(key, enabled) {
        if (!enabled) {
            onOutcome(ComposerAutofocusOutcome.Disabled)
            return@LaunchedEffect
        }
        var frames = 0
        while (blocked()) {
            if (frames >= ComposerAutofocusSettleFrames) {
                onOutcome(ComposerAutofocusOutcome.Blocked)
                return@LaunchedEffect
            }
            withFrameNanos { }
            frames += 1
        }
        try {
            focusRequester.requestFocus()
            onOutcome(ComposerAutofocusOutcome.Focused)
        } catch (_: IllegalStateException) {
            // The editor is not attached (a read-only or not yet mounted composer): nothing to focus.
            onOutcome(ComposerAutofocusOutcome.Blocked)
        }
    }
}
