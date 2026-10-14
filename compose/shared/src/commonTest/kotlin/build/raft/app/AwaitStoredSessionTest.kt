package build.raft.app

import build.raft.app.data.SharedAuthResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AwaitStoredSessionTest {
    @Test fun aSessionWrittenAfterTheFirstReadIsPickedUp() = runBlocking {
        // The shell came up with no session; the app-scope exchange writes it a moment later.
        var stored: String? = null
        var reads = 0
        val result = awaitStoredSession(attempts = 5, delayMs = 1) {
            reads++
            if (reads == 3) stored = "session"
            stored?.let { SharedAuthResult.Success(it) } ?: SharedAuthResult.Unauthenticated
        }
        assertEquals(SharedAuthResult.Success("session"), result)
        assertEquals(3, reads)
    }

    @Test fun givesUpAfterTheAttemptsWithoutASession() = runBlocking {
        var reads = 0
        val result = awaitStoredSession<String>(attempts = 4, delayMs = 1) { reads++; SharedAuthResult.Unauthenticated }
        assertTrue(result is SharedAuthResult.Unauthenticated)
        assertEquals(4, reads)
    }

    @Test fun anImmediateSessionNeedsOneRead() = runBlocking {
        var reads = 0
        assertEquals(SharedAuthResult.Success(1), awaitStoredSession(attempts = 4, delayMs = 1) { reads++; SharedAuthResult.Success(1) })
        assertEquals(1, reads)
    }
}
