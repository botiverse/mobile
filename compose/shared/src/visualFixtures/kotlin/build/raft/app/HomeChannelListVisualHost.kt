package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import build.raft.app.base.BasePager
import build.raft.app.compat.pager.Page
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.model.AgentStatus
import kotlinx.coroutines.flow.flowOf

/**
 * The whole phone Home root (`screens.home.channel-list` / `.dark`): the production
 * [RaftStructuralHomeShell] with its top bar (workspace pill + notification bell), the Saved
 * entry row, the Pinned, Joint Channels, Channels and Direct Messages sections and the phone
 * tab bar, from typed fixture state (no session, no auth).
 *
 * Every row comes from [VisualFixtureData.HomeChannelList], generated from the
 * `homeChannelList` block of slock's `packages/visual-testing/shared/fixtureData.json`, the
 * one source the React case seeds from too (`slock-visual generate-fixture-data`). This file
 * only maps those rows onto the Home models. Web-only row states (`mention`, the `@` sticker;
 * `followedThreadsWithNewReplies`, the thread count) have no Android Home model yet and are
 * not drawn here; the report shows the gap.
 *
 * No row is selected: a phone Home root never shows a selected row (opening a conversation
 * pushes it over Home, and the web hides the tab bar on those routes). Selected rows are
 * covered by `components.home.sidebar.row-states`.
 *
 * `pageData.appearance` = `dark` | `light` (default light) pins the app theme for the capture
 * and restores the previous appearance when the page leaves, so later cases in the same
 * instrumentation process keep their own theme. It is not persisted.
 */
@Page("compose_home_channel_list_visual", supportInLocal = true)
internal class ComposeHomeChannelListVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        val appearance =
            if (pageData.params.optString("appearance") == "dark") RaftAppAppearance.Dark else RaftAppAppearance.Light
        setContent {
            HomeChannelListVisualAppearance(appearance) { HomeChannelListVisualContent() }
        }
    }
}

/**
 * Pins the process-wide app theme ([RaftAppThemeState], which every `RaftColor` token and the
 * page root's `RaftTheme` read) while the fixture is on screen. Applied after the first
 * composition, so the first frame may still be light; the capture settles well past it.
 */
@Composable
private fun HomeChannelListVisualAppearance(appearance: RaftAppAppearance, content: @Composable () -> Unit) {
    DisposableEffect(appearance) {
        val previous = RaftAppThemeState.appearance
        RaftAppThemeState.apply(appearance)
        onDispose { RaftAppThemeState.apply(previous) }
    }
    content()
}

private typealias HomeChannelListRow = Map<String, Any?>

private fun HomeChannelListRow.text(key: String): String = this[key] as String

private fun HomeChannelListRow.textOrNull(key: String): String? = this[key] as String?

private fun HomeChannelListRow.count(key: String): Int = this[key] as Int

private fun HomeChannelListRow.flag(key: String): Boolean = this[key] as Boolean

/** fixture `type` (web `Channel.type`): `private` is a channel kind, `joint` its own section. */
private fun homeChannelListChannel(row: HomeChannelListRow): RaftHomeConversationItem {
    val type = row.text("type")
    return RaftHomeConversationItem(
        id = row.text("id"),
        name = row.text("name"),
        kind = if (type == JointChannelKindWireValue) JointChannelKindWireValue else "channel",
        channelType = type.takeIf { it == "private" },
        unreadCount = row.count("unread"),
        hasDraft = row.flag("draft"),
        activityMuted = row.flag("muted"),
        activityMuteSupported = true
    )
}

private fun homeChannelListDirectMessage(row: HomeChannelListRow): RaftHomeConversationItem {
    val isAgent = row.text("peerType") == "agent"
    return RaftHomeConversationItem(
        id = row.text("id"),
        name = row.text("name"),
        kind = RaftThreadSurfaceKind.DirectMessage,
        unreadCount = row.count("unread"),
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

@Composable
private fun HomeChannelListVisualContent() {
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
        searchAndActivityInBottomBar = true,
        outerPadding = 0.dp
    )
}
