package build.raft.app.desktop

import androidx.compose.ui.input.key.Key

/**
 * The global search shortcut (web `isGlobalSearchShortcut`): the host maps it and sends the app
 * shell [EVENT_NAME] (the shared `RAFT_SEARCH_SHORTCUT_EVENT`), which opens search. Taken at the
 * window before the content, so a focused text field (where Ctrl+K could mean "delete to the
 * line's end") never keeps it.
 */
internal object RaftDesktopSearchInput {
    const val EVENT_NAME = "slockSearchShortcut"
}

/** ⌘K on macOS, Ctrl+K on Linux and Windows; a key up, Shift, Alt or any other key: false. */
internal fun raftDesktopSearchShortcut(
    keyDown: Boolean,
    key: Key,
    meta: Boolean,
    ctrl: Boolean,
    alt: Boolean,
    shift: Boolean,
    mac: Boolean,
): Boolean {
    if (!keyDown || key != Key.K || alt || shift) return false
    return if (mac) meta && !ctrl else ctrl && !meta
}
