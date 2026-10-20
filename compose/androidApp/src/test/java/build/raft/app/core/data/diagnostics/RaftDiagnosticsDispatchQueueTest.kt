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
    fun dispatchesInOrderOffCallerThread() {
        val callerThread = Thread.currentThread().name
        val processed = CopyOnWriteArrayList<Triple<String, String, Long>>()
        val queue =
            RaftDiagnosticsDispatchQueue(
                capacity = 8,
                initialSequence = 40L,
                threadFactory = ThreadFactory { runnable -> Thread(runnable, "diagnostics-test-writer") },
                processor = { entry, _ ->
                    processed += Triple(entry.message, Thread.currentThread().name, entry.sequence)
                },
            )

        queue.enqueue(pending("first"))
        queue.enqueue(pending("second"))
        assertTrue(queue.flush())

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
    fun fixedCostAuthHintReservesOnlyKnownCriticalMarkers() {
        assertFalse(hasFixedCostAuthCriticalHint("shared_auth auth_login_start provider=password"))
        assertTrue(hasFixedCostAuthCriticalHint("shared_auth refresh_failed status=401"))
        assertTrue(hasFixedCostAuthCriticalHint("shared_realtime_auth_refresh_failed reason=resume"))
    }

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
    fun saturationKeepsTypedAuthCriticalWhenOrdinaryDebugDrops() {
        val processorStarted = CountDownLatch(1)
        val releaseProcessor = CountDownLatch(1)
        val processed = CopyOnWriteArrayList<Pair<String, Long>>()
        val queue =
            RaftDiagnosticsDispatchQueue(
                capacity = 1,
                criticalCapacity = 1,
                initialSequence = 0L,
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
        queue.enqueue(pending("ordinary-dropped"))
        queue.enqueue(pending("auth-critical", criticalHint = DiagnosticCriticalHint.AuthCritical))

        releaseProcessor.countDown()
        assertTrue(queue.flush())

        assertEquals(listOf("running", "queued", "auth-critical"), processed.map { it.first })
        assertEquals(1L, processed.last().second)
    }

    @Test
    fun reservedLaneCannotBeOvertakenByLaterNormalEntry() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val processed = CopyOnWriteArrayList<Long>()
        val queue =
            RaftDiagnosticsDispatchQueue(
                capacity = 1,
                criticalCapacity = 1,
                initialSequence = 0L,
                threadFactory = ThreadFactory { runnable -> Thread(runnable, "diagnostics-test-writer") },
                processor = { entry, _ ->
                    when (entry.sequence) {
                        1L -> {
                            firstStarted.countDown()
                            releaseFirst.await(2L, TimeUnit.SECONDS)
                        }
                        2L -> {
                            secondStarted.countDown()
                            releaseSecond.await(2L, TimeUnit.SECONDS)
                        }
                    }
                    processed += entry.sequence
                },
            )

        queue.enqueue(pending("running"))
        assertTrue(firstStarted.await(1L, TimeUnit.SECONDS))
        queue.enqueue(pending("queued"))
        queue.enqueue(pending("reserved", criticalHint = DiagnosticCriticalHint.AuthCritical))

        releaseFirst.countDown()
        assertTrue(secondStarted.await(1L, TimeUnit.SECONDS))
        queue.enqueue(pending("later-normal"))
        releaseSecond.countDown()

        assertTrue(queue.flush())

        assertEquals(listOf(1L, 2L, 3L, 4L), processed)
    }

    @Test
    fun reservedLaneCountsOverflowWithoutReplacingEarlierCriticalEntry() {
        val processorStarted = CountDownLatch(1)
        val releaseProcessor = CountDownLatch(1)
        val processed = CopyOnWriteArrayList<Pair<String, Long>>()
        val queue =
            RaftDiagnosticsDispatchQueue(
                capacity = 1,
                criticalCapacity = 1,
                initialSequence = 0L,
                threadFactory = ThreadFactory { runnable -> Thread(runnable, "diagnostics-test-writer") },
                processor = { entry, dropped ->
                    processorStarted.countDown()
                    releaseProcessor.await(2L, TimeUnit.SECONDS)
                    processed += entry.message to dropped
                },
            )

        queue.enqueue(pending("running"))
        assertTrue(processorStarted.await(1L, TimeUnit.SECONDS))
        queue.enqueue(pending("first-critical", criticalHint = DiagnosticCriticalHint.AuthCritical))
        queue.enqueue(pending("later-critical", criticalHint = DiagnosticCriticalHint.AuthCritical))

        releaseProcessor.countDown()
        assertTrue(queue.flush())

        assertEquals(listOf("running", "first-critical"), processed.map { it.first })
        assertEquals(1L, processed.last().second)
    }

    @Test
    fun flushReportsTimeoutAndLaterCompletion() {
        val processorStarted = CountDownLatch(1)
        val releaseProcessor = CountDownLatch(1)
        val queue =
            RaftDiagnosticsDispatchQueue(
                capacity = 1,
                criticalCapacity = 1,
                threadFactory = ThreadFactory { runnable -> Thread(runnable, "diagnostics-test-writer") },
                processor = { _, _ ->
                    processorStarted.countDown()
                    releaseProcessor.await(2L, TimeUnit.SECONDS)
                },
            )

        queue.enqueue(pending("slow"))
        assertTrue(processorStarted.await(1L, TimeUnit.SECONDS))

        assertFalse(queue.flush(timeoutMillis = 10L))
        releaseProcessor.countDown()
        assertTrue(queue.flush(timeoutMillis = 1_000L))
    }

    @Test
    fun processorFailureMakesBarrierFailWithoutStoppingLaterEntries() {
        val processed = CopyOnWriteArrayList<String>()
        val queue =
            RaftDiagnosticsDispatchQueue(
                capacity = 4,
                criticalCapacity = 1,
                threadFactory = ThreadFactory { runnable -> Thread(runnable, "diagnostics-test-writer") },
                processor = { entry, _ ->
                    if (entry.message == "broken") error("synthetic diagnostics failure")
                    processed += entry.message
                },
            )

        queue.enqueue(pending("broken"))
        queue.enqueue(pending("after-failure"))

        assertFalse(queue.flush())
        assertEquals(listOf("after-failure"), processed)
    }

    @Test
    fun concurrentProducersReceiveStrictAcceptedSequenceOrder() {
        val processed = CopyOnWriteArrayList<Long>()
        val queue =
            RaftDiagnosticsDispatchQueue(
                capacity = 256,
                criticalCapacity = 64,
                initialSequence = 0L,
                threadFactory = ThreadFactory { runnable -> Thread(runnable, "diagnostics-test-writer") },
                processor = { entry, _ -> processed += entry.sequence },
            )
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val producerId = AtomicInteger(0)

        repeat(2) {
            Thread {
                val id = producerId.incrementAndGet()
                ready.countDown()
                start.await(1L, TimeUnit.SECONDS)
                repeat(100) { index -> queue.enqueue(pending("producer-$id-$index")) }
                completed.countDown()
            }.start()
        }

        assertTrue(ready.await(1L, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(completed.await(2L, TimeUnit.SECONDS))
        assertTrue(queue.flush())
        assertEquals((1L..200L).toList(), processed)
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
