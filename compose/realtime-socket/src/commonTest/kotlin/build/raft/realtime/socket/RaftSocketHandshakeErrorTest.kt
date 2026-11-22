package build.raft.realtime.socket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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
}
