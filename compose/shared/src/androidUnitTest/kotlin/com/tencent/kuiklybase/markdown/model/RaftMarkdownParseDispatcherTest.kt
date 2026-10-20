package com.tencent.kuiklybase.markdown.model

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

class RaftMarkdownParseDispatcherTest {
    @Test
    fun androidMarkdownParsingSerializesConcurrentCallers() = runBlocking {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val firstStarted = CountDownLatch(1)
        val release = CountDownLatch(1)

        val jobs = List(4) {
            async(RaftMarkdownParseDispatcher) {
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
                "the first Markdown parser task should start"
            )
            Thread.sleep(100)
            assertEquals(RAFT_MARKDOWN_PARSE_PARALLELISM, maximumActive.get())
        } finally {
            release.countDown()
        }
        jobs.forEach { it.await() }
        assertEquals(RAFT_MARKDOWN_PARSE_PARALLELISM, maximumActive.get())
    }
}
