package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The generic offline-first layer (Store5 over the offline resource rows). */
class RaftOfflineResourcesTest {
    @Serializable
    data class Note(val text: String)

    private class Fixture(scope: CoroutineScope) {
        val rows = InMemoryRaftOfflineResourceRows()
        var environment = "https://api.example"
        var now = 1_000L
        val cache = RaftOfflineResourceCache(rows, environmentId = { environment }, scope = scope, nowMillis = { now })

        fun notes(
            maxEntriesPerAccount: Int = 8,
            maxBytesPerEntry: Int = 1024,
            maxAgeMillis: Long? = null,
        ): RaftOfflineResource<String, Note> =
            cache.resource(
                RaftOfflineResourceSpec(
                    name = "notes",
                    version = 1,
                    serializer = Note.serializer(),
                    paramsId = { it },
                    maxEntriesPerAccount = maxEntriesPerAccount,
                    maxBytesPerEntry = maxBytesPerEntry,
                    maxAgeMillis = maxAgeMillis,
                )
            )
    }

    @Test
    fun revalidateEmitsStoredStandInThenStoresAndEmitsTheNetworkAnswer() = runTest {
        val fixture = Fixture(backgroundScope)
        val notes = fixture.notes()
        val key = notes.key("user-1", "server-1", "a")!!
        notes.write(key, Note("stored"))

        val emissions = notes.store { DataResult.Content(Note("network")) }.revalidate(key).toList()

        assertEquals(
            listOf(RaftOfflineEmission.StandIn(Note("stored")), RaftOfflineEmission.Fresh(Note("network"))),
            emissions,
        )
        assertEquals(Note("network"), notes.read(key))
    }

    @Test
    fun revalidateWithNothingStoredEmitsOnlyTheAnswer() = runTest {
        val fixture = Fixture(backgroundScope)
        val notes = fixture.notes()
        val key = notes.key("user-1", "server-1", "a")!!

        val emissions = notes.store { DataResult.Content(Note("network")) }.revalidate(key).toList()

        assertEquals(listOf(RaftOfflineEmission.Fresh(Note("network"))), emissions)
        assertEquals(Note("network"), notes.read(key))
    }

    @Test
    fun failedRequestKeepsStoredValueButLostAccessDropsIt() = runTest {
        val fixture = Fixture(backgroundScope)
        val notes = fixture.notes()
        val key = notes.key("user-1", "server-1", "a")!!
        notes.write(key, Note("stored"))
        val offline = DataResult.Error(ScreenError(code = "offline", retryable = true))

        val failed = notes.store { offline }.revalidate(key).toList()

        assertEquals(listOf(RaftOfflineEmission.StandIn(Note("stored")), RaftOfflineEmission.Failed(offline)), failed)
        assertEquals(Note("stored"), notes.read(key))

        val forbidden = DataResult.NoPermission(PermissionBlocker(code = "forbidden"))
        val lost = notes.store { forbidden }.revalidate(key).toList()

        assertEquals(RaftOfflineEmission.Failed(forbidden), lost.last())
        assertNull(notes.read(key))
    }

    @Test
    fun fetchThatThrowsRethrowsAndKeepsStoredValue() = runTest {
        val fixture = Fixture(backgroundScope)
        val notes = fixture.notes()
        val key = notes.key("user-1", "server-1", "a")!!
        notes.write(key, Note("stored"))

        assertFailsWith<IllegalStateException> {
            notes.store { error("socket closed") }.revalidate(key).toList()
        }
        assertEquals(Note("stored"), notes.read(key))
    }

    @Test
    fun valuesAreIsolatedByEnvironmentAccountServerAndParams() = runTest {
        val fixture = Fixture(backgroundScope)
        val notes = fixture.notes()
        notes.write(notes.key("user-1", "server-1", "a")!!, Note("mine"))

        assertNull(notes.read(notes.key("user-2", "server-1", "a")!!))
        assertNull(notes.read(notes.key("user-1", "server-2", "a")!!))
        assertNull(notes.read(notes.key("user-1", "server-1", "b")!!))
        fixture.environment = "https://staging.example"
        assertNull(notes.read(notes.key("user-1", "server-1", "a")!!))
        fixture.environment = "https://api.example"
        assertEquals(Note("mine"), notes.read(notes.key("user-1", "server-1", "a")!!))
        assertNull(notes.key("", "server-1", "a"))
    }

    @Test
    fun wipeAllRemovesEveryResourceAndFencesInFlightWrites() = runTest {
        val fixture = Fixture(backgroundScope)
        val notes = fixture.notes()
        val other =
            fixture.cache.resource(
                RaftOfflineResourceSpec(
                    name = "other",
                    version = 1,
                    serializer = Note.serializer(),
                    paramsId = { _: Unit -> "" },
                    maxEntriesPerAccount = 8,
                    maxBytesPerEntry = 1024,
                )
            )
        val key = notes.key("user-1", "server-1", "a")!!
        notes.write(key, Note("a"))
        other.write(other.key("user-2", "server-9", Unit)!!, Note("b"))

        // A request of the signed-out account is still in flight when the wipe runs.
        val gate = CompletableDeferred<Unit>()
        val inFlight = notes.store { gate.await(); DataResult.Content(Note("late")) }

        fixture.cache.wipeAll()
        assertTrue(fixture.rows.rows.isEmpty())

        gate.complete(Unit)
        val emissions = inFlight.revalidate(key).toList()

        // The answer is still delivered to its (stale) caller, but nothing reaches the disk.
        assertEquals(listOf(RaftOfflineEmission.Fresh(Note("late"))), emissions)
        assertTrue(fixture.rows.rows.isEmpty())
        // A key minted after the wipe writes again.
        assertTrue(notes.write(notes.key("user-1", "server-1", "a")!!, Note("next")))
    }

    @Test
    fun wipeServerRemovesOnlyThatServerOfThatAccount() = runTest {
        val fixture = Fixture(backgroundScope)
        val notes = fixture.notes()
        notes.write(notes.key("user-1", "server-1", "a")!!, Note("1"))
        notes.write(notes.key("user-1", "server-2", "a")!!, Note("2"))
        notes.write(notes.key("user-2", "server-1", "a")!!, Note("3"))

        fixture.cache.wipeServer("server-1", accountId = "user-1")

        assertNull(notes.read(notes.key("user-1", "server-1", "a")!!))
        assertEquals(Note("2"), notes.read(notes.key("user-1", "server-2", "a")!!))
        assertEquals(Note("3"), notes.read(notes.key("user-2", "server-1", "a")!!))
    }

    @Test
    fun capsEvictOldestPerAccountRejectOversizedAndExpireOld() = runTest {
        val fixture = Fixture(backgroundScope)
        val notes = fixture.notes(maxEntriesPerAccount = 2, maxBytesPerEntry = 64, maxAgeMillis = 10_000L)
        listOf("server-1", "server-2", "server-3").forEach { server ->
            fixture.now += 1
            notes.write(notes.key("user-1", server, "a")!!, Note(server))
        }
        notes.write(notes.key("user-2", "server-1", "a")!!, Note("other account"))

        assertNull(notes.read(notes.key("user-1", "server-1", "a")!!))
        assertEquals(Note("server-3"), notes.read(notes.key("user-1", "server-3", "a")!!))
        assertEquals(Note("other account"), notes.read(notes.key("user-2", "server-1", "a")!!))

        // Too large: not stored, and the stale copy it would have replaced goes.
        val key = notes.key("user-1", "server-3", "a")!!
        assertFalse(notes.write(key, Note("x".repeat(100))))
        assertNull(notes.read(key))

        fixture.now += 10_001L
        assertNull(notes.read(notes.key("user-1", "server-2", "a")!!))
    }

    @Test
    fun updateWritesThroughOnlyWhatTheCallerConfirms() = runTest {
        val fixture = Fixture(backgroundScope)
        val notes = fixture.notes()
        val key = notes.key("user-1", "server-1", "a")!!

        assertFalse(notes.update(key) { Note("ignored") })
        assertNull(notes.read(key))

        notes.write(key, Note("v1"))
        assertTrue(notes.update(key) { it.copy(text = it.text + "+") })
        assertEquals(Note("v1+"), notes.read(key))
        assertTrue(notes.update(key) { null })
        assertNull(notes.read(key))
    }
}
