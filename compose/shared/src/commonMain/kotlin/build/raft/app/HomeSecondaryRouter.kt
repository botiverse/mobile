package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import build.raft.app.base.Utils
import build.raft.app.data.DEFAULT_ACTIVITY_TASK_STATUS_BADGES_ENABLED
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.DEFAULT_DESKTOP_MODE_ON_LARGE_SCREEN
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.RaftChatLayoutPreferenceEvents
import build.raft.app.data.RaftRealtimeEvents
import build.raft.app.data.MyMessageAlignmentPreference
import build.raft.app.data.NetworkRaftAnnouncementsRemindersApi
import build.raft.app.data.NetworkRaftHomeApi
import build.raft.app.data.PersistentChatLayoutPreferenceRepository
import build.raft.app.data.ReadReceiptCoordinator
import build.raft.app.data.ReadReceiptOwner
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.RealSavedRepository
import build.raft.app.data.RealSearchRepository
import build.raft.app.data.SearchTimeRange
import build.raft.app.data.SharedAdaptiveWorkspaceStore
import build.raft.app.data.SharedComputersIntent
import build.raft.app.data.SharedComputersStore
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.SharedConversationIdentityProvider
import build.raft.app.data.SharedConversationIdentitySupplementSource
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeDataSource
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedHomeSavedStateCache
import build.raft.app.data.SharedHomeSearchSavedIntent
import build.raft.app.data.SharedHomeSearchSavedViewModel
import build.raft.app.data.SharedHomeShellDestinationActivity
import build.raft.app.data.SharedHomeShellDestinationSaved
import build.raft.app.data.SharedHomeShellDestinationSearch
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SharedHomeStore
import build.raft.app.data.SharedHomeViewModel
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftComputersNetworkApi
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.kuiklyBridgeCallContext
import build.raft.app.data.mergedSharedConversationDirectoryState
import build.raft.app.data.parseRaftHome
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.data.typedPinnedOrLegacy
import build.raft.app.model.ServerId
import build.raft.app.model.RaftTimeContext
import build.raft.app.resources.MR
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.compat.pager.Pager
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

internal const val HomeDestinationSearch = SharedHomeShellDestinationSearch
internal const val HomeDestinationActivity = SharedHomeShellDestinationActivity
internal const val HomeDestinationSaved = SharedHomeShellDestinationSaved
internal val homeSecondaryDestinationKeys =
    listOf(HomeDestinationSearch, HomeDestinationActivity, HomeDestinationSaved)

private data class HomeSecondaryRouteEnvironment(
    val pager: Pager,
    val bridge: build.raft.app.base.BridgeModule,
    val authStore: RaftAuthSessionStore,
    val networkClient: RaftNetworkClient,
    val serverMembersStore: SharedServerMembersStore,
    val computersStore: SharedComputersStore,
    val composerDraftPersistSlice: SharedThreadComposerDraftPersistSlice,
    val settingsCapabilities: BridgeSettingsPlatformCapabilities,
    val homeSavedStateCache: SharedHomeSavedStateCache,
    val timeContextProvider: RaftTimeContextProvider,
    val timeContext: AppShellThreadTimeContext?,
    val alignOwnMessagesRight: Boolean,
    val compactConsecutiveMessages: Boolean,
    val foregroundMessageBannersEnabled: Boolean,
    val activityTaskStatusBadgesEnabled: Boolean,
    val activitySenderAvatarsEnabled: Boolean,
    val storeScope: RaftShellStoreScope,
    val hasExplicitShellScope: Boolean,
    val adaptivePane: RaftAdaptivePaneState,
    val shellScope: RaftAppShellScope,
    val conversationIdentityProvider: SharedConversationIdentityProvider?,
    val sharedHomeStore: SharedHomeStore?,
    val readReceiptCoordinator: ReadReceiptCoordinator,
    val readReceiptOwnerFactory: (String, String) -> ReadReceiptOwner,
    // Shell-scoped workspace (resolves to the AppShell's store under the shared scope)
    // so standalone opens while desktop mode is OFF can park the current selection.
    val sharedWorkspaceStore: SharedAdaptiveWorkspaceStore,
    // task #13 three-pane: when the destination is embedded inside the AppShell middle pane
    // (wide LeftRail), conversation/entity opens must route to the shell's adaptive workspace
    // OpenDetail instead of pushing a standalone page (which would cover the rail).
    // Null = legacy standalone behavior (push).
    val inlineOpenRequest: ((HomeMessageOpenRequest, String) -> Unit)? = null,
    val inlineOpenEntity: ((SearchEntityResult) -> Boolean)? = null,
    // When embedded inline there is no pushed page to pop — "close" returns to the host tab.
    val inlineOnClose: (() -> Unit)? = null,
    // stdrc 2026-10-21: Search is a bottom tab on the phone. As a tab root the page has no Back
    // and keeps the bar's inset; its results still push pages (with Back, over the bar).
    val tabRoot: Boolean = false,
)

@Composable
private fun rememberHomeSecondaryRouteEnvironment(
    routeVisibleTick: Int = 0,
    adaptivePane: RaftAdaptivePaneState = RaftAdaptivePaneState(),
    inlineOpenRequest: ((HomeMessageOpenRequest, String) -> Unit)? = null,
    inlineOpenEntity: ((SearchEntityResult) -> Boolean)? = null,
    inlineOnClose: (() -> Unit)? = null,
    tabRoot: Boolean = false,
    hostPager: Pager? = null,
): HomeSecondaryRouteEnvironment {
    RememberKmmResourceLocaleSeedFromLocalActivity()
    // A tab rendered in an app-shell child page reads the shell's page (store scope, time
    // capabilities, page pushes), not the child host's.
    val localPager = LocalActivity.current.getPager() as Pager
    val pager = hostPager ?: localPager
    val bridge = remember(pager) { Utils.bridgeModule(pager.pagerId) }
    val storeScope = remember(pager.pageData.params.toString(), pager.pagerId) {
        pager.pageData.params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
    }
    val hasExplicitShellScope = remember(pager.pageData.params.toString()) {
        pager.pageData.params.raftShellStoreScopeOrNull() != null
    }
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val bridgeCallScope = rememberCoroutineScope()
    val linkNavigator = rememberRaftLinkNavigator(pager, bridge)
    val copiedMessage = stringResource(MR.strings.common_copied)
    val settingsCapabilities =
        remember(bridge, bridgeCallScope, linkNavigator, copiedMessage) {
            BridgeSettingsPlatformCapabilities(
                bridge = bridge,
                copiedMessage = copiedMessage,
                bridgeCallContext = bridgeCallScope.kuiklyBridgeCallContext(),
                openUrlHandler = { url -> linkNavigator.open(url) }
            )
        }
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val networkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val serverMembersStore = remember(sharedStores, storeScope) { sharedStores.serverMembersStore(storeScope) }
    val computersStore = remember(sharedStores, storeScope) { sharedStores.computersStore(storeScope) }
    val sharedPreferenceStore = remember { raftSharedPreferenceStore() }
    val chatLayoutPreferenceRepository =
        remember(sharedPreferenceStore) {
            PersistentChatLayoutPreferenceRepository(KeyValueChatLayoutPreferenceStore(sharedPreferenceStore))
    }
    var myMessageAlignment by remember { mutableStateOf(MyMessageAlignmentPreference.Default) }
    var compactConsecutiveMessages by remember { mutableStateOf(false) }
    var foregroundMessageBannersEnabled by remember { mutableStateOf(false) }
    var activityTaskStatusBadgesEnabled by remember { mutableStateOf(DEFAULT_ACTIVITY_TASK_STATUS_BADGES_ENABLED) }
    var activitySenderAvatarsEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(chatLayoutPreferenceRepository) {
        val chatLayout = chatLayoutPreferenceRepository.getChatLayoutPreference()
        myMessageAlignment = chatLayout.myMessageAlignment
        compactConsecutiveMessages = chatLayout.compactConsecutiveMessages
        foregroundMessageBannersEnabled = chatLayout.foregroundMessageBannersEnabled
        activityTaskStatusBadgesEnabled = chatLayout.activityTaskStatusBadgesEnabled
        activitySenderAvatarsEnabled = chatLayout.activitySenderAvatarsEnabled
    }
    val timeCapabilities =
        remember(pager.pageData.params.toString()) {
            pager.pageData.params.threadTimeCapabilities()
        }
    val timeContextProvider =
        rememberLiveRaftTimeContext(
            capabilities = timeCapabilities,
            pageVisibleTick = routeVisibleTick
        )
    val timeContext =
        remember(timeCapabilities) {
            timeCapabilities?.let {
                RaftTimeContext(
                    localOffsetMinutes = it.localOffsetMinutes,
                    timeFormat = it.timeFormat
                )
            }
        }
    val shellScope = rememberRaftAppShellScope(adaptivePane)
    return remember(
        pager,
        bridge,
        authStore,
        networkClient,
        settingsCapabilities,
        serverMembersStore,
        computersStore,
        sharedStores.composerDraftPersistSlice,
        sharedStores.homeSavedStateCache,
        timeContextProvider,
        timeContext,
        myMessageAlignment,
        compactConsecutiveMessages,
        foregroundMessageBannersEnabled,
        activityTaskStatusBadgesEnabled,
        activitySenderAvatarsEnabled,
        storeScope,
        hasExplicitShellScope,
        adaptivePane,
        shellScope,
        inlineOpenRequest,
        inlineOpenEntity,
        inlineOnClose,
        tabRoot,
    ) {
        HomeSecondaryRouteEnvironment(
            pager = pager,
            bridge = bridge,
            authStore = authStore,
            networkClient = networkClient,
            serverMembersStore = serverMembersStore,
            computersStore = computersStore,
            composerDraftPersistSlice = sharedStores.composerDraftPersistSlice,
            settingsCapabilities = settingsCapabilities,
            homeSavedStateCache = sharedStores.homeSavedStateCache,
            timeContextProvider = timeContextProvider,
            timeContext = timeContext,
            alignOwnMessagesRight = myMessageAlignment == MyMessageAlignmentPreference.Right,
            compactConsecutiveMessages = compactConsecutiveMessages,
            foregroundMessageBannersEnabled = foregroundMessageBannersEnabled,
            activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
            activitySenderAvatarsEnabled = activitySenderAvatarsEnabled,
            storeScope = storeScope,
            hasExplicitShellScope = hasExplicitShellScope,
            adaptivePane = adaptivePane,
            shellScope = shellScope,
            conversationIdentityProvider = sharedStores.conversationIdentityProvider(storeScope),
            sharedHomeStore = sharedStores.sharedHomeStore(storeScope),
            readReceiptCoordinator = sharedStores.processReadReceiptCoordinator(),
            readReceiptOwnerFactory = sharedStores::readReceiptOwner,
            sharedWorkspaceStore = sharedStores.adaptiveWorkspaceStore(storeScope),
            inlineOpenRequest = inlineOpenRequest,
            inlineOpenEntity = inlineOpenEntity,
            inlineOnClose = inlineOnClose,
            tabRoot = tabRoot,
        )
    }
}

@Composable
internal fun HomeSecondaryRouterContent(
    destination: String,
    routeVisibleTick: Int = 0,
    initialSearchChannelId: String? = null,
    initialSearchOpenFilterId: String? = null,
    initialSearchFocusInput: Boolean = false,
    adaptivePane: RaftAdaptivePaneState = RaftAdaptivePaneState(),
    inlineOpenRequest: ((HomeMessageOpenRequest, String) -> Unit)? = null,
    inlineOpenEntity: ((SearchEntityResult) -> Boolean)? = null,
    inlineOnClose: (() -> Unit)? = null,
    tabRoot: Boolean = false,
    hostPager: Pager? = null,
    searchListState: LazyListState? = null,
    searchReturnHighlight: HomeSearchReturnHighlight? = null,
    searchBottomContentPadding: Dp? = null,
    searchInputActive: Boolean = true,
    searchFocusInputRequest: Int = 0,
    searchQueryRequest: RaftSearchTabQueryRequest? = null,
    onSearchQueryApplied: () -> Unit = {},
) {
    val environment = rememberHomeSecondaryRouteEnvironment(
        routeVisibleTick,
        adaptivePane,
        inlineOpenRequest,
        inlineOpenEntity,
        inlineOnClose,
        tabRoot,
        hostPager,
    )
    val retainedDestination = retainedHomeSecondaryDestination(destination)
    PerformanceSceneEffect(
        bridge = environment.bridge,
        sceneName = retainedDestination?.let(::homeSecondaryPerformanceRoute) ?: "kuikly_home_secondary",
        active = true
    )
    CompositionLocalProvider(LocalRaftTimeContextProvider provides environment.timeContextProvider) {
        when (retainedDestination) {
            HomeDestinationActivity -> HomeActivityRouterContent(environment, routeVisibleTick)
            HomeDestinationSearch ->
                HomeSearchRouterContent(
                    environment,
                    routeVisibleTick,
                    initialChannelId = initialSearchChannelId,
                    initialOpenFilterId = initialSearchOpenFilterId,
                    initialFocusInput = initialSearchFocusInput,
                    listState = searchListState,
                    returnHighlight = searchReturnHighlight,
                    bottomContentPadding = searchBottomContentPadding,
                    inputActive = searchInputActive,
                    focusInputRequest = searchFocusInputRequest,
                    queryRequest = searchQueryRequest,
                    onQueryApplied = onSearchQueryApplied
                )
            HomeDestinationSaved -> HomeSavedRouterContent(environment, routeVisibleTick)
            else -> RaftCenteredLoading(message = stringResource(MR.strings.auth_state_opening_server))
        }
    }
}

@Composable
private fun HomeActivityRouterContent(
    environment: HomeSecondaryRouteEnvironment,
    routeVisibleTick: Int = 0
) {
    // task #13: surface-owned adaptive workspace. The pushed Activity page gets its own
    // SharedAdaptiveWorkspaceStore instance — never the shell-scoped singleton — so its
    // selection/detail/lease lifecycle is fully independent of the Home surface it covers.
    // Canonical product state (messages, drafts, permissions) still flows through the
    // existing shared stores; only the surface's selection ownership is private.
    val sharedStores = remember(environment.bridge) { RaftSharedStoreRegistry.get(environment.bridge) }
    val activityWorkspaceStore = remember { SharedAdaptiveWorkspaceStore() }
    // Lease keeps the detail's realtime rooms subscribed while this surface owns the
    // selection; released when the workspace resets or the page disposes. Registry +
    // events are the same shell-scoped shared instances Home uses — the OWNER record is
    // what is surface-private (consumerId names this surface).
    val activityRealtimeEvents = remember(sharedStores) { RaftRealtimeEvents(sharedStores.eventBus) }
    val activityRoomLeaseRegistry =
        remember(sharedStores, environment.storeScope) {
            sharedStores.realtimeRoomLeaseRegistry(environment.storeScope)
        }
    val activityWorkspaceSession by activityWorkspaceStore.state.collectAsState()
    val activityWorkspace = activityWorkspaceSession.workspace
    // The page-level pane state (fed by RaftAdaptivePanePagerEvent on the pushed page's own
    // BasePager) — this is what lets DualPane resolve on a secondary surface; the default
    // shellScope construction never receives those events and would stay compact forever.
    val adaptivePane = environment.adaptivePane
    var activityServerId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(environment.authStore) {
        activityServerId =
            environment.authStore
                .readSession()
                ?.currentServerId
                ?.trim()
                ?.takeIf(String::isNotBlank)
    }
    // task #45: same user gate the Home shell uses — the persisted "desktop mode on
    // large screens" setting (default ON), no pageData param anymore.
    var activityAdaptiveEnabled by remember {
        mutableStateOf(DEFAULT_DESKTOP_MODE_ON_LARGE_SCREEN)
    }
    val chatLayoutPreferenceEvents =
        remember(sharedStores.eventBus) { RaftChatLayoutPreferenceEvents(sharedStores.eventBus) }
    DisposableEffect(chatLayoutPreferenceEvents) {
        val subscription =
            chatLayoutPreferenceEvents.addPreferenceListener { chatLayout ->
                activityAdaptiveEnabled = chatLayout.desktopModeOnLargeScreen
            }
        onDispose { subscription.close() }
    }
    val activityChatLayoutPreferenceStore =
        remember { KeyValueChatLayoutPreferenceStore(raftSharedPreferenceStore()) }
    LaunchedEffect(activityChatLayoutPreferenceStore) {
        activityAdaptiveEnabled =
            activityChatLayoutPreferenceStore.readDesktopModeOnLargeScreen()
    }
    LaunchedEffect(activityWorkspaceStore, activityServerId, adaptivePane, activityAdaptiveEnabled) {
        if (!activityAdaptiveEnabled) return@LaunchedEffect
        val serverId = activityServerId ?: return@LaunchedEffect
        activityWorkspaceStore.update { current ->
            var next =
                current.workspace
                    ?: RaftAdaptiveWorkspaceState(
                        shellInstanceId = environment.storeScope.shellInstanceId + ":activity",
                        serverId = serverId,
                    )
            if (next.serverId != serverId) {
                next = reduceRaftAdaptiveWorkspace(next, RaftAdaptiveWorkspaceIntent.AuthorityChanged(serverId))
            }
            next = reduceRaftAdaptiveWorkspace(next, RaftAdaptiveWorkspaceIntent.WindowChanged(adaptivePane))
            current.copy(workspace = next)
        }
    }
    val activityListPane: @Composable (Boolean) -> Unit = { listActive ->
        SharedHomeActivityScreen(
            bridge = environment.bridge,
            authStore = environment.authStore,
            routeVisibleTick = routeVisibleTick,
            active = listActive,
            onOpenMessage = { request ->
                val handled =
                    if (activityAdaptiveEnabled) {
                        openActivityAdaptiveMessageRequest(
                            environment = environment,
                            workspaceStore = activityWorkspaceStore,
                            workspace = activityWorkspace,
                            request = request,
                            serverId = activityServerId,
                            realtimeEvents = activityRealtimeEvents,
                            roomLeaseRegistry = activityRoomLeaseRegistry,
                        )
                    } else {
                        false
                    }
                if (!handled) {
                    // task #45: OFF keeps the workspace parked, but the standalone open is
                    // the user's current selection — record it so a later ON restores this
                    // conversation instead of a stale pre-OFF one.
                    parkStandaloneSelection(
                        environment = environment,
                        workspaceStore = activityWorkspaceStore,
                        request = request,
                        serverId = activityServerId,
                    )
                }
                if (!handled) {
                    environment.pager.openStandaloneHomeMessageRequest(
                        bridge = environment.bridge,
                        request = request,
                        source = "home_activity",
                        timeContext = environment.timeContext,
                        alignOwnMessagesRight = environment.alignOwnMessagesRight,
                        compactConsecutiveMessages = environment.compactConsecutiveMessages,
                            storeScope = environment.storeScope
                    )
                }
            },
            onClose = { environment.inlineOnClose?.invoke() ?: environment.pager.closeHomeSecondaryPage() },
            bottomContentPadding = environment.shellScope.bottomInset(includeBottomTabBar = false),
            timeContext = environment.timeContext,
            shellScope = environment.shellScope,
            activityTaskStatusBadgesEnabled = environment.activityTaskStatusBadgesEnabled,
            activitySenderAvatarsEnabled = environment.activitySenderAvatarsEnabled,
            refreshOnAppLifecycleResume = true,
            externalSelectedThreadId = activityWorkspace?.selection?.identity?.threadId,
            storeScope = environment.storeScope
        )
    }
    val workspace = activityWorkspace
    val selection = workspace?.selection
    val detailPayload = activityWorkspaceSession.launchPayload
    val dualPane = activityAdaptiveEnabled && workspace?.pane?.layout == RaftAdaptivePaneLayout.DualPane
    val detailPageData =
        remember(
            detailPayload,
            selection,
            environment.storeScope.shellInstanceId,
        ) {
            if (detailPayload == null || selection == null || workspace == null) {
                null
            } else {
                threadLaunchPageData(detailPayload)
                    .withRaftShellStoreScope(environment.storeScope)
                    .put(RAFT_ADAPTIVE_DETAIL_SHELL_ID, workspace.shellInstanceId)
                    .put(RAFT_ADAPTIVE_DETAIL_IDENTITY_KEY, selection.identity.key)
                    .put(RAFT_ADAPTIVE_DETAIL_GENERATION, selection.generation)
            }
        }
    // CloseDetail: the embedded thread's own Back posts RAFT_ADAPTIVE_DETAIL_CLOSE_EVENT
    // carrying this surface's namespaced shell id; only this page reduces it (Home's
    // observer filters on the un-suffixed shell id, so events can't cross).
    DisposableEffect(activityWorkspaceStore, environment.storeScope.shellInstanceId) {
        val activityShellId = environment.storeScope.shellInstanceId + ":activity"
        val subscription =
            sharedStores.eventBus.observe(
                eventName = RAFT_ADAPTIVE_DETAIL_CLOSE_EVENT,
                crossProcess = true,
            ) { eventData ->
                val request = eventData?.toRaftAdaptiveDetailCloseRequestOrNull()
                    ?: return@observe
                if (request.shellInstanceId != activityShellId) return@observe
                val current = activityWorkspaceStore.snapshot().workspace ?: return@observe
                val next =
                    reduceRaftAdaptiveWorkspace(
                        current,
                        RaftAdaptiveWorkspaceIntent.CloseDetail(
                            identityKey = request.identityKey,
                            generation = request.generation,
                        ),
                    )
                if (current.selection != null && next.selection == null) {
                    releaseActivityAdaptiveLease(
                        environment,
                        activityWorkspaceStore,
                        activityRealtimeEvents,
                        activityRoomLeaseRegistry,
                        request.generation,
                    )
                }
                activityWorkspaceStore.update { session ->
                    session.copy(
                        workspace = next,
                        launchPayload =
                            if (current.selection != null && next.selection == null) {
                                null
                            } else {
                                session.launchPayload
                            },
                    )
                }
            }
        onDispose { subscription.close() }
    }
    // Explicit teardown: remember state being dropped is not a resource release — the
    // lease (a shared-registry room subscription) must be returned here so a covered Home
    // never sees Activity's rooms linger.
    DisposableEffect(activityWorkspaceStore) {
        onDispose {
            releaseActivityAdaptiveLease(
                environment,
                activityWorkspaceStore,
                activityRealtimeEvents,
                activityRoomLeaseRegistry,
            )
        }
    }
    // Back ordering (task #13): with a mounted detail the first Back dismisses the detail
    // only; the Activity list/page stays until a second Back. Release this surface's own
    // lease — never the covered Home's.
    // Consume Back whenever a detail is mounted on this surface — DualPane split OR
    // compact DetailOnly (narrowed with a selection). Narrowing must not strand the
    // selection behind the list.
    // task #45: while the desktop-mode setting is OFF the surface must behave as a
    // plain mobile page — a retained workspace selection (kept for the OFF->ON round
    // trip) must neither steal Back nor render the detail slot.
    // A retained split-view selection is page state, not a transient overlay: keep the
    // full-page back swipe (iPad split view would otherwise never pop while a detail is open).
    RaftBackHandler(enabled = activityAdaptiveEnabled && selection != null, blocksBackSwipe = false) {
        val current = activityWorkspaceStore.snapshot().workspace ?: return@RaftBackHandler
        val dismissed =
            reduceRaftAdaptiveWorkspace(current, RaftAdaptiveWorkspaceIntent.DismissDetail)
        val releasedGeneration = current.selection?.generation
        activityWorkspaceStore.update { session ->
            session.copy(workspace = dismissed, launchPayload = null)
        }
        releasedGeneration?.let { generation ->
            releaseActivityAdaptiveLease(
                environment,
                activityWorkspaceStore,
                activityRealtimeEvents,
                activityRoomLeaseRegistry,
                generation,
            )
        }
    }
    // Compact + selection = DetailOnly (same contract as Home): the detail takes the whole
    // surface; the list is retained in the workspace, not remounted. Compact without a
    // selection stays the plain list.
    // Compact + selection = DetailOnly only while desktop mode is ON. With the setting
    // OFF the workspace state is parked, not rendered — the list stays the only surface.
    val detailOnly =
        activityAdaptiveEnabled &&
            !dualPane &&
            selection != null &&
            workspace?.presentation == RaftAdaptiveWorkspacePresentation.DetailOnly
    if (dualPane) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .width(360.dp)
                    .fillMaxHeight(),
            ) {
                activityListPane(true)
            }
            Spacer(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(RaftColor.BorderBlack),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                if (detailPageData != null && selection != null) {
                    RaftChildPage(
                        pageName = THREAD_PAGE,
                        pageData = detailPageData,
                        modifier = Modifier.fillMaxSize(),
                        active = true,
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(MR.strings.home_adaptive_select_conversation),
                            color = RaftColor.TextMutedGray,
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        }
    } else if (detailOnly && detailPageData != null) {
        // Compact + live selection = DetailOnly (Home's presentation contract): the detail
        // takes the whole surface; the list stays parked in the workspace, not remounted.
        RaftChildPage(
            pageName = THREAD_PAGE,
            pageData = detailPageData,
            modifier = Modifier.fillMaxSize(),
            active = true,
        )
    } else {
        activityListPane(true)
    }
}



private fun parkStandaloneSelection(
    environment: HomeSecondaryRouteEnvironment,
    workspaceStore: SharedAdaptiveWorkspaceStore,
    request: HomeMessageOpenRequest,
    serverId: String?,
) {
    // Only park while desktop mode is OFF: ON + compact standalone pushes are normal
    // navigation and must not overwrite the live selection shown on the next wide tick.
    if (KeyValueChatLayoutPreferenceStore(raftSharedPreferenceStore())
            .readDesktopModeOnLargeScreenSync()
    ) {
        return
    }
    val readyServerId = serverId?.trim()?.takeIf(String::isNotBlank) ?: return
    val launchPayload =
        buildStandaloneConversationThreadLaunchPayload(
            conversation = request.conversationIdentity,
            timeContext = environment.timeContext,
            alignOwnMessagesRight = environment.alignOwnMessagesRight,
            compactConsecutiveMessages = environment.compactConsecutiveMessages,
            anchorMessageId = request.anchorMessageId,
            originFallback = request.originFallback,
            onWarn = { environment.bridge.logWarn(it) },
        ) ?: return
    val identity = launchPayload.toRaftAdaptiveDetailIdentity(readyServerId) ?: return
    workspaceStore.update { session ->
        val workspace = session.workspace ?: return@update session
        if (workspace.serverId != identity.serverId) return@update session
        val next =
            reduceRaftAdaptiveWorkspace(
                workspace,
                RaftAdaptiveWorkspaceIntent.ParkSelection(identity),
            )
        session.copy(
            workspace = next,
            launchPayload =
                if (next.selection?.identity == identity) launchPayload else session.launchPayload,
        )
    }
}

private fun releaseActivityAdaptiveLease(
    environment: HomeSecondaryRouteEnvironment,
    workspaceStore: SharedAdaptiveWorkspaceStore,
    realtimeEvents: RaftRealtimeEvents,
    roomLeaseRegistry: build.raft.app.data.SharedRealtimeRoomLeaseRegistry,
    expectedGeneration: Long? = null,
) {
    val owner = workspaceStore.snapshot().handoffRoomLeaseOwner ?: return
    if (expectedGeneration != null && owner.generation != expectedGeneration) return
    realtimeEvents.applyRoomTransitions(roomLeaseRegistry.release(owner))
    workspaceStore.update { current ->
        if (current.handoffRoomLeaseOwner == owner) {
            current.copy(handoffRoomLeaseOwner = null)
        } else {
            current
        }
    }
}

private fun openActivityAdaptiveMessageRequest(
    environment: HomeSecondaryRouteEnvironment,
    workspaceStore: SharedAdaptiveWorkspaceStore,
    workspace: RaftAdaptiveWorkspaceState?,
    request: HomeMessageOpenRequest,
    serverId: String?,
    realtimeEvents: RaftRealtimeEvents,
    roomLeaseRegistry: build.raft.app.data.SharedRealtimeRoomLeaseRegistry,
): Boolean {
    val current = workspace ?: return false
    if (current.pane.layout != RaftAdaptivePaneLayout.DualPane) return false
    val readyServerId = serverId ?: return false
    val launchPayload =
        buildStandaloneConversationThreadLaunchPayload(
            conversation = request.conversationIdentity,
            timeContext = environment.timeContext,
            alignOwnMessagesRight = environment.alignOwnMessagesRight,
            compactConsecutiveMessages = environment.compactConsecutiveMessages,
            anchorMessageId = request.anchorMessageId,
            originFallback = request.originFallback,
            onWarn = { environment.bridge.logWarn(it) },
        ) ?: return false
    val identity = launchPayload.toRaftAdaptiveDetailIdentity(readyServerId) ?: return false
    val next = reduceRaftAdaptiveWorkspace(current, RaftAdaptiveWorkspaceIntent.OpenDetail(identity))
    if (next.selection?.identity != identity) return false
    val nextSelection = next.selection ?: return false
    // Lease the detail's realtime rooms under an owner tagged to this surface so the
    // covered Home never acquires/releases on Activity's behalf (and vice versa).
    val owner =
        build.raft.app.data.RealtimeRoomLeaseOwner(
            shellInstanceId = environment.storeScope.shellInstanceId,
            serverId = nextSelection.identity.serverId,
            consumerId = "activity-surface-detail",
            generation = nextSelection.generation,
        )
    val roomIds =
        launchPayload
            .toInitialPayloadOrNull(launchPayload.instanceId)
            ?.activeRealtimeRoomIds()
            .orEmpty()
    realtimeEvents.applyRoomTransitions(roomLeaseRegistry.acquire(owner, roomIds))
    val previousOwner = workspaceStore.snapshot().handoffRoomLeaseOwner
    workspaceStore.update { state ->
        state.copy(workspace = next, launchPayload = launchPayload, handoffRoomLeaseOwner = owner)
    }
    if (previousOwner != null && previousOwner != owner) {
        realtimeEvents.applyRoomTransitions(roomLeaseRegistry.release(previousOwner))
    }
    environment.bridge.log(
        "activity_adaptive_open threadId=${identity.threadId} key=${identity.key}"
    )
    return true
}

@Composable
private fun HomeSearchRouterContent(
    environment: HomeSecondaryRouteEnvironment,
    routeVisibleTick: Int = 0,
    initialChannelId: String? = null,
    initialOpenFilterId: String? = null,
    initialFocusInput: Boolean = false,
    listState: LazyListState? = null,
    returnHighlight: HomeSearchReturnHighlight? = null,
    bottomContentPadding: Dp? = null,
    inputActive: Boolean = true,
    focusInputRequest: Int = 0,
    queryRequest: RaftSearchTabQueryRequest? = null,
    onQueryApplied: () -> Unit = {}
) {
    val conversationNotReadyMessage = stringResource(MR.strings.home_error_conversation_not_ready)
    val homeDataSource =
        remember(environment.authStore, environment.networkClient) {
            SharedHomeDataSource(
                sessionStore = environment.authStore,
                api = NetworkRaftHomeApi(environment.networkClient),
                announcementsApi = NetworkRaftAnnouncementsRemindersApi(environment.networkClient),
                computersApi = RaftComputersNetworkApi(environment.networkClient),
                parseHome = { payload, session ->
                    parseRaftHome(payload, session, raftLogger("SlockHomeSecondaryParse"))
                },
                diagnosticsLogger = raftLogger("SlockHomeSecondaryHome", "home_secondary_home")
            )
        }
    val homeViewModel: SharedHomeViewModel =
        viewModel(key = "home-secondary-home") {
            SharedHomeViewModel(
                authStore = environment.authStore,
                dataSource = homeDataSource,
                diagnosticsLogger = raftLogger("SlockHomeSecondaryHome", "home_secondary_home")
            )
        }
    val homeState by homeViewModel.state.collectAsState()
    val sharedHomeStateFlow =
        remember(environment.sharedHomeStore) {
            environment.sharedHomeStore?.state ?: flowOf(SharedHomeState())
        }
    val sharedHomeState by sharedHomeStateFlow.collectAsState(SharedHomeState())
    val pinnedItems =
        remember(sharedHomeState.content, homeState.content) {
            val sidebarOrder =
                (sharedHomeState.content as? SharedHomeContentState.Content)?.snapshot?.sidebarOrder
                    ?: (homeState.content as? SharedHomeContentState.Content)?.snapshot?.sidebarOrder
            sidebarOrder?.typedPinnedOrLegacy().orEmpty()
        }
    LaunchedEffect(homeViewModel) {
        homeViewModel.dispatch(SharedHomeIntent.Start)
    }
    val searchSavedViewModel = rememberHomeSearchSavedViewModel(environment, key = "home-secondary-search")
    val searchSavedState by searchSavedViewModel.state.collectAsState()
    val normalizedInitialChannelId =
        remember(initialChannelId) { initialChannelId?.trim()?.takeIf { it.isNotBlank() } }
    var skipInitialChannelRefresh by remember(normalizedInitialChannelId) {
        mutableStateOf(normalizedInitialChannelId != null)
    }
    LaunchedEffect(initialChannelId) {
        val channelId = normalizedInitialChannelId ?: return@LaunchedEffect
        searchSavedViewModel.dispatch(SharedHomeSearchSavedIntent.PreselectSearchChannel(channelId))
    }
    LaunchedEffect(routeVisibleTick, normalizedInitialChannelId) {
        if (activityPageDidAppearShouldRefresh(routeVisibleTick)) {
            if (
                skipInitialChannelRefresh &&
                !searchSavedState.search.searched &&
                searchSavedState.search.query.isBlank()
            ) {
                skipInitialChannelRefresh = false
            } else {
                skipInitialChannelRefresh = false
                searchSavedViewModel.dispatch(SharedHomeSearchSavedIntent.RefreshSearch)
            }
        }
    }
    val searchServerId =
        (homeState.content as? SharedHomeContentState.Content)
            ?.snapshot
            ?.server
            ?.id
            ?.value
    val agentStatusResolver =
        remember(environment.serverMembersStore, searchServerId) {
            raftScopedAgentStatusResolver(environment.serverMembersStore, searchServerId)
        }
    val memberAvatarResolver =
        remember(environment.serverMembersStore, searchServerId) {
            if (searchServerId.isNullOrBlank()) {
                EmptyRaftMemberAvatarResolver
            } else {
                raftMemberAvatarResolver(environment.serverMembersStore, searchServerId)
            }
        }
    LaunchedEffect(environment.serverMembersStore, searchServerId) {
        val serverId = searchServerId?.trim()?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        environment.serverMembersStore.refreshMembers(ServerId(serverId), DataRequestPriority.Background)
    }
    LaunchedEffect(environment.computersStore, searchServerId, routeVisibleTick) {
        environment.computersStore.reconcileOwner()
        val serverId = searchServerId?.trim()?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        environment.computersStore.dispatch(
            SharedComputersIntent.EnsureComputerPicker(
                serverId = serverId,
                priority = DataRequestPriority.Background
            )
        )
    }
    val computersState by environment.computersStore.state.collectAsState()
    val computerDirectory =
        remember(searchServerId, computersState) {
            computersState.toHomeSearchComputerDirectory(searchServerId)
        }
    val ownListState = rememberLazyListState()
    val searchListState = listState ?: ownListState
    val activeDraftRecords by environment.composerDraftPersistSlice.activeDraftRecordsState.collectAsState(emptySet())
    val draftProjection =
        remember(activeDraftRecords) {
            activeDraftRecords.toSharedThreadComposerDraftProjection()
        }
    // t5/#419 A3: user-initiated opens resolve against the real directory + server id.
    val conversationDirectoryFlow =
        remember(environment.conversationIdentityProvider, searchServerId) {
            searchServerId?.takeIf { it.isNotBlank() }
                ?.let { serverId -> environment.conversationIdentityProvider?.observeServer(ServerId(serverId)) }
                ?: flowOf(SharedConversationDirectoryState())
        }
    val conversationDirectory by conversationDirectoryFlow.collectAsState(SharedConversationDirectoryState())
    val searchConversationDirectory =
        remember(searchServerId, searchSavedState.search.items) {
            searchServerId
                ?.takeIf { it.isNotBlank() }
                ?.let(::ServerId)
                ?.let { serverId -> searchSavedState.search.items.toSharedConversationDirectoryState(serverId) }
                ?: SharedConversationDirectoryState()
        }
    val openConversationDirectory =
        remember(searchConversationDirectory, conversationDirectory) {
            listOf(searchConversationDirectory, conversationDirectory)
                .mergedSharedConversationDirectoryState()
        }
    LaunchedEffect(environment.sharedHomeStore, searchServerId, searchSavedState.search.items) {
        val serverId = searchServerId?.takeIf { it.isNotBlank() }?.let(::ServerId) ?: return@LaunchedEffect
        environment.sharedHomeStore?.supplementConversationIdentity(
            serverId = serverId,
            source = SharedConversationIdentitySupplementSource.Search,
            items = searchSavedState.search.items
        )
    }
    val chrome = homeSearchPageChrome(tabRoot = environment.tabRoot)
    SharedHomeSearchPanel(
        state = searchSavedState.search,
        homeState = homeState,
        cacheServerId = searchServerId,
        agentStatusResolver = agentStatusResolver,
        memberAvatarResolver = memberAvatarResolver,
        computerDirectory = computerDirectory,
        draftProjection = draftProjection,
        onIntent = searchSavedViewModel::dispatch,
        onClose =
            if (chrome.showsBack) {
                { environment.inlineOnClose?.invoke() ?: environment.pager.closeHomeSecondaryPage() }
            } else {
                null
            },
        onOpenItem = { item ->
            when (
                val resolution =
                    item.toHomeMessageOpenRequest(
                        serverId = searchServerId.orEmpty(),
                        conversationDirectory = openConversationDirectory
                    )
            ) {
                is ConversationOpenResolution.Resolved -> {
                    val opened =
                        environment.inlineOpenRequest?.let { open ->
                            open(resolution.request, "home_search")
                            true
                        } ?: run {
                            // task #45: OFF standalone opens through Search still park the
                            // current selection so re-enabling restores this conversation.
                            parkStandaloneSelection(
                                environment = environment,
                                workspaceStore = environment.sharedWorkspaceStore,
                                request = resolution.request,
                                serverId = searchServerId,
                            )
                            environment.pager.openStandaloneHomeMessageRequest(
                            bridge = environment.bridge,
                            request = resolution.request,
                            source = "home_search",
                            timeContext = environment.timeContext,
                            alignOwnMessagesRight = environment.alignOwnMessagesRight,
                            compactConsecutiveMessages = environment.compactConsecutiveMessages,
                            storeScope = environment.storeScope
                            )
                        }
                    if (opened) {
                        val entityKey =
                            resolution.request.conversationIdentity.searchPersonalizationEntityKeyOrNull()
                        searchSavedViewModel.dispatch(
                            SharedHomeSearchSavedIntent.RecordSuccessfulSearchOpen(
                                query = searchSavedState.search.resultQuery.orEmpty(),
                                entityKind = entityKey?.kind,
                                entityId = entityKey?.id,
                            )
                        )
                    }
                }
                is ConversationOpenResolution.Pending -> {
                    environment.bridge.logWarn(
                        "search_open_request_pending reason=${resolution.reason} itemKey=${item.key}"
                    )
                    environment.bridge.toast(conversationNotReadyMessage)
                }
            }
        },
        onOpenEntity = { entity ->
            val opened =
                environment.inlineOpenEntity?.invoke(entity)
                    ?: run {
                        // task #45: OFF standalone entity opens (channel/DM search results)
                        // park the current selection so re-enabling restores this
                        // conversation — same contract as the message-open fallback above.
                        (entity.destinationOrNull() as? HomeSearchEntityDestination.Conversation)
                            ?.let { destination ->
                                parkStandaloneSelection(
                                    environment = environment,
                                    workspaceStore = environment.sharedWorkspaceStore,
                                    request =
                                        HomeMessageOpenRequest(
                                            conversationIdentity = destination.identity,
                                            anchorMessageId = null,
                                        ),
                                    serverId = searchServerId,
                                )
                            }
                        environment.pager.openHomeSearchEntity(
                            bridge = environment.bridge,
                            entity = entity,
                            timeContext = environment.timeContext,
                            alignOwnMessagesRight = environment.alignOwnMessagesRight,
                            compactConsecutiveMessages = environment.compactConsecutiveMessages,
                            storeScope = environment.storeScope
                        )
                    }
            if (opened) {
                val entityKey = entity.searchPersonalizationEntityKeyOrNull()
                searchSavedViewModel.dispatch(
                    SharedHomeSearchSavedIntent.RecordSuccessfulSearchOpen(
                        query = searchSavedState.search.query,
                        entityKind = entityKey?.kind,
                        entityId = entityKey?.id,
                    )
                )
            }
        },
        onRefreshComputers = {
            searchServerId?.trim()?.takeIf { it.isNotBlank() }?.let { serverId ->
                environment.computersStore.dispatch(
                    SharedComputersIntent.RefreshComputerPicker(serverId = serverId)
                )
            }
        },
        pinnedItems = pinnedItems,
        onTogglePin =
            environment.sharedHomeStore?.let { store ->
                { target ->
                    store.dispatch(
                        SharedHomeIntent.TogglePinned(
                            conversationId = target.conversationId,
                            pinnedKind = target.kind,
                            pinnedId = target.id
                        )
                    )
                }
            },
        initialOpenFilterId = initialOpenFilterId,
        initialFocusInput = initialFocusInput,
        inputActive = inputActive,
        focusInputRequest = focusInputRequest,
        tabHead = environment.tabRoot,
        visibleTick = routeVisibleTick,
        returnHighlightState = returnHighlight,
        // Nav spec §7.2: a conversation-scoped pushed search hands "Search all" to the Search tab.
        onExpandToSearchTab =
            if (normalizedInitialChannelId != null && !environment.tabRoot && environment.inlineOnClose == null) {
                { query ->
                    // Only hand off while an app shell is alive to take it; otherwise stay here.
                    if (RaftSearchTabHandoff.request(query, shellAlive = AppShellChildTabRegistry.hasEntries())) {
                        environment.pager.closeHomeSecondaryPage()
                    }
                }
            } else {
                null
            },
        externalQueryRequest = queryRequest,
        onExternalQueryApplied = onQueryApplied,
        bottomContentPadding =
            bottomContentPadding ?: environment.shellScope.bottomInset(includeBottomTabBar = chrome.aboveBottomBar),
        timeContext = environment.timeContext,
        shellScope = environment.shellScope,
        listState = searchListState
    )
}

@Composable
private fun HomeSavedRouterContent(
    environment: HomeSecondaryRouteEnvironment,
    routeVisibleTick: Int = 0
) {
    val conversationNotReadyMessage = stringResource(MR.strings.home_error_conversation_not_ready)
    val copiedMessage = stringResource(MR.strings.common_copied)
    val copyLinkFailedMessage = stringResource(MR.strings.thread_error_copy_link)
    val searchSavedViewModel = rememberHomeSearchSavedViewModel(environment, key = "home-secondary-saved")
    val searchSavedState by searchSavedViewModel.state.collectAsState()
    LaunchedEffect(routeVisibleTick) {
        if (activityPageDidAppearShouldRefresh(routeVisibleTick)) {
            searchSavedViewModel.dispatch(SharedHomeSearchSavedIntent.RefreshSaved)
        }
    }
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val copyScope = rememberCoroutineScope()
    val activeDraftRecords by environment.composerDraftPersistSlice.activeDraftRecordsState.collectAsState(emptySet())
    val draftProjection =
        remember(activeDraftRecords) {
            activeDraftRecords.toSharedThreadComposerDraftProjection()
        }
    var savedServerId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(environment.authStore, environment.serverMembersStore) {
        val serverId =
            environment.authStore
                .readSession()
                ?.currentServerId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        savedServerId = serverId
        if (serverId != null) {
            environment.serverMembersStore.refreshMembers(ServerId(serverId), DataRequestPriority.Background)
        }
    }
    val memberAvatarResolver =
        remember(environment.serverMembersStore, savedServerId) {
            if (savedServerId.isNullOrBlank()) {
                EmptyRaftMemberAvatarResolver
            } else {
                raftMemberAvatarResolver(environment.serverMembersStore, savedServerId)
            }
        }
    // t5/#419 A3: user-initiated opens resolve against the real directory + server id.
    val conversationDirectoryFlow =
        remember(environment.conversationIdentityProvider, savedServerId) {
            savedServerId?.takeIf { it.isNotBlank() }
                ?.let { serverId -> environment.conversationIdentityProvider?.observeServer(ServerId(serverId)) }
                ?: flowOf(SharedConversationDirectoryState())
        }
    val conversationDirectory by conversationDirectoryFlow.collectAsState(SharedConversationDirectoryState())
    val savedConversationDirectory =
        remember(savedServerId, searchSavedState.saved.items) {
            savedServerId
                ?.takeIf { it.isNotBlank() }
                ?.let(::ServerId)
                ?.let { serverId -> searchSavedState.saved.items.toSharedConversationDirectoryState(serverId) }
                ?: SharedConversationDirectoryState()
        }
    val openConversationDirectory =
        remember(savedConversationDirectory, conversationDirectory) {
            listOf(savedConversationDirectory, conversationDirectory)
                .mergedSharedConversationDirectoryState()
        }
    LaunchedEffect(environment.sharedHomeStore, savedServerId, searchSavedState.saved.items) {
        val serverId = savedServerId?.takeIf { it.isNotBlank() }?.let(::ServerId) ?: return@LaunchedEffect
        environment.sharedHomeStore?.supplementConversationIdentity(
            serverId = serverId,
            source = SharedConversationIdentitySupplementSource.Saved,
            items = searchSavedState.saved.items
        )
    }
    SharedHomeSavedPanel(
        state = searchSavedState.saved,
        cacheServerId = savedServerId,
        memberAvatarResolver = memberAvatarResolver,
        draftProjection = draftProjection,
        onIntent = searchSavedViewModel::dispatch,
        onClose = { environment.inlineOnClose?.invoke() ?: environment.pager.closeHomeSecondaryPage() },
        onOpenItem = { item ->
            when (
                val resolution =
                    item.toHomeMessageOpenRequest(
                        serverId = savedServerId.orEmpty(),
                        conversationDirectory = openConversationDirectory
                    )
            ) {
                is ConversationOpenResolution.Resolved -> {
                    // Large-screen inline Saved opens in the shared detail pane, like Search.
                    environment.inlineOpenRequest?.let { open ->
                        open(resolution.request, "home_saved")
                    } ?: run {
                        parkStandaloneSelection(
                            environment = environment,
                            workspaceStore = environment.sharedWorkspaceStore,
                            request = resolution.request,
                            serverId = savedServerId,
                        )
                        environment.pager.openStandaloneHomeMessageRequest(
                            bridge = environment.bridge,
                            request = resolution.request,
                            source = "home_saved",
                            timeContext = environment.timeContext,
                            alignOwnMessagesRight = environment.alignOwnMessagesRight,
                            compactConsecutiveMessages = environment.compactConsecutiveMessages,
                            storeScope = environment.storeScope
                        )
                    }
                }
                is ConversationOpenResolution.Pending -> {
                    environment.bridge.logWarn(
                        "saved_open_request_pending reason=${resolution.reason} itemKey=${item.key}"
                    )
                    environment.bridge.toast(conversationNotReadyMessage)
                }
            }
        },
        bottomContentPadding = environment.shellScope.bottomInset(includeBottomTabBar = false),
        timeContext = environment.timeContext,
        shellScope = environment.shellScope,
        listState = listState,
        onTitleClick = { scrollScope.launch { listState.animateScrollToTopExpressive() } },
        onCopyLink = { item ->
            copyScope.launch {
                val serverSlug = environment.authStore.readSession()?.currentServerSlug()
                val webBaseUrl =
                    when (val developer = environment.settingsCapabilities.loadDeveloper()) {
                        is DataResult.Content -> developer.data.webUrl
                        else -> null
                    }
                val permalink = serverSlug?.let { sharedHomeSavedPermalink(item, it, webBaseUrl) }
                if (permalink != null) {
                    environment.bridge.copyToPasteboard(permalink)
                    environment.bridge.toast(copiedMessage)
                } else {
                    environment.bridge.toast(copyLinkFailedMessage)
                }
            }
        },
        onCopyMarkdown = { item ->
            environment.bridge.copyToPasteboard(item.sharedHomeSavedMarkdownContent())
            environment.bridge.toast(copiedMessage)
        }
    )
}

@Composable
private fun rememberHomeSearchSavedViewModel(
    environment: HomeSecondaryRouteEnvironment,
    key: String
): SharedHomeSearchSavedViewModel {
    val currentTimeContext = rememberUpdatedState(environment.timeContext)
    val savedRepository =
        remember(environment.authStore, environment.networkClient) {
            RealSavedRepository(
                sessionStore = environment.authStore,
                networkClient = environment.networkClient,
                warningLogger = raftLogger("SlockHomeSecondarySaved", "home_secondary_saved", RaftLogLevel.Warn)
            )
        }
    val searchRepository =
        remember(environment.authStore, environment.networkClient) {
            RealSearchRepository(
                sessionStore = environment.authStore,
                networkClient = environment.networkClient,
                warningLogger = raftLogger("SlockHomeSecondarySearch", "home_secondary_search", RaftLogLevel.Warn)
            )
        }
    val memberRepository =
        remember(environment.authStore, environment.networkClient) {
            RealMemberRepository(
                sessionStore = environment.authStore,
                networkClient = environment.networkClient,
                logger = raftLogger("SlockHomeSecondaryMember", "home_secondary_member"),
                warningLogger = raftLogger("SlockHomeSecondaryMember", "home_secondary_member_warning", RaftLogLevel.Warn)
            )
        }
    return viewModel(key = key) {
        SharedHomeSearchSavedViewModel(
            authStore = environment.authStore,
            savedRepository = savedRepository,
            searchRepository = searchRepository,
            memberRepository = memberRepository,
            serverMembersStore = environment.serverMembersStore,
            savedStateCache = environment.homeSavedStateCache,
            readReceiptCoordinator = environment.readReceiptCoordinator,
            readReceiptOwnerFactory = environment.readReceiptOwnerFactory,
            timeRangeBoundsProvider = { range -> currentTimeContext.value.toSearchIsoBounds(range) }
        )
    }
}
