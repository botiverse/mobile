package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.ArtifactsHomeActor
import build.raft.app.data.ArtifactsHomeNews
import build.raft.app.data.ArtifactsHomeRow
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InboxItem
import build.raft.app.data.ScreenError
import build.raft.app.data.SearchArtifactHit
import build.raft.app.data.SearchArtifactsState
import build.raft.app.data.SearchArtifactsStatus
import build.raft.app.data.SearchChannelHit
import build.raft.app.data.SearchCoreGroup
import build.raft.app.data.SearchCoreGroupState
import build.raft.app.data.SearchCoreGroupStatus
import build.raft.app.data.SearchCoreGroupsIntent
import build.raft.app.data.SearchCoreGroupsState
import build.raft.app.data.SearchPersonHit
import build.raft.app.data.SearchRequiredAction
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeSearchPanelState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.authSessionFixture
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import build.raft.app.resources.StringDesc
import build.raft.app.settings.SearchEntityResult
import build.raft.app.settings.SharedHomeSearchPanel
import build.raft.app.thread.key
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Global search's channels and people from core's search (web #15387, Kirby's design), as
 * [SharedHomeSearchPanel] draws them for "launch":
 * - `search.core-groups.phone` (+ `.dark`): Channels 12 with Show all at the heading's end, rows
 *   with one mark at most (Archived, External with the external icon), "Not joined · <description>"
 *   and no Join; People 3 (Agent, Guest); the Artifacts row of an archived home says Archived;
 * - `search.core-groups.wide` (+ `.dark`): the wide layout's panel with the same groups;
 * - `search.core-groups.failed`: the server couldn't be asked: local rows and the line with Retry.
 * Verify / record as [RaftRobolectricScreenshot].
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1100dp-xxhdpi")
class SearchCoreGroupsScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    @Test
    fun phone() = capture("search.core-groups.phone", RaftAppAppearance.Light) { Panel(Answered) }

    @Test
    fun phoneDark() = capture("search.core-groups.phone.dark", RaftAppAppearance.Dark) { Panel(Answered) }

    @Test
    fun failed() = capture("search.core-groups.failed", RaftAppAppearance.Light) { Panel(Failed) }

    @Test
    fun showAllExpandsTheGroupAndLoadMoreAsksForItsNextPage() =
        runRobolectricComposeUiTest {
            val intents = mutableListOf<SearchCoreGroupsIntent>()
            setContent { Host { Panel(Answered, onCoreGroupsIntent = { intents += it }) } }

            onAllNodesWithTag(HomeSearchEntityGroupTags.LoadMore).assertCountEquals(0)
            onAllNodesWithTag(HomeSearchEntityGroupTags.ShowAll)[0].performClick()
            waitForIdle()
            scrollTo(HomeSearchEntityGroupTags.LoadMore)
            onNodeWithTag(HomeSearchEntityGroupTags.LoadMore).performClick()
            waitForIdle()

            assertEquals(listOf<SearchCoreGroupsIntent>(SearchCoreGroupsIntent.LoadMore(SearchCoreGroup.Channels)), intents)
        }

    @Test
    fun aFailedSearchOffersRetryButOfflineOnlySaysSo() =
        runRobolectricComposeUiTest {
            val intents = mutableListOf<SearchCoreGroupsIntent>()
            val offline = mutableStateOf(false)
            setContent {
                Host {
                    CompositionLocalProvider(LocalRaftAppShellNetworkOffline provides offline.value) {
                        Panel(Failed, onCoreGroupsIntent = { intents += it })
                    }
                }
            }

            onNodeWithText("Couldn't search all channels and people, so only those already loaded are shown.").assertExists()
            // The loaded channel stays.
            onNodeWithText("launch-room").assertExists()
            onNodeWithTag(HomeSearchEntityGroupTags.IncompleteRetry).performClick()
            waitForIdle()
            assertEquals(listOf<SearchCoreGroupsIntent>(SearchCoreGroupsIntent.Retry), intents)

            offline.value = true
            waitForIdle()
            onNodeWithText("You're offline, so only channels and people already loaded are shown.").assertExists()
            onAllNodesWithTag(HomeSearchEntityGroupTags.IncompleteRetry).assertCountEquals(0)
        }

    private fun ComposeUiTest.scrollTo(tag: String) {
        val scrollables = onAllNodes(hasScrollToNodeAction())
        val reached =
            (0 until scrollables.fetchSemanticsNodes().size).any { index ->
                runCatching { scrollables[index].performScrollToNode(hasTestTag(tag)) }.isSuccess
            }
        assertEquals(true, reached, "$tag is in the results list")
    }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = REPORT_DIR, activities = activities) { Host(content) }

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply {
                    pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftDesignSystemTheme { Box(Modifier.fillMaxSize()) { content() } }
        }
    }

    @Composable
    private fun Panel(
        core: SearchCoreGroupsState,
        overlay: HomeSearchOverlayChrome? = null,
        onOpenEntity: (SearchEntityResult) -> Unit = {},
        onCoreGroupsIntent: (SearchCoreGroupsIntent) -> Unit = {},
    ) {
        SharedHomeSearchPanel(
            state =
                SharedHomeSearchPanelState(
                    serverId = "server-1",
                    query = "launch",
                    searched = true,
                    items = listOf(message("1", "The launch checklist is ready for review.")),
                    totalCount = 1,
                ),
            homeState = Home,
            onIntent = {},
            onOpenItem = {},
            onOpenEntity = onOpenEntity,
            bottomContentPadding = 0.dp,
            timeContext = TimeContext,
            overlay = overlay,
            artifacts = Artifacts,
            coreGroups = core,
            onCoreGroupsIntent = onCoreGroupsIntent,
        )
    }

    private companion object {
        const val REPORT_DIR = "build/reports/home-screenshots"
        const val TraceId = "0af7651916cd43dd8448eb211c80319c"

        // 2026-11-16 at UTC+0.
        val TimeContext = RaftTimeContext(localOffsetMinutes = 0, todayEpochDay = 20773L, nowEpochMillis = 1_794_830_400_000L)

        val Home =
            SharedHomeState(
                content =
                    SharedHomeContentState.Content(
                        snapshot =
                            HomeSnapshot(
                                server = ServerSummary(ServerId("server-1"), "Raft", plan = "pro"),
                                currentMember = MemberProfile(id = MemberId("user-1"), displayName = "Me", handle = "me", kind = MemberKind.Human),
                                channels = listOf(HomeChannelRow(threadId = ThreadId("launch-room"), channelId = ChannelId("launch-room"), name = "launch-room")),
                                directMessages = emptyList(),
                                unreadTotal = 0,
                            ),
                        session = authSessionFixture(),
                    )
            )

        fun channel(
            id: String,
            description: String? = null,
            type: String = "channel",
            archived: Boolean = false,
            joined: Boolean = true,
            external: Boolean = false,
            guest: Boolean = false,
        ) = SearchChannelHit(
            id = id,
            name = id,
            description = description,
            type = type,
            archived = archived,
            joined = joined,
            external = external,
            guest = guest,
            canPost = joined && !archived,
            requiredAction = if (!joined && !archived && type == "channel") SearchRequiredAction.JoinChannel else null,
        )

        fun person(id: String, name: String, displayName: String?, agent: Boolean = false, guest: Boolean = false, dm: String? = null) =
            SearchPersonHit(
                agent = agent,
                id = id,
                name = name,
                displayName = displayName,
                avatarUrl = null,
                role = if (guest) "guest" else "member",
                guest = guest,
                dmChannelId = dm,
                canPost = dm != null,
                requiredAction = if (dm == null) SearchRequiredAction.CreateDm else null,
            )

        val Answered =
            SearchCoreGroupsState(
                serverId = "server-1",
                channels =
                    SearchCoreGroupState(
                        status = SearchCoreGroupStatus.Ok,
                        query = "launch",
                        items =
                            listOf(
                                channel("launch-room", description = "Where the launch happens"),
                                channel("launch-public", description = "Launch notes and checklists", joined = false),
                                channel("launch-partners", type = "joint", external = true),
                                channel("launch-guests", type = "private", guest = true),
                                channel("launch-2025", archived = true),
                                channel("launch-retro", description = "What we learned"),
                            ),
                        nextCursor = "channels.6.tag",
                        total = 12,
                    ),
                people =
                    SearchCoreGroupState(
                        status = SearchCoreGroupStatus.Ok,
                        query = "launch",
                        items =
                            listOf(
                                person("a-launch", "launch-bot", "Launch Bot", agent = true, dm = "dm-1"),
                                person("u-kirby", "kirby", "Kirby Launch"),
                                person("u-gwen", "gwen", "Gwen (launch guest)", guest = true),
                            ),
                        total = 3,
                    ),
            )

        val Failed =
            SearchCoreGroupsState(
                serverId = "server-1",
                channels = SearchCoreGroupState(status = SearchCoreGroupStatus.Failed, query = "launch", error = ScreenError("web_search_http_500", traceId = TraceId)),
                people = SearchCoreGroupState(status = SearchCoreGroupStatus.Ok, query = "launch"),
            )

        val Artifacts =
            SearchArtifactsState(
                serverId = "server-1",
                query = "launch",
                status = SearchArtifactsStatus.Ok,
                hits =
                    listOf(
                        SearchArtifactHit(
                            row =
                                ArtifactsHomeRow(
                                    summary =
                                        ArtifactSummary(
                                            id = "pt_launch",
                                            title = "Launch plan",
                                            description = null,
                                            kind = ArtifactKind.Html,
                                            version = 1,
                                            pinned = false,
                                            latestVersion = 1,
                                            home = ArtifactHome.Conversation("launch-2025", dm = false, archived = true),
                                            authorName = "Kirby",
                                            updatedAt = "2026-11-16T06:00:00.000Z",
                                        ),
                                    author = ArtifactsHomeActor(type = "user", id = "u-kirby", name = "kirby", displayName = "Kirby"),
                                    openedAt = null,
                                    news = ArtifactsHomeNews(),
                                ),
                        ),
                    ),
            )

        fun message(id: String, preview: String) =
            InboxItem(
                key = "channel:c:$id",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("c"),
                channelName = "launch-room",
                lastMessageSenderName = "Friday",
                lastMessageSenderKind = MemberKind.Agent,
                lastMessagePreview = preview,
                lastMessageAt = "2026-11-15T16:31:00Z",
            )
    }
}
