package build.raft.app.components

import build.raft.app.SlockColor
import build.raft.app.SlockFloatingDefaultViewportGap
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
import build.raft.app.slockCaptureLiveRootAnchor
import build.raft.app.withSlockFontWeight
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import build.raft.app.compat.ui.NestedScrollMode
import build.raft.app.compat.ui.nestedScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class SlockBrutalSelectOption(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    /** A group heading inside the menu: never selectable, drawn muted in regular weight. */
    val header: Boolean = false
)

internal fun slockSelectMenuWidth(
    triggerWidthPx: Float,
    density: Float,
    fallbackWidth: Dp = 220.dp
): Dp =
    if (triggerWidthPx > 0f && density > 0f) {
        (triggerWidthPx / density).dp
    } else {
        fallbackWidth
    }

/**
 * raft-ui select popup height: the rows (at least one, so an empty list still shows its
 * row) plus the popup's `p-1.5` inset on both edges, capped at [maxMenuHeight]. The seam
 * ring is drawn over the content and adds no height.
 */
internal fun slockBrutalSelectListHeight(
    optionCount: Int,
    rowHeight: Dp,
    maxMenuHeight: Dp
): Dp =
    ((optionCount.coerceAtLeast(1) * rowHeight.value).dp + SlockRaftMetrics.PopupPadding * 2)
        .coerceAtMost(maxMenuHeight)

/** raft-ui `SelectGroupLabel`: `px-2.5 pt-2 pb-1` around a 12/16 caption = 28dp. */
internal val SlockRaftSelectGroupLabelHeight = 28.dp

/** raft-ui select popup minimum width (`min-w-[max(var(--anchor-width),220px)]`). */
internal val SlockRaftSelectMinWidth = 220.dp

/**
 * raft select popup height: 36dp items and 28dp group labels plus the `p-1.5` inset on
 * both edges, capped at [maxMenuHeight].
 */
internal fun slockRaftSelectListHeight(
    options: List<SlockBrutalSelectOption>,
    maxMenuHeight: Dp
): Dp {
    val rows =
        if (options.isEmpty()) {
            SlockRaftMetrics.MenuRowHeight.value
        } else {
            options.sumOf { option ->
                (if (option.header) SlockRaftSelectGroupLabelHeight else SlockRaftMetrics.MenuRowHeight).value.toDouble()
            }.toFloat()
        }
    return (rows.dp + SlockRaftMetrics.PopupPadding * 2).coerceAtMost(maxMenuHeight)
}

@Composable
internal fun SlockBrutalSelectField(
    options: List<SlockBrutalSelectOption>,
    selectedId: String,
    onSelect: (SlockBrutalSelectOption) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Select...",
    selectedLabel: String = "",
    enabled: Boolean = true,
    rowHeight: Dp = 36.dp,
    triggerHeight: Dp = 40.dp,
    maxMenuHeight: Dp = 220.dp
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.firstOrNull { it.id == selectedId }
    val displayLabel = selectedLabel.ifBlank { selectedOption?.label.orEmpty() }.ifBlank { placeholder }
    val shadowOffset = if (enabled) SlockShadow.BrutalSmall else 0.dp
    var triggerAnchorValue by remember { mutableStateOf<SlockPopoverAnchor?>(null) }
    var triggerRootWidthPx by remember { mutableStateOf(0f) }
    val triggerAnchor = remember { SlockLiveRootAnchorRef(identity = "brutal_select") }
    val density = LocalDensity.current
    val menuWidth = slockSelectMenuWidth(triggerRootWidthPx, density.density)
    val menuShadowOffset = SlockShadow.Brutal
    val listHeight = slockBrutalSelectListHeight(options.size, rowHeight, maxMenuHeight)
    if (slockRaftActive()) {
        SlockRaftSelectField(
            options = options,
            selectedId = selectedId,
            onSelect = onSelect,
            modifier = modifier,
            displayLabel = displayLabel,
            placeholder = selectedLabel.isBlank() && selectedOption == null,
            enabled = enabled,
            triggerHeight = slockRaftFieldHeight(triggerHeight),
            maxMenuHeight = maxMenuHeight
        )
        return
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(triggerHeight + shadowOffset)
                    .slockCaptureLiveRootAnchor(triggerAnchor) { size ->
                        triggerRootWidthPx = size.width.toFloat()
                    }
        ) {
            if (enabled) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(triggerHeight)
                            .offset(x = shadowOffset, y = shadowOffset)
                            .background(SlockColor.ShadowBlack)
                )
            }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(triggerHeight)
                        .border(SlockStroke.Default, SlockColor.BorderBlack)
                        .background(if (enabled) SlockColor.SurfaceWhite else SlockColor.SurfaceCream)
                        .clickable(enabled = enabled) {
                            if (expanded) {
                                expanded = false
                            } else {
                                triggerAnchor.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero))?.let { anchor ->
                                    triggerAnchorValue = anchor
                                    expanded = true
                                }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayLabel,
                    style = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray),
                    color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                SlockLucideIcon(
                    SlockLucideIconName.ChevronDown,
                    modifier = Modifier.size(14.dp),
                    color = if (enabled) "#FF000000" else SlockColor.TextMutedGrayArgb
                )
            }
        }
        if (expanded && enabled && triggerAnchorValue != null) {
            val contentSize =
                with(density) {
                    Size(
                        width = (menuWidth + menuShadowOffset).toPx(),
                        height = (listHeight + menuShadowOffset).toPx()
                    )
                }
            SlockModalAnchoredPopover(
                anchor = triggerAnchorValue!!,
                contentSize = contentSize,
                onDismiss = { expanded = false },
                viewportGap = with(density) { SlockFloatingDefaultViewportGap.dp.toPx() },
                verticalOffset = with(density) { (triggerHeight + shadowOffset).toPx() },
                verticalOffsetAbove = with(density) { shadowOffset.toPx() },
                verticalPlacement = SlockFloatingVerticalPlacement.Auto
            ) {
                SlockBrutalSelectMenu(
                    options = options,
                    selectedId = selectedId,
                    enabled = enabled,
                    rowHeight = rowHeight,
                    listHeight = listHeight,
                    modifier = Modifier.width(menuWidth),
                    onSelect = { option ->
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}

/**
 * raft-ui 0.6.0 Select: [SlockRaftSelectTrigger] plus a popup on the overlay surface
 * (container radius, popover layer, seam ring, float shadow, 6dp inset). Items are 36dp
 * control-radius rows in callout 14/20 medium; the selected item is bold with a trailing
 * 16dp check (no fill), group headers are 12/16 bold hint captions, disabled rows take
 * the disabled ink. The popup opens 4dp below the trigger, at least as wide as it.
 */
@Composable
private fun SlockRaftSelectField(
    options: List<SlockBrutalSelectOption>,
    selectedId: String,
    onSelect: (SlockBrutalSelectOption) -> Unit,
    modifier: Modifier,
    displayLabel: String,
    placeholder: Boolean,
    enabled: Boolean,
    triggerHeight: Dp,
    maxMenuHeight: Dp
) {
    var expanded by remember { mutableStateOf(false) }
    var triggerAnchorValue by remember { mutableStateOf<SlockPopoverAnchor?>(null) }
    var triggerRootWidthPx by remember { mutableStateOf(0f) }
    val triggerAnchor = remember { SlockLiveRootAnchorRef(identity = "raft_select") }
    val density = LocalDensity.current
    val menuWidth = slockSelectMenuWidth(triggerRootWidthPx, density.density).coerceAtLeast(SlockRaftSelectMinWidth)
    val rowHeight = SlockRaftMetrics.MenuRowHeight
    val popupInset = SlockRaftMetrics.PopupPadding
    val listHeight = slockRaftSelectListHeight(options, maxMenuHeight)
    val sideOffset = 4.dp
    Column(modifier = modifier.fillMaxWidth()) {
        SlockRaftSelectTrigger(
            label = displayLabel,
            placeholder = placeholder,
            modifier =
                Modifier.slockCaptureLiveRootAnchor(triggerAnchor) { size ->
                    triggerRootWidthPx = size.width.toFloat()
                },
            enabled = enabled,
            expanded = expanded,
            height = triggerHeight,
            onClick = {
                if (expanded) {
                    expanded = false
                } else {
                    triggerAnchor.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero))?.let { anchor ->
                        triggerAnchorValue = anchor
                        expanded = true
                    }
                }
            }
        )
        if (expanded && enabled && triggerAnchorValue != null) {
            val contentSize =
                with(density) {
                    Size(
                        width = menuWidth.toPx(),
                        height = listHeight.toPx()
                    )
                }
            SlockModalAnchoredPopover(
                anchor = triggerAnchorValue!!,
                contentSize = contentSize,
                onDismiss = { expanded = false },
                viewportGap = with(density) { SlockFloatingDefaultViewportGap.dp.toPx() },
                verticalOffset = with(density) { (triggerHeight + sideOffset).toPx() },
                verticalOffsetAbove = with(density) { sideOffset.toPx() },
                verticalPlacement = SlockFloatingVerticalPlacement.Auto
            ) {
                Box(
                    modifier =
                        Modifier
                            .width(menuWidth)
                            .height(listHeight)
                            // Keeps taps on empty popup space off the dismiss barrier while
                            // letting the rows and the list's scroll win (see the brutal menu).
                            .clickable(onClick = {})
                            .slockRaftOverlaySurface()
                ) {
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(listHeight)
                                .padding(popupInset)
                                .nestedScroll(
                                    scrollUp = NestedScrollMode.SELF_ONLY,
                                    scrollDown = NestedScrollMode.SELF_ONLY
                                )
                    ) {
                        items(options.size, key = { idx -> options[idx].id }) { idx ->
                            val option = options[idx]
                            val selected = selectedId == option.id
                            val rowEnabled = option.enabled && !option.header
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(if (option.header) SlockRaftSelectGroupLabelHeight else rowHeight)
                                        .clickable(enabled = rowEnabled) {
                                            expanded = false
                                            onSelect(option)
                                        }
                                        .padding(
                                            start = SlockRaftMetrics.MenuItemPaddingHorizontal,
                                            end = SlockRaftMetrics.MenuItemPaddingHorizontal,
                                            top = if (option.header) SlockRaftMetrics.MenuLabelPaddingTop else 0.dp,
                                            bottom = if (option.header) SlockRaftMetrics.MenuLabelPaddingBottom else 0.dp
                                        ),
                                horizontalArrangement = Arrangement.spacedBy(SlockRaftMetrics.MenuItemGap),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = option.label,
                                    style =
                                        if (option.header) {
                                            SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp).withSlockFontWeight(FontWeight.Bold)
                                        } else {
                                            SlockType.Body
                                                .withSlockFontWeight(if (selected) FontWeight.Bold else FontWeight.Medium)
                                        },
                                    color =
                                        when {
                                            option.header -> SlockColor.ForegroundHint
                                            rowEnabled -> SlockColor.TextBlack
                                            else -> SlockColor.ForegroundDisabled
                                        },
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

@Composable
private fun SlockBrutalSelectMenu(
    options: List<SlockBrutalSelectOption>,
    selectedId: String,
    enabled: Boolean,
    rowHeight: Dp,
    listHeight: Dp,
    onSelect: (SlockBrutalSelectOption) -> Unit,
    modifier: Modifier = Modifier
) {
    val shadowOffset = SlockShadow.Brutal
    Box(
        modifier =
            modifier
                .height(listHeight + shadowOffset)
                // This panel contains a LazyColumn. nativeDispatchCapture(),
                // which slockFloatingContentClickTarget() installs on
                // Android/OHOS, takes the drag stream before Kuikly's lazy
                // list can scroll. A plain no-op clickable still keeps taps
                // on empty menu space from reaching the dismiss barrier while
                // allowing child rows and the list's scroll handler to win.
                .clickable(onClick = {})
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(listHeight)
                    .offset(x = shadowOffset, y = shadowOffset)
                    .background(SlockColor.ShadowBlack)
        )
        // Keep the viewport chrome outside the scrolling node. Kuikly's
        // LazyColumn repositions its native children while scrolling; when
        // the background and border live on that same node, those children
        // can repaint over the border and make the menu look split into
        // multiple boxes. The outer Box owns the fixed white surface, clip,
        // and continuous border; only its contents scroll.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(listHeight)
                    .background(SlockColor.SurfaceWhite)
                    .clipToBounds()
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
        ) {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(listHeight)
                        // The select lives in a floating overlay while its
                        // trigger often sits inside a form LazyColumn. Keep
                        // the drag entirely in this menu so an in-progress
                        // option scroll cannot also move/focus the underlying
                        // form after native capture was removed.
                        .nestedScroll(
                            scrollUp = NestedScrollMode.SELF_ONLY,
                            scrollDown = NestedScrollMode.SELF_ONLY
                        )
            ) {
                items(options.size, key = { idx -> options[idx].id }) { idx ->
                    val option = options[idx]
                    val selected = selectedId == option.id
                    val rowEnabled = enabled && option.enabled && !option.header
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(rowHeight)
                                .clickable(enabled = rowEnabled) { onSelect(option) }
                                .background(if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.width(14.dp), contentAlignment = Alignment.Center) {
                            if (selected) {
                                SlockLucideIcon(SlockLucideIconName.Check, modifier = Modifier.size(12.dp), color = "#FF000000")
                            }
                        }
                        Text(
                            text = option.label,
                            style = if (option.header) SlockType.Caption else SlockType.Caption.withSlockFontWeight(FontWeight.Bold),
                            color = if (rowEnabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
