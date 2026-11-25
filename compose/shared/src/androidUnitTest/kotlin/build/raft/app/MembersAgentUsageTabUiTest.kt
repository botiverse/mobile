package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.AppLocale
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftAgentsApi
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.MemberId
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
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
 * Robolectric Compose UI tests for the Agent Profile Usage tab (`MembersAgentUsageTab`,
 * `botiverse/slock` `AgentUsageTab.tsx`, `agent_usage_v0`).
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class MembersAgentUsageTabUiTest {
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
            todayEpochDay = raftEpochDayForDate(2026, 3, 14),
            nowEpochMillis = 1_773_489_600_000L, // 2026-03-14T12:00:00Z
        )

    private fun readyPayload(from: String = "2026-03-01", to: String = "2026-03-14"): String =
        """
        {
          "from": "$from",
          "to": "$to",
          "as_of": "2026-03-14T12:00:00Z",
          "since": "2026-03-01",
          "visible_agents": 1,
          "agents": [
            {
              "agent_id": "agent-1",
              "since": "2026-03-01",
              "usage_reported": true,
              "computer_reports_usage": true,
              "runtime_reports_usage": true
            }
          ],
          "rows": [
            {
              "day": "2026-03-05",
              "agent_id": "agent-1",
              "runtime": "claude",
              "model": "claude-sonnet-4-6",
              "input_new": 800,
              "cache_read": 1000,
              "cache_write": 0,
              "output": 200,
              "reasoning_output": null,
              "turns": 2
            },
            {
              "day": "2026-03-12",
              "agent_id": "agent-1",
              "runtime": "claude",
              "model": "claude-sonnet-4-6",
              "input_new": 1000,
              "cache_read": 4000,
              "cache_write": 200,
              "output": 300,
              "reasoning_output": 100,
              "turns": 3
            },
            {
              "day": "2026-03-14",
              "agent_id": "agent-1",
              "runtime": "claude",
              "model": "claude-sonnet-4-6",
              "input_new": 500,
              "cache_read": 2000,
              "cache_write": null,
              "output": 100,
              "reasoning_output": null,
              "turns": 1
            }
          ],
          "context": [
            {
              "agent_id": "agent-1",
              "context_used": 64000,
              "context_window": 200000,
              "at": "2026-03-14T11:55:00Z"
            }
          ]
        }
        """.trimIndent()

    @Test
    fun readyStateRendersSummaryTogglesCacheReadsAndPicksDay(): Unit =
        runRobolectricComposeUiTest {
            val api = FakeAgentUsageApi { from, to -> HomeNetworkResponse(code = 200, bodyText = readyPayload(from, to)) }
            val client = RaftAgentsApiClient(api)
            setContent {
                MembersAgentUsageTab(
                    agentId = MemberId("agent-1"),
                    serverId = ServerId("srv-1"),
                    agentsApiClient = client,
                    timeContext = timeContext,
                    onOpenComputer = {},
                )
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(AgentUsageTestTags.Headline, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            // Default: Cache reads OFF -> 7-day chart tokens = (1000+200+300) + (500+100) = 2100 -> "2.1K"
            onNodeWithTag(AgentUsageTestTags.Headline, useUnmergedTree = true).assertTextContains("2.1K")
            onNodeWithTag(AgentUsageTestTags.Change).assertTextContains("+50% vs prior period")
            onNodeWithTag(AgentUsageTestTags.Reasoning, useUnmergedTree = true).assertTextContains("Includes 100 reasoning")
            onNodeWithTag(AgentUsageTestTags.ContextMeter, useUnmergedTree = true).assertExists()
            onNodeWithTag(AgentUsageTestTags.Models, useUnmergedTree = true).assertExists()

            // Toggle Cache reads ON -> 2100 + 6000 = 8100 -> "8.1K"
            onNodeWithTag(AgentUsageTestTags.CacheReadsSwitch, useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithTag(AgentUsageTestTags.Headline, useUnmergedTree = true).assertTextContains("8.1K")

            // Pick a past day (2026-03-12): updates headline, hides context meter, shows weekday comparison
            onNodeWithTag("${AgentUsageTestTags.BarPrefix}2026-03-12", useUnmergedTree = true).performClick()
            waitForIdle()
            // 2026-03-12 with cache reads ON = 1000+200+300+4000 = 5500 -> "5.5K"
            onNodeWithTag(AgentUsageTestTags.Headline, useUnmergedTree = true).assertTextContains("5.5K")
            onAllNodesWithTag(AgentUsageTestTags.ContextMeter, useUnmergedTree = true).assertCountEquals(0)
            onNodeWithTag(AgentUsageTestTags.ContextWindow, useUnmergedTree = true).assertExists()

            // Tap the same bar again to unpick and return to full 7-day range
            onNodeWithTag("${AgentUsageTestTags.BarPrefix}2026-03-12", useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithTag(AgentUsageTestTags.Headline, useUnmergedTree = true).assertTextContains("8.1K")
            onNodeWithTag(AgentUsageTestTags.ContextMeter, useUnmergedTree = true).assertExists()
        }

    @Test
    fun switchingPeriodRequestsNewWindow(): Unit =
        runRobolectricComposeUiTest {
            val api = FakeAgentUsageApi { from, to -> HomeNetworkResponse(code = 200, bodyText = readyPayload(from, to)) }
            val client = RaftAgentsApiClient(api)
            setContent {
                MembersAgentUsageTab(
                    agentId = MemberId("agent-1"),
                    serverId = ServerId("srv-1"),
                    agentsApiClient = client,
                    timeContext = timeContext,
                    onOpenComputer = {},
                )
            }
            waitUntil(timeoutMillis = 5_000) { api.requests.isNotEmpty() }
            assertEquals("2026-03-01" to "2026-03-14", api.requests.first())

            // Open Period Select and choose 14 days
            onNodeWithTag(AgentUsageTestTags.PeriodTrigger, useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithTag("${AgentUsageTestTags.PeriodMenuOptionPrefix}14", useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = 5_000) { api.requests.size >= 2 }
            assertEquals("2026-02-15" to "2026-03-14", api.requests.last())
        }

    @Test
    fun unreportedStatesGateOpenComputerButtonStrictly(): Unit =
        runRobolectricComposeUiTest {
            var openedComputer = 0
            val computerTooOldJson =
                """{"from":"2026-03-01","to":"2026-03-14","as_of":"2026-03-14T12:00:00Z","since":null,"visible_agents":1,"agents":[{"agent_id":"agent-1","since":null,"usage_reported":false,"computer_reports_usage":false,"runtime_reports_usage":true}],"rows":[],"context":[]}"""
            val runtimeNoUsageJson =
                """{"from":"2026-03-01","to":"2026-03-14","as_of":"2026-03-14T12:00:00Z","since":null,"visible_agents":1,"agents":[{"agent_id":"agent-1","since":null,"usage_reported":false,"computer_reports_usage":true,"runtime_reports_usage":false}],"rows":[],"context":[]}"""
            var mode by mutableStateOf(0)
            val api =
                FakeAgentUsageApi { _, _ ->
                    HomeNetworkResponse(code = 200, bodyText = if (mode == 0) computerTooOldJson else runtimeNoUsageJson)
                }
            val client = RaftAgentsApiClient(api)
            setContent {
                val activeAgent = remember(mode) { MemberId("agent-1") }
                MembersAgentUsageTab(
                    agentId = activeAgent,
                    serverId = ServerId(if (mode == 0) "srv-old" else "srv-rt"),
                    agentsApiClient = client,
                    timeContext = timeContext,
                    onOpenComputer = { openedComputer += 1 },
                )
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag("agent-usage-unsupported", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Update Raft Computer to see usage.", useUnmergedTree = true).assertExists()
            onNodeWithTag(AgentUsageTestTags.OpenComputerButton, useUnmergedTree = true).performClick()
            assertEquals(1, openedComputer)

            // Switch to runtime_no_usage: no Open computer button
            mode = 1
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag("agent-usage-runtime-no-usage", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("This agent's runtime doesn't report token usage, so there is none to show.", useUnmergedTree = true)
                .assertExists()
            onAllNodesWithTag(AgentUsageTestTags.OpenComputerButton, useUnmergedTree = true).assertCountEquals(0)
        }

    @Test
    fun hoistedUsageTabStateRetainsLoadedSummaryAcrossTabDisposalWithoutRefetchOrLoadingFlash(): Unit =
        runRobolectricComposeUiTest {
            val api = FakeAgentUsageApi { from, to -> HomeNetworkResponse(code = 200, bodyText = readyPayload(from, to)) }
            val client = RaftAgentsApiClient(api)
            var showUsageTab by mutableStateOf(true)
            setContent {
                val usageState = remember { MembersAgentUsageTabState() }
                if (showUsageTab) {
                    MembersAgentUsageTab(
                        agentId = MemberId("agent-1"),
                        serverId = ServerId("srv-1"),
                        agentsApiClient = client,
                        timeContext = timeContext,
                        onOpenComputer = {},
                        state = usageState,
                    )
                }
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(AgentUsageTestTags.Headline, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(1, api.requests.size)
            onNodeWithTag(AgentUsageTestTags.CacheReadsSwitch, useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithTag(AgentUsageTestTags.Headline, useUnmergedTree = true).assertTextContains("8.1K")

            // Dispose Usage tab (simulating TabPager swipe to Overview) and remount it.
            showUsageTab = false
            waitForIdle()
            onAllNodesWithTag(AgentUsageTestTags.Headline, useUnmergedTree = true).assertCountEquals(0)

            showUsageTab = true
            waitForIdle()
            onNodeWithTag(AgentUsageTestTags.Headline, useUnmergedTree = true).assertTextContains("8.1K")
            onAllNodesWithText("Loading…", useUnmergedTree = true).assertCountEquals(0)
            assertEquals(1, api.requests.size, "re-entering Usage tab with hoisted state must not refetch")
        }

    private class FakeAgentUsageApi(
        private val responder: (from: String, to: String) -> HomeNetworkResponse,
    ) : RaftAgentsApi {
        val requests = mutableListOf<Pair<String, String>>()

        override suspend fun agentUsage(serverId: ServerId, from: String, to: String, agentId: MemberId?): HomeNetworkResponse {
            requests += from to to
            return responder(from, to)
        }

        override suspend fun agents() = error("unused")
        override suspend fun agent(agentId: MemberId) = error("unused")
        override suspend fun createAgent(body: String) = error("unused")
        override suspend fun updateAgent(agentId: MemberId, body: String) = error("unused")
        override suspend fun startAgent(agentId: MemberId) = error("unused")
        override suspend fun stopAgent(agentId: MemberId) = error("unused")
        override suspend fun resetAgent(agentId: MemberId, body: String) = error("unused")
        override suspend fun deleteAgent(agentId: MemberId, body: String) = error("unused")
        override suspend fun assignMachine(agentId: MemberId, body: String) = error("unused")
        override suspend fun agentDirectMessages(agentId: MemberId) = error("unused")
        override suspend fun agentActivityLog(agentId: MemberId, limit: Int) = error("unused")
        override suspend fun agentWorkspaceFiles(agentId: MemberId, dirPath: String?) = error("unused")
        override suspend fun readAgentWorkspaceFile(agentId: MemberId, path: String) = error("unused")
        override suspend fun agentSkills(agentId: MemberId) = error("unused")
        override suspend fun agentExternalStatus(agentId: MemberId) = error("unused")
        override suspend fun agentScopes(agentId: MemberId) = error("unused")
        override suspend fun updateAgentScopes(agentId: MemberId, body: String) = error("unused")
        override suspend fun resetAgentScopes(agentId: MemberId) = error("unused")
        override suspend fun issueBootstrapToken(agentId: MemberId, body: String) = error("unused")
    }
}
