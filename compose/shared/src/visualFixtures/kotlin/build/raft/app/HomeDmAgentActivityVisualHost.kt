package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import build.raft.app.base.BasePager
import build.raft.app.compat.pager.Page
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.model.AgentStatus
import build.raft.app.model.Presence
import kotlinx.coroutines.flow.flowOf

/**
 * Home DM rows showing the agent's live activity (web `SidebarAgentMeta`, `rf-srow__meta--live`).
 * Each row pins one branch of [raftHomeDmAgentLiveActivity] plus the trailing-column rules:
 *  - `Tool Agent`: working with a detail -> the detail verbatim;
 *  - `Busy Agent`: working, no detail -> "Working…";
 *  - `Thinking Agent`: thinking (thought text withheld) -> "Thinking…";
 *  - `Long Activity Agent`: a long detail truncates at 58% of the row, the name keeps its room;
 *  - `Draft Agent`: draft mark, then the activity;
 *  - `Unread Agent`: working but unread -> the count alone (status pixel stays on the avatar);
 *  - `Idle Agent`: online -> nothing on the right;
 *  - `External Agent`: an external agent never shows live activity.
 */
@Page("compose_home_dm_agent_activity_visual", supportInLocal = true)
internal class ComposeHomeDmAgentActivityVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeDmAgentActivityVisualContent() }
    }
}

private data class HomeDmAgentActivityVisualRow(
    val id: String,
    val name: String,
    val status: SharedServerMemberStatus,
    val unreadCount: Int = 0,
    val hasDraft: Boolean = false,
    val description: String? = null
)

private fun visualAgentStatus(status: AgentStatus, label: String? = null, external: Boolean = false) =
    SharedServerMemberStatus(status = status, presence = status.presence, statusLabel = label, external = external)

private val homeDmAgentActivityVisualRows =
    listOf(
        HomeDmAgentActivityVisualRow("agent-tool", "Tool Agent", visualAgentStatus(AgentStatus.Working, "Tool finished")),
        HomeDmAgentActivityVisualRow("agent-busy", "Busy Agent", visualAgentStatus(AgentStatus.Working)),
        HomeDmAgentActivityVisualRow(
            "agent-thinking",
            "Thinking Agent",
            visualAgentStatus(AgentStatus.Thinking, "Let me look at the failing test before answering")
        ),
        HomeDmAgentActivityVisualRow(
            "agent-long",
            "Long Activity Agent",
            visualAgentStatus(AgentStatus.Working, "Running command: ./gradlew :shared:testDebugUnitTest --tests '*Home*'"),
            description = "Release reviewer"
        ),
        HomeDmAgentActivityVisualRow(
            "agent-draft",
            "Draft Agent",
            visualAgentStatus(AgentStatus.Working, "Reading file..."),
            hasDraft = true
        ),
        HomeDmAgentActivityVisualRow(
            "agent-unread",
            "Unread Agent",
            visualAgentStatus(AgentStatus.Working, "Tool finished"),
            unreadCount = 3
        ),
        HomeDmAgentActivityVisualRow("agent-idle", "Idle Agent", visualAgentStatus(AgentStatus.Online)),
        HomeDmAgentActivityVisualRow(
            "agent-external",
            "External Agent",
            visualAgentStatus(AgentStatus.Working, "Tool finished", external = true)
        )
    )

@Composable
private fun HomeDmAgentActivityVisualContent() {
    val resolver: RaftAgentStatusResolver =
        remember {
            val byId = homeDmAgentActivityVisualRows.associate { it.id to it.status.toRaftAgentStatusIndicatorState() }
            ({ memberId -> flowOf(byId[memberId]) })
        }
    RaftStructuralHomeShell(
        currentServerName = VisualFixtureData.Server.NAME,
        currentServerId = VisualFixtureData.Server.ID,
        servers =
            listOf(
                RaftServerSwitcherItem(
                    id = VisualFixtureData.Server.ID,
                    name = VisualFixtureData.Server.NAME,
                    slug = VisualFixtureData.Server.SLUG
                )
            ),
        channels = emptyList(),
        directMessages =
            homeDmAgentActivityVisualRows.map { row ->
                RaftHomeConversationItem(
                    id = "dm-${row.id}",
                    name = row.name,
                    kind = RaftThreadSurfaceKind.DirectMessage,
                    unreadCount = row.unreadCount,
                    description = row.description,
                    peerType = "agent",
                    presenceMemberId = row.id,
                    hasDraft = row.hasDraft
                )
            },
        agentStatusResolver = resolver,
        showBottomBar = true,
        outerPadding = 0.dp
    )
}
