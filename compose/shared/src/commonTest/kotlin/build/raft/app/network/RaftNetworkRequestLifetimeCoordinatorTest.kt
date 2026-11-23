package build.raft.app.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RaftNetworkRequestLifetimeCoordinatorTest {
    @Test
    fun requestClassifiesOnlyLongRunningComposerMutationsAsProcessBound() {
        assertEquals(
            RaftNetworkMethodClass.ProcessMutation,
            raftNetworkMethodClass("POST", "https://example.test/api/messages", serverScoped = true)
        )
        // Every human send now goes to `POST /api/v2/messages`: still process-bound.
        assertEquals(
            RaftNetworkMethodClass.ProcessMutation,
            raftNetworkMethodClass("POST", "https://example.test/api/v2/messages", serverScoped = true)
        )
        assertEquals(
            RaftNetworkMethodClass.ProcessMutation,
            raftNetworkMethodClass(
                "POST",
                "https://example.test/api/attachments/upload?serverId=server-1",
                serverScoped = true
            )
        )
        assertEquals(
            RaftNetworkMethodClass.Mutation,
            raftNetworkMethodClass(
                "POST",
                "https://example.test/api/channels/channel-1/read",
                serverScoped = true
            )
        )
        assertEquals(
            RaftNetworkMethodClass.Read,
            raftNetworkMethodClass("GET", "https://example.test/api/messages", serverScoped = true)
        )
        assertEquals(
            RaftNetworkMethodClass.Mutation,
            raftNetworkMethodClass(
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
        )) {
            assertEquals(RaftNetworkMethodClass.ProcessMutation, raftNetworkMethodClass("POST", url, serverScoped = false), url)
        }
        assertEquals(
            RaftNetworkMethodClass.Mutation,
            raftNetworkMethodClass("POST", "https://api.raft.build/api/auth/app-login/start", serverScoped = false),
        )
        assertEquals(
            RaftNetworkMethodClass.Read,
            raftNetworkMethodClass("GET", "https://api.raft.build/api/auth/app-login/complete", serverScoped = false),
        )
    }

    @Test
    fun lifecycleRotationCompletesOldAwaitImmediatelyAndFencesLateSuccess() = runBlocking {
        var now = 1_000L
        val coordinator = RaftNetworkRequestLifetimeCoordinator(nowMillis = { now })
        coordinator.onProcessVisibilityChanged(foreground = true)
        val callbackReady = CompletableDeferred<(String) -> Unit>()
        var cancelCount = 0
        val pending =
            async {
                coordinator.execute(
                    methodClass = RaftNetworkMethodClass.Read,
                    timeoutMillis = 30_000L
                ) { callback ->
                    callbackReady.complete(callback)
                    RaftNetworkRequestCancelHandle { cancelCount += 1 }
                }
            }

        val lateCallback = callbackReady.await()
        now = 1_250L
        val transition = coordinator.onProcessVisibilityChanged(foreground = false)
        val outcome = withTimeout(250L) { pending.await() }

        assertTrue(transition.changed)
        assertEquals(1, transition.retiredRequestCount)
        val retired = assertIs<RaftNetworkRequestLifetimeOutcome.Retired>(outcome)
        assertEquals(1L, retired.descriptor.generation)
        assertEquals(31_000L, retired.descriptor.configuredDeadlineAtMillis)
        assertEquals(2L, retired.replacementGeneration)
        assertFalse(retired.processForeground)
        assertFalse(retired.mutationOutcomeUnknown)
        assertEquals(1, cancelCount)
        assertEquals(0, coordinator.activeRequestCountForTest())

        lateCallback("late-success")
        assertIs<RaftNetworkRequestLifetimeOutcome.Retired>(outcome)
        assertEquals(1, cancelCount)
    }

    @Test
    fun processMutationRemainsBoundAcrossVisibilityEdgesAndIsNeverReplayed() = runBlocking {
        val coordinator = RaftNetworkRequestLifetimeCoordinator(nowMillis = { 10_000L })
        coordinator.onProcessVisibilityChanged(foreground = true)
        val callbackReady = CompletableDeferred<(String) -> Unit>()
        var startCount = 0
        var cancelCount = 0
        val pending =
            async {
                coordinator.execute(
                    methodClass = RaftNetworkMethodClass.ProcessMutation,
                    timeoutMillis = 30_000L
                ) { callback ->
                    startCount += 1
                    callbackReady.complete(callback)
                    RaftNetworkRequestCancelHandle { cancelCount += 1 }
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

        val completed = assertIs<RaftNetworkRequestLifetimeOutcome.Completed<String>>(outcome)
        assertEquals("mutation-success", completed.value)
        assertEquals(RaftNetworkMethodClass.ProcessMutation, completed.descriptor.methodClass)
        assertEquals(1, startCount)
        assertEquals(0, cancelCount)
        assertEquals(0, coordinator.activeRequestCountForTest())
    }

    @Test
    fun updateProcessVisibilityAdvancesGenerationBeforeNotifyingForegroundObservers() = runBlocking {
        // Seed foreground -> background so the next foreground call is a real visibility edge.
        updateRaftProcessNetworkVisibility(foreground = true)
        updateRaftProcessNetworkVisibility(foreground = false)

        val observedForegroundState = CompletableDeferred<Boolean>()
        val startedDuringForegroundEvent = CompletableDeferred<RaftNetworkRequestLifetimeOutcome<String>>()
        val readCallback = CompletableDeferred<(String) -> Unit>()

        val collector =
            launch(Dispatchers.Unconfined) {
                build.raft.app.data.RaftFreshnessSignal.events.collect { event ->
                    if (event.trigger == build.raft.app.data.RaftFreshnessTrigger.Foreground && !observedForegroundState.isCompleted) {
                        observedForegroundState.complete(raftProcessIsForeground())
                        launch(Dispatchers.Unconfined) {
                            val outcome =
                                RaftProcessNetworkRequestLifetime.coordinator.execute(
                                    methodClass = RaftNetworkMethodClass.Read,
                                    timeoutMillis = 30_000L,
                                ) { callback ->
                                    readCallback.complete(callback)
                                    RaftNetworkRequestCancelHandle {}
                                }
                            startedDuringForegroundEvent.complete(outcome)
                        }
                    }
                }
            }
        try {
            val retired = updateRaftProcessNetworkVisibility(foreground = true)
            assertEquals(0, retired)
            assertTrue(
                withTimeout(250L) { observedForegroundState.await() },
                "coordinator must already be foreground when RaftFreshnessSignal.onForeground() fires",
            )
            val callback = withTimeout(250L) { readCallback.await() }
            assertFalse(
                startedDuringForegroundEvent.isCompleted,
                "a foreground catch-up started from the freshness signal must not be retired by the same edge",
            )
            callback("ok")
            val outcome = withTimeout(250L) { startedDuringForegroundEvent.await() }
            assertEquals("ok", assertIs<RaftNetworkRequestLifetimeOutcome.Completed<String>>(outcome).value)
        } finally {
            collector.cancel()
        }
    }
}
