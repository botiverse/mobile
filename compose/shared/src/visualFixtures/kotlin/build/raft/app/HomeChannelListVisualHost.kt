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
 * The same rows are seeded on the web side from
 * `botiverse/slock packages/visual-testing/shared/homeChannelListFixture.json`; keep the two
 * in step (names, counts, order, states). Web-only states in that fixture (the `@` mention
 * sticker on `release`, the followed-thread count on `mobile`) have no Android model on Home
 * yet, so here those rows carry only what Android can draw; the report shows the gap.
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

private const val HomeChannelListServerId = "visual-server"
private const val HomeChannelListServerName = "Raft Design"
private const val HomeChannelListServerSlug = "visual"

/** Saved messages total (web `savedMessagesFixture.json` `total`). */
private const val HomeChannelListSavedCount = 5

private fun homeChannelListAgentStatus(status: AgentStatus, label: String? = null, runtime: String) =
    SharedServerMemberStatus(
        status = status,
        presence = status.presence,
        statusLabel = label,
        runtime = runtime,
        external = false
    )

private val homeChannelListAgentStatuses =
    mapOf(
        "agent-cindy" to homeChannelListAgentStatus(AgentStatus.Working, "Capturing visual testing baselines", runtime = "codex"),
        "agent-builder" to homeChannelListAgentStatus(AgentStatus.Online, runtime = "claude"),
        "agent-product-ux" to homeChannelListAgentStatus(AgentStatus.Online, runtime = "claude"),
    )

private val homeChannelListChannels =
    listOf(
        // Pinned: leaves the Channels section for the Pinned one.
        RaftHomeConversationItem(id = "channel-announcements", name = "announcements", kind = "channel", unreadCount = 3),
        RaftHomeConversationItem(id = "channel-partners-sync", name = "partners-sync", kind = JointChannelKindWireValue, unreadCount = 2),
        // Web also draws the `@` mention sticker here.
        RaftHomeConversationItem(id = "channel-release", name = "release", kind = "channel", unreadCount = 5),
        // Web draws the followed-thread count (2 threads with new replies); no channel unread.
        RaftHomeConversationItem(id = "channel-mobile", name = "mobile", kind = "channel"),
        RaftHomeConversationItem(
            id = "channel-ops-alerts",
            name = "ops-alerts",
            kind = "channel",
            unreadCount = 12,
            activityMuted = true,
            activityMuteSupported = true
        ),
        RaftHomeConversationItem(
            id = "channel-roadmap",
            name = "roadmap",
            kind = "channel",
            channelType = "private",
            hasDraft = true
        ),
        RaftHomeConversationItem(
            id = "channel-long-name",
            name = "visual-parity-and-release-readiness-for-every-phone",
            kind = "channel"
        ),
    )

private val homeChannelListDirectMessages =
    listOf(
        RaftHomeConversationItem(
            id = "dm-agent-cindy",
            name = "Cindy",
            kind = RaftThreadSurfaceKind.DirectMessage,
            avatarLabel = "CI",
            peerType = "agent",
            presenceMemberId = "agent-cindy"
        ),
        RaftHomeConversationItem(
            id = "dm-agent-builder",
            name = "Builder",
            kind = RaftThreadSurfaceKind.DirectMessage,
            unreadCount = 4,
            avatarLabel = "BU",
            peerType = "agent",
            presenceMemberId = "agent-builder"
        ),
        RaftHomeConversationItem(
            id = "dm-agent-product-ux",
            name = "Product UX",
            kind = RaftThreadSurfaceKind.DirectMessage,
            avatarLabel = "PR",
            peerType = "agent",
            presenceMemberId = "agent-product-ux"
        ),
        RaftHomeConversationItem(
            id = "dm-human-jiacheng",
            name = "Jiacheng from the infrastructure reliability team",
            kind = RaftThreadSurfaceKind.DirectMessage,
            unreadCount = 1,
            avatarLabel = "JI",
            peerType = "human",
            presenceMemberId = "visual-human-jiacheng"
        ),
    )

@Composable
private fun HomeChannelListVisualContent() {
    val resolver: RaftAgentStatusResolver =
        remember {
            val byId = homeChannelListAgentStatuses.mapValues { (_, status) -> status.toRaftAgentStatusIndicatorState() }
            ({ memberId -> flowOf(byId[memberId]) })
        }
    RaftStructuralHomeShell(
        currentServerName = HomeChannelListServerName,
        currentServerId = HomeChannelListServerId,
        servers =
            listOf(
                RaftServerSwitcherItem(
                    id = HomeChannelListServerId,
                    name = HomeChannelListServerName,
                    slug = HomeChannelListServerSlug
                )
            ),
        channels = homeChannelListChannels,
        directMessages = homeChannelListDirectMessages,
        pinnedChannelIds = listOf("channel-announcements"),
        savedCount = HomeChannelListSavedCount,
        agentStatusResolver = resolver,
        contentRefreshEnabled = false,
        selectedTabKey = "home",
        showBottomBar = true,
        searchAndActivityInBottomBar = true,
        outerPadding = 0.dp
    )
}
