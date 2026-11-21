package build.raft.app.widget

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RaftWidgetBroadcastRunnerTest {
    @Test
    fun finishesPendingResultAfterWorkCompletesOffCallerThread() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner =
            RaftWidgetBroadcastRunner(
                workDispatcher = dispatcher,
                coordinatorDispatcher = dispatcher,
                finishTimeoutMillis = 7_000L,
            )
        var workRan = false
        var finishCalls = 0

        runner.launch(finish = { finishCalls += 1 }) {
            workRan = true
        }
        // Not executed synchronously on the caller (main) thread.
        assertFalse(workRan)
        assertEquals(0, finishCalls)

        advanceUntilIdle()
        assertTrue(workRan)
        assertEquals(1, finishCalls)
    }

    @Test
    fun finishesPendingResultEvenWhenWorkThrows() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val errors = mutableListOf<Throwable>()
        val runner =
            RaftWidgetBroadcastRunner(
                workDispatcher = dispatcher,
                coordinatorDispatcher = dispatcher,
                finishTimeoutMillis = 7_000L,
                onError = { errors += it },
            )
        var finishCalls = 0

        runner.launch(finish = { finishCalls += 1 }) {
            error("simulated widget failure")
        }

        advanceUntilIdle()
        assertEquals(1, errors.size)
        assertEquals(1, finishCalls)
    }

    @Test
    fun finishesPendingResultAtTimeoutWhenWorkStalls() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner =
            RaftWidgetBroadcastRunner(
                workDispatcher = dispatcher,
                coordinatorDispatcher = dispatcher,
                finishTimeoutMillis = 7_000L,
            )
        val gate = CompletableDeferred<Unit>()
        var finishCalls = 0

        val job =
            runner.launch(finish = { finishCalls += 1 }) {
                gate.await()
            }

        advanceTimeBy(6_999L)
        runCurrent()
        assertEquals(0, finishCalls)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, finishCalls)

        // Release the background job so the test scheduler drains cleanly.
        job.cancel()
        advanceUntilIdle()
        assertEquals(1, finishCalls)
    }

    @Test
    fun runsWorkWhenPendingResultIsNull() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner =
            RaftWidgetBroadcastRunner(
                workDispatcher = dispatcher,
                coordinatorDispatcher = dispatcher,
            )
        var ran = false

        runner.launch(finish = null) {
            ran = true
        }
        assertFalse(ran)

        advanceUntilIdle()
        assertTrue(ran)
    }
}
