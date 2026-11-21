package build.raft.app.shared

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidRealtimeNotificationQueueTest {
    @Test
    fun postFollowedByRetractPreservesStrictFifoOrderEvenWhenPostSuspends() = runTest {
        val workerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val queue = AndroidRealtimeNotificationQueue(workerScope)
            val events = mutableListOf<String>()
            val sessionGate = CompletableDeferred<Unit>()

            val postJob =
                launch(StandardTestDispatcher(testScheduler)) {
                    queue.enqueueAndAwait {
                        events += "post:start"
                        sessionGate.await()
                        events += "post:end"
                    }
                }
            testScheduler.runCurrent()

            queue.enqueue { events += "retract" }
            testScheduler.runCurrent()

            assertEquals(
                "retract must wait for the earlier suspended post to finish",
                listOf("post:start"),
                events,
            )
            assertTrue(postJob.isActive)

            sessionGate.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("post:start", "post:end", "retract"), events)
            assertTrue(postJob.isCompleted)
        } finally {
            workerScope.cancel()
        }
    }

    @Test
    fun retractFollowedByPostRunsRetractBeforePost() = runTest {
        val workerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val queue = AndroidRealtimeNotificationQueue(workerScope)
            val events = mutableListOf<String>()

            queue.enqueue { events += "retract" }
            val postJob =
                launch(StandardTestDispatcher(testScheduler)) {
                    queue.enqueueAndAwait { events += "post" }
                }

            testScheduler.advanceUntilIdle()
            assertEquals(listOf("retract", "post"), events)
            assertTrue(postJob.isCompleted)
        } finally {
            workerScope.cancel()
        }
    }

    @Test
    fun failingEntryReportsErrorWithoutBreakingSubsequentWork() = runTest {
        val workerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val errors = mutableListOf<String>()
            val events = mutableListOf<String>()
            val queue = AndroidRealtimeNotificationQueue(workerScope) { error -> errors += error.message.orEmpty() }

            queue.enqueue { throw IllegalStateException("boom") }
            val second =
                launch(StandardTestDispatcher(testScheduler)) {
                    queue.enqueueAndAwait { events += "after_error" }
                }

            testScheduler.advanceUntilIdle()
            assertEquals(listOf("boom"), errors)
            assertEquals(listOf("after_error"), events)
            assertTrue(second.isCompleted)
        } finally {
            workerScope.cancel()
        }
    }
}
