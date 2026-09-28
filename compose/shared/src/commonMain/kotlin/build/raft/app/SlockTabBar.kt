package build.raft.app

import build.raft.app.markdown.traceMarkdownSection
import build.raft.app.markdown.traceMarkdownSectionNonComposable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.extension.MakeKuiklyComposeNode
import com.tencent.kuikly.compose.extension.setEvent
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.semantics.contentDescription
import com.tencent.kuikly.compose.ui.semantics.semantics
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.compose.ui.zIndex
import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.event.Event
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
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
    OhosHdsImmersive,
    IosLiquidGlass,
    AndroidFloating
}

private val SlockSharedBottomTabBarHeight = 58.dp
private val SlockOhosHdsBottomTabBarHostHeight = 92.dp
// Mirror of the HDS floating bar geometry inside SlockOhosHdsTabBarView.ets:
// a 56vp bar lifted 26vp off the host bottom, inset 18vp per side. The
// Compose-side touch blocker below must track these numbers so it covers
// exactly the pill and nothing else.
private val SlockOhosHdsDockBarHeight = 56.dp
private val SlockOhosHdsDockBottomMargin = 26.dp
private val SlockOhosHdsDockSideMargin = 18.dp

// The native OHOS dock lives in the 92dp dock band of the host; the pill sits
// [band top+10 .. band top+66]. Custom native (ForwardArkTS) views are hit-test
// transparent, so taps over the pill fall through to the content list unless
// a Compose blocker in the same subtree consumes them first.
internal fun slockOhosHdsDockBlockerTopOffset(): Dp =
    SlockOhosHdsBottomTabBarHostHeight - SlockOhosHdsDockBottomMargin - SlockOhosHdsDockBarHeight

// When the dock band is seated at the host bottom (OHOS immersive, where the
// app now paints into the bottom safe area), the pill sits a fixed
// SlockOhosHdsDockBottomMargin above the host bottom regardless of safe-area
// height. The tap blocker (height = dock bar height) is bottom-anchored and
// lifted by that margin so it still covers exactly the pill rect.
internal fun slockOhosHdsDockBlockerBottomOffset(): Dp = SlockOhosHdsDockBottomMargin

internal fun slockOhosHdsDockBlockerHeight(): Dp = SlockOhosHdsDockBarHeight

internal fun slockOhosHdsDockBlockerSideMargin(): Dp = SlockOhosHdsDockSideMargin

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
        SlockAppTabBarRenderer.OhosHdsImmersive,
        SlockAppTabBarRenderer.IosLiquidGlass,
        SlockAppTabBarRenderer.AndroidFloating ->
            slockFloatingDockBottomTabBarHostHeight(bottomSafeArea)
        SlockAppTabBarRenderer.SharedCompose -> slockSharedBottomTabBarContentInset(bottomSafeArea)
    }
}

@Composable
internal fun slockAppBottomTabBarOverlaysContent(): Boolean {
    return when (slockAppTabBarRenderer()) {
        SlockAppTabBarRenderer.OhosHdsImmersive,
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
    SlockOhosHdsBottomTabBarHostHeight + bottomSafeArea

internal val SlockVerticalTabSectionTextStyle =
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
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(53.dp)
            .background(SlockColor.SurfaceWhite)
    ) {
        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            items.forEachIndexed { index, item ->
                val selected = item.selected
                val label = remember(item.label) { item.label.uppercase() }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                        .clickable(enabled = item.enabled) { onSelect(item.key) }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
                ) {
                    SlockLucideIcon(
                        name = item.iconName,
                        color = if (item.enabled) "#FF000000" else "#FF737373",
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = label,
                        style = SlockType.TabLabel.copy(color = SlockColor.TextBlack),
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (index < items.lastIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(SlockStroke.Default)
                            .background(SlockColor.BorderBlack)
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SlockStroke.Default)
                .background(SlockColor.BorderBlack)
                .align(Alignment.TopCenter)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SlockStroke.Default)
                .background(SlockColor.BorderBlack)
                .align(Alignment.BottomCenter)
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
        SlockAppTabBarRenderer.OhosHdsImmersive -> {
            // OHOS custom native views are hit-test transparent, so the dock
            // needs a Compose blocker under the pill to stop tap-through.
            // The OHOS host now paints into the bottom safe area (see Index.ets
            // expandSafeArea), so seat the dock band at the host bottom instead
            // of leaving the safe area as empty space below it.
            SlockNativeFloatingBottomTabBarHost(
                items = items,
                onSelect = onSelect,
                modifier = hostModifier,
                bottomSafeArea = bottomSafeArea,
                containDockTaps = true,
                seatDockAtHostBottom = true
            )
            return
        }
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
            .background(SlockColor.SurfaceWhite)
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
                .height(SlockOhosHdsBottomTabBarHostHeight)
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
                        text = item.label.uppercase(),
                        style = SlockType.Caption.copy(
                            color = when {
                                !item.enabled -> Color.Black.copy(alpha = 0.26f)
                                selected -> Color.Black
                                else -> Color.Black.copy(alpha = 0.45f)
                            },
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        ),
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
    containDockTaps: Boolean = false,
    seatDockAtHostBottom: Boolean = false
) {
    // The dock band is a fixed 92dp. By default it sits at the top of the host
    // and the bottom safe area is empty space below it. When the host already
    // paints into the safe area (OHOS immersive), seat the band at the host
    // bottom so the pill drops by one safe-area height instead of floating high.
    val dockBandAlignment = if (seatDockAtHostBottom) Alignment.BottomCenter else Alignment.TopCenter
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SlockOhosHdsBottomTabBarHostHeight + bottomSafeArea)
    ) {
        // Drawn first (lower z) so it sits under the native dock. Sized to the
        // pill rect only — margins stay tap-through, matching iOS pointInside.
        // The blocker tracks the dock band: top-anchored when the band is at the
        // top, bottom-anchored when the band is seated at the host bottom.
        if (containDockTaps) {
            val blockerModifier = if (seatDockAtHostBottom) {
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -slockOhosHdsDockBlockerBottomOffset())
            } else {
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = slockOhosHdsDockBlockerTopOffset())
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(slockOhosHdsDockBlockerHeight())
                    .then(blockerModifier)
                    .padding(horizontal = slockOhosHdsDockBlockerSideMargin())
                    .clickable(onClick = {})
            )
        }
        SlockNativeFloatingTabBar(
            items = items,
            onSelect = onSelect,
            bottomSafeArea = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(SlockOhosHdsBottomTabBarHostHeight)
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
        "ohosHdsImmersive", "hdsImmersive" -> SlockAppTabBarRenderer.OhosHdsImmersive
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
    MakeKuiklyComposeNode<SlockNativeFloatingTabBarView>(
        factory = { SlockNativeFloatingTabBarView() },
        modifier = modifier.setEvent("select") { payload ->
            val key =
                (payload as? JSONObject)?.optString("key")
                    ?: (payload as? Map<*, *>)?.get("key")?.toString()
                    ?: ""
            if (key.isNotBlank()) {
                onSelect(key)
            }
        },
        viewInit = {
            getViewAttr().run {
                keys(items.tabJoin { it.key })
                labels(items.tabJoin { it.label })
                icons(items.tabJoin { it.iconName.wireName })
                enabled(items.tabJoin { if (it.enabled) "1" else "0" })
                selectedKey(items.firstOrNull { it.selected }?.key.orEmpty())
                bottomSafeArea(bottomSafeArea.value)
            }
        },
        viewUpdate = { view ->
            view.getViewAttr().run {
                keys(items.tabJoin { it.key })
                labels(items.tabJoin { it.label })
                icons(items.tabJoin { it.iconName.wireName })
                enabled(items.tabJoin { if (it.enabled) "1" else "0" })
                selectedKey(items.firstOrNull { it.selected }?.key.orEmpty())
                bottomSafeArea(bottomSafeArea.value)
            }
        }
    )
}

private fun List<SlockBottomTabItemPayload>.tabJoin(value: (SlockBottomTabItemPayload) -> String): String =
    joinToString("\u001F") { value(it).replace("\u001F", " ") }

private class SlockNativeFloatingTabBarView : DeclarativeBaseView<SlockNativeFloatingTabBarAttr, SlockNativeFloatingTabBarEvent>() {
    override fun createAttr(): SlockNativeFloatingTabBarAttr = SlockNativeFloatingTabBarAttr()

    override fun createEvent(): SlockNativeFloatingTabBarEvent = SlockNativeFloatingTabBarEvent()

    override fun viewName(): String = VIEW_NAME

    companion object {
        const val VIEW_NAME = "SlockNativeFloatingTabBarView"
    }
}

private class SlockNativeFloatingTabBarEvent : Event()

private class SlockNativeFloatingTabBarAttr : Attr() {
    fun keys(value: String): SlockNativeFloatingTabBarAttr {
        "keysText" with value
        return this
    }

    fun labels(value: String): SlockNativeFloatingTabBarAttr {
        "labelsText" with value
        return this
    }

    fun icons(value: String): SlockNativeFloatingTabBarAttr {
        "iconsText" with value
        return this
    }

    fun enabled(value: String): SlockNativeFloatingTabBarAttr {
        "enabledText" with value
        return this
    }

    fun selectedKey(value: String): SlockNativeFloatingTabBarAttr {
        "selectedKey" with value
        return this
    }

    fun bottomSafeArea(value: Float): SlockNativeFloatingTabBarAttr {
        "bottomSafeArea" with value
        return this
    }
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
                    .then(if (selected) Modifier.border(SlockStroke.Default, SlockColor.BorderBlack) else Modifier),
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
                .then(if (selected) Modifier.border(SlockStroke.Default, SlockColor.BorderBlack) else Modifier)
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
