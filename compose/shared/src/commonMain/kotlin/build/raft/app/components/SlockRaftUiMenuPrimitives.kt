package build.raft.app.components

import build.raft.app.slockDisplayUppercase
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
import build.raft.app.SlockShadow
import build.raft.app.SlockStroke
import build.raft.app.SlockType
import build.raft.app.TextField
import build.raft.app.slockCaptureLiveRootAnchor
import build.raft.app.slockSquareCorners
import build.raft.app.slockFloatingContentClickTarget
import build.raft.app.withSlockFontWeight
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
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
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
    val headerHeight = if (showHeader) 35.dp else 0.dp
    val searchHeight = if (search != null) 47.dp else 0.dp
    val optionHeight =
        if (options.isEmpty()) {
            emptyHeight
        } else {
            ((rowHeight.value * options.size) + (SlockStroke.Thin.value * (options.size - 1).coerceAtLeast(0))).dp
        }
    val contentSize =
        with(density) {
            Size(
                width = (width + shadowOffset).toPx(),
                height = (headerHeight + searchHeight + optionHeight + shadowOffset).toPx()
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
    SlockSelectTrigger(
        label = displayLabel,
        modifier = modifier,
        enabled = enabled,
        expanded = expanded,
        height = triggerHeight,
        onClick = onTriggerClick
    )
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
        verticalOffset = triggerHeight + shadowOffset + 3.dp,
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
    val contentSize =
        with(density) {
            Size(
                width = (width + shadowOffset).toPx(),
                height = rowHeight.toPx() * effectiveRowCount + shadowOffset.toPx()
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
