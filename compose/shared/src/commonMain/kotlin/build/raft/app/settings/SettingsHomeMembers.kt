package build.raft.app

import build.raft.app.resources.*
import androidx.compose.ui.input.key.key
import build.raft.app.data.normalizedAgentRuntimeStatus
import build.raft.app.data.SharedComputersIntent
import build.raft.app.data.SharedComputersPickerSlice
import build.raft.app.data.SharedComputersStore
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.MemberRepository
import build.raft.app.data.SettingsRepository
import build.raft.app.data.SettingsServerManagementRepository
import build.raft.app.data.SharedMembersDataSource
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.RaftAgentsNetworkApi
import build.raft.app.data.SharedCreateAgentCoordinator
import build.raft.app.data.SharedCreateAgentFormStore
import build.raft.app.data.SharedAgentDetailIntent
import build.raft.app.data.SharedAgentDetailStore
import build.raft.app.data.SharedAgentPanelTabOrderStore
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.MemberSnapshot
import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.RaftTimeContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.unit.Dp
import build.raft.app.compat.pager.Pager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import build.raft.app.data.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import build.raft.app.model.isExternalAgent

@Composable
internal fun SharedMembersScreen(
    /** Back to More when Members is a page pushed from it; null on the wide rail. */
    onBack: (() -> Unit)? = null,
    bridge: build.raft.app.base.BridgeModule,
    storeScope: RaftShellStoreScope,
    sharedStores: RaftSharedStoreProvider,
    /** Developer environment web URL the invite link is built from. */
    loadInviteWebBaseUrl: suspend () -> String?,
    session: StoredAuthSession?,
    memberRepository: MemberRepository,
    settingsRepository: SettingsRepository,
    createAgentCoordinator: SharedCreateAgentCoordinator,
    createAgentFormStore: SharedCreateAgentFormStore,
    computersStore: SharedComputersStore,
    agentDetailStore: SharedAgentDetailStore,
    agentPanelTabOrderStore: SharedAgentPanelTabOrderStore,
    serverMembersStore: SharedServerMembersStore,
    active: Boolean,
    membersRefreshKey: Int,
    initialContent: SharedMembersUiState.Content?,
    onContentLoaded: (SharedMembersUiState.Content) -> Unit,
    onRetry: () -> Unit,
    timeContext: AppShellTimeContext?,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    scrollToTopRequest: Int = 0,
    initialListState: AppShellMembersListState = AppShellMembersListState(),
    onListStateSnapshot: (AppShellMembersListState) -> Unit = {},
    paneLayout: RaftAdaptivePaneLayout = RaftAdaptivePaneLayout.Compact,
    desktopMembersEnabled: Boolean = false,
    detailSelection: androidx.compose.runtime.MutableState<RaftMemberDetailPayload?>,
    /** A member the wide layout's Back / Forward reopens in the split's detail pane, until handled. */
    detailOpenRequest: MemberDetailIdentity? = null,
    onDetailOpenRequestHandled: () -> Unit = {},
    onForegroundActivity: (String) -> Unit = {},
    /** The split detail's DM button: open the DM in the shell (Chat, the DM selected); false = a page. */
    openDirectMessageInShell: ((ThreadLaunchConversationIdentity) -> Boolean)? = null,
) {
    val errorStrings = localizedErrorPresentationStrings()
    val membersTitle = stringResource(Res.string.tab_members)
    val membersLoadFailedMessage = stringResource(Res.string.thread_composer_load_members_failed)
    val openProfileFailedMessage = stringResource(Res.string.member_error_open_profile)
    val currentServerId = session?.currentServerId?.trim()?.takeIf { it.isNotBlank() }
    var selectedDetail by detailSelection
    val showMemberSplit = desktopMembersEnabled && paneLayout == RaftAdaptivePaneLayout.DualPane
    // In the split view the detail pane is page state; only the compact detail overlay blocks
    // the full-page back swipe.
    RaftBackHandler(enabled = active && selectedDetail != null, label = "members_detail_pane", blocksBackSwipe = !showMemberSplit) { selectedDetail = null }
    var state by remember(currentServerId) {
        mutableStateOf<SharedMembersUiState>(initialContent ?: SharedMembersUiState.Loading)
    }
    var refreshing by remember { mutableStateOf(false) }
    // The agent just created here, until it lands (see the effect beside MembersAdaptiveWorkspace):
    // the app shell's Members (Settings and the server menu) creates through this screen, not
    // MembersPage (Hands afae9ebb). Same landing as MembersPage ([CreatedAgentLanding]).
    var pendingCreatedAgent by remember(currentServerId) { mutableStateOf<CreatedAgentLanding?>(null) }
    val pager = LocalActivity.current.getPager() as Pager
    val routeScope = rememberCoroutineScope()
    val agentPanelTabOrderState by agentPanelTabOrderStore.state.collectAsState()
    val settingsServerManagementRepository = settingsRepository as? SettingsServerManagementRepository
    suspend fun loadAgentPanelTabOrderForSharedMembers(): List<String>? {
        val repository = settingsServerManagementRepository ?: return null
        return when (val result = repository.getServerSidebarOrder()) {
            is DataResult.Content -> result.data.agentPanelTabOrder
            is DataResult.Empty -> null
            is DataResult.Error -> {
                raftLogWarn("SlockAgentPanelTabOrder", "shared_members_agent_tab_order_load_failed code=${result.error.code}")
                null
            }
            is DataResult.NoPermission -> {
                raftLogWarn("SlockAgentPanelTabOrder", "shared_members_agent_tab_order_load_blocked code=${result.blocker.code}")
                null
            }
        }
    }
    LaunchedEffect(agentPanelTabOrderStore, currentServerId) {
        agentPanelTabOrderStore.bindServer(
            serverId = currentServerId,
            source = "shared_members_bind_server",
            logger = raftLogger("SlockAgentPanelTabOrder")
        )
    }
    LaunchedEffect(
        settingsServerManagementRepository,
        agentPanelTabOrderStore,
        currentServerId,
        agentPanelTabOrderState.serverId,
        agentPanelTabOrderState.loaded
    ) {
        if (currentServerId.isNullOrBlank()) return@LaunchedEffect
        if (agentPanelTabOrderState.serverId == currentServerId && agentPanelTabOrderState.loaded) return@LaunchedEffect
        loadAgentPanelTabOrderForSharedMembers()?.let { order ->
            agentPanelTabOrderStore.applyLoaded(
                serverId = currentServerId,
                order = order,
                source = "shared_members_sidebar_order_get",
                logger = raftLogger("SlockAgentPanelTabOrder")
            )
        }
    }

    LaunchedEffect(computersStore, currentServerId, active, membersRefreshKey) {
        computersStore.reconcileOwner()
        val serverId = currentServerId?.takeIf { active && it.isNotBlank() }
            ?: return@LaunchedEffect
        computersStore.ensureComputerPickerForServer(
            serverId = serverId,
            force = true,
            priority = DataRequestPriority.Visible
        )
    }

    // Task #150: a Retry tap forces the reload so it can never be satisfied by (or stuck behind)
    // what the failed attempt left in the shared members core.
    var membersRetryForce by remember { mutableStateOf(false) }
    // Keyed locally too: the parent's refresh key reaches this screen through a tab content lambda,
    // so a Retry must not depend on that lambda being recomposed to re-run the load.
    var membersRetryNonce by remember { mutableStateOf(0) }
    LaunchedEffect(membersRefreshKey, currentServerId, membersRetryNonce) {
        val force = membersRetryForce
        membersRetryForce = false
        var previousContent: SharedMembersUiState.Content? = null
        try {
            previousContent =
                sharedMembersCachedContentForServer(state as? SharedMembersUiState.Content, currentServerId)
                    ?: sharedMembersCachedContentForServer(initialContent, currentServerId)
            if (previousContent != null) {
                serverMembersStore.seed(MemberSnapshot(previousContent.server, previousContent.members))
                state = previousContent
                refreshing = true
            } else {
                state = SharedMembersUiState.Loading
            }
            val server = session?.servers?.firstOrNull { it.id == currentServerId }?.toServerSummary()
            if (session == null || currentServerId.isNullOrBlank() || server == null) {
                // Was silent: a Retry that ends here never reached the network.
                raftLogger("SlockMembersPage")(
                    "members_page_load_skipped reason=server_required session=${session != null} " +
                        "serverSelected=${!currentServerId.isNullOrBlank()} serverInSession=${server != null}"
                )
                state = previousContent ?: SharedMembersUiState.Error("server_required")
                return@LaunchedEffect
            }
            val serverId = ServerId(currentServerId)
            val response =
                serverMembersStore.refreshMembers(
                    serverId,
                    DataRequestPriority.Visible,
                    force = force,
                    reason = if (force) "members_retry" else "members_page"
                )
            state =
                when (response) {
                    is DataResult.Content -> {
                        val content =
                            serverMembersStore.currentServerMembersContent(serverId, fallbackServer = server)?.let { snapshot ->
                                SharedMembersUiState.Content(snapshot.server, snapshot.members, session.user.id)
                            } ?: SharedMembersUiState.Content(server, emptyList(), session.user.id)
                        onContentLoaded(content)
                        content
                    }
                    is DataResult.Empty -> {
                        val content = SharedMembersUiState.Content(server, emptyList(), session.user.id)
                        onContentLoaded(content)
                        content
                    }
                    is DataResult.NoPermission ->
                        previousContent ?: SharedMembersUiState.Error(response.blocker.code, response.blocker.traceId)
                    is DataResult.Error ->
                        previousContent ?: SharedMembersUiState.Error(response.error.code, response.error.traceId)
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            state = previousContent ?: SharedMembersUiState.Error("members_network")
        } finally {
            refreshing = false
        }
    }

    when (val current = state) {
        SharedMembersUiState.Loading ->
            Column(modifier = Modifier.fillMaxSize().background(RaftColor.Canvas)) {
                // No head under the wide top bar, as the loaded list (MembersListContent).
                if (!raftUnderAppShellTopBar() || onBack != null) {
                    MembersPageHeader(title = membersTitle, onBack = onBack)
                }
                RaftCenteredLoading()
        }
        is SharedMembersUiState.Error ->
            MembersPageErrorContent(
                message = membersLoadFailedMessage,
                code = current.code,
                traceId = current.traceId,
                onBack = onBack,
                onRetry = {
                    membersRetryForce = true
                    membersRetryNonce += 1
                    onRetry()
                }
            )
        is SharedMembersUiState.Content -> {
            val serverId = current.server.id.value
            val agentPanelTabOrder =
                if (agentPanelTabOrderState.serverId == serverId) {
                    agentPanelTabOrderState.order
                } else {
                    emptyList()
                }
            val membersPayload = buildHostMembersPayload(current.server, timeContext, agentPanelTabOrder)
            val renderMembersPayload = membersPayload.copy(members = current.members)
            LaunchedEffect(detailOpenRequest, membersPayload, showMemberSplit) {
                val request = detailOpenRequest ?: return@LaunchedEffect
                // The same payload a row tap in the split builds (identity only; the detail loads the rest).
                if (showMemberSplit) selectedDetail = membersPayload.toMemberDetailPageData(request)?.toRaftMemberDetailPayload()
                onDetailOpenRequestHandled()
            }
            val agentStatusResolver =
                remember(serverMembersStore, serverId) {
                    raftScopedAgentStatusResolver(serverMembersStore, serverId)
                }
            val computersSlice by computersStore
                .computerPickerSliceFlow(serverId)
                .collectAsState(SharedComputersPickerSlice())
            // MEMBERS_COMPUTERS_ERROR_RETRY_WIRING_START
            val loadMachines: () -> Unit = {
                onForegroundActivity("load_machines")
                if (serverId.isNotBlank()) {
                    computersStore.dispatch(
                        SharedComputersIntent.EnsureComputerPicker(
                            serverId = serverId,
                            priority = DataRequestPriority.Visible
                        )
                    )
                }
            }
            val retryMachines: () -> Unit = {
                onForegroundActivity("retry_machines")
                if (serverId.isNotBlank()) {
                    computersStore.dispatch(
                        SharedComputersIntent.RefreshComputerPicker(
                            serverId = serverId,
                            priority = DataRequestPriority.Visible
                        )
                    )
                }
            }
            // task #79: wire Settings → Members humans "+" invite flow.
            // Mirror the MembersPage.kt path: build a SharedMembersDataSource
            // on the fly from the host memberRepository + settingsRepository,
            // then expose loadJoinLinks/ensureJoinLink/sendInvite to the
            // dialog via MembersRouteContent invite params.
            val settingsMembersDataSource = remember(memberRepository, settingsRepository, serverMembersStore, serverId) {
                SharedMembersDataSource(
                    memberRepository = memberRepository,
                    settingsRepository = settingsRepository,
                    serverMembersStore = serverMembersStore
                )
            }
            val inviteHumanDialog: (@Composable (() -> Unit) -> Unit)? =
                if (membersPayload.canInviteHumans && serverId.isNotBlank()) {
                    { onDismiss ->
                        SharedServerInviteHumanDialogHost(
                            serverId = serverId,
                            membersDataSource = settingsMembersDataSource,
                            loadWebBaseUrl = {
                                onForegroundActivity("load_invite_link")
                                loadInviteWebBaseUrl()
                            },
                            copyToPasteboard = { url ->
                                onForegroundActivity("copy_invite_link")
                                bridge.copyToPasteboard(url)
                            },
                            onDismiss = onDismiss,
                            onSeatLimitWithEmails =
                                if (settingsBillingOffersUpgrade()) {
                                    { emails ->
                                        pager.openRaftUpgradeDialog(
                                            RaftUpgradeGate.SeatLimitInvite,
                                            storeScope,
                                            seatRequestPurpose =
                                                emails.takeIf { it.isNotEmpty() }?.let { build.raft.app.data.SeatRequestPurpose.Invite(it) },
                                        )
                                    }
                                } else {
                                    null
                                },
                        )
                    }
                } else {
                    null
                }
            // [land] false: the external agent's two-step dialog stays open on its setup and lands it on Done.
            val createAgentProfile: suspend (SharedCreateAgentDialogSubmit, Boolean) -> DataResult<MemberProfile> = { submit, land ->
                onForegroundActivity("create_agent_submit")
                val result =
                    createAgentCoordinator
                        .createAgent(submit.toCreateAgentRequest())
                var created: MemberProfile? = null
                if (result is DataResult.Content) {
                    createAgentFormStore.rememberCreatedAgent(serverId, submit)
                    val profile = createdAgentMemberProfile(result.data, submit, computersSlice.machines)
                    created = profile
                    val nextState =
                        when (val latest = state) {
                            is SharedMembersUiState.Content ->
                                latest.copy(members = latest.members + profile)
                            else -> latest
                        }
                    state = nextState
                    (nextState as? SharedMembersUiState.Content)?.let(onContentLoaded)
                    // Stage the landing inside submit, before the dialog's teardown cancels this
                    // coroutine (the same ordering as MembersPage, task #399 / #3140).
                    if (land) pendingCreatedAgent = createdAgentLanding(submit, profile.inCreatingServer(serverId))
                    // The row above is only a stand-in: replace it with the server's record. Re-entering
                    // the page seeds the store from this cached content, so without a forced refresh the
                    // stand-in was served as a cache hit indefinitely (task #148, HuaTuo 23bc896b).
                    val refreshed =
                        serverMembersStore.refreshMembers(
                            ServerId(serverId),
                            DataRequestPriority.Background,
                            force = true,
                            reason = "members_agent_created"
                        )
                    val latest = state as? SharedMembersUiState.Content
                    if (refreshed is DataResult.Content && latest != null) {
                        serverMembersStore.currentServerMembersContent(ServerId(serverId), fallbackServer = latest.server)
                            ?.let { snapshot ->
                                val content = latest.copy(server = snapshot.server, members = snapshot.members)
                                state = content
                                onContentLoaded(content)
                            }
                    }
                }
                when (result) {
                    is DataResult.Content -> DataResult.Content(created!!.inCreatingServer(serverId))
                    is DataResult.Empty -> DataResult.Empty(result.reason)
                    is DataResult.Error -> result
                    is DataResult.NoPermission -> result
                }
            }
            val createAgentSubmit: suspend (SharedCreateAgentDialogSubmit) -> DataResult<Unit> = { submit ->
                createAgentProfile(submit, true).map { }
            }
            val externalAgentsApiClient =
                remember(sharedStores, storeScope) {
                    RaftAgentsApiClient(
                        transport = RaftAgentsNetworkApi(sharedStores.networkClient(storeScope)),
                        diagnosticsLogger = raftLogger("SlockAgentsApi", "app_shell_external_agent_create")
                    )
                }
            val createExternalAgentHost =
                RaftExternalAgentCreateHost(
                    agentsApiClient = externalAgentsApiClient,
                    serverUrl = sharedStores.apiEnvironmentStore.current().webUrl,
                    create = { submit -> createAgentProfile(submit, false) },
                    onFinished = { profile -> pendingCreatedAgent = CreatedAgentLanding.SetupGuide(profile) },
                    onDiscarded = { profile ->
                        (state as? SharedMembersUiState.Content)?.let { latest ->
                            val content = latest.copy(members = latest.members.filterNot { it.id == profile.id })
                            state = content
                            onContentLoaded(content)
                        }
                        routeScope.launch {
                            serverMembersStore.refreshMembers(
                                ServerId(serverId),
                                DataRequestPriority.Background,
                                force = true,
                                reason = "members_agent_discarded"
                            )
                        }
                    },
                )
            // Task #409: persist per-machine agent-group collapse for the
            // shared members page under the session user id.
            val membersAgentGroupCollapseStore = remember {
                build.raft.app.data.KeyValueMembersAgentMachineGroupCollapseStore(
                    build.raft.app.data.raftSharedPreferenceStore()
                )
            }
            // [openRuntimeEditor]: a row's Edit agent, the profile with its Edit runtime config open.
            val openMemberDetailWith: (MemberProfile, Boolean) -> Unit = { member, openRuntimeEditor ->
                onForegroundActivity("open_member")
                if (showMemberSplit) {
                    // Resolve local fallback now, never from a later selected server.
                    val identity = MemberDetailIdentity(member.id, member.kind, member.serverId ?: ServerId(serverId))
                    selectedDetail = membersPayload.toMemberDetailPageData(identity, openRuntimeEditor)?.toRaftMemberDetailPayload()
                } else routeScope.launch {
                    val identity = MemberDetailIdentity(member.id, member.kind, member.serverId)
                    val accessMode = identity.memberDetailAccessMode(serverId)
                    val launchPayload =
                        if (
                            member.kind == MemberKind.Agent &&
                                accessMode.allowsPrivateAgentSurfaces &&
                                (agentPanelTabOrderState.serverId != serverId || !agentPanelTabOrderState.loaded)
                        ) {
                            loadAgentPanelTabOrderForSharedMembers()
                                ?.also { order ->
                                    agentPanelTabOrderStore.applyLoaded(
                                        serverId = serverId,
                                        order = order,
                                        source = "shared_members_detail_open_preload",
                                        logger = raftLogger("SlockAgentPanelTabOrder")
                                    )
                                }
                                ?.let { order -> membersPayload.copy(agentPanelTabOrder = order) }
                                ?: membersPayload
                        } else {
                            membersPayload
                        }
                    val pageData =
                        launchPayload.toMemberDetailPageData(
                            identity,
                            openRuntimeEditor
                        )
                    if (pageData == null) {
                        bridge.toast(openProfileFailedMessage)
                        bridge.log("members_detail_open_skip reason=missing_member_id source=app_shell")
                    } else {
                        val seedMember =
                            member.copy(
                                serverId =
                                    member.serverId
                                        ?: serverId.trim().takeIf { it.isNotBlank() }?.let(::ServerId)
                            )
                        agentDetailStore.dispatch(SharedAgentDetailIntent.SeedDetailFromMember(seedMember))
                        pager.openRaftPage(
                            pageName = MEMBER_DETAIL_PAGE,
                            pageData = pageData,
                            storeScopeOverride = storeScope,
                            onMissingScope = {
                                bridge.toast(openProfileFailedMessage)
                                bridge.log("members_detail_open_skip reason=missing_shell_scope source=app_shell")
                            }
                        )
                    }
                }
            }
            val openMemberDetail: (MemberProfile) -> Unit = { member -> openMemberDetailWith(member, false) }
            // Web MemberActions: the rows' menus and Edit agent.
            val membersRowActions =
                rememberMembersRowActions(
                    pager = pager,
                    bridge = bridge,
                    storeScope = storeScope,
                    viewerRole = current.server.role,
                    currentUserId = current.currentUserId,
                    currentServerId = serverId,
                    onEditAgent = { member -> openMemberDetailWith(member, true) },
                    openInShell = openDirectMessageInShell,
                    timeContext = timeContext,
                )
            val createdAgentReceipt =
                rememberRaftCreatedAgentReceipt(
                    pager = pager,
                    bridge = bridge,
                    storeScope = storeScope,
                    openSetupGuide = openMemberDetail,
                    openInShell = openDirectMessageInShell,
                    timeContext = timeContext,
                )
            // Land the just-created agent as MembersPage does: an external agent's profile, else
            // the "Created <name>" toast whose View opens its DM. Runs after the stand-in row is in
            // state; cleared at once so returning to the list does not land it again.
            LaunchedEffect(pendingCreatedAgent) {
                val landing = pendingCreatedAgent ?: return@LaunchedEffect
                pendingCreatedAgent = null
                bridge.log("members_create_agent_landing memberId=${landing.agent.id.value} source=app_shell")
                createdAgentReceipt.land(landing)
            }
            MembersAdaptiveWorkspace(
                split = showMemberSplit,
                selected = selectedDetail != null,
                listPane = {
                    MembersRouteContent(
                        // Back to More on the phone (the loading and error states already had it).
                        onBack = onBack,
                        selectedIdentity = selectedDetail?.identity,
                        selectedLocalServerId = ServerId(serverId),
                        payload = renderMembersPayload,
                        refreshing = refreshing,
                        paneLayout = paneLayout,
                        onAddHuman = { onForegroundActivity("add_human") },
                        agentStatusResolver = agentStatusResolver,
                        onOpenMemberDetail = openMemberDetail,
                        rowActions = membersRowActions,
                        canCreateAgents = membersPayload.canCreateAgents,
                        createAgent = MembersCreateAgentInputs(
                            createAgentSubmit = createAgentSubmit,
                            createExternalAgent = createExternalAgentHost,
                            createAgentMachines = computersSlice.machines,
                            createAgentMachinesLoading = computersSlice.initialLoadInFlight,
                            createAgentMachinesErrorMessage = computersSlice.initialLoadError?.userFacingMessage(errorStrings),
                            createAgentCurrentAgentCount = current.members.count { it.kind == MemberKind.Agent },
                            createAgentLoadMachines = loadMachines,
                            createAgentRetryMachines = retryMachines,
                            createAgentFormStore = createAgentFormStore,
                            createAgentServerId = serverId,
                            createAgentTakenAgentNames = current.members.takenAgentNames(),
                            createAgentAddComputer = { pager.openSettingsComputersPage(storeScope, openAddComputer = true) }
                        ),
                        inviteHuman = MembersInviteHumanInputs(inviteHumanDialog = inviteHumanDialog),
                        bottomContentPadding = bottomContentPadding,
                        scrollToTopRequest = scrollToTopRequest,
                        initialListState = initialListState,
                        onListStateSnapshot = onListStateSnapshot,
                        // Task #409: persist per-machine collapse under the session user id.
                        currentUserId = current.currentUserId,
                        agentGroupCollapseStore = membersAgentGroupCollapseStore,
                        // A pull used to call only onRetry(), which bumps the parent's refresh key; that key
                        // reaches this screen through a tab content lambda that need not recompose, so the
                        // load often never re-ran and `refreshing` never went true -> false (the indicator
                        // hung or never turned). A pull now drives the local forced reload like Retry does
                        // (members) and refreshes the computer list; the load always clears `refreshing`.
                        onRefresh = {
                            onForegroundActivity("refresh")
                            raftLogger("SlockMembersPage")("members_pull_refresh serverId=$serverId")
                            refreshing = true
                            membersRetryForce = true
                            membersRetryNonce += 1
                            retryMachines()
                            onRetry()
                        }
                    )
                },
                detailPane = {
                    val detail = selectedDetail
                    if (detail == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(Res.string.members_select_detail), color = RaftColor.TextMutedGray)
                        }
                    } else if (active) {
                        // Dispose all detail-local effects on identity changes. Canonical
                        // store selectors still enforce server/member/access ownership.
                        key(storeScope, session?.user?.id, currentServerId, detail.identity) {
                            MemberDetailPageContent(
                                pageVisibleTick = 0,
                                embeddedPayload = detail,
                                storeScopeOverride = storeScope,
                                onClose = { selectedDetail = null },
                                openDirectMessageInShell = openDirectMessageInShell,
                            )
                        }
                    }
                },
            )
            // MEMBERS_COMPUTERS_ERROR_RETRY_WIRING_END
        }
    }
}

internal fun buildHostMembersPayload(
    server: ServerSummary,
    timeContext: RaftTimeContext?,
    agentPanelTabOrder: List<String> = emptyList()
): RaftMembersPayload =
    RaftMembersPayload(
        instanceId = server.id.value,
        title = "Members",
        localOffsetMinutes = timeContext?.localOffsetMinutes,
        todayEpochDay = timeContext?.todayEpochDay,
        nowEpochMillis = timeContext?.nowEpochMillis,
        timeFormat = timeContext?.timeFormat,
        agentPanelTabOrder = agentPanelTabOrder,
        // Product mapping for now: create-agent follows server admin/settings permission
        // until the API exposes a dedicated capability.
        canCreateAgents = server.permissions.canManageSettings,
        canInviteHumans = server.permissions.canInviteMember,
        showGuests = membersShowGuests(server.role)
    )

internal sealed interface SharedMembersUiState {
    data object Loading : SharedMembersUiState
    data class Error(val code: String, val traceId: String? = null) : SharedMembersUiState
    data class Content(
        val server: ServerSummary,
        val members: List<MemberProfile>,
        val currentUserId: String?
    ) : SharedMembersUiState
}

internal fun StoredAuthSession.withAppShellSelectedServer(serverId: String): StoredAuthSession? {
    val targetServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return null
    val server = servers.firstOrNull { it.id == targetServerId } ?: return null
    return copy(selectedServerId = targetServerId, selectedServerSlug = server.slug)
}

internal fun sharedMembersCachedContentForServer(
    cachedContent: SharedMembersUiState.Content?,
    serverId: String?
): SharedMembersUiState.Content? =
    cachedContent?.takeIf { content ->
        !serverId.isNullOrBlank() && content.server.id.value == serverId
    }

private fun String.toSingleLineDiagnostics(): String =
    lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(" | ")
        .take(1200)

internal fun parseSharedMembers(bodyText: String): List<MemberProfile> =
    runCatching { Json.parseToJsonElement(bodyText).collectMemberProfiles() }
        .getOrDefault(emptyList())
        .distinctBy { "${it.kind.name}:${it.id.value}" }

internal fun parseSharedAgents(bodyText: String): List<MemberProfile> =
    runCatching { Json.parseToJsonElement(bodyText).collectMemberProfiles(MemberKind.Agent) }
        .getOrDefault(emptyList())
        .map { it.copy(kind = MemberKind.Agent) }
        .distinctBy { "${it.kind.name}:${it.id.value}" }

internal fun mergeSharedMembers(
    members: List<MemberProfile>,
    agents: List<MemberProfile>
): List<MemberProfile> =
    (members + agents.map { it.copy(kind = MemberKind.Agent) })
        .distinctBy { "${it.kind.name}:${it.id.value}" }

private fun JsonElement.collectMemberProfiles(defaultKind: MemberKind? = null): List<MemberProfile> =
    when (this) {
        is JsonArray -> mapNotNull { it.asObjectOrNull()?.toSharedMemberProfile(defaultKind) }
        is JsonObject -> buildList {
            this@collectMemberProfiles.toSharedMemberProfile(defaultKind)?.let(::add)
            addAll(this@collectMemberProfiles.arrayProfiles("members"))
            addAll(this@collectMemberProfiles.arrayProfiles("items"))
            addAll(this@collectMemberProfiles.arrayProfiles("results"))
            addAll(this@collectMemberProfiles.arrayProfiles("humans", MemberKind.Human))
            addAll(this@collectMemberProfiles.arrayProfiles("users", MemberKind.Human))
            addAll(this@collectMemberProfiles.arrayProfiles("agents", MemberKind.Agent))
            val data = this@collectMemberProfiles["data"]
            if (data is JsonArray) addAll(data.collectMemberProfiles(defaultKind))
            if (data is JsonObject) addAll(data.collectMemberProfiles(defaultKind))
        }
        else -> emptyList()
    }

private fun JsonObject.arrayProfiles(name: String, defaultKind: MemberKind? = null): List<MemberProfile> =
    (this[name] as? JsonArray)?.collectMemberProfiles(defaultKind).orEmpty()

private fun JsonObject.toSharedMemberProfile(defaultKind: MemberKind? = null): MemberProfile? {
    val profile = firstObject("profile", "user", "account", "member", "agent") ?: this
    val kind = explicitMemberKind(defaultKind, profile)
    val id =
        profile.string("id", "memberId", "userId", "agentId", "_id")
            ?: string("id", "memberId", "userId", "agentId", "_id")
            ?: return null
    val displayName =
        profile.string("displayName", "name", "username", "email")
            ?: string("displayName", "name", "username", "email")
            ?: id
    val handle =
        profile.string("handle", "username", "email")
            ?: string("handle", "username", "email")
            ?: displayName
    val avatarWireValue =
        profile.avatarWireValue()
            ?: avatarWireValue()
    val agentStatus =
        normalizedAgentRuntimeStatus(
            activity = profile.string("agentStatus", "activity") ?: string("agentStatus", "activity"),
            lifecycleStatus = profile.string("status") ?: string("status")
        )
    return MemberProfile(
        id = MemberId(id),
        displayName = displayName,
        handle = handle,
        kind = kind,
        description = profile.string("description") ?: string("description"),
        roleDescription = profile.string("roleDescription", "role") ?: string("roleDescription", "role"),
        avatar = AvatarReference.fromWireValue(avatarWireValue),
        avatarLabel = displayName.toRaftAvatarLabel(),
        presence = (profile.string("presence", "availability") ?: string("presence", "availability")).toSharedPresence(),
        agentStatus = agentStatus,
        isExternal =
            isExternalAgent(
                external = profile.boolean("external") ?: boolean("external"),
                runtime = profile.string("runtime") ?: string("runtime")
            ),
        permissionBadges = profile.stringArray("permissionBadges") ?: stringArray("permissionBadges") ?: emptyList()
    )
}

private fun JsonObject.explicitMemberKind(defaultKind: MemberKind?, profile: JsonObject): MemberKind =
    (profile.string("kind", "memberKind", "type", "memberType") ?: string("kind", "memberKind", "type", "memberType"))
        ?.lowercase()
        ?.let { value ->
            when {
                "agent" in value || "bot" in value -> MemberKind.Agent
                "system" in value -> MemberKind.System
                "human" in value || "user" in value -> MemberKind.Human
                else -> null
            }
        }
        ?: (if (profile.boolean("isAgent") == true || boolean("isAgent") == true || firstObject("agent") != null) MemberKind.Agent else null)
        ?: defaultKind
        ?: MemberKind.Human

private fun String?.toSharedPresence(): Presence =
    when (this?.trim()?.lowercase()) {
        "active", "online", "available", "connected", "working", "thinking" -> Presence.Active
        "away", "idle", "busy" -> Presence.Away
        else -> Presence.Offline
    }

private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonObject.firstObject(vararg names: String): JsonObject? =
    names.firstNotNullOfOrNull { name -> this[name] as? JsonObject }

private fun JsonObject.string(vararg names: String): String? =
    names.firstNotNullOfOrNull { name -> (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() } }

private fun JsonObject.boolean(vararg names: String): Boolean? =
    names.firstNotNullOfOrNull { name -> (this[name] as? JsonPrimitive)?.booleanOrNull }

private fun JsonObject.stringArray(name: String): List<String>? =
    (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { value -> value.isNotBlank() } }

private fun JsonObject.avatarWireValue(): String? =
    string(
        "avatarWireValue",
        "avatar",
        "avatarUrl",
        "avatar_url",
        "imageUrl",
        "profileImageUrl",
        "profileAvatar",
        "pictureUrl"
    )
