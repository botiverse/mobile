package build.raft.app.components

import build.raft.app.raftMenuWidth
import build.raft.app.RaftColor
import build.raft.app.RaftFloatingDefaultViewportGap
import build.raft.app.RaftMonoFamily
import build.raft.app.RaftFloatingVerticalPlacement
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftLiveRootAnchorRef
import build.raft.app.RaftPopoverAnchor
import build.raft.app.RaftPopoverLocalPoint
import build.raft.app.RaftModalAnchoredPopover
import build.raft.app.RaftAppRadius
import build.raft.app.RaftShadow
import build.raft.app.RaftAppStroke
import build.raft.app.RaftType
import build.raft.app.TextField
import build.raft.app.raftCaptureLiveRootAnchor
import build.raft.app.raftFloatingContentClickTarget
import build.raft.app.withRaftFontWeight
import build.raft.app.raftArgbHex
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.tencent.tmm.kmmresource.compose.stringResource
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class RaftSelectionPopoverOption(
    val id: String,
    val label: String,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val italic: Boolean = false,
    val reserveLeadingSlot: Boolean = false,
    val leadingIconName: RaftLucideIconName? = null
)

internal data class RaftUiSelectionOption(
    val id: String,
    val label: String,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val italic: Boolean = false,
    val reserveLeadingSlot: Boolean = true,
    val leadingIconName: RaftLucideIconName? = null
)

@Composable
internal fun RaftSelectionPopover(
    title: String,
    options: List<RaftSelectionPopoverOption>,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    showClear: Boolean = false,
    search: String? = null,
    searchPlaceholder: String = "Search...",
    emptyLabel: String? = null,
    onClear: () -> Unit = {},
    onSelect: (RaftSelectionPopoverOption) -> Unit = {}
) {
    RaftLookSelectionPopover(
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
}

// raft selection-popover geometry (web SelectionPopover): header px-4 pt-2.5 pb-1 with a
// 12/16 caption, a 40dp search row over a hairline, and a p-1.5 list of 36dp rows.
private val RaftSelectionHeaderHeight = 30.dp
private val RaftSelectionSearchHeight = 41.dp

/**
 * raft-ui 0.6.0 selection popover (web `SelectionPopover`): overlay surface (container
 * radius, popover layer, seam ring, float shadow); sentence-case caption header in hint
 * ink with an underlined Clear; optional search row; 36dp control-radius option rows in
 * callout type, the checked row bold with a trailing 16dp check. No row dividers.
 */
@Composable
private fun RaftLookSelectionPopover(
    title: String,
    options: List<RaftSelectionPopoverOption>,
    modifier: Modifier,
    showHeader: Boolean,
    showClear: Boolean,
    search: String?,
    searchPlaceholder: String,
    emptyLabel: String?,
    onClear: () -> Unit,
    onSelect: (RaftSelectionPopoverOption) -> Unit
) {
    Column(modifier = modifier.fillMaxWidth().raftLookOverlaySurface()) {
        if (showHeader) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(RaftSelectionHeaderHeight)
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
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
                if (showClear) {
                    Box(modifier = Modifier.clickable { onClear() }) {
                        Text(
                            text = stringResource(MR.strings.common_clear),
                            style =
                                RaftType.Caption
                                    .copy(fontSize = 12.sp, lineHeight = 16.sp, textDecoration = TextDecoration.Underline)
                                    .withRaftFontWeight(FontWeight.SemiBold),
                            color = RaftColor.TextBlack,
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
                RaftLucideIcon(
                    RaftLucideIconName.Search,
                    modifier = Modifier.size(16.dp),
                    color = raftArgbHex(RaftColor.ForegroundHint)
                )
                TextField(
                    value = search,
                    onValueChange = {},
                    placeholder = searchPlaceholder,
                    placeholderColor = RaftColor.ForegroundHint,
                    autoFocus = false,
                    maxLines = 1,
                    textStyle = RaftType.Input.copy(fontSize = 14.sp, lineHeight = 20.sp, color = RaftColor.TextBlack),
                    modifier = Modifier.weight(1f)
                )
            }
            RaftLookSeparator()
        }
        Column(modifier = Modifier.fillMaxWidth().padding(RaftMetrics.PopupPadding)) {
            if (options.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(
                        text = emptyLabel ?: stringResource(MR.strings.common_no_matches),
                        style = RaftType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp),
                        color = RaftColor.ForegroundHint
                    )
                }
            } else {
                options.forEach { option ->
                    RaftSelectionOptionRow(option = option, onSelect = onSelect)
                }
            }
        }
    }
}

@Composable
private fun RaftSelectionOptionRow(
    option: RaftSelectionPopoverOption,
    onSelect: (RaftSelectionPopoverOption) -> Unit
) {
    val rowEnabled = option.enabled
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(RaftMetrics.MenuRowHeight)
                .clickable(enabled = rowEnabled) { onSelect(option) }
                .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (option.reserveLeadingSlot || option.leadingIconName != null) {
            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                option.leadingIconName?.let {
                    RaftLucideIcon(
                        it,
                        modifier = Modifier.size(16.dp),
                        color = raftArgbHex(if (rowEnabled) RaftColor.TextMuted else RaftColor.ForegroundDisabled)
                    )
                }
            }
        }
        Text(
            text = option.label,
            style =
                RaftType.Body
                    .copy(fontSize = 14.sp, lineHeight = 20.sp)
                    .withRaftFontWeight(if (option.checked && rowEnabled) FontWeight.Bold else FontWeight.Medium),
            color =
                when {
                    !rowEnabled -> RaftColor.ForegroundDisabled
                    option.italic -> RaftColor.TextMuted
                    else -> RaftColor.TextBlack
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (option.checked) {
            RaftLucideIcon(RaftLucideIconName.Check, modifier = Modifier.size(16.dp), color = RaftColor.TextBlackArgb)
        }
    }
}

/** raft-ui `Separator` (1dp `--line-hairline`). [inset] mirrors a menu's `-mx-1.5 my-1.5`. */
@Composable
internal fun RaftLookSeparator(modifier: Modifier = Modifier, verticalSpacing: Dp = 0.dp) {
    Box(
        modifier =
            modifier
                .padding(vertical = verticalSpacing)
                .fillMaxWidth()
                .height(RaftAppStroke.Thin)
                .background(RaftColor.BorderHairline)
    )
}

@Composable
internal fun RaftUiSelectionPopover(
    title: String,
    options: List<RaftUiSelectionOption>,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    showHeader: Boolean = true,
    showClear: Boolean = false,
    search: String? = null,
    searchPlaceholder: String = "Search...",
    emptyLabel: String? = null,
    onClear: () -> Unit = {},
    onSelect: (RaftUiSelectionOption) -> Unit = {}
) {
    RaftSelectionPopover(
        title = title,
        options =
            options.map { option ->
                RaftSelectionPopoverOption(
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
internal fun RaftMenuItemRow(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIconName: RaftLucideIconName? = null,
    trailing: String? = null,
    dividerBefore: Boolean = false,
    onClick: () -> Unit = {}
) {
    // raft-ui MenuItem: min 36dp, control radius, px-2.5 py-1.5, 10dp gap, callout
    // 14/20 medium ink, 16dp icon in icon ink, mono 12/16 hint trailing; disabled ink.
    // Separators are the menu's `-mx-1.5 my-1.5` hairline.
    Column(modifier = modifier.fillMaxWidth()) {
        if (dividerBefore) {
            RaftLookSeparator(verticalSpacing = 6.dp)
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(RaftMetrics.MenuRowHeight)
                    .clickable(enabled = enabled) { onClick() }
                    .padding(horizontal = RaftMetrics.MenuItemPaddingHorizontal),
            horizontalArrangement = Arrangement.spacedBy(RaftMetrics.MenuItemGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingIconName?.let {
                RaftLucideIcon(
                    it,
                    modifier = Modifier.size(16.dp),
                    color = raftArgbHex(if (enabled) RaftColor.TextMuted else RaftColor.ForegroundDisabled)
                )
            }
            Text(
                text = label,
                style = RaftType.Button,
                color = if (enabled) RaftColor.TextBlack else RaftColor.ForegroundDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            trailing?.let {
                Text(
                    text = it,
                    style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                    color = RaftColor.ForegroundHint,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
internal fun RaftAppSelectTrigger(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    expanded: Boolean = false,
    // react's select trigger box is 40px tall (components.ui.select.states renders
    // 120px @3x); 42dp drew every default trigger 2dp too tall — task #370.
    height: Dp = 40.dp,
    onClick: () -> Unit = {}
) {
    RaftLookSelectTrigger(
        label = label,
        placeholder = label.isBlank(),
        modifier = modifier,
        enabled = enabled,
        expanded = expanded,
        height = raftFieldHeight(height),
        onClick = onClick
    )
}

/**
 * raft-ui 0.6.0 `SelectTrigger` (outline button chrome on the field axis): 44dp, control
 * radius, 1.5dp `--line-strong` edge on the card layer, 14/12dp side padding, subhead
 * 15/20 semibold value (placeholder medium hint ink), 16dp hint chevron. Disabled is the
 * opaque `--fill-muted` / `--line-hairline` pair. No shadow.
 */
@Composable
internal fun RaftLookSelectTrigger(
    label: String,
    placeholder: Boolean,
    modifier: Modifier,
    enabled: Boolean,
    expanded: Boolean,
    height: Dp,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(RaftAppRadius.Control)
    val ink =
        when {
            !enabled -> RaftColor.ForegroundDisabled
            placeholder -> RaftColor.ForegroundHint
            else -> RaftColor.TextBlack
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .background(if (enabled) RaftColor.Card else RaftColor.FillMuted, shape)
                .border(RaftAppStroke.Control, if (enabled) RaftColor.LineField else RaftColor.BorderHairline, shape)
                .clickable(enabled = enabled) { onClick() }
                .padding(start = 14.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label.ifBlank { "Select..." },
            style =
                RaftType.Body
                    .copy(fontSize = 15.sp, lineHeight = 20.sp, color = ink)
                    .withRaftFontWeight(if (placeholder) FontWeight.Medium else FontWeight.SemiBold),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        RaftLucideIcon(
            RaftLucideIconName.ChevronDown,
            modifier = Modifier.size(16.dp),
            color = raftArgbHex(if (enabled) RaftColor.ForegroundHint else RaftColor.ForegroundDisabled),
            strokeWidth = 2f,
            rotationDegrees = if (expanded) 180f else 0f
        )
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun RaftUiControlledSelectionPopover(
    expanded: Boolean,
    anchor: RaftPopoverAnchor?,
    title: String,
    options: List<RaftUiSelectionOption>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    width: Dp = 220.dp,
    rowHeight: Dp = 36.dp,
    emptyHeight: Dp = 44.dp,
    shadowOffset: Dp = RaftShadow.Brutal,
    horizontalOffset: Dp = 0.dp,
    verticalOffset: Dp = 8.dp,
    verticalOffsetAbove: Dp = verticalOffset,
    horizontalAutoFlip: Boolean = true,
    verticalPlacement: RaftFloatingVerticalPlacement = RaftFloatingVerticalPlacement.Auto,
    showHeader: Boolean = true,
    showClear: Boolean = false,
    search: String? = null,
    searchPlaceholder: String = "Search...",
    emptyLabel: String? = null,
    onClear: () -> Unit = {}
) {
    if (!expanded || anchor == null) return
    val density = LocalDensity.current
    val headerHeight = if (showHeader) RaftSelectionHeaderHeight else 0.dp
    val searchHeight = if (search != null) RaftSelectionSearchHeight else 0.dp
    val optionHeight =
        if (options.isEmpty()) {
            // raft: p-1.5 list, no row dividers, 36dp rows (empty row = callout + py-2).
            36.dp + RaftMetrics.PopupPadding * 2
        } else {
            (RaftMetrics.MenuRowHeight.value * options.size).dp + RaftMetrics.PopupPadding * 2
        }
    // raft overlays float on a shadow that takes no layout space.
    val contentSize =
        with(density) {
            Size(
                width = width.toPx(),
                height = (headerHeight + searchHeight + optionHeight).toPx()
            )
        }
    RaftModalAnchoredPopover(
        anchor = anchor,
        contentSize = contentSize,
        onDismiss = onDismiss,
        modifier = modifier,
        viewportGap = with(density) { RaftFloatingDefaultViewportGap.dp.toPx() },
        horizontalOffset = with(density) { horizontalOffset.toPx() },
        verticalOffset = with(density) { verticalOffset.toPx() },
        verticalOffsetAbove = with(density) { verticalOffsetAbove.toPx() },
        horizontalAutoFlip = horizontalAutoFlip,
        verticalPlacement = verticalPlacement
    ) {
        RaftUiSelectionPopover(
            title = title,
            options = options,
            selectedId = selectedId,
            modifier = Modifier.width(width).raftFloatingContentClickTarget(),
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
internal fun RaftUiControlledSelect(
    expanded: Boolean,
    anchor: RaftPopoverAnchor?,
    options: List<RaftUiSelectionOption>,
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
    val shadowOffset = if (enabled) RaftShadow.BrutalSmall else 0.dp
    val selectedOption = options.firstOrNull { it.id == selectedId }
    val displayLabel = selectedLabel.ifBlank { selectedOption?.label.orEmpty() }.ifBlank { placeholder }
    val resolvedTriggerHeight = raftFieldHeight(triggerHeight)
    RaftLookSelectTrigger(
        label = displayLabel,
        placeholder = selectedLabel.isBlank() && selectedOption == null,
        modifier = modifier,
        enabled = enabled,
        expanded = expanded,
        height = resolvedTriggerHeight,
        onClick = onTriggerClick
    )
    RaftUiControlledSelectionPopover(
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
internal fun RaftUiSelectField(
    options: List<RaftUiSelectionOption>,
    selectedId: String,
    onSelect: (RaftUiSelectionOption) -> Unit,
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
    var triggerAnchorValue by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var triggerRootWidthPx by remember { mutableStateOf(0f) }
    val triggerAnchor = remember { RaftLiveRootAnchorRef(identity = "raft_ui_select") }
    val density = LocalDensity.current
    val menuWidth = raftSelectMenuWidth(triggerRootWidthPx, density.density)
    RaftUiControlledSelect(
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
                .raftCaptureLiveRootAnchor(triggerAnchor) { size ->
                    triggerRootWidthPx = size.width.toFloat()
                },
        onTriggerClick = {
            if (expanded) {
                expanded = false
            } else {
                triggerAnchor.resolve(RaftPopoverLocalPoint.Exact(Offset.Zero))?.let { anchor ->
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
internal fun RaftUiControlledMenu(
    expanded: Boolean,
    anchor: RaftPopoverAnchor,
    items: List<RaftUiMenuItem>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 200.dp,
    rowHeight: Dp = 40.dp,
    shadowOffset: Dp = RaftShadow.Brutal,
    horizontalOffset: Dp = 0.dp,
    verticalOffset: Dp = 16.dp,
    horizontalAutoFlip: Boolean = true,
    verticalPlacement: RaftFloatingVerticalPlacement = RaftFloatingVerticalPlacement.Auto,
    showDividers: Boolean = true
) {
    if (!expanded) return
    val density = LocalDensity.current
    val effectiveRowCount = items.size.coerceAtLeast(1)
    // raft overlays float on a shadow that takes no layout space; the list sits in a p-1.5 popup.
    val chromeHeight = RaftMetrics.PopupPadding * 2
    // raft: the surface turns the legacy 40dp row into `min-h-9` and widens to `min-w-60`.
    val measuredRowHeight = if (rowHeight == 40.dp) RaftMetrics.MenuRowHeight else rowHeight
    val measuredWidth = raftMenuWidth(width)
    val contentSize =
        with(density) {
            Size(
                width = measuredWidth.toPx(),
                height =
                    measuredRowHeight.toPx() * effectiveRowCount +
                        chromeHeight.toPx()
            )
        }
    RaftModalAnchoredPopover(
        anchor = anchor,
        contentSize = contentSize,
        onDismiss = onDismiss,
        modifier = modifier,
        viewportGap = with(density) { RaftFloatingDefaultViewportGap.dp.toPx() },
        horizontalOffset = with(density) { horizontalOffset.toPx() },
        verticalOffset = with(density) { verticalOffset.toPx() },
        horizontalAutoFlip = horizontalAutoFlip,
        verticalPlacement = verticalPlacement
    ) {
        RaftUiMenuSurface(
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
