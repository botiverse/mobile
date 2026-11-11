package build.raft.app.data

import build.raft.app.model.MemberId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Payloads copied from botiverse/slock 853c6998bcef45df5c25e63b1035e2388608721d:
// packages/server/src/routes/agents.ts `POST /:id/subagents/:taskId/interrupt` and
// packages/web/src/components/agent/stopSubagent.ts.
class AgentSubagentStopStoreTest {
    private val agentId = MemberId("agent-1")

    @Test
    fun interruptSubagentParsesEveryServerOutcome() {
        val cases =
            listOf(
                """{"outcome":"interrupted"}""" to AgentSubagentStopOutcome.Interrupted,
                """{"outcome":"not_running"}""" to AgentSubagentStopOutcome.NotRunning,
                """{"outcome":"unsupported"}""" to AgentSubagentStopOutcome.Unsupported,
                // Web stopSubagent reads anything else as not running.
                """{"outcome":"stopped"}""" to AgentSubagentStopOutcome.NotRunning,
                """{}""" to AgentSubagentStopOutcome.NotRunning,
            )
        cases.forEach { (body, expected) -> assertEquals(expected, parseSharedAgentSubagentInterruptOutcome(body), body) }
        assertNull(parseSharedAgentSubagentInterruptOutcome("not json"))
    }

    @Test
    fun interruptSubagentPostsTheWebRouteWithTheIdEncoded() = runTest {
        val network = ScriptedAgentControlsNetworkClient()
        network.respond("POST", "agents/agent-1/subagents/thread%2Fa%20b/interrupt", 200, """{"outcome":"interrupted"}""")

        val result = RaftAgentControlsApi(network).interruptSubagent(agentId, "thread/a b")

        assertEquals(DataResult.Content(AgentSubagentStopOutcome.Interrupted), result)
        val request = network.requests.single()
        assertEquals("POST", request.method)
        assertEquals(DataRequestPriority.Foreground, request.priority)
    }

    @Test
    fun refusalsMapToTheirOwnFeedbackAndKeepTheErrorForItsTraceId() = runTest {
        val cases =
            listOf(
                Triple(409, """{"error":"This agent's computer is offline.","code":"computer_offline"}""", AgentSubagentStopFeedback.ComputerOffline::class),
                Triple(
                    409,
                    """{"error":"This agent's computer needs an upgrade before a sub-agent can be stopped.","code":"computer_upgrade_required"}""",
                    AgentSubagentStopFeedback.UpgradeRequired::class
                ),
                Triple(504, """{"error":"The agent's computer did not answer in time. Try again.","code":"computer_timeout"}""", AgentSubagentStopFeedback.Failed::class),
                Triple(400, """{"error":"Invalid sub-agent id"}""", AgentSubagentStopFeedback.Failed::class),
            )
        cases.forEach { (status, body, expected) ->
            val network = ScriptedAgentControlsNetworkClient()
            network.respond("POST", "agents/agent-1/subagents/t1/interrupt", status, body)
            val feedback = RaftAgentControlsApi(network).interruptSubagent(agentId, "t1").toAgentSubagentStopFeedback()
            assertEquals(expected, feedback::class, body)
        }
    }

    @Test
    fun storeReportsOneFeedbackPerStopAndIsSingleFlight() = runTest {
        val gate = CompletableDeferred<DataResult<AgentSubagentStopOutcome>>()
        val calls = mutableListOf<Pair<MemberId, String>>()
        val store = AgentSubagentStopStore({ id, task -> calls += id to task; gate.await() }, this)

        store.dispatch(AgentSubagentStopIntent.Stop(agentId, "task-1"))
        runCurrent()
        store.dispatch(AgentSubagentStopIntent.Stop(agentId, "task-2"))
        runCurrent()
        assertEquals("task-1", store.state.value.inFlightTaskId)
        assertEquals(listOf(agentId to "task-1"), calls)

        gate.complete(DataResult.Content(AgentSubagentStopOutcome.Unsupported))
        runCurrent()
        assertNull(store.state.value.inFlightTaskId)
        assertEquals(AgentSubagentStopFeedback.Unsupported, store.state.value.feedback)
        assertEquals(agentId, store.state.value.feedbackAgentId)

        store.dispatch(AgentSubagentStopIntent.ConsumeFeedback)
        assertNull(store.state.value.feedback)
    }

    @Test
    fun storeSendsNoIdTheServerWouldRefuseAndTurnsAThrowIntoFailed() = runTest {
        var calls = 0
        val store = AgentSubagentStopStore({ _, _ -> calls += 1; error("socket closed") }, this)

        store.dispatch(AgentSubagentStopIntent.Stop(agentId, "x".repeat(AgentSubagentTaskIdMaxLength + 1)))
        store.dispatch(AgentSubagentStopIntent.Stop(agentId, " "))
        runCurrent()
        assertEquals(0, calls)

        store.dispatch(AgentSubagentStopIntent.Stop(agentId, "task-1"))
        runCurrent()
        assertEquals(1, calls)
        assertIs<AgentSubagentStopFeedback.Failed>(store.state.value.feedback)
        assertNull(store.state.value.inFlightTaskId)
    }

    @Test
    fun stopSubagentsFollowsStopTurnAuthorityAndTheRuntime() {
        fun can(role: String? = "member", creator: Boolean = false, external: Boolean = false, deleted: Boolean = false, runtime: String? = "codex") =
            canStopAgentSubagents(serverRole = role, isCreator = creator, isExternalAgent = external, deleted = deleted, runtime = runtime)

        listOf("codex", "claude", "kimi-sdk", "qoder").forEach { assertTrue(can(runtime = it), it) }
        listOf("pi", "gemini", null).forEach { assertFalse(can(runtime = it), it.toString()) }
        assertFalse(can(role = "guest"))
        assertTrue(can(role = "guest", creator = true))
        assertFalse(can(external = true))
        assertFalse(can(deleted = true))
    }
}
