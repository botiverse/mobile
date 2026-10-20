package build.raft.app

import build.raft.app.components.TabPager
import build.raft.app.components.TabPagerGesturePolicy
import build.raft.app.markdown.traceMarkdownSection
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import build.raft.designsystem.gesture.rememberRaftBackSwipeScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class RaftTabSpec<T>(
    val key: T,
    val label: String,
    val iconName: RaftLucideIconName,
    /** Minimum width. Labels may expand the item inside the horizontal LazyRow. */
    val width: Dp = RaftTopTabBarDefaults.TabWidth
)

internal data class RaftTopTabBarItem(
    val key: String,
    val label: String,
    val iconName: RaftLucideIconName,
    /** Minimum width. Labels may expand the item inside the horizontal LazyRow. */
    val width: Dp = RaftTopTabBarDefaults.TabWidth
)

internal object RaftTopTabBarDefaults {
    val Height = 32.dp
    val TabWidth = 98.dp
}

/**
 * Pager index the [RaftTabbedScaffold] [TabPager] must show for [selectedKey].
 * Index-aligned with [tabs]: if [selectedKey] is not in [tabs], falls back to 0.
 */
internal fun <T> raftTabbedScaffoldPageIndex(
    tabs: List<RaftTabSpec<T>>,
    selectedKey: T,
): Int = tabs.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 } ?: 0

/**
 * Tab key for a [TabPager] page index, or `null` if [page] is out of range for
 * the current [tabs] list.
 */
internal fun <T> raftTabbedScaffoldPageKey(
    tabs: List<RaftTabSpec<T>>,
    page: Int,
): T? = tabs.getOrNull(page)?.key

@Composable
internal fun <T> RaftTabbedScaffold(
    tabs: List<RaftTabSpec<T>>,
    selectedKey: T,
    onSelectedKeyChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    itemKeyPrefix: String = "top-tab",
    content: @Composable (T, Modifier, Boolean) -> Unit
) {
    // task #232 PR3: mode tabs in RaftTabbedScaffold (Thread Chat/Tasks/Files)
    // share TabPager with Activity (PR1, #2224) and Agent Detail (PR2, #2345).
    // RaftTopTabBar stays the tap surface and selection owner; the body is a
    // HorizontalPager sharing the same selected index with keepItemAlive = true
    // so the 2-3 thread mode tabs render real content during swipe transitions
    // and stay alive across back-and-forth swipes without reload or loading
    // placeholder flicker. Platform gesture policy is injected via
    // raftPlatformTabPagerStartEdgeInsetDp. iOS uses 0dp: TabPager reports to the
    // host's full-page back swipe (rememberRaftBackSwipeScroll) — on the first page a
    // back-direction drag pops the page, past it the pager keeps the drag.
    TabPager(
        selectedIndex = raftTabbedScaffoldPageIndex(tabs, selectedKey),
        onSelectedIndexChange = { index ->
            raftTabbedScaffoldPageKey(tabs, index)?.let(onSelectedKeyChange)
        },
        pageCount = tabs.size.coerceAtLeast(1),
        gesturePolicy = TabPagerGesturePolicy(
            startEdgeInset = raftPlatformTabPagerStartEdgeInsetDp().dp,
        ),
        keepItemAlive = true,
        tabBar = {
            RaftTopTabBar(
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
        modifier = modifier.fillMaxSize().background(RaftColor.SurfaceWhite)
    ) { page, active ->
        val pageKey = raftTabbedScaffoldPageKey(tabs, page)
        if (pageKey != null) {
            content(
                pageKey,
                Modifier
                    .fillMaxSize()
                    .background(RaftColor.SurfaceWhite),
                active
            )
        }
    }
}

internal fun <T> RaftTabSpec<T>.toTopTabBarItem(): RaftTopTabBarItem =
    RaftTopTabBarItem(
        key = key.toString(),
        label = label,
        iconName = iconName,
        width = width
    )

@Composable
internal fun RaftTopTabBar(
    items: List<RaftTopTabBarItem>,
    selectedKey: String,
    onSelectedKeyChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    itemKeyPrefix: String = "top-tab"
) {
    // #192 trace scope extension: top tab bar render. Switches between tabs
    // are a known jank source (`operation=scene_enter` perf log line); this
    // marker measures the actual LazyRow + Row composition + tab click path.
    traceMarkdownSection(RaftTabBarTraceNames.TOP_TAB_RENDER, items.size) {
    // raft-ui `ConversationPanelTabs` + `Tabs` (default variant): a panel strip
    // (px-4 py-2, seam below) of 32dp tabs hugging their label; the active tab
    // is the yellow sticker (ink edge, bold ink label), the rest ghost on
    // foreground-muted.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(RaftTopTabBarDefaults.Height + RaftAppSpacing.Md + RaftAppStroke.Thin)
            .background(RaftColor.Canvas)
    ) {
        val rowState = rememberLazyListState()
        val backSwipe = rememberRaftBackSwipeScroll(rowState)
        LazyRow(
            state = rowState,
            userScrollEnabled = backSwipe.userScrollEnabled,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(backSwipe.modifier)
                .padding(horizontal = RaftAppSpacing.Md, vertical = RaftAppSpacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(items.size, key = { "$itemKeyPrefix-${items[it].key}" }) { index ->
                val item = items[index]
                val selected = item.key == selectedKey
                val shape = RoundedCornerShape(RaftAppRadius.Control)
                Row(
                    modifier = Modifier
                        .height(RaftTopTabBarDefaults.Height)
                        .then(
                            if (selected) {
                                Modifier
                                    .background(RaftColor.Brand, shape)
                                    .border(RaftAppStroke.Control, RaftColor.StickerInk, shape)
                            } else {
                                Modifier
                            }
                        )
                        .clickable { onSelectedKeyChange(item.key) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                ) {
                    val contentColor = if (selected) RaftColor.StickerInk else RaftColor.TextMuted
                    RaftLucideIcon(
                        item.iconName,
                        modifier = Modifier.size(16.dp),
                        color = raftArgbHex(contentColor),
                        strokeWidth = 2f
                    )
                    Text(
                        text = item.label,
                        style =
                            RaftType.Button
                                .withRaftFontWeight(if (selected) FontWeight.Bold else FontWeight.SemiBold)
                                .copy(fontSize = 14.sp, lineHeight = 20.sp, color = contentColor),
                        color = contentColor,
                        maxLines = 1
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(RaftColor.BorderSeam))
    }
    }
}
