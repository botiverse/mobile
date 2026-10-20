package build.raft.app.components

import build.raft.app.RaftColor
import build.raft.app.RaftFloatingDefaultViewportGap
import build.raft.app.RaftFloatingVerticalPlacement
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftLiveRootAnchorRef
import build.raft.app.RaftPopoverAnchor
import build.raft.app.RaftPopoverLocalPoint
import build.raft.app.RaftModalAnchoredPopover
import build.raft.app.RaftShadow
import build.raft.app.RaftAppStroke
import build.raft.app.RaftType
import build.raft.app.raftCaptureLiveRootAnchor
import build.raft.app.withRaftFontWeight
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

internal data class RaftBrutalSelectOption(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    /** A group heading inside the menu: never selectable, drawn muted in regular weight. */
    val header: Boolean = false
)

internal fun raftSelectMenuWidth(
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
internal fun raftBrutalSelectListHeight(
    optionCount: Int,
    rowHeight: Dp,
    maxMenuHeight: Dp
): Dp =
    ((optionCount.coerceAtLeast(1) * rowHeight.value).dp + RaftMetrics.PopupPadding * 2)
        .coerceAtMost(maxMenuHeight)

/** raft-ui `SelectGroupLabel`: `px-2.5 pt-2 pb-1` around a 12/16 caption = 28dp. */
internal val RaftSelectGroupLabelHeight = 28.dp

/** raft-ui select popup minimum width (`min-w-[max(var(--anchor-width),220px)]`). */
internal val RaftSelectMinWidth = 220.dp

/**
 * raft select popup height: 36dp items and 28dp group labels plus the `p-1.5` inset on
 * both edges, capped at [maxMenuHeight].
 */
internal fun raftSelectListHeight(
    options: List<RaftBrutalSelectOption>,
    maxMenuHeight: Dp
): Dp {
    val rows =
        if (options.isEmpty()) {
            RaftMetrics.MenuRowHeight.value
        } else {
            options.sumOf { option ->
                (if (option.header) RaftSelectGroupLabelHeight else RaftMetrics.MenuRowHeight).value.toDouble()
            }.toFloat()
        }
    return (rows.dp + RaftMetrics.PopupPadding * 2).coerceAtMost(maxMenuHeight)
}

@Composable
internal fun RaftBrutalSelectField(
    options: List<RaftBrutalSelectOption>,
    selectedId: String,
    onSelect: (RaftBrutalSelectOption) -> Unit,
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
    val shadowOffset = if (enabled) RaftShadow.BrutalSmall else 0.dp
    var triggerAnchorValue by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var triggerRootWidthPx by remember { mutableStateOf(0f) }
    val triggerAnchor = remember { RaftLiveRootAnchorRef(identity = "brutal_select") }
    val density = LocalDensity.current
    val menuWidth = raftSelectMenuWidth(triggerRootWidthPx, density.density)
    val menuShadowOffset = RaftShadow.Brutal
    val listHeight = raftBrutalSelectListHeight(options.size, rowHeight, maxMenuHeight)
    if (raftActive()) {
        RaftSelectField(
            options = options,
            selectedId = selectedId,
            onSelect = onSelect,
            modifier = modifier,
            displayLabel = displayLabel,
            placeholder = selectedLabel.isBlank() && selectedOption == null,
            enabled = enabled,
            triggerHeight = raftFieldHeight(triggerHeight),
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
                    .raftCaptureLiveRootAnchor(triggerAnchor) { size ->
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
                            .background(RaftColor.ShadowBlack)
                )
            }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(triggerHeight)
                        .border(RaftAppStroke.Default, RaftColor.BorderBlack)
                        .background(if (enabled) RaftColor.SurfaceWhite else RaftColor.SurfaceCream)
                        .clickable(enabled = enabled) {
                            if (expanded) {
                                expanded = false
                            } else {
                                triggerAnchor.resolve(RaftPopoverLocalPoint.Exact(Offset.Zero))?.let { anchor ->
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
                    style = RaftType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = if (enabled) RaftColor.TextBlack else RaftColor.TextMutedGray),
                    color = if (enabled) RaftColor.TextBlack else RaftColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                RaftLucideIcon(
                    RaftLucideIconName.ChevronDown,
                    modifier = Modifier.size(14.dp),
                    color = if (enabled) "#FF000000" else RaftColor.TextMutedGrayArgb
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
            RaftModalAnchoredPopover(
                anchor = triggerAnchorValue!!,
                contentSize = contentSize,
                onDismiss = { expanded = false },
                viewportGap = with(density) { RaftFloatingDefaultViewportGap.dp.toPx() },
                verticalOffset = with(density) { (triggerHeight + shadowOffset).toPx() },
                verticalOffsetAbove = with(density) { shadowOffset.toPx() },
                verticalPlacement = RaftFloatingVerticalPlacement.Auto
            ) {
                RaftBrutalSelectMenu(
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
 * raft-ui 0.6.0 Select: [RaftLookSelectTrigger] plus a popup on the overlay surface
 * (container radius, popover layer, seam ring, float shadow, 6dp inset). Items are 36dp
 * control-radius rows in callout 14/20 medium; the selected item is bold with a trailing
 * 16dp check (no fill), group headers are 12/16 bold hint captions, disabled rows take
 * the disabled ink. The popup opens 4dp below the trigger, at least as wide as it.
 */
@Composable
private fun RaftSelectField(
    options: List<RaftBrutalSelectOption>,
    selectedId: String,
    onSelect: (RaftBrutalSelectOption) -> Unit,
    modifier: Modifier,
    displayLabel: String,
    placeholder: Boolean,
    enabled: Boolean,
    triggerHeight: Dp,
    maxMenuHeight: Dp
) {
    var expanded by remember { mutableStateOf(false) }
    var triggerAnchorValue by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var triggerRootWidthPx by remember { mutableStateOf(0f) }
    val triggerAnchor = remember { RaftLiveRootAnchorRef(identity = "raft_select") }
    val density = LocalDensity.current
    val menuWidth = raftSelectMenuWidth(triggerRootWidthPx, density.density).coerceAtLeast(RaftSelectMinWidth)
    val rowHeight = RaftMetrics.MenuRowHeight
    val popupInset = RaftMetrics.PopupPadding
    val listHeight = raftSelectListHeight(options, maxMenuHeight)
    val sideOffset = 4.dp
    Column(modifier = modifier.fillMaxWidth()) {
        RaftLookSelectTrigger(
            label = displayLabel,
            placeholder = placeholder,
            modifier =
                Modifier.raftCaptureLiveRootAnchor(triggerAnchor) { size ->
                    triggerRootWidthPx = size.width.toFloat()
                },
            enabled = enabled,
            expanded = expanded,
            height = triggerHeight,
            onClick = {
                if (expanded) {
                    expanded = false
                } else {
                    triggerAnchor.resolve(RaftPopoverLocalPoint.Exact(Offset.Zero))?.let { anchor ->
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
            RaftModalAnchoredPopover(
                anchor = triggerAnchorValue!!,
                contentSize = contentSize,
                onDismiss = { expanded = false },
                viewportGap = with(density) { RaftFloatingDefaultViewportGap.dp.toPx() },
                verticalOffset = with(density) { (triggerHeight + sideOffset).toPx() },
                verticalOffsetAbove = with(density) { sideOffset.toPx() },
                verticalPlacement = RaftFloatingVerticalPlacement.Auto
            ) {
                Box(
                    modifier =
                        Modifier
                            .width(menuWidth)
                            .height(listHeight)
                            // Keeps taps on empty popup space off the dismiss barrier while
                            // letting the rows and the list's scroll win (see the brutal menu).
                            .clickable(onClick = {})
                            .raftLookOverlaySurface()
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
                                        .height(if (option.header) RaftSelectGroupLabelHeight else rowHeight)
                                        .clickable(enabled = rowEnabled) {
                                            expanded = false
                                            onSelect(option)
                                        }
                                        .padding(
                                            start = RaftMetrics.MenuItemPaddingHorizontal,
                                            end = RaftMetrics.MenuItemPaddingHorizontal,
                                            top = if (option.header) RaftMetrics.MenuLabelPaddingTop else 0.dp,
                                            bottom = if (option.header) RaftMetrics.MenuLabelPaddingBottom else 0.dp
                                        ),
                                horizontalArrangement = Arrangement.spacedBy(RaftMetrics.MenuItemGap),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = option.label,
                                    style =
                                        if (option.header) {
                                            RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp).withRaftFontWeight(FontWeight.Bold)
                                        } else {
                                            RaftType.Body
                                                .withRaftFontWeight(if (selected) FontWeight.Bold else FontWeight.Medium)
                                        },
                                    color =
                                        when {
                                            option.header -> RaftColor.ForegroundHint
                                            rowEnabled -> RaftColor.TextBlack
                                            else -> RaftColor.ForegroundDisabled
                                        },
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

@Composable
private fun RaftBrutalSelectMenu(
    options: List<RaftBrutalSelectOption>,
    selectedId: String,
    enabled: Boolean,
    rowHeight: Dp,
    listHeight: Dp,
    onSelect: (RaftBrutalSelectOption) -> Unit,
    modifier: Modifier = Modifier
) {
    val shadowOffset = RaftShadow.Brutal
    Box(
        modifier =
            modifier
                .height(listHeight + shadowOffset)
                // This panel contains a LazyColumn. nativeDispatchCapture(),
                // which raftFloatingContentClickTarget() installs on
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
                    .background(RaftColor.ShadowBlack)
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
                    .background(RaftColor.SurfaceWhite)
                    .clipToBounds()
                    .border(RaftAppStroke.Default, RaftColor.BorderBlack)
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
                                .background(if (selected) RaftColor.BrandYellow else RaftColor.SurfaceWhite)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.width(14.dp), contentAlignment = Alignment.Center) {
                            if (selected) {
                                RaftLucideIcon(RaftLucideIconName.Check, modifier = Modifier.size(12.dp), color = "#FF000000")
                            }
                        }
                        Text(
                            text = option.label,
                            style = if (option.header) RaftType.Caption else RaftType.Caption.withRaftFontWeight(FontWeight.Bold),
                            color = if (rowEnabled) RaftColor.TextBlack else RaftColor.TextMutedGray,
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
