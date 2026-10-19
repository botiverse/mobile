package build.raft.app.components

import build.raft.app.slockDisplayUppercase
import build.raft.app.slockRaftMenuWidth
import build.raft.app.SlockAnchoredPopover
import build.raft.app.SlockColor
import build.raft.app.SlockFloatingDefaultViewportGap
import build.raft.app.SlockMonoFamily
import build.raft.app.SlockFloatingVerticalPlacement
import build.raft.app.SlockLucideIcon
import build.raft.app.SlockLucideIconName
import build.raft.app.SlockLiveRootAnchorRef
import build.raft.app.SlockPopoverAnchor
import build.raft.app.SlockPopoverLocalPoint
import build.raft.app.SlockModalAnchoredPopover
import build.raft.app.SlockRadius
import build.raft.app.SlockShadow
import build.raft.app.SlockStroke
import build.raft.app.SlockType
import build.raft.app.TextField
import build.raft.app.slockCaptureLiveRootAnchor
import build.raft.app.slockSquareCorners
import build.raft.app.slockFloatingContentClickTarget
import build.raft.app.withSlockFontWeight
import build.raft.app.slockArgbHex
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.RectangleShape
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextDecoration
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

internal data class SlockSelectionPopoverOption(
    val id: String,
    val label: String,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val italic: Boolean = false,
    val reserveLeadingSlot: Boolean = false,
    val leadingIconName: SlockLucideIconName? = null
)

internal data class SlockRaftUiSelectionOption(
    val id: String,
    val label: String,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val italic: Boolean = false,
    val reserveLeadingSlot: Boolean = true,
    val leadingIconName: SlockLucideIconName? = null
)

@Composable
internal fun SlockSelectionPopover(
    title: String,
    options: List<SlockSelectionPopoverOption>,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    showClear: Boolean = false,
    search: String? = null,
    searchPlaceholder: String = "Search...",
    emptyLabel: String? = null,
    onClear: () -> Unit = {},
    onSelect: (SlockSelectionPopoverOption) -> Unit = {}
) {
    if (slockRaftActive()) {
        SlockRaftSelectionPopover(
            title = title,
            options = options,
            modifier = modifier,
            showHeader = showHeader,
            showClear = showClear,
            search = search,
            searchPlaceholder = searchPlaceholder,
            emptyLabel = emptyLabel,
            onClear = onClear,
            onSelect = onSelect
        )
        return
    }
    Box(modifier = modifier.fillMaxWidth().padding(end = 3.dp, bottom = 3.dp)) {
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .offset(x = 3.dp, y = 3.dp)
                    .background(SlockColor.ShadowBlack)
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(SlockColor.SurfaceWhite, RectangleShape)
                    .border(SlockStroke.Default, SlockColor.BorderBlack, RectangleShape)
                    .slockSquareCorners()
        ) {
            if (showHeader) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title.slockDisplayUppercase(),
                        style = SlockType.SectionLabel.copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 1.0.sp),
                        color = SlockColor.TextBlack.copy(alpha = 0.60f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (showClear) {
                        Text(
                            text = stringResource(MR.strings.common_clear),
                            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.5.sp).withSlockFontWeight(FontWeight.Bold),
                            color = SlockColor.TextBlack.copy(alpha = 0.50f),
                            maxLines = 1,
                            modifier = Modifier.clickable { onClear() }
                        )
                    }
                }
                SlockThinDivider()
            }
            if (search != null) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                    SlockBrutalInputField(
                        value = search,
                        onValueChange = {},
                        placeholder = searchPlaceholder,
                        autoFocus = false,
                        fieldMinHeight = 30.dp,
                        horizontalPadding = 8.dp,
                        verticalPadding = 5.dp,
                        textStyle = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextBlack),
                        showShadow = true
                    )
                }
                SlockThinDivider()
            }
            if (options.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = emptyLabel ?: stringResource(MR.strings.common_no_matches),
                        style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 11.sp, lineHeight = 14.sp),
                        color = SlockColor.TextBlack.copy(alpha = 0.40f)
                    )
                }
            } else {
                options.forEachIndexed { index, option ->
                    SlockSelectionPopoverOptionRow(option = option, onSelect = onSelect)
                    if (index != options.lastIndex) {
                        SlockSubtleDivider()
                    }
                }
            }
        }
    }
}

// raft selection-popover geometry (web SelectionPopover): header px-4 pt-2.5 pb-1 with a
// 12/16 caption, a 40dp search row over a hairline, and a p-1.5 list of 36dp rows.
private val SlockRaftSelectionHeaderHeight = 30.dp
private val SlockRaftSelectionSearchHeight = 41.dp

/**
 * raft-ui 0.6.0 selection popover (web `SelectionPopover`): overlay surface (container
 * radius, popover layer, seam ring, float shadow); sentence-case caption header in hint
 * ink with an underlined Clear; optional search row; 36dp control-radius option rows in
 * callout type, the checked row bold with a trailing 16dp check. No row dividers.
 */
@Composable
private fun SlockRaftSelectionPopover(
    title: String,
    options: List<SlockSelectionPopoverOption>,
    modifier: Modifier,
    showHeader: Boolean,
    showClear: Boolean,
    search: String?,
    searchPlaceholder: String,
    emptyLabel: String?,
    onClear: () -> Unit,
    onSelect: (SlockSelectionPopoverOption) -> Unit
) {
    Column(modifier = modifier.fillMaxWidth().slockRaftOverlaySurface()) {
        if (showHeader) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(SlockRaftSelectionHeaderHeight)
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
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
                if (showClear) {
                    Box(modifier = Modifier.clickable { onClear() }) {
                        Text(
                            text = stringResource(MR.strings.common_clear),
                            style =
                                SlockType.Caption
                                    .copy(fontSize = 12.sp, lineHeight = 16.sp, textDecoration = TextDecoration.Underline)
                                    .withSlockFontWeight(FontWeight.SemiBold),
                            color = SlockColor.TextBlack,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        if (search != null) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SlockLucideIcon(
                    SlockLucideIconName.Search,
                    modifier = Modifier.size(16.dp),
                    color = slockArgbHex(SlockColor.ForegroundHint)
                )
                TextField(
                    value = search,
                    onValueChange = {},
                    placeholder = searchPlaceholder,
                    placeholderColor = SlockColor.ForegroundHint,
                    autoFocus = false,
                    maxLines = 1,
                    textStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                    modifier = Modifier.weight(1f)
                )
            }
            SlockRaftSeparator()
        }
        Column(modifier = Modifier.fillMaxWidth().padding(SlockRaftMetrics.PopupPadding)) {
            if (options.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(
                        text = emptyLabel ?: stringResource(MR.strings.common_no_matches),
                        style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp),
                        color = SlockColor.ForegroundHint
                    )
                }
            } else {
                options.forEach { option ->
                    SlockRaftSelectionOptionRow(option = option, onSelect = onSelect)
                }
            }
        }
    }
}

@Composable
private fun SlockRaftSelectionOptionRow(
    option: SlockSelectionPopoverOption,
    onSelect: (SlockSelectionPopoverOption) -> Unit
) {
    val rowEnabled = option.enabled
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(SlockRaftMetrics.MenuRowHeight)
                .clickable(enabled = rowEnabled) { onSelect(option) }
                .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (option.reserveLeadingSlot || option.leadingIconName != null) {
            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                option.leadingIconName?.let {
                    SlockLucideIcon(
                        it,
                        modifier = Modifier.size(16.dp),
                        color = slockArgbHex(if (rowEnabled) SlockColor.TextMuted else SlockColor.ForegroundDisabled)
                    )
                }
            }
        }
        Text(
            text = option.label,
            style =
                SlockType.Body
                    .copy(fontSize = 14.sp, lineHeight = 20.sp)
                    .withSlockFontWeight(if (option.checked && rowEnabled) FontWeight.Bold else FontWeight.Medium),
            color =
                when {
                    !rowEnabled -> SlockColor.ForegroundDisabled
                    option.italic -> SlockColor.TextMuted
                    else -> SlockColor.TextBlack
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (option.checked) {
            SlockLucideIcon(SlockLucideIconName.Check, modifier = Modifier.size(16.dp), color = SlockColor.TextBlackArgb)
        }
    }
}

/** raft-ui `Separator` (1dp `--line-hairline`). [inset] mirrors a menu's `-mx-1.5 my-1.5`. */
@Composable
internal fun SlockRaftSeparator(modifier: Modifier = Modifier, verticalSpacing: Dp = 0.dp) {
    Box(
        modifier =
            modifier
                .padding(vertical = verticalSpacing)
                .fillMaxWidth()
                .height(SlockStroke.Thin)
                .background(SlockColor.BorderHairline)
    )
}

@Composable
internal fun SlockRaftUiSelectionPopover(
    title: String,
    options: List<SlockRaftUiSelectionOption>,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    showHeader: Boolean = true,
    showClear: Boolean = false,
    search: String? = null,
    searchPlaceholder: String = "Search...",
    emptyLabel: String? = null,
    onClear: () -> Unit = {},
    onSelect: (SlockRaftUiSelectionOption) -> Unit = {}
) {
    SlockSelectionPopover(
        title = title,
        options =
            options.map { option ->
                SlockSelectionPopoverOption(
                    id = option.id,
                    label = option.label,
                    checked = option.selected || option.id == selectedId,
                    enabled = option.enabled,
                    italic = option.italic,
                    reserveLeadingSlot = option.reserveLeadingSlot,
                    leadingIconName = option.leadingIconName
                )
            },
        modifier = modifier,
        showHeader = showHeader,
        showClear = showClear,
        search = search,
        searchPlaceholder = searchPlaceholder,
        emptyLabel = emptyLabel,
        onClear = onClear,
        onSelect = { selected ->
            options.firstOrNull { it.id == selected.id }?.let(onSelect)
        }
    )
}

@Composable
internal fun SlockMenuItemRow(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIconName: SlockLucideIconName? = null,
    trailing: String? = null,
    dividerBefore: Boolean = false,
    onClick: () -> Unit = {}
) {
    if (slockRaftActive()) {
        // raft-ui MenuItem: min 36dp, control radius, px-2.5 py-1.5, 10dp gap, callout
        // 14/20 medium ink, 16dp icon in icon ink, mono 12/16 hint trailing; disabled ink.
        // Separators are the menu's `-mx-1.5 my-1.5` hairline.
        Column(modifier = modifier.fillMaxWidth()) {
            if (dividerBefore) {
                SlockRaftSeparator(verticalSpacing = 6.dp)
            }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(SlockRaftMetrics.MenuRowHeight)
                        .clickable(enabled = enabled) { onClick() }
                        .padding(horizontal = SlockRaftMetrics.MenuItemPaddingHorizontal),
                horizontalArrangement = Arrangement.spacedBy(SlockRaftMetrics.MenuItemGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                leadingIconName?.let {
                    SlockLucideIcon(
                        it,
                        modifier = Modifier.size(16.dp),
                        color = slockArgbHex(if (enabled) SlockColor.TextMuted else SlockColor.ForegroundDisabled)
                    )
                }
                Text(
                    text = label,
                    style = SlockType.Body.withSlockFontWeight(FontWeight.Medium),
                    color = if (enabled) SlockColor.TextBlack else SlockColor.ForegroundDisabled,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                trailing?.let {
                    Text(
                        text = it,
                        style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                        color = SlockColor.ForegroundHint,
                        maxLines = 1
                    )
                }
            }
        }
        return
    }
    Column(modifier = modifier.fillMaxWidth()) {
        if (dividerBefore) {
            SlockSubtleDivider()
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .background(SlockColor.SurfaceWhite)
                    .clickable(enabled = enabled) { onClick() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingIconName?.let {
                SlockLucideIcon(it, modifier = Modifier.size(14.dp), color = if (enabled) "#FF000000" else SlockColor.TextMutedGrayArgb)
            }
            Text(
                text = label,
                style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 18.sp).withSlockFontWeight(FontWeight.Medium),
                color = if (enabled) SlockColor.TextBlack else SlockColor.TextBlack.copy(alpha = 0.30f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            trailing?.let {
                Text(
                    text = it,
                    style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 14.sp),
                    color = SlockColor.TextBlack.copy(alpha = 0.40f),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
internal fun SlockSelectTrigger(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    expanded: Boolean = false,
    // react's select trigger box is 40px tall (components.ui.select.states renders
    // 120px @3x); 42dp drew every default trigger 2dp too tall — task #370.
    height: Dp = 40.dp,
    onClick: () -> Unit = {}
) {
    if (slockRaftActive()) {
        SlockRaftSelectTrigger(
            label = label,
            placeholder = label.isBlank(),
            modifier = modifier,
            enabled = enabled,
            expanded = expanded,
            height = slockRaftFieldHeight(height),
            onClick = onClick
        )
        return
    }
    val shadowOffset = SlockShadow.BrutalSmall
    val contentColor = if (enabled) SlockColor.TextBlack else SlockColor.TextBlack.copy(alpha = 0.50f)
    val backgroundColor = if (enabled) SlockColor.SurfaceWhite else Color(0xFFF3F4F6)
    Box(modifier = modifier.fillMaxWidth().height(height + shadowOffset)) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .offset(x = shadowOffset, y = shadowOffset)
                    .background(SlockColor.ShadowBlack)
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .background(backgroundColor)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .clickable(enabled = enabled) { onClick() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label.ifBlank { "Select..." },
                style = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = contentColor),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            SlockLucideIcon(
                SlockLucideIconName.ChevronDown,
                modifier = Modifier.size(14.dp),
                color = if (enabled) "#FF000000" else "#80000000",
                rotationDegrees = if (expanded) 180f else 0f
            )
        }
    }
}

/**
 * raft-ui 0.6.0 `SelectTrigger` (outline button chrome on the field axis): 44dp, control
 * radius, 1.5dp `--line-strong` edge on the card layer, 14/12dp side padding, subhead
 * 15/20 semibold value (placeholder medium hint ink), 16dp hint chevron. Disabled is the
 * opaque `--fill-muted` / `--line-hairline` pair. No shadow.
 */
@Composable
internal fun SlockRaftSelectTrigger(
    label: String,
    placeholder: Boolean,
    modifier: Modifier,
    enabled: Boolean,
    expanded: Boolean,
    height: Dp,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(SlockRadius.Control)
    val ink =
        when {
            !enabled -> SlockColor.ForegroundDisabled
            placeholder -> SlockColor.ForegroundHint
            else -> SlockColor.TextBlack
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .background(if (enabled) SlockColor.Card else SlockColor.FillMuted, shape)
                .border(SlockStroke.Control, if (enabled) SlockColor.LineField else SlockColor.BorderHairline, shape)
                .clickable(enabled = enabled) { onClick() }
                .padding(start = 14.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label.ifBlank { "Select..." },
            style =
                SlockType.Body
                    .copy(fontSize = 15.sp, lineHeight = 20.sp, color = ink)
                    .withSlockFontWeight(if (placeholder) FontWeight.Medium else FontWeight.SemiBold),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        SlockLucideIcon(
            SlockLucideIconName.ChevronDown,
            modifier = Modifier.size(16.dp),
            color = slockArgbHex(if (enabled) SlockColor.ForegroundHint else SlockColor.ForegroundDisabled),
            strokeWidth = 2f,
            rotationDegrees = if (expanded) 180f else 0f
        )
    }
}

@Composable
internal fun SlockRaftUiControlledSelectionPopover(
    expanded: Boolean,
    anchor: SlockPopoverAnchor?,
    title: String,
    options: List<SlockRaftUiSelectionOption>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    width: Dp = 220.dp,
    rowHeight: Dp = 36.dp,
    emptyHeight: Dp = 44.dp,
    shadowOffset: Dp = SlockShadow.Brutal,
    horizontalOffset: Dp = 0.dp,
    verticalOffset: Dp = 8.dp,
    verticalOffsetAbove: Dp = verticalOffset,
    horizontalAutoFlip: Boolean = true,
    verticalPlacement: SlockFloatingVerticalPlacement = SlockFloatingVerticalPlacement.Auto,
    showHeader: Boolean = true,
    showClear: Boolean = false,
    search: String? = null,
    searchPlaceholder: String = "Search...",
    emptyLabel: String? = null,
    onClear: () -> Unit = {}
) {
    if (!expanded || anchor == null) return
    val density = LocalDensity.current
    val raft = slockRaftActive()
    val headerHeight = if (showHeader) (if (raft) SlockRaftSelectionHeaderHeight else 35.dp) else 0.dp
    val searchHeight = if (search != null) (if (raft) SlockRaftSelectionSearchHeight else 47.dp) else 0.dp
    val optionHeight =
        when {
            // raft: p-1.5 list, no row dividers, 36dp rows (empty row = callout + py-2).
            raft && options.isEmpty() -> 36.dp + SlockRaftMetrics.PopupPadding * 2
            raft -> (SlockRaftMetrics.MenuRowHeight.value * options.size).dp + SlockRaftMetrics.PopupPadding * 2
            options.isEmpty() -> emptyHeight
            else -> ((rowHeight.value * options.size) + (SlockStroke.Thin.value * (options.size - 1).coerceAtLeast(0))).dp
        }
    // raft overlays float on a shadow that takes no layout space.
    val reservedShadow = if (raft) 0.dp else shadowOffset
    val contentSize =
        with(density) {
            Size(
                width = (width + reservedShadow).toPx(),
                height = (headerHeight + searchHeight + optionHeight + reservedShadow).toPx()
            )
        }
    SlockModalAnchoredPopover(
        anchor = anchor,
        contentSize = contentSize,
        onDismiss = onDismiss,
        modifier = modifier,
        viewportGap = with(density) { SlockFloatingDefaultViewportGap.dp.toPx() },
        horizontalOffset = with(density) { horizontalOffset.toPx() },
        verticalOffset = with(density) { verticalOffset.toPx() },
        verticalOffsetAbove = with(density) { verticalOffsetAbove.toPx() },
        horizontalAutoFlip = horizontalAutoFlip,
        verticalPlacement = verticalPlacement
    ) {
        SlockRaftUiSelectionPopover(
            title = title,
            options = options,
            selectedId = selectedId,
            modifier = Modifier.width(width).slockFloatingContentClickTarget(),
            showHeader = showHeader,
            showClear = showClear,
            search = search,
            searchPlaceholder = searchPlaceholder,
            emptyLabel = emptyLabel,
            onClear = onClear,
            onSelect = { option ->
                onSelect(option.id)
                onDismiss()
            }
        )
    }
}

@Composable
internal fun SlockRaftUiControlledSelect(
    expanded: Boolean,
    anchor: SlockPopoverAnchor?,
    options: List<SlockRaftUiSelectionOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Options",
    selectedLabel: String = "",
    placeholder: String = "Select...",
    enabled: Boolean = true,
    triggerHeight: Dp = 40.dp,
    menuWidth: Dp = 220.dp,
    rowHeight: Dp = 36.dp,
    search: String? = null,
    searchPlaceholder: String = "Search...",
    emptyLabel: String? = null,
    showClear: Boolean = false,
    onClear: () -> Unit = {},
    onTriggerClick: () -> Unit = {}
) {
    val shadowOffset = if (enabled) SlockShadow.BrutalSmall else 0.dp
    val selectedOption = options.firstOrNull { it.id == selectedId }
    val displayLabel = selectedLabel.ifBlank { selectedOption?.label.orEmpty() }.ifBlank { placeholder }
    val resolvedTriggerHeight = slockRaftFieldHeight(triggerHeight)
    if (slockRaftActive()) {
        SlockRaftSelectTrigger(
            label = displayLabel,
            placeholder = selectedLabel.isBlank() && selectedOption == null,
            modifier = modifier,
            enabled = enabled,
            expanded = expanded,
            height = resolvedTriggerHeight,
            onClick = onTriggerClick
        )
    } else {
        SlockSelectTrigger(
            label = displayLabel,
            modifier = modifier,
            enabled = enabled,
            expanded = expanded,
            height = triggerHeight,
            onClick = onTriggerClick
        )
    }
    SlockRaftUiControlledSelectionPopover(
        expanded = expanded && enabled,
        anchor = anchor,
        title = title,
        options = options,
        selectedId = selectedId,
        onSelect = onSelect,
        onDismiss = onDismiss,
        width = menuWidth,
        rowHeight = rowHeight,
        verticalOffset = resolvedTriggerHeight + shadowOffset + 3.dp,
        verticalOffsetAbove = 3.dp,
        showClear = showClear,
        search = search,
        searchPlaceholder = searchPlaceholder,
        emptyLabel = emptyLabel,
        onClear = onClear
    )
}

@Composable
internal fun SlockRaftUiSelectField(
    options: List<SlockRaftUiSelectionOption>,
    selectedId: String,
    onSelect: (SlockRaftUiSelectionOption) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Options",
    placeholder: String = "Select...",
    selectedLabel: String = "",
    enabled: Boolean = true,
    triggerHeight: Dp = 40.dp,
    rowHeight: Dp = 36.dp,
    search: String? = null,
    searchPlaceholder: String = "Search...",
    emptyLabel: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var triggerAnchorValue by remember { mutableStateOf<SlockPopoverAnchor?>(null) }
    var triggerRootWidthPx by remember { mutableStateOf(0f) }
    val triggerAnchor = remember { SlockLiveRootAnchorRef(identity = "raft_ui_select") }
    val density = LocalDensity.current
    val menuWidth = slockSelectMenuWidth(triggerRootWidthPx, density.density)
    SlockRaftUiControlledSelect(
        expanded = expanded,
        anchor = triggerAnchorValue,
        options = options,
        selectedId = selectedId,
        selectedLabel = selectedLabel,
        placeholder = placeholder,
        enabled = enabled,
        title = title,
        triggerHeight = triggerHeight,
        menuWidth = menuWidth,
        rowHeight = rowHeight,
        search = search,
        searchPlaceholder = searchPlaceholder,
        emptyLabel = emptyLabel,
        modifier =
            modifier
                .fillMaxWidth()
                .slockCaptureLiveRootAnchor(triggerAnchor) { size ->
                    triggerRootWidthPx = size.width.toFloat()
                },
        onTriggerClick = {
            if (expanded) {
                expanded = false
            } else {
                triggerAnchor.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero))?.let { anchor ->
                    triggerAnchorValue = anchor
                    expanded = true
                }
            }
        },
        onDismiss = { expanded = false },
        onSelect = { id ->
            options.firstOrNull { it.id == id }?.let(onSelect)
        }
    )
}

@Composable
internal fun SlockRaftUiControlledMenu(
    expanded: Boolean,
    anchor: SlockPopoverAnchor,
    items: List<SlockRaftUiMenuItem>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 200.dp,
    rowHeight: Dp = 40.dp,
    shadowOffset: Dp = SlockShadow.Brutal,
    horizontalOffset: Dp = 0.dp,
    verticalOffset: Dp = 16.dp,
    horizontalAutoFlip: Boolean = true,
    verticalPlacement: SlockFloatingVerticalPlacement = SlockFloatingVerticalPlacement.Auto,
    showDividers: Boolean = true
) {
    if (!expanded) return
    val density = LocalDensity.current
    val effectiveRowCount = items.size.coerceAtLeast(1)
    val raft = slockRaftActive()
    // raft overlays float on a shadow that takes no layout space; the list sits in a p-1.5 popup.
    val reservedShadow = if (raft) 0.dp else shadowOffset
    val chromeHeight = if (raft) SlockRaftMetrics.PopupPadding * 2 else 0.dp
    // raft: the surface turns the legacy 40dp row into `min-h-9` and widens to `min-w-60`.
    val measuredRowHeight = if (raft && rowHeight == 40.dp) SlockRaftMetrics.MenuRowHeight else rowHeight
    val measuredWidth = if (raft) slockRaftMenuWidth(width) else width
    val contentSize =
        with(density) {
            Size(
                width = (measuredWidth + reservedShadow).toPx(),
                height =
                    measuredRowHeight.toPx() * effectiveRowCount +
                        (chromeHeight + reservedShadow).toPx()
            )
        }
    SlockModalAnchoredPopover(
        anchor = anchor,
        contentSize = contentSize,
        onDismiss = onDismiss,
        modifier = modifier,
        viewportGap = with(density) { SlockFloatingDefaultViewportGap.dp.toPx() },
        horizontalOffset = with(density) { horizontalOffset.toPx() },
        verticalOffset = with(density) { verticalOffset.toPx() },
        horizontalAutoFlip = horizontalAutoFlip,
        verticalPlacement = verticalPlacement
    ) {
        SlockRaftUiMenuSurface(
            items = items,
            onSelect = onSelect,
            onDismiss = onDismiss,
            modifier = Modifier,
            width = width,
            rowHeight = rowHeight,
            shadowOffset = shadowOffset,
            showDividers = showDividers
        )
    }
}

@Composable
private fun SlockSelectionPopoverOptionRow(
    option: SlockSelectionPopoverOption,
    onSelect: (SlockSelectionPopoverOption) -> Unit
) {
    val rowEnabled = option.enabled
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(SlockColor.SurfaceWhite)
                .clickable(enabled = rowEnabled) { onSelect(option) }
                .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (option.reserveLeadingSlot || option.leadingIconName != null) {
            Box(modifier = Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                option.leadingIconName?.let {
                    SlockLucideIcon(it, modifier = Modifier.size(14.dp), color = if (rowEnabled) "#FF000000" else SlockColor.TextMutedGrayArgb)
                }
            }
        }
        Text(
            text = option.label,
            style =
                SlockType.Caption
                    .copy(fontSize = 12.sp, lineHeight = 18.sp)
                    .withSlockFontWeight(FontWeight.Bold),
            color =
                when {
                    !rowEnabled -> SlockColor.TextBlack.copy(alpha = 0.30f)
                    option.italic -> SlockColor.TextBlack.copy(alpha = 0.70f)
                    else -> SlockColor.TextBlack
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (option.checked) {
            SlockLucideIcon(SlockLucideIconName.Check, modifier = Modifier.size(12.dp), color = "#FF000000")
        }
    }
}

@Composable
private fun SlockThinDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Thin).background(SlockColor.BorderBlack))
}

@Composable
private fun SlockSubtleDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Thin).background(SlockColor.BorderBlack.copy(alpha = 0.10f)))
}
