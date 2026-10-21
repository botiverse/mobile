package build.raft.app

import build.raft.app.data.isAppUpdateHomeNotificationId
import build.raft.app.data.raftComputerAttentionPairs
import build.raft.app.data.raftComputerAttentionUnseen

/**
 * Per-key bottom-tab icon. Activity uses the Lucide `activity` glyph, the same icon as the web
 * Activity nav item (artin 2026-10-20, Activity as a bottom tab); DMs/More keep the #94 icons.
 */
internal fun appShellTabIcon(key: String): RaftLucideIconName =
    when (key) {
        "home" -> RaftLucideIconName.House
        "dms" -> RaftLucideIconName.MessageCircle
        "activity" -> RaftLucideIconName.Activity
        "more" -> RaftLucideIconName.Ellipsis
        "tasks" -> RaftLucideIconName.SquareCheckBig
        "members" -> RaftLucideIconName.Users
        "settings" -> RaftLucideIconName.Settings
        AppShellSearchTabKey -> RaftLucideIconName.Search
        else -> RaftLucideIconName.Circle
    }

/**
 * Phone bottom bar (stdrc 2026-10-21): 主页 · 动态 · 任务 · 搜索 · 更多 in one full-width edge
 * bar. Search is a tab ([AppShellSearchTabKey], stdrc option B): selecting it shows the kept-alive
 * Search page, a tab root without Back, and the bar stays. More's root page is Settings;
 * Members is pushed from it (back returns to More). Members and Settings stay shell child pages
 * so the wide rail, deep links and old launch values still select them directly.
 */
internal val AppShellPhoneBottomBarKeys: List<String> =
    listOf("home", "activity", "tasks", AppShellSearchTabKey, "more")

/** The tabs of [AppShellPhoneBottomBarKeys]; every slot is a real tab. */
internal val AppShellPhoneBottomTabKeys: List<String> = AppShellPhoneBottomBarKeys

/**
 * The Search tab on the phone bar. Its page has no Back; a result opens a page with Back over
 * the shell, and Back returns to the tab with its query, results and scroll. The wide rail keeps
 * its own inline Search entry ([AppShellRailSearchKey]) instead.
 */
internal const val AppShellSearchTabKey = "search"

/** Child pages pushed from the More (Settings) page; the bottom bar keeps More selected. */
internal val AppShellMoreChildTabKeys: Set<String> = setOf("members")

/** The bottom-bar key that shows as selected for [selectedTab]. */
internal fun appShellPhoneBottomBarSelectedKey(selectedTab: String): String {
    val tab = retainedAppShellSelectedTab(selectedTab)
    return if (tab in AppShellMoreChildTabKeys || tab == "settings") "more" else tab
}

/**
 * Where Back goes from [selectedTab] on the phone layout: a page pushed from More returns to
 * More; null means the ordinary shell Back rules apply. The wide rail lists Members as a rail
 * root, so it never pops it to More.
 */
internal fun appShellMoreChildBackTarget(selectedTab: String, navRailActive: Boolean): String? =
    if (!navRailActive && retainedAppShellSelectedTab(selectedTab) in AppShellMoreChildTabKeys) "more" else null

/**
 * Settings has one root per layout (stdrc 2026-10-21): the More tab on the phone bar, the pinned
 * Settings entry on the wide rail. A selection made for the other layout (a `settings` deep link
 * or the diagnostics link on a phone, a phone More left open when the window widens) moves to
 * this layout's root; null keeps [selectedTab].
 */
internal fun appShellSettingsRootTab(selectedTab: String, navRailActive: Boolean): String? =
    when {
        !navRailActive && selectedTab == "settings" -> "more"
        navRailActive && selectedTab == "more" -> "settings"
        else -> null
    }

/**
 * The Search page's chrome under the navigation rule (stdrc 2026-10-21): as the phone Search tab
 * root it has no Back and sits above the bottom bar; pushed (wide rail, channel search) it has
 * Back and covers the bar. A result always opens a pushed page with Back over either.
 */
internal data class HomeSearchPageChrome(val showsBack: Boolean, val aboveBottomBar: Boolean)

internal fun homeSearchPageChrome(tabRoot: Boolean): HomeSearchPageChrome =
    HomeSearchPageChrome(showsBack = !tabRoot, aboveBottomBar = tabRoot)

/** The wide rail lists Search as its inline entry, not a tab: a selected Search tab moves there. */
internal fun appShellSearchTabMovesToRailSearch(selectedTab: String, navRailActive: Boolean): Boolean =
    navRailActive && selectedTab == AppShellSearchTabKey

/**
 * artin 2026-10-21 (with Jasper, design system): Activity unread on the tab icon is the pink
 * unread count — the conversation count the Activity head reads as "N unread", capped "99+" by
 * the renderers — instead of a dot. Like the dot it was, the selected tab never wears it. Every
 * other tab keeps its dot rule; only Activity carries a number.
 */
internal fun appShellActivityTabBadgeCount(unreadCount: Int, selected: Boolean): Int =
    if (selected) 0 else unreadCount.coerceAtLeast(0)

/**
 * Something new inside More (the Settings page). Each signal dots its own row on that page
 * ([settingsRowKey]) and the More tab.
 */
internal enum class RaftMoreAttention(val settingsRowKey: String) {
    /** An app update is available: Settings > About checks/installs it. */
    AppUpdate(SettingsNativeDetailPage.About.key),

    /** A computer entered an offline / outdated state not yet seen: the Computers row. */
    Computers(SettingsDestinationComputers),
}

/**
 * The More signals from the Home notices the shell already loads (they clear when dismissed):
 * - [RaftMoreAttention.AppUpdate]: the app-update notice.
 * - [RaftMoreAttention.Computers]: a computer the offline-computer or outdated-daemon notice
 *   names is in a state not yet seen ([computersSeen]; raft-design #620 "Attention dots": the
 *   dot marks an unseen change, opening Computers sees it — see data/RaftComputerAttentionSeen.kt).
 * Members has no unread signal today. The More tab dot reads this same set
 * ([appShellMoreTabShowsDot]), so the tab and its rows light and clear together.
 */
internal fun raftMoreAttention(
    homeNotificationIds: List<String>,
    computersSeen: Set<String> = emptySet(),
): Set<RaftMoreAttention> =
    buildSet {
        if (homeNotificationIds.any { it.isAppUpdateHomeNotificationId() }) add(RaftMoreAttention.AppUpdate)
        if (raftComputerAttentionUnseen(raftComputerAttentionPairs(homeNotificationIds), computersSeen)) {
            add(RaftMoreAttention.Computers)
        }
    }

/**
 * The More tab wears the same dot as Activity when Settings has something new
 * ([raftMoreAttention]); like Activity, never while More is the selected tab.
 */
internal fun appShellMoreTabShowsDot(attention: Set<RaftMoreAttention>, selected: Boolean): Boolean =
    attention.isNotEmpty() && !selected

/** Wide-rail tab entries (web LeftRail): Settings is pinned to the rail bottom separately. */
internal val AppShellRailTabKeys: List<String> = listOf("home", "activity", "tasks", "members")

/**
 * task #49: the wide-screen rail lists its entries in the web LeftRail order — Search, Chat
 * (Home, with DMs beside it), Activity, Tasks, Members. Entries the web rail does not have keep
 * their relative order after these. Settings stays pinned to the rail bottom separately.
 */
internal val AppShellRailWebOrder: List<String> =
    listOf(
        AppShellRailSearchKey,
        "home",
        "dms",
        "activity",
        "tasks",
        "members",
    )

internal fun <T> List<T>.orderedLikeWebRail(key: (T) -> String): List<T> =
    sortedBy { item ->
        AppShellRailWebOrder.indexOf(key(item)).let { if (it < 0) AppShellRailWebOrder.size else it }
    }

/** Where a conversation search's "Search all" shows its query in the app shell. */
internal enum class AppShellSearchHandoffTarget { SearchTab, RailInlineSearch }

/** The phone bar has the Search tab; the wide rail has its inline Search entry instead. */
internal fun appShellSearchHandoffTarget(navRailActive: Boolean): AppShellSearchHandoffTarget =
    if (navRailActive) AppShellSearchHandoffTarget.RailInlineSearch else AppShellSearchHandoffTarget.SearchTab
