package build.raft.app

import build.raft.app.data.isAppUpdateHomeNotificationId
import build.raft.app.data.raftComputerAttentionPairs
import build.raft.app.data.raftComputerAttentionUnseen
import build.raft.designsystem.components.RaftConversationKindGlyph

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

/**
 * The server's own pages on the phone (raft-design #712, D1a): Members and Settings (the `more`
 * key) open from the server panel as pushed pages that belong to no tab — Back, no bar. (The
 * third, Computers, is a page of its own.) The wide rail lists them as rail roots instead.
 */
internal val AppShellMoreChildTabKeys: Set<String> = setOf("members", "more")

/** The bottom-bar key that shows as selected for [selectedTab]. */
internal fun appShellPhoneBottomBarSelectedKey(selectedTab: String): String {
    val tab = retainedAppShellSelectedTab(selectedTab)
    return if (tab in AppShellMoreChildTabKeys || tab == "settings") "more" else tab
}

/**
 * Where Back goes from [selectedTab] on the phone layout: a server page (Members, Settings)
 * returns to [origin], the page that showed when the server panel opened it; opened any other
 * way (a deep link) it returns to Home. Null means the ordinary shell Back rules apply. The
 * wide rail lists them as rail roots, so it never pops them.
 */
internal fun appShellMoreChildBackTarget(selectedTab: String, navRailActive: Boolean, origin: String? = null): String? =
    if (!navRailActive && retainedAppShellSelectedTab(selectedTab) in AppShellMoreChildTabKeys) {
        appShellServerPageBackTarget(origin)
    } else {
        null
    }

/** A server page's Back: its origin when that is a tab root of the phone bar, else Home. */
internal fun appShellServerPageBackTarget(origin: String?): String =
    origin?.takeIf { it in AppShellPhoneBottomBarKeys && it != AppShellServerTabKey } ?: "home"

/**
 * The origin to remember when the server panel opens a server page while [selectedTab] shows:
 * that tab, or — when a server page is already showing (Settings -> panel -> Members) — the
 * origin it had, so Back still lands where the person started.
 */
internal fun appShellServerPageOrigin(selectedTab: String, currentOrigin: String?): String? {
    val tab = retainedAppShellSelectedTab(selectedTab)
    return if (tab in AppShellMoreChildTabKeys) currentOrigin else tab
}

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
 * root it has no Back and sits above the bottom bar; pushed (channel search) it has Back and
 * covers the bar. A result always opens a pushed page with Back over either.
 *
 * The wide layout's inline Search ([railInline]: the top bar's search field, the Duo rail's
 * Search) is a list-column mode, not a page: like web's desktop search it draws no Back button
 * (Esc / system Back, another tab or the top search field leave it; [showsBack] still hands
 * close to a system side column) and its field takes focus as it opens (artin 2026-10-29).
 */
internal data class HomeSearchPageChrome(
    val showsBack: Boolean,
    val aboveBottomBar: Boolean,
    val showsBackButton: Boolean = showsBack,
    val focusesInputOnOpen: Boolean = false,
)

internal fun homeSearchPageChrome(tabRoot: Boolean, railInline: Boolean = false): HomeSearchPageChrome =
    HomeSearchPageChrome(
        showsBack = !tabRoot,
        aboveBottomBar = tabRoot,
        showsBackButton = !tabRoot && !railInline,
        focusesInputOnOpen = railInline,
    )

/** The wide rail lists Search as its inline entry, not a tab: a selected Search tab moves there. */
internal fun appShellSearchTabMovesToRailSearch(selectedTab: String, navRailActive: Boolean): Boolean =
    navRailActive && selectedTab == AppShellSearchTabKey

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

/**
 * Wide-rail entries, top to bottom, as the web LeftRail lists them (Direction A, #proj-mobile
 * 10-30): Chat (the `home` tab), Activity, Tasks, Members, Computers, Apps. Help and Settings are
 * pinned to the rail bottom separately. Computers and Apps are not shell tabs: they open the
 * pages their Settings rows open ([AppShellRailComputersKey], [AppShellRailAppsKey]).
 */
internal val AppShellRailTabKeys: List<String> =
    listOf("home", "activity", "tasks", "members", AppShellRailComputersKey, AppShellRailAppsKey)

/** The rail's Computers entry: opens the Computers page, as Settings > Computers does. */
internal const val AppShellRailComputersKey = "computers"

/** The rail's Apps entry: opens the Applications page, as Settings > Applications does. */
internal const val AppShellRailAppsKey = "apps"

/** The rail's Help entry in its footer, above Settings: opens the Help menu beside the rail. */
internal const val AppShellRailHelpKey = "help"

/** Rail entries that open a page over the shell instead of selecting a tab; never selected. */
internal val AppShellRailPageKeys: Set<String> = setOf(AppShellRailComputersKey, AppShellRailAppsKey)

/**
 * The rail's icon for [key], the web LeftRail's: raft-ui `ChatIcon` for Chat (drawn through
 * [appShellRailGlyph], this is its fallback), Lucide `list-todo` for Tasks, `monitor` for
 * Computers, `blocks` for Apps, `circle-question-mark` (lucide-react `CircleHelp`) for Help. The
 * phone bar keeps [appShellTabIcon].
 */
internal fun appShellRailTabIcon(key: String): RaftLucideIconName =
    when (key) {
        "home" -> RaftLucideIconName.MessageSquare
        "tasks" -> RaftLucideIconName.ListTodo
        AppShellRailComputersKey -> RaftLucideIconName.Monitor
        AppShellRailAppsKey -> RaftLucideIconName.Blocks
        AppShellRailHelpKey -> RaftLucideIconName.CircleQuestionMark
        else -> appShellTabIcon(key)
    }

/** The raft-ui glyph a rail entry draws instead of its Lucide icon: Chat is `ChatIcon`. */
internal fun appShellRailGlyph(key: String): RaftConversationKindGlyph? =
    if (key == "home") RaftConversationKindGlyph.Chat else null

/**
 * Each rail entry's own attention dot (web LeftRail: Computers and Settings dot separately). An
 * unseen offline/outdated computer dots Computers; an app update (read in Settings > About) dots
 * Settings. Apps has no signal on mobile yet (web's is the admin-review flag of
 * `GET /integrations/permission-updates`), so it never dots. The phone's More tab keeps the
 * combined [appShellMoreTabShowsDot].
 */
internal fun appShellRailShowsAttention(key: String, attention: Set<RaftMoreAttention>, selected: Boolean): Boolean =
    !selected &&
        when (key) {
            AppShellRailComputersKey -> RaftMoreAttention.Computers in attention
            "settings" -> RaftMoreAttention.AppUpdate in attention
            else -> false
        }

/**
 * The rail's entries ([AppShellRailTabKeys]) for [selectedTab]: the web LeftRail's icons
 * ([appShellRailTabIcon]), Activity's unread dot, and Computers' own dot
 * ([appShellRailShowsAttention]). Computers and Apps open pages, so they are never selected.
 */
internal fun appShellRailTabs(
    selectedTab: String,
    labelFor: (String) -> String,
    activityUnreadCount: Int = 0,
    moreAttention: Set<RaftMoreAttention> = emptySet(),
): List<RaftBottomTabItemPayload> =
    appShellBottomTabs(
        selectedKey = retainedAppShellSelectedTab(selectedTab),
        orderedTabKeys = AppShellRailTabKeys,
        activityUnreadCount = activityUnreadCount,
        labelFor = labelFor,
    ).map { item ->
        val selected = item.selected && item.key !in AppShellRailPageKeys
        item.copy(
            iconName = appShellRailTabIcon(item.key),
            selected = selected,
            attentionDot = item.attentionDot || appShellRailShowsAttention(item.key, moreAttention, selected),
        )
    }

/**
 * The rail's footer, web LeftRail's order: Help (selected while its menu is open), then Settings,
 * dotted only for an app update now that Computers has its own entry.
 */
internal fun appShellRailFooterTabs(
    selectedTab: String,
    helpLabel: String,
    settingsLabel: String,
    helpOpen: Boolean,
    moreAttention: Set<RaftMoreAttention> = emptySet(),
): List<RaftBottomTabItemPayload> {
    val settingsSelected = retainedAppShellSelectedTab(selectedTab) == "settings"
    return listOf(
        RaftBottomTabItemPayload(
            key = AppShellRailHelpKey,
            label = helpLabel,
            iconName = appShellRailTabIcon(AppShellRailHelpKey),
            selected = helpOpen,
        ),
        RaftBottomTabItemPayload(
            key = "settings",
            label = settingsLabel,
            iconName = RaftLucideIconName.Settings,
            selected = settingsSelected,
            attentionDot = appShellRailShowsAttention("settings", moreAttention, settingsSelected),
        ),
    )
}

/**
 * The wide-screen rail keeps the phone bar's destinations in the phone bar's order (nav spec
 * 2026-10-21 §5, raft-design responsive.md: "keeps the phone's destinations in the phone's
 * order"): Home (with DMs beside it), Activity, Tasks, Search, then Members, Computers and Apps,
 * which the phone reaches through the server panel / Settings. More's own page is the Settings
 * entry pinned to the rail bottom.
 * Entries not listed keep their relative order after these.
 */
internal val AppShellRailOrder: List<String> =
    listOf(
        "home",
        "dms",
        "activity",
        "tasks",
        AppShellRailSearchKey,
        "members",
        AppShellRailComputersKey,
        AppShellRailAppsKey,
    )

internal fun <T> List<T>.orderedLikePhoneBar(key: (T) -> String): List<T> =
    sortedBy { item ->
        AppShellRailOrder.indexOf(key(item)).let { if (it < 0) AppShellRailOrder.size else it }
    }

/**
 * Search and Activity are already on the shell's own navigation: the phone bar's tabs, or on a
 * wide screen the top bar's search field and the rail's Activity (nav spec 2026-10-21 §5,
 * artin task #8). The Home list then drops its Search and Activity
 * rows so the two-pane layout does not list them twice; Saved is on neither and keeps its row.
 */
internal fun appShellNavCarriesSearchAndActivity(navRailActive: Boolean): Boolean =
    if (navRailActive) {
        (AppShellRailTabKeys + AppShellRailSearchKey).containsAll(listOf("activity", AppShellRailSearchKey))
    } else {
        AppShellPhoneBottomBarKeys.containsAll(listOf("activity", AppShellSearchTabKey))
    }

/** Where a conversation search's "Search all" shows its query in the app shell. */
internal enum class AppShellSearchHandoffTarget { SearchTab, RailInlineSearch }

/** The phone bar has the Search tab; the wide rail has its inline Search entry instead. */
internal fun appShellSearchHandoffTarget(navRailActive: Boolean): AppShellSearchHandoffTarget =
    if (navRailActive) AppShellSearchHandoffTarget.RailInlineSearch else AppShellSearchHandoffTarget.SearchTab

/**
 * Design D1a (#proj-raft-ui task #152, stdrc + artin; raft-design #712): the phone bar's last
 * tab is the current server. It keeps the `more` key, but a press or a long press opens the
 * server panel (服务器面板, a menu) instead of a page: the server list, then the pages that were
 * on More ([RaftServerMenuEntry]). It holds no page, so it is never selected.
 */
internal const val AppShellServerTabKey = "more"

/** The server's pages in the server panel, in order; each opens the page the More row opened. */
internal enum class RaftServerMenuEntry(val key: String) {
    Members("members"),
    Computers(SettingsDestinationComputers),
    Settings("settings"),
    ;

    companion object {
        fun fromKey(key: String): RaftServerMenuEntry? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The More dot moves to the menu row behind it (nav spec §4, D1a): an available app update is
 * read in Settings > About, so it lights Settings; an unseen offline/outdated computer lights
 * Computers.
 */
internal fun RaftMoreAttention.serverMenuEntry(): RaftServerMenuEntry =
    when (this) {
        RaftMoreAttention.AppUpdate -> RaftServerMenuEntry.Settings
        RaftMoreAttention.Computers -> RaftServerMenuEntry.Computers
    }

/** What the current-server tab and its menu show. */
internal data class RaftServerMenuModel(
    /** Every server, in the switcher's order (the Home switcher's items). */
    val servers: List<RaftServerSwitcherItem>,
    /** The server you are in: the tab's avatar and name, the menu's checked row. */
    val currentServer: RaftServerSwitcherItem,
    val entries: List<RaftServerMenuEntry> = RaftServerMenuEntry.entries,
    /** Menu rows that wear the attention pixel. */
    val attentionEntries: Set<RaftServerMenuEntry> = emptySet(),
    /** Another server has unread: the avatar's attention light (as on the Home switcher). */
    val otherServersUnread: Boolean = false,
)

/**
 * The menu model from the Home switcher's own data ([sharedHomeSwitcherItems]) and the More
 * signals ([raftMoreAttention]).
 */
internal fun raftServerMenuModel(
    servers: List<RaftServerSwitcherItem>,
    currentServerId: String,
    currentServerName: String,
    moreAttention: Set<RaftMoreAttention> = emptySet(),
): RaftServerMenuModel =
    RaftServerMenuModel(
        servers = servers,
        currentServer = homeHeaderServerIdentity(currentServerId, currentServerName, servers),
        attentionEntries = moreAttention.mapTo(mutableSetOf()) { it.serverMenuEntry() },
        otherServersUnread = homeServerSwitcherHasOtherUnread(currentServerId, servers),
    )

/**
 * The server's pages in the popover's bottom row (raft-design #741, navigation.md "The server
 * in the apps' desktop mode"). The phone keeps every page: it has no rail. On a wide screen the
 * rail already has Members, Computers and Settings, so the row would only repeat them and the popover draws no row at all (artin, 10-29: "电脑端这里就不用有两个选择").
 */
internal fun raftServerMenuEntriesFor(
    entries: List<RaftServerMenuEntry>,
    wide: Boolean,
    guest: Boolean,
): List<RaftServerMenuEntry> =
    if (!wide) entries else emptyList()

/** The unread count a server row shows: the Home switcher's label, or null. */
internal fun raftServerMenuUnreadLabel(server: RaftServerSwitcherItem): String? =
    homeServerSwitcherBadgePresentation(server).label

/**
 * The dot on the server tab's avatar (Kirby / Jasper 10-22, raft-design #712 review): only
 * "another server has unread", the same as Home's top-bar avatar. What More's dot used to mark
 * (an app update, a computer needing action) stays on its own panel row
 * ([RaftServerMenuModel.attentionEntries]) and does not light the avatar. The tab is never
 * selected, so no selected-tab rule applies. Change the rule here only.
 */
internal fun appShellServerTabShowsAttention(model: RaftServerMenuModel): Boolean = model.otherServersUnread

/** A server switch picked in the server menu: the server to select and the tab to stay on. */
internal data class AppShellServerSwitchPlan(val serverId: String, val tab: String)

/**
 * Picking [pickedServerId] in the server menu while [selectedTab] shows. Null when it is already
 * the current server (the menu just closes). D1a: the switch keeps the tab you are on — on
 * Activity you land on the new server's Activity, not on Home.
 */
internal fun appShellServerMenuSwitchPlan(
    currentServerId: String,
    pickedServerId: String,
    selectedTab: String,
): AppShellServerSwitchPlan? {
    val target = pickedServerId.trim().takeIf { it.isNotEmpty() && it != currentServerId } ?: return null
    return AppShellServerSwitchPlan(serverId = target, tab = selectedTab)
}
