package build.raft.app.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class RaftJsonParseDispatcherTest {
    @Test
    fun androidJsonParsingSerializesConcurrentCallers() = runBlocking {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val firstStarted = CountDownLatch(1)
        val release = CountDownLatch(1)

        val jobs = List(4) {
            async(RaftJsonParseDispatcher) {
                val running = active.incrementAndGet()
                maximumActive.updateAndGet { current -> maxOf(current, running) }
                firstStarted.countDown()
                try {
                    release.await(5, TimeUnit.SECONDS)
                } finally {
                    active.decrementAndGet()
                }
            }
        }

        try {
            assertTrue(
                withContext(Dispatchers.IO) { firstStarted.await(5, TimeUnit.SECONDS) },
                "the first JSON parser task should start",
            )
            Thread.sleep(100)
            assertEquals(RAFT_JSON_PARSE_PARALLELISM, maximumActive.get())
        } finally {
            release.countDown()
        }
        jobs.forEach { it.await() }
        assertEquals(RAFT_JSON_PARSE_PARALLELISM, maximumActive.get())
    }
}
