package build.raft.app

import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.app.resources.*
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import build.raft.app.markdown.traceMarkdownSection
import build.raft.app.markdown.traceMarkdownSectionNonComposable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import build.raft.designsystem.components.RaftUnreadCount
import build.raft.designsystem.components.RaftMobileNavDefaults
import build.raft.designsystem.components.RaftMobileNavItem
import build.raft.designsystem.components.RaftAppRailDefaults
import build.raft.designsystem.components.RaftAppRailItem
import build.raft.designsystem.components.RaftAppRail
import build.raft.designsystem.components.RaftConversationKindIcon
import build.raft.designsystem.components.RaftConversationKindGlyph
import build.raft.designsystem.components.RaftAppRailFooterItem
import build.raft.app.components.RaftDesignSystemTheme
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import build.raft.designsystem.components.raftUnreadCountDescription
import build.raft.designsystem.components.raftUnreadCountText
import kotlinx.serialization.Serializable
import build.raft.app.components.RaftAttentionDot

@Serializable
internal data class RaftTabItemPayload(
    val key: String = "",
    val title: String = "",
    val subtitle: String = "",
    val iconName: RaftLucideIconName = RaftLucideIconName.Settings,
    val value: String? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val section: String? = null,
    /** Attention dot after the title (something new behind this row). */
    val attentionDot: Boolean = false
)

@Serializable
internal data class RaftBottomTabItemPayload(
    val key: String = "",
    val label: String = "",
    val iconName: RaftLucideIconName = RaftLucideIconName.Circle,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    /** Unread count drawn as the pink raft-ui count on the tab icon; 0 draws nothing. */
    val badgeCount: Int = 0,
    /**
     * Attention dot on the tab icon (raft-design MobileNav / Badge: tab-bar icons carry a dot,
     * never a number). The shell never sets it on the selected tab.
     */
    val attentionDot: Boolean = false,
    /**
     * The count behind [attentionDot], for the host-drawn iOS system bar only: UIKit has no
     * small-dot badge (an empty `badgeValue` draws a full-size badge), so that bar shows this
     * count the native way, as Mail does. 0 = no count known: no badge there. The Compose bar
     * ignores it and keeps the dot.
     */
    val nativeBadgeCount: Int = 0,
    /**
     * The tab opens a panel instead of a page and is never selected (the current-server tab).
     * The iOS 26 system bar shows it as a separate round button beside the tab bar.
     */
    val opensPanel: Boolean = false,
    /** Screen-reader label when it differs from [label] ("Current server: Raft HQ"). */
    val accessibilityLabel: String? = null,
    /** The server's avatar wire value (an image URL), for the iOS 26 server button; null = none. */
    val avatarSource: String? = null,
    /** The server's initial, drawn on the app's ink tile when there is no avatar image. */
    val avatarFallbackLabel: String? = null,
)

internal enum class RaftTabBarPresentation {
    Edge,
    SafeAreaHost,
    FloatingDock,
    NavigationRail
}

internal enum class RaftAppTabBarRenderer {
    SharedCompose,
    IosLiquidGlass,
    AndroidFloating,
    // The page host draws a system tab bar (iOS UITabBarController) from the state published
    // through RaftNativeTabBarBridge; Compose draws no bar. Selected whenever the host
    // registered a bridge for the page, independent of the renderer page-data param.
    NativeHost
}

// The full-width edge bar (stdrc 2026-10-21); the bottom safe area is added below it.
private val RaftSharedBottomTabBarHeight = RaftBottomTabBarSpec.BarHeight
// UIKit's compact UITabBar height, used for the native host band until the host reports the
// laid-out bar.
private val RaftNativeHostTabBarFallbackHeight = 49.dp

@Composable
internal fun raftAppBottomTabBarContentInset(bottomSafeArea: Dp): Dp {
    val nativeTabBar = raftNativeTabBarBridge()
    if (nativeTabBar != null) {
        val band by nativeTabBar.band.collectAsState()
        return raftNativeHostTabBarContentInset(band, bottomSafeArea)
    }
    return raftAppBottomTabBarContentInset(
        renderer = raftAppTabBarRenderer(),
        bottomSafeArea = bottomSafeArea
    )
}

/**
 * Content inset under a host-drawn tab bar. [nativeBand] already spans the home-indicator
 * area, so the safe area is not added again; content keeps the shared Lg breathing room above
 * the bar. Before the host reports a band, a compact UIKit bar plus the safe area is assumed.
 */
internal fun raftNativeHostTabBarContentInset(band: RaftNativeTabBarBand?, bottomSafeArea: Dp): Dp =
    when {
        band == null -> raftNativeHostTabBarContentInset(0.dp, bottomSafeArea)
        band.edge == RaftNativeTabBarEdge.Bottom ->
            raftNativeHostTabBarContentInset(band.points.toFloat().dp, bottomSafeArea)
        // The bar sits on another edge (top or side, reserved by raftNativeTabBarEdgeInsets):
        // the bottom keeps only the safe area, like a page without a bar.
        else -> bottomSafeArea + RaftAppSpacing.Lg
    }

internal fun raftNativeHostTabBarContentInset(nativeBand: Dp, bottomSafeArea: Dp): Dp {
    val band = if (nativeBand > 0.dp) nativeBand else RaftNativeHostTabBarFallbackHeight + bottomSafeArea
    return band + RaftAppSpacing.Lg
}

internal fun raftAppBottomTabBarContentInset(
    renderer: RaftAppTabBarRenderer,
    bottomSafeArea: Dp
): Dp {
    return when (renderer) {
        RaftAppTabBarRenderer.NativeHost -> raftNativeHostTabBarContentInset(0.dp, bottomSafeArea)
        // stdrc 2026-10-21: every Compose renderer draws the same edge bar fixed to the bottom
        // edge (the floating docks are gone), so they all reserve the edge band.
        RaftAppTabBarRenderer.IosLiquidGlass,
        RaftAppTabBarRenderer.AndroidFloating,
        RaftAppTabBarRenderer.SharedCompose -> raftSharedBottomTabBarContentInset(bottomSafeArea)
    }
}

@Composable
internal fun raftAppBottomTabBarOverlaysContent(): Boolean {
    return when (raftAppTabBarRenderer()) {
        // The native host bar floats above the page: content runs edge to edge under it
        // (Liquid Glass on iOS 26+) and reserves the band through the content inset.
        RaftAppTabBarRenderer.NativeHost -> true
        // The Compose edge bar sits in its own band below the content.
        RaftAppTabBarRenderer.IosLiquidGlass,
        RaftAppTabBarRenderer.AndroidFloating,
        RaftAppTabBarRenderer.SharedCompose -> false
    }
}

internal fun raftSharedBottomTabBarHostHeight(bottomSafeArea: Dp): Dp =
    RaftSharedBottomTabBarHeight + bottomSafeArea

internal fun raftSharedBottomTabBarContentInset(bottomSafeArea: Dp): Dp =
    raftSharedBottomTabBarHostHeight(bottomSafeArea) + RaftAppSpacing.Lg

// raft-ui SidebarGroupLabel (the Web settings sidebar's group headings): `text-footnote
// font-bold normal-case tracking-normal`, i.e. RaftType.SectionLabel in source case.
internal val RaftVerticalTabSectionTextStyle: TextStyle
    get() =
        RaftType.SectionLabel.copy(
            color = RaftColor.TextBlack.copy(alpha = 0.4f)
        )
internal val RaftVerticalTabSectionBottomPadding = 4.dp

@Composable
internal fun RaftVerticalTabBar(
    items: List<RaftTabItemPayload>,
    showSelection: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    sectionStartPadding: Dp = 6.dp,
    itemStartPadding: Dp = 14.dp
) {
    // Sticker items on the page's canvas: the whole list stays out of the horizontal safe area.
    Column(modifier = modifier.fillMaxWidth().raftSafeAreaHorizontalPadding()) {
        var lastSection: String? = null
        items.forEachIndexed { index, item ->
            if (item.section != null && item.section != lastSection) {
                lastSection = item.section
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = sectionStartPadding,
                            top = if (index > 0) 12.dp else 0.dp,
                            bottom = RaftVerticalTabSectionBottomPadding
                        )
                ) {
                    Text(
                        item.section,
                        style = RaftVerticalTabSectionTextStyle,
                        color = RaftVerticalTabSectionTextStyle.color
                    )
                }
            }
            RaftVerticalTabItem(
                item = item,
                selected = showSelection && item.selected,
                drawTopBorder = index == 0,
                onSelect = onSelect,
                itemStartPadding = itemStartPadding
            )
        }
    }
}

@Composable
internal fun RaftBottomTabBar(
    items: List<RaftBottomTabItemPayload>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    serverTab: (@Composable (Modifier) -> Unit)? = null,
) {
    // #192 trace scope extension: bottom tab bar (Home / Tasks / etc.) render.
    // Tab switches are #191 hot path; this marker measures per-tab-item composition.
    traceMarkdownSection(RaftTabBarTraceNames.BOTTOM_TAB_RENDER, items.size) {
    // stdrc 2026-10-21, back to the approved edge bar (mockup A, pre-#3340): full width on the
    // card layer with a 1dp seam on top, fixed to the bottom edge. Every slot (the Search
    // action included) is a 56x32 pill slot holding a centred 23dp icon with the label 4dp
    // below. The selected pill is the brand sticker (2dp ink border, 9dp radius); counts and
    // dots sit on the slot's top-right corner, above the pill border, never over the icon.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(RaftBottomTabBarSpec.BarHeight)
            .background(RaftColor.Card)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(top = RaftAppStroke.Thin + RaftBottomTabBarSpec.TopPadding)
        ) {
            items.forEach { item ->
                if (serverTab != null && item.key == AppShellServerTabKey) {
                    // D1a: the current-server tab draws itself (avatar, name, long press).
                    serverTab(Modifier.weight(1f).fillMaxHeight())
                } else {
                    RaftBottomTabItem(
                        item = item,
                        onSelect = onSelect,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(RaftAppStroke.Thin)
                .background(RaftColor.BorderSeam)
                .align(Alignment.TopCenter)
        )
    }
    }
}

/**
 * Geometry of the approved bottom tab bar mockup (/tmp/activity-tab-mockups gen.js `.tabbar`).
 * The tab geometry is the design system's [RaftMobileNavDefaults]: every phone tab is drawn by
 * [build.raft.designsystem.components.RaftMobileNavItem], the current-server tab by
 * [build.raft.designsystem.components.RaftMobileNavServerPopoverItem].
 */
internal object RaftBottomTabBarSpec {
    val BarHeight = RaftMobileNavDefaults.BarHeight
    val TopPadding = RaftMobileNavDefaults.TopPadding
    val PillWidth = RaftMobileNavDefaults.PillWidth
    val PillHeight = RaftMobileNavDefaults.PillHeight
    val PillRadius = RaftMobileNavDefaults.PillRadius
    val PillBorder = RaftMobileNavDefaults.PillBorder
    val IconSize = RaftMobileNavDefaults.IconSize
    const val IconStroke = 2f
    val LabelGap = RaftMobileNavDefaults.LabelGap
    val LabelFontSize = RaftMobileNavDefaults.LabelFontSize
    val LabelLineHeight = RaftMobileNavDefaults.LabelLineHeight
    val LabelWeight = FontWeight.SemiBold
    val SelectedLabelWeight = FontWeight.Bold
    // Count badge, positioned from the pill slot's outer top-left corner.
    val BadgeOffsetX = 35.dp
    val BadgeOffsetY = (-3).dp
    val BadgeHeight = 18.dp
    val BadgeMinWidth = 20.dp
    val BadgeHorizontalPadding = 5.dp
    val BadgeRadius = 5.dp
    val BadgeBorder = 1.5.dp
    val BadgeFontSize = 11.sp
    // Attention dot, positioned like the count from the pill slot's top-left corner: it sits
    // on the pill's top-right corner (web MobileNav `top-1 right-2`).
    val DotOffsetX = RaftMobileNavDefaults.DotOffsetX
    val DotOffsetY = RaftMobileNavDefaults.DotOffsetY
}

/**
 * Optical size of a tab glyph relative to [RaftBottomTabBarSpec.IconSize] (stdrc 2026-10-21:
 * the Activity icon "floats"). Lucide `activity` is the only tab glyph whose ink spans the
 * whole 2..22 live area of the 24-unit viewBox (its pulse peaks touch y=2.2 and y=21.8, the
 * line runs x=2..22), while house, square-check-big, search and ellipsis keep to the 3..21
 * box, with their bottom edges on y=21. Drawn at the same size it reads ~11% larger and its
 * thin lower peak hangs below the others' baseline, so the mass of the glyph (the mid line)
 * looks lifted. Scaling it by 18/20 maps its ink onto the same 3..21 box: same size, same
 * top and bottom edges as the other tabs.
 */
internal fun raftBottomTabIconScale(icon: RaftLucideIconName): Float =
    if (icon == RaftLucideIconName.Activity) RaftBottomTabActivityIconScale else 1f

internal const val RaftBottomTabActivityIconScale = 18f / 20f

internal fun raftBottomTabLabelWeight(selected: Boolean): FontWeight =
    if (selected) RaftBottomTabBarSpec.SelectedLabelWeight else RaftBottomTabBarSpec.LabelWeight

@Composable
private fun RaftBottomTabItem(
    item: RaftBottomTabItemPayload,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // The design system's raft-ui MobileNavItem, as the server tab next to it: the 56x32 pill
    // slot (brand sticker when selected, the press wash on the pill, never over the whole
    // cell), the label 4dp under it, the attention pixel on the slot's top-right corner. The
    // app adds its glyph (the Activity one optically scaled) and its unread count.
    val attentionDescription =
        if (item.key == "more" || item.key == "settings") stringResource(Res.string.tab_more_attention_dot) else stringResource(Res.string.tab_activity_unread_dot)
    RaftDesignSystemTheme {
        RaftMobileNavItem(
            label = item.label,
            selected = item.selected,
            onClick = { onSelect(item.key) },
            icon = { ink ->
                RaftLucideIcon(
                    name = item.iconName,
                    color = raftArgbHex(ink),
                    strokeWidth = RaftBottomTabBarSpec.IconStroke,
                    modifier = Modifier.size(RaftBottomTabBarSpec.IconSize * raftBottomTabIconScale(item.iconName))
                )
            },
            modifier = modifier,
            attention = item.attentionDot,
            attentionDescription = attentionDescription,
            enabled = item.enabled,
            badge = {
                RaftBottomTabCountBadge(
                    count = item.badgeCount,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = RaftBottomTabBarSpec.BadgeOffsetX, y = RaftBottomTabBarSpec.BadgeOffsetY)
                        .wrapContentSize(align = Alignment.TopStart, unbounded = true)
                )
            },
        )
    }
}

/**
 * The tab's pink unread count (mockup `.badge.tb`): 18dp tall, min 20dp wide, 5dp side
 * padding inside a 1.5dp ink border, 5dp radius, 11sp bold. Nothing for 0; `99+` above 99.
 */
@Composable
private fun RaftBottomTabCountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val shape = RoundedCornerShape(RaftBottomTabBarSpec.BadgeRadius)
    val description = raftUnreadCountDescription(count)
    Box(
        modifier = modifier
            .zIndex(1f)
            .clearAndSetSemantics { contentDescription = description }
            .height(RaftBottomTabBarSpec.BadgeHeight)
            .widthIn(min = RaftBottomTabBarSpec.BadgeMinWidth)
            .background(RaftColor.Attention, shape)
            .border(RaftBottomTabBarSpec.BadgeBorder, RaftColor.StickerInk, shape)
            .padding(horizontal = RaftBottomTabBarSpec.BadgeBorder + RaftBottomTabBarSpec.BadgeHorizontalPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = raftUnreadCountText(count),
            style = RaftType.TabLabel
                .copy(fontSize = RaftBottomTabBarSpec.BadgeFontSize, lineHeight = RaftBottomTabBarSpec.BadgeFontSize)
                .withRaftFontWeight(FontWeight.Bold)
                .copy(color = RaftColor.StickerInk),
            color = RaftColor.StickerInk,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
internal fun RaftAppBottomTabBarHost(
    items: List<RaftBottomTabItemPayload>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomSafeArea: Dp = 0.dp,
    serverTab: (@Composable (Modifier) -> Unit)? = null,
) {
    val hostModifier = modifier.zIndex(RaftAppBottomTabBarHostZIndex)
    val nativeTabBar = raftNativeTabBarBridge()
    if (nativeTabBar != null) {
        // The host draws the system tab bar; Compose only publishes the tabs. Leaving
        // composition (the shell hides its bar) hides the native bar too.
        RaftNativeTabBarPublisher(bridge = nativeTabBar, items = items, onSelect = onSelect)
        return
    }
    if (raftAppTabBarRenderer() == RaftAppTabBarRenderer.NativeHost) return
    RaftSharedBottomTabBarBand(
        items = items,
        onSelect = onSelect,
        modifier = hostModifier,
        bottomSafeArea = bottomSafeArea,
        serverTab = serverTab,
    )
}

/**
 * The Compose edge bar's band (stdrc 2026-10-21: one edge bar for every Compose renderer, the
 * iOS Liquid Glass and Android floating docks are gone): the bar plus the bottom safe area, all
 * on the card layer, so the bar meets the screen edge.
 */
@Composable
internal fun RaftSharedBottomTabBarBand(
    items: List<RaftBottomTabItemPayload>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomSafeArea: Dp = 0.dp,
    serverTab: (@Composable (Modifier) -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(raftSharedBottomTabBarHostHeight(bottomSafeArea))
            .background(RaftColor.Card)
            .raftTouchBlocker()
    ) {
        RaftBottomTabBar(
            items = items,
            onSelect = onSelect,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            serverTab = serverTab,
        )
    }
}

@Composable
private fun raftAppTabBarRenderer(): RaftAppTabBarRenderer {
    if (raftNativeTabBarBridge() != null) return RaftAppTabBarRenderer.NativeHost
    val params = (LocalActivity.current.getPager() as Pager).pageData.params
    val explicitRenderer =
        params.optString("slockNativeTabBarRenderer")
            .ifBlank { params.optString("nativeTabBarRenderer") }
    return resolveRaftAppTabBarRenderer(
        explicitRenderer = explicitRenderer,
        platformDefaultRenderer = raftPlatformDefaultAppTabBarRenderer()
    )
}

internal fun resolveRaftAppTabBarRenderer(
    explicitRenderer: String?,
    platformDefaultRenderer: String?
): RaftAppTabBarRenderer {
    return when (explicitRenderer?.takeIf { it.isNotBlank() } ?: platformDefaultRenderer.orEmpty()) {
        "iosLiquidGlass", "iosNativeFloating" -> RaftAppTabBarRenderer.IosLiquidGlass
        "androidFloating", "androidNativeFloating" -> RaftAppTabBarRenderer.AndroidFloating
        else -> RaftAppTabBarRenderer.SharedCompose
    }
}

/**
 * The wide layout's navigation rail (web LeftRail with the top bar): the destinations at the top,
 * the tools (Settings) pinned to the bottom. The server and Search live in the top bar above it
 * ([RaftAppShellTopBar], artin task #8), so the rail has no header. Without the top bar (iPhone
 * Duo's inner screen, raft-design #859) the rail takes the server tile in its [header] and the
 * bell in [footerTools], before [bottomItems] (raft-ui AppRail without the top bar).
 * [sidebarToggle], where a list column sits beside the main area, heads the footer (web
 * SidebarToggleRailItem).
 */
@Composable
internal fun RaftNavigationRailTabBar(
    items: List<RaftBottomTabItemPayload>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomItems: List<RaftBottomTabItemPayload> = emptyList(),
    onBottomItemSelect: ((String) -> Unit)? = null,
    topSafeArea: Dp = 0.dp,
    bottomSafeArea: Dp = 0.dp,
    header: (@Composable () -> Unit)? = null,
    footerTools: (@Composable () -> Unit)? = null,
    sidebarToggle: RaftRailSidebarToggle? = null,
) {
    // The design system's raft-ui AppRail: 56dp plus the seam, 40dp items 6dp apart and 8dp from
    // both ends (48dp / 36dp in a short window), the selected sticker on the frame. The footer in
    // web LeftRail's order: the collapse `Toggle` ([sidebarToggle]), [footerTools] (the bell),
    // then the destinations (Help, Settings) as `Action`s.
    val select = onBottomItemSelect ?: onSelect
    val attentionDescriptions = raftNavRailAttentionDescriptions()
    val toggleItem = sidebarToggle?.let { raftRailSidebarToggleItem(it) }
    RaftDesignSystemTheme {
        RaftAppRail(
            modifier = modifier,
            topInset = topSafeArea,
            bottomInset = bottomSafeArea,
            header = header,
            footerItems =
                listOfNotNull(
                    toggleItem,
                    footerTools?.let { tools -> RaftAppRailFooterItem.Custom(key = "rail-footer-tools", content = tools) },
                ) + bottomItems.map { item ->
                    val glyph = raftNavGlyph(RaftNavPresentation.Rail, item.key)
                    RaftAppRailFooterItem.Action(
                        key = item.key,
                        label = item.label,
                        onClick = { select(item.key) },
                        icon = { ink -> RaftNavigationRailIcon(item, glyph, ink) },
                        selected = item.selected,
                        enabled = item.enabled,
                        attention = item.attentionDot || item.badgeCount > 0,
                        attentionDescription = attentionDescriptions(item.key),
                        // web LeftRail: Help's hover opens its menu, so it shows no tooltip.
                        showTooltip = RaftAppDestination.fromId(item.key)?.route != RaftNavRoute.Menu,
                    )
                },
        ) {
            items.forEach { item ->
                RaftNavigationRailTabItem(item = item, onSelect = onSelect)
            }
        }
    }
}

/**
 * The rail's Collapse / Expand Sidebar toggle: [hidden] is the column's remembered state,
 * [onHiddenChange] hides or shows it. [shortcutLabel] (⌘\ / Ctrl+\) follows the name in the tooltip.
 */
@Immutable
internal data class RaftRailSidebarToggle(
    val hidden: Boolean,
    val onHiddenChange: (Boolean) -> Unit,
    val shortcutLabel: String? = null,
)

/**
 * web SidebarToggleRailItem: the name follows the state (Collapse Sidebar while the column
 * shows, Expand Sidebar while it is hidden), as does the icon (PanelLeftClose / PanelLeftOpen).
 */
@Composable
private fun raftRailSidebarToggleItem(toggle: RaftRailSidebarToggle): RaftAppRailFooterItem.Toggle =
    RaftAppRailFooterItem.Toggle(
        key = "rail-sidebar-toggle",
        label = stringResource(if (toggle.hidden) Res.string.rail_expand_sidebar else Res.string.rail_collapse_sidebar),
        checked = !toggle.hidden,
        onCheckedChange = { shown -> toggle.onHiddenChange(!shown) },
        icon = { ink, shown ->
            RaftLucideIcon(
                name = if (shown) RaftLucideIconName.PanelLeftClose else RaftLucideIconName.PanelLeftOpen,
                color = raftArgbHex(ink),
                modifier = Modifier.size(RaftAppRailDefaults.IconSize),
            )
        },
        shortcutLabel = toggle.shortcutLabel,
    )

/** The screen-reader words for a rail item's dot, by its destination's badge source. */
@Composable
private fun raftNavRailAttentionDescriptions(): (String) -> String {
    val more = stringResource(Res.string.tab_more_attention_dot)
    val row = stringResource(Res.string.more_row_attention_dot)
    val activity = stringResource(Res.string.tab_activity_unread_dot)
    return { key ->
        when (RaftAppDestination.fromId(key)?.badge) {
            RaftNavBadgeSource.ServerPages, RaftNavBadgeSource.AppUpdate -> more
            RaftNavBadgeSource.ComputersAttention, RaftNavBadgeSource.AppsReview -> row
            else -> activity
        }
    }
}

@Composable
private fun RaftNavigationRailIcon(item: RaftBottomTabItemPayload, glyph: RaftConversationKindGlyph?, ink: Color) {
    if (glyph != null) {
        // web LeftRail: Chat is raft-ui `ChatIcon`, not a Lucide glyph.
        RaftConversationKindIcon(glyph, size = RaftAppRailDefaults.IconSize, color = ink)
    } else {
        RaftLucideIcon(
            name = item.iconName,
            color = raftArgbHex(ink),
            modifier = Modifier.size(RaftAppRailDefaults.IconSize)
        )
    }
}

@Composable
private fun RaftNavigationRailTabItem(
    item: RaftBottomTabItemPayload,
    onSelect: (String) -> Unit
) {
    // raft-ui AppRail: a rail icon shows only the attention pixel, never a count (Badge rules),
    // so an unread count reads as the dot here.
    val attentionDescription = raftNavRailAttentionDescriptions()(item.key)
    val glyph = raftNavGlyph(RaftNavPresentation.Rail, item.key)
    RaftAppRailItem(
        label = item.label,
        selected = item.selected,
        onClick = { onSelect(item.key) },
        icon = { ink -> RaftNavigationRailIcon(item, glyph, ink) },
        enabled = item.enabled,
        attention = item.attentionDot || item.badgeCount > 0,
        attentionDescription = attentionDescription,
    )
}

@Composable
private fun RaftVerticalTabItem(
    item: RaftTabItemPayload,
    selected: Boolean,
    drawTopBorder: Boolean,
    onSelect: (String) -> Unit,
    itemStartPadding: Dp
) {
    val textColor = if (item.enabled) (if (selected) RaftColor.StickerInk else RaftColor.TextBlack) else RaftColor.TextMutedGray
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 0.dp, vertical = 2.dp)
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(x = RaftAppStroke.Default, y = RaftAppStroke.Default)
                    .background(RaftColor.StickerInk)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(if (selected) RaftColor.AccentPink else Color.Transparent)
                // Same as the rail item: a removed border modifier does not clear the native border.
                .border(RaftAppStroke.Default, if (selected) RaftColor.StickerInk else Color.Transparent)
                .clickable(enabled = item.enabled) { onSelect(item.key) }
                .padding(start = itemStartPadding, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RaftLucideIcon(
                name = item.iconName,
                color = if (item.enabled) raftLegacyIconInk(onSticker = selected) else RaftColor.TextMutedGrayArgb,
                modifier = Modifier.size(14.dp)
            )
            Text(
                item.title,
                style = RaftType.Button.copy(color = textColor),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (item.attentionDot) {
                val description = stringResource(Res.string.more_row_attention_dot)
                RaftAttentionDot(modifier = Modifier.semantics { contentDescription = description })
            }
        }
    }
}

/**
 * Swallows touches that would otherwise fall through the tab bar to the page under it, without
 * any press feedback (a bare `clickable` greyed the whole bar while a finger rested on it).
 */
@Composable
private fun Modifier.raftTouchBlocker(): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
