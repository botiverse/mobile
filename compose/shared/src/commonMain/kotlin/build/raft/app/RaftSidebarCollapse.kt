package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.RaftCollapsibleColumn

/**
 * The wide layout's sidebar collapse (web LeftRail `SidebarToggleRailItem`, uiStore
 * `sidebarHidden` / `activityListHidden`; raft-design Navigation "Collapsing the sidebar"): the
 * rail's toggle and ⌘\ / Ctrl+\ hide the list column beside the main area so the main area takes
 * its width, at once (no slide). The state is remembered per device, each column on its own.
 */
@Stable
internal class RaftSidebarCollapseState(private val store: KeyValueChatLayoutPreferenceStore?) {
    private val hidden =
        RaftCollapsibleColumn.entries.associateWith { column ->
            mutableStateOf(store?.readCollapsibleColumnHiddenSync(column) ?: false)
        }

    fun isHidden(column: RaftCollapsibleColumn): Boolean = hidden.getValue(column).value

    fun setHidden(column: RaftCollapsibleColumn, hidden: Boolean) {
        val state = this.hidden.getValue(column)
        if (state.value == hidden) return
        state.value = hidden
        store?.saveCollapsibleColumnHidden(column, hidden)
    }

    fun toggle(column: RaftCollapsibleColumn) = setHidden(column, !isHidden(column))
}

@Composable
internal fun rememberRaftSidebarCollapseState(store: KeyValueChatLayoutPreferenceStore?): RaftSidebarCollapseState =
    remember(store) { RaftSidebarCollapseState(store) }

/**
 * Which column the rail's toggle (and the shortcut) collapses on this screen, or null where it is
 * not shown (web shellRouteLayout `sidebarCollapsible` / `collapseColumn`): only on the wide
 * layout, where a list column sits beside the main area. Home's channels and the Settings list
 * are the sidebar; Activity's list collapses on its own. Phone layouts, the pages without a list
 * column (Tasks, Members' own page), a page pushed over Home and the inline Search / Saved lists
 * (which stay put, as web's Search results list) have nothing to collapse; the remembered state
 * waits for the next screen that has one.
 */
internal fun appShellCollapsibleColumn(
    navRailActive: Boolean,
    selectedTab: String,
    selectedHomeDestination: String?,
    railInlineSecondaryDestination: String?,
): RaftCollapsibleColumn? {
    if (!navRailActive || railInlineSecondaryDestination != null) return null
    return when (retainedAppShellSelectedTab(selectedTab)) {
        "home" -> RaftCollapsibleColumn.Sidebar.takeIf { selectedHomeDestination == null }
        "settings", "more" -> RaftCollapsibleColumn.Sidebar
        "activity" -> RaftCollapsibleColumn.ActivityList
        else -> null
    }
}

/**
 * ⌘\ on Apple platforms, Ctrl+\ elsewhere (web `isSidebarToggleShortcut`), with no Shift or Alt.
 * Key repeat is the caller's to ignore.
 */
internal fun raftSidebarToggleShortcut(
    keyDown: Boolean,
    key: Key,
    meta: Boolean,
    ctrl: Boolean,
    alt: Boolean,
    shift: Boolean,
    apple: Boolean,
): Boolean {
    if (!keyDown || key != Key.Backslash || alt || shift) return false
    return if (apple) meta && !ctrl else ctrl && !meta
}

/** The shortcut as the rail's tooltip shows it (web `getSidebarToggleShortcutLabel`). */
internal fun raftSidebarToggleShortcutLabel(apple: Boolean): String = if (apple) "⌘\\" else "Ctrl+\\"
