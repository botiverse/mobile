package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
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
import kotlin.test.assertTrue
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
    fun theFilterBarSendsItsIntents() = runRobolectricComposeUiTest {
        show(loaded(rows = listOf(row("pt_a", "Launch plan"))))

        // A switch (review M3), named by its filter.
        onNodeWithTag(ArtifactsHomeTags.Relevant).assertIsOff()
        onNodeWithTag(ArtifactsHomeTags.Relevant).performClick()
        assertEquals(ArtifactsHomeIntent.SetRelevant(true), intents.last())
        onNodeWithTag(ArtifactsHomeTags.Conversation).assert(hasContentDescription("By conversation"))
        onNodeWithTag(ArtifactsHomeTags.Sort).assert(hasContentDescription("Sort: Recently opened"))
        onNodeWithTag(ArtifactsHomeTags.Search, useUnmergedTree = true).assert(hasContentDescription("Search Artifacts by title"))

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
        onNodeWithTag(ArtifactsHomeTags.Relevant).assertIsOn()
        onNodeWithTag(ArtifactsHomeTags.Conversation).assert(hasContentDescription("Conversation: #proj"))
        onNodeWithTag(ArtifactsHomeTags.Author).assert(hasContentDescription("Author: Pat"))
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

    @Test
    fun theGridShowsTilesAndTheToggleSwitchesTheView() = runRobolectricComposeUiTest {
        val views = mutableListOf<ArtifactsHomeView>()
        show(loaded(rows = listOf(row("pt_a", "Launch plan"), row("pt_b", "Metrics"))), view = ArtifactsHomeView.Grid, onView = { views += it })
        onAllNodesWithTag(ArtifactsHomeTags.Tile).assertCountEquals(2)
        onNodeWithTag(ArtifactsHomeTags.ViewList).performClick()
        assertEquals(listOf(ArtifactsHomeView.List), views)
        onAllNodesWithTag(ArtifactsHomeTags.Row).assertCountEquals(2)
        onAllNodes(hasContentDescription("Launch plan, ", substring = true)).assertCountEquals(1)
    }

    /**
     * Masonry: a tall tile doesn't push the tile beside it down. Each column packs its own, so
     * the third tile sits right under the short first one (one gap), not under the tall second
     * one, and Load more spans the full width.
     */
    @Test
    fun theGridIsMasonryAndLoadMoreSpansTheLine() = runRobolectricComposeUiTest {
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                ArtifactsHomeScreen(
                    state = loaded(rows = listOf(row("pt_a", "Short"), row("pt_b", "Tall"), row("pt_c", "Next")), nextCursor = "c1"),
                    view = ArtifactsHomeView.Grid,
                    readerId = "me",
                    serverName = null,
                    wide = false,
                    onIntent = { intents += it },
                    onView = {},
                    onOpen = {},
                    onBack = {},
                    cover = { row -> Box(Modifier.fillMaxWidth().height(if (row.summary.id == "pt_b") 240.dp else 40.dp)) },
                )
            }
        }
        val tiles = onAllNodesWithTag(ArtifactsHomeTags.Tile)
        tiles.assertCountEquals(3)
        val first = tiles[0].getUnclippedBoundsInRoot()
        val second = tiles[1].getUnclippedBoundsInRoot()
        val third = tiles[2].getUnclippedBoundsInRoot()
        assertNear(first.left, third.left)
        assertTrue(second.left > first.left)
        // Under the short first tile, 8dp down: not below the tall second one's bottom.
        assertNear(first.bottom + 8.dp, third.top)
        assertTrue(third.top < second.bottom)
        val more = onNodeWithTag(ArtifactsHomeTags.More).getUnclippedBoundsInRoot()
        assertTrue(more.top >= maxOf(second.bottom, third.bottom))
    }

    /**
     * artin's review of the head, against the web's phone toolbar (ArtifactDashboardToolbar.tsx):
     * the view toggle at the head's trailing end, icons only; the search (InputGroup `md`) and the
     * sort (its icon and chevron) one 32dp height, on the 16dp margins the tiles keep.
     */
    @Test
    fun theHeadAndToolbarSitOnOneGrid() = runRobolectricComposeUiTest {
        show(loaded(rows = listOf(row("pt_a", "Launch plan"), row("pt_b", "Metrics"))), view = ArtifactsHomeView.Grid)
        val page = onNodeWithTag(ArtifactsHomeTags.Page).getUnclippedBoundsInRoot()
        val toggle = onNodeWithTag(ArtifactsHomeTags.ViewList).getUnclippedBoundsInRoot()
        val search = onNodeWithTag(ArtifactsHomeTags.Search, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val sort = onNodeWithTag(ArtifactsHomeTags.Sort).getUnclippedBoundsInRoot()
        val tiles = onAllNodesWithTag(ArtifactsHomeTags.Tile)
        val firstTile = tiles[0].getUnclippedBoundsInRoot()
        val secondTile = tiles[1].getUnclippedBoundsInRoot()
        // At the head's end: the last segment ends within the track's 3dp inset of the 16dp margin.
        assertTrue(toggle.right <= page.right - 16.dp + 0.5.dp && toggle.right >= page.right - 16.dp - 3.5.dp, "toggle ends at ${toggle.right}, page ${page.right}")
        assertTrue(toggle.left > search.left + search.width / 2)
        // Icons only on a phone: the labels are names and tooltips, not text on the head.
        onAllNodesWithText("Grid").assertCountEquals(0)
        onAllNodesWithText("Recently opened").assertCountEquals(0)
        onNodeWithText("Search", useUnmergedTree = true).assertExists()
        // The search and the sort: one height (a picker trigger's 32dp), one top, the 16dp margins.
        assertNear(32.dp, search.height)
        assertNear(search.height, sort.height)
        assertNear(search.top, sort.top)
        assertNear(page.left + 16.dp, search.left)
        assertNear(page.right - 16.dp, sort.right)
        // The tiles below keep the same edges.
        assertNear(search.left, firstTile.left)
        assertNear(sort.right, secondTile.right)
    }

    private fun assertNear(expected: Dp, actual: Dp) = assertTrue(kotlin.math.abs(expected.value - actual.value) < 0.5f, "expected $expected, was $actual")

    /** Review M1: Russian counts take their few / many forms (files/plurals/ru.json). */
    @Test
    fun russianNewCommentCountsTakeTheirOwnForms() = runRobolectricComposeUiTest {
        StringDesc.localeType = AppLocale.Russian.stringDescLocaleType
        show(loaded(rows = listOf(row("pt_a", "A", news = ArtifactsHomeNews(newComments = 3)), row("pt_b", "B", news = ArtifactsHomeNews(newComments = 5)), row("pt_c", "C", news = ArtifactsHomeNews(newComments = 21)))))
        onNode(hasContentDescription("3 новых комментария", substring = true)).assertExists()
        onNode(hasContentDescription("5 новых комментариев", substring = true)).assertExists()
        onNode(hasContentDescription("21 новый комментарий", substring = true)).assertExists()
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
