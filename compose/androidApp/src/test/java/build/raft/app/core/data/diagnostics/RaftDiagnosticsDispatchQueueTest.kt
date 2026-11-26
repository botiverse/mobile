package build.raft.app.core.data.diagnostics

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftDiagnosticsDispatchQueueTest {
    @Test
    fun saturationDropsWithoutBlockingCallerAndReportsDropCount() {
        val processorStarted = CountDownLatch(1)
        val releaseProcessor = CountDownLatch(1)
        val processed = CopyOnWriteArrayList<Pair<String, Long>>()
        val queue =
            RaftDiagnosticsDispatchQueue(
                capacity = 1,
                threadFactory = ThreadFactory { runnable -> Thread(runnable, "diagnostics-test-writer") },
                processor = { entry, dropped ->
                    processorStarted.countDown()
                    releaseProcessor.await(2L, TimeUnit.SECONDS)
                    processed += entry.message to dropped
                },
            )

        queue.enqueue(pending("running"))
        assertTrue(processorStarted.await(1L, TimeUnit.SECONDS))
        queue.enqueue(pending("queued"))

        val startedAt = System.nanoTime()
        queue.enqueue(pending("dropped"))
        val enqueueMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        releaseProcessor.countDown()
        assertTrue(queue.flush())
        queue.enqueue(pending("after-drop"))
        assertTrue(queue.flush())

        assertTrue("saturated enqueue blocked for ${enqueueMillis}ms", enqueueMillis < 100L)
        assertEquals(listOf("running", "queued", "after-drop"), processed.map { it.first })
        assertEquals(1L, processed.last().second)
    }

    @Test
    fun flushReturnsTrueAfterProcessorFailureWithoutPoisoningFutureFlushes() {
        val processed = CopyOnWriteArrayList<String>()
        val queue =
            RaftDiagnosticsDispatchQueue(
                capacity = 8,
                threadFactory = ThreadFactory { runnable -> Thread(runnable, "diagnostics-test-failure") },
                processor = { entry, _ ->
                    if (entry.message == "boom") {
                        throw IllegalStateException("simulated processor failure")
                    }
                    processed += entry.message
                },
            )

        assertTrue(queue.enqueue(pending("boom")))
        assertTrue("flush must succeed once queued entries drain even after a processor failure", queue.flush())
        assertEquals(1L, queue.failedEntryCount())
        assertEquals(1L, queue.droppedEntryCount())

        assertTrue(queue.enqueue(pending("recovered-entry")))
        assertTrue("subsequent flushes must not remain permanently poisoned", queue.flush())
        assertEquals(listOf("recovered-entry"), processed.toList())
    }

    private fun pending(
        message: String,
        includeNonCritical: Boolean = true,
        criticalHint: DiagnosticCriticalHint = DiagnosticCriticalHint.None,
    ): PendingDiagnosticLog =
        PendingDiagnosticLog(
            level = "debug",
            tag = "DispatchTest",
            message = message,
            timestampMillis = 123L,
            threadName = "original-caller",
            sequence = 0L,
            includeNonCritical = includeNonCritical,
            criticalHint = criticalHint,
        )
}
