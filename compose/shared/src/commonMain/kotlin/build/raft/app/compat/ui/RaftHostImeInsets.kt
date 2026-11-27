package build.raft.app.compat.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Density
import build.raft.designsystem.components.LocalRaftToastAvoidanceActive
import build.raft.designsystem.components.RaftToastBottomInsetProvider

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
 * The Android and iOS hosts therefore drive `imeBottomPx` directly as the single authoritative IME
 * height (`imeAnimationRunning` stays `false` permanently on both platforms so Compose never
 * switches between two inset sources mid-animation or at settlement):
 * - Android: settled heights from `onApplyWindowInsets`, per-frame interpolated heights from
 *   `WindowInsetsAnimation.Callback.onProgress`, and Choreographer vsync reconciliation if an OEM
 *   drops `onStart`/`onEnd`.
 * - iOS: curve-matched per-frame heights from `UIKeyboardWillChangeFrameNotification` and settled
 *   heights from `UIKeyboardDidChangeFrameNotification` / zero-duration changes (see
 *   [ProvideRaftPlatformImeInsets]).
 * Hosts that publish nothing (desktop, tests) keep reading `WindowInsets.ime` alone.
 */

/** IME geometry reported by the native host for its window. Written on the main thread. */
@Stable
class RaftHostImeInsets {
    /** Bottom IME inset of the window in px, driven directly by the native host. */
    var imeBottomPx: Int by mutableIntStateOf(0)

    /**
     * When `true`, `WindowInsets.ime` is used instead of [imeBottomPx]. Both Android and iOS keep
     * this `false` so [imeBottomPx] remains the single authoritative inset source.
     */
    var imeAnimationRunning: Boolean by mutableStateOf(false)
}

/** The host's IME report for this composition's window; null where the host reports none. */
val LocalRaftHostImeInsets = staticCompositionLocalOf<RaftHostImeInsets?> { null }

/**
 * Wraps one page's content with the platform's own keyboard report, where the platform needs one.
 *
 * iOS: CMP 1.12 animates `WindowInsets.ime` with the wrong curve (it passes the keyboard's
 * `UIViewAnimationCurve` raw value as `UIViewAnimationOptions` without the `<< 16` shift, so the
 * keyboard's curve 7 becomes ease-in-out; CMP-10448, fixed upstream in 1.13). The composer then
 * trails the keyboard by up to a third of its height at the start of the slide and catches up at
 * the end, which reads as a low-frame-rate, unglued keyboard. The iOS actual follows the keyboard
 * with the real curve and keeps [RaftHostImeInsets.imeBottomPx] as the single authoritative IME
 * inset source even after settling (no handoff back to `WindowInsets.ime`).
 * Android's host provides [LocalRaftHostImeInsets] round its page stack itself and the desktop has
 * no software keyboard: both pass [content] through.
 */
@Composable
internal expect fun ProvideRaftPlatformImeInsets(content: @Composable () -> Unit)

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

/**
 * Feeds the host's live bottom safe-area + keyboard inset into the enclosing `RaftToastHost`
 * ([RaftToastBottomInsetProvider]), so toasts follow the exact same IME source as the composer
 * (Android's dispatched [LocalRaftHostImeInsets] and iOS's curve-matched keyboard tracker) instead
 * of fighting CMP's raw `WindowInsets.safeDrawing`.
 */
@Composable
fun RaftToastHostImeBottomInset() {
    if (!LocalRaftToastAvoidanceActive.current) return
    val nonImeBottom = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val ime = WindowInsets.ime
    val hostIme = LocalRaftHostImeInsets.current
    RaftToastBottomInsetProvider {
        maxOf(nonImeBottom.getBottom(this), raftImeBottomPx(ime, hostIme, this)).toFloat()
    }
}
