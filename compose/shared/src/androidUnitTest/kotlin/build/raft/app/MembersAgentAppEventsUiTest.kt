package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentAppEvent
import build.raft.app.data.AgentAppEventDetail
import build.raft.app.data.AgentAppEventPage
import build.raft.app.data.AgentAppEventStatus
import build.raft.app.data.AppLocale
import build.raft.app.data.DataResult
import build.raft.app.data.stringDescLocaleType
import build.raft.app.members.MembersAgentAppEventsSection
import build.raft.app.members.MembersAgentAppEventsState
import build.raft.app.model.MemberId
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
 * Robolectric Compose UI tests for [MembersAgentAppEventsSection] (#proj-mobile:#386 P1-5):
 * null detailLoader settles cleanly to the failure banner instead of hanging in "Loading…",
 * and hoisted [MembersAgentAppEventsState] retains loaded events and expanded payloads across
 * TabPager horizontal page disposal.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class MembersAgentAppEventsUiTest {
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

    private val timeContext =
        RaftTimeContext(
            localOffsetMinutes = 0,
            todayEpochDay = raftEpochDayForDate(2026, 11, 25),
            nowEpochMillis = 1_795_600_000_000L,
        )

    private val sampleEvent =
        AgentAppEvent(
            id = "evt-1",
            appClientId = "stamp",
            appName = "Stamp",
            kind = "pr.merged",
            summary = "PR #42 merged",
            status = AgentAppEventStatus.Delivered,
            createdAt = "2026-11-25T10:00:00Z",
        )

    @Test
    fun nullDetailLoaderSettlesToErrorBannerInsteadOfStuckLoading(): Unit =
        runRobolectricComposeUiTest {
            setContent {
                RaftDesignSystemTheme {
                    MembersAgentAppEventsSection(
                        agentId = MemberId("agent-1"),
                        loader = { _, _, _ -> DataResult.Content(AgentAppEventPage(listOf(sampleEvent), nextCursor = null)) },
                        detailLoader = null,
                        timeContext = timeContext,
                    )
                }
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("PR #42 merged", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("PR #42 merged", useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("Failed to load the event", useUnmergedTree = true).assertIsDisplayed()
            onAllNodesWithText("Loading…", useUnmergedTree = true).assertCountEquals(0)
        }

    @Test
    fun hoistedAppEventsStateRetainsEventsAndExpandedPayloadAcrossTabDisposal(): Unit =
        runRobolectricComposeUiTest {
            var listCalls = 0
            var detailCalls = 0
            var showAppsTab by mutableStateOf(true)
            setContent {
                val eventsState = remember { MembersAgentAppEventsState() }
                RaftDesignSystemTheme {
                    if (showAppsTab) {
                        MembersAgentAppEventsSection(
                            agentId = MemberId("agent-1"),
                            loader = { _, _, _ ->
                                listCalls += 1
                                DataResult.Content(AgentAppEventPage(listOf(sampleEvent), nextCursor = null))
                            },
                            detailLoader = { _, id ->
                                detailCalls += 1
                                DataResult.Content(AgentAppEventDetail(id, """{"merged":true}"""))
                            },
                            timeContext = timeContext,
                            state = eventsState,
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("PR #42 merged", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(1, listCalls)

            onNodeWithText("PR #42 merged", useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("""{"merged":true}""", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(1, detailCalls)

            // Dispose Apps tab (TabPager horizontal swipe away) and remount it.
            showAppsTab = false
            waitForIdle()
            onAllNodesWithText("PR #42 merged", useUnmergedTree = true).assertCountEquals(0)

            showAppsTab = true
            waitForIdle()
            onNodeWithText("PR #42 merged", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("""{"merged":true}""", useUnmergedTree = true).assertIsDisplayed()
            onAllNodesWithText("Loading…", useUnmergedTree = true).assertCountEquals(0)
            assertEquals(1, listCalls, "re-entering Apps tab with hoisted state must not refetch list")
            assertEquals(1, detailCalls, "re-entering Apps tab with hoisted state must not refetch expanded payload")
        }
}
