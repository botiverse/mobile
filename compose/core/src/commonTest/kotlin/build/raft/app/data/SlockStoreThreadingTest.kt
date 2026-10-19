package build.raft.app.data

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class SlockStoreThreadingTest {
    private val violations = mutableListOf<SlockStoreThreadingViolation>()

    @BeforeTest
    fun installGuard() {
        SlockStoreThreading.installViolationHandler { violation -> violations += violation }
    }

    @AfterTest
    fun removeGuard() {
        SlockStoreThreading.installViolationHandler(null)
    }

    @Test
    fun writesOnTheStoreDispatcherPassTheGuard() = runTest {
        val state = slockStoreState(0, "TestStore")

        withContext(SlockStoreThreading.dispatcher) {
            assertTrue(SlockStoreThreading.isOnStoreDispatcher)
            state.value = 1
            state.update { it + 1 }
            state.emit(3)
        }

        assertEquals(3, state.value)
        assertEquals(emptyList(), violations)
    }

    @Test
    fun writesFromAnyOtherThreadAreReportedWithTheStoreName() = runTest {
        val state = slockStoreState("initial", "SharedHomeStore")
        assertFalse(SlockStoreThreading.isOnStoreDispatcher)

        state.value = "ui-thread write"
        state.update { "$it, again" }

        assertEquals("ui-thread write, again", state.value, "the guard reports; it never drops a write")
        assertEquals(listOf("SharedHomeStore", "SharedHomeStore"), violations.map { it.subject })
    }

    @Test
    fun withoutAHandlerNothingIsChecked() = runTest {
        SlockStoreThreading.installViolationHandler(null)
        val state = slockStoreState(0, "TestStore")

        state.value = 1

        assertEquals(1, state.value)
        assertEquals(emptyList(), violations)
    }

    @Test
    fun blockingCallsAreReportedOnlyOnTheStoreDispatcher() = runTest {
        SlockStoreThreading.checkNotOnStoreDispatcher("off.store")
        withContext(SlockStoreThreading.dispatcher) {
            SlockStoreThreading.checkNotOnStoreDispatcher("SlockSqlDatabase.transaction")
        }

        assertEquals(
            listOf(SlockStoreThreadingViolation.Kind.BlockingCallOnStoreDispatcher to "SlockSqlDatabase.transaction"),
            violations.map { it.kind to it.subject },
        )
    }

    @Test
    fun runOffStoreDispatcherLeavesTheStoreDispatcherForBlockingWork() = runTest {
        val confinedInside =
            withContext(SlockStoreThreading.dispatcher) {
                SlockStoreThreading.runOffStoreDispatcher {
                    SlockStoreThreading.checkNotOnStoreDispatcher("blocking")
                    SlockStoreThreading.isOnStoreDispatcher
                }
            }

        assertFalse(confinedInside)
        assertEquals(emptyList(), violations)
    }

    @Test
    fun storeScopeRunsOneTaskAtATime() = runTest {
        // A plain read-modify-write: lost updates are only impossible if tasks never overlap.
        var counter = 0
        var overlapping = 0
        var maxOverlap = 0
        val scope = SlockStoreThreading.newScope()
        val jobs =
            List(500) {
                scope.launch {
                    overlapping += 1
                    maxOverlap = maxOf(maxOverlap, overlapping)
                    val read = counter
                    repeat(100) { counter = read }
                    counter = read + 1
                    overlapping -= 1
                }
            }
        jobs.joinAll()

        assertEquals(500, counter)
        assertEquals(1, maxOverlap)
    }
}
