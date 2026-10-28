package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Conversations marked unread with nothing countable (botiverse/slock #11795): web `QuietUnreadDot`,
 * the sidebar's quiet square pixel where a count would be. Home: a marked channel (unread name, the
 * dot), a marked muted channel (quiet name, the dot), a counted channel and a marked DM (the dot in
 * place of the draft / agent meta). Activity: a marked thread of only the person's replies (bold
 * title, the dot), beside a counted row and a read one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h560dp-xxhdpi")
class MarkedUnreadRowsScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun homeMarkedUnreadRowsLight() =
        capture("screens.home.marked-unread", RaftAppAppearance.Light) { HomeMarkedUnreadRows() }

    @Test
    fun homeMarkedUnreadRowsDark() =
        capture("screens.home.marked-unread.dark", RaftAppAppearance.Dark) { HomeMarkedUnreadRows() }

    @Test
    fun activityMarkedUnreadRowsLight() =
        capture("screens.activity.rows.marked-unread", RaftAppAppearance.Light) { ActivityMarkedUnreadRows(it) }

    @Test
    fun activityMarkedUnreadRowsDark() =
        capture("screens.activity.rows.marked-unread.dark", RaftAppAppearance.Dark) { ActivityMarkedUnreadRows(it) }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable (RaftAppAppearance) -> Unit) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { content(appearance) }
        }
    }
}

@Composable
private fun HomeMarkedUnreadRows() {
    RaftStructuralHomeShell(
        currentServerName = "Raft",
        currentServerId = "server-1",
        channels =
            listOf(
                RaftHomeConversationItem(id = "c-marked", name = "proj-mobile", kind = "channel", markedUnread = true),
                RaftHomeConversationItem(
                    id = "c-muted",
                    name = "proj-connectors",
                    kind = "channel",
                    markedUnread = true,
                    activityMuted = true,
                    activityMuteSupported = true
                ),
                RaftHomeConversationItem(id = "c-counted", name = "release", kind = "channel", unreadCount = 3, markedUnread = true),
                RaftHomeConversationItem(id = "c-read", name = "general", kind = "channel", hasDraft = true),
            ),
        directMessages =
            listOf(
                RaftHomeConversationItem(
                    id = "dm-marked",
                    name = "Cindy",
                    kind = RaftThreadSurfaceKind.DirectMessage,
                    avatarLabel = "CI",
                    peerType = "human",
                    markedUnread = true
                ),
                RaftHomeConversationItem(
                    id = "dm-read",
                    name = "Volta",
                    kind = RaftThreadSurfaceKind.DirectMessage,
                    avatarLabel = "VO",
                    peerType = "human"
                ),
            ),
        contentRefreshEnabled = false,
        selectedTabKey = "home",
        showBottomBar = true,
        searchAndActivityOnShellNav = true,
        outerPadding = 0.dp,
    )
}

@Composable
private fun ActivityMarkedUnreadRows(appearance: RaftAppAppearance) {
    val timeContext =
        RaftTimeContext(
            localOffsetMinutes = 8 * 60,
            todayEpochDay = raftEpochDayForDate(2026, 10, 28),
            nowEpochMillis = 1_793_150_000_000L,
        )
    val rows =
        listOf(
            // T3: a thread whose only message from someone else is the parent; every reply is the person's own.
            InboxItem(
                key = "thread:marked",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-marked"),
                channelName = "thread",
                markedUnread = true,
                parentChannelId = ChannelId("mobile"),
                parentChannelName = "mobile",
                parentChannelKind = ConversationKind.Channel,
                parentMessagePreview = "a question from the peer",
                latestActivityPreview = "my second answer",
                latestActivitySenderName = "Lagoon",
                latestActivitySenderId = "agent-lagoon",
                latestActivitySenderKind = MemberKind.Agent,
                latestActivityAt = "2026-10-28T03:40:00Z",
            ),
            InboxItem(
                key = "channel:counted",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("release"),
                channelName = "release",
                unreadCount = 2,
                markedUnread = true,
                lastMessageSenderName = "Volta",
                lastMessageSenderKind = MemberKind.Agent,
                lastMessagePreview = "The preview build is up.",
                lastMessageAt = "2026-10-28T03:20:00Z",
            ),
            InboxItem(
                key = "channel:read",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("general"),
                channelName = "general",
                lastMessageSenderName = "Scout",
                lastMessageSenderKind = MemberKind.Agent,
                lastMessagePreview = "Merged.",
                lastMessageAt = "2026-10-28T03:00:00Z",
            ),
        )
    val context =
        ActivityConversationListContext(
            channelSymbolsByChannelId = mapOf("mobile" to "#", "release" to "#", "general" to "#"),
        )
    RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
        Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
            rows.forEachIndexed { index, item ->
                ActivityConversationListRow(
                    item = item,
                    model = item.activityConversationRowModel(context, InboxFilter.Unread),
                    divider = index > 0,
                    selected = false,
                    timeContext = timeContext,
                    agentStatusResolver = EmptyRaftAgentStatusResolver,
                    canMarkDone = true,
                    openGestureKey = item.key,
                    captureMenuAnchor = false,
                    onOpen = {},
                    onOpenMenu = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
