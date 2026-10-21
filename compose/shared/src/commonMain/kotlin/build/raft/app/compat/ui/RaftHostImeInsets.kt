package build.raft.app.compat.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Density

/*
 * The keyboard as the host window last reported it (Hands 1af517cc, Android 16, Huawei: in a DM
 * the keyboard came up and the composer stayed behind it).
 *
 * Compose's `WindowInsets.ime` follows the platform's IME inset *animation*: once an animation is
 * prepared it ignores every plain inset dispatch until that animation ends. When the platform
 * shows or hides the keyboard without running an animation through this window (a keyboard that
 * opens "by itself": focus restored on return, an IME the system re-shows, a show that cancels a
 * running hide), `WindowInsets.ime` keeps the old value. The diagnostics show exactly that: the DM
 * behind the reply page took the new keyboard at once, the page in front kept a zero keyboard for
 * 356 ms and then jumped, and a one-frame keyboard blip never reached its layout. Everything
 * placed from `WindowInsets.ime` (the composer's bottom padding, the shell's bar hiding) then sits
 * behind the keyboard.
 *
 * The Android host therefore also publishes the window's own IME inset from every inset dispatch,
 * plus whether a platform IME animation is currently driving `WindowInsets.ime`. While one is, the
 * animated Compose value is used (the composer rides the keyboard frame by frame); otherwise the
 * host's value is the truth and is used directly. Hosts that publish nothing (iOS, tests)
 * keep reading `WindowInsets.ime` alone.
 */

/** IME geometry reported by the native host for its window. Written on the main thread. */
@Stable
class RaftHostImeInsets {
    /** Bottom IME inset of the window in px, from the latest platform inset dispatch. */
    var imeBottomPx: Int by mutableIntStateOf(0)

    /** A platform IME inset animation is running and feeding `WindowInsets.ime` frame by frame. */
    var imeAnimationRunning: Boolean by mutableStateOf(false)
}

/** The host's IME report for this composition's window; null where the host reports none. */
val LocalRaftHostImeInsets = staticCompositionLocalOf<RaftHostImeInsets?> { null }

/**
 * The keyboard's bottom inset to lay out against: the animated Compose value while a platform IME
 * animation drives it (or when the host reports nothing), the host's dispatched value otherwise.
 */
internal fun resolveRaftImeBottomPx(
    composeImeBottomPx: Int,
    hostImeBottomPx: Int?,
    hostImeAnimationRunning: Boolean,
): Int =
    if (hostImeBottomPx == null || hostImeAnimationRunning) {
        composeImeBottomPx
    } else {
        hostImeBottomPx
    }.coerceAtLeast(0)

/**
 * Reads [resolveRaftImeBottomPx] for [host] against [ime]. State reads happen in the caller's
 * phase, so call it from a layout block to keep keyboard frames out of composition.
 */
internal fun raftImeBottomPx(ime: WindowInsets, host: RaftHostImeInsets?, density: Density): Int =
    resolveRaftImeBottomPx(
        composeImeBottomPx = ime.getBottom(density),
        hostImeBottomPx = host?.imeBottomPx,
        hostImeAnimationRunning = host?.imeAnimationRunning ?: false,
    )

/** The current keyboard bottom inset in px for this window (see [resolveRaftImeBottomPx]). */
@Composable
internal fun raftImeBottomPx(density: Density): Int =
    raftImeBottomPx(WindowInsets.ime, LocalRaftHostImeInsets.current, density)
