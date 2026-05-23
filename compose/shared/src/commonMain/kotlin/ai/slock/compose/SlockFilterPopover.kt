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
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp

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
    multiSelect: Boolean = true,
    headerSlot: (@Composable () -> Unit)? = null
) {
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    val localAnchor = Offset(anchor.x - overlayOrigin.x, anchor.y - overlayOrigin.y)
    val popoverWidth = 260.dp
    val xClamped = localAnchor.x.coerceAtLeast(8f)
    val yClamped = (localAnchor.y + 6f).coerceAtLeast(8f)
    val filtered =
        if (searchText.isBlank()) options
        else options.filter { it.label.contains(searchText, ignoreCase = true) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOrigin = it.positionInRoot() }
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.TopStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = xClamped.dp, y = yClamped.dp)
                .padding(end = 4.dp, bottom = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(x = 4.dp, y = 4.dp)
                    .background(SlockColor.ShadowBlack)
            )
            Column(
                modifier = Modifier
                    .width(popoverWidth)
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    // Swallow taps inside the card so the scrim dismiss doesn't fire.
                    .clickable(enabled = false, onClick = {})
                    .padding(SlockSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
            ) {
                Text(text = title.uppercase(), style = SlockType.SectionLabel, color = SlockColor.TextBlack)
                headerSlot?.invoke()
                TextField(
                    value = searchText,
                    onValueChange = onSearchChange,
                    placeholder = searchPlaceholder,
                    placeholderColor = SlockColor.TextMutedGray,
                    autoFocus = false,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 32.dp)
                        .background(SlockColor.AppBgLightBlue.copy(alpha = 0.6f))
                        .border(SlockStroke.Thin, SlockColor.BorderBlack)
                        .padding(horizontal = SlockSpacing.Sm, vertical = 6.dp)
                )
                if (filtered.isEmpty()) {
                    Text(
                        text = "No matches",
                        style = SlockType.Caption,
                        color = SlockColor.TextMutedGray,
                        modifier = Modifier.padding(SlockSpacing.Sm)
                    )
                } else {
                    // Cap inner LazyColumn height so the popover never exceeds a sane size.
                    // Heuristic: ~7 rows of 34.dp + padding. The LazyColumn itself handles
                    // scrolling beyond that.
                    Box(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                        LazyColumn {
                            items(filtered.size, key = { i -> filtered[i].id }) { index ->
                                val option = filtered[index]
                                val selected = option.id in selectedIds
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 34.dp)
                                        .background(if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                                        .clickable {
                                            onToggle(option.id)
                                            if (!multiSelect) onDismiss()
                                        }
                                        .padding(horizontal = SlockSpacing.Sm, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
                                ) {
                                    Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                                        if (selected) SlockLucideIcon("check", modifier = Modifier.size(14.dp), color = "#FF000000")
                                    }
                                    option.icon?.let { SlockLucideIcon(it, modifier = Modifier.size(12.dp), color = "#FF000000") }
                                    Text(
                                        text = option.label,
                                        style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                                        color = SlockColor.TextBlack,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
                if (multiSelect && selectedIds.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .heightIn(min = 28.dp)
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .clickable(onClick = onClear)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SlockLucideIcon("x", modifier = Modifier.size(12.dp), color = "#FF000000")
                        Text(
                            text = "Clear".uppercase(),
                            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                            color = SlockColor.TextBlack,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
