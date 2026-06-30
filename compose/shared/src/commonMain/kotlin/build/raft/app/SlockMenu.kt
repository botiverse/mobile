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
    val menuWidth = slockBaseMenuWidth(items, uppercaseLabels)
    val menuSize = with(density) {
        Size(
            width = (menuWidth + shadowOffset).toPx(),
            height =
                SlockBaseMenuRowHeight.toPx() * items.size +
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
                    .slockFloatingContentClickTarget()
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
                        onClick = { onSelect(item.id) }
                    )
                }
            }
        }
    }
}

private fun slockBaseMenuWidth(items: List<SlockBaseMenuItem>, uppercaseLabels: Boolean): Dp {
    val widestLabelWidth =
        items.maxOfOrNull { item ->
            val label = if (uppercaseLabels) item.label.uppercase() else item.label
            label.fold(0) { total, char -> total + if (char.code > 127) 14 else 8 }
        } ?: 0
    return (widestLabelWidth + SlockBaseMenuChromeWidthValue)
        .coerceIn(SlockBaseMenuMinWidth.value.toInt(), SlockBaseMenuMaxWidth.value.toInt())
        .dp
}

@Composable
private fun SlockBaseMenuRow(
    item: SlockBaseMenuItem,
    selected: Boolean,
    selectedRowColor: Color,
    uppercaseLabel: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(SlockBaseMenuRowHeight)
            .background(if (selected) selectedRowColor else SlockColor.SurfaceWhite)
            .padding(horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            val leadingIcon = item.icon ?: SlockLucideIconName.Check.takeIf { selected }
            leadingIcon?.let { icon ->
                SlockLucideIcon(icon, modifier = Modifier.size(SlockBaseMenuIconSize), color = SlockColor.TextBlackArgb)
            } ?: Spacer(modifier = Modifier.width(SlockBaseMenuIconSize))
            Text(
                text = if (uppercaseLabel) item.label.uppercase() else item.label,
                style = SlockType.Caption.copy(
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    letterSpacing = 0.8.sp
                ).withSlockFontWeight(FontWeight.SemiBold),
                color = SlockBaseMenuForegroundColor,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
