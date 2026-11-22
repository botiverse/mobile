package build.raft.app

import build.raft.app.data.AgentBlockedState
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
    fun anInvalidCredentialNeedsAManualRestart() {
        fun needs(status: AgentStatus?, credentialReason: AgentCredentialHoldReason?, external: Boolean = false, blocked: AgentBlockedState? = null) =
            agentNeedsManualRestart(status, "idle", null, null, external, blocked, likelyOnline = false, credentialReason = credentialReason)
        assertTrue(needs(AgentStatus.Online, UserReauth), "held for another agent's error: Retry")
        assertFalse(needs(AgentStatus.Online, null), "an older daemon's held notice: Online, no Retry")
        assertFalse(needs(AgentStatus.Online, Billing, external = true))
        assertFalse(needs(AgentStatus.Online, Billing, blocked = AgentBlockedState.ComputerAccessRevoked))
    }
}
