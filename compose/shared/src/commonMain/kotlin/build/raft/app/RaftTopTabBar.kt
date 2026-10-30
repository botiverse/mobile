package build.raft.app

import build.raft.app.components.TabPager
import build.raft.app.components.TabPagerGesturePolicy
import build.raft.app.markdown.traceMarkdownSection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftConversationKindGlyph
import build.raft.designsystem.components.RaftConversationKindIcon
import build.raft.designsystem.components.RaftTab
import build.raft.designsystem.components.RaftTabStrip
import build.raft.designsystem.theme.LocalRaftContentColor

internal data class RaftTabSpec<T>(
    val key: T,
    val label: String,
    val iconName: RaftLucideIconName,
    /** Minimum width. Unused by the RaftTabStrip tabs, which hug their label. */
    val width: Dp = RaftTopTabBarDefaults.TabWidth,
    /** A raft-ui conversation glyph drawn instead of [iconName] (web Chat tab: `ChatIcon`). */
    val glyph: RaftConversationKindGlyph? = null
)

internal data class RaftTopTabBarItem(
    val key: String,
    val label: String,
    val iconName: RaftLucideIconName,
    /** Minimum width. Unused by the RaftTabStrip tabs, which hug their label. */
    val width: Dp = RaftTopTabBarDefaults.TabWidth,
    /** A raft-ui conversation glyph drawn instead of [iconName] (web Chat tab: `ChatIcon`). */
    val glyph: RaftConversationKindGlyph? = null
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
    /** A tap on a tab in the strip (not a swipe), with whether that tab was already selected. */
    onTabTapped: (key: T, wasSelected: Boolean) -> Unit = { _, _ -> },
    /** The pager settled on [key] after a user swipe from another tab (never for taps, restores or spring-backs). */
    onTabSwiped: (key: T) -> Unit = {},
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
        onUserSwipeSettled = { index -> raftTabbedScaffoldPageKey(tabs, index)?.let(onTabSwiped) },
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
                        onTabTapped(tab.key, tab.key == selectedKey)
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
        width = width,
        glyph = glyph
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
    // marker measures the actual strip composition + tab click path.
    traceMarkdownSection(RaftTabBarTraceNames.TOP_TAB_RENDER, items.size) {
        // raft-ui Tabs (default variant) as a panel strip: the design system's RaftTabStrip
        // (layer-canvas, 16/8dp padding, line-seam, sideways scroll with edge fades, the
        // selected tab scrolled into view, back-swipe region registration) with sticker
        // RaftTabs. Each tab keeps its sticker fill and edge in its modifier chain and only
        // recolours them, so the selection moving with the pager can never leave a stale
        // yellow sticker behind (the hand-rolled chip added/removed its background and
        // border nodes, and on iOS every visited tab stayed yellow).
        RaftTabStrip(modifier = modifier) {
            items.forEach { item ->
                key("$itemKeyPrefix-${item.key}") {
                    RaftTab(
                        text = item.label,
                        selected = item.key == selectedKey,
                        onClick = { onSelectedKeyChange(item.key) },
                        icon = {
                            val glyph = item.glyph
                            if (glyph != null) {
                                // raft-ui Tabs: `[&_svg]:size-4 [&_svg]:stroke-2` on the 18-unit glyph.
                                RaftConversationKindIcon(
                                    glyph,
                                    size = 16.dp,
                                    color = LocalRaftContentColor.current,
                                    strokeWidth = 2f
                                )
                            } else {
                                RaftLucideIcon(
                                    item.iconName,
                                    modifier = Modifier.size(16.dp),
                                    color = raftArgbHex(LocalRaftContentColor.current),
                                    strokeWidth = 2f
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
