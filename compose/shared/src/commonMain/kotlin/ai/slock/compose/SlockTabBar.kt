package ai.slock.compose

import ai.slock.compose.markdown.traceMarkdownSection
import ai.slock.compose.markdown.traceMarkdownSectionNonComposable
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
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
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

private enum class SlockAppTabBarRenderer {
    SharedCompose,
    OhosHdsImmersive
}

private val SlockSharedBottomTabBarHeight = 58.dp
private val SlockOhosHdsBottomTabBarDockHeight = 56.dp
private val SlockOhosHdsBottomTabBarHostHeight = 92.dp
private val SlockBottomTabBarFloatingMargin = SlockSpacing.Md

@Composable
internal fun slockAppBottomTabBarContentInset(bottomSafeArea: Dp): Dp {
    return when (slockAppTabBarRenderer()) {
        // OHOS HDS dock already floats above the system gesture area; adding the host safe area
        // again here creates an extra blank strip below the dock and pushes content too far up.
        SlockAppTabBarRenderer.OhosHdsImmersive -> SlockOhosHdsBottomTabBarHostHeight + SlockSpacing.Lg
        SlockAppTabBarRenderer.SharedCompose -> slockSharedBottomTabBarContentInset(bottomSafeArea)
    }
}

internal fun slockSharedBottomTabBarHostHeight(bottomSafeArea: Dp): Dp =
    SlockSharedBottomTabBarHeight + bottomSafeArea

internal fun slockSharedBottomTabBarContentInset(bottomSafeArea: Dp): Dp =
    slockSharedBottomTabBarHostHeight(bottomSafeArea) + SlockSpacing.Lg

@Composable
internal fun SlockVerticalTabBar(
    items: List<SlockTabItemPayload>,
    showSelection: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        var lastSection: String? = null
        items.forEachIndexed { index, item ->
            if (item.section != null && item.section != lastSection) {
                lastSection = item.section
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, top = if (index > 0) 12.dp else 0.dp, bottom = 6.dp)
                ) {
                    Text(
                        item.section.uppercase(),
                        style = SlockType.Caption.copy(
                            letterSpacing = 2.sp,
                            color = SlockColor.TextMutedGray
                        ),
                        color = SlockColor.TextMutedGray
                    )
                }
            }
            SlockVerticalTabItem(
                item = item,
                selected = showSelection && item.selected,
                drawTopBorder = index == 0,
                onSelect = onSelect
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
    if (slockAppTabBarRenderer() == SlockAppTabBarRenderer.OhosHdsImmersive) {
        SlockOhosHdsBottomTabBarHost(
            items = items,
            onSelect = onSelect,
            modifier = modifier,
            bottomSafeArea = bottomSafeArea
        )
        return
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(slockSharedBottomTabBarHostHeight(bottomSafeArea))
            .background(SlockColor.SurfaceWhite)
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
private fun SlockOhosHdsBottomTabBarHost(
    items: List<SlockBottomTabItemPayload>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomSafeArea: Dp = 0.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SlockOhosHdsBottomTabBarHostHeight + bottomSafeArea)
    ) {
        SlockOhosHdsTabBar(
            items = items,
            onSelect = onSelect,
            bottomSafeArea = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(SlockOhosHdsBottomTabBarHostHeight)
                .align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun slockAppTabBarRenderer(): SlockAppTabBarRenderer {
    val params = (LocalActivity.current.getPager() as Pager).pageData.params
    return when (params.optString("slockNativeTabBarRenderer").ifBlank { params.optString("nativeTabBarRenderer") }) {
        "ohosHdsImmersive", "hdsImmersive" -> SlockAppTabBarRenderer.OhosHdsImmersive
        else -> SlockAppTabBarRenderer.SharedCompose
    }
}

@Composable
private fun SlockOhosHdsTabBar(
    items: List<SlockBottomTabItemPayload>,
    onSelect: (String) -> Unit,
    bottomSafeArea: Dp,
    modifier: Modifier = Modifier
) {
    MakeKuiklyComposeNode<SlockOhosHdsTabBarView>(
        factory = { SlockOhosHdsTabBarView() },
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

private class SlockOhosHdsTabBarView : DeclarativeBaseView<SlockOhosHdsTabBarAttr, SlockOhosHdsTabBarEvent>() {
    override fun createAttr(): SlockOhosHdsTabBarAttr = SlockOhosHdsTabBarAttr()

    override fun createEvent(): SlockOhosHdsTabBarEvent = SlockOhosHdsTabBarEvent()

    override fun viewName(): String = VIEW_NAME

    companion object {
        const val VIEW_NAME = "SlockOhosHdsTabBarView"
    }
}

private class SlockOhosHdsTabBarEvent : Event()

private class SlockOhosHdsTabBarAttr : Attr() {
    fun keys(value: String): SlockOhosHdsTabBarAttr {
        "keysText" with value
        return this
    }

    fun labels(value: String): SlockOhosHdsTabBarAttr {
        "labelsText" with value
        return this
    }

    fun icons(value: String): SlockOhosHdsTabBarAttr {
        "iconsText" with value
        return this
    }

    fun enabled(value: String): SlockOhosHdsTabBarAttr {
        "enabledText" with value
        return this
    }

    fun selectedKey(value: String): SlockOhosHdsTabBarAttr {
        "selectedKey" with value
        return this
    }

    fun bottomSafeArea(value: Float): SlockOhosHdsTabBarAttr {
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SlockSpacing.Lg)
            .padding(bottom = SlockSpacing.Md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(end = SlockStroke.Default, bottom = SlockStroke.Default)
        ) {
            Box(Modifier.matchParentSize().offset(x = SlockStroke.Default, y = SlockStroke.Default).background(SlockColor.BorderBlack))
            SlockBottomTabBar(
                items = items,
                onSelect = onSelect,
                modifier = Modifier.fillMaxWidth().border(SlockStroke.Default, SlockColor.BorderBlack)
            )
        }
        Spacer(modifier = Modifier.fillMaxWidth().height(bottomSafeArea))
    }
}

@Composable
internal fun SlockNavigationRailTabBar(
    items: List<SlockBottomTabItemPayload>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(72.dp)
            .fillMaxHeight()
            .background(SlockColor.SurfaceWhite)
    ) {
        items.forEach { item ->
            val selected = item.selected
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .background(if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                    .clickable(enabled = item.enabled) { onSelect(item.key) }
                    .padding(horizontal = SlockSpacing.Xs),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
            ) {
                SlockLucideIcon(
                    name = item.iconName,
                    color = if (item.enabled) "#FF000000" else "#FF737373",
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = item.label.uppercase(),
                    style = SlockType.TabLabel.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SlockVerticalTabItem(
    item: SlockTabItemPayload,
    selected: Boolean,
    drawTopBorder: Boolean,
    onSelect: (String) -> Unit
) {
    val textColor = if (item.enabled) SlockColor.TextBlack else SlockColor.TextMutedGray
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
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
                .height(38.dp)
                .background(if (selected) SlockColor.AccentPink else Color.Transparent)
                .then(if (selected) Modifier.border(SlockStroke.Default, SlockColor.BorderBlack) else Modifier)
                .clickable(enabled = item.enabled) { onSelect(item.key) }
                .padding(start = 14.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SlockLucideIcon(
                name = item.iconName.toVerticalTabIconName(),
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

private fun SlockLucideIconName.toVerticalTabIconName(): SlockLucideIconName =
    when (this) {
        SlockLucideIconName.Building2 -> SlockLucideIconName.Users
        SlockLucideIconName.AppWindow -> SlockLucideIconName.Square
        SlockLucideIconName.Languages -> SlockLucideIconName.Globe
        SlockLucideIconName.Type -> SlockLucideIconName.FileText
        else -> this
    }
