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
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.DEFAULT_DESKTOP_MODE_ON_LARGE_SCREEN
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.KuiklyChatLayoutPreferenceEvents
import build.raft.app.data.KuiklyRealtimeEvents
import build.raft.app.data.MyMessageAlignmentPreference
import build.raft.app.data.NetworkSlockAnnouncementsRemindersApi
import build.raft.app.data.NetworkSlockHomeApi
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
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.SlockComputersNetworkApi
import build.raft.app.data.SlockNetworkClient
import build.raft.app.data.SlockSharedStoreRegistry
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.kuiklyBridgeCallContext
import build.raft.app.data.mergedSharedConversationDirectoryState
import build.raft.app.data.parseSlockHome
import build.raft.app.data.slockSharedPreferenceStore
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.data.typedPinnedOrLegacy
import build.raft.app.model.ServerId
import build.raft.app.model.SlockTimeContext
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.lazy.rememberLazyListState
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
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
    val authStore: SlockAuthSessionStore,
    val networkClient: SlockNetworkClient,
    val serverMembersStore: SharedServerMembersStore,
    val computersStore: SharedComputersStore,
    val composerDraftPersistSlice: SharedThreadComposerDraftPersistSlice,
    val settingsCapabilities: BridgeSettingsPlatformCapabilities,
    val homeSavedStateCache: SharedHomeSavedStateCache,
    val timeContextProvider: SlockTimeContextProvider,
    val timeContext: AppShellThreadTimeContext?,
    val alignOwnMessagesRight: Boolean,
    val compactConsecutiveMessages: Boolean,
    val foregroundMessageBannersEnabled: Boolean,
    val activityTaskStatusBadgesEnabled: Boolean,
    val activitySenderAvatarsEnabled: Boolean,
    val storeScope: SlockShellStoreScope,
    val hasExplicitShellScope: Boolean,
    val adaptivePane: SlockAdaptivePaneState,
    val shellScope: SlockAppShellScope,
    val conversationIdentityProvider: SharedConversationIdentityProvider?,
    val sharedHomeStore: SharedHomeStore?,
    val readReceiptCoordinator: ReadReceiptCoordinator,
    val readReceiptOwnerFactory: (String, String) -> ReadReceiptOwner,
    // task #13 three-pane: when the destination is embedded inside the AppShell middle pane
    // (wide LeftRail), conversation/entity opens must route to the shell's adaptive workspace
    // OpenDetail instead of pushing a standalone page (which would cover the rail).
    // Null = legacy standalone behavior (push).
    val inlineOpenRequest: ((HomeMessageOpenRequest, String) -> Unit)? = null,
    val inlineOpenEntity: ((SearchEntityResult) -> Boolean)? = null,
    // When embedded inline there is no pushed page to pop — "close" returns to the host tab.
    val inlineOnClose: (() -> Unit)? = null,
)

@Composable
private fun rememberHomeSecondaryRouteEnvironment(
    routeVisibleTick: Int = 0,
    adaptivePane: SlockAdaptivePaneState = SlockAdaptivePaneState(),
    inlineOpenRequest: ((HomeMessageOpenRequest, String) -> Unit)? = null,
    inlineOpenEntity: ((SearchEntityResult) -> Boolean)? = null,
    inlineOnClose: (() -> Unit)? = null,
): HomeSecondaryRouteEnvironment {
    RememberKmmResourceLocaleSeedFromLocalActivity()
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager) { Utils.bridgeModule(pager.pagerId) }
    val storeScope = remember(pager.pageData.params.toString(), pager.pagerId) {
        pager.pageData.params.slockShellStoreScope(SlockShellStoreScope.Legacy.shellInstanceId)
    }
    val hasExplicitShellScope = remember(pager.pageData.params.toString()) {
        pager.pageData.params.slockShellStoreScopeOrNull() != null
    }
    val sharedStores = remember(bridge) { SlockSharedStoreRegistry.get(bridge) }
    val bridgeCallScope = rememberCoroutineScope()
    val linkNavigator = rememberSlockLinkNavigator(pager, bridge)
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
    val sharedPreferenceStore = remember { slockSharedPreferenceStore() }
    val chatLayoutPreferenceRepository =
        remember(sharedPreferenceStore) {
            PersistentChatLayoutPreferenceRepository(KeyValueChatLayoutPreferenceStore(sharedPreferenceStore))
    }
    var myMessageAlignment by remember { mutableStateOf(MyMessageAlignmentPreference.Default) }
    var compactConsecutiveMessages by remember { mutableStateOf(false) }
    var foregroundMessageBannersEnabled by remember { mutableStateOf(false) }
    var activityTaskStatusBadgesEnabled by remember { mutableStateOf(false) }
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
        rememberLiveSlockTimeContext(
            capabilities = timeCapabilities,
            pageVisibleTick = routeVisibleTick
        )
    val timeContext =
        remember(timeCapabilities) {
            timeCapabilities?.let {
                SlockTimeContext(
                    localOffsetMinutes = it.localOffsetMinutes,
                    timeFormat = it.timeFormat
                )
            }
        }
    val shellScope = rememberSlockAppShellScope(adaptivePane)
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
            inlineOpenRequest = inlineOpenRequest,
            inlineOpenEntity = inlineOpenEntity,
            inlineOnClose = inlineOnClose,
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
    adaptivePane: SlockAdaptivePaneState = SlockAdaptivePaneState(),
    inlineOpenRequest: ((HomeMessageOpenRequest, String) -> Unit)? = null,
    inlineOpenEntity: ((SearchEntityResult) -> Boolean)? = null,
    inlineOnClose: (() -> Unit)? = null,
) {
    val environment = rememberHomeSecondaryRouteEnvironment(
        routeVisibleTick,
        adaptivePane,
        inlineOpenRequest,
        inlineOpenEntity,
        inlineOnClose,
    )
    val retainedDestination = retainedHomeSecondaryDestination(destination)
    PerformanceSceneEffect(
        bridge = environment.bridge,
        sceneName = retainedDestination?.let(::homeSecondaryPerformanceRoute) ?: "kuikly_home_secondary",
        active = true
    )
    CompositionLocalProvider(LocalSlockTimeContextProvider provides environment.timeContextProvider) {
        when (retainedDestination) {
            HomeDestinationActivity -> HomeActivityRouterContent(environment, routeVisibleTick)
            HomeDestinationSearch ->
                HomeSearchRouterContent(
                    environment,
                    routeVisibleTick,
                    initialChannelId = initialSearchChannelId,
                    initialOpenFilterId = initialSearchOpenFilterId,
                    initialFocusInput = initialSearchFocusInput
                )
            HomeDestinationSaved -> HomeSavedRouterContent(environment, routeVisibleTick)
            else -> SlockCenteredLoading(message = stringResource(MR.strings.auth_state_opening_server))
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
    val sharedStores = remember(environment.bridge) { SlockSharedStoreRegistry.get(environment.bridge) }
    val activityWorkspaceStore = remember { SharedAdaptiveWorkspaceStore() }
    // Lease keeps the detail's realtime rooms subscribed while this surface owns the
    // selection; released when the workspace resets or the page disposes. Registry +
    // events are the same shell-scoped shared instances Home uses — the OWNER record is
    // what is surface-private (consumerId names this surface).
    val activityRealtimeEvents = remember(sharedStores) { KuiklyRealtimeEvents(sharedStores.eventBus) }
    val activityRoomLeaseRegistry =
        remember(sharedStores, environment.storeScope) {
            sharedStores.realtimeRoomLeaseRegistry(environment.storeScope)
        }
    val activityWorkspaceSession by activityWorkspaceStore.state.collectAsState()
    val activityWorkspace = activityWorkspaceSession.workspace
    // The page-level pane state (fed by SlockAdaptivePanePagerEvent on the pushed page's own
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
        remember(sharedStores.eventBus) { KuiklyChatLayoutPreferenceEvents(sharedStores.eventBus) }
    DisposableEffect(chatLayoutPreferenceEvents) {
        val subscription =
            chatLayoutPreferenceEvents.addPreferenceListener { chatLayout ->
                activityAdaptiveEnabled = chatLayout.desktopModeOnLargeScreen
            }
        onDispose { subscription.close() }
    }
    val activityChatLayoutPreferenceStore =
        remember { KeyValueChatLayoutPreferenceStore(slockSharedPreferenceStore()) }
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
                    ?: SlockAdaptiveWorkspaceState(
                        shellInstanceId = environment.storeScope.shellInstanceId + ":activity",
                        serverId = serverId,
                    )
            if (next.serverId != serverId) {
                next = reduceSlockAdaptiveWorkspace(next, SlockAdaptiveWorkspaceIntent.AuthorityChanged(serverId))
            }
            next = reduceSlockAdaptiveWorkspace(next, SlockAdaptiveWorkspaceIntent.WindowChanged(adaptivePane))
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
    val dualPane = activityAdaptiveEnabled && workspace?.pane?.layout == SlockAdaptivePaneLayout.DualPane
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
                    .withSlockShellStoreScope(environment.storeScope)
                    .put(SLOCK_ADAPTIVE_DETAIL_SHELL_ID, workspace.shellInstanceId)
                    .put(SLOCK_ADAPTIVE_DETAIL_IDENTITY_KEY, selection.identity.key)
                    .put(SLOCK_ADAPTIVE_DETAIL_GENERATION, selection.generation)
            }
        }
    // CloseDetail: the embedded thread's own Back posts SLOCK_ADAPTIVE_DETAIL_CLOSE_EVENT
    // carrying this surface's namespaced shell id; only this page reduces it (Home's
    // observer filters on the un-suffixed shell id, so events can't cross).
    DisposableEffect(activityWorkspaceStore, environment.storeScope.shellInstanceId) {
        val activityShellId = environment.storeScope.shellInstanceId + ":activity"
        val subscription =
            sharedStores.eventBus.observe(
                eventName = SLOCK_ADAPTIVE_DETAIL_CLOSE_EVENT,
                crossProcess = true,
            ) { eventData ->
                val request = eventData?.toSlockAdaptiveDetailCloseRequestOrNull()
                    ?: return@observe
                if (request.shellInstanceId != activityShellId) return@observe
                val current = activityWorkspaceStore.snapshot().workspace ?: return@observe
                val next =
                    reduceSlockAdaptiveWorkspace(
                        current,
                        SlockAdaptiveWorkspaceIntent.CloseDetail(
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
    SlockBackHandler(enabled = activityAdaptiveEnabled && selection != null) {
        val current = activityWorkspaceStore.snapshot().workspace ?: return@SlockBackHandler
        val dismissed =
            reduceSlockAdaptiveWorkspace(current, SlockAdaptiveWorkspaceIntent.DismissDetail)
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
            workspace?.presentation == SlockAdaptiveWorkspacePresentation.DetailOnly
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
                    .background(Color.Black),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                if (detailPageData != null && selection != null) {
                    SlockKuiklyChildPage(
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
                            color = SlockColor.TextMutedGray,
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        }
    } else if (detailOnly && detailPageData != null) {
        // Compact + live selection = DetailOnly (Home's presentation contract): the detail
        // takes the whole surface; the list stays parked in the workspace, not remounted.
        SlockKuiklyChildPage(
            pageName = THREAD_PAGE,
            pageData = detailPageData,
            modifier = Modifier.fillMaxSize(),
            active = true,
        )
    } else {
        activityListPane(true)
    }
}


private fun releaseActivityAdaptiveLease(
    environment: HomeSecondaryRouteEnvironment,
    workspaceStore: SharedAdaptiveWorkspaceStore,
    realtimeEvents: KuiklyRealtimeEvents,
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
    workspace: SlockAdaptiveWorkspaceState?,
    request: HomeMessageOpenRequest,
    serverId: String?,
    realtimeEvents: KuiklyRealtimeEvents,
    roomLeaseRegistry: build.raft.app.data.SharedRealtimeRoomLeaseRegistry,
): Boolean {
    val current = workspace ?: return false
    if (current.pane.layout != SlockAdaptivePaneLayout.DualPane) return false
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
    val identity = launchPayload.toSlockAdaptiveDetailIdentity(readyServerId) ?: return false
    val next = reduceSlockAdaptiveWorkspace(current, SlockAdaptiveWorkspaceIntent.OpenDetail(identity))
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
    initialFocusInput: Boolean = false
) {
    val conversationNotReadyMessage = stringResource(MR.strings.home_error_conversation_not_ready)
    val homeDataSource =
        remember(environment.authStore, environment.networkClient) {
            SharedHomeDataSource(
                sessionStore = environment.authStore,
                api = NetworkSlockHomeApi(environment.networkClient),
                announcementsApi = NetworkSlockAnnouncementsRemindersApi(environment.networkClient),
                computersApi = SlockComputersNetworkApi(environment.networkClient),
                parseHome = { payload, session ->
                    parseSlockHome(payload, session, slockLogger("SlockHomeSecondaryParse"))
                },
                diagnosticsLogger = slockLogger("SlockHomeSecondaryHome", "home_secondary_home")
            )
        }
    val homeViewModel: SharedHomeViewModel =
        viewModel(key = "home-secondary-home") {
            SharedHomeViewModel(
                authStore = environment.authStore,
                dataSource = homeDataSource,
                diagnosticsLogger = slockLogger("SlockHomeSecondaryHome", "home_secondary_home")
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
            slockScopedAgentStatusResolver(environment.serverMembersStore, searchServerId)
        }
    val memberAvatarResolver =
        remember(environment.serverMembersStore, searchServerId) {
            if (searchServerId.isNullOrBlank()) {
                EmptySlockMemberAvatarResolver
            } else {
                slockMemberAvatarResolver(environment.serverMembersStore, searchServerId)
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
    val listState = rememberLazyListState()
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
    SharedHomeSearchPanel(
        state = searchSavedState.search,
        homeState = homeState,
        cacheServerId = searchServerId,
        agentStatusResolver = agentStatusResolver,
        memberAvatarResolver = memberAvatarResolver,
        computerDirectory = computerDirectory,
        draftProjection = draftProjection,
        onIntent = searchSavedViewModel::dispatch,
        onClose = { environment.inlineOnClose?.invoke() ?: environment.pager.closeHomeSecondaryPage() },
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
                        } ?: environment.pager.openStandaloneHomeMessageRequest(
                            bridge = environment.bridge,
                            request = resolution.request,
                            source = "home_search",
                            timeContext = environment.timeContext,
                            alignOwnMessagesRight = environment.alignOwnMessagesRight,
                            compactConsecutiveMessages = environment.compactConsecutiveMessages,
                            storeScope = environment.storeScope
                        )
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
                    ?: environment.pager.openHomeSearchEntity(
                        bridge = environment.bridge,
                        entity = entity,
                        timeContext = environment.timeContext,
                        alignOwnMessagesRight = environment.alignOwnMessagesRight,
                        compactConsecutiveMessages = environment.compactConsecutiveMessages,
                        storeScope = environment.storeScope
                    )
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
        bottomContentPadding = environment.shellScope.bottomInset(includeBottomTabBar = false),
        timeContext = environment.timeContext,
        shellScope = environment.shellScope,
        listState = listState
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
                EmptySlockMemberAvatarResolver
            } else {
                slockMemberAvatarResolver(environment.serverMembersStore, savedServerId)
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
                is ConversationOpenResolution.Resolved ->
                    environment.pager.openStandaloneHomeMessageRequest(
                        bridge = environment.bridge,
                        request = resolution.request,
                        source = "home_saved",
                        timeContext = environment.timeContext,
                        alignOwnMessagesRight = environment.alignOwnMessagesRight,
                        compactConsecutiveMessages = environment.compactConsecutiveMessages,
                                    storeScope = environment.storeScope
                    )
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
                warningLogger = slockLogger("SlockHomeSecondarySaved", "home_secondary_saved", SlockLogLevel.Warn)
            )
        }
    val searchRepository =
        remember(environment.authStore, environment.networkClient) {
            RealSearchRepository(
                sessionStore = environment.authStore,
                networkClient = environment.networkClient,
                warningLogger = slockLogger("SlockHomeSecondarySearch", "home_secondary_search", SlockLogLevel.Warn)
            )
        }
    val memberRepository =
        remember(environment.authStore, environment.networkClient) {
            RealMemberRepository(
                sessionStore = environment.authStore,
                networkClient = environment.networkClient,
                logger = slockLogger("SlockHomeSecondaryMember", "home_secondary_member"),
                warningLogger = slockLogger("SlockHomeSecondaryMember", "home_secondary_member_warning", SlockLogLevel.Warn)
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
