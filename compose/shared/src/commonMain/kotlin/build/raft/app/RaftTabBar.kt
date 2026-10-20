package build.raft.app

import androidx.compose.ui.text.TextStyle
import build.raft.app.markdown.traceMarkdownSection
import build.raft.app.markdown.traceMarkdownSectionNonComposable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
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
    val enabled: Boolean = true
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
    AndroidFloating
}

private val RaftSharedBottomTabBarHeight = 58.dp
// Fixed band that hosts the native floating dock (iOS Liquid Glass, Android
// floating). Scrollable content reserves this band plus the bottom safe area.
private val RaftFloatingDockBottomTabBarHostHeight = 92.dp

@Composable
internal fun raftAppBottomTabBarContentInset(bottomSafeArea: Dp): Dp {
    return raftAppBottomTabBarContentInset(
        renderer = raftAppTabBarRenderer(),
        bottomSafeArea = bottomSafeArea
    )
}

internal fun raftAppBottomTabBarContentInset(
    renderer: RaftAppTabBarRenderer,
    bottomSafeArea: Dp
): Dp {
    return when (renderer) {
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

internal val RaftVerticalTabSectionTextStyle: TextStyle
    get() =
        RaftType.TabLabel.copy(
            letterSpacing = 1.sp,
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
                        item.section.uppercase(),
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
    // raft-ui `MobileNav` (Raft DS > TabBar): an edge bar on the card layer
    // with a seam on top; the selected tab wears the yellow sticker behind its
    // icon and a bold label, the rest sit on foreground-hint.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(53.dp)
            .background(RaftColor.Card)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(start = 8.dp, top = RaftAppStroke.Thin + 6.dp, end = 8.dp)
        ) {
            items.forEach { item ->
                val selected = item.selected
                // raft-ui MobileNav press: the overlay sits on the sticker (12% ink on the active
                // brand sticker, fill-pressed at rest), never a wash over the whole cell. A bare
                // clickable here fell back to CMP's default indication: a dark gray rectangle.
                val pressSource = remember { MutableInteractionSource() }
                val pressed = pressSource.collectIsPressedAsState().value
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = pressSource,
                            indication = null,
                            enabled = item.enabled
                        ) { onSelect(item.key) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    // 56x30 sticker plus its 1.5dp sticker-edge ring drawn
                    // outside it (ring-[1.5px]); the label sits 3dp below the
                    // sticker, i.e. 7dp below the 22dp icon.
                    Box(
                        modifier = Modifier
                            .width(56.dp + RaftAppStroke.Control * 2)
                            .height(30.dp + RaftAppStroke.Control * 2)
                            .offset(y = -RaftAppStroke.Control)
                            .then(
                                if (selected) {
                                    Modifier
                                        .border(RaftAppStroke.Control, RaftColor.StickerEdge, RoundedCornerShape(RaftAppRadius.Control + RaftAppStroke.Control))
                                        .padding(RaftAppStroke.Control)
                                        .background(RaftColor.Brand, RoundedCornerShape(RaftAppRadius.Control))
                                        .border(RaftAppStroke.Control, RaftColor.StickerInk, RoundedCornerShape(RaftAppRadius.Control))
                                } else {
                                    Modifier
                                }
                            )
                            .then(
                                if (pressed && item.enabled) {
                                    Modifier.background(
                                        raftBottomTabPressOverlay(selected),
                                        RoundedCornerShape(RaftAppRadius.Control)
                                    )
                                } else {
                                    Modifier
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        RaftLucideIcon(
                            name = item.iconName,
                            color =
                                when {
                                    !item.enabled -> raftArgbHex(RaftColor.ForegroundDisabled)
                                    selected -> raftArgbHex(RaftColor.StickerInk)
                                    else -> raftArgbHex(RaftColor.ForegroundHint)
                                },
                            strokeWidth = 2f,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Text(
                        text = item.label,
                        style =
                            RaftType.TabLabel
                                .withRaftFontWeight(if (selected) FontWeight.Bold else FontWeight.SemiBold)
                                .copy(color = if (selected) RaftColor.TextBlack else RaftColor.ForegroundHint),
                        color = if (selected) RaftColor.TextBlack else RaftColor.ForegroundHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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

@Composable
internal fun RaftAppBottomTabBarHost(
    items: List<RaftBottomTabItemPayload>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomSafeArea: Dp = 0.dp
) {
    val hostModifier = modifier.zIndex(RaftAppBottomTabBarHostZIndex)
    when (raftAppTabBarRenderer()) {
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
                val selected = item.selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            if (selected) RaftColor.BrandYellow.copy(alpha = 0.2f) else Color.Transparent,
                            RoundedCornerShape(16.dp)
                        )
                        .clickable(enabled = item.enabled) { onSelect(item.key) }
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    RaftLucideIcon(
                        name = item.iconName,
                        color = when {
                            !item.enabled -> "#42000000"
                            selected -> "#FF000000"
                            else -> "#73000000"
                        },
                        modifier = Modifier.size(17.dp)
                    )
                    Text(
                        text = item.label.raftDisplayUppercase(),
                        style = RaftType.Caption.copy(
                            color = when {
                                !item.enabled -> Color.Black.copy(alpha = 0.26f)
                                selected -> Color.Black
                                else -> Color.Black.copy(alpha = 0.45f)
                            },
                            fontSize = 9.sp,
                            lineHeight = 11.sp
                        ).withRaftFontWeight(if (selected) FontWeight.Bold else FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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
        }
    }
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
