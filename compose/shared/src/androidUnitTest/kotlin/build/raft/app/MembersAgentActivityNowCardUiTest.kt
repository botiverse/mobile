package build.raft.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import build.raft.app.data.AgentActivityOverviewState
import build.raft.app.data.AgentActivityOverviewStatus
import build.raft.app.data.AgentActivityTurn
import build.raft.app.data.AgentActivityTurnOutcome
import build.raft.app.data.AppLocale
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.Presence
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.StringDesc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Activity overview's "now" card on a real composition (the production
 * [MembersAgentActivityOverview]), ported from web packages/web/tests/agentActivityOverview.behavior.test.tsx
 * at botiverse/slock 64fe42dfe1 (#13108): working with a running turn says "Working · for 3m …"
 * from the turn's start and the step it is on; online after a finished turn says when it ended,
 * with no step; working without a running turn says the step once, in the headline.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1500dp")
class MembersAgentActivityNowCardUiTest {
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

    // 2026-06-19 20:05 at UTC+8; the finished turn below ends at 20:01:41.
    private val timeContext =
        RaftTimeContext(
            localOffsetMinutes = 8 * 60,
            todayEpochDay = raftEpochDayForDate(2026, 6, 19),
            nowEpochMillis = 1_781_870_700_000L,
        )

    private val finished = turn(id = "t1", startedAt = 1_781_870_400_000L, endedAt = 1_781_870_501_000L)

    private fun turn(id: String, startedAt: Long, endedAt: Long, running: Boolean = false) =
        AgentActivityTurn(
            id = id,
            startedAtEpochMillis = startedAt,
            endedAtEpochMillis = endedAt,
            outcome = if (running) AgentActivityTurnOutcome.Unknown else AgentActivityTurnOutcome.Completed,
            trigger = null,
            actions = emptyList(),
            toolCalls = 0,
            problems = emptyList(),
            problemCount = 0,
            messagesSent = 0,
            lastReplyMessageId = null,
            running = running,
        )

    private fun now(status: AgentStatus, label: String) =
        MembersAgentActivityNow(
            label = label,
            indicator = raftAgentStatusIndicatorState(status, Presence.Active, statusLabel = label, external = false),
        )

    private fun ComposeUiTest.overview(now: MembersAgentActivityNow, running: AgentActivityTurn?) {
        val state =
            AgentActivityOverviewState(
                agentId = MemberId("agent-1"),
                status = AgentActivityOverviewStatus.Loaded,
                turns = listOf(finished),
                running = running,
            )
        setContent {
            MigrationFixtureHost {
                MembersAgentActivityOverview(
                    binding = remember { MembersAgentActivityOverviewBinding(state = MutableStateFlow(state), onIntent = {}) },
                    now = now,
                    hasRawActivity = true,
                    hasComputer = true,
                    timeContext = timeContext,
                    onShowRaw = {},
                    onOpenComputer = null,
                    onOpenUrl = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    /** The text of the one node tagged [tag], or null when there is none. */
    private fun ComposeUiTest.text(tag: String): String? {
        val nodes = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(nodes.size <= 1, "more than one $tag: ${nodes.size}")
        return nodes.singleOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
    }

    @Test
    fun workingSaysForHowLongFromTheRunningTurnsStartAndTheStep() =
        runRobolectricComposeUiTest {
            // The server read the turn long after it started: the card counts from its start.
            val startedAt = defaultNowMillis() - 3 * 60_000L - 5_000L
            overview(now(AgentStatus.Working, "Running tests"), turn("run", startedAt, endedAt = startedAt + 1_000L, running = true))
            val headline = text("agent-now-headline")
            assertTrue(headline != null && Regex("^Working · for 3m \\d+s$").matches(headline), "headline=$headline")
            assertEquals("Running tests", text("agent-now-step"))
        }

    @Test
    fun idleSaysWhenTheLastTurnEndedAndNoStep() =
        runRobolectricComposeUiTest {
            overview(now(AgentStatus.Online, "Idle"), running = null)
            assertEquals("Idle · last turn ended 20:01", text("agent-now-headline"))
            assertNull(text("agent-now-step"))
        }

    @Test
    fun workingWithoutARunningTurnSaysTheStepOnce() =
        runRobolectricComposeUiTest {
            overview(now(AgentStatus.Working, "Running tests"), running = null)
            assertEquals("Running tests", text("agent-now-headline"))
            assertNull(text("agent-now-step"), "the headline already says it")
        }
}
