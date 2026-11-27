package build.raft.app

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import build.raft.app.data.ArtifactExcerpt
import build.raft.app.data.ArtifactExcerptBlock
import build.raft.app.data.ArtifactExcerptListItem
import build.raft.app.data.ArtifactExcerptSpan
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactSettled
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.ArtifactsHomeActor
import build.raft.app.data.ArtifactsHomeBehalf
import build.raft.app.data.ArtifactsHomeCachedSnapshot
import build.raft.app.data.ArtifactsHomeConversation
import build.raft.app.data.ArtifactsHomeCounts
import build.raft.app.data.ArtifactsHomeIntent
import build.raft.app.data.ArtifactsHomeLoad
import build.raft.app.data.ArtifactsHomeNews
import build.raft.app.data.ArtifactsHomePage
import build.raft.app.data.ArtifactsHomePlace
import build.raft.app.data.ArtifactsHomeQuery
import build.raft.app.data.ArtifactsHomeRow
import build.raft.app.data.ArtifactsHomeScope
import build.raft.app.data.ArtifactsHomeSettlement
import build.raft.app.data.ArtifactsHomeSnapshotCache
import build.raft.app.data.ArtifactsHomeSort
import build.raft.app.data.ArtifactsHomeSource
import build.raft.app.data.ArtifactsHomeState
import build.raft.app.data.ArtifactsHomeStatus
import build.raft.app.data.ArtifactsHomeStatusFilter
import build.raft.app.data.ArtifactsHomeView
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.SharedArtifactsHomeStore
import build.raft.app.data.artifactsHomeSnapshotCacheKey
import build.raft.app.data.stringDescLocaleType
import kotlinx.coroutines.CompletableDeferred
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.StringDesc
import build.raft.app.thread.ArtifactSettledTagTestTag
import build.raft.app.thread.dispatch
import build.raft.designsystem.tokens.RaftUiThemeColors
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
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

    @Test
    fun gridTilesEqualizeRowHeightAlignMetaFooterAndWorkWithExcerptCovers() = runRobolectricComposeUiTest {
        val richExcerpt =
            ArtifactExcerpt(
                text = "Q4 release readiness checklist and performance budgets.",
                blocks =
                    listOf(
                        ArtifactExcerptBlock.Heading(1, listOf(ArtifactExcerptSpan("Release Readiness"))),
                        ArtifactExcerptBlock.Paragraph(listOf(ArtifactExcerptSpan("All mobile lanes pass ", bold = false), ArtifactExcerptSpan("zero jank", bold = true))),
                        ArtifactExcerptBlock.Quote(listOf(ArtifactExcerptSpan("Keep borders subtle and rows level."))),
                        ArtifactExcerptBlock.ListBlock(
                            listOf(
                                ArtifactExcerptListItem(level = 0, checked = true, spans = listOf(ArtifactExcerptSpan("Equalize row height"))),
                                ArtifactExcerptListItem(level = 0, checked = false, spans = listOf(ArtifactExcerptSpan("Pin footer row"))),
                            ),
                        ),
                        ArtifactExcerptBlock.Code(listOf("border = lineHairline")),
                        ArtifactExcerptBlock.Table(
                            header = listOf(listOf(ArtifactExcerptSpan("Lane")), listOf(ArtifactExcerptSpan("Status"))),
                            rows = listOf(listOf(listOf(ArtifactExcerptSpan("Android")), listOf(ArtifactExcerptSpan("Green")))),
                        ),
                    ),
            )
        val plainExcerpt = ArtifactExcerpt(text = "Short API specification summary for client callers.")
        val rows =
            listOf(
                row(
                    id = "pt_two_line",
                    title = "Q4 Mobile Release Readiness and Performance Review Plan",
                    kind = ArtifactKind.Markdown,
                    excerpt = richExcerpt,
                    commentCount = 3,
                    updatedAt = "2026-11-18T09:00:00.000Z",
                ),
                row(
                    id = "pt_one_line",
                    title = "API Notes",
                    kind = ArtifactKind.Markdown,
                    home = ArtifactHome.Public,
                    excerpt = plainExcerpt,
                    commentCount = 1,
                    updatedAt = "2026-11-18T08:00:00.000Z",
                ),
                row(
                    id = "pt_settled_short",
                    title = "Draft",
                    settled = settledInfo,
                    settledShown = true,
                    commentCount = 0,
                    updatedAt = "2026-11-18T07:00:00.000Z",
                ),
                row(
                    id = "pt_two_line_right",
                    title = "Cross-platform Sync Protocol Architecture Overview",
                    commentCount = 2,
                    updatedAt = "2026-11-18T06:00:00.000Z",
                ),
                row(
                    id = "pt_trailing_odd",
                    title = "Standalone Tile",
                    commentCount = 0,
                    updatedAt = "2026-11-18T05:00:00.000Z",
                ),
            )
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext),
            ) {
                ArtifactsHomeScreen(
                    state =
                        loaded(
                            rows = rows,
                            settledEnabled = true,
                            statusFilter = ArtifactsHomeStatusFilter.All,
                        ),
                    view = ArtifactsHomeView.Grid,
                    readerId = "me",
                    serverName = "Northwind Labs",
                    wide = false,
                    onIntent = { intents += it },
                    onView = {},
                    onOpen = { opened += it.summary.id },
                    onBack = {},
                    cover = { r, _, corner ->
                        val excerpt = r.summary.excerpt
                        if (excerpt != null) {
                            ArtifactsHomeTileCoverFrame(Alignment.TopStart) {
                                ArtifactCardExcerpt(excerpt, fitFrame = true, corner = corner)
                            }
                        } else {
                            ArtifactsHomeTilePlaceholder(r.summary.kind, corner = corner)
                        }
                    },
                )
            }
        }
        waitForIdle()

        val tiles = onAllNodesWithTag(ArtifactsHomeTags.Tile)
        tiles.assertCountEquals(5)
        val titles = onAllNodesWithTag(ArtifactsHomeTags.TileTitle, useUnmergedTree = true)
        titles.assertCountEquals(5)
        val metas = onAllNodesWithTag(ArtifactsHomeTags.TileMeta, useUnmergedTree = true)
        metas.assertCountEquals(5)

        // Row 1: 2-line title on left, 1-line title on right -> identical tile height and bottom-aligned meta row.
        val leftTitleBounds = titles[0].fetchSemanticsNode().boundsInRoot
        val rightTitleBounds = titles[1].fetchSemanticsNode().boundsInRoot
        assertTrue(
            leftTitleBounds.height > rightTitleBounds.height,
            "expected left title (2 lines) taller than right title (1 line), got ${leftTitleBounds.height} vs ${rightTitleBounds.height}",
        )
        val leftTileBounds = tiles[0].fetchSemanticsNode().boundsInRoot
        val rightTileBounds = tiles[1].fetchSemanticsNode().boundsInRoot
        assertEquals(leftTileBounds.height, rightTileBounds.height, "row 1 tiles must share identical height")
        assertEquals(leftTileBounds.top, rightTileBounds.top, "row 1 tiles must share top edge")
        assertEquals(leftTileBounds.bottom, rightTileBounds.bottom, "row 1 tiles must share bottom edge")

        val leftMetaBounds = metas[0].fetchSemanticsNode().boundsInRoot
        val rightMetaBounds = metas[1].fetchSemanticsNode().boundsInRoot
        assertEquals(leftMetaBounds.bottom, rightMetaBounds.bottom, "row 1 meta rows must align flush at the bottom")
        assertEquals(leftMetaBounds.top, rightMetaBounds.top, "row 1 meta rows must share top edge")

        // Row 2: 1-line title on left (with Settled tooltip badge), 2-line title on right -> equalized too.
        val row2LeftTitle = titles[2].fetchSemanticsNode().boundsInRoot
        val row2RightTitle = titles[3].fetchSemanticsNode().boundsInRoot
        assertTrue(
            row2RightTitle.height > row2LeftTitle.height,
            "expected row 2 right title (2 lines) taller than left title (1 line), got ${row2RightTitle.height} vs ${row2LeftTitle.height}",
        )
        val row2LeftTile = tiles[2].fetchSemanticsNode().boundsInRoot
        val row2RightTile = tiles[3].fetchSemanticsNode().boundsInRoot
        assertEquals(row2LeftTile.height, row2RightTile.height, "row 2 tiles must share identical height")
        assertEquals(
            metas[2].fetchSemanticsNode().boundsInRoot.bottom,
            metas[3].fetchSemanticsNode().boundsInRoot.bottom,
            "row 2 meta rows must align flush at the bottom",
        )

        // Row 3: single trailing tile stays single-column width (trailing Spacer occupies the empty cell).
        val trailingTile = tiles[4].fetchSemanticsNode().boundsInRoot
        assertEquals(leftTileBounds.width, trailingTile.width, "trailing odd tile must keep single-column width")

        // Opening the context menu on a grid tile inside IntrinsicSize.Max must not crash.
        tiles[0].performMouseInput {
            moveTo(Offset(width * 0.5f, height * 0.5f))
            press(MouseButton.Secondary)
            release(MouseButton.Secondary)
        }
        waitForIdle()
        onNodeWithTag(ArtifactsHomeTags.HomeMenu).assertIsDisplayed()
    }

    @Test
    fun gridColumnCountMatchesWebContainerBreakpoints() {
        assertEquals(2, artifactsHomeGridColumnCount(160f))
        assertEquals(2, artifactsHomeGridColumnCount(390f))
        assertEquals(2, artifactsHomeGridColumnCount(439f))
        assertEquals(2, artifactsHomeGridColumnCount(440f))
        assertEquals(3, artifactsHomeGridColumnCount(648f))
        assertEquals(3, artifactsHomeGridColumnCount(800f))
        assertEquals(6, artifactsHomeGridColumnCount(1280f))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h780dp-xxhdpi")
    fun gridTileBordersUseLineHairlineNotLineSeam() {
        val richExcerpt =
            ArtifactExcerpt(
                text = "Q4 release readiness checklist and performance budgets.",
                blocks =
                    listOf(
                        ArtifactExcerptBlock.Heading(1, listOf(ArtifactExcerptSpan("Release Plan"))),
                        ArtifactExcerptBlock.Paragraph(listOf(ArtifactExcerptSpan("Verify grid card row heights and hairline border parity."))),
                        ArtifactExcerptBlock.ListBlock(
                            listOf(
                                ArtifactExcerptListItem(level = 0, checked = true, spans = listOf(ArtifactExcerptSpan("Subtle hairline border"))),
                                ArtifactExcerptListItem(level = 0, checked = true, spans = listOf(ArtifactExcerptSpan("Equal card height"))),
                            ),
                        ),
                    ),
            )
        val gridRows =
            listOf(
                row(
                    id = "pt_1",
                    title = "Q4 Mobile Release Readiness and Performance Review Plan",
                    kind = ArtifactKind.Markdown,
                    excerpt = richExcerpt,
                    commentCount = 3,
                    updatedAt = "2026-11-18T09:00:00.000Z",
                ),
                row(
                    id = "pt_2",
                    title = "API Notes",
                    kind = ArtifactKind.Markdown,
                    home = ArtifactHome.Public,
                    excerpt = ArtifactExcerpt(text = "Concise overview of REST and realtime contracts for mobile clients."),
                    commentCount = 1,
                    updatedAt = "2026-11-18T08:00:00.000Z",
                ),
                row(
                    id = "pt_3",
                    title = "Design Tokens",
                    commentCount = 0,
                    updatedAt = "2026-11-18T07:00:00.000Z",
                ),
                row(
                    id = "pt_4",
                    title = "Cross-platform Sync Protocol Architecture Overview",
                    author = assistant,
                    commentCount = 5,
                    updatedAt = "2026-11-18T06:00:00.000Z",
                ),
            )

        val light = RaftUiThemeColors.RaftLight
        val expectedHairlineOnCard = light.lineHairline.compositeOver(light.layerCard)
        val seamOnCard = light.lineSeam.compositeOver(light.layerCard)
        val expectedStrongOnSelected = light.lineStrong.compositeOver(light.fillCellSelected)

        var firstCoverBounds: androidx.compose.ui.geometry.Rect? = null
        var secondCoverBounds: androidx.compose.ui.geometry.Rect? = null
        RaftRobolectricScreenshot.render(RaftAppAppearance.Light, activities, content = {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext),
            ) {
                Box(Modifier.fillMaxSize()) {
                    ArtifactsHomeScreen(
                        state = loaded(rows = gridRows),
                        view = ArtifactsHomeView.Grid,
                        readerId = "me",
                        serverName = "Northwind Labs",
                        wide = false,
                        onIntent = {},
                        onView = {},
                        onOpen = {},
                        onBack = {},
                        currentArtifactId = "pt_2",
                        cover = { r, _, corner ->
                            Box(
                                Modifier.onGloballyPositioned { coords ->
                                    if (r.summary.id == "pt_1") firstCoverBounds = coords.boundsInRoot()
                                    if (r.summary.id == "pt_2") secondCoverBounds = coords.boundsInRoot()
                                },
                            ) {
                                val excerpt = r.summary.excerpt
                                if (excerpt != null) {
                                    ArtifactsHomeTileCoverFrame(Alignment.TopStart) {
                                        ArtifactCardExcerpt(excerpt, fitFrame = true, corner = corner)
                                    }
                                } else {
                                    ArtifactsHomeTilePlaceholder(r.summary.kind, corner = corner)
                                }
                            }
                        },
                    )
                }
            }
        }) { activity, bitmap ->
            val density = activity.resources.displayMetrics.density
            val leftCover = requireNotNull(firstCoverBounds) { "pt_1 cover bounds not measured" }
            val rightCover = requireNotNull(secondCoverBounds) { "pt_2 cover bounds not measured" }
            // Border is 1dp wide immediately outside the inset cover box; sample its center pixel.
            val unselectedBorderPixel = bitmap.getPixel((leftCover.left - density * 0.5f).toInt(), ((leftCover.top + leftCover.bottom) / 2f).roundToInt())
            assertPixelNear(expectedHairlineOnCard, unselectedBorderPixel, "unselected tile border must use lineHairline")
            assertTrue(
                AndroidColor.red(unselectedBorderPixel) - AndroidColor.red(seamOnCard.toArgb()) > 40,
                "unselected tile border must be visibly lighter than lineSeam",
            )
            val selectedBorderPixel = bitmap.getPixel((rightCover.left - density * 0.5f).toInt(), ((rightCover.top + rightCover.bottom) / 2f).roundToInt())
            assertPixelNear(expectedStrongOnSelected, selectedBorderPixel, "selected tile border must keep lineStrong")

            if (System.getenv("RAFT_CAPTURE_ARTIFACTS_GRID_VERIFY") == "true") {
                val out = File("build/reports/home-screenshots/artifacts-home-grid-verify-selected-light.png")
                out.parentFile?.mkdirs()
                out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }

        if (System.getenv("RAFT_CAPTURE_ARTIFACTS_GRID_VERIFY") == "true") {
            listOf(
                RaftAppAppearance.Light to "artifacts-home-grid-verify-light.png",
                RaftAppAppearance.Dark to "artifacts-home-grid-verify-dark.png",
            ).forEach { (appearance, fileName) ->
                RaftRobolectricScreenshot.render(appearance, activities, content = {
                    val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
                    CompositionLocalProvider(
                        LocalActivity provides RaftPageActivity(pager),
                        LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext),
                    ) {
                        ArtifactsHomeScreen(
                            state = loaded(rows = gridRows),
                            view = ArtifactsHomeView.Grid,
                            readerId = "me",
                            serverName = "Northwind Labs",
                            wide = false,
                            onIntent = {},
                            onView = {},
                            onOpen = {},
                            onBack = {},
                            cover = { r, _, corner ->
                                val excerpt = r.summary.excerpt
                                if (excerpt != null) {
                                    ArtifactsHomeTileCoverFrame(Alignment.TopStart) {
                                        ArtifactCardExcerpt(excerpt, fitFrame = true, corner = corner)
                                    }
                                } else {
                                    ArtifactsHomeTilePlaceholder(r.summary.kind, corner = corner)
                                }
                            },
                        )
                    }
                }) { _, bitmap ->
                    val out = File("build/reports/home-screenshots/$fileName")
                    out.parentFile?.mkdirs()
                    out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
    }

    @Test
    fun gridSkeletonBordersUseLineHairlineNotLineSeam() {
        val light = RaftUiThemeColors.RaftLight
        val expectedHairlineOnCard = light.lineHairline.compositeOver(light.layerCard)
        val seamOnCard = light.lineSeam.compositeOver(light.layerCard)

        RaftRobolectricScreenshot.render(RaftAppAppearance.Light, activities, content = {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext),
            ) {
                build.raft.app.components.RaftDesignSystemTheme {
                    ArtifactsHomeSkeleton(
                        view = ArtifactsHomeView.Grid,
                        wide = false,
                        grouped = false,
                        horizontalPadding = ArtifactsHomeMargin,
                        animated = false,
                    )
                }
            }
        }) { activity, bitmap ->
            val density = activity.resources.displayMetrics.density
            val borderX = (ArtifactsHomeMargin.value * density + density * 0.5f).toInt()
            val expectedR = AndroidColor.red(expectedHairlineOnCard.toArgb())
            val seamR = AndroidColor.red(seamOnCard.toArgb())
            var foundHairline = false
            var foundSeam = false
            for (y in 0 until bitmap.height) {
                val px = bitmap.getPixel(borderX, y)
                val r = AndroidColor.red(px)
                if (abs(r - expectedR) <= 10 && abs(AndroidColor.green(px) - r) <= 6) foundHairline = true
                if (abs(r - seamR) <= 10 && abs(AndroidColor.green(px) - r) <= 6) foundSeam = true
            }
            assertTrue(foundHairline, "grid skeleton tile border must render with lineHairline")
            assertTrue(!foundSeam, "grid skeleton tile border must not render with darker lineSeam")
        }
    }

    @Test
    fun warmSnapshotCacheRendersCachedRowsOnFrameOneWithoutSkeletonFlash() = runRobolectricComposeUiTest {
        val cache = ArtifactsHomeSnapshotCache()
        val cachedRow = row("pt_cached", "Launch plan.html", updatedAt = "2026-11-18T08:00:00.000Z")
        val cacheKey =
            artifactsHomeSnapshotCacheKey(
                environmentId = "https://api.example",
                readerId = "me",
                serverId = "s-1",
                query = ArtifactsHomeQuery(),
            )!!
        cache.put(
            cacheKey,
            ArtifactsHomeCachedSnapshot(
                rows = listOf(cachedRow),
                nextCursor = null,
                counts = ArtifactsHomeCounts(total = 1, filtered = 1, relevant = 0),
                countsNarrowed = false,
                rowsByOpened = true,
                settledEnabled = false,
                knownAuthors = emptyMap(),
            ),
        )
        val pendingRefresh = CompletableDeferred<ArtifactsHomeLoad<ArtifactsHomePage>>()
        val hangingApi =
            object : ArtifactsHomeSource {
                override suspend fun page(
                    serverId: String,
                    query: ArtifactsHomeQuery,
                    cursor: String?,
                    priority: DataRequestPriority,
                ): ArtifactsHomeLoad<ArtifactsHomePage> = pendingRefresh.await()

                override suspend fun authors(serverId: String, q: String): ArtifactsHomeLoad<List<ArtifactsHomeActor>> =
                    ArtifactsHomeLoad.Ok(emptyList())

                override suspend fun conversations(serverId: String, q: String): ArtifactsHomeLoad<List<ArtifactsHomeConversation>> =
                    ArtifactsHomeLoad.Ok(emptyList())

                override suspend fun setSettled(serverId: String, artifactId: String, settled: Boolean): ArtifactsHomeLoad<ArtifactsHomeSettlement> =
                    ArtifactsHomeLoad.Ok(ArtifactsHomeSettlement(null))

                override suspend fun setFollowing(serverId: String, artifactId: String, following: Boolean): ArtifactsHomeLoad<Boolean> =
                    ArtifactsHomeLoad.Ok(following)
            }

        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            val scope = rememberCoroutineScope()
            val store =
                remember {
                    SharedArtifactsHomeStore(
                        api = hangingApi,
                        scope = scope,
                        snapshotCache = cache,
                        environmentIdProvider = { "https://api.example" },
                        initialServerId = "s-1",
                        initialReaderId = "me",
                    )
                }
            LaunchedEffect(store) {
                store.dispatch(ArtifactsHomeIntent.Start("s-1", "me"))
            }
            val state by store.state.collectAsState()
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext),
            ) {
                ArtifactsHomeScreen(
                    state = state,
                    view = ArtifactsHomeView.List,
                    readerId = "me",
                    serverName = "Northwind Labs",
                    wide = false,
                    onIntent = store::dispatch,
                    onView = {},
                    onOpen = {},
                    onBack = {},
                )
            }
        }
        waitForIdle()
        // Frame 1 immediately displays the cached artifact row with zero skeleton placeholders while refresh is pending.
        onAllNodesWithTag(ArtifactsHomeTags.Skeleton).assertCountEquals(0)
        onAllNodesWithTag(ArtifactsHomeTags.Row).assertCountEquals(1)
        onNodeWithText("Launch plan", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun assertPixelNear(expected: Color, actualArgb: Int, label: String, maxDelta: Int = 10) {
        val expectedArgb = expected.toArgb()
        val dr = abs(AndroidColor.red(expectedArgb) - AndroidColor.red(actualArgb))
        val dg = abs(AndroidColor.green(expectedArgb) - AndroidColor.green(actualArgb))
        val db = abs(AndroidColor.blue(expectedArgb) - AndroidColor.blue(actualArgb))
        assertTrue(
            dr <= maxDelta && dg <= maxDelta && db <= maxDelta,
            "$label: expected rgb(${AndroidColor.red(expectedArgb)},${AndroidColor.green(expectedArgb)},${AndroidColor.blue(expectedArgb)}) " +
                "got rgb(${AndroidColor.red(actualArgb)},${AndroidColor.green(actualArgb)},${AndroidColor.blue(actualArgb)})",
        )
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
        kind: ArtifactKind = ArtifactKind.Html,
        author: ArtifactsHomeActor = ArtifactsHomeActor("user", "u-2", "kirby", "Kirby"),
        home: ArtifactHome = ArtifactHome.Conversation("proj-workspace", dm = false),
        news: ArtifactsHomeNews = ArtifactsHomeNews(),
        commentCount: Int = 4,
        updatedAt: String = "2026-11-18T08:00:00.000Z",
        settled: ArtifactSettled? = null,
        settledShown: Boolean = settled != null,
        following: Boolean? = null,
        fromAnotherServer: Boolean = false,
        excerpt: ArtifactExcerpt? = null,
    ) =
        ArtifactsHomeRow(
            summary =
                ArtifactSummary(
                    id = id,
                    title = title,
                    description = null,
                    kind = kind,
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
                    excerpt = excerpt,
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
