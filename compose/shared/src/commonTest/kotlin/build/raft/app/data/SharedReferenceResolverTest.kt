package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

/**
 * artin 11-07: a reference opens from local data first; only a local miss fetches, the loading
 * state is that fetch phase alone, a fetched resolution is local on the next tap, and a failure
 * is an outcome with a reason (the caller toasts it and does not navigate).
 */
class SharedReferenceResolverTest {
    private val key =
        requireNotNull(SharedReferenceKey.of("env\u001fviewer\u001fserver-1", SharedReferenceKind.Thread, "proj-stamp", "BC037B0B"))

    /** The network: counts calls and records whether the loading state was on while it ran. */
    private class FakeReferenceNetwork(
        private val resolver: SharedReferenceResolver,
        var answer: DataResult<String> = DataResult.Content("thread-target"),
        val gate: CompletableDeferred<Unit>? = null
    ) {
        var calls = 0
        val fetchingDuringCall = mutableListOf<Set<String?>>()
        val started = CompletableDeferred<Unit>()

        suspend fun fetch(): DataResult<String> {
            calls += 1
            fetchingDuringCall += resolver.inFlight.value.values.map { it.actionId }.toSet()
            started.complete(Unit)
            gate?.await()
            return answer
        }
    }

    @Test
    fun aLocalHitOpensWithNoNetworkCallAndNoLoadingState() = runTest {
        val resolver = SharedReferenceResolver()
        val network = FakeReferenceNetwork(resolver)
        val fetchingSeenByLocal = mutableListOf<Map<Long, SharedReferenceFetch>>()

        val outcome =
            resolver.resolve(
                key = key,
                actionId = "message-a:thread",
                local = {
                    fetchingSeenByLocal += resolver.inFlight.value
                    "local-target"
                },
                fetch = network::fetch
            )

        val open = assertIs<SharedReferenceOutcome.Open<String>>(outcome)
        assertEquals("local-target", open.target)
        assertEquals(SharedReferenceSource.Local, open.source)
        assertEquals(0, network.calls, "a local hit must not call the network")
        assertEquals(listOf(emptyMap<Long, SharedReferenceFetch>()), fetchingSeenByLocal, "the local lookup shows no loading state")
        assertTrue(resolver.inFlight.value.isEmpty())
    }

    @Test
    fun aLocalMissFetchesExactlyOnceWithLoadingOnlyDuringTheFetch() = runTest {
        val resolver = SharedReferenceResolver()
        val gate = CompletableDeferred<Unit>()
        val network = FakeReferenceNetwork(resolver, gate = gate)

        val pending =
            async(start = CoroutineStart.UNDISPATCHED) {
                resolver.resolve(key = key, actionId = "message-a:thread", local = { null }, fetch = network::fetch)
            }
        network.started.await()
        assertTrue(resolver.isFetching("message-a:thread"), "loading is on while the fetch runs")

        gate.complete(Unit)
        val open = assertIs<SharedReferenceOutcome.Open<String>>(pending.await())

        assertEquals("thread-target", open.target)
        assertEquals(SharedReferenceSource.Network, open.source)
        assertEquals(1, network.calls)
        assertEquals(setOf<String?>("message-a:thread"), network.fetchingDuringCall.single())
        assertFalse(resolver.isFetching("message-a:thread"), "loading is off once the fetch answered")
    }

    @Test
    fun aSecondTapAfterASuccessfulFetchIsLocal() = runTest {
        val resolver = SharedReferenceResolver()
        val network = FakeReferenceNetwork(resolver)

        assertIs<SharedReferenceOutcome.Open<String>>(
            resolver.resolve(key = key, actionId = "message-a:thread", local = { null }, fetch = network::fetch)
        )
        // The same ref, as written in another message (case differs): same reference.
        val sameRef = requireNotNull(SharedReferenceKey.of(key.scopeId, key.kind, key.conversationId, "bc037b0b"))
        val second =
            resolver.resolve(key = sameRef, actionId = "message-b:thread", local = { null }, fetch = network::fetch)

        val open = assertIs<SharedReferenceOutcome.Open<String>>(second)
        assertEquals("thread-target", open.target)
        assertEquals(SharedReferenceSource.Resolved, open.source)
        assertEquals(1, network.calls, "the second tap must not fetch again")
        assertTrue(resolver.inFlight.value.isEmpty())

        // Another account or server never reuses it.
        val otherServer = requireNotNull(SharedReferenceKey.of("env\u001fviewer\u001fserver-2", key.kind, key.conversationId, key.id))
        resolver.resolve(key = otherServer, actionId = null, local = { null }, fetch = network::fetch)
        assertEquals(2, network.calls)
    }

    @Test
    fun aFailureIsAnErrorOutcomeWithItsReasonAndLoadingCleared() = runTest {
        val cases =
            listOf(
                DataResult.Empty(EmptyReason.NoMessages) to SharedReferenceFailure.NotFound,
                DataResult.Empty(EmptyReason.NoChannels) to SharedReferenceFailure.OtherServer,
                DataResult.NoPermission(PermissionBlocker(code = "forbidden")) to SharedReferenceFailure.NoAccess,
                DataResult.Error(ScreenError(code = "network_unavailable")) to SharedReferenceFailure.Unavailable
            )
        cases.forEach { (answer, expected) ->
            val resolver = SharedReferenceResolver()
            val network = FakeReferenceNetwork(resolver, answer = answer)
            var navigated = false

            val outcome =
                resolver.resolve(key = key, actionId = "message-a:thread", local = { null }, fetch = network::fetch)
            if (outcome is SharedReferenceOutcome.Open) navigated = true

            val failed = assertIs<SharedReferenceOutcome.Failed>(outcome, "answer=$answer")
            assertEquals(expected, failed.failure, "answer=$answer")
            assertEquals(answer, failed.result)
            assertTrue(!navigated, "a failure must not navigate")
            assertEquals(1, network.calls)
            assertTrue(resolver.inFlight.value.isEmpty(), "loading must be cleared after a failure ($answer)")

            // A failure is not remembered: the next tap asks again.
            resolver.resolve(key = key, actionId = "message-a:thread", local = { null }, fetch = network::fetch)
            assertEquals(2, network.calls)
        }
    }

    @Test
    fun anIncompleteFetchedTargetIsNotKept() = runTest {
        val resolver = SharedReferenceResolver()
        val network = FakeReferenceNetwork(resolver, answer = DataResult.Content("message-without-thread"))

        repeat(2) {
            resolver.resolve(
                key = key,
                actionId = "message-a:thread",
                local = { null },
                fetch = network::fetch,
                cacheable = { target -> target != "message-without-thread" }
            )
        }

        assertEquals(2, network.calls, "a target without its thread must be asked again, not answered from the cache")
    }

    @Test
    fun aKeptResolutionWhoseTargetIsGoneIsForgottenAndTheNextTapFetches() = runTest {
        val resolver = SharedReferenceResolver()
        val network = FakeReferenceNetwork(resolver)
        resolver.resolve(key = key, actionId = "message-a:thread", local = { null }, fetch = network::fetch)
        assertEquals("thread-target", resolver.cached(key))

        // The opened page reports its target deleted / refused.
        assertEquals(1, resolver.forgetTargets { _, target -> target == "thread-target" })

        val again = resolver.resolve(key = key, actionId = "message-a:thread", local = { null }, fetch = network::fetch)
        assertEquals(SharedReferenceSource.Network, assertIs<SharedReferenceOutcome.Open<String>>(again).source)
        assertEquals(2, network.calls)
    }

    @Test
    fun overlappingFetchesFromTwoPagesEachKeepTheirOwnChipBusyInEitherOrder() = runTest {
        listOf(true, false).forEach { firstFinishesFirst ->
            val resolver = SharedReferenceResolver()
            val gateA = CompletableDeferred<Unit>()
            val gateB = CompletableDeferred<Unit>()
            val pageA = FakeReferenceNetwork(resolver, gate = gateA)
            val pageB = FakeReferenceNetwork(resolver, gate = gateB)
            val keyB = requireNotNull(SharedReferenceKey.of(key.scopeId, key.kind, "other-channel", "aa11bb22"))

            val a =
                async(start = CoroutineStart.UNDISPATCHED) {
                    resolver.resolve(key = key, actionId = "page-a:thread", local = { null }, fetch = pageA::fetch)
                }
            val b =
                async(start = CoroutineStart.UNDISPATCHED) {
                    resolver.resolve(key = keyB, actionId = "page-b:thread", local = { null }, fetch = pageB::fetch)
                }
            assertTrue(resolver.isFetching("page-a:thread"))
            assertTrue(resolver.isFetching("page-b:thread"))

            val (firstGate, firstAction, secondGate, secondAction) =
                if (firstFinishesFirst) {
                    listOf(gateA, "page-a:thread", gateB, "page-b:thread")
                } else {
                    listOf(gateB, "page-b:thread", gateA, "page-a:thread")
                }
            (firstGate as CompletableDeferred<Unit>).complete(Unit)
            (if (firstFinishesFirst) a else b).await()
            assertFalse(resolver.isFetching(firstAction as String), "order=$firstFinishesFirst: the finished chip is restored")
            assertTrue(
                resolver.isFetching(secondAction as String),
                "order=$firstFinishesFirst: the other page's chip stays busy while its fetch runs"
            )

            (secondGate as CompletableDeferred<Unit>).complete(Unit)
            (if (firstFinishesFirst) b else a).await()
            assertTrue(resolver.inFlight.value.isEmpty(), "order=$firstFinishesFirst")
        }
    }
}
