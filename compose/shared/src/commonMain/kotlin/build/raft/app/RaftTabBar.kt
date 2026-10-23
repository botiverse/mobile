package build.raft.app

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
import build.raft.app.components.RaftDesignSystemTheme
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import build.raft.designsystem.components.raftUnreadCountDescription
import build.raft.designsystem.components.raftUnreadCountText
import kotlinx.serialization.Serializable
import build.raft.app.components.RaftAttentionDot
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.compose.stringResource

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
        val bandPoints by nativeTabBar.bottomInsetPoints.collectAsState()
        return raftNativeHostTabBarContentInset(bandPoints.toFloat().dp, bottomSafeArea)
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
    Column(modifier = modifier.fillMaxWidth()) {
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

/** The attention dot on a tab icon, announced as unread activity / something new. */
@Composable
private fun RaftTabAttentionDot(key: String, modifier: Modifier = Modifier) {
    val description =
        if (key == "more") stringResource(MR.strings.tab_more_attention_dot) else stringResource(MR.strings.tab_activity_unread_dot)
    RaftAttentionDot(modifier = modifier.zIndex(1f).semantics { contentDescription = description })
}

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
        if (item.key == "more") stringResource(MR.strings.tab_more_attention_dot) else stringResource(MR.strings.tab_activity_unread_dot)
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
    // stdrc 2026-10-21: one edge bar for every Compose renderer (the iOS Liquid Glass and
    // Android floating docks are gone). The band is the bar plus the bottom safe area, all on
    // the card layer, so the bar meets the screen edge.
    Box(
        modifier = hostModifier
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
 * task #31: Web-aligned left navigation rail (LeftRail.tsx contract, task #30 diff list).
 * Full-height brand-yellow column: 64dp wide with a right black separator; a 62dp server
 * header on top (40dp avatar tile or first-letter fallback, bottom divider aligned with the
 * channel list header rule); icon-only 40dp visual buttons with 18dp icons at 6dp rhythm and
 * 8dp top/bottom breathing room; selection is a white 2dp-bordered tile with the classic 2dp
 * hard shadow — never a yellow cell or capsule. Main tabs occupy the top group; utility
 * entries pin to the bottom through a flexible spacer so the settings cluster never gets
 * pushed off by short heights. All entries map to real destinations — no filler buttons.
 */
internal data class RaftNavigationRailHeader(
    val key: String,
    val label: String,
    val unread: Boolean = false,
    val enabled: Boolean = true
)

@Composable
internal fun RaftNavigationRailTabBar(
    items: List<RaftBottomTabItemPayload>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    header: RaftNavigationRailHeader? = null,
    headerContent: (@Composable () -> Unit)? = null,
    bottomItems: List<RaftBottomTabItemPayload> = emptyList(),
    onBottomItemSelect: ((String) -> Unit)? = null,
    /** D1a: the current-server entry, last in the rail's foot; it opens the server menu. */
    bottomServerEntry: (@Composable () -> Unit)? = null,
    topSafeArea: Dp = 0.dp,
    bottomSafeArea: Dp = 0.dp
) {
    Row(modifier = modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .width(RaftNavigationRailWidth)
                .fillMaxHeight()
                // The rail is the yellow frame: brand yellow in light, the dark frame layer in dark.
                .background(if (RaftAppThemeState.isDark) RaftColor.Frame else RaftColor.BrandYellow)
        ) {
            if (header != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Page-header frame contract (raftTopPageHeaderFrameMetrics): total
                        // band = topSafeArea + 62dp with the 2dp divider as the LAST 2dp of
                        // that band. This Column is the content area — topSafeArea + (62 −
                        // stroke) — so the divider drawn after it ends at safeArea + 62dp,
                        // exactly the channel list header baseline; and the 40dp tile keeps
                        // ~60dp of room instead of clipping.
                        .height(
                            RaftNavigationRailHeaderHeight + topSafeArea - RaftAppStroke.Thin
                        )
                        .padding(top = topSafeArea),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (headerContent != null) {
                        headerContent()
                    } else {
                        RaftNavigationRailServerTile(header = header, onSelect = onSelect)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(RaftAppStroke.Thin)
                        .background(RaftColor.BorderSeam)
                )
            } else {
                Spacer(Modifier.height(RaftAppSpacing.Sm + topSafeArea))
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = RaftAppSpacing.Sm),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(RaftNavigationRailItemSpacing)
            ) {
                items.forEach { item ->
                    RaftNavigationRailTabItem(item = item, onSelect = onSelect)
                }
            }
            if (bottomItems.isNotEmpty() || bottomServerEntry != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = RaftAppSpacing.Sm, bottom = RaftAppSpacing.Sm + bottomSafeArea),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(RaftNavigationRailItemSpacing)
                ) {
                    bottomItems.forEach { item ->
                        RaftNavigationRailTabItem(item = item, onSelect = onBottomItemSelect ?: onSelect)
                    }
                    bottomServerEntry?.invoke()
                }
            }
        }
        // The rail's right-edge divider runs the full window height, through the status bar.
        // task #482 had started it below the top inset, which left the status-bar part of
        // this column transparent and showed a thin white line between the rail and the
        // list; artin (2026-10-03) chose the full-height line instead.
        Box(
            modifier = Modifier
                .width(RaftAppStroke.Thin)
                .fillMaxHeight()
                .background(RaftColor.BorderSeam)
        )
    }
}

@Composable
private fun RaftNavigationRailTabItem(
    item: RaftBottomTabItemPayload,
    onSelect: (String) -> Unit
) {
    val selected = item.selected
    // 48dp outer cell owns the tap (min 44dp touch target); the 40dp visual tile
    // inside keeps the web button size.
    Box(
        modifier =
            Modifier
                .size(RaftNavigationRailTouchSize)
                .clickable(enabled = item.enabled) { onSelect(item.key) }
                // task #31: icon-only buttons keep an accessible label.
                .semantics { contentDescription = item.label },
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.size(RaftNavigationRailButtonSize)) {
            // raft-ui ghost control (web desktop rail): no edge, no offset shadow; "you are here"
            // is the pressed-fill wash on the control radius.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        if (selected) RaftColor.FillPressed else Color.Transparent,
                        RoundedCornerShape(RaftAppRadius.Control),
                    ),
                contentAlignment = Alignment.Center
            ) {
                RaftLucideIcon(
                    name = item.iconName,
                    color = if (item.enabled) RaftColor.TextBlackArgb else RaftColor.TextMutedGrayArgb,
                    modifier = Modifier.size(RaftNavigationRailIconSize)
                )
            }
            RaftTabIconBadge(
                count = item.badgeCount,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp)
            )
            if (item.attentionDot) {
                RaftTabAttentionDot(key = item.key, modifier = Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp))
            }
        }
    }
}

/**
 * The unread count on a tab icon (Activity tab): raft-ui's pink count sticker, the same one the
 * Home sidebar rows wear. Nothing for 0; `99+` above 99.
 */
@Composable
private fun RaftTabIconBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    RaftUnreadCount(count = count, modifier = modifier.zIndex(1f))
}

@Composable
private fun RaftNavigationRailServerTile(
    header: RaftNavigationRailHeader,
    onSelect: (String) -> Unit
) {
    val tileSize = 40.dp
    Box(modifier = Modifier.size(tileSize + RaftAppStroke.Default * 2)) {
        Box(
            modifier = Modifier
                .size(tileSize)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(RaftAppRadius.Control))
                .background(RaftColor.SurfaceWhite)
                .border(RaftAppStroke.Thin, RaftColor.BorderHairline, RoundedCornerShape(RaftAppRadius.Control))
                .clickable(enabled = header.enabled) { onSelect(header.key) },
            contentAlignment = Alignment.Center
        ) {
            // Default tile shows the server's first letter; the real avatar surface is supplied
            // through headerContent (it needs the mounted avatar cache in the shell context).
            Text(
                text = header.label.take(1).uppercase(),
                style =
                    RaftType.Button.copy(
                        fontFamily = raftMonoFamilyForWeight(FontWeight.Bold),
                        fontSize = 17.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Normal
                    ),
                color = RaftColor.TextBlack,
                maxLines = 1
            )
        }
        if (header.unread) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .align(Alignment.TopEnd)
                    .background(RaftColor.AccentPink)
                    .border(RaftAppStroke.Default, RaftColor.StickerInk)
            )
        }
    }
}

private val RaftNavigationRailWidth = 64.dp
private val RaftNavigationRailHeaderHeight = 62.dp
private val RaftNavigationRailButtonSize = 40.dp
private val RaftNavigationRailTouchSize = 48.dp
private val RaftNavigationRailIconSize = 18.dp
private val RaftNavigationRailItemSpacing = 6.dp

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
                val description = stringResource(MR.strings.more_row_attention_dot)
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
