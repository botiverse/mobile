package build.raft.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.nucleusframework.window.tao.LocalTaoWindow
import dev.nucleusframework.window.tao.TaoMouseButton
import dev.nucleusframework.window.tao.TaoWindow
import java.lang.reflect.Field

/**
 * A mouse's side buttons (back / forward) do nothing in the window, instead of clicking what is under
 * the pointer. Nucleus 2.5.18's Tao window reports every button past the middle one with a single
 * code, [TaoMouseButton.OTHER] (native `events.rs` `mouse_button_code`: back and forward look the
 * same), and its scene host maps that code to a primary press (`AbstractTaoComposeSceneHost.mapButton`).
 *
 * The window's button listener is one slot, filled by the window's host when the window opens and
 * not readable through the API: [install] reads it and puts back a listener that drops
 * [TaoMouseButton.OTHER] and hands every other button to it. Stepping through the history with the
 * side buttons needs their number, which Tao does not pass on; ⌘[ / ⌘] do.
 */
internal object RaftDesktopSideButtons {
    /** Whether a press or release of the Tao window's [button] code reaches the window's content. */
    fun reachesContent(button: Int): Boolean = button != TaoMouseButton.OTHER

    /** [hostListener] without the side buttons. */
    fun withoutSideButtons(hostListener: (Int, Boolean) -> Unit): (Int, Boolean) -> Unit =
        { button, pressed -> if (reachesContent(button)) hostListener(button, pressed) }

    /** The window's listener slot (TaoWindow.pointerButtonListener), or null when this Nucleus has none. */
    internal val listenerField: Field? =
        runCatching {
            TaoWindow::class.java.getDeclaredField("pointerButtonListener").takeIf { it.type == Function2::class.java }?.apply { isAccessible = true }
        }.getOrNull()

    /** True when [window]'s host listener now drops the side buttons; false leaves the window as it was. */
    fun install(window: TaoWindow): Boolean {
        val hostListener =
            runCatching {
                @Suppress("UNCHECKED_CAST")
                listenerField?.get(window) as? (Int, Boolean) -> Unit
            }.getOrNull()
        if (hostListener == null) {
            RaftDesktopLog.w(TAG, "side_buttons_not_filtered reason=${if (listenerField == null) "no_listener_slot" else "no_host_listener"}")
            return false
        }
        window.onPointerButton(withoutSideButtons(hostListener))
        return true
    }

    private const val TAG = "RaftDesktopSideButtons"
}

/** Drops the window's side-button presses ([RaftDesktopSideButtons]) once the window's host listens. */
@Composable
internal fun RaftDesktopDropSideButtons() {
    val window = LocalTaoWindow.current ?: return
    // The window's host fills the listener slot when it opens the window, before the content is
    // composed, so it is there to wrap by the time this runs.
    LaunchedEffect(window) { RaftDesktopSideButtons.install(window) }
}
