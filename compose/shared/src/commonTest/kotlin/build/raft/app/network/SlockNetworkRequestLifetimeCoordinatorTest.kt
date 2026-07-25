package build.raft.app.network

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SlockNetworkRequestLifetimeCoordinatorTest {
    @Test
    fun lifecycleRotationCompletesOldAwaitImmediatelyAndFencesLateSuccess() = runBlocking {
        var now = 1_000L
        val coordinator = SlockNetworkRequestLifetimeCoordinator(nowMillis = { now })
        coordinator.onProcessVisibilityChanged(foreground = true)
        val callbackReady = CompletableDeferred<(String) -> Unit>()
        var cancelCount = 0
        val pending =
            async {
                coordinator.execute(
                    methodClass = SlockNetworkMethodClass.Read,
                    timeoutMillis = 30_000L
                ) { callback ->
                    callbackReady.complete(callback)
                    SlockNetworkRequestCancelHandle { cancelCount += 1 }
                }
            }

        val lateCallback = callbackReady.await()
        now = 1_250L
        val transition = coordinator.onProcessVisibilityChanged(foreground = false)
        val outcome = withTimeout(250L) { pending.await() }

        assertTrue(transition.changed)
        assertEquals(1, transition.retiredRequestCount)
        val retired = assertIs<SlockNetworkRequestLifetimeOutcome.Retired>(outcome)
        assertEquals(1L, retired.descriptor.generation)
        assertEquals(31_000L, retired.descriptor.configuredDeadlineAtMillis)
        assertEquals(2L, retired.replacementGeneration)
        assertFalse(retired.processForeground)
        assertFalse(retired.mutationOutcomeUnknown)
        assertEquals(1, cancelCount)
        assertEquals(0, coordinator.activeRequestCountForTest())

        lateCallback("late-success")
        assertIs<SlockNetworkRequestLifetimeOutcome.Retired>(outcome)
        assertEquals(1, cancelCount)
    }

    @Test
    fun hostVisibilitySequenceDeduplicatesSignalsAndRetiresPendingRequestOnce() = runBlocking {
        val coordinator = SlockNetworkRequestLifetimeCoordinator(nowMillis = { 1_000L })
        val transitions = mutableListOf<SlockNetworkLifecycleTransition>()
        transitions += coordinator.onProcessVisibilityChanged(foreground = true)
        transitions += coordinator.onProcessVisibilityChanged(foreground = true)

        val callbackReady = CompletableDeferred<(String) -> Unit>()
        val startCount = atomic(0)
        val cancelCount = atomic(0)
        val pending =
            async {
                coordinator.execute(
                    methodClass = SlockNetworkMethodClass.Read,
                    timeoutMillis = 30_000L
                ) { callback ->
                    startCount.incrementAndGet()
                    callbackReady.complete(callback)
                    SlockNetworkRequestCancelHandle { cancelCount.incrementAndGet() }
                }
            }
        val lateCallback = callbackReady.await()

        transitions += coordinator.onProcessVisibilityChanged(foreground = false)
        transitions += coordinator.onProcessVisibilityChanged(foreground = false)
        transitions += coordinator.onProcessVisibilityChanged(foreground = true)
        val outcome = pending.await()

        assertEquals(listOf(false, false, true, false, true), transitions.map { it.changed })
        assertEquals(listOf(1L, 1L, 2L, 2L, 3L), transitions.map { it.generation })
        assertEquals(listOf(0, 0, 1, 0, 0), transitions.map { it.retiredRequestCount })
        val retired = assertIs<SlockNetworkRequestLifetimeOutcome.Retired>(outcome)
        assertEquals(1L, retired.descriptor.generation)
        assertEquals(2L, retired.replacementGeneration)
        assertFalse(retired.processForeground)
        assertEquals(1, startCount.value)
        assertEquals(1, cancelCount.value)
        assertEquals(0, coordinator.activeRequestCountForTest())

        lateCallback("late-after-deduplicated-host-sequence")
        assertEquals(1, cancelCount.value)
        assertEquals(0, coordinator.activeRequestCountForTest())
    }

    @Test
    fun lifecycleRetirementBeforeHandleAttachmentCancelsOnceAndFencesLateCallback() = runBlocking {
        val coordinator = SlockNetworkRequestLifetimeCoordinator(nowMillis = { 2_000L })
        coordinator.onProcessVisibilityChanged(foreground = true)
        val starterEntered = CompletableDeferred<Unit>()
        val releaseStarter = CompletableDeferred<Unit>()
        val callbackReady = CompletableDeferred<(String) -> Unit>()
        val cancelCount = atomic(0)
        val pending =
            async(Dispatchers.Default) {
                coordinator.execute(
                    methodClass = SlockNetworkMethodClass.Read,
                    timeoutMillis = 30_000L
                ) { callback ->
                    callbackReady.complete(callback)
                    starterEntered.complete(Unit)
                    runBlocking { releaseStarter.await() }
                    SlockNetworkRequestCancelHandle { cancelCount.incrementAndGet() }
                }
            }
        starterEntered.await()
        val lateCallback = callbackReady.await()

        val transition = coordinator.onProcessVisibilityChanged(foreground = false)
        lateCallback("late-before-handle")

        assertTrue(transition.changed)
        assertEquals(1, transition.retiredRequestCount)
        assertEquals(0, coordinator.activeRequestCountForTest())

        releaseStarter.complete(Unit)
        val outcome = withTimeout(250L) { pending.await() }

        assertIs<SlockNetworkRequestLifetimeOutcome.Retired>(outcome)
        assertEquals(1, cancelCount.value)
        lateCallback("late-after-handle")
        assertEquals(1, cancelCount.value)
    }

    @Test
    fun newGenerationReadCanSucceedAfterRetirement() = runBlocking {
        val coordinator = SlockNetworkRequestLifetimeCoordinator(nowMillis = { 5_000L })
        coordinator.onProcessVisibilityChanged(foreground = true)
        coordinator.onProcessVisibilityChanged(foreground = false)

        val outcome =
            coordinator.execute(
                methodClass = SlockNetworkMethodClass.Read,
                timeoutMillis = 30_000L
            ) { callback ->
                callback("fresh")
                SlockNetworkRequestCancelHandle { error("completed calls must not be cancelled") }
            }

        val completed = assertIs<SlockNetworkRequestLifetimeOutcome.Completed<String>>(outcome)
        assertEquals("fresh", completed.value)
        assertEquals(2L, completed.descriptor.generation)
        assertEquals(0, coordinator.activeRequestCountForTest())
    }

    @Test
    fun retiredMutationIsOutcomeUnknownAndNeverReplayed() = runBlocking {
        val coordinator = SlockNetworkRequestLifetimeCoordinator(nowMillis = { 10_000L })
        coordinator.onProcessVisibilityChanged(foreground = true)
        val callbackReady = CompletableDeferred<(String) -> Unit>()
        var startCount = 0
        var cancelCount = 0
        val pending =
            async {
                coordinator.execute(
                    methodClass = SlockNetworkMethodClass.Mutation,
                    timeoutMillis = 30_000L
                ) { callback ->
                    startCount += 1
                    callbackReady.complete(callback)
                    SlockNetworkRequestCancelHandle { cancelCount += 1 }
                }
            }
        val lateCallback = callbackReady.await()

        coordinator.onProcessVisibilityChanged(foreground = false)
        val outcome = withTimeout(250L) { pending.await() }
        lateCallback("late-mutation-success")

        val retired = assertIs<SlockNetworkRequestLifetimeOutcome.Retired>(outcome)
        assertTrue(retired.mutationOutcomeUnknown)
        assertEquals(SlockNetworkMethodClass.Mutation, retired.descriptor.methodClass)
        assertEquals(1, startCount)
        assertEquals(1, cancelCount)
    }

    @Test
    fun callerCancellationCancelsThePhysicalCallWithoutPublishingATerminal() = runBlocking {
        val coordinator = SlockNetworkRequestLifetimeCoordinator()
        val callbackReady = CompletableDeferred<(String) -> Unit>()
        var cancelCount = 0
        val pending =
            async {
                coordinator.execute(
                    methodClass = SlockNetworkMethodClass.Read,
                    timeoutMillis = 30_000L
                ) { callback ->
                    callbackReady.complete(callback)
                    SlockNetworkRequestCancelHandle { cancelCount += 1 }
                }
            }
        val lateCallback = callbackReady.await()

        pending.cancelAndJoin()
        lateCallback("late")

        assertEquals(1, cancelCount)
        assertEquals(0, coordinator.activeRequestCountForTest())
    }
}
