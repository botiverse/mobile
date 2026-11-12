package build.raft.designsystem.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftStrings
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

/** One choice of a [RaftFitToolbarScope] or a [RaftFitToolbarChoice]: [key] names it to the page, [label] to people, [count] (a scope's) follows the label. */
data class RaftFitToolbarOption(val key: String, val label: String, val count: Int? = null)

/**
 * The scope at the start of a [RaftFitToolbar] (All / Humans / Agents with their counts): a
 * [RaftSegmentedControl], a Select once the toolbar gives way (its trigger names the current
 * scope alone, the counts in its menu). [label] is its accessible name ("Show").
 *
 * [testTag] tags the segmented control, each segment `<testTag>-<key>`, the Select's trigger
 * `<testTag>-select` and its options `<testTag>-select-<key>`.
 */
class RaftFitToolbarScope(
    val options: List<RaftFitToolbarOption>,
    val selectedKey: String,
    val onSelect: (String) -> Unit,
    val label: String,
    val testTag: String? = null,
)

/**
 * The search of a [RaftFitToolbar]: the field that fills the rest of the first line. [label] is
 * its accessible name and, as a button, its tooltip ("Search members"); [placeholder] the hint
 * in the field (the label by default), cut after a whole word with "…" where it doesn't fit;
 * [clearLabel] adds the × that empties it once it has text ("Clear search"). [testTag] is on
 * the text field.
 */
class RaftFitToolbarSearch(
    val value: String,
    val onValueChange: (String) -> Unit,
    val label: String,
    val placeholder: String = label,
    val clearLabel: String? = null,
    val testTag: String? = null,
    /**
     * Whether a search given way to a button is open over the scope, when the page holds it (so a
     * page can close it on system Back with a RaftBackHandler); null [onOpenChange]: the toolbar
     * holds it itself.
     */
    val open: Boolean = false,
    val onOpenChange: ((Boolean) -> Unit)? = null,
    /**
     * Told whether the search is drawn over the scope (opened from its button, or holding text
     * while given way): the state a page's Back handler closes, as Esc does (text first).
     */
    val onShownOverScopeChange: ((Boolean) -> Unit)? = null,
)

/**
 * A picker of one value on a [RaftFitToolbar]: a filter, or a display control (sort, grouping,
 * view). A [RaftPickerTriggerButton] with [icon] and [text] (the value, or the filter's name)
 * that opens a menu of [options], [selectedKey] checked; [applied] fills it `info-soft`
 * (an applied filter, a grouping off its default). Given way, it shows its icon alone, named
 * and tooltipped by [contentDescription]; in More it is [heading] over its options.
 * [icon] fills the box it is given, in [build.raft.designsystem.theme.LocalRaftContentColor].
 * [testTag] is on the trigger, each option `<testTag>-<key>`.
 *
 * [menu], when given, is the menu instead of [options]: a choice of several values (checkboxes,
 * the menu staying open while picking) or of two values in one list (a sort's key and its
 * direction, each in its own group). It closes the menu with `close` when it means to; in More it
 * shows under [heading] the same way.
 *
 * [key] names the choice across recompositions (default: [testTag], else [heading], else [text];
 * pass one when the text is the value and changes): its open
 * menu stays with it when the page reorders its choices (applied filters first).
 *
 * [onOpen], when given, is what the trigger does instead of opening a menu: a filter whose picker
 * the page draws itself (a searchable sheet of many people or channels). Such a choice belongs
 * with the filters, which never fold into More.
 */
class RaftFitToolbarChoice(
    val text: String,
    val icon: @Composable () -> Unit,
    val options: List<RaftFitToolbarOption>,
    val selectedKey: String?,
    val onSelect: (String) -> Unit,
    val heading: String? = null,
    val contentDescription: String = text,
    val applied: Boolean = false,
    val testTag: String? = null,
    val menu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
    val key: String = testTag ?: heading ?: text,
    val onOpen: (() -> Unit)? = null,
)

/** One view of a [RaftFitToolbarView]: [icon] (in a 16dp box, filling it) and [label], its name. */
class RaftFitToolbarViewOption(
    val key: String,
    val label: String,
    val icon: @Composable () -> Unit,
)

/**
 * The view switch of a [RaftFitToolbar] (web `ListViewSwitch`: Cards / List), after the display
 * controls and giving way with them: a [RaftSegmentedControl] of icons and words, its icons alone
 * from the first step, and in More [heading] over the views. [label] is its accessible name
 * ("Computers view"). [testTag] tags the control, each segment `<testTag>-<key>` and each More
 * item `<testTag>-more-<key>`.
 */
class RaftFitToolbarView(
    val options: List<RaftFitToolbarViewOption>,
    val selectedKey: String,
    val onSelect: (String) -> Unit,
    val label: String,
    val heading: String? = null,
    val testTag: String? = null,
)

/**
 * A secondary action of a [RaftFitToolbar] (Invite human beside Create agent): a `default` `md`
 * [RaftButton] with [icon] and [text], its icon alone once the toolbar gives way (the text its
 * name and tooltip), a More item after that. [icon] fills the box it is given.
 */
class RaftFitToolbarAction(
    val text: String,
    val icon: @Composable () -> Unit,
    val onClick: () -> Unit,
    val testTag: String? = null,
)

/**
 * The primary action at the end of a [RaftFitToolbar] (Create agent): a `primary` `md` button,
 * + once the toolbar gives way (its [text] kept as its name and tooltip). With a [menu] (and
 * its [menuLabel], the ▾'s name: "More ways to add an agent") it is a split button (raft-ui
 * Button › Split buttons): the ▾ opens [menu], the other ways to the same result. It never
 * folds into More. [testTag] is on the main part, [menuTestTag] on the ▾.
 */
class RaftFitToolbarPrimary(
    val text: String,
    val onClick: () -> Unit,
    val icon: (@Composable () -> Unit)? = null,
    val menuLabel: String? = null,
    val menu: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)? = null,
    val testTag: String? = null,
    val menuTestTag: String? = null,
)

object RaftFitToolbarDefaults {
    /** Between two parts of a line, and between the two lines. */
    val Gap: Dp = 8.dp

    /** The page's edge, inside the toolbar's full-width seam. */
    val HorizontalPadding: Dp = 16.dp

    /**
     * The most the search's least width may be (raft-ui `FIT_SEARCH_MAX_LEAST`): its icon,
     * its padding and "Search…". A shorter placeholder's own width wins.
     */
    val SearchMaxLeast: Dp = 120.dp

    /** A line's controls with a finger (a mouse or trackpad takes the shared row height, 34). */
    internal val TouchControlHeight: Dp = 32.dp

    /** A field's room besides its placeholder: 13.5dp edges (inside the 1.5dp border), the 18dp icon and 8dp after it. */
    internal val SearchChrome: Dp = 13.5.dp + 18.dp + 8.dp + 13.5.dp

    /** raft-ui ButtonSplitMenu `md`: `w-7`. */
    internal val SplitMenuWidth: Dp = 28.dp
}

/**
 * raft-ui FitToolbar as the toolbar over a list (raft-design responsive.md › The toolbar over a
 * list; slock web `listToolbar.ts`): one line of
 * `[scope] [search] [filters] [display] [secondary] [⋯ More] [primary]`, the search filling the
 * rest of it. It measures every part in each of its forms and, as its own width narrows (never
 * the window's), gives way in this order until the line fits ([RaftFitToolbarStep]):
 *
 * 1. the display controls show only their icons;
 * 2. the filters show only their icons;
 * 3. with [wrap], the filters move to a second line that scrolls sideways, their labels back;
 * 4. the secondary actions show only their icons;
 * 5. the primary action becomes + (a split keeps its ▾);
 * 6. the scope's segmented control becomes a Select;
 * 7. the display controls and the secondary actions fold into ⋯ More together (More shows
 *    only then; its menu holds them as headed choices and items);
 * 8. the search becomes its icon button at the line's start, which opens the field over the
 *    scope until it is empty and loses focus (or Esc); a search with text stays open.
 *
 * The search keeps at least its icon and whole placeholder, or [RaftFitToolbarDefaults.SearchMaxLeast]
 * if less. At most two lines, the second only for the filters. Each line is the controls' height
 * (32dp with a finger, 34dp with a mouse or trackpad) in the header air: 49dp with a finger
 * (8 + 32 + 8 + the seam) and 44dp with a mouse (5 + 34 + 4 + the seam), the 1dp `line-seam`
 * across the full width under it. The segmented scope's track hangs 3dp into the air. The page
 * gives the sides ([horizontalPadding]) and the background.
 *
 * Every part is optional; a toolbar with no search lets the line-one filters fill the rest, or
 * pins what follows the scope at the line's end. [moreLabel] names More ("More").
 *
 * [filtersEnd] is pinned after the filters while it is given (web Computers' Clear all): a ghost
 * button with its words, its icon alone (its words its name and tooltip) once the filters show
 * their icons or move to the second line. [view] follows the display controls (web Cards / List)
 * and gives way with them.
 */
@Composable
fun RaftFitToolbar(
    modifier: Modifier = Modifier,
    scope: RaftFitToolbarScope? = null,
    search: RaftFitToolbarSearch? = null,
    filters: List<RaftFitToolbarChoice> = emptyList(),
    filtersEnd: RaftFitToolbarAction? = null,
    display: List<RaftFitToolbarChoice> = emptyList(),
    view: RaftFitToolbarView? = null,
    secondary: List<RaftFitToolbarAction> = emptyList(),
    primary: RaftFitToolbarPrimary? = null,
    moreLabel: String = LocalRaftStrings.current.moreActions,
    wrap: Boolean = false,
    horizontalPadding: Dp = RaftFitToolbarDefaults.HorizontalPadding,
) {
    val fine = RaftTheme.pointerInput == RaftPointerInput.Fine
    val controlHeight = RaftTheme.controls.rowHeight ?: RaftFitToolbarDefaults.TouchControlHeight
    // raft-design Spacing › Heights: the header air round the controls, less the seam below.
    val airTop = if (fine) 5.dp else 8.dp
    val airBottom = if (fine) 4.dp else 8.dp
    val seam = RaftTheme.colors.lineSeam
    val placeholderStyle = RaftTheme.typography.field
    val measurer = rememberTextMeasurer()
    var ownSearchOpen by remember { mutableStateOf(false) }
    val searchOpen = if (search?.onOpenChange != null) search.open else ownSearchOpen
    val setSearchOpen: (Boolean) -> Unit = { open -> search?.onOpenChange?.invoke(open) ?: run { ownSearchOpen = open } }
    // What the last measure chose, reported after it: an open search the toolbar no longer gives
    // way (it widened) is closed, and the page learns when the search covers the scope.
    // Null until the first measure: an effect may run before it, and must not close a search the
    // toolbar is about to give way.
    val measuredCollapsed = remember { mutableStateOf<Boolean?>(null) }
    val measuredOverScope = remember { mutableStateOf(false) }
    val currentSetSearchOpen by rememberUpdatedState(setSearchOpen)
    val onShownOverScope by rememberUpdatedState(search?.onShownOverScopeChange)
    LaunchedEffect(measuredCollapsed.value, searchOpen) {
        if (measuredCollapsed.value == false && searchOpen) currentSetSearchOpen(false)
    }
    LaunchedEffect(measuredOverScope.value) { onShownOverScope?.invoke(measuredOverScope.value) }
    SubcomposeLayout(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = RaftStroke.Hairline.toPx()
                drawRect(seam, topLeft = Offset(0f, size.height - stroke), size = Size(size.width, stroke))
            }
            .clipToBounds()
            .padding(start = horizontalPadding, end = horizontalPadding, top = airTop, bottom = airBottom + RaftStroke.Hairline),
    ) { constraints ->
        val available = constraints.maxWidth
        val gap = RaftFitToolbarDefaults.Gap.roundToPx()
        val lineHeight = controlHeight.roundToPx()
        val searchChrome = RaftFitToolbarDefaults.SearchChrome.roundToPx()
        val free = Constraints()

        // Every part in each of its forms, composed only to be measured: never placed, and with
        // no semantics, so nothing reads or finds it twice. Their menus never open.
        fun natural(key: String, content: @Composable () -> Unit): Int =
            subcompose(key) { Box(Modifier.clearAndSetSemantics {}) { content() } }.maxOfOrNull { it.measure(free).width } ?: 0

        fun group(widths: List<Int>): Int = if (widths.isEmpty()) 0 else widths.sum() + gap * (widths.size - 1)

        fun shown(key: String, within: Constraints = free, content: @Composable () -> Unit): Placeable =
            subcompose(key) { Box(propagateMinConstraints = true) { content() } }.first().measure(within)

        val scopeWidths =
            scope?.let {
                natural("measure-scope") { RaftFitToolbarScopeSegmented(it) } to
                    natural("measure-scope-select") { RaftFitToolbarScopeSelect(it, controlHeight) }
            }
        val searchMin =
            if (search == null) {
                0
            } else {
                val hint = search.placeholder.ifEmpty { search.label }
                val hintWidth = measurer.measure(hint, placeholderStyle, softWrap = false, maxLines = 1).size.width
                minOf(hintWidth + searchChrome, RaftFitToolbarDefaults.SearchMaxLeast.roundToPx())
            }
        val endWidths =
            filtersEnd?.let { end ->
                natural("measure-filters-end") { RaftFitToolbarFiltersEnd(end, false) } to
                    natural("measure-filters-end-icon") { RaftFitToolbarFiltersEnd(end, true) }
            }
        val viewWidths =
            view?.let { v ->
                natural("measure-view") { RaftFitToolbarViewSwitch(v, false) } to
                    natural("measure-view-icon") { RaftFitToolbarViewSwitch(v, true) }
            }
        val widths =
            RaftFitToolbarWidths(
                gap = gap,
                scope = scopeWidths?.first ?: 0,
                scopeSelect = scopeWidths?.second ?: 0,
                searchMin = searchMin,
                searchButton = if (search == null) 0 else lineHeight,
                filters =
                    group(
                        filters.mapIndexed { i, c -> natural("measure-filter-$i") { RaftFitToolbarPicker(c, false, controlHeight) } } +
                            listOfNotNull(endWidths?.first),
                    ),
                filterIcons =
                    group(
                        filters.mapIndexed { i, c -> natural("measure-filter-icon-$i") { RaftFitToolbarPicker(c, true, controlHeight) } } +
                            listOfNotNull(endWidths?.second),
                    ),
                display =
                    group(
                        display.mapIndexed { i, c -> natural("measure-display-$i") { RaftFitToolbarPicker(c, false, controlHeight) } } +
                            listOfNotNull(viewWidths?.first),
                    ),
                displayIcons =
                    group(
                        display.mapIndexed { i, c -> natural("measure-display-icon-$i") { RaftFitToolbarPicker(c, true, controlHeight) } } +
                            listOfNotNull(viewWidths?.second),
                    ),
                secondary = group(secondary.mapIndexed { i, a -> natural("measure-secondary-$i") { RaftFitToolbarActionButton(a, false) } }),
                secondaryIcons = group(secondary.mapIndexed { i, a -> natural("measure-secondary-icon-$i") { RaftFitToolbarActionButton(a, true) } }),
                more = lineHeight,
                primary = primary?.let { p -> natural("measure-primary") { RaftFitToolbarPrimaryButton(p, false) } } ?: 0,
                primaryPlus = primary?.let { p -> natural("measure-primary-plus") { RaftFitToolbarPrimaryButton(p, true) } } ?: 0,
            )
        val forms = raftFitToolbarForms(raftFitToolbarStep(widths, available, wrap), wrap)
        val searchCollapsed = search != null && forms.searchButton
        val searchOverScope = searchCollapsed && (searchOpen || search?.value?.isNotEmpty() == true)
        if (measuredCollapsed.value != searchCollapsed) measuredCollapsed.value = searchCollapsed
        if (measuredOverScope.value != searchOverScope) measuredOverScope.value = searchOverScope
        val hasFilters = filters.isNotEmpty() || filtersEnd != null
        val filtersOnFirstLine = hasFilters && !forms.filtersWrapped

        // The first line, in order; the filler takes what the others leave.
        val parts = ArrayList<RaftFitPart>(10)
        if (searchCollapsed) {
            parts +=
                if (searchOverScope) {
                    RaftFitPart(null, widths.searchButton)
                } else {
                    RaftFitPart(shown("search-button") { RaftFitToolbarSearchButton(search!!) { setSearchOpen(true) } })
                }
        }
        if (scope != null) {
            parts +=
                if (searchOverScope) {
                    // Hidden under the open search, its room kept so nothing else moves.
                    RaftFitPart(null, if (forms.scopeSelect) widths.scopeSelect else widths.scope)
                } else {
                    RaftFitPart(
                        shown("scope") {
                            if (forms.scopeSelect) RaftFitToolbarScopeSelect(scope, controlHeight) else RaftFitToolbarScopeSegmented(scope)
                        },
                    )
                }
        }
        val fillerIndex = parts.size
        val fillerKind =
            when {
                search != null && !searchCollapsed -> RaftFitFiller.Search
                search == null && filtersOnFirstLine -> RaftFitFiller.Filters
                else -> RaftFitFiller.Space
            }
        parts += RaftFitPart(null, 0, filler = true)
        if (filtersOnFirstLine && fillerKind != RaftFitFiller.Filters) {
            // A scrolling row needs a bounded width: at most the line (the step chosen leaves it room).
            parts +=
                RaftFitPart(
                    shown("filters", Constraints(maxWidth = available)) {
                        RaftFitToolbarFilterRow(filters, filtersEnd, forms.filterIcons, forms.filterIcons, controlHeight)
                    },
                )
        }
        val folded = display.isNotEmpty() || view != null || secondary.isNotEmpty()
        if (forms.inMore) {
            if (folded) {
                parts +=
                    RaftFitPart(
                        shown("more") {
                            RaftFitToolbarMore(moreLabel, display, view, secondary)
                        },
                    )
            }
        } else {
            display.forEach { choice ->
                parts += RaftFitPart(shown("display-${choice.key}") { RaftFitToolbarPicker(choice, forms.displayIcons, controlHeight) })
            }
            if (view != null) {
                parts += RaftFitPart(shown("view") { RaftFitToolbarViewSwitch(view, forms.displayIcons) })
            }
            secondary.forEachIndexed { i, action ->
                parts += RaftFitPart(shown("secondary-$i") { RaftFitToolbarActionButton(action, forms.secondaryIcons) })
            }
        }
        if (primary != null) {
            parts += RaftFitPart(shown("primary") { RaftFitToolbarPrimaryButton(primary, forms.primaryPlus) })
        }

        // A spacer filler takes no gaps of its own; the search or the filters take theirs.
        val counted = parts.filterIndexed { i, _ -> i != fillerIndex || fillerKind != RaftFitFiller.Space }
        val fixed = parts.filterIndexed { i, _ -> i != fillerIndex }.sumOf { it.width } + gap * (counted.size - 1).coerceAtLeast(0)
        val fillerWidth = (available - fixed).coerceAtLeast(0)
        val filler =
            when (fillerKind) {
                RaftFitFiller.Search ->
                    shown("search", Constraints.fixedWidth(fillerWidth)) {
                        RaftFitToolbarSearchField(
                            search!!,
                            raftFitSearchHint(search, fillerWidth - searchChrome, measurer, placeholderStyle),
                            collapsed = false,
                            onClose = { setSearchOpen(false) },
                        )
                    }
                RaftFitFiller.Filters ->
                    shown("filters", Constraints.fixedWidth(fillerWidth)) {
                        RaftFitToolbarFilterRow(filters, filtersEnd, forms.filterIcons, forms.filterIcons, controlHeight)
                    }
                RaftFitFiller.Space -> null
            }
        parts[fillerIndex] = RaftFitPart(filler, fillerWidth, filler = true)

        val xs = IntArray(parts.size)
        var x = 0
        parts.forEachIndexed { i, part ->
            xs[i] = x
            x += part.width + if (i == fillerIndex && fillerKind == RaftFitFiller.Space) 0 else gap
        }
        // The open search covers the line from its start to the room the search had.
        val overScope =
            if (searchOverScope && search != null) {
                // From the line's start to where the part after the search's room begins.
                val end = xs[fillerIndex] + fillerWidth - if (fillerIndex + 1 < parts.size) gap else 0
                val width = end.coerceAtLeast(lineHeight)
                shown("search", Constraints.fixedWidth(width)) {
                    RaftFitToolbarSearchField(
                        search,
                        raftFitSearchHint(search, width - searchChrome, measurer, placeholderStyle),
                        collapsed = true,
                        onClose = { setSearchOpen(false) },
                    )
                }
            } else {
                null
            }
        val secondLine =
            if (forms.filtersWrapped) {
                // Their words back on their own line; the end (Clear all) stays its icon.
                shown("filters", Constraints(maxWidth = available)) { RaftFitToolbarFilterRow(filters, filtersEnd, false, true, controlHeight) }
            } else {
                null
            }
        // A line is the control height, or taller when large text makes a part taller; a part may
        // hang 3dp into the air above and below (the segmented track), as it does at any size.
        val hang = 2 * 3.dp.roundToPx()
        fun lineFor(tallest: Int): Int = maxOf(lineHeight, tallest - hang)
        val firstLine = lineFor(maxOf(parts.maxOfOrNull { it.placeable?.height ?: 0 } ?: 0, overScope?.height ?: 0))
        val secondLineHeight = secondLine?.let { lineFor(it.height) } ?: 0
        val height = if (secondLine != null) firstLine + gap + secondLineHeight else firstLine
        layout(available, height) {
            parts.forEachIndexed { i, part ->
                val placeable = part.placeable ?: return@forEachIndexed
                // Centred on the line: the segmented track (38 / 40) hangs 3dp into the air.
                placeable.placeRelative(xs[i], (firstLine - placeable.height) / 2)
            }
            overScope?.placeRelative(0, (firstLine - overScope.height) / 2)
            secondLine?.placeRelative(0, firstLine + gap + (secondLineHeight - secondLine.height) / 2)
        }
    }
}

/** A part of the first line: what is drawn (null: nothing, its room kept) and its width. */
private class RaftFitPart(val placeable: Placeable?, val width: Int = placeable?.width ?: 0, val filler: Boolean = false)

private enum class RaftFitFiller { Search, Filters, Space }

private fun Modifier.raftFitTag(tag: String?): Modifier = if (tag == null) this else testTag(tag)

/** The placeholder cut to [room], the field's width less its icon and padding ([raftFitSearchPlaceholder]). */
private fun raftFitSearchHint(
    search: RaftFitToolbarSearch,
    room: Int,
    measurer: TextMeasurer,
    style: TextStyle,
): String =
    raftFitSearchPlaceholder(search.placeholder) { candidate ->
        measurer.measure(candidate, style, softWrap = false, maxLines = 1).size.width <= room
    }

/** The scope as raft-ui SegmentedControl, each segment with its count. */
@Composable
private fun RaftFitToolbarScopeSegmented(scope: RaftFitToolbarScope) {
    RaftSegmentedControl(modifier = Modifier.raftFitTag(scope.testTag)) {
        scope.options.forEach { option ->
            RaftSegment(
                text = option.label,
                selected = option.key == scope.selectedKey,
                onClick = { scope.onSelect(option.key) },
                modifier = Modifier.raftFitTag(scope.testTag?.let { "$it-${option.key}" }),
                count = option.count?.toString(),
            )
        }
    }
}

/**
 * The scope once it has given way (web `ListScopeSelect`): a picker trigger naming the current
 * scope alone; its menu lists every scope with its count, the current one checked.
 */
@Composable
private fun RaftFitToolbarScopeSelect(scope: RaftFitToolbarScope, controlHeight: Dp) {
    var open by remember { mutableStateOf(false) }
    val current = scope.options.firstOrNull { it.key == scope.selectedKey } ?: scope.options.firstOrNull()
    val currentLabel = current?.label.orEmpty()
    val tag = scope.testTag?.let { "$it-select" }
    Box {
        RaftPickerTriggerButton(
            text = currentLabel,
            onClick = { open = true },
            modifier =
                Modifier
                    .heightIn(min = controlHeight)
                    .semantics { contentDescription = "${scope.label}, $currentLabel" }
                    .raftFitTag(tag),
        )
        RaftDropdownMenu(expanded = open, onDismissRequest = { open = false }, minWidth = 160.dp) {
            scope.options.forEach { option ->
                RaftMenuItem(
                    text = option.label,
                    onClick = {
                        open = false
                        scope.onSelect(option.key)
                    },
                    modifier = Modifier.raftFitTag(tag?.let { "$it-${option.key}" }),
                    checked = option.key == scope.selectedKey,
                    shortcut = option.count?.toString(),
                )
            }
        }
    }
}

/** The search's icon button (the last step): it opens the field over the scope. */
@Composable
private fun RaftFitToolbarSearchButton(search: RaftFitToolbarSearch, onOpen: () -> Unit) {
    RaftIconButton(onClick = onOpen, tooltip = search.label, variant = RaftButtonVariant.Ghost, size = RaftIconButtonSize.Md) {
        RaftGlyphIcon(RaftGlyph.Search, size = 18.dp)
    }
}

/**
 * The search field (raft-ui FitToolbarSearch): an `md` input group with the search icon, the
 * cut [placeholder], and × once it has text. Esc empties it, then (opened from its button,
 * [collapsed]) closes it; opened from its button it takes focus, and closes once it is empty
 * and loses focus.
 */
@Composable
private fun RaftFitToolbarSearchField(
    search: RaftFitToolbarSearch,
    placeholder: String,
    collapsed: Boolean,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    if (collapsed) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
    val clearLabel = search.clearLabel
    val value = search.value
    RaftTextField(
        value = value,
        onValueChange = search.onValueChange,
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        hadFocus = true
                    } else if (hadFocus) {
                        hadFocus = false
                        if (collapsed && value.isEmpty()) onClose()
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || event.key != Key.Escape) {
                        false
                    } else if (value.isNotEmpty()) {
                        search.onValueChange("")
                        true
                    } else if (collapsed) {
                        onClose()
                        true
                    } else {
                        false
                    }
                }
                .semantics { contentDescription = search.label }
                .raftFitTag(search.testTag),
        placeholder = placeholder,
        leading = { RaftGlyphIcon(RaftGlyph.Search, size = 16.dp) },
        trailingAction =
            if (value.isNotEmpty() && clearLabel != null) {
                {
                    // raft-ui InputGroup `md`: the embedded button is the field less its 6dp insets.
                    RaftTextFieldClearAction(onClear = { search.onValueChange("") }, modifier = Modifier.size(20.dp), label = clearLabel)
                }
            } else {
                null
            },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        size = RaftTextFieldSize.Md,
    )
}

/** A filter or display control (web FitToolbarPicker on a Select): the trigger and its menu of options. */
@Composable
private fun RaftFitToolbarPicker(choice: RaftFitToolbarChoice, iconsOnly: Boolean, controlHeight: Dp) {
    var open by remember { mutableStateOf(false) }
    Box {
        val trigger: @Composable () -> Unit = {
            RaftPickerTriggerButton(
                // No label: one gap between the icon and the chevron.
                text = if (iconsOnly) "" else choice.text,
                onClick = { choice.onOpen?.invoke() ?: run { open = true } },
                modifier =
                    Modifier
                        .heightIn(min = controlHeight)
                        .semantics { contentDescription = choice.contentDescription }
                        .raftFitTag(choice.testTag),
                hasValue = choice.applied,
                leadingIcon = choice.icon,
            )
        }
        if (iconsOnly) RaftTooltip(choice.contentDescription) { trigger() } else trigger()
        RaftDropdownMenu(expanded = open, onDismissRequest = { open = false }, minWidth = 190.dp, alignEnd = true) {
            RaftFitToolbarChoiceItems(choice) { open = false }
        }
    }
}

/** A choice's options as menu items, the chosen one checked; or its own [RaftFitToolbarChoice.menu]. */
@Composable
private fun ColumnScope.RaftFitToolbarChoiceItems(choice: RaftFitToolbarChoice, close: () -> Unit) {
    val menu = choice.menu
    if (menu != null) {
        this.menu(close)
        return
    }
    choice.options.forEach { option ->
        RaftMenuItem(
            text = option.label,
            onClick = {
                close()
                choice.onSelect(option.key)
            },
            modifier = Modifier.raftFitTag(choice.testTag?.let { "$it-${option.key}" }),
            checked = option.key == choice.selectedKey,
        )
    }
}

/**
 * The filters in one row that scrolls sideways when it has less room than they need, [end]
 * (Clear all) pinned after them, its icon alone with [endIconOnly].
 */
@Composable
private fun RaftFitToolbarFilterRow(
    filters: List<RaftFitToolbarChoice>,
    end: RaftFitToolbarAction?,
    iconsOnly: Boolean,
    endIconOnly: Boolean,
    controlHeight: Dp,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(RaftFitToolbarDefaults.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (filters.isNotEmpty()) {
            Row(
                Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(RaftFitToolbarDefaults.Gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Keyed, not placed: a reordered filter keeps its own open menu (review of #4627).
                filters.forEach { choice -> key(choice.key) { RaftFitToolbarPicker(choice, iconsOnly, controlHeight) } }
            }
        }
        if (end != null) RaftFitToolbarFiltersEnd(end, endIconOnly)
    }
}

/** What ends the filters (web Computers' Clear all): a `ghost` `md` button with its words, or its icon alone named by them. */
@Composable
private fun RaftFitToolbarFiltersEnd(action: RaftFitToolbarAction, iconOnly: Boolean) {
    val modifier = Modifier.raftFitTag(action.testTag)
    if (iconOnly) {
        RaftIconButton(onClick = action.onClick, tooltip = action.text, modifier = modifier, variant = RaftButtonVariant.Ghost, size = RaftIconButtonSize.Md) {
            action.icon()
        }
    } else {
        RaftButton(text = action.text, onClick = action.onClick, modifier = modifier, variant = RaftButtonVariant.Ghost, size = RaftButtonSize.Md)
    }
}

/** The view switch (web `ListViewSwitch`): a segmented control of icons and words, or of its icons alone. */
@Composable
private fun RaftFitToolbarViewSwitch(view: RaftFitToolbarView, iconsOnly: Boolean) {
    RaftSegmentedControl(
        modifier = Modifier.semantics { contentDescription = view.label }.raftFitTag(view.testTag),
        iconsOnly = iconsOnly,
    ) {
        view.options.forEach { option ->
            RaftSegment(
                text = option.label,
                selected = option.key == view.selectedKey,
                onClick = { view.onSelect(option.key) },
                modifier = Modifier.raftFitTag(view.testTag?.let { "$it-${option.key}" }),
                icon = option.icon,
            )
        }
    }
}

/** A secondary action: a `default` `md` button, or its icon alone with its words as name and tooltip. */
@Composable
private fun RaftFitToolbarActionButton(action: RaftFitToolbarAction, iconsOnly: Boolean) {
    val modifier = Modifier.raftFitTag(action.testTag)
    if (iconsOnly) {
        RaftIconButton(onClick = action.onClick, tooltip = action.text, modifier = modifier, variant = RaftButtonVariant.Default, size = RaftIconButtonSize.Md) {
            action.icon()
        }
    } else {
        RaftButton(
            text = action.text,
            onClick = action.onClick,
            modifier = modifier,
            variant = RaftButtonVariant.Default,
            size = RaftButtonSize.Md,
            leadingIcon = action.icon,
        )
    }
}

/**
 * ⋯ More (web `ListToolbarMore`): a ghost icon button, shown only while it holds the display
 * controls and the secondary actions; its menu has each display control as its heading over
 * its options, then the secondary actions as items.
 */
@Composable
private fun RaftFitToolbarMore(
    label: String,
    display: List<RaftFitToolbarChoice>,
    view: RaftFitToolbarView?,
    secondary: List<RaftFitToolbarAction>,
) {
    // Its own state: More leaves the toolbar (and its menu closes) once it holds nothing.
    var open by remember { mutableStateOf(false) }
    Box {
        RaftIconButton(onClick = { open = true }, tooltip = label, variant = RaftButtonVariant.Ghost, size = RaftIconButtonSize.Md) {
            RaftGlyphIcon(RaftGlyph.Ellipsis, size = 18.dp)
        }
        RaftDropdownMenu(expanded = open, onDismissRequest = { open = false }, minWidth = 220.dp, alignEnd = true) {
            val close = { open = false }
            display.forEachIndexed { index, choice ->
                if (index > 0) RaftMenuSeparator()
                choice.heading?.let { RaftMenuLabel(it) }
                RaftFitToolbarChoiceItems(choice, close)
            }
            if (view != null) {
                if (display.isNotEmpty()) RaftMenuSeparator()
                view.heading?.let { RaftMenuLabel(it) }
                view.options.forEach { option ->
                    RaftMenuItem(
                        text = option.label,
                        onClick = {
                            close()
                            view.onSelect(option.key)
                        },
                        modifier = Modifier.raftFitTag(view.testTag?.let { "$it-more-${option.key}" }),
                        checked = option.key == view.selectedKey,
                    )
                }
            }
            if ((display.isNotEmpty() || view != null) && secondary.isNotEmpty()) RaftMenuSeparator()
            secondary.forEach { action ->
                RaftMenuItem(
                    text = action.text,
                    onClick = {
                        close()
                        action.onClick()
                    },
                    modifier = Modifier.raftFitTag(action.testTag?.let { "$it-more" }),
                    leadingIcon = action.icon,
                )
            }
        }
    }
}

/**
 * The primary action: a `primary` `md` button with its icon and words, or + ([plus]) with its
 * words as name and tooltip. With a menu, raft-ui's split button: the main part and a 28dp ▾
 * joined on one edge, the die-cut edge round the pair, the ▾ opening the menu.
 */
@Composable
private fun RaftFitToolbarPrimaryButton(primary: RaftFitToolbarPrimary, plus: Boolean) {
    val menu = primary.menu
    val menuLabel = primary.menuLabel
    if (menu == null || menuLabel == null) {
        val modifier = Modifier.raftFitTag(primary.testTag)
        if (plus) {
            RaftIconButton(onClick = primary.onClick, tooltip = primary.text, modifier = modifier, variant = RaftButtonVariant.Primary, size = RaftIconButtonSize.Md) {
                RaftGlyphIcon(RaftGlyph.Plus, size = 18.dp)
            }
        } else {
            RaftButton(
                text = primary.text,
                onClick = primary.onClick,
                modifier = modifier,
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Md,
                leadingIcon = primary.icon,
            )
        }
        return
    }
    var open by remember { mutableStateOf(false) }
    val joined = remember { RaftJoinedButtons() }
    val variant = RaftButtonVariant.Primary
    val look = raftButtonLook(variant, enabled = true, frameless = false, colors = RaftTheme.colors)
    // The pair takes one die-cut edge, round its outside; each part draws none of its own.
    val partLook = look.copy(outerRing = Color.Transparent)
    val metrics = raftButtonMetrics(RaftButtonSize.Md, variant, RaftTheme.controls)
    val textStyle = raftButtonTextStyle(RaftButtonSize.Md, variant, RaftTheme.typography)
    Box {
        CompositionLocalProvider(LocalRaftJoinedButtons provides joined) {
            Row(Modifier.raftDieCutEdge(look.outerRing, RaftRadius.Control), verticalAlignment = Alignment.CenterVertically) {
                val main: @Composable () -> Unit = {
                    RaftButtonBox(
                        look = partLook,
                        onClick = primary.onClick,
                        modifier =
                            Modifier
                                .height(metrics.height)
                                .then(if (plus) Modifier.width(metrics.height).semantics { contentDescription = primary.text } else Modifier)
                                .raftFitTag(primary.testTag),
                        enabled = true,
                        loading = false,
                        loadingLabel = "",
                        interactionSource = null,
                        join = RaftButtonJoin.End,
                    ) {
                        if (plus) {
                            RaftGlyphIcon(RaftGlyph.Plus, size = 18.dp)
                        } else {
                            val icon = primary.icon
                            val start = (if (icon != null) metrics.iconSidePaddingX else metrics.paddingX) + RaftStroke.Control
                            Row(
                                Modifier.padding(start = start, end = metrics.paddingX + RaftStroke.Control),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (icon != null) Box(Modifier.size(metrics.iconSize), contentAlignment = Alignment.Center) { icon() }
                                RaftText(primary.text, style = textStyle, color = look.ink, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (plus) RaftTooltip(primary.text) { main() } else main()
                RaftTooltip(menuLabel) {
                    RaftButtonBox(
                        look = partLook,
                        onClick = { open = true },
                        modifier =
                            Modifier
                                .height(metrics.height)
                                .width(RaftFitToolbarDefaults.SplitMenuWidth)
                                .semantics { contentDescription = menuLabel }
                                .raftFitTag(primary.menuTestTag),
                        enabled = true,
                        loading = false,
                        loadingLabel = "",
                        interactionSource = null,
                        join = RaftButtonJoin.Start,
                        open = open,
                    ) {
                        RaftGlyphIcon(RaftGlyph.ChevronDown, size = 16.dp)
                    }
                }
            }
        }
        RaftDropdownMenu(expanded = open, onDismissRequest = { open = false }, minWidth = 220.dp, alignEnd = true) {
            this.menu { open = false }
        }
    }
}
