package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.model.AgentStatus
import kotlinx.coroutines.flow.flowOf

// The rows of `screens.home.channel-list` mapped from the generated VisualFixtureData onto the
// Home models. Lives in src/visualFixtureData so it compiles into commonMain for the gated visual
// page and into commonTest for the JVM screenshot test (see shared/build.gradle.kts).

internal typealias HomeChannelListRow = Map<String, Any?>

private fun HomeChannelListRow.text(key: String): String = this[key] as String

private fun HomeChannelListRow.textOrNull(key: String): String? = this[key] as String?

private fun HomeChannelListRow.number(key: String): Int = this[key] as Int

private fun HomeChannelListRow.flag(key: String): Boolean = this[key] as Boolean

/** fixture `type` (web `Channel.type`): `private` is a channel kind, `joint` its own section. */
internal fun homeChannelListChannel(row: HomeChannelListRow): RaftHomeConversationItem {
    val type = row.text("type")
    return RaftHomeConversationItem(
        id = row.text("id"),
        name = row.text("name"),
        kind = if (type == JointChannelKindWireValue) JointChannelKindWireValue else "channel",
        channelType = type.takeIf { it == "private" },
        unreadCount = row.number("unread"),
        mentioned = row.flag("mention"),
        followedThreadUnreadCount = row.number("followedThreadsWithNewReplies"),
        hasDraft = row.flag("draft"),
        activityMuted = row.flag("muted"),
        activityMuteSupported = true
    )
}

internal fun homeChannelListDirectMessage(row: HomeChannelListRow): RaftHomeConversationItem {
    val isAgent = row.text("peerType") == "agent"
    return RaftHomeConversationItem(
        id = row.text("id"),
        name = row.text("name"),
        kind = RaftThreadSurfaceKind.DirectMessage,
        unreadCount = row.number("unread"),
        avatarLabel = row.text("avatarLabel"),
        peerType = if (isAgent) "agent" else "human",
        presenceMemberId = row.text("peerId")
    )
}

/** fixture `activity` (web agent activity) as the Android agent status. */
private fun homeChannelListAgentStatus(activity: String?): AgentStatus =
    when (activity) {
        "working" -> AgentStatus.Working
        "thinking" -> AgentStatus.Thinking
        "online" -> AgentStatus.Online
        "idle" -> AgentStatus.Idle
        else -> AgentStatus.Offline
    }

private val homeChannelListAgentStatuses: Map<String, SharedServerMemberStatus> =
    VisualFixtureData.HomeChannelList.DIRECT_MESSAGES
        .filter { row -> row.text("peerType") == "agent" }
        .associate { row ->
            val status = homeChannelListAgentStatus(row.textOrNull("activity"))
            row.text("peerId") to
                SharedServerMemberStatus(
                    status = status,
                    presence = status.presence,
                    statusLabel = row.textOrNull("activityDetail"),
                    runtime = row.textOrNull("runtime"),
                    external = false
                )
        }

/**
 * The phone Home root of `screens.home.channel-list`, from [VisualFixtureData.HomeChannelList].
 * Shared by the connected-test page (`compose_home_channel_list_visual`) and the JVM screenshot
 * test (`HomeChannelListScreenshotTest`); the caller pins the theme.
 */
@Composable
internal fun HomeChannelListVisualContent(
    unreadStale: Boolean = false,
    /** A status bar inset for the head to paint under (iOS-like phone top); null keeps the page's own. */
    topSafeArea: Dp? = null,
) {
    val fixture = VisualFixtureData.HomeChannelList
    val resolver: RaftAgentStatusResolver =
        remember {
            val byId = homeChannelListAgentStatuses.mapValues { (_, status) -> status.toRaftAgentStatusIndicatorState() }
            ({ memberId -> flowOf(byId[memberId]) })
        }
    val channels = remember { fixture.CHANNELS.map(::homeChannelListChannel) }
    val directMessages = remember { fixture.DIRECT_MESSAGES.map(::homeChannelListDirectMessage) }
    RaftStructuralHomeShell(
        currentServerName = fixture.SERVER_NAME,
        currentServerId = VisualFixtureData.Server.ID,
        servers =
            listOf(
                RaftServerSwitcherItem(
                    id = VisualFixtureData.Server.ID,
                    name = fixture.SERVER_NAME,
                    slug = VisualFixtureData.Server.SLUG
                )
            ),
        channels = channels,
        directMessages = directMessages,
        pinnedChannelIds = fixture.PINNED_CHANNEL_IDS,
        savedCount = fixture.SAVED_TOTAL,
        agentStatusResolver = resolver,
        contentRefreshEnabled = false,
        selectedTabKey = "home",
        showBottomBar = true,
        searchAndActivityOnShellNav = true,
        outerPadding = 0.dp,
        unreadStale = unreadStale,
        topSafeAreaOverride = topSafeArea,
    )
}
