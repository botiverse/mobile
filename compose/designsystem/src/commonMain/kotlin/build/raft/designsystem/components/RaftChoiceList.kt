package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
 * quota window in `warning-strong`). [media] is drawn before the label (an avatar), centred on
 * the name's line; [group] is the [RaftChoiceListGroup.value] the row is listed under.
 */
@Immutable
class RaftChoiceListItem<T>(
    val value: T,
    val label: String,
    val descriptions: List<AnnotatedString> = emptyList(),
    val enabled: Boolean = true,
    val searchText: String? = null,
    val media: (@Composable () -> Unit)? = null,
    val group: String? = null,
)

/** A heading over the rows of one kind (Agents, People), in the groups' order. */
@Immutable
class RaftChoiceListGroup(val value: String, val label: String)

/**
 * When the search field shows (raft-ui `search`): [Auto] above 5 items, for a list people choose
 * from by comparing the rows; [Always], for a list of things people know by name (a person).
 * On a touch screen the field never takes focus by itself either way.
 */
enum class RaftChoiceListSearch { Auto, Always }

/**
 * raft-ui ChoiceList (single choice; raft-design #767, `choice-list.md`): an always-open list to
 * choose one of any number of [items] when choosing is the dialog's main content (where to move
 * an agent). Never a dropdown, at 3 items or at 300.
 *
 * - The frame: `layer-card`, a hairline border, `radius-container`, 4dp inside; rows 2dp apart.
 * - A row: the radio circle centred on the name's line, 10dp before the item's media and the
 *   name (`text-subhead` 600) and its description lines (`text-footnote` `foreground-muted`),
 *   each cut off on its own line. 44dp for one line (12dp above and below the name), each line
 *   adding its own height. The whole row chooses; `brand-soft` under a mouse, the press overlay
 *   on press. Disabled rows take the disabled ink and choose nothing.
 * - [action]: a button at the row's end (a 32dp ghost [RaftIconButton] "Studio Mac details"),
 *   centred on the name's line, outside the row's selectable node, so pressing it chooses
 *   nothing and it is not read into the radio's name. Rows keep 44dp at their end for it.
 * - The search field ([searchPlaceholder], the clear button [clearSearchLabel]) shows per
 *   [search]: above [RaftChoiceListDefaults.SearchAbove] items, or always. The rows scroll
 *   inside the frame past 288dp, and the search never takes focus by itself. The rows chosen
 *   when the search text last changed stay first, above the matches; searching never changes
 *   the choice. With nothing matching, [noMatchLabel] sits under them. When an [Auto] list drops
 *   back to 5 items or fewer, the field goes and its text with it.
 * - [searchValue] / [onSearchValueChange]: the caller owns the search text (to quote it in the
 *   footer or the no-match line). [matchedValues]: the caller's own search (a ranked people
 *   search) found these, in this order; the list shows them instead of matching itself.
 * - [groups]: the rows come under their group's heading (`text-caption` bold `foreground-hint`,
 *   10dp in, 8dp above, 4dp below), in the groups' order; rows of no known group follow with no
 *   heading, and a group with nothing to show draws none.
 * - [footer]: one row of the caller's (the way out, "Create an agent") inside the frame under
 *   the rows, past a `line-seam` rule; it doesn't scroll with them.
 * - [fill]: the list takes the height it is given (give [modifier] a height or a weight) and its
 *   rows scroll inside it, instead of stopping at 288dp.
 * - No items: [emptyLabel] in the frame, no rows and no search.
 *
 * raft-ui holds no copy: every word is the caller's. Deviation: the touch sizes always apply
 * (a phone app). [RaftMultiChoiceList] is the `multiple` variant.
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
    search: RaftChoiceListSearch = RaftChoiceListSearch.Auto,
    searchValue: String? = null,
    onSearchValueChange: ((String) -> Unit)? = null,
    matchedValues: List<T>? = null,
    groups: List<RaftChoiceListGroup> = emptyList(),
    footer: (@Composable () -> Unit)? = null,
    fill: Boolean = false,
) {
    RaftChoiceListFrame(
        items = items,
        chosen = if (value == null) emptyList() else listOf(value),
        multiple = false,
        onRowClick = { item -> if (item.value != value) onValueChange(item.value) },
        searchPlaceholder = searchPlaceholder,
        clearSearchLabel = clearSearchLabel,
        noMatchLabel = noMatchLabel,
        emptyLabel = emptyLabel,
        modifier = modifier,
        enabled = enabled,
        initialQuery = initialQuery,
        action = action,
        search = search,
        searchValue = searchValue,
        onSearchValueChange = onSearchValueChange,
        matchedValues = matchedValues,
        groups = groups,
        footer = footer,
        fill = fill,
    )
}

/**
 * raft-ui ChoiceList `multiple` (raft-design #774, `choice-list.md`): the same always-open list
 * as [RaftChoiceList], to choose several of any number of [items] (Add Members): a checkbox on
 * each row, and a tap anywhere on a row ticks or unticks it. [values] are the chosen values;
 * [onValuesChange] gets the new list (a ticked row added at its end). Keep removable chips of the chosen
 * items outside the list, above it. Everything else is as [RaftChoiceList].
 */
@Composable
fun <T> RaftMultiChoiceList(
    items: List<RaftChoiceListItem<T>>,
    values: List<T>,
    onValuesChange: (List<T>) -> Unit,
    searchPlaceholder: String,
    clearSearchLabel: String,
    noMatchLabel: String,
    emptyLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalRaftFieldState.current.enabled,
    initialQuery: String = "",
    action: (@Composable (RaftChoiceListItem<T>) -> Unit)? = null,
    search: RaftChoiceListSearch = RaftChoiceListSearch.Auto,
    searchValue: String? = null,
    onSearchValueChange: ((String) -> Unit)? = null,
    matchedValues: List<T>? = null,
    groups: List<RaftChoiceListGroup> = emptyList(),
    footer: (@Composable () -> Unit)? = null,
    fill: Boolean = false,
) {
    RaftChoiceListFrame(
        items = items,
        chosen = values,
        multiple = true,
        onRowClick = { item -> onValuesChange(if (item.value in values) values - item.value else values + item.value) },
        searchPlaceholder = searchPlaceholder,
        clearSearchLabel = clearSearchLabel,
        noMatchLabel = noMatchLabel,
        emptyLabel = emptyLabel,
        modifier = modifier,
        enabled = enabled,
        initialQuery = initialQuery,
        action = action,
        search = search,
        searchValue = searchValue,
        onSearchValueChange = onSearchValueChange,
        matchedValues = matchedValues,
        groups = groups,
        footer = footer,
        fill = fill,
    )
}

/**
 * The rows to draw for [needle] (raft-ui `visibleItems`): everything with no search; otherwise
 * the [pinned] rows (chosen when the text last changed) and any chosen row the search misses
 * first, then the matches, in [matchedValues]' order when the caller searched, else the items'.
 * [ChoiceListRows.noMatch]: nothing at all matches (a chosen row that matches is a match).
 */
internal fun <T> raftChoiceListRows(
    items: List<RaftChoiceListItem<T>>,
    needle: String,
    pinned: List<T>,
    chosen: List<T>,
    matchedValues: List<T>?,
): ChoiceListRows<T> {
    if (needle.isEmpty()) return ChoiceListRows(items, noMatch = false)
    val matched = matchedValues?.toSet()
    fun matches(item: RaftChoiceListItem<T>) =
        if (matched != null) item.value in matched else (item.searchText ?: item.label).lowercase().contains(needle)
    val top = items.filter { it.value in pinned || (it.value in chosen && !matches(it)) }
    val ordered =
        if (matchedValues != null) {
            matchedValues.distinct().mapNotNull { value -> items.firstOrNull { it.value == value } }
        } else {
            items
        }
    val rest = ordered.filter { it !in top && matches(it) }
    return ChoiceListRows(top + rest, noMatch = items.none(::matches))
}

internal class ChoiceListRows<T>(val rows: List<RaftChoiceListItem<T>>, val noMatch: Boolean)

/** The rows by group, in the groups' order (each keeping the search's order); no-group rows last. */
internal fun <T> raftChoiceListSections(
    rows: List<RaftChoiceListItem<T>>,
    groups: List<RaftChoiceListGroup>,
): List<Pair<RaftChoiceListGroup?, List<RaftChoiceListItem<T>>>> {
    val known = groups.map { it.value }.toSet()
    val sections =
        groups.map { group -> group to rows.filter { it.group == group.value } } +
            listOf(null to rows.filter { it.group == null || it.group !in known })
    return sections.filter { (_, sectionRows) -> sectionRows.isNotEmpty() }
}

@Composable
private fun <T> RaftChoiceListFrame(
    items: List<RaftChoiceListItem<T>>,
    chosen: List<T>,
    multiple: Boolean,
    onRowClick: (RaftChoiceListItem<T>) -> Unit,
    searchPlaceholder: String,
    clearSearchLabel: String,
    noMatchLabel: String,
    emptyLabel: String,
    modifier: Modifier,
    enabled: Boolean,
    initialQuery: String,
    action: (@Composable (RaftChoiceListItem<T>) -> Unit)?,
    search: RaftChoiceListSearch,
    searchValue: String?,
    onSearchValueChange: ((String) -> Unit)?,
    matchedValues: List<T>?,
    groups: List<RaftChoiceListGroup>,
    footer: (@Composable () -> Unit)?,
    fill: Boolean,
) {
    val colors = RaftTheme.colors
    val searchable =
        items.isNotEmpty() && (search == RaftChoiceListSearch.Always || items.size > RaftChoiceListDefaults.SearchAbove)
    var ownQuery by remember { mutableStateOf(initialQuery) }
    val query = searchValue ?: ownQuery
    val setQuery: (String) -> Unit = { next ->
        if (searchValue == null) ownQuery = next
        onSearchValueChange?.invoke(next)
    }
    LaunchedEffect(searchable) {
        if (!searchable) {
            ownQuery = ""
            if (!searchValue.isNullOrEmpty()) onSearchValueChange?.invoke("")
        }
    }
    val needle = if (searchable) query.trim().lowercase() else ""
    // Taken when the text changes, not when the choice does: a row never moves under the finger.
    val pinned = remember(needle) { chosen }
    val visible = raftChoiceListRows(items, needle, pinned, chosen, matchedValues)
    val noMatches = needle.isNotEmpty() && visible.noMatch
    val frameShape = RoundedCornerShape(RaftRadius.Container)
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (searchable) {
            RaftTextField(
                value = query,
                onValueChange = setQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = searchPlaceholder,
                enabled = enabled,
                leading = { RaftGlyphIcon(RaftGlyph.Search, size = 18.dp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                trailingAction =
                    if (query.isNotEmpty()) {
                        { RaftTextFieldClearAction(onClear = { setQuery("") }, label = clearSearchLabel, enabled = enabled) }
                    } else {
                        null
                    },
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (fill) Modifier.weight(1f, fill = true) else Modifier)
                .clip(frameShape)
                .background(colors.layerCard, frameShape)
                .border(RaftStroke.Hairline, colors.lineHairline, frameShape),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(
                        when {
                            fill -> Modifier.weight(1f, fill = true).verticalScroll(rememberScrollState())
                            searchable -> Modifier.heightIn(max = RaftChoiceListDefaults.ScrollMaxHeight).verticalScroll(rememberScrollState())
                            else -> Modifier
                        },
                    )
                    .padding(4.dp),
            ) {
                if (items.isEmpty()) {
                    ChoiceListNote(emptyLabel)
                } else {
                    val sections = raftChoiceListSections(visible.rows, groups)
                    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        sections.forEach { (group, rows) ->
                            if (group != null) ChoiceListHeading(group.label)
                            rows.forEach { item ->
                                ChoiceListRow(
                                    item = item,
                                    selected = item.value in chosen,
                                    multiple = multiple,
                                    enabled = enabled && item.enabled,
                                    onClick = { onRowClick(item) },
                                    action = action,
                                )
                            }
                        }
                    }
                    if (noMatches) ChoiceListNote(noMatchLabel)
                }
            }
            if (footer != null) {
                Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam))
                Box(Modifier.fillMaxWidth().padding(4.dp)) { footer() }
            }
        }
    }
}

/** A group heading: raft-ui `choice-list` heading (`px-2.5 pt-2 pb-1`, caption bold, hint). */
@Composable
private fun ChoiceListHeading(text: String) {
    RaftText(
        text,
        Modifier.fillMaxWidth().semantics { heading() }.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
        style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
        color = RaftTheme.colors.foregroundHint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun <T> ChoiceListRow(
    item: RaftChoiceListItem<T>,
    selected: Boolean,
    multiple: Boolean,
    enabled: Boolean,
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
                    .then(
                        if (multiple) {
                            Modifier.toggleable(selected, source, indication = null, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
                        } else {
                            Modifier.selectable(selected, source, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                        },
                    )
                    .heightIn(min = RaftChoiceListDefaults.RowMinHeight)
                    .padding(
                        start = 10.dp,
                        end = if (action != null) RaftChoiceListDefaults.ActionInset else 10.dp,
                        top = inset,
                        bottom = inset,
                    ),
            control = {
                if (multiple) {
                    RaftCheckboxIndicator(selected, enabled = enabled, modifier = Modifier.raftFocusRing(focused, RaftRadius.Sticker, colors))
                } else {
                    RaftRadioBox(selected, enabled, focused = focused, modifier = Modifier)
                }
            },
        ) {
            ChoiceListRowBody(media = item.media, lineHeight = lineHeight) {
                RaftText(
                    item.label,
                    style = labelStyle,
                    color = if (enabled) colors.foreground else colors.foregroundDisabled,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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

/** A row's media (an avatar) on the name's line, 10dp before the name and lines (`gap-2.5`). */
@Composable
private fun ChoiceListRowBody(
    media: (@Composable () -> Unit)?,
    lineHeight: Dp,
    content: @Composable () -> Unit,
) {
    if (media == null) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.height(lineHeight), contentAlignment = Alignment.Center) { media() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}

@Composable
private fun ChoiceListNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
        RaftText(text, style = RaftTheme.typography.callout, color = RaftTheme.colors.foregroundHint)
    }
}
