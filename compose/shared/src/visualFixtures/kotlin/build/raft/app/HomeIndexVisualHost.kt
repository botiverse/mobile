package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import build.raft.app.base.BasePager
import build.raft.app.compat.pager.Page
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.model.AgentStatus
import kotlinx.coroutines.flow.flowOf

/**
 * screens.home.index: the loaded Home screen (the most visited surface). The production Home
 * shell ([RaftStructuralHomeShell], the same composable screens.home.loading pairs) fed the rows
 * of the shared botiverse/slock `packages/visual-testing/shared/homeIndexFixture.json`, which the
 * React baseline serves from its API stubs:
 *  - CHANNELS: design (3 unread), android-artifacts, product (12 unread), general;
 *  - DIRECT MESSAGES: Cindy (agent, working, 2 unread), Designer (human, 1 unread),
 *    KMP-专家 (agent, online, runtime label);
 *  - PINNED: the Product UX Designer agent DM (working, live activity meta);
 *  - Saved count 5 and the app-shell bottom tab bar.
 * Keep the rows in sync with that JSON.
 */
@Page("compose_home_index_visual", supportInLocal = true)
internal class ComposeHomeIndexVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeIndexVisualContent() }
    }
}

private fun homeIndexAgentStatus(status: AgentStatus, label: String?) =
    SharedServerMemberStatus(
        status = status,
        presence = status.presence,
        statusLabel = label,
        detailKind = "other",
        runtime = "codex",
        external = false
    )

private val homeIndexAgentStatuses: Map<String, SharedServerMemberStatus> =
    mapOf(
        "agent-cindy" to homeIndexAgentStatus(AgentStatus.Working, "Capturing visual testing baselines"),
        "agent-product-ux" to homeIndexAgentStatus(AgentStatus.Working, "Capturing deterministic Activity tab state"),
        "agent-kmp-expert" to homeIndexAgentStatus(AgentStatus.Online, "Reviewing shared state")
    )

private val homeIndexChannels =
    listOf(
        RaftHomeConversationItem(id = "channel-design", name = "design", kind = RaftThreadSurfaceKind.Channel, unreadCount = 3),
        RaftHomeConversationItem(id = "channel-android", name = "android-artifacts", kind = RaftThreadSurfaceKind.Channel),
        RaftHomeConversationItem(id = "channel-product", name = "product", kind = RaftThreadSurfaceKind.Channel, unreadCount = 12),
        RaftHomeConversationItem(id = "channel-general", name = "general", kind = RaftThreadSurfaceKind.Channel)
    )

private val homeIndexDirectMessages =
    listOf(
        RaftHomeConversationItem(
            id = "dm-agent-cindy",
            name = "Cindy",
            kind = RaftThreadSurfaceKind.DirectMessage,
            unreadCount = 2,
            description = "Keeps visual testing release notes aligned.",
            avatarWireValue = "pixel:robot",
            peerType = "agent",
            presenceMemberId = "agent-cindy"
        ),
        RaftHomeConversationItem(
            id = "dm-agent-product-ux",
            name = "Product UX Designer",
            kind = RaftThreadSurfaceKind.DirectMessage,
            description = "Turns product feedback, screenshots, and release notes into actionable UX specs.",
            avatarWireValue = "pixel:robot",
            peerType = "agent",
            presenceMemberId = "agent-product-ux"
        ),
        RaftHomeConversationItem(
            id = "dm-agent-kmp-expert",
            name = "KMP-专家",
            kind = RaftThreadSurfaceKind.DirectMessage,
            description = "Shared KMP architecture and runtime adaptation.",
            avatarWireValue = "pixel:robot",
            peerType = "agent",
            presenceMemberId = "agent-kmp-expert"
        ),
        RaftHomeConversationItem(
            id = "dm-human-designer",
            name = "Designer",
            kind = RaftThreadSurfaceKind.DirectMessage,
            unreadCount = 1,
            peerType = "human",
            presenceMemberId = "visual-human-designer"
        )
    )

@Composable
private fun HomeIndexVisualContent() {
    val resolver: RaftAgentStatusResolver =
        remember {
            val byId = homeIndexAgentStatuses.mapValues { (_, status) -> status.toRaftAgentStatusIndicatorState() }
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
        channels = homeIndexChannels,
        directMessages = homeIndexDirectMessages,
        savedCount = 5,
        pinnedAgentIds = listOf("agent-product-ux"),
        pinnedOrder = listOf("agent-product-ux"),
        pinnedItems = listOf(SettingsServerSidebarPinnedItem(kind = "agent", id = "agent-product-ux")),
        agentStatusResolver = resolver,
        contentRefreshEnabled = false,
        showBottomBar = true,
        outerPadding = 0.dp
    )
}
