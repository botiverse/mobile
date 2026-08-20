package build.raft.app

import build.raft.app.data.AuthAuthorityChangedMessage
import build.raft.app.data.AuthInviteEnvironmentUnavailableCode
import build.raft.app.data.isInviteEnvironmentIntegrityFailure
import kotlin.test.Test
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
}
