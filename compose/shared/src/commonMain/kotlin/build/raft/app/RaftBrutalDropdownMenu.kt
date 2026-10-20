package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.compose.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import build.raft.app.components.RaftMetrics
import build.raft.app.components.raftActive
import build.raft.app.components.raftLookOverlaySurface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val RaftBrutalDropdownMenuWidthValue = 200f
private const val RaftBrutalDropdownMenuRowHeightValue = 40f
// #71: 8dp default vertical offset put the first menu row right at the
// button's bottom edge — easy to mis-click the first action (Stop / Delete /
// etc.) when the user intended the button. 16dp gives a clearer visual gap.
private const val RaftBrutalDropdownMenuVerticalOffsetValue = 16f
private const val RaftBrutalDropdownMenuShadowOffsetValue = 4f

/**
 * Single-action menu item rendered by [RaftBrutalDropdownMenu]. `id` is the stable
 * identity returned via `onSelect`; `destructive` switches the row tint to a red wash
 * (used for "Delete" style actions).
 */
internal data class RaftBrutalDropdownMenuItem(
    val id: String,
    val label: String,
    val icon: RaftLucideIconName? = null,
    val trailing: String? = null,
    val trailingIcon: RaftLucideIconName? = null,
    val destructive: Boolean = false,
    val enabled: Boolean = true
)

/**
 * Unified anchored single-action dropdown menu, used as a more compact alternative to
 * [SlockBrutalActionSheet] for triggers like the three-dot overflow button on a row.
 * Mirrors the React `DropdownMenu` pattern (`relative` parent + `absolute top-full
 * right-0` card with `border-2 border-black bg-white shadow-brutal`).
 *
 * Uses [RaftAnchoredPopover] for positioning so the menu can:
 * - flip vertically when there is no room below the anchor (mirrors React
 *   `placeSidebarContextMenu.ts placeMenuAxis`)
 * - flip horizontally when there is no room to the right of the anchor (#223
 *   `horizontalAutoFlip` pattern, reused from RaftFilterPopover)
 *
 * Selecting an item calls [onSelect] with the item id and then dismisses the menu via
 * [onDismiss]. The host is responsible for actually performing the action in response
 * to the id.
 */
@Composable
internal fun RaftBrutalDropdownMenu(
    anchor: RaftPopoverAnchor,
    items: List<RaftBrutalDropdownMenuItem>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    widthDp: Float = RaftBrutalDropdownMenuWidthValue,
    horizontalOffsetDp: Float = 0f,
    // null = the register default: raft-ui DropdownMenuContent's `sideOffset ?? 6`, the
    // brutal register's 16dp (#71).
    verticalOffsetDp: Float? = null,
    horizontalAutoFlip: Boolean = true,
    verticalPlacement: RaftFloatingVerticalPlacement = RaftFloatingVerticalPlacement.Auto
) {
    val density = LocalDensity.current
    val raft = raftActive()
    // raft-ui DropdownMenu content is `min-w-60`.
    val popoverWidth = if (raft) raftMenuWidth(widthDp.dp) else widthDp.dp
    val shadowOffsetDp = RaftBrutalDropdownMenuShadowOffsetValue.dp
    val rowHeight = RaftBrutalDropdownMenuRowHeightValue.dp
    // Card height = max(n * rowHeight, rowHeight) for a visible "no actions" placeholder
    // when items is empty + shadow offset. RaftAnchoredPopover only needs an upper bound
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
                        (effectiveRowCount * RaftMetrics.MenuRowHeight.value + RaftMetrics.PopupPadding.value * 2)
                            .dp
                            .toPx()
                )
            } else {
                Size(
                    width = (popoverWidth + shadowOffsetDp).toPx(),
                    height =
                        // Rows + the border the rows sit inside (react content-box, see
                        // RaftBrutalDropdownMenuSurface) + the shadow (task #370).
                        ((effectiveRowCount * RaftBrutalDropdownMenuRowHeightValue +
                            RaftAppStroke.Default.value * 2).dp + shadowOffsetDp)
                            .toPx()
                )
            }
        }
    val horizontalOffsetPx = with(density) { horizontalOffsetDp.dp.toPx() }
    val resolvedVerticalOffsetDp =
        verticalOffsetDp
            ?: if (raft) RaftMetrics.MenuSideOffset.value else RaftBrutalDropdownMenuVerticalOffsetValue
    val verticalOffsetPx = with(density) { resolvedVerticalOffsetDp.dp.toPx() }
    val viewportGapPx = with(density) { RaftFloatingDefaultViewportGap.dp.toPx() }
    RaftModalAnchoredPopover(
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
        RaftBrutalDropdownMenuSurface(
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
internal fun RaftBrutalDropdownMenuSurface(
    items: List<RaftBrutalDropdownMenuItem>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = RaftBrutalDropdownMenuWidthValue.dp,
    rowHeight: Dp = RaftBrutalDropdownMenuRowHeightValue.dp,
    shadowOffset: Dp = RaftBrutalDropdownMenuShadowOffsetValue.dp,
    showDividers: Boolean = true,
    dividerBeforeIds: Set<String> = emptySet(),
    // Optional card header rendered above the rows inside the same border/shadow
    // (task #11: the message long-press menu's reaction bar), separated from the
    // rows by a heavy divider.
    headerContent: (@Composable () -> Unit)? = null,
    // raft-ui DropdownMenuItem draws 16dp icons; web's hand-rolled MenuItem (the message
    // context menu) keeps lucide's `size={14}`.
    iconSize: Dp = RaftMetrics.MenuIconSize,
    onDismiss: () -> Unit = {}
) {
    if (raftActive()) {
        RaftDropdownMenuSurface(
            items = items,
            onSelect = onSelect,
            modifier = modifier,
            width = width?.let(::raftMenuWidth),
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
                .background(RaftColor.ShadowBlack)
        )
        Column(
            modifier = Modifier
                .let { base -> if (width != null) base.width(width) else base.fillMaxWidth() }
                .background(RaftColor.SurfaceWhite)
                .border(RaftAppStroke.Default, RaftColor.BorderBlack)
                .raftFloatingContentClickTarget()
                // react draws its 2px border around the rows (content-box), so the
                // card is rows + 2*2dp tall and the rows are inset by the stroke;
                // Compose paints the border over the content instead — pad the rows
                // in by the stroke to match (task #370).
                .padding(RaftAppStroke.Default)
        ) {
            headerContent?.let { header ->
                header()
                Box(modifier = Modifier.fillMaxWidth().height(RaftAppStroke.Default).background(RaftColor.BorderBlack))
            }
            if (items.isEmpty()) {
                RaftBrutalDropdownMenuEmptyRow(rowHeight = rowHeight)
            } else {
                items.forEachIndexed { index, item ->
                    if (index > 0 && (showDividers || item.id in dividerBeforeIds)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(RaftAppStroke.Thin)
                                .background(RaftColor.BorderBlack.copy(alpha = 0.1f))
                        )
                    }
                    RaftBrutalDropdownMenuRow(
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
internal fun raftMenuWidth(width: Dp): Dp =
    if (width < RaftMetrics.MenuMinWidth) RaftMetrics.MenuMinWidth else width

/**
 * raft-ui 0.6.0 DropdownMenu / ContextMenu content: container radius, `--layer-popover`,
 * 1dp seam ring, float shadow, `p-1.5` inset, `min-w-60`. Only explicit separators are
 * drawn: raft menus do not rule every row. A separator is the menu's `-mx-1.5 my-1.5`
 * hairline, so it bleeds through the 6dp side inset to the popup edges while the rows
 * stay inset. A header (reaction bar) sits above a separator. The legacy 40dp row
 * default becomes raft's 36dp `min-h-9` row.
 */
@Composable
private fun RaftDropdownMenuSurface(
    items: List<RaftBrutalDropdownMenuItem>,
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
        if (rowHeight == RaftBrutalDropdownMenuRowHeightValue.dp) RaftMetrics.MenuRowHeight else rowHeight
    val inset = Modifier.padding(horizontal = RaftMetrics.PopupPadding)
    Column(
        modifier = modifier
            .let { base -> if (width != null) base.width(width) else base.fillMaxWidth() }
            .raftFloatingContentClickTarget()
            .raftLookOverlaySurface()
            .padding(vertical = RaftMetrics.PopupPadding)
    ) {
        headerContent?.let { header ->
            Box(modifier = inset.fillMaxWidth()) { header() }
            RaftLookMenuSeparator()
        }
        if (items.isEmpty()) {
            Row(
                modifier = inset
                    .fillMaxWidth()
                    .height(resolvedRowHeight)
                    .padding(horizontal = RaftMetrics.MenuItemPaddingHorizontal),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(MR.strings.common_no_actions_available),
                    style = RaftType.Body,
                    color = RaftColor.ForegroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            items.forEachIndexed { index, item ->
                if (index > 0 && item.id in dividerBeforeIds) {
                    RaftLookMenuSeparator()
                }
                Box(modifier = inset.fillMaxWidth()) {
                    RaftBrutalDropdownMenuRow(
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

/** raft-ui menu separator: a full-bleed 1dp `--line-hairline` hairline with `my-1.5`. */
@Composable
private fun RaftLookMenuSeparator() {
    Box(
        modifier = Modifier
            .padding(vertical = RaftMetrics.MenuSeparatorInset)
            .fillMaxWidth()
            .height(RaftAppStroke.Thin)
            .background(RaftColor.BorderHairline)
    )
}

/**
 * One dropdown row with the shared press feedback. Also used by [RaftBaseMenuOverlay]'s
 * raft popup so every raft menu row shares the BrandSoft press wash. [emphasized] (raft
 * only) sets the label bold, the Select / status-menu treatment of the selected row.
 */
@Composable
internal fun RaftBrutalDropdownMenuRow(
    item: RaftBrutalDropdownMenuItem,
    rowHeight: Dp,
    iconSize: Dp = RaftMetrics.MenuIconSize,
    emphasized: Boolean = false,
    onClick: () -> Unit
) {
    // Press feedback mirrors web MenuItem `hover:bg-palette-yellow/30` (artin,
    // #proj-uiux task #7): the row washes brand yellow while the finger is
    // down. Destructive rows keep their red wash.
    var pressed by remember(item.id) { mutableStateOf(false) }
    val gestureOwner =
        rememberRaftGestureOwner(
            surface = "brutal-dropdown-row",
            semanticIdentity = item.id,
            callbacks =
                RaftBrutalDropdownRowGestureCallbacks(
                    enabled = item.enabled,
                    onClick = onClick,
                    onPressedChange = { pressed = it }
                )
        )
    if (raftActive()) {
        RaftDropdownMenuRow(
            item = item,
            rowHeight = rowHeight,
            iconSize = iconSize,
            emphasized = emphasized,
            pressed = pressed,
            gestureModifier = Modifier.raftGesturePointerInput(gestureOwner, item.enabled) { owner ->
                var gesture: RaftGestureSession<RaftBrutalDropdownRowGestureCallbacks>? = null
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
        RaftColor.AccentRed.copy(alpha = 0.08f)
    } else if (pressed && item.enabled) {
        RaftColor.SoftSignal.copy(alpha = 0.30f) // react-parity: intentional bg-palette-yellow/30 wash
    } else {
        RaftColor.SurfaceWhite
    }
    val labelColor = if (item.destructive) {
        RaftColor.AccentRed
    } else if (!item.enabled) {
        RaftColor.TextBlack.copy(alpha = 0.30f)
    } else {
        RaftColor.TextBlack
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .background(rowBackground)
            .raftGesturePointerInput(gestureOwner, item.enabled) { owner ->
                var gesture: RaftGestureSession<RaftBrutalDropdownRowGestureCallbacks>? = null
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
                RaftLucideIcon(icon, modifier = Modifier.size(14.dp), color = if (item.destructive) "#FFD32F2F" else "#FF000000")
            }
        }
        Text(
            text = item.label,
            // react menu rows are text-sm font-medium (14px/20px): measured cap
            // heights in components.ui.menu-item.states are 14px-sized, and 12sp
            // bold rendered visibly smaller/narrower labels (task #370).
            style = RaftType.Caption.copy(
                fontSize = 14.sp,
                lineHeight = 20.sp
            ).withRaftFontWeight(FontWeight.Medium),
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        item.trailing?.let {
            Text(
                text = it,
                style = RaftType.Caption.copy(
                    fontSize = 12.sp,
                    lineHeight = 14.sp
                ),
                color = RaftColor.TextBlack.copy(alpha = 0.40f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        item.trailingIcon?.let {
            // Icon affordances are Lucide, never text glyphs (task #494) —
            // e.g. the selected-item check the raft-ui menu shows.
            RaftLucideIcon(
                name = it,
                color = RaftColor.TextBlackArgb,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * raft-ui menu item: min 36dp, control radius, px-2.5, 10dp gap, callout 14/20 medium;
 * pressed/highlighted = `--brand-soft`; 16dp icons in icon ink; destructive items take
 * the `--danger-strong` ink (no red wash); shortcut / trailing text is mono 12/16 hint.
 */
@Composable
private fun RaftDropdownMenuRow(
    item: RaftBrutalDropdownMenuItem,
    rowHeight: Dp,
    iconSize: Dp,
    emphasized: Boolean,
    pressed: Boolean,
    gestureModifier: Modifier
) {
    val ink =
        when {
            !item.enabled -> RaftColor.ForegroundDisabled
            item.destructive -> RaftColor.DangerStrong
            else -> RaftColor.TextBlack
        }
    val iconInk =
        when {
            !item.enabled -> RaftColor.ForegroundDisabled
            item.destructive -> RaftColor.DangerStrong
            else -> RaftColor.TextMuted
        }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .background(
                if (pressed && item.enabled) RaftColor.BrandSoft else Color.Transparent,
                RoundedCornerShape(RaftAppRadius.Control)
            )
            .then(gestureModifier)
            .padding(horizontal = RaftMetrics.MenuItemPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RaftMetrics.MenuItemGap)
    ) {
        item.icon?.let { icon ->
            RaftLucideIcon(icon, modifier = Modifier.size(iconSize), color = raftArgbHex(iconInk))
        }
        Text(
            text = item.label,
            // callout 14/20 medium = the shared content size.
            style = RaftType.Body.withRaftFontWeight(if (emphasized) FontWeight.Bold else FontWeight.Medium),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        item.trailing?.let {
            Text(
                text = it,
                style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                color = RaftColor.ForegroundHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        item.trailingIcon?.let {
            // `indicator`: ml-auto size-4 check in foreground ink.
            RaftLucideIcon(name = it, color = RaftColor.TextBlackArgb, modifier = Modifier.size(RaftMetrics.MenuIconSize))
        }
    }
}

private data class RaftBrutalDropdownRowGestureCallbacks(
    val enabled: Boolean,
    val onClick: () -> Unit,
    val onPressedChange: (Boolean) -> Unit
)

@Composable
private fun RaftBrutalDropdownMenuEmptyRow(rowHeight: Dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .background(RaftColor.SurfaceWhite)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(MR.strings.common_no_actions_available),
            style = RaftType.Caption.copy(
                fontSize = 11.sp,
                lineHeight = 14.sp
            ).withRaftFontWeight(FontWeight.Bold),
            color = RaftColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
