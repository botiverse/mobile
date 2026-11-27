package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentActivityEntry
import build.raft.app.data.AgentActivitySubagentGroup
import build.raft.app.data.AgentActivitySubagentState
import build.raft.app.data.groupAgentActivitySubagents
import build.raft.app.model.AgentActivitySubagentLineage
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Sub-agents view on a real composition (web AgentActivityLog, slock #proj-runtime:#41 and
 * #13029): a row opens its sub-agent, the raw log's spawn row and a sub-agent's badge open that
 * run, and Stop shows only on a running sub-agent for a viewer who may stop it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MembersAgentSubagentsUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val log = subagentsFixtureLog()
    private val groups = groupAgentActivitySubagents(log)

    @Test
    fun stopShowsOnlyOnARunningSubagentForAViewerWhoMayStopIt() =
        runRobolectricComposeUiTest {
            val stopped = mutableListOf<String>()
            var backs = 0
            val running = groups.first { it.state == AgentActivitySubagentState.Running }
            val done = groups.first { it.state == AgentActivitySubagentState.Done }
            var shown by mutableStateOf(running)
            var stop by mutableStateOf<MembersAgentSubagentStop?>(MembersAgentSubagentStop(null) { stopped += it })
            setContent {
                RaftDesignSystemTheme {
                    MembersAgentSubagentDetail(
                        group = shown,
                        timeContext = SubagentsFixtureTimeContext,
                        stop = stop,
                        onBack = { backs += 1 },
                        horizontalInset = 16.dp,
                        modifier = Modifier.fillMaxSize(),
                        nowEpochMillis = { SubagentsFixtureNowMillis },
                    ) { _, entry, last -> MembersAgentActivityRow(entry, SubagentsFixtureTimeContext, onOpenUrl = {}, last = last) }
                }
            }
            onNodeWithTag(MembersAgentSubagentStopTag).performClick()
            assertEquals(listOf("task-1"), stopped)
            onNodeWithContentDescription("Back to subagents").performClick()
            assertEquals(1, backs)

            stop = null
            waitForIdle()
            onAllNodesWithTag(MembersAgentSubagentStopTag).assertCountEquals(0)

            stop = MembersAgentSubagentStop(null) { stopped += it }
            shown = done
            waitForIdle()
            onAllNodesWithTag(MembersAgentSubagentStopTag).assertCountEquals(0)
        }

    /** slock #16323: subagent model appears in subagent list facts, detail metadata, and raw activity badge. */
    @Test
    fun subagentModelRendersInListDetailAndRawActivityBadge() =
        runRobolectricComposeUiTest {
            val modeledGroup =
                AgentActivitySubagentGroup(
                    key = "task-model-1",
                    parentToolUseId = "toolu_m1",
                    taskId = "task-model-1",
                    subagentType = "Explore",
                    model = "claude-haiku-4-5-20251001",
                    title = "Audit endpoints",
                    state = AgentActivitySubagentState.Done,
                    startedAtMillis = SubagentsFixtureNowMillis - 12_000L,
                    endedAtMillis = SubagentsFixtureNowMillis - 2_000L,
                    lastAtMillis = SubagentsFixtureNowMillis - 2_000L,
                    steps = 2,
                    lastToolName = "Grep",
                    items = emptyList(),
                )
            val modeledEntry =
                AgentActivityEntry(
                    id = "entry-model-1",
                    type = "tool_start",
                    title = "Searching code",
                    toolName = "Grep",
                    toolInput = "handlers",
                    timestamp = "2026-11-10T09:30:00.000Z",
                    isSubagent = true,
                    subagent =
                        AgentActivitySubagentLineage(
                            parentToolUseId = "toolu_m1",
                            subagentType = "Explore",
                            model = "claude-haiku-4-5-20251001",
                            taskId = "task-model-1",
                        ),
                )
            var showDetail by mutableStateOf(false)
            setContent {
                RaftDesignSystemTheme {
                    if (!showDetail) {
                        Column(Modifier.fillMaxSize()) {
                            MembersAgentSubagentsList(
                                groups = listOf(modeledGroup),
                                timeContext = SubagentsFixtureTimeContext,
                                onOpen = {},
                                contentPadding = PaddingValues(16.dp),
                                modifier = Modifier.weight(1f),
                                nowEpochMillis = { SubagentsFixtureNowMillis },
                            )
                            MembersAgentActivityRow(
                                activity = modeledEntry,
                                timeContext = SubagentsFixtureTimeContext,
                                onOpenUrl = {},
                                last = true,
                            )
                        }
                    } else {
                        MembersAgentSubagentDetail(
                            group = modeledGroup,
                            timeContext = SubagentsFixtureTimeContext,
                            stop = null,
                            onBack = {},
                            horizontalInset = 16.dp,
                            modifier = Modifier.fillMaxSize(),
                            nowEpochMillis = { SubagentsFixtureNowMillis },
                        ) { _, entry, last -> MembersAgentActivityRow(entry, SubagentsFixtureTimeContext, onOpenUrl = {}, last = last) }
                    }
                }
            }

            waitForIdle()
            onNodeWithText("Explore · claude-haiku-4-5-20251001 · 2 steps · took 10s", substring = true).assertIsDisplayed()
            onNodeWithText("Subagent: Explore · claude-haiku-4-5-20251001").assertIsDisplayed()

            showDetail = true
            waitForIdle()
            onNodeWithText("Model").assertIsDisplayed()
            onNodeWithText("claude-haiku-4-5-20251001").assertIsDisplayed()
        }
}
