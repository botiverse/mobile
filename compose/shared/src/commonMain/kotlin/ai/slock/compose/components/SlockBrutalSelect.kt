package ai.slock.compose.components

import ai.slock.compose.Modal
import ai.slock.compose.SlockColor
import ai.slock.compose.SlockLucideIcon
import ai.slock.compose.SlockLucideIconName
import ai.slock.compose.SlockStroke
import ai.slock.compose.SlockType
import ai.slock.compose.withSlockFontWeight
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
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

internal data class SlockBrutalSelectOption(
    val id: String,
    val label: String,
    val enabled: Boolean = true
)

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
    val shadowOffset = if (enabled) 3.dp else 0.dp
    // Track the trigger's root position so we can render the menu as a
    // full-screen overlay (`Modal { ... }`) anchored to it instead of
    // pushing content. Captured per member-id to handle list reuse.
    var triggerRootOffset by remember { mutableStateOf(Offset.Zero) }
    var triggerRootWidth by remember { mutableStateOf(0f) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(triggerHeight + shadowOffset)
                    .onGloballyPositioned { coords ->
                        triggerRootOffset = coords.positionInRoot()
                        triggerRootWidth = coords.size.width.toFloat()
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
                        .clickable(enabled = enabled) { expanded = !expanded }
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
        if (expanded && enabled) {
            Modal(onWillDismiss = { expanded = false }) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clickable { expanded = false },
                    contentAlignment = Alignment.TopStart
                ) {
                    // Anchor the menu under the trigger (3dp visual gap mirrors
                    // the trigger's shadow offset so the menu visually
                    // continues the trigger's brutal shadow line).
                    val menuModifier = if (triggerRootWidth > 0f) {
                        Modifier
                            .offset {
                                IntOffset(
                                    x = triggerRootOffset.x.toInt(),
                                    y = (triggerRootOffset.y + triggerHeight.value + shadowOffset.value).toInt()
                                )
                            }
                            .width(triggerRootWidth.toInt().dp)
                    } else {
                        Modifier
                            .offset {
                                IntOffset(
                                    x = triggerRootOffset.x.toInt(),
                                    y = (triggerRootOffset.y + triggerHeight.value + shadowOffset.value).toInt()
                                )
                            }
                    }
                    SlockBrutalSelectMenu(
                        options = options,
                        selectedId = selectedId,
                        enabled = enabled,
                        rowHeight = rowHeight,
                        maxMenuHeight = maxMenuHeight,
                        modifier = menuModifier,
                        onSelect = { option ->
                            expanded = false
                            onSelect(option)
                        }
                    )
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
    maxMenuHeight: Dp,
    onSelect: (SlockBrutalSelectOption) -> Unit,
    modifier: Modifier = Modifier
) {
    val shadowOffset = 3.dp
    val listHeight = ((options.size * rowHeight.value) + SlockStroke.Default.value).dp.coerceAtMost(maxMenuHeight)
    Box(
        modifier =
            modifier
                .height(listHeight + shadowOffset)
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(listHeight)
                    .offset(x = shadowOffset, y = shadowOffset)
                    .background(SlockColor.ShadowBlack)
        )
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(listHeight)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .background(SlockColor.SurfaceWhite)
        ) {
            items(options.size, key = { idx -> options[idx].id }) { idx ->
                val option = options[idx]
                val selected = selectedId == option.id
                val rowEnabled = enabled && option.enabled
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
                        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold),
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
