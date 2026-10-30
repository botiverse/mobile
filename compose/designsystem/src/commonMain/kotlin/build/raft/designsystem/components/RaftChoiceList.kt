package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

/** raft-ui ChoiceList metrics (choice-list.recipe.ts, choice-list.md), at the touch sizes. */
object RaftChoiceListDefaults {
    /** "the box shows only once the list is longer than a glance, above 5 items" (`search="auto"`). */
    const val SearchAbove: Int = 5

    /** `max-h-72`: with the search box on, the rows scroll inside the frame past 288dp. */
    val ScrollMaxHeight: Dp = 288.dp

    /** `pointer-coarse:min-h-11`: a one-line row is 44dp on a touch screen. */
    val RowMinHeight: Dp = 44.dp

    /** `pr-11`: the end of a row with an action keeps 44dp for it. */
    val ActionInset: Dp = 44.dp
}

/**
 * One row of a [RaftChoiceList] or [RaftMultiChoiceList]: its [value], [label] (also what the
 * search matches, unless [searchText] is given) and up to two [descriptions] under it. A blank
 * description is left out, never drawn empty. Colour inside a line is the caller's (a used-up
 * quota window in `warning-strong`). [media] (an avatar) sits between the mark and the name,
 * centred on the name's line; [labelTrailing] (an App badge) follows the name, which truncates
 * first; [group] names the row's [RaftChoiceListGroup] in a [RaftMultiChoiceList].
 */
@Immutable
class RaftChoiceListItem<T>(
    val value: T,
    val label: String,
    val descriptions: List<AnnotatedString> = emptyList(),
    val enabled: Boolean = true,
    val searchText: String? = null,
    val media: (@Composable () -> Unit)? = null,
    val labelTrailing: (@Composable () -> Unit)? = null,
    val group: String? = null,
)

/** A heading over the rows of one kind (Agents, People) in a [RaftMultiChoiceList]. */
@Immutable
class RaftChoiceListGroup(val value: String, val label: String)

/**
 * raft-ui ChoiceList (single choice; raft-design #767, `choice-list.md`): an always-open list to
 * choose one of any number of [items] when choosing is the dialog's main content (where to move
 * an agent). Never a dropdown, at 3 items or at 300.
 *
 * - The frame: `layer-card`, a hairline border, `radius-container`, 4dp inside; rows 2dp apart.
 * - A row: the radio circle centred on the name's line, 10dp before the name (`text-subhead`
 *   600) and its description lines (`text-footnote` `foreground-muted`), each cut off on its
 *   own line. 44dp for one line (12dp above and below the name), each line adding its own
 *   height. The whole row chooses; `brand-soft` under a mouse, the press overlay on press.
 *   Disabled rows take the disabled ink and choose nothing.
 * - [action]: a button at the row's end (a 32dp ghost [RaftIconButton] "Studio Mac details"),
 *   centred on the name's line, outside the row's selectable node, so pressing it chooses
 *   nothing and it is not read into the radio's name. Rows keep 44dp at their end for it.
 * - Above [RaftChoiceListDefaults.SearchAbove] items a search field ([searchPlaceholder], the
 *   clear button [clearSearchLabel]) sits above the frame, the rows scroll inside the frame
 *   past 288dp, and the search never takes focus by itself. The rows chosen when the search
 *   text last changed stay first, above the matches; searching never changes the choice. With
 *   nothing else matching, [noMatchLabel] sits under them. Back to 5 items or fewer, the field
 *   goes and its text with it.
 * - No items: [emptyLabel] in the frame, no rows and no search.
 *
 * raft-ui holds no copy: every word is the caller's. Deviation: the touch sizes always apply
 * (a phone app). Several choices, groups, a footer and `fill`: [RaftMultiChoiceList].
 */
@Composable
fun <T> RaftChoiceList(
    items: List<RaftChoiceListItem<T>>,
    value: T?,
    onValueChange: (T) -> Unit,
    searchPlaceholder: String,
    clearSearchLabel: String,
    noMatchLabel: String,
    emptyLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalRaftFieldState.current.enabled,
    initialQuery: String = "",
    action: (@Composable (RaftChoiceListItem<T>) -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val searchable = items.size > RaftChoiceListDefaults.SearchAbove
    var query by remember { mutableStateOf(initialQuery) }
    LaunchedEffect(searchable) { if (!searchable) query = "" }
    val needle = if (searchable) query.trim().lowercase() else ""
    // Taken when the text changes, not when the choice does: a row never moves under the finger.
    val pinned = remember(needle) { value }
    val chosen = if (needle.isEmpty()) null else items.firstOrNull { it.value == pinned }
    val matches =
        if (needle.isEmpty()) {
            items
        } else {
            items.filter { it !== chosen && (it.searchText ?: it.label).lowercase().contains(needle) }
        }
    val visible = if (chosen != null) listOf(chosen) + matches else matches
    val noMatches = needle.isNotEmpty() && matches.isEmpty()
    val frameShape = RoundedCornerShape(RaftRadius.Container)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (searchable) {
            RaftTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = searchPlaceholder,
                enabled = enabled,
                leading = { RaftGlyphIcon(RaftGlyph.Search, size = 18.dp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                trailingAction =
                    if (query.isNotEmpty()) {
                        { RaftTextFieldClearAction(onClear = { query = "" }, label = clearSearchLabel, enabled = enabled) }
                    } else {
                        null
                    },
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.layerCard, frameShape)
                .border(RaftStroke.Hairline, colors.lineHairline, frameShape)
                .then(if (searchable) Modifier.heightIn(max = RaftChoiceListDefaults.ScrollMaxHeight) else Modifier)
                .then(if (searchable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(4.dp),
        ) {
            if (items.isEmpty()) {
                ChoiceListNote(emptyLabel)
            } else {
                Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    visible.forEach { item ->
                        ChoiceListRow(
                            item = item,
                            selected = item.value == value,
                            enabled = enabled && item.enabled,
                            multiple = false,
                            onClick = { if (item.value != value) onValueChange(item.value) },
                            action = action,
                        )
                    }
                }
                if (noMatches) ChoiceListNote(noMatchLabel)
            }
        }
    }
}

/**
 * raft-ui ChoiceList with `multiple` and `search="always"` (choice-list.md, "Add Members"): a
 * checkbox on each row, [values] the chosen ones; the whole row ticks or unticks it.
 *
 * - The search field always shows (people recall a name), never takes focus by itself (a touch
 *   screen), and its text is the caller's ([query], [onQueryChange]) so a [footer] or
 *   [noMatchLabel] can quote it. Rows match their `searchText` (else `label`) case-insensitively,
 *   or, when the caller runs its own search, [matchedValues] in its order.
 * - The rows chosen when the search text last changed stay first within their own group (Agents,
 *   Humans), ahead of that group's other matches; searching never changes the choice.
 * - [groups]: each item names its group; rows come under their group's heading (`text-caption`
 *   bold `foreground-hint`, 10dp in, 8dp above and 4dp under) in the groups' order, items of no
 *   known group last with no heading; an empty group draws no heading.
 * - [footer]: one row inside the frame under the rows, past a seam, that doesn't scroll with
 *   them (the way to make what isn't listed: "Create Agent").
 * - [fill]: the list takes the height it is given (pass a weight in [modifier]) and its rows
 *   scroll inside it; otherwise the rows scroll past 288dp.
 * - No items: [emptyLabel] in the frame and no rows; nothing matching: [noMatchLabel] under the
 *   chosen rows.
 */
@Composable
fun <T> RaftMultiChoiceList(
    items: List<RaftChoiceListItem<T>>,
    values: Set<T>,
    onValuesChange: (Set<T>) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    searchPlaceholder: String,
    clearSearchLabel: String,
    noMatchLabel: String,
    emptyLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalRaftFieldState.current.enabled,
    groups: List<RaftChoiceListGroup> = emptyList(),
    matchedValues: List<T>? = null,
    fill: Boolean = false,
    footer: (@Composable () -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val needle = query.trim().lowercase()
    // Taken when the text changes, not when the choice does: a row never moves under the finger.
    val pinned = remember(needle) { values }
    val entries = raftMultiChoiceListEntries(items, pinned, needle, matchedValues, groups)
    val noMatches = needle.isNotEmpty() && entries.none { it is RaftChoiceListEntry.Row && it.item.value !in pinned }
    val frameShape = RoundedCornerShape(RaftRadius.Container)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RaftTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = searchPlaceholder,
            enabled = enabled,
            leading = { RaftGlyphIcon(RaftGlyph.Search, size = 18.dp) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            trailingAction =
                if (query.isNotEmpty()) {
                    { RaftTextFieldClearAction(onClear = { onQueryChange("") }, label = clearSearchLabel, enabled = enabled) }
                } else {
                    null
                },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (fill) Modifier.weight(1f) else Modifier)
                .background(colors.layerCard, frameShape)
                .border(RaftStroke.Hairline, colors.lineHairline, frameShape),
        ) {
            val rows: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
                if (items.isEmpty()) {
                    item(key = "empty") { ChoiceListNote(emptyLabel) }
                } else {
                    entries.forEach { entry ->
                        when (entry) {
                            is RaftChoiceListEntry.Heading ->
                                item(key = "group:${entry.group.value}") { ChoiceListHeading(entry.group.label) }
                            is RaftChoiceListEntry.Row -> {
                                val row = entry.item
                                item(key = "row:${row.value}") {
                                    ChoiceListRow(
                                        item = row,
                                        selected = row.value in values,
                                        enabled = enabled && row.enabled,
                                        multiple = true,
                                        onClick = { onValuesChange(if (row.value in values) values - row.value else values + row.value) },
                                        action = null,
                                    )
                                }
                            }
                        }
                    }
                    if (noMatches) item(key = "no-match") { ChoiceListNote(noMatchLabel) }
                }
            }
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(if (fill) Modifier.weight(1f) else Modifier.heightIn(max = RaftChoiceListDefaults.ScrollMaxHeight)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                content = rows,
            )
            if (footer != null) {
                Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam))
                Box(Modifier.fillMaxWidth().padding(4.dp)) { footer() }
            }
        }
    }
}

internal sealed interface RaftChoiceListEntry<out T> {
    class Heading(val group: RaftChoiceListGroup) : RaftChoiceListEntry<Nothing>

    class Row<T>(val item: RaftChoiceListItem<T>) : RaftChoiceListEntry<T>
}

/**
 * The rows a [RaftMultiChoiceList] draws, in order: the [pinned] (chosen when the search text
 * last changed) first, then the matches ([matchedValues] in their order when given, else each
 * row's search text containing [needle]); arranged under [groups]' headings in the groups'
 * order, rows of no known group last with no heading.
 */
internal fun <T> raftMultiChoiceListEntries(
    items: List<RaftChoiceListItem<T>>,
    pinned: Set<T>,
    needle: String,
    matchedValues: List<T>?,
    groups: List<RaftChoiceListGroup>,
): List<RaftChoiceListEntry<T>> {
    val visible =
        if (needle.isEmpty()) {
            items
        } else {
            val chosen = items.filter { it.value in pinned }
            val matches =
                if (matchedValues != null) {
                    val byValue = items.associateBy { it.value }
                    matchedValues.mapNotNull { byValue[it] }.filter { it.value !in pinned }
                } else {
                    items.filter { it.value !in pinned && (it.searchText ?: it.label).lowercase().contains(needle) }
                }
            chosen + matches
        }
    val known = groups.map { it.value }.toSet()
    val out = mutableListOf<RaftChoiceListEntry<T>>()
    groups.forEach { group ->
        val rows = visible.filter { it.group == group.value }
        if (rows.isNotEmpty()) {
            out += RaftChoiceListEntry.Heading(group)
            rows.forEach { out += RaftChoiceListEntry.Row(it) }
        }
    }
    visible.filter { it.group == null || it.group !in known }.forEach { out += RaftChoiceListEntry.Row(it) }
    return out
}

@Composable
private fun ChoiceListHeading(text: String) {
    Box(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp)) {
        RaftText(
            text,
            style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
            color = RaftTheme.colors.foregroundHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun <T> ChoiceListRow(
    item: RaftChoiceListItem<T>,
    selected: Boolean,
    enabled: Boolean,
    multiple: Boolean,
    onClick: () -> Unit,
    action: (@Composable (RaftChoiceListItem<T>) -> Unit)?,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val source = remember { MutableInteractionSource() }
    val isPressed = raftIsPressed(source)
    val isHovered = raftIsHovered(source)
    val isFocused = raftIsFocused(source)
    val pressed = enabled && isPressed
    val hovered = enabled && isHovered
    val focused = enabled && isFocused
    val labelStyle = typography.subhead.copy(fontWeight = FontWeight.SemiBold)
    val lineHeight = raftLineHeightDp(labelStyle.lineHeight, labelStyle.fontSize, LocalDensity.current)
    // (44 - 1lh) / 2: one line makes a 44dp row.
    val inset = ((RaftChoiceListDefaults.RowMinHeight - lineHeight) / 2).coerceAtLeast(0.dp)
    val chipShape = RoundedCornerShape(RaftRadius.Chip)
    val lines = item.descriptions.filter { it.isNotBlank() }.take(2)
    Box(Modifier.fillMaxWidth()) {
        RaftFirstLineRow(
            lineHeight = lineHeight,
            gap = 10.dp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (hovered) Modifier.background(colors.brandSoft, chipShape) else Modifier)
                    .raftPressOverlay(pressed, raftPressOverlayColor(RaftPressSurface.Paper, colors), RaftRadius.Chip)
                    .raftPointerHover(source)
                    .then(
                        if (multiple) {
                            Modifier.toggleable(selected, source, indication = null, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
                        } else {
                            Modifier.selectable(selected, source, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                        }
                    )
                    .heightIn(min = RaftChoiceListDefaults.RowMinHeight)
                    .padding(
                        start = 10.dp,
                        end = if (action != null) RaftChoiceListDefaults.ActionInset else 10.dp,
                        top = inset,
                        bottom = inset,
                    ),
            control = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (multiple) {
                        RaftCheckboxIndicator(checked = selected, enabled = enabled)
                    } else {
                        RaftRadioBox(selected, enabled, focused = focused, modifier = Modifier)
                    }
                    item.media?.invoke()
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftText(
                        item.label,
                        modifier = Modifier.weight(1f, fill = false),
                        style = labelStyle,
                        color = if (enabled) colors.foreground else colors.foregroundDisabled,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.labelTrailing?.invoke()
                }
                lines.forEach { line ->
                    RaftText(
                        line,
                        style = typography.footnote,
                        color = if (enabled) colors.foregroundMuted else colors.foregroundDisabled,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (action != null) {
            // 32dp, centred on the name's line, 4dp from the row's end.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = (inset + lineHeight / 2 - 16.dp).coerceAtLeast(0.dp), end = 4.dp),
            ) { action(item) }
        }
    }
}

@Composable
private fun ChoiceListNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
        RaftText(text, style = RaftTheme.typography.callout, color = RaftTheme.colors.foregroundHint)
    }
}
