package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.gestures.detectTapGestures
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
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
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

private const val SlockBrutalDropdownMenuWidthValue = 200f
private const val SlockBrutalDropdownMenuRowHeightValue = 40f
// #71: 8dp default vertical offset put the first menu row right at the
// button's bottom edge — easy to mis-click the first action (Stop / Delete /
// etc.) when the user intended the button. 16dp gives a clearer visual gap.
private const val SlockBrutalDropdownMenuVerticalOffsetValue = 16f
private const val SlockBrutalDropdownMenuShadowOffsetValue = 4f

/**
 * Single-action menu item rendered by [SlockBrutalDropdownMenu]. `id` is the stable
 * identity returned via `onSelect`; `destructive` switches the row tint to a red wash
 * (used for "Delete" style actions).
 */
internal data class SlockBrutalDropdownMenuItem(
    val id: String,
    val label: String,
    val icon: SlockLucideIconName? = null,
    val trailing: String? = null,
    val trailingIcon: SlockLucideIconName? = null,
    val destructive: Boolean = false,
    val enabled: Boolean = true
)

/**
 * Unified anchored single-action dropdown menu, used as a more compact alternative to
 * [SlockBrutalActionSheet] for triggers like the three-dot overflow button on a row.
 * Mirrors the React `DropdownMenu` pattern (`relative` parent + `absolute top-full
 * right-0` card with `border-2 border-black bg-white shadow-brutal`).
 *
 * Uses [SlockAnchoredPopover] for positioning so the menu can:
 * - flip vertically when there is no room below the anchor (mirrors React
 *   `placeSidebarContextMenu.ts placeMenuAxis`)
 * - flip horizontally when there is no room to the right of the anchor (#223
 *   `horizontalAutoFlip` pattern, reused from SlockFilterPopover)
 *
 * Selecting an item calls [onSelect] with the item id and then dismisses the menu via
 * [onDismiss]. The host is responsible for actually performing the action in response
 * to the id.
 */
@Composable
internal fun SlockBrutalDropdownMenu(
    anchor: Offset,
    items: List<SlockBrutalDropdownMenuItem>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    widthDp: Float = SlockBrutalDropdownMenuWidthValue,
    horizontalOffsetDp: Float = 0f,
    verticalOffsetDp: Float = SlockBrutalDropdownMenuVerticalOffsetValue,
    horizontalAutoFlip: Boolean = true,
    verticalPlacement: SlockFloatingVerticalPlacement = SlockFloatingVerticalPlacement.Auto
) {
    val density = LocalDensity.current
    val popoverWidth = widthDp.dp
    val shadowOffsetDp = SlockBrutalDropdownMenuShadowOffsetValue.dp
    val rowHeight = SlockBrutalDropdownMenuRowHeightValue.dp
    // Card height = max(n * rowHeight, rowHeight) for a visible "no actions" placeholder
    // when items is empty + shadow offset. SlockAnchoredPopover only needs an upper bound
    // for vertical auto-flip; the actual content size is set by the Column inside, so this
    // is purely advisory. We always reserve at least one row so an empty items list still
    // produces a visible card (e.g. a non-admin user clicking the trigger still sees a
    // "No actions available" panel rather than a 4px-tall sliver).
    val effectiveRowCount = items.size.coerceAtLeast(1)
    val contentSize =
        with(density) {
            Size(
                width = (popoverWidth + shadowOffsetDp).toPx(),
                height =
                    // Rows + the border the rows sit inside (react content-box, see
                    // SlockBrutalDropdownMenuSurface) + the shadow (task #370).
                    ((effectiveRowCount * SlockBrutalDropdownMenuRowHeightValue +
                        SlockStroke.Default.value * 2).dp + shadowOffsetDp)
                        .toPx()
            )
        }
    val horizontalOffsetPx = with(density) { horizontalOffsetDp.dp.toPx() }
    val verticalOffsetPx = with(density) { verticalOffsetDp.dp.toPx() }
    val viewportGapPx = with(density) { SlockFloatingDefaultViewportGap.dp.toPx() }
    SlockAnchoredPopover(
        anchor = anchor,
        contentSize = contentSize,
        onDismiss = onDismiss,
        modifier = modifier,
        viewportGap = viewportGapPx,
        horizontalOffset = horizontalOffsetPx,
        verticalOffset = verticalOffsetPx,
        horizontalAutoFlip = horizontalAutoFlip,
        verticalPlacement = verticalPlacement
    ) { popoverModifier, _ ->
        SlockBrutalDropdownMenuSurface(
            items = items,
            onSelect = onSelect,
            modifier = popoverModifier,
            width = popoverWidth,
            rowHeight = rowHeight,
            shadowOffset = shadowOffsetDp,
            onDismiss = onDismiss
        )
    }
}

@Composable
internal fun SlockBrutalDropdownMenuSurface(
    items: List<SlockBrutalDropdownMenuItem>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = SlockBrutalDropdownMenuWidthValue.dp,
    rowHeight: Dp = SlockBrutalDropdownMenuRowHeightValue.dp,
    shadowOffset: Dp = SlockBrutalDropdownMenuShadowOffsetValue.dp,
    showDividers: Boolean = true,
    dividerBeforeIds: Set<String> = emptySet(),
    onDismiss: () -> Unit = {}
) {
    Box(
        modifier = modifier.padding(end = shadowOffset, bottom = shadowOffset)
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = shadowOffset, y = shadowOffset)
                .background(SlockColor.ShadowBlack)
        )
        Column(
            modifier = Modifier
                .let { base -> if (width != null) base.width(width) else base.fillMaxWidth() }
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .slockFloatingContentClickTarget()
                // react draws its 2px border around the rows (content-box), so the
                // card is rows + 2*2dp tall and the rows are inset by the stroke;
                // Compose paints the border over the content instead — pad the rows
                // in by the stroke to match (task #370).
                .padding(SlockStroke.Default)
        ) {
            if (items.isEmpty()) {
                SlockBrutalDropdownMenuEmptyRow(rowHeight = rowHeight)
            } else {
                items.forEachIndexed { index, item ->
                    if (index > 0 && (showDividers || item.id in dividerBeforeIds)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(SlockStroke.Thin)
                                .background(SlockColor.BorderBlack.copy(alpha = 0.1f))
                        )
                    }
                    SlockBrutalDropdownMenuRow(
                        item = item,
                        rowHeight = rowHeight,
                        onClick = {
                            if (item.enabled) {
                                onSelect(item.id)
                            }
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SlockBrutalDropdownMenuRow(
    item: SlockBrutalDropdownMenuItem,
    rowHeight: Dp,
    onClick: () -> Unit
) {
    // Press feedback mirrors web MenuItem `hover:bg-soft-signal/30` (artin,
    // #proj-uiux task #7): the row washes brand yellow while the finger is
    // down. Destructive rows keep their red wash.
    var pressed by remember { mutableStateOf(false) }
    val rowBackground = if (item.destructive) {
        SlockColor.AccentRed.copy(alpha = 0.08f)
    } else if (pressed && item.enabled) {
        SlockColor.SoftSignal.copy(alpha = 0.30f)
    } else {
        SlockColor.SurfaceWhite
    }
    val labelColor = if (item.destructive) {
        SlockColor.AccentRed
    } else if (!item.enabled) {
        SlockColor.TextBlack.copy(alpha = 0.30f)
    } else {
        SlockColor.TextBlack
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .background(rowBackground)
            .pointerInput(item.enabled) {
                detectTapGestures(
                    onPress = {
                        if (item.enabled) {
                            pressed = true
                            try {
                                tryAwaitRelease()
                            } finally {
                                pressed = false
                            }
                        }
                    },
                    onTap = { if (item.enabled) onClick() }
                )
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item.icon?.let { icon ->
            Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                SlockLucideIcon(icon, modifier = Modifier.size(14.dp), color = if (item.destructive) "#FFD32F2F" else "#FF000000")
            }
        }
        Text(
            text = item.label,
            // react menu rows are text-sm font-medium (14px/20px): measured cap
            // heights in components.ui.menu-item.states are 14px-sized, and 12sp
            // bold rendered visibly smaller/narrower labels (task #370).
            style = SlockType.Caption.copy(
                fontSize = 14.sp,
                lineHeight = 20.sp
            ).withSlockFontWeight(FontWeight.Medium),
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        item.trailing?.let {
            Text(
                text = it,
                style = SlockType.Caption.copy(
                    fontSize = 12.sp,
                    lineHeight = 14.sp
                ),
                color = SlockColor.TextBlack.copy(alpha = 0.40f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        item.trailingIcon?.let {
            // Icon affordances are Lucide, never text glyphs (task #494) —
            // e.g. the selected-item check the raft-ui menu shows.
            SlockLucideIcon(
                name = it,
                color = SlockColor.TextBlackArgb,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun SlockBrutalDropdownMenuEmptyRow(rowHeight: Dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .background(SlockColor.SurfaceWhite)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(MR.strings.common_no_actions_available),
            style = SlockType.Caption.copy(
                fontSize = 11.sp,
                lineHeight = 14.sp
            ).withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
