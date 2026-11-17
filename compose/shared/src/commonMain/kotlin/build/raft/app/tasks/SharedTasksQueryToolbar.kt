package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import build.raft.app.data.ServerTaskBoardStatuses
import build.raft.app.data.ServerTaskSort
import build.raft.app.data.TasksPageScope
import build.raft.app.data.TasksQueryIntent
import build.raft.app.data.TasksQueryState
import build.raft.app.data.TasksViewMode
import build.raft.app.model.TaskStatus
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftCheckboxIndicator
import build.raft.designsystem.components.RaftFitToolbar
import build.raft.designsystem.components.RaftFitToolbarAction
import build.raft.designsystem.components.RaftFitToolbarChoice
import build.raft.designsystem.components.RaftFitToolbarOption
import build.raft.designsystem.components.RaftFitToolbarScope
import build.raft.designsystem.components.RaftFitToolbarSearch
import build.raft.designsystem.components.RaftFitToolbarView
import build.raft.designsystem.components.RaftFitToolbarViewOption
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.theme.LocalRaftContentColor

/** Test tags of the Tasks page's toolbar (web `tasks-page-*` data-testids). */
internal object SharedTasksQueryToolbarTags {
    const val Toolbar = "tasks-page-toolbar"
    /** The scope; its segments are `tasks-page-scope-mine` / `tasks-page-scope-all`. */
    const val Scope = "tasks-page-scope"
    const val Search = "tasks-page-search"
    const val Status = "tasks-page-filter-status"
    const val Sort = "tasks-page-sort"
}

/**
 * The Tasks page's toolbar (web `TasksPageToolbar`, botiverse/slock staging
 * c6580f2f41a617f1c406f13ffdcac2150ea3b044) on [RaftFitToolbar], wrapping:
 * `[Mine N | All N] [search] [Channel] [Status] [Creator] [Assignee] [Clear all] [Sort] [List | Board] [⋯]`.
 *
 * The applied filters come first and are filled; Clear all ends them (not the search, which has
 * its ×). Status is a multi-select menu with checkboxes, staying open while picking (as the
 * Computers Runtime filter); Channel, Creator and Assignee open their searchable sheets
 * ([onFilterClick] with `channel`, `creator`, `assignee`). Sort offers the web's three orders.
 *
 * As the toolbar narrows the search may give way to its button; opened over the scope, system
 * Back first empties it, then closes it (as Esc, and as the Members toolbar), and only while it
 * covers the scope, so a search on its own line never swallows Back.
 */
@Composable
internal fun SharedTasksQueryToolbar(
    state: TasksQueryState,
    scopeCounts: Map<TasksPageScope, Int?>,
    onIntent: (TasksQueryIntent) -> Unit,
    selectedMode: TasksViewMode,
    onModeSelected: (TasksViewMode) -> Unit,
    openFilterId: String?,
    onFilterClick: (String) -> Unit,
) {
    val query = state.query
    var searchOpen by remember { mutableStateOf(false) }
    var searchOverScope by remember { mutableStateOf(false) }
    RaftBackHandler(enabled = searchOverScope, label = "tasks_toolbar_search") {
        if (state.searchDraft.isNotEmpty()) onIntent(TasksQueryIntent.SetSearch("")) else searchOpen = false
    }
    val scopeLabels =
        mapOf(
            TasksPageScope.Mine to stringResource(Res.string.tasks_scope_mine),
            TasksPageScope.All to stringResource(Res.string.tasks_scope_all),
        )
    val searchLabel = stringResource(Res.string.tasks_search_label)
    val channelLabel = stringResource(Res.string.tasks_chip_channel)
    val creatorLabel = stringResource(Res.string.tasks_chip_creator)
    val assigneeLabel = stringResource(Res.string.tasks_chip_assignee)
    // A filter that opens its sheet: its name, and how many it has chosen once applied (web "Assignee 1").
    fun sheetChoice(id: String, label: String, icon: RaftLucideIconName, selected: Int, tag: String): Pair<RaftFitToolbarChoice, Boolean> =
        RaftFitToolbarChoice(
            text = if (selected == 0) label else "$label $selected",
            icon = { SharedTasksQueryToolbarIcon(icon) },
            options = emptyList(),
            selectedKey = null,
            onSelect = {},
            applied = selected > 0 || openFilterId == id,
            testTag = tag,
            onOpen = { onFilterClick(id) },
        ) to (selected > 0)
    val status = tasksStatusFilterChoice(query.statuses, onIntent) to query.statuses.isNotEmpty()
    // Web order: Channel, Status, Creator, Assignee; the applied first.
    val filters =
        listOf(
            sheetChoice("channel", channelLabel, RaftLucideIconName.Hash, query.channelIds.size, SharedTasksToolbarTags.Channel),
            status,
            sheetChoice("creator", creatorLabel, RaftLucideIconName.CircleUser, query.creators.size, SharedTasksToolbarTags.Creator),
            sheetChoice("assignee", assigneeLabel, RaftLucideIconName.User, query.assignees.size, SharedTasksToolbarTags.Assignee),
        )
            .sortedByDescending { (_, applied) -> applied }
            .map { it.first }
    val boardLabel = stringResource(Res.string.tasks_view_board)
    val listLabel = stringResource(Res.string.tasks_view_list)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RaftColor.SurfaceWhite)
                .raftSafeAreaHorizontalPadding()
                .testTag(SharedTasksQueryToolbarTags.Toolbar)
    ) {
        RaftFitToolbar(
            scope =
                RaftFitToolbarScope(
                    options = TasksPageScope.entries.map { RaftFitToolbarOption(key = it.storageKey, label = scopeLabels.getValue(it), count = scopeCounts[it]) },
                    selectedKey = query.scope.storageKey,
                    onSelect = { key -> TasksPageScope.fromStorageKey(key)?.let { onIntent(TasksQueryIntent.SetScope(it)) } },
                    label = stringResource(Res.string.tasks_scope_label),
                    testTag = SharedTasksQueryToolbarTags.Scope,
                ),
            search =
                RaftFitToolbarSearch(
                    value = state.searchDraft,
                    onValueChange = { onIntent(TasksQueryIntent.SetSearch(it)) },
                    label = searchLabel,
                    clearLabel = stringResource(Res.string.list_toolbar_clear_search),
                    testTag = SharedTasksQueryToolbarTags.Search,
                    open = searchOpen,
                    onOpenChange = { searchOpen = it },
                    onShownOverScopeChange = { searchOverScope = it },
                ),
            filters = filters,
            filtersEnd =
                if (query.hasToolbarFilter) {
                    RaftFitToolbarAction(
                        text = stringResource(Res.string.tasks_action_clear_all),
                        icon = { SharedTasksQueryToolbarIcon(RaftLucideIconName.X) },
                        onClick = { onIntent(TasksQueryIntent.ClearFilters) },
                        testTag = SharedTasksToolbarTags.Clear,
                    )
                } else {
                    null
                },
            display = listOf(tasksSortChoice(query.sort, onIntent)),
            view =
                RaftFitToolbarView(
                    options =
                        listOf(TasksViewMode.List, TasksViewMode.Board).map { mode ->
                            RaftFitToolbarViewOption(
                                key = mode.name.lowercase(),
                                label = if (mode == TasksViewMode.Board) boardLabel else listLabel,
                                icon = {
                                    SharedTasksQueryToolbarIcon(if (mode == TasksViewMode.Board) RaftLucideIconName.Columns3 else RaftLucideIconName.LayoutList)
                                },
                            )
                        },
                    selectedKey = selectedMode.name.lowercase(),
                    onSelect = { key -> TasksViewMode.entries.firstOrNull { it.name.lowercase() == key }?.let(onModeSelected) },
                    label = stringResource(Res.string.tasks_view_label),
                    heading = stringResource(Res.string.list_toolbar_view),
                    testTag = SharedTasksToolbarTags.View,
                ),
            moreLabel = stringResource(Res.string.list_toolbar_more),
            wrap = true,
        )
    }
}

/**
 * Web `StatusFilterChip`: the board statuses, several at once, each with its checkbox; the menu
 * stays open while picking. One chosen says its name; several, "Status" and how many.
 */
@Composable
private fun tasksStatusFilterChoice(
    chosen: Set<TaskStatus>,
    onIntent: (TasksQueryIntent) -> Unit,
): RaftFitToolbarChoice {
    val filterName = stringResource(Res.string.tasks_chip_status)
    val labels = ServerTaskBoardStatuses.associateWith { it.localizedLabel() }
    val chosenInOrder = ServerTaskBoardStatuses.filter { it in chosen }
    val text =
        when (chosenInOrder.size) {
            0 -> filterName
            1 -> labels.getValue(chosenInOrder.first())
            else -> "$filterName ${chosenInOrder.size}"
        }
    return RaftFitToolbarChoice(
        text = text,
        icon = { SharedTasksQueryToolbarIcon(RaftLucideIconName.CircleDot) },
        options = emptyList(),
        selectedKey = null,
        onSelect = {},
        contentDescription = if (chosenInOrder.isEmpty()) filterName else "$filterName: ${chosenInOrder.joinToString(", ") { labels.getValue(it) }}",
        applied = chosenInOrder.isNotEmpty(),
        testTag = SharedTasksQueryToolbarTags.Status,
        key = SharedTasksQueryToolbarTags.Status,
        menu = { _ ->
            ServerTaskBoardStatuses.forEach { status ->
                RaftMenuItem(
                    text = labels.getValue(status),
                    modifier = Modifier.testTag("${SharedTasksQueryToolbarTags.Status}-${status.toWireValue()}"),
                    leadingIcon = { RaftCheckboxIndicator(checked = status in chosen) },
                    onClick = { onIntent(TasksQueryIntent.ToggleStatus(status)) },
                )
            }
        },
    )
}

/** Web `SortPicker`: the order's name on the trigger ("Sort: Recently updated" its accessible name). */
@Composable
private fun tasksSortChoice(
    sort: ServerTaskSort,
    onIntent: (TasksQueryIntent) -> Unit,
): RaftFitToolbarChoice {
    val labels =
        mapOf(
            ServerTaskSort.Activity to stringResource(Res.string.tasks_sort_activity),
            ServerTaskSort.ActivityAsc to stringResource(Res.string.tasks_sort_activity_asc),
            ServerTaskSort.Created to stringResource(Res.string.tasks_sort_created),
        )
    val label = labels.getValue(sort)
    return RaftFitToolbarChoice(
        text = label,
        icon = { SharedTasksQueryToolbarIcon(RaftLucideIconName.ArrowDownUp) },
        options = ServerTaskSort.entries.map { RaftFitToolbarOption(key = it.wire, label = labels.getValue(it)) },
        selectedKey = sort.wire,
        onSelect = { key -> ServerTaskSort.fromWire(key)?.let { onIntent(TasksQueryIntent.SetSort(it)) } },
        heading = stringResource(Res.string.list_toolbar_sort),
        contentDescription = stringResource(Res.string.tasks_sort_trigger_label, label),
        testTag = SharedTasksQueryToolbarTags.Sort,
        key = SharedTasksQueryToolbarTags.Sort,
    )
}

/** A toolbar icon filling the box its control gives it, in the control's ink. */
@Composable
private fun SharedTasksQueryToolbarIcon(name: RaftLucideIconName) {
    RaftLucideIcon(name, Modifier.fillMaxSize(), color = raftArgbHex(LocalRaftContentColor.current))
}
