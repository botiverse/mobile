package build.raft.app.members

import build.raft.app.resources.*
import build.raft.app.settings.RaftUpgradeGate
import build.raft.app.settings.openRaftUpgradeDialog
import build.raft.app.settings.openSettingsBillingPage
import build.raft.app.settings.settingsBillingOffersUpgrade
import build.raft.app.shell.openRaftPage
import build.raft.app.shell.raftShellStoreScope
import build.raft.app.shell.rememberRaftLinkNavigator
import build.raft.app.shell.toRaftAdaptivePaneEventOrNull
import build.raft.app.thread.dispatch
import build.raft.app.toCreateAgentRequest
import build.raft.app.userFacingMessage
import androidx.compose.runtime.setValue
import build.raft.app.base.Utils
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.SharedAgentDetailIntent
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.RaftAgentsNetworkApi
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.data.RaftComputersNetworkApi
import build.raft.app.data.SharedComputersIntent
import build.raft.app.data.SharedComputersPickerSlice
import build.raft.app.data.SharedComputersStore
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.kuiklyBridgeCallContext
import build.raft.app.model.AgentStatus
import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.openSettingsComputersPage
import build.raft.app.optString
import build.raft.app.raftAvatarDiagnosticsHash
import build.raft.app.raftScopedAgentStatusResolver
import build.raft.app.reduceRaftAdaptivePane
import build.raft.app.rememberCreatedAgent
import build.raft.app.rememberRaftCreatedAgentReceipt
import build.raft.app.rememberShellRuntimeAccountUsageAccess
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat24Hour
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IPagerEventObserver
import build.raft.app.compat.pager.Pager
import build.raft.app.createdAgentLanding
import build.raft.app.currentRaftEffectiveDateTimeContext
import kotlinx.coroutines.flow.flowOf
import build.raft.app.data.map
import build.raft.app.get
import build.raft.app.inCreatingServer
import build.raft.app.localizedErrorPresentationStrings
import build.raft.app.memberDetailAccessMode
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import androidx.compose.ui.semantics.selected
import build.raft.app.AuthenticatedProductPager
import build.raft.app.AuthenticatedProductRoute
import build.raft.app.CreatedAgentLanding
import build.raft.app.MemberDetailIdentity
import build.raft.app.RaftAdaptivePaneLayout
import build.raft.app.RaftAdaptivePaneState
import build.raft.app.RaftExternalAgentCreateHost
import build.raft.app.SharedCreateAgentDialogSubmit

private const val MEMBERS_PAGE_DATA_EVENT = "slockPageData"

@Page(MEMBERS_SMOKE_PAGE, supportInLocal = true)
internal class ComposeMembersSmokePage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent {
            MembersRouteContent(
                payload = membersSmokePayload,
                onAddHuman = {},
                onOpenMemberDetail = {}
            )
        }
    }
}

@Page(MEMBERS_PAGE, supportInLocal = true)
internal class ComposeMembersPage : AuthenticatedProductPager(AuthenticatedProductRoute.Members) {
    private var adaptivePane by mutableStateOf(RaftAdaptivePaneState())

    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { MembersPageContent(paneLayout = adaptivePane.layout) }
    }

    override fun onRaftAdaptivePaneChanged(eventData: JSONObject) {
        val event = eventData.toRaftAdaptivePaneEventOrNull() ?: return
        adaptivePane = reduceRaftAdaptivePane(adaptivePane, event)
    }
}

@Page(MEMBER_DETAIL_PAGE, supportInLocal = true)
internal class ComposeMemberDetailPage : AuthenticatedProductPager(AuthenticatedProductRoute.MemberDetail) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { MemberDetailPageContent(raftPageVisibleResumeTick, pageVisible = raftPageVisible) }
    }
}

@Serializable
data class RaftMembersPayload(
    val instanceId: String = "",
    val title: String = "Members",
    val labels: RaftMembersLabels = RaftMembersLabels(),
    val members: List<MemberProfile> = emptyList(),
    val initialAgentTab: String? = null,
    val localOffsetMinutes: Int? = null,
    val todayEpochDay: Long? = null,
    val nowEpochMillis: Long? = null,
    val timeFormat: String? = null,
    val agentPanelTabOrder: List<String> = emptyList(),
    /**
     * Whether the active session can create agents. Driven by the host from
     * server capabilities (mirrors React's `useServerPermissions().capabilities`).
     * When false, the agents "+" is hidden. When null/true, the "+" is rendered.
     * Field was added by #43 to address QA-2 from #42 review.
     */
    val canCreateAgents: Boolean = true,
    /**
     * Whether the active session can invite human members. Driven by the host
     * from server capabilities. When false, the humans "+" is hidden and the
     * invite dialog cannot be opened from this page.
     */
    val canInviteHumans: Boolean = true,
    /** Server-wide human directory visibility; conversation member lists are separate. */
    val showHumanDirectory: Boolean = true,
    /**
     * Guests are listed among the Humans for server owners and admins only (web MembersPage
     * `showGuests`); false until the host knows the reader is one ([membersShowGuests]).
     */
    val showGuests: Boolean = false
)

internal data class RaftMemberDetailPayload(
    val instanceId: String = "",
    val identity: MemberDetailIdentity? = null,
    val initialAgentTab: String? = null,
    val visualMode: String? = null,
    val localOffsetMinutes: Int? = null,
    val todayEpochDay: Long? = null,
    val nowEpochMillis: Long? = null,
    val timeFormat: String? = null,
    val agentPanelTabOrder: List<String> = emptyList(),
    /** A remote member's server label from the opener's joint roster (never authority). */
    val sourceServerName: String? = null,
    /**
     * The channel (a thread's parent) the person's profile was opened from: the profile read names
     * their servers in it (web profileStore `profileChannelId`, botiverse/slock #14009). Identity
     * of the opener only; the servers come from the read.
     */
    val profileChannelId: String? = null,
    /**
     * Opened by a Members row's Edit agent (web `requestEdit`): the agent's Profile tab with its
     * Edit runtime config dialog open, as soon as it can open.
     */
    val openRuntimeEditor: Boolean = false,
)

@Serializable
data class RaftMembersLabels(
    val agents: String = "AGENTS",
    val humans: String = "HUMANS",
    val addHuman: String = "Add human"
)

internal fun canonicalMembersDisplayText(
    value: String,
    canonicalDefault: String,
    localizedDefault: String
): String = if (value == canonicalDefault) localizedDefault else value

private val membersSmokePayload = RaftMembersPayload(
    title = "Members",
    members = listOf(
        MemberProfile(
            id = build.raft.app.model.MemberId("cindy"),
            displayName = "Cindy",
            handle = "Onboarding Assistant",
            kind = MemberKind.Agent,
            description = "Onboarding Assistant",
            roleDescription = "agent",
            avatar = build.raft.app.model.AvatarReference.Pixel("heart"),
            avatarLabel = "C",
            presence = build.raft.app.model.Presence.Active,
            agentStatus = build.raft.app.model.AgentStatus.Online,
            permissionBadges = listOf("computer:MacBook Pro")
        ),
        MemberProfile(
            id = build.raft.app.model.MemberId("builder"),
            displayName = "Builder",
            handle = "@builder",
            kind = MemberKind.Agent,
            description = "File and repo automation",
            avatar = build.raft.app.model.AvatarReference.Pixel("robot"),
            avatarLabel = "B",
            presence = build.raft.app.model.Presence.Away,
            agentStatus = build.raft.app.model.AgentStatus.Working,
            permissionBadges = listOf("computer:MacBook Pro")
        ),
        MemberProfile(
            id = build.raft.app.model.MemberId("artin"),
            displayName = "Artin",
            handle = "@artin",
            kind = MemberKind.Human,
            description = "Owner",
            avatarLabel = "A",
            presence = build.raft.app.model.Presence.Active
        ),
        MemberProfile(
            id = build.raft.app.model.MemberId("jiacheng"),
            displayName = "Jiacheng",
            handle = "@jiacheng",
            kind = MemberKind.Human,
            description = "Android",
            avatarLabel = "J",
            presence = build.raft.app.model.Presence.Away
        )
    )
)

@Composable
internal fun MembersPageContent(
    paneLayout: RaftAdaptivePaneLayout = RaftAdaptivePaneLayout.Compact,
) {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val errorStrings = localizedErrorPresentationStrings()
    val storeScope = remember(pager.pageData.params.toString(), pager.pagerId) {
        pager.pageData.params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
    }
    var payloadText by remember { mutableStateOf(pager.pageData.params.optString("payload")) }
    var instanceId by remember { mutableStateOf(pager.pageData.params.optString("instanceId")) }
    DisposableEffect(pager) {
        val observer =
            object : IPagerEventObserver {
                override fun onPagerEvent(pagerEvent: String, eventData: JSONObject) {
                    if (pagerEvent != MEMBERS_PAGE_DATA_EVENT) return
                    eventData.optString("payload").takeIf { it.isNotBlank() }?.let {
                        payloadText = it
                    }
                    eventData.optString("instanceId").takeIf { it.isNotBlank() }?.let {
                        instanceId = it
                    }
                }
            }
        pager.addPagerEventObserver(observer)
        onDispose { pager.removePagerEventObserver(observer) }
    }
    val payload = remember(payloadText, instanceId) {
        val decodedPayload = payloadText.toRaftMembersPayload()
        if (instanceId.isBlank() || decodedPayload.instanceId == instanceId) {
            decodedPayload
        } else {
            decodedPayload.copy(instanceId = instanceId)
        }
    }
    val bridgeCallScope = rememberCoroutineScope()
    val linkNavigator = rememberRaftLinkNavigator(pager, bridge)
    val copiedMessage = stringResource(Res.string.common_copied)
    val settingsCapabilities =
        remember(bridge, bridgeCallScope, linkNavigator, copiedMessage) {
            BridgeSettingsPlatformCapabilities(
                bridge = bridge,
                copiedMessage = copiedMessage,
                bridgeCallContext = bridgeCallScope.kuiklyBridgeCallContext(),
                openUrlHandler = { url -> linkNavigator.open(url) }
            )
        }

    // Build the KMP dataSource + coordinator directly (no bridge event round-trip).
    // Use the shared provider network client so request scheduling and transport swaps
    // stay centralized instead of being recreated per page.
    val sharedStores = remember { build.raft.app.data.RaftSharedStoreRegistry.get(bridge) }
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val networkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val serverMembersStore = remember(sharedStores, storeScope) { sharedStores.serverMembersStore(storeScope) }
    val agentDetailStore = remember(sharedStores, storeScope) { sharedStores.agentDetailStore(storeScope) }
    val createAgentFormStore = remember(sharedStores, storeScope) { sharedStores.createAgentFormStore(storeScope) }
    // Task #409: members-page agent machine groups persist their collapse
    // state in the same slock:sidebarCollapsed:<userId>:agent-machine-group:*
    // key family the web sidebar uses.
    val membersAgentGroupCollapseStore = remember {
        build.raft.app.data.KeyValueMembersAgentMachineGroupCollapseStore(
            build.raft.app.data.raftSharedPreferenceStore()
        )
    }
    val agentsApiClient = remember(networkClient) {
        build.raft.app.data.RaftAgentsApiClient(
            transport = build.raft.app.data.RaftAgentsNetworkApi(networkClient),
            diagnosticsLogger = { message -> bridge.log("members_agents_api $message") }
        )
    }
    val computersApiClient = remember(networkClient) {
        build.raft.app.data.RaftComputersApiClient(
            build.raft.app.data.RaftComputersNetworkApi(networkClient)
        )
    }
    val scope = rememberCoroutineScope()
    val openProfileFailed = stringResource(Res.string.member_error_open_profile)
    val computersStore = remember(computersApiClient, scope) {
        SharedComputersStore(
            computersApiClient = computersApiClient,
            scope = scope,
            logger = { message -> bridge.log("members_$message") },
            ownerProvider = {
                sharedStores.computersOwner(storeScope)
            }
        )
    }
    val createAgentCoordinator = remember(agentsApiClient, computersApiClient) {
        build.raft.app.data.SharedCreateAgentCoordinator(
            agentsApiClient = agentsApiClient,
            computersApiClient = computersApiClient,
            logger = { message -> bridge.log("members_create_agent $message") }
        )
    }
    val settingsRepository = remember(networkClient, authStore) {
        build.raft.app.data.RealSettingsRepository(
            sessionStore = authStore,
            networkClient = networkClient,
            logger = { message -> bridge.log("members_settings_repository $message") }
        )
    }
    val membersDataSource = remember(networkClient, authStore, settingsRepository, serverMembersStore) {
        build.raft.app.data.SharedMembersDataSource(
            memberRepository = build.raft.app.data.RealMemberRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = { message -> bridge.log("members_member_repository $message") }
            ),
            settingsRepository = settingsRepository,
            serverMembersStore = serverMembersStore
        )
    }
    // The plan's agent limit (web CreateAgentDialog `loadBilling`), read once per server; unread
    // billing (a 403, offline) falls back to the plan's limits and the server's agents.
    var createAgentBilling by remember { mutableStateOf<build.raft.app.data.CreateAgentBilling?>(null) }
    var createAgentServerPlan by remember { mutableStateOf<String?>(null) }
    var optimisticMembers by remember { mutableStateOf<List<MemberProfile>>(emptyList()) }
    // The agent just created here, staged until the dialog has closed: an external agent opens its
    // profile (setup guide), any other stays on the list with a "Created <name>" toast whose View
    // opens its DM (botiverse/slock #14482; cindyz #triage f4835750, superseding task #399's jump).
    var pendingCreatedAgent by remember { mutableStateOf<CreatedAgentLanding?>(null) }
    // Derive the active serverId from the auth session. Replaces the prior
    // `readCurrentServerId()` stub (fixed QA-3): machines load + plan limit
    // checks now use the real selected server.
    var activeServerId by remember { mutableStateOf<String?>(null) }
    var membersCurrentUserId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(authStore) {
        val session = authStore.readSession()
        activeServerId = session?.currentServerId
        membersCurrentUserId = session?.user?.id
    }
    val serverId = activeServerId ?: ""
    val agentPanelTabOrderStore = sharedStores.agentPanelTabOrderStore
    val agentPanelTabOrderState by agentPanelTabOrderStore.state.collectAsState()
    suspend fun loadAgentPanelTabOrderForMembersPage(): List<String>? =
        when (val result = settingsRepository.getServerSidebarOrder()) {
            is DataResult.Content -> result.data.agentPanelTabOrder
            is DataResult.Empty -> null
            is DataResult.Error -> {
                bridge.log("members_agent_tab_order_load_failed code=${result.error.code}")
                null
            }
            is DataResult.NoPermission -> {
                bridge.log("members_agent_tab_order_load_blocked code=${result.blocker.code}")
                null
            }
        }
    LaunchedEffect(agentPanelTabOrderStore, serverId, payload.agentPanelTabOrder) {
        agentPanelTabOrderStore.bindServer(
            serverId = serverId,
            initialOrder = payload.agentPanelTabOrder,
            source = "members_page_bind_payload",
            logger = { message -> bridge.log(message) }
        )
    }
    LaunchedEffect(
        settingsRepository,
        agentPanelTabOrderStore,
        serverId,
        agentPanelTabOrderState.serverId,
        agentPanelTabOrderState.loaded
    ) {
        if (serverId.trim().isBlank()) return@LaunchedEffect
        if (agentPanelTabOrderState.serverId == serverId && agentPanelTabOrderState.loaded) return@LaunchedEffect
        loadAgentPanelTabOrderForMembersPage()?.let { order ->
            agentPanelTabOrderStore.applyLoaded(
                serverId = serverId,
                order = order,
                source = "members_page_sidebar_order_get",
                logger = { message -> bridge.log(message) }
            )
        }
    }
    val serverMembersEntry by remember(serverMembersStore, serverId) {
        val cleanServerId = serverId.trim()
        if (cleanServerId.isBlank()) {
            flowOf(SharedServerMembersEntry(serverId = ""))
        } else {
            serverMembersStore.serverMembersEntryFlow(ServerId(cleanServerId))
        }
    }.collectAsState(
        // Data-layer plan step 3: first frame = the roster already cached (memory or durable seed),
        // never an empty list that the flow fills a frame later.
        remember(serverMembersStore, serverId) {
            serverId.trim().takeIf { it.isNotBlank() }
                ?.let { serverMembersStore.currentServerMembersEntry(ServerId(it)) }
                ?: SharedServerMembersEntry(serverId = serverId)
        }
    )
    // An agent archived from its profile leaves the roster in the store; a row this page added
    // itself (a just-created agent) must leave with it, or it stays until a reload.
    val archivedAgentIds by remember(serverMembersStore, serverId) {
        val cleanServerId = serverId.trim()
        if (cleanServerId.isBlank()) flowOf(emptySet()) else serverMembersStore.deletedAgentIdsFlow(ServerId(cleanServerId))
    }.collectAsState(
        remember(serverMembersStore, serverId) {
            serverId.trim().takeIf { it.isNotBlank() }?.let { serverMembersStore.deletedAgentIds(ServerId(it)) }.orEmpty()
        }
    )
    val localMembers =
        remember(serverMembersEntry.members, optimisticMembers, archivedAgentIds) {
            membersPageMergedMembers(serverMembersEntry.members, optimisticMembers, archivedAgentIds)
        }
    val currentAgentCount = localMembers.count { it.kind == MemberKind.Agent }
    LaunchedEffect(networkClient, serverId) {
        createAgentBilling = null
        createAgentServerPlan = null
        if (serverId.isBlank()) return@LaunchedEffect
        createAgentServerPlan =
            authStore.readSession()?.servers?.firstOrNull { it.id == serverId }?.toServerSummary()?.plan
        createAgentBilling = build.raft.app.data.loadCreateAgentBilling(networkClient)
    }
    val createAgentLimit =
        build.raft.app.data.createAgentLimit(createAgentBilling, createAgentServerPlan, currentAgentCount)
    // Task #148: which agent rows reach the list without a computer id, and from where (store vs
    // the row added right after creating an agent), so a device run can locate where the id is lost.
    val agentsWithoutComputer =
        remember(localMembers) {
            val storeIds = serverMembersEntry.members.mapTo(HashSet()) { it.id.value.trim() }
            localMembers
                .filter { it.kind == MemberKind.Agent && it.membersComputerId == null }
                .map { agent ->
                    val source = if (agent.id.value.trim() in storeIds) "store" else "optimistic"
                    "${agent.id.value.raftAvatarDiagnosticsHash()}:$source:badges=${agent.permissionBadges.size}"
                }
        }
    LaunchedEffect(agentsWithoutComputer, serverMembersEntry.status) {
        if (agentsWithoutComputer.isNotEmpty()) {
            bridge.log(
                "members_agents_without_computer status=${serverMembersEntry.status} " +
                    "count=${agentsWithoutComputer.size} rows=${agentsWithoutComputer.joinToString(",")}"
            )
        }
    }
    LaunchedEffect(serverId) {
        optimisticMembers = emptyList()
    }
    val agentStatusResolver =
        remember(serverMembersStore, serverId) {
            raftScopedAgentStatusResolver(serverMembersStore, serverId)
        }
    val memberAvatarResolver =
        remember(serverMembersStore, serverId) {
            raftMemberAvatarResolver(serverMembersStore, serverId)
        }
    val membersRefreshing = serverMembersEntry.status == build.raft.app.data.SharedServerMembersLoadStatus.InFlight
    val refreshMembers: () -> Unit = remember(serverMembersStore, serverId) {
        {
            val cleanServerId = serverId.trim()
            if (cleanServerId.isNotBlank()) {
                scope.launch {
                    serverMembersStore.refreshMembers(
                        ServerId(cleanServerId),
                        DataRequestPriority.Foreground,
                        force = true
                    )

                }
            }
        }
    }
    LaunchedEffect(serverMembersStore, serverId) {
        val cleanServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        serverMembersStore.refreshMembers(ServerId(cleanServerId), DataRequestPriority.Background)
    }
    val computersSlice by computersStore
        .computerPickerSliceFlow(serverId)
        .collectAsState(SharedComputersPickerSlice())
    LaunchedEffect(computersStore, serverId) {
        val cleanServerId = serverId.trim().takeIf { it.isNotBlank() }
        computersStore.reconcileOwner()
        if (cleanServerId != null) {
            computersStore.ensureComputerPickerForServer(
                serverId = cleanServerId,
                priority = DataRequestPriority.Visible
            )
        }
    }
    // The server runtime catalog names runtimes on this page (subtitles, chips, the switch picker):
    // the cached answer at once, the server's in the background.
    LaunchedEffect(createAgentFormStore, serverId) {
        if (serverId.isBlank()) return@LaunchedEffect
        createAgentFormStore.cachedServerRuntimeCatalog(serverId)
        createAgentFormStore.loadServerRuntimeCatalog(serverId, DataRequestPriority.Background)
    }
    // [land] false: the external agent's two-step dialog stays open on its setup and lands it on Done.
    val createAgentProfile: suspend (SharedCreateAgentDialogSubmit, Boolean) -> DataResult<MemberProfile> =
        remember(createAgentCoordinator, createAgentFormStore, serverId) {
            { submit, land ->
                val result =
                    createAgentCoordinator
                        .createAgent(submit.toCreateAgentRequest())
                var created: MemberProfile? = null
                if (result is DataResult.Content) {
                    createAgentFormStore.rememberCreatedAgent(serverId, submit)
                    val profile = createdAgentMemberProfile(result.data, submit, computersSlice.machines)
                    created = profile
                    optimisticMembers = optimisticMembers + profile
                    // Hand the created agent to the landing below ([CreatedAgentLanding]).
                    //
                    // Ordering matters here and must not be changed: this runs inside the dialog's
                    // submit coroutine, which the caller cancels by clearing activeCreateAgentMode
                    // AFTER submit() returns. Staging the landing inside submit — before that
                    // teardown — is what makes the write survive (the #3140 bug: the dismissal
                    // came first and cancelled it).
                    if (land) pendingCreatedAgent = createdAgentLanding(submit, profile.inCreatingServer(serverId))
                    serverId.trim().takeIf { it.isNotBlank() }?.let { cleanServerId ->
                        serverMembersStore.refreshMembers(
                            ServerId(cleanServerId),
                            DataRequestPriority.Background,
                            force = true
                        )
                    }
                }
                when (result) {
                    is DataResult.Content -> DataResult.Content(created!!.inCreatingServer(serverId))
                    is DataResult.Empty -> DataResult.Empty(result.reason)
                    is DataResult.Error -> result
                    is DataResult.NoPermission -> result
                }
            }
        }
    val createAgentSubmit: (suspend (SharedCreateAgentDialogSubmit) -> DataResult<Unit>)? =
        remember(createAgentProfile) {
            { submit -> createAgentProfile(submit, true).map { } }
        }
    val createExternalAgentHost =
        remember(createAgentProfile, agentsApiClient, serverId) {
            RaftExternalAgentCreateHost(
                agentsApiClient = agentsApiClient,
                serverUrl = sharedStores.apiEnvironmentStore.current().webUrl,
                create = { submit -> createAgentProfile(submit, false) },
                onFinished = { profile -> pendingCreatedAgent = CreatedAgentLanding.SetupGuide(profile) },
                onDiscarded = { profile ->
                    optimisticMembers = optimisticMembers.filterNot { it.id == profile.id }
                    serverId.trim().takeIf { it.isNotBlank() }?.let { cleanServerId ->
                        scope.launch {
                            serverMembersStore.refreshMembers(ServerId(cleanServerId), DataRequestPriority.Background, force = true)
                        }
                    }
                },
            )
        }
    // MEMBERS_COMPUTERS_ERROR_RETRY_WIRING_START
    val loadMachines: () -> Unit = remember(computersStore, serverId) {
        {
            if (serverId.isNotBlank()) {
                computersStore.dispatch(
                    SharedComputersIntent.EnsureComputerPicker(
                        serverId = serverId,
                        priority = DataRequestPriority.Visible
                    )
                )
            }
        }
    }
    val retryMachines: () -> Unit = remember(computersStore, serverId) {
        {
            if (serverId.isNotBlank()) {
                computersStore.dispatch(
                    SharedComputersIntent.RefreshComputerPicker(
                        serverId = serverId,
                        priority = DataRequestPriority.Visible
                    )
                )
            }
        }
    }
    // Humans "+" invite: web `InviteHumanDialog.tsx` (email / link tabs, link limits,
    // update / revoke), rendered by the shared dialog host.
    val inviteHumanDialog: (@Composable (() -> Unit) -> Unit)? =
        if (payload.canInviteHumans && serverId.isNotBlank()) {
            { onDismiss ->
                SharedServerInviteHumanDialogHost(
                    serverId = serverId,
                    membersDataSource = membersDataSource,
                    loadWebBaseUrl = {
                        (settingsCapabilities.loadDeveloper() as? DataResult.Content)?.data?.webUrl
                    },
                    copyToPasteboard = bridge::copyToPasteboard,
                    onDismiss = onDismiss,
                    // Web InviteHumanDialog `seat_limit_invite`; iOS: the error alone (no add-seats entry).
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

    val agentPanelTabOrder =
        if (agentPanelTabOrderState.serverId == serverId) {
            agentPanelTabOrderState.order
        } else {
            payload.agentPanelTabOrder
        }

    val membersPayload = payload.forMembersRoute(localMembers, agentPanelTabOrder, serverMembersEntry.server)

    // task #399 (Hands 3a56004f): the Members page has its own create path (createAgentSubmit
    // above), separate from ChannelMembersCreateAgentDialog used by the thread member panel.
    // #3132/#3140 only fixed the thread panel, so creating here still landed on the list.
    // Defined here so both the row tap and the post-create path share one navigation.
    fun openMemberDetail(member: MemberProfile, openRuntimeEditor: Boolean = false) {
        scope.launch {
            val identity = MemberDetailIdentity(member.id, member.kind, member.serverId)
            val accessMode = identity.memberDetailAccessMode(serverId)
            val launchPayload =
                if (
                    member.kind == MemberKind.Agent &&
                    accessMode.allowsPrivateAgentSurfaces &&
                    (agentPanelTabOrderState.serverId != serverId || !agentPanelTabOrderState.loaded)
                ) {
                    loadAgentPanelTabOrderForMembersPage()
                        ?.also { order ->
                            agentPanelTabOrderStore.applyLoaded(
                                serverId = serverId,
                                order = order,
                                source = "members_detail_open_preload",
                                logger = { message -> bridge.log(message) }
                            )
                        }
                        ?.let { order -> membersPayload.copy(agentPanelTabOrder = order) }
                        ?: membersPayload
                } else {
                    membersPayload
                }
            val pageData = launchPayload.toMemberDetailPageData(identity, openRuntimeEditor)
            if (pageData == null) {
                bridge.toast(openProfileFailed)
                bridge.log("members_detail_open_skip reason=missing_member_id instanceId=${payload.instanceId}")
            } else {
                val seedMember =
                    member.copy(
                        serverId = member.serverId ?: serverId.trim().takeIf { it.isNotBlank() }?.let(::ServerId)
                    )
                agentDetailStore.dispatch(SharedAgentDetailIntent.SeedDetailFromMember(seedMember))
                pager.openRaftPage(
                    pageName = MEMBER_DETAIL_PAGE,
                    pageData = pageData,
                    storeScopeOverride = storeScope,
                    onMissingScope = {
                        bridge.toast(openProfileFailed)
                        bridge.log("members_detail_open_skip reason=missing_shell_scope instanceId=${payload.instanceId}")
                    }
                )
            }
        }
    }

    val effectiveTimeContext = currentRaftEffectiveDateTimeContext(payload.toRaftTimeContextOrNull())
    val createdAgentReceipt =
        rememberRaftCreatedAgentReceipt(
            pager = pager,
            bridge = bridge,
            storeScope = storeScope,
            openSetupGuide = { openMemberDetail(it) },
            timeContext = effectiveTimeContext,
        )
    // Lands a just-created agent once the dialog has closed (no toast with an action over a modal,
    // Raft DS › Toast). Runs after localMembers merged the optimistic row, so an external agent's
    // profile is addressable. Cleared immediately so recomposition does not land it again.
    LaunchedEffect(pendingCreatedAgent) {
        val landing = pendingCreatedAgent ?: return@LaunchedEffect
        pendingCreatedAgent = null
        bridge.log("members_create_agent_landing memberId=${landing.agent.id.value} instanceId=${payload.instanceId}")
        createdAgentReceipt.land(landing)
    }

    val membersRuntimeUsageAccess = rememberShellRuntimeAccountUsageAccess()
    CompositionLocalProvider(LocalMembersRuntimeAccountUsageAccess provides membersRuntimeUsageAccess) {
        MembersRouteContent(
        payload = membersPayload,
        refreshing = membersRefreshing,
        onBack = { bridge.closePage() },
        onRefresh = refreshMembers,
        paneLayout = paneLayout,
        onAddHuman = { /* humans "+" wired via addHumanSubmit below */ },
        onOpenMemberDetail = { openMemberDetail(it) },
        // Web MemberActions: the rows' menus and Edit agent.
        rowActions = rememberMembersRowActions(
            pager = pager,
            bridge = bridge,
            storeScope = storeScope,
            viewerRole = serverMembersEntry.server?.role,
            currentUserId = membersCurrentUserId,
            currentServerId = serverId,
            onEditAgent = { openMemberDetail(it, openRuntimeEditor = true) },
            timeContext = effectiveTimeContext,
        ),
        agentStatusResolver = agentStatusResolver,
        memberAvatarResolver = memberAvatarResolver,
        canCreateAgents = payload.canCreateAgents,
        createAgent = MembersCreateAgentInputs(
            createAgentSubmit = createAgentSubmit,
            createExternalAgent = createExternalAgentHost,
            createAgentMachines = computersSlice.machines,
            createAgentMachinesLoading = computersSlice.initialLoadInFlight,
            createAgentMachinesErrorMessage = computersSlice.initialLoadError?.userFacingMessage(errorStrings),
            createAgentCurrentAgentCount = createAgentLimit.usage,
            createAgentMaxAgents = createAgentLimit.limit,
            createAgentLimitPlanLabel = createAgentLimit.planLabel,
            createAgentSeatLimit = createAgentLimit.seatState,
            createAgentLoadMachines = loadMachines,
            createAgentRetryMachines = retryMachines,
            createAgentFormStore = createAgentFormStore,
            createAgentServerId = serverId,
            createAgentTakenAgentNames = localMembers.takenAgentNames(),
            createAgentViewPlan =
                if (settingsBillingOffersUpgrade()) null else ({ pager.openSettingsBillingPage(storeScope) }),
            // Web CreateAgentDialog `seat_limit_agent_create`: Free → upgrade, Pro → add seats.
            createAgentUpgradeWithName =
                if (settingsBillingOffersUpgrade()) {
                    { agentName ->
                        pager.openRaftUpgradeDialog(
                            RaftUpgradeGate.SeatLimitAgentCreate,
                            storeScope,
                            knownBillingPresent = createAgentBilling != null,
                            knownPricing = createAgentBilling?.pricing,
                            seatRequestPurpose =
                                agentName.trim().takeIf { it.isNotEmpty() }?.let { build.raft.app.data.SeatRequestPurpose.AgentCreate(it) },
                        )
                    }
                } else {
                    null
                },
            createAgentAddComputer = { pager.openSettingsComputersPage(storeScope, openAddComputer = true) },
        ),
        inviteHuman = MembersInviteHumanInputs(inviteHumanDialog = inviteHumanDialog),
        // Task #409: persist per-machine collapse under the session user id.
        currentUserId = membersCurrentUserId,
        agentGroupCollapseStore = membersAgentGroupCollapseStore,
        // #89: pass agentsApiClient so Permissions tab can call
        // `agentScopes` / `updateAgentScopes` / `resetAgentScopes` directly.
        agentsApiClient = agentsApiClient
    )
    }
    // MEMBERS_COMPUTERS_ERROR_RETRY_WIRING_END
}

private fun RaftMembersPayload.toRaftTimeContextOrNull(): RaftTimeContext? {
    // Only pin a static payload context when the caller explicitly pinned a clock (visual fixtures / tests);
    // live routes that merely pass todayEpochDay follow the enclosing live DI time provider.
    if (nowEpochMillis == null) return null
    val offset = localOffsetMinutes ?: 0
    return RaftTimeContext(
        localOffsetMinutes = offset,
        todayEpochDay = todayEpochDay,
        nowEpochMillis = nowEpochMillis,
        timeFormat = timeFormat ?: RaftTimeFormat24Hour,
    )
}
