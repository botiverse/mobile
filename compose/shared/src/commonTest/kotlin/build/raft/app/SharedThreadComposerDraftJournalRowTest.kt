package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.SharedPreferenceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #246: the crash journal's compact row (no JSON on the input thread), reading of the older JSON
 * rows, and the in-memory cache of the journal index that lets an update skip the index read.
 */
class SharedThreadComposerDraftJournalRowTest {
    private val draftKey = "send=channel-row|parent=_|mention=channel-row"
    private val threadKey = "send=channel-row|parent=message-3|mention=channel-row"
    private val accountA = SharedThreadComposerDraftAccount("https://api.raft.build", "user-a")
    private val accountB = SharedThreadComposerDraftAccount("https://api.raft.build", "user-b")

    private fun entry(key: String = draftKey, text: String = "hello", seq: Long = 7L, generation: Long = 3L, asTask: Boolean = false) =
        SharedThreadComposerDraftJournalEntry(key = key, seq = seq, generation = generation, process = -42L, text = text, asTask = asTask)

    @Test
    fun theCompactRowRoundTripsEveryFieldAndAnyText() {
        val texts = listOf("", "plain", "semi;colons;;", "line\nbreaks\r\n", "j2;1;2;3;1;4;5;fake", "{\"json\":true}", "emoji 🙂 中文", " ")
        for (text in texts) {
            for (asTask in listOf(true, false)) {
                val original = entry(key = "send=a;b|parent=_|mention=x", text = text, asTask = asTask, seq = 1_700_000_000_000_123L)
                val row = SharedThreadComposerDraftJournalRow.encode(original)
                assertTrue(row.startsWith("j2;"), row)
                assertEquals(original, SharedThreadComposerDraftJournalRow.decode(row), row)
            }
        }
    }

    @Test
    fun aTruncatedOrMangledRowIsNeverAppliedInPart() {
        val row = SharedThreadComposerDraftJournalRow.encode(entry(text = "the whole text"))
        for (cut in 1 until row.length) assertNull(SharedThreadComposerDraftJournalRow.decode(row.substring(0, cut)), "cut=$cut")
        assertNull(SharedThreadComposerDraftJournalRow.decode(row + "x"))
        assertNull(SharedThreadComposerDraftJournalRow.decode(row.replaceFirst("j2;7;", "j2;x;")))
        assertNull(SharedThreadComposerDraftJournalRow.decode(row.replaceFirst(";0;", ";2;")))
        assertNull(SharedThreadComposerDraftJournalRow.decode("garbage"))
    }

    @Test
    fun anOlderJsonRowIsStillReadAppliedAndClearedThenRewrittenInTheCompactFormat() {
        val storage = InMemoryRaftKeyValueStore()
        val legacy =
            """{"key":"$draftKey","seq":5,"generation":2,"process":99,"text":"from an older build","asTask":true}"""
        storage.putString(SharedThreadComposerDraftPersistence.JournalIndexKey, draftKey)
        storage.putString(SharedThreadComposerDraftPersistence.journalRowKey(draftKey), legacy)
        val persistence = SharedThreadComposerDraftPersistence(storage)

        assertEquals(entry(text = "from an older build", seq = 5L, generation = 2L, asTask = true).copy(process = 99L), persistence.readJournal(draftKey))
        // A covering write drops it like any row.
        persistence.clearJournalIfCovered(draftKey, seq = 4L)
        assertNotNull(storage.getString(SharedThreadComposerDraftPersistence.journalRowKey(draftKey)))
        persistence.clearJournalIfCovered(draftKey, seq = 5L)
        assertNull(storage.getString(SharedThreadComposerDraftPersistence.journalRowKey(draftKey)))

        // A restart restores an old row's text (process 99 is not this one), and the next write is compact.
        storage.putString(SharedThreadComposerDraftPersistence.journalRowKey(draftKey), legacy)
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(slice(storage, process = 1L)))
        store.dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "legacy"), draftKey))
        assertEquals("from an older build", store.state.value.seedText)
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "typed after the update"))
        assertTrue(storage.getString(SharedThreadComposerDraftPersistence.journalRowKey(draftKey))!!.startsWith("j2;"))
    }

    @Test
    fun anUpdateOfARegisteredKeySkipsTheIndexAndOnlyTheFirstWriteRegisters() {
        val storage = CountingStore()
        val persistence = SharedThreadComposerDraftPersistence(storage)

        val first = persistence.writeJournal(entry(seq = 1L))
        val indexReadsAfterFirst = storage.indexReads
        val second = persistence.writeJournal(entry(seq = 2L, text = "hello!"))
        val third = persistence.writeJournal(entry(seq = 3L, text = "hello!!"))

        assertTrue(first.indexWrite)
        assertFalse(second.indexWrite)
        assertFalse(third.indexWrite)
        assertEquals(indexReadsAfterFirst, storage.indexReads, "updates read the index")
        assertEquals(1, storage.indexWrites)
        // One write per update: the row.
        assertEquals(1 + 3, storage.writes)
        assertEquals("hello!!", persistence.readJournal(draftKey)?.text)
    }

    @Test
    fun theIndexCacheFollowsClearAllAndAnExternalClearSoNoRowIsEverLeftUnlisted() {
        val storage = InMemoryRaftKeyValueStore()
        val persistence = SharedThreadComposerDraftPersistence(storage)
        persistence.writeJournal(entry(seq = 1L))

        // Every draft cleared (another facade on the same store, as the account purge does).
        SharedThreadComposerDraftPersistence(storage).clearJournal()
        assertNull(storage.getString(SharedThreadComposerDraftPersistence.JournalIndexKey))

        val again = persistence.writeJournal(entry(seq = 2L))
        assertTrue(again.indexWrite, "after a clear the key is registered again")
        assertEquals(listOf(draftKey), indexOf(storage))
        // So a later clear-all still finds the row.
        persistence.clearJournal()
        assertNull(storage.getString(SharedThreadComposerDraftPersistence.journalRowKey(draftKey)))
    }

    @Test
    fun theIndexCacheFollowsARolledBackRegistration() {
        val storage = InMemoryRaftKeyValueStore()
        val persistence = SharedThreadComposerDraftPersistence(storage)
        val before = persistence.readJournalRaw(listOf(draftKey))
        persistence.writeJournal(entry(seq = 1L))

        // The transition is rolled back: the row goes back to "none".
        persistence.restoreJournalRaw(before)
        assertNull(storage.getString(SharedThreadComposerDraftPersistence.journalRowKey(draftKey)))

        // The index may still list it (listed without a row is allowed); a new write must leave it listed.
        storage.putString(SharedThreadComposerDraftPersistence.JournalIndexKey, null)
        persistence.restoreJournalRaw(before)
        persistence.writeJournal(entry(seq = 2L))
        assertEquals(listOf(draftKey), indexOf(storage))
    }

    @Test
    fun coexistingComposersEachRegisterTheirOwnKeyOnce() {
        val storage = CountingStore()
        val persistence = SharedThreadComposerDraftPersistence(storage)
        persistence.writeJournal(entry(key = draftKey, seq = 1L))
        persistence.writeJournal(entry(key = threadKey, seq = 2L))
        persistence.writeJournal(entry(key = draftKey, seq = 3L))
        persistence.writeJournal(entry(key = threadKey, seq = 4L))

        assertEquals(2, storage.indexWrites)
        assertEquals(listOf(draftKey, threadKey), indexOf(storage))
        // Another facade of the same store shares the cache and the index.
        SharedThreadComposerDraftPersistence(storage).writeJournal(entry(key = threadKey, seq = 5L))
        assertEquals(2, storage.indexWrites)
    }

    @Test
    fun anAccountSwitchNeverReusesTheOtherAccountsIndexCache() {
        val base = InMemoryRaftKeyValueStore()
        var live: SharedThreadComposerDraftAccount? = accountA
        val view = SharedThreadComposerDraftAccountStore(base, liveAccount = { live })
        val persistence = SharedThreadComposerDraftPersistence(view)
        persistence.writeJournal(entry(seq = 1L, text = "A"))

        live = accountB
        view.rebind(accountB)
        val inB = persistence.writeJournal(entry(seq = 2L, text = "B"))

        assertTrue(inB.indexWrite, "B's index never had the key: it must be registered for B")
        assertEquals(listOf(draftKey), view.getString(SharedThreadComposerDraftPersistence.JournalIndexKey)?.lines())
        // Clearing B's journal clears B's row; A's row and index are untouched.
        persistence.clearJournal()
        assertNull(view.getString(SharedThreadComposerDraftPersistence.journalRowKey(draftKey)))
        live = accountA
        view.rebind(accountA)
        assertEquals("A", persistence.readJournal(draftKey)?.text)
    }

    @Test
    fun anAccountPurgeLeavesNoJournalRowAndTheNextSessionRegistersAgain() {
        val base = CountingStore()
        var live: SharedThreadComposerDraftAccount? = accountA
        val view = SharedThreadComposerDraftAccountStore(base, liveAccount = { live })
        val persistence = SharedThreadComposerDraftPersistence(view)
        val accounts = SharedThreadComposerDraftAccounts(base = base, store = view, persistenceFor = { SharedThreadComposerDraftPersistence(it) })
        val slice = SharedThreadComposerDraftPersistSlice(persistence, scheduler = VirtualDraftWriteClock())
        accounts.onAccountBinding(accountA, slice)
        persistence.writeJournal(entry(seq = 1L, text = "A's text"))

        // Signed out: A's rows are deleted directly (not through this facade's cache).
        live = null
        accounts.onAccountBinding(null, slice)
        assertTrue(base.keys().none { it.contains(":journal") }, "rows left: ${base.keys()}")

        // A signs in again: the cached "registered" must not survive the purge.
        live = accountA
        accounts.onAccountBinding(accountA, slice)
        assertTrue(persistence.writeJournal(entry(seq = 2L, text = "A again")).indexWrite)
        live = null
        accounts.onAccountBinding(null, slice)
        assertTrue(base.keys().none { it.contains(":journal") }, "rows left: ${base.keys()}")
    }

    @Test
    fun sendDeleteAndSwitchingNeverBringOldTextBackWithTheCompactRow() {
        val storage = InMemoryRaftKeyValueStore()
        val clock = VirtualDraftWriteClock()
        val shared = slice(storage, process = 1L, clock = clock)
        val channel = composer(shared, draftKey)
        val thread = composer(shared, threadKey)

        // Send in the channel while the thread's text is only journaled.
        thread.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "reply in progress"))
        channel.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "sent"))
        channel.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        channel.dispatch(SharedThreadComposerDraftIntent.CompleteSend())
        // Delete (type away) in the thread, then switch the channel composer to the thread and back.
        thread.dispatch(SharedThreadComposerDraftIntent.Change(seedText = ""))
        channel.dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "c"), threadKey))
        assertEquals("", channel.state.value.seedText)
        channel.dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "c"), draftKey))
        assertEquals("", channel.state.value.seedText)

        assertEquals("", restart(storage, draftKey))
        assertEquals("", restart(storage, threadKey))
        clock.runEverythingIncludingCancelled()
        assertEquals("", restart(storage, draftKey))
        assertEquals("", restart(storage, threadKey))
    }

    @Test
    fun aKillRightAfterTheJournalWriteRestoresTheLastText() {
        val storage = InMemoryRaftKeyValueStore()
        val store = composer(slice(storage, process = 1L), draftKey)
        "the last text".forEachIndexed { i, _ -> store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "the last text".take(i + 1))) }

        assertNull(SharedThreadComposerDraftPersistence(storage).read(draftKey))
        assertEquals("the last text", restart(storage, draftKey))
    }

    private fun slice(storage: SharedPreferenceStore, process: Long, clock: VirtualDraftWriteClock = VirtualDraftWriteClock()) =
        SharedThreadComposerDraftPersistSlice(
            persistence = SharedThreadComposerDraftPersistence(storage),
            scheduler = clock,
            changeWriteIntervalMillis = 40L,
            monotonicMillis = clock::now,
            journalProcess = process,
        )

    private fun composer(slice: SharedThreadComposerDraftPersistSlice, key: String): SharedThreadComposerDraftStore =
        SharedThreadComposerDraftStore(persistSlices = listOf(slice)).also {
            it.dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "row-$key"), key))
        }

    /** A new process over the same storage. */
    private fun restart(storage: SharedPreferenceStore, key: String): String =
        composer(slice(storage, process = 2L), key).state.value.seedText

    private fun indexOf(storage: SharedPreferenceStore): List<String> =
        storage.getString(SharedThreadComposerDraftPersistence.JournalIndexKey)?.lines()?.filter(String::isNotBlank).orEmpty()

    private class CountingStore : SharedPreferenceStore {
        private val values = mutableMapOf<String, String>()
        var indexReads = 0
        var indexWrites = 0
        var writes = 0

        fun keys(): Set<String> = values.keys.toSet()

        override fun getString(key: String): String? {
            if (key == SharedThreadComposerDraftPersistence.JournalIndexKey) indexReads += 1
            return values[key]
        }

        override fun putString(key: String, value: String?) {
            writes += 1
            if (key == SharedThreadComposerDraftPersistence.JournalIndexKey) indexWrites += 1
            if (value == null) values.remove(key) else values[key] = value
        }
    }
}
