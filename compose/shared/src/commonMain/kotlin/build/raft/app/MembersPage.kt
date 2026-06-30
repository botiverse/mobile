package build.raft.app

import build.raft.app.base.Utils
import build.raft.app.base.BridgeModule
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.components.SlockAnchorPlacement
import build.raft.app.components.SlockBrutalInputField
import build.raft.app.components.SlockBrutalModalLayer
import build.raft.app.components.SlockModalShellModel
import build.raft.app.PixelAvatarDefaultKey
import build.raft.app.PixelAvatarPickerKeys
import build.raft.app.components.SlockBrutalCard
import build.raft.app.components.SlockCheckbox
import build.raft.app.components.slockAnchoredTriggerGeometryAnchor
import build.raft.app.data.AttachmentUploadFile
import build.raft.app.data.AgentActivityEntry
import build.raft.app.data.AgentAppLoginItem
import build.raft.app.data.AgentAppLoginLoadState
import build.raft.app.data.AgentAppLoginSnapshot
import build.raft.app.data.AgentAppLoginType
import build.raft.app.data.AgentAvatarUploadRequest
import build.raft.app.data.AgentCreatedSummary
import build.raft.app.data.AgentCreatorSummary
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentScopeSet
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AgentDmSummary
import build.raft.app.data.AgentLifecycleActionResult
import build.raft.app.data.AgentMachineAssignmentRequest
import build.raft.app.data.AgentReminderSummary
import build.raft.app.data.AgentResetMode
import build.raft.app.data.AgentSkillGroup
import build.raft.app.data.AgentSkillPathGroup
import build.raft.app.data.AgentSkillSummary
import build.raft.app.data.AgentSkillsSnapshot
import build.raft.app.data.AgentWorkspaceChildrenSnapshot
import build.raft.app.data.AgentWorkspaceTreeSnapshot
import build.raft.app.data.AgentProfileUpdateRequest
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.MemberRepository
import build.raft.app.data.SharedAgentDetailActions
import build.raft.app.data.SharedAgentDetailIntent
import build.raft.app.data.SharedAgentDetailLoadStatus
import build.raft.app.data.SharedAgentDetailState
import build.raft.app.data.SharedAgentDetailStore
import build.raft.app.data.SharedAgentPanelTabOrderStore
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.parseSharedThreadPickedAttachmentFiles
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.ScreenError
import build.raft.app.data.SlockAgentsApiClient
import build.raft.app.data.SlockAgentsNetworkApi
import build.raft.app.data.SlockComputersApiClient
import build.raft.app.data.SlockComputersNetworkApi
import build.raft.app.data.SharedComputersIntent
import build.raft.app.data.SharedComputersLoadStatus
import build.raft.app.data.SharedComputersPickerSlice
import build.raft.app.data.SharedComputersStore
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.defaultNowMillis
import build.raft.app.markdown.SlockMarkdownBody
import build.raft.app.model.AgentProfile
import build.raft.app.model.AgentStatus
import build.raft.app.model.AvatarReference
import build.raft.app.model.RUNTIME_PROVIDER_MODE_CUSTOM
import build.raft.app.model.RUNTIME_PROVIDER_MODE_DEFAULT
import build.raft.app.model.RUNTIMES
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeModeConfig
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.RuntimeProviderConfig
import build.raft.app.model.getModelLabel
import build.raft.app.model.getDefaultModel
import build.raft.app.model.getRuntimeDisplayName
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.buildRuntimeConfig
import build.raft.app.model.supportsRuntimeFastMode
import build.raft.app.model.supportsRuntimeReasoningEffort
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.SlockWorkspaceFileKind
import build.raft.app.model.SlockWorkspaceNode
import build.raft.app.model.SlockTimeContext
import build.raft.app.model.SlockTimeFormat12Hour
import build.raft.app.model.SlockTimeFormat24Hour
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.app.model.formatSlockTimestampClockWithSeconds
import build.raft.app.model.parseSlockTimestampEpochMillisOrNull
import build.raft.app.model.toMemberProfile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.gestures.detectDragGesturesAfterLongPress
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.ExperimentalLayoutApi
import com.tencent.kuikly.compose.foundation.layout.FlowRow
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.requiredWidth
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.lazy.LazyListState
import com.tencent.kuikly.compose.foundation.lazy.LazyRow
import com.tencent.kuikly.compose.foundation.lazy.rememberLazyListState
import com.tencent.kuikly.compose.extension.NestedScrollMode
import com.tencent.kuikly.compose.extension.nestedScroll
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.material3.pullToRefreshItem
import com.tencent.kuikly.compose.material3.rememberPullToRefreshState
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.text.font.FontStyle
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.pager.Pager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

const val MEMBERS_PAGE = "compose_members"
const val MEMBER_DETAIL_PAGE = "compose_member_detail"
const val MEMBERS_SMOKE_PAGE = "compose_members_smoke"
private const val MEMBERS_PAGE_DATA_EVENT = "slockPageData"

private val MembersPageInset = SlockSpacing.Md
private val MemberAvatarSize = 34.dp
private val MemberRowMinHeight = 48.dp
private val MemberStatusDotSize = 10.dp
private val MembersActionSize = 32.dp
private val MembersHeaderMessageButtonWidth = 116.dp
private val MembersAgentActivityTimeColumnWidth = 60.dp
private val MembersAgentActivityIndicatorLaneWidth = 13.dp
private val MembersAgentActivityContentGap = 6.dp

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

internal fun membersOrderedAgentTabs(
    visibleTabs: List<MembersAgentDetailTab>,
    storedOrder: List<String>
): List<MembersAgentDetailTab> {
    if (visibleTabs.isEmpty() || storedOrder.isEmpty()) return visibleTabs
    val visibleByKey = visibleTabs.associateBy { it.persistedOrderKey }
    val ordered = mutableListOf<MembersAgentDetailTab>()
    storedOrder.forEach { rawKey ->
        val tab = visibleByKey[rawKey.toMembersAgentTabOrderKey()] ?: return@forEach
        if (tab !in ordered) ordered += tab
    }
    return ordered + visibleTabs.filterNot { it in ordered }
}

private val MembersAgentDetailTab.persistedOrderKey: String
    get() =
        when (this) {
            MembersAgentDetailTab.Dms -> "dms"
            else -> name.lowercase()
        }

internal val MembersAgentDetailTab.serverOrderKey: String
    get() =
        when (this) {
            MembersAgentDetailTab.Dms -> "chat"
            MembersAgentDetailTab.Apps -> "integrations"
            else -> name.lowercase()
        }

internal fun List<MembersAgentDetailTab>.toMembersAgentPanelTabOrderWireKeys(): List<String> =
    map { it.serverOrderKey }

internal fun List<MembersAgentDetailTab>.membersMoveTab(
    fromIndex: Int,
    toIndex: Int
): List<MembersAgentDetailTab> {
    if (fromIndex !in indices || toIndex !in indices || fromIndex == toIndex) return this
    val mutable = toMutableList()
    val item = mutable.removeAt(fromIndex)
    mutable.add(toIndex, item)
    return mutable
}

internal fun membersAgentTabVisibleAnchorIndex(
    tabs: List<MembersAgentDetailTab>,
    selectedTab: MembersAgentDetailTab
): Int {
    val selectedIndex = tabs.indexOf(selectedTab)
    return when {
        selectedIndex <= 0 -> 0
        else -> selectedIndex - 1
    }
}

private fun String.toMembersAgentTabOrderKey(): String =
    trim().lowercase()
        .replace("-", "_")
        .let { key ->
            when (key) {
                "chat", "channels", "agent_dms", "agentdms", "dm", "direct_messages", "directmessages" -> "dms"
                "integrations", "connected_apps", "connectedapps", "app_logins", "applogins" -> "apps"
                else -> key
            }
        }

private fun String?.toMembersAgentDetailTabOrNull(): MembersAgentDetailTab? {
    val normalized = this?.toMembersAgentTabOrderKey() ?: return null
    return MembersAgentDetailTab.entries.firstOrNull { tab ->
        tab.persistedOrderKey == normalized ||
        tab.name.lowercase() == normalized || tab.label.lowercase().replace(" ", "_") == normalized
    }
}

private sealed interface MembersAgentDetailLoadState {
    data object Idle : MembersAgentDetailLoadState
    data object Loading : MembersAgentDetailLoadState
    data class Content(
        val detail: AgentDetailSnapshot,
        val refreshing: Boolean = false
    ) : MembersAgentDetailLoadState
    data class NoPermission(val code: String) : MembersAgentDetailLoadState
    data class Error(val code: String) : MembersAgentDetailLoadState
    data object Empty : MembersAgentDetailLoadState
}

private fun SharedAgentDetailState.toMembersAgentDetailLoadState(): MembersAgentDetailLoadState =
    when (detailLoadStatus) {
        SharedAgentDetailLoadStatus.Idle -> MembersAgentDetailLoadState.Idle
        SharedAgentDetailLoadStatus.Loading -> detail?.let { MembersAgentDetailLoadState.Content(it, refreshing = true) }
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
internal class ComposeMembersPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { MembersPageContent() }
    }
}

@Page(MEMBER_DETAIL_PAGE, supportInLocal = true)
internal class ComposeMemberDetailPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { MemberDetailPageContent() }
    }
}

@Serializable
data class KuiklyMembersPayload(
    val instanceId: String = "",
    val title: String = "Members",
    val labels: KuiklyMembersLabels = KuiklyMembersLabels(),
    val members: List<MemberProfile> = emptyList(),
    val initialAgentTab: String? = null,
    val localOffsetMinutes: Int = 0,
    val todayEpochDay: Long? = null,
    val nowEpochMillis: Long? = null,
    val timeFormat: String = SlockTimeFormat24Hour,
    val agentPanelTabOrder: List<String> = emptyList(),
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
    val memberKind: String = "",
    val sourceServerId: String = "",
    val initialAgentTab: String? = null,
    val localOffsetMinutes: Int = 0,
    val todayEpochDay: Long? = null,
    val nowEpochMillis: Long? = null,
    val timeFormat: String = SlockTimeFormat24Hour,
    val agentPanelTabOrder: List<String> = emptyList()
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

private val membersHumanDetailVisualMember = MemberProfile(
    id = MemberId("visual-human-1"),
    displayName = "artin",
    handle = "@artin",
    kind = MemberKind.Human,
    description = "Owner",
    roleDescription = "Owner",
    email = "artin@slock.ai",
    avatarLabel = "A",
    presence = Presence.Active,
    joinedAt = "2026-06-18T00:00:00.000Z",
    membershipStatus = "active"
)

private val membersAgentDetailVisualScopeSet = AgentScopeSet(
    agentId = membersAgentDetailVisualMember.id,
    granted = listOf(
        "inbox:receive",
        "server:read",
        "channel:read",
        "message:read",
        "message:send",
        "task:read",
        "task:write",
        "knowledge:read"
    ),
    mode = "custom",
    revision = 7L,
    updatedAt = "2026-06-19T12:28:00.000Z"
)

private val membersAgentDetailVisualSnapshot = AgentDetailSnapshot(
    agentId = membersAgentDetailVisualMember.id,
    summary = AgentDetailSummary(
        id = membersAgentDetailVisualMember.id.value,
        name = "Product-UX-Designer",
        displayName = membersAgentDetailVisualMember.displayName,
        createdAt = "2026-06-01T09:15:00Z",
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
        creator = AgentCreatorSummary(
            type = "human",
            id = "artin",
            name = "artin",
            displayName = "artin",
            avatarUrl = "pixel:user",
            gravatarHash = null
        ),
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
    ),
    appLogins = AgentAppLoginSnapshot(
        agentId = membersAgentDetailVisualMember.id,
        items = listOf(
            AgentAppLoginItem(
                id = "grant-raft-actions-product-ux",
                type = AgentAppLoginType.Active,
                clientId = "raft-actions",
                clientKey = "raft-actions",
                clientName = "Raft Actions",
                clientDescription = "Executes approved action cards and task updates through Raft Agent Login.",
                clientHomepageUrl = "https://raft.local/actions",
                clientAgentManifestUrl = "https://raft.local/.well-known/agent.json",
                scopes = listOf("messages:read", "tasks:write"),
                remember = true,
                createdAt = "2026-06-19T12:20:00Z",
                resolvedAt = "2026-06-19T12:21:00Z",
                resolvedByUserId = "artin"
            ),
            AgentAppLoginItem(
                id = "grant-calendar-product-ux",
                type = AgentAppLoginType.Active,
                clientId = "calendar",
                clientKey = "calendar",
                clientName = "Calendar",
                clientDescription = "Reads launch review reminders and scheduled UX follow-ups.",
                clientHomepageUrl = "https://calendar.local",
                scopes = listOf("calendar:read"),
                remember = false,
                createdAt = "2026-06-18T09:00:00Z",
                resolvedAt = "2026-06-18T09:01:00Z",
                resolvedByUserId = "artin"
            ),
            AgentAppLoginItem(
                id = "request-drafts-product-ux",
                type = AgentAppLoginType.Pending,
                clientId = "drafts",
                clientName = "Drafts",
                clientDescription = "Pending request hidden to match React active-only Apps tab.",
                scopes = listOf("documents:read"),
                createdAt = "2026-06-20T09:00:00Z"
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
    val settingsCapabilities = remember { BridgeSettingsPlatformCapabilities(bridge) }

    // Build the KMP dataSource + coordinator directly (no bridge event round-trip).
    // Use the shared provider network client so request scheduling and transport swaps
    // stay centralized instead of being recreated per page.
    val sharedStores = remember { build.raft.app.data.SlockSharedStoreRegistry.get(pager, bridge) }
    val authStore = sharedStores.authStore
    val networkClient = sharedStores.networkClient
    val agentsApiClient = remember(networkClient) {
        build.raft.app.data.SlockAgentsApiClient(
            transport = build.raft.app.data.SlockAgentsNetworkApi(networkClient),
            diagnosticsLogger = { message -> bridge.log("members_agents_api $message") }
        )
    }
    val computersApiClient = remember(networkClient) {
        build.raft.app.data.SlockComputersApiClient(
            build.raft.app.data.SlockComputersNetworkApi(networkClient)
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
    val membersDataSource = remember(networkClient, authStore, settingsRepository) {
        build.raft.app.data.SharedMembersDataSource(
            memberRepository = build.raft.app.data.RealMemberRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = { message -> bridge.log("members_member_repository $message") }
            ),
            settingsRepository = settingsRepository,
            serverMembersStore = sharedStores.serverMembersStore
        )
    }
    var maxAgents by remember { mutableStateOf(-1) }
    var optimisticMembers by remember { mutableStateOf<List<MemberProfile>>(emptyList()) }
    // Derive the active serverId from the auth session. Replaces the prior
    // `readCurrentServerId()` stub (fixed QA-3): machines load + plan limit
    // checks now use the real selected server.
    var activeServerId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(authStore) {
        activeServerId = authStore.readSession()?.currentServerId
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
        agentPanelTabOrderStore.bindServer(serverId, payload.agentPanelTabOrder)
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
            agentPanelTabOrderStore.applyLoaded(serverId, order)
        }
    }
    val serverMembersEntry by remember(sharedStores.serverMembersStore, serverId) {
        val cleanServerId = serverId.trim()
        if (cleanServerId.isBlank()) {
            flowOf(SharedServerMembersEntry(serverId = ""))
        } else {
            sharedStores.serverMembersStore.serverMembersEntryFlow(ServerId(cleanServerId))
        }
    }.collectAsState(SharedServerMembersEntry(serverId = serverId))
    val localMembers =
        remember(serverMembersEntry.members, optimisticMembers) {
            val merged = LinkedHashMap<String, MemberProfile>()
            serverMembersEntry.members.forEach { member ->
                member.id.value.trim().takeIf { it.isNotBlank() }?.let { merged[it] = member }
            }
            optimisticMembers.forEach { member ->
                member.id.value.trim().takeIf { it.isNotBlank() && it !in merged }?.let { merged[it] = member }
            }
            merged.values.toList()
        }
    val currentAgentCount = localMembers.count { it.kind == MemberKind.Agent }
    LaunchedEffect(serverId) {
        optimisticMembers = emptyList()
    }
    val agentStatusResolver =
        remember(sharedStores.serverMembersStore, serverId) {
            slockAgentStatusResolver(sharedStores.serverMembersStore, serverId)
        }
    val memberAvatarResolver =
        remember(sharedStores.serverMembersStore, serverId) {
            slockMemberAvatarResolver(sharedStores.serverMembersStore, serverId)
        }
    val membersRefreshing = serverMembersEntry.status == build.raft.app.data.SharedServerMembersLoadStatus.InFlight
    val refreshMembers: () -> Unit = remember(sharedStores.serverMembersStore, serverId) {
        {
            val cleanServerId = serverId.trim()
            if (cleanServerId.isNotBlank()) {
                scope.launch {
                    sharedStores.serverMembersStore.refreshMembers(
                        ServerId(cleanServerId),
                        DataRequestPriority.Foreground,
                        force = true
                    )
                }
            }
        }
    }
    LaunchedEffect(sharedStores.serverMembersStore, serverId) {
        val cleanServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        sharedStores.serverMembersStore.refreshMembers(ServerId(cleanServerId), DataRequestPriority.Background)
    }
    val computersSlice by computersStore
        .computerPickerSliceFlow(serverId)
        .collectAsState(SharedComputersPickerSlice())
    val createAgentSubmit: (suspend (SharedCreateAgentDialogSubmit) -> DataResult<Unit>)? =
        remember(createAgentCoordinator) {
            { submit ->
                val result = createAgentCoordinator.createAgent(submit.toCreateAgentRequest())
                if (result is DataResult.Content) {
                    val profile = build.raft.app.model.MemberProfile(
                        id = build.raft.app.model.MemberId(result.data.id),
                        displayName = result.data.displayName?.takeIf { it.isNotBlank() } ?: result.data.name,
                        handle = result.data.name,
                        kind = build.raft.app.model.MemberKind.Agent,
                        description = result.data.description,
                        roleDescription = result.data.model,
                        avatarUrl = result.data.avatarUrl,
                        avatar = build.raft.app.model.AvatarReference.fromWireValue(result.data.avatarUrl),
                        presence = build.raft.app.model.Presence.Offline,
                        agentStatus = build.raft.app.model.AgentStatus.Online
                    )
                    optimisticMembers = optimisticMembers + profile
                    serverId.trim().takeIf { it.isNotBlank() }?.let { cleanServerId ->
                        sharedStores.serverMembersStore.refreshMembers(
                            ServerId(cleanServerId),
                            DataRequestPriority.Background,
                            force = true
                        )
                    }
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
    var createAgentRuntimeModels by remember(serverId) {
        mutableStateOf<Map<Pair<String, String>, List<SharedCreateAgentRuntimeModelOption>>>(emptyMap())
    }
    var createAgentRuntimeModelLoadsInFlight by remember(serverId) {
        mutableStateOf<Set<Pair<String, String>>>(emptySet())
    }
    val loadRuntimeModels: (String, String, Boolean) -> Unit = { machineId, runtime, force ->
        val key = machineId.trim() to runtime.trim()
        if (serverId.isBlank() || key.first.isBlank() || key.second.isBlank()) {
            // no-op: the dialog keeps using the static runtime catalog fallback
        } else if (!force && (key in createAgentRuntimeModels || key in createAgentRuntimeModelLoadsInFlight)) {
            // no-op: cached or already loading
        } else {
            createAgentRuntimeModelLoadsInFlight = createAgentRuntimeModelLoadsInFlight + key
            scope.launch {
                when (val result = createAgentCoordinator.loadRuntimeModels(serverId, key.first, key.second)) {
                    is DataResult.Content -> {
                        createAgentRuntimeModels = createAgentRuntimeModels + (key to result.data)
                    }
                    is DataResult.Empty -> Unit
                    is DataResult.Error ->
                        bridge.log("create_agent_runtime_models_failed source=members_page code=${result.error.code}")
                    is DataResult.NoPermission ->
                        bridge.log("create_agent_runtime_models_blocked source=members_page code=${result.blocker.code}")
                }
                createAgentRuntimeModelLoadsInFlight = createAgentRuntimeModelLoadsInFlight - key
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
    var inviteJoinLinkWebBaseUrl by remember { mutableStateOf<String?>(null) }
    var inviteJoinLinkCopied by remember { mutableStateOf(false) }
    fun loadInviteJoinLink() {
        if (serverId.isBlank() || inviteJoinLinkLoading) return
        inviteJoinLinkLoading = true
        scope.launch {
            val webBaseUrl =
                when (val developer = settingsCapabilities.loadDeveloper()) {
                    is DataResult.Content -> developer.data.webUrl
                    else -> null
                }
            val listed = membersDataSource.loadJoinLinks(serverId)
            val existing = (listed as? DataResult.Content)?.data.orEmpty().firstOrNull()
            val result = if (existing != null) {
                DataResult.Content(existing)
            } else {
                membersDataSource.ensureJoinLink(serverId)
            }
            inviteJoinLinkLoading = false
            when (result) {
                is DataResult.Content -> {
                    inviteJoinLinkWebBaseUrl = webBaseUrl
                    inviteJoinLinkUrl = normalizeInviteHumanJoinUrl(result.data.url, webBaseUrl)
                }
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

    val agentPanelTabOrder =
        if (agentPanelTabOrderState.serverId == serverId) {
            agentPanelTabOrderState.order
        } else {
            payload.agentPanelTabOrder
        }
    val membersPayload = payload.copy(members = localMembers, agentPanelTabOrder = agentPanelTabOrder)

    MembersRouteContent(
        payload = membersPayload,
        refreshing = membersRefreshing,
        onRefresh = refreshMembers,
        onAddHuman = { /* humans "+" wired via addHumanSubmit below */ },
        onOpenMemberDetail = { member ->
            scope.launch {
                val launchPayload =
                    if (member.kind == MemberKind.Agent && (agentPanelTabOrderState.serverId != serverId || !agentPanelTabOrderState.loaded)) {
                        loadAgentPanelTabOrderForMembersPage()
                            ?.also { order -> agentPanelTabOrderStore.applyLoaded(serverId, order) }
                            ?.let { order -> membersPayload.copy(agentPanelTabOrder = order) }
                            ?: membersPayload
                    } else {
                        membersPayload
                    }
                val pageData = launchPayload.toMemberDetailPageData(member.id.value, member.kind)
                if (pageData == null) {
                    bridge.toast("Unable to open profile")
                    bridge.log("members_detail_open_skip reason=missing_member_id instanceId=${payload.instanceId}")
                } else {
                    sharedStores.agentDetailStore.dispatch(SharedAgentDetailIntent.SeedDetailFromMember(member))
                    pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                        .openPage(MEMBER_DETAIL_PAGE, pageData)
                }
            }
        },
        agentStatusResolver = agentStatusResolver,
        memberAvatarResolver = memberAvatarResolver,
        canCreateAgents = payload.canCreateAgents,
        createAgentSubmit = createAgentSubmit,
        createAgentMachines = computersSlice.machines,
        createAgentMachinesLoading = computersSlice.status == SharedComputersLoadStatus.InFlight,
        createAgentCurrentAgentCount = currentAgentCount,
        createAgentMaxAgents = maxAgents,
        createAgentLoadMachines = loadMachines,
        createAgentRuntimeModelsByMachineAndRuntime = createAgentRuntimeModels,
        createAgentRuntimeModelLoadsInFlight = createAgentRuntimeModelLoadsInFlight,
        createAgentLoadRuntimeModels = loadRuntimeModels,
        inviteHumanSubmit = inviteHumanSubmit,
        inviteHumanJoinLinkLoading = inviteJoinLinkLoading,
        inviteHumanJoinLinkUrl = inviteJoinLinkUrl,
        inviteHumanJoinLinkCopied = inviteJoinLinkCopied,
        inviteHumanJoinLinkCopy = { url ->
            bridge.copyToPasteboard(url)
            inviteJoinLinkCopied = true
        },
        inviteHumanLoadJoinLink = { loadInviteJoinLink() },
        // #89: pass agentsApiClient so Permissions tab can call
        // `agentScopes` / `updateAgentScopes` / `resetAgentScopes` directly.
        agentsApiClient = agentsApiClient
    )
}

private sealed interface MemberDetailRouteState {
    data object Loading : MemberDetailRouteState
    data class Error(val code: String) : MemberDetailRouteState
    data class Agent(
        val server: ServerSummary,
        val currentUserId: String?
    ) : MemberDetailRouteState
    data class Human(
        val member: MemberProfile
    ) : MemberDetailRouteState
}

private data class MemberDetailVisualSeed(
    val member: MemberProfile,
    val agentDetailState: MembersAgentDetailLoadState,
    val agentScopeSet: AgentScopeSet? = null
)

private class MemberDetailRouteProvider(
    val bridge: BridgeModule,
    val authStore: SlockAuthSessionStore,
    val memberRepository: MemberRepository,
    val settingsRepository: build.raft.app.data.SettingsServerManagementRepository,
    val settingsCapabilities: BridgeSettingsPlatformCapabilities,
    val agentDetailStore: SharedAgentDetailStore,
    val agentPanelTabOrderStore: SharedAgentPanelTabOrderStore,
    val serverMembersStore: build.raft.app.data.SharedServerMembersStore,
    val agentsApiClient: build.raft.app.data.SlockAgentsApiClient? = null
) {
    suspend fun cachedContext(
        memberId: String,
        expectedKind: MemberKind?
    ): MemberDetailRouteState.Agent? {
        if (expectedKind != null && expectedKind != MemberKind.Agent) return null
        val selectedMemberId = memberId.trim()
        if (selectedMemberId.isBlank()) return null
        val selectedId = MemberId(selectedMemberId)
        val state = agentDetailStore.state.value
        val detail = state.detail
        if (state.agentId != selectedId || detail == null || detail.agentId != selectedId) {
            return null
        }
        val session = authStore.readSession() ?: return null
        val serverId = session.currentServerId
        val server = session.servers.firstOrNull { it.id == serverId }?.toServerSummary() ?: return null
        return MemberDetailRouteState.Agent(
            server = server,
            currentUserId = session.user.id
        )
    }

    suspend fun loadMember(
        memberId: String,
        expectedKind: MemberKind?,
        sourceServerId: String? = null
    ): MemberDetailRouteState {
        val selectedMemberId = memberId.trim()
        if (selectedMemberId.isBlank()) return MemberDetailRouteState.Error("member_id_missing")
        val session = authStore.readSession()
        val serverId = session?.currentServerId
        val server = session?.servers?.firstOrNull { it.id == serverId }?.toServerSummary()
        if (session == null || serverId.isNullOrBlank() || server == null) {
            return MemberDetailRouteState.Error("server_required")
        }
        val selectedId = MemberId(selectedMemberId)
        val profileServerId =
            sourceServerId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(::ServerId)
                ?: ServerId(serverId)
        if (expectedKind == MemberKind.Human) {
            return loadHumanMember(selectedId, profileServerId)
        }
        val state = agentDetailStore.state.value
        val hasMatchingDetail = state.agentId == selectedId && state.detail?.agentId == selectedId
        if (!hasMatchingDetail) {
            agentDetailStore.dispatch(SharedAgentDetailIntent.SelectMember(selectedId))
        }
        agentDetailStore.dispatch(SharedAgentDetailIntent.SetDetailLoading(selectedId))
        when (val detailResult = memberRepository.getAgentDetail(DataRequestPriority.Visible, selectedId)) {
            is DataResult.Content -> {
                agentDetailStore.dispatch(SharedAgentDetailIntent.SelectMember(selectedId))
                agentDetailStore.dispatch(SharedAgentDetailIntent.SetDetail(selectedId, detailResult.data))
                return MemberDetailRouteState.Agent(
                    server = server,
                    currentUserId = session.user.id
                )
            }
            is DataResult.Empty -> {
                return if (expectedKind == null) {
                    loadHumanMember(selectedId, profileServerId)
                } else {
                    agentDetailStore.dispatch(SharedAgentDetailIntent.SetDetailEmpty(selectedId))
                    MemberDetailRouteState.Error("member_not_found")
                }
            }
            is DataResult.Error -> {
                agentDetailStore.dispatch(
                    SharedAgentDetailIntent.SetDetailError(selectedId, detailResult.error.code)
                )
                return MemberDetailRouteState.Error(detailResult.error.code)
            }
            is DataResult.NoPermission -> {
                agentDetailStore.dispatch(
                    SharedAgentDetailIntent.SetDetailNoPermission(selectedId, detailResult.blocker.code)
                )
                return MemberDetailRouteState.Error(detailResult.blocker.code)
            }
        }
    }

    private suspend fun loadHumanMember(selectedId: MemberId, profileServerId: ServerId): MemberDetailRouteState {
        agentDetailStore.dispatch(SharedAgentDetailIntent.DeselectMember)
        return when (val memberResult = memberRepository.getMemberProfile(DataRequestPriority.Visible, selectedId, profileServerId)) {
            is DataResult.Content -> {
                serverMembersStore.seedProfiles(profileServerId, listOf(memberResult.data))
                MemberDetailRouteState.Human(memberResult.data)
            }
            is DataResult.Empty ->
                MemberDetailRouteState.Error("member_not_found")
            is DataResult.Error ->
                MemberDetailRouteState.Error(memberResult.error.code)
            is DataResult.NoPermission ->
                MemberDetailRouteState.Error(memberResult.blocker.code)
        }
    }

    fun updateCachedMemberProfile(member: MemberProfile) {
        val state = agentDetailStore.state.value
        val detail = state.detail ?: return
        if (state.agentId != member.id || detail.agentId != member.id) return
        agentDetailStore.dispatch(
            SharedAgentDetailIntent.SetDetail(
                member.id,
                detail.withMemberProfileProjection(member)
            )
        )
    }

    fun updateCachedLifecycleResult(action: MembersAgentLifecycleAction, agentId: MemberId) {
        val state = agentDetailStore.state.value
        val detail = state.detail ?: return
        if (state.agentId != agentId || detail.agentId != agentId) return
        agentDetailStore.dispatch(
            SharedAgentDetailIntent.SetDetail(
                agentId,
                detail.withMemberDetailLifecycleResult(action),
                preserveSequencedActivity = false
            )
        )
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
                        build.raft.app.data.AgentResetRequest(
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

    suspend fun loadAgentPanelTabOrder(): DataResult<List<String>> =
        when (val result = settingsRepository.getServerSidebarOrder()) {
            is DataResult.Content -> DataResult.Content(result.data.agentPanelTabOrder)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> DataResult.Error(result.error)
            is DataResult.NoPermission -> DataResult.NoPermission(result.blocker)
        }

    suspend fun updateAgentPanelTabOrder(tabs: List<MembersAgentDetailTab>): DataResult<List<String>> =
        when (
            val result = settingsRepository.updateServerSidebarOrder(
                build.raft.app.data.SettingsServerSidebarOrderUpdateRequest(
                    agentPanelTabOrder = tabs.toMembersAgentPanelTabOrderWireKeys()
                )
            )
        ) {
            is DataResult.Content -> DataResult.Content(result.data.agentPanelTabOrder)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> DataResult.Error(result.error)
            is DataResult.NoPermission -> DataResult.NoPermission(result.blocker)
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
    val sharedStores = remember { build.raft.app.data.SlockSharedStoreRegistry.get(pager, bridge) }
    val authStore = sharedStores.authStore
    val networkClient = sharedStores.networkClient
    val memberRepository: MemberRepository =
        remember(networkClient, authStore) {
            RealMemberRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = { message -> bridge.log("member_detail_repository $message") }
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
    val settingsCapabilities = remember { BridgeSettingsPlatformCapabilities(bridge) }
    val agentDetailStore = sharedStores.agentDetailStore
    val agentPanelTabOrderStore = sharedStores.agentPanelTabOrderStore
    val serverMembersStore = sharedStores.serverMembersStore
    val agentsApiClient = remember(networkClient) {
        build.raft.app.data.SlockAgentsApiClient(
            transport = build.raft.app.data.SlockAgentsNetworkApi(networkClient),
            diagnosticsLogger = { message -> bridge.log("member_detail_agents_api $message") }
        )
    }
    return remember(
        bridge,
        authStore,
        memberRepository,
        settingsRepository,
        settingsCapabilities,
        agentDetailStore,
        agentPanelTabOrderStore,
        serverMembersStore,
        agentsApiClient
    ) {
        MemberDetailRouteProvider(
            bridge = bridge,
            authStore = authStore,
            memberRepository = memberRepository,
            settingsRepository = settingsRepository,
            settingsCapabilities = settingsCapabilities,
            agentDetailStore = agentDetailStore,
            agentPanelTabOrderStore = agentPanelTabOrderStore,
            serverMembersStore = serverMembersStore,
            agentsApiClient = agentsApiClient
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
    val visualSeed = remember(payload) { payload.toMemberDetailVisualSeedOrNull() }
    if (visualSeed != null) {
        MemberDetailVisualSeedContent(payload = payload, seed = visualSeed)
        return
    }
    val provider = rememberMemberDetailRouteProvider(pager, bridge)
    val routeScope = rememberCoroutineScope()
    var routeState by remember(payload.memberId) {
        mutableStateOf<MemberDetailRouteState>(MemberDetailRouteState.Loading)
    }
    var localAgentDetailRefreshKey by remember { mutableStateOf(0) }
    val agentDetailStoreState by provider.agentDetailStore.state.collectAsState()
    val agentPanelTabOrderState by provider.agentPanelTabOrderStore.state.collectAsState()
    val selectedMemberId = remember(payload.memberId) {
        payload.memberId.trim().takeIf { it.isNotBlank() }?.let(::MemberId)
    }
    val selectedAgentDetailStoreStateMatches = selectedMemberId != null && agentDetailStoreState.agentId == selectedMemberId
    val selectedAgentDetail = agentDetailStoreState.detail?.takeIf {
        selectedAgentDetailStoreStateMatches && it.agentId == selectedMemberId
    }
    val agentDetailState =
        if (selectedAgentDetailStoreStateMatches) {
            agentDetailStoreState.toMembersAgentDetailLoadState()
        } else {
            MembersAgentDetailLoadState.Loading
        }
    val timeContext = payload.toSlockTimeContext()
    val initialAgentTab = remember(payload.initialAgentTab) { payload.initialAgentTab.toMembersAgentDetailTabOrNull() }
    val expectedKind = remember(payload.memberKind) { payload.memberKind.toMemberDetailExpectedKind() }
    val statusLookupServerId =
        when (val current = routeState) {
            is MemberDetailRouteState.Agent -> current.server.id.value
            else -> null
        }
    LaunchedEffect(provider.agentPanelTabOrderStore, statusLookupServerId, payload.agentPanelTabOrder) {
        provider.agentPanelTabOrderStore.bindServer(statusLookupServerId, payload.agentPanelTabOrder)
    }
    val agentPanelTabOrder =
        if (agentPanelTabOrderState.serverId == statusLookupServerId) {
            agentPanelTabOrderState.order
        } else {
            payload.agentPanelTabOrder
        }
    val agentStatusResolver =
        remember(provider.serverMembersStore, statusLookupServerId) {
            slockAgentStatusResolver(provider.serverMembersStore, statusLookupServerId)
        }
    val memberAvatarResolver =
        remember(provider.serverMembersStore, statusLookupServerId) {
            slockMemberAvatarResolver(provider.serverMembersStore, statusLookupServerId)
        }

    LaunchedEffect(payload.memberId, expectedKind, payload.sourceServerId, localAgentDetailRefreshKey) {
        val cached = provider.cachedContext(payload.memberId, expectedKind)
        routeState = cached ?: MemberDetailRouteState.Loading
        routeState = provider.loadMember(payload.memberId, expectedKind, payload.sourceServerId)
    }
    LaunchedEffect(provider.serverMembersStore, statusLookupServerId) {
        val serverId = statusLookupServerId?.trim()?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        provider.serverMembersStore.refreshMembers(ServerId(serverId), DataRequestPriority.Background)
    }
    LaunchedEffect(
        provider,
        payload.memberId,
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
            is DataResult.Content -> provider.agentPanelTabOrderStore.applyLoaded(statusLookupServerId, result.data)
            is DataResult.Empty -> Unit
            is DataResult.Error ->
                bridge.log("member_detail_agent_tab_order_load_failed code=${result.error.code}")
            is DataResult.NoPermission ->
                bridge.log("member_detail_agent_tab_order_load_blocked code=${result.blocker.code}")
        }
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
            MemberDetailErrorPageShell(
                title = "Could not load member",
                detail = current.code,
                onBack = { provider.bridge.closePage() }
            )
        is MemberDetailRouteState.Agent -> {
            val member = selectedAgentDetail?.toMemberProfile(current.server)
            if (member == null) {
                Box(
                    modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite),
                    contentAlignment = Alignment.Center
                ) {
                    SlockCenteredLoading()
                }
            } else {
                val agentDetailActions =
                    remember(provider) {
                        object : SharedAgentDetailActions {
                            override suspend fun runLifecycleAction(
                                agentId: MemberId,
                                action: MembersAgentLifecycleAction
                            ): DataResult<AgentLifecycleActionResult> {
                                val result = provider.runLifecycleAction(agentId, action)
                                if (result is DataResult.Content && result.data.success) {
                                    provider.updateCachedLifecycleResult(action, agentId)
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
                                    provider.updateCachedMemberProfile(result.data)
                                }
                                return result
                            }
                        }
                    }
                MembersDetailContent(
                    member = member,
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
                    onOpenDirectMessage = { provider.bridge.settingsEvent(payload.instanceId, "openMember", member.id.value) },
                    onCopyMemberId = {
                        provider.copyMemberId(member)
                    },
                    onRemoveMember = {
                        provider.bridge.settingsEvent(payload.instanceId, "removeMember", member.id.value)
                    },
                    onOpenUrl = { provider.bridge.openUrl(it) },
                    onOpenReminderMessage = { permalink ->
                        pager.openMembersReminderMessage(
                            permalink = permalink,
                            timeContext = timeContext,
                            onOpenExternal = { provider.bridge.openUrl(it) },
                            onUnavailableAction = { provider.bridge.toast(it) }
                        )
                    },
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
                            provider.updateCachedMemberProfile(result.data)
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
                    initialAgentTab = initialAgentTab,
                    agentPanelTabOrder = agentPanelTabOrder,
                    onAgentPanelTabOrderChange = { orderedTabs ->
                        val previousOrder = agentPanelTabOrder
                        val nextOrder = orderedTabs.toMembersAgentPanelTabOrderWireKeys()
                        if (nextOrder != previousOrder) {
                            provider.agentPanelTabOrderStore.applyOptimistic(statusLookupServerId, nextOrder)
                            routeScope.launch {
                                when (val result = provider.updateAgentPanelTabOrder(orderedTabs)) {
                                    is DataResult.Content ->
                                        provider.agentPanelTabOrderStore.applyLoaded(statusLookupServerId, result.data)
                                    is DataResult.Empty -> {
                                        provider.agentPanelTabOrderStore.applyLoaded(statusLookupServerId, previousOrder)
                                        bridge.toast("Unable to save tab order")
                                    }
                                    is DataResult.Error -> {
                                        provider.agentPanelTabOrderStore.applyLoaded(statusLookupServerId, previousOrder)
                                        bridge.toast("Failed to save tab order: ${result.error.code}")
                                    }
                                    is DataResult.NoPermission -> {
                                        provider.agentPanelTabOrderStore.applyLoaded(statusLookupServerId, previousOrder)
                                        bridge.toast("You do not have permission to update tab order.")
                                    }
                                }
                            }
                        }
                    },
                    agentStatusResolver = agentStatusResolver,
                    memberAvatarResolver = memberAvatarResolver,
                    agentsApiClient = provider.agentsApiClient
                )
            }
        }
        is MemberDetailRouteState.Human -> {
            MembersDetailContent(
                member = current.member,
                agentDetailState = MembersAgentDetailLoadState.Idle,
                canViewPrivateAgentSurfaces = { _, _ -> false },
                canManageAgent = { _, _ -> false },
                onBack = { provider.bridge.closePage() },
                onOpenDirectMessage = {
                    provider.bridge.settingsEvent(payload.instanceId, "openMember", current.member.id.value)
                },
                onCopyMemberId = {
                    provider.copyMemberId(current.member)
                },
                onRemoveMember = {
                    provider.bridge.settingsEvent(payload.instanceId, "removeMember", current.member.id.value)
                },
                onOpenUrl = { provider.bridge.openUrl(it) },
                onOpenReminderMessage = { permalink ->
                    pager.openMembersReminderMessage(
                        permalink = permalink,
                        timeContext = timeContext,
                        onOpenExternal = { provider.bridge.openUrl(it) },
                        onUnavailableAction = { provider.bridge.toast(it) }
                    )
                },
                onUpdateAgentProfile = null,
                onUploadAgentAvatar = null,
                onAgentLifecycleAction = null,
                agentWorkspaceTreeLoader = null,
                agentWorkspaceChildrenLoader = null,
                onCopyAgentDiagnostics = {},
                onReportAgentIssue = null,
                onUnavailableAction = { bridge.toast(it) },
                timeContext = timeContext,
                initialAgentTab = initialAgentTab,
                agentPanelTabOrder = payload.agentPanelTabOrder,
                agentStatusResolver = agentStatusResolver,
                memberAvatarResolver = memberAvatarResolver
            )
        }
    }
}

private fun KuiklyMemberDetailPayload.toMemberDetailVisualSeedOrNull(): MemberDetailVisualSeed? {
    if (!instanceId.startsWith("visual-")) return null
    return when (memberId) {
        membersAgentDetailVisualMember.id.value ->
            MemberDetailVisualSeed(
                member = membersAgentDetailVisualMember,
                agentDetailState = MembersAgentDetailLoadState.Content(membersAgentDetailVisualSnapshot),
                agentScopeSet = membersAgentDetailVisualScopeSet
            )
        membersHumanDetailVisualMember.id.value ->
            MemberDetailVisualSeed(
                member = membersHumanDetailVisualMember,
                agentDetailState = MembersAgentDetailLoadState.Idle
            )
        else -> null
    }
}

@Composable
private fun MemberDetailVisualSeedContent(
    payload: KuiklyMemberDetailPayload,
    seed: MemberDetailVisualSeed
) {
    val initialAgentTab = remember(payload.initialAgentTab) {
        payload.initialAgentTab.toMembersAgentDetailTabOrNull()
    }
    MembersDetailContent(
        member = seed.member,
        agentDetailState = seed.agentDetailState,
        canViewPrivateAgentSurfaces = { member, _ -> member.kind == MemberKind.Agent },
        canManageAgent = { member, _ -> member.kind == MemberKind.Agent },
        onBack = {},
        onOpenDirectMessage = {},
        onCopyMemberId = {},
        onRemoveMember = {},
        onOpenUrl = {},
        onUpdateAgentProfile = { DataResult.Content(seed.member) },
        onUploadAgentAvatar = null,
        onAgentLifecycleAction = { agentId, action ->
            DataResult.Content(
                AgentLifecycleActionResult(
                    agentId = agentId,
                    action = action.actionKey,
                    success = true
                )
            )
        },
        agentWorkspaceTreeLoader = { DataResult.Content(membersAgentDetailVisualWorkspaceTreeSnapshot) },
        agentWorkspaceChildrenLoader = { _, _ ->
            DataResult.Content(membersAgentDetailVisualWorkspaceNotesChildren)
        },
        onCopyAgentDiagnostics = {},
        onReportAgentIssue = { true },
        onUnavailableAction = {},
        timeContext = payload.toSlockTimeContext(),
        initialAgentTab = initialAgentTab,
        agentPanelTabOrder = payload.agentPanelTabOrder,
        initialAgentScopeSet = seed.agentScopeSet
    )
}

@Composable
private fun MemberDetailErrorPageShell(
    title: String,
    detail: String,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        SlockTopPageHeader(
            title = "Member",
            subtitle = "Profile",
            iconName = SlockLucideIconName.Users,
            backgroundColor = SlockColor.SurfaceWhite,
            leadingContent = {
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.ArrowLeft,
                    onClick = onBack,
                    size = 30.dp,
                    shadowOffset = 2.dp
                )
            }
        )
        SlockEmptyState(
            title = title,
            detail = detail,
            iconName = SlockLucideIconName.TriangleAlert,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
internal fun MembersAgentDetailVisualContent() {
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

private fun Pager.openMembersReminderMessage(
    permalink: String,
    timeContext: SlockTimeContext,
    onOpenExternal: (String) -> Unit,
    onUnavailableAction: (String) -> Unit
) {
    val target = permalink.toSlockThreadWebLinkTarget()
    if (target == null) {
        onOpenExternal(permalink)
        return
    }
    val messageId = target.messageId?.takeIf { it.isNotBlank() }
    if (messageId == null) {
        onUnavailableAction("Unable to open reminder message")
        return
    }
    val launchPayload =
        target.toMembersReminderThreadLaunchPayload(
            messageId = messageId,
            timeContext = timeContext
        )
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(THREAD_PAGE, launchPayload.toThreadPageData())
}

private fun SlockThreadWebLinkTarget.toMembersReminderThreadLaunchPayload(
    messageId: String,
    timeContext: SlockTimeContext
): WireThreadLaunchPayload {
    val isDm = routeKind == KuiklyThreadSurfaceKind.DirectMessage
    val parentMessageId = threadParentMessageId?.takeIf { it.isNotBlank() }
    val instanceId = "members-reminder-${channelId}-${messageId}"
    if (parentMessageId != null) {
        return WireThreadLaunchPayload(
            instanceId = instanceId,
            surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
            channelKind = if (isDm) KuiklyThreadSurfaceKind.DirectMessage else KuiklyThreadSurfaceKind.Channel,
            parentChannelId = channelId,
            parentMessageId = parentMessageId,
            parentSurfaceKind = if (isDm) KuiklyThreadSurfaceKind.DirectMessage else KuiklyThreadSurfaceKind.Channel,
            parentChannelKind = if (isDm) KuiklyThreadSurfaceKind.DirectMessage else KuiklyThreadSurfaceKind.Channel,
            originParentMessageId = parentMessageId,
            focusedMessageId = messageId,
            localOffsetMinutes = timeContext.localOffsetMinutes,
            todayEpochDay = timeContext.todayEpochDay,
            nowEpochMillis = timeContext.nowEpochMillis,
            timeFormat = timeContext.timeFormat
        )
    }
    val surfaceKind =
        if (isDm) {
            KuiklyThreadSurfaceKind.DirectMessage
        } else {
            KuiklyThreadSurfaceKind.Channel
        }
    return WireThreadLaunchPayload(
        instanceId = instanceId,
        surfaceKind = surfaceKind,
        channelKind = if (isDm) KuiklyThreadSurfaceKind.DirectMessage else KuiklyThreadSurfaceKind.Channel,
        threadId = channelId.takeIf { surfaceKind == KuiklyThreadSurfaceKind.DirectMessage },
        sourceThreadId = channelId,
        streamChannelId = channelId,
        focusedMessageId = messageId,
        localOffsetMinutes = timeContext.localOffsetMinutes,
        todayEpochDay = timeContext.todayEpochDay,
        nowEpochMillis = timeContext.nowEpochMillis,
        timeFormat = timeContext.timeFormat
    )
}

internal fun String.toKuiklyMembersPayload(): KuiklyMembersPayload {
    if (isBlank()) return KuiklyMembersPayload()
    val payload = JSONObject(this)
    val labelsJson = payload.optJSONObject("labels")
    return KuiklyMembersPayload(
        instanceId = payload.optString("instanceId"),
        title = payload.optString("title").ifBlank { "Members" },
        localOffsetMinutes = payload.optString("localOffsetMinutes").toIntOrNull() ?: 0,
        todayEpochDay = payload.optString("todayEpochDay").toLongOrNull(),
        nowEpochMillis = payload.optString("nowEpochMillis").toLongOrNull(),
        timeFormat = payload.optString("timeFormat").ifBlank { SlockTimeFormat24Hour },
        initialAgentTab = payload.optNullableString("initialAgentTab"),
        agentPanelTabOrder = payload.optStringArray("agentPanelTabOrder"),
        canCreateAgents = (payload.opt("canCreateAgents") as? Boolean) ?: true,
        labels = KuiklyMembersLabels(
            agents = labelsJson?.optString("agents").orEmpty().ifBlank { "AGENTS" },
            humans = labelsJson?.optString("humans").orEmpty().ifBlank { "HUMANS" },
            addHuman = labelsJson?.optString("addHuman").orEmpty().ifBlank { "Add human" }
        )
    )
}

internal fun KuiklyMembersPayload.toMemberDetailPageData(
    memberId: String,
    selectedMemberKind: MemberKind? = null
): JSONObject? {
    val selected = memberId.trim().takeIf { it.isNotBlank() } ?: return null
    val selectedMember = members.firstOrNull { it.id.value == selected }
    return JSONObject().apply {
        put("pageName", MEMBER_DETAIL_PAGE)
        put("instanceId", "members-detail-${instanceId.ifBlank { "members" }}-$selected")
        put("memberId", selected)
        (selectedMemberKind ?: selectedMember?.kind)?.let {
            put("memberKind", it.toMemberDetailKindWireValue())
        }
        selectedMember?.serverId?.value?.trim()?.takeIf { it.isNotBlank() }?.let {
            put("sourceServerId", it)
        }
        initialAgentTab?.let { put("initialAgentTab", it) }
        put("localOffsetMinutes", localOffsetMinutes)
        todayEpochDay?.let { put("todayEpochDay", it) }
        nowEpochMillis?.let { put("nowEpochMillis", it) }
        put("timeFormat", timeFormat)
        if (agentPanelTabOrder.isNotEmpty()) {
            put("agentPanelTabOrder", JSONArray().apply {
                agentPanelTabOrder.forEach(::put)
            })
        }
    }
}

internal fun JSONObject.toKuiklyMemberDetailPayload(): KuiklyMemberDetailPayload {
    val payloadText = optString("payload")
    val payload = if (payloadText.isNotBlank()) JSONObject(payloadText) else this
    return KuiklyMemberDetailPayload(
        instanceId = payload.optString("instanceId").ifBlank { optString("instanceId") },
        memberId = payload.optString("memberId").ifBlank { payload.optString("selectedMemberId") },
        memberKind = payload.optString("memberKind")
            .ifBlank { payload.optString("selectedMemberKind") }
            .ifBlank { payload.optString("kind") },
        sourceServerId = payload.optString("sourceServerId").ifBlank { payload.optString("serverId") },
        initialAgentTab = payload.optNullableString("initialAgentTab"),
        localOffsetMinutes = payload.optString("localOffsetMinutes").toIntOrNull() ?: 0,
        todayEpochDay = payload.optString("todayEpochDay").toLongOrNull(),
        nowEpochMillis = payload.optString("nowEpochMillis").toLongOrNull(),
        timeFormat = payload.optString("timeFormat").ifBlank { SlockTimeFormat24Hour },
        agentPanelTabOrder = payload.optStringArray("agentPanelTabOrder")
    )
}

private fun MemberKind.toMemberDetailKindWireValue(): String =
    when (this) {
        MemberKind.Agent -> "agent"
        MemberKind.Human -> "human"
        MemberKind.System -> "system"
    }

private fun String.toMemberDetailExpectedKind(): MemberKind? =
    when (trim().lowercase()) {
        "agent" -> MemberKind.Agent
        "human", "user" -> MemberKind.Human
        else -> null
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
        email = optNullableString("email"),
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

private fun JSONObject.optStringArray(key: String): List<String> =
    buildList {
        val array = optJSONArray(key) ?: return@buildList
        for (index in 0 until array.length()) {
            array.optString(index)?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }

@Composable
internal fun MembersRouteContent(
    payload: KuiklyMembersPayload,
    refreshing: Boolean = false,
    onAddHuman: () -> Unit,
    onOpenMemberDetail: (MemberProfile) -> Unit,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    /**
     * Optional create-agent flow. When non-null, the agents section "+" opens a
     * SharedCreateAgentDialog and submit is forwarded to this host callback.
     * The host is responsible for the actual API call and refreshing the members
     * payload.
     */
    createAgentSubmit: (suspend (SharedCreateAgentDialogSubmit) -> DataResult<Unit>)? = null,
    createAgentMachines: List<SharedCreateAgentMachineOption> = emptyList(),
    createAgentMachinesLoading: Boolean = false,
    createAgentCurrentAgentCount: Int = 0,
    createAgentMaxAgents: Int = -1,
    createAgentLoadMachines: () -> Unit = {},
    createAgentRuntimeModelsByMachineAndRuntime: Map<Pair<String, String>, List<SharedCreateAgentRuntimeModelOption>> = emptyMap(),
    createAgentRuntimeModelLoadsInFlight: Set<Pair<String, String>> = emptySet(),
    createAgentLoadRuntimeModels: (machineId: String, runtime: String, force: Boolean) -> Unit = { _, _, _ -> },
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
    inviteHumanJoinLinkCopy: (String) -> Unit = {},
    inviteHumanLoadJoinLink: () -> Unit = {},
    agentsApiClient: build.raft.app.data.SlockAgentsApiClient? = null,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    initialListState: AppShellMembersListState = AppShellMembersListState(),
    onListStateSnapshot: (AppShellMembersListState) -> Unit = {},
    onRefresh: () -> Unit = {}
) {
    MembersListContent(
        payload = payload,
        refreshing = refreshing,
        onRefresh = onRefresh,
        onAddHuman = onAddHuman,
        onOpenMember = onOpenMemberDetail,
        agentStatusResolver = agentStatusResolver,
        memberAvatarResolver = memberAvatarResolver,
        canCreateAgents = canCreateAgents,
        createAgentSubmit = createAgentSubmit,
        createAgentMachines = createAgentMachines,
        createAgentMachinesLoading = createAgentMachinesLoading,
        createAgentCurrentAgentCount = createAgentCurrentAgentCount,
        createAgentMaxAgents = createAgentMaxAgents,
        createAgentLoadMachines = createAgentLoadMachines,
        createAgentRuntimeModelsByMachineAndRuntime = createAgentRuntimeModelsByMachineAndRuntime,
        createAgentRuntimeModelLoadsInFlight = createAgentRuntimeModelLoadsInFlight,
        createAgentLoadRuntimeModels = createAgentLoadRuntimeModels,
        inviteHumanSubmit = inviteHumanSubmit,
        inviteHumanJoinLinkLoading = inviteHumanJoinLinkLoading,
        inviteHumanJoinLinkUrl = inviteHumanJoinLinkUrl,
        inviteHumanJoinLinkCopied = inviteHumanJoinLinkCopied,
        inviteHumanJoinLinkCopy = inviteHumanJoinLinkCopy,
        inviteHumanLoadJoinLink = { inviteHumanLoadJoinLink() },
        bottomContentPadding = bottomContentPadding,
        initialListState = initialListState,
        onListStateSnapshot = onListStateSnapshot
    )
}

@Composable
internal fun MembersListContent(
    payload: KuiklyMembersPayload,
    refreshing: Boolean = false,
    onAddHuman: () -> Unit,
    onOpenMember: (MemberProfile) -> Unit,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    canCreateAgents: Boolean = false,
    createAgentSubmit: (suspend (SharedCreateAgentDialogSubmit) -> DataResult<Unit>)? = null,
    createAgentMachines: List<SharedCreateAgentMachineOption> = emptyList(),
    createAgentMachinesLoading: Boolean = false,
    createAgentCurrentAgentCount: Int = 0,
    createAgentMaxAgents: Int = -1,
    createAgentLoadMachines: () -> Unit = {},
    createAgentRuntimeModelsByMachineAndRuntime: Map<Pair<String, String>, List<SharedCreateAgentRuntimeModelOption>> = emptyMap(),
    createAgentRuntimeModelLoadsInFlight: Set<Pair<String, String>> = emptySet(),
    createAgentLoadRuntimeModels: (machineId: String, runtime: String, force: Boolean) -> Unit = { _, _, _ -> },
    inviteHumanSubmit: (suspend (emails: List<String>) -> DataResult<Unit>)? = null,
    inviteHumanJoinLinkLoading: Boolean = false,
    inviteHumanJoinLinkUrl: String? = null,
    inviteHumanJoinLinkCopied: Boolean = false,
    inviteHumanJoinLinkCopy: (String) -> Unit = {},
    inviteHumanLoadJoinLink: () -> Unit = {},
    agentsApiClient: build.raft.app.data.SlockAgentsApiClient? = null,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    initialListState: AppShellMembersListState = AppShellMembersListState(),
    onListStateSnapshot: (AppShellMembersListState) -> Unit = {},
    onRefresh: () -> Unit = {}
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
    var agentsExpanded by remember(payload.instanceId) { mutableStateOf(initialListState.agentsExpanded) }
    var humansExpanded by remember(payload.instanceId) { mutableStateOf(initialListState.humansExpanded) }
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

    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex = initialListState.listPosition.firstVisibleItemIndex,
            initialFirstVisibleItemScrollOffset = initialListState.listPosition.firstVisibleItemScrollOffset
        )
    val latestAgentsExpanded by rememberUpdatedState(agentsExpanded)
    val latestHumansExpanded by rememberUpdatedState(humansExpanded)
    val latestOnListStateSnapshot by rememberUpdatedState(onListStateSnapshot)
    DisposableEffect(listState) {
        onDispose {
            latestOnListStateSnapshot(
                AppShellMembersListState(
                    listPosition = listState.toAppShellLazyListPosition(),
                    agentsExpanded = latestAgentsExpanded,
                    humansExpanded = latestHumansExpanded
                )
            )
        }
    }
    var pullRefreshActive by remember(payload.instanceId) { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (!refreshing) {
            pullRefreshActive = false
        }
    }
    val pullRefreshing = refreshing && pullRefreshActive
    val pullToRefreshState = rememberPullToRefreshState(pullRefreshing)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        MembersPageHeader(title = payload.title, refreshing = refreshing)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
            beyondBoundsItemCount = sharedListBeyondBoundsItemCount(lazyItemCount)
        ) {
            pullToRefreshItem(
                state = pullToRefreshState,
                scrollState = listState,
                onRefresh = {
                    pullRefreshActive = true
                    onRefresh()
                },
                content = { pullProgress, isRefreshing, _ ->
                    SlockRaftPullRefreshIndicator(
                        pullProgress = pullProgress,
                        isRefreshing = isRefreshing
                    )
                }
            )
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
                            agentStatusResolver = agentStatusResolver,
                            memberAvatarResolver = memberAvatarResolver,
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
                        agentStatusResolver = agentStatusResolver,
                        memberAvatarResolver = memberAvatarResolver,
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
            runtimeModelsByMachineAndRuntime = createAgentRuntimeModelsByMachineAndRuntime,
            runtimeModelLoadsInFlight = createAgentRuntimeModelLoadsInFlight,
            onRuntimeModelsRequest = createAgentLoadRuntimeModels,
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
        1 + // pull refresh indicator
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
    onRemoveMember: (() -> Unit)? = null,
    onOpenUrl: (String) -> Unit,
    onOpenReminderMessage: (String) -> Unit = {},
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
    initialAgentTab: MembersAgentDetailTab? = null,
    agentPanelTabOrder: List<String> = emptyList(),
    onAgentPanelTabOrderChange: ((List<MembersAgentDetailTab>) -> Unit)? = null,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    // #89: forwarded to the Permissions tab so it can call
    // `agentScopes` / `updateAgentScopes` / `resetAgentScopes`. When null
    // the Permissions tab renders an "Agent API not available" error banner.
    agentsApiClient: build.raft.app.data.SlockAgentsApiClient? = null,
    initialAgentScopeSet: AgentScopeSet? = null
) {
    val detail = (agentDetailState as? MembersAgentDetailLoadState.Content)?.detail
    val deleted = detail?.summary?.deletedAt?.isNotBlank() == true
    val agentStatusIndicatorState =
        if (member.kind == MemberKind.Agent) {
            rememberSlockAgentStatusIndicatorState(agentStatusResolver, member.id.value)
                ?: member.toSlockAgentStatusIndicatorState(
                    summaryStatus = detail?.summary?.status,
                    summaryActivity = detail?.summary?.activity
                )
        } else {
            null
        }
    val canViewPrivate = member.kind == MemberKind.Agent && canViewPrivateAgentSurfaces(member, detail)
    val canManage = member.kind == MemberKind.Agent && !deleted && canManageAgent(member, detail)
    val scope = rememberCoroutineScope()
    val pager = LocalActivity.current.getPager() as Pager
    val fallbackAgentDetailStore = remember { SharedAgentDetailStore() }
    val storeState by (sharedAgentDetailStore ?: fallbackAgentDetailStore).state.collectAsState()
    var actionState by remember(member.id.value, initialVisualMode) {
        mutableStateOf<MembersAgentActionState?>(null)
    }
    var diagnosticActionState by remember(member.id.value) { mutableStateOf<MembersAgentDiagnosticActionState?>(null) }
    var pendingConfirmation by remember(member.id.value) {
        mutableStateOf<MembersAgentLifecycleAction?>(null)
    }
    var restartResetDialogOpen by remember(member.id.value, initialVisualMode) {
        mutableStateOf(false)
    }
    var restartResetSelection by remember(member.id.value) {
        mutableStateOf(MembersAgentLifecycleAction.Restart)
    }
    var selectedTab by remember(member.id.value, initialAgentTab) {
        mutableStateOf(initialAgentTab ?: MembersAgentDetailTab.Profile)
    }
    var managementMenuOpen by remember(member.id.value, initialVisualMode) {
        mutableStateOf(initialVisualMode == MembersAgentDetailVisualMode.LifecycleActions)
    }
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
    val visibleTabs = remember(canViewPrivate, deleted, member.kind, agentPanelTabOrder) {
        if (member.kind == MemberKind.Agent) {
            membersOrderedAgentTabs(
                visibleTabs = membersVisibleAgentTabs(canViewPrivateSurfaces = canViewPrivate, deleted = deleted),
                storedOrder = agentPanelTabOrder
            )
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
            if (errorCode == null && action.isRestartResetDialogAction()) {
                restartResetDialogOpen = false
                restartResetSelection = MembersAgentLifecycleAction.Restart
            }
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
    fun openRestartResetDialog() {
        restartResetSelection = MembersAgentLifecycleAction.Restart
        actionState = null
        restartResetDialogOpen = true
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
        Column(modifier = Modifier.fillMaxSize()) {
            val runtimeErrorText = member.membersAgentRuntimeErrorText(detail, agentStatusIndicatorState)
            MembersDetailHeader(
                member = member,
                detail = detail,
                deleted = deleted,
                canManage = canManage,
                onBack = onBack,
                onAvatarClick = if (canManage && !deleted) ({ showAvatarPicker = true }) else null,
                onOpenDirectMessage = onOpenDirectMessage,
                onCopyMemberId = onCopyMemberId,
                agentStatusIndicatorState = agentStatusIndicatorState,
                memberAvatarResolver = memberAvatarResolver,
                onRequestManagementMenu = { anchor ->
                    managementMenuAnchor = anchor
                    managementMenuOpen = true
                }
            )
            if (runtimeErrorText != null) {
                MembersAgentRuntimeErrorBanner(
                    errorText = runtimeErrorText,
                    canViewPrivate = canViewPrivate,
                    onViewLogs = { selectedTab = MembersAgentDetailTab.Activity },
                    onCopyInfo = { copyDiagnostics() },
                    modifier = Modifier.padding(
                        start = MembersPageInset,
                        top = SlockSpacing.Sm,
                        end = MembersPageInset
                    )
                )
            }
            if (member.kind == MemberKind.Agent) {
                MembersAgentTabBar(
                    tabs = visibleTabs,
                    selectedTab = selectedTab,
                    onSelectedTabChange = { selectedTab = it },
                    onReorderTabs = onAgentPanelTabOrderChange
                )
            }
            if (member.kind == MemberKind.Agent && selectedTab == MembersAgentDetailTab.Activity) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    Spacer(Modifier.height(SlockSpacing.Sm))
                    MembersAgentDetailStateNotice(
                        state = agentDetailState,
                        canViewPrivate = canViewPrivate,
                        modifier = Modifier.padding(horizontal = MembersPageInset)
                    )
                    MembersAgentActivityTab(
                        detail = detail,
                        diagnosticActionState = effectiveDiagnosticActionState,
                        onCopyDiagnostics = { copyDiagnostics() },
                        timeContext = timeContext,
                        onOpenUrl = onOpenUrl,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md),
                    beyondBoundsItemCount = sharedListBeyondBoundsItemCount(12)
                ) {
                    if (initialVisualMode != MembersAgentDetailVisualMode.AvatarManagement) {
                        item { Spacer(Modifier.height(SlockSpacing.Sm)) }
                    }
                    if (member.kind == MemberKind.Agent) {
                        if (initialVisualMode != MembersAgentDetailVisualMode.AvatarManagement) {
                            item {
                                MembersAgentDetailStateNotice(
                                    state = agentDetailState,
                                    canViewPrivate = canViewPrivate,
                                    modifier = Modifier.padding(horizontal = MembersPageInset)
                                )
                            }
                        }
                        item {
                            when (selectedTab) {
                                MembersAgentDetailTab.Profile ->
                                    MembersAgentProfileTab(
                                        member = member,
                                        detail = detail,
                                        canManage = canManage,
                                        agentStatusIndicatorState = agentStatusIndicatorState,
                                        agentStatusResolver = agentStatusResolver,
                                        memberAvatarResolver = memberAvatarResolver,
                                        showAvatarPicker = showAvatarPicker && canManage && !deleted,
                                        onToggleAvatarPicker = {
                                            showAvatarPicker = !showAvatarPicker
                                        },
                                        onSelectAvatar = { key ->
                                            saveAvatar("pixel:$key")
                                        },
                                        onClearAvatar = {
                                            saveAvatar(null)
                                        },
                                        onUploadAvatar = {
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
                                        sharedAgentDetailStore = sharedAgentDetailStore,
                                        storeState = storeState.takeIf { sharedAgentDetailStore != null },
                                        agentDetailActions = agentDetailActions,
                                        onUpdateAgentProfile = onUpdateAgentProfile,
                                        onUnavailableAction = onUnavailableAction,
                                        lifecycleInFlight = lifecycleInFlight,
                                        diagnosticActionState = effectiveDiagnosticActionState,
                                        onRunLifecycleAction = { runLifecycleAction(it) },
                                        onOpenRestartReset = { openRestartResetDialog() },
                                        onCopyDiagnostics = { copyDiagnostics() },
                                        onReportIssue = { reportIssue() },
                                        timeContext = timeContext,
                                        modifier = Modifier.padding(horizontal = MembersPageInset)
                                    )
                                MembersAgentDetailTab.Permissions ->
                                    MembersAgentPermissionsTab(
                                        member = member,
                                        detail = detail,
                                        canManage = canManage,
                                        agentsApiClient = agentsApiClient,
                                        initialScopeSet = initialAgentScopeSet,
                                        onUnavailableAction = onUnavailableAction,
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
                                        onOpenMessageRef = onOpenReminderMessage,
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
                                        timeContext = timeContext,
                                        onOpenUrl = onOpenUrl,
                                        modifier = Modifier.padding(horizontal = MembersPageInset)
                                    )
                                MembersAgentDetailTab.Activity -> Unit
                            }
                        }
                    } else {
                        item {
                            MembersHumanDetailSections(
                                member = member,
                                timeContext = timeContext,
                                memberAvatarResolver = memberAvatarResolver,
                                onRemoveMember = onRemoveMember?.takeIf {
                                    member.membershipStatus != "removed"
                                },
                                modifier = Modifier.padding(horizontal = MembersPageInset)
                            )
                        }
                    }
                    item { Spacer(Modifier.height(180.dp)) }
                }
            }
        }
        val dropdownAnchor: Offset = managementMenuAnchor
            ?: Offset(100f, 100f)
        if (managementMenuOpen && member.kind == MemberKind.Agent) {
            val menuSummary = detail?.summary
            val menuStatus = member.membersAgentStatusDisplayState(menuSummary?.status, menuSummary?.activity).status
            val menuPrimary = membersPrimaryLifecycleAction(menuStatus, deleted)
            val menuItems = buildMembersManagementMenuItems(
                primaryAction = menuPrimary,
                canManage = canManage,
                deleted = deleted,
                inProgress = lifecycleInFlight != null
            )
            SlockBrutalDropdownMenu(
                anchor = dropdownAnchor,
                items = menuItems,
                onSelect = { id ->
                    handleMembersManagementMenuSelection(
                        id = id,
                        primaryAction = menuPrimary,
                        openRestartResetDialog = { openRestartResetDialog() },
                        runLifecycleAction = { runLifecycleAction(it) }
                    )
                },
                onDismiss = {
                    managementMenuOpen = false
                }
            )
        }
        if (restartResetDialogOpen && member.kind == MemberKind.Agent) {
            val selectedActionResult =
                if (sharedAgentDetailStore != null) {
                    storeState.lastActionResult
                        ?.takeIf { it.actionKey == restartResetSelection.actionKey }
                        ?.let {
                            MembersAgentActionState(
                                action = restartResetSelection,
                                inProgress = false,
                                errorCode = it.errorCode,
                                successLabel = it.successLabel
                            )
                        }
                } else {
                    actionState?.takeIf { it.action == restartResetSelection }
                }
            MembersRestartResetDialog(
                agentName = member.displayName,
                selectedAction = restartResetSelection,
                busy = lifecycleInFlight != null,
                errorCode = selectedActionResult?.errorCode,
                onSelectedActionChange = { restartResetSelection = it },
                onConfirm = { runLifecycleAction(restartResetSelection, confirmed = true) },
                onDismiss = {
                    if (lifecycleInFlight == null) {
                        restartResetDialogOpen = false
                        restartResetSelection = MembersAgentLifecycleAction.Restart
                        actionState = null
                    }
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

internal fun buildMembersManagementMenuItems(
    primaryAction: MembersAgentLifecycleAction?,
    canManage: Boolean,
    deleted: Boolean,
    inProgress: Boolean
): List<SlockBrutalDropdownMenuItem> {
    if (deleted || !canManage) return emptyList()
    val items = mutableListOf<SlockBrutalDropdownMenuItem>()
    if (primaryAction != null) {
        items += SlockBrutalDropdownMenuItem(
            id = primaryAction.actionKey,
            label = when (primaryAction) {
                MembersAgentLifecycleAction.Start -> "Start Agent"
                MembersAgentLifecycleAction.Stop -> "Stop Agent"
                else -> primaryAction.label
            },
            icon = primaryAction.iconName,
            enabled = !inProgress
        )
    }
    items += SlockBrutalDropdownMenuItem(
        id = MembersAgentLifecycleAction.Restart.actionKey,
        label = "Restart / Reset",
        icon = SlockLucideIconName.RotateCcw,
        enabled = !inProgress
    )
    return items
}

private fun handleMembersManagementMenuSelection(
    id: String,
    primaryAction: MembersAgentLifecycleAction?,
    openRestartResetDialog: () -> Unit,
    runLifecycleAction: (MembersAgentLifecycleAction) -> Unit
) {
    if (id == MembersAgentLifecycleAction.Restart.actionKey) {
        openRestartResetDialog()
        return
    }
    val action = MembersAgentLifecycleAction.values().firstOrNull { it.actionKey == id }
        ?: primaryAction?.takeIf { it.actionKey == id }
    if (action != null) {
        runLifecycleAction(action)
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
    agentStatusIndicatorState: SlockAgentStatusIndicatorState?,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    onBack: () -> Unit,
    onAvatarClick: (() -> Unit)?,
    onOpenDirectMessage: () -> Unit,
    onCopyMemberId: () -> Unit,
    onRequestManagementMenu: (Offset) -> Unit
) {
    val summary = detail?.summary
    val threeDotButtonAnchor = remember { SlockMutableOffsetRef() }
    val agentStatusDisplayState = member.membersAgentStatusDisplayState(summary?.status, summary?.activity)
    val headerMetaLabel = if (deleted) "Deleted" else member.detailHandle
    val avatarClick = onAvatarClick
    val topSafeArea = slockTopSafeAreaPadding()
    val messageButtonWidth = if (member.kind == MemberKind.Human) 104.dp else MembersHeaderMessageButtonWidth
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(topSafeArea + SlockSize.PageHeaderHeight)
            .background(SlockColor.SurfaceWhite)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(topSafeArea + SlockSize.PageHeaderContentHeight)
                .padding(start = 12.dp, top = topSafeArea + 8.dp, end = 24.dp, bottom = 8.dp),
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
                statusDisplayState = agentStatusDisplayState,
                statusIndicatorState = agentStatusIndicatorState,
                memberAvatarResolver = memberAvatarResolver,
                showStatusOverlay = false,
                modifier = if (avatarClick != null) {
                    Modifier.clickable(onClick = avatarClick)
                } else {
                    Modifier
                }
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = member.displayName,
                    style = SlockType.Body.copy(fontSize = 16.sp, lineHeight = 20.sp)
                        .withSlockFontWeight(FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = headerMetaLabel,
                        style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                        color = if (deleted) SlockColor.AccentOrange else SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
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
                modifier = Modifier.width(messageButtonWidth),
                onClick = onOpenDirectMessage
            )
            if (member.kind == MemberKind.Agent) {
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.Menu,
                    onClick = {
                        onRequestManagementMenu(threeDotButtonAnchor.value)
                    },
                    size = MembersActionSize,
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        val pos = coordinates.positionInRoot()
                        threeDotButtonAnchor.update(
                            membersManagementMenuAnchor(
                                buttonLeftPx = pos.x,
                                buttonTopPx = pos.y,
                                buttonWidthPx = coordinates.size.width.toFloat(),
                                buttonHeightPx = coordinates.size.height.toFloat()
                            )
                        )
                    }
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SlockStroke.Default)
                .background(SlockColor.BorderBlack)
                .align(Alignment.BottomCenter)
        )
    }
}

internal fun membersManagementMenuAnchor(
    buttonLeftPx: Float,
    buttonTopPx: Float,
    buttonWidthPx: Float,
    buttonHeightPx: Float
): Offset =
    slockAnchoredTriggerGeometryAnchor(
        root = Offset(buttonLeftPx, buttonTopPx),
        width = buttonWidthPx,
        height = buttonHeightPx,
        placement = SlockAnchorPlacement.BottomEnd
    )

@Composable
private fun MembersDetailSummary(
    member: MemberProfile,
    detail: AgentDetailSnapshot?,
    deleted: Boolean,
    agentStatusIndicatorState: SlockAgentStatusIndicatorState?,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
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
            statusDisplayState = member.membersAgentStatusDisplayState(summary?.status, summary?.activity),
            statusIndicatorState = agentStatusIndicatorState,
            memberAvatarResolver = memberAvatarResolver
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
private fun MembersRestartResetDialog(
    agentName: String,
    selectedAction: MembersAgentLifecycleAction,
    busy: Boolean,
    errorCode: String?,
    onSelectedActionChange: (MembersAgentLifecycleAction) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    SlockUnifiedDialog(
        title = "Restart ${agentName.uppercase()}",
        onDismiss = onDismiss,
        dismissEnabled = !busy,
        maxWidth = 520.dp,
        titleFontSize = 24,
        titleLineHeight = 30,
        titleMaxLines = 2,
        contentSpacing = SlockSpacing.Md
    ) {
        errorCode?.takeIf { it.isNotBlank() }?.let {
            MembersRestartResetNotice(
                text = "Failed to reset agent: $it",
                color = SlockColor.AccentOrange
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
            MembersRestartResetActionCard(
                action = MembersAgentLifecycleAction.Restart,
                selected = selectedAction == MembersAgentLifecycleAction.Restart,
                enabled = !busy,
                onClick = { onSelectedActionChange(MembersAgentLifecycleAction.Restart) }
            )
            MembersRestartResetActionCard(
                action = MembersAgentLifecycleAction.ResetSession,
                selected = selectedAction == MembersAgentLifecycleAction.ResetSession,
                enabled = !busy,
                onClick = { onSelectedActionChange(MembersAgentLifecycleAction.ResetSession) }
            )
            MembersRestartResetActionCard(
                action = MembersAgentLifecycleAction.ResetFull,
                selected = selectedAction == MembersAgentLifecycleAction.ResetFull,
                enabled = !busy,
                onClick = { onSelectedActionChange(MembersAgentLifecycleAction.ResetFull) }
            )
        }
        if (selectedAction == MembersAgentLifecycleAction.ResetFull) {
            MembersRestartResetNotice(
                text = "This will permanently delete all workspace files including MEMORY.md and notes/. This cannot be undone.",
                color = SlockColor.AccentOrange
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SlockBrutalActionButton(
                label = "Cancel",
                leadingIcon = SlockLucideIconName.X,
                enabled = !busy,
                backgroundColor = SlockColor.SurfaceWhite,
                minWidth = 124.dp,
                minHeight = 36.dp,
                textStyle = SlockType.ButtonBold.copy(fontSize = 13.sp, lineHeight = 16.sp),
                onClick = onDismiss
            )
            SlockBrutalActionButton(
                label = if (busy) "Restarting..." else selectedAction.restartResetButtonLabel(),
                leadingIcon = SlockLucideIconName.RotateCcw,
                enabled = !busy,
                backgroundColor = selectedAction.restartResetAccentColor(),
                minWidth = selectedAction.restartResetButtonMinWidth(),
                minHeight = 36.dp,
                textStyle = SlockType.ButtonBold.copy(fontSize = 13.sp, lineHeight = 16.sp),
                onClick = onConfirm
            )
        }
    }
}

@Composable
private fun MembersRestartResetActionCard(
    action: MembersAgentLifecycleAction,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shadowOffset = if (selected) 4.dp else 0.dp
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(end = shadowOffset, bottom = shadowOffset)
    ) {
        if (shadowOffset > 0.dp) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .offset(x = shadowOffset, y = shadowOffset)
                        .background(SlockColor.ShadowBlack)
            )
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(if (selected) action.restartResetTintColor() else SlockColor.SurfaceWhite)
                    .border(
                        if (selected) SlockStroke.Default else SlockStroke.Thin,
                        if (selected) SlockColor.BorderBlack else SlockColor.BorderBlack.copy(alpha = 0.32f)
                    )
                    .clickable(enabled = enabled, onClick = onClick)
                    .padding(horizontal = SlockSpacing.Md, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Text(
                text = action.restartResetTitle(),
                style = SlockType.ButtonBold.copy(fontSize = 15.sp, lineHeight = 20.sp),
                color = SlockColor.TextBlack,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = action.restartResetDescription(),
                style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = SlockColor.TextMutedGray
            )
        }
    }
}

@Composable
private fun MembersRestartResetNotice(text: String, color: Color) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(color)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(SlockSpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
        verticalAlignment = Alignment.Top
    ) {
        SlockLucideIcon(SlockLucideIconName.TriangleAlert, modifier = Modifier.size(16.dp), color = SlockColor.TextBlackArgb)
        Text(
            text = text,
            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
            color = SlockColor.TextBlack
        )
    }
}

private fun MembersAgentLifecycleAction.isRestartResetDialogAction(): Boolean =
    this == MembersAgentLifecycleAction.Restart ||
        this == MembersAgentLifecycleAction.ResetSession ||
        this == MembersAgentLifecycleAction.ResetFull

private fun MembersAgentLifecycleAction.restartResetTitle(): String =
    when (this) {
        MembersAgentLifecycleAction.Restart -> "RESTART"
        MembersAgentLifecycleAction.ResetSession -> "RESET SESSION & RESTART"
        MembersAgentLifecycleAction.ResetFull -> "FULL RESET & RESTART"
        else -> label.uppercase()
    }

private fun MembersAgentLifecycleAction.restartResetButtonLabel(): String =
    when (this) {
        MembersAgentLifecycleAction.Restart -> "Restart"
        MembersAgentLifecycleAction.ResetSession -> "Reset Session & Restart"
        MembersAgentLifecycleAction.ResetFull -> "Full Reset & Restart"
        else -> label
    }

private fun MembersAgentLifecycleAction.restartResetButtonMinWidth(): Dp =
    when (this) {
        MembersAgentLifecycleAction.Restart -> 136.dp
        MembersAgentLifecycleAction.ResetSession -> 220.dp
        MembersAgentLifecycleAction.ResetFull -> 206.dp
        else -> 136.dp
    }

private fun MembersAgentLifecycleAction.restartResetDescription(): String =
    when (this) {
        MembersAgentLifecycleAction.Restart ->
            "Stop and restart the agent process. Keeps the runtime session and workspace files."
        MembersAgentLifecycleAction.ResetSession ->
            "Clear the runtime session and restart. Workspace files (MEMORY.md, notes/) are preserved."
        MembersAgentLifecycleAction.ResetFull ->
            "Clear the runtime session, delete all workspace files, and restart from scratch."
        else -> ""
    }

private fun MembersAgentLifecycleAction.restartResetAccentColor(): Color =
    when (this) {
        MembersAgentLifecycleAction.ResetFull -> SlockColor.AccentRed
        MembersAgentLifecycleAction.ResetSession -> SlockColor.AccentOrange
        else -> SlockColor.AccentCyan
    }

private fun MembersAgentLifecycleAction.restartResetTintColor(): Color =
    SlockColor.AppBgLightBlue

@Composable
private fun MembersAgentTabBar(
    tabs: List<MembersAgentDetailTab>,
    selectedTab: MembersAgentDetailTab,
    onSelectedTabChange: (MembersAgentDetailTab) -> Unit,
    onReorderTabs: ((List<MembersAgentDetailTab>) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    var draggedTab by remember(tabs) { mutableStateOf<MembersAgentDetailTab?>(null) }
    var dragStartIndex by remember(tabs) { mutableStateOf(-1) }
    var dragOffsetX by remember(tabs) { mutableStateOf(0f) }
    LaunchedEffect(tabs, selectedTab) {
        if (tabs.isNotEmpty() && !listState.isScrollInProgress) {
            listState.scrollToItem(membersAgentTabVisibleAnchorIndex(tabs, selectedTab))
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(SlockTopTabBarDefaults.Height)
            .background(SlockColor.SurfaceWhite)
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            items(tabs.size, key = { "members-agent-tab-${tabs[it].name}" }) { index ->
                val tab = tabs[index]
                val spec = tab.toSlockTabSpec()
                val selected = tab == selectedTab
                val dragging = tab == draggedTab
                Row(
                    modifier = Modifier
                        .width(spec.width)
                        .fillMaxHeight()
                        .background(
                            when {
                                dragging -> SlockColor.AccentCyan
                                selected -> SlockColor.BrandYellow
                                else -> SlockColor.SurfaceWhite
                            }
                        )
                        .clickable { onSelectedTabChange(tab) }
                        .pointerInput(tabs, tab, onReorderTabs) {
                            if (onReorderTabs == null) return@pointerInput
                            val stepWidthPx = spec.width.toPx().coerceAtLeast(1f)
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggedTab = tab
                                    dragStartIndex = index
                                    dragOffsetX = 0f
                                },
                                onDragEnd = {
                                    val fromIndex = dragStartIndex
                                    val toIndex = (fromIndex + (dragOffsetX / stepWidthPx).roundToInt())
                                        .coerceIn(0, tabs.lastIndex)
                                    if (fromIndex in tabs.indices && toIndex != fromIndex) {
                                        onReorderTabs(tabs.membersMoveTab(fromIndex, toIndex))
                                    }
                                    draggedTab = null
                                    dragStartIndex = -1
                                    dragOffsetX = 0f
                                },
                                onDragCancel = {
                                    draggedTab = null
                                    dragStartIndex = -1
                                    dragOffsetX = 0f
                                }
                            ) { _, dragAmount ->
                                dragOffsetX += dragAmount.x
                            }
                        }
                        .padding(horizontal = SlockSpacing.Sm + SlockSpacing.Xxs, vertical = SlockSpacing.Xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm - SlockSpacing.Xxs, Alignment.CenterHorizontally)
                ) {
                    SlockLucideIcon(spec.iconName, modifier = Modifier.size(16.dp), color = SlockColor.TextBlackArgb)
                    Text(
                        text = spec.label,
                        style = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp, color = SlockColor.TextBlack),
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .height(SlockTopTabBarDefaults.Height - SlockStroke.Default)
                        .width(SlockStroke.Default)
                        .background(SlockColor.BorderBlack)
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
    }
}

private fun MembersAgentDetailTab.toSlockTabSpec(): SlockTabSpec<MembersAgentDetailTab> =
    SlockTabSpec(
        key = this,
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
            MembersAgentDetailLoadState.Idle -> null
            is MembersAgentDetailLoadState.Content ->
                if (state.refreshing) "Refreshing agent profile..." else null
        }
    val privateMessage =
        if (!canViewPrivate && state is MembersAgentDetailLoadState.Content) {
            "Profile is public. Permissions, Agent DMs, Reminders, Workspace, Apps, and Activity are hidden for this account."
        } else {
            null
        }
    val resolved = message ?: privateMessage ?: return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(SlockColor.SurfaceCream)
            .border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = resolved,
            style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp).withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.TextBlack,
            modifier = Modifier.padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Xs)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MembersAgentRuntimeErrorBanner(
    errorText: String,
    canViewPrivate: Boolean,
    onViewLogs: () -> Unit,
    onCopyInfo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(SlockStroke.Default, SlockColor.AccentOrange)
            .background(SlockColor.SurfaceCream)
            .padding(SlockSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SlockStatusIndicator(
                color = SlockColor.AccentOrange,
                size = 9.dp,
                pulsing = false
            )
            Text(
                text = if (canViewPrivate) errorText else "Agent encountered an error",
                style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp)
                    .withSlockFontWeight(FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        if (canViewPrivate) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                SlockBrutalActionButton(
                    label = "View logs",
                    leadingIcon = SlockLucideIconName.FileText,
                    minWidth = 92.dp,
                    minHeight = 30.dp,
                    textStyle = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp)
                        .withSlockFontWeight(FontWeight.Bold),
                    shadowOffset = 2.dp,
                    onClick = onViewLogs
                )
                SlockBrutalActionButton(
                    label = "Copy info",
                    leadingIcon = SlockLucideIconName.Copy,
                    minWidth = 92.dp,
                    minHeight = 30.dp,
                    textStyle = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp)
                        .withSlockFontWeight(FontWeight.Bold),
                    shadowOffset = 2.dp,
                    onClick = onCopyInfo
                )
            }
        }
    }
}

@Composable
private fun MembersHumanDetailSections(
    member: MemberProfile,
    timeContext: SlockTimeContext,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    onRemoveMember: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        MembersHumanProfileSummary(member, memberAvatarResolver)
        MembersHumanDescriptionSection(member)
        MembersHumanInfoSection(member, timeContext)
        MembersHumanCreatedAgentsSection(member, memberAvatarResolver)
        if (onRemoveMember != null) {
            MembersHumanActionsSection(onRemoveMember)
        }
    }
}

@Composable
private fun MembersHumanProfileSummary(
    member: MemberProfile,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SlockSpacing.Md),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        MembersAvatar(
            member = member,
            avatarSize = 74.dp,
            statusSize = 0.dp,
            memberAvatarResolver = memberAvatarResolver,
            showStatusOverlay = false
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Text(
                text = member.displayName,
                style = SlockType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp),
                color = SlockColor.TextBlack,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (member.membershipStatus == "removed") {
                MembersHumanRoleChip("Removed", SlockColor.EmptyStateGray)
            }
        }
    }
}

@Composable
private fun MembersHumanDescriptionSection(member: MemberProfile) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        MembersDetailSectionDivider()
        MembersDetailSectionTitle("DESCRIPTION")
        val descriptionMissing = member.description.isNullOrBlank()
        Text(
            text = member.description?.takeIf { it.isNotBlank() } ?: "No description",
            style = SlockType.Body.copy(
                fontSize = 15.sp,
                lineHeight = 22.sp,
                fontStyle = if (descriptionMissing) FontStyle.Italic else FontStyle.Normal
            ),
            color = if (descriptionMissing) {
                SlockColor.TextMutedGray.copy(alpha = 0.75f)
            } else {
                SlockColor.TextBlack
            }
        )
    }
}

@Composable
private fun MembersHumanInfoSection(
    member: MemberProfile,
    timeContext: SlockTimeContext
) {
    val joined = member.joinedAt?.takeIf { it.isNotBlank() }?.membersAgentCreatedDate(timeContext)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        MembersDetailSectionDivider()
        MembersDetailSectionTitle("INFO")
        MembersHumanInfoItem(label = "Role") {
            MembersHumanRoleChip(
                label = member.roleDescription?.takeIf { it.isNotBlank() } ?: "Member",
                backgroundColor = member.membersHumanRoleChipColor()
            )
        }
        member.email?.takeIf { it.isNotBlank() }?.let { email ->
            MembersHumanInfoItem(label = "Email") {
                Text(
                    text = email,
                    style = SlockType.Input.copy(fontSize = 16.sp, lineHeight = 22.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        joined?.takeIf { it.isNotBlank() }?.let { joinedDate ->
            MembersHumanInfoItem(label = "Joined") {
                Text(
                    text = joinedDate,
                    style = SlockType.Input.copy(fontSize = 16.sp, lineHeight = 22.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MembersHumanInfoItem(
    label: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Text(
            text = label,
            style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        content()
    }
}

@Composable
private fun MembersHumanRoleChip(
    label: String,
    backgroundColor: Color
) {
    Box(
        modifier = Modifier
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .background(backgroundColor)
            .padding(horizontal = SlockSpacing.Sm, vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = SlockType.ButtonBold.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MembersHumanCreatedAgentsSection(
    member: MemberProfile,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver
) {
    val createdAgents = member.createdAgents
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        MembersDetailSectionDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            MembersDetailSectionTitle("CREATED AGENTS")
            Text(
                text = createdAgents.size.toString(),
                style = SlockType.Input.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = SlockColor.TextMutedGray.copy(alpha = 0.7f)
            )
        }
        if (createdAgents.isEmpty()) {
            Text(
                text = "No created agents",
                style = SlockType.Body.copy(
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    fontStyle = FontStyle.Italic
                ),
                color = SlockColor.TextMutedGray.copy(alpha = 0.75f)
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                createdAgents.forEach { agent ->
                    MembersHumanCreatedAgentRow(agent, memberAvatarResolver)
                }
            }
        }
    }
}

@Composable
private fun MembersHumanActionsSection(onRemoveMember: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        MembersDetailSectionDivider()
        MembersDetailSectionTitle("ACTIONS")
        SlockBrutalActionButton(
            label = "Remove Member",
            leadingIcon = SlockLucideIconName.Trash2,
            minHeight = 40.dp,
            backgroundColor = SlockColor.AccentRed,
            textStyle = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 3.dp),
            onClick = onRemoveMember
        )
    }
}

@Composable
private fun MembersHumanCreatedAgentRow(
    agent: AgentProfile,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver
) {
    val profile = remember(agent) { agent.toMemberProfile() }
    val statusDisplayState = profile.toSlockAgentStatusIndicatorState(
        summaryStatus = agent.statusLabel,
        summaryActivity = agent.statusLabel
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Thin, SlockColor.BorderBlack.copy(alpha = 0.25f))
            .background(SlockColor.SurfaceWhite)
            .padding(horizontal = 12.dp, vertical = SlockSpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MembersAvatar(
            member = profile,
            avatarSize = 32.dp,
            statusSize = 0.dp,
            memberAvatarResolver = memberAvatarResolver,
            showStatusOverlay = false
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = profile.displayName,
                style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp)
                    .withSlockFontWeight(FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = agent.runtime?.takeIf { it.isNotBlank() }?.let { getRuntimeDisplayName(it) }
                    ?: profile.detailRoleLabel,
                style = SlockType.Input.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        SlockStatusIndicator(
            color = statusDisplayState.color,
            size = 10.dp,
            pulsing = statusDisplayState.pulsing
        )
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
            MembersAgentAvatarChoiceGrid(
                member = member,
                uploading = uploading,
                onUploadClick = {
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
                onClear = onClear,
                onSelect = onSelect
            )
        }
    }
}

private val MembersAgentAvatarPickerCellSize = 40.dp
private val MembersAgentAvatarPickerAvatarSize = 36.dp

@Composable
private fun MembersAgentAvatarChoiceGrid(
    member: MemberProfile,
    uploading: Boolean,
    onUploadClick: () -> Unit,
    onClear: () -> Unit,
    onSelect: (String) -> Unit
) {
    // React parity: each cell renders the actual pixel art (or upload icon
    // for the first cell) inside a 40dp cell with a 2dp border, mirroring
    // `AgentDetailPanel.tsx`. Active selection gets a pink border
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
        Box(
            modifier = Modifier
                .size(MembersAgentAvatarPickerCellSize)
                .border(SlockStroke.Default, if (activeCustomAvatar) SlockColor.AccentPink else SlockColor.BorderBlack)
                .background(if (activeCustomAvatar) SlockColor.AccentPink.copy(alpha = 0.2f) else Color.Transparent)
                .clickable(enabled = !uploading) { onUploadClick() },
            contentAlignment = Alignment.Center
        ) {
            if (activeCustomAvatar) {
                SlockAvatar(
                    avatarWireValue = member.avatarUrl,
                    label = "Custom avatar",
                    avatarSize = MembersAgentAvatarPickerAvatarSize,
                    borderWidth = 0.dp
                )
            } else {
                SlockLucideIcon(
                    if (uploading) SlockLucideIconName.LoaderCircle else SlockLucideIconName.Upload,
                    modifier = Modifier.size(16.dp),
                    color = "#99000000",
                    spinning = uploading
                )
            }
        }
        MembersAgentAvatarPickerCell(
            wireValue = "pixel:$PixelAvatarDefaultKey",
            fallbackLabel = PixelAvatarDefaultKey,
            active = activePixelKey == PixelAvatarDefaultKey,
            onClick = onClear
        )
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
private fun MembersAgentProfileHeader(
    member: MemberProfile,
    summary: AgentDetailSummary?,
    canEdit: Boolean,
    agentStatusIndicatorState: SlockAgentStatusIndicatorState?,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    showAvatarPicker: Boolean,
    onToggleAvatarPicker: () -> Unit,
    onSelectAvatar: (String) -> Unit,
    onClearAvatar: () -> Unit,
    onUploadAvatar: suspend () -> DataResult<MemberProfile>,
    onUnavailableAction: (String) -> Unit
) {
    val statusDisplayState = member.membersAgentStatusDisplayState(summary?.status, summary?.activity)
    val statusLabel = summary?.activity?.takeIf { it.isNotBlank() }
        ?: statusDisplayState.status?.label
        ?: member.detailStatusLabel
    val scope = rememberCoroutineScope()
    var uploading by remember(member.id.value) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = SlockSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp),
                contentAlignment = Alignment.Center
            ) {
                MembersAvatar(
                    member = member,
                    avatarSize = 64.dp,
                    memberAvatarResolver = memberAvatarResolver,
                    showStatusOverlay = false
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = member.displayName,
                    style = SlockType.Body.copy(fontSize = 18.sp, lineHeight = 22.sp)
                        .withSlockFontWeight(FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = member.detailHandle,
                    style = SlockType.Caption.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SlockStatusIndicator(
                        color = agentStatusIndicatorState?.color ?: SlockColor.EmptyStateGray,
                        size = 8.dp,
                        pulsing = agentStatusIndicatorState?.pulsing == true
                    )
                    Text(
                        text = statusLabel,
                        style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (canEdit && showAvatarPicker) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = SlockSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                MembersDetailSectionDivider()
                Text(
                    text = "CHOOSE AVATAR",
                    style = SlockType.SectionLabel.copy(fontSize = 11.sp, lineHeight = 16.sp),
                    color = SlockColor.TextMutedGray
                )
                MembersAgentAvatarChoiceGrid(
                    member = member,
                    uploading = uploading,
                    onUploadClick = {
                        if (!uploading) {
                            uploading = true
                            scope.launch {
                                when (val result = onUploadAvatar()) {
                                    is DataResult.Content -> uploading = false
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
                    onClear = onClearAvatar,
                    onSelect = onSelectAvatar
                )
            }
        }
    }
}

@Composable
private fun MembersAgentProfileTab(
    member: MemberProfile,
    detail: AgentDetailSnapshot?,
    canManage: Boolean,
    agentStatusIndicatorState: SlockAgentStatusIndicatorState?,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    showAvatarPicker: Boolean,
    onToggleAvatarPicker: () -> Unit,
    onSelectAvatar: (String) -> Unit,
    onClearAvatar: () -> Unit,
    onUploadAvatar: suspend () -> DataResult<MemberProfile>,
    sharedAgentDetailStore: SharedAgentDetailStore? = null,
    storeState: SharedAgentDetailState? = null,
    agentDetailActions: SharedAgentDetailActions? = null,
    onUpdateAgentProfile: (suspend (AgentProfileUpdateRequest) -> DataResult<MemberProfile>)?,
    onUnavailableAction: (String) -> Unit,
    lifecycleInFlight: String? = null,
    diagnosticActionState: MembersAgentDiagnosticActionState? = null,
    onRunLifecycleAction: (MembersAgentLifecycleAction) -> Unit = {},
    onOpenRestartReset: () -> Unit = {},
    onCopyDiagnostics: () -> Unit = {},
    onReportIssue: () -> Unit = {},
    timeContext: SlockTimeContext,
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
    var editingRuntimeConfig by remember(member.id.value) { mutableStateOf(false) }
    var savingRuntimeConfig by remember(member.id.value) { mutableStateOf(false) }
    var runtimeConfigError by remember(member.id.value) { mutableStateOf<String?>(null) }

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

    fun saveRuntimeConfig(nextConfig: RuntimeConfig) {
        val callback = onUpdateAgentProfile
        if (!canEdit || (agentDetailActions == null && callback == null) || savingRuntimeConfig) {
            onUnavailableAction("Runtime config editing is not available.")
            return
        }
        val currentConfig = summary.toMembersRuntimeConfig(member)
        val currentStatus = AgentStatus.fromWireValue(summary?.status)
        val activeAgent = currentStatus != null && currentStatus.presence != Presence.Offline
        val restartMode =
            when {
                !activeAgent -> null
                nextConfig.runtime != currentConfig.runtime -> AgentResetMode.Session
                else -> AgentResetMode.Restart
            }
        scope.launch {
            savingRuntimeConfig = true
            runtimeConfigError = null
            val result =
                try {
                    val request = AgentProfileUpdateRequest(
                        agentId = member.id,
                        displayName = member.displayName,
                        description = member.description?.takeIf { it.isNotBlank() },
                        runtimeConfig = nextConfig,
                        includeRuntimeConfig = true,
                        runtimeResetMode = restartMode
                    )
                    if (agentDetailActions != null) {
                        agentDetailActions.updateAgentProfile(request)
                    } else {
                        requireNotNull(callback).invoke(request)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    DataResult.Error(ScreenError("agent_runtime_config_save_failed"))
                }
            when (result) {
                is DataResult.Content,
                is DataResult.Empty -> {
                    editingRuntimeConfig = false
                    val store = sharedAgentDetailStore
                    val currentDetail = detail
                    if (store != null && currentDetail != null) {
                        store.dispatch(
                            SharedAgentDetailIntent.SetDetail(
                                member.id,
                                currentDetail.withRuntimeConfigProjection(nextConfig)
                            )
                        )
                    }
                }
                is DataResult.NoPermission ->
                    runtimeConfigError = "No permission: ${result.blocker.code}"
                is DataResult.Error ->
                    runtimeConfigError = "Save failed: ${result.error.code}"
            }
            savingRuntimeConfig = false
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
        if (deleted) {
            MembersDetailSection(title = "DELETED") {
                MembersDetailField("Status", "Deleted")
                MembersDetailField("Deleted at", summary.deletedAt.orDash())
            }
        }

        MembersAgentProfileHeader(
            member = member,
            summary = summary,
            canEdit = canEdit,
            agentStatusIndicatorState = agentStatusIndicatorState,
            memberAvatarResolver = memberAvatarResolver,
            showAvatarPicker = showAvatarPicker,
            onToggleAvatarPicker = onToggleAvatarPicker,
            onSelectAvatar = onSelectAvatar,
            onClearAvatar = onClearAvatar,
            onUploadAvatar = onUploadAvatar,
            onUnavailableAction = onUnavailableAction
        )

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
                MembersEditableSectionTitleRow(
                    title = "DISPLAY NAME",
                    showEdit = canEdit && !editingDisplayName,
                    onEditClick = {
                        if (storeBackedProfileEdit) {
                            profileStore.dispatch(
                                SharedAgentDetailIntent.BeginEditDisplayName(member.displayName)
                            )
                        } else {
                            localDraftDisplayName = member.displayName
                            localDisplayNameError = null
                            localEditingDisplayName = true
                        }
                    }
                )
            }
            if (editingDisplayName) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
                ) {
                    SlockBrutalInputField(
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
                MembersEditableSectionTitleRow(
                    title = "DESCRIPTION",
                    showEdit = canEdit && !editingRole,
                    onEditClick = {
                        if (storeBackedProfileEdit) {
                            profileStore.dispatch(
                                SharedAgentDetailIntent.BeginEditRole(member.description.orEmpty())
                            )
                        } else {
                            localDraftRole = member.description.orEmpty()
                            localRoleError = null
                            localEditingRole = true
                        }
                    }
                )
            }
            if (editingRole) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
                ) {
                    SlockBrutalInputField(
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

        // INFO section — mirrors React AgentDetailPanel's Computer / Created / Creator rows.
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = SlockSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            MembersDetailSectionDivider()
            MembersDetailSectionTitle("INFO")
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
            summary?.createdAt?.membersAgentCreatedDate(timeContext)?.takeIf { it.isNotBlank() }?.let { createdDate ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Created",
                        style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                        color = SlockColor.TextMutedGray,
                        modifier = Modifier.width(86.dp)
                    )
                    Text(
                        text = createdDate,
                        style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                val creatorProfile = summary?.creator?.toMemberProfile()
                if (creatorProfile != null) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MembersAvatar(
                            member = creatorProfile,
                            avatarSize = 22.dp,
                            statusSize = 0.dp,
                            memberAvatarResolver = memberAvatarResolver,
                            showStatusOverlay = false
                        )
                        Text(
                            text = creatorProfile.displayName,
                            style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp)
                                .withSlockFontWeight(FontWeight.Bold),
                            color = SlockColor.TextBlack,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Text(
                            text = creatorProfile.detailHandle,
                            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                            color = SlockColor.TextMutedGray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Text(
                        text = "No creator assigned",
                        style = SlockType.Body.copy(
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            fontStyle = FontStyle.Italic
                        ),
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // RUNTIME CONFIG section — mirrors React `AgentDetailPanel` runtime block:
        // key/value rows with brutal semantic chips, env vars embedded in the same
        // section, and a pencil entry point for the shared runtime-config editor.
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = SlockSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            MembersDetailSectionDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                MembersEditableSectionTitleRow(
                    title = "RUNTIME CONFIG",
                    showEdit = canEdit && !editingRuntimeConfig,
                    onEditClick = {
                        runtimeConfigError = null
                        editingRuntimeConfig = true
                    }
                )
            }
            MembersAgentRuntimeConfigRows(summary = summary, member = member)
            MembersAgentRuntimeEnvVars(summary)
        }

        if (editingRuntimeConfig) {
            MembersAgentRuntimeConfigDialog(
                member = member,
                summary = summary,
                saving = savingRuntimeConfig,
                error = runtimeConfigError,
                onDismiss = {
                    if (!savingRuntimeConfig) {
                        editingRuntimeConfig = false
                        runtimeConfigError = null
                    }
                },
                onSave = { nextConfig -> saveRuntimeConfig(nextConfig) }
            )
        }

        // Avatar editing is surfaced from the top-left avatar in `MembersDetailHeader`
        // (PUX #49 spec v4 lock). Clicking it opens the floating `MembersAgentAvatarPicker`
        // overlay, owned by `MembersDetailContent`. The body AVATAR section + the in-page
        // picker invocation that previously lived here were removed in this commit so the
        // avatar entry point is consistent with React `AgentDetailPanel.tsx`.
        MembersAgentCreatedAgentsSection(
            detail = detail,
            agentStatusResolver = agentStatusResolver,
            memberAvatarResolver = memberAvatarResolver
        )
        MembersAgentSkillsSection(detail)
        MembersAgentProfileActionsSection(
            member = member,
            detail = detail,
            canManage = canManage,
            lifecycleInFlight = lifecycleInFlight,
            diagnosticActionState = diagnosticActionState,
            onRunLifecycleAction = onRunLifecycleAction,
            onOpenRestartReset = onOpenRestartReset,
            onCopyDiagnostics = onCopyDiagnostics,
            onReportIssue = onReportIssue
        )
    }
}

@Composable
private fun MembersAgentProfileActionsSection(
    member: MemberProfile,
    detail: AgentDetailSnapshot?,
    canManage: Boolean,
    lifecycleInFlight: String?,
    diagnosticActionState: MembersAgentDiagnosticActionState?,
    onRunLifecycleAction: (MembersAgentLifecycleAction) -> Unit,
    onOpenRestartReset: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onReportIssue: () -> Unit
) {
    val summary = detail?.summary
    val deleted = summary?.deletedAt?.isNotBlank() == true
    if (!canManage || deleted) return

    val status = member.membersAgentStatusDisplayState(summary?.status, summary?.activity).status
    val primaryAction = membersPrimaryLifecycleAction(status, deleted = false)
    val primaryLabel =
        when (primaryAction) {
            MembersAgentLifecycleAction.Start -> "Start Agent"
            MembersAgentLifecycleAction.Stop -> "Stop Agent"
            null -> "Start Agent"
            else -> primaryAction.label
        }
    val primaryBusy = lifecycleInFlight == primaryAction?.actionKey
    val copyState = diagnosticActionState?.takeIf { it.actionKey == "copy" }
    val reportState = diagnosticActionState?.takeIf { it.actionKey == "report" }
    val diagnosticBusy = diagnosticActionState?.inProgress == true

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        MembersDetailSectionDivider()
        MembersDetailSectionTitle("ACTIONS")
        if (primaryAction != null) {
            SlockBrutalActionButton(
                label = if (primaryBusy) primaryAction.profileActionBusyLabel() else primaryLabel,
                leadingIcon = primaryAction.iconName,
                enabled = lifecycleInFlight == null,
                minHeight = 42.dp,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onRunLifecycleAction(primaryAction) }
            )
        }
        SlockBrutalActionButton(
            label = "Restart / Reset",
            leadingIcon = SlockLucideIconName.RotateCcw,
            enabled = lifecycleInFlight == null,
            minHeight = 42.dp,
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenRestartReset
        )
        SlockBrutalActionButton(
            label = copyState?.successLabel ?: "Copy Diagnostic Info",
            leadingIcon = if (copyState?.successLabel != null) SlockLucideIconName.Check else SlockLucideIconName.Clipboard,
            enabled = !diagnosticBusy,
            minHeight = 42.dp,
            modifier = Modifier.fillMaxWidth(),
            onClick = onCopyDiagnostics
        )
        SlockBrutalActionButton(
            label = if (reportState?.inProgress == true) "Reporting..." else "Report Issue",
            leadingIcon = SlockLucideIconName.Bug,
            enabled = !diagnosticBusy,
            backgroundColor = SlockColor.AccentOrange,
            minHeight = 42.dp,
            modifier = Modifier.fillMaxWidth(),
            onClick = onReportIssue
        )
        SlockBrutalActionButton(
            label = if (lifecycleInFlight == MembersAgentLifecycleAction.Delete.actionKey) "Deleting..." else "Delete Agent",
            leadingIcon = SlockLucideIconName.Trash2,
            enabled = lifecycleInFlight == null,
            backgroundColor = SlockColor.AccentRed,
            minHeight = 42.dp,
            modifier = Modifier.fillMaxWidth(),
            onClick = { onRunLifecycleAction(MembersAgentLifecycleAction.Delete) }
        )
    }
}

private fun MembersAgentLifecycleAction.profileActionBusyLabel(): String =
    when (this) {
        MembersAgentLifecycleAction.Start -> "Starting..."
        MembersAgentLifecycleAction.Stop -> "Stopping..."
        MembersAgentLifecycleAction.Delete -> "Deleting..."
        MembersAgentLifecycleAction.Restart -> "Restarting..."
        MembersAgentLifecycleAction.ResetSession -> "Resetting..."
        MembersAgentLifecycleAction.ResetFull -> "Resetting..."
        MembersAgentLifecycleAction.UnassignMachine -> "Unassigning..."
    }

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun MembersAgentRuntimeConfigRows(
    summary: AgentDetailSummary?,
    member: MemberProfile
) {
    val runtimeConfig = summary.toMembersRuntimeConfig(member)
    val runtimeId = runtimeConfig.runtime
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        MembersAgentRuntimeConfigField("Runtime") {
            MembersAgentRuntimeChip(
                label = getRuntimeDisplayName(runtimeId),
                kind = MembersAgentRuntimeChipKind.Runtime
            )
        }
        MembersAgentRuntimeConfigField("Model") {
            MembersAgentRuntimeChip(
                label = getModelLabel(runtimeId, runtimeConfig.toMembersRuntimeModelValue()),
                kind = MembersAgentRuntimeChipKind.Model
            )
        }
        if (supportsRuntimeReasoningEffort(runtimeId)) {
            MembersAgentRuntimeConfigField("Reasoning") {
                MembersAgentRuntimeChip(
                    label = runtimeConfig.reasoningEffort?.toMembersDisplayLabel() ?: "Default",
                    kind = MembersAgentRuntimeChipKind.Reasoning
                )
            }
        }
        if (supportsRuntimeFastMode(runtimeId)) {
            MembersAgentRuntimeConfigField("Mode") {
                MembersAgentRuntimeChip(
                    label = if (runtimeConfig.mode is RuntimeModeConfig.Fast) "Fast" else "Default",
                    kind = MembersAgentRuntimeChipKind.Mode
                )
            }
        }
        if (runtimeId == "claude") {
            MembersAgentRuntimeConfigField("Provider") {
                val provider = runtimeConfig.provider as? RuntimeProviderConfig.Custom
                Text(
                    text = provider?.apiUrl?.takeIf { it.isNotBlank() } ?: "Default",
                    style = SlockType.Caption.copy(
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontStyle = if (provider == null) FontStyle.Italic else FontStyle.Normal
                    ),
                    color = if (provider == null) SlockColor.TextMutedGray else SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            MembersAgentRuntimeConfigField("Command") {
                val command = runtimeConfig.command?.takeIf { it.isNotBlank() }
                Text(
                    text = command ?: "Default",
                    style = SlockType.Caption.copy(
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontStyle = if (command == null) FontStyle.Italic else FontStyle.Normal
                    ),
                    color = if (command == null) SlockColor.TextMutedGray else SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MembersAgentRuntimeConfigField(
    label: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.width(122.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        content()
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun MembersAgentRuntimeEnvVars(summary: AgentDetailSummary?) {
    val envVarNames = summary.membersRuntimeEnvVarNames()
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = SlockSpacing.Xs),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Text(
            text = "Environment Variables",
            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (envVarNames.isEmpty()) {
            Text(
                text = "No environment variables configured",
                style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                color = SlockColor.TextMutedGray,
                fontStyle = FontStyle.Italic
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
            ) {
                envVarNames.forEach { name ->
                    Box(
                        modifier = Modifier
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(horizontal = SlockSpacing.Xs, vertical = 2.dp)
                    ) {
                        Text(
                            text = "$name=***",
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
}

@Composable
private fun MembersAgentRuntimeConfigDialog(
    member: MemberProfile,
    summary: AgentDetailSummary?,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (RuntimeConfig) -> Unit
) {
    val initialConfig = remember(member.id.value, summary) { summary.toMembersRuntimeConfig(member) }
    var runtime by remember(initialConfig) { mutableStateOf(initialConfig.runtime) }
    val initialModelValue = remember(initialConfig) { initialConfig.toMembersRuntimeModelValue() }
    val initialModelSelection = remember(initialConfig) {
        createAgentModelSelectionForValue(initialConfig.runtime, initialModelValue)
    }
    var model by remember(initialConfig) { mutableStateOf(initialModelSelection.second) }
    var customModelMode by remember(initialConfig) { mutableStateOf(initialModelSelection.first) }
    var customModelName by remember(initialConfig) {
        mutableStateOf(if (initialModelSelection.first) initialModelSelection.second else "")
    }
    val initialProvider = initialConfig.provider as? RuntimeProviderConfig.Custom
    var providerMode by remember(initialConfig) {
        mutableStateOf(
            if (initialProvider != null) RUNTIME_PROVIDER_MODE_CUSTOM else RUNTIME_PROVIDER_MODE_DEFAULT
        )
    }
    var providerApiUrl by remember(initialConfig) { mutableStateOf(initialProvider?.apiUrl.orEmpty()) }
    var providerApiKey by remember(initialConfig) { mutableStateOf(initialProvider?.apiKey.orEmpty()) }
    var fastMode by remember(initialConfig) { mutableStateOf(initialConfig.mode is RuntimeModeConfig.Fast) }
    var reasoningEffort by remember(initialConfig) { mutableStateOf(initialConfig.reasoningEffort) }
    var command by remember(initialConfig) { mutableStateOf(initialConfig.command.orEmpty()) }
    var envVarEntries by remember(initialConfig) {
        mutableStateOf(initialConfig.envVars.orEmpty().entries.map { it.key to it.value })
    }
    var advancedOpen by remember(initialConfig) {
        mutableStateOf(command.isNotBlank() || envVarEntries.isNotEmpty())
    }
    val customModelMissing = customModelMode && customModelName.trim().isBlank()
    val providerUrlInvalid =
        runtime == "claude" &&
            providerMode == RUNTIME_PROVIDER_MODE_CUSTOM &&
            !Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(providerApiUrl.trim())
    val providerKeyInvalid =
        runtime == "claude" &&
            providerMode == RUNTIME_PROVIDER_MODE_CUSTOM &&
            providerApiKey.isBlank()

    SlockUnifiedDialog(
        title = "EDIT RUNTIME CONFIG",
        dismissEnabled = !saving,
        onDismiss = onDismiss
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    AdvancedRuntimeSection(
                        runtime = runtime,
                        onRuntimeChange = { nextRuntime ->
                            runtime = nextRuntime
                            model = getDefaultModel(nextRuntime)
                            customModelMode = false
                            customModelName = ""
                            providerMode = RUNTIME_PROVIDER_MODE_DEFAULT
                            providerApiUrl = ""
                            providerApiKey = ""
                            fastMode = false
                            command = ""
                            if (!supportsRuntimeReasoningEffort(nextRuntime)) {
                                reasoningEffort = null
                            }
                        },
                        model = model,
                        onModelChange = { selectedModel ->
                            model = selectedModel
                            customModelName = selectedModel
                        },
                        customModelMode = customModelMode,
                        onCustomModelModeChange = { enabled ->
                            customModelMode = enabled
                            if (enabled && customModelName.isBlank()) {
                                customModelName = model
                            }
                        },
                        customModelName = customModelName,
                        onCustomModelNameChange = {
                            customModelName = it
                            model = it
                        },
                        runtimeModelsOverride = emptyList(),
                        enabled = !saving,
                        providerMode = providerMode,
                        onProviderModeChange = { providerMode = it },
                        providerApiUrl = providerApiUrl,
                        onProviderApiUrlChange = { providerApiUrl = it },
                        providerApiKey = providerApiKey,
                        onProviderApiKeyChange = { providerApiKey = it },
                        fastMode = fastMode,
                        onFastModeChange = { fastMode = it },
                        reasoningEffort = reasoningEffort,
                        onReasoningEffortChange = { reasoningEffort = it },
                        envVarEntries = envVarEntries,
                        onEnvVarEntriesChange = { envVarEntries = it },
                        command = command,
                        onCommandChange = { command = it },
                        advancedOpen = advancedOpen,
                        onAdvancedOpenChange = { advancedOpen = it }
                    )
                }
                item {
                    Text(
                        text = "Changing runtime config may restart the agent so the next launch uses the new settings.",
                        style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                        color = SlockColor.TextMutedGray
                    )
                }
                error?.takeIf { it.isNotBlank() }?.let { message ->
                    item {
                        Text(
                            text = message,
                            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold)
                                .copy(color = SlockColor.AccentRed),
                            color = SlockColor.AccentRed
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                SlockBrutalActionButton(
                    label = "Cancel",
                    enabled = !saving,
                    backgroundColor = SlockColor.SurfaceWhite,
                    onClick = onDismiss
                )
                SlockBrutalActionButton(
                    label = if (saving) "Saving..." else "Save Runtime Config",
                    enabled = !saving && !customModelMissing && !providerUrlInvalid && !providerKeyInvalid,
                    backgroundColor = SlockColor.AccentPink,
                    minWidth = 166.dp,
                    onClick = {
                        onSave(
                            buildRuntimeConfig(
                                runtime = runtime,
                                model = if (customModelMode) customModelName else model,
                                customModelMode = customModelMode,
                                customModelName = customModelName.takeIf { customModelMode },
                                providerMode = providerMode,
                                providerApiUrl = providerApiUrl,
                                providerApiKey = providerApiKey,
                                fastMode = fastMode,
                                reasoningEffort = reasoningEffort,
                                envVars = envVarEntries.toMembersRuntimeEnvVars(),
                                command = command
                            )
                        )
                    }
                )
            }
        }
    }
}

private fun AgentDetailSummary?.toMembersRuntimeConfig(member: MemberProfile): RuntimeConfig {
    val runtime = this?.runtime?.takeIf { it.isNotBlank() }
        ?: member.roleDescription?.takeIf { it.isNotBlank() }
        ?: RUNTIMES.first().id
    val model = this?.model?.takeIf { it.isNotBlank() } ?: getDefaultModel(runtime)
    return RuntimeConfig(
        runtime = runtime,
        model = RuntimeModelConfig.Preset(model),
        mode = if (this?.executionMode?.trim()?.lowercase() == "fast") {
            RuntimeModeConfig.Fast
        } else {
            RuntimeModeConfig.Default
        },
        reasoningEffort = this?.reasoning?.takeIf { it.isNotBlank() },
        envVars = this?.envVars?.takeIf { it.isNotEmpty() }
    )
}

private fun RuntimeConfig.toMembersRuntimeModelValue(): String =
    when (val modelConfig = model) {
        is RuntimeModelConfig.Preset -> modelConfig.id
        is RuntimeModelConfig.Custom -> modelConfig.name
    }

private fun AgentDetailSummary?.membersRuntimeEnvVarNames(): List<String> =
    if (this == null) {
        emptyList()
    } else {
        (envVars.keys + envVarNames)
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .distinct()
            .sorted()
    }

private fun List<Pair<String, String>>.toMembersRuntimeEnvVars(): Map<String, String>? {
    val values =
        mapNotNull { (key, value) ->
            key.trim().takeIf { it.isNotBlank() }?.let { it to value }
        }.toMap()
    return values.takeIf { it.isNotEmpty() }
}

private fun AgentDetailSnapshot.withRuntimeConfigProjection(config: RuntimeConfig): AgentDetailSnapshot {
    val currentSummary = summary ?: AgentDetailSummary(id = agentId.value, name = agentId.value)
    return copy(
        summary = currentSummary.copy(
            runtime = config.runtime,
            model = config.toMembersRuntimeModelValue(),
            reasoning = config.reasoningEffort,
            executionMode = if (config.mode is RuntimeModeConfig.Fast) "fast" else "default",
            envVars = config.envVars.orEmpty(),
            envVarNames = config.envVars.orEmpty().keys.sorted()
        )
    )
}

@Composable
private fun MembersAgentSkillsSection(detail: AgentDetailSnapshot?) {
    val groups = detail?.skills?.groups.orEmpty()
    val totalSkills = groups.sumOf { group -> group.paths.sumOf { path -> path.skills.size } }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        MembersDetailSectionDivider()
        MembersDetailSectionTitle("SKILLS ($totalSkills)")
        if (groups.isEmpty()) {
            Text(
                text = "No skills installed",
                style = SlockType.Body.copy(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontStyle = FontStyle.Italic
                ),
                color = SlockColor.TextMutedGray
            )
        } else {
            groups.forEach { group ->
                MembersAgentSkillSubSection(group)
            }
        }
    }
}

@Composable
private fun MembersAgentSkillSubSection(group: AgentSkillGroup) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SlockLucideIcon(
                name = if (group.title.equals("workspace", ignoreCase = true)) {
                    SlockLucideIconName.FolderOpen
                } else {
                    SlockLucideIconName.Globe
                },
                modifier = Modifier.size(13.dp),
                color = SlockColor.TextMutedGrayArgb
            )
            Text(
                text = group.title,
                style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = SlockColor.TextMutedGray
            )
            Text(
                text = "(${group.paths.sumOf { path -> path.skills.size }})",
                style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = SlockColor.TextMutedGray.copy(alpha = 0.6f)
            )
        }
        if (group.paths.isEmpty()) {
            Text(
                text = if (group.title.equals("workspace", ignoreCase = true)) {
                    "No skills in this agent's workspace"
                } else {
                    "No global skills installed on this computer"
                },
                style = SlockType.Caption.copy(
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontStyle = FontStyle.Italic
                ),
                color = SlockColor.TextMutedGray.copy(alpha = 0.7f)
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
            ) {
                group.paths.forEach { pathGroup ->
                    MembersAgentSkillPathGroup(pathGroup)
                }
            }
        }
    }
}

@Composable
private fun MembersAgentSkillPathGroup(pathGroup: AgentSkillPathGroup) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = pathGroup.path,
                style = SlockType.Input.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = SlockColor.TextMutedGray.copy(alpha = 0.8f),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "(${pathGroup.skills.size})",
                style = SlockType.Input.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = SlockColor.TextMutedGray.copy(alpha = 0.6f)
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            pathGroup.skills.forEach { skill ->
                MembersAgentSkillCard(skill)
            }
        }
    }
}

@Composable
private fun MembersAgentSkillCard(skill: AgentSkillSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Default, SlockColor.BorderBlack.copy(alpha = 0.3f))
            .background(SlockColor.SurfaceWhite)
            .padding(horizontal = SlockSpacing.Md, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Text(
            text = skill.name,
            style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp)
                .withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        skill.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            Text(
                text = detail,
                style = SlockType.Body.copy(fontSize = 12.sp, lineHeight = 17.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MembersAgentCreatedAgentsSection(
    detail: AgentDetailSnapshot?,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver
) {
    val createdAgents = detail?.summary?.createdAgents.orEmpty()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        MembersDetailSectionDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            MembersDetailSectionTitle("CREATED AGENTS", modifier = Modifier.weight(1f))
            Text(
                text = createdAgents.size.toString(),
                style = SlockType.Input.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = SlockColor.TextMutedGray.copy(alpha = 0.7f)
            )
        }
        if (createdAgents.isEmpty()) {
            Text(
                text = "No created agents",
                style = SlockType.Body.copy(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontStyle = FontStyle.Italic
                ),
                color = SlockColor.TextMutedGray.copy(alpha = 0.7f)
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                createdAgents.forEach { agent ->
                    MembersAgentCreatedAgentRow(agent, agentStatusResolver, memberAvatarResolver)
                }
            }
        }
    }
}

@Composable
private fun MembersAgentCreatedAgentRow(
    agent: AgentCreatedSummary,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver
) {
    val profile = remember(agent) { agent.toMemberProfile() }
    val statusDisplayState = rememberSlockAgentStatusIndicatorState(agentStatusResolver, profile.id.value)
        ?: profile.toSlockAgentStatusIndicatorState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Default, SlockColor.BorderBlack.copy(alpha = 0.3f))
            .background(SlockColor.SurfaceWhite)
            .padding(horizontal = 12.dp, vertical = SlockSpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MembersAvatar(
            member = profile,
            avatarSize = 32.dp,
            statusSize = 0.dp,
            memberAvatarResolver = memberAvatarResolver,
            showStatusOverlay = false
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = profile.displayName,
                style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp)
                    .withSlockFontWeight(FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = agent.runtime?.takeIf { it.isNotBlank() }?.let { getRuntimeDisplayName(it) }
                    ?: "Agent",
                style = SlockType.Input.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        SlockStatusIndicator(
            color = statusDisplayState.color,
            size = 10.dp,
            pulsing = statusDisplayState.pulsing
        )
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
        val now = defaultNowMillis()
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
    onOpenMessageRef: (String) -> Unit,
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
                MembersAgentReminderCard(reminder, timeContext, onOpenMessageRef)
            }
        }
    }
}

@Composable
private fun MembersAgentReminderCard(
    reminder: AgentReminderSummary,
    timeContext: SlockTimeContext,
    onOpenMessageRef: (String) -> Unit
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
            val permalink = reminder.messagePermalink?.takeIf { it.isNotBlank() }
            MembersAgentReminderChip(
                iconName = SlockLucideIconName.Link2,
                text = messageRef,
                background = SlockColor.SurfaceWhite,
                textColor =
                    if (permalink != null) {
                        SlockColor.TextBlack
                    } else {
                        SlockColor.TextBlack.copy(alpha = 0.7f)
                    },
                onClick = permalink?.let { link -> { onOpenMessageRef(link) } }
            )
        }
    }
}

@Composable
private fun MembersAgentReminderChip(
    iconName: SlockLucideIconName,
    text: String,
    background: Color,
    textColor: Color,
    onClick: (() -> Unit)? = null
) {
    val interactionModifier =
        if (onClick != null) {
            Modifier.clickable(onClick = onClick)
        } else {
            Modifier
        }
    Row(
        modifier = interactionModifier
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
private fun MembersAgentAppsTab(
    detail: AgentDetailSnapshot?,
    timeContext: SlockTimeContext,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    MembersDetailSection(title = "CONNECTED APPS", modifier = modifier) {
        Text(
            text = "Apps this agent has logged into with Raft Agent Login",
            style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = SlockColor.TextMutedGray
        )
        val appLogins = detail?.appLogins
        when (appLogins?.loadState) {
            AgentAppLoginLoadState.NoPermission ->
                MembersDetailField(
                    "Apps",
                    appLogins.errorCode?.takeIf { it.isNotBlank() }
                        ?: "Only server owners and admins can view agent integrations."
                )
            AgentAppLoginLoadState.Error ->
                MembersDetailField(
                    "Apps",
                    "Failed to load connected apps: ${appLogins.errorCode ?: "unknown_error"}"
                )
            else -> {
                val activeItems = appLogins?.items.orEmpty()
                    .filter { it.type == AgentAppLoginType.Active && it.revokedAt.isNullOrBlank() }
                if (activeItems.isEmpty()) {
                    MembersDetailField("Apps", "No connected apps yet.")
                } else {
                    activeItems.forEach { item ->
                        MembersAgentAppLoginCard(
                            item = item,
                            timeContext = timeContext,
                            onOpenUrl = onOpenUrl
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MembersAgentAppLoginCard(
    item: AgentAppLoginItem,
    timeContext: SlockTimeContext,
    onOpenUrl: (String) -> Unit
) {
    SlockBrutalCard(
        shadowOffset = 3.dp,
        contentPadding = SlockSpacing.Md,
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = item.clientName,
                    style = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                item.createdAt?.let {
                    Text(
                        text = "Connected ${it.membersActivityDisplayTime(timeContext)}",
                        style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                        color = SlockColor.TextMutedGray
                    )
                }
            }
            MembersAgentAppLoginStatusBadge("Active")
        }
        item.clientDescription?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = SlockType.Body.copy(fontSize = 12.sp, lineHeight = 17.sp),
                color = SlockColor.TextBlack
            )
        }
        item.clientAgentManifestUrl?.takeIf { it.isNotBlank() }?.let {
            MembersDetailField("Agent manifest", it)
        }
        if (item.scopes.isNotEmpty()) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
            ) {
                item.scopes.forEach { scope ->
                    MembersAgentAppLoginScopeChip(scope)
                }
            }
        }
        item.clientHomepageUrl?.takeIf { it.isNotBlank() }?.let { homepageUrl ->
            SlockBrutalActionButton(
                label = "View service",
                leadingIcon = SlockLucideIconName.ExternalLink,
                enabled = true,
                backgroundColor = SlockColor.SurfaceWhite,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpenUrl(homepageUrl) }
            )
        }
    }
}

@Composable
private fun MembersAgentAppLoginStatusBadge(label: String) {
    Text(
        text = label,
        style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
        color = SlockColor.TextBlack,
        modifier =
            Modifier
                .background(SlockColor.AccentLime.copy(alpha = 0.32f))
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(horizontal = SlockSpacing.Xs, vertical = 2.dp),
        maxLines = 1
    )
}

@Composable
private fun MembersAgentAppLoginScopeChip(scope: String) {
    Text(
        text = scope,
        style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
        color = SlockColor.TextBlack,
        modifier =
            Modifier
                .background(SlockColor.EmptyStateGray)
                .border(SlockStroke.Thin, SlockColor.BorderBlack.copy(alpha = 0.36f))
                .padding(horizontal = SlockSpacing.Xs, vertical = 2.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun MembersAgentActivityTab(
    detail: AgentDetailSnapshot?,
    diagnosticActionState: MembersAgentDiagnosticActionState?,
    onCopyDiagnostics: () -> Unit,
    timeContext: SlockTimeContext,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        MembersDetailSectionDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MembersPageInset,
                    vertical = SlockSpacing.Xs
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            MembersDetailSectionTitle("ACTIVITY DIAGNOSTICS", modifier = Modifier.weight(1f))
            SlockBrutalActionButton(
                label = diagnosticActionState?.takeIf { it.actionKey == "copy" }?.successLabel ?: "Copy info",
                leadingIcon = SlockLucideIconName.Clipboard,
                enabled = diagnosticActionState?.inProgress != true,
                minHeight = 32.dp,
                shadowOffset = 2.dp,
                textStyle = SlockType.Button.copy(fontSize = 11.sp, lineHeight = 14.sp),
                modifier = Modifier.width(116.dp),
                onClick = onCopyDiagnostics
            )
        }
        MembersDetailSectionDivider()
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
                    .weight(1f)
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
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
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
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = activity.timestamp.membersActivityDisplayTime(timeContext),
            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.requiredWidth(MembersAgentActivityTimeColumnWidth)
        )
        Box(
            modifier = Modifier.requiredWidth(MembersAgentActivityIndicatorLaneWidth),
            contentAlignment = Alignment.TopCenter
        ) {
            SlockStatusIndicator(
                color = (activity.statusActivity ?: activity.type).membersActivityAccentColor(),
                size = 9.dp,
                pulsing = (activity.statusActivity ?: activity.type).membersActivityPulsing(),
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        Column(
            modifier = Modifier
                .padding(start = MembersAgentActivityContentGap)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = activity.title,
                style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp)
                    .withSlockFontWeight(FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
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

// #89 React parity: agent scope grant list + per-group row definitions.
// Mirrors `packages/shared/src/agentScopes.ts` AGENT_SCOPE_GROUPS. Kept local
// (private to MembersPage.kt) for now — owner pending whether to lift to
// shared model module if other surfaces need it. Two sections
// ("notifications" / "cli") group 14 grantable scopes into 8 resources;
// each row carries the scope token, a short verbLabel and a one-sentence
// description that mirrors the React `AgentScopeGroup.scopes[]` shape.
private data class MembersAgentScopeRow(
    val scope: String,
    val verbLabel: String,
    val description: String
)

private data class MembersAgentScopeGroup(
    val resource: String,
    val section: String,
    val label: String,
    val description: String,
    val scopes: List<MembersAgentScopeRow>
)

private val membersAgentScopeGroups: List<MembersAgentScopeGroup> = listOf(
    MembersAgentScopeGroup(
        resource = "inbox",
        section = "notifications",
        label = "Inbox",
        description = "Control whether new activity can be delivered to this agent and wake it up.",
        scopes = listOf(
            MembersAgentScopeRow(
                scope = "inbox:receive",
                verbLabel = "Receive notifications",
                description = "Receive new inbox events and wake for activity from other people and agents."
            )
        )
    ),
    MembersAgentScopeGroup(
        resource = "server",
        section = "cli",
        label = "Server",
        description = "Discover channels, members, and other agents in this server.",
        scopes = listOf(
            MembersAgentScopeRow(
                scope = "server:read",
                verbLabel = "Read",
                description = "List channels, members, and agents (slock server info)."
            )
        )
    ),
    MembersAgentScopeGroup(
        resource = "channel",
        section = "cli",
        label = "Channels",
        description = "View channel rosters, join visible public channels, and leave channels the agent is no longer using.",
        scopes = listOf(
            MembersAgentScopeRow("channel:read", "Read", "View channel membership and metadata."),
            MembersAgentScopeRow("channel:join", "Join", "Join visible public channels."),
            MembersAgentScopeRow("channel:leave", "Leave", "Leave channels the agent is no longer using.")
        )
    ),
    MembersAgentScopeGroup(
        resource = "thread",
        section = "cli",
        label = "Threads",
        description = "Follow and unfollow thread updates.",
        scopes = listOf(
            MembersAgentScopeRow("thread:unfollow", "Unfollow", "Unfollow a thread to stop receiving updates.")
        )
    ),
    MembersAgentScopeGroup(
        resource = "message",
        section = "cli",
        label = "Messages",
        description = "Read, search, and send messages across channels, threads, and DMs.",
        scopes = listOf(
            MembersAgentScopeRow("message:read", "Read", "Read / search / resolve messages."),
            MembersAgentScopeRow("message:send", "Send", "Send messages (DM / channel / thread).")
        )
    ),
    MembersAgentScopeGroup(
        resource = "attachment",
        section = "cli",
        label = "Attachments",
        description = "Upload and view file attachments in conversation.",
        scopes = listOf(
            MembersAgentScopeRow("attachment:upload", "Upload", "Upload files to conversation."),
            MembersAgentScopeRow("attachment:view", "View", "View uploaded files in conversation.")
        )
    ),
    MembersAgentScopeGroup(
        resource = "task",
        section = "cli",
        label = "Tasks",
        description = "List, create, and update tasks on the board.",
        scopes = listOf(
            MembersAgentScopeRow("task:read", "Read", "List tasks."),
            MembersAgentScopeRow("task:write", "Write", "Create, claim, unclaim, update tasks.")
        )
    ),
    MembersAgentScopeGroup(
        resource = "knowledge",
        section = "cli",
        label = "Knowledge",
        description = "Read agent manual / knowledge base entries.",
        scopes = listOf(
            MembersAgentScopeRow("knowledge:read", "Read", "Read slock knowledge base entries.")
        )
    ),
    MembersAgentScopeGroup(
        resource = "action",
        section = "cli",
        label = "Actions",
        description = "Prepare action cards for the user to commit.",
        scopes = listOf(
            MembersAgentScopeRow("action:prepare", "Prepare", "Prepare an action card; the human commits the action.")
        )
    )
)

@Composable
private fun MembersAgentPermissionsTab(
    member: MemberProfile,
    detail: AgentDetailSnapshot?,
    canManage: Boolean,
    agentsApiClient: build.raft.app.data.SlockAgentsApiClient?,
    initialScopeSet: AgentScopeSet? = null,
    onUnavailableAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // #89 React parity: mirror `AgentScopesPanel.tsx` state machine.
    // Local state only (per owner 06-25 04:08 — `SharedAgentDetailStore`
    // integration deferred to AD4 #56 lane).
    var serverSet by remember(member.id, initialScopeSet) { mutableStateOf<AgentScopeSet?>(initialScopeSet) }
    var draft by remember(member.id, initialScopeSet) { mutableStateOf(initialScopeSet?.granted?.toSet()) }
    var loading by remember(member.id, initialScopeSet) { mutableStateOf(initialScopeSet == null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun loadScopes() {
        if (agentsApiClient == null) {
            error = "Agent API not available"
            loading = false
            return
        }
        when (val result = agentsApiClient.agentScopes(member.id)) {
            is DataResult.Content -> {
                serverSet = result.data
                draft = result.data.granted.toSet()
                error = null
            }
            is DataResult.Error -> {
                error = "Failed to load agent permissions: ${result.error.code}"
            }
            is DataResult.Empty -> error = "No permissions data"
            is DataResult.NoPermission -> error = "No permission to view permissions."
        }
        loading = false
    }

    LaunchedEffect(member.id) {
        if (initialScopeSet == null) {
            loadScopes()
        }
    }

    fun toggle(scope: String) {
        val current = draft ?: return
        val next = if (scope in current) current - scope else current + scope
        draft = next
    }

    suspend fun save() {
        val current = draft ?: return
        val currentServer = serverSet ?: return
        if (agentsApiClient == null) return
        saving = true
        error = null
        when (val result = agentsApiClient.updateAgentScopes(member.id, current.toList())) {
            is DataResult.Content -> {
                serverSet = result.data
                draft = result.data.granted.toSet()
            }
            is DataResult.Error -> error = "Failed to save: ${result.error.code}"
            is DataResult.Empty -> error = "Empty response"
            is DataResult.NoPermission -> error = "No permission to save permissions."
        }
        saving = false
    }

    suspend fun resetToDefault() {
        if (agentsApiClient == null) return
        saving = true
        error = null
        when (val result = agentsApiClient.resetAgentScopes(member.id)) {
            is DataResult.Content -> {
                serverSet = result.data
                draft = result.data.granted.toSet()
            }
            is DataResult.Error -> error = "Failed to reset: ${result.error.code}"
            is DataResult.Empty -> error = "Empty response"
            is DataResult.NoPermission -> error = "No permission to reset permissions."
        }
        saving = false
    }

    fun discard() {
        val currentServer = serverSet ?: return
        draft = currentServer.granted.toSet()
    }

    val currentServer = serverSet
    val currentDraft = draft
    val isDirty = currentServer != null && currentDraft != null && !currentDraft.toSet().equals(currentServer.granted.toSet())

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
        if (error != null && currentServer == null) {
            // Initial-load error: brutal warning card + Retry button. Return
            // early after rendering so the rest of the panel is not drawn
            // over the error.
            ErrorBanner(
                text = error ?: "",
                action = "Retry",
                onAction = { scope.launch { loadScopes() } }
            )
            return
        }
        if (loading || currentServer == null || currentDraft == null) {
            // Loading state: text "Loading permissions…"
            Text(
                text = "Loading permissions…",
                style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = SlockColor.TextMutedGray,
                modifier = Modifier.padding(SlockSpacing.Sm)
            )
            return
        }

        if (error != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .background(SlockColor.SurfaceCream)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SlockLucideIcon(
                    SlockLucideIconName.TriangleAlert,
                    modifier = Modifier.size(16.dp),
                    color = "#FFD32F2F"
                )
                Text(
                    text = error ?: "",
                    style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = SlockColor.TextBlack,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Mode banner (custom / default)
        val isCustom = currentServer.mode == "custom"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .background(SlockColor.SurfaceWhite)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (isCustom) "Customized permissions" else "Using default permissions",
                    style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = SlockColor.TextBlack
                )
                Text(
                    text = if (isCustom) "New capabilities stay off until you turn them on for this agent." else "New default capabilities are enabled automatically for this agent.",
                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = SlockColor.TextMutedGray
                )
            }
            if (isCustom) {
                SlockBrutalActionButton(
                    label = "Use default",
                    onClick = {
                        scope.launch { resetToDefault() }
                    },
                    enabled = !saving,
                    minWidth = 88.dp,
                    minHeight = 32.dp
                )
            }
        }

        // Notifications section
        val notificationGroups = membersAgentScopeGroups.filter { it.section == "notifications" }
        val cliGroups = membersAgentScopeGroups.filter { it.section == "cli" }

        PermissionsSection(title = "Notifications / inbox", description = "Controls what can wake the agent or enter its inbox.", groups = notificationGroups, draft = currentDraft, canManage = canManage, onToggle = ::toggle)
        PermissionsSection(title = "Raft CLI permissions", description = "Controls what the agent can actively do through slock CLI commands.", groups = cliGroups, draft = currentDraft, canManage = canManage, onToggle = ::toggle)

        // Action bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SlockBrutalActionButton(
                label = if (saving) "Saving…" else "Save permissions",
                onClick = {
                    scope.launch { save() }
                },
                enabled = !saving && isDirty,
                backgroundColor = SlockColor.AccentPink,
                minWidth = 132.dp
            )
            SlockBrutalActionButton(
                label = "Discard",
                onClick = ::discard,
                enabled = !saving && isDirty,
                minWidth = 88.dp
            )
            Text(
                text = "rev ${currentServer.revision}",
                style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp),
                color = SlockColor.TextMutedGray.copy(alpha = 0.4f),
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@Composable
private fun PermissionsSection(
    title: String,
    description: String,
    groups: List<MembersAgentScopeGroup>,
    draft: Set<String>,
    canManage: Boolean,
    onToggle: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = SlockColor.TextBlack
            )
            Text(
                text = description,
                style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = SlockColor.TextMutedGray
            )
        }
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEach { group ->
                PermissionsGroupBox(group = group, draft = draft, canManage = canManage, onToggle = onToggle)
            }
        }
    }
}

@Composable
private fun PermissionsGroupBox(
    group: MembersAgentScopeGroup,
    draft: Set<String>,
    canManage: Boolean,
    onToggle: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .background(SlockColor.SurfaceWhite)
    ) {
        Column(modifier = Modifier.fillMaxWidth().border(width = 0.dp, color = Color.Transparent).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = group.label,
                style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 13.sp, lineHeight = 16.sp),
                color = SlockColor.TextBlack
            )
            Text(
                text = group.description,
                style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = SlockColor.TextMutedGray.copy(alpha = 0.5f)
            )
        }
        // Hairline divider between header and rows.
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Thin).background(SlockColor.BorderBlack.copy(alpha = 0.1f)))
        group.scopes.forEachIndexed { index, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 0.dp, color = Color.Transparent)
                    .let {
                        if (index < group.scopes.lastIndex) {
                            it.border(width = 0.dp, color = Color.Transparent)
                        } else it
                    }
                    .clickable(enabled = canManage) { onToggle(row.scope) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SlockCheckbox(
                    checked = row.scope in draft,
                    onCheckedChange = { onToggle(row.scope) },
                    enabled = canManage,
                    size = 14.dp
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = row.verbLabel,
                            style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 16.sp),
                            color = SlockColor.TextBlack
                        )
                        Text(
                            text = row.scope,
                            style = SlockType.Caption.copy(fontSize = 9.sp, lineHeight = 12.sp),
                            color = SlockColor.TextMutedGray.copy(alpha = 0.5f)
                        )
                    }
                    Text(
                        text = row.description,
                        style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                        color = SlockColor.TextMutedGray.copy(alpha = 0.6f)
                    )
                }
            }
            if (index < group.scopes.lastIndex) {
                Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Thin).background(SlockColor.BorderBlack.copy(alpha = 0.1f)))
            }
        }
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
private fun MembersProfilePencilButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(18.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        SlockLucideIcon(
            SlockLucideIconName.Pencil,
            modifier = Modifier.size(14.dp),
            color = SlockColor.TextMutedGrayArgb
        )
    }
}

@Composable
private fun MembersEditableSectionTitleRow(
    title: String,
    showEdit: Boolean,
    onEditClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        MembersDetailSectionTitle(title)
        if (showEdit) {
            MembersProfilePencilButton(onClick = onEditClick)
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun MembersDetailSectionDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SlockStroke.Thin)
            .background(SlockColor.BorderBlack.copy(alpha = 0.1f))
    )
}

@Composable
private fun MembersDetailSectionTitle(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title.uppercase(),
        style = SlockType.Caption.copy(fontSize = 13.sp, lineHeight = 18.sp)
            .withSlockFontWeight(FontWeight.Bold),
        color = SlockColor.TextMutedGray,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
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
        MembersDetailSectionDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            MembersDetailSectionTitle(title, modifier = Modifier.weight(1f))
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
        subtitle = null,
        iconName = SlockLucideIconName.Users,
        refreshing = refreshing
    )
}

@Composable
private fun MembersListRow(
    member: MemberProfile,
    modifier: Modifier = Modifier,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    onClick: () -> Unit = {}
) {
    val agentStatusDisplayState =
        if (member.kind == MemberKind.Agent) {
            rememberSlockAgentStatusIndicatorState(agentStatusResolver, member.id.value)
        } else {
            null
        }
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
            showStatusOverlay = false,
            statusIndicatorState = agentStatusDisplayState,
            memberAvatarResolver = memberAvatarResolver
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
                color = agentStatusDisplayState?.color ?: SlockColor.EmptyStateGray,
                size = MemberStatusDotSize,
                pulsing = agentStatusDisplayState?.pulsing == true
            )
        }
    }
}

@Composable
private fun MembersAvatar(
    member: MemberProfile,
    avatarSize: Dp = MemberAvatarSize,
    statusSize: Dp = MemberStatusDotSize,
    statusDisplayState: MembersAgentStatusDisplayState = member.membersAgentStatusDisplayState(),
    statusIndicatorState: SlockAgentStatusIndicatorState? = null,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    showStatusOverlay: Boolean = true,
    modifier: Modifier = Modifier
) {
    val resolvedAvatarWireValue = rememberSlockMemberAvatarWireValue(memberAvatarResolver, member.id.value)
    val fallbackAvatarWireValue = remember(member.avatar, member.avatarUrl, member.gravatarHash, member.kind, member.id) {
        member.avatarWireValueForAvatarSlot()
    }
    val avatarWireValue = resolvedAvatarWireValue ?: fallbackAvatarWireValue
    SlockAvatarWithStatus(
        modifier = modifier,
        statusColor =
            if (showStatusOverlay && member.kind == MemberKind.Agent) {
                statusIndicatorState?.color ?: statusDisplayState.color
            } else {
                null
            },
        avatarSize = avatarSize,
        statusSize = statusSize,
        statusPulsing = statusIndicatorState?.pulsing ?: statusDisplayState.pulsing
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
    slockAgentStatusIndicatorState(this, presence).color

private fun AgentStatus?.membersStatusPulsing(): Boolean =
    slockAgentStatusIndicatorState(this, this?.presence ?: Presence.Offline).pulsing

internal data class MembersAgentStatusDisplayState(
    val status: AgentStatus?,
    val color: Color,
    val pulsing: Boolean
)

internal fun MemberProfile.membersAgentStatusDisplayState(
    summaryStatus: String? = null,
    summaryActivity: String? = null
): MembersAgentStatusDisplayState {
    val resolvedStatus = membersResolvedAgentStatus(summaryStatus, summaryActivity)
    return MembersAgentStatusDisplayState(
        status = resolvedStatus,
        color = resolvedStatus.membersStatusColor(presence),
        pulsing = resolvedStatus.membersStatusPulsing()
    )
}

private fun MemberProfile.toSlockAgentStatusIndicatorState(
    summaryStatus: String? = null,
    summaryActivity: String? = null
): SlockAgentStatusIndicatorState {
    val display = membersAgentStatusDisplayState(
        summaryStatus = summaryStatus,
        summaryActivity = summaryActivity
    )
    return SlockAgentStatusIndicatorState(
        status = display.status,
        color = display.color,
        pulsing = display.pulsing
    )
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

private fun MemberProfile.membersHumanRoleChipColor(): Color =
    when (roleDescription?.trim()?.lowercase()) {
        "owner" -> SlockColor.AccentOrange
        "admin" -> SlockColor.AccentPink
        "member" -> SlockColor.AccentLavender
        else -> if (membershipStatus == "removed") SlockColor.EmptyStateGray else SlockColor.AccentLavender
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

private fun MemberProfile.membersAgentRuntimeErrorText(
    detail: AgentDetailSnapshot?,
    statusIndicatorState: SlockAgentStatusIndicatorState?
): String? {
    if (kind != MemberKind.Agent) return null
    val summary = detail?.summary
    val status = statusIndicatorState?.status ?: membersResolvedAgentStatus(summary?.status, summary?.activity)
    return if (status == AgentStatus.Error) {
        membersAgentActivityErrorText(summary?.activityDetail)
    } else {
        null
    }
}

internal fun membersAgentActivityErrorText(detail: String?): String {
    val displayDetail = membersAgentActivityErrorDisplayDetail(detail)
    return if (displayDetail.isBlank()) "Error" else "Error: $displayDetail"
}

internal fun membersAgentActivityErrorDisplayDetail(detail: String?): String {
    val trimmed = detail?.trim().orEmpty()
    if (trimmed.isBlank()) return ""
    val parsed =
        if (trimmed.firstOrNull() in setOf('{', '[', '"')) {
            runCatching { MembersAgentActivityErrorJson.parseToJsonElement(trimmed) }.getOrNull()
        } else {
            null
        }
    return parsed?.membersAgentActivityErrorMessage()?.takeIf { it.isNotBlank() } ?: trimmed
}

private val MembersAgentActivityErrorJson = Json { ignoreUnknownKeys = true }

private fun JsonElement.membersAgentActivityErrorMessage(): String? =
    when (this) {
        is JsonObject -> membersAgentActivityErrorMessage()
        is JsonArray -> firstNotNullOfOrNull { it.membersAgentActivityErrorMessage() }
        is JsonPrimitive -> contentOrNull?.trim()?.takeIf { it.isNotBlank() }
    }

private fun JsonObject.membersAgentActivityErrorMessage(): String? {
    listOf("message", "error_description").forEach { key ->
        this[key].membersAgentActivityStringOrNull()?.let { return it }
    }
    val error = this["error"]
    error.membersAgentActivityStringOrNull()?.let { return it }
    error?.membersAgentActivityErrorMessage()?.let { return it }
    (this["errors"] as? JsonArray)
        ?.firstNotNullOfOrNull { it.membersAgentActivityErrorMessage() }
        ?.let { return it }
    return null
}

private fun JsonElement?.membersAgentActivityStringOrNull(): String? =
    (this as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotBlank() }

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

private fun AgentCreatorSummary.toMemberProfile(): MemberProfile? {
    if (id.isBlank() || name.isBlank()) return null
    val display = displayName?.takeIf { it.isNotBlank() } ?: name
    return MemberProfile(
        id = MemberId(id),
        displayName = display,
        handle = name,
        kind = if (type == "agent") MemberKind.Agent else MemberKind.Human,
        avatarUrl = avatarUrl,
        gravatarHash = gravatarHash,
        avatar = AvatarReference.fromWireValue(avatarUrl),
        avatarLabel = display.take(1).uppercase()
    )
}

private fun AgentCreatedSummary.toMemberProfile(): MemberProfile {
    val display = displayName?.takeIf { it.isNotBlank() } ?: name.ifBlank { id }
    val status = AgentStatus.fromWireValue(status)
    val avatarWireValue = avatarUrl?.takeIf { it.isNotBlank() }
    return MemberProfile(
        id = MemberId(id),
        displayName = display,
        handle = name.takeIf { it.isNotBlank() } ?: id,
        kind = MemberKind.Agent,
        avatarUrl = avatarWireValue,
        avatar = AvatarReference.fromWireValue(avatarWireValue),
        avatarLabel = display.take(1).uppercase(),
        presence = status?.presence ?: Presence.Offline,
        agentStatus = status,
        roleDescription = runtime
    )
}

private fun String?.toMembersRuntimeModeChipLabel(runtimeId: String?): String? {
    if (runtimeId == null || !supportsRuntimeFastMode(runtimeId)) return null
    return when (this?.trim()?.lowercase()) {
        "fast" -> "Fast"
        "default" -> "Default"
        else -> null
    }
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
        description = summary?.description?.takeIf { it.isNotBlank() },
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

private fun AgentDetailSnapshot.withMemberProfileProjection(member: MemberProfile): AgentDetailSnapshot {
    val fallbackName = member.handle.ifBlank { member.displayName.ifBlank { member.id.value } }
    val nextSummary =
        (summary ?: AgentDetailSummary(id = member.id.value, name = fallbackName)).copy(
            name = fallbackName,
            displayName = member.displayName.ifBlank { fallbackName },
            description = member.description?.takeIf { it.isNotBlank() },
            status = member.agentStatus?.label ?: summary?.status,
            runtime = member.roleDescription ?: summary?.runtime,
            avatarUrl = member.avatar?.wireValue ?: member.avatarUrl ?: summary?.avatarUrl
        )
    return copy(summary = nextSummary)
}

private fun AgentDetailSnapshot.withMemberDetailLifecycleResult(
    action: MembersAgentLifecycleAction
): AgentDetailSnapshot {
    val currentSummary = summary ?: AgentDetailSummary(id = agentId.value, name = agentId.value)
    val nextSummary =
        when (action) {
            MembersAgentLifecycleAction.Start,
            MembersAgentLifecycleAction.Restart ->
                currentSummary.copy(status = AgentStatus.Working.label, activity = AgentStatus.Working.label)
            MembersAgentLifecycleAction.Stop ->
                currentSummary.copy(status = AgentStatus.Offline.label, activity = AgentStatus.Offline.label)
            MembersAgentLifecycleAction.UnassignMachine ->
                currentSummary.copy(machineId = null, machineName = null)
            MembersAgentLifecycleAction.Delete ->
                currentSummary.copy(deletedAt = currentSummary.deletedAt ?: "deleted")
            MembersAgentLifecycleAction.ResetSession,
            MembersAgentLifecycleAction.ResetFull ->
                currentSummary
        }
    return copy(summary = nextSummary)
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

private fun String.membersAgentCreatedDate(timeContext: SlockTimeContext): String {
    val trimmed = trim()
    if (trimmed.isEmpty()) return ""
    val epochMillis =
        parseSlockTimestampEpochMillisOrNull(trimmed, timeContext.localOffsetMinutes) ?: return trimmed
    val localMillis = epochMillis + timeContext.localOffsetMinutes.toLong() * MembersReminderMillisPerMinute
    val epochDay = membersReminderFloorDiv(localMillis, MembersReminderMillisPerDay)
    val date = membersReminderCivilDateFromEpochDay(epochDay)
    return "${MembersReminderMonthNames[date.month - 1]} ${date.day}, ${date.year}"
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
private fun ErrorBanner(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .background(SlockColor.SurfaceCream)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SlockLucideIcon(
            SlockLucideIconName.TriangleAlert,
            modifier = Modifier.size(16.dp),
            color = "#FFD32F2F"
        )
        Text(
            text = text,
            style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = SlockColor.TextBlack,
            modifier = Modifier.weight(1f)
        )
        if (action != null && onAction != null) {
            SlockBrutalActionButton(
                label = action,
                onClick = onAction,
                minWidth = 72.dp,
                minHeight = 32.dp
            )
        }
    }
}

@Composable
private fun SlockSpinnerIcon(
    size: Dp = 14.dp,
    color: String = SlockColor.TextBlackArgb
) {
    SlockLucideIcon(
        SlockLucideIconName.LoaderCircle,
        modifier = Modifier.size(size),
        color = color,
        spinning = true
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
