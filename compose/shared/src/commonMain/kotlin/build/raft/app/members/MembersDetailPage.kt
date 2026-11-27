package build.raft.app.members

import build.raft.app.data.isFor
import build.raft.app.assistant.AssistantOwnerScope
import build.raft.app.assistant.LocalAssistantOwnerScope
import build.raft.app.resources.*
import build.raft.app.settings.RaftUpgradeGate
import build.raft.app.settings.buildStandaloneConversationThreadLaunchPayload
import build.raft.app.settings.iconName
import build.raft.app.settings.matches
import build.raft.app.settings.observe
import build.raft.app.settings.openRaftUpgradeDialog
import build.raft.app.settings.openSettingsBillingPage
import build.raft.app.settings.openSettingsFeedbackPage
import build.raft.app.settings.openSettingsMarketplacePage
import build.raft.app.settings.settingsBillingOffersUpgrade
import build.raft.app.shell.RaftLinkNavigator
import build.raft.app.shell.RaftLinkOpenDecision
import build.raft.app.shell.RaftLinkOpenIntent
import build.raft.app.shell.openRaftPage
import build.raft.app.shell.raftShellStoreScope
import build.raft.app.shell.rememberRaftLinkNavigator
import build.raft.app.thread.RaftThreadWebLinkTarget
import build.raft.app.thread.THREAD_PAGE
import build.raft.app.thread.dispatch
import build.raft.app.thread.icon
import build.raft.app.thread.memberDetailIdentity
import build.raft.app.thread.resolveMarkdownOpenRequest
import build.raft.app.thread.toRaftThreadWebLinkTarget
import build.raft.app.thread.toRaftTimeContext
import build.raft.app.thread.toThreadPageData
import build.raft.app.traceId
import build.raft.app.userFacingErrorCodeMessage
import build.raft.app.userFacingMessage
import build.raft.app.withRuntimePreferences
import build.raft.app.members.toRaftTimeContext
import build.raft.app.membersAgentErrorRecordStaleDetail
import androidx.compose.runtime.setValue
import build.raft.app.components.RaftSlotIcon
import build.raft.app.conversationIdentity
import build.raft.app.currentRaftEffectiveDateTimeContext
import build.raft.app.currentRaftTimeContextProvider
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.app.base.Utils
import build.raft.app.canSeeAgentMigrationLog
import build.raft.app.base.BridgeModule
import build.raft.app.data.isOpening
import build.raft.app.data.AGENT_MIGRATION_HOSTED_FEATURE_FLAG_KEY
import build.raft.app.data.AgentMigrationIntent
import build.raft.app.data.AgentMigrationStore
import build.raft.app.data.isRaftHostVisible
import build.raft.app.data.AgentAppGrantsRevokeStore
import build.raft.app.data.AgentSubagentStopStore
import build.raft.app.data.AgentTurnStopStore
import build.raft.app.data.AgentWorkerTimelineIntent
import build.raft.app.data.AgentWorkerTimelineStore
import build.raft.app.data.AgentWorkersIntent
import build.raft.app.data.AgentWorkersStore
import build.raft.app.data.RaftAgentControlsApi
import build.raft.app.data.AgentArchiveWorkspaceChoice
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentIssueReportRepository
import build.raft.app.data.AgentIssueReportResult
import build.raft.app.data.AgentIssueTicketRequest
import build.raft.app.data.AgentLifecycleActionResult
import build.raft.app.data.AgentProfileUpdateRequest
import build.raft.app.data.BridgeRaftAvatarNativeCache
import build.raft.app.data.ChannelRepository
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.isMemberHasComputerRefusal
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.MemberRepository
import build.raft.app.data.OpenDirectMessageResult
import build.raft.app.data.SharedAgentDetailActions
import build.raft.app.data.SharedAgentDetailIntent
import build.raft.app.data.SharedAgentDetailLoadStatus
import build.raft.app.data.SharedAgentDetailOwner
import build.raft.app.data.SharedAgentDetailState
import build.raft.app.data.SharedAgentDetailStore
import build.raft.app.data.SharedAgentIssueReportRequest
import build.raft.app.data.ProcessRaftDatetimeFoundationOwner
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftTimeFormatRuntime
import build.raft.app.data.RaftTimeZoneRuntime
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.get
import build.raft.app.iconName
import build.raft.app.localizedErrorPresentationStrings
import build.raft.app.matches
import build.raft.app.memberDetailAccessMode
import build.raft.app.memberDetailIdentity
import build.raft.app.memberDetailIdentityFromWire
import build.raft.app.memberKindWireValue
import build.raft.app.members.iconName
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.mergedSharedConversationDirectoryState
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.RaftAgentsNetworkApi
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.data.RaftComputersNetworkApi
import build.raft.app.data.SharedComputersIntent
import build.raft.app.data.SharedComputersPickerSlice
import build.raft.app.data.selectComputerServerSlice
import build.raft.app.data.SharedRuntimeAccountUsageStore
import build.raft.app.data.SharedCreateAgentFormStore
import build.raft.app.data.cachedAgentDetailFor
import build.raft.app.data.kuiklyBridgeCallContext
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.ThreadId
import build.raft.app.openAgentMigrationLogPage
import build.raft.app.openSettingsComputerPage
import build.raft.app.opt
import build.raft.app.optBoolean
import build.raft.app.optString
import build.raft.app.raftComputerCommandContext
import build.raft.app.raftFeedbackWebViewClientKind
import build.raft.app.raftScopedAgentStatusResolver
import build.raft.app.rememberAgentLoungeEnabled
import build.raft.app.rememberAgentLoungeOpener
import build.raft.app.rememberLiveRaftTimeContext
import build.raft.app.rememberRaftAgentStatusIndicatorState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.module.CodecModule
import build.raft.app.compat.json.JSONArray
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import kotlinx.coroutines.delay
import build.raft.app.data.map
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.selected
import build.raft.app.AgentMigrationComputerAgents
import build.raft.app.AgentRuntimeErrorKind
import build.raft.app.ComputerRuntimeAuthHost
import build.raft.app.ErrorPresentationStrings
import build.raft.app.LocalRaftArtifactOpenStoreScope
import build.raft.app.LocalRaftTimeContextProvider
import build.raft.app.MemberDetailAccessMode
import build.raft.app.MemberDetailIdentity
import build.raft.app.MemberDetailIdentitySource
import build.raft.app.MembersAgentLifecycleAction
import build.raft.app.RaftAgentActivitySubscriptionEffect
import build.raft.app.RaftCenteredLoading
import build.raft.app.RaftColor
import build.raft.app.RaftContentLucideIcon
import build.raft.app.RaftExternalAgentCreateHost
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.RaftTimeCapabilities
import build.raft.app.RaftTimeContextProvider
import build.raft.app.RaftTopPageHeader
import build.raft.app.SharedCreateAgentDialogSubmit
import build.raft.app.SharedCreateAgentMachineOption
import build.raft.app.SharedCreateAgentRuntimeModelCatalog
import build.raft.app.ThreadLaunchConversationIdentity
import build.raft.app.WireThreadLaunchPayload
import build.raft.app.agentMigrationAgentsByMachine
import build.raft.app.agentTypedRuntimeErrorKind
import build.raft.designsystem.components.RaftEmptyState
import build.raft.designsystem.components.RaftEmptyStateDefaults

private fun SharedAgentDetailState.toMembersAgentDetailLoadState(): MembersAgentDetailLoadState =
    when (detailLoadStatus) {
        SharedAgentDetailLoadStatus.Idle -> MembersAgentDetailLoadState.Idle
        SharedAgentDetailLoadStatus.Loading -> detail?.let { MembersAgentDetailLoadState.Content(it, refreshing = true) }
            ?: MembersAgentDetailLoadState.Loading
        SharedAgentDetailLoadStatus.Content -> detail?.let(MembersAgentDetailLoadState::Content)
            ?: MembersAgentDetailLoadState.Empty
        SharedAgentDetailLoadStatus.NoPermission ->
            MembersAgentDetailLoadState.NoPermission(
                detailPermissionBlocker ?: PermissionBlocker("server_forbidden")
            )
        SharedAgentDetailLoadStatus.Error ->
            MembersAgentDetailLoadState.Error(
                detailError ?: ScreenError("member_detail_load_failed")
            )
        SharedAgentDetailLoadStatus.Empty -> MembersAgentDetailLoadState.Empty
    }

/** Flag on and allowed: the overview binding for the Activity tab; otherwise the raw log only. */
private fun membersAgentActivityOverviewBindingOrNull(
    store: build.raft.app.data.SharedAgentActivityOverviewStore?,
    enabled: Boolean
): MembersAgentActivityOverviewBinding? {
    if (!enabled || store == null) return null
    return MembersAgentActivityOverviewBinding(state = store.state, onIntent = store::dispatch)
}

@Composable
private fun rememberMemberDetailRouteProvider(
    pager: Pager,
    bridge: BridgeModule,
    storeScopeOverride: RaftShellStoreScope? = null
): MemberDetailRouteProvider {
    val cacheScope = rememberCoroutineScope()
    val sharedStores = remember { build.raft.app.data.RaftSharedStoreRegistry.get(bridge) }
    val storeScope = storeScopeOverride ?: remember(pager.pageData.params.toString(), pager.pagerId) {
        pager.pageData.params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
    }
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val networkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val memberRepository: MemberRepository =
        remember(networkClient, authStore) {
            RealMemberRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = { message -> bridge.log("member_detail_repository $message") }
            )
        }
    val channelRepository: ChannelRepository =
        remember(networkClient, authStore) {
            RealChannelRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                warningLogger = { message -> bridge.log("member_detail_channel_repository $message") }
            )
        }
    val settingsRepository: build.raft.app.data.SettingsServerManagementRepository =
        remember(networkClient, authStore) {
            build.raft.app.data.RealSettingsRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = { message -> bridge.log("member_detail_settings_repository $message") }
            )
        }
    // This profile's own agent-detail store, kept while the route stays composed (under the pages
    // opened over it too): another agent's profile opened above cannot take it over, so Back shows
    // this page exactly as it was (Hands 11c0ed95). See SharedAgentDetailStore.openRouteStore.
    val shellAgentDetailStore = remember(sharedStores, storeScope) { sharedStores.agentDetailStore(storeScope) }
    val agentDetailStore = remember(shellAgentDetailStore) { shellAgentDetailStore.openRouteStore() }
    DisposableEffect(shellAgentDetailStore, agentDetailStore) {
        onDispose { shellAgentDetailStore.closeRouteStore(agentDetailStore) }
    }
    val selectedServerStore = remember(sharedStores, storeScope) { sharedStores.selectedServerStore(storeScope) }
    val agentPanelTabOrderStore = sharedStores.agentPanelTabOrderStore
    val serverMembersStore = remember(sharedStores, storeScope) { sharedStores.serverMembersStore(storeScope) }
    val scope = rememberCoroutineScope()
    val codecModule = remember(pager.pagerId) { pager.acquireModule<CodecModule>(CodecModule.MODULE_NAME) }
    val agentIssueReportRepository = remember(networkClient, authStore, selectedServerStore, codecModule) {
        AgentIssueReportRepository(
            sessionStore = authStore,
            selectedServerStore = selectedServerStore,
            networkClient = networkClient,
            sha256Hex = codecModule::sha256,
            logger = { message -> bridge.log("member_detail_agent_issue $message") },
            feedbackClientKind = raftFeedbackWebViewClientKind(),
            feedbackClientVersion = build.raft.app.network.RaftAppIdentityRegistry.current()?.version.orEmpty()
        )
    }
    val computersApiClient = remember(networkClient) {
        RaftComputersApiClient(
            RaftComputersNetworkApi(networkClient)
        )
    }
    // Data-layer plan step 5: the shell's owner-fenced, durable machine cache (no route-local copy),
    // so the agent's computer link / status paint from cache on the first frame.
    val computersStore = remember(sharedStores, storeScope) { sharedStores.computersStore(storeScope) }
    val agentsApiClient = remember(networkClient) {
        build.raft.app.data.RaftAgentsApiClient(
            transport = build.raft.app.data.RaftAgentsNetworkApi(networkClient),
            diagnosticsLogger = { message -> bridge.log("member_detail_agents_api $message") }
        )
    }
    val createAgentCoordinator = remember(agentsApiClient, computersApiClient) {
        build.raft.app.data.SharedCreateAgentCoordinator(
            agentsApiClient = agentsApiClient,
            computersApiClient = computersApiClient,
            logger = { message -> bridge.log("member_detail_runtime_models $message") }
        )
    }
    val createAgentFormStore = remember(sharedStores, storeScope) {
        sharedStores.createAgentFormStore(storeScope)
    }
    val runtimeAccountUsageStore = remember(sharedStores, storeScope) {
        sharedStores.runtimeAccountUsageStore(storeScope)
    }
    val activityOverviewRepository = remember(networkClient) {
        build.raft.app.data.RealAgentActivityOverviewRepository(
            networkClient = networkClient,
            logger = { message -> bridge.log("member_detail_$message") },
            contextPageSink = sharedStores.messageContextPageSink(storeScope),
        )
    }
    val agentControlsApi = remember(networkClient) { RaftAgentControlsApi(networkClient) }
    val avatarNativeCache =
        remember(bridge, scope) {
            BridgeRaftAvatarNativeCache(
                bridge = bridge,
                bridgeCallContext = scope.kuiklyBridgeCallContext()
            )
        }
    return remember(
        bridge,
        authStore,
        sharedStores.apiEnvironmentStore,
        memberRepository,
        channelRepository,
        settingsRepository,
        agentIssueReportRepository,
        agentDetailStore,
        selectedServerStore,
        agentPanelTabOrderStore,
        serverMembersStore,
        computersStore,
        createAgentCoordinator,
        createAgentFormStore,
        avatarNativeCache,
        sharedStores.conversationIdentityProvider(storeScope),
        sharedStores.sharedHomeStore(storeScope),
        storeScope,
        agentsApiClient,
        runtimeAccountUsageStore,
        activityOverviewRepository,
        agentControlsApi
    ) {
        MemberDetailRouteProvider(
            cacheScope = cacheScope,
            computersOwner = { sharedStores.computersOwner(storeScope) },
            bridge = bridge,
            authStore = authStore,
            apiEnvironmentStore = sharedStores.apiEnvironmentStore,
            memberRepository = memberRepository,
            channelRepository = channelRepository,
            settingsRepository = settingsRepository,
            agentIssueReportRepository = agentIssueReportRepository,
            agentDetailStore = agentDetailStore,
            selectedServerStore = selectedServerStore,
            agentPanelTabOrderStore = agentPanelTabOrderStore,
            serverMembersStore = serverMembersStore,
            computersStore = computersStore,
            createAgentCoordinator = createAgentCoordinator,
            createAgentFormStore = createAgentFormStore,
            avatarCacheRuntime = sharedStores.avatarCacheRuntime,
            avatarNativeCache = avatarNativeCache,
            conversationIdentityProvider = sharedStores.conversationIdentityProvider(storeScope),
            sharedHomeStore = sharedStores.sharedHomeStore(storeScope),
            storeScope = storeScope,
            agentsApiClient = agentsApiClient,
            runtimeAccountUsageStore = runtimeAccountUsageStore,
            serverFeatureFlags = sharedStores.serverFeatureFlags,
            activityOverviewRepository = activityOverviewRepository,
            agentActivityOverviewCache = sharedStores.agentActivityOverviewCache,
            messageContextPageSink = sharedStores.messageContextPageSink(storeScope),
            agentControlsApi = agentControlsApi,
            agentDetailCache = sharedStores.agentDetailCache,
            agentUpdatedSignals = sharedStores.agentUpdatedSignals,
            agentWorkersChangedSignals = sharedStores.agentWorkersChangedSignals,
            accountBinding = { sharedStores.currentAccountBindingOrNull() },
            memberRemovalImpactRepository = build.raft.app.data.NetworkMemberRemovalImpactRepository(networkClient)
        )
    }
}

/**
 * First frame of an agent detail route: when the bound account has a cached canonical detail for
 * the agent on the active server, seed the shell's detail store with it (flagged as a cache replay)
 * and start in the Agent state instead of the spinner.
 */
private fun MemberDetailRouteProvider.seedCachedAgentDetailRoute(
    identity: MemberDetailIdentity?,
    activeServerId: String?
): MemberDetailRouteState? {
    identity ?: return null
    if (identity.kind != MemberKind.Agent) return null
    if (identity.memberDetailAccessMode(activeServerId) != MemberDetailAccessMode.LocalFull) return null
    val serverId = membersAgentProfileRequestServerId(identity, activeServerId) ?: return null
    val (owner, cached) = peekCachedAgentDetail(identity.memberId, serverId) ?: return null
    agentDetailStore.seedCachedAgentDetail(identity.memberId, owner, profileGeneration = 0L, cached = cached)
    return MemberDetailRouteState.Agent(server = cached.server, currentUserId = cached.currentUserId)
}

/** Replays [cached] into the store as [agentId]'s detail under [owner] / [profileGeneration]. */
internal fun SharedAgentDetailStore.seedCachedAgentDetail(
    agentId: MemberId,
    owner: SharedAgentDetailOwner,
    profileGeneration: Long,
    cached: build.raft.app.data.RaftCachedAgentDetail
) {
    dispatch(SharedAgentDetailIntent.SelectMember(agentId, owner, profileGeneration))
    dispatch(
        SharedAgentDetailIntent.SetDetail(
            agentId = agentId,
            detail = cached.detail,
            serverId = cached.server.id,
            owner = owner,
            profileGeneration = profileGeneration,
            fromCache = true
        )
    )
}

/** The route already paints this agent's cached detail: a refresh must not blank it. */
private fun MemberDetailRouteState.isCachedAgentDetailRoute(
    identity: MemberDetailIdentity,
    agentDetailState: SharedAgentDetailState
): Boolean =
    this is MemberDetailRouteState.Agent &&
        identity.kind == MemberKind.Agent &&
        agentDetailState.agentId == identity.memberId &&
        agentDetailState.detailServerId == server.id &&
        agentDetailState.detail?.agentId == identity.memberId

@Composable
internal fun MemberDetailPageContent(
    pageVisibleTick: Int,
    embeddedPayload: RaftMemberDetailPayload? = null,
    storeScopeOverride: RaftShellStoreScope? = null,
    onClose: (() -> Unit)? = null,
    /** The pager's own visibility (covered / backgrounded); embedded hosts stay visible. */
    pageVisible: Boolean = true,
    /**
     * Embedded in the wide shell: opens a member's DM in the shell's content area (Chat with the DM
     * selected) instead of a page over the shell; false falls back to the page.
     */
    openDirectMessageInShell: ((ThreadLaunchConversationIdentity) -> Boolean)? = null,
) {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val errorStrings = localizedErrorPresentationStrings()
    val linkNavigator = rememberRaftLinkNavigator(pager, bridge)
    val memberIdCopied = stringResource(Res.string.member_notice_member_id_copied)
    val diagnosticsCopied = stringResource(Res.string.member_notice_diagnostics_copied)
    val removeFromSettings = stringResource(Res.string.member_notice_remove_from_settings)
    val tabOrderSaveError = stringResource(Res.string.member_error_tab_order_save)
    val tabOrderSaveErrorTemplate = membersStringTemplate(Res.string.member_error_tab_order_save_detail)
    val tabOrderPermissionError = stringResource(Res.string.member_error_tab_order_permission)
    val reminderOpenError = stringResource(Res.string.member_error_open_reminder)
    val directMessageOpenError = stringResource(Res.string.member_error_open_direct_message)
    val loungeOpenFailed = stringResource(Res.string.agent_lounge_open_failed)
    val directMessageOpenErrorTemplate = membersStringTemplate(Res.string.member_error_open_direct_message_detail)
    val directMessagePermissionError = stringResource(Res.string.member_error_open_direct_message_permission)
    val openProfileFailed = stringResource(Res.string.member_error_open_profile)
    val payload = embeddedPayload ?: remember(pager.pageData.params.toString()) {
        pager.pageData.params.toRaftMemberDetailPayload()
    }
    val visualSeed = remember(payload) { payload.toMemberDetailVisualSeedOrNull() }
    if (embeddedPayload == null && visualSeed != null) {
        MemberDetailVisualSeedContent(
            payload = payload,
            seed = visualSeed,
            pageVisibleTick = pageVisibleTick
        )
        return
    }
    val provider = rememberMemberDetailRouteProvider(pager, bridge, storeScopeOverride)
    val closeDetail = onClose ?: { provider.bridge.closePage() }
    val routeScope = rememberCoroutineScope()
    val agentTurnStopStore =
        remember(provider, routeScope) {
            provider.agentControlsApi?.let { api -> AgentTurnStopStore(api::interruptTurn, routeScope) }
        }
    val agentSubagentStopStore =
        remember(provider, routeScope) {
            provider.agentControlsApi?.let { api -> AgentSubagentStopStore(api::interruptSubagent, routeScope) }
        }
    val agentWorkersStore =
        remember(provider, routeScope) {
            provider.agentControlsApi?.let { api ->
                AgentWorkersStore(
                    listWorkers = api::listWorkers,
                    stopWorker = api::stopWorker,
                    scope = routeScope,
                    listRecentWorkers = api::listRecentWorkers,
                    workersChangedSignals =
                        provider.agentWorkersChangedSignals?.events ?: kotlinx.coroutines.flow.emptyFlow(),
                )
            }
        }
    val agentWorkerTimelineStore =
        remember(provider, routeScope) {
            provider.agentControlsApi?.let { api ->
                AgentWorkerTimelineStore(
                    getTimeline = api::getWorkerTimeline,
                    scope = routeScope,
                )
            }
        }
    LaunchedEffect(agentWorkersStore, pageVisible) {
        agentWorkersStore?.dispatch(AgentWorkersIntent.SetPageVisible(pageVisible))
    }
    val agentMigrationStore =
        remember(provider, routeScope) {
            provider.agentControlsApi?.let { api ->
                AgentMigrationStore(
                    dataSource = api,
                    scope = routeScope,
                    isHostVisible = { isRaftHostVisible(provider.storeScope.shellInstanceId) }
                )
            }
        }
    // The migration poll runs only while this page and the app are on screen.
    LaunchedEffect(agentMigrationStore, pageVisible, pageVisibleTick) {
        agentMigrationStore?.dispatch(AgentMigrationIntent.SetPageVisible(pageVisible))
    }
    val memberIdentity = payload.identity
    // The Apps tab's Revoke (web AgentAppAccessTab): one store per shown agent, so a confirmation
    // never carries over to another agent's page; what it changes goes to this route's detail.
    val agentAppGrantsRevokeStore =
        remember(provider, routeScope, memberIdentity?.memberId) {
            AgentAppGrantsRevokeStore(
                revokeGrant = { grantId -> provider.memberRepository.revokeAgentAppGrant(DataRequestPriority.Foreground, grantId) },
                loadAppLogins = { agentId -> provider.memberRepository.getAgentAppLogins(DataRequestPriority.Visible, agentId) },
                applyToDetail = provider::updateAgentAppLogins,
                scope = routeScope,
            )
        }
    val activeServerId by provider.selectedServerStore.currentServerId.collectAsState()
    var routeState by remember(memberIdentity) {
        mutableStateOf(
            memberDetailInitialRouteState(
                identity = memberIdentity,
                activeServerId = provider.selectedServerStore.currentServerId.value,
                agentDetailState = provider.agentDetailStore.state.value,
                serverMembersState = provider.serverMembersStore.state.value,
                currentUserId = provider.accountBinding()?.second
            ) ?: provider.seedCachedAgentDetailRoute(memberIdentity, provider.selectedServerStore.currentServerId.value)
                ?: MemberDetailRouteState.Loading
        )
    }
    var localAgentDetailRefreshKey by remember { mutableStateOf(0) }
    var agentProfileRequestGeneration by remember { mutableStateOf(0L) }
    var agentActivityLoadState by remember(memberIdentity) {
        mutableStateOf<MembersAgentActivityLoadState>(MembersAgentActivityLoadState.Idle)
    }
    var agentActivityRequestGeneration by remember { mutableStateOf(0L) }
    val activityOverviewServerId =
        (routeState as? MemberDetailRouteState.Agent)?.server?.id
            ?: memberIdentity?.sourceServerId
            ?: activeServerId?.trim()?.takeIf { it.isNotBlank() }?.let(::ServerId)
    val activityOverviewStore =
        remember(provider, memberIdentity, activityOverviewServerId) {
            provider.activityOverviewRepository?.let { repository ->
                build.raft.app.data.SharedAgentActivityOverviewStore(
                    repository = repository,
                    scope = routeScope,
                    logger = { message -> bridge.log("member_detail_$message") },
                    snapshotCache = provider.agentActivityOverviewCache,
                    cacheKeyFor = { agentId ->
                        val binding = provider.accountBinding()
                        val env = binding?.first ?: provider.apiEnvironmentStore.current().apiBaseUrl
                        val accountId = binding?.second
                        build.raft.app.data.agentActivityOverviewCacheKey(
                            environmentId = env,
                            accountId = accountId,
                            serverId = activityOverviewServerId?.value,
                            agentId = agentId,
                        )
                    },
                    initialAgentId = memberIdentity?.takeIf { it.kind == MemberKind.Agent }?.memberId,
                )
            }
        }
    LaunchedEffect(activityOverviewStore, pageVisible) {
        activityOverviewStore?.dispatch(
            build.raft.app.data.AgentActivityOverviewIntent.SetPageVisible(pageVisible)
        )
    }
    // Flag reads below follow an evaluation that lands after first frame.
    val serverFeatureFlagRevision = provider.serverFeatureFlags?.snapshotRevision?.collectAsState()?.value
    val agentDetailStoreState by provider.agentDetailStore.state.collectAsState()
    val pendingDirectMessageOpens by provider.directMessageOpens.pending.collectAsState()
    // Agent Lounge v0: the profile's Lounge action (web useOpenAgentLounge).
    val loungeOpener = rememberAgentLoungeOpener(bridge, provider.storeScope)
    val pendingLoungeOpens by loungeOpener.opening.collectAsState()
    val agentPanelTabOrderState by provider.agentPanelTabOrderStore.state.collectAsState()
    val serverMembersState by provider.serverMembersStore.state.collectAsState()
    val selectedMemberId = memberIdentity?.memberId
    val selectedAgentRouteServerId = (routeState as? MemberDetailRouteState.Agent)?.server?.id
    val selectedAgentDetailStoreStateMatches =
        selectedMemberId != null &&
            selectedAgentRouteServerId != null &&
            agentDetailStoreState.agentId == selectedMemberId &&
            agentDetailStoreState.detailServerId == selectedAgentRouteServerId
    val selectedAgentDetail = agentDetailStoreState.detail?.takeIf {
        selectedAgentDetailStoreStateMatches && it.agentId == selectedMemberId
    }
    val agentDetailState =
        if (selectedAgentDetailStoreStateMatches) {
            agentDetailStoreState.toMembersAgentDetailLoadState()
        } else {
            MembersAgentDetailLoadState.Loading
        }
    val timeContextProvider = rememberMemberDetailTimeContextProvider(payload, pageVisibleTick, pageVisible)
    val fallbackDateContext = currentRaftEffectiveDateTimeContext(payload.toRaftTimeContextOrNull())
    val timeContext =
        (if (payload.nowEpochMillis != null) timeContextProvider.current else timeContextProvider.dateContext)
            ?: fallbackDateContext
    val initialAgentTab = remember(payload.initialAgentTab) { payload.initialAgentTab.toMembersAgentDetailTabOrNull() }
    val expectedKind = memberIdentity?.kind
    val statusLookupServerId =
        when (val current = routeState) {
            is MemberDetailRouteState.Agent ->
                current.server.id.value.takeIf { current.accessMode.allowsPrivateAgentSurfaces }
            else -> null
        }
    // The detail page shows the agent's live activity: detailed activity while it is open (own server).
    RaftAgentActivitySubscriptionEffect(
        subscriptions = remember(bridge) { build.raft.app.data.RaftSharedStoreRegistry.get(bridge) }.agentActivitySubscriptions,
        serverId = statusLookupServerId,
        agentIds = listOfNotNull(selectedMemberId?.value),
    )
    // Human details can render created-agent rows, but they must not opt into the
    // agent-only computers/runtime surface. Bind only the status/avatar read edge
    // to the route's canonical server; remote profile-only agents remain unbound.
    val statusResolverServerId = memberDetailStatusResolverServerId(routeState)
    val agentPanelTabOrderServerId =
        when (val current = routeState) {
            is MemberDetailRouteState.Agent ->
                current.server.id.value.takeIf { current.accessMode.allowsPrivateAgentSurfaces }
            else -> null
        }
    val computerPickerSlice by provider.computersStore.computerPickerSliceFlow(statusLookupServerId).collectAsState(
        SharedComputersPickerSlice()
    )
    var agentRuntimeModelCatalogs by remember(memberIdentity, statusLookupServerId) {
        mutableStateOf<Map<Pair<String, String>, SharedCreateAgentRuntimeModelCatalog>>(emptyMap())
    }
    var agentRuntimeModelLoadsInFlight by remember(memberIdentity, statusLookupServerId) {
        mutableStateOf<Set<Pair<String, String>>>(emptySet())
    }
    var agentRuntimeOptionRequestGenerations by remember(memberIdentity, statusLookupServerId) {
        mutableStateOf<Map<Pair<String, String>, Long>>(emptyMap())
    }
    LaunchedEffect(provider.computersStore, statusLookupServerId) {
        val serverId = statusLookupServerId?.trim()?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        provider.computersStore.dispatch(SharedComputersIntent.EnsureComputerPicker(serverId))
    }
    // Task #259: the status row's truth source is the computers store, not `machineId != null`.
    // The slice for this server is what EnsureComputerPicker above is loading; the resolver
    // distinguishes "store has not answered" (render nothing) from "answered, machine absent"
    // ("no computer assigned") -- web collapses the two and flashes the latter on every open.
    val computersState by provider.computersStore.state.collectAsState()
    val migrationComputers by
        remember(provider.computersStore, statusLookupServerId) {
            provider.computersStore.migrationComputersFlow(statusLookupServerId)
        }.collectAsState(emptyList())
    val machineStatusRow: (String?) -> MembersMachineStatusRow =
        remember(computersState, statusLookupServerId) {
            // selectComputerServerSlice normalises the key; indexing serverSlices directly
            // would miss silently and read as "store never answered".
            val slice = computersState.selectComputerServerSlice(statusLookupServerId)
            ;
            { machineId ->
                membersMachineStatusRow(
                    machineId = machineId,
                    machines = slice.machines,
                    loadStatus = slice.status,
                    hasSnapshot = slice.hasSnapshot
                )
            }
        }
    LaunchedEffect(provider.agentPanelTabOrderStore, agentPanelTabOrderServerId, payload.agentPanelTabOrder) {
        val serverId = agentPanelTabOrderServerId ?: return@LaunchedEffect
        provider.agentPanelTabOrderStore.bindServer(
            serverId = serverId,
            initialOrder = payload.agentPanelTabOrder,
            source = "member_detail_bind_payload",
            logger = { message -> bridge.log(message) }
        )
    }
    // Task #22: the user's tab order (synced from the server's sidebar order) must be on screen
    // from the first frame. knownOrderFor reads the store, or the order persisted on device when
    // the store is not bound to this server yet, synchronously during composition.
    val agentTabOrderRender =
        remember(agentPanelTabOrderState, agentPanelTabOrderServerId, payload.agentPanelTabOrder) {
            membersAgentTabOrderForRender(
                orderServerId = agentPanelTabOrderServerId,
                knownOrder = provider.agentPanelTabOrderStore.knownOrderFor(agentPanelTabOrderServerId),
                payloadOrder = payload.agentPanelTabOrder
            )
        }
    val agentPanelTabOrder = agentTabOrderRender.order
    // Cold start with nothing on device: hold the tab strip until the order is known, so it never
    // draws the default order and then reorders. Latched per member; a bounded wait keeps an
    // offline open from hiding the tabs (the selection is pinned once shown, see MembersDetailContent).
    var agentTabOrderReady by remember(memberIdentity) { mutableStateOf(agentTabOrderRender.known) }
    if (!agentTabOrderReady && agentTabOrderRender.known) agentTabOrderReady = true
    LaunchedEffect(memberIdentity, agentTabOrderReady) {
        if (agentTabOrderReady) return@LaunchedEffect
        delay(MembersAgentTabOrderWaitMillis)
        agentTabOrderReady = true
    }
    val initialAgentTabForRender = remember(memberIdentity, agentTabOrderReady) {
        membersInitialAgentDetailTabForRender(
            expectedKind = expectedKind,
            explicitInitialTab = initialAgentTab,
            payloadAgentPanelTabOrder = payload.agentPanelTabOrder,
            orderKnown = agentTabOrderRender.known
        )
    }
    val agentStatusResolver =
        remember(provider.serverMembersStore, statusResolverServerId) {
            raftScopedAgentStatusResolver(provider.serverMembersStore, statusResolverServerId)
        }
    val memberAvatarResolver =
        remember(provider.serverMembersStore, statusResolverServerId) {
            if (statusResolverServerId == null) {
                EmptyRaftMemberAvatarResolver
            } else {
                raftMemberAvatarResolver(provider.serverMembersStore, statusResolverServerId)
            }
        }

    suspend fun refreshVisibleAgentActivity(
        current: MemberDetailRouteState.Agent,
        agentId: MemberId
    ) {
        if (!current.accessMode.allowsPrivateAgentSurfaces) return
        val detailState = provider.agentDetailStore.state.value
        val hasCachedActivity =
            detailState.detail
                ?.takeIf {
                    it.agentId == agentId &&
                        detailState.detailServerId == current.server.id
                }
                ?.activity
                ?.isNotEmpty() == true
        val generation = agentActivityRequestGeneration + 1L
        agentActivityRequestGeneration = generation
        val fence =
            MembersAgentActivityRequestFence(
                memberId = agentId,
                serverId = current.server.id,
                generation = generation
            )
        val presenceRequest =
            provider.serverMembersStore.beginAgentActivityPresenceProjection(current.server.id, agentId)
        agentActivityLoadState = membersAgentActivityLoadStart(hasCachedActivity)
        val result = provider.loadAgentActivity(agentId)
        val routeServerId = (routeState as? MemberDetailRouteState.Agent)?.server?.id
        if (!fence.matches(memberIdentity, routeServerId, agentActivityRequestGeneration)) return
        if (result is DataResult.Content) {
            provider.agentDetailStore.dispatch(
                SharedAgentDetailIntent.SetAgentActivity(
                    agentId = agentId,
                    activity = result.data,
                    serverId = current.server.id
                )
            )
            provider.serverMembersStore.applyAgentActivitySnapshot(presenceRequest, agentId, result.data)
        }
        agentActivityLoadState = result.toMembersAgentActivityLoadState()
    }

    var humanDescriptionEditRevision by remember(memberIdentity) { mutableStateOf(0L) }

    LaunchedEffect(memberIdentity, activeServerId, localAgentDetailRefreshKey) {
        if (memberIdentity == null) {
            routeState = MemberDetailRouteState.Error("member_identity_missing")
            return@LaunchedEffect
        }
        val startHumanDescriptionRevision = humanDescriptionEditRevision
        if (
            (memberIdentity.kind == MemberKind.Agent || routeState !is MemberDetailRouteState.Human) &&
            !routeState.isCachedAgentDetailRoute(memberIdentity, provider.agentDetailStore.state.value)
        ) {
            routeState = MemberDetailRouteState.Loading
        }
        val generation = agentProfileRequestGeneration + 1L
        agentProfileRequestGeneration = generation
        val requestServerId = membersAgentProfileRequestServerId(memberIdentity, activeServerId)
        val requestOwner =
            if (memberIdentity.kind == MemberKind.Agent) {
                requestServerId?.let { serverId ->
                    provider.currentAgentDetailOwner(
                        expectedServerId = serverId,
                        requireCurrentServer =
                            memberIdentity.memberDetailAccessMode(activeServerId).allowsPrivateAgentSurfaces
                    )
                }
            } else {
                null
            }
        val requestFence =
            requestServerId?.let { serverId ->
                MembersAgentProfileRequestFence(
                    memberId = memberIdentity.memberId,
                    serverId = serverId,
                    generation = generation
                )
            }
        var hasCachedAgentDetail = false
        // A remote agent's profile is the seed its opener left (thread roster / author row): read
        // it before the canonical-request reset below clears the store.
        val remoteAgentSeed =
            if (
                memberIdentity.kind == MemberKind.Agent &&
                membersAgentProfileIsRemote(memberIdentity.memberDetailAccessMode(activeServerId))
            ) {
                provider.agentDetailStore.state.value.detail?.takeIf { it.agentId == memberIdentity.memberId }
            } else {
                null
            }
        if (memberIdentity.kind == MemberKind.Agent) {
            if (requestOwner == null) {
                routeState = MemberDetailRouteState.Error("member_request_stale")
                return@LaunchedEffect
            }
            // Data-layer plan step 5: this account's last canonical answer for the agent on this
            // server paints at once (no spinner, no edit authority) while the request refreshes it.
            // Offline-first: memory, else the whitelisted record on disk (a cold start / offline open).
            val cached =
                if (memberIdentity.memberDetailAccessMode(activeServerId) == MemberDetailAccessMode.LocalFull) {
                    provider.agentDetailCache?.cachedAgentDetailFor(requestOwner, memberIdentity.memberId)
                } else {
                    null
                }
            if (cached != null) {
                hasCachedAgentDetail = true
                provider.agentDetailStore.seedCachedAgentDetail(memberIdentity.memberId, requestOwner, generation, cached)
                routeState = MemberDetailRouteState.Agent(server = cached.server, currentUserId = cached.currentUserId)
            } else {
                // Remove roster-derived seeds before the canonical request. They are useful for list
                // rows, but have neither a profile revision nor runtime-config authority.
                provider.agentDetailStore.dispatch(SharedAgentDetailIntent.DeselectMember)
                provider.agentDetailStore.dispatch(
                    SharedAgentDetailIntent.SelectMember(memberIdentity.memberId, requestOwner, generation)
                )
                provider.agentDetailStore.dispatch(SharedAgentDetailIntent.SetDetailLoading(memberIdentity.memberId))
            }
        }
        val loaded = provider.loadMember(memberIdentity, requestOwner, generation, {
            requestFence?.matches(memberIdentity, activeServerId, agentProfileRequestGeneration) == true
        }, hasCachedAgentDetail = hasCachedAgentDetail, remoteAgentSeed = remoteAgentSeed,
            sourceServerName = payload.sourceServerName,
            profileChannelId = payload.profileChannelId?.let(::ChannelId))
        if (
            !memberDetailAppliesLoadedRoute(
                identity = memberIdentity,
                shown = routeState,
                loaded = loaded,
                agentRequestCurrent =
                    requestFence?.matches(memberIdentity, activeServerId, agentProfileRequestGeneration) != false
            )
        ) {
            if (loaded is MemberDetailRouteState.Error && memberIdentity.kind != MemberKind.Agent) {
                bridge.log("member_detail_human_refresh_kept_roster code=${loaded.error.code}")
            }
            return@LaunchedEffect
        }
        val reconciled =
            if (loaded is MemberDetailRouteState.Human && humanDescriptionEditRevision != startHumanDescriptionRevision) {
                val confirmed = (routeState as? MemberDetailRouteState.Human)?.takeIf { it.member.id == loaded.member.id }
                if (confirmed != null) {
                    provider.serverMembersStore.updateHumanDescription(confirmed.member.id, confirmed.member.description)
                    loaded.copy(member = loaded.member.copy(description = confirmed.member.description))
                } else {
                    loaded
                }
            } else {
                loaded
            }
        routeState = reconciled
        // The canonical detail request already includes the freshly merged durable/current
        // activity snapshot. Do not immediately repeat the same visible activity request.
    }
    // Realtime `agent:updated { agentId }` (a profile edit, or botiverse/slock #13492: the agent's
    // modelLimits signal may have changed): the app shell re-reads the roster, and the open Profile
    // re-reads this agent's detail in the background, keeping what it shows until the answer lands.
    // One background re-read of the open agent's detail, keeping what it shows until the answer
    // lands. Skipped for another server's profile, and while a first load (or a cache replay still
    // refreshing) brings the fresh answer itself.
    suspend fun rereadAgentDetailInBackground(identity: MemberDetailIdentity, signal: build.raft.app.data.RaftAgentUpdatedSignal? = null) {
        val route = routeState as? MemberDetailRouteState.Agent ?: return
        if (route.accessMode != MemberDetailAccessMode.LocalFull) return
        if (signal != null && !signal.isFor(route.server.id.value, identity.memberId.value)) return
        val state = provider.agentDetailStore.state.value
        val owner = state.detailOwner ?: return
        if (
            state.agentId != identity.memberId ||
            state.detailFromCache ||
            state.detailLoadStatus != SharedAgentDetailLoadStatus.Content
        ) {
            return
        }
        val generation = state.detailProfileGeneration
        val requestGeneration = agentProfileRequestGeneration
        val current = {
            agentProfileRequestGeneration == requestGeneration &&
                provider.agentDetailStore.state.value.let {
                    it.agentId == identity.memberId && it.detailProfileGeneration == generation
                }
        }
        val loaded = provider.loadMember(identity, owner, generation, current, hasCachedAgentDetail = true)
        if (!current() || (loaded as? MemberDetailRouteState.Error)?.error?.code == "member_request_stale") {
            return
        }
        routeState = loaded
    }
    LaunchedEffect(provider, memberIdentity) {
        val signals = provider.agentUpdatedSignals ?: return@LaunchedEffect
        val identity = memberIdentity?.takeIf { it.kind == MemberKind.Agent } ?: return@LaunchedEffect
        signals.events.collect { signal -> rereadAgentDetailInBackground(identity, signal) }
    }
    // botiverse/slock #13435: a kept BillingError shows while the agent starts again, and the
    // server clears it once the provider answers, then sends `agent:updated`. That signal can be
    // missed (a reconnect, the app in the background, a load in flight when it came), so while the
    // banner shows, the agent moving into a running state and the page coming back on screen each
    // re-read the detail once.
    val billingRereadAgentId = memberIdentity?.takeIf { it.kind == MemberKind.Agent }?.memberId
    val billingRereadStatus =
        rememberRaftAgentStatusIndicatorState(agentStatusResolver, billingRereadAgentId?.value)?.status
    val billingErrorShown =
        agentDetailStoreState.agentId == billingRereadAgentId &&
            agentDetailStoreState.detail?.summary.let {
                agentTypedRuntimeErrorKind(it?.lastRuntimeErrorClass, it?.lastRuntimeErrorReason) ==
                    AgentRuntimeErrorKind.BillingExhausted
            }
    val billingRereadOnRun = membersBillingErrorRereadDue(billingErrorShown, billingRereadStatus)
    LaunchedEffect(provider, memberIdentity, billingRereadOnRun) {
        val identity = memberIdentity?.takeIf { it.kind == MemberKind.Agent } ?: return@LaunchedEffect
        if (billingRereadOnRun) rereadAgentDetailInBackground(identity)
    }
    // botiverse/slock #13276 (web agentErrorRecordRefresh): the server pushes an error status before
    // it stores the error (with its `requiredAction`, which names an unusable sign-in or plan), so a
    // live error the kept record does not describe yet re-reads the detail once the burst settles,
    // at most once per error detail.
    val errorRecordStaleDetail =
        if (agentDetailStoreState.agentId == billingRereadAgentId) {
            membersAgentErrorRecordStaleDetail(billingRereadStatus, agentDetailStoreState.detail?.summary)
        } else {
            null
        }
    var errorRecordRereadDetail by remember(billingRereadAgentId) { mutableStateOf<String?>(null) }
    LaunchedEffect(provider, memberIdentity, errorRecordStaleDetail) {
        val identity = memberIdentity?.takeIf { it.kind == MemberKind.Agent } ?: return@LaunchedEffect
        val detail = errorRecordStaleDetail ?: return@LaunchedEffect
        if (detail == errorRecordRereadDetail) return@LaunchedEffect
        delay(MembersAgentErrorRecordRereadDelayMs)
        errorRecordRereadDetail = detail
        rereadAgentDetailInBackground(identity)
    }
    val currentBillingErrorShown by rememberUpdatedState(billingErrorShown)
    LaunchedEffect(provider, memberIdentity, pageVisibleTick) {
        val identity = memberIdentity?.takeIf { it.kind == MemberKind.Agent } ?: return@LaunchedEffect
        if (pageVisibleTick > 0 && currentBillingErrorShown) rereadAgentDetailInBackground(identity)
    }
    LaunchedEffect(provider.serverMembersStore, statusResolverServerId) {
        val serverId = statusResolverServerId ?: return@LaunchedEffect
        provider.serverMembersStore.refreshMembers(ServerId(serverId), DataRequestPriority.Background)
    }
    LaunchedEffect(
        provider,
        memberIdentity,
        statusLookupServerId,
        expectedKind,
        agentPanelTabOrderState.serverId,
        agentPanelTabOrderState.loaded
    ) {
        statusLookupServerId?.trim()?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (expectedKind == MemberKind.Human) return@LaunchedEffect
        if (agentPanelTabOrderState.serverId == statusLookupServerId && agentPanelTabOrderState.loaded) {
            return@LaunchedEffect
        }
        when (val result = provider.loadAgentPanelTabOrder()) {
            is DataResult.Content ->
                provider.agentPanelTabOrderStore.applyLoaded(
                    serverId = statusLookupServerId,
                    order = result.data,
                    source = "member_detail_sidebar_order_get",
                    logger = { message -> bridge.log(message) }
                )
            is DataResult.Empty ->
                provider.agentPanelTabOrderStore.applyLoaded(
                    serverId = statusLookupServerId,
                    order = emptyList(),
                    source = "member_detail_sidebar_order_empty",
                    logger = { message -> bridge.log(message) }
                )
            is DataResult.Error -> {
                provider.agentPanelTabOrderStore.applyLoadFailed(
                    serverId = statusLookupServerId,
                    source = "member_detail_sidebar_order_error_fallback",
                    logger = { message -> bridge.log(message) }
                )
                bridge.log("member_detail_agent_tab_order_load_failed code=${result.error.code}")
            }
            is DataResult.NoPermission -> {
                provider.agentPanelTabOrderStore.applyLoadFailed(
                    serverId = statusLookupServerId,
                    source = "member_detail_sidebar_order_blocked_fallback",
                    logger = { message -> bridge.log(message) }
                )
                bridge.log("member_detail_agent_tab_order_load_blocked code=${result.blocker.code}")
            }
        }
    }

    val runtimeAccountUsageOwnerTracker = remember(provider) {
        MembersRuntimeAccountUsageOwnerTracker { provider.currentRuntimeAccountUsageOwner() }
    }
    LaunchedEffect(runtimeAccountUsageOwnerTracker, activeServerId, pageVisibleTick) {
        runtimeAccountUsageOwnerTracker.refresh()
    }
    // Live identity boundary: a sign-out / account / environment change clears a usage card that
    // was already loaded for the previous identity, even while this page stays composed.
    LaunchedEffect(runtimeAccountUsageOwnerTracker, provider.authStore) {
        (provider.authStore as? build.raft.app.data.RaftAuthSessionEventSource)
            ?.authSessionEvents
            ?.collect { event -> runtimeAccountUsageOwnerTracker.onAuthSessionEvent(event) }
    }
    val runtimeAccountUsageOwner by runtimeAccountUsageOwnerTracker.owner.collectAsState()
    // Who is looking, for the pickers' quota lines (web canViewMachineRuntimeAccountUsage).
    var runtimeAccountUsageViewer by remember { mutableStateOf<build.raft.app.data.RuntimeAccountUsageViewer?>(null) }
    LaunchedEffect(runtimeAccountUsageOwner, activeServerId) {
        runtimeAccountUsageViewer =
            if (runtimeAccountUsageOwner == null) null
            else build.raft.app.data.runtimeAccountUsageViewerFor(provider.authStore.readSession())
    }
    // Task #137: read-only runtime account usage for the agent's Runtime Config chip. Only
    // routes that allow private agent surfaces get a server id; the server still decides
    // access (403 hides the entry).
    val runtimeAuthHost =
        remember(bridge, provider.storeScope) {
            ComputerRuntimeAuthHost(
                realtime = build.raft.app.data.RaftSharedStoreRegistry.get(bridge).realtimeDomainEventOwner(provider.storeScope),
                openUrl = { url -> bridge.openUrl(url, inBrowser = true) },
            )
        }
    val runtimeAccountUsageAccess =
        remember(provider.runtimeAccountUsageStore, provider.computersStore, runtimeAccountUsageOwner, statusLookupServerId, runtimeAccountUsageOwnerTracker, runtimeAccountUsageViewer, runtimeAuthHost) {
            provider.runtimeAccountUsageStore?.let { store ->
                MembersRuntimeAccountUsageAccess(
                    store = store,
                    owner = runtimeAccountUsageOwner,
                    serverId = statusLookupServerId,
                    onIdentityChanged = { routeScope.launch { runtimeAccountUsageOwnerTracker.invalidate() } },
                    log = { message -> bridge.log(message) },
                    viewer = runtimeAccountUsageViewer,
                    auth = runtimeAuthHost,
                    serverFeatureFlags = provider.serverFeatureFlags,
                    computersStateFlow = provider.computersStore.state,
                )
            }
        }
    // The viewer's own personal assistant, for its owner's section of the agent profile.
    val assistantOwnerScope = rememberMemberDetailAssistantOwnerScope(provider, routeState as? MemberDetailRouteState.Agent, routeScope, closeDetail)
    val agentVoiceSectionScope =
        rememberMemberDetailAgentVoiceSectionScope(
            provider = provider,
            agentRoute = routeState as? MemberDetailRouteState.Agent,
            detail = selectedAgentDetail,
            timeContext = timeContextProvider.current ?: timeContext,
        )
    val profileDescriptionServerId =
        when (val current = routeState) {
            is MemberDetailRouteState.Agent -> current.server.id.value
            is MemberDetailRouteState.Human -> current.server.id.value
            else -> activeServerId
        }
    val profileDescriptionArtifacts = rememberMemberDetailProfileDescriptionArtifactHost(bridge, profileDescriptionServerId)
    CompositionLocalProvider(
        LocalMembersRuntimeAccountUsageAccess provides runtimeAccountUsageAccess,
        LocalAssistantOwnerScope provides assistantOwnerScope,
        build.raft.app.agents.LocalAgentVoiceSectionScope provides agentVoiceSectionScope,
        LocalRaftTimeContextProvider provides timeContextProvider,
        LocalRaftProfileDescriptionArtifacts provides profileDescriptionArtifacts,
        LocalRaftArtifactOpenStoreScope provides provider.storeScope,
    ) {
        Box(Modifier.fillMaxSize()) {
        when (val current = routeState) {
            MemberDetailRouteState.Loading ->
                MemberDetailLoadingPageShell(onBack = { closeDetail() })
            is MemberDetailRouteState.Error ->
                MemberDetailErrorPageShell(
                    title = stringResource(Res.string.member_error_load_member),
                    detail = current.error.userFacingMessage(errorStrings),
                    onBack = { closeDetail() },
                    onRetry = { localAgentDetailRefreshKey += 1 }
                )
            is MemberDetailRouteState.Agent -> {
                val member = selectedAgentDetail?.toStaticMembersProfile(current.server)
                val loungeEnabled =
                    rememberAgentLoungeEnabled(provider.serverFeatureFlags, current.server.id.value, current.server.role)
                if (member == null) {
                    // The store has no detail for this agent until this page's load lands: keep the
                    // page's header and Back around the spinner.
                    MemberDetailLoadingPageShell(onBack = { closeDetail() })
                } else {
                    val agentMachineId =
                        selectedAgentDetail.summary?.machineId
                            ?: member.membersComputerId
                    // Task #336: gate the "Computer" link on the machine actually being
                    // registered on this server. Same three-state source as
                    // machineStatusRow: while the computers store has not answered the
                    // link stays (unknown), once a snapshot proves the machine is not
                    // here the link is withdrawn instead of opening a guaranteed 404.
                    val computerSliceForLink = computersState.selectComputerServerSlice(statusLookupServerId)
                    val computerNavigationTarget =
                        membersAgentComputerNavigationTarget(
                            serverId = current.server.id.value,
                            summaryMachineId = selectedAgentDetail.summary?.machineId,
                            memberMachineId = member.membersComputerId,
                            accessMode = current.accessMode,
                            machineKnown = membersAgentComputerKnownState(
                                machineId = agentMachineId,
                                machines = computerSliceForLink.machines,
                                loadStatus = computerSliceForLink.status,
                                hasSnapshot = computerSliceForLink.hasSnapshot,
                            ),
                        )
                    val onOpenComputer: (() -> Unit)? =
                        computerNavigationTarget?.let { target ->
                            {
                                pager.openSettingsComputerPage(
                                    storeScope = provider.storeScope,
                                    target = target,
                                )
                            }
                        }
                    val managedAppDetailTarget =
                        membersManagedAppDetailTarget(
                            member = member,
                            summary = selectedAgentDetail.summary,
                            viewerServerRole = current.server.role,
                            currentServerId = activeServerId ?: current.server.id.value,
                            accessMode = current.accessMode,
                        )
                    val onOpenManagedApp: (() -> Unit)? =
                        managedAppDetailTarget?.let { target ->
                            {
                                pager.openSettingsMarketplacePage(
                                    storeScope = provider.storeScope,
                                    installedAppId = target.appId,
                                )
                            }
                        }
                    val agentRuntimeModelsByRuntime =
                        agentMachineId
                            ?.let { machineId ->
                                agentRuntimeModelCatalogs
                                    .filterKeys { (catalogMachineId, _) -> catalogMachineId == machineId }
                                    .mapKeys { (key, _) -> key.second }
                            }
                            .orEmpty()
                    val agentProfileEditAuthority =
                        agentDetailStoreState.detailOwner
                            ?.takeIf { owner ->
                                selectedAgentDetailStoreStateMatches &&
                                    !agentDetailStoreState.detailFromCache &&
                                    owner.serverId == current.server.id &&
                                    agentDetailStoreState.detailProfileGeneration == agentProfileRequestGeneration
                            }
                            ?.let { owner ->
                                MembersAgentProfileEditAuthority(
                                    memberId = member.id,
                                    environmentId = owner.environmentId,
                                    accountId = owner.accountId,
                                    serverId = owner.serverId,
                                    profileGeneration = agentDetailStoreState.detailProfileGeneration
                                )
                            }
                    val loadAgentRuntimeModels: (String, Boolean) -> Unit = { runtime, force ->
                        val key = agentMachineId?.trim().orEmpty() to runtime.trim()
                        val serverId = statusLookupServerId?.trim().orEmpty()
                        if (serverId.isNotBlank() && key.first.isNotBlank() && key.second.isNotBlank() &&
                            (force || (key !in agentRuntimeModelCatalogs && key !in agentRuntimeModelLoadsInFlight))
                        ) {
                            val requestGeneration = (agentRuntimeOptionRequestGenerations[key] ?: 0L) + 1L
                            agentRuntimeOptionRequestGenerations =
                                agentRuntimeOptionRequestGenerations + (key to requestGeneration)
                            val requestFence =
                                MembersAgentRuntimeOptionsRequestFence(
                                    memberId = member.id,
                                    serverId = ServerId(serverId),
                                    machineId = key.first,
                                    runtimeId = key.second,
                                    profileGeneration = agentProfileRequestGeneration,
                                    generation = requestGeneration
                                )
                            agentRuntimeModelLoadsInFlight = agentRuntimeModelLoadsInFlight + key
                            routeScope.launch {
                                val result =
                                    provider.createAgentCoordinator.loadRuntimeModels(
                                        serverId = serverId,
                                        machineId = key.first,
                                        runtime = key.second
                                    )
                                if (!requestFence.matches(
                                        identity = memberIdentity,
                                        activeServerId = activeServerId,
                                        currentMachineId = agentMachineId,
                                        currentRuntimeId = key.second,
                                        currentProfileGeneration = agentProfileRequestGeneration,
                                        currentGeneration = agentRuntimeOptionRequestGenerations[key] ?: 0L
                                    )
                                ) {
                                    agentRuntimeModelLoadsInFlight =
                                        membersAgentRuntimeRequestCompletionInFlight(
                                            inFlight = agentRuntimeModelLoadsInFlight,
                                            generations = agentRuntimeOptionRequestGenerations,
                                            key = key,
                                            requestGeneration = requestGeneration
                                        )
                                    return@launch
                                }
                                // Only the loaded models are kept (read-only runtime rows); a failure
                                // leaves the key unloaded, so the next request retries it.
                                when (result) {
                                    is DataResult.Content ->
                                        if (result.data.models.isNotEmpty()) {
                                            agentRuntimeModelCatalogs = agentRuntimeModelCatalogs + (key to result.data)
                                        }
                                    is DataResult.Empty -> Unit
                                    is DataResult.Error -> {
                                        bridge.log(
                                            "member_detail_runtime_models_failed runtime=${key.second} " +
                                                "code=${result.error.code}"
                                        )
                                    }
                                    is DataResult.NoPermission -> {
                                        bridge.log(
                                            "member_detail_runtime_models_blocked runtime=${key.second} " +
                                                "code=${result.blocker.code}"
                                        )
                                    }
                                }
                                agentRuntimeModelLoadsInFlight =
                                    membersAgentRuntimeRequestCompletionInFlight(
                                        inFlight = agentRuntimeModelLoadsInFlight,
                                        generations = agentRuntimeOptionRequestGenerations,
                                        key = key,
                                        requestGeneration = requestGeneration
                                    )
                            }
                        }
                    }
                    val agentDetailActions =
                        remember(provider, current.server.id) {
                            object : SharedAgentDetailActions {
                                override suspend fun runLifecycleAction(
                                    agentId: MemberId,
                                    action: MembersAgentLifecycleAction,
                                    workspaceChoice: AgentArchiveWorkspaceChoice
                                ): DataResult<AgentLifecycleActionResult> {
                                    val result = provider.runLifecycleAction(current.server.id, agentId, action, workspaceChoice)
                                    if (result is DataResult.Content && result.data.success) {
                                        localAgentDetailRefreshKey += 1
                                        if (action == MembersAgentLifecycleAction.Delete) {
                                            closeDetail()
                                        }
                                    }
                                    return result
                                }

                                override fun copyDiagnostics(diagnostics: String) {
                                    provider.copyDiagnostics(diagnostics, diagnosticsCopied)
                                }

                                override suspend fun reportIssue(
                                    request: SharedAgentIssueReportRequest
                                ): DataResult<AgentIssueReportResult> {
                                    return provider.reportIssue(
                                        server = current.server,
                                        member = member,
                                        detail = selectedAgentDetail,
                                        request = request
                                    )
                                }

                                override suspend fun retryIssueTicket(
                                    request: AgentIssueTicketRequest
                                ): DataResult<String> = provider.retryIssueTicket(current.server, request)

                                override suspend fun updateAgentProfile(
                                    request: AgentProfileUpdateRequest
                                ): DataResult<MemberProfile> =
                                    provider.updateAgentProfile(current.server.id, request)
                            }
                        }
                    val workerV1Enabled =
                        remember(provider.serverFeatureFlags, current.server.id, serverFeatureFlagRevision) {
                            provider.serverFeatureFlags?.isWorkerV1Enabled(current.server.id) == true
                        }
                    val workersPanelEnabled =
                        workerV1Enabled &&
                            current.accessMode.allowsPrivateAgentSurfaces &&
                            !member.isExternal &&
                            !build.raft.app.model.isExternalAgentRuntime(selectedAgentDetail.summary?.runtime)
                    LaunchedEffect(agentWorkersStore, current.server.id, member.id, workersPanelEnabled) {
                        agentWorkersStore?.dispatch(
                            AgentWorkersIntent.Bind(
                                serverId = current.server.id.value,
                                agentId = member.id,
                                enabled = workersPanelEnabled,
                            )
                        )
                    }
                    LaunchedEffect(agentWorkerTimelineStore, member.id, workersPanelEnabled) {
                        val openAgentId = agentWorkerTimelineStore?.state?.value?.agentId
                        if (!workersPanelEnabled || (openAgentId != null && openAgentId != member.id)) {
                            agentWorkerTimelineStore?.dispatch(AgentWorkerTimelineIntent.Close)
                        }
                    }
                    MembersDetailContent(
                        member = member,
                        currentUserId = current.currentUserId,
                        currentServerId = current.server.id.value,
                        serverMembersState = serverMembersState,
                        agentDetailState = agentDetailState,
                        agentActivityLoadState = agentActivityLoadState,
                        activityOverview =
                            membersAgentActivityOverviewBindingOrNull(
                                store = activityOverviewStore,
                                enabled =
                                    current.accessMode.allowsPrivateAgentSurfaces &&
                                        (current.server.permissions.canManageSettings ||
                                            selectedAgentDetail?.summary?.creatorId == current.currentUserId)
                            ),
                        runtimeModels = MembersAgentRuntimeModelInputs(
                            agentMachineRuntimes =
                                membersAgentMachineRuntimeIds(
                                    machines = computerPickerSlice.machines,
                                    machineId = selectedAgentDetail.summary?.machineId ?: member.membersComputerId
                                ),
                            agentRuntimeModelsByRuntime = agentRuntimeModelsByRuntime,
                            onAgentRuntimeModelsRequest = loadAgentRuntimeModels
                        ),
                        createAgentFormStore = provider.createAgentFormStore,
                        externalSetupServerUrl = provider.apiEnvironmentStore.current().webUrl,
                        remoteControls = MembersAgentRemoteControls(
                            serverRole = current.server.role,
                            turnStopStore = agentTurnStopStore,
                            subagentStopStore = agentSubagentStopStore,
                            workersStore = if (workersPanelEnabled) agentWorkersStore else null,
                            workerTimelineStore = if (workersPanelEnabled) agentWorkerTimelineStore else null,
                            migration =
                                agentMigrationStore?.let { store ->
                                    val flags = provider.serverFeatureFlags
                                    val migrationServerMembers = serverMembersState.entryFor(current.server.id)?.members.orEmpty()
                                    MembersAgentMigrationBinding(
                                        store = store,
                                        hostedAvailable =
                                            remember(flags, current.server.id, serverFeatureFlagRevision) {
                                                flags?.isEnabled(current.server.id, AGENT_MIGRATION_HOSTED_FEATURE_FLAG_KEY) == true
                                            },
                                        computers = migrationComputers,
                                        // Upgrade: the upgrade dialog (web `agent_migration`); iOS view-only
                                        // Billing: "View your plan", the view-only page.
                                        onOpenBilling = {
                                            if (settingsBillingOffersUpgrade()) {
                                                pager.openRaftUpgradeDialog(RaftUpgradeGate.AgentMigration, provider.storeScope)
                                            } else {
                                                pager.openSettingsBillingPage(provider.storeScope)
                                            }
                                        },
                                        onAgentPlacementChanged = { localAgentDetailRefreshKey += 1 },
                                        computerAgents =
                                            AgentMigrationComputerAgents(
                                                byMachine =
                                                    remember(migrationServerMembers) {
                                                        agentMigrationAgentsByMachine(migrationServerMembers)
                                                    },
                                                serverId = current.server.id.value,
                                                agentStatusResolver = agentStatusResolver,
                                                onOpenProfile = { agent ->
                                                    val pageData =
                                                        payload.toCreatedAgentDetailPageData(
                                                            agentId = agent.id,
                                                            sourceServerId = agent.serverId ?: current.server.id
                                                        )
                                                    if (pageData == null) {
                                                        bridge.toast(openProfileFailed)
                                                    } else {
                                                        pager.openRaftPage(
                                                            pageName = MEMBER_DETAIL_PAGE,
                                                            pageData = pageData,
                                                            storeScopeOverride = provider.storeScope,
                                                            onMissingScope = { bridge.toast(openProfileFailed) }
                                                        )
                                                    }
                                                }
                                            ),
                                        onOpenLog =
                                            if (canSeeAgentMigrationLog(current.server.role)) {
                                                { pager.openAgentMigrationLogPage(provider.storeScope, agentId = member.id.value) }
                                            } else {
                                                null
                                            },
                                        onInfoToast = bridge::toast
                                    )
                                },
                            appAccess =
                                MembersAgentAppAccessBinding(
                                    store = agentAppGrantsRevokeStore,
                                    canManage = { detail -> current.viewerManagesAgentAppAccess(detail) },
                                    onApproveRequest = { targetAgentId, requestId ->
                                        val result = provider.memberRepository.approveAgentAppRequest(DataRequestPriority.Foreground, requestId)
                                        if (result is build.raft.app.data.DataResult.Content) {
                                            when (val fresh = provider.memberRepository.getAgentAppLogins(DataRequestPriority.Visible, targetAgentId)) {
                                                is build.raft.app.data.DataResult.Content ->
                                                    provider.updateAgentAppLogins(build.raft.app.data.SharedAgentDetailIntent.SetAgentAppLogins(targetAgentId, fresh.data))
                                                is build.raft.app.data.DataResult.Error ->
                                                    provider.updateAgentAppLogins(build.raft.app.data.SharedAgentDetailIntent.SetAgentAppLoginsRefreshFailed(targetAgentId, fresh.error))
                                                else -> Unit
                                            }
                                        }
                                        result
                                    },
                                    onDenyRequest = { targetAgentId, requestId ->
                                        val result = provider.memberRepository.denyAgentAppRequest(DataRequestPriority.Foreground, requestId)
                                        if (result is build.raft.app.data.DataResult.Content) {
                                            when (val fresh = provider.memberRepository.getAgentAppLogins(DataRequestPriority.Visible, targetAgentId)) {
                                                is build.raft.app.data.DataResult.Content ->
                                                    provider.updateAgentAppLogins(build.raft.app.data.SharedAgentDetailIntent.SetAgentAppLogins(targetAgentId, fresh.data))
                                                is build.raft.app.data.DataResult.Error ->
                                                    provider.updateAgentAppLogins(build.raft.app.data.SharedAgentDetailIntent.SetAgentAppLoginsRefreshFailed(targetAgentId, fresh.error))
                                                else -> Unit
                                            }
                                        }
                                        result
                                    },
                                )
                        ),
                        agentProfileEditAuthority = agentProfileEditAuthority,
                        canViewPrivateAgentSurfaces = { _, detail -> current.viewerSeesPrivateAgentSurfaces(detail) },
                        canManageAgent = { _, detail ->
                            membersRouteCanManageAgent(
                                allowsPrivateAgentSurfaces = current.accessMode.allowsPrivateAgentSurfaces,
                                canManageSettings = current.server.permissions.canManageSettings,
                                currentUserId = current.currentUserId,
                                detail = detail
                            )
                        },
                        machineStatusRow = machineStatusRow,
                        offlineComputerInputs = MembersAgentOfflineComputerInputs(
                            machines = computerSliceForLink.machines,
                            offlineObservedAtMs = computerSliceForLink.offlineObservedAtMs,
                            commandContext = raftComputerCommandContext(
                                serverSlug = current.server.slug,
                                environment = provider.apiEnvironmentStore.current(),
                            ),
                        ),
                        actions = MembersDetailActions(
                            onBack = { closeDetail() },
                            directMessageOpening = pendingDirectMessageOpens.isOpening(member.kind, member.id),
                            onStartCall =
                                rememberMemberDetailAgentVoiceCallAction(
                                    provider = provider,
                                    current = current,
                                    member = member,
                                    detail = selectedAgentDetail,
                                    agentMachineId = agentMachineId,
                                    machines = computerSliceForLink.machines,
                                ),
                            onOpenLounge =
                                if (loungeEnabled) {
                                    {
                                        loungeOpener.open(current.server.id.value, member.id.value, member.displayName) { opened ->
                                            val identity = opened.conversationIdentity()
                                            if (openDirectMessageInShell?.invoke(identity) != true) {
                                                val launchPayload =
                                                    buildStandaloneConversationThreadLaunchPayload(
                                                        conversation = identity,
                                                        timeContext = timeContextProvider.current ?: timeContext,
                                                        instanceIdOverride = "members-lounge-${member.id.value}-${identity.threadId.value}"
                                                    )
                                                if (launchPayload == null) {
                                                    bridge.toast(loungeOpenFailed)
                                                } else {
                                                    pager.openRaftPage(
                                                        pageName = THREAD_PAGE,
                                                        pageData = launchPayload.toThreadPageData(),
                                                        storeScopeOverride = provider.storeScope,
                                                        onMissingScope = { bridge.toast(loungeOpenFailed) }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    null
                                },
                            loungeOpening = member.id.value in pendingLoungeOpens,
                            onOpenDirectMessage = {
                                routeScope.launch {
                                    provider.openDirectMessage(member)?.openMembersDirectMessageResult(
                                        pager = pager,
                                        storeScope = provider.storeScope,
                                        openInShell = openDirectMessageInShell,
                                        member = member,
                                        timeContext = timeContextProvider.current ?: timeContext,
                                        unavailableMessage = directMessageOpenError,
                                        errorTemplate = directMessageOpenErrorTemplate,
                                        permissionMessage = directMessagePermissionError,
                                        errorStrings = errorStrings,
                                        onUnavailableAction = { bridge.toast(it) },
                                        onErrorAction = { message, traceId -> bridge.errorToast(message, traceId) }
                                    )
                                }
                            },
                            onOpenComputer = onOpenComputer,
                            onOpenManagedApp = onOpenManagedApp,
                            onCopyMemberId = {
                                provider.copyMemberId(member, memberIdCopied)
                            },
                            onRemoveMember = {
                                provider.bridge.toast(removeFromSettings)
                            },
                            onOpenUrl = { linkNavigator.open(it) },
                            onOpenReminderMessage = { permalink ->
                                pager.openMembersReminderMessage(
                                    permalink = permalink,
                                    server = current.server,
                                    conversationIdentityProvider = provider.conversationIdentityProvider,
                                    storeScope = provider.storeScope,
                                    timeContext = timeContextProvider.current ?: timeContext,
                                    onOpenExternal = {
                                        linkNavigator.openMembersReminderForceExternalFallback(it)
                                    },
                                    unavailableMessage = reminderOpenError,
                                    onUnavailableAction = { provider.bridge.toast(it) }
                                )
                            },
                            onOpenActivityMessage = { channelId, messageId ->
                                pager.openMembersMessageTarget(
                                    target = RaftThreadWebLinkTarget(channelId = channelId, messageId = messageId),
                                    messageId = messageId,
                                    server = current.server,
                                    conversationIdentityProvider = provider.conversationIdentityProvider,
                                    storeScope = provider.storeScope,
                                    timeContext = timeContextProvider.current ?: timeContext,
                                    unavailableMessage = reminderOpenError,
                                    onUnavailableAction = { provider.bridge.toast(it) }
                                )
                            },
                            onOpenWorkerTimelineMessage =
                                rememberMemberDetailWorkerTimelineMessageOpener(
                                    pager = pager,
                                    provider = provider,
                                    server = current.server,
                                    timeContextProvider = timeContextProvider,
                                    fallbackTimeContext = timeContext,
                                    scope = routeScope,
                                ),
                            onUnavailableAction = { bridge.toast(it) }
                        ),
                        agentDetailActions = agentDetailActions,
                        agentCallbacks = MembersAgentDetailCallbacks(
                            onUpdateAgentProfile = { request ->
                                agentDetailActions.updateAgentProfile(request)
                            },
                            onErrorToast = { message, error -> bridge.errorToast(message, error) },
                            onApplyAgentProfileResult = { updatedMember ->
                                provider.updateCachedMemberProfile(updatedMember, current.server.id)
                            },
                            onApplyAgentRuntimeConfigProjection = provider::applyRuntimeConfigProjection,
                            onPickAgentAvatarFile = {
                                provider.pickAgentAvatarFile()
                            },
                            onUploadAgentAvatar = { agentId, file ->
                                val result = provider.uploadAgentAvatar(current.server.id, agentId, file)
                                if (result is DataResult.Content) {
                                    provider.updateCachedMemberProfile(result.data, current.server.id)
                                }
                                result
                            },
                            onAgentLifecycleAction = { agentId, action, workspaceChoice ->
                                agentDetailActions.runLifecycleAction(agentId, action, workspaceChoice)
                            },
                            agentWorkspaceTreeLoader = { agentId ->
                                provider.memberRepository.getAgentWorkspaceTree(DataRequestPriority.Visible, agentId)
                            },
                            agentWorkspaceChildrenLoader = { agentId, dirPath ->
                                provider.memberRepository.getAgentWorkspaceChildren(DataRequestPriority.Visible, agentId, dirPath)
                            },
                            agentAppEventsLoader = { agentId, clientId, before ->
                                provider.memberRepository.getAgentAppEvents(DataRequestPriority.Visible, agentId, clientId, before)
                            },
                            agentAppEventLoader = { agentId, eventId ->
                                provider.memberRepository.getAgentAppEvent(DataRequestPriority.Visible, agentId, eventId)
                            },
                            onCopyAgentDiagnostics = agentDetailActions::copyDiagnostics,
                            onCopyAgentIssueReference = provider.bridge::copyToPasteboard,
                            onReportAgentIssue = agentDetailActions::reportIssue,
                            // Web "View Ticket" opens Settings › Feedback on the ticket: the native
                            // ticket page on Android / iOS, the web page on Compose desktop.
                            onOpenFeedbackTicket = { ticketId ->
                                provider.bridge.log("member_detail_feedback_ticket_open")
                                pager.openSettingsFeedbackPage(provider.storeScope, ticketId = ticketId)
                            }
                        ),
                        timeContext = timeContext,
                        presentation = MembersAgentDetailPresentation(
                            pageVisibleTick = pageVisibleTick,
                            // A Members row's Edit agent (only where the reader may see the private tabs).
                            openRuntimeEditor = payload.openRuntimeEditor && current.accessMode.allowsPrivateAgentSurfaces,
                            initialAgentTab =
                                if (current.accessMode.allowsPrivateAgentSurfaces) {
                                    initialAgentTabForRender
                                } else {
                                    MembersAgentDetailTab.Profile
                                },
                            agentPanelTabOrder = agentPanelTabOrder,
                            agentPanelTabOrderReady = agentTabOrderReady,
                            onAgentPanelTabOrderChange = { orderedTabs ->
                                val previousOrder = agentPanelTabOrder
                                val nextOrder = orderedTabs.toMembersAgentPanelTabOrderWireKeys()
                                if (nextOrder != previousOrder) {
                                    provider.agentPanelTabOrderStore.applyOptimistic(
                                        serverId = statusLookupServerId,
                                        order = nextOrder,
                                        source = "member_detail_user_reorder_optimistic",
                                        logger = { message -> bridge.log(message) }
                                    )
                                    routeScope.launch {
                                        when (val result = provider.updateAgentPanelTabOrder(orderedTabs)) {
                                            is DataResult.Content ->
                                                provider.agentPanelTabOrderStore.applyLoaded(
                                                    serverId = statusLookupServerId,
                                                    order = result.data,
                                                    source = "member_detail_user_reorder_save_success",
                                                    logger = { message -> bridge.log(message) }
                                                )
                                            is DataResult.Empty -> {
                                                provider.agentPanelTabOrderStore.applyLoaded(
                                                    serverId = statusLookupServerId,
                                                    order = previousOrder,
                                                    source = "member_detail_user_reorder_save_empty_rollback",
                                                    logger = { message -> bridge.log(message) }
                                                )
                                                bridge.toast(tabOrderSaveError)
                                            }
                                            is DataResult.Error -> {
                                                provider.agentPanelTabOrderStore.applyLoaded(
                                                    serverId = statusLookupServerId,
                                                    order = previousOrder,
                                                    source = "member_detail_user_reorder_save_error_rollback",
                                                    logger = { message -> bridge.log(message) }
                                                )
                                                bridge.errorToast(
                                                    tabOrderSaveErrorTemplate
                                                        .membersFormat(result.error.userFacingMessage(errorStrings)),
                                                    result.error.traceId,
                                                )
                                            }
                                            is DataResult.NoPermission -> {
                                                provider.agentPanelTabOrderStore.applyLoaded(
                                                    serverId = statusLookupServerId,
                                                    order = previousOrder,
                                                    source = "member_detail_user_reorder_save_blocked_rollback",
                                                    logger = { message -> bridge.log(message) }
                                                )
                                                bridge.errorToast(tabOrderPermissionError, result.blocker)
                                            }
                                        }
                                    }
                                }
                            }
                        ),
                        sharedAgentDetailStore = provider.agentDetailStore,
                        accessMode = current.accessMode,
                        agentStatusResolver = agentStatusResolver,
                        memberAvatarResolver = memberAvatarResolver,
                        agentsApiClient = provider.agentsApiClient
                    )
                }
            }
            is MemberDetailRouteState.Human -> {
                val serverMembers = serverMembersState.entryFor(current.server.id)?.members.orEmpty()
                val ownerCount = serverMembers.count {
                    it.kind == MemberKind.Human &&
                        it.membershipStatus == "active" &&
                        it.serverRole.normalizedMembersServerRole() == "owner"
                }
                val canRemoveHumanMember =
                    membersCanRemoveHumanMember(
                        actorRole = current.server.role,
                        targetRole = current.member.serverRole,
                        targetMembershipStatus = current.member.membershipStatus,
                        targetMemberId = current.member.id.value,
                        currentUserId = current.currentUserId,
                        targetServerId = current.member.serverId?.value,
                        currentServerId = current.server.id.value,
                        ownerCount = ownerCount.takeIf { it > 0 }
                    )
                // Promote to server member (web HumanProfileActions, design D2): an owner/admin makes a
                // Guest a member, the same role change as the Role picker, behind its confirmation.
                val canPromoteGuest =
                    current.member.serverRole.normalizedMembersServerRole() == "guest" &&
                        MembersHumanServerRole.Member in
                        membersEditableHumanServerRoles(
                            actorRole = current.server.role,
                            targetRole = current.member.serverRole,
                            targetMembershipStatus = current.member.membershipStatus,
                            accessMode = current.accessMode,
                            targetMemberId = current.member.id.value,
                            currentUserId = current.currentUserId,
                            targetServerId = current.member.serverId?.value,
                            currentServerId = current.server.id.value,
                            ownerCount = ownerCount.takeIf { it > 0 }
                        )
                var promoteConfirmOpen by remember(current.member.id) { mutableStateOf(false) }
                var promoting by remember(current.member.id) { mutableStateOf(false) }
                var promoteError by remember(current.member.id) { mutableStateOf<String?>(null) }
                if (promoteConfirmOpen) {
                    MembersPromoteGuestConfirmDialog(
                        personName = current.member.displayName.trim().ifBlank { current.member.handle },
                        serverName = current.server.name,
                        promoting = promoting,
                        errorMessage = promoteError,
                        onDismiss = { if (!promoting) promoteConfirmOpen = false },
                        onConfirm = {
                            promoting = true
                            promoteError = null
                            routeScope.launch {
                                val result =
                                    runCatchingPreservingCancellation {
                                        provider.updateHumanRole(current.server.id.value, current.member.id.value, MembersHumanServerRole.Member)
                                    }.getOrNull()
                                promoting = false
                                if (result is DataResult.Content && result.data.success) {
                                    promoteConfirmOpen = false
                                    // The profile reads as a member now: the Member badge, no channel row, no Promote.
                                    routeState =
                                        current.copy(
                                            member =
                                                current.member.copy(
                                                    serverRole = MembersHumanServerRole.Member.wireValue,
                                                    // As the parser labels a wire role (`toSettingsLabel`).
                                                    roleDescription = MembersHumanServerRole.Member.wireValue.replaceFirstChar { it.titlecase() },
                                                    guestChannel = null
                                                )
                                        )
                                } else {
                                    promoteError =
                                        (result as? DataResult.Error)?.error?.userFacingMessage(errorStrings)
                                            ?: (result as? DataResult.Content)?.data?.message?.takeIf { it.isNotBlank() }
                                            ?: "member_role_save_failed".userFacingErrorCodeMessage(errorStrings)
                                }
                            }
                        },
                    )
                }
                // Remove member asks first (web HumanProfileActions' RemoveMemberConfirmDialog), and
                // its banner lists any blocking Computers for one-step disconnect + remove (slock #16386)
                // or legacy machines that go offline (#13547). A 409 member_has_computer re-reads the list.
                var removeConfirmOpen by remember(current.member.id) { mutableStateOf(false) }
                var removing by remember(current.member.id) { mutableStateOf(false) }
                var removeError by remember(current.member.id) { mutableStateOf<String?>(null) }
                var removeImpactRevision by remember(current.member.id) { mutableStateOf(0) }
                val removeFailedFallback = stringResource(Res.string.thread_members_remove_failed)
                if (removeConfirmOpen) {
                    val personName = current.member.displayName.trim().ifBlank { current.member.handle }
                    MembersRemoveMemberConfirmDialog(
                        personName = personName,
                        impact =
                            rememberMemberRemovalImpact(
                                repository = provider.memberRemovalImpactRepository,
                                serverId = current.server.id.value,
                                memberId = current.member.id.value,
                                revision = removeImpactRevision,
                            ),
                        removing = removing,
                        errorMessage = removeError,
                        onDismiss = { removeConfirmOpen = false },
                        onConfirm = { revokeComputerIds ->
                            removing = true
                            removeError = null
                            routeScope.launch {
                                val result =
                                    runCatchingPreservingCancellation {
                                        provider.removeHumanMember(
                                            current.server.id.value,
                                            current.member.id.value,
                                            revokeComputerIds,
                                        )
                                    }.getOrNull()
                                removing = false
                                if (result is DataResult.Content && result.data.success) {
                                    removeConfirmOpen = false
                                    closeDetail()
                                } else {
                                    removeError =
                                        (result as? DataResult.Error)?.error?.userFacingMessage(errorStrings)
                                            ?: (result as? DataResult.NoPermission)?.blocker?.serverMessage?.takeIf { it.isNotBlank() }
                                            ?: (result as? DataResult.Content)?.data?.message?.takeIf { it.isNotBlank() }
                                            ?: removeFailedFallback
                                    if (result?.isMemberHasComputerRefusal() == true) {
                                        removeImpactRevision += 1
                                    }
                                }
                            }
                        },
                    )
                }
                MembersDetailContent(
                    member = current.member,
                    currentUserId = current.currentUserId,
                    currentServerId = current.server.id.value,
                    agentDetailState = MembersAgentDetailLoadState.Idle,
                    canViewPrivateAgentSurfaces = { _, _ -> false },
                    canManageAgent = { _, _ -> false },
                    machineStatusRow = machineStatusRow,
                    actions = MembersDetailActions(
                        onBack = { closeDetail() },
                        directMessageOpening = pendingDirectMessageOpens.isOpening(current.member.kind, current.member.id),
                        onOpenDirectMessage = {
                            routeScope.launch {
                                provider.openDirectMessage(current.member)?.openMembersDirectMessageResult(
                                    pager = pager,
                                    storeScope = provider.storeScope,
                                    openInShell = openDirectMessageInShell,
                                    member = current.member,
                                    timeContext = timeContextProvider.current ?: timeContext,
                                    unavailableMessage = directMessageOpenError,
                                    errorTemplate = directMessageOpenErrorTemplate,
                                    permissionMessage = directMessagePermissionError,
                                    errorStrings = errorStrings,
                                    onUnavailableAction = { bridge.toast(it) },
                                    onErrorAction = { message, traceId -> bridge.errorToast(message, traceId) }
                                )
                            }
                        },
                        onCopyMemberId = {
                            provider.copyMemberId(current.member, memberIdCopied)
                        },
                        onRemoveMember = if (canRemoveHumanMember) ({
                            removeError = null
                            removeImpactRevision = 0
                            removeConfirmOpen = true
                        }) else null,
                        onPromoteGuest = if (canPromoteGuest) ({
                            promoteError = null
                            promoteConfirmOpen = true
                        }) else null,
                        promotingGuest = promoting,
                        onOpenUrl = { linkNavigator.open(it) },
                        onOpenReminderMessage = { permalink ->
                            pager.openMembersReminderMessage(
                                permalink = permalink,
                                server = current.server,
                                conversationIdentityProvider = provider.conversationIdentityProvider,
                                storeScope = provider.storeScope,
                                timeContext = timeContextProvider.current ?: timeContext,
                                onOpenExternal = {
                                    linkNavigator.openMembersReminderForceExternalFallback(it)
                                },
                                unavailableMessage = reminderOpenError,
                                onUnavailableAction = { provider.bridge.toast(it) }
                            )
                        },
                        onUnavailableAction = { bridge.toast(it) },
                        onOpenCreatedAgent = { agent ->
                            val pageData =
                                payload.toCreatedAgentDetailPageData(
                                    agentId = agent.id,
                                    sourceServerId = current.member.serverId ?: current.server.id
                                )
                            if (pageData == null) {
                                bridge.toast(openProfileFailed)
                            } else {
                                pager.openRaftPage(
                                    pageName = MEMBER_DETAIL_PAGE,
                                    pageData = pageData,
                                    storeScopeOverride = provider.storeScope,
                                    onMissingScope = { bridge.toast(openProfileFailed) }
                                )
                            }
                        }
                    ),
                    humanRole = MembersHumanRoleInputs(
                        humanActorRole = current.server.role,
                        humanOwnerCount = ownerCount.takeIf { it > 0 },
                        humanAccessMode = current.accessMode,
                        onUpdateHumanRole = { memberId, role ->
                            provider.updateHumanRole(current.server.id.value, memberId, role)
                        },
                        onUpdateHumanDescription = { description ->
                            provider.updateHumanDescription(current.member.id, description)
                        },
                        onApplyHumanDescription = { savedDescription ->
                            humanDescriptionEditRevision += 1L
                            routeState =
                                (routeState as? MemberDetailRouteState.Human)?.let { state ->
                                    if (state.member.id == current.member.id) {
                                        state.copy(member = state.member.copy(description = savedDescription))
                                    } else {
                                        state
                                    }
                                } ?: routeState
                        }
                    ),
                    agentCallbacks = MembersAgentDetailCallbacks(
                        onUpdateAgentProfile = null,
                        onUploadAgentAvatar = null,
                        onAgentLifecycleAction = null,
                        agentWorkspaceTreeLoader = null,
                        agentWorkspaceChildrenLoader = null,
                        onCopyAgentDiagnostics = {},
                        onReportAgentIssue = null
                    ),
                    timeContext = timeContext,
                    presentation = MembersAgentDetailPresentation(
                        pageVisibleTick = pageVisibleTick,
                        initialAgentTab = initialAgentTab,
                        agentPanelTabOrder = payload.agentPanelTabOrder
                    ),
                    agentStatusResolver = agentStatusResolver,
                    memberAvatarResolver = memberAvatarResolver
                )
            }
        }
        build.raft.app.agents.StandaloneAgentVoiceCallOverlay()
        }
    }
}

@Composable
private fun MemberDetailLoadingPageShell(
    onBack: () -> Unit
) {
    MemberDetailRoutePageShell(onBack = onBack) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            RaftCenteredLoading()
        }
    }
}

@Composable
private fun MemberDetailErrorPageShell(
    title: String,
    detail: String,
    onBack: () -> Unit,
    onRetry: () -> Unit
) {
    MemberDetailRoutePageShell(onBack = onBack) {
        RaftEmptyState(
            title = title,
            modifier = Modifier.fillMaxSize(),
            description = detail,
            icon = { RaftContentLucideIcon(RaftLucideIconName.TriangleAlert, size = RaftEmptyStateDefaults.IconSize) },
            actions = {
                RaftButton(
                    text = stringResource(Res.string.common_retry),
                    onClick = onRetry,
                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.RefreshCw) },
                    size = RaftButtonSize.Lg,
                )
            },
        )
    }
}

@Composable
private fun MemberDetailRoutePageShell(
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    val inWideSplit = LocalRaftMembersWideSplitDetail.current
    Column(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        RaftTopPageHeader(
            title = stringResource(Res.string.member_role_member),
            subtitle = stringResource(Res.string.member_label_profile),
            iconName = RaftLucideIconName.Users,
            backgroundColor = RaftColor.SurfaceWhite,
            onBack = onBack.takeUnless { inWideSplit }
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            content()
        }
    }
}

internal fun RaftLinkNavigator.openMembersReminderForceExternalFallback(
    rawUrl: String
): RaftLinkOpenDecision =
    open(rawUrl, RaftLinkOpenIntent.ForceExternal)

private fun Pager.openMembersReminderMessage(
    permalink: String,
    server: ServerSummary,
    conversationIdentityProvider: build.raft.app.data.SharedConversationIdentityProvider?,
    storeScope: RaftShellStoreScope,
    timeContext: RaftTimeContext,
    onOpenExternal: (String) -> Unit,
    unavailableMessage: String,
    onUnavailableAction: (String) -> Unit
) {
    val target = permalink.toRaftThreadWebLinkTarget()
    if (target == null) {
        onOpenExternal(permalink)
        return
    }
    val messageId = target.messageId?.takeIf { it.isNotBlank() }
    if (messageId == null) {
        onUnavailableAction(unavailableMessage)
        return
    }
    openMembersMessageTarget(
        target = target,
        messageId = messageId,
        server = server,
        conversationIdentityProvider = conversationIdentityProvider,
        storeScope = storeScope,
        timeContext = timeContext,
        unavailableMessage = unavailableMessage,
        onUnavailableAction = onUnavailableAction
    )
}

/** Opens [messageId] in its conversation ([target] names the channel), resolved like a permalink. */
private fun Pager.openMembersMessageTarget(
    target: RaftThreadWebLinkTarget,
    messageId: String,
    server: ServerSummary,
    conversationIdentityProvider: build.raft.app.data.SharedConversationIdentityProvider?,
    storeScope: RaftShellStoreScope,
    timeContext: RaftTimeContext,
    unavailableMessage: String,
    onUnavailableAction: (String) -> Unit
) {
    val launchPayload =
        target.toMembersReminderThreadLaunchPayload(
            messageId = messageId,
            timeContext = timeContext,
            server = server,
            conversationIdentityProvider = conversationIdentityProvider
        )
            ?: run {
                onUnavailableAction(unavailableMessage)
                return
            }
    openRaftPage(
        pageName = THREAD_PAGE,
        pageData = launchPayload.toThreadPageData(),
        storeScopeOverride = storeScope,
        onMissingScope = { onUnavailableAction(unavailableMessage) }
    )
}

internal fun DataResult<OpenDirectMessageResult>.openMembersDirectMessageResult(
    pager: Pager,
    // The member page's own scope: an embedded detail (the wide Members pane) sits on the shell's
    // pager, whose page data carries no shell scope, so the open was rejected (missing_source_scope)
    // and showed "Unable to open direct message" (artin 11-01, Compose 版).
    storeScope: RaftShellStoreScope,
    openInShell: ((ThreadLaunchConversationIdentity) -> Boolean)?,
    member: MemberProfile,
    timeContext: RaftTimeContext?,
    unavailableMessage: String,
    errorTemplate: String,
    permissionMessage: String,
    errorStrings: ErrorPresentationStrings,
    onUnavailableAction: (String) -> Unit,
    /** A server error or refusal: its message and the failed request's trace id, for an error toast. */
    onErrorAction: (message: String, traceId: String?) -> Unit = { message, _ -> onUnavailableAction(message) },
) {
    when (this) {
        is DataResult.Content -> {
            val identity = membersDirectMessageConversationIdentity(member, data.conversation)
            if (identity != null && openInShell?.invoke(identity) == true) return
            val launchPayload =
                buildMembersDirectMessageThreadLaunchPayload(
                    member = member,
                    conversation = data.conversation,
                    timeContext = timeContext
                )
            if (launchPayload == null) {
                onUnavailableAction(unavailableMessage)
            } else {
                pager.openRaftPage(
                    pageName = THREAD_PAGE,
                    pageData = launchPayload.toThreadPageData(),
                    storeScopeOverride = storeScope,
                    onMissingScope = { onUnavailableAction(unavailableMessage) }
                )
            }
        }
        is DataResult.Empty ->
            onUnavailableAction(unavailableMessage)
        is DataResult.Error ->
            onErrorAction(membersFormattedScreenError(errorTemplate, error, errorStrings), error.traceId)
        is DataResult.NoPermission ->
            onErrorAction(permissionMessage, blocker.traceId)
    }
}

/** The DM with [member] as a conversation identity, or null without a thread / channel id. */
internal fun membersDirectMessageConversationIdentity(
    member: MemberProfile,
    conversation: HomeDirectMessageRow,
): ThreadLaunchConversationIdentity? {
    val threadId =
        conversation.threadId.value.trim().takeIf { it.isNotBlank() }
            ?: conversation.channelId.value.trim().takeIf { it.isNotBlank() }
            ?: return null
    return ThreadLaunchConversationIdentity(
        threadId = ThreadId(threadId),
        channelId = ChannelId(threadId),
        channelType = RaftThreadSurfaceKind.DirectMessage,
        kind = ConversationKind.DirectMessage,
        dmPeerIdentity =
            memberDetailIdentity(
                memberId = member.id.value,
                kind = member.kind,
                sourceServerId = member.serverId?.value
            )
    )
}

internal fun buildMembersDirectMessageThreadLaunchPayload(
    member: MemberProfile,
    conversation: HomeDirectMessageRow,
    timeContext: RaftTimeContext?
): WireThreadLaunchPayload? {
    val identity = membersDirectMessageConversationIdentity(member, conversation) ?: return null
    return buildStandaloneConversationThreadLaunchPayload(
        conversation = identity,
        timeContext = timeContext,
        instanceIdOverride = "members-message-${member.id.value}-${identity.threadId.value}"
    )
}

internal fun RaftThreadWebLinkTarget.toMembersReminderThreadLaunchPayload(
    messageId: String,
    timeContext: RaftTimeContext,
    server: ServerSummary,
    conversationIdentityProvider: build.raft.app.data.SharedConversationIdentityProvider?,
    conversationDirectory: SharedConversationDirectoryState = SharedConversationDirectoryState()
): WireThreadLaunchPayload? {
    val instanceId = "members-reminder-${channelId}-${messageId}"
    val serverId = server.id.value.takeIf { it.isNotBlank() } ?: return null
    val providerDirectory =
        listOfNotNull(conversationIdentityProvider?.resolveProjection(server.id, channelId))
            .toSharedConversationDirectoryState()
    val directory =
        listOf(conversationDirectory, providerDirectory).mergedSharedConversationDirectoryState()
    val request = resolveMarkdownOpenRequest(serverId, directory) ?: return null
    return buildStandaloneConversationThreadLaunchPayload(
        conversation = request.conversationIdentity,
        timeContext = timeContext,
        anchorMessageId = request.anchorMessageId ?: messageId,
        instanceIdOverride = instanceId
    )
}

internal fun String.toRaftMembersPayload(): RaftMembersPayload {
    if (isBlank()) return RaftMembersPayload()
    val payload = JSONObject(this)
    val labelsJson = payload.optJSONObject("labels")
    return RaftMembersPayload(
        instanceId = payload.optString("instanceId"),
        title = payload.optString("title").ifBlank { "Members" },
        localOffsetMinutes = payload.optString("localOffsetMinutes").toIntOrNull(),
        todayEpochDay = payload.optString("todayEpochDay").toLongOrNull(),
        nowEpochMillis = payload.optString("nowEpochMillis").toLongOrNull(),
        timeFormat = payload.optNullableString("timeFormat"),
        initialAgentTab = payload.optNullableString("initialAgentTab"),
        agentPanelTabOrder = payload.optStringArray("agentPanelTabOrder"),
        canCreateAgents = (payload.opt("canCreateAgents") as? Boolean) ?: true,
        canInviteHumans = (payload.opt("canInviteHumans") as? Boolean) ?: true,
        labels = RaftMembersLabels(
            agents = labelsJson?.optString("agents").orEmpty().ifBlank { "AGENTS" },
            humans = labelsJson?.optString("humans").orEmpty().ifBlank { "HUMANS" },
            addHuman = labelsJson?.optString("addHuman").orEmpty().ifBlank { "Add human" }
        )
    )
}

internal fun RaftMembersPayload.toMemberDetailPageData(
    identity: MemberDetailIdentity,
    /** A row's Edit agent: open the Profile tab with the Edit runtime config dialog. */
    openRuntimeEditor: Boolean = false
): JSONObject? {
    val selected = identity.memberId.value.trim().takeIf { it.isNotBlank() } ?: return null
    val selectedMember = members.firstOrNull { it.id.value == selected }
    val launchInitialAgentTab =
        membersInitialAgentDetailTabForLaunch(
            explicitInitialTab = initialAgentTab,
            agentPanelTabOrder = agentPanelTabOrder,
            selectedMemberKind = identity.kind
        )
    return JSONObject().apply {
        put("pageName", MEMBER_DETAIL_PAGE)
        put("instanceId", "members-detail-${instanceId.ifBlank { "members" }}-$selected")
        put("memberId", selected)
        put("memberKind", identity.kind.memberKindWireValue())
        (identity.sourceServerId ?: selectedMember?.serverId)?.value?.trim()?.takeIf { it.isNotBlank() }?.let {
            put("sourceServerId", it)
        }
        if (openRuntimeEditor && identity.kind == MemberKind.Agent) {
            put("initialAgentTab", MembersAgentDetailTab.Profile.serverOrderKey)
            put(MemberDetailOpenRuntimeEditorKey, true)
        } else {
            launchInitialAgentTab?.let { put("initialAgentTab", it.serverOrderKey) }
        }
        localOffsetMinutes?.let { put("localOffsetMinutes", it) }
        todayEpochDay?.let { put("todayEpochDay", it) }
        nowEpochMillis?.let { put("nowEpochMillis", it) }
        timeFormat?.takeIf { it.isNotBlank() }?.let { put("timeFormat", it) }
        if (agentPanelTabOrder.isNotEmpty()) {
            put("agentPanelTabOrder", JSONArray().apply {
                agentPanelTabOrder.forEach(::put)
            })
        }
    }
}

internal fun JSONObject.toRaftMemberDetailPayload(): RaftMemberDetailPayload {
    val payloadText = optString("payload")
    val payload = if (payloadText.isNotBlank()) JSONObject(payloadText) else this
    return RaftMemberDetailPayload(
        instanceId = payload.optString("instanceId").ifBlank { optString("instanceId") },
        identity =
            memberDetailIdentityFromWire(
                memberId = payload.optString("memberId").ifBlank { payload.optString("selectedMemberId") },
                kindWireValue =
                    payload.optString("memberKind")
                        .ifBlank { payload.optString("selectedMemberKind") }
                        .ifBlank { payload.optString("kind") },
                sourceServerId = payload.optString("sourceServerId").ifBlank { payload.optString("serverId") },
                source = MemberDetailIdentitySource.LegacyPageData
            ),
        initialAgentTab = payload.optNullableString("initialAgentTab"),
        visualMode = payload.optNullableString("visualMode") ?: optNullableString("visualMode"),
        localOffsetMinutes = payload.optString("localOffsetMinutes").toIntOrNull(),
        todayEpochDay = payload.optString("todayEpochDay").toLongOrNull(),
        nowEpochMillis = payload.optString("nowEpochMillis").toLongOrNull(),
        timeFormat = payload.optNullableString("timeFormat"),
        agentPanelTabOrder = payload.optStringArray("agentPanelTabOrder"),
        sourceServerName = payload.optNullableString("sourceServerName"),
        profileChannelId = payload.optNullableString(MemberDetailProfileChannelIdKey),
        openRuntimeEditor = payload.optBoolean(MemberDetailOpenRuntimeEditorKey)
    )
}

/** Page-data key of [RaftMemberDetailPayload.openRuntimeEditor]. */
internal const val MemberDetailOpenRuntimeEditorKey = "openRuntimeEditor"

private fun JSONObject.optNullableString(key: String): String? =
    optString(key).takeIf { it.isNotBlank() && it != "null" }

private fun JSONObject.optStringArray(key: String): List<String> =
    buildList {
        val array = optJSONArray(key) ?: return@buildList
        for (index in 0 until array.length()) {
            array.optString(index)?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }

/**
 * Parameter holders for [MembersRouteContent] / [MembersListContent] (task #1130): both took 33
 * parameters, past the 31-per-int `$changed` / `$default` mask boundary. Same caveats as
 * [MembersAgentRuntimeModelInputs]: plain unstable classes, behaviour unchanged, no
 * `@Stable` / `@Immutable` later.
 */
internal class MembersCreateAgentInputs(
    /**
     * Optional create-agent flow. When non-null, the agents section "+" opens a
     * SharedCreateAgentDialog and submit is forwarded to this host callback.
     * The host is responsible for the actual API call and refreshing the members
     * payload.
     */
    val createAgentSubmit: (suspend (SharedCreateAgentDialogSubmit) -> DataResult<Unit>)? = null,
    /**
     * The external agent's own two-step dialog (web `CreateExternalAgentDialog`, botiverse/slock
     * #14627): Create agent's "External agent" opens it instead of the create form; null keeps the form.
     */
    val createExternalAgent: RaftExternalAgentCreateHost? = null,
    val createAgentMachines: List<SharedCreateAgentMachineOption> = emptyList(),
    val createAgentMachinesLoading: Boolean = false,
    val createAgentMachinesErrorMessage: String? = null,
    val createAgentCurrentAgentCount: Int = 0,
    val createAgentMaxAgents: Int = -1,
    /** The plan for the agent-limit banner ("… on Free plan"); null states the server's count. */
    val createAgentLimitPlanLabel: String? = null,
    /** Universal-seat state under `seat_v1`; null under `person_v2` or non-seat plans. */
    val createAgentSeatLimit: build.raft.app.data.CreateAgentSeatState? = null,
    val createAgentLoadMachines: () -> Unit = {},
    val createAgentRetryMachines: () -> Unit = {},
    val createAgentFormStore: build.raft.app.data.SharedCreateAgentFormStore? = null,
    val createAgentServerId: String? = null,
    /** Agent @names already used on this server, so a suggested name avoids them. */
    val createAgentTakenAgentNames: List<String> = emptyList(),
    /**
     * iOS view-only Billing: "View your plan" on the agent-limit banner and error opens the
     * view-only Plan & Billing page. Null elsewhere (no button).
     */
    val createAgentViewPlan: (() -> Unit)? = null,
    /** Where Billing offers upgrades: the agent-limit banner's "Upgrade for more" link opens Billing. */
    val createAgentUpgrade: (() -> Unit)? = null,
    /** Like [createAgentUpgrade], carrying the typed/suggested agent name for `pro_seat_add` seat requests. */
    val createAgentUpgradeWithName: ((String) -> Unit)? = null,
    /** The no-computer dialog's "Add Computer": Settings > Computers with its Add Computer dialog open. */
    val createAgentAddComputer: (() -> Unit)? = null
)

/** @names (handles) of live agents and humans, so a suggested create-agent name avoids them. */
internal fun List<MemberProfile>.takenAgentNames(): List<String> =
    filter {
        (it.kind == MemberKind.Agent && it.agentDeletedAt.isNullOrBlank()) || it.kind == MemberKind.Human
    }.map { it.handle }

internal class MembersInviteHumanInputs(
    /**
     * Optional invite-human flow. When non-null, the humans section "+" opens
     * this dialog (the host renders [SharedServerInviteHumanDialogHost]); its
     * argument closes it. Null hides the entry.
     */
    val inviteHumanDialog: (@Composable (onDismiss: () -> Unit) -> Unit)? = null
)

/** Web `agentErrorRecordRefreshDelayMs`: how long a burst of error frames settles before the re-read. */
private const val MembersAgentErrorRecordRereadDelayMs = 1_500L

/**
 * Web `ViewMessageLink` (`AgentWorkerTimeline.tsx`): resolves a timeline event's `messageId`
 * through `GET /api/messages/context/:id?before=0&after=0` under the viewer's own permissions,
 * then opens it in its conversation (or shows `"Message not found"` on 404/403/error).
 * Kept out of [MemberDetailPageContent] (JVM method size).
 */
@Composable
private fun rememberMemberDetailWorkerTimelineMessageOpener(
    pager: Pager,
    provider: MemberDetailRouteProvider,
    server: ServerSummary,
    timeContextProvider: RaftTimeContextProvider,
    fallbackTimeContext: RaftTimeContext,
    scope: kotlinx.coroutines.CoroutineScope,
): (String) -> Unit {
    val unavailableMessage = stringResource(Res.string.message_link_not_found_title)
    var inFlightMessageId by remember(server.id) { mutableStateOf<String?>(null) }
    val latestFallbackTimeContext by rememberUpdatedState(fallbackTimeContext)
    return remember(pager, provider, server, timeContextProvider, scope, unavailableMessage) {
        { rawMessageId: String ->
            val messageId = rawMessageId.trim()
            val api = provider.agentControlsApi
            if (messageId.isNotEmpty() && inFlightMessageId == null) {
                if (api == null) {
                    provider.bridge.toast(unavailableMessage)
                } else {
                    inFlightMessageId = messageId
                    scope.launch {
                        try {
                            when (
                                val result =
                                    api.resolveMessageTarget(
                                        messageId = messageId,
                                        contextPageSink = provider.messageContextPageSink,
                                    )
                            ) {
                                is DataResult.Content ->
                                    pager.openMembersMessageTarget(
                                        target =
                                            RaftThreadWebLinkTarget(
                                                channelId = result.data.channelId,
                                                messageId = result.data.messageId,
                                            ),
                                        messageId = result.data.messageId,
                                        server = server,
                                        conversationIdentityProvider = provider.conversationIdentityProvider,
                                        storeScope = provider.storeScope,
                                        timeContext = timeContextProvider.current ?: latestFallbackTimeContext,
                                        unavailableMessage = unavailableMessage,
                                        onUnavailableAction = { provider.bridge.toast(it) },
                                    )
                                is DataResult.Empty,
                                is DataResult.Error,
                                is DataResult.NoPermission -> provider.bridge.toast(unavailableMessage)
                            }
                        } finally {
                            if (inFlightMessageId == messageId) {
                                inFlightMessageId = null
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The viewer's own personal assistant, for its owner's section of the agent profile
 * ([AssistantOwnerScope]); null off an agent route, without the person or the flags. Kept out of
 * [MemberDetailPageContent] (JVM method size).
 */
@Composable
private fun rememberMemberDetailAssistantOwnerScope(
    provider: MemberDetailRouteProvider,
    assistantRoute: MemberDetailRouteState.Agent?,
    routeScope: kotlinx.coroutines.CoroutineScope,
    closeDetail: () -> Unit,
): AssistantOwnerScope? {
    val bridge = provider.bridge
    val closeAfterAssistantDeleted by rememberUpdatedState(closeDetail)
    return remember(
        bridge,
        provider,
        assistantRoute?.server?.id,
        assistantRoute?.server?.role,
        assistantRoute?.currentUserId
    ) {
        val route = assistantRoute ?: return@remember null
        val userId = route.currentUserId?.trim()?.takeIf { it.isNotBlank() } ?: return@remember null
        val flags = provider.serverFeatureFlags ?: return@remember null
        val networkClient = build.raft.app.data.RaftSharedStoreRegistry.get(bridge).networkClient(provider.storeScope)
        AssistantOwnerScope(
            store = build.raft.app.data.RaftSharedStoreRegistry.get(bridge).myAssistantStore(provider.storeScope),
            featureFlags = flags,
            serverMembersStore = provider.serverMembersStore,
            // The profile's own lifecycle Start (MemberRepository.startAgent), single-flight per agent.
            startAgent = { agentId ->
                provider.runLifecycleAction(route.server.id, agentId, MembersAgentLifecycleAction.Start)
            },
            onErrorToast = { message, error -> bridge.errorToast(message, error) },
            serverId = route.server.id.value,
            userId = userId,
            viewerIsGuest = route.server.role?.trim()?.equals("guest", ignoreCase = true) == true,
            settingChangesApi = build.raft.app.data.RaftAssistantSettingChangesNetworkApi(networkClient),
            weChatApi = build.raft.app.data.RaftAssistantDmCardsNetworkApi(networkClient),
            weChatConnectApi = build.raft.app.data.RaftAssistantWeChatConnectNetworkApi(networkClient),
            actionScope = routeScope,
            onToast = { message, intent -> bridge.toastWithIntent(message, intent) },
            // Deleted: the profile closes, back to the assistant's page (Create) or wherever it was opened from.
            onAssistantDeleted = { closeAfterAssistantDeleted() },
        )
    }
}

@Composable
private fun rememberMemberDetailAgentVoiceSectionScope(
    provider: MemberDetailRouteProvider,
    agentRoute: MemberDetailRouteState.Agent?,
    detail: AgentDetailSnapshot?,
    timeContext: RaftTimeContext?,
): build.raft.app.agents.AgentVoiceSectionScope? {
    val bridge = provider.bridge
    val canManage =
        agentRoute != null &&
            membersRouteCanManageAgent(
                allowsPrivateAgentSurfaces = agentRoute.accessMode.allowsPrivateAgentSurfaces,
                canManageSettings = agentRoute.server.permissions.canManageSettings,
                currentUserId = agentRoute.currentUserId,
                detail = detail,
            )
    return remember(bridge, provider, agentRoute?.server?.id, canManage, timeContext) {
        val route = agentRoute ?: return@remember null
        val flags = provider.serverFeatureFlags ?: return@remember null
        val networkClient = build.raft.app.data.RaftSharedStoreRegistry.get(bridge).networkClient(provider.storeScope)
        build.raft.app.agents.AgentVoiceSectionScope(
            api = build.raft.app.data.RaftAgentVoiceSettingsApi(networkClient),
            featureFlags = flags,
            serverId = route.server.id.value,
            canManage = canManage,
            timeContext = timeContext,
        )
    }
}

@Composable
private fun rememberMemberDetailAgentVoiceCallAction(
    provider: MemberDetailRouteProvider,
    current: MemberDetailRouteState.Agent,
    member: MemberProfile,
    detail: AgentDetailSnapshot?,
    agentMachineId: String?,
    machines: List<build.raft.app.data.ComputerSummary>,
): (() -> Unit)? {
    val bounded =
        membersAgentIsBoundedPublicProjection(
            accessMode = current.accessMode,
            profileProjection =
                detail?.summary?.profileProjection?.takeIf { it.isNotBlank() } ?: member.profileProjection,
        )
    if (bounded) return null
    val sharedStores = remember(provider.bridge) { build.raft.app.data.RaftSharedStoreRegistry.get(provider.bridge) }
    return build.raft.app.agents.rememberAgentVoiceCallAction(
        flags = provider.serverFeatureFlags,
        callStore = sharedStores.agentVoiceCallStore,
        serverId = current.server.id.value,
        serverRole = current.server.role,
        agentId = member.id.value,
        agentName = member.displayName,
        avatarWireValue = build.raft.app.model.avatarWireValueForAvatarSlot(
            kind = member.kind,
            id = member.id.value,
            avatarWireValue = member.avatar?.wireValue,
        ),
        deletedAt = detail?.summary?.deletedAt ?: member.agentDeletedAt,
        managedByAppInstallId = detail?.summary?.managedByAppInstallId ?: member.managedByAppInstallId,
        agentStatus = detail?.summary?.status ?: member.agentLifecycleStatus,
        machineId = agentMachineId,
        computerOffline = build.raft.app.agents.isComputerOfflineForVoiceCall(agentMachineId, machines),
    )
}

/**
 * Resolves a [RaftTimeContextProvider] for [MemberDetailPageContent] without fabricating silent UTC
 * or freezing relative reminder clocks when opened from routes without explicit timezone params
 * (AssistantPage, SettingsServerArchive, RaftSettingsComputersPage, ThreadMembersPage).
 *
 * - Pinned payloads (`nowEpochMillis != null`, e.g., visual fixtures) get a deterministic static
 *   provider with no timer. Payloads carrying only `todayEpochDay` (live route hand-off) stay live
 *   so relative reminder labels and chat timestamps keep ticking.
 * - Live pages inherit [currentRaftTimeContextProvider] (`LocalRaftTimeContextProvider` in wide split
 *   or `BasePager`'s `LocalRaftPageBaseTimeContextProvider`) whenever the payload does not specify a
 *   conflicting offset or 12h/24h format, avoiding duplicate timers; isolated callers or explicit
 *   overrides get a fallback live provider.
 */
@Composable
internal fun rememberMemberDetailTimeContextProvider(
    payload: RaftMemberDetailPayload,
    pageVisibleTick: Int,
    pageVisible: Boolean,
): RaftTimeContextProvider {
    val hasPinnedClock = payload.nowEpochMillis != null
    val inherited = currentRaftTimeContextProvider
    val runtimeTimeFormat = RaftTimeFormatRuntime.observe(fallback = payload.timeFormat ?: RaftTimeFormat24Hour)
    val runtimeTimeZone = RaftTimeZoneRuntime.observe()
    val pinnedContext =
        remember(hasPinnedClock, payload) {
            if (hasPinnedClock) payload.toRaftTimeContext() else null
        }
    val pinnedProvider =
        remember(pinnedContext) {
            pinnedContext?.let(::RaftTimeContextProvider)
        }
    val inheritedDateContext = inherited?.dateContext
    val inheritedMatchesRuntime =
        inherited != null &&
            inheritedDateContext?.timeFormat == runtimeTimeFormat &&
            (runtimeTimeZone == null || inheritedDateContext.timeZoneId == runtimeTimeZone)
    val needsFallback = !hasPinnedClock && !inheritedMatchesRuntime
    val fallbackCapabilities =
        remember(needsFallback, payload.localOffsetMinutes, runtimeTimeFormat, runtimeTimeZone) {
            if (needsFallback) {
                RaftTimeCapabilities(
                    localOffsetMinutes =
                        payload.localOffsetMinutes
                            ?: ProcessRaftDatetimeFoundationOwner.localOffsetMinutesAt(defaultNowMillis(), 0),
                    timeFormat = runtimeTimeFormat,
                ).withRuntimePreferences(
                    timeFormat = runtimeTimeFormat,
                    preferredTimeZoneId = runtimeTimeZone,
                )
            } else {
                null
            }
        }
    return when {
        pinnedProvider != null -> pinnedProvider
        inheritedMatchesRuntime -> inherited!!
        else ->
            rememberLiveRaftTimeContext(
                capabilities = fallbackCapabilities,
                pageVisibleTick = pageVisibleTick,
                active = pageVisible,
            )
    }
}

internal fun RaftMemberDetailPayload.toRaftTimeContextOrNull(): RaftTimeContext? {
    val offset = localOffsetMinutes ?: if (todayEpochDay != null || nowEpochMillis != null) 0 else return null
    return RaftTimeContext(
        localOffsetMinutes = offset,
        todayEpochDay = todayEpochDay,
        nowEpochMillis = nowEpochMillis,
        timeFormat = timeFormat ?: RaftTimeFormat24Hour,
    )
}

internal fun RaftMemberDetailPayload.toRaftTimeContext(): RaftTimeContext =
    RaftTimeContext(
        localOffsetMinutes = localOffsetMinutes ?: 0,
        todayEpochDay = todayEpochDay,
        nowEpochMillis = nowEpochMillis,
        timeFormat = timeFormat ?: RaftTimeFormat24Hour,
    )
