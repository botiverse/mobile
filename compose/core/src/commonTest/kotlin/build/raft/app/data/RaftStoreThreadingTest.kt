package build.raft.app.data

import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class RaftStoreThreadingTest {
    private val violations = mutableListOf<RaftStoreThreadingViolation>()

    @BeforeTest
    fun installGuard() {
        RaftStoreThreading.installViolationHandler { violation -> violations += violation }
    }

    @AfterTest
    fun removeGuard() {
        RaftStoreThreading.installViolationHandler(null)
    }

    @Test
    fun writesOnTheStoreDispatcherPassTheGuard() = runTest {
        val state = raftStoreState(0, "TestStore")

        withContext(RaftStoreThreading.dispatcher) {
            assertTrue(RaftStoreThreading.isOnStoreDispatcher)
            state.value = 1
            state.update { it + 1 }
            state.emit(3)
        }

        assertEquals(3, state.value)
        assertEquals(emptyList(), violations)
    }

    @Test
    fun writesFromAnyOtherThreadAreReportedWithTheStoreName() = runTest {
        val state = raftStoreState("initial", "SharedHomeStore")
        assertFalse(RaftStoreThreading.isOnStoreDispatcher)

        state.value = "ui-thread write"
        state.update { "$it, again" }

        assertEquals("ui-thread write, again", state.value, "the guard reports; it never drops a write")
        assertEquals(listOf("SharedHomeStore", "SharedHomeStore"), violations.map { it.subject })
    }

    @Test
    fun withoutAHandlerNothingIsChecked() = runTest {
        RaftStoreThreading.installViolationHandler(null)
        val state = raftStoreState(0, "TestStore")

        state.value = 1

        assertEquals(1, state.value)
        assertEquals(emptyList(), violations)
    }

    @Test
    fun blockingCallsAreReportedOnlyOnTheStoreDispatcher() = runTest {
        RaftStoreThreading.checkNotOnStoreDispatcher("off.store")
        withContext(RaftStoreThreading.dispatcher) {
            RaftStoreThreading.checkNotOnStoreDispatcher("RaftSqlDatabase.transaction")
        }

        assertEquals(
            listOf(RaftStoreThreadingViolation.Kind.BlockingCallOnStoreDispatcher to "RaftSqlDatabase.transaction"),
            violations.map { it.kind to it.subject },
        )
    }

    @Test
    fun runOffStoreDispatcherLeavesTheStoreDispatcherForBlockingWork() = runTest {
        val confinedInside =
            withContext(RaftStoreThreading.dispatcher) {
                RaftStoreThreading.runOffStoreDispatcher {
                    RaftStoreThreading.checkNotOnStoreDispatcher("blocking")
                    RaftStoreThreading.isOnStoreDispatcher
                }
            }

        assertFalse(confinedInside)
        assertEquals(emptyList(), violations)
    }

    @Test
    fun storeDispatcherAdmitsOneTaskAtATime() {
        // Deterministic: a manual delegate stands in for the thread pool, so the test observes how
        // many workers the store dispatcher hands to it instead of racing real threads.
        val pool = ManualDispatcher()
        val scope = CoroutineScope(raftStoreConfinedDispatcher(pool))
        val order = mutableListOf<String>()
        var workersQueuedWhileFirstTaskRan = -1

        scope.launch {
            order += "first confined=${RaftStoreThreading.isOnStoreDispatcher}"
            scope.launch { order += "third" }
            workersQueuedWhileFirstTaskRan = pool.queued
        }
        scope.launch { order += "second" }

        assertEquals(1, pool.queued, "two pending tasks, but only one worker may reach the pool")
        pool.runNext()

        assertEquals(0, workersQueuedWhileFirstTaskRan, "no second worker while a store task is running")
        assertEquals(listOf("first confined=true", "second", "third"), order)
        assertEquals(0, pool.queued)
        assertFalse(RaftStoreThreading.isOnStoreDispatcher, "confinement ends with the worker")
    }

    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        val queued: Int get() = tasks.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runNext() = tasks.removeFirst().run()
    }
}
