package build.raft.realtime.socket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RaftSocketHandshakeErrorTest {
    @Test
    fun everyServerHandshakeErrorIsTyped() {
        // GeneratedSocketHandshakeErrors is synced from botiverse/slock; a new message fails here until typed.
        GeneratedSocketHandshakeErrors.All.forEach { message ->
            assertNotEquals(RaftSocketHandshakeErrorKind.Unknown, RaftSocketHandshakeError.kindOf(message), message)
        }
        assertEquals(GeneratedSocketHandshakeErrors.All.size, GeneratedSocketHandshakeErrors.All.map(RaftSocketHandshakeError::kindOf).toSet().size)
    }

    @Test
    fun ssoAndMembershipAreTerminalAndTokenErrorsRefresh() {
        val expected =
            mapOf(
                GeneratedSocketHandshakeErrors.SsoRequired to (true to false),
                GeneratedSocketHandshakeErrors.NotServerMember to (true to false),
                GeneratedSocketHandshakeErrors.InvalidOrExpiredToken to (false to true),
                GeneratedSocketHandshakeErrors.AuthenticationRequired to (false to true),
                GeneratedSocketHandshakeErrors.InvalidTokenType to (false to true),
                GeneratedSocketHandshakeErrors.AuthenticationChangedReconnectRequired to (false to true),
            )
        expected.forEach { (message, flags) ->
            val error = RaftSocketHandshakeError.fromMessage(message)
            assertEquals(flags, error.terminal to error.requiresTokenRefresh, message)
            assertEquals(message, error.message)
        }
    }

    @Test
    fun matchingIsExactNotByKeyword() {
        // The Android client used to refresh on any message containing "token" / "auth" / "invalid".
        listOf("invalid token", "SSO_REQUIRED ", "Token expired", "xhr poll error").forEach { message ->
            val error = RaftSocketHandshakeError.fromMessage(message)
            assertEquals(RaftSocketHandshakeErrorKind.Unknown, error.kind, message)
            assertFalse(error.terminal)
            assertFalse(error.requiresTokenRefresh)
        }
        assertTrue(RaftSocketHandshakeError.fromMessage("SSO_REQUIRED").terminal)
    }
}
