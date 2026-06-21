package ai.slock.compose

import ai.slock.compose.base.Utils
import ai.slock.compose.components.SlockBrutalActionButton
import ai.slock.compose.components.SlockBrutalIconButton
import ai.slock.compose.components.SlockBrutalModalLayer
import ai.slock.compose.components.SlockBrutalTag
import ai.slock.compose.components.SlockBrutalTextField
import ai.slock.compose.components.SlockModalShellModel
import ai.slock.compose.PixelAvatarPickerKeys
import ai.slock.compose.data.AttachmentUploadFile
import ai.slock.compose.data.AgentActivityEntry
import ai.slock.compose.data.AgentCreatedSummary
import ai.slock.compose.data.AgentDetailSnapshot
import ai.slock.compose.data.AgentDetailSummary
import ai.slock.compose.data.AgentDmSummary
import ai.slock.compose.data.AgentLifecycleActionResult
import ai.slock.compose.data.AgentReminderSummary
import ai.slock.compose.data.AgentResetMode
import ai.slock.compose.data.AgentSkillGroup
import ai.slock.compose.data.AgentSkillPathGroup
import ai.slock.compose.data.AgentSkillSummary
import ai.slock.compose.data.AgentSkillsSnapshot
import ai.slock.compose.data.AgentWorkspaceChildrenSnapshot
import ai.slock.compose.data.AgentWorkspaceFilePreviewSnapshot
import ai.slock.compose.data.AgentWorkspaceTreeSnapshot
import ai.slock.compose.data.AgentProfileUpdateRequest
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.EmptyReason
import ai.slock.compose.data.KuiklySlockNetworkClient
import ai.slock.compose.data.ScreenError
import ai.slock.compose.data.SlockAgentsApiClient
import ai.slock.compose.data.SlockAgentsNetworkApi
import ai.slock.compose.data.SlockComputersApiClient
import ai.slock.compose.data.SlockComputersNetworkApi
import ai.slock.compose.markdown.SlockMarkdownBody
import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.AvatarReference
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Presence
import ai.slock.compose.model.SlockWorkspaceFileKind
import ai.slock.compose.model.SlockWorkspaceFilePreview
import ai.slock.compose.model.SlockWorkspaceNode
import ai.slock.compose.model.SlockTimeContext
import ai.slock.compose.model.SlockTimeFormat24Hour
import ai.slock.compose.model.avatarWireValueForAvatarSlot
import ai.slock.compose.model.formatSlockTimestampClockWithSeconds
import ai.slock.compose.model.parseSlockTimestampEpochMillisOrNull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.lazy.LazyListState
import com.tencent.kuikly.compose.foundation.lazy.LazyRow
import com.tencent.kuikly.compose.foundation.lazy.rememberLazyListState
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.pager.Pager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

const val MEMBERS_PAGE = "compose_members"
const val MEMBERS_SMOKE_PAGE = "compose_members_smoke"
private const val MEMBERS_AGENT_DETAIL_VISUAL_PAGE = "compose_members_agent_detail_visual"
private const val MEMBERS_PAGE_DATA_EVENT = "slockPageData"

private val MembersPageInset = SlockSpacing.Md
private val MemberAvatarSize = 34.dp
private val MemberRowMinHeight = 48.dp
private val MemberStatusDotSize = 10.dp
private val MembersActionSize = 32.dp

internal enum class MembersAgentDetailTab(
    val label: String,
    val iconName: SlockLucideIconName,
    val privateSurface: Boolean = true
) {
    Profile("Profile", SlockLucideIconName.Bot, privateSurface = false),
    Permissions("Permissions", SlockLucideIconName.ShieldCheck),
    Dms("Agent DMs", SlockLucideIconName.MessageSquare),
    Reminders("Reminders", SlockLucideIconName.BellRing),
    Workspace("Workspace", SlockLucideIconName.FolderOpen),
    Apps("Apps", SlockLucideIconName.Link2),
    Activity("Activity", SlockLucideIconName.Activity)
}

internal fun membersVisibleAgentTabs(canViewPrivateSurfaces: Boolean, deleted: Boolean): List<MembersAgentDetailTab> =
    MembersAgentDetailTab.entries.filter { tab ->
        when {
            tab == MembersAgentDetailTab.Profile -> true
            tab == MembersAgentDetailTab.Permissions && deleted -> false
            else -> canViewPrivateSurfaces
        }
    }

private sealed interface MembersAgentDetailLoadState {
    data object Idle : MembersAgentDetailLoadState
    data object Loading : MembersAgentDetailLoadState
    data class Content(val detail: AgentDetailSnapshot) : MembersAgentDetailLoadState
    data class NoPermission(val code: String) : MembersAgentDetailLoadState
    data class Error(val code: String) : MembersAgentDetailLoadState
    data object Empty : MembersAgentDetailLoadState
}

private sealed interface MembersAgentWorkspaceTreeState {
    data object Idle : MembersAgentWorkspaceTreeState
    data object Loading : MembersAgentWorkspaceTreeState
    data class Content(val snapshot: AgentWorkspaceTreeSnapshot) : MembersAgentWorkspaceTreeState
    data class NoPermission(val code: String) : MembersAgentWorkspaceTreeState
    data class Error(val code: String) : MembersAgentWorkspaceTreeState
    data object Empty : MembersAgentWorkspaceTreeState
}

private sealed interface MembersAgentWorkspaceFileState {
    data object None : MembersAgentWorkspaceFileState
    data class Loading(val path: String) : MembersAgentWorkspaceFileState
    data class Content(val snapshot: AgentWorkspaceFilePreviewSnapshot) : MembersAgentWorkspaceFileState
    data class Error(val path: String, val code: String) : MembersAgentWorkspaceFileState
    data class NoPermission(val path: String, val code: String) : MembersAgentWorkspaceFileState
    data class Empty(val path: String) : MembersAgentWorkspaceFileState
}

private enum class MembersWorkspaceViewMode {
    Raw,
    Preview
}

internal enum class MembersAgentLifecycleAction(
    val actionKey: String,
    val label: String,
    val iconName: SlockLucideIconName,
    val successLabel: String,
    val destructive: Boolean = false
) {
    Start("start", "Start", SlockLucideIconName.Play, "Started"),
    Stop("stop", "Stop", SlockLucideIconName.Square, "Stopped"),
    Restart("restart", "Restart", SlockLucideIconName.RotateCw, "Restarted"),
    ResetSession("reset_session", "Reset session", SlockLucideIconName.RefreshCw, "Session reset"),
    ResetFull("reset_full", "Full reset", SlockLucideIconName.Eraser, "Full reset"),
    Delete("delete", "Delete Agent", SlockLucideIconName.Trash2, "Deleted", destructive = true),
    UnassignMachine("assign_machine", "Unassign machine", SlockLucideIconName.MonitorX, "Machine unassigned");

    val resetMode: AgentResetMode?
        get() =
            when (this) {
                Restart -> AgentResetMode.Restart
                ResetSession -> AgentResetMode.Session
                ResetFull -> AgentResetMode.Full
                Start,
                Stop,
                Delete,
                UnassignMachine -> null
            }

    val requiresConfirmation: Boolean
        get() = this != Start
}

private data class MembersAgentActionState(
    val action: MembersAgentLifecycleAction,
    val inProgress: Boolean,
    val errorCode: String? = null,
    val successLabel: String? = null
)

private data class MembersAgentDiagnosticActionState(
    val actionKey: String,
    val inProgress: Boolean,
    val errorCode: String? = null,
    val successLabel: String? = null
)

internal fun membersPrimaryLifecycleAction(status: AgentStatus?, deleted: Boolean): MembersAgentLifecycleAction? =
    if (deleted) {
        null
    } else {
        when (status) {
            AgentStatus.Offline,
            AgentStatus.Disconnected,
            AgentStatus.Error,
            null -> MembersAgentLifecycleAction.Start
            AgentStatus.Online,
            AgentStatus.Working,
            AgentStatus.Thinking,
            AgentStatus.Idle -> MembersAgentLifecycleAction.Stop
        }
    }

@Page(MEMBERS_SMOKE_PAGE, supportInLocal = true)
internal class ComposeMembersSmokePage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent {
            MembersRouteContent(
                payload = membersSmokePayload,
                onAddHuman = {},
                onOpenDirectMessage = {},
                onCopyMemberId = {}
            )
        }
    }
}

@Page(MEMBERS_PAGE, supportInLocal = true)
internal class ComposeMembersPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { MembersPageContent() }
    }
}

@Page(MEMBERS_AGENT_DETAIL_VISUAL_PAGE, supportInLocal = true)
internal class ComposeMembersAgentDetailVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { MembersAgentDetailVisualContent() }
    }
}

@Serializable
data class KuiklyMembersPayload(
    val instanceId: String = "",
    val title: String = "Members",
    val labels: KuiklyMembersLabels = KuiklyMembersLabels(),
    val members: List<MemberProfile> = emptyList(),
    val localOffsetMinutes: Int = 0,
    val todayEpochDay: Long? = null,
    val nowEpochMillis: Long? = null,
    val timeFormat: String = SlockTimeFormat24Hour,
    /**
     * Whether the active session can create agents. Driven by the host from
     * server capabilities (mirrors React's `useServerPermissions().capabilities`).
     * When false, the agents "+" is hidden. When null/true, the "+" is rendered.
     * Field was added by #43 to address QA-2 from #42 review.
     */
    val canCreateAgents: Boolean = true
)

@Serializable
data class KuiklyMembersLabels(
    val agents: String = "AGENTS",
    val humans: String = "HUMANS",
    val addHuman: String = "Add human"
)

private val membersSmokePayload = KuiklyMembersPayload(
    title = "Members",
    members = listOf(
        MemberProfile(
            id = ai.slock.compose.model.MemberId("cindy"),
            displayName = "Cindy",
            handle = "Onboarding Assistant",
            kind = MemberKind.Agent,
            description = "Onboarding Assistant",
            roleDescription = "agent",
            avatar = ai.slock.compose.model.AvatarReference.Pixel("heart"),
            avatarLabel = "C",
            presence = ai.slock.compose.model.Presence.Active,
            agentStatus = ai.slock.compose.model.AgentStatus.Online,
            permissionBadges = listOf("computer:MacBook Pro")
        ),
        MemberProfile(
            id = ai.slock.compose.model.MemberId("builder"),
            displayName = "Builder",
            handle = "@builder",
            kind = MemberKind.Agent,
            description = "File and repo automation",
            avatar = ai.slock.compose.model.AvatarReference.Pixel("robot"),
            avatarLabel = "B",
            presence = ai.slock.compose.model.Presence.Away,
            agentStatus = ai.slock.compose.model.AgentStatus.Working,
            permissionBadges = listOf("computer:MacBook Pro")
        ),
        MemberProfile(
            id = ai.slock.compose.model.MemberId("artin"),
            displayName = "Artin",
            handle = "@artin",
            kind = MemberKind.Human,
            description = "Owner",
            avatarLabel = "A",
            presence = ai.slock.compose.model.Presence.Active
        ),
        MemberProfile(
            id = ai.slock.compose.model.MemberId("jiacheng"),
            displayName = "Jiacheng",
            handle = "@jiacheng",
            kind = MemberKind.Human,
            description = "Android",
            avatarLabel = "J",
            presence = ai.slock.compose.model.Presence.Away
        )
    )
)

private val membersAgentDetailVisualMember = MemberProfile(
    id = MemberId("agent-product-ux"),
    displayName = "Product UX Designer",
    handle = "@Product-UX-Designer",
    kind = MemberKind.Agent,
    description = "Turns product feedback, screenshots, and release notes into actionable UX specs.",
    roleDescription = "Product / UX agent",
    avatar = AvatarReference.Pixel("paint"),
    avatarLabel = "P",
    presence = Presence.Active,
    agentStatus = AgentStatus.Working,
    permissionBadges = listOf("computer:MacBook Pro", "runtime:Codex")
)

private val membersAgentDetailVisualSnapshot = AgentDetailSnapshot(
    agentId = membersAgentDetailVisualMember.id,
    summary = AgentDetailSummary(
        id = membersAgentDetailVisualMember.id.value,
        name = "Product-UX-Designer",
        displayName = membersAgentDetailVisualMember.displayName,
        status = "working",
        runtime = "codex",
        model = "gpt-5-codex",
        reasoning = "medium",
        executionMode = "byoc",
        machineId = "computer-mbp",
        machineName = "Jiachengs-MacBook-Pro",
        daemonVersion = "0.61.1",
        creatorId = "artin",
        creatorType = "user",
        envVarNames = listOf("RAFT_PROFILE", "SLOCK_VISUAL_PROVIDER"),
        createdAgents = listOf(
            AgentCreatedSummary(
                id = "agent-visual-qa",
                name = "visual-qa",
                displayName = "Visual QA",
                runtime = "codex",
                status = "idle"
            )
        ),
        activity = "working",
        activityDetail = "Reviewing Agent detail fixtures"
    ),
    directMessages = listOf(
        AgentDmSummary(
            id = "dm-agent-product-ux-artin",
            peerId = "artin",
            peerName = "artin",
            lastMessageAt = "2026-06-19T12:24:00Z",
            lastMessagePreview = "201继续推进"
        )
    ),
    reminders = listOf(
        AgentReminderSummary(
            id = "reminder-product-ux-memory",
            title = "Roll 1-6; if 3, tidy Product-UX memory/files",
            fireAt = "2026-06-19T18:30:00Z",
            recurrenceDescription = "daily"
        )
    ),
    activity = listOf(
        AgentActivityEntry(
            id = "activity-agent-detail-fixture",
            type = "status",
            title = "Agent detail visual fixture",
            body = "Rebuilt from current main with real Members detail components.",
            timestamp = "2026-06-19T12:28:00Z",
            details = listOf("runtime" to "codex", "surface" to "members.agent-detail.profile"),
            statusActivity = "working",
            statusDetail = "Capturing deterministic profile state"
        )
    ),
    skills = AgentSkillsSnapshot(
        agentId = membersAgentDetailVisualMember.id,
        groups = listOf(
            AgentSkillGroup(
                title = "Product review",
                count = 2,
                paths = listOf(
                    AgentSkillPathGroup(
                        path = "notes/product",
                        count = 2,
                        skills = listOf(
                            AgentSkillSummary("feature-map", "React/KMP parity maps"),
                            AgentSkillSummary("visual-testing", "Cross-platform fixture ownership")
                        )
                    )
                )
            )
        )
    )
)

@Composable
internal fun MembersPageContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
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
        val decodedPayload = payloadText.toKuiklyMembersPayload()
        if (instanceId.isBlank() || decodedPayload.instanceId == instanceId) {
            decodedPayload
        } else {
            decodedPayload.copy(instanceId = instanceId)
        }
    }

    // Build the KMP dataSource + coordinator directly (no bridge event round-trip).
    // Mirrors ThreadPage's pattern: sharedStores gives us the auth store;
    // pager.acquireModule<SlockNetworkModule>(...) gives us a network client.
    val sharedStores = remember { ai.slock.compose.data.SlockSharedStoreRegistry.get(bridge) }
    val authStore = sharedStores.authStore
    val networkClient = remember {
        KuiklySlockNetworkClient(
            sessionStore = authStore,
            networkModule = pager.acquireModule<ai.slock.compose.network.SlockNetworkModule>(
                ai.slock.compose.network.SlockNetworkModule.MODULE_NAME
            ),
            warningLogger = { message -> bridge.log("members_network $message") }
        )
    }
    val agentsApiClient = remember(networkClient) {
        ai.slock.compose.data.SlockAgentsApiClient(
            transport = ai.slock.compose.data.SlockAgentsNetworkApi(networkClient),
            diagnosticsLogger = { message -> bridge.log("members_agents_api $message") }
        )
    }
    val computersApiClient = remember(networkClient) {
        ai.slock.compose.data.SlockComputersApiClient(
            ai.slock.compose.data.SlockComputersNetworkApi(networkClient)
        )
    }
    val createAgentCoordinator = remember(agentsApiClient, computersApiClient) {
        ai.slock.compose.data.SharedCreateAgentCoordinator(
            agentsApiClient = agentsApiClient,
            computersApiClient = computersApiClient,
            logger = { message -> bridge.log("members_create_agent $message") }
        )
    }
    val membersDataSource = remember(networkClient, authStore) {
        ai.slock.compose.data.SharedMembersDataSource(
            memberRepository = ai.slock.compose.data.RealMemberRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = { message -> bridge.log("members_member_repository $message") }
            ),
            settingsRepository = ai.slock.compose.data.RealSettingsRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = { message -> bridge.log("members_settings_repository $message") }
            )
        )
    }
    var machines by remember { mutableStateOf<List<SharedCreateAgentMachineOption>>(emptyList()) }
    var machinesLoading by remember { mutableStateOf(false) }
    var currentAgentCount by remember { mutableStateOf(payload.members.count { it.kind == MemberKind.Agent }) }
    var maxAgents by remember { mutableStateOf(-1) }
    var localMembers by remember { mutableStateOf(payload.members) }
    LaunchedEffect(payload) {
        // Sync local members when the host pushes a fresh payload
        localMembers = payload.members
        currentAgentCount = payload.members.count { it.kind == MemberKind.Agent }
    }
    // Derive the active serverId from the auth session. Replaces the prior
    // `readCurrentServerId()` stub (fixed QA-3): machines load + plan limit
    // checks now use the real selected server.
    var activeServerId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(authStore) {
        activeServerId = authStore.readSession()?.currentServerId
    }
    val serverId = activeServerId ?: ""
    val scope = rememberCoroutineScope()
    val createAgentSubmit: (suspend (SharedCreateAgentDialogSubmit) -> DataResult<Unit>)? =
        remember(createAgentCoordinator) {
            { submit ->
                val result = createAgentCoordinator.createAgent(submit.toCreateAgentRequest())
                if (result is DataResult.Content) {
                    val profile = ai.slock.compose.model.MemberProfile(
                        id = ai.slock.compose.model.MemberId(result.data.id),
                        displayName = result.data.displayName?.takeIf { it.isNotBlank() } ?: result.data.name,
                        handle = result.data.name,
                        kind = ai.slock.compose.model.MemberKind.Agent,
                        description = result.data.activityDetail,
                        roleDescription = result.data.model,
                        presence = ai.slock.compose.model.Presence.Offline,
                        agentStatus = ai.slock.compose.model.AgentStatus.Online
                    )
                    localMembers = localMembers + profile
                }
                when (result) {
                    is DataResult.Content -> DataResult.Content(Unit)
                    is DataResult.Empty -> DataResult.Empty(result.reason)
                    is DataResult.Error -> result
                    is DataResult.NoPermission -> result
                }
            }
        }
    // Load machines lazily when the dialog opens; see MembersListContent state.
    val loadMachines: () -> Unit = remember(createAgentCoordinator, serverId) {
        {
            if (machines.isEmpty() && !machinesLoading && serverId.isNotBlank()) {
                machinesLoading = true
                scope.launch {
                    val result = createAgentCoordinator.loadMachines(serverId)
                    machinesLoading = false
                    if (result is DataResult.Content) {
                        machines = result.data
                    }
                }
            }
        }
    }

    // Task #43 step 3: humans "+" invite flow state. Mirrors the React
    // `InviteHumanDialog.tsx` (join link + emails + send). Join link
    // auto-loads on first open; if none exist, the data source creates
    // one (no expiry, no max-uses). Sending POSTs one `/servers/{id}/invites`
    // per non-empty email.
    var inviteJoinLinkLoading by remember { mutableStateOf(false) }
    var inviteJoinLinkUrl by remember { mutableStateOf<String?>(null) }
    var inviteJoinLinkCopied by remember { mutableStateOf(false) }
    fun loadInviteJoinLink() {
        if (serverId.isBlank() || inviteJoinLinkLoading) return
        inviteJoinLinkLoading = true
        scope.launch {
            val listed = membersDataSource.loadJoinLinks(serverId)
            val existing = (listed as? DataResult.Content)?.data.orEmpty().firstOrNull()
            val result = if (existing != null) {
                DataResult.Content(existing)
            } else {
                membersDataSource.ensureJoinLink(serverId)
            }
            inviteJoinLinkLoading = false
            when (result) {
                is DataResult.Content -> inviteJoinLinkUrl = result.data.url
                is DataResult.NoPermission ->
                    bridge.toast("You do not have permission to manage invites.")
                is DataResult.Error ->
                    bridge.toast("Failed to load join link: ${result.error.code}")
                is DataResult.Empty -> Unit
            }
        }
    }
    val inviteHumanSubmit: (suspend (List<String>) -> DataResult<Unit>)? =
        remember(membersDataSource, serverId) {
            { emails ->
                if (serverId.isBlank()) {
                    DataResult.Error(ScreenError(code = "server_id_missing", retryable = false))
                } else {
                    val results = emails.map { email ->
                        membersDataSource.sendInvite(serverId, email)
                    }
                    when {
                        results.isEmpty() ->
                            DataResult.Empty(EmptyReason.NoSettingsAvailable)
                        results.all { it is DataResult.Content } ->
                            DataResult.Content(Unit)
                        else -> {
                            val firstError = results.firstNotNullOfOrNull {
                                (it as? DataResult.Error)?.error?.code
                            }
                            DataResult.Error(
                                ScreenError(
                                    code = firstError ?: "invite_send_failed",
                                    retryable = true
                                )
                            )
                        }
                    }
                }
            }
        }

    MembersRouteContent(
        payload = payload.copy(members = localMembers),
        onAddHuman = { /* humans "+" wired via addHumanSubmit below */ },
        onOpenDirectMessage = { bridge.settingsEvent(payload.instanceId, "openMember", it.id.value) },
        onCopyMemberId = {
            bridge.copyToPasteboard(it.id.value)
            bridge.toast("Copied member id")
        },
        onOpenUrl = { bridge.openUrl(it) },
        canCreateAgents = payload.canCreateAgents,
        createAgentSubmit = createAgentSubmit,
        createAgentMachines = machines,
        createAgentMachinesLoading = machinesLoading,
        createAgentCurrentAgentCount = currentAgentCount,
        createAgentMaxAgents = maxAgents,
        createAgentLoadMachines = loadMachines,
        inviteHumanSubmit = inviteHumanSubmit,
        inviteHumanJoinLinkLoading = inviteJoinLinkLoading,
        inviteHumanJoinLinkUrl = inviteJoinLinkUrl,
        inviteHumanJoinLinkCopied = inviteJoinLinkCopied,
        inviteHumanJoinLinkCopy = {
            val url = inviteJoinLinkUrl
            if (url != null) {
                bridge.copyToPasteboard(url)
                inviteJoinLinkCopied = true
            }
        },
        inviteHumanLoadJoinLink = { loadInviteJoinLink() }
    )
}

@Composable
private fun MembersAgentDetailVisualContent() {
    MembersDetailContent(
        member = membersAgentDetailVisualMember,
        agentDetailState = MembersAgentDetailLoadState.Content(membersAgentDetailVisualSnapshot),
        canViewPrivateAgentSurfaces = { _, _ -> true },
        canManageAgent = { _, _ -> true },
        onBack = {},
        onOpenDirectMessage = {},
        onCopyMemberId = {},
        onOpenUrl = {},
        onUpdateAgentProfile = { DataResult.Content(membersAgentDetailVisualMember) },
        onAgentLifecycleAction = { _, action ->
            DataResult.Content(
                AgentLifecycleActionResult(
                    agentId = membersAgentDetailVisualMember.id,
                    action = action.actionKey,
                    success = true
                )
            )
        },
        agentWorkspaceTreeLoader = null,
        agentWorkspaceChildrenLoader = null,
        agentWorkspaceFileLoader = null,
        onCopyAgentDiagnostics = {},
        onReportAgentIssue = null,
        onUnavailableAction = {},
        onUploadAgentAvatar = null,
        timeContext = SlockTimeContext(
            localOffsetMinutes = 480,
            todayEpochDay = 20623,
            nowEpochMillis = 1781872200000,
            timeFormat = SlockTimeFormat24Hour
        )
    )
}

private fun readCurrentServerId(): String? = null

internal fun String.toKuiklyMembersPayload(): KuiklyMembersPayload {
    if (isBlank()) return KuiklyMembersPayload()
    val payload = JSONObject(this)
    val labelsJson = payload.optJSONObject("labels")
    val membersJson = payload.optJSONArray("members")
    return KuiklyMembersPayload(
        instanceId = payload.optString("instanceId"),
        title = payload.optString("title").ifBlank { "Members" },
        localOffsetMinutes = payload.optString("localOffsetMinutes").toIntOrNull() ?: 0,
        todayEpochDay = payload.optString("todayEpochDay").toLongOrNull(),
        nowEpochMillis = payload.optString("nowEpochMillis").toLongOrNull(),
        timeFormat = payload.optString("timeFormat").ifBlank { SlockTimeFormat24Hour },
        canCreateAgents = (payload.opt("canCreateAgents") as? Boolean) ?: true,
        labels = KuiklyMembersLabels(
            agents = labelsJson?.optString("agents").orEmpty().ifBlank { "AGENTS" },
            humans = labelsJson?.optString("humans").orEmpty().ifBlank { "HUMANS" },
            addHuman = labelsJson?.optString("addHuman").orEmpty().ifBlank { "Add human" }
        ),
        members =
            buildList {
                if (membersJson == null) return@buildList
                for (index in 0 until membersJson.length()) {
                    add(membersJson.optJSONObject(index).toMemberProfile())
                }
            }
    )
}

private fun JSONObject?.toMemberProfile(): MemberProfile {
    if (this == null) return KuiklyMembersPayload().members.firstOrNull() ?: MemberProfile(
        id = MemberId("unknown"),
        displayName = "Unknown",
        handle = "@unknown",
        kind = MemberKind.Human
    )
    val avatarJson = optJSONObject("avatar")
    val avatarWireValue =
        avatarJson?.optString("wireValue")
            ?: optString("avatarWireValue").takeIf { it.isNotBlank() }
            ?: optString("avatarUrl").takeIf { it.isNotBlank() }
    return MemberProfile(
        id = MemberId(optString("id").ifBlank { "unknown" }),
        displayName = optString("displayName").ifBlank { "Unknown" },
        handle = optString("handle").ifBlank { "@unknown" },
        kind = optString("kind").toMemberKind(),
        description = optNullableString("description"),
        roleDescription = optNullableString("roleDescription"),
        avatarUrl = optNullableString("avatarUrl"),
        gravatarHash = optNullableString("gravatarHash"),
        avatar = AvatarReference.fromWireValue(avatarWireValue),
        avatarLabel = optString("avatarLabel").ifBlank { optString("displayName").take(1).uppercase() },
        presence = optString("presence").toPresence(),
        agentStatus = AgentStatus.fromWireValue(optNullableString("agentStatus")),
        permissionBadges =
            buildList {
                val badges = this@toMemberProfile.optJSONArray("permissionBadges") ?: return@buildList
                for (index in 0 until badges.length()) {
                    badges.optString(index)?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
    )
}

private fun String.toMemberKind(): MemberKind =
    MemberKind.entries.firstOrNull { it.name.equals(this, ignoreCase = true) } ?: MemberKind.Human

private fun String.toPresence(): Presence =
    Presence.entries.firstOrNull { it.name.equals(this, ignoreCase = true) } ?: Presence.Offline

private fun JSONObject.optNullableString(key: String): String? =
    optString(key).takeIf { it.isNotBlank() && it != "null" }

@Composable
internal fun MembersRouteContent(
    payload: KuiklyMembersPayload,
    onAddHuman: () -> Unit,
    onOpenDirectMessage: (MemberProfile) -> Unit,
    onCopyMemberId: (MemberProfile) -> Unit,
    onOpenUrl: (String) -> Unit = {},
    agentDetailLoader: (suspend (MemberId) -> DataResult<AgentDetailSnapshot>)? = null,
    agentDetailRefreshKey: Any? = Unit,
    onUpdateAgentProfile: (suspend (AgentProfileUpdateRequest) -> DataResult<MemberProfile>)? = null,
    onUploadAgentAvatar: (suspend (MemberId, AttachmentUploadFile) -> DataResult<MemberProfile>)? = null,
    onPickAgentAvatarFile: suspend () -> DataResult<AttachmentUploadFile> = { DataResult.Empty(EmptyReason.NoMembers) },
    onAgentLifecycleAction: (suspend (MemberId, MembersAgentLifecycleAction) -> DataResult<AgentLifecycleActionResult>)? = null,
    agentWorkspaceTreeLoader: (suspend (MemberId) -> DataResult<AgentWorkspaceTreeSnapshot>)? = null,
    agentWorkspaceChildrenLoader: (suspend (MemberId, String) -> DataResult<AgentWorkspaceChildrenSnapshot>)? = null,
    agentWorkspaceFileLoader: (suspend (MemberId, String) -> DataResult<AgentWorkspaceFilePreviewSnapshot>)? = null,
    onCopyAgentDiagnostics: (String) -> Unit = {},
    onReportAgentIssue: (suspend (String) -> Boolean)? = null,
    canViewPrivateAgentSurfaces: (MemberProfile, AgentDetailSnapshot?) -> Boolean = { _, _ -> false },
    canManageAgent: (MemberProfile, AgentDetailSnapshot?) -> Boolean = { _, _ -> false },
    onUnavailableAction: (String) -> Unit = {},
    /**
     * Optional create-agent flow. When non-null, the agents section "+" opens a
     * SharedCreateAgentDialog and submit is forwarded to this host callback.
     * The host is responsible for the actual API call and refreshing the
     * members payload. Mirrors the existing onAgentLifecycleAction pattern.
     */
    createAgentSubmit: (suspend (SharedCreateAgentDialogSubmit) -> DataResult<Unit>)? = null,
    createAgentMachines: List<SharedCreateAgentMachineOption> = emptyList(),
    createAgentMachinesLoading: Boolean = false,
    createAgentCurrentAgentCount: Int = 0,
    createAgentMaxAgents: Int = -1,
    createAgentLoadMachines: () -> Unit = {},
    /**
     * Permission gate for showing the agents "+". Defaults to false (no create).
     * When true, the agents "+" is rendered. When false, the "+" is hidden.
     */
    canCreateAgents: Boolean = false,
    /**
     * Optional invite-human flow. When non-null, the humans section "+" opens
     * a [SharedInviteHumanDialog] and submit is forwarded to this host
     * callback. Mirrors the [createAgentSubmit] pattern.
     */
    inviteHumanSubmit: (suspend (emails: List<String>) -> DataResult<Unit>)? = null,
    inviteHumanJoinLinkLoading: Boolean = false,
    inviteHumanJoinLinkUrl: String? = null,
    inviteHumanJoinLinkCopied: Boolean = false,
    inviteHumanJoinLinkCopy: () -> Unit = {},
    inviteHumanLoadJoinLink: () -> Unit = {}
) {
    var selectedMemberId by remember(payload.instanceId) { mutableStateOf<String?>(null) }
    val selectedMember = payload.members.firstOrNull { it.id.value == selectedMemberId }
    val timeContext = payload.toSlockTimeContext()
    var agentDetailState by remember(selectedMember?.id?.value) {
        mutableStateOf<MembersAgentDetailLoadState>(MembersAgentDetailLoadState.Idle)
    }
    LaunchedEffect(selectedMemberId, selectedMember?.id?.value) {
        if (selectedMemberId != null && selectedMember == null) {
            selectedMemberId = null
        }
    }
    LaunchedEffect(selectedMember?.id?.value, selectedMember?.kind, agentDetailRefreshKey) {
        val member = selectedMember
        if (member == null || member.kind != MemberKind.Agent || agentDetailLoader == null) {
            agentDetailState = MembersAgentDetailLoadState.Idle
            return@LaunchedEffect
        }
        if (agentDetailState !is MembersAgentDetailLoadState.Content) {
            agentDetailState = MembersAgentDetailLoadState.Loading
        }
        val nextState =
            when (val result = agentDetailLoader(member.id)) {
                is DataResult.Content -> MembersAgentDetailLoadState.Content(result.data)
                is DataResult.Empty -> MembersAgentDetailLoadState.Empty
                is DataResult.NoPermission -> MembersAgentDetailLoadState.NoPermission(result.blocker.code)
                is DataResult.Error -> MembersAgentDetailLoadState.Error(result.error.code)
            }
        agentDetailState = nextState
    }
    SlockBackHandler(enabled = selectedMember != null) {
        selectedMemberId = null
    }
    if (selectedMember != null) {
        MembersDetailContent(
            member = selectedMember,
            agentDetailState = agentDetailState,
            canViewPrivateAgentSurfaces = canViewPrivateAgentSurfaces,
            canManageAgent = canManageAgent,
            onBack = { selectedMemberId = null },
            onOpenDirectMessage = { onOpenDirectMessage(selectedMember) },
            onCopyMemberId = { onCopyMemberId(selectedMember) },
            onOpenUrl = onOpenUrl,
            onUpdateAgentProfile = onUpdateAgentProfile,
            onUploadAgentAvatar = onUploadAgentAvatar,
            onPickAgentAvatarFile = onPickAgentAvatarFile,
            onAgentLifecycleAction = onAgentLifecycleAction,
            agentWorkspaceTreeLoader = agentWorkspaceTreeLoader,
            agentWorkspaceChildrenLoader = agentWorkspaceChildrenLoader,
            agentWorkspaceFileLoader = agentWorkspaceFileLoader,
            onCopyAgentDiagnostics = onCopyAgentDiagnostics,
            onReportAgentIssue = onReportAgentIssue,
            onUnavailableAction = onUnavailableAction,
            timeContext = timeContext
        )
    } else {
        MembersListContent(
            payload = payload,
            onAddHuman = onAddHuman,
            onOpenMember = { selectedMemberId = it.id.value },
            canCreateAgents = canCreateAgents,
            createAgentSubmit = createAgentSubmit,
            createAgentMachines = createAgentMachines,
            createAgentMachinesLoading = createAgentMachinesLoading,
            createAgentCurrentAgentCount = createAgentCurrentAgentCount,
            createAgentMaxAgents = createAgentMaxAgents,
            createAgentLoadMachines = createAgentLoadMachines,
            inviteHumanSubmit = inviteHumanSubmit,
            inviteHumanJoinLinkLoading = inviteHumanJoinLinkLoading,
            inviteHumanJoinLinkUrl = inviteHumanJoinLinkUrl,
            inviteHumanJoinLinkCopied = inviteHumanJoinLinkCopied,
            inviteHumanJoinLinkCopy = inviteHumanJoinLinkCopy,
            inviteHumanLoadJoinLink = { inviteHumanLoadJoinLink() }
        )
    }
}

@Composable
internal fun MembersListContent(
    payload: KuiklyMembersPayload,
    onAddHuman: () -> Unit,
    onOpenMember: (MemberProfile) -> Unit,
    canCreateAgents: Boolean = false,
    createAgentSubmit: (suspend (SharedCreateAgentDialogSubmit) -> DataResult<Unit>)? = null,
    createAgentMachines: List<SharedCreateAgentMachineOption> = emptyList(),
    createAgentMachinesLoading: Boolean = false,
    createAgentCurrentAgentCount: Int = 0,
    createAgentMaxAgents: Int = -1,
    createAgentLoadMachines: () -> Unit = {},
    inviteHumanSubmit: (suspend (emails: List<String>) -> DataResult<Unit>)? = null,
    inviteHumanJoinLinkLoading: Boolean = false,
    inviteHumanJoinLinkUrl: String? = null,
    inviteHumanJoinLinkCopied: Boolean = false,
    inviteHumanJoinLinkCopy: () -> Unit = {},
    inviteHumanLoadJoinLink: () -> Unit = {}
) {
    val groupedMembers = remember(payload.members) {
        payload.members.groupBy {
            when (it.kind) {
                MemberKind.Human -> payload.labels.humans
                MemberKind.Agent, MemberKind.System -> payload.labels.agents
            }
        }
    }
    val agents = groupedMembers[payload.labels.agents].orEmpty()
    val humans = groupedMembers[payload.labels.humans].orEmpty()
    var agentsExpanded by remember(payload.instanceId, agents.size) { mutableStateOf(true) }
    var humansExpanded by remember(payload.instanceId, humans.size) { mutableStateOf(true) }
    var activeCreateAgent by remember(payload.instanceId) { mutableStateOf(false) }
    var createAgentSubmitting by remember { mutableStateOf(false) }
    var createAgentError by remember { mutableStateOf<String?>(null) }
    var activeInviteHuman by remember(payload.instanceId) { mutableStateOf(false) }
    var inviteHumanSubmitting by remember { mutableStateOf(false) }
    var inviteHumanError by remember { mutableStateOf<String?>(null) }
    val lazyItemCount =
        membersLazyItemCount(
            agents = agents,
            humansCount = humans.size,
            agentsExpanded = agentsExpanded,
            humansExpanded = humansExpanded
        )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        MembersPageHeader(title = payload.title)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
            beyondBoundsItemCount = sharedListBeyondBoundsItemCount(lazyItemCount)
        ) {
            item { Spacer(Modifier.height(SlockSpacing.Md)) }
            item {
                SlockCollapsibleSectionHeader(
                    label = payload.labels.agents,
                    count = agents.size,
                    expanded = agentsExpanded,
                    onExpandedChange = { agentsExpanded = it },
                    modifier = Modifier.padding(horizontal = MembersPageInset),
                    trailingContent = {
                        if (canCreateAgents) {
                            SlockSectionHeaderIconAction(
                                icon = SlockLucideIconName.Plus,
                                contentDescription = "Create agent",
                                onClick = {
                                    if (createAgentSubmit != null) {
                                        activeCreateAgent = true
                                        createAgentLoadMachines()
                                    }
                                }
                            )
                        }
                    }
                )
            }
            if (agentsExpanded) {
                groupedAgentMembers(agents).forEach { group ->
                    item("group_${group.label}") {
                        Text(
                            text = group.label,
                            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                            color = SlockColor.TextMutedGray,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = MembersPageInset)
                                .padding(top = SlockSpacing.Xs)
                        )
                    }
                    items(group.members.size, key = { "agent_${group.members[it].id.value}" }) { index ->
                        val member = group.members[index]
                        MembersListRow(
                            member = member,
                            modifier = Modifier.padding(horizontal = MembersPageInset),
                            onClick = { onOpenMember(member) }
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(SlockSpacing.Sm))
                SlockCollapsibleSectionHeader(
                    label = payload.labels.humans,
                    count = humans.size,
                    expanded = humansExpanded,
                    onExpandedChange = { humansExpanded = it },
                    modifier = Modifier.padding(horizontal = MembersPageInset),
                    trailingContent = {
                        SlockSectionHeaderIconAction(
                            icon = SlockLucideIconName.Plus,
                            contentDescription = "Invite human",
                            onClick = { activeInviteHuman = true }
                        )
                    }
                )
            }
            if (humansExpanded) {
                items(humans.size, key = { "human_${humans[it].id.value}" }) { index ->
                    val member = humans[index]
                    MembersListRow(
                        member = member,
                        modifier = Modifier.padding(horizontal = MembersPageInset),
                        onClick = { onOpenMember(member) }
                    )
                }
            }
        }
    }
    val submit: (suspend (SharedCreateAgentDialogSubmit) -> DataResult<Unit>)? = createAgentSubmit
    if (activeCreateAgent && submit != null) {
        val scope = rememberCoroutineScope()
        SharedCreateAgentDialog(
            submitting = createAgentSubmitting,
            errorMessage = createAgentError,
            machines = createAgentMachines,
            machinesLoading = createAgentMachinesLoading,
            currentAgentCount = createAgentCurrentAgentCount,
            maxAgents = createAgentMaxAgents,
            onDismiss = {
                if (!createAgentSubmitting) {
                    activeCreateAgent = false
                    createAgentError = null
                }
            },
            onSubmit = { request ->
                createAgentSubmitting = true
                createAgentError = null
                scope.launch {
                    val result = submit(request)
                    createAgentSubmitting = false
                    when (result) {
                        is DataResult.Content -> {
                            activeCreateAgent = false
                            createAgentError = null
                        }
                        is DataResult.Empty -> {
                            createAgentError = "Empty response"
                        }
                        is DataResult.Error -> {
                            createAgentError = "Failed to create agent: ${result.error.code}"
                        }
                        is DataResult.NoPermission -> {
                            createAgentError = "You do not have permission to create agents."
                        }
                    }
                }
            }
        )
    }
    val inviteSubmit: (suspend (List<String>) -> DataResult<Unit>)? = inviteHumanSubmit
    if (activeInviteHuman && inviteSubmit != null) {
        val scope = rememberCoroutineScope()
        // Load the join link when the dialog opens. Cheap: one GET (and at
        // most one POST if the list is empty), both gated on the user's
        // manage-invite permission via the data source.
        LaunchedEffect(Unit) {
            if (inviteHumanJoinLinkUrl == null && !inviteHumanJoinLinkLoading) {
                inviteHumanLoadJoinLink()
            }
        }
        SharedInviteHumanDialog(
            submitting = inviteHumanSubmitting,
            errorMessage = inviteHumanError,
            joinLinkLoading = inviteHumanJoinLinkLoading,
            joinLinkUrl = inviteHumanJoinLinkUrl,
            copied = inviteHumanJoinLinkCopied,
            onCopyJoinLink = inviteHumanJoinLinkCopy,
            onDismiss = {
                if (!inviteHumanSubmitting) {
                    activeInviteHuman = false
                    inviteHumanError = null
                }
            },
            onSubmit = { emails ->
                inviteHumanSubmitting = true
                inviteHumanError = null
                scope.launch {
                    val result = inviteSubmit(emails)
                    inviteHumanSubmitting = false
                    when (result) {
                        is DataResult.Content -> {
                            activeInviteHuman = false
                            inviteHumanError = null
                        }
                        is DataResult.Empty -> {
                            inviteHumanError = "Empty response"
                        }
                        is DataResult.Error -> {
                            inviteHumanError = "Failed to send invites: ${result.error.code}"
                        }
                        is DataResult.NoPermission -> {
                            inviteHumanError = "You do not have permission to send invites."
                        }
                    }
                }
            }
        )
    }
}

internal fun membersLazyItemCount(
    agents: List<MemberProfile>,
    humansCount: Int,
    agentsExpanded: Boolean,
    humansExpanded: Boolean
): Int {
    val fixedItems =
        1 + // top spacer
            1 + // agents header
            1 + // humans header
            1 // bottom spacer
    val agentItems =
        if (agentsExpanded) {
            groupedAgentMembers(agents).sumOf { group -> 1 + group.members.size }
        } else {
            0
        }
    return fixedItems + agentItems + (if (humansExpanded) humansCount else 0)
}

@Composable
private fun MembersDetailContent(
    member: MemberProfile,
    agentDetailState: MembersAgentDetailLoadState,
    canViewPrivateAgentSurfaces: (MemberProfile, AgentDetailSnapshot?) -> Boolean,
    canManageAgent: (MemberProfile, AgentDetailSnapshot?) -> Boolean,
    onBack: () -> Unit,
    onOpenDirectMessage: () -> Unit,
    onCopyMemberId: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onUpdateAgentProfile: (suspend (AgentProfileUpdateRequest) -> DataResult<MemberProfile>)?,
    onUploadAgentAvatar: (suspend (MemberId, AttachmentUploadFile) -> DataResult<MemberProfile>)?,
    onPickAgentAvatarFile: suspend () -> DataResult<AttachmentUploadFile> = { DataResult.Empty(EmptyReason.NoMembers) },
    onAgentLifecycleAction: (suspend (MemberId, MembersAgentLifecycleAction) -> DataResult<AgentLifecycleActionResult>)?,
    agentWorkspaceTreeLoader: (suspend (MemberId) -> DataResult<AgentWorkspaceTreeSnapshot>)?,
    agentWorkspaceChildrenLoader: (suspend (MemberId, String) -> DataResult<AgentWorkspaceChildrenSnapshot>)?,
    agentWorkspaceFileLoader: (suspend (MemberId, String) -> DataResult<AgentWorkspaceFilePreviewSnapshot>)?,
    onCopyAgentDiagnostics: (String) -> Unit,
    onReportAgentIssue: (suspend (String) -> Boolean)?,
    onUnavailableAction: (String) -> Unit,
    timeContext: SlockTimeContext
) {
    val detail = (agentDetailState as? MembersAgentDetailLoadState.Content)?.detail
    val deleted = detail?.summary?.deletedAt?.isNotBlank() == true
    val canViewPrivate = member.kind == MemberKind.Agent && canViewPrivateAgentSurfaces(member, detail)
    val canManage = member.kind == MemberKind.Agent && !deleted && canManageAgent(member, detail)
    val scope = rememberCoroutineScope()
    var actionState by remember(member.id.value) { mutableStateOf<MembersAgentActionState?>(null) }
    var diagnosticActionState by remember(member.id.value) { mutableStateOf<MembersAgentDiagnosticActionState?>(null) }
    var pendingConfirmation by remember(member.id.value) { mutableStateOf<MembersAgentLifecycleAction?>(null) }
    var selectedTab by remember(member.id.value) { mutableStateOf(MembersAgentDetailTab.Profile) }
    var managementMenuOpen by remember(member.id.value) { mutableStateOf(false) }
    var managementMenuAnchor by remember(member.id.value) { mutableStateOf<Offset?>(null) }
    var showAvatarPicker by remember(member.id.value) { mutableStateOf(false) }
    var workspaceTreeState by remember(member.id.value) {
        mutableStateOf<MembersAgentWorkspaceTreeState>(MembersAgentWorkspaceTreeState.Idle)
    }
    var expandedWorkspaceDirs by remember(member.id.value) { mutableStateOf<Set<String>>(emptySet()) }
    var loadedWorkspaceDirs by remember(member.id.value) { mutableStateOf<Map<String, List<SlockWorkspaceNode>>>(emptyMap()) }
    var loadingWorkspaceDirs by remember(member.id.value) { mutableStateOf<Set<String>>(emptySet()) }
    var workspaceDirErrors by remember(member.id.value) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var workspaceFileState by remember(member.id.value) {
        mutableStateOf<MembersAgentWorkspaceFileState>(MembersAgentWorkspaceFileState.None)
    }
    var workspaceViewMode by remember(member.id.value) { mutableStateOf(MembersWorkspaceViewMode.Preview) }
    val visibleTabs = remember(canViewPrivate, deleted, member.kind) {
        if (member.kind == MemberKind.Agent) {
            membersVisibleAgentTabs(canViewPrivateSurfaces = canViewPrivate, deleted = deleted)
        } else {
            listOf(MembersAgentDetailTab.Profile)
        }
    }
    LaunchedEffect(visibleTabs, selectedTab) {
        if (selectedTab !in visibleTabs) {
            selectedTab = MembersAgentDetailTab.Profile
        }
    }
    fun loadWorkspaceRoot() {
        val callback = agentWorkspaceTreeLoader
        if (callback == null) {
            workspaceTreeState = MembersAgentWorkspaceTreeState.Error("workspace_loader_unavailable")
            return
        }
        scope.launch {
            workspaceTreeState = MembersAgentWorkspaceTreeState.Loading
            expandedWorkspaceDirs = emptySet()
            loadedWorkspaceDirs = emptyMap()
            loadingWorkspaceDirs = emptySet()
            workspaceDirErrors = emptyMap()
            workspaceFileState = MembersAgentWorkspaceFileState.None
            workspaceTreeState =
                try {
                    callback(member.id).toMembersWorkspaceTreeState()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    MembersAgentWorkspaceTreeState.Error("workspace_root_failed")
                }
        }
    }
    fun toggleWorkspaceDirectory(path: String) {
        val expanded = path in expandedWorkspaceDirs
        expandedWorkspaceDirs =
            if (expanded) {
                expandedWorkspaceDirs - path
            } else {
                expandedWorkspaceDirs + path
            }
        if (!expanded && path !in loadedWorkspaceDirs && path !in loadingWorkspaceDirs) {
            val callback = agentWorkspaceChildrenLoader
            if (callback == null) {
                workspaceDirErrors = workspaceDirErrors + (path to "workspace_children_unavailable")
                return
            }
            scope.launch {
                loadingWorkspaceDirs = loadingWorkspaceDirs + path
                workspaceDirErrors = workspaceDirErrors - path
                when (
                    val result =
                        try {
                            callback(member.id, path)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Throwable) {
                            DataResult.Error(ScreenError("workspace_children_failed"))
                        }
                ) {
                    is DataResult.Content ->
                        loadedWorkspaceDirs = loadedWorkspaceDirs + (path to result.data.children)
                    is DataResult.Empty ->
                        loadedWorkspaceDirs = loadedWorkspaceDirs + (path to emptyList())
                    is DataResult.NoPermission ->
                        workspaceDirErrors = workspaceDirErrors + (path to result.blocker.code)
                    is DataResult.Error ->
                        workspaceDirErrors = workspaceDirErrors + (path to result.error.code)
                }
                loadingWorkspaceDirs = loadingWorkspaceDirs - path
            }
        }
    }
    fun selectWorkspaceFile(path: String) {
        val callback = agentWorkspaceFileLoader
        if (callback == null) {
            workspaceFileState = MembersAgentWorkspaceFileState.Error(path, "workspace_file_unavailable")
            return
        }
        workspaceViewMode = MembersWorkspaceViewMode.Preview
        scope.launch {
            workspaceFileState = MembersAgentWorkspaceFileState.Loading(path)
            workspaceFileState =
                try {
                    callback(member.id, path).toMembersWorkspaceFileState(path)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    MembersAgentWorkspaceFileState.Error(path, "workspace_file_failed")
                }
        }
    }
    LaunchedEffect(member.id.value, selectedTab, canViewPrivate, agentWorkspaceTreeLoader) {
        if (
            selectedTab == MembersAgentDetailTab.Workspace &&
            canViewPrivate &&
            workspaceTreeState is MembersAgentWorkspaceTreeState.Idle
        ) {
            loadWorkspaceRoot()
        }
    }
    fun runLifecycleAction(action: MembersAgentLifecycleAction, confirmed: Boolean = false) {
        if (actionState?.inProgress == true) return
        val callback = onAgentLifecycleAction
        if (!canManage || callback == null) {
            onUnavailableAction("Agent management is not available.")
            return
        }
        if (action.requiresConfirmation && !confirmed) {
            pendingConfirmation = action
            actionState = null
            return
        }
        scope.launch {
            pendingConfirmation = null
            actionState = MembersAgentActionState(action, inProgress = true)
            val result =
                try {
                    callback(member.id, action)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    DataResult.Error(ScreenError("agent_${action.actionKey}_failed"))
                }
            val errorCode = result.membersLifecycleErrorCode(action)
            actionState =
                MembersAgentActionState(
                    action = action,
                    inProgress = false,
                    errorCode = errorCode,
                    successLabel = action.successLabel.takeIf { errorCode == null }
                )
        }
    }
    fun copyDiagnostics() {
        onCopyAgentDiagnostics(buildMembersAgentDiagnostics(member, detail))
        diagnosticActionState =
            MembersAgentDiagnosticActionState(
                actionKey = "copy",
                inProgress = false,
                successLabel = "Diagnostics copied"
            )
    }
    fun reportIssue() {
        val callback = onReportAgentIssue
        if (diagnosticActionState?.inProgress == true) return
        if (callback == null) {
            onUnavailableAction("Agent issue reporting is not available.")
            return
        }
        scope.launch {
            diagnosticActionState =
                MembersAgentDiagnosticActionState(actionKey = "report", inProgress = true)
            val diagnostics = buildMembersAgentDiagnostics(member, detail)
            val reported =
                try {
                    callback(diagnostics)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    false
                }
            diagnosticActionState =
                MembersAgentDiagnosticActionState(
                    actionKey = "report",
                    inProgress = false,
                    errorCode = if (reported) null else "agent_issue_report_failed",
                    successLabel = "Issue reported".takeIf { reported }
                )
        }
    }
    fun saveAvatar(avatarUrl: String?) {
        val callback = onUpdateAgentProfile
        if (!canManage || deleted || callback == null) {
            onUnavailableAction("Agent avatar editing is not available.")
            return
        }
        scope.launch {
            val result =
                try {
                    callback(
                        AgentProfileUpdateRequest(
                            agentId = member.id,
                            displayName = member.displayName,
                            description = member.description?.takeIf { it.isNotBlank() },
                            avatarUrl = avatarUrl,
                            includeAvatarUrl = true
                        )
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    DataResult.Error(ScreenError("agent_avatar_save_failed"))
                }
            when (result) {
                is DataResult.Content -> {
                    showAvatarPicker = false
                }
                is DataResult.Empty -> {
                    onUnavailableAction("Avatar unchanged")
                }
                is DataResult.NoPermission -> {
                    onUnavailableAction("No permission: ${result.blocker.code}")
                }
                is DataResult.Error -> {
                    onUnavailableAction("Avatar save failed: ${result.error.code}")
                }
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
        MembersDetailHeader(
            member = member,
            detail = detail,
            deleted = deleted,
            canManage = canManage,
            onBack = onBack,
            onAvatarClick = if (canManage && !deleted) ({ showAvatarPicker = true }) else null,
            onOpenDirectMessage = onOpenDirectMessage,
            onCopyMemberId = onCopyMemberId,
            onRequestManagementMenu = { anchor ->
                managementMenuAnchor = anchor
                managementMenuOpen = true
            }
        )
        if (member.kind == MemberKind.Agent) {
            MembersAgentTabBar(
                tabs = visibleTabs,
                selectedTab = selectedTab,
                onSelectedTabChange = { selectedTab = it }
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md),
            beyondBoundsItemCount = sharedListBeyondBoundsItemCount(12)
        ) {
            item { Spacer(Modifier.height(SlockSpacing.Sm)) }
            if (member.kind == MemberKind.Agent) {
                item {
                    MembersAgentDetailStateNotice(
                        state = agentDetailState,
                        canViewPrivate = canViewPrivate,
                        modifier = Modifier.padding(horizontal = MembersPageInset)
                    )
                }
                item {
                    when (selectedTab) {
                        MembersAgentDetailTab.Profile ->
                            MembersAgentProfileTab(
                                member = member,
                                detail = detail,
                                canManage = canManage,
                                onUpdateAgentProfile = onUpdateAgentProfile,
                                onUnavailableAction = onUnavailableAction,
                                modifier = Modifier.padding(horizontal = MembersPageInset)
                            )
                        MembersAgentDetailTab.Permissions ->
                            MembersAgentPlaceholderTab(
                                title = "PERMISSIONS",
                                detail = "No permission scopes available.",
                                modifier = Modifier.padding(horizontal = MembersPageInset)
                            )
                        MembersAgentDetailTab.Dms ->
                            MembersAgentDmsTab(
                                detail = detail,
                                modifier = Modifier.padding(horizontal = MembersPageInset)
                            )
                        MembersAgentDetailTab.Reminders ->
                            MembersAgentRemindersTab(
                                detail = detail,
                                modifier = Modifier.padding(horizontal = MembersPageInset)
                            )
                        MembersAgentDetailTab.Workspace ->
                            MembersAgentWorkspaceTab(
                                treeState = workspaceTreeState,
                                expandedDirs = expandedWorkspaceDirs,
                                loadedDirs = loadedWorkspaceDirs,
                                loadingDirs = loadingWorkspaceDirs,
                                dirErrors = workspaceDirErrors,
                                fileState = workspaceFileState,
                                viewMode = workspaceViewMode,
                                onRefresh = { loadWorkspaceRoot() },
                                onToggleDirectory = { toggleWorkspaceDirectory(it) },
                                onSelectFile = { selectWorkspaceFile(it) },
                                onBackToTree = { workspaceFileState = MembersAgentWorkspaceFileState.None },
                                onViewModeChange = { workspaceViewMode = it },
                                onOpenUrl = onOpenUrl,
                                modifier = Modifier.padding(horizontal = MembersPageInset)
                            )
                        MembersAgentDetailTab.Apps ->
                            MembersAgentAppsTab(
                                detail = detail,
                                modifier = Modifier.padding(horizontal = MembersPageInset)
                            )
                        MembersAgentDetailTab.Activity ->
                            MembersAgentActivityTab(
                                member = member,
                                detail = detail,
                                diagnosticActionState = diagnosticActionState,
                                onCopyDiagnostics = { copyDiagnostics() },
                                timeContext = timeContext,
                                onOpenUrl = onOpenUrl,
                                modifier = Modifier.padding(horizontal = MembersPageInset)
                            )
                    }
                }
            } else {
                item {
                    MembersHumanDetailSections(
                        member = member,
                        modifier = Modifier.padding(horizontal = MembersPageInset)
                    )
                }
            }
            item { Spacer(Modifier.height(180.dp)) }
        }
        if (showAvatarPicker && canManage && !deleted) {
            MembersAgentAvatarPicker(
                member = member,
                onSelect = { key ->
                    saveAvatar("pixel:$key")
                },
                onClear = {
                    saveAvatar(null)
                },
                onUpload = {
                    val uploadCallback = onUploadAgentAvatar
                    if (uploadCallback == null) {
                        DataResult.Empty(EmptyReason.NoMembers)
                    } else {
                        val picked = onPickAgentAvatarFile()
                        when (picked) {
                            is DataResult.Content ->
                                uploadCallback(member.id, picked.data)
                            is DataResult.Empty -> DataResult.Empty(picked.reason)
                            is DataResult.Error -> DataResult.Error(picked.error)
                            is DataResult.NoPermission -> DataResult.NoPermission(picked.blocker)
                        }
                    }
                },
                onUnavailableAction = onUnavailableAction,
                onDismiss = { showAvatarPicker = false }
            )
        }
        val dropdownAnchor = managementMenuAnchor
        if (managementMenuOpen && member.kind == MemberKind.Agent) {
            val menuSummary = detail?.summary
            val menuStatus = member.membersResolvedAgentStatus(menuSummary?.status, menuSummary?.activity)
            val menuPrimary = membersPrimaryLifecycleAction(menuStatus, deleted)
            val menuMachineAssigned = menuSummary?.machineId?.isNotBlank() == true
            val menuItems = buildMembersManagementMenuItems(
                primaryAction = menuPrimary,
                machineAssigned = menuMachineAssigned,
                canManage = canManage,
                deleted = deleted,
                hasReportIssue = onReportAgentIssue != null,
                inProgress = actionState?.inProgress == true || diagnosticActionState?.inProgress == true
            )
            SlockBrutalDropdownMenu(
                anchor = dropdownAnchor ?: Offset(100f, 100f),
                items = menuItems,
                onSelect = { id ->
                    handleMembersManagementMenuSelection(
                        id = id,
                        primaryAction = menuPrimary,
                        runLifecycleAction = { runLifecycleAction(it) },
                        copyDiagnostics = { copyDiagnostics() },
                        reportIssue = { reportIssue() }
                    )
                },
                onDismiss = { managementMenuOpen = false }
            )
        }
        pendingConfirmation?.let { action ->
            SlockBrutalModalLayer(
                model = SlockModalShellModel(
                    title = "Confirm ${action.label}",
                    subtitle = "${action.label} ${member.displayName}. This uses the real agent management API."
                ),
                onDismiss = {
                    pendingConfirmation = null
                }
            ) {
                MembersLifecycleConfirmation(
                    action = action,
                    agentName = member.displayName,
                    busy = actionState?.inProgress == true,
                    onConfirm = { runLifecycleAction(action, confirmed = true) },
                    onCancel = {
                        pendingConfirmation = null
                        actionState = null
                    }
                )
            }
        }
    }
    }
}

private fun DataResult<AgentLifecycleActionResult>.membersLifecycleErrorCode(action: MembersAgentLifecycleAction): String? =
    when (this) {
        is DataResult.Content ->
            if (data.success) null else "agent_${action.actionKey}_failed"
        is DataResult.Empty -> reason.name
        is DataResult.Error -> error.code
        is DataResult.NoPermission -> blocker.code
    }

private fun buildMembersManagementMenuItems(
    primaryAction: MembersAgentLifecycleAction?,
    machineAssigned: Boolean,
    canManage: Boolean,
    deleted: Boolean,
    hasReportIssue: Boolean,
    inProgress: Boolean
): List<SlockBrutalDropdownMenuItem> {
    if (deleted || !canManage) return emptyList()
    val items = mutableListOf<SlockBrutalDropdownMenuItem>()
    if (primaryAction != null) {
        items += SlockBrutalDropdownMenuItem(
            id = primaryAction.actionKey,
            label = primaryAction.label,
            icon = primaryAction.iconName,
            enabled = !inProgress
        )
    }
    items += SlockBrutalDropdownMenuItem(
        id = MembersAgentLifecycleAction.Restart.actionKey,
        label = MembersAgentLifecycleAction.Restart.label,
        icon = MembersAgentLifecycleAction.Restart.iconName,
        enabled = !inProgress
    )
    items += SlockBrutalDropdownMenuItem(
        id = MembersAgentLifecycleAction.ResetSession.actionKey,
        label = MembersAgentLifecycleAction.ResetSession.label,
        icon = MembersAgentLifecycleAction.ResetSession.iconName,
        enabled = !inProgress
    )
    items += SlockBrutalDropdownMenuItem(
        id = MembersAgentLifecycleAction.ResetFull.actionKey,
        label = MembersAgentLifecycleAction.ResetFull.label,
        icon = MembersAgentLifecycleAction.ResetFull.iconName,
        enabled = !inProgress
    )
    if (machineAssigned) {
        items += SlockBrutalDropdownMenuItem(
            id = MembersAgentLifecycleAction.UnassignMachine.actionKey,
            label = MembersAgentLifecycleAction.UnassignMachine.label,
            icon = MembersAgentLifecycleAction.UnassignMachine.iconName,
            enabled = !inProgress
        )
    }
    items += SlockBrutalDropdownMenuItem(
        id = "copy_diagnostics",
        label = "Copy diagnostics",
        icon = SlockLucideIconName.Clipboard,
        enabled = !inProgress
    )
    if (hasReportIssue) {
        items += SlockBrutalDropdownMenuItem(
            id = "report_issue",
            label = "Report issue",
            icon = SlockLucideIconName.MessageSquarePlus,
            enabled = !inProgress
        )
    }
    items += SlockBrutalDropdownMenuItem(
        id = MembersAgentLifecycleAction.Delete.actionKey,
        label = MembersAgentLifecycleAction.Delete.label,
        icon = MembersAgentLifecycleAction.Delete.iconName,
        destructive = true,
        enabled = !inProgress
    )
    return items
}

private fun handleMembersManagementMenuSelection(
    id: String,
    primaryAction: MembersAgentLifecycleAction?,
    runLifecycleAction: (MembersAgentLifecycleAction) -> Unit,
    copyDiagnostics: () -> Unit,
    reportIssue: () -> Unit
) {
    when (id) {
        "copy_diagnostics" -> copyDiagnostics()
        "report_issue" -> reportIssue()
        else -> {
            val action = MembersAgentLifecycleAction.values().firstOrNull { it.actionKey == id }
                ?: primaryAction?.takeIf { it.actionKey == id }
            if (action != null) {
                runLifecycleAction(action)
            }
        }
    }
}

private fun DataResult<AgentWorkspaceTreeSnapshot>.toMembersWorkspaceTreeState(): MembersAgentWorkspaceTreeState =
    when (this) {
        is DataResult.Content -> MembersAgentWorkspaceTreeState.Content(data)
        is DataResult.Empty -> MembersAgentWorkspaceTreeState.Empty
        is DataResult.Error -> MembersAgentWorkspaceTreeState.Error(error.code)
        is DataResult.NoPermission -> MembersAgentWorkspaceTreeState.NoPermission(blocker.code)
    }

private fun DataResult<AgentWorkspaceFilePreviewSnapshot>.toMembersWorkspaceFileState(
    path: String
): MembersAgentWorkspaceFileState =
    when (this) {
        is DataResult.Content -> MembersAgentWorkspaceFileState.Content(data)
        is DataResult.Empty -> MembersAgentWorkspaceFileState.Empty(path)
        is DataResult.Error -> MembersAgentWorkspaceFileState.Error(path, error.code)
        is DataResult.NoPermission -> MembersAgentWorkspaceFileState.NoPermission(path, blocker.code)
    }

@Composable
private fun MembersDetailHeader(
    member: MemberProfile,
    detail: AgentDetailSnapshot?,
    deleted: Boolean,
    canManage: Boolean,
    onBack: () -> Unit,
    onAvatarClick: (() -> Unit)?,
    onOpenDirectMessage: () -> Unit,
    onCopyMemberId: () -> Unit,
    onRequestManagementMenu: (Offset) -> Unit
) {
    val summary = detail?.summary
    var threeDotButtonCenter by remember { mutableStateOf<Offset?>(null) }
    val statusLabel =
        if (deleted) {
            "Deleted"
        } else {
            summary?.activityDetail?.takeIf { it.isNotBlank() }
                ?: summary?.activity?.takeIf { it.isNotBlank() }?.toMembersDisplayLabel()
                ?: member.detailStatusLabel
        }
    val avatarClick = onAvatarClick
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
    ) {
        Spacer(Modifier.height(slockTopSafeAreaPadding()))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 62.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            SlockBrutalIconButton(
                iconName = SlockLucideIconName.ArrowLeft,
                onClick = onBack,
                size = MembersActionSize
            )
            MembersAvatar(
                member = member,
                avatarSize = 42.dp,
                statusSize = 10.dp,
                statusPulsing = member.membersResolvedAgentStatus(summary?.status, summary?.activity).membersStatusPulsing(),
                modifier = if (avatarClick != null) {
                    Modifier.clickable(onClick = avatarClick)
                } else {
                    Modifier
                }
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = member.displayName,
                        style = SlockType.Body.copy(fontSize = 16.sp, lineHeight = 20.sp)
                            .withSlockFontWeight(FontWeight.Bold),
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    MemberKindBadge(member.kind)
                }
                Text(
                    text = member.description?.takeIf { it.isNotBlank() } ?: member.detailHandle,
                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = statusLabel,
                    style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = if (deleted) SlockColor.AccentOrange else SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            SlockBrutalActionButton(
                label = "Message",
                leadingIcon = SlockLucideIconName.MessageSquare,
                enabled = member.kind != MemberKind.System && !deleted,
                // Match the three-dot IconButton visual height so both sit on the same row
                // baseline. SlockBrutalIconButton(size=32) outer box = 32 + 3dp shadow = 35dp;
                // SlockBrutalActionButton with minHeight=MembersActionSize gives 32 + 3dp
                // shadow = 35dp outer, aligned.
                minHeight = MembersActionSize,
                modifier = Modifier.width(92.dp),
                onClick = onOpenDirectMessage
            )
            if (member.kind == MemberKind.Agent) {
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.Ellipsis,
                    onClick = {
                        onRequestManagementMenu(threeDotButtonCenter ?: Offset.Zero)
                    },
                    size = MembersActionSize,
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        val pos = coordinates.positionInRoot()
                        threeDotButtonCenter = Offset(
                            x = pos.x + coordinates.size.width.toFloat() / 2f,
                            y = pos.y + coordinates.size.height.toFloat() / 2f
                        )
                    }
                )
            } else {
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.Copy,
                    onClick = onCopyMemberId,
                    size = MembersActionSize
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SlockStroke.Default)
                .background(SlockColor.BorderBlack)
        )
    }
}

@Composable
private fun MembersDetailSummary(
    member: MemberProfile,
    detail: AgentDetailSnapshot?,
    deleted: Boolean,
    modifier: Modifier = Modifier
) {
    val summary = detail?.summary
    val statusLabel =
        if (deleted) {
            "Deleted"
        } else {
            summary?.activityDetail?.takeIf { it.isNotBlank() }
                ?: summary?.activity?.takeIf { it.isNotBlank() }?.toMembersDisplayLabel()
                ?: member.detailStatusLabel
        }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .background(if (deleted) SlockColor.EmptyStateGray else SlockColor.SurfaceCream)
            .padding(SlockSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        MembersAvatar(
            member = member,
            avatarSize = 58.dp,
            statusSize = 12.dp,
            statusPulsing = member.membersResolvedAgentStatus(summary?.status, summary?.activity).membersStatusPulsing()
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = member.displayName,
                    style = SlockType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                MemberKindBadge(member.kind)
            }
            Text(
                text = member.detailHandle,
                style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = member.description?.takeIf { it.isNotBlank() } ?: member.detailRoleLabel,
                style = SlockType.Body.withSlockFontWeight(FontWeight.Normal).copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = SlockColor.TextBlack,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = statusLabel,
                style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = if (deleted) SlockColor.AccentRed else SlockColor.TextMutedGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MembersDetailActions(
    member: MemberProfile,
    deleted: Boolean,
    onOpenDirectMessage: () -> Unit,
    onCopyMemberId: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        MembersDetailActionButton(
            label = "Message",
            iconName = SlockLucideIconName.MessageSquare,
            enabled = member.kind != MemberKind.System && !deleted,
            onClick = onOpenDirectMessage,
            modifier = Modifier.weight(1f)
        )
        MembersDetailActionButton(
            label = "Copy ID",
            iconName = SlockLucideIconName.Copy,
            enabled = true,
            onClick = onCopyMemberId,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MembersLifecycleConfirmation(
    action: MembersAgentLifecycleAction,
    agentName: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (action.destructive) SlockColor.AccentRed else SlockColor.SurfaceCream)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .padding(SlockSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Text(
            text = "Confirm ${action.label}",
            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp).withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.TextBlack
        )
        Text(
            text = "${action.label} ${agentName}. This uses the real agent management API.",
            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
            color = SlockColor.TextBlack
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
            SlockBrutalActionButton(
                label = "Cancel",
                leadingIcon = SlockLucideIconName.X,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = onCancel
            )
            SlockBrutalActionButton(
                label = "Confirm",
                leadingIcon = SlockLucideIconName.Check,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = onConfirm
            )
        }
    }
}

@Composable
private fun MembersAgentTabBar(
    tabs: List<MembersAgentDetailTab>,
    selectedTab: MembersAgentDetailTab,
    onSelectedTabChange: (MembersAgentDetailTab) -> Unit,
    modifier: Modifier = Modifier
) {
    SlockTopTabBar(
        items = tabs.map { it.toMembersTopTabBarItem() },
        selectedKey = selectedTab.name,
        onSelectedKeyChange = { key ->
            tabs.firstOrNull { it.name == key }?.let(onSelectedTabChange)
        },
        modifier = modifier,
        itemKeyPrefix = "members-agent-tab"
    )
}

private fun MembersAgentDetailTab.toMembersTopTabBarItem(): SlockTopTabBarItem =
    SlockTopTabBarItem(
        key = name,
        label = label,
        iconName = iconName,
        width =
            when (this) {
                MembersAgentDetailTab.Profile,
                MembersAgentDetailTab.Activity,
                MembersAgentDetailTab.Apps -> 98.dp
                MembersAgentDetailTab.Dms -> 112.dp
                MembersAgentDetailTab.Permissions,
                MembersAgentDetailTab.Reminders,
                MembersAgentDetailTab.Workspace -> 126.dp
            }
    )

@Composable
private fun MembersAgentDetailStateNotice(
    state: MembersAgentDetailLoadState,
    canViewPrivate: Boolean,
    modifier: Modifier = Modifier
) {
    val message =
        when (state) {
            MembersAgentDetailLoadState.Loading -> "Loading agent profile..."
            MembersAgentDetailLoadState.Empty -> "Agent profile metadata is unavailable."
            is MembersAgentDetailLoadState.Error -> "Could not load agent profile: ${state.code}"
            is MembersAgentDetailLoadState.NoPermission -> "Private agent surfaces are hidden: ${state.code}"
            MembersAgentDetailLoadState.Idle,
            is MembersAgentDetailLoadState.Content -> null
        }
    val privateMessage =
        if (!canViewPrivate && state is MembersAgentDetailLoadState.Content) {
            "Profile is public. Permissions, Agent DMs, Reminders, Workspace, Apps, and Activity are hidden for this account."
        } else {
            null
        }
    val resolved = message ?: privateMessage ?: return
    Text(
        text = resolved,
        style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp).withSlockFontWeight(FontWeight.Bold),
        color = SlockColor.TextBlack,
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceCream)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(SlockSpacing.Sm)
    )
}

@Composable
private fun MembersHumanDetailSections(member: MemberProfile, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
        MembersDetailSection(title = "PROFILE") {
            MembersDetailField("Kind", member.kind.name)
            MembersDetailField("Role", member.detailRoleLabel)
            MembersDetailField("Handle", member.detailHandle)
            MembersDetailField("Member ID", member.id.value)
        }
        MembersDetailSection(title = "STATUS") {
            MembersDetailField("Status", member.detailStatusLabel)
            MembersDetailField("Description", member.description?.takeIf { it.isNotBlank() } ?: "No description available.")
        }
    }
}

@Composable
private fun MembersAgentAvatarPicker(
    member: MemberProfile,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
    onUpload: suspend () -> DataResult<MemberProfile>,
    onUnavailableAction: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var uploading by remember(member.id.value) { mutableStateOf(false) }
    // Use SlockUnifiedDialog (the unified modal engine — wraps Kuikly `Modal(...)` for
    // overlay rendering) instead of SlockModalLayer (which is a plain Box and was getting
    // 0 vertical space when rendered as a sibling after a fillMaxSize LazyColumn in
    // MembersDetailContent's outer Column — see #49 top-left avatar bug thread).
    SlockUnifiedDialog(
        title = "Change avatar",
        dismissEnabled = !uploading,
        onDismiss = onDismiss
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            Text(
                text = member.displayName,
                style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
            ) {
                MembersAvatar(member = member, avatarSize = 80.dp)
                Text(
                    text = member.avatar?.wireValue ?: member.avatarUrl ?: "Default",
                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = SlockColor.TextMutedGray,
                    textAlign = TextAlign.Center
                )
            }
            Text(
                text = "Choose",
                style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = SlockColor.TextBlack,
                fontWeight = FontWeight.Bold
            )
            val rows = PixelAvatarPickerKeys.chunked(4)
            Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
                rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
                    ) {
                        row.forEach { key ->
                            SlockBrutalActionButton(
                                label = key,
                                onClick = { onSelect(key) },
                                modifier = Modifier.weight(1f),
                                backgroundColor = if (member.avatar?.wireValue == "pixel:$key") SlockColor.BrandYellow else SlockColor.SurfaceWhite,
                                minHeight = 56.dp,
                                textStyle = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp),
                                leadingIcon = null
                            )
                        }
                    }
                }
            }
            Text(
                text = "Actions",
                style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = SlockColor.TextBlack,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
            ) {
                SlockBrutalActionButton(
                    label = if (uploading) "Uploading…" else "Upload",
                    leadingIcon = if (uploading) SlockLucideIconName.Loader else SlockLucideIconName.Upload,
                    onClick = {
                        if (!uploading) {
                            uploading = true
                            scope.launch {
                                when (val result = onUpload()) {
                                    is DataResult.Content -> onDismiss()
                                    is DataResult.Empty -> {
                                        uploading = false
                                        onUnavailableAction("Avatar upload cancelled.")
                                    }
                                    is DataResult.Error -> {
                                        uploading = false
                                        onUnavailableAction("Avatar upload failed: ${result.error.code}")
                                    }
                                    is DataResult.NoPermission -> {
                                        uploading = false
                                        onUnavailableAction("No permission: ${result.blocker.code}")
                                    }
                                }
                            }
                        }
                    },
                    enabled = !uploading,
                    modifier = Modifier.weight(1f)
                )
                if (member.avatarUrl != null || member.avatar != null) {
                    SlockBrutalActionButton(
                        label = "Clear",
                        leadingIcon = SlockLucideIconName.X,
                        onClick = onClear,
                        modifier = Modifier.weight(1f),
                        backgroundColor = SlockColor.AccentPink
                    )
                }
            }
        }
    }
}

@Composable
private fun MembersAgentProfileTab(
    member: MemberProfile,
    detail: AgentDetailSnapshot?,
    canManage: Boolean,
    onUpdateAgentProfile: (suspend (AgentProfileUpdateRequest) -> DataResult<MemberProfile>)?,
    onUnavailableAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val summary = detail?.summary
    val scope = rememberCoroutineScope()
    var draftDisplayName by remember(member.id.value, member.displayName) { mutableStateOf(member.displayName) }
    var draftDescription by remember(member.id.value, member.description) { mutableStateOf(member.description.orEmpty()) }
    var actionLabel by remember(member.id.value) { mutableStateOf<String?>(null) }
    val deleted = summary?.deletedAt?.isNotBlank() == true
    val saving = actionLabel == "Saving"

    fun saveProfile(avatarUrl: String? = null, includeAvatarUrl: Boolean = false) {
        if (!canManage || onUpdateAgentProfile == null || saving) {
            onUnavailableAction("Agent profile editing is not available.")
            return
        }
        val displayName = draftDisplayName.trim()
        if (displayName.isBlank()) {
            actionLabel = "Display name is required"
            return
        }
        scope.launch {
            actionLabel = "Saving"
            actionLabel =
                when (val result = onUpdateAgentProfile(
                    AgentProfileUpdateRequest(
                        agentId = member.id,
                        displayName = displayName,
                        description = draftDescription.trim().takeIf { it.isNotBlank() },
                        avatarUrl = avatarUrl,
                        includeAvatarUrl = includeAvatarUrl
                    )
                )) {
                    is DataResult.Content -> "Saved"
                    is DataResult.Empty -> "Nothing changed"
                    is DataResult.NoPermission -> "No permission: ${result.blocker.code}"
                    is DataResult.Error -> "Save failed: ${result.error.code}"
                }
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
        if (deleted) {
            MembersDetailSection(title = "DELETED") {
                MembersDetailField("Status", "Deleted")
                MembersDetailField("Deleted at", summary.deletedAt.orDash())
            }
        }
        MembersDetailSection(title = "PROFILE") {
            MembersDetailField("Agent ID", member.id.value)
            MembersDetailField("Name", summary?.name?.takeIf { it.isNotBlank() } ?: member.handle)
            MembersDetailField("Handle", member.detailHandle)
            MembersDetailField("Creator", summary?.creatorLabel.orDash())
            if (canManage && !deleted) {
                SlockBrutalTextField(
                    label = "Display name",
                    value = draftDisplayName,
                    placeholder = member.displayName,
                    onValueChange = { draftDisplayName = it },
                    enabled = !saving,
                    required = true
                )
                SlockBrutalTextField(
                    label = "Description",
                    value = draftDescription,
                    placeholder = "Describe this agent",
                    onValueChange = { draftDescription = it },
                    enabled = !saving,
                    maxLines = 4,
                    fieldMinHeight = 86.dp
                )
                SlockBrutalActionButton(
                    label = actionLabel ?: "Save profile",
                    leadingIcon = if (saving) SlockLucideIconName.Loader else SlockLucideIconName.Check,
                    onClick = { saveProfile() },
                    enabled = !saving,
                    backgroundColor = SlockColor.BrandYellow,
                    minHeight = 40.dp
                )
            } else {
                MembersDetailField("Display name", member.displayName)
                MembersDetailField("Description", member.description?.takeIf { it.isNotBlank() } ?: "No description available.")
            }
        }
        // Avatar editing is surfaced from the top-left avatar in `MembersDetailHeader`
        // (PUX #49 spec v4 lock). Clicking it opens the floating `MembersAgentAvatarPicker`
        // overlay, owned by `MembersDetailContent`. The body AVATAR section + the in-page
        // picker invocation that previously lived here were removed in this commit so the
        // avatar entry point is consistent with React `AgentDetailPanel.tsx`.
        MembersDetailSection(title = "RUNTIME") {
            MembersDetailField("Status", if (deleted) "Deleted" else summary?.status?.toMembersDisplayLabel() ?: member.detailStatusLabel)
            MembersDetailField("Runtime", summary?.runtime.orBadgeFallback(member.roleDescription))
            MembersDetailField("Model", summary?.model.orDash())
            MembersDetailField("Reasoning", summary?.reasoning.orDash())
            MembersDetailField("Execution", summary?.executionMode.orDash())
            MembersDetailField("Machine", summary?.machineName.orBadgeFallback(member.membersComputerName))
            MembersDetailField("Machine ID", summary?.machineId.orBadgeFallback(member.membersComputerId))
            MembersDetailField("Daemon", summary?.daemonVersion.orDash())
        }
        MembersDetailSection(title = "ENV VARS") {
            if (summary?.envVarNames.orEmpty().isEmpty()) {
                MembersDetailField("Variables", "No environment variables exposed.")
            } else {
                summary?.envVarNames.orEmpty().forEach { name ->
                    MembersDetailField(name, "Configured")
                }
            }
        }
        MembersAgentSkillsSection(detail)
        MembersAgentCreatedAgentsSection(detail)
    }
}

@Composable
private fun MembersAgentSkillsSection(detail: AgentDetailSnapshot?) {
    MembersDetailSection(title = "SKILLS") {
        val groups = detail?.skills?.groups.orEmpty()
        if (groups.isEmpty()) {
            MembersDetailField("Skills", "No skills exposed.")
        } else {
            groups.forEach { group ->
                MembersDetailField(group.title, "${group.count} skills")
                group.paths.flatMap { it.skills }.take(4).forEach { skill ->
                    MembersDetailField(skill.name, skill.detail ?: "Available")
                }
            }
        }
    }
}

@Composable
private fun MembersAgentCreatedAgentsSection(detail: AgentDetailSnapshot?) {
    MembersDetailSection(title = "CREATED AGENTS") {
        val createdAgents = detail?.summary?.createdAgents.orEmpty()
        if (createdAgents.isEmpty()) {
            MembersDetailField("Agents", "No created agents exposed.")
        } else {
            createdAgents.take(6).forEach { agent ->
                MembersDetailField(
                    agent.displayName?.takeIf { it.isNotBlank() } ?: agent.name,
                    listOfNotNull(agent.runtime, agent.status?.toMembersDisplayLabel()).joinToString(" · ").ifBlank { agent.id }
                )
            }
        }
    }
}

@Composable
private fun MembersAgentDmsTab(detail: AgentDetailSnapshot?, modifier: Modifier = Modifier) {
    MembersDetailSection(title = "AGENT DMS", modifier = modifier) {
        val rows = detail?.directMessages.orEmpty()
        if (rows.isEmpty()) {
            MembersDetailField("DMs", "No agent-to-agent DMs yet.")
        } else {
            rows.take(8).forEach { row ->
                MembersDetailField(row.peerName, row.lastMessagePreview ?: row.lastMessageAt ?: row.id)
            }
        }
    }
}

@Composable
private fun MembersAgentRemindersTab(detail: AgentDetailSnapshot?, modifier: Modifier = Modifier) {
    MembersDetailSection(title = "REMINDERS", modifier = modifier) {
        val rows = detail?.reminders.orEmpty()
        if (rows.isEmpty()) {
            MembersDetailField("Reminders", "No scheduled reminders.")
        } else {
            rows.take(8).forEach { reminder ->
                MembersDetailField(reminder.title, listOfNotNull(reminder.fireAt, reminder.status).joinToString(" · "))
            }
        }
    }
}

@Composable
private fun MembersAgentWorkspaceTab(
    treeState: MembersAgentWorkspaceTreeState,
    expandedDirs: Set<String>,
    loadedDirs: Map<String, List<SlockWorkspaceNode>>,
    loadingDirs: Set<String>,
    dirErrors: Map<String, String>,
    fileState: MembersAgentWorkspaceFileState,
    viewMode: MembersWorkspaceViewMode,
    onRefresh: () -> Unit,
    onToggleDirectory: (String) -> Unit,
    onSelectFile: (String) -> Unit,
    onBackToTree: () -> Unit,
    onViewModeChange: (MembersWorkspaceViewMode) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    MembersDetailSection(title = "WORKSPACE", modifier = modifier) {
        when (treeState) {
            MembersAgentWorkspaceTreeState.Idle,
            MembersAgentWorkspaceTreeState.Loading ->
                MembersDetailField("State", "Loading workspace files...")
            MembersAgentWorkspaceTreeState.Empty ->
                MembersWorkspaceEmptyState("No files yet.", onRefresh)
            is MembersAgentWorkspaceTreeState.Error ->
                MembersWorkspaceErrorState("Failed to load files: ${treeState.code}", onRefresh)
            is MembersAgentWorkspaceTreeState.NoPermission ->
                MembersWorkspaceErrorState("Workspace hidden: ${treeState.code}", onRefresh)
            is MembersAgentWorkspaceTreeState.Content -> {
                val selectedPath = fileState.membersWorkspaceSelectedPath()
                if (selectedPath != null) {
                    MembersWorkspaceFileViewer(
                        fileState = fileState,
                        viewMode = viewMode,
                        onBackToTree = onBackToTree,
                        onViewModeChange = onViewModeChange,
                        onOpenUrl = onOpenUrl
                    )
                } else {
                    MembersWorkspaceTreeView(
                        root = treeState.snapshot.root,
                        rootPath = treeState.snapshot.rootPath,
                        expandedDirs = expandedDirs,
                        loadedDirs = loadedDirs,
                        loadingDirs = loadingDirs,
                        dirErrors = dirErrors,
                        onRefresh = onRefresh,
                        onToggleDirectory = onToggleDirectory,
                        onSelectFile = onSelectFile
                    )
                }
            }
        }
    }
}

@Composable
private fun MembersWorkspaceTreeView(
    root: SlockWorkspaceNode,
    rootPath: String,
    expandedDirs: Set<String>,
    loadedDirs: Map<String, List<SlockWorkspaceNode>>,
    loadingDirs: Set<String>,
    dirErrors: Map<String, String>,
    onRefresh: () -> Unit,
    onToggleDirectory: (String) -> Unit,
    onSelectFile: (String) -> Unit
) {
    val rows = remember(root, expandedDirs, loadedDirs) {
        membersWorkspaceRows(root.children, expandedDirs, loadedDirs)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = rootPath,
            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        SlockBrutalActionButton(
            label = "Refresh",
            leadingIcon = SlockLucideIconName.RefreshCw,
            enabled = true,
            modifier = Modifier.width(92.dp),
            onClick = onRefresh
        )
    }
    if (rows.isEmpty()) {
        MembersDetailField("Files", "No files yet.")
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 280.dp, max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        beyondBoundsItemCount = sharedListBeyondBoundsItemCount(rows.size)
    ) {
        items(rows.size, key = { "workspace-${rows[it].node.path}" }) { index ->
            val row = rows[index]
            MembersWorkspaceNodeRow(
                row = row,
                expanded = row.node.path in expandedDirs,
                loading = row.node.path in loadingDirs,
                errorCode = dirErrors[row.node.path],
                onToggleDirectory = onToggleDirectory,
                onSelectFile = onSelectFile
            )
        }
    }
}

@Composable
private fun MembersWorkspaceNodeRow(
    row: MembersWorkspaceFlatNode,
    expanded: Boolean,
    loading: Boolean,
    errorCode: String?,
    onToggleDirectory: (String) -> Unit,
    onSelectFile: (String) -> Unit
) {
    val node = row.node
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 30.dp)
                .clickable {
                    if (node.kind == SlockWorkspaceFileKind.Folder) {
                        onToggleDirectory(node.path)
                    } else {
                        onSelectFile(node.path)
                    }
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Spacer(Modifier.width((row.depth * 14).dp))
            if (node.kind == SlockWorkspaceFileKind.Folder) {
                SlockLucideIcon(
                    if (expanded) SlockLucideIconName.ChevronDown else SlockLucideIconName.ChevronRight,
                    Modifier.size(13.dp),
                )
            } else {
                Spacer(Modifier.width(13.dp))
            }
            SlockLucideIcon(node.membersWorkspaceIconName(expanded), Modifier.size(14.dp))
            Text(
                text = node.name.ifBlank { node.path },
                style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 17.sp)
                    .withSlockFontWeight(if (node.name == "memory.md") FontWeight.Bold else FontWeight.Normal),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            node.meta?.takeIf { it.isNotBlank() }?.let { meta ->
                Text(
                    text = meta,
                    style = SlockType.Caption.copy(fontSize = 9.sp, lineHeight = 12.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1
                )
            }
            if (loading) {
                Text(
                    text = "Loading",
                    style = SlockType.Caption.copy(fontSize = 9.sp, lineHeight = 12.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1
                )
            }
        }
        errorCode?.let {
            Text(
                text = "Failed to load: $it",
                style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = SlockColor.AccentOrange,
                modifier = Modifier.padding(start = ((row.depth + 1) * 14).dp)
            )
        }
    }
}

@Composable
private fun MembersWorkspaceFileViewer(
    fileState: MembersAgentWorkspaceFileState,
    viewMode: MembersWorkspaceViewMode,
    onBackToTree: () -> Unit,
    onViewModeChange: (MembersWorkspaceViewMode) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val path = fileState.membersWorkspaceSelectedPath().orEmpty()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        SlockBrutalActionButton(
            label = "Files",
            leadingIcon = SlockLucideIconName.ArrowLeft,
            enabled = true,
            modifier = Modifier.width(96.dp),
            onClick = onBackToTree
        )
        Text(
            text = path,
            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
    when (fileState) {
        is MembersAgentWorkspaceFileState.Loading ->
            MembersDetailField("Preview", "Loading file...")
        is MembersAgentWorkspaceFileState.Error ->
            MembersDetailField("Preview", "Failed to load: ${fileState.code}")
        is MembersAgentWorkspaceFileState.NoPermission ->
            MembersDetailField("Preview", "File hidden: ${fileState.code}")
        is MembersAgentWorkspaceFileState.Empty ->
            MembersDetailField("Preview", "File content is unavailable.")
        MembersAgentWorkspaceFileState.None -> Unit
        is MembersAgentWorkspaceFileState.Content ->
            MembersWorkspacePreviewContent(
                preview = fileState.snapshot.preview,
                viewMode = viewMode,
                onViewModeChange = onViewModeChange,
                onOpenUrl = onOpenUrl
            )
    }
}

@Composable
private fun MembersWorkspacePreviewContent(
    preview: SlockWorkspaceFilePreview,
    viewMode: MembersWorkspaceViewMode,
    onViewModeChange: (MembersWorkspaceViewMode) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    when (preview) {
        is SlockWorkspaceFilePreview.Text -> {
            if (preview.markdown) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
                    SlockBrutalActionButton(
                        label = "Raw",
                        leadingIcon = SlockLucideIconName.FileCode,
                        enabled = true,
                        modifier = Modifier.weight(1f),
                        onClick = { onViewModeChange(MembersWorkspaceViewMode.Raw) }
                    )
                    SlockBrutalActionButton(
                        label = "Preview",
                        leadingIcon = SlockLucideIconName.Eye,
                        enabled = true,
                        modifier = Modifier.weight(1f),
                        onClick = { onViewModeChange(MembersWorkspaceViewMode.Preview) }
                    )
                }
            }
            Text(
                text = preview.membersWorkspaceMetaLine(),
                style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 280.dp, max = 560.dp)
                    .background(SlockColor.SurfaceCream)
                    .border(SlockStroke.Thin, SlockColor.BorderBlack)
                    .padding(SlockSpacing.Sm)
            ) {
                if (preview.markdown && viewMode == MembersWorkspaceViewMode.Preview) {
                    SlockMarkdownBody(
                        body = preview.content,
                        textStyle = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 17.sp),
                        onOpenUrl = onOpenUrl
                    )
                } else {
                    Text(
                        text = preview.content,
                        style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                        color = SlockColor.TextBlack,
                        maxLines = 28,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        is SlockWorkspaceFilePreview.Image -> {
            Text(
                text = preview.membersWorkspaceMetaLine(),
                style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (preview.imageUrl != null) {
                SlockCachedImage(
                    source = preview.imageUrl,
                    contentDescription = preview.path,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 420.dp)
                        .border(SlockStroke.Thin, SlockColor.BorderBlack)
                        .background(SlockColor.SurfaceWhite)
                )
            } else {
                MembersDetailField("Image", preview.description ?: "Image preview is unavailable.")
            }
        }
        is SlockWorkspaceFilePreview.Unsupported ->
            MembersDetailField("Unsupported", preview.reason)
        is SlockWorkspaceFilePreview.Error ->
            MembersDetailField("Error", preview.reason)
        is SlockWorkspaceFilePreview.Loading ->
            MembersDetailField("Preview", preview.detail)
    }
}

@Composable
private fun MembersWorkspaceEmptyState(message: String, onRefresh: () -> Unit) {
    MembersDetailField("Files", message)
    SlockBrutalActionButton(
        label = "Retry",
        leadingIcon = SlockLucideIconName.RefreshCw,
        enabled = true,
        modifier = Modifier.fillMaxWidth(),
        onClick = onRefresh
    )
}

@Composable
private fun MembersWorkspaceErrorState(message: String, onRefresh: () -> Unit) {
    MembersDetailField("State", message)
    SlockBrutalActionButton(
        label = "Retry",
        leadingIcon = SlockLucideIconName.RefreshCw,
        enabled = true,
        modifier = Modifier.fillMaxWidth(),
        onClick = onRefresh
    )
}

@Composable
private fun MembersAgentAppsTab(detail: AgentDetailSnapshot?, modifier: Modifier = Modifier) {
    MembersDetailSection(title = "APPS", modifier = modifier) {
        val groups = detail?.skills?.groups.orEmpty()
        if (groups.isEmpty()) {
            MembersDetailField("Apps", "No connected apps exposed.")
        } else {
            groups.forEach { group ->
                MembersDetailField(group.title, "${group.count} available")
            }
        }
    }
}

@Composable
private fun MembersAgentActivityTab(
    member: MemberProfile,
    detail: AgentDetailSnapshot?,
    diagnosticActionState: MembersAgentDiagnosticActionState?,
    onCopyDiagnostics: () -> Unit,
    timeContext: SlockTimeContext,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    MembersDetailSection(title = "ACTIVITY", modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = member.membersResolvedAgentStatus(detail?.summary?.status, detail?.summary?.activity)?.label
                    ?: member.detailStatusLabel,
                style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            SlockBrutalActionButton(
                label = diagnosticActionState?.takeIf { it.actionKey == "copy" }?.successLabel ?: "Copy Diagnostic Info",
                leadingIcon = SlockLucideIconName.Clipboard,
                enabled = diagnosticActionState?.inProgress != true,
                modifier = Modifier.width(168.dp),
                onClick = onCopyDiagnostics
            )
        }
        val rows =
            remember(detail?.agentId, detail?.activity) {
                detail?.activity.orEmpty()
                    .filterNot { it.type == "tool_start" && it.toolName?.normalizedMembersToolName() == "send_message" }
                    .sortedWith(compareBy<AgentActivityEntry> { it.timestamp.membersActivitySortKey() }.thenBy { it.id })
            }
        if (rows.isEmpty()) {
            MembersDetailField("Activity", "No activity yet. Start the agent to see its activity log.")
        } else {
            val listState = rememberLazyListState()
            var allowBottomAnchor by remember(detail?.agentId) { mutableStateOf(true) }
            LaunchedEffect(detail?.agentId, rows.size) {
                snapshotFlow {
                    listState.isScrollInProgress to listState.isMembersActivityAtBottom(rows.lastIndex)
                }
                    .distinctUntilChanged()
                    .collect { (isScrollInProgress, isAtBottom) ->
                        val nextAllow =
                            when {
                                isScrollInProgress && !isAtBottom -> false
                                isAtBottom -> true
                                else -> allowBottomAnchor
                            }
                        if (allowBottomAnchor != nextAllow) {
                            allowBottomAnchor = nextAllow
                        }
                    }
            }
            LaunchedEffect(detail?.agentId, rows.size, rows.lastOrNull()?.activityTimelineKey()) {
                if (rows.isNotEmpty() && allowBottomAnchor && !listState.isScrollInProgress) {
                    listState.scrollToItem(rows.lastIndex)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                beyondBoundsItemCount = sharedListBeyondBoundsItemCount(rows.size)
            ) {
                items(rows.size, key = { rows[it].activityTimelineKey() }) { index ->
                    MembersAgentActivityRow(rows[index], timeContext, onOpenUrl)
                }
            }
        }
    }
}

@Composable
private fun MembersAgentActivityRow(
    activity: AgentActivityEntry,
    timeContext: SlockTimeContext,
    onOpenUrl: (String) -> Unit
) {
    val key = activity.activityTimelineKey()
    var expanded by remember(key) { mutableStateOf(false) }
    val body = activity.activityPrimaryDetail().orEmpty()
    val longBody = body.length > 200 || body.count { it == '\n' } > 2
    val displayBody =
        if (!expanded && longBody) {
            body.take(200).trimEnd() + "…"
        } else {
            body
        }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .padding(SlockSpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
            text = activity.timestamp.membersActivityDisplayTime(timeContext),
            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            modifier = Modifier.width(74.dp)
        )
        SlockStatusIndicator(
            color = (activity.statusActivity ?: activity.type).membersActivityAccentColor(),
            size = 9.dp,
            pulsing = (activity.statusActivity ?: activity.type).membersActivityPulsing(),
            modifier = Modifier.padding(top = 5.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = activity.title,
                    style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 17.sp)
                        .withSlockFontWeight(FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = activity.type.membersActivityTypeLabel(),
                    style = SlockType.Caption.copy(fontSize = 9.sp, lineHeight = 12.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1
                )
            }
            if (displayBody.isNotBlank()) {
                if (activity.refTextEnabled) {
                    SlockMarkdownBody(
                        body = displayBody,
                        textStyle = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        onOpenUrl = onOpenUrl,
                    )
                } else {
                    Text(
                        text = displayBody,
                        style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        color = SlockColor.TextMutedGray,
                        maxLines = if (expanded) 12 else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (longBody) {
                Text(
                    text = if (expanded) "Show less" else "Show more",
                    style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp)
                        .withSlockFontWeight(FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    modifier = Modifier.clickable { expanded = !expanded }
                )
            }
        }
    }
}

@Composable
private fun MembersAgentPlaceholderTab(title: String, detail: String, modifier: Modifier = Modifier) {
    MembersDetailSection(title = title, modifier = modifier) {
        MembersDetailField("State", detail)
    }
}

@Composable
private fun MemberKindBadge(kind: MemberKind) {
    SlockBrutalTag(
        label = kind.name.uppercase(),
        backgroundColor = kind.membersAvatarColor,
        borderColor = SlockColor.BorderBlack,
        textColor = SlockColor.TextBlack
    )
}

@Composable
private fun MembersDetailActionButton(
    label: String,
    iconName: SlockLucideIconName,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SlockBrutalActionButton(
        label = label,
        leadingIcon = iconName,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        backgroundColor = SlockColor.AccentPink
    )
}

@Composable
private fun MembersDetailSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
        Text(
            text = title,
            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .background(SlockColor.SurfaceWhite)
                .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            content()
        }
    }
}

@Composable
private fun MembersDetailField(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
            text = label.uppercase(),
            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            modifier = Modifier.width(86.dp)
        )
        Text(
            text = value.ifBlank { "—" },
            style = SlockType.Body.withSlockFontWeight(FontWeight.Normal).copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = SlockColor.TextBlack,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MembersPageHeader(title: String) {
    SlockTopPageHeader(
        title = title,
        subtitle = "Workspace members",
        iconName = SlockLucideIconName.Users
    )
}

@Composable
private fun MembersListRow(
    member: MemberProfile,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MemberRowMinHeight)
            .clickable(onClick = onClick)
            .padding(vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        MembersAvatar(
            member = member,
            showStatusOverlay = false
        )
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Text(
                text = member.displayName,
                style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 19.sp),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.5f, fill = false)
            )
            Text(
                text = member.description ?: member.handle,
                style = SlockType.Body.withSlockFontWeight(FontWeight.Normal).copy(fontSize = 14.sp, lineHeight = 19.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        if (member.kind == MemberKind.Agent) {
            SlockStatusIndicator(
                color = member.agentStatus.membersStatusColor(member.presence),
                size = MemberStatusDotSize,
                pulsing = member.agentStatus.membersStatusPulsing()
            )
        }
    }
}

@Composable
private fun MembersAvatar(
    member: MemberProfile,
    avatarSize: Dp = MemberAvatarSize,
    statusSize: Dp = MemberStatusDotSize,
    statusPulsing: Boolean = member.agentStatus.membersStatusPulsing(),
    showStatusOverlay: Boolean = true,
    modifier: Modifier = Modifier
) {
    val avatarWireValue = remember(member.avatar, member.avatarUrl, member.gravatarHash, member.kind, member.id) {
        member.avatarWireValueForAvatarSlot()
    }
    SlockAvatarWithStatus(
        modifier = modifier,
        statusColor =
            if (showStatusOverlay && member.kind == MemberKind.Agent) {
                member.agentStatus.membersStatusColor(member.presence)
            } else {
                null
            },
        avatarSize = avatarSize,
        statusSize = statusSize,
        statusPulsing = statusPulsing
    ) {
        SlockAvatar(
            avatarWireValue = avatarWireValue,
            label = member.avatarLabel,
            avatarSize = avatarSize
        )
    }
}

private val MemberKind.membersAvatarColor: Color
    get() = when (this) {
        MemberKind.Human -> SlockColor.AccentLavender
        MemberKind.Agent -> SlockColor.AccentPink
        MemberKind.System -> SlockColor.AccentCyan
    }

private fun AgentStatus?.membersStatusColor(presence: Presence): Color =
    when (this) {
        AgentStatus.Online -> SlockColor.AccentLime
        AgentStatus.Working -> SlockColor.BrandYellow
        AgentStatus.Thinking -> SlockColor.BrandYellow
        AgentStatus.Idle -> SlockColor.AccentLime
        AgentStatus.Disconnected,
        AgentStatus.Offline -> SlockColor.EmptyStateGray
        AgentStatus.Error -> SlockColor.AccentOrange
        null -> presence.membersPresenceColor
    }

private fun AgentStatus?.membersStatusPulsing(): Boolean =
    this == AgentStatus.Working || this == AgentStatus.Thinking

private val Presence.membersPresenceColor: Color
    get() = when (this) {
        Presence.Active -> SlockColor.AccentLime
        Presence.Away -> SlockColor.BrandYellow
        Presence.Offline -> SlockColor.EmptyStateGray
    }

private val MemberProfile.detailHandle: String
    get() = handle.takeIf { it.isNotBlank() }?.let { if (it.startsWith("@")) it else "@$it" } ?: id.value

private val MemberProfile.detailRoleLabel: String
    get() =
        roleDescription?.takeIf { it.isNotBlank() }
            ?: when (kind) {
                MemberKind.Agent -> "Agent"
                MemberKind.Human -> "Human member"
                MemberKind.System -> "System"
            }

private val MemberProfile.detailStatusLabel: String
    get() =
        when (kind) {
            MemberKind.Agent -> agentStatus?.label ?: presence.name
            MemberKind.Human -> "Human"
            MemberKind.System -> "System"
        }

private fun MemberProfile.membersResolvedAgentStatus(
    summaryStatus: String?,
    summaryActivity: String?
): AgentStatus? =
    AgentStatus.fromWireValue(summaryActivity)
        ?: AgentStatus.fromWireValue(summaryStatus)
        ?: agentStatus

internal fun buildMembersAgentDiagnostics(
    member: MemberProfile,
    detail: AgentDetailSnapshot?
): String {
    val summary = detail?.summary
    return listOf(
        "Raft agent diagnostics",
        "agentId=${member.id.value}",
        "displayName=${member.displayName}",
        "handle=${member.detailHandle}",
        "status=${member.membersResolvedAgentStatus(summary?.status, summary?.activity)?.label ?: member.detailStatusLabel}",
        "activity=${summary?.activity.orDash()}",
        "activityDetail=${summary?.activityDetail.orDash()}",
        "runtime=${summary?.runtime.orDash()}",
        "model=${summary?.model.orDash()}",
        "machineId=${summary?.machineId.orBadgeFallback(member.membersComputerId)}",
        "machineName=${summary?.machineName.orBadgeFallback(member.membersComputerName)}",
        "daemon=${summary?.daemonVersion.orDash()}",
        "deleted=${summary?.deletedAt?.isNotBlank() == true}"
    ).joinToString("\n")
}

private val ai.slock.compose.data.AgentDetailSummary?.creatorLabel: String?
    get() =
        this?.creatorId?.takeIf { it.isNotBlank() }?.let { creatorId ->
            listOfNotNull(creatorType?.takeIf { it.isNotBlank() }?.toMembersDisplayLabel(), creatorId)
                .joinToString(": ")
        }

private val MemberProfile.membersComputerName: String?
    get() =
        permissionBadges.firstNotNullOfOrNull { badge ->
            badge.removePrefix("computer:")
                .takeIf { badge.startsWith("computer:") && it.isNotBlank() }
        }

private val MemberProfile.membersComputerId: String?
    get() =
        permissionBadges.firstNotNullOfOrNull { badge ->
            badge.removePrefix("computer-id:")
                .takeIf { badge.startsWith("computer-id:") && it.isNotBlank() }
        }

private fun String?.orDash(): String = this?.takeIf { it.isNotBlank() } ?: "—"

private fun String?.orBadgeFallback(fallback: String?): String =
    this?.takeIf { it.isNotBlank() } ?: fallback?.takeIf { it.isNotBlank() } ?: "—"

private fun String.toMembersDisplayLabel(): String =
    replace("_", " ")
        .replace("-", " ")
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

private fun AgentActivityEntry.activityPrimaryDetail(): String? =
    body?.takeIf { it.isNotBlank() }
        ?: statusDetail?.takeIf { it.isNotBlank() }
        ?: details.firstOrNull()?.let { (_, value) -> value.takeIf { it.isNotBlank() } }

private fun AgentActivityEntry.activityTimelineKey(): String =
    id.ifBlank { "${type}:${timestamp.orEmpty()}:${body.orEmpty()}:${toolName.orEmpty()}" }

private data class MembersWorkspaceFlatNode(
    val node: SlockWorkspaceNode,
    val depth: Int
)

private fun membersWorkspaceRows(
    nodes: List<SlockWorkspaceNode>,
    expandedDirs: Set<String>,
    loadedDirs: Map<String, List<SlockWorkspaceNode>>
): List<MembersWorkspaceFlatNode> =
    buildList {
        fun visit(current: List<SlockWorkspaceNode>, depth: Int) {
            current.forEach { node ->
                add(MembersWorkspaceFlatNode(node, depth))
                if (node.kind == SlockWorkspaceFileKind.Folder && node.path in expandedDirs) {
                    visit(loadedDirs[node.path] ?: node.children, depth + 1)
                }
            }
        }
        visit(nodes, 0)
    }

private fun SlockWorkspaceNode.membersWorkspaceIconName(expanded: Boolean): String =
    when (kind) {
        SlockWorkspaceFileKind.Folder -> if (expanded) "folder-open" else "folder"
        SlockWorkspaceFileKind.Markdown,
        SlockWorkspaceFileKind.Text -> "file-text"
        SlockWorkspaceFileKind.Image -> "image"
        SlockWorkspaceFileKind.Unsupported -> "file"
    }

private fun MembersAgentWorkspaceFileState.membersWorkspaceSelectedPath(): String? =
    when (this) {
        MembersAgentWorkspaceFileState.None -> null
        is MembersAgentWorkspaceFileState.Loading -> path
        is MembersAgentWorkspaceFileState.Content -> snapshot.path
        is MembersAgentWorkspaceFileState.Error -> path
        is MembersAgentWorkspaceFileState.NoPermission -> path
        is MembersAgentWorkspaceFileState.Empty -> path
    }

private fun SlockWorkspaceFilePreview.Text.membersWorkspaceMetaLine(): String =
    listOfNotNull(
        mimeType?.takeIf { it.isNotBlank() },
        sizeBytes.membersWorkspaceSizeLabel(),
        modifiedAt?.takeIf { it.isNotBlank() }
    ).joinToString(" · ").ifBlank { path }

private fun SlockWorkspaceFilePreview.Image.membersWorkspaceMetaLine(): String =
    listOfNotNull(
        mimeType?.takeIf { it.isNotBlank() },
        sizeBytes.membersWorkspaceSizeLabel(),
        modifiedAt?.takeIf { it.isNotBlank() }
    ).joinToString(" · ").ifBlank { path }

private fun Long?.membersWorkspaceSizeLabel(): String? {
    val bytes = this ?: return null
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}

private fun String?.membersActivitySortKey(): Long =
    parseSlockTimestampEpochMillisOrNull(this) ?: Long.MIN_VALUE

private fun KuiklyMembersPayload.toSlockTimeContext(): SlockTimeContext =
    SlockTimeContext(
        localOffsetMinutes = localOffsetMinutes,
        todayEpochDay = todayEpochDay,
        nowEpochMillis = nowEpochMillis,
        timeFormat = timeFormat
    )

private fun String?.membersActivityDisplayTime(timeContext: SlockTimeContext): String =
    formatSlockTimestampClockWithSeconds(this, timeContext)
        .takeIf { it.isNotBlank() }
        ?: "No time"

private fun String.membersActivityTypeLabel(): String =
    when (this) {
        "tool_start" -> "Tool"
        "slock_action" -> "Action"
        "compaction_started",
        "compaction_finished" -> "Compaction"
        else -> toMembersDisplayLabel().ifBlank { "Activity" }
    }

private fun String.membersActivityAccentColor(): Color =
    when (membersActivityStatusKey()) {
        "error", "failed", "failure" -> SlockColor.AccentOrange
        "tool_start", "tool", "thinking", "working", "busy" -> SlockColor.BrandYellow
        "running", "active", "online", "compaction_finished" -> SlockColor.AccentLime
        "text", "message", "output" -> SlockColor.AccentCyan
        "offline", "disconnected" -> SlockColor.EmptyStateGray
        "system", "slock_action", "compaction_started" -> SlockColor.AccentOrange
        else -> SlockColor.BrandYellow
    }

private fun String.membersActivityPulsing(): Boolean =
    when (membersActivityStatusKey()) {
        "tool_start",
        "tool",
        "thinking",
        "working",
        "running",
        "busy" -> true
        else -> false
    }

private fun String.membersActivityStatusKey(): String =
    trim().lowercase().replace('-', '_')

private fun String.normalizedMembersToolName(): String =
    trim()
        .substringAfterLast("__")
        .substringAfterLast("/")
        .substringAfterLast(".")
        .ifBlank { trim() }

private fun LazyListState.isMembersActivityAtBottom(lastIndex: Int): Boolean {
    if (lastIndex <= 0) return true
    if (!canScrollForward) return true
    return layoutInfo.visibleItemsInfo.any { item ->
        item.index == lastIndex && item.offset + item.size <= layoutInfo.viewportEndOffset + 4
    }
}

private data class MembersAgentGroup(
    val label: String,
    val members: List<MemberProfile>
)

private fun groupedAgentMembers(members: List<MemberProfile>): List<MembersAgentGroup> =
    members
        .groupBy { member ->
            member.permissionBadges
                .firstNotNullOfOrNull { badge ->
                    badge.removePrefix("computer:")
                        .takeIf { badge.startsWith("computer:") && it.isNotBlank() }
                } ?: "no computer"
        }
        .map { (label, groupedMembers) -> MembersAgentGroup(label, groupedMembers) }
