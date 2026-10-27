package build.raft.app.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerButton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Back / forward through the wide layout's history from the keyboard and the mouse (Slack
 * desktop): the host maps the input to a direction and sends it to the app shell page
 * ([EVENT_NAME], the shared `RAFT_WIDE_HISTORY_NAVIGATE_EVENT`), which owns the history and shows
 * the entry. A page pushed over the shell takes the mouse's back button as Back instead.
 *
 * Not mapped: a trackpad's two-finger horizontal swipe. Compose Desktop (and AWT) deliver it only
 * as horizontal scroll deltas, the same events every horizontally scrollable list consumes; macOS's
 * own navigation swipe (NSEvent swipe / `GestureUtilities`) is not reachable from the JVM.
 */
internal enum class RaftDesktopHistoryDirection(val wireName: String) {
    Back("back"),
    Forward("forward"),
}

internal object RaftDesktopHistoryInput {
    const val EVENT_NAME = "slockWideHistoryNavigate"
    const val DIRECTION_KEY = "direction"

    val isMac: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

    fun eventData(direction: RaftDesktopHistoryDirection): JsonObject =
        JsonObject(mapOf(DIRECTION_KEY to JsonPrimitive(direction.wireName)))
}

/**
 * Cmd+[ / Cmd+] on macOS; Ctrl+[ / Ctrl+] or Alt+Left / Alt+Right on Linux and Windows (their
 * browsers' Back / Forward; on macOS Option+arrows move by word, so they stay unmapped there).
 * Shift or any extra modifier, a key up, or another key: null.
 */
internal fun raftDesktopHistoryShortcut(
    keyDown: Boolean,
    key: Key,
    meta: Boolean,
    ctrl: Boolean,
    alt: Boolean,
    shift: Boolean,
    mac: Boolean,
): RaftDesktopHistoryDirection? {
    if (!keyDown || shift) return null
    val bracketChord = if (mac) meta && !ctrl && !alt else ctrl && !meta && !alt
    val arrowChord = !mac && alt && !ctrl && !meta
    return when {
        bracketChord && key == Key.LeftBracket -> RaftDesktopHistoryDirection.Back
        bracketChord && key == Key.RightBracket -> RaftDesktopHistoryDirection.Forward
        arrowChord && key == Key.DirectionLeft -> RaftDesktopHistoryDirection.Back
        arrowChord && key == Key.DirectionRight -> RaftDesktopHistoryDirection.Forward
        else -> null
    }
}

/** A mouse's side buttons: back (button 4) and forward (button 5). */
internal fun raftDesktopHistoryMouseButton(button: PointerButton?): RaftDesktopHistoryDirection? =
    when (button) {
        PointerButton.Back -> RaftDesktopHistoryDirection.Back
        PointerButton.Forward -> RaftDesktopHistoryDirection.Forward
        else -> null
    }
