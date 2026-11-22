package build.raft.app.data

import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

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
    fun writesFromAnyOtherThreadAreReportedWithTheStoreName() = runTest {
        val state = raftStoreState("initial", "SharedHomeStore")
        assertFalse(RaftStoreThreading.isOnStoreDispatcher)

        state.value = "ui-thread write"
        state.update { "$it, again" }

        assertEquals("ui-thread write, again", state.value, "the guard reports; it never drops a write")
        assertEquals(listOf("SharedHomeStore", "SharedHomeStore"), violations.map { it.subject })
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
