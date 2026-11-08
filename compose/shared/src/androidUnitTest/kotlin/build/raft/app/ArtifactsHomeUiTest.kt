package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
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
import build.raft.app.data.ArtifactsHomeChoiceKind
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
        onNodeWithText("Launch plan").assertExists() // the old `.html` left off
        onAllNodesWithText("#proj-workspace · v2 · 4 comments").assertCountEquals(2)
        onNodeWithText("Ottie for you").assertExists()
        onNodeWithText("Mentions you · 2 new comments").assertExists()
        onNodeWithTag(ArtifactsHomeTags.Count).assertExists()
        onNodeWithText("24 Artifacts").assertExists()
    }

    @Test
    fun aRowOpensTheViewerAndLoadMoreAsksForTheNextPage() = runRobolectricComposeUiTest {
        show(loaded(rows = listOf(row("pt_a", "Launch plan")), nextCursor = "c1"))

        onNodeWithText("Launch plan").performClick()
        assertEquals(listOf("pt_a"), opened)

        onNodeWithTag(ArtifactsHomeTags.More).performClick()
        assertEquals(ArtifactsHomeIntent.LoadMore, intents.last())
    }

    @Test
    fun theFilterBarSendsItsIntents() = runRobolectricComposeUiTest {
        show(loaded(rows = listOf(row("pt_a", "Launch plan"))))

        onNodeWithTag(ArtifactsHomeTags.Relevant).performClick()
        assertEquals(ArtifactsHomeIntent.SetRelevant(true), intents.last())

        onNodeWithTag(ArtifactsHomeTags.Conversation).performClick()
        assertEquals(ArtifactsHomeIntent.OpenChoices(ArtifactsHomeChoiceKind.Conversation), intents.last())

        onNodeWithTag(ArtifactsHomeTags.Author).performClick()
        assertEquals(ArtifactsHomeIntent.OpenChoices(ArtifactsHomeChoiceKind.Author), intents.last())

        onNodeWithText("Relevant to me 2").assertExists()
        onAllNodesWithTag(ArtifactsHomeTags.Clear).assertCountEquals(0)
    }

    @Test
    fun theConversationSheetOffersTheHomesAndPicksOne() = runRobolectricComposeUiTest {
        val state = loaded(rows = listOf(row("pt_a", "Launch plan")))
        show(state.copy(conversationChoices = state.conversationChoices.copy(open = true)))

        onNodeWithText("Everyone in Northwind Labs").assertExists()
        onNodeWithText("Anyone with the link").performClick()
        assertEquals(
            ArtifactsHomeIntent.SetPlace(build.raft.app.data.ArtifactsHomePlace.Home(build.raft.app.data.ArtifactsHomeScope.Public)),
            intents.first { it is ArtifactsHomeIntent.SetPlace },
        )
    }

    @Test
    fun relevantHidesTheSortAndBothFiltersShowClearAll() = runRobolectricComposeUiTest {
        val conversation = build.raft.app.data.ArtifactsHomeConversation("c-1", "proj", "channel")
        show(
            loaded(rows = listOf(row("pt_a", "Launch plan"))).copy(
                query = ArtifactsHomeQuery(place = build.raft.app.data.ArtifactsHomePlace.Conversation(conversation), author = "user:u-1", relevant = true),
                authorActor = ArtifactsHomeActor("user", "u-1", "pat", "Pat"),
            ),
        )

        onAllNodesWithTag(ArtifactsHomeTags.Sort).assertCountEquals(0)
        onNodeWithText("#proj").assertExists()
        onNodeWithText("Pat").assertExists()
        onNodeWithTag(ArtifactsHomeTags.Clear).performClick()
        assertEquals(ArtifactsHomeIntent.ClearFilters, intents.last())
    }

    @Test
    fun theSkeletonWhileTheFirstPageLoads() = runRobolectricComposeUiTest {
        // The skeleton pulses for ever: the clock is stepped by hand, so the test never waits for idle.
        mainClock.autoAdvance = false
        show(ArtifactsHomeState(serverId = "s-1", status = ArtifactsHomeStatus.Loading))
        onNodeWithTag(ArtifactsHomeTags.Skeleton).assertExists()
        onAllNodesWithTag(ArtifactsHomeTags.Row).assertCountEquals(0)
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
        onNodeWithText("Nothing new for you").assertExists()
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

    @Test
    fun theGridShowsTilesAndTheToggleSwitchesTheView() = runRobolectricComposeUiTest {
        val views = mutableListOf<ArtifactsHomeView>()
        show(loaded(rows = listOf(row("pt_a", "Launch plan"), row("pt_b", "Metrics"))), view = ArtifactsHomeView.Grid, onView = { views += it })
        onAllNodesWithTag(ArtifactsHomeTags.Tile).assertCountEquals(2)
        onNodeWithTag(ArtifactsHomeTags.ViewList).performClick()
        assertEquals(listOf(ArtifactsHomeView.List), views)
        onAllNodesWithTag(ArtifactsHomeTags.Row).assertCountEquals(2)
        onAllNodesWithText("Launch plan").assertCountEquals(1)
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
