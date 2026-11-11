package build.raft.app

import build.raft.app.data.AgentBlockedState
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.model.AgentCredentialHoldReason
import build.raft.app.model.AgentCredentialHoldReason.Billing
import build.raft.app.model.AgentCredentialHoldReason.UserModelAccess
import build.raft.app.model.AgentCredentialHoldReason.UserReauth
import build.raft.app.model.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Web `credentialInvalidReasonOf` and the badge's Retry rule (botiverse/slock #13276, staging
 * 853c6998b: web tests/credentialHoldProjection.test.ts and agentRuntimeErrorBanner.behavior.test.tsx
 * "4b" cases).
 */
class AgentCredentialInvalidStatusTest {
    private fun reason(
        status: AgentStatus?,
        detail: String?,
        detailKind: String?,
        hold: AgentCredentialHoldReason? = null,
        message: String? = null,
        errorClass: String? = null,
        requiredAction: AgentCredentialHoldReason? = null,
    ) = agentCredentialInvalidReason(status, detail, detailKind, hold, message, errorClass, requiredAction)

    @Test
    fun theHeldCarrierNamesTheReasonOnTheOnlineIdleStatusOnly() {
        assertEquals(UserModelAccess, reason(AgentStatus.Online, "anything", "idle", hold = UserModelAccess))
        assertNull(reason(AgentStatus.Online, "anything", "idle"), "an older daemon's held notice: no reason")
        assertNull(reason(AgentStatus.Online, "anything", "other", hold = Billing))
    }

    @Test
    fun anOwnErrorNamesTheReasonOnlyWhileTheKeptErrorIsTheOneShown() {
        val error = { message: String, errorClass: String?, requiredAction: AgentCredentialHoldReason? ->
            reason(AgentStatus.Error, "boom", "runtime_error", message = message, errorClass = errorClass, requiredAction = requiredAction)
        }
        assertEquals(UserModelAccess, error("boom", "ModelConfigError", UserModelAccess))
        assertEquals(UserModelAccess, error("boom", "AuthError", UserModelAccess), "requiredAction comes first")
        assertEquals(UserReauth, error("boom", "AuthError", null))
        assertEquals(Billing, error("boom", "BillingError", null))
        assertNull(error("boom", "ModelConfigError", null))
        assertNull(error("older", "AuthError", null), "not the error shown")
        assertNull(
            reason(AgentStatus.Online, "boom", "idle", message = "boom", errorClass = "AuthError"),
            "recovered: Online",
        )
    }

    private val online =
        SharedServerMemberStatus(status = AgentStatus.Online, presence = AgentStatus.Online.presence, detailKind = "idle", external = false)
            .toRaftAgentStatusIndicatorState()

    private val heldSummary =
        AgentDetailSummary(
            id = "agent-held",
            name = "held",
            runtime = "codex",
            activity = "online",
            activityDetail = "held notice",
            activityDetailKind = "idle",
            credentialHold = UserReauth,
        )

    @Test
    fun theProfileReadsTheLiveStatusWithTheProfilesCarrier() {
        assertEquals(UserReauth, membersAgentCredentialInvalidReason(online, heldSummary, external = false, blocked = null))
        // The live status moved on (the release, a turn): the old carrier names nothing.
        val working = online.copy(status = AgentStatus.Working, detailKind = "thinking")
        assertNull(membersAgentCredentialInvalidReason(working, heldSummary, external = false, blocked = null))
        assertNull(membersAgentCredentialInvalidReason(online, heldSummary, external = true, blocked = null), "an external agent")
        assertNull(
            membersAgentCredentialInvalidReason(online, heldSummary, external = false, blocked = AgentBlockedState.ComputerAccessRevoked),
            "a held Computer's agent says why it is held instead",
        )
        assertNull(membersAgentCredentialInvalidReason(online.copy(likelyOnline = true), heldSummary, external = false, blocked = null))
    }

    @Test
    fun anInvalidCredentialNeedsAManualRestart() {
        fun needs(status: AgentStatus?, credentialReason: AgentCredentialHoldReason?, external: Boolean = false, blocked: AgentBlockedState? = null) =
            agentNeedsManualRestart(status, "idle", null, null, external, blocked, likelyOnline = false, credentialReason = credentialReason)
        assertTrue(needs(AgentStatus.Online, UserReauth), "held for another agent's error: Retry")
        assertFalse(needs(AgentStatus.Online, null), "an older daemon's held notice: Online, no Retry")
        assertFalse(needs(AgentStatus.Online, Billing, external = true))
        assertFalse(needs(AgentStatus.Online, Billing, blocked = AgentBlockedState.ComputerAccessRevoked))
    }

    @Test
    fun aLiveErrorTheKeptRecordDoesNotDescribeIsReReadOncePerDetail() {
        val own = AgentDetailSummary(id = "agent-1", name = "own", activity = "error", activityDetail = "boom", lastRuntimeErrorMessage = "older")
        assertEquals("boom", membersAgentErrorRecordStaleDetail(AgentStatus.Error, own))
        assertNull(membersAgentErrorRecordStaleDetail(AgentStatus.Error, own.copy(lastRuntimeErrorMessage = "boom")), "described already")
        assertNull(membersAgentErrorRecordStaleDetail(AgentStatus.Online, own), "only an error")
        assertNull(membersAgentErrorRecordStaleDetail(AgentStatus.Error, own.copy(activityDetail = null)))
    }
}
