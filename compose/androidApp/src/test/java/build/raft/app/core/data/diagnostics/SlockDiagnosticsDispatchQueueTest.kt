package build.raft.app.core.data.diagnostics

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockDiagnosticsDispatchQueueTest {
    @Test
    fun dispatchesInOrderOffCallerThread() {
        val callerThread = Thread.currentThread().name
        val processed = CopyOnWriteArrayList<Triple<String, String, Long>>()
        val queue =
            SlockDiagnosticsDispatchQueue(
                capacity = 8,
                threadFactory = ThreadFactory { runnable -> Thread(runnable, "diagnostics-test-writer") },
                processor = { entry, _ ->
                    processed += Triple(entry.message, Thread.currentThread().name, entry.sequence)
                },
            )

        queue.enqueue(pending("first", sequence = 41L))
        queue.enqueue(pending("second", sequence = 42L))
        queue.flush()

        assertEquals(listOf("first", "second"), processed.map { it.first })
        assertEquals(setOf("diagnostics-test-writer"), processed.map { it.second }.toSet())
        assertNotEquals(callerThread, processed.first().second)
        assertEquals(listOf(41L, 42L), processed.map { it.third })
    }

    @Test
    fun releaseDebugGateKeepsAuthCriticalEvidenceOnly() {
        assertFalse(shouldEmitDiagnostic(includeNonCritical = false, authEvent = null))
        assertTrue(shouldEmitDiagnostic(includeNonCritical = false, authEvent = "auth_http_401"))
        assertTrue(shouldEmitDiagnostic(includeNonCritical = true, authEvent = null))
    }

    @Test
    fun saturationDropsWithoutBlockingCallerAndReportsDropCount() {
        val processorStarted = CountDownLatch(1)
        val releaseProcessor = CountDownLatch(1)
        val processed = CopyOnWriteArrayList<Pair<String, Long>>()
        val queue =
            SlockDiagnosticsDispatchQueue(
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
        queue.flush()
        queue.enqueue(pending("after-drop"))
        queue.flush()

        assertTrue("saturated enqueue blocked for ${enqueueMillis}ms", enqueueMillis < 100L)
        assertEquals(listOf("running", "queued", "after-drop"), processed.map { it.first })
        assertEquals(1L, processed.last().second)
    }

    @Test
    fun saturationPreservesReleaseAuthCandidateInEmergencySlot() {
        val processorStarted = CountDownLatch(1)
        val releaseProcessor = CountDownLatch(1)
        val processed = CopyOnWriteArrayList<Pair<String, Long>>()
        val queue =
            SlockDiagnosticsDispatchQueue(
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
        queue.enqueue(pending("auth-candidate", includeNonCritical = false))

        releaseProcessor.countDown()
        queue.flush()

        assertEquals(listOf("running", "queued", "auth-candidate"), processed.map { it.first })
        assertEquals(0L, processed.last().second)
    }

    @Test
    fun saturationEmergencySlotKeepsFirstCandidateAndCountsLaterOverflow() {
        val processorStarted = CountDownLatch(1)
        val releaseProcessor = CountDownLatch(1)
        val processed = CopyOnWriteArrayList<Pair<String, Long>>()
        val queue =
            SlockDiagnosticsDispatchQueue(
                capacity = 1,
                threadFactory = ThreadFactory { runnable -> Thread(runnable, "diagnostics-test-writer") },
                processor = { entry, dropped ->
                    processorStarted.countDown()
                    releaseProcessor.await(2L, TimeUnit.SECONDS)
                    processed += entry.message to dropped
                },
            )

        queue.enqueue(pending("running", sequence = 1L))
        assertTrue(processorStarted.await(1L, TimeUnit.SECONDS))
        queue.enqueue(pending("queued", sequence = 2L))
        queue.enqueue(pending("first-emergency", includeNonCritical = false, sequence = 3L))
        queue.enqueue(pending("later-overflow", includeNonCritical = false, sequence = 4L))

        releaseProcessor.countDown()
        queue.flush()

        assertEquals(listOf("running", "queued", "first-emergency"), processed.map { it.first })
        assertEquals(1L, processed.last().second)
    }

    private fun pending(
        message: String,
        includeNonCritical: Boolean = true,
        sequence: Long = message.length.toLong(),
    ): PendingDiagnosticLog =
        PendingDiagnosticLog(
            level = "debug",
            tag = "DispatchTest",
            message = message,
            timestampMillis = 123L,
            threadName = "original-caller",
            sequence = sequence,
            includeNonCritical = includeNonCritical,
        )
}
