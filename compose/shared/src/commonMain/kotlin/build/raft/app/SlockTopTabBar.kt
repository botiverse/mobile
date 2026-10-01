package build.raft.app

import build.raft.app.components.TabPager
import build.raft.app.components.TabPagerGesturePolicy
import build.raft.app.markdown.traceMarkdownSection
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.clickable
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
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.foundation.lazy.LazyRow
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

internal data class SlockTabSpec<T>(
    val key: T,
    val label: String,
    val iconName: SlockLucideIconName,
    /** Minimum width. Labels may expand the item inside the horizontal LazyRow. */
    val width: Dp = SlockTopTabBarDefaults.TabWidth
)

internal data class SlockTopTabBarItem(
    val key: String,
    val label: String,
    val iconName: SlockLucideIconName,
    /** Minimum width. Labels may expand the item inside the horizontal LazyRow. */
    val width: Dp = SlockTopTabBarDefaults.TabWidth
)

internal object SlockTopTabBarDefaults {
    val Height = 32.dp
    val TabWidth = 98.dp
}

/**
 * Pager index the [SlockTabbedScaffold] [TabPager] must show for [selectedKey].
 * Index-aligned with [tabs]: if [selectedKey] is not in [tabs], falls back to 0.
 */
internal fun <T> slockTabbedScaffoldPageIndex(
    tabs: List<SlockTabSpec<T>>,
    selectedKey: T,
): Int = tabs.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 } ?: 0

/**
 * Tab key for a [TabPager] page index, or `null` if [page] is out of range for
 * the current [tabs] list.
 */
internal fun <T> slockTabbedScaffoldPageKey(
    tabs: List<SlockTabSpec<T>>,
    page: Int,
): T? = tabs.getOrNull(page)?.key

@Composable
internal fun <T> SlockTabbedScaffold(
    tabs: List<SlockTabSpec<T>>,
    selectedKey: T,
    onSelectedKeyChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    itemKeyPrefix: String = "top-tab",
    content: @Composable (T, Modifier, Boolean) -> Unit
) {
    // task #232 PR3: mode tabs in SlockTabbedScaffold (Thread Chat/Tasks/Files)
    // share TabPager with Activity (PR1, #2224) and Agent Detail (PR2, #2345).
    // SlockTopTabBar stays the tap surface and selection owner; the body is a
    // HorizontalPager sharing the same selected index with keepItemAlive = true
    // so the 2-3 thread mode tabs render real content during swipe transitions
    // and stay alive across back-and-forth swipes without reload or loading
    // placeholder flicker. Platform edge-back avoidance is injected via
    // slockPlatformTabPagerStartEdgeInsetDp (iOS 24dp).
    TabPager(
        selectedIndex = slockTabbedScaffoldPageIndex(tabs, selectedKey),
        onSelectedIndexChange = { index ->
            slockTabbedScaffoldPageKey(tabs, index)?.let(onSelectedKeyChange)
        },
        pageCount = tabs.size.coerceAtLeast(1),
        gesturePolicy = TabPagerGesturePolicy(
            startEdgeInset = slockPlatformTabPagerStartEdgeInsetDp().dp,
        ),
        keepItemAlive = true,
        tabBar = {
            SlockTopTabBar(
                items = tabs.map { it.toTopTabBarItem() },
                selectedKey = selectedKey.toString(),
                onSelectedKeyChange = { key ->
                    tabs.firstOrNull { it.key.toString() == key }?.let { tab ->
                        onSelectedKeyChange(tab.key)
                    }
                },
                itemKeyPrefix = itemKeyPrefix
            )
        },
        modifier = modifier.fillMaxSize().background(SlockColor.SurfaceWhite)
    ) { page, active ->
        val pageKey = slockTabbedScaffoldPageKey(tabs, page)
        if (pageKey != null) {
            content(
                pageKey,
                Modifier
                    .fillMaxSize()
                    .background(SlockColor.SurfaceWhite),
                active
            )
        }
    }
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
                        .widthIn(min = item.width)
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
                        maxLines = 1
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
