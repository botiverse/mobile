package build.raft.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ThreadServerSlugCaptureTest {
    @Test
    fun resolvesWhenCaptureSucceedsAfterRetries() = runBlocking {
        var captures = 0
        val slug =
            resolveThreadServerSlugWithRetry(
                capture = {
                    captures++
                    if (captures < 3) null else "server-42"
                },
                sleep = {}
            )
        assertEquals("server-42", slug)
        assertEquals(3, captures)
    }

    @Test
    fun returnsNullAfterExhaustingAttempts() = runBlocking {
        var captures = 0
        val slug =
            resolveThreadServerSlugWithRetry(
                capture = {
                    captures++
                    null
                },
                attempts = 4,
                sleep = {}
            )
        assertNull(slug)
        assertEquals(4, captures)
    }

    @Test
    fun propagatesCancellationFromSleep() = runBlocking {
        var captures = 0
        assertFailsWith<CancellationException> {
            resolveThreadServerSlugWithRetry(
                capture = {
                    captures++
                    null
                },
                sleep = { throw CancellationException("cancelled") }
            )
        }
        assertEquals(0, captures)
    }
}
