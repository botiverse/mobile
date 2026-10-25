package build.raft.app.data

import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SharedJointActorDirectoryTest {
    private val server = ServerId("6f1c2a0e-1111-4c4c-8a8a-000000000001")
    private val peerServer = "6f1c2a0e-2222-4c4c-8a8a-000000000002"

    private fun uuid(n: Int): String = "0b6f7d1e-0000-4e4e-9b9b-${n.toString().padStart(12, '0')}"

    private fun human(n: Int) = ActorResolveRef(MemberKind.Human, uuid(n))

    private fun agent(n: Int) = ActorResolveRef(MemberKind.Agent, uuid(n))

    private fun resolvedActorOf(ref: ActorResolveRef, avatar: String? = null) =
        ResolvedActor(
            kind = ref.kind,
            id = ref.id,
            homeServerId = peerServer,
            name = "peer-${ref.id.takeLast(2)}",
            displayName = null,
            avatarUrl = avatar,
            gravatarHash = null,
        )

    private class Harness(
        scope: CoroutineScope,
        var account: String? = "user-1",
        val known: MutableSet<String> = mutableSetOf(),
        var answer: suspend (List<ActorResolveRef>) -> ActorResolveOutcome,
    ) {
        val calls = mutableListOf<List<ActorResolveRef>>()
        val directory =
            SharedJointActorDirectory(
                coroutineScope = scope,
                owner = { serverId -> account?.let { ProcessServerMembersOwner("https://api.test", it, serverId.value) } },
                knownLocally = { _, ref -> ref.id in known },
                resolve = { _, refs ->
                    calls += refs
                    answer(refs)
                },
            )
    }

    private fun TestScope.harness(
        answer: suspend (List<ActorResolveRef>) -> ActorResolveOutcome = { ActorResolveOutcome.Resolved(emptyList()) },
    ) =
        // Batches run on the test scheduler as foreground work, so advanceUntilIdle drives their
        // debounce and requests (backgroundScope work would be left pending).
        Harness(scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), answer = answer)

    @Test
    fun batchesDedupesAndSkipsLocalAndMalformedRefs() = runTest {
        val h = harness { refs -> ActorResolveOutcome.Resolved(refs.map { resolvedActorOf(it, "https://cdn.test/a.webp") }) }
        h.known += uuid(3)

        h.directory.request(server, listOf(human(1), agent(2)))
        h.directory.request(server, listOf(human(1), human(3), ActorResolveRef(MemberKind.Human, "system")))
        h.directory.request(server, listOf(ActorResolveRef(MemberKind.System, uuid(4))))
        advanceUntilIdle()

        assertEquals(listOf(listOf(human(1), agent(2))), h.calls)
        assertEquals("https://cdn.test/a.webp", h.directory.current(server, uuid(1))?.avatarUrl)
        assertEquals(MemberKind.Agent, h.directory.current(server, uuid(2))?.kind)
        // Wrong kind asked: no row.
        assertNull(h.directory.current(server, uuid(2), MemberKind.Human))

        // Resolved rows are not asked again.
        h.directory.request(server, listOf(human(1)))
        advanceUntilIdle()
        assertEquals(1, h.calls.size)
    }

    @Test
    fun aRefTheRosterLearnsWhileTheBatchWaitsIsNotSent() = runTest {
        val h = harness()
        h.directory.request(server, listOf(human(1), human(2)))
        h.known += uuid(2)
        advanceUntilIdle()
        assertEquals(listOf(listOf(human(1))), h.calls)
    }

    @Test
    fun absentAndFailedRefsAreNotAskedAgain() = runTest {
        val h = harness { refs -> if (refs.first() == human(1)) ActorResolveOutcome.Resolved(emptyList()) else ActorResolveOutcome.Failed(500) }
        h.directory.request(server, listOf(human(1)))
        advanceUntilIdle()
        h.directory.request(server, listOf(human(2)))
        advanceUntilIdle()
        h.directory.request(server, listOf(human(1), human(2)))
        advanceUntilIdle()
        assertEquals(listOf(listOf(human(1)), listOf(human(2))), h.calls)
    }

    @Test
    fun anOlderServerOrAGuestTurnsTheScopeOffForTheSession() = runTest {
        val h = harness { ActorResolveOutcome.Unavailable }
        h.directory.request(server, listOf(human(1)))
        advanceUntilIdle()
        h.directory.request(server, listOf(human(2)))
        advanceUntilIdle()
        assertEquals(listOf(listOf(human(1))), h.calls)
        assertNull(h.directory.current(server, uuid(1)))

        // A new session (clear on sign-out) may ask again.
        h.directory.clear()
        h.directory.request(server, listOf(human(2)))
        advanceUntilIdle()
        assertEquals(listOf(listOf(human(1)), listOf(human(2))), h.calls)
    }

    @Test
    fun moreThanTwoHundredRefsGoInChunks() = runTest {
        val h = harness()
        h.directory.request(server, (1..450).map(::human))
        advanceUntilIdle()
        assertEquals(listOf(200, 200, 50), h.calls.map { it.size })
        assertEquals(450, h.calls.flatten().toSet().size)
    }

    @Test
    fun oneBatchInFlightPerScopeAndRefsQueuedMeanwhileFollowIt() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = harness { refs ->
            if (refs.first() == human(1)) gate.await()
            ActorResolveOutcome.Resolved(refs.map { resolvedActorOf(it) })
        }
        h.directory.request(server, listOf(human(1)))
        advanceUntilIdle()
        assertEquals(1, h.calls.size)

        h.directory.request(server, listOf(human(1), human(2)))
        advanceUntilIdle()
        assertEquals(1, h.calls.size, "no second request while the first is in flight")

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(listOf(human(1)), listOf(human(2))), h.calls)
        assertEquals(uuid(2), h.directory.current(server, uuid(2))?.id)
    }

    @Test
    fun anAnswerForAScopeNoLongerCurrentIsDropped() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = harness { refs ->
            gate.await()
            ActorResolveOutcome.Resolved(refs.map { resolvedActorOf(it, "https://cdn.test/a.webp") })
        }
        h.directory.request(server, listOf(human(1)))
        advanceUntilIdle()
        h.account = "user-2"
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(h.directory.current(server, uuid(1)))
        h.account = "user-1"
        assertNull(h.directory.current(server, uuid(1)), "user-1's late answer was not written either")
    }

    @Test
    fun clearDuringAFlightDropsItsAnswerAndForgetsAskedRefs() = runTest {
        val gate = CompletableDeferred<Unit>()
        var answered = 0
        val h = harness { refs ->
            if (answered++ == 0) gate.await()
            ActorResolveOutcome.Resolved(refs.map { resolvedActorOf(it) })
        }
        h.directory.request(server, listOf(human(1)))
        advanceUntilIdle()
        h.directory.clear()
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(h.directory.current(server, uuid(1)))

        h.directory.request(server, listOf(human(1)))
        advanceUntilIdle()
        assertEquals(2, h.calls.size)
        assertEquals(uuid(1), h.directory.current(server, uuid(1))?.id)

        h.directory.clearServer(server)
        assertNull(h.directory.current(server, uuid(1)))
    }

    @Test
    fun anUnchangedAnswerDoesNotReEmit() = runTest {
        val h = harness { refs -> ActorResolveOutcome.Resolved(refs.map { resolvedActorOf(human(1)) }) }
        val emissions = mutableListOf<ResolvedActor?>()
        backgroundScope.launch { h.directory.actorFlow(server, human(1)).toList(emissions) }
        runCurrent()
        advanceUntilIdle()
        // The flow asked on collection; a second, unrelated ref answers with the same row.
        h.directory.request(server, listOf(human(2)))
        advanceUntilIdle()

        assertEquals(2, h.calls.size)
        assertEquals(listOf(null, resolvedActorOf(human(1))), emissions)
        assertTrue(h.directory.rows.value.values.single().size == 1)
    }

    @Test
    fun signedOutAsksNothing() = runTest {
        val h = harness()
        h.account = null
        h.directory.request(server, listOf(human(1)))
        advanceUntilIdle()
        assertTrue(h.calls.isEmpty())
    }
}
