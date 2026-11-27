package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import build.raft.app.data.SearchArtifactsIntent
import build.raft.app.data.SearchArtifactsState
import build.raft.app.data.SearchArtifactsStatus
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
import build.raft.app.settings.SharedHomeSearchPanel
import build.raft.app.thread.key
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
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
 * The Artifacts group of global search (web `AppSearchGroupSection` + `ArtifactSearchHit`, slock
 * staging 93207e216), as [SharedHomeSearchPanel] draws it:
 * - `search.artifacts.results`: the phone page with a destination, then "Artifacts 3" (kind tile,
 *   marked title, "#place · author · time") with Load more, then the messages;
 * - `search.artifacts.loading`: the group's heading over its skeleton while it reads;
 * - `search.artifacts.failed`: "Searching Artifacts took too long." with Retry and the trace id;
 * - `search.overlay.artifacts`: the wide panel previews the group (no Load more).
 * Verify / record as [RaftRobolectricScreenshot].
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h720dp-xxhdpi")
class SearchArtifactsGroupScreenshotTest {
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
    fun results() =
        RaftRobolectricScreenshot.capture("search.artifacts.results", RaftAppAppearance.Light, reportDir = REPORT_DIR, activities = activities) {
            Host { Panel(ResultsGroup) }
        }

    @Test
    fun aHitOpensItsArtifactAndTheSectionAsksForMore() =
        runRobolectricComposeUiTest {
            val opened = mutableListOf<SearchArtifactHit>()
            val intents = mutableListOf<SearchArtifactsIntent>()
            setContent { Host { Panel(ResultsGroup, onArtifactsIntent = { intents += it }, onOpenArtifact = { opened += it }) } }

            onAllNodesWithTag(HomeSearchArtifactsTags.Hit, useUnmergedTree = true).assertCountEquals(3)
            onNodeWithText("Launch plan").performClick()
            waitForIdle()
            assertEquals(listOf("pt_launch"), opened.map { it.id })

            scrollTo(HomeSearchArtifactsTags.More)
            onNodeWithTag(HomeSearchArtifactsTags.More).performClick()
            waitForIdle()
            assertEquals(listOf<SearchArtifactsIntent>(SearchArtifactsIntent.LoadMore), intents)
        }

    @Test
    fun aFailedGroupRetries() =
        runRobolectricComposeUiTest {
            val intents = mutableListOf<SearchArtifactsIntent>()
            setContent { Host { Panel(FailedGroup, onArtifactsIntent = { intents += it }) } }

            onNodeWithText("Searching Artifacts took too long.").assertExists()
            onNodeWithTag(RaftTraceIdNoteTestTag, useUnmergedTree = true).assertExists()
            onNodeWithTag(HomeSearchArtifactsTags.Retry).performClick()
            waitForIdle()
            assertEquals(listOf<SearchArtifactsIntent>(SearchArtifactsIntent.Retry), intents)
        }

    @Test
    @Config(qualifiers = "w1440dp-h900dp-xhdpi")
    fun theWidePanelHandsAnArtifactToTheFullSearchPage() =
        runRobolectricComposeUiTest {
            val fullResults = mutableListOf<String>()
            val opened = mutableListOf<SearchArtifactHit>()
            setContent {
                Host { Panel(ResultsGroup, overlay = overlayChrome(onOpenFullResults = { fullResults += it }), onOpenArtifact = { opened += it }) }
            }
            onAllNodesWithTag(HomeSearchArtifactsTags.More, useUnmergedTree = true).assertCountEquals(0)

            onNodeWithText("Plan review notes").performClick()
            waitForIdle()

            assertEquals(listOf("plan"), fullResults, "web #12764: the full search page, with the words")
            assertEquals(listOf("pt_review"), opened.map { it.id })
        }

    private fun ComposeUiTest.scrollTo(tag: String) {
        val scrollables = onAllNodes(hasScrollToNodeAction())
        val reached =
            (0 until scrollables.fetchSemanticsNodes().size).any { index ->
                runCatching { scrollables[index].performScrollToNode(hasTestTag(tag)) }.isSuccess
            }
        assertEquals(true, reached, "$tag is in the results list")
    }

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply {
                    pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftTheme(appearance = RaftAppearance.Light) { Box(Modifier.fillMaxSize()) { content() } }
        }
    }

    @Composable
    private fun Panel(
        artifacts: SearchArtifactsState,
        overlay: HomeSearchOverlayChrome? = null,
        onArtifactsIntent: (SearchArtifactsIntent) -> Unit = {},
        onOpenArtifact: (SearchArtifactHit) -> Unit = {},
    ) {
        SharedHomeSearchPanel(
            state =
                SharedHomeSearchPanelState(
                    serverId = "server-1",
                    query = "plan",
                    searched = true,
                    items = listOf(message("1", "The plan is in the doc.")),
                    totalCount = 1,
                ),
            homeState = Home,
            onIntent = {},
            onOpenItem = {},
            onOpenEntity = {},
            bottomContentPadding = 0.dp,
            timeContext = TimeContext,
            overlay = overlay,
            artifacts = artifacts,
            onArtifactsIntent = onArtifactsIntent,
            onOpenArtifact = onOpenArtifact,
        )
    }

    private fun overlayChrome(onOpenFullResults: (String) -> Unit = {}) =
        HomeSearchOverlayChrome(
            anchorTop = 6.dp,
            anchorWidth = 0.dp,
            visitedThreadIds = emptyList(),
            excludeThreadId = null,
            onDismiss = {},
            onOpenFullResults = onOpenFullResults,
        )

    private companion object {
        const val REPORT_DIR = "build/reports/home-screenshots"

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
                                channels = listOf(HomeChannelRow(threadId = ThreadId("plan-room"), channelId = ChannelId("plan-room"), name = "plan-room")),
                                directMessages = emptyList(),
                                unreadTotal = 0,
                            ),
                        session = authSessionFixture(),
                    )
            )

        fun hit(id: String, title: String, kind: ArtifactKind, home: ArtifactHome, author: String, updatedAt: String) =
            SearchArtifactHit(
                row =
                    ArtifactsHomeRow(
                        summary =
                            ArtifactSummary(
                                id = id,
                                title = title,
                                description = null,
                                kind = kind,
                                version = 1,
                                pinned = false,
                                latestVersion = 1,
                                home = home,
                                authorName = author,
                                updatedAt = updatedAt,
                            ),
                        author = ArtifactsHomeActor(type = "user", id = "u-$author", name = author.lowercase(), displayName = author),
                        openedAt = null,
                        news = ArtifactsHomeNews(),
                    ),
            )

        val ResultsGroup =
            SearchArtifactsState(
                serverId = "server-1",
                query = "plan",
                status = SearchArtifactsStatus.Ok,
                hits =
                    listOf(
                        hit("pt_launch", "Launch plan", ArtifactKind.Html, ArtifactHome.Conversation("launch", dm = false), "Kirby", "2026-11-16T06:00:00.000Z"),
                        hit("pt_review", "Plan review notes.md", ArtifactKind.Markdown, ArtifactHome.Server, "Remy", "2026-11-13T08:00:00.000Z"),
                        hit("pt_budget", "Budget plan", ArtifactKind.Csv, ArtifactHome.Public, "Ottie", "2026-10-02T08:00:00.000Z"),
                    ),
                nextCursor = "c1",
            )

        val FailedGroup =
            SearchArtifactsState(
                serverId = "server-1",
                query = "plan",
                status = SearchArtifactsStatus.Failed,
                timedOut = true,
                error = ScreenError("web_search_group_timeout", traceId = "0af7651916cd43dd8448eb211c80319c"),
            )

        fun message(id: String, preview: String) =
            InboxItem(
                key = "channel:c:$id",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("c"),
                channelName = "launch",
                lastMessageSenderName = "Friday",
                lastMessageSenderKind = MemberKind.Agent,
                lastMessagePreview = preview,
                lastMessageAt = "2026-11-15T16:31:00Z",
            )
    }
}
