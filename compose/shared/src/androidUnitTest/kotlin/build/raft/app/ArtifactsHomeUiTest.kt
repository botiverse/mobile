package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import build.raft.app.data.ArtifactsHomeBehalf
import build.raft.app.data.ArtifactsHomeCounts
import build.raft.app.data.ArtifactsHomeIntent
import build.raft.app.data.ArtifactsHomeNews
import build.raft.app.data.ArtifactsHomeQuery
import build.raft.app.data.ArtifactsHomeRow
import build.raft.app.data.ArtifactsHomeSort
import build.raft.app.data.ArtifactsHomeState
import build.raft.app.data.ArtifactsHomeStatus
import build.raft.app.data.ArtifactsHomeView
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
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
 * The Artifacts home page on a real composition (AGENTS.md "Compose UI tests"), against the web's
 * `ArtifactsDashboard` / `DashboardList` behaviour (slock staging aae2b7a89): the list, the
 * filters' intents, the states by cause, and a row opening the viewer.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class ArtifactsHomeUiTest {
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

    private val intents = mutableListOf<ArtifactsHomeIntent>()
    private val opened = mutableListOf<String>()

    private fun ComposeUiTest.show(initial: ArtifactsHomeState, view: ArtifactsHomeView = ArtifactsHomeView.List, onView: (ArtifactsHomeView) -> Unit = {}) {
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            var shownView by remember { mutableStateOf(view) }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                ArtifactsHomeScreen(
                    state = initial,
                    view = shownView,
                    readerId = "me",
                    serverName = "Northwind Labs",
                    wide = false,
                    onIntent = { intents += it },
                    onView = {
                        shownView = it
                        onView(it)
                    },
                    onOpen = { opened += it.summary.id },
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun listsTheRowsWithTheirPlaceFactsAuthorAndSticker() = runRobolectricComposeUiTest {
        show(loaded(rows = listOf(row("pt_a", "Launch plan.html", author = assistant, news = ArtifactsHomeNews(mentioned = true, newComments = 2)), row("pt_b", "Metrics"))))

        onAllNodesWithTag(ArtifactsHomeTags.Row).assertCountEquals(2)
        // One name per row (review M4), its parts cleared: the old `.html` left off.
        onNode(hasContentDescription("Launch plan, #proj-workspace, v2, 4 comments, Ottie for you, Updated ", substring = true)).assertExists()
        onNode(hasContentDescription("Mentions you · 2 new comments", substring = true)).assertExists()
        onNode(hasContentDescription("Metrics, #proj-workspace, v2, 4 comments, Kirby, Updated ", substring = true)).assertExists()
        onAllNodesWithText("Ottie for you").assertCountEquals(0)
        onNodeWithTag(ArtifactsHomeTags.Count).assertExists()
        onNodeWithText("24 Artifacts").assertExists()
    }

    @Test
    fun aRowOpensTheViewerAndLoadMoreAsksForTheNextPage() = runRobolectricComposeUiTest {
        show(loaded(rows = listOf(row("pt_a", "Launch plan")), nextCursor = "c1"))

        onAllNodesWithTag(ArtifactsHomeTags.Row)[0].performClick()
        assertEquals(listOf("pt_a"), opened)

        onNodeWithTag(ArtifactsHomeTags.More).performClick()
        assertEquals(ArtifactsHomeIntent.LoadMore, intents.last())
    }

    @Test
    fun aFailedReadIsAWarningWithTryAgainAndTheRowsStay() = runRobolectricComposeUiTest {
        show(loaded(rows = listOf(row("pt_a", "Launch plan"))).copy(status = ArtifactsHomeStatus.Failed))
        onNodeWithTag(ArtifactsHomeTags.Failed).assertExists()
        onAllNodesWithTag(ArtifactsHomeTags.Row).assertCountEquals(1)
        onNodeWithText("Try again").performClick()
        assertEquals(ArtifactsHomeIntent.Retry, intents.last())
    }

    @Test
    fun emptyStatesSayWhy() = runRobolectricComposeUiTest {
        var state by mutableStateOf(loaded(rows = emptyList()))
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                ArtifactsHomeScreen(state, ArtifactsHomeView.List, "me", null, false, { intents += it }, {}, {}, {})
            }
        }
        onNodeWithText("No Artifacts yet").assertExists()
        onAllNodesWithTag(ArtifactsHomeTags.EmptyAction).assertCountEquals(0)

        state = state.copy(query = ArtifactsHomeQuery(relevant = true))
        onNodeWithText("Nothing here involves you yet").assertExists()
        onNodeWithText("See all").performClick()
        assertEquals(ArtifactsHomeIntent.SetRelevant(false), intents.last())

        state = state.copy(query = ArtifactsHomeQuery(q = "plan", author = "user:u-1"))
        onNodeWithText("No matching Artifacts").assertExists()
        onNodeWithText("Clear search and filter").performClick()
        assertEquals(ArtifactsHomeIntent.ClearSearchAndFilters, intents.last())

        state = state.copy(status = ArtifactsHomeStatus.Off)
        onNodeWithTag(ArtifactsHomeTags.Off).assertExists()
        onNodeWithText("Artifacts aren't on for this server").assertExists()
    }

    private val assistant =
        ArtifactsHomeActor(type = "agent", id = "a-1", name = "ottie", displayName = "Ottie", onBehalfOf = ArtifactsHomeBehalf("me", "Me"))

    private fun row(id: String, title: String, author: ArtifactsHomeActor = ArtifactsHomeActor("user", "u-2", "kirby", "Kirby"), news: ArtifactsHomeNews = ArtifactsHomeNews()) =
        ArtifactsHomeRow(
            summary =
                ArtifactSummary(
                    id = id,
                    title = title,
                    description = null,
                    kind = ArtifactKind.Html,
                    version = 2,
                    pinned = false,
                    latestVersion = 2,
                    home = ArtifactHome.Conversation("proj-workspace", dm = false),
                    updatedAt = "2026-11-07T08:00:00.000Z",
                    commentCount = 4,
                ),
            author = author,
            openedAt = null,
            news = news,
        )

    private fun loaded(rows: List<ArtifactsHomeRow>, nextCursor: String? = null) =
        ArtifactsHomeState(
            serverId = "s-1",
            status = ArtifactsHomeStatus.Ok,
            query = ArtifactsHomeQuery(sort = ArtifactsHomeSort.Opened),
            rows = rows,
            nextCursor = nextCursor,
            counts = ArtifactsHomeCounts(total = 24, filtered = 24, relevant = 2),
        )
}
