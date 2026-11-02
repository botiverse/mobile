package build.raft.app

import build.raft.app.resources.*
import build.raft.designsystem.components.RaftMenuSearchField
import build.raft.app.components.RaftMetrics
import build.raft.app.components.RaftLookSeparator
import build.raft.app.components.raftLookOverlaySurface
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import build.raft.app.components.RaftSlotIcon
import build.raft.designsystem.components.RaftComboboxOption
import build.raft.designsystem.components.RaftComboboxSheet
import build.raft.designsystem.components.RaftMultiComboboxSheet
import build.raft.designsystem.components.RaftComboboxPopover
import build.raft.designsystem.components.RaftMultiComboboxPopover

private const val RaftFilterPopoverWidthValue = 220f
private const val RaftFilterPopoverVerticalOffsetValue = 8f

/**
 * Generic option shown by [RaftFilterPopover]. `id` must be stable across recompositions
 * — it's the LazyColumn key and the selection identity used by `selectedIds`.
 */
internal data class RaftFilterOption(
    val id: String,
    val label: String,
    val icon: RaftLucideIconName? = null
)

/**
 * Unified anchored multi-select dropdown for filter chips across the app.
 *
 * Rendered as a full-screen overlay so taps outside the card dismiss without needing a
 * window-level Popup primitive (which Kuikly Compose does not currently expose in
 * commonMain). The card is positioned by translating [anchor] (in screen-root coords)
 * into the overlay's local coord system; on layouts where the overlay isn't the entire
 * root, this keeps the popover visually attached to the trigger chip.
 *
 * Multi-select semantics, with `onToggle(id)` flipping membership in [selectedIds]. A
 * "Clear" chip is shown when at least one option is selected, mirroring the React
 * DropdownMenu pattern. Search is local to the popover state and filters [options] in
 * place; the caller owns [searchText] so the input value can survive recomposition.
 *
 * @param multiSelect When true, tapping an option toggles selection and the popover
 *   stays open (allowing batch selection). When false, the popover auto-dismisses after
 *   the user picks an option — useful for "Creator" style filters where exactly one
 *   value is meaningful at a time.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun RaftFilterPopover(
    anchor: RaftPopoverAnchor?,
    title: String,
    searchPlaceholder: String,
    searchText: String,
    onSearchChange: (String) -> Unit,
    options: List<RaftFilterOption>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    widthDp: Float = RaftFilterPopoverWidthValue,
    horizontalOffsetDp: Float = 0f,
    verticalOffsetDp: Float = RaftFilterPopoverVerticalOffsetValue,
    multiSelect: Boolean = true,
    surfaceBorderColor: Color = RaftColor.BorderBlack,
    surfaceShadowColor: Color = RaftColor.ShadowBlack,
    selectedRowColor: Color = RaftColor.AppBgLightBlue.copy(alpha = 0.28f),
    searchable: Boolean = true,
    showClearWhenSelected: Boolean = multiSelect,
    headerSlot: (@Composable () -> Unit)? = null
) {
    if (anchor == null) return
    val filtered =
        if (searchText.isBlank()) options
        else options.filter { it.label.contains(searchText, ignoreCase = true) }
    RaftLookFilterPopover(
        anchor = anchor,
        title = title,
        filtered = filtered,
        selectedIds = selectedIds,
        onToggle = onToggle,
        onClear = onClear,
        onDismiss = onDismiss,
        widthDp = widthDp,
        horizontalOffsetDp = horizontalOffsetDp,
        verticalOffsetDp = verticalOffsetDp,
        multiSelect = multiSelect,
        showClearWhenSelected = showClearWhenSelected,
        headerSlot = headerSlot,
        searchField =
            if (searchable) {
                {
                    // raft-ui Combobox / Select popup search row (glyph, field type, its own hairline).
                    RaftMenuSearchField(
                        value = searchText,
                        onValueChange = onSearchChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = searchPlaceholder.ifEmpty { null }
                    )
                }
            } else {
                null
            }
    )
}

// raft-ui Combobox / Select popup (web search filters): overlay surface (container radius,
// popover layer, seam ring, float shadow), min 220dp wide; a `py-1.5 pl-4 pr-1.5` header
// with the caption-bold hint label over a hairline; an `h-10 px-3` search row with its own
// hairline; a `p-1.5` list (max 256dp) of 36dp control-radius rows in callout medium ink,
// the selected row bold with a trailing 16dp check. No row dividers, no selected fill.
private val RaftFilterHeaderHeight = 28.dp
private val RaftFilterSearchHeight = 40.dp
private val RaftFilterHeaderSlotHeight = 24.dp
private val RaftFilterListMaxHeight = 256.dp
private val RaftFilterMinWidth = 220.dp

@Composable
private fun RaftLookFilterPopover(
    anchor: RaftPopoverAnchor,
    title: String,
    filtered: List<RaftFilterOption>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    widthDp: Float,
    horizontalOffsetDp: Float,
    verticalOffsetDp: Float,
    multiSelect: Boolean,
    showClearWhenSelected: Boolean,
    headerSlot: (@Composable () -> Unit)?,
    searchField: (@Composable () -> Unit)?
) {
    val density = LocalDensity.current
    val popoverWidth = maxOf(widthDp.dp, RaftFilterMinWidth)
    val listHeight =
        if (filtered.isEmpty()) {
            48.dp
        } else {
            minOf(RaftMetrics.MenuRowHeight * filtered.size + RaftMetrics.PopupPadding * 2, RaftFilterListMaxHeight)
        }
    val popoverHeight =
        RaftFilterHeaderHeight + RaftAppStroke.Thin +
            (if (searchField != null) RaftFilterSearchHeight + RaftAppStroke.Thin else 0.dp) +
            listHeight +
            // A header slot is a caption-height status line padded 4dp top and bottom.
            (if (headerSlot != null) RaftFilterHeaderSlotHeight else 0.dp)
    RaftModalAnchoredPopover(
        anchor = anchor,
        // raft overlays float on a shadow that takes no layout space.
        contentSize = with(density) { Size(popoverWidth.toPx(), popoverHeight.toPx()) },
        onDismiss = onDismiss,
        viewportGap = with(density) { RaftFloatingDefaultViewportGap.dp.toPx() },
        horizontalOffset = with(density) { horizontalOffsetDp.dp.toPx() },
        verticalOffset = with(density) { verticalOffsetDp.dp.toPx() },
        // Filter chip anchors are their left edge; clamp to the viewport
        // instead of flipping the popover entirely to the chip's left.
        horizontalAutoFlip = false,
        verticalPlacement = RaftFloatingVerticalPlacement.Auto
    ) {
        Column(
            modifier = Modifier
                .width(popoverWidth)
                .raftLookOverlaySurface()
                // Keeps taps on card whitespace from dismissing the overlay while leaving
                // the nested list scrollable (see the brutal surface above).
                .clickable(onClick = {})
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(RaftFilterHeaderHeight)
                    .padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp).withRaftFontWeight(FontWeight.Bold),
                    color = RaftColor.ForegroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (showClearWhenSelected && selectedIds.isNotEmpty()) {
                    // raft-ui ComboboxClear: an `h-7 px-2` ghost in `text-footnote` semibold
                    // `foreground-muted`.
                    Box(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(RaftAppRadius.Control))
                            .clickable(onClick = onClear)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(Res.string.common_clear),
                            style = RaftType.Body
                                .copy(fontSize = 13.sp, lineHeight = 18.sp)
                                .withRaftFontWeight(FontWeight.SemiBold),
                            color = RaftColor.TextMuted,
                            maxLines = 1
                        )
                    }
                }
            }
            headerSlot?.let {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) { it() }
            }
            RaftLookSeparator()
            // The search row draws its own hairline under it.
            searchField?.invoke()
            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(
                        text = stringResource(Res.string.common_no_matches),
                        style = RaftType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp),
                        color = RaftColor.ForegroundHint
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = RaftFilterListMaxHeight)) {
                    LazyColumn(modifier = Modifier.padding(RaftMetrics.PopupPadding)) {
                        items(filtered.size, key = { i -> filtered[i].id }) { index ->
                            val option = filtered[index]
                            val selected = option.id in selectedIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(RaftMetrics.MenuRowHeight)
                                    .clickable {
                                        onToggle(option.id)
                                        if (!multiSelect) onDismiss()
                                    }
                                    .padding(horizontal = RaftMetrics.MenuItemPaddingHorizontal),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(RaftMetrics.MenuItemGap)
                            ) {
                                option.icon?.let {
                                    Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                        RaftLucideIcon(it, modifier = Modifier.size(12.dp), color = raftArgbHex(RaftColor.TextBlack))
                                    }
                                }
                                Text(
                                    text = option.label,
                                    style = RaftType.Body
                                        .copy(fontSize = 14.sp, lineHeight = 20.sp)
                                        .withRaftFontWeight(if (selected) FontWeight.Bold else FontWeight.Medium),
                                    color = RaftColor.TextBlack,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (selected) {
                                    RaftLucideIcon(
                                        RaftLucideIconName.Check,
                                        modifier = Modifier.size(RaftMetrics.MenuIconSize),
                                        color = RaftColor.TextBlackArgb,
                                        strokeWidth = 2f
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A searchable filter or picker opened from a chip (Tasks' channel / creator / assignee
 * filters and assignee picker): on a phone, raft-ui ComboboxSelect is a bottom sheet with
 * search ([RaftMultiComboboxSheet] / [RaftComboboxSheet]), not a popover under the chip.
 * Same callbacks as [RaftFilterPopover]: [onToggle] per changed id, [onClear] for "clear
 * all"; a one-value pick ([multiSelect] false) closes the sheet. An empty list says
 * [status] when given (a load in progress or its error), else "No matches". The short fixed
 * lists (sort, time, scope) stay anchored popovers. Search's sender and channel filters
 * open [RaftFilterComboboxPopover] instead, as on the web.
 */
@Composable
internal fun RaftFilterSheet(
    title: String,
    searchPlaceholder: String,
    searchText: String,
    onSearchChange: (String) -> Unit,
    options: List<RaftFilterOption>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    multiSelect: Boolean = true,
    showClear: Boolean = multiSelect,
    status: String? = null
) {
    val comboboxOptions = remember(options) { options.map { RaftComboboxOption(it.id, it.label) } }
    val leading = rememberRaftFilterOptionLeading(options)
    val emptyFor = raftFilterEmptyText(status)
    val clearLabel = stringResource(Res.string.common_clear)
    if (multiSelect) {
        RaftMultiComboboxSheet(
            values = options.map { it.id }.filter { it in selectedIds },
            options = comboboxOptions,
            onValuesChange = { next -> applyRaftFilterSelection(selectedIds, next, onToggle, onClear) },
            onDismissRequest = onDismiss,
            title = title,
            emptyText = emptyFor,
            query = searchText,
            onQueryChange = onSearchChange,
            searchPlaceholder = searchPlaceholder,
            doneLabel = stringResource(Res.string.common_done),
            clearLabel = clearLabel.takeIf { showClear },
            optionLeading = leading
        )
    } else {
        RaftComboboxSheet(
            value = selectedIds.firstOrNull(),
            options = comboboxOptions,
            onValueChange = onToggle,
            onDismissRequest = onDismiss,
            title = title,
            emptyText = emptyFor,
            query = searchText,
            onQueryChange = onSearchChange,
            searchPlaceholder = searchPlaceholder,
            onClear = onClear.takeIf { showClear },
            clearLabel = clearLabel,
            optionLeading = leading
        )
    }
}

/**
 * A searchable filter opened from a chip as the web's search filters open it (raft-ui
 * Combobox popup, artin 2026-11-02: "small dropdowns" around here): a small popup under the
 * chip, at any number of options, with the title, Clear and the search row at its top
 * ([RaftComboboxPopover] / [RaftMultiComboboxPopover]); the search row takes focus only when
 * tapped. Place it in the same `Box` as the chip, shown while [expanded]. Callbacks as
 * [RaftFilterSheet]; a one-value pick ([multiSelect] false) closes the popup, a multiple
 * pick keeps it open.
 */
@Composable
internal fun RaftFilterComboboxPopover(
    expanded: Boolean,
    title: String,
    searchPlaceholder: String,
    searchText: String,
    onSearchChange: (String) -> Unit,
    options: List<RaftFilterOption>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    multiSelect: Boolean = true,
    showClear: Boolean = multiSelect,
    status: String? = null
) {
    val comboboxOptions = remember(options) { options.map { RaftComboboxOption(it.id, it.label) } }
    val leading = rememberRaftFilterOptionLeading(options)
    val emptyFor = raftFilterEmptyText(status)
    val clearLabel = stringResource(Res.string.common_clear)
    if (multiSelect) {
        RaftMultiComboboxPopover(
            expanded = expanded,
            values = options.map { it.id }.filter { it in selectedIds },
            options = comboboxOptions,
            onValuesChange = { next -> applyRaftFilterSelection(selectedIds, next, onToggle, onClear) },
            onDismissRequest = onDismiss,
            title = title,
            emptyText = emptyFor,
            query = searchText,
            onQueryChange = onSearchChange,
            searchPlaceholder = searchPlaceholder,
            clearLabel = clearLabel.takeIf { showClear },
            optionLeading = leading
        )
    } else {
        RaftComboboxPopover(
            expanded = expanded,
            value = selectedIds.firstOrNull(),
            options = comboboxOptions,
            onValueChange = onToggle,
            onDismissRequest = onDismiss,
            title = title,
            emptyText = emptyFor,
            query = searchText,
            onQueryChange = onSearchChange,
            searchPlaceholder = searchPlaceholder,
            onClear = onClear.takeIf { showClear },
            clearLabel = clearLabel,
            optionLeading = leading
        )
    }
}

/** An option's [RaftFilterOption.icon] as the combobox row's leading slot; null when no option has one. */
@Composable
private fun rememberRaftFilterOptionLeading(
    options: List<RaftFilterOption>
): (@Composable (RaftComboboxOption<String>) -> Unit)? {
    val icons = remember(options) { options.mapNotNull { option -> option.icon?.let { option.id to it } }.toMap() }
    val leading: (@Composable (RaftComboboxOption<String>) -> Unit)? =
        if (icons.isEmpty()) {
            null
        } else {
            { option -> icons[option.value]?.let { RaftSlotIcon(it, size = 16.dp) } }
        }
    return leading
}

/** What an empty list says: [status] (a load in progress or its error) when given, else "No matches". */
@Composable
private fun raftFilterEmptyText(status: String?): (String) -> String {
    val noMatches = stringResource(Res.string.common_no_matches)
    return { status ?: noMatches }
}

/** A multiple pick's new [next] values as the filter's callbacks: [onClear] when none is left, else [onToggle] per changed id. */
private fun applyRaftFilterSelection(
    selectedIds: Set<String>,
    next: List<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit
) {
    if (next.isEmpty()) onClear() else raftFilterSelectionToggles(selectedIds, next).forEach(onToggle)
}

/**
 * The ids whose membership differs between [selectedIds] and [next], in the order to toggle
 * them: the ones dropped (in [selectedIds]'s order), then the ones added (in [next]'s order).
 */
internal fun raftFilterSelectionToggles(selectedIds: Set<String>, next: List<String>): List<String> {
    val nextSet = next.toSet()
    return selectedIds.filter { it !in nextSet } + next.distinct().filter { it !in selectedIds }
}
