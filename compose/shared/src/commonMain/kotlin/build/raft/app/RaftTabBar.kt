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
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import build.raft.designsystem.components.RaftUnreadCount
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import build.raft.designsystem.components.raftUnreadCountDescription
import build.raft.designsystem.components.raftUnreadCountText
import kotlinx.serialization.Serializable

@Serializable
internal data class RaftTabItemPayload(
    val key: String = "",
    val title: String = "",
    val subtitle: String = "",
    val iconName: RaftLucideIconName = RaftLucideIconName.Settings,
    val value: String? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val section: String? = null
)

@Serializable
internal data class RaftBottomTabItemPayload(
    val key: String = "",
    val label: String = "",
    val iconName: RaftLucideIconName = RaftLucideIconName.Circle,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    /** Unread count drawn as the pink raft-ui count on the tab icon; 0 draws nothing. */
    val badgeCount: Int = 0
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

private val RaftSharedBottomTabBarHeight = 58.dp
// Fixed band that hosts the native floating dock (iOS Liquid Glass, Android
// floating). Scrollable content reserves this band plus the bottom safe area.
private val RaftFloatingDockBottomTabBarHostHeight = 92.dp
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
        // Native floating docks remain overlays so backgrounds can paint
        // edge-to-edge, but scrollable trailing content must reserve the full
        // host band. Otherwise the final row settles under the native bar and
        // its tap target is intercepted by the tab surface.
        RaftAppTabBarRenderer.IosLiquidGlass,
        RaftAppTabBarRenderer.AndroidFloating ->
            raftFloatingDockBottomTabBarHostHeight(bottomSafeArea)
        RaftAppTabBarRenderer.SharedCompose -> raftSharedBottomTabBarContentInset(bottomSafeArea)
    }
}

@Composable
internal fun raftAppBottomTabBarOverlaysContent(): Boolean {
    return when (raftAppTabBarRenderer()) {
        // The native host bar floats above the page: content runs edge to edge under it
        // (Liquid Glass on iOS 26+) and reserves the band through the content inset.
        RaftAppTabBarRenderer.NativeHost,
        RaftAppTabBarRenderer.IosLiquidGlass,
        RaftAppTabBarRenderer.AndroidFloating -> true
        RaftAppTabBarRenderer.SharedCompose -> false
    }
}

internal fun raftSharedBottomTabBarHostHeight(bottomSafeArea: Dp): Dp =
    RaftSharedBottomTabBarHeight + bottomSafeArea

internal fun raftSharedBottomTabBarContentInset(bottomSafeArea: Dp): Dp =
    raftSharedBottomTabBarHostHeight(bottomSafeArea) + RaftAppSpacing.Lg

internal fun raftFloatingDockBottomTabBarHostHeight(bottomSafeArea: Dp): Dp =
    RaftFloatingDockBottomTabBarHostHeight + bottomSafeArea

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
    modifier: Modifier = Modifier
) {
    // #192 trace scope extension: bottom tab bar (Home / Tasks / etc.) render.
    // Tab switches are #191 hot path; this marker measures per-tab-item composition.
    traceMarkdownSection(RaftTabBarTraceNames.BOTTOM_TAB_RENDER, items.size) {
    // Approved mockup (activity-tab A): an edge bar on the card layer with a
    // 1dp seam on top; every tab is a 56x32 pill slot holding a centred 23dp
    // icon with the label 4dp below. The selected pill is the brand sticker
    // (2dp ink border, 9dp radius); the pink count sits on the slot's top-right
    // corner, above the pill border, and never covers the icon.
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
                RaftBottomTabItem(
                    item = item,
                    onSelect = onSelect,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
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

/** Geometry of the approved bottom tab bar mockup (/tmp/activity-tab-mockups gen.js `.tabbar`). */
internal object RaftBottomTabBarSpec {
    val BarHeight = 58.dp
    val TopPadding = 7.dp
    val PillWidth = 56.dp
    val PillHeight = 32.dp
    val PillRadius = 9.dp
    val PillBorder = 2.dp
    val IconSize = 23.dp
    const val IconStroke = 2f
    val LabelGap = 4.dp
    val LabelFontSize = 11.5.sp
    val LabelLineHeight = 14.sp
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
}

internal fun raftBottomTabLabelWeight(selected: Boolean): FontWeight =
    if (selected) RaftBottomTabBarSpec.SelectedLabelWeight else RaftBottomTabBarSpec.LabelWeight

@Composable
private fun RaftBottomTabItem(
    item: RaftBottomTabItemPayload,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top
) {
    val selected = item.selected
    // raft-ui MobileNav press: the overlay sits on the pill (12% ink on the active
    // brand sticker, fill-pressed at rest), never a wash over the whole cell. A bare
    // clickable here fell back to CMP's default indication: a dark gray rectangle.
    val pressSource = remember { MutableInteractionSource() }
    val pressed = pressSource.collectIsPressedAsState().value
    val pillShape = RoundedCornerShape(RaftBottomTabBarSpec.PillRadius)
    val inkColor = when {
        !item.enabled -> RaftColor.ForegroundDisabled
        selected -> RaftColor.StickerInk
        else -> RaftColor.ForegroundHint
    }
    Column(
        modifier = modifier
            .clickable(
                interactionSource = pressSource,
                indication = null,
                enabled = item.enabled
            ) { onSelect(item.key) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = verticalArrangement
    ) {
        // The slot holds the pill and, as a sibling drawn after it, the badge,
        // so the pill border can never paint over the count.
        Box(
            modifier = Modifier.size(RaftBottomTabBarSpec.PillWidth, RaftBottomTabBarSpec.PillHeight)
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .then(
                        if (selected) {
                            val edge = RaftColor.StickerEdge
                            Modifier
                                .drawBehind {
                                    // Die-cut ring outside the pill: transparent in light, a paper ring in dark.
                                    if (edge.alpha > 0f) {
                                        val w = RaftAppStroke.Control.toPx()
                                        drawRoundRect(
                                            color = edge,
                                            topLeft = Offset(-w / 2f, -w / 2f),
                                            size = Size(size.width + w, size.height + w),
                                            cornerRadius = CornerRadius(RaftBottomTabBarSpec.PillRadius.toPx() + w / 2f),
                                            style = Stroke(w)
                                        )
                                    }
                                }
                                .background(RaftColor.Brand, pillShape)
                                .border(RaftBottomTabBarSpec.PillBorder, RaftColor.StickerInk, pillShape)
                        } else {
                            Modifier
                        }
                    )
                    .then(
                        if (pressed && item.enabled) {
                            Modifier.background(raftBottomTabPressOverlay(selected), pillShape)
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                RaftLucideIcon(
                    name = item.iconName,
                    color = raftArgbHex(inkColor),
                    strokeWidth = RaftBottomTabBarSpec.IconStroke,
                    modifier = Modifier.size(RaftBottomTabBarSpec.IconSize)
                )
            }
            RaftBottomTabCountBadge(
                count = item.badgeCount,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = RaftBottomTabBarSpec.BadgeOffsetX, y = RaftBottomTabBarSpec.BadgeOffsetY)
                    .wrapContentSize(align = Alignment.TopStart, unbounded = true)
            )
        }
        Spacer(Modifier.height(RaftBottomTabBarSpec.LabelGap))
        val labelColor = when {
            !item.enabled -> RaftColor.ForegroundDisabled
            selected -> RaftColor.TextBlack
            else -> RaftColor.ForegroundHint
        }
        // The label is sized to the bar's fixed 58dp geometry and, like a native tab bar,
        // does not scale with the system font size (it would run out of the bar).
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        Text(
            text = item.label,
            style =
                RaftType.TabLabel
                    .copy(
                        fontSize = RaftBottomTabBarSpec.LabelFontSize / fontScale,
                        lineHeight = RaftBottomTabBarSpec.LabelLineHeight / fontScale
                    )
                    .withRaftFontWeight(raftBottomTabLabelWeight(selected))
                    .copy(color = labelColor),
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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
    bottomSafeArea: Dp = 0.dp
) {
    val hostModifier = modifier.zIndex(RaftAppBottomTabBarHostZIndex)
    val nativeTabBar = raftNativeTabBarBridge()
    if (nativeTabBar != null) {
        // The host draws the system tab bar; Compose only publishes the tabs. Leaving
        // composition (the shell hides its bar) hides the native bar too.
        RaftNativeTabBarPublisher(bridge = nativeTabBar, items = items, onSelect = onSelect)
        return
    }
    when (raftAppTabBarRenderer()) {
        RaftAppTabBarRenderer.NativeHost -> return
        RaftAppTabBarRenderer.IosLiquidGlass -> {
            // The iOS host paints edge-to-edge. Seat the native dock band at the
            // host bottom so UITabBar receives the real bottom safe area instead
            // of leaving that inset as a second empty band below the tab bar.
            RaftNativeFloatingBottomTabBarHost(
                items = items,
                onSelect = onSelect,
                modifier = hostModifier,
                bottomSafeArea = bottomSafeArea,
                seatDockAtHostBottom = true
            )
            return
        }
        RaftAppTabBarRenderer.AndroidFloating -> {
            RaftSharedAndroidFloatingBottomTabBarHost(
                items = items,
                onSelect = onSelect,
                modifier = hostModifier,
                bottomSafeArea = bottomSafeArea
            )
            return
        }
        RaftAppTabBarRenderer.SharedCompose -> Unit
    }
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
                .align(Alignment.TopCenter)
        )
    }
}

@Composable
private fun RaftSharedAndroidFloatingBottomTabBarHost(
    items: List<RaftBottomTabItemPayload>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomSafeArea: Dp = 0.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(raftFloatingDockBottomTabBarHostHeight(bottomSafeArea))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The old Android ComposeView received the dock band's exact
                // 92dp parent constraint, so its nominal 56dp Row expanded to
                // the full band. Preserve that rendered geometry in Kuikly.
                .height(RaftFloatingDockBottomTabBarHostHeight)
                .padding(horizontal = 18.dp)
                .background(RaftColor.SurfaceWhite.copy(alpha = 0.94f), RoundedCornerShape(28.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                // Same pill / badge / label as the shared bar (approved mockup A).
                RaftBottomTabItem(
                    item = item,
                    onSelect = onSelect,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.Center
                )
            }
        }
    }
}

@Composable
private fun RaftNativeFloatingBottomTabBarHost(
    items: List<RaftBottomTabItemPayload>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomSafeArea: Dp = 0.dp,
    seatDockAtHostBottom: Boolean = false
) {
    // The dock band is a fixed 92dp. By default it sits at the top of the host
    // and the bottom safe area is empty space below it. When the host already
    // paints into the safe area (iOS edge-to-edge), seat the band at the host
    // bottom so the pill drops by one safe-area height instead of floating high.
    val dockBandAlignment = if (seatDockAtHostBottom) Alignment.BottomCenter else Alignment.TopCenter
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(RaftFloatingDockBottomTabBarHostHeight + bottomSafeArea)
    ) {
        RaftNativeFloatingTabBar(
            items = items,
            onSelect = onSelect,
            bottomSafeArea = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(RaftFloatingDockBottomTabBarHostHeight)
                .align(dockBandAlignment)
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

@Composable
private fun RaftNativeFloatingTabBar(
    items: List<RaftBottomTabItemPayload>,
    onSelect: (String) -> Unit,
    bottomSafeArea: Dp,
    modifier: Modifier = Modifier
) {
    // TODO(cmp-switch): the native floating tab bars (iOS liquid glass / Android floating) were
    // Kuikly native views; render the shared Compose floating dock until a CMP interop exists.
    RaftFloatingDockTabBar(
        items = items,
        onSelect = onSelect,
        modifier = modifier,
        bottomSafeArea = bottomSafeArea
    )
}

@Composable
internal fun RaftFloatingDockTabBar(
    items: List<RaftBottomTabItemPayload>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomSafeArea: Dp = 0.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(raftFloatingDockBottomTabBarHostHeight(bottomSafeArea))
            .raftTouchBlocker()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(end = RaftAppStroke.Default, bottom = bottomSafeArea + RaftAppSpacing.Md + RaftAppStroke.Default)
        ) {
            Box(Modifier.matchParentSize().offset(x = RaftAppStroke.Default, y = RaftAppStroke.Default).background(RaftColor.BorderBlack))
            RaftBottomTabBar(
                items = items,
                onSelect = onSelect,
                modifier = Modifier.fillMaxWidth().border(RaftAppStroke.Default, RaftColor.BorderBlack)
            )
        }
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
    topSafeArea: Dp = 0.dp,
    bottomSafeArea: Dp = 0.dp
) {
    Row(modifier = modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .width(RaftNavigationRailWidth)
                .fillMaxHeight()
                .background(RaftColor.BrandYellow)
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
                            RaftNavigationRailHeaderHeight + topSafeArea - RaftAppStroke.Default
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
                        .height(RaftAppStroke.Default)
                        .background(RaftColor.BorderBlack)
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
            if (bottomItems.isNotEmpty()) {
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
                }
            }
        }
        // The rail's right-edge divider runs the full window height, through the status bar.
        // task #482 had started it below the top inset, which left the status-bar part of
        // this column transparent and showed a thin white line between the rail and the
        // list; artin (2026-10-03) chose the full-height line instead.
        Box(
            modifier = Modifier
                .width(RaftAppStroke.Default)
                .fillMaxHeight()
                .background(RaftColor.BorderBlack)
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
            if (selected) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .offset(x = RaftAppStroke.Default, y = RaftAppStroke.Default)
                        .background(RaftColor.BorderBlack)
                )
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(if (selected) RaftColor.SurfaceWhite else Color.Transparent)
                    // Keep the border modifier on every state and change only its color. Dropping
                    // it on deselect left the native border in place, so every rail button tapped
                    // before kept an outline (artin, 1.13.0 large screen, Android and HarmonyOS).
                    .border(RaftAppStroke.Default, if (selected) RaftColor.BorderBlack else Color.Transparent),
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
                .align(Alignment.TopStart)
                .offset(x = RaftAppStroke.Default, y = RaftAppStroke.Default)
                .background(RaftColor.BorderBlack)
        )
        Box(
            modifier = Modifier
                .size(tileSize)
                .align(Alignment.TopStart)
                .background(RaftColor.SurfaceWhite)
                .border(RaftAppStroke.Default, RaftColor.BorderBlack)
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
                    .border(RaftAppStroke.Default, RaftColor.BorderBlack)
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
    val textColor = if (item.enabled) RaftColor.TextBlack else RaftColor.TextMutedGray
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
                    .background(RaftColor.BorderBlack)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(if (selected) RaftColor.AccentPink else Color.Transparent)
                // Same as the rail item: a removed border modifier does not clear the native border.
                .border(RaftAppStroke.Default, if (selected) RaftColor.BorderBlack else Color.Transparent)
                .clickable(enabled = item.enabled) { onSelect(item.key) }
                .padding(start = itemStartPadding, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RaftLucideIcon(
                name = item.iconName,
                color = if (item.enabled) "#FF000000" else "#FF737373",
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
        }
    }
}

/** Press overlay of a bottom tab's sticker (raft-ui Color > Pressed overlays). */
internal fun raftBottomTabPressOverlay(selected: Boolean): Color =
    if (selected) RaftColor.StickerPressed else RaftColor.FillPressed

/**
 * Swallows touches that would otherwise fall through the tab bar to the page under it, without
 * any press feedback (a bare `clickable` greyed the whole bar while a finger rested on it).
 */
@Composable
private fun Modifier.raftTouchBlocker(): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
