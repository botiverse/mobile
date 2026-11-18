package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.MviPersistSlice
import build.raft.app.data.MviPersistSliceTransitionSnapshot
import build.raft.app.data.SharedPreferenceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Task #246: every typed change is journaled with one store write on the input thread before the full
 * draft write runs in the background.  "Process death" is a fresh slice (another process id) over the
 * same storage, with the background write still unrun.
 */
class SharedThreadComposerDraftCrashJournalTest {
    private val draftKey = "send=channel-journal|parent=_|mention=channel-journal"
    private val otherKey = "send=channel-other|parent=_|mention=channel-other"

    @Test
    fun aKillBeforeTheFullWriteRestoresTheJournaledText() {
        val storage = InMemoryRaftKeyValueStore()
        val costs = mutableListOf<Duration>()
        val store = startStore(storage, VirtualDraftWriteClock(), journalWriteCost = { cost, _ -> costs += cost })

        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "journal only"))

        // The full draft row was never written: only the journal row.
        assertNull(SharedThreadComposerDraftPersistence(storage).read(draftKey))
        assertNotNull(storage.getString(SharedThreadComposerDraftPersistence.JournalKey))
        assertEquals(1, costs.size)
        assertEquals("journal only", restart(storage).seedText)
    }

    @Test
    fun theFullWriteSupersedesTheJournal() {
        val storage = InMemoryRaftKeyValueStore()
        val clock = VirtualDraftWriteClock()
        val store = startStore(storage, clock)

        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "written"))
        clock.advanceBy(0L)

        assertEquals("written", SharedThreadComposerDraftPersistence(storage).read(draftKey)?.text)
        assertNull(storage.getString(SharedThreadComposerDraftPersistence.JournalKey))
        assertEquals("written", restart(storage).seedText)
    }

    @Test
    fun aSentTextIsNeverRestoredFromTheJournal() {
        val storage = InMemoryRaftKeyValueStore()
        val clock = VirtualDraftWriteClock()
        val store = startStore(storage, clock)

        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "the message"))
        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend())
        clock.runEverythingIncludingCancelled()

        assertNull(storage.getString(SharedThreadComposerDraftPersistence.JournalKey))
        assertEquals("", restart(storage).seedText)
    }

    @Test
    fun aDeletedOrClearedDraftIsNeverRestoredFromTheJournal() {
        // Typed away: the journal holds the empty text.
        val typedAway = InMemoryRaftKeyValueStore()
        val first = startStore(typedAway, VirtualDraftWriteClock())
        first.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "gone soon"))
        first.dispatch(SharedThreadComposerDraftIntent.Change(seedText = ""))
        assertEquals("", restart(typedAway).seedText)

        // Deleted elsewhere (e.g. the Drafts list) through another facade: the journal goes with it.
        val deleted = InMemoryRaftKeyValueStore()
        val second = startStore(deleted, VirtualDraftWriteClock())
        second.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "delete me"))
        SharedThreadComposerDraftPersistence(deleted).clear(draftKey)
        assertNull(deleted.getString(SharedThreadComposerDraftPersistence.JournalKey))
        assertEquals("", restart(deleted).seedText)
    }

    @Test
    fun theJournalNeverRestoresIntoAnotherConversation() {
        val storage = InMemoryRaftKeyValueStore()
        val store = startStore(storage, VirtualDraftWriteClock())
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "for this channel"))

        assertEquals("", restart(storage, otherKey).seedText)
        assertEquals("for this channel", restart(storage, draftKey).seedText)
    }

    @Test
    fun aStaleJournalOfAnOlderGenerationInThisProcessIsIgnored() {
        val storage = InMemoryRaftKeyValueStore()
        val process = 42L
        val slice = slice(storage, VirtualDraftWriteClock(), process = process)
        // Opening the composer moves this key's generation past 0 (Start flushes the key).
        open(slice, draftKey)
        SharedThreadComposerDraftPersistence(storage).writeJournal(
            SharedThreadComposerDraftJournalEntry(draftKey, seq = 1L, generation = 0L, process = process, text = "stale", asTask = false)
        )

        assertEquals("", open(slice, draftKey).seedText)
        // The same entry from another process (one that died) is applied.
        assertEquals("stale", restart(storage).seedText)
    }

    @Test
    fun aRolledBackChangeLeavesNoJournal() {
        val storage = InMemoryRaftKeyValueStore()
        val failing =
            object : MviPersistSlice<SharedThreadComposerDraftState, SharedThreadComposerDraftIntent> {
                override fun applyTransition(
                    previous: SharedThreadComposerDraftState,
                    intent: SharedThreadComposerDraftIntent,
                    next: SharedThreadComposerDraftState,
                    snapshot: MviPersistSliceTransitionSnapshot?
                ) {
                    if (intent is SharedThreadComposerDraftIntent.Change) throw IllegalStateException("later_slice_failed")
                }
            }
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(slice(storage, VirtualDraftWriteClock()), failing))
        store.dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "composer-journal"), draftKey))

        val failure = runCatching { store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "never committed")) }.exceptionOrNull()

        assertTrue(failure != null || store.state.value.seedText != "never committed")
        assertNull(storage.getString(SharedThreadComposerDraftPersistence.JournalKey))
        assertEquals("", restart(storage).seedText)
    }

    private fun slice(
        storage: SharedPreferenceStore,
        clock: VirtualDraftWriteClock,
        process: Long = 1L,
        journalWriteCost: ((Duration, Int) -> Unit)? = null,
    ) = SharedThreadComposerDraftPersistSlice(
        persistence = SharedThreadComposerDraftPersistence(storage),
        scheduler = clock,
        changeWriteIntervalMillis = 40L,
        monotonicMillis = clock::now,
        journalWriteCost = journalWriteCost,
        journalProcess = process,
    )

    private fun open(slice: SharedThreadComposerDraftPersistSlice, key: String): SharedThreadComposerDraftState {
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(slice))
        store.dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "composer-journal"), key))
        return store.state.value
    }

    private fun startStore(
        storage: SharedPreferenceStore,
        clock: VirtualDraftWriteClock,
        journalWriteCost: ((Duration, Int) -> Unit)? = null,
    ): SharedThreadComposerDraftStore {
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(slice(storage, clock, journalWriteCost = journalWriteCost)))
        store.dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "composer-journal"), draftKey))
        return store
    }

    /** A new process: another process id, a fresh facade and slice over the same storage. */
    private fun restart(storage: SharedPreferenceStore, key: String = draftKey): SharedThreadComposerDraftState =
        open(slice(storage, VirtualDraftWriteClock(), process = 2L), key)
}
