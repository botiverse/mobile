package build.raft.app

import build.raft.app.data.isAppUpdateHomeNotificationId
import build.raft.app.data.raftComputerAttentionPairs
import build.raft.app.data.raftComputerAttentionUnseen
import build.raft.app.settings.AppShellRailSearchKey
import build.raft.app.settings.SettingsDestinationComputers
import build.raft.app.settings.SettingsDestinationConnectedApps
import build.raft.app.settings.SettingsDestinationHandsFeedback
import build.raft.app.settings.SettingsNativeDetailPage
import build.raft.app.settings.retainedAppShellSelectedTab
import build.raft.app.thread.key

/**
 * The phone bar's icon for [key]: its destination's compact icon ([RaftAppDestination]); Activity
 * is the Lucide `activity` glyph, the web Activity nav item's (artin 2026-10-20).
 */
internal fun appShellTabIcon(key: String): RaftLucideIconName =
    RaftAppDestination.fromId(key)?.let { RaftNavPresentation.PhoneBar.iconOf(it).lucide } ?: appShellLegacyTabIcon(key)

/** Keys that are no destination any more: the Slack-nav `dms` tab (task #94). */
internal fun appShellLegacyTabIcon(key: String): RaftLucideIconName =
    when (key) {
        "dms" -> RaftLucideIconName.MessageCircle
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
    RaftNavPresentation.PhoneBar.destinations.map { it.id }

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

/**
 * Shell tabs that can be promoted onto the phone bottom tab bar when `tab_bar_places_v0` is ON,
 * or otherwise open from the server panel as pushed pages with Back.
 */
internal val AppShellPromotablePhoneTabKeys: Set<String> =
    setOf(
        RaftAppDestination.Artifacts.id,
        RaftAppDestination.Members.id,
        RaftAppDestination.Computers.id,
        RaftAppDestination.Apps.id,
    )

/**
 * Whether [tab] is currently a pushed More/Server child page on the phone (carries Back, hides
 * bottom bar) for the active [phoneTabKeys]. Any promotable tab currently on [phoneTabKeys] is a
 * top-level phone bottom tab instead.
 */
internal fun appShellIsMoreChildTab(
    tab: String,
    phoneTabKeys: List<String> = AppShellPhoneBottomBarKeys,
): Boolean {
    val retained = retainedAppShellSelectedTab(tab)
    if (retained == AppShellServerTabKey) return true
    if (retained in phoneTabKeys) return false
    return retained in AppShellMoreChildTabKeys || retained in AppShellPromotablePhoneTabKeys
}

/** The bottom-bar key that shows as selected for [selectedTab]. */
internal fun appShellPhoneBottomBarSelectedKey(
    selectedTab: String,
    phoneTabKeys: List<String> = AppShellPhoneBottomBarKeys,
): String {
    val tab = retainedAppShellSelectedTab(selectedTab)
    if (tab == "dms") return tab
    if (tab in phoneTabKeys && tab != AppShellServerTabKey) return tab
    return if (appShellIsMoreChildTab(tab, phoneTabKeys) || tab == "settings" || tab !in phoneTabKeys) {
        AppShellServerTabKey
    } else {
        tab
    }
}

/**
 * Where Back goes from [selectedTab] on the phone layout: a server page (Members, Settings, or a
 * non-promoted Artifacts/Computers/Apps tab) returns to [origin], the page that showed when the
 * server panel opened it; opened any other way (a deep link) it returns to the primary phone tab.
 * Null means the ordinary shell Back rules apply. The wide rail lists them as rail roots, so it
 * never pops them.
 */
internal fun appShellMoreChildBackTarget(
    selectedTab: String,
    navRailActive: Boolean,
    origin: String? = null,
    phoneTabKeys: List<String> = AppShellPhoneBottomBarKeys,
): String? =
    if (!navRailActive && appShellIsMoreChildTab(selectedTab, phoneTabKeys)) {
        appShellServerPageBackTarget(origin, phoneTabKeys)
    } else {
        null
    }

/** A server page's Back: its origin when that is a tab root of the phone bar, else the bar's first tab. */
internal fun appShellServerPageBackTarget(
    origin: String?,
    phoneTabKeys: List<String> = AppShellPhoneBottomBarKeys,
): String =
    origin?.takeIf { it in phoneTabKeys && it != AppShellServerTabKey } ?: appShellPhonePrimaryTabKey(phoneTabKeys)

/**
 * The origin to remember when the server panel opens a server page while [selectedTab] shows:
 * that tab, or — when a server page is already showing (Settings -> panel -> Members) — the
 * origin it had, so Back still lands where the person started.
 */
internal fun appShellServerPageOrigin(
    selectedTab: String,
    currentOrigin: String?,
    phoneTabKeys: List<String> = AppShellPhoneBottomBarKeys,
): String? {
    val tab = retainedAppShellSelectedTab(selectedTab)
    return if (appShellIsMoreChildTab(tab, phoneTabKeys)) currentOrigin else tab
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

    /**
     * A feedback ticket has a reply not read yet (the unread total over every app, RaftFeedbackUnreadStore):
     * the Feedback row (web `useSettingsPageAttention` / SettingsSidebarList `FeedbackUnreadDot`).
     */
    FeedbackReplies(SettingsDestinationHandsFeedback),

    /**
     * An installed App waits for an owner/admin review (a permission update or prompt-text
     * confirmation, `needsAdminReview` on `GET /integrations/permission-updates`, web
     * `useAppsPageAttention`): dots Apps on the wide rail and the phone server panel.
     */
    AppsReview(SettingsDestinationConnectedApps),
}

/**
 * The More signals from the Home notices the shell already loads (they clear when dismissed):
 * - [RaftMoreAttention.AppUpdate]: the app-update notice.
 * - [RaftMoreAttention.Computers]: a computer the offline-computer or outdated-daemon notice
 *   names is in a state not yet seen ([computersSeen]; raft-design #620 "Attention dots": the
 *   dot marks an unseen change, opening Computers sees it — see data/RaftComputerAttentionSeen.kt).
 * - [RaftMoreAttention.FeedbackReplies]: [feedbackUnreadTotal] (unread feedback replies over every app)
 *   is above 0; reading them clears it.
 * Members has no unread signal today. The More tab dot reads this same set
 * ([appShellMoreTabShowsDot]), so the tab and its rows light and clear together.
 */
internal fun raftMoreAttention(
    homeNotificationIds: List<String>,
    computersSeen: Set<String> = emptySet(),
    feedbackUnreadTotal: Int = 0,
    appsNeedsReview: Boolean = false,
): Set<RaftMoreAttention> =
    buildSet {
        if (homeNotificationIds.any { it.isAppUpdateHomeNotificationId() }) add(RaftMoreAttention.AppUpdate)
        if (raftComputerAttentionUnseen(raftComputerAttentionPairs(homeNotificationIds), computersSeen)) {
            add(RaftMoreAttention.Computers)
        }
        if (feedbackUnreadTotal > 0) add(RaftMoreAttention.FeedbackReplies)
        if (appsNeedsReview) add(RaftMoreAttention.AppsReview)
    }

/**
 * The rows that wear a dot on the wide rail's Settings list: only Feedback, as on the web, whose
 * SettingsSidebarList (the wide Settings list) dots its Feedback row (`FeedbackUnreadDot`) and no
 * other. The phone Settings root dots every [RaftMoreAttention.settingsRowKey].
 */
internal fun settingsWideAttentionRowKeys(attention: Set<RaftMoreAttention>): Set<String> =
    if (RaftMoreAttention.FeedbackReplies in attention) setOf(RaftMoreAttention.FeedbackReplies.settingsRowKey) else emptySet()

/**
 * The More tab wears the same dot as Activity when Settings has something new
 * ([raftMoreAttention]); like Activity, never while More is the selected tab.
 */
internal fun appShellMoreTabShowsDot(attention: Set<RaftMoreAttention>, selected: Boolean): Boolean =
    attention.isNotEmpty() && !selected

/**
 * Wide-rail entries, top to bottom: [RaftNavPresentation.Rail], the web LeftRail's set (Chat,
 * Activity, Tasks, Artifacts, Members, Computers, Apps, Assistant; Help and Settings in its footer).
 */
internal val AppShellRailTabKeys: List<String> = RaftNavPresentation.Rail.destinations.map { it.id }

/** The rail's entries for [selectedTab] ([raftNavItems] on [RaftNavPresentation.Rail]). */
internal fun appShellRailTabs(
    selectedTab: String,
    labelFor: (String) -> String,
    activityUnreadCount: Int = 0,
    moreAttention: Set<RaftMoreAttention> = emptySet(),
    /** The rail page shown inline in the content area (Computers / Apps): it is the selected entry. */
    inlinePageRailId: String? = null,
    /** workspace_artifacts_v0 loaded and on ([appShellArtifactsShown]): the Artifacts entry shows. */
    artifacts: Boolean = false,
    /** The Assistant entry shows (web `useRememberedAssistantShown`, the phone Home row's rule). */
    assistant: Boolean = false,
): List<RaftBottomTabItemPayload> =
    raftNavItems(
        presentation = RaftNavPresentation.Rail,
        ids =
            AppShellRailTabKeys.filter { id ->
                when (id) {
                    RaftAppDestination.Artifacts.id -> artifacts
                    RaftAppDestination.Assistant.id -> assistant
                    else -> true
                }
            },
        selectedKey = inlinePageRailId ?: retainedAppShellSelectedTab(selectedTab),
        labelFor = labelFor,
        badges = RaftNavBadgeInputs(activityUnreadCount, moreAttention),
    )

/** The rail's footer ([RaftNavPresentation.Rail]'s `footer`): Help (selected while its menu is open), Settings. */
internal fun appShellRailFooterTabs(
    selectedTab: String,
    labelFor: (String) -> String,
    helpOpen: Boolean,
    moreAttention: Set<RaftMoreAttention> = emptySet(),
): List<RaftBottomTabItemPayload> =
    raftNavItems(
        presentation = RaftNavPresentation.Rail,
        ids = RaftNavPresentation.Rail.footer.map { it.id },
        selectedKey = retainedAppShellSelectedTab(selectedTab),
        labelFor = labelFor,
        badges = RaftNavBadgeInputs(moreAttention = moreAttention),
        menuOpen = helpOpen,
    )

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
        // web LeftRail: Artifacts after Tasks, before Members.
        RaftAppDestination.Artifacts.id,
        "members",
        RaftAppDestination.Computers.id,
        RaftAppDestination.Apps.id,
        // web RAIL_TAB_IDS: the assistant last.
        RaftAppDestination.Assistant.id,
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

/** The server's pages in the server panel; each opens the corresponding destination. */
internal enum class RaftServerMenuEntry(val key: String) {
    /**
     * Built-in places that can overflow into the Server panel when moved off the phone bottom bar
     * under `tab_bar_places_v0` (never in [RaftDefaultServerMenuEntries] while the flag is OFF).
     */
    Home(RaftAppDestination.Chat.id),
    Activity(RaftAppDestination.Activity.id),
    Tasks(RaftAppDestination.Tasks.id),
    Search(AppShellSearchTabKey),

    /**
     * The Artifacts home (web MobileServerTab: first of the server's pages, behind the rail's gate,
     * workspace_artifacts_v0; guests too). Not a shell tab when off the bar: a pushed page.
     */
    Artifacts("artifacts"),
    Members(RaftAppDestination.Members.id),
    Computers(RaftAppDestination.Computers.id),
    Apps(RaftAppDestination.Apps.id),
    Settings(RaftAppDestination.Settings.id),
    ;

    companion object {
        fun fromKey(key: String): RaftServerMenuEntry? = entries.firstOrNull { it.key == key }
    }
}

/** The default flag-off server panel entries, in canonical order. */
internal val RaftDefaultServerMenuEntries: List<RaftServerMenuEntry> =
    listOf(
        RaftServerMenuEntry.Artifacts,
        RaftServerMenuEntry.Members,
        RaftServerMenuEntry.Computers,
        RaftServerMenuEntry.Apps,
        RaftServerMenuEntry.Settings,
    )

/**
 * The More dot moves to the menu row behind it (nav spec §4, D1a): an available app update is
 * read in Settings > About and a feedback reply in Settings > Feedback, so they light Settings
 * (web MobileServerTab `useSettingsPageAttention`); an unseen offline/outdated computer lights
 * Computers.
 */
internal fun RaftMoreAttention.serverMenuEntry(): RaftServerMenuEntry =
    when (this) {
        RaftMoreAttention.AppUpdate, RaftMoreAttention.FeedbackReplies -> RaftServerMenuEntry.Settings
        RaftMoreAttention.Computers -> RaftServerMenuEntry.Computers
        RaftMoreAttention.AppsReview -> RaftServerMenuEntry.Apps
    }

/** What the current-server tab and its menu show. */
internal data class RaftServerMenuModel(
    /** Every server, in the switcher's order (the Home switcher's items). */
    val servers: List<RaftServerSwitcherItem>,
    /** The server you are in: the tab's avatar and name, the menu's checked row. */
    val currentServer: RaftServerSwitcherItem,
    val entries: List<RaftServerMenuEntry> = RaftDefaultServerMenuEntries,
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
 * in the apps' desktop mode"). The phone keeps every page: it has no rail, and its Settings has
 * no Computers or Apps row (artin 2026-11-05, dm task #17; [settingsHomeRowKeys]). A guest has no
 * Apps page (web MobileServerTab): their Applications row stays in Settings. On a wide screen the
 * rail already has Members, Computers, Apps and Settings, so the row would only repeat them and
 * the popover draws no row at all (artin, 10-29: "电脑端这里就不用有两个选择").
 */
internal fun raftServerMenuEntriesFor(
    entries: List<RaftServerMenuEntry>,
    wide: Boolean,
    guest: Boolean,
    /** The server's workspace_artifacts_v0, already loaded and on (web `useArtifactsRailShown`). */
    artifacts: Boolean = false,
): List<RaftServerMenuEntry> {
    val shown = if (artifacts) entries else entries - RaftServerMenuEntry.Artifacts
    return when {
        wide -> emptyList()
        guest -> shown - RaftServerMenuEntry.Apps
        else -> shown
    }
}

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

/**
 * Dismiss an open composer keyboard when switching to another app-shell tab (never on Search,
 * where a re-tap intentionally focuses the search box).
 */
internal fun shouldDismissKeyboardOnAppShellTabSwitch(nextTab: String): Boolean =
    nextTab != AppShellSearchTabKey

/**
 * Dismiss an open composer keyboard when tapping a rail tab that changes the shown surface (another
 * tab or dismissing an inline secondary page like Search/Computers/Apps), while leaving inline
 * Search itself and re-taps on the already-visible tab alone.
 */
internal fun shouldDismissKeyboardOnRailTabSelect(
    tappedKey: String,
    inlineSecondaryDestination: String?,
    selectedTab: String,
): Boolean {
    if (tappedKey == AppShellRailSearchKey) return false
    if (inlineSecondaryDestination != null) return true
    return retainedAppShellSelectedTab(selectedTab) != retainedAppShellSelectedTab(tappedKey)
}

/**
 * Dismiss an open composer keyboard when opening a different inline rail page (Computers, Apps,
 * Artifacts, Assistant), while leaving re-taps on the already-active inline page (e.g. typing in
 * Assistant or Computers search) and unrouted pages alone.
 */
internal fun shouldDismissKeyboardOnRailInlinePageSelect(
    inlinePage: String?,
    currentInlineSecondaryDestination: String?,
): Boolean = inlinePage != null && currentInlineSecondaryDestination != inlinePage
