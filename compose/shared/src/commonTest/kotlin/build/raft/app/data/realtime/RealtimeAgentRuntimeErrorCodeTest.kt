package build.raft.app.data.realtime

import build.raft.app.data.AgentRuntimeErrorCode
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberPresenceUpdated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * `agent:activity` for a runtime error (botiverse/slock #13949 `runtimeErrorActivityPush`): every
 * viewer gets the error's `errorCode`, a member gets only the code's English sentence as the detail.
 * Vectors from server agentOrchestrator.runtimeErrorVisibility.test.ts; web #13985
 * attachSocketTypedCarriers reads the code only on an error frame and only when it is known.
 */
class RealtimeAgentRuntimeErrorCodeTest {
    private val usageLimitSentence =
        "The agent's model provider usage limit has been reached. Wait for the limit to reset, or ask the agent's owner to switch it to another model or account."

    private fun frame(activity: String = "error", detail: String = usageLimitSentence, errorCode: String? = "usage_limit"): String {
        val code = if (errorCode == null) "" else ", \"errorCode\": \"$errorCode\""
        return """
        {
          "agentId": "agent-1",
          "activity": "$activity",
          "detail": "$detail",
          "detailKind": "runtime_error",
          "timestamp": 1782367200000,
          "serverSeq": 53 $code
        }
        """.trimIndent()
    }

    private fun map(payload: String): MemberPresenceUpdated =
        assertIs<MemberPresenceUpdated>(RealtimeEventMapper().map(RealtimeSocketEvents.AgentActivity, payload))

    @Test
    fun anErrorFrameCarriesItsCode() {
        val member = map(frame())
        assertEquals(AgentStatus.Error, member.agentStatus)
        assertEquals(AgentRuntimeErrorCode.UsageLimit, member.errorCode)
        assertEquals(usageLimitSentence, member.statusLabel)
        // A typed AuthError reads auth_required to members.
        assertEquals(AgentRuntimeErrorCode.AuthRequired, map(frame(errorCode = "auth_required")).errorCode)
    }

    @Test
    fun noCodeAnUnknownCodeOrAnotherActivityIsNone() {
        // An unknown error: no code, the generic line.
        assertNull(map(frame(detail = "Agent encountered an error", errorCode = null)).errorCode)
        // A newer server's code is no code.
        assertNull(map(frame(errorCode = "quota_galaxy")).errorCode)
        // The code rides only an error frame.
        assertNull(map(frame(activity = "offline")).errorCode)
    }

    @Test
    fun everyCodeOfTheSharedListIsKnown() {
        listOf("usage_limit", "billing", "model_unavailable", "auth_required", "network_timeout", "input_too_large")
            .forEach { wire -> assertEquals(wire, AgentRuntimeErrorCode.fromWire(wire)?.wireValue, wire) }
        assertNull(AgentRuntimeErrorCode.fromWire(null))
        assertNull(AgentRuntimeErrorCode.fromWire("USAGE_LIMIT"))
    }
}
