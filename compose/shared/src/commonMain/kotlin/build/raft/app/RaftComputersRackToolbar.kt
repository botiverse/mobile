package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
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
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCheckboxIndicator
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuSeparator
import build.raft.designsystem.components.RaftPickerTriggerButton
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextFieldClearAction
import build.raft.designsystem.components.RaftTextFieldSize
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius

/*
 * The Computers page's toolbar on a phone (task #proj-mobile:#60 step 2): web
 * features/computer/ComputersRackToolbar.tsx in its narrow, two-line form (slock staging 573130e3).
 *
 * - Line 1: the search filling the line, then Group and Sort as their icons (FitToolbar's
 *   `displayLabels` step), each filled `info-soft` once it differs from its default.
 * - Line 2: Status and Runtime with their words, the applied first, scrolling sideways; Clear all
 *   as its × pinned at the end while the search, Status or Runtime is set.
 *
 * Cards / List stay in the page head (as Artifacts' view toggle does), Add Computer on the counts
 * line. The logic is [RaftComputersRackToolbarModel]'s; this only draws it and sends intents.
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
}

@Composable
internal fun RaftComputersRackToolbar(
    state: RaftComputersRackToolbarState,
    model: RaftComputersRackToolbarModel,
    onIntent: (RaftComputersRackToolbarIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().background(RaftTheme.colors.layerCanvas).testTag(RaftComputersRackTags.Toolbar)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val searchLabel = stringResource(Res.string.settings_computers_rack_search_label)
                val query = state.filters.query
                RaftTextField(
                    value = query,
                    onValueChange = { onIntent(RaftComputersRackToolbarIntent.Search(it)) },
                    modifier = Modifier.weight(1f).testTag(RaftComputersRackTags.Search).semantics { contentDescription = searchLabel },
                    placeholder = stringResource(Res.string.settings_computers_rack_search_placeholder),
                    leading = { RaftComputersIcon(RaftLucideIconName.Search, size = 14.dp) },
                    trailingAction =
                        if (query.isNotEmpty()) {
                            {
                                RaftTextFieldClearAction(
                                    onClear = { onIntent(RaftComputersRackToolbarIntent.Search("")) },
                                    label = stringResource(Res.string.settings_computers_rack_search_clear),
                                )
                            }
                        } else {
                            null
                        },
                    size = RaftTextFieldSize.Md,
                )
                RaftRackGroupPicker(state.groupBy, onIntent)
                RaftRackSortPicker(model, onIntent)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val statusApplied = model.filters.status != RaftComputersRackStatusFilter.All
                val runtimeApplied = model.filters.runtimes.isNotEmpty()
                // Web: the applied filters first, so they are in view before any scrolling.
                val filters =
                    listOf(RaftRackFilterSlot.Status to statusApplied, RaftRackFilterSlot.Runtime to runtimeApplied)
                        .filter { (slot, _) -> slot != RaftRackFilterSlot.Runtime || model.runtimeOptions.isNotEmpty() }
                        .sortedByDescending { (_, applied) -> applied }
                        .map { it.first }
                LazyRow(
                    Modifier.weight(1f, fill = false),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    filters.forEach { slot ->
                        item(key = slot.name) {
                            when (slot) {
                                RaftRackFilterSlot.Status -> RaftRackStatusPicker(model, onIntent)
                                RaftRackFilterSlot.Runtime -> RaftRackRuntimePicker(model, onIntent)
                            }
                        }
                    }
                }
                if (model.filtered) {
                    // Web: on a phone Clear all is its × (a ghost icon button), named by its words.
                    RaftIconButton(
                        onClick = { onIntent(RaftComputersRackToolbarIntent.ClearFilters) },
                        tooltip = stringResource(Res.string.settings_computers_rack_filter_clear_all),
                        modifier = Modifier.testTag(RaftComputersRackTags.Clear),
                        variant = RaftButtonVariant.Ghost,
                    ) { RaftComputersIcon(RaftLucideIconName.X) }
                }
            }
        }
        RaftSeparator()
    }
}

private enum class RaftRackFilterSlot { Status, Runtime }

/** Web `RackStatusSelect`: one value, named "Status: All statuses"; filled once it is not All. */
@Composable
private fun RaftRackStatusPicker(model: RaftComputersRackToolbarModel, onIntent: (RaftComputersRackToolbarIntent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val status = model.filters.status
    val value = raftRackStatusLabel(status)
    val name = stringResource(Res.string.settings_computers_rack_filter_applied, stringResource(Res.string.settings_computers_rack_filter_status), value)
    Box {
        RaftPickerTriggerButton(
            text = value,
            onClick = { open = true },
            modifier = Modifier.testTag(RaftComputersRackTags.Status).semantics { contentDescription = name },
            hasValue = status != RaftComputersRackStatusFilter.All,
            leadingIcon = { RaftComputersIcon(RaftLucideIconName.CircleDot, size = 14.dp) },
        )
        RaftDropdownMenu(expanded = open, onDismissRequest = { open = false }, minWidth = 200.dp) {
            RaftComputersRackStatusFilter.entries.forEach { option ->
                RaftMenuItem(
                    text = raftRackStatusLabel(option),
                    checked = option == status,
                    modifier = Modifier.testTag("${RaftComputersRackTags.Status}-${option.name.lowercase()}"),
                    onClick = {
                        open = false
                        onIntent(RaftComputersRackToolbarIntent.SetStatus(option))
                    },
                )
            }
        }
    }
}

/**
 * Web `RackRuntimeSelect`: several values, each with its checkbox; the list stays open while
 * picking. One chosen says its name; several, "Runtime" and how many.
 */
@Composable
private fun RaftRackRuntimePicker(model: RaftComputersRackToolbarModel, onIntent: (RaftComputersRackToolbarIntent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val chosen = model.filters.runtimes
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
    Box {
        RaftPickerTriggerButton(
            text = text,
            onClick = { open = true },
            modifier = Modifier.testTag(RaftComputersRackTags.Runtime).semantics { contentDescription = name },
            hasValue = chosen.isNotEmpty(),
            leadingIcon = { RaftComputersIcon(RaftLucideIconName.Cpu, size = 14.dp) },
        )
        RaftDropdownMenu(expanded = open, onDismissRequest = { open = false }, minWidth = 200.dp) {
            model.runtimeOptions.forEach { option ->
                RaftMenuItem(
                    text = raftRackRuntimeLabel(option),
                    modifier = Modifier.testTag("${RaftComputersRackTags.Runtime}-${option.name.lowercase()}"),
                    leadingIcon = { RaftCheckboxIndicator(checked = option in chosen) },
                    onClick = { onIntent(RaftComputersRackToolbarIntent.ToggleRuntime(option, model.runtimeOptions)) },
                )
            }
        }
    }
}

/** Web `RackGroupSelect`, as its icon on a phone: named "Group" or "Group: Name prefix"; filled once grouped. */
@Composable
private fun RaftRackGroupPicker(groupBy: ComputersRackGroupBy, onIntent: (RaftComputersRackToolbarIntent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val grouped = groupBy != ComputersRackGroupBy.None
    val name =
        if (grouped) {
            stringResource(Res.string.settings_computers_rack_group_button, raftRackGroupByLabel(groupBy))
        } else {
            stringResource(Res.string.settings_computers_rack_group_label)
        }
    Box {
        RaftPickerTriggerButton(
            text = "",
            onClick = { open = true },
            modifier = Modifier.testTag(RaftComputersRackTags.Group).semantics { contentDescription = name },
            hasValue = grouped,
            leadingIcon = { RaftComputersIcon(RaftLucideIconName.Rows3, size = 14.dp) },
        )
        RaftDropdownMenu(expanded = open, onDismissRequest = { open = false }, alignEnd = true, minWidth = 200.dp) {
            ComputersRackGroupBy.entries.forEach { option ->
                RaftMenuItem(
                    text = raftRackGroupByLabel(option),
                    checked = option == groupBy,
                    modifier = Modifier.testTag("${RaftComputersRackTags.Group}-${option.storageKey}"),
                    onClick = {
                        open = false
                        onIntent(RaftComputersRackToolbarIntent.SetGroupBy(option))
                    },
                )
            }
        }
    }
}

/**
 * Web `RackSortSelect`, as its icon on a phone: the keys (Name; what each runtime has left; Disk,
 * Memory), then the direction in its own group, each checked. A new key starts at its own first
 * direction; the list stays open while choosing. Filled once it is not Name, A to Z.
 */
@Composable
private fun RaftRackSortPicker(model: RaftComputersRackToolbarModel, onIntent: (RaftComputersRackToolbarIntent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val sort = model.sort
    val name =
        stringResource(Res.string.settings_computers_rack_sort_label, raftRackSortKeyLabel(sort.key)) + ", " +
            raftRackSortDirectionLabel(sort)
    Box {
        RaftPickerTriggerButton(
            text = "",
            onClick = { open = true },
            modifier = Modifier.testTag(RaftComputersRackTags.Sort).semantics { contentDescription = name },
            hasValue = model.sortApplied,
            leadingIcon = { RaftComputersIcon(RaftLucideIconName.ArrowDownUp, size = 14.dp) },
        )
        RaftDropdownMenu(expanded = open, onDismissRequest = { open = false }, alignEnd = true, minWidth = 220.dp) {
            model.sortMenuSections.forEachIndexed { index, keys ->
                if (index > 0) RaftMenuSeparator()
                keys.forEach { key ->
                    RaftMenuItem(
                        text = raftRackSortKeyLabel(key),
                        checked = key == sort.key,
                        modifier = Modifier.testTag("${RaftComputersRackTags.Sort}-${key.wire}"),
                        onClick = { onIntent(RaftComputersRackToolbarIntent.ChooseSortKey(key, sort)) },
                    )
                }
            }
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
    }
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

@Composable
internal fun raftRackSortKeyLabel(key: ComputersRackSortKey): String =
    when (key) {
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
