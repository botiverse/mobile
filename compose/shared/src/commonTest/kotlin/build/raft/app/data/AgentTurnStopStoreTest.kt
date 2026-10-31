package build.raft.app.data

import build.raft.app.MEMBERS_STOP_TURN_MENU_ID
import build.raft.app.MembersAgentLifecycleAction
import build.raft.app.buildMembersManagementMenuItems
import build.raft.app.membersRouteCanManageAgent
import build.raft.app.model.MemberId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Payloads copied from botiverse/slock AGENT_CONTROLS_SLOCK_SHA:
// packages/server/src/routes/agents.ts `POST /:id/interrupt-turn` and
// packages/server/src/services/agentOrchestrator.ts `interruptAgentTurn`.
class AgentTurnStopStoreTest {
    private val agentId = MemberId("agent-1")

    @Test
    fun interruptTurnParsesEveryServerOutcome() {
        val cases =
            listOf(
                """{"outcome":"interrupted","toolName":"Bash","stuckForMs":120000}""" to AgentTurnStopOutcome.Interrupted,
                """{"outcome":"restarted"}""" to AgentTurnStopOutcome.Restarted,
                """{"outcome":"not_running"}""" to AgentTurnStopOutcome.NotRunning,
                // A hosted provider answers with its own kind; web reads anything else as not running.
                """{"outcome":"stopped"}""" to AgentTurnStopOutcome.NotRunning
            )
        cases.forEach { (body, expected) ->
            assertEquals(expected, parseSharedAgentTurnInterruptOutcome(body), body)
        }
        assertNull(parseSharedAgentTurnInterruptOutcome("not json"))
    }

    @Test
    fun interruptTurnPostsTheWebRouteAtForegroundPriority() = runTest {
        val network = ScriptedAgentControlsNetworkClient()
        network.respond("POST", "agents/agent-1/interrupt-turn", 200, """{"outcome":"interrupted"}""")

        val result = RaftAgentControlsApi(network).interruptTurn(agentId)

        assertEquals(DataResult.Content(AgentTurnStopOutcome.Interrupted), result)
        val request = network.requests.single()
        assertEquals("POST", request.method)
        assertEquals(DataRequestPriority.Foreground, request.priority)
    }

    @Test
    fun refusalsMapToTheirOwnFeedbackAndOthersToFailed() = runTest {
        val cases =
            listOf(
                Triple(409, """{"error":"This agent's computer is offline.","code":"computer_offline"}""", AgentTurnStopFeedback.ComputerOffline),
                Triple(
                    409,
                    """{"error":"This agent's computer needs an upgrade before a single turn can be stopped. You can stop the whole agent instead.","code":"computer_upgrade_required"}""",
                    AgentTurnStopFeedback.UpgradeRequired
                ),
                Triple(504, """{"error":"The agent's computer did not answer in time. Try again, or stop the whole agent.","code":"computer_timeout"}""", AgentTurnStopFeedback.Failed),
                Triple(503, """{"error":"Couldn't stop the agent's current turn. Try again.","code":"hosted_stop_failed"}""", AgentTurnStopFeedback.Failed),
                Triple(500, """{"error":"Failed to stop the agent's current turn"}""", AgentTurnStopFeedback.Failed),
                Triple(
                    403,
                    """{"error":"The `controlAgentRuntime` capability or human creator authority is required to control agents"}""",
                    AgentTurnStopFeedback.Failed
                )
            )
        cases.forEach { (status, body, expected) ->
            val network = ScriptedAgentControlsNetworkClient()
            network.respond("POST", "agents/agent-1/interrupt-turn", status, body)
            val result = RaftAgentControlsApi(network).interruptTurn(agentId)
            assertEquals(expected, result.toAgentTurnStopFeedback(), body)
        }
    }

    @Test
    fun storeReportsOneFeedbackPerStopAndIsSingleFlight() = runTest {
        val gate = CompletableDeferred<DataResult<AgentTurnStopOutcome>>()
        var calls = 0
        val store = AgentTurnStopStore({ calls += 1; gate.await() }, this)

        store.dispatch(AgentTurnStopIntent.Stop(agentId))
        runCurrent()
        store.dispatch(AgentTurnStopIntent.Stop(agentId))
        runCurrent()
        assertEquals(agentId, store.state.value.inFlightAgentId)
        assertEquals(1, calls)

        gate.complete(DataResult.Content(AgentTurnStopOutcome.Restarted))
        runCurrent()
        assertNull(store.state.value.inFlightAgentId)
        assertEquals(AgentTurnStopFeedback.Restarted, store.state.value.feedback)
        assertEquals(agentId, store.state.value.feedbackAgentId)

        store.dispatch(AgentTurnStopIntent.ConsumeFeedback)
        assertNull(store.state.value.feedback)
    }

    @Test
    fun storeTurnsAThrownRequestIntoAFailedFeedback() = runTest {
        val store = AgentTurnStopStore({ error("socket closed") }, this)

        store.dispatch(AgentTurnStopIntent.Stop(agentId))
        runCurrent()

        assertEquals(AgentTurnStopFeedback.Failed, store.state.value.feedback)
        assertTrue(store.state.value.feedback!!.isError)
        assertNull(store.state.value.inFlightAgentId)
    }

    @Test
    fun stopTurnFollowsServerAuthorityAndPresence() {
        assertTrue(canStopAgentTurn("member", isCreator = false, isExternalAgent = false, deleted = false, online = true))
        assertTrue(canStopAgentTurn("guest", isCreator = true, isExternalAgent = false, deleted = false, online = true))
        assertFalse(canStopAgentTurn("guest", isCreator = false, isExternalAgent = false, deleted = false, online = true))
        assertFalse(canStopAgentTurn("owner", isCreator = false, isExternalAgent = false, deleted = false, online = false))
        assertFalse(canStopAgentTurn("owner", isCreator = false, isExternalAgent = true, deleted = false, online = true))
        assertFalse(canStopAgentTurn("owner", isCreator = false, isExternalAgent = false, deleted = true, online = true))
    }

    // slock #12029: Stop Agent needs `stopAgents` (owner/admin) or creator; members keep
    // Stop Current Turn on any agent (asserted above) but never get Stop Agent on someone else's.
    @Test
    fun stopAgentIsOwnerAdminOrCreatorWhileMembersKeepStopTurn() {
        assertTrue(serverRoleCanStopAgents("owner"))
        assertTrue(serverRoleCanStopAgents("admin"))
        assertFalse(serverRoleCanStopAgents("member"))
        assertFalse(serverRoleCanStopAgents("guest"))
        assertFalse(serverRoleCanStopAgents(null))

        fun detail(creatorId: String) =
            AgentDetailSnapshot(
                agentId = agentId,
                summary = AgentDetailSummary(id = agentId.value, name = "Agent", creatorId = creatorId),
                directMessages = emptyList(),
                reminders = emptyList(),
                activity = emptyList()
            )
        fun memberCanManage(creatorId: String) =
            membersRouteCanManageAgent(
                allowsPrivateAgentSurfaces = true,
                canManageSettings = serverRoleCanStopAgents("member"),
                currentUserId = "member-1",
                detail = detail(creatorId)
            )
        fun menu(canManage: Boolean) =
            buildMembersManagementMenuItems(
                primaryAction = MembersAgentLifecycleAction.Stop,
                canManage = canManage,
                deleted = false,
                inProgress = false,
                canStopTurn = canStopAgentTurn("member", isCreator = false, isExternalAgent = false, deleted = false, online = true)
            )

        // Someone else's agent: only Stop Current Turn.
        assertEquals(listOf(MEMBERS_STOP_TURN_MENU_ID), menu(memberCanManage("owner-1")).map { it.id })
        // Their own agent: Stop Agent is back.
        assertEquals(MembersAgentLifecycleAction.Stop.actionKey, menu(memberCanManage("member-1")).first().id)
    }

    @Test
    fun menuOffersStopTurnAloneToRuntimeControllersAndLastToManagers() {
        val controllerOnly =
            buildMembersManagementMenuItems(
                primaryAction = MembersAgentLifecycleAction.Stop,
                canManage = false,
                deleted = false,
                inProgress = false,
                canStopTurn = true
            )
        assertEquals(listOf(MEMBERS_STOP_TURN_MENU_ID), controllerOnly.map { it.id })

        val manager =
            buildMembersManagementMenuItems(
                primaryAction = MembersAgentLifecycleAction.Stop,
                canManage = true,
                deleted = false,
                inProgress = false,
                canStopTurn = true,
                stopTurnInProgress = true
            )
        assertEquals(MEMBERS_STOP_TURN_MENU_ID, manager.last().id)
        assertFalse(manager.last().enabled)
    }
}
