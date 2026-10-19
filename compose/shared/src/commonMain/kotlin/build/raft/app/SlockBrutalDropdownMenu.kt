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
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.ui.graphics.Color
import build.raft.app.components.SlockRaftMetrics
import build.raft.app.components.slockRaftActive
import build.raft.app.components.slockRaftOverlaySurface
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
    anchor: SlockPopoverAnchor,
    items: List<SlockBrutalDropdownMenuItem>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    widthDp: Float = SlockBrutalDropdownMenuWidthValue,
    horizontalOffsetDp: Float = 0f,
    // null = the register default: raft-ui DropdownMenuContent's `sideOffset ?? 6`, the
    // brutal register's 16dp (#71).
    verticalOffsetDp: Float? = null,
    horizontalAutoFlip: Boolean = true,
    verticalPlacement: SlockFloatingVerticalPlacement = SlockFloatingVerticalPlacement.Auto
) {
    val density = LocalDensity.current
    val raft = slockRaftActive()
    // raft-ui DropdownMenu content is `min-w-60`.
    val popoverWidth = if (raft) slockRaftMenuWidth(widthDp.dp) else widthDp.dp
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
            if (raft) {
                // raft: 36dp rows in a p-1.5 popup; the float shadow takes no layout space.
                Size(
                    width = popoverWidth.toPx(),
                    height =
                        (effectiveRowCount * SlockRaftMetrics.MenuRowHeight.value + SlockRaftMetrics.PopupPadding.value * 2)
                            .dp
                            .toPx()
                )
            } else {
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
        }
    val horizontalOffsetPx = with(density) { horizontalOffsetDp.dp.toPx() }
    val resolvedVerticalOffsetDp =
        verticalOffsetDp
            ?: if (raft) SlockRaftMetrics.MenuSideOffset.value else SlockBrutalDropdownMenuVerticalOffsetValue
    val verticalOffsetPx = with(density) { resolvedVerticalOffsetDp.dp.toPx() }
    val viewportGapPx = with(density) { SlockFloatingDefaultViewportGap.dp.toPx() }
    SlockModalAnchoredPopover(
        anchor = anchor,
        contentSize = contentSize,
        onDismiss = onDismiss,
        modifier = modifier,
        viewportGap = viewportGapPx,
        horizontalOffset = horizontalOffsetPx,
        verticalOffset = verticalOffsetPx,
        horizontalAutoFlip = horizontalAutoFlip,
        verticalPlacement = verticalPlacement
    ) {
        SlockBrutalDropdownMenuSurface(
            items = items,
            onSelect = onSelect,
            modifier = Modifier,
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
    // Optional card header rendered above the rows inside the same border/shadow
    // (task #11: the message long-press menu's reaction bar), separated from the
    // rows by a heavy divider.
    headerContent: (@Composable () -> Unit)? = null,
    // raft-ui DropdownMenuItem draws 16dp icons; web's hand-rolled MenuItem (the message
    // context menu) keeps lucide's `size={14}`.
    iconSize: Dp = SlockRaftMetrics.MenuIconSize,
    onDismiss: () -> Unit = {}
) {
    if (slockRaftActive()) {
        SlockRaftDropdownMenuSurface(
            items = items,
            onSelect = onSelect,
            modifier = modifier,
            width = width?.let(::slockRaftMenuWidth),
            rowHeight = rowHeight,
            dividerBeforeIds = dividerBeforeIds,
            headerContent = headerContent,
            iconSize = iconSize,
            onDismiss = onDismiss
        )
        return
    }
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
            headerContent?.let { header ->
                header()
                Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
            }
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

/** raft-ui menu popup width: the caller's width, never under `min-w-60`. */
internal fun slockRaftMenuWidth(width: Dp): Dp =
    if (width < SlockRaftMetrics.MenuMinWidth) SlockRaftMetrics.MenuMinWidth else width

/**
 * raft-ui 0.6.0 DropdownMenu / ContextMenu content: container radius, `--layer-popover`,
 * 1dp seam ring, float shadow, `p-1.5` inset, `min-w-60`. Only explicit separators are
 * drawn: raft menus do not rule every row. A separator is the menu's `-mx-1.5 my-1.5`
 * hairline, so it bleeds through the 6dp side inset to the popup edges while the rows
 * stay inset. A header (reaction bar) sits above a separator. The legacy 40dp row
 * default becomes raft's 36dp `min-h-9` row.
 */
@Composable
private fun SlockRaftDropdownMenuSurface(
    items: List<SlockBrutalDropdownMenuItem>,
    onSelect: (String) -> Unit,
    modifier: Modifier,
    width: Dp?,
    rowHeight: Dp,
    dividerBeforeIds: Set<String>,
    headerContent: (@Composable () -> Unit)?,
    iconSize: Dp,
    onDismiss: () -> Unit
) {
    val resolvedRowHeight =
        if (rowHeight == SlockBrutalDropdownMenuRowHeightValue.dp) SlockRaftMetrics.MenuRowHeight else rowHeight
    val inset = Modifier.padding(horizontal = SlockRaftMetrics.PopupPadding)
    Column(
        modifier = modifier
            .let { base -> if (width != null) base.width(width) else base.fillMaxWidth() }
            .slockFloatingContentClickTarget()
            .slockRaftOverlaySurface()
            .padding(vertical = SlockRaftMetrics.PopupPadding)
    ) {
        headerContent?.let { header ->
            Box(modifier = inset.fillMaxWidth()) { header() }
            SlockRaftMenuSeparator()
        }
        if (items.isEmpty()) {
            Row(
                modifier = inset
                    .fillMaxWidth()
                    .height(resolvedRowHeight)
                    .padding(horizontal = SlockRaftMetrics.MenuItemPaddingHorizontal),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(MR.strings.common_no_actions_available),
                    style = SlockType.Body,
                    color = SlockColor.ForegroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            items.forEachIndexed { index, item ->
                if (index > 0 && item.id in dividerBeforeIds) {
                    SlockRaftMenuSeparator()
                }
                Box(modifier = inset.fillMaxWidth()) {
                    SlockBrutalDropdownMenuRow(
                        item = item,
                        rowHeight = resolvedRowHeight,
                        iconSize = iconSize,
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

/** raft-ui menu separator: a full-bleed 1dp `--line-muted` hairline with `my-1.5`. */
@Composable
private fun SlockRaftMenuSeparator() {
    Box(
        modifier = Modifier
            .padding(vertical = SlockRaftMetrics.MenuSeparatorInset)
            .fillMaxWidth()
            .height(SlockStroke.Thin)
            .background(SlockColor.LineMuted)
    )
}

@Composable
private fun SlockBrutalDropdownMenuRow(
    item: SlockBrutalDropdownMenuItem,
    rowHeight: Dp,
    iconSize: Dp = SlockRaftMetrics.MenuIconSize,
    onClick: () -> Unit
) {
    // Press feedback mirrors web MenuItem `hover:bg-soft-signal/30` (artin,
    // #proj-uiux task #7): the row washes brand yellow while the finger is
    // down. Destructive rows keep their red wash.
    var pressed by remember(item.id) { mutableStateOf(false) }
    val gestureOwner =
        rememberSlockGestureOwner(
            surface = "brutal-dropdown-row",
            semanticIdentity = item.id,
            callbacks =
                SlockBrutalDropdownRowGestureCallbacks(
                    enabled = item.enabled,
                    onClick = onClick,
                    onPressedChange = { pressed = it }
                )
        )
    if (slockRaftActive()) {
        SlockRaftDropdownMenuRow(
            item = item,
            rowHeight = rowHeight,
            iconSize = iconSize,
            pressed = pressed,
            gestureModifier = Modifier.slockGesturePointerInput(gestureOwner, item.enabled) { owner ->
                var gesture: SlockGestureSession<SlockBrutalDropdownRowGestureCallbacks>? = null
                detectTapGestures(
                    onPress = {
                        val session = owner.beginGesture().also { gesture = it }
                        val callbacks = session.currentCallbacksOrNull()
                        if (callbacks != null && callbacks.enabled) {
                            callbacks.onPressedChange(true)
                            try {
                                tryAwaitRelease()
                            } finally {
                                gesture?.currentCallbacksOrNull()?.onPressedChange(false)
                            }
                        }
                    },
                    onTap = {
                        (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.let { callbacks ->
                            if (callbacks.enabled) callbacks.onClick()
                        }
                    }
                )
            }
        )
        return
    }
    val rowBackground = if (item.destructive) {
        // deliberate react-parity wash (menu destructive row / press soft-signal, web bg-*/N); triaged token-audit #1062
        SlockColor.AccentRed.copy(alpha = 0.08f)
    } else if (pressed && item.enabled) {
        SlockColor.SoftSignal.copy(alpha = 0.30f) // react-parity: intentional bg-soft-signal/30 wash
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
            .slockGesturePointerInput(gestureOwner, item.enabled) { owner ->
                var gesture: SlockGestureSession<SlockBrutalDropdownRowGestureCallbacks>? = null
                detectTapGestures(
                    onPress = {
                        val session = owner.beginGesture().also { gesture = it }
                        val callbacks = session.currentCallbacksOrNull()
                        if (callbacks != null && callbacks.enabled) {
                            callbacks.onPressedChange(true)
                            try {
                                tryAwaitRelease()
                            } finally {
                                gesture?.currentCallbacksOrNull()?.onPressedChange(false)
                            }
                        }
                    },
                    onTap = {
                        (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.let { callbacks ->
                            if (callbacks.enabled) callbacks.onClick()
                        }
                    }
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

/**
 * raft-ui menu item: min 36dp, control radius, px-2.5, 10dp gap, callout 14/20 medium;
 * pressed/highlighted = `--primary-soft`; 16dp icons in icon ink; destructive items take
 * the `--danger-strong` ink (no red wash); shortcut / trailing text is mono 12/16 hint.
 */
@Composable
private fun SlockRaftDropdownMenuRow(
    item: SlockBrutalDropdownMenuItem,
    rowHeight: Dp,
    iconSize: Dp,
    pressed: Boolean,
    gestureModifier: Modifier
) {
    val ink =
        when {
            !item.enabled -> SlockColor.ForegroundDisabled
            item.destructive -> SlockColor.DangerStrong
            else -> SlockColor.TextBlack
        }
    val iconInk =
        when {
            !item.enabled -> SlockColor.ForegroundDisabled
            item.destructive -> SlockColor.DangerStrong
            else -> SlockColor.ForegroundIcon
        }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .background(
                if (pressed && item.enabled) SlockColor.PrimarySoft else Color.Transparent,
                RoundedCornerShape(SlockRadius.Control)
            )
            .then(gestureModifier)
            .padding(horizontal = SlockRaftMetrics.MenuItemPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockRaftMetrics.MenuItemGap)
    ) {
        item.icon?.let { icon ->
            SlockLucideIcon(icon, modifier = Modifier.size(iconSize), color = slockArgbHex(iconInk))
        }
        Text(
            text = item.label,
            // callout 14/20 medium = the shared content size.
            style = SlockType.Body.withSlockFontWeight(FontWeight.Medium),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        item.trailing?.let {
            Text(
                text = it,
                style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                color = SlockColor.ForegroundHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        item.trailingIcon?.let {
            // `indicator`: ml-auto size-4 check in foreground ink.
            SlockLucideIcon(name = it, color = SlockColor.TextBlackArgb, modifier = Modifier.size(SlockRaftMetrics.MenuIconSize))
        }
    }
}

private data class SlockBrutalDropdownRowGestureCallbacks(
    val enabled: Boolean,
    val onClick: () -> Unit,
    val onPressedChange: (Boolean) -> Unit
)

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
