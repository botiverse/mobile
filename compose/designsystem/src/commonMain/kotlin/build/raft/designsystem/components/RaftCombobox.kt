package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

// raft-ui 0.10.3 Combobox / ComboboxSelect (combobox.tsx, combobox-select.css) on a phone.
// Where the list opens (raft-ui team, #proj-raft-ui):
// - picking one of a few values (at most [RaftComboboxSheetThreshold]): the anchored list
//   of a Select, no search, no keyboard;
// - picking one of more: a bottom sheet with the search row at its top and the list filling
//   it; the search row is not focused, so the keyboard comes up only when it is tapped;
// - typing a new value (`create`) or picking several: always the sheet; with `create` the
//   search row is focused on open (keyboard up) and the list sits between it and the
//   keyboard.
// Rows are the menu's: the highlight is `brand-soft`, the current value is bold with a
// check, never a fill (States > Selected: value selection).

/**
 * The most values a one-value combobox lists in an anchored Select list; with more it
 * opens the bottom sheet with search. raft-design Select: "One of up to 10 values" is a
 * Select; more ("One or a few of many values, with search") is ComboboxSelect, which on a
 * phone is a bottom sheet (Page structure, overlays on a phone).
 */
const val RaftComboboxSheetThreshold = 10

/** Where a combobox's list opens. */
internal enum class RaftComboboxPresentation {
    /** Under the trigger, as wide as it: a Select's list ([RaftDropdownMenu]). */
    AnchoredList,

    /** A bottom sheet with the search row at the top. */
    Sheet,
}

/**
 * Where the list of a combobox with [optionCount] options opens: a [multiple] pick, a
 * combobox that [creates] values, or more than [RaftComboboxSheetThreshold] options take
 * the sheet; anything else is a Select's anchored list.
 */
internal fun raftComboboxPresentation(optionCount: Int, creates: Boolean, multiple: Boolean): RaftComboboxPresentation =
    if (multiple || creates || optionCount > RaftComboboxSheetThreshold) RaftComboboxPresentation.Sheet else RaftComboboxPresentation.AnchoredList

/**
 * Whether the sheet's search row takes focus (and the keyboard) when it opens: only when
 * typing is the point, a combobox that [creates] values. A pure pick waits for a tap on it.
 */
internal fun raftComboboxFocusesSearch(creates: Boolean): Boolean = creates

/**
 * One value of a [RaftCombobox] / [RaftMultiCombobox]: its [label] (what the trigger and
 * the row show, and what search matches), an optional [description] (a second line that
 * says what the value means, `text-caption` `foreground-hint`; never the label again in
 * another form), the [group] it is listed under (consecutive options with the same group
 * share one group label), [keywords] that search matches too (an id, an email, a former
 * name), and whether it can be picked.
 */
@Immutable
data class RaftComboboxOption<out T>(
    val value: T,
    val label: String,
    val description: String? = null,
    val group: String? = null,
    val keywords: List<String> = emptyList(),
    val enabled: Boolean = true,
)

/**
 * The options a combobox lists for [query]: every option when the query is blank, else
 * those where each word of the query (split on white space) is found, ignoring case, in
 * the label or in one of the keywords. Order is kept, so groups stay together.
 */
fun <T> raftComboboxFilter(options: List<RaftComboboxOption<T>>, query: String): List<RaftComboboxOption<T>> {
    val terms = query.trim().split(RaftComboboxWhitespace).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return options
    return options.filter { option ->
        terms.all { term ->
            option.label.contains(term, ignoreCase = true) || option.keywords.any { it.contains(term, ignoreCase = true) }
        }
    }
}

private val RaftComboboxWhitespace = Regex("\\s+")

/**
 * The value a `create` combobox offers to use for [query] (ComboboxSelect `create`): the
 * trimmed text, unless it is blank or is exactly (ignoring case and surrounding space) the
 * label of one of [options] or one of the [taken] labels (the values already chosen, which
 * may not be options).
 */
internal fun raftComboboxCreateQuery(options: List<RaftComboboxOption<*>>, query: String, taken: List<String> = emptyList()): String? {
    val typed = query.trim()
    if (typed.isEmpty()) return null
    val exists = options.any { it.label.trim().equals(typed, ignoreCase = true) } || taken.any { it.trim().equals(typed, ignoreCase = true) }
    return if (exists) null else typed
}

/** The "Use “text”" row of a `create` combobox (raft-ui's `renderCreateLabel`). */
val RaftComboboxDefaultCreateLabel: (String) -> String = { "Use “$it”" }

/** One row of a combobox list: a group label, or the option at [Option.index] of the filtered list. */
internal sealed interface RaftComboboxRow {
    data class Group(val label: String) : RaftComboboxRow

    data class Option(val index: Int) : RaftComboboxRow
}

/**
 * The rows that list [options] (already filtered): a [RaftComboboxRow.Group] label before
 * each run of options of one group, so a group whose options all failed to match loses
 * its label too. Options without a group get no label.
 */
internal fun raftComboboxRows(options: List<RaftComboboxOption<*>>): List<RaftComboboxRow> {
    val rows = ArrayList<RaftComboboxRow>(options.size)
    var current: String? = null
    options.forEachIndexed { index, option ->
        val group = option.group
        if (group != null && group != current) rows += RaftComboboxRow.Group(group)
        current = group
        rows += RaftComboboxRow.Option(index)
    }
    return rows
}

/** The trigger's text for [value]: its option's label, or the value itself while its option hasn't loaded (or was typed). */
internal fun <T> raftComboboxLabelOf(options: List<RaftComboboxOption<T>>, value: T): String =
    options.firstOrNull { it.value == value }?.label ?: value.toString()

/**
 * raft-ui ComboboxSelect, one value. The trigger is the field Select's
 * ([RaftSelectTrigger]: 44dp minimum, control radius, 1dp `line-field` frame on
 * `layer-card`, the value or the [placeholder] in `foreground-hint`, a chevron; [invalid]
 * turns the frame `danger-strong`; disabled greys it). Inside a [RaftField] it takes the
 * field's label, error and type, as a Select does.
 *
 * Where a tap opens the [options] ([raftComboboxPresentation]):
 * - at most [RaftComboboxSheetThreshold] and no [create]: the Select's list under the
 *   trigger, exactly as wide as it (no search, no keyboard); keyboard navigation and focus
 *   return as in [RaftSelect];
 * - more, or [create]: a bottom sheet titled [title] (default: the field's label, else the
 *   placeholder) with a search row at its top ([searchPlaceholder]) and the list filling
 *   the sheet, filtered as people type ([raftComboboxFilter]). Without [create] the search
 *   row waits for a tap (no keyboard on open); with it the search row is focused on open.
 *
 * Options are grouped by [RaftComboboxOption.group]; the current value is bold with a
 * check. Picking one calls [onValueChange] and closes the list; so do Back and a tap
 * outside, without a change. When nothing matches, the sheet says [emptyText] of the typed
 * text, as what found nothing ("No models match \"zz\""). With [onClear], a [clearLabel]
 * text button at the end of the sheet's title clears the value.
 *
 * [create]: people may type a value that is not an option (a custom model id). Unless the
 * text is exactly an option's label (or the current value's), the first row offers it as
 * [createLabel] of the text ("Use “my-model”", with a + glyph); picking that row calls
 * [create] with the trimmed text and closes the sheet.
 *
 * [optionLeading] draws an icon (16dp, `foreground-muted` as the content colour) or an
 * avatar before an option's label.
 *
 * Screen readers hear the trigger as a drop-down list named by the label with its value
 * (or the placeholder) and whether it is expanded; each option is one focus stop, read as
 * selectable with its selected state, then its description.
 */
@Composable
fun <T> RaftCombobox(
    value: T?,
    options: List<RaftComboboxOption<T>>,
    onValueChange: (T) -> Unit,
    emptyText: (query: String) -> String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    title: String? = null,
    searchPlaceholder: String = "Search",
    onClear: (() -> Unit)? = null,
    clearLabel: String = "Clear",
    create: ((String) -> Unit)? = null,
    createLabel: (String) -> String = RaftComboboxDefaultCreateLabel,
    enabled: Boolean = LocalRaftFieldState.current.enabled,
    invalid: Boolean = LocalRaftFieldState.current.invalid,
    label: String? = LocalRaftFieldState.current.label,
    optionLeading: (@Composable (RaftComboboxOption<T>) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var triggerWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val trigger = remember { FocusRequester() }
    val inField = LocalRaftFieldState.current.inField
    val shown = value?.let { raftComboboxLabelOf(options, it) }
    val presentation = raftComboboxPresentation(options.size, creates = create != null, multiple = false)
    Box(modifier) {
        RaftSelectTrigger(
            value = shown,
            onClick = {
                query = ""
                open = true
            },
            modifier = Modifier.focusRequester(trigger).onSizeChanged { triggerWidth = it.width },
            placeholder = placeholder,
            enabled = enabled,
            invalid = invalid,
            label = label,
            expanded = open,
            onCollapse = { open = false },
        )
        if (presentation == RaftComboboxPresentation.AnchoredList) {
            RaftDropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                width = maxOf(220.dp, with(density) { triggerWidth.toDp() }),
                offset = 4.dp,
                returnFocusTo = trigger,
            ) {
                CompositionLocalProvider(LocalRaftMenuFieldType provides inField) {
                    RaftComboboxMenuRows(
                        options = options,
                        isSelected = { it == value },
                        onPick = { option ->
                            open = false
                            if (option.value != value) onValueChange(option.value)
                        },
                        emptyText = emptyText,
                        optionLeading = optionLeading,
                    )
                }
            }
        } else if (open) {
            RaftSheetWindow(onDismissRequest = { open = false }) { surfaceModifier ->
                RaftComboboxSheetSurface(
                    title = title ?: label ?: placeholder,
                    query = query,
                    onQueryChange = { query = it },
                    options = options,
                    isSelected = { it == value },
                    onPick = { option ->
                        open = false
                        if (option.value != value) onValueChange(option.value)
                    },
                    emptyText = emptyText,
                    modifier = surfaceModifier,
                    searchPlaceholder = searchPlaceholder,
                    onClear =
                        if (onClear != null && value != null) {
                            {
                                open = false
                                onClear()
                            }
                        } else {
                            null
                        },
                    clearLabel = clearLabel,
                    onCreate =
                        if (create != null) {
                            { text ->
                                open = false
                                create(text)
                            }
                        } else {
                            null
                        },
                    createLabel = createLabel,
                    takenLabels = listOfNotNull(shown),
                    optionLeading = optionLeading,
                    inWindow = true,
                )
            }
        }
    }
}

/**
 * raft-ui ComboboxSelect `multiple`: a few of many values (people, agents, labels) as
 * chips in the field ([RaftComboboxChipsTrigger]). Always opens [RaftCombobox]'s bottom
 * sheet, whatever the number of options; picking a row adds or removes its value
 * ([onValuesChange]) and keeps the sheet open; every chosen row is bold with a check.
 * [doneLabel] (a full-width primary button at the bottom) closes it, as do Back and a tap
 * on the scrim. With values chosen, [clearLabel] (null: none) at the end of the title
 * clears them all. Each chip has a remove button named by [removeLabel] ("Remove Bugen").
 *
 * The search row is not focused on open unless [create] is set. With [create], typed text
 * that is not an option is offered as the first row ([createLabel]); picking it calls
 * [create] with the trimmed text (add it to the values and, usually, the options), clears
 * the search and keeps the sheet open.
 */
@Composable
fun <T> RaftMultiCombobox(
    values: List<T>,
    options: List<RaftComboboxOption<T>>,
    onValuesChange: (List<T>) -> Unit,
    emptyText: (query: String) -> String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    title: String? = null,
    searchPlaceholder: String = "Search",
    doneLabel: String = "Done",
    clearLabel: String? = "Clear",
    removeLabel: (String) -> String = { "Remove $it" },
    create: ((String) -> Unit)? = null,
    createLabel: (String) -> String = RaftComboboxDefaultCreateLabel,
    enabled: Boolean = LocalRaftFieldState.current.enabled,
    invalid: Boolean = LocalRaftFieldState.current.invalid,
    label: String? = LocalRaftFieldState.current.label,
    optionLeading: (@Composable (RaftComboboxOption<T>) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val chips = values.map { raftComboboxLabelOf(options, it) }
    Box(modifier) {
        RaftComboboxChipsTrigger(
            chips = chips,
            onClick = {
                query = ""
                open = true
            },
            onRemove = { index -> onValuesChange(values.filterIndexed { i, _ -> i != index }) },
            placeholder = placeholder,
            enabled = enabled,
            invalid = invalid,
            label = label,
            expanded = open,
            onCollapse = { open = false },
            removeLabel = removeLabel,
        )
        if (open) {
            RaftSheetWindow(onDismissRequest = { open = false }) { surfaceModifier ->
                RaftComboboxSheetSurface(
                    title = title ?: label ?: placeholder,
                    query = query,
                    onQueryChange = { query = it },
                    options = options,
                    isSelected = { it in values },
                    onPick = { option ->
                        onValuesChange(if (option.value in values) values.filter { it != option.value } else values + option.value)
                    },
                    emptyText = emptyText,
                    modifier = surfaceModifier,
                    searchPlaceholder = searchPlaceholder,
                    onClear = if (clearLabel != null && values.isNotEmpty()) { { onValuesChange(emptyList()) } } else null,
                    clearLabel = clearLabel ?: "",
                    onDone = { open = false },
                    doneLabel = doneLabel,
                    onCreate =
                        if (create != null) {
                            { text ->
                                query = ""
                                create(text)
                            }
                        } else {
                            null
                        },
                    createLabel = createLabel,
                    takenLabels = chips,
                    optionLeading = optionLeading,
                    inWindow = true,
                )
            }
        }
    }
}

/**
 * [RaftCombobox]'s bottom sheet for a trigger of the caller's own (a filter chip, a toolbar
 * button) rather than the select field: shown while composed, closed by [onDismissRequest]
 * (Back, a tap on the scrim). Titled [title], with the search row at the top ([query] is
 * the caller's, so it can survive the sheet; options are filtered by it here) and the list
 * filling the sheet; the search row waits for a tap. Picking a row calls [onValueChange]
 * and then [onDismissRequest]. With [onClear], [clearLabel] at the end of the title clears
 * the value and closes the sheet.
 */
@Composable
fun <T> RaftComboboxSheet(
    value: T?,
    options: List<RaftComboboxOption<T>>,
    onValueChange: (T) -> Unit,
    onDismissRequest: () -> Unit,
    title: String,
    emptyText: (query: String) -> String,
    query: String,
    onQueryChange: (String) -> Unit,
    searchPlaceholder: String = "Search",
    onClear: (() -> Unit)? = null,
    clearLabel: String = "Clear",
    optionLeading: (@Composable (RaftComboboxOption<T>) -> Unit)? = null,
) {
    RaftSheetWindow(onDismissRequest = onDismissRequest) { surfaceModifier ->
        RaftComboboxSheetSurface(
            title = title,
            query = query,
            onQueryChange = onQueryChange,
            options = options,
            isSelected = { it == value },
            onPick = { option ->
                if (option.value != value) onValueChange(option.value)
                onDismissRequest()
            },
            emptyText = emptyText,
            modifier = surfaceModifier,
            searchPlaceholder = searchPlaceholder,
            onClear =
                if (onClear != null && value != null) {
                    {
                        onClear()
                        onDismissRequest()
                    }
                } else {
                    null
                },
            clearLabel = clearLabel,
            optionLeading = optionLeading,
            inWindow = true,
        )
    }
}

/**
 * [RaftMultiCombobox]'s bottom sheet for a trigger of the caller's own (a filter chip):
 * shown while composed. Picking a row adds or removes its value ([onValuesChange]) and
 * keeps the sheet open; [doneLabel], Back and a tap on the scrim call [onDismissRequest].
 * With values chosen, [clearLabel] (null: none) clears them all. [query] as in
 * [RaftComboboxSheet].
 */
@Composable
fun <T> RaftMultiComboboxSheet(
    values: List<T>,
    options: List<RaftComboboxOption<T>>,
    onValuesChange: (List<T>) -> Unit,
    onDismissRequest: () -> Unit,
    title: String,
    emptyText: (query: String) -> String,
    query: String,
    onQueryChange: (String) -> Unit,
    searchPlaceholder: String = "Search",
    doneLabel: String = "Done",
    clearLabel: String? = "Clear",
    optionLeading: (@Composable (RaftComboboxOption<T>) -> Unit)? = null,
) {
    RaftSheetWindow(onDismissRequest = onDismissRequest) { surfaceModifier ->
        RaftComboboxSheetSurface(
            title = title,
            query = query,
            onQueryChange = onQueryChange,
            options = options,
            isSelected = { it in values },
            onPick = { option ->
                onValuesChange(if (option.value in values) values.filter { it != option.value } else values + option.value)
            },
            emptyText = emptyText,
            modifier = surfaceModifier,
            searchPlaceholder = searchPlaceholder,
            onClear = if (clearLabel != null && values.isNotEmpty()) { { onValuesChange(emptyList()) } } else null,
            clearLabel = clearLabel ?: "",
            onDone = onDismissRequest,
            doneLabel = doneLabel,
            optionLeading = optionLeading,
            inWindow = true,
        )
    }
}

/**
 * raft-ui ComboboxChips as a trigger: the field of a [RaftMultiCombobox]. The select
 * trigger's frame and states (44dp minimum, control radius, 1dp `line-field` on
 * `layer-card`, pressed, focus ring, [invalid] `danger-strong`, disabled `fill-muted`),
 * holding one chip per chosen value (32dp, chip radius, `fill-muted`, `text-footnote`
 * semibold, 6dp apart, wrapping onto more lines), 6dp inside the frame's outer edge, so one
 * line of chips keeps the field 44dp tall (raft-design Shape > Inside a frame), and the
 * chevron 12dp from its end. Each chip ends in a 28dp remove button (a 14dp × in
 * `foreground-hint`, sticker radius) that calls [onRemove] with its index and is named
 * [removeLabel] of the chip; without [onRemove], or disabled, chips have none. With no
 * chips it shows the [placeholder] like the select trigger.
 *
 * Screen readers hear a drop-down list named by the label with the chosen values (or the
 * placeholder) and, when [expanded] is given, whether it is expanded; each remove button
 * is a focus stop of its own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RaftComboboxChipsTrigger(
    chips: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onRemove: ((Int) -> Unit)? = null,
    placeholder: String = "",
    enabled: Boolean = LocalRaftFieldState.current.enabled,
    invalid: Boolean = LocalRaftFieldState.current.invalid,
    label: String? = LocalRaftFieldState.current.label,
    expanded: Boolean? = null,
    onCollapse: (() -> Unit)? = null,
    removeLabel: (String) -> String = { "Remove $it" },
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val look = raftSelectTriggerLook(enabled, invalid, chips.isNotEmpty(), colors)
    val isPressed = raftIsPressed(source)
    val pressed = enabled && isPressed
    val isFocused = raftIsFocused(source)
    val focused = enabled && isFocused
    val shape = RoundedCornerShape(RaftRadius.Control)
    val field = LocalRaftFieldState.current
    val shown = if (chips.isEmpty()) placeholder else chips.joinToString(", ")
    Row(
        modifier
            .fillMaxWidth()
            // A minimum: more chips wrap onto more lines and a large font scale grows it.
            .heightIn(min = 44.dp)
            .raftFocusRing(focused, RaftRadius.Control, colors)
            .background(look.fill, shape)
            .raftPressOverlay(pressed, look.pressOverlay, RaftRadius.Control)
            .border(RaftStroke.Field, look.border, shape)
            .clickable(source, indication = null, enabled = enabled, role = Role.DropdownList, onClick = onClick)
            .semantics {
                if (label != null) contentDescription = if (shown.isEmpty()) label else "$label, $shown"
                when (expanded) {
                    true -> collapse { onCollapse?.invoke(); onCollapse != null }
                    false -> expand { onClick(); true }
                    null -> {}
                }
                if (invalid && enabled) error(field.error ?: RaftInvalidValueMessage)
            }
            // Chips sit `--control-embedded-inset` inside the outer edge, on the frame side of
            // the 1.5dp border; the placeholder sits where the select trigger's text does.
            .padding(
                start = if (chips.isEmpty()) 14.dp + RaftStroke.Field else RaftTextFieldDefaults.EmbeddedInset,
                end = 12.dp + RaftStroke.Field,
                top = RaftTextFieldDefaults.EmbeddedInset,
                bottom = RaftTextFieldDefaults.EmbeddedInset,
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chips.isEmpty()) {
            RaftText(
                placeholder,
                Modifier.weight(1f),
                style = if (field.inField) typography.field else typography.subhead.copy(fontWeight = FontWeight.Medium),
                color = look.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(RaftTextFieldDefaults.EmbeddedInset),
                verticalArrangement = Arrangement.spacedBy(RaftTextFieldDefaults.EmbeddedInset),
            ) {
                chips.forEachIndexed { index, chip ->
                    RaftComboboxChip(
                        text = chip,
                        enabled = enabled,
                        onRemove = if (onRemove != null && enabled) { { onRemove(index) } } else null,
                        removeLabel = removeLabel(chip),
                    )
                }
            }
        }
        RaftGlyphIcon(RaftGlyph.ChevronDown, size = 16.dp, color = look.chevron)
    }
}

/** combobox.tsx `chip`: 32dp (`--control-embedded-size`), chip radius, `fill-muted`, footnote semibold, 8dp in, the remove button 2dp from the end. */
@Composable
private fun RaftComboboxChip(text: String, enabled: Boolean, onRemove: (() -> Unit)?, removeLabel: String) {
    val colors = RaftTheme.colors
    Row(
        Modifier
            .heightIn(min = RaftTextFieldDefaults.EmbeddedControlSize)
            .background(colors.fillMuted, RoundedCornerShape(RaftRadius.Chip))
            .padding(start = 8.dp, end = if (onRemove != null) 2.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftText(
            text,
            Modifier.weight(1f, fill = false),
            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = if (enabled) colors.foreground else colors.foregroundDisabled,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onRemove != null) RaftComboboxChipRemove(removeLabel, onRemove)
    }
}

/**
 * combobox.tsx `chipRemove`: 28dp (4dp under the chip; [size]), sticker radius, a 14dp × in
 * `foreground-hint`; pressed `fill-pressed`. Its keyboard focus ring is a 2dp `foreground`
 * ring drawn inside it, which the chip would clip outside. With [widenTouchTarget] it takes
 * touches 44dp square (a standalone chip on a phone, [RaftFilterChip]); the combobox's
 * chips keep their box (see the README).
 */
@Composable
internal fun RaftComboboxChipRemove(
    label: String,
    onClick: () -> Unit,
    size: Dp = RaftTextFieldDefaults.EmbeddedControlSize - 4.dp,
    widenTouchTarget: Boolean = false,
) {
    val colors = RaftTheme.colors
    val source = remember { MutableInteractionSource() }
    val isPressed = raftIsPressed(source)
    val isHovered = raftIsHovered(source)
    val pressed = isPressed || isHovered
    val focused = raftIsFocused(source)
    Box(
        Modifier
            .size(size)
            .then(if (widenTouchTarget) Modifier.raftTouchTarget(true, source, onClick) else Modifier)
            .raftPressOverlay(pressed, colors.fillPressed, RaftRadius.Sticker)
            .raftInsetFocusOutline(focused, colors.foreground, RaftRadius.Sticker)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        RaftGlyphIcon(RaftGlyph.X, size = 14.dp, color = colors.foregroundHint)
    }
}

/**
 * combobox.tsx `clear` in the sheet's title row: a 28dp text button, control radius, 8dp
 * padding, `text-footnote` (`text-control` on a phone) semibold `foreground-muted`;
 * pressed lays `fill-pressed` over it and turns it `foreground`. Takes touches 44dp tall.
 */
@Composable
private fun RaftComboboxClear(text: String, onClick: () -> Unit) {
    val colors = RaftTheme.colors
    val source = remember { MutableInteractionSource() }
    val isPressed = raftIsPressed(source)
    val isHovered = raftIsHovered(source)
    val pressed = isPressed || isHovered
    val focused = raftIsFocused(source)
    Box(
        Modifier
            .heightIn(min = 28.dp)
            .raftTouchTarget(true, source, onClick)
            .raftFocusRing(focused, RaftRadius.Control, colors)
            .raftPressOverlay(pressed, colors.fillPressed, RaftRadius.Control)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        RaftText(
            text,
            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = if (pressed) colors.foreground else colors.foregroundMuted,
            maxLines = 1,
        )
    }
}

/**
 * One option of a combobox list: the menu row's metrics (36dp minimum, control radius,
 * 10dp padding and gap, `text-callout` medium `foreground`, or the field type in a field
 * Select's anchored list), bold with a 16dp check when [selected], `brand-soft` while
 * pressed or focused, `foreground-disabled` when not [enabled]. [leading] (an icon or an
 * avatar) goes before the label in `foreground-muted`; [description] is a second line
 * under the label (`text-caption` regular `foreground-hint`, 2dp under it), with the
 * leading slot and the check on the label's line. One focus stop, selectable, with its
 * selected state; in a [RaftDropdownMenu] it takes part in the menu's keyboard navigation.
 */
@Composable
internal fun RaftComboboxOptionRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val isPressed = raftIsPressed(source)
    val isFocused = raftIsFocused(source)
    val highlighted = enabled && (isPressed || isFocused)
    val ink = if (enabled) colors.foreground else colors.foregroundDisabled
    // A field Select's options share the field type (select.tsx `chrome: field`), the chosen one too.
    val labelStyle =
        if (LocalRaftMenuFieldType.current) {
            typography.field
        } else {
            typography.callout.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        }
    // The leading slot and the check sit on the label's line.
    val lineModifier = Modifier.sizeIn(minWidth = 16.dp, minHeight = 20.dp)
    val nav = LocalRaftMenuNav.current
    val entry = remember(nav) { RaftMenuNav.Entry() }
    entry.text = text
    entry.enabled = enabled
    if (nav != null) {
        DisposableEffect(nav, entry) {
            nav.entries += entry
            onDispose { nav.entries -= entry }
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .then(
                if (nav != null) {
                    Modifier
                        .focusRequester(entry.requester)
                        .onFocusChanged { entry.focused = it.isFocused }
                        .onGloballyPositioned { entry.top = it.positionInRoot().y }
                } else {
                    Modifier
                },
            )
            .background(if (highlighted) colors.brandSoft else Color.Transparent, RoundedCornerShape(RaftRadius.Chip))
            .selectable(selected = selected, interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = raftOptionInset(labelStyle)),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = if (description != null) Alignment.Top else Alignment.CenterVertically,
    ) {
        if (leading != null) {
            CompositionLocalProvider(LocalRaftContentColor provides if (enabled) colors.foregroundMuted else colors.foregroundDisabled) {
                Box(lineModifier, contentAlignment = Alignment.Center) { leading() }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // raft-ui 0.13: a long option wraps to two lines, then clamps.
            RaftText(text, style = labelStyle, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (description != null) {
                RaftText(
                    description,
                    style = typography.caption.copy(fontWeight = FontWeight.Normal),
                    color = if (enabled) colors.foregroundHint else colors.foregroundDisabled,
                )
            }
        }
        if (selected) {
            Box(lineModifier, contentAlignment = Alignment.Center) { RaftGlyphIcon(RaftGlyph.Check, size = 16.dp, color = ink) }
        }
    }
}

/**
 * The rows of a combobox's anchored list (inside a [RaftDropdownMenu] or a
 * [RaftMenuSurface]): group labels and [RaftComboboxOptionRow]s, or [emptyText] of an
 * empty query (select.tsx `empty`: `text-callout` `foreground-hint`, 10 x 8dp) when there
 * are no options. Plain rows, no lazy list: the anchored list holds at most
 * [RaftComboboxSheetThreshold].
 */
@Composable
internal fun <T> RaftComboboxMenuRows(
    options: List<RaftComboboxOption<T>>,
    isSelected: (T) -> Boolean,
    onPick: (RaftComboboxOption<T>) -> Unit,
    emptyText: (query: String) -> String,
    optionLeading: (@Composable (RaftComboboxOption<T>) -> Unit)? = null,
) {
    if (options.isEmpty()) {
        RaftText(
            emptyText(""),
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            style = RaftTheme.typography.callout,
            color = RaftTheme.colors.foregroundHint,
        )
        return
    }
    raftComboboxRows(options).forEach { row ->
        when (row) {
            is RaftComboboxRow.Group -> RaftMenuLabel(row.label, Modifier.semantics { heading() })
            is RaftComboboxRow.Option -> {
                val option = options[row.index]
                RaftComboboxOptionRow(
                    text = option.label,
                    selected = isSelected(option.value),
                    onClick = { onPick(option) },
                    description = option.description,
                    enabled = option.enabled,
                    leading = if (optionLeading != null) { { optionLeading(option) } } else null,
                )
            }
        }
    }
}

/**
 * The bottom sheet of a combobox without its window ([RaftSheetSurface]'s look), for
 * [RaftCombobox], [RaftMultiCombobox] and fixtures: [title] (and, with [onClear], the
 * [clearLabel] button at its end), the search row under it (16dp in, the field type, a
 * `line-hairline` under it; [searchPlaceholder] as its hint), then a lazy list filling the
 * rest of the sheet (6dp padding, so the labels sit on the 16dp margin): with [onCreate],
 * the "Use “text”" row ([createLabel], a + glyph) first while the typed text is not an
 * option ([raftComboboxCreateQuery], also checked against [takenLabels]); then the
 * [options] filtered by [query] with group labels; or [emptyText] of the query
 * (`text-callout` `foreground-hint`, 16 x 14dp, a polite live region) when there is
 * nothing to show. Then, with [onDone], the [doneLabel] button.
 *
 * [inWindow]: in a [RaftSheetWindow], so the content clears the home indicator and the
 * keyboard, and, when [raftComboboxFocusesSearch] (with [onCreate]), the search row takes
 * focus once it is laid out.
 *
 * [searchable] = false drops the search row: a Select's list in a sheet, for a phone's
 * picker of up to [RaftComboboxSheetThreshold] values ([RaftChoicePicker]).
 */
@Composable
internal fun <T> RaftComboboxSheetSurface(
    title: String,
    query: String,
    onQueryChange: (String) -> Unit,
    options: List<RaftComboboxOption<T>>,
    isSelected: (T) -> Boolean,
    onPick: (RaftComboboxOption<T>) -> Unit,
    emptyText: (query: String) -> String,
    modifier: Modifier = Modifier,
    searchPlaceholder: String = "Search",
    onClear: (() -> Unit)? = null,
    clearLabel: String = "Clear",
    onDone: (() -> Unit)? = null,
    doneLabel: String = "Done",
    onCreate: ((String) -> Unit)? = null,
    createLabel: (String) -> String = RaftComboboxDefaultCreateLabel,
    takenLabels: List<String> = emptyList(),
    optionLeading: (@Composable (RaftComboboxOption<T>) -> Unit)? = null,
    inWindow: Boolean = false,
    searchable: Boolean = true,
) {
    val search = remember { FocusRequester() }
    if (searchable && inWindow && raftComboboxFocusesSearch(creates = onCreate != null)) {
        LaunchedEffect(Unit) {
            // The input attaches its focus target in the first frame.
            withFrameNanos { }
            runCatching { search.requestFocus() }
        }
    }
    val shown = remember(options, query) { raftComboboxFilter(options, query) }
    val createQuery = if (onCreate != null) raftComboboxCreateQuery(options, query, takenLabels) else null
    // The sheet is a window of its own: the field around the trigger doesn't reach in.
    CompositionLocalProvider(LocalRaftFieldState provides RaftFieldState()) {
        RaftSheetSurfaceImpl(
            title = title,
            modifier = modifier,
            description = null,
            clearNavigationBar = inWindow,
            actions =
                if (onDone != null) {
                    {
                        RaftButton(
                            doneLabel,
                            onDone,
                            Modifier.fillMaxWidth(),
                            variant = RaftButtonVariant.Primary,
                            size = RaftButtonSize.Lg,
                        )
                    }
                } else {
                    null
                },
            content = {
                RaftComboboxList(
                    options = shown,
                    query = query,
                    isSelected = isSelected,
                    onPick = onPick,
                    emptyText = emptyText,
                    createQuery = createQuery,
                    onCreate = onCreate,
                    createLabel = createLabel,
                    optionLeading = optionLeading,
                )
            },
            headerAction = if (onClear != null) { { RaftComboboxClear(clearLabel, onClear) } } else null,
            pinned =
                if (searchable) {
                    {
                        RaftSearchRow(
                            value = query,
                            onValueChange = onQueryChange,
                            placeholder = searchPlaceholder,
                            inset = 16.dp,
                            focusRequester = search,
                        )
                    }
                } else {
                    null
                },
            fillContent = true,
        )
    }
}

@Composable
private fun <T> RaftComboboxList(
    options: List<RaftComboboxOption<T>>,
    query: String,
    isSelected: (T) -> Boolean,
    onPick: (RaftComboboxOption<T>) -> Unit,
    emptyText: (query: String) -> String,
    createQuery: String?,
    onCreate: ((String) -> Unit)?,
    createLabel: (String) -> String,
    optionLeading: (@Composable (RaftComboboxOption<T>) -> Unit)?,
) {
    if (options.isEmpty() && (createQuery == null || onCreate == null)) {
        // ComboboxEmpty: never an empty list; says what found nothing.
        RaftText(
            emptyText(query.trim()),
            Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            style = RaftTheme.typography.callout,
            color = RaftTheme.colors.foregroundHint,
        )
        return
    }
    val rows = remember(options) { raftComboboxRows(options) }
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(6.dp)) {
        if (createQuery != null && onCreate != null) {
            item {
                RaftComboboxOptionRow(
                    text = createLabel(createQuery),
                    selected = false,
                    onClick = { onCreate(createQuery) },
                    leading = { RaftGlyphIcon(RaftGlyph.Plus) },
                )
            }
        }
        items(rows.size) { i ->
            when (val row = rows[i]) {
                is RaftComboboxRow.Group -> RaftMenuLabel(row.label, Modifier.semantics { heading() })
                is RaftComboboxRow.Option -> {
                    val option = options[row.index]
                    RaftComboboxOptionRow(
                        text = option.label,
                        selected = isSelected(option.value),
                        onClick = { onPick(option) },
                        description = option.description,
                        enabled = option.enabled,
                        leading = if (optionLeading != null) { { optionLeading(option) } } else null,
                    )
                }
            }
        }
    }
}
