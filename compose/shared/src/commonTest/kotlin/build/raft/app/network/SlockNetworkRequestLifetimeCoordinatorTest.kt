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
    fun requestClassifiesOnlyLongRunningComposerMutationsAsProcessBound() {
        assertEquals(
            SlockNetworkMethodClass.ProcessMutation,
            slockNetworkMethodClass("POST", "https://example.test/api/messages", serverScoped = true)
        )
        assertEquals(
            SlockNetworkMethodClass.ProcessMutation,
            slockNetworkMethodClass(
                "POST",
                "https://example.test/api/attachments/upload?serverId=server-1",
                serverScoped = true
            )
        )
        assertEquals(
            SlockNetworkMethodClass.Mutation,
            slockNetworkMethodClass(
                "POST",
                "https://example.test/api/channels/channel-1/read",
                serverScoped = true
            )
        )
        assertEquals(
            SlockNetworkMethodClass.Read,
            slockNetworkMethodClass("GET", "https://example.test/api/messages", serverScoped = true)
        )
        assertEquals(
            SlockNetworkMethodClass.Mutation,
            slockNetworkMethodClass(
                "POST",
                "https://third-party.test/api/messages",
                serverScoped = false
            )
        )
    }

    @Test
    fun oneTimeAuthCodeExchangesAreProcessBoundEvenWithoutAServer() {
        for (url in listOf(
            "https://api.raft.build/api/auth/app-login/complete",
            "https://raft.example.com/custom/api/auth/app-login/complete",
            "https://api.raft.build/api/auth/mobile/oauth/complete",
        )) {
            assertEquals(SlockNetworkMethodClass.ProcessMutation, slockNetworkMethodClass("POST", url, serverScoped = false), url)
        }
        assertEquals(
            SlockNetworkMethodClass.Mutation,
            slockNetworkMethodClass("POST", "https://api.raft.build/api/auth/app-login/start", serverScoped = false),
        )
        assertEquals(
            SlockNetworkMethodClass.Read,
            slockNetworkMethodClass("GET", "https://api.raft.build/api/auth/app-login/complete", serverScoped = false),
        )
    }

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
    fun processMutationRemainsBoundAcrossVisibilityEdgesAndIsNeverReplayed() = runBlocking {
        val coordinator = SlockNetworkRequestLifetimeCoordinator(nowMillis = { 10_000L })
        coordinator.onProcessVisibilityChanged(foreground = true)
        val callbackReady = CompletableDeferred<(String) -> Unit>()
        var startCount = 0
        var cancelCount = 0
        val pending =
            async {
                coordinator.execute(
                    methodClass = SlockNetworkMethodClass.ProcessMutation,
                    timeoutMillis = 30_000L
                ) { callback ->
                    startCount += 1
                    callbackReady.complete(callback)
                    SlockNetworkRequestCancelHandle { cancelCount += 1 }
                }
            }
        val lateCallback = callbackReady.await()

        val background = coordinator.onProcessVisibilityChanged(foreground = false)
        val foreground = coordinator.onProcessVisibilityChanged(foreground = true)

        assertEquals(0, background.retiredRequestCount)
        assertEquals(0, foreground.retiredRequestCount)
        assertFalse(pending.isCompleted)
        assertEquals(1, coordinator.activeRequestCountForTest())

        lateCallback("mutation-success")
        val outcome = withTimeout(250L) { pending.await() }

        val completed = assertIs<SlockNetworkRequestLifetimeOutcome.Completed<String>>(outcome)
        assertEquals("mutation-success", completed.value)
        assertEquals(SlockNetworkMethodClass.ProcessMutation, completed.descriptor.methodClass)
        assertEquals(1, startCount)
        assertEquals(0, cancelCount)
        assertEquals(0, coordinator.activeRequestCountForTest())
    }

    @Test
    fun ordinaryMutationKeepsVisibilityScopedOutcomeUnknownContract() = runBlocking {
        val coordinator = SlockNetworkRequestLifetimeCoordinator(nowMillis = { 11_000L })
        coordinator.onProcessVisibilityChanged(foreground = true)
        val callbackReady = CompletableDeferred<(String) -> Unit>()
        var cancelCount = 0
        val pending =
            async {
                coordinator.execute(
                    methodClass = SlockNetworkMethodClass.Mutation,
                    timeoutMillis = 30_000L
                ) { callback ->
                    callbackReady.complete(callback)
                    SlockNetworkRequestCancelHandle { cancelCount += 1 }
                }
            }
        val lateCallback = callbackReady.await()

        val transition = coordinator.onProcessVisibilityChanged(foreground = false)
        val outcome = withTimeout(250L) { pending.await() }
        lateCallback("late")

        assertEquals(1, transition.retiredRequestCount)
        val retired = assertIs<SlockNetworkRequestLifetimeOutcome.Retired>(outcome)
        assertTrue(retired.mutationOutcomeUnknown)
        assertEquals(SlockNetworkMethodClass.Mutation, retired.descriptor.methodClass)
        assertEquals(1, cancelCount)
        assertEquals(0, coordinator.activeRequestCountForTest())
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
