package build.raft.app

import build.raft.app.data.AuthAuthorityChangedMessage
import build.raft.app.data.AuthInviteEnvironmentUnavailableCode
import build.raft.app.data.isInviteEnvironmentIntegrityFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InviteAcceptPageTest {
    @Test
    fun inviteEnvironmentIntegrityFailuresRetainWitnessForPageRecreation() {
        assertTrue(isInviteEnvironmentIntegrityFailure(AuthAuthorityChangedMessage))
        assertTrue(isInviteEnvironmentIntegrityFailure(" auth_authority_changed "))
        assertTrue(isInviteEnvironmentIntegrityFailure(AuthInviteEnvironmentUnavailableCode))
        assertTrue(isInviteEnvironmentIntegrityFailure("invite-environment-unavailable"))
        assertFalse(isInviteEnvironmentIntegrityFailure("invalid_token"))
        assertFalse(isInviteEnvironmentIntegrityFailure("network"))
    }

    @Test
    fun pageFailureCleanupKeepsIntegrityWitnessAndRetiresOrdinaryFailures() {
        fun clearCount(errorCode: String): Int {
            var count = 0
            clearInviteResumeAfterInviteFailure(errorCode) { count += 1 }
            return count
        }

        assertEquals(0, clearCount(AuthAuthorityChangedMessage))
        assertEquals(0, clearCount(AuthInviteEnvironmentUnavailableCode))
        assertEquals(1, clearCount("invalid_token"))
        assertEquals(1, clearCount("network"))
    }

    @Test
    fun pageSuccessfulAcceptAndFinalizeRetiresWitnessExactlyOnce() {
        var count = 0
        clearInviteResumeAfterInviteCompletion { count += 1 }
        assertEquals(1, count)
    }
}
