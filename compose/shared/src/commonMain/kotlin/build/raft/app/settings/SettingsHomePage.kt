package build.raft.app

import androidx.compose.runtime.MutableState
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.raftDedupeKey
import build.raft.app.data.ArchivedNameCollision
import build.raft.app.data.archivedNameCollisionOrNull
import build.raft.designsystem.motion.rememberRaftReducedMotion
import build.raft.app.data.TaskConversationDetailsRepository
import build.raft.app.resources.*
import build.raft.designsystem.components.rememberRaftMobileNavServerPopoverState
import build.raft.designsystem.focus.RaftFocusRegion
import build.raft.designsystem.focus.RaftFocusRegionHost
import build.raft.app.data.raftHostShowsContent
import build.raft.app.data.raftProcessRealtimeOwnerGeneration
import build.raft.app.data.raftProcessRealtimeSessionDemand
import build.raft.app.data.RaftNavDestination
import build.raft.app.data.RaftNavSurface
import build.raft.app.data.RaftProductEvents
import build.raft.app.data.RaftRatingPrompt
import build.raft.app.data.SHELL_OVERLAY_MESSAGE_TYPE
import build.raft.app.data.SHELL_OVERLAY_REQUEST_ID
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.normalizedOrNull
import build.raft.app.data.backFromDetail
import build.raft.app.data.closeFromDetailPage
import build.raft.app.data.closeSideThread
import build.raft.app.data.backClosesSideThreadFirst
import build.raft.app.data.sideThreadColumnAvailable
import build.raft.app.data.visibleSideThread
import build.raft.app.data.AdaptiveSideThread
import build.raft.app.data.RaftListPaneKind
import build.raft.app.data.RaftCollapsibleColumn
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import build.raft.app.data.closeDetailCompletely
import build.raft.app.data.withOpenedDetail
import build.raft.app.data.SharedAdaptiveWorkspaceSession
import build.raft.app.data.detailListSelectionId
import build.raft.app.data.hasVisibleDetailBackHistory
import build.raft.app.data.NetworkRaftThreadApi
import build.raft.app.base.Utils
import build.raft.app.network.raftNetworkKmmRecoveryEnvironmentId
import build.raft.app.data.NetworkRaftHomeApi
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.BridgeRaftAvatarNativeCache
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.KeyValuePushPreviewModePreferenceStore
import build.raft.app.data.KeyValueLocalePreferenceStore
import build.raft.app.data.RaftHostNetworkEvents
import build.raft.app.data.RaftAppModalPromptSlot
import build.raft.app.data.RaftAppUpdateEvents
import build.raft.app.data.RaftMinBuildGate
import build.raft.app.data.RaftMinBuildGateDecision
import build.raft.app.data.arbitrateRaftAppModalPrompt
import build.raft.app.data.RaftChatLayoutPreferenceEvents
import build.raft.app.data.RaftComposerSendKey
import build.raft.app.data.RaftComposerSendKeyRuntime
import build.raft.app.data.RaftRealtimeEvents
import build.raft.app.data.RaftTimeFormatRuntime
import build.raft.app.data.RaftTimeZoneRuntime
import build.raft.app.data.RealtimeRoomLeaseOwner
import build.raft.app.data.AppLocale
import build.raft.app.data.MyMessageAlignmentPreference
import build.raft.app.data.NetworkRaftAnnouncementsRemindersApi
import build.raft.app.data.RaftAnnouncementsRemindersApiClient
import build.raft.app.data.NetworkRaftTasksApi
import build.raft.app.data.PersistentChatLayoutPreferenceRepository
import build.raft.app.data.DEFAULT_DESKTOP_MODE_ON_LARGE_SCREEN
import build.raft.app.data.DEFAULT_SERVER_DRAWER_ENABLED
import build.raft.app.data.raftChatLayoutPreferenceRepository
import build.raft.app.data.authSessionTerminalSourceOrNull
import build.raft.app.data.SharedAuthGateIntent
import build.raft.app.data.SharedAuthReadyPhase
import build.raft.app.data.SharedAuthGateState
import build.raft.app.data.SharedAuthGateViewModel
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.SharedTasksRouteIntent
import build.raft.app.data.SharedTasksRouteOwner
import build.raft.app.data.WindowNavigationAction
import build.raft.app.data.WindowNavigationIntent
import build.raft.app.data.WindowNavigationReadiness
import build.raft.app.data.ConversationForwardAction
import build.raft.app.data.ConversationForwardHomeReturnSignals
import build.raft.app.data.appShellConversationForwardGestureArmed
import build.raft.app.data.toConversationForwardEntry
import build.raft.app.data.realtime.RealtimeCursorPolicy
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeDataSource
import build.raft.app.data.HomeConversationRow
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.DRAFTS_SECTION_FEATURE_FLAG_KEY
import build.raft.app.data.agentLoungeEnabled
import build.raft.app.data.AGENT_LOUNGE_FEATURE_FLAG_KEY
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SharedHomeShellIntent
import build.raft.app.data.SharedHomeShellStore
import build.raft.app.data.RealtimeGuardedDomainEventSinks
import build.raft.app.data.RealtimeShellCallbackGate
import build.raft.app.data.RealtimeGrantRecord
import build.raft.app.data.SharedSettingsDeveloperSnapshot
import build.raft.app.data.PushPreviewMode
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RAFT_SHELL_OVERLAY_REQUEST_EVENT
import build.raft.app.data.RAFT_SHELL_OVERLAY_RESULT_EVENT
import build.raft.app.data.SHELL_OVERLAY_TYPE_CANCEL
import build.raft.app.data.RaftShellOpenOverlay
import build.raft.app.data.RaftShellOverlayResult
import build.raft.app.data.raftShellShouldDropOverlayRequest
import build.raft.app.data.shellOverlayRequestIdOrNull
import build.raft.app.data.toRaftShellOverlayRequestOrNull
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.isAuthSessionError
import build.raft.app.data.isSubmittableChannelName
import build.raft.app.data.RaftAuthSessionEvents
import build.raft.app.data.kuiklyBridgeCallContext
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.data.messageBodyFontSizeOwnerKey
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.parseRaftHome
import build.raft.app.data.CreateChannelRequest
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.RealConversationRepository
import build.raft.app.data.RealInboxRepository
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.RealReadReceiptRepository
import build.raft.app.data.RealSavedRepository
import build.raft.app.data.RealSearchRepository
import build.raft.app.data.RealSettingsRepository
import build.raft.app.data.SharedMembersDataSource
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.RaftAgentsNetworkApi
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.data.RaftComputersNetworkApi
import build.raft.app.data.SharedCreateAgentCoordinator
import build.raft.app.data.SharedAgentDetailIntent
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.canWriteTasksInChannel
import build.raft.app.data.mergedSharedConversationDirectoryState
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.data.SharedHomeSearchSavedViewModel
import build.raft.app.data.externalShareComposerText
import build.raft.app.data.ScreenError
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.model.ConversationKind
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerDeleted
import build.raft.app.model.ServerId
import build.raft.app.model.ServerMembershipRemoved
import build.raft.app.data.realtime.RaftRealtimeNotifyContract
import build.raft.app.data.realtime.GeneratedRealtimeContract
import build.raft.app.model.TaskSummary
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.RaftTimeContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.collectAsState
import build.raft.app.compat.ui.LocalRaftPageActive
import build.raft.app.compat.ui.collectAsStateWhileStarted
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.width
import build.raft.designsystem.components.RaftWindowDialogProperties
import build.raft.designsystem.components.RaftWindowDialog
import build.raft.designsystem.components.RaftKeyGlyph
import androidx.compose.ui.Modifier
import build.raft.app.components.RaftMetrics
import build.raft.app.components.RaftUiControlledMenu
import build.raft.app.components.RaftUiMenuItem
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.unit.dp
import build.raft.app.compat.pager.Page
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import build.raft.app.data.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.time.TimeSource
import build.raft.designsystem.components.RaftEmptyState
import build.raft.designsystem.components.RaftEmptyStateDefaults

const val SETTINGS_HOME_PAGE = "compose_settings_home"
const val SETTINGS_ROUTE_VISUAL_PAGE = "compose_settings_route_visual"
const val SETTINGS_TAB_BAR_SMOKE_PAGE = "compose_settings_tabbar_smoke"
const val BOTTOM_TAB_BAR_SMOKE_PAGE = "compose_bottom_tabbar_smoke"
const val HOME_SEARCH_PAGE = "compose_home_search"
internal const val HOME_SAVED_PAGE = "compose_home_saved"
internal const val HOME_DRAFTS_PAGE = "compose_home_drafts"
internal const val HOME_ACTIVITY_PAGE = "compose_home_activity"
const val HOME_SEARCH_SELECTED_CHANNEL_ID = "selectedChannelId"
const val HOME_SEARCH_INITIAL_OPEN_FILTER_ID = "initialOpenFilterId"
const val HOME_SEARCH_FOCUS_INPUT = "focusSearchInput"
const val AUTH_CHOOSE_SERVER_PAGE = "compose_auth_choose_server"
const val RAFT_APP_SHELL_PAGE = "compose_slock_app_shell"
internal const val RAFT_APP_SHELL_CHILD_TAB_PAGE = "compose_app_shell_child_tab"
internal const val RAFT_CONVERSATION_FORWARD_PAGER_EVENT = "slockConversationForwardRequested"
/**
 * iOS host: the app shell's view controller appeared on top of its navigation stack (a pushed
 * page was popped). Sent by RaftPageViewController.viewDidAppear, independent of the Compose
 * lifecycle that drives pageDidAppear.
 */
internal const val RAFT_APP_SHELL_HOST_DID_APPEAR_PAGER_EVENT = "slockAppShellHostDidAppear"
internal const val AUTH_CHOOSE_SERVER_FROM_SHELL = "slockChooseServerFromShell"
internal const val AUTH_CHOOSE_SERVER_SKIP_INITIAL_LOAD = "slockAuthChooseServerSkipInitialLoad"
internal const val APP_SHELL_SUPPRESS_RESTORE_LANDING = "slockSuppressRestoreLanding"
private const val APP_SHELL_AUTH_GATE_INSTANCE_ID = "slockAuthGateInstanceId"

internal sealed interface AuthGateMessage {
    val traceId: String?

    data class Dynamic(
        val value: String,
        override val traceId: String? = null,
    ) : AuthGateMessage

    data class Static(
        val value: StringDesc,
        val pinSignOut: Boolean = false,
        /** The server error's trace id, shown with the message on the login page. */
        override val traceId: String? = null,
    ) : AuthGateMessage
}

@Composable
internal fun AuthGateMessage?.localizedMessage(): String? =
    when (this) {
        is AuthGateMessage.Dynamic -> value
        is AuthGateMessage.Static -> value.localized()
        null -> null
    }

internal const val SettingsDestinationAccount = "account"
internal const val SettingsDestinationComputers = "computers"
internal const val SettingsDestinationAdministration = "administration"
/** IM Bridges, the web section `/settings/im-bridges`; a row only while `slack_bridge_v0` is on. */
internal const val SettingsDestinationImBridges = "im-bridges"
/** App authorizations, the web section `/settings/app-authorizations` (slock #16572); every member including guests. */
internal const val SettingsDestinationAppAuthorizations = "app-authorizations"
/** Labs, the web section `/settings/labs`; a row only while `server_labs_ui_v0` is on. */
internal const val SettingsDestinationLabs = "labs"
internal const val SettingsDestinationConnectedApps = "connected-apps"
internal const val SettingsDestinationBilling = "billing"
internal const val SettingsDestinationStorage = "storage"
internal const val SettingsDestinationReleaseNotes = "releaseNotes"
internal const val SettingsDestinationHandsFeedback = "hands-feedback"
/** Settings > Resources > Documentation: the docs site in the browser (web: a link, ↗). */
internal const val SettingsDestinationDocumentation = "documentation"

internal const val AppShellInitialTabKey = "slockInitialTab"
private const val AppShellRealtimeAuthRefreshAction = "authRefreshRequired"
/** WindowNavigationIntent.source of an external message link (#proj-mobile #25/#26). */
internal const val MessageLinkNavigationSource = "message_link"
private const val MessageLinkServerNamePlaceholder = "__raft_message_link_server__"
// AppShell child pages fill the native window. A floating bottom tab bar may
// overlay that page, but it does not turn the full-screen page into an inset
// card. Applying the iOS `liquid` host style here clips every screen edge to a
// fixed 28pt radius; the white AppShell surface then leaks between that radius
// and each iPad/window corner. Keep the page full-bleed and let the native
// window own its own corner mask. A future genuinely inset card must opt in at
// that inset host instead of coupling its shape to the tab-bar renderer.
@Suppress("UNUSED_PARAMETER")
internal fun appShellChildPageGlassStyle(bottomTabBarOverlaysContent: Boolean): String? = null

// xxchan 2026-10-20: the phone bar is 主页 · 动态 · 任务 · 更多 (+ the Search action); Members and
// Settings are pushed from More. They stay shell child pages so the wide rail, deep links and
// old launch values keep selecting them directly.
internal val AppShellChildPageTabKeys = listOf("home", "activity", "tasks", AppShellSearchTabKey, "more", "members", "settings")

internal typealias AppShellChildTabRenderer = @Composable (tab: String, active: Boolean) -> Unit

internal class AppShellChildTabRenderSlot(
    initialRenderer: AppShellChildTabRenderer
) {
    var renderer: AppShellChildTabRenderer by mutableStateOf(initialRenderer)
}

internal class AppShellChildTabEntry(
    val debugId: Int,
    val timestampDisplayState: RaftTimestampDisplayState = RaftTimestampDisplayState(),
    initialSelectedTab: String = "home",
    initialParentVisible: Boolean = true,
    initialRetainedTabs: Set<String> = setOf(initialSelectedTab),
    initialRenderer: AppShellChildTabRenderer = { _, _ ->
        RaftCenteredLoading()
    },
    initialTabKeys: List<String> = AppShellChildPageTabKeys,
    initialModalHostState: RaftUnifiedDialogHostState? = null
) {
    var selectedTab by mutableStateOf(initialSelectedTab)
    var parentVisible by mutableStateOf(initialParentVisible)
    var retainedTabs by mutableStateOf(initialRetainedTabs)
    private val renderSlots =
        initialTabKeys.associateWith {
            AppShellChildTabRenderSlot(initialRenderer)
        }
    // task #94: when the Slack-nav gate flips, the entry is re-registered with a different tab
    // key set while retained native child pages may still briefly compose against the previous
    // entry. Render slots therefore fail closed to the shell renderer (which itself falls back
    // to home content for unknown keys) instead of throwing on getValue.
    private val fallbackRenderer: AppShellChildTabRenderer = initialRenderer
    var serverStackRenderer: AppShellChildTabRenderer by mutableStateOf(initialRenderer)
    var modalHostState: RaftUnifiedDialogHostState? by mutableStateOf(initialModalHostState)

    fun rendererFor(tab: String): AppShellChildTabRenderer =
        renderSlots[tab]?.renderer ?: fallbackRenderer

    fun updateRenderer(tab: String, renderer: AppShellChildTabRenderer) {
        renderSlots[tab]?.let { slot -> slot.renderer = renderer }
    }

    fun updateSelection(
        selectedTab: String,
        retainedTabs: Set<String>,
        renderer: AppShellChildTabRenderer,
        parentVisible: Boolean = true,
        parentStarted: Boolean = parentVisible,
    ) {
        // Pushed server stack pages (Settings / Members on iOS) read the shell's latest renderer
        // even while the shell itself is covered (`CREATED`), matching Android's `RaftStackShellTabs`.
        this.serverStackRenderer = renderer
        // Update the selected tab's renderer while STARTED (including pop-back slide so fresh data
        // paints on Frame 1) or when the selected tab changed (deep link); skip only when covered
        // (`CREATED`) on the same tab so hidden recompositions do not churn child tabs.
        if (parentStarted || selectedTab != this.selectedTab) {
            updateRenderer(selectedTab, renderer)
        }
        this.retainedTabs = retainedTabs
        this.selectedTab = selectedTab
        this.parentVisible = parentVisible
    }
}

private data class AppShellChildTabRegistration(
    val entry: AppShellChildTabEntry,
    val replacedEntryId: Int
)

internal object AppShellChildTabRegistry {
    private val entries = mutableMapOf<String, AppShellChildTabEntry>()
    private val revisionFlow = MutableStateFlow(0L)
    val revision: StateFlow<Long> = revisionFlow
    private var nextDebugId = 0

    fun createEntryId(): Int {
        nextDebugId += 1
        return nextDebugId
    }

    fun register(shellId: String, entry: AppShellChildTabEntry): AppShellChildTabEntry? {
        val replaced = entries.put(shellId, entry)
        revisionFlow.value += 1L
        return replaced
    }

    fun unregister(shellId: String, entry: AppShellChildTabEntry): Boolean {
        return if (entries[shellId] === entry) {
            entries.remove(shellId)
            revisionFlow.value += 1L
            true
        } else {
            false
        }
    }

    fun entry(shellId: String): AppShellChildTabEntry? = entries[shellId]

    /** True while an app shell is composed (it registers its child tabs). */
    fun hasEntries(): Boolean = entries.isNotEmpty()
}

internal fun ScreenLoadState<SharedSettingsDeveloperSnapshot>.configuredWebBaseUrlOrNull(): String? =
    (this as? ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>)?.data?.webUrl

private fun isRetainedAppShellTab(tab: String): Boolean =
    tab in retainedAppShellTabKeys

private fun String.toHomeSecondaryOpenIntent(): SharedHomeShellIntent =
    when (retainedHomeSecondaryDestination(this)) {
        HomeDestinationActivity -> SharedHomeShellIntent.OpenActivity
        HomeDestinationSaved -> SharedHomeShellIntent.OpenSaved
        HomeDestinationSearch -> SharedHomeShellIntent.OpenSearch
        else -> SharedHomeShellIntent.CloseSecondaryDestination
    }

@Composable
private fun AppShellTabPager(
    selectedTab: String,
    pageVisible: Boolean,
    childHostShellId: String,
    mountedTabs: Set<String>,
    childTabSwitchEpoch: Int,
    childPageTabKeys: List<String> = AppShellChildPageTabKeys,
    /** Android's phone bottom tabs: Material fade through between child tabs (see the stack). */
    fadeThrough: Boolean = false,
    log: (String) -> Unit = {},
    content: @Composable (tab: String, active: Boolean) -> Unit
) {
    val activeTab = retainedAppShellSelectedTab(selectedTab)
    val activeChildTab = appShellActiveChildPagerTab(activeTab, childPageTabKeys)
    // Keepalive stack (V2): render every retained child tab, not just the active one,
    // so Home/Tasks/Members/Settings stay alive and switching back is instant with
    // full state. Only the selected child is visible; the rest are hard-hidden via the
    // native `active` guard (visibility=INVISIBLE), so an inactive child's late render
    // can never reach the visible layer. Pixel visibility follows the selected tab
    // even while this shell page is covered (`pageVisible` false). Hiding the
    // selected tab on disappear leaves a frame with no active child when a channel
    // page is removed before the shell's appear event, and the clear container
    // flashes white. Lifecycle still follows the pager appear events. There is no
    // HorizontalPager/scrollToPage, so none of the pager scroll races (overlay /
    // blank / stale native page) can occur. The active tab is always in the list
    // even if mountedTabs is momentarily stale.
    val retainedChildTabs =
        appShellRetainedChildTabs(activeChildTab, mountedTabs, childPageTabKeys)
    LaunchedEffect(activeChildTab, childTabSwitchEpoch, retainedChildTabs) {
        log(
            "app_shell_keepalive_stack active=$activeChildTab epoch=$childTabSwitchEpoch " +
                "retained=$retainedChildTabs mountedTabs=$mountedTabs"
        )
    }
    // The fade through (Android phone bar) animates these kept-alive layers; it never re-creates a
    // tab, so a switch keeps each tab's state and reloads nothing. Reduced motion: at once.
    val reducedMotion = rememberRaftReducedMotion()
    Box(modifier = Modifier.fillMaxSize()) {
        if (activeChildTab != null) {
            AppShellTabFadeThroughStack(
                activeTab = activeChildTab,
                tabs = retainedChildTabs,
                fadeThrough = fadeThrough,
                reducedMotion = reducedMotion,
            ) { tab, active, placed ->
                RaftAppShellChildTabHost(
                    shellId = childHostShellId,
                    tab = tab,
                    active = active,
                    placed = placed
                )
            }
        } else {
            key("app-shell-$activeTab") {
                content(activeTab, pageVisible)
            }
        }
    }
}

@Composable
private fun RaftAppShellChildTabHost(
    shellId: String,
    tab: String,
    active: Boolean,
    placed: Boolean = active
) {
    val glassStyle = appShellChildPageGlassStyle(raftAppBottomTabBarOverlaysContent())
    val pageData =
        remember(shellId, tab) {
            JSONObject().apply {
                put("shellId", shellId)
                put("tab", tab)
            }
        }
    key("app-shell-child-page-$tab") {
        RaftChildPage(
            pageName = RAFT_APP_SHELL_CHILD_TAB_PAGE,
            pageData = pageData,
            modifier =
                Modifier
                    .fillMaxSize(),
            active = active,
            placed = placed,
            glassStyle = glassStyle
        )
    }
}

@Composable
internal fun rememberRaftServerDrawerUserHeader(
    readySession: StoredAuthSession?
): RaftServerDrawerUserHeader =
    remember(
        readySession?.user?.id,
        readySession?.user?.displayName,
        readySession?.user?.name,
        readySession?.user?.email,
        readySession?.user?.avatarUrl,
        readySession?.currentServerId
    ) {
        val user = readySession?.user
        val displayName = user?.bestDisplayName ?: "Raft user"
        RaftServerDrawerUserHeader(
            displayName = displayName,
            memberId = user?.id,
            serverId = readySession?.currentServerId,
            avatarWireValue = user?.avatarUrl,
            avatarLabel = displayName.take(2).uppercase().ifBlank { "?" }
        )
    }

@Composable
internal fun RaftAppShellContent(
    pageVisibleTick: Int = 0,
    initialAuthRestoreTick: Int = 0,
    pageVisible: Boolean = true,
    adaptivePane: RaftAdaptivePaneState = RaftAdaptivePaneState(),
    conversationForwardRequestTick: Int = 0,
    /** Advances with each [RAFT_APP_SHELL_HOST_DID_APPEAR_PAGER_EVENT] (iOS). */
    hostDidAppearTick: Int = 0,
    /** A desktop shortcut or mouse button's step through the wide layout's history. */
    wideHistoryRequest: RaftWideNavRequest? = null,
    searchHandoff: RaftSearchTabQueryRequest? = null,
    onSearchHandoffApplied: () -> Unit = {},
    /** Advances with each ⌘K / Ctrl+K from the host window (the desktop app). */
    searchShortcutTick: Int = 0,
) {
    // #85 follow-up: seed the KmmResource `StringDesc.localeType`
    // global from the persisted `AppLocale` as early as possible
    // in the app shell lifecycle, so shared `StringDesc` lookups resolve
    // against the user's last selection rather than the OS default.
    RememberKmmResourceLocaleSeedFromLocalActivity(pageVisibleTick)
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    // The shell page's RaftDatabaseLaunchGate (BasePager, outside the environment indicator) has
    // already created the provider or shown the database recovery page, so this is the same
    // instance and never the first database open.
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val shellRuntimeUsageAccess = rememberShellRuntimeAccountUsageAccess()
    CompositionLocalProvider(LocalMembersRuntimeAccountUsageAccess provides shellRuntimeUsageAccess) {
        RaftAppShellReadyContent(
            pageVisibleTick = pageVisibleTick,
            initialAuthRestoreTick = initialAuthRestoreTick,
            pageVisible = pageVisible,
            adaptivePane = adaptivePane,
            conversationForwardRequestTick = conversationForwardRequestTick,
            hostDidAppearTick = hostDidAppearTick,
            wideHistoryRequest = wideHistoryRequest,
            searchHandoff = searchHandoff,
            onSearchHandoffApplied = onSearchHandoffApplied,
            searchShortcutTick = searchShortcutTick,
            sharedStores = sharedStores,
        )
    }
}

@Composable
private fun RaftAppShellReadyContent(
    pageVisibleTick: Int,
    initialAuthRestoreTick: Int,
    pageVisible: Boolean,
    adaptivePane: RaftAdaptivePaneState,
    conversationForwardRequestTick: Int,
    hostDidAppearTick: Int,
    wideHistoryRequest: RaftWideNavRequest? = null,
    searchHandoff: RaftSearchTabQueryRequest? = null,
    onSearchHandoffApplied: () -> Unit = {},
    searchShortcutTick: Int = 0,
    sharedStores: RaftSharedStoreProvider,
) {
    val errorStrings = localizedErrorPresentationStrings()
    val conversationNotReadyMessage = stringResource(Res.string.home_error_conversation_not_ready)
    val notificationServerUnavailableMessage = stringResource(Res.string.home_error_notification_server_unavailable)
    val notificationSignInMessage = stringResource(Res.string.home_error_notification_sign_in)
    val channelCreatedMessage = stringResource(Res.string.thread_action_card_success_channel)
    val channelNotCreatedMessage = stringResource(Res.string.channel_create_error_not_created)
    val channelUnarchiveFailedMessage = stringResource(Res.string.channel_create_failed_unarchive)
    val conversationUnavailableMessage = stringResource(Res.string.home_error_conversation_unavailable)
    val pager = LocalActivity.current.getPager() as Pager
    val timestampDisplayState =
        LocalRaftTimestampDisplayState.current ?: remember { RaftTimestampDisplayState() }
    val launchSelection = remember(pager.pageData.params.toString()) {
        pager.pageData.params.appShellLaunchSelection()
    }
    val diagnosticsDeepLinkRequest = remember(pager.pageData.params.toString()) {
        pager.pageData.params.diagnosticsDeepLinkRequest()
    }
    val suppressRestoreLanding = remember(pager.pageData.params.toString()) {
        pager.pageData.params.optBoolean(APP_SHELL_SUPPRESS_RESTORE_LANDING, false)
    }
    // task #45: the user-facing "Desktop mode on large screens" toggle (Settings >
    // Mobile, default ON) replaces the two QA-only adaptive params. The persisted
    // value lands via the chat-layout snapshot runtime authority below.
    val desktopModeOnLargeScreenState = remember {
        mutableStateOf(DEFAULT_DESKTOP_MODE_ON_LARGE_SCREEN)
    }
    var desktopModeOnLargeScreen by desktopModeOnLargeScreenState
    val authGateInstanceId = remember(pager.pageData.params.toString()) {
        pager.pageData.params.optString(APP_SHELL_AUTH_GATE_INSTANCE_ID)
    }
    val selectedTabState = remember { mutableStateOf(launchSelection.selectedTab) }
    var selectedTab by selectedTabState
    // Navigation 3 (Android, stack on): the host saves the tab under the stack's pages with them.
    AppShellStackRootTabEffect(
        selectedTabState = selectedTabState,
    )
    // Navigation 3 (Android, stack on): the window's shell tabs for the server pages pushed over
    // the shell (AppShellServerStackPage.kt); null when the shell is not a stack root.
    val stackShellTabs = LocalRaftStackShellTabs.current
    val childTabSwitchEpochState = remember { mutableStateOf(0) }
    var childTabSwitchEpoch by childTabSwitchEpochState
    // artin 2026-11-02: the tabs picked before this one; Back on a tab root walks back through them.
    val appShellTabHistoryState = remember { mutableStateOf(AppShellTabHistory()) }
    var appShellTabHistory by appShellTabHistoryState
    var homeFocusLossDismissEpoch by remember { mutableStateOf(0) }
    val retainedAppShellTabsState = remember {
        mutableStateOf(setOf("home", launchSelection.selectedTab))
    }
    var retainedAppShellTabs by retainedAppShellTabsState
    val prewarmedAppShellTabsState = remember { mutableStateOf(emptySet<String>()) }
    var prewarmedAppShellTabs by prewarmedAppShellTabsState
    val prewarmAdmissionGate = remember { sharedPrewarmAdmissionGate() }
    val childPagePrewarmPolicy =
        remember { appShellChildPagePrewarmPolicy(raftPlatformAppShellChildPagePrewarmMode()) }
    val settingsHeaderTaps = remember { SettingsDeveloperUnlockTaps() }
    var homeListPositions by remember { mutableStateOf(AppShellServerLazyListPositions()) }
    val pendingHomeServerSwitchIdState = remember { mutableStateOf<String?>(null) }
    var pendingHomeServerSwitchId by pendingHomeServerSwitchIdState
    val observedHomeServerSwitchIdState = remember { mutableStateOf<String?>(null) }
    var observedHomeServerSwitchId by observedHomeServerSwitchIdState
    var tasksListPosition by remember { mutableStateOf(AppShellLazyListPosition()) }
    var membersListState by remember { mutableStateOf(AppShellMembersListState()) }
    var tabScrollToTopRequests by remember { mutableStateOf(AppShellTabScrollToTopRequests()) }
    val tasksSurfaceActive = selectedTab == "tasks"
    // D1a (raft-design #712): the server panel the current-server tab (phone bar) or the rail's
    // foot entry opens; the page that showed when it opened a server page (Back returns there);
    // and focus going back to the server tab after such a page closes.
    val serverMenuState = rememberRaftMobileNavServerPopoverState()
    val serverPageOriginState = remember { mutableStateOf<String?>(null) }
    var serverPageOrigin by serverPageOriginState
    val serverTabFocusPendingState = remember { mutableStateOf(false) }
    var serverTabFocusPending by serverTabFocusPendingState
    val serverTabFocusOnPageReturnState = remember { mutableStateOf(false) }
    var serverTabFocusOnPageReturn by serverTabFocusOnPageReturnState
    // Server switcher "Invite Human" (web ServerSwitcherMenu): the server whose
    // invite dialog is open, or null.
    var serverSwitcherInviteServerId by remember { mutableStateOf<String?>(null) }
    // task #13 (three-pane contract): while the wide LeftRail is active, Search is a rail
    // destination rendered INLINE in the AppShell middle pane — never a pushed secondary page
    // (the rail must stay). Narrow or flag-off keeps the legacy push path unchanged.
    val railInlineSecondaryDestinationState = remember { mutableStateOf<String?>(null) }
    var railInlineSecondaryDestination by railInlineSecondaryDestinationState
    // The inline Search's field focuses as it opens; opening it again while it is open (top bar
    // search field, the Duo rail's Search) focuses the field again, like web's search shortcut.
    var railInlineSearchFocusRequest by remember { mutableStateOf(0) }
    // "Search for" hands the panel's query to the list column's Search; each hand-over is new.
    var searchOverlayHandoffNonce by remember { mutableStateOf(-1L) }
    // page_viewed for the wide rail's inline list panes (Search, Saved): they are not pages of their own.
    AppShellRailInlineProductPageEffect(
        pager = pager,
        railInlineSecondaryDestinationState = railInlineSecondaryDestinationState,
    )
    // The Search tab's result list position survives leaving and re-entering the tab.
    val searchTabListState = rememberLazyListState()
    val searchTabReturnHighlight = remember { HomeSearchReturnHighlight() }
    // Re-tapping the selected Search tab focuses its field (one increment per tap).
    var searchTabFocusInputRequest by remember { mutableStateOf(0) }
    // A query a conversation search handed to the Search tab ("Search all"), until applied.
    var searchTabQueryRequest by remember { mutableStateOf<RaftSearchTabQueryRequest?>(null) }
    // task #13: once a rail surface exposes Activity (rail active or an inline destination is
    // covering the middle pane) the live tab must survive width transitions — narrowing must
    // not clamp a user-reached Activity selection back to Home.
    val railActivityReachedState = remember { mutableStateOf(false) }
    var railActivityReached by railActivityReachedState
    val childTabShellId = remember(pager.pagerId) { "app-shell-child-tabs-${pager.pagerId}" }
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val storeScope = remember(pager.pageData.params.toString(), pager.pagerId) {
        pager.pageData.params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
    }
    val adaptiveWorkspaceStore =
        remember(sharedStores, storeScope) { sharedStores.adaptiveWorkspaceStore(storeScope) }
    val adaptiveWorkspaceSessionState = adaptiveWorkspaceStore.state.collectAsState()
    val adaptiveWorkspaceSession by adaptiveWorkspaceSessionState
    val adaptiveWorkspaceState = adaptiveWorkspaceSession.workspace
    val adaptiveDetailLaunchPayload = adaptiveWorkspaceSession.launchPayload
    val adaptiveDefaultLandingServerId = adaptiveWorkspaceSession.defaultLandingServerId
    // task #34: a task opened from the Tasks page owns the task column beside the list (web
    // TasksRoute: the task a column of the row, the list the conversation's). Expanded, it fills
    // the row (web Expand, the thread's Focus mode). The id only marks the open task's card.
    val tasksDetailColumnActiveState = remember { mutableStateOf(false) }
    var tasksDetailColumnActive by tasksDetailColumnActiveState
    val tasksDetailColumnExpandedState = remember { mutableStateOf(false) }
    var tasksDetailColumnExpanded by tasksDetailColumnExpandedState
    var tasksDetailColumnTaskId by remember { mutableStateOf<String?>(null) }
    // Which task each column detail showed, so a history restore marks the reopened task's card.
    var tasksDetailColumnTaskIds by remember { mutableStateOf(RaftTasksColumnTaskIds()) }
    rememberAuthRuntimeCapabilities(
        store = sharedStores.authRuntimeCapabilityStore,
        pageData = pager.pageData.params
    )
    val visibleAppearanceGeneration =
        appShellAppearanceGeneration(pageVisibleTick, initialAuthRestoreTick)
    val windowNavigationCoordinator =
        remember(sharedStores, storeScope) { sharedStores.windowNavigationCoordinator(storeScope) }
    val conversationForwardHistory =
        remember(sharedStores, storeScope) { sharedStores.conversationForwardHistory(storeScope) }
    val conversationForwardHomeReturns = remember { ConversationForwardHomeReturnSignals() }
    var handledConversationForwardRequestTick by remember { mutableStateOf(0) }
    AppShellAppearanceEffects(
        sharedStores = sharedStores,
        pager = pager,
        bridge = bridge,
        storeScope = storeScope,
        visibleAppearanceGeneration = visibleAppearanceGeneration,
    )
    val authStore = sharedStores.authStore
    val shellAuthStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val authSessionTerminalSource =
        remember(shellAuthStore) { shellAuthStore.authSessionTerminalSourceOrNull() }
    val selectedServerStore = remember(sharedStores, storeScope) { sharedStores.selectedServerStore(storeScope) }
    val appShellServerMembersStore =
        remember(sharedStores, storeScope) { sharedStores.serverMembersStore(storeScope) }
    val appShellRealtimeDomainEventOwner =
        remember(sharedStores, storeScope) { sharedStores.realtimeDomainEventOwner(storeScope) }
    val canonicalFinalLifecycleEffects =
        remember(sharedStores) {
            sharedStores.realtimeAuthorityProvider.canonicalFinalLifecycleEffectsOrNull()
        }
    val realtimeAuthorityFlow =
        remember(sharedStores, storeScope) { sharedStores.realtimeAuthority(storeScope) }
    val realtimeAuthority by realtimeAuthorityFlow.collectAsState()
    val processRealtimeAuthorityFlow =
        remember(sharedStores) { sharedStores.processRealtimeAuthority() }
    val processRealtimeAuthorityState = processRealtimeAuthorityFlow.collectAsState()
    val processRealtimeAuthority by processRealtimeAuthorityState
    val realtimeCallbackGate =
        remember(sharedStores, appShellRealtimeDomainEventOwner) {
            RealtimeShellCallbackGate(
                authorityProvider = sharedStores.realtimeAuthorityProvider,
                domainOwner = appShellRealtimeDomainEventOwner,
            )
        }
    val appShellAgentDetailStore =
        remember(sharedStores, storeScope) { sharedStores.agentDetailStore(storeScope) }
    val appShellPresenceTransportCursors =
        remember(sharedStores, storeScope) { mutableMapOf<String, AppShellPresenceTransportCursor>() }
    val selectedServerIdState = selectedServerStore.selectedServerId.collectAsState()
    val selectedServerId by selectedServerIdState
    val canonicalFinalSelectedServerId =
        selectedServerId?.takeIf { it.isNotBlank() }
            ?: storeScope.initialServerId?.takeIf { it.isNotBlank() }
    val canonicalFinalDomainGrant =
        remember(realtimeAuthority, canonicalFinalSelectedServerId) {
            val serverId = canonicalFinalSelectedServerId
            if (serverId == null) null else realtimeAuthority?.grant(serverId)
        }
    val canonicalFinalUnreadGrants =
        remember(realtimeAuthority, canonicalFinalSelectedServerId) {
            realtimeAuthority?.grantsByServer?.values
                ?.filter { grant -> grant.serverId != canonicalFinalSelectedServerId }
                ?.sortedBy(RealtimeGrantRecord::serverId)
                .orEmpty()
        }
    val canonicalFinalDomainSlot =
        remember(canonicalFinalDomainGrant) {
            canonicalFinalDomainGrant?.let { grant -> grant.scopeLeaseId to grant.serverId }
        }
    val canonicalFinalUnreadSlots =
        remember(canonicalFinalUnreadGrants) {
            canonicalFinalUnreadGrants.map { grant -> grant.scopeLeaseId to grant.serverId }
        }
    val activeAppShellCanonicalFinalSinksState =
        remember { mutableStateOf<RealtimeGuardedDomainEventSinks?>(null) }
    var activeAppShellCanonicalFinalSinks by activeAppShellCanonicalFinalSinksState
    AppShellCanonicalFinalSinkEffect(
        appShellRealtimeDomainEventOwner = appShellRealtimeDomainEventOwner,
        canonicalFinalLifecycleEffects = canonicalFinalLifecycleEffects,
        selectedServerIdState = selectedServerIdState,
        canonicalFinalSelectedServerId = canonicalFinalSelectedServerId,
        canonicalFinalDomainSlot = canonicalFinalDomainSlot,
        canonicalFinalUnreadSlots = canonicalFinalUnreadSlots,
        activeAppShellCanonicalFinalSinksState = activeAppShellCanonicalFinalSinksState,
    )
    val appShellCoroutineScope = rememberCoroutineScope()
    // Hands (artin 2026-10-22): raw host Offline waits out a 3s grace before the banner shows.
    val networkBannerGate =
        remember(appShellCoroutineScope) { RaftAppShellNetworkBannerGate(appShellCoroutineScope) }
    val hostNetworkStatus by networkBannerGate.bannerStatus.collectAsState()
    val avatarNativeCache =
        remember(bridge, appShellCoroutineScope) {
            BridgeRaftAvatarNativeCache(
                bridge = bridge,
                bridgeCallContext = appShellCoroutineScope.kuiklyBridgeCallContext()
            )
        }
    val authDataSource = remember(sharedStores, storeScope, avatarNativeCache) {
        sharedStores.createAuthDataSourceForCurrentEnvironment(storeScope, avatarNativeCache)
    }
    val eventBus = sharedStores.eventBus
    /**
     * task #180: the menu a child page has asked the shell to draw. Null when no page is asking.
     * The shell is the only place that can draw outside a child page's pane, which is why this
     * lives here rather than in the requesting page.
     */
    var shellOverlayMenu by remember { mutableStateOf<RaftShellOpenOverlay?>(null) }
    var shellOverlayContent by remember { mutableStateOf<RaftShellOverlayContentEntry?>(null) }
    DisposableEffect(eventBus) {
        val subscription =
            eventBus.observe(RAFT_SHELL_OVERLAY_REQUEST_EVENT) { eventData ->
                // task #180 diagnostics (Argus): point 2 of 3 -- the shell saw a request (or why not).
                val messageType = eventData?.optString(SHELL_OVERLAY_MESSAGE_TYPE)
                val incomingRequestId = eventData?.shellOverlayRequestIdOrNull()
                if (messageType == SHELL_OVERLAY_TYPE_CANCEL) {
                    val current = shellOverlayMenu
                    if (current != null && current.request.requestId == incomingRequestId) {
                        shellOverlayContent = null
                        shellOverlayMenu = null
                    }
                    return@observe
                }
                val request = eventData.toRaftShellOverlayRequestOrNull()
                raftTraceToPlatformLog(
                    "SlockShellOverlay",
                    "shell_observe parsed=${request != null} requestId=${request?.requestId} " +
                        "pageKey=${request?.pageKey} anchor=${request?.anchor} items=${request?.items?.size} " +
                        "rawRequestId=${eventData?.optString(SHELL_OVERLAY_REQUEST_ID)} " +
                        "type=${eventData?.optString(SHELL_OVERLAY_MESSAGE_TYPE)}"
                )
                if (request == null) return@observe
                // Ignore a request superseded by another page, and honour a cancel from the page
                // that owns the menu currently on screen.
                val current = shellOverlayMenu
                if (!raftShellShouldDropOverlayRequest(current?.request?.requestId, request.requestId)) {
                    shellOverlayContent = RaftShellOverlayContentRegistry.take(request.requestId)
                    shellOverlayMenu = RaftShellOpenOverlay(request)
                }
            }
        onDispose { subscription.close() }
    }
    val authSessionEvents =
        remember(eventBus, pager.pagerId) {
            RaftAuthSessionEvents(
                eventBus = eventBus,
                source = "app-shell:${pager.pagerId}"
            )
        }
    val authGateViewModel: SharedAuthGateViewModel =
        viewModel(key = appShellAuthGateViewModelKey(pager.pagerId, authGateInstanceId)) {
            SharedAuthGateViewModel(authDataSource)
        }
    val authGateStateState = remember { mutableStateOf(authGateViewModel.state.value) }
    var authGateState by authGateStateState
    LaunchedEffect(authGateViewModel) {
        authGateViewModel.state.collect { nextState ->
            authGateState = nextState
        }
    }
    val cachedRestoreSessionState = remember { mutableStateOf<StoredAuthSession?>(null) }
    var cachedRestoreSession by cachedRestoreSessionState
    AppShellAuthRestoreEffects(
        initialAuthRestoreTick = initialAuthRestoreTick,
        shellAuthStore = shellAuthStore,
        authGateViewModel = authGateViewModel,
        authGateStateState = authGateStateState,
        cachedRestoreSessionState = cachedRestoreSessionState,
    )
    val authGateMessageState = remember { mutableStateOf<AuthGateMessage?>(null) }
    val authSessionResetGenerationState = remember { mutableStateOf(0) }
    val appliedAuthSessionResetGenerationState = remember { mutableStateOf(0) }
    AppShellAuthSessionEffects(
        sharedStores = sharedStores,
        pager = pager,
        bridge = bridge,
        authStore = authStore,
        shellAuthStore = shellAuthStore,
        authSessionTerminalSource = authSessionTerminalSource,
        appShellCoroutineScope = appShellCoroutineScope,
        authSessionEvents = authSessionEvents,
        authGateViewModel = authGateViewModel,
        authGateMessageState = authGateMessageState,
        authSessionResetGenerationState = authSessionResetGenerationState,
    )
    // Preserve the cheap bootstrap-only path for signed-out/unknown sessions. A valid local
    // session is discovered by the effect above; once present, the Home graph is allowed to
    // materialize so its identity-bound durable snapshot can paint before remote auth settles.
    // White-screen diagnostics: the shell's first frame of real content (not the restore splash /
    // frame) and, when it has not come within a second, what it still waits on.
    val appShellContentStageState = remember { mutableStateOf<RaftWhiteScreenStage?>(RaftWhiteScreenStage.Session) }
    var appShellContentStage by appShellContentStageState
    RaftFirstContentWatch(
        page = "app_shell",
        stage =
            if (authGateState == SharedAuthGateState.Restoring && cachedRestoreSession == null) {
                RaftWhiteScreenStage.Session
            } else {
                appShellContentStage
            },
    )
    if (authGateState == SharedAuthGateState.Restoring && cachedRestoreSession == null) {
        if (suppressRestoreLanding) {
            AppShellNewWindowRestoreFrame(adaptivePane = adaptivePane, selectedTab = selectedTab)
        } else {
            // Web #15116: a slow restore offers Try again and Sign out instead of waiting it out.
            AppShellColdRestoreSplash(
                authDataSource = authDataSource,
                bridge = bridge,
                appShellCoroutineScope = appShellCoroutineScope,
                authGateViewModel = authGateViewModel,
                authGateMessageState = authGateMessageState,
                authSessionResetGenerationState = authSessionResetGenerationState,
            )
        }
        return
    }
    val shellScope = rememberRaftAppShellScope(adaptivePane)
    val appShellNetworkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val linkNavigator = rememberRaftLinkNavigator(pager, bridge)
    val copiedMessage = stringResource(Res.string.common_copied)
    val settingsCapabilities =
        remember(bridge, appShellCoroutineScope, linkNavigator, copiedMessage) {
            BridgeSettingsPlatformCapabilities(
                bridge = bridge,
                copiedMessage = copiedMessage,
                bridgeCallContext = appShellCoroutineScope.kuiklyBridgeCallContext(),
                openUrlHandler = { url -> linkNavigator.open(url) }
            )
        }
    val sharedPreferenceStore = remember { raftSharedPreferenceStore() }
    val memberRepository =
        remember(shellAuthStore, appShellNetworkClient, appShellServerMembersStore) {
            RealMemberRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                logger = raftLogger("SlockAppShell", "app_shell_member_repository"),
                cachedMembersProvider = appShellServerMembersStore::currentServerMembers
            )
        }
    val homeDataSource =
        remember(
            memberRepository,
            appShellServerMembersStore,
            settingsCapabilities,
        ) {
            SharedHomeDataSource(
                sessionStore = shellAuthStore,
                api = NetworkRaftHomeApi(appShellNetworkClient),
                announcementsApi = NetworkRaftAnnouncementsRemindersApi(appShellNetworkClient),
                computersApi = RaftComputersNetworkApi(appShellNetworkClient),
                appUpdateStatusProvider = {
                    when (val result = settingsCapabilities.checkHandsUpdateStatus()) {
                        is DataResult.Content ->
                            DataResult.Content(result.data.withSkippedAppUpdateVersionApplied())
                        is DataResult.Empty -> result
                        is DataResult.Error -> result
                        is DataResult.NoPermission -> result
                    }
                },
                memberRepository = memberRepository,
                serverMembersStore = appShellServerMembersStore,
                parseHome = { payload, session ->
                    parseRaftHome(payload, session, raftLogger("SlockHomeParse"))
                },
                diagnosticsLogger = raftLogger("SlockSharedHome", "shared_home")
            )
        }
    val channelRepository =
        remember(shellAuthStore, appShellNetworkClient) {
            RealChannelRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                warningLogger = raftLogger("SlockChannelRepository", "shared_channel_repository", RaftLogLevel.Warn)
            )
        }
    // Pending joint-channel invites for the Home notification center (web usePendingJointInvites).
    val jointInviteScope = rememberCoroutineScope()
    val jointInviteStore =
        remember(appShellNetworkClient, jointInviteScope) {
            build.raft.app.data.SharedJointInviteStore(
                dataSource =
                    build.raft.app.data.NetworkJointInviteDataSource(
                        networkClient = appShellNetworkClient,
                        logger = raftLogger("SlockJointInvites", "shared_joint_invites", RaftLogLevel.Warn),
                    ),
                scope = jointInviteScope,
            )
        }
    // web #12939: `joint-invites:changed` reads the pending invites again right away.
    AppShellJointInviteEffect(
        appShellRealtimeDomainEventOwner = appShellRealtimeDomainEventOwner,
        jointInviteStore = jointInviteStore,
    )
    // Pending App permission updates / admin review dot for owners & admins (#proj-mobile:#345).
    val appPermissionUpdatesStore =
        remember(appShellNetworkClient, jointInviteScope) {
            build.raft.app.data.SharedAppPermissionUpdatesStore(
                dataSource = build.raft.app.data.NetworkAppPermissionUpdatesDataSource(appShellNetworkClient),
                scope = jointInviteScope,
            )
        }
    // A pending two-step verification reset, first in the Home notification center (web #12295).
    val secondFactorRecoveryStore =
        remember(appShellNetworkClient, jointInviteScope) {
            build.raft.app.data.SharedSecondFactorRecoveryStore(
                dataSource = build.raft.app.data.NetworkSecondFactorRecoveryDataSource(appShellNetworkClient),
                scope = jointInviteScope,
            )
        }
    val homeReadReceiptRepository =
        remember(shellAuthStore, appShellNetworkClient) {
            RealReadReceiptRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                warningLogger = raftLogger("SlockReadReceipt", "shared_home_read_receipt", RaftLogLevel.Warn)
            )
        }
    val conversationRepository =
        remember(shellAuthStore, appShellNetworkClient, selectedServerStore) {
            RealConversationRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                routeServerIdProvider = { selectedServerStore.selectedServerId.value },
                warningLogger = raftLogger("SlockConversationRepository", "shared_conversation_repository", RaftLogLevel.Warn)
            )
        }
    val agentsApiClient =
        remember(appShellNetworkClient) {
            RaftAgentsApiClient(
                transport = RaftAgentsNetworkApi(appShellNetworkClient),
                diagnosticsLogger = raftLogger("SlockAgentsApi", "app_shell_agents_api")
            )
        }
    val computersApiClient =
        remember(appShellNetworkClient) {
            RaftComputersApiClient(RaftComputersNetworkApi(appShellNetworkClient))
        }
    val createAgentFormStore =
        remember(sharedStores, storeScope) { sharedStores.createAgentFormStore(storeScope) }
    val createAgentCoordinator =
        remember(agentsApiClient, computersApiClient) {
            SharedCreateAgentCoordinator(
                agentsApiClient = agentsApiClient,
                computersApiClient = computersApiClient,
                logger = raftLogger("SlockCreateAgent", "app_shell_thread_create_agent")
            )
        }
    val savedRepository =
        remember(shellAuthStore, appShellNetworkClient) {
            RealSavedRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                warningLogger = raftLogger("SlockSavedRepository", "shared_saved_repository", RaftLogLevel.Warn)
            )
        }
    val searchRepository =
        remember(shellAuthStore, appShellNetworkClient) {
            RealSearchRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                warningLogger = raftLogger("SlockSearchRepository", "shared_search_repository", RaftLogLevel.Warn)
            )
        }
    val composerDraftPersistSlice = sharedStores.composerDraftPersistSlice
    val activeDraftRecords by composerDraftPersistSlice.activeDraftRecordsState.collectAsStateWhileStarted()
    val draftProjection =
        remember(activeDraftRecords) {
            activeDraftRecords.toSharedThreadComposerDraftProjection()
        }
    val localePreferenceStore =
        remember(sharedPreferenceStore) {
            KeyValueLocalePreferenceStore(sharedPreferenceStore)
        }
    val chatLayoutPreferenceEvents =
        remember(eventBus) { RaftChatLayoutPreferenceEvents(eventBus) }
    val chatLayoutPreferenceRepository =
        remember(sharedPreferenceStore, chatLayoutPreferenceEvents) {
            raftChatLayoutPreferenceRepository(
                delegate =
                    PersistentChatLayoutPreferenceRepository(
                        KeyValueChatLayoutPreferenceStore(sharedPreferenceStore)
                    ),
                events = chatLayoutPreferenceEvents
            )
        }
    val appUpdateEvents = remember(eventBus) { RaftAppUpdateEvents(eventBus) }
    val realtimeEvents = remember(eventBus) { RaftRealtimeEvents(eventBus) }
    val hostNetworkEvents = remember(eventBus) { RaftHostNetworkEvents(eventBus) }
    val homeActivityRepository =
        remember(shellAuthStore, appShellNetworkClient) {
            RealInboxRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                logger = raftLogger("SlockHomeActivity", "shared_home_activity"),
                warningLogger = raftLogger("SlockHomeActivity", "shared_home_activity_warning", RaftLogLevel.Warn)
            )
        }
    val homeSidebarOrderRepository =
        remember(shellAuthStore, appShellNetworkClient) {
            RealSettingsRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                logger = raftLogger("SlockHomeSidebarOrder", "shared_home_sidebar_order"),
                warningLogger = raftLogger("SlockHomeSidebarOrder", "shared_home_sidebar_order_warning", RaftLogLevel.Warn)
            )
        }
    val homeStore =
        remember(
            sharedStores,
            storeScope,
            homeDataSource,
            homeSidebarOrderRepository,
            homeReadReceiptRepository,
            channelRepository,
            appShellCoroutineScope
        ) {
            sharedStores.getOrCreateHomeStore(
                scope = storeScope,
                dataSource = homeDataSource,
                diagnosticsLogger = raftLogger("SlockSharedHome", "shared_home"),
                loadSidebarOrder = { serverId -> homeSidebarOrderRepository.getServerSidebarOrder(serverId) },
                updateSidebarOrder = { request -> homeSidebarOrderRepository.updateServerSidebarOrder(request) },
                loadServerOrder = { homeSidebarOrderRepository.getServerOrder() },
                updateServerOrder = { request -> homeSidebarOrderRepository.updateServerOrder(request) },
                markChannelReadAll = { channelId -> homeReadReceiptRepository.markChannelReadAll(channelId) },
                markChannelUnread = { channelId -> homeReadReceiptRepository.markChannelUnread(channelId) },
                markConversationReadAll = { channelId -> homeReadReceiptRepository.markConversationReadAll(channelId) },
                archiveChannel = { channelId -> channelRepository.archiveChannel(channelId) },
                leaveChannel = { channelId -> channelRepository.leaveChannel(channelId) },
                setChannelActivityMuted = { channelId, muted -> channelRepository.setChannelActivityMuted(channelId, muted) },
                pinUpdateFailureWithTraceCallback = { message, traceId -> bridge.errorToast(message, traceId) },
            )
        }
    AppShellHomeStoreEffects(
        bridge = bridge,
        conversationRepository = conversationRepository,
        homeStore = homeStore,
    )
    val homeActivityReplyThreadPrewarmDataSource =
        remember(
            shellAuthStore,
            appShellNetworkClient,
            conversationRepository,
            sharedStores.threadMessageStore,
            sharedStores.threadPayloadStore,
            sharedStores.threadPendingSendJournal,
            sharedStores.apiEnvironmentStore
        ) {
            SharedThreadDataSource(
                networkApi = NetworkRaftThreadApi(appShellNetworkClient),
                sessionStore = shellAuthStore,
                pendingSendJournal = sharedStores.threadPendingSendJournal,
                selectedServerStore = selectedServerStore,
                conversationRepository = conversationRepository,
                messageStore = sharedStores.threadMessageStore,
                cacheEnvironmentIdProvider = {
                    sharedStores.apiEnvironmentStore.current().apiBaseUrl
                },
                apiEnvironmentIdProvider = {
                    sharedStores.apiEnvironmentStore.current().apiBaseUrl
                },
                replyCacheIdentityAuthority = {
                    sharedStores.captureThreadReplyCacheIdentity(storeScope)
                },
                replyCacheIdentityTransitionWaiter = { transitionAnchor ->
                    sharedStores.awaitThreadReplyCacheIdentityTransition(storeScope, transitionAnchor)
                },
                payloadStore = sharedStores.threadPayloadStore,
                durableLastKnownReader = sharedStores.threadDurableLastKnownReader,
                enteredScopeSnapshotLoader = sharedStores.threadEnteredScopeSnapshotLoader,
                durablePageExtender = sharedStores.threadDurablePageExtender,
                readReceiptCoordinator = sharedStores.processReadReceiptCoordinator(),
                readReceiptOwnerFactory = sharedStores::readReceiptOwner,
                threadPageSizeProvider = { serverId ->
                    sharedStores.serverFeatureFlags.snapshotFor(serverId).mobileThreadPageSize()
                },
                diagnosticsLogger = raftLogger("SlockHomeActivity", "shared_home_activity_thread_prewarm")
            )
        }
    val homeActivityStore =
        remember(
            storeScope,
            shellAuthStore,
            homeActivityRepository,
            homeReadReceiptRepository,
            conversationRepository,
            memberRepository,
            appShellServerMembersStore,
            channelRepository,
            homeStore,
            homeActivityReplyThreadPrewarmDataSource
        ) {
            sharedStores.getOrCreateHomeActivityStore(
                scope = storeScope,
                inboxRepository = homeActivityRepository,
                readReceiptRepository = homeReadReceiptRepository,
                conversationRepository = conversationRepository,
                memberRepository = memberRepository,
                channelRepository = channelRepository,
                prewarmReplyThread = homeActivityReplyThreadPrewarmDataSource::prewarmReplyThread,
                replyThreadContentCached = homeActivityReplyThreadPrewarmDataSource::hasCurrentReplyThreadContent,
                // Hidden Home folds these into one read when it shows (fb-52cc0499 request storm).
                onUnreadCountsChanged = { homeStore.requestUnreadCountsReconcile(reason = "activity_unread_counts") },
                // task #436: shared store — both call sites must use one identity.
                diagnosticsLogger = sharedActivityStoreDiagnosticsLogger()
            )
        }
    val appShellComputersStore =
        remember(sharedStores, storeScope) { sharedStores.computersStore(storeScope) }
    RaftRatingPromptShellBinding(authStore = shellAuthStore, computersStore = appShellComputersStore)
    val settingsRepository =
        remember(sharedStores, shellAuthStore, appShellNetworkClient) {
            RealSettingsRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                logger = raftLogger("SlockSettingsRepository", "shared_settings_repository"),
                warningLogger = raftLogger("SlockSettingsRepository", "shared_settings_repository_warning", RaftLogLevel.Warn),
                channelRepository = channelRepository,
                onAppearanceSnapshot = { ownerId, snapshot ->
                    messageBodyFontSizeOwnerKey(
                        sharedStores.apiEnvironmentStore.current().apiBaseUrl,
                        ownerId,
                    )?.let { ownerKey ->
                        sharedStores.messageBodyFontSizeStore.acceptAuthoritative(ownerKey, snapshot)
                    }
                },
                serverNotificationLastKnownStore = sharedStores.settingsServerNotificationLastKnownStore,
                onTimeFormatUpdated = RaftTimeFormatRuntime::update,
                onTimeZoneUpdated = RaftTimeZoneRuntime::update,
                onComposerSendKeyUpdated = RaftComposerSendKeyRuntime::setSendKey,
            )
        }
    val myMessageAlignmentState = remember { mutableStateOf(MyMessageAlignmentPreference.Default) }
    var myMessageAlignment by myMessageAlignmentState
    val compactConsecutiveMessagesState = remember { mutableStateOf(false) }
    var compactConsecutiveMessages by compactConsecutiveMessagesState
    val foregroundMessageBannersEnabledState = remember { mutableStateOf(false) }
    var foregroundMessageBannersEnabled by foregroundMessageBannersEnabledState
    val serverDrawerEnabledState = remember { mutableStateOf(DEFAULT_SERVER_DRAWER_ENABLED) }
    var serverDrawerEnabled by serverDrawerEnabledState
    val conversationForwardGestureEnabledState = remember { mutableStateOf(false) }
    var conversationForwardGestureEnabled by conversationForwardGestureEnabledState
    val serverDrawerProgrammaticCloseFenceState = remember { mutableStateOf<((String) -> Unit)?>(null) }
    var serverDrawerProgrammaticCloseFence by serverDrawerProgrammaticCloseFenceState
    var createChannelDialogOpen by remember { mutableStateOf(false) }
    // Web SidebarDialogs: the External section's "+" opens Create Channel with External chosen.
    var createChannelExternalChosen by remember { mutableStateOf(false) }
    // Each opening of Create Channel: its External store lives for that whole opening.
    var createChannelSession by remember { mutableStateOf(0) }
    var createChannelSubmitting by remember { mutableStateOf(false) }
    // task #226: global announcement modal. The API client already exists (the
    // dismiss is server-persisted per user); the shell fetches the latest active
    // announcement on session-ready and refetches on the announcement:new socket
    // event (RealtimeEventMapper maps it to RealtimeRefreshScope.Announcements,
    // which the home store deliberately ignores — the modal is the sole consumer).
    val announcementsClient =
        remember(appShellNetworkClient) {
            RaftAnnouncementsRemindersApiClient(NetworkRaftAnnouncementsRemindersApi(appShellNetworkClient))
        }
    var createChannelError by remember { mutableStateOf<String?>(null) }
    // The refused create's trace ID, shown under [createChannelError] (AGENTS.md "Errors carry the trace ID").
    var createChannelErrorTraceId by remember { mutableStateOf<String?>(null) }
    // Web ArchivedNameCollisionBanner (slock #12614): an archived channel holds the name.
    var createChannelCollision by remember { mutableStateOf<ArchivedNameCollision?>(null) }
    var createChannelMembersLoading by remember { mutableStateOf(false) }
    var createChannelMembers by remember { mutableStateOf<List<MemberProfile>>(emptyList()) }
    val baseHostThreadTimeCapabilities = remember(pager.pageData.params.toString()) {
        pager.pageData.params.threadTimeCapabilities()
    }
    val hostTimeFormat =
        RaftTimeFormatRuntime.observe(fallback = baseHostThreadTimeCapabilities?.timeFormat ?: RaftTimeFormat24Hour)
    val hostPreferredTimeZone = RaftTimeZoneRuntime.observe()
    val hostThreadTimeCapabilities = remember(baseHostThreadTimeCapabilities, hostTimeFormat, hostPreferredTimeZone) {
        baseHostThreadTimeCapabilities?.withRuntimePreferences(
            timeFormat = hostTimeFormat,
            preferredTimeZoneId = hostPreferredTimeZone,
        )
    }
    val fallbackHostTimeContextProvider = currentRaftTimeContextProvider
    val ownHostThreadTimeContextProvider =
        rememberLiveRaftTimeContext(
            capabilities = hostThreadTimeCapabilities,
            pageVisibleTick = pageVisibleTick,
            active = hostThreadTimeCapabilities != null,
        )
    val hostThreadTimeContextProvider =
        ownHostThreadTimeContextProvider.takeIf { hostThreadTimeCapabilities != null }
            ?: fallbackHostTimeContextProvider
            ?: ownHostThreadTimeContextProvider
    val currentHostThreadTimeContextProvider = rememberUpdatedState(hostThreadTimeContextProvider)
    val notificationWindowNavigationIntent =
        remember(pager.pageData.params.toString()) {
            pager.pageData.params.appShellWindowNavigationIntent(
                fallbackServerId =
                    pager.pageData.params
                        .raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
                        .initialServerId
            )
        }
    val desktopShortcutWindowNavigationIntent =
        remember(pager.pageData.params.toString()) {
            pager.pageData.params.appShellDesktopShortcutNavigationIntent(
                fallbackServerId =
                    pager.pageData.params
                        .raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
                        .initialServerId
            )
        }
    // #4260: links and notification opens are taken and opened by this intake, which does not wait
    // for the shell to recompose (a covered shell does not).
    val navigationIntake =
        remember(windowNavigationCoordinator) { RaftShellNavigationIntake(windowNavigationCoordinator) }
    // A notification without a conversation (reminder, notice, test, agent route; contract §4.5):
    // switch to its server and open Activity. From the launch pageData (Android) or the open event (iOS).
    val pendingNotificationActivityServerIdState = remember(pager.pageData.params.toString()) {
        mutableStateOf(pager.pageData.params.appShellNotificationActivityServerId())
    }
    var pendingNotificationActivityServerId by pendingNotificationActivityServerIdState
    // An App permission update push (`{ type: "app" }`, slock #16546): switch to its server and
    // open the installed App's detail in the authenticated Apps WebView.
    val pendingNotificationAppTargetState = remember(pager.pageData.params.toString()) {
        mutableStateOf(pager.pageData.params.appShellNotificationAppTarget())
    }
    var pendingNotificationAppTarget by pendingNotificationAppTargetState
    // #proj-mobile #25/#26: external message links wait in the process inbox (through sign-in)
    // and, once resolved for this account, enter the same window navigation queue as a
    // notification tap ([navigationIntake]).
    val messageLinkSwitchedServerTemplate =
        stringResource(Res.string.message_link_switched_server, MessageLinkServerNamePlaceholder)
    AppShellWindowNavigationEventEffects(
        bridge = bridge,
        storeScope = storeScope,
        windowNavigationCoordinator = windowNavigationCoordinator,
        eventBus = eventBus,
        navigationIntake = navigationIntake,
        pendingNotificationActivityServerIdState = pendingNotificationActivityServerIdState,
        pendingNotificationAppTargetState = pendingNotificationAppTargetState,
    )
    val homeSearchSavedViewModel: SharedHomeSearchSavedViewModel =
        viewModel(key = "shared-home-search-saved") {
            SharedHomeSearchSavedViewModel(
                authStore = shellAuthStore,
                savedRepository = savedRepository,
                searchRepository = searchRepository,
                memberRepository = memberRepository,
                serverMembersStore = appShellServerMembersStore,
                savedStateCache = sharedStores.homeSavedStateCache,
                readReceiptCoordinator = sharedStores.processReadReceiptCoordinator(),
                readReceiptOwnerFactory = sharedStores::readReceiptOwner,
                timeRangeBoundsProvider = { range -> currentHostThreadTimeContextProvider.value.current.toSearchIsoBounds(range) },
                diagnosticsLogger = raftLogger("SlockHomeSearchSaved", "shared_home_search_saved")
            )
        }
    val homeShellStore =
        remember(
            homeStore,
            homeActivityStore,
            homeSearchSavedViewModel,
            appShellCoroutineScope
        ) {
            SharedHomeShellStore(
                homeState = homeStore.state,
                activityState = homeActivityStore.state,
                searchSavedState = homeSearchSavedViewModel.state,
                dispatchHome = homeStore::dispatch,
                dispatchHomeVisibleRefresh = homeStore::dispatchVisibleForegroundRefresh,
                // Live realtime applies run under the realtime authority registry lock: apply the
                // patch and return. A Home/Activity load the event starts (or one already in flight)
                // keeps running in its store scope; waiting for it here held every later realtime
                // event behind Home bootstraps (feedback bc6e392e: up to 17 s).
                acceptHome = homeStore::accept,
                dispatchActivity = homeActivityStore::dispatch,
                acceptActivityRealtime = homeActivityStore::applyLiveRealtime,
                dispatchSearchSaved = homeSearchSavedViewModel::dispatch,
                refreshActivitySummary = { serverId, reason ->
                    serverId
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { sharedStores.activityUnreadSummaryStore.refresh(ServerId(it), reason) }
                },
                resetActivity = homeActivityStore::reset,
                diagnosticsLogger = raftLogger("SlockHomeShell", "shared_home_shell"),
                scope = appShellCoroutineScope,
                selectedConversationSeen = { raftHostShowsContent(storeScope.shellInstanceId) }
            )
        }
    AppShellHomeShellStoreEffects(
        selectedTabState = selectedTabState,
        homeShellStore = homeShellStore,
    )
    val homeShellRenderState =
        remember(homeShellStore, serverDrawerEnabled) {
            if (serverDrawerEnabled) {
                homeShellStore.state
                    .map { state -> state.withoutServerDrawerRuntimeForAppShellRender() }
                    .distinctUntilChanged()
            } else {
                homeShellStore.state
            }
        }
    val initialHomeShellRenderState =
        remember(homeShellStore, serverDrawerEnabled) {
            if (serverDrawerEnabled) {
                homeShellStore.state.value.withoutServerDrawerRuntimeForAppShellRender()
            } else {
                homeShellStore.state.value
            }
        }
    val homeShellState by homeShellRenderState.collectAsStateWhileStarted(initialHomeShellRenderState)
    val homeState = homeShellState.home
    val homeActivityState = homeShellState.activity
    val homeSearchSavedState = homeShellState.searchSaved
    val selectedHomeDestination = homeShellState.destination
    val selectedHomeConversationId = homeShellState.selectedConversationId
    val tasksFirstRenderLoggedKeyState = remember { mutableStateOf<String?>(null) }
    var tasksFirstRenderLoggedKey by tasksFirstRenderLoggedKeyState
    var consumedDiagnosticsDeepLinkId by remember { mutableStateOf<String?>(null) }
    val readyGate = authGateState as? SharedAuthGateState.Ready
    val readySession = readyGate?.session
    val homePreviewSession =
        readySession
            ?: cachedRestoreSession.takeIf { authGateState == SharedAuthGateState.Restoring }
    val readyAuthPhase =
        readyGate?.phase
            ?: cachedRestoreSession
                ?.takeIf { authGateState == SharedAuthGateState.Restoring }
                ?.let { SharedAuthReadyPhase.CachedRestorePending }
    val readyServerId = selectedServerId?.trim()?.takeIf(String::isNotBlank) ?: readySession?.currentServerId
    val serverLastConversationRepository = ServerLastConversationRepository.App
    val serverLastConversationOwnerKey =
        messageBodyFontSizeOwnerKey(sharedStores.apiEnvironmentStore.current().apiBaseUrl, readySession?.user?.id.orEmpty())
    val tasksOwner = SharedTasksRouteOwner(
        environment = sharedStores.apiEnvironmentStore.current().apiBaseUrl,
        accountId = readySession?.user?.id,
        serverId = readyServerId
    )
    val tasksDataSource =
        remember(appShellNetworkClient, tasksOwner.environment, tasksOwner.accountId) {
            SharedTasksDataSource(
                api = NetworkRaftTasksApi(appShellNetworkClient),
                diagnosticsLogger = raftLogger("SlockTasks", "shared_tasks")
            )
        }
    val tasksBinding = rememberSharedTasksRoute(
        owner = tasksOwner,
        authStore = shellAuthStore,
        dataSource = tasksDataSource,
        sharedTasksDataStore = sharedStores.tasksDataStore
    )
    val tasksRoute = tasksBinding.route
    val tasksViewModel = tasksRoute.viewModel
    val tasksState = tasksBinding.state
    // Slack-aligned navigation was retired; persisted/flagged Slack tabs are ignored. The live
    // child-page set is Home, Activity, Tasks, More plus the More pages Members and Settings.
    val slackNavTabKeys = AppShellChildPageTabKeys
    val slackNavMode = false
    // Clamp a deep link / stale selection onto the active ordered key set: with the gate off
    // the Slack keys are not child pages, and with the gate on the legacy hidden keys (tasks/
    // members) are not first-class tabs anymore. Home is the safe anchor in both cases.
    // Mirrors selectAppShellTab but avoids the local-function ordering constraint.
    AppShellTabAndDesktopModeEffects(
        desktopModeOnLargeScreenState = desktopModeOnLargeScreenState,
        selectedTabState = selectedTabState,
        childTabSwitchEpochState = childTabSwitchEpochState,
        appShellTabHistoryState = appShellTabHistoryState,
        retainedAppShellTabsState = retainedAppShellTabsState,
        railInlineSecondaryDestinationState = railInlineSecondaryDestinationState,
        railActivityReachedState = railActivityReachedState,
        bridge = bridge,
        shellScope = shellScope,
        slackNavTabKeys = slackNavTabKeys,
    )
    val homePreviewServerId =
        selectedServerId?.trim()?.takeIf(String::isNotBlank) ?: homePreviewSession?.currentServerId
    val createChannelAgentStatusResolver =
        remember(appShellServerMembersStore, readyServerId) {
            raftScopedAgentStatusResolver(appShellServerMembersStore, readyServerId)
        }
    val appShellEnvironment = sharedStores.apiEnvironmentStore.current()
    val serverSetupFlagRevision by sharedStores.serverFeatureFlags.snapshotRevision.collectAsState()
    val serverSetupGateServerId = readyServerId?.takeIf(String::isNotBlank) ?: homePreviewServerId
    val serverSetupGateOn =
        remember(serverSetupFlagRevision, serverSetupGateServerId) {
            serverSetupGateEnabled(sharedStores.serverFeatureFlags, serverSetupGateServerId.orEmpty())
        }
    val setupAdmissionHost =
        if (readySession != null && !readyServerId.isNullOrBlank()) {
            rememberServerSetupAdmissionHost(
                session = requireNotNull(readySession),
                serverId = readyServerId,
                environmentId =
                    appShellEnvironment.let { environment ->
                        raftNetworkKmmRecoveryEnvironmentId(
                            environmentKey = environment.key,
                            apiBaseUrl = environment.apiBaseUrl,
                        )
                    },
                webBaseUrl = appShellEnvironment.webUrl,
                networkClient = appShellNetworkClient,
                shellAuthStore = shellAuthStore,
                authEvents = sharedStores.authStore,
                authDataSource = authDataSource,
                bridge = bridge,
                openExternalUrl = linkNavigator::open,
                refreshSignal = pageVisibleTick,
                gateEnabled = serverSetupGateOn,
            )
        } else {
            null
        }
    val setupAdmissionAllowsHome = setupAdmissionHost?.allowsHome == true
    val authoritativeHomeStartAllowed =
        appShellAuthoritativeHomeStartAllowed(
            authGateState = authGateState,
            setupAdmissionAllowsHome = setupAdmissionAllowsHome
        )
    AppShellHomePreviewEffect(
        bridge = bridge,
        homeStore = homeStore,
        homePreviewSession = homePreviewSession,
        readyAuthPhase = readyAuthPhase,
        homePreviewServerId = homePreviewServerId,
    )
    val cachedHomePreview =
        appShellCanRenderCachedHomePreview(
            readySession = homePreviewSession,
            readyPhase = readyAuthPhase,
            setupAdmissionState = setupAdmissionHost?.state,
            serverSetupGateEnabled = serverSetupGateOn,
            readyServerId = homePreviewServerId,
            selectedTab = selectedTab,
            selectedHomeDestination = selectedHomeDestination,
            pageVisible = pageVisible,
            state = homeState
        )
    // artin 2026-10-10 (Hands 79db9fe6): once the identity-bound cached Home can be shown, the
    // user may leave Home (open a channel, switch tab) while the session and server setup
    // admission are still being confirmed in the background; the admission screen only takes over
    // again if admission ends Blocked/Failed or the cache no longer matches the account.
    val cachedRestoreUsable =
        appShellCanRenderCachedHomePreview(
            readySession = homePreviewSession,
            readyPhase = readyAuthPhase,
            setupAdmissionState = setupAdmissionHost?.state,
            serverSetupGateEnabled = serverSetupGateOn,
            readyServerId = homePreviewServerId,
            state = homeState
        )
    val adaptiveRoomLeaseRegistry =
        remember(sharedStores, storeScope) { sharedStores.realtimeRoomLeaseRegistry(storeScope) }
    fun rejectPendingAdaptiveDetailOpens(
        dropped: Map<String, build.raft.app.data.PendingAdaptiveDetailOpen>,
        reason: String,
    ) {
        val anchorStore = sharedStores.adaptiveScrollAnchorStore(storeScope)
        dropped.forEach { (requestId, pending) ->
            anchorStore.discard(
                shellInstanceId = storeScope.shellInstanceId,
                identity = pending.identity,
                requestId = requestId,
            )
            sharedStores.eventBus.post(
                eventName = RAFT_ADAPTIVE_DETAIL_OPEN_ACK_EVENT,
                eventData =
                    RaftAdaptiveDetailOpenAck(
                        shellInstanceId = storeScope.shellInstanceId,
                        requestId = requestId,
                        accepted = false,
                        reason = reason,
                    ).toJson(),
                crossProcess = true,
                source = "adaptive_detail_open_dropped",
            )
        }
    }

    fun releaseAdaptiveHandoffLease(expectedGeneration: Long? = null) {
        val owner = adaptiveWorkspaceStore.snapshot().handoffRoomLeaseOwner ?: return
        if (expectedGeneration != null && owner.generation != expectedGeneration) return
        realtimeEvents.applyRoomTransitions(adaptiveRoomLeaseRegistry.release(owner))
        adaptiveWorkspaceStore.update { current ->
            if (current.handoffRoomLeaseOwner == owner) {
                current.copy(handoffRoomLeaseOwner = null)
            } else {
                current
            }
        }
    }
    fun installAdaptiveHandoffLease(
        selection: RaftAdaptiveDetailSelection,
        launchPayload: WireThreadLaunchPayload,
    ) {
        val nextOwner =
            RealtimeRoomLeaseOwner(
                shellInstanceId = storeScope.shellInstanceId,
                serverId = selection.identity.serverId,
                consumerId = "adaptive-handoff",
                generation = selection.generation,
            )
        val initialPayload = launchPayload.toInitialPayloadOrNull(launchPayload.instanceId)
        val roomIds = initialPayload?.activeRealtimeRoomIds().orEmpty()
        // Task #14c: a reply thread's own room stays joined after the detail closes (its parent's does not).
        initialPayload?.replyThreadRealtimeRoomIds()?.forEach { roomId ->
            adaptiveRoomLeaseRegistry.keepJoinedUntilClear(nextOwner.serverId, roomId)
        }
        realtimeEvents.applyRoomTransitions(adaptiveRoomLeaseRegistry.acquire(nextOwner, roomIds))
        val previousOwner = adaptiveWorkspaceStore.snapshot().handoffRoomLeaseOwner
        adaptiveWorkspaceStore.update { current ->
            current.copy(handoffRoomLeaseOwner = nextOwner)
        }
        if (previousOwner != null && previousOwner != nextOwner) {
            realtimeEvents.applyRoomTransitions(adaptiveRoomLeaseRegistry.release(previousOwner))
        }
    }
    // task #36: when the row (or desktop mode) can no longer hold the side column, fold the side
    // thread into the main detail with the channel in Back history. #189 rule 3 decides it from the
    // row plan (sideThreadColumnAvailable), not from fixed window and detail widths. The fold is a
    // page swap and one-way: once the thread is the main detail the channel's Tasks / Files leave the
    // row, so the plan may then say "no fold" again; nothing unfolds on that (the way back out is #189
    // step 3's). Not yet: the web's forceFold (a touch tablet in portrait under 1280 always folds).
    // task #37: the user's list column width (saved per device) and whether its divider is being
    // dragged. A drag that squeezes the detail below the column threshold folds the side thread
    // only when the finger lifts, so dragging across the threshold and back loses nothing.
    val listPanePreferenceStore = remember { KeyValueChatLayoutPreferenceStore(raftSharedPreferenceStore()) }
    val adaptiveListPaneWidths = rememberRaftListPaneWidths(listPanePreferenceStore)
    // The rail's Collapse / Expand Sidebar: each list column's hidden state, saved per device.
    val sidebarCollapse = rememberRaftSidebarCollapseState(listPanePreferenceStore)
    var adaptiveListPaneResizing by remember { mutableStateOf(false) }
    // artin 2026-10-29: the section the wide Settings detail shows (null: the default, Account).
    var settingsWidePicked by remember { mutableStateOf<String?>(null) }
    // The setting a Settings search result opened the picked section at (its row and the request).
    var settingsWideAnchor by remember { mutableStateOf<Pair<String, SettingsSearchAnchorRequest>?>(null) }
    var settingsSearchOpenNonce by remember { mutableStateOf(0L) }
    // A drag only defers the fold while it can still be in progress: desktop mode on, dual pane.
    // (The handle also reports the end when it goes inactive or leaves composition.)
    val listPaneResizeDefersFold =
        adaptiveListPaneResizing && desktopModeOnLargeScreen &&
            adaptivePane.layout == RaftAdaptivePaneLayout.DualPane
    AppShellAdaptiveColumnEffects(
        adaptivePane = adaptivePane,
        desktopModeOnLargeScreenState = desktopModeOnLargeScreenState,
        selectedTabState = selectedTabState,
        bridge = bridge,
        adaptiveWorkspaceStore = adaptiveWorkspaceStore,
        adaptiveWorkspaceSessionState = adaptiveWorkspaceSessionState,
        adaptiveWorkspaceState = adaptiveWorkspaceState,
        tasksDetailColumnActiveState = tasksDetailColumnActiveState,
        tasksDetailColumnExpandedState = tasksDetailColumnExpandedState,
        listPaneResizeDefersFold = listPaneResizeDefersFold,
    )
    val tasksDetailColumnOpen =
        appShellTasksDetailColumnOpen(
            desktopModeOnLargeScreen = desktopModeOnLargeScreen,
            dualPane = adaptivePane.layout == RaftAdaptivePaneLayout.DualPane,
            selectedTab = selectedTab,
            selectionPresent = adaptiveWorkspaceState?.selection != null,
            columnActive = tasksDetailColumnActive,
        )
    val adaptiveParentVisible =
        appShellSharedDetailSurfaceVisible(
            pageVisible = pageVisible,
            selectedTab = selectedTab,
            selectedHomeDestination = selectedHomeDestination,
            railInlineSecondaryDestination = railInlineSecondaryDestination,
            tasksDetailColumnOpen = tasksDetailColumnOpen,
        )
    LaunchedEffect(
        desktopModeOnLargeScreen,
        storeScope.shellInstanceId,
        readyServerId,
        adaptivePane,
        adaptiveParentVisible,
        authoritativeHomeStartAllowed,
    ) {
        if (!authoritativeHomeStartAllowed) {
            releaseAdaptiveHandoffLease()
            adaptiveWorkspaceStore.update { current ->
                if (current.defaultLandingServerId == null) current
                else current.copy(defaultLandingServerId = null)
            }
            return@LaunchedEffect
        }
        val serverId = readyServerId?.trim()?.takeIf(String::isNotBlank)
        if (!desktopModeOnLargeScreen && serverId != null) {
            // task #45: toggling the user setting OFF must not destroy the workspace —
            // destination, selection and drafts must survive a live OFF->ON round trip.
            // The workspace keeps its last state; rendering gates on the flag, and the
            // next ON tick re-drives WindowChanged/ParentVisibilityChanged from current
            // values. (Server loss below still clears it.)
            return@LaunchedEffect
        }
        if (serverId == null) {
            val droppedOpens = adaptiveWorkspaceStore.snapshot().pendingDetailOpens
            adaptiveWorkspaceStore.update { current ->
                current.copy(
                    workspace =
                        current.workspace?.let { state ->
                            reduceRaftAdaptiveWorkspace(state, RaftAdaptiveWorkspaceIntent.TerminalReset)
                        },
                    launchPayload = null,
                    pendingDetailOpens = emptyMap(),
                    defaultLandingServerId = null,
                )
            }
            rejectPendingAdaptiveDetailOpens(droppedOpens, "workspace_reset")
            releaseAdaptiveHandoffLease()
            return@LaunchedEffect
        }
        // Reduce window/visibility on the store's CURRENT workspace inside the atomic
        // update — seeding from the compose snapshot (`adaptiveWorkspaceState`) here is a
        // lost-update race: a concurrently-written selection (e.g. a rail Search/Activity
        // open landing between snapshot read and update) would be silently overwritten.
        // Capture the dropped pending opens BEFORE the update so the reject/nack path
        // can still see them (they are cleared inside the update for the authority change).
        val droppedOpens = adaptiveWorkspaceStore.snapshot().pendingDetailOpens
        var authorityChanged = false
        var defaultLandingDropped = false
        adaptiveWorkspaceStore.update { current ->
            var next =
                current.workspace
                    ?: RaftAdaptiveWorkspaceState(
                        shellInstanceId = storeScope.shellInstanceId,
                        serverId = serverId,
                    )
            val changed = next.serverId != serverId
            authorityChanged = changed
            if (changed) {
                next =
                    reduceRaftAdaptiveWorkspace(
                        next,
                        RaftAdaptiveWorkspaceIntent.AuthorityChanged(serverId),
                    )
            }
            val beforeWindow = next
            next =
                reduceRaftAdaptiveWorkspace(
                    next,
                    RaftAdaptiveWorkspaceIntent.WindowChanged(adaptivePane),
                )
            // Uses the same [raftAdaptiveDefaultLandingDropped] predicate as
            // [SharedAdaptiveWorkspaceStore.update] (the canonical normalizer for launchPayload and
            // defaultLandingServerId) so handoff-lease release stays in lockstep with store resets.
            val dropped = raftAdaptiveDefaultLandingDropped(beforeWindow, next)
            defaultLandingDropped = dropped
            next =
                reduceRaftAdaptiveWorkspace(
                    next,
                    RaftAdaptiveWorkspaceIntent.ParentVisibilityChanged(adaptiveParentVisible),
                )
            current.copy(
                workspace = next,
                launchPayload = if (changed || dropped) null else current.launchPayload,
                pendingDetailOpens =
                    if (changed) emptyMap() else current.pendingDetailOpens,
                defaultLandingServerId =
                    if (changed || dropped) null else current.defaultLandingServerId,
            )
        }
        if (authorityChanged) {
            rejectPendingAdaptiveDetailOpens(droppedOpens, "authority_changed")
            releaseAdaptiveHandoffLease()
        } else if (defaultLandingDropped) {
            releaseAdaptiveHandoffLease()
        }
    }
    fun postShellAdaptiveTrace(
        hop: String,
        identityKey: String,
        generation: Long,
        requestId: String? = null,
        extra: JSONObject.() -> Unit = {},
    ) {
        bridge.log(
            formatAdaptiveTraceLine(
                side = "shell",
                hop = hop,
                identityKey = identityKey,
                generation = generation,
                requestId = requestId,
            ),
        )
        sharedStores.eventBus.postAdaptiveTrace(
            side = "shell",
            hop = hop,
            identityKey = identityKey,
            generation = generation,
            requestId = requestId,
            extra = extra,
        )
    }
    DisposableEffect(sharedStores, storeScope.shellInstanceId) {
        val openSubscription =
            sharedStores.eventBus.observe(
                eventName = RAFT_ADAPTIVE_DETAIL_OPEN_EVENT,
                crossProcess = true,
            ) { eventData ->
                val request = eventData?.toRaftAdaptiveDetailOpenRequestOrNull()
                    ?: return@observe
                if (request.shellInstanceId != storeScope.shellInstanceId) return@observe
                postShellAdaptiveTrace(
                    hop = "open_received",
                    identityKey = request.identity.key,
                    generation = 0L,
                    requestId = request.requestId,
                )
                val session = adaptiveWorkspaceStore.snapshot()
                val (nextSession, decision) =
                    evaluateRaftAdaptiveDetailOpen(
                        request = request,
                        workspaceEnabled = desktopModeOnLargeScreen,
                        session = session,
                        // Hard off until the native route-token-verified commit action lands
                        // (task #18, Jony follow-up); keeps the merged protocol inert.
                        nativeGuardedCommitAvailable = false,
                    )
                if (nextSession != session) {
                    adaptiveWorkspaceStore.update { nextSession }
                }
                when (decision) {
                    is RaftAdaptiveDetailOpenDecision.Rejected -> {
                        postShellAdaptiveTrace(
                            hop = "open_rejected_ack",
                            identityKey = request.identity.key,
                            generation = 0L,
                            requestId = request.requestId,
                        ) { put("reason", decision.reason) }
                        sharedStores.eventBus.post(
                            eventName = RAFT_ADAPTIVE_DETAIL_OPEN_ACK_EVENT,
                            eventData =
                                RaftAdaptiveDetailOpenAck(
                                    shellInstanceId = storeScope.shellInstanceId,
                                    requestId = request.requestId,
                                    accepted = false,
                                    reason = decision.reason,
                                ).toJson(),
                            crossProcess = true,
                            source = "adaptive_detail_open_rejected",
                        )
                        sharedStores
                            .adaptiveScrollAnchorStore(storeScope)
                            .discard(
                                shellInstanceId = storeScope.shellInstanceId,
                                identity = request.identity,
                                requestId = request.requestId,
                            )
                    }
                    is RaftAdaptiveDetailOpenDecision.Accepted -> {
                        // Ack is held until the destination pane child reports READY (mount +
                        // draft/viewport restore), not merely received. See the
                        // RAFT_ADAPTIVE_DETAIL_READY_EVENT subscription below.
                        postShellAdaptiveTrace(
                            hop = "open_accepted",
                            identityKey = request.identity.key,
                            generation = decision.generation,
                            requestId = request.requestId,
                        )
                    }
                }
            }
        val readySubscription =
            sharedStores.eventBus.observe(
                eventName = RAFT_ADAPTIVE_DETAIL_READY_EVENT,
                crossProcess = true,
            ) { eventData ->
                val shellId = eventData?.optString(RAFT_ADAPTIVE_DETAIL_SHELL_ID)?.trim()
                    ?: return@observe
                if (shellId != storeScope.shellInstanceId) return@observe
                val identityKey = eventData.optString(RAFT_ADAPTIVE_DETAIL_IDENTITY_KEY).trim()
                val generation =
                    eventData.optDouble(RAFT_ADAPTIVE_DETAIL_GENERATION, 0.0).toLong()
                if (identityKey.isBlank() || generation <= 0L) return@observe
                postShellAdaptiveTrace(
                    hop = "ready_received",
                    identityKey = identityKey,
                    generation = generation,
                )
                val session = adaptiveWorkspaceStore.snapshot()
                val (nextSession, pending) =
                    completeRaftAdaptiveDetailOpen(
                        identityKey = identityKey,
                        generation = generation,
                        session = session,
                    )
                if (pending == null) {
                    postShellAdaptiveTrace(
                        hop = "ready_no_pending",
                        identityKey = identityKey,
                        generation = generation,
                    )
                    return@observe
                }
                adaptiveWorkspaceStore.update { nextSession }
                // READY means the destination restored and mounted; ack authorizes the source to
                // attempt the native commit. The staged anchor is NOT consumed here — it is
                // consumed only on the native commit receipt (see the commit-result subscription
                // below), so a rejected or failed commit keeps the recovery record.
                if (pending.acknowledged) return@observe
                postShellAdaptiveTrace(
                    hop = "ack_sent",
                    identityKey = pending.identity.key,
                    generation = generation,
                    requestId = pending.requestId,
                )
                sharedStores.eventBus.post(
                    eventName = RAFT_ADAPTIVE_DETAIL_OPEN_ACK_EVENT,
                    eventData =
                        RaftAdaptiveDetailOpenAck(
                            shellInstanceId = storeScope.shellInstanceId,
                            requestId = pending.requestId,
                            accepted = true,
                            generation = generation,
                        ).toJson(),
                    crossProcess = true,
                    source = "adaptive_detail_open_ready",
                )
            }
        // Source-side cancel notice (task #18 hardening): drops exactly the named pending request
        // and its staged anchor so a READY arriving after the source gave up resolves to
        // ready_no_pending instead of a stray accepted ACK. The drop applies to acknowledged
        // pendings too: a cancel event only exists when the source never dispatched commit
        // (dispatchAdaptiveCommit sets commitDispatched before any later cancel can run), so no
        // commit receipt can ever arrive to settle this entry — retaining it would leak the
        // pending while the transaction is already dead on the source side.
        val cancelSubscription =
            sharedStores.eventBus.observe(
                eventName = RAFT_ADAPTIVE_DETAIL_CANCEL_EVENT,
                crossProcess = true,
            ) { eventData ->
                val cancel = eventData?.toRaftAdaptiveDetailCancelOrNull()
                    ?: return@observe
                if (cancel.shellInstanceId != storeScope.shellInstanceId) return@observe
                val session = adaptiveWorkspaceStore.snapshot()
                val pending = session.pendingDetailOpens[cancel.requestId] ?: return@observe
                adaptiveWorkspaceStore.update { current ->
                    dropRaftAdaptiveDetailOpen(cancel.requestId, current)
                }
                sharedStores
                    .adaptiveScrollAnchorStore(storeScope)
                    .discard(
                        shellInstanceId = storeScope.shellInstanceId,
                        identity = pending.identity,
                        requestId = cancel.requestId,
                    )
                postShellAdaptiveTrace(
                    hop = "open_cancelled",
                    identityKey = pending.identity.key,
                    generation = cancel.generation,
                    requestId = cancel.requestId,
                ) { put("reason", cancel.reason) }
            }
        // The source reports its prepared controller identities after native prepare; bind them
        // to the pending request so a stray commit receipt cannot settle it.
        val preparedSubscription =
            sharedStores.eventBus.observe(
                eventName = RAFT_ADAPTIVE_DETAIL_PREPARED_EVENT,
                crossProcess = true,
            ) { eventData ->
                val prepared = eventData?.toRaftAdaptiveDetailPreparedOrNull()
                    ?: return@observe
                if (prepared.shellInstanceId != storeScope.shellInstanceId) return@observe
                adaptiveWorkspaceStore.update { current ->
                    markRaftAdaptiveDetailOpenPrepared(
                        requestId = prepared.requestId,
                        sourceControllerToken = prepared.sourceControllerToken,
                        targetControllerToken = prepared.targetControllerToken,
                        session = current,
                    )
                }
            }
        // The authoritative end of an adoption is the native commit receipt, not the ack.
        // commit=true → consume the staged anchor exactly once under the request fence and
        // settle the pending entry; failure → discard the anchor, drop pending, and clear the
        // staged detail selection so the destination does not stay mounted while the source
        // page is still alive.
        val commitSubscription =
            sharedStores.eventBus.observe(
                eventName = RAFT_ADAPTIVE_NATIVE_COMMIT_RESULT_EVENT,
                crossProcess = true,
            ) { eventData ->
                val result = eventData?.toRaftAdaptiveNativeCommitResultOrNull()
                    ?: return@observe
                val session = adaptiveWorkspaceStore.snapshot()
                val (nextSession, pending) =
                    settleRaftAdaptiveDetailOpen(
                        result = result,
                        session = session,
                    )
                if (pending == null) return@observe
                val anchorStore = sharedStores.adaptiveScrollAnchorStore(storeScope)
                if (result.committed) {
                    adaptiveWorkspaceStore.update { nextSession }
                    anchorStore.consume(
                        shellInstanceId = storeScope.shellInstanceId,
                        identity = pending.identity,
                        requestId = pending.requestId,
                    )
                } else {
                    // Keep the pending entry and the staged anchor: a rejected/timed-out commit
                    // may mean the native pop already happened but the stack readback failed —
                    // discarding the recovery record here would lose the viewport anchor while
                    // both pages are gone (source popped, destination still mounted). Retention
                    // is what keeps an explicit retry or user navigation able to recover; the
                    // TerminalReset/AuthorityChanged paths still drop+nack as before.
                }
            }
        val closeSubscription =
            sharedStores.eventBus.observe(
                eventName = RAFT_ADAPTIVE_DETAIL_CLOSE_EVENT,
                crossProcess = true,
            ) { eventData ->
                val request = eventData?.toRaftAdaptiveDetailCloseRequestOrNull()
                    ?: return@observe
                if (request.shellInstanceId != storeScope.shellInstanceId) return@observe
                val before = adaptiveWorkspaceStore.snapshot()
                val after =
                    // task #36: a side-column page's close only closes the column.
                    adaptiveWorkspaceStore.update { current -> current.closeFromDetailPage(request) }
                if (before.workspace?.selection != after.workspace?.selection) {
                    releaseAdaptiveHandoffLease(request.generation)
                    homeShellStore.dispatch(
                        SharedHomeShellIntent.SetSelectedConversation(after.detailListSelectionId())
                    )
                }
            }
        onDispose {
            openSubscription.close()
            readySubscription.close()
            cancelSubscription.close()
            preparedSubscription.close()
            commitSubscription.close()
            closeSubscription.close()
        }
    }
    val tasksServerMembersEntry by rememberAppShellTasksServerMembersEntry(appShellServerMembersStore, readyServerId)
    // QA bootstrap READY + waiting diagnostics + bounded Home retry (no-op unless session.qaBootstrap is set).
    AppShellSignInEffects(
        pageVisible = pageVisible,
        bridge = bridge,
        storeScope = storeScope,
        authDataSource = authDataSource,
        authGateStateState = authGateStateState,
        settingsCapabilities = settingsCapabilities,
        homeShellStore = homeShellStore,
        homeState = homeState,
        readyServerId = readyServerId,
    )
    val activityUnreadServerId = remember(readyServerId) { ServerId(readyServerId.orEmpty()) }
    val activityUnreadSummaryFlow =
        remember(sharedStores.activityUnreadSummaryStore, activityUnreadServerId) {
            sharedStores.activityUnreadSummaryStore.projectionFlow(activityUnreadServerId)
        }
    val activityUnreadSummary by activityUnreadSummaryFlow.collectAsStateWhileStarted(
        sharedStores.activityUnreadSummaryStore.currentProjection(activityUnreadServerId)
    )
    val serverActivityExpectedOwner =
        readySession?.user?.id
            ?.takeIf { it.isNotBlank() }
            ?.let { accountId ->
                readyServerId?.takeIf { it.isNotBlank() }?.let { serverId ->
                    sharedStores.readReceiptOwner(serverId, accountId)
                }
            }
    AppShellNotificationBadgeEffects(
        readySession = readySession,
        readyServerId = readyServerId,
        homeShellStore = homeShellStore,
        homeState = homeState,
        activityUnreadSummaryFlow = activityUnreadSummaryFlow,
        authoritativeHomeStartAllowed = authoritativeHomeStartAllowed,
        serverActivityExpectedOwner = serverActivityExpectedOwner,
        bridge = bridge,
        sharedStores = sharedStores,
    )
    val renderedHomeServerId =
        (homeState.content as? SharedHomeContentState.Content)?.snapshot?.server?.id?.value
    val navigationStoreScope =
        remember(
            storeScope.shellInstanceId,
            storeScope.initialServerId,
            selectedServerId,
            renderedHomeServerId
        ) {
            RaftShellStoreScope.create(
                shellInstanceId = storeScope.shellInstanceId,
                initialServerId =
                    appShellNavigationServerId(
                        selectedServerId = selectedServerId,
                        renderedHomeServerId = renderedHomeServerId,
                        scopeInitialServerId = storeScope.initialServerId
                    )
            )
        }
    // The native shell can dispatch Back directly to popMainRoute, bypassing
    // a child Kuikly page's BackHandler. Keep the same selection in both hosts.
    val membersDetailSelection = remember(navigationStoreScope, readySession?.user?.id, readyServerId) {
        mutableStateOf<RaftMemberDetailPayload?>(null)
    }
    // Wide Activity right-pane Artifact viewer state (kept beside membersDetailSelection so
    // popMainRoute can dismiss an open Activity Artifact before leaving the tab).
    val activityArtifactState = remember { ActivityWideArtifactState() }
    val latestReadySessionState = rememberUpdatedState(readySession)
    val latestReadySession by latestReadySessionState
    val latestAuthoritativeHomeStartAllowedState = rememberUpdatedState(authoritativeHomeStartAllowed)
    val latestAuthoritativeHomeStartAllowed by latestAuthoritativeHomeStartAllowedState
    val latestCachedRestoreUsable by rememberUpdatedState(cachedRestoreUsable)
    AppShellHomeServerSwitchEffect(
        pendingHomeServerSwitchIdState = pendingHomeServerSwitchIdState,
        observedHomeServerSwitchIdState = observedHomeServerSwitchIdState,
        bridge = bridge,
        homeState = homeState,
        renderedHomeServerId = renderedHomeServerId,
    )
    val announcementOwner = listOf(
        sharedStores.apiEnvironmentStore.current().apiBaseUrl,
        readySession?.user?.id,
        readyServerId,
        processRealtimeAuthority?.witness?.authGeneration?.toString(),
        authoritativeHomeStartAllowed.toString(),
    )
    val latestAnnouncementOwner by rememberUpdatedState(announcementOwner)
    val announcementStore = remember(announcementsClient, announcementOwner) {
        build.raft.app.data.SharedAnnouncementStore(announcementsClient) {
            announcementOwner == latestAnnouncementOwner &&
                readySession != null && authoritativeHomeStartAllowed
        }
    }
    var announcementModalShown by remember { mutableStateOf(false) }
    val sendPromptHostState = remember { AppShellSendPromptHostState() }
    val latestAnnouncementStore by rememberUpdatedState(announcementStore)
    AppShellAnnouncementEffects(
        announcementStore = announcementStore,
    )
    val latestSelectedTabState = rememberUpdatedState(selectedTab)
    val latestSelectedTab by latestSelectedTabState
    val latestSelectedHomeDestinationState = rememberUpdatedState(selectedHomeDestination)
    val latestSelectedHomeDestination by latestSelectedHomeDestinationState
    val latestVisibleAppearanceGeneration by rememberUpdatedState(visibleAppearanceGeneration)
    val latestPageVisible by rememberUpdatedState(pageVisible)
    val homeConversationDirectoryFlow =
        remember(homeStore, readyServerId) {
            val serverId = readyServerId?.takeIf { it.isNotBlank() }
            if (serverId == null) {
                MutableStateFlow(SharedConversationDirectoryState())
            } else {
                homeStore.observeServer(ServerId(serverId))
            }
        }
    val homeConversationDirectory by homeConversationDirectoryFlow.collectAsStateWhileStarted(SharedConversationDirectoryState())
    val appShellConversationDirectory =
        remember(homeConversationDirectory, homeActivityState.snapshot, homeActivityState.dmPeerNames) {
            val activityDirectory =
                homeActivityState.snapshot?.toSharedConversationDirectoryState(
                    dmPeerNames = homeActivityState.dmPeerNames
                )
                    ?: SharedConversationDirectoryState()
            listOf(homeConversationDirectory, activityDirectory)
                .mergedSharedConversationDirectoryState()
        }
    val latestAppShellConversationDirectoryState = rememberUpdatedState(appShellConversationDirectory)
    val latestAppShellConversationDirectory by latestAppShellConversationDirectoryState
    val latestSelectedHomeConversationIdState = rememberUpdatedState(
        appShellForegroundVisibleHomeConversationId(
            homeShellSelectedConversationId = selectedHomeConversationId,
            homeStoreSelectedConversationId = homeState.selectedConversationId
        )
    )
    val latestSelectedHomeConversationId by latestSelectedHomeConversationIdState
    AppShellForegroundBannerEffects(
        pager = pager,
        suppressRestoreLanding = suppressRestoreLanding,
        selectedTabState = selectedTabState,
        childTabShellId = childTabShellId,
        bridge = bridge,
        appShellServerMembersStore = appShellServerMembersStore,
        authGateStateState = authGateStateState,
        foregroundMessageBannersEnabledState = foregroundMessageBannersEnabledState,
        readyServerId = readyServerId,
        authoritativeHomeStartAllowed = authoritativeHomeStartAllowed,
    )
    val homeSurfaceVisible =
        pageVisible &&
            selectedTab == "home" &&
            selectedHomeDestination == null
    val homeContentReadyForConversationForward = homeState.content is SharedHomeContentState.Content

    // The one arming rule for the iOS Forward recognizer ([appShellConversationForwardGestureArmed]).
    fun armConversationForwardGesture() {
        val armed =
            appShellConversationForwardGestureArmed(
                preferenceEnabled = conversationForwardGestureEnabled,
                authoritativeHomeStartAllowed = authoritativeHomeStartAllowed,
                homeSurfaceVisible = homeSurfaceVisible,
                homeContentReady = homeContentReadyForConversationForward,
                canForward = conversationForwardHistory.canForward,
            )
        bridge.setConversationForwardGestureEnabled(armed)
        if (conversationForwardGestureEnabled && homeSurfaceVisible) {
            bridge.log(
                "conversation_forward_arm armed=$armed authoritative=$authoritativeHomeStartAllowed " +
                    "homeReady=$homeContentReadyForConversationForward available=${conversationForwardHistory.canForward}"
            )
        }
    }
    AppShellHomeStartEffects(
        pageVisible = pageVisible,
        desktopModeOnLargeScreenState = desktopModeOnLargeScreenState,
        selectedTabState = selectedTabState,
        bridge = bridge,
        adaptiveWorkspaceStore = adaptiveWorkspaceStore,
        appShellCoroutineScope = appShellCoroutineScope,
        appUpdateEvents = appUpdateEvents,
        homeShellStore = homeShellStore,
        homeState = homeState,
        selectedHomeDestination = selectedHomeDestination,
        readySession = readySession,
        readyServerId = readyServerId,
        slackNavMode = slackNavMode,
        homePreviewServerId = homePreviewServerId,
        setupAdmissionAllowsHome = setupAdmissionAllowsHome,
        authoritativeHomeStartAllowed = authoritativeHomeStartAllowed,
        cachedHomePreview = cachedHomePreview,
        latestAuthoritativeHomeStartAllowedState = latestAuthoritativeHomeStartAllowedState,
        homeSurfaceVisible = homeSurfaceVisible,
    )
    val sessionTimeFormat = (readySession ?: homePreviewSession).appShellThreadTimeFormat()
    val sessionPreferredTimezone = (readySession ?: homePreviewSession).appShellPreferredTimezone()
    val effectiveSessionTimeFormat =
        RaftTimeFormatRuntime.observe(fallback = sessionTimeFormat ?: RaftTimeFormat24Hour)
    val sessionComposerSendKey = (readySession ?: homePreviewSession)?.user?.composerSendKey
    val authRequired = authGateState is SharedAuthGateState.AuthRequired
    LaunchedEffect(readySession?.user?.id, sessionTimeFormat, sessionPreferredTimezone, sessionComposerSendKey, authRequired) {
        if (readySession != null || authRequired) {
            RaftTimeFormatRuntime.update(sessionTimeFormat)
            RaftTimeZoneRuntime.update(sessionPreferredTimezone)
            RaftComposerSendKeyRuntime.setSendKey(
                if (readySession != null) RaftComposerSendKey.fromWire(sessionComposerSendKey) else RaftComposerSendKey.Default
            )
        }
    }
    // Subscribe only to low-frequency dateContext (updates on midnight, timezone/DST shift, or
    // 12h/24h format change — never on 1-minute ticks) so child tabs and task heads always format
    // dates/clocks consistently instead of leaking raw ISO strings.
    val threadTimeContext =
        hostThreadTimeContextProvider.dateContext
            ?.withTimeFormat(effectiveSessionTimeFormat)
            ?: remember(hostThreadTimeCapabilities, effectiveSessionTimeFormat) {
                hostThreadTimeCapabilities?.staticContext().withTimeFormat(effectiveSessionTimeFormat)
            }
    fun selectAppShellTab(
        nextTab: String,
        directChildSwitch: Boolean = false,
        source: AppShellTabSwitchSource = AppShellTabSwitchSource.Programmatic,
    ) {
        prewarmAdmissionGate.invalidate("tab_select:$nextTab")
        serverMenuState.dismiss()
        val retainedTab = retainedAppShellSelectedTab(nextTab)
        appShellTabHistory =
            when (source) {
                AppShellTabSwitchSource.User ->
                    appShellTabHistory.selected(
                        from = selectedTab,
                        to = retainedTab,
                        roots =
                            appShellTabHistoryRoots(
                                raftAdaptiveNavRailActive(
                                    desktopModeEnabled = desktopModeOnLargeScreen,
                                    paneState = shellScope.adaptivePane
                                )
                            ),
                    )
                AppShellTabSwitchSource.Back -> appShellTabHistory.arrivedByBack(retainedTab)
                AppShellTabSwitchSource.Programmatic -> AppShellTabHistory()
            }
        val nextEpoch = appShellNextChildTabSwitchEpoch(selectedTab, retainedTab, childTabSwitchEpoch)
        val direct = directChildSwitch && retainedTab in slackNavTabKeys
        bridge.log(
            "app_shell_select_tab next=$nextTab retained=$retainedTab from=$selectedTab " +
                "epoch=$childTabSwitchEpoch->$nextEpoch direct=$direct mode=single_child " +
                "source=${source.name} history=${appShellTabHistory.tabs.size}"
        )
        childTabSwitchEpoch = nextEpoch
        selectedTab = retainedTab
    }
    LaunchedEffect(diagnosticsDeepLinkRequest?.id, readyServerId, authoritativeHomeStartAllowed) {
        if (!authoritativeHomeStartAllowed) return@LaunchedEffect
        val request = diagnosticsDeepLinkRequest ?: return@LaunchedEffect
        if (readyServerId == null || consumedDiagnosticsDeepLinkId == request.id) {
            return@LaunchedEffect
        }
        consumedDiagnosticsDeepLinkId = request.id
        selectAppShellTab("settings")
        retainedAppShellTabs = retainedAppShellTabs + "settings"
        bridge.log(
            "diagnostics_deeplink_start id=${request.id} autoCloseMs=${request.autoCloseMs}"
        )
        val opened =
            pager.openSettingsNativeDetailPage(
                page = SettingsNativeDetailPage.Diagnostics,
                storeScope = navigationStoreScope,
                launchIntent =
                    SettingsNativeDetailLaunchIntent(
                        autoCloseMs = request.autoCloseMs,
                    ),
            )
        if (!opened) {
            bridge.logWarn("diagnostics_deeplink_rejected id=${request.id}")
        }
        bridge.log("diagnostics_deeplink_complete id=${request.id} opened=$opened")
    }
    val appShellModalHostState = rememberRaftUnifiedDialogHostState()
    val realtimeEventMapper =
        remember(sharedStores) {
            RealtimeEventMapper(
                messageShadowObserver = sharedStores.messageShadowObserver,
            )
        }
    val realtimeConnectionFamilyIdState = remember { mutableStateOf<String?>(null) }
    var realtimeConnectionFamilyId by realtimeConnectionFamilyIdState
    val pendingRealtimeAuthRefreshReasonState = remember { mutableStateOf<String?>(null) }
    var pendingRealtimeAuthRefreshReason by pendingRealtimeAuthRefreshReasonState
    val pendingRealtimeAuthRefreshPreviousFamilyIdState = remember { mutableStateOf<String?>(null) }
    var pendingRealtimeAuthRefreshPreviousFamilyId by pendingRealtimeAuthRefreshPreviousFamilyIdState
    val realtimeAuthorityConnectTickState = remember { mutableStateOf(0) }
    var realtimeAuthorityConnectTick by realtimeAuthorityConnectTickState
    // Bumped when this window comes to the foreground with workspaces in separate windows: the window
    // in front takes the process realtime connection for its server (see raftShellOwnsProcessRealtime).
    val realtimeShellForegroundTickState = remember { mutableStateOf(0) }
    var realtimeShellForegroundTick by realtimeShellForegroundTickState
    val realtimeReconciledOwnerGenerationState = remember { mutableStateOf(-1L) }
    var realtimeReconciledOwnerGeneration by realtimeReconciledOwnerGenerationState
    var membersListRefreshKey by remember { mutableStateOf(0) }
    val membersAgentDetailRefreshKeyState = remember { mutableStateOf(0) }
    var membersAgentDetailRefreshKey by membersAgentDetailRefreshKeyState
    var membersListCachedContent by remember { mutableStateOf<SharedMembersUiState.Content?>(null) }
    val foregroundBannerStore =
        remember(sharedStores, navigationStoreScope) {
            sharedStores.foregroundBannerStore(navigationStoreScope)
        }
    val foregroundMessageBanner by foregroundBannerStore.banner.collectAsStateWhileStarted()
    AppShellTaskDraftsAndBannerEffects(
        sharedStores = sharedStores,
        pager = pager,
        selectedTabState = selectedTabState,
        selectedServerStore = selectedServerStore,
        authGateStateState = authGateStateState,
        selectedHomeDestination = selectedHomeDestination,
        readySession = readySession,
        latestSelectedTabState = latestSelectedTabState,
        latestSelectedHomeDestinationState = latestSelectedHomeDestinationState,
        latestAppShellConversationDirectoryState = latestAppShellConversationDirectoryState,
        homeConversationDirectoryFlow = homeConversationDirectoryFlow,
        homeShellStore = homeShellStore,
        latestSelectedHomeConversationIdState = latestSelectedHomeConversationIdState,
        foregroundBannerStore = foregroundBannerStore,
    )
    val visibleRefreshGate = remember { AppShellVisibleRefreshGate() }

    fun refreshVisibleAppShellRoute(
        reason: String,
        signal: AppShellVisibleRefreshSignal,
        appearanceGeneration: Int,
        foregroundEpoch: Long? = null
    ): Boolean {
        if (latestReadySession == null || !latestAuthoritativeHomeStartAllowed) return false
        val refreshTarget =
            "${retainedAppShellSelectedTab(latestSelectedTab)}:${latestSelectedHomeDestination.orEmpty()}"
        if (
            !visibleRefreshGate.shouldRefresh(
                signal = signal,
                appearanceGeneration = appearanceGeneration,
                target = refreshTarget,
                foregroundEpoch = foregroundEpoch,
                serverId = latestReadySession?.currentServerId,
            )
        ) {
            bridge.log(
                "app_shell_visible_refresh_skip reason=$reason " +
                    "cause=same_appearance_or_foreground_epoch detail=${visibleRefreshGate.lastDecisionCause} " +
                    "tab=${latestSelectedTab} homeDestination=${latestSelectedHomeDestination.orEmpty()}"
            )
            return false
        }
        bridge.log(
            "app_shell_visible_refresh reason=$reason cause=${visibleRefreshGate.lastDecisionCause} " +
                "tab=${latestSelectedTab} homeDestination=${latestSelectedHomeDestination.orEmpty()}"
        )
        when (retainedAppShellSelectedTab(latestSelectedTab)) {
            "home" ->
                if (homeDestinationRefreshesOnLifecycleResume(latestSelectedHomeDestination)) {
                    when (retainedHomeSecondaryDestination(latestSelectedHomeDestination)) {
                        null, HomeDestinationActivity, HomeDestinationSaved, HomeDestinationSearch ->
                            homeShellStore.dispatch(SharedHomeShellIntent.ForegroundRefresh)
                    }
                }
            // task #94: DMs renders the same home store; Activity forwards foreground refresh
            // to ActivityStore, and More is the settings surface.
            "dms" -> homeShellStore.dispatch(SharedHomeShellIntent.ForegroundRefresh)
            "activity" -> homeShellStore.dispatch(SharedHomeShellIntent.ForegroundRefresh)
            "more" -> Unit
            "tasks" -> tasksViewModel.dispatch(SharedTasksRouteIntent.Refresh)
            "members" -> {
                membersListRefreshKey += 1
                membersAgentDetailRefreshKey += 1
            }
            "settings" -> Unit
        }
        return true
    }

    AppShellHomeFreshnessCatchUpEffect(
        serverId = readySession?.currentServerId,
        visible = pageVisible && authoritativeHomeStartAllowed && readySession != null,
        contentReady = homeState.content is SharedHomeContentState.Content,
        selectedTab = selectedTab,
        selectedHomeDestination = selectedHomeDestination,
        visibleRefreshGate = visibleRefreshGate,
        onCatchUp = { trigger ->
            if (latestReadySession == null || !latestAuthoritativeHomeStartAllowed) return@AppShellHomeFreshnessCatchUpEffect
            val tab = retainedAppShellSelectedTab(latestSelectedTab)
            val destination = latestSelectedHomeDestination
            val shouldRefreshHome =
                when (tab) {
                    "home" ->
                        homeDestinationRefreshesOnLifecycleResume(destination) &&
                            when (retainedHomeSecondaryDestination(destination)) {
                                null, HomeDestinationActivity, HomeDestinationSaved, HomeDestinationSearch -> true
                                else -> false
                            }
                    "dms", "activity" -> true
                    else -> false
                }
            if (!shouldRefreshHome) return@AppShellHomeFreshnessCatchUpEffect
            val refreshTarget = "$tab:${destination.orEmpty()}"
            visibleRefreshGate.recordFreshCheck(
                serverId = latestReadySession?.currentServerId,
                target = refreshTarget,
            )
            bridge.log(
                "app_shell_freshness_catch_up trigger=${trigger.wireName} tab=$tab homeDestination=${destination.orEmpty()}"
            )
            homeShellStore.dispatch(SharedHomeShellIntent.ForegroundRefresh)
        },
    )

    LaunchedEffect(visibleAppearanceGeneration) {
        if (
            appShellAppearanceShouldRefresh(
                appearanceGeneration = visibleAppearanceGeneration,
                pageVisibleTick = pageVisibleTick,
                selectedTab = latestSelectedTab,
                selectedHomeDestination = latestSelectedHomeDestination,
                hasRetainedHomeContent = homeState.content is SharedHomeContentState.Content
            )
        ) {
            if (latestSelectedHomeDestination != null) {
                homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
            } else {
                refreshVisibleAppShellRoute(
                    reason =
                        if (pageVisibleTick == 0) {
                            "initial_page_did_appear"
                        } else {
                            "page_did_appear"
                        },
                    signal = AppShellVisibleRefreshSignal.PageDidAppear,
                    appearanceGeneration = visibleAppearanceGeneration
                )
            }
        }
    }

    fun resetAppShellSessionState() {
        adaptiveWorkspaceStore.update { current ->
            current.copy(
                workspace =
                    current.workspace?.let { state ->
                        reduceRaftAdaptiveWorkspace(state, RaftAdaptiveWorkspaceIntent.TerminalReset)
                    },
                launchPayload = null,
                defaultLandingServerId = null,
            )
        }
        releaseAdaptiveHandoffLease()
        realtimeConnectionFamilyId = null
        pendingRealtimeAuthRefreshReason = null
        pendingRealtimeAuthRefreshPreviousFamilyId = null
        membersListRefreshKey = 0
        membersAgentDetailRefreshKey = 0
        membersListCachedContent = null
        foregroundBannerStore.clear()
        conversationForwardHistory.clear()
        bridge.setConversationForwardGestureEnabled(false)
        sharedStores.homeSavedStateCache.clear()
        selectAppShellTab("home")
        retainedAppShellTabs = setOf("home")
        prewarmedAppShellTabs = emptySet()
        homeListPositions = AppShellServerLazyListPositions()
        pendingHomeServerSwitchId = null
        observedHomeServerSwitchId = null
        tasksListPosition = AppShellLazyListPosition()
        membersListState = AppShellMembersListState()
        // Retire all in-memory Home rows on an auth/session reset. Durable identity snapshots are
        // intentionally retained and will be rehydrated only under the successor account/server
        // key; keeping the old rows here would create an A-account/B-account preview window.
        homeStore.reset()
        homeShellStore.reset()
    }
    AppShellSessionResetEffects(
        authSessionResetGenerationState = authSessionResetGenerationState,
        appliedAuthSessionResetGenerationState = appliedAuthSessionResetGenerationState,
        homeState = homeState,
        authGateStateState = authGateStateState,
        shellAuthStore = shellAuthStore,
        bridge = bridge,
        authGateViewModel = authGateViewModel,
        authGateMessageState = authGateMessageState,
        storeScope = storeScope,
        authDataSource = authDataSource,
        resetAppShellSessionState = ::resetAppShellSessionState,
    )

    AppShellRuntimeAuthorityEffects(
        desktopModeOnLargeScreenState = desktopModeOnLargeScreenState,
        bridge = bridge,
        conversationForwardHistory = conversationForwardHistory,
        settingsCapabilities = settingsCapabilities,
        chatLayoutPreferenceEvents = chatLayoutPreferenceEvents,
        chatLayoutPreferenceRepository = chatLayoutPreferenceRepository,
        myMessageAlignmentState = myMessageAlignmentState,
        compactConsecutiveMessagesState = compactConsecutiveMessagesState,
        foregroundMessageBannersEnabledState = foregroundMessageBannersEnabledState,
        serverDrawerEnabledState = serverDrawerEnabledState,
        conversationForwardGestureEnabledState = conversationForwardGestureEnabledState,
        foregroundBannerStore = foregroundBannerStore,
    )
    LaunchedEffect(conversationForwardGestureEnabled, authoritativeHomeStartAllowed) {
        conversationForwardHistory.setEnabled(conversationForwardGestureEnabled)
        armConversationForwardGesture()
    }
    val latestForegroundMessageBannersEnabled by rememberUpdatedState(foregroundMessageBannersEnabled)
    // The in-app banner obeys the device push preview choice (read per event from the single store).
    val pushPreviewModeStore = remember { KeyValuePushPreviewModePreferenceStore(raftSharedPreferenceStore()) }
    val latestPushPreviewBannerStrings by rememberUpdatedState(
        RaftPushPreviewBannerStrings(
            hiddenTitle = stringResource(Res.string.push_preview_hidden_title),
            hiddenMessage = stringResource(Res.string.push_preview_hidden_message),
            hiddenMention = stringResource(Res.string.push_preview_hidden_mention),
            senderOnlyMessageTemplate =
                stringResource(Res.string.push_preview_sender_only_message, RaftPushPreviewBannerStrings.SENDER_PLACEHOLDER),
            senderOnlyMentionTemplate =
                stringResource(Res.string.push_preview_sender_only_mention, RaftPushPreviewBannerStrings.SENDER_PLACEHOLDER)
        )
    )
    AppShellContentStageEffect(
        selectedTabState = selectedTabState,
        authGateStateState = authGateStateState,
        appShellContentStageState = appShellContentStageState,
        homeState = homeState,
        cachedHomePreview = cachedHomePreview,
    )
    AppShellAuthGateContent(
        authGateStateState = authGateStateState,
        cachedHomePreview = cachedHomePreview,
        bridge = bridge,
        shellScope = shellScope,
        homeState = homeState,
        suppressRestoreLanding = suppressRestoreLanding,
        adaptivePane = adaptivePane,
        selectedTabState = selectedTabState,
        authGateMessageState = authGateMessageState,
        pager = pager,
        authDataSource = authDataSource,
        authGateViewModel = authGateViewModel,
        resetAppShellSessionState = ::resetAppShellSessionState,
    )
    // Its branches for every gate but Ready end in the `return` that used to end this function.
    if (authGateState !is SharedAuthGateState.Ready) return

    if (setupAdmissionHost != null && !setupAdmissionHost.allowsHome && !cachedRestoreUsable) {
        setupAdmissionHost.content()
        return
    }
    SideEffect { setupAdmissionHost?.noteHomeShown?.invoke() }

    AppShellReadyServerEffect(
        sharedStores = sharedStores,
        conversationNotReadyMessage = conversationNotReadyMessage,
        pager = pager,
        bridge = bridge,
        authGateStateState = authGateStateState,
        myMessageAlignmentState = myMessageAlignmentState,
        compactConsecutiveMessagesState = compactConsecutiveMessagesState,
        readySession = readySession,
        readyServerId = readyServerId,
        authoritativeHomeStartAllowed = authoritativeHomeStartAllowed,
        navigationStoreScope = navigationStoreScope,
        appShellConversationDirectory = appShellConversationDirectory,
        threadTimeContext = threadTimeContext,
    )

    /**
     * Opens [launchPayload] in the shared workspace detail when the wide dual-pane workspace can
     * show it. Returns the new selection, or null when the caller must fall back to a native push.
     *
     * task #33: reduces on the store's CURRENT session inside the atomic update. Seeding from the
     * compose snapshot here was a lost update: a first tap right after switching to the DMs tab
     * could write back parentVisible=false (pre-switch), leaving the new detail inactive while the
     * previous conversation (e.g. Home's #all) stayed on screen.
     */
    fun openLaunchPayloadInAdaptiveDetail(
        launchPayload: WireThreadLaunchPayload,
        defaultLanding: Boolean = false,
    ): RaftAdaptiveDetailSelection? {
        val adaptiveIdentity = readyServerId?.let(launchPayload::toRaftAdaptiveDetailIdentity)
        if (!desktopModeOnLargeScreen || adaptiveIdentity == null) return null
        var before: SharedAdaptiveWorkspaceSession? = null
        val after =
            adaptiveWorkspaceStore.update { current ->
                before = current
                current.withOpenedDetail(adaptiveIdentity, launchPayload, defaultLanding)
            }
        val opened =
            after.workspace?.selection?.takeIf { after !== before && it.identity == adaptiveIdentity }
                ?: return null
        if (opened != before?.workspace?.selection || before?.launchPayload == null) {
            installAdaptiveHandoffLease(opened, launchPayload)
        }
        activityArtifactState.close()
        // Dual-pane detail does not push a native controller, so it must neither become
        // a Forward entry nor leave an older native-push branch reachable.
        conversationForwardHistory.clear()
        bridge.setConversationForwardGestureEnabled(false)
        return opened
    }

    fun openHomeConversation(
        conversation: ThreadLaunchConversationIdentity,
        source: String,
        anchorMessageId: String? = null,
        recordForwardHistory: Boolean = true,
        originFallback: ThreadLaunchOriginFallback? = null,
        // Shell-initiated wide default landing: only ever fills the visible empty pane; it must
        // never fall back to a standalone push or park a compact selection the user did not pick.
        adaptiveDefaultLanding: Boolean = false,
    ) {
        if (
            !appShellHomeConversationOpenAllowed(
                authoritativeHomeStartAllowed = latestAuthoritativeHomeStartAllowed,
                cachedRestoreUsable = latestCachedRestoreUsable
            )
        ) {
            bridge.logWarn(
                "home_open_conversation_drop reason=home_authority_pending " +
                    "threadId=${conversation.threadId.value} source=$source"
            )
            return
        }
        if (!latestAuthoritativeHomeStartAllowed) {
            bridge.log(
                "home_open_conversation_during_cached_restore threadId=${conversation.threadId.value} source=$source"
            )
        }
        bridge.log(
            "home_open_conversation threadId=${conversation.threadId.value} " +
                "kind=${conversation.kind.name} source=$source anchorMessageId=${anchorMessageId.orEmpty()}"
        )
        bridge.performanceMarker(
            phase = "home_open",
            routeName = "kuikly_home",
            event = "instant",
            threadId = conversation.threadId.value,
            metadata =
                mapOf(
                    "kind" to conversation.kind.name,
                    "source" to source,
                    "anchor" to (anchorMessageId != null).toString()
                )
        )
        homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(conversation.threadId.value))
        val launchPayload =
            buildStandaloneConversationThreadLaunchPayload(
                conversation = conversation,
                timeContext = threadTimeContext,
                alignOwnMessagesRight = myMessageAlignment == MyMessageAlignmentPreference.Right,
                compactConsecutiveMessages = compactConsecutiveMessages,
                    onWarn = { bridge.logWarn(it) },
                anchorMessageId = anchorMessageId,
                originFallback = originFallback
            ) ?: return
        bridge.log(
            "home_open_thread_launch source=$source threadId=${conversation.threadId.value} " +
                "sourceThreadId=${launchPayload.sourceThreadId.orEmpty()} surface=${launchPayload.surfaceKind} " +
                "parentChannelId=${launchPayload.parentChannelId.orEmpty()} parentMessageId=${launchPayload.parentMessageId.orEmpty()} " +
                "anchorMessageId=${anchorMessageId.orEmpty()} originParentMessageId=${launchPayload.originParentMessageId.orEmpty()}"
        )
        val adaptiveIdentity = readyServerId?.let(launchPayload::toRaftAdaptiveDetailIdentity)
        if (openLaunchPayloadInAdaptiveDetail(launchPayload, adaptiveDefaultLanding) != null) return
        if (adaptiveDefaultLanding) {
            bridge.log("home_open_conversation_landing_refused threadId=${conversation.threadId.value}")
            return
        }
        // task #45: while desktop mode is OFF the standalone push still owns the screen,
        // but a parked workspace (same server) must track the user's current selection —
        // otherwise the next ON tick would resurrect a stale pre-OFF detail.
        if (!desktopModeOnLargeScreen && adaptiveIdentity != null) {
            adaptiveWorkspaceStore.update { current ->
                val workspace = current.workspace ?: return@update current
                if (workspace.serverId != adaptiveIdentity.serverId) return@update current
                val next =
                    reduceRaftAdaptiveWorkspace(
                        workspace,
                        RaftAdaptiveWorkspaceIntent.ParkSelection(adaptiveIdentity),
                    )
                current.copy(
                    workspace = next,
                    launchPayload =
                        if (next.selection?.identity == adaptiveIdentity) launchPayload
                        else current.launchPayload,
                )
            }
        }
        if (recordForwardHistory) {
            readyServerId
                ?.takeIf { it.isNotBlank() }
                ?.let { serverId ->
                    conversationForwardHistory.recordNewOpen(
                        conversation.toConversationForwardEntry(serverId)
                    )
                }
        }
        bridge.setConversationForwardGestureEnabled(false)
        pager.openStandaloneThreadLaunchPayload(launchPayload, navigationStoreScope)
    }

    fun openHomeMessageRequest(request: HomeMessageOpenRequest, source: String, fallbackAnchorMessageId: String? = null) {
        openHomeConversation(
            conversation = request.conversationIdentity,
            source = source,
            anchorMessageId = request.anchorMessageId ?: fallbackAnchorMessageId,
            // Notification/system window routing is not a user-selected Home branch.
            recordForwardHistory = false,
            originFallback = request.originFallback
        )
    }

    // Back on Home from a pushed conversation: the conversation it left becomes the Forward
    // entry. On iOS the shell's Compose pageDidAppear (pageVisibleTick) does not reliably follow a
    // native push/pop, so the host's own viewDidAppear (hostDidAppearTick) counts as well.
    LaunchedEffect(pageVisibleTick, hostDidAppearTick, authoritativeHomeStartAllowed) {
        if (!authoritativeHomeStartAllowed) {
            bridge.setConversationForwardGestureEnabled(false)
            return@LaunchedEffect
        }
        if (conversationForwardHomeReturns.consume(pageVisibleTick, hostDidAppearTick)) {
            conversationForwardHistory.recordHomeVisible()
            bridge.log(
                "conversation_forward_home_visible tick=$pageVisibleTick hostTick=$hostDidAppearTick " +
                    "available=${conversationForwardHistory.canForward}"
            )
        }
        armConversationForwardGesture()
    }

    // Also keyed on the inputs of the arming rule, so the gesture is armed as soon as they all
    // hold: a Home whose list is still loading when the shell reappears, or a Beta preference
    // switched on with Home showing, used to stay unarmed until the next page appearance.
    LaunchedEffect(
        homeSurfaceVisible,
        readyServerId,
        renderedHomeServerId,
        conversationForwardRequestTick,
        authoritativeHomeStartAllowed,
        homeContentReadyForConversationForward,
        conversationForwardGestureEnabled,
    ) {
        if (!authoritativeHomeStartAllowed) {
            if (conversationForwardRequestTick > handledConversationForwardRequestTick) {
                bridge.log("conversation_forward_drop reason=home_start_not_authoritative")
            }
            bridge.setConversationForwardGestureEnabled(false)
            return@LaunchedEffect
        }
        if (conversationForwardRequestTick <= handledConversationForwardRequestTick) {
            armConversationForwardGesture()
            return@LaunchedEffect
        }
        handledConversationForwardRequestTick = conversationForwardRequestTick
        bridge.setConversationForwardGestureEnabled(false)
        if (!homeSurfaceVisible) {
            bridge.log("conversation_forward_drop reason=home_surface_not_visible")
            return@LaunchedEffect
        }
        val content = homeState.content as? SharedHomeContentState.Content
        val readiness =
            WindowNavigationReadiness(
                knownServerIds = readySession?.servers.orEmpty().mapTo(linkedSetOf()) { server -> server.id },
                canonicalServerId = readySession?.currentServerId,
                homeSnapshotServerId = content?.snapshot?.server?.id?.value,
                homeSessionServerId = content?.session?.currentServerId,
                conversationDirectory = appShellConversationDirectory
            )
        when (val action = conversationForwardHistory.requestForward(readiness)) {
            is ConversationForwardAction.Open -> {
                bridge.log(
                    "conversation_forward_open serverId=${action.entry.serverId} " +
                        "threadId=${action.request.conversationIdentity.threadId.value}"
                )
                openHomeConversation(
                    conversation = action.request.conversationIdentity,
                    source = "ios_forward_gesture",
                    anchorMessageId = action.request.anchorMessageId,
                    recordForwardHistory = false,
                    originFallback = action.request.originFallback
                )
            }
            is ConversationForwardAction.Drop -> {
                bridge.logWarn("conversation_forward_drop reason=${action.reason}")
                bridge.toast(conversationUnavailableMessage)
            }
            null -> {
                bridge.log("conversation_forward_defer reason=home_or_directory_not_ready")
                armConversationForwardGesture()
            }
        }
    }

    // External sources only transport this typed identity envelope in pageData. The queue and
    // its consumed/opened markers live with the window's RaftShellStoreScope, so an AppShell
    // recreation or duplicate PendingIntent cannot replay the route. #4260: the opens themselves
    // run in [navigationIntake], which reads the latest shell state below; this effect only wakes
    // it when that state changes while the shell composes.
    AppShellChatLayoutEffect(
        windowNavigationCoordinator = windowNavigationCoordinator,
        myMessageAlignmentState = myMessageAlignmentState,
        notificationWindowNavigationIntent = notificationWindowNavigationIntent,
        desktopShortcutWindowNavigationIntent = desktopShortcutWindowNavigationIntent,
        navigationIntake = navigationIntake,
        homeState = homeState,
        readySession = readySession,
        authoritativeHomeStartAllowed = authoritativeHomeStartAllowed,
        appShellConversationDirectory = appShellConversationDirectory,
        threadTimeContext = threadTimeContext,
    )
    val latestNavigationReadinessState = rememberUpdatedState {
        if (!authoritativeHomeStartAllowed) return@rememberUpdatedState null
        val content = homeState.content as? SharedHomeContentState.Content
        WindowNavigationReadiness(
            knownServerIds = readySession?.servers.orEmpty().mapTo(linkedSetOf()) { server -> server.id },
            canonicalServerId = readySession?.currentServerId,
            homeSnapshotServerId = content?.snapshot?.server?.id?.value,
            homeSessionServerId = content?.session?.currentServerId,
            conversationDirectory = appShellConversationDirectory
        )
    }
    val latestNavigationReadiness by latestNavigationReadinessState
    val latestNavigationPerformState = rememberUpdatedState<suspend (WindowNavigationAction) -> Unit> { action ->
        val content = homeState.content as? SharedHomeContentState.Content
        when (action) {
            is WindowNavigationAction.ActivateServer -> {
                bridge.log(
                    "window_navigation_server_activate source=${action.intent.source} " +
                        "targetServerId=${action.intent.serverId} currentServerId=${readySession?.currentServerId.orEmpty()}"
                )
                when (val result = authDataSource.selectServer(action.intent.serverId)) {
                    is SharedAuthResult.Success -> {
                        authGateViewModel.dispatch(SharedAuthGateIntent.ServerReady(result.data))
                        resetAppShellSessionState()
                        bridge.log("window_navigation_server_activated targetServerId=${action.intent.serverId}")
                        if (action.intent.source == MessageLinkNavigationSource) {
                            result.data.servers.firstOrNull { it.id == action.intent.serverId }?.name
                                ?.takeIf { it.isNotBlank() }
                                ?.let { name ->
                                    bridge.toast(messageLinkSwitchedServerTemplate.replace(MessageLinkServerNamePlaceholder, name))
                                }
                        }
                    }
                    is SharedAuthResult.Failure -> {
                        windowNavigationCoordinator.rejectActivation(action.intent.key, result.code.name)
                            ?.let { rejection ->
                                bridge.logWarn("window_navigation_drop reason=${rejection.reason}")
                                if (rejection.intent.source == MessageLinkNavigationSource) {
                                    pager.openRaftMessageLinkNotFoundPage(navigationStoreScope)
                                } else {
                                    bridge.errorToast(notificationServerUnavailableMessage, result.traceId)
                                }
                            }
                    }
                    SharedAuthResult.Unauthenticated -> {
                        windowNavigationCoordinator.rejectActivation(action.intent.key, "unauthenticated")
                            ?.let { rejection ->
                                bridge.logWarn("window_navigation_drop reason=${rejection.reason}")
                                bridge.toast(notificationSignInMessage)
                            }
                    }
                }
            }
            is WindowNavigationAction.EnsureHome -> {
                homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.Start))
                bridge.log(
                    "window_navigation_defer reason=home_not_ready_for_server " +
                        "source=${action.intent.source} targetServerId=${action.intent.serverId} " +
                        "contentServerId=${content?.snapshot?.server?.id?.value.orEmpty()}"
                )
            }
            is WindowNavigationAction.OpenConversation -> {
                selectAppShellTab("home")
                retainedAppShellTabs = retainedAppShellTabs + "home"
                homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
                bridge.log(
                    "window_navigation_open source=${action.intent.source} " +
                        "threadId=${action.request.conversationIdentity.threadId.value} " +
                        "focusedMessageId=${action.request.anchorMessageId.orEmpty()}"
                )
                openHomeMessageRequest(action.request, source = action.intent.source)
            }
            is WindowNavigationAction.Reject -> {
                bridge.logWarn(
                    "window_navigation_drop source=${action.intent.source} reason=${action.reason} " +
                        "targetServerId=${action.intent.serverId}"
                )
                if (action.intent.source == MessageLinkNavigationSource) {
                    pager.openRaftMessageLinkNotFoundPage(navigationStoreScope)
                    return@rememberUpdatedState
                }
                bridge.toast(
                    if (action.reason == "server_unavailable") {
                        Res.string.home_error_notification_server_unavailable.desc().resolve()
                    } else {
                        Res.string.home_error_conversation_unavailable.desc().resolve()
                    }
                )
            }
        }
    }
    val latestNavigationPerform by latestNavigationPerformState
    // An external message link (Android App Link / iOS Universal Link). It waits in the process
    // inbox while signed out or restoring; with a ready session [navigationIntake] takes it once,
    // resolves {slug}/conversation/message for this account, and either queues the same
    // identity-only WindowNavigationIntent a notification tap queues, or shows the one
    // "Message not found" page. The resolve runs in the intake's scope so a session/key change
    // after the take cannot drop the link half-way.
    val messageLinkSessionReady = authoritativeHomeStartAllowed && readySession != null
    AppShellMessageLinkWakeEffect(
        navigationIntake = navigationIntake,
        messageLinkSessionReady = messageLinkSessionReady,
    )
    val latestMessageLinkSessionReadyState = rememberUpdatedState(messageLinkSessionReady)
    val latestMessageLinkSessionReady by latestMessageLinkSessionReadyState
    val latestResolveMessageLinkState = rememberUpdatedState<suspend (RaftPendingMessageLink) -> WindowNavigationIntent?> { taken ->
        val servers = readySession?.servers.orEmpty()
        bridge.log("message_link_resolve id=${taken.id} servers=${servers.size}")
        val resolver = RaftMessageLinkResolver(
            appShellNetworkClient,
            diagnosticsLogger = bridge::log,
            contextPageSink = sharedStores.messageContextPageSink(storeScope),
        )
        when (val resolution = resolver.resolve(taken.link, servers)) {
            is RaftMessageLinkResolution.Open ->
                WindowNavigationIntent(
                    serverId = resolution.serverId,
                    sourceIdentity = resolution.sourceIdentity,
                    source = MessageLinkNavigationSource,
                    launchId = "message-link-${taken.id}"
                )
            is RaftMessageLinkResolution.NotFound -> {
                // The page's button goes back to the conversation list (Home tab) on a phone, the
                // server's channel list on a wide window: leave the Home tab with nothing open under it.
                selectAppShellTab("home")
                retainedAppShellTabs = retainedAppShellTabs + "home"
                homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
                pager.openRaftConversationUnavailablePage(navigationStoreScope)
                null
            }
            is RaftMessageLinkResolution.PublicPreview -> {
                pager.openRaftMessageLinkPublicPreviewPage(resolution.link, navigationStoreScope)
                null
            }
            is RaftMessageLinkResolution.PublicNotFound -> {
                // A server the account is not in, with nothing public for this link: the neutral
                // "Message not found" page over whatever is shown, as a rejected link opens it (not
                // the conversation-unavailable page, which is for one of the account's servers).
                pager.openRaftMessageLinkNotFoundPage(navigationStoreScope)
                null
            }
        }
    }
    val latestResolveMessageLink by latestResolveMessageLinkState
    // An external Artifact link (`/s/{slug}/artifacts?artifact=…`, slock #13670): the in-app
    // viewer, over whatever is shown; its web page in the browser only when no page can open here.
    AppShellExternalLinkNavigationEffects(
        sharedStores = sharedStores,
        pager = pager,
        desktopModeOnLargeScreenState = desktopModeOnLargeScreenState,
        bridge = bridge,
        storeScope = storeScope,
        processRealtimeAuthorityState = processRealtimeAuthorityState,
        appShellAgentDetailStore = appShellAgentDetailStore,
        selectedServerIdState = selectedServerIdState,
        canonicalFinalSelectedServerId = canonicalFinalSelectedServerId,
        linkNavigator = linkNavigator,
        navigationIntake = navigationIntake,
        readySession = readySession,
        authoritativeHomeStartAllowed = authoritativeHomeStartAllowed,
        navigationStoreScope = navigationStoreScope,
        realtimeConnectionFamilyIdState = realtimeConnectionFamilyIdState,
        pendingRealtimeAuthRefreshReasonState = pendingRealtimeAuthRefreshReasonState,
        pendingRealtimeAuthRefreshPreviousFamilyIdState = pendingRealtimeAuthRefreshPreviousFamilyIdState,
        realtimeAuthorityConnectTickState = realtimeAuthorityConnectTickState,
        realtimeShellForegroundTickState = realtimeShellForegroundTickState,
        realtimeReconciledOwnerGenerationState = realtimeReconciledOwnerGenerationState,
        foregroundBannerStore = foregroundBannerStore,
        latestNavigationReadinessState = latestNavigationReadinessState,
        latestNavigationPerformState = latestNavigationPerformState,
        messageLinkSessionReady = messageLinkSessionReady,
        latestMessageLinkSessionReadyState = latestMessageLinkSessionReadyState,
        latestResolveMessageLinkState = latestResolveMessageLinkState,
        sendPromptHostState = sendPromptHostState,
    )

    // Wide Home default landing runs AFTER AppShellChatLayoutEffect & AppShellExternalLinkNavigationEffects
    // so launch notification/shortcut intents are enqueued first and any immediately-resolvable
    // OpenConversation claims the detail pane before default landing falls back to #all (task #243).
    val windowNavigationStateVersion by windowNavigationCoordinator.stateVersion.collectAsState()
    // Only into Chat's own detail, not beside Activity's or an inline list (RaftRailPlaces.kt).
    val chatDetailShown = appShellChatDetailShown(selectedTab, selectedHomeDestination, railInlineSecondaryDestination)
    LaunchedEffect(
        desktopModeOnLargeScreen,
        adaptivePane,
        adaptiveParentVisible,
        adaptiveWorkspaceState?.pane,
        adaptiveWorkspaceState?.selection,
        adaptiveWorkspaceState?.parentVisible,
        adaptiveDefaultLandingServerId,
        readyServerId,
        selectedHomeConversationId,
        homeState.content,
        authoritativeHomeStartAllowed,
        windowNavigationStateVersion,
        chatDetailShown,
    ) {
        if (!authoritativeHomeStartAllowed) return@LaunchedEffect
        val liveSession = adaptiveWorkspaceStore.snapshot()
        val workspace = liveSession.workspace ?: return@LaunchedEffect
        val serverId = readyServerId?.trim()?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        if (
            !desktopModeOnLargeScreen ||
            !chatDetailShown ||
            workspace.serverId != serverId ||
            !workspace.acceptsAdaptiveDefaultLanding() ||
            liveSession.defaultLandingServerId == serverId
        ) {
            return@LaunchedEffect
        }
        val content = homeState.content as? SharedHomeContentState.Content ?: return@LaunchedEffect
        if (content.snapshot.server.id.value != serverId) return@LaunchedEffect
        // If an incoming notification/shortcut/deep-link OpenConversation is already resolvable
        // for this server, let navigationIntake claim the pane first. Only resolvable opens pause
        // default landing (unresolvable Pending routes never stall it), and advancing bumps
        // windowNavigationStateVersion so this effect always re-runs if the open falls through.
        if (latestNavigationReadiness()?.let(windowNavigationCoordinator::hasReadyOpenRequest) == true) {
            navigationIntake.wake()
            return@LaunchedEffect
        }
        val conversations: List<HomeConversationRow> =
            content.snapshot.channels + content.snapshot.directMessages
        // Switching servers clears the selection; the server's remembered conversation (web
        // `slock:serverSurface:v1:<slug>`) comes back instead of #all while it is still listed.
        val remembered = serverLastConversationRepository.read(serverLastConversationOwnerKey, serverId)
        val resolution =
            resolveRaftAdaptiveHomeLanding(
                selectedThreadId = selectedHomeConversationId,
                remembered = remembered,
                candidates =
                    conversations.map { row ->
                        RaftAdaptiveHomeLandingCandidate(
                            threadId = row.threadId.value,
                            name = row.name,
                            kind = row.kind,
                        )
                    },
            )
        if (resolution.rememberedUnavailable) {
            bridge.log("server_last_conversation_forget serverId=$serverId reason=not_listed")
            serverLastConversationRepository.forget(serverLastConversationOwnerKey, serverId)
        }
        val landingConversation =
            when (val landing = resolution.landing) {
                is RaftAdaptiveHomeLanding.Row ->
                    conversations
                        .firstOrNull { row -> row.threadId.value == landing.threadId }
                        ?.toThreadLaunchConversationIdentity()
                is RaftAdaptiveHomeLanding.ReplyThread -> landing.toThreadLaunchConversationIdentity()
                null -> null
            }
        if (landingConversation == null) {
            adaptiveWorkspaceStore.update { current ->
                if (current.workspace?.serverId == serverId) {
                    current.copy(defaultLandingServerId = serverId)
                } else {
                    current
                }
            }
        } else {
            openHomeConversation(
                conversation = landingConversation,
                source = "adaptive_default_landing",
                recordForwardHistory = false,
                adaptiveDefaultLanding = true,
            )
            // openHomeConversation records defaultLandingServerId atomically when it installs the
            // detail. If payload construction returned early while the pane still accepts landing
            // for this server, settle the guard so the empty pane never stays suppressed.
            adaptiveWorkspaceStore.update { current ->
                val currentWorkspace = current.workspace
                if (
                    currentWorkspace != null &&
                    currentWorkspace.serverId == serverId &&
                    currentWorkspace.acceptsAdaptiveDefaultLanding() &&
                    current.defaultLandingServerId != serverId
                ) {
                    current.copy(defaultLandingServerId = serverId)
                } else {
                    current
                }
            }
        }
    }

    fun dismissAdaptiveDetailSelection() {
        val current = adaptiveWorkspaceStore.snapshot().workspace
        bridge.log(
            "adaptive_detail_dismiss selection=${current?.selection?.identity?.key.orEmpty()} " +
                "presentation=${current?.presentation}"
        )
        if (current == null) return
        val selection = current.selection ?: return
        val request = RaftAdaptiveDetailCloseRequest(current.shellInstanceId, selection.identity.key, selection.generation)
        val next = adaptiveWorkspaceStore.update { it.backFromDetail(request) }
        releaseAdaptiveHandoffLease(selection.generation)
        homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(next.detailListSelectionId()))
    }

    /**
     * task #34: the task column's Close closes the whole detail, including any thread → channel
     * navigation made inside it (Back still unwinds one level at a time).
     */
    fun closeTasksDetailColumn() {
        val selection = adaptiveWorkspaceStore.snapshot().workspace?.selection
        bridge.log("tasks_detail_column_close selection=${selection?.identity?.key.orEmpty()}")
        tasksDetailColumnActive = false
        tasksDetailColumnExpanded = false
        RaftRatingPrompt.onTaskListShown()
        if (selection == null) return
        adaptiveWorkspaceStore.update { it.closeDetailCompletely() }
        releaseAdaptiveHandoffLease(selection.generation)
        homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(null))
    }

    fun popMainRoute(): Boolean {
        if (createChannelDialogOpen) {
            createChannelDialogOpen = false
            createChannelError = null
            createChannelErrorTraceId = null
            createChannelCollision = null
            return true
        }
        if (homeState.serverSwitcherExpanded) {
            serverDrawerProgrammaticCloseFence?.invoke("back")
            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.DismissServerSwitcher))
            return true
        }
        if (selectedTab == "members" && membersDetailSelection.value != null) {
            membersDetailSelection.value = null
            return true
        }
        if (
            desktopModeOnLargeScreen &&
            retainedAppShellSelectedTab(selectedTab) == "activity" &&
            railInlineSecondaryDestination == null &&
            activityArtifactState.close()
        ) {
            return true
        }
        // task #36: an open side thread closes first; the main detail stays.
        if (!tasksDetailColumnOpen && adaptiveWorkspaceStore.snapshot().backClosesSideThreadFirst(desktopModeOnLargeScreen)) {
            adaptiveWorkspaceStore.update { it.copy(sideThread = null) }
            return true
        }
        if (desktopModeOnLargeScreen && adaptiveWorkspaceStore.snapshot().hasVisibleDetailBackHistory()) {
            dismissAdaptiveDetailSelection()
            return true
        }
        if (
            desktopModeOnLargeScreen &&
            appShellRailSurfaceBackDismissesDetailFirst(
                selectedTab = selectedTab,
                railInlineSecondaryDestination = railInlineSecondaryDestination,
                selectionPresent =
                    adaptiveWorkspaceStore.snapshot().let { session ->
                        session.workspace?.selection != null &&
                            // The DMs tab does not show (so must not dismiss) a channel selection.
                            (
                                retainedAppShellSelectedTab(selectedTab) != "dms" ||
                                    appShellDmsTabShowsDetail(
                                        surfaceKind = session.launchPayload?.surfaceKind,
                                        parentSurfaceKind = session.launchPayload?.parentSurfaceKind,
                                    )
                            )
                    },
                tasksDetailColumnOpen = tasksDetailColumnOpen,
            )
        ) {
            dismissAdaptiveDetailSelection()
            return true
        }
        if (railInlineSecondaryDestination != null) {
            railInlineSecondaryDestination = null
            return true
        }
        if (selectedTab == "home" && selectedHomeDestination != null) {
            homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
            return true
        }
        // A server page (Members, Settings) goes back to the page the server panel opened it
        // from, or Home after a deep link, on the phone layout; focus returns to the server tab.
        val backNavRail =
            raftAdaptiveNavRailActive(
                desktopModeEnabled = desktopModeOnLargeScreen,
                paneState = shellScope.adaptivePane
            )
        appShellMoreChildBackTarget(
            selectedTab = selectedTab,
            navRailActive = backNavRail,
            origin = serverPageOrigin,
        )?.let { target ->
            selectAppShellTab(target, directChildSwitch = true, source = AppShellTabSwitchSource.Back)
            retainedAppShellTabs = retainedAppShellTabs + target
            serverTabFocusPending = true
            return true
        }
        // artin 2026-11-02: Back on a tab root returns to the tab picked before it (Activity ->
        // Search -> Back = Activity); with none left it goes Home, and Home leaves the app.
        fun backToEarlierTab(): Boolean {
            val target =
                appShellTabHistory.backTarget(selectedTab, appShellTabHistoryRoots(backNavRail))
                    ?: return false
            bridge.log("app_back_tab_history from=$selectedTab to=$target history=${appShellTabHistory.tabs.size}")
            selectAppShellTab(target, directChildSwitch = true, source = AppShellTabSwitchSource.Back)
            if (isRetainedAppShellTab(target)) retainedAppShellTabs = retainedAppShellTabs + target
            if (target == "home") homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
            return true
        }
        if (selectedTab != "home") return backToEarlierTab()
        if (
            desktopModeOnLargeScreen &&
            adaptiveWorkspaceState?.presentation == RaftAdaptiveWorkspacePresentation.DetailOnly
        ) {
            dismissAdaptiveDetailSelection()
            return true
        }
        return backToEarlierTab()
    }

    fun openAppShellActivityTab(source: String) {
        bridge.log("app_shell_activity_tab_open source=$source from=$selectedTab")
        railInlineSecondaryDestination = null
        if (shouldDismissHomeNotificationCenterForAppShellTabSelection(selectedTab, "activity")) {
            homeFocusLossDismissEpoch += 1
            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.DismissServerSwitcher))
        }
        selectAppShellTab("activity", directChildSwitch = true, source = AppShellTabSwitchSource.User)
        retainedAppShellTabs = retainedAppShellTabs + "activity"
    }

    fun openHomeSecondaryDestination(destination: String) {
        if (retainedHomeSecondaryDestination(destination) == HomeDestinationActivity) {
            // artin 2026-10-20: Activity is a bottom tab; the old Home 动态 destination (and any
            // caller still asking for it) lands on the kept-alive Activity tab, not a pushed page.
            openAppShellActivityTab(source = "home_secondary_destination")
            return
        }
        retainedHomeSecondaryDestination(destination)?.let { retainedDestination ->
            homeShellStore.dispatch(retainedDestination.toHomeSecondaryOpenIntent())
            pager.openHomeSecondaryPage(retainedDestination, threadTimeContext, navigationStoreScope)
        }
    }

    // stdrc 2026-10-21 (option B): Search is a phone tab. Opening search (the Home head's Search
    // button) selects it like a bar tap; the wide rail keeps the pushed search page.
    fun openSearchTab(source: String) {
        val navRail =
            raftAdaptiveNavRailActive(
                desktopModeEnabled = desktopModeOnLargeScreen,
                paneState = shellScope.adaptivePane
            )
        if (navRail) {
            bridge.log("app_shell_search_open source=$source tab=$selectedTab mode=rail_page")
            openHomeSecondaryDestination(HomeDestinationSearch)
            return
        }
        bridge.log("app_shell_search_open source=$source tab=$selectedTab mode=tab")
        if (shouldDismissHomeNotificationCenterForAppShellTabSelection(selectedTab, AppShellSearchTabKey)) {
            homeFocusLossDismissEpoch += 1
            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.DismissServerSwitcher))
        }
        selectAppShellTab(AppShellSearchTabKey, directChildSwitch = true, source = AppShellTabSwitchSource.User)
        retainedAppShellTabs = retainedAppShellTabs + AppShellSearchTabKey
    }

    // Nav spec §10.5: a conversation search's "Search all" popped the pages above the shell,
    // which took the request once (ComposeRaftAppShellPage). Show Search with that query: the
    // phone Search tab, or the rail's inline Search on the wide layout. The search page clears
    // [searchTabQueryRequest] once it has applied it.
    LaunchedEffect(searchHandoff?.nonce) {
        val request = searchHandoff ?: return@LaunchedEffect
        val navRail =
            raftAdaptiveNavRailActive(
                desktopModeEnabled = desktopModeOnLargeScreen,
                paneState = shellScope.adaptivePane
            )
        val target = appShellSearchHandoffTarget(navRail)
        bridge.log("app_shell_search_handoff nonce=${request.nonce} queryLength=${request.query.length} target=$target")
        searchTabQueryRequest = request
        when (target) {
            AppShellSearchHandoffTarget.SearchTab -> openSearchTab(source = "conversation_search_expand")
            AppShellSearchHandoffTarget.RailInlineSearch -> railInlineSecondaryDestination = AppShellRailSearchKey
        }
        onSearchHandoffApplied()
    }

    // Settings search's No match > Search everywhere: Search with the same query, the way a
    // conversation search's "Search all" hands it over (the Search tab, or the rail's inline Search).
    fun openSearchEverywhere(query: String, source: String) {
        val navRail =
            raftAdaptiveNavRailActive(
                desktopModeEnabled = desktopModeOnLargeScreen,
                paneState = shellScope.adaptivePane
            )
        val target = appShellSearchHandoffTarget(navRail)
        bridge.log("app_shell_search_everywhere source=$source queryLength=${query.length} target=$target")
        searchOverlayHandoffNonce -= 1
        searchTabQueryRequest = RaftSearchTabQueryRequest(query.trim(), nonce = searchOverlayHandoffNonce)
        when (target) {
            AppShellSearchHandoffTarget.SearchTab -> openSearchTab(source = source)
            AppShellSearchHandoffTarget.RailInlineSearch -> railInlineSecondaryDestination = AppShellRailSearchKey
        }
    }

    val handleAppShellBackState = rememberUpdatedState {
        if (popMainRoute()) {
            bridge.log(
                "app_back_root result=main selectedTab=$selectedTab " +
                    "homeDestination=${selectedHomeDestination.orEmpty()}"
            )
        } else {
            bridge.logWarn(
                "app_back_root result=close_page selectedTab=$selectedTab " +
                    "homeDestination=${selectedHomeDestination.orEmpty()}"
            )
            bridge.closePage()
        }
    }
    val handleAppShellBack by handleAppShellBackState

    AppShellBackAndTasksEffects(
        selectedTabState = selectedTabState,
        tasksSurfaceActive = tasksSurfaceActive,
        bridge = bridge,
        appShellServerMembersStore = appShellServerMembersStore,
        selectedHomeDestination = selectedHomeDestination,
        tasksFirstRenderLoggedKeyState = tasksFirstRenderLoggedKeyState,
        readyServerId = readyServerId,
        tasksRoute = tasksRoute,
        tasksState = tasksState,
        authoritativeHomeStartAllowed = authoritativeHomeStartAllowed,
        handleAppShellBackState = handleAppShellBackState,
    )

    fun loadCreateChannelMembers() {
        createChannelMembersLoading = true
        appShellCoroutineScope.launch {
            try {
                val session = shellAuthStore.readSession()
                val serverId = session?.currentServerId
                if (serverId.isNullOrBlank()) {
                    createChannelMembers = emptyList()
                    createChannelMembersLoading = false
                    return@launch
                }
                createChannelMembers =
                    when (val result = appShellServerMembersStore.refreshMembers(ServerId(serverId), DataRequestPriority.Visible)) {
                        is DataResult.Content -> appShellServerMembersStore.currentServerMembers(ServerId(serverId))
                        is DataResult.Empty,
                        is DataResult.Error,
                        is DataResult.NoPermission -> emptyList()
                    }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                createChannelMembers = emptyList()
            } finally {
                createChannelMembersLoading = false
            }
        }
    }

    fun openCreateChannelDialog(external: Boolean = false) {
        if (!latestPageVisible) return
        createChannelExternalChosen = external
        createChannelSession += 1
        createChannelDialogOpen = true
        createChannelError = null
        createChannelErrorTraceId = null
        createChannelCollision = null
        createChannelMembers = emptyList()
        loadCreateChannelMembers()
    }

    // The External section's "+" ([externalCreateEntry], web useExternalLimitGate surface sidebar):
    // prefetches Free external-channel slot availability so opens are immediate when the slot is
    // free, and re-reads + passes channel context when the slot appears taken.
    var externalCreateGateReading by remember { mutableStateOf(false) }
    var prefetchedExternalFreeLimit by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val homeExternalSnapshot = (homeState.content as? SharedHomeContentState.Content)?.snapshot
    val latestHomeExternalSnapshot by rememberUpdatedState(homeExternalSnapshot)
    val homeExternalServerId = homeExternalSnapshot?.server?.id?.value
    val homeExternalServerPlan = homeExternalSnapshot?.server?.plan
    val homeCanCreateExternal = externalChannelCreateAccess(homeExternalSnapshot?.server?.role)
    val homeExternalSlotKey =
        remember(homeExternalSnapshot?.channels, homeExternalServerId) {
            if (homeExternalSnapshot == null || homeExternalServerId == null) {
                ""
            } else {
                externalSlotChannelsKey(homeExternalSnapshot.channels, homeExternalServerId)
            }
        }
    LaunchedEffect(homeExternalServerId, homeExternalServerPlan, homeCanCreateExternal, homeExternalSlotKey) {
        prefetchedExternalFreeLimit = null
        val serverId = homeExternalServerId
        if (serverId == null || homeExternalServerPlan != "free" || !homeCanCreateExternal) {
            return@LaunchedEffect
        }
        val reached =
            runCatchingPreservingCancellation {
                RealCreateExternalChannelDataSource(appShellNetworkClient, channelRepository).freeLimitReached()
            }.getOrNull()
        if (reached != null) {
            prefetchedExternalFreeLimit = serverId to reached
        }
    }
    fun openExternalCreateChannelGated() {
        if (externalCreateGateReading) return
        val snapshot = latestHomeExternalSnapshot
        val serverId = snapshot?.server?.id?.value
        val plan = snapshot?.server?.plan
        if (plan != "free" || (serverId != null && prefetchedExternalFreeLimit == (serverId to false))) {
            openCreateChannelDialog(external = true)
            return
        }
        externalCreateGateReading = true
        // Undispatched: a server that is not Free reads nothing, so Create Channel opens in this tap.
        appShellCoroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val reached =
                try {
                    runCatchingPreservingCancellation {
                        RealCreateExternalChannelDataSource(appShellNetworkClient, channelRepository).freeLimitReached()
                    }.getOrNull()
                } finally {
                    externalCreateGateReading = false
                }
            val liveSnapshot = latestHomeExternalSnapshot ?: return@launch
            if (serverId != null && liveSnapshot.server.id.value != serverId) return@launch
            if (liveSnapshot.server.plan != "free") {
                openCreateChannelDialog(external = true)
                return@launch
            }
            if (serverId != null && reached != null) {
                prefetchedExternalFreeLimit = serverId to reached
            }
            if (reached == true) {
                if (settingsBillingOffersUpgrade()) {
                    pager.openRaftUpgradeDialog(
                        gate = RaftUpgradeGate.ExternalChannelFreeLimit,
                        storeScope = storeScope,
                        externalContext =
                            externalSlotUpgradeContext(liveSnapshot.channels, liveSnapshot.server.id.value),
                    )
                } else {
                    pager.openSettingsBillingPage(storeScope)
                }
            } else {
                openCreateChannelDialog(external = true)
            }
        }
    }

    // A created channel: the dialog closes onto it (tygg 11-06: land in the new channel, as the
    // archived-name Open and Unarchive do). [toast]: the plain create's "created" line; the
    // External flow says what its invites came to instead.
    fun landInCreatedChannel(channel: HomeChannelRow, toast: Boolean = true) {
        selectAppShellTab("home")
        retainedAppShellTabs = retainedAppShellTabs + "home"
        homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
        homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(channel.threadId.value))
        homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.ChannelCreated(channel)))
        homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.Refresh))
        createChannelDialogOpen = false
        createChannelError = null
        createChannelErrorTraceId = null
        if (toast) bridge.toast(channelCreatedMessage)
        openHomeConversation(channel.toThreadLaunchConversationIdentity(), "create_channel")
    }

    fun showCreateChannelRefusal(result: DataResult<*>) {
        when (result) {
            is DataResult.Content -> Unit
            is DataResult.Empty -> {
                createChannelError = channelNotCreatedMessage
            }
            is DataResult.Error -> {
                // An archived channel the user can see holds the name: say so and offer
                // Open Channel / Unarchive / Change Name (web, slock #12614). Any other
                // refusal, including the plain "already taken", keeps the error line.
                val collision = result.error.archivedNameCollisionOrNull()
                if (collision != null) {
                    createChannelCollision = collision
                } else {
                    createChannelError = result.error.code
                    createChannelErrorTraceId = result.error.traceId
                }
            }
            is DataResult.NoPermission -> {
                createChannelError = result.blocker.code
                createChannelErrorTraceId = result.blocker.traceId
            }
        }
    }

    fun submitCreateChannel(
        name: String,
        description: String?,
        visibility: String,
        agentMemberIds: List<String>,
        userMemberIds: List<String>
    ) {
        val trimmedName = name.trim()
        if (!trimmedName.isSubmittableChannelName() || createChannelSubmitting) {
            return
        }
        createChannelSubmitting = true
        createChannelError = null
        createChannelErrorTraceId = null
        createChannelCollision = null
        appShellCoroutineScope.launch {
            try {
                when (
                    val result =
                        channelRepository.createChannel(
                            CreateChannelRequest(
                                name = trimmedName,
                                description = description?.trim()?.takeIf { it.isNotBlank() },
                                visibility = visibility.trim().ifBlank { "public" },
                                agentMemberIds = agentMemberIds,
                                userMemberIds = userMemberIds
                            )
                        )
                ) {
                    is DataResult.Content -> landInCreatedChannel(result.data.channel)
                    else -> showCreateChannelRefusal(result)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                createChannelError = "channel_create_failed"
            } finally {
                createChannelSubmitting = false
            }
        }
    }

    // Web Open Channel: leave the dialog for the archived channel, read-only.
    fun openCreateChannelCollision() {
        val collision = createChannelCollision ?: return
        if (createChannelSubmitting) return
        createChannelDialogOpen = false
        createChannelError = null
        createChannelErrorTraceId = null
        createChannelCollision = null
        openHomeConversation(
            buildThreadLaunchConversationIdentity(
                kind = ConversationKind.Channel,
                channelId = collision.archivedChannelId,
                channelType = collision.archivedChannelType,
            ),
            "create_channel_archived_open",
        )
    }

    // Web Unarchive: restore the archived channel, then leave the dialog for it.
    fun unarchiveCreateChannelCollision() {
        val collision = createChannelCollision ?: return
        if (createChannelSubmitting || !collision.canUnarchive) return
        createChannelSubmitting = true
        createChannelError = null
        createChannelErrorTraceId = null
        appShellCoroutineScope.launch {
            try {
                when (val result = channelRepository.unarchiveChannel(collision.archivedChannelId)) {
                    is DataResult.Content -> {
                        createChannelDialogOpen = false
                        createChannelCollision = null
                        homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.Refresh))
                        openHomeConversation(result.data.toThreadLaunchConversationIdentity(), "create_channel_archived_unarchive")
                    }
                    is DataResult.Error -> {
                        createChannelError = result.error.serverMessage ?: channelUnarchiveFailedMessage
                        createChannelErrorTraceId = result.error.traceId
                    }
                    is DataResult.NoPermission -> {
                        createChannelError = result.blocker.serverMessage ?: channelUnarchiveFailedMessage
                        createChannelErrorTraceId = result.blocker.traceId
                    }
                    is DataResult.Empty -> {
                        createChannelError = channelUnarchiveFailedMessage
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                createChannelError = channelUnarchiveFailedMessage
            } finally {
                createChannelSubmitting = false
            }
        }
    }

    fun handleRealtimeAuthRefreshRequest(eventData: JsonObject) {
        val requestedServerId = eventData.optString("serverId").takeIf { it.isNotBlank() } ?: return
        val reason = eventData.optString("reason").takeIf { it.isNotBlank() } ?: "reconnect"
        val currentSession =
            (authGateViewModel.state.value as? SharedAuthGateState.Ready)?.session
                ?: readySession
                ?: run {
                    bridge.realtimeAuthRefreshFinished(reason, success = false)
                    return
                }
        val currentServerId = selectedServerStore.selectedServerId.value
        if (!canRefreshRealtimeServer(currentSession, requestedServerId)) {
            bridge.log(
                "shared_realtime_auth_refresh_skip reason=$reason requestedServerId=$requestedServerId " +
                    "currentServerId=${currentServerId.orEmpty()} result=server_not_authenticated"
            )
            bridge.realtimeAuthRefreshFinished(reason, success = false)
            return
        }
        val previousFamilyId = realtimeConnectionFamilyId
        appShellCoroutineScope.launch {
            when (val result = authDataSource.refreshRealtimeAuth(reason)) {
                is SharedAuthResult.Success -> {
                    val refreshed = result.data
                    if (!canRefreshRealtimeServer(refreshed, requestedServerId) || refreshed.accessToken.isBlank()) {
                        bridge.log("shared_realtime_auth_refresh_skip reason=$reason result=server_mismatch")
                        bridge.realtimeAuthRefreshFinished(reason, success = false)
                        return@launch
                    }
                    authGateViewModel.dispatch(SharedAuthGateIntent.ServerReady(refreshed))
                    appShellAgentDetailStore.dispatch(SharedAgentDetailIntent.ResetAgentActivitySeq)
                    pendingRealtimeAuthRefreshReason = reason
                    pendingRealtimeAuthRefreshPreviousFamilyId = previousFamilyId
                    realtimeAuthorityConnectTick += 1
                }
                SharedAuthResult.Unauthenticated -> {
                    pendingRealtimeAuthRefreshReason = null
                    pendingRealtimeAuthRefreshPreviousFamilyId = null
                    bridge.realtimeAuthRefreshFinished(reason, success = false)
                    raftLogInfo(
                        "SlockSharedAuth",
                        "shared_realtime_auth_refresh_failed reason=$reason result=unauthenticated",
                        RaftPlatformLogHint.AuthCritical
                    )
                }
                is SharedAuthResult.Failure -> {
                    pendingRealtimeAuthRefreshReason = null
                    pendingRealtimeAuthRefreshPreviousFamilyId = null
                    bridge.realtimeAuthRefreshFinished(reason, success = false)
                    raftLogInfo(
                        "SlockSharedAuth",
                        "shared_realtime_auth_refresh_failed reason=$reason code=${result.code}",
                        RaftPlatformLogHint.AuthCritical
                    )
                }
            }
        }
    }
    val latestRealtimeAuthRefreshHandler by rememberUpdatedState<(JsonObject) -> Unit>(
        { eventData -> handleRealtimeAuthRefreshRequest(eventData) }
    )
    val latestResetAppShellSessionState by rememberUpdatedState<() -> Unit>({ resetAppShellSessionState() })
    AppShellRealtimeSinkAndNetworkEffects(
        sharedStores = sharedStores,
        bridge = bridge,
        appShellServerMembersStore = appShellServerMembersStore,
        appShellRealtimeDomainEventOwner = appShellRealtimeDomainEventOwner,
        appShellAgentDetailStore = appShellAgentDetailStore,
        activeAppShellCanonicalFinalSinksState = activeAppShellCanonicalFinalSinksState,
        appShellCoroutineScope = appShellCoroutineScope,
        networkBannerGate = networkBannerGate,
        realtimeEvents = realtimeEvents,
        hostNetworkEvents = hostNetworkEvents,
        homeShellStore = homeShellStore,
        latestReadySessionState = latestReadySessionState,
        membersAgentDetailRefreshKeyState = membersAgentDetailRefreshKeyState,
    )

    DisposableEffect(
        realtimeEvents,
        homeStore,
        realtimeEventMapper,
        realtimeCallbackGate,
        authoritativeHomeStartAllowed,
    ) {
        var suppressedVisibleRefreshCount = 0
        // Events arrive as immutable kotlinx JsonObjects, so they are shared with the launched
        // coroutine as-is: no snapshot copy, no Kuikly JSONObject round trip.
        val onRealtimeEvent: (JsonObject) -> Unit = onRealtimeEvent@{ eventData ->
            if (!latestAuthoritativeHomeStartAllowed) return@onRealtimeEvent
            if (
                eventData.optString(RaftRealtimeNotifyContract.KeyAction) ==
                    RaftRealtimeNotifyContract.ActionPresenceTransportConnected
            ) {
                val serverId =
                    eventData
                        .optString(RaftRealtimeNotifyContract.KeyServerId)
                        .trim()
                        .takeIf { it.isNotBlank() }
                val transportEpoch =
                    eventData
                        .optLong(RaftRealtimeNotifyContract.KeyPresenceTransportEpoch, 0L)
                        .takeIf { it > 0L }
                val witness = eventData.realtimeConnectionWitnessOrNull()
                val authority = processRealtimeAuthorityFlow.value
                val shellServerId =
                    selectedServerStore.selectedServerId.value
                        ?: storeScope.initialServerId
                if (
                    serverId != null &&
                    transportEpoch != null &&
                    witness != null &&
                    witness == authority?.witness &&
                    authority?.grant(serverId) != null &&
                    shouldRouteRealtimeEventToShell(shellServerId, serverId)
                ) {
                    val nextCursor =
                        nextAppShellPresenceTransportCursor(
                            previous = appShellPresenceTransportCursors[serverId],
                            connectionFamilyId = witness.connectionFamilyId,
                            transportEpoch = transportEpoch
                        )
                    if (nextCursor != null) {
                        appShellPresenceTransportCursors[serverId] = nextCursor
                        // Native posts this signal synchronously before any
                        // event from the new socket. Reset here, before the
                        // per-event coroutine launch, so a low post-restart
                        // seq cannot race ahead of the rebaseline. The cursor
                        // rejects duplicate or delayed old-epoch signals.
                        appShellAgentDetailStore.dispatch(
                            SharedAgentDetailIntent.ResetAgentActivitySeq
                        )
                    }
                }
                return@onRealtimeEvent
            }
                val appearanceGenerationAtDelivery = latestVisibleAppearanceGeneration
                appShellCoroutineScope.launch {
                    if (
                        eventData.optString(RaftRealtimeNotifyContract.KeyAction) ==
                        RaftRealtimeNotifyContract.ActionAppLifecycle
                    ) {
                        val event = eventData.optString(RaftRealtimeNotifyContract.KeyLifecycleEvent)
                        val foregroundEpoch =
                            eventData
                                .optLong(RaftRealtimeNotifyContract.KeyForegroundEpoch, 0L)
                                .takeIf { it > 0L }
                        if (!appLifecycleEventTargetsShell(
                                eventShellInstanceId = eventData.optString(RaftRealtimeNotifyContract.KeyShellInstanceId),
                                routeShellInstanceId = storeScope.shellInstanceId
                            )
                        ) {
                            return@launch
                        }
                        if (eventData.optString(RaftRealtimeNotifyContract.KeyPageName) != RAFT_APP_SHELL_PAGE) {
                            return@launch
                        }
                        if (
                            event == RaftRealtimeNotifyContract.LifecycleHiddenPause ||
                            event == RaftRealtimeNotifyContract.LifecycleHidden ||
                            event == RaftRealtimeNotifyContract.LifecycleHiddenKeepAlive
                        ) {
                            visibleRefreshGate.onLifecycleHidden(
                                appearanceGeneration = appearanceGenerationAtDelivery,
                                foregroundEpoch = foregroundEpoch
                            )
                            return@launch
                        }
                        val visibleLifecycleEvent =
                            event == RaftRealtimeNotifyContract.LifecycleVisibleResume ||
                                event == RaftRealtimeNotifyContract.LifecycleVisibleShow
                        if (visibleLifecycleEvent) {
                            foregroundEpoch?.let(visibleRefreshGate::observeLifecycleForegroundEpoch)
                            if (
                                raftMultiShellHostEnabled() &&
                                raftProcessRealtimeOwnerGeneration() != realtimeReconciledOwnerGeneration
                            ) {
                                realtimeShellForegroundTick += 1
                            }
                            // Web: the reset notice is read again whenever the window comes back.
                            if (latestReadySession != null) {
                                secondFactorRecoveryStore.dispatch(build.raft.app.data.SharedSecondFactorRecoveryIntent.RefreshIfStale)
                            }
                        }
                        if (visibleLifecycleEvent && !latestPageVisible) {
                            bridge.log(
                                "app_shell_visible_refresh_skip reason=app_lifecycle_$event " +
                                    "cause=page_not_visible tab=$latestSelectedTab " +
                                    "homeDestination=${latestSelectedHomeDestination.orEmpty()}"
                            )
                            suppressedVisibleRefreshCount++
                            return@launch
                        }
                        if (
                            appShellLifecycleCanRefreshVisibleRoute(latestPageVisible, event) &&
                            latestReadySession != null
                        ) {
                            val fgResumeMark = TimeSource.Monotonic.markNow()
                            val plan =
                                appShellForegroundResumePlan(
                                    pageVisible = latestPageVisible,
                                    selectedTab = latestSelectedTab,
                                    selectedHomeDestination = latestSelectedHomeDestination
                                )
                            val refreshedVisibleRoute =
                                if (plan?.refreshVisibleRoute == true) {
                                    refreshVisibleAppShellRoute(
                                        reason = "app_lifecycle_$event",
                                        signal = AppShellVisibleRefreshSignal.Lifecycle,
                                        appearanceGeneration = appearanceGenerationAtDelivery,
                                        foregroundEpoch = foregroundEpoch
                                    )
                                } else {
                                    false
                                }
                            if (refreshedVisibleRoute && plan?.refreshActivityUnreadSummary == true) {
                                latestReadySession
                                    ?.currentServerId
                                    ?.trim()
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { serverId ->
                                        sharedStores.activityUnreadSummaryStore.onForeground(
                                            serverId = ServerId(serverId),
                                            epoch = foregroundEpoch ?: 0L,
                                            reason = "app_shell_$event"
                                        )
                                    }
                            }
                            if (refreshedVisibleRoute && plan?.refreshServerMembers == true) {
                                sharedStores.foregroundReconciler.onForeground("app_shell_$event")
                            }
                            raftLogInfo(
                                "SlockForegroundPerf",
                                "fg_resume event=$event serverId=${eventData.optString(RaftRealtimeNotifyContract.KeyServerId)} epoch=${foregroundEpoch ?: 0L} routeRefreshed=$refreshedVisibleRoute suppressedVisibleRefresh=$suppressedVisibleRefreshCount elapsedMs=${fgResumeMark.elapsedNow().inWholeMilliseconds}"
                            )
                            suppressedVisibleRefreshCount = 0
                        }
                        return@launch
                    }
                    if (eventData.optString("action") == AppShellRealtimeAuthRefreshAction) {
                        latestRealtimeAuthRefreshHandler(eventData)
                        return@launch
                    }
                    val event =
                        decodeSharedShellRealtimeEvent(
                            eventData,
                            realtimeEventMapper,
                            raftLogger("SlockRealtimeDecode")
                        ) ?: return@launch
                    val serverId = resolveSharedShellRealtimeServerId(eventData, event, latestReadySession) ?: return@launch
                    val witness = eventData.realtimeConnectionWitnessOrNull() ?: return@launch
                    val grant = realtimeCallbackGate.resolveExistingGrant(witness, serverId) ?: return@launch
                    // Task #168: the connection that delivered this frame, when the host names it. The durable
                    // canary decides reconnects by it, so frame/signal order across coroutines does not matter.
                    val frameTransportEpoch =
                        eventData
                            .optLong(RaftRealtimeNotifyContract.KeyPresenceTransportEpoch, 0L)
                            .takeIf { it > 0L }
                    val activeSelectedServerId = selectedServerStore.selectedServerId.value
                    val processAccepted =
                        sharedStores.processRealtimeDomainEventOwner().acceptProcessMutation(
                            grant,
                            event,
                            source = "process_realtime",
                            presenceTransportEpoch = frameTransportEpoch
                        )
                    if (!processAccepted) return@launch
                    RealtimeCursorPolicy.appliedSeqForEvent(event)
                        .takeIf { appliedSeq -> appliedSeq > 0L }
                        ?.let { appliedSeq ->
                            bridge.realtimeCursorAdvance(
                                serverId = serverId,
                                appliedSeq = appliedSeq,
                                authorityScopeLeaseId = grant.scopeLeaseId,
                                authorityPrincipalId = grant.principalId,
                                authorityAuthGeneration = grant.authGeneration,
                                authorityConnectionFamilyId = grant.connectionFamilyId,
                            )
                        }
                    if (event is ServerMembershipRemoved && !selectedServerStore.isSelected(serverId)) {
                        bridge.realtimeDisconnect(
                            reason = GeneratedRealtimeContract.DisconnectReason.ServerMembershipRemoved,
                            serverId = serverId,
                            shellInstanceId = "process-realtime-host-owner",
                            explicitClose = true,
                        )
                        bridge.log("shared_realtime_server_membership_removed serverId=$serverId selected=false")
                        authGateViewModel.accept(SharedAuthGateIntent.Restore)
                        return@launch
                    }
                    if (event is ServerDeleted && !selectedServerStore.isSelected(serverId)) {
                        bridge.realtimeDisconnect(
                            reason = GeneratedRealtimeContract.DisconnectReason.ServerDeleted,
                            serverId = serverId,
                            shellInstanceId = "process-realtime-host-owner",
                            explicitClose = true,
                        )
                        bridge.log("shared_realtime_server_deleted serverId=$serverId selected=false")
                        authGateViewModel.accept(SharedAuthGateIntent.Restore)
                        return@launch
                    }
                    val shellGrant = realtimeAuthority?.grant(serverId) ?: return@launch
                    if (!shouldRouteRealtimeEventToShell(activeSelectedServerId, serverId)) {
                        if (shouldProjectBackgroundServerUnread(latestReadySession, activeSelectedServerId, serverId)) {
                            appShellRealtimeDomainEventOwner.acceptHomeUnreadProjection(
                                shellGrant,
                                event,
                                source = "app_shell_background_server",
                                transportEpoch = frameTransportEpoch,
                            )
                        } else {
                            bridge.log(
                                "shared_realtime_event_skip reason=server_mismatch " +
                                    "eventServerId=$serverId shellServerId=${activeSelectedServerId.orEmpty()}"
                            )
                        }
                        return@launch
                    }
                    if (event is ServerMembershipRemoved) {
                        applySelectedServerTerminalRealtimeTransition(
                            grant = grant,
                            eventServerId = event.serverId.value,
                            isSelected = { candidate -> selectedServerStore.isSelected(candidate) },
                            runIfCurrentGrant = sharedStores.realtimeAuthorityProvider::runIfCurrentGrant,
                            disconnectAndReset = {
                                raftLogWarn(
                                    "SlockRealtimeDomain",
                                    "shared_realtime_server_membership_removed serverId=${event.serverId.value}"
                                )
                                bridge.realtimeDisconnect(
                                    reason = "server_membership_removed",
                                    serverId = event.serverId.value,
                                    shellInstanceId = storeScope.shellInstanceId,
                                    explicitClose = true
                                )
                                latestResetAppShellSessionState()
                            },
                            clearSelectedServer = {
                                shellAuthStore.updateSelectedServer(serverId = null, serverSlug = null)
                            },
                            restoreAuthGate = {
                                authGateViewModel.accept(SharedAuthGateIntent.Restore)
                            },
                        )
                        return@launch
                    }
                    if (event is ServerDeleted) {
                        applySelectedServerTerminalRealtimeTransition(
                            grant = grant,
                            eventServerId = event.serverId.value,
                            isSelected = { candidate -> selectedServerStore.isSelected(candidate) },
                            runIfCurrentGrant = sharedStores.realtimeAuthorityProvider::runIfCurrentGrant,
                            disconnectAndReset = {
                                raftLogWarn(
                                    "SlockRealtimeDomain",
                                    "shared_realtime_server_deleted serverId=${event.serverId.value}"
                                )
                                bridge.realtimeDisconnect(
                                    reason = "server_deleted",
                                    serverId = event.serverId.value,
                                    shellInstanceId = storeScope.shellInstanceId,
                                    explicitClose = true
                                )
                                latestResetAppShellSessionState()
                            },
                            clearSelectedServer = {
                                shellAuthStore.updateSelectedServer(serverId = null, serverSlug = null)
                            },
                            restoreAuthGate = {
                                authGateViewModel.accept(SharedAuthGateIntent.Restore)
                            },
                        )
                        return@launch
                    }
                    val projected = appShellRealtimeDomainEventOwner.accept(
                        shellGrant,
                        event,
                        source = "app_shell",
                        transportEpoch = frameTransportEpoch,
                    )
                    if (!projected) return@launch
                    sharedStores.realtimeAuthorityProvider.runIfCurrentGrant(grant) {
                        if (event is RealtimeRefreshRequired && event.scope == RealtimeRefreshScope.Announcements) {
                            // task #226: announcement:new — refetch the latest active
                            // announcement so the modal pops live.
                            latestAnnouncementStore.refresh()
                        }
                    // task #459: `message:new` is a data-sync trigger only — the store
                    // accept above is its whole job. Attention comes from the
                    // server-qualified `notification:push` stream (server PR #4233):
                    // identity is typed on the payload, so there is nothing to defer.
                        if (event is NotificationPushReceived) {
                            if (!sharedStores.isActiveAppShellPager(pager.pagerId)) {
                                return@runIfCurrentGrant
                            }
                            // One banner per notification, however many of the user's sockets delivered it.
                            if (!build.raft.app.data.RaftNotificationPushDedupe.firstForBanner(event.raftDedupeKey())) {
                                return@runIfCurrentGrant
                            }
                            if (latestForegroundMessageBannersEnabled) {
                                val banner =
                                    raftNotificationPushBannerOrNull(
                                    event = event,
                                    currentServerId = activeSelectedServerId,
                                    visibleConversationKey =
                                        appShellVisibleForegroundConversationKey(
                                            selectedTab = latestSelectedTab,
                                            selectedHomeDestination = latestSelectedHomeDestination,
                                            selectedConversationId = latestSelectedHomeConversationId
                                        ),
                                    visibleRouteIdentity = foregroundBannerStore.visibleRouteIdentity(),
                                    visibleRouteIdentities = foregroundBannerStore.visibleRouteIdentities(),
                                    conversationDirectory = latestAppShellConversationDirectory,
                                    onSuppressed = { reason ->
                                        bridge.log(
                                            "notification_push_banner_suppressed reason=$reason " +
                                                "serverId=${event.serverId.value} messageId=${event.messageId.value} kind=${event.kind.name}"
                                        )
                                    }
                                    )
                                        ?.redactedForPushPreview(
                                            mode =
                                                PushPreviewMode.fromWire(pushPreviewModeStore.readPushPreviewModeSync())
                                                    ?: PushPreviewMode.Hidden,
                                            senderName = event.senderName,
                                            mentioned = event.mentioned == true,
                                            strings = latestPushPreviewBannerStrings
                                        )
                                banner?.let {
                                    bridge.log(
                                        "notification_push_banner_shown serverId=${event.serverId.value} " +
                                            "messageId=${event.messageId.value} kind=${event.kind.name}"
                                    )
                                    foregroundBannerStore.show(it)
                                }
                            } else {
                                foregroundBannerStore.dismiss()
                            }
                        }
                    }
                }
            }
        val callbackRef = realtimeEvents.addListener(onRealtimeEvent)
        // task #354: the canonical AppShell ingest also consumes the Android
        // raw-ingest topic — large raw server events are routed only there so
        // they are decoded exactly once. Other platforms never publish on it,
        // and page surfaces must not subscribe to it.
        val rawIngestRef = realtimeEvents.addRawIngestListener(onRealtimeEvent)
        onDispose {
            realtimeEvents.removeListener(callbackRef)
            realtimeEvents.removeListener(rawIngestRef)
        }
    }

    val navRailActive =
        raftAdaptiveNavRailActive(
            desktopModeEnabled = desktopModeOnLargeScreen,
            paneState = shellScope.adaptivePane
        )
    // The wide layout's profile and Artifact columns (RaftWideSideColumns.kt, #189 steps 3-4): they belong to the
    // conversation picked, and reopen as pages when the window leaves the wide layout.
    val sideColumns = rememberRaftWideSideColumns(adaptiveWorkspaceState?.selection?.identity, navRailActive, tab = selectedTab)
    val activitySurfaceSelected =
        retainedAppShellSelectedTab(selectedTab) == "activity" && railInlineSecondaryDestination == null
    ActivityWideArtifactLifecycleEffect(
        state = activityArtifactState,
        wide = navRailActive,
        surfaceSelected = activitySurfaceSelected,
        active = pageVisible && activitySurfaceSelected,
        serverId = readyServerId,
    )
    // stdrc 2026-10-21: Settings is the More tab's root on the phone bar and the pinned rail entry
    // on the wide layout; a selection made for the other layout moves to this layout's root.
    LaunchedEffect(selectedTab, navRailActive) {
        if (appShellSearchTabMovesToRailSearch(selectedTab, navRailActive)) {
            // The rail has no Search tab: a phone Search tab left open when the window widens
            // becomes the rail's inline Search over Home.
            bridge.log("app_shell_search_tab_to_rail from=$selectedTab")
            selectAppShellTab("home", directChildSwitch = true)
            railInlineSecondaryDestination = AppShellRailSearchKey
            return@LaunchedEffect
        }
        val root = appShellSettingsRootTab(selectedTab, navRailActive) ?: return@LaunchedEffect
        bridge.log("app_shell_settings_root from=$selectedTab to=$root navRail=$navRailActive")
        selectAppShellTab(root, directChildSwitch = true)
        retainedAppShellTabs = retainedAppShellTabs + root
    }
    // artin 2026-10-27 ("宽屏还有一个痛点，就是前进后退"): Back / Forward through what the wide layout
    // showed, Slack desktop style (RaftWideNavigationHistory.kt). Recorded from what the shell shows
    // — not from each open call — so row taps, in-pane thread replaces, search / saved / activity
    // picks and member picks all count. Phone single-pane keeps its own back stack: nothing is
    // recorded or restored there.
    val wideNavHistoriesState = remember(storeScope.shellInstanceId) { mutableStateOf(RaftWideNavHistories()) }
    var wideNavHistories by wideNavHistoriesState
    var wideNavMemberOpenRequest by remember(readyServerId) { mutableStateOf<MemberDetailIdentity?>(null) }
    val wideNavShowing =
        if (navRailActive && pageVisible) {
            raftWideNavEntryOf(
                serverId = readyServerId,
                selectedTab = selectedTab,
                railInlineSecondaryDestination = railInlineSecondaryDestination,
                selectedHomeDestination = selectedHomeDestination,
                tasksDetailColumnOpen = tasksDetailColumnOpen,
                conversation = adaptiveWorkspaceState?.selection?.identity,
                conversationRoute = adaptiveDetailLaunchPayload,
                member = membersDetailSelection.value?.identity,
                activityArtifact = activityArtifactState.currentTarget,
            )
        } else {
            null
        }
    val latestWideNavShowingState = rememberUpdatedState(wideNavShowing)
    val latestWideNavShowing by latestWideNavShowingState
    AppShellWideNavShowingEffect(
        wideNavHistoriesState = wideNavHistoriesState,
        wideNavShowing = wideNavShowing,
    )
    // What each rail tab had open (RaftRailPlaces.kt): a rail click lands back there, and only the
    // conversation Chat shows becomes its server's last conversation.
    val railPlaces =
        rememberAppShellRailPlaces(
            shellInstanceId = storeScope.shellInstanceId,
            adaptiveWorkspaceStore = adaptiveWorkspaceStore,
            homeShellStore = homeShellStore,
            bridge = bridge,
            wideNavShowing = wideNavShowing,
            defaultLandingServerId = adaptiveDefaultLandingServerId,
            adaptiveWorkspaceState = adaptiveWorkspaceState,
            navRailActive = navRailActive,
            serverLastConversationRepository = serverLastConversationRepository,
            serverLastConversationOwnerKey = serverLastConversationOwnerKey,
        )
    val railSelectionOpenedOn = rememberRaftSelectionOpenedOn(adaptiveWorkspaceState?.selection, wideNavShowing?.surface)
    // What was open beside each conversation (AppShellBesideMemoryEffects.kt): remembered, and restored on entering it.
    AppShellBesideMemoryEffect(
        wideNavShowing = wideNavShowing,
        accountId = readySession?.user?.id,
        columns = sideColumns,
        adaptiveWorkspaceStore = adaptiveWorkspaceStore,
        desktopModeOnLargeScreen = desktopModeOnLargeScreen,
        navigationStoreScope = navigationStoreScope,
        sharedStores = sharedStores,
        serverMembersStore = appShellServerMembersStore,
        memberRepository = memberRepository,
        conversationRepository = conversationRepository,
        agentsApiClient = agentsApiClient,
        bridge = bridge,
    )
    val wideNavHistory = wideNavHistories.of(readyServerId)
    AppShellWideNavRestoreEffect(
        bridge = bridge,
        readyServerId = readyServerId,
        wideNavHistoriesState = wideNavHistoriesState,
        latestWideNavShowingState = latestWideNavShowingState,
        wideNavHistory = wideNavHistory,
    )

    val appShellFocusManager = LocalFocusManager.current
    val appShellKeyboardController = LocalSoftwareKeyboardController.current
    val dismissAppShellKeyboard: () -> Unit = {
        appShellFocusManager.clearFocus(force = true)
        appShellKeyboardController?.hide()
    }

    /** Reopens [entry] through the normal open paths (identity only; the stores load the content). */
    fun restoreWideNavEntry(entry: RaftWideNavEntry) {
        if (entry.surface != AppShellRailSearchKey) {
            dismissAppShellKeyboard()
        }
        serverMenuState.dismiss()
        if (
            entry.surface == AppShellRailSearchKey || entry.surface == AppShellRailSavedKey || entry.surface == AppShellRailDraftsKey ||
                appShellRailInlinePageRailId(entry.surface) != null
        ) {
            railInlineSecondaryDestination = entry.surface
        } else {
            railInlineSecondaryDestination = null
            if (retainedAppShellSelectedTab(selectedTab) != entry.surface) {
                // As a rail tap selects a tab (onAppShellTabSelected).
                selectAppShellTab(entry.surface, directChildSwitch = true, source = AppShellTabSwitchSource.Back)
                if (isRetainedAppShellTab(entry.surface)) retainedAppShellTabs = retainedAppShellTabs + entry.surface
            }
            // A Home entry is the Home list, never a Home secondary page.
            if (entry.surface == "home") homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
        }
        when (val detail = entry.detail) {
            is RaftWideNavDetail.Conversation -> {
                activityArtifactState.close()
                openLaunchPayloadInAdaptiveDetail(detail.route)
                val session = adaptiveWorkspaceStore.snapshot()
                if (session.workspace?.selection?.identity?.key != detail.identity.key) {
                    bridge.logWarn("wide_history_restore_open_refused detail=$detail")
                    return
                }
                if (entry.surface == "tasks") {
                    tasksDetailColumnActive = true
                    tasksDetailColumnTaskId = tasksDetailColumnTaskIds.restored(detail.identity.key, tasksDetailColumnTaskId)
                }
                homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(session.detailListSelectionId()))
            }
            is RaftWideNavDetail.Artifact -> {
                openActivityWideArtifactInShell(
                    state = activityArtifactState,
                    target = detail.target,
                    scopeOverride = null,
                    fallbackScope = navigationStoreScope,
                    conversationId = null,
                    sharedStores = sharedStores,
                    adaptiveWorkspaceStore = adaptiveWorkspaceStore,
                    releaseLease = ::releaseAdaptiveHandoffLease,
                    clearSelectedConversation = {
                        homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(null))
                    },
                )
            }
            is RaftWideNavDetail.Member -> wideNavMemberOpenRequest = detail.identity
            null ->
                when (entry.surface) {
                    "members" -> membersDetailSelection.value = null
                    "tasks" -> {
                        tasksDetailColumnActive = false
                        // As Close: going forward again reopens the task beside the list, not expanded.
                        tasksDetailColumnExpanded = false
                        RaftRatingPrompt.onTaskListShown()
                    }
                    "settings" -> Unit
                    else -> {
                        activityArtifactState.close()
                        val selection = adaptiveWorkspaceStore.snapshot().workspace?.selection ?: return
                        adaptiveWorkspaceStore.update { it.closeDetailCompletely() }
                        releaseAdaptiveHandoffLease(selection.generation)
                        homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(null))
                    }
                }
        }
    }

    fun navigateWideHistory(direction: RaftWideNavDirection, source: String) {
        val serverId = readyServerId ?: return
        if (!navRailActive) return
        if (direction == RaftWideNavDirection.Back && sideColumns.back(tasksDetailColumnOpen, source) { bridge.log(it) }) return
        val before = wideNavHistories.of(serverId)
        val after = before.go(direction)
        val target = after.current?.takeIf { after != before } ?: return
        wideNavHistories = wideNavHistories.go(serverId, direction)
        bridge.log(
            "wide_history_${direction.wireName} source=$source index=${after.index}/${after.entries.size} " +
                "surface=${target.surface} detail=${target.detail}"
        )
        restoreWideNavEntry(target)
    }
    LaunchedEffect(wideHistoryRequest) {
        val request = wideHistoryRequest ?: return@LaunchedEffect
        navigateWideHistory(request.direction, source = "host")
    }
    val showAppShellBottomTabs =
        appShellBottomTabsVisible(
            selectedTab = selectedTab,
            selectedHomeDestination = selectedHomeDestination,
            navRailActive = navRailActive,
        ) && !navRailActive &&
            !appShellAdaptiveDetailCoversTabRoot(
                desktopModeOnLargeScreen = desktopModeOnLargeScreen,
                selectedTab = selectedTab,
                presentation = adaptiveWorkspaceState?.presentation,
            )
    // Every navigation surface's labels come from its destinations (RaftAppDestinations.kt).
    val phoneNavLabels = raftNavLabels(RaftNavPresentation.PhoneBar)
    val railNavLabels = raftNavLabels(RaftNavPresentation.Rail)
    val homeTabLabel = phoneNavLabels.getValue(RaftAppDestination.Chat.id)
    val tasksTabLabel = phoneNavLabels.getValue(RaftAppDestination.Tasks.id)
    val settingsTabLabel = stringResource(RaftAppDestination.Settings.label)
    val dmsTabLabel = stringResource(Res.string.tab_dms)
    val activityTabLabel = phoneNavLabels.getValue(RaftAppDestination.Activity.id)
    val searchTabLabel = phoneNavLabels.getValue(RaftAppDestination.Search.id)
    val moreTabLabel = phoneNavLabels.getValue(RaftAppDestination.Server.id)
    // Activity tab badge = Activity unread only (the count the old Home 动态 row showed). The
    // notification center keeps its own Bell dot in the Home head (task #18).
    val activityTabUnreadCount = homeActivitySidebarUnreadCount(activityUnreadSummary.totalUnreadCount)
    // Computers attention (raft-design #620 "Attention dots"): the dot marks a (computer, state)
    // you have not seen; opening Computers sees it. Seen pairs persist per user + server.
    val computerAttentionSeenStore = remember {
        runCatching {
            build.raft.app.data.KeyValueRaftComputerAttentionSeenStore(build.raft.app.data.raftSharedPreferenceStore())
        }.getOrNull()
    }
    val computerAttentionUserId = readySession?.user?.id?.trim()?.takeIf(String::isNotEmpty)
    val computerAttentionServerId = readyServerId
    val currentComputerAttention = remember(homeState.notifications) {
        build.raft.app.data.raftComputerAttentionPairs(homeState.notifications.map { it.id })
    }
    val computerAttentionSeenState = remember(computerAttentionSeenStore, computerAttentionUserId, computerAttentionServerId) {
        mutableStateOf(
            if (computerAttentionUserId != null && computerAttentionServerId != null) {
                computerAttentionSeenStore?.read(computerAttentionUserId, computerAttentionServerId).orEmpty()
            } else {
                emptySet()
            }
        )
    }
    var computerAttentionSeen by computerAttentionSeenState
    val writeComputerAttentionSeen: (Set<String>) -> Unit = { next ->
        if (next != computerAttentionSeen) {
            computerAttentionSeen = next
            if (computerAttentionUserId != null && computerAttentionServerId != null) {
                computerAttentionSeenStore?.write(computerAttentionUserId, computerAttentionServerId, next)
            }
        }
    }
    // A computer observed leaving a state forgets it was seen there, so re-entering lights again.
    AppShellComputerAttentionEffects(
        computerAttentionUserId = computerAttentionUserId,
        computerAttentionServerId = computerAttentionServerId,
        currentComputerAttention = currentComputerAttention,
        computerAttentionSeenState = computerAttentionSeenState,
        writeComputerAttentionSeen = writeComputerAttentionSeen,
    )
    // Every way into Computers (the More/Settings row, Settings > Computers on the wide rail, a
    // Home notice).
    val openComputersPageWith: (attention: Boolean) -> Unit = { attention ->
        writeComputerAttentionSeen(
            build.raft.app.data.raftComputerAttentionMarkSeen(computerAttentionSeen, currentComputerAttention)
        )
        pager.openSettingsComputersPage(storeScope, attention = attention)
    }
    val openComputersPage: () -> Unit = { openComputersPageWith(false) }
    val appPermissionUpdatesRole =
        (homeState.content as? SharedHomeContentState.Content)
            ?.takeIf { it.snapshot.server.id.value == readyServerId }
            ?.snapshot
            ?.server
            ?.role
            ?: readySession?.servers?.firstOrNull { it.id == readyServerId }?.role
            ?: readySession?.currentServerSummary()?.role
    val canManageIntegrations = build.raft.app.model.serverRoleCanManageIntegrations(appPermissionUpdatesRole)
    AppShellAppPermissionUpdatesEffect(
        appShellRealtimeDomainEventOwner = appShellRealtimeDomainEventOwner,
        store = appPermissionUpdatesStore,
        serverId = readyServerId,
        canManageIntegrations = canManageIntegrations,
        pageVisibleTick = pageVisibleTick,
    )
    val appPermissionUpdatesState by appPermissionUpdatesStore.state.collectAsState()
    val appsNeedsReview =
        canManageIntegrations &&
            readyServerId != null &&
            appPermissionUpdatesState.serverId == readyServerId &&
            appPermissionUpdatesState.needsAdminReview
    // Something new in More / Settings (app update -> About; an offline/outdated machine not yet
    // seen -> Computers; a feedback reply not read yet -> Feedback; pending App admin review -> Apps);
    // it dots the rail/panel entries and the matching Settings rows from this one set.
    val feedbackUnreadTotal by sharedStores.feedbackUnread.unreadTotal.collectAsState()
    val moreAttention = remember(homeState.notifications, computerAttentionSeen, feedbackUnreadTotal, appsNeedsReview) {
        raftMoreAttention(
            homeNotificationIds = homeState.notifications.map { it.id },
            computersSeen = computerAttentionSeen,
            feedbackUnreadTotal = feedbackUnreadTotal,
            appsNeedsReview = appsNeedsReview,
        )
    }
    // D1a: the current-server tab and its menu, from the Home switcher's own items.
    val serverMenuContent = homeState.content as? SharedHomeContentState.Content
    val serverMenuModel =
        rememberAppShellServerMenuModel(
            serverMenuContent = serverMenuContent,
            homeState = homeState,
            serverActivityExpectedOwner = serverActivityExpectedOwner,
            moreAttention = moreAttention,
        )
    // The menu belongs to the bar or the rail it rose from: it closes when they go (a pushed page
    // hides the bar, the window crosses the rail breakpoint).
    AppShellServerMenuDismissEffect(
        serverMenuState = serverMenuState,
        navRailActive = navRailActive,
        showAppShellBottomTabs = showAppShellBottomTabs,
    )
    // web `useArtifactsRailShown`: the Artifacts entries (server panel row, wide rail tab) only where the current server's
    // workspace_artifacts_v0 is already loaded and on (the panel never asks for flags itself).
    val artifactsFlagRevision by sharedStores.serverFeatureFlags.snapshotRevision.collectAsState()
    val serverMenuArtifactsShown =
        remember(serverMenuModel?.currentServer?.id, artifactsFlagRevision) {
            appShellArtifactsShown(sharedStores.serverFeatureFlags, serverMenuModel?.currentServer?.id)
        }
    // web LeftRail `useRememberedAssistantShown`: the rail's Assistant, by the phone Home row's rule.
    val railAssistantShown =
        rememberAppShellRailAssistantShown(
            navRailActive = navRailActive,
            sharedStores = sharedStores,
            storeScope = storeScope,
            session = readySession,
            serverId = serverMenuModel?.currentServer?.id,
            railInlineSecondaryDestinationState = railInlineSecondaryDestinationState,
            wideNavHistoriesState = wideNavHistoriesState,
        )
    // Artifacts off for this server (or the flag turned off): its inline page closes and the wide
    // history's arrows never reopen it (review L1).
    AppShellServerMenuEffects(
        pageVisibleTick = pageVisibleTick,
        selectedTabState = selectedTabState,
        serverMenuState = serverMenuState,
        serverPageOriginState = serverPageOriginState,
        serverTabFocusPendingState = serverTabFocusPendingState,
        serverTabFocusOnPageReturnState = serverTabFocusOnPageReturnState,
        railInlineSecondaryDestinationState = railInlineSecondaryDestinationState,
        bridge = bridge,
        authDataSource = authDataSource,
        serverDrawerProgrammaticCloseFenceState = serverDrawerProgrammaticCloseFenceState,
        homeShellStore = homeShellStore,
        homeState = homeState,
        readyServerId = readyServerId,
        wideNavHistoriesState = wideNavHistoriesState,
        serverMenuArtifactsShown = serverMenuArtifactsShown,
    )
    val serverTabAccessibilityLabel =
        serverMenuModel?.let { stringResource(Res.string.server_tab_a11y_label, it.currentServer.name) }
    val appShellBottomTabItems = remember(
        selectedTab,
        homeTabLabel,
        tasksTabLabel,
        activityTabLabel,
        searchTabLabel,
        moreTabLabel,
        activityTabUnreadCount,
        moreAttention,
        serverMenuModel,
        serverTabAccessibilityLabel
    ) {
        appShellPhoneBottomTabs(
            selectedTab = selectedTab,
            homeLabel = homeTabLabel,
            activityLabel = activityTabLabel,
            searchLabel = searchTabLabel,
            tasksLabel = tasksTabLabel,
            moreLabel = moreTabLabel,
            activityUnreadCount = activityTabUnreadCount,
            moreAttention = moreAttention,
            serverTab = serverMenuModel,
            serverTabAccessibilityLabel = serverTabAccessibilityLabel
        )
    }
    // The wide rail keeps the web LeftRail entries (Direction A, #proj-mobile 10-30): Chat,
    // Activity, Tasks, Members, Computers, Apps; Members is a rail root there.
    val appShellRailTabItems = remember(selectedTab, railInlineSecondaryDestination, railNavLabels, activityTabUnreadCount, moreAttention, serverMenuArtifactsShown, railAssistantShown) {
        appShellRailTabs(
            artifacts = serverMenuArtifactsShown,
            assistant = railAssistantShown,
            selectedTab = selectedTab,
            inlinePageRailId = appShellRailInlinePageRailId(railInlineSecondaryDestination),
            activityUnreadCount = activityTabUnreadCount,
            moreAttention = moreAttention,
            labelFor = { key -> railNavLabels[key] ?: key },
        )
    }
    // The rail's tabs in the person's own order, saved on the account (web SortableRailTabs,
    // slock #10279): the session's `auth/me` seeds it, a drop saves it.
    val railTabOrderStore = sharedStores.railTabOrderStore
    val railTabOrderStateState = railTabOrderStore.state.collectAsState()
    val railTabOrderState by railTabOrderStateState
    AppShellRailTabOrderEffects(
        bridge = bridge,
        readySession = readySession,
        navRailActive = navRailActive,
        railTabOrderStore = railTabOrderStore,
        railTabOrderStateState = railTabOrderStateState,
    )
    val railTabReorder =
        remember(railTabOrderStore) {
            RaftRailTabReorder { keys ->
                railTabOrderStore.dispatch(
                    build.raft.app.data.SharedRailTabOrderIntent.Reorder(
                        build.raft.app.data.raftRailTabOrderWithVisibleOrder(
                            fullOrder = railTabOrderStore.state.value.order,
                            visibleOrder = keys.mapNotNull(::raftRailTabIdForKey),
                        )
                    )
                )
            }
        }
    // The rail's Help menu (web LeftRail HelpMenu), beside the rail.
    val railHelpOpenState = remember { mutableStateOf(false) }
    var railHelpOpen by railHelpOpenState
    // It belongs to the rail: it closes when the window leaves the wide layout, and when the
    // server panel or the bell opens.
    AppShellRailHelpEffects(
        serverMenuState = serverMenuState,
        navRailActive = navRailActive,
        railHelpOpenState = railHelpOpenState,
    )
    val appShellBottomContentInset = shellScope.contentBottomInset(showAppShellBottomTabs)
    AppShellChildPagePrewarmEffects(
        pageVisibleTick = pageVisibleTick,
        pager = pager,
        selectedTabState = selectedTabState,
        retainedAppShellTabsState = retainedAppShellTabsState,
        prewarmedAppShellTabsState = prewarmedAppShellTabsState,
        prewarmAdmissionGate = prewarmAdmissionGate,
        childPagePrewarmPolicy = childPagePrewarmPolicy,
        childTabShellId = childTabShellId,
        bridge = bridge,
        appShellComputersStore = appShellComputersStore,
        homeState = homeState,
        readySession = readySession,
        readyServerId = readyServerId,
        slackNavTabKeys = slackNavTabKeys,
        slackNavMode = slackNavMode,
        authoritativeHomeStartAllowed = authoritativeHomeStartAllowed,
    )
    // MEMBERS_COMPUTERS_IDLE_PREWARM_END

    val appShellHomeLabels = RaftHomeShellLabels.localized()
    val serverDrawerUserHeader = rememberRaftServerDrawerUserHeader(readySession)
    // "Open workspaces in separate app windows": another server opens in (or brings to front) its
    // own window, the way the Choose workspace page does; false means switch in place.
    fun openServerInSeparateWindowIfEnabled(serverId: String, source: String, keepTab: String? = null): Boolean {
        val session = readySession ?: return false
        // The server this window shows now (the shell's selected server, else what Home renders,
        // else the session's) — never the launch-time scope server.
        val currentServerId =
            appShellNavigationServerId(
                selectedServerId = selectedServerId,
                renderedHomeServerId = renderedHomeServerId,
                scopeInitialServerId = session.currentServerId
            )
        val pageData =
            appShellServerSwitchNewWindowPageDataOrNull(
                sourceStoreScope = navigationStoreScope,
                currentServerId = currentServerId,
                sourceDocumentShell = pager.pageData.params.optBoolean(RAFT_DOCUMENT_SHELL, false),
                selectedServerId = serverId,
                multiShellHostEnabled = raftMultiShellHostEnabled(),
                routeOwner =
                    AuthenticatedRouteOwner(
                        environmentId = sharedStores.apiEnvironmentStore.current().apiBaseUrl,
                        accountId = session.user.id,
                        serverId = serverId
                    ).normalizedOrNull(),
                keepTab = keepTab,
                sourceWindowServerId = pager.pageData.params.authenticatedRouteOwnerOrNull()?.serverId,
                windowShowingPickedServer =
                    raftProcessRealtimeSessionDemand.shellShowing(
                        serverId = serverId,
                        exceptShellInstanceId = navigationStoreScope.shellInstanceId
                    )
            ) ?: return false
        pageData.put(RAFT_SERVER_SWITCH_TAP_EPOCH_MILLIS, build.raft.app.data.raftNowEpochMillis())
        bridge.log(
            "app_shell_server_switch_new_window source=$source " +
                "from=${currentServerId.orEmpty()} to=${serverId.trim()} tab=${keepTab.orEmpty()}"
        )
        openAppShellServerSwitchWindow(
            serverId = serverId,
            pageData = pageData,
            noteServerSelected = sharedStores::noteServerSwitchedToWindow,
            open = pager::openAppShellReplacingCurrentPage
        )
        return true
    }

    fun openServerDrawerHome(serverIdValue: String) {
        val normalizedServerId = serverIdValue.trim().takeIf { it.isNotBlank() } ?: return
        val session = latestReadySession
        if (session == null || session.servers.none { it.id == normalizedServerId }) {
            bridge.logWarn(
                "server_drawer_home_open_rejected serverId=$normalizedServerId reason=stale_projection"
            )
            return
        }
        if (openServerInSeparateWindowIfEnabled(normalizedServerId, source = "server_drawer")) {
            serverDrawerProgrammaticCloseFence?.invoke("server_drawer_new_window")
            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.DismissServerSwitcher))
            return
        }
        selectAppShellTab("home")
        retainedAppShellTabs = retainedAppShellTabs + "home"
        homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
        homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.SelectServer(normalizedServerId)))
    }

    fun openServerDrawerConversation(serverIdValue: String, conversationId: String) {
        val normalizedServerId = serverIdValue.trim().takeIf { it.isNotBlank() } ?: return
        val normalizedConversationId = conversationId.trim().takeIf { it.isNotBlank() } ?: return
        appShellCoroutineScope.launch {
            val serverId = ServerId(normalizedServerId)
            val drawerSection = homeShellStore.state.value.home.serverDrawer.sections[serverId]
            val conversation =
                drawerSection?.content?.snapshot?.let { it.channels + it.directMessages }
                    ?.firstOrNull { it.threadId.value == normalizedConversationId }
            val selectedSession = latestReadySession?.withAppShellSelectedServer(normalizedServerId)
            if (drawerSection == null || conversation == null || selectedSession == null) {
                bridge.logWarn(
                    "server_drawer_open_rejected serverId=$normalizedServerId " +
                        "conversationId=$normalizedConversationId reason=stale_projection"
                )
                bridge.toast(conversationUnavailableMessage)
                return@launch
            }
            if (selectedServerStore.selectedServerId.value != normalizedServerId) {
                shellAuthStore.updateSelectedServer(normalizedServerId, drawerSection.server.slug)
                authGateViewModel.dispatch(SharedAuthGateIntent.ServerReady(selectedSession))
                homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.SelectServer(normalizedServerId)))
            }
            val targetStoreScope =
                RaftShellStoreScope.create(
                    shellInstanceId = storeScope.shellInstanceId,
                    initialServerId = normalizedServerId
                )
            val identity = conversation.toThreadLaunchConversationIdentity()
            val payload =
                buildStandaloneConversationThreadLaunchPayload(
                    conversation = identity,
                    timeContext = threadTimeContext,
                    alignOwnMessagesRight = myMessageAlignment == MyMessageAlignmentPreference.Right,
                    compactConsecutiveMessages = compactConsecutiveMessages,
                            onWarn = bridge::logWarn
                ) ?: return@launch
            homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(identity.threadId.value))
            conversationForwardHistory.recordNewOpen(
                identity.toConversationForwardEntry(normalizedServerId)
            )
            bridge.setConversationForwardGestureEnabled(false)
            pager.openStandaloneThreadLaunchPayload(payload, targetStoreScope)
            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.DismissServerSwitcher))
        }
    }

    suspend fun openExternalShareComposer(
        handoff: build.raft.app.data.ExternalShareComposerHandoff
    ): Boolean {
        val target = handoff.target
        val normalizedServerId = target.serverId.value.trim().takeIf { it.isNotBlank() }
        val targetLookupChannelId =
            if (target.channelKind == ConversationKind.Thread) {
                target.parentChannelId?.value?.trim()?.takeIf { it.isNotBlank() }
            } else {
                target.channelId?.value?.trim()?.takeIf { it.isNotBlank() }
            }
        val resolvedJointChannelId =
            target.jointChannelId?.trim()?.takeIf { it.isNotBlank() }
                ?: normalizedServerId?.let { serverKey ->
                    targetLookupChannelId?.let { channelKey ->
                        homeShellStore.state.value.home.serverDrawer.sections[ServerId(serverKey)]
                            ?.content
                            ?.snapshot
                            ?.channels
                            ?.firstOrNull { it.channelId.value == channelKey }
                            ?.jointChannelId
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                    }
                }
                ?: targetLookupChannelId
                    ?.let(latestAppShellConversationDirectory::resolve)
                    ?.takeIf { projection ->
                        projection.surfaceKind != RaftThreadSurfaceKind.ReplyThread &&
                            (normalizedServerId == null || projection.serverId == normalizedServerId || projection.serverId.isBlank())
                    }
                    ?.jointChannelId
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
        val identity = target.toExternalShareConversationIdentityOrNull(resolvedJointChannelId = resolvedJointChannelId)
        if (normalizedServerId == null || identity == null) {
            bridge.logWarn("external_share_composer_open_rejected reason=destination_identity_missing")
            bridge.toast(conversationUnavailableMessage)
            return false
        }
        val session = latestReadySession
        val selectedServer = session?.servers?.firstOrNull { it.id == normalizedServerId }
        val selectedSession = session?.withAppShellSelectedServer(normalizedServerId)
        if (
            session == null ||
            session.user.id != handoff.accountId ||
            selectedServer == null ||
            selectedSession == null ||
            normalizedServerId != handoff.serverId
        ) {
            bridge.logWarn(
                "external_share_composer_open_rejected serverId=$normalizedServerId " +
                    "conversationId=${identity.threadId.value} reason=authority_changed"
            )
            bridge.toast(conversationUnavailableMessage)
            return false
        }
        val payload =
            buildStandaloneConversationThreadLaunchPayload(
                conversation = identity,
                timeContext = threadTimeContext,
                alignOwnMessagesRight = myMessageAlignment == MyMessageAlignmentPreference.Right,
                compactConsecutiveMessages = compactConsecutiveMessages,
                originFallback = target.toExternalShareOriginFallbackOrNull(),
                onWarn = bridge::logWarn
            ) ?: run {
                bridge.toast(conversationUnavailableMessage)
                return false
            }
        val attachments =
            handoff.envelope.items.mapIndexed { index, item ->
                SharedThreadComposerAttachment(
                    id = "external-share-${handoff.envelope.token}-$index",
                    fileName = item.displayName,
                    sizeBytes = item.sizeBytes,
                    mimeType = item.mimeType.takeIf(String::isNotBlank),
                    previewUri = item.localRef,
                    status = "queued",
                    fileRef = item.localRef,
                    source = "external_share",
                    externalShareToken = handoff.envelope.token,
                    externalShareAccountId = handoff.accountId,
                    externalShareServerId = handoff.serverId
                )
            }
        val stageResult =
            composerDraftPersistSlice.stageExternalShareDraft(
                draftKey = payload.threadComposerDraftKey(),
                text = handoff.envelope.externalShareComposerText(),
                attachments = attachments,
                source =
                    SharedThreadComposerExternalShareSource(
                        token = handoff.envelope.token,
                        accountId = handoff.accountId,
                        serverId = handoff.serverId
                    )
            )
        if (stageResult !is ExternalShareComposerDraftStageResult.Staged) {
            val code = (stageResult as ExternalShareComposerDraftStageResult.Rejected).code
            bridge.logWarn("external_share_composer_stage_rejected code=$code")
            bridge.toast(conversationUnavailableMessage)
            return false
        }
        if (selectedServerStore.selectedServerId.value != normalizedServerId) {
            shellAuthStore.updateSelectedServer(normalizedServerId, selectedServer.slug)
            authGateViewModel.dispatch(SharedAuthGateIntent.ServerReady(selectedSession))
            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.SelectServer(normalizedServerId)))
        }
        selectAppShellTab("home")
        retainedAppShellTabs = retainedAppShellTabs + "home"
        homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
        homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(identity.threadId.value))
        val targetStoreScope =
            RaftShellStoreScope.create(
                shellInstanceId = storeScope.shellInstanceId,
                initialServerId = normalizedServerId
            )
        pager.openStandaloneThreadLaunchPayload(payload, targetStoreScope)
        bridge.log(
            "external_share_composer_opened serverId=$normalizedServerId " +
                "threadId=${identity.threadId.value} kind=${identity.kind.name} " +
                "attachments=${attachments.size} upload=0 send=0"
        )
        return true
    }

    val onAppShellTabSelected: (String) -> Unit = onBottomTabSelected@{
                prewarmAdmissionGate.invalidate("bottom_tab_tap:$it")
                val nextTab = retainedAppShellSelectedTab(it)
                when (appShellBottomTabTapAction(selectedTab = selectedTab, tappedTab = nextTab)) {
                    AppShellBottomTabTapAction.ScrollCurrentToTop -> {
                        tabScrollToTopRequests = tabScrollToTopRequests.request(nextTab)
                        return@onBottomTabSelected
                    }
                    AppShellBottomTabTapAction.FocusSearchField -> {
                        searchTabFocusInputRequest += 1
                        return@onBottomTabSelected
                    }
                    AppShellBottomTabTapAction.Ignore -> return@onBottomTabSelected
                    AppShellBottomTabTapAction.Select -> Unit
                }
                if (!shouldHandleAppShellTabSelection(selectedTab, nextTab)) return@onBottomTabSelected
                if (shouldDismissKeyboardOnAppShellTabSwitch(nextTab)) {
                    dismissAppShellKeyboard()
                }
                // Selecting a real rail tab dismisses an inline secondary destination
                // (Search) — its middle-pane slot goes back to the tab's own content.
                railInlineSecondaryDestination = null
                val visitedBefore = nextTab in retainedAppShellTabs
                bridge.performanceMarker(
                    phase = appShellBottomTabPerformancePhase(nextTab),
                    routeName = appShellBottomTabPerformanceRoute(nextTab),
                    event = "instant",
                    metadata =
                        mapOf(
                            "from" to retainedAppShellSelectedTab(selectedTab),
                            "to" to nextTab,
                            "primaryTab" to isRetainedAppShellTab(nextTab).toString(),
                            "visitedBefore" to visitedBefore.toString()
                        )
                )
                if (shouldDismissHomeNotificationCenterForAppShellTabSelection(selectedTab, nextTab)) {
                    homeFocusLossDismissEpoch += 1
                    serverDrawerProgrammaticCloseFence?.invoke("bottom_tab")
                    homeShellStore.dispatch(
                        SharedHomeShellIntent.Home(SharedHomeIntent.DismissServerSwitcher)
                    )
                }
                selectAppShellTab(nextTab, directChildSwitch = true, source = AppShellTabSwitchSource.User)
                if (isRetainedAppShellTab(nextTab)) {
                    retainedAppShellTabs = retainedAppShellTabs + nextTab
                }
                if (nextTab == "home") {
                    homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
                }
            }

    // D1a: picking a server in the server menu switches like the Home switcher does (session
    // projection + SelectServer) and keeps the tab you are on.
    fun switchServerFromServerMenu(serverId: String) {
        serverMenuState.dismiss()
        val current = serverMenuModel?.currentServer?.id.orEmpty()
        val plan = appShellServerMenuSwitchPlan(current, serverId, selectedTab) ?: return
        if (openServerInSeparateWindowIfEnabled(plan.serverId, source = "server_menu", keepTab = plan.tab)) return
        val homeServerId = appShellHomeListServerId(renderedServerId = renderedHomeServerId, readyServerId = readyServerId)
        pendingHomeServerSwitchId = plan.serverId.takeIf { it != homeServerId }
        observedHomeServerSwitchId = null
        bridge.performanceMarker(
            phase = "home_server_switch_select",
            routeName = "kuikly_home",
            event = "instant",
            metadata = mapOf("from" to current, "to" to plan.serverId, "source" to "server_menu", "tab" to plan.tab)
        )
        readySession
            ?.withAppShellSelectedServer(plan.serverId)
            ?.let { session -> authGateViewModel.dispatch(SharedAuthGateIntent.ServerReady(session)) }
        homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(null))
        homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.SelectServer(plan.serverId)))
        if (selectedTab != plan.tab) selectAppShellTab(plan.tab, directChildSwitch = true)
        bridge.log("app_shell_server_menu_switch to=${plan.serverId} tab=${plan.tab}")
    }

    LaunchedEffect(pendingNotificationActivityServerId, readySession?.currentServerId) {
        val target = pendingNotificationActivityServerId ?: return@LaunchedEffect
        val session = readySession ?: return@LaunchedEffect
        pendingNotificationActivityServerId = null
        if (session.servers.none { it.id == target }) {
            bridge.logWarn("notification_open_activity_drop reason=unknown_server targetServerId=$target")
            return@LaunchedEffect
        }
        if (session.currentServerId != target) {
            session.withAppShellSelectedServer(target)
                ?.let { switched -> authGateViewModel.dispatch(SharedAuthGateIntent.ServerReady(switched)) }
            homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(null))
            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.SelectServer(target)))
        }
        selectAppShellTab("activity", directChildSwitch = true)
        bridge.log("notification_open_activity_done targetServerId=$target")
    }

    LaunchedEffect(pendingNotificationAppTarget, readySession?.currentServerId) {
        val target = pendingNotificationAppTarget ?: return@LaunchedEffect
        val session = readySession ?: return@LaunchedEffect
        if (session.servers.none { it.id == target.serverId }) {
            pendingNotificationAppTarget = null
            bridge.logWarn("notification_open_app_drop reason=unknown_server targetServerId=${target.serverId}")
            return@LaunchedEffect
        }
        if (session.currentServerId != target.serverId) {
            val switched = session.withAppShellSelectedServer(target.serverId)
            if (switched == null) {
                pendingNotificationAppTarget = null
                bridge.logWarn("notification_open_app_drop reason=server_switch_unavailable targetServerId=${target.serverId}")
                return@LaunchedEffect
            }
            authGateViewModel.dispatch(SharedAuthGateIntent.ServerReady(switched))
            homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(null))
            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.SelectServer(target.serverId)))
            return@LaunchedEffect
        }
        pendingNotificationAppTarget = null
        serverMenuState.dismiss()
        pager.openSettingsMarketplacePage(storeScope = storeScope, installedAppId = target.appId)
        bridge.log("notification_open_app_done targetServerId=${target.serverId} appId=${target.appId}")
    }

    // D1a: the server panel's pages open the pages the More rows opened, as pushed pages that
    // belong to no tab; Back returns to the page that showed when the panel opened them.
    fun openServerMenuEntry(entry: RaftServerMenuEntry) {
        serverMenuState.dismiss()
        prewarmAdmissionGate.invalidate("server_menu_open:${entry.key}")
        bridge.log("app_shell_server_menu_open row=${entry.key} from=$selectedTab")
        if (!navRailActive) serverPageOrigin = appShellServerPageOrigin(selectedTab, serverPageOrigin)
        when (entry) {
            RaftServerMenuEntry.Artifacts -> {
                // web MobileServerTab `openArtifacts`: the page pushed over the shell.
                if (!navRailActive) serverTabFocusOnPageReturn = true
                pager.openArtifactsHomePage(storeScope)
            }
            RaftServerMenuEntry.Members -> {
                RaftProductEvents.navEntrySelected(
                    destination = RaftNavDestination.Members,
                    surface = RaftNavSurface.MobileMore,
                    wasActive = retainedAppShellSelectedTab(selectedTab) == "members",
                )
                if (appShellServerPagePushes(stackShellTabs != null, navRailActive)) {
                    serverTabFocusOnPageReturn = true
                    pager.openAppShellServerStackPage("members", shellId = childTabShellId.takeIf { stackShellTabs == null })
                } else {
                    selectAppShellTab("members", directChildSwitch = true, source = AppShellTabSwitchSource.User)
                    retainedAppShellTabs = retainedAppShellTabs + "members"
                }
            }
            RaftServerMenuEntry.Computers -> {
                RaftProductEvents.navEntrySelected(
                    destination = RaftNavDestination.Computers,
                    surface = RaftNavSurface.MobileMore,
                    wasActive = false,
                )
                if (!navRailActive) serverTabFocusOnPageReturn = true
                openComputersPage()
            }
            RaftServerMenuEntry.Apps -> {
                // The phone's Apps (web MobileServerTab `openApps`): the page the wide rail's Apps
                // shows. The app's nav events have no `apps` yet (RaftAppDestination.Apps).
                if (!navRailActive) serverTabFocusOnPageReturn = true
                pager.openSettingsMarketplacePage(storeScope)
            }
            RaftServerMenuEntry.Settings -> {
                // On the phone Settings is the pushed page under the `more` key; the pinned rail
                // entry when wide.
                val settingsKey = if (navRailActive) "settings" else AppShellServerTabKey
                RaftProductEvents.navEntrySelected(
                    destination = RaftNavDestination.Settings,
                    surface = RaftNavSurface.MobileMore,
                    wasActive = retainedAppShellSelectedTab(selectedTab) == settingsKey,
                )
                if (appShellServerPagePushes(stackShellTabs != null, navRailActive)) {
                    serverTabFocusOnPageReturn = true
                    pager.openAppShellServerStackPage(settingsKey, shellId = childTabShellId.takeIf { stackShellTabs == null })
                } else {
                    selectAppShellTab(settingsKey, directChildSwitch = true, source = AppShellTabSwitchSource.User)
                    retainedAppShellTabs = retainedAppShellTabs + settingsKey
                }
            }
        }
    }

    // raft-design #712: on the phone Settings is a pushed page titled Settings, opened
    // from the server panel, with Back (Members and Computers are on the panel now,
    // so they no longer lead it); its rows keep their attention dots. The wide rail
    // keeps its Settings page as before.
    // A Settings row's page; [anchor]: a Settings search result's setting to open it at.
    val openSettingsRowAt: (String, SettingsSearchAnchorRequest?) -> Unit = { key, anchor ->
        prewarmAdmissionGate.invalidate("settings_open_page:$key")
        settingsHeaderTaps.reset()
        when (key) {
            SettingsDestinationAccount -> pager.openSettingsAccountPage(storeScope, anchor)
            SettingsDestinationStorage -> pager.openSettingsStoragePage(storeScope)
            SettingsDestinationReleaseNotes -> pager.openReleaseNotesPage(storeScope = storeScope)
            SettingsDestinationDocumentation -> bridge.openUrl(RaftDocumentationUrl, inBrowser = true)
            SettingsDestinationBilling -> pager.openSettingsBillingPage(storeScope, anchor)
            SettingsDestinationComputers -> {
                if (!navRailActive) {
                    RaftProductEvents.navEntrySelected(
                        destination = RaftNavDestination.Computers,
                        surface = RaftNavSurface.MobileMore,
                        wasActive = false,
                    )
                }
                openComputersPage()
            }
            SettingsDestinationAdministration -> pager.openSettingsAdministrationPage(storeScope, anchor)
            SettingsDestinationImBridges -> pager.openSettingsImBridgesPage(storeScope)
            SettingsDestinationAppAuthorizations -> pager.openSettingsAppAuthorizationsPage(storeScope, anchor)
            SettingsDestinationLabs -> pager.openSettingsLabsPage(storeScope)
            SettingsDestinationConnectedApps -> pager.openSettingsMarketplacePage(storeScope)
            // The native ticket list, on every platform.
            SettingsDestinationHandsFeedback -> pager.openSettingsFeedbackPage(storeScope)
            else -> {
                val page = SettingsNativeDetailPage.fromKey(key)
                if (page == null || !pager.openSettingsNativeDetailPage(page, storeScope, anchor = anchor)) {
                    bridge.logWarn("settings_native_detail_rejected page=$key")
                }
            }
        }
    }
    val openSettingsRow: (String) -> Unit = { key -> openSettingsRowAt(key, null) }

    // A link to Raft's own web Settings (an announcement's "Choose a theme"): the in-app page, on
    // the server the link names, as the Settings rows open it; the Settings home for a tab the
    // app has no page of its own for.
    fun openSettingsFromLink(
        link: RaftSettingsLink,
        target: RaftSettingsLinkTarget,
        source: String,
        anchorOverride: String? = null,
    ) {
        serverMenuState.dismiss()
        target.switchToServerId?.let { serverId ->
            readySession
                ?.withAppShellSelectedServer(serverId)
                ?.let { switched -> authGateViewModel.dispatch(SharedAuthGateIntent.ServerReady(switched)) }
            homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(null))
            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.SelectServer(serverId)))
        }
        val key = target.destinationKey
        // A block the in-app page has (Account's `#two-step` / `#passkeys`, or an internal jump
        // such as the background-kill notice opening Notifications at keep-alive): opened at it,
        // located as a Settings search result is.
        val anchor = (anchorOverride ?: link.inAppAnchor)?.let { SettingsSearchAnchorRequest(it, ++settingsSearchOpenNonce) }
        bridge.log(
            "settings_link_open source=$source tab=${link.webTab ?: "home"} page=${key ?: "home"} " +
                "section=${link.section != null} anchor=${anchor?.id ?: "none"} switched=${target.switchToServerId != null}"
        )
        if (navRailActive) {
            railInlineSecondaryDestination = null
            // The wide rail's Settings: the section beside the list, or a page over it.
            selectAppShellTab("settings", directChildSwitch = true, source = AppShellTabSwitchSource.User)
            retainedAppShellTabs = retainedAppShellTabs + "settings"
            when {
                key == null -> settingsWidePicked = null
                settingsWidePick(key) is SettingsWidePick.Detail -> {
                    settingsWidePicked = key
                    settingsWideAnchor = anchor?.let { key to it }
                }
                else -> openSettingsRowAt(key, anchor)
            }
        } else if (key != null) {
            openSettingsRowAt(key, anchor)
        } else if (appShellServerPagePushes(stackShellTabs != null, navRailActive)) {
            pager.openAppShellServerStackPage(
                AppShellServerTabKey,
                shellId = childTabShellId.takeIf { stackShellTabs == null },
            )
        } else {
            selectAppShellTab(AppShellServerTabKey, directChildSwitch = true, source = AppShellTabSwitchSource.User)
            retainedAppShellTabs = retainedAppShellTabs + AppShellServerTabKey
        }
    }

    fun onServerMenuPick(pick: RaftServerMenuPick) {
        when (pick) {
            // A held press let go over a server while a switch is pending: the list takes none.
            is RaftServerMenuPick.Server ->
                if (!homeServerSwitchListPending(homeState.serverSwitchState.pendingServerId)) {
                    switchServerFromServerMenu(pick.serverId)
                }
            is RaftServerMenuPick.Page -> openServerMenuEntry(pick.entry)
        }
    }

    // iPhone Duo's inner screen (raft-design #859): the wide layout without the top bar; the rail
    // holds the server tile, Search and the bell.
    val railWithoutTopBar = raftAppRailWithoutTopBar(navRailActive)
    // The server panel: over the content on the phone (above its bar), over the whole shell under
    // the wide top bar's workspace button ([topBar]), or next to the rail header's tile.
    val serverMenuOverlay: @Composable (topBar: Boolean) -> Unit = { topBar ->
        if (serverMenuState.open && serverMenuModel != null && serverMenuContent != null) {
            RaftAppServerMenuOverlay(
                model = serverMenuModel,
                state = serverMenuState,
                anchor =
                    when {
                        !topBar -> RaftServerMenuAnchor.BottomBar
                        railWithoutTopBar -> RaftServerMenuAnchor.RailHeader
                        else -> RaftServerMenuAnchor.TopBar
                    },
                entries =
                    raftServerMenuEntriesFor(
                        serverMenuModel.entries,
                        wide = navRailActive,
                        guest = serverMenuContent.snapshot.server.role.equals("guest", ignoreCase = true),
                        artifacts = serverMenuArtifactsShown,
                    ),
                onPick = ::onServerMenuPick,
                switchPending = homeServerSwitchListPending(homeState.serverSwitchState.pendingServerId),
                onServerOrderChange = { order ->
                    bridge.log("app_shell_server_menu_reorder count=${order.size}")
                    homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.ReorderServers(order)))
                },
                prefersChineseCommunity = localePreferenceStore.readLocaleSync() == AppLocale.SimplifiedChinese,
                onCreateServerClick = {
                    pager.openAuthChooseServerPage(
                        storeScope = navigationStoreScope,
                        documentShell = pager.pageData.params.optBoolean(RAFT_DOCUMENT_SHELL, false)
                    )
                },
                onInviteHumanClick =
                    homeServerSwitcherInviteClick(
                        serverMenuContent.snapshot.server,
                        { serverId -> serverSwitcherInviteServerId = serverId }
                    ) { serverMenuState.dismiss() },
                topInset =
                    if (topBar && !railWithoutTopBar) raftAppShellTopBarMenuTopInset(shellScope.topSafeArea) else shellScope.topSafeArea,
                screenHeight =
                    shellScope.adaptivePane
                        .takeIf { it.heightPx > 0 && it.density > 0f }
                        ?.let { (it.heightPx / it.density).dp },
                bottomInset =
                    when {
                        navRailActive -> shellScope.bottomSafeArea
                        // The iOS system bar floats over the content: clear its band.
                        shellScope.bottomTabBarOverlaysContent -> shellScope.bottomTabBarContentInset - RaftAppSpacing.Lg
                        else -> 0.dp
                    },
            )
        }
    }

    // artin task #8: the wide top bar's bell, and the sign to the list panes under it that they
    // have no head of their own.
    // Starts with this layout's value, so the first frame's panes are right; later changes (the
    // posture arriving, the window turning) land after composition (sindresorhus, #3906).
    val appShellTopBar = remember { RaftAppShellTopBarState().apply { drawn = !railWithoutTopBar } }
    // web SidebarToggleRailItem: the column the rail's toggle and ⌘\ / Ctrl+\ collapse here, if any.
    val collapsibleColumn =
        appShellCollapsibleColumn(
            navRailActive = navRailActive,
            selectedTab = selectedTab,
            selectedHomeDestination = selectedHomeDestination,
            railInlineSecondaryDestination = railInlineSecondaryDestination,
        )
    val shortcutsUseCommand = remember { raftPlatformShortcutsUseCommand() }
    // The web's desktop search (artin dm:@artin:#33): on the wide layout (desktop and iPad alike)
    // the top bar's search field and ⌘K / Ctrl+K open the search panel over the page, which stays
    // as it is behind the scrim; "Search for" goes on to the full results page (the list column's
    // Search). The phone layout keeps its Search tab: ⌘K there opens it.
    val searchOverlayOpenState = remember { mutableStateOf(false) }
    var searchOverlayOpen by searchOverlayOpenState
    var searchOverlayFocusRequest by remember { mutableStateOf(0) }
    AppShellSearchOverlayRailEffect(
        navRailActive = navRailActive,
        searchOverlayOpenState = searchOverlayOpenState,
    )
    fun openSearchOverlay(source: String) {
        if (!navRailActive) {
            openSearchTab(source)
            return
        }
        serverMenuState.dismiss()
        appShellTopBar.bellOpen.value = false
        RaftProductEvents.navEntrySelected(
            destination = appShellNavDestination(AppShellRailSearchKey),
            surface = RaftNavSurface.TopBar,
            wasActive = searchOverlayOpen,
        )
        // Already open: its field takes focus again (web: ⌘K on search focuses the mounted field).
        if (searchOverlayOpen) searchOverlayFocusRequest += 1
        searchOverlayOpen = true
        bridge.log("app_shell_search_overlay_open from=$source")
    }
    val shellPageActive = LocalRaftPageActive.current
    val latestShellPageActive by rememberUpdatedState(shellPageActive)
    LaunchedEffect(searchShortcutTick) {
        if (searchShortcutTick > 0 && latestPageVisible && latestShellPageActive) openSearchOverlay(source = "host_shortcut")
    }
    if (searchOverlayOpen && navRailActive && pageVisible && shellPageActive) {
        val density = LocalDensity.current
        val fieldBounds = appShellTopBar.searchFieldBounds.takeIf { appShellTopBar.drawn }
        val visited = remember(wideNavHistory) { wideNavHistory.visitedThreadIds() }
        val closeOverlay = {
            searchOverlayOpen = false
            // A reopened panel focuses as a new one, not as a ⌘K refocus.
            searchOverlayFocusRequest = 0
        }
        RaftWindowDialog(
            onDismissRequest = {
                bridge.log("app_shell_search_overlay_close")
                closeOverlay()
            },
            // The panel draws the scrim itself, so a click on it closes the panel.
            properties = RaftWindowDialogProperties(drawScrim = false),
        ) {
            HomeSecondaryRouterContent(
                destination = HomeDestinationSearch,
                routeVisibleTick = pageVisibleTick,
                adaptivePane = shellScope.adaptivePane,
                // A row opens where the list column's Search opens it: in the detail pane.
                inlineOpenRequest = { request, source -> openHomeMessageRequest(request, source) },
                inlineOpenEntity = { entity ->
                    entity.homeSearchInlineConversationOrNull()?.let { conversation ->
                        openHomeConversation(conversation = conversation, source = "home_search_overlay_entity")
                        true
                    } ?: false
                },
                searchOverlay =
                    HomeSearchOverlayChrome(
                        anchorTop = fieldBounds?.let { with(density) { it.top.toDp() } },
                        anchorWidth = fieldBounds?.let { with(density) { it.width.toDp() } } ?: 0.dp,
                        visitedThreadIds = visited,
                        excludeThreadId = adaptiveWorkspaceState?.selection?.identity?.threadId,
                        onDismiss = closeOverlay,
                        focusRequest = searchOverlayFocusRequest,
                        onOpenFullResults = { query ->
                            bridge.log("app_shell_search_overlay_full_results queryLength=${query.length}")
                            closeOverlay()
                            searchOverlayHandoffNonce -= 1
                            searchTabQueryRequest = RaftSearchTabQueryRequest(query.trim(), nonce = searchOverlayHandoffNonce)
                            if (railInlineSecondaryDestination == AppShellRailSearchKey) railInlineSearchFocusRequest += 1
                            railInlineSecondaryDestination = AppShellRailSearchKey
                        },
                    ),
            )
        }
    }
    // Held down, the key repeats: one press toggles once.
    var sidebarShortcutHeld by remember { mutableStateOf(false) }
    AppShellTopBarEffects(
        railHelpOpenState = railHelpOpenState,
        railWithoutTopBar = railWithoutTopBar,
        appShellTopBar = appShellTopBar,
    )
    // Who is working on this server (Live agent activity): the wide top bar and the Home bar read it.
    val latestThreadTimeContext by rememberUpdatedState(threadTimeContext)
    val workingNowSource =
        remember(sharedStores, storeScope, readyServerId, appShellServerMembersStore) {
            readyServerId?.trim()?.takeIf(String::isNotBlank)?.let { serverId ->
                RaftWorkingNowSource(
                    serverId = serverId,
                    items = sharedStores.liveAgentActivity(storeScope).itemsFlow(serverId),
                    agentStatusResolver = raftScopedAgentStatusResolver(appShellServerMembersStore, serverId),
                    subscriptions = sharedStores.agentActivitySubscriptions,
                    onOpenAgent = { agentId ->
                        val identity =
                            MemberDetailIdentity(memberId = MemberId(agentId), kind = MemberKind.Agent, sourceServerId = ServerId(serverId))
                        val pageData = homeSearchMemberDetailPageData(identity, latestThreadTimeContext)
                        bridge.log("working_now_open_agent agentId=$agentId opened=${pageData != null}")
                        if (pageData != null) {
                            pager.openRaftPage(pageName = MEMBER_DETAIL_PAGE, pageData = pageData, storeScopeOverride = storeScope)
                        }
                    },
                )
            }
        }
    val mainRouteSurface: @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalRaftAppShellTopBar provides appShellTopBar.takeIf { navRailActive },
            LocalRaftWorkingNow provides workingNowSource,
            // One channel-kind glyph per channel (search, drafts, pickers): this server's Home rows.
            LocalRaftChannelKinds provides rememberRaftChannelKindsState(homeState),
            // App-shell inline surfaces (wide search, Artifacts rail page, split panes) share the shell's scope.
            LocalRaftArtifactOpenStoreScope provides storeScope,
        ) {
        RaftAppShellSurface(
            shellScope = shellScope,
            bottomTabs = appShellBottomTabItems,
            // web MainLayout: ⌘\ / Ctrl+\ anywhere in the app, a text field included, while a
            // column can collapse.
            modifier =
                Modifier.onPreviewKeyEvent { event ->
                    // web MainLayout: ⌘K / Ctrl+K anywhere opens search (a text field included).
                    if (
                        raftSearchShortcut(
                            keyDown = event.type == KeyEventType.KeyDown,
                            key = event.key,
                            meta = event.isMetaPressed,
                            ctrl = event.isCtrlPressed,
                            alt = event.isAltPressed,
                            shift = event.isShiftPressed,
                            apple = shortcutsUseCommand,
                        )
                    ) {
                        openSearchOverlay(source = "shortcut")
                        return@onPreviewKeyEvent true
                    }
                    if (event.key == Key.Backslash && event.type == KeyEventType.KeyUp) sidebarShortcutHeld = false
                    val column = collapsibleColumn ?: return@onPreviewKeyEvent false
                    val shortcut =
                        raftSidebarToggleShortcut(
                            keyDown = event.type == KeyEventType.KeyDown,
                            key = event.key,
                            meta = event.isMetaPressed,
                            ctrl = event.isCtrlPressed,
                            alt = event.isAltPressed,
                            shift = event.isShiftPressed,
                            apple = shortcutsUseCommand,
                        )
                    if (!shortcut) return@onPreviewKeyEvent false
                    if (!sidebarShortcutHeld) {
                        sidebarShortcutHeld = true
                        sidebarCollapse.toggle(column)
                        bridge.log("app_shell_sidebar_toggle column=$column hidden=${sidebarCollapse.isHidden(column)} from=shortcut")
                    }
                    true
                },
            bottomServerTab =
                serverMenuModel?.let { model ->
                    { tabModifier ->
                        RaftAppServerTab(
                            model = model,
                            state = serverMenuState,
                            onPick = ::onServerMenuPick,
                            modifier = tabModifier,
                            requestFocus = serverTabFocusPending,
                            onFocusRequested = { serverTabFocusPending = false },
                        )
                    }
                },
            onBottomTabSelected = { key ->
                if (key == AppShellServerTabKey && serverMenuModel != null) {
                    // The server tab opens the server panel, not a page (also the iOS system bar's tap).
                    bridge.log("app_shell_server_menu_toggle open=${!serverMenuState.open}")
                    serverMenuState.toggle()
                    return@RaftAppShellSurface
                }
                serverMenuState.dismiss()
                RaftProductEvents.navEntrySelected(
                    destination = appShellNavDestination(key),
                    surface = RaftNavSurface.MobileTabBar,
                    wasActive = appShellPhoneBottomBarSelectedKey(selectedTab) == key,
                )
                onAppShellTabSelected(key)
            },
            networkStatus = hostNetworkStatus,
            topNoticeBanner =
                rememberRaftBackgroundKillNoticeBannerSlot(
                    networkStatus = hostNetworkStatus,
                    onOpenNotificationSettings = {
                        openSettingsFromLink(
                            link = RaftBackgroundKillNoticeSettingsLink,
                            target = RaftBackgroundKillNoticeSettingsTarget,
                            source = "background_kill_notice",
                            anchorOverride = SETTINGS_NOTIFICATIONS_BACKGROUND_KEEP_ALIVE_ANCHOR,
                        )
                    },
                ),
            showBottomTabs = showAppShellBottomTabs,
            // artin 2026-10-10 (Hands 79db9fe6): the cached Home is usable at once while the
            // session and server list refresh in the background; a failed restore or an expired
            // session still goes to its prompt / sign-in. No blocking overlay.
            interactionLocked = false,
            modalHostState = appShellModalHostState,
            leadingNavigation =
                if (navRailActive) {
                    // task #31: Web LeftRail parity — icon-only tabs, settings pinned to the bottom.
                    // artin task #8: the top bar holds the server and Search, so the rail has
                    // neither (web LeftRail with the top bar).
                    {
                        // web LeftRail footer: Help, then Settings. Computers has its own entry and
                        // dot now, so Settings dots only for an app update (Settings > About).
                        val railBottomItems =
                            remember(railNavLabels, selectedTab, railInlineSecondaryDestination, moreAttention, railHelpOpen) {
                                appShellRailFooterTabs(
                                    // An inline page (Computers, Apps) is the selection: Settings is not.
                                    selectedTab = if (appShellRailInlinePageRailId(railInlineSecondaryDestination) != null) "" else selectedTab,
                                    labelFor = { key -> railNavLabels[key] ?: key },
                                    helpOpen = railHelpOpen,
                                    moreAttention = moreAttention,
                                )
                            }
                        val railItems =
                            appShellRailTabItems.orderedLikePhoneBar { it.key }.let { items ->
                                if (railWithoutTopBar) {
                                    appShellRailItemsWithSearch(
                                        items = items,
                                        searchLabel = searchTabLabel,
                                        searchOpen = railInlineSecondaryDestination == AppShellRailSearchKey,
                                    )
                                } else {
                                    items
                                }
                            }.orderedLikeRailTabOrder(railTabOrderState.order)
                        if (railWithoutTopBar) {
                            // Without the top bar the system's side column holds the history's Back.
                            raftSideColumnFallbackBack(
                                raftSideColumnBackAction(
                                    onBack = {
                                        appShellTopBar.bellOpen.value = false
                                        navigateWideHistory(RaftWideNavDirection.Back, source = "side_column")
                                    },
                                    enabled = wideNavHistory.canGoBack,
                                )
                            )
                        }
                        RaftNavigationRailTabBar(
                            items = railItems,
                            onSelect = { key ->
                                if (key == AppShellRailSearchKey) {
                                    // The rail's Search: the inline Search over the current tab.
                                    serverMenuState.dismiss()
                                    appShellTopBar.bellOpen.value = false
                                    RaftProductEvents.navEntrySelected(
                                        destination = appShellNavDestination(AppShellRailSearchKey),
                                        surface = RaftNavSurface.Rail,
                                        wasActive = railInlineSecondaryDestination == AppShellRailSearchKey,
                                    )
                                    if (railInlineSecondaryDestination == AppShellRailSearchKey) railInlineSearchFocusRequest += 1
                                    railInlineSecondaryDestination = AppShellRailSearchKey
                                    bridge.log("app_shell_rail_secondary_open destination=search from=rail")
                                    return@RaftNavigationRailTabBar
                                }
                                railHelpOpen = false
                                val destination = RaftAppDestination.fromId(key)
                                if (destination?.route == RaftNavRoute.Page) {
                                    // Computers and Apps open the pages their Settings rows open, in
                                    // the content area beside the rail (an inline page; the selected
                                    // tab stays under it).
                                    val inlinePage = appShellRailInlinePageKey(destination)
                                    if (shouldDismissKeyboardOnRailInlinePageSelect(inlinePage, railInlineSecondaryDestination)) {
                                        dismissAppShellKeyboard()
                                    }
                                    serverMenuState.dismiss()
                                    appShellTopBar.bellOpen.value = false
                                    RaftProductEvents.navEntrySelected(
                                        destination = appShellNavDestination(key),
                                        surface = RaftNavSurface.Rail,
                                        wasActive = false,
                                    )
                                    bridge.log("app_shell_rail_page_open key=$key")
                                    if (inlinePage == null) {
                                        bridge.logWarn("app_shell_rail_page_unrouted key=$key")
                                    } else {
                                        if (destination == RaftAppDestination.Computers) {
                                            writeComputerAttentionSeen(
                                                build.raft.app.data.raftComputerAttentionMarkSeen(computerAttentionSeen, currentComputerAttention)
                                            )
                                        }
                                        railInlineSecondaryDestination = inlinePage
                                    }
                                    return@RaftNavigationRailTabBar
                                }
                                if (shouldDismissKeyboardOnRailTabSelect(key, railInlineSecondaryDestination, selectedTab)) {
                                    dismissAppShellKeyboard()
                                }
                                RaftProductEvents.navEntrySelected(
                                    destination = appShellNavDestination(key),
                                    surface = RaftNavSurface.Rail,
                                    wasActive =
                                        railInlineSecondaryDestination == null &&
                                            retainedAppShellSelectedTab(selectedTab) == key,
                                )
                                // Tapping a real rail item dismisses the inline secondary destination
                                // even when the underlying tab is already selected (Activity -> Search
                                // -> Activity must land back on Activity).
                                railInlineSecondaryDestination = null
                                onAppShellTabSelected(key)
                                // Then the tab's place (RaftRailPlaces.kt): Chat's conversation, a list with nothing open.
                                railPlaces.land(latestWideNavShowing, key, selectedTab, railSelectionOpenedOn) { place ->
                                    restoreWideNavEntry(place)
                                }
                            },
                            modifier = Modifier.fillMaxHeight(),
                            reorder = railTabReorder,
                            // The server tab at the foot is the phone's; the top bar holds the server.
                            bottomItems =
                                if (railWithoutTopBar && railInlineSecondaryDestination == AppShellRailSearchKey) {
                                    railBottomItems.map { it.copy(selected = false) }
                                } else {
                                    railBottomItems
                                },
                            onBottomItemSelect = { key ->
                                if (RaftAppDestination.fromId(key)?.route == RaftNavRoute.Menu) {
                                    if (!railHelpOpen) dismissAppShellKeyboard()
                                    serverMenuState.dismiss()
                                    appShellTopBar.bellOpen.value = false
                                    railHelpOpen = !railHelpOpen
                                    bridge.log("app_shell_rail_help_toggle open=$railHelpOpen")
                                    return@RaftNavigationRailTabBar
                                }
                                if (shouldDismissKeyboardOnRailTabSelect(key, railInlineSecondaryDestination, selectedTab)) {
                                    dismissAppShellKeyboard()
                                }
                                railHelpOpen = false
                                RaftProductEvents.navEntrySelected(
                                    destination = appShellNavDestination(key),
                                    surface = RaftNavSurface.Rail,
                                    wasActive = railInlineSecondaryDestination == null && retainedAppShellSelectedTab(selectedTab) == key,
                                )
                                // Like a rail tab: Settings replaces an inline destination (Computers,
                                // Apps) even when Settings is the tab under it.
                                railInlineSecondaryDestination = null
                                onAppShellTabSelected(key)
                            },
                            sidebarToggle =
                                collapsibleColumn?.let { column ->
                                    RaftRailSidebarToggle(
                                        hidden = sidebarCollapse.isHidden(column),
                                        onHiddenChange = { hidden ->
                                            sidebarCollapse.setHidden(column, hidden)
                                            bridge.log("app_shell_sidebar_toggle column=$column hidden=$hidden from=rail")
                                        },
                                        shortcutLabel = raftSidebarToggleShortcutLabel(shortcutsUseCommand),
                                    )
                                },
                            // The top bar above the rail clears the status bar; without it the rail does.
                            topSafeArea = if (railWithoutTopBar) shellScope.topSafeArea else 0.dp,
                            bottomSafeArea = shellScope.bottomSafeArea,
                            header =
                                if (railWithoutTopBar && serverMenuModel != null) {
                                    {
                                        RaftAppRailServerTile(
                                            model = serverMenuModel,
                                            open = serverMenuState.open,
                                            onClick = {
                                                appShellTopBar.bellOpen.value = false
                                                bridge.log("app_shell_server_menu_toggle open=${!serverMenuState.open} from=rail")
                                                serverMenuState.toggle()
                                            },
                                        )
                                    }
                                } else {
                                    null
                                },
                            footerTools =
                                if (railWithoutTopBar) {
                                    { RaftAppRailNotificationBell(appShellTopBar, onToggle = { serverMenuState.dismiss() }) }
                                } else {
                                    null
                                },
                        )
                    }
                } else {
                    null
                },
            topNavigation =
                if (navRailActive && !railWithoutTopBar) {
                    {
                        // iPhone Duo's inner screen held sideways (raft-design #859): the system's
                        // side column holds Back (the wide history's), so the bar draws no arrows.
                        val historyBackInColumn =
                            raftSideColumnFallbackBack(
                                raftSideColumnBackAction(
                                    onBack = {
                                        appShellTopBar.bellOpen.value = false
                                        navigateWideHistory(RaftWideNavDirection.Back, source = "side_column")
                                    },
                                    enabled = wideNavHistory.canGoBack,
                                )
                            )
                        RaftAppShellTopBar(
                            currentServer = serverMenuModel?.currentServer,
                            servers = serverMenuModel?.servers.orEmpty(),
                            serverMenuOpen = serverMenuState.open,
                            onWorkspaceClick = {
                                appShellTopBar.bellOpen.value = false
                                bridge.log("app_shell_server_menu_toggle open=${!serverMenuState.open} from=top_bar")
                                serverMenuState.toggle()
                            },
                            onServerPick = { serverId ->
                                bridge.log("app_shell_top_bar_server_pick to=$serverId")
                                switchServerFromServerMenu(serverId)
                            },
                            searchLabel = searchTabLabel,
                            // The search panel over the page (web SearchOverlay); its "Search for"
                            // opens the list column's Search.
                            onSearchClick = { openSearchOverlay(source = "top_bar") },
                            searchShortcut = RaftKeyGlyph.searchShortcut(apple = shortcutsUseCommand),
                            windowWidth =
                                shellScope.adaptivePane
                                    .takeIf { it.widthPx > 0 && it.density > 0f }
                                    ?.let { (it.widthPx / it.density).dp } ?: 0.dp,
                            topSafeArea = shellScope.topSafeArea,
                            bell = appShellTopBar,
                            onBellToggle = { serverMenuState.dismiss() },
                            history =
                                if (historyBackInColumn) null else RaftAppShellTopBarHistory(
                                    canGoBack = wideNavHistory.canGoBack,
                                    canGoForward = wideNavHistory.canGoForward,
                                    onBack = {
                                        appShellTopBar.bellOpen.value = false
                                        navigateWideHistory(RaftWideNavDirection.Back, source = "top_bar")
                                    },
                                    onForward = {
                                        appShellTopBar.bellOpen.value = false
                                        navigateWideHistory(RaftWideNavDirection.Forward, source = "top_bar")
                                    },
                                ),
                            updateAction =
                                rememberAppShellTopBarUpdateAction(
                                    onOpenUpdatePage = { pager.openAppUpdatePage(storeScope) },
                                    onCompleteRestart = { updateSnapshot ->
                                        triggerAppShellUpdateRestartOrOpenPage(
                                            snapshot = updateSnapshot,
                                            scope = appShellCoroutineScope,
                                            settingsCapabilities = settingsCapabilities,
                                            onOpenUpdatePage = { pager.openAppUpdatePage(storeScope) },
                                        )
                                    },
                                    onBeforeAction = {
                                        appShellTopBar.bellOpen.value = false
                                        serverMenuState.dismiss()
                                    },
                                ),
                        )
                    }
                } else {
                    null
                },
            // The top bar's server panel opens under its workspace button, and the bell's popover
            // under the bell, over the rail.
            shellOverlay =
                if (navRailActive) {
                    {
                        serverMenuOverlay(true)
                        RaftAppTopBarNotificationOverlay(
                            appShellTopBar,
                            raftAppShellTopBarMenuTopInset(shellScope.topSafeArea),
                            besideRail = railWithoutTopBar,
                            bottomInset = shellScope.bottomSafeArea,
                        )
                        RaftAppRailHelpOverlay(
                            open = railHelpOpen,
                            onDismiss = { railHelpOpen = false },
                            bottomInset = shellScope.bottomSafeArea,
                            onDocumentation = {
                                railHelpOpen = false
                                bridge.log("app_shell_rail_help_pick item=documentation")
                                bridge.openUrl(RaftDocumentationUrl, inBrowser = true)
                            },
                            onFeedback = {
                                railHelpOpen = false
                                bridge.log("app_shell_rail_help_pick item=feedback")
                                // The Settings Feedback row's page (web Help > Feedback opens Settings' Feedback).
                                pager.openSettingsFeedbackPage(storeScope)
                            },
                        )
                    }
                } else {
                    null
                },
        ) {
                    // The wide top bar clears the status bar: the pages under it start at its seam.
                    @Suppress("NAME_SHADOWING")
                    val shellScope = if (navRailActive && !railWithoutTopBar) shellScope.copy(topSafeArea = 0.dp) else shellScope
                    if (!navRailActive && showAppShellBottomTabs) serverMenuOverlay(false)
                    serverSwitcherInviteServerId?.let { inviteServerId ->
                        val inviteMembersDataSource =
                            remember(memberRepository, settingsRepository, appShellServerMembersStore) {
                                SharedMembersDataSource(
                                    memberRepository = memberRepository,
                                    settingsRepository = settingsRepository,
                                    serverMembersStore = appShellServerMembersStore
                                )
                            }
                        SharedServerInviteHumanDialogHost(
                            serverId = inviteServerId,
                            membersDataSource = inviteMembersDataSource,
                            loadWebBaseUrl = {
                                (settingsCapabilities.loadDeveloper() as? DataResult.Content)?.data?.webUrl
                            },
                            copyToPasteboard = bridge::copyToPasteboard,
                            onDismiss = { serverSwitcherInviteServerId = null }
                        )
                    }
                    // task #180: SHELL-RENDERED MENUS. A Kuikly @Page child (thread column, detail
                    // pane) is its own composition, so a Modal opened inside it is clipped to that
                    // pane -- device evidence showed nothing drawn outside the window's left 400px
                    // strip could ever appear, whatever coordinates were passed. Pages therefore ASK
                    // the shell to draw, and the shell (this composition, spanning the window)
                    // renders the menu. Same host pattern as the rail server switcher above.
                    shellOverlayMenu?.let { open ->
                        // task #180 diagnostics (Argus): point 3 of 3 -- the host is composing.
                        raftTraceToPlatformLog(
                            "SlockShellOverlay",
                            "shell_host_compose requestId=${open.request.requestId} " +
                                "anchor=${open.request.anchor} items=${open.request.items.size} " +
                                "content=${shellOverlayContent != null}"
                        )
                        val customContent = shellOverlayContent?.takeIf {
                            it.requestId == open.request.requestId
                        }
                        val dismissShellOverlay = {
                            eventBus.post(
                                RAFT_SHELL_OVERLAY_RESULT_EVENT,
                                RaftShellOverlayResult(
                                    requestId = open.request.requestId,
                                    dismissed = true
                                ).toJson()
                            )
                            shellOverlayContent = null
                            shellOverlayMenu = null
                        }
                        if (customContent != null) {
                            // The shell is the full-window composition. Keep the exact child menu
                            // composable (including its reaction atlas and group dividers), but
                            // mount it in this window-level Modal surface rather than the 400dp
                            // child-page surface.
                            RaftModalAnchoredPopover(
                                anchor = RaftPopoverAnchor.forRootOffset(open.request.anchor),
                                contentSize = customContent.contentSize,
                                onDismiss = dismissShellOverlay,
                                viewportGap = customContent.viewportGap,
                                horizontalOffset = customContent.horizontalOffset,
                                verticalOffset = customContent.verticalOffset,
                                horizontalAutoFlip = true,
                                verticalPlacement = RaftFloatingVerticalPlacement.Auto
                            ) {
                                customContent.content { id ->
                                    customContent.onSelected(id)
                                    shellOverlayContent = null
                                    shellOverlayMenu = null
                                }
                            }
                        } else {
                            RaftUiControlledMenu(
                                expanded = true,
                                // The anchor arrives already resolved in WINDOW space: the page owns the
                                // conversion, and re-deriving it here is what produced four rounds of
                                // wrong positions.
                                anchor = RaftPopoverAnchor.forRootOffset(open.request.anchor),
                                items =
                                    open.request.items.map { item ->
                                        RaftUiMenuItem(
                                            id = item.id,
                                            label = item.label,
                                            icon = item.iconName?.let { name -> RaftLucideIconName.entries.firstOrNull { it.name == name } },
                                            enabled = item.enabled,
                                            dividerBefore = item.dividerBefore,
                                        )
                                    },
                                onSelect = { id ->
                                    eventBus.post(
                                        RAFT_SHELL_OVERLAY_RESULT_EVENT,
                                        RaftShellOverlayResult(
                                            requestId = open.request.requestId,
                                            selectedId = id
                                        ).toJson()
                                    )
                                    shellOverlayMenu = null
                                },
                                onDismiss = dismissShellOverlay,
                                // raft-ui DropdownMenuContent's `sideOffset` 6.
                                verticalOffset = RaftMetrics.MenuSideOffset
                            )
                        }
                    }
                    // task #34: the shared workspace detail child, hosted by the list + detail split
                    // (Home, Activity, DMs, Search) and by the Tasks page's task column.
                    // task #36: the main detail plus, when it fits, the reply-thread side column.
                    // [allowSideColumn] is false on hosts that cannot show one (the task column);
                    // the active host reports its measured width for the column decision.
                    val adaptiveDetailPane: @Composable (Modifier, Boolean, Boolean, Boolean, Boolean) -> Unit = { paneModifier, active, detailAccepted, allowSideColumn, defaultLandingEligible ->
                        val density = LocalDensity.current
                        val workspace = adaptiveWorkspaceState
                        // task #33: a surface may decline the shared selection (the DMs tab shows only
                        // DMs); a declined selection renders the empty placeholder, never its composer.
                        // task #243: fence selection/payload on readyServerId so Frame T0 of a server
                        // switch immediately unmounts the previous server's detail before Effect #1
                        // applies AuthorityChanged.
                        val selection =
                            raftAdaptiveSelectionForReadyServer(
                                workspace = workspace,
                                readyServerId = readyServerId,
                                detailAccepted = detailAccepted,
                            )
                        val detailPayload = adaptiveDetailLaunchPayload?.takeIf { selection != null }
                        // Only the selected tab's pane hosts the reply-thread column: every kept-alive tab
                        // (Home, Activity, ...) composes this pane, and each composed its own thread page
                        // for the same side thread (desktop freeze on opening a reply, 11-03).
                        val tabSelected = LocalRaftAppShellTabSelected.current
                        val sideThread: AdaptiveSideThread? =
                            adaptiveWorkspaceSession.visibleSideThread()?.takeIf {
                                allowSideColumn && detailAccepted && tabSelected && selection != null
                            }
                        val showSideColumn =
                            sideThread != null &&
                                (adaptiveWorkspaceSession.sideThreadColumnAvailable(desktopModeOnLargeScreen) ||
                                    listPaneResizeDefersFold)
                        LaunchedEffect(active, allowSideColumn) {
                            if (active && !allowSideColumn) {
                                adaptiveWorkspaceStore.update {
                                    if (it.detailPaneWidthDp == null && it.sideThreadFolds == null) it
                                    else it.copy(detailPaneWidthDp = null, sideThreadFolds = null)
                                }
                            }
                        }
                        // Wide pane roles (RaftWidePanes.kt): a page the conversation opens with a
                        // Supporting role shows in the pane on the right; the conversation stays.
                        // The task column has no room for one: its pages open as before.
                        // Inside the list + detail split the detail's columns stand in the split's row,
                        // right of this pane: the width the side column decision reads is that of the
                        // pane and its columns together, as when they stood inside it.
                        // #189 rule 3 (web rowPlan decideFold): whether the thread's column folds into the
                        // conversation, from that row with every column in it at its kept width (the Tasks /
                        // Files column the conversation hands up included) and the conversation's kept width.
                        val gridRoomDp = raftPaneGridRoomDp()
                        val gridThreadFolds = raftPaneGridFolds(RaftPaneGridColumns.Thread)
                        LaunchedEffect(active, allowSideColumn, gridRoomDp, gridThreadFolds) {
                            if (!active || !allowSideColumn || gridRoomDp == null) return@LaunchedEffect
                            adaptiveWorkspaceStore.update {
                                if (it.detailPaneWidthDp == gridRoomDp && it.sideThreadFolds == gridThreadFolds) it
                                else it.copy(detailPaneWidthDp = gridRoomDp, sideThreadFolds = gridThreadFolds)
                            }
                        }
                        val profileColumnHost = navRailActive && allowSideColumn
                        Box(
                            modifier =
                                paneModifier
                                    .fillMaxSize()
                                    .onSizeChanged { size ->
                                        if (!active || !allowSideColumn || gridRoomDp != null) return@onSizeChanged
                                        val widthDp = with(density) { size.width.toDp().value }
                                        // No grid row here: the measured width decides the fold (sideThreadFolds null).
                                        adaptiveWorkspaceStore.update {
                                            if (it.detailPaneWidthDp == widthDp && it.sideThreadFolds == null) it
                                            else it.copy(detailPaneWidthDp = widthDp, sideThreadFolds = null)
                                        }
                                    },
                        ) {
                        val detailModifier = Modifier.fillMaxSize()
                        val childActive =
                            active && workspace?.presentation != RaftAdaptiveWorkspacePresentation.Inactive
                        val adaptiveEmptyLabel = stringResource(Res.string.home_adaptive_select_conversation)
                        val suppressEmptyState =
                            shouldSuppressWideAdaptiveEmptyState(
                                desktopModeOnLargeScreen = desktopModeOnLargeScreen,
                                detailAccepted = detailAccepted,
                                defaultLandingEligible = defaultLandingEligible,
                                authoritativeHomeStartAllowed = authoritativeHomeStartAllowed,
                                readyServerId = readyServerId,
                                adaptiveDefaultLandingServerId = adaptiveDefaultLandingServerId,
                                homeContent = homeState.content,
                            )
                        val detailPageData =
                            remember(
                                detailPayload,
                                selection,
                                workspace?.shellInstanceId,
                                navigationStoreScope.shellInstanceId,
                                navigationStoreScope.initialServerId,
                            ) {
                                if (workspace == null || detailPayload == null || selection == null) {
                                    null
                                } else {
                                    threadLaunchPageData(detailPayload)
                                        .withRaftShellStoreScope(navigationStoreScope)
                                        .put(RAFT_ADAPTIVE_DETAIL_SHELL_ID, workspace.shellInstanceId)
                                        .put(RAFT_ADAPTIVE_DETAIL_IDENTITY_KEY, selection.identity.key)
                                        .put(RAFT_ADAPTIVE_DETAIL_GENERATION, selection.generation)
                                }
                            }
                        // Bug A (11500008): hand-off point from the shell selection to the detail page.
                        // Every detail_mount must be followed by the detail's own effect_enter for the
                        // same identity/generation; a mount with no matching effect_enter means the
                        // detail page kept rendering an older selection.
                        LaunchedEffect(active, selection?.identity?.key, selection?.generation) {
                            val mounted = selection?.takeIf { active && detailPageData != null } ?: return@LaunchedEffect
                            postShellAdaptiveTrace(
                                hop = "detail_mount",
                                identityKey = mounted.identity.key,
                                generation = mounted.generation,
                            )
                        }
                        // The side thread stays beside the profile column: the row plan gives both room, or
                        // folds the thread into the conversation (#189 rule 3), clips or makes tabs.
                        val sideThreadColumn: RaftPaneGridColumn? =
                            if (showSideColumn && sideThread != null && workspace != null) {
                                // task #180: the page data key is deliberately independent of Focus mode.
                                // Toggling re-lays-out the same child page instead of recreating it, which is
                                // what keeps the reader's scroll position across Focus <-> Standard.
                                val sidePageData =
                                    remember(sideThread, navigationStoreScope.shellInstanceId, navigationStoreScope.initialServerId) {
                                        threadLaunchPageData(sideThread.payload)
                                            .withRaftShellStoreScope(navigationStoreScope)
                                            .put(RAFT_ADAPTIVE_DETAIL_SHELL_ID, workspace.shellInstanceId)
                                            .put(RAFT_ADAPTIVE_DETAIL_IDENTITY_KEY, sideThread.identity.key)
                                            .put(RAFT_ADAPTIVE_DETAIL_GENERATION, sideThread.generation)
                                    }
                                val sideThreadPageData by rememberUpdatedState(sidePageData)
                                // Read in the cell through state, not captured in the remembered column (PR #4661
                                // review): captured, a tab switched away and back could leave the column's page
                                // inactive and blank. The column object stays the same; the page follows the state.
                                val sideThreadActive by rememberUpdatedState(childActive)
                                // The column is a side pane of one page (web layout-memory "Close and Back"): its head's
                                // Close closes the column only, the channel staying; it never steps the detail back.
                                val sideThreadShown by rememberUpdatedState<AdaptiveSideThread>(sideThread)
                                val sideThreadShellId by rememberUpdatedState(workspace.shellInstanceId)
                                val sideThreadPane =
                                    remember {
                                        RaftSidePanePage(depth = 1) {
                                            val shellId = sideThreadShellId
                                            if (shellId.isNotBlank()) {
                                                val request = sideThreadShown.closeRequest(shellId)
                                                adaptiveWorkspaceStore.update { current -> current.closeSideThread(request) }
                                            }
                                            bridge.log("wide_side_thread_close from=head")
                                        }
                                    }
                                val sideThreadResizeLabel = stringResource(Res.string.thread_side_thread_resize)
                                // Its tab where the row is short of 160 per column (#189 rule 4; web RowTabs "Thread").
                                val sideThreadTabLabel = stringResource(Res.string.thread_reply_title)
                                remember(sideThreadResizeLabel, sideThreadTabLabel) {
                                    RaftPaneGridColumn(
                                        bounds = RaftPaneGridColumns.Thread,
                                        resizeLabel = sideThreadResizeLabel,
                                        tabLabel = sideThreadTabLabel,
                                        // ThreadPageHeader renders row tabs inline (web ThreadPanelHeader), avoiding an extra strip above the header.
                                        headCarriesTabs = true,
                                        cells =
                                            listOf(
                                                RaftPaneGridCell(id = "thread") {
                                                    RaftFocusRegionHost(
                                                        region = RaftFocusRegion.SideThread,
                                                        modifier = Modifier.fillMaxSize(),
                                                    ) {
                                                        CompositionLocalProvider(LocalRaftSidePanePage provides sideThreadPane) {
                                                            RaftChildPage(
                                                                pageName = THREAD_PAGE,
                                                                pageData = sideThreadPageData,
                                                                modifier = Modifier.fillMaxSize(),
                                                                active = sideThreadActive,
                                                            )
                                                        }
                                                    }
                                                }
                                            ),
                                    )
                                }
                            } else {
                                null
                            }
                        // The detail is the main pane of the pane grid (RaftPaneGrid.kt; web
                        // MainLayout's row): the thread's column right of it (web COLUMN_BOUNDS.thread:
                        // 400 by default, at least 360, its left seam dragging it until the
                        // conversation is down to 360; the width is kept), then the columns the
                        // conversation declares (Task v2's Tasks / Files), laid out by the #189 row
                        // plan (all give way in proportion, then clip, then become tabs). Inside the
                        // list + detail split they all stand in its row. A column's Expand (#189 rule 5, the
                        // thread's Focus mode) fills the row with it, every other column at 160.
                        // The profile column (RaftWidePanes.kt, #189 step 3): the profile over the channel details,
                        // the row's last column (web DEFAULT_CHANNEL_SIDE_LAYOUT), with the conversation's host that
                        // opens pages in it. A page opened in it restores an expanded row, and Back restores it first
                        // (RaftWorkspaceDetailGrid).
                        RaftWorkspaceDetailRow(
                            modifier = detailModifier,
                            columns = sideColumns,
                            columnHost = profileColumnHost,
                            detailAccepted = detailAccepted,
                            tabSelected = tabSelected,
                            active = active,
                            sideThreadColumn = sideThreadColumn,
                            // A conversation opened in the column (the profile's Message) becomes the main detail and
                            // the column closes, as the web's closeProfile + navigate.
                            openDetail = { pageData ->
                                val opened = threadLaunchPayloadOrNull(pageData)?.let(::openLaunchPayloadInAdaptiveDetail)
                                if (opened != null) {
                                    sideColumns.profile = sideColumns.profile.cleared()
                                    homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(opened.identity.threadId))
                                }
                                bridge.log("wide_profile_column_open_detail opened=${opened != null}")
                                opened != null
                            },
                        ) {
                            if (detailPageData == null) {
                                if (suppressEmptyState) {
                                    // task #243: keep the pane's themed surface blank while Home
                                    // default landing is still pending, avoiding a transient flash
                                    // of "Select a conversation" on cold start or server switch.
                                    Box(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite))
                                } else {
                                    // The wide detail with nothing open: the shared empty state (icon,
                                    // muted title) on the pane's surface, not a bare line of text.
                                    RaftEmptyState(
                                        title = adaptiveEmptyLabel,
                                        modifier = Modifier.fillMaxSize(),
                                        icon = { RaftContentLucideIcon(RaftLucideIconName.Message, size = RaftEmptyStateDefaults.IconSize) },
                                    )
                                }
                            } else {
                                RaftFocusRegionHost(
                                    region = RaftFocusRegion.Conversation,
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    RaftChildPage(
                                        pageName = THREAD_PAGE,
                                        pageData = detailPageData,
                                        modifier = Modifier.fillMaxSize(),
                                        active = childActive,
                                        onLoadSuccess = {
                                            selection?.generation?.let(::releaseAdaptiveHandoffLease)
                                        },
                                        onLoadFailure = {
                                            selection?.generation?.let(::releaseAdaptiveHandoffLease)
                                        },
                                    )
                                }
                            }
                        }
                        }
                    }
                    val tasksTabContent: @Composable (active: Boolean) -> Unit = { active ->
                        // Task v2: a task opened without a page of its own (its empty conversation, a legacy panel).
                        val tasksOpenSurfaceFlow = remember { TaskOpenSurfaceFlow(foregroundBannerStore.taskPendingDrafts) }
                        TaskOpenSurfaceIncomingNavigationEffect(tasksOpenSurfaceFlow, foregroundBannerStore.incomingNavigation)
                        val tasksOpenGate = remember { TaskOpenGate() }
                        var tasksPendingSurfaceServerId by remember { mutableStateOf<String?>(null) }
                        val tasksPendingColumnAllowed =
                            appShellTasksPendingColumnAllowed(
                                desktopModeOnLargeScreen = desktopModeOnLargeScreen,
                                dualPane = adaptivePane.layout == RaftAdaptivePaneLayout.DualPane,
                                selectedTab = selectedTab,
                                railInlineSecondaryDestination = railInlineSecondaryDestination,
                            )
                        val tasksPendingSurface =
                            (tasksOpenSurfaceFlow.surface as? TaskOpenSurface.Pending)
                                .takeIf {
                                    tasksPendingColumnAllowed &&
                                        !tasksDetailColumnOpen &&
                                        tasksPendingSurfaceServerId == readyServerId
                                }
                        val tasksPendingColumnOpen = tasksPendingSurface != null
                        val tasksColumnOpen = tasksDetailColumnOpen || tasksPendingColumnOpen
                        fun closeTasksPendingColumn() {
                            if (tasksOpenSurfaceFlow.requestClose()) {
                                tasksPendingSurfaceServerId = null
                                if (!tasksDetailColumnOpen) {
                                    tasksDetailColumnExpanded = false
                                }
                            }
                        }
                        TasksTabPendingColumnEffects(
                            flow = tasksOpenSurfaceFlow,
                            pendingColumnAllowed = tasksPendingColumnAllowed,
                            liveDetailColumnOpen = tasksDetailColumnOpen,
                            serverId = readyServerId,
                            onPendingClosed = {
                                tasksPendingSurfaceServerId = null
                                if (!tasksDetailColumnOpen) tasksDetailColumnExpanded = false
                            },
                        )
                        val tasksTabMounted = remember { mutableStateOf(true) }
                        LaunchedEffect(tasksColumnOpen) {
                            if (!tasksColumnOpen && tasksDetailColumnExpanded) {
                                tasksDetailColumnExpanded = false
                            }
                        }
                        DisposableEffect(Unit) {
                            onDispose {
                                tasksTabMounted.value = false
                                if (!tasksDetailColumnActive) tasksDetailColumnExpanded = false
                            }
                        }
                        // Web canWriteTasksInChannel (slock #15155): the Tasks page and the task pages it opens
                        // offer task writes only in a channel the reader may write (this server's Home rows).
                        val tasksChannelWriteServerId = readyServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
                        val tasksChannelWriteCheck: (ChannelId?) -> Boolean =
                            remember(appShellConversationDirectory, tasksChannelWriteServerId) {
                                { channelId -> appShellConversationDirectory.canWriteTasksInChannel(tasksChannelWriteServerId, channelId) }
                            }
                        val latestTasksOpenServerId by rememberUpdatedState(readyServerId)
                        val latestTasksTabActive by rememberUpdatedState(active)
                        val latestTasksPageVisible by rememberUpdatedState(pageVisible)
                        val latestTasksAdaptivePane by rememberUpdatedState(adaptivePane)
                        val latestTasksDetailColumnOpen by rememberUpdatedState(tasksDetailColumnOpen)
                        val tasksSendFailedLabel = stringResource(Res.string.task_conversation_send_failed)
                        val tasksOpenFailedLabel = stringResource(Res.string.thread_error_open_task)
                        val tasksUpdateFailedLabel = stringResource(Res.string.thread_error_update_task)
                        val tasksAssignFailedLabel = stringResource(Res.string.thread_error_update_assignee)
                        PerformanceSceneEffect(bridge, "kuikly_tasks", active = active)
                        // Foreground is the shell's visible-route refresh; a socket reconnect re-reads missed task pushes.
                        RaftLiveCatchUpEffect(serverId = selectedServerId, visible = active, foreground = false) {
                            tasksViewModel.dispatch(SharedTasksRouteIntent.CatchUp)
                        }
                        /**
                         * [animated] false: the conversation takes the pending task page's place (its
                         * first send); the page goes once it is covered, or at once where it opened in place.
                         */
                        fun openTaskPage(task: TaskSummary, resolvedConversationChannelId: ChannelId?, animated: Boolean = true) {
                            if (tasksOpenSurfaceFlow.sending) return
                            val taskLaunchPayload =
                                buildStandaloneTaskThreadLaunchPayload(
                                    task = task,
                                    serverId = latestTasksOpenServerId.orEmpty(),
                                    conversationDirectory = appShellConversationDirectory,
                                    timeContext = threadTimeContext,
                                    alignOwnMessagesRight = myMessageAlignment == MyMessageAlignmentPreference.Right,
                                    compactConsecutiveMessages = compactConsecutiveMessages,
                                    resolvedConversationChannelId = resolvedConversationChannelId,
                                )
                            if (taskLaunchPayload == null) {
                                bridge.logWarn("tasks_open_rejected task=${task.id.value} reason=launch_unresolved")
                                return
                            }
                            // page_viewed: the thread page this opens counts as `task`.
                            RaftProductEvents.recorder.markTaskThreadOpening(taskLaunchPayload.instanceId)
                            // Rating prompt (task_result_viewed): counts when the list shows again. With a
                            // task open beside the list, the list was in view: another task replacing it
                            // counts the one that was open.
                            if (latestTasksDetailColumnOpen && tasksDetailColumnTaskId != task.id.value) {
                                RaftRatingPrompt.onTaskListShown()
                            }
                            RaftRatingPrompt.onTaskOpened(task)
                            // task #34: wide desktop mode keeps the list and opens the task's thread
                            // in the column beside it (web TasksRoute), replacing the task open there;
                            // otherwise the full-page push stays.
                            val columnSelection = openLaunchPayloadInAdaptiveDetail(taskLaunchPayload)
                            if (columnSelection != null) {
                                tasksDetailColumnActive = true
                                tasksDetailColumnTaskId = task.id.value
                                tasksDetailColumnTaskIds =
                                    tasksDetailColumnTaskIds.opened(columnSelection.identity.key, task.id.value)
                                // Web tasksPageOpen: opened from the list, a task starts beside it; a
                                // first-send / arrived hand-off (animated = false) keeps the column's Expand state.
                                if (animated) tasksDetailColumnExpanded = false
                                bridge.log(
                                    "tasks_detail_column_open task=${task.id} " +
                                        "selection=${columnSelection.identity.key} generation=${columnSelection.generation}"
                                )
                                tasksOpenSurfaceFlow.requestClose()
                                tasksPendingSurfaceServerId = null
                                return
                            }
                            pager.openStandaloneThreadLaunchPayload(taskLaunchPayload, navigationStoreScope, animated)
                        }
                        val tasksDetailsRepository = remember(appShellNetworkClient) { TaskConversationDetailsRepository(appShellNetworkClient) }
                        val tasksPendingEditing =
                            remember(
                                shellAuthStore,
                                tasksDataSource,
                                sharedStores.tasksDataStore,
                                tasksUpdateFailedLabel,
                                tasksAssignFailedLabel,
                                bridge,
                            ) {
                                TaskPageEditing(
                                    authStore = shellAuthStore,
                                    tasksDataSource = tasksDataSource,
                                    tasksDataStore = sharedStores.tasksDataStore,
                                    onOperationFailed = { event ->
                                        bridge.showTaskOperationFailedToast(
                                            event = event,
                                            updateFailedLabel = tasksUpdateFailedLabel,
                                            assignFailedLabel = tasksAssignFailedLabel,
                                        )
                                    },
                                    log = { bridge.log(it) },
                                )
                            }
                        val onTasksPendingConversationArrived: (TaskSummary) -> Unit = { arrived ->
                            val serverId = readyServerId?.takeIf { it.isNotBlank() }
                            bridge.log("tasks_pending_conversation_arrived task=${arrived.id.value}")
                            launchArrivedTaskConversationOpen(
                                arrived = arrived,
                                serverId = serverId,
                                tasksDataSource = tasksDataSource,
                                scope = appShellCoroutineScope,
                                isStillActive = {
                                    tasksTabMounted.value &&
                                        railInlineSecondaryDestination == null &&
                                        latestTasksOpenServerId == serverId &&
                                        latestTasksTabActive &&
                                        latestTasksPageVisible
                                },
                                openPage = { openedTask, conversationId -> openTaskPage(openedTask, conversationId, animated = false) },
                                log = bridge::log,
                            )
                        }
                        val onTasksPendingSend: (TaskSummary, String) -> Unit = { task, text ->
                            val serverId = readyServerId?.takeIf { it.isNotBlank() }
                            if (serverId != null) {
                                appShellCoroutineScope.launch {
                                    val opened =
                                        tasksOpenSurfaceFlow.send(
                                            text = text,
                                            serverId = serverId,
                                            tasksDataSource = tasksDataSource,
                                            sendMessage = { channelId, body, randomId ->
                                                homeActivityReplyThreadPrewarmDataSource.sendMessage(
                                                    channelId = channelId,
                                                    body = body,
                                                    asTask = false,
                                                    randomId = randomId,
                                                )
                                            },
                                            currentServerId = { latestTasksOpenServerId },
                                            fallbackError = tasksSendFailedLabel,
                                        )
                                    bridge.log("tasks_task_first_send task=${task.id.value} opened=${opened != null}")
                                    opened?.let { (sentTask, conversationId) ->
                                        val stillOnTasksTab =
                                            tasksTabMounted.value &&
                                                railInlineSecondaryDestination == null &&
                                                latestTasksOpenServerId == serverId &&
                                                latestTasksTabActive &&
                                                latestTasksPageVisible
                                        if (stillOnTasksTab) {
                                            openTaskPage(sentTask, conversationId, animated = false)
                                        } else {
                                            tasksOpenSurfaceFlow.requestClose()
                                            tasksPendingSurfaceServerId = null
                                        }
                                    }
                                }
                            }
                        }
                        (tasksOpenSurfaceFlow.surface as? TaskOpenSurface.Legacy)?.let { legacy ->
                            TaskLegacyPanelSheet(task = legacy.task, onDismiss = { tasksOpenSurfaceFlow.dismiss() })
                        }
                        // The open pending page follows the board's copy of its task; when its conversation
                        // appears (someone wrote first) the conversation page opens instead.
                        val tasksOpenSurfaceTaskId = tasksOpenSurfaceFlow.surface?.task?.id
                        LaunchedEffect(tasksOpenSurfaceTaskId, tasksState) {
                            val taskId = tasksOpenSurfaceTaskId ?: return@LaunchedEffect
                            val fresh = (tasksState.loadState as? build.raft.app.data.ScreenLoadState.Content)?.data?.tasks.orEmpty().firstOrNull { it.id == taskId }
                            tasksOpenSurfaceFlow.onTaskChanged(fresh)?.let(onTasksPendingConversationArrived)
                        }
                        // The task opened from the page stands in a column beside the list on the wide
                        // layout (RaftTasksTaskColumn.kt; web TasksRoute), the list still in use.
                        RaftTasksTaskColumnLayout(
                            open = tasksColumnOpen,
                            expanded = tasksDetailColumnExpanded,
                            active = active,
                            onToggleExpand = { tasksDetailColumnExpanded = !tasksDetailColumnExpanded },
                            onClose = {
                                if (tasksPendingColumnOpen) {
                                    closeTasksPendingColumn()
                                } else {
                                    closeTasksDetailColumn()
                                }
                            },
                            // Back unwinds navigation made inside the column one level at a time, then closes it.
                            onBack = {
                                if (tasksPendingColumnOpen) {
                                    closeTasksPendingColumn()
                                } else if (adaptiveWorkspaceStore.snapshot().hasVisibleDetailBackHistory()) {
                                    dismissAdaptiveDetailSelection()
                                } else {
                                    closeTasksDetailColumn()
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                            task = {
                                val columnBottomInset = shellScope.embeddedChildBottomInset(showAppShellBottomTabs)
                                if (tasksPendingSurface != null) {
                                    TaskPendingSurfaceContent(
                                        surface = tasksPendingSurface,
                                        flow = tasksOpenSurfaceFlow,
                                        modifier = Modifier.padding(bottom = columnBottomInset),
                                        useHostSafeArea = true,
                                        canWriteTaskChannel = tasksChannelWriteCheck,
                                        detailsRepository = tasksDetailsRepository,
                                        serverId = readyServerId,
                                        viewerUserId = readySession?.user?.id,
                                        timeContext = threadTimeContext,
                                        membersStore = appShellServerMembersStore,
                                        editing = tasksPendingEditing,
                                        hostVisible = pageVisible,
                                        onConversationArrived = onTasksPendingConversationArrived,
                                        onSend = onTasksPendingSend,
                                        onBack = { closeTasksPendingColumn() },
                                    )
                                } else {
                                    adaptiveDetailPane(
                                        Modifier.padding(bottom = columnBottomInset),
                                        active,
                                        true,
                                        false,
                                        false,
                                    )
                                }
                            },
                        ) {
                        key(tasksRoute) {
                        // The page's Mine / All, search, status and sort: read from the server's task query.
                        val tasksQueryStore = tasksViewModel.queryStore
                        val tasksListShown = LocalRaftTasksListShown.current
                        val tasksQueryVisible = active && pageVisible && tasksListShown
                        LaunchedEffect(tasksQueryStore, tasksQueryVisible) {
                            tasksQueryStore?.setVisible(tasksQueryVisible)
                            if (tasksQueryVisible) tasksQueryStore?.start()
                        }
                        DisposableEffect(tasksQueryStore) {
                            onDispose { tasksQueryStore?.setVisible(false) }
                        }
                        val tasksQueryState = tasksQueryStore?.let { store -> store.state.collectAsState().value }
                        SharedTasksScreen(
                            openTaskId =
                                when {
                                    tasksDetailColumnOpen -> tasksDetailColumnTaskId
                                    tasksPendingColumnOpen -> tasksPendingSurface?.task?.id?.value
                                    else -> null
                                },
                            canWriteTaskChannel = tasksChannelWriteCheck,
                            query =
                                // A server without the board query (before slock v1.21.63): the app's own list.
                                if (tasksQueryStore != null && tasksQueryState != null && !tasksQueryState.unsupported) {
                                    SharedTasksQueryBinding(tasksQueryState) { intent ->
                                        prewarmAdmissionGate.invalidate("tasks_query_intent:${intent::class.simpleName}")
                                        tasksQueryStore.dispatch(intent)
                                    }
                                } else {
                                    null
                                },
                            // The page's folds, kept for this route's server (web tasksPageStore, slock eb63aec86e).
                            folds = tasksRoute.folds,
                            state = tasksState,
                            onIntent = { intent ->
                                prewarmAdmissionGate.invalidate("tasks_intent:${intent::class.simpleName}")
                                tasksViewModel.dispatch(intent)
                            },
                            scrollToTopRequest = tabScrollToTopRequests.tasks,
                            bottomContentPadding = appShellBottomContentInset,
                            draftProjection = draftProjection,
                            initialListPosition = tasksListPosition,
                            onListPositionSnapshot = {
                                prewarmAdmissionGate.invalidate("tasks_scroll")
                                tasksListPosition = it
                            },
                            identityOptions = tasksServerMembersEntry.members,
                            timeContext = threadTimeContext,
                            onOpenTask = { task ->
                                prewarmAdmissionGate.invalidate("tasks_open_task")
                                val serverId = readyServerId?.takeIf { it.isNotBlank() }
                                launchTasksTabTaskOpen(
                                    task = task,
                                    serverId = serverId,
                                    tasksDataStore = sharedStores.tasksDataStore,
                                    tasksDataSource = tasksDataSource,
                                    gate = tasksOpenGate,
                                    scope = appShellCoroutineScope,
                                    isStillActive = {
                                        tasksTabMounted.value &&
                                            railInlineSecondaryDestination == null &&
                                            latestTasksOpenServerId == serverId &&
                                            latestTasksTabActive &&
                                            latestTasksPageVisible
                                    },
                                    openSurface = { surface ->
                                        when (surface) {
                                            is TaskOpenSurface.Pending -> {
                                                // The rule a chat opens it by too: the page, but on the wide layout.
                                                if (!taskPendingOpensAsPage(desktopModeOnLargeScreen, latestTasksAdaptivePane)) {
                                                    bridge.log("tasks_open_surface kind=Pending task=${surface.task.id.value} layout=dual_pane")
                                                    if (!tasksOpenSurfaceFlow.sending) {
                                                        if (latestTasksDetailColumnOpen) closeTasksDetailColumn()
                                                        tasksDetailColumnExpanded = false
                                                        tasksPendingSurfaceServerId = latestTasksOpenServerId
                                                        tasksOpenSurfaceFlow.open(surface)
                                                    }
                                                } else {
                                                    bridge.log("tasks_open_pending_page task=${surface.task.id.value}")
                                                    pager.openTaskPendingPage(
                                                        task = surface.task,
                                                        serverId = latestTasksOpenServerId,
                                                        storeScope = navigationStoreScope,
                                                        foregroundBannerStore = foregroundBannerStore,
                                                        timeContext = threadTimeContext,
                                                        alignOwnMessagesRight = myMessageAlignment == MyMessageAlignmentPreference.Right,
                                                        compactConsecutiveMessages = compactConsecutiveMessages,
                                                        canWriteChannel = tasksChannelWriteCheck(surface.task.channelId),
                                                    )
                                                }
                                            }
                                            is TaskOpenSurface.Legacy -> {
                                                bridge.log("tasks_open_surface kind=Legacy task=${surface.task.id.value}")
                                                tasksOpenSurfaceFlow.open(surface)
                                            }
                                        }
                                    },
                                    openPage = { openedTask, conversationId -> openTaskPage(openedTask, conversationId) },
                                    onLoadError = { error -> bridge.errorToast(tasksOpenFailedLabel, error) },
                                    onNoPermission = { blocker -> bridge.errorToast(tasksOpenFailedLabel, blocker) },
                                    onNotFound = { bridge.toast(tasksOpenFailedLabel) },
                                    log = bridge::log,
                                )
                            }
                        )
                        }
                        }
                    }
                    // task #13: shared list-detail host — the Activity tab renders the same
                    // split (360dp list + workspace detail) the Home tab uses, driven by the
                    // shell-wide adaptiveWorkspaceStore. The detail host lives in this stable
                    // slot across wide/compact transitions so the native child controller,
                    // draft and scroll state are retained rather than remounted by branch
                    // movement; task #22 lifts only the detail slot above the floating dock.
                    val adaptiveSplitListDetail:
                        @Composable
                        (
                            enabled: Boolean,
                            active: Boolean,
                            listPane: @Composable (Boolean) -> Unit,
                            detailAccepted: Boolean,
                            defaultLandingEligible: Boolean,
                            listKind: RaftListPaneKind,
                            fillListWhenNothingPicked: Boolean,
                            collapseColumn: RaftCollapsibleColumn?,
                            overrideDetailPane: (@Composable (Modifier) -> Unit)?,
                        ) -> Unit = { enabled, active, listPane, detailAccepted, defaultLandingEligible, listKind, fillListWhenNothingPicked, collapseColumn, overrideDetailPane ->
                        val workspace = adaptiveWorkspaceState
                        val selection =
                            raftAdaptiveSelectionForReadyServer(
                                workspace = workspace,
                                readyServerId = readyServerId,
                                detailAccepted = detailAccepted,
                            )
                        val hasSelection = overrideDetailPane != null || selection != null
                        val detailPane: @Composable (Modifier) -> Unit = { modifier ->
                            if (overrideDetailPane != null) {
                                overrideDetailPane(modifier)
                            } else {
                                adaptiveDetailPane(modifier, active, detailAccepted, true, defaultLandingEligible)
                            }
                        }
                        if (enabled) {
                            // task #37 / artin task #20: per-kind list width and the split live in
                            // RaftAdaptiveListDetailSplit.kt.
                            // task #243: fall back to adaptivePane when workspace is still null
                            // (Frame 1 / CachedRestorePending) so wide layouts render split panes
                            // immediately in lockstep with navRailActive and tasksDetailColumnOpen.
                            val dualPane =
                                (workspace?.pane ?: adaptivePane).layout == RaftAdaptivePaneLayout.DualPane
                            RaftAdaptiveListDetailSplit(
                                kind = listKind,
                                widths = adaptiveListPaneWidths,
                                dualPane = dualPane,
                                active = active,
                                hasSelection = hasSelection,
                                detailBottomInset = shellScope.embeddedChildBottomInset(showAppShellBottomTabs),
                                listPane = listPane,
                                detailPane = detailPane,
                                fillListWhenNothingPicked = fillListWhenNothingPicked,
                                collapsed = collapseColumn != null && sidebarCollapse.isHidden(collapseColumn),
                                // The handle's collapse (web `snap` "collapse"; by touch only on a deliberate release): only where the rail's toggle can bring it back.
                                onCollapsedChange =
                                    collapseColumn?.takeIf { it == collapsibleColumn }?.let { column ->
                                        { hidden ->
                                            sidebarCollapse.setHidden(column, hidden)
                                            bridge.log("app_shell_sidebar_toggle column=$column hidden=$hidden from=handle")
                                        }
                                    },
                                // Web: Activity's list with an item open is at most half the row, so the
                                // conversation or Artifact is never narrower than the list (stdrc).
                                maxShare = 0.5f.takeIf { collapseColumn == RaftCollapsibleColumn.ActivityList && hasSelection },
                                // #337: on a portrait tablet the list collapses while it would squeeze the
                                // conversation under 360 beside the columns open right of it (not saved).
                                onGivesWayChange =
                                    collapseColumn?.let { column ->
                                        { givesWay ->
                                            if (givesWay != sidebarCollapse.isGivingWay(column)) {
                                                sidebarCollapse.setGivingWay(column, givesWay)
                                                bridge.log("app_shell_sidebar_gives_way column=$column givesWay=$givesWay")
                                            }
                                        }
                                    },
                                onResizingChange = { adaptiveListPaneResizing = it },
                                log = { bridge.log(it) },
                            )
                        } else {
                            listPane(active)
                        }
                    }
                    val homeTabContentBase:
                        @Composable
                        (
                            active: Boolean,
                            kindFilter: ConversationKind?,
                            scrollRequest: Int,
                            positions: AppShellServerLazyListPositions,
                            onPositionsSnapshot: (AppShellServerLazyListPositions) -> Unit
                        ) -> Unit = { active, kindFilter, scrollRequest, positions, onPositionsSnapshot ->
                        val homeListServerId =
                            appShellHomeListServerId(
                                renderedServerId = renderedHomeServerId,
                                readyServerId = readyServerId
                            )
                        val homeListPositionServerId = readyServerId ?: homeListServerId
                        // Server flags, read per server like the web `useServerFeatureFlag`.
                        val homeFeatureFlagRevision by sharedStores.serverFeatureFlags.snapshotRevision.collectAsState()
                        // agent_lounge_v0 (web useAgentLoungeEnabled): flag on and not a Guest.
                        val homeAgentLoungeEnabled =
                            remember(homeListServerId, homeFeatureFlagRevision, homeState) {
                                val flagServerId = homeListServerId?.takeIf { it.isNotBlank() }
                                val role =
                                    (homeState.content as? SharedHomeContentState.Content)?.baseSnapshot?.server?.role
                                flagServerId != null &&
                                    agentLoungeEnabled(
                                        sharedStores.serverFeatureFlags.isEnabled(ServerId(flagServerId), AGENT_LOUNGE_FEATURE_FLAG_KEY),
                                        role
                                    )
                            }
                        // drafts_section_v0 (web DRAFTS_SECTION_FLAG_KEY): the Drafts row under Saved.
                        val homeDraftsSource =
                            remember(homeListServerId, homeFeatureFlagRevision, appShellNetworkClient) {
                                val flagServerId = homeListServerId?.takeIf { it.isNotBlank() }
                                if (
                                    flagServerId != null &&
                                        sharedStores.serverFeatureFlags.isEnabled(
                                            ServerId(flagServerId),
                                            DRAFTS_SECTION_FEATURE_FLAG_KEY
                                        )
                                ) {
                                    RaftDraftsSource(appShellNetworkClient, sharedStores.tasksDataStore)
                                } else {
                                    null
                                }
                            }
                        // The phone Home's Assistant row (web `mobileInline` only; the wide layout has the rail).
                        // The layout decides, not the "Desktop mode on large screens" setting: that is on by
                        // default on every device, a phone included, and a phone has no rail.
                        val homeAssistantEntry =
                            rememberAppShellHomeAssistantEntry(
                                phone = appShellHomeCarriesAssistantRow(navRailActive),
                                sharedStores = sharedStores,
                                storeScope = storeScope,
                            ) { pager.openAssistantPage(navigationStoreScope, threadTimeContext) }
                        val prefersChineseCommunity =
                            localePreferenceStore.readLocaleSync() == AppLocale.SimplifiedChinese
                        PerformanceSceneEffect(bridge, "kuikly_home", active = active)
                        val workspace = adaptiveWorkspaceState
                        val selection = workspace?.selection
                        val homeAssistantAnswers by
                            remember(sharedStores, storeScope) { sharedStores.myAssistantStore(storeScope) }.answers.collectAsState()
                        val homePersonalAssistantAgentIds =
                            remember(homeAssistantAnswers) {
                                homeAssistantAnswers.values.mapNotNull { it.assistant?.agentId }.toSet()
                            }
                        val homeListPane: @Composable (Boolean) -> Unit = { listActive ->
                            SharedHomeScreen(
                                bridge = bridge,
                                state = homeState,
                                active = listActive,
                                scrollToTopRequest = scrollRequest,
                                conversationKindFilter = kindFilter,
                                sessionReady =
                                    authoritativeHomeStartAllowed &&
                                        readyServerId?.isNotBlank() == true,
                                draftPersistSlice = composerDraftPersistSlice,
                                conversationIdentityProvider = sharedStores.conversationIdentityProvider(storeScope),
                                activityUnreadCount = activityUnreadSummary.totalUnreadCount,
                                activityTotalCount = activityUnreadSummary.totalCount,
                                serverActivityExpectedOwner = serverActivityExpectedOwner,
                                savedCount = homeSearchSavedState.saved.totalCount,
                                prefersChineseCommunity = prefersChineseCommunity,
                                serverDrawerEnabled = serverDrawerEnabled,
                                // The workspace avatar is always in the Home header (no longer a setting, 1.27.0).
                                homeServerAvatarEnabled = true,
                                // artin task #8: on a wide screen the top bar holds the server.
                                serverSwitcherInTopBar = navRailActive,
                                // The phone bar or the wide rail carries Search and Activity (nav
                                // spec §5): the list keeps only Saved, never a second copy.
                                searchAndActivityOnShellNav = appShellNavCarriesSearchAndActivity(navRailActive),
                                agentLoungeEnabled = homeAgentLoungeEnabled,
                                agentLoungeNetworkClient = appShellNetworkClient,
                                personalAssistantAgentIds = homePersonalAssistantAgentIds,
                                onIntent = { intent ->
                                    prewarmAdmissionGate.invalidate("home_intent:${intent::class.simpleName}")
                                    if (intent is SharedHomeIntent.SelectServer) {
                                        pendingHomeServerSwitchId =
                                            intent.serverId.takeIf { it != homeListServerId }
                                        observedHomeServerSwitchId = null
                                        bridge.performanceMarker(
                                            phase = "home_server_switch_select",
                                            routeName = "kuikly_home",
                                            event = "instant",
                                            metadata =
                                                mapOf(
                                                    "from" to homeListServerId.orEmpty(),
                                                    "to" to intent.serverId
                                                )
                                        )
                                        readySession
                                            ?.withAppShellSelectedServer(intent.serverId)
                                            ?.let { session ->
                                                authGateViewModel.dispatch(SharedAuthGateIntent.ServerReady(session))
                                                bridge.performanceMarker(
                                                    phase = "home_server_switch_session_projected",
                                                    routeName = "kuikly_home",
                                                    event = "instant",
                                                    metadata = mapOf("serverId" to intent.serverId)
                                                )
                                            }
                                    }
                                    homeShellStore.dispatch(SharedHomeShellIntent.Home(intent))
                                    if (intent is SharedHomeIntent.SelectServer) {
                                        bridge.performanceMarker(
                                            phase = "home_server_switch_store_dispatched",
                                            routeName = "kuikly_home",
                                            event = "instant",
                                            metadata = mapOf("serverId" to intent.serverId)
                                        )
                                    }
                                },
                                onNotificationDismiss = { id ->
                                    homeShellStore.dispatch(SharedHomeShellIntent.DismissNotification(id))
                                },
                                onAppUpdateOpen = {
                                    pager.openAppUpdatePage(storeScope)
                                },
                                onOpenReleaseNotes = { versionCode ->
                                    pager.openReleaseNotesPage(versionCode = versionCode, storeScope = storeScope)
                                },
                                externalFocusLossDismissEpoch = homeFocusLossDismissEpoch,
                                selectedConversationId =
                                    if (desktopModeOnLargeScreen) adaptiveWorkspaceSession.detailListSelectionId()
                                    else resolveRaftHomeSelectedConversationId(
                                        adaptiveWorkspaceEnabled = false,
                                        adaptiveSelection = selection,
                                        shellSelectedConversationId = selectedHomeConversationId,
                                        homeSelectedConversationId = homeState.selectedConversationId,
                                    ),
                                onSelectedConversationCleared = {
                                    homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(null))
                                },
                                onOpenServerInSeparateWindow = { serverId ->
                                    openServerInSeparateWindowIfEnabled(serverId, source = "home_switcher", keepTab = selectedTab)
                                },
                                onOpenSearch = {
                                    openSearchTab(source = "home_head")
                                },
                                onOpenActivity = {
                                    openHomeSecondaryDestination(HomeDestinationActivity)
                                },
                                onOpenSaved = {
                                    RaftProductEvents.navEntrySelected(
                                        destination = RaftNavDestination.Saved,
                                        surface =
                                            if (desktopModeOnLargeScreen) {
                                                RaftNavSurface.Sidebar
                                            } else {
                                                RaftNavSurface.MobileHomeShortcuts
                                            },
                                        wasActive =
                                            desktopModeOnLargeScreen &&
                                                railInlineSecondaryDestination == AppShellRailSavedKey,
                                    )
                                    if (desktopModeOnLargeScreen) {
                                        // Large screens keep the rail and open Saved as the
                                        // list pane with the shared detail beside it.
                                        railInlineSecondaryDestination = AppShellRailSavedKey
                                        bridge.log("app_shell_rail_secondary_open destination=saved")
                                    } else {
                                        openHomeSecondaryDestination(HomeDestinationSaved)
                                    }
                                },
                                draftsSource = homeDraftsSource,
                                onOpenDrafts = {
                                    if (desktopModeOnLargeScreen) {
                                        // As Saved: the wide rail opens Drafts as the list pane.
                                        railInlineSecondaryDestination = AppShellRailDraftsKey
                                        bridge.log("app_shell_rail_secondary_open destination=drafts")
                                    } else {
                                        pager.openHomeDraftsPage(threadTimeContext, navigationStoreScope)
                                    }
                                },
                                assistantEntry = homeAssistantEntry,
                                onOpenComputers = {
                                    selectAppShellTab("settings")
                                    retainedAppShellTabs = retainedAppShellTabs + "settings"
                                    openComputersPage()
                                },
                                onOpenComputer = { machineId ->
                                    selectAppShellTab("settings")
                                    retainedAppShellTabs = retainedAppShellTabs + "settings"
                                    val identity = settingsComputerRouteIdentity(readyServerId, machineId)
                                    if (identity == null) {
                                        openComputersPage()
                                    } else {
                                        pager.openSettingsComputerPage(storeScope, identity)
                                    }
                                },
                                onOpenComputersAttention = {
                                    selectAppShellTab("settings")
                                    retainedAppShellTabs = retainedAppShellTabs + "settings"
                                    openComputersPageWith(true)
                                },
                                onOpenUrl = settingsCapabilities::openUrl,
                                onOpenServerSelection = {
                                    pager.openAuthChooseServerPage(
                                        storeScope = navigationStoreScope,
                                        documentShell = pager.pageData.params.optBoolean(RAFT_DOCUMENT_SHELL, false)
                                    )
                                },
                                onInviteHuman = { serverId -> serverSwitcherInviteServerId = serverId },
                                onCreateChannel = { openCreateChannelDialog() },
                                // Web SidebarDialogs: Create Channel with External chosen (the Free limit gate inside it).
                                onCreateJointChannel = { openExternalCreateChannelGated() },
                                onOpenConversation = { conversationIdentity ->
                                    openHomeConversation(conversationIdentity, "home")
                                },
                                serverMembersStore = appShellServerMembersStore,
                                channelRepository = channelRepository,
                                jointInviteStore = jointInviteStore,
                                secondFactorRecoveryStore = secondFactorRecoveryStore,
                                topSafeAreaOverride = shellScope.topSafeArea,
                                bottomContentPadding = appShellBottomContentInset,
                                listPositionServerId = homeListPositionServerId,
                                initialListPosition =
                                    appShellLazyListPositionForServer(
                                        currentServerId = homeListPositionServerId,
                                        savedPositions = positions
                                    ),
                                onListPositionSnapshot = { serverId, position ->
                                    prewarmAdmissionGate.invalidate("home_scroll")
                                    onPositionsSnapshot(positions.withPosition(serverId, position))
                                },
                                onContentComposeCommitted = { serverId ->
                                    if (
                                        pendingHomeServerSwitchId == serverId &&
                                        observedHomeServerSwitchId == serverId
                                    ) {
                                        bridge.performanceMarker(
                                            phase = "home_server_switch_compose_committed",
                                            routeName = "kuikly_home",
                                            event = "instant",
                                            metadata = mapOf("serverId" to serverId)
                                        )
                                        pendingHomeServerSwitchId = null
                                        observedHomeServerSwitchId = null
                                    }
                                }
                            )
                        }
                        adaptiveSplitListDetail(
                            desktopModeOnLargeScreen,
                            active,
                            homeListPane,
                            true,
                            selectedHomeDestination == null,
                            RaftListPaneKind.Sidebar,
                            false,
                            RaftCollapsibleColumn.Sidebar,
                            null,
                        )
                    }
                    val homeTabContent: @Composable (active: Boolean) -> Unit = { active ->
                        homeTabContentBase(
                            active,
                            // task #94: in Slack-nav mode the Home tab is channels-only; the DMs
                            // tab owns direct messages. Legacy mode keeps the merged list.
                            if (slackNavMode) ConversationKind.Channel else null,
                            tabScrollToTopRequests.home,
                            homeListPositions,
                            { homeListPositions = it }
                        )
                    }
                    val dmsTabContent: @Composable (active: Boolean) -> Unit = { }
                    // task #94: Activity tab hosts the shared activity inbox surface directly —
                    // the same screen the home secondary Activity destination renders.
                    // task #13: on DualPane the inbox stays as the 360dp left list and the
                    // opened thread renders in the shared workspace detail slot at right —
                    // same canonical-identity OpenDetail path the Home tab uses, so compact
                    // keeps the standalone push behavior unchanged.
                    val activityListPane: @Composable (Boolean, ActivityArrivalVisits) -> Unit = { listActive, arrivalVisits ->
                        SharedHomeActivityScreen(
                            bridge = bridge,
                            authStore = shellAuthStore,
                            routeVisibleTick = pageVisibleTick,
                            active = listActive,
                            onOpenMessage = { request ->
                                activityArtifactState.close()
                                openHomeMessageRequest(
                                    request = request,
                                    source = "activity_tab"
                                )
                            },
                            onClose = null,
                            bottomContentPadding = appShellBottomContentInset,
                            includeTopSafeArea = true,
                            timeContext = threadTimeContext,
                            shellScope = shellScope,
                            storeScope = navigationStoreScope,
                            externalSelectedThreadId =
                                if (activityArtifactState.isOpen) null
                                else adaptiveWorkspaceState?.selection?.identity?.threadId,
                            externalSelectedArtifactId = activityArtifactState.currentArtifactId.takeIf { navRailActive },
                            onOpenArtifact =
                                if (navRailActive) {
                                    { target, scopeOverride, conv ->
                                        openActivityWideArtifactInShell(
                                            state = activityArtifactState,
                                            target = target,
                                            scopeOverride = scopeOverride,
                                            fallbackScope = navigationStoreScope,
                                            conversationId = conv,
                                            sharedStores = sharedStores,
                                            adaptiveWorkspaceStore = adaptiveWorkspaceStore,
                                            releaseLease = ::releaseAdaptiveHandoffLease,
                                            clearSelectedConversation = {
                                                homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(null))
                                            },
                                        )
                                    }
                                } else {
                                    null
                                },
                            // The two-pane layout owns the highlight: none once the detail closes.
                            externalSelectionOwned = navRailActive,
                            // Activity's own open, not Chat's conversation left in the slot (RaftRailPlaces.kt).
                            externalSelectionOpenedHere = railSelectionOpenedOn == "activity",
                            mainTab = true,
                            scrollToTopRequest = tabScrollToTopRequests.activity,
                            arrivalVisits = arrivalVisits,
                        )
                    }
                    val activityTabContent: @Composable (active: Boolean) -> Unit = { active ->
                        PerformanceSceneEffect(bridge, "kuikly_activity", active = active)
                        // mobile #193: the visit outlives the list, which leaves composition while
                        // collapsed beside an open conversation; Back that closes the conversation
                        // brings the list back without reopening it.
                        val activityArrivalVisits = rememberActivityArrivalVisits(active)
                        val activityArtifactDetail: (@Composable (Modifier) -> Unit)? =
                            if (navRailActive && activityArtifactState.isOpen) {
                                { detailModifier ->
                                    RaftActivityArtifactDetailPane(
                                        state = activityArtifactState,
                                        active = active,
                                        modifier = detailModifier,
                                    )
                                }
                            } else {
                                null
                            }
                        adaptiveSplitListDetail(
                            desktopModeOnLargeScreen,
                            active,
                            { listActive -> activityListPane(listActive, activityArrivalVisits) },
                            true,
                            false,
                            // Web Activity is two columns at every desktop width (slock #10842).
                            RaftListPaneKind.List,
                            false,
                            // Collapsible like the sidebar, remembered on its own (web activityList).
                            RaftCollapsibleColumn.ActivityList,
                            activityArtifactDetail,
                        )
                    }
                    // Members and Settings are server pages pushed from the server panel on the phone
                    // layout (raft-design #712): their header gets a back button to the page the panel
                    // opened them from (Home after a deep link). On the wide rail they are rail roots.
                    val moreChildBack: (() -> Unit)? =
                        appShellMoreChildBackTarget(selectedTab, navRailActive, serverPageOrigin)?.let { target ->
                            {
                                selectAppShellTab(target, directChildSwitch = true, source = AppShellTabSwitchSource.Back)
                                retainedAppShellTabs = retainedAppShellTabs + target
                                serverTabFocusPending = true
                            }
                        }
                    val membersTabContent: @Composable (active: Boolean) -> Unit = { active ->
                        // On the Navigation 3 stack (AppShellServerStackPage.kt) Back closes the page
                        // and no bottom bar sits under it.
                        val stackPageClose = LocalAppShellServerStackPageClose.current
                        SharedMembersScreen(
                            onBack = stackPageClose ?: moreChildBack,
                            bridge = bridge,
                            storeScope = navigationStoreScope,
                            sharedStores = sharedStores,
                            loadInviteWebBaseUrl = {
                                (settingsCapabilities.loadDeveloper() as? DataResult.Content)?.data?.webUrl
                            },
                            session = readySession,
                            memberRepository = memberRepository,
                            settingsRepository = settingsRepository,
                            createAgentCoordinator = createAgentCoordinator,
                            createAgentFormStore = createAgentFormStore,
                            computersStore = appShellComputersStore,
                            agentDetailStore = appShellAgentDetailStore,
                            agentPanelTabOrderStore = sharedStores.agentPanelTabOrderStore,
                            serverMembersStore = appShellServerMembersStore,
                            active = active,
                            membersRefreshKey = membersListRefreshKey,
                            initialContent =
                                sharedMembersCachedContentForServer(membersListCachedContent, readyServerId),
                            onContentLoaded = { membersListCachedContent = it },
                            onRetry = {
                                prewarmAdmissionGate.invalidate("members_retry")
                                membersListRefreshKey += 1
                            },
                            timeContext = threadTimeContext,
                            bottomContentPadding =
                                if (stackPageClose != null) raftBottomSafeAreaPadding() + RaftAppSpacing.Lg else appShellBottomContentInset,
                            scrollToTopRequest = tabScrollToTopRequests.members,
                            initialListState = membersListState,
                            onListStateSnapshot = {
                                prewarmAdmissionGate.invalidate("members_scroll")
                                membersListState = it
                            },
                            paneLayout = shellScope.adaptivePane.layout,
                            desktopMembersEnabled = desktopModeOnLargeScreen,
                            detailSelection = membersDetailSelection,
                            detailOpenRequest = wideNavMemberOpenRequest,
                            onDetailOpenRequestHandled = { wideNavMemberOpenRequest = null },
                            onForegroundActivity = { reason ->
                                prewarmAdmissionGate.invalidate("members_$reason")
                            },
                            // Web: the profile's DM button goes to the DM in Chat, beside the rail.
                            openDirectMessageInShell =
                                if (navRailActive) {
                                    { conversation ->
                                        onAppShellTabSelected("home")
                                        openHomeConversation(conversation, "members_direct_message")
                                        true
                                    }
                                } else {
                                    null
                                },
                        )
                    }
                    val settingsSession = (authGateState as? SharedAuthGateState.Ready)?.session
                    val settingsTabContent: @Composable (active: Boolean) -> Unit = { active ->
                        // IM Bridges row: the server's slack_bridge_v0, live like the web
                        // `useServerFeatureFlag` + `isSlackBridgeSurfaceEnabled` (off until resolved on).
                        val settingsFeatureFlagRevision by sharedStores.serverFeatureFlags.snapshotRevision.collectAsState()
                        val imBridgesEnabled =
                            remember(readyServerId, settingsFeatureFlagRevision) {
                                settingsImBridgesEnabled(sharedStores.serverFeatureFlags, readyServerId)
                            }
                        // Labs row: the server's server_labs_ui_v0 (web `labsUiEnabled`).
                        val labsEnabled =
                            remember(readyServerId, settingsFeatureFlagRevision) {
                                settingsLabsEnabled(sharedStores.serverFeatureFlags, readyServerId)
                            }
                        // Search settings (raft-design settings.md#searching-settings): the query
                        // stays while Settings stays on one server; who can find what follows the
                        // role and the server flags the pages read themselves.
                        val settingsSearchState = rememberSettingsSearchState(readyServerId)
                        // Replies in threads I follow is findable only while its row shows: the
                        // loaded account settings carry the field (none before they load).
                        val settingsNotificationAccount by
                            build.raft.app.data.RaftNotificationAccountSettingsStore.state.collectAsState()
                        val settingsNotificationSettings = settingsNotificationAccount.settings
                        val settingsSearchContext =
                            remember(settingsSession, readyServerId, settingsFeatureFlagRevision, settingsNotificationSettings) {
                                settingsSearchContext(
                                    settingsSession,
                                    sharedStores.serverFeatureFlags,
                                    readyServerId,
                                    notificationSettings = settingsNotificationSettings,
                                )
                            }
                        val settingsSearchServerName = settingsSession?.settingsThemeServer()?.name.orEmpty()
                        if (navRailActive) {
                            // artin 2026-10-29: the wide Settings is list + detail like the web's; the
                            // section opens beside the list (SettingsWideSplit.kt). Feedback is pushed.
                            SettingsWideListDetail(
                                payload =
                                    appShellSettingsHomePayload(settingsSession, imBridgesEnabled = imBridgesEnabled, labsEnabled = labsEnabled)
                                        .withAttentionRows(settingsWideAttentionRowKeys(moreAttention)),
                                widths = adaptiveListPaneWidths,
                                active = active,
                                picked = settingsWidePicked,
                                onPick = { key ->
                                    when (val pick = settingsWidePick(key)) {
                                        is SettingsWidePick.Detail -> {
                                            if (pick.key == SettingsDestinationComputers) {
                                                writeComputerAttentionSeen(
                                                    build.raft.app.data.raftComputerAttentionMarkSeen(computerAttentionSeen, currentComputerAttention)
                                                )
                                            }
                                            bridge.log("settings_wide_pick row=$key")
                                            settingsWidePicked = pick.key
                                            settingsWideAnchor = null
                                        }
                                        SettingsWidePick.Push -> openSettingsRow(key)
                                    }
                                },
                                onBackToDefault = {
                                    bridge.log("settings_wide_back from=$settingsWidePicked")
                                    settingsWidePicked = null
                                    settingsWideAnchor = null
                                },
                                search =
                                    SettingsSearchHost(
                                        state = settingsSearchState,
                                        context = settingsSearchContext,
                                        serverName = settingsSearchServerName,
                                        active = active,
                                        selectedId = settingsWideAnchor?.takeIf { it.first == settingsWidePicked }?.second?.id,
                                        onOpen = { setting ->
                                            val anchor = SettingsSearchAnchorRequest(setting.id, ++settingsSearchOpenNonce)
                                            bridge.log("settings_search_open id=${setting.id} row=${setting.rowKey} mode=wide")
                                            when (val pick = settingsWidePick(setting.rowKey)) {
                                                is SettingsWidePick.Detail -> {
                                                    settingsWidePicked = pick.key
                                                    settingsWideAnchor = pick.key to anchor
                                                }
                                                SettingsWidePick.Push -> openSettingsRowAt(setting.rowKey, anchor)
                                            }
                                        },
                                        onSearchEverywhere = { query -> openSearchEverywhere(query, "settings_search_everywhere") },
                                    ),
                                detailBottomInset = shellScope.embeddedChildBottomInset(showAppShellBottomTabs),
                                bottomContentPadding = appShellBottomContentInset,
                                collapsed = sidebarCollapse.isHidden(RaftCollapsibleColumn.Sidebar),
                                onCollapsedChange =
                                    RaftCollapsibleColumn.Sidebar.takeIf { it == collapsibleColumn }?.let { column ->
                                        { hidden ->
                                            sidebarCollapse.setHidden(column, hidden)
                                            bridge.log("app_shell_sidebar_toggle column=$column hidden=$hidden from=handle")
                                        }
                                    },
                                onResizingChange = { adaptiveListPaneResizing = it },
                                detailPane = { key, modifier ->
                                    val detailAnchor = settingsWideAnchor?.takeIf { it.first == key }?.second
                                    val detailPage =
                                        remember(key, storeScope, detailAnchor) {
                                            settingsWideDetailPage(key, storeScope, detailAnchor)
                                        }
                                    if (detailPage != null) {
                                        RaftChildPage(
                                            pageName = detailPage.first,
                                            pageData = detailPage.second,
                                            modifier = modifier,
                                            active = active,
                                        )
                                    }
                                },
                            )
                        } else {
                        val stackPageClose = LocalAppShellServerStackPageClose.current
                        SettingsAppShellHomeContent(
                            moreRoot =
                                if (navRailActive) {
                                    null
                                } else {
                                    SettingsMoreRoot(
                                        title = settingsTabLabel,
                                        attentionRowKeys = moreAttention.mapTo(mutableSetOf()) { it.settingsRowKey },
                                    )
                                },
                            onBack = stackPageClose ?: moreChildBack,
                            session = settingsSession,
                            imBridgesEnabled = imBridgesEnabled,
                            labsEnabled = labsEnabled,
                            bottomContentPadding =
                                if (stackPageClose != null) raftBottomSafeAreaPadding() + RaftAppSpacing.Lg else appShellBottomContentInset,
                            scrollToTopRequest = tabScrollToTopRequests.more,
                            search =
                                SettingsSearchHost(
                                    state = settingsSearchState,
                                    context = settingsSearchContext,
                                    serverName = settingsSearchServerName,
                                    active = active,
                                    // The page is pushed; Back returns to the results with the query kept.
                                    onOpen = { setting ->
                                        bridge.log("settings_search_open id=${setting.id} row=${setting.rowKey} mode=push")
                                        openSettingsRowAt(setting.rowKey, SettingsSearchAnchorRequest(setting.id, ++settingsSearchOpenNonce))
                                    },
                                    onSearchEverywhere = { query ->
                                        // Settings may be a page over the shell (the server panel's): close it first.
                                        stackPageClose?.invoke()
                                        openSearchEverywhere(query, "settings_search_everywhere")
                                    },
                                ),
                            onSettingsHeaderClick = {
                                prewarmAdmissionGate.invalidate("settings_header")
                                if (settingsHeaderTaps.tap()) {
                                    pager.openSettingsNativeDetailPage(SettingsNativeDetailPage.Developer, storeScope)
                                }
                            },
                            onSelect = openSettingsRow,
                        )
                        }
                    }
                    val currentChildTab = retainedAppShellSelectedTab(selectedTab)
                    val moreTabContent: @Composable (active: Boolean) -> Unit = { active ->
                        PerformanceSceneEffect(bridge, "kuikly_more", active = active)
                        settingsTabContent(active)
                    }
                    // stdrc 2026-10-21 (option B): the phone Search tab. A tab root (no Back, the bar
                    // stays); a result pushes its page over the shell and Back returns here with the
                    // query, results and scroll kept (the tab stays composed like the others).
                    // The field never autofocuses (nav spec: the first switch shows the tab with the bar);
                    // tapping it or re-tapping the selected Search tab focuses it. While the tab is not
                    // shown its field holds neither focus nor the IME.
                    val searchTabContent: @Composable (active: Boolean) -> Unit = { active ->
                        HomeSecondaryRouterContent(
                            destination = HomeDestinationSearch,
                            routeVisibleTick = pageVisibleTick,
                            initialSearchFocusInput = false,
                            searchInputActive = active,
                            searchFocusInputRequest = searchTabFocusInputRequest,
                            searchQueryRequest = searchTabQueryRequest,
                            onSearchQueryApplied = { searchTabQueryRequest = null },
                            adaptivePane = shellScope.adaptivePane,
                            tabRoot = true,
                            hostPager = pager,
                            searchListState = searchTabListState,
                            searchReturnHighlight = searchTabReturnHighlight,
                            searchBottomContentPadding = appShellBottomContentInset,
                        )
                    }
                    val shellRuntimeUsageAccess = LocalMembersRuntimeAccountUsageAccess.current
                    val childTabRenderer: AppShellChildTabRenderer = { tab, active ->
                        val homeSummary = sharedHomeContentRenderSummary(homeState.content)
                        LaunchedEffect(tab, active, currentChildTab, readyServerId, homeSummary, homeState.homeRealtimeDirty) {
                            bridge.log(
                                "app_shell_child_tab_dispatch tab=$tab active=$active selectedTab=$currentChildTab " +
                                    "readyServerId=${readyServerId.orEmpty()} homeState=$homeSummary " +
                                    "homeRealtimeDirty=${homeState.homeRealtimeDirty}"
                            )
                        }
                        CompositionLocalProvider(
                            LocalRaftTimeContextProvider provides hostThreadTimeContextProvider,
                            // Each child tab runs in a RaftChildPage without serialized shell scope in pageData.
                            LocalRaftArtifactOpenStoreScope provides storeScope,
                            LocalMembersRuntimeAccountUsageAccess provides shellRuntimeUsageAccess,
                        ) {
                            when (tab) {
                                "home" -> homeTabContent(active)
                                "tasks" -> tasksTabContent(active)
                                "members" -> membersTabContent(active)
                                "settings" -> settingsTabContent(active)
                                "dms" -> dmsTabContent(active)
                                "activity" -> activityTabContent(active)
                                "more" -> moreTabContent(active)
                                AppShellSearchTabKey -> searchTabContent(active)
                                else -> homeTabContent(active)
                            }
                        }
                    }
                    val childTabRegistration =
                        remember(childTabShellId, slackNavTabKeys) {
                            val entry = AppShellChildTabEntry(
                                debugId = AppShellChildTabRegistry.createEntryId(),
                                timestampDisplayState = timestampDisplayState,
                                initialSelectedTab = currentChildTab,
                                initialParentVisible = pageVisible,
                                initialRetainedTabs = retainedAppShellTabs,
                                initialRenderer = childTabRenderer,
                                // task #94: the render-slot map follows the resolved ordered
                                // keys; a gate flip re-registers the entry with the new set.
                                initialTabKeys = slackNavTabKeys,
                                initialModalHostState = appShellModalHostState
                            )
                            val replaced = AppShellChildTabRegistry.register(childTabShellId, entry)
                            AppShellChildTabRegistration(entry = entry, replacedEntryId = replaced?.debugId ?: 0)
                        }
                    val childTabEntry = childTabRegistration.entry
                    val shellLifecycleOwner = LocalLifecycleOwner.current
                    SideEffect {
                        val parentStarted =
                            pageVisible || shellLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                        childTabEntry.updateSelection(
                            selectedTab = currentChildTab,
                            parentVisible = pageVisible,
                            parentStarted = parentStarted,
                            retainedTabs = retainedAppShellTabs,
                            renderer = childTabRenderer
                        )
                        childTabEntry.modalHostState = appShellModalHostState
                        if (stackShellTabs != null) {
                            stackShellTabs.entry = childTabEntry
                            stackShellTabs.renderer = childTabRenderer
                        }
                    }
                    LaunchedEffect(childTabShellId, childTabEntry.debugId) {
                        bridge.log(
                            "app_shell_child_tab_registry_register shellId=$childTabShellId " +
                                "entryId=${childTabEntry.debugId} replacedEntryId=${childTabRegistration.replacedEntryId} " +
                                "selectedTab=${childTabEntry.selectedTab}"
                        )
                    }
                    DisposableEffect(childTabShellId, childTabEntry) {
                        onDispose {
                            if (stackShellTabs != null && stackShellTabs.entry === childTabEntry) {
                                stackShellTabs.entry = null
                                stackShellTabs.renderer = null
                            }
                            val removed = AppShellChildTabRegistry.unregister(childTabShellId, childTabEntry)
                            bridge.log(
                                "app_shell_child_tab_registry_unregister shellId=$childTabShellId " +
                                    "entryId=${childTabEntry.debugId} removed=$removed"
                            )
                        }
                    }
                    // task #13 three-pane: a rail secondary destination (Search) replaces the
                    // tab host's middle area with its own list pane; the shared workspace
                    // detail slot to its right keeps working via adaptiveSplitListDetail.
                    // Narrow or flag-off never reaches here — the push path handles those.
                    // The inline Search / Saved lists mark the row whose conversation is open in
                    // the detail beside them, like Home and Activity (one selection per list: their
                    // row keys are not shared).
                    val railInlineSearchSelection =
                        remember { RaftInlineListSelection { adaptiveWorkspaceStore.snapshot().workspace?.selection?.identity?.key } }
                    val railInlineSavedSelection =
                        remember { RaftInlineListSelection { adaptiveWorkspaceStore.snapshot().workspace?.selection?.identity?.key } }
                    val railInlineOpenDetailKey = adaptiveWorkspaceState?.selection?.identity?.key
                    SideEffect {
                        railInlineSearchSelection.openDetailKey = railInlineOpenDetailKey
                        railInlineSavedSelection.openDetailKey = railInlineOpenDetailKey
                    }
                    val railInlineDestination =
                        when (railInlineSecondaryDestination) {
                            AppShellRailSearchKey -> HomeDestinationSearch
                            AppShellRailSavedKey -> HomeDestinationSaved
                            AppShellRailDraftsKey -> HomeDestinationDrafts
                            else -> null
                        }
                    if (railInlineDestination != null) {
                        val searchListPane: @Composable (Boolean) -> Unit = { listActive ->
                            HomeSecondaryRouterContent(
                                destination = railInlineDestination,
                                routeVisibleTick = pageVisibleTick,
                                // The field focuses as the inline Search opens (homeSearchPageChrome).
                                initialSearchFocusInput = false,
                                searchFocusInputRequest = railInlineSearchFocusRequest,
                                adaptivePane = shellScope.adaptivePane,
                                searchQueryRequest = searchTabQueryRequest.takeIf { railInlineDestination == HomeDestinationSearch },
                                onSearchQueryApplied = { searchTabQueryRequest = null },
                                inlineListSelection =
                                    if (railInlineDestination == HomeDestinationSearch) {
                                        railInlineSearchSelection
                                    } else {
                                        railInlineSavedSelection
                                    },
                                inlineOpenRequest = { request, source ->
                                    openHomeMessageRequest(request, source)
                                },
                                inlineOnClose = {
                                    // Same ordering as popMainRoute: a mounted detail
                                    // closes before the inline Search destination exits.
                                    // Read the store snapshot, not the compose state: the
                                    // remember-keyed environment may hold this lambda across
                                    // recompositions, so only the store has the live truth.
                                    val liveSelection =
                                        adaptiveWorkspaceStore.snapshot().workspace?.selection
                                    val dismissFirst =
                                        appShellRailSurfaceBackDismissesDetailFirst(
                                            selectedTab = selectedTab,
                                            railInlineSecondaryDestination = railInlineSecondaryDestination,
                                            selectionPresent = liveSelection != null,
                                        )
                                    bridge.log(
                                        "rail_inline_secondary_close destination=$railInlineSecondaryDestination " +
                                            "selectedTab=$selectedTab dismissFirst=$dismissFirst " +
                                            "selection=${liveSelection?.identity?.key.orEmpty()}"
                                    )
                                    if (dismissFirst) {
                                        dismissAdaptiveDetailSelection()
                                    } else {
                                        railInlineSecondaryDestination = null
                                    }
                                },
                                inlineOpenEntity = { entity ->
                                    // Conversation entities open in the shared detail pane;
                                    // computers and people decline and open their page.
                                    entity.homeSearchInlineConversationOrNull()?.let { conversation ->
                                        openHomeConversation(
                                            conversation = conversation,
                                            source = "home_search_entity",
                                        )
                                        true
                                    } ?: false
                                },
                            )
                        }
                        adaptiveSplitListDetail(
                            desktopModeOnLargeScreen,
                            pageVisible,
                            searchListPane,
                            true,
                            false,
                            // Web Search fills the width until a result is picked (searchMasterDetail).
                            RaftListPaneKind.List,
                            true,
                            // Its list stays put (web: Search does not collapse).
                            null,
                            null,
                        )
                    } else if (appShellRailInlinePage(railInlineSecondaryDestination, storeScope) != null) {
                        val inlinePage =
                            remember(railInlineSecondaryDestination, storeScope, threadTimeContext) {
                                appShellRailInlinePage(railInlineSecondaryDestination, storeScope, threadTimeContext)
                            }
                        if (inlinePage != null) {
                            // The page as in the wide Settings pane: its head without Back (the rail
                            // is the way out), under the top bar.
                            CompositionLocalProvider(
                                LocalRaftSettingsWideDetail provides true,
                                LocalRaftSettingsWideDetailClose provides { railInlineSecondaryDestination = null },
                            ) {
                                RaftChildPage(
                                    pageName = inlinePage.first,
                                    pageData = inlinePage.second,
                                    modifier = Modifier.fillMaxSize(),
                                    active = pageVisible,
                                )
                            }
                        }
                    } else {
                    AppShellTabPager(
                        selectedTab = selectedTab,
                        pageVisible = pageVisible,
                        childHostShellId = childTabShellId,
                        mountedTabs = retainedAppShellTabs + prewarmedAppShellTabs,
                        childTabSwitchEpoch = childTabSwitchEpoch,
                        childPageTabKeys = slackNavTabKeys,
                        // Android's phone bottom bar only: the wide rail keeps its instant switch.
                        fadeThrough = raftPlatformAppShellTabFadeThrough() && !navRailActive,
                        log = { bridge.log(it) }
                    ) { tab, active ->
                        CompositionLocalProvider(
                            LocalRaftTimeContextProvider provides hostThreadTimeContextProvider,
                            // Each child tab runs in a RaftChildPage without serialized shell scope in pageData.
                            LocalRaftArtifactOpenStoreScope provides storeScope,
                        ) {
                            when (tab) {
                                "settings" -> settingsTabContent(active)
                                else -> childTabRenderer(tab, active)
                            }
                        }
                    }
                    }
            Box(modifier = Modifier.fillMaxSize()) {
                if (createChannelDialogOpen) {
                    // The server as Home last had it: a moment without Home content (a refresh)
                    // neither drops the External store nor takes External away.
                    val createServerNow = (homeState.content as? SharedHomeContentState.Content)?.snapshot?.server
                    var createServerLast by remember(createChannelSession) { mutableStateOf(createServerNow) }
                    if (createServerNow != null && createServerNow != createServerLast) {
                        SideEffect { createServerLast = createServerNow }
                    }
                    val createServer = createServerNow ?: createServerLast
                    // One store for the whole opening (its emails, servers and created channel),
                    // rebuilt only for another opening or another server.
                    val createExternalStoreForOpening =
                        remember(createChannelSession, createServer?.id) {
                                CreateExternalChannelStore(
                                    appShellCoroutineScope,
                                    RealCreateExternalChannelDataSource(
                                        appShellNetworkClient,
                                        channelRepository,
                                        raftLogger("SlockCreateExternalChannel", "create_external_channel", RaftLogLevel.Warn),
                                    ),
                                )
                        }
                    val createExternalStoreState by createExternalStoreForOpening.state.collectAsState()
                    // Offered while the viewer may create external channels; once the channel exists,
                    // its invite steps go on whatever happens to the role.
                    val createExternalAccess =
                        externalChannelCreateAccess(createServer?.role) || createExternalStoreState.created != null
                    val createExternalStore = createExternalStoreForOpening.takeIf { createExternalAccess }
                    val createExternalState = createExternalStore?.let { createExternalStoreState }
                    val createServerName = createServer?.name.orEmpty()
                    val createChannelNameForToasts = createExternalState?.created?.channel?.name?.removePrefix("#").orEmpty()
                    LaunchedEffect(createExternalState?.effects) {
                        val store = createExternalStore ?: return@LaunchedEffect
                        val pending = createExternalState?.effects.orEmpty()
                        if (pending.isEmpty()) return@LaunchedEffect
                        val toastEffects = mutableListOf<ExternalInvitesEffect>()
                        val finishedChannels = mutableListOf<HomeChannelRow>()
                        pending.forEach { effect ->
                            store.dispatch(CreateExternalChannelIntent.ConsumeEffect(effect))
                            when (effect) {
                                is CreateExternalChannelEffect.CreateFailed -> showCreateChannelRefusal(effect.result)
                                is CreateExternalChannelEffect.Finished -> finishedChannels += effect.channel.channel
                                is CreateExternalChannelEffect.Toast -> toastEffects += effect.effect
                            }
                        }
                        bridge.postExternalInviteToasts(
                            effects = toastEffects,
                            joint = false,
                            channel = "#$createChannelNameForToasts",
                            host = createServerName,
                        )
                        finishedChannels.forEach { channel -> landInCreatedChannel(channel, toast = false) }
                    }
                    SharedCreateChannelDialog(
                        externalStore = createExternalStore,
                        serverName = createServerName,
                        initialVisibility =
                            if (createChannelExternalChosen) SharedCreateChannelVisibility.External else SharedCreateChannelVisibility.Public,
                        onExternalUpgrade = {
                            createChannelDialogOpen = false
                            // Web openUpgradeDialog("external_channel_free_limit", { surface: "create_channel" }).
                            if (settingsBillingOffersUpgrade()) {
                                val snapshot = (homeState.content as? SharedHomeContentState.Content)?.snapshot
                                pager.openRaftUpgradeDialog(
                                    gate = RaftUpgradeGate.ExternalChannelFreeLimit,
                                    storeScope = storeScope,
                                    externalContext =
                                        snapshot?.let { externalSlotUpgradeContext(it.channels, it.server.id.value) },
                                )
                            } else {
                                pager.openSettingsBillingPage(storeScope)
                            }
                        },
                        submitting = createChannelSubmitting,
                        membersLoading = createChannelMembersLoading,
                        members = createChannelMembers,
                        errorMessage = createChannelError,
                        errorTraceId = createChannelErrorTraceId,
                        onExternalCommitStart = {
                            // As the plain create: a new attempt clears the last refusal.
                            createChannelError = null
                            createChannelErrorTraceId = null
                            createChannelCollision = null
                        },
                        archivedCollision = createChannelCollision,
                        onOpenArchivedCollision = { openCreateChannelCollision() },
                        onUnarchiveArchivedCollision = { unarchiveCreateChannelCollision() },
                        onClearArchivedCollision = { createChannelCollision = null },
                        agentStatusResolver = createChannelAgentStatusResolver,
                        onDismiss = {
                            if (!createChannelSubmitting) {
                                createChannelDialogOpen = false
                                createChannelError = null
                                createChannelErrorTraceId = null
                                createChannelCollision = null
                            }
                        },
                        onSubmit = { name, description, visibility, agentMemberIds, userMemberIds ->
                            submitCreateChannel(name, description, visibility, agentMemberIds, userMemberIds)
                        }
                    )
                }
                val minBuildGateDecision by RaftMinBuildGate.decision.collectAsState()
                val announcementPromptSlot =
                    arbitrateRaftAppModalPrompt(
                        minBuildGateBlocking = minBuildGateDecision is RaftMinBuildGateDecision.Block,
                        userDialogActive = createChannelDialogOpen || sendPromptHostState.activeRequest != null,
                        announcementReady = true,
                        appUpdateStrongPromptReady = false,
                    )
                AppShellAnnouncementModalHost(
                    announcementStore = announcementStore,
                    pageVisible = pageVisible,
                    pageVisibleTick = pageVisibleTick,
                    hostDidAppearTick = hostDidAppearTick,
                    isOnline = hostNetworkStatus != RaftAppShellNetworkStatus.Offline,
                    onModalShownChanged = { announcementModalShown = it },
                    suppressedBySiblingDialog = announcementPromptSlot != RaftAppModalPromptSlot.Announcement,
                    onDismissAnnouncement = { announcementId ->
                        if (announcementStore.dismiss(announcementId)) {
                            appShellCoroutineScope.launch {
                                announcementStore.persistDismissal(announcementId)
                            }
                        }
                    },
                    onOpenUrl = { url ->
                        if (announcementOwner == latestAnnouncementOwner) {
                            val announcementWebBase = sharedStores.apiEnvironmentStore.current().webUrl.trimEnd('/')
                            when (
                                val action =
                                    announcementLinkAction(
                                        href = url,
                                        webBaseUrl = announcementWebBase,
                                        currentServerId = readyServerId,
                                        servers = readySession?.servers.orEmpty(),
                                    )
                            ) {
                                is AnnouncementLinkAction.OpenSettings ->
                                    openSettingsFromLink(action.link, action.target, source = "announcement")
                                is AnnouncementLinkAction.SendPrompt ->
                                    sendPromptHostState.open(readyServerId, action.link.prompt)
                                is AnnouncementLinkAction.OpenUrl -> linkNavigator.open(action.url, action.openIntent)
                                null -> Unit
                            }
                        }
                    },
                )
                AppShellUpdatePromptHost(
                    pageVisible = pageVisible,
                    userDialogActive = createChannelDialogOpen || sendPromptHostState.activeRequest != null,
                    announcementStore = announcementStore,
                    announcementModalShown = announcementModalShown,
                    onOpenUpdatePage = { pager.openAppUpdatePage(storeScope) },
                    onCompleteRestart = { updateSnapshot ->
                        triggerAppShellUpdateRestartOrOpenPage(
                            snapshot = updateSnapshot,
                            scope = appShellCoroutineScope,
                            settingsCapabilities = settingsCapabilities,
                            onOpenUpdatePage = { pager.openAppUpdatePage(storeScope) },
                        )
                    },
                    onSnoozed = { appUpdateEvents.notifyStatusChanged() },
                    minBuildGateDecision = minBuildGateDecision,
                )
                AppShellSendPromptHost(
                    hostState = sendPromptHostState,
                    readySession = readySession,
                    readyServerId = readyServerId,
                    homeState = homeState,
                    homeStore = homeStore,
                    serverMembersStore = appShellServerMembersStore,
                    channelRepository = channelRepository,
                    conversationRepository = conversationRepository,
                    onOpenDirectMessage = { conversation ->
                        openHomeMessageRequest(
                            request = HomeMessageOpenRequest(conversation, anchorMessageId = null),
                            source = "send_prompt",
                        )
                    },
                    onLog = bridge::log,
                )
            }
        }
        }
    }

    val mainRouteContent: @Composable () -> Unit = {
        if (serverDrawerEnabled) {
            AppShellServerDrawerHost(
                homeShellStore = homeShellStore,
                bridge = bridge,
                labels = appShellHomeLabels,
                userHeader = serverDrawerUserHeader,
                topSafeArea = shellScope.topSafeArea,
                bottomSafeArea = shellScope.bottomSafeArea,
                serverMembersStore = appShellServerMembersStore,
                serverActivityExpectedOwner = serverActivityExpectedOwner,
                selectedTab = selectedTab,
                modalGestureBlocked =
                    appShellModalHostState.entries.isNotEmpty() ||
                        createChannelDialogOpen ||
                        announcementModalShown ||
                        sendPromptHostState.activeRequest != null,
                dismissLabel = stringResource(Res.string.common_dismiss),
                onServerClick = ::openServerDrawerHome,
                onConversationClick = ::openServerDrawerConversation,
                onProgrammaticCloseFenceChanged = { serverDrawerProgrammaticCloseFence = it },
                content = mainRouteSurface
            )
        } else {
            mainRouteSurface()
        }
    }

    AppShellBannerAndExternalShareHosts(
        foregroundMessageBanner = foregroundMessageBanner,
        shellScope = shellScope,
        appShellServerMembersStore = appShellServerMembersStore,
        sharedStores = sharedStores,
        readySession = readySession,
        foregroundBannerStore = foregroundBannerStore,
        navigationIntake = navigationIntake,
        bridge = bridge,
        openHomeMessageRequestFn = ::openHomeMessageRequest,
        mainRouteContent = mainRouteContent,
        pager = pager,
        shellAuthStore = shellAuthStore,
        appShellNetworkClient = appShellNetworkClient,
        shellInstanceId = storeScope.shellInstanceId,
        channelRepository = channelRepository,
        memberRepository = memberRepository,
        homeStore = homeStore,
        openExternalShareComposerFn = ::openExternalShareComposer,
    )
}

/**
 * The ready server's members entry (the Tasks tab's identity options). Kept out of
 * RaftAppShellReadyContent (JVM method size); the expression is moved verbatim.
 */
@Composable
private fun rememberAppShellTasksServerMembersEntry(
    appShellServerMembersStore: build.raft.app.data.SharedServerMembersStore,
    readyServerId: String?,
): State<SharedServerMembersEntry> =
    remember(appShellServerMembersStore, readyServerId) {
        val serverId = readyServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
        if (serverId == null) {
            MutableStateFlow(SharedServerMembersEntry(serverId = ""))
        } else {
            appShellServerMembersStore.serverMembersEntryFlow(serverId)
        }
    }.collectAsState(
        remember(appShellServerMembersStore, readyServerId) {
            readyServerId?.takeIf { it.isNotBlank() }
                ?.let { appShellServerMembersStore.currentServerMembersEntry(ServerId(it)) }
                ?: SharedServerMembersEntry(serverId = readyServerId.orEmpty())
        }
    )

/**
 * The shell's session resets: a raised auth-session reset generation, an auth error on Home and
 * the SSO-required sign-out. Moved verbatim out of RaftAppShellReadyContent (JVM method size).
 */
@Composable
@NonRestartableComposable
private fun AppShellSessionResetEffects(
    authSessionResetGenerationState: MutableState<Int>,
    appliedAuthSessionResetGenerationState: MutableState<Int>,
    homeState: SharedHomeState,
    authGateStateState: MutableState<SharedAuthGateState>,
    shellAuthStore: build.raft.app.data.RaftAuthSessionStore,
    bridge: build.raft.app.base.BridgeModule,
    authGateViewModel: SharedAuthGateViewModel,
    authGateMessageState: MutableState<AuthGateMessage?>,
    storeScope: RaftShellStoreScope,
    authDataSource: SharedAuthDataSource,
    resetAppShellSessionState: () -> Unit,
) {
    val authSessionResetGeneration by authSessionResetGenerationState
    var appliedAuthSessionResetGeneration by appliedAuthSessionResetGenerationState
    val authGateState by authGateStateState
    var authGateMessage by authGateMessageState
    LaunchedEffect(authSessionResetGeneration) {
        if (authSessionResetGeneration > appliedAuthSessionResetGeneration) {
            appliedAuthSessionResetGeneration = authSessionResetGeneration
            resetAppShellSessionState()
        }
    }

    LaunchedEffect(homeState.content, authGateState) {
        val error = (homeState.content as? SharedHomeContentState.Error)?.code ?: return@LaunchedEffect
        if (!ScreenError(error, retryable = false).isAuthSessionError()) return@LaunchedEffect
        val cachedSession = runCatchingPreservingCancellation { shellAuthStore.readSession() }.getOrNull()
        if (!shouldApplyHomeAuthErrorGate(error, authGateState, cachedSession)) {
            bridge.logWarn(
                "app_shell_home_auth_error_gate_skip code=$error " +
                    "gate=${authGateState.authGateDiagnosticsName()} hasCachedSession=${cachedSession != null}"
            )
            if (authGateState == SharedAuthGateState.AuthRequired && cachedSession != null) {
                authGateViewModel.dispatch(SharedAuthGateIntent.Restore)
            }
            return@LaunchedEffect
        }
        bridge.logWarn("app_shell_home_auth_error_gate code=$error")
        authGateMessage = AuthGateMessage.Static(Res.string.auth_error_sign_in_continue.desc())
        authGateViewModel.dispatch(SharedAuthGateIntent.SignedOut)
        resetAppShellSessionState()
    }

    // An organization requires single sign-on and this session isn't one (a 403 `SSO_REQUIRED` on any
    // request): sign out and ask the person to sign in again, which goes through the web sign-in page
    // and its "Continue with SSO". Once per session; the signal hands each request to one shell only.
    LaunchedEffect(bridge, storeScope) {
        build.raft.app.data.RaftSsoRequiredSignal.Process.signInRequests.collect { request ->
            bridge.logWarn("sso_required_sign_in shell=${storeScope.shellInstanceId}")
            authDataSource.logout()
            bridge.realtimeDisconnect("sso_required")
            authGateMessage =
                AuthGateMessage.Static(Res.string.auth_error_sso_required.desc(), traceId = request.traceId)
            authGateViewModel.dispatch(SharedAuthGateIntent.SignedOut)
            resetAppShellSessionState()
        }
    }
}

/**
 * What the shell shows for every auth gate but Ready, with that gate's effects. Moved verbatim
 * out of RaftAppShellReadyContent (JVM method size): each of those branches still ends in
 * `return`, which now ends this function, so RaftAppShellReadyContent returns right after the
 * call for every gate but Ready.
 */
@Composable
@NonRestartableComposable
private fun AppShellAuthGateContent(
    authGateStateState: MutableState<SharedAuthGateState>,
    cachedHomePreview: Boolean,
    bridge: build.raft.app.base.BridgeModule,
    shellScope: RaftAppShellScope,
    homeState: SharedHomeState,
    suppressRestoreLanding: Boolean,
    adaptivePane: RaftAdaptivePaneState,
    selectedTabState: MutableState<String>,
    authGateMessageState: MutableState<AuthGateMessage?>,
    pager: Pager,
    authDataSource: SharedAuthDataSource,
    authGateViewModel: SharedAuthGateViewModel,
    resetAppShellSessionState: () -> Unit,
) {
    val authGateState by authGateStateState
    val selectedTab by selectedTabState
    var authGateMessage by authGateMessageState
    when (val gate = authGateState) {
        SharedAuthGateState.Restoring -> {
            if (cachedHomePreview) {
                AppShellCachedHomePreviewContent(
                    bridge = bridge,
                    shellScope = shellScope,
                    state = homeState
                )
            } else {
                if (suppressRestoreLanding) {
                    AppShellNewWindowRestoreFrame(adaptivePane = adaptivePane, selectedTab = selectedTab)
                } else {
                    AppShellRestoreSplashContent()
                }
                return
            }
            return
        }
        SharedAuthGateState.AuthRequired -> {
            val localizedAuthGateMessage = authGateMessage.localizedMessage()
            val authGateTraceId = authGateMessage?.traceId
            LaunchedEffect(localizedAuthGateMessage) {
                pager.openLoginReplacingCurrentPage(localizedAuthGateMessage, authGateTraceId)
            }
            AppShellRestoreSplashContent()
            return
        }
        is SharedAuthGateState.BootstrapRecoveryRequired -> {
            QaBootstrapRecoveryEffect(
                session = gate.session,
                authDataSource = authDataSource,
                dispatch = authGateViewModel::dispatch,
            )
            AppShellSilentRestoreContent()
            return
        }
        is SharedAuthGateState.ServerRequired -> {
            AppShellServerGateContent(
                session = gate.session,
                authDataSource = authDataSource,
                onReady = { session ->
                    authGateMessage = null
                    authGateViewModel.dispatch(SharedAuthGateIntent.ServerReady(session))
                    resetAppShellSessionState()
                },
                onSignOut = {
                    authGateMessage = null
                    bridge.realtimeDisconnect("logout")
                    authGateViewModel.dispatch(SharedAuthGateIntent.SignedOut)
                    resetAppShellSessionState()
                }
            )
            return
        }
        is SharedAuthGateState.EmailVerificationRequired -> {
            AppShellEmailVerificationGateContent(
                session = gate.session,
                authDataSource = authDataSource,
                onRefresh = {
                    authGateMessage = null
                    authGateViewModel.dispatch(SharedAuthGateIntent.Restore)
                },
                onSignOut = {
                    authGateMessage = null
                    bridge.realtimeDisconnect("logout")
                    authGateViewModel.dispatch(SharedAuthGateIntent.SignedOut)
                    resetAppShellSessionState()
                }
            )
            return
        }
        is SharedAuthGateState.ProfileSetupRequired -> {
            LaunchedEffect(gate.session.user.id) {
                pager.openAuthProfileSetupReplacingCurrentPage()
            }
            AppShellRestoreSplashContent()
            return
        }
        is SharedAuthGateState.Error -> {
            AppShellAuthErrorContent(
                message = gate.message.localized(),
                onRetry = { authGateViewModel.dispatch(SharedAuthGateIntent.Retry) },
                onSignIn = { authGateViewModel.dispatch(SharedAuthGateIntent.ShowSignIn) },
                onOpenDiagnostics = { pager.openAppShellDeveloper() },
                topBanner = { SignedOutAppUpdateBanner(pager, bridge) }
            )
            return
        }
        is SharedAuthGateState.Ready -> Unit
    }
}

/**
 * The current-server tab's menu model, from the Home switcher's own items. Kept out of
 * RaftAppShellReadyContent (JVM method size); the expression is moved verbatim.
 */
@Composable
private fun rememberAppShellServerMenuModel(
    serverMenuContent: SharedHomeContentState.Content?,
    homeState: SharedHomeState,
    serverActivityExpectedOwner: build.raft.app.data.ReadReceiptOwner?,
    moreAttention: Set<RaftMoreAttention>,
): RaftServerMenuModel? =
        serverMenuContent?.let { content ->
            remember(
                content.session,
                content.snapshot.server,
                homeState.serverUnreadCounts,
                serverActivityExpectedOwner,
                homeState.serverActivityUnread,
                moreAttention,
            ) {
                raftServerMenuModel(
                    servers =
                        sharedHomeSwitcherItems(
                            session = content.session,
                            selectedServer = content.snapshot.server,
                            serverUnreadCounts = homeState.serverUnreadCounts,
                            activityExpectedOwner = serverActivityExpectedOwner,
                            activityProjection = homeState.serverActivityUnread,
                        ),
                    currentServerId = content.snapshot.server.id.value,
                    currentServerName = content.snapshot.server.name,
                    moreAttention = moreAttention,
                )
            }
        }

/**
 * The shell's foreground banner host around the main route, and the external share launch host.
 * Moved verbatim out of RaftAppShellReadyContent (JVM method size). The local functions below
 * keep the moved code's calls as they were: they call the shell's own functions, passed in.
 */
@Composable
@NonRestartableComposable
private fun AppShellBannerAndExternalShareHosts(
    foregroundMessageBanner: RaftForegroundMessageBanner?,
    shellScope: RaftAppShellScope,
    appShellServerMembersStore: build.raft.app.data.SharedServerMembersStore,
    sharedStores: RaftSharedStoreProvider,
    readySession: StoredAuthSession?,
    foregroundBannerStore: SharedForegroundBannerStore,
    navigationIntake: RaftShellNavigationIntake,
    bridge: build.raft.app.base.BridgeModule,
    openHomeMessageRequestFn: (HomeMessageOpenRequest, String, String?) -> Unit,
    mainRouteContent: @Composable () -> Unit,
    pager: Pager,
    shellAuthStore: build.raft.app.data.RaftAuthSessionStore,
    appShellNetworkClient: build.raft.app.data.RaftNetworkClient,
    shellInstanceId: String,
    channelRepository: build.raft.app.data.ChannelRepository,
    memberRepository: build.raft.app.data.MemberRepository,
    homeStore: build.raft.app.data.SharedHomeStore,
    openExternalShareComposerFn: suspend (build.raft.app.data.ExternalShareComposerHandoff) -> Boolean,
) {
    fun openHomeMessageRequest(request: HomeMessageOpenRequest, source: String, fallbackAnchorMessageId: String? = null) =
        openHomeMessageRequestFn(request, source, fallbackAnchorMessageId)

    suspend fun openExternalShareComposer(handoff: build.raft.app.data.ExternalShareComposerHandoff): Boolean =
        openExternalShareComposerFn(handoff)

    Box(modifier = Modifier.fillMaxSize()) {
        RaftForegroundBannerHost(
            banner = foregroundMessageBanner,
            topSafeArea = shellScope.topSafeArea,
            serverMembersStore = appShellServerMembersStore,
            currentServerStore = sharedStores.localDataStore,
            currentServerIdOverride = readySession?.currentServerId?.let(::ServerId),
            onBannerClick = { target ->
                foregroundBannerStore.dismiss(target.id)
                val crossServerSource = target.crossServerSource
                if (crossServerSource != null) {
                    // Another workspace: switch to it first, then open (window navigation).
                    val enqueued =
                        navigationIntake.enqueue(
                            WindowNavigationIntent(
                                serverId = target.serverId,
                                sourceIdentity = crossServerSource,
                                source = "foreground_banner"
                            )
                        )
                    bridge.log("window_navigation_received source=foreground_banner enqueued=$enqueued targetServerId=${target.serverId}")
                } else {
                    openHomeMessageRequest(
                        request = target.openRequest,
                        source = "foreground_banner",
                        fallbackAnchorMessageId = target.focusedMessageId
                    )
                }
            },
            onBannerDismiss = { target -> foregroundBannerStore.dismiss(target.id) }
        ) {
            key("route-main") { mainRouteContent() }
        }
        ExternalShareLaunchHost(
            pageData = pager.pageData.params,
            authStore = shellAuthStore,
            networkClient = appShellNetworkClient,
            authority =
                readySession?.let { session ->
                    ExternalShareLaunchAuthority(
                        accountId = session.user.id,
                        serverId = session.currentServerId.orEmpty(),
                        servers =
                            session.servers.map { server ->
                                build.raft.app.data.ExternalShareServerOption(
                                    id = server.id,
                                    title = server.name,
                                    slug = server.slug,
                                    avatarUrl = server.avatarUrl,
                                    publicPageLive = server.publicPageLive
                                )
                            }
                    )
                },
            bridge = bridge,
            onOpenComposer = ::openExternalShareComposer,
            shellInstanceId = shellInstanceId,
            channelRepository = channelRepository,
            memberRepository = memberRepository,
            cachedHomeSnapshotProvider = { serverId ->
                (homeStore.state.value.content as? SharedHomeContentState.Content)
                    ?.takeUnless { it.isDurableIdentityCache }
                    ?.snapshot
                    ?.takeIf { it.server.id == serverId }
            },
        )
    }
}

private fun AppShellTimeContext?.withTimeFormat(timeFormat: String?): AppShellTimeContext? =
    this?.withTimeFormat(timeFormat)

private fun Pager.openHomeSecondaryPage(
    destination: String,
    timeContext: AppShellThreadTimeContext?,
    storeScope: RaftShellStoreScope
) {
    val retainedDestination = retainedHomeSecondaryDestination(destination) ?: return
    val pageName =
        when (retainedDestination) {
            HomeDestinationActivity -> HOME_ACTIVITY_PAGE
            HomeDestinationSearch -> HOME_SEARCH_PAGE
            HomeDestinationSaved -> HOME_SAVED_PAGE
            else -> return
        }
    val pageData =
        JSONObject().apply {
            put("pageName", pageName)
            put("destination", retainedDestination)
            if (retainedDestination == HomeDestinationSearch) {
                put(HOME_SEARCH_FOCUS_INPUT, true)
            }
            timeContext?.localOffsetMinutes?.let { put("localOffsetMinutes", it) }
            timeContext?.todayEpochDay?.let { put("todayEpochDay", it) }
            timeContext?.nowEpochMillis?.let { put("nowEpochMillis", it) }
            timeContext?.timeFormat?.takeIf { it.isNotBlank() }?.let { put("timeFormat", it) }
        }
    openRaftPage(
        pageName = pageName,
        pageData = pageData,
        storeScopeOverride = storeScope
    )
}

@Composable
private fun AppShellSilentRestoreContent() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(RaftColor.SurfaceWhite)
    )
}

@Composable
private fun AppShellCachedHomePreviewContent(
    bridge: build.raft.app.base.BridgeModule,
    shellScope: RaftAppShellScope,
    state: SharedHomeState
) {
    RaftAppShellSurface(
        shellScope = shellScope,
        bottomTabs = emptyList(),
        onBottomTabSelected = {},
        showBottomTabs = false,
        // Shown only while the stored session is read (Restoring → Ready(CachedRestorePending) is
        // local); no blocking overlay here either.
        interactionLocked = false
    ) {
        SharedHomeScreen(
            bridge = bridge,
            state = state,
            active = false,
            sessionReady = false,
            serverDrawerEnabled = false,
            homeServerAvatarEnabled = false,
            // Match the list the restored shell will show (desktop mode defaults on, so the wide
            // dual-pane layout uses the rail, which carries Search and Activity like the bar).
            searchAndActivityOnShellNav =
                appShellNavCarriesSearchAndActivity(
                    navRailActive = shellScope.adaptivePane.layout == RaftAdaptivePaneLayout.DualPane
                ),
            onIntent = {},
            onNotificationDismiss = {},
            onOpenSearch = {},
            onOpenActivity = {},
            onOpenSaved = {},
            onOpenComputers = {},
            onOpenUrl = {},
            onOpenServerSelection = {},
            onCreateChannel = {},
            onOpenConversation = {},
            topSafeAreaOverride = shellScope.topSafeArea,
            bottomContentPadding = shellScope.contentBottomInset(includeBottomTabBar = false)
        )
    }
}

/**
 * The cold-restore splash with its slow-restore way out (web #15116), out of
 * RaftAppShellReadyContent for JVM method size: Try again is the gate's Retry, Sign out the
 * email-verification gate's sign-out.
 */
@Composable
private fun AppShellColdRestoreSplash(
    authDataSource: SharedAuthDataSource,
    bridge: build.raft.app.base.BridgeModule,
    appShellCoroutineScope: kotlinx.coroutines.CoroutineScope,
    authGateViewModel: SharedAuthGateViewModel,
    authGateMessageState: MutableState<AuthGateMessage?>,
    authSessionResetGenerationState: MutableState<Int>,
) {
    var authGateMessage by authGateMessageState
    var authSessionResetGeneration by authSessionResetGenerationState
    var signingOut by remember { mutableStateOf(false) }
    AppShellSessionRestoreSplash(
        signingOut = signingOut,
        onRetry = {
            authDataSource.logDiagnostics("auth_restore_slow action=retry")
            authGateViewModel.dispatch(SharedAuthGateIntent.Retry)
        },
        onSignOut = {
            if (!signingOut) {
                signingOut = true
                authDataSource.logDiagnostics("auth_restore_slow action=sign_out")
                // The shell's scope, not the splash's: the sign-out finishes even if the restore lands
                // meanwhile and the splash leaves.
                appShellCoroutineScope.launch {
                    try {
                        appShellRestoreSignOut(
                            logout = { authDataSource.logout() },
                            disconnectRealtime = { bridge.realtimeDisconnect("logout") },
                            dispatch = { intent ->
                                authGateMessage = null
                                authGateViewModel.dispatch(intent)
                                // The email-verification sign-out resets the shell's session state; past
                                // this early return it is reset when the shell composes again.
                                authSessionResetGeneration += 1
                            },
                        )
                    } finally {
                        signingOut = false
                    }
                }
            }
        },
    )
}
