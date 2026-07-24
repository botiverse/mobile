package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthSessionTerminalRouteGateTest {
    @Test
    fun foregroundClearTerminalizesOnlyOnceForTheCurrentGeneration() {
        val gate = AuthSessionTerminalRouteGate()

        assertFalse(gate.reconcile(AuthSessionTerminalSnapshot(3, authSessionFixture())))
        assertEquals(
            AuthSessionClearReason.AuthLoss,
            gate.admit(AuthSessionEvent.Cleared(4, AuthSessionClearReason.AuthLoss)),
        )
        assertNull(gate.admit(AuthSessionEvent.Cleared(4, AuthSessionClearReason.AuthLoss)))
        assertNull(gate.admit(AuthSessionEvent.Cleared(3, AuthSessionClearReason.AuthLoss)))
    }

    @Test
    fun lateClearCannotEvictANewerAuthenticatedSession() {
        val gate = AuthSessionTerminalRouteGate()
        val successor = authSessionFixture(userId = "successor")

        assertFalse(gate.reconcile(AuthSessionTerminalSnapshot(8, successor)))
        assertNull(gate.admit(AuthSessionEvent.Cleared(8, AuthSessionClearReason.AuthLoss)))
        assertEquals(
            AuthSessionClearReason.Logout,
            gate.admit(AuthSessionEvent.Cleared(9, AuthSessionClearReason.Logout)),
        )
    }

    @Test
    fun backgroundOrColdReentryWithoutSessionTerminalizesExactlyOnce() {
        val gate = AuthSessionTerminalRouteGate()

        assertTrue(gate.reconcile(AuthSessionTerminalSnapshot(4, null)))
        assertFalse(gate.reconcile(AuthSessionTerminalSnapshot(4, null)))
    }

    @Test
    fun nonClearEventsAdvanceTheGenerationFenceWithoutTerminalizing() {
        val gate = AuthSessionTerminalRouteGate()

        assertFalse(gate.reconcile(AuthSessionTerminalSnapshot(10, authSessionFixture())))
        assertNull(gate.admit(AuthSessionEvent.TokensRotated(12)))
        assertNull(gate.admit(AuthSessionEvent.Cleared(11, AuthSessionClearReason.AuthLoss)))
    }

    @Test
    fun truePermission403RemainsDistinctFromAuthExpiry() {
        assertFalse(PermissionBlocker("server_forbidden", "server_admin").isAuthSessionBlocker())
        assertTrue(PermissionBlocker("auth_required", "auth_session").isAuthSessionBlocker())
    }
}
