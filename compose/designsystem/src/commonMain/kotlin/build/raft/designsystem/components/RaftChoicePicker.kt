package build.raft.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

// raft-design picker rule (Jasper, #proj-mobile): how one value is picked depends on how many
// values there are, counting every option (a "Hosted" choice too):
// - up to RaftChoicePickerRadioMax: a RadioGroup, every option visible with its description;
// - up to RaftComboboxSheetThreshold: a Select, which on a phone is a bottom sheet listing the
//   options (no search);
// - more: a searchable picker (ComboboxSelect), the bottom sheet with the search row.

/** The most options a [RaftChoicePicker] shows as radios; with more it is a Select. */
const val RaftChoicePickerRadioMax = 5

/** How a [RaftChoicePicker] with a given number of options is drawn. */
enum class RaftChoicePickerVariant {
    /** [RaftRadioGroup] of [RaftRadioRow]s, each option's description under its label. */
    RadioGroup,

    /** A Select: the trigger opens a bottom sheet listing the options, without search. */
    SheetList,

    /** ComboboxSelect: the trigger opens the bottom sheet with a search row. */
    SearchSheet,
}

/**
 * The variant for [optionCount] options: at most [RaftChoicePickerRadioMax] radios, at most
 * [RaftComboboxSheetThreshold] a sheet list, more a searchable sheet.
 */
fun raftChoicePickerVariant(optionCount: Int): RaftChoicePickerVariant =
    when {
        optionCount <= RaftChoicePickerRadioMax -> RaftChoicePickerVariant.RadioGroup
        optionCount <= RaftComboboxSheetThreshold -> RaftChoicePickerVariant.SheetList
        else -> RaftChoicePickerVariant.SearchSheet
    }

/**
 * Pick one of [options], drawn by how many there are ([raftChoicePickerVariant]):
 * - radios: a [RaftRadioGroup] of [RaftRadioRow]s with each option's
 *   [RaftComboboxOption.description] under its label;
 * - a sheet list or a searchable sheet: a [RaftSelectTrigger] (the field Select; the
 *   [placeholder] while nothing is chosen) that opens a bottom sheet titled [title] listing
 *   the options with their descriptions (`text-caption` `foreground-hint`), the chosen one
 *   bold with a check; the searchable one has the search row ([searchPlaceholder]) at its top
 *   and says [emptyText] of the query when nothing matches.
 *
 * Picking calls [onValueChange] (not for the value already chosen) and closes the sheet.
 * A disabled option ([RaftComboboxOption.enabled]) is greyed out and cannot be picked.
 *
 * Grouped options ([RaftComboboxOption.group]) list under their group's heading, radios too;
 * in the sheet a separator goes between groups, never under a heading (Raft DS Select). An
 * option's [RaftComboboxOption.badge] follows its label in the list and in the trigger.
 * [listAction] is a last row that changes what the list shows (after a separator in the sheet,
 * a ghost button under the radios): it runs without picking and leaves the sheet open.
 */
@Composable
fun <T> RaftChoicePicker(
    value: T?,
    options: List<RaftComboboxOption<T>>,
    onValueChange: (T) -> Unit,
    title: String,
    emptyText: (query: String) -> String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    searchPlaceholder: String = "Search",
    enabled: Boolean = LocalRaftFieldState.current.enabled,
    label: String? = LocalRaftFieldState.current.label,
    listAction: RaftComboboxListAction? = null,
) {
    when (val variant = raftChoicePickerVariant(options.size)) {
        RaftChoicePickerVariant.RadioGroup ->
            Column(modifier) {
                RaftRadioGroup {
                    raftComboboxRows(options).forEachIndexed { index, row ->
                        when (row) {
                            is RaftComboboxRow.Group -> RaftChoicePickerGroupLabel(row.label, first = index == 0)
                            is RaftComboboxRow.Option -> {
                                val option = options[row.index]
                                RaftRadioRow(
                                    label = option.label,
                                    selected = option.value == value,
                                    onClick = { if (option.value != value) onValueChange(option.value) },
                                    description = option.description,
                                    enabled = enabled && option.enabled,
                                    badge = option.badge,
                                )
                            }
                        }
                    }
                }
                if (listAction != null) {
                    RaftButton(
                        listAction.label,
                        listAction.onClick,
                        variant = RaftButtonVariant.Ghost,
                        size = RaftButtonSize.Md,
                        enabled = enabled,
                    )
                }
            }
        RaftChoicePickerVariant.SheetList, RaftChoicePickerVariant.SearchSheet -> {
            var open by remember { mutableStateOf(false) }
            var query by remember { mutableStateOf("") }
            val trigger = remember { FocusRequester() }
            Box(modifier) {
                RaftSelectTrigger(
                    value = value?.let { raftComboboxLabelOf(options, it) },
                    onClick = {
                        query = ""
                        open = true
                    },
                    modifier = Modifier.focusRequester(trigger),
                    placeholder = placeholder,
                    enabled = enabled,
                    label = label,
                    expanded = open,
                    onCollapse = { open = false },
                    badge = value?.let { raftComboboxBadgeOf(options, it) },
                )
                if (open) {
                    RaftSheetWindow(onDismissRequest = { open = false }) { surfaceModifier ->
                        RaftChoicePickerSheetSurface(
                            value = value,
                            options = options,
                            onPick = { picked ->
                                open = false
                                if (picked != value) onValueChange(picked)
                            },
                            title = title,
                            emptyText = emptyText,
                            searchable = variant == RaftChoicePickerVariant.SearchSheet,
                            query = query,
                            onQueryChange = { query = it },
                            searchPlaceholder = searchPlaceholder,
                            modifier = surfaceModifier,
                            inWindow = true,
                            listAction = listAction,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The bottom sheet of a [RaftChoicePicker] without its window, for fixtures and screenshots:
 * [title], the search row when [searchable], then the [options] (filtered by [query]) with
 * their descriptions; the chosen [value] bold with a check.
 */
@Composable
fun <T> RaftChoicePickerSheetSurface(
    value: T?,
    options: List<RaftComboboxOption<T>>,
    onPick: (T) -> Unit,
    title: String,
    emptyText: (query: String) -> String,
    searchable: Boolean,
    modifier: Modifier = Modifier,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    searchPlaceholder: String = "Search",
    inWindow: Boolean = false,
    listAction: RaftComboboxListAction? = null,
) {
    RaftComboboxSheetSurface(
        title = title,
        query = if (searchable) query else "",
        onQueryChange = onQueryChange,
        options = options,
        isSelected = { it == value },
        onPick = { onPick(it.value) },
        emptyText = emptyText,
        modifier = modifier,
        searchPlaceholder = searchPlaceholder,
        inWindow = inWindow,
        searchable = searchable,
        separateGroups = true,
        listAction = listAction,
    )
}

/** A group heading among radios: `text-caption` bold `foreground-hint`, 12dp above a later group. */
@Composable
private fun RaftChoicePickerGroupLabel(text: String, first: Boolean) {
    RaftText(
        text,
        Modifier.padding(top = if (first) 0.dp else 12.dp).semantics { heading() },
        style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
        color = RaftTheme.colors.foregroundHint,
    )
}
