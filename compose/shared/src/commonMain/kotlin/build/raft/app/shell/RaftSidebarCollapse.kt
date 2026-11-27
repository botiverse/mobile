package build.raft.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.RaftCollapsibleColumn
import build.raft.app.settings.retainedAppShellSelectedTab
import build.raft.app.thread.key
import build.raft.designsystem.theme.RaftPointerInput

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

    /** Columns giving way to the row on a portrait tablet ([raftSidebarGivesWay]): hidden, never saved. */
    private val givingWay = RaftCollapsibleColumn.entries.associateWith { mutableStateOf(false) }

    /** Columns the person brought back while they give way: shown, the row clipping, until the row has room again. */
    private val keptOpen = RaftCollapsibleColumn.entries.associateWith { mutableStateOf(false) }

    fun isHidden(column: RaftCollapsibleColumn): Boolean =
        hidden.getValue(column).value || (givingWay.getValue(column).value && !keptOpen.getValue(column).value)

    /**
     * The person hides or shows [column] (the rail's toggle, the shortcut, the handle): saved as before. Shown while it
     * gives way, it stays shown until the row has room again, and gives way again the next time it doesn't.
     */
    fun setHidden(column: RaftCollapsibleColumn, hidden: Boolean) {
        keptOpen.getValue(column).value = !hidden && givingWay.getValue(column).value
        val state = this.hidden.getValue(column)
        if (state.value == hidden) return
        state.value = hidden
        store?.saveCollapsibleColumnHidden(column, hidden)
    }

    fun toggle(column: RaftCollapsibleColumn) = setHidden(column, !isHidden(column))

    fun isGivingWay(column: RaftCollapsibleColumn): Boolean = givingWay.getValue(column).value

    /**
     * Whether [column] gives way to the row now ([raftSidebarGivesWay]). Never saved: the saved state is untouched, so
     * the column comes back when the row has room again (the side column closed, the tablet turned).
     */
    fun setGivingWay(column: RaftCollapsibleColumn, givesWay: Boolean) {
        val state = givingWay.getValue(column)
        if (state.value == givesWay) return
        state.value = givesWay
        if (!givesWay) keptOpen.getValue(column).value = false
    }
}

/**
 * Below this width a touch tablet held upright gives way: the width under which responsive.md keeps a portrait touch
 * tablet's thread folded whatever the room (web index.css `(orientation: portrait) and (max-width: 1279.98px)`,
 * useSideRoom `TOUCH_PORTRAIT`).
 */
internal const val RAFT_PORTRAIT_TABLET_MAX_WIDTH_DP = 1280f

/**
 * Whether the list column left of the main area gives way to the row (#337, Reef's patrol item 7): on a touch tablet
 * held upright under [RAFT_PORTRAIT_TABLET_MAX_WIDTH_DP] (an iPad in portrait, 744 to 1024dp), where the sidebar
 * beside the conversation leaves too little room for the conversation's 360 and the columns open right of it
 * ([crowdsRow], the design system's `raftPaneGridLeadingCrowdsRow`: Tasks / Files, the channel details, a profile), the
 * sidebar collapses as the rail's Collapse Sidebar would, so the conversation keeps its 360 and its composer's Send
 * stays in view instead of the row clipping it. It is the sidebar, not the side column, that gives way: the side column
 * is what was just opened. The row itself is web's #189 row; responsive.md's sidebar toggle is "a change of the
 * window", so this is the same row a window one sidebar wider would get. A phone has no sidebar; a mouse or trackpad,
 * a landscape window and anything from 1280dp keep web's row as it is (the row gives way, then clips).
 */
internal fun raftSidebarGivesWay(
    pointerInput: RaftPointerInput,
    windowWidthDp: Float,
    windowHeightDp: Float,
    crowdsRow: Boolean,
): Boolean =
    crowdsRow &&
        pointerInput == RaftPointerInput.Touch &&
        windowHeightDp > windowWidthDp &&
        windowWidthDp < RAFT_PORTRAIT_TABLET_MAX_WIDTH_DP

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
