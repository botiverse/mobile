package build.raft.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.AgentActivityActionCount
import build.raft.app.data.AgentActivityActionKind
import build.raft.app.data.AgentActivityOverviewState
import build.raft.app.data.AgentActivityOverviewStatus
import build.raft.app.data.AgentActivityTurn
import build.raft.app.data.AgentActivityTurnOutcome
import build.raft.app.data.AgentActivityTurnUsageLimit
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.StringDesc
import kotlinx.coroutines.flow.MutableStateFlow
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
 * A run of usage-limit retries in the Activity overview on a real composition (the production
 * [MembersAgentActivityOverview]), ported from web packages/web/tests/agentActivityOverview.behavior.test.tsx
 * "hourly usage-limit retries read as one wait with the reset time, each retry still a click away"
 * (botiverse/slock f273c7d6ff, #13050): the retries are one entry with the reset and the count,
 * the work before it its own turn, and Show each turn lists every retry, each ending when it failed.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1500dp")
class MembersAgentActivityQuietFailureRunUiTest {
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

    private val hour = 3_600_000L

    /** The web test's `at(hours)`: `Date.UTC(2026, 10, 4, 23, 49, 0)` (07:49 at UTC+8) plus [hours]. */
    private fun at(hours: Int) = 1_793_836_140_000L + hours * hour

    /** `Date.UTC(2026, 10, 8, 7, 0, 0)`: 11-08 15:00 at UTC+8. */
    private val resetsAt = 1_794_121_200_000L

    private fun turn(id: String, startedAt: Long, endedAt: Long) =
        AgentActivityTurn(
            id = id,
            startedAtEpochMillis = startedAt,
            endedAtEpochMillis = endedAt,
            outcome = AgentActivityTurnOutcome.Failed,
            trigger = null,
            actions = emptyList(),
            toolCalls = 0,
            problems = emptyList(),
            problemCount = 0,
            messagesSent = 0,
            lastReplyMessageId = null,
        )

    private fun retry(hours: Int) =
        turn("retry-$hours", at(hours), at(hours) + 1_000L).copy(usageLimit = AgentActivityTurnUsageLimit(resetsAt))

    private val work =
        turn("work", at(-1), at(-1) + 60_000L).copy(
            outcome = AgentActivityTurnOutcome.Completed,
            actions = listOf(AgentActivityActionCount(AgentActivityActionKind.RunCommand, 1)),
            toolCalls = 1,
            messagesSent = 1,
            lastReplyMessageId = MessageId("reply-1"),
        )

    private fun ComposeUiTest.overview() {
        val state =
            AgentActivityOverviewState(
                agentId = MemberId("agent-1"),
                status = AgentActivityOverviewStatus.Loaded,
                // Newest first, as the server pages them.
                turns = listOf(retry(2), retry(1), retry(0), work),
            )
        setContent {
            MigrationFixtureHost {
                MembersAgentActivityOverview(
                    binding = remember { MembersAgentActivityOverviewBinding(state = MutableStateFlow(state), onIntent = {}) },
                    now = MembersAgentActivityNow(label = "Offline", indicator = null),
                    hasRawActivity = true,
                    hasComputer = true,
                    timeContext =
                        RaftTimeContext(
                            localOffsetMinutes = 8 * 60,
                            todayEpochDay = raftEpochDayForDate(2026, 11, 5),
                            nowEpochMillis = at(3),
                        ),
                    onShowRaw = {},
                    onOpenComputer = null,
                    onOpenUrl = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    /** Every text inside the run's entry, in order. */
    private fun ComposeUiTest.runText(): String =
        onAllNodes(hasAnyAncestor(hasTestTag(MembersAgentActivityQuietFailureRunTag)), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } }
            .joinToString(" | ")

    private fun ComposeUiTest.turnsInTheRun(): Int =
        onAllNodes(
            hasTestTag(MembersAgentActivityTurnSectionTag) and hasAnyAncestor(hasTestTag(MembersAgentActivityQuietFailureRunTag)),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size

    @Test
    fun hourlyUsageLimitRetriesReadAsOneWaitEachRetryStillATapAway() =
        runRobolectricComposeUiTest {
            overview()
            assertEquals(1, onAllNodesWithTag(MembersAgentActivityQuietFailureRunTag, useUnmergedTree = true).fetchSemanticsNodes().size)
            val text = runText()
            assertTrue(text.contains("Usage limit reached · resets 11-08 15:00 · 3 attempts"), text)
            // The last retry's badges, and the span from the first start to the last end.
            assertTrue(text.contains("Ended with an error"), text)
            assertTrue(text.contains("07:49–09:49"), text)
            // The work before it is its own turn; the retries are not listed one by one.
            assertEquals(1, onAllNodesWithTag(MembersAgentActivityTurnSectionTag, useUnmergedTree = true).fetchSemanticsNodes().size)
            assertEquals(0, turnsInTheRun())

            onNodeWithText("Show each turn").performClick()
            assertEquals(3, turnsInTheRun())
            // Each retry ends when it failed, not when the next one started.
            assertTrue(runText().contains("took 1s"), runText())

            onNodeWithText("Hide turns").performClick()
            assertEquals(0, turnsInTheRun())
        }
}
