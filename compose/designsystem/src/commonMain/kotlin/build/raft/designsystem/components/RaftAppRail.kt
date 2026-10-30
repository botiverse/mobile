package build.raft.designsystem.components

import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.theme.RaftThemeDecorSlot
import build.raft.designsystem.theme.raftThemeDecor
import build.raft.designsystem.theme.raftThemeDecorShown
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette

// raft-ui AppRail (packages/ui/src/components/app-rail): the leftmost column of a wide layout,
// on the frame, the destinations at the top and the tools at the bottom. Mirrors the parts:
// - AppRailRoot + AppRailHeader + AppRailNav + AppRailFooter -> RaftAppRail (its slots)
// - AppRailItem (+ ItemIcon, ItemLabel, ItemAttention)       -> RaftAppRailItem
// - web LeftRail's sidebar toggle (AppShellSidebarTrigger)   -> RaftAppRailToggleItem
// - web LeftRail's RailTabButton tooltip (side="right")      -> the items' tooltip
// The app supplies the destinations, the header tile and what each one opens.

/** raft-ui AppRail metrics (icons-only variant, the one the product ships). */
object RaftAppRailDefaults {
    /** The rail, without its 1dp `line-seam` (raft-ui: 56px plus the seam). */
    val Width: Dp = 56.dp

    /** The header square; its seam lines up with the sidebar's and the panels' headers. */
    val HeaderHeight: Dp = 56.dp

    /** One destination's square (`size-10`). */
    val ItemSize: Dp = 40.dp

    /** Between items, in the nav and the footer (`gap-1.5`). */
    val ItemGap: Dp = 6.dp

    /**
     * From both ends of the rail, above the footer and over the first item without a header
     * (nav `first:pt-2`, `pb-2`; footer `pb-2`).
     */
    val Inset: Dp = 8.dp

    /** Under the header's seam to the first item (nav `pt-2.5`, when the header comes first). */
    val InsetUnderHeader: Dp = 10.dp

    /** The icon inside an item (`[&_svg]:size-5`). */
    val IconSize: Dp = 20.dp

    /**
     * The tallest window that takes [RaftAppRailMetrics.Compact] (raft-ui
     * `[@media(max-height:600px)]`, inclusive; web LeftRail "36×36 when the window is under 600 tall").
     */
    val CompactMaxWindowHeight: Dp = 600.dp

    /**
     * The metrics of the rail around the caller ([RaftAppRailMetrics.Regular] outside a
     * [RaftAppRail]): a tile the app puts in the header or the footer sizes itself by them.
     */
    val metrics: RaftAppRailMetrics
        @Composable get() = LocalRaftAppRailMetrics.current
}

/**
 * One size of the rail: [Regular], or [Compact] in a short window ([raftAppRailCompact]). The
 * icon and the gaps between items keep their size; the column, the header and the items shrink.
 */
@Immutable
data class RaftAppRailMetrics(
    /** The rail without its seam (root `w-[calc(56px+1px)]`, compact `w-[49px]`). */
    val width: Dp,
    /** The header square (`h-(--shell-header-height,56px)`, compact `h-12`). */
    val headerHeight: Dp,
    /** One item's square (`size-10`, compact `size-9`). */
    val itemSize: Dp,
    /** `[&_svg]:size-5` in both sizes. */
    val iconSize: Dp,
    /** `gap-1.5` in both sizes. */
    val itemGap: Dp,
    /** Over the first item under the header (`pt-2.5`, compact `pt-1.5`). */
    val navTopUnderHeader: Dp,
    /** Over the first item with no header (`first:pt-2`, compact `first:pt-1.5`). */
    val navTop: Dp,
    /** Under the last destination, above the footer (nav `pb-2` in both sizes). */
    val navBottom: Dp,
    /** Under the footer's last item (`pb-2`, compact `pb-1.5`). */
    val footerBottom: Dp,
) {
    companion object {
        val Regular =
            RaftAppRailMetrics(
                width = RaftAppRailDefaults.Width,
                headerHeight = RaftAppRailDefaults.HeaderHeight,
                itemSize = RaftAppRailDefaults.ItemSize,
                iconSize = RaftAppRailDefaults.IconSize,
                itemGap = RaftAppRailDefaults.ItemGap,
                navTopUnderHeader = RaftAppRailDefaults.InsetUnderHeader,
                navTop = RaftAppRailDefaults.Inset,
                navBottom = RaftAppRailDefaults.Inset,
                footerBottom = RaftAppRailDefaults.Inset,
            )

        val Compact =
            RaftAppRailMetrics(
                width = 48.dp,
                headerHeight = 48.dp,
                itemSize = 36.dp,
                iconSize = RaftAppRailDefaults.IconSize,
                itemGap = RaftAppRailDefaults.ItemGap,
                navTopUnderHeader = 6.dp,
                navTop = 6.dp,
                navBottom = RaftAppRailDefaults.Inset,
                footerBottom = 6.dp,
            )
    }
}

/**
 * Whether a window [windowHeight] tall takes the compact rail: raft-ui keys it on the viewport
 * (`[@media(max-height:600px)]`), not on the rail's own height, so the app does too. An unknown
 * (zero) height, before the window has a size, is not short.
 */
fun raftAppRailCompact(windowHeight: Dp): Boolean = windowHeight > 0.dp && windowHeight <= RaftAppRailDefaults.CompactMaxWindowHeight

/** [RaftAppRailMetrics.Compact] when [compact], else [RaftAppRailMetrics.Regular]. */
fun raftAppRailMetrics(compact: Boolean): RaftAppRailMetrics = if (compact) RaftAppRailMetrics.Compact else RaftAppRailMetrics.Regular

/** The metrics [RaftAppRail] lays out with, for its items. */
internal val LocalRaftAppRailMetrics = staticCompositionLocalOf { RaftAppRailMetrics.Regular }

/**
 * The physical side a rail item's tooltip opens on: away from the screen edge the rail stands
 * on, the start edge (web LeftRail `side="right"`), so the left in RTL.
 */
internal fun raftAppRailTooltipSide(layoutDirection: LayoutDirection): RaftTooltipSide =
    if (layoutDirection == LayoutDirection.Rtl) RaftTooltipSide.Left else RaftTooltipSide.Right

/**
 * One of the tools in the rail's footer ([RaftAppRail]'s `footerItems`), in the web's order:
 * collapse sidebar ([Toggle]), help, settings ([Action]s). [Custom] places anything else (the
 * bell or help with their popovers) in the list. [key] keeps an item's state when the list changes.
 */
@Stable
sealed interface RaftAppRailFooterItem {
    val key: Any

    /** A [RaftAppRailItem]: Settings, Help. */
    class Action(
        override val key: Any,
        val label: String,
        val onClick: () -> Unit,
        val icon: @Composable (ink: Color) -> Unit,
        val selected: Boolean = false,
        val enabled: Boolean = true,
        val attention: Boolean = false,
        val attentionDescription: String? = null,
        val shortcutLabel: String? = null,
        val showTooltip: Boolean = true,
    ) : RaftAppRailFooterItem

    /** A [RaftAppRailToggleItem]: Collapse / Expand sidebar. */
    class Toggle(
        override val key: Any,
        val label: String,
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
        val icon: @Composable (ink: Color, checked: Boolean) -> Unit,
        val enabled: Boolean = true,
        val shortcutLabel: String? = null,
        val stateDescription: String? = null,
    ) : RaftAppRailFooterItem

    /** Anything else in the footer's column, at this place in the list. */
    class Custom(
        override val key: Any,
        val content: @Composable () -> Unit,
    ) : RaftAppRailFooterItem
}

/**
 * raft-ui `AppRailRoot`: a [RaftAppRailDefaults.Width] column on `layer-frame` (the brand yellow
 * in light mode) with a full-height `line-seam` on its right. [header] (the workspace tile, in a
 * 56dp square under [topInset] with its own seam) is left out when something above the rail
 * holds the workspace. [content] is the destinations ([RaftAppRailItem]s, 6dp apart, 10dp under
 * the header's seam or 8dp from the top without one); the footer pins the tools to the bottom,
 * 8dp above [bottomInset]: [footer]'s content, then [footerItems] (collapse sidebar, help,
 * settings: [RaftAppRailFooterItem]). A decorated theme paints its pixel art under the
 * destinations, just above the footer ([RaftThemeDecorSlot.Rail], raft-ui `frame-decor-rail`).
 *
 * Compact ([RaftAppRailMetrics.Compact]): in a window at most 600dp tall the rail narrows to
 * 48dp, its header to 48dp and its items to 36dp, 6dp from the ends (raft-ui
 * `[@media(max-height:600px)]`). [compact] forces it either way (tests, previews); null decides
 * from the window's height ([raftAppRailCompact]). Items read the size from the rail.
 *
 * Each item names itself in a tooltip on the side away from the rail's edge (right; left in RTL).
 */
@Composable
fun RaftAppRail(
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    footerItems: List<RaftAppRailFooterItem> = emptyList(),
    compact: Boolean? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val metrics = raftAppRailMetrics(compact ?: raftAppRailCompact(windowHeight))
    val hasFooter = footer != null || footerItems.isNotEmpty()
    Row(modifier.fillMaxHeight()) {
        Column(
            Modifier
                .fillMaxHeight()
                .background(colors.layerFrame)
                // On the island's side in landscape the frame grows to the screen edge and the
                // items keep their 56dp column past the safe area.
                .raftSafeAreaHorizontalPadding(WindowInsetsSides.Start)
                .width(metrics.width),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CompositionLocalProvider(
                LocalRaftContentColor provides colors.foregroundOnFrame,
                LocalRaftAppRailMetrics provides metrics,
            ) {
                if (header != null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = topInset)
                            .height(metrics.headerHeight - RaftStroke.Hairline),
                        contentAlignment = Alignment.Center,
                    ) { header() }
                    Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam))
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = if (header == null) metrics.navTop + topInset else metrics.navTopUnderHeader),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(metrics.itemGap),
                ) {
                    content()
                    RaftAppRailDecor()
                }
                if (hasFooter) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = metrics.navBottom, bottom = metrics.footerBottom + bottomInset),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(metrics.itemGap),
                    ) {
                        footer?.invoke(this)
                        footerItems.forEach { item -> key(item.key) { RaftAppRailFooterItemView(item) } }
                    }
                }
            }
        }
        Box(Modifier.width(RaftStroke.Hairline).fillMaxHeight().background(colors.lineSeam))
    }
}

@Composable
private fun RaftAppRailFooterItemView(item: RaftAppRailFooterItem) {
    when (item) {
        is RaftAppRailFooterItem.Action ->
            RaftAppRailItem(
                label = item.label,
                selected = item.selected,
                onClick = item.onClick,
                icon = item.icon,
                enabled = item.enabled,
                attention = item.attention,
                attentionDescription = item.attentionDescription,
                shortcutLabel = item.shortcutLabel,
                showTooltip = item.showTooltip,
            )
        is RaftAppRailFooterItem.Toggle ->
            RaftAppRailToggleItem(
                label = item.label,
                checked = item.checked,
                onCheckedChange = item.onCheckedChange,
                icon = item.icon,
                enabled = item.enabled,
                shortcutLabel = item.shortcutLabel,
                stateDescription = item.stateDescription,
            )
        is RaftAppRailFooterItem.Custom -> item.content()
    }
}

/**
 * raft-ui AppRail nav's `after:` strip (`mt-auto h-28 min-h-0 w-12 shrink frame-decor-rail`): the
 * theme's 48x112 pixel art as the nav's last item, at its foot, in whatever room the
 * destinations leave (one item gap after the last), cut from the top when that is less than the
 * art. Nothing at all (no item, no gap) without decor to draw.
 */
@Composable
private fun ColumnScope.RaftAppRailDecor() {
    if (!raftThemeDecorShown(RaftTheme.themeId, RaftTheme.isDark, RaftTheme.decor, RaftThemeDecorSlot.Rail)) return
    Box(Modifier.fillMaxWidth().weight(1f).raftThemeDecor(RaftThemeDecorSlot.Rail))
}

/**
 * raft-ui `AppRailItem`: one destination, a 40dp square (36dp in a compact rail) on the control
 * radius holding its 20dp [icon] (drawn in the `ink` it is given). Selected, it is the sticker on
 * the frame: `layer-frame-active` (white in light mode, yellow in dark) with a 1.5dp
 * `line-frame-active` edge and the die-cut `sticker-edge` ring; a press or a mouse / trackpad
 * hover (faded over 100ms) lays `fill-frame-medium` over an unselected one. [attention] puts the
 * pink pixel (`Status sm`, 8dp) on the icon's top-right corner; the selected item never shows it.
 * Screen readers read [label], plus [attentionDescription] while the pixel shows ("Activity,
 * new"). A rail never shows a count (raft-ui Badge rules).
 *
 * Tooltip (web LeftRail `RailTabButton`): a mouse / trackpad hover or a touch long press shows
 * [label], with [shortcutLabel] ("⌘\") after it, in a [RaftTooltip] beside the item on the side
 * away from the rail's edge (right; left in RTL). The long press that opens it does not click.
 * [showTooltip] = false leaves it out (web: Help, whose hover opens its menu instead); a
 * disabled item shows none (web: `disabled:pointer-events-none`).
 *
 * The touch target reaches 44dp around the 40dp square, over the 6dp gaps.
 */
@Composable
fun RaftAppRailItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable (ink: Color) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    attention: Boolean = false,
    attentionDescription: String? = null,
    shortcutLabel: String? = null,
    showTooltip: Boolean = true,
) {
    val showsAttention = attention && !selected
    RaftAppRailItemBox(
        label = label,
        selected = selected,
        enabled = enabled,
        showsAttention = showsAttention,
        shortcutLabel = shortcutLabel,
        showTooltip = showTooltip,
        onClick = onClick,
        modifier = modifier,
        action = { source ->
            clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics {
                    this.selected = selected
                    contentDescription =
                        if (showsAttention && attentionDescription != null) "$label, $attentionDescription" else label
                }
        },
        icon = icon,
    )
}

/**
 * A rail tool that switches something on and off (web LeftRail's sidebar toggle, raft-ui
 * `AppShellSidebarTrigger` rendered as an `AppRailItem`): the [RaftAppRailItem] look, never the
 * selected sticker; the state shows in the icon, which [icon] draws for [checked] (PanelLeftClose
 * while the sidebar shows, PanelLeftOpen while it is hidden). A tap calls [onCheckedChange] with
 * the other state. Screen readers get a switch with its on / off state (as web's
 * `aria-expanded`), named [label]; [stateDescription] replaces the on / off reading ("Sidebar
 * shown"). Like web, [label] may follow the state ("Collapse sidebar" / "Expand sidebar"); it is
 * also the tooltip, with [shortcutLabel] ("⌘\") after it.
 */
@Composable
fun RaftAppRailToggleItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: @Composable (ink: Color, checked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shortcutLabel: String? = null,
    stateDescription: String? = null,
) {
    RaftAppRailItemBox(
        label = label,
        selected = false,
        enabled = enabled,
        showsAttention = false,
        shortcutLabel = shortcutLabel,
        showTooltip = true,
        onClick = { onCheckedChange(!checked) },
        modifier = modifier,
        action = { source ->
            toggleable(
                value = checked,
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ).semantics {
                contentDescription = label
                if (stateDescription != null) this.stateDescription = stateDescription
            }
        },
        icon = { ink -> icon(ink, checked) },
    )
}

/**
 * The item's face, shared by [RaftAppRailItem] and [RaftAppRailToggleItem]; [action] adds the
 * click or toggle and the semantics on the square, [onClick] serves the widened touch ring.
 */
@Composable
private fun RaftAppRailItemBox(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    showsAttention: Boolean,
    shortcutLabel: String?,
    showTooltip: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    action: Modifier.(MutableInteractionSource) -> Modifier,
    icon: @Composable (ink: Color) -> Unit,
) {
    val colors = RaftTheme.colors
    val metrics = LocalRaftAppRailMetrics.current
    val source = remember { MutableInteractionSource() }
    // `hover:bg-fill-frame-medium`, and the same on press; the selected sticker takes neither.
    // Remembered here, outside the tooltip branch, so flipping `enabled` keeps the state.
    val overlay =
        rememberRaftStateOverlay(source, RaftHoverTier.Medium, RaftPressSurface.Frame, enabled = enabled && !selected)
    val shape = RoundedCornerShape(RaftRadius.Control)
    val edge = colors.stickerEdge
    val ink =
        when {
            !enabled -> colors.foregroundDisabled
            selected -> colors.foregroundOnFrameActive
            else -> colors.foregroundOnFrame
        }
    val face: @Composable (Modifier) -> Unit = { outer ->
        Box(
            outer
                .size(metrics.itemSize)
                .raftTouchTarget(enabled, source, onClick)
                .then(
                    if (selected) {
                        Modifier.drawBehind {
                            // The die-cut ring outside the sticker: transparent in light, paper in dark.
                            if (edge.alpha > 0f) {
                                val w = RaftStroke.Control.toPx()
                                drawRoundRect(
                                    color = edge,
                                    topLeft = Offset(-w / 2f, -w / 2f),
                                    size = Size(size.width + w, size.height + w),
                                    cornerRadius = CornerRadius(RaftRadius.Control.toPx() + w / 2f),
                                    style = Stroke(w),
                                )
                            }
                        }
                    } else {
                        Modifier
                    },
                )
                .background(if (selected) colors.layerFrameActive else Color.Transparent, shape)
                .raftStateOverlay(overlay, shape)
                .border(RaftStroke.Control, if (selected) colors.lineFrameActive else Color.Transparent, shape)
                .raftPointerHover(source)
                .action(source),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(metrics.iconSize), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalRaftContentColor provides ink) { icon(ink) }
            }
            if (showsAttention) {
                // raft-ui AppRailItemAttention: the `attention` pixel, `-top-1 -right-1`.
                RaftStatusDot(
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp),
                    size = RaftStatusSize.Sm,
                    color = RaftUiPalette.attention,
                )
            }
        }
    }
    if (showTooltip && enabled) {
        // web LeftRail TOOLTIP_CONTENT_PROPS.right: beside the rail, toward the content.
        CompositionLocalProvider(LocalRaftTooltipSide provides raftAppRailTooltipSide(LocalLayoutDirection.current)) {
            RaftTooltip(text = label, modifier = modifier, shortcut = shortcutLabel) { face(Modifier) }
        }
    } else {
        face(modifier)
    }
}
