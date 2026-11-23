package build.raft.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import build.raft.app.data.RaftHostInUse
import build.raft.app.data.raftWindowInUse
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Feeds [RaftHostInUse] on desktop: the window counts as in use while it is focused and had input
 * (key, click, pointer or wheel) in the last 3 minutes, the approved "in use" rule. Gaining focus
 * counts as input. Pages read what is on screen only while it holds.
 *
 * Input is what reaches the window's Compose content ([raftDesktopInUseInput], and the window's key
 * handler): the Tao window has no AWT event queue to listen to. Keys typed into an embedded web page
 * go to the page itself and are not counted; the pointer over it is.
 */
internal object RaftDesktopInUse {
    @Volatile private var focused = false
    @Volatile private var lastInputAtMillis: Long? = null

    fun onFocusChanged(hasFocus: Boolean) {
        focused = hasFocus
        if (hasFocus) lastInputAtMillis = nowMillis()
        recompute()
    }

    fun onInput() {
        lastInputAtMillis = nowMillis()
        // Pointer moves arrive often: only a change of state is published.
        if (!RaftHostInUse.isInUse) recompute()
    }

    fun recompute() {
        val next = raftWindowInUse(focused = focused, lastInputAtMillis = lastInputAtMillis, nowMillis = nowMillis())
        if (next != RaftHostInUse.isInUse) {
            RaftDesktopLog.d(TAG, "host_in_use inUse=$next focused=$focused")
            RaftHostInUse.update(next)
        }
    }

    private fun nowMillis(): Long = System.nanoTime() / 1_000_000L

    private const val TAG = "SlockDesktopInUse"
}

/** Every pointer event over the window's content counts as input; nothing is consumed. */
internal fun Modifier.raftDesktopInUseInput(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial)
                RaftDesktopInUse.onInput()
            }
        }
    }

/** Tracks the window's focus ([focused]) for [RaftDesktopInUse] and re-checks the 3-minute input window. */
@Composable
internal fun RaftDesktopInUseTracker(focused: Boolean) {
    LaunchedEffect(focused) { RaftDesktopInUse.onFocusChanged(focused) }
    DisposableEffect(Unit) { onDispose { RaftDesktopInUse.onFocusChanged(false) } }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(IN_USE_RECHECK_MILLIS)
            RaftDesktopInUse.recompute()
        }
    }
}

/** How late an idle window may notice it went out of use. */
private const val IN_USE_RECHECK_MILLIS = 10_000L
