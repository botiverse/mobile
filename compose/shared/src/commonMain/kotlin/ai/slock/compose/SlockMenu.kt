package ai.slock.compose

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
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

internal data class SlockBaseMenuItem(
    val id: String,
    val label: String,
    val icon: String? = null
)

private val SlockBaseMenuWidth = 136.dp
private val SlockBaseMenuRowHeight = 34.dp
private val SlockBaseMenuShadowOffset = 4.dp
private const val SlockBaseMenuHorizontalOffset = -8f
private const val SlockBaseMenuVerticalOffset = 8f

@Composable
internal fun SlockBaseMenuOverlay(
    anchor: Offset,
    items: List<SlockBaseMenuItem>,
    selectedId: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    surfaceBorderColor: Color = SlockColor.BorderBlack,
    surfaceShadowColor: Color = SlockColor.ShadowBlack,
    selectedRowColor: Color = SlockColor.BrandYellow,
    uppercaseLabels: Boolean = true,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    val shadowOffset = if (surfaceShadowColor == Color.Transparent) 0.dp else SlockBaseMenuShadowOffset
    val menuSize = Size(
        width = SlockBaseMenuWidth.value + shadowOffset.value,
        height = SlockBaseMenuRowHeight.value * items.size + shadowOffset.value
    )
    SlockAnchoredPopover(
        anchor = anchor,
        contentSize = menuSize,
        horizontalOffset = SlockBaseMenuHorizontalOffset,
        verticalOffset = SlockBaseMenuVerticalOffset,
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
                    .width(SlockBaseMenuWidth)
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, surfaceBorderColor)
                    .slockFloatingContentClickTarget()
            ) {
                items.forEach { item ->
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
            if (selected) {
                SlockLucideIcon("check", modifier = Modifier.size(12.dp), color = "#FF000000")
            } else {
                Spacer(modifier = Modifier.width(12.dp))
            }
            Text(
                text = if (uppercaseLabel) item.label.uppercase() else item.label,
                style = SlockType.Caption.copy(
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                ),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            item.icon?.takeIf { it.isNotBlank() }?.let { icon ->
                SlockLucideIcon(icon, modifier = Modifier.size(12.dp), color = "#80000000")
            }
        }
    }
}
