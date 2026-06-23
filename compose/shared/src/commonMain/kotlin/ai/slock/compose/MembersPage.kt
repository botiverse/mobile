package ai.slock.compose

import ai.slock.compose.base.Utils
import ai.slock.compose.base.BridgeModule
import ai.slock.compose.components.SlockBrutalActionButton
import ai.slock.compose.components.SlockBrutalIconButton
import ai.slock.compose.components.SlockBrutalModalLayer
import ai.slock.compose.components.SlockBrutalTextField
import ai.slock.compose.components.SlockModalShellModel
import ai.slock.compose.PixelAvatarDefaultKey
import ai.slock.compose.PixelAvatarPickerKeys
import ai.slock.compose.components.SlockBrutalCard
import ai.slock.compose.data.AttachmentUploadFile
import ai.slock.compose.data.AgentActivityEntry
import ai.slock.compose.data.AgentAvatarUploadRequest
import ai.slock.compose.data.AgentCreatedSummary
import ai.slock.compose.data.AgentDetailSnapshot
import ai.slock.compose.data.AgentDetailSummary
import ai.slock.compose.data.AgentDmSummary
import ai.slock.compose.data.AgentLifecycleActionResult
import ai.slock.compose.data.AgentMachineAssignmentRequest
import ai.slock.compose.data.AgentReminderSummary
import ai.slock.compose.data.AgentResetMode
import ai.slock.compose.data.AgentSkillGroup
import ai.slock.compose.data.AgentSkillPathGroup
import ai.slock.compose.data.AgentSkillSummary
import ai.slock.compose.data.AgentSkillsSnapshot
import ai.slock.compose.data.AgentWorkspaceChildrenSnapshot
import ai.slock.compose.data.AgentWorkspaceTreeSnapshot
import ai.slock.compose.data.AgentProfileUpdateRequest
import ai.slock.compose.data.BridgeSettingsPlatformCapabilities
import ai.slock.compose.data.DataRequestPriority
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.EmptyReason
import ai.slock.compose.data.MemberRepository
import ai.slock.compose.data.SharedAgentDetailActions
import ai.slock.compose.data.SharedAgentDetailIntent
import ai.slock.compose.data.SharedAgentDetailLoadStatus
import ai.slock.compose.data.SharedAgentDetailState
import ai.slock.compose.data.SharedAgentDetailStore
import ai.slock.compose.data.SlockAuthSessionStore
import ai.slock.compose.data.KuiklySlockNetworkClient
import ai.slock.compose.data.parseSharedThreadPickedAttachmentFiles
import ai.slock.compose.data.RealMemberRepository
import ai.slock.compose.data.ScreenError
import ai.slock.compose.data.SlockAgentsApiClient
import ai.slock.compose.data.SlockAgentsNetworkApi
import ai.slock.compose.data.SlockComputersApiClient
import ai.slock.compose.data.SlockComputersNetworkApi
import ai.slock.compose.data.SharedComputersIntent
import ai.slock.compose.data.SharedComputersLoadStatus
import ai.slock.compose.data.SharedComputersPickerSlice
import ai.slock.compose.data.SharedComputersStore
import ai.slock.compose.markdown.SlockMarkdownBody
import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.AvatarReference
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Presence
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import ai.slock.compose.model.SlockWorkspaceFileKind
import ai.slock.compose.model.SlockWorkspaceNode
import ai.slock.compose.model.SlockTimeContext
import ai.slock.compose.model.SlockTimeFormat12Hour
import ai.slock.compose.model.SlockTimeFormat24Hour
import ai.slock.compose.model.avatarWireValueForAvatarSlot
import ai.slock.compose.model.formatSlockTimestampClockWithSeconds
import ai.slock.compose.model.parseSlockTimestampEpochMillisOrNull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.tencent.kuikly.compose.foundation.layout.ExperimentalLayoutApi
import com.tencent.kuikly.compose.foundation.layout.FlowRow
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
import com.tencent.kuikly.compose.extension.NestedScrollMode
import com.tencent.kuikly.compose.extension.nestedScroll
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.animation.core.RepeatMode
import com.tencent.kuikly.compose.animation.core.animateFloat
import com.tencent.kuikly.compose.animation.core.infiniteRepeatable
import com.tencent.kuikly.compose.animation.core.rememberInfiniteTransition
import com.tencent.kuikly.compose.animation.core.tween
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.graphicsLayer
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontStyle
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
import kotlin.math.abs
import kotlin.math.roundToLong

const val MEMBERS_PAGE = "compose_members"
const val MEMBER_DETAIL_PAGE = "compose_member_detail"
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

private fun String?.toMembersAgentDetailTabOrNull(): MembersAgentDetailTab? {
    val normalized = this?.trim()?.lowercase()?.replace("-", "_") ?: return null
    return MembersAgentDetailTab.entries.firstOrNull { tab ->
        tab.name.lowercase() == normalized || tab.label.lowercase().replace(" ", "_") == normalized
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

private fun SharedAgentDetailState.toMembersAgentDetailLoadState(): MembersAgentDetailLoadState =
    when (detailLoadStatus) {
        SharedAgentDetailLoadStatus.Idle -> MembersAgentDetailLoadState.Idle
        SharedAgentDetailLoadStatus.Loading -> detail?.let(MembersAgentDetailLoadState::Content)
            ?: MembersAgentDetailLoadState.Loading
        SharedAgentDetailLoadStatus.Content -> detail?.let(MembersAgentDetailLoadState::Content)
            ?: MembersAgentDetailLoadState.Empty
        SharedAgentDetailLoadStatus.NoPermission ->
            MembersAgentDetailLoadState.NoPermission(detailErrorCode.orEmpty())
        SharedAgentDetailLoadStatus.Error ->
            MembersAgentDetailLoadState.Error(detailErrorCode.orEmpty())
        SharedAgentDetailLoadStatus.Empty -> MembersAgentDetailLoadState.Empty
    }

private sealed interface MembersAgentWorkspaceTreeState {
    data object Idle : MembersAgentWorkspaceTreeState
    data object Loading : MembersAgentWorkspaceTreeState
    data class Content(val snapshot: AgentWorkspaceTreeSnapshot) : MembersAgentWorkspaceTreeState
    data class NoPermission(val code: String) : MembersAgentWorkspaceTreeState
    data class Error(val code: String) : MembersAgentWorkspaceTreeState
    data object Empty : MembersAgentWorkspaceTreeState
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

@Page(MEMBER_DETAIL_PAGE, supportInLocal = true)
internal class ComposeMemberDetailPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { MemberDetailPageContent() }
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
    val selectedMemberId: String? = null,
    val initialAgentTab: String? = null,
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

data class KuiklyMemberDetailPayload(
    val instanceId: String = "",
    val memberId: String = "",
    val initialAgentTab: String? = null,
    val localOffsetMinutes: Int = 0,
    val todayEpochDay: Long? = null,
    val nowEpochMillis: Long? = null,
    val timeFormat: String = SlockTimeFormat24Hour
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
        activityDetail = "Capturing deterministic Activity tab state"
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
            fireAt = "2026-06-19T18:30:00Z"
        )
    ),
    activity = listOf(
        AgentActivityEntry(
            id = "activity-agent-detail-working",
            type = "status",
            title = "Working",
            body = "Capturing deterministic Activity tab state",
            timestamp = "2026-06-19T12:28:00Z",
            statusActivity = "working",
            statusDetail = "Capturing deterministic Activity tab state"
        ),
        AgentActivityEntry(
            id = "activity-agent-detail-thinking",
            type = "thinking",
            title = "Thinking",
            body = "Comparing React and Android Members Activity screenshots before publishing.",
            timestamp = "2026-06-19T12:29:00Z"
        ),
        AgentActivityEntry(
            id = "activity-agent-detail-command",
            type = "tool_start",
            title = "Running command",
            body = "node tools/visual-testing/src/cli.mjs diff --case screens.members.agent-detail.activity",
            timestamp = "2026-06-19T12:30:00Z",
            toolName = "shell",
            toolInput = "node tools/visual-testing/src/cli.mjs diff --case screens.members.agent-detail.activity"
        ),
        AgentActivityEntry(
            id = "activity-agent-detail-report",
            type = "slock_action",
            title = "Posted status update",
            body = "Reported capture progress in #product:d4870bc3 and kept #225 in review.",
            timestamp = "2026-06-19T12:31:00Z",
            refTextEnabled = true
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

private val membersAgentDetailVisualWorkspaceTreeSnapshot = AgentWorkspaceTreeSnapshot(
    agentId = membersAgentDetailVisualMember.id,
    rootPath = "~/.slock/agents/agent-product-ux",
    root = SlockWorkspaceNode(
        id = "~/.slock/agents/agent-product-ux",
        name = "agent-product-ux",
        path = "~/.slock/agents/agent-product-ux",
        kind = SlockWorkspaceFileKind.Folder,
        children = listOf(
            SlockWorkspaceNode(
                id = "MEMORY.md",
                name = "MEMORY.md",
                path = "MEMORY.md",
                kind = SlockWorkspaceFileKind.Markdown,
                meta = "86 B"
            ),
            SlockWorkspaceNode(
                id = "notes",
                name = "notes",
                path = "notes",
                kind = SlockWorkspaceFileKind.Folder,
                meta = "folder"
            )
        )
    )
)

private val membersAgentDetailVisualWorkspaceNotesChildren = AgentWorkspaceChildrenSnapshot(
    agentId = membersAgentDetailVisualMember.id,
    dirPath = "notes",
    children = listOf(
        SlockWorkspaceNode(
            id = "notes/visual-parity.md",
            name = "visual-parity.md",
            path = "notes/visual-parity.md",
            kind = SlockWorkspaceFileKind.Markdown,
            meta = "1.2 KB"
        )
    )
)

private enum class MembersAgentDetailVisualMode {
    Profile,
    LifecycleActions,
    AvatarManagement
}

private fun String.toMembersAgentDetailVisualMode(): MembersAgentDetailVisualMode =
    when (trim().lowercase()) {
        "lifecycle_actions" -> MembersAgentDetailVisualMode.LifecycleActions
        "avatar_management" -> MembersAgentDetailVisualMode.AvatarManagement
        else -> MembersAgentDetailVisualMode.Profile
    }

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
    val scope = rememberCoroutineScope()
    val computersStore = remember(computersApiClient, scope) {
        SharedComputersStore(
            computersApiClient = computersApiClient,
            scope = scope,
            logger = { message -> bridge.log("members_$message") }
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
    val computersSlice by computersStore
        .computerPickerSliceFlow(serverId)
        .collectAsState(SharedComputersPickerSlice())
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
                        avatarUrl = result.data.avatarUrl,
                        avatar = ai.slock.compose.model.AvatarReference.fromWireValue(result.data.avatarUrl),
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
    val loadMachines: () -> Unit = remember(computersStore, serverId) {
        {
            if (serverId.isNotBlank()) {
                computersStore.dispatch(SharedComputersIntent.EnsureComputerPicker(serverId))
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
        agentDetailLoader = { agentId ->
            if (payload.selectedMemberId == membersAgentDetailVisualMember.id.value &&
                agentId == membersAgentDetailVisualMember.id
            ) {
                DataResult.Content(membersAgentDetailVisualSnapshot)
            } else {
                DataResult.Empty(EmptyReason.NoMembers)
            }
        },
        canViewPrivateAgentSurfaces = { member, _ ->
            member.id == membersAgentDetailVisualMember.id
        },
        canManageAgent = { member, _ ->
            member.id == membersAgentDetailVisualMember.id
        },
        agentWorkspaceTreeLoader = { agentId ->
            if (agentId == membersAgentDetailVisualMember.id) {
                DataResult.Content(membersAgentDetailVisualWorkspaceTreeSnapshot)
            } else {
                DataResult.Empty(EmptyReason.NoMembers)
            }
        },
        agentWorkspaceChildrenLoader = { agentId, path ->
            if (agentId == membersAgentDetailVisualMember.id && path == "notes") {
                DataResult.Content(membersAgentDetailVisualWorkspaceNotesChildren)
            } else {
                DataResult.Empty(EmptyReason.NoMembers)
            }
        },
        canCreateAgents = payload.canCreateAgents,
        createAgentSubmit = createAgentSubmit,
        createAgentMachines = computersSlice.machines,
        createAgentMachinesLoading = computersSlice.status == SharedComputersLoadStatus.InFlight,
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

private sealed interface MemberDetailRouteState {
    data object Loading : MemberDetailRouteState
    data class Error(val code: String) : MemberDetailRouteState
    data class Content(
        val server: ServerSummary,
        val member: MemberProfile,
        val currentUserId: String?,
        val agentDetailSeeded: Boolean = false
    ) : MemberDetailRouteState
}

private class MemberDetailRouteProvider(
    val bridge: BridgeModule,
    val authStore: SlockAuthSessionStore,
    val memberRepository: MemberRepository,
    val settingsCapabilities: BridgeSettingsPlatformCapabilities,
    val agentDetailStore: SharedAgentDetailStore
) {
    suspend fun loadMember(memberId: String): MemberDetailRouteState {
        val selectedMemberId = memberId.trim()
        if (selectedMemberId.isBlank()) return MemberDetailRouteState.Error("member_id_missing")
        val session = authStore.readSession()
        val serverId = session?.currentServerId
        val server = session?.servers?.firstOrNull { it.id == serverId }?.toServerSummary()
        if (session == null || serverId.isNullOrBlank() || server == null) {
            return MemberDetailRouteState.Error("server_required")
        }
        val selectedId = MemberId(selectedMemberId)
        when (val detailResult = memberRepository.getAgentDetail(DataRequestPriority.Visible, selectedId)) {
            is DataResult.Content -> {
                agentDetailStore.dispatch(SharedAgentDetailIntent.SelectMember(selectedId))
                agentDetailStore.dispatch(SharedAgentDetailIntent.SetDetail(selectedId, detailResult.data))
                return MemberDetailRouteState.Content(
                    server = server,
                    member = detailResult.data.toMemberProfile(server),
                    currentUserId = session.user.id,
                    agentDetailSeeded = true
                )
            }
            is DataResult.Empty,
            is DataResult.Error,
            is DataResult.NoPermission -> Unit
        }
        return when (val result = memberRepository.getMembers(DataRequestPriority.Visible, ServerId(serverId))) {
            is DataResult.Content -> {
                val member = result.data.members.firstOrNull { it.id.value == selectedMemberId }
                if (member != null) {
                    MemberDetailRouteState.Content(result.data.server, member, session.user.id)
                } else {
                    MemberDetailRouteState.Error("member_not_found")
                }
            }
            is DataResult.Empty -> MemberDetailRouteState.Error("member_not_found")
            is DataResult.Error -> MemberDetailRouteState.Error(result.error.code)
            is DataResult.NoPermission -> MemberDetailRouteState.Error(result.blocker.code)
        }
    }

    suspend fun loadAgentDetail(member: MemberProfile?) {
        if (member == null || member.kind != MemberKind.Agent) {
            agentDetailStore.dispatch(SharedAgentDetailIntent.DeselectMember)
            return
        }
        agentDetailStore.dispatch(SharedAgentDetailIntent.SelectMember(member.id))
        agentDetailStore.dispatch(SharedAgentDetailIntent.SetDetailLoading(member.id))
        when (val result = memberRepository.getAgentDetail(DataRequestPriority.Visible, member.id)) {
            is DataResult.Content ->
                agentDetailStore.dispatch(SharedAgentDetailIntent.SetDetail(member.id, result.data))
            is DataResult.Empty ->
                agentDetailStore.dispatch(SharedAgentDetailIntent.SetDetailEmpty(member.id))
            is DataResult.NoPermission ->
                agentDetailStore.dispatch(
                    SharedAgentDetailIntent.SetDetailNoPermission(member.id, result.blocker.code)
                )
            is DataResult.Error ->
                agentDetailStore.dispatch(
                    SharedAgentDetailIntent.SetDetailError(member.id, result.error.code)
                )
        }
    }

    suspend fun runLifecycleAction(
        agentId: MemberId,
        action: MembersAgentLifecycleAction
    ): DataResult<AgentLifecycleActionResult> {
        val result =
            when (action) {
                MembersAgentLifecycleAction.Start ->
                    memberRepository.startAgent(DataRequestPriority.Foreground, agentId)
                MembersAgentLifecycleAction.Stop ->
                    memberRepository.stopAgent(DataRequestPriority.Foreground, agentId)
                MembersAgentLifecycleAction.Restart,
                MembersAgentLifecycleAction.ResetSession,
                MembersAgentLifecycleAction.ResetFull ->
                    memberRepository.resetAgent(
                        DataRequestPriority.Foreground,
                        ai.slock.compose.data.AgentResetRequest(
                            agentId,
                            action.resetMode ?: AgentResetMode.Restart
                        )
                    )
                MembersAgentLifecycleAction.Delete ->
                    memberRepository.deleteAgent(DataRequestPriority.Foreground, agentId)
                MembersAgentLifecycleAction.UnassignMachine ->
                    memberRepository.assignAgentMachine(
                        DataRequestPriority.Foreground,
                        AgentMachineAssignmentRequest(agentId, machineId = null)
                    )
            }
        if (result is DataResult.Content && result.data.success) {
            if (
                action == MembersAgentLifecycleAction.ResetSession ||
                action == MembersAgentLifecycleAction.ResetFull
            ) {
                memberRepository.clearAgentActivityCache(agentId)
            }
        }
        return result
    }

    suspend fun updateAgentProfile(request: AgentProfileUpdateRequest): DataResult<MemberProfile> =
        memberRepository.updateAgentProfile(DataRequestPriority.Foreground, request)

    suspend fun uploadAgentAvatar(
        agentId: MemberId,
        file: AttachmentUploadFile
    ): DataResult<MemberProfile> =
        memberRepository.uploadAgentAvatar(
            priority = DataRequestPriority.Foreground,
            request = AgentAvatarUploadRequest(agentId = agentId, file = file)
        )

    suspend fun pickAgentAvatarFile(): DataResult<AttachmentUploadFile> {
        val response = bridge.threadAttachmentUpload(channelId = "agent-avatar-picker", imageOnly = true)
        val code = response?.optInt("code", 0) ?: 0
        return if (code == 204) {
            DataResult.Empty(EmptyReason.NoMembers)
        } else if (code !in 200..299) {
            val errorCode = response?.optString("errorCode").orEmpty().ifBlank { "agent_avatar_picker_failed" }
            DataResult.Error(ScreenError(code = errorCode, retryable = true))
        } else {
            val file = parseSharedThreadPickedAttachmentFiles(response).firstOrNull()
            if (file != null) {
                DataResult.Content(file)
            } else {
                DataResult.Error(ScreenError(code = "agent_avatar_picker_empty", retryable = true))
            }
        }
    }

    fun copyMemberId(member: MemberProfile) {
        bridge.copyToPasteboard(member.id.value)
        bridge.toast("Copied member id")
    }

    fun copyDiagnostics(diagnostics: String) {
        bridge.copyToPasteboard(diagnostics)
        bridge.toast("Copied agent diagnostics")
    }

    suspend fun reportIssue(diagnostics: String): Boolean {
        bridge.log("agent_diagnostics_report ${diagnostics.toMemberDetailDiagnosticsLine()}")
        val ok = settingsCapabilities.sendFeedback()
        bridge.toast(if (ok) "Agent issue report sent" else "Unable to report agent issue")
        return ok
    }
}

@Composable
private fun rememberMemberDetailRouteProvider(
    pager: Pager,
    bridge: BridgeModule
): MemberDetailRouteProvider {
    val sharedStores = remember { ai.slock.compose.data.SlockSharedStoreRegistry.get(bridge) }
    val authStore = sharedStores.authStore
    val networkClient =
        remember {
            KuiklySlockNetworkClient(
                sessionStore = authStore,
                networkModule = pager.acquireModule<ai.slock.compose.network.SlockNetworkModule>(
                    ai.slock.compose.network.SlockNetworkModule.MODULE_NAME
                ),
                warningLogger = { message -> bridge.log("member_detail_network $message") }
            )
        }
    val memberRepository: MemberRepository =
        remember(networkClient, authStore) {
            RealMemberRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = { message -> bridge.log("member_detail_repository $message") }
            )
        }
    val settingsCapabilities = remember { BridgeSettingsPlatformCapabilities(bridge) }
    val agentDetailStore =
        remember {
            SharedAgentDetailStore(
                logger = { message -> bridge.log("member_detail_store $message") }
            )
        }
    return remember(bridge, authStore, memberRepository, settingsCapabilities, agentDetailStore) {
        MemberDetailRouteProvider(
            bridge = bridge,
            authStore = authStore,
            memberRepository = memberRepository,
            settingsCapabilities = settingsCapabilities,
            agentDetailStore = agentDetailStore
        )
    }
}

@Composable
private fun MemberDetailPageContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val payload = remember(pager.pageData.params.toString()) {
        pager.pageData.params.toKuiklyMemberDetailPayload()
    }
    val provider = rememberMemberDetailRouteProvider(pager, bridge)
    var routeState by remember(payload.memberId) {
        mutableStateOf<MemberDetailRouteState>(MemberDetailRouteState.Loading)
    }
    var localAgentDetailRefreshKey by remember { mutableStateOf(0) }
    val agentDetailStoreState by provider.agentDetailStore.state.collectAsState()
    val agentDetailState = agentDetailStoreState.toMembersAgentDetailLoadState()
    val timeContext = payload.toSlockTimeContext()
    val initialAgentTab = remember(payload.initialAgentTab) { payload.initialAgentTab.toMembersAgentDetailTabOrNull() }

    LaunchedEffect(payload.memberId) {
        routeState = MemberDetailRouteState.Loading
        routeState = provider.loadMember(payload.memberId)
    }

    val content = routeState as? MemberDetailRouteState.Content
    val member = content?.member
    LaunchedEffect(member?.id?.value, member?.kind, content?.agentDetailSeeded, localAgentDetailRefreshKey) {
        if (content?.agentDetailSeeded == true && localAgentDetailRefreshKey == 0) {
            return@LaunchedEffect
        }
        provider.loadAgentDetail(member)
    }

    when (val current = routeState) {
        MemberDetailRouteState.Loading ->
            Box(
                modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite),
                contentAlignment = Alignment.Center
            ) {
                SlockCenteredLoading()
            }
        is MemberDetailRouteState.Error ->
            Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
                SlockTopPageHeader(
                    title = "Member",
                    subtitle = "Profile",
                    iconName = SlockLucideIconName.Users
                )
                SlockEmptyState(
                    title = "Could not load member",
                    detail = current.code,
                    iconName = SlockLucideIconName.TriangleAlert,
                    modifier = Modifier.fillMaxSize()
                )
            }
        is MemberDetailRouteState.Content -> {
            val agentDetailActions =
                remember(provider) {
                    object : SharedAgentDetailActions {
                        override suspend fun runLifecycleAction(
                            agentId: MemberId,
                            action: MembersAgentLifecycleAction
                        ): DataResult<AgentLifecycleActionResult> {
                            val result = provider.runLifecycleAction(agentId, action)
                            if (result is DataResult.Content && result.data.success) {
                                routeState = routeState.withMemberDetailLifecycleResult(action, agentId)
                                localAgentDetailRefreshKey += 1
                                if (action == MembersAgentLifecycleAction.Delete) {
                                    provider.bridge.closePage()
                                }
                            }
                            return result
                        }

                        override fun copyDiagnostics(diagnostics: String) {
                            provider.copyDiagnostics(diagnostics)
                        }

                        override suspend fun reportIssue(diagnostics: String): Boolean {
                            return provider.reportIssue(diagnostics)
                        }

                        override suspend fun updateAgentProfile(
                            request: AgentProfileUpdateRequest
                        ): DataResult<MemberProfile> {
                            val result = provider.updateAgentProfile(request)
                            if (result is DataResult.Content) {
                                routeState = routeState.withMemberDetailProfile(result.data)
                            }
                            return result
                        }
                    }
                }
            MembersDetailContent(
                member = current.member,
                agentDetailState = agentDetailState,
                canViewPrivateAgentSurfaces = { _, detail ->
                    current.server.permissions.canManageSettings ||
                        detail?.summary?.creatorId == current.currentUserId
                },
                canManageAgent = { _, detail ->
                    current.server.permissions.canManageSettings ||
                        detail?.summary?.creatorId == current.currentUserId
                },
                onBack = { provider.bridge.closePage() },
                onOpenDirectMessage = { provider.bridge.settingsEvent(payload.instanceId, "openMember", current.member.id.value) },
                onCopyMemberId = {
                    provider.copyMemberId(current.member)
                },
                onOpenUrl = { provider.bridge.openUrl(it) },
                agentDetailActions = agentDetailActions,
                onUpdateAgentProfile = { request ->
                    agentDetailActions.updateAgentProfile(request)
                },
                onPickAgentAvatarFile = {
                    provider.pickAgentAvatarFile()
                },
                onUploadAgentAvatar = { agentId, file ->
                    val result = provider.uploadAgentAvatar(agentId, file)
                    if (result is DataResult.Content) {
                        routeState = routeState.withMemberDetailProfile(result.data)
                    }
                    result
                },
                onAgentLifecycleAction = { agentId, action ->
                    agentDetailActions.runLifecycleAction(agentId, action)
                },
                agentWorkspaceTreeLoader = { agentId ->
                    provider.memberRepository.getAgentWorkspaceTree(DataRequestPriority.Visible, agentId)
                },
                agentWorkspaceChildrenLoader = { agentId, dirPath ->
                    provider.memberRepository.getAgentWorkspaceChildren(DataRequestPriority.Visible, agentId, dirPath)
                },
                onCopyAgentDiagnostics = agentDetailActions::copyDiagnostics,
                onReportAgentIssue = agentDetailActions::reportIssue,
                onUnavailableAction = { bridge.toast(it) },
                timeContext = timeContext,
                sharedAgentDetailStore = provider.agentDetailStore,
                initialAgentTab = initialAgentTab
            )
        }
    }
}

@Composable
private fun MembersAgentDetailVisualContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val visualMode = pager.pageData.params.optString("visualMode").toMembersAgentDetailVisualMode()
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
        onCopyAgentDiagnostics = {},
        onReportAgentIssue = null,
        onUnavailableAction = {},
        onUploadAgentAvatar = null,
        timeContext = SlockTimeContext(
            localOffsetMinutes = 480,
            todayEpochDay = 20623,
            nowEpochMillis = 1781872200000,
            timeFormat = SlockTimeFormat24Hour
        ),
        initialVisualMode = visualMode
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
        selectedMemberId = payload.optNullableString("selectedMemberId"),
        initialAgentTab = payload.optNullableString("initialAgentTab"),
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

internal fun JSONObject.toKuiklyMemberDetailPayload(): KuiklyMemberDetailPayload {
    val payloadText = optString("payload")
    val payload = if (payloadText.isNotBlank()) JSONObject(payloadText) else this
    return KuiklyMemberDetailPayload(
        instanceId = payload.optString("instanceId").ifBlank { optString("instanceId") },
        memberId = payload.optString("memberId").ifBlank { payload.optString("selectedMemberId") },
        initialAgentTab = payload.optNullableString("initialAgentTab"),
        localOffsetMinutes = payload.optString("localOffsetMinutes").toIntOrNull() ?: 0,
        todayEpochDay = payload.optString("todayEpochDay").toLongOrNull(),
        nowEpochMillis = payload.optString("nowEpochMillis").toLongOrNull(),
        timeFormat = payload.optString("timeFormat").ifBlank { SlockTimeFormat24Hour }
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
    refreshing: Boolean = false,
    sharedAgentDetailStore: SharedAgentDetailStore? = null,
    onAddHuman: () -> Unit,
    onOpenDirectMessage: (MemberProfile) -> Unit,
    onCopyMemberId: (MemberProfile) -> Unit,
    onOpenUrl: (String) -> Unit = {},
    agentDetailActions: SharedAgentDetailActions? = null,
    agentDetailLoader: (suspend (MemberId) -> DataResult<AgentDetailSnapshot>)? = null,
    agentDetailRefreshKey: Any? = Unit,
    onUpdateAgentProfile: (suspend (AgentProfileUpdateRequest) -> DataResult<MemberProfile>)? = null,
    onUploadAgentAvatar: (suspend (MemberId, AttachmentUploadFile) -> DataResult<MemberProfile>)? = null,
    onPickAgentAvatarFile: suspend () -> DataResult<AttachmentUploadFile> = { DataResult.Empty(EmptyReason.NoMembers) },
    onAgentLifecycleAction: (suspend (MemberId, MembersAgentLifecycleAction) -> DataResult<AgentLifecycleActionResult>)? = null,
    agentWorkspaceTreeLoader: (suspend (MemberId) -> DataResult<AgentWorkspaceTreeSnapshot>)? = null,
    agentWorkspaceChildrenLoader: (suspend (MemberId, String) -> DataResult<AgentWorkspaceChildrenSnapshot>)? = null,
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
    inviteHumanLoadJoinLink: () -> Unit = {},
    bottomContentPadding: Dp = SlockSpacing.Lg
) {
    var selectedMemberId by remember(payload.instanceId, payload.selectedMemberId) {
        mutableStateOf(payload.selectedMemberId)
    }
    val selectedMember = payload.members.firstOrNull { it.id.value == selectedMemberId }
    val initialAgentTab = remember(payload.initialAgentTab) { payload.initialAgentTab.toMembersAgentDetailTabOrNull() }
    val timeContext = payload.toSlockTimeContext()
    val fallbackAgentDetailStore = remember { SharedAgentDetailStore() }
    val effectiveAgentDetailStore = sharedAgentDetailStore ?: fallbackAgentDetailStore
    val agentDetailStoreState by effectiveAgentDetailStore.state.collectAsState()
    var localAgentDetailState by remember(selectedMember?.id?.value) {
        mutableStateOf<MembersAgentDetailLoadState>(MembersAgentDetailLoadState.Idle)
    }
    val storeBackedAgentDetail = sharedAgentDetailStore != null
    val agentDetailState =
        if (storeBackedAgentDetail) {
            agentDetailStoreState.toMembersAgentDetailLoadState()
        } else {
            localAgentDetailState
        }
    LaunchedEffect(selectedMemberId, selectedMember?.id?.value) {
        if (selectedMemberId != null && selectedMember == null) {
            selectedMemberId = null
        }
    }
    LaunchedEffect(selectedMember?.id?.value, selectedMember?.kind, agentDetailRefreshKey, storeBackedAgentDetail) {
        val member = selectedMember
        if (member == null || member.kind != MemberKind.Agent || agentDetailLoader == null) {
            if (storeBackedAgentDetail) {
                effectiveAgentDetailStore.dispatch(SharedAgentDetailIntent.DeselectMember)
            } else {
                localAgentDetailState = MembersAgentDetailLoadState.Idle
            }
            return@LaunchedEffect
        }
        if (storeBackedAgentDetail) {
            effectiveAgentDetailStore.dispatch(SharedAgentDetailIntent.SelectMember(member.id))
            effectiveAgentDetailStore.dispatch(SharedAgentDetailIntent.SetDetailLoading(member.id))
        } else if (localAgentDetailState !is MembersAgentDetailLoadState.Content) {
            localAgentDetailState = MembersAgentDetailLoadState.Loading
        }
        when (val result = agentDetailLoader(member.id)) {
            is DataResult.Content ->
                if (storeBackedAgentDetail) {
                    effectiveAgentDetailStore.dispatch(SharedAgentDetailIntent.SetDetail(member.id, result.data))
                } else {
                    localAgentDetailState = MembersAgentDetailLoadState.Content(result.data)
                }
            is DataResult.Empty ->
                if (storeBackedAgentDetail) {
                    effectiveAgentDetailStore.dispatch(SharedAgentDetailIntent.SetDetailEmpty(member.id))
                } else {
                    localAgentDetailState = MembersAgentDetailLoadState.Empty
                }
            is DataResult.NoPermission ->
                if (storeBackedAgentDetail) {
                    effectiveAgentDetailStore.dispatch(
                        SharedAgentDetailIntent.SetDetailNoPermission(member.id, result.blocker.code)
                    )
                } else {
                    localAgentDetailState = MembersAgentDetailLoadState.NoPermission(result.blocker.code)
                }
            is DataResult.Error ->
                if (storeBackedAgentDetail) {
                    effectiveAgentDetailStore.dispatch(
                        SharedAgentDetailIntent.SetDetailError(member.id, result.error.code)
                    )
                } else {
                    localAgentDetailState = MembersAgentDetailLoadState.Error(result.error.code)
                }
        }
    }
    LaunchedEffect(selectedMember?.id?.value, selectedMember?.kind, storeBackedAgentDetail) {
        if (storeBackedAgentDetail && (selectedMember == null || selectedMember.kind != MemberKind.Agent)) {
            effectiveAgentDetailStore.dispatch(SharedAgentDetailIntent.DeselectMember)
        }
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
            agentDetailActions = agentDetailActions,
            onUpdateAgentProfile = onUpdateAgentProfile,
            onUploadAgentAvatar = onUploadAgentAvatar,
            onPickAgentAvatarFile = onPickAgentAvatarFile,
            onAgentLifecycleAction = onAgentLifecycleAction,
            agentWorkspaceTreeLoader = agentWorkspaceTreeLoader,
            agentWorkspaceChildrenLoader = agentWorkspaceChildrenLoader,
            onCopyAgentDiagnostics = onCopyAgentDiagnostics,
            onReportAgentIssue = onReportAgentIssue,
            onUnavailableAction = onUnavailableAction,
            timeContext = timeContext,
            sharedAgentDetailStore = sharedAgentDetailStore,
            initialAgentTab = initialAgentTab
        )
    } else {
        MembersListContent(
            payload = payload,
            refreshing = refreshing,
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
            inviteHumanLoadJoinLink = { inviteHumanLoadJoinLink() },
            bottomContentPadding = bottomContentPadding
        )
    }
}

@Composable
internal fun MembersListContent(
    payload: KuiklyMembersPayload,
    refreshing: Boolean = false,
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
    inviteHumanLoadJoinLink: () -> Unit = {},
    bottomContentPadding: Dp = SlockSpacing.Lg
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
        MembersPageHeader(title = payload.title, refreshing = refreshing)
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
                            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
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
            item { Spacer(Modifier.height(bottomContentPadding)) }
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
    agentDetailActions: SharedAgentDetailActions? = null,
    onUpdateAgentProfile: (suspend (AgentProfileUpdateRequest) -> DataResult<MemberProfile>)?,
    onUploadAgentAvatar: (suspend (MemberId, AttachmentUploadFile) -> DataResult<MemberProfile>)?,
    onPickAgentAvatarFile: suspend () -> DataResult<AttachmentUploadFile> = { DataResult.Empty(EmptyReason.NoMembers) },
    onAgentLifecycleAction: (suspend (MemberId, MembersAgentLifecycleAction) -> DataResult<AgentLifecycleActionResult>)?,
    agentWorkspaceTreeLoader: (suspend (MemberId) -> DataResult<AgentWorkspaceTreeSnapshot>)?,
    agentWorkspaceChildrenLoader: (suspend (MemberId, String) -> DataResult<AgentWorkspaceChildrenSnapshot>)?,
    onCopyAgentDiagnostics: (String) -> Unit,
    onReportAgentIssue: (suspend (String) -> Boolean)?,
    onUnavailableAction: (String) -> Unit,
    timeContext: SlockTimeContext,
    sharedAgentDetailStore: SharedAgentDetailStore? = null,
    initialVisualMode: MembersAgentDetailVisualMode = MembersAgentDetailVisualMode.Profile,
    initialAgentTab: MembersAgentDetailTab? = null
) {
    val detail = (agentDetailState as? MembersAgentDetailLoadState.Content)?.detail
    val deleted = detail?.summary?.deletedAt?.isNotBlank() == true
    val canViewPrivate = member.kind == MemberKind.Agent && canViewPrivateAgentSurfaces(member, detail)
    val canManage = member.kind == MemberKind.Agent && !deleted && canManageAgent(member, detail)
    val scope = rememberCoroutineScope()
    val pager = LocalActivity.current.getPager() as Pager
    val fallbackAgentDetailStore = remember { SharedAgentDetailStore() }
    val storeState by (sharedAgentDetailStore ?: fallbackAgentDetailStore).state.collectAsState()
    var actionState by remember(member.id.value, initialVisualMode) {
        mutableStateOf<MembersAgentActionState?>(
            if (initialVisualMode == MembersAgentDetailVisualMode.LifecycleActions) {
                MembersAgentActionState(MembersAgentLifecycleAction.Restart, inProgress = true)
            } else {
                null
            }
        )
    }
    var diagnosticActionState by remember(member.id.value) { mutableStateOf<MembersAgentDiagnosticActionState?>(null) }
    var pendingConfirmation by remember(member.id.value, initialVisualMode) {
        mutableStateOf<MembersAgentLifecycleAction?>(
            if (initialVisualMode == MembersAgentDetailVisualMode.LifecycleActions) {
                MembersAgentLifecycleAction.Restart
            } else {
                null
            }
        )
    }
    var selectedTab by remember(member.id.value, initialAgentTab) {
        mutableStateOf(initialAgentTab ?: MembersAgentDetailTab.Profile)
    }
    var managementMenuOpen by remember(member.id.value) { mutableStateOf(false) }
    val storeLifecycleInFlight: String? = storeState.lifecycleActionInFlight
    val storeDiagnosticInFlight: String? = storeState.diagnosticActionInFlight
    val lifecycleInFlight: String? = if (sharedAgentDetailStore != null) {
        storeLifecycleInFlight
    } else {
        actionState?.takeIf { it.inProgress }?.action?.actionKey
    }
    val diagnosticInFlight: String? = if (sharedAgentDetailStore != null) {
        storeDiagnosticInFlight
    } else {
        diagnosticActionState?.takeIf { it.inProgress }?.actionKey
    }
    val storePendingConfirmation: MembersAgentLifecycleAction? =
        storeState.pendingConfirmation.takeIf { sharedAgentDetailStore != null }
    val effectiveDiagnosticActionState =
        if (sharedAgentDetailStore != null) {
            storeState.diagnosticActionInFlight?.let {
                MembersAgentDiagnosticActionState(actionKey = it, inProgress = true)
            } ?: storeState.lastActionResult
                ?.takeIf { it.actionKey == "copy" || it.actionKey == "report" }
                ?.let {
                    MembersAgentDiagnosticActionState(
                        actionKey = it.actionKey,
                        inProgress = false,
                        errorCode = it.errorCode,
                        successLabel = it.successLabel
                    )
                }
        } else {
            diagnosticActionState
        }
    var managementMenuAnchor by remember(member.id.value) { mutableStateOf<Offset?>(null) }
    var showAvatarPicker by remember(member.id.value, initialVisualMode) {
        mutableStateOf(initialVisualMode == MembersAgentDetailVisualMode.AvatarManagement)
    }
    var workspaceTreeState by remember(member.id.value) {
        mutableStateOf<MembersAgentWorkspaceTreeState>(MembersAgentWorkspaceTreeState.Idle)
    }
    var expandedWorkspaceDirs by remember(member.id.value) { mutableStateOf<Set<String>>(emptySet()) }
    var loadedWorkspaceDirs by remember(member.id.value) { mutableStateOf<Map<String, List<SlockWorkspaceNode>>>(emptyMap()) }
    var loadingWorkspaceDirs by remember(member.id.value) { mutableStateOf<Set<String>>(emptySet()) }
    var workspaceDirErrors by remember(member.id.value) { mutableStateOf<Map<String, String>>(emptyMap()) }
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
        pager.openSlockFilePreviewPage(agentWorkspaceLazyPreviewPayload(agentId = member.id.value, path = path))
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
        if (lifecycleInFlight != null) return
        val callback = onAgentLifecycleAction
        if (!canManage || (agentDetailActions == null && callback == null)) {
            onUnavailableAction("Agent management is not available.")
            return
        }
        if (action.requiresConfirmation && !confirmed) {
            actionState = null
            if (sharedAgentDetailStore != null) {
                sharedAgentDetailStore.dispatch(SharedAgentDetailIntent.ShowConfirmation(action))
            } else {
                pendingConfirmation = action
            }
            return
        }
        scope.launch {
            pendingConfirmation = null
            actionState = MembersAgentActionState(action, inProgress = true)
            val result =
                if (sharedAgentDetailStore != null && agentDetailActions != null) {
                    sharedAgentDetailStore.runLifecycleAction(member.id, action, agentDetailActions)
                } else {
                    try {
                        requireNotNull(callback).invoke(member.id, action)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        DataResult.Error(ScreenError("agent_${action.actionKey}_failed"))
                    }
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
        val diagnostics = buildMembersAgentDiagnostics(member, detail)
        if (sharedAgentDetailStore != null && agentDetailActions != null) {
            sharedAgentDetailStore.copyDiagnostics(diagnostics, agentDetailActions)
        } else {
            onCopyAgentDiagnostics(diagnostics)
            diagnosticActionState =
                MembersAgentDiagnosticActionState(
                    actionKey = "copy",
                    inProgress = false,
                    successLabel = "Diagnostics copied"
                )
        }
    }
    fun reportIssue() {
        val callback = onReportAgentIssue
        if (diagnosticInFlight != null) return
        if (agentDetailActions == null && callback == null) {
            onUnavailableAction("Agent issue reporting is not available.")
            return
        }
        scope.launch {
            diagnosticActionState =
                MembersAgentDiagnosticActionState(actionKey = "report", inProgress = true)
            val diagnostics = buildMembersAgentDiagnostics(member, detail)
            val reported =
                if (sharedAgentDetailStore != null && agentDetailActions != null) {
                    sharedAgentDetailStore.reportIssue(diagnostics, agentDetailActions)
                } else {
                    try {
                        requireNotNull(callback).invoke(diagnostics)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        false
                    }
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
                                sharedAgentDetailStore = sharedAgentDetailStore,
                                storeState = storeState.takeIf { sharedAgentDetailStore != null },
                                agentDetailActions = agentDetailActions,
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
                                timeContext = timeContext,
                                modifier = Modifier.padding(horizontal = MembersPageInset)
                            )
                        MembersAgentDetailTab.Workspace ->
                            MembersAgentWorkspaceTab(
                                treeState = workspaceTreeState,
                                expandedDirs = expandedWorkspaceDirs,
                                loadedDirs = loadedWorkspaceDirs,
                                loadingDirs = loadingWorkspaceDirs,
                                dirErrors = workspaceDirErrors,
                                onRefresh = { loadWorkspaceRoot() },
                                onToggleDirectory = { toggleWorkspaceDirectory(it) },
                                onSelectFile = { selectWorkspaceFile(it) },
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
                                diagnosticActionState = effectiveDiagnosticActionState,
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
        val dropdownAnchor: Offset = managementMenuAnchor
            ?: Offset(100f, 100f)
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
                hasReportIssue = agentDetailActions != null || onReportAgentIssue != null,
                inProgress = lifecycleInFlight != null || diagnosticInFlight != null
            )
            SlockBrutalDropdownMenu(
                anchor = dropdownAnchor,
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
                onDismiss = {
                    managementMenuOpen = false
                }
            )
        }
        val activePendingConfirmation =
            if (sharedAgentDetailStore != null) storePendingConfirmation else pendingConfirmation
        activePendingConfirmation?.let { action ->
            SlockBrutalModalLayer(
                model = SlockModalShellModel(
                    title = "Confirm ${action.label}",
                    subtitle = "${action.label} ${member.displayName}. This uses the real agent management API."
                ),
                onDismiss = {
                    if (sharedAgentDetailStore != null) {
                        sharedAgentDetailStore.dispatch(SharedAgentDetailIntent.DismissConfirmation)
                    } else {
                        pendingConfirmation = null
                    }
                }
            ) {
                MembersLifecycleConfirmation(
                    action = action,
                    agentName = member.displayName,
                    busy = lifecycleInFlight == action.actionKey,
                    onConfirm = { runLifecycleAction(action, confirmed = true) },
                    onCancel = {
                        if (sharedAgentDetailStore != null) {
                            sharedAgentDetailStore.dispatch(SharedAgentDetailIntent.DismissConfirmation)
                        } else {
                            pendingConfirmation = null
                            actionState = null
                        }
                    }
                )
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
    val threeDotButtonCenter = remember { SlockMutableOffsetRef() }
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
                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
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
                    iconName = SlockLucideIconName.Menu,
                    onClick = {
                        onRequestManagementMenu(threeDotButtonCenter.value)
                    },
                    size = MembersActionSize,
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        val pos = coordinates.positionInRoot()
                        threeDotButtonCenter.update(
                            Offset(
                                // #71 anchor at the button's TOP-LEFT corner
                                // (was button CENTER) so the dropdown menu
                                // can auto-flip horizontally against the
                                // button's right edge instead of the center,
                                // matching React `DropdownMenu` (place right
                                // edge of menu at right edge of trigger).
                                x = pos.x,
                                y = pos.y + coordinates.size.height.toFloat() / 2f
                            )
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
                style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
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
            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp).withSlockFontWeight(FontWeight.Bold),
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
            // Loading state is intentionally silent (#51 follow-up from @Cindy / @artin):
            // the body banner with "Loading agent profile..." is intrusive when the
            // detail is re-fetched after a dropdown action (e.g. lifecycle action or
            // refresh), and a new fetch typically completes fast enough that the
            // user-facing in-progress indicator on the action itself is enough.
            MembersAgentDetailLoadState.Loading -> null
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
                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                    color = SlockColor.TextMutedGray,
                    textAlign = TextAlign.Center
                )
            }
            Text(
                text = "Choose Avatar",
                style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp)
                    .withSlockFontWeight(FontWeight.Bold),
                color = SlockColor.TextBlack,
            )
            // React parity: each cell renders the actual pixel art (or upload icon
            // for the first cell) inside a 40dp cell with a 2dp border, mirroring
            // `AgentDetailPanel.tsx:2331-2391`. Active selection gets a pink border
            // + pink tint background (React `border-brutal-pink bg-brutal-pink/20`).
            val activeCustomAvatar = member.avatarUrl != null
            val activePixelKey = if (activeCustomAvatar) {
                null
            } else {
                (member.avatar?.wireValue?.removePrefix("pixel:") ?: PixelAvatarDefaultKey)
            }
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
            ) {
                // Cell 1: Upload custom image (mirrors React label/input combo;
                // here we route through the existing onUpload callback path since
                // file picking is owned by Android-App-Integrator via
                // onPickAgentAvatarFile — out of scope for #57).
                Box(
                    modifier = Modifier
                        .size(MembersAgentAvatarPickerCellSize)
                        .border(SlockStroke.Default, if (activeCustomAvatar) SlockColor.AccentPink else SlockColor.BorderBlack)
                        .background(if (activeCustomAvatar) SlockColor.AccentPink.copy(alpha = 0.2f) else Color.Transparent)
                        .clickable(enabled = !uploading) {
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
                    contentAlignment = Alignment.Center
                ) {
                    if (activeCustomAvatar) {
                        // Show the currently uploaded image (mirrors React's
                        // `<img src={agent.avatarUrl!} />` inside the Upload cell).
                        // `SlockAvatar` handles the HTTP path internally with the
                        // existing lavender fallback background + http image overlay.
                        SlockAvatar(
                            avatarWireValue = member.avatarUrl,
                            label = "Custom avatar",
                            avatarSize = MembersAgentAvatarPickerAvatarSize,
                            borderWidth = 0.dp
                        )
                    } else {
                        SlockLucideIcon(
                            if (uploading) SlockLucideIconName.Loader else SlockLucideIconName.Upload,
                            modifier = Modifier.size(16.dp),
                            color = "#99000000"
                        )
                    }
                }
                // Cell 2: Default (robot) — mirrors React `title="Default (robot)"`.
                // Click calls onClear which sets avatar to null → renderer falls back
                // to robot pixel art, same as React `handleClearAvatar`.
                MembersAgentAvatarPickerCell(
                    wireValue = "pixel:$PixelAvatarDefaultKey",
                    fallbackLabel = PixelAvatarDefaultKey,
                    active = activePixelKey == PixelAvatarDefaultKey,
                    onClick = onClear
                )
                // Cells 3..N: remaining pixel keys (PixelAvatarPickerKeys now
                // includes "robot" since #57; see SlockHomeComponents.kt
                // PixelAvatarPickerKeys comment).
                PixelAvatarPickerKeys.filter { it != PixelAvatarDefaultKey }.forEach { key ->
                    MembersAgentAvatarPickerCell(
                        wireValue = "pixel:$key",
                        fallbackLabel = key,
                        active = activePixelKey == key,
                        onClick = { onSelect(key) }
                    )
                }
            }
        }
    }
}

private val MembersAgentAvatarPickerCellSize = 40.dp
private val MembersAgentAvatarPickerAvatarSize = 36.dp

@Composable
private fun MembersAgentAvatarPickerCell(
    wireValue: String,
    fallbackLabel: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(MembersAgentAvatarPickerCellSize)
            .border(SlockStroke.Default, if (active) SlockColor.AccentPink else SlockColor.BorderBlack)
            .background(if (active) SlockColor.AccentPink.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        SlockAvatar(
            avatarWireValue = wireValue,
            label = fallbackLabel,
            avatarSize = MembersAgentAvatarPickerAvatarSize,
            borderWidth = 0.dp
        )
    }
}

private const val MembersAgentProfileDescriptionMaxLength = 3000

@Composable
private fun MembersAgentProfileTab(
    member: MemberProfile,
    detail: AgentDetailSnapshot?,
    canManage: Boolean,
    sharedAgentDetailStore: SharedAgentDetailStore? = null,
    storeState: SharedAgentDetailState? = null,
    agentDetailActions: SharedAgentDetailActions? = null,
    onUpdateAgentProfile: (suspend (AgentProfileUpdateRequest) -> DataResult<MemberProfile>)?,
    onUnavailableAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val summary = detail?.summary
    val scope = rememberCoroutineScope()
    val deleted = summary?.deletedAt?.isNotBlank() == true
    val canEdit = canManage && !deleted

    // Display Name: independent edit state machine, mirrors React `editingDisplayName`
    // (AgentDetailPanel.tsx ~L1073). Pencil icon toggles edit mode; inline Save/Cancel
    // dispatch the update; Enter submits, Escape cancels.
    val profileStore = sharedAgentDetailStore
    val profileState = storeState
    val storeBackedProfileEdit = profileStore != null && profileState != null
    var localEditingDisplayName by remember(member.id.value) { mutableStateOf(false) }
    var localDraftDisplayName by remember(member.id.value, member.displayName) { mutableStateOf(member.displayName) }
    var localSavingDisplayName by remember(member.id.value) { mutableStateOf(false) }
    var localDisplayNameError by remember(member.id.value) { mutableStateOf<String?>(null) }
    val editingDisplayName =
        if (storeBackedProfileEdit) profileState.displayNameEditing else localEditingDisplayName
    val draftDisplayName =
        if (storeBackedProfileEdit) profileState.displayNameDraft ?: member.displayName else localDraftDisplayName
    val savingDisplayName =
        if (storeBackedProfileEdit) profileState.displayNameSaving else localSavingDisplayName
    val displayNameError =
        if (storeBackedProfileEdit) profileState.displayNameError else localDisplayNameError

    // Description: independent edit state machine, mirrors React `editingRole`
    // (~L1180). Same shape as display name but uses textarea + char counter.
    var localEditingRole by remember(member.id.value) { mutableStateOf(false) }
    var localDraftRole by remember(member.id.value, member.description) { mutableStateOf(member.description.orEmpty()) }
    var localSavingRole by remember(member.id.value) { mutableStateOf(false) }
    var localRoleError by remember(member.id.value) { mutableStateOf<String?>(null) }
    val editingRole =
        if (storeBackedProfileEdit) profileState.roleEditing else localEditingRole
    val draftRole =
        if (storeBackedProfileEdit) profileState.roleDraft ?: member.description.orEmpty() else localDraftRole
    val savingRole =
        if (storeBackedProfileEdit) profileState.roleSaving else localSavingRole
    val roleError =
        if (storeBackedProfileEdit) profileState.roleError else localRoleError

    fun saveDisplayName() {
        val callback = onUpdateAgentProfile
        if (!canEdit || (agentDetailActions == null && callback == null) || savingDisplayName) {
            onUnavailableAction("Agent profile editing is not available.")
            return
        }
        val next = draftDisplayName.trim()
        if (next.isBlank()) {
            if (storeBackedProfileEdit) {
                profileStore.dispatch(
                    SharedAgentDetailIntent.SetDisplayNameError("Display name is required")
                )
            } else {
                localDisplayNameError = "Display name is required"
            }
            return
        }
        scope.launch {
            if (storeBackedProfileEdit) {
                profileStore.dispatch(SharedAgentDetailIntent.StartSaveDisplayName(next))
            } else {
                localSavingDisplayName = true
                localDisplayNameError = null
            }
            val result =
                try {
                    val request = AgentProfileUpdateRequest(
                        agentId = member.id,
                        displayName = next,
                        description = member.description?.takeIf { it.isNotBlank() }
                    )
                    if (agentDetailActions != null) {
                        agentDetailActions.updateAgentProfile(request)
                    } else {
                        requireNotNull(callback).invoke(request)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    DataResult.Error(ScreenError("agent_profile_save_failed"))
                }
            when (result) {
                is DataResult.Content -> {
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.FinishSaveDisplayName)
                    } else {
                        localEditingDisplayName = false
                    }
                }
                is DataResult.Empty -> {
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.FinishSaveDisplayName)
                    } else {
                        localEditingDisplayName = false
                    }
                }
                is DataResult.NoPermission -> {
                    val error = "No permission: ${result.blocker.code}"
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.SetDisplayNameError(error))
                    } else {
                        localDisplayNameError = error
                    }
                }
                is DataResult.Error -> {
                    val error = "Save failed: ${result.error.code}"
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.SetDisplayNameError(error))
                    } else {
                        localDisplayNameError = error
                    }
                }
            }
            if (!storeBackedProfileEdit) {
                localSavingDisplayName = false
            }
        }
    }

    fun saveRole() {
        val callback = onUpdateAgentProfile
        if (!canEdit || (agentDetailActions == null && callback == null) || savingRole) {
            onUnavailableAction("Agent profile editing is not available.")
            return
        }
        val next = draftRole.trim()
        if (next.length > MembersAgentProfileDescriptionMaxLength) {
            val error = "Description must be at most $MembersAgentProfileDescriptionMaxLength characters"
            if (storeBackedProfileEdit) {
                profileStore.dispatch(SharedAgentDetailIntent.SetRoleError(error))
            } else {
                localRoleError = error
            }
            return
        }
        scope.launch {
            if (storeBackedProfileEdit) {
                profileStore.dispatch(SharedAgentDetailIntent.StartSaveRole(next))
            } else {
                localSavingRole = true
                localRoleError = null
            }
            val result =
                try {
                    val request = AgentProfileUpdateRequest(
                        agentId = member.id,
                        displayName = member.displayName,
                        description = next.takeIf { it.isNotBlank() }
                    )
                    if (agentDetailActions != null) {
                        agentDetailActions.updateAgentProfile(request)
                    } else {
                        requireNotNull(callback).invoke(request)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    DataResult.Error(ScreenError("agent_profile_save_failed"))
                }
            when (result) {
                is DataResult.Content -> {
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.FinishSaveRole)
                    } else {
                        localEditingRole = false
                    }
                }
                is DataResult.Empty -> {
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.FinishSaveRole)
                    } else {
                        localEditingRole = false
                    }
                }
                is DataResult.NoPermission -> {
                    val error = "No permission: ${result.blocker.code}"
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.SetRoleError(error))
                    } else {
                        localRoleError = error
                    }
                }
                is DataResult.Error -> {
                    val error = "Save failed: ${result.error.code}"
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.SetRoleError(error))
                    } else {
                        localRoleError = error
                    }
                }
            }
            if (!storeBackedProfileEdit) {
                localSavingRole = false
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

        // PROFILE section — light divider style (no box border) per React parity.
        // Each field is its own column with eyebrow label + content, mirroring React's
        // `<div className="px-5 py-3"><SectionEyebrow>...</SectionEyebrow><p>...</p></div>`
        // pattern. Display Name and Description each have an independent pencil-edit
        // state machine (mirrors React `editingDisplayName` + `editingRole`).
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            // Display Name field
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Display Name",
                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                    color = SlockColor.TextMutedGray,
                    modifier = Modifier.weight(1f)
                )
                if (canEdit && !editingDisplayName) {
                    SlockBrutalIconButton(
                        iconName = SlockLucideIconName.Pencil,
                        onClick = {
                            if (storeBackedProfileEdit) {
                                profileStore.dispatch(
                                    SharedAgentDetailIntent.BeginEditDisplayName(member.displayName)
                                )
                            } else {
                                localDraftDisplayName = member.displayName
                                localDisplayNameError = null
                                localEditingDisplayName = true
                            }
                        },
                        size = MembersActionSize
                    )
                }
            }
            if (editingDisplayName) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
                ) {
                    SlockBrutalTextField(
                        label = "Display name",
                        value = draftDisplayName,
                        placeholder = "Display name",
                        onValueChange = {
                            if (storeBackedProfileEdit) {
                                profileStore.dispatch(
                                    SharedAgentDetailIntent.UpdateDisplayNameDraft(it)
                                )
                            } else {
                                localDraftDisplayName = it
                                if (localDisplayNameError != null) localDisplayNameError = null
                            }
                        },
                        enabled = !savingDisplayName,
                        autoFocus = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
                        SlockBrutalActionButton(
                            label = if (savingDisplayName) "Saving\u2026" else "Save",
                            onClick = { saveDisplayName() },
                            enabled = !savingDisplayName,
                            backgroundColor = SlockColor.BrandYellow
                        )
                        SlockBrutalActionButton(
                            label = "Cancel",
                            onClick = {
                                if (storeBackedProfileEdit) {
                                    profileStore.dispatch(
                                        SharedAgentDetailIntent.CancelEditDisplayName
                                    )
                                } else {
                                    localDraftDisplayName = member.displayName
                                    localDisplayNameError = null
                                    localEditingDisplayName = false
                                }
                            },
                            enabled = !savingDisplayName,
                            backgroundColor = SlockColor.SurfaceWhite
                        )
                    }
                    if (displayNameError != null) {
                        Text(
                            text = displayNameError.orEmpty(),
                            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp)
                                .withSlockFontWeight(FontWeight.Bold),
                            color = SlockColor.AccentRed
                        )
                    }
                }
            } else {
                Text(
                    text = member.displayName,
                    style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Description field (independent edit state machine)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = SlockSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Description",
                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                    color = SlockColor.TextMutedGray,
                    modifier = Modifier.weight(1f)
                )
                if (canEdit && !editingRole) {
                    SlockBrutalIconButton(
                        iconName = SlockLucideIconName.Pencil,
                        onClick = {
                            if (storeBackedProfileEdit) {
                                profileStore.dispatch(
                                    SharedAgentDetailIntent.BeginEditRole(member.description.orEmpty())
                                )
                            } else {
                                localDraftRole = member.description.orEmpty()
                                localRoleError = null
                                localEditingRole = true
                            }
                        },
                        size = MembersActionSize
                    )
                }
            }
            if (editingRole) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
                ) {
                    SlockBrutalTextField(
                        label = "Description",
                        value = draftRole,
                        placeholder = "Describe this agent",
                        onValueChange = {
                            if (storeBackedProfileEdit) {
                                profileStore.dispatch(
                                    SharedAgentDetailIntent.UpdateRoleDraft(it)
                                )
                            } else {
                                localDraftRole = it
                                if (localRoleError != null) localRoleError = null
                            }
                        },
                        enabled = !savingRole,
                        autoFocus = true,
                        maxLines = 4,
                        fieldMinHeight = 86.dp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SlockBrutalActionButton(
                            label = if (savingRole) "Saving\u2026" else "Save",
                            onClick = { saveRole() },
                            enabled = !savingRole,
                            backgroundColor = SlockColor.BrandYellow
                        )
                        SlockBrutalActionButton(
                            label = "Cancel",
                            onClick = {
                                if (storeBackedProfileEdit) {
                                    profileStore.dispatch(
                                        SharedAgentDetailIntent.CancelEditRole
                                    )
                                } else {
                                    localDraftRole = member.description.orEmpty()
                                    localRoleError = null
                                    localEditingRole = false
                                }
                            },
                            enabled = !savingRole,
                            backgroundColor = SlockColor.SurfaceWhite
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${draftRole.trim().length}/$MembersAgentProfileDescriptionMaxLength",
                            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                            color = SlockColor.TextMutedGray
                        )
                    }
                    if (roleError != null) {
                        Text(
                            text = roleError.orEmpty(),
                            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp)
                                .withSlockFontWeight(FontWeight.Bold),
                            color = SlockColor.AccentRed
                        )
                    }
                }
            } else {
                Text(
                    text = member.description?.takeIf { it.isNotBlank() }
                        ?: "No description",
                    style = SlockType.Body.copy(
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontStyle = if (member.description.isNullOrBlank()) FontStyle.Italic else FontStyle.Normal
                    ),
                    color = if (member.description.isNullOrBlank()) SlockColor.TextMutedGray else SlockColor.TextBlack,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // INFO section — mirrors React L1170-1238. Shows Computer (machine name + status
        // dot + Connected/Offline + daemon v?), Creator (button + avatar + name + @handle,
        // clickable). Created row is omitted because AgentDetailSummary doesn't carry a
        // createdAt field (React Agent type does); tracked as follow-up.
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = SlockSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Text(
                text = "Info",
                style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                color = SlockColor.TextMutedGray
            )
            // Computer row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Computer",
                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                    color = SlockColor.TextMutedGray,
                    modifier = Modifier.width(86.dp)
                )
                Text(
                    text = summary?.machineName.orBadgeFallback(member.membersComputerName),
                    style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    color = SlockColor.TextBlack,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val machineStatusLabel =
                    if (summary?.machineId != null) "Connected" else "Offline"
                Text(
                    text = machineStatusLabel,
                    style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = SlockColor.TextMutedGray
                )
                if (summary?.daemonVersion != null) {
                    Text(
                        text = " · daemon v${summary.daemonVersion}",
                        style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                        color = SlockColor.TextMutedGray
                    )
                }
            }
            // Creator row (clickable button — React opens the creator's profile)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Creator",
                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                    color = SlockColor.TextMutedGray,
                    modifier = Modifier.width(86.dp)
                )
                Text(
                    text = summary?.creatorLabel.orDash(),
                    style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // RUNTIME CONFIG section — mirrors React L1240-1320. Each chip is rendered via
        // `MembersAgentRuntimeChip` (a purpose-built component for runtime semantic chips;
        // we don't reuse the deleted `SlockBrutalTag` because runtime chips need their
        // own color-by-kind mapping and shouldn't be confused with kind badges).
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = SlockSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Text(
                text = "Runtime Config",
                style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                color = SlockColor.TextMutedGray
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                val runtimeLabel = summary?.runtime.orBadgeFallback(member.roleDescription)
                if (runtimeLabel.isNotBlank()) {
                    MembersAgentRuntimeChip(
                        label = runtimeLabel,
                        kind = MembersAgentRuntimeChipKind.Runtime
                    )
                }
                if (!summary?.model.isNullOrBlank()) {
                    MembersAgentRuntimeChip(
                        label = summary.model.orDash(),
                        kind = MembersAgentRuntimeChipKind.Model
                    )
                }
                if (!summary?.reasoning.isNullOrBlank()) {
                    MembersAgentRuntimeChip(
                        label = summary.reasoning.orDash(),
                        kind = MembersAgentRuntimeChipKind.Reasoning
                    )
                }
                if (!summary?.executionMode.isNullOrBlank()) {
                    MembersAgentRuntimeChip(
                        label = summary.executionMode.orDash(),
                        kind = MembersAgentRuntimeChipKind.Mode
                    )
                }
            }
        }

        // ENV VARS section — mirrors React `EnvVarsSection` L235+ inline span + brutal
        // pill style. KMP currently exposes only `envVarNames` (not key=value pairs), so
        // we render just the key as an inline span (no `=` masked value bullet). Adding
        // full env var values + native PasswordVisualTransformation is tracked as a
        // follow-up that also requires the data layer to surface `envVars: Map<String,
        // String>` on `AgentDetailSummary`.
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = SlockSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Text(
                text = "Environment Variables",
                style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                color = SlockColor.TextMutedGray
            )
            val envVarNames = summary?.envVarNames.orEmpty()
            if (envVarNames.isEmpty()) {
                Text(
                    text = "No environment variables configured",
                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                    color = SlockColor.TextMutedGray,
                    fontStyle = FontStyle.Italic
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
                ) {
                    envVarNames.take(8).forEach { name ->
                        Box(
                            modifier = Modifier
                                .background(SlockColor.SurfaceWhite)
                                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                                .padding(horizontal = SlockSpacing.Xs, vertical = 2.dp)
                        ) {
                            Text(
                                text = name,
                                style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                                color = SlockColor.TextBlack,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Avatar editing is surfaced from the top-left avatar in `MembersDetailHeader`
        // (PUX #49 spec v4 lock). Clicking it opens the floating `MembersAgentAvatarPicker`
        // overlay, owned by `MembersDetailContent`. The body AVATAR section + the in-page
        // picker invocation that previously lived here were removed in this commit so the
        // avatar entry point is consistent with React `AgentDetailPanel.tsx`.
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
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        // #67 React parity: AGENT PRIVATE CHATS header + caption explaining the
        // audit-trail nature of the rows (mirrors React `AgentDmsTab.tsx`
        // 'Visible here as an activity/audit trail...' caption).
        Text(
            text = "AGENT PRIVATE CHATS",
            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp)
                .withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.TextMutedGray
        )
        Text(
            text = "Visible here as an activity/audit trail. These rows are intentionally not direct conversation links.",
            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
            color = SlockColor.TextMutedGray.copy(alpha = 0.7f)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SlockStroke.Thin)
                .background(SlockColor.BorderBlack.copy(alpha = 0.15f))
        )
        val rows = detail?.directMessages.orEmpty()
        if (rows.isEmpty()) {
            Text(
                text = "No agent-to-agent DMs yet.",
                style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = SlockColor.TextMutedGray
            )
        } else {
            rows.take(8).forEach { row ->
                MembersAgentDmRow(row)
            }
        }
    }
}

@Composable
private fun MembersAgentDmRow(row: AgentDmSummary) {
    // React parity: subtle bordered card (alpha 0.2 border, no brutal shadow)
    // with avatar + name + handle + relative time on the top row, body text
    // on the second row, and an ACTIVITY ONLY tag on the third row.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Thin, SlockColor.BorderBlack.copy(alpha = 0.2f))
            .background(SlockColor.SurfaceWhite)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Agent DM",
                style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = SlockColor.TextMutedGray
            )
            SlockAvatar(
                avatarWireValue = row.peerAvatarUrl,
                label = row.peerName.take(1).uppercase(),
                avatarSize = 22.dp,
                borderWidth = SlockStroke.Thin
            )
            Text(
                text = row.peerName,
                style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "@${row.peerName}",
                style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 1
            )
            Text(
                text = row.lastMessageAt.membersDmRelativeTime(),
                style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 1
            )
        }
        if (!row.lastMessagePreview.isNullOrBlank()) {
            Text(
                text = row.lastMessagePreview,
                style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = SlockColor.TextBlack,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "ACTIVITY ONLY",
            style = SlockType.Caption.copy(fontSize = 9.sp, lineHeight = 12.sp)
                .withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.TextMutedGray.copy(alpha = 0.7f)
        )
    }
}

private fun String?.membersDmRelativeTime(): String {
    val raw = this?.trim()?.takeIf { it.isNotBlank() } ?: return ""
    // Try ISO timestamp first.
    val epochMillis = parseSlockTimestampEpochMillisOrNull(raw)
    if (epochMillis != null) {
        val now = System.currentTimeMillis()
        val diffMs = now - epochMillis
        if (diffMs < 0L) return raw
        val minutes = diffMs / 60_000L
        val hours = diffMs / 3_600_000L
        val days = diffMs / 86_400_000L
        return when {
            minutes < 1L -> "just now"
            minutes < 60L -> "$minutes minute${if (minutes == 1L) "" else "s"} ago"
            hours < 24L -> "$hours hour${if (hours == 1L) "" else "s"} ago"
            days < 30L -> "$days day${if (days == 1L) "" else "s"} ago"
            else -> raw
        }
    }
    // Otherwise pass through (already-friendly strings like '4 days ago').
    return raw
}

@Composable
private fun MembersAgentRemindersTab(
    detail: AgentDetailSnapshot?,
    timeContext: SlockTimeContext,
    modifier: Modifier = Modifier
) {
    val rows = detail?.reminders.orEmpty()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (rows.isEmpty()) {
            SlockEmptyState(
                title = "No reminders yet",
                detail = "This agent hasn't scheduled anything. Reminders appear here in real time as soon as the agent schedules them.",
                iconName = SlockLucideIconName.BellRing,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 520.dp)
            )
        } else {
            rows.take(8).forEach { reminder ->
                MembersAgentReminderCard(reminder, timeContext)
            }
        }
    }
}

@Composable
private fun MembersAgentReminderCard(
    reminder: AgentReminderSummary,
    timeContext: SlockTimeContext
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Default, SlockColor.BorderBlack.copy(alpha = 0.3f))
            .background(SlockColor.SurfaceWhite)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Text(
            text = reminder.title,
            style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp)
                .withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.TextBlack,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SlockLucideIcon(
                    SlockLucideIconName.Clock3,
                    modifier = Modifier.size(12.dp),
                    color = "#99000000"
                )
                Text(
                    text = reminder.fireAt.membersReminderRelativeTime(timeContext),
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Medium)
                        .copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = SlockColor.TextBlack.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = reminder.fireAt.membersReminderShortDateTime(timeContext),
                style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = SlockColor.TextBlack.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        reminder.recurrenceDescription?.takeIf { it.isNotBlank() }?.let { recurrence ->
            MembersAgentReminderChip(
                iconName = SlockLucideIconName.RefreshCw,
                text = recurrence,
                background = SlockColor.AccentLavender.copy(alpha = 0.3f),
                textColor = SlockColor.TextBlack
            )
        }
        reminder.messageRef?.takeIf { it.isNotBlank() }?.let { messageRef ->
            MembersAgentReminderChip(
                iconName = SlockLucideIconName.Link2,
                text = messageRef,
                background = SlockColor.SurfaceWhite,
                textColor = SlockColor.TextBlack.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun MembersAgentReminderChip(
    iconName: SlockLucideIconName,
    text: String,
    background: Color,
    textColor: Color
) {
    Row(
        modifier = Modifier
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockLucideIcon(iconName, modifier = Modifier.size(11.dp), color = SlockColor.TextBlackArgb)
        Text(
            text = text,
            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MembersAgentWorkspaceTab(
    treeState: MembersAgentWorkspaceTreeState,
    expandedDirs: Set<String>,
    loadedDirs: Map<String, List<SlockWorkspaceNode>>,
    loadingDirs: Set<String>,
    dirErrors: Map<String, String>,
    onRefresh: () -> Unit,
    onToggleDirectory: (String) -> Unit,
    onSelectFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    MembersDetailSection(
        title = "WORKSPACE",
        modifier = modifier,
        framed = false,
        trailingContent = {
            SlockBrutalIconButton(
                iconName = SlockLucideIconName.RefreshCw,
                onClick = onRefresh,
                size = 30.dp,
                shadowOffset = 2.dp
            )
        }
    ) {
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
            is MembersAgentWorkspaceTreeState.Content ->
                MembersWorkspaceTreeView(
                    root = treeState.snapshot.root,
                    expandedDirs = expandedDirs,
                    loadedDirs = loadedDirs,
                    loadingDirs = loadingDirs,
                    dirErrors = dirErrors,
                    onToggleDirectory = onToggleDirectory,
                    onSelectFile = onSelectFile
                )
        }
    }
}

@Composable
private fun MembersWorkspaceTreeView(
    root: SlockWorkspaceNode,
    expandedDirs: Set<String>,
    loadedDirs: Map<String, List<SlockWorkspaceNode>>,
    loadingDirs: Set<String>,
    dirErrors: Map<String, String>,
    onToggleDirectory: (String) -> Unit,
    onSelectFile: (String) -> Unit
) {
    val rows = remember(root, expandedDirs, loadedDirs) {
        membersWorkspaceRows(root.children, expandedDirs, loadedDirs)
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
                if (loading) {
                    // #70: replace the chevron with a rotating Loader spinner
                    // while the directory contents are being fetched. Mirrors
                    // React's `AgentWorkspace.tsx` spinner pattern (but KMP
                    // per @artin's 06-23 20:51 directive shows the spinner
                    // inline as the chevron substitute instead of alongside
                    // a "Loading..." text label).
                    SlockSpinnerIcon(size = 13.dp)
                } else {
                    SlockLucideIcon(
                        if (expanded) SlockLucideIconName.ChevronDown else SlockLucideIconName.ChevronRight,
                        Modifier.size(13.dp),
                    )
                }
            } else {
                Spacer(Modifier.width(13.dp))
            }
            SlockLucideIcon(node.membersWorkspaceIconName(expanded), Modifier.size(14.dp))
            Text(
                text = node.name.ifBlank { node.path },
                style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp)
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
    MembersDetailSection(
        title = "ACTIVITY DIAGNOSTICS",
        modifier = modifier,
        framed = false,
        trailingContent = {
            SlockBrutalActionButton(
                label = diagnosticActionState?.takeIf { it.actionKey == "copy" }?.successLabel ?: "Copy Diagnostic Info",
                leadingIcon = SlockLucideIconName.Clipboard,
                enabled = diagnosticActionState?.inProgress != true,
                modifier = Modifier.width(168.dp),
                onClick = onCopyDiagnostics
            )
        }
    ) {
        // #63 React parity: wrap the activity list in a `SlockBrutalCard` so
        // the visible list surface matches the brutal bordered box with
        // right + bottom shadow used in React's `AgentDetailPanel.tsx`
        // activity section. The previous flat layout (no border, no shadow)
        // contributed to the 12.66% pixel mismatch on
        // `screens.members.agent-detail.activity`.
        val rows =
            remember(detail?.agentId, detail?.activity) {
                detail?.activity.orEmpty()
                    .filterNot { it.type == "tool_start" && it.toolName?.normalizedMembersToolName() == "send_message" }
                    .sortedWith(compareBy<AgentActivityEntry> { it.timestamp.membersActivitySortKey() }.thenBy { it.id })
            }
        if (rows.isEmpty()) {
            SlockEmptyState(
                title = "No activity yet",
                detail = "Start the agent to see its activity log.",
                iconName = SlockLucideIconName.Activity,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 520.dp)
            )
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
            SlockBrutalCard(
                shadowOffset = 4.dp,
                contentPadding = 0.dp,
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 520.dp)
                        .nestedScroll(
                            scrollUp = NestedScrollMode.SELF_FIRST,
                            scrollDown = NestedScrollMode.SELF_FIRST
                        ),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    beyondBoundsItemCount = sharedListBeyondBoundsItemCount(rows.size)
                ) {
                    items(rows.size, key = { rows[it].activityTimelineKey() }) { index ->
                        MembersAgentActivityRow(rows[index], timeContext, onOpenUrl)
                    }
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
                    style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp)
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
                // #64 owner override (artin 06-23 17:41): '改成用 SlockMarkdownBody
                // 比较好，它能展示出来 #xxx 这些东西'. Restore the markdown branch so
                // the activity body can render task refs / issue refs (e.g. `#225`,
                // `#task-7`) as styled inline text + clickable onOpenUrl links
                // when `refTextEnabled` is true.
                if (activity.refTextEnabled) {
                    SlockMarkdownBody(
                        body = displayBody,
                        textStyle = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                        onOpenUrl = onOpenUrl,
                    )
                } else {
                    Text(
                        text = displayBody,
                        style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
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
    // Mirrors React `AgentDetailPanel.tsx` header agent/human tag style:
    // inline span with brutal pill (border-2 border-black + bg-[color] + px-2 py-0.5 +
    // text-xs font-bold). The header tag is not its own React component, so we don't
    // introduce a generic `SlockBrutalTag` wrapper either — we render the same shape
    // inline so KMP stays aligned with the React source pattern.
    Box(
        modifier = Modifier
            .background(kind.membersAvatarColor)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .padding(horizontal = SlockSpacing.Xs, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = kind.name.uppercase(),
            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp)
                .withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
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
    framed: Boolean = true,
    trailingContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Text(
                text = title,
                style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            trailingContent?.invoke()
        }
        val contentModifier =
            if (framed) {
                Modifier
                    .fillMaxWidth()
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .background(SlockColor.SurfaceWhite)
                    .padding(SlockSpacing.Md)
            } else {
                Modifier.fillMaxWidth()
            }
        Column(
            modifier = contentModifier,
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
private fun MembersPageHeader(
    title: String,
    refreshing: Boolean = false
) {
    SlockTopPageHeader(
        title = title,
        subtitle = "Workspace members",
        iconName = SlockLucideIconName.Users,
        refreshing = refreshing
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
                style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.5f, fill = false)
            )
            Text(
                text = member.description ?: member.handle,
                style = SlockType.Body.withSlockFontWeight(FontWeight.Normal).copy(fontSize = 14.sp, lineHeight = 20.sp),
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

private fun KuiklyMemberDetailPayload.toSlockTimeContext(): SlockTimeContext =
    SlockTimeContext(
        localOffsetMinutes = localOffsetMinutes,
        todayEpochDay = todayEpochDay,
        nowEpochMillis = nowEpochMillis,
        timeFormat = timeFormat
    )

private fun AgentDetailSnapshot.toMemberProfile(server: ServerSummary): MemberProfile {
    val summary = summary
    val handle = summary?.name?.takeIf { it.isNotBlank() } ?: agentId.value
    val label = summary?.displayName?.takeIf { it.isNotBlank() } ?: handle
    val status = AgentStatus.fromWireValue(summary?.activity ?: summary?.status) ?: AgentStatus.Offline
    val avatarUrl = summary?.avatarUrl?.takeIf { it.isNotBlank() }
    return MemberProfile(
        id = agentId,
        displayName = label,
        handle = handle,
        kind = MemberKind.Agent,
        description =
            summary?.activityDetail?.takeIf { it.isNotBlank() }
                ?: summary?.activity?.takeIf { it.isNotBlank() },
        roleDescription = summary?.runtime?.takeIf { it.isNotBlank() },
        avatarUrl = avatarUrl,
        avatar = AvatarReference.fromWireValue(avatarUrl),
        avatarLabel = label.take(1).uppercase(),
        presence = status.presence,
        agentStatus = status,
        permissionBadges = listOfNotNull(
            summary?.runtime?.takeIf { it.isNotBlank() },
            summary?.machineName?.takeIf { it.isNotBlank() }?.let { "computer:$it" },
            summary?.machineId?.takeIf { it.isNotBlank() }?.let { "computer-id:$it" }
        ),
        serverId = server.id,
        serverName = server.name.takeIf { it.isNotBlank() },
        serverSlug = server.slug?.takeIf { it.isNotBlank() }
    )
}

private fun MemberDetailRouteState.withMemberDetailProfile(member: MemberProfile): MemberDetailRouteState {
    val content = this as? MemberDetailRouteState.Content ?: return this
    return if (content.member.id == member.id) {
        content.copy(member = member)
    } else {
        this
    }
}

private fun MemberDetailRouteState.withMemberDetailLifecycleResult(
    action: MembersAgentLifecycleAction,
    agentId: MemberId
): MemberDetailRouteState {
    val content = this as? MemberDetailRouteState.Content ?: return this
    if (content.member.id != agentId) return this
    return when (action) {
        MembersAgentLifecycleAction.Delete ->
            MemberDetailRouteState.Error("agent_deleted")
        MembersAgentLifecycleAction.Start,
        MembersAgentLifecycleAction.Restart ->
            content.copy(
                member = content.member.copy(agentStatus = AgentStatus.Working, presence = Presence.Active)
            )
        MembersAgentLifecycleAction.Stop ->
            content.copy(
                member = content.member.copy(agentStatus = AgentStatus.Offline, presence = Presence.Offline)
            )
        MembersAgentLifecycleAction.UnassignMachine ->
            content.copy(
                member =
                    content.member.copy(
                        permissionBadges =
                            content.member.permissionBadges.filterNot {
                                it.startsWith("computer:") || it.startsWith("computer-id:")
                            }
                    )
            )
        MembersAgentLifecycleAction.ResetSession,
        MembersAgentLifecycleAction.ResetFull ->
            content
    }
}

private fun String.toMemberDetailDiagnosticsLine(): String =
    lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(" | ")
        .take(1200)

private fun String?.membersActivityDisplayTime(timeContext: SlockTimeContext): String =
    formatSlockTimestampClockWithSeconds(this, timeContext)
        .takeIf { it.isNotBlank() }
        ?: "No time"

private fun String?.membersReminderRelativeTime(timeContext: SlockTimeContext): String {
    val trimmed = this?.trim().orEmpty()
    if (trimmed.isEmpty()) return ""
    val fireAtMillis =
        parseSlockTimestampEpochMillisOrNull(trimmed, timeContext.localOffsetMinutes) ?: return trimmed
    val nowMillis = timeContext.nowEpochMillis ?: return trimmed.membersReminderShortDateTime(timeContext)
    val diffMillis = fireAtMillis - nowMillis
    val absMillis = abs(diffMillis)
    val unitMillis =
        when {
            absMillis < MembersReminderMillisPerHour -> MembersReminderMillisPerMinute
            absMillis < MembersReminderMillisPerDay -> MembersReminderMillisPerHour
            else -> MembersReminderMillisPerDay
        }
    val unit =
        when (unitMillis) {
            MembersReminderMillisPerMinute -> "minute"
            MembersReminderMillisPerHour -> "hour"
            else -> "day"
        }
    val amount = (diffMillis.toDouble() / unitMillis.toDouble()).roundToLong()
    val absoluteAmount = abs(amount)
    if (absoluteAmount == 0L) return "now"
    val label = "$absoluteAmount $unit${if (absoluteAmount == 1L) "" else "s"}"
    return if (amount > 0L) "in $label" else "$label ago"
}

private fun String?.membersReminderShortDateTime(timeContext: SlockTimeContext): String {
    val trimmed = this?.trim().orEmpty()
    if (trimmed.isEmpty()) return ""
    val epochMillis =
        parseSlockTimestampEpochMillisOrNull(trimmed, timeContext.localOffsetMinutes) ?: return trimmed
    val localMillis = epochMillis + timeContext.localOffsetMinutes.toLong() * MembersReminderMillisPerMinute
    val epochDay = membersReminderFloorDiv(localMillis, MembersReminderMillisPerDay)
    val millisOfDay = membersReminderFloorMod(localMillis, MembersReminderMillisPerDay)
    val date = membersReminderCivilDateFromEpochDay(epochDay)
    val hour = (millisOfDay / MembersReminderMillisPerHour).toInt()
    val minute = ((millisOfDay % MembersReminderMillisPerHour) / MembersReminderMillisPerMinute).toInt()
    return "${MembersReminderMonthNames[date.month - 1]} ${date.day}, ${membersReminderClock(hour, minute, timeContext.timeFormat)}"
}

private fun membersReminderClock(hour: Int, minute: Int, timeFormat: String): String {
    if (timeFormat != SlockTimeFormat12Hour) {
        return "${hour.membersReminderTwoDigits()}:${minute.membersReminderTwoDigits()}"
    }
    val displayHour = (hour % 12).let { if (it == 0) 12 else it }
    return "${displayHour.membersReminderTwoDigits()}:${minute.membersReminderTwoDigits()} ${if (hour < 12) "AM" else "PM"}"
}

private data class MembersReminderCivilDate(
    val year: Int,
    val month: Int,
    val day: Int
)

private fun membersReminderCivilDateFromEpochDay(epochDay: Long): MembersReminderCivilDate {
    val zeroDay = epochDay + 719_468L
    val era = membersReminderFloorDiv(zeroDay, 146_097L)
    val dayOfEra = zeroDay - era * 146_097L
    val yearOfEra =
        (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    val year = yearOfEra + era * 400L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val day = dayOfYear - (153L * monthPrime + 2L) / 5L + 1L
    val month = monthPrime + if (monthPrime < 10L) 3L else -9L
    val adjustedYear = year + if (month <= 2L) 1L else 0L
    return MembersReminderCivilDate(adjustedYear.toInt(), month.toInt(), day.toInt())
}

private fun membersReminderFloorDiv(value: Long, divisor: Long): Long {
    val quotient = value / divisor
    val remainder = value % divisor
    return if (remainder != 0L && (value xor divisor) < 0L) quotient - 1 else quotient
}

private fun membersReminderFloorMod(value: Long, divisor: Long): Long =
    value - membersReminderFloorDiv(value, divisor) * divisor

private fun Int.membersReminderTwoDigits(): String =
    if (this < 10) "0$this" else toString()

private val MembersReminderMonthNames =
    listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private const val MembersReminderMillisPerMinute = 60_000L
private const val MembersReminderMillisPerHour = 3_600_000L
private const val MembersReminderMillisPerDay = 86_400_000L

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

/**
 * #70: Rotating Loader spinner used by the workspace file tree as a chevron
 * substitute while a directory's children are being fetched
 * (`loadingDirs.contains(node.path)`). Mirrors the React
 * `AgentWorkspace.tsx` `<Spinner size="xs" />` pattern, but the KMP form
 * drops the React "Loading..." text per @artin's 06-23 20:51 directive
 * ("不要用文字做到最后，就把那个文件夹的 icon 改成 loading spinner 就行").
 *
 * 900ms rotation cycle matches the visual rhythm of the React inline
 * spinner without being so fast it looks nervous on the cheap-ohm
 * emulator display.
 */
@Composable
private fun SlockSpinnerIcon(
    size: Dp = 14.dp,
    color: String = SlockColor.TextBlackArgb
) {
    val transition = rememberInfiniteTransition(label = "slock-workspace-spinner")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Restart
        ),
        label = "slock-workspace-spinner-rotation"
    )
    SlockLucideIcon(
        SlockLucideIconName.Loader,
        modifier = Modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation },
        color = color
    )
}

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
