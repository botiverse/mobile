package build.raft.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.data.RaftMobileBuiltInPlaceIds
import build.raft.app.data.RaftMobilePlacesSnapshot
import build.raft.app.data.RaftRailPlace
import build.raft.app.data.RaftRailPlacesSnapshot
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SharedMobileRailPlacesState
import build.raft.app.data.SharedMobileRailPlacesStore
import build.raft.app.data.SharedRailPlacesStore
import build.raft.app.data.SharedRailTabOrderIntent
import build.raft.app.data.SharedRailTabOrderStore
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.TAB_BAR_PLACES_V0_FEATURE_FLAG_KEY
import build.raft.app.data.isRaftMobileBuiltInPlaceId
import build.raft.app.data.raftRailPlacesWithVisibleReorder
import build.raft.app.data.raftRailTabOrderWithVisibleOrder
import build.raft.app.data.withRaftRailKeptPlaceKeys
import build.raft.app.model.ServerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Maximum number of dynamic place tabs placed before the fixed Server tab on the phone bar. */
internal const val AppShellPhoneMaxPlaceTabs: Int = 4

/** Number of columns in the phone Server popover's More grid (matches the 5-tab bottom bar density). */
internal const val AppShellPhoneMoreGridColumns: Int = 5

/**
 * Whether per-server phone & native tablet/foldable navigation places are enabled for [serverId]
 * (`tab_bar_places_v0`, `#proj-mobile:#329`).
 *
 * - Default OFF when unresolved, missing, failed, or false.
 * - ALWAYS false on Desktop Compose (`isDesktop == true`), which exclusively uses desktop
 *   `rail_places_v0` / `places` and never reads `mobilePlaces`.
 */
internal fun appShellTabBarPlacesEnabled(
    flags: SharedServerFeatureFlags,
    serverId: String?,
    isDesktop: Boolean = raftPlatformIsDesktop(),
): Boolean {
    if (isDesktop) return false
    val id = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return flags.isEnabled(ServerId(id), TAB_BAR_PLACES_V0_FEATURE_FLAG_KEY)
}

/** Maps a wire built-in mobile place key (`RAIL_MOBILE_PLACE_IDS`) to its phone shell tab key; null for `app:`/unknown. */
internal fun raftShellTabKeyForMobilePlace(placeKey: String): String? =
    when (placeKey) {
        "chat" -> RaftAppDestination.Chat.id
        "activity" -> RaftAppDestination.Activity.id
        "tasks" -> RaftAppDestination.Tasks.id
        "search" -> AppShellSearchTabKey
        "artifacts" -> RaftAppDestination.Artifacts.id
        "members" -> RaftAppDestination.Members.id
        "computers" -> RaftAppDestination.Computers.id
        "apps" -> RaftAppDestination.Apps.id
        else -> null
    }

/** Maps a shell tab key or wide-rail item key back to its wire built-in mobile place key (`RAIL_MOBILE_PLACE_IDS`). */
internal fun raftMobilePlaceKeyForShellTab(shellTabKey: String): String? =
    when (shellTabKey) {
        RaftAppDestination.Chat.id -> "chat"
        RaftAppDestination.Activity.id -> "activity"
        RaftAppDestination.Tasks.id -> "tasks"
        AppShellSearchTabKey, AppShellRailSearchKey -> "search"
        RaftAppDestination.Artifacts.id, AppShellRailArtifactsKey -> "artifacts"
        RaftAppDestination.Members.id -> "members"
        RaftAppDestination.Computers.id, AppShellRailComputersKey -> "computers"
        RaftAppDestination.Apps.id, AppShellRailAppsKey -> "apps"
        else -> null
    }

/** Maps a wire built-in mobile place key (`RAIL_MOBILE_PLACE_IDS`) to its native tablet/foldable rail item key. */
internal fun raftRailItemKeyForMobilePlace(placeKey: String): String? =
    when (placeKey) {
        "search" -> AppShellRailSearchKey
        else -> raftShellTabKeyForMobilePlace(placeKey)
    }

/**
 * Visibility rules for built-in mobile places (Kirby v8 #415 §7):
 * - `chat`, `activity`, `tasks`, `search`: always visible.
 * - `artifacts`: visible only when `workspace_artifacts_v0` is enabled for the server.
 * - `members`, `computers`, `apps`: hidden for guests (`isGuest == true`).
 */
internal fun appShellIsMobileBuiltInPlaceVisible(
    placeId: String,
    artifactsEnabled: Boolean,
    isGuest: Boolean,
): Boolean =
    when (placeId) {
        "chat", "activity", "tasks", "search" -> true
        "artifacts" -> artifactsEnabled
        "members", "computers", "apps" -> !isGuest
        else -> false
    }

/**
 * Visible built-in mobile place IDs in display order for Phase 1 (`tab_bar_places_v0`):
 * skips `app:` entries, filters by [artifactsEnabled] and [isGuest], falls back to
 * [RaftMobileBuiltInPlaceIds] when [mobilePlaces] is null (older server / not loaded yet) or
 * contains zero visible built-ins.
 */
internal fun appShellVisibleMobileBuiltInPlaceIds(
    mobilePlaces: List<RaftRailPlace>?,
    artifactsEnabled: Boolean,
    isGuest: Boolean,
): List<String> {
    val rawBuiltInIds =
        mobilePlaces
            ?.asSequence()
            ?.filter { it.isBuiltIn && isRaftMobileBuiltInPlaceId(it.key) }
            ?.map { it.key }
            ?.distinct()
            ?.toList()
            ?: RaftMobileBuiltInPlaceIds
    val visible =
        rawBuiltInIds.filter {
            appShellIsMobileBuiltInPlaceVisible(it, artifactsEnabled = artifactsEnabled, isGuest = isGuest)
        }
    if (visible.isNotEmpty()) return visible
    return RaftMobileBuiltInPlaceIds.filter {
        appShellIsMobileBuiltInPlaceVisible(it, artifactsEnabled = artifactsEnabled, isGuest = isGuest)
    }
}

/**
 * Dynamic phone bottom-bar tab keys:
 * - Flag OFF: fixed [AppShellPhoneBottomBarKeys] (`home`, `activity`, `tasks`, `search`, `more`).
 * - Flag ON (Phase 1): first 1..4 visible built-in places from [mobilePlaces] + fixed
 *   [AppShellServerTabKey] (`more`).
 */
internal fun appShellDynamicPhoneTabKeys(
    enabled: Boolean,
    mobilePlaces: List<RaftRailPlace>?,
    artifactsEnabled: Boolean,
    isGuest: Boolean,
): List<String> {
    if (!enabled) return AppShellPhoneBottomBarKeys
    val placeTabs =
        appShellVisibleMobileBuiltInPlaceIds(
            mobilePlaces = mobilePlaces,
            artifactsEnabled = artifactsEnabled,
            isGuest = isGuest,
        )
            .mapNotNull(::raftShellTabKeyForMobilePlace)
            .take(AppShellPhoneMaxPlaceTabs)
            .ifEmpty { listOf(RaftAppDestination.Chat.id) }
    return placeTabs + AppShellServerTabKey
}

/**
 * First (primary) top-level destination on the phone bottom bar: `"home"` when flag is OFF or
 * default order is active, otherwise slot 0 of [phoneTabKeys] (Kirby §7: server landing tab and
 * Android Back root).
 */
internal fun appShellPhonePrimaryTabKey(phoneTabKeys: List<String> = AppShellPhoneBottomBarKeys): String =
    phoneTabKeys.firstOrNull { it != AppShellServerTabKey } ?: RaftAppDestination.Chat.id

/**
 * When a foldable/tablet widens into [navRailActive] while a promoted phone page tab (`artifacts`,
 * `computers`, `apps`) is selected, returns its inline rail destination key so the rail highlights
 * and renders it seamlessly.
 */
internal fun appShellPromotedPhoneTabMovesToRailInline(
    selectedTab: String,
    navRailActive: Boolean,
): String? {
    if (!navRailActive) return null
    return when (retainedAppShellSelectedTab(selectedTab)) {
        RaftAppDestination.Artifacts.id -> AppShellRailArtifactsKey
        RaftAppDestination.Computers.id -> AppShellRailComputersKey
        RaftAppDestination.Apps.id -> AppShellRailAppsKey
        else -> null
    }
}

/** Maps a built-in mobile place id (`RAIL_MOBILE_PLACE_IDS`) to its [RaftServerMenuEntry]. */
internal fun raftServerMenuEntryForMobilePlace(placeId: String): RaftServerMenuEntry? =
    when (placeId) {
        "chat" -> RaftServerMenuEntry.Home
        "activity" -> RaftServerMenuEntry.Activity
        "tasks" -> RaftServerMenuEntry.Tasks
        "search" -> RaftServerMenuEntry.Search
        "artifacts" -> RaftServerMenuEntry.Artifacts
        "members" -> RaftServerMenuEntry.Members
        "computers" -> RaftServerMenuEntry.Computers
        "apps" -> RaftServerMenuEntry.Apps
        else -> null
    }

/**
 * Server popover destination entries when `tab_bar_places_v0` is ON:
 * - On wide layouts (`wide == true`), returns empty (matches existing rail rule).
 * - On phones, lists every visible built-in place NOT currently on [phoneTabKeys] (in
 *   [mobilePlaces] order — including `Home`, `Activity`, `Tasks`, `Search` if moved off the bar!),
 *   followed by [RaftServerMenuEntry.Settings].
 */
internal fun appShellMobileServerMenuEntries(
    mobilePlaces: List<RaftRailPlace>?,
    phoneTabKeys: List<String>,
    wide: Boolean,
    guest: Boolean,
    artifacts: Boolean,
): List<RaftServerMenuEntry> {
    if (wide) return emptyList()
    val onBarPlaceIds = phoneTabKeys.mapNotNull(::raftMobilePlaceKeyForShellTab).toSet()
    val overflowPlaces =
        appShellVisibleMobileBuiltInPlaceIds(
            mobilePlaces = mobilePlaces,
            artifactsEnabled = artifacts,
            isGuest = guest,
        )
            .filter { it !in onBarPlaceIds }
            .mapNotNull(::raftServerMenuEntryForMobilePlace)
    return overflowPlaces + RaftServerMenuEntry.Settings
}

/**
 * Projects the native Android / iOS tablet & foldable navigation rail items from `mobilePlaces`
 * when `tab_bar_places_v0` is ON (xxchan v6):
 * - Phase 1: built-in places only (skips `app:` entries).
 * - Preserves `mobilePlaces` order, including `search` ([AppShellRailSearchKey]) in its exact
 *   `mobilePlaces` slot so folding/unfolding keeps positions 1..4 consistent.
 * - Filters hidden built-ins (`artifacts` behind flag; `members`/`computers`/`apps` hidden for guests).
 * - NEVER called on Desktop Compose (`raftPlatformIsDesktop() == true`).
 */
internal fun projectNativeTabletRailItemsFromMobilePlaces(
    builtInItems: List<RaftBottomTabItemPayload>,
    searchItem: RaftBottomTabItemPayload,
    mobilePlaces: List<RaftRailPlace>?,
    artifactsEnabled: Boolean,
    isGuest: Boolean,
): List<RaftBottomTabItemPayload> {
    val itemsByPlaceId = linkedMapOf<String, RaftBottomTabItemPayload>()
    itemsByPlaceId["search"] = searchItem
    builtInItems.forEach { item ->
        val placeId = raftMobilePlaceKeyForShellTab(item.key) ?: return@forEach
        if (placeId != "search") {
            itemsByPlaceId[placeId] = item
        }
    }
    return appShellVisibleMobileBuiltInPlaceIds(
        mobilePlaces = mobilePlaces,
        artifactsEnabled = artifactsEnabled,
        isGuest = isGuest,
    ).mapNotNull { placeId -> itemsByPlaceId[placeId] }
}

/**
 * Hosts a promoted phone bottom-bar destination (`Artifacts`, `Computers`, `Apps`) directly as a
 * top-level shell tab without a Back button while keeping phone top safe area, compact layout, and
 * bottom tab-bar clearance. Extracted outside `RaftAppShellReadyContent` to preserve its JVM method
 * size budget.
 */
@Composable
internal fun AppShellPromotedPhoneTabHost(
    page: Pair<String, JSONObject>?,
    bottomInset: Dp,
    webViewBottomInset: Dp,
    active: Boolean,
) {
    if (page == null) return
    CompositionLocalProvider(
        LocalRaftPhoneTabRoot provides true,
        LocalRaftPhoneTabRootBottomInset provides bottomInset,
        LocalRaftPhoneTabRootWebViewBottomInset provides webViewBottomInset,
    ) {
        RaftChildPage(
            pageName = page.first,
            pageData = page.second,
            modifier = Modifier.fillMaxSize(),
            active = active,
        )
    }
}

/** Consolidated per-server mobile places shell state, kept outside `RaftAppShellReadyContent`. */
internal data class AppShellMobilePlacesShellState(
    val isDesktopHost: Boolean,
    val enabled: Boolean,
    val store: SharedMobileRailPlacesStore,
    val storeState: State<SharedMobileRailPlacesState>,
    val activeSnapshot: RaftMobilePlacesSnapshot?,
    val isGuest: Boolean,
    val artifactsEnabled: Boolean,
    val activePhoneTabKeys: List<String>,
)

@Composable
internal fun rememberAppShellMobilePlacesShellState(
    sharedStores: RaftSharedStoreProvider,
    readySession: StoredAuthSession?,
    readyServerId: String?,
    homeState: SharedHomeState,
    flagRevision: Long,
): AppShellMobilePlacesShellState {
    val isDesktopHost = remember { raftPlatformIsDesktop() }
    val enabled =
        remember(sharedStores.serverFeatureFlags, readyServerId, flagRevision, isDesktopHost) {
            appShellTabBarPlacesEnabled(sharedStores.serverFeatureFlags, readyServerId, isDesktop = isDesktopHost)
        }
    val store = sharedStores.mobileRailPlacesStore
    val storeState = store.state.collectAsState()
    val stateValue by storeState
    val activeSnapshot = if (enabled) stateValue.activeSnapshot else null
    val role =
        (homeState.content as? SharedHomeContentState.Content)
            ?.takeIf { it.snapshot.server.id.value == readyServerId }
            ?.snapshot?.server?.role
            ?: readySession?.servers?.firstOrNull { it.id == readyServerId }?.role
            ?: readySession?.currentServerSummary()?.role
    val isGuest = role.equals("guest", ignoreCase = true)
    val artifactsEnabled =
        remember(sharedStores.serverFeatureFlags, readyServerId, flagRevision) {
            appShellArtifactsShown(sharedStores.serverFeatureFlags, readyServerId)
        }
    val activePhoneTabKeys =
        remember(enabled, activeSnapshot?.mobilePlaces, artifactsEnabled, isGuest) {
            appShellDynamicPhoneTabKeys(
                enabled = enabled,
                mobilePlaces = activeSnapshot?.mobilePlaces,
                artifactsEnabled = artifactsEnabled,
                isGuest = isGuest,
            )
        }
    return AppShellMobilePlacesShellState(
        isDesktopHost = isDesktopHost,
        enabled = enabled,
        store = store,
        storeState = storeState,
        activeSnapshot = activeSnapshot,
        isGuest = isGuest,
        artifactsEnabled = artifactsEnabled,
        activePhoneTabKeys = activePhoneTabKeys,
    )
}

/**
 * Resolves the active navigation rail items:
 * - Native Android / iOS tablet & foldable with `tab_bar_places_v0` ON -> projects from `mobilePlaces`
 *   (including `search` in its slot so fold/unfold preserves positions 1..4).
 * - Desktop Compose (always) or Android / iOS with `tab_bar_places_v0` OFF -> keeps existing
 *   `rail_places_v0` / `railTabOrder` untouched.
 */
internal fun appShellResolvedRailItems(
    useNativeMobileRailPlaces: Boolean,
    baseRailItems: List<RaftBottomTabItemPayload>,
    searchLabel: String,
    railInlineSecondaryDestination: String?,
    mobilePlacesSnapshot: RaftMobilePlacesSnapshot?,
    artifactsEnabled: Boolean,
    isGuest: Boolean,
    desktopRailPlacesSnapshot: RaftRailPlacesSnapshot?,
    railTabOrder: List<String>,
    resolveIconUrl: (String?) -> String?,
): List<RaftBottomTabItemPayload> {
    if (useNativeMobileRailPlaces) {
        val searchOpen = railInlineSecondaryDestination == AppShellRailSearchKey
        val searchItem =
            RaftBottomTabItemPayload(
                key = AppShellRailSearchKey,
                label = searchLabel,
                iconName = RaftNavPresentation.Rail.iconOf(RaftAppDestination.Search).lucide,
                selected = searchOpen,
            )
        val projected =
            projectNativeTabletRailItemsFromMobilePlaces(
                builtInItems = baseRailItems,
                searchItem = searchItem,
                mobilePlaces = mobilePlacesSnapshot?.mobilePlaces,
                artifactsEnabled = artifactsEnabled,
                isGuest = isGuest,
            )
        return if (searchOpen) {
            projected.map { if (it.key == AppShellRailSearchKey) it else it.copy(selected = false) }
        } else {
            projected
        }
    }
    return desktopRailPlacesSnapshot?.let { placesSnapshot ->
        projectRailItemsFromServerPlaces(
            builtInItems = baseRailItems,
            snapshot = placesSnapshot,
            resolveIconUrl = resolveIconUrl,
        )
    } ?: baseRailItems.orderedLikeRailTabOrder(railTabOrder)
}

/** Resolves drag-reorder handling for the wide rail (mobile places on native tablet/foldable vs desktop places / account order). */
internal fun appShellResolvedRailTabReorder(
    useNativeMobileRailPlaces: Boolean,
    mobilePlacesStore: SharedMobileRailPlacesStore,
    mobilePlacesLoaded: Boolean,
    useDesktopRailPlaces: Boolean,
    railPlacesStore: SharedRailPlacesStore,
    desktopRailPlacesLoaded: Boolean,
    railTabOrderStore: SharedRailTabOrderStore,
    readyServerId: String?,
    scope: CoroutineScope,
): RaftRailTabReorder {
    val serverId = readyServerId?.takeIf { it.isNotBlank() }
    if (useNativeMobileRailPlaces && mobilePlacesLoaded && serverId != null) {
        return RaftRailTabReorder(
            fixedKeys = emptySet(),
            onOrderChange = { keys ->
                val mobileKeys = keys.mapNotNull(::raftMobilePlaceKeyForShellTab)
                if (mobileKeys.isNotEmpty()) {
                    scope.launch { mobilePlacesStore.saveOrder(serverId, mobileKeys) }
                }
            },
        )
    }
    val useServerPlaces = useDesktopRailPlaces && desktopRailPlacesLoaded && serverId != null
    return RaftRailTabReorder(
        fixedKeys = if (useServerPlaces) RaftRailPlacesFixedItemKeys else emptySet(),
        onOrderChange = { keys ->
            val snapshot = if (useDesktopRailPlaces) railPlacesStore.state.value.activeSnapshot else null
            if (useDesktopRailPlaces && serverId != null && snapshot != null) {
                val reordered =
                    raftRailPlacesWithVisibleReorder(
                        fullKeys = withRaftRailKeptPlaceKeys(snapshot.places.map { it.key }),
                        visibleOrder = keys.mapNotNull(::raftRailPlaceKeyForItemKey),
                    )
                scope.launch { railPlacesStore.savePlaces(serverId, reordered) }
            } else {
                railTabOrderStore.dispatch(
                    SharedRailTabOrderIntent.Reorder(
                        raftRailTabOrderWithVisibleOrder(
                            fullOrder = railTabOrderStore.state.value.order,
                            visibleOrder = keys.mapNotNull(::raftRailTabIdForKey),
                        ),
                    ),
                )
            }
        },
    )
}

/** Resolves server popover entries (overflow mobile places + Settings when flag ON, legacy list when OFF). */
internal fun appShellResolvedServerMenuEntries(
    tabBarPlacesEnabled: Boolean,
    defaultEntries: List<RaftServerMenuEntry>,
    mobilePlaces: List<RaftRailPlace>?,
    phoneTabKeys: List<String>,
    wide: Boolean,
    guest: Boolean,
    artifacts: Boolean,
): List<RaftServerMenuEntry> =
    if (tabBarPlacesEnabled) {
        appShellMobileServerMenuEntries(
            mobilePlaces = mobilePlaces,
            phoneTabKeys = phoneTabKeys,
            wide = wide,
            guest = guest,
            artifacts = artifacts,
        )
    } else {
        raftServerMenuEntriesFor(
            entries = defaultEntries,
            wide = wide,
            guest = guest,
            artifacts = artifacts,
        )
    }
