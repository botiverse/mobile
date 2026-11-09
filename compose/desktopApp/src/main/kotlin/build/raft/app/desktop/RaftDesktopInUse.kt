package build.raft.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import build.raft.app.data.RaftHostInUse
import build.raft.app.data.raftWindowInUse
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Feeds [RaftHostInUse] on desktop: the window counts as in use while it is focused and had input
 * (key, click, pointer or wheel) in the last 3 minutes, the approved "in use" rule. Gaining focus
 * counts as input. Pages read what is on screen only while it holds.
 */
internal object RaftDesktopInUse {
    @Volatile private var focused = false
    @Volatile private var lastInputAtMillis: Long? = null
    private var installed = false

    private val inputListener = AWTEventListener { onInput() }

    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        runCatching {
            Toolkit.getDefaultToolkit().addAWTEventListener(
                inputListener,
                AWTEvent.KEY_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or
                    AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK,
            )
        }.onFailure { RaftDesktopLog.w(TAG, "input listener unavailable: ${it.message}") }
        recompute()
    }

    fun onFocusChanged(hasFocus: Boolean) {
        focused = hasFocus
        if (hasFocus) lastInputAtMillis = nowMillis()
        recompute()
    }

    private fun onInput() {
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

/** Tracks [window]'s focus for [RaftDesktopInUse] and re-checks the 3-minute input window. */
@Composable
internal fun RaftDesktopInUseTracker(window: Window) {
    DisposableEffect(window) {
        val listener =
            object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent?) = RaftDesktopInUse.onFocusChanged(true)

                override fun windowLostFocus(e: WindowEvent?) = RaftDesktopInUse.onFocusChanged(false)
            }
        window.addWindowFocusListener(listener)
        RaftDesktopInUse.onFocusChanged(window.isFocused)
        onDispose {
            window.removeWindowFocusListener(listener)
            RaftDesktopInUse.onFocusChanged(false)
        }
    }
    LaunchedEffect(window) {
        while (isActive) {
            delay(IN_USE_RECHECK_MILLIS)
            RaftDesktopInUse.recompute()
        }
    }
}

/** How late an idle window may notice it went out of use. */
private const val IN_USE_RECHECK_MILLIS = 10_000L
