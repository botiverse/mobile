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

class SlockMarkdownParseDispatcherTest {
    @Test
    fun androidMarkdownParsingSharesDefaultDispatcherWithTwoWayParallelism() = runBlocking {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val firstPairStarted = CountDownLatch(2)
        val release = CountDownLatch(1)

        val jobs = List(4) {
            async(SlockMarkdownParseDispatcher) {
                val running = active.incrementAndGet()
                maximumActive.updateAndGet { current -> maxOf(current, running) }
                firstPairStarted.countDown()
                try {
                    release.await(5, TimeUnit.SECONDS)
                } finally {
                    active.decrementAndGet()
                }
            }
        }

        try {
            assertTrue(
                withContext(Dispatchers.IO) { firstPairStarted.await(5, TimeUnit.SECONDS) },
                "two parser tasks should start without serializing all Markdown work"
            )
            Thread.sleep(100)
            assertEquals(2, maximumActive.get())
        } finally {
            release.countDown()
        }
        jobs.forEach { it.await() }
        assertEquals(2, maximumActive.get())
    }
}
