package build.raft.app

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
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.TextUnit
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

internal data class SlockBaseMenuItem(
    val id: String,
    val label: String,
    val icon: SlockLucideIconName? = null,
    val dividerBefore: Boolean = false
)

private val SlockBaseMenuMinWidth = 136.dp
private val SlockBaseMenuMaxWidth = 280.dp
private val SlockBaseMenuRowHeight = 34.dp
private val SlockBaseMenuIconSize = 14.dp
private val SlockBaseMenuShadowOffset = 4.dp
private val SlockBaseMenuHorizontalOffset = (-8).dp
private val SlockBaseMenuVerticalOffset = 8.dp
private val SlockBaseMenuViewportGap = 8.dp
private val SlockBaseMenuForegroundColor = Color(0xFF000000)
private const val SlockBaseMenuChromeWidthValue = 84

// Status editors can be opened from a card near either viewport edge. React measures
// the trigger and flips the portal above when needed; retain that contract here.
internal val SlockTaskStatusMenuVerticalPlacement = SlockFloatingVerticalPlacement.Auto

internal enum class SlockMenuSelectedIndicatorPlacement {
    Leading,
    Trailing
}

@Composable
internal fun SlockBaseMenuOverlay(
    anchor: Offset,
    items: List<SlockBaseMenuItem>,
    selectedId: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    surfaceBorderColor: Color = SlockBaseMenuForegroundColor,
    surfaceShadowColor: Color = SlockBaseMenuForegroundColor,
    selectedRowColor: Color = SlockColor.BrandYellow,
    uppercaseLabels: Boolean = true,
    minWidth: Dp = SlockBaseMenuMinWidth,
    // Exact menu width (border-box, like react's fixed-width menus). When null the
    // width is estimated from the widest label via slockBaseMenuWidth (task #370).
    width: Dp? = null,
    rowHeight: Dp = SlockBaseMenuRowHeight,
    labelFontSize: TextUnit = 12.sp,
    labelLineHeight: TextUnit = 14.sp,
    labelFontWeight: FontWeight = FontWeight.SemiBold,
    labelLetterSpacing: TextUnit = 0.8.sp,
    selectedIndicatorPlacement: SlockMenuSelectedIndicatorPlacement = SlockMenuSelectedIndicatorPlacement.Leading,
    modifier: Modifier = Modifier,
    horizontalOffset: Dp = SlockBaseMenuHorizontalOffset,
    verticalOffset: Dp = SlockBaseMenuVerticalOffset,
    // #223: default to Auto so the menu flips above the trigger when
    // there's no room below. The trigger (e.g. row top-left) is
    // passed in as `anchor`, so the menu's natural reading is "drop
    // below the trigger; if no room, flip above".
    verticalPlacement: SlockFloatingVerticalPlacement = SlockFloatingVerticalPlacement.Auto
) {
    if (items.isEmpty()) return
    val density = LocalDensity.current
    val shadowOffset = if (surfaceShadowColor == Color.Transparent) 0.dp else SlockBaseMenuShadowOffset
    val menuWidth = width ?: slockBaseMenuWidth(items, uppercaseLabels, minWidth)
    val menuSize = with(density) {
        Size(
            width = (menuWidth + shadowOffset).toPx(),
            height =
                rowHeight.toPx() * items.size +
                    // Rows sit inside the border (react content-box height), see the
                    // Column's border padding below (task #370).
                    SlockStroke.Default.toPx() * 2 +
                    SlockStroke.Default.toPx() * items.count { it.dividerBefore } +
                    shadowOffset.toPx()
        )
    }
    val horizontalOffsetPx = with(density) { horizontalOffset.toPx() }
    val verticalOffsetPx = with(density) { verticalOffset.toPx() }
    val viewportGapPx = with(density) { SlockBaseMenuViewportGap.toPx() }
    SlockAnchoredPopover(
        anchor = anchor,
        contentSize = menuSize,
        viewportGap = viewportGapPx,
        horizontalOffset = horizontalOffsetPx,
        verticalOffset = verticalOffsetPx,
        verticalPlacement = verticalPlacement,
        horizontalAutoFlip = true,
        onDismiss = onDismiss,
        modifier = modifier
    ) { popoverModifier, _ ->
        Box(
            modifier = popoverModifier
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
                    .width(menuWidth)
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, surfaceBorderColor)
                    // This surface is the parent of actual Button rows. Native dispatch
                    // capture here wins before child rows on Android, so only use the
                    // no-op clickable needed to keep blank menu space out of the
                    // sibling dismiss barrier.
                    .clickable(onClick = {})
                    // react draws its 2px border around the rows (content-box), so
                    // the menu is rows + 2*2dp tall and the rows are inset by the
                    // stroke; Compose paints the border over the content instead —
                    // pad the rows in by the stroke to match (task #370).
                    .padding(SlockStroke.Default)
            ) {
                items.forEach { item ->
                    if (item.dividerBefore) {
                        Box(Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockBaseMenuForegroundColor))
                    }
                    val selected = item.id == selectedId
                    SlockBaseMenuRow(
                        item = item,
                        selected = selected,
                        selectedRowColor = selectedRowColor,
                        uppercaseLabel = uppercaseLabels,
                        rowHeight = rowHeight,
                        labelFontSize = labelFontSize,
                        labelLineHeight = labelLineHeight,
                        labelFontWeight = labelFontWeight,
                        labelLetterSpacing = labelLetterSpacing,
                        selectedIndicatorPlacement = selectedIndicatorPlacement,
                        onClick = { onSelect(item.id) }
                    )
                }
            }
        }
    }
}

/**
 * The task-status dropdown, aligned to react (#370/#112). This is the single
 * source of truth for that menu's styling — the Tasks page and the thread
 * conversation flow (#394) both go through here so the two can never drift back
 * to the SlockBaseMenuOverlay defaults (uppercase labels, yellow selected row,
 * leading check, estimated width) that read as the "old" menu. Callers pass only
 * their own status items + selection/dispatch; the visual style lives here.
 */
@Composable
internal fun SlockTaskStatusMenuOverlay(
    anchor: Offset,
    items: List<SlockBaseMenuItem>,
    selectedId: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    SlockBaseMenuOverlay(
        anchor = anchor,
        items = items,
        selectedId = selectedId,
        selectedRowColor = SlockColor.SurfaceWhite,
        uppercaseLabels = false,
        // react's status menu renders at exactly its 140px min-width (the longest
        // label "In Progress" is narrower); the char estimate overshoots to 172dp.
        width = 140.dp,
        rowHeight = 36.dp,
        labelFontSize = 14.sp,
        labelLineHeight = 20.sp,
        labelFontWeight = FontWeight.Normal,
        labelLetterSpacing = 0.sp,
        selectedIndicatorPlacement = SlockMenuSelectedIndicatorPlacement.Trailing,
        horizontalOffset = 0.dp,
        verticalOffset = 4.dp,
        verticalPlacement = SlockTaskStatusMenuVerticalPlacement,
        onDismiss = onDismiss,
        onSelect = onSelect
    )
}

private fun slockBaseMenuWidth(items: List<SlockBaseMenuItem>, uppercaseLabels: Boolean, minWidth: Dp): Dp {
    val widestLabelWidth =
        items.maxOfOrNull { item ->
            val label = if (uppercaseLabels) item.label.uppercase() else item.label
            label.fold(0) { total, char -> total + if (char.code > 127) 14 else 8 }
        } ?: 0
    return (widestLabelWidth + SlockBaseMenuChromeWidthValue)
        .coerceIn(minWidth.value.toInt(), SlockBaseMenuMaxWidth.value.toInt())
        .dp
}

@Composable
private fun SlockBaseMenuRow(
    item: SlockBaseMenuItem,
    selected: Boolean,
    selectedRowColor: Color,
    uppercaseLabel: Boolean,
    rowHeight: Dp,
    labelFontSize: TextUnit,
    labelLineHeight: TextUnit,
    labelFontWeight: FontWeight,
    labelLetterSpacing: TextUnit,
    selectedIndicatorPlacement: SlockMenuSelectedIndicatorPlacement,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .background(if (selected) selectedRowColor else SlockColor.SurfaceWhite)
            .padding(horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            val leadingIcon =
                item.icon ?: SlockLucideIconName.Check.takeIf {
                    selected && selectedIndicatorPlacement == SlockMenuSelectedIndicatorPlacement.Leading
                }
            if (leadingIcon != null) {
                SlockLucideIcon(leadingIcon, modifier = Modifier.size(SlockBaseMenuIconSize), color = SlockColor.TextBlackArgb)
            } else if (selectedIndicatorPlacement == SlockMenuSelectedIndicatorPlacement.Leading) {
                Spacer(modifier = Modifier.width(SlockBaseMenuIconSize))
            }
            Text(
                text = if (uppercaseLabel) item.label.uppercase() else item.label,
                style = SlockType.Caption.copy(
                    fontSize = labelFontSize,
                    lineHeight = labelLineHeight,
                    letterSpacing = labelLetterSpacing
                ).withSlockFontWeight(labelFontWeight),
                color = SlockBaseMenuForegroundColor,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (selectedIndicatorPlacement == SlockMenuSelectedIndicatorPlacement.Trailing) {
                if (selected) {
                    SlockLucideIcon(SlockLucideIconName.Check, modifier = Modifier.size(SlockBaseMenuIconSize), color = SlockColor.TextBlackArgb)
                } else {
                    Spacer(modifier = Modifier.width(SlockBaseMenuIconSize))
                }
            }
        }
    }
}
