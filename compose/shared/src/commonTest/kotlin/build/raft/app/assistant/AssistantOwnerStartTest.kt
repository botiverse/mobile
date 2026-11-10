package build.raft.app.assistant

import build.raft.app.data.AgentLifecycleActionResult
import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import build.raft.app.model.MemberId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The owner's Start on the paused notice (AssistantOwnerSection `AssistantOwnerPausedNotice`). */
class AssistantOwnerStartTest {
    private val agentId = MemberId("asst-1")

    @Test
    fun aStartThatWentThroughHasNoError() {
        assertNull(assistantStartError(DataResult.Content(AgentLifecycleActionResult(agentId, success = true, action = "start"))))
    }

    @Test
    fun aRefusedStartKeepsItsErrorAndTraceId() {
        val refused = ScreenError("http_409", serverCode = "machine_unassigned", traceId = TRACE_ID)
        val error = assistantStartError(DataResult.Error(refused))
        assertEquals(refused, error)
        assertEquals(TRACE_ID, error?.traceId)
    }

    @Test
    fun theTwoRefusalsAfterThePauseIsLiftedAreTold() {
        // slock `POST agents/:id/start` lifts an assistant's pause, then refuses one with no
        // Computer or a revoked one: the notice goes away, so these must say why elsewhere.
        assertEquals(
            AssistantStartRefusal.MachineUnassigned,
            assistantStartRefusal(ScreenError("http_409", serverCode = "machine_unassigned", traceId = null)),
        )
        assertEquals(
            AssistantStartRefusal.ComputerAccessRevoked,
            assistantStartRefusal(ScreenError("http_409", serverCode = "computer_access_revoked", traceId = null)),
        )
        assertNull(assistantStartRefusal(ScreenError("http_409", traceId = null)))
        assertNull(assistantStartRefusal(ScreenError("server_unavailable", traceId = null)))
        assertNull(assistantStartRefusal(ScreenError(AssistantStartInFlightCode, traceId = null)))
    }

    @Test
    fun theInFlightCodeIsTheRepositorysSingleFlightRefusal() {
        // MemberRepositoryDataSource `executeAgentLifecycleAction`: `agent_${action}_in_flight`.
        assertEquals("agent_start_in_flight", AssistantStartInFlightCode)
    }
}

private const val TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
