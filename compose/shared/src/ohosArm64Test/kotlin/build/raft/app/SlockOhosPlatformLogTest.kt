package build.raft.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockOhosPlatformLogTest {
    @Test
    fun preparedBatchEntryIsSanitizedAndKeepsCallerTimestamp() {
        val prepared =
            prepareSlockOhosLog(
                level = SlockLogLevel.Warn,
                tag = "SlockAuth",
                message =
                    "auth_failed authorization=Bearer secret-token " +
                        "access_token=access-secret password=hunter2 user=qa@example.com",
                timestampMillis = 123_456L,
                sourceSeq = 17L,
                dropped = 3,
                hint = SlockPlatformLogHint.AuthCritical
            )

        assertEquals("warn", prepared.level)
        assertEquals("SlockAuth", prepared.tag)
        assertEquals("auth_failed", prepared.event)
        assertEquals(123_456L, prepared.timestampMillis)
        assertEquals(17L, prepared.sourceSeq)
        assertEquals(3, prepared.dropped)
        assertEquals("kmp-worker", prepared.thread)
        assertTrue(prepared.critical)
        assertFalse(prepared.message.contains("secret-token"))
        assertFalse(prepared.message.contains("access-secret"))
        assertFalse(prepared.message.contains("hunter2"))
        assertFalse(prepared.message.contains("qa@example.com"))
        assertTrue(prepared.message.contains("[redacted]"))
        assertFalse(prepared.truncated)
    }

    @Test
    fun preparedBatchEntryTruncatesMessageAndEventOffHostThread() {
        val prepared =
            prepareSlockOhosLog(
                level = SlockLogLevel.Info,
                tag = "T".repeat(80),
                message = "event_name ${"x".repeat(3_000)}",
                timestampMillis = 7L
            )

        assertEquals(48, prepared.tag.length)
        assertEquals("event_name", prepared.event)
        assertEquals(2_048, prepared.message.length)
        assertTrue(prepared.truncated)
    }

    @Test
    fun criticalLaneSurvivesNormalSaturationAndMergesBySourceSequence() {
        val queue = SlockOhosProducerQueue(normalCapacity = 2, criticalCapacity = 1)
        assertTrue(queue.tryEnqueue(pendingLog(1L), SlockOhosProducerLane.Normal))
        assertTrue(queue.tryEnqueue(pendingLog(2L), SlockOhosProducerLane.Normal))
        assertFalse(queue.tryEnqueue(pendingLog(3L), SlockOhosProducerLane.Normal))
        assertTrue(queue.tryEnqueue(pendingLog(4L), SlockOhosProducerLane.Critical))
        assertFalse(queue.tryEnqueue(pendingLog(5L), SlockOhosProducerLane.Critical))

        assertEquals(1L, queue.tryPollMerged()?.sourceSeq)
        assertEquals(2L, queue.tryPollMerged()?.sourceSeq)
        assertEquals(4L, queue.tryPollMerged()?.sourceSeq)
        assertEquals(null, queue.tryPollMerged())
        assertFalse(queue.hasPending())
    }

    @Test
    fun criticalOverflowProducesStructuredSecretFreeEvidence() {
        val breadcrumb =
            slockOhosDropBreadcrumb(
                lane = SlockOhosProducerLane.Critical,
                count = 7,
                capacity = 32,
                timestampMillis = 99L
            )

        assertEquals(SlockLogLevel.Error, breadcrumb.level)
        assertEquals(7, breadcrumb.dropped)
        assertEquals(0L, breadcrumb.sourceSeq)
        assertEquals("slock_ohos_log_queue_dropped lane=critical count=7 capacity=32", breadcrumb.message)
    }

    @Test
    fun flushBarrierWaitsForDelayedPreClearBatchBeforeAck() = runBlocking {
        val barrier = SlockOhosProducerFlushBarrier()
        val completion = CompletableDeferred<Boolean>()
        barrier.add(completion)
        barrier.recordDelivery(success = true)

        barrier.completeIfIdle(hasPending = true)
        assertFalse(completion.isCompleted)

        barrier.completeIfIdle(hasPending = false)
        assertTrue(completion.await())
    }

    @Test
    fun flushBarrierFailsClosedInsteadOfExportingCompleteAfterDeliveryFailure() = runBlocking {
        val barrier = SlockOhosProducerFlushBarrier()
        val completion = CompletableDeferred<Boolean>()
        barrier.add(completion)
        barrier.recordDelivery(success = false)
        barrier.completeIfIdle(hasPending = false)

        assertFalse(completion.await())

        val retry = CompletableDeferred<Boolean>()
        barrier.add(retry)
        barrier.completeIfIdle(hasPending = false)
        assertFalse(retry.await())
    }

    @Test
    fun producerPendingStateCoversDelayedBatchUntilHostAdmission() {
        val transitions = mutableListOf<Boolean>()
        val state = SlockOhosProducerPendingState { pending ->
            transitions += pending
            true
        }

        state.accepted()
        state.accepted()
        assertEquals(listOf(true), transitions)
        state.delivered(1)
        assertEquals(listOf(true), transitions)
        state.delivered(1)
        assertEquals(listOf(true, false), transitions)
        assertTrue(state.isHealthy())
    }

    @Test
    fun producerPendingNotificationFailureMakesExportBarrierFailClosed() {
        val state = SlockOhosProducerPendingState { false }
        state.accepted()
        state.delivered(1)
        assertFalse(state.isHealthy())
    }

    private fun pendingLog(sourceSeq: Long): SlockOhosPendingLog =
        SlockOhosPendingLog(
            level = SlockLogLevel.Info,
            tag = "SlockTest",
            message = "event_$sourceSeq",
            timestampMillis = sourceSeq,
            sourceSeq = sourceSeq,
            hint = SlockPlatformLogHint.None
        )
}
