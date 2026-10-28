package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Write ordering of the offline resource layer (#3809 review): a fetch never overwrites a newer confirmed
 * mutation, a wipe fences only the rows it wipes, and a wipe is ordered before every later write.
 */
class RaftOfflineResourcesOrderingTest {
    @Serializable
    data class Rows(val ids: List<String>)

    private class Fixture(scope: CoroutineScope) {
        val rows = InMemoryRaftOfflineResourceRows()
        val cache = RaftOfflineResourceCache(rows, environmentId = { "https://api.example" }, scope = scope)
        val lists: RaftOfflineResource<String, Rows> =
            cache.resource(
                RaftOfflineResourceSpec(
                    name = "lists",
                    version = 1,
                    serializer = Rows.serializer(),
                    paramsId = { it },
                    maxEntriesPerAccount = 8,
                    maxBytesPerEntry = 4096,
                )
            )

        fun key(account: String = "user-1", server: String = "server-1"): RaftOfflineResourceKey<String> =
            lists.key(account, server, "list")!!
    }

    /** A fetch held at the network until [release]; [started] completes when the request went out. */
    private class GatedFetch(private val answer: Rows) {
        val started = CompletableDeferred<Unit>()
        private val gate = CompletableDeferred<Unit>()
        var calls = 0

        suspend fun fetch(): DataResult<Rows> {
            calls += 1
            started.complete(Unit)
            gate.await()
            return DataResult.Content(answer)
        }

        fun release() {
            gate.complete(Unit)
        }
    }

    // (a) A fetch that started before a confirmed mutation must not write its older answer over it.
    @Test
    fun fetchStartedBeforeAConfirmedMutationCannotResurrectTheRow() = runTest {
        val fixture = Fixture(backgroundScope)
        val lists = fixture.lists
        lists.write(fixture.key(), Rows(listOf("m1", "m2")))
        val network = GatedFetch(Rows(listOf("m1", "m2")))
        val store = lists.store { network.fetch() }

        val emissions = async { store.revalidate(fixture.key()).toList() }
        network.started.await()
        // The server confirms "unsave m1" while the older request is still in flight.
        assertTrue(lists.update(fixture.key()) { it.copy(ids = it.ids - "m1") })
        network.release()

        // Neither the disk nor the answer shown brings m1 back.
        assertEquals(RaftOfflineEmission.Fresh(Rows(listOf("m2"))), emissions.await().last())
        assertEquals(Rows(listOf("m2")), lists.read(fixture.key()))

        // Positive control: a fetch that started after the mutation is stored.
        lists.store { DataResult.Content(Rows(listOf("m2", "m3"))) }.revalidate(fixture.key()).toList()
        assertEquals(Rows(listOf("m2", "m3")), lists.read(fixture.key()))
    }

    // (a) Nothing stored: a fetch that started before a confirmed mutation is neither stored nor shown; the
    // layer asks once more (that request starts after the mutation).
    @Test
    fun fetchSupersededWithNothingStoredAsksAgainInsteadOfShowingTheOlderAnswer() = runTest {
        val fixture = Fixture(backgroundScope)
        val lists = fixture.lists
        val first = GatedFetch(Rows(listOf("m1", "m2")))
        var calls = 0
        val store =
            lists.store {
                calls += 1
                if (calls == 1) first.fetch() else DataResult.Content(Rows(listOf("m2")))
            }

        val emissions = async { store.revalidate(fixture.key()).toList() }
        first.started.await()
        // Confirmed "unsave m1" with nothing stored yet (a no-op on disk, but newer than the request).
        lists.update(fixture.key()) { it.copy(ids = it.ids - "m1") }
        first.release()

        assertEquals(listOf<RaftOfflineEmission<Rows>>(RaftOfflineEmission.Fresh(Rows(listOf("m2")))), emissions.await())
        assertEquals(2, calls)
        assertEquals(Rows(listOf("m2")), lists.read(fixture.key()))
    }

    // (c) A queued wipe runs to the end even when the caller that drains it (here a read) is cancelled.
    @Test
    fun wipeDrainedByACancelledCallerStillCompletes() = runTest {
        val fixture = Fixture(backgroundScope)
        val lists = fixture.lists
        lists.write(fixture.key(), Rows(listOf("signed-out")))
        val deleting = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        fixture.rows.beforeDeleteAll = {
            deleting.complete(Unit)
            gate.await()
        }

        fixture.cache.wipeAllAsync()
        val reader = launch(start = CoroutineStart.UNDISPATCHED) { lists.read(fixture.key()) }
        deleting.await()
        reader.cancel()
        gate.complete(Unit)
        runCurrent()

        assertTrue(fixture.rows.rows.isEmpty())
    }

    // (a) update() is one read-modify-write: two confirmed mutations racing both apply.
    @Test
    fun concurrentConfirmedMutationsBothApply() = runTest {
        val fixture = Fixture(backgroundScope)
        val lists = fixture.lists
        lists.write(fixture.key(), Rows(listOf("m1", "m2", "m3")))
        // Each reader, having read, waits (bounded, virtual time) for the other to read too: without one lock around the
        // read-modify-write both read the same old list and the second write drops the first mutation.
        var reads = 0
        val bothRead = CompletableDeferred<Unit>()
        fixture.rows.afterRead = {
            reads += 1
            if (reads >= 2) bothRead.complete(Unit) else withTimeoutOrNull(1_000L) { bothRead.await() }
        }

        val first = async { lists.update(fixture.key()) { it.copy(ids = it.ids - "m1") } }
        val second = async { lists.update(fixture.key()) { it.copy(ids = it.ids - "m2") } }
        first.await()
        second.await()

        fixture.rows.afterRead = {}
        assertEquals(Rows(listOf("m3")), lists.read(fixture.key()))
    }

    // (b) Losing one server fences that server's in-flight writes only.
    @Test
    fun wipeServerDoesNotFenceInFlightWritesOfOtherServersOrAccounts() = runTest {
        val fixture = Fixture(backgroundScope)
        val lists = fixture.lists
        lists.write(fixture.key(server = "server-1"), Rows(listOf("old")))
        val otherServer = GatedFetch(Rows(listOf("server-2")))
        val otherAccount = GatedFetch(Rows(listOf("user-2")))
        val otherServerStore = lists.store { otherServer.fetch() }
        val otherAccountStore = lists.store { otherAccount.fetch() }

        val a = async { otherServerStore.revalidate(fixture.key(server = "server-2")).toList() }
        val b = async { otherAccountStore.revalidate(fixture.key(account = "user-2", server = "server-1")).toList() }
        otherServer.started.await()
        otherAccount.started.await()
        fixture.cache.wipeServer("server-1", accountId = "user-1")
        otherServer.release()
        otherAccount.release()
        a.await()
        b.await()

        assertNull(lists.read(fixture.key(server = "server-1")))
        assertEquals(Rows(listOf("server-2")), lists.read(fixture.key(server = "server-2")))
        assertEquals(Rows(listOf("user-2")), lists.read(fixture.key(account = "user-2", server = "server-1")))
    }

    // (b) A server wipe for every account still leaves the other servers' in-flight writes alone.
    @Test
    fun wipeServerForEveryAccountFencesOnlyThatServer() = runTest {
        val fixture = Fixture(backgroundScope)
        val lists = fixture.lists
        val sameServer = GatedFetch(Rows(listOf("late")))
        val otherServer = GatedFetch(Rows(listOf("server-2")))
        val sameStore = lists.store { sameServer.fetch() }
        val otherStore = lists.store { otherServer.fetch() }

        val a = async { sameStore.revalidate(fixture.key(account = "user-2", server = "server-1")).toList() }
        val b = async { otherStore.revalidate(fixture.key(server = "server-2")).toList() }
        sameServer.started.await()
        otherServer.started.await()
        fixture.cache.wipeServer("server-1")
        sameServer.release()
        otherServer.release()
        a.await()
        b.await()

        assertNull(lists.read(fixture.key(account = "user-2", server = "server-1")))
        assertEquals(Rows(listOf("server-2")), lists.read(fixture.key(server = "server-2")))
    }

    // Wipe while a fetch of the wiped server is in flight: the answer arrives after the wipe, nothing is written.
    @Test
    fun fetchCompletingAfterWipeOfItsServerWritesNothing() = runTest {
        val fixture = Fixture(backgroundScope)
        val lists = fixture.lists
        lists.write(fixture.key(), Rows(listOf("old")))
        val network = GatedFetch(Rows(listOf("late")))
        val store = lists.store { network.fetch() }

        val emissions = async { store.revalidate(fixture.key()).toList() }
        network.started.await()
        fixture.cache.wipeServer("server-1", accountId = "user-1")
        network.release()

        assertEquals(RaftOfflineEmission.Fresh(Rows(listOf("late"))), emissions.await().last())
        assertTrue(fixture.rows.rows.isEmpty())
    }

    // Same for the asynchronous sign-out wipe.
    @Test
    fun fetchCompletingAfterWipeAllAsyncWritesNothing() = runTest {
        val fixture = Fixture(backgroundScope)
        val lists = fixture.lists
        lists.write(fixture.key(), Rows(listOf("old")))
        val network = GatedFetch(Rows(listOf("late")))
        val store = lists.store { network.fetch() }

        val emissions = async { store.revalidate(fixture.key()).toList() }
        network.started.await()
        fixture.cache.wipeAllAsync()
        network.release()
        emissions.await()
        runCurrent()

        assertTrue(fixture.rows.rows.isEmpty())
    }

    // (c) The asynchronous wipe is ordered before every later read and write: it never deletes the next
    // account's first write, and the wiped rows are gone for a read that runs before the delete.
    @Test
    fun wipeAllAsyncIsOrderedBeforeLaterWritesAndReads() = runTest {
        val fixture = Fixture(backgroundScope)
        val lists = fixture.lists
        lists.write(fixture.key(account = "user-1"), Rows(listOf("signed-out")))

        fixture.cache.wipeAllAsync()
        assertNull(lists.read(fixture.key(account = "user-1")))
        assertTrue(lists.write(fixture.key(account = "user-2"), Rows(listOf("next account"))))
        runCurrent()

        assertEquals(Rows(listOf("next account")), lists.read(fixture.key(account = "user-2")))
        assertNull(lists.read(fixture.key(account = "user-1")))
    }

    // Sign-out keeps the rows (owner decision) but a request of the signed-out session cannot write.
    @Test
    fun fenceInFlightWritesKeepsRowsAndDropsTheSignedOutSessionsLateAnswer() = runTest {
        val fixture = Fixture(backgroundScope)
        val lists = fixture.lists
        lists.write(fixture.key(), Rows(listOf("kept")))
        val network = GatedFetch(Rows(listOf("late")))
        val store = lists.store { network.fetch() }

        val emissions = async { store.revalidate(fixture.key()).toList() }
        network.started.await()
        fixture.cache.fenceInFlightWrites()
        network.release()
        emissions.await()

        assertEquals(Rows(listOf("kept")), lists.read(fixture.key()))
        // A key minted after the fence writes again.
        assertTrue(lists.write(fixture.key(), Rows(listOf("next"))))
        assertEquals(Rows(listOf("next")), lists.read(fixture.key()))
    }

    // An answer of an older request never replaces the stored answer of a newer one (two writers).
    @Test
    fun anOlderAnswerNeverReplacesANewerOne() = runTest {
        val fixture = Fixture(backgroundScope)
        val lists = fixture.lists
        val olderStart = lists.fetchStarted()
        val newerStart = lists.fetchStarted()

        assertTrue(lists.write(fixture.key(), Rows(listOf("newer")), startedAt = newerStart))
        assertFalse(lists.write(fixture.key(), Rows(listOf("older")), startedAt = olderStart))
        assertEquals(Rows(listOf("newer")), lists.read(fixture.key()))
        // The same answer again (e.g. patched) still writes.
        assertTrue(lists.write(fixture.key(), Rows(listOf("newer+")), startedAt = newerStart))
        assertEquals(Rows(listOf("newer+")), lists.read(fixture.key()))
    }

    @Test
    fun updateEachChangesEveryStoredValueOfOneAccountAndServer() = runTest {
        val fixture = Fixture(backgroundScope)
        val lists = fixture.lists
        lists.write(lists.key("user-1", "server-1", "a")!!, Rows(listOf("x", "y")))
        lists.write(lists.key("user-1", "server-1", "b")!!, Rows(listOf("y")))
        lists.write(lists.key("user-1", "server-2", "a")!!, Rows(listOf("y")))

        assertEquals(2, lists.updateEach("user-1", "server-1") { it.copy(ids = it.ids - "y") })

        assertEquals(Rows(listOf("x")), lists.read(lists.key("user-1", "server-1", "a")!!))
        assertEquals(Rows(emptyList()), lists.read(lists.key("user-1", "server-1", "b")!!))
        assertEquals(Rows(listOf("y")), lists.read(lists.key("user-1", "server-2", "a")!!))
    }

    // Two concurrent revalidations of one key share one network request.
    @Test
    fun concurrentRevalidationsShareOneRequest() = runTest {
        val fixture = Fixture(backgroundScope)
        val lists = fixture.lists
        val network = GatedFetch(Rows(listOf("network")))
        val store = lists.store { network.fetch() }

        val first = async { store.revalidate(fixture.key()).toList() }
        val second = async { store.revalidate(fixture.key()).toList() }
        network.started.await()
        runCurrent()
        network.release()

        assertEquals(listOf(RaftOfflineEmission.Fresh(Rows(listOf("network")))), first.await())
        assertEquals(listOf(RaftOfflineEmission.Fresh(Rows(listOf("network")))), second.await())
        assertEquals(1, network.calls)
        assertEquals(Rows(listOf("network")), lists.read(fixture.key()))
    }
}
