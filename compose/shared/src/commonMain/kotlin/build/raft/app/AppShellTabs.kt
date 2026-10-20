package build.raft.app


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
        AppShellSearchActionKey -> RaftLucideIconName.Search
        else -> RaftLucideIconName.Circle
    }

/**
 * Phone bottom bar (xxchan 2026-10-20): 主页 · 动态 · 任务 · 更多 as tabs, plus Search as a
 * separate action button beside them. Members and Settings moved behind More; they stay shell
 * child pages (deep links, the wide rail and old launch values still select them directly) but
 * are shown as pages pushed from More, with a back button.
 */
internal val AppShellPhoneBottomTabKeys: List<String> = listOf("home", "activity", "tasks", "more")

/**
 * Bottom-bar action key for Search. It is not a tab: a tap opens the search page on top of
 * the current tab, which stays selected. Never a valid selected-tab value.
 */
internal const val AppShellSearchActionKey = "search"

/** Child pages pushed from the More list; the bottom bar keeps More selected while they show. */
internal val AppShellMoreChildTabKeys: Set<String> = setOf("members", "settings")

/** The bottom-bar key that shows as selected for [selectedTab]. */
internal fun appShellPhoneBottomBarSelectedKey(selectedTab: String): String {
    val tab = retainedAppShellSelectedTab(selectedTab)
    return if (tab in AppShellMoreChildTabKeys) "more" else tab
}

/**
 * Where Back goes from [selectedTab] on the phone layout: a page pushed from More returns to
 * More; null means the ordinary shell Back rules apply. The wide rail lists Members and
 * Settings as rail roots, so it never pops them to More.
 */
internal fun appShellMoreChildBackTarget(selectedTab: String, navRailActive: Boolean): String? =
    if (!navRailActive && retainedAppShellSelectedTab(selectedTab) in AppShellMoreChildTabKeys) "more" else null

/**
 * Activity unread on a tab icon is an attention dot, never a number (raft-design MobileNav /
 * Badge), and the selected tab never wears it (web MainLayout `ActivityTabUnreadDot`).
 */
internal fun appShellActivityTabShowsDot(unreadCount: Int, selected: Boolean): Boolean =
    unreadCount > 0 && !selected

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
