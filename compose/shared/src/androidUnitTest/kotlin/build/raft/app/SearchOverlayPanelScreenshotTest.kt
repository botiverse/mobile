package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InboxItem
import build.raft.app.data.SearchSenderFilterItem
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeSearchPanelState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.toHomeChannelRow
import build.raft.app.data.toHomeDirectMessageRow
import build.raft.app.model.AvatarReference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.Presence
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.model.ThreadId
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The wide layout's search panel (web `SearchOverlay`) as the app draws it: the real
 * [SharedHomeSearchPanel] in panel mode, with the app's filter chips, entity and message rows
 * and hit highlights, over a stand-in page in a 1440x900 window at 2x, the web render's frame
 * (reports/desktop-search/web-overlay-*.png: visual-testing fixture, query "android").
 *
 * - `search.overlay.results`: android-artifacts + Android Developer 4, the Cindy and artin
 *   messages, the "Search for" row under the keyboard cursor.
 * - `search.overlay.empty`: nothing typed, no visits: "Search everything".
 * - `search.overlay.recent`: nothing typed, two conversations visited.
 *
 * Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1440dp-h900dp-xhdpi")
class SearchOverlayPanelScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun results() = capture("search.overlay.results", resultsState)

    @Test fun resultsDark() = capture("search.overlay.results.dark", resultsState, RaftAppAppearance.Dark)

    @Test fun empty() = capture("search.overlay.empty", SharedHomeSearchPanelState(serverId = serverId))

    @Test fun recent() =
        capture("search.overlay.recent", SharedHomeSearchPanelState(serverId = serverId), visited = listOf("dm_artin", "thread_design", "thread_android"))

    private fun capture(
        name: String,
        state: SharedHomeSearchPanelState,
        appearance: RaftAppAppearance = RaftAppAppearance.Light,
        visited: List<String> = emptyList(),
    ) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
                    Box(Modifier.fillMaxSize()) {
                        StandInPage()
                        SharedHomeSearchPanel(
                            state = state,
                            homeState = homeState,
                            onIntent = {},
                            onOpenItem = {},
                            onOpenEntity = {},
                            bottomContentPadding = 0.dp,
                            // The web render's clock: the fixture's June messages read "134 days ago".
                            timeContext =
                                RaftTimeContext(
                                    localOffsetMinutes = 0,
                                    todayEpochDay = raftEpochDayForDate(2026, 11, 3),
                                    nowEpochMillis = java.time.Instant.parse("2026-11-03T10:30:00Z").toEpochMilli(),
                                ),
                            overlay =
                                HomeSearchOverlayChrome(
                                    // The top bar field's top: (48 - 36) / 2 under the window top.
                                    anchorTop = 6.dp,
                                    anchorWidth = 0.dp,
                                    visitedThreadIds = visited,
                                    excludeThreadId = null,
                                    onDismiss = {},
                                    onOpenFullResults = {},
                                ),
                        )
                    }
                }
            }
        }
    }
}

/** The web render's stand-in frame: a 48dp top bar, the rail and list columns, a # design head. */
@Composable
private fun StandInPage() {
    val colors = RaftTheme.colors
    Column(Modifier.fillMaxSize().background(colors.layerCanvas)) {
        Box(Modifier.fillMaxWidth().height(48.dp).background(colors.layerFrame).padding(start = 16.dp), contentAlignment = Alignment.CenterStart) {
            RaftText("Raft", style = RaftTheme.typography.headline, color = colors.foregroundOnFrame)
        }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(56.dp).fillMaxHeight().background(colors.layerFrame))
            Box(Modifier.width(240.dp).fillMaxHeight().background(colors.layerSidebar))
            Box(Modifier.padding(20.dp)) { RaftText("# design", style = RaftTheme.typography.headline) }
        }
    }
}

private val serverId: String get() = homeSearchVisualServer.id.value

private val homeState: SharedHomeState
    get() =
        SharedHomeState(
            content =
                SharedHomeContentState.Content(
                    snapshot =
                        HomeSnapshot(
                            server = homeSearchVisualServer,
                            currentMember =
                                MemberProfile(
                                    id = MemberId("visual-user"),
                                    displayName = "artin",
                                    handle = "artin",
                                    kind = MemberKind.Human,
                                    presence = Presence.Active,
                                ),
                            channels =
                                listOf(
                                    ConversationSummary(
                                        threadId = ThreadId("thread_design"),
                                        channelId = ChannelId("ch_design"),
                                        kind = ConversationKind.Channel,
                                        name = "design",
                                    ).toHomeChannelRow(),
                                    ConversationSummary(
                                        threadId = ThreadId("thread_android"),
                                        channelId = ChannelId("ch_android"),
                                        kind = ConversationKind.Channel,
                                        name = "android-artifacts",
                                    ).toHomeChannelRow(),
                                ),
                            directMessages =
                                listOf(
                                    ConversationSummary(
                                        threadId = ThreadId("dm_artin"),
                                        channelId = ChannelId("dm_artin"),
                                        kind = ConversationKind.DirectMessage,
                                        name = "Cindy",
                                        presenceMemberId = MemberId("agent-cindy"),
                                    ).toHomeDirectMessageRow(),
                                ),
                            unreadTotal = 0,
                        ),
                    session = homeSearchVisualSession,
                ),
        )

private val resultsState: SharedHomeSearchPanelState
    get() =
        SharedHomeSearchPanelState(
            serverId = serverId,
            query = "android",
            searched = true,
            senderFilters =
                listOf(
                    SearchSenderFilterItem(
                        id = "agent-android-developer-4",
                        label = "Android Developer 4",
                        handle = "Android-Developer-4",
                        kind = MemberKind.Agent,
                        sourceServerId = homeSearchVisualServer.id,
                        avatarWireValue = AvatarReference.Random("android-developer").wireValue,
                    ),
                ),
            items =
                listOf(
                    InboxItem(
                        key = "msg-visual-search-1",
                        kind = InboxItemKind.Channel,
                        channelId = ChannelId("ch_design"),
                        channelName = "design",
                        lastMessageId = MessageId("msg-visual-search-1"),
                        lastMessagePreview = "Captured the Android visual artifact for #product:f8e569cb and queued React parity.",
                        lastMessageAt = "2026-06-22T10:30:00Z",
                        lastMessageSenderName = "Cindy",
                        latestActivityMessageId = MessageId("msg-visual-search-1"),
                        latestActivityPreview = "Captured the Android visual artifact for #product:f8e569cb and queued React parity.",
                        latestActivityAt = "2026-06-22T10:30:00Z",
                        latestActivitySenderName = "Cindy",
                    ),
                    InboxItem(
                        key = "msg-visual-search-2",
                        kind = InboxItemKind.Thread,
                        channelId = ChannelId("thread-msg-agent-reply"),
                        channelName = "design",
                        lastMessageId = MessageId("msg-visual-search-2"),
                        lastMessagePreview = "Review the Android composer crop before release.",
                        lastMessageAt = "2026-06-22T10:35:00Z",
                        lastMessageSenderName = "artin",
                        threadChannelId = ChannelId("thread-msg-agent-reply"),
                        parentChannelId = ChannelId("ch_design"),
                        parentChannelName = "design",
                        parentChannelKind = ConversationKind.Channel,
                        parentMessageId = MessageId("msg-agent-reply"),
                        parentMessagePreview = "Captured the Android visual artifact for #product:f8e569cb and queued React parity.",
                        latestActivityMessageId = MessageId("msg-visual-search-2"),
                        latestActivityPreview = "Review the Android composer crop before release.",
                        latestActivityAt = "2026-06-22T10:35:00Z",
                        latestActivitySenderName = "artin",
                    ),
                ),
            totalCount = 2,
            hasMore = false,
        )
