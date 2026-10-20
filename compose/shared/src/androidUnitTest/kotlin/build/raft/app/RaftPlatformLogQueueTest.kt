package build.raft.app

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RaftPlatformLogQueueTest {
    @Test
    fun flushWaitsForAnEntryAdmittedAfterItsLaneWasPolledEmpty() = runTest {
        for (lane in RaftPlatformLogHint.entries) {
            val delivered = mutableListOf<String>()
            lateinit var queue: RaftPlatformLogQueue
            lateinit var atFlush: Deferred<List<String>>
            var admitted = false
            queue = RaftPlatformLogQueue(
                scope = backgroundScope,
                recordSink = { delivered += it.message },
                afterLanePoll = { polledLane ->
                    if (!admitted && polledLane == lane) {
                        admitted = true
                        assertTrue(queue.enqueue(RaftLogLevel.Info, "QueueTest", "before-flush", lane))
                        // Enqueue the marker now; resume inline when it completes, before
                        // the consumer can deliver an entry on a subsequent iteration.
                        atFlush = async(UnconfinedTestDispatcher(testScheduler)) {
                            queue.flush()
                            delivered.toList()
                        }
                    }
                }
            )

            runCurrent()

            assertTrue(admitted, "the consumer must reach the controlled interleaving for $lane")
            assertTrue(atFlush.isCompleted, "the flush must finish after dispatch for $lane")
            assertEquals(listOf("before-flush"), atFlush.await(), "accepted $lane entry must precede flush")
            assertEquals(listOf("before-flush"), delivered, "entry is delivered exactly once")
        }
    }

    @Test
    fun laterCriticalEntryCannotOvertakeNormalEntryMissedByFirstPoll() = runTest {
        val delivered = mutableListOf<String>()
        lateinit var queue: RaftPlatformLogQueue
        var admitted = false
        queue = RaftPlatformLogQueue(
            scope = backgroundScope,
            recordSink = { delivered += it.message },
            afterLanePoll = { lane ->
                if (!admitted && lane == RaftPlatformLogHint.None) {
                    admitted = true
                    assertTrue(queue.enqueue(RaftLogLevel.Info, "QueueTest", "normal-first", RaftPlatformLogHint.None))
                    assertTrue(queue.enqueue(RaftLogLevel.Warn, "QueueTest", "critical-second", RaftPlatformLogHint.AuthCritical))
                }
            }
        )

        runCurrent()

        assertTrue(admitted, "the normal lane must be polled before either entry is admitted")
        assertEquals(listOf("normal-first", "critical-second"), delivered)
    }
}
