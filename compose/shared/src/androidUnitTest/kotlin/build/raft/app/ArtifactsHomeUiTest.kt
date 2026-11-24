package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.AppLocale
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactSettled
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.ArtifactsHomeActor
import build.raft.app.data.ArtifactsHomeBehalf
import build.raft.app.data.ArtifactsHomeCounts
import build.raft.app.data.ArtifactsHomeIntent
import build.raft.app.data.ArtifactsHomeNews
import build.raft.app.data.ArtifactsHomePlace
import build.raft.app.data.ArtifactsHomeQuery
import build.raft.app.data.ArtifactsHomeRow
import build.raft.app.data.ArtifactsHomeScope
import build.raft.app.data.ArtifactsHomeSort
import build.raft.app.data.ArtifactsHomeState
import build.raft.app.data.ArtifactsHomeStatus
import build.raft.app.data.ArtifactsHomeStatusFilter
import build.raft.app.data.ArtifactsHomeView
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
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
 * The Artifacts home page on a real composition (AGENTS.md "Compose UI tests"), against web
 * `ArtifactsDashboard` / `ArtifactDashboardList` / `ArtifactDashboardToolbar` / `ArtifactTile` /
 * `ArtifactListRow` (botiverse/slock staging #15654, #16137, #16153, #16182, #16290).
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class ArtifactsHomeUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    private val timeContext =
        RaftTimeContext(
            localOffsetMinutes = 0,
            todayEpochDay = raftEpochDayForDate(2026, 11, 18),
            nowEpochMillis = 1_794_996_000_000L, // 2026-11-18T10:00:00Z
        )

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

    private fun ComposeUiTest.show(
        initial: ArtifactsHomeState,
        view: ArtifactsHomeView = ArtifactsHomeView.List,
        wide: Boolean = false,
        onView: (ArtifactsHomeView) -> Unit = {},
    ) {
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            var shownView by remember { mutableStateOf(view) }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext),
            ) {
                ArtifactsHomeScreen(
                    state = initial,
                    view = shownView,
                    readerId = "me",
                    serverName = "Northwind Labs",
                    wide = wide,
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
    fun listsTheRowsWithForYouCollapsibleSectionDateGroupsAndBadges() = runRobolectricComposeUiTest {
        show(
            loaded(
                rows =
                    listOf(
                        row(
                            "pt_a",
                            "Launch plan.html",
                            author = assistant,
                            news = ArtifactsHomeNews(mentioned = true, newComments = 2),
                            updatedAt = "2026-11-18T08:00:00.000Z",
                        ),
                        row(
                            "pt_b",
                            "Metrics",
                            updatedAt = "2026-11-18T07:00:00.000Z",
                        ),
                    ),
            ),
        )

        onNodeWithTag(ArtifactsHomeTags.Groups).assertExists()
        onNodeWithTag(ArtifactsHomeTags.ForYouHead).assertExists()
        onNodeWithText("For you").assertExists()
        onNodeWithTag(ArtifactsHomeTags.ForYouCount, useUnmergedTree = true).assertExists()
        onNodeWithText("Today").assertExists()
        onAllNodesWithTag(ArtifactsHomeTags.Row).assertCountEquals(2)
        // Stripped `.html` extension and full accessible description.
        onNode(hasContentDescription("Launch plan, #proj-workspace, v2, 4 comments, Ottie for you, Updated ", substring = true)).assertExists()
        onNode(hasContentDescription("Metrics, #proj-workspace, v2, 4 comments, Kirby, Updated ", substring = true)).assertExists()
        onNodeWithTag(ArtifactsHomeTags.MentionedPill, useUnmergedTree = true).assertExists()
        onAllNodesWithTag(ArtifactsHomeTags.CommentBadge, useUnmergedTree = true).assertCountEquals(2)
        onNodeWithTag(ArtifactsHomeTags.Count).assertExists()
        onNodeWithText("24 Artifacts").assertExists()

        // Collapsing "For you" hides pt_a while keeping Today's pt_b visible; clicking again restores it.
        onNodeWithTag(ArtifactsHomeTags.ForYouHead).performClick()
        onAllNodesWithTag(ArtifactsHomeTags.Row).assertCountEquals(1)
        onNodeWithTag(ArtifactsHomeTags.ForYouHead).performClick()
        onAllNodesWithTag(ArtifactsHomeTags.Row).assertCountEquals(2)
    }

    @Test
    fun compactGridTilesShowPublicOnlyForPublicHomeBadgesAndNoVersionText() = runRobolectricComposeUiTest {
        show(
            loaded(
                rows =
                    listOf(
                        row(
                            "pt_pub",
                            "Public guide",
                            home = ArtifactHome.Public,
                            news = ArtifactsHomeNews(mentioned = true, newerVersion = true, newComments = 2),
                            updatedAt = "2026-11-18T08:00:00.000Z",
                        ),
                        row(
                            "pt_ch",
                            "Channel doc",
                            home = ArtifactHome.Conversation("proj-workspace", dm = false),
                            commentCount = 4,
                            updatedAt = "2026-11-18T07:00:00.000Z",
                        ),
                    ),
            ),
            view = ArtifactsHomeView.Grid,
        )

        onAllNodesWithTag(ArtifactsHomeTags.Tile).assertCountEquals(2)
        // Public badge ONLY on the public-home tile (not on the channel tile).
        onAllNodesWithTag(ArtifactsHomeTags.PublicBadge, useUnmergedTree = true).assertCountEquals(1)
        onAllNodesWithTag(ArtifactsHomeTags.MentionedPill, useUnmergedTree = true).assertCountEquals(1)
        onAllNodesWithTag(ArtifactsHomeTags.NewVersionPill, useUnmergedTree = true).assertCountEquals(1)
        onAllNodesWithTag(ArtifactsHomeTags.CommentBadge, useUnmergedTree = true).assertCountEquals(2)
        // Compact tiles drop the old `v2` version line.
        onAllNodesWithText("v2", useUnmergedTree = true).assertCountEquals(0)
        onNodeWithText("This week").assertExists()
    }

    @Test
    fun settledStripAndStatusPickerControlSettledVisibility() = runRobolectricComposeUiTest {
        var state by mutableStateOf(
            loaded(
                rows =
                    listOf(
                        row("pt_work", "Working doc", settledShown = true),
                        row("pt_done", "Done doc", settled = settledInfo, settledShown = true),
                    ),
                settledEnabled = true,
                statusFilter = ArtifactsHomeStatusFilter.Unsettled,
            ),
        )
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext),
            ) {
                ArtifactsHomeScreen(state, ArtifactsHomeView.List, "me", "Northwind Labs", false, { intents += it }, {}, { opened += it.summary.id }, {})
            }
        }

        // Status picker present, only working row visible, strip says "1 settled hidden · Show".
        onNodeWithTag(ArtifactsHomeTags.Status).assertExists()
        onAllNodesWithTag(ArtifactsHomeTags.Row).assertCountEquals(1)
        onNodeWithTag(ArtifactsHomeTags.SettledStrip).assertExists()
        onNodeWithText("Show").performClick()
        assertEquals(ArtifactsHomeIntent.SetStatus(ArtifactsHomeStatusFilter.All), intents.last())

        // Under All, both rows show and strip says "1 settled shown dimmed · Hide".
        state = state.copy(statusFilter = ArtifactsHomeStatusFilter.All)
        onAllNodesWithTag(ArtifactsHomeTags.Row).assertCountEquals(2)
        onNodeWithTag(ArtifactSettledTagTestTag, useUnmergedTree = true).assertExists()
        onNodeWithText("Hide").performClick()
        assertEquals(ArtifactsHomeIntent.SetStatus(ArtifactsHomeStatusFilter.Unsettled), intents.last())

        // When settled is off on the server, Status picker and settled strip do not appear.
        state = state.copy(settledEnabled = false, statusFilter = ArtifactsHomeStatusFilter.Unsettled)
        onAllNodesWithTag(ArtifactsHomeTags.Status).assertCountEquals(0)
        onAllNodesWithTag(ArtifactsHomeTags.SettledStrip).assertCountEquals(0)
        onAllNodesWithTag(ArtifactsHomeTags.Row).assertCountEquals(2)
    }

    @Test
    fun clearAllAppearsForAnyActiveFilterAndDispatchesClearFilters() = runRobolectricComposeUiTest {
        var state by mutableStateOf(loaded(rows = listOf(row("pt_a", "Plan")), settledEnabled = true))
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext),
            ) {
                ArtifactsHomeScreen(state, ArtifactsHomeView.List, "me", "Northwind Labs", true, { intents += it }, {}, {}, {})
            }
        }

        // Default (Unsettled + no other filter): Clear all hidden.
        onAllNodesWithTag(ArtifactsHomeTags.Clear).assertCountEquals(0)

        // Status changed from default Unsettled -> All shows Clear all.
        state = state.copy(statusFilter = ArtifactsHomeStatusFilter.All)
        onNodeWithTag(ArtifactsHomeTags.Clear).assertExists()
        onNodeWithTag(ArtifactsHomeTags.Clear).performClick()
        assertEquals(ArtifactsHomeIntent.ClearFilters, intents.last())

        // Place alone shows Clear all.
        state = state.copy(statusFilter = ArtifactsHomeStatusFilter.Unsettled, query = ArtifactsHomeQuery(place = ArtifactsHomePlace.Home(ArtifactsHomeScope.Public)))
        onNodeWithTag(ArtifactsHomeTags.Clear).assertExists()

        // Search alone shows Clear all.
        state = state.copy(query = ArtifactsHomeQuery(q = "plan"), search = "plan")
        onNodeWithTag(ArtifactsHomeTags.Clear).assertExists()

        // Relevant alone shows Clear all.
        state = state.copy(query = ArtifactsHomeQuery(relevant = true), search = "")
        onNodeWithTag(ArtifactsHomeTags.Clear).assertExists()
    }

    @Test
    fun searchingRendersFlatWithoutGroupsOrForYouHeading() = runRobolectricComposeUiTest {
        show(
            loaded(
                rows =
                    listOf(
                        row("pt_a", "Launch plan", news = ArtifactsHomeNews(mentioned = true)),
                        row("pt_b", "Launch notes"),
                    ),
                query = ArtifactsHomeQuery(q = "launch"),
                search = "launch",
            ),
        )

        onAllNodesWithTag(ArtifactsHomeTags.Row).assertCountEquals(2)
        onAllNodesWithTag(ArtifactsHomeTags.Groups).assertCountEquals(0)
        onAllNodesWithTag(ArtifactsHomeTags.ForYouHead).assertCountEquals(0)
        onAllNodesWithTag(ArtifactsHomeTags.DateGroupHeading).assertCountEquals(0)
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
    fun emptyStatesSayWhyIncludingSettledEmptyStates() = runRobolectricComposeUiTest {
        var state by mutableStateOf(loaded(rows = emptyList()))
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext),
            ) {
                ArtifactsHomeScreen(state, ArtifactsHomeView.List, "me", null, false, { intents += it }, {}, {}, {})
            }
        }
        onNodeWithText("No Artifacts yet").assertExists()
        onAllNodesWithTag(ArtifactsHomeTags.EmptyAction).assertCountEquals(0)

        state = state.copy(query = ArtifactsHomeQuery(relevant = true))
        onNodeWithText("Nothing here involves you yet").assertExists()
        onNodeWithText("See all").performClick()
        assertEquals(ArtifactsHomeIntent.SetRelevant(false), intents.last())

        state = state.copy(query = ArtifactsHomeQuery(q = "plan", author = "user:u-1"), search = "plan")
        onNodeWithText("No Artifacts match your search").assertExists()
        onNodeWithTag(ArtifactsHomeTags.EmptyAction).performClick()
        assertEquals(ArtifactsHomeIntent.ClearSearchAndFilters, intents.last())

        // All loaded rows hidden by Unsettled -> "No unsettled Artifacts" + "Show settled".
        state =
            loaded(
                rows = listOf(row("pt_done", "Done", settled = settledInfo, settledShown = true)),
                settledEnabled = true,
                statusFilter = ArtifactsHomeStatusFilter.Unsettled,
            )
        onNodeWithText("No unsettled Artifacts").assertExists()
        onNodeWithText("Show settled").performClick()
        assertEquals(ArtifactsHomeIntent.SetStatus(ArtifactsHomeStatusFilter.All), intents.last())

        // Status = Settled with only working rows -> "No settled Artifacts" + "See all".
        state =
            loaded(
                rows = listOf(row("pt_work", "Working", settledShown = true)),
                settledEnabled = true,
                statusFilter = ArtifactsHomeStatusFilter.Settled,
            )
        onNodeWithText("No settled Artifacts").assertExists()
        onNodeWithText("See all").performClick()
        assertEquals(ArtifactsHomeIntent.SetStatus(ArtifactsHomeStatusFilter.All), intents.last())

        state = state.copy(status = ArtifactsHomeStatus.Off)
        onNodeWithTag(ArtifactsHomeTags.Off).assertExists()
        onNodeWithText("Artifacts aren't on for this server").assertExists()
    }

    @Test
    fun contextMenuAlwaysOpensShowsFollowUnfollowAfterCopyLinkAndHidesFollowForForeignPublic() = runRobolectricComposeUiTest {
        val copied = mutableListOf<String>()
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext),
            ) {
                ArtifactsHomeScreen(
                    state =
                        loaded(
                            rows =
                                listOf(
                                    row("pt_followed", "Followed doc", following = true),
                                    row("pt_unfollowed", "Not followed", following = false),
                                    row("pt_foreign", "Foreign public", home = ArtifactHome.Public, following = false, fromAnotherServer = true),
                                ),
                            settledEnabled = false,
                        ),
                    view = ArtifactsHomeView.List,
                    readerId = "me",
                    serverName = "Northwind Labs",
                    wide = false,
                    onIntent = { intents += it },
                    onView = {},
                    onOpen = { opened += it.summary.id },
                    onBack = {},
                    onCopyLink = { copied += it.summary.id },
                )
            }
        }
        waitForIdle()

        // Context menu opens even when settledEnabled = false; Copy link sits above Unfollow.
        onAllNodesWithTag(ArtifactsHomeTags.Row)[0].performMouseInput {
            moveTo(Offset(width * 0.3f, height * 0.5f))
            press(MouseButton.Secondary)
            release(MouseButton.Secondary)
        }
        waitForIdle()
        onNodeWithTag(ArtifactsHomeTags.HomeMenu).assertIsDisplayed()
        val copyTop = onNodeWithTag(ArtifactsHomeTags.HomeMenuCopyLink).fetchSemanticsNode().boundsInRoot.top
        val followTop = onNodeWithTag(ArtifactsHomeTags.HomeMenuFollow).fetchSemanticsNode().boundsInRoot.top
        assertEquals(true, copyTop < followTop, "Copy link precedes Follow/Unfollow")
        assertEquals("Unfollow", onNodeWithTag(ArtifactsHomeTags.HomeMenuFollow).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text })
        onNodeWithTag(ArtifactsHomeTags.HomeMenuFollow).performClick()
        waitForIdle()
        assertEquals(ArtifactsHomeIntent.SetFollowing("pt_followed", false), intents.last())

        // Second row has following = false -> menu says Follow and dispatches SetFollowing(true).
        onAllNodesWithTag(ArtifactsHomeTags.Row)[1].performMouseInput {
            moveTo(Offset(width * 0.3f, height * 0.5f))
            press(MouseButton.Secondary)
            release(MouseButton.Secondary)
        }
        waitForIdle()
        assertEquals("Follow", onNodeWithTag(ArtifactsHomeTags.HomeMenuFollow).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text })
        onNodeWithTag(ArtifactsHomeTags.HomeMenuFollow).performClick()
        waitForIdle()
        assertEquals(ArtifactsHomeIntent.SetFollowing("pt_unfollowed", true), intents.last())

        // Cross-server public item omits Follow/Unfollow.
        onAllNodesWithTag(ArtifactsHomeTags.Row)[2].performMouseInput {
            moveTo(Offset(width * 0.3f, height * 0.5f))
            press(MouseButton.Secondary)
            release(MouseButton.Secondary)
        }
        waitForIdle()
        onNodeWithTag(ArtifactsHomeTags.HomeMenu).assertIsDisplayed()
        onAllNodesWithTag(ArtifactsHomeTags.HomeMenuFollow).assertCountEquals(0)
    }

    @Test
    fun relevantToMeUnfollowedShowsBadgeOnTileAndListRowAndClearsOnUndoOrAllView() = runRobolectricComposeUiTest {
        var state by mutableStateOf(
            loaded(
                rows = listOf(row("pt_a", "Plan", following = false), row("pt_b", "Active", following = true)),
                query = ArtifactsHomeQuery(relevant = true),
                unfollowedIds = setOf("pt_a"),
            ),
        )
        var view by mutableStateOf(ArtifactsHomeView.Grid)
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext),
            ) {
                ArtifactsHomeScreen(
                    state = state,
                    view = view,
                    readerId = "me",
                    serverName = "Northwind Labs",
                    wide = false,
                    onIntent = { intents += it },
                    onView = { view = it },
                    onOpen = { opened += it.summary.id },
                    onBack = {},
                )
            }
        }
        waitForIdle()

        // Under Relevant to me, Grid tile shows the Unfollowed badge.
        onAllNodesWithTag(ArtifactsHomeTags.UnfollowedTag, useUnmergedTree = true).assertCountEquals(1)
        onNodeWithText("Unfollowed", useUnmergedTree = true).assertExists()

        // List row shows the Unfollowed badge too.
        view = ArtifactsHomeView.List
        waitForIdle()
        onAllNodesWithTag(ArtifactsHomeTags.UnfollowedTag, useUnmergedTree = true).assertCountEquals(1)

        // Outside Relevant to me, Unfollowed badge is omitted.
        state = state.copy(query = ArtifactsHomeQuery(relevant = false))
        waitForIdle()
        onAllNodesWithTag(ArtifactsHomeTags.UnfollowedTag, useUnmergedTree = true).assertCountEquals(0)

        // Back under Relevant to me, after Undo sets following = true and clears unfollowedIds, badge disappears.
        state =
            state.copy(
                query = ArtifactsHomeQuery(relevant = true),
                rows = listOf(row("pt_a", "Plan", following = true), row("pt_b", "Active", following = true)),
                unfollowedIds = emptySet(),
            )
        waitForIdle()
        onAllNodesWithTag(ArtifactsHomeTags.UnfollowedTag, useUnmergedTree = true).assertCountEquals(0)
    }

    private val assistant =
        ArtifactsHomeActor(type = "agent", id = "a-1", name = "ottie", displayName = "Ottie", onBehalfOf = ArtifactsHomeBehalf("me", "Me"))

    private val settledInfo =
        ArtifactSettled(
            by = ArtifactsHomeActor("agent", "a-1", "remy", "Remy"),
            at = "2026-11-18T09:30:00.000Z",
            version = 2,
        )

    private fun row(
        id: String,
        title: String,
        author: ArtifactsHomeActor = ArtifactsHomeActor("user", "u-2", "kirby", "Kirby"),
        home: ArtifactHome = ArtifactHome.Conversation("proj-workspace", dm = false),
        news: ArtifactsHomeNews = ArtifactsHomeNews(),
        commentCount: Int = 4,
        updatedAt: String = "2026-11-18T08:00:00.000Z",
        settled: ArtifactSettled? = null,
        settledShown: Boolean = settled != null,
        following: Boolean? = null,
        fromAnotherServer: Boolean = false,
    ) =
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
                    home = home,
                    updatedAt = updatedAt,
                    commentCount = commentCount,
                    settled = settled,
                    settledShown = settledShown,
                    following = following,
                    fromAnotherServer = fromAnotherServer,
                ),
            author = author,
            openedAt = null,
            news = news,
            fromAnotherServer = fromAnotherServer,
        )

    private fun loaded(
        rows: List<ArtifactsHomeRow>,
        nextCursor: String? = null,
        query: ArtifactsHomeQuery = ArtifactsHomeQuery(sort = ArtifactsHomeSort.Opened),
        search: String = query.q,
        settledEnabled: Boolean = false,
        statusFilter: ArtifactsHomeStatusFilter = ArtifactsHomeStatusFilter.Unsettled,
        unfollowedIds: Set<String> = emptySet(),
    ) =
        ArtifactsHomeState(
            serverId = "s-1",
            status = ArtifactsHomeStatus.Ok,
            query = query,
            search = search,
            statusFilter = statusFilter,
            settledEnabled = settledEnabled,
            rows = rows,
            nextCursor = nextCursor,
            counts = ArtifactsHomeCounts(total = 24, filtered = 24, relevant = 2),
            unfollowedIds = unfollowedIds,
        )
}
