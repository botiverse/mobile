package build.raft.app

import build.raft.app.components.SlockBrutalInputField
import build.raft.app.components.SlockRaftMetrics
import build.raft.app.components.SlockRaftSeparator
import build.raft.app.components.slockRaftActive
import build.raft.app.components.slockRaftOverlaySurface
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.compose.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val SlockFilterPopoverWidthValue = 220f
private const val SlockFilterPopoverEstimatedHeightValue = 360f
private const val SlockFilterPopoverVerticalOffsetValue = 8f

/**
 * Generic option shown by [SlockFilterPopover]. `id` must be stable across recompositions
 * — it's the LazyColumn key and the selection identity used by `selectedIds`.
 */
internal data class SlockFilterOption(
    val id: String,
    val label: String,
    val icon: SlockLucideIconName? = null
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
@Composable
internal fun SlockFilterPopover(
    anchor: SlockPopoverAnchor?,
    title: String,
    searchPlaceholder: String,
    searchText: String,
    onSearchChange: (String) -> Unit,
    options: List<SlockFilterOption>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    widthDp: Float = SlockFilterPopoverWidthValue,
    horizontalOffsetDp: Float = 0f,
    verticalOffsetDp: Float = SlockFilterPopoverVerticalOffsetValue,
    multiSelect: Boolean = true,
    surfaceBorderColor: Color = SlockColor.BorderBlack,
    surfaceShadowColor: Color = SlockColor.ShadowBlack,
    selectedRowColor: Color = SlockColor.AppBgLightBlue.copy(alpha = 0.28f),
    searchable: Boolean = true,
    showClearWhenSelected: Boolean = multiSelect,
    headerSlot: (@Composable () -> Unit)? = null
) {
    if (anchor == null) return
    val filtered =
        if (searchText.isBlank()) options
        else options.filter { it.label.contains(searchText, ignoreCase = true) }
    if (slockRaftActive()) {
        SlockRaftFilterPopover(
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
                        SlockBrutalInputField(
                            value = searchText,
                            onValueChange = onSearchChange,
                            placeholder = searchPlaceholder,
                            autoFocus = false,
                            maxLines = 1,
                            textStyle = SlockRaftFilterPopoverInputStyle(),
                            fieldMinHeight = SlockRaftFilterSearchHeight,
                            horizontalPadding = SlockRaftFilterSearchPaddingHorizontal,
                            verticalPadding = 0.dp,
                            showShadow = false,
                            // The search row is chromeless: only the hairline below it.
                            borderColor = Color.Transparent,
                            enabledBackgroundColor = Color.Transparent,
                            placeholderColor = SlockColor.ForegroundHint,
                            // Same as the brutal field above: the whole 40dp row focuses the
                            // editor (iOS's native hit region is smaller than the row).
                            containerTapRequestsFocus = true,
                            showClearWhenNotEmpty = true,
                            onClear = { onSearchChange("") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    null
                }
        )
        return
    }
    val density = LocalDensity.current
    val shadowOffset = if (surfaceShadowColor == Color.Transparent) 0.dp else 4.dp
    val popoverWidth = widthDp.dp
    val popoverSize =
        with(density) {
            Size(
                width = (popoverWidth + shadowOffset).toPx(),
                height = (SlockFilterPopoverEstimatedHeightValue.dp + shadowOffset).toPx()
            )
        }
    val horizontalOffsetPx = with(density) { horizontalOffsetDp.dp.toPx() }
    val verticalOffsetPx = with(density) { verticalOffsetDp.dp.toPx() }
    val viewportGapPx = with(density) { SlockFloatingDefaultViewportGap.dp.toPx() }
    SlockModalAnchoredPopover(
        anchor = anchor,
        contentSize = popoverSize,
        onDismiss = onDismiss,
        viewportGap = viewportGapPx,
        horizontalOffset = horizontalOffsetPx,
        verticalOffset = verticalOffsetPx,
        // Filter chip anchors are their left edge; clamp to the viewport
        // instead of flipping the popover entirely to the chip's left.
        horizontalAutoFlip = false,
        verticalPlacement = SlockFloatingVerticalPlacement.Auto
    ) {
        Box(
            modifier = Modifier
                .padding(end = shadowOffset, bottom = shadowOffset)
        ) {
            if (shadowOffset > 0.dp) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .offset(x = shadowOffset, y = shadowOffset)
                        .background(surfaceShadowColor)
                )
            }
            Column(
                modifier = Modifier
                    .width(popoverWidth)
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, surfaceBorderColor)
                    // A filter popover owns a LazyColumn. The native capture used
                    // by slockFloatingContentClickTarget intercepts its drag stream
                    // before Kuikly delivers it to that list. The empty clickable
                    // still prevents taps on card whitespace from dismissing the
                    // overlay while leaving the nested list scrollable.
                    .clickable(onClick = {})
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SlockColor.SurfaceWhite)
                        .border(SlockStroke.Thin, SlockColor.BorderBlack.copy(alpha = 0.1f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title.slockDisplayUppercase(),
                        style = SlockType.Caption.copy(
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            letterSpacing = 0.9.sp
                        ).withSlockFontWeight(FontWeight.Bold),
                        color = SlockColor.TextMutedGray
                    )
                    Box(modifier = Modifier.weight(1f))
                    if (showClearWhenSelected && selectedIds.isNotEmpty()) {
                        Text(
                            text = stringResource(MR.strings.common_clear),
                            style = SlockType.Caption.copy(
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                letterSpacing = 0.9.sp
                            ).withSlockFontWeight(FontWeight.Bold),
                            color = SlockColor.TextMutedGray,
                            modifier = Modifier.clickable(onClick = onClear)
                        )
                    }
                }
                headerSlot?.invoke()
                if (searchable) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(SlockStroke.Thin, SlockColor.BorderBlack.copy(alpha = 0.1f))
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        SlockBrutalInputField(
                            value = searchText,
                            onValueChange = onSearchChange,
                            placeholder = searchPlaceholder,
                            autoFocus = false,
                            maxLines = 1,
                            // iOS keeps the native editor's precise hit region smaller
                            // than the brutal field chrome. Let the full visible field
                            // request focus so taps on its padding still open the editor.
                            containerTapRequestsFocus = true,
                            showClearWhenNotEmpty = true,
                            onClear = { onSearchChange("") },
                            modifier = Modifier
                                .fillMaxWidth()
                        )
                    }
                }
                if (filtered.isEmpty()) {
                    Text(
                        text = stringResource(MR.strings.common_no_matches),
                        style = SlockType.Caption,
                        color = SlockColor.TextMutedGray,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)
                    )
                } else {
                    Box(modifier = Modifier.fillMaxWidth().heightIn(max = 256.dp)) {
                        LazyColumn {
                            items(filtered.size, key = { i -> filtered[i].id }) { index ->
                                val option = filtered[index]
                                val selected = option.id in selectedIds
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(36.dp)
                                            .background(if (selected) selectedRowColor else SlockColor.SurfaceWhite)
                                            .clickable {
                                                onToggle(option.id)
                                                if (!multiSelect) onDismiss()
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        option.icon?.let {
                                            Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                                                SlockLucideIcon(it, modifier = Modifier.size(12.dp), color = "#80000000")
                                            }
                                        }
                                        Text(
                                            text = option.label,
                                            style = SlockType.Caption.copy(
                                                fontSize = 12.sp,
                                                lineHeight = 16.sp
                                            ).withSlockFontWeight(FontWeight.Bold),
                                            color = SlockColor.TextBlack,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (selected) {
                                            SlockLucideIcon(SlockLucideIconName.Check, modifier = Modifier.size(12.dp), color = "#FF000000")
                                        }
                                    }
                                    if (index < filtered.lastIndex) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = SlockStroke.Thin)
                                                .background(SlockColor.BorderBlack.copy(alpha = 0.1f))
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
}

@Composable
internal fun SlockFilterPopover(
    anchor: SlockPopoverAnchor?,
    title: String,
    searchPlaceholder: String,
    searchText: TextFieldValue,
    onSearchChange: (TextFieldValue) -> Unit,
    options: List<SlockFilterOption>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    widthDp: Float = SlockFilterPopoverWidthValue,
    horizontalOffsetDp: Float = 0f,
    verticalOffsetDp: Float = SlockFilterPopoverVerticalOffsetValue,
    multiSelect: Boolean = true,
    surfaceBorderColor: Color = SlockColor.BorderBlack,
    surfaceShadowColor: Color = SlockColor.ShadowBlack,
    selectedRowColor: Color = SlockColor.AppBgLightBlue.copy(alpha = 0.28f),
    searchable: Boolean = true,
    showClearWhenSelected: Boolean = multiSelect,
    headerSlot: (@Composable () -> Unit)? = null
) {
    if (anchor == null) return
    val filtered =
        if (searchText.text.isBlank()) options
        else options.filter { it.label.contains(searchText.text, ignoreCase = true) }
    if (slockRaftActive()) {
        SlockRaftFilterPopover(
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
                        SlockBrutalInputField(
                            value = searchText,
                            onValueChange = onSearchChange,
                            placeholder = searchPlaceholder,
                            autoFocus = false,
                            maxLines = 1,
                            textStyle = SlockRaftFilterPopoverInputStyle(),
                            fieldMinHeight = SlockRaftFilterSearchHeight,
                            horizontalPadding = SlockRaftFilterSearchPaddingHorizontal,
                            verticalPadding = 0.dp,
                            showShadow = false,
                            // The search row is chromeless: only the hairline below it.
                            borderColor = Color.Transparent,
                            enabledBackgroundColor = Color.Transparent,
                            placeholderColor = SlockColor.ForegroundHint,
                            // Same as the brutal field above: the whole 40dp row focuses the
                            // editor (iOS's native hit region is smaller than the row).
                            containerTapRequestsFocus = true,
                            showClearWhenNotEmpty = true,
                            onClear = { onSearchChange(TextFieldValue("")) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    null
                }
        )
        return
    }
    val density = LocalDensity.current
    val shadowOffset = if (surfaceShadowColor == Color.Transparent) 0.dp else 4.dp
    val popoverWidth = widthDp.dp
    val popoverSize =
        with(density) {
            Size(
                width = (popoverWidth + shadowOffset).toPx(),
                height = (SlockFilterPopoverEstimatedHeightValue.dp + shadowOffset).toPx()
            )
        }
    val horizontalOffsetPx = with(density) { horizontalOffsetDp.dp.toPx() }
    val verticalOffsetPx = with(density) { verticalOffsetDp.dp.toPx() }
    val viewportGapPx = with(density) { SlockFloatingDefaultViewportGap.dp.toPx() }
    SlockModalAnchoredPopover(
        anchor = anchor,
        contentSize = popoverSize,
        onDismiss = onDismiss,
        viewportGap = viewportGapPx,
        horizontalOffset = horizontalOffsetPx,
        verticalOffset = verticalOffsetPx,
        // Filter chip anchors are their left edge; clamp to the viewport
        // instead of flipping the popover entirely to the chip's left.
        horizontalAutoFlip = false,
        verticalPlacement = SlockFloatingVerticalPlacement.Auto
    ) {
        Box(
            modifier = Modifier
                .padding(end = shadowOffset, bottom = shadowOffset)
        ) {
            if (shadowOffset > 0.dp) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .offset(x = shadowOffset, y = shadowOffset)
                        .background(surfaceShadowColor)
                )
            }
            Column(
                modifier = Modifier
                    .width(popoverWidth)
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, surfaceBorderColor)
                    // A filter popover owns a LazyColumn. The native capture used
                    // by slockFloatingContentClickTarget intercepts its drag stream
                    // before Kuikly delivers it to that list. The empty clickable
                    // still prevents taps on card whitespace from dismissing the
                    // overlay while leaving the nested list scrollable.
                    .clickable(onClick = {})
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SlockColor.SurfaceWhite)
                        .border(SlockStroke.Thin, SlockColor.BorderBlack.copy(alpha = 0.1f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title.slockDisplayUppercase(),
                        style = SlockType.Caption.copy(
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            letterSpacing = 0.9.sp
                        ).withSlockFontWeight(FontWeight.Bold),
                        color = SlockColor.TextMutedGray
                    )
                    Box(modifier = Modifier.weight(1f))
                    if (showClearWhenSelected && selectedIds.isNotEmpty()) {
                        Text(
                            text = stringResource(MR.strings.common_clear),
                            style = SlockType.Caption.copy(
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                letterSpacing = 0.9.sp
                            ).withSlockFontWeight(FontWeight.Bold),
                            color = SlockColor.TextMutedGray,
                            modifier = Modifier.clickable(onClick = onClear)
                        )
                    }
                }
                headerSlot?.invoke()
                if (searchable) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(SlockStroke.Thin, SlockColor.BorderBlack.copy(alpha = 0.1f))
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        SlockBrutalInputField(
                            value = searchText,
                            onValueChange = onSearchChange,
                            placeholder = searchPlaceholder,
                            autoFocus = false,
                            maxLines = 1,
                            // iOS keeps the native editor's precise hit region smaller
                            // than the brutal field chrome. Let the full visible field
                            // request focus so taps on its padding still open the editor.
                            containerTapRequestsFocus = true,
                            showClearWhenNotEmpty = true,
                            onClear = { onSearchChange(TextFieldValue("")) },
                            modifier = Modifier
                                .fillMaxWidth()
                        )
                    }
                }
                if (filtered.isEmpty()) {
                    Text(
                        text = stringResource(MR.strings.common_no_matches),
                        style = SlockType.Caption,
                        color = SlockColor.TextMutedGray,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)
                    )
                } else {
                    Box(modifier = Modifier.fillMaxWidth().heightIn(max = 256.dp)) {
                        LazyColumn {
                            items(filtered.size, key = { i -> filtered[i].id }) { index ->
                                val option = filtered[index]
                                val selected = option.id in selectedIds
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(36.dp)
                                            .background(if (selected) selectedRowColor else SlockColor.SurfaceWhite)
                                            .clickable {
                                                onToggle(option.id)
                                                if (!multiSelect) onDismiss()
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        option.icon?.let {
                                            Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                                                SlockLucideIcon(it, modifier = Modifier.size(12.dp), color = "#80000000")
                                            }
                                        }
                                        Text(
                                            text = option.label,
                                            style = SlockType.Caption.copy(
                                                fontSize = 12.sp,
                                                lineHeight = 16.sp
                                            ).withSlockFontWeight(FontWeight.Bold),
                                            color = SlockColor.TextBlack,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (selected) {
                                            SlockLucideIcon(SlockLucideIconName.Check, modifier = Modifier.size(12.dp), color = "#FF000000")
                                        }
                                    }
                                    if (index < filtered.lastIndex) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = SlockStroke.Thin)
                                                .background(SlockColor.BorderBlack.copy(alpha = 0.1f))
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
}

// raft-ui Combobox / Select popup (web search filters): overlay surface (container radius,
// popover layer, seam ring, float shadow), min 220dp wide; a `py-1.5 pl-4 pr-1.5` header
// with the caption-bold hint label over a hairline; an `h-10 px-3` search row with its own
// hairline; a `p-1.5` list (max 256dp) of 36dp control-radius rows in callout medium ink,
// the selected row bold with a trailing 16dp check. No row dividers, no selected fill.
private val SlockRaftFilterHeaderHeight = 28.dp
private val SlockRaftFilterSearchHeight = 40.dp
private val SlockRaftFilterSearchPaddingHorizontal = 12.dp
private val SlockRaftFilterHeaderSlotHeight = 24.dp
private val SlockRaftFilterListMaxHeight = 256.dp
private val SlockRaftFilterMinWidth = 220.dp

@Composable
private fun SlockRaftFilterPopoverInputStyle() =
    SlockType.Input.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack)

@Composable
private fun SlockRaftFilterPopover(
    anchor: SlockPopoverAnchor,
    title: String,
    filtered: List<SlockFilterOption>,
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
    val popoverWidth = maxOf(widthDp.dp, SlockRaftFilterMinWidth)
    val listHeight =
        if (filtered.isEmpty()) {
            48.dp
        } else {
            minOf(SlockRaftMetrics.MenuRowHeight * filtered.size + SlockRaftMetrics.PopupPadding * 2, SlockRaftFilterListMaxHeight)
        }
    val popoverHeight =
        SlockRaftFilterHeaderHeight + SlockStroke.Thin +
            (if (searchField != null) SlockRaftFilterSearchHeight + SlockStroke.Thin else 0.dp) +
            listHeight +
            // A header slot is a caption-height status line padded 4dp top and bottom.
            (if (headerSlot != null) SlockRaftFilterHeaderSlotHeight else 0.dp)
    SlockModalAnchoredPopover(
        anchor = anchor,
        // raft overlays float on a shadow that takes no layout space.
        contentSize = with(density) { Size(popoverWidth.toPx(), popoverHeight.toPx()) },
        onDismiss = onDismiss,
        viewportGap = with(density) { SlockFloatingDefaultViewportGap.dp.toPx() },
        horizontalOffset = with(density) { horizontalOffsetDp.dp.toPx() },
        verticalOffset = with(density) { verticalOffsetDp.dp.toPx() },
        // Filter chip anchors are their left edge; clamp to the viewport
        // instead of flipping the popover entirely to the chip's left.
        horizontalAutoFlip = false,
        verticalPlacement = SlockFloatingVerticalPlacement.Auto
    ) {
        Column(
            modifier = Modifier
                .width(popoverWidth)
                .slockRaftOverlaySurface()
                // Keeps taps on card whitespace from dismissing the overlay while leaving
                // the nested list scrollable (see the brutal surface above).
                .clickable(onClick = {})
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SlockRaftFilterHeaderHeight)
                    .padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp).withSlockFontWeight(FontWeight.Bold),
                    color = SlockColor.ForegroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (showClearWhenSelected && selectedIds.isNotEmpty()) {
                    Box(
                        modifier = Modifier.clickable(onClick = onClear).padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(MR.strings.common_clear),
                            style = SlockType.Caption
                                .copy(fontSize = 12.sp, lineHeight = 16.sp, textDecoration = TextDecoration.Underline)
                                .withSlockFontWeight(FontWeight.SemiBold),
                            color = SlockColor.TextBlack,
                            maxLines = 1
                        )
                    }
                }
            }
            headerSlot?.let {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) { it() }
            }
            SlockRaftSeparator()
            if (searchField != null) {
                searchField()
                SlockRaftSeparator()
            }
            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(
                        text = stringResource(MR.strings.common_no_matches),
                        style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp),
                        color = SlockColor.ForegroundHint
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = SlockRaftFilterListMaxHeight)) {
                    LazyColumn(modifier = Modifier.padding(SlockRaftMetrics.PopupPadding)) {
                        items(filtered.size, key = { i -> filtered[i].id }) { index ->
                            val option = filtered[index]
                            val selected = option.id in selectedIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(SlockRaftMetrics.MenuRowHeight)
                                    .clickable {
                                        onToggle(option.id)
                                        if (!multiSelect) onDismiss()
                                    }
                                    .padding(horizontal = SlockRaftMetrics.MenuItemPaddingHorizontal),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(SlockRaftMetrics.MenuItemGap)
                            ) {
                                option.icon?.let {
                                    Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                        SlockLucideIcon(it, modifier = Modifier.size(12.dp), color = slockArgbHex(SlockColor.TextBlack))
                                    }
                                }
                                Text(
                                    text = option.label,
                                    style = SlockType.Body
                                        .copy(fontSize = 14.sp, lineHeight = 20.sp)
                                        .withSlockFontWeight(if (selected) FontWeight.Bold else FontWeight.Medium),
                                    color = SlockColor.TextBlack,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (selected) {
                                    SlockLucideIcon(
                                        SlockLucideIconName.Check,
                                        modifier = Modifier.size(SlockRaftMetrics.MenuIconSize),
                                        color = SlockColor.TextBlackArgb,
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
