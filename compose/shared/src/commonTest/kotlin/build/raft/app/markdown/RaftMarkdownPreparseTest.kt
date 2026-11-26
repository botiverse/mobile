package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class RaftMarkdownPreparseTest {
    @Test
    fun emptyOrNonPositiveLimitShortCircuits() = runTest {
        val empty = prepareRaftMarkdownFullRendererBodies(emptyList())
        assertEquals(0, empty.requestedCount)
        assertEquals(0, empty.parsedCount)
        assertEquals(0, empty.cacheHitCount)
        assertEquals(0, empty.scheduledCount)

        val capped = prepareRaftMarkdownFullRendererBodies(listOf("hello **world**"), maxBodies = 0)
        assertEquals(0, capped.requestedCount)
        assertEquals(0, capped.scheduledCount)
    }

    @Test
    fun deduplicatesCapsAndCachesAcrossCalls() = runTest {
        val firstBody = "raft-markdown-preparse-test-a: **bold** and `code`"
        val secondBody = "raft-markdown-preparse-test-b: - item 1\n- item 2"
        val thirdBody = "raft-markdown-preparse-test-c: > quote"

        val firstPass =
            prepareRaftMarkdownFullRendererBodies(
                bodies = listOf("   ", firstBody, firstBody, secondBody, thirdBody),
                maxBodies = 2,
            )
        assertEquals(2, firstPass.requestedCount)
        assertEquals(2, firstPass.scheduledCount)
        assertEquals(2, firstPass.parsedCount)
        assertEquals(0, firstPass.cacheHitCount)

        val secondPass =
            prepareRaftMarkdownFullRendererBodies(
                bodies = listOf(firstBody, secondBody),
                maxBodies = 2,
            )
        assertEquals(2, secondPass.requestedCount)
        assertEquals(0, secondPass.scheduledCount)
        assertEquals(2, secondPass.cacheHitCount)
        assertEquals(0, secondPass.parsedCount)
    }
}
