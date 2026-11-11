package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentActivitySubagentState
import build.raft.app.data.groupAgentActivitySubagents
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
    fun aRowOpensItsSubagent() =
        runRobolectricComposeUiTest {
            val opened = mutableListOf<String>()
            setContent {
                RaftDesignSystemTheme {
                    MembersAgentSubagentsList(
                        groups = groups,
                        timeContext = SubagentsFixtureTimeContext,
                        onOpen = { opened += it },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.fillMaxSize(),
                        nowEpochMillis = { SubagentsFixtureNowMillis },
                    )
                }
            }
            onAllNodesWithTag(MembersAgentSubagentRowTag).assertCountEquals(3)
            onNodeWithText("Find the stale call sites of isSecondaryThreadId").performClick()
            assertEquals(listOf(groups.first { it.state == AgentActivitySubagentState.Running }.key), opened)
        }

    @Test
    fun theRawLogsSpawnRowAndASubagentsBadgeOpenThatRun() =
        runRobolectricComposeUiTest {
            val opened = mutableListOf<String>()
            val link = membersAgentActivitySubagentLink(log, groups) { opened += it }
            val explore = groups.first { it.subagentType == "Explore" }
            val spawn = log.first { it.callId == "toolu_a" }
            val inner = log.first { it.callId == "toolu_g" }
            setContent {
                RaftDesignSystemTheme {
                    Box(Modifier.height(400.dp)) {
                        Column {
                            MembersAgentActivityRow(spawn, SubagentsFixtureTimeContext, onOpenUrl = {}, subagentLink = link)
                            MembersAgentActivityRow(inner, SubagentsFixtureTimeContext, onOpenUrl = {}, subagentLink = link)
                        }
                    }
                }
            }
            onAllNodesWithTag(MembersAgentActivitySubagentOpenTag).assertCountEquals(2)
            onNodeWithText("Open subagent").performClick()
            onNodeWithText("Subagent: Explore").performClick()
            assertEquals(listOf(explore.key, explore.key), opened)
        }

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
}
