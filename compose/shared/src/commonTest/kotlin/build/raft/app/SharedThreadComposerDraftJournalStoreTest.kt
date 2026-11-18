package build.raft.app

import build.raft.app.data.SharedPreferenceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #246: the crash journal in a store of its own (Android: the `slockDraftJournal` MMKV file),
 * rows of older builds still read from the shared store, and every clear / purge / account path
 * covering both.
 */
class SharedThreadComposerDraftJournalStoreTest {
    private val draftKey = "send=channel-own|parent=_|mention=channel-own"
    private val threadKey = "send=channel-own|parent=message-9|mention=channel-own"
    private val accountA = SharedThreadComposerDraftAccount("https://api.raft.build", "user-a")
    private val accountB = SharedThreadComposerDraftAccount("https://api.raft.build", "user-b")
    private val row = SharedThreadComposerDraftPersistence::journalRowKey

    private fun entry(text: String, seq: Long, generation: Long = 1L, key: String = draftKey, process: Long = 9L) =
        SharedThreadComposerDraftJournalEntry(key = key, seq = seq, generation = generation, process = process, text = text, asTask = false)

    @Test
    fun theJournalIsWrittenOnlyToItsOwnStoreAndUpdatesTouchNothingElse() {
        val main = MapStore()
        val journal = MapStore()
        val persistence = SharedThreadComposerDraftPersistence(main, journalStore = journal)

        persistence.writeJournal(entry("a", seq = 1L))
        val journalWritesAfterFirst = journal.writes
        persistence.writeJournal(entry("ab", seq = 2L))
        persistence.writeJournal(entry("abc", seq = 3L))

        assertEquals(0, main.writes, "the shared store is never written by the journal")
        assertEquals(journalWritesAfterFirst + 2, journal.writes, "one write per update")
        assertEquals(0, journal.indexReadsAfter(journalWritesAfterFirst))
        assertNotNull(journal.getString(row(draftKey)))
        assertEquals("abc", persistence.readJournal(draftKey)?.text)
    }

    @Test
    fun aRowOfAnOlderBuildInTheSharedStoreIsReadAndTheNewerEntryWins() {
        val main = MapStore()
        val journal = MapStore()
        val persistence = SharedThreadComposerDraftPersistence(main, journalStore = journal)
        val legacyJson = """{"key":"$draftKey","seq":5,"generation":2,"process":99,"text":"old build","asTask":false}"""

        // Only the old location (JSON, as #4955 wrote it): read.
        main.putString(row(draftKey), legacyJson)
        assertEquals("old build", persistence.readJournal(draftKey)?.text)

        // Both: the newer change wins, by seq, then by generation; a tie keeps the new location.
        journal.putString(row(draftKey), SharedThreadComposerDraftJournalRow.encode(entry("new store, older", seq = 4L)))
        assertEquals("old build", persistence.readJournal(draftKey)?.text)
        journal.putString(row(draftKey), SharedThreadComposerDraftJournalRow.encode(entry("new store, newer", seq = 6L)))
        assertEquals("new store, newer", persistence.readJournal(draftKey)?.text)
        main.putString(row(draftKey), SharedThreadComposerDraftJournalRow.encode(entry("old, same seq, later generation", seq = 6L, generation = 3L)))
        assertEquals("old, same seq, later generation", persistence.readJournal(draftKey)?.text)
        main.putString(row(draftKey), SharedThreadComposerDraftJournalRow.encode(entry("old, same seq and generation", seq = 6L, generation = 1L)))
        assertEquals("new store, newer", persistence.readJournal(draftKey)?.text)
    }

    @Test
    fun aRestartRestoresAnOldLocationRowWritesItAsTheDraftAndLeavesNoRowAnywhere() {
        val main = MapStore()
        val journal = MapStore()
        main.putString(SharedThreadComposerDraftPersistence.JournalIndexKey, draftKey)
        main.putString(row(draftKey), SharedThreadComposerDraftJournalRow.encode(entry("typed before the update", seq = 5L)))

        val store = composer(slice(main, journal, process = 1L), draftKey)

        assertEquals("typed before the update", store.state.value.seedText)
        assertEquals("typed before the update", SharedThreadComposerDraftPersistence(main).read(draftKey)?.text)
        assertNull(main.getString(row(draftKey)))
        assertNull(journal.getString(row(draftKey)))
        // Typing goes to the new location only.
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "typed after the update"))
        assertNull(main.getString(row(draftKey)))
        assertNotNull(journal.getString(row(draftKey)))
        assertEquals("typed after the update", composer(slice(main, journal, process = 2L), draftKey).state.value.seedText)
    }

    @Test
    fun everyClearCoversBothLocationsAndARollbackPutsBothBack() {
        val main = MapStore()
        val journal = MapStore()
        val persistence = SharedThreadComposerDraftPersistence(main, journalStore = journal)
        main.putString(SharedThreadComposerDraftPersistence.JournalIndexKey, draftKey)
        main.putString(row(draftKey), SharedThreadComposerDraftJournalRow.encode(entry("old", seq = 1L)))
        persistence.writeJournal(entry("new", seq = 2L))

        val image = persistence.readJournalRaw(listOf(draftKey))
        persistence.clearJournalFor(draftKey)
        assertNull(main.getString(row(draftKey)))
        assertNull(journal.getString(row(draftKey)))
        persistence.restoreJournalRaw(image)
        assertEquals("old", SharedThreadComposerDraftJournalRow.decode(main.getString(row(draftKey))!!)?.text)
        assertEquals("new", SharedThreadComposerDraftJournalRow.decode(journal.getString(row(draftKey))!!)?.text)

        persistence.clearJournalIfCovered(draftKey, seq = 2L)
        assertNull(main.getString(row(draftKey)))
        assertNull(journal.getString(row(draftKey)))

        main.putString(row(threadKey), SharedThreadComposerDraftJournalRow.encode(entry("old thread", seq = 3L, key = threadKey)))
        main.putString(SharedThreadComposerDraftPersistence.JournalIndexKey, threadKey)
        persistence.writeJournal(entry("new channel", seq = 4L))
        persistence.clearJournal()
        assertTrue(main.keys().none { it.endsWith(":journal") || it.endsWith(":journalKeys") }, "${main.keys()}")
        assertTrue(journal.keys().isEmpty(), "${journal.keys()}")
    }

    @Test
    fun aSentTextNeverComesBackFromEitherLocation() {
        val main = MapStore()
        val journal = MapStore()
        main.putString(SharedThreadComposerDraftPersistence.JournalIndexKey, draftKey)
        main.putString(row(draftKey), SharedThreadComposerDraftJournalRow.encode(entry("old build text", seq = 1L)))
        val clock = VirtualDraftWriteClock()
        val store = composer(slice(main, journal, process = 1L, clock = clock), draftKey)

        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "the message"))
        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend())
        clock.runEverythingIncludingCancelled()

        assertEquals("", composer(slice(main, journal, process = 2L), draftKey).state.value.seedText)
    }

    @Test
    fun theAccountPurgeDeletesTheDedicatedStoresRowsEvenWhenTheAccountHasNoOtherRow() {
        val base = MapStore()
        val journalBase = MapStore()
        var live: SharedThreadComposerDraftAccount? = accountA
        val accountStore = SharedThreadComposerDraftAccountStore(base, liveAccount = { live })
        val journalView = SharedThreadComposerDraftAccountLinkedStore(journalBase, accountStore)
        val persistence = SharedThreadComposerDraftPersistence(accountStore, journalStore = journalView)
        val accounts =
            SharedThreadComposerDraftAccounts(
                base = base,
                store = accountStore,
                persistenceFor = { SharedThreadComposerDraftPersistence(it) },
                journalBase = journalBase,
            )
        val slice = SharedThreadComposerDraftPersistSlice(persistence, scheduler = VirtualDraftWriteClock())
        accounts.onAccountBinding(accountA, slice)

        // Only a journal row (the full draft write never ran): the account is still listed as an owner.
        persistence.writeJournal(entry("A's journal only", seq = 1L))
        assertTrue(accountStore.owners().contains(accountA.storageToken))
        assertTrue(journalBase.keys().isNotEmpty())

        live = null
        accounts.onAccountBinding(null, slice)
        assertTrue(journalBase.keys().isEmpty(), "rows left: ${journalBase.keys()}")

        // Signed in again: registration happens again (the cached index did not survive the purge).
        live = accountA
        accounts.onAccountBinding(accountA, slice)
        assertTrue(persistence.writeJournal(entry("A again", seq = 2L)).indexWrite)
    }

    @Test
    fun anotherAccountNeverSeesTheJournalInTheDedicatedStore() {
        val base = MapStore()
        val journalBase = MapStore()
        var live: SharedThreadComposerDraftAccount? = accountA
        val accountStore = SharedThreadComposerDraftAccountStore(base, liveAccount = { live })
        val persistence = SharedThreadComposerDraftPersistence(accountStore, journalStore = SharedThreadComposerDraftAccountLinkedStore(journalBase, accountStore))
        persistence.writeJournal(entry("A's text", seq = 1L))

        live = accountB
        accountStore.rebind(accountB)
        assertNull(persistence.readJournal(draftKey))
        assertTrue(persistence.writeJournal(entry("B's text", seq = 2L)).indexWrite, "B registers in its own index")
        assertEquals("B's text", persistence.readJournal(draftKey)?.text)

        // No account bound: nothing is read and nothing is written.
        live = null
        assertNull(persistence.readJournal(draftKey))
        val before = journalBase.writes
        persistence.writeJournal(entry("nobody", seq = 3L))
        assertEquals(before, journalBase.writes)

        live = accountA
        accountStore.rebind(accountA)
        assertEquals("A's text", persistence.readJournal(draftKey)?.text)
    }

    @Test
    fun coexistingComposersKeepTheirOwnRowsInTheDedicatedStore() {
        val main = MapStore()
        val journal = MapStore()
        val shared = slice(main, journal, process = 1L)
        val channel = composer(shared, draftKey)
        val thread = composer(shared, threadKey)
        channel.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "channel"))
        thread.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "thread"))
        channel.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "channel, newest"))

        assertFalse(main.keys().any { it.endsWith(":journal") })
        assertEquals("channel, newest", composer(slice(main, journal, process = 2L), draftKey).state.value.seedText)
        assertEquals("thread", composer(slice(main, journal, process = 2L), threadKey).state.value.seedText)
    }

    private fun slice(main: SharedPreferenceStore, journal: SharedPreferenceStore, process: Long, clock: VirtualDraftWriteClock = VirtualDraftWriteClock()) =
        SharedThreadComposerDraftPersistSlice(
            persistence = SharedThreadComposerDraftPersistence(main, journalStore = journal),
            scheduler = clock,
            changeWriteIntervalMillis = 40L,
            monotonicMillis = clock::now,
            journalProcess = process,
        )

    private fun composer(slice: SharedThreadComposerDraftPersistSlice, key: String): SharedThreadComposerDraftStore =
        SharedThreadComposerDraftStore(persistSlices = listOf(slice)).also {
            it.dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "own-$key"), key))
        }

    /** A map store that counts writes and, per write count, reads of the journal index. */
    private class MapStore : SharedPreferenceStore {
        private val values = mutableMapOf<String, String>()
        private val indexReadsAtWrite = mutableListOf<Int>()
        var writes = 0

        fun keys(): Set<String> = values.keys.toSet()

        /** Reads of a journal index key made after the first [afterWrites] writes. */
        fun indexReadsAfter(afterWrites: Int): Int = indexReadsAtWrite.count { it >= afterWrites }

        override fun getString(key: String): String? {
            if (key.endsWith("journalKeys")) indexReadsAtWrite += writes
            return values[key]
        }

        override fun putString(key: String, value: String?) {
            writes += 1
            if (value == null) values.remove(key) else values[key] = value
        }
    }
}
