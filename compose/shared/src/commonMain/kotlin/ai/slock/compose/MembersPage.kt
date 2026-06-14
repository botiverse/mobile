package ai.slock.compose

import ai.slock.compose.base.Utils
import ai.slock.compose.components.SlockBrutalActionButton
import ai.slock.compose.components.SlockBrutalIconButton
import ai.slock.compose.components.SlockBrutalTextField
import ai.slock.compose.data.AgentActivityEntry
import ai.slock.compose.data.AgentDetailSnapshot
import ai.slock.compose.data.AgentLifecycleActionResult
import ai.slock.compose.data.AgentResetMode
import ai.slock.compose.data.AgentWorkspaceChildrenSnapshot
import ai.slock.compose.data.AgentWorkspaceFilePreviewSnapshot
import ai.slock.compose.data.AgentWorkspaceTreeSnapshot
import ai.slock.compose.data.AgentProfileUpdateRequest
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.ScreenError
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
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.pager.Pager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

const val MEMBERS_PAGE = "compose_members"
const val MEMBERS_SMOKE_PAGE = "compose_members_smoke"
private const val MEMBERS_PAGE_DATA_EVENT = "slockPageData"

private val MembersPageInset = SlockSpacing.Md
private val MemberAvatarSize = 34.dp
private val MemberRowMinHeight = 48.dp
private val MemberStatusDotSize = 10.dp
private val MembersActionSize = 30.dp
private val MembersActionIconSize = 16.dp

internal enum class MembersAgentDetailTab(
    val label: String,
    val iconName: String,
    val privateSurface: Boolean = true
) {
    Profile("Profile", "bot", privateSurface = false),
    Permissions("Permissions", "shield-check"),
    Dms("Agent DMs", "message-square"),
    Reminders("Reminders", "bell-ring"),
    Workspace("Workspace", "folder-open"),
    Apps("Apps", "plug"),
    Activity("Activity", "activity")
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
    val iconName: String,
    val successLabel: String,
    val destructive: Boolean = false
) {
    Start("start", "Start", "play", "Started"),
    Stop("stop", "Stop", "square", "Stopped"),
    Restart("restart", "Restart", "rotate-cw", "Restarted"),
    ResetSession("reset_session", "Reset session", "refresh-cw", "Session reset"),
    ResetFull("reset_full", "Full reset", "eraser", "Full reset"),
    Delete("delete", "Delete Agent", "trash-2", "Deleted", destructive = true),
    UnassignMachine("assign_machine", "Unassign machine", "monitor-x", "Machine unassigned");

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

@Serializable
data class KuiklyMembersPayload(
    val instanceId: String = "",
    val title: String = "Members",
    val labels: KuiklyMembersLabels = KuiklyMembersLabels(),
    val members: List<MemberProfile> = emptyList(),
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

    MembersRouteContent(
        payload = payload,
        onAddHuman = { bridge.settingsEvent(payload.instanceId, "addHuman", null) },
        onOpenDirectMessage = { bridge.settingsEvent(payload.instanceId, "openMember", it.id.value) },
        onCopyMemberId = {
            bridge.copyToPasteboard(it.id.value)
            bridge.toast("Copied member id")
        }
    )
}

private fun String.toKuiklyMembersPayload(): KuiklyMembersPayload {
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
    agentDetailLoader: (suspend (MemberId) -> DataResult<AgentDetailSnapshot>)? = null,
    agentDetailRefreshKey: Any? = Unit,
    onUpdateAgentProfile: (suspend (AgentProfileUpdateRequest) -> DataResult<MemberProfile>)? = null,
    onAgentLifecycleAction: (suspend (MemberId, MembersAgentLifecycleAction) -> DataResult<AgentLifecycleActionResult>)? = null,
    agentWorkspaceTreeLoader: (suspend (MemberId) -> DataResult<AgentWorkspaceTreeSnapshot>)? = null,
    agentWorkspaceChildrenLoader: (suspend (MemberId, String) -> DataResult<AgentWorkspaceChildrenSnapshot>)? = null,
    agentWorkspaceFileLoader: (suspend (MemberId, String) -> DataResult<AgentWorkspaceFilePreviewSnapshot>)? = null,
    onCopyAgentDiagnostics: (String) -> Unit = {},
    onReportAgentIssue: (suspend (String) -> Boolean)? = null,
    canViewPrivateAgentSurfaces: (MemberProfile, AgentDetailSnapshot?) -> Boolean = { _, _ -> false },
    canManageAgent: (MemberProfile, AgentDetailSnapshot?) -> Boolean = { _, _ -> false },
    onUnavailableAction: (String) -> Unit = {}
) {
    var selectedMemberId by remember(payload.instanceId) { mutableStateOf<String?>(null) }
    val selectedMember = payload.members.firstOrNull { it.id.value == selectedMemberId }
    val timeContext = payload.toSlockTimeContext()
    var agentDetailState by remember(selectedMember?.id?.value) {
        mutableStateOf<MembersAgentDetailLoadState>(MembersAgentDetailLoadState.Idle)
    }
    LaunchedEffect(selectedMemberId, payload.members) {
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
            onUpdateAgentProfile = onUpdateAgentProfile,
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
            onOpenMember = { selectedMemberId = it.id.value }
        )
    }
}

@Composable
internal fun MembersListContent(
    payload: KuiklyMembersPayload,
    onAddHuman: () -> Unit,
    onOpenMember: (MemberProfile) -> Unit
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
                        SlockSectionHeaderIconAction("plus", "Add member", onAddHuman)
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
                        SlockSectionHeaderIconAction("plus", "Add member", onAddHuman)
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
            item { Spacer(Modifier.height(180.dp)) }
        }
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
    onUpdateAgentProfile: (suspend (AgentProfileUpdateRequest) -> DataResult<MemberProfile>)?,
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
    var managementExpanded by remember(member.id.value) { mutableStateOf(false) }
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        MembersDetailHeader(
            member = member,
            detail = detail,
            deleted = deleted,
            canManage = canManage,
            managementExpanded = managementExpanded,
            onBack = onBack,
            onOpenDirectMessage = onOpenDirectMessage,
            onCopyMemberId = onCopyMemberId,
            onToggleManagement = { managementExpanded = !managementExpanded }
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md),
            beyondBoundsItemCount = sharedListBeyondBoundsItemCount(12)
        ) {
            item { Spacer(Modifier.height(SlockSpacing.Sm)) }
            if (member.kind == MemberKind.Agent) {
                if (managementExpanded) {
                    item {
                        MembersAgentLifecycleSection(
                            member = member,
                            detail = detail,
                            canManage = canManage,
                            deleted = deleted,
                            actionState = actionState,
                            diagnosticActionState = diagnosticActionState,
                            pendingConfirmation = pendingConfirmation,
                            onRequestAction = { runLifecycleAction(it) },
                            onConfirmAction = { runLifecycleAction(it, confirmed = true) },
                            onCancelConfirmation = {
                                pendingConfirmation = null
                                actionState = null
                            },
                            onCopyDiagnostics = { copyDiagnostics() },
                            onReportIssue = onReportAgentIssue?.let { { reportIssue() } },
                            modifier = Modifier.padding(horizontal = MembersPageInset)
                        )
                    }
                }
                item {
                    MembersAgentTabBar(
                        tabs = visibleTabs,
                        selectedTab = selectedTab,
                        onSelectedTabChange = { selectedTab = it },
                        modifier = Modifier.padding(horizontal = MembersPageInset)
                    )
                }
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
}

private fun DataResult<AgentLifecycleActionResult>.membersLifecycleErrorCode(action: MembersAgentLifecycleAction): String? =
    when (this) {
        is DataResult.Content ->
            if (data.success) null else "agent_${action.actionKey}_failed"
        is DataResult.Empty -> reason.name
        is DataResult.Error -> error.code
        is DataResult.NoPermission -> blocker.code
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
    managementExpanded: Boolean,
    onBack: () -> Unit,
    onOpenDirectMessage: () -> Unit,
    onCopyMemberId: () -> Unit,
    onToggleManagement: () -> Unit
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
                iconName = "arrow-left",
                onClick = onBack,
                size = MembersActionSize,
                shadowOffset = 0.dp
            )
            MembersAvatar(
                member = member,
                avatarSize = 42.dp,
                statusSize = 10.dp,
                statusPulsing = member.membersResolvedAgentStatus(summary?.status, summary?.activity).membersStatusPulsing()
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
            MembersSmallActionButton(
                label = "Message",
                iconName = "message-square",
                enabled = member.kind != MemberKind.System && !deleted,
                modifier = Modifier.width(92.dp),
                onClick = onOpenDirectMessage
            )
            if (member.kind == MemberKind.Agent) {
                SlockBrutalIconButton(
                    iconName = if (managementExpanded) "chevron-up" else "more-horizontal",
                    onClick = onToggleManagement,
                    size = MembersActionSize,
                    shadowOffset = if (canManage) 0.dp else 1.dp
                )
            } else {
                SlockBrutalIconButton(
                    iconName = "copy",
                    onClick = onCopyMemberId,
                    size = MembersActionSize,
                    shadowOffset = 0.dp
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
            iconName = "message-square",
            enabled = member.kind != MemberKind.System && !deleted,
            onClick = onOpenDirectMessage,
            modifier = Modifier.weight(1f)
        )
        MembersDetailActionButton(
            label = "Copy ID",
            iconName = "copy",
            enabled = true,
            onClick = onCopyMemberId,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MembersAgentLifecycleSection(
    member: MemberProfile,
    detail: AgentDetailSnapshot?,
    canManage: Boolean,
    deleted: Boolean,
    actionState: MembersAgentActionState?,
    diagnosticActionState: MembersAgentDiagnosticActionState?,
    pendingConfirmation: MembersAgentLifecycleAction?,
    onRequestAction: (MembersAgentLifecycleAction) -> Unit,
    onConfirmAction: (MembersAgentLifecycleAction) -> Unit,
    onCancelConfirmation: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onReportIssue: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val summary = detail?.summary
    val status = member.membersResolvedAgentStatus(summary?.status, summary?.activity)
    val primaryAction = membersPrimaryLifecycleAction(status, deleted)
    val machineAssigned = summary?.machineId?.isNotBlank() == true
    val copyState = diagnosticActionState?.takeIf { it.actionKey == "copy" }
    val reportState = diagnosticActionState?.takeIf { it.actionKey == "report" }
    MembersDetailSection(title = "AGENT ACTIONS", modifier = modifier) {
        when {
            deleted -> MembersDetailField("Management", "Agent is deleted.")
            !canManage -> MembersDetailField("Management", "Management actions require permission.")
            primaryAction != null -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
                    MembersLifecycleActionButton(
                        action = primaryAction,
                        actionState = actionState,
                        enabled = actionState?.inProgress != true,
                        modifier = Modifier.weight(1f),
                        onClick = { onRequestAction(primaryAction) }
                    )
                    MembersLifecycleActionButton(
                        action = MembersAgentLifecycleAction.Restart,
                        actionState = actionState,
                        enabled = actionState?.inProgress != true,
                        modifier = Modifier.weight(1f),
                        onClick = { onRequestAction(MembersAgentLifecycleAction.Restart) }
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
                    MembersLifecycleActionButton(
                        action = MembersAgentLifecycleAction.ResetSession,
                        actionState = actionState,
                        enabled = actionState?.inProgress != true,
                        modifier = Modifier.weight(1f),
                        onClick = { onRequestAction(MembersAgentLifecycleAction.ResetSession) }
                    )
                    MembersLifecycleActionButton(
                        action = MembersAgentLifecycleAction.ResetFull,
                        actionState = actionState,
                        enabled = actionState?.inProgress != true,
                        modifier = Modifier.weight(1f),
                        onClick = { onRequestAction(MembersAgentLifecycleAction.ResetFull) }
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
                    MembersLifecycleActionButton(
                        action = MembersAgentLifecycleAction.Delete,
                        actionState = actionState,
                        enabled = actionState?.inProgress != true,
                        modifier = if (machineAssigned) Modifier.weight(1f) else Modifier.fillMaxWidth(),
                        onClick = { onRequestAction(MembersAgentLifecycleAction.Delete) }
                    )
                    if (machineAssigned) {
                        MembersLifecycleActionButton(
                            action = MembersAgentLifecycleAction.UnassignMachine,
                            actionState = actionState,
                            enabled = actionState?.inProgress != true,
                            modifier = Modifier.weight(1f),
                            onClick = { onRequestAction(MembersAgentLifecycleAction.UnassignMachine) }
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
                    MembersSmallActionButton(
                        label = copyState?.successLabel ?: "Copy diagnostics",
                        iconName = "clipboard",
                        enabled = diagnosticActionState?.inProgress != true,
                        modifier = Modifier.weight(1f),
                        onClick = onCopyDiagnostics
                    )
                    onReportIssue?.let { reportIssue ->
                        MembersSmallActionButton(
                            label =
                                when {
                                    reportState?.inProgress == true -> "Reporting..."
                                    reportState?.errorCode != null -> "Report failed"
                                    reportState?.successLabel != null -> reportState.successLabel
                                    else -> "Report issue"
                                },
                            iconName = if (reportState?.inProgress == true) "loader" else "message-square-plus",
                            enabled = diagnosticActionState?.inProgress != true,
                            modifier = Modifier.weight(1f),
                            onClick = reportIssue
                        )
                    }
                }
                reportState?.errorCode?.let { errorCode ->
                    MembersDetailField("Diagnostics", "Failed: $errorCode")
                }
                pendingConfirmation?.let { action ->
                    MembersLifecycleConfirmation(
                        action = action,
                        agentName = member.displayName,
                        busy = actionState?.inProgress == true,
                        onConfirm = { onConfirmAction(action) },
                        onCancel = onCancelConfirmation
                    )
                }
                actionState?.let { state ->
                    if (!state.inProgress) {
                        MembersDetailField(
                            "Result",
                            state.errorCode?.let { "Failed: $it" } ?: state.successLabel ?: "Done"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MembersLifecycleActionButton(
    action: MembersAgentLifecycleAction,
    actionState: MembersAgentActionState?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val current = actionState?.takeIf { it.action == action }
    val label =
        when {
            current?.inProgress == true -> "${action.label}..."
            current?.errorCode == null && current?.successLabel != null -> current.successLabel
            else -> action.label
        }
    SlockBrutalActionButton(
        label = label,
        leadingIcon = if (current?.inProgress == true) "loader" else action.iconName,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        backgroundColor = if (action.destructive) SlockColor.AccentRed else SlockColor.SurfaceWhite,
        minWidth = 0.dp,
        minHeight = 36.dp,
        shadowOffset = 1.dp,
        textStyle = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 13.sp).withSlockFontWeight(FontWeight.Bold)
    )
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
            MembersSmallActionButton(
                label = "Cancel",
                iconName = "x",
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = onCancel
            )
            MembersSmallActionButton(
                label = "Confirm",
                iconName = "check",
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
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        items(tabs.size, key = { "agent-tab-${tabs[it].name}" }) { index ->
            val tab = tabs[index]
            val selected = tab == selectedTab
            Row(
                modifier = Modifier
                    .width(if (tab == MembersAgentDetailTab.Dms) 112.dp else 96.dp)
                    .height(34.dp)
                    .background(if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .clickable { onSelectedTabChange(tab) }
                    .padding(horizontal = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SlockLucideIcon(tab.iconName, Modifier.size(13.dp))
                Text(
                    text = tab.label,
                    style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 13.sp)
                        .withSlockFontWeight(FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

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
                    leadingIcon = if (saving) "loader" else "check",
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
        MembersDetailSection(title = "AVATAR") {
            MembersDetailField("Current", member.avatarUrl ?: member.avatar?.wireValue ?: "Default")
            if (canManage && !deleted) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
                    MembersSmallActionButton("Robot", "bot", enabled = !saving, modifier = Modifier.weight(1f)) {
                        saveProfile(avatarUrl = "pixel:robot", includeAvatarUrl = true)
                    }
                    MembersSmallActionButton("Heart", "heart", enabled = !saving, modifier = Modifier.weight(1f)) {
                        saveProfile(avatarUrl = "pixel:heart", includeAvatarUrl = true)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
                    MembersSmallActionButton("Upload", "upload", enabled = !saving, modifier = Modifier.weight(1f)) {
                        onUnavailableAction("Avatar upload is not available yet.")
                    }
                    MembersSmallActionButton("Clear", "x", enabled = !saving, modifier = Modifier.weight(1f)) {
                        saveProfile(avatarUrl = null, includeAvatarUrl = true)
                    }
                }
            }
        }
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
                        onViewModeChange = onViewModeChange
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
        MembersSmallActionButton(
            label = "Refresh",
            iconName = "refresh-cw",
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
                SlockLucideIcon(if (expanded) "chevron-down" else "chevron-right", Modifier.size(13.dp))
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
    onViewModeChange: (MembersWorkspaceViewMode) -> Unit
) {
    val path = fileState.membersWorkspaceSelectedPath().orEmpty()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        MembersSmallActionButton(
            label = "Files",
            iconName = "arrow-left",
            enabled = true,
            modifier = Modifier.width(74.dp),
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
                onViewModeChange = onViewModeChange
            )
    }
}

@Composable
private fun MembersWorkspacePreviewContent(
    preview: SlockWorkspaceFilePreview,
    viewMode: MembersWorkspaceViewMode,
    onViewModeChange: (MembersWorkspaceViewMode) -> Unit
) {
    when (preview) {
        is SlockWorkspaceFilePreview.Text -> {
            if (preview.markdown) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
                    MembersSmallActionButton(
                        label = "Raw",
                        iconName = "file-code",
                        enabled = true,
                        modifier = Modifier.weight(1f),
                        onClick = { onViewModeChange(MembersWorkspaceViewMode.Raw) }
                    )
                    MembersSmallActionButton(
                        label = "Preview",
                        iconName = "eye",
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
                        textStyle = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 17.sp)
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
    MembersSmallActionButton(
        label = "Retry",
        iconName = "refresh-cw",
        enabled = true,
        modifier = Modifier.fillMaxWidth(),
        onClick = onRefresh
    )
}

@Composable
private fun MembersWorkspaceErrorState(message: String, onRefresh: () -> Unit) {
    MembersDetailField("State", message)
    MembersSmallActionButton(
        label = "Retry",
        iconName = "refresh-cw",
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
            MembersSmallActionButton(
                label = diagnosticActionState?.takeIf { it.actionKey == "copy" }?.successLabel ?: "Copy Diagnostic Info",
                iconName = "clipboard",
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
                }.collect { (isScrollInProgress, isAtBottom) ->
                    if (isScrollInProgress && !isAtBottom) {
                        allowBottomAnchor = false
                    } else if (isAtBottom) {
                        allowBottomAnchor = true
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
                    MembersAgentActivityRow(rows[index], timeContext)
                }
            }
        }
    }
}

@Composable
private fun MembersAgentActivityRow(
    activity: AgentActivityEntry,
    timeContext: SlockTimeContext
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
private fun MembersSmallActionButton(
    label: String,
    iconName: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    SlockBrutalActionButton(
        label = label,
        leadingIcon = iconName,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        backgroundColor = SlockColor.SurfaceWhite,
        minWidth = 0.dp,
        minHeight = 34.dp,
        shadowOffset = 1.dp,
        textStyle = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 13.sp).withSlockFontWeight(FontWeight.Bold)
    )
}

@Composable
private fun MemberKindBadge(kind: MemberKind) {
    Text(
        text = kind.name.uppercase(),
        style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp).withSlockFontWeight(FontWeight.Bold),
        color = SlockColor.TextBlack,
        maxLines = 1,
        modifier = Modifier
            .background(kind.membersAvatarColor)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .padding(horizontal = SlockSpacing.Xs, vertical = 2.dp)
    )
}

@Composable
private fun MembersDetailActionButton(
    label: String,
    iconName: String,
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
        backgroundColor = SlockColor.AccentPink,
        minWidth = 0.dp,
        minHeight = 42.dp,
        shadowOffset = 2.dp,
        textStyle = SlockType.Button.copy(fontSize = 12.sp, lineHeight = 16.sp)
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
        iconName = "users"
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
        MembersAvatar(member = member)
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
    }
}

@Composable
private fun MembersAvatar(
    member: MemberProfile,
    avatarSize: Dp = MemberAvatarSize,
    statusSize: Dp = MemberStatusDotSize,
    statusPulsing: Boolean = member.agentStatus.membersStatusPulsing()
) {
    val avatarWireValue = remember(member.avatar, member.avatarUrl, member.gravatarHash, member.kind, member.id) {
        member.avatarWireValueForAvatarSlot()
    }
    SlockAvatarWithStatus(
        statusColor =
            if (member.kind == MemberKind.Agent) {
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
