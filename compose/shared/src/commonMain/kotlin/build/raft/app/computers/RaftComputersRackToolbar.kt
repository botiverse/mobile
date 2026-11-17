package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.data.ComputersRackGroupBy
import build.raft.app.data.ComputersRackSort
import build.raft.app.data.ComputersRackSortKey
import build.raft.app.data.ComputersRackView
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftCheckboxIndicator
import build.raft.designsystem.components.RaftFitToolbar
import build.raft.designsystem.components.RaftFitToolbarAction
import build.raft.designsystem.components.RaftFitToolbarChoice
import build.raft.designsystem.components.RaftFitToolbarOption
import build.raft.designsystem.components.RaftFitToolbarPrimary
import build.raft.designsystem.components.RaftFitToolbarSearch
import build.raft.designsystem.components.RaftFitToolbarView
import build.raft.designsystem.components.RaftFitToolbarViewOption
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuSeparator
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius

/*
 * The Computers page's toolbar, the toolbar over a list on [RaftFitToolbar] (web
 * features/computer/ComputersRackToolbar.tsx, slock staging 80e1ce7049; raft-design
 * computers.md › The toolbar), the same on a phone and a wide window:
 *
 *   [search] [Status] [Runtime] [Clear all] [Group] [Sort] [Cards | List] [⋯] [Add Computer]
 *
 * The search fills the line; Status and Runtime are filled `info-soft` once applied and the
 * applied come first; Clear all ends them while the search, Status or Runtime is set. Group and
 * Sort (its key and, in their own group, its direction) are filled once off their defaults. As
 * the toolbar narrows it gives way by itself: Group, Sort and the view switch show their icons,
 * the filters theirs, the filters move to a scrolling second line with their words back (Clear
 * all as ×), Add Computer becomes +, Group, Sort and the view fold into ⋯, and last an empty
 * search becomes its button (system Back closes it, as Esc does: the text first).
 *
 * The logic is [RaftComputersRackToolbarModel]'s; this only draws it and sends intents.
 */

/** Test tags (web `data-testid`s). */
internal object RaftComputersRackTags {
    const val Toolbar = "computers-rack-toolbar"
    const val Search = "computers-rack-search"
    const val Status = "computers-rack-status"
    const val Runtime = "computers-rack-runtime"
    const val Group = "computers-rack-group"
    const val Sort = "computers-rack-sort"
    const val Clear = "computers-rack-clear"
    const val View = "computers-rack-view"
    const val Add = "computers-rack-add"
    const val EmptyClear = "computers-rack-empty-clear"
    const val GroupTitle = "rack-group"
    const val OfflineTitle = "rack-group-offline"
    const val CardMeters = "rack-card-meters"
    const val ListHeader = "computers-rack-header"
    const val MenuDelete = "rack-row-delete"
    const val DeleteConfirm = "computers-delete-confirm"
    const val ActionError = "computers-action-error"
    const val UpgradeHint = "rack-upgrade-hint"
    const val ManualUpgrade = "rack-manual-upgrade"
    const val InstallSheet = "rack-install-sheet"
    const val LoadingSkeleton = "computers-loading-skeleton"
}

/**
 * The toolbar under the Computers head. [view] is the list's Cards / List; [onAddComputer] is Add
 * Computer at the end, given where the person may add computers (null hides it).
 */
@Composable
internal fun RaftComputersRackToolbar(
    state: RaftComputersRackToolbarState,
    model: RaftComputersRackToolbarModel,
    onIntent: (RaftComputersRackToolbarIntent) -> Unit,
    view: ComputersRackView,
    onAddComputer: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val query = state.filters.query
    // A search given way to a button and opened: system Back closes it (AGENTS.md › Back Handling).
    var searchOpen by remember { mutableStateOf(false) }
    var searchShown by remember { mutableStateOf(false) }
    // As Esc: the text first, then the search itself. Only while it is drawn over the line's start,
    // so Back is never swallowed by an inline search (a wide toolbar).
    RaftBackHandler(enabled = searchShown, label = "computers_toolbar_search") {
        if (query.isNotEmpty()) onIntent(RaftComputersRackToolbarIntent.Search("")) else searchOpen = false
    }
    val statusApplied = model.filters.status != RaftComputersRackStatusFilter.All
    val runtimeApplied = model.filters.runtimes.isNotEmpty()
    val status = raftRackStatusChoice(model, onIntent)
    val runtime = if (model.runtimeOptions.isNotEmpty()) raftRackRuntimeChoice(model, onIntent) else null
    // Web: the applied filters first, so they are in view before any scrolling.
    val filters =
        listOfNotNull(status to statusApplied, runtime?.let { it to runtimeApplied })
            .sortedByDescending { (_, applied) -> applied }
            .map { it.first }
    val clearAllLabel = stringResource(Res.string.settings_computers_rack_filter_clear_all)
    val addLabel = stringResource(Res.string.settings_computers_add_computer)
    Box(modifier.fillMaxWidth().background(RaftTheme.colors.layerCanvas).testTag(RaftComputersRackTags.Toolbar)) {
        RaftFitToolbar(
            search =
                RaftFitToolbarSearch(
                    value = query,
                    onValueChange = { onIntent(RaftComputersRackToolbarIntent.Search(it)) },
                    label = stringResource(Res.string.settings_computers_rack_search_label),
                    placeholder = stringResource(Res.string.settings_computers_rack_search_placeholder),
                    clearLabel = stringResource(Res.string.list_toolbar_clear_search),
                    testTag = RaftComputersRackTags.Search,
                    open = searchOpen,
                    onOpenChange = { searchOpen = it },
                    onShownOverScopeChange = { searchShown = it },
                ),
            filters = filters,
            filtersEnd =
                if (model.filtered) {
                    RaftFitToolbarAction(
                        text = clearAllLabel,
                        icon = { RaftRackToolbarIcon(RaftLucideIconName.X) },
                        onClick = { onIntent(RaftComputersRackToolbarIntent.ClearFilters) },
                        testTag = RaftComputersRackTags.Clear,
                    )
                } else {
                    null
                },
            display = listOf(raftRackGroupChoice(state.groupBy, onIntent), raftRackSortChoice(model, onIntent)),
            view = raftRackViewSwitch(view, onIntent),
            primary =
                onAddComputer?.let { add ->
                    RaftFitToolbarPrimary(
                        text = addLabel,
                        onClick = add,
                        icon = { RaftRackToolbarIcon(RaftLucideIconName.Plus) },
                        testTag = RaftComputersRackTags.Add,
                    )
                },
            moreLabel = stringResource(Res.string.list_toolbar_more),
            wrap = true,
        )
    }
}

/** A toolbar icon filling the box its control gives it, in the control's ink. */
@Composable
private fun RaftRackToolbarIcon(name: RaftLucideIconName) {
    RaftLucideIcon(name, Modifier.fillMaxSize(), color = raftArgbHex(LocalRaftContentColor.current))
}

/** Web `RackStatusSelect`: one value, named "Status: All statuses"; filled once it is not All. */
@Composable
private fun raftRackStatusChoice(
    model: RaftComputersRackToolbarModel,
    onIntent: (RaftComputersRackToolbarIntent) -> Unit,
): RaftFitToolbarChoice {
    val status = model.filters.status
    val value = raftRackStatusLabel(status)
    val name = stringResource(Res.string.settings_computers_rack_filter_applied, stringResource(Res.string.settings_computers_rack_filter_status), value)
    val options = RaftComputersRackStatusFilter.entries.map { RaftFitToolbarOption(key = it.name.lowercase(), label = raftRackStatusLabel(it)) }
    return RaftFitToolbarChoice(
        text = value,
        icon = { RaftRackToolbarIcon(RaftLucideIconName.CircleDot) },
        options = options,
        selectedKey = status.name.lowercase(),
        onSelect = { key ->
            RaftComputersRackStatusFilter.entries.firstOrNull { it.name.lowercase() == key }?.let { onIntent(RaftComputersRackToolbarIntent.SetStatus(it)) }
        },
        contentDescription = name,
        applied = status != RaftComputersRackStatusFilter.All,
        testTag = RaftComputersRackTags.Status,
    )
}

/**
 * Web `RackRuntimeSelect`: several values, each with its checkbox; the list stays open while
 * picking. One chosen says its name; several, "Runtime" and how many.
 */
@Composable
private fun raftRackRuntimeChoice(
    model: RaftComputersRackToolbarModel,
    onIntent: (RaftComputersRackToolbarIntent) -> Unit,
): RaftFitToolbarChoice {
    val chosen = model.filters.runtimes
    val options = model.runtimeOptions
    val filterName = stringResource(Res.string.settings_computers_rack_filter_runtime)
    val names = chosen.map { raftRackRuntimeLabel(it) }
    val text =
        when (chosen.size) {
            0 -> filterName
            1 -> names.first()
            else -> "$filterName ${chosen.size}"
        }
    val name =
        if (chosen.isEmpty()) {
            filterName
        } else {
            stringResource(Res.string.settings_computers_rack_filter_applied, filterName, names.joinToString(", "))
        }
    return RaftFitToolbarChoice(
        text = text,
        icon = { RaftRackToolbarIcon(RaftLucideIconName.Cpu) },
        options = emptyList(),
        selectedKey = null,
        onSelect = {},
        contentDescription = name,
        applied = chosen.isNotEmpty(),
        testTag = RaftComputersRackTags.Runtime,
        menu = { _ ->
            options.forEach { option ->
                RaftMenuItem(
                    text = raftRackRuntimeLabel(option),
                    modifier = Modifier.testTag("${RaftComputersRackTags.Runtime}-${option.name.lowercase()}"),
                    leadingIcon = { RaftCheckboxIndicator(checked = option in chosen) },
                    onClick = { onIntent(RaftComputersRackToolbarIntent.ToggleRuntime(option, options)) },
                )
            }
        },
    )
}

/** Web `RackGroupSelect`: "Group", or "Group: Name prefix" and filled once grouped. */
@Composable
private fun raftRackGroupChoice(
    groupBy: ComputersRackGroupBy,
    onIntent: (RaftComputersRackToolbarIntent) -> Unit,
): RaftFitToolbarChoice {
    val grouped = groupBy != ComputersRackGroupBy.None
    val label =
        if (grouped) {
            stringResource(Res.string.settings_computers_rack_group_button, raftRackGroupByLabel(groupBy))
        } else {
            stringResource(Res.string.settings_computers_rack_group_label)
        }
    val options = ComputersRackGroupBy.entries.map { RaftFitToolbarOption(key = it.storageKey, label = raftRackGroupByLabel(it)) }
    return RaftFitToolbarChoice(
        text = label,
        icon = { RaftRackToolbarIcon(RaftLucideIconName.Rows3) },
        options = options,
        selectedKey = groupBy.storageKey,
        onSelect = { key -> ComputersRackGroupBy.entries.firstOrNull { it.storageKey == key }?.let { onIntent(RaftComputersRackToolbarIntent.SetGroupBy(it)) } },
        heading = stringResource(Res.string.list_toolbar_group),
        contentDescription = label,
        applied = grouped,
        testTag = RaftComputersRackTags.Group,
    )
}

/**
 * Web `RackSortSelect`: "Sort: Name", named with its direction too. Its list holds the choices
 * (Mine first, Name; what each runtime has left; Disk, Memory), then, under a sort, the direction
 * in its own group, each checked. A new key starts at its own first direction; Mine first (the
 * default, no sort: the viewer's own computers first) has no direction. The list stays open while
 * choosing. Filled once a sort is chosen.
 */
@Composable
private fun raftRackSortChoice(
    model: RaftComputersRackToolbarModel,
    onIntent: (RaftComputersRackToolbarIntent) -> Unit,
): RaftFitToolbarChoice {
    val sort = model.sort
    val label = stringResource(Res.string.settings_computers_rack_sort_label, raftRackSortKeyLabel(sort?.key))
    val name = if (sort != null) label + ", " + raftRackSortDirectionLabel(sort) else label
    return RaftFitToolbarChoice(
        text = label,
        icon = { RaftRackToolbarIcon(RaftLucideIconName.ArrowDownUp) },
        options = emptyList(),
        selectedKey = null,
        onSelect = {},
        heading = stringResource(Res.string.list_toolbar_sort),
        contentDescription = name,
        applied = model.sortApplied,
        testTag = RaftComputersRackTags.Sort,
        menu = { _ -> RaftRackSortMenuItems(model, onIntent) },
    )
}

/** The Sort list: the choice sections, then (under a sort) the directions the key has, each checked. */
@Composable
private fun RaftRackSortMenuItems(model: RaftComputersRackToolbarModel, onIntent: (RaftComputersRackToolbarIntent) -> Unit) {
    val sort = model.sort
    model.sortMenuSections.forEachIndexed { index, keys ->
        if (index > 0) RaftMenuSeparator()
        keys.forEach { key ->
            RaftMenuItem(
                text = raftRackSortKeyLabel(key),
                checked = key == sort?.key,
                modifier = Modifier.testTag("${RaftComputersRackTags.Sort}-${key?.wire ?: RAFT_RACK_SORT_MINE_FIRST_WIRE}"),
                onClick = { onIntent(RaftComputersRackToolbarIntent.ChooseSortKey(key, sort)) },
            )
        }
    }
    if (sort == null) return
    RaftMenuSeparator()
    raftRackSortDirections(sort.key).forEach { direction ->
        RaftMenuItem(
            text = raftRackSortDirectionLabel(sort.copy(direction = direction)),
            checked = direction == sort.direction,
            modifier = Modifier.testTag("${RaftComputersRackTags.Sort}-direction-${direction.wire}"),
            onClick = { onIntent(RaftComputersRackToolbarIntent.ChooseSortDirection(direction, sort)) },
        )
    }
}

/** Web `RackViewSwitch`: Cards / List (the default first, stdrc 11-05), kept on this device. */
@Composable
private fun raftRackViewSwitch(view: ComputersRackView, onIntent: (RaftComputersRackToolbarIntent) -> Unit): RaftFitToolbarView {
    val views = listOf(ComputersRackView.Cards, ComputersRackView.List)
    val cards = stringResource(Res.string.settings_computers_rack_view_cards)
    val list = stringResource(Res.string.settings_computers_rack_view_list)
    return RaftFitToolbarView(
        options =
            views.map { option ->
                RaftFitToolbarViewOption(
                    key = option.storageKey,
                    label = if (option == ComputersRackView.Cards) cards else list,
                    icon = {
                        RaftRackToolbarIcon(if (option == ComputersRackView.Cards) RaftLucideIconName.LayoutGrid else RaftLucideIconName.LayoutList)
                    },
                )
            },
        selectedKey = view.storageKey,
        onSelect = { key -> views.firstOrNull { it.storageKey == key }?.let { onIntent(RaftComputersRackToolbarIntent.SetView(it)) } },
        label = stringResource(Res.string.settings_computers_rack_view_label),
        heading = stringResource(Res.string.list_toolbar_view),
        testTag = RaftComputersRackTags.View,
    )
}

/**
 * Web `RackGroupTitle` with a fold: a chevron, the group's name and how many it holds in machine
 * type ("Offline 3 computers"). The whole line is the fold's button, named "Collapse Offline, 3
 * computers" / "Expand …".
 */
@Composable
internal fun RaftRackFoldTitle(
    title: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val counts = raftRackComputerCount(count)
    val name =
        stringResource(
            if (collapsed) Res.string.settings_computers_rack_group_expand else Res.string.settings_computers_rack_group_collapse,
            title,
            counts,
        )
    Row(
        modifier
            .fillMaxWidth()
            .raftClickable(onClick = onToggle, shape = RoundedCornerShape(RaftRadius.Control))
            .semantics(mergeDescendants = true) { contentDescription = name }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.rotate(if (collapsed) -90f else 0f)) {
            CompositionLocalProvider(LocalRaftContentColor provides RaftTheme.colors.foregroundHint) {
                RaftComputersIcon(RaftLucideIconName.ChevronDown, size = 14.dp)
            }
        }
        RaftText(
            title,
            modifier = Modifier.weight(1f, fill = false),
            style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.Bold),
            color = RaftTheme.colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        RaftText(counts, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// Words (web computersRackToolbarModel.ts labels)

@Composable
internal fun raftRackComputerCount(count: Int): String =
    if (count == 1) {
        stringResource(Res.string.settings_computers_rack_computer_count_one, count)
    } else {
        stringResource(Res.string.settings_computers_rack_computer_count_other, count)
    }

@Composable
internal fun raftRackStatusLabel(status: RaftComputersRackStatusFilter): String =
    stringResource(
        when (status) {
            RaftComputersRackStatusFilter.All -> Res.string.settings_computers_rack_filter_status_all
            RaftComputersRackStatusFilter.Online -> Res.string.settings_computers_rack_filter_status_online
            RaftComputersRackStatusFilter.Offline -> Res.string.settings_computers_rack_filter_status_offline
            RaftComputersRackStatusFilter.Attention -> Res.string.settings_computers_rack_filter_status_attention
        },
    )

@Composable
internal fun raftRackRuntimeLabel(runtime: RaftComputersRackRuntimeFilter): String =
    runtime.provider?.let(::raftRackProviderName) ?: stringResource(Res.string.settings_computers_rack_filter_runtime_other)

@Composable
internal fun raftRackGroupByLabel(groupBy: ComputersRackGroupBy): String =
    stringResource(
        when (groupBy) {
            ComputersRackGroupBy.None -> Res.string.settings_computers_rack_group_none
            ComputersRackGroupBy.Prefix -> Res.string.settings_computers_rack_group_prefix
            ComputersRackGroupBy.Provider -> Res.string.settings_computers_rack_group_provider
        },
    )

/** Mine first's test tag suffix, web `key:default`. */
internal const val RAFT_RACK_SORT_MINE_FIRST_WIRE = "default"

/** "Mine first" (null, no sort), "Name", "Disk used", "Claude Code remaining". */
@Composable
internal fun raftRackSortKeyLabel(key: ComputersRackSortKey?): String =
    when (key) {
        null -> stringResource(Res.string.settings_computers_rack_sort_default)
        ComputersRackSortKey.Name -> stringResource(Res.string.settings_computers_rack_sort_name)
        ComputersRackSortKey.Disk -> stringResource(Res.string.settings_computers_rack_sort_disk)
        ComputersRackSortKey.Memory -> stringResource(Res.string.settings_computers_rack_sort_memory)
        is ComputersRackSortKey.Remaining -> stringResource(Res.string.settings_computers_rack_sort_remaining, raftRackProviderName(key.provider))
    }

@Composable
internal fun raftRackSortDirectionLabel(sort: ComputersRackSort): String =
    stringResource(
        when (raftRackSortDirectionCopy(sort.key, sort.direction)) {
            RaftRackSortDirectionCopy.NameAsc -> Res.string.settings_computers_rack_sort_name_asc
            RaftRackSortDirectionCopy.NameDesc -> Res.string.settings_computers_rack_sort_name_desc
            RaftRackSortDirectionCopy.RemainingDesc -> Res.string.settings_computers_rack_sort_remaining_desc
            RaftRackSortDirectionCopy.RemainingAsc -> Res.string.settings_computers_rack_sort_remaining_asc
            RaftRackSortDirectionCopy.UsedDesc -> Res.string.settings_computers_rack_sort_used_desc
            RaftRackSortDirectionCopy.UsedAsc -> Res.string.settings_computers_rack_sort_used_asc
        },
    )
