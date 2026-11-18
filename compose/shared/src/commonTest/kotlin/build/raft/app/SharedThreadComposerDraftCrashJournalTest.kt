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
    /** A reply thread of the same channel: its own draft key. */
    private val threadKey = "send=channel-journal|parent=message-7|mention=channel-journal"

    @Test
    fun aKillBeforeTheFullWriteRestoresTheJournaledText() {
        val storage = InMemoryRaftKeyValueStore()
        val costs = mutableListOf<Duration>()
        val store = startStore(storage, VirtualDraftWriteClock(), journalWriteCost = { cost, _ -> costs += cost })

        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "journal only"))

        // The full draft row was never written: only the journal row.
        assertNull(SharedThreadComposerDraftPersistence(storage).read(draftKey))
        assertNotNull(storage.getString(journalRow(draftKey)))
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
        assertNull(storage.getString(journalRow(draftKey)))
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

        assertNull(storage.getString(journalRow(draftKey)))
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
        assertNull(deleted.getString(journalRow(draftKey)))
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
        assertNull(storage.getString(journalRow(draftKey)))
        assertEquals("", restart(storage).seedText)
    }

    /**
     * Two composers open at once on one slice (as production shares one slice between every composer:
     * a channel and its reply thread, or two windows), both typing while their background writes are
     * delayed (the clock never runs): a kill restores each conversation's own newest text.
     */
    @Test
    fun twoCoexistingComposersWithDelayedWritesBothSurviveAKillInTheirOwnConversation() {
        val storage = InMemoryRaftKeyValueStore()
        val clock = VirtualDraftWriteClock()
        val shared = slice(storage, clock)
        val channel = composer(shared, draftKey, "channel")
        val thread = composer(shared, threadKey, "thread")

        channel.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "channel draft"))
        thread.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "thread reply"))
        channel.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "channel draft, newest"))

        // Nothing durable yet: only the two journal rows.
        assertNull(SharedThreadComposerDraftPersistence(storage).read(draftKey))
        assertNull(SharedThreadComposerDraftPersistence(storage).read(threadKey))
        assertEquals("channel draft, newest", restart(storage, draftKey).seedText)
        assertEquals("thread reply", restart(storage, threadKey).seedText)
        assertEquals("", restart(storage, otherKey).seedText)
    }

    @Test
    fun oneComposersBackgroundWriteNeverDropsAnotherComposersJournal() {
        val storage = InMemoryRaftKeyValueStore()
        val clock = VirtualDraftWriteClock()
        val shared = slice(storage, clock)
        val channel = composer(shared, draftKey, "channel")
        val thread = composer(shared, threadKey, "thread")

        channel.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "channel written"))
        thread.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "thread pending"))
        // Both writes land in order: the channel's first, while the thread's journal is still the only copy
        // of its text, then the thread's.  Each clears only its own row.
        clock.advanceBy(0L)
        assertEquals("channel written", SharedThreadComposerDraftPersistence(storage).read(draftKey)?.text)
        assertNull(storage.getString(journalRow(draftKey)))
        assertNull(storage.getString(journalRow(threadKey)))

        // Both type again inside the write interval (their writes are delayed, the clock does not run);
        // the channel's write then lands (Flush) and must leave the thread's journal alone.
        thread.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "thread pending, newer"))
        channel.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "channel newer"))
        // The thread's durable draft is still the older text: its newest text is only in its journal.
        assertEquals("thread pending", SharedThreadComposerDraftPersistence(storage).read(threadKey)?.text)
        channel.dispatch(SharedThreadComposerDraftIntent.Flush)
        assertEquals("channel newer", SharedThreadComposerDraftPersistence(storage).read(draftKey)?.text)
        assertNull(storage.getString(journalRow(draftKey)))
        assertNotNull(storage.getString(journalRow(threadKey)))

        assertEquals("channel newer", restart(storage, draftKey).seedText)
        assertEquals("thread pending, newer", restart(storage, threadKey).seedText)
    }

    @Test
    fun aSendInOneComposerClearsOnlyItsOwnJournal() {
        val storage = InMemoryRaftKeyValueStore()
        val clock = VirtualDraftWriteClock()
        val shared = slice(storage, clock)
        val channel = composer(shared, draftKey, "channel")
        val thread = composer(shared, threadKey, "thread")

        thread.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "reply still typing"))
        channel.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "sent to the channel"))
        channel.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        channel.dispatch(SharedThreadComposerDraftIntent.CompleteSend())

        assertNull(storage.getString(journalRow(draftKey)))
        assertEquals("", restart(storage, draftKey).seedText)
        assertEquals("reply still typing", restart(storage, threadKey).seedText)
    }

    /** Switching one composer from the channel to a thread (and back) never carries text across. */
    @Test
    fun switchingConversationThenAKillRestoresEachTextOnlyInItsOwnConversation() {
        val storage = InMemoryRaftKeyValueStore()
        val clock = VirtualDraftWriteClock()
        val shared = slice(storage, clock)
        val store = composer(shared, draftKey, "switching")

        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "channel text"))
        store.dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "switching"), threadKey))
        assertEquals("", store.state.value.seedText)
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "thread text"))
        store.dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "switching"), otherKey))
        assertEquals("", store.state.value.seedText)

        assertEquals("channel text", restart(storage, draftKey).seedText)
        assertEquals("thread text", restart(storage, threadKey).seedText)
        assertEquals("", restart(storage, otherKey).seedText)
    }

    @Test
    fun switchingAwayAfterSendingNeverBringsTheSentTextBack() {
        val storage = InMemoryRaftKeyValueStore()
        val clock = VirtualDraftWriteClock()
        val store = composer(slice(storage, clock), draftKey, "switching")

        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "sent then left"))
        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend())
        store.dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "switching"), threadKey))
        clock.runEverythingIncludingCancelled()

        assertEquals("", restart(storage, draftKey).seedText)
        assertEquals("", restart(storage, threadKey).seedText)
    }

    /** A restored journal becomes the durable draft at once (Drafts list, later exits) and its row goes. */
    @Test
    fun aRestoredJournalIsWrittenAsTheDraftAndThenDropped() {
        val storage = InMemoryRaftKeyValueStore()
        val store = startStore(storage, VirtualDraftWriteClock())
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "only in the journal"))

        assertEquals("only in the journal", restart(storage).seedText)

        assertEquals("only in the journal", SharedThreadComposerDraftPersistence(storage).read(draftKey)?.text)
        assertNull(storage.getString(journalRow(draftKey)))
        assertEquals("only in the journal", restart(storage).seedText)
    }

    @Test
    fun clearingEveryJournalDropsEachRowAndTheirList() {
        val storage = InMemoryRaftKeyValueStore()
        val shared = slice(storage, VirtualDraftWriteClock())
        composer(shared, draftKey, "channel").dispatch(SharedThreadComposerDraftIntent.Change(seedText = "a"))
        composer(shared, threadKey, "thread").dispatch(SharedThreadComposerDraftIntent.Change(seedText = "b"))

        SharedThreadComposerDraftPersistence(storage).clearJournal()

        assertNull(storage.getString(journalRow(draftKey)))
        assertNull(storage.getString(journalRow(threadKey)))
        assertNull(storage.getString(SharedThreadComposerDraftPersistence.JournalIndexKey))
    }

    private fun journalRow(key: String) = SharedThreadComposerDraftPersistence.journalRowKey(key)

    private fun composer(slice: SharedThreadComposerDraftPersistSlice, key: String, instance: String): SharedThreadComposerDraftStore {
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(slice))
        store.dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = instance), key))
        return store
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
