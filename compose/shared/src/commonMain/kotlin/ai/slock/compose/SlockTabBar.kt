package ai.slock.compose

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
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
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import kotlinx.serialization.Serializable

@Serializable
internal data class SlockTabItemPayload(
    val key: String = "",
    val title: String = "",
    val subtitle: String = "",
    val iconName: String = "",
    val value: String? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true
)

@Serializable
internal data class SlockBottomTabItemPayload(
    val key: String = "",
    val label: String = "",
    val iconName: String = "",
    val selected: Boolean = false,
    val enabled: Boolean = true
)

internal enum class SlockTabBarPresentation {
    Edge,
    SafeAreaHost,
    FloatingDock,
    NavigationRail
}

@Composable
internal fun SlockVerticalTabBar(
    items: List<SlockTabItemPayload>,
    showSelection: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
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
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(53.dp)
            .background(SlockColor.SurfaceWhite)
    ) {
        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            items.forEachIndexed { index, item ->
                val selected = item.selected
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
                        name = item.iconName.takeIf { it.isNotBlank() } ?: "circle",
                        color = if (item.enabled) "#FF000000" else "#FF737373",
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = item.label.uppercase(),
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

@Composable
internal fun SlockAppBottomTabBarHost(
    items: List<SlockBottomTabItemPayload>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomSafeArea: Dp = 0.dp
) {
    Column(modifier = modifier.fillMaxWidth().background(SlockColor.SurfaceWhite)) {
        SlockBottomTabBar(
            items = items,
            onSelect = onSelect,
            modifier = Modifier.fillMaxWidth().offset(y = -SlockStroke.Default)
        )
        Spacer(modifier = Modifier.fillMaxWidth().height(SlockSpacing.Xs))
        Spacer(modifier = Modifier.fillMaxWidth().height(bottomSafeArea))
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
                    name = item.iconName.takeIf { it.isNotBlank() } ?: "circle",
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
    val backgroundColor = when {
        !item.enabled -> SlockColor.EmptyStateGray
        selected -> SlockColor.AccentCyan
        else -> SlockColor.SurfaceWhite
    }
    val textColor = if (item.enabled) SlockColor.TextBlack else SlockColor.TextMutedGray
    Column(modifier = Modifier.fillMaxWidth()) {
        if (drawTopBorder) {
            Spacer(modifier = Modifier.fillMaxWidth().heightIn(min = SlockStroke.Default).background(SlockColor.BorderBlack))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(backgroundColor)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .clickable(enabled = item.enabled) { onSelect(item.key) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                SlockLucideIcon(
                    name = item.iconName.takeIf { it.isNotBlank() } ?: "settings",
                    color = if (item.enabled) "#FF000000" else "#FF737373",
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.title,
                    style = SlockType.Body.copy(color = textColor),
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                item.subtitle.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                        color = SlockColor.TextMutedGray,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            item.value?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(72.dp)
                )
            }
            SlockLucideIcon(
                name = "chevron-right",
                color = if (item.enabled) "#FF000000" else "#FF737373",
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
