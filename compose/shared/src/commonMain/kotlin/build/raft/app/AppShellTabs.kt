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
        AppShellSearchActionKey -> RaftLucideIconName.Search
        else -> RaftLucideIconName.Circle
    }

/**
 * Phone bottom bar (stdrc 2026-10-21): 主页 · 动态 · 任务 · 搜索 · 更多 in one full-width edge
 * bar. Search holds the slot left of More but stays an action ([AppShellSearchActionKey]): a tap opens
 * search over the current tab and it is never the selected item. More's root page is Settings;
 * Members is pushed from it (back returns to More). Members and Settings stay shell child pages
 * so the wide rail, deep links and old launch values still select them directly.
 */
internal val AppShellPhoneBottomBarKeys: List<String> =
    listOf("home", "activity", "tasks", AppShellSearchActionKey, "more")

/** The real tabs among [AppShellPhoneBottomBarKeys] (everything but the Search action). */
internal val AppShellPhoneBottomTabKeys: List<String> = AppShellPhoneBottomBarKeys - AppShellSearchActionKey

/**
 * Bottom-bar action key for Search. It is not a tab: a tap opens the search page on top of
 * the current tab, which stays selected. Never a valid selected-tab value.
 */
internal const val AppShellSearchActionKey = "search"

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
 * Activity unread on a tab icon is an attention dot, never a number (raft-design MobileNav /
 * Badge), and the selected tab never wears it (web MainLayout `ActivityTabUnreadDot`).
 */
internal fun appShellActivityTabShowsDot(unreadCount: Int, selected: Boolean): Boolean =
    unreadCount > 0 && !selected

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
