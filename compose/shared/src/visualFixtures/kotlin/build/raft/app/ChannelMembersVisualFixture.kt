package build.raft.app

import androidx.compose.runtime.*
import build.raft.app.base.BasePager
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.AgentStatus
import build.raft.app.model.Presence
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.app.compat.pager.Page
import com.tencent.tmm.kmmresource.resource.desc.StringDesc
import kotlinx.coroutines.flow.flowOf

/** Paired with packages/visual-testing/shared/memberParityFixture.json in botiverse/slock.
 * The production roster renderer owns all layout; this build-gated page only supplies state.
 */
@Page("compose_channel_members_parity_visual", supportInLocal = true)
internal class ChannelMembersParityVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        val variant = pageData.params.optString("variant", "roster")
        setContent {
            RaftThemeProvider(raftLightTheme) {
                // Match ThreadMembersPageScreen's production outer surface.
                Box(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
                    ChannelMembersParityContent(variant)
                }
            }
        }
    }
}

@Composable
private fun ChannelMembersParityContent(variant: String) {
    val previousLocale = remember { StringDesc.localeType }
    StringDesc.localeType = AppLocale.SimplifiedChinese.stringDescLocaleType
    DisposableEffect(Unit) { onDispose { StringDesc.localeType = previousLocale } }
    var members by remember { mutableStateOf(listOf(
        RaftThreadMemberItem("human-alex", "Alex", handle = "alex", effectiveChannelRole = "owner"),
        RaftThreadMemberItem("human-maya", "Maya", handle = "maya", description = "Product Designer"),
        RaftThreadMemberItem("1", "Grace", handle = "grace", kind = "agent", effectiveChannelRole = "admin"),
        RaftThreadMemberItem("2", "Josh", handle = "josh", kind = "agent"),
        RaftThreadMemberItem("3", "Agent", handle = "agent", kind = "agent", sourceServerId = "peer", sourceServerLabel = "Peer Server", canRemove = false),
    )) }
    val candidates = remember { listOf(RaftThreadMemberItem("candidate", "Quill", handle = "quill", kind = "agent")) +
        if (variant == "interaction") listOf(RaftThreadMemberItem("human-taylor", "Taylor", handle = "taylor")) else emptyList() }
    var submitStatus by remember { mutableStateOf("idle") }
    var submitAttempts by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf(TextFieldValue(if (variant == "search") "Grace" else "")) }
    var agentsExpanded by remember { mutableStateOf(variant != "collapsed") }
    var humansExpanded by remember { mutableStateOf(true) }
    var externalExpanded by remember { mutableStateOf(true) }
    var addVisible by remember { mutableStateOf(variant == "add" || variant == "interaction") }
    val resolver: RaftAgentStatusResolver = remember { { id -> flowOf(
        raftAgentStatusIndicatorState(
            status = if (id == "2") AgentStatus.Offline else AgentStatus.Online,
            presence = if (id == "2") Presence.Offline else Presence.Active,
            statusLabel = if (id == "2") "offline" else "online", external = false,
        )
    ) } }
    ThreadMembersPageContent(
        members = members, filteredMembers = filterThreadAddMemberCandidates(query.text, members),
        filteredExternal = emptyList(), externalMemberCount = 0, candidates = candidates,
        rosterQuery = query, onQueryChange = { query = it }, agentsExpanded = agentsExpanded,
        humansExpanded = humansExpanded, externalExpanded = externalExpanded,
        onAgentsExpandedChange = { agentsExpanded = it }, onHumansExpandedChange = { humansExpanded = it },
        onExternalExpandedChange = { externalExpanded = it }, addDialogVisible = addVisible,
        onAddVisibleChange = { addVisible = it }, addMemberStatus = submitStatus, addMemberErrorCode = "member_add_failed",
        removalGate = ThreadMemberRemovalGateState(), membersFailed = false, membersLoading = false,
        refreshing = false, canAddMembers = true, canCreateAgents = true, agentStatusResolver = resolver,
        memberAvatarResolver = EmptyRaftMemberAvatarResolver, onBack = {}, onRefresh = {},
        onOpenMember = {}, onRemoveMember = {}, onSubmit = { selected ->
            if (variant == "interaction") {
                submitAttempts += 1
                println("MEMBER_ADD_QA attempt=$submitAttempts selected=" + selected.map { "${it.kind}:${it.id}" }.sorted().joinToString(","))
                if (submitAttempts == 1) submitStatus = "failed"
                else {
                    members = members + selected
                    submitStatus = "idle"
                    addVisible = false
                }
            }
        },
    )
}
