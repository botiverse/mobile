package ai.slock.compose

import ai.slock.compose.markdown.traceMarkdownSection
import ai.slock.compose.markdown.traceMarkdownSectionNonComposable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.gestures.detectHorizontalDragGestures
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.lazy.LazyRow
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.input.pointer.positionChange
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import kotlin.math.abs

internal enum class SlockSelectionChangeSource {
    Click,
    Swipe,
    Availability
}

internal data class SlockTabSpec<T>(
    val key: T,
    val label: String,
    val iconName: SlockLucideIconName,
    val width: Dp = SlockTopTabBarDefaults.TabWidth,
    val swipeEnabled: Boolean = true
)

internal data class SlockTopTabBarItem(
    val key: String,
    val label: String,
    val iconName: SlockLucideIconName,
    val width: Dp = SlockTopTabBarDefaults.TabWidth
)

internal object SlockTopTabBarDefaults {
    val Height = 32.dp
    val TabWidth = 98.dp
}

@Composable
internal fun <T> SlockTabbedScaffold(
    tabs: List<SlockTabSpec<T>>,
    selectedKey: T,
    onSelectedKeyChange: (T, SlockSelectionChangeSource) -> Unit,
    modifier: Modifier = Modifier,
    itemKeyPrefix: String = "top-tab",
    swipeEnabled: Boolean = false,
    canSwipe: () -> Boolean = { true },
    content: @Composable (T, Modifier) -> Unit
) {
    Column(modifier = modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        SlockTopTabBar(
            items = tabs.map { it.toTopTabBarItem() },
            selectedKey = selectedKey.toString(),
            onSelectedKeyChange = { key ->
                tabs.firstOrNull { it.key.toString() == key }?.let { tab ->
                    onSelectedKeyChange(tab.key, SlockSelectionChangeSource.Click)
                }
            },
            itemKeyPrefix = itemKeyPrefix
        )
        val selectedTab = tabs.firstOrNull { it.key == selectedKey }
        val contentModifier =
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .slockSwipeSelection(
                    items = tabs.filter { it.swipeEnabled }.map { it.key },
                    selected = selectedKey,
                    enabled = swipeEnabled && selectedTab?.swipeEnabled == true,
                    canSwipe = canSwipe,
                    onSelectedChange = { next ->
                        onSelectedKeyChange(next, SlockSelectionChangeSource.Swipe)
                    }
                )
        content(selectedKey, contentModifier)
    }
}

@Composable
internal fun <T> Modifier.slockSwipeSelection(
    items: List<T>,
    selected: T,
    enabled: Boolean,
    swipeThreshold: Dp = 96.dp,
    horizontalDominanceRatio: Float = 1.45f,
    canSwipe: () -> Boolean = { true },
    onSelectedChange: (T) -> Unit
): Modifier {
    if (!enabled || items.size < 2) return this
    val density = LocalDensity.current
    val thresholdPx = with(density) { swipeThreshold.toPx() }
    val itemsState = rememberUpdatedState(items)
    val selectedState = rememberUpdatedState(selected)
    val canSwipeState = rememberUpdatedState(canSwipe)
    val onSelectedChangeState = rememberUpdatedState(onSelectedChange)
    return this.pointerInput(enabled, thresholdPx, horizontalDominanceRatio) {
        var dragDistance = 0f
        var verticalDistance = 0f
        detectHorizontalDragGestures(
            onDragStart = {
                dragDistance = 0f
                verticalDistance = 0f
            },
            onDragEnd = {
                if (canSwipeState.value() && isClearHorizontalSwipe(dragDistance, verticalDistance, thresholdPx, horizontalDominanceRatio)) {
                    val currentItems = itemsState.value
                    val currentIndex = currentItems.indexOf(selectedState.value)
                    if (currentIndex >= 0) {
                        when {
                            dragDistance <= -thresholdPx ->
                                currentItems.getOrNull(currentIndex + 1)?.let(onSelectedChangeState.value)
                            dragDistance >= thresholdPx ->
                                currentItems.getOrNull(currentIndex - 1)?.let(onSelectedChangeState.value)
                        }
                    }
                }
                dragDistance = 0f
                verticalDistance = 0f
            },
            onDragCancel = {
                dragDistance = 0f
                verticalDistance = 0f
            }
        ) { change, dragAmount ->
            dragDistance += dragAmount
            verticalDistance += change.positionChange().y
        }
    }
}

internal fun isClearHorizontalSwipe(
    horizontalDistance: Float,
    verticalDistance: Float,
    thresholdPx: Float,
    horizontalDominanceRatio: Float
): Boolean {
    val horizontal = abs(horizontalDistance)
    val vertical = abs(verticalDistance)
    return horizontal >= thresholdPx && horizontal >= vertical * horizontalDominanceRatio
}

internal fun <T> SlockTabSpec<T>.toTopTabBarItem(): SlockTopTabBarItem =
    SlockTopTabBarItem(
        key = key.toString(),
        label = label,
        iconName = iconName,
        width = width
    )

@Composable
internal fun SlockTopTabBar(
    items: List<SlockTopTabBarItem>,
    selectedKey: String,
    onSelectedKeyChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    itemKeyPrefix: String = "top-tab"
) {
    // #192 trace scope extension: top tab bar render. Switches between tabs
    // are a known jank source (`operation=scene_enter` perf log line); this
    // marker measures the actual LazyRow + Row composition + tab click path.
    traceMarkdownSection(SlockTabBarTraceNames.TOP_TAB_RENDER, items.size) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(SlockTopTabBarDefaults.Height)
            .background(SlockColor.SurfaceWhite)
    ) {
        LazyRow(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(items.size, key = { "$itemKeyPrefix-${items[it].key}" }) { index ->
                val item = items[index]
                val selected = item.key == selectedKey
                Row(
                    modifier = Modifier
                        .width(item.width)
                        .fillMaxHeight()
                        .background(if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                        .clickable { onSelectedKeyChange(item.key) }
                        .padding(horizontal = SlockSpacing.Sm + SlockSpacing.Xxs, vertical = SlockSpacing.Xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm - SlockSpacing.Xxs, Alignment.CenterHorizontally)
                ) {
                    SlockLucideIcon(item.iconName, modifier = Modifier.size(16.dp), color = "#FF000000")
                    Text(
                        text = item.label,
                        style = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp, color = SlockColor.TextBlack),
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .height(SlockTopTabBarDefaults.Height - SlockStroke.Default)
                        .width(SlockStroke.Default)
                        .background(SlockColor.BorderBlack)
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
    }
    }
}
