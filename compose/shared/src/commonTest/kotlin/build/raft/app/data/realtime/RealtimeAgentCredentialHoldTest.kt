package build.raft.app.data.realtime

import build.raft.app.model.AgentCredentialHoldReason
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberPresenceUpdated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * `agent:activity` for an agent the daemon holds for an unusable sign-in (botiverse/slock #13276,
 * staging 853c6998b: agentOrchestrator emits `credentialHold` beside an idle status; web
 * credentialHoldProjection.test.ts and agentActivityCompatibility.test.ts).
 */
class RealtimeAgentCredentialHoldTest {
    private val heldDetail = "Codex CLI is not logged in on this computer. (Reported by another agent on the same sign-in.)"

    private fun frame(activity: String = "online", detailKind: String = "idle", carrier: String? = """{ "reason": "user_reauth" }"""): String {
        val hold = if (carrier == null) "" else ""","credentialHold": $carrier"""
        return """
        {
          "agentId": "agent-held",
          "activity": "$activity",
          "detail": "$heldDetail",
          "detailKind": "$detailKind",
          "timestamp": 1782367200000,
          "serverSeq": 7$hold
        }
        """.trimIndent()
    }

    private fun map(payload: String): MemberPresenceUpdated =
        assertIs<MemberPresenceUpdated>(RealtimeEventMapper().map(RealtimeSocketEvents.AgentActivity, payload))

    @Test
    fun theIdleStatusCarriesTheHeldReason() {
        val held = map(frame())
        assertEquals(AgentStatus.Online, held.agentStatus)
        assertEquals("idle", held.detailKind)
        assertEquals(AgentCredentialHoldReason.UserReauth, held.credentialHold)
        assertEquals(AgentCredentialHoldReason.UserModelAccess, map(frame(carrier = """{ "reason": "user_model_access", "model": "not-echoed" }""")).credentialHold)
        assertEquals(AgentCredentialHoldReason.Billing, map(frame(carrier = """{ "reason": "billing" }""")).credentialHold)
    }

    @Test
    fun onlyAnIdleFrameWithAKnownReasonCarriesIt() {
        assertNull(map(frame(activity = "working", detailKind = "starting")).credentialHold, "only an idle frame carries it")
        assertNull(map(frame(carrier = """{ "reason": "quota" }""")).credentialHold, "an unknown reason is no reason")
        assertNull(map(frame(carrier = null)).credentialHold, "an older daemon's held notice: none")
    }
}
