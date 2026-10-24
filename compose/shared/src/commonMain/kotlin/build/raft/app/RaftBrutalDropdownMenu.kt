package build.raft.app

import build.raft.app.components.RaftIconSpinner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.compose.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import build.raft.app.components.RaftMetrics
import build.raft.app.components.RaftSlotIcon
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuSeparator
import build.raft.designsystem.components.RaftMenuSurface
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

/**
 * Single-action menu item rendered by [RaftBrutalDropdownMenu]. `id` is the stable
 * identity returned via `onSelect`. `destructive` marks a "Delete" style action; it is drawn
 * like any other item (raft-ui: no red menu items, the confirm dialog carries the deletion).
 */
@Deprecated(
    "Legacy RaftBrutal layer: use build.raft.designsystem.components.RaftMenuItem. New uses fail scripts/check_raftbrutal_usage.sh; see docs/raftbrutal-migration.md.",
    level = DeprecationLevel.WARNING,
)
internal data class RaftBrutalDropdownMenuItem(
    val id: String,
    val label: String,
    val icon: RaftLucideIconName? = null,
    val trailing: String? = null,
    val trailingIcon: RaftLucideIconName? = null,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    /** In progress: a spinner in place of [icon]. */
    val loading: Boolean = false
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
@Deprecated(
    "Legacy RaftBrutal layer: use build.raft.designsystem.components.RaftDropdownMenu. New uses fail scripts/check_raftbrutal_usage.sh; see docs/raftbrutal-migration.md.",
    level = DeprecationLevel.WARNING,
)
internal fun RaftBrutalDropdownMenu(
    anchor: RaftPopoverAnchor,
    items: List<RaftBrutalDropdownMenuItem>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    widthDp: Float = RaftBrutalDropdownMenuWidthValue,
    horizontalOffsetDp: Float = 0f,
    // null = the default: raft-ui DropdownMenuContent's `sideOffset ?? 6`.
    verticalOffsetDp: Float? = null,
    horizontalAutoFlip: Boolean = true,
    verticalPlacement: RaftFloatingVerticalPlacement = RaftFloatingVerticalPlacement.Auto
) {
    val density = LocalDensity.current
    // raft-ui DropdownMenu content is `min-w-60`.
    val popoverWidth = raftMenuWidth(widthDp.dp)
    val rowHeight = RaftBrutalDropdownMenuRowHeightValue.dp
    // Card height = max(n * rowHeight, rowHeight) for a visible "no actions" placeholder
    // when items is empty. RaftAnchoredPopover only needs an upper bound
    // for vertical auto-flip; the actual content size is set by the Column inside, so this
    // is purely advisory. We always reserve at least one row so an empty items list still
    // produces a visible card (e.g. a non-admin user clicking the trigger still sees a
    // "No actions available" panel rather than a 4px-tall sliver).
    val effectiveRowCount = items.size.coerceAtLeast(1)
    val contentSize =
        with(density) {
            // raft: 36dp rows in a p-1.5 popup; the float shadow takes no layout space.
            Size(
                width = popoverWidth.toPx(),
                height =
                    (effectiveRowCount * RaftMetrics.MenuRowHeight.value + RaftMetrics.PopupPadding.value * 2)
                        .dp
                        .toPx()
            )
        }
    val horizontalOffsetPx = with(density) { horizontalOffsetDp.dp.toPx() }
    val resolvedVerticalOffsetDp = verticalOffsetDp ?: RaftMetrics.MenuSideOffset.value
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
            onDismiss = onDismiss
        )
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
@Deprecated(
    "Legacy RaftBrutal layer: use build.raft.designsystem.components.RaftMenuSurface. New uses fail scripts/check_raftbrutal_usage.sh; see docs/raftbrutal-migration.md.",
    level = DeprecationLevel.WARNING,
)
internal fun RaftBrutalDropdownMenuSurface(
    items: List<RaftBrutalDropdownMenuItem>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = RaftBrutalDropdownMenuWidthValue.dp,
    rowHeight: Dp = RaftBrutalDropdownMenuRowHeightValue.dp,
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
}

/** raft-ui menu popup width: the caller's width, never under `min-w-60`. */
internal fun raftMenuWidth(width: Dp): Dp =
    if (width < RaftMetrics.MenuMinWidth) RaftMetrics.MenuMinWidth else width

/**
 * The design-system menu popup ([RaftMenuSurface]: container radius, popover layer, seam
 * ring, float shadow, `p-1.5`, `min-w-60`) with a row per item ([RaftBrutalDropdownMenuRow]).
 * Only explicit separators are drawn ([RaftMenuSeparator]); a header (the message menu's
 * reaction bar) sits above one. An empty list shows one disabled "No actions" row.
 */
@Suppress("UNUSED_PARAMETER")
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
    RaftMenuSurface(
        modifier = modifier.raftFloatingContentClickTarget(),
        width = width
    ) {
        headerContent?.let { header ->
            Box(modifier = Modifier.fillMaxWidth()) { header() }
            RaftMenuSeparator()
        }
        if (items.isEmpty()) {
            RaftMenuItem(
                text = stringResource(MR.strings.common_no_actions_available),
                onClick = {},
                enabled = false
            )
        } else {
            items.forEachIndexed { index, item ->
                if (index > 0 && item.id in dividerBeforeIds) {
                    RaftMenuSeparator()
                }
                RaftBrutalDropdownMenuRow(
                    item = item,
                    rowHeight = RaftMetrics.MenuRowHeight,
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

/**
 * One dropdown row with the shared press feedback. Also used by [RaftBaseMenuOverlay]'s
 * raft popup so every raft menu row shares the BrandSoft press wash. [emphasized] (raft
 * only) sets the label bold, the Select / status-menu treatment of the selected row.
 */
@Composable
@Deprecated(
    "Legacy RaftBrutal layer: use build.raft.designsystem.components.RaftMenuItem. New uses fail scripts/check_raftbrutal_usage.sh; see docs/raftbrutal-migration.md.",
    level = DeprecationLevel.WARNING,
)
internal fun RaftBrutalDropdownMenuRow(
    item: RaftBrutalDropdownMenuItem,
    rowHeight: Dp,
    iconSize: Dp = RaftMetrics.MenuIconSize,
    emphasized: Boolean = false,
    onClick: () -> Unit
) {
    // The design-system menu item (brand-soft highlight, bold + check for the current value,
    // mono shortcut). It has no trailing icon other than the check, so those rows keep the
    // raft-look row below.
    if (item.trailingIcon == null || item.trailingIcon == RaftLucideIconName.Check) {
        RaftMenuItem(
            text = item.label,
            onClick = onClick,
            enabled = item.enabled,
            checked = emphasized || item.trailingIcon == RaftLucideIconName.Check,
            leadingIcon =
                if (item.loading) {
                    { RaftIconSpinner() }
                } else {
                    item.icon?.let { icon -> { RaftSlotIcon(icon) } }
                },
            shortcut = item.trailing
        )
        return
    }
    // Press feedback mirrors web MenuItem `hover:bg-palette-yellow/30` (artin,
    // #proj-uiux task #7): the row washes brand yellow while the finger is
    // down.
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
}

/**
 * raft-ui menu item: min 36dp, control radius, px-2.5, 10dp gap, callout 14/20 medium;
 * pressed/highlighted = `--brand-soft`; 16dp icons in icon ink; shortcut / trailing text is
 * mono 12/16 hint.
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
            else -> RaftColor.TextBlack
        }
    val iconInk =
        when {
            !item.enabled -> RaftColor.ForegroundDisabled
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
        if (item.loading) {
            RaftIconSpinner(size = iconSize, color = iconInk)
        } else {
            item.icon?.let { icon ->
                RaftLucideIcon(icon, modifier = Modifier.size(iconSize), color = raftArgbHex(iconInk))
            }
        }
        Text(
            text = item.label,
            // raft-ui MenuItem: `text-callout` 14/20 at 500 (web measures 14px rows; the
            // 16/24 content size is for message text, not menu items).
            style = RaftType.Button.withRaftFontWeight(if (emphasized) FontWeight.Bold else FontWeight.Medium),
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
