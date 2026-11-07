package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
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
        val fetchingDuringCall = mutableListOf<SharedReferenceFetch?>()
        val started = CompletableDeferred<Unit>()

        suspend fun fetch(): DataResult<String> {
            calls += 1
            fetchingDuringCall += resolver.fetching.value
            started.complete(Unit)
            gate?.await()
            return answer
        }
    }

    @Test
    fun aLocalHitOpensWithNoNetworkCallAndNoLoadingState() = runTest {
        val resolver = SharedReferenceResolver()
        val network = FakeReferenceNetwork(resolver)
        val fetchingSeenByLocal = mutableListOf<SharedReferenceFetch?>()

        val outcome =
            resolver.resolve(
                key = key,
                actionId = "message-a:thread",
                local = {
                    fetchingSeenByLocal += resolver.fetching.value
                    "local-target"
                },
                fetch = network::fetch
            )

        val open = assertIs<SharedReferenceOutcome.Open<String>>(outcome)
        assertEquals("local-target", open.target)
        assertEquals(SharedReferenceSource.Local, open.source)
        assertEquals(0, network.calls, "a local hit must not call the network")
        assertEquals(listOf<SharedReferenceFetch?>(null), fetchingSeenByLocal, "the local lookup shows no loading state")
        assertNull(resolver.fetching.value)
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
        assertEquals("message-a:thread", resolver.fetching.value?.actionId, "loading is on while the fetch runs")

        gate.complete(Unit)
        val open = assertIs<SharedReferenceOutcome.Open<String>>(pending.await())

        assertEquals("thread-target", open.target)
        assertEquals(SharedReferenceSource.Network, open.source)
        assertEquals(1, network.calls)
        assertEquals("message-a:thread", network.fetchingDuringCall.single()?.actionId)
        assertNull(resolver.fetching.value, "loading is off once the fetch answered")
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
        assertNull(resolver.fetching.value)

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
            assertNull(resolver.fetching.value, "loading must be cleared after a failure ($answer)")

            // A failure is not remembered: the next tap asks again.
            resolver.resolve(key = key, actionId = "message-a:thread", local = { null }, fetch = network::fetch)
            assertEquals(2, network.calls)
        }
    }

    @Test
    fun taskRefNetworkLookupRunsInTheSameFetchPhase() = runTest {
        val resolver = SharedReferenceResolver()
        var fetchingInside: SharedReferenceFetch? = null

        resolver.fetchPhase("message-a:task") { fetchingInside = resolver.fetching.value }

        assertEquals("message-a:task", fetchingInside?.actionId)
        assertNull(resolver.fetching.value)
    }
}
