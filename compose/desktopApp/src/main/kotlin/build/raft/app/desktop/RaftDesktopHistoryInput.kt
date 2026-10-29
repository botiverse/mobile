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
 * macOS swipe events (`-[NSResponder swipeWithEvent:]`: a three-finger trackpad swipe set to
 * "Swipe between pages", and what mouse utilities such as Logi Options+ can send for a mouse's
 * back / forward buttons) reach the JVM through `com.apple.eawt.event.GestureUtilities`
 * ([RaftDesktopInputDiagnostics.installSwipes], [raftDesktopHistorySwipe]).
 *
 * Not mapped: a trackpad's two-finger horizontal swipe. Compose Desktop (and AWT) deliver it only
 * as horizontal scroll deltas, the same events every horizontally scrollable list consumes.
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

/** Who takes a history step: the app shell (its history), the page on top (closing), or nobody. */
internal enum class RaftDesktopHistoryRoute { Shell, ClosePage, None }

/**
 * The shell alone on the stack takes every step; a page pushed over it takes Back as closing
 * itself and ignores Forward; any other lone page (none today) ignores both.
 */
internal fun raftDesktopHistoryRoute(
    stackSize: Int,
    topIsShell: Boolean,
    direction: RaftDesktopHistoryDirection,
): RaftDesktopHistoryRoute =
    when {
        stackSize > 1 ->
            if (direction == RaftDesktopHistoryDirection.Back) RaftDesktopHistoryRoute.ClosePage else RaftDesktopHistoryRoute.None
        stackSize == 1 && topIsShell -> RaftDesktopHistoryRoute.Shell
        else -> RaftDesktopHistoryRoute.None
    }

/**
 * A mouse's side buttons. Compose maps AWT button n (above 3) to `PointerButton(n - 1)`:
 * - AWT 4 / 5 ([PointerButton.Back] / [PointerButton.Forward]): macOS (AppKit buttonNumber 3 / 4;
 *   the JDK's macOS toolkit reports at most 5 buttons and drops higher ones) and Windows (XBUTTON1 / 2).
 * - AWT 6 / 7 (`PointerButton(5)` / `(6)`): Linux X11, where X buttons 4-7 are the scroll wheel and
 *   the side buttons 8 / 9 arrive as AWT 6 / 7.
 */
internal fun raftDesktopHistoryMouseButton(button: PointerButton?): RaftDesktopHistoryDirection? =
    when (button?.index) {
        PointerButton.Back.index, AWT_BUTTON_6_INDEX -> RaftDesktopHistoryDirection.Back
        PointerButton.Forward.index, AWT_BUTTON_7_INDEX -> RaftDesktopHistoryDirection.Forward
        else -> null
    }

private const val AWT_BUTTON_6_INDEX = 5
private const val AWT_BUTTON_7_INDEX = 6

/**
 * A macOS swipe, by the `com.apple.eawt.event.SwipeListener` method the JDK called. The JDK passes
 * NSEvent's `deltaX` through and calls `swipedRight` for deltaX > 0, which browsers (Chromium,
 * Safari) treat as Back, and `swipedLeft` for deltaX < 0 (Forward). Up / down: null.
 */
internal fun raftDesktopHistorySwipe(listenerMethod: String): RaftDesktopHistoryDirection? =
    when (listenerMethod) {
        "swipedRight" -> RaftDesktopHistoryDirection.Back
        "swipedLeft" -> RaftDesktopHistoryDirection.Forward
        else -> null
    }
