package ai.slock.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.lazy.items
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val SlockFilterPopoverWidthValue = 220f
private const val SlockFilterPopoverEstimatedHeightValue = 360f
private const val SlockFilterPopoverViewportGapValue = 8f
private const val SlockFilterPopoverVerticalOffsetValue = 8f

/**
 * Generic option shown by [SlockFilterPopover]. `id` must be stable across recompositions
 * — it's the LazyColumn key and the selection identity used by `selectedIds`.
 */
internal data class SlockFilterOption(
    val id: String,
    val label: String,
    /** Optional Lucide icon name. Must already be registered in `SlockLucideIcons.Names`. */
    val icon: String? = null
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
    anchor: Offset,
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
    headerSlot: (@Composable () -> Unit)? = null
) {
    val density = LocalDensity.current
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    var overlayWidth by remember { mutableStateOf(0f) }
    var overlayHeight by remember { mutableStateOf(0f) }
    var dismissEnabled by remember(anchor, title) { mutableStateOf(false) }
    val shadowOffset = if (surfaceShadowColor == Color.Transparent) 0.dp else 4.dp
    val localAnchor = Offset(anchor.x - overlayOrigin.x, anchor.y - overlayOrigin.y)
    val popoverWidth = widthDp.dp
    val maxX =
        (overlayWidth - widthDp - SlockFilterPopoverViewportGapValue)
            .coerceAtLeast(SlockFilterPopoverViewportGapValue)
    val maxY =
        (overlayHeight - SlockFilterPopoverEstimatedHeightValue - SlockFilterPopoverViewportGapValue)
            .coerceAtLeast(SlockFilterPopoverViewportGapValue)
    val xClamped = (localAnchor.x + horizontalOffsetDp).coerceIn(SlockFilterPopoverViewportGapValue, maxX)
    val yClamped = (localAnchor.y + verticalOffsetDp).coerceIn(SlockFilterPopoverViewportGapValue, maxY)
    val xOffsetDp = with(density) { xClamped.toDp() }
    val yOffsetDp = with(density) { yClamped.toDp() }
    val filtered =
        if (searchText.isBlank()) options
        else options.filter { it.label.contains(searchText, ignoreCase = true) }
    LaunchedEffect(anchor, title) {
        dismissEnabled = false
        delay(120)
        dismissEnabled = true
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                overlayOrigin = it.positionInRoot()
                overlayWidth = it.size.width.toFloat()
                overlayHeight = it.size.height.toFloat()
            }
            .clickable(enabled = dismissEnabled, onClick = onDismiss),
        contentAlignment = Alignment.TopStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = xOffsetDp, y = yOffsetDp)
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
                        text = title.uppercase(),
                        style = SlockType.Caption.copy(
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            letterSpacing = 0.9.sp
                        ).withSlockFontWeight(FontWeight.Bold),
                        color = SlockColor.TextMutedGray
                    )
                    Box(modifier = Modifier.weight(1f))
                    if (multiSelect && selectedIds.isNotEmpty()) {
                        Text(
                            text = "CLEAR",
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(SlockStroke.Thin, SlockColor.BorderBlack.copy(alpha = 0.1f))
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    TextField(
                        value = searchText,
                        onValueChange = onSearchChange,
                        placeholder = searchPlaceholder,
                        placeholderColor = SlockColor.TextMutedGray,
                        textStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
                        autoFocus = false,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 36.dp)
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }
                if (filtered.isEmpty()) {
                    Text(
                        text = "No matches",
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
                                            .heightIn(min = 36.dp)
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
                                            SlockLucideIcon("check", modifier = Modifier.size(12.dp), color = "#FF000000")
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
