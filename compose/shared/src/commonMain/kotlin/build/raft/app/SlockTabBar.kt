package build.raft.app

import androidx.compose.ui.text.TextStyle
import build.raft.app.markdown.traceMarkdownSection
import build.raft.app.markdown.traceMarkdownSectionNonComposable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
internal data class SlockTabItemPayload(
    val key: String = "",
    val title: String = "",
    val subtitle: String = "",
    val iconName: SlockLucideIconName = SlockLucideIconName.Settings,
    val value: String? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val section: String? = null
)

@Serializable
internal data class SlockBottomTabItemPayload(
    val key: String = "",
    val label: String = "",
    val iconName: SlockLucideIconName = SlockLucideIconName.Circle,
    val selected: Boolean = false,
    val enabled: Boolean = true
)

internal enum class SlockTabBarPresentation {
    Edge,
    SafeAreaHost,
    FloatingDock,
    NavigationRail
}

internal enum class SlockAppTabBarRenderer {
    SharedCompose,
    IosLiquidGlass,
    AndroidFloating
}

private val SlockSharedBottomTabBarHeight = 58.dp
// Fixed band that hosts the native floating dock (iOS Liquid Glass, Android
// floating). Scrollable content reserves this band plus the bottom safe area.
private val SlockFloatingDockBottomTabBarHostHeight = 92.dp

@Composable
internal fun slockAppBottomTabBarContentInset(bottomSafeArea: Dp): Dp {
    return slockAppBottomTabBarContentInset(
        renderer = slockAppTabBarRenderer(),
        bottomSafeArea = bottomSafeArea
    )
}

internal fun slockAppBottomTabBarContentInset(
    renderer: SlockAppTabBarRenderer,
    bottomSafeArea: Dp
): Dp {
    return when (renderer) {
        // Native floating docks remain overlays so backgrounds can paint
        // edge-to-edge, but scrollable trailing content must reserve the full
        // host band. Otherwise the final row settles under the native bar and
        // its tap target is intercepted by the tab surface.
        SlockAppTabBarRenderer.IosLiquidGlass,
        SlockAppTabBarRenderer.AndroidFloating ->
            slockFloatingDockBottomTabBarHostHeight(bottomSafeArea)
        SlockAppTabBarRenderer.SharedCompose -> slockSharedBottomTabBarContentInset(bottomSafeArea)
    }
}

@Composable
internal fun slockAppBottomTabBarOverlaysContent(): Boolean {
    return when (slockAppTabBarRenderer()) {
        SlockAppTabBarRenderer.IosLiquidGlass,
        SlockAppTabBarRenderer.AndroidFloating -> true
        SlockAppTabBarRenderer.SharedCompose -> false
    }
}

internal fun slockSharedBottomTabBarHostHeight(bottomSafeArea: Dp): Dp =
    SlockSharedBottomTabBarHeight + bottomSafeArea

internal fun slockSharedBottomTabBarContentInset(bottomSafeArea: Dp): Dp =
    slockSharedBottomTabBarHostHeight(bottomSafeArea) + SlockSpacing.Lg

internal fun slockFloatingDockBottomTabBarHostHeight(bottomSafeArea: Dp): Dp =
    SlockFloatingDockBottomTabBarHostHeight + bottomSafeArea

internal val SlockVerticalTabSectionTextStyle: TextStyle
    get() =
        SlockType.TabLabel.copy(
            letterSpacing = 1.sp,
            color = SlockColor.TextBlack.copy(alpha = 0.4f)
        )
internal val SlockVerticalTabSectionBottomPadding = 4.dp

@Composable
internal fun SlockVerticalTabBar(
    items: List<SlockTabItemPayload>,
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
                            bottom = SlockVerticalTabSectionBottomPadding
                        )
                ) {
                    Text(
                        item.section.uppercase(),
                        style = SlockVerticalTabSectionTextStyle,
                        color = SlockVerticalTabSectionTextStyle.color
                    )
                }
            }
            SlockVerticalTabItem(
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
internal fun SlockBottomTabBar(
    items: List<SlockBottomTabItemPayload>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // #192 trace scope extension: bottom tab bar (Home / Tasks / etc.) render.
    // Tab switches are #191 hot path; this marker measures per-tab-item composition.
    traceMarkdownSection(SlockTabBarTraceNames.BOTTOM_TAB_RENDER, items.size) {
    // raft-ui `MobileNav` (Raft DS > TabBar): an edge bar on the card layer
    // with a seam on top; the selected tab wears the yellow sticker behind its
    // icon and a bold label, the rest sit on foreground-hint.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(53.dp)
            .background(SlockColor.Card)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(start = 8.dp, top = SlockStroke.Thin + 6.dp, end = 8.dp)
        ) {
            items.forEach { item ->
                val selected = item.selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(enabled = item.enabled) { onSelect(item.key) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    // 56x30 sticker plus its 1.5dp sticker-edge ring drawn
                    // outside it (ring-[1.5px]); the label sits 3dp below the
                    // sticker, i.e. 7dp below the 22dp icon.
                    Box(
                        modifier = Modifier
                            .width(56.dp + SlockStroke.Control * 2)
                            .height(30.dp + SlockStroke.Control * 2)
                            .offset(y = -SlockStroke.Control)
                            .then(
                                if (selected) {
                                    Modifier
                                        .border(SlockStroke.Control, SlockColor.StickerEdge, RoundedCornerShape(SlockRadius.Control + SlockStroke.Control))
                                        .padding(SlockStroke.Control)
                                        .background(SlockColor.Brand, RoundedCornerShape(SlockRadius.Control))
                                        .border(SlockStroke.Control, SlockColor.StickerInk, RoundedCornerShape(SlockRadius.Control))
                                } else {
                                    Modifier
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        SlockLucideIcon(
                            name = item.iconName,
                            color =
                                when {
                                    !item.enabled -> slockArgbHex(SlockColor.ForegroundDisabled)
                                    selected -> slockArgbHex(SlockColor.StickerInk)
                                    else -> slockArgbHex(SlockColor.ForegroundHint)
                                },
                            strokeWidth = 2f,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Text(
                        text = item.label,
                        style =
                            SlockType.TabLabel
                                .withSlockFontWeight(if (selected) FontWeight.Bold else FontWeight.SemiBold)
                                .copy(color = if (selected) SlockColor.TextBlack else SlockColor.ForegroundHint),
                        color = if (selected) SlockColor.TextBlack else SlockColor.ForegroundHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SlockStroke.Thin)
                .background(SlockColor.BorderSeam)
                .align(Alignment.TopCenter)
        )
    }
    }
}

@Composable
internal fun SlockAppBottomTabBarHost(
    items: List<SlockBottomTabItemPayload>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomSafeArea: Dp = 0.dp
) {
    val hostModifier = modifier.zIndex(SlockAppBottomTabBarHostZIndex)
    when (slockAppTabBarRenderer()) {
        SlockAppTabBarRenderer.IosLiquidGlass -> {
            // The iOS host paints edge-to-edge. Seat the native dock band at the
            // host bottom so UITabBar receives the real bottom safe area instead
            // of leaving that inset as a second empty band below the tab bar.
            SlockNativeFloatingBottomTabBarHost(
                items = items,
                onSelect = onSelect,
                modifier = hostModifier,
                bottomSafeArea = bottomSafeArea,
                seatDockAtHostBottom = true
            )
            return
        }
        SlockAppTabBarRenderer.AndroidFloating -> {
            SlockSharedAndroidFloatingBottomTabBarHost(
                items = items,
                onSelect = onSelect,
                modifier = hostModifier,
                bottomSafeArea = bottomSafeArea
            )
            return
        }
        SlockAppTabBarRenderer.SharedCompose -> Unit
    }
    Box(
        modifier = hostModifier
            .fillMaxWidth()
            .height(slockSharedBottomTabBarHostHeight(bottomSafeArea))
            .background(SlockColor.Card)
            .clickable(onClick = {})
    ) {
        SlockBottomTabBar(
            items = items,
            onSelect = onSelect,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        )
    }
}

@Composable
private fun SlockSharedAndroidFloatingBottomTabBarHost(
    items: List<SlockBottomTabItemPayload>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomSafeArea: Dp = 0.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(slockFloatingDockBottomTabBarHostHeight(bottomSafeArea))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The old Android ComposeView received the dock band's exact
                // 92dp parent constraint, so its nominal 56dp Row expanded to
                // the full band. Preserve that rendered geometry in Kuikly.
                .height(SlockFloatingDockBottomTabBarHostHeight)
                .padding(horizontal = 18.dp)
                .background(SlockColor.SurfaceWhite.copy(alpha = 0.94f), RoundedCornerShape(28.dp))
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
                            if (selected) SlockColor.BrandYellow.copy(alpha = 0.2f) else Color.Transparent,
                            RoundedCornerShape(16.dp)
                        )
                        .clickable(enabled = item.enabled) { onSelect(item.key) }
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    SlockLucideIcon(
                        name = item.iconName,
                        color = when {
                            !item.enabled -> "#42000000"
                            selected -> "#FF000000"
                            else -> "#73000000"
                        },
                        modifier = Modifier.size(17.dp)
                    )
                    Text(
                        text = item.label.slockDisplayUppercase(),
                        style = SlockType.Caption.copy(
                            color = when {
                                !item.enabled -> Color.Black.copy(alpha = 0.26f)
                                selected -> Color.Black
                                else -> Color.Black.copy(alpha = 0.45f)
                            },
                            fontSize = 9.sp,
                            lineHeight = 11.sp
                        ).withSlockFontWeight(if (selected) FontWeight.Bold else FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SlockNativeFloatingBottomTabBarHost(
    items: List<SlockBottomTabItemPayload>,
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
            .height(SlockFloatingDockBottomTabBarHostHeight + bottomSafeArea)
    ) {
        SlockNativeFloatingTabBar(
            items = items,
            onSelect = onSelect,
            bottomSafeArea = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(SlockFloatingDockBottomTabBarHostHeight)
                .align(dockBandAlignment)
        )
    }
}

@Composable
private fun slockAppTabBarRenderer(): SlockAppTabBarRenderer {
    val params = (LocalActivity.current.getPager() as Pager).pageData.params
    val explicitRenderer =
        params.optString("slockNativeTabBarRenderer")
            .ifBlank { params.optString("nativeTabBarRenderer") }
    return resolveSlockAppTabBarRenderer(
        explicitRenderer = explicitRenderer,
        platformDefaultRenderer = slockPlatformDefaultAppTabBarRenderer()
    )
}

internal fun resolveSlockAppTabBarRenderer(
    explicitRenderer: String?,
    platformDefaultRenderer: String?
): SlockAppTabBarRenderer {
    return when (explicitRenderer?.takeIf { it.isNotBlank() } ?: platformDefaultRenderer.orEmpty()) {
        "iosLiquidGlass", "iosNativeFloating" -> SlockAppTabBarRenderer.IosLiquidGlass
        "androidFloating", "androidNativeFloating" -> SlockAppTabBarRenderer.AndroidFloating
        else -> SlockAppTabBarRenderer.SharedCompose
    }
}

@Composable
private fun SlockNativeFloatingTabBar(
    items: List<SlockBottomTabItemPayload>,
    onSelect: (String) -> Unit,
    bottomSafeArea: Dp,
    modifier: Modifier = Modifier
) {
    // TODO(cmp-switch): the native floating tab bars (iOS liquid glass / Android floating) were
    // Kuikly native views; render the shared Compose floating dock until a CMP interop exists.
    SlockFloatingDockTabBar(
        items = items,
        onSelect = onSelect,
        modifier = modifier,
        bottomSafeArea = bottomSafeArea
    )
}

@Composable
internal fun SlockFloatingDockTabBar(
    items: List<SlockBottomTabItemPayload>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomSafeArea: Dp = 0.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(slockFloatingDockBottomTabBarHostHeight(bottomSafeArea))
            .clickable(onClick = {})
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(end = SlockStroke.Default, bottom = bottomSafeArea + SlockSpacing.Md + SlockStroke.Default)
        ) {
            Box(Modifier.matchParentSize().offset(x = SlockStroke.Default, y = SlockStroke.Default).background(SlockColor.BorderBlack))
            SlockBottomTabBar(
                items = items,
                onSelect = onSelect,
                modifier = Modifier.fillMaxWidth().border(SlockStroke.Default, SlockColor.BorderBlack)
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
internal data class SlockNavigationRailHeader(
    val key: String,
    val label: String,
    val unread: Boolean = false,
    val enabled: Boolean = true
)

@Composable
internal fun SlockNavigationRailTabBar(
    items: List<SlockBottomTabItemPayload>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    header: SlockNavigationRailHeader? = null,
    headerContent: (@Composable () -> Unit)? = null,
    bottomItems: List<SlockBottomTabItemPayload> = emptyList(),
    onBottomItemSelect: ((String) -> Unit)? = null,
    topSafeArea: Dp = 0.dp,
    bottomSafeArea: Dp = 0.dp
) {
    Row(modifier = modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .width(SlockNavigationRailWidth)
                .fillMaxHeight()
                .background(SlockColor.BrandYellow)
        ) {
            if (header != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Page-header frame contract (slockTopPageHeaderFrameMetrics): total
                        // band = topSafeArea + 62dp with the 2dp divider as the LAST 2dp of
                        // that band. This Column is the content area — topSafeArea + (62 −
                        // stroke) — so the divider drawn after it ends at safeArea + 62dp,
                        // exactly the channel list header baseline; and the 40dp tile keeps
                        // ~60dp of room instead of clipping.
                        .height(
                            SlockNavigationRailHeaderHeight + topSafeArea - SlockStroke.Default
                        )
                        .padding(top = topSafeArea),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (headerContent != null) {
                        headerContent()
                    } else {
                        SlockNavigationRailServerTile(header = header, onSelect = onSelect)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SlockStroke.Default)
                        .background(SlockColor.BorderBlack)
                )
            } else {
                Spacer(Modifier.height(SlockSpacing.Sm + topSafeArea))
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = SlockSpacing.Sm),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(SlockNavigationRailItemSpacing)
            ) {
                items.forEach { item ->
                    SlockNavigationRailTabItem(item = item, onSelect = onSelect)
                }
            }
            if (bottomItems.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = SlockSpacing.Sm, bottom = SlockSpacing.Sm + bottomSafeArea),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(SlockNavigationRailItemSpacing)
                ) {
                    bottomItems.forEach { item ->
                        SlockNavigationRailTabItem(item = item, onSelect = onBottomItemSelect ?: onSelect)
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
                .width(SlockStroke.Default)
                .fillMaxHeight()
                .background(SlockColor.BorderBlack)
        )
    }
}

@Composable
private fun SlockNavigationRailTabItem(
    item: SlockBottomTabItemPayload,
    onSelect: (String) -> Unit
) {
    val selected = item.selected
    // 48dp outer cell owns the tap (min 44dp touch target); the 40dp visual tile
    // inside keeps the web button size.
    Box(
        modifier =
            Modifier
                .size(SlockNavigationRailTouchSize)
                .clickable(enabled = item.enabled) { onSelect(item.key) }
                // task #31: icon-only buttons keep an accessible label.
                .semantics { contentDescription = item.label },
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.size(SlockNavigationRailButtonSize)) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .offset(x = SlockStroke.Default, y = SlockStroke.Default)
                        .background(SlockColor.BorderBlack)
                )
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(if (selected) SlockColor.SurfaceWhite else Color.Transparent)
                    // Keep the border modifier on every state and change only its color. Dropping
                    // it on deselect left the native border in place, so every rail button tapped
                    // before kept an outline (artin, 1.13.0 large screen, Android and HarmonyOS).
                    .border(SlockStroke.Default, if (selected) SlockColor.BorderBlack else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                SlockLucideIcon(
                    name = item.iconName,
                    color = if (item.enabled) SlockColor.TextBlackArgb else SlockColor.TextMutedGrayArgb,
                    modifier = Modifier.size(SlockNavigationRailIconSize)
                )
            }
        }
    }
}

@Composable
private fun SlockNavigationRailServerTile(
    header: SlockNavigationRailHeader,
    onSelect: (String) -> Unit
) {
    val tileSize = 40.dp
    Box(modifier = Modifier.size(tileSize + SlockStroke.Default * 2)) {
        Box(
            modifier = Modifier
                .size(tileSize)
                .align(Alignment.TopStart)
                .offset(x = SlockStroke.Default, y = SlockStroke.Default)
                .background(SlockColor.BorderBlack)
        )
        Box(
            modifier = Modifier
                .size(tileSize)
                .align(Alignment.TopStart)
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .clickable(enabled = header.enabled) { onSelect(header.key) },
            contentAlignment = Alignment.Center
        ) {
            // Default tile shows the server's first letter; the real avatar surface is supplied
            // through headerContent (it needs the mounted avatar cache in the shell context).
            Text(
                text = header.label.take(1).uppercase(),
                style =
                    SlockType.Button.copy(
                        fontFamily = slockMonoFamilyForWeight(FontWeight.Bold),
                        fontSize = 17.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Normal
                    ),
                color = SlockColor.TextBlack,
                maxLines = 1
            )
        }
        if (header.unread) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .align(Alignment.TopEnd)
                    .background(SlockColor.AccentPink)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
            )
        }
    }
}

private val SlockNavigationRailWidth = 64.dp
private val SlockNavigationRailHeaderHeight = 62.dp
private val SlockNavigationRailButtonSize = 40.dp
private val SlockNavigationRailTouchSize = 48.dp
private val SlockNavigationRailIconSize = 18.dp
private val SlockNavigationRailItemSpacing = 6.dp

@Composable
private fun SlockVerticalTabItem(
    item: SlockTabItemPayload,
    selected: Boolean,
    drawTopBorder: Boolean,
    onSelect: (String) -> Unit,
    itemStartPadding: Dp
) {
    val textColor = if (item.enabled) SlockColor.TextBlack else SlockColor.TextMutedGray
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
                    .offset(x = SlockStroke.Default, y = SlockStroke.Default)
                    .background(SlockColor.BorderBlack)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(if (selected) SlockColor.AccentPink else Color.Transparent)
                // Same as the rail item: a removed border modifier does not clear the native border.
                .border(SlockStroke.Default, if (selected) SlockColor.BorderBlack else Color.Transparent)
                .clickable(enabled = item.enabled) { onSelect(item.key) }
                .padding(start = itemStartPadding, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SlockLucideIcon(
                name = item.iconName,
                color = if (item.enabled) "#FF000000" else "#FF737373",
                modifier = Modifier.size(14.dp)
            )
            Text(
                item.title,
                style = SlockType.Button.copy(color = textColor),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
