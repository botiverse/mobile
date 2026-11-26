package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.key
import build.raft.app.data.RaftFreshnessTrigger
import build.raft.app.data.SharedAuthGateIntent
import build.raft.app.data.SharedAuthReadyPhase
import build.raft.app.data.SharedAuthGateState
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeShellStore
import build.raft.app.data.SharedHomeState
import build.raft.app.data.RAFT_FRESHNESS_CONNECTED_FOREGROUND_SKIP_MILLIS
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.realtime.RaftRealtimeConnectionCauses
import build.raft.app.data.realtime.RaftRealtimeNotifyContract
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.size
import build.raft.app.compat.json.JSONObject
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.map
import build.raft.app.data.map
import kotlin.time.Clock

private const val HomeSurfaceVisibleResumeDelayMs = 48L
private const val DiagnosticsDeepLinkIdKey = "slockDiagnosticsDeepLinkId"
private const val DiagnosticsDeepLinkAutoCloseMsKey = "slockDiagnosticsDeepLinkAutoCloseMs"
private val AppShellChildPagePrewarmOrder = listOf("settings", "tasks", "members")

/** Rail-only inline destination key (task #13 three-pane): Search renders in the AppShell
 *  middle pane under the wide LeftRail; it is not a persisted tab key. */
internal const val AppShellRailSearchKey = "rail_search"

/** Saved opened from the Home sidebar in desktop mode: an inline list pane, like rail Search. */
internal const val AppShellRailSavedKey = "rail_saved"
internal const val AppShellRailDraftsKey = "rail_drafts"

/**
 * artin 11-01: the rail's page destinations (Computers, Apps) open in the shell's content area
 * beside the rail on the wide layout, like Chat / Activity / Tasks / Members (web LeftRail), not as
 * a page over the shell. Inline keys, like [AppShellRailSearchKey]; not persisted tab keys.
 */
internal const val AppShellRailComputersKey = "rail_computers"
internal const val AppShellRailAppsKey = "rail_apps"
internal const val AppShellRailArtifactsKey = "rail_artifacts"

/** The rail's Assistant: the assistant's page beside the rail (web LeftRail's `assistant` rail mode). */
internal const val AppShellRailAssistantKey = "rail_assistant"

/** The inline key a rail page destination opens as on the wide layout, or null. */
internal fun appShellRailInlinePageKey(destination: RaftAppDestination?): String? =
    when (destination) {
        RaftAppDestination.Computers -> AppShellRailComputersKey
        RaftAppDestination.Apps -> AppShellRailAppsKey
        RaftAppDestination.Artifacts -> AppShellRailArtifactsKey
        RaftAppDestination.Assistant -> AppShellRailAssistantKey
        else -> null
    }

/** The rail entry an inline page key highlights ("computers" / "apps"), or null for none / a list pane. */
internal fun appShellRailInlinePageRailId(railInlineSecondaryDestination: String?): String? =
    when (railInlineSecondaryDestination) {
        AppShellRailComputersKey -> RaftAppDestination.Computers.id
        AppShellRailAppsKey -> RaftAppDestination.Apps.id
        AppShellRailArtifactsKey -> RaftAppDestination.Artifacts.id
        AppShellRailAssistantKey -> RaftAppDestination.Assistant.id
        else -> null
    }

/**
 * The page an inline page key shows in the content area: the page its Settings row shows in the
 * wide pane. [timeContext] is the shell's time display, for a page that shows messages (the
 * assistant's DM).
 */
internal fun appShellRailInlinePage(
    railInlineSecondaryDestination: String?,
    storeScope: RaftShellStoreScope,
    timeContext: AppShellThreadTimeContext? = null,
): Pair<String, JSONObject>? =
    when (railInlineSecondaryDestination) {
        AppShellRailComputersKey -> settingsWideDetailPage(SettingsDestinationComputers, storeScope)
        AppShellRailAppsKey -> settingsWideDetailPage(SettingsDestinationConnectedApps, storeScope)
        // Its head without Back (the rail is the way out), as Computers.
        AppShellRailArtifactsKey -> ARTIFACTS_HOME_PAGE to artifactsHomePageData(storeScope)
        // Its head without Back too; its gear and its DM as on the phone's page.
        AppShellRailAssistantKey -> ASSISTANT_PAGE to assistantPageData(storeScope, timeContext)
        else -> null
    }

/** An inline rail destination that is a list pane (Search, Saved, Drafts) hosting the shared detail beside it. */
private fun appShellRailInlineListPane(railInlineSecondaryDestination: String?): Boolean =
    railInlineSecondaryDestination != null && appShellRailInlinePageRailId(railInlineSecondaryDestination) == null

/**
 * The phone bottom bar (stdrc 2026-10-21): Home · Activity · Tasks · Search · More for
 * [selectedTab]. Search is a tab left of More; a page pushed from More (Members) and Settings
 * itself keep More selected.
 */
internal fun appShellPhoneBottomTabs(
    selectedTab: String,
    homeLabel: String,
    activityLabel: String,
    searchLabel: String,
    tasksLabel: String,
    moreLabel: String,
    activityUnreadCount: Int = 0,
    moreAttention: Set<RaftMoreAttention> = emptySet(),
    /**
     * D1a: the last tab is the current server — its name as the label (also what the iOS system
     * bar shows), the server glyph, never selected, and the avatar dot
     * ([appShellServerTabShowsAttention]).
     * Null (no server loaded yet) keeps the plain More tab.
     */
    serverTab: RaftServerMenuModel? = null,
    /** The server tab's screen-reader label ("Current server: Raft HQ"). */
    serverTabAccessibilityLabel: String? = null,
    /** Dynamic ordered keys when `tab_bar_places_v0` is ON (default: fixed [AppShellPhoneBottomBarKeys]). */
    orderedTabKeys: List<String> = AppShellPhoneBottomBarKeys,
    artifactsLabel: String = "",
    membersLabel: String = "",
    computersLabel: String = "",
    appsLabel: String = "",
): List<RaftBottomTabItemPayload> =
    appShellBottomTabs(
        selectedKey = appShellPhoneBottomBarSelectedKey(selectedTab, orderedTabKeys),
        orderedTabKeys = orderedTabKeys,
        activityUnreadCount = activityUnreadCount,
        moreAttention = moreAttention,
        labelFor = { key ->
            when (key) {
                "home" -> homeLabel
                "activity" -> activityLabel
                AppShellSearchTabKey -> searchLabel
                "tasks" -> tasksLabel
                RaftAppDestination.Artifacts.id -> artifactsLabel.ifBlank { key.uppercase() }
                RaftAppDestination.Members.id -> membersLabel.ifBlank { key.uppercase() }
                RaftAppDestination.Computers.id -> computersLabel.ifBlank { key.uppercase() }
                RaftAppDestination.Apps.id -> appsLabel.ifBlank { key.uppercase() }
                "more" -> moreLabel
                else -> key.uppercase()
            }
        }
    ).map { item ->
        if (serverTab == null || item.key != AppShellServerTabKey) {
            item
        } else {
            item.copy(
                label = serverTab.currentServer.name.ifBlank { moreLabel },
                iconName = RaftLucideIconName.Server,
                // It opens the server panel, not a page: never selected (raft-design #712).
                selected = false,
                attentionDot = appShellServerTabShowsAttention(serverTab),
                // No count on iOS (artin 10-23): the panel's rows show other servers' unread.
                opensPanel = true,
                accessibilityLabel = serverTabAccessibilityLabel,
                avatarSource = serverTab.currentServer.toServerSwitcherAvatarPresentation().avatarWireValue,
                avatarFallbackLabel = serverTab.currentServer.toServerSwitcherAvatarPresentation().fallbackLabel,
            )
        }
    }

/**
 * task #94: the phone bar's payloads for the ordered tab keys: [raftNavItems] on
 * [RaftNavPresentation.PhoneBar], so labels' order, icons and dots come from each key's
 * [RaftAppDestination] (Home=house, Activity=activity, Tasks=square-check, More=ellipsis; the
 * legacy `dms` keeps its message circle).
 *
 * This list is the single source of truth for every bottom-bar renderer, including the
 * host-drawn iOS UITabBar (RaftNativeTabBarBridge): a key added to the ordered tab keys
 * appears there with its label and icon. Activity unread is an attention dot, hidden while
 * Activity is the selected tab ([appShellActivityTabShowsDot]); More wears the same dot when
 * Settings has something new ([appShellMoreTabShowsDot]); tabs carry no numbers.
 */
internal fun appShellBottomTabs(
    selectedKey: String,
    orderedTabKeys: List<String>,
    activityUnreadCount: Int = 0,
    moreAttention: Set<RaftMoreAttention> = emptySet(),
    labelFor: (String) -> String
): List<RaftBottomTabItemPayload> =
    raftNavItems(
        presentation = RaftNavPresentation.PhoneBar,
        ids = orderedTabKeys,
        selectedKey = selectedKey,
        labelFor = labelFor,
        badges = RaftNavBadgeInputs(activityUnreadCount, moreAttention),
    )

// task #94: valid app-shell tab keys = legacy set plus the Slack-aligned set. Slack keys only
// enter live tab state while the Slack-nav gate is on (or a persisted order exists), so the
// union keeps legacy clamping behavior unchanged while making the Slack keys first-class for
// child-page lookup, tap normalization and retained-tab bookkeeping.
internal val retainedAppShellTabKeys =
    listOf("home", "tasks", "members", "settings") +
        listOf(
            "home",
            "dms",
            "activity",
            "more",
            AppShellSearchTabKey,
            RaftAppDestination.Artifacts.id,
            RaftAppDestination.Computers.id,
            RaftAppDestination.Apps.id,
        )

internal fun retainedAppShellSelectedTab(selectedTab: String): String =
    selectedTab.takeIf { it in retainedAppShellTabKeys } ?: "home"

/**
 * task #13: the shared adaptive detail slot lives inside adaptiveSplitListDetail, which is
 * hosted by the Home surface (no pushed secondary page), the Activity rail tab, or an inline
 * rail secondary destination (Search). Any other destination — or a backgrounded host —
 * suspends the detail (ParentVisibilityChanged).
 */
internal fun appShellSharedDetailSurfaceVisible(
    pageVisible: Boolean,
    selectedTab: String,
    selectedHomeDestination: String?,
    railInlineSecondaryDestination: String?,
    tasksDetailColumnOpen: Boolean = false,
): Boolean =
    pageVisible &&
        (
            (retainedAppShellSelectedTab(selectedTab) == "home" && selectedHomeDestination == null) ||
                retainedAppShellSelectedTab(selectedTab) == "activity" ||
                // task #33: the Slack-nav DMs tab hosts the same list + detail split.
                retainedAppShellSelectedTab(selectedTab) == "dms" ||
                // task #34: the Tasks page hosts the detail in its task column while a task
                // opened from the page is showing.
                (retainedAppShellSelectedTab(selectedTab) == "tasks" && tasksDetailColumnOpen) ||
                appShellRailInlineListPane(railInlineSecondaryDestination)
        )

/**
 * task #33: the DMs tab only shows a DM (or a reply thread inside a DM) in its detail pane. A
 * channel selected on Home stays Home's; next to the DMs list it renders the empty placeholder
 * instead of a channel composer.
 */
internal fun appShellDmsTabShowsDetail(surfaceKind: String?, parentSurfaceKind: String?): Boolean =
    surfaceKind == RaftThreadSurfaceKind.DirectMessage ||
        (surfaceKind == RaftThreadSurfaceKind.ReplyThread && parentSurfaceKind == RaftThreadSurfaceKind.DirectMessage)

/**
 * task #13 two-step Back contract: while a rail surface (Activity tab or an inline secondary
 * destination like Search) is showing a shared detail selection, the first Back/close must
 * dismiss the detail; only a detail-free surface exits the destination or falls back to Home.
 */
internal fun appShellRailSurfaceBackDismissesDetailFirst(
    selectedTab: String,
    railInlineSecondaryDestination: String?,
    selectionPresent: Boolean,
    tasksDetailColumnOpen: Boolean = false,
): Boolean =
    selectionPresent &&
        (
            appShellRailInlineListPane(railInlineSecondaryDestination) ||
                retainedAppShellSelectedTab(selectedTab) == "activity" ||
                retainedAppShellSelectedTab(selectedTab) == "dms" ||
                (retainedAppShellSelectedTab(selectedTab) == "tasks" && tasksDetailColumnOpen)
        )

/**
 * task #34: on a wide desktop-mode screen a task opened from the Tasks page shows its thread in a
 * column beside the list (web TasksRoute), so the list (filters, scroll) stays put and in use.
 * [columnActive] is set by an open from the page and kept while the user navigates inside the
 * detail (thread → parent channel advances the selection generation); it is cleared when the
 * column closes, the selection ends, the user leaves the Tasks tab, or the wide layout goes away
 * — so a conversation selected elsewhere (Home, Activity, Search) never opens beside the list.
 */
internal fun appShellTasksDetailColumnOpen(
    desktopModeOnLargeScreen: Boolean,
    dualPane: Boolean,
    selectedTab: String,
    selectionPresent: Boolean,
    columnActive: Boolean,
): Boolean =
    desktopModeOnLargeScreen &&
        dualPane &&
        retainedAppShellSelectedTab(selectedTab) == "tasks" &&
        selectionPresent &&
        columnActive

/** task #34: the task column's ownership ends once any of these no longer holds. */
internal fun appShellTasksDetailColumnStaysActive(
    desktopModeOnLargeScreen: Boolean,
    dualPane: Boolean,
    selectedTab: String,
    selectionPresent: Boolean,
): Boolean =
    desktopModeOnLargeScreen &&
        dualPane &&
        retainedAppShellSelectedTab(selectedTab) == "tasks" &&
        selectionPresent

/**
 * LS-07 / web TasksRoute: whether the Tasks tab may host a pending task (no conversation yet,
 * [TaskOpenSurface.Pending]) inside its right-hand task column beside the list.
 */
internal fun appShellTasksPendingColumnAllowed(
    desktopModeOnLargeScreen: Boolean,
    dualPane: Boolean,
    selectedTab: String,
    railInlineSecondaryDestination: String? = null,
): Boolean =
    desktopModeOnLargeScreen &&
        dualPane &&
        retainedAppShellSelectedTab(selectedTab) == "tasks" &&
        railInlineSecondaryDestination == null

/**
 * LS-07 / web TasksRoute: whether the Tasks page's right-hand task column is currently showing a
 * pending task (no conversation yet), without needing an adaptive-workspace conversation selection.
 */
internal fun appShellTasksPendingColumnOpen(
    desktopModeOnLargeScreen: Boolean,
    dualPane: Boolean,
    selectedTab: String,
    pendingSurfaceOpen: Boolean,
    railInlineSecondaryDestination: String? = null,
): Boolean =
    appShellTasksPendingColumnAllowed(
        desktopModeOnLargeScreen = desktopModeOnLargeScreen,
        dualPane = dualPane,
        selectedTab = selectedTab,
        railInlineSecondaryDestination = railInlineSecondaryDestination,
    ) && pendingSurfaceOpen

/**
 * task #13 destination preservation: once the wide rail (or an inline rail destination like
 * Search) has let the user reach Activity — a rail-only key absent from the legacy ordered
 * tab set — narrowing back to compact must not silently evict that live selection to Home.
 * The Activity key stays allowed for the rest of the shell session once reachable; unrelated
 * keys still clamp to Home exactly as before.
 */
internal fun appShellAllowedTabKeys(
    tabKeys: List<String>,
    railActive: Boolean,
    railInlineSecondaryDestination: String?,
    activityReachedThisSession: Boolean,
): List<String> =
    if (railActive || railInlineSecondaryDestination != null || activityReachedThisSession) {
        if ("activity" in tabKeys) tabKeys else tabKeys + "activity"
    } else {
        tabKeys
    }

internal fun shouldHandleAppShellTabSelection(currentTab: String, nextTab: String): Boolean =
    retainedAppShellSelectedTab(currentTab) != retainedAppShellSelectedTab(nextTab)

/**
 * task #45: whether the ON→OFF transition should clear the rail-only transient
 * surfaces (inline secondary destination, slack-nav More menu) exactly once.
 *
 * Pure decision for the LaunchedEffect in the shell composition: the cleanup is
 * edge-triggered — it runs only when `desktopModeOnLargeScreen` flips true→false
 * while armed. While OFF the user may reopen those surfaces, so a persistent
 * predicate would wipe them; a cold start that begins OFF must not clear state
 * it never created. Returns true when the caller should reset
 * `railInlineSecondaryDestination` now, and the next
 * armed latch value.
 */
internal fun desktopModeOffCleanupDecision(
    desktopModeOnLargeScreen: Boolean,
    cleanupArmed: Boolean,
): Pair<Boolean, Boolean> =
    if (desktopModeOnLargeScreen) {
        false to true
    } else if (cleanupArmed) {
        true to false
    } else {
        false to false
    }

internal fun shouldDismissHomeNotificationCenterForAppShellTabSelection(currentTab: String, targetTab: String): Boolean =
    retainedAppShellSelectedTab(currentTab) == "home" &&
        retainedAppShellSelectedTab(targetTab) != "home"

internal data class AppShellLaunchSelection(
    val selectedTab: String = "home",
)

internal data class DiagnosticsDeepLinkRequest(
    val id: String,
    val autoCloseMs: Long = 0L
)

internal fun JSONObject.appShellLaunchSelection(): AppShellLaunchSelection {
    return AppShellLaunchSelection(selectedTab = raftAppShellLaunchTab(optString(AppShellInitialTabKey)))
}

internal fun JSONObject.diagnosticsDeepLinkRequest(): DiagnosticsDeepLinkRequest? {
    val id = optString(DiagnosticsDeepLinkIdKey).takeIf { it.isNotBlank() } ?: return null
    return DiagnosticsDeepLinkRequest(
        id = id,
        autoCloseMs = optLong(DiagnosticsDeepLinkAutoCloseMsKey, 0L).coerceAtLeast(0L)
    )
}

internal fun appShellActiveChildPagerTab(
    selectedTab: String,
    childPageTabKeys: List<String> = AppShellChildPageTabKeys
): String? =
    retainedAppShellSelectedTab(selectedTab).takeIf { it in childPageTabKeys }

// V2 keepalive stack: the ordered set of child tabs to keep composed (alive) at once.
// Empty when there is no active child tab (non-child surfaces render directly). The
// active tab is always included even if [mountedTabs] is momentarily stale, so it can
// never fail to render. [childPageTabKeys] is the active ordered key set (legacy while the
// Slack-nav gate is off, Slack-aligned otherwise).
internal fun appShellRetainedChildTabs(
    activeChildTab: String?,
    mountedTabs: Set<String>,
    childPageTabKeys: List<String> = AppShellChildPageTabKeys
): List<String> =
    if (activeChildTab == null) {
        emptyList()
    } else {
        (childPageTabKeys.filter { it in mountedTabs } + activeChildTab).distinct()
    }

internal fun appShellChildPagePrewarmOrder(
    activeChildTab: String?,
    retainedTabs: Set<String>,
    prewarmedTabs: Set<String>
): List<String> =
    AppShellChildPagePrewarmOrder.filter { tab ->
        tab != activeChildTab && tab !in retainedTabs && tab !in prewarmedTabs
    }

internal fun appShellChildPageRendersContent(active: Boolean, retained: Boolean): Boolean =
    active || retained

/**
 * Whether an app-shell child tab's content runs as active. [placedActive] is the tab pager's
 * own placement decision (the child page's `LocalRaftPageActive`), which is synchronous with the
 * selection; the selection mirrored into [AppShellChildTabEntry] trails it by a frame.
 */
internal fun appShellChildTabContentActive(parentVisible: Boolean, placedActive: Boolean): Boolean =
    parentVisible && placedActive

internal fun appShellChildTabIsActive(
    parentVisible: Boolean,
    selectedTab: String,
    tab: String
): Boolean = parentVisible && retainedAppShellSelectedTab(selectedTab) == tab

internal fun appShellNextChildTabSwitchEpoch(currentTab: String, nextTab: String, currentEpoch: Int): Int =
    if (retainedAppShellSelectedTab(currentTab) == retainedAppShellSelectedTab(nextTab)) {
        currentEpoch
    } else {
        currentEpoch + 1
    }

internal fun retainedHomeSecondaryDestination(destination: String?): String? =
    destination?.takeIf { it in homeSecondaryDestinationKeys }

// task #383: the Activity destination does not self-refresh on app foreground
// (its "own lifecycle effects" only fire on Kuikly push/pop), so the shell
// resume path must route a ForegroundRefresh to it. Saved/Search keep the old
// contract (their surfaces still own their resume refresh).
internal fun homeDestinationRefreshesOnLifecycleResume(destination: String?): Boolean =
    retainedHomeSecondaryDestination(destination).let { it == null || it == HomeDestinationActivity }

/**
 * How the parent detail pane gives up its space when Focus mode owns the row.
 *
 * task #180 P0: the pane used to collapse with `Modifier.weight(0f)`, which is **illegal** in
 * Compose -- it throws `IllegalArgumentException("invalid weight 0.0")` and crashed the app the
 * moment Focus was entered. A zero WIDTH is legal, so Focus collapses the pane by width and the
 * pane takes no weight at all.
 *
 * Exposed as a pure description (not a Modifier) so tests can pin the invariant: when focused the
 * pane is zero-width and carries no weight, and the weight used anywhere else is always > 0.
 */
internal data class RaftParentPaneLayout(
    val widthDp: Float?,
    val weight: Float?,
)

internal fun raftParentPaneLayout(focused: Boolean): RaftParentPaneLayout =
    if (focused) {
        RaftParentPaneLayout(widthDp = 0f, weight = null)
    } else {
        RaftParentPaneLayout(widthDp = null, weight = RAFT_PARENT_PANE_WEIGHT)
    }

/** The only weight the parent pane may ever take. Must be > 0 -- Compose rejects zero. */
internal const val RAFT_PARENT_PANE_WEIGHT: Float = 1f

internal fun appShellAppearanceGeneration(
    pageVisibleTick: Int,
    initialAuthRestoreTick: Int
): Int = pageVisibleTick + initialAuthRestoreTick

internal fun appShellAppearanceShouldRefresh(
    appearanceGeneration: Int,
    pageVisibleTick: Int,
    selectedTab: String,
    selectedHomeDestination: String?,
    hasRetainedHomeContent: Boolean
): Boolean {
    if (appearanceGeneration <= 0) return false
    if (pageVisibleTick > 0) return true
    // A new pager can attach to process-retained Home state after missing the native resume event.
    return retainedAppShellSelectedTab(selectedTab) == "home" &&
        selectedHomeDestination == null &&
        hasRetainedHomeContent
}

internal fun appShellLifecycleCanRefreshVisibleRoute(
    pageVisible: Boolean,
    lifecycleEvent: String
): Boolean =
    pageVisible &&
        (
            lifecycleEvent == RaftRealtimeNotifyContract.LifecycleVisibleResume ||
                lifecycleEvent == RaftRealtimeNotifyContract.LifecycleVisibleShow
            )

internal data class AppShellForegroundResumePlan(
    val refreshVisibleRoute: Boolean,
    val refreshActivityUnreadSummary: Boolean,
    val refreshServerMembers: Boolean
)

internal fun appShellForegroundResumePlan(
    pageVisible: Boolean,
    selectedTab: String,
    selectedHomeDestination: String?
): AppShellForegroundResumePlan? {
    if (!pageVisible) return null
    return when (retainedAppShellSelectedTab(selectedTab)) {
        "home" ->
            when {
                selectedHomeDestination.isNullOrBlank() ->
                    AppShellForegroundResumePlan(
                        refreshVisibleRoute = true,
                        refreshActivityUnreadSummary = true,
                        refreshServerMembers = true
                    )
                // task #383: Activity destination joins the visible-route
                // refresh; unread summary and members stay scoped to Home.
                retainedHomeSecondaryDestination(selectedHomeDestination) == HomeDestinationActivity ->
                    AppShellForegroundResumePlan(
                        refreshVisibleRoute = true,
                        refreshActivityUnreadSummary = false,
                        refreshServerMembers = false
                    )
                else ->
                    null
            }
        else ->
            AppShellForegroundResumePlan(
                refreshVisibleRoute = true,
                refreshActivityUnreadSummary = false,
                refreshServerMembers = false
            )
    }
}

internal enum class AppShellVisibleRefreshSignal {
    Lifecycle,
    PageDidAppear
}

internal data class AppShellVisibleRefreshKey(
    val appearanceGeneration: Int,
    val target: String
)

/**
 * Hands f6c3f3a6: a process foreground this long after the gate's last refresh always refreshes
 * the visible route, whatever the appearance bookkeeping says. Wall clock: the monotonic clock
 * does not advance while an iOS device sleeps, and a suspension is exactly that.
 */
internal const val AppShellResumeStaleRefreshMillis: Long = 60_000L

internal class AppShellVisibleRefreshGate(
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val staleRefreshMillis: Long = AppShellResumeStaleRefreshMillis,
    private val connectedForegroundSkipMillis: Long = RAFT_FRESHNESS_CONNECTED_FOREGROUND_SKIP_MILLIS,
    private val liveConnectionToken: (serverId: String) -> Long? = RaftRealtimeConnectionCauses::liveConnectionToken,
) {
    private var lastRefreshAtMillis: Long? = null
    private var lastRefreshServerId: String? = null
    private var lastRefreshTarget: String? = null
    private var lastRefreshConnectionToken: Long? = null

    /** Why the last [shouldRefresh] refreshed or skipped, for the shell's diagnostics line. */
    var lastDecisionCause: String = "none"
        private set

    private val seenSignalsByKey =
        mutableMapOf<AppShellVisibleRefreshKey, MutableSet<AppShellVisibleRefreshSignal>>()
    private val keyOrder = mutableListOf<AppShellVisibleRefreshKey>()
    private val retiredThroughGenerationByTarget = mutableMapOf<String, Int>()
    private var pendingLifecycleGeneration: Int? = null
    // Process foreground is the refresh authority. PageDidAppear remains a fallback when a
    // newly observed process epoch reached this mounted shell while its pager was still hidden.
    private var latestProcessForegroundEpoch: Long? = null
    private var refreshedProcessForegroundEpoch: Long? = null

    fun observeLifecycleForegroundEpoch(foregroundEpoch: Long) {
        if (foregroundEpoch <= 0L) return
        latestProcessForegroundEpoch =
            maxOf(latestProcessForegroundEpoch ?: Long.MIN_VALUE, foregroundEpoch)
    }

    /** Records an out-of-band silent catch-up (e.g. RaftFreshnessSignal Reconnect/NetworkRestored) for [target]. */
    fun recordFreshCheck(serverId: String?, target: String) {
        val normalizedServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
        lastRefreshAtMillis = nowEpochMillis()
        lastRefreshServerId = normalizedServerId
        lastRefreshTarget = target
        lastRefreshConnectionToken = normalizedServerId?.let(liveConnectionToken)
    }

    fun onLifecycleHidden(
        appearanceGeneration: Int,
        foregroundEpoch: Long? = null
    ) {
        foregroundEpoch?.let(::observeLifecycleForegroundEpoch)
        pendingLifecycleGeneration =
            maxOf(
                pendingLifecycleGeneration ?: Int.MIN_VALUE,
                appearanceGeneration + 1
            )
    }

    fun shouldRefresh(
        signal: AppShellVisibleRefreshSignal,
        appearanceGeneration: Int,
        target: String,
        foregroundEpoch: Long? = null,
        serverId: String? = null,
    ): Boolean {
        foregroundEpoch?.let(::observeLifecycleForegroundEpoch)
        val effectiveForegroundEpoch = latestProcessForegroundEpoch
        if (
            effectiveForegroundEpoch != null &&
            effectiveForegroundEpoch <=
                (refreshedProcessForegroundEpoch ?: Long.MIN_VALUE)
        ) {
            lastDecisionCause = "same_foreground_epoch"
            return false
        }
        val normalizedServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
        val now = nowEpochMillis()
        val lastRefreshAt = lastRefreshAtMillis
        // raft-design freshness.md (#1411, #proj-mobile:#198): a foreground return while the server's
        // realtime socket stayed continuously connected, < 60 s after the last check, needs none.
        if (
            signal == AppShellVisibleRefreshSignal.Lifecycle &&
            connectedForegroundSkipMillis > 0L &&
            normalizedServerId != null &&
            normalizedServerId == lastRefreshServerId &&
            target == lastRefreshTarget &&
            lastRefreshAt != null &&
            now >= lastRefreshAt &&
            now - lastRefreshAt < connectedForegroundSkipMillis
        ) {
            val currentToken = liveConnectionToken(normalizedServerId)
            if (currentToken != null && currentToken == lastRefreshConnectionToken) {
                if (effectiveForegroundEpoch != null) {
                    refreshedProcessForegroundEpoch = effectiveForegroundEpoch
                }
                lastDecisionCause = "connected_within_60s"
                return false
            }
        }
        // A lifecycle signal past the check above carries a process foreground not refreshed yet.
        val newProcessForeground =
            signal == AppShellVisibleRefreshSignal.Lifecycle && foregroundEpoch != null && effectiveForegroundEpoch != null
        val refresh = shouldRefreshForAppearance(signal, appearanceGeneration, target, effectiveForegroundEpoch, newProcessForeground)
        if (refresh) {
            lastRefreshAtMillis = now
            lastRefreshServerId = normalizedServerId
            lastRefreshTarget = target
            lastRefreshConnectionToken = normalizedServerId?.let(liveConnectionToken)
            return true
        }
        if (newProcessForeground && (lastRefreshAt == null || now - lastRefreshAt >= staleRefreshMillis)) {
            // Backstop: the bookkeeping above judged this foreground already served, but the route
            // was last refreshed long ago (an app suspended for 1h44m stayed on old Activity rows).
            refreshedProcessForegroundEpoch = effectiveForegroundEpoch
            lastRefreshAtMillis = now
            lastRefreshServerId = normalizedServerId
            lastRefreshTarget = target
            lastRefreshConnectionToken = normalizedServerId?.let(liveConnectionToken)
            lastDecisionCause = "stale_since_last_refresh"
            return true
        }
        return false
    }

    private fun shouldRefreshForAppearance(
        signal: AppShellVisibleRefreshSignal,
        appearanceGeneration: Int,
        target: String,
        effectiveForegroundEpoch: Long?,
        newProcessForeground: Boolean
    ): Boolean {
        val currentKey = AppShellVisibleRefreshKey(appearanceGeneration, target)
        val currentSignals = seenSignalsByKey[currentKey]
        val retiredThroughGeneration =
            retiredThroughGenerationByTarget.getOrElse(target) { Int.MIN_VALUE }
        val key =
            when (signal) {
                AppShellVisibleRefreshSignal.PageDidAppear -> {
                    if ((pendingLifecycleGeneration ?: Int.MAX_VALUE) <= appearanceGeneration) {
                        pendingLifecycleGeneration = null
                    }
                    currentKey
                }
                AppShellVisibleRefreshSignal.Lifecycle -> {
                    val pendingGeneration = pendingLifecycleGeneration
                    when {
                        pendingGeneration != null && pendingGeneration > appearanceGeneration -> {
                            pendingLifecycleGeneration = null
                            AppShellVisibleRefreshKey(pendingGeneration, target)
                        }
                        appearanceGeneration <= retiredThroughGeneration -> currentKey
                        currentSignals?.contains(AppShellVisibleRefreshSignal.PageDidAppear) == true &&
                            AppShellVisibleRefreshSignal.Lifecycle !in currentSignals -> currentKey
                        currentSignals?.contains(AppShellVisibleRefreshSignal.Lifecycle) == true -> currentKey
                        else -> AppShellVisibleRefreshKey(appearanceGeneration + 1, target)
                    }
                }
            }
        if (
            key.appearanceGeneration <=
            retiredThroughGenerationByTarget.getOrElse(key.target) { Int.MIN_VALUE }
        ) {
            if (signal == AppShellVisibleRefreshSignal.Lifecycle && effectiveForegroundEpoch != null) {
                refreshedProcessForegroundEpoch = effectiveForegroundEpoch
            }
            lastDecisionCause = if (newProcessForeground) "retired_appearance_new_epoch" else "retired_appearance"
            return false
        }
        val seenSignals = seenSignalsByKey[key]
        if (seenSignals != null) {
            // Hands f6c3f3a6: hide -> show -> hide -> show with no page appearance in between
            // maps both shows to the same pending appearance key. The second show is a new
            // process foreground (the epoch moved), not a duplicate of the first, so it refreshes.
            val repeatedForeground = newProcessForeground && AppShellVisibleRefreshSignal.Lifecycle in seenSignals
            seenSignals += signal
            if (signal == AppShellVisibleRefreshSignal.Lifecycle && effectiveForegroundEpoch != null) {
                refreshedProcessForegroundEpoch = effectiveForegroundEpoch
            }
            lastDecisionCause = if (repeatedForeground) "repeated_foreground_same_appearance" else "same_appearance"
            return repeatedForeground
        }
        seenSignalsByKey[key] = mutableSetOf(signal)
        lastDecisionCause = "new_appearance"
        if (effectiveForegroundEpoch != null) {
            refreshedProcessForegroundEpoch = effectiveForegroundEpoch
        }
        keyOrder += key
        while (keyOrder.size > MaxRememberedAppearanceKeys) {
            val retiredKey = keyOrder.removeAt(0)
            seenSignalsByKey.remove(retiredKey)
            retiredThroughGenerationByTarget[retiredKey.target] =
                maxOf(
                    retiredThroughGenerationByTarget.getOrElse(retiredKey.target) { Int.MIN_VALUE },
                    retiredKey.appearanceGeneration
                )
        }
        return true
    }

    private companion object {
        const val MaxRememberedAppearanceKeys = 16
    }
}

internal fun requestAppShellInitialAuthRestore(
    dispatch: (SharedAuthGateIntent) -> Unit
) {
    dispatch(SharedAuthGateIntent.Restore)
}

internal fun homeSecondaryPerformanceRoute(destination: String): String =
    when (retainedHomeSecondaryDestination(destination)) {
        HomeDestinationActivity -> "kuikly_home_activity"
        HomeDestinationSearch -> "kuikly_home_search"
        HomeDestinationSaved -> "kuikly_home_saved"
        else -> "kuikly_home_secondary"
    }

internal fun appShellBottomTabPerformanceRoute(tab: String): String =
    when (retainedAppShellSelectedTab(tab)) {
        "tasks" -> "kuikly_tasks"
        "members" -> "kuikly_members"
        "settings" -> "kuikly_settings"
        "dms" -> "kuikly_dms"
        "activity" -> "kuikly_activity"
        "more" -> "kuikly_more"
        AppShellSearchTabKey -> "kuikly_search"
        RaftAppDestination.Artifacts.id -> "kuikly_artifacts"
        RaftAppDestination.Computers.id -> "kuikly_computers"
        RaftAppDestination.Apps.id -> "kuikly_apps"
        else -> "kuikly_home"
    }

internal fun appShellBottomTabPerformancePhase(tab: String): String =
    "bottom_tab_${retainedAppShellSelectedTab(tab)}"

/**
 * Navigation rule (stdrc 2026-10-21): a page with Back at the top shows no bottom tab bar; a
 * page without Back is a tab root and shows it. The roots (Home, Activity, Tasks, More = the
 * Settings page) show the bar; an in-shell child page that carries Back hides it — Members
 * pushed from More ([appShellMoreChildBackTarget]). Home secondary destinations and every other
 * Back page (settings groups, Computers, threads, task details) are native pages pushed over the
 * shell, so the bar stays mounted behind them. The wide nav rail never shows the bar (callers
 * also gate on it).
 */
internal fun appShellBottomTabsVisible(
    selectedTab: String,
    selectedHomeDestination: String?,
    navRailActive: Boolean = false,
    phoneTabKeys: List<String> = AppShellPhoneBottomBarKeys,
): Boolean =
    when (retainedAppShellSelectedTab(selectedTab)) {
        // Home secondary destinations are native child pages. Keep the root tab bar mounted
        // behind them so returning does not recreate it after the root is already visible.
        "home" ->
            selectedHomeDestination == null ||
                retainedHomeSecondaryDestination(selectedHomeDestination) != null
        else -> appShellMoreChildBackTarget(selectedTab, navRailActive, phoneTabKeys = phoneTabKeys) == null
    }

/**
 * Navigation rule on a window that shrank below the wide layout (a tablet turned to portrait, a
 * foldable folded, a split-screen resize): the Home / Activity list-detail host keeps the open
 * conversation and shows it alone (DetailOnly) with its own Back, so it is a pushed page and the
 * bottom bar steps aside, as it does for a conversation opened on a phone.
 */
internal fun appShellAdaptiveDetailCoversTabRoot(
    desktopModeOnLargeScreen: Boolean,
    selectedTab: String,
    presentation: RaftAdaptiveWorkspacePresentation?,
): Boolean =
    desktopModeOnLargeScreen &&
        presentation == RaftAdaptiveWorkspacePresentation.DetailOnly &&
        retainedAppShellSelectedTab(selectedTab) in AppShellAdaptiveListDetailTabs

/** The tabs whose root is the shared list-detail host ([appShellAdaptiveDetailCoversTabRoot]). */
internal val AppShellAdaptiveListDetailTabs: Set<String> = setOf("home", "activity")

internal fun sharedHomeShouldStartOnActive(active: Boolean, sessionReady: Boolean, state: SharedHomeState): Boolean =
    active &&
        sessionReady &&
        (
            state.content !is SharedHomeContentState.Content ||
                state.homeRealtimeDirty ||
                (state.content as? SharedHomeContentState.Content)?.needsNetworkRefresh == true
        )

internal fun appShellParentShouldStartHome(
    selectedTab: String,
    selectedHomeDestination: String?,
    readyServerId: String?,
    state: SharedHomeState,
    pageVisible: Boolean = true,
    readyAccountId: String? = null,
    homeLikeTabs: Set<String> = emptySet()
): Boolean =
    pageVisible &&
        retainedAppShellSelectedTab(selectedTab).let { tab ->
            tab == "home" || tab in homeLikeTabs
        } &&
        selectedHomeDestination == null &&
        !readyServerId.isNullOrBlank() &&
        (
            state.content.let { content ->
                content is SharedHomeContentState.Content &&
                    (
                        content.snapshot.server.id.value != readyServerId ||
                            (
                                readyAccountId != null &&
                                    content.snapshot.currentMember.id.value != readyAccountId
                            )
                    )
            } ||
                sharedHomeShouldStartOnActive(active = true, sessionReady = true, state = state)
        )

internal fun appShellAuthoritativeHomeStartAllowed(
    authGateState: SharedAuthGateState,
    setupAdmissionAllowsHome: Boolean
): Boolean {
    val ready = authGateState as? SharedAuthGateState.Ready ?: return false
    return setupAdmissionAllowsHome && ready.phase != SharedAuthReadyPhase.CachedRestorePending
}

/**
 * Hands 79db9fe6 / e486495b (artin): a tap on a channel in the identity-bound cached Home opens it
 * while the session and server admission are still being confirmed in the background; it used to
 * be dropped (`home_open_conversation_drop reason=home_authority_pending`) until the restore ended.
 */
internal fun appShellHomeConversationOpenAllowed(
    authoritativeHomeStartAllowed: Boolean,
    cachedRestoreUsable: Boolean
): Boolean = authoritativeHomeStartAllowed || cachedRestoreUsable

internal fun appShellCanRenderCachedHomePreview(
    readySession: StoredAuthSession?,
    readyPhase: SharedAuthReadyPhase?,
    setupAdmissionState: ServerSetupAdmissionState? = null,
    serverSetupGateEnabled: Boolean = true,
    readyServerId: String?,
    selectedTab: String = "home",
    selectedHomeDestination: String? = null,
    pageVisible: Boolean = true,
    state: SharedHomeState
): Boolean {
    if (!pageVisible || retainedAppShellSelectedTab(selectedTab) != "home" || selectedHomeDestination != null) {
        return false
    }
    val session = readySession ?: return false
    val phase = readyPhase ?: return false
    if (phase == SharedAuthReadyPhase.Confirmed) return false
    // The cached Home stays while the server setup check is still running; once it answers (or
    // with the setup gate off, when there is no check), Home hands over to the authoritative Home
    // and setup takes the screen only on a positive blocksChat.
    if (
        phase == SharedAuthReadyPhase.CachedRestoreCompleted &&
        (!serverSetupGateEnabled || serverSetupAdmissionResolved(setupAdmissionState))
    ) {
        return false
    }
    if (serverSetupBlocksHome(setupAdmissionState)) {
        return false
    }
    val serverId = readyServerId?.trim()?.takeIf { it.isNotBlank() } ?: return false
    val content = state.content as? SharedHomeContentState.Content ?: return false
    return content.isDurableIdentityCache &&
        content.needsNetworkRefresh &&
        content.snapshot.server.id.value == serverId &&
        content.snapshot.currentMember.id.value == session.user.id &&
        content.session.user.id == session.user.id &&
        content.session.currentServerId == serverId &&
        session.servers.any { it.id == serverId }
}

internal fun appShellNavigationServerId(
    selectedServerId: String?,
    renderedHomeServerId: String?,
    scopeInitialServerId: String?
): String? =
    selectedServerId?.trim()?.takeIf { it.isNotBlank() }
        ?: renderedHomeServerId?.trim()?.takeIf { it.isNotBlank() }
        ?: scopeInitialServerId?.trim()?.takeIf { it.isNotBlank() }

internal fun sharedHomeHeaderRefreshing(state: SharedHomeState): Boolean =
    (state.content as? SharedHomeContentState.Content)?.contentRefreshing == true

internal fun homeSurfaceVisibilityDispatchDelayMs(visible: Boolean): Long =
    if (visible) HomeSurfaceVisibleResumeDelayMs else 0L

/**
 * Binds the Home / DMs / Activity shell routes to [SharedHomeShellStore]'s store-owned
 * outcome-tracked [build.raft.app.data.RaftFreshnessGate] (#wg-mobile-feedback:#274 Phase 2) for
 * socket reconnect and network recovery (the Tasks and Artifacts tabs own their own store gates),
 * and stamps [visibleRefreshGate] so an immediate unbroken-socket foreground return within 60 s
 * skips a redundant refetch.
 */
@Composable
internal fun AppShellHomeFreshnessCatchUpEffect(
    homeShellStore: SharedHomeShellStore,
    serverId: String?,
    visible: Boolean,
    contentReady: Boolean,
    selectedTab: String,
    selectedHomeDestination: String?,
    visibleRefreshGate: AppShellVisibleRefreshGate,
    onCatchUpTriggered: (
        trigger: RaftFreshnessTrigger,
        serverId: String,
        tab: String,
        destination: String?,
    ) -> Unit,
) {
    val latestVisibleRefreshGate by rememberUpdatedState(visibleRefreshGate)
    val latestOnCatchUpTriggered by rememberUpdatedState(onCatchUpTriggered)
    val refreshTarget = "${retainedAppShellSelectedTab(selectedTab)}:${selectedHomeDestination.orEmpty()}"
    LaunchedEffect(visibleRefreshGate, serverId, refreshTarget, contentReady) {
        if (contentReady) {
            visibleRefreshGate.recordFreshCheck(serverId = serverId, target = refreshTarget)
        }
    }
    LaunchedEffect(homeShellStore, serverId, visible) {
        homeShellStore.setFreshnessScope(
            serverId = serverId,
            visible = visible,
            onTriggered = { trigger, triggeredServerId, tab, destination ->
                latestVisibleRefreshGate.recordFreshCheck(
                    serverId = triggeredServerId,
                    target = "${retainedAppShellSelectedTab(tab)}:${destination.orEmpty()}",
                )
                latestOnCatchUpTriggered(trigger, triggeredServerId, tab, destination)
            },
        )
    }
    DisposableEffect(homeShellStore) {
        onDispose {
            homeShellStore.stopFreshnessObserver()
        }
    }
}
