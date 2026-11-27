package build.raft.app

import build.raft.app.thread.resolveThreadServerSlugWithRetry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
