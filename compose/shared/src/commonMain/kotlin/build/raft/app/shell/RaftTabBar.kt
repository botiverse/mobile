package build.raft.app.shell

import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.raftSlot
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.app.resources.*
import build.raft.app.settings.iconName
import build.raft.app.thread.RaftConnectedAppLogo
import build.raft.app.thread.icon
import build.raft.app.thread.key
import build.raft.app.withRaftFontWeight
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import build.raft.app.markdown.traceMarkdownSection
import build.raft.app.markdown.traceMarkdownSectionNonComposable
import build.raft.app.members.iconName
import build.raft.app.optString
import build.raft.app.raftArgbHex
import build.raft.app.raftLegacyIconInk
import build.raft.app.raftLogInfo
import build.raft.app.raftPlatformDefaultAppTabBarRenderer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
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
import androidx.compose.foundation.layout.requiredSize
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
import build.raft.app.HomeAssistantFace
import build.raft.app.RaftAppSpacing
import build.raft.app.RaftAppStroke
import build.raft.app.RaftColor
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftType
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import build.raft.designsystem.components.RaftUnreadCount
import build.raft.designsystem.components.RaftToastAvoidBottom
import build.raft.designsystem.components.RaftMobileNavDefaults
import build.raft.designsystem.components.RaftMobileNavItem
import build.raft.designsystem.components.RaftAppRailDefaults
import build.raft.designsystem.components.RaftAppRailItem
import build.raft.designsystem.components.RaftAppRail
import build.raft.designsystem.components.RaftConversationKindIcon
import build.raft.designsystem.components.RaftConversationKindGlyph
import build.raft.designsystem.components.RaftAppRailFooterItem
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.get
import build.raft.app.iconName
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import build.raft.designsystem.components.raftUnreadCountDescription
import build.raft.designsystem.components.raftUnreadCountText
import kotlinx.serialization.Serializable
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftStatusVariant
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.components.RaftPressSurface
import androidx.compose.ui.semantics.Role

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
    val attentionDot: Boolean = false,
    /** The row leaves Raft for another app: a trailing ↗ (raft-ui 0.26 #758). */
    val opensExternally: Boolean = false
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
    /** Whether this rail item represents an installed App place (`app:<clientId>`, slock #16684). */
    val isAppPlace: Boolean = false,
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

/**
 * Physical height (from the window's bottom edge, including the home-indicator safe area) of the
 * currently visible bottom tab bar, or `0.dp` when the bar is hidden or on another edge (top/side
 * on iPad). Mirrors [RaftNativeTabBarToastAvoidance] and [raftSharedBottomTabBarHostHeight] so
 * overlays outside the shell's own toast layer (such as the screenshot feedback prompt pill) stay
 * clear of the iOS 26 Liquid Glass floating `UITabBar` and Android bottom navigation bar without
 * double-counting the bottom safe area.
 */
internal fun raftNativeHostActiveBottomTabBarBandHeight(
    band: RaftNativeTabBarBand?,
    visible: Boolean,
    bottomSafeArea: Dp,
): Dp =
    when {
        !visible -> 0.dp
        band == null -> RaftNativeHostTabBarFallbackHeight + bottomSafeArea
        band.edge == RaftNativeTabBarEdge.Bottom ->
            if (band.points > 0.0) band.points.toFloat().dp else RaftNativeHostTabBarFallbackHeight + bottomSafeArea
        else -> 0.dp
    }

@Composable
internal fun raftAppActiveBottomTabBarBandHeight(bottomSafeArea: Dp): Dp {
    val nativeTabBar = raftNativeTabBarBridge()
    if (nativeTabBar != null) {
        val band by nativeTabBar.band.collectAsState()
        val state by nativeTabBar.state.collectAsState()
        return raftNativeHostActiveBottomTabBarBandHeight(
            band = band,
            visible = state.visible,
            bottomSafeArea = bottomSafeArea,
        )
    }
    return when (raftAppTabBarRenderer()) {
        RaftAppTabBarRenderer.NativeHost -> RaftNativeHostTabBarFallbackHeight + bottomSafeArea
        RaftAppTabBarRenderer.IosLiquidGlass,
        RaftAppTabBarRenderer.AndroidFloating,
        RaftAppTabBarRenderer.SharedCompose -> raftSharedBottomTabBarHostHeight(bottomSafeArea)
    }
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

/**
 * The full-width edge bar's band (stdrc 2026-10-21) over [bottomSafeArea], as raft-ui MobileNav
 * draws it (mobile-nav.mjs): the 52dp bar and its bottom padding, the safe area or at least 8dp
 * ([RaftMobileNavDefaults.height]). 60dp with nothing under it, 76dp over Android's 24dp gesture
 * handle, 86dp over an iPhone's 34pt home indicator.
 */
internal fun raftSharedBottomTabBarHostHeight(bottomSafeArea: Dp): Dp =
    RaftMobileNavDefaults.height(bottomSafeArea)

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
    onLongPress: ((String) -> Unit)? = null,
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
            // raft-ui `mobile-nav-root`, for the visual-testing component comparison.
            .raftSlot("mobile-nav-root")
            .fillMaxWidth()
            .height(RaftBottomTabBarSpec.BarHeight)
            .background(RaftColor.Card)
    ) {
        val currentOnSelect by rememberUpdatedState(onSelect)
        val currentOnLongPress by rememberUpdatedState(onLongPress)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .raftSafeAreaHorizontalPadding()
                .padding(horizontal = RaftMobileNavDefaults.SidePadding)
        ) {
            items.forEach { item ->
                val currentEnabled by rememberUpdatedState(item.enabled)
                val itemLongPress = if (item.key == AppShellServerTabKey) null else onLongPress
                // Expand each 45dp tab's effective tap area vertically across the bar's top 7dp
                // (seam + TopPadding) and bottom 8dp (MinBottomPadding) while keeping the inner
                // RaftMobileNavItem / server tab at its exact 45dp visual & semantics box. Taps
                // landing inside the inner item are consumed there first so this never double-fires;
                // taps in the system gesture safe area below 60dp stay guarded by raftTouchBlocker.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pointerInput(item.key, itemLongPress != null) {
                            // raw-click: invisible vertical padding tap-forgiveness wrapper around the 45dp tab; inner DS item owns press/hover/a11y
                            detectTapGestures(
                                onLongPress =
                                    if (itemLongPress != null) {
                                        {
                                            if (currentEnabled) {
                                                currentOnLongPress?.invoke(item.key)
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                onTap = {
                                    if (currentEnabled) {
                                        currentOnSelect(item.key)
                                    }
                                },
                            )
                        }
                        .padding(
                            top = RaftAppStroke.Thin + RaftBottomTabBarSpec.TopPadding,
                            bottom = RaftMobileNavDefaults.MinBottomPadding,
                        )
                ) {
                    if (serverTab != null && item.key == AppShellServerTabKey) {
                        // D1a: the current-server tab draws itself (avatar, name, long press).
                        serverTab(Modifier.fillMaxSize())
                    } else {
                        RaftBottomTabItem(
                            item = item,
                            onSelect = onSelect,
                            onLongPress = itemLongPress,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
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
    /**
     * The bar with nothing under it: its 52dp and the least bottom padding, 8dp. Over a bottom
     * safe area [RaftSharedBottomTabBarBand] adds the rest of the padding below it.
     */
    val BarHeight = RaftMobileNavDefaults.height()
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
    // Count badge, positioned from the pill slot's outer top-left corner; on the 30dp pill it
    // straddles the top edge 4dp up, its lower half clear of the 22dp icon.
    val BadgeOffsetX = 35.dp
    val BadgeOffsetY = (-4).dp
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

internal fun raftBottomTabLabelWeight(selected: Boolean): FontWeight =
    if (selected) RaftBottomTabBarSpec.SelectedLabelWeight else RaftBottomTabBarSpec.LabelWeight

@Composable
private fun RaftBottomTabItem(
    item: RaftBottomTabItemPayload,
    onSelect: (String) -> Unit,
    onLongPress: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // The design system's raft-ui MobileNavItem, as the server tab next to it: the 56x30 pill
    // slot (brand sticker when selected, the press wash on the pill, never over the whole
    // cell), the label 3dp under it, the attention pixel on the slot's top-right corner. The
    // app adds its glyph, every one at 22dp as the web draws them, and its unread count.
    val attentionDescription =
        if (item.key == "more" || item.key == "settings") stringResource(Res.string.tab_more_attention_dot) else stringResource(Res.string.tab_activity_unread_dot)
    val longPressLabel = if (onLongPress != null) stringResource(Res.string.rail_places_edit) else null
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
                    modifier = Modifier.size(RaftBottomTabBarSpec.IconSize)
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
            onLongClick = onLongPress?.let { handler -> { handler(item.key) } },
            onLongClickLabel = longPressLabel,
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
    onLongPress: ((String) -> Unit)? = null,
) {
    val hostModifier = modifier.zIndex(RaftAppBottomTabBarHostZIndex)
    val nativeTabBar = raftNativeTabBarBridge()
    if (nativeTabBar != null) {
        // The host draws the system tab bar; Compose only publishes the tabs. Leaving
        // composition (the shell hides its bar) hides the native bar too.
        RaftNativeTabBarPublisher(bridge = nativeTabBar, items = items, onSelect = onSelect)
        RaftNativeTabBarToastAvoidance(nativeTabBar)
        return
    }
    if (raftAppTabBarRenderer() == RaftAppTabBarRenderer.NativeHost) return
    // raft-ui: on a top-level page the window's toasts sit just above the tab bar. Report the bar's
    // fixed resting band height rather than measuring the bar while AnimatedVisibility slides it
    // back in after the keyboard closes (which would push a visible toast up mid-slide).
    RaftToastAvoidBottom(height = raftSharedBottomTabBarHostHeight(bottomSafeArea))
    RaftSharedBottomTabBarBand(
        items = items,
        onSelect = onSelect,
        modifier = hostModifier,
        bottomSafeArea = bottomSafeArea,
        serverTab = serverTab,
        onLongPress = onLongPress,
    )
}

/** raft-ui: the window's toasts sit just above a host-drawn bar on the bottom edge while it shows. */
@Composable
private fun RaftNativeTabBarToastAvoidance(bridge: RaftNativeTabBarBridge) {
    val band by bridge.band.collectAsState()
    val state by bridge.state.collectAsState()
    val bottomBand = band?.takeIf { it.edge == RaftNativeTabBarEdge.Bottom && state.visible }
    RaftToastAvoidBottom(
        height = (bottomBand?.points ?: 0.0).toFloat().dp,
        enabled = bottomBand != null,
    )
}

/**
 * The Compose edge bar's band (stdrc 2026-10-21: one edge bar for every Compose renderer, the
 * iOS Liquid Glass and Android floating docks are gone): the bar and its bottom padding over the
 * bottom safe area ([raftSharedBottomTabBarHostHeight]: the safe area, at least 8dp, as raft-ui
 * MobileNav pads it), all on the card layer, so the bar meets the screen edge. The tabs keep
 * their place at the top and take taps down to the bar's least 8dp padding, as with no safe area.
 */
@Composable
internal fun RaftSharedBottomTabBarBand(
    items: List<RaftBottomTabItemPayload>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomSafeArea: Dp = 0.dp,
    serverTab: (@Composable (Modifier) -> Unit)? = null,
    onLongPress: ((String) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val bandHeight = raftSharedBottomTabBarHostHeight(bottomSafeArea)
    val selectedTabKey = items.firstOrNull { it.selected }?.key.orEmpty()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(bandHeight)
            .background(RaftColor.Card)
            .raftTouchBlocker { offset ->
                raftLogInfo(
                    "SlockKuikly",
                    "app_shell_bottom_tab_bar_unhandled_tap " +
                        "xDp=${(offset.x / density.density).roundToInt()} " +
                        "yDp=${(offset.y / density.density).roundToInt()} " +
                        "barHeightDp=${RaftBottomTabBarSpec.BarHeight.value.roundToInt()} " +
                        "bandHeightDp=${bandHeight.value.roundToInt()} " +
                        "bottomSafeAreaDp=${bottomSafeArea.value.roundToInt()} " +
                        "selectedTab=$selectedTabKey",
                )
            }
    ) {
        RaftBottomTabBar(
            items = items,
            onSelect = onSelect,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            serverTab = serverTab,
            onLongPress = onLongPress,
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
 * SidebarToggleRailItem). With [reorder] the destinations can be dragged into the person's own
 * order (web SortableRailTabs, [RaftRailReorderableTabs]); [items] come in that order.
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
    reorder: RaftRailTabReorder? = null,
    desktopPlaces: RaftDesktopRailPlacesHost? = null,
) {
    // The design system's raft-ui AppRail: 56dp plus the seam, 40dp items 6dp apart and 8dp from
    // both ends (48dp / 36dp in a short window), the selected sticker on the frame. The footer in
    // web LeftRail's order: the collapse `Toggle` ([sidebarToggle]), [footerTools] (the bell),
    // then the destinations (Help, Settings) as `Action`s.
    val select = onBottomItemSelect ?: onSelect
    val attentionDescriptions = raftNavRailAttentionDescriptions()
    val toggleItem = sidebarToggle?.let { raftRailSidebarToggleItem(it) }
    var railHeightPx by remember { mutableStateOf(0f) }
    val footerCount =
        (if (toggleItem != null) 1 else 0) +
            (if (footerTools != null) 1 else 0) +
            bottomItems.size
    RaftDesignSystemTheme {
        RaftAppRail(
            modifier =
                if (desktopPlaces != null) {
                    modifier.onGloballyPositioned { railHeightPx = it.size.height.toFloat() }
                } else {
                    modifier
                },
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
            if (desktopPlaces != null) {
                val metrics = RaftAppRailDefaults.metrics
                val density = LocalDensity.current
                val availableNavHeightPx =
                    if (railHeightPx <= 0f) {
                        0f
                    } else {
                        with(density) {
                            val headerPx = if (header != null) (metrics.headerHeight + topSafeArea).toPx() else 0f
                            val navTopPx = if (header == null) (metrics.navTop + topSafeArea).toPx() else metrics.navTopUnderHeader.toPx()
                            val footerPx =
                                if (footerCount > 0) {
                                    (metrics.navBottom + metrics.footerBottom + bottomSafeArea).toPx() +
                                        footerCount * metrics.itemSize.toPx() +
                                        (footerCount - 1).coerceAtLeast(0) * metrics.itemGap.toPx()
                                } else {
                                    0f
                                }
                            (railHeightPx - headerPx - navTopPx - footerPx).coerceAtLeast(0f)
                        }
                    }
                RaftDesktopRailPlacesNav(host = desktopPlaces, availableNavHeightPx = availableNavHeightPx)
            } else if (reorder != null) {
                RaftRailReorderableTabs(items = items, onSelect = onSelect, reorder = reorder)
            } else {
                items.forEach { item ->
                    RaftNavigationRailTabItem(item = item, onSelect = onSelect)
                }
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

/** Web `OttieRailAvatar` (default size): Ottie's face on the rail, 28dp. */
internal val RaftRailOttieFaceSize = 28.dp

/** The screen-reader words for a rail item's dot, by its destination's badge source. */
@Composable
private fun raftNavRailAttentionDescriptions(): (String) -> String {
    val more = stringResource(Res.string.tab_more_attention_dot)
    val row = stringResource(Res.string.more_row_attention_dot)
    val activity = stringResource(Res.string.tab_activity_unread_dot)
    return { key ->
        when (RaftAppDestination.fromId(key)?.badge) {
            RaftNavBadgeSource.ServerPages, RaftNavBadgeSource.SettingsAttention -> more
            RaftNavBadgeSource.ComputersAttention, RaftNavBadgeSource.AppsReview -> row
            else -> activity
        }
    }
}

@Composable
internal fun RaftNavigationRailIcon(item: RaftBottomTabItemPayload, glyph: RaftConversationKindGlyph?, ink: Color) {
    if (item.isAppPlace) {
        RaftConnectedAppLogo(
            name = item.avatarFallbackLabel ?: item.label,
            logoUrl = item.avatarSource,
            size = RaftAvatarSize.Sm,
        )
    } else if (raftNavShowsOttie(RaftNavPresentation.Rail, item.key)) {
        // web OttieRailAvatar: a 28dp face where the other items have a 20dp glyph, centred on
        // the icon box and overhanging it; in full colour in either sticker (not the item's ink).
        HomeAssistantFace(Modifier.requiredSize(RaftRailOttieFaceSize), size = RaftRailOttieFaceSize)
    } else if (glyph != null) {
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
internal fun RaftNavigationRailTabItem(
    item: RaftBottomTabItemPayload,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
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
        modifier = modifier,
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
                .raftClickable(onClick = { onSelect(item.key) }, enabled = item.enabled, role = Role.Tab, surface = if (selected) RaftPressSurface.Sticker else RaftPressSurface.Paper, selected = selected)
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
                RaftStatusDot(variant = RaftStatusVariant.Accent, contentDescription = description)
            }
            if (item.opensExternally) {
                RaftLucideIcon(
                    name = RaftLucideIconName.ArrowUpRight,
                    color = if (item.enabled) raftLegacyIconInk(onSticker = selected) else RaftColor.TextMutedGrayArgb,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/**
 * Swallows touches that would otherwise fall through the tab bar to the page under it, without
 * any press feedback (a bare `clickable` greyed the whole bar while a finger rested on it).
 */
@Composable
private fun Modifier.raftTouchBlocker(
    onSwallowedTap: ((Offset) -> Unit)? = null,
): Modifier {
    val currentOnSwallowedTap by rememberUpdatedState(onSwallowedTap)
    return pointerInput(Unit) {
        // raw-click: tap sink, keeps taps on blank bar/safe-area space from reaching what is under it; not a control
        detectTapGestures { offset ->
            currentOnSwallowedTap?.invoke(offset)
        }
    }
}
